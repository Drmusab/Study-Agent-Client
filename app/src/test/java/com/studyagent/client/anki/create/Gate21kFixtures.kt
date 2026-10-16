package com.studyagent.client.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelField
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.create.AddNoteDraft
import com.studyagent.client.core.anki.create.AnkiNoteCreationContext
import com.studyagent.client.core.anki.create.CreationMediaKind
import com.studyagent.client.core.anki.create.DefaultNoteCreationCoordinator
import com.studyagent.client.core.anki.create.DefaultNoteCreationLedger
import com.studyagent.client.core.anki.create.NoteCreationCoordinator
import com.studyagent.client.core.anki.create.PendingMedia

/**
 * GATE 21K — two controlled fake backends with deliberately colliding backend-local identities
 * (CHECKPOINT 21K-33):
 *
 * ```text
 * Backend A (AnkiDroidLocal):      model "100" (Front/Back),    deck "10" ("Deck@A"), first note "1000"
 * Backend B (PcAgent pc-profile):  model "100" (Question/Answer), deck "10" ("Deck@B"), first note "1000"
 * ```
 *
 * Same model id, same model *name*, same deck id, same first note id, same generated card ids —
 * different schemas, different deck names, different media stores, different note contents. If any
 * creation path ever confused the two, the per-backend call logs and the hydrated contents would
 * show it immediately. No real Anki is touched.
 */
class Gate21kWorld(
    val events: MutableList<String> = mutableListOf()
) {
    val aId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
    val bId: AnkiBackendId = AnkiBackendId.PcAgent("pc-profile-1")

    val a: FakeNoteCreationBackend = collidingBackend(aId, deckName = "Deck@A", fields = listOf("Front", "Back"))
    val b: FakeNoteCreationBackend = collidingBackend(bId, deckName = "Deck@B", fields = listOf("Question", "Answer"))

    val store = InMemoryNoteCreationStore(events)
    val ledger = DefaultNoteCreationLedger(store)
    val registry = AnkiBackendRegistry(listOf(a, b))
    val coordinator: NoteCreationCoordinator =
        DefaultNoteCreationCoordinator(backends = registry, ledger = ledger)

    /** A fresh coordinator (new process) over the same durable store and a chosen registry. */
    fun restartedCoordinator(vararg backends: FakeNoteCreationBackend): NoteCreationCoordinator {
        val members = if (backends.isEmpty()) listOf(a, b) else backends.toList()
        return DefaultNoteCreationCoordinator(
            backends = AnkiBackendRegistry(members),
            ledger = DefaultNoteCreationLedger(store)
        )
    }

    fun modelOf(backend: FakeNoteCreationBackend): AnkiNoteModel = backend.models.first()

    fun draftFor(
        backend: FakeNoteCreationBackend,
        values: Map<Int, String> = mapOf(0 to "front-${backend.id.stableId}", 1 to "back"),
        tags: List<String> = listOf("alpha"),
        media: List<PendingMedia> = emptyList(),
        collectionKey: String? = null,
        model: AnkiNoteModel = modelOf(backend)
    ): AddNoteDraft = AddNoteDraft(
        context = AnkiNoteCreationContext.of(backend.id, collectionKey),
        model = model,
        fieldValues = values,
        tags = tags,
        media = media
    )

    private fun collidingBackend(
        id: AnkiBackendId,
        deckName: String,
        fields: List<String>
    ): FakeNoteCreationBackend {
        val backend = FakeNoteCreationBackend(backendId = id, events = events)
        backend.models.clear()
        backend.models += AnkiNoteModel(
            ref = AnkiNoteModelRef(id, "100"),
            name = "Basic",
            kind = AnkiNoteModelKind.NORMAL,
            fields = fields.mapIndexed { index, name -> AnkiNoteModelField(ordinal = index, name = name) },
            templateCount = 1,
            defaultDeckId = "10"
        )
        backend.decks.clear()
        backend.decks += AnkiDeck(ref = AnkiDeckRef(id, "1"), name = "Default", isFiltered = false)
        backend.decks += AnkiDeck(ref = AnkiDeckRef(id, "10"), name = deckName, isFiltered = false)
        return backend
    }

    companion object {
        fun image(target: Int = 1, name: String = "image.png") = PendingMedia(
            contentUri = "content://picker/$name",
            sourceName = name,
            mimeType = "image/png",
            extension = "png",
            sizeBytes = 2048,
            kind = CreationMediaKind.IMAGE,
            targetFieldOrdinal = target
        )

        fun sound(target: Int = 0, name: String = "clip.mp3") = PendingMedia(
            contentUri = "content://picker/$name",
            sourceName = name,
            mimeType = "audio/mpeg",
            extension = "mp3",
            sizeBytes = 1024,
            kind = CreationMediaKind.AUDIO,
            targetFieldOrdinal = target
        )
    }
}

/** Every backend touch the fake observed, reads included. Zero means "never reached this backend". */
fun FakeNoteCreationBackend.totalCalls(): Int =
    mediaStoreCalls.size + createNoteCalls.size + hydrationCalls.size + modelListingCalls

/** Write touches only (media stores + note creations). */
fun FakeNoteCreationBackend.writeCalls(): Int = mediaStoreCalls.size + createNoteCalls.size
