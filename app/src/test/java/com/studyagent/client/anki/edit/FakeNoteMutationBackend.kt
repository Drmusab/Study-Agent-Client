package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
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
    val events: MutableList<String> = mutableListOf()
) : AnkiBackend {

    val caps = MutableStateFlow(capabilities)

    // ---- collection state -----------------------------------------------------------------------
    var noteTypeId: String? = "model-1"
    val fields: MutableList<AnkiNoteField> = mutableListOf(
        AnkiNoteField(name = "Front", value = "front text", ordinal = 0),
        AnkiNoteField(name = "Back", value = "back text", ordinal = 1)
    )
    var tags: List<String> = listOf("alpha")
    var deckId: String = "deck-a"
    val decks: MutableList<AnkiDeck> = mutableListOf(
        AnkiDeck(ref = AnkiDeckRef(backendId, "deck-a"), name = "A", isFiltered = false),
        AnkiDeck(ref = AnkiDeckRef(backendId, "deck-b"), name = "B", isFiltered = false),
        AnkiDeck(ref = AnkiDeckRef(backendId, "deck-filtered"), name = "F", isFiltered = true),
        AnkiDeck(ref = AnkiDeckRef(backendId, "deck-unknown"), name = "U", isFiltered = null)
    )

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

    fun cardRef(): AnkiCardRef = AnkiCardRef(backendId, cardId = "card-1", noteId = "note-1", cardOrd = 0)
    fun noteRef(): AnkiNoteRef = AnkiNoteRef(backendId, "note-1")

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

    /** Applies [step] to the collection state exactly as a correct backend would. */
    fun applyFaithfully(step: NoteMutationStep) {
        when (step) {
            is NoteMutationStep.UpdateNoteContent -> {
                step.fieldValues?.let { values ->
                    values.forEachIndexed { index, value ->
                        fields[index] = fields[index].copy(value = value)
                    }
                }
                step.tags?.let { tags = it }
            }
            is NoteMutationStep.ChangeDeck -> deckId = step.toDeck.deckId
        }
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
        applyFaithfully(request.step)
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
