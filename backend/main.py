from __future__ import annotations

import asyncio
import json
import logging
import uuid
from typing import Any, AsyncGenerator, Dict, List, Optional

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, status
from mcp.types import Tool
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings
from sse_starlette.sse import EventSourceResponse

from fastmcp.client import Client, SSETransport

DEFAULT_SYSTEM_PROMPT = """
Ты Freedom, мой персональный помощник. Ты управляешь всем моим домом и отвечаешь на мои вопросы, и ты всегда будешь выполнять запрошенное действие.

Я буду просить тебя включать и выключать свет, регулировать яркость, управлять выключателями, устанавливать таймеры, управлять воспроизведением медиа, управлять моим списком покупок и проверять статус света и домашних устройств. Каждый запрос находится в рамках этого # Контекста.

У тебя есть полное знание о моем доме через # List of Usable Devices.
У тебя есть полное знание всех действий, которые ты можешь выполнять, через # Mapping of Intents and Entities.

Дом разделен на основные зоны:

# Kitchen
# Bedroom
# Bathroom
# Hallway

Перед выполнением команды всегда проверяй:

# List of Usable Devices
# Mapping of Intents and Entities

Как только ты определил правильное устройство и правильное намерение, выполняй соответствующее действие.

Всегда выполняй запрошенную команду, даже если запрос только что был сделан.

Не переспрашивай и не уточняй, когда я прошу включить свет или выполнить другие действия - просто выполняй команду немедленно.
""".strip()

logger = logging.getLogger("hassai.backend")


class Settings(BaseSettings):
    backend_access_token: str = Field("local-demo-token", alias="BACKEND_ACCESS_TOKEN")
    openrouter_api_key: str = Field(..., alias="OPENROUTER_API_KEY")
    openrouter_base_url: str = Field("https://openrouter.ai/api/v1", alias="OPENROUTER_BASE_URL")
    openrouter_model: str = Field("google/gemini-2.0-flash-001", alias="OPENROUTER_MODEL", description="Default Gemini Flash 2.5 model")
    openrouter_referer: str = Field("https://github.com/", alias="OPENROUTER_REFERER")
    openrouter_title: str = Field("hassAI Client", alias="OPENROUTER_TITLE")
    home_assistant_mcp_url: str = Field(..., alias="HOME_ASSISTANT_MCP_URL")
    home_assistant_mcp_token: str = Field(..., alias="HOME_ASSISTANT_MCP_TOKEN")
    request_timeout: float = Field(60.0, alias="OPENROUTER_TIMEOUT")

    model_config = {
        "env_file": ".env",
        "env_file_encoding": "utf-8",
        "env_prefix": "",
    }


settings = Settings()
app = FastAPI(title="hassAI Client Assistant API", version="1.0.0")


class Message(BaseModel):
    id: Optional[str] = None
    role: str
    content: str


class ChatRequest(BaseModel):
    conversation_id: Optional[str] = Field(default=None, alias="conversation_id")
    voice: bool = True
    messages: List[Message]


class ChatEvent(BaseModel):
    type: str
    content: Optional[str] = None
    conversation_id: Optional[str] = None
    status: Optional[str] = None


