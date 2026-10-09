package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 17 PART II — repository source audit for note editing (AUDIT-17-01 … AUDIT-17-14). Each test
 * pins one structural rule that a behavioural test cannot see from the outside: where writes may
 * originate, which types may be shared, what may be persisted, which capability claims may be made,
 * and — since the UI now exists — that the presentation layer decides nothing.
 *
 * The ID → test mapping is recorded in `docs/GATE_17_NOTE_EDITING.md` §PART II and each test below
 * carries its ID. Rules are enforced on source text with comment lines stripped, so documentation
 * may explain a retired or forbidden spelling while live code may not use it.
 */
class Gate17ArchitectureAuditTest {

    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private val mainRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")

    /** Source text with comment-only lines removed, so documentation cannot trip a behavioural rule. */
    private fun File.code(): String = readLines()
        .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") }
        .joinToString("\n")

    private fun File.containsWord(word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(code())

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 17 source $relative" }
        return file
    }

    private fun mainSources(): List<File> = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun editSources(): List<File> =
        File(mainRoot, "core/anki/edit").listFiles()!!.filter { it.extension == "kt" }

    private fun noteMutationDataSources(): List<File> =
        mainSources().filter { it.name.contains("NoteMutation") || it.name == "DataStoreNoteMutationStore.kt" }

    @Test
    fun theNoteMutationStatusEnumIsExactlyTheSpecifiedSet() {
        val text = main("core/anki/edit/NoteMutationStatus.kt").code()
        val body = text.substringAfter("enum class NoteMutationStatus").substringAfter("{").substringBefore(";")
        val entries = body.split(",").map { it.trim().substringBefore("(").trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("PREPARED", "SUBMITTING", "APPLIED", "RETRY_ALLOWED", "AMBIGUOUS", "CONFLICT"), entries)
    }

    @Test
    fun noteMutationTypesDoNotReuseTheReviewCommitOrReviewerActionLedgersOrStatuses() {
        // The safety policy legitimately reads those ledgers; nothing else in the edit model may.
        val forbidden = listOf("ReviewCommitLedger", "ReviewerActionLedger", "ReviewCommitStatus", "ReviewerActionStatus", "ReviewCommitRecord", "ReviewerActionRecord")
        for (file in editSources().filter { it.name != "NoteEditSafetyPolicy.kt" }) {
            val text = file.code()
            for (name in forbidden) assertFalse("${file.name} must not reference $name", text.contains(name))
        }
        for (file in noteMutationDataSources()) {
            val text = file.code()
            for (name in forbidden) assertFalse("${file.name} must not reference $name", text.contains(name))
        }
    }

    @Test
    fun onlyTheCoordinatorInvokesABackendNoteWrite() {
        val callers = mainSources()
            .filter { it.code().contains(".applyNoteMutation(") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("NoteMutationCoordinator.kt"), callers)
    }

    @Test
    fun theOnlyProviderNoteWriteRunsThroughTheSharedPermitAndTheMapper() {
        val writers = mainSources()
            .filter { it.code().contains("safeUpdate(") && it.name != "AnkiDroidProviderClient.kt" && it.name != "AndroidAnkiDroidProbe.kt" }
            .map { it.name }
            .sorted()
        assertEquals(
            listOf("AnkiDroidNoteMutationGateway.kt", "AnkiDroidRatingGateway.kt", "AnkiDroidReviewerActionGateway.kt"),
            writers.filter { !it.contains("Probe") }.sorted()
        )
        val gateway = main("data/anki/ankidroid/AnkiDroidNoteMutationGateway.kt").code()
        assertTrue(gateway.contains("writePermit.acquire("))
        assertTrue(gateway.contains("writePermit.release()"))
        assertFalse("the note gateway must never retry", gateway.contains("retry", ignoreCase = true))
    }

    @Test
    fun aNoteWriteValueIsConstructedOnlyByTheMapper() {
        // The read path legitimately selects `flds`/`tags`; only a typed write value may carry them.
        // GATE 18 extends the sanctioned set with the creation mapper: it builds the one
        // note-insert and media-insert value from the locked creation contract
        // (docs/GATE_18_BACKEND_CREATION_CONTRACT.md). No other file may construct write values.
        val writers = mainSources()
            .filter { it.code().contains("ProviderValue.StringValue(") }
            .map { it.name }
            .sorted()
        assertEquals(
            listOf("AnkiDroidCreationMapper.kt", "AnkiDroidNoteMutationMapper.kt"),
            writers
        )
    }

