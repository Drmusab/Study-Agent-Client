package com.studyagent.client.core.study

import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.ReviewerActionKind
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.CardTextDirection

/**
 * Resolves the natural reading direction ([CardTextDirection.RTL] vs [CardTextDirection.LTR]) from
 * the first strong directional Unicode character in [text] (GATE 12 STEP 34).
 */
fun resolveAnswerTextDirection(
    text: String,
    explicit: CardTextDirection = CardTextDirection.AUTO
): CardTextDirection {
    if (explicit == CardTextDirection.RTL || explicit == CardTextDirection.LTR) return explicit
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return CardTextDirection.RTL

            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return CardTextDirection.LTR
        }
        index += Character.charCount(codePoint)
    }
    return CardTextDirection.LTR
}

/**
 * Canonical Anki rating type alias so domain and presentation callers can name [AnkiRating] or
 * [Rating] interchangeably without introducing a duplicate enum.
 */
typealias AnkiRating = Rating

/**
 * Explicit reveal state for an Anki review turn (GATE 12 STEP 5).
 *
 * Renderer loading/degradation belongs to [com.studyagent.client.core.render.AnkiRenderState],
 * never to a third ambiguous reveal state.
 */
enum class AnswerRevealState {
    HIDDEN,
    REVEALED
}

/**
 * Explicit post-reveal presentation mode for an Anki review turn (GATE 12 STEP 6).
 *
 * - [ORIGINAL]: render `answerHtml` through the GATE 08/09 renderer.
 * - [CLEAN]: display `answerText` without re-parsing HTML.
 * - [COMPARE]: display user answer, clean reference answer, and AI evaluation side by side / stacked.
 */
enum class AnswerCompareMode {
    ORIGINAL,
    CLEAN,
    COMPARE
}

/**
 * Status of optional AI answer evaluation on an Anki turn (GATE 12 STEP 15, 23, 40).
 */
enum class AnswerEvaluationStatus {
    /** Manual review without AI; evaluation UI is omitted cleanly. */
    NOT_REQUESTED,
    /** AI evaluation is currently in flight for the active turn. */
    EVALUATING,
    /** AI evaluation completed for the active turn. */
    COMPLETED,
    /** AI evaluation failed, timed out, or had no reference text; manual review continues. */
    UNAVAILABLE
}

/**
 * Explicit classification of the clean answer channel (GATE 12 STEP 24, 25).
 *
 * Distinguishes:
 * - [PRESENT]: non-empty `answerText` exists.
 * - [EMPTY]: `answerText` is legitimately `""` (not loading, not an error; uses original HTML if available).
 * - [MISSING]: `answerText` is `null` (unavailable from backend; uses original HTML if available).
 * - [RENDER_FAILURE]: `answerHtml` failed to render in WebView and degraded to `CLEAN` `answerText`.
 */
enum class CleanAnswerState {
    PRESENT,
    EMPTY,
    MISSING,
    RENDER_FAILURE
}

/**
 * Deterministic post-answer audio sequence phase (GATE 12 STEP 27).
 *
 * Enforces the strict ordering:
 * 1. stop active question TTS / answer STT ([STOPPING_QUESTION_AND_STT])
 * 2. reveal visual answer ([VISUAL_REVEALED])
 * 3. play card audio only according to explicit media policy ([CARD_MEDIA])
 * 4. speak AI feedback or answer if enabled ([SPEAKING_FEEDBACK] / [SPEAKING_ANSWER])
 * 5. acoustic gap ([ACOUSTIC_GAP])
 * 6. optional rating voice command listener ([LISTENING_FOR_RATING])
 *
 * Card audio, TTS feedback, and STT are never active simultaneously.
 */
enum class AnswerAudioSequencePhase {
    IDLE,
    STOPPING_QUESTION_AND_STT,
    VISUAL_REVEALED,
    CARD_MEDIA,
    SPEAKING_ANSWER,
    SPEAKING_FEEDBACK,
    ACOUSTIC_GAP,
    LISTENING_FOR_RATING;

    val isSpeaking: Boolean
        get() = this == SPEAKING_ANSWER || this == SPEAKING_FEEDBACK

    val isListening: Boolean
        get() = this == LISTENING_FOR_RATING
}

/**
 * Structured summary of AI evaluation points for presentation (GATE 12 STEP 4, 15, 39).
 */
