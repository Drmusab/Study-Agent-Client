package com.studyagent.client.anki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studyagent.client.appContainer
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewTurn
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.ReviewCommitOutcome
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiStudyEffectExecutor
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCardState
import com.studyagent.client.data.anki.ankidroid.ProviderQueryResult
import com.studyagent.client.di.DefaultAppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 11C — destructive verification against a disposable AnkiDroid collection.
 *
 * This test is deliberately opt-in. It will not touch an installed user's deck by accident. A
 * device operator must provide all of these instrumentation arguments:
 *
 * ```text
 * -e studyagent.ankidroid.allowMutation true
 * -e studyagent.ankidroid.disposableDeckId <id>
 * -e studyagent.ankidroid.disposableProfileConfirmed true
 * ```
 *
 * The deck/profile confirmation is an operator assertion that the selected collection is
 * disposable and contains only test cards. The public provider does not expose a reliable profile
 * identity, so the test does not invent one. The test uses the real AppContainer, provider client,
 * durable ledger, coordinator, and AnkiDroid backend; it does not use private Anki storage.
 */
@RunWith(AndroidJUnit4::class)
class AnkiDroidDisposableCommitInstrumentedTest {

    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun `one valid rating is committed once and next card waits for durable committed`() {
        val rig = disposableRig()
        val (backend, session) = openDisposableSession(rig)
        val first = runBlocking { backend.nextCard(session) }
        assumeTrue("the disposable deck has no scheduled test card", first is com.studyagent.client.core.anki.NextCardResult.Card)
        val turn = (first as com.studyagent.client.core.anki.NextCardResult.Card).turn
        val rating = selectedRating(turn)
        val stateBefore = readState(rig, turn)
        val request = CommitRatingRequest(
            commitId = turn.commitId,
            card = turn.cardRef,
            rating = rating,
            ratedAtEpochMs = System.currentTimeMillis(),
            deckRef = turn.scheduledCard.deckRef
        )

        val beforeMutation = backend.ratingMutationInvocationCount
        val committed = runBlocking { rig.coordinator.commit(request) }
        assertTrue("the coordinator must report a durable committed result", committed is ReviewCommitOutcome.Committed)
        val record = runBlocking { rig.coordinator.durableRecord(request.commitId) }
        assertEquals(ReviewCommitStatus.COMMITTED, record?.status)
        assertEquals("one logical commit enters the adapter once", beforeMutation + 1L, backend.ratingMutationInvocationCount)

        val stateAfter = readState(rig, turn)
        assertEquals("the real scheduler must increment the review count", stateBefore.reps + 1, stateAfter.reps)
        assertTrue("the real scheduler must expose a review timestamp", stateAfter.lastReviewEpochSeconds != null)

        // A duplicate Study-Agent event replays the durable ledger result; it cannot call the
        // provider again, and it cannot mint a new transaction identity.
        val duplicate = runBlocking { rig.coordinator.commit(request) }
        assertTrue(duplicate is ReviewCommitOutcome.Committed)
        assertEquals(beforeMutation + 1L, backend.ratingMutationInvocationCount)

        // The durable COMMITTED write happened before this query. The adapter does not query a
        // next card internally; this call is the first legal scheduler progression after commit.
        assertEquals(ReviewCommitStatus.COMMITTED, runBlocking { rig.coordinator.durableRecord(request.commitId) }?.status)
        val next = runBlocking { backend.nextCard(session) }
        assertTrue(
            "after a committed rating the scheduler may return another card or finish, but not a commit barrier failure: $next",
            next is com.studyagent.client.core.anki.NextCardResult.Card ||
                next is com.studyagent.client.core.anki.NextCardResult.Finished
        )
        if (next is com.studyagent.client.core.anki.NextCardResult.Card) {
            assertFalse("the answered turn must not be silently presented as the next turn", next.turn.turnId == turn.turnId)
        }
    }

    private data class Rig(
        val container: DefaultAppContainer,
        val backend: AnkiDroidBackend,
        val coordinator: AnkiStudyEffectExecutor,
        val deckId: String
    )

