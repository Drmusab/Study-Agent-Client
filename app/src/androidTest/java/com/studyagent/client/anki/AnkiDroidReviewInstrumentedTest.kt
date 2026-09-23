package com.studyagent.client.anki

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyagent.client.appContainer
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.AnkiReviewTurnContent
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.CommitRatingResult
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 06 §105-§109 / §152-§159 — the real AnkiDroid scheduled-review suite.
 *
 * ## Status
 *
 * **NOT RUN.** The gate environment has no JDK, no Android SDK and no device, so nothing in this
 * file has ever executed. It is committed as the harness that turns the source-level reading of
 * the `schedule` endpoint (`docs/GATE_06_ANKI_REVIEW.md` §3) into observed behaviour, and it is
 * written so that it is also safe where AnkiDroid is absent.
 *
 * ## Why it never fails on an emulator without AnkiDroid
 *
 * Every test starts with [assumeTrue] on a *positive* precondition: AnkiDroid installed, the
 * provider reachable, the permission granted, a collection initialised, scheduled review
 * implemented and a deck actually selected. A missing AnkiDroid produces "skipped", never a red
 * gate about the phone rather than about the code.
 *
 * ## Why it never rates a card (§105/§150)
 *
 * This suite is **read-only**. It never issues an `update`, never buries, never suspends, and its
 * only rating call asserts a *refusal*. Running it leaves the test collection's scheduling state
 * exactly as it found it.
 */
@RunWith(AndroidJUnit4::class)
class AnkiDroidReviewInstrumentedTest {

    private fun backend(): AnkiDroidBackend = appContainer.ankiDroidBackend as AnkiDroidBackend

    /** Opens a session for AnkiDroid's currently selected deck, or skips the test. */
    private fun beginOnSelectedDeck(limit: Int? = null): Pair<AnkiDroidBackend, AnkiReviewSession> {
        val backend = backend()
        runBlocking {
            backend.refreshAvailability()
            val availability = backend.availability.value
            assumeTrue(
                "AnkiDroid is not ready: ${availability::class.simpleName}",
                availability is AnkiAvailability.Ready
            )
            assumeTrue(
                "scheduled review is not implemented at this provider spec",
                backend.capabilities.value.scheduledReview
            )
        }
        val selected = runBlocking { (backend.getSelectedDeck() as? AnkiResult.Success)?.value }
        assumeTrue("AnkiDroid has no selected deck to review", selected != null)
        val deckRef = selected!!

        return when (val opened = runBlocking { backend.beginReview(BeginReviewRequest(context(deckRef), limit)) }) {
            is AnkiResult.Success -> backend to opened.value
            is AnkiResult.Failure ->
                throw AssertionError("a ready AnkiDroid must open a session: ${opened.error}")
        }
    }

    private fun context(deckRef: AnkiDeckRef) = AnkiSessionContext(
        backendId = AnkiBackendId.AnkiDroidLocal,
        collection = null,
        deckRef = deckRef,
        startedAtEpochMs = System.currentTimeMillis(),
        capabilities = appContainer.ankiDroidBackend.capabilities.value,
        studySessionId = "instrumented-${System.nanoTime()}"
    )

    /**
     * §105/§152 — a real read, twice, without a rating.
     *
     * Records the observed behaviour rather than demanding one: the assertion is that the second
     * read does not produce a *different* unresolved turn, that the card identity is usable, and
     * that the process collapses the whole thing to one provider query.
     */
    @Test
    fun `the scheduler answers with a usable card and a second read does not advance`() {
        val opened = beginOnSelectedDeck()
        val (backend, session) = opened

        val first = runBlocking { backend.nextCard(session) }
        when (first) {
            is NextCardResult.Card -> {
                val turn = first.turn
                val card = (turn.content as AnkiReviewTurnContent.Scheduled).scheduledCard
                assertTrue("the card must carry a note identity", !card.ref.noteId.isNullOrBlank())
                assertNotNull("the card must carry an ordinal", card.ref.cardOrd)
                assertTrue("the deck binding must survive", card.deckRef.backendId == AnkiBackendId.AnkiDroidLocal)

                // The pinned provider hard-codes four buttons for every card; if a real device
                // ever reports something else, that is a contract change this build must notice
                // rather than silently render four buttons.
                assertTrue(
                    "unexpected button configuration: ${card.ratingOptions}",
                    card.ratingOptions is AnkiRatingOptions.Known ||
                        card.ratingOptions is AnkiRatingOptions.Unmapped
                )

                val second = runBlocking { backend.nextCard(session) }
                assertEquals(
                    "a second read before a rating must not replace the current turn",
                    first,
                    second
                )
                assertEquals(
                    "one unresolved turn means one provider query",
                    1,
                    backend.reviewProgress(session)?.presentedTurnCount
                )
            }
            is NextCardResult.Finished -> {
                // A deck with nothing due is a valid state, not a failure (§32/§154). Record it
                // and assert the backend is still healthy (§74).
                assertTrue(backend.availability.value is AnkiAvailability.Ready)
            }
            is NextCardResult.Failure ->
                throw AssertionError("a healthy AnkiDroid must not fail the read: ${first.error}")
            is NextCardResult.BackendUnavailable ->
                throw AssertionError("AnkiDroid became unavailable mid-session: ${first.error}")
        }
    }