data class EvaluationSummary(
    val score: Int? = null,
    val correctPoints: List<String> = emptyList(),
    val missingPoints: List<String> = emptyList(),
    val incorrectPoints: List<String> = emptyList(),
    val confidence: Double? = null
) {
    val hasKeyPoints: Boolean
        get() = correctPoints.isNotEmpty() || missingPoints.isNotEmpty() || incorrectPoints.isNotEmpty()

    companion object {
        fun from(evaluation: Evaluation?): EvaluationSummary? = evaluation?.let {
            EvaluationSummary(
                score = it.score,
                correctPoints = it.correctPoints,
                missingPoints = it.missingPoints,
                incorrectPoints = it.incorrectPoints,
                confidence = it.confidence
            )
        }
    }
}

/**
 * Request handed to an [AnkiAnswerEvaluator] (GATE 12 STEP 13).
 *
 * [referenceAnswerText] is strictly derived from `AnkiRenderedCard.evaluationAnswerText`
 * (`pureAnswerText ?: answerText`) and never from `answerHtml` (INV-12-08).
 */
data class AnkiAnswerEvaluationRequest(
    val requestId: String,
    val sessionId: String,
    val turnId: ReviewTurnId,
    val cardRef: AnkiCardRef,
    val questionText: String?,
    val referenceAnswerText: String,
    val userAnswerText: String
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(userAnswerText.isNotBlank()) { "userAnswerText must not be blank" }
    }

    companion object {
        /**
         * Builds an evaluation request using `card.evaluationAnswerText` (`pureAnswerText ?: answerText`).
         * Returns `null` if the card has no non-blank pure/clean answer text, ensuring `answerHtml`
         * is never sent to the AI evaluator.
         */
        fun fromCard(
            requestId: String,
            sessionId: String,
            turnId: ReviewTurnId,
            card: AnkiRenderedCard,
            userAnswerText: String
        ): AnkiAnswerEvaluationRequest? {
            val pureOrClean = card.evaluationAnswerText?.takeIf { it.isNotBlank() } ?: return null
            return AnkiAnswerEvaluationRequest(
                requestId = requestId,
                sessionId = sessionId,
                turnId = turnId,
                cardRef = card.ref,
                questionText = card.questionText,
                referenceAnswerText = pureOrClean,
                userAnswerText = userAnswerText.trim()
            )
        }
    }
}

/**
 * Outcome of an [AnkiAnswerEvaluator] call (GATE 12 STEP 15, 23).
 */
sealed interface AnkiAnswerEvaluationResult {
    data class Success(val evaluation: Evaluation) : AnkiAnswerEvaluationResult
    data class Failure(val reason: String) : AnkiAnswerEvaluationResult
}

/**
 * Pluggable evaluator for Anki study turns.
 */
fun interface AnkiAnswerEvaluator {
    suspend fun evaluate(request: AnkiAnswerEvaluationRequest): AnkiAnswerEvaluationResult
}

/**
 * Pure, backend-neutral presentation model for an Anki turn's answer reveal, reference comparison,
 * AI feedback, and rating selection experience (GATE 12 STEP 4–25, 34–45).
 *
 * Contains no Android, WebView, or provider types.
 *
 * Hidden-answer protection (STEP 20 / INV-12-03): when [revealState] is [AnswerRevealState.HIDDEN],
 * [AnswerReviewModel.from] withholds [referenceAnswerText], [answerHtml], [answerText],
 * [pureAnswerText], [rawReferenceAnswerText], and strips the answer channels from [renderedCard]
 * so the UI cannot accidentally display or leak any answer content before reveal.
 */
