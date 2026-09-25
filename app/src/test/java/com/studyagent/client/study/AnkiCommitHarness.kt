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
    /**
     * Backend identity the fake runs under. The default impersonates AnkiDroid, whose semantics are
     * clamped to AT_MOST_ONCE_FAIL_CLOSED with *no* authoritative reconciliation no matter what the
     * fake claims. Tests that need a backend with verified reconciliation must choose another id.
     */
    val backendId: AnkiBackendId = BACKEND,
    private val mode: AnkiBackendMode = AnkiBackendMode.ANKIDROID_LOCAL,
    cards: List<AnkiRenderedCard> = listOf(card("A", backendId), card("B", backendId)),
    val store: InMemoryReviewCommitStore = InMemoryReviewCommitStore(),
    commitSteps: List<FakeAnkiBackend.CommitStep> = emptyList(),
    nextErrors: List<AnkiError> = emptyList(),
    private val faults: CommitFaultInjector = NoCommitFaults,
    /** Optional decorator around the fake (e.g. an ordered call recorder). Never changes semantics. */
    wrap: (FakeAnkiBackend) -> AnkiBackend = { it },
    /** LOCAL_DEDUP_ONLY makes the fake re-apply any repeated id that already had an effect. */
    guarantee: CommitGuaranteeLevel = CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED
) {
    var now: Long = 10_000L
    val clock: () -> Long = { now }
    val deck: AnkiDeckRef = deckFor(backendId)
    val fake = FakeAnkiBackend(id = backendId, decks = listOf(AnkiDeck(deck, "Deck")), cards = cards,
        commitSteps = commitSteps, nextErrors = nextErrors, instanceId = "gate11", guaranteeLevel = guarantee)
    val backend: AnkiBackend = wrap(fake)
    var ledger = ReviewCommitLedger(store, clock)
        private set
    var executor = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(backend)), ledger, clock, faults)
        private set

    var state = SessionMachineState.initial()
    val pending = ArrayDeque<AnkiStudyEffect>()
    val rejected = mutableListOf<String>()

    fun restartLedger() {
        ledger = store.restart(clock)
        executor = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(backend)), ledger, clock, faults)
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
        send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", mode, deck, speakQuestion = speak)))
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

        /** A non-AnkiDroid identity whose fake may legitimately advertise authoritative reconciliation. */
        val RECONCILABLE_BACKEND = AnkiBackendId.PcAgent("gate11-reconcilable")

        fun deckFor(backend: AnkiBackendId) = AnkiDeckRef(backend, "1", "collection")

        fun card(id: String, backend: AnkiBackendId = BACKEND) = AnkiRenderedCard(
            AnkiCardRef(backend, cardId = id, collectionKey = "collection"),
            "<b>Q $id</b>", "<b>A $id</b>", "Question $id", "Answer $id", "Answer $id",
            deckRef = deckFor(backend)
        )

        /** Harness over a backend whose frozen semantics include authoritative reconciliation. */
        fun reconcilable(commitSteps: List<FakeAnkiBackend.CommitStep>) = AnkiCommitHarness(
            backendId = RECONCILABLE_BACKEND, mode = AnkiBackendMode.PC_AGENT, commitSteps = commitSteps
        )
    }
}
