package com.studyagent.client.ui.screens.study

import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.CardTextDirection
import com.studyagent.client.core.study.AnkiRating
import com.studyagent.client.core.study.AnswerAudioSequencePhase
import com.studyagent.client.core.study.AnswerCompareMode
import com.studyagent.client.core.study.AnswerEvaluationStatus
import com.studyagent.client.core.study.AnswerRevealState
import com.studyagent.client.core.study.AnswerReviewModel
import com.studyagent.client.core.study.CleanAnswerState
import com.studyagent.client.core.study.EvaluationSummary
import com.studyagent.client.core.study.RatingCommitRecoveryUi
import com.studyagent.client.core.study.RatingCommitUiState
import com.studyagent.client.core.study.resolveAnswerTextDirection

/**
 * Accessibility semantics and test tags for the GATE 12 Answer Reveal, Reference Answer & Compare
 * experience (STEP 45).
 */
object AnswerReviewSemantics {
    const val REVEAL_ANSWER = "Reveal Answer"
    const val ORIGINAL_ANSWER = "Original Answer"
    const val CLEAN_ANSWER = "Clean Answer"
    const val COMPARE_ANSWERS = "Compare Answers"
    const val YOUR_ANSWER = "Your Answer"
    const val REFERENCE_ANSWER = "Reference Answer"
    const val AI_FEEDBACK = "AI Feedback"
    const val SUGGESTED_RATING = "Suggested Rating"
    const val REPEAT_ANSWER = "Repeat Answer"
    const val REPEAT_FEEDBACK = "Repeat Feedback"

    const val TAG_REVEAL_BUTTON = "anki_reveal_answer_button"
    const val TAG_MODE_SELECTOR = "anki_compare_mode_selector"
    const val TAG_MODE_ORIGINAL = "anki_mode_original"
    const val TAG_MODE_CLEAN = "anki_mode_clean"
    const val TAG_MODE_COMPARE = "anki_mode_compare"
    const val TAG_YOUR_ANSWER = "anki_your_answer_card"
    const val TAG_REFERENCE_ANSWER = "anki_reference_answer_card"
    const val TAG_AI_FEEDBACK = "anki_ai_feedback_card"
    const val TAG_SUGGESTED_BADGE = "anki_suggested_rating_badge"
    const val TAG_SCROLL_CONTAINER = "anki_answer_scroll_container"
    const val TAG_RATING_BAR = "anki_rating_bar"

    fun modeTabSemantics(mode: AnswerCompareMode): String = when (mode) {
        AnswerCompareMode.ORIGINAL -> ORIGINAL_ANSWER
        AnswerCompareMode.CLEAN -> CLEAN_ANSWER
        AnswerCompareMode.COMPARE -> COMPARE_ANSWERS
    }

    fun ratingButtonSemantics(
        rating: AnkiRating,
        intervalLabel: String? = null,
        isSuggested: Boolean = false
    ): String = buildString {
        append("Rate ").append(rating.displayName)
        if (!intervalLabel.isNullOrBlank()) {
            append(", next review ").append(intervalLabel)
        }
        if (isSuggested) {
            append(", ").append(SUGGESTED_RATING)
        }
    }
}

/**
 * Presentation model for a single rating button in the revealed answer rating bar (STEP 16, 18, 41, 45).
 */
data class AnswerRatingOptionUi(
    val rating: AnkiRating,
    val label: String,
    val intervalLabel: String?,
    val isSuggested: Boolean,
    val isSelected: Boolean,
    val isCommitted: Boolean,
    val enabled: Boolean,
    val contentDescription: String
)

/**
 * Presentation model for the `Your Answer` block in `COMPARE` mode (STEP 3, 34, 37, 45).
 */
data class UserAnswerComparisonUi(
    val label: String = AnswerReviewSemantics.YOUR_ANSWER,
    val hasRecordedAnswer: Boolean,
    val displayText: String,
    val textDirection: CardTextDirection
) {
    companion object {
        const val EMPTY_SKIPPED_COPY = "No spoken answer recorded (revealed directly)."

        fun from(userAnswerText: String?): UserAnswerComparisonUi {
            val trimmed = userAnswerText?.trim()?.takeIf { it.isNotEmpty() }
            return if (trimmed != null) {
                UserAnswerComparisonUi(
                    hasRecordedAnswer = true,
                    displayText = trimmed,
                    textDirection = resolveAnswerTextDirection(trimmed)
                )
            } else {
                UserAnswerComparisonUi(
                    hasRecordedAnswer = false,
                    displayText = EMPTY_SKIPPED_COPY,
                    textDirection = CardTextDirection.LTR
                )
            }
        }
    }
}

/**
 * Presentation model for the `Reference Answer` block in `COMPARE` mode (STEP 12, 14, 34, 38, 45).
 */
data class ReferenceAnswerComparisonUi(
    val label: String = AnswerReviewSemantics.REFERENCE_ANSWER,
    val displayText: String,
    val rawEvaluatorText: String?,
    val showRawEvaluatorText: Boolean,
    val canSwitchToOriginal: Boolean,
    val cleanAnswerState: CleanAnswerState,
    val textDirection: CardTextDirection
)

