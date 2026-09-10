package com.studyagent.client.core.voice

sealed interface SpeechRecognitionResult {
    data class Partial(val text: String) : SpeechRecognitionResult
    data class Final(val text: String) : SpeechRecognitionResult
    data object NoSpeech : SpeechRecognitionResult
    data class Error(val errorCode: Int, val errorMessage: String) : SpeechRecognitionResult
    data class ListeningStateChanged(val isListening: Boolean) : SpeechRecognitionResult
    data class RmsChanged(val rmsDb: Float) : SpeechRecognitionResult
}
