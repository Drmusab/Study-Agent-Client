package com.studyagent.client.core.anki

import com.studyagent.client.core.anki.edit.BackendNoteMutationRequest
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationRequest
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationResult
import com.studyagent.client.core.anki.edit.NoteMutationSemantics
import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend-neutral Anki boundary. Implementations translate framework/protocol failures into
 * typed outcomes, but propagate coroutine cancellation. No AI, voice or Study UI ownership.
 * Scheduling belongs to Anki. No real production implementation is registered in GATE 03.
 */
interface AnkiBackend {
    val id: AnkiBackendId
    val availability: StateFlow<AnkiAvailability>
    val capabilities: StateFlow<AnkiCapabilities>

    /** Effect semantics, not UI capability. Unverified adapters cannot claim safe replay. */
    val commitSemantics: CommitSemantics get() = CommitSemantics.UNVERIFIED

    suspend fun refreshAvailability()

    /**
     * Read-only deck listing (GATE 05). `Success(emptyList())` means the collection really has
     * no decks; every other outcome is a typed failure — never an empty list standing in for one
     * (INV-ANKI-DECK-06). Decks carry backend-qualified identity, the original full name, and
     * nullable counts/filtered flags (`null` = the backend did not say, never a guessed zero).
     */
    suspend fun getDecks(): AnkiResult<List<AnkiDeck>>

    /**
     * The deck the *backend* currently has selected (AnkiDroid's own "current deck"), if the
     * backend exposes that notion. `Success(null)` = exposed but not determinable right now.
     * This is informational: it is not Study-Agent's session deck and is never written (§14/§43).
     */
    suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?>

