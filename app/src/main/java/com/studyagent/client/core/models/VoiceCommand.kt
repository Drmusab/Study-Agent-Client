package com.studyagent.client.core.models

sealed interface VoiceCommand {
    data object Again : VoiceCommand
    data object Hard : VoiceCommand
    data object Good : VoiceCommand
    data object Easy : VoiceCommand

    data object Repeat : VoiceCommand
    data object Hint : VoiceCommand
    data object Explain : VoiceCommand
    data object ShowAnswer : VoiceCommand
    data object Skip : VoiceCommand

    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object Stop : VoiceCommand
    data object EndSession : VoiceCommand

    data class StartStudy(val deck: String? = null) : VoiceCommand
    data object StatusQuestion : VoiceCommand // "How many cards left?"

    data class SubmitAnswer(val answer: String) : VoiceCommand
    data class Unknown(val rawText: String) : VoiceCommand

    val commandName: String
        get() = this::class.simpleName ?: "VoiceCommand"
}
