package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only scheduler stand-in: deterministic supplied queue, no scheduling calculations.
 * All state mutations are serialized. Delay is cancellable and uses the caller's test scheduler.
 * Identity counters survive reset; ledgers never evict a dedup key to make room for another write.
 * Use a fresh instance or reset between scenarios. Nothing registers this in production DI.
 */
class FakeBackendCommitStore {
    /** Share this across constructed fakes to model a backend-owned dedup table surviving restart. */
    internal val records = linkedMapOf<ReviewCommitId, FakeAnkiBackend.RecordedCommit>()
    internal val mutex = Mutex()
}

class FakeAnkiBackend(
    override val id: AnkiBackendId = AnkiBackendId.Fake(),
    decks: List<AnkiDeck> = emptyList(),
    cards: List<AnkiRenderedCard> = emptyList(),
    initialCapabilities: AnkiCapabilities = REVIEW_CAPABILITIES,
    initialAvailability: AnkiAvailability = AnkiAvailability.Ready(initialCapabilities),
    private val latencyMs: Long = 0,
    var decksError: AnkiError? = null,
    private val beginError: AnkiError? = null,
    nextErrors: List<AnkiError> = emptyList(),
    commitSteps: List<CommitStep> = emptyList(),
    private val maxLedgerEntries: Int = 10_000,
    private val instanceId: String = UUID.randomUUID().toString(),
    /** GATE 11 checkpoint 4 — deterministic scheduler behaviour for the failure laboratory. */
    val mode: FakeCommitMode = FakeCommitMode.SUCCESS,
    /**
     * GATE 13 — whether this fake also models the flag *write* path. Off by default, because the
     * pinned AnkiDroid public contract has no flag column at all; a test that exercises the flag
     * flow turns it on and then gets a backend whose capability set and semantics agree.
     */
    private val flagWrites: Boolean = false,
    private val guaranteeLevel: CommitGuaranteeLevel = mode.guarantee,
    private val persistedEffectStore: FakeBackendCommitStore = FakeBackendCommitStore()
) : AnkiBackend {
    override val commitSemantics = CommitSemantics(guaranteeLevel,
        supportsIdempotentReplay = guaranteeLevel == CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED ||
            guaranteeLevel == CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE,
        supportsAuthoritativeReconciliation = true, commitReceiptKind = CommitReceiptKind.NONE)
    data class CommitStep(
        val result: BackendCommitResult,
        /** Models applied-write/lost-response separately from never-applied/unknown response. */
        val appliedWhenAmbiguous: Boolean = false
    ) {
        init { require(!appliedWhenAmbiguous || result is BackendCommitResult.OutcomeUnknown) }
    }

    data class RecordedCommit(
        val request: CommitRatingRequest,
        val result: BackendCommitResult,
        val attempts: Int,
        val mutationCount: Int
    )

    // A shared simulated backend owns serialization as well as the logical commit table; two
    // recreated adapters cannot race around a process-local mutex and double-apply the same ID.
    private val mutex = persistedEffectStore.mutex
    private val deckData = decks.toMutableList()
    var selectedDeckRef: AnkiDeckRef? = decks.firstOrNull()?.ref
    var selectedDeckError: AnkiError? = null
    var getDecksCalls: Int = 0
        private set
    var getSelectedDeckCalls: Int = 0
        private set

    // GATE 15 — observable, bounded read-only browse requests. These counters never include row-level calls.
    var browseCalls: Int = 0
        private set
    val browseLimits: MutableList<Int> = mutableListOf()
    val browseQueries: MutableList<AnkiCardQuery> = mutableListOf()
    var browseError: AnkiError? = null
    var browseGate: CompletableDeferred<Unit>? = null
    var ignoreBrowseCancellation: Boolean = false

    // Defensively detach caller-owned collections so fixture mutation cannot rewrite an active turn.
    private val cardData = cards.map { card ->
        card.copy(media = card.media.toList(), metadata = card.metadata.copy(tags = card.metadata.tags.toSet()),
            scheduling = card.scheduling?.let { it.copy(nextReviewTimes = it.nextReviewTimes.toMap()) })
    }.toMutableList()
    private val nextFailures = ArrayDeque(nextErrors)
    private val commits = ArrayDeque(commitSteps)
    private val ledger = persistedEffectStore.records
    private val mutableAvailability = MutableStateFlow(coherent(initialAvailability, initialCapabilities))
    private val mutableCapabilities = MutableStateFlow(initialCapabilities)
    override val availability = mutableAvailability.asStateFlow()
    override val capabilities = mutableCapabilities.asStateFlow()

    private var serial = 0L
    private var session: AnkiReviewSession? = null
    private var beginRequest: BeginReviewRequest? = null
    private var queue: List<AnkiRenderedCard> = emptyList()
    private var cursor = 0
    private var active: AnkiReviewTurn? = null

    // ---------------------------------------------------------------- GATE 07 card hydration

    /** Read-only card lookups answered since construction (STEP 40/W — bounded call assertions). */
    var hydrateCalls: Int = 0
        private set

    /** Scripted hydration failure for every lookup (STEP 43 typed outcomes). */
    var hydrateError: AnkiError? = null

    /** Turn-scoped single-slot memo, mirroring the real backend (STEP 80-§84). */
    private var hydrationMemo: Pair<String, AnkiRenderedCard>? = null

    init {
        require(latencyMs >= 0 && maxLedgerEntries > 0 && instanceId.isNotBlank())
        require(nextErrors.size <= maxLedgerEntries && commitSteps.size <= maxLedgerEntries)
        require(deckData.all { it.ref.backendId == id })
        require(cardData.all { it.ref.backendId == id })
        require(deckData.map { it.ref }.distinct().size == deckData.size)
        requireSupported(initialCapabilities, flagWrites)
    }

    suspend fun setAvailability(value: AnkiAvailability) = mutex.withLock {
        mutableAvailability.value = coherent(value, mutableCapabilities.value)
    }

    suspend fun setCapabilities(value: AnkiCapabilities) = mutex.withLock {
        requireSupported(value, flagWrites)
        // Publish both projections under the operation lock; selector requires both to allow review.
        mutableCapabilities.value = value
        mutableAvailability.value = coherent(mutableAvailability.value, value)
    }

    suspend fun recordedCommits(): List<RecordedCommit> = mutex.withLock { ledger.values.toList() }

    // ---------------------------------------------------------------- GATE 11 commit controls

    private val invocations = AtomicInteger(0)
    private val mutationBoundaryCrossings = AtomicInteger(0)

    /** Every `commitRating` call, including ones answered from the fake's own dedup ledger. */
    val commitInvocations: Int get() = invocations.get()

    /** Durable mutation-boundary crossings accepted by the transaction layer. */
    val mutationBoundaryCrossingCount: Int get() = mutationBoundaryCrossings.get()
    /** Compatibility name: this is the boundary-attempt count, not the effect count. */
    val physicalCommitCalls: Int get() = mutationBoundaryCrossingCount
    val logicalCommitCount: Int get() = ledger.size
    /** Actual simulated scheduler effects; an ambiguous response may still have zero or one. */
    val backendEffectCount: Int get() = ledger.values.sumOf { it.mutationCount }
    /** Every commitRating delivery, including failures and backend-table replays. */
    val deliveryCount: Int get() = invocations.get()
    /** Number of durable boundary crossings, separate from deliveries and effects. */
    val mutationAttemptCount: Int get() = mutationBoundaryCrossingCount

    private val redeliveries = AtomicInteger(0)

    /** Re-deliveries of a commit id that was already delivered (a resend, not a first attempt). */
    val redeliveryCount: Int get() = redeliveries.get()

    /** Scheduler queries answered with a card; the next-card barrier must keep this at zero. */
    private val nextCards = AtomicInteger(0)
    val nextCardCount: Int get() = nextCards.get()

    /** Gate used by [FakeCommitMode.DELAYED_SUCCESS]: the effect waits here for the test. */
    private val delayedSuccessGate = CompletableDeferred<Unit>()

    /** Releases exactly the [FakeCommitMode.DELAYED_SUCCESS] delivery that is currently in flight. */
    fun releaseDelayedSuccess() { delayedSuccessGate.complete(Unit) }

    /** When set, every commit suspends here (after the call started) until the test completes it. */
    @Volatile var commitGate: CompletableDeferred<Unit>? = null

    /**
     * When set, the fake suspends *inside* the mutation window — after the durable boundary was
     * accepted and before the scheduler effect is applied. This is the "provider is slow" window
     * in which a crash or cancellation must leave the transaction AMBIGUOUS, never PREPARED.
     */
    @Volatile var mutationGate: CompletableDeferred<Unit>? = null

    /** When set, the fake throws after the durable mutation boundary has been accepted. */
    @Volatile var commitThrowable: Throwable? = null
    /** An effect may occur before the exception removes the response. */
    @Volatile var applyBeforeThrow: Boolean = false

    /** When set, `prepareCommit` refuses with this (pre-mutation). */
    @Volatile var prepareRefusal: CommitPreparation.Refused? = null
    var prepareCalls: Int = 0
        private set

    /** Scripted reconciliation answers, consumed in order; otherwise the truthful answer is used. */
    val reconcileResults: ArrayDeque<ReconcileCommitResult> = ArrayDeque()
    var reconcileCalls: Int = 0
        private set
    /** When set, reconciliation suspends on it first (models a hung provider query). */
    var reconcileGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var endReviewCalls: Int = 0
        private set

    /**
     * GATE 11D §16 test knob — the collection the fake currently considers its own. `null`
     * (default) = the fake does not observe collection identity, so no mismatch check runs.
     * When set, a reconciliation request whose card belongs to a *different known* collection
     * is inconclusive: the transaction is never redirected to the current collection
     * (INV-11D-13).
     */
    var currentCollectionKey: String? = null

    /** Invalidates all handles and drops scenario state without reusing presentation identifiers. */
    suspend fun reset() = mutex.withLock {
        ledger.clear()
        nextFailures.clear()
        commits.clear()
        session = null
        beginRequest = null
        queue = emptyList()
        cursor = 0
        active = null
        hydrationMemo = null
    }

    /**
     * GATE 11D — a client-side process restart, modelled with the real topology: the backend's
     * per-session runtime state (handle, active turn, queue cursor) is client-side state and dies
     * with the Study-Agent process, while the scheduler itself — applied effects and the
     * backend-owned dedup table — survives. This is what makes a post-restart retry of the
     * original turn fail closed with a typed pre-mutation refusal instead of double-applying.
     */
    suspend fun simulateProcessRestart() = mutex.withLock {
        session = null
        beginRequest = null
        queue = emptyList()
        cursor = 0
        active = null
        hydrationMemo = null
    }

    override suspend fun refreshAvailability() { delay(latencyMs) }

    suspend fun replaceDecks(decks: List<AnkiDeck>) = mutex.withLock {
        require(decks.all { it.ref.backendId == id })
        require(decks.map { it.ref }.distinct().size == decks.size)
        deckData.clear()
        deckData.addAll(decks)
        if (selectedDeckRef != null && decks.none { it.ref == selectedDeckRef }) selectedDeckRef = null
    }

    override suspend fun getDecks(): AnkiResult<List<AnkiDeck>> {
        delay(latencyMs)
        return mutex.withLock {
            getDecksCalls += 1
            usabilityError()?.let { return@withLock AnkiResult.Failure(it) }
            if (!capabilities.value.deckListing) return@withLock AnkiResult.Failure(unsupported("deck_listing"))
            decksError?.let { return@withLock AnkiResult.Failure(it) }
            AnkiResult.Success(deckData.toList())
        }
    }

    /**
     * GATE 15 fake transport: the *backend* evaluates the whole normative query contract — scope,
     * text, filters, sort and bounded paging — across the fixture collection before returning one
     * page. Nothing here touches the scheduler, and no client-side page-local filtering or sorting
     * exists anywhere in this path (INV-15-Q15/Q16).
     */
    override suspend fun browseCards(query: AnkiCardQuery): AnkiResult<AnkiCardPage> {
        val normalized = query.normalized()
        return if (ignoreBrowseCancellation) {
            withContext(NonCancellable) { performBrowse(normalized) }
        } else {
            performBrowse(normalized)
        }
    }

    private suspend fun performBrowse(query: AnkiCardQuery): AnkiResult<AnkiCardPage> {
        mutex.withLock {
            browseCalls += 1
            browseLimits += query.page.limit
            browseQueries += query
        }
        delay(latencyMs)
        browseGate?.await()
        return mutex.withLock {
            usabilityError()?.let { return@withLock AnkiResult.Failure(it) }

            // §63 validation order: structurally valid query, valid scope identity, supported
            // features, valid cursor — then the read.
            query.structuralError()?.let { return@withLock AnkiResult.Failure(it) }
            val collectionKey = deckData.firstOrNull()?.ref?.collectionKey

            // §6 — a missing deck is a typed NotFound, never an empty page.
            val scopeDeckIds: Set<String>? = when (val scope = query.scope) {
                AnkiCardScope.AllCards -> null
                is AnkiCardScope.Deck -> {
                    val known = deckData.firstOrNull { it.ref.deckId == scope.deckId }
                        ?: return@withLock AnkiResult.Failure(
                            AnkiError.DeckNotFound(AnkiDeckRef(id, scope.deckId, collectionKey))
                        )
                    if (scope.includeChildren) descendantDeckIds(known.ref.deckId) else setOf(known.ref.deckId)
                }
            }

            val capabilities = mutableCapabilities.value.cardBrowser
            query.unsupportedFeature(capabilities)?.let { feature ->
                return@withLock AnkiResult.Failure(AnkiError.UnsupportedQueryFeature(feature))
            }

            val pager = AnkiOffsetCursorAdapter(query.consistencyKey(id, collectionKey))
            val offset = when (val resolution = query.page.cursor?.let(pager::offsetFor)) {
                null -> 0
                is AnkiOffsetCursorAdapter.Resolution.Valid -> resolution.offset
                AnkiOffsetCursorAdapter.Resolution.Malformed ->
                    return@withLock AnkiResult.Failure(AnkiError.InvalidCursor("malformed_cursor"))
                AnkiOffsetCursorAdapter.Resolution.ForeignQuery ->
                    return@withLock AnkiResult.Failure(AnkiError.InvalidCursor("cursor_query_mismatch"))
            }

            browseError?.let { return@withLock AnkiResult.Failure(it) }

            val normalizedText = query.normalizedText
            val matching = cardData.filter { card -> matchesQuery(card, query, normalizedText, scopeDeckIds) }
            val ordered = orderBy(query.sort, matching)
            if (offset > ordered.size) {
                return@withLock AnkiResult.Failure(AnkiError.InvalidCursor("cursor_offset_out_of_range"))
            }

            val pageItems = ordered.drop(offset).take(query.page.limit).map(::toListItem)
            for (item in pageItems) {
                item.pageIdentityError(id)?.let { detail ->
                    return@withLock AnkiResult.Failure(AnkiError.DataIntegrityFailure(detail))
                }
                if (scopeDeckIds != null && item.deckId !in scopeDeckIds) {
                    return@withLock AnkiResult.Failure(AnkiError.DataIntegrityFailure("card_row_out_of_scope"))
                }
            }
            val nextOffset = offset + pageItems.size
            val nextCursor = if (nextOffset < ordered.size) pager.cursorFor(nextOffset) else null
            // §36 — an exact total only when the capability is actually advertised.
            val totalCount = ordered.size.takeIf { capabilities.supportsTotalCount }
            AnkiResult.Success(
                AnkiCardPage.of(
                    items = pageItems,
                    nextCursor = nextCursor,
                    totalCount = totalCount,
                    // §34/§40 — this backend cannot prove snapshot isolation, so it does not claim it.
                    snapshotToken = null
                )
            )
        }
    }

    /** §19 filter combination: categories AND together, flags/types OR, tags all-present. */
    private fun matchesQuery(
        card: AnkiRenderedCard,
        query: AnkiCardQuery,
        normalizedText: String?,
        scopeDeckIds: Set<String>?
    ): Boolean {
        if (scopeDeckIds != null && card.deckRef?.deckId !in scopeDeckIds) return false
        if (normalizedText != null) {
            val haystacks = listOfNotNull(card.questionText, card.answerText, card.pureAnswerText)
            if (haystacks.none { it.contains(normalizedText, ignoreCase = true) }) return false
        }
        val filters = query.filters
        if (filters.flags.isNotEmpty() && card.flag !in filters.flags) return false
        if (filters.tags.isNotEmpty() && filters.tags.any { it !in card.metadata.tags }) return false
        if (filters.cardTypes.isNotEmpty() && card.browserType() !in filters.cardTypes) return false
        when (filters.suspension) {
            SuspensionFilter.Any -> Unit
            SuspensionFilter.SuspendedOnly -> if (card.isSuspended() != true) return false
            SuspensionFilter.NotSuspended -> if (card.isSuspended() != false) return false
        }
        when (filters.burial) {
            BurialFilter.Any -> Unit
            BurialFilter.BuriedOnly -> if (card.isBuried() != true) return false
            BurialFilter.NotBuried -> if (card.isBuried() != false) return false
        }
        return true
    }

    /**
     * Authoritative hierarchy walk over the fixture deck tree (never deck-name prefix matching,
     * §66). A deck with no children resolves to itself.
     */
    private fun descendantDeckIds(deckId: String): Set<String> {
        val children = deckData.groupBy { it.parentRef?.deckId }
        val result = LinkedHashSet<String>()
        val pending = ArrayDeque<String>()
        pending += deckId
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!result.add(current)) continue
            children[current]?.forEach { child -> pending += child.ref.deckId }
        }
        return result
    }

    /** Every supported sort is deterministic: requested key first, stable card identity last (§26). */
    private fun orderBy(sort: AnkiCardSort, cards: List<AnkiRenderedCard>): List<AnkiRenderedCard> = when (sort) {
        AnkiCardSort.Default -> cards
        is AnkiCardSort.Due -> cards.sortedWith(
            keyComparator(sort.direction) { it.scheduling?.dueEpochSeconds }
        )
        is AnkiCardSort.Created -> cards.sortedWith(
            keyComparator(sort.direction) { it.metadata.noteCreatedEpochSeconds }
        )
        is AnkiCardSort.Modified -> cards.sortedWith(
            keyComparator(sort.direction) { it.metadata.noteModifiedEpochSeconds }
        )
        is AnkiCardSort.Reps -> cards.sortedWith(
            keyComparator(sort.direction) { it.scheduling?.reps?.toLong() }
        )
        is AnkiCardSort.Lapses -> cards.sortedWith(
            keyComparator(sort.direction) { it.scheduling?.lapses?.toLong() }
        )
    }

    private fun <T : Comparable<T>> keyComparator(
        direction: SortDirection,
        key: (AnkiRenderedCard) -> T?
    ): Comparator<AnkiRenderedCard> = Comparator { first, second ->
        val left = key(first)
        val right = key(second)
        val primary = when {
            left == null && right == null -> 0
            // A missing backend-reported sort key is never invented; it sorts after real values.
            left == null -> 1
            right == null -> -1
            direction == SortDirection.ASCENDING -> left.compareTo(right)
            else -> right.compareTo(left)
        }
        if (primary != 0) primary else first.ref.stableKey.compareTo(second.ref.stableKey)
    }

    private fun AnkiRenderedCard.browserType(): AnkiCardType? = when (metadata.queueState) {
        AnkiCardQueueState.NEW -> AnkiCardType.NEW
        AnkiCardQueueState.LEARNING -> AnkiCardType.LEARNING
        AnkiCardQueueState.REVIEW -> AnkiCardType.REVIEW
        AnkiCardQueueState.RELEARNING -> AnkiCardType.RELEARNING
        AnkiCardQueueState.UNKNOWN -> AnkiCardType.UNKNOWN
        AnkiCardQueueState.SUSPENDED, AnkiCardQueueState.BURIED, null -> null
    }

    private fun AnkiRenderedCard.isSuspended(): Boolean? = when (metadata.queueState) {
        null, AnkiCardQueueState.UNKNOWN -> null
        else -> metadata.queueState == AnkiCardQueueState.SUSPENDED
    }

    private fun AnkiRenderedCard.isBuried(): Boolean? = when (metadata.queueState) {
        null, AnkiCardQueueState.UNKNOWN -> null
        else -> metadata.queueState == AnkiCardQueueState.BURIED
    }

    private fun toListItem(card: AnkiRenderedCard): AnkiCardListItem = AnkiCardListItem(
        cardRef = card.ref,
        noteRef = card.noteRef,
        deckRef = card.deckRef,
        deckName = card.metadata.deckName ?: deckData.firstOrNull { it.ref == card.deckRef }?.name,
        questionText = card.questionText,
        answerText = card.answerText,
        tags = card.metadata.tags.toList(),
        flag = card.flag,
        type = card.browserType(),
        scheduling = card.scheduling,
        suspended = card.isSuspended(),
        buried = card.isBuried()
    )

    override suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?> {
        delay(latencyMs)
        return mutex.withLock {
            getSelectedDeckCalls += 1
            usabilityError()?.let { return@withLock AnkiResult.Failure(it) }
            if (!capabilities.value.deckListing) return@withLock AnkiResult.Failure(unsupported("deck_listing"))
            selectedDeckError?.let { return@withLock AnkiResult.Failure(it) }
            AnkiResult.Success(selectedDeckRef)
        }
    }

    override suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession> {
        delay(latencyMs)
        return mutex.withLock {
            val context = request.context
            if (context.backendId != id) return@withLock AnkiResult.Failure(AnkiError.SessionInvalid())
            usabilityError()?.let { return@withLock AnkiResult.Failure(it) }
            if (!capabilities.value.scheduledReview || !capabilities.value.review ||
                !context.capabilities.scheduledReview || !context.capabilities.review
            ) {
                return@withLock AnkiResult.Failure(unsupported("review"))
            }
            beginError?.let { return@withLock AnkiResult.Failure(it) }
            if (session?.context?.studySessionId == context.studySessionId) {
                return@withLock if (beginRequest == request) AnkiResult.Success(checkNotNull(session))
                else AnkiResult.Failure(AnkiError.SessionInvalid())
            }
            active?.let { turn ->
                if (ledger[turn.commitId]?.result !is BackendCommitResult.ConfirmedCommitted) {
                    return@withLock AnkiResult.Failure(AnkiError.CommitConflict())
                }
            }
            context.deckRef?.let { deck ->
                if (deckData.none { it.ref == deck }) return@withLock AnkiResult.Failure(AnkiError.DeckNotFound(deck))
            }
            val collectionKey = context.collection?.collectionKey ?: context.deckRef?.collectionKey
            val selected = cardData.filter {
                (context.deckRef == null || it.deckRef == context.deckRef) &&
                    (collectionKey == null || it.ref.collectionKey == collectionKey)
            }
            queue = selected.take(request.limit ?: selected.size)
            cursor = 0
            active = null
            beginRequest = request
            val opened = AnkiReviewSession(context, "$instanceId:session:${++serial}")
            session = opened
            AnkiResult.Success(opened)
        }
    }

    override suspend fun nextCard(session: AnkiReviewSession): NextCardResult {
        delay(latencyMs)
        return mutex.withLock {
            if (this.session != session) return@withLock NextCardResult.Failure(AnkiError.SessionInvalid())
            usabilityError()?.let { return@withLock NextCardResult.BackendUnavailable(it) }
            if (!capabilities.value.scheduledReview) {
                return@withLock NextCardResult.Failure(unsupported("scheduledReview"))
            }
            nextFailures.removeFirstOrNull()?.let { return@withLock NextCardResult.Failure(it) }
            active?.let { turn ->
                when (val recorded = ledger[turn.commitId]?.result) {
                    is BackendCommitResult.ConfirmedCommitted -> Unit
                    // An unknown outcome blocks: the scheduler state cannot be proven either way.
                    is BackendCommitResult.OutcomeUnknown ->
                        return@withLock NextCardResult.Failure(AnkiError.CommitConflict(turn.cardRef))
                    // A proven non-application only blocks when the backend called it a *conflict*
                    // with the collection state. A plain safe failure left nothing behind, so the
                    // same turn is still the current one and the same rating may be resubmitted.
                    is BackendCommitResult.ConfirmedNotCommitted ->
                        if (recorded.reason is AnkiError.CommitConflict) {
                            return@withLock NextCardResult.Failure(AnkiError.CommitConflict(turn.cardRef))
                        } else {
                            nextCards.incrementAndGet()
                            return@withLock NextCardResult.Card(turn)
                        }
                    else -> {
                        nextCards.incrementAndGet()
                        return@withLock NextCardResult.Card(turn)
                    }
                }
            }
            if (cursor == queue.size) return@withLock NextCardResult.Finished
            val card = queue[cursor]
            cursor += 1
            val turn = AnkiReviewTurn(
                ReviewTurnId("$instanceId:turn:${++serial}"), session.context.studySessionId,
                // GATE 07 — the scheduler answers with identity + metadata only; content arrives
                // through hydrateCardContent (STEP 92's scheduled → hydrate flow).
                AnkiReviewTurnContent.Scheduled(scheduledOf(card)),
                position = cursor, remaining = queue.size - cursor
            )
            active = turn
            hydrationMemo = null // a new presentation resolves the old turn's cache (STEP 83)
            nextCards.incrementAndGet()
            NextCardResult.Card(turn)
        }
    }

    /**
     * GATE 07 — the scheduler side of a fixture card: identity + rating options + the fixture's
     * scheduling labels and media references. Content is NOT copied here (STEP 92 keeps the two
     * phases apart: `nextCard` answers scheduled, `hydrateCardContent` answers rendered).
     */
    private fun scheduledOf(card: AnkiRenderedCard): AnkiScheduledCard = AnkiScheduledCard(
        ref = card.ref,
        noteRef = card.noteRef,
        deckRef = requireNotNull(card.deckRef) { "fake fixtures must carry a deck ref" },
        ratingOptions = AnkiRatingOptions.Known(SCHEDULER_BUTTON_ORDER),
        scheduling = card.scheduling,
        media = card.media,
        degradations = emptyList()
    )

    /**
     * GATE 07 — read-only hydration against the fixture "collection" (STEP 92). The stored
     * fixture is the *current* authoritative content, so replacing it between scheduling and
     * hydration reproduces STEP 45 (edited card → latest content) and removing it reproduces
     * STEP 44 (deleted card → [AnkiError.CardNotFound], never a blank card).
     */
    override suspend fun hydrateCardContent(card: AnkiCardRef): AnkiResult<AnkiRenderedCard> {
        delay(latencyMs)
        return mutex.withLock {
            usabilityError()?.let { return@withLock AnkiResult.Failure(it) }
            if (!capabilities.value.renderedCards) return@withLock AnkiResult.Failure(unsupported("renderedCards"))
            if (card.backendId != id) {
                return@withLock AnkiResult.Failure(AnkiError.InvalidRequest(detail = "card_ref_foreign_backend"))
            }
            hydrateError?.let { return@withLock AnkiResult.Failure(it) }
            // Single-flight: the lock plus this re-check collapse concurrent consumers into one
            // lookup, mirroring the real backend's hydration mutex (STEP 80).
            hydrationMemo?.let { (key, cached) ->
                if (key == card.stableKey) return@withLock AnkiResult.Success(cached)
            }
            hydrateCalls += 1
            // Identity-confirmed lookup: enrichment is fine, unconfirmed claims are not (STEP 54).
            val found = cardData.firstOrNull { AnkiCardHydration.identityMatches(card, it.ref) }
                ?: return@withLock AnkiResult.Failure(AnkiError.CardNotFound(card = card))
            hydrationMemo = card.stableKey to found
            AnkiResult.Success(found)
        }
    }

    /**
     * Test-only fixture mutation (STEP 45): replaces the stored content of the card whose ref
     * matches [updated], modelling an edit made in AnkiDroid. Read-only towards Anki itself —
     * this mutates the stand-in's own data, never a backend.
     */
    suspend fun replaceCard(updated: AnkiRenderedCard): Boolean = mutex.withLock {
        require(updated.ref.backendId == id)
        val index = cardData.indexOfFirst { AnkiCardHydration.identityMatches(updated.ref, it.ref) }
        if (index < 0) return@withLock false
        cardData[index] = updated
        hydrationMemo = null
        true
    }

    /** Test-only fixture mutation (STEP 44): the card is gone; hydration must fail typed. */
    suspend fun deleteCard(card: AnkiCardRef): Boolean = mutex.withLock {
        val removed = cardData.removeAll { AnkiCardHydration.identityMatches(card, it.ref) }
        if (removed) hydrationMemo = null
        removed
    }

    /** Evidence = the fixture card's applied-review count, so reconciliation is checkable. */
    override suspend fun prepareCommit(request: CommitRatingRequest): CommitPreparation {
        delay(latencyMs)
        if (mode.refusesBeforeDispatch) {
            prepareCalls += 1
            return CommitPreparation.Refused(AnkiError.BackendUnavailable(), retryable = true)
        }
        return mutex.withLock {
            prepareCalls += 1
            prepareRefusal?.let { return@withLock it }
            val applied = ledger.values.filter { it.request.card == request.card }.sumOf { it.mutationCount }
            CommitPreparation.Ready(ReviewCommitEvidence("fake-reviews-v1", "applied=$applied"))
        }
    }

    /**
     * Truthful by default: the stand-in knows whether an AMBIGUOUS write really applied
     * ([CommitStep.appliedWhenAmbiguous]). Scripted [reconcileResults] override it. A decision is
     * mirrored into the fake's own ledger, exactly like the real backend's session record.
     */
    override suspend fun reconcileCommit(request: ReconcileCommitRequest): ReconcileCommitResult {
        reconcileGate?.await()
        delay(latencyMs)
        return mutex.withLock {
            reconcileCalls += 1
            // GATE 11D read-only evidence boundaries: an unreachable backend proves nothing
            // (§20/INV-11D-15); a card that no longer exists is not evidence in either
            // direction (§19/INV-11D-14); a collection the transaction does not belong to must
            // never be silently re-targeted (§16/INV-11D-13).
            usabilityError()?.let { return@withLock ReconcileCommitResult.Unavailable(it) }
            currentCollectionKey?.let { expected ->
                val actual = request.card.collectionKey
                if (actual != null && actual != expected) {
                    return@withLock ReconcileCommitResult.StillAmbiguous("collection_mismatch")
                }
            }
            if (cardData.none { AnkiCardHydration.identityMatches(request.card, it.ref) }) {
                return@withLock ReconcileCommitResult.StillAmbiguous("card_not_found")
            }
            val recorded = ledger[request.commitId]
            val answer = reconcileResults.removeFirstOrNull() ?: when {
                recorded == null -> ReconcileCommitResult.StillAmbiguous("fake_unknown_commit")
                recorded.mutationCount > 0 -> ReconcileCommitResult.Applied("fake_applied")
                else -> ReconcileCommitResult.NotApplied("fake_not_applied")
            }
            if (recorded != null) {
                when (answer) {
                    is ReconcileCommitResult.Applied ->
                        ledger[request.commitId] = recorded.copy(result = BackendCommitResult.ConfirmedCommitted())
                    is ReconcileCommitResult.NotApplied -> {
                        ledger[request.commitId] = recorded.copy(
                            result = BackendCommitResult.ConfirmedNotCommitted(AnkiError.QueryFailure("fake_reconciled_not_applied")))
                    }
                    else -> Unit
                }
            }
            answer
        }
    }

    /** Explicit user abort: releases the handle and any unresolved turn. Never a mutation. */
    override suspend fun endReview(session: AnkiReviewSession): Boolean = mutex.withLock {
        endReviewCalls += 1
        if (this.session != session) return@withLock false
        this.session = null
        beginRequest = null
        active = null
        hydrationMemo = null
        true
    }

    override suspend fun commitRating(request: CommitRatingRequest): BackendCommitResult =
        commitRatingInternal(request, mutationEntry = null)

    override suspend fun commitRating(
        request: CommitRatingRequest,
        mutationEntry: suspend () -> Boolean
    ): BackendCommitResult = commitRatingInternal(request, mutationEntry)

    private suspend fun commitRatingInternal(
        request: CommitRatingRequest,
        mutationEntry: (suspend () -> Boolean)?
    ): BackendCommitResult {
        invocations.incrementAndGet()
        delay(latencyMs)
        commitGate?.await()
        return mutex.withLock {
            if (request.commitId.backendId != id || request.card.backendId != id) {
                return@withLock BackendCommitResult.ConfirmedNotCommitted(AnkiError.SessionInvalid())
            }
            val previous = ledger[request.commitId]
            if (previous != null) {
                redeliveries.incrementAndGet()
                if (previous.request.card != request.card || previous.request.rating != request.rating ||
                    previous.request.deckRef != request.deckRef || previous.request.collectionRef != request.collectionRef) {
                    // A different payload wearing a known transaction id is refused outright: it is
                    // not an attempt of this transaction, so it must not touch its recorded state.
                    return@withLock BackendCommitResult.ConfirmedNotCommitted(AnkiError.CommitConflict(request.card))
                }
                if (guaranteeLevel == CommitGuaranteeLevel.LOCAL_DEDUP_ONLY && previous.mutationCount > 0) {
                    // This mode deliberately has no backend-owned dedup protection: the same id
                    // would cross the boundary and apply a second scheduler effect.
                    if (!crossMutationBoundary(mutationEntry)) {
                        return@withLock BackendCommitResult.ConfirmedNotCommitted(AnkiError.CommitLedgerUnavailable())
                    }
                    ledger[request.commitId] = previous.copy(attempts = previous.attempts + 1,
                        mutationCount = previous.mutationCount + 1, result = BackendCommitResult.ConfirmedCommitted())
                    return@withLock BackendCommitResult.ConfirmedCommitted()
                }
                if (commitSemantics.supportsIdempotentReplay && previous.result is BackendCommitResult.OutcomeUnknown) {
                    // A backend-owned dedup table answers from recorded truth; no mutation boundary.
                    val result = if (previous.mutationCount > 0) BackendCommitResult.ConfirmedCommitted()
                        else BackendCommitResult.ConfirmedNotCommitted(AnkiError.QueryFailure("fake_no_effect"))
                    ledger[request.commitId] = previous.copy(result = result)
                    return@withLock result
                }
                // At-most-once: a non-retryable/ambiguous response is answered without re-dispatch.
                if (previous.result !is BackendCommitResult.ConfirmedNotCommitted) return@withLock previous.result
            }
            if (session?.context?.studySessionId != request.commitId.studySessionId) {
                return@withLock BackendCommitResult.ConfirmedNotCommitted(AnkiError.SessionInvalid())
            }
            val turn = active
            if (turn == null || turn.turnId != request.commitId.turnId || turn.cardRef != request.card) {
                return@withLock BackendCommitResult.ConfirmedNotCommitted(AnkiError.StaleTurn())
            }
            if (previous == null && ledger.size >= maxLedgerEntries) {
                return@withLock BackendCommitResult.ConfirmedNotCommitted(AnkiError.QueryFailure("fake-ledger-full"))
            }
            fun recordPreMutationResult(result: BackendCommitResult): BackendCommitResult {
                ledger[request.commitId] = RecordedCommit(request, result,
                    (previous?.attempts ?: 0) + 1, previous?.mutationCount ?: 0)
                return result
            }
            val unavailable = usabilityError()
            if (unavailable != null) {
                return@withLock recordPreMutationResult(BackendCommitResult.ConfirmedNotCommitted(unavailable))
            }
            if (!capabilities.value.review) {
                return@withLock recordPreMutationResult(BackendCommitResult.ConfirmedNotCommitted(unsupported("review")))
            }
            if (mode.refusesBeforeDispatch) {
                return@withLock recordPreMutationResult(
                    BackendCommitResult.ConfirmedNotCommitted(AnkiError.BackendUnavailable()))
            }
            if (mode == FakeCommitMode.FAIL_BEFORE_MUTATION) {
                // A delivered request can fail in read-only preflight before the callback. Record
                // the known no-effect result so a same-id retry remains safe and countable.
                val refused = BackendCommitResult.ConfirmedNotCommitted(AnkiError.QueryFailure("fake_fail_before_mutation"))
                ledger[request.commitId] = RecordedCommit(request, refused, (previous?.attempts ?: 0) + 1, 0)
                return@withLock refused
            }
            val scripted = commits.removeFirstOrNull()
            if (!crossMutationBoundary(mutationEntry)) {
                return@withLock BackendCommitResult.ConfirmedNotCommitted(AnkiError.CommitLedgerUnavailable())
            }
            mutationGate?.await() // the durable boundary is now behind us, the effect is not yet applied
            commitThrowable?.let { thrown ->
                ledger[request.commitId] = RecordedCommit(request, BackendCommitResult.OutcomeUnknown(),
                    (previous?.attempts ?: 0) + 1, if (applyBeforeThrow) 1 else 0)
                throw thrown
            }
            val step = when {
                // A scripted step always wins over the mode, so existing scenarios keep their
                // exact sequencing.
                scripted != null -> scripted
                mode == FakeCommitMode.OUTCOME_UNKNOWN ->
                    // Nothing applied, and the backend cannot prove it: fail closed despite zero effect.
                    CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("fake_outcome_unknown")))
                mode == FakeCommitMode.MUTATE_THEN_DROP_RESPONSE -> {
                    // The effect happens; the response never arrives.
                    ledger[request.commitId] = RecordedCommit(request, BackendCommitResult.OutcomeUnknown(),
                        (previous?.attempts ?: 0) + 1, 1)
                    throw FakeTransportLost("fake_response_dropped_after_effect")
                }
                mode == FakeCommitMode.DELAYED_SUCCESS -> {
                    delayedSuccessGate.await()
                    CommitStep(BackendCommitResult.ConfirmedCommitted())
                }
                else -> CommitStep(BackendCommitResult.ConfirmedCommitted())
            }
            val applied = step.result is BackendCommitResult.ConfirmedCommitted || step.appliedWhenAmbiguous
            ledger[request.commitId] = RecordedCommit(request, step.result, (previous?.attempts ?: 0) + 1,
                if (applied) 1 else 0)
            step.result
        }
    }

    /** True only when the durable boundary accepted this potential physical mutation. */
    private suspend fun crossMutationBoundary(mutationEntry: (suspend () -> Boolean)?): Boolean {
        if (mutationEntry != null && !mutationEntry()) return false
        mutationBoundaryCrossings.incrementAndGet()
        return true
    }

    private fun usabilityError(): AnkiError? = availability.value.unavailabilityError()
    private fun unsupported(action: String) = AnkiError.UnsupportedAction(action)

    // ---------------------------------------------------------------- GATE 13 reviewer actions

    /**
     * One scripted reviewer-action answer. Steps are consumed in order, and the last one that was
     * used repeats — so a test that only cares about one outcome scripts one step, and a test that
     * wants "refused, then applied" scripts two.
     */
    data class ActionStep(
        val result: ReviewerActionBackendResult,
        /** False models an answer that never asks to cross the durable boundary (§17 violation). */
        val callsMutationEntry: Boolean = true,
        /** Refuses the boundary, as a backend must when the caller cannot make `SUBMITTING` durable. */
        val refusesMutationEntry: Boolean = false,
        /** The read-only evidence `reconcileReviewerAction` reports while this step is current. */
        val reconciliation: ReviewerActionReconciliationResult? = null
    )

    private val actionSteps = ArrayDeque<ActionStep>()
    /** The last step that was used; it repeats once the script runs out. */
    private var lastActionStep: ActionStep? = null
    private val actionInvocations = AtomicInteger(0)
    private val actionBoundaryCrossings = AtomicInteger(0)
    private val actionReconciliations = AtomicInteger(0)

    /** Every `performReviewerAction` call. */
    val reviewerActionInvocations: Int get() = actionInvocations.get()

    /** Accepted mutation-boundary crossings: what the ledger actually authorized. */
    val reviewerActionBoundaryCrossings: Int get() = actionBoundaryCrossings.get()

    /** Read-only reconciliation probes (§27). Never a mutation. */
    val reviewerActionReconciliationCount: Int get() = actionReconciliations.get()

    /** Scripts the next answers, in order. */
    fun scriptActions(vararg steps: ActionStep) = actionSteps.addAll(steps)

    override fun reviewerActionSemantics(action: ReviewerAction): ReviewerActionSemantics =
        when (action.kind) {
            // Mirrors the pinned AnkiDroid claim: bury/suspend are verified in both directions,
            // a flag has no public write path at all — unless this fake was asked to model one.
            ReviewerActionKind.BURY, ReviewerActionKind.SUSPEND -> ReviewerActionSemantics(
                supportsIdempotentReplay = true,
                supportsAuthoritativeReconciliation = true
            )
            ReviewerActionKind.FLAG -> if (flagWrites) ReviewerActionSemantics(
                supportsIdempotentReplay = true,
                supportsAuthoritativeReconciliation = false
            ) else ReviewerActionSemantics.UNVERIFIED
        }

    override suspend fun performReviewerAction(
        cardRef: AnkiCardRef,
        action: ReviewerAction,
        mutationEntry: suspend () -> Boolean
    ): ReviewerActionBackendResult = mutex.withLock {
        actionInvocations.incrementAndGet()
        val step = actionSteps.removeFirstOrNull()?.also { lastActionStep = it }
            ?: lastActionStep
            ?: defaultActionStep(action)
        if (step.refusesMutationEntry) {
            return@withLock ReviewerActionBackendResult.ConfirmedNotApplied(
                AnkiError.QueryFailure("boundary_refused"))
        }
        if (step.callsMutationEntry) {
            if (!mutationEntry()) {
                return@withLock ReviewerActionBackendResult.ConfirmedNotApplied(
                    AnkiError.ActionLedgerUnavailable())
            }
            actionBoundaryCrossings.incrementAndGet()
        }
        // GATE 13 — a confirmed turn-invalidating action really changes what the scheduler hands
        // back: the card leaves the queue and the active turn is released, exactly like AnkiDroid's
        // `sched.buryCards` / `sched.suspendCards`. Without this the fake would keep offering the
        // buried card and a "fresh scheduler query" assertion would be vacuous.
        if (step.result is ReviewerActionBackendResult.ConfirmedApplied && action.invalidatesCurrentTurn) {
            if (active?.cardRef == cardRef) active = null
            val index = queue.indexOfFirst { it.ref == cardRef }
            if (index >= 0) {
                queue = queue.filterIndexed { i, _ -> i != index }
                if (index < cursor) cursor -= 1
            }
        }
        step.result
    }

    override suspend fun reconcileReviewerAction(
        request: ReconcileReviewerActionRequest
    ): ReviewerActionReconciliationResult = mutex.withLock {
        actionReconciliations.incrementAndGet()
        (actionSteps.firstOrNull() ?: lastActionStep)?.reconciliation
            ?: ReviewerActionReconciliationResult.Unresolved(AnkiError.Unknown("no_evidence"))
    }

    /** The default answer: a confirmable bury/suspend, or an unsupported flag. */
    private fun defaultActionStep(action: ReviewerAction): ActionStep = when (action.kind) {
        ReviewerActionKind.BURY, ReviewerActionKind.SUSPEND -> ActionStep(
            ReviewerActionBackendResult.ConfirmedApplied(
                ReviewerActionReceipt(
                    backendId = id,
                    actionKey = action.key,
                    cardState = ReviewerCardState.fromQueue(
                        if (action.kind == ReviewerActionKind.BURY) ReviewerCardState.QUEUE_MANUALLY_BURIED
                        else ReviewerCardState.QUEUE_SUSPENDED
                    ),
                    detail = "confirmed_by_fake"
                )
            )
        )
        ReviewerActionKind.FLAG -> if (flagWrites) {
            ActionStep(
                ReviewerActionBackendResult.ConfirmedApplied(
                    ReviewerActionReceipt(
                        backendId = id,
                        actionKey = action.key,
                        flag = (action as? ReviewerAction.SetFlag)?.flag,
                        detail = "confirmed_by_fake"
                    )
                )
            )
        } else {
            ActionStep(
                ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.UnsupportedAction(action.key))
            )
        }
    }

    companion object {
        /**
         * GATE 15 §62 — the complete structured browser capability set. Every advertised field is
         * implemented by [FakeAnkiBackend.browseCards]; nothing is advertised that the fake cannot
         * honour, because a capability that lies is worse than a missing one.
         */
        val CARD_BROWSER_CAPABILITIES = AnkiCardBrowserCapabilities(
            canBrowseAllCards = true,
            canBrowseDeck = true,
            canIncludeChildDecks = true,
            canSearchText = true,
            supportedFilters = AnkiCardFilterCapability.entries.toSet(),
            supportedSorts = AnkiCardSortCapability.entries.toSet(),
            supportsTotalCount = true,
            maxPageSize = AnkiPageRequest.MAX_LIMIT
        )

        /**
         * GATE 15 §20/§76 — the deliberately limited capability set used by the negative contract
         * tests: no text search, tag filtering only, reps sorting only, no exact total. A backend
         * with these capabilities must *reject* the rest, never drop it silently.
         */
        val LIMITED_CARD_BROWSER_CAPABILITIES = CARD_BROWSER_CAPABILITIES.copy(
            canSearchText = false,
            supportedFilters = setOf(AnkiCardFilterCapability.TAGS),
            supportedSorts = setOf(AnkiCardSortCapability.REPS),
            supportsTotalCount = false
        )

        val REVIEW_CAPABILITIES = AnkiCapabilities(
            review = true,
            scheduledReview = true,
            deckListing = true,
            renderedCards = true,
            reviewIntervals = true,
            cardBrowser = CARD_BROWSER_CAPABILITIES
        )

        /**
         * GATE 13 — the rating capabilities plus the reviewer-action write path the fake models.
         * Flags stay absent: the pinned AnkiDroid contract cannot express a flag write, so the fake
         * mirrors that and a test that wants a flag must supply its own backend.
         */
        val REVIEW_ACTION_CAPABILITIES = REVIEW_CAPABILITIES.copy(bury = true, suspendCards = true)

        /** The stand-in scheduler offers the four buttons the pinned AnkiDroid provider hard-codes. */
        val SCHEDULER_BUTTON_ORDER: List<Rating> =
            listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY)

        private fun coherent(state: AnkiAvailability, capabilities: AnkiCapabilities): AnkiAvailability =
            if (state is AnkiAvailability.Ready) AnkiAvailability.Ready(capabilities) else state

        private fun requireSupported(value: AnkiCapabilities, flagWrites: Boolean) {
            // GATE 13 — bury/suspend are implemented by this fake (the same reviewer-action family
            // the AnkiDroid provider contract exposes). Flags are only implemented when the test
            // asks for them (`flagWrites`), mirroring the pinned AnkiDroid contract where there is
            // no public flag write to model.
            require((!value.flags || flagWrites) && !value.editNotes && !value.createNotes &&
                !value.search && !value.media) { "Fake does not implement these features" }
        }
    }
}

/**
 * GATE 11 checkpoint 4 — the response is gone after the collection was already changed. This is a
 * transport failure, not a scheduler answer, and the client must fail closed (AMBIGUOUS).
 */
class FakeTransportLost(message: String) : RuntimeException(message)