/**
 * Presentation model for the optional AI feedback card (STEP 15, 16, 23, 34, 39, 40, 45).
 */
sealed interface AnswerEvaluationCardUi {
    /** Manual review without AI: omit evaluation card cleanly (STEP 15, 40). */
    data object Hidden : AnswerEvaluationCardUi

    /** AI evaluation is currently running. */
    data object Evaluating : AnswerEvaluationCardUi

    /** AI evaluation failed or was unavailable: non-blocking notice, manual rating stays active (STEP 23). */
    data class Unavailable(
        val statusMessage: String,
        val reasonToken: String?
    ) : AnswerEvaluationCardUi

    /** AI evaluation succeeded: concise feedback, key points, optional score, advisory suggestion (STEP 15, 39). */
    data class Available(
        val label: String = AnswerReviewSemantics.AI_FEEDBACK,
        val feedbackText: String,
        val feedbackDirection: CardTextDirection,
        val score: Int?,
        val summary: EvaluationSummary?,
        val suggestedRating: AnkiRating?,
        val suggestedAdvisoryLabel: String?,
        val canRepeatFeedback: Boolean
    ) : AnswerEvaluationCardUi
}

/**
 * Complete backend-neutral UI state for the GATE 12 post-answer review experience (STEP 4–45).
 *
 * Follows the recommended compact phone structure (STEP 36):
 * 1. Question summary (collapsed or compact once revealed)
 * 2. Mode selector: `Original | Clean | Compare`
 * 3. Scrollable Reference / Compare area (`scrollableContent = true`)
 * 4. AI feedback card (if available)
 * 5. Pinned Rating bar (with scheduler interval labels + advisory suggested badge, always reachable)
 */