    /**
     * GATE 14 — read-only summary of one deck, addressed by stable deck ID (never by name).
     *
     * The default is derived from the *same single batched listing read* as [getDecks] — one
     * backend call, no N+1 fan-out (INV-14-17): a backend whose transport answers a per-deck
     * summary more cheaply may override it, but no implementation may turn this into one query
     * per count. Counts keep their nullability semantics (`null` = the backend did not say,
     * never a guessed zero — INV-14-03); [AnkiDeckSummary.totalCards] stays `null` unless the
     * backend genuinely reports a total, and [AnkiDeckSummary.isSelectedByBackend] stays `null`
     * here because this read does not ask for the backend's selected deck.
     *
     * Outcomes: [AnkiResult.Success] with the summary; [AnkiError.DeckNotFound] when the listing
     * succeeded but carries no deck with this identity; every listing failure propagates as-is
     * (typed availability/permission/query errors — never remapped to an empty or zero summary).
     * Read-only: no scheduler mutation, no review session, no write (INV-14-06).
     */
    suspend fun getDeckSummary(deckId: String): AnkiResult<AnkiDeckSummary> {
        if (deckId.isBlank()) return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "blank_deck_id"))
        return when (val listing = getDecks()) {
            is AnkiResult.Failure -> AnkiResult.Failure(listing.error)
            is AnkiResult.Success -> {
                val deck = listing.value.firstOrNull { it.ref.backendId == id && it.ref.deckId == deckId }
                    ?: return AnkiResult.Failure(AnkiError.DeckNotFound(AnkiDeckRef(id, deckId)))
                AnkiResult.Success(
                    AnkiDeckSummary(
                        deck = deck,
                        counts = deck.counts,
                        isSelectedByBackend = null,
                        isFiltered = deck.isFiltered
                    )
                )
            }
        }
    }

    /**
     * GATE 15 — the single, read-only card-browser entry point (§1):
     * `browseCards(AnkiCardQuery): AnkiCardPage`. There is deliberately no `searchCards`,
     * `filterCards`, `sortCards`, `browseDeckCards` or `getCardsPage`: the query object carries
     * scope, text, filters, sort and bounded paging so every backend honours one set of semantics.
     *
     * The backend must, in this order (§63): check availability and the collection, apply
     * [AnkiCardQuery.normalized], reject a structurally invalid query with [AnkiError.InvalidQuery],
     * reject an unsupported requested feature with [AnkiError.UnsupportedQueryFeature], reject a
     * cursor that does not belong to this exact query with [AnkiError.InvalidCursor], execute one
     * collection-wide read, map identities (failing with [AnkiError.DataIntegrityFailure] rather
     * than inventing one) and return one bounded [AnkiCardPage].
     *
     * Forbidden everywhere in this call: rating, burying, suspending, flagging, note edits, deck
     * changes, sync, marking viewed or advancing the scheduler (§70), and any local
     * "load a page then filter/sort it in the client" fallback (§64/§65). The call is a read: it
     * may be retried safely (§53) and must cooperate with coroutine cancellation, which is never
     * surfaced as a query error (§54/INV-15-Q19).
     *
     * The default is deliberately unsupported, so adding the contract never makes an existing
     * adapter look browser-capable. Implementations advertise exactly what they support in
     * [AnkiCapabilities.cardBrowser] and reject anything else explicitly.
     */
    suspend fun browseCards(query: AnkiCardQuery): AnkiResult<AnkiCardPage> =
        AnkiResult.Failure(AnkiError.UnsupportedQueryFeature(feature = "card_browser"))

    /**
     * Open a scheduled-review session bound immutably to this backend, one collection and one
     * deck (§10/§130). The deck is validated against the live collection before the session
     * exists, so a stale reference fails here rather than mid-session (§45/§46).
     *
     * Idempotent per user study session (§85): repeating the identical request returns the same
     * handle; a *different* request for a session that is already open is refused instead of
     * silently replacing the review context (§128/§129).
     */
    suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession>

    /**
     * The next card **the backend's scheduler** currently wants reviewed — not the first card of
     * the deck, not a locally ordered due list (INV-ANKI-REV-01/02).
     *
     * One session has at most one active uncommitted turn (INV-ANKI-REV-03): while a turn is
     * unresolved this returns *that* turn again rather than asking the scheduler for another card,
     * so a UI double tap, a voice command and a reconnect callback cannot produce two active
     * presentations (§63-§66/§92/§93). A retry after a read that changed nothing is therefore
     * safe and returns the same uncommitted turn.
     *
     * `Finished` is a valid end state, not an error: it means the scheduler has nothing more for
     * this session. It is never conflated with a failed or unavailable backend
     * (INV-ANKI-REV-06, §31/§32/§74). An ambiguous or rejected commit blocks progression with a
     * typed failure until recovery decides otherwise.
     */
    suspend fun nextCard(session: AnkiReviewSession): NextCardResult

    /**
     * GATE 07 — read-only hydration of one *known* card identity into backend-neutral normalized
     * content (STEP 09/§10/§43).
     *
     * The input is a domain reference — never a `Cursor`, `Uri`, selection string or
     * `ContentResolver` (INV-ANKI-CARD-11/§10). The output is an [AnkiRenderedCard] carrying the
     * three separated content channels (visual / speech / evaluation — INV-ANKI-CARD-05) plus
     * lenient optional metadata, or a typed failure: [AnkiError.CardNotFound] (deleted between
     * scheduling and hydration — never a blank card, §44), [AnkiError.StaleCardReference] (the
     * answer is not the scheduled card — §47/§54), [AnkiError.MalformedResponse] (identity or
     * content cannot be trusted — §74/INV-ANKI-CARD-15), and the ordinary availability family
     * ([AnkiError.PermissionRequired], [AnkiError.BackendUnavailable],
     * [AnkiError.CollectionUnavailable], … — §48/§49).
     *
     * Read-only and idempotent (INV-ANKI-CARD-10/§25): it performs no rating, no edit and no
     * scheduler mutation, and repeating it returns equivalent *current* content (§45/§50) — the
     * latest authoritative state if the card was edited in AnkiDroid in between. It never
     * attaches content to a turn; `AnkiCardHydration.attach` owns the identity-verified
     * association (STEP 52/§53).
     */
    suspend fun hydrateCardContent(card: AnkiCardRef): AnkiResult<AnkiRenderedCard>

    /**
     * GATE 16 — the single, deep, read-only card-details entry point (CHECKPOINT 03):
     * `getCardDetails(cardRef): AnkiCardDetails`. There is deliberately no `getNoteDetails`,
     * `getCardFields`, `getCardScheduling` or `getCardMetadata`: one call returns one coherent
     * [AnkiCardDetails] so the UI never assembles a card from several provider APIs and can never
     * mix rows from two lookups.
     *
     * Lookup is **exact** (INV-16-11/12): the backend resolves this one card identity — never a
     * collection-wide card listing filtered client-side, never a question/deck/position search.
     * Supporting reads for the same card's note, note type or deck are permitted; scanning other
     * cards is not.
     *
     * Outcomes: [AnkiResult.Success] with the details snapshot; [AnkiError.CardNotFound] when the
     * card no longer exists (never a same-note sibling, never stale browser content — GATE 16 §23);
     * [AnkiError.DataIntegrityFailure] (or the project-equivalent [AnkiError.MalformedResponse])
     * when the card exists but its note relationship cannot be trusted (GATE 16 §24 — fields are
     * never fabricated); [AnkiError.InvalidRequest] for a reference belonging to another backend;
     * the ordinary availability family otherwise. A backend that cannot serve details at all
     * answers [AnkiError.UnsupportedAction] and advertises `AnkiCapabilities.cardDetails = false`.
     *
     * Read-only and idempotent (INV-16-01): no rating, no edit, no flag/tag/deck change, no
     * scheduler mutation, no session advancement (INV-16-18/19). Repeating the call re-fetches the
     * *current* state. Optional metadata the backend cannot expose stays `null` (INV-16-10) —
     * unknown is never zero. The reference is never reinterpreted against another backend or
     * collection (INV-16-15).
     */
    suspend fun getCardDetails(cardRef: AnkiCardRef): AnkiResult<AnkiCardDetails> =
        AnkiResult.Failure(AnkiError.UnsupportedAction(action = "card_details"))

    /**
     * GATE 11 — read-only preparation of one rating transaction, called *before* the ledger marks
     * the commit SUBMITTING.
     *
     * A backend may return [CommitPreparation.Ready] with opaque, content-free baseline
     * [ReviewCommitEvidence] (for AnkiDroid: card counters). This snapshot is NOT a commit receipt:
     * it only assists a backend with an independent commit-correlated status API. It is persisted
     * with PREPARED for recovery. [CommitPreparation.Refused] is a pre-mutation refusal.
     *
     * The default is "no evidence concept": commits still work, but reconciliation stays
     * [ReconcileCommitResult.Unsupported] — honest, never fabricated certainty.
     */
    suspend fun prepareCommit(request: CommitRatingRequest): CommitPreparation = CommitPreparation.Ready(null)

    /**
     * Same commit ID and payload must not mutate twice; different payload is a conflict.
     * Ambiguous writes block progression and blind resubmission until reconciled.
     * This interface supplies correlation, NOT a claim of distributed exactly-once delivery.
     *
     * GATE 11B result contract (see [BackendCommitResult]): [BackendCommitResult.ConfirmedCommitted]
     * only when the backend has proof the scheduler applied the rating;
     * [BackendCommitResult.ConfirmedNotCommitted] only when it is proven NOT applied — the backend
     * never decides whether a retry is *offered* (GATE 11B §10: that is the coordinator's job, from
     * the durable status); everything uncertain — a timeout, a lost response, an unclassified
     * exception after dispatch — is [BackendCommitResult.OutcomeUnknown]. "No exception" is not
     * proof of success.
     */
    suspend fun commitRating(request: CommitRatingRequest): BackendCommitResult

    /**
     * Transaction executor's durable boundary. [mutationEntry] MUST be called once, after any
     * read-only preflight but immediately BEFORE invoking the real scheduler mutation. A backend
     * must not invoke that mutation if this callback returns false. If it returns before calling
     * the callback it certifies that no scheduler mutation was dispatched by this attempt.
     * The default has no separate preflight: it writes the marker before [commitRating].
     * AnkiDroid overrides this so its queue/deck checks stay on the PREPARED side of the boundary.
     */
    suspend fun commitRating(request: CommitRatingRequest, mutationEntry: suspend () -> Boolean): BackendCommitResult {
        if (!mutationEntry()) return BackendCommitResult.ConfirmedNotCommitted(AnkiError.CommitLedgerUnavailable())
        return commitRating(request)
    }

    /**
     * GATE 11D §11 — what this backend can *authoritatively* prove about a commit whose response
     * was lost, as a three-state capability.
     *
     * The default derives from the backend's frozen [commitSemantics]; a backend overrides it when
     * its observable surface is more specific (for example [ReconciliationSupport.PARTIAL]). The
     * [AnkiReviewCommitReconciler] caps a transaction's frozen promise by this live capability —
     * a capability can only shrink, never grow.
     */
    fun reconciliationSupport(): ReconciliationSupport =
        if (commitSemantics.supportsAuthoritativeReconciliation) ReconciliationSupport.SUPPORTED
        else ReconciliationSupport.UNSUPPORTED

    /**
     * GATE 11 — decide an AMBIGUOUS commit from backend-observable evidence, *without* mutating.
     *
     * Implemented only where the backend exposes enough state to decide; the default answers
     * [ReconcileCommitResult.Unsupported], which keeps the commit AMBIGUOUS. Implementations must
     * not report `Applied` from a change that cannot be attributed to this transaction.
     */
    suspend fun reconcileCommit(request: ReconcileCommitRequest): ReconcileCommitResult =
        ReconcileCommitResult.Unsupported()

    /**
     * GATE 13 §8 — the one reviewer-action entry point (flag / bury / suspend).
     *
     * A separate mutation family from [commitRating], by construction:
     *
     * - it is **not** routed through the rating transaction pipeline and never writes a
     *   [ReviewCommitStatus] (INV-13-04): burying a card is not rating it, and no review history
     *   may be fabricated for it;
     * - it returns [ReviewerActionBackendResult] — never a `Boolean` (§8) — whose
     *   [ReviewerActionBackendResult.OutcomeUnknown] is a first-class outcome that a lost response
     *   is never reinterpreted out of ([ReviewerActionStatus.AMBIGUOUS]);
     * - the default implementation refuses with [AnkiError.UnsupportedAction], so a backend that
     *   has not audited its own action contract cannot silently accept one (capability truth).
     *
     * Implementations must dispatch **at most one** backend mutation per call, must never retry
     * internally, and must confirm success from the backend's own observable state rather than
     * from a returned row count.
     */
    suspend fun performReviewerAction(
        cardRef: AnkiCardRef,
        action: ReviewerAction
    ): ReviewerActionBackendResult =
        ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.UnsupportedAction(action.key))

    /**
     * GATE 13 §17/INV-13-08 — the same entry point with the transaction executor's durable boundary.
     *
     * [mutationEntry] MUST be called once, after any read-only preflight but immediately BEFORE
     * invoking the real backend mutation. A backend must not invoke that mutation if this callback
     * returns `false`. If it returns before calling the callback it certifies that **no backend
     * mutation was dispatched by this attempt**, which is what lets the coordinator record a
     * pre-dispatch refusal as provably-not-applied.
     *
     * The default has no separate preflight: it writes the boundary marker before
     * [performReviewerAction]. AnkiDroid overrides this so its card-state reads stay on the
     * `PREPARED` side of the boundary.
     */
    suspend fun performReviewerAction(
        cardRef: AnkiCardRef,
        action: ReviewerAction,
        mutationEntry: suspend () -> Boolean
    ): ReviewerActionBackendResult {
        if (!mutationEntry()) {
            return ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.ActionLedgerUnavailable())
        }
        return performReviewerAction(cardRef, action)
    }

    /**
     * GATE 13 §28 — the *verified* semantics of one reviewer action on this backend.
     *
     * The default claims nothing ([ReviewerActionSemantics.UNVERIFIED]): an audit is a fact about a
     * public contract, so a backend that has not audited one cannot inherit a promise. Without
     * verified semantics an unresolved action can never be reconciled and is never replayed (§29).
     */
    fun reviewerActionSemantics(action: ReviewerAction): ReviewerActionSemantics =
        ReviewerActionSemantics.UNVERIFIED

    /**
     * GATE 13 §27 — decide an unresolved action from backend-observable evidence, **without**
     * mutating.
     *
     * Implemented only where the backend exposes enough state to decide; the default answers
     * [ReviewerActionReconciliationResult.Unresolved], which keeps the record `AMBIGUOUS`.
     * Implementations must not report `ConfirmedApplied` from a change that cannot be attributed to
     * this action, and must never dispatch a mutation from here (read-only by contract).
     */
    suspend fun reconcileReviewerAction(
        request: ReconcileReviewerActionRequest
    ): ReviewerActionReconciliationResult = ReviewerActionReconciliationResult.Unresolved()

    /**
     * GATE 06 §86 — close a review session and forget its runtime record. Releasing a handle is
     * **not** a mutation towards the scheduler: it never rates, buries, suspends or queries the
     * next card. `true` when the backend released (or never held) the handle.
     *
     * The default answers `false`: a backend that has not modelled session handles cannot claim one
     * was released.
     */
    suspend fun endReview(session: AnkiReviewSession): Boolean = false

    /**
     * GATE 17 — the concurrency and ordering claims this backend makes for note edits. Effect
     * semantics, never UI capability; see [NoteMutationSemantics]. Unverified adapters claim nothing.
     */
    val noteMutationSemantics: NoteMutationSemantics get() = NoteMutationSemantics.UNVERIFIED

    /**
     * GATE 17 — ONE backend write step of a note mutation (fields+tags, or a card deck move). Only
     * [NoteMutationCoordinator] may call this, and only after the boundary is durably recorded.
     *
     * The adapter must classify honestly: [NoteMutationBackendResult.ConfirmedNotApplied] only when
     * it can prove nothing was applied, [NoteMutationBackendResult.Conflict] only when it refused
     * because the note no longer matches, and [NoteMutationBackendResult.OutcomeUnknown] otherwise.
     * The default refuses without any effect.
     */
    suspend fun applyNoteMutation(request: BackendNoteMutationRequest): NoteMutationBackendResult =
        NoteMutationBackendResult.ConfirmedNotApplied(AnkiError.UnsupportedAction(action = "note_mutation"))

    /**
     * GATE 17 — read-only evidence check for an AMBIGUOUS note mutation. Must never write. The
     * default is [NoteMutationReconciliationResult.Unresolved], which keeps the record AMBIGUOUS.
     */
    suspend fun reconcileNoteMutation(
        request: NoteMutationReconciliationRequest
    ): NoteMutationReconciliationResult = NoteMutationReconciliationResult.Unresolved()
}