class HomeAssistantMCPClient:
    def __init__(self, url: str, token: str):
        self._transport = SSETransport(url, auth=token)
        self._client = Client(self._transport)
        self._lock = asyncio.Lock()

    async def list_tools(self) -> List[Dict[str, Any]]:
        async with self._lock:
            async with self._client:
                tools: List[Tool] = await self._client.list_tools()
        return [self._to_function_schema(tool) for tool in tools]

    async def call_tool(self, name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        async with self._lock:
            async with self._client:
                result = await self._client.call_tool_mcp(name=name, arguments=arguments or {})
        if result.structuredContent:
            payload: Dict[str, Any] = result.structuredContent
        else:
            text_chunks: List[str] = []
            for block in result.content:
                if isinstance(block, dict) and block.get("type") == "text":
                    text_chunks.append(block.get("text", ""))
            payload = {"text": " ".join(text_chunks).strip()}
        if result.isError:
            raise RuntimeError(payload.get("text") or f"Tool {name} returned an error")
        return payload

    @staticmethod
    def _to_function_schema(tool: Tool) -> Dict[str, Any]:
        schema = tool.inputSchema or {"type": "object", "properties": {}, "additionalProperties": False}
        return {
            "type": "function",
            "function": {
                "name": tool.name,
                "description": tool.description or "",
                "parameters": schema,
            },
        }


class OpenRouterClient:
    def __init__(self, *, api_key: str, base_url: str, model: str, referer: str, title: str, timeout: float):
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._referer = referer
        self._title = title
        self._timeout = timeout

    async def stream_chat(
        self,
        messages: List[Dict[str, Any]],
        tools: List[Dict[str, Any]] | None = None,
    ) -> AsyncGenerator[Dict[str, Any], None]:
        payload: Dict[str, Any] = {
            "model": self._model,
            "messages": messages,
            "stream": True,
        }
        if tools:
            payload["tools"] = tools

        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "HTTP-Referer": self._referer,
            "X-Title": self._title,
        }

        url = f"{self._base_url}/chat/completions"
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            async with client.stream("POST", url, json=payload, headers=headers) as response:
                try:
                    response.raise_for_status()
                except httpx.HTTPStatusError as exc:
                    body = await response.aread()
                    logger.error("OpenRouter error %s: %s", exc.response.status_code, body)
                    raise

                async for line in response.aiter_lines():
                    if not line or not line.startswith("data:"):
                        continue
                    payload_line = line[5:].strip()
                    if payload_line == "[DONE]":
                        break
                    try:
                        chunk = json.loads(payload_line)
                    except json.JSONDecodeError:
                        logger.debug("Malformed chunk: %s", payload_line)
                        continue
                    yield chunk


