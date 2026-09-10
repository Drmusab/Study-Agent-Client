package com.studyagent.client.core.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AndroidTextToSpeechManager(
    private val context: Context
) : TextToSpeechManager, TextToSpeech.OnInitListener {

    private val tag = "TextToSpeechMgr"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var targetLocale: Locale = Locale.US
    private var pendingRate: Float = 1.0f
    private var pendingPitch: Float = 1.0f

    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val errorCallbacks = ConcurrentHashMap<String, (String) -> Unit>()

    init {
        mainHandler.post {
            try {
                tts = TextToSpeech(context, this)
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed to initialize Android TextToSpeech: ${e.message}", e)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            AppLogger.i(tag, "TextToSpeech initialized successfully")
            tts?.let { engine ->
                val result = engine.setLanguage(targetLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    AppLogger.w(tag, "Locale $targetLocale is missing data or not supported")
                }
                engine.setSpeechRate(pendingRate)
                engine.setPitch(pendingPitch)
                engine.setOnUtteranceProgressListener(createProgressListener())
            }
            _isInitialized.value = true
        } else {
            AppLogger.e(tag, "TextToSpeech initialization failed with status $status")
            _isInitialized.value = false
        }
    }

    private fun createProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                AppLogger.d(tag, "TTS started utterance: $utteranceId")
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                AppLogger.d(tag, "TTS completed utterance: $utteranceId")
                _isSpeaking.value = false
                if (utteranceId != null) {
                    completionCallbacks.remove(utteranceId)?.let { cb ->
                        mainHandler.post { cb.invoke() }
                    }
                    errorCallbacks.remove(utteranceId)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, -1)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                AppLogger.e(tag, "TTS error on utterance: $utteranceId (code: $errorCode)")
                _isSpeaking.value = false
                if (utteranceId != null) {
                    errorCallbacks.remove(utteranceId)?.let { cb ->
                        mainHandler.post { cb.invoke("TTS playback error ($errorCode)") }
                    }
                    completionCallbacks.remove(utteranceId)
                }
            }
        }
    }

    override fun speak(
        text: String,
        flushQueue: Boolean,
        utteranceId: String,
        onDone: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }

        if (onDone != null) {
            completionCallbacks[utteranceId] = onDone
        }
        if (onError != null) {
            errorCallbacks[utteranceId] = onError
        }

        mainHandler.post {
            val engine = tts
            if (engine == null || !_isInitialized.value) {
                AppLogger.w(tag, "TTS engine not ready yet to speak: $text")
                errorCallbacks.remove(utteranceId)?.invoke("TTS engine not initialized")
                return@post
            }

            val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            AppLogger.d(tag, "Speaking: '$text' (utteranceId=$utteranceId)")
            val res = engine.speak(text, queueMode, params, utteranceId)
            if (res != TextToSpeech.SUCCESS) {
                AppLogger.e(tag, "TTS speak failed with result code $res")
                errorCallbacks.remove(utteranceId)?.invoke("TTS speak invocation failed ($res)")
            }
        }
    }

    override fun stop() {
        mainHandler.post {
            try {
                tts?.stop()
            } catch (e: Exception) {
                AppLogger.w(tag, "Error stopping TTS: ${e.message}")
            }
            completionCallbacks.clear()
            errorCallbacks.clear()
            _isSpeaking.value = false
        }
    }

    override fun setLanguage(locale: Locale): Boolean {
        this.targetLocale = locale
        val engine = tts ?: return false
        val res = engine.setLanguage(locale)
        val supported = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        AppLogger.i(tag, "Setting TTS language to $locale, supported=$supported")
        return supported
    }

    override fun setSpeechRate(rate: Float) {
        this.pendingRate = rate
        tts?.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        this.pendingPitch = pitch
        tts?.setPitch(pitch)
    }

    override fun release() {
        mainHandler.post {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {
                AppLogger.w(tag, "Error releasing TTS: ${e.message}")
            }
            tts = null
            _isInitialized.value = false
            _isSpeaking.value = false
        }
    }
}
