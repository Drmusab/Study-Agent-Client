package com.studyagent.client.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Study Control Center configuration models.
 *
 * [StudyControlConfig] is the single coherent model describing how the PC
 * Study Agent should run sessions (deck, mode, targets, evaluation, feedback,
 * Socratic behavior, hints, auto-rating, privacy). It is transmitted to the
 * server via `update_study_config` and embedded in `start_session.config`.
 *
 * Device-level voice preferences (STT/TTS locale, pitch, speed) intentionally
 * live in [AppSettings]/Settings screen, NOT here.
 */

/** Study mode. DUE_REVIEWS keeps the v1 wire value for backward compatibility. */
@Serializable
enum class StudyMode(val wireValue: String, val displayName: String) {
    @SerialName("review_due")
    DUE_REVIEWS("review_due", "Due Reviews"),

    @SerialName("new_cards")
    NEW_CARDS("new_cards", "New Cards"),

    @SerialName("due_and_new")
    DUE_AND_NEW("due_and_new", "Due + New"),

    @SerialName("weak_cards")
    WEAK_CARDS("weak_cards", "Weak Cards"),

    @SerialName("incorrect_cards")
    INCORRECT_CARDS("incorrect_cards", "Incorrect Cards"),

    @SerialName("custom")
    CUSTOM_SESSION("custom", "Custom Session");

    companion object {
        fun fromWire(value: String?): StudyMode =
            entries.firstOrNull { it.wireValue == value } ?: DUE_REVIEWS
    }
}

/** How a session terminates. */
@Serializable
enum class SessionTargetType(val wireValue: String, val displayName: String) {
    @SerialName("cards")
    CARDS("cards", "Cards"),

    @SerialName("minutes")
    MINUTES("minutes", "Minutes"),

    @SerialName("finish_due")
    FINISH_DUE("finish_due", "Finish due cards");

    companion object {
        fun fromWire(value: String?): SessionTargetType =
            entries.firstOrNull { it.wireValue == value } ?: CARDS
    }
}

/** Handling of learning/relearning cards within session queues. */
@Serializable
enum class LearningHandling(val wireValue: String, val displayName: String) {
    @SerialName("mixed")
    MIXED("mixed", "Mixed with reviews"),

    @SerialName("after_reviews")
    AFTER_REVIEWS("after_reviews", "After reviews"),

    @SerialName("separate")
    SEPARATE("separate", "Separate step");

    companion object {
        fun fromWire(value: String?): LearningHandling =
            entries.firstOrNull { it.wireValue == value } ?: MIXED
    }
}

/** Strictness of the PC-side evaluation agent. */
@Serializable
enum class EvaluationStrictness(val wireValue: String, val displayName: String) {
    @SerialName("lenient")
    LENIENT("lenient", "Lenient"),

    @SerialName("balanced")
    BALANCED("balanced", "Balanced"),

    @SerialName("strict")
    STRICT("strict", "Strict"),

    @SerialName("exam")
    EXAM("exam", "Exam Mode");

    companion object {
        fun fromWire(value: String?): EvaluationStrictness =
            entries.firstOrNull { it.wireValue == value } ?: BALANCED
    }
}

/** Depth of pedagogical feedback returned/spoken after each answer. */
@Serializable
enum class FeedbackDepth(val wireValue: String, val displayName: String) {
    @SerialName("minimal")
    MINIMAL("minimal", "Minimal"),

    @SerialName("normal")
    NORMAL("normal", "Normal"),

    @SerialName("detailed")
    DETAILED("detailed", "Detailed"),

    @SerialName("tutor")
    TUTOR("tutor", "Tutor");

    companion object {
        fun fromWire(value: String?): FeedbackDepth =
            entries.firstOrNull { it.wireValue == value } ?: NORMAL
    }
}

/** When the agent may offer hints automatically. Manual hint always stays available. */
@Serializable
enum class HintPolicy(val wireValue: String, val displayName: String) {
    @SerialName("none")
    NO_AUTOMATIC("none", "No automatic hints"),

