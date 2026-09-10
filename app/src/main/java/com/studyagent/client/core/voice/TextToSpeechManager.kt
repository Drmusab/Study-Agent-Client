package com.studyagent.client.core.voice

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

interface TextToSpeechManager {
    val isSpeaking: StateFlow<Boolean>
    val isInitialized: StateFlow<Boolean>

    fun speak(
        text: String,
        flushQueue: Boolean = true,
        utteranceId: String = "study_tts",
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    )

    fun stop()
    fun setLanguage(locale: Locale): Boolean
    fun setSpeechRate(rate: Float)
    fun setPitch(pitch: Float)
    fun release()
}
