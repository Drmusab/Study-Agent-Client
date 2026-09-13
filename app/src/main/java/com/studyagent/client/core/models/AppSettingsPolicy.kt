package com.studyagent.client.core.models

import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import java.util.Locale

/**
 * The single policy used when settings enter the runtime.
 *
 * Preferences are untrusted input: they can outlive the build that wrote them, be
 * partially written by an interrupted upgrade, or contain values from a newer
 * version after a downgrade.  Keeping the bounds and tolerant enum parsing here
 * prevents the UI, DataStore mapper and voice/audio consumers from inventing
 * different interpretations of the same value.
 */
object AppSettingsPolicy {
    // TTS controls. These are also the ranges exposed by SettingsScreen and used
    // by DefaultSpeechOrchestrator.
    const val MIN_TTS_RATE = 0.6f
    const val MAX_TTS_RATE = 1.8f
    const val DEFAULT_TTS_RATE = 1.0f
    const val DEFAULT_FEEDBACK_RATE = 1.05f

    const val MIN_SPEECH_PITCH = 0.7f
    const val MAX_SPEECH_PITCH = 1.4f
    const val DEFAULT_SPEECH_PITCH = 1.0f

    const val MIN_ACOUSTIC_GAP_MS = 150
    const val MAX_ACOUSTIC_GAP_MS = 1200
    const val DEFAULT_ACOUSTIC_GAP_MS = 350

    // Network settings. Zero reconnect attempts is a useful explicit choice.
    const val MIN_RECONNECT_ATTEMPTS = 0
    const val MAX_RECONNECT_ATTEMPTS = 100
    const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 10
    const val MIN_PING_INTERVAL_SECONDS = 5L
    const val MAX_PING_INTERVAL_SECONDS = 300L
    const val DEFAULT_PING_INTERVAL_SECONDS = 15L

    const val DEFAULT_ENGLISH_LOCALE = "en-US"
    const val DEFAULT_ARABIC_LOCALE = "ar-IQ"
    const val DEFAULT_TTS_ARABIC_LOCALE = "ar-SA"
    const val DEFAULT_STT_LANGUAGE_MODE = "AUTO_EN_AR"
    const val DEFAULT_STT_RECOGNITION_MODE = "AUTO"
    const val DEFAULT_STT_ANSWER_LENGTH = "NORMAL"

    private val localePattern = Regex(
        "^[A-Za-z]{2,3}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|[0-9]{3}))?(?:-[A-Za-z0-9]{5,8})*$"
    )

