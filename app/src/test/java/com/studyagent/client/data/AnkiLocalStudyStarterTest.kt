package com.studyagent.client.data

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendSelector
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionCapabilities
import com.studyagent.client.core.anki.ReviewerActionKind
import com.studyagent.client.core.study.AnkiStudyRequest
import com.studyagent.client.data.repository.AnkiLocalStudyStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 13 STEP 32.2 — the app's session-start site.
 *
 * The interesting property is not "a request is built" but *what is frozen into it*: the session's
 * reviewer-action capabilities must be a snapshot of the backend's audited contract taken at start,
 * so no later preference change, capability shrink or provider outage can re-shape a mutation that is
 * already in progress (INV-13-16, §28/§29).
 */
class AnkiLocalStudyStarterTest {

    private val deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1", "collection")
    private val deck = AnkiDeck(deckRef, "Deck")

    private val actionCapabilities = AnkiCapabilities(
        deckListing = true,
        review = true,
        scheduledReview = true,
        bury = true,
        suspendCards = true,
        flags = false
    )

    private fun deviceBackend(
        capabilities: AnkiCapabilities = actionCapabilities,
        availability: AnkiAvailability = AnkiAvailability.Ready(capabilities),
        /** The pinned contract cannot write a flag; a test that claims one must model that path. */
        flagWrites: Boolean = false
    ) = FakeAnkiBackend(
        id = AnkiBackendId.AnkiDroidLocal,
        decks = listOf(deck),
        initialCapabilities = capabilities,
        initialAvailability = availability,
        flagWrites = flagWrites
    )

    /** Records what the state machine would have received; the starter performs no backend write. */
    private class Dispatcher {
        val dispatched = mutableListOf<AnkiStudyRequest>()
        fun dispatch(request: AnkiStudyRequest) {
            dispatched.add(request)
        }
    }

    private fun starter(
        vararg backends: FakeAnkiBackend,
        dispatcher: Dispatcher,
        scope: CoroutineScope
    ): AnkiLocalStudyStarter {
        val registry = AnkiBackendRegistry(backends.toList())
        return AnkiLocalStudyStarter(
            registry = registry,
            selector = AnkiBackendSelector(registry),
            scope = scope,
            dispatch = dispatcher::dispatch,
            studySessionIdFactory = { "session-fixed" }
        )
    }

    /** The contract the backend itself declares, resolved through the one capability mapping. */
    private fun declaredBy(fake: FakeAnkiBackend): ReviewerActionCapabilities =
        ReviewerActionCapabilities(
            flag = fake.capabilities.value.flags,
            bury = fake.capabilities.value.bury,
            suspend = fake.capabilities.value.suspendCards,
            semantics = ReviewerActionKind.entries.associateWith { kind ->
                fake.reviewerActionSemantics(ReviewerAction.representative(kind))
            }
        )

    @Test
    fun `a start freezes the backend's audited reviewer-action capabilities into the request`() = runTest {
        val fake = deviceBackend()
        val dispatcher = Dispatcher()
        val starter = starter(fake, dispatcher = dispatcher, scope = backgroundScope)

        val result = starter.start(AnkiLocalStudyStarter.Options(deckName = "Deck"))

        assertTrue("expected a start, got $result", result is AnkiLocalStudyStarter.Result.Started)
        val started = result as AnkiLocalStudyStarter.Result.Started
        val request = dispatcher.dispatched.single()

        assertEquals("session-fixed", request.studySessionId)
        assertEquals(deckRef, request.deck)
        assertEquals("the request carries the backend's own audited contract", declaredBy(fake), request.reviewerActions)
        assertTrue(request.reviewerActions.supports(ReviewerAction.BuryCard))
        assertTrue(request.reviewerActions.supports(ReviewerAction.SuspendCard))
        assertFalse(
            "a backend without a flag write path must not advertise one",
            request.reviewerActions.supports(ReviewerAction.SetFlag(AnkiFlag.RED))
        )
        assertTrue(
            "bury's audited semantics travel with the freeze",
            request.reviewerActions.semanticsOf(ReviewerActionKind.BURY).supportsIdempotentReplay
        )
        assertEquals(request.reviewerActions, started.reviewerActions)
        assertEquals(AnkiBackendId.AnkiDroidLocal, started.backendId)
        assertEquals(deckRef, started.deck)
    }

