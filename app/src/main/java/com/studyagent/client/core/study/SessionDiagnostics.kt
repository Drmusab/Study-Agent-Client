package com.studyagent.client.core.study

import kotlinx.coroutines.flow.StateFlow

/**
 * Diagnostics exposed from the machine (§143).
 * Sanitized: never includes full answer text.
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
    val hasPendingRatingConfirmation: Boolean
)

fun SessionMachineState.toDiagnostics(): SessionDiagnosticsSnapshot = SessionDiagnosticsSnapshot(
    sessionId = session?.sessionId,
    epoch = epoch,
    phase = SessionPhase.serverPhaseName(phase),
    currentCardId = cardTurn?.cardId,
    turnGeneration = cardTurn?.generation,
    turnId = cardTurn?.turnId,
    pendingAction = pendingAction?.let { "${it.type}:${it.messageId.take(8)}" },
    connectionStatus = connection.name,
    lastAcceptedEvent = lastAcceptedEvent,
    lastRejectedEvent = lastRejectedEvent,
    lastRejectedReason = lastRejectedReason,
    ledgerSize = ledger.entries.size,
    recentMessageIdsCount = recentServerMessageIds.size,
    historySize = transitionHistory.size,
    hasPendingTranscript = pendingTranscript != null,
    hasPendingRatingConfirmation = pendingRatingConfirmation != null
)
