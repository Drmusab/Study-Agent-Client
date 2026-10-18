package com.studyagent.client.data

import com.studyagent.client.core.anki.AnkiTransactionFamily
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionFact
import com.studyagent.client.anki.fake.InMemoryReviewerActionStore
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.ReviewCommitFailure
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitPhase
import com.studyagent.client.core.anki.ReviewCommitRecord
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.create.CreationLedgerResult
import com.studyagent.client.core.anki.create.NoteCreationRecord
import com.studyagent.client.core.anki.edit.NoteLedgerResult
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.repository.LedgerUnresolvedTransactionReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21M Revision 2 — the ledger read-only projection behind diagnostics (CHECKPOINT 21M-17,
 * AUDIT-21M-R2-14).
 *
 * Diagnostics may read what a durable ledger already keeps and report the **transaction backend**, the
 * **status** and a **count**. It may never write, retry or reassign, and it may never turn "this family
 * could not be read" into "0 unresolved" — a fabricated zero is the one number that would tell a user
 * their ambiguous commits have disappeared. Three outcomes must therefore stay distinguishable:
 *
 * - read, and empty ⇒ a truthful `0`;
 * - refused or unreadable ⇒ `unavailableFamilies`;
 * - no projection supplied in this build ⇒ `notProjectedFamilies`.
 *
 * The class under test is constructed from read lambdas only, so it cannot reach a ledger's write path,
 * a backend, a coordinator or a preference.
 */
class LedgerUnresolvedTransactionReaderTest {

    private val local = AnkiBackendId.AnkiDroidLocal
    private val pc = AnkiBackendId.PcAgent("home")

    private fun reviewCommit(
        id: AnkiBackendId,
        session: String,
        turn: Int,
        status: ReviewCommitStatus,
        cardId: String = "card-$session-$turn"
    ) = ReviewCommitRecord(
        commitId = ReviewCommitId(
            backendId = id,
            studySessionId = session,
            turnId = ReviewTurnId("$session-t$turn")
        ),
        card = AnkiCardRef(backendId = id, cardId = cardId, noteId = "note-1"),
        rating = Rating.GOOD,
        status = status,
        // The record's own invariants, not the reader's: a resolved-but-unfinished transaction carries
        // a reason, a terminal one carries none, and COMMITTED carries the rating that was applied.
        attemptCount = if (status == ReviewCommitStatus.PREPARED) 0 else 1,
        phase = ReviewCommitPhase.INTENT_PERSISTED,
        failure = when (status) {
            ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitFailure("no_effect")
            ReviewCommitStatus.AMBIGUOUS -> ReviewCommitFailure("unknown")
            else -> null
        },
        committedRating = if (status == ReviewCommitStatus.COMMITTED) Rating.GOOD else null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        ratedAtEpochMs = 900L
    )

    private fun reader(
        reviewCommits: (suspend () -> List<ReviewCommitRecord>)? = null,
        reviewerActions: (suspend () -> List<com.studyagent.client.core.anki.ReviewerActionRecord>)? = null,
        noteMutations: (suspend () -> NoteLedgerResult<List<NoteMutationRecord>>)? = null,
        noteCreations: (suspend () -> CreationLedgerResult<List<NoteCreationRecord>>)? = null
    ) = LedgerUnresolvedTransactionReader(
        reviewCommits = reviewCommits,
        reviewerActions = reviewerActions,
        noteMutations = noteMutations,
        noteCreations = noteCreations
    )

    // ------------------------------------------------------------------ projection

    @Test
    fun `a projected family reports each record's own backend and status`() = runTest {
        val read = reader(reviewCommits = {
            listOf(
                reviewCommit(local, "s-1", 1, ReviewCommitStatus.PREPARED),
                reviewCommit(pc, "s-2", 1, ReviewCommitStatus.AMBIGUOUS),
                reviewCommit(local, "s-3", 1, ReviewCommitStatus.RETRY_ALLOWED)
            )
        }).read()

        assertTrue(read.loaded)
        assertEquals(emptySet<AnkiTransactionFamily>(), read.unavailableFamilies)
        assertEquals(
            setOf(
                AnkiTransactionFamily.REVIEWER_ACTION,
                AnkiTransactionFamily.NOTE_MUTATION,
                AnkiTransactionFamily.NOTE_CREATION
            ),
            read.notProjectedFamilies
        )
        assertEquals(
            listOf(
                Triple(AnkiTransactionFamily.REVIEW_COMMIT, local, "PREPARED"),
                Triple(AnkiTransactionFamily.REVIEW_COMMIT, pc, "AMBIGUOUS"),
                Triple(AnkiTransactionFamily.REVIEW_COMMIT, local, "RETRY_ALLOWED")
            ),
            read.facts.map { Triple(it.family, it.backendId, it.statusToken) }
        )
    }

    @Test
    fun `the projection reuses the ledger's own status vocabulary and adds nothing`() = runTest {
        val statuses = listOf(
            ReviewCommitStatus.PREPARED,
            ReviewCommitStatus.SUBMITTING,
            ReviewCommitStatus.RETRY_ALLOWED,
            ReviewCommitStatus.AMBIGUOUS,
            ReviewCommitStatus.COMMITTED
        )
        val read = reader(reviewCommits = {
            statuses.mapIndexed { index, status -> reviewCommit(local, "s-$index", 1, status) }
        }).read()

        assertEquals(statuses.map { it.name }, read.facts.map { it.statusToken })
    }

