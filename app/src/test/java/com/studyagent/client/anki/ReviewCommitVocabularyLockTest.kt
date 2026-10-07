package com.studyagent.client.anki

import com.studyagent.client.core.anki.ReviewCommitCoordinator
import com.studyagent.client.core.anki.ReviewCommitPhase
import com.studyagent.client.core.anki.ReviewCommitRecoveryAction
import com.studyagent.client.core.anki.ReviewCommitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 11B — the naming model, enforced so it stays locked.
 *
 * The gate closes with one rule: *when reading any variable or log line, an engineer must be able
 * to tell immediately which layer it belongs to*. These checks are that rule made executable — a
 * closed durable vocabulary, a closed attempt vocabulary, a coordinator with exactly three entry
 * points, a presentation vocabulary that never reuses a durable name, and a repository scan for
 * the retired spellings.
 *
 * Source scans strip comments, exactly like [AnkiRatingCommitArchitectureTest], so prose about a
 * retired name is allowed while a live occurrence is not.
 */
class ReviewCommitVocabularyLockTest {

    private val appModuleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstNotNullOfOrNull { dir ->
            File(dir, "src/main/java/com/studyagent/client").takeIf { it.isDirectory }?.let { dir }
                ?: File(dir, "app/src/main/java/com/studyagent/client").takeIf { it.isDirectory }?.let { File(dir, "app") }
        } ?: error("could not locate the app module from ${File("").absolutePath}")

    private val mainRoot = File(appModuleDir, "src/main/java/com/studyagent/client")
    private val testRoot = File(appModuleDir, "src/test/java/com/studyagent/client")
    private val repoRoot = appModuleDir.parentFile

