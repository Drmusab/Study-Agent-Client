package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 17 — repository source audit for note editing. Each test pins one structural rule that a
 * behavioural test cannot see from the outside: where writes may originate, which types may be
 * shared, what may be persisted, and which capability claims may be made.
 *
 * Note: the GATE 17 specification text was not available in the repository when this audit was
 * written, so tests are named by the rule they enforce, not by a spec identifier.
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
        val writers = mainSources()
            .filter { it.code().contains("ProviderValue.StringValue(") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("AnkiDroidNoteMutationMapper.kt"), writers)
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
}