/** Scheduled review only. Null deck means backend-defined collection-wide review. */
data class BeginReviewRequest(val context: AnkiSessionContext, val limit: Int? = null)
// No require() on the limit: an out-of-range value must be *refused*, not crash the caller.
// Backends validate the range and return a typed InvalidRequest("review_limit_out_of_range"),
// which keeps untrusted/edge input on the domain-error path (INV-ANKI-DET-09).

/** Opaque backend stream handle, separate from the existing user study-session ID. */
data class AnkiReviewSession(val context: AnkiSessionContext, val backendSessionRef: String) {
    init { require(backendSessionRef.isNotBlank()) }
}

/**
 * What one review turn currently knows about its card (§27/§122/§123).
 *
 * A turn is created from scheduler identity alone ([Scheduled]) and only later carries card
 * content ([Rendered]). Modelling the two phases instead of filling an [AnkiRenderedCard] with
 * placeholder question/answer strings is what keeps "this card's answer is genuinely empty"
 * distinguishable from "this card has not been loaded yet" (§28).
 *
 * GATE 07 keeps the [AnkiScheduledCard] inside **both** phases (STEP 55/§57/INV-ANKI-CARD-23):
 * hydration must never erase the scheduler's rating options, button count or next-review labels,
 * so [Rendered] carries the rendered content *and* the scheduling context it was scheduled with.
 * The two surfaces stay separate objects with disjoint authoritative fields (see
 * [AnkiSchedulingInfo]) rather than being flattened into one ambiguous blob.
 */
