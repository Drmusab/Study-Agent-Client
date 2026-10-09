package com.studyagent.client.anki.create

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCreatedNote
import com.studyagent.client.core.anki.AnkiCreatedNoteCard
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelField
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.create.CreateNoteBackendRequest
import com.studyagent.client.core.anki.create.CreateNoteBackendResult
import com.studyagent.client.core.anki.create.MediaStoreBackendResult
import com.studyagent.client.core.anki.create.NoteCreationSemantics
import com.studyagent.client.core.anki.create.StoreMediaBackendRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-only [AnkiBackend] for GATE 18. Models the pinned creation contract faithfully by default:
 * media is stored content-idempotently (equal preferred names deduplicate to one stored name), a
 * note insert returns an authoritative id, and hydration reads back what the backend holds — never
 * the request. Every step is scriptable (refuse, unknown outcome, throw-free failures), and all
 * calls are recorded in [events] so ordering can be asserted across coordinator + backend.
 *
 * Deliberately separate from the review fake, exactly as GATE 17 kept its own mutation fake: the
 * creation contract needs different scripting and mixing them would blur both.
 */
class FakeNoteCreationBackend(
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal,
    capabilities: AnkiCapabilities = defaultCapabilities(),
    override val noteCreationSemantics: NoteCreationSemantics = NoteCreationSemantics.ANKIDROID_V2_24_1,
    /** Shared with the store/harness so cross-component ordering can be asserted. */
    val events: MutableList<String> = mutableListOf()
) : AnkiBackend {

    val caps = MutableStateFlow(capabilities)

    // ---- collection state -----------------------------------------------------------------------

    val models: MutableList<AnkiNoteModel> = mutableListOf(basicModel())
    val decks: MutableList<AnkiDeck> = mutableListOf(
        AnkiDeck(ref = AnkiDeckRef(backendId, "1"), name = "Default", isFiltered = false),
        AnkiDeck(ref = AnkiDeckRef(backendId, "7"), name = "Spanish", isFiltered = false)
    )

    /** Notes the backend believes exist, keyed by note id (hydration truth, never the request). */
    val storedNotes = LinkedHashMap<String, AnkiCreatedNote>()

    /** Stored media names — content-idempotent: re-storing an equal preferred name returns it once. */
    val storedMedia = LinkedHashMap<String, String>()

    private var nextNoteId = 1000L

    // ---- scripting ------------------------------------------------------------------------------

    /** Scripted model-listing failures; consumed in order. */
    val modelListingResults = ArrayDeque<AnkiResult<List<AnkiNoteModel>>>()

    /** Scripted media-store outcomes; consumed in order, otherwise faithful storage. */
    val mediaStoreResults = ArrayDeque<MediaStoreBackendResult>()

    /** Scripted create-note outcomes; consumed in order, otherwise faithful creation. */
    val createNoteResults = ArrayDeque<CreateNoteBackendResult>()

    /** Scripted hydration failures; consumed in order. */
    val hydrationFailures = ArrayDeque<AnkiError>()

    /** How many cards Anki "generates" for a created note (per-note-type scripting). */
    var generatedCardsFor: (CreateNoteBackendRequest) -> Int = { 1 }

    /** Called before each create dispatch, so a test can drift the schema mid-flight. */
    var beforeCreate: (suspend (FakeNoteCreationBackend) -> Unit)? = null

    /** When true, a faithful create returns a null-uri style OutcomeUnknown. */
    var failCreateWithUnknown = false

    // ---- observation ----------------------------------------------------------------------------

    val mediaStoreCalls = mutableListOf<StoreMediaBackendRequest>()
    val createNoteCalls = mutableListOf<CreateNoteBackendRequest>()
    val hydrationCalls = mutableListOf<AnkiNoteRef>()
    var modelListingCalls = 0
        private set

    // ---------------------------------------------------------------- creation surface

    override suspend fun getNoteModels(): AnkiResult<List<AnkiNoteModel>> {
        modelListingCalls++
        events += "backend:models"
        if (modelListingResults.isNotEmpty()) return modelListingResults.removeFirst()
        return AnkiResult.Success(models.toList())
    }

    override suspend fun storeAnkiMedia(request: StoreMediaBackendRequest): MediaStoreBackendResult {
        mediaStoreCalls += request
        events += "backend:media:${request.preferredName}"
        if (mediaStoreResults.isNotEmpty()) return mediaStoreResults.removeFirst()
        // Content-idempotent: the pinned provider deduplicates by hash; the fake dedupes by name.
        val name = storedMedia.getOrPut(request.preferredName) { request.preferredName }
        return MediaStoreBackendResult.Stored(name)
    }

    override suspend fun createAnkiNote(request: CreateNoteBackendRequest): CreateNoteBackendResult {
        createNoteCalls += request
        events += "backend:create"
        beforeCreate?.invoke(this)
        if (createNoteResults.isNotEmpty()) return createNoteResults.removeFirst()
        if (failCreateWithUnknown) {
            return CreateNoteBackendResult.OutcomeUnknown(AnkiError.QueryFailure("fake_null_uri"))
        }
        val noteId = (nextNoteId++).toString()
        val model = models.firstOrNull { it.ref.modelId == request.model.modelId }
        val cardCount = generatedCardsFor(request)
        val deckId = model?.defaultDeckId ?: "1"
        val deckName = decks.firstOrNull { it.ref.deckId == deckId }?.name
        storedNotes[noteId] = AnkiCreatedNote(
            noteRef = AnkiNoteRef(backendId, noteId),
            model = request.model,
            modelName = model?.name,
            fields = request.orderedFields.toList(),
            tags = request.tags.toList(),
            cards = List(cardCount) { index ->
                AnkiCreatedNoteCard(
                    ref = AnkiCardRef(backendId, cardId = "$noteId-c$index", noteId = noteId, cardOrd = index),
                    cardName = if (cardCount == 1) null else "Card ${index + 1}",
                    deckRef = AnkiDeckRef(backendId, deckId),
                    deckName = deckName
                )
            }
        )
        return CreateNoteBackendResult.ConfirmedCreated(noteId)
    }

    override suspend fun resolveCreatedNote(noteRef: AnkiNoteRef): AnkiResult<AnkiCreatedNote> {
        hydrationCalls += noteRef
        events += "backend:hydrate:${noteRef.noteId}"
        if (hydrationFailures.isNotEmpty()) return AnkiResult.Failure(hydrationFailures.removeFirst())
        val note = storedNotes[noteRef.noteId]
            ?: return AnkiResult.Failure(AnkiError.NoteNotFound())
        return AnkiResult.Success(note)
    }

    // ---------------------------------------------------------------- ordinary surface (minimal)

    override val id: AnkiBackendId get() = backendId
    override val availability: StateFlow<AnkiAvailability> =
        MutableStateFlow(AnkiAvailability.Ready(capabilities))
    override val capabilities: StateFlow<AnkiCapabilities> get() = caps

    override suspend fun refreshAvailability() = Unit

    override suspend fun getDecks(): AnkiResult<List<AnkiDeck>> {
        events += "backend:decks"
        return AnkiResult.Success(decks.toList())
    }

    override suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?> = AnkiResult.Success(null)

    override suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession> =
        AnkiResult.Failure(AnkiError.UnsupportedAction("not_used_by_creation_tests"))

    override suspend fun nextCard(session: AnkiReviewSession): NextCardResult = NextCardResult.Finished

    override suspend fun hydrateCardContent(card: AnkiCardRef) =
        AnkiResult.Failure(AnkiError.UnsupportedAction("not_used_by_creation_tests"))

    override suspend fun commitRating(request: CommitRatingRequest): BackendCommitResult =
        error("rating is not part of creation tests")

    companion object {
        fun defaultCapabilities() = AnkiCapabilities(
            deckListing = true,
            noteModelListing = true,
            createNotes = true,
            storeMedia = true,
            authoritativeCreationReconciliation = false
        )

        /** A two-field Basic model bound to deck 7, matching the fake's deck listing. */
        fun basicModel(
            backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal,
            modelId: String = "model-basic",
            defaultDeckId: String? = "7"
        ): AnkiNoteModel = AnkiNoteModel(
            ref = AnkiNoteModelRef(backendId, modelId),
            name = "Basic",
            kind = AnkiNoteModelKind.NORMAL,
            fields = listOf(
                AnkiNoteModelField(ordinal = 0, name = "Front"),
                AnkiNoteModelField(ordinal = 1, name = "Back")
            ),
            templateCount = 1,
            defaultDeckId = defaultDeckId
        )

        /** A cloze model with three fields. */
        fun clozeModel(backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal): AnkiNoteModel =
            AnkiNoteModel(
                ref = AnkiNoteModelRef(backendId, "model-cloze"),
                name = "Cloze",
                kind = AnkiNoteModelKind.CLOZE,
                fields = listOf(
                    AnkiNoteModelField(ordinal = 0, name = "Text"),
                    AnkiNoteModelField(ordinal = 1, name = "Extra"),
                    AnkiNoteModelField(ordinal = 2, name = "Back Extra")
                ),
                templateCount = null,
                defaultDeckId = "1"
            )
    }
}
