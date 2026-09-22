package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendSelector
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.CommitFailureClass
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.asCommitFailureClass
import com.studyagent.client.core.anki.isReadyForReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GATE 01 architecture contract tests (contract-test rule §96): only *behavior*
 * defined by the GATE 01 contract types is asserted here — backend-mode
 * resolution, identity equality semantics, exactly-once commit id composition,
 * availability truthfulness and commit-failure classification.
 *
 * No AnkiDroid, no Android framework, no PC protocol: the Anki domain layer
 * must stay backend-neutral (INV-ANKI-06), so these tests run on the JVM alone.
 */
class AnkiArchitectureContractTest {

    // ------------------------------------------------------------------ identity

    @Test
    fun `backend ids round-trip through their stable ids`() {
        AnkiBackendId.entries.forEach { id ->
            assertEquals(id, AnkiBackendId.fromStableId(id.stableId))
        }
        assertNull(AnkiBackendId.fromStableId("not-a-backend"))
        assertNull(AnkiBackendId.fromStableId(null))
    }

    @Test
    fun `AUTO mode pins no backend while explicit modes pin exactly one`() {
        assertNull(AnkiBackendMode.AUTO.pinnedBackendId())
        assertEquals(AnkiBackendId.ANKIDROID_LOCAL, AnkiBackendMode.ANKIDROID_LOCAL.pinnedBackendId())
        assertEquals(AnkiBackendId.PC_AGENT, AnkiBackendMode.PC_AGENT.pinnedBackendId())
    }

