package com.studyagent.client.anki

import com.studyagent.client.anki.ankidroid.AnkiDroidApiCapabilityReport
import com.studyagent.client.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.anki.ankidroid.AnkiDroidCardQueryDiagnostics
import com.studyagent.client.anki.ankidroid.AnkiDroidHealthSnapshot
import com.studyagent.client.anki.ankidroid.AnkiDroidIntegrationState
import com.studyagent.client.anki.ankidroid.AnkiDroidMetadata
import com.studyagent.client.anki.ankidroid.AnkiDroidProviderSpecSource
import com.studyagent.client.anki.ankidroid.AnkiDroidScheduledCardQuery
import com.studyagent.client.anki.ankidroid.CapabilitySupport
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.testutil.TestClock
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
 * GATE 07 — the whole hydration journey, asserted against **both** implementations: the fake
 * backend (STEP 92's scheduled → hydrate flow, fixture mutations for deleted/edited cards) and
 * the real AnkiDroid backend on its fakes (single-flight, turn-scoped memo, typed failures).
 *
 * These tests hold the line on the acceptance criteria: stale hydration never crosses turns,
 * duplicate consumers never stampede the provider, a deleted card fails typed instead of looking
 * blank, and every content shape (HTML, plain, cloze, Arabic, mixed, large, metadata-poor)
 * survives the trip byte-for-byte.
 */
class AnkiCardFlowTest {

    // ---------------------------------------------------------------- fixtures (STEP 93)

    private fun htmlCard() = card("html").copy(
        questionHtml = "<div class=\"card\"><b>Front &amp; <i>markup</i></b></div>",
        answerHtml = "Back<br><hr id=answer>Extra",
        questionText = "Front & markup",
        answerText = "Back\n\n<hr id=answer>Extra",
        pureAnswerText = "Extra"
    )

    private fun plainCard() = card("plain").copy(
        questionHtml = "Just words",
        answerHtml = "Just answers",
        questionText = "Just words",
        answerText = "Just answers",
        pureAnswerText = "Just answers"
    )

    private fun clozeCard() = card("cloze").copy(
        questionHtml = "The {{c1::mitral valve}} is in the {{c2::heart}}.",
        answerHtml = "The <span class=cloze>mitral valve</span> is in the {{c2::heart}}.",
        questionText = "The {{c1::mitral valve}} is in the {{c2::heart}}.",
        answerText = "The mitral valve is in the {{c2::heart}}.",
        pureAnswerText = "mitral valve"
    )

    private fun arabicCard() = card("arab").copy(
        questionHtml = "<b dir=\"rtl\">ما هو القلب؟</b>",
        answerHtml = "<div dir=\"rtl\">عضلة تضخ الدم</div>",
        questionText = "ما هو القلب؟",
        answerText = "عضلة تضخ الدم",
        pureAnswerText = "عضلة تضخ الدم"
    )

    private fun mixedCard() = card("mixed").copy(
        questionHtml = "<b>Heart</b> — القلب",
        answerHtml = " pump<br>مضخة",
        questionText = "Heart — القلب",
        answerText = "pump\nمضخة",
        pureAnswerText = "pump"
    )

    private fun largeCard() = card("large").copy(
        questionHtml = "<div>" + "payload ".repeat(5_000) + "</div>",
        answerHtml = "<div>" + "answer ".repeat(5_000) + "</div>",
        questionText = "payload ".repeat(5_000),
        answerText = "answer ".repeat(5_000),
        pureAnswerText = "answer ".repeat(1_000)
    )

    private fun metadataPoorCard() = card("sparse").copy(
        questionHtml = "q",
        answerHtml = null,
        questionText = null,
        answerText = null,
        pureAnswerText = null,
        scheduling = null,
        metadata = AnkiCardMetadata(),
        degradations = listOf("card_speech_text_unavailable")
    )

    private fun backend(vararg cards: AnkiRenderedCard) = FakeAnkiBackend(
        fakeId, listOf(deck()), cards.toList()
    )

    // ---------------------------------------------------------------- content channels (STEP 93)

    @Test fun `every content shape survives hydration byte for byte`() = runTest {
        for (fixture in listOf(htmlCard(), plainCard(), clozeCard(), arabicCard(), mixedCard(), largeCard(), metadataPoorCard())) {
            val backend = backend(fixture)
            val session = backend.begin()
            val turn = (backend.nextCard(session) as NextCardResult.Card).turn
            // The scheduled phase carries identity, never fabricated content (§27/§122).
            assertNull(turn.renderedCard)
            assertEquals(fixture.ref, turn.scheduledCard.ref)

            val hydrated = (backend.hydrateCardContent(turn.cardRef) as AnkiResult.Success).value
            // Verbatim: no sanitizing, no entity decoding, no Unicode normalization, no
            // whitespace collapsing, no template expansion (INV-ANKI-CARD-08/28, STEP 20).
            assertEquals(fixture.questionHtml, hydrated.questionHtml)
            assertEquals(fixture.answerHtml, hydrated.answerHtml)
            assertEquals(fixture.questionText, hydrated.questionText)
            assertEquals(fixture.answerText, hydrated.answerText)
            assertEquals(fixture.pureAnswerText, hydrated.pureAnswerText)
            assertEquals(fixture.degradations, hydrated.degradations)
            // The three channels stay distinct: speech never borrows HTML, evaluation never
            // borrows either of the other channels' raw values (INV-ANKI-CARD-06/07).
            assertEquals(fixture.evaluationAnswerText, hydrated.evaluationAnswerText)
        }
    }

    @Test fun `hydration enriches the turn and preserves the scheduled identity`() = runTest {
        val backend = backend(htmlCard())
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val hydratedTurn = (AnkiCardHydration.attach(
            turn, (backend.hydrateCardContent(turn.cardRef) as AnkiResult.Success).value
        ) as AnkiResult.Success).value
        assertEquals(turn.turnId, hydratedTurn.turnId) // same presentation (INV-ANKI-CARD-22)
        assertEquals(turn.scheduledCard, hydratedTurn.scheduledCard) // scheduler metadata intact (23)
        assertTrue(hydratedTurn.content is AnkiReviewTurnContent.Rendered)
    }

    // ---------------------------------------------------------------- lifecycle (STEP 44/45/49/80-§84)

    @Test fun `a deleted card fails typed and never becomes a blank card`() = runTest {
        val backend = backend(htmlCard())
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        // GATE 07 is read-only towards Anki — the fixture edit models the user deleting the card
        // in AnkiDroid (STEP 44).
        assertTrue(backend.deleteCard(turn.cardRef))
        val failure = backend.hydrateCardContent(turn.cardRef) as AnkiResult.Failure
        val notFound = failure.error as AnkiError.CardNotFound
        assertEquals(turn.cardRef, notFound.card) // typed and identity-bearing
    }

    @Test fun `a card edited in AnkiDroid hydrates to its latest content`() = runTest {
        val original = htmlCard()
        val backend = backend(original)
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val before = (backend.hydrateCardContent(turn.cardRef) as AnkiResult.Success).value
        assertEquals(original.questionHtml, before.questionHtml)

        // The user edits the card in AnkiDroid (STEP 45). The next hydration is the *latest*
        // authoritative content — content is replaceable, identity is not (§84).
        val edited = original.copy(answerText = "edited answer", pureAnswerText = "edited")
        assertTrue(backend.replaceCard(edited))
        val after = (backend.hydrateCardContent(turn.cardRef) as AnkiResult.Success).value
        assertEquals("edited answer", after.answerText)
        assertEquals(original.ref, after.ref)
    }

    @Test fun `a late hydration never overwrites a different turn`() = runTest {
        val backend = backend(htmlCard(), plainCard())
        val session = backend.begin()
        val first = (backend.nextCard(session) as NextCardResult.Card).turn
        val late = (backend.hydrateCardContent(first.cardRef) as AnkiResult.Success).value
        // The turn advances while the first result is still in flight (STEP 49).
        assertTrue(backend.commitRating(first.request(Rating.GOOD)) is CommitRatingResult.Committed)
        val second = (backend.nextCard(session) as NextCardResult.Card).turn
        assertNotEquals(first.turnId, second.turnId)
        // The late result verifies against the *turn it is attached to* — here it does not
        // belong, so it is refused instead of replacing the new presentation (INV-ANKI-CARD-30).
        assertTrue(AnkiCardHydration.attach(second, late) is AnkiResult.Failure)
        assertNull(second.renderedCard)
    }

    @Test fun `duplicate consumers share one read and the memo dies with the turn`() = runTest {
        val backend = backend(htmlCard(), plainCard(), htmlCard().copy(ref = card("html").ref))
        val session = backend.begin()
        val first = (backend.nextCard(session) as NextCardResult.Card).turn
        repeat(5) {
            backend.hydrateCardContent(first.cardRef)
        }
        // Duplicates hit the memo — one lookup total (STEP 80-§82).
        assertEquals(1, backend.hydrateCalls)

        backend.commitRating(first.request(Rating.GOOD))
        val second = (backend.nextCard(session) as NextCardResult.Card).turn
        backend.commitRating(second.request(Rating.GOOD))
        val third = (backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals(first.cardRef, third.cardRef) // the same card returns…
        backend.hydrateCardContent(third.cardRef)
        // …but the new presentation resolved the old turn's cache (STEP 83/§84): re-read, not
        // resurrected content.
        assertEquals(2, backend.hydrateCalls)
    }

    @Test fun `concurrent consumers of one card collapse into a single lookup`() = runTest {
        val backend = backend(htmlCard())
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val results = List(20) { async { backend.hydrateCardContent(turn.cardRef) } }.awaitAll()
        assertTrue(results.all { it is AnkiResult.Success })
        assertEquals(1, backend.hydrateCalls) // single-flight (STEP 80)
    }

    @Test fun `idempotent hydration answers equal content on every read`() = runTest {
        val backend = backend(mixedCard())
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val first = (backend.hydrateCardContent(turn.cardRef) as AnkiResult.Success).value
        val second = (backend.hydrateCardContent(turn.cardRef) as AnkiResult.Success).value
        assertEquals(first, second)
    }

    @Test fun `hydration of another backend's card is refused before any lookup`() = runTest {
        val backend = backend(htmlCard())
        val foreign = card("A", id = AnkiBackendId.PcAgent("desktop"))
        val failure = backend.hydrateCardContent(foreign.ref) as AnkiResult.Failure
        assertTrue(failure.error is AnkiError.InvalidRequest)
        assertEquals(0, backend.hydrateCalls)
    }

    @Test fun `a backend without card content support says so truthfully`() = runTest {
        val caps = AnkiCapabilities(deckListing = true, scheduledReview = true)
        val backend = FakeAnkiBackend(
            fakeId, listOf(deck()), listOf(htmlCard()), initialCapabilities = caps
        )
        val failure = backend.hydrateCardContent(htmlCard().ref) as AnkiResult.Failure
        assertTrue(failure.error is AnkiError.UnsupportedAction)
        assertEquals(0, backend.hydrateCalls)
    }

    @Test fun `typed failures pass through hydration unchanged`() = runTest {
        val backend = backend(htmlCard())
        backend.hydrateError = AnkiError.PermissionRequired()
        val failure = backend.hydrateCardContent(htmlCard().ref) as AnkiResult.Failure
        assertTrue(failure.error is AnkiError.PermissionRequired)
    }

    @Test fun `a cancelled hydration is a cancellation and never a domain failure`() = runTest {
        val backend = FakeAnkiBackend(
            fakeId, listOf(deck()), listOf(htmlCard()), latencyMs = 50
        )
        val turn = (backend.nextCard(backend.begin()) as NextCardResult.Card).turn
        // The call is abandoned mid-flight (user navigated away): it times out — which is
        // structured cancellation — and no AnkiResult.Failure appears anywhere (INV-ANKI-CARD-29).
        val result = kotlinx.coroutines.withTimeoutOrNull(10) { backend.hydrateCardContent(turn.cardRef) }
        assertNull(result)
    }

    // ---------------------------------------------------------------- the real backend on its fakes

    private fun ankiDroidState(capabilities: AnkiCapabilities): AnkiDroidIntegrationState = AnkiDroidIntegrationState(
        availability = AnkiAvailability.Ready(capabilities),
        capabilities = capabilities,
        apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN.copy(specVersion = 2),
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

    private fun realBackend(
        cardGateway: com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidCardGateway,
        capabilities: AnkiCapabilities = AnkiCapabilities(deckListing = true, scheduledReview = true, renderedCards = true),
        scheduled: List<AnkiScheduledCard> = emptyList()
    ): AnkiDroidBackend {
        val deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1700000000000")
        val reviewResults = scheduled.map {
            AnkiResult.Success<AnkiDroidScheduledCardQuery>(
                AnkiDroidScheduledCardQuery.Scheduled(it, 1L, 1L)
            )
        }.toMutableList()
        return AnkiDroidBackend(
            gateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidGateway(stateToReturn = ankiDroidState(capabilities)),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            clock = TestClock(),
            deckGateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidDeckGateway(),
            reviewGateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidReviewGateway(results = reviewResults),
            cardGateway = cardGateway,
            turnIds = com.studyagent.client.anki.ankidroid.SequentialReviewTurnIdSource(instancePrefix = "flow")
        )
    }

    private fun realCard(): AnkiRenderedCard = AnkiRenderedCard(
        ref = AnkiCardRef(AnkiBackendId.AnkiDroidLocal, cardId = "42", noteId = "7", cardOrd = 0),
        questionHtml = "<b>Front</b>",
        answerHtml = "Back",
        questionText = "Front",
        answerText = "Back",
        pureAnswerText = null
    )

    private fun realScheduled(): AnkiScheduledCard = AnkiScheduledCard(
        ref = AnkiCardRef(AnkiBackendId.AnkiDroidLocal, cardId = null, noteId = "7", cardOrd = 0),
        noteRef = AnkiNoteRef(AnkiBackendId.AnkiDroidLocal, "7"),
        deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1700000000000"),
        ratingOptions = AnkiRatingOptions.Known(listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY))
    )

    @Test fun `the real backend coalesces concurrent hydration into one provider read`() = runTest {
        val cardGateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidCardGateway(
            results = mutableListOf(AnkiResult.Success(realCard()))
        )
        val backend = realBackend(cardGateway)
        val results = List(20) { async { backend.hydrateCardContent(realCard().ref) } }.awaitAll()
        assertTrue(results.all { it is AnkiResult.Success })
        assertEquals(1, cardGateway.queryCalls) // single-flight at the session boundary (STEP 80)
    }

    @Test fun `the real backend's memo is turn scoped and cleared at session end`() = runTest {
        val cardGateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidCardGateway(
            results = mutableListOf(AnkiResult.Success(realCard()))
        )
        val backend = realBackend(cardGateway, scheduled = listOf(realScheduled()))
        backend.hydrateCardContent(realCard().ref)
        backend.hydrateCardContent(realCard().ref)
        assertEquals(1, cardGateway.queryCalls) // duplicate → memo (STEP 82)

        val context = AnkiSessionContext(
            backendId = backend.id,
            collection = null,
            deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1700000000000"),
            startedAtEpochMs = 1_700_000_000_000L,
            capabilities = AnkiCapabilities(deckListing = true, scheduledReview = true, renderedCards = true),
            studySessionId = "flow-1"
        )
        val session = (backend.beginReview(BeginReviewRequest(context)) as AnkiResult.Success).value
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals("7", turn.cardRef.noteId)

        // Ending the session resolves the turn and its cache (STEP 83): the next lookup goes
        // back to the backend as the single source of truth (§84).
        assertTrue(backend.endReview(session))
        backend.hydrateCardContent(realCard().ref)
        assertEquals(2, cardGateway.queryCalls)
    }

    @Test fun `the real backend surfaces gateway failures as typed results`() = runTest {
        val cardGateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidCardGateway(
            results = mutableListOf(
                AnkiResult.Failure(AnkiError.StaleCardReference(card = null, detail = "card_identity_mismatch"))
            )
        )
        val backend = realBackend(cardGateway)
        val failure = backend.hydrateCardContent(realCard().ref) as AnkiResult.Failure
        assertTrue(failure.error is AnkiError.StaleCardReference)
        assertEquals(AnkiDroidCardQueryDiagnostics.NONE, backend.cardGatewayDiagnostics())
    }

    @Test fun `the real backend refuses to fabricate content when the capability is missing`() = runTest {
        val cardGateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidCardGateway()
        val backend = realBackend(cardGateway, capabilities = AnkiCapabilities.NONE)
        val failure = backend.hydrateCardContent(realCard().ref) as AnkiResult.Failure
        assertTrue(failure.error is AnkiError.UnsupportedAction)
        assertEquals(0, cardGateway.queryCalls)
        assertFalse(cardGateway.requestedPaths.isNotEmpty())
    }

    @Test fun `the real backend refuses foreign card references before any lookup`() = runTest {
        val cardGateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidCardGateway()
        val backend = realBackend(cardGateway)
        val foreign = realCard().ref.copy(backendId = AnkiBackendId.PcAgent("desktop"))
        val failure = backend.hydrateCardContent(foreign) as AnkiResult.Failure
        assertTrue(failure.error is AnkiError.InvalidRequest)
        assertEquals(0, cardGateway.queryCalls)
    }

    @Test fun `cancellation of a real hydration is never an ordinary failure`() = runTest {
        val cardGateway = com.studyagent.client.anki.ankidroid.FakeAnkiDroidCardGateway(
            throwable = kotlinx.coroutines.CancellationException("abandoned")
        )
        val backend = realBackend(cardGateway)
        var caught: Throwable? = null
        try {
            backend.hydrateCardContent(realCard().ref)
        } catch (throwable: Throwable) {
            caught = throwable
        }
        assertTrue(caught is kotlinx.coroutines.CancellationException)
    }
}