sealed interface AnkiReviewTurnContent {
    /** Identity + scheduler metadata, exactly as GATE 06 received them. Survives hydration. */
    val scheduledCard: AnkiScheduledCard
    val ref: AnkiCardRef get() = scheduledCard.ref

    /** Presentation media: scheduled references until hydration, merged references after. */
    val media: List<AnkiMediaRef>

    /** Scheduler identity and metadata only; GATE 07 replaces this with [Rendered] on hydration. */
    data class Scheduled(override val scheduledCard: AnkiScheduledCard) : AnkiReviewTurnContent {
        override val media: List<AnkiMediaRef> get() = scheduledCard.media
    }

    data class Rendered(
        val card: AnkiRenderedCard,
        override val scheduledCard: AnkiScheduledCard
    ) : AnkiReviewTurnContent {
        override val media: List<AnkiMediaRef> get() = card.media
    }
}

/**
 * Immutable binding of one presentation to the user session and backend.
 *
 * A turn is a *presentation*, not a card: the same card legitimately produces many turns with
 * distinct [turnId]s (INV-ANKI-REV-04/05). AnkiDroid owns card identity; Study-Agent owns turn
 * identity, and a turn id is created only once a scheduled card has actually been accepted as
 * current — a failed provider read never leaves an orphaned turn behind (§58/§59).
 *
 * Hydration never changes [turnId] (INV-ANKI-CARD-22): loading content is not a new
 * presentation. `AnkiCardHydration.attach` performs the identity-verified attach and is the
 * only supported way to move from [AnkiReviewTurnContent.Scheduled] to
 * [AnkiReviewTurnContent.Rendered] (INV-ANKI-CARD-02).
 */