    private fun disposableRig(): Rig {
        assumeTrue(
            "real mutation disabled; provide studyagent.ankidroid.allowMutation=true",
            arguments.getString("studyagent.ankidroid.allowMutation") == "true"
        )
        assumeTrue(
            "operator has not confirmed a disposable AnkiDroid profile",
            arguments.getString("studyagent.ankidroid.disposableProfileConfirmed") == "true"
        )
        val deckId = arguments.getString("studyagent.ankidroid.disposableDeckId")
        assumeTrue("provide the id of the disposable test deck", !deckId.isNullOrBlank())

        val container = appContainer() as? DefaultAppContainer
        assumeTrue("the real default AppContainer is required", container != null)
        val backend = container!!.ankiDroidBackend as? AnkiDroidBackend
        assumeTrue("the real AnkiDroid backend is required", backend != null)
        return Rig(
            container = container,
            backend = backend!!,
            coordinator = AnkiStudyEffectExecutor(
                registry = container.ankiBackendRegistry,
                ledger = container.reviewCommitLedger
            ),
            deckId = deckId!!
        )
    }

    private fun openDisposableSession(rig: Rig): Pair<AnkiDroidBackend, AnkiReviewSession> {
        val backend = rig.backend
        runBlocking {
            backend.refreshAvailability()
            assumeTrue("AnkiDroid is not ready: ${backend.availability.value}", backend.availability.value is AnkiAvailability.Ready)
            assumeTrue("the real rating capability is not available", backend.capabilities.value.review)
            val decks = backend.getDecks()
            assumeTrue("disposable deck listing failed: $decks", decks is AnkiResult.Success)
            assumeTrue(
                "the requested deck is not present in the active disposable collection",
                (decks as AnkiResult.Success).value.any { it.ref.deckId == rig.deckId }
            )
        }
        val context = AnkiSessionContext(
            backendId = AnkiBackendId.AnkiDroidLocal,
            collection = null,
            deckRef = com.studyagent.client.core.anki.AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, rig.deckId),
            startedAtEpochMs = System.currentTimeMillis(),
            capabilities = backend.capabilities.value,
            studySessionId = "real-disposable-${System.currentTimeMillis()}"
        )
        val opened = runBlocking { backend.beginReview(BeginReviewRequest(context)) }
        return when (opened) {
            is AnkiResult.Success -> backend to opened.value
            is AnkiResult.Failure -> error("disposable review session refused: ${opened.error}")
        }
    }

    private fun readState(rig: Rig, turn: AnkiReviewTurn): AnkiDroidCardState {
        val authority = rig.backend.integrationState.value.metadata?.authority
        assumeTrue("the provider authority is unavailable", !authority.isNullOrBlank())
        val noteId = turn.cardRef.noteId
        val cardOrd = turn.cardRef.cardOrd
        assumeTrue("the scheduled card has no public note and ordinal identity", noteId != null && cardOrd != null)
        val path = "${AnkiDroidApiContract.NOTES_PATH}/$noteId/${AnkiDroidApiContract.NOTE_CARDS_PATH}/$cardOrd"
        val query = runBlocking {
            rig.container.ankiDroidProviderClient.safeQuery(
                authority!!,
                path,
                AnkiDroidApiContract.CARD_STATE_PROJECTION,
                null,
                null,
                null
            ) { row -> AnkiDroidCardState.fromRow(row) ?: error("unmappable card-state row") }
        }
        val rows = query as? ProviderQueryResult.Success<AnkiDroidCardState>
        assumeTrue("the public card-state query failed: $query", rows != null && rows.data.isNotEmpty())
        return rows!!.data.first()
    }

    private fun selectedRating(turn: AnkiReviewTurn): Rating {
        val options = turn.ratingOptions as? AnkiRatingOptions.Known
        assumeTrue("the provider returned an unmapped rating set: ${turn.ratingOptions}", options != null)
        val requested = arguments.getString("studyagent.ankidroid.rating")?.let(Rating::fromString)
        val selected = requested ?: options!!.ratings.first()
        assumeTrue("selected rating $selected was not offered by the scheduler", options!!.ratings.contains(selected))
        return selected
    }
}
