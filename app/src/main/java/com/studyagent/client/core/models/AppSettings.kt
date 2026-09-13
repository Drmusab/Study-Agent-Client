package com.studyagent.client.core.models

import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val selectedProfileId: String? = null,

    // ---- Speech recognition (STT) ----
    /**
     * Legacy single-locale STT language. Still read (so an existing install keeps its
     * choice) but superseded by [sttLanguageMode]; see `AppSettingsPreferencesCodec`
     * for the migration.
     */
    val sttLanguage: String = AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE,
    /** `AUTO_EN_AR` | `ENGLISH` | `ARABIC` — the user no longer switches locale per card. */
    val sttLanguageMode: String = AppSettingsPolicy.DEFAULT_STT_LANGUAGE_MODE,
    val sttEnglishLocale: String = AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE,
    val sttArabicLocale: String = AppSettingsPolicy.DEFAULT_ARABIC_LOCALE,
    /** Locale AUTO falls back to when language detection/switching is unavailable. */
    val sttAutoFallbackLocale: String = AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE,
    /** `AUTO` | `PREFER_ON_DEVICE` | `SYSTEM_DEFAULT`. */
    val sttRecognitionMode: String = AppSettingsPolicy.DEFAULT_STT_RECOGNITION_MODE,
    val sttPreferOnDevice: Boolean = true,
    val sttShowPartialTranscript: Boolean = true,
    val sttMedicalBiasing: Boolean = true,
    /** `SHORT` | `NORMAL` | `LONG` — the only answer-length knob exposed to users. */
    val sttAnswerLength: String = AppSettingsPolicy.DEFAULT_STT_ANSWER_LENGTH,
    /**
     * Logs full transcript text. Developer opt-in only; medical answers are never logged
     * by default.
     */
    val sttDebugTranscriptLogging: Boolean = false,

    // ---- Legacy voice locale (kept for migration compatibility; voices are now per-language) ----
    val ttsLanguage: String = AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE,
    /**
     * Legacy global rate. It is read from old installs to seed the per-purpose rates, but is
     * not a runtime TTS source after migration. The codec keeps a model-value shadow while the
     * stable `speech_rate` key mirrors [questionRate] for older app versions.
     */
    val speechRate: Float = AppSettingsPolicy.DEFAULT_TTS_RATE,
    /** Shared TTS pitch (used as TtsSettings.pitch). */
    val speechPitch: Float = AppSettingsPolicy.DEFAULT_SPEECH_PITCH,

    // ---- Voice engine / voices ----
    /** TTS engine package name, null = system default. */
    val ttsEngineId: String? = null,
    /** Persisted engine voice names; null = automatic recommended voice. */
    val englishVoiceId: String? = null,
    val arabicVoiceId: String? = null,
    val preferOfflineVoices: Boolean = true,

    // ---- Per-purpose speech rates ----
    val questionRate: Float = AppSettingsPolicy.DEFAULT_TTS_RATE,
    val feedbackRate: Float = AppSettingsPolicy.DEFAULT_FEEDBACK_RATE,
    val explanationRate: Float = AppSettingsPolicy.DEFAULT_TTS_RATE,

    // ---- Speech pipeline behavior ----
    val ttsAutoLanguageDetection: Boolean = true,
    val ttsMedicalPronunciation: Boolean = true,
    val ttsAcousticGapMs: Int = AppSettingsPolicy.DEFAULT_ACOUSTIC_GAP_MS,
    val headsetDisconnectBehavior: HeadsetDisconnectBehavior = HeadsetDisconnectBehavior.PAUSE_SPEECH,

    // ---- Study audio routing (headphones are optional; the phone is a first-class route) ----
    /**
     * `AUTO` | `HEADSET_PREFERRED` | `PHONE` | `HEADSET_REQUIRED`.
     *
     * Typed via [StudyAudioMode] in the model and stored by name; an unknown value migrates to
     * [StudyAudioMode.AUTO] rather than crashing the app on a settings file from another build.
     */
    val studyAudioMode: StudyAudioMode = StudyAudioMode.AUTO,
    /** One-time "no headphones — using the phone speaker and microphone" notice (§31/§66). */
    val phoneAudioNoticeAcknowledged: Boolean = false,

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
    val maxReconnectAttempts: Int = AppSettingsPolicy.DEFAULT_MAX_RECONNECT_ATTEMPTS,
    val pingIntervalSeconds: Long = AppSettingsPolicy.DEFAULT_PING_INTERVAL_SECONDS
)
