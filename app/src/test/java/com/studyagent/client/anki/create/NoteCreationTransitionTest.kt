package com.studyagent.client.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.create.CreationMediaStep
import com.studyagent.client.core.anki.create.CreationMediaKind
import com.studyagent.client.core.anki.create.NoteCreationEvent
import com.studyagent.client.core.anki.create.NoteCreationId
import com.studyagent.client.core.anki.create.NoteCreationReason
import com.studyagent.client.core.anki.create.NoteCreationRecord
import com.studyagent.client.core.anki.create.NoteCreationStatus
import com.studyagent.client.core.anki.create.NoteCreationTransitionResult
import com.studyagent.client.core.anki.create.NoteCreationTransitions
import com.studyagent.client.core.anki.create.newPreparedNoteCreationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 18 — the closed transition table (CONTRACT-18-41). Every legal edge is asserted, and every
 * status is asserted to reject everything the table does not list. The table is the law: a rejected
 * event must never change the record.
 */
class NoteCreationTransitionTest {

    private val backend = AnkiBackendId.AnkiDroidLocal

    private fun prepared(
        mediaCount: Int = 0,
        storedNames: List<String?> = emptyList()
    ): NoteCreationRecord {
        val base = newPreparedNoteCreationRecord(
            creationId = NoteCreationId("creation-1"),
            backendId = backend,
            collectionKey = null,
            modelId = "model-basic",
            modelName = "Basic",
            fieldCount = 2,
            tagCount = 1,
            mediaSteps = List(mediaCount) { index ->
                CreationMediaStep(
                    index = index,
                    requestedName = "media$index.png",
                    kind = CreationMediaKind.IMAGE,
                    storedName = storedNames.getOrNull(index)
                )
            },
            nowEpochMs = 100L
        )
        return base
    }

    private fun withStatus(record: NoteCreationRecord, status: NoteCreationStatus, noteId: String? = null) =
        record.copy(status = status, createdNoteId = noteId)

    private fun accepted(result: NoteCreationTransitionResult): NoteCreationRecord =
        (result as NoteCreationTransitionResult.Accepted).record

    private fun assertRejected(result: NoteCreationTransitionResult) {
        assertTrue("expected rejection, got $result", result is NoteCreationTransitionResult.Rejected)
    }

    // ---------------------------------------------------------------- PREPARED

    @Test
    fun `prepared enters the first media boundary`() {
        val next = accepted(
            NoteCreationTransitions.apply(
                prepared(mediaCount = 2), NoteCreationEvent.EnterMediaBoundary(0), 200L
            )
        )
        assertEquals(NoteCreationStatus.STORING_MEDIA, next.status)
        assertEquals(0, next.lastEnteredStep)
    }

    @Test
    fun `prepared cannot enter media boundary without a media plan`() {
        assertRejected(
            NoteCreationTransitions.apply(prepared(), NoteCreationEvent.EnterMediaBoundary(0), 200L)
        )
    }

    @Test
    fun `prepared with media cannot skip straight to the note boundary`() {
        assertRejected(
            NoteCreationTransitions.apply(prepared(mediaCount = 1), NoteCreationEvent.EnterNoteBoundary, 200L)
        )
    }

    @Test
    fun `prepared without media enters the note boundary and counts the attempt`() {
        val next = accepted(
            NoteCreationTransitions.apply(prepared(), NoteCreationEvent.EnterNoteBoundary, 200L)
        )
        assertEquals(NoteCreationStatus.CREATING_NOTE, next.status)
        assertEquals(1, next.attemptCount)
        assertEquals(0, next.lastEnteredStep)
    }

    @Test
    fun `prepared abandoned at restart closes without any boundary note`() {
        val next = accepted(
            NoteCreationTransitions.apply(prepared(), NoteCreationEvent.RestartAbandoned, 200L)
        )
        assertEquals(NoteCreationStatus.ABANDONED, next.status)
        assertEquals(NoteCreationReason.ABANDONED_ON_RESTART, next.reason)
    }

    @Test
    fun `prepared refused pre-boundary abandons`() {
        val next = accepted(
            NoteCreationTransitions.apply(prepared(), NoteCreationEvent.PreBoundaryRefused, 200L)
        )
        assertEquals(NoteCreationStatus.ABANDONED, next.status)
        assertEquals(NoteCreationReason.PRE_BOUNDARY_REFUSED, next.reason)
    }

    // ---------------------------------------------------------------- STORING_MEDIA