data class AnswerReviewModel(
    val turnId: ReviewTurnId,
    val userAnswerText: String?,
    val referenceAnswerText: String?,
    val evaluationFeedback: String?,
    val evaluationSummary: EvaluationSummary?,
    val suggestedRating: AnkiRating?,
    val revealState: AnswerRevealState,
    val compareMode: AnswerCompareMode,
    val cardRef: AnkiCardRef? = null,
    val questionHtml: String? = null,
    val questionText: String? = null,
    val answerHtml: String? = null,
    val answerText: String? = null,
    val pureAnswerText: String? = null,
    val rawReferenceAnswerText: String? = null,
    val showRawReferenceAnswer: Boolean = false,
    val renderedCard: AnkiRenderedCard? = null,
    val renderedSide: AnkiCardSide = if (revealState == AnswerRevealState.REVEALED) AnkiCardSide.ANSWER else AnkiCardSide.QUESTION,
    val cleanAnswerState: CleanAnswerState = CleanAnswerState.MISSING,
    val renderFallbackReason: String? = null,
    val evaluationStatus: AnswerEvaluationStatus = if (evaluationFeedback != null || evaluationSummary != null) {
        AnswerEvaluationStatus.COMPLETED
    } else {
        AnswerEvaluationStatus.NOT_REQUESTED
    },
    val evaluationFailureReason: String? = null,
    val availableRatings: List<AnkiRating> = emptyList(),
    val nextReviewTimes: Map<AnkiRating, String> = emptyMap(),
    val ratingOptionsMapped: Boolean = availableRatings.isNotEmpty(),
    val selectedRating: AnkiRating? = null,
    val committedRating: AnkiRating? = null,
    val commitUiState: RatingCommitUiState = RatingCommitUiState.AwaitingRating,
    val ratingControlsEnabled: Boolean = false,
    val audioSequencePhase: AnswerAudioSequencePhase = AnswerAudioSequencePhase.IDLE,
    // ---- GATE 13 reviewer actions (flag / bury / suspend) ----
    /**
     * The current turn's reviewer-action projection; [ReviewerActionUiState.Idle] when none. It is
     * derived from the durable [com.studyagent.client.core.anki.ReviewerActionStatus] (§7), never a
     * second state model.
     */
    val reviewerActionState: ReviewerActionUiState = ReviewerActionUiState.Idle,
    /**
     * The action kinds this session's frozen capability set allows (STEP 22/INV-13-17). The menu
     * renders exactly this list — never a disabled control standing in for an unsupported action.
     */
    val availableReviewerActionKinds: List<ReviewerActionKind> = emptyList(),
    /**
     * Presentation-only: why the last action request was refused *before* anything was recorded
     * (§15/§23). Never a transaction state — nothing was sent, nothing changed.
     */
    val reviewerActionRefusal: ReviewerActionRefusal? = null,
    /** True while the turn presented here is the one an action may act on. */
    val reviewerActionsEnabled: Boolean = false,
    /**
     * GATE 13 §18/§21 — the flag the *backend* currently reports for this card, when it reports one.
     *
     * It is a card projection, not action state: after a confirmed `SetFlag` the reducer projects the
     * confirmed flag onto the turn ([com.studyagent.client.core.anki.AnkiReviewTurn.withFlag]) and it
     * appears here. It changes no transaction status, schedules nothing and is never what the menu
     * uses to decide whether an action *may* run (that is [reviewerActionState] plus the policy).
     *
     * `null` means "the backend did not say" (`AnkiRenderedCard.flag` is nullable) — never a guess of
     * `NONE`. A flag is not answer content, so it stays visible while the answer is hidden.
     */
    val currentFlag: AnkiFlag? = null
) {
    /** True only after explicit or post-answer reveal. */
    val isRevealed: Boolean
        get() = revealState == AnswerRevealState.REVEALED

    /** True when the user revealed directly without providing an answer transcript (STEP 37). */
    val isUserAnswerSkippedOrEmpty: Boolean
        get() = userAnswerText.isNullOrBlank()

    /** Resolved text direction for the user's answer transcript (STEP 34). */
    val userAnswerDirection: CardTextDirection
        get() = resolveAnswerTextDirection(userAnswerText.orEmpty())

    /** Resolved text direction for the clean reference answer (STEP 34). */
    val referenceAnswerDirection: CardTextDirection
        get() = resolveAnswerTextDirection(referenceAnswerText.orEmpty())

    /** Resolved text direction for the AI feedback (STEP 34). */
    val evaluationFeedbackDirection: CardTextDirection
        get() = resolveAnswerTextDirection(evaluationFeedback.orEmpty())

    /**
     * Effective mode used for the reference answer view, accounting for missing/empty channels and
     * renderer fallback (STEP 24, 25).
     */
    val effectiveCompareMode: AnswerCompareMode
        get() = when {
            revealState == AnswerRevealState.HIDDEN -> AnswerCompareMode.ORIGINAL
            renderFallbackReason != null && compareMode == AnswerCompareMode.ORIGINAL && !answerText.isNullOrEmpty() ->
                AnswerCompareMode.CLEAN
            compareMode == AnswerCompareMode.CLEAN && answerText.isNullOrEmpty() && !answerHtml.isNullOrBlank() ->
                AnswerCompareMode.ORIGINAL
            compareMode == AnswerCompareMode.ORIGINAL && answerHtml == null && answerText != null ->
                AnswerCompareMode.CLEAN
            else -> compareMode
        }

    companion object {
        /**
         * Projects [SessionMachineState] into [AnswerReviewModel], or `null` when no hydrated Anki
         * turn is active.
         */
        fun from(state: SessionMachineState): AnswerReviewModel? {
            val local = state.anki ?: return null
            return from(local, state.phase, state.cardTurn)
        }

        /**
         * Projects [AnkiStudyInteraction] into [AnswerReviewModel].
         */
        fun from(
            local: AnkiStudyInteraction,
            phase: SessionPhase = SessionPhase.WaitingForRating,
            cardTurn: CardTurn? = null
        ): AnswerReviewModel? {
            val turn = local.turn ?: return null
            val card = turn.renderedCard ?: return null
            val revealState = local.revealState
            val revealed = revealState == AnswerRevealState.REVEALED

            val evaluation = local.evaluation ?: cardTurn?.evaluation
            val suggested = evaluation?.suggestedRating
            val knownRatings = (turn.ratingOptions as? AnkiRatingOptions.Known)?.ratings ?: emptyList()
            val intervals = turn.scheduledCard.scheduling?.nextReviewTimes ?: emptyMap()

            val cleanState = when {
                local.renderFallbackReason != null -> CleanAnswerState.RENDER_FAILURE
                card.answerText == null -> CleanAnswerState.MISSING
                card.answerText.isEmpty() -> CleanAnswerState.EMPTY
                else -> CleanAnswerState.PRESENT
            }

            // STEP 20 / INV-12-03: Never expose answer channels in the presentation projection
            // while revealState == HIDDEN.
            val projectedCard = if (revealed) {
                card
            } else {
                card.copy(
                    answerHtml = null,
                    answerText = null,
                    pureAnswerText = null
                )
            }

            // Clean reference answer uses answerText; if answerText is null/empty and fallback is needed,
            // it never exposes raw HTML as plain text (STEP 11, 12, 14, 25).
            val referenceText = if (revealed) card.answerText else null
            val rawReferenceText = if (revealed && local.showRawReferenceAnswer) card.pureAnswerText else null

            val commitUiState = local.commit?.commitUiState ?: RatingCommitUiState.AwaitingRating
            val ratingAllowedPhase = phase == SessionPhase.WaitingForRating ||
                phase == SessionPhase.SpeakingFeedback ||
                phase == SessionPhase.ShowingAnswer
            // GATE 13 §25 — an unresolved reviewer action blocks rating: the card may be
            // mid-mutation or may already have left the queue. The reason comes from the one policy
            // (derived from the durable status), never from a UI-local comparison.
            val ratingBlockedByAction = local.ratingBlockedByAction != null
            val ratingEnabled = revealed &&
                ratingAllowedPhase &&
                local.commit == null &&
                turn.ratingOptions is AnkiRatingOptions.Known &&
                !ratingBlockedByAction

            return AnswerReviewModel(
                turnId = turn.turnId,
                userAnswerText = local.transcript,
                referenceAnswerText = referenceText,
                evaluationFeedback = evaluation?.shortFeedback?.takeIf { it.isNotBlank() },
                evaluationSummary = EvaluationSummary.from(evaluation),
                suggestedRating = suggested,
                revealState = revealState,
                compareMode = local.compareMode,
                cardRef = turn.cardRef,
                questionHtml = card.questionHtml,
                questionText = card.questionText,
                answerHtml = if (revealed) card.answerHtml else null,
                answerText = if (revealed) card.answerText else null,
                pureAnswerText = if (revealed) card.pureAnswerText else null,
                rawReferenceAnswerText = rawReferenceText,
                showRawReferenceAnswer = local.showRawReferenceAnswer,
                renderedCard = projectedCard,
                renderedSide = if (revealed) AnkiCardSide.ANSWER else AnkiCardSide.QUESTION,
                cleanAnswerState = cleanState,
                renderFallbackReason = if (revealed) local.renderFallbackReason else null,
                evaluationStatus = local.evaluationStatus,
                evaluationFailureReason = local.evaluationFailureReason,
                availableRatings = knownRatings,
                nextReviewTimes = intervals,
                ratingOptionsMapped = turn.ratingOptions is AnkiRatingOptions.Known,
                selectedRating = local.selectedRating,
                committedRating = local.committedRating,
                commitUiState = commitUiState,
                ratingControlsEnabled = ratingEnabled,
                audioSequencePhase = local.audioSequencePhase,
                reviewerActionState = local.reviewerActionUi,
                availableReviewerActionKinds = local.request.reviewerActions.availableKinds,
                reviewerActionRefusal = local.reviewerActionRefusal,
                reviewerActionsEnabled = true,
                // The backend-reported flag after a confirmed SetFlag (§18). Read from the projected
                // card, never from the request: a request that was refused or never applied must not
                // show up as a flag.
                currentFlag = projectedCard.flag
            )
        }
    }
}

