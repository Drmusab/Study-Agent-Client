package com.studyagent.client.core.voice.tts

import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.AppSettingsPolicy

/** What to do with in-flight speech when the headset disconnects mid-utterance. */
enum class HeadsetDisconnectBehavior {
    /** Safe default for pocket study: stop speech; never blast private content on the speaker. */
    PAUSE_SPEECH,

    /** Let Android reroute to the phone speaker and keep talking. */
    CONTINUE_ON_PHONE;

    companion object {
        /** Stable storage contract; Kotlin enum names remain an implementation detail. */
        fun fromStorage(raw: String?): HeadsetDisconnectBehavior = when (raw?.trim()?.uppercase()) {
            "CONTINUE_ON_PHONE" -> CONTINUE_ON_PHONE
            "PAUSE_SPEECH" -> PAUSE_SPEECH
            else -> PAUSE_SPEECH
        }

        fun toStorage(value: HeadsetDisconnectBehavior): String = when (value) {
            PAUSE_SPEECH -> "PAUSE_SPEECH"
            CONTINUE_ON_PHONE -> "CONTINUE_ON_PHONE"
        }
    }
}

/**
 * Structured voice-output configuration consumed by [SpeechOrchestrator].
 * Built from the flat [AppSettings] via [toTtsSettings] so persistence stays
 * backward-compatible while the speech subsystem gets a typed view.
 */
data class TtsSettings(
    /** TTS engine package, null = system default engine. */
    val engineId: String? = null,

    /** Engine voice names (e.g. "en-US-x-sfg#female_2-local"); null = automatic ranking. */
    val englishVoiceId: String? = null,
    val arabicVoiceId: String? = null,

    /** Preferred locales when no explicit voice is selected. */
    val englishLocale: String = AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE,
    val arabicLocale: String = AppSettingsPolicy.DEFAULT_TTS_ARABIC_LOCALE,

    /** Prefer voices that do NOT require a network connection. */
    val preferOfflineVoices: Boolean = true,

    /** Per-purpose base rates (clamped by the orchestrator before use). */
    val questionRate: Float = AppSettingsPolicy.DEFAULT_TTS_RATE,
    val feedbackRate: Float = AppSettingsPolicy.DEFAULT_FEEDBACK_RATE,
    val explanationRate: Float = AppSettingsPolicy.DEFAULT_TTS_RATE,

    val pitch: Float = AppSettingsPolicy.DEFAULT_SPEECH_PITCH,

    /** Automatic Arabic/English segmentation of mixed cards. */
    val autoLanguageDetection: Boolean = true,

    /** Medical abbreviation/unit pronunciation layer. */
    val medicalPronunciation: Boolean = true,

    val headsetDisconnectBehavior: HeadsetDisconnectBehavior = HeadsetDisconnectBehavior.PAUSE_SPEECH,

    /** Acoustic separation between end-of-speech and microphone activation. */
    val acousticGapMs: Int = DEFAULT_ACOUSTIC_GAP_MS
) {
    init {
        require(acousticGapMs in MIN_ACOUSTIC_GAP_MS..MAX_ACOUSTIC_GAP_MS) {
            "acousticGapMs out of range: $acousticGapMs"
        }
    }

    companion object {
        const val DEFAULT_ACOUSTIC_GAP_MS = AppSettingsPolicy.DEFAULT_ACOUSTIC_GAP_MS
        const val MIN_ACOUSTIC_GAP_MS = AppSettingsPolicy.MIN_ACOUSTIC_GAP_MS
        const val MAX_ACOUSTIC_GAP_MS = AppSettingsPolicy.MAX_ACOUSTIC_GAP_MS
    }
}

/** Maps the (flat, migration-safe) persisted settings onto the structured TTS view. */
fun AppSettings.toTtsSettings(): TtsSettings = TtsSettings(
    engineId = ttsEngineId,
    englishVoiceId = englishVoiceId,
    arabicVoiceId = arabicVoiceId,
    preferOfflineVoices = preferOfflineVoices,
    questionRate = questionRate,
    feedbackRate = feedbackRate,
    explanationRate = explanationRate,
    pitch = speechPitch,
    autoLanguageDetection = ttsAutoLanguageDetection,
    medicalPronunciation = ttsMedicalPronunciation,
    headsetDisconnectBehavior = headsetDisconnectBehavior,
    acousticGapMs = ttsAcousticGapMs
)
