package com.studyagent.client.core.study

/**
 * Session diagnostics (§59).
 *
 * Sanitized by construction: identifiers are abbreviated and no card/question/answer text is
 * present. What is present is exactly what is needed to explain a race:
 *
 * - *which* session and epoch this is (a stale epoch is the first thing to rule out),
 * - *which* turn was live (turn id + generation),
 * - *which* action is in flight and for how long,
 * - *what* the machine last accepted or rejected (the reducer's own verdict),
 * - and how full each bounded structure is (a counter that only ever grows is a bug).
 */
data class SessionDiagnosticsSnapshot(
    val sessionId: String?,
    val epoch: Long,
    val phase: String,
    val currentCardId: String?,
    val turnGeneration: Long?,
    val turnId: String?,
    val pendingAction: String?,
    val connectionStatus: String,
    val lastAcceptedEvent: String?,
    val lastRejectedEvent: String?,
    val lastRejectedReason: String?,
    val ledgerSize: Int,
    val recentMessageIdsCount: Int,
    val historySize: Int,
    val hasPendingTranscript: Boolean,
    val hasPendingRatingConfirmation: Boolean,
    // ---- added for the expanded Diagnostics snapshot (§59) ----
    /** `SUBMIT_ANSWER:1a2b3c4d` — type + abbreviated message id, or null when nothing is in flight. */
    val pendingActionDetail: String? = null,
    /** Age of [pendingAction] in ms, or -1 when unknown. */
    val pendingActionAgeMs: Long = -1L,
    val isPaused: Boolean = false,
    val isRecovering: Boolean = false,
    /** True when the session ended locally because the server never confirmed (§82). */
    val finishingIsLocalOnly: Boolean = false,
    val activeSpeechEffectId: String? = null,
    val activeRecognitionEffectId: String? = null,
    val cardTurnHistorySize: Int = 0,
    val inFlightAnswers: Int = 0,
    val inFlightRatings: Int = 0,
    /** Server error currently held by the machine, if any (message is sanitized by the caller). */
    val errorCode: String? = null,
    val errorRecoverable: Boolean? = null
)

fun SessionMachineState.toDiagnostics(nowMs: Long = System.currentTimeMillis()): SessionDiagnosticsSnapshot {
    val pending = pendingAction
    return SessionDiagnosticsSnapshot(
        sessionId = session?.sessionId,
        epoch = epoch,
        phase = SessionPhase.serverPhaseName(phase),
        currentCardId = cardTurn?.cardId,
        turnGeneration = cardTurn?.generation,
        turnId = cardTurn?.turnId,
        pendingAction = pending?.let { "${it.type}:${it.messageId.take(8)}" },
        connectionStatus = connection.name,
        lastAcceptedEvent = lastAcceptedEvent,
        lastRejectedEvent = lastRejectedEvent,
        lastRejectedReason = lastRejectedReason,
        ledgerSize = ledger.entries.size,
        recentMessageIdsCount = recentServerMessageIds.size,
        historySize = transitionHistory.size,
        hasPendingTranscript = pendingTranscript != null,
        hasPendingRatingConfirmation = pendingRatingConfirmation != null,
        pendingActionDetail = pending?.let { "${it.type}:${it.messageId.take(8)}" },
        pendingActionAgeMs = pending?.let { (nowMs - it.createdAtMs).coerceAtLeast(0L) } ?: -1L,
        isPaused = phase.isPaused,
        isRecovering = phase.isRecovering,
        finishingIsLocalOnly = finishingIsLocalOnly,
        activeSpeechEffectId = activeSpeechEffectId,
        activeRecognitionEffectId = activeRecognitionEffectId,
        cardTurnHistorySize = cardTurnHistory.size,
        inFlightAnswers = ledger.entries.values.count { it.answerState == SubmissionLedger.SubmissionState.IN_FLIGHT },
        inFlightRatings = ledger.entries.values.count { it.ratingState == SubmissionLedger.SubmissionState.IN_FLIGHT },
        errorCode = error?.problem?.name,
        errorRecoverable = error?.recoverable
    )
}

/**
 * Internal resource inventory of the session machine (§19).
 *
 * Every entry is a bounded structure with a known ceiling. The endurance tests assert these
 * *after* a thousand turns: a value proportional to turn count is a leak, not a statistic.
 */
data class MachineResourceCounts(
    /** Timer jobs currently registered; must return to 0 when the session is idle/terminal. */
    val pendingTimers: Int,
    /** Events dispatched but not yet processed by the machine coroutine. */
    val eventsAwaitingProcessing: Long,
    /** Distinct turns held in the submission ledger. */
    val ledgerEntries: Int,
    /** Dedup window of server message ids. */
    val recentServerMessageIds: Int,
    /** Bounded transition ring buffer. */
    val transitionHistory: Int,
    /** Bounded accepted-turn id history. */
    val cardTurnHistory: Int,
    val activeSpeechEffects: Int,
    val activeRecognitionEffects: Int,
    val hasPendingAction: Boolean
) {
    /** True when nothing is left over from finished work. */
    val quiescent: Boolean
        get() = eventsAwaitingProcessing == 0L && pendingTimers == 0 && activeSpeechEffects == 0 &&
            activeRecognitionEffects == 0

    fun render(): String = buildString {
        append("timers=").append(pendingTimers)
        append(" pendingEvents=").append(eventsAwaitingProcessing)
        append(" ledger=").append(ledgerEntries)
        append(" dedup=").append(recentServerMessageIds)
        append(" transitions=").append(transitionHistory)
        append(" turns=").append(cardTurnHistory)
        append(" speech=").append(activeSpeechEffects)
        append(" stt=").append(activeRecognitionEffects)
        append(" pendingAction=").append(hasPendingAction)
    }
}
