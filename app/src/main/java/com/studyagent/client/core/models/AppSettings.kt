package com.studyagent.client.core.models

import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val selectedProfileId: String? = null,

    // ---- Speech recognition (STT) ----
    /**
     * Legacy single-locale STT language. Still read (so an existing install keeps its
     * choice) but superseded by [sttLanguageMode]; see `PreferencesDataStore.readSettings`
     * for the migration.
     */
    val sttLanguage: String = "en-US",
    /** `AUTO_EN_AR` | `ENGLISH` | `ARABIC` — the user no longer switches locale per card. */
    val sttLanguageMode: String = "AUTO_EN_AR",
    val sttEnglishLocale: String = "en-US",
    val sttArabicLocale: String = "ar-IQ",
    /** Locale AUTO falls back to when language detection/switching is unavailable. */
    val sttAutoFallbackLocale: String = "en-US",
    /** `AUTO` | `PREFER_ON_DEVICE` | `SYSTEM_DEFAULT`. */
    val sttRecognitionMode: String = "AUTO",
    val sttPreferOnDevice: Boolean = true,
    val sttShowPartialTranscript: Boolean = true,
    val sttMedicalBiasing: Boolean = true,
    /** `SHORT` | `NORMAL` | `LONG` — the only answer-length knob exposed to users. */
    val sttAnswerLength: String = "NORMAL",
    /**
     * Logs full transcript text. Developer opt-in only; medical answers are never logged
     * by default.
     */
    val sttDebugTranscriptLogging: Boolean = false,

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
