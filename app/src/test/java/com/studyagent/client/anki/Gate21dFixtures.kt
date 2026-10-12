package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendPreferenceStore
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiCardPage
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelEnriched
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.CommitPreparation
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.ReconcileCommitRequest
import com.studyagent.client.core.anki.ReconcileCommitResult
import com.studyagent.client.core.anki.ReconcileReviewerActionRequest
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionBackendResult
import com.studyagent.client.core.anki.ReviewerActionReconciliationResult
import com.studyagent.client.core.anki.create.CreateNoteBackendRequest
import com.studyagent.client.core.anki.create.CreateNoteBackendResult
import com.studyagent.client.core.anki.create.MediaStoreBackendResult
import com.studyagent.client.core.anki.create.StoreMediaBackendRequest
import com.studyagent.client.core.anki.AnkiCreatedNote
import com.studyagent.client.core.anki.edit.BackendNoteMutationRequest
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationRequest
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Stable identities used across the GATE 21D verification. */
internal object Gate21dIds {
    val local: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
    val pcA: AnkiBackendId = AnkiBackendId.PcAgent("pc-a")
    val pcB: AnkiBackendId = AnkiBackendId.PcAgent("pc-b")
}

/** Capability sets named for what they model, so a test reads as a scenario. */
internal object Gate21dCaps {
    val REVIEW = AnkiCapabilities(review = true, scheduledReview = true, deckListing = true, renderedCards = true)
    val DECKS_ONLY = AnkiCapabilities(deckListing = true)
    val WITH_CREATE = REVIEW.copy(createNotes = true, noteModelListing = true, storeMedia = true)
}

/** A preference store that never changes: the status center only needs `mode` for its snapshot. */
internal class FixedPreferenceStore(initial: AnkiBackendMode = AnkiBackendMode.AUTO) : AnkiBackendPreferenceStore {
    private val state = MutableStateFlow(initial)
    override val mode: StateFlow<AnkiBackendMode> = state.asStateFlow()
    override val lastWriteError: StateFlow<String?> = MutableStateFlow<String?>(null)
    override suspend fun setMode(mode: AnkiBackendMode): Boolean {
        state.value = mode
        return true
    }
}

/**
 * GATE 21D fixture — a **strict, call-recording** backend.
 *
 * It exists to prove a negative that a behavioural test cannot otherwise see: that reading the
 * registry, observing a state and requesting a refresh reach *nothing* of a backend except its two
 * state flows and `refreshAvailability()`. Every other member of [AnkiBackend] records its name in
 * [calls] and refuses with a typed failure — in particular every scheduler, rating, reviewer-action,
 * note-edit, creation and media member, so a health check that "tests readiness" by writing would be
 * visible as a recorded call. (`Gate21dArchitectureAuditTest` pins that this class overrides every
 * member `AnkiBackend` declares, so a member added later cannot slip past unrecorded.)
 *
 * Availability and capabilities are two separate flows, exactly as in production, and [publish] sets
 * them one after the other — so a reader can observe the same transient disagreement a real backend
 * can produce. [availabilityOverride] lets a fixture be driven by a real availability source (the PC
 * readiness structure) instead of by hand.
 *
 * Not a registered production backend: a test double, in test sources only.
 */
