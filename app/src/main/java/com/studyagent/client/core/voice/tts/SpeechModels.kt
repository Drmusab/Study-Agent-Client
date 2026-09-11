package com.studyagent.client.core.voice.tts

import java.util.UUID

/**
 * Domain model for the speech-output subsystem.
 *
 * Study logic (repository, view models) never talks to the Android TTS engine
 * directly. It builds a [SpeechRequest] via [SpeechIds]/[SpeechRequest] and
 * receives a structured [SpeechResult]. All Android `TextToSpeech` callbacks
 * are hidden behind [TtsEngineAdapter] and [SpeechOrchestrator].
 */

/** What the spoken content is for. Drives speech profile (rate), logging and default policies. */
enum class SpeechPurpose {
    QUESTION,
    FEEDBACK,
    HINT,
    EXPLANATION,
    ANSWER,
    STATUS,
    SYSTEM,
    SESSION_SUMMARY,
    ERROR,
    RATING_CONFIRMATION,
    PREVIEW
}

/** Ordering hint for queued requests. Lower rank = spoken sooner. */
enum class SpeechPriority(val rank: Int) {
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3)
}

/**
 * How a new request interacts with in-flight / pending speech.
 *
 * - [REPLACE]   — cancel the current request (if [SpeechRequest.interruptible]) and drop all
 *                 pending requests, then play this one. Used for new questions.
 * - [APPEND]    — enqueue after existing work, ordered by [SpeechPriority] then arrival.
 * - [INTERRUPT] — like REPLACE but cancels even non-interruptible current speech.
 *                 Reserved for critical system announcements (e.g. headset lost).
 * - [IGNORE_IF_DUPLICATE] — drop silently (result = [SpeechResult.Cancelled]) if an identical
 *                 request (same id, or same purpose+text) is already speaking or queued.
 */
enum class QueuePolicy {
    REPLACE,
    APPEND,
    INTERRUPT,
    IGNORE_IF_DUPLICATE
}

/** Per-request language override. [AUTO] defers to the language segmenter. */
enum class LanguageHint {
    AUTO,
    ENGLISH,
    ARABIC
}

/**
 * Optional per-request fine-tuning. Null fields fall back to the purpose profile
 * resolved from [TtsSettings] by the orchestrator.
 */
data class SpeechProfile(
    val rateOverride: Float? = null,
    val pitchOverride: Float? = null,
    val voiceIdOverride: String? = null
)

/**
 * One logical thing to say. May be chopped into several engine utterances
 * internally (language segments × chunks) but completes exactly once.
 */
data class SpeechRequest(
    val id: String,
    val text: String,
    val purpose: SpeechPurpose,
    val languageHint: LanguageHint = LanguageHint.AUTO,
    val priority: SpeechPriority = SpeechPriority.NORMAL,
    val queuePolicy: QueuePolicy = QueuePolicy.REPLACE,
    val interruptible: Boolean = true,
    val profile: SpeechProfile? = null
) {
    /** Identity used for duplicate suppression (content is never logged or persisted). */
    val semanticKey: String get() = "${purpose.name}:${text.trim()}"
}

/** Terminal result of a [SpeechRequest]. Delivered exactly once per accepted request. */
sealed interface SpeechResult {
    data object Completed : SpeechResult
    data object Cancelled : SpeechResult
    data class Failed(val error: SpeechError) : SpeechResult
}

enum class SpeechErrorCode {
    ENGINE_NOT_INITIALIZED,
    ENGINE_INITIALIZATION_FAILED,
    ENGINE_UNAVAILABLE,
    LANGUAGE_UNSUPPORTED,
    VOICE_UNAVAILABLE,
    MISSING_LANGUAGE_DATA,
    AUDIO_FOCUS_DENIED,
    AUDIO_FOCUS_LOST,
    SPEAK_FAILED,
    PLAYBACK_ERROR,
    TIMEOUT,
    ROUTE_LOST,
    QUEUE_FULL
}