    @SerialName("after_first_failure")
    AFTER_FIRST_FAILURE("after_first_failure", "Hint after first failure"),

    @SerialName("after_second_failure")
    AFTER_SECOND_FAILURE("after_second_failure", "Hint after second failure"),

    @SerialName("manual_only")
    MANUAL_ONLY("manual_only", "Manual hints only");

    companion object {
        fun fromWire(value: String?): HintPolicy =
            entries.firstOrNull { it.wireValue == value } ?: MANUAL_ONLY
    }
}

/**
 * AI-assisted rating behavior. The PC server converts evaluations into
 * suggested ratings; the client only decides how much automation to apply.
 * Default stays safe: SUGGEST.
 */
@Serializable
enum class AutoRatingMode(val wireValue: String, val displayName: String) {
    @SerialName("manual")
    MANUAL("manual", "Manual only"),

    @SerialName("suggest")
    SUGGEST("suggest", "Suggest rating"),

    @SerialName("auto_confident")
    AUTO_CONFIDENT("auto_confident", "Auto-rate high confidence"),

    @SerialName("automatic")
    AUTOMATIC("automatic", "Fully automatic");

    companion object {
        fun fromWire(value: String?): AutoRatingMode =
            entries.firstOrNull { it.wireValue == value } ?: SUGGEST
    }
}

/**
 * Transcript privacy. Defaults to minimal collection: only the evaluation
 * score is retained unless the user opts into storing transcripts.
 */
@Serializable
enum class TranscriptRetention(val wireValue: String, val displayName: String) {
    @SerialName("none")
    NONE("none", "Do not save transcripts"),

    @SerialName("score_only")
    SCORE_ONLY("score_only", "Save only evaluation score"),

    @SerialName("full")
    FULL("full", "Save transcripts for analytics");

    companion object {
        fun fromWire(value: String?): TranscriptRetention =
            entries.firstOrNull { it.wireValue == value } ?: SCORE_ONLY
    }
}

/** Evaluation knobs transmitted to the PC evaluator (no clinical rules on Android). */
@Serializable
data class EvaluationConfig(
    val strictness: EvaluationStrictness = EvaluationStrictness.BALANCED,
    @SerialName("semantic_matching")
    val semanticMatching: Boolean = true,
    @SerialName("require_key_points")
    val requireKeyPoints: Boolean = true,
    @SerialName("penalize_incorrect")
    val penalizeIncorrectStatements: Boolean = true,
    @SerialName("penalize_dangerous")
    val penalizeDangerousMisconceptions: Boolean = true,
    @SerialName("partial_credit")
    val partialCredit: Boolean = true
)

/** Socratic follow-up behavior executed by the PC agent. */
@Serializable
data class SocraticConfig(
    val enabled: Boolean = false,
    @SerialName("max_follow_ups")
    val maxFollowUps: Int = 2,
    @SerialName("reveal_after_attempts")
    val revealAfterAttempts: Int = 3
)

/**
 * The coherent Study Control configuration. One model instead of dozens of
 * unrelated boolean flows. Persisted locally, synchronized with the PC agent
 * when it advertises the `study_config` capability.
 */
