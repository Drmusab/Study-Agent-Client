package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.ReviewCommitEvidence
import com.studyagent.client.core.models.Rating

/**
 * GATE 11 — the one authoritative domain-rating → provider-ease mapping (STEP 29-§31).
 *
 * Numeric ease values exist only here, below the gateway. Verified at the v2.24.1 pin (see
 * `AnkiDroidApiContract` GATE 11 table): `answer_ease` 1..4 → `Ease.fromValue` →
 * `CardAnswer.Rating.forNumber(value - 1)` = AGAIN/HARD/GOOD/EASY. The provider's review-info
 * endpoint hard-codes four buttons, so all four ratings are valid provider inputs; whether the
 * *scheduler offered* a rating for this card is still enforced from the turn's rating options.
 */
internal object AnkiDroidRatingContract {
    private const val EASE_AGAIN = 1
    private const val EASE_HARD = 2
    private const val EASE_GOOD = 3
    private const val EASE_EASY = 4

    fun easeFor(rating: Rating): Int = when (rating) {
        Rating.AGAIN -> EASE_AGAIN
        Rating.HARD -> EASE_HARD
        Rating.GOOD -> EASE_GOOD
        Rating.EASY -> EASE_EASY
    }
}

/**
 * GATE 11 — the card's stored review counters: identity + raw scheduler facts, never content.
 * Used only as commit evidence (before/after comparison); nothing here drives scheduling.
 */
