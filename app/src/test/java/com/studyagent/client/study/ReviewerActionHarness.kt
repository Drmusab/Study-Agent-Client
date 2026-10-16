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
    flagWrites: Boolean = false,
    /** Explicit test-only contract; false mirrors AnkiDroid's unsupported historical OP-M3. */
    authoritativeReviewerActionReconciliation: Boolean = false,
    commitSteps: List<FakeAnkiBackend.CommitStep> = emptyList(),
    /**
     * GATE 21I — other backends installed alongside the session's own. A registry that holds more
     * than one backend is what makes "the action is not redirected by a preference change"
     * observable: a preference-driven resolution could pick another backend, and must not.
     */
    val extraBackends: List<AnkiBackend> = emptyList(),
    /**
     * GATE 21/21I — the session's immutable backend lock, exactly as production wires it
     * (`AnkiStudyEffectExecutor.sessionLocks`). It is what makes the `OP-M1` session-backend
     * consistency check and the "bound live in this process" evidence real in this harness.
     */
    val sessionLocks: AnkiStudyBackendLockTracker = AnkiStudyBackendLockTracker(),
    /**
     * GATE 21H/21I — the collection identity this fake reports, if any.
     *
     * The default is production reality: AnkiDroid reports none (no key exists, DISCOVERY-21-03),
     * so a session bound to it records no collection and the collection dimension stays genuinely
     * unknown; every other identity models a *keyed* backend whose cards and session carry the
     * same key it reports, so a durable record can reach an authoritative collection match.
     * `OP-M3` still additionally requires an explicit authoritative-reconciliation capability;
     * tests opt into that separately through `authoritativeReviewerActionReconciliation`. Tests
     * that need a keyed *local* backend alongside a PC substitute (so session-start resolution
     * stays unambiguous) override this explicitly.
     */
    liveCollectionKey: String? = if (backendId == BACKEND) null else COLLECTION_KEY
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
        flagWrites = flagWrites,
        authoritativeReviewerActionReconciliation = authoritativeReviewerActionReconciliation,
        commitSteps = commitSteps
    ).apply {
        currentCollectionIdentity = liveCollectionKey?.let { AnkiCollectionIdentity(backendId, it) }
    }
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

    /**
     * GATE 21I — the registry is one shared instance across simulated process restarts: a backend
     * that was *removed from configuration* stays removed, while a backend that merely lost
     * connectivity is still the same registered instance (its scheduler state outlives the
     * client process, which is what makes a lost response recoverable).
     */
    val registry = AnkiBackendRegistry(listOf(backend) + extraBackends)
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
        registry = registry,
        ledger = commitLedger,
        clock = clock,
        actionLedger = ledger,
        sessionLocks = sessionLocks
    )

    /**
     * A new process over the same durable state: ledger memory dies, the store survives. The
     * backends themselves are the same objects — a real backend's scheduler state outlives the
     * client process, which is exactly what makes a lost response recoverable.
     */
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
        DefaultReviewerActionCoordinator(ledger, commitLedger, registry, clock)

    companion object {
        val BACKEND = AnkiBackendId.AnkiDroidLocal

        /** The collection key this harness's fixtures carry on cards and decks. */
        const val COLLECTION_KEY = "collection"

        fun deckFor(backend: AnkiBackendId) = AnkiDeckRef(backend, "1", COLLECTION_KEY)

        fun card(
            id: String,
            backend: AnkiBackendId = BACKEND,
            collectionKey: String? = COLLECTION_KEY
        ) = AnkiRenderedCard(
            AnkiCardRef(backend, cardId = id, collectionKey = collectionKey),
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