/**
 * Structured metadata-only diagnostic event definitions for GATE 12 (STEP 46, INV-12-25).
 *
 * Raw card HTML, question text, answer text, pure answer text, user answers, and AI feedback text
 * are strictly excluded from all metadata maps.
 */
object AnkiAnswerReviewDiagnostics {
    const val EVENT_ANKI_ANSWER_REVEALED = "ANKI_ANSWER_REVEALED"
    const val EVENT_ANKI_COMPARE_MODE_CHANGED = "ANKI_COMPARE_MODE_CHANGED"
    const val EVENT_ANKI_ANSWER_RENDER_FALLBACK = "ANKI_ANSWER_RENDER_FALLBACK"
    const val EVENT_ANKI_EVALUATION_DISPLAYED = "ANKI_EVALUATION_DISPLAYED"
    const val EVENT_ANKI_EVALUATION_SKIPPED_OR_UNAVAILABLE = "ANKI_EVALUATION_SKIPPED_OR_UNAVAILABLE"

    val FORBIDDEN_CONTENT_KEYS: Set<String> = setOf(
        "question",
        "questionHtml",
        "questionText",
        "answer",
        "answerHtml",
        "answerText",
        "pureAnswerText",
        "referenceAnswerText",
        "userAnswerText",
        "transcript",
        "feedback",
        "evaluationFeedback",
        "html",
        "content"
    )

