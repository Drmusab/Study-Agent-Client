package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import org.junit.Assert.assertFalse

/**
 * GATE 11 — reducer + real [AnkiStudyEffectExecutor] + durable ledger + [FakeAnkiBackend].
 *
 * Effects are *collected*, never auto-run: a test decides when each one executes, which is how
 * duplicate deliveries, stale results, interleavings and "process death between two steps" are
 * expressed deterministically. [ledger] can be replaced by [restartLedger] to model a new process.
 */
class AnkiCommitHarness(
    cards: List<AnkiRenderedCard> = listOf(card("A"), card("B")),
    val store: InMemoryReviewCommitStore = InMemoryReviewCommitStore(),
    commitSteps: List<FakeAnkiBackend.CommitStep> = emptyList(),
    nextErrors: List<AnkiError> = emptyList()
) {
    var now: Long = 10_000L
    val clock: () -> Long = { now }
    val fake = FakeAnkiBackend(id = BACKEND, decks = listOf(AnkiDeck(DECK, "Deck")), cards = cards,
        commitSteps = commitSteps, nextErrors = nextErrors, instanceId = "gate11")
    var ledger = ReviewCommitLedger(store, clock)
        private set
    var executor = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(fake)), ledger, clock)
        private set

    var state = SessionMachineState.initial()
    val pending = ArrayDeque<AnkiStudyEffect>()
    val rejected = mutableListOf<String>()

    fun restartLedger() {
        ledger = store.restart(clock)
        executor = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(fake)), ledger, clock)
    }

    fun send(event: StudyEvent): Transition = StudyReducer.reduce(state, event, now).also { t ->
        state = t.newState
        if (!t.accepted) rejected += t.rejectionReason.orEmpty()
        assertFalse("No PC traffic on the local Anki path", t.effects.any { it is StudyEffect.Network })
        pending.addAll(t.effects.filterIsInstance<AnkiStudyEffect>().filterNot { it is AnkiStudyEffect.CancelReads })
    }

    /** Executes one effect, delivering intermediate and final events to the reducer in order. */
    suspend fun run(effect: AnkiStudyEffect): List<AnkiStudyEvent> {
        val delivered = mutableListOf<AnkiStudyEvent>()
        val final = executor.execute(effect) { progress -> delivered += progress; send(progress) }
        final?.let { delivered += it; send(it) }
        return delivered
    }

    /** Runs queued effects (and the effects they produce) until none are left. */
    suspend fun drain(limit: Int = 20) {
        var n = 0
        while (pending.isNotEmpty()) {
            check(n++ < limit) { "effect storm" }
            run(pending.removeFirst())
        }
    }

    suspend fun startAndLoad(speak: Boolean = false) {
        send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, DECK, speakQuestion = speak)))
        drain()
    }

    /** Loads the first card and reveals it, leaving the machine in WaitingForRating. */
    suspend fun loadToRating() {
        startAndLoad()
        now += 4_000L // the user thinks for four seconds
        send(StudyEvent.UserRequestAnswer(state.currentCardId))
        check(state.phase == SessionPhase.WaitingForRating) { "not waiting for rating: ${state.phase}" }
    }

    fun rate(rating: Rating = Rating.GOOD): Transition = send(StudyEvent.UserRateCard(rating, state.currentCardId!!))

    val commit: AnkiRatingCommit? get() = state.anki?.commit
    val turn: AnkiReviewTurn? get() = state.anki?.turn

    fun takeCommitEffect(): AnkiStudyEffect.CommitRating {
        val effect = pending.filterIsInstance<AnkiStudyEffect.CommitRating>().single()
        pending.remove(effect)
        return effect
    }

    companion object {
        val BACKEND = AnkiBackendId.AnkiDroidLocal
        val DECK = AnkiDeckRef(BACKEND, "1", "collection")

        fun card(id: String) = AnkiRenderedCard(
            AnkiCardRef(BACKEND, cardId = id, collectionKey = "collection"),
            "<b>Q $id</b>", "<b>A $id</b>", "Question $id", "Answer $id", "Answer $id",
            deckRef = DECK
        )
    }
}
