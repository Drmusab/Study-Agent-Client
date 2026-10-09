package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 18 PART II — repository source audit for note/media creation (AUDIT-18-01 … AUDIT-18-14).
 * Each test pins one structural rule a behavioural test cannot see from the outside: where creation
 * writes may originate, what Anki must own, what may never be touched, how the durable order is
 * kept, and that the presentation layer decides nothing.
 *
 * Rules are enforced on source text with comment lines stripped, so documentation may explain a
 * retired or forbidden spelling while live code may not use it. The locked contract is
 * `docs/GATE_18_BACKEND_CREATION_CONTRACT.md`; the IDs below map to its invariants (INV-18-*) and
 * the gate's audit checklist.
 */
class Gate18ArchitectureAuditTest {

    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private val mainRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")

    private fun File.code(): String = readLines()
        .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") }
        .joinToString("\n")

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 18 source $relative" }
        return file
    }

    private fun mainSources(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun createSources(): List<File> =
        File(mainRoot, "core/anki/create").listFiles()!!.filter { it.extension == "kt" }

    private fun creationDataSources(): List<File> = mainSources().filter {
        it.name.contains("Creation") || it.name == "DataStoreNoteCreationStore.kt" ||
            it.name == "AndroidAnkiDroidMediaProbe.kt"
    }

    // AUDIT-18-01 — one creation entry point.
    @Test
    fun onlyTheCoordinatorInvokesBackendCreation() {
        val createCallers = mainSources()
            .filter { it.code().contains(".createAnkiNote(") }
            .map { it.name }
            .sorted()
        assertEquals(
            listOf("NoteCreationCoordinator.kt"),
            createCallers
        )
        val mediaCallers = mainSources()
            .filter { it.code().contains(".storeAnkiMedia(") }
            .map { it.name }
            .sorted()
        assertEquals(
            listOf("NoteCreationCoordinator.kt"),
            mediaCallers
        )
        // The backend implements the boundary; it never invokes itself.
        val backendImpl = main("data/anki/ankidroid/AnkiDroidBackend.kt").code()
        assertTrue(backendImpl.contains("override suspend fun createAnkiNote("))
        assertTrue(backendImpl.contains("override suspend fun storeAnkiMedia("))
    }

    // AUDIT-18-02 — Study-Agent never creates cards; Anki generates them (INV-18-02/03).
    @Test
    fun creationCodeNeverCreatesOrAssumesCards() {
        for (file in createSources() + creationDataSources()) {
            val text = file.code()
            for (token in listOf("addCard(", "generateCards", "newCard(", "cardGen")) {
                assertFalse("${file.name} must not create cards: $token", text.contains(token))
            }
        }
        // The hydration surface treats the generated cards as an enumeration to read, never assumes
        // one card: the created-note type carries a LIST of cards.
        val models = main("core/anki/AnkiNoteModels.kt").code()
        assertTrue(models.contains("val cards: List<AnkiCreatedNoteCard>"))
    }

    // AUDIT-18-03 — no private media directories; media travels public APIs only (INV-18-12).
    @Test
    fun creationCodeNeverTouchesAnkisPrivateMediaStorage() {
        for (file in createSources() + creationDataSources()) {
            val text = file.code()
            for (token in listOf(
                "/AnkiDroid/", "collection.media", "media.db", "mediaDb",
                "getExternalFilesDir", "FileOutputStream", "copyTo(", "java.io.File("
            )) {
                assertFalse("${file.name} must not touch Anki media storage: $token", text.contains(token))
            }
        }
        val probe = main("data/anki/ankidroid/AndroidAnkiDroidMediaProbe.kt").code()
        assertFalse("the probe reads the picked URI only, never the AnkiDroid provider",
            probe.contains("com.ichi2"))
    }

    // AUDIT-18-04 — no atomic facade over a multi-boundary operation (INV-18-05).
    @Test
    fun mediaAndNoteStaySeparateBoundaries() {
        val coordinator = main("core/anki/create/NoteCreationCoordinator.kt").code()
        assertTrue(coordinator.contains("storeAnkiMedia("))
        assertTrue(coordinator.contains("createAnkiNote("))
        // The backend interface exposes them as separate methods with separate results.
        val backend = main("core/anki/AnkiBackend.kt").code()
        assertTrue(backend.contains("suspend fun storeAnkiMedia("))
        assertTrue(backend.contains("suspend fun createAnkiNote("))
        assertTrue(backend.contains("suspend fun resolveCreatedNote("))
        // And the result models never collapse into one type.
        val results = main("core/anki/create/NoteCreationBackendResults.kt").code()
        assertTrue(results.contains("sealed interface CreateNoteBackendResult"))
        assertTrue(results.contains("sealed interface MediaStoreBackendResult"))
    }

    // AUDIT-18-05 — creation state never shares a ledger, id or status with other gates (INV-18-17).
    @Test
    fun creationLedgerIsSeparateFromEveryOtherLedger() {
        val forbidden = listOf(
            "ReviewCommitLedger", "ReviewerActionLedger", "NoteMutationLedger",
            "ReviewCommitStatus", "ReviewerActionStatus", "NoteMutationStatus",
            "NoteMutationId", "ReviewCommitId", "ReviewerActionId"
        )
        for (file in createSources() + creationDataSources()) {
            val text = file.code()
            for (name in forbidden) {
                assertFalse("${file.name} must not reference $name", text.contains(name))
            }
        }
        // The store file name is its own.
        val store = main("data/anki/DataStoreNoteCreationStore.kt").code()
        assertTrue(store.contains("\"anki_note_creation_ledger\""))
    }

    // AUDIT-18-06 — the status table is exactly the locked set (CONTRACT-18-41).
    @Test
    fun theCreationStatusEnumIsExactlyTheSpecifiedSet() {
        val text = main("core/anki/create/NoteCreationStatus.kt").code()
        val body = text.substringAfter("enum class NoteCreationStatus").substringAfter("{").substringBefore(";")
        val entries = body.split(",").map { it.trim().substringBefore("(").trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf("PREPARED", "STORING_MEDIA", "CREATING_NOTE", "CREATED", "RETRY_ALLOWED", "AMBIGUOUS", "ABANDONED"),
            entries
        )
    }

    // AUDIT-18-07 — durable ordering: boundary marks precede their dispatches (docs/GATE_18 §15).
    @Test
    fun boundaryMarksAreDurableBeforeTheirDispatches() {
        val coordinator = main("core/anki/create/NoteCreationCoordinator.kt").code()
        val enterMedia = coordinator.indexOf("EnterMediaBoundary(index)")
        val storeMedia = coordinator.indexOf("backend.storeAnkiMedia(")
        val enterNote = coordinator.indexOf("NoteCreationEvent.EnterNoteBoundary)")
        val createNote = coordinator.indexOf("backend.createAnkiNote(")
        assertTrue(enterMedia in 0 until storeMedia)
        assertTrue(storeMedia < enterNote)
        assertTrue(enterNote in 0 until createNote)
        // Media boundaries come before the note boundary.
        assertTrue(storeMedia < createNote)
    }

    // AUDIT-18-08 — no blind retry of an unknown outcome (INV-18-08).
    @Test
    fun noAutomaticRetryLoopExistsAroundCreation() {
        val coordinator = main("core/anki/create/NoteCreationCoordinator.kt").code()
        assertFalse("no retry loop may wrap the media boundary",
            Regex("while\\s*\\([^)]*\\)[^}]*storeAnkiMedia").containsMatchIn(coordinator))
        assertFalse("no retry loop may wrap the note boundary",
            Regex("while\\s*\\([^)]*\\)[^}]*createAnkiNote").containsMatchIn(coordinator))
        // AMBIGUOUS is never fed back into a dispatch path.
        assertFalse(
            coordinator.lineSequence().any {
                it.contains("AMBIGUOUS") && it.contains("retry", ignoreCase = true) &&
                    !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//")
            }
        )
    }

    // AUDIT-18-09 — the presentation layer decides nothing about creation.
    @Test
    fun theAddNoteScreenOnlyTalksToItsViewModel() {
        val screen = main("ui/screens/addnote/AddNoteScreen.kt").code()
        for (token in listOf(
            "createAnkiNote", "storeAnkiMedia", "NoteCreationLedger", "ledger.apply",
            "ContentResolver", "safeInsert", "AnkiDroidApiContract"
        )) {
            assertFalse("AddNoteScreen must not reach past the ViewModel: $token", screen.contains(token))
        }
    }

    // AUDIT-18-10 — a hydration failure never re-creates (INV-18-09).
    @Test
    fun hydrationFailureKeepsTheCreationCreated() {
        val transitions = main("core/anki/create/NoteCreationTransition.kt").code()
        assertTrue(transitions.contains("NoteHydrationFailed"))
        // The coordinator's finishCreated never dispatches on a hydration failure.
        val coordinator = main("core/anki/create/NoteCreationCoordinator.kt").code()
        val finish = coordinator.substringAfter("private suspend fun finishCreated")
            .substringBefore("private suspend fun readCreatedNote")
        assertFalse(finish.contains("createAnkiNote("))
        assertFalse(finish.contains("storeAnkiMedia("))
    }

    // AUDIT-18-11 — one derived save flag, computed in the mapper.
    @Test
    fun canSaveIsASingleDerivedFlagComputedInTheMapper() {
        val mapper = main("ui/screens/addnote/AddNoteMapper.kt").code()
        assertTrue(mapper.contains("val canSave ="))
        val screen = main("ui/screens/addnote/AddNoteScreen.kt").code()
        assertTrue(screen.contains("editor.canSave"))
        // The screen never recomputes the rule.
        assertFalse(screen.contains("validationTokens.isEmpty()"))
    }

    // AUDIT-18-12 — no deck or model creation, no caller deck (CONTRACT-18-05/06).
    @Test
    fun creationNeverCreatesDecksOrModelsAndAcceptsNoCallerDeck() {
        for (file in createSources() + creationDataSources()) {
            val text = file.code()
            for (token in listOf("addDeck(", "createDeck(", "addModel(", "createModel(", "newDeck(")) {
                assertFalse("${file.name} must not create decks/models: $token", text.contains(token))
            }
        }
        // The note insert carries exactly mid/flds/tags — no deck column.
        val mapper = main("data/anki/ankidroid/AnkiDroidCreationMapper.kt").code()
        val mapCreate = mapper.substringAfter("fun mapNoteCreate").substringBefore("fun mapMediaStore")
        assertFalse(mapCreate.contains("deck", ignoreCase = true))
    }

    // AUDIT-18-13 — no bulk add (the pinned backend cannot prove it safe).
    @Test
    fun creationIsOneNoteAtATime() {
        for (file in createSources() + creationDataSources()) {
            val text = file.code()
            for (token in listOf("bulkInsert", "applyBatch", "addNotes(", "batchCreate")) {
                assertFalse("${file.name} must not bulk-add: $token", text.contains(token))
            }
        }
    }

    // AUDIT-18-14 — capability honesty: creation is gated by capabilities, never by names.
    @Test
    fun creationIsGatedByCapabilitiesNotBackendNames() {
        val coordinator = main("core/anki/create/NoteCreationCoordinator.kt").code()
        assertTrue(coordinator.contains("caps.createNotes"))
        assertTrue(coordinator.contains("caps.noteModelListing"))
        assertTrue(coordinator.contains("caps.storeMedia"))
        val mapper = main("ui/screens/addnote/AddNoteMapper.kt").code()
        assertTrue(mapper.contains("capabilities.createNotes"))
        assertTrue(mapper.contains("capabilities.noteModelListing"))
        // No name-based branching anywhere in the creation surface.
        for (file in createSources() + creationDataSources() +
            listOf(main("ui/screens/addnote/AddNoteMapper.kt"), main("ui/screens/addnote/AddNoteViewModel.kt"))) {
            val text = file.code()
            assertFalse("${file.name} must not branch on the backend name",
                Regex("is\\s+AnkiBackendId\\.AnkiDroidLocal").containsMatchIn(text))
        }
    }
}
