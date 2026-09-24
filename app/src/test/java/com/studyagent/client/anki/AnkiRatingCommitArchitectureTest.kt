package com.studyagent.client.anki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 11 Part X — the forbidden patterns, enforced as source scans (comments stripped).
 *
 * Each check is the executable form of one review grep: numeric ease above the gateway, a UI
 * that knows which backend it talks to, input-specific commit paths, a commit retried from a
 * catch block, a ledger transition back to NOT_STARTED, and a reducer that advances on selection.
 */
class AnkiRatingCommitArchitectureTest {
    private val appModuleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstNotNullOfOrNull { dir ->
            File(dir, "src/main/java/com/studyagent/client").takeIf { it.isDirectory }?.let { dir }
                ?: File(dir, "app/src/main/java/com/studyagent/client").takeIf { it.isDirectory }?.let { File(dir, "app") }
        } ?: error("could not locate the app module from ${File("").absolutePath}")

    private val root = File(appModuleDir, "src/main/java/com/studyagent/client")
    private val main: List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)(?<!:)//.*$"), " ")

    private fun File.rel(): String = relativeTo(root).path.replace('\\', '/')

    private fun offenders(files: List<File>, pattern: Regex): List<String> =
        files.filter { pattern.containsMatchIn(it.code()) }.map { it.rel() }

    private val outsideAnkiDroidLayer = main.filterNot { it.rel().startsWith("data/anki/ankidroid/") }

    @Test fun `numeric ease and provider rating vocabulary never leave the AnkiDroid layer`() {
        val found = offenders(outsideAnkiDroidLayer, Regex("answer_ease|time_taken|\\bEASE_[0-9A-Z]|easeFor\\("))
        assertTrue("ease values belong below the gateway: $found", found.isEmpty())
    }

    @Test fun `the UI never asks which backend is active or calls AnkiDroid`() {
        val ui = main.filter { it.rel().startsWith("ui/") }
        assertTrue(ui.isNotEmpty())
        val anywhere = offenders(ui, Regex("AnkiDroidBackend|is\\s+AnkiDroid|commitRating\\(|ContentResolver|ReviewCommitLedger"))
        assertTrue("UI must stay backend-neutral: $anywhere", anywhere.isEmpty())
        // The study/rating UI must not even import the AnkiDroid layer (Settings may show its health).
        val ratingUi = ui.filter { it.rel().startsWith("ui/screens/study/") || it.rel().startsWith("ui/components/") }
        val layer = offenders(ratingUi, Regex("data\\.anki\\.ankidroid"))
        assertTrue("rating UI must not import the AnkiDroid layer: $layer", layer.isEmpty())
    }

    @Test fun `there are no input specific commit paths`() {
        val found = offenders(main, Regex("(voice|touch|keyboard|headset|button)Commit", RegexOption.IGNORE_CASE))
        assertTrue("all inputs converge on one rating event: $found", found.isEmpty())
    }

    @Test fun `a commit is never retried from a catch block`() {
        val found = offenders(main, Regex("catch\\s*\\([^)]*\\)\\s*\\{[^}]*commitRating\\(", RegexOption.DOT_MATCHES_ALL))
        assertTrue("no blind retry after an exception: $found", found.isEmpty())
    }

    @Test fun `the ledger has no transition back to NOT_STARTED`() {
        val ledger = main.single { it.name == "ReviewCommitLedger.kt" }
        val back = Regex("copy\\([^)]*state\\s*=\\s*ReviewCommitState\\.NOT_STARTED").containsMatchIn(ledger.code())
        assertTrue("SUBMITTING/FAILED/AMBIGUOUS must never become NOT_STARTED in the ledger", !back)
    }

    @Test fun `only the COMMITTED branch of the reducer requests the next card after a rating`() {
        val reducer = main.single { it.name == "StudyReducer.kt" }.code()
        val select = reducer.substringAfter("private fun selectRating(").substringBefore("private fun resolveAnkiCommit(")
        assertTrue("selection must not request a next card", !select.contains("AnkiStudyEffect.Next"))
        val resolve = reducer.substringAfter("private fun resolveAnkiCommit(").substringBefore("// ------------------------------------------------------------------ helpers")
        assertTrue(Regex("outcome is AnkiCommitOutcome\\.Committed\\)\\s*listOf\\(AnkiStudyEffect\\.Next").containsMatchIn(resolve))
    }

    @Test fun `the reducer and the executor stay in their lanes`() {
        val reducer = main.single { it.name == "StudyReducer.kt" }.code()
        assertTrue("the reducer never calls a backend", !Regex("\\.commitRating\\(|\\.nextCard\\(|\\.prepareCommit\\(").containsMatchIn(reducer))
        val executor = main.single { it.name == "AnkiStudyEffectExecutor.kt" }.code()
        assertTrue("the executor never writes machine state", !executor.contains("_machineState") && !executor.contains("SessionMachineState("))
    }
}
