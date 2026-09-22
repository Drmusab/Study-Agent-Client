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
        "AnkiDroidProviderClient.kt",
        "AnkiDroidMapper.kt"
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
            "import com.studyagent.client.core.common."
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
    fun `the integration layer never writes and links no AnkiDroid internals`() {
        val forbidden = listOf(
            // mutations (§67/§79/§80) — the provider is read through one bounded query only
            ".insert(", ".update(", ".delete(", "openFileDescriptor", "openInputStream",
            "openOutputStream", "ContentProviderOperation", "resolver.call(",
            // GATE 06+ APIs that must not exist yet
            "addNote(", "addNotes(", "updateNote(", "addMediaFromUri(", "addNewDeck(",
            "selectDeckWithCheck", "getNextCard(", "commitRating(",
            // AnkiDroid internals: never linked, never copied
            "FlashCardsContract", "ReviewInfo", "libanki", "Reviewer"
        )
        for (token in forbidden) {
            val found = offenders(token, layerFiles)
            assertTrue("'$token' must not appear in the integration layer's code: $found", found.isEmpty())
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