@Serializable
data class StudyControlConfig(
    /** Last deck the user chose. The server remains authoritative for deck data. */
    @SerialName("active_deck")
    val activeDeck: String? = null,
    @SerialName("study_mode")
    val studyMode: StudyMode = StudyMode.DUE_REVIEWS,
    @SerialName("session_target_type")
    val sessionTargetType: SessionTargetType = SessionTargetType.CARDS,
    @SerialName("session_target_value")
    val sessionTargetValue: Int = 50,
    /** Session-level limit; NOT an Anki deck-options override. */
    @SerialName("new_per_day")
    val newPerDay: Int = 20,
    /** Session-level review limit; null = defer to Anki scheduler defaults. */
    @SerialName("review_limit_per_day")
    val reviewLimitPerDay: Int? = null,
    @SerialName("learning_handling")
    val learningHandling: LearningHandling = LearningHandling.MIXED,
    val evaluation: EvaluationConfig = EvaluationConfig(),
    @SerialName("feedback_depth")
    val feedbackDepth: FeedbackDepth = FeedbackDepth.NORMAL,
    val socratic: SocraticConfig = SocraticConfig(),
    @SerialName("hint_policy")
    val hintPolicy: HintPolicy = HintPolicy.MANUAL_ONLY,
    @SerialName("rating_mode")
    val ratingMode: AutoRatingMode = AutoRatingMode.SUGGEST,
    /** Percent (50..100). Used when [ratingMode] == AUTO_CONFIDENT. */
    @SerialName("auto_rate_confidence")
    val autoRateConfidence: Int = 95,
    @SerialName("transcript_retention")
    val transcriptRetention: TranscriptRetention = TranscriptRetention.SCORE_ONLY
) {
    /** Local validation performed before anything is sent to the server. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        when (sessionTargetType) {
            SessionTargetType.CARDS ->
                if (sessionTargetValue !in 1..999) errors.add("Card target must be between 1 and 999")
            SessionTargetType.MINUTES ->
                if (sessionTargetValue !in 1..240) errors.add("Minutes target must be between 1 and 240")
            SessionTargetType.FINISH_DUE -> Unit
        }
        if (newPerDay !in 0..999) errors.add("New cards/day must be between 0 and 999")
        reviewLimitPerDay?.let {
            if (it !in 0..9999) errors.add("Review limit/day must be between 0 and 9999")
        }
        if (ratingMode == AutoRatingMode.AUTO_CONFIDENT && autoRateConfidence !in 50..100) {
            errors.add("Auto-rate confidence must be between 50% and 100%")
        }
        if (socratic.maxFollowUps !in 1..5) errors.add("Socratic follow-ups must be between 1 and 5")
        if (socratic.revealAfterAttempts !in 1..10) errors.add("Reveal-after attempts must be between 1 and 10")
        return errors
    }

    /** Structured optional configuration embedded into `start_session` (v2). */
    fun toSessionStartConfig(): SessionStartConfig = SessionStartConfig(
        targetType = sessionTargetType.wireValue,
        targetValue = if (sessionTargetType == SessionTargetType.FINISH_DUE) null else sessionTargetValue,
        newPerDay = newPerDay,
        reviewLimitPerDay = reviewLimitPerDay,
        learningHandling = learningHandling.wireValue,
        feedbackDepth = feedbackDepth.wireValue,
        evaluationStrictness = evaluation.strictness.wireValue,
        semanticMatching = evaluation.semanticMatching,
        partialCredit = evaluation.partialCredit,
        socraticMode = socratic.enabled,
        hintPolicy = hintPolicy.wireValue,
        ratingMode = ratingMode.wireValue,
        autoRateConfidence = if (ratingMode == AutoRatingMode.AUTO_CONFIDENT) autoRateConfidence else null,
        transcriptRetention = transcriptRetention.wireValue
    )
}

/**
 * Structured optional `start_session.config` payload. v1 servers ignore it,
 * preserving backward compatibility (no dozens of top-level fields).
 */