    /**
     * Normalizes a complete settings object. This is intentionally pure, so it
     * can be used by codec tests and by any future non-DataStore import path.
     */
    fun normalize(settings: AppSettings): AppSettings {
        val legacyLanguage = normalizeLocale(settings.sttLanguage, DEFAULT_ENGLISH_LOCALE)
        return settings.copy(
            selectedProfileId = settings.selectedProfileId.normalizedNullableString(),
            sttLanguage = legacyLanguage,
            sttLanguageMode = normalizeLanguageMode(settings.sttLanguageMode),
            sttEnglishLocale = normalizeLocale(settings.sttEnglishLocale, DEFAULT_ENGLISH_LOCALE),
            sttArabicLocale = normalizeLocale(settings.sttArabicLocale, DEFAULT_ARABIC_LOCALE),
            sttAutoFallbackLocale = normalizeLocale(
                settings.sttAutoFallbackLocale,
                DEFAULT_ENGLISH_LOCALE
            ),
            sttRecognitionMode = normalizeRecognitionMode(settings.sttRecognitionMode),
            sttAnswerLength = normalizeAnswerLength(settings.sttAnswerLength),
            ttsLanguage = normalizeLocale(settings.ttsLanguage, DEFAULT_ENGLISH_LOCALE),
            speechRate = normalizeRate(settings.speechRate, DEFAULT_TTS_RATE),
            speechPitch = normalizeFloat(
                settings.speechPitch,
                DEFAULT_SPEECH_PITCH,
                MIN_SPEECH_PITCH,
                MAX_SPEECH_PITCH
            ),
            ttsEngineId = settings.ttsEngineId.normalizedNullableString(),
            englishVoiceId = settings.englishVoiceId.normalizedNullableString(),
            arabicVoiceId = settings.arabicVoiceId.normalizedNullableString(),
            questionRate = normalizeRate(settings.questionRate, DEFAULT_TTS_RATE),
            feedbackRate = normalizeRate(settings.feedbackRate, DEFAULT_FEEDBACK_RATE),
            explanationRate = normalizeRate(settings.explanationRate, DEFAULT_TTS_RATE),
            ttsAcousticGapMs = settings.ttsAcousticGapMs.coerceIn(
                MIN_ACOUSTIC_GAP_MS,
                MAX_ACOUSTIC_GAP_MS
            ),
            headsetDisconnectBehavior = normalizeHeadsetDisconnectBehavior(
                settings.headsetDisconnectBehavior
            ),
            studyAudioMode = normalizeAudioMode(settings.studyAudioMode),
            maxReconnectAttempts = settings.maxReconnectAttempts.coerceIn(
                MIN_RECONNECT_ATTEMPTS,
                MAX_RECONNECT_ATTEMPTS
            ),
            pingIntervalSeconds = settings.pingIntervalSeconds.coerceIn(
                MIN_PING_INTERVAL_SECONDS,
                MAX_PING_INTERVAL_SECONDS
            )
        )
    }

    fun normalizeRate(value: Float?, fallback: Float = DEFAULT_TTS_RATE): Float =
        normalizeFloat(value ?: fallback, fallback, MIN_TTS_RATE, MAX_TTS_RATE)

    fun normalizeLocale(raw: String?, fallback: String): String {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isBlank() || !localePattern.matches(candidate)) return fallback
        return try {
            val locale = Locale.forLanguageTag(candidate)
            if (locale.language.length !in 2..3) fallback else locale.toLanguageTag()
        } catch (_: RuntimeException) {
            // Locale.forLanguageTag is specified to be tolerant, but keep this
            // boundary defensive for vendor implementations and future platforms.
            fallback
        }
    }

    fun normalizeLanguageMode(raw: String?): String = when (raw?.trim()?.uppercase()) {
        DEFAULT_STT_LANGUAGE_MODE -> DEFAULT_STT_LANGUAGE_MODE
        "ENGLISH" -> "ENGLISH"
        "ARABIC" -> "ARABIC"
        else -> DEFAULT_STT_LANGUAGE_MODE
    }

    fun normalizeRecognitionMode(raw: String?): String = when (raw?.trim()?.uppercase()) {
        DEFAULT_STT_RECOGNITION_MODE -> DEFAULT_STT_RECOGNITION_MODE
        "PREFER_ON_DEVICE" -> "PREFER_ON_DEVICE"
        "SYSTEM_DEFAULT" -> "SYSTEM_DEFAULT"
        else -> DEFAULT_STT_RECOGNITION_MODE
    }

    fun normalizeAnswerLength(raw: String?): String = when (raw?.trim()?.uppercase()) {
        "SHORT" -> "SHORT"
        DEFAULT_STT_ANSWER_LENGTH -> DEFAULT_STT_ANSWER_LENGTH
        "LONG" -> "LONG"
        else -> DEFAULT_STT_ANSWER_LENGTH
    }

    fun normalizeHeadsetDisconnectBehavior(value: HeadsetDisconnectBehavior): HeadsetDisconnectBehavior =
        value

    fun normalizeAudioMode(value: StudyAudioMode): StudyAudioMode = value

    private fun normalizeFloat(value: Float, fallback: Float, min: Float, max: Float): Float =
        if (!value.isFinite()) fallback else value.coerceIn(min, max)

    private fun String?.normalizedNullableString(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }
}