/**
 * Typed speech error. [recoverable]=false means the speech pipeline itself is
 * degraded; the study session should still continue visually (see graceful
 * degradation in the repository).
 */
data class SpeechError(
    val code: SpeechErrorCode,
    val message: String,
    val recoverable: Boolean = true,
    val requiresUserAction: Boolean = false
) {
    companion object {
        fun engineInitFailed(status: Int) = SpeechError(
            SpeechErrorCode.ENGINE_INITIALIZATION_FAILED,
            "TTS engine failed to initialize (status $status). Speech output is unavailable.",
            recoverable = false,
            requiresUserAction = true
        )

        fun initTimeout() = SpeechError(
            SpeechErrorCode.ENGINE_INITIALIZATION_FAILED,
            "TTS engine did not initialize in time.",
            recoverable = true
        )

        fun notInitialized() = SpeechError(
            SpeechErrorCode.ENGINE_NOT_INITIALIZED,
            "Speech request arrived after the engine was released.",
            recoverable = false
        )

        fun timeout() = SpeechError(
            SpeechErrorCode.TIMEOUT,
            "The speech engine stopped responding; the utterance was cancelled.",
            recoverable = true
        )

        fun routeLost() = SpeechError(
            SpeechErrorCode.ROUTE_LOST,
            "Headset disconnected while speaking.",
            recoverable = true
        )
    }
}

/**
 * Observable lifecycle of the speech pipeline. Exposed as StateFlow; safe for Compose.
 * No Android framework types leak through this model.
 */
sealed interface TtsState {
    data object Uninitialized : TtsState
    data object Initializing : TtsState

    data class Ready(
        val enginePackage: String? = null,
        val englishVoiceDisplay: String? = null,
        val arabicVoiceDisplay: String? = null
    ) : TtsState

    data class Speaking(
        val requestId: String,
        val purpose: SpeechPurpose,
        val chunkIndex: Int,
        val chunkCount: Int,
        val queueDepth: Int
    ) : TtsState

    data class Error(val error: SpeechError) : TtsState

    data object Released : TtsState
}

/** Why speech was stopped. Mapped to a terminal [SpeechResult] for affected callers. */
enum class StopReason {
    USER,           // "stop speaking", preview restart, manual stop
    REPLACED,       // superseded by a newer request (REPLACE / INTERRUPT)
    PAUSE,          // study session paused
    SESSION_END,
    ROUTE_LOST,     // headset disconnected while speaking
    FOCUS_LOST,     // permanent audio-focus loss
    RELEASE
}

/**
 * Unique, content-free utterance identities (e.g. `q_c123_9f2b1c4d`).
 * Never includes question/answer text — ids appear in logs and engine traces.
 */
object SpeechIds {
    private const val MAX_CARD_ID_CHARS = 24

    fun forPurpose(purpose: SpeechPurpose, cardId: String? = null): String {
        val prefix = when (purpose) {
            SpeechPurpose.QUESTION -> "q"
            SpeechPurpose.FEEDBACK -> "feedback"
            SpeechPurpose.HINT -> "hint"
            SpeechPurpose.EXPLANATION -> "explain"
            SpeechPurpose.ANSWER -> "answer"
            SpeechPurpose.STATUS -> "status"
            SpeechPurpose.SYSTEM -> "system"
            SpeechPurpose.SESSION_SUMMARY -> "summary"
            SpeechPurpose.ERROR -> "error"
            SpeechPurpose.RATING_CONFIRMATION -> "rating"
            SpeechPurpose.PREVIEW -> "preview"
        }
        val cardPart = cardId
            ?.let { "_" + sanitizeCardId(it) }
            .orEmpty()
        return "${prefix}${cardPart}_${shortUuid()}"
    }

    /** Engine-level utterance id for chunk [chunkIndex] of logical request [requestId]. */
    fun chunkId(requestId: String, chunkIndex: Int): String = "$requestId#$chunkIndex"

    private fun sanitizeCardId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_CARD_ID_CHARS)

    private fun shortUuid(): String = UUID.randomUUID().toString().substring(0, 8)
}
