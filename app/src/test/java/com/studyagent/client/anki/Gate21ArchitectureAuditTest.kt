package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 21 PART II — repository source audit (AUDIT-21-01 … AUDIT-21-15).
 *
 * These are the structural rules a behavioural test cannot see from outside: *where* a backend may be
 * chosen, *what* a locked context may read, and which component owns which truth. They follow the
 * convention of the GATE 12–18 audits: rules are checked on source text with comments stripped, so a
 * document may explain a forbidden spelling while live code may not use it.
 *
 * The locked contract is `docs/GATE_21_MULTI_BACKEND_CONTRACT.md`; the IDs below map to its
 * invariants (INV-21-*) and the gate's audit checklist.
 */
class Gate21ArchitectureAuditTest {

    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private val mainRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")

    /** Source with comment lines removed, so documentation cannot satisfy (or break) a rule. */
    private fun File.code(): String = readLines()
        .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") }
        .joinToString("\n")

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 21 source $relative" }
        return file
    }

    private fun mainSources(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun studySources(): List<File> =
        File(mainRoot, "core/study").listFiles()!!.filter { it.extension == "kt" }

    // ------------------------------------------------------------- AUDIT-21-01 / AUDIT-21-02

    @Test
    fun `every scheduler and mutation call resolves through a locked or persisted identity`() {
        val executor = main("core/study/AnkiStudyEffectExecutor.kt").code()
        // Next card and hydration go through the session's / turn's own backend…
        assertTrue(
            "next card must resolve through the locked session context",
            executor.contains("registry.find(effect.session.context.backendId)?.nextCard(effect.session)")
        )
        assertTrue(
            "hydration must resolve through the turn's own backend",
            executor.contains("registry.find(effect.turn.backendId)?.hydrateCardContent(effect.turn.cardRef)")
        )
        // …and the rating mutation goes through the commit id's persisted backend.
        assertTrue(
            "the rating mutation must resolve through commitId.backendId",
            executor.contains("registry.find(commitId.backendId)")
        )
        // No scheduler call may resolve through the global preference or the AUTO policy.
        // GATE 21G: the executor holds exactly one resolution — the pre-binding decision inside
        // begin() — and everything after begin() routes via locked or persisted identity only.
        val beginBody = executor.substringAfter("private suspend fun begin(")
            .substringBefore("private suspend fun commitTransaction(")
        val outsideBegin = executor.substringBefore("private suspend fun begin(") +
            executor.substringAfter("private suspend fun commitTransaction(")
        assertFalse(
            "study code outside begin() must not consult any resolver",
            outsideBegin.contains("AnkiBackendResolver") ||
                outsideBegin.contains("AnkiBackendSelector(registry).resolve(")
        )
        assertFalse(
            "runtime study code must not read a backend preference",
            executor.contains("preferenceStore") || executor.contains("AnkiBackendPreferenceStore")
        )
    }

    @Test
    fun `the only backend selection in study runtime happens at session begin`() {
        val executor = main("core/study/AnkiStudyEffectExecutor.kt").code()
        // Exactly one resolution site, inside begin(): the pre-binding decision.
        // GATE 21G: the site migrated from the GATE 01 selector adapter to the GATE 21E pure
        // policy (AnkiBackendResolver2), per the 21E handoff. The rule is unchanged: one site,
        // inside begin(), never after binding.
        assertEquals(
            "exactly one backend resolution may exist in study runtime",
            1,
            Regex("""AnkiBackendResolver2\.resolve\(""").findAll(executor).count()
        )
        assertEquals(
            "the legacy selector adapter must no longer resolve inside study runtime",
            0,
            Regex("""AnkiBackendSelector\(registry\)\.resolve\(""").findAll(executor).count()
        )
        val beginBody = executor.substringAfter("private suspend fun begin(")
            .substringBefore("private suspend fun commitTransaction(")
        assertTrue(
            "the single resolution must live inside begin()",
            beginBody.contains("AnkiBackendResolver2.resolve(")
        )
        // The locked identity is captured in the session context, and only a successful start
        // publishes it. GATE 21G: the same acceptance durably owns the session when a lock store
        // is wired, and an unpersistable start is refused rather than half-bound.
        assertTrue(
            "the lock is published only after the backend accepted the session",
            beginBody.contains("if (result is AnkiResult.Success)") &&
                beginBody.contains("sessionLocks?.locked(context)")
        )
        assertTrue(
            "a successful start persists its lock when a store is wired",
            beginBody.contains("sessionLockStore")
        )
    }

    // ----------------------------------------------------------------------- AUDIT-21-03

    @Test
    fun `no cross-backend failover exists anywhere in study runtime`() {
        studySources().forEach { file ->
            val code = file.code()
            listOf("alternateBackend", "tryOtherBackend", "fallbackBackend", "switchBackend").forEach { token ->
                assertFalse(
                    "${file.name} must not contain a failover spelling ($token)",
                    code.contains(token)
                )
            }
        }
    }

    // ----------------------------------------------------------------------- AUDIT-21-04

    @Test
    fun `transaction recovery routes by the persisted record identity`() {
        val actions = main("core/anki/ReviewerActionRecovery.kt").code()
        assertTrue(
            "reviewer-action reconciliation must resolve the record's own backend",
            actions.contains("registry.find(record.backendId)")
        )
        assertTrue(
            "reviewer-action reconciliation must refuse a foreign card or collection",
            actions.contains("record.cardRef.backendId != backend.id")
        )

        val commits = main("core/anki/ReviewCommitReconciliation.kt").code()
        assertTrue(
            "commit reconciliation must resolve by the commit's own backend identity",
            commits.contains("commitId.backendId") || commits.contains("record.backendId")
        )

        // The recovery planner may never consult the routing policy or the preference.
        val planner = main("core/anki/AnkiTransactionRecoveryRouting.kt").code()
        assertFalse(planner.contains("AnkiBackendResolver"))
        assertFalse(planner.contains("AnkiBackendPreferenceStore"))
        assertFalse(planner.contains("AnkiBackendMode"))
        assertTrue(
            "recovery must locate the exact backend by identity",
            planner.contains("registry.find(backendId)")
        )
    }

    // ----------------------------------------------------------------------- AUDIT-21-06

    @Test
    fun `every resource reference is backend-qualified`() {
        val refs = main("core/anki/AnkiRefs.kt").code()
        listOf("AnkiCollectionIdentity", "AnkiDeckRef", "AnkiNoteRef", "AnkiCardRef").forEach { type ->
            val body = refs.substringAfter("data class $type(").substringBefore("\n)")
            assertTrue(
                "$type must carry a backendId as part of its identity",
                body.contains("val backendId: AnkiBackendId")
            )
            assertTrue(
                "$type must key its stable identity on the backend",
                refs.substringAfter("data class $type(").contains("backendId.stableId")
            )
        }
        val models = main("core/anki/AnkiNoteModels.kt").code()
        assertTrue(
            "AnkiNoteModelRef must carry a backendId",
            models.substringAfter("data class AnkiNoteModelRef(").substringBefore("\n)")
                .contains("val backendId: AnkiBackendId")
        )
    }

    @Test
    fun `a session context refuses a collection identity belonging to another backend`() {
        val context = main("core/anki/AnkiSessionContext.kt").code()
        assertTrue(
            context.contains("require(collection == null || collection.backendId == backendId)")
        )
    }

    // ----------------------------------------------------------------------- AUDIT-21-07

    @Test
    fun `AUTO resolution exists only at pre-binding sites`() {
        val callers = mainSources()
            .filter { it.code().let { c -> c.contains(".resolve(") && c.contains("BackendResolutionRequest") } }
            .map { it.name }
            .sorted()
        // The GATE 01 selector adapter, the status center, GATE 21F's new-read binding boundary,
        // and — since GATE 21G — the two session-start pre-binding sites (the starter resolves for
        // the request, the executor resolves inside begin()) are the only policy callers.
        // Reference/continued reads never resolve; bound study work never resolves.
        assertEquals(
            listOf(
                "AnkiBackendSelector.kt",
                "AnkiBackendStatus.kt",
                "AnkiLocalStudyStarter.kt",
                "AnkiReadContextController.kt",
                "AnkiStudyEffectExecutor.kt"
            ),
            callers
        )
        // The resolver itself is the only definition of the policy.
        assertTrue(
            main("core/anki/AnkiBackendResolver.kt").code()
                .contains("fun resolve(") &&
                main("core/anki/AnkiBackendResolver.kt").code()
                .contains("request: BackendResolutionRequest")
        )
        // The study runtime and every coordinator are deliberately absent from that list,
        // except the executor's single pre-binding site inside begin() (see the test above).
        studySources().forEach { file ->
            val code = file.code()
            if (file.name == "AnkiStudyEffectExecutor.kt") {
                assertEquals(
                    "the executor builds exactly one pre-binding request",
                    1,
                    Regex("""BackendResolutionRequest2\(""").findAll(code).count()
                )
                val beginBody = code.substringAfter("private suspend fun begin(")
                    .substringBefore("private suspend fun commitTransaction(")
                assertTrue(
                    "the executor's single request must live inside begin()",
                    beginBody.contains("BackendResolutionRequest2(")
                )
            } else {
                assertFalse(
                    "${file.name} must not build a BackendResolutionRequest",
                    code.contains("BackendResolutionRequest")
                )
            }
        }
    }

    // ------------------------------------------------------------------ AUDIT-21-08 / 21-10

    @Test
    fun `manual selection never silently substitutes another backend`() {
        val resolver = main("core/anki/AnkiBackendResolver.kt").code()
        // The mode filter runs first and is never widened by availability.
        assertTrue(resolver.contains("request.preference.accepts(it.id)"))
        assertTrue(
            "an explicit mode must be able to fail with its own typed reason",
            resolver.contains("PreferredBackendUnavailable")
        )
        // A local-preference branch may only ever *narrow* to the local kind, never widen to remote.
        assertTrue(resolver.contains("it.locality == AnkiBackendLocality.LOCAL_DEVICE"))
    }

    // ----------------------------------------------------------------------- AUDIT-21-13

    @Test
    fun `the presentation layer never branches on a backend name`() {
        val offenders = mainSources()
            .filter { it.path.contains("/ui/") }
            .filter { file ->
                val code = file.code()
                // The one allowed place is the display-label mapper, which turns an identity into
                // copy and decides nothing.
                file.name != "AnkiBackendUiModels.kt" &&
                    (code.contains("is AnkiBackendId.AnkiDroidLocal") ||
                        code.contains("is AnkiBackendId.PcAgent"))
            }
            .map { it.name }
        assertEquals("UI must route by capability, not by backend name", emptyList<String>(), offenders)
    }

    // ----------------------------------------------------------------------- AUDIT-21-14

    @Test
    fun `the four sources of truth are separate components`() {
        // Preference (future default) / registry (existence+state) / resolver (pre-bind policy) /
        // session lock (active truth) must not be collapsed into one object.
        val preference = main("core/anki/AnkiBackendPreference.kt").code()
        val registry = main("core/anki/AnkiBackendSelector.kt").code()
        val resolver = main("core/anki/AnkiBackendResolver.kt").code()
        val status = main("core/anki/AnkiBackendStatus.kt").code()

        assertTrue(preference.contains("interface AnkiBackendPreferenceStore"))
        assertTrue(registry.contains("class AnkiBackendRegistry"))
        assertTrue(resolver.contains("class AnkiBackendResolver"))
        assertTrue(status.contains("class AnkiStudyBackendLockTracker"))

        // The registry owns existence, not policy.
        assertFalse("the registry must not route", registry.contains("class AnkiBackendResolver"))
        // The resolver owns policy, not storage.
        assertFalse(resolver.contains("AnkiBackendPreferenceStore"))
        // The lock tracker cannot rewrite a lock: it only records and releases.
        assertTrue(status.contains("fun locked(context: AnkiSessionContext)"))
        assertTrue(status.contains("fun released(studySessionId: String)"))
        assertFalse(
            "the lock tracker must not resolve backends",
            status.substringAfter("class AnkiStudyBackendLockTracker").contains("AnkiBackendResolver")
        )
    }

    // ----------------------------------------------------------------------- AUDIT-21-15

    @Test
    fun `diagnostics keep preferred, resolved, locked and transaction backends apart`() {
        val diagnostics = main("core/anki/AnkiBackendPreference.kt").code()
        val body = diagnostics.substringAfter("data class AnkiBackendRoutingDiagnostics(")
            .substringBefore("\n)")
        listOf("preferred", "resolved", "lockedSessionBackend", "transactionBackend", "availability")
            .forEach { field ->
                assertTrue("diagnostics must expose `$field`", body.contains("val $field"))
            }
    }

    // --------------------------------------------------------------- INV-21-12 / 21-13

    @Test
    fun `browser paging state cannot cross a backend boundary`() {
        val paging = main("core/anki/AnkiCardPaging.kt").code()
        assertTrue(
            "an offset cursor must be bound to a query consistency key",
            paging.contains("class AnkiOffsetCursorAdapter(private val consistencyKey: String)")
        )
        assertTrue(
            "a cursor from another query/backend/collection must be typed foreign, never re-based",
            paging.contains("Resolution.ForeignQuery")
        )
        assertTrue(
            "a foreign card reference must be rejected, not reinterpreted",
            paging.contains("\"foreign_card_ref\"")
        )
        assertTrue(
            "the row-identity check must compare against the backend that produced the page",
            paging.contains("fun AnkiCardListItem.pageIdentityError(") &&
            paging.contains("backendId: AnkiBackendId")
        )
        val query = main("core/anki/AnkiCardQuery.kt").code()
        assertTrue(
            "a query's consistency key must include the backend identity",
            query.contains("fun consistencyKey(backendId: AnkiBackendId")
        )
    }

    // ------------------------------------------------------------- INV-21-14 / 21-15

    @Test
    fun `bound editors and drafts resolve their backend from the route, not the preference`() {
        val nav = main("ui/navigation/AppNavHost.kt").code()
        // Read contexts bind card details by the exact route backend; Edit Note and Add Note keep
        // their existing exact registry lookups rather than consulting the new-read preference.
        assertTrue(nav.contains("backendId = cardRef.backendId"))
        assertTrue(nav.contains("container.ankiBackendRegistry.find(editCardRef.backendId)"))
        assertTrue(nav.contains("addNoteBackendId?.let(container.ankiBackendRegistry::find)"))
        assertTrue(nav.contains("container.ankiReadContextController"))
        assertFalse(
            "navigation must not resolve a bound screen through the AUTO policy",
            nav.contains("BackendResolutionRequest")
        )
    }

    @Test
    fun `the note coordinators refuse a foreign backend instead of re-routing`() {
        val edit = main("core/anki/edit/NoteMutationCoordinator.kt").code()
        assertTrue(
            "a note mutation must refuse a backend mismatch",
            edit.contains("NoteMutationRefusal.BackendMismatch")
        )
        val create = main("core/anki/create/NoteCreationCoordinator.kt").code()
        assertTrue(
            "a note creation must refuse a backend mismatch",
            create.contains("NoteCreationRefusal.BackendMismatch")
        )
    }

    // ------------------------------------------------------------------ INV-21-19 / 21-20

    @Test
    fun `gate 21 adds no cross-backend migration, mapping or merging`() {
        mainSources().forEach { file ->
            val code = file.code()
            listOf(
                "translateCardId",
                "mapCrossBackend",
                "mergeCollections",
                "federatedLibrary",
                "crossBackendSync"
            ).forEach { token ->
                assertFalse(
                    "${file.name} must not contain cross-backend translation ($token)",
                    code.contains(token)
                )
            }
        }
    }
}