    private val main: List<File> = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val tests: List<File> = testRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)(?<!:)//.*$"), " ")

    private fun File.rel(): String = relativeTo(repoRoot).path.replace('\\', '/')

    /**
     * The retired spellings of GATE 11B §20, plus the retired attempt-phase names of GATE 11. Each
     * is matched as a whole word, so a longer legitimate identifier is never a false positive.
     */
    private val retiredNames = listOf(
        "ReviewCommitState",
        "CommitAttemptPhase",
        "FAILED_SAFE_TO_RETRY",
        "FAILED_NOT_RETRYABLE",
        "SafeToRetry",
        "safeToRetry",
        "RetryableFailure",
        "Retryable",
        "AnkiRatingAmbiguous",
        "AnkiRatingCommitted",
        "SubmittingAnkiRating",
        "MUTATION_CALL_ENTERED",
        "MUTATION_RESPONSE_RECEIVED",
        "LOCAL_RESULT_PERSISTED"
    ).map { Regex("\\b$it\\b") }

    /** `NOT_STARTED` is retired for the commit ledger only; another ledger legitimately uses it. */
    private val notStarted = Regex("\\bNOT_STARTED\\b")
    private val unrelatedLedgerMarkers = listOf("SubmissionLedger", "SubmissionState", "answerState",
        "ratingState", "skipState")

    /**
     * Where a retired name may still appear, and why: a migration adapter that must read old rows,
     * a different ledger, a test that pins either, or this class — which names the retired words in
     * order to forbid them.
     */
    private val allowedSources: Map<String, String> = mapOf(
        "app/src/main/java/com/studyagent/client/core/anki/ReviewCommitLedger.kt" to
            "TEMPORARY MIGRATION ADAPTER — reads ledger schemas 1-3 and rewrites them as canonical",
        "app/src/main/java/com/studyagent/client/core/study/SubmissionLedger.kt" to
            "LEGITIMATE UNRELATED USE — the PC submission ledger, not the rating-commit ledger",
        "app/src/test/java/com/studyagent/client/anki/ReviewCommitLedgerTest.kt" to
            "pins the legacy schema migration adapter above",
        "app/src/test/java/com/studyagent/client/anki/ReviewCommitVocabularyLockTest.kt" to
            "names the retired spellings in order to forbid them"
    )

    /**
     * Living specifications, which must speak the canonical vocabulary. The gate reports
     * (`docs/GATE_*.md`) and the ADRs are excluded on purpose: they are history, kept verbatim and
     * each carrying an explicit GATE 11B amendment that maps the old words to the new ones.
     */
    private val livingDocs = listOf(
        "README.md",
        "docs/SESSION_STATE_MACHINE.md",
        "docs/ANKI_INTEGRATION_ARCHITECTURE.md",
        "docs/ANKIDROID_INTEGRATION.md",
        "docs/GATE_01_ANKI_CONTRACT.md",
        "docs/GATE_03_ANKI_DOMAIN.md"
    )

    private fun offenders(files: List<File>, pattern: Regex): List<String> =
        files.filter { pattern.containsMatchIn(it.code()) }.map { it.rel() }

    /** A `NOT_STARTED` that belongs to the unrelated PC submission ledger is not an occurrence. */
    private fun notStartedOffenders(files: List<File>): List<String> = files.filter { file ->
        file.code().lines().any { line ->
            notStarted.containsMatchIn(line) && unrelatedLedgerMarkers.none { line.contains(it) }
        }
    }.map { it.rel() }

    private fun retiredIn(files: List<File>): List<String> {
        val scanned = files - files.filter { allowedSources.containsKey(it.rel()) }.toSet()
        return retiredNames.flatMap { pattern -> offenders(scanned, pattern).map { pattern to it } }
            .map { (pattern, file) -> "$file: ${pattern.pattern}" } +
            notStartedOffenders(scanned).map { "$it: NOT_STARTED" }
    }

    // ------------------------------------------------------------------ closed vocabularies

    @Test fun `the durable status vocabulary is closed to the five canonical values`() {
        assertEquals(
            setOf("PREPARED", "SUBMITTING", "COMMITTED", "RETRY_ALLOWED", "AMBIGUOUS"),
            ReviewCommitStatus.entries.map { it.name }.toSet()
        )
    }

    @Test fun `the attempt phase vocabulary is closed to the four canonical values`() {
        assertEquals(
            setOf("INTENT_PERSISTED", "MUTATION_BOUNDARY_ENTERED", "BACKEND_RESPONSE_RECEIVED",
                "FINAL_STATUS_PERSISTED"),
            ReviewCommitPhase.entries.map { it.name }.toSet()
        )
    }

    /** Status and phase never share a name, so `status = SUBMITTING, phase = PREPARED` is unwritable. */
    @Test fun `status and phase names do not overlap`() {
        val phases = ReviewCommitPhase.entries.map { it.name }.toSet()
        val shared = ReviewCommitStatus.entries.map { it.name }.toSet() intersect phases
        assertTrue("a name belongs to two dimensions: $shared", shared.isEmpty())
    }

    @Test fun `the coordinator has exactly the three canonical entry points`() {
        val entryPoints = ReviewCommitCoordinator::class.java.declaredMethods.map { it.name }.sorted()
        assertEquals(listOf("commit", "recover", "retry"), entryPoints)
    }

    /**
     * INV-11B-13 and §15-§17: the presentation vocabulary describes what the interface may show,
     * and must never reuse a durable status name.
     */
    @Test fun `the presentation vocabulary never reuses a durable status name`() {
        val ui = main.single { it.name == "RatingCommitUiState.kt" }.code()
        val cases = Regex("data (?:object|class) (\\w+)").findAll(ui).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("AwaitingRating", "Saving", "RetryAvailable", "VerificationRequired", "Saved"),
            cases
        )
        val shared = cases intersect ReviewCommitStatus.entries.map { it.name }.toSet()
        assertTrue("the UI renames durable truth: $shared", shared.isEmpty())
    }

    /** §25 and §14: a recovery action is a decision, not a status; the two vocabularies stay disjoint. */
    @Test fun `the recovery vocabulary stays disjoint from the durable status vocabulary`() {
        val actions = setOf("ResumeCommitted", "OfferRetry", "Reconcile", "RemainBlocked", "IntegrityFailure")
        val statuses = ReviewCommitStatus.entries.map { it.name }.toSet()
        assertTrue("a recovery action names a durable status: ${actions intersect statuses}",
            (actions intersect statuses).isEmpty())
        // §25's example: the same fact is spellable in both layers without colliding.
        assertEquals("RETRY_ALLOWED", ReviewCommitStatus.RETRY_ALLOWED.name)
        assertEquals("OfferRetry", ReviewCommitRecoveryAction.OfferRetry.label)
    }

    // ------------------------------------------------------------------ retired spellings

    @Test fun `no retired commit spelling survives in production sources`() {
        val found = retiredIn(main)
        assertTrue("retired commit vocabulary is still live in: $found", found.isEmpty())
    }

    @Test fun `no retired commit spelling survives in the test sources`() {
        val found = retiredIn(tests)
        assertTrue("retired commit vocabulary is still live in: $found", found.isEmpty())
    }

    /** Whatever survives must survive inside a classified, allowlisted file — nothing else. */
    @Test fun `every surviving retired spelling is classified`() {
        val survivors = (main + tests).filter { file ->
            val hits = retiredNames.any { it.containsMatchIn(file.code()) } ||
                notStartedOffenders(listOf(file)).isNotEmpty()
            hits && !allowedSources.containsKey(file.rel())
        }.map { it.rel() }
        assertTrue("unclassified legacy occurrences: $survivors", survivors.isEmpty())
        val stale = allowedSources.keys - (main + tests).map { it.rel() }.toSet()
        assertTrue("the allowlist names files that no longer exist: $stale", stale.isEmpty())
    }

    @Test fun `the living specifications speak the canonical vocabulary`() {
        val docs = livingDocs.map { File(repoRoot, it) }
        assertTrue("a living specification is missing: ${docs.filterNot { it.exists() }}",
            docs.all { it.exists() })
        val found = docs.flatMap { file ->
            retiredNames.filter { it.containsMatchIn(file.readText()) }
                .map { "${file.name}: ${it.pattern}" }
        }
        assertTrue("a living specification still uses retired vocabulary: $found", found.isEmpty())
    }
}
