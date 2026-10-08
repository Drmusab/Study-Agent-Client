package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.edit.NoteMutationEvent
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationOperation
import com.studyagent.client.core.anki.edit.NoteMutationPlan
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationTransitionResult
import com.studyagent.client.core.anki.edit.NoteMutationTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GATE 17 §33 — the closed transition table, checked exhaustively against an explicit expectation. */
class NoteMutationTransitionTest {

    private val backend = AnkiBackendId.AnkiDroidLocal

    /** A two-operation plan (content, then deck) so index-dependent guards are exercised. */
    private fun record(status: NoteMutationStatus, lastEntered: Int, attempts: Int = 1): NoteMutationRecord =
        NoteMutationRecord(
            mutationId = NoteMutationId("m"),
            backendId = backend,
            cardRef = AnkiCardRef(backend, cardId = "c", noteId = "n", cardOrd = 0),
            noteRef = AnkiNoteRef(backend, "n"),
            noteTypeId = "model",
            changedFields = emptyList(),
            tagsChanged = false,
            deckChange = null,
            plan = NoteMutationPlan(
                listOf(
                    NoteMutationOperation.UpdateNoteContent(updatesFields = true, updatesTags = false),
                    NoteMutationOperation.ChangeDeck(AnkiDeckRef(backend, "d"))
                )
            ),
            status = status,
            lastEnteredOperation = lastEntered,
            attemptCount = attempts,
            reason = NoteMutationReason.NONE,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L
        )

    private fun accepted(result: NoteMutationTransitionResult): NoteMutationStatus? =
        (result as? NoteMutationTransitionResult.Accepted)?.record?.status

    private fun allEvents(): List<NoteMutationEvent> = listOf(
        NoteMutationEvent.EnterMutationBoundary,
        NoteMutationEvent.EnterNextOperation(1),
        NoteMutationEvent.BackendConfirmedApplied,
        NoteMutationEvent.BackendConfirmedNotApplied,
        NoteMutationEvent.BackendConflict,
        NoteMutationEvent.BackendOutcomeUnknown,
        NoteMutationEvent.PreBoundaryConflict(NoteMutationReason.CONFLICT_BEFORE_WRITE),
        NoteMutationEvent.RetryRequested,
        NoteMutationEvent.RecoveryNormalizedUnknown,
        NoteMutationEvent.ReconciliationConfirmedApplied,
        NoteMutationEvent.ReconciliationConfirmedNotApplied
    )

    /**
     * The complete expected table at lastEnteredOperation == 0 (first operation entered, plan of two).
     * Any (status, event) pair missing from this map must be rejected.
     */
    private val expectedAtIndexZero: Map<Pair<NoteMutationStatus, String>, NoteMutationStatus> = mapOf(
        (NoteMutationStatus.PREPARED to "EnterMutationBoundary") to NoteMutationStatus.SUBMITTING,
        (NoteMutationStatus.PREPARED to "PreBoundaryConflict") to NoteMutationStatus.CONFLICT,
        (NoteMutationStatus.RETRY_ALLOWED to "RetryRequested") to NoteMutationStatus.PREPARED,
        (NoteMutationStatus.RETRY_ALLOWED to "PreBoundaryConflict") to NoteMutationStatus.CONFLICT,
        (NoteMutationStatus.SUBMITTING to "EnterNextOperation") to NoteMutationStatus.SUBMITTING,
        (NoteMutationStatus.SUBMITTING to "BackendConfirmedNotApplied") to NoteMutationStatus.RETRY_ALLOWED,
        (NoteMutationStatus.SUBMITTING to "BackendConflict") to NoteMutationStatus.CONFLICT,
        (NoteMutationStatus.SUBMITTING to "BackendOutcomeUnknown") to NoteMutationStatus.AMBIGUOUS,
        (NoteMutationStatus.SUBMITTING to "RecoveryNormalizedUnknown") to NoteMutationStatus.AMBIGUOUS,
        (NoteMutationStatus.AMBIGUOUS to "ReconciliationConfirmedApplied") to NoteMutationStatus.APPLIED,
        (NoteMutationStatus.AMBIGUOUS to "ReconciliationConfirmedNotApplied") to NoteMutationStatus.RETRY_ALLOWED
    )

