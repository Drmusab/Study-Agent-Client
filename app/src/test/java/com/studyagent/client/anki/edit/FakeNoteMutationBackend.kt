package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.NoteConflictGuarantee
import com.studyagent.client.core.anki.edit.BackendNoteMutationRequest
import com.studyagent.client.core.anki.edit.NoteDeckChangeScope
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationRequest
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationResult
import com.studyagent.client.core.anki.edit.NoteMutationSemantics
import com.studyagent.client.core.anki.edit.NoteMutationStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-only [AnkiBackend] for GATE 17. One in-memory note with a card in one deck. Writes apply
 * faithfully by default; [applyHook] can replace any step's behaviour (refuse, lie, throw, block).
 *
 * This is deliberately separate from the 1131-line [com.studyagent.client.anki.fake.FakeAnkiBackend]:
 * the note-edit contract needs very different scripting, and mixing them would blur both.
 */
class FakeNoteMutationBackend(
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal,
    capabilities: AnkiCapabilities = defaultCapabilities(),
    override val noteMutationSemantics: NoteMutationSemantics = NoteMutationSemantics.ANKIDROID_V2_24_1,
    /** Shared with the store/harness so cross-component ordering can be asserted. */
    val events: MutableList<String> = mutableListOf(),
    /**
     * GATE 21J (CHECKPOINT 21J-30) — the note/card/deck ids this instance exposes. The defaults are
     * the GATE 17 fixture. The 21J gate sets them to the *same numeric ids* on two different
     * backends, which is the collision the ownership rules exist for.
     */
    private val noteId: String = "note-1",
    private val cardId: String = "card-1",
    /**
     * Deck ids in listing order. [deckIds] `[0]` is the source deck; `[1]` is a ordinary target.
     * [filteredDeckId] and [unverifiableDeckId] keep the GATE 17 refusal cases.
     */
    private val deckIds: List<String> = listOf("deck-a", "deck-b"),
    private val filteredDeckId: String = "deck-filtered",
    private val unverifiableDeckId: String = "deck-unknown"
) : AnkiBackend {

    val caps = MutableStateFlow(capabilities)

    /**
     * GATE 21J — the collection identity this instance reports. `null` is the AnkiDroid-like case:
     * no authoritative discriminator. Tests set it to model two distinct collections.
     */
    var collectionIdentity: AnkiCollectionIdentity? = null
    override val currentCollectionIdentity: AnkiCollectionIdentity?
        get() = collectionIdentity

    // ---- collection state -----------------------------------------------------------------------
    var noteTypeId: String? = "model-1"
    val fields: MutableList<AnkiNoteField> = mutableListOf(
        AnkiNoteField(name = "Front", value = "front text", ordinal = 0),
        AnkiNoteField(name = "Back", value = "back text", ordinal = 1)
    )
    var tags: List<String> = listOf("alpha")
    var deckId: String = deckIds[0]
    val decks: MutableList<AnkiDeck> = mutableListOf(
        AnkiDeck(ref = AnkiDeckRef(backendId, deckIds[0]), name = "A", isFiltered = false),
        AnkiDeck(ref = AnkiDeckRef(backendId, deckIds[1]), name = "B", isFiltered = false),
        AnkiDeck(ref = AnkiDeckRef(backendId, filteredDeckId), name = "F", isFiltered = true),
        AnkiDeck(ref = AnkiDeckRef(backendId, unverifiableDeckId), name = "U", isFiltered = null)
    )

    /**
     * GATE 21J (CHECKPOINT 21J-10/11) — the other cards the same note generates, and the deck each
     * one currently sits in. Empty by default, which is the GATE 17 one-card fixture. A note is not
     * a card: a deck write is card-scoped, so a sibling must never move because the edited card did.
     */
    val siblingCards: MutableList<AnkiCardRef> = mutableListOf()
    private val siblingDeckIds: MutableMap<String, String> = mutableMapOf()

    /** Registers one further card of this note, in [deckId]. Returns the ref for direct assertions. */
    fun addSiblingCard(id: String, ord: Int, deckId: String): AnkiCardRef {
        val ref = AnkiCardRef(backendId, cardId = id, noteId = noteId, cardOrd = ord)
        siblingCards += ref
        siblingDeckIds[id] = deckId
        return ref
    }

    /** The deck a card of this note currently sits in, by card id. Null when the card is unknown. */
    fun deckOfCard(id: String): String? = when (id) {
        cardId -> deckId
        else -> siblingDeckIds[id]
    }

    val otherDeckIds: List<String> get() = listOf(deckIds[1])

    // ---- scripting ------------------------------------------------------------------------------
    /** Replaces the default behaviour of [applyNoteMutation] when non-null. */
    var applyHook: (suspend (BackendNoteMutationRequest, FakeNoteMutationBackend) -> NoteMutationBackendResult)? = null
    var reconcileResult: NoteMutationReconciliationResult = NoteMutationReconciliationResult.Unresolved()
    var readFailure: AnkiError? = null
    var decksFailure: AnkiError? = null

    /** Called before each card read, so a test can change the note between base and save. */
    var beforeRead: (suspend (FakeNoteMutationBackend) -> Unit)? = null

    // ---- observation ----------------------------------------------------------------------------
    val applyCalls = mutableListOf<BackendNoteMutationRequest>()
    val reconcileCalls = mutableListOf<NoteMutationReconciliationRequest>()
    var readCount = 0

    fun cardRef(): AnkiCardRef = AnkiCardRef(backendId, cardId = cardId, noteId = noteId, cardOrd = 0)
    fun noteRef(): AnkiNoteRef = AnkiNoteRef(backendId, noteId)

    /** The exact details a read returns for the current state. */
    fun details(): AnkiCardDetails = AnkiCardDetails(
        cardRef = cardRef(),
        noteRef = noteRef(),
        cardOrd = 0,
        deckRef = AnkiDeckRef(backendId, deckId),
        deckName = deckId,
        noteTypeId = noteTypeId,
        noteTypeName = "Basic",
        templateName = null,
        questionHtml = null,
        answerHtml = null,
        questionText = null,
        answerText = null,
        pureAnswerText = null,
        fields = fields.toList(),
        tags = tags,
        flag = null,
        cardType = null,
        queueState = null,
        scheduling = null,
        originalDeckRef = null,
        noteCreatedEpochSeconds = null,
        noteModifiedEpochSeconds = null
    )

    /** Applies [step] to the collection state exactly as a correct backend would, for the primary card. */
    fun applyFaithfully(step: NoteMutationStep) {
        when (step) {
            is NoteMutationStep.UpdateNoteContent -> applyContentFaithfully(step)
            is NoteMutationStep.ChangeDeck -> setDeckFor(cardRef(), step.toDeck.deckId)
        }
    }

    /**
     * GATE 21J — the same, but honouring the *step's own card*. A deck write is card-scoped
     * (CONTRACT-03), so the card in the request moves and its siblings do not.
     */
    fun applyFaithfully(request: BackendNoteMutationRequest) {
        when (val step = request.step) {
            is NoteMutationStep.UpdateNoteContent -> applyContentFaithfully(step)
            is NoteMutationStep.ChangeDeck -> setDeckFor(request.cardRef, step.toDeck.deckId)
        }
    }

    private fun applyContentFaithfully(step: NoteMutationStep.UpdateNoteContent) {
        step.fieldValues?.let { values ->
            values.forEachIndexed { index, value ->
                fields[index] = fields[index].copy(value = value)
            }
        }
        step.tags?.let { tags = it }
    }

    /** One card moves. Nothing here can move the note's other cards. */
    fun setDeckFor(target: AnkiCardRef, deckId: String) {
        if (target.cardId == null || target.cardId == this.cardId) this.deckId = deckId
        else siblingDeckIds[target.cardId] = deckId
    }

    override val id: AnkiBackendId get() = backendId
    override val availability: StateFlow<AnkiAvailability> =
        MutableStateFlow(AnkiAvailability.Ready(capabilities))
    override val capabilities: StateFlow<AnkiCapabilities> get() = caps

    override suspend fun refreshAvailability() = Unit

    override suspend fun getDecks(): AnkiResult<List<AnkiDeck>> {
        events += "read:decks"
        decksFailure?.let { return AnkiResult.Failure(it) }
        return AnkiResult.Success(decks.toList())
    }

    override suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?> = AnkiResult.Success(null)

    override suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession> =
        AnkiResult.Failure(AnkiError.UnsupportedAction("not_used_by_note_edit_tests"))

    override suspend fun nextCard(session: AnkiReviewSession): NextCardResult = NextCardResult.Finished

    override suspend fun hydrateCardContent(card: AnkiCardRef) =
        AnkiResult.Failure(AnkiError.UnsupportedAction("not_used_by_note_edit_tests"))

    override suspend fun getCardDetails(cardRef: AnkiCardRef): AnkiResult<AnkiCardDetails> {
        events += "read:card"
        beforeRead?.invoke(this)
        readCount++
        readFailure?.let { return AnkiResult.Failure(it) }
        return AnkiResult.Success(details())
    }

    override suspend fun commitRating(request: CommitRatingRequest): BackendCommitResult =
        error("rating is not part of note-edit tests")

    override suspend fun applyNoteMutation(request: BackendNoteMutationRequest): NoteMutationBackendResult {
        applyCalls += request
        events += "backend:apply:${request.stepIndex}"
        val hook = applyHook
        if (hook != null) return hook(request, this)
        applyFaithfully(request)
        return NoteMutationBackendResult.ConfirmedApplied
    }

    override suspend fun reconcileNoteMutation(
        request: NoteMutationReconciliationRequest
    ): NoteMutationReconciliationResult {
        reconcileCalls += request
        events += "backend:reconcile"
        return reconcileResult
    }

    companion object {
        fun defaultCapabilities() = AnkiCapabilities(
            cardDetails = true,
            editNoteFields = true,
            editNoteTags = true,
            changeCardDeck = true,
            noteEditConflictGuarantee = NoteConflictGuarantee.BEST_EFFORT_PRE_SAVE_REREAD
        )
    }
}

/** Test helpers shared by the GATE 17 suites. */
internal fun FakeNoteMutationBackend.editableBase(): com.studyagent.client.core.anki.edit.NoteEditBase =
    com.studyagent.client.core.anki.edit.NoteEditBase.from(details())
        ?: error("fixture base must be editable")

/** The deck-change scope the fake advertises must match what the backend semantics claim. */
internal val FakeNoteMutationBackend.deckScope: NoteDeckChangeScope get() = noteMutationSemantics.deckChangeScope
