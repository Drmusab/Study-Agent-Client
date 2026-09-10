package com.studyagent.client.core.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SpeechRecognitionManager {
    val isListening: StateFlow<Boolean>
    val recognitionEvents: Flow<SpeechRecognitionResult>

    fun startListening(languageCode: String = "en-US", isHandsFree: Boolean = true)
    fun stopListening()
    fun cancel()
    fun release()
}