    private fun storing(mediaCount: Int = 2, entered: Int = 0, stored: List<String?> = listOf(null, null)) =
        withStatus(prepared(mediaCount, stored), NoteCreationStatus.STORING_MEDIA)
            .copy(lastEnteredStep = entered)

    @Test
    fun `storing media advances only to the next step`() {
        val next = accepted(
            NoteCreationTransitions.apply(storing(), NoteCreationEvent.EnterMediaBoundary(1), 200L)
        )
        assertEquals(1, next.lastEnteredStep)
        // Skipping or repeating a step is rejected.
        assertRejected(
            NoteCreationTransitions.apply(storing(), NoteCreationEvent.EnterMediaBoundary(0), 200L)
        )
        assertRejected(
            NoteCreationTransitions.apply(storing(), NoteCreationEvent.EnterMediaBoundary(2), 200L)
        )
    }

    @Test
    fun `storing media persists the backend returned name for the entered step only`() {
        val next = accepted(
            NoteCreationTransitions.apply(storing(), NoteCreationEvent.MediaStored(0, "stored_a.png"), 200L)
        )
        assertEquals("stored_a.png", next.mediaSteps[0].storedName)
        assertNull(next.mediaSteps[1].storedName)
        // A name for a step that was never entered is rejected.
        assertRejected(
            NoteCreationTransitions.apply(storing(), NoteCreationEvent.MediaStored(1, "x.png"), 200L)
        )
        // Re-storing over a known name is rejected: the first stored name is durable truth.
        val withName = storing(stored = listOf("a.png", null))
        assertRejected(
            NoteCreationTransitions.apply(withName, NoteCreationEvent.MediaStored(0, "b.png"), 200L)
        )
    }

    @Test
    fun `confirmed not stored closes as retryable non-creation`() {
        val next = accepted(
            NoteCreationTransitions.apply(storing(), NoteCreationEvent.MediaConfirmedNotStored, 200L)
        )
        assertEquals(NoteCreationStatus.RETRY_ALLOWED, next.status)
        assertEquals(NoteCreationReason.NOT_CREATED_BY_BACKEND, next.reason)
    }

    @Test
    fun `unknown media outcome abandons with the orphan risk recorded`() {
        val next = accepted(
            NoteCreationTransitions.apply(storing(), NoteCreationEvent.MediaOutcomeUnknown, 200L)
        )
        assertEquals(NoteCreationStatus.ABANDONED, next.status)
        assertEquals(NoteCreationReason.OUTCOME_UNKNOWN, next.reason)
        assertTrue(next.mediaBoundaryEntered)
    }

    @Test
    fun `note boundary requires every media step stored`() {
        val complete = storing(stored = listOf("a.png", "b.png"))
        val next = accepted(
            NoteCreationTransitions.apply(complete, NoteCreationEvent.EnterNoteBoundary, 200L)
        )
        assertEquals(NoteCreationStatus.CREATING_NOTE, next.status)
        assertEquals(2, next.lastEnteredStep)
        assertEquals(1, next.attemptCount)
        // Incomplete plan is rejected.
        assertRejected(
            NoteCreationTransitions.apply(storing(), NoteCreationEvent.EnterNoteBoundary, 200L)
        )
    }

    @Test
    fun `storing media pre-boundary refusal and restart abandonment close the record`() {
        assertEquals(
            NoteCreationStatus.ABANDONED,
            accepted(NoteCreationTransitions.apply(storing(), NoteCreationEvent.PreBoundaryRefused, 200L)).status
        )
        assertEquals(
            NoteCreationStatus.ABANDONED,
            accepted(NoteCreationTransitions.apply(storing(), NoteCreationEvent.RestartAbandoned, 200L)).status
        )
    }

    // ---------------------------------------------------------------- CREATING_NOTE

    private fun creating(mediaCount: Int = 0, stored: List<String?> = emptyList()) =
        withStatus(prepared(mediaCount, stored), NoteCreationStatus.CREATING_NOTE)
            .copy(lastEnteredStep = mediaCount, attemptCount = 1)

    @Test
    fun `backend confirmed created is terminal positive with the note id`() {
        val next = accepted(
            NoteCreationTransitions.apply(
                creating(), NoteCreationEvent.BackendConfirmedCreated("9001"), 200L
            )
        )
        assertEquals(NoteCreationStatus.CREATED, next.status)
        assertEquals("9001", next.createdNoteId)
    }

