package com.studyagent.client.core.voice.stt

/**
 * Structured STT configuration, mirrored from `AppSettings` the same way `TtsSettings`
 * mirrors the TTS half. Study logic never reaches into `AppSettings` for recognition
 * behaviour — it reads this, so presets and Settings both flow through one path.
 *
 * Only the fields with a real user-visible effect live here; raw `RecognizerIntent` flags
 * and millisecond silence hints are deliberately absent (§128).
 */
data class SttSettings(
    val backendPreference: RecognitionBackendPreference = RecognitionBackendPreference.AUTO,
    val languageMode: RecognitionLanguageMode = RecognitionLanguageMode.AUTO_EN_AR,
    val englishLocale: String = DEFAULT_ENGLISH_LOCALE,
    val arabicLocale: String = DEFAULT_ARABIC_LOCALE,
    /** Used when AUTO cannot use language detection/switching (§31 fallback). */
    val autoModeFallbackLocale: String = DEFAULT_ENGLISH_LOCALE,
    val preferOnDevice: Boolean = true,
    val autoSubmitAnswers: Boolean = true,
    val spokenRatings: Boolean = true,
    val showPartialTranscript: Boolean = true,
    val medicalVocabularyBiasing: Boolean = true,
    val answerEndpointProfile: AnswerEndpointProfile = AnswerEndpointProfile.NORMAL,
    val handsFreeMode: Boolean = true,
    /**
     * Ask the user to confirm a spoken rating when the parsed confidence is below
     * [ratingConfirmationThreshold] instead of silently changing Anki scheduling.
     */
    val confirmAmbiguousRating: Boolean = false,
    /** Logs full transcript text. Developer-only, off by default (§46). */
    val debugTranscriptLogging: Boolean = false,

    // ---- confidence gates -------------------------------------------------
    /** Below this, a spoken rating is never applied automatically. */
    val ratingMinConfidence: Float = RATING_MIN_CONFIDENCE,
    /** Between [ratingMinConfidence] and this, a rating is accepted only on an exact grammar hit. */
    val ratingConfirmationThreshold: Float = RATING_CONFIRM_THRESHOLD,
    /** Destructive commands (End session, Skip, Again) need at least this. */
    val destructiveCommandMinConfidence: Float = DESTRUCTIVE_MIN_CONFIDENCE,
    /** Below this an answer is flagged "check transcript" — never "wrong answer" (§72). */
    val lowConfidenceTranscriptThreshold: Float = LOW_CONFIDENCE_THRESHOLD,

    // ---- retry / rate limits (§51/§52) ------------------------------------
    val maxRetriesPerTurn: Int = DEFAULT_MAX_RETRIES,
    val minTurnIntervalMs: Long = DEFAULT_MIN_TURN_INTERVAL_MS,
    val retryBackoffMs: Long = DEFAULT_RETRY_BACKOFF_MS
) {
    /** Resolves a language tag for a single-locale request under the current mode. */
    fun primaryLocaleFor(mode: RecognitionLanguageMode): String = when (mode) {
        RecognitionLanguageMode.ENGLISH -> englishLocale
        RecognitionLanguageMode.ARABIC -> arabicLocale
        RecognitionLanguageMode.AUTO_EN_AR -> autoModeFallbackLocale
    }

    /** Locales offered to Android 14+ language detection/switching. Deliberately small (§29). */
    fun detectionAllowlist(): List<String> = listOf(englishLocale, arabicLocale)

    companion object {
        const val DEFAULT_ENGLISH_LOCALE = "en-US"
        const val DEFAULT_ARABIC_LOCALE = "ar-IQ"

        const val RATING_MIN_CONFIDENCE = 0.45f
        const val RATING_CONFIRM_THRESHOLD = 0.75f
        const val DESTRUCTIVE_MIN_CONFIDENCE = 0.60f
        const val LOW_CONFIDENCE_THRESHOLD = 0.35f

        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_MIN_TURN_INTERVAL_MS = 250L
        const val DEFAULT_RETRY_BACKOFF_MS = 400L
    }
}

/**
 * The only answer-length knob shown to ordinary users (§16/§128). It maps onto the
 * internal [EndpointProfile] plus a watchdog budget; timings stay out of the UI.
 */
enum class AnswerEndpointProfile(val label: String) {
    SHORT("Short answers"),
    NORMAL("Normal"),
    LONG("Long answers (more pauses tolerated)");

    fun toEndpointProfile(): EndpointProfile = when (this) {
        SHORT -> EndpointProfile.NORMAL_ANSWER
        NORMAL -> EndpointProfile.NORMAL_ANSWER
        LONG -> EndpointProfile.LONG_ANSWER
    }

    companion object {
        fun fromName(raw: String?): AnswerEndpointProfile =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NORMAL
    }
}
