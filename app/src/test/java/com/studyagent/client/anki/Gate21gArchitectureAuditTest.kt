package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 21G — repository source audit (AUDIT-21G-01 … AUDIT-21G-06).
 *
 * The structural rules a behavioural test cannot see from outside: the lock carries identity
 * only, 21G adds no second resolver/registry/scheduler/ledger/queue, admission precedes
 * scheduler traffic, and the continuation/restore policies route nothing. Convention follows the
 * GATE 12–21 audits: rules are checked on source text with comments stripped.
 */
class Gate21gArchitectureAuditTest {

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
        check(file.isFile) { "Missing GATE 21G source $relative" }
        return file
    }

    // ------------------------------------------------------------- AUDIT-21G-01: no new truth

    @Test fun `gate 21g adds no second resolver registry scheduler ledger or queue`() {
        listOf(
            "core/anki/StudySessionBackendLock.kt",
            "core/anki/StudySessionLockStore.kt",
            "core/study/StudySessionOwnershipProjection.kt"
        ).forEach { relative ->
            val code = main(relative).code()
            listOf(
                "class AnkiBackendResolver", "object AnkiBackendResolver",
                "class AnkiBackendRegistry", "fun resolveAuto", "fun resolve(",
                "ReviewCommitLedger(", "ReviewerActionLedger(", "OfflineQueue", "WorkManager"
            ).forEach { token ->
                assertFalse(
                    "$relative must not declare $token",
                    code.contains(token)
                )
            }
        }
    }

    // ------------------------------------------------- AUDIT-21G-02: the lock is identity only

    @Test fun `the persisted lock carries identity, never capability or policy`() {
        val code = main("core/anki/StudySessionBackendLock.kt").code()
        val lockBody = code.substringAfter("data class StudySessionBackendLock(")
            .substringBefore("\n) {")
        listOf("studySessionId", "backendId", "collection", "deckRef", "startedAtEpochMs", "schemaVersion")
            .forEach { field -> assertTrue("the lock must carry $field", lockBody.contains(field)) }
        listOf(
            "capabilit", "availab", "preference", "Preference", "AUTO", "transport", "Transport",
            "endpoint", "credential", "turn", "Turn", "schedul", "question", "answer", "ledger", "Ledger"
        ).forEach { token ->
            assertFalse("the lock body must not mention $token", lockBody.contains(token))
        }
    }

    // --------------------------------------- AUDIT-21G-03: admission precedes scheduler traffic

    @Test fun `session admission runs before the backend accepts the session`() {
        val executor = main("core/study/AnkiStudyEffectExecutor.kt").code()
        val beginBody = executor.substringAfter("private suspend fun begin(")
            .substringBefore("private suspend fun commitTransaction(")
        val admission = beginBody.indexOf("StudySessionAdmissionPolicy.admit(")
        val accept = beginBody.indexOf(".beginReview(")
        assertTrue("begin() must admit through the 21G policy", admission >= 0)
        assertTrue("begin() must hand the admitted context to the backend", accept >= 0)
        assertTrue(
            "admission must precede the backend call",
            admission < accept
        )
        assertTrue(
            "a refused admission must fail the start",
            beginBody.contains("is StudySessionAdmission.Refused -> return failed(")
        )
    }

    // -------------------------------------- AUDIT-21G-04: continuation and restore route nothing

    @Test fun `continuation and restore consult no resolver registry preference or sibling`() {
        val code = main("core/anki/StudySessionBackendLock.kt").code()
        val policies = code.substringAfter("object StudySessionContinuationPolicy")
        listOf(
            "AnkiBackendResolver", "AnkiBackendRegistry", "BackendPreference", "preferenceStore",
            "AnkiBackendSelector", ".resolve(", "alternateBackend", "fallbackBackend",
            "tryOtherBackend", "switchBackend"
        ).forEach { token ->
            assertFalse("continuation/restore must not mention $token", policies.contains(token))
        }
    }

    // ----------------------------------------------- AUDIT-21G-05: the store moves bytes only

    @Test fun `the lock store exposes save load and clear only`() {
        val code = main("core/anki/StudySessionLockStore.kt").code()
        val face = code.substringAfter("interface StudySessionLockStore")
            .substringBefore("\n}")
        assertTrue(face.contains("fun save("))
        assertTrue(face.contains("fun load("))
        assertTrue(face.contains("fun clear("))
        listOf("fun update", "fun rebind", "fun migrate", "fun resolve", "fun refresh").forEach { token ->
            assertFalse("the store must not expose $token", face.contains(token))
        }
    }

    // ------------------------------------------ AUDIT-21G-06: the projection decides nothing

    @Test fun `the ownership projection is data only`() {
        val code = main("core/study/StudySessionOwnershipProjection.kt").code()
        assertFalse(
            "the projection must not reach into data/",
            code.contains("com.studyagent.client.data.")
        )
        listOf("AnkiRenderedCard", "AnkiReviewTurnContent", "ReviewCommitLedger", "ReviewerActionLedger")
            .forEach { token ->
                assertFalse("the projection must not carry $token", code.contains(token))
            }
        val imports = main("core/study/StudySessionOwnershipProjection.kt").readLines()
            .filter { it.startsWith("import ") }
        assertEquals(
            "the projection reads core state only",
            emptyList<String>(),
            imports.filter { it.contains("com.studyagent.client.data.") }
        )
    }
}
