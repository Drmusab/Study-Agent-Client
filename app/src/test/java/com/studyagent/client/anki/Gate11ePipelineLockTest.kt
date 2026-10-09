package com.studyagent.client.anki

import com.studyagent.client.core.anki.CommitGuaranteeLevel
import com.studyagent.client.core.anki.CommitReceiptKind
import com.studyagent.client.core.anki.CommitSemantics
import com.studyagent.client.core.anki.nextCardAllowed
import com.studyagent.client.core.study.AnkiCommitOutcome
import com.studyagent.client.core.study.allowsNextCard
import com.studyagent.client.core.anki.enforced
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.diagnostics.DiagnosticTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 11E — the aggregate pipeline lock, enforced as source scans (comments stripped).
 *
 * GATE 11A-11D built the pipeline; this gate decides whether it may be *locked*, and a lock is
 * only worth anything if a later change that reopens a hole fails a test. Every check here is the
 * executable form of one PART I freeze or one PART III verification that a grep can answer:
 *
 * | Check | Freeze / verification |
 * |---|---|
 * | one next-card barrier | PART I §6, INV-11E-18 |
 * | one production mutation path | PART I §3, VERIFICATION 21 |
 * | one real AnkiDroid write primitive | PART I §3 §7, VERIFICATION 21/22 |
 * | no alternate scheduler mutation | INV-11E-03/24 |
 * | the canonical diagnostic timeline | PART I §8 |
 * | UI is a projection | PART I §2, INV-11E-05/08 |
 * | one recovery transition table | PART I §5, INV-11E-15/16/17 |
 * | guarantee never exceeds the backend | PART I §7, INV-11E-29, VERIFICATION 23 |
 *
 * It complements, and deliberately does not duplicate, [ReviewCommitVocabularyLockTest] (the
 * vocabulary) and [AnkiRatingCommitArchitectureTest] (the forbidden patterns of GATE 11/11B).
 */
class Gate11ePipelineLockTest {

    private val appModuleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstNotNullOfOrNull { dir ->
            File(dir, "src/main/java/com/studyagent/client").takeIf { it.isDirectory }?.let { dir }
                ?: File(dir, "app/src/main/java/com/studyagent/client").takeIf { it.isDirectory }?.let { File(dir, "app") }
        } ?: error("could not locate the app module from ${File("").absolutePath}")