@Serializable
data class SessionStartConfig(
    @SerialName("target_type")
    val targetType: String = SessionTargetType.CARDS.wireValue,
    @SerialName("target_value")
    val targetValue: Int? = null,
    @SerialName("new_per_day")
    val newPerDay: Int? = null,
    @SerialName("review_limit_per_day")
    val reviewLimitPerDay: Int? = null,
    @SerialName("learning_handling")
    val learningHandling: String? = null,
    @SerialName("feedback_depth")
    val feedbackDepth: String? = null,
    @SerialName("evaluation_strictness")
    val evaluationStrictness: String? = null,
    @SerialName("semantic_matching")
    val semanticMatching: Boolean? = null,
    @SerialName("partial_credit")
    val partialCredit: Boolean? = null,
    @SerialName("socratic_mode")
    val socraticMode: Boolean? = null,
    @SerialName("hint_policy")
    val hintPolicy: String? = null,
    @SerialName("rating_mode")
    val ratingMode: String? = null,
    @SerialName("auto_rate_confidence")
    val autoRateConfidence: Int? = null,
    @SerialName("transcript_retention")
    val transcriptRetention: String? = null
)

/**
 * Study presets produce explicit configuration objects — preset behavior is
 * never scattered across UI conditional statements.
 */
enum class StudyPreset(val displayName: String, val description: String) {
    QUICK_REVIEW("Quick Review", "Fast pass through due cards with balanced feedback"),
    DEEP_STUDY("Deep Study", "Detailed feedback, Socratic follow-ups, manual rating"),
    WALKING_MODE("Walking Mode", "Hands-free, minimal feedback, voice rating"),
    EXAM_MODE("Exam Mode", "Strict evaluation, no hints, results summarized at the end"),
    WEAKNESS_TRAINING("Weakness Training", "Targets weak cards with detailed explanations"),
    CUSTOM("Custom", "Manually tuned configuration");

    /**
     * Applies this preset's agent-side configuration on top of [base],
     * preserving user identity data (active deck, daily limits, privacy).
     */
    fun applyTo(base: StudyControlConfig): StudyControlConfig = when (this) {
        QUICK_REVIEW -> base.copy(
            studyMode = StudyMode.DUE_REVIEWS,
            sessionTargetType = SessionTargetType.CARDS,
            sessionTargetValue = 30,
            feedbackDepth = FeedbackDepth.NORMAL,
            evaluation = base.evaluation.copy(strictness = EvaluationStrictness.BALANCED),
            socratic = SocraticConfig(enabled = false),
            hintPolicy = HintPolicy.AFTER_FIRST_FAILURE,
            ratingMode = AutoRatingMode.SUGGEST
        )

        DEEP_STUDY -> base.copy(
            studyMode = StudyMode.DUE_AND_NEW,
            sessionTargetType = SessionTargetType.MINUTES,
            sessionTargetValue = 45,
            feedbackDepth = FeedbackDepth.DETAILED,
            evaluation = base.evaluation.copy(strictness = EvaluationStrictness.BALANCED),
            socratic = SocraticConfig(enabled = true, maxFollowUps = 2, revealAfterAttempts = 3),
            hintPolicy = HintPolicy.MANUAL_ONLY,
            ratingMode = AutoRatingMode.MANUAL
        )

        WALKING_MODE -> base.copy(
            studyMode = StudyMode.DUE_REVIEWS,
            sessionTargetType = SessionTargetType.MINUTES,
            sessionTargetValue = 25,
            feedbackDepth = FeedbackDepth.MINIMAL,
            evaluation = base.evaluation.copy(strictness = EvaluationStrictness.BALANCED),
            socratic = SocraticConfig(enabled = false),
            hintPolicy = HintPolicy.NO_AUTOMATIC,
            ratingMode = AutoRatingMode.SUGGEST
        )

        EXAM_MODE -> base.copy(
            studyMode = StudyMode.DUE_REVIEWS,
            sessionTargetType = SessionTargetType.FINISH_DUE,
            feedbackDepth = FeedbackDepth.MINIMAL,
            evaluation = base.evaluation.copy(
                strictness = EvaluationStrictness.EXAM,
                partialCredit = false
            ),
            socratic = SocraticConfig(enabled = false),
            hintPolicy = HintPolicy.NO_AUTOMATIC,
            ratingMode = AutoRatingMode.MANUAL
        )

        WEAKNESS_TRAINING -> base.copy(
            studyMode = StudyMode.WEAK_CARDS,
            sessionTargetType = SessionTargetType.MINUTES,
            sessionTargetValue = 20,
            feedbackDepth = FeedbackDepth.DETAILED,
            evaluation = base.evaluation.copy(strictness = EvaluationStrictness.STRICT),
            socratic = SocraticConfig(enabled = true, maxFollowUps = 1, revealAfterAttempts = 2),
            hintPolicy = HintPolicy.AFTER_SECOND_FAILURE,
            ratingMode = AutoRatingMode.SUGGEST
        )

        CUSTOM -> base
    }

