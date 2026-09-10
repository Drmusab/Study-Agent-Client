package com.studyagent.client.core.models

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val selectedProfileId: String? = null,
    val sttLanguage: String = "en-US",
    val ttsLanguage: String = "en-US",
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val handsFreeMode: Boolean = true,
    val autoPlayQuestion: Boolean = true,
    val autoPlayFeedback: Boolean = true,
    val autoSubmitTranscript: Boolean = true,
    val confirmRating: Boolean = false,
    val showTranscriptOnScreen: Boolean = true,
    val useFakeAgent: Boolean = false,
    val debugLogging: Boolean = true,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 10,
    val pingIntervalSeconds: Long = 15L
)
