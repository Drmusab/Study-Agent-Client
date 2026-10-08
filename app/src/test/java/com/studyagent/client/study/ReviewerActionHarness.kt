package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.anki.fake.InMemoryReviewerActionStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import org.junit.Assert.assertFalse

/**
 * GATE 13 — reducer + real [AnkiStudyEffectExecutor] + the durable reviewer-action ledger + the
 * production [DefaultReviewerActionCoordinator] + [FakeAnkiBackend].
 *
 * Effects are *collected*, never auto-run: a test decides when each one executes, which is how
 * duplicate deliveries, stale results, interleavings and "process death between two steps" are
 * expressed deterministically. [restart] models a new process over the same durable state (both
 * ledgers: the reviewer-action one and the rating one, since §23 mutual exclusion reads both).
 */
class ReviewerActionHarness(
    val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal,
    private val mode: AnkiBackendMode = AnkiBackendMode.ANKIDROID_LOCAL,
    cards: List<AnkiRenderedCard> = listOf(card("A", backendId), card("B", backendId)),
    val store: InMemoryReviewerActionStore = InMemoryReviewerActionStore(),
    val commitStore: InMemoryReviewCommitStore = InMemoryReviewCommitStore(),
    capabilities: AnkiCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES,
    flagWrites: Boolean = false
) {
    var now: Long = 10_000L
    val clock: () -> Long = { now }
    val deck: AnkiDeckRef = deckFor(backendId)
    val fake = FakeAnkiBackend(
        id = backendId,
        decks = listOf(AnkiDeck(deck, "Deck")),
        cards = cards,
        initialCapabilities = capabilities,
        instanceId = "gate13",
        flagWrites = flagWrites
    )
    val backend: AnkiBackend = fake
    val semantics = ReviewerActionCapabilities(
        flag = capabilities.flags,
        bury = capabilities.bury,
        suspend = capabilities.suspendCards,
        semantics = mapOf(
            ReviewerActionKind.BURY to fake.reviewerActionSemantics(ReviewerAction.BuryCard),
            ReviewerActionKind.SUSPEND to fake.reviewerActionSemantics(ReviewerAction.SuspendCard),
            ReviewerActionKind.FLAG to fake.reviewerActionSemantics(ReviewerAction.SetFlag(AnkiFlag.RED))
        )
    )

    var commitLedger = ReviewCommitLedger(commitStore, clock)
        private set
    var ledger = DurableReviewerActionLedger(store, clock)
        private set
    var executor = newExecutor()
        private set

    var state = SessionMachineState.initial()
    val pending = ArrayDeque<AnkiStudyEffect>()
    val rejected = mutableListOf<String>()

    private fun newExecutor() = AnkiStudyEffectExecutor(
        registry = AnkiBackendRegistry(listOf(backend)),
        ledger = commitLedger,
        clock = clock,
        actionLedger = ledger
    )

    /** A new process over the same durable state: ledger memory dies, the store survives. */
    fun restart() {
        ledger = store.restart(clock)
        commitLedger = commitStore.restart(clock)
        executor = newExecutor()
    }

    fun send(event: StudyEvent): Transition = StudyReducer.reduce(state, event, now).also { transition ->
        state = transition.newState
        if (!transition.accepted) rejected += transition.rejectionReason.orEmpty()
        assertFalse("No PC traffic on the local Anki path", transition.effects.any { it is StudyEffect.Network })
        pending.addAll(
            transition.effects.filterIsInstance<AnkiStudyEffect>().filterNot { it is AnkiStudyEffect.CancelReads }
        )
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
        send(AnkiStudyEvent.Start(AnkiStudyRequest(
            studySessionId = "study-1",
            preference = mode,
            deck = deck,
            speakQuestion = speak,
            reviewerActions = semantics
        )))
        drain()
    }

    /** Reveals the answer, leaving the machine in WaitingForRating with an open turn. */
    suspend fun loadToRating() {
        startAndLoad()
        now += 4_000L
        send(StudyEvent.UserRequestAnswer(state.currentCardId))
        check(state.phase == SessionPhase.WaitingForRating) { "not waiting for rating: ${state.phase}" }
    }

    fun rate(rating: Rating = Rating.GOOD) = send(StudyEvent.UserRateCard(rating, state.currentCardId!!))

    fun requestAction(action: ReviewerAction) =
        send(AnkiStudyEvent.ReviewerActionRequested(action, epoch = state.epoch, cardId = state.currentCardId))

    val action: AnkiReviewerAction? get() = state.anki?.reviewerAction
    val turn: AnkiReviewTurn? get() = state.anki?.turn
    val commit: AnkiRatingCommit? get() = state.anki?.commit

    fun takeEffect(clazz: Class<out AnkiStudyEffect>): AnkiStudyEffect {
        val effect = pending.first { clazz.isInstance(it) }
        pending.remove(effect)
        return effect
    }

    fun takePerformEffect(): AnkiStudyEffect.PerformReviewerAction =
        takeEffect(AnkiStudyEffect.PerformReviewerAction::class.java) as AnkiStudyEffect.PerformReviewerAction

    fun takeNextEffects(): List<AnkiStudyEffect.Next> =
        pending.filterIsInstance<AnkiStudyEffect.Next>().also { next -> next.forEach { pending.remove(it) } }

    /** Direct coordinator access, for the transaction-level assertions (VERIFICATION 1-13). */
    fun coordinator(): DefaultReviewerActionCoordinator =
        DefaultReviewerActionCoordinator(ledger, commitLedger, AnkiBackendRegistry(listOf(backend)), clock)

    companion object {
        val BACKEND = AnkiBackendId.AnkiDroidLocal

        fun deckFor(backend: AnkiBackendId) = AnkiDeckRef(backend, "1", "collection")

        fun card(id: String, backend: AnkiBackendId = BACKEND) = AnkiRenderedCard(
            AnkiCardRef(backend, cardId = id, collectionKey = "collection"),
            "<b>Q $id</b>", "<b>A $id</b>", "Question $id", "Answer $id", "Answer $id",
            deckRef = deckFor(backend)
        )

        /** A harness whose fake also models the flag write path (the pinned AnkiDroid cannot). */
        fun flagCapable(): ReviewerActionHarness = ReviewerActionHarness(
            capabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES.copy(flags = true),
            flagWrites = true
        )
    }
}
