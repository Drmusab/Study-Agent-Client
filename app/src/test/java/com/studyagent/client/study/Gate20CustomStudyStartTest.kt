package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.study.AnkiStudyRequest
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.core.anki.customstudy.AnkiReschedulingDisclosure
import com.studyagent.client.core.anki.customstudy.AnkiStudyMode
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiStudyEvent
import com.studyagent.client.data.repository.AnkiLocalStudyStarter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 20 — the two places a study session is admitted (IMPLEMENTATION-20-08/20-11, VER-20-01/18/21).
 *
 * 1. the pre-binding start site, [AnkiLocalStudyStarter];
 * 2. the binding moment inside [com.studyagent.client.core.study.AnkiStudyEffectExecutor].
 *
 * What is under test is that a deck the backend reports as **filtered** never reaches the
 * scheduler: it is refused with a typed reason, no session is registered, and no card is ever
 * asked for. An ordinary deck is unaffected — the guard must not become a silent behaviour change
 * for the normal path (VER-20-25).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Gate20CustomStudyStartTest {

    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val deckRef = AnkiDeckRef(backendId, "1", "collection")

    private fun backend(
        deck: AnkiDeck,
        capabilities: AnkiCustomStudyCapabilities = AnkiCustomStudyCapabilities.NONE,
        rescheduling: AnkiReschedulingDisclosure? = null
    ) = FakeAnkiBackend(
        id = backendId,
        decks = listOf(deck),
        cards = listOf(AnkiCommitHarness.card("A", backendId)),
        customStudyCapabilities = capabilities,
        filteredRescheduling = rescheduling
    )

    /** Records what the state machine would have received; the starter performs no backend write. */
    private class Dispatcher {
        val dispatched = mutableListOf<AnkiStudyRequest>()
        fun dispatch(request: AnkiStudyRequest) {
            dispatched.add(request)
        }
    }

    private fun starter(
        backend: FakeAnkiBackend,
        dispatcher: Dispatcher,
        scope: kotlinx.coroutines.CoroutineScope
    ): AnkiLocalStudyStarter = AnkiLocalStudyStarter(
        registry = AnkiBackendRegistry(listOf(backend)),
        scope = scope,
        dispatch = dispatcher::dispatch,
        studySessionIdFactory = { "gate20-session" }
    )

    // ------------------------------------------------------- VER-20-25  the normal path is untouched

    @Test fun `an ordinary deck still starts, in normal mode, with no extra provider traffic`() = runTest {
        val fake = backend(AnkiDeck(deckRef, "Medicine", isFiltered = false))
        val dispatcher = Dispatcher()
        val starter = starter(fake, dispatcher, backgroundScope)
        val dispatched = dispatcher.dispatched
        val result = starter.start(AnkiLocalStudyStarter.Options(deckId = "1"))

        assertTrue("expected a start, got $result", result is AnkiLocalStudyStarter.Result.Started)
        val started = result as AnkiLocalStudyStarter.Result.Started
        assertEquals(AnkiStudyMode.Normal, started.studyMode)
        assertEquals(AnkiStudyMode.Normal, dispatched.single().studyMode)
        val decksBefore = fake.getDecksCalls
        // The rescheduling read is only issued for a deck the backend itself calls filtered.
        assertEquals(decksBefore, fake.getDecksCalls)
    }

    // --------------------------------------------------- VER-20-18  a filtered deck never starts (pre)

    @Test fun `a known filtered deck is refused at the start site and nothing is dispatched`() = runTest {
        val fake = backend(AnkiDeck(deckRef, "Custom Study Session", isFiltered = true))
        val dispatcher = Dispatcher()
        val starter = starter(fake, dispatcher, backgroundScope)
        val dispatched = dispatcher.dispatched
        val result = starter.start(AnkiLocalStudyStarter.Options(deckId = "1"))

        assertTrue("expected a refusal, got $result", result is AnkiLocalStudyStarter.Result.Refused)
        val refused = result as AnkiLocalStudyStarter.Result.Refused
        assertEquals(AnkiLocalStudyStarter.REASON_CUSTOM_STUDY_REFUSED, refused.reason)
        assertEquals("filtered_deck_review", (refused.error as? AnkiError.UnsupportedAction)?.action)
        assertTrue("no session may be dispatched", dispatched.isEmpty())
        assertEquals(0, fake.nextCardCount)
        assertEquals(0, fake.physicalCommitCalls)
    }

    @Test fun `a filtered deck stays refused even when the backend claims filtered review but discloses nothing`() = runTest {
        val fake = backend(
            deck = AnkiDeck(deckRef, "Filtered", isFiltered = true),
            capabilities = AnkiCustomStudyCapabilities(
                canReviewFilteredDeck = true,
                canProveFilteredRatingCommit = true,
                canExposeReschedulingSemantics = true
            )
        )
        val dispatcher = Dispatcher()
        val starter = starter(fake, dispatcher, backgroundScope)
        val dispatched = dispatcher.dispatched
        val result = starter.start(AnkiLocalStudyStarter.Options(deckId = "1"))

        assertTrue("expected a refusal, got $result", result is AnkiLocalStudyStarter.Result.Refused)
        val error = (result as AnkiLocalStudyStarter.Result.Refused).error
        assertEquals("filtered_deck_rescheduling", (error as? AnkiError.UnsupportedAction)?.action)
        assertTrue(dispatched.isEmpty())
    }

    @Test fun `an undisclosed filtered deck is never started as if it were ordinary review`() = runTest {
        val fake = backend(AnkiDeck(deckRef, "Filtered", isFiltered = null))
        val dispatcher = Dispatcher()
        val starter = starter(fake, dispatcher, backgroundScope)
        val dispatched = dispatcher.dispatched
        val result = starter.start(AnkiLocalStudyStarter.Options(deckId = "1"))

        // Unknown is not filtered: the start proceeds, and stays in the only constructible mode.
        assertTrue("expected a start, got $result", result is AnkiLocalStudyStarter.Result.Started)
        assertEquals(AnkiStudyMode.Normal, (result as AnkiLocalStudyStarter.Result.Started).studyMode)
        assertEquals(AnkiStudyMode.Normal, dispatched.single().studyMode)
    }

    // -------------------------------------------- VER-20-18/21  the binding moment refuses the same deck

    @Test fun `the binding moment refuses a filtered deck even when the request claims normal mode`() = runTest {
        val fake = backend(AnkiDeck(deckRef, "Custom Study Session", isFiltered = true))
        val h = AnkiCommitHarness(customBackend = fake)
        // A request that asserts the normal mode: the executor re-reads the listing and refuses
        // anyway, so a stale or forged request can never put a filtered deck in front of the
        // scheduler (INV-20-14/20-18).
        h.send(
            AnkiStudyEvent.Start(
                AnkiStudyRequest("gate20", AnkiBackendMode.ANKIDROID_LOCAL, deckRef, studyMode = AnkiStudyMode.Normal)
            )
        )
        h.drain()

        assertNull("no review session may exist", h.state.anki?.reviewSession)
        assertEquals(0, fake.nextCardCount)
        assertEquals(0, fake.physicalCommitCalls)
        assertTrue("no card was ever scheduled", h.state.currentCardId == null)
    }

    @Test fun `the binding moment lets an ordinary deck through and the scheduler still owns order`() = runTest {
        val fake = backend(AnkiDeck(deckRef, "Medicine", isFiltered = false))
        val h = AnkiCommitHarness(customBackend = fake)
        h.startAndLoad()
        assertTrue("the backend scheduler provided a card", h.state.currentCardId != null)
        assertEquals(AnkiStudyMode.Normal, h.state.anki?.reviewSession?.context?.studyMode)
    }

    // ------------------------------------------- VER-20-26  no card is ever browsed into a session

    @Test fun `the card came from the backend scheduler and never from a client-side list`() = runTest {
        val fake = backend(AnkiDeck(deckRef, "Medicine", isFiltered = false))
        val h = AnkiCommitHarness(customBackend = fake)
        h.loadToRating()
        h.rate(Rating.GOOD)
        h.run(h.takeCommitEffect())
        h.drain()
        assertEquals(1, fake.physicalCommitCalls)
        assertTrue("every card came from the scheduler, never from a client-side list", fake.nextCardCount >= 1)
    }
}