    /**
     * Local hands-free behavior produced by this preset (stored in AppSettings).
     * Null value = leave the user's current device-level preference untouched.
     */
    fun localBehavior(): LocalStudyBehavior? = when (this) {
        WALKING_MODE -> LocalStudyBehavior(
            handsFreeMode = true,
            autoPlayQuestion = true,
            autoSubmitTranscript = true,
            autoPlayFeedback = true,
            listenForSpokenRating = true,
            showTranscriptOnScreen = false
        )

        EXAM_MODE -> LocalStudyBehavior(
            handsFreeMode = true,
            autoPlayQuestion = true,
            autoSubmitTranscript = true,
            autoPlayFeedback = true,
            listenForSpokenRating = true,
            showTranscriptOnScreen = false
        )

        DEEP_STUDY -> LocalStudyBehavior(
            handsFreeMode = false,
            autoPlayQuestion = true,
            autoSubmitTranscript = false,
            autoPlayFeedback = true,
            listenForSpokenRating = false,
            showTranscriptOnScreen = true
        )

        QUICK_REVIEW, WEAKNESS_TRAINING -> LocalStudyBehavior(
            handsFreeMode = true,
            autoPlayQuestion = true,
            autoSubmitTranscript = true,
            autoPlayFeedback = true,
            listenForSpokenRating = true,
            showTranscriptOnScreen = null
        )

        CUSTOM -> null
    }

    companion object {
        /**
         * Determines which preset (if any) matches a configuration by comparing
         * the fields presets actually control.
         */
        fun matching(config: StudyControlConfig): StudyPreset {
            return entries.firstOrNull { preset ->
                preset != CUSTOM && preset.applyTo(config).let { candidate ->
                    candidate.studyMode == config.studyMode &&
                        candidate.sessionTargetType == config.sessionTargetType &&
                        candidate.sessionTargetValue == config.sessionTargetValue &&
                        candidate.feedbackDepth == config.feedbackDepth &&
                        candidate.evaluation.strictness == config.evaluation.strictness &&
                        candidate.evaluation.partialCredit == config.evaluation.partialCredit &&
                        candidate.socratic == config.socratic &&
                        candidate.hintPolicy == config.hintPolicy &&
                        candidate.ratingMode == config.ratingMode
                }
            } ?: CUSTOM
        }
    }
}

/** Device-local study behavior toggles mirrored from [AppSettings]. */
data class LocalStudyBehavior(
    val handsFreeMode: Boolean? = null,
    val autoPlayQuestion: Boolean? = null,
    val autoSubmitTranscript: Boolean? = null,
    val autoPlayFeedback: Boolean? = null,
    val listenForSpokenRating: Boolean? = null,
    val showTranscriptOnScreen: Boolean? = null
) {
    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        handsFreeMode = handsFreeMode ?: settings.handsFreeMode,
        autoPlayQuestion = autoPlayQuestion ?: settings.autoPlayQuestion,
        autoSubmitTranscript = autoSubmitTranscript ?: settings.autoSubmitTranscript,
        autoPlayFeedback = autoPlayFeedback ?: settings.autoPlayFeedback,
        listenForSpokenRating = listenForSpokenRating ?: settings.listenForSpokenRating,
        showTranscriptOnScreen = showTranscriptOnScreen ?: settings.showTranscriptOnScreen
    )
}