data class AnkiReviewTurn(
    val turnId: ReviewTurnId,
    val studySessionId: String,
    val content: AnkiReviewTurnContent,
    val position: Int? = null,
    val remaining: Int? = null
) {
    init {
        require(studySessionId.isNotBlank())
        require(position == null || position > 0)
        require(remaining == null || remaining >= 0)
    }
    val cardRef: AnkiCardRef get() = content.ref
    /** The scheduled identity + scheduler metadata. Always present, even after hydration. */
    val scheduledCard: AnkiScheduledCard get() = content.scheduledCard
    /** Non-null only after GATE 07 hydration; never a placeholder. */
    val renderedCard: AnkiRenderedCard? get() = (content as? AnkiReviewTurnContent.Rendered)?.card
    /** Scheduler rating options survive hydration (INV-ANKI-CARD-23). */
    val ratingOptions: AnkiRatingOptions get() = content.scheduledCard.ratingOptions
    val backendId: AnkiBackendId get() = cardRef.backendId
    val commitId: ReviewCommitId get() = ReviewCommitId(backendId, studySessionId, turnId)

    /**
     * GATE 13 STEP 38 — the *minimal* flag projection of a confirmed [ReviewerAction.SetFlag].
     *
     * Deliberately narrow: only the flag marker changes. [turnId], the scheduled card, the rating
     * options, the rendered content, the media and the turn position are all preserved, so a flag
     * never resets the presentation, the transcript or the reveal/compare state (STEP 37) and never
     * triggers a re-read of the card just to display a marker. A turn that was never hydrated keeps
     * its [AnkiReviewTurnContent.Scheduled] shape — there is no rendered card to project onto.
     */
    fun withFlag(flag: AnkiFlag): AnkiReviewTurn {
        val rendered = content as? AnkiReviewTurnContent.Rendered ?: return this
        if (rendered.card.flag == flag) return this
        return copy(content = rendered.copy(card = rendered.card.copy(flag = flag)))
    }
}

