package com.studyagent.client.anki

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21K — repository source audit for note/media creation backend binding.
 *
 * The behavioural half lives in `anki/create/Gate21kNoteCreationOwnershipTest`. This half checks
 * the structural rules a behavioural test cannot see: where a backend may be chosen, what the
 * coordinator may hold, and that no preference, resolver, mode or sibling-family type reaches the
 * creation path.
 *
 * Convention follows the GATE 12–21J audits: rules are checked on source text with comment lines
 * stripped, so documentation may name a forbidden spelling while live code may not use it.
 *
 * Labels are this suite's own numbering (AUDIT-21K-01 … 21K-14); the mapping to the GATE 21K
 * PART II audit list is stated in `docs/GATE_21K_NOTE_CREATION_BINDING.md`.
 */
class Gate21kArchitectureAuditTest {

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
        check(file.isFile) { "Missing GATE 21K source $relative" }
        return file
    }

    private val coordinator by lazy { main("core/anki/create/NoteCreationCoordinator.kt").code() }
    private val routing by lazy { main("core/anki/create/NoteCreationRoutingPolicy.kt").code() }
    private val draft by lazy { main("core/anki/create/AddNoteDraft.kt").code() }
    private val record by lazy { main("core/anki/create/NoteCreationRecord.kt").code() }
    private val backendResults by lazy { main("core/anki/create/NoteCreationBackendResults.kt").code() }
    private val addNoteViewModel by lazy { main("ui/screens/addnote/AddNoteViewModel.kt").code() }

    private val creationSources: List<Pair<String, String>> by lazy {
        File(mainRoot, "core/anki/create").listFiles()!!
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.code() }
    }

    private val addNoteUiSources: List<Pair<String, String>> by lazy {
        File(mainRoot, "ui/screens/addnote").listFiles()!!
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.code() }
    }

    // ------------------------------------------------------- AUDIT-21K-01 — no pinned backend

    @Test
    fun `AUDIT-21K-01 the coordinator is built from a registry and holds no single backend`() {
        assertTrue(
            "constructor must take the registry",
            coordinator.contains("private val backends: AnkiBackendRegistry")
        )
        assertFalse(
            "no single AnkiBackend may be a stored coordinator property",
            coordinator.contains("private val backend: AnkiBackend")
        )
        assertFalse(
            "no AnkiDroid instance may be pinned in the creation engine",
            coordinator.contains("AnkiBackendId.AnkiDroidLocal")
        )
        assertFalse(
            "no PC instance may be pinned in the creation engine",
            coordinator.contains("AnkiBackendId.PcAgent(")
        )
    }

    // --------------------------------------- AUDIT-21K-02 — no preference, mode or resolver input

    @Test
    fun `AUDIT-21K-02 no creation source reads the global preference, a mode, or a resolver`() {
        val forbidden = listOf(
            "BackendPreference",
            "AnkiBackendPreferenceStore",
            "AnkiBackendMode",
            "AnkiBackendResolver",
            "BackendResolutionRequest",
            "BackendResolutionResult"
        )
        creationSources.forEach { (name, code) ->
            forbidden.forEach { token ->
                assertFalse("$name must not reference $token", code.contains(token))
            }
        }
    }

    // ------------------------------- AUDIT-21K-03 — the routing policy is the only registry reader

    @Test
    fun `AUDIT-21K-03 only the routing policy looks a backend up, and only by its own identity`() {
        val lookups = creationSources.flatMap { (name, code) ->
            code.lineSequence()
                .mapIndexed { index, line -> Triple(name, index + 1, line) }
                .filter { it.third.contains("registry.find(") || it.third.contains("backends.find(") }
                .toList()
        }
        assertEquals(
            "exactly the two routing entry points may read the registry: ${lookups.map { "${it.first}:${it.second}" }}",
            2,
            lookups.size
        )
        assertTrue(lookups.all { it.first == "NoteCreationRoutingPolicy.kt" })
        assertTrue(
            "admission looks up the draft context's own backend",
            routing.contains("registry.find(context.backendId)")
        )
        assertTrue(
            "recovery looks up the durable record's own backend",
            routing.contains("registry.find(record.backendId)")
        )
    }

    @Test
    fun `AUDIT-21K-03 the coordinator reaches a backend only through a route`() {
        assertEquals(
            "create, retry and hydrate each take their backend from a Routed verdict",
            3,
            coordinator.lineSequence().count { it.contains("is NoteCreationRoute.Routed -> route.backend") }
        )
        assertFalse(
            "the coordinator never consults the registry itself",
            coordinator.contains("registry.find(") || coordinator.contains("backends.find(")
        )
    }

    // ---------------------------------- AUDIT-21K-04 — one matrix, no family-specific re-decision

    @Test
    fun `AUDIT-21K-04 every verdict is delegated to the locked continuation matrix`() {
        assertTrue(
            "the family states facts and defers the decision",
            routing.contains("AnkiIdentityContinuation.continuationVerdict(operation, check, facts)")
        )
        val reasons = routing.lineSequence()
            .filter { it.contains("AnkiContinuationBlockReason.") }
            .toList()
        assertEquals(
            "the only block reason the family names itself is the unreachable foreign-backend guard: $reasons",
            1,
            reasons.size
        )
        assertTrue(reasons.single().contains("AnkiContinuationBlockReason.FOREIGN_BACKEND"))
    }

    @Test
    fun `AUDIT-21K-04 the family states its own recovery and non-application facts`() {
        assertTrue(
            "DECISION-21K-02 — the pre-write refusal proof is collection-independent",
            routing.contains("nonApplicationProofIsCollectionIndependent = true")
        )
        assertTrue(
            "reconciliation availability is read from the backend's semantics, not asserted",
            routing.contains("backend.noteCreationSemantics.authoritativeCreationReconciliation")
        )
        assertTrue(
            "creation recovery routes by the record's own backend, not by collection proof",
            routing.contains("familyRecoveryRequiresCollectionProof = false")
        )
    }

    // ------------------------------ AUDIT-21K-05/06 — a foreign answer is never claimed as our own

    @Test
    fun `AUDIT-21K-05 a media store or creation answering under a foreign identity fails closed`() {
        assertTrue(
            "media result identity is checked against the invoked backend",
            coordinator.contains("if (result.media.backendId != backend.id)")
        )
        assertTrue(
            "and reported as a distinct, content-free refusal",
            coordinator.contains("\"stored_media_foreign_backend\"")
        )
        assertTrue(
            "creation confirmation identity is checked against the invoked backend",
            coordinator.contains("if (result.noteRef.backendId != backend.id)")
        )
        assertTrue(
            "and never claimed as this backend's note",
            coordinator.contains("\"created_note_foreign_backend\"")
        )
        assertTrue(
            "the routed instance is re-checked against the draft before any dispatch",
            coordinator.contains("if (draft.backendId != backend.id) return Preflight.Refused")
        )
    }

    @Test
    fun `AUDIT-21K-06 field assembly refuses a stored name qualified to another backend`() {
        assertTrue(
            "an identical media name from another backend is never referenced",
            draft.contains("storedMedia.any { it.backendId != draft.backendId }")
        )
        assertTrue(
            "and a contradicting collection key is refused too",
            draft.contains("it.collectionKey != draft.collectionKey")
        )
    }

    // -------------------------------------- AUDIT-21K-07 — the record is the durable qualification

    @Test
    fun `AUDIT-21K-07 the durable record carries and re-attaches its own backend qualification`() {
        assertTrue(record.contains("val backendId: AnkiBackendId"))
        assertTrue(record.contains("val collectionKey: String?"))
        assertTrue(
            "a bare stored name is re-qualified where it leaves the record",
            record.contains("AnkiStoredMediaRef(backendId, it, collectionKey)")
        )
        assertTrue(
            "the boundary contract carries a qualified note reference, never a bare id",
            backendResults.contains("data class ConfirmedCreated(val noteRef: AnkiNoteRef)")
        )
        assertTrue(
            "and a qualified media reference, never a bare name",
            backendResults.contains("data class Stored(val media: AnkiStoredMediaRef)")
        )
    }

    // --------------------------------- AUDIT-21K-08 — no cross-backend or duplicate create retry

    @Test
    fun `AUDIT-21K-08 the creation engine has exactly one note-boundary dispatch site`() {
        assertEquals(
            "one dispatch site means no fallback path can create a second note",
            1,
            coordinator.lineSequence().count { it.contains("backend.createAnkiNote(") }
        )
        assertEquals(
            "and one media-store dispatch site",
            1,
            coordinator.lineSequence().count { it.contains("backend.storeAnkiMedia(") }
        )
        assertEquals(
            "and one hydration read site",
            1,
            coordinator.lineSequence().count { it.contains("backend.resolveCreatedNote(") }
        )
    }

    @Test
    fun `AUDIT-21K-08 the post-create read is a bound read routed by the record's own owner`() {
        assertTrue(
            "hydration is OP-R2 against the record, never a fresh AUTO binding",
            coordinator.contains("routing.route(record, AnkiOperationClass.OP_R2, boundIsLiveInThisProcess = false)")
        )
    }

    // --------------------------------------- AUDIT-21K-09 — orphan-media policy left untouched

    @Test
    fun `AUDIT-21K-09 no creation source deletes or cleans up media`() {
        val forbidden = listOf("deleteMedia", "removeMedia", "deleteAnkiMedia", "cleanupMedia", "purgeMedia")
        creationSources.forEach { (name, code) ->
            forbidden.forEach { token ->
                assertFalse(
                    "$name must not silently clean up orphan media ($token)",
                    code.contains(token)
                )
            }
        }
    }

    // ------------------------------------- AUDIT-21K-10 — the family owns its own identity types

    @Test
    fun `AUDIT-21K-10 no creation source borrows another transaction family's identity`() {
        val forbidden = listOf(
            "NoteMutationStatus",
            "NoteMutationId",
            "ReviewCommitStatus",
            "ReviewCommitId",
            "ReviewerActionStatus",
            "ReviewerActionId",
            "import com.studyagent.client.core.anki.edit."
        )
        creationSources.forEach { (name, code) ->
            forbidden.forEach { token ->
                assertFalse("$name must not reference $token", code.contains(token))
            }
        }
    }

    // ------------------------------------- AUDIT-21K-11 — one backend per draft and per request

    @Test
    fun `AUDIT-21K-11 a draft and a boundary request each refuse a mixed backend identity`() {
        assertTrue(
            "a draft cannot carry another backend's model",
            draft.contains("require(model.ref.backendId == backendId)")
        )
        assertTrue(
            "a draft's model cannot contradict its context's collection",
            draft.contains("model.ref.collectionKey == collectionKey")
        )
        assertTrue(
            "the boundary request refuses a foreign model reference",
            backendResults.contains("require(model.backendId == backendId)")
        )
        assertTrue(
            "the media request carries one backend identity",
            backendResults.contains("val backendId: AnkiBackendId")
        )
    }

    // --------------------------------------- AUDIT-21K-12 — the UI never chooses a backend

    @Test
    fun `AUDIT-21K-12 the add-note UI resolves nothing and picks no backend`() {
        val forbidden = listOf(
            "BackendPreference",
            "AnkiBackendResolver",
            "AnkiBackendRegistry",
            "AnkiBackendMode",
            "BackendResolution"
        )
        addNoteUiSources.forEach { (name, code) ->
            forbidden.forEach { token ->
                assertFalse("$name must not perform backend selection ($token)", code.contains(token))
            }
        }
        assertTrue(
            "the view model receives an already-bound context",
            addNoteViewModel.contains("AnkiReadContextBinding")
        )
        assertTrue(
            "and every write goes through the coordinator",
            addNoteViewModel.contains("coordinator.create(draft)")
        )
        assertTrue(
            "recovery listings are filtered to the bound backend",
            addNoteViewModel.contains("record.backendId == backend.id")
        )
    }

    @Test
    fun `AUDIT-21K-12 the add-note UI surfaces a blocked route as its own refusal`() {
        assertTrue(
            addNoteViewModel.contains("NoteCreationRefusal.BackendRouteBlocked")
        )
    }
}