    private val root = File(appModuleDir, "src/main/java/com/studyagent/client")
    private val repoRoot = appModuleDir.parentFile
    private val main: List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)(?<!:)//.*$"), " ")

    private fun File.rel(): String = relativeTo(repoRoot).path.replace('\\', '/')

    /** Path relative to the source package root: what the layer-prefix checks below match on. */
    private fun File.pkg(): String = relativeTo(root).path.replace('\\', '/')

    private fun offenders(files: List<File>, pattern: Regex): List<String> =
        files.filter { pattern.containsMatchIn(it.code()) }.map { it.rel() }

    private fun file(name: String): File = main.single { it.name == name }

    // --------------------------------------------------------- PART I §6 — next-card barrier

    @Test fun `the next-card barrier is declared exactly once per mutation family`() {
        val declarations = main.flatMap { f ->
            Regex("fun nextCardAllowed\\(").findAll(f.code()).map { f.rel() }.toList()
        }
        // GATE 13 §22 adds the *second* family's barrier (reviewer actions have their own status
        // vocabulary, so they cannot share the rating overload). The rule stays one declaration per
        // family, each owning file listed here — a third copy anywhere fails this test.
        assertEquals(
            "the barrier is a rule, and two copies of a rule are two rules: $declarations",
            listOf(
                "app/src/main/java/com/studyagent/client/core/anki/ReviewCommitRecovery.kt",
                "app/src/main/java/com/studyagent/client/core/anki/ReviewerActionStatus.kt"
            ),
            declarations
        )
    }

    @Test fun `every outcome kind agrees with the barrier of its own durable status`() {
        val outcomes = listOf(
            AnkiCommitOutcome.Committed(AnkiCommitOutcome.SOURCE_BACKEND_CONFIRMED),
            AnkiCommitOutcome.Failed("not_applied", dispatched = true),
            AnkiCommitOutcome.Ambiguous("unknown_outcome"),
            AnkiCommitOutcome.PersistenceFailure("store_write_failed")
        )
        outcomes.forEach { outcome ->
            assertEquals(
                "outcome ${outcome::class.simpleName} disagrees with nextCardAllowed(${outcome.status})",
                nextCardAllowed(outcome.status), outcome.allowsNextCard()
            )
        }
        assertTrue(AnkiCommitOutcome.Committed(AnkiCommitOutcome.SOURCE_BACKEND_CONFIRMED).allowsNextCard())
        outcomes.filterNot { it is AnkiCommitOutcome.Committed }
            .forEach { assertTrue("${it::class.simpleName} must block the next card", !it.allowsNextCard()) }
    }

    /**
     * INV-11E-18, as a source rule: the effect that advances the session is never emitted from a
     * raw status comparison. A future "just this once" comparison on the same line is what this
     * test exists to catch.
     */
    @Test fun `no next-card effect is emitted from a raw COMMITTED comparison`() {
        val violations = main.flatMap { f ->
            f.code().lines()
                .filter { it.contains("AnkiStudyEffect.Next") && it.contains("ReviewCommitStatus.COMMITTED") }
                .map { "${f.rel()}: ${it.trim()}" }
        }
        assertTrue("the next-card effect is coupled to a raw status comparison: $violations", violations.isEmpty())
    }

    @Test fun `the production barrier sites all route through the shared rule`() {
        val reducer = file("StudyReducer.kt").code()
        assertTrue("the reducer must gate the commit outcome on allowsNextCard()",
            reducer.contains("outcome.allowsNextCard()"))
        assertTrue("the read-only next-card retry must gate on nextCardAllowed(",
            reducer.contains("nextCardAllowed(commit.status)"))
        val machine = file("StudySessionMachine.kt").code()
        assertTrue("the machine's UI copy and invariant must read the shared barrier",
            machine.contains("nextCardAllowed("))
        // GATE 13 §22 — the reviewer-action site is the fourth legal emission, and it must route
        // through the action family's own barrier instead of a local `status == APPLIED`.
        assertTrue("the confirmed action must gate its next-card query on the shared rule",
            reducer.contains("nextCardAllowed(action.action,"))
        // The legal next-card emissions: session begin, read retry, commit outcome, confirmed
        // turn-invalidating reviewer action. Anything else fails this test.
        val nextEmissions = Regex("AnkiStudyEffect\\.Next\\(").findAll(reducer).count()
        assertEquals(
            "session begin, read retry, commit outcome and a confirmed reviewer action are the only Next emissions",
            4, nextEmissions
        )
    }

    // ------------------------------------------- PART I §3 — one production mutation path

    @Test fun `the only scheduler write primitive is the single AnkiDroid provider update`() {
        val providerClient = "app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidProviderClient.kt"
        val writes = Regex("\\.(update|insert|delete|applyBatch|bulkInsert)\\(")
        val found = main.flatMap { f ->
            writes.findAll(f.code()).map { "${f.rel()}: ${it.value}" }.toList()
        }
        // GATE 11 invariant: the scheduler writes through exactly one primitive — a single
        // ContentResolver.update, owned by the provider client. GATE 18 adds a *creation* insert
        // (notes/media) that is sanctioned by the isolation layer's write allowlist but is NOT a
        // scheduler write: it is serialized on the same physical-write permit and its ordering is
        // locked by docs/GATE_18_BACKEND_CREATION_CONTRACT.md. Both calls live in the one client.
        assertEquals("scheduler update plus the GATE 18 creation insert, nothing else: $found",
            listOf("$providerClient: .insert(", "$providerClient: .update("), found.sorted())
        assertEquals("exactly one ContentResolver.update call exists",
            1, Regex("contentResolver\\.update\\(").findAll(file("AnkiDroidProviderClient.kt").code()).count())
        assertEquals("exactly one ContentResolver.insert call exists (GATE 18 creation)",
            1, Regex("contentResolver\\.insert\\(").findAll(file("AnkiDroidProviderClient.kt").code()).count())
    }

    @Test fun `only the study coordinator calls commitRating`() {
        val callers = offenders(main, Regex("\\.commitRating\\("))
        assertEquals("one caller, the coordinator: $callers",
            listOf("app/src/main/java/com/studyagent/client/core/study/AnkiStudyEffectExecutor.kt"), callers)
    }

    @Test fun `the commit effect is created only by the reducer and the coordinator entry points`() {
        // The reducer creates it (selection and explicit retry); the coordinator's three entry
        // points wrap the one private commitTransaction. Anything else is a second path.
        val creators = offenders(main, Regex("AnkiStudyEffect\\.CommitRating\\("))
        assertEquals(
            setOf(
                "app/src/main/java/com/studyagent/client/core/study/StudyReducer.kt",
                "app/src/main/java/com/studyagent/client/core/study/AnkiStudyEffectExecutor.kt"
            ), creators.toSet()
        )
        val executor = file("AnkiStudyEffectExecutor.kt").code()
        assertTrue("commitTransaction must stay private to the coordinator",
            Regex("private suspend fun commitTransaction\\(").containsMatchIn(executor))
    }

    @Test fun `the scheduler read has one call site and the committer one answer call`() {
        val readers = offenders(main, Regex("\\.nextCard\\("))
        assertEquals("one next-card query site: $readers",
            listOf("app/src/main/java/com/studyagent/client/core/study/AnkiStudyEffectExecutor.kt"), readers)
        assertEquals("exactly one provider answer per commit",
            1, Regex("gateway\\.submitAnswer\\(").findAll(file("AnkiDroidRatingCommitter.kt").code()).count())
    }

    /**
     * INV-11E-25 — no automatic mutation retry below the coordinator. The strongest form of the
     * invariant is structural: the adapter cannot retry because it cannot see the transaction. It
     * has no ledger, no coordinator and no retry entry point, and `submitAnswer` is called once
     * per commit (asserted above), so a retry loop is not expressible in this layer at all.
     */
    @Test fun `the adapter layer cannot retry because it cannot see the transaction`() {
        val adapters = main.filter { it.pkg().startsWith("data/anki/") }
        assertTrue(adapters.isNotEmpty())
        val found = offenders(adapters, Regex("ReviewCommitLedger|ReviewCommitCoordinator|\\bretry\\(|\\.retry\\("))
        assertTrue("the adapter must not reach the transaction owner or a retry entry: $found", found.isEmpty())
    }

    // --------------------------------------------------------- PART I §8 — diagnostics

    @Test fun `the commit timeline carries every canonical correlation field and no content`() {
        val metadata = file("StudySessionMachine.kt").code()
            .substringAfter("private fun commitMetadata(")
            .substringBefore("\n    private fun ")
        val required = listOf(
            "session" to "StudySessionId",
            "commit" to "ReviewCommitId",
            "backend" to "BackendId",
            "state" to "ReviewCommitStatus",
            "phase" to "ReviewCommitPhase",
            "attempt" to "AttemptCount",
            "rating" to "SelectedRating",
            "committed" to "CommittedRating",
            "action" to "RecoveryAction",
            "guarantee" to "GuaranteeLevel"
        )
        required.forEach { (key, field) ->
            assertTrue("the timeline is missing $field (key `$key`)", metadata.contains("\"$key\""))
        }
        val forbidden = listOf("question", "answerText", "html", "transcript", "feedback", "content")
        val leaked = forbidden.filter { metadata.contains(it) }
        assertTrue("the commit timeline leaked content keys: $leaked", leaked.isEmpty())
    }

    @Test fun `the metadata budget fits the whole commit timeline`() {
        assertTrue(
            "a truncated correlation field is a missing correlation field: ${DiagnosticTimeline.MAX_METADATA_ENTRIES}",
            DiagnosticTimeline.MAX_METADATA_ENTRIES >= 11
        )
    }

    // ------------------------------------------------- PART I §2 — source-of-truth ownership

    @Test fun `the UI is a projection and never names transaction truth`() {
        val ui = main.filter { it.pkg().startsWith("ui/") }
        assertTrue(ui.isNotEmpty())
        val found = offenders(ui, Regex("ReviewCommitLedger|AnkiStudyEffectExecutor|\\.commitRating\\(|ReviewCommitStatus\\.COMMITTED"))
        assertTrue("the UI must project, never own, transaction truth: $found", found.isEmpty())
    }

    @Test fun `the recovery transition table is declared once and owned by the transaction domain`() {
        val declarations = main.flatMap { f ->
            Regex("fun recoveryTransition\\(").findAll(f.code()).map { f.rel() }.toList()
        }
        assertEquals("one closed recovery table: $declarations",
            listOf("app/src/main/java/com/studyagent/client/core/anki/ReviewCommitRecovery.kt"), declarations)
        // AMBIGUOUS may never go straight to PREPARED or SUBMITTING (PART I §5): the table says so.
        listOf(
            ReviewCommitStatus.AMBIGUOUS to ReviewCommitStatus.PREPARED,
            ReviewCommitStatus.AMBIGUOUS to ReviewCommitStatus.SUBMITTING
        ).forEach { (from, to) ->
            assertTrue(
                "the recovery table has no $from → $to row",
                com.studyagent.client.core.anki.recoveryTransition(
                    from, com.studyagent.client.core.anki.ReviewCommitRecoveryEvent.RETRY_REQUESTED
                ) != to
            )
        }
    }

    // ------------------------------------- PART I §7 / VERIFICATION 23 — guarantee audit

    @Test fun `the AnkiDroid guarantee never exceeds its verified primitives`() {
        assertEquals(CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED, CommitSemantics.ANKIDROID.guaranteeLevel)
        assertTrue("AnkiDroid does not deduplicate a repeated commit id",
            !CommitSemantics.ANKIDROID.supportsIdempotentReplay)
        assertTrue("AnkiDroid cannot authoritatively reconcile a lost response",
            !CommitSemantics.ANKIDROID.supportsAuthoritativeReconciliation)
        assertEquals(CommitReceiptKind.NONE, CommitSemantics.ANKIDROID.commitReceiptKind)
    }

    @Test fun `an overclaiming backend is clamped, never trusted`() {
        val overclaim = CommitSemantics(
            CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE,
            supportsIdempotentReplay = true,
            supportsAuthoritativeReconciliation = true,
            commitReceiptKind = CommitReceiptKind.BACKEND_TRANSACTION_ID
        )
        val validation = com.studyagent.client.core.anki.validateCommitSemantics(overclaim, AnkiBackendId.AnkiDroidLocal)
        assertTrue("AnkiDroid must not be allowed to advertise exactly-once",
            validation is com.studyagent.client.core.anki.CommitSemanticsValidation.Invalid)
        assertEquals(
            "an overclaim fails closed instead of being believed",
            CommitSemantics.UNVERIFIED, overclaim.enforced(AnkiBackendId.AnkiDroidLocal)
        )
        assertEquals(CommitSemantics.ANKIDROID, CommitSemantics.ANKIDROID.enforced(AnkiBackendId.AnkiDroidLocal))
    }
}