    private fun eventName(event: NoteMutationEvent): String = when (event) {
        is NoteMutationEvent.EnterMutationBoundary -> "EnterMutationBoundary"
        is NoteMutationEvent.EnterNextOperation -> "EnterNextOperation"
        is NoteMutationEvent.BackendConfirmedApplied -> "BackendConfirmedApplied"
        is NoteMutationEvent.BackendConfirmedNotApplied -> "BackendConfirmedNotApplied"
        is NoteMutationEvent.BackendConflict -> "BackendConflict"
        is NoteMutationEvent.BackendOutcomeUnknown -> "BackendOutcomeUnknown"
        is NoteMutationEvent.PreBoundaryConflict -> "PreBoundaryConflict"
        is NoteMutationEvent.RetryRequested -> "RetryRequested"
        is NoteMutationEvent.RecoveryNormalizedUnknown -> "RecoveryNormalizedUnknown"
        is NoteMutationEvent.ReconciliationConfirmedApplied -> "ReconciliationConfirmedApplied"
        is NoteMutationEvent.ReconciliationConfirmedNotApplied -> "ReconciliationConfirmedNotApplied"
    }

    @Test
    fun everyStatusEventPairMatchesTheClosedTableAtIndexZero() {
        for (status in NoteMutationStatus.values()) {
            for (event in allEvents()) {
                val rec = record(status, lastEntered = 0)
                val actual = accepted(NoteMutationTransitions.apply(rec, event, nowEpochMs = 9L))
                val expected = expectedAtIndexZero[status to eventName(event)]
                assertEquals("transition $status x ${eventName(event)}", expected, actual)
            }
        }
    }

    @Test
    fun appliedAndConflictAreTerminalForEveryEvent() {
        for (status in listOf(NoteMutationStatus.APPLIED, NoteMutationStatus.CONFLICT)) {
            assertTrue(status.isTerminal)
            for (event in allEvents()) {
                assertEquals(null, accepted(NoteMutationTransitions.apply(record(status, 0), event, 9L)))
            }
        }
    }

    @Test
    fun ambiguousCannotBeRetriedDirectly() {
        val ambiguous = record(NoteMutationStatus.AMBIGUOUS, lastEntered = 0)
        assertEquals(null, accepted(NoteMutationTransitions.apply(ambiguous, NoteMutationEvent.RetryRequested, 9L)))
        assertEquals(null, accepted(NoteMutationTransitions.apply(ambiguous, NoteMutationEvent.EnterMutationBoundary, 9L)))
        assertFalse(NoteMutationStatus.AMBIGUOUS.isTerminal)
    }

    @Test
    fun retryReturnsToPreparedWithSameIdAndNeverSkipsToSubmitting() {
        val retryable = record(NoteMutationStatus.RETRY_ALLOWED, lastEntered = 0)
        val requeued = (NoteMutationTransitions.apply(retryable, NoteMutationEvent.RetryRequested, 9L) as NoteMutationTransitionResult.Accepted).record
        assertEquals(NoteMutationStatus.PREPARED, requeued.status)
        assertEquals(retryable.mutationId, requeued.mutationId)
        assertEquals(null, accepted(NoteMutationTransitions.apply(retryable, NoteMutationEvent.EnterNextOperation(1), 9L)))
    }

