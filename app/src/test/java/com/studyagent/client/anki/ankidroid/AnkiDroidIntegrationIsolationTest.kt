package com.studyagent.client.anki.ankidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 02 §3/§6/§7/§10/§63/§65 — the boundary, enforced as a test instead of a convention.
 *
 * Everything else in this suite proves that the integration *behaves*; this proves it is
 * *contained*. These are source scans rather than reflection because the questions are about what
 * a file may contain at all: which package may name AnkiDroid, which file may touch the Android
 * provider APIs, which dependencies are forbidden, and what the manifest may ask for.
 *
 * A scan cannot prove absence the way a compiler can, but it fails loudly on exactly the
 * regressions that are otherwise invisible in review: someone importing the AnkiDroid artifact
 * "just for the constants", a debug authority leaking into release code, a query sneaking in that
 * writes, or a storage permission arriving "to make it work".
 */
class AnkiDroidIntegrationIsolationTest {

    /**
     * The app module, found from the working directory of the test JVM.
     *
     * Gradle runs unit tests with the module directory as the working directory, but IDEs and
     * ad-hoc runs sometimes use the repository root, so both shapes are accepted before giving up
     * (a wrong answer here would silently scan nothing and "pass").
     */
    private val appModuleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstNotNullOfOrNull { dir ->
            File(dir, "src/main/java/com/studyagent/client").takeIf { it.isDirectory }?.let { dir }
                ?: File(dir, "app/src/main/java/com/studyagent/client")
                    .takeIf { it.isDirectory }?.let { File(dir, "app") }
        }
        ?: error("could not locate the app module from ${File("").absolutePath}")

    private val mainSources: List<File> = File(appModuleDir, "src/main/java/com/studyagent/client")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private val layerFiles: List<File> = mainSources.filter { it.isLayerFile() }

    private val outsideLayer: List<File> = mainSources.filterNot { it.isLayerFile() }

    /** Only these files may touch the platform (INV-ANKI-DET-06, extended in GATE 04). */
    private val androidFacingFiles = setOf(
        "AndroidAnkiDroidProbe.kt",
        "AnkiDroidLauncher.kt",
        "AnkiDroidProviderClient.kt"
    )

    // ---------------------------------------------------------------- helpers

    private fun File.isLayerFile(): Boolean =
        path.replace('\\', '/').contains("client/data/anki/ankidroid/")

