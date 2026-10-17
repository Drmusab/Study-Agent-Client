package com.studyagent.client.anki.customstudy

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.DecodedSessionLock
import com.studyagent.client.core.anki.StudySessionBackendLock
import com.studyagent.client.core.anki.StudySessionLockCodec
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.core.anki.customstudy.AnkiReschedulingDisclosure
import com.studyagent.client.core.anki.customstudy.AnkiStudyMode
import com.studyagent.client.core.anki.customstudy.CustomStudyFilterDimension
import com.studyagent.client.core.anki.customstudy.CustomStudyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * GATE 20 — the contract layer: capability honesty, the one scheduler-authority decision, session
 * identity, and the two rescheduling disclosures (DECISION-20-01/02/06/08/09/21, INV-20-03/13/16).
 *
 * Every test here is pure: no backend, no scheduler, no card. That is deliberate — the point of the
 * gate is that the *decision* about custom study is made from facts, not from a queue.
 */
class Gate20CustomStudyContractTest {

    private val backend = AnkiBackendId.AnkiDroidLocal
    private val other = AnkiBackendId.PcAgent("other")

    private fun deck(isFiltered: Boolean?, id: String = "1", owner: AnkiBackendId = backend) =
        AnkiDeck(AnkiDeckRef(owner, id, "collection"), "Deck $id", isFiltered = isFiltered)

    // ------------------------------------------------------------ capability honesty (AUDIT-20-13)

    @Test fun `the default capability claim is nothing at all`() {
        val none = AnkiCustomStudyCapabilities.NONE
        assertFalse(none.supportsAnyCustomStudy)
        assertFalse(none.canReviewFilteredDeck)
        assertFalse(none.canCreateFilteredSession)
        assertTrue(none.supportedFilterVocabulary().isEmpty())
    }