    @Test
    fun boundaryEntryIncrementsAttemptsAndSetsIndexZero() {
        val prepared = record(NoteMutationStatus.PREPARED, lastEntered = -1, attempts = 0)
        val entered = (NoteMutationTransitions.apply(prepared, NoteMutationEvent.EnterMutationBoundary, 9L) as NoteMutationTransitionResult.Accepted).record
        assertEquals(NoteMutationStatus.SUBMITTING, entered.status)
        assertEquals(0, entered.lastEnteredOperation)
        assertEquals(1, entered.attemptCount)
    }

    @Test
    fun nextOperationMustBeExactlyOneStepForward() {
        val submitting = record(NoteMutationStatus.SUBMITTING, lastEntered = 0)
        assertEquals(NoteMutationStatus.SUBMITTING, accepted(NoteMutationTransitions.apply(submitting, NoteMutationEvent.EnterNextOperation(1), 9L)))
        assertEquals(null, accepted(NoteMutationTransitions.apply(submitting, NoteMutationEvent.EnterNextOperation(0), 9L)))
        assertEquals(null, accepted(NoteMutationTransitions.apply(submitting, NoteMutationEvent.EnterNextOperation(2), 9L)))
    }

    @Test
    fun appliedRequiresEveryOperationEntered() {
        val firstOnly = record(NoteMutationStatus.SUBMITTING, lastEntered = 0)
        assertEquals(null, accepted(NoteMutationTransitions.apply(firstOnly, NoteMutationEvent.BackendConfirmedApplied, 9L)))
        val last = record(NoteMutationStatus.SUBMITTING, lastEntered = 1)
        assertEquals(NoteMutationStatus.APPLIED, accepted(NoteMutationTransitions.apply(last, NoteMutationEvent.BackendConfirmedApplied, 9L)))
    }

    @Test
    fun laterOperationCannotClaimProvenNonApplicationOrConflict() {
        // After an earlier operation may have applied, a "not applied" claim is not a retry permit.
        val later = record(NoteMutationStatus.SUBMITTING, lastEntered = 1)
        assertEquals(null, accepted(NoteMutationTransitions.apply(later, NoteMutationEvent.BackendConfirmedNotApplied, 9L)))
        assertEquals(null, accepted(NoteMutationTransitions.apply(later, NoteMutationEvent.BackendConflict, 9L)))
        val ambiguousLater = record(NoteMutationStatus.AMBIGUOUS, lastEntered = 1)
        assertEquals(null, accepted(NoteMutationTransitions.apply(ambiguousLater, NoteMutationEvent.ReconciliationConfirmedNotApplied, 9L)))
    }

    @Test
    fun outcomeUnknownAfterEarlierOperationIsRecordedAsPartial() {
        val later = record(NoteMutationStatus.SUBMITTING, lastEntered = 1)
        val ambiguous = (NoteMutationTransitions.apply(later, NoteMutationEvent.BackendOutcomeUnknown, 9L) as NoteMutationTransitionResult.Accepted).record
        assertEquals(NoteMutationReason.PARTIAL_OPERATION_UNKNOWN, ambiguous.reason)
    }

    @Test
    fun rejectedTransitionLeavesStatusUnchanged() {
        val terminal = record(NoteMutationStatus.APPLIED, lastEntered = 1)
        val result = NoteMutationTransitions.apply(terminal, NoteMutationEvent.RetryRequested, 9L)
        assertTrue(result is NoteMutationTransitionResult.Rejected)
        assertEquals(NoteMutationStatus.APPLIED, (result as NoteMutationTransitionResult.Rejected).from)
    }

    @Test
    fun statusEnumIsExactlyTheSpecifiedSixValues() {
        assertEquals(
            listOf("PREPARED", "SUBMITTING", "APPLIED", "RETRY_ALLOWED", "AMBIGUOUS", "CONFLICT"),
            NoteMutationStatus.values().map { it.name }
        )
        assertEquals(
            setOf(NoteMutationStatus.APPLIED, NoteMutationStatus.CONFLICT),
            NoteMutationStatus.values().filter { it.isTerminal }.toSet()
        )
    }
}
