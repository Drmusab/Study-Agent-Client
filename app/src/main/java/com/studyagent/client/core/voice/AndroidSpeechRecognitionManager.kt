package com.studyagent.client.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class AndroidSpeechRecognitionManager(
    private val context: Context
) : SpeechRecognitionManager {

    private val tag = "SpeechRecMgr"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognitionEvents = MutableSharedFlow<SpeechRecognitionResult>(extraBufferCapacity = 64)
    override val recognitionEvents: SharedFlow<SpeechRecognitionResult> = _recognitionEvents.asSharedFlow()

    private var activeLanguage: String = "en-US"

    private fun ensureRecognizerOnMainThread(onReady: (SpeechRecognizer) -> Unit) {
        mainHandler.post {
            if (speechRecognizer == null) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    AppLogger.e(tag, "Speech recognition is not available on this device")
                    _recognitionEvents.tryEmit(SpeechRecognitionResult.Error(-1, "Speech recognition unavailable"))
                    return@post
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            }
            speechRecognizer?.let(onReady)
        }
    }

    override fun startListening(languageCode: String, isHandsFree: Boolean) {
        this.activeLanguage = languageCode
        ensureRecognizerOnMainThread { recognizer ->
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (isHandsFree) {
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    } else {
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    }
                }

                AppLogger.d(tag, "Starting speech recognition (lang=$languageCode, handsFree=$isHandsFree)")
                recognizer.startListening(intent)
                _isListening.value = true
                _recognitionEvents.tryEmit(SpeechRecognitionResult.ListeningStateChanged(true))
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed to start speech recognition: ${e.message}", e)
                _isListening.value = false
                _recognitionEvents.tryEmit(SpeechRecognitionResult.Error(-2, e.message ?: "Failed to start"))
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                AppLogger.w(tag, "Error stopping recognizer: ${e.message}")
            }
            _isListening.value = false
        }
    }

    override fun cancel() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                AppLogger.w(tag, "Error cancelling recognizer: ${e.message}")
            }
            _isListening.value = false
            _recognitionEvents.tryEmit(SpeechRecognitionResult.ListeningStateChanged(false))
        }
    }

    override fun release() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                AppLogger.w(tag, "Error destroying recognizer: ${e.message}")
            }
            speechRecognizer = null
            _isListening.value = false
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                AppLogger.d(tag, "Speech recognizer ready for speech")
                _isListening.value = true
                _recognitionEvents.tryEmit(SpeechRecognitionResult.ListeningStateChanged(true))
            }

            override fun onBeginningOfSpeech() {
                AppLogger.d(tag, "User began speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                _recognitionEvents.tryEmit(SpeechRecognitionResult.RmsChanged(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                AppLogger.d(tag, "End of speech detected")
                _isListening.value = false
                _recognitionEvents.tryEmit(SpeechRecognitionResult.ListeningStateChanged(false))
            }

            override fun onError(error: Int) {
                _isListening.value = false
                val errorMsg = mapErrorCode(error)
                AppLogger.w(tag, "Speech recognition error code=$error ($errorMsg)")
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    _recognitionEvents.tryEmit(SpeechRecognitionResult.NoSpeech)
                } else {
                    _recognitionEvents.tryEmit(SpeechRecognitionResult.Error(error, errorMsg))
                }
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val topMatch = matches?.firstOrNull()?.trim()
                AppLogger.i(tag, "Final recognition result: '$topMatch'")
                if (!topMatch.isNullOrEmpty()) {
                    _recognitionEvents.tryEmit(SpeechRecognitionResult.Final(topMatch))
                } else {
                    _recognitionEvents.tryEmit(SpeechRecognitionResult.NoSpeech)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val topPartial = matches?.firstOrNull()?.trim()
                if (!topPartial.isNullOrEmpty()) {
                    AppLogger.d(tag, "Partial recognition result: '$topPartial'")
                    _recognitionEvents.tryEmit(SpeechRecognitionResult.Partial(topPartial))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun mapErrorCode(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side recognition error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout during speech recognition"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected within timeout"
            else -> "Speech recognition error ($errorCode)"
        }
    }
}