data class AnkiDroidCardState(
    val cardId: Long,
    val noteId: Long,
    val cardOrd: Int,
    val deckId: Long,
    val originalDeckId: Long,
    val reps: Int,
    val lapses: Int,
    val intervalDays: Int,
    val type: Int,
    val queue: Int,
    val due: Long,
    val lastReviewEpochSeconds: Long?
) {
    val inFilteredDeck: Boolean get() = originalDeckId != 0L

    /** Same card and every tracked scheduling fact unchanged. */
    fun unchangedFrom(other: AnkiDroidCardState): Boolean = this == other

    fun sameIdentity(other: AnkiDroidCardState): Boolean =
        cardId == other.cardId && noteId == other.noteId && cardOrd == other.cardOrd

    fun toEvidence(capturedAtMs: Long): ReviewCommitEvidence = ReviewCommitEvidence(
        FORMAT,
        listOf(
            "c=$cardId", "n=$noteId", "o=$cardOrd", "d=$deckId", "od=$originalDeckId", "r=$reps",
            "l=$lapses", "i=$intervalDays", "t=$type", "q=$queue", "du=$due",
            "lr=${lastReviewEpochSeconds ?: "-"}", "at=$capturedAtMs"
        ).joinToString(";")
    )

    companion object {
        const val FORMAT = "ankidroid-card-state-v1"

        /** `null` for foreign/unknown formats or damaged tokens — never a guessed baseline. */
        fun fromEvidence(evidence: ReviewCommitEvidence?): AnkiDroidCardState? {
            if (evidence == null || evidence.format != FORMAT) return null
            val fields = evidence.token.split(';').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            return try {
                AnkiDroidCardState(
                    cardId = fields.getValue("c").toLong(),
                    noteId = fields.getValue("n").toLong(),
                    cardOrd = fields.getValue("o").toInt(),
                    deckId = fields.getValue("d").toLong(),
                    originalDeckId = fields.getValue("od").toLong(),
                    reps = fields.getValue("r").toInt(),
                    lapses = fields.getValue("l").toInt(),
                    intervalDays = fields.getValue("i").toInt(),
                    type = fields.getValue("t").toInt(),
                    queue = fields.getValue("q").toInt(),
                    due = fields.getValue("du").toLong(),
                    lastReviewEpochSeconds = fields.getValue("lr").let { if (it == "-") null else it.toLong() }
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Maps one card row of [AnkiDroidApiContract.CARD_STATE_PROJECTION]; `null` if identity/counters are missing. */
        fun fromRow(row: AnkiDroidProviderRow): AnkiDroidCardState? {
            fun long(column: String): Long? =
                row.columnIndex(column).takeIf { it >= 0 && !row.isNull(it) }?.let { row.getLong(it) }
            return AnkiDroidCardState(
                cardId = long(AnkiDroidApiContract.CARD_ID_COLUMN) ?: return null,
                noteId = long(AnkiDroidApiContract.CARD_NOTE_ID_COLUMN) ?: return null,
                cardOrd = long(AnkiDroidApiContract.CARD_ORD_COLUMN)?.toInt() ?: return null,
                deckId = long(AnkiDroidApiContract.CARD_DECK_ID_COLUMN) ?: return null,
                originalDeckId = long(AnkiDroidApiContract.CARD_ORIGINAL_DECK_ID_COLUMN) ?: 0L,
                reps = long(AnkiDroidApiContract.CARD_REPS_COLUMN)?.toInt() ?: return null,
                lapses = long(AnkiDroidApiContract.CARD_LAPSES_COLUMN)?.toInt() ?: return null,
                intervalDays = long(AnkiDroidApiContract.CARD_INTERVAL_COLUMN)?.toInt() ?: return null,
                type = long(AnkiDroidApiContract.CARD_TYPE_COLUMN)?.toInt() ?: return null,
                queue = long(AnkiDroidApiContract.CARD_QUEUE_COLUMN)?.toInt() ?: return null,
                due = long(AnkiDroidApiContract.CARD_DUE_COLUMN) ?: return null,
                lastReviewEpochSeconds = long(AnkiDroidApiContract.CARD_LAST_REVIEW_TIME_COLUMN)
            )
        }
    }
}

/**
 * GATE 11 — advisory card-state comparison during the *synchronous* answer call.
 *
 * Counters and timestamp are NOT a transaction-correlated receipt. They cannot prove a lost
 * answer was ours, nor that an unchanged card will not be mutated by an outstanding call. This
 * classifier is never used by process-death reconciliation or by exception/timeout handling.
 */
internal object AnkiDroidCommitVerifier {
    sealed interface Verdict {
        val detail: String
        /** Observations consistent with one normal answer, NOT a standalone commit receipt. */
        data class ConsistentWithAnswer(override val detail: String) : Verdict
        /** No *observed* change, NOT proof that an outstanding call will never land. */
        data class Unchanged(override val detail: String) : Verdict
        data class Unattributable(override val detail: String) : Verdict
    }

    /** Clock granularity of `last_review_time` (seconds) plus scheduling slack. */
    private const val WINDOW_SLACK_SECONDS = 2L

    /**
     * @param windowStartMs start of the mutation window (just before the provider call).
     * @param windowEndMs end of the window (after the call returned, or "now" for reconciliation).
     * @param tightWindow `true` only for the verification read inside the commit itself, where
     *   before/after are milliseconds apart; reconciliation after the fact is never tight.
     */
    fun classify(
        before: AnkiDroidCardState,
        after: AnkiDroidCardState,
        windowStartMs: Long,
        windowEndMs: Long,
        tightWindow: Boolean
    ): Verdict {
        if (!after.sameIdentity(before)) return Verdict.Unattributable("card_identity_changed")
        val startSec = windowStartMs / 1000L - WINDOW_SLACK_SECONDS
        val endSec = windowEndMs / 1000L + WINDOW_SLACK_SECONDS
        return when (after.reps - before.reps) {
            1 -> {
                val reviewedAt = after.lastReviewEpochSeconds
                when {
                    reviewedAt == null -> Verdict.Unattributable("reps_plus_one_without_review_time")
                    reviewedAt in startSec..endSec -> Verdict.ConsistentWithAnswer("reps_plus_one_in_window")
                    // Timestamp proximity (or distance) cannot attribute a review to our commit.
                    else -> Verdict.Unattributable("review_time_outside_window")
                }
            }
            0 -> when {
                after.unchangedFrom(before) ->
                    if (before.inFilteredDeck) Verdict.Unattributable("filtered_deck_no_observable_change")
                    else Verdict.Unchanged("no_state_change_observed")
                before.inFilteredDeck && tightWindow ->
                    Verdict.Unattributable("preview_transition_not_attributable")
                else -> Verdict.Unattributable("state_changed_without_review")
            }
            else -> Verdict.Unattributable("external_review_activity")
        }
    }
}
