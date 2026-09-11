package com.studyagent.client.core.models

import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val selectedProfileId: String? = null,
    val sttLanguage: String = "en-US",

    // ---- Legacy voice locale (kept for migration compatibility; voices are now per-language) ----
    val ttsLanguage: String = "en-US",
    /** Legacy global rate — remains readable; per-purpose rates fall back to it on migration. */
    val speechRate: Float = 1.0f,
    /** Shared TTS pitch (used as TtsSettings.pitch). */
    val speechPitch: Float = 1.0f,

    // ---- Voice engine / voices ----
    /** TTS engine package name, null = system default. */
    val ttsEngineId: String? = null,
    /** Persisted engine voice names; null = automatic recommended voice. */
    val englishVoiceId: String? = null,
    val arabicVoiceId: String? = null,
    val preferOfflineVoices: Boolean = true,

    // ---- Per-purpose speech rates ----
    val questionRate: Float = 1.0f,
    val feedbackRate: Float = 1.05f,
    val explanationRate: Float = 1.0f,

    // ---- Speech pipeline behavior ----
    val ttsAutoLanguageDetection: Boolean = true,
    val ttsMedicalPronunciation: Boolean = true,
    val ttsAcousticGapMs: Int = 350,
    val headsetDisconnectBehavior: HeadsetDisconnectBehavior = HeadsetDisconnectBehavior.PAUSE_SPEECH,

    // ---- Study behavior ----
    val handsFreeMode: Boolean = true,
    val autoPlayQuestion: Boolean = true,
    val autoPlayFeedback: Boolean = true,
    val autoSubmitTranscript: Boolean = true,
    val confirmRating: Boolean = false,
    /** Listen for a spoken rating after feedback (Control Center: Hands-Free Study). */
    val listenForSpokenRating: Boolean = true,
    val showTranscriptOnScreen: Boolean = true,
    val useFakeAgent: Boolean = false,
    val debugLogging: Boolean = true,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 10,
    val pingIntervalSeconds: Long = 15L
)
