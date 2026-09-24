package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewTurn
import com.studyagent.client.core.anki.AnkiReviewTurnContent
import com.studyagent.client.core.anki.AnkiScheduledCard
import com.studyagent.client.core.anki.unavailabilityError
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.CommitPreparation
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.CommitRatingResult
import com.studyagent.client.core.anki.ReconcileCommitRequest
import com.studyagent.client.core.anki.ReconcileCommitResult
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.common.SystemAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * GATE 04 — real backend shell behind GATE 03's AnkiBackend interface (§22).
 *
 * Exposes:
 * - id = ANKIDROID_LOCAL (§23)
 * - availability (StateFlow)
 * - capabilities (StateFlow)
 * - refreshAvailability()
 *
 * GATE 05 implements [getDecks] / [getSelectedDeck] through [AnkiDroidDeckGateway].
 * GATE 06 implements [beginReview] / [nextCard] through [AnkiDroidReviewGateway], and owns the
 * one piece of runtime state that cannot live in the gateway: **the active review turn of the
 * active session** (§60/§61). GATE 07 implements [hydrateCardContent] through
 * [AnkiDroidCardGateway] — read-only, identity-verified, turn-agnostic (STEP 52). GATE 11
 * implements [prepareCommit] / [commitRating] / [reconcileCommit] through the single writer
 * [AnkiDroidRatingGateway] and [AnkiDroidRatingCommitter]; without a rating gateway they refuse
 * truthfully and `review` is not advertised.
 *
 * Architecture:
 * AnkiDroidGateway (health) + AnkiDroidDeckGateway (decks) + AnkiDroidReviewGateway (scheduler)
 *   + AnkiDroidCardGateway (card content)
 *   ↓
 * AnkiDroidBackend  (session ownership, one active turn per session)
 *   ↓
 * AnkiBackend (domain)
 *
 * No Cursor, ContentResolver, Uri or provider JSON escapes (§4/§10).
 * Deck, review and card-content operations are read-only (INV-ANKI-DECK-12, INV-ANKI-REV-07/15,
 * INV-ANKI-CARD-10): the scheduler selects the card, Study-Agent only reports what it selected
 * and what it contains.
 */