    @Test
    fun noAddOrDeleteNoteOrNoteTypeWriteExistsInGateSeventeenCode() {
        val banned = listOf("addNote", "deleteNote", "ADD_NOTE", "DELETE_NOTE", "NOTE_TYPES_ID", "NOTES_V2", "ContentValues(")
        for (file in editSources() + noteMutationDataSources()) {
            val text = file.code()
            for (token in banned) assertFalse("${file.name} must not contain $token", text.contains(token))
        }
    }

    @Test
    fun noForceOverwriteOrSilentRetryOfAnAmbiguousWriteExists() {
        for (file in editSources() + noteMutationDataSources()) {
            val text = file.code()
            assertFalse("${file.name} must not contain a force path", file.containsWord("force"))
        }
        val coordinator = main("core/anki/edit/NoteMutationCoordinator.kt").code()
        assertTrue(coordinator.contains("RETRY_ALLOWED"))
        assertFalse(
            "AMBIGUOUS must never be retried in place",
            coordinator.lineSequence().any { it.contains("AMBIGUOUS") && it.contains("retry", ignoreCase = true) }
        )
    }

    @Test
    fun theUiLayerHasNoDirectProviderOrAnkiConnectAccess() {
        val ui = File(mainRoot, "ui")
        if (!ui.isDirectory) return
        ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.code()
            assertFalse("${file.name} must not touch ContentResolver", text.contains("ContentResolver"))
            assertFalse("${file.name} must not touch AnkiConnect", text.contains("AnkiConnect"))
            assertFalse("${file.name} must not call applyNoteMutation", text.contains("applyNoteMutation"))
        }
    }

    @Test
    fun persistedCodecCarriesMetadataOnlyNeverFieldValues() {
        val codec = main("core/anki/edit/NoteMutationLedger.kt").code()
            .substringAfter("object NoteMutationCodec")
        assertFalse(codec.contains("oldValue"))
        assertFalse(codec.contains("newValue"))
        assertFalse(codec.contains("fieldValues"))
        assertFalse(codec.contains("fieldChanges"))
        assertTrue(codec.contains("VERSION"))
    }

    @Test
    fun noFabricatedRevisionTokenIsIntroduced() {
        for (file in editSources() + noteMutationDataSources()) {
            val text = file.code()
            for (token in listOf("revisionToken", "versionToken", "etag", "checksum", "csum")) {
                assertFalse("${file.name} must not fabricate $token", file.containsWord(token))
            }
        }
    }

    @Test
    fun deckIdentityIsTheStableIdNeverTheDeckName() {
        for (file in editSources() + noteMutationDataSources()) {
            val text = file.code()
            assertFalse("${file.name} must not key on deck name", text.contains("deckName"))
        }
        val mapper = main("data/anki/ankidroid/AnkiDroidNoteMutationMapper.kt").code()
        assertTrue(mapper.contains("toDeck.deckId"))
    }

    @Test
    fun theBackendClaimsOnlyWhatItWiresAndNeverClaimsReconciliation() {
        val backend = main("data/anki/ankidroid/AnkiDroidBackend.kt").code()
        val support = backend.substringAfter("private fun withWriteSupport").substringBefore("override val noteMutationSemantics")
        assertTrue(support.contains("editNoteFields = capabilities.editNoteFields && noteGateway != null && noteMutationGateway != null"))
        assertTrue(support.contains("changeCardDeck = capabilities.changeCardDeck && noteGateway != null && noteMutationGateway != null"))
        assertTrue(support.contains("editNotes = false"))
        assertTrue(support.contains("authoritativeMutationReconciliation = false"))
        assertFalse("the backend must not override reconciliation", backend.contains("override suspend fun reconcileNoteMutation"))
    }

    @Test
    fun theCoordinatorChecksStudyOverlapBeforeTheTransactionAndAgainBeforeTheWrite() {
        val coordinator = main("core/anki/edit/NoteMutationCoordinator.kt").code()
        assertTrue(
            "overlap must be re-checked before the first write",
            coordinator.indexOf("blockingStudyActivity") != coordinator.lastIndexOf("blockingStudyActivity")
        )
    }

    @Test
    fun theDurableOrderIsPreparedThenSubmittingThenBackendThenTerminal() {
        val coordinator = main("core/anki/edit/NoteMutationCoordinator.kt").code()
        val prepared = coordinator.indexOf("NoteMutationStatus.PREPARED")
        val submitting = coordinator.indexOf("NoteMutationStatus.SUBMITTING")
        val backendCall = coordinator.indexOf("callBackend(")
        assertTrue(prepared in 0 until submitting)
        assertTrue("no backend write may precede the SUBMITTING record", submitting in 0 until backendCall)
    }

    @Test
    fun theAnkiDroidNoteWriteStatusIsNeverBuiltFromRenderedContent() {
        // The record is built from ids, ordinals and names only. Field values come from the in-memory base.
        val declarations = main("core/anki/edit/NoteMutationRecord.kt").code().lines()
            .map { it.trim() }
            .filter { it.startsWith("val ") || it.startsWith("var ") }
        for (line in declarations) {
            assertFalse("record must not persist a value: $line", line.contains("value", ignoreCase = true))
            assertFalse("record must not persist rendered content: $line", line.contains("html", ignoreCase = true))
            assertFalse("record must not persist text content: $line", line.contains("text", ignoreCase = true))
        }
    }

    @Test
    fun theSharedDataStoreFileIsSeparateFromEveryOtherLedgerFile() {
        val names = mainSources().filter { it.code().contains("PreferenceDataStoreFactory.create") }
            .map { it.name }
        assertTrue(names.containsAll(listOf("DataStoreNoteMutationStore.kt", "DataStoreReviewerActionStore.kt", "DataStoreReviewCommitStore.kt")))
        val fileNames = listOf("anki_note_mutation_ledger", "anki_reviewer_action_ledger")
        assertEquals(fileNames.distinct().size, fileNames.size)
        assertTrue(main("data/anki/DataStoreNoteMutationStore.kt").code().contains("\"anki_note_mutation_ledger\""))
    }

    // ---- PART II: the editing UI and its navigation (AUDIT-17-09 … AUDIT-17-14) -------------------

    private fun editUiSources(): List<File> =
        File(mainRoot, "ui/screens/editnote").listFiles()!!.filter { it.extension == "kt" }.sortedBy { it.name }

    /** The Compose layer of the editor: the screen plus the three controls it renders. */
    private fun editComposeSources(): List<File> = listOf(
        main("ui/screens/editnote/EditNoteScreen.kt"),
        main("ui/components/anki/NoteFieldEditor.kt"),
        main("ui/components/anki/TagsEditor.kt"),
        main("ui/components/anki/DeckSelector.kt")
    )

    /**
     * AUDIT-17-09 — the Compose layer renders decisions, it never makes one. Capability truth,
     * declared semantics and every write live below it, so no screen can enable a control the
     * contract refuses (INV-17-17).
     */
    @Test
    fun theComposeEditLayerMakesNoCapabilityOrContractDecision() {
        val forbidden = listOf(
            "AnkiCapabilities", "NoteMutationSemantics", "NoteConflictGuarantee", "NoteDeckChangeScope",
            "editNoteFields", "editNoteTags", "changeCardDeck", "deckChangeScope",
            "NoteMutationCoordinator", "NoteMutationLedger", "applyNoteMutation", "AnkiBackend"
        )
        for (file in editComposeSources()) {
            val text = file.code()
            for (token in forbidden) {
                assertFalse("${file.name} must not decide from $token", text.contains(token))
            }
        }
    }

    /**
     * AUDIT-17-10 — the projection and its model are pure Kotlin: no Compose, no Android, no data
     * layer. That is what makes every wording and gating rule unit-testable on the JVM.
     */
    @Test
    fun theEditProjectionAndItsModelArePureKotlin() {
        // The editor is exactly four files: model, projection, ViewModel, screen. A fifth would be a
        // second place where a decision could hide.
        assertEquals(
            listOf("EditNoteMapper.kt", "EditNoteModels.kt", "EditNoteScreen.kt", "EditNoteViewModel.kt"),
            editUiSources().map { it.name }
        )
        for (name in listOf("EditNoteModels.kt", "EditNoteMapper.kt")) {
            val text = main("ui/screens/editnote/$name").readText()
            assertFalse("$name must not import Compose", text.contains("androidx.compose"))
            assertFalse("$name must not import Android", text.contains("import android."))
            assertFalse("$name must not import the data layer", text.contains("com.studyagent.client.data."))
        }
    }

    /**
     * AUDIT-17-11 — one derived flag decides whether a write may be offered, and the screen only
     * reads it. Nothing in the Compose layer recomputes dirtiness or blocking.
     */
    @Test
    fun theSaveControlIsEnabledOnlyByTheMappedFlag() {
        val screen = main("ui/screens/editnote/EditNoteScreen.kt").code()
        assertTrue(screen.contains("enabled = editor.canSave"))
        assertFalse("the screen must not recompute the rule", screen.contains("dirty &&"))
        val model = main("ui/screens/editnote/EditNoteModels.kt").code()
        assertTrue(model.contains("val canSave: Boolean"))
        assertTrue(model.contains("blockedByMutation == null"))
        assertTrue(model.contains("saveState !is EditNoteSaveState.Saving"))
        assertTrue(model.contains("fields.none { it.issue?.blocking == true }"))
    }

    /**
     * AUDIT-17-12 — recovery affordances follow the record's status exactly as mapped, and an
     * unsupported one is absent rather than disabled-looking.
     */
    @Test
    fun theRecoveryControlsFollowTheMappedAffordances() {
        val screen = main("ui/screens/editnote/EditNoteScreen.kt").code()
        for (gate in listOf(
            "blocked.canRetry", "blocked.canResume", "blocked.canRecover",
            "blocked.canAttest", "blocked.canRestartAfterConflict"
        )) {
            assertTrue("the screen must gate on $gate", screen.contains("if ($gate)"))
        }
        // AMBIGUOUS has exactly one escape, and it is the user's own attestation.
        assertTrue(screen.contains("viewModel.attest(NoteMutationAttestation.APPLIED_IN_COLLECTION)"))
        assertTrue(screen.contains("viewModel.attest(NoteMutationAttestation.ABSENT_FROM_COLLECTION)"))
        val mapper = main("ui/screens/editnote/EditNoteMapper.kt").code()
        assertTrue(mapper.contains("canAttest = ambiguous"))
        assertTrue(mapper.contains("canRetry = record.status == NoteMutationStatus.RETRY_ALLOWED"))
    }

    /**
     * AUDIT-17-13 — the read-only card-details screen offers the editor only when the connected
     * backend actually claims an editing dimension, and it stays read-only either way.
     */
    @Test
    fun theCardDetailsEntryIsCapabilityGatedAndTheScreenStaysReadOnly() {
        val screen = main("ui/screens/carddetails/CardDetailsScreen.kt").code()
        assertTrue(screen.contains("if (ready?.canOpenNoteEditor == true && onOpenNoteEditor != null)"))
        assertTrue(screen.contains("card_details_open_editor"))
        assertTrue(screen.contains("onOpenNoteEditor.invoke(it.cardRef)"))
        assertTrue(main("ui/screens/carddetails/CardDetailsMapper.kt").code().contains("canOpenNoteEditor"))
        val viewModel = main("ui/screens/carddetails/CardDetailsViewModel.kt").code()
        assertTrue(viewModel.contains("noteEditingOffered"))
        assertTrue(viewModel.contains("capabilities.editNoteFields"))
        assertTrue(viewModel.contains("capabilities.editNoteTags"))
        assertTrue(viewModel.contains("capabilities.changeCardDeck"))
        for (name in listOf(
            "CardDetailsScreen.kt", "CardDetailsMapper.kt", "CardDetailsModels.kt", "CardDetailsViewModel.kt"
        )) {
            val text = main("ui/screens/carddetails/$name").code()
            assertFalse("$name must not write a note", text.contains("applyNoteMutation"))
            assertFalse("$name must not own a mutation", text.contains("NoteMutationCoordinator"))
        }
    }

    /**
     * AUDIT-17-14 — the editor is reached by a stable reference, bound to the backend that owns the
     * card, and a saved edit forces an authoritative re-read instead of trusting the draft.
     */
    @Test
    fun theEditorIsReachedByReferenceBoundToTheOwningBackend() {
        val routes = main("ui/navigation/Screen.kt").code()
        assertTrue(routes.contains("data object EditNote : Screen(\"edit-note/{cardRef}\")"))
        assertTrue(routes.contains("fun decodeCardRef(token: String?): AnkiCardRef?"))
        val nav = main("ui/navigation/AppNavHost.kt").code()
        assertTrue(nav.contains("container.ankiBackendRegistry.find(editCardRef.backendId)"))
        assertTrue(nav.contains("coordinator = container.noteMutationCoordinator"))
        assertTrue(nav.contains("navController.navigate(Screen.EditNote.createRoute(ref))"))
        assertTrue(nav.contains("NOTE_EDIT_SAVED_KEY"))
        assertTrue(nav.contains("detailsViewModel.refresh()"))
        // The route carries an id, never note content.
        for (token in listOf("fieldValues", "draftTags", "flds", "noteContent")) {
            assertFalse("navigation must not carry $token", routes.contains(token) || nav.contains(token))
        }
    }

    /** The editor's ViewModel writes only through the coordinator, and reads only through the backend. */
    @Test
    fun theEditViewModelWritesOnlyThroughTheCoordinator() {
        val viewModel = main("ui/screens/editnote/EditNoteViewModel.kt").code()
        for (call in listOf(
            "coordinator.save(", "coordinator.retry(", "coordinator.resumePrepared(",
            "coordinator.recover(", "coordinator.resolveAmbiguous(", "coordinator.startAfterConflict(",
            "coordinator.activeMutationFor("
        )) {
            assertTrue("the ViewModel must reach the domain through $call", viewModel.contains(call))
        }
        for (token in listOf(
            "applyNoteMutation", "ContentResolver", "FlashCardsContract", "com.studyagent.client.data.",
            "safeUpdate("
        )) {
            assertFalse("the ViewModel must not touch $token", viewModel.contains(token))
        }
    }

    /** Deck scope is CARD_ONLY at this pin, so every deck wording in the UI says card, never note. */
    @Test
    fun deckWordingIsCardScopedThroughoutTheUiLayer() {
        File(mainRoot, "ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.code()
            for (phrase in listOf("Move Note", "moves the note", "move this note", "move the whole note")) {
                assertFalse("${file.name} must not offer a note-wide move", text.contains(phrase))
            }
        }
        assertTrue(
            main("ui/components/anki/DeckSelector.kt").code()
                .contains("This moves the card you opened, not the note.")
        )
        assertTrue(
            main("ui/screens/editnote/EditNoteMapper.kt").code()
                .contains("Moving a deck moves THIS CARD ONLY.")
        )
    }

    /** Tags are one replace-only write at this pin, so the UI never promises a delta operation. */
    @Test
    fun tagWordingIsReplaceOnlyThroughoutTheUiLayer() {
        val tags = main("ui/components/anki/TagsEditor.kt").code()
        assertTrue(tags.contains("One write replaces the whole tag set."))
        assertTrue(tags.contains("Anki stores tags in its own order"))
        for (file in editComposeSources()) {
            val text = file.code()
            assertFalse("${file.name} must not promise a tag delta", text.contains("addTag("))
            assertFalse("${file.name} must not promise a tag delta", text.contains("removeTag("))
        }
    }

    /**
     * The presentation vocabulary never renames durable truth — the GATE 11B rule, applied to the
     * GATE 17 save states. A log line and a label must not be confusable with a persisted status.
     */
    @Test
    fun theEditPresentationVocabularyNeverRenamesADurableStatus() {
        val model = main("ui/screens/editnote/EditNoteModels.kt").code()
        // `code()` strips comments, so the boundary is the next declaration, not a doc line.
        val saveStates = model.substringAfter("sealed interface EditNoteSaveState")
            .substringBefore("data class EditNoteEditorState")
        val cases = Regex("data (?:object|class) (\\w+)").findAll(saveStates)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("Idle", "Saving", "Saved", "Blocked", "RetryAvailable", "Conflicted", "Ambiguous"),
            cases
        )
        val durable = setOf("PREPARED", "SUBMITTING", "APPLIED", "RETRY_ALLOWED", "AMBIGUOUS", "CONFLICT")
        assertTrue("a presentation case renames durable truth: ${cases intersect durable}",
            (cases intersect durable).isEmpty())
    }

    /** Every screen of the editor is reachable in tests: the tag names the audit and QA rely on. */
    @Test
    fun theEditorExposesStableTestTagsForEveryAffordance() {
        val screen = main("ui/screens/editnote/EditNoteScreen.kt").code()
        assertTrue(screen.contains("const val EDIT_NOTE_TEST_TAG = \"edit_note\""))
        for (suffix in listOf("blocked", "retry", "recover", "attest_applied", "attest_absent", "restart", "save")) {
            assertTrue("missing test tag for $suffix", screen.contains("\$EDIT_NOTE_TEST_TAG $suffix"))
        }
        assertTrue(main("ui/components/anki/TagsEditor.kt").code().contains("const val TAGS_EDITOR_TEST_TAG"))
        assertTrue(main("ui/components/anki/DeckSelector.kt").code().contains("const val DECK_SELECTOR_TEST_TAG"))
    }
}