    /**
     * The source with comments removed: the scans are about what the code *does*, not about the
     * provenance notes that legitimately name AnkiDroid classes this project must never link.
     */
    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)(?<!:)//.*$"), " ")

    private fun File.xmlWithoutComments(): String =
        readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

    private fun imports(file: File): List<String> =
        file.readLines().filter { it.startsWith("import ") }

    private fun offenders(token: String, files: List<File>): List<String> =
        files.filter { it.code().contains(token) }.map { it.name }

    // ---------------------------------------------------------------- which package may know what

    @Test
    fun `only the integration layer names the AnkiDroid contract`() {
        val leaked = offenders("com.ichi2.anki", outsideLayer)
        assertTrue(
            "only data/anki/ankidroid may reference the AnkiDroid authority/permission: $leaked",
            leaked.isEmpty()
        )
        assertTrue(
            "the scan must actually see the layer's pinned constants",
            layerFiles.any { it.code().contains("com.ichi2.anki") }
        )
    }

    @Test
    fun `the debug authority never appears in production code`() {
        val leaked = offenders("com.ichi2.anki.debug", outsideLayer)
        assertTrue("the debug endpoint belongs to debug builds only: $leaked", leaked.isEmpty())
    }

    @Test
    fun `no file outside the layer resolves an AnkiDroid provider or asks about its permission`() {
        for (token in listOf("resolveContentProvider", "ContentProviderClient", "READ_WRITE_DATABASE")) {
            val leaked = offenders(token, outsideLayer)
            assertTrue("'$token' must stay inside the integration layer: $leaked", leaked.isEmpty())
        }
    }

    // ---------------------------------------------------------------- the layer's own discipline

    @Test
    fun `the platform stays behind the two platform files`() {
        for (file in layerFiles.filterNot { it.name in androidFacingFiles }) {
            val androidImports = imports(file).filter { it.startsWith("import android") }
            assertTrue(
                "${file.name} must not import Android types: $androidImports",
                androidImports.isEmpty()
            )
        }
        // …and the two exceptions really are the platform files.
        for (file in layerFiles.filter { it.name in androidFacingFiles }) {
            assertTrue(
                "${file.name} should be Android-specific",
                imports(file).any { it.startsWith("import android") }
            )
        }
    }

    @Test
    fun `the integration layer may only import approved dependencies`() {
        val allowedPrefixes = listOf(
            "import android.",
            "import kotlin.",
            "import kotlinx.",
            "import java.",
            "import com.studyagent.client.core.anki.",
            "import com.studyagent.client.core.common.",
            // `core.models.Rating` is already the domain vocabulary for AGAIN/HARD/GOOD/EASY —
            // `AnkiSchedulingInfo` above the layer uses it. GATE 06 needs it too, and inventing a
            // parallel Anki rating enum to avoid this import is exactly what GATE 03 §76 forbids.
            "import com.studyagent.client.core.models."
        )
        val offenders = layerFiles.flatMap { file ->
            imports(file)
                .filterNot { line -> allowedPrefixes.any { line.startsWith(it) } }
                .map { "${file.name}: $it" }
        }
        assertTrue(
            "the layer must stay a leaf (domain + coroutines + platform): $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the integration layer writes only through the single rating writer and links no AnkiDroid internals`() {
        val forbidden = listOf(
            // mutations other than the one GATE 11 owns (§67/§79/§80)
            ".insert(", ".delete(", "openFileDescriptor", "openInputStream",
            "openOutputStream", "ContentProviderOperation", "resolver.call(",
            // APIs that must not exist
            "addNote(", "addNotes(", "updateNote(", "addMediaFromUri(", "addNewDeck(",
            "selectDeckWithCheck", "getNextCard(",
            // bury / suspend stay out of scope (GATE 11 answers cards and nothing else)
            "\"buried\"", "\"suspended\"",
            // `sched.` prefixed so the scan cannot be fooled by an unrelated name: the layer does
            // have a `suspendCards` *capability flag*, and it must keep having one.
            "sched.answerCard", "sched.buryCards", "sched.suspendCards",
            "nextIvl", "getSchedulingStates",
            // AnkiDroid internals: never linked, never copied
            "FlashCardsContract", "ReviewInfo", "libanki", "Reviewer"
        )
        for (token in forbidden) {
            val found = offenders(token, layerFiles)
            assertTrue("'$token' must not appear in the integration layer's code: $found", found.isEmpty())
        }
    }

    /**
     * GATE 11 — the write allowlist. The platform `update` call exists in exactly one place (the
     * provider client), the answer columns are named only by the pinned contract, and only the
     * rating gateway issues writes. A new writer anywhere else fails this test.
     */
    @Test
    fun `GATE 11 write tokens appear only in their owning file`() {
        val allowed = mapOf(
            ".update(" to setOf("AnkiDroidProviderClient.kt"),
            "\"answer_ease\"" to setOf("AnkiDroidApiContract.kt"),
            "\"time_taken\"" to setOf("AnkiDroidApiContract.kt"),
            "safeUpdate(" to setOf("AnkiDroidProviderClient.kt", "AndroidAnkiDroidProbe.kt", "AnkiDroidRatingGateway.kt"),
            "submitAnswer(" to setOf("AnkiDroidRatingGateway.kt", "AnkiDroidRatingCommitter.kt"),
            "easeFor(" to setOf("AnkiDroidCommitEvidence.kt", "AnkiDroidRatingGateway.kt")
        )
        // Provider-specific tokens are checked app-wide; generic names only inside the layer
        // (the PC protocol legitimately has a `submitAnswer(` message factory).
        val layerOnly = setOf(".update(", "submitAnswer(", "easeFor(")
        for ((token, owners) in allowed) {
            val found = offenders(token, if (token in layerOnly) layerFiles else mainSources).toSet()
            assertTrue("'$token' may only appear in $owners, found in $found", found.isNotEmpty() && owners.containsAll(found))
        }
    }

    @Test
    fun `the integration layer uses no forbidden technology or blocking calls`() {
        val forbidden = listOf(
            "androidx.work", "androidx.room", "SQLiteDatabase.", "openOrCreateDatabase", "rawQuery",
            "getDatabasePath", "/data/data", "GlobalScope", "Thread.sleep", "runBlocking",
            "SystemClock", "Dispatchers.Main"
        )
        for (token in forbidden) {
            val found = offenders(token, layerFiles)
            assertTrue("'$token' must not appear in the integration layer's code: $found", found.isEmpty())
        }
    }

    // ---------------------------------------------------------------- no shadow scheduler

    /**
     * GATE 06 §177-§179 — the review path must *report* the scheduler, never imitate it.
     *
     * The scan is deliberately narrow (only the files that implement scheduled review) so that it
     * fails for the right reason: a `sortedBy` in a review file means someone started ordering
     * cards, and a `LocalDate` there means someone started deciding what "today" is. Both are
     * Study-Agent deciding *why* a card is due, which INV-ANKI-REV-01/02 forbid.
     */
    @Test
    fun `the review path never orders, parks or time computes cards itself`() {
        val reviewFiles = layerFiles.filter {
            it.name.contains("Review") || it.name == "AnkiDroidBackend.kt"
        }
        assertTrue("the scan must actually see the review implementation", reviewFiles.size >= 3)

        val forbidden = listOf(
            // ordering / selection
            "sortedBy", "sortedWith", "sorted()", "compareBy", "minByOrNull", "maxByOrNull", "shuffled",
            // time and day arithmetic
            "LocalDate", "LocalDateTime", "ZonedDateTime", "Instant", "TimeZone", "java.time",
            "currentTimeMillis", "nanoTime", "Calendar",
            // prefetching and queue building
            "prefetch", "ArrayDeque", "List<AnkiReviewTurn>", "MutableList<AnkiReviewTurn>",
            "List<AnkiScheduledCard>", "MutableList<AnkiScheduledCard>"
        )
        for (token in forbidden) {
            val found = offenders(token, reviewFiles)
            assertTrue("'$token' would be Study-Agent scheduling cards locally: $found", found.isEmpty())
        }
    }

    /**
     * GATE 06 §177 — the endpoint's own vocabulary stops at this layer.
     *
     * If `button_count` or `next_review_times` appeared in a ViewModel, the UI would be reasoning
     * about scheduler columns; if the URI path appeared there, a second, unbounded caller could
     * bypass the gateway's validation (limit bounds, deck ownership, single-flight).
     */
    @Test
    fun `the scheduled review endpoint's vocabulary never leaves the integration layer`() {
        val tokens = listOf(
            "\"schedule\"",
            "\"button_count\"",
            "\"next_review_times\"",
            "\"media_files\"",
            "\"deckID\"",
            "\"note_id\""
        )
        for (token in tokens) {
            val leaked = offenders(token, outsideLayer)
            assertTrue("'$token' belongs to the integration layer only: $leaked", leaked.isEmpty())
        }
        assertTrue(
            "the scan must actually see the endpoint pinned in the layer",
            layerFiles.any { it.code().contains("\"button_count\"") }
        )
    }

    /**
     * GATE 06 §178 — neither Anki layer may contain date/time arithmetic.
     *
     * The scheduler owns the day boundary, the cutoff and the timezone (§113/§114); "how long ago"
     * is measured with the injected `AppClock`, which is not a calendar.
     */
    @Test
    fun `no anki layer computes calendars or due dates`() {
        val ankiLayers = mainSources.filter {
            val path = it.path.replace('\\', '/')
            path.contains("/core/anki/") || path.contains("/data/anki/")
        }
        assertTrue(ankiLayers.size >= 10)
        for (token in listOf("java.time", "TimeZone", "Calendar", "LocalDate", "epochDay", "dayOfYear")) {
            val found = offenders(token, ankiLayers)
            assertTrue("'$token' means Study-Agent is deciding when a card is due: $found", found.isEmpty())
        }
    }

    // ---------------------------------------------------------------- one state hierarchy

    @Test
    fun `Anki availability remains exactly one hierarchy`() {
        val duplicateDeclarations = Regex(
            "\\b(class|object|interface|enum class)\\s+" +
                "(AnkiDroidAvailability2|AnkiAvailability2|AnkiStatus|AnkiHealthState|" +
                "AnkiConnectionStatus|AnkiBackendStatus)\\b"
        )
        val offenders = mainSources.filter { it.code().contains(duplicateDeclarations) }.map { it.name }
        assertTrue("no second availability/health hierarchy may exist: $offenders", offenders.isEmpty())

        val declarations = mainSources.count { it.code().contains("sealed interface AnkiAvailability") }
        assertEquals("AnkiAvailability is the single vocabulary", 1, declarations)
    }

    // ---------------------------------------------------------------- the manifest

    @Test
    fun `the manifest asks for the AnkiDroid integration, and nothing more`() {
        val manifest = File(appModuleDir, "src/main/AndroidManifest.xml").xmlWithoutComments()

        assertTrue(
            "Study-Agent declares the permission the AnkiDroid provider enforces",
            manifest.contains("""<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE" />""")
        )
        assertTrue(
            "package visibility is declared the minimal way: one package in <queries>",
            manifest.contains("""<package android:name="com.ichi2.anki" />""")
        )
        for (forbidden in listOf(
            "com.ichi2.anki.debug",
            "QUERY_ALL_PACKAGES",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "MANAGE_EXTERNAL_STORAGE",
            "READ_MEDIA_"
        )) {
            assertFalse(
                "the release manifest must not declare '$forbidden'",
                manifest.contains(forbidden)
            )
        }
    }

    @Test
    fun `the debug endpoint lives in the debug manifest only`() {
        val debugManifest = File(appModuleDir, "src/debug/AndroidManifest.xml")
        assertTrue("expected ${debugManifest.path}", debugManifest.isFile)

        val text = debugManifest.xmlWithoutComments()
        assertTrue(
            text.contains("""<uses-permission android:name="com.ichi2.anki.debug.permission.READ_WRITE_DATABASE" />""")
        )
        assertTrue(text.contains("""<package android:name="com.ichi2.anki.debug" />"""))
    }
}