    @Test fun `filtered review cannot be claimed without a provable commit`() {
        try {
            AnkiCustomStudyCapabilities(
                canReviewFilteredDeck = true,
                canProveFilteredRatingCommit = false,
                canExposeReschedulingSemantics = true
            )
            fail("a filtered-review claim without commit provability must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `filtered review cannot be claimed without disclosed rescheduling`() {
        try {
            AnkiCustomStudyCapabilities(
                canReviewFilteredDeck = true,
                canProveFilteredRatingCommit = true,
                canExposeReschedulingSemantics = false
            )
            fail("a filtered-review claim without a rescheduling disclosure must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `preview-only cannot exist without the no-reschedule mode`() {
        try {
            AnkiCustomStudyCapabilities(canPreviewOnly = true, canSelectNoReschedule = false)
            fail("preview-only without no-reschedule must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `a complete claim is representable and reports its vocabulary`() {
        val full = AnkiCustomStudyCapabilities(
            canReviewFilteredDeck = true,
            canProveFilteredRatingCommit = true,
            canExposeReschedulingSemantics = true,
            canFilterByDeck = true,
            canExcludeChildDecks = true,
            canFilterByTag = true,
            canFilterByState = true,
            canLimitCardCount = true,
            canSelectOrdering = true,
            canSelectNoReschedule = true,
            canPreviewOnly = true
        )
        assertTrue(full.supportedFilterVocabulary().containsAll(
            listOf(
                CustomStudyFilterDimension.DECK,
                CustomStudyFilterDimension.EXCLUDE_CHILD_DECKS,
                CustomStudyFilterDimension.TAG,
                CustomStudyFilterDimension.CARD_STATE,
                CustomStudyFilterDimension.CARD_LIMIT,
                CustomStudyFilterDimension.ORDERING,
                CustomStudyFilterDimension.RESCHEDULING
            )
        ))
    }

    // --------------------------------------------------------------- the decision (DECISION-20-02)

    @Test fun `an ordinary deck proceeds in normal mode with one scheduler authority`() {
        val decision = CustomStudyPolicy.evaluate(
            deck = deck(isFiltered = false),
            backendId = backend,
            capabilities = AnkiCustomStudyCapabilities.NONE
        )
        assertEquals(CustomStudyPolicy.Decision.Proceed(AnkiStudyMode.Normal), decision)
    }

    @Test fun `a known filtered deck is refused when the backend cannot review one`() {
        val decision = CustomStudyPolicy.evaluate(
            deck = deck(isFiltered = true),
            backendId = backend,
            capabilities = AnkiCustomStudyCapabilities.NONE
        )
        val refusal = (decision as CustomStudyPolicy.Decision.Refused).refusal
        assertEquals(CustomStudyPolicy.Refusal.FILTERED_DECK_REVIEW_UNSUPPORTED, refusal)
        assertTrue(CustomStudyPolicy.errorFor(refusal) is AnkiError.UnsupportedAction)
        assertEquals("filtered_deck_review", (CustomStudyPolicy.errorFor(refusal) as AnkiError.UnsupportedAction).action)
    }

    @Test fun `a filtered deck on a capable backend is still refused when rescheduling is undisclosed`() {
        val capable = AnkiCustomStudyCapabilities(
            canReviewFilteredDeck = true,
            canProveFilteredRatingCommit = true,
            canExposeReschedulingSemantics = true
        )
        val refusal = (CustomStudyPolicy.evaluate(deck(true), backend, capable) as
            CustomStudyPolicy.Decision.Refused).refusal
        assertEquals(CustomStudyPolicy.Refusal.FILTERED_RESCHEDULING_UNDISCLOSED, refusal)
    }

    @Test fun `a filtered deck with a disclosed mode proceeds in filtered mode bound to that deck`() {
        val capable = AnkiCustomStudyCapabilities(
            canReviewFilteredDeck = true,
            canProveFilteredRatingCommit = true,
            canExposeReschedulingSemantics = true
        )
        val decision = CustomStudyPolicy.evaluate(
            deck = deck(isFiltered = true, id = "42"),
            backendId = backend,
            capabilities = capable,
            rescheduling = AnkiReschedulingDisclosure.NO_RESCHEDULING
        )
        val mode = (decision as CustomStudyPolicy.Decision.Proceed).mode as AnkiStudyMode.Filtered
        assertEquals("42", mode.filteredDeckRef.deckId)
        assertEquals(backend, mode.filteredDeckRef.backendId)
        assertEquals(AnkiReschedulingDisclosure.NO_RESCHEDULING, mode.rescheduling)
        assertTrue(mode.isFiltered)
    }

    @Test fun `a deck from another backend is refused instead of reinterpreted`() {
        val refusal = (CustomStudyPolicy.evaluate(deck(false, owner = other), backend,
            AnkiCustomStudyCapabilities.NONE) as CustomStudyPolicy.Decision.Refused).refusal
        assertEquals(CustomStudyPolicy.Refusal.FOREIGN_DECK, refusal)
    }

    @Test fun `an unknown filtered flag never upgrades into a filtered mode`() {
        val capable = AnkiCustomStudyCapabilities(
            canReviewFilteredDeck = true,
            canProveFilteredRatingCommit = true,
            canExposeReschedulingSemantics = true
        )
        // Unknown is not "filtered": no session silently enters a mode the backend never named.
        assertEquals(
            CustomStudyPolicy.Decision.Proceed(AnkiStudyMode.Normal),
            CustomStudyPolicy.evaluate(deck(isFiltered = null), backend, capable)
        )
    }

    @Test fun `rescheduling disclosures never read like normal review`() {
        assertTrue(AnkiReschedulingDisclosure.NORMAL_RESCHEDULING.schedulingSummary().contains("change"))
        assertTrue(AnkiReschedulingDisclosure.NO_RESCHEDULING.schedulingSummary().contains("NOT"))
    }

    // ------------------------------------------------------------- session identity (DECISION-20-09)

    @Test fun `a filtered session context is bound to the filtered deck it names`() {
        val ref = AnkiDeckRef(backend, "7", "collection")
        val context = AnkiSessionContext(
            backendId = backend,
            collection = null,
            deckRef = ref,
            startedAtEpochMs = 10L,
            capabilities = AnkiCapabilities.NONE,
            studySessionId = "s1",
            studyMode = AnkiStudyMode.Filtered(ref, AnkiReschedulingDisclosure.NORMAL_RESCHEDULING)
        )
        assertTrue(context.studyMode.isFiltered)
        try {
            AnkiSessionContext(
                backendId = backend, collection = null, deckRef = AnkiDeckRef(backend, "8", "collection"),
                startedAtEpochMs = 10L, capabilities = AnkiCapabilities.NONE, studySessionId = "s2",
                studyMode = AnkiStudyMode.Filtered(ref, AnkiReschedulingDisclosure.NORMAL_RESCHEDULING)
            )
            fail("a filtered mode bound to a different deck is contradictory data")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `the study mode is never persisted and a restored session is therefore always normal`() {
        val lock = StudySessionBackendLock.from(
            AnkiSessionContext(
                backendId = backend, collection = null, deckRef = AnkiDeckRef(backend, "1", "collection"),
                startedAtEpochMs = 10L, capabilities = AnkiCapabilities.NONE, studySessionId = "s1",
                studyMode = AnkiStudyMode.Normal
            )
        )
        val bytes = StudySessionLockCodec.encode(lock)
        assertFalse("the mode must not appear in durable bytes", bytes.contains("studyMode"))
        val restored = (StudySessionLockCodec.decode(bytes) as? DecodedSessionLock.Valid)?.lock
            ?: error("the lock must round-trip")
        assertEquals(AnkiStudyMode.Normal, restored.studyMode)
    }

    // ------------------------------------------------------------------------- vocabulary honesty

    @Test fun `no filter dimension is advertised without its capability`() {
        val deckOnly = AnkiCustomStudyCapabilities(canFilterByDeck = true)
        assertEquals(setOf(CustomStudyFilterDimension.DECK), deckOnly.supportedFilterVocabulary())
        assertNull(deckOnly.supportedFilterVocabulary().firstOrNull { it == CustomStudyFilterDimension.TAG })
    }
}