    @Test
    fun `the reviewer-action family is read through the real durable ledger seam`() = runTest {
        // The same wiring production uses: `reviewerActionLedger.unresolved()`. An empty durable store
        // is a *read*, so the family is neither unavailable nor unprojected — a truthful zero.
        val ledger = InMemoryReviewerActionStore().restart(clock = { 1L })
        val read = reader(reviewerActions = { ledger.unresolved() }).read()

        assertTrue(read.loaded)
        assertEquals(emptyList<AnkiUnresolvedTransactionFact>(), read.facts)
        assertEquals(emptySet<AnkiTransactionFamily>(), read.unavailableFamilies)
        assertEquals(
            setOf(
                AnkiTransactionFamily.REVIEW_COMMIT,
                AnkiTransactionFamily.NOTE_MUTATION,
                AnkiTransactionFamily.NOTE_CREATION
            ),
            read.notProjectedFamilies
        )
    }

    @Test
    fun `an empty typed projection is a read, not a missing projection`() = runTest {
        val read = reader(
            noteMutations = { NoteLedgerResult.Ok(emptyList()) },
            noteCreations = { CreationLedgerResult.Ok(emptyList()) }
        ).read()

        assertTrue(read.loaded)
        assertEquals("a read-but-empty family yields no fact, so the renderer prints a truthful 0",
            emptyList<AnkiUnresolvedTransactionFact>(), read.facts)
        assertEquals(emptySet<AnkiTransactionFamily>(), read.unavailableFamilies)
        assertEquals(
            setOf(AnkiTransactionFamily.REVIEW_COMMIT, AnkiTransactionFamily.REVIEWER_ACTION),
            read.notProjectedFamilies
        )
    }

    // ------------------------------------------------------------------ honesty about unreadable families

    @Test
    fun `a family with no projection is reported as not projected, never as zero`() = runTest {
        val read = reader().read()

        assertFalse(read.loaded)
        assertEquals(emptyList<AnkiUnresolvedTransactionFact>(), read.facts)
        assertEquals(emptySet<AnkiTransactionFamily>(), read.unavailableFamilies)
        assertEquals(AnkiTransactionFamily.entries.toSet(), read.notProjectedFamilies)
    }

    @Test
    fun `a family whose projection is refused or unreadable says so`() = runTest {
        val read = reader(
            noteMutations = { NoteLedgerResult.Unavailable("db busy") },
            noteCreations = { CreationLedgerResult.Rejected("ledger sealed") }
        ).read()

        // A read in which every supplied family was refused is still a read: the surface reports
        // `read_unavailable` per family instead of falling silent or printing zeros.
        assertTrue("a refused read is still reported", read.loaded)
        assertEquals(emptyList<AnkiUnresolvedTransactionFact>(), read.facts)
        assertEquals(
            setOf(AnkiTransactionFamily.NOTE_MUTATION, AnkiTransactionFamily.NOTE_CREATION),
            read.unavailableFamilies
        )
        assertEquals(
            setOf(AnkiTransactionFamily.REVIEW_COMMIT, AnkiTransactionFamily.REVIEWER_ACTION),
            read.notProjectedFamilies
        )
    }

    @Test
    fun `a family whose read throws is unavailable, while a readable family still projects`() = runTest {
        val read = reader(
            reviewCommits = { listOf(reviewCommit(local, "s-1", 1, ReviewCommitStatus.PREPARED)) },
            reviewerActions = { throw IllegalStateException("content://ledger/private") }
        ).read()

        assertTrue(read.loaded)
        assertEquals(1, read.facts.size)
        assertEquals(setOf(AnkiTransactionFamily.REVIEWER_ACTION), read.unavailableFamilies)
        assertEquals(
            setOf(AnkiTransactionFamily.NOTE_MUTATION, AnkiTransactionFamily.NOTE_CREATION),
            read.notProjectedFamilies
        )
    }

    @Test
    fun `cancellation is never swallowed into an unavailable family`() = runTest {
        val thrown = try {
            reader(reviewCommits = { throw CancellationException("cancelled") }).read()
            null
        } catch (failure: CancellationException) {
            failure
        }
        assertTrue("cancellation must propagate", thrown != null)
    }

    @Test
    fun `the failure detail never reaches a fact`() = runTest {
        val read = reader(
            reviewCommits = { throw IllegalStateException("content://private/secret-key") }
        ).read()

        assertEquals(emptyList<AnkiUnresolvedTransactionFact>(), read.facts)
        // The family is named, nothing else: no reason text, no endpoint, no path.
        assertEquals(setOf(AnkiTransactionFamily.REVIEW_COMMIT), read.unavailableFamilies)
        assertFalse(read.toString().contains("secret-key"))
        assertFalse(read.toString().contains("content://"))
    }

    // ------------------------------------------------------------------ privacy of a projected fact

    @Test
    fun `a projected fact carries no record content, key or timestamp`() = runTest {
        val secretCard = "card-secret-987654"
        val fact = reader(reviewCommits = {
            listOf(reviewCommit(local, "s-1", 1, ReviewCommitStatus.PREPARED, cardId = secretCard))
        }).read().facts.single()

        // Exactly three fields: family, transaction backend, the ledger's status token.
        assertEquals(
            "AnkiUnresolvedTransactionFact(" +
                "family=${fact.family}, backendId=${fact.backendId}, statusToken=${fact.statusToken})",
            fact.toString()
        )
        assertFalse(fact.toString().contains(secretCard))
        assertFalse(fact.toString().contains("Epoch"))
        assertFalse(fact.toString().contains("attempt"))
        assertFalse(fact.toString().contains("s-1"))
        assertEquals(local, fact.backendId)
    }
}