class AnkiDroidBackend(
    private val gateway: AnkiDroidGateway,
    private val scope: CoroutineScope,
    private val clock: AppClock = SystemAppClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val deckGateway: AnkiDroidDeckGateway,
    private val reviewGateway: AnkiDroidReviewGateway,
    private val cardGateway: AnkiDroidCardGateway,
    private val turnIds: ReviewTurnIdSource = SequentialReviewTurnIdSource(),
    /**
     * GATE 11 — the only writer. Absent (tests, older composition) = rating commits are refused
     * truthfully and the `review` capability is not advertised.
     */
    private val ratingGateway: AnkiDroidRatingGateway? = null
) : AnkiBackend {

    override val id: AnkiBackendId = AnkiBackendId.AnkiDroidLocal

    /** Distinguishes handles this instance issued from handles issued by a previous process. */
    private val instanceId: String = UUID.randomUUID().toString()

    // Single source of truth (§45): integration state internally, derived flows externally (§44)
    private val _integrationState = MutableStateFlow(gateway.currentState())

    // Derived flows — never three unrelated mutable stores
    override val availability: StateFlow<AnkiAvailability> = _integrationState
        .map { it.availability }
        .stateIn(scope, SharingStarted.Eagerly, _integrationState.value.availability)

    override val capabilities: StateFlow<AnkiCapabilities> = _integrationState
        .map { withRatingSupport(it.capabilities) }
        .stateIn(scope, SharingStarted.Eagerly, withRatingSupport(_integrationState.value.capabilities))

    /** GATE 11 — the full review loop (`review`) is only claimed when a rating writer is wired. */
    private fun withRatingSupport(capabilities: AnkiCapabilities): AnkiCapabilities =
        if (ratingGateway == null) capabilities.copy(review = false) else capabilities

    private val committer: AnkiDroidRatingCommitter? = ratingGateway?.let { AnkiDroidRatingCommitter(it, clock) }

    /** Full integration state for diagnostics and settings (internal but observable). */
    val integrationState: StateFlow<AnkiDroidIntegrationState> = _integrationState.asStateFlow()

    private val refreshMutex = Mutex()
    private var generation: Long = 0L
    private val publicationGuard = AnkiDroidHealthPublicationGuard()

    /**
     * GATE 06 — the scheduled-review serialization point (§63/§64/§161/§162).
     *
     * One lock covers "read the session, ask the scheduler, install the turn". That single span is
     * what makes three separate guarantees true at once, rather than three hopeful checks:
     *
     * - **one active turn** — a second caller waits, then observes the turn the first installed
     *   instead of asking the scheduler again (§63-§65);
     * - **no stale installation** — a result can only ever be installed into the record it was
     *   read for, because no other operation (a new session, an ended session, a health change)
     *   can interleave between the read and the install (§161);
     * - **one scheduler query per card** — double-taps, voice commands and reconnect callbacks
     *   converge instead of stampeding the provider (§143).
     *
     * Holding a mutex across a provider read is deliberate: this is the operation whose
     * interleaving is expensive (a second scheduler read, or a card presented to a session that no
     * longer exists), and it is never held by ordinary deck or health reads.
     */
    private val reviewMutex = Mutex()
    private var reviewGeneration: Long = 0L
    private var reviewRecords: MutableMap<String, ReviewSessionRecord> = mutableMapOf()
    private var lastReviewDiagnostics: AnkiReviewDiagnostics = AnkiReviewDiagnostics.NONE

    /**
     * GATE 07 — the turn-scoped rendered-card memo (STEP 80-§84).
     *
     * One slot, keyed by the card ref's stable key. Its only job is that two consumers asking
     * for the *same active card* share one provider read instead of stampeding it (STEP 80,
     * test S). It is a presentation cache, never collection truth (INV-ANKI-CARD-26): it is
     * dropped the moment a new turn is presented or a session ends (STEP 83), so a card edited
     * in AnkiDroid is re-read as authoritative content for any future turn
     * (INV-ANKI-CARD-27/§84). Nothing here is persisted; process death recovers by rehydrating
     * from a logical ref (STEP 85).
     */
    private var hydrationMemo: Pair<String, AnkiRenderedCard>? = null

    /**
     * Serializes hydration so concurrent consumers of the same card share one provider read
     * (STEP 80's single-flight rule: "duplicate or concurrent consumers must not cause repeated
     * full provider card reads"). The memo is re-checked after acquiring the lock, so the second
     * waiter observes the first one's answer instead of re-querying. Hydration is rare and
     * turn-scoped — one active card at a time — so one lock is the right shape here.
     */
    private val hydrationMutex = Mutex()

    init {
        AppLogger.i("AnkiDroidBackend", "AnkiDroidBackend created id=${id.stableId}")
    }

    override suspend fun refreshAvailability() {
        try {
            val requestId = publicationGuard.newRequest()
            val newState = refreshMutex.withLock {
                // Increment generation for stale protection
                generation += 1
                gateway.refreshIntegrationState()
            }

            // Stale protection: only publish if current
            publicationGuard.publishIfCurrent(requestId) {
                _integrationState.value = newState
            }

            if (publicationGuard.currentRequestId != requestId) {
                AppLogger.i("AnkiDroidBackend", "ANKI_HEALTH_STALE_DISCARDED backend gen=$requestId")
            }
        } catch (cancellation: CancellationException) {
            // Cancellation is never converted into domain failure (§33/§34/§79)
            throw cancellation
        } catch (throwable: Throwable) {
            AppLogger.w("AnkiDroidBackend", "ANKI_HEALTH_CHECK_FAILED ${throwable::class.java.simpleName}", throwable)
            // Do not overwrite state with failure unless we have no state — preserve last known
            // The gateway already produced a fault state; we just ensure it's visible
            val faultState = AnkiDroidIntegrationState(
                availability = AnkiAvailability.Fault(AnkiError.Unknown(cause = throwable::class.java.simpleName)),
                capabilities = AnkiCapabilities.NONE,
                apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN,
                metadata = null,
                lastCheckAtMs = clock.nowMillis(),
                latencyMs = null,
                lastError = AnkiError.Unknown(cause = throwable::class.java.simpleName),
                healthSnapshot = null,
                capabilityDetails = emptyList()
            )
            _integrationState.value = faultState
        }
    }

    /**
     * Read-only deck listing (GATE 05). `Success(emptyList())` is a real empty collection;
     * every other outcome is a typed failure (INV-ANKI-DECK-06).
     */
    override suspend fun getDecks(): AnkiResult<List<AnkiDeck>> {
        try {
            guardDeckRead()?.let { return AnkiResult.Failure(it) }
            val authority = currentAuthority()
                ?: return AnkiResult.Failure(AnkiError.QueryFailure(causeCategory = "authority-unknown"))
            return when (val listing = deckGateway.queryDecks(authority)) {
                is AnkiResult.Success -> AnkiResult.Success(listing.value.decks)
                is AnkiResult.Failure -> listing
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    override suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?> {
        try {
            guardDeckRead()?.let { return AnkiResult.Failure(it) }
            val authority = currentAuthority()
                ?: return AnkiResult.Failure(AnkiError.QueryFailure(causeCategory = "authority-unknown"))
            return deckGateway.querySelectedDeck(authority)
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    fun lastDeckQueryDiagnostics(): AnkiDeckQueryDiagnostics = deckGateway.lastListingDiagnostics()

    private fun guardDeckRead(): AnkiError? {
        val availability = _integrationState.value.availability
        availability.unavailabilityError()?.let { return it }
        if (!_integrationState.value.capabilities.deckListing) {
            return AnkiError.UnsupportedAction(action = "deckListing")
        }
        return null
    }

    private fun currentAuthority(): String? = _integrationState.value.metadata?.authority

    /**
     * GATE 07 — read-only card-content hydration (STEP 09-§13/§43-§50).
     *
     * The gateway owns the provider conversation and the identity verification (STEP 54); this
     * method owns only the session-side guards and the turn-scoped memo (STEP 80-§84). It never
     * mutates Anki, never touches the scheduler and never installs the result into a turn
     * (STEP 51/§52) — `AnkiCardHydration.attach` does the identity-verified association, and
     * GATE 10 decides whether the turn is still current (INV-ANKI-CARD-30).
     */
    override suspend fun hydrateCardContent(card: AnkiCardRef): AnkiResult<AnkiRenderedCard> {
        try {
            // Backend loss / permission loss / closed collection surface as the typed family —
            // never a silent fallback to another backend (STEP 48/§49).
            usabilityError()?.let { return AnkiResult.Failure(it) }
            if (!_integrationState.value.capabilities.renderedCards) {
                return AnkiResult.Failure(AnkiError.UnsupportedAction(action = "renderedCards"))
            }
            if (card.backendId != id) {
                return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "card_ref_foreign_backend"))
            }
            return hydrationMutex.withLock {
                hydrationMemo?.let { (key, cached) ->
                    if (key == card.stableKey) return@withLock AnkiResult.Success(cached)
                }
                val authority = currentAuthority()
                    ?: return@withLock AnkiResult.Failure(AnkiError.QueryFailure(causeCategory = "authority-unknown"))
                when (val result = cardGateway.queryCard(authority, card)) {
                    is AnkiResult.Failure -> result
                    is AnkiResult.Success -> {
                        hydrationMemo = card.stableKey to result.value
                        result
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            // Cancellation is never translated into an ordinary card failure (INV-ANKI-CARD-29).
            throw cancellation
        } catch (throwable: Throwable) {
            AppLogger.w(TAG, "ANKI_CARD_HYDRATION_FAILED error=${throwable::class.java.simpleName}")
            return AnkiResult.Failure(AnkiError.Unknown(cause = throwable::class.java.simpleName))
        }
    }

    /** Content-free card-hydration facts for settings/diagnostics (STEP 87). Never card text. */
    fun cardGatewayDiagnostics(): AnkiDroidCardQueryDiagnostics = cardGateway.lastQueryDiagnostics()

    /**
     * Opens a scheduled-review session bound to exactly one backend, one collection and one deck
     * (§9/§10/§130). Nothing is written to AnkiDroid (INV-ANKI-REV-15); the deck is only *read* to
     * prove it still exists, because the `schedule` endpoint cannot distinguish "that deck is
     * gone" from "nothing is due" and Study-Agent must not guess (§46).
     */
    override suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession> {
        try {
            return reviewMutex.withLock { beginReviewLocked(request) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AppLogger.w(TAG, "ANKI_REVIEW_SESSION_START_FAILED ${throwable::class.java.simpleName}")
            return AnkiResult.Failure(
                AnkiError.Unknown(cause = throwable::class.java.simpleName)
            )
        }
    }

    private suspend fun beginReviewLocked(request: BeginReviewRequest): AnkiResult<AnkiReviewSession> {
        val context = request.context

        // A foreign backend id is never reinterpreted as this one (§8).
        if (context.backendId != id) return AnkiResult.Failure(AnkiError.SessionInvalid())

        usabilityError()?.let { return AnkiResult.Failure(it) }
        // GATE 06 gates on scheduledReview, not on the full review loop: this backend can ask the
        // scheduler for a card and cannot yet commit a rating, and both of those are true at once
        // (§75/§146). Gating on `review` here would make the gate's own feature unreachable.
        if (!_integrationState.value.capabilities.scheduledReview) {
            return AnkiResult.Failure(AnkiError.UnsupportedAction(action = "scheduledReview"))
        }

        // One session, one deck (§130/§131): "whichever deck AnkiDroid happens to have selected"
        // is not an acceptable review context, so a null deck ref is refused rather than resolved
        // by guessing. The caller resolves AnkiDroid's selected deck through getSelectedDeck().
        val deckRef = context.deckRef
            ?: return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "review_requires_deck_ref"))

        request.limit?.let { limit ->
            if (limit <= 0 || limit > MAX_SESSION_CARD_LIMIT) {
                return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "review_limit_out_of_range"))
            }
        }

        // Idempotent per user study session (§85): a repeat of the identical request returns the
        // handle that is already open; a *different* request for the same session is refused
        // rather than silently replacing the review context (§128/§129).
        reviewRecords.values
            .firstOrNull { it.session.context.studySessionId == context.studySessionId }
            ?.let { existing ->
                return if (existing.request == request) {
                    AnkiResult.Success(existing.session)
                } else {
                    AnkiResult.Failure(AnkiError.SessionInvalid())
                }
            }

        val authority = currentAuthority()
            ?: return AnkiResult.Failure(AnkiError.QueryFailure(causeCategory = "authority-unknown"))

        // Confirm the deck belongs to the collection as it is right now (§45/§46/§77).
        when (val listing = deckGateway.queryDecks(authority)) {
            is AnkiResult.Failure -> return listing
            is AnkiResult.Success -> {
                if (listing.value.decks.none { it.ref == deckRef }) {
                    return AnkiResult.Failure(AnkiError.DeckNotFound(deckRef))
                }
            }
        }

        val session = AnkiReviewSession(
            context = context,
            backendSessionRef = "$instanceId:review:${++reviewGeneration}"
        )
        reviewRecords[session.backendSessionRef] = ReviewSessionRecord(
            session = session,
            request = request,
            deckRef = deckRef,
            limit = request.limit
        )
        recordDiagnostics(status = "STARTED", deckRef = deckRef, session = session)
        AppLogger.i(
            TAG,
            "ANKI_REVIEW_SESSION_STARTED deck=${deckRef.deckId} limit=${request.limit ?: "none"}"
        )
        return AnkiResult.Success(session)
    }

    /**
     * The next card **AnkiDroid's scheduler** selects (§4/INV-ANKI-REV-01/02).
     *
     * Ordering is never computed here: no due comparison, no FSRS, no learning steps, no local
     * queue (§38). The gateway asks for exactly one row and this method presents it once.
     *
     * While a turn is unresolved this returns *that same turn* instead of asking again
     * (INV-ANKI-REV-03/08, §63-§66/§92/§93). That matters at Gate 06 exactly as much as it will
     * after Gate 11: with no commit path yet, the correct answer to a second `nextCard()` is the
     * card the user is already looking at, not a second card.
     */
    override suspend fun nextCard(session: AnkiReviewSession): NextCardResult {
        try {
            return reviewMutex.withLock { nextCardLocked(session) }
        } catch (cancellation: CancellationException) {
            // Cancellation is never converted into a domain failure (§140/INV-ANKI-REV-13).
            throw cancellation
        } catch (throwable: Throwable) {
            AppLogger.w(TAG, "ANKI_NEXT_CARD_FAILED ${throwable::class.java.simpleName}")
            return NextCardResult.Failure(AnkiError.Unknown(cause = throwable::class.java.simpleName))
        }
    }

    private suspend fun nextCardLocked(session: AnkiReviewSession): NextCardResult {
        // A handle this backend did not issue, or one that a later session superseded, is refused
        // rather than reinterpreted (§161/§162).
        val record = reviewRecords[session.backendSessionRef]
        if (record == null || record.session != session) {
            return NextCardResult.Failure(AnkiError.SessionInvalid())
        }

        // AnkiDroid disabled, uninstalled, unpermitted, or its collection closed mid-session:
        // report the typed reason and never fall back to another backend (§47-§49/§132).
        usabilityError()?.let { return NextCardResult.BackendUnavailable(it) }
        if (!_integrationState.value.capabilities.scheduledReview) {
            return NextCardResult.Failure(AnkiError.UnsupportedAction(action = "scheduledReview"))
        }

        record.activeTurn?.let { turn ->
            // GATE 11 — an unresolved turn whose commit is AMBIGUOUS or rejected blocks progression
            // with a typed failure; it is never silently replaced by the next card.
            return when (record.commits[turn.commitId]?.result) {
                is CommitRatingResult.Ambiguous, is CommitRatingResult.Rejected ->
                    NextCardResult.Failure(AnkiError.CommitConflict(turn.cardRef))
                else -> NextCardResult.Card(turn)
            }
        }
        if (record.schedulerExhausted) return NextCardResult.Finished

        val authority = currentAuthority()
            ?: return NextCardResult.Failure(AnkiError.QueryFailure(causeCategory = "authority-unknown"))

        val query = reviewGateway.queryNextScheduledCard(
            authority = authority,
            deckRef = record.deckRef,
            limit = SINGLE_CARD_QUERY_LIMIT
        )

        val outcome = when (query) {
            is AnkiResult.Failure -> return NextCardResult.Failure(query.error)
            is AnkiResult.Success -> query.value
        }

        return when (outcome) {
            is AnkiDroidScheduledCardQuery.NoCardDue -> resolveEmptyAnswer(record)
            is AnkiDroidScheduledCardQuery.Scheduled -> present(record, outcome.card)
        }
    }

    /**
     * The `schedule` endpoint answers an unknown deck id with the same empty cursor it uses for an
     * exhausted deck, so "nothing due" and "that deck is gone" are not distinguishable from the
     * card read alone. Resolving it against the deck list is the only honest way to keep §46
     * (deleted deck → typed failure) and §32 (exhausted deck → a valid end state) apart — and it
     * costs one query, only at the moment a session would otherwise finish.
     */
    private suspend fun resolveEmptyAnswer(record: ReviewSessionRecord): NextCardResult {
        val authority = currentAuthority()
            ?: return NextCardResult.Failure(AnkiError.QueryFailure(causeCategory = "authority-unknown"))
        when (val listing = deckGateway.queryDecks(authority)) {
            is AnkiResult.Failure -> return NextCardResult.Failure(listing.error)
            is AnkiResult.Success -> {
                if (listing.value.decks.none { it.ref == record.deckRef }) {
                    AppLogger.w(TAG, "ANKI_REVIEW_DECK_GONE deck=${record.deckRef.deckId}")
                    reviewRecords.remove(record.session.backendSessionRef)
                    return NextCardResult.Failure(AnkiError.DeckNotFound(record.deckRef))
                }
            }
        }
        record.schedulerExhausted = true
        recordDiagnostics(status = "FINISHED", deckRef = record.deckRef, session = record.session)
        AppLogger.i(TAG, "ANKI_REVIEW_SESSION_FINISHED deck=${record.deckRef.deckId} presented=${record.presentedCount}")
        return NextCardResult.Finished
    }

    /** Creates the presentation identity. Study-Agent owns turns; AnkiDroid owns cards (§57). */
    private fun present(record: ReviewSessionRecord, card: AnkiScheduledCard): NextCardResult {
        val turn = AnkiReviewTurn(
            turnId = turnIds.next(),
            studySessionId = record.session.context.studySessionId,
            content = AnkiReviewTurnContent.Scheduled(card),
            position = record.presentedCount + 1,
            remaining = null
        )
        record.activeTurn = turn
        record.presentedCount += 1
        // STEP 83 — a new presentation resolves the old turn: its memoized content must not
        // survive into the next one (INV-ANKI-CARD-26/§84).
        hydrationMemo = null
        recordDiagnostics(status = "CARD_AVAILABLE", deckRef = record.deckRef, session = record.session, turn = turn)
        return NextCardResult.Card(turn)
    }

    /**
     * Session-scoped progress for the future coordinator (§33/§34), never a scheduling decision.
     *
     * The distinction between "the scheduler ran out" and "the user's own session limit was
     * reached" is modelled here as data rather than as a second `NextCardResult` variant: with no
     * commit path yet, a limit cannot become reachable, and inventing an unreachable result
     * variant would be untestable. The count is Study-Agent's; the *cards* remain AnkiDroid's.
     */
    fun reviewProgress(session: AnkiReviewSession): AnkiReviewSessionProgress? =
        reviewRecords[session.backendSessionRef]?.let { record ->
            AnkiReviewSessionProgress(
                backendSessionRef = record.session.backendSessionRef,
                deckRef = record.deckRef,
                limit = record.limit,
                presentedTurnCount = record.presentedCount,
                hasActiveTurn = record.activeTurn != null,
                schedulerExhausted = record.schedulerExhausted
            )
        }

    /**
     * GATE 06 §86 — close a review session and forget its runtime record.
     *
     * Deliberately a no-op towards AnkiDroid: ending a Study-Agent session must not mutate a card,
     * change a listing or touch the scheduler. It forgets a handle, and a forgotten handle is then
     * refused like any other unknown one — which is also the observable proof that recovery after
     * process/backend recreation cannot resurrect a stale turn (§97/§99/§161).
     */
    override suspend fun endReview(session: AnkiReviewSession): Boolean = reviewMutex.withLock {
        val removed = reviewRecords.remove(session.backendSessionRef) != null
        if (removed) {
            hydrationMemo = null // STEP 83 — session end invalidates the turn-scoped cache.
            AppLogger.i(TAG, "ANKI_REVIEW_SESSION_ENDED ref=${session.backendSessionRef}")
        }
        removed
    }

    /** Content-free diagnostics for settings and the debug harness (§82/§84). Never card text. */
    fun reviewDiagnostics(): AnkiReviewDiagnostics = lastReviewDiagnostics

    fun reviewGatewayDiagnostics(): AnkiDroidReviewQueryDiagnostics = reviewGateway.lastQueryDiagnostics()

    private fun recordDiagnostics(
        status: String,
        deckRef: AnkiDeckRef,
        session: AnkiReviewSession,
        turn: AnkiReviewTurn? = null
    ) {
        lastReviewDiagnostics = AnkiReviewDiagnostics(
            lastStatus = status,
            backendId = id.stableId,
            sessionRef = session.backendSessionRef,
            deckRef = deckRef,
            turnId = turn?.turnId?.value,
            cardRef = turn?.cardRef,
            buttonCount = turn?.let { buttonCountOf(it) },
            lastAtMs = clock.nowMillis()
        )
    }

    /**
     * GATE 11 — read-only preparation: validates the commit against the live session and captures
     * the card's stored review counters as the durable baseline (STEP 58).
     */
    override suspend fun prepareCommit(request: CommitRatingRequest): CommitPreparation {
        try {
            return reviewMutex.withLock {
                validateCommit(request)?.let { return@withLock CommitPreparation.Refused(it, retryable = false) }
                val committer = this.committer
                    ?: return@withLock CommitPreparation.Refused(AnkiError.UnsupportedAction("ratingCommit"), retryable = false)
                usabilityError()?.let { return@withLock CommitPreparation.Refused(it, retryable = true) }
                val authority = currentAuthority()
                    ?: return@withLock CommitPreparation.Refused(AnkiError.QueryFailure("authority-unknown"), retryable = true)
                committer.prepare(authority, request.card)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            return CommitPreparation.Refused(AnkiError.Unknown(cause = throwable::class.java.simpleName), retryable = true)
        }
    }

    /**
     * GATE 11 — the rating mutation (STEP 18-§28).
     *
     * One lock spans validation, the provider protocol and the turn update, so a duplicate call
     * waits and then answers from [ReviewSessionRecord.commits] instead of reaching the provider
     * again, and `nextCard` can never interleave with a commit of the active turn. The protocol
     * itself — preconditions, the single provider update, evidence-based classification — lives in
     * [AnkiDroidRatingCommitter]. Only `Committed` releases the turn, so the next `nextCard` asks
     * the scheduler; anything escaping after the lock is AMBIGUOUS, never a failure.
     */
    override suspend fun commitRating(request: CommitRatingRequest): CommitRatingResult {
        try {
            return reviewMutex.withLock { commitLocked(request) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AppLogger.w(TAG, "ANKI_COMMIT_UNEXPECTED error=${throwable::class.java.simpleName}")
            return CommitRatingResult.Ambiguous(AnkiError.Unknown(cause = throwable::class.java.simpleName))
        }
    }

    private suspend fun commitLocked(request: CommitRatingRequest): CommitRatingResult {
        if (request.commitId.backendId != id || request.card.backendId != id) {
            return CommitRatingResult.Rejected(AnkiError.SessionInvalid())
        }
        val record = sessionRecordFor(request.commitId.studySessionId)
            ?: return CommitRatingResult.Rejected(AnkiError.SessionInvalid())
        record.commits[request.commitId]?.let { previous ->
            if (!previous.samePayload(request)) return CommitRatingResult.Rejected(AnkiError.CommitConflict(request.card))
            // Known outcomes are final here; only a proven-not-applied attempt may be sent again.
            if (previous.result !is CommitRatingResult.RetryableFailure) return previous.result
        }
        validateCommit(request)?.let { return CommitRatingResult.Rejected(it) }
        val committer = this.committer
            ?: return CommitRatingResult.Rejected(AnkiError.UnsupportedAction("ratingCommit"))
        usabilityError()?.let { return CommitRatingResult.RetryableFailure(it) }
        val authority = currentAuthority()
            ?: return CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("authority-unknown"))
        val deckId = record.deckRef.deckId.toLongOrNull()
            ?: return CommitRatingResult.Rejected(AnkiError.InvalidRequest("deck_id_unmappable"))

        val result = committer.commit(authority, deckId, request)
        record.rememberCommit(request.commitId, BackendCommitRecord(request.card, request.rating, result))
        if (result is CommitRatingResult.Committed) {
            record.activeTurn = null // resolved: the next nextCard() asks the scheduler again
            hydrationMemo = null
        }
        AppLogger.i(TAG, "ANKI_COMMIT_RESULT result=${result::class.simpleName}")
        return result
    }

    /**
     * GATE 11 — reconciliation from the durable baseline. Works without the original session (after
     * process death); when the session is still open its turn follows the decision.
     */
    override suspend fun reconcileCommit(request: ReconcileCommitRequest): ReconcileCommitResult {
        try {
            return reviewMutex.withLock {
                if (request.commitId.backendId != id) return@withLock ReconcileCommitResult.Unsupported("foreign_backend")
                val committer = this.committer ?: return@withLock ReconcileCommitResult.Unsupported()
                usabilityError()?.let { return@withLock ReconcileCommitResult.Unavailable(it) }
                val authority = currentAuthority()
                    ?: return@withLock ReconcileCommitResult.Unavailable(AnkiError.QueryFailure("authority-unknown"))
                val result = committer.reconcile(authority, request)
                sessionRecordFor(request.commitId.studySessionId)?.let { record ->
                    val turn = record.activeTurn
                    if (turn != null && turn.commitId == request.commitId) {
                        val decided: CommitRatingResult? = when (result) {
                            is ReconcileCommitResult.Applied -> CommitRatingResult.Committed()
                            is ReconcileCommitResult.NotApplied ->
                                if (result.safeToRetry) CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("reconciled_not_applied"))
                                else CommitRatingResult.Rejected(AnkiError.CommitConflict(turn.cardRef))
                            else -> null
                        }
                        if (decided != null) {
                            record.rememberCommit(request.commitId, BackendCommitRecord(request.card, request.rating, decided))
                            if (decided is CommitRatingResult.Committed) {
                                record.activeTurn = null
                                hydrationMemo = null
                            }
                        }
                    }
                }
                result
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            return ReconcileCommitResult.StillAmbiguous("reconcile_failed_${throwable::class.java.simpleName}")
        }
    }

    /** Session + turn + scheduler-offered rating. `null` = valid. Never touches the provider. */
    private fun validateCommit(request: CommitRatingRequest): AnkiError? {
        if (request.commitId.backendId != id || request.card.backendId != id) return AnkiError.SessionInvalid()
        val record = sessionRecordFor(request.commitId.studySessionId) ?: return AnkiError.SessionInvalid()
        val turn = record.activeTurn
        if (turn == null || turn.turnId != request.commitId.turnId || turn.cardRef != request.card) {
            return AnkiError.StaleTurn()
        }
        val options = turn.ratingOptions
        if (options !is AnkiRatingOptions.Known || !options.supports(request.rating)) {
            return AnkiError.InvalidRequest("rating_not_offered_by_scheduler")
        }
        return null
    }

    private fun sessionRecordFor(studySessionId: String): ReviewSessionRecord? =
        reviewRecords.values.firstOrNull { it.session.context.studySessionId == studySessionId }

    private fun usabilityError(): AnkiError? =
        _integrationState.value.availability.unavailabilityError()

    private fun buttonCountOf(turn: AnkiReviewTurn): Int? = buttonCountOf(turn.scheduledCard)

    private fun buttonCountOf(card: AnkiScheduledCard): Int = when (val options = card.ratingOptions) {
        is AnkiRatingOptions.Known -> options.buttonCount
        is AnkiRatingOptions.Unmapped -> options.buttonCount
    }

    private companion object {
        const val TAG = "AnkiDroidBackend"

        /**
         * GATE 06 asks for exactly one card (§13/§40). Preloading a queue would hold scheduler
         * state that the next rating invalidates, and §15 forbids assuming a prefetched card is
         * still next.
         */
        const val SINGLE_CARD_QUERY_LIMIT = 1

        /** AnkiDroid owns the real daily limits; this only bounds the user's own session (§78). */
        const val MAX_SESSION_CARD_LIMIT = 500
    }

    /**
     * Updates integration state from external health repository (for lifecycle refresh).
     * Used when AnkiDroidHealthRepository produces a new snapshot.
     */
    suspend fun updateFromHealthSnapshot(snapshot: AnkiDroidHealthSnapshot) {
        try {
            val capResult = try {
                // Capability probe based on detection
                // This is read-only, no mutation (§15)
                DefaultAnkiDroidCapabilityProbe(dispatchers, clock::nowMillis).probe(snapshot.detection)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                AnkiDroidCapabilityProbeResult(
                    implemented = AnkiCapabilities.NONE,
                    apiReport = AnkiDroidApiCapabilityReport.UNKNOWN,
                    details = emptyList(),
                    probedAtMs = clock.nowMillis(),
                    latencyMs = 0L
                )
            }

            val metadata = AnkiDroidMetadata(
                packageName = snapshot.detection.packageName,
                providerPackage = snapshot.detection.providerFacts?.providerPackage ?: snapshot.detection.packageName,
                authority = snapshot.detection.authority,
                endpointLabel = snapshot.detection.endpointLabel,
                providerSpec = snapshot.detection.providerSpec,
                providerSpecSource = snapshot.detection.providerFacts?.providerSpecSource,
                packageVersion = null, // will be filled by gateway if available
                providerReachable = snapshot.detection.providerAvailable,
                permissionGranted = snapshot.detection.permissionGranted,
                collectionReady = snapshot.detection.collectionReady,
                checkedAuthorities = snapshot.detection.checkedAuthorities
            )

            val lastError: AnkiError? = when (val avail = snapshot.detection.availability) {
                is AnkiAvailability.Fault -> avail.error
                is AnkiAvailability.Unsupported -> AnkiError.UnsupportedAction("backend_api")
                is AnkiAvailability.ProviderUnavailable -> AnkiError.ProviderUnavailable(detail = avail.detail)
                is AnkiAvailability.PermissionRequired -> AnkiError.PermissionRequired()
                AnkiAvailability.CollectionNotInitialized -> AnkiError.CollectionUnavailable()
                is AnkiAvailability.TemporarilyUnavailable -> AnkiError.QueryFailure("temporarily_unavailable")
                else -> snapshot.detection.failure?.let { AnkiDroidErrorMapper.mapFailure(it, "health_repo_sync") }
            }

            val newState = AnkiDroidIntegrationState(
                availability = snapshot.detection.availability,
                capabilities = capResult.implemented,
                apiCapabilities = capResult.apiReport,
                metadata = metadata,
                lastCheckAtMs = snapshot.checkedAtEpochMs,
                latencyMs = snapshot.durationMs,
                lastError = lastError,
                healthSnapshot = snapshot,
                capabilityDetails = capResult.details
            )

            _integrationState.value = newState
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }
}
