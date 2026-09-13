package com.studyagent.client.core.study

/**
 * Exactly-once ledger (§24-§25). The per-card answer/rating guards from
 * `StudySessionRepository` are evolved into structured per-turn state so
 * send failures are recoverable (§27) and duplicate taps are harmless.
 *
 * Transport remains at-least-once; this ledger enforces exactly-once intent.
 */
data class SubmissionLedger(
    val entries: Map<String, TurnLedger> = emptyMap()
) {
    data class TurnLedger(
        val turnId: String,
        val cardId: String,
        val answerState: SubmissionState = SubmissionState.NOT_STARTED,
        val ratingState: SubmissionState = SubmissionState.NOT_STARTED,
        val skipState: SubmissionState = SubmissionState.NOT_STARTED,
        val answerMessageId: String? = null,
        val ratingMessageId: String? = null
    )

    enum class SubmissionState {
        NOT_STARTED,
        IN_FLIGHT,
        ACKNOWLEDGED,
        FAILED_RETRYABLE,
        FAILED_FINAL
    }

    fun forTurn(turnId: String): TurnLedger? = entries[turnId]

    fun forCardId(cardId: String): TurnLedger? = entries.values.firstOrNull { it.cardId == cardId }

    fun tryBeginAnswer(turnId: String, cardId: String, messageId: String): SubmissionLedger {
        val existing = entries[turnId]
        if (existing != null && existing.answerState != SubmissionState.NOT_STARTED &&
            existing.answerState != SubmissionState.FAILED_RETRYABLE) {
            return this
        }
        val updated = (existing ?: TurnLedger(turnId, cardId)).copy(
            answerState = SubmissionState.IN_FLIGHT,
            answerMessageId = messageId
        )
        return copy(entries = entries + (turnId to updated))
    }

    fun tryBeginRating(turnId: String, cardId: String, messageId: String): SubmissionLedger {
        val existing = entries[turnId]
        if (existing != null && existing.ratingState != SubmissionState.NOT_STARTED &&
            existing.ratingState != SubmissionState.FAILED_RETRYABLE) {
            return this
        }
        val updated = (existing ?: TurnLedger(turnId, cardId)).copy(
            ratingState = SubmissionState.IN_FLIGHT,
            ratingMessageId = messageId
        )
        return copy(entries = entries + (turnId to updated))
    }

    fun markAnswerAck(turnId: String): SubmissionLedger {
        val e = entries[turnId] ?: return this
        return copy(entries = entries + (turnId to e.copy(answerState = SubmissionState.ACKNOWLEDGED)))
    }

    fun markRatingAck(turnId: String): SubmissionLedger {
        val e = entries[turnId] ?: return this
        return copy(entries = entries + (turnId to e.copy(ratingState = SubmissionState.ACKNOWLEDGED)))
    }

    fun markAnswerFailed(turnId: String, retryable: Boolean): SubmissionLedger {
        val e = entries[turnId] ?: return this
        return copy(entries = entries + (turnId to e.copy(
            answerState = if (retryable) SubmissionState.FAILED_RETRYABLE else SubmissionState.FAILED_FINAL
        )))
    }

    fun markRatingFailed(turnId: String, retryable: Boolean): SubmissionLedger {
        val e = entries[turnId] ?: return this
        return copy(entries = entries + (turnId to e.copy(
            ratingState = if (retryable) SubmissionState.FAILED_RETRYABLE else SubmissionState.FAILED_FINAL
        )))
    }

    fun canBeginAnswer(turnId: String): Boolean {
        val e = entries[turnId] ?: return true
        return e.answerState == SubmissionState.NOT_STARTED || e.answerState == SubmissionState.FAILED_RETRYABLE
    }

    fun canBeginRating(turnId: String): Boolean {
        val e = entries[turnId] ?: return true
        return e.ratingState == SubmissionState.NOT_STARTED || e.ratingState == SubmissionState.FAILED_RETRYABLE
    }

    fun hasAnswerInFlight(turnId: String): Boolean = entries[turnId]?.answerState == SubmissionState.IN_FLIGHT
    fun hasRatingInFlight(turnId: String): Boolean = entries[turnId]?.ratingState == SubmissionState.IN_FLIGHT

    fun pruneOld(keepTurnIds: Set<String>): SubmissionLedger {
        if (entries.size <= 32) return this
        val pruned = entries.filterKeys { it in keepTurnIds }
        return copy(entries = pruned)
    }

    // Legacy compatibility helpers for cardId-based checks
    fun canBeginAnswerForCard(cardId: String): Boolean = forCardId(cardId)?.let {
        it.answerState == SubmissionLedger.SubmissionState.NOT_STARTED ||
            it.answerState == SubmissionLedger.SubmissionState.FAILED_RETRYABLE
    } ?: true

    fun canBeginRatingForCard(cardId: String): Boolean = forCardId(cardId)?.let {
        it.ratingState == SubmissionLedger.SubmissionState.NOT_STARTED ||
            it.ratingState == SubmissionLedger.SubmissionState.FAILED_RETRYABLE
    } ?: true
}