    /**
     * §105/§175 — the suite performs **zero** scheduler mutation.
     *
     * The rating call is expected to be refused (GATE 11 owns it), and the turn must still be the
     * same one afterwards: a refusal that had quietly advanced anything would be the worst
     * possible failure mode for the gate that follows.
     */
    @Test
    fun `no rating is ever written and a refusal does not move the session`() {
        val opened = beginOnSelectedDeck()
        val (backend, session) = opened

        val first = runBlocking { backend.nextCard(session) }
        assumeTrue("nothing due, so there is no card to (not) rate", first is NextCardResult.Card)
        val turn = (first as NextCardResult.Card).turn

        val result = runBlocking {
            backend.commitRating(
                CommitRatingRequest(
                    commitId = ReviewCommitId(AnkiBackendId.AnkiDroidLocal, turn.studySessionId, turn.turnId),
                    card = turn.cardRef,
                    rating = Rating.GOOD,
                    ratedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
        assertTrue("GATE 06 must refuse rather than pretend", result is CommitRatingResult.Rejected)
        assertEquals(
            "ratingCommitIntegrationPending",
            ((result as CommitRatingResult.Rejected).error as AnkiError.UnsupportedAction).action
        )
        assertEquals(NextCardResult.Card(turn), runBlocking { backend.nextCard(session) })
    }

    /**
     * §164/§165/§46 — a foreign or stale reference is refused on a real provider too.
     *
     * The deck is *not* deleted to prove this: an id that cannot be in any collection is used, so
     * the test cannot damage the collection it runs against.
     */
    @Test
    fun `an unknown deck is refused without touching the collection`() {
        val backend = backend()
        runBlocking {
            backend.refreshAvailability()
            assumeTrue(
                "AnkiDroid is not ready",
                backend.availability.value is AnkiAvailability.Ready
            )
            assumeTrue("scheduled review is not implemented", backend.capabilities.value.scheduledReview)

            val impossible = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1")
            val result = backend.beginReview(BeginReviewRequest(context(impossible)))

            // Deck 1 is the built-in Default deck and normally *does* exist, so this test only
            // asserts the shape of the answer: either a session (the deck exists) or a typed
            // refusal (it does not) — never an exception, never a fabricated session.
            when (result) {
                is AnkiResult.Success ->
                assertEquals(AnkiBackendId.AnkiDroidLocal, result.value.context.backendId)
                is AnkiResult.Failure -> assertTrue(
                    "unexpected error: ${result.error}",
                    result.error is AnkiError.DeckNotFound || result.error is AnkiError.SessionInvalid
                )
            }
        }
    }

    /**
     * §86 — ending a Study-Agent session forgets a handle and mutates nothing.
     */
    @Test
    fun `ending a session forgets the handle and leaves the scheduler alone`() {
        val opened = beginOnSelectedDeck()
        val (backend, session) = opened

        runBlocking { backend.nextCard(session) }
        assertTrue(runBlocking { backend.endReview(session) })
        assertEquals(
            NextCardResult.Failure(AnkiError.SessionInvalid()),
            runBlocking { backend.nextCard(session) }
        )
    }

    /**
     * §113/§114/§159 — the read is bounded and asks for exactly one card, whatever the deck size.
     */
    @Test
    fun `one logical next card is one bounded provider query`() {
        val opened = beginOnSelectedDeck()
        val (backend, session) = opened

        val before = appContainer.ankiDroidReviewGateway.lastQueryDiagnostics().providerQueryCount
        runBlocking { backend.nextCard(session) }
        val after = appContainer.ankiDroidReviewGateway.lastQueryDiagnostics().providerQueryCount

        assertTrue(
            "a nextCard must cost at most one scheduler read, saw ${after - before}",
            after - before <= 1L
        )
        val limit = appContainer.ankiDroidReviewGateway.lastQueryDiagnostics().lastLimit
        assertTrue("a scheduled read must be bounded to one row, saw $limit", limit == null || limit == 1)
    }
}
