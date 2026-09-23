package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewTurnContent
import com.studyagent.client.core.anki.AnkiScheduledCard
import com.studyagent.client.core.anki.AnkiSchedulingInfo
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.CommitRatingResult
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiCapabilityReport
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend as Backend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidIntegrationState
import com.studyagent.client.data.anki.ankidroid.AnkiDroidMetadata
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderSpecSource
import com.studyagent.client.data.anki.ankidroid.AnkiDroidScheduledCardQuery
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDeckListing
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidCardGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidDeckGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidReviewGateway
import com.studyagent.client.data.anki.ankidroid.SequentialReviewTurnIdSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 06 §151/§164-§166 — the scheduled-review session, its invariants and its refusals.
 *
 * Everything here runs against fakes, which is exactly the point: the *contract* Study-Agent must
 * uphold (one active turn, typed failures, no mutation, no local scheduling) is Study-Agent's, and
 * a JVM test can and must prove it. Whether AnkiDroid's real scheduler behaves as read is a
 * different question, answered only by the instrumented suite (§152, `REAL_DEVICE_TEST_MATRIX`).
 */
class AnkiDroidReviewSessionTest {

    private val deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1700000000000")
    private val deck = AnkiDeck(deckRef, "Medicine")

    private fun reviewState(
        capabilities: AnkiCapabilities = AnkiCapabilities(deckListing = true, scheduledReview = true),
        availability: AnkiAvailability = AnkiAvailability.Ready(capabilities)
    ): AnkiDroidIntegrationState = AnkiDroidIntegrationState(
        availability = availability,
        capabilities = capabilities,
        apiCapabilities = apiReport(),
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
        latencyMs = 5L,
        lastError = null,
        healthSnapshot = AnkiDroidHealthSnapshot.checking(1_700_000_000_000L),
        capabilityDetails = emptyList()
    )

    private fun apiReport() = AnkiDroidApiCapabilityReport(
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
    )

