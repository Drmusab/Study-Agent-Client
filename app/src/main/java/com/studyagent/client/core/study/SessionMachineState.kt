package com.studyagent.client.core.study

import com.studyagent.client.core.models.StudyCard

/**
 * Authoritative internal state (§73). Immutable snapshot; one owner.
 * Public flows `StudyState` / `StudySession` are derived from this so they
 * can never disagree (§75).
 */
data class SessionMachineState(
    /** Local generation; bumped on each new session (§14). */
    val epoch: Long = 1L,
    val phase: SessionPhase = SessionPhase.Idle,
    val session: StudySessionSnapshot? = null,
    val cardTurn: CardTurn? = null,
    /** Ordered history: last accepted cardTurnIds for 1000-card sim (§137) */
    val cardTurnHistory: List<String> = emptyList(),
    val pendingAction: PendingAction? = null,
    val pendingActionQueue: List<PendingAction> = emptyList(),
    val pauseContext: ResumeContext? = null,
    val connection: SessionConnectionStatus = SessionConnectionStatus.CONNECTED,
    val error: SessionProblemHolder? = null,
    /** For graceful session end when server unreachable §82. */
    val finishingIsLocalOnly: Boolean = false,
    val ledger: SubmissionLedger = SubmissionLedger(),
    /** Bounded LRU of recent server messageIds (§22). */
    val recentServerMessageIds: LinkedHashSet<String> = linkedSetOf(),
    /** Bounded ring buffer (§142). */
    val transitionHistory: ArrayDeque<TransitionRecord> = ArrayDeque(),
    /** Diagnostics counters. */
    val lastAcceptedEvent: String? = null,
    val lastRejectedEvent: String? = null,
    val lastRejectedReason: String? = null,
    /** Pending spoken-rating confirmation idempotency §78. */
    val pendingRatingConfirmation: PendingRatingConfirmation? = null,
    /** Pending transcript waiting for user approval (autoSubmit=false). */
    val pendingTranscript: PendingTranscript? = null,
    /** Active speech effect IDs for turn-ownership validation (§64, §79). */
    val activeSpeechEffectId: String? = null,
    val activeRecognitionEffectId: String? = null,
    /** Card generation counter for turn identity. */
    val cardGeneration: Long = 0L,
) {
    val isIdle: Boolean get() = phase is SessionPhase.Idle
    val isFinished: Boolean get() = phase is SessionPhase.Finished
    val hasSession: Boolean get() = session != null
    val currentCardId: String? get() = cardTurn?.cardId ?: session?.currentCard?.id
    val sessionId: String? get() = session?.sessionId

    fun withPhase(newPhase: SessionPhase): SessionMachineState = copy(phase = newPhase)

    fun withCardTurn(turn: CardTurn?): SessionMachineState = copy(cardTurn = turn)

    /** Record a transition; keep last 200 (§142). */
    fun recordTransition(
        event: StudyEvent,
        from: SessionPhase,
        to: SessionPhase,
        reason: String? = null
    ): SessionMachineState {
        val record = TransitionRecord(
            epoch = epoch,
            from = from,
            event = event.debugName,
            to = to,
            cardId = currentCardId,
            reason = reason,
            atMs = System.currentTimeMillis()
        )
        val hist = ArrayDeque(transitionHistory)
        hist.addLast(record)
        while (hist.size > 200) hist.removeFirst()
        return copy(transitionHistory = hist, lastAcceptedEvent = event.debugName)
    }

    fun recordRejected(event: StudyEvent, reason: String): SessionMachineState {
        val hist = ArrayDeque(transitionHistory)
        hist.addLast(
            TransitionRecord(
                epoch = epoch,
                from = phase,
                event = event.debugName,
                to = phase,
                cardId = currentCardId,
                reason = "REJECTED: $reason",
                atMs = System.currentTimeMillis()
            )
        )
        while (hist.size > 200) hist.removeFirst()
        return copy(
            transitionHistory = hist,
            lastRejectedEvent = event.debugName,
            lastRejectedReason = reason
        )
    }

    fun rememberServerMessageId(id: String?, maxSize: Int = 200): SessionMachineState {
        if (id.isNullOrBlank()) return this
        if (recentServerMessageIds.contains(id)) return this
        val newSet = LinkedHashSet(recentServerMessageIds)
        newSet.add(id)
        while (newSet.size > maxSize) {
            newSet.remove(newSet.first())
        }
        return copy(recentServerMessageIds = newSet)
    }

    fun isDuplicateServerMessage(id: String?): Boolean = !id.isNullOrBlank() && recentServerMessageIds.contains(id)

    companion object {
        fun initial(epoch: Long = 1L): SessionMachineState = SessionMachineState(epoch = epoch)
    }
}

data class SessionProblemHolder(
    val problem: SessionProblem,
    val message: String,
    val recoverable: Boolean,
    val atMs: Long = System.currentTimeMillis()
)

enum class SessionConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    RECOVERING
}

data class PendingRatingConfirmation(
    val rating: com.studyagent.client.core.models.Rating,
    val turnId: String,
    val cardId: String,
    val epoch: Long,
    val atMs: Long = System.currentTimeMillis()
)

data class PendingTranscript(
    val text: String,
    val cardId: String,
    val turnId: String,
    val epoch: Long,
    val atMs: Long = System.currentTimeMillis()
)

data class TransitionRecord(
    val epoch: Long,
    val from: SessionPhase,
    val event: String,
    val to: SessionPhase,
    val cardId: String?,
    val reason: String? = null,
    val atMs: Long
)
