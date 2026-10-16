package com.studyagent.client.anki

import com.studyagent.client.anki.edit.FakeNoteMutationBackend
import com.studyagent.client.anki.edit.Gate21jBackends
import com.studyagent.client.anki.edit.Gate21jIds
import com.studyagent.client.anki.edit.Gate21jWorld
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationOutcome
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21J — PART II, the independent architecture audit, **using the gate brief's canonical IDs**
 * (AUDIT-21J-01 … AUDIT-21J-25).
 *
 * Two kinds of evidence are used, and each audit says which it leans on:
 *
 * - **Source audit** — the rule is a property of the repository text (where a backend may be chosen,
 *   what a component may hold, which contract module decides a cell). Comment lines are stripped
 *   first, so documentation may name a forbidden spelling while live code may not use it.
 * - **Behaviour** — the rule is re-derived by driving the two colliding fake backends
 *   (`Gate21jBackends`); it points at the verification suite for the full scenario.
 *
 * The older `Gate21jArchitectureAuditTest` keeps its own `SRC-21J-*` numbering (structural checks
 * written before the brief's list was available); this class is the canonical mapping. Where an audit
 * duplicates a check the report says so rather than pretending the coverage is new.
 */
class Gate21jCanonicalAuditTest {

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

    /** Source with comments intact: for rules that are *about* the documentation. */
    private fun rawMain(relative: String): String = main(relative).readText()

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 21J source $relative" }
        return file
    }

    private fun editSources(): List<String> =
        File(mainRoot, "core/anki/edit").listFiles()!!
            .filter { it.isFile && it.extension == "kt" }
            .map { it.code() }

    private val coordinator by lazy { main("core/anki/edit/NoteMutationCoordinator.kt").code() }
    private val routing by lazy { main("core/anki/edit/NoteMutationRoutingPolicy.kt").code() }
    private val record by lazy { main("core/anki/edit/NoteMutationRecord.kt").code() }
    private val transitions by lazy { main("core/anki/edit/NoteMutationTransition.kt").code() }
    private val planner by lazy { main("core/anki/edit/NoteEditPlanner.kt").code() }
    private val editViewModel by lazy { main("ui/screens/editnote/EditNoteViewModel.kt").code() }
    private val editMapper by lazy { main("ui/screens/editnote/EditNoteMapper.kt").code() }
    private val nav by lazy { main("ui/navigation/AppNavHost.kt").code() }
    private val screen by lazy { main("ui/navigation/Screen.kt").code() }
    private val ledger by lazy { main("core/anki/edit/NoteMutationLedger.kt").code() }
    private val ankiDroidBackend by lazy { main("data/anki/ankidroid/AnkiDroidBackend.kt").code() }

    // ------------------------------------------------------------------ AUDIT-21J-01

    @Test
    fun `AUDIT-21J-01 the ownership rules are the locked 21B and GATE 17 contracts`() {
        // The note family decides its cells with the shared transcription of locked §3.3 …
        assertTrue(
            "the policy must decide through the locked identity continuation",
            routing.contains("AnkiIdentityContinuation.continuationVerdict(operation, check, facts)")
        )
        assertTrue(
            "and must compute assurance with the locked 21C algorithm",
            routing.contains("AnkiIdentityAssurancePolicy.check(")
        )
        // … and the 21B output contract's vocabulary is present verbatim.
        assertTrue(coordinator.contains("NoteMutationRefusal.BackendMismatch"))
        assertTrue(coordinator.contains("NoteMutationRefusal.BackendRouteBlocked"))
        assertEquals(
            listOf("PREPARED", "SUBMITTING", "APPLIED", "RETRY_ALLOWED", "AMBIGUOUS", "CONFLICT"),
            NoteMutationStatus.values().map { it.name }
        )
        // "Drafts are never migrated": nothing in the family re-binds an existing draft to an owner.
        for (source in editSources()) {
            assertFalse("no draft migration", source.contains("migrate"))
            assertFalse("no identity re-pointing", source.contains("repoint"))
        }
    }

    // ------------------------------------------------------------------ AUDIT-21J-02

    @Test
    fun `AUDIT-21J-02 the GATE 17 coordinator and ledger remain the single mutation authority`() {
        val editFiles = File(mainRoot, "core/anki/edit").listFiles()!!.filter { it.extension == "kt" }.map { it.name }
        assertEquals(
            "exactly one ledger implementation in the family",
            1,
            editFiles.count { it == "NoteMutationLedger.kt" }
        )
        assertEquals(
            "exactly one coordinator implementation in the family",
            1,
            editFiles.count { it == "NoteMutationCoordinator.kt" }
        )
        // The write is reachable from exactly one place, and the screen never reaches the backend.
        assertEquals(1, Regex("""\.applyNoteMutation\(""").findAll(coordinator).count())
        assertFalse(editViewModel.contains("applyNoteMutation("))
        assertFalse(editMapper.contains("applyNoteMutation("))
        assertTrue("the state machine is the GATE 17 one", transitions.contains("NoteMutationTransitions"))
    }

    // ------------------------------------------------------------------ AUDIT-21J-03

    @Test
    fun `AUDIT-21J-03 an editor cannot switch its BackendId`() {
        assertTrue(editViewModel.contains("private val backend: AnkiBackend = initialBackend"))
        assertFalse(Regex("""\bvar\s+backend\b""").containsMatchIn(editViewModel))
        assertFalse(editViewModel.contains("switchBackend"))
        assertTrue(editViewModel.contains("card_ref_foreign_backend"))
        // The route resolves the editor's owner from the qualified reference itself.
        assertTrue(nav.contains("container.ankiBackendRegistry.find(editCardRef.backendId)"))
        assertTrue(screen.contains("edit-note/"))
    }

    // ------------------------------------------------------------------ AUDIT-21J-04

    @Test
    fun `AUDIT-21J-04 an admitted transaction's destination is immutable`() {
        assertTrue(record.contains("val backendId: AnkiBackendId"))
        for (source in editSources()) {
            assertFalse(
                "no record is ever copied onto another owner",
                Regex("""\.copy\([^)]*backendId""").containsMatchIn(source)
            )
        }
        assertTrue(
            "the route is always the record's own backend",
            routing.contains("registry.find(record.backendId)")
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-05

    @Test
    fun `AUDIT-21J-05 note, card and deck references stay backend and collection qualified`() {
        val refs = main("core/anki/AnkiRefs.kt").code()
        assertTrue(refs.contains("data class AnkiNoteRef"))
        assertTrue(refs.contains("data class AnkiCardRef"))
        assertTrue(refs.contains("data class AnkiDeckRef"))
        // Four references (note, card, deck, and — since GATE 21K landed the DECISION-21B-04 media
        // qualification — stored media) plus the collection identity itself, the fifth owner-bearing
        // type. Every one of them carries its owner and its collection evidence.
        assertTrue(refs.contains("data class AnkiStoredMediaRef"))
        assertEquals("every reference carries its owner", 5, Regex("""val backendId: AnkiBackendId""").findAll(refs).count())
        assertEquals("every reference carries its collection evidence", 4, Regex("""val collectionKey: String\? = null""").findAll(refs).count())
        assertTrue(refs.contains("identityKey(backendId.stableId, collectionKey"))

        val pair = Gate21jBackends()
        assertNotEquals(
            "identical numeric ids are different resources",
            pair.a.noteRef().stableKey,
            pair.b.noteRef().stableKey
        )
        assertNotEquals(pair.a.cardRef().stableKey, pair.b.cardRef().stableKey)
        assertTrue(pair.registryOfNoteIsItsOwnOwner())
    }

    private fun Gate21jBackends.registryOfNoteIsItsOwnOwner(): Boolean {
        val registry = AnkiBackendRegistry(all())
        return registry.find(a.cardRef().backendId) === a && registry.find(b.cardRef().backendId) === b
    }

    // ------------------------------------------------------------------ AUDIT-21J-06

    @Test
    fun `AUDIT-21J-06 every write and recovery read is routed to the exact instance`() = runBlocking {
        assertTrue(coordinator.contains("private suspend fun callBackend(backend: AnkiBackend,"))
        assertTrue(coordinator.contains("routing.admit(base.backendId)"))
        assertTrue(coordinator.contains("routeFor(record)"))

        // Behaviour: the same numeric ids on the other backend never receive the operation.
        val w = Gate21jWorld(Gate21jBackends())
        val outcome = w.coordinator.save(w.base(), NoteEditDraft(fieldValues = mapOf(0 to "A")))
        assertTrue(outcome is NoteMutationOutcome.Applied)
        assertEquals(1, w.pair.a.applyCalls.size)
        assertEquals(0, w.pair.b.applyCalls.size)
    }

    // ------------------------------------------------------------------ AUDIT-21J-07

    @Test
    fun `AUDIT-21J-07 no preference or AUTO resolution exists in the note path`() {
        val forbidden = listOf(
            "AnkiBackendMode", "AnkiBackendResolver", "AnkiBackendPreference",
            "BackendPreference", "resolveBackend(", "preferenceStore", "AnkiBackendSelector", ".AUTO", "AUTO"
        )
        for (source in editSources() + editViewModel + editMapper) {
            for (token in forbidden) {
                assertFalse("the note path must not reference `$token`", source.contains(token))
            }
        }
        // Recovery is per record, and the registry is never walked to find a substitute.
        for (forbiddenWalk in listOf("registry.registered", "backends.registered", "registry.states()", "registry.descriptors()")) {
            assertFalse("no fan-out: $forbiddenWalk", coordinator.contains(forbiddenWalk))
        }
    }

    // ------------------------------------------------------------------ AUDIT-21J-08

    @Test
    fun `AUDIT-21J-08 an unsupported operation is refused rather than substituted`() {
        assertTrue(
            "the coordinator refuses an unadvertised dimension before the patch",
            coordinator.contains("unsupportedDimensionFor(patch, backend)")
        )
        assertTrue(
            "the AnkiDroid adapter refuses a step its capabilities do not advertise",
            ankiDroidBackend.contains("val allowed = when (val step = request.step)")
        )
        assertTrue(ankiDroidBackend.contains("NoteMutationBackendResult.ConfirmedNotApplied"))
    }

    // ------------------------------------------------------------------ AUDIT-21J-09

    @Test
    fun `AUDIT-21J-09 field and model identity are never borrowed from another backend`() {
        assertTrue(
            "the base carries its own note type",
            main("core/anki/edit/NoteEditModels.kt").code().contains("val noteTypeId: String?")
        )
        assertTrue(
            "field identity is the authoritative ordinal",
            planner.contains("if (ordinal !in base.fields.indices)")
        )
        for (source in editSources()) {
            assertFalse("no model listing is consulted by an edit", source.contains("getNoteModel"))
        }
        // Behaviour: A's schema governs A's note even under an identical model id.
        assertEquals(Gate21jIds.MODEL, Gate21jBackends().a.noteTypeId)
    }

    // ------------------------------------------------------------------ AUDIT-21J-10

    @Test
    fun `AUDIT-21J-10 tag writes follow the pinned replace-only contract`() {
        assertTrue(
            "the whole-set replace claim is declared for AnkiDroid",
            main("core/anki/edit/NoteMutationBackendResult.kt").code()
                .contains("tagWriteModel = NoteTagWriteModel.REPLACE_ONLY_CANONIFIED")
        )
        assertTrue("the draft's tag set is normalized before it becomes a patch", planner.contains("normalizeTags"))
        // The enum member documents what the pin does NOT offer; what must not exist is a *use* of it.
        for (source in editSources()) {
            assertFalse(
                "no add/remove delta path exists in the family",
                source.contains("NoteTagWriteModel.ADD_REMOVE_DELTA")
            )
        }
        assertFalse(
            "the adapter has no separate add/remove tag call",
            File(mainRoot, "data/anki/ankidroid/AnkiDroidNoteMutationGateway.kt").code()
                .let { it.contains("addTag") || it.contains("removeTag") }
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-11

    @Test
    fun `AUDIT-21J-11 deck operations stay card-scoped and owner-scoped`() = runBlocking {
        assertTrue(
            "the plan's deck operation is card-scoped by name and payload",
            rawMain("core/anki/edit/NoteEditModels.kt").contains("Card-scoped only; never a note-wide move")
        )
        assertTrue(
            "the coordinator refuses any other scope",
            coordinator.contains("noteMutationSemantics.deckChangeScope != NoteDeckChangeScope.CARD_ONLY")
        )
        assertTrue(
            "deck ids are compared only inside the owner",
            coordinator.contains("it.ref.backendId == backend.id && it.ref.deckId == change.toDeck.deckId")
        )

        val w = Gate21jWorld(Gate21jBackends())
        w.coordinator.save(w.base(), NoteEditDraft(targetDeck = AnkiDeckRef(w.pair.aId, Gate21jIds.OTHER_DECK)))
        assertEquals(Gate21jIds.OTHER_DECK, w.pair.a.deckOfCard(Gate21jIds.CARD))
        assertEquals(Gate21jIds.DECK, w.pair.a.deckOfCard(Gate21jIds.SIBLING_CARD))
    }

    // ------------------------------------------------------------------ AUDIT-21J-12

    @Test
    fun `AUDIT-21J-12 composite saves claim no unproven atomicity`() {
        assertTrue("the plan is an ordered list of operations", main("core/anki/edit/NoteEditModels.kt").code().contains("val operations: List<NoteMutationOperation>"))
        assertTrue(
            "atomicity is a per-backend claim, not an assumption",
            main("core/anki/edit/NoteMutationBackendResult.kt").code().contains("val contentWriteAtomic: Boolean")
        )
        assertTrue(
            "APPLIED needs a verification over ALL steps",
            coordinator.contains("NoteMutationVerifier.verify(steps, refreshed, record.backendId)")
        )
        assertTrue(
            "a failure after an earlier step is ambiguous, never retryable",
            coordinator.contains("return markAmbiguous(current)")
        )
        assertTrue(transitions.contains("NoteMutationReason.PARTIAL_OPERATION_UNKNOWN"))
    }

    // ------------------------------------------------------------------ AUDIT-21J-13

    @Test
    fun `AUDIT-21J-13 collection assurance follows the locked rule, with null never a match`() {
        assertTrue(routing.contains("AnkiIdentityAssurancePolicy.check("))
        assertTrue(
            "the family's facts are stated, not invented per cell",
            routing.contains("nonApplicationProofIsCollectionIndependent = true")
        )
        assertTrue(
            "a backend's own semantics decide the reconciliation claim",
            routing.contains("authoritativeReconciliationAvailable = backend.noteMutationSemantics.authoritativeReconciliation")
        )
        assertTrue(
            "unknown is never a wildcard",
            main("core/anki/AnkiRefs.kt").code().contains("val hasAuthoritativeKey: Boolean get() = collectionKey != null")
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-14

    @Test
    fun `AUDIT-21J-14 CONFLICT requires evidence and is distinct from AMBIGUOUS`() = runBlocking {
        assertTrue(
            "a conflict is only recorded when the base actually drifted",
            coordinator.contains("if (latest != payload.base)")
        )
        assertTrue(
            "an unknown outcome records ambiguity, not conflict",
            transitions.contains("NoteMutationEvent.BackendOutcomeUnknown ->")
        )
        // Behaviour: the two outcomes are different durable statuses, from different causes.
        val conflictWorld = Gate21jWorld(Gate21jBackends())
        conflictWorld.pair.a.beforeRead = { fake -> fake.fields[0] = fake.fields[0].copy(value = "theirs") }
        val conflict = conflictWorld.coordinator.save(
            conflictWorld.base(),
            NoteEditDraft(fieldValues = mapOf(0 to "mine"))
        )
        assertEquals(NoteMutationStatus.CONFLICT, (conflict as NoteMutationOutcome.Conflict).record.status)

        val ambiguousWorld = Gate21jWorld(Gate21jBackends())
        ambiguousWorld.pair.a.applyHook = { _, _ ->
            com.studyagent.client.core.anki.edit.NoteMutationBackendResult
                .OutcomeUnknown(AnkiError.Unknown("lost"))
        }
        val ambiguous = ambiguousWorld.coordinator.save(
            ambiguousWorld.base(),
            NoteEditDraft(fieldValues = mapOf(0 to "mine"))
        )
        assertEquals(
            NoteMutationStatus.AMBIGUOUS,
            (ambiguous as NoteMutationOutcome.VerificationRequired).record.status
        )
        assertNotEquals(conflict.record.status, ambiguous.record.status)
    }

    // ------------------------------------------------------------------ AUDIT-21J-15

    @Test
    fun `AUDIT-21J-15 no irreversible Save precedes the durable record and intent`() {
        val createAt = coordinator.indexOf("ledger.create(record)")
        val boundaryAt = coordinator.indexOf("NoteMutationEvent.EnterMutationBoundary")
        val writeAt = coordinator.indexOf("callBackend(backend, request)")
        assertTrue("the record is persisted before the boundary", createAt in 1 until boundaryAt)
        assertTrue("the boundary is durable before the write", boundaryAt in 1 until writeAt)
        assertEquals(
            "one write call site (the declaration plus a single call would be two matches)",
            1,
            Regex("""callBackend\(backend, request\)""").findAll(coordinator).count()
        )
        assertTrue(coordinator.contains("write_not_committed") || ledger.contains("write_not_committed"))
    }

    // ------------------------------------------------------------------ AUDIT-21J-16

    @Test
    fun `AUDIT-21J-16 a possibly applied mutation is never replayed blindly`() = runBlocking {
        assertTrue(
            "retry refuses anything that is not RETRY_ALLOWED, before any transition",
            coordinator.indexOf("if (record.status != NoteMutationStatus.RETRY_ALLOWED)") <
                coordinator.indexOf("NoteMutationStatus.RETRY_ALLOWED, NoteMutationEvent.RetryRequested")
        )
        assertTrue(
            "recovery of a submitted-but-unrecorded write normalizes to ambiguity",
            coordinator.contains("NoteMutationEvent.RecoveryNormalizedUnknown")
        )
        val w = Gate21jWorld(Gate21jBackends())
        w.pair.a.applyHook = { request, fake ->
            fake.applyFaithfully(request)
            com.studyagent.client.core.anki.edit.NoteMutationBackendResult
                .OutcomeUnknown(AnkiError.Unknown("lost"))
        }
        val ambiguous = (w.coordinator.save(w.base(), NoteEditDraft(fieldValues = mapOf(0 to "once")))
            as NoteMutationOutcome.VerificationRequired).record
        w.pair.a.applyHook = null
        assertEquals(
            NoteMutationOutcome.Refused(
                com.studyagent.client.core.anki.edit.NoteMutationRefusal
                    .RetryNotAllowed(NoteMutationStatus.AMBIGUOUS)
            ),
            w.coordinator.retry(ambiguous.mutationId)
        )
        assertEquals("nothing was replayed", 1, w.pair.a.applyCalls.size)
    }

    // ------------------------------------------------------------------ AUDIT-21J-17

    @Test
    fun `AUDIT-21J-17 retry permission is an evidence decision`() {
        assertTrue(
            "retry requires the backend's proven non-application",
            transitions.contains("NoteMutationEvent.BackendConfirmedNotApplied")
        )
        assertTrue(
            "reconciliation is the only automatic exit from ambiguity",
            transitions.contains("NoteMutationEvent.ReconciliationConfirmedApplied") &&
                transitions.contains("NoteMutationEvent.ReconciliationConfirmedNotApplied")
        )
        assertTrue(
            "a human attestation is recorded as an attestation",
            transitions.contains("NoteMutationReason.USER_ATTESTED_APPLIED")
        )
        assertTrue(
            "connectivity is not modelled as evidence anywhere in the family",
            editSources().none { it.contains("isConnected") || it.contains("reconnect(") }
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-18

    @Test
    fun `AUDIT-21J-18 unresolved transactions on different backends stay independent`() {
        assertTrue(
            "recovery asks exactly one owner",
            coordinator.contains("routing.route(record, AnkiOperationClass.OP_M3, pending.containsKey(record.mutationId))")
        )
        assertTrue(
            "the active lookup is keyed by owner and note",
            ledger.contains("findActiveForNote") && ledger.contains("it.backendId == backendId")
        )
        assertFalse("recovery is per record, never a sweep", coordinator.contains("unresolved("))
        assertTrue(
            "the only record-wide pass is the ledger's own startup normalization",
            ledger.contains("normalizeAfterRestart")
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-19

    @Test
    fun `AUDIT-21J-19 a historical record without provable ownership is never guessed`() {
        assertTrue("unknown keys fail closed", ledger.contains("ignoreUnknownKeys = false"))
        assertTrue("a corrupt snapshot is a refusal", ledger.contains("snapshot_corrupt"))
        assertTrue("the owner comes from the record", routing.contains("registry.find(record.backendId)"))
        assertTrue(
            "a missing owner is a typed block, not a fallback",
            routing.contains("TOKEN_NOT_REGISTERED") &&
                routing.contains("const val TOKEN_NOT_REGISTERED: String = \"backend_not_registered\"")
        )
        assertTrue(
            "the optional field defaults, so pre-21J bytes decode",
            record.contains("val collectionRef: AnkiCollectionIdentity? = null")
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-20

    @Test
    fun `AUDIT-21J-20 a stale result cannot be published into a newer editor session`() {
        val guards = Regex("""if \(token != generation\) return@launch""").findAll(editViewModel).count()
        assertTrue("every suspending editor action re-checks its generation (found $guards)", guards >= 5)
        assertTrue(
            "a save captures the generation it was issued under",
            editViewModel.contains("val token = generation")
        )
        assertTrue(
            "the screen's saved signal is only raised by the current generation's outcome",
            editViewModel.contains("_savedCardRef.value =")
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-21

    @Test
    fun `AUDIT-21J-21 refresh after a mutation stays inside the confirmed owner`() {
        assertTrue(
            "the refresh is the owner's own post-write read",
            coordinator.contains("readForVerification(backend, record)")
        )
        assertTrue(
            "the editor refreshes through its immutable backend",
            editViewModel.contains("backend.getCardDetails(cardRef)")
        )
        // The ledger's own `cache` is its loaded mirror of the durable snapshot, not a read cache of
        // backend resources. What must not exist is a *resource* cache keyed by bare numeric ids.
        for (source in editSources() + editViewModel) {
            for (token in listOf("LibrarySnapshot", "NoteCache", "noteCache", "ResourceCache", "CardCache")) {
                assertFalse("no second cache system: `$token`", source.contains(token))
            }
        }
    }

    // ------------------------------------------------------------------ AUDIT-21J-22

    @Test
    fun `AUDIT-21J-22 a draft is not a transaction, and discarding one cannot delete one`() {
        assertTrue(
            "the durable record holds metadata, not values",
            rawMain("core/anki/edit/NoteMutationRecord.kt").contains("It never stores field values, tag")
        )
        assertFalse("no field value type is persisted", record.contains("newValue"))
        assertTrue(
            "the in-process payload is explicitly memory-only",
            rawMain("core/anki/edit/NoteMutationCoordinator.kt").let { text ->
                text.contains("It holds note") && text.contains("content, so it is never persisted")
            }
        )
        assertTrue(
            "an unresolved record blocks the editor rather than being dropped",
            editViewModel.contains("activeMutationFor")
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-23

    @Test
    fun `AUDIT-21J-23 no credential or private content leaks through the new surface`() {
        for (source in editSources() + editViewModel + editMapper) {
            for (secret in listOf("password", "apiKey", "api_key", "credential", "secret", "bearer")) {
                assertFalse("edit surface must not mention `$secret`", source.contains(secret, ignoreCase = true))
            }
        }
        assertTrue(
            "the blocked-route projection carries a token, never a payload",
            editMapper.contains("is NoteMutationRefusal.BackendRouteBlocked ->")
        )
        assertFalse(
            "refusal copy never interpolates note content",
            editMapper.contains("newValue")
        )
    }

    // ------------------------------------------------------------------ AUDIT-21J-24

    @Test
    fun `AUDIT-21J-24 the earlier gates keep their own authority`() {
        // 21H / 21I families and the 21C identity seams are untouched by this gate.
        assertTrue(File(mainRoot, "core/anki/ReviewCommitRoutingPolicy.kt").isFile)
        assertTrue(File(mainRoot, "core/anki/ReviewerActionRoutingPolicy.kt").isFile)
        assertTrue(File(mainRoot, "core/anki/AnkiIdentityContinuation.kt").isFile)
        assertTrue(File(mainRoot, "core/anki/AnkiTransactionRecoveryRouting.kt").isFile)
        // GATE 17's suite is still the transaction contract, and 21J changed no assertion in it.
        val coordinatorTest = File(appRoot, "src/test/java/com/studyagent/client/anki/edit/NoteMutationCoordinatorTest.kt")
        assertTrue(coordinatorTest.isFile)
        assertTrue(coordinatorTest.code().contains("reconcilingHarness"))
    }

    // ------------------------------------------------------------------ AUDIT-21J-25

    @Test
    fun `AUDIT-21J-25 no creation, migration or offline replay infrastructure was added`() {
        val forbidden = listOf(
            "NoteCreation", "CreateNote", "crossBackendNote", "migrateNote", "offlineQueue",
            "replayQueue", "pendingMutationQueue", "ModelEditor", "TemplateEditor", "mergeNotes"
        )
        for (source in editSources() + editViewModel + editMapper) {
            for (token in forbidden) {
                assertFalse("out of scope for 21J: `$token`", source.contains(token))
            }
        }
        // The creation family still owns its own ledger and status vocabulary.
        assertTrue(File(mainRoot, "core/anki/create").isDirectory)
        val creationLedger = File(mainRoot, "core/anki/create")
            .listFiles()!!.any { it.name.contains("NoteCreationLedger") }
        assertTrue("GATE 18's ledger is separate", creationLedger)
    }
}