    private fun scheduledCard(
        noteId: String = "1700000000123",
        ord: Int = 0,
        ratings: AnkiRatingOptions = AnkiRatingOptions.Known(
            listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY)
        ),
        media: List<AnkiMediaRef> = emptyList()
    ) = AnkiScheduledCard(
        ref = AnkiCardRef(AnkiBackendId.AnkiDroidLocal, cardId = null, noteId = noteId, cardOrd = ord),
        noteRef = AnkiNoteRef(AnkiBackendId.AnkiDroidLocal, noteId),
        deckRef = deckRef,
        ratingOptions = ratings,
        scheduling = AnkiSchedulingInfo(
            nextReviewTimes = mapOf(Rating.AGAIN to "1m", Rating.HARD to "6m", Rating.GOOD to "1d", Rating.EASY to "4d")
        ),
        media = media
    )

    /**
     * One backend under test plus the fakes behind it.
     *
     * An `inner` class on purpose: `begin`/`beginOn`/`open` are the *test's* vocabulary for opening
     * a session, and they read the fixture deck and session-context rules from the suite instead of
     * duplicating them.
     */
    private inner class Harness(
        val backend: Backend,
        val review: FakeAnkiDroidReviewGateway,
        val decks: FakeAnkiDroidDeckGateway,
        val gateway: FakeAnkiDroidGateway
    ) {
        /** Opens a session on the fixture deck. */
        suspend fun begin(sessionId: String = "session-1", limit: Int? = null) =
            beginOn(deckRef, sessionId, limit)

        /** Opens a session on an arbitrary deck reference, including a deliberately bad one. */
        suspend fun beginOn(deck: AnkiDeckRef?, sessionId: String = "session-1", limit: Int? = null) =
            backend.beginReview(BeginReviewRequest(context(deck, sessionId), limit))

        /** Opens a session that is expected to succeed. */
        suspend fun open(sessionId: String = "session-1", limit: Int? = null) =
            (begin(sessionId, limit) as AnkiResult.Success).value
    }

    private fun harness(
        capabilities: AnkiCapabilities = AnkiCapabilities(deckListing = true, scheduledReview = true),
        availability: AnkiAvailability = AnkiAvailability.Ready(capabilities),
        decksToServe: List<AnkiDeck> = listOf(deck),
        reviewResults: MutableList<AnkiResult<AnkiDroidScheduledCardQuery>> = mutableListOf()
    ): Harness {
        val integration = reviewState(capabilities, availability)
        val fakeGateway = FakeAnkiDroidGateway(stateToReturn = integration)
        val deckGateway = FakeAnkiDroidDeckGateway().also { it.succeed(decksToServe) }
        val reviewGateway = FakeAnkiDroidReviewGateway(results = reviewResults)
        val backend = Backend(
            gateway = fakeGateway,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            deckGateway = deckGateway,
            reviewGateway = reviewGateway,
            cardGateway = FakeAnkiDroidCardGateway(),
            turnIds = SequentialReviewTurnIdSource(instancePrefix = "test")
        )
        return Harness(backend, reviewGateway, deckGateway, fakeGateway)
    }

    private fun context(
        deck: AnkiDeckRef? = deckRef,
        studySessionId: String = "session-1",
        backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal,
        capabilities: AnkiCapabilities = AnkiCapabilities(deckListing = true, scheduledReview = true)
    ) = AnkiSessionContext(
        backendId = backendId,
        collection = null,
        deckRef = deck,
        startedAtEpochMs = 1_700_000_000_000L,
        capabilities = capabilities,
        studySessionId = studySessionId
    )

    private fun available(noteId: String = "1700000000123", ord: Int = 0) =
        AnkiResult.Success<AnkiDroidScheduledCardQuery>(
            AnkiDroidScheduledCardQuery.Scheduled(scheduledCard(noteId, ord), queryDurationMs = 3L, mappingDurationMs = 1L)
        )

    // ---------------------------------------------------------------- begin

    @Test
    fun `a valid deck opens a session bound to backend collection and deck`() = runTest {
        val h = harness()
        val session = h.open()

        assertEquals(AnkiBackendId.AnkiDroidLocal, session.context.backendId)
        assertEquals(deckRef, session.context.deckRef)
        assertTrue(session.backendSessionRef.isNotBlank())
        // Immutable binding: availability changes cannot rewrite it (§128/INV-ANKI-REV-11).
        assertEquals("session-1", session.context.studySessionId)
    }

    @Test
    fun `repeating the identical begin returns the same handle`() = runTest {
        val h = harness()
        val first = h.open()
        val second = h.open()

        assertEquals(first, second)
        assertEquals(1, h.decks.queryDecksCalls)
    }

    @Test
    fun `a different begin for the same study session is refused not silently applied`() = runTest {
        val h = harness()
        h.open(limit = null)
        val other = h.begin(limit = 20)

        val error = (other as AnkiResult.Failure).error
        assertTrue(error is AnkiError.SessionInvalid)
    }

    @Test
    fun `a foreign backend context is refused`() = runTest {
        val h = harness()
        val result = h.backend.beginReview(
            BeginReviewRequest(context(backendId = AnkiBackendId.PcAgent("desktop")))
        )
        assertTrue((result as AnkiResult.Failure).error is AnkiError.SessionInvalid)
        assertTrue("no provider traffic for a foreign backend", h.review.queryCalls == 0)
    }

    @Test
    fun `a missing deck ref is refused rather than resolved by guessing`() = runTest {
        val h = harness()
        val error = (h.beginOn(null) as AnkiResult.Failure).error
        assertEquals("review_requires_deck_ref", (error as AnkiError.InvalidRequest).detail)
    }

    @Test
    fun `a deck that is not in the collection is a typed DeckNotFound`() = runTest {
        val h = harness()
        val missing = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "999")
        val error = (h.beginOn(missing) as AnkiResult.Failure).error
        assertEquals(AnkiError.DeckNotFound(missing), error)
    }

    @Test
    fun `an out of range session limit is refused`() = runTest {
        for (limit in listOf(0, -1, 100_000)) {
            val h = harness()
            val error = (h.begin(limit = limit) as AnkiResult.Failure).error
            assertEquals("limit=$limit", "review_limit_out_of_range", (error as AnkiError.InvalidRequest).detail)
        }
    }

    @Test
    fun `a backend without scheduledReview is refused truthfully`() = runTest {
        val h = harness(capabilities = AnkiCapabilities(deckListing = true))
        val error = (h.begin() as AnkiResult.Failure).error
        assertEquals("scheduledReview", (error as AnkiError.UnsupportedAction).action)
        assertFalse(h.backend.capabilities.value.review)
    }

    @Test
    fun `a permission loss before begin is reported as PermissionRequired`() = runTest {
        val h = harness(availability = AnkiAvailability.PermissionRequired())
        assertTrue((h.begin() as AnkiResult.Failure).error is AnkiError.PermissionRequired)
    }

    // ---------------------------------------------------------------- next card

    @Test
    fun `one scheduled card becomes exactly one turn`() = runTest {
        val h = harness(reviewResults = mutableListOf(available()))
        val session = h.open()
        val turn = (h.backend.nextCard(session) as NextCardResult.Card).turn

        assertEquals(ReviewTurnId("test:turn:1"), turn.turnId)
        assertEquals("session-1", turn.studySessionId)
        assertEquals(1, turn.position)
        // Gate 06 content is scheduler identity, never a fabricated rendered card (§27/§122).
        assertTrue(turn.content is AnkiReviewTurnContent.Scheduled)
        assertNull(turn.renderedCard)
        assertEquals("1700000000123", turn.cardRef.noteId)
        assertEquals(0, turn.cardRef.cardOrd)
        assertNull("no card id was reported, so none is invented (§17)", turn.cardRef.cardId)
        assertNull(turn.remaining)
        assertEquals(1, h.review.queryCalls)
    }

    @Test
    fun `button count intervals and media survive the trip to the domain`() = runTest {
        val media = listOf(AnkiMediaRef.BackendStream("front.png"), AnkiMediaRef.BackendStream("sound.mp3"))
        val h = harness(
            reviewResults = mutableListOf(
                AnkiResult.Success(AnkiDroidScheduledCardQuery.Scheduled(scheduledCard(media = media), 1L, 1L))
            )
        )
        val turn = (h.backend.nextCard(h.open()) as NextCardResult.Card).turn
        val card = (turn.content as AnkiReviewTurnContent.Scheduled).scheduledCard

        assertEquals(
            listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY),
            (card.ratingOptions as AnkiRatingOptions.Known).ratings
        )
        assertEquals(4, (card.ratingOptions as AnkiRatingOptions.Known).buttonCount)
        assertEquals("4d", card.scheduling?.nextReviewTimes?.get(Rating.EASY))
        assertEquals(media, card.media)
        assertEquals(4, h.backend.reviewDiagnostics().buttonCount)
    }

    @Test
    fun `a two button scheduler answer is carried through as unmapped`() = runTest {
        val h = harness(
            reviewResults = mutableListOf(
                AnkiResult.Success(
                    AnkiDroidScheduledCardQuery.Scheduled(
                        scheduledCard(ratings = AnkiRatingOptions.Unmapped(2)), 1L, 1L
                    )
                )
            )
        )
        val card = ((h.backend.nextCard(h.open()) as NextCardResult.Card).turn.content
            as AnkiReviewTurnContent.Scheduled).scheduledCard

        // The UI must be able to ask "what may I offer?" and get an honest "not these four" —
        // never a hard-coded four-button row (§20/§21/INV-ANKI-REV-09).
        assertEquals(AnkiRatingOptions.Unmapped(2), card.ratingOptions)
    }

    @Test
    fun `no due cards is Finished and never a backend failure`() = runTest {
        val h = harness(reviewResults = mutableListOf(AnkiResult.Success(AnkiDroidScheduledCardQuery.NoCardDue(0L))))
        val session = h.open()

        assertEquals(NextCardResult.Finished, h.backend.nextCard(session))
        assertEquals(NextCardResult.Finished, h.backend.nextCard(session))

        // §74/§127: an empty deck does not make the backend unhealthy.
        assertTrue(h.backend.availability.value is AnkiAvailability.Ready)
        assertTrue(h.backend.capabilities.value.scheduledReview)
        assertEquals("FINISHED", h.backend.reviewDiagnostics().lastStatus)
    }

    @Test
    fun `an empty deck is Finished and a permission loss is a failure`() = runTest {
        val empty = harness(reviewResults = mutableListOf(AnkiResult.Success(AnkiDroidScheduledCardQuery.NoCardDue(0L))))
        val emptySession = empty.open()
        assertEquals(NextCardResult.Finished, empty.backend.nextCard(emptySession))

        val denied = harness(availability = AnkiAvailability.PermissionRequired())
        val deniedSession = denied.backend.beginReview(BeginReviewRequest(context())).let { it as AnkiResult.Failure }
        assertTrue(deniedSession.error is AnkiError.PermissionRequired)
    }

    @Test
    fun `a duplicate nextCard returns the active turn instead of asking again`() = runTest {
        val h = harness(reviewResults = mutableListOf(available(), available("999", 1)))
        val session = h.open()

        val first = (h.backend.nextCard(session) as NextCardResult.Card).turn
        val second = (h.backend.nextCard(session) as NextCardResult.Card).turn

        // INV-ANKI-REV-03/08 and §92/§93: the scheduler is asked once per unresolved turn, so a
        // double tap cannot replace the card the user is looking at.
        assertEquals(first, second)
        assertEquals(1, h.review.queryCalls)
        assertEquals("1700000000123", second.cardRef.noteId)
    }

    @Test
    fun `twenty concurrent nextCard calls produce one turn and one provider query`() = runTest {
        val h = harness(reviewResults = mutableListOf(available()))
        val session = h.open()

        val results = List(20) { async { h.backend.nextCard(session) } }.awaitAll()

        val turns = results.map { (it as NextCardResult.Card).turn }.distinct()
        assertEquals(1, turns.size)
        assertEquals(1, h.review.queryCalls)
        assertEquals(1, h.backend.reviewProgress(session)?.presentedTurnCount)
    }

    @Test
    fun `a handle from another backend instance is refused as stale`() = runTest {
        // Process/backend recreation is modelled by a second backend: handles are minted per
        // instance, so the old one cannot address the new instance's collection (§97/§99/§162).
        val first = harness(reviewResults = mutableListOf(available()))
        val staleHandle = first.open()
        val second = harness(reviewResults = mutableListOf(available("555", 2)))

        assertEquals(
            NextCardResult.Failure(AnkiError.SessionInvalid()),
            second.backend.nextCard(staleHandle)
        )
        assertEquals(0, second.review.queryCalls)
    }

    @Test
    fun `ending a session forgets the handle without touching Anki`() = runTest {
        val h = harness(reviewResults = mutableListOf(available()))
        val session = h.open()
        (h.backend.nextCard(session) as NextCardResult.Card)

        assertTrue(h.backend.endReview(session))
        assertEquals(NextCardResult.Failure(AnkiError.SessionInvalid()), h.backend.nextCard(session))
        assertNull(h.backend.reviewProgress(session))
        // §86: closing a Study-Agent session mutates nothing — no rating, no scheduler call.
        assertEquals(1, h.review.queryCalls)
    }

    @Test
    fun `a concurrent new session cannot inherit an in flight turn`() = runTest {
        val h = harness(reviewResults = mutableListOf(available(), available("555", 2)))
        val sessionA = h.open(sessionId = "session-a")

        val pendingTurn = async { h.backend.nextCard(sessionA) }
        val sessionB = async { h.begin(sessionId = "session-b") }.await()

        assertTrue(pendingTurn.await() is NextCardResult.Card)
        assertTrue(sessionB is AnkiResult.Success)
        // Serialization is what makes §161 structural: B exists only after A's read installed its
        // turn, so A's result can never be handed to B.
        assertNull(h.backend.reviewProgress((sessionB as AnkiResult.Success).value)?.let { progress ->
            if (progress.hasActiveTurn) progress else null
        })
    }

    @Test
    fun `a forged handle is refused`() = runTest {
        val h = harness()
        val session = h.open()
        val forged = session.copy(backendSessionRef = "forged")
        assertEquals(NextCardResult.Failure(AnkiError.SessionInvalid()), h.backend.nextCard(forged))
        assertEquals(0, h.review.queryCalls)
    }

    @Test
    fun `a read failure leaves no turn behind and does not consume a card`() = runTest {
        val h = harness(
            reviewResults = mutableListOf(
                AnkiResult.Failure(AnkiError.QueryFailure("provider-busy")),
                available()
            )
        )
        val session = h.open()

        val failed = h.backend.nextCard(session) as NextCardResult.Failure
        assertTrue(failed.error is AnkiError.QueryFailure)
        // §58/§59: no orphaned turn identity for a presentation that never happened.
        assertEquals(0, h.backend.reviewProgress(session)?.presentedTurnCount)
        assertFalse(h.backend.reviewProgress(session)?.hasActiveTurn == true)

        // A retry is safe: the read changed nothing, so it simply asks again (§89/§90).
        val turn = (h.backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals(ReviewTurnId("test:turn:1"), turn.turnId)
        assertEquals(2, h.review.queryCalls)
    }

    @Test
    fun `a malformed scheduled card fails the turn without crashing`() = runTest {
        val h = harness(
            reviewResults = mutableListOf(
                AnkiResult.Failure(AnkiError.MalformedResponse("review_note_id_invalid"))
            )
        )
        val session = h.open()
        val failure = h.backend.nextCard(session) as NextCardResult.Failure
        assertEquals("review_note_id_invalid", (failure.error as AnkiError.MalformedResponse).detail)
        assertEquals(0, h.backend.reviewProgress(session)?.presentedTurnCount)
    }

    // ---------------------------------------------------------------- mid-session changes

    @Test
    fun `backend loss mid session is BackendUnavailable with a typed error`() = runTest {
        val h = harness(reviewResults = mutableListOf(available()))
        val session = h.open()

        h.gateway.stateToReturn = reviewState(
            capabilities = AnkiCapabilities.NONE,
            availability = AnkiAvailability.ProviderUnavailable("provider-not-resolvable")
        )
        h.backend.refreshAvailability()

        val result = h.backend.nextCard(session) as NextCardResult.BackendUnavailable
        assertTrue(result.error is AnkiError.ProviderUnavailable)
        // Never a silent fallback to another backend (§48/§132).
        assertEquals(0, h.review.queryCalls)
    }

    @Test
    fun `permission loss mid session preserves the session for recovery`() = runTest {
        val h = harness(reviewResults = mutableListOf(available()))
        val session = h.open()

        h.gateway.stateToReturn = reviewState(
            capabilities = AnkiCapabilities(deckListing = true, scheduledReview = true),
            availability = AnkiAvailability.PermissionRequired()
        )
        h.backend.refreshAvailability()

        val result = h.backend.nextCard(session) as NextCardResult.BackendUnavailable
        assertTrue(result.error is AnkiError.PermissionRequired)
        // The session is *not* discarded: regaining the permission resumes the same review (§49).
        assertEquals(deckRef, h.backend.reviewProgress(session)?.deckRef)
    }

    @Test
    fun `a deck deleted mid session fails typed instead of looking like an empty queue`() = runTest {
        val h = harness(
            reviewResults = mutableListOf(AnkiResult.Success(AnkiDroidScheduledCardQuery.NoCardDue(0L)))
        )
        val session = h.open()

        // The provider answers an unknown deck id with the *same* empty cursor it uses for an
        // exhausted deck, so an empty answer is not enough to conclude "nothing due" (§46).
        h.decks.decksResult = emptyDeckListing()

        val result = h.backend.nextCard(session) as NextCardResult.Failure
        assertEquals(AnkiError.DeckNotFound(deckRef), result.error)
        assertNull("the session is gone, not merely finished", h.backend.reviewProgress(session))
    }

    @Test
    fun `an exhausted deck with the deck still present finishes normally`() = runTest {
        val h = harness(
            reviewResults = mutableListOf(AnkiResult.Success(AnkiDroidScheduledCardQuery.NoCardDue(0L)))
        )
        val session = h.open()
        assertEquals(NextCardResult.Finished, h.backend.nextCard(session))
        assertTrue(h.backend.reviewProgress(session)?.schedulerExhausted == true)
    }

    @Test
    fun `a collection switch is not silently continued`() = runTest {
        // The pinned provider exposes no collection identity (GATE 05 §4), so a profile switch
        // cannot be *detected* by identity. What it does become is a typed failure the moment the
        // collection is no longer usable — and the session is never carried into the new one.
        val h = harness(reviewResults = mutableListOf(available()))
        val session = h.open()

        h.gateway.stateToReturn = reviewState(
            capabilities = AnkiCapabilities(deckListing = true, scheduledReview = true),
            availability = AnkiAvailability.CollectionNotInitialized
        )
        h.backend.refreshAvailability()

        val result = h.backend.nextCard(session) as NextCardResult.BackendUnavailable
        assertTrue(result.error is AnkiError.CollectionUnavailable)
    }

    @Test
    fun `cancellation is propagated and never converted into a domain failure`() = runTest {
        val h = harness()
        h.review.throwable = CancellationException("cancelled")
        val session = h.open()

        try {
            h.backend.nextCard(session)
            throw AssertionError("cancellation must propagate")
        } catch (expected: CancellationException) {
            // INV-ANKI-REV-13
        }
    }

    // ---------------------------------------------------------------- no mutation, ever

    @Test
    fun `commitRating stays a truthful refusal and no review turn is mutated`() = runTest {
        val h = harness(reviewResults = mutableListOf(available()))
        val turn = (h.backend.nextCard(h.open()) as NextCardResult.Card).turn

        val request = CommitRatingRequest(
            commitId = ReviewCommitId(AnkiBackendId.AnkiDroidLocal, "session-1", turn.turnId),
            card = turn.cardRef,
            rating = Rating.GOOD,
            ratedAtEpochMs = 1_700_000_000_000L
        )
        val result = h.backend.commitRating(request)

        assertTrue(result is CommitRatingResult.Rejected)
        assertEquals(
            "ratingCommitIntegrationPending",
            ((result as CommitRatingResult.Rejected).error as AnkiError.UnsupportedAction).action
        )
        // GATE 06 §35/§175: zero rating mutations, so the scheduler is never asked again either.
        assertEquals(1, h.review.queryCalls)
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    fun `diagnostics describe the session without leaking content`() = runTest {
        val h = harness(
            reviewResults = mutableListOf(
                AnkiResult.Success(
                    AnkiDroidScheduledCardQuery.Scheduled(
                        scheduledCard(media = listOf(AnkiMediaRef.BackendStream("patient-scan.png"))), 1L, 1L
                    )
                )
            )
        )
        val session = h.open()
        h.backend.nextCard(session)

        val diagnostics = h.backend.reviewDiagnostics()
        assertEquals("CARD_AVAILABLE", diagnostics.lastStatus)
        assertEquals("ankidroid_local", diagnostics.backendId)
        assertEquals(session.backendSessionRef, diagnostics.sessionRef)
        assertEquals(deckRef, diagnostics.deckRef)
        assertEquals("test:turn:1", diagnostics.turnId)
        assertEquals("1700000000123", diagnostics.cardRef?.noteId)
        assertEquals(4, diagnostics.buttonCount)
        assertFalse(diagnostics.toString().contains("patient-scan.png"))
    }

    @Test
    fun `progress is session scoped and reports the local presentation count`() = runTest {
        val h = harness(reviewResults = mutableListOf(available()))
        val session = h.open(limit = 10)
        assertNull(h.backend.reviewProgress(session.copy(backendSessionRef = "other")))

        val progress = h.backend.reviewProgress(session)!!
        assertEquals(deckRef, progress.deckRef)
        assertEquals(10, progress.limit)
        assertEquals(0, progress.presentedTurnCount)
        assertFalse(progress.hasActiveTurn)
        assertFalse(progress.schedulerExhausted)

        h.backend.nextCard(session)
        val after = h.backend.reviewProgress(session)!!
        assertEquals(1, after.presentedTurnCount)
        assertTrue(after.hasActiveTurn)
    }

    @Test
    fun `turn identity is Study-Agent's and distinct from the card's`() = runTest {
        val h = harness(reviewResults = mutableListOf(available(), available("999", 1)))
        val s1 = h.open(sessionId = "session-1")
        val s2 = h.open(sessionId = "session-2")

        val t1 = (h.backend.nextCard(s1) as NextCardResult.Card).turn
        val t2 = (h.backend.nextCard(s2) as NextCardResult.Card).turn

        assertNotEquals(t1.turnId, t2.turnId)
        assertNotEquals(t1.commitId, t2.commitId)
        // Same turn id ⇒ same retry key; different turn id ⇒ never a silent double commit (§57).
        assertEquals(t1.commitId, ReviewCommitId(AnkiBackendId.AnkiDroidLocal, "session-1", t1.turnId))
    }
}

/** An empty deck listing with the shape the real gateway produces. */
private fun emptyDeckListing(): AnkiResult<AnkiDroidDeckListing> = AnkiResult.Success(
    AnkiDroidDeckListing(
        decks = emptyList(),
        skippedRowCount = 0,
        skippedByReason = emptyMap(),
        rowsSeen = 0,
        countsParsed = 0,
        filteredParsed = 0,
        queryDurationMs = 0L,
        mappingDurationMs = 0L
    )
)