    @Test
    fun `backend confirmed not created is proven non-creation and retryable`() {
        val next = accepted(
            NoteCreationTransitions.apply(creating(), NoteCreationEvent.BackendConfirmedNotCreated, 200L)
        )
        assertEquals(NoteCreationStatus.RETRY_ALLOWED, next.status)
        assertEquals(NoteCreationReason.NOT_CREATED_BY_BACKEND, next.reason)
    }

    @Test
    fun `unknown backend outcome is ambiguous and never retryable`() {
        val next = accepted(
            NoteCreationTransitions.apply(creating(), NoteCreationEvent.BackendOutcomeUnknown, 200L)
        )
        assertEquals(NoteCreationStatus.AMBIGUOUS, next.status)
        assertEquals(NoteCreationReason.OUTCOME_UNKNOWN, next.reason)
        assertEquals(false, next.status.userRetryAllowed)
    }

    @Test
    fun `finalization failure after confirmation fails closed to ambiguous`() {
        val next = accepted(
            NoteCreationTransitions.apply(creating(), NoteCreationEvent.FinalizationFailedAfterConfirm, 200L)
        )
        assertEquals(NoteCreationStatus.AMBIGUOUS, next.status)
    }

    @Test
    fun `restart recovery of an unclosed note boundary is ambiguous`() {
        val next = accepted(
            NoteCreationTransitions.apply(creating(), NoteCreationEvent.RecoveryNormalizedUnknown, 200L)
        )
        assertEquals(NoteCreationStatus.AMBIGUOUS, next.status)
        assertEquals(NoteCreationReason.RECOVERED_AFTER_RESTART, next.reason)
    }

    // ---------------------------------------------------------------- RETRY_ALLOWED

    @Test
    fun `retry resets to prepared and clears stored names but keeps the plan`() {
        val retryable = withStatus(
            prepared(2, listOf("a.png", "b.png")), NoteCreationStatus.RETRY_ALLOWED
        )
        val next = accepted(
            NoteCreationTransitions.apply(retryable, NoteCreationEvent.RetryRequested, 200L)
        )
        assertEquals(NoteCreationStatus.PREPARED, next.status)
        assertEquals(-1, next.lastEnteredStep)
        assertEquals(2, next.mediaSteps.size)
        assertTrue(next.mediaSteps.all { it.storedName == null })
        assertEquals(NoteCreationReason.NONE, next.reason)
    }

    @Test
    fun `retryable abandoned at restart closes`() {
        val retryable = withStatus(prepared(), NoteCreationStatus.RETRY_ALLOWED)
        val next = accepted(
            NoteCreationTransitions.apply(retryable, NoteCreationEvent.RestartAbandoned, 200L)
        )
        assertEquals(NoteCreationStatus.ABANDONED, next.status)
    }

    // ---------------------------------------------------------------- AMBIGUOUS

    @Test
    fun `ambiguous closes only by human attestation`() {
        val ambiguous = withStatus(creating(), NoteCreationStatus.AMBIGUOUS)
        val created = accepted(
            NoteCreationTransitions.apply(ambiguous, NoteCreationEvent.UserAttestedCreated, 200L)
        )
        assertEquals(NoteCreationStatus.CREATED, created.status)
        assertEquals(NoteCreationReason.USER_ATTESTED_CREATED, created.reason)

        val absent = accepted(
            NoteCreationTransitions.apply(ambiguous, NoteCreationEvent.UserAttestedAbsent, 200L)
        )
        assertEquals(NoteCreationStatus.ABANDONED, absent.status)
        assertEquals(NoteCreationReason.USER_ATTESTED_ABSENT, absent.reason)

        // No automatic replay edge exists.
        assertRejected(NoteCreationTransitions.apply(ambiguous, NoteCreationEvent.RetryRequested, 200L))
        assertRejected(NoteCreationTransitions.apply(ambiguous, NoteCreationEvent.EnterNoteBoundary, 200L))
    }

    // ---------------------------------------------------------------- CREATED

    @Test
    fun `hydration failure never reopens a confirmed creation`() {
        val created = withStatus(creating(), NoteCreationStatus.CREATED, noteId = "9001")
        val next = accepted(
            NoteCreationTransitions.apply(created, NoteCreationEvent.NoteHydrationFailed, 200L)
        )
        assertEquals(NoteCreationStatus.CREATED, next.status)
        assertEquals("9001", next.createdNoteId)
        assertEquals(NoteCreationReason.HYDRATION_FAILED, next.reason)
        // Every other event is rejected on a terminal positive record.
        assertRejected(NoteCreationTransitions.apply(created, NoteCreationEvent.BackendOutcomeUnknown, 200L))
        assertRejected(NoteCreationTransitions.apply(created, NoteCreationEvent.RetryRequested, 200L))
    }