/**
 * Existing Rating has exactly AGAIN/HARD/GOOD/EASY semantics; no parallel AnkiRating enum.
 *
 * GATE 11: [commitId] carries the study session id and the review turn id, so the request is the
 * spec's `(sessionId, turnId, cardRef, commitId, rating, answerTime)` without duplicated fields.
 * [answerDurationMs] is measured from question presentation to rating selection (see
 * `docs/SESSION_STATE_MACHINE.md` §17). [evidence] is the backend's own pre-mutation baseline from
 * [AnkiBackend.prepareCommit]; the ledger persists it, so every retry re-sends the same request.
 */
data class CommitRatingRequest(
    val commitId: ReviewCommitId,
    val card: AnkiCardRef,
    val rating: Rating,
    val ratedAtEpochMs: Long,
    val answerDurationMs: Long? = null,
    val evidence: ReviewCommitEvidence? = null,
    /** The deck bound to the turn; identity only, for restore/integrity checks. */
    val deckRef: AnkiDeckRef? = null,
    /** Collection identity from the bound review context, when available. */
    val collectionRef: AnkiCollectionIdentity? = null
) {
    val sessionId: String get() = commitId.studySessionId
    val turnId: ReviewTurnId get() = commitId.turnId

    init {
        require(commitId.backendId == card.backendId)
        require(deckRef == null || (deckRef.backendId == card.backendId &&
            (deckRef.collectionKey == null || card.collectionKey == null || deckRef.collectionKey == card.collectionKey)))
        require(collectionRef == null || collectionRef.backendId == commitId.backendId)
        require(collectionRef?.collectionKey == null || card.collectionKey == null ||
            collectionRef.collectionKey == card.collectionKey)
        require(collectionRef?.collectionKey == null || deckRef?.collectionKey == null ||
            collectionRef.collectionKey == deckRef.collectionKey)
        require(ratedAtEpochMs >= 0)
        require(answerDurationMs == null || answerDurationMs >= 0)
    }
}

/**
 * GATE 11 — everything a backend needs to decide an AMBIGUOUS commit after the fact, taken from the
 * durable ledger record (so it works after process death, without the original session handle).
 * The mutation window is `[submittedAtEpochMs, windowEndEpochMs]`.
 */
data class ReconcileCommitRequest(
    val commitId: ReviewCommitId,
    val card: AnkiCardRef,
    val rating: Rating,
    val evidence: ReviewCommitEvidence?,
    val submittedAtEpochMs: Long,
    val windowEndEpochMs: Long
) {
    init {
        require(commitId.backendId == card.backendId)
        require(submittedAtEpochMs >= 0 && windowEndEpochMs >= submittedAtEpochMs)
    }
}

/** Future typed bury/suspend/flag methods may share identity, not string commands. */
data class CardActionRequest(
    val card: AnkiCardRef,
    val session: AnkiReviewSession,
    val turnId: ReviewTurnId? = null
) {
    init {
        require(card.backendId == session.context.backendId)
        val collectionKey = session.context.collection?.collectionKey ?: session.context.deckRef?.collectionKey
        require(collectionKey == null || card.collectionKey == null || collectionKey == card.collectionKey)
    }
}