data class AnswerReviewUiState(
    val turnId: ReviewTurnId,
    val revealState: AnswerRevealState,
    val selectedCompareMode: AnswerCompareMode,
    val effectiveCompareMode: AnswerCompareMode,
    val renderedSide: AnkiCardSide,
    val rendererMode: AnkiCardRenderMode,
    val questionSummaryText: String,
    val questionDirection: CardTextDirection,
    val isQuestionCompact: Boolean,
    val showRevealButton: Boolean,
    val showModeSelector: Boolean,
    val scrollableContent: Boolean = true,
    val ratingBarPinned: Boolean = true,
    /** Visible only when [revealState] is [AnswerRevealState.REVEALED]; always `null` when `HIDDEN`. */
    val visibleAnswerHtml: String?,
    /** Visible only when [revealState] is [AnswerRevealState.REVEALED]; always `null` when `HIDDEN`. */
    val visibleAnswerText: String?,
    val cleanAnswerState: CleanAnswerState,
    val renderFallbackBanner: String?,
    val userAnswerComparison: UserAnswerComparisonUi?,
    val referenceAnswerComparison: ReferenceAnswerComparisonUi?,
    val evaluationCard: AnswerEvaluationCardUi,
    val suggestedRating: AnkiRating?,
    val selectedRating: AnkiRating?,
    val committedRating: AnkiRating?,
    val ratingOptions: List<AnswerRatingOptionUi>,
    val ratingControlsEnabled: Boolean,
    val canRepeatAnswer: Boolean,
    val canRepeatFeedback: Boolean,
    val commitUiState: RatingCommitUiState,
    val recoveryBanner: RatingCommitRecoveryUi?,
    val audioSequencePhase: AnswerAudioSequencePhase
) {
    companion object {
        fun from(
            model: AnswerReviewModel,
            recovery: RatingCommitRecoveryUi? = null
        ): AnswerReviewUiState {
            val revealed = model.revealState == AnswerRevealState.REVEALED
            val effectiveMode = model.effectiveCompareMode

            val rendererMode = when {
                !revealed -> AnkiCardRenderMode.ORIGINAL
                effectiveMode == AnswerCompareMode.CLEAN -> AnkiCardRenderMode.CLEAN
                else -> AnkiCardRenderMode.ORIGINAL
            }

            val questionSummary = model.questionText?.takeIf { it.isNotBlank() } ?: "Question"
            val visibleHtml = if (revealed) model.answerHtml else null
            val visibleText = if (revealed) model.answerText else null

            val fallbackBanner = if (revealed && model.renderFallbackReason != null) {
                "Original HTML answer could not be rendered (${model.renderFallbackReason}). Showing Clean Answer."
            } else {
                null
            }

            val userCompare = if (revealed && effectiveMode == AnswerCompareMode.COMPARE) {
                UserAnswerComparisonUi.from(model.userAnswerText)
            } else if (revealed && !model.userAnswerText.isNullOrBlank()) {
                UserAnswerComparisonUi.from(model.userAnswerText)
            } else {
                null
            }

            val refCompare = if (revealed) {
                val refText = when {
                    !model.referenceAnswerText.isNullOrEmpty() -> model.referenceAnswerText
                    model.cleanAnswerState == CleanAnswerState.EMPTY && !model.answerHtml.isNullOrBlank() ->
                        "Clean text is empty for this card; view Original Answer for full formatting."
                    model.cleanAnswerState == CleanAnswerState.EMPTY ->
                        "(Empty answer)"
                    !model.answerHtml.isNullOrBlank() ->
                        "Clean text unavailable; view Original Answer."
                    else ->
                        "Reference answer unavailable."
                }
                ReferenceAnswerComparisonUi(
                    displayText = refText,
                    rawEvaluatorText = model.rawReferenceAnswerText,
                    showRawEvaluatorText = model.showRawReferenceAnswer && model.rawReferenceAnswerText != null,
                    canSwitchToOriginal = !model.answerHtml.isNullOrBlank(),
                    cleanAnswerState = model.cleanAnswerState,
                    textDirection = resolveAnswerTextDirection(refText)
                )
            } else {
                null
            }

            val evalCard: AnswerEvaluationCardUi = when {
                !revealed && model.evaluationStatus == AnswerEvaluationStatus.EVALUATING ->
                    AnswerEvaluationCardUi.Evaluating
                !revealed ->
                    AnswerEvaluationCardUi.Hidden
                model.evaluationStatus == AnswerEvaluationStatus.NOT_REQUESTED ->
                    AnswerEvaluationCardUi.Hidden
                model.evaluationStatus == AnswerEvaluationStatus.EVALUATING ->
                    AnswerEvaluationCardUi.Evaluating
                model.evaluationStatus == AnswerEvaluationStatus.UNAVAILABLE ->
                    AnswerEvaluationCardUi.Unavailable(
                        statusMessage = "AI evaluation unavailable — compare with the reference answer and rate manually.",
                        reasonToken = model.evaluationFailureReason
                    )
                model.evaluationStatus == AnswerEvaluationStatus.COMPLETED &&
                    (model.evaluationFeedback != null || model.evaluationSummary != null) -> {
                    val feedback = model.evaluationFeedback ?: ""
                    val suggested = model.suggestedRating
                    AnswerEvaluationCardUi.Available(
                        feedbackText = feedback,
                        feedbackDirection = resolveAnswerTextDirection(feedback),
                        score = model.evaluationSummary?.score,
                        summary = model.evaluationSummary,
                        suggestedRating = suggested,
                        suggestedAdvisoryLabel = suggested?.let { "Suggested: ${it.displayName} (Advisory)" },
                        canRepeatFeedback = feedback.isNotBlank()
                    )
                }
                else -> AnswerEvaluationCardUi.Hidden
            }

            val controlsEnabled = model.ratingControlsEnabled &&
                (recovery?.ratingControlsEnabled ?: true) &&
                model.commitUiState is RatingCommitUiState.AwaitingRating

            val ratingButtons = model.availableRatings.map { rating ->
                val interval = model.nextReviewTimes[rating]
                val isSuggested = model.suggestedRating == rating
                val isSelected = model.selectedRating == rating
                val isCommitted = model.committedRating == rating
                AnswerRatingOptionUi(
                    rating = rating,
                    label = rating.displayName,
                    intervalLabel = interval,
                    isSuggested = isSuggested,
                    isSelected = isSelected,
                    isCommitted = isCommitted,
                    enabled = controlsEnabled,
                    contentDescription = AnswerReviewSemantics.ratingButtonSemantics(
                        rating = rating,
                        intervalLabel = interval,
                        isSuggested = isSuggested
                    )
                )
            }

            return AnswerReviewUiState(
                turnId = model.turnId,
                revealState = model.revealState,
                selectedCompareMode = model.compareMode,
                effectiveCompareMode = effectiveMode,
                renderedSide = if (revealed) AnkiCardSide.ANSWER else AnkiCardSide.QUESTION,
                rendererMode = rendererMode,
                questionSummaryText = questionSummary,
                questionDirection = resolveAnswerTextDirection(questionSummary),
                isQuestionCompact = revealed,
                showRevealButton = !revealed,
                showModeSelector = revealed,
                scrollableContent = true,
                ratingBarPinned = true,
                visibleAnswerHtml = visibleHtml,
                visibleAnswerText = visibleText,
                cleanAnswerState = model.cleanAnswerState,
                renderFallbackBanner = fallbackBanner,
                userAnswerComparison = userCompare,
                referenceAnswerComparison = refCompare,
                evaluationCard = evalCard,
                suggestedRating = model.suggestedRating,
                selectedRating = model.selectedRating,
                committedRating = model.committedRating,
                ratingOptions = ratingButtons,
                ratingControlsEnabled = controlsEnabled,
                canRepeatAnswer = revealed && !model.answerText.isNullOrBlank(),
                canRepeatFeedback = revealed && !model.evaluationFeedback.isNullOrBlank(),
                commitUiState = model.commitUiState,
                recoveryBanner = recovery,
                audioSequencePhase = model.audioSequencePhase
            )
        }
    }
}