    @Test
    fun `abandoned has no outgoing edges`() {
        val abandoned = withStatus(prepared(), NoteCreationStatus.ABANDONED)
        for (event in listOf<NoteCreationEvent>(
            NoteCreationEvent.RetryRequested,
            NoteCreationEvent.EnterNoteBoundary,
            NoteCreationEvent.BackendConfirmedCreated("1"),
            NoteCreationEvent.RestartAbandoned,
            NoteCreationEvent.UserAttestedCreated
        )) {
            assertRejected(NoteCreationTransitions.apply(abandoned, event, 200L))
        }
    }

    @Test
    fun `exhaustive sweep rejects every unlisted edge`() {
        val allEvents = listOf<NoteCreationEvent>(
            NoteCreationEvent.EnterMediaBoundary(0),
            NoteCreationEvent.EnterMediaBoundary(1),
            NoteCreationEvent.MediaStored(0, "n.png"),
            NoteCreationEvent.MediaConfirmedNotStored,
            NoteCreationEvent.MediaOutcomeUnknown,
            NoteCreationEvent.EnterNoteBoundary,
            NoteCreationEvent.BackendConfirmedCreated("1"),
            NoteCreationEvent.BackendConfirmedNotCreated,
            NoteCreationEvent.BackendOutcomeUnknown,
            NoteCreationEvent.FinalizationFailedAfterConfirm,
            NoteCreationEvent.RecoveryNormalizedUnknown,
            NoteCreationEvent.PreBoundaryRefused,
            NoteCreationEvent.RetryRequested,
            NoteCreationEvent.RestartAbandoned,
            NoteCreationEvent.UserAttestedCreated,
            NoteCreationEvent.UserAttestedAbsent,
            NoteCreationEvent.NoteHydrationFailed
        )
        // The accepted edges per status, as locked in the table header of NoteCreationTransition.kt.
        val acceptedCount = mapOf(
            NoteCreationStatus.PREPARED to { event: NoteCreationEvent ->
                val record = prepared(mediaCount = 1)
                NoteCreationTransitions.apply(record, event, 1L)
            },
            NoteCreationStatus.STORING_MEDIA to { event: NoteCreationEvent ->
                NoteCreationTransitions.apply(storing(), event, 1L)
            },
            NoteCreationStatus.CREATING_NOTE to { event: NoteCreationEvent ->
                NoteCreationTransitions.apply(creating(), event, 1L)
            },
            NoteCreationStatus.RETRY_ALLOWED to { event: NoteCreationEvent ->
                NoteCreationTransitions.apply(withStatus(prepared(1), NoteCreationStatus.RETRY_ALLOWED), event, 1L)
            },
            NoteCreationStatus.AMBIGUOUS to { event: NoteCreationEvent ->
                NoteCreationTransitions.apply(withStatus(creating(), NoteCreationStatus.AMBIGUOUS), event, 1L)
            },
            NoteCreationStatus.CREATED to { event: NoteCreationEvent ->
                NoteCreationTransitions.apply(
                    withStatus(creating(), NoteCreationStatus.CREATED, noteId = "1"), event, 1L
                )
            },
            NoteCreationStatus.ABANDONED to { event: NoteCreationEvent ->
                NoteCreationTransitions.apply(withStatus(prepared(), NoteCreationStatus.ABANDONED), event, 1L)
            }
        )
        val expectedAccepted = mapOf(
            NoteCreationStatus.PREPARED to 3,
            NoteCreationStatus.STORING_MEDIA to 6,
            NoteCreationStatus.CREATING_NOTE to 5,
            NoteCreationStatus.RETRY_ALLOWED to 2,
            NoteCreationStatus.AMBIGUOUS to 2,
            NoteCreationStatus.CREATED to 1,
            NoteCreationStatus.ABANDONED to 0
        )
        for ((status, run) in acceptedCount) {
            val accepted = allEvents.count { event ->
                run(event) is NoteCreationTransitionResult.Accepted
            }
            assertEquals(
                "$status accepts exactly its locked table rows",
                expectedAccepted.getValue(status), accepted
            )
        }
    }
}