    @Test
    fun `card ref requires a usable identity and never fabricates one`() {
        try {
            AnkiCardRef(AnkiBackendId.ANKIDROID_LOCAL, null, null, null, null)
            fail("An AnkiCardRef with no cardId and no noteId+ord must not be constructible")
        } catch (_: IllegalArgumentException) {
            // expected — identity must come from the backend, never be invented (contract §9)
        }
        // noteId without ord is equally insufficient
        try {
            AnkiCardRef(AnkiBackendId.PC_AGENT, null, "n-1", null, null)
            fail("noteId without cardOrd must not be constructible")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `card identity is backend-qualified so equal raw ids never collide across backends`() {
        val local = AnkiCardRef(AnkiBackendId.ANKIDROID_LOCAL, "123", "45", 0, "col-a")
        val desktop = AnkiCardRef(AnkiBackendId.PC_AGENT, "123", "45", 0, "col-b")

        // INV-ANKI-06: card 123 on AnkiDroid is not card 123 on Desktop Anki.
        assertNotEquals(local, desktop)
        assertNotEquals(local.stableKey, desktop.stableKey)
    }

    @Test
    fun `note plus ord identifies a card when cardId is unavailable`() {
        val ref = AnkiCardRef(
            backendId = AnkiBackendId.ANKIDROID_LOCAL,
            cardId = null,
            noteId = "n-9",
            cardOrd = 1,
            collectionKey = "col-a"
        )
        assertTrue(ref.stableKey.contains("note:n-9#ord=1"))
    }

    // ------------------------------------------------------------------ turn vs card identity

    @Test
    fun `review commit ids are turn-scoped so a card reviewed twice yields two commits`() {
        val session = "session-7"
        val cardTurnOne = ReviewTurnId("7:card-42:1")
        val cardTurnTwo = ReviewTurnId("7:card-42:2")

        val first = ReviewCommitId(AnkiBackendId.PC_AGENT, session, cardTurnOne)
        val second = ReviewCommitId(AnkiBackendId.PC_AGENT, session, cardTurnTwo)

        // Same card, different turns: INV-ANKI-02/03 — card id alone is never a commit key.
        assertNotEquals(first, second)
        assertNotEquals(first.stableKey, second.stableKey)

        // A retry of the SAME turn reproduces the SAME key (the idempotency contract §29-§30).
        val retry = ReviewCommitId(AnkiBackendId.PC_AGENT, session, cardTurnOne)
        assertEquals(first.stableKey, retry.stableKey)

        // And the backend is part of the key, so a commit never migrates between backends.
        val wrongBackend = ReviewCommitId(AnkiBackendId.ANKIDROID_LOCAL, session, cardTurnOne)
        assertNotEquals(first.stableKey, wrongBackend.stableKey)
    }

    // ------------------------------------------------------------------ availability truthfulness

    @Test
    fun `only Ready with review capability accepts new review sessions`() {
        assertTrue(AnkiAvailability.Ready(AnkiCapabilities(review = true)).isReadyForReview)
        // Ready but review-incapable is NOT a review start condition (feature fallback §72).
        assertFalse(AnkiAvailability.Ready(AnkiCapabilities(review = false, deckListing = true)).isReadyForReview)
        assertFalse(AnkiAvailability.NotInstalled.isReadyForReview)
        assertFalse(AnkiAvailability.PermissionRequired().isReadyForReview)
        assertFalse(AnkiAvailability.CollectionNotInitialized.isReadyForReview)
        assertFalse(AnkiAvailability.TemporarilyUnavailable().isReadyForReview)
        assertFalse(AnkiAvailability.AgentDisconnected.isReadyForReview)
        assertFalse(AnkiAvailability.AgentAnkiUnavailable.isReadyForReview)
        assertFalse(AnkiAvailability.Unsupported("api too old").isReadyForReview)
        assertFalse(AnkiAvailability.Fault(AnkiError.Unknown()).isReadyForReview)
    }

    @Test
    fun `capabilities default to the safe all-false answer`() {
        val none = AnkiCapabilities.NONE
        assertFalse(none.review)
        assertFalse(none.deckListing)
        assertFalse(none.renderedCards)
        assertFalse(none.media)
        assertFalse(none.bury)
        assertFalse(none.suspendCards)
        assertFalse(none.editNotes)
        assertFalse(none.createNotes)
        assertFalse(none.search)
    }

    // ------------------------------------------------------------------ backend-mode resolution

    @Test
    fun `AUTO prefers AnkiDroid-local when both backends are review-ready`() {
        val resolution = AnkiBackendSelector.resolve(
            preference = AnkiBackendMode.AUTO,
            implemented = setOf(AnkiBackendId.ANKIDROID_LOCAL, AnkiBackendId.PC_AGENT),
            availabilityOf = { AnkiAvailability.Ready(AnkiCapabilities(review = true)) }
        )
        assertEquals(
            AnkiBackendSelector.Resolution.Resolved(AnkiBackendId.ANKIDROID_LOCAL),
            resolution
        )
    }

    @Test
    fun `AUTO falls back to the PC path when AnkiDroid is not ready`() {
        val resolution = AnkiBackendSelector.resolve(
            preference = AnkiBackendMode.AUTO,
            implemented = setOf(AnkiBackendId.ANKIDROID_LOCAL, AnkiBackendId.PC_AGENT),
            availabilityOf = { id ->
                if (id == AnkiBackendId.PC_AGENT) {
                    AnkiAvailability.Ready(AnkiCapabilities(review = true))
                } else {
                    AnkiAvailability.PermissionRequired()
                }
            }
        )
        assertEquals(AnkiBackendSelector.Resolution.Resolved(AnkiBackendId.PC_AGENT), resolution)
    }

    @Test
    fun `AUTO never resolves a backend that is not implemented in this build`() {
        val resolution = AnkiBackendSelector.resolve(
            preference = AnkiBackendMode.AUTO,
            // Only the PC backend exists yet — today's reality (backward compatibility §91).
            implemented = setOf(AnkiBackendId.PC_AGENT),
            availabilityOf = { AnkiAvailability.Ready(AnkiCapabilities(review = true)) }
        )
        assertEquals(AnkiBackendSelector.Resolution.Resolved(AnkiBackendId.PC_AGENT), resolution)

        val nothingReady = AnkiBackendSelector.resolve(
            preference = AnkiBackendMode.AUTO,
            implemented = setOf(AnkiBackendId.PC_AGENT),
            availabilityOf = { AnkiAvailability.AgentDisconnected }
        )
        assertEquals(
            AnkiBackendSelector.Resolution.Unavailable(
                AnkiBackendSelector.UnavailableReason.NO_READY_BACKEND
            ),
            nothingReady
        )
    }

    @Test
    fun `explicit preference fails closed instead of silently switching backends`() {
        // INV-ANKI-07 spirit, applied at selection time: an explicit AnkiDroid preference must
        // never resolve to the PC backend, even when the PC path is perfectly healthy.
        val resolution = AnkiBackendSelector.resolve(
            preference = AnkiBackendMode.ANKIDROID_LOCAL,
            implemented = setOf(AnkiBackendId.ANKIDROID_LOCAL, AnkiBackendId.PC_AGENT),
            availabilityOf = { id ->
                if (id == AnkiBackendId.PC_AGENT) AnkiAvailability.Ready(AnkiCapabilities(review = true))
                else AnkiAvailability.NotInstalled
            }
        )
        assertEquals(
            AnkiBackendSelector.Resolution.Unavailable(
                AnkiBackendSelector.UnavailableReason.EXPLICIT_BACKEND_NOT_READY
            ),
            resolution
        )

        val notImplemented = AnkiBackendSelector.resolve(
            preference = AnkiBackendMode.ANKIDROID_LOCAL,
            implemented = setOf(AnkiBackendId.PC_AGENT),
            availabilityOf = { AnkiAvailability.NotInstalled }
        )
        assertEquals(
            AnkiBackendSelector.Resolution.Unavailable(
                AnkiBackendSelector.UnavailableReason.EXPLICIT_BACKEND_NOT_IMPLEMENTED
            ),
            notImplemented
        )
    }

    // ------------------------------------------------------------------ commit failure classes

    @Test
    fun `commit failures classify conservatively`() {
        // Determined-before-mutation failures are the only safe retries.
        assertEquals(CommitFailureClass.FAILED_SAFE_TO_RETRY, AnkiError.BackendUnavailable().asCommitFailureClass())
        assertEquals(CommitFailureClass.FAILED_SAFE_TO_RETRY, AnkiError.CollectionUnavailable().asCommitFailureClass())

        // Deterministic refusals are REJECTED — surfaced, never retried.
        assertEquals(CommitFailureClass.REJECTED, AnkiError.PermissionRequired().asCommitFailureClass())
        assertEquals(CommitFailureClass.REJECTED, AnkiError.DeckNotFound().asCommitFailureClass())
        assertEquals(CommitFailureClass.REJECTED, AnkiError.CardNotFound().asCommitFailureClass())
        assertEquals(CommitFailureClass.REJECTED, AnkiError.CommitConflict().asCommitFailureClass())
        assertEquals(CommitFailureClass.REJECTED, AnkiError.UnsupportedAction("bury").asCommitFailureClass())

        // Unknown is always AMBIGUOUS: the scheduler MAY have applied the mutation,
        // so session progression must stop until reconciliation (INV-ANKI-08).
        assertEquals(CommitFailureClass.AMBIGUOUS, AnkiError.Unknown().asCommitFailureClass())
    }

    // ------------------------------------------------------------------ session context lock

    @Test
    fun `session context is an immutable one-backend lock`() {
        val context = AnkiSessionContext(
            backendId = AnkiBackendId.ANKIDROID_LOCAL,
            collection = null,
            deckRef = AnkiDeckRef(AnkiBackendId.ANKIDROID_LOCAL, "42"),
            startedAtEpochMs = 1_758_499_200_000L,
            capabilities = AnkiCapabilities(review = true),
            studySessionId = "session-3"
        )
        // The deck of a context always belongs to the context's backend (INV-ANKI-01).
        assertEquals(context.backendId, context.deckRef.backendId)
        // A context never transitions: "switching" means building a NEW context next session.
        val nextSession = context.copy(studySessionId = "session-4", backendId = AnkiBackendId.PC_AGENT)
        assertNotEquals(context, nextSession)
        assertEquals(AnkiBackendId.ANKIDROID_LOCAL, context.backendId)
    }
}