    @Test
    fun `the freeze is a snapshot taken at start, not a live view of the backend`() = runTest {
        val fake = deviceBackend(flagWrites = true)
        val dispatcher = Dispatcher()
        val starter = starter(fake, dispatcher = dispatcher, scope = backgroundScope)

        starter.start(AnkiLocalStudyStarter.Options(deckName = "Deck"))
        val first = dispatcher.dispatched[0].reviewerActions

        // The provider later loses the suspend write path and gains flags.
        fake.setCapabilities(actionCapabilities.copy(suspendCards = false, flags = true))

        starter.start(AnkiLocalStudyStarter.Options(deckName = "Deck"))
        val second = dispatcher.dispatched[1].reviewerActions

        assertTrue("the first session keeps the set it started with", first.supports(ReviewerAction.SuspendCard))
        assertFalse("the second session freezes the new contract", second.supports(ReviewerAction.SuspendCard))
        assertTrue(second.supports(ReviewerAction.SetFlag(AnkiFlag.RED)))
    }

    @Test
    fun `no ready backend refuses and dispatches nothing`() = runTest {
        val fake = deviceBackend(availability = AnkiAvailability.PermissionRequired())
        val dispatcher = Dispatcher()
        val starter = starter(fake, dispatcher = dispatcher, scope = backgroundScope)

        val result = starter.start(AnkiLocalStudyStarter.Options(deckName = "Deck"))

        assertEquals(
            AnkiLocalStudyStarter.REASON_NO_READY_BACKEND,
            (result as AnkiLocalStudyStarter.Result.Refused).reason
        )
        assertTrue(
            "the backend's own typed reason travels with the refusal",
            result.error is com.studyagent.client.core.anki.AnkiError.PermissionRequired
        )
        assertTrue("a refusal must not dispatch", dispatcher.dispatched.isEmpty())
    }

    @Test
    fun `a deck the collection does not have refuses and dispatches nothing`() = runTest {
        val fake = deviceBackend()
        val dispatcher = Dispatcher()
        val starter = starter(fake, dispatcher = dispatcher, scope = backgroundScope)

        val result = starter.start(AnkiLocalStudyStarter.Options(deckName = "Cardiology"))

        assertEquals(
            AnkiLocalStudyStarter.REASON_DECK_NOT_FOUND,
            (result as AnkiLocalStudyStarter.Result.Refused).reason
        )
        assertTrue(dispatcher.dispatched.isEmpty())
    }

    @Test
    fun `an agent-only registry never starts a local session`() = runTest {
        // "Study on this phone" must refuse rather than silently reviewing through a PC agent: the two
        // backends have different ledgers, different identities and different recovery semantics.
        val pcId = AnkiBackendId.PcAgent("pc")
        val pc = FakeAnkiBackend(
            id = pcId,
            decks = listOf(AnkiDeck(AnkiDeckRef(pcId, "9", "collection"), "Deck"))
        )
        val dispatcher = Dispatcher()
        val starter = starter(pc, dispatcher = dispatcher, scope = backgroundScope)

        val result = starter.start(AnkiLocalStudyStarter.Options(deckName = "Deck"))

        assertTrue("expected a refusal, got $result", result is AnkiLocalStudyStarter.Result.Refused)
        assertTrue(dispatcher.dispatched.isEmpty())
    }

    @Test
    fun `local readiness reflects the on-device backend only`() = runBlocking {
        val fake = deviceBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val starter = starter(fake, dispatcher = Dispatcher(), scope = scope)
            assertTrue("a ready device backend means the local action is available", awaitReady(starter, true))

            fake.setAvailability(AnkiAvailability.NotInstalled)
            assertTrue("an unavailable backend clears it", awaitReady(starter, false))
        } finally {
            scope.cancel()
        }
    }

    /** The readiness flow is a `StateFlow` fed by a background collector; wait for, never race it. */
    private fun awaitReady(
        starter: AnkiLocalStudyStarter,
        expected: Boolean,
        timeoutMs: Long = 2_000L
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (starter.localStudyReady.value == expected) return true
            Thread.sleep(5L)
        }
        return starter.localStudyReady.value == expected
    }
}
