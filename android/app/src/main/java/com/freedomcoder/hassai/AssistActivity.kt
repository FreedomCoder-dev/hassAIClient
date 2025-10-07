package com.freedomcoder.hassai

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.freedomcoder.hassai.assist.VoiceRecognitionManager
import com.freedomcoder.hassai.data.AssistantRepository
import com.freedomcoder.hassai.model.ChatMessage
import com.freedomcoder.hassai.ui.AssistUiState
import com.freedomcoder.hassai.ui.AssistViewModel
import com.freedomcoder.hassai.ui.components.ChatBubble
import com.freedomcoder.hassai.ui.theme.HassAIClientTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AssistActivity : ComponentActivity() {

    private val viewModel: AssistViewModel by viewModels {
        viewModelFactory {
            initializer {
                val repository = AssistantRepository(
                    client = OkHttpClient.Builder().build(),
                    baseUrl = BuildConfig.BACKEND_URL,
                    accessToken = BuildConfig.BACKEND_TOKEN,
                )
                AssistViewModel(repository)
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HassAIClientTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val permissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

                var voiceManager by remember { mutableStateOf<VoiceRecognitionManager?>(null) }

                LaunchedEffect(permissionState.status) {
                    if (permissionState.status is PermissionStatus.Granted) {
                        if (voiceManager == null) {
                            voiceManager = VoiceRecognitionManager(
                                context = context,
                                onResult = { text, isFinal -> viewModel.onTranscription(text, isFinal) },
                                onError = { error ->
                                    scope.launch { snackbarHostState.showSnackbar(error) }
                                },
                            ).also { it.startListening() }
                        } else {
                            voiceManager?.startListening()
                        }
                        viewModel.toggleListening(true)
                    } else {
                        viewModel.toggleListening(false)
                    }
                }

                LaunchedEffect(Unit) {
                    if (permissionState.status !is PermissionStatus.Granted) {
                        permissionState.launchPermissionRequest()
                    }
                }

                DisposableEffect(Unit) {
                    onDispose { voiceManager?.destroy() }
                }

                AssistScaffold(
                    state = uiState,
                    snackbarHostState = snackbarHostState,
                    onTextChanged = viewModel::updateInput,
                    onSend = viewModel::sendMessage,
                    onRetryVoice = {
                        if (permissionState.status is PermissionStatus.Granted) {
                            voiceManager?.startListening()
                            viewModel.toggleListening(true)
                        } else {
                            permissionState.launchPermissionRequest()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AssistScaffold(
    state: AssistUiState,
    snackbarHostState: SnackbarHostState,
    onTextChanged: (String) -> Unit,
    onSend: (String) -> Unit,
    onRetryVoice: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onRetryVoice) {
                Icon(imageVector = Icons.Filled.Mic, contentDescription = stringResource(id = R.string.microphone))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { paddingValues ->
        AssistContent(
            state = state,
            onTextChanged = onTextChanged,
            onSend = onSend,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

@Composable
private fun AssistContent(
    state: AssistUiState,
    onTextChanged: (String) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        state.status?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Column(modifier = Modifier.weight(1f, fill = true)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 16.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatBubble(message = message)
                }
            }
        }

        AnimatedVisibility(visible = state.isListening, enter = fadeIn(), exit = fadeOut()) {
            ListeningHint()
        }

        Spacer(modifier = Modifier.height(8.dp))

        MessageComposer(
            value = state.inputText,
            onValueChange = onTextChanged,
            onSend = onSend,
            enabled = !state.isStreaming,
        )

        AnimatedVisibility(visible = state.error != null, enter = fadeIn(), exit = fadeOut()) {
            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ListeningHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraLarge,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(id = R.string.voice_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        placeholder = { Text(text = stringResource(id = R.string.tap_to_speak)) },
        enabled = enabled,
        trailingIcon = {
            IconButton(onClick = { onSend(value) }, enabled = enabled && value.isNotBlank()) {
                Icon(imageVector = Icons.Default.Send, contentDescription = stringResource(id = R.string.send))
            }
        },
        singleLine = false,
        maxLines = 4,
    )
}