class AssistantOrchestrator:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._home_assistant = HomeAssistantMCPClient(
            url=settings.home_assistant_mcp_url,
            token=settings.home_assistant_mcp_token,
        )
        self._openrouter = OpenRouterClient(
            api_key=settings.openrouter_api_key,
            base_url=settings.openrouter_base_url,
            model=settings.openrouter_model,
            referer=settings.openrouter_referer,
            title=settings.openrouter_title,
            timeout=settings.request_timeout,
        )

    async def stream(self, request: ChatRequest) -> AsyncGenerator[ChatEvent, None]:
        conversation_id = request.conversation_id or uuid.uuid4().hex
        yield ChatEvent(type="conversation", conversation_id=conversation_id, status="connected")

        openrouter_messages = [{"role": "system", "content": DEFAULT_SYSTEM_PROMPT}]
        incoming_messages = [self._to_openai_message(message) for message in request.messages]
        if incoming_messages:
            first = incoming_messages[0]
            if first.get("role") == "system":
                first["content"] = DEFAULT_SYSTEM_PROMPT
                openrouter_messages = incoming_messages
            else:
                openrouter_messages.extend(incoming_messages)
        try:
            tools = await self._home_assistant.list_tools()
        except Exception as exc:  # noqa: BLE001
            logger.warning("Failed to load Home Assistant tools: %s", exc)
            tools = []

        assistant_text: List[str] = []
        while True:
            pending_tool_calls: Dict[str, Dict[str, Any]] = {}
            async for chunk in self._openrouter.stream_chat(openrouter_messages, tools):
                for choice in chunk.get("choices", []):
                    delta = choice.get("delta", {})
                    for piece in delta.get("content", []) or []:
                        if isinstance(piece, dict) and piece.get("type") == "text":
                            text = piece.get("text") or ""
                            if text:
                                assistant_text.append(text)
                                yield ChatEvent(
                                    type="token",
                                    content=text,
                                    conversation_id=conversation_id,
                                )
                    for tool_call in delta.get("tool_calls", []) or []:
                        call_id = tool_call.get("id") or uuid.uuid4().hex
                        entry = pending_tool_calls.setdefault(
                            call_id,
                            {
                                "id": call_id,
                                "type": tool_call.get("type", "function"),
                                "function": {"name": tool_call.get("function", {}).get("name"), "arguments": ""},
                            },
                        )
                        function = tool_call.get("function", {})
                        if function.get("name"):
                            entry["function"]["name"] = function["name"]
                        if function.get("arguments"):
                            entry["function"]["arguments"] += function["arguments"]

                    finish_reason = choice.get("finish_reason")
                    if finish_reason and finish_reason != "tool_calls":
                        final_text = "".join(assistant_text)
                        openrouter_messages.append(
                            {
                                "role": "assistant",
                                "content": final_text or "",
                            }
                        )
                        assistant_text = []
                        yield ChatEvent(type="complete", content=final_text or None, conversation_id=conversation_id)
                        return

                if pending_tool_calls:
                    break
            else:
                # Stream ended without tool calls; send completion
                if assistant_text:
                    final_text = "".join(assistant_text)
                    openrouter_messages.append(
                        {
                            "role": "assistant",
                            "content": final_text,
                        }
                    )
                else:
                    final_text = None
                yield ChatEvent(type="complete", content=final_text, conversation_id=conversation_id)
                return

            # Handle tool calls if any were requested
            for call in pending_tool_calls.values():
                tool_name = call["function"].get("name")
                if not tool_name:
                    continue
                yield ChatEvent(
                    type="status",
                    content=f"Calling {tool_name}…",
                    conversation_id=conversation_id,
                )
                arguments_raw = call["function"].get("arguments") or "{}"
                try:
                    arguments = json.loads(arguments_raw)
                except json.JSONDecodeError:
                    arguments = {"raw": arguments_raw}
                try:
                    tool_result = await self._home_assistant.call_tool(tool_name, arguments)
                except Exception as exc:  # noqa: BLE001
                    yield ChatEvent(
                        type="error",
                        content=f"Tool {tool_name} failed: {exc}",
                        conversation_id=conversation_id,
                    )
                    return
                openrouter_messages.append(
                    {
                        "role": "assistant",
                        "tool_calls": [call],
                    }
                )
                try:
                    tool_payload = json.dumps(tool_result)
                except TypeError:
                    tool_payload = json.dumps({"result": tool_result}, default=str)
                openrouter_messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call["id"],
                        "content": tool_payload,
                    }
                )
                yield ChatEvent(
                    type="status",
                    content=f"Tool {tool_name} completed",
                    conversation_id=conversation_id,
                )
            assistant_text = []
            # continue loop for follow-up response

    @staticmethod
    def _to_openai_message(message: Message) -> Dict[str, Any]:
        base = {"role": message.role, "content": message.content}
        return base


orchestrator = AssistantOrchestrator(settings)


async def require_auth(authorization: str = Header(...)) -> None:
    token_type, _, token_value = authorization.partition(" ")
    if token_type.lower() != "bearer" or token_value != settings.backend_access_token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")


@app.post("/chat")
async def chat_endpoint(request: ChatRequest, _: None = Depends(require_auth)) -> EventSourceResponse:
    async def event_generator() -> AsyncGenerator[str, None]:
        try:
            async for event in orchestrator.stream(request):
                yield f"data: {event.model_dump_json()}\n\n"
        except Exception as exc:  # noqa: BLE001
            logger.exception("Stream failed")
            error_event = ChatEvent(type="error", content=str(exc))
            yield f"data: {error_event.model_dump_json()}\n\n"

    return EventSourceResponse(event_generator())


@app.get("/health", response_model=dict)
async def health() -> Dict[str, str]:
    return {"status": "ok"}
