package com.freedomcoder.hassai.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceRecognitionManager(
    context: Context,
    private val onResult: (text: String, isFinal: Boolean) -> Unit,
    private val onError: (String) -> Unit,
) : RecognitionListener {

    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    private val recognizerIntent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
    }

    init {
        speechRecognizer.setRecognitionListener(this)
    }

    fun startListening() {
        try {
            speechRecognizer.startListening(recognizerIntent)
        } catch (error: Throwable) {
            onError(error.message ?: "Unable to start voice recognition")
        }
    }

    fun stopListening() {
        speechRecognizer.stopListening()
    }

    fun destroy() {
        speechRecognizer.destroy()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Ready for speech")
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "Speech started")
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "Speech ended")
    }

    override fun onError(error: Int) {
        onError("Speech recognition error: $error")
    }

    override fun onResults(results: Bundle?) {
        val transcripts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!transcripts.isNullOrEmpty()) {
            onResult(transcripts.first(), true)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val transcripts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!transcripts.isNullOrEmpty()) {
            onResult(transcripts.first(), false)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        private const val TAG = "VoiceRecognition"
    }
}
