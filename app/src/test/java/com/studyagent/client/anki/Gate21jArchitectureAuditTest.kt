package com.studyagent.client.anki

import com.studyagent.client.core.anki.edit.NoteMutationCodec
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21J — repository source audit for note-edit backend binding.
 *
 * The behavioural half lives in `anki/edit/Gate21jNoteEditOwnershipTest` and
 * `anki/edit/NoteMutationRoutingPolicyTest`. This half checks the structural rules a behavioural
 * test cannot see: where a backend may be chosen, what the coordinator may hold, and that no
 * preference, resolver or mode reaches the note-mutation path.
 *
 * Convention follows the GATE 12–21I audits: rules are checked on source text with comment lines
 * stripped, so documentation may name a forbidden spelling while live code may not use it.
 *
 * Labels are this suite's own numbering (AUDIT-21J-01 … 21J-14). The GATE 21J audit list is not
 * in the repository, so the mapping to its canonical IDs is stated in the verification report,
 * not asserted here.
 */
class Gate21jArchitectureAuditTest {

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
        check(file.isFile) { "Missing GATE 21J source $relative" }
        return file
    }

    private val coordinator by lazy { main("core/anki/edit/NoteMutationCoordinator.kt").code() }
    private val routing by lazy { main("core/anki/edit/NoteMutationRoutingPolicy.kt").code() }
    private val editViewModel by lazy { main("ui/screens/editnote/EditNoteViewModel.kt").code() }
    private val editMapper by lazy { main("ui/screens/editnote/EditNoteMapper.kt").code() }
    private val appContainer by lazy { main("di/AppContainer.kt").code() }

    private val noteMutationSources: List<String> by lazy {
        File(mainRoot, "core/anki/edit").listFiles()!!
            .filter { it.isFile && it.extension == "kt" }
            .map { it.code() }
    }

    // ------------------------------------------------------------- AUDIT-21J-01 — no pinned backend

    @Test
    fun `AUDIT-21J-01 the coordinator is built from a registry and holds no single backend`() {
        assertTrue(
            "constructor must take the registry",
            coordinator.contains("private val backends: AnkiBackendRegistry")
        )
        assertFalse(
            "no single backend may be a constructor or stored property",
            Regex("""(private\s+)?val\s+backend\s*:\s*AnkiBackend\b""").containsMatchIn(coordinator)
        )
    }

    // ------------------------------------------------------------- AUDIT-21J-02 — one call site per boundary

    @Test
    fun `AUDIT-21J-02 every backend write and reconciliation is reached through a routed instance`() {
        assertEquals(
            "exactly one write call, inside the single boundary helper",
            1,
            Regex("""\.applyNoteMutation\(""").findAll(coordinator).count()
        )
        assertTrue(
            "the write must be made by callBackend, which receives a routed instance",
            Regex("""private suspend fun callBackend\(backend: AnkiBackend,""").containsMatchIn(coordinator)
        )
        assertTrue(
            "reconciliation is routed as OP-M3 before it reads",
            coordinator.contains("routing.route(record, AnkiOperationClass.OP_M3,")
        )
    }

    @Test
    fun `AUDIT-21J-03 admission, retry, resume and pre-write read each consult the routing policy`() {
        assertTrue("admission", coordinator.contains("routing.admit(base.backendId)"))
        assertTrue(
            "retry and resume run through the owner route",
            Regex("""routeFor\(record\)""").findAll(coordinator).count() >= 2
        )
        assertTrue(
            "the route's pending-binding input is the in-process payload only",
            coordinator.contains("boundIsLiveInThisProcess = pending.containsKey(record.mutationId)")
        )
    }

    @Test
    fun `AUDIT-21J-04 the registry is never walked to pick a backend`() {
        for (forbidden in listOf("backends.all", "backends.first", "backends.firstOrNull", "backends.find {", "backends.toList()")) {
            assertFalse("forbidden selection `$forbidden`", coordinator.contains(forbidden))
        }
        assertFalse(
            "the policy resolves by record identity only",
            Regex("""registry\.(first|firstOrNull|all)""").containsMatchIn(routing)
        )
    }

    // ------------------------------------------------------------- AUDIT-21J-05 — no preference or resolver

    @Test
    fun `AUDIT-21J-05 no preference, mode or resolver reaches the note-mutation path`() {
        val forbidden = listOf(
            "AnkiBackendMode",
            "AnkiBackendResolver",
            "AnkiBackendPreference",
            "resolveBackend(",
            "ankiBackendPreference",
            "preferenceStore",
            "\"AUTO\"",
            ".AUTO",
            "switchBackend"
        )
        for (source in noteMutationSources) {
            for (token in forbidden) {
                assertFalse("edit/ must not reference `$token`", source.contains(token))
            }
        }
        assertFalse(editViewModel.contains("switchBackend"))
        assertFalse(editViewModel.contains("AnkiBackendMode"))
    }

    @Test
    fun `AUDIT-21J-06 the routing policy is pure - no ledger, no backend call, no UI`() {
        for (forbidden in listOf("applyNoteMutation(", "reconcileNoteMutation(", "getCardDetails(", "Ledger", "pending", "viewModelScope", "androidx.")) {
            assertFalse("routing policy must not reference `$forbidden`", routing.contains(forbidden))
        }
    }

    // ------------------------------------------------------------- AUDIT-21J-07 — editor binding

    @Test
    fun `AUDIT-21J-07 the editor's backend is an immutable val with no setter`() {
        assertTrue(
            "the editor holds its owner as a val",
            editViewModel.contains("private val backend: AnkiBackend = initialBackend")
        )
        assertFalse(
            "no mutable backend property",
            Regex("""\bvar\s+backend\b""").containsMatchIn(editViewModel)
        )
    }

    @Test
    fun `AUDIT-21J-08 the container wires the registry and no pinned instance into the note coordinator`() {
        val block = appContainer.substringAfter("DefaultNoteMutationCoordinator(").substringBefore("nowEpochMs")
        assertTrue("registry is passed", block.contains("backends = ankiBackendRegistry"))
        assertFalse(
            "no pinned AnkiDroid instance may be passed as the coordinator's backend",
            block.contains("backend = ankiDroidBackend")
        )
    }

    // ------------------------------------------------------------- AUDIT-21J-09 — deck targets

    @Test
    fun `AUDIT-21J-09 deck decisions compare ids only within the owning backend`() {
        assertTrue(
            "target lookup is scoped to the owner",
            coordinator.contains("it.ref.backendId == backend.id && it.ref.deckId == change.toDeck.deckId")
        )
        assertTrue(
            "source lookup is scoped to the owner",
            coordinator.contains("it.ref.backendId == backend.id && it.ref.deckId == source.deckId")
        )
        assertFalse(
            "no bare deck-id lookup",
            Regex("""firstOrNull\s*\{\s*it\.ref\.deckId\s*==""").containsMatchIn(coordinator)
        )
    }

    @Test
    fun `AUDIT-21J-10 a foreign deck target is a validation error, never a silent drop`() {
        val planner = main("core/anki/edit/NoteEditPlanner.kt").code()
        assertTrue(
            "validateDraft refuses a target on another backend",
            planner.contains("errors += NoteEditValidationError.DeckTargetWrongBackend")
        )
        val validateAt = coordinator.indexOf("NoteEditPlanner.validateDraft")
        val patchAt = coordinator.indexOf("NoteEditPlanner.buildPatch")
        assertTrue("the coordinator validates before it builds a patch", validateAt >= 0 && validateAt < patchAt)
    }

    // ------------------------------------------------------------- AUDIT-21J-11 — durable shape

    @Test
    fun `AUDIT-21J-11 the record carries the admitted collection as an optional, owner-checked field`() {
        val record = main("core/anki/edit/NoteMutationRecord.kt").code()
        assertTrue(
            "optional with a null default, so older snapshots decode",
            record.contains("val collectionRef: AnkiCollectionIdentity? = null")
        )
        assertTrue(
            "validated against the owning backend",
            record.contains("collectionRef == null || collectionRef.backendId == backendId")
        )
    }

    @Test
    fun `AUDIT-21J-12 the codec version and strictness are unchanged`() {
        val ledger = main("core/anki/edit/NoteMutationLedger.kt").code()
        assertEquals(1, NoteMutationCodec.VERSION)
        assertTrue(ledger.contains("const val VERSION: Int = 1"))
        assertTrue(
            "unknown keys still fail closed",
            ledger.contains("ignoreUnknownKeys = false")
        )
    }

    @Test
    fun `AUDIT-21J-13 the GATE 17 status vocabulary is reused, not replaced`() {
        assertEquals(
            listOf("PREPARED", "SUBMITTING", "APPLIED", "RETRY_ALLOWED", "AMBIGUOUS", "CONFLICT"),
            NoteMutationStatus.values().map { it.name }
        )
    }

    @Test
    fun `AUDIT-21J-14 the refusal for a blocked route is shown, not swallowed`() {
        assertTrue(
            "the mapper projects every blocked route",
            editMapper.contains("is NoteMutationRefusal.BackendRouteBlocked ->")
        )
        assertTrue(
            "the mismatch refusal is still a typed refusal",
            coordinator.contains("NoteMutationRefusal.BackendMismatch")
        )
    }
}
