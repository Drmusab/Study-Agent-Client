package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Test-only scheduler stand-in: deterministic supplied queue, no scheduling calculations.
 * All state mutations are serialized. Delay is cancellable and uses the caller's test scheduler.
 * Identity counters survive reset; ledgers never evict a dedup key to make room for another write.
 * Use a fresh instance or reset between scenarios. Nothing registers this in production DI.
 */
class FakeAnkiBackend(
    override val id: AnkiBackendId = AnkiBackendId.Fake(),
    decks: List<AnkiDeck> = emptyList(),
    cards: List<AnkiRenderedCard> = emptyList(),
    initialCapabilities: AnkiCapabilities = REVIEW_CAPABILITIES,
    initialAvailability: AnkiAvailability = AnkiAvailability.Ready(initialCapabilities),
    private val latencyMs: Long = 0,
    private val decksError: AnkiError? = null,
    private val beginError: AnkiError? = null,
    nextErrors: List<AnkiError> = emptyList(),
    commitSteps: List<CommitStep> = emptyList(),
    private val maxLedgerEntries: Int = 10_000,
    private val instanceId: String = UUID.randomUUID().toString()
) : AnkiBackend {
    data class CommitStep(
        val result: CommitRatingResult,
        /** Models applied-write/lost-response separately from never-applied/unknown response. */
        val appliedWhenAmbiguous: Boolean = false
    ) {
        init { require(!appliedWhenAmbiguous || result is CommitRatingResult.Ambiguous) }
    }

    data class RecordedCommit(
        val request: CommitRatingRequest,
        val result: CommitRatingResult,
        val attempts: Int,
        val mutationCount: Int
    )

    private val mutex = Mutex()
    private val deckData = decks.toMutableList()
    var selectedDeckRef: AnkiDeckRef? = decks.firstOrNull()?.ref
    var selectedDeckError: AnkiError? = null
    var getDecksCalls: Int = 0
        private set
    // Defensively detach caller-owned collections so fixture mutation cannot rewrite an active turn.
    private val cardData = cards.map { card ->
        card.copy(media = card.media.toList(), metadata = card.metadata.copy(tags = card.metadata.tags.toSet()),
            scheduling = card.scheduling?.let { it.copy(nextReviewTimes = it.nextReviewTimes.toMap()) })
    }
    private val nextFailures = ArrayDeque(nextErrors)
    private val commits = ArrayDeque(commitSteps)
    private val ledger = linkedMapOf<ReviewCommitId, RecordedCommit>()
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

    init {
        require(latencyMs >= 0 && maxLedgerEntries > 0 && instanceId.isNotBlank())
        require(nextErrors.size <= maxLedgerEntries && commitSteps.size <= maxLedgerEntries)
        require(deckData.all { it.ref.backendId == id })
        require(cardData.all { it.ref.backendId == id })
        require(deckData.map { it.ref }.distinct().size == deckData.size)
        requireSupported(initialCapabilities)
    }

    suspend fun setAvailability(value: AnkiAvailability) = mutex.withLock {
        mutableAvailability.value = coherent(value, mutableCapabilities.value)
    }

    suspend fun setCapabilities(value: AnkiCapabilities) = mutex.withLock {
        requireSupported(value)
        // Publish both projections under the operation lock; selector requires both to allow review.
        mutableCapabilities.value = value
        mutableAvailability.value = coherent(mutableAvailability.value, value)
    }

    suspend fun recordedCommits(): List<RecordedCommit> = mutex.withLock { ledger.values.toList() }

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

    override suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?> {
        delay(latencyMs)
        return mutex.withLock {
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
            if (!capabilities.value.review || !context.capabilities.review) {
                return@withLock AnkiResult.Failure(unsupported("review"))
            }
            beginError?.let { return@withLock AnkiResult.Failure(it) }
            if (session?.context?.studySessionId == context.studySessionId) {
                return@withLock if (beginRequest == request) AnkiResult.Success(checkNotNull(session))
                else AnkiResult.Failure(AnkiError.SessionInvalid())
            }
            active?.let { turn ->
                if (ledger[turn.commitId]?.result !is CommitRatingResult.Committed) {
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
            if (!capabilities.value.review) return@withLock NextCardResult.Failure(unsupported("review"))
            nextFailures.removeFirstOrNull()?.let { return@withLock NextCardResult.Failure(it) }
            active?.let { turn ->
                when (ledger[turn.commitId]?.result) {
                    is CommitRatingResult.Committed -> Unit
                    is CommitRatingResult.Ambiguous, is CommitRatingResult.Rejected ->
                        return@withLock NextCardResult.Failure(AnkiError.CommitConflict(turn.card.ref))
                    else -> return@withLock NextCardResult.Card(turn)
                }
            }
            if (cursor == queue.size) return@withLock NextCardResult.Finished
            val card = queue[cursor]
            cursor += 1
            val turn = AnkiReviewTurn(
                ReviewTurnId("$instanceId:turn:${++serial}"), session.context.studySessionId,
                card, position = cursor, remaining = queue.size - cursor
            )
            active = turn
            NextCardResult.Card(turn)
        }
    }

    override suspend fun commitRating(request: CommitRatingRequest): CommitRatingResult {
        delay(latencyMs)
        return mutex.withLock {
            if (request.commitId.backendId != id || request.card.backendId != id) {
                return@withLock CommitRatingResult.Rejected(AnkiError.SessionInvalid())
            }
            val previous = ledger[request.commitId]
            if (previous != null) {
                if (previous.request != request) return@withLock CommitRatingResult.Rejected(AnkiError.CommitConflict(request.card))
                // A known ACK or ambiguous response remains known even if the backend disappears.
                if (previous.result !is CommitRatingResult.RetryableFailure) return@withLock previous.result
            }
            if (session?.context?.studySessionId != request.commitId.studySessionId) {
                return@withLock CommitRatingResult.Rejected(AnkiError.SessionInvalid())
            }
            val turn = active
            if (turn == null || turn.turnId != request.commitId.turnId || turn.card.ref != request.card) {
                return@withLock CommitRatingResult.Rejected(AnkiError.StaleTurn())
            }
            if (previous == null && ledger.size >= maxLedgerEntries) {
                return@withLock CommitRatingResult.Rejected(AnkiError.QueryFailure("fake-ledger-full"))
            }
            val unavailable = usabilityError()
            val step = when {
                unavailable != null -> CommitStep(CommitRatingResult.RetryableFailure(unavailable))
                !capabilities.value.review -> CommitStep(CommitRatingResult.Rejected(unsupported("review")))
                else -> commits.removeFirstOrNull() ?: CommitStep(CommitRatingResult.Committed())
            }
            val applied = step.result is CommitRatingResult.Committed || step.appliedWhenAmbiguous
            ledger[request.commitId] = RecordedCommit(request, step.result, (previous?.attempts ?: 0) + 1,
                if (applied) 1 else 0)
            step.result
        }
    }

    private fun usabilityError(): AnkiError? = availability.value.unavailabilityError()
    private fun unsupported(action: String) = AnkiError.UnsupportedAction(action)

    companion object {
        val REVIEW_CAPABILITIES = AnkiCapabilities(review = true, deckListing = true, renderedCards = true,
            reviewIntervals = true)

        private fun coherent(state: AnkiAvailability, capabilities: AnkiCapabilities): AnkiAvailability =
            if (state is AnkiAvailability.Ready) AnkiAvailability.Ready(capabilities) else state

        private fun requireSupported(value: AnkiCapabilities) {
            require(!value.flags && !value.bury && !value.suspendCards && !value.editNotes &&
                !value.createNotes && !value.search && !value.media) { "Fake does not implement these features" }
        }
    }
}
