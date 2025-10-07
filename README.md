# hassAIClient

A modern Android assistant experience for Home Assistant users, backed by an MCP-compatible relay that streams responses from OpenRouter with Gemini Flash 2.5 by default.

## Project structure

- `android/` – Jetpack Compose Android app that registers as an assistant and launches from the power button. The UI defaults to voice capture, streams responses in real time, and persists conversation context per session.
- `backend/` – FastAPI server that brokers chat requests to OpenRouter and attaches a Home Assistant MCP server over SSE using [`fastmcp`](https://pypi.org/project/fastmcp/).

## Android client

* Built with Android Gradle Plugin 8.6, Kotlin 2.0, and Material 3 Compose UI.
* Implements a `VoiceInteractionService` so it can be set as the system assistant (long-press power key).
* Voice capture is on by default via `SpeechRecognizer`; partial transcriptions fill the text composer automatically.
* Streams assistant tokens from the backend over SSE and renders them incrementally in a chat layout.

### Configuration

Update `android/app/build.gradle.kts` with the backend URL/token or override them at runtime with build config fields:

```kotlin
buildConfigField("String", "BACKEND_URL", "\"http://10.0.2.2:8000\"")
buildConfigField("String", "BACKEND_TOKEN", "\"local-demo-token\"")
```

Build with the included Gradle wrapper:

```bash
cd android
./gradlew assembleDebug
```

Install the resulting APK and set **hassAI Client** as the default assistant in system settings.

## Python backend

The backend exposes a single `/chat` SSE endpoint secured by a bearer token. It:

1. Validates the request token.
2. Forwards the conversation to OpenRouter (default model `google/gemini-2.0-flash-001`, aliased as Gemini Flash 2.5).
3. Streams chunks back to the Android client while handling tool calls.
4. Uses `fastmcp` to connect to a Home Assistant MCP server over SSE (bearer auth) and forwards any tool calls the model triggers.

### Environment variables

| Variable | Purpose |
| --- | --- |
| `BACKEND_ACCESS_TOKEN` | Token expected from the Android client (defaults to `local-demo-token`). |
| `OPENROUTER_API_KEY` | Required OpenRouter API key. |
| `OPENROUTER_MODEL` | Optional override for the default Gemini Flash model. |
| `OPENROUTER_BASE_URL` | Override OpenRouter endpoint (defaults to `https://openrouter.ai/api/v1`). |
| `OPENROUTER_REFERER` / `OPENROUTER_TITLE` | Metadata headers required by OpenRouter. |
| `HOME_ASSISTANT_MCP_URL` | URL of the Home Assistant MCP SSE endpoint. |
| `HOME_ASSISTANT_MCP_TOKEN` | Bearer token for Home Assistant MCP. |

### Running locally

Create a virtual environment and install dependencies:

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Start the API (requires the environment variables above):

```bash
uvicorn main:app --reload --port 8000
```

The Android client expects the backend at `http://10.0.2.2:8000/chat` when running in an emulator.

## Development tips

- The backend returns SSE events shaped as `{ "type": "token" | "status" | "complete" | "error", "content": "..." }` to support streaming UI updates.
- The app keeps voice capture running unless the microphone permission is denied. Tapping the floating microphone action restarts listening.

