package com.studyagent.client.anki.customstudy

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.core.anki.customstudy.CustomStudyPolicy
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCustomStudyCapabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 20 PART II — the structural half of the audit (AUDIT-20-01 … AUDIT-20-14).
 *
 * Rules are checked on source text **with comments stripped**, following the GATE 12–21I convention,
 * so a document may explain a forbidden spelling while live code may not use it. The behavioural
 * half lives in `study/Gate20CustomStudyStartTest` and `study/Gate20CustomStudyEnduranceTest`.
 */
class Gate20ArchitectureAuditTest {

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

    private fun mainSources(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 20 source $relative" }
        return file
    }

    // ------------------------------------------------------------- AUDIT-20-03  no local review queue

    /** Names that only exist if someone built a client-side queue. */
    private val forbiddenQueueSpellings = listOf(
        "customQueue", "nextLocalCard", "localQueue", "sortedCards", "filteredCards",
        "pickNextCard", "chooseNextCard", "nextCardFrom("
    )

    @Test fun `no client-side review queue exists anywhere in production sources`() {
        val offenders = mainSources().flatMap { file ->
            val code = file.code()
            forbiddenQueueSpellings.filter { code.contains(it) }.map { "${file.name}: $it" }
        }
        assertEquals("a local review queue is forbidden (INV-20-03/20-07): $offenders", emptyList<String>(), offenders)
    }

    @Test fun `the custom-study package never touches a card, a queue or the scheduler`() {
        val packageSources = File(mainRoot, "core/anki/customstudy")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the contract package must exist", packageSources.isNotEmpty())
        packageSources.forEach { file ->
            val code = file.code()
            assertFalse("${file.name} must not browse cards", code.contains("browseCards"))
            assertFalse("${file.name} must not ask the scheduler", code.contains("nextCard"))
            assertFalse("${file.name} must not rate", code.contains("commitRating"))
            assertFalse("${file.name} must not sort", code.contains("sortedBy"))
        }
    }

    // ---------------------------------------------------- AUDIT-20-06/20-11  one rating pipeline only

    @Test fun `custom study adds no second rating path`() {
        val ratingCallers = mainSources().filter { file ->
            val code = file.code()
            code.contains("commitRating(") && !file.path.contains("core/anki/ReviewCommit")
        }
        // GATE 11's own pipeline plus the executor are the only callers; the custom-study layer is not
        // among them, so a filtered rating still travels the same transaction (INV-20-11).
        ratingCallers.forEach { file ->
            assertFalse(
                "a custom-study file must not call commitRating: ${file.name}",
                file.path.contains("customstudy")
            )
        }
    }

    @Test fun `the decision is asked at both admission points and nowhere else`() {
        val callers = mainSources().filter { it.code().contains("CustomStudyPolicy.evaluate") }
            .map { it.name }.sorted()
        assertEquals(
            "the policy must guard the start site and the binding moment only: $callers",
            listOf("AnkiLocalStudyStarter.kt", "AnkiStudyEffectExecutor.kt"),
            callers
        )
    }

    // ----------------------------------------------- AUDIT-20-02/20-14  one scheduler authority per session

    @Test fun `a session carries exactly one mode and one backend, and both are immutable`() {
        val context = main("core/anki/AnkiSessionContext.kt").code()
        assertTrue(context.contains("val studyMode: AnkiStudyMode"))
        assertTrue(context.contains("val backendId: AnkiBackendId"))
        // data class + no mutability: the only way the mode changes is a new context.
        assertTrue(context.contains("data class AnkiSessionContext"))
    }

    @Test fun `the study mode is not persisted, so a restore can never invent a filtered session`() {
        val lock = main("core/anki/StudySessionBackendLock.kt").code()
        assertTrue(lock.contains("@Transient val studyMode"))
    }

    // -------------------------------------------------------- AUDIT-20-13  capability honesty by default

    @Test fun `every adapter that has not audited custom study claims nothing`() {
        val backendInterface = main("core/anki/AnkiBackend.kt").code()
        assertTrue(
            backendInterface.contains("customStudyCapabilities: AnkiCustomStudyCapabilities get() = AnkiCustomStudyCapabilities.NONE")
        )
        assertTrue(backendInterface.contains("filtered_deck_rescheduling"))
    }

    @Test fun `the AnkiDroid adapter claims nothing, and says why in its own file`() {
        assertFalse(AnkiDroidCustomStudyCapabilities.AUDITED.canReviewFilteredDeck)
        assertFalse(AnkiDroidCustomStudyCapabilities.AUDITED.canCreateFilteredSession)
        assertFalse(AnkiDroidCustomStudyCapabilities.AUDITED.canFilterByTag)
        assertFalse(AnkiDroidCustomStudyCapabilities.AUDITED.supportsAnyCustomStudy)
        // The one scope truth the backend really has: a deck scope always includes its descendants.
        assertTrue(AnkiDroidCustomStudyCapabilities.DECK_SCOPE_INCLUDES_DESCENDANTS)
    }

    @Test fun `the AnkiDroid adapter refuses to guess rescheduling semantics`() = runTest {
        val result = AnkiDroidCustomStudyCapabilities.filteredDeckRescheduling(
            AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1")
        )
        assertTrue(result is com.studyagent.client.core.anki.AnkiResult.Failure)
        val error = (result as com.studyagent.client.core.anki.AnkiResult.Failure).error
        assertEquals("filtered_deck_rescheduling", (error as? AnkiError.UnsupportedAction)?.action)
    }

    // ------------------------------------------------------------ AUDIT-20-01  every behaviour traces back

    @Test fun `the policy is the only place a mode is decided`() {
        val modes = mainSources().filter { it.code().contains("AnkiStudyMode.Filtered(") }.map { it.name }
        // The policy, the mode type itself and its tests. No UI, no adapter, no executor invents one.
        modes.forEach { name ->
            assertTrue(
                "only the policy may construct a filtered mode: $name",
                name == "CustomStudyPolicy.kt" || name == "AnkiStudyMode.kt"
            )
        }
    }

    @Test fun `no capability is advertised without the two that make filtered review safe`() {
        val capabilities = main("core/anki/customstudy/AnkiCustomStudyCapabilities.kt").code()
        assertTrue(capabilities.contains("canReviewFilteredDeck || canProveFilteredRatingCommit"))
        assertTrue(capabilities.contains("canReviewFilteredDeck || canExposeReschedulingSemantics"))
    }

    @Test fun `the refusal tokens are content-free`() {
        CustomStudyPolicy.Refusal.entries.forEach { refusal ->
            assertTrue(refusal.token.matches(Regex("[a-z0-9_]+")))
        }
    }
}
