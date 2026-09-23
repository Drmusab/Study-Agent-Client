package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.AnkiReviewTurnContent
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiCapabilityReport
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidIntegrationState
import com.studyagent.client.data.anki.ankidroid.AnkiDroidMetadata
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderSpecSource
import com.studyagent.client.data.anki.ankidroid.AnkiDroidScheduledCardQuery
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidCardGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidDeckGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidReviewGateway
import com.studyagent.client.data.anki.ankidroid.SequentialReviewTurnIdSource
import com.studyagent.client.testutil.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 06 §101/§103/§167 — the *scheduled review* contract, asserted against every backend that
 * implements it.
 *
 * Two very different implementations run the identical assertions: the test scheduler stand-in and
 * the real AnkiDroid backend (on its fakes). That is the point — the semantics a future
 * `StudySessionMachine` will depend on ("retrying a read does not advance", "nothing due is not an
 * error", "one session has at most one unresolved turn") must belong to the *contract*, not to one
 * implementation, or GATE 10 will be testing the fake rather than the app.
 */
abstract class AnkiScheduledReviewContract {

    /** A ready backend with exactly one deck and a scripted answer sequence. */
    protected data class Fixture(
        val backend: AnkiBackend,
        val context: AnkiSessionContext,
        val deck: AnkiDeck,
        /** Cards the scheduler will report, in order; empty means "nothing due". */
        val scheduled: List<Pair<String, Int>>
    )

    protected abstract fun fixture(scheduled: List<Pair<String, Int>>): Fixture

    private suspend fun Fixture.begin(): AnkiResult<AnkiReviewSession> =
        backend.beginReview(BeginReviewRequest(context))

    private suspend fun Fixture.session(): AnkiReviewSession =
        (begin() as AnkiResult.Success).value

    // ---------------------------------------------------------------- the invariants

    @Test
    fun `a valid deck opens exactly one session bound to its backend`() = runTest {
        val f = fixture(listOf("100" to 0))
        val session = f.session()

        assertEquals(f.backend.id, session.context.backendId)
        assertEquals(f.deck.ref, session.context.deckRef)
        assertTrue(session.backendSessionRef.isNotBlank())
    }

    @Test
    fun `an exhausted scheduler is Finished, not a failure and not an unhealthy backend`() = runTest {
        val f = fixture(emptyList())
        val session = f.session()

        assertEquals(NextCardResult.Finished, f.backend.nextCard(session))
        assertEquals(NextCardResult.Finished, f.backend.nextCard(session))
        // §74/§127: "no cards due" and "backend down" are different facts about the world.
        assertTrue(f.backend.availability.value is AnkiAvailability.Ready)
    }

    @Test
    fun `one card yields at most one unresolved turn`() = runTest {
        val f = fixture(listOf("100" to 0, "200" to 1))
        val session = f.session()

        val turn = (f.backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals("100", turn.cardRef.noteId)
        assertEquals(
            "a second read must not advance while a turn is unresolved",
            NextCardResult.Card(turn),
            f.backend.nextCard(session)
        )
    }

    @Test
    fun `concurrent reads still yield exactly one turn`() = runTest {
        val f = fixture(listOf("100" to 0, "200" to 1))
        val session = f.session()

        val results = List(8) { async { f.backend.nextCard(session) } }.awaitAll()
        val turns = results.map { (it as NextCardResult.Card).turn }.distinct()

        assertEquals(1, turns.size)
        assertEquals("100", turns.single().cardRef.noteId)
    }

    @Test
    fun `every turn carries backend qualified identity and the session's own turn id`() = runTest {
        val f = fixture(listOf("100" to 0, "200" to 1))
        val session = f.session()

        val first = (f.backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals(f.backend.id, first.cardRef.backendId)
        assertEquals(f.backend.id, first.backendId)
        assertEquals(session.context.studySessionId, first.studySessionId)
        assertFalse(first.turnId.value.isBlank())
        // A turn's commit correlation is the session + turn identity, never the card alone (§57).
        assertEquals(session.context.studySessionId, first.commitId.studySessionId)
        assertEquals(first.turnId, first.commitId.turnId)
        assertEquals(f.backend.id, first.commitId.backendId)
        // The card's deck is the session's deck, whichever representation the backend used (§10).
        val turnDeck = when (val content = first.content) {
            is AnkiReviewTurnContent.Scheduled -> content.scheduledCard.deckRef
            is AnkiReviewTurnContent.Rendered -> content.scheduledCard.deckRef
        }
        assertEquals(f.deck.ref, turnDeck)
    }

    @Test
    fun `a forged or foreign session handle is refused`() = runTest {
        val f = fixture(listOf("100" to 0))
        val session = f.session()

        assertTrue(f.backend.nextCard(session.copy(backendSessionRef = "forged")) is NextCardResult.Failure)
        val foreign = session.copy(
            context = session.context.copy(backendId = AnkiBackendId.PcAgent("someone-else"))
        )
        assertTrue(f.backend.nextCard(foreign) is NextCardResult.Failure)
    }

    @Test
    fun `a begin for a foreign backend is refused`() = runTest {
        val f = fixture(listOf("100" to 0))
        val result = f.backend.beginReview(
            BeginReviewRequest(f.context.copy(backendId = AnkiBackendId.PcAgent("someone-else")))
        )
        assertTrue(result is AnkiResult.Failure)
    }

    @Test
    fun `repeating the identical begin is idempotent`() = runTest {
        val f = fixture(listOf("100" to 0))
        assertEquals(f.begin(), f.begin())
        assertEquals((f.begin() as AnkiResult.Success).value, f.session())
    }

    // ---------------------------------------------------------------- the stand-in itself

    @Test
    fun `the test backend mirrors the one active turn semantics`() = runTest {
        val f = fakeFixture(listOf("100" to 0, "200" to 1))
        val session = f.session()
        val first = (f.backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals(first, (f.backend.nextCard(session) as NextCardResult.Card).turn)
    }

    @Test
    fun `the same card appearing again becomes a new turn, never the same turn`() = runTest {
        // The test backend can commit (GATE 03), which the real one cannot yet — this is how the
        // "same card, new turn" rule is exercised before GATE 11 exists (§16/INV-ANKI-REV-04/05).
        val f = fakeFixture(listOf("100" to 0, "200" to 1, "100" to 0))
        val session = f.session()

        val turns = mutableListOf<com.studyagent.client.core.anki.AnkiReviewTurn>()
        repeat(3) {
            val turn = (f.backend.nextCard(session) as NextCardResult.Card).turn
            turns += turn
            f.backend.commitRating(turn.request(Rating.GOOD))
        }

        assertEquals("100", turns[0].cardRef.noteId)
        assertEquals("100", turns[2].cardRef.noteId)
        assertEquals(turns[0].cardRef, turns[2].cardRef)
        assertNotEquals("a repeated card is a new presentation", turns[0].turnId, turns[2].turnId)
        assertEquals(3, turns.map { it.turnId }.distinct().size)
        assertEquals(NextCardResult.Finished, f.backend.nextCard(session))
    }

    @Test
    fun `the real backend refuses rating commits it cannot perform`() = runTest {
        val f = fixture(listOf("100" to 0))
        val session = f.session()
        val turn = (f.backend.nextCard(session) as NextCardResult.Card).turn

        assertTrue(f.backend.commitRating(turn.request(Rating.GOOD)) is com.studyagent.client.core.anki.CommitRatingResult.Rejected)
        // …and the refusal does not silently advance the session either.
        assertEquals(NextCardResult.Card(turn), f.backend.nextCard(session))
    }

    // ---------------------------------------------------------------- fixtures

    private fun fakeFixture(scheduled: List<Pair<String, Int>>): Fixture {
        val deckRef = AnkiDeckRef(AnkiBackendId.Fake("contract"), "deck-1")
        val deck = AnkiDeck(deckRef, "Contract")
        val cards = scheduled.map { (noteId, ord) -> testCard(deckRef, noteId, ord) }
        val backend = FakeAnkiBackend(AnkiBackendId.Fake("contract"), listOf(deck), cards)
        return Fixture(backend, contextOf(backend.id, deckRef), deck, scheduled)
    }

    private fun realFixture(scheduled: List<Pair<String, Int>>): Fixture {
        val deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1700000000000")
        val deck = AnkiDeck(deckRef, "Contract")
        val capabilities = AnkiCapabilities(deckListing = true, scheduledReview = true)
        val state = AnkiDroidIntegrationState(
            availability = AnkiAvailability.Ready(capabilities),
            capabilities = capabilities,
            apiCapabilities = AnkiDroidApiCapabilityReport(
                deckListing = CapabilitySupport.SUPPORTED,
                deckCounts = CapabilitySupport.SUPPORTED,
                scheduledReview = CapabilitySupport.SUPPORTED,
                renderedCards = CapabilitySupport.SUPPORTED,
                simpleCardText = CapabilitySupport.SUPPORTED,
                nextReviewIntervals = CapabilitySupport.SUPPORTED,
                ratingCommit = CapabilitySupport.SUPPORTED,
                flags = CapabilitySupport.UNSUPPORTED,
                bury = CapabilitySupport.SUPPORTED,
                suspend = CapabilitySupport.SUPPORTED,
                noteRead = CapabilitySupport.SUPPORTED,
                noteEdit = CapabilitySupport.SUPPORTED,
                noteCreate = CapabilitySupport.SUPPORTED,
                mediaRead = CapabilitySupport.SUPPORTED,
                mediaWrite = CapabilitySupport.SUPPORTED,
                search = CapabilitySupport.SUPPORTED,
                noteTypes = CapabilitySupport.SUPPORTED,
                cardTemplates = CapabilitySupport.SUPPORTED,
                specVersion = 2,
                reason = "provider contract"
            ),
            metadata = AnkiDroidMetadata(
                packageName = "com.ichi2.anki",
                providerPackage = "com.ichi2.anki",
                authority = "com.ichi2.anki.flashcards",
                endpointLabel = "release",
                providerSpec = 2,
                providerSpecSource = AnkiDroidProviderSpecSource.METADATA,
                packageVersion = "2.24.1",
                providerReachable = true,
                permissionGranted = true,
                collectionReady = true
            ),
            lastCheckAtMs = 1_700_000_000_000L,
            latencyMs = 1L,
            lastError = null,
            healthSnapshot = AnkiDroidHealthSnapshot.checking(1_700_000_000_000L),
            capabilityDetails = emptyList()
        )
        val deckGateway = FakeAnkiDroidDeckGateway().also { it.succeed(listOf(deck)) }
        val results = scheduled.map { (noteId, ord) ->
            AnkiResult.Success<AnkiDroidScheduledCardQuery>(
                AnkiDroidScheduledCardQuery.Scheduled(testScheduledCard(deckRef, noteId, ord), 1L, 1L)
            )
        }.toMutableList()
        val reviewGateway = FakeAnkiDroidReviewGateway(results = results.ifEmpty {
            mutableListOf(AnkiResult.Success(AnkiDroidScheduledCardQuery.NoCardDue(0L)))
        })
        val backend = AnkiDroidBackend(
            gateway = FakeAnkiDroidGateway(stateToReturn = state),
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = TestClock(),
            deckGateway = deckGateway,
            reviewGateway = reviewGateway,
            cardGateway = FakeAnkiDroidCardGateway(),
            turnIds = SequentialReviewTurnIdSource(instancePrefix = "contract")
        )
        return Fixture(backend, contextOf(backend.id, deckRef), deck, scheduled)
    }

    private fun contextOf(backendId: AnkiBackendId, deckRef: AnkiDeckRef) = AnkiSessionContext(
        backendId = backendId,
        collection = null,
        deckRef = deckRef,
        startedAtEpochMs = 1_700_000_000_000L,
        capabilities = AnkiCapabilities(review = true, scheduledReview = true, deckListing = true),
        studySessionId = "contract-session"
    )

    /** The AnkiDroid implementation must satisfy the contract too (GATE 06 §167). */
    class RealBackendContract : AnkiScheduledReviewContract() {
        override fun fixture(scheduled: List<Pair<String, Int>>) = realFixture(scheduled)
    }

    /** …and so must the stand-in GATE 10 will be tested against. */
    class FakeBackendContract : AnkiScheduledReviewContract() {
        override fun fixture(scheduled: List<Pair<String, Int>>) = fakeFixture(scheduled)
    }
}

private fun testCard(deckRef: AnkiDeckRef, noteId: String, ord: Int) = com.studyagent.client.core.anki.AnkiRenderedCard(
    ref = com.studyagent.client.core.anki.AnkiCardRef(
        backendId = deckRef.backendId, cardId = null, noteId = noteId, cardOrd = ord
    ),
    questionHtml = null,
    answerHtml = null,
    questionText = "q-$noteId",
    answerText = "a-$noteId",
    pureAnswerText = null,
    noteRef = com.studyagent.client.core.anki.AnkiNoteRef(deckRef.backendId, noteId),
    deckRef = deckRef
)

private fun testScheduledCard(deckRef: AnkiDeckRef, noteId: String, ord: Int) =
    com.studyagent.client.core.anki.AnkiScheduledCard(
        ref = com.studyagent.client.core.anki.AnkiCardRef(
            backendId = deckRef.backendId, cardId = null, noteId = noteId, cardOrd = ord
        ),
        noteRef = com.studyagent.client.core.anki.AnkiNoteRef(deckRef.backendId, noteId),
        deckRef = deckRef,
        ratingOptions = com.studyagent.client.core.anki.AnkiRatingOptions.Known(
            listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY)
        )
    )