    fun containsForbiddenContentKey(metadata: Map<String, String>): Boolean =
        metadata.keys.any { key ->
            FORBIDDEN_CONTENT_KEYS.any { forbidden -> key.equals(forbidden, ignoreCase = true) }
        }

    fun answerRevealedMetadata(local: AnkiStudyInteraction): Map<String, String> = mapOf(
        "mode" to local.compareMode.name,
        "hasUserAnswer" to (!local.transcript.isNullOrBlank()).toString(),
        "evalStatus" to local.evaluationStatus.name,
        "hasHtml" to (local.turn?.renderedCard?.answerHtml != null).toString(),
        "hasCleanText" to (!local.turn?.renderedCard?.answerText.isNullOrEmpty()).toString()
    )

    fun compareModeChangedMetadata(from: AnswerCompareMode, to: AnswerCompareMode): Map<String, String> = mapOf(
        "from" to from.name,
        "to" to to.name
    )

    fun renderFallbackMetadata(reason: String, fallbackMode: AnswerCompareMode): Map<String, String> = mapOf(
        "reason" to reason.take(64),
        "fallbackMode" to fallbackMode.name
    )

    fun evaluationDisplayedMetadata(evaluation: Evaluation): Map<String, String> = mapOf(
        "hasScore" to (evaluation.score != null).toString(),
        "suggestedRating" to (evaluation.suggestedRating?.name?.lowercase() ?: "none"),
        "correctCount" to evaluation.correctPoints.size.toString(),
        "missingCount" to evaluation.missingPoints.size.toString(),
        "incorrectCount" to evaluation.incorrectPoints.size.toString()
    )

    fun evaluationSkippedOrUnavailableMetadata(
        status: AnswerEvaluationStatus,
        reason: String?
    ): Map<String, String> = mapOf(
        "status" to status.name,
        "reason" to (reason ?: "manual_review")
    )
}