internal class ProbeOnlyBackend(
    override val id: AnkiBackendId,
    availability: AnkiAvailability = AnkiAvailability.Checking,
    capabilities: AnkiCapabilities = AnkiCapabilities.NONE,
    private val claim: AnkiCapabilities? = null,
    private val availabilityOverride: StateFlow<AnkiAvailability>? = null,
    private val capabilitiesOverride: StateFlow<AnkiCapabilities>? = null,
    private val onRefresh: suspend ProbeOnlyBackend.() -> Unit = {}
) : AnkiBackend {

    private val ownAvailability = MutableStateFlow(availability)
    private val ownCapabilities = MutableStateFlow(capabilities)

    override val availability: StateFlow<AnkiAvailability> =
        availabilityOverride ?: ownAvailability.asStateFlow()
    override val capabilities: StateFlow<AnkiCapabilities> =
        capabilitiesOverride ?: ownCapabilities.asStateFlow()
    override val implementedCapabilities: AnkiCapabilities? get() = claim

    /** Every call that reached this backend beyond reading its flows, in order. */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    /** How many collectors currently observe this backend's availability — 0 means nobody does. */
    val availabilityObservers: Int get() = ownAvailability.subscriptionCount.value

    /** Publishes availability first and capabilities second, like two independent flows would. */
    fun publish(availability: AnkiAvailability, capabilities: AnkiCapabilities? = null) {
        ownAvailability.value = availability
        if (capabilities != null) ownCapabilities.value = capabilities
    }

    /** A coherent `Ready`: the embedded snapshot equals the live projection. */
    fun publishReady(capabilities: AnkiCapabilities) = publish(AnkiAvailability.Ready(capabilities), capabilities)

    private fun refuse(name: String): AnkiError {
        calls += name
        return AnkiError.UnsupportedAction("probe_only:$name")
    }

    override suspend fun refreshAvailability() {
        calls += "refreshAvailability"
        onRefresh(this)
    }

    override suspend fun getDecks(): AnkiResult<List<AnkiDeck>> = AnkiResult.Failure(refuse("getDecks"))

    override suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?> = AnkiResult.Failure(refuse("getSelectedDeck"))

    override suspend fun browseCards(query: AnkiCardQuery): AnkiResult<AnkiCardPage> =
        AnkiResult.Failure(refuse("browseCards"))

    override suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession> =
        AnkiResult.Failure(refuse("beginReview"))

    override suspend fun nextCard(session: AnkiReviewSession): NextCardResult =
        NextCardResult.Failure(refuse("nextCard"))

    override suspend fun hydrateCardContent(card: AnkiCardRef): AnkiResult<AnkiRenderedCard> =
        AnkiResult.Failure(refuse("hydrateCardContent"))

    override suspend fun getCardDetails(cardRef: AnkiCardRef): AnkiResult<AnkiCardDetails> =
        AnkiResult.Failure(refuse("getCardDetails"))

    override suspend fun prepareCommit(request: CommitRatingRequest): CommitPreparation =
        CommitPreparation.Refused(refuse("prepareCommit"), retryable = false)

    override suspend fun commitRating(request: CommitRatingRequest): BackendCommitResult =
        BackendCommitResult.ConfirmedNotCommitted(refuse("commitRating"))

    override suspend fun commitRating(
        request: CommitRatingRequest,
        mutationEntry: suspend () -> Boolean
    ): BackendCommitResult = BackendCommitResult.ConfirmedNotCommitted(refuse("commitRatingWithEntry"))

    override suspend fun reconcileCommit(request: ReconcileCommitRequest): ReconcileCommitResult {
        refuse("reconcileCommit")
        return ReconcileCommitResult.Unsupported()
    }

    override suspend fun performReviewerAction(
        cardRef: AnkiCardRef,
        action: ReviewerAction
    ): ReviewerActionBackendResult = ReviewerActionBackendResult.ConfirmedNotApplied(refuse("performReviewerAction"))

    override suspend fun performReviewerAction(
        cardRef: AnkiCardRef,
        action: ReviewerAction,
        mutationEntry: suspend () -> Boolean
    ): ReviewerActionBackendResult =
        ReviewerActionBackendResult.ConfirmedNotApplied(refuse("performReviewerActionWithEntry"))

    override suspend fun reconcileReviewerAction(
        request: ReconcileReviewerActionRequest
    ): ReviewerActionReconciliationResult {
        refuse("reconcileReviewerAction")
        return ReviewerActionReconciliationResult.Unresolved()
    }

    override suspend fun endReview(session: AnkiReviewSession): Boolean {
        refuse("endReview")
        return false
    }

    override suspend fun applyNoteMutation(request: BackendNoteMutationRequest): NoteMutationBackendResult =
        NoteMutationBackendResult.ConfirmedNotApplied(refuse("applyNoteMutation"))

    override suspend fun reconcileNoteMutation(
        request: NoteMutationReconciliationRequest
    ): NoteMutationReconciliationResult {
        refuse("reconcileNoteMutation")
        return NoteMutationReconciliationResult.Unresolved()
    }

    override suspend fun getNoteModels(): AnkiResult<List<AnkiNoteModel>> =
        AnkiResult.Failure(refuse("getNoteModels"))

    override suspend fun getNoteModel(ref: AnkiNoteModelRef): AnkiResult<AnkiNoteModelEnriched> =
        AnkiResult.Failure(refuse("getNoteModel"))

    override suspend fun storeAnkiMedia(request: StoreMediaBackendRequest): MediaStoreBackendResult =
        MediaStoreBackendResult.ConfirmedNotStored(refuse("storeAnkiMedia"))

    override suspend fun createAnkiNote(request: CreateNoteBackendRequest): CreateNoteBackendResult =
        CreateNoteBackendResult.ConfirmedNotCreated(refuse("createAnkiNote"))

    override suspend fun resolveCreatedNote(noteRef: AnkiNoteRef): AnkiResult<AnkiCreatedNote> =
        AnkiResult.Failure(refuse("resolveCreatedNote"))
}
