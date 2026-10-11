package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 21C PART II — repository source audit (AUDIT-21C-01 … AUDIT-21C-15).
 *
 * These are the structural rules a behavioural test cannot see from outside: *which* vocabulary the
 * identity model is allowed to contain, *what* the persistence layer may derive, and *where* the
 * identity module may be consumed. They follow the GATE 12–21 convention: rules are checked on source
 * text with comments stripped, so a document may explain a forbidden spelling while live code may not
 * use it.
 *
 * The locked contract is `docs/BACKEND_IDENTITY_CONTRACT.md`; the decisions are DECISION-21B-01…04
 * and DECISION-21B-17.
 */
class Gate21cArchitectureAuditTest {

    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private val mainRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")

    /** The three files this gate adds. They are the identity model, and nothing else. */
    private val identityModel = listOf(
        "core/anki/AnkiIdentityAssurance.kt",
        "core/anki/AnkiIdentityContinuation.kt",
        "core/anki/AnkiBackendContext.kt"
    )

    /** Source with comments removed, so documentation cannot satisfy (or break) a rule. */
    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)\\s*//.*$"), " ")

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 21C source $relative" }
        return file
    }

    private fun mainSources(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun identityCode(): String = identityModel.joinToString("\n") { main(it).code() }

    private fun assertNoTokens(text: String, tokens: List<String>, scope: String) {
        tokens.forEach { token ->
            assertFalse("$scope must not contain `$token`", text.contains(token))
        }
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-01

    @Test
    fun `the locked assurance vocabulary and operation classes are exactly what is encoded`() {
        val assurance = main("core/anki/AnkiIdentityAssurance.kt").code()
        listOf(
            "AuthoritativeMatch",
            "SessionContinuityVerified",
            "IdentityUnverifiable",
            "AuthoritativeMismatch",
            "AnkiUnverifiabilityQualifier",
            "NOMINAL",
            "DEGRADED"
        ).forEach { assertTrue("the assurance model must encode `$it`", assurance.contains(it)) }

        val matrix = main("core/anki/AnkiIdentityContinuation.kt").code()
        listOf("OP_R1", "OP_R2", "OP_S1", "OP_S2", "OP_M1", "OP_M2", "OP_M3", "OP_P").forEach {
            assertTrue("the matrix must name `$it`", matrix.contains(it))
        }
        // §3.2: a nominal absence is never upgraded, and a lost key is mismatch-grade.
        assertTrue(assurance.contains("DEGRADED"))
        assertTrue(matrix.contains("IDENTITY_UNVERIFIABLE_DEGRADED"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-02

    @Test
    fun `the identity model never substitutes a kind for a configured instance`() {
        assertNoTokens(
            identityCode(),
            listOf("AnkiBackendKind", "AnkiBackendLocality"),
            "the identity model"
        )
        // A descriptor still refuses a kind that contradicts its identity.
        assertTrue(
            main("core/anki/AnkiBackendDescriptor.kt").code()
                .contains("require(kind == id.kind) { \"A descriptor's kind must match its identity\" }")
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-03

    @Test
    fun `no collection key sentinel is ever constructed in production sources`() {
        // `collectionKey` is an opaque backend-issued token: a client-written literal would be the
        // invented default the contract forbids. Null is the only "no evidence" spelling.
        mainSources().forEach { file ->
            val code = file.code()
            assertFalse(
                "${file.name} must not write a literal collection key",
                Regex("collectionKey\\s*=\\s*\"").containsMatchIn(code)
            )
        }
        assertTrue(
            main("core/anki/AnkiRefs.kt").code()
                .contains("val hasAuthoritativeKey: Boolean get() = collectionKey != null")
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-04

    @Test
    fun `every resource key is composed from its backend and collection without a sentinel`() {
        val refs = main("core/anki/AnkiRefs.kt").code()
        assertFalse("an absent collection is never spelled as a sentinel", refs.contains("?: \"?\""))

        // Model and template keys use the same length-prefixed composition as the other refs, so two
        // different (collection, id) pairs can never compose one identity.
        listOf("core/anki/AnkiNoteModels.kt", "core/anki/AnkiTemplateModels.kt").forEach { path ->
            val code = main(path).code()
            assertTrue("$path must compose its key with identityKey", code.contains("identityKey("))
        }

        // Media references stay context-scoped: the hydration/scheduled types are never persisted
        // (no serializable envelope may carry a media name out of its creating context).
        val media = main("core/anki/AnkiModels.kt").code()
        assertTrue(media.contains("sealed interface AnkiMediaRef"))
        assertFalse("media references are live-state only", media.contains("@Serializable"))
        listOf(
            "core/anki/ReviewCommitLedger.kt",
            "core/anki/ReviewerActionLedger.kt",
            "core/anki/edit/NoteMutationLedger.kt",
            "core/anki/create/NoteCreationLedger.kt"
        ).forEach { path ->
            assertFalse("$path must not persist a media reference", main(path).code().contains("AnkiMediaRef"))
        }
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-05

    @Test
    fun `identity values are immutable and never hand-roll their equality`() {
        listOf(identityCode(), main("core/anki/AnkiRefs.kt").code()).forEach { code ->
            assertFalse("identity must not be mutable", Regex("\\bvar\\s").containsMatchIn(code))
            assertFalse("identity equality is structural", code.contains("override fun equals"))
            assertFalse("identity hashing is structural", code.contains("override fun hashCode"))
        }
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-06

    @Test
    fun `assurance is contextual evidence and is never persisted`() {
        // The four categories are computed values, not durable state: a stored "verified" would
        // outlive the connection it described.
        listOf(
            "core/anki/AnkiIdentityAssurance.kt",
            "core/anki/AnkiIdentityContinuation.kt"
        ).forEach { path ->
            assertFalse("$path must not be serializable", main(path).code().contains("@Serializable"))
        }
        // The identity context *may* be persisted, deliberately, and says so.
        assertTrue(main("core/anki/AnkiBackendContext.kt").code().contains("@Serializable"))

        listOf(
            "core/anki/ReviewCommitLedger.kt",
            "core/anki/ReviewerActionLedger.kt",
            "core/anki/edit/NoteMutationLedger.kt",
            "core/anki/create/NoteCreationLedger.kt"
        ).forEach { path ->
            assertNoTokens(
                main(path).code(),
                listOf("AnkiIdentityAssurance", "AnkiIdentityCheck", "AnkiContinuationVerdict"),
                path
            )
        }
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-07

    @Test
    fun `the persistence layer decodes ownership and never constructs or reassigns it`() {
        listOf(
            "core/anki/ReviewCommitLedger.kt",
            "core/anki/ReviewerActionLedger.kt",
            "core/anki/edit/NoteMutationLedger.kt",
            "core/anki/create/NoteCreationLedger.kt"
        ).forEach { path ->
            val code = main(path).code()
            assertNoTokens(
                code,
                listOf(
                    "AnkiBackendId.PcAgent(",
                    "AnkiBackendId.AnkiDroidLocal",
                    "AnkiBackendId.Fake(",
                    "AnkiBackendResolver",
                    "AnkiBackendPreferenceStore",
                    "AnkiBackendMode",
                    "AnkiBackendRegistry"
                ),
                path
            )
        }
        // The one sanctioned derivation decodes the identity the record itself carries.
        val commits = main("core/anki/ReviewCommitLedger.kt").code()
        assertTrue(commits.contains("parseTombstoneIdentity("))
        assertTrue(commits.contains("AnkiBackendId.fromStableId(parts[0])"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-08

    @Test
    fun `migration is versioned, total and parameterless beyond the snapshot itself`() {
        assertNoTokens(
            main("core/anki/ReviewCommitLedger.kt").code(),
            listOf("deleteUnreadable", "dropUnreadable", "assignOwner"),
            "the commit ledger"
        )
        val commits = main("core/anki/ReviewCommitLedger.kt").code()
        assertTrue(commits.contains("if (schemaVersion !in 1..SCHEMA_VERSION)"))
        assertTrue(commits.contains("normalizeLegacyVocabulary"))

        // A decoder can only ever see the snapshot: no backend, registry or preference can reach
        // the migration path, so no decode can consult a live backend.
        listOf(
            "core/anki/ReviewCommitLedger.kt" to "fun decode(snapshot: String): Decoded",
            "core/anki/ReviewerActionLedger.kt" to "fun decode(snapshot: String): Decoded",
            "core/anki/edit/NoteMutationLedger.kt" to "fun decode(text: String): List<NoteMutationRecord>?",
            "core/anki/create/NoteCreationLedger.kt" to "fun decode(text: String): List<NoteCreationRecord>?"
        ).forEach { (path, signature) ->
            assertTrue("$path must decode from the snapshot alone", main(path).code().contains(signature))
        }
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-09

    @Test
    fun `backend adapters preserve local ids and invent no context`() {
        val adapters = File(mainRoot, "data/anki").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(adapters.isNotEmpty())
        adapters.forEach { file ->
            val code = file.code()
            assertFalse("${file.name} must not invent a collection key", Regex("collectionKey\\s*=\\s*\"").containsMatchIn(code))
            assertFalse("${file.name} must not name a test double", code.contains("AnkiBackendId.Fake"))
        }
        // AnkiDroid honestly reports the absence of an authoritative collection discriminator.
        assertTrue(
            main("data/anki/ankidroid/AnkiDroidDeckMapper.kt").code()
                .contains("collectionKey = null")
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-10

    @Test
    fun `route decoding never consults a preference, a registry or a policy`() {
        val screen = main("ui/navigation/Screen.kt").code()
        assertNoTokens(
            screen,
            listOf(
                "AnkiBackendMode",
                "AnkiBackendResolver",
                "AnkiBackendRegistry",
                "BackendResolutionRequest",
                "preferenceStore"
            ),
            "navigation routes"
        )
        assertTrue(
            "a route decodes the identity it encoded",
            screen.contains("AnkiBackendId.fromStableId")
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-11

    @Test
    fun `the identity domain reaches no provider, storage or transport`() {
        assertNoTokens(
            identityCode(),
            listOf(
                "android.",
                "androidx.",
                "java.io.",
                "com.ichi2",
                "ContentResolver",
                "getContentResolver",
                "SQLite",
                "okhttp",
                "AnkiBackend(",
                "AnkiDroidBackend"
            ),
            "the identity model"
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-12

    @Test
    fun `the identity module is not a second resolver, registry or settings owner`() {
        assertNoTokens(
            identityCode(),
            listOf(
                "AnkiBackendRegistry",
                "AnkiBackendResolver",
                "AnkiBackendPreferenceStore",
                "AnkiBackendMode",
                "BackendResolutionRequest",
                "preferenceStore",
                ".resolve(",
                "commitRating",
                "AnkiBackendSelector"
            ),
            "the identity model"
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-13

    @Test
    fun `gate 21c adds no registry, resolver, auto routing or failover`() {
        mainSources().forEach { file ->
            val code = file.code()
            listOf(
                "class BackendRegistry",
                "class BackendResolver",
                "class AnkiBackendAutoResolver",
                "fun resolveAuto",
                "AUTO_RESOLUTION"
            ).forEach { token ->
                assertFalse("${file.name} must not contain `$token`", code.contains(token))
            }
        }
        // No failover spelling may appear in the identity model at all.
        assertNoTokens(
            identityCode(),
            listOf("alternateBackend", "fallbackBackend", "switchBackend", "tryOtherBackend"),
            "the identity model"
        )
        // The composition root still registers exactly the one real backend it registered before.
        val container = main("di/AppContainer.kt").code()
        assertTrue(container.contains("AnkiBackendRegistry(listOf(ankiDroidBackend))"))
        assertFalse(container.contains("PcAnkiBackend("))
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-14

    @Test
    fun `identity vocabulary never becomes transaction truth`() {
        assertNoTokens(
            identityCode(),
            listOf(
                "ReviewCommitStatus",
                "ReviewerActionStatus",
                "NoteMutationStatus",
                "NoteCreationStatus",
                "Ledger",
                "Coordinator",
                "Reconciler"
            ),
            "the identity model"
        )
        // GATE 21C wires the identity module into no runtime routing path: the study runtime is
        // untouched, so no scheduler or mutation decision can depend on it yet. The sub-gate that
        // introduces the first consumer (21F/21G) replaces this assertion with its own.
        val study = File(mainRoot, "core/study").listFiles()!!.filter { it.extension == "kt" }
        assertTrue(study.isNotEmpty())
        study.forEach { file ->
            assertNoTokens(
                file.code(),
                listOf("AnkiIdentityAssurance", "AnkiIdentityContinuation", "AnkiBackendContext", "AnkiIdentityCheck"),
                file.name
            )
        }
    }

    // ----------------------------------------------------------------------------- AUDIT-21C-15

    @Test
    fun `identity strings carry no secret, endpoint or credential`() {
        val code = identityCode()
        listOf("password", "passphrase", "secret", "credential", "apiKey", "authToken").forEach { token ->
            assertFalse("the identity model must not contain `$token`", code.contains(token))
        }
        // Short words are matched whole: `import` contains "port", and identity has none of these.
        listOf("\\bhost\\b", "\\bport\\b", "\\btoken\\b", "\\bendpoint\\b", "\\bcredentials?\\b", "https?://").forEach { pattern ->
            assertFalse("the identity model must not contain `$pattern`", Regex(pattern).containsMatchIn(code))
        }
        // The one PC discriminator is already public and content-free: it is the profile id.
        assertEquals(
            "pc_agent:desktop-a",
            com.studyagent.client.core.anki.AnkiBackendId.PcAgent("desktop-a").stableId
        )
    }
}
