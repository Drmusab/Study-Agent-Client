package com.studyagent.client.anki.ankidroid

import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureClassifier
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.AnkiDroidOperationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 02 §20/§21/§59/§60/§61 — the failure classifier, the component that decides *why*
 * Study-Agent cannot talk to AnkiDroid.
 *
 * A wrong classification is not a cosmetic bug: "PermissionRequired" sends the user to a
 * permission screen, "CollectionNotInitialized" tells them to finish AnkiDroid setup, and
 * "NotInstalled" tells them to install the app. The two rules this test defends hardest are
 * therefore:
 *
 *  - a `SecurityException` is never swallowed and never relabelled (§20/§60);
 *  - an `IllegalStateException` whose message matches no *documented* setup signature is reported
 *    as a provider error, never as "your setup is incomplete" (§61 — the classifier must not
 *    invent an explanation).
 */
class AnkiDroidFailureClassifierTest {

    /** Stand-in for AnkiDroid's own exception type: matched by class name, never linked. */
    private class SystemStorageException(message: String) : RuntimeException(message)

    /** Stand-in for a binder transport failure. */
    private class DeadObjectException(message: String) : RuntimeException(message)

    private fun classify(
        throwable: Throwable,
        stage: AnkiDroidOperationStage = AnkiDroidOperationStage.COLLECTION_PROBE
    ) = AnkiDroidFailureClassifier.classify(throwable, stage)

    @Test
    fun `SecurityException is permission denied and never swallowed`() {
        val failure = classify(SecurityException("Permission not granted for: com.ichi2.anki.flashcards"))

        assertEquals(AnkiDroidFailureCategory.PERMISSION_DENIED, failure.category)
        assertEquals(AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE, failure.evidence)
        assertEquals("SecurityException", failure.exceptionClass)
        assertEquals("PERMISSION_DENIED/SECURITY_EXCEPTION_TYPE/Permission not granted", failure.technicalLabel)
    }

    @Test
    fun `SecurityException wins over any other signature in the same message`() {
        // Order matters: a provider that refuses on permission while its message happens to
        // mention storage must still be reported as a permission problem — the remedy differs.
        val failure = classify(
            SecurityException("storage is not yet configured"),
            AnkiDroidOperationStage.COLLECTION_PROBE
        )

        assertEquals(AnkiDroidFailureCategory.PERMISSION_DENIED, failure.category)
        assertEquals(AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE, failure.evidence)
    }

    @Test
    fun `a permission signature is recognised without the exception type`() {
        val failure = classify(IllegalStateException("Permission not granted for: authority"))

        assertEquals(AnkiDroidFailureCategory.PERMISSION_DENIED, failure.category)
        assertEquals(AnkiDroidFailureEvidence.SECURITY_SIGNATURE, failure.evidence)
        assertEquals("Permission not granted", failure.evidenceToken)
    }

    @Test
    fun `documented setup signatures map to collection not ready`() {
        val documented = listOf(
            "storage is not yet configured",
            "AnkiDroid's storage is not yet configured (first-run setup incomplete)",
            "collection has not been initialized",
            "no collection available"
        )

        for (message in documented) {
            val failure = classify(IllegalStateException(message))
            assertEquals(
                "message should be recognised as collection setup: $message",
                AnkiDroidFailureCategory.COLLECTION_NOT_READY,
                failure.category
            )
            assertEquals(AnkiDroidFailureEvidence.COLLECTION_SETUP_SIGNATURE, failure.evidence)
        }
    }

    @Test
    fun `an undocumented IllegalStateException is a provider error not a setup problem`() {
        // §61: the type is known, the cause is not. Reporting "finish setup" here would be a
        // guess presented as a fact, and would hide a real defect behind a user-action message.
        val failure = classify(IllegalStateException("Backend returned an unexpected state"))

        assertEquals(AnkiDroidFailureCategory.PROVIDER_ERROR, failure.category)
        assertEquals(AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE, failure.evidence)
        assertEquals("IllegalStateException", failure.exceptionClass)
        assertNull(failure.evidenceToken)
    }

    @Test
    fun `lock signatures map to collection locked`() {
        val failure = classify(IllegalStateException("The database is locked by another process"))

        assertEquals(AnkiDroidFailureCategory.COLLECTION_LOCKED, failure.category)
        assertEquals(AnkiDroidFailureEvidence.LOCK_SIGNATURE, failure.evidence)
    }

    @Test
    fun `storage failures map to collection unavailable`() {
        val failure = classify(SystemStorageException("boom"))

        assertEquals(AnkiDroidFailureCategory.COLLECTION_UNAVAILABLE, failure.category)
        assertEquals(AnkiDroidFailureEvidence.STORAGE_EXCEPTION, failure.evidence)
        assertEquals("SystemStorageException", failure.evidenceToken)
    }

    @Test
    fun `binder transport failures map to provider error`() {
        val failure = classify(DeadObjectException("binder died"))

        assertEquals(AnkiDroidFailureCategory.PROVIDER_ERROR, failure.category)
        assertEquals(AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE, failure.evidence)
        assertEquals("DeadObjectException", failure.evidenceToken)
    }

    @Test
    fun `the provider rejecting our own query shape is a contract mismatch`() {
        val withSignature = classify(IllegalArgumentException("URI content://x/y is not supported"))
        assertEquals(AnkiDroidFailureCategory.CONTRACT_MISMATCH, withSignature.category)
        assertEquals(AnkiDroidFailureEvidence.PROBE_CONTRACT_REJECTED, withSignature.evidence)
        assertEquals("is not supported", withSignature.evidenceToken)

        val byType = classify(UnsupportedOperationException("nope"))
        assertEquals(AnkiDroidFailureCategory.CONTRACT_MISMATCH, byType.category)
        assertEquals("UnsupportedOperationException", byType.evidenceToken)
    }

    @Test
    fun `an unknown throwable stays unknown instead of being guessed`() {
        val failure = classify(RuntimeException("something else entirely"))

        assertEquals(AnkiDroidFailureCategory.UNEXPECTED, failure.category)
        assertEquals(AnkiDroidFailureEvidence.UNCLASSIFIED, failure.evidence)
        assertEquals("RuntimeException", failure.exceptionClass)
    }

    @Test
    fun `an empty message never matches a signature`() {
        val failure = classify(IllegalStateException())

        assertEquals(AnkiDroidFailureCategory.PROVIDER_ERROR, failure.category)
        assertNull(failure.evidenceToken)
    }

    @Test
    fun `timeouts are never inferred from an exception type`() {
        val failure = AnkiDroidFailureClassifier.timedOut(AnkiDroidOperationStage.COLLECTION_PROBE)

        assertEquals(AnkiDroidFailureCategory.TIMEOUT, failure.category)
        assertEquals(AnkiDroidFailureEvidence.TIMEOUT_BUDGET, failure.evidence)
        assertNull(failure.exceptionClass)
        assertEquals("probe_budget_collection_probe", failure.evidenceToken)
    }

    @Test
    fun `classification is content free and never carries raw provider text`() {
        val secretLooking = "Permission not granted for: card 12345 note 'my private note text'"
        val failure = classify(SecurityException(secretLooking))

        for (text in listOfNotNull(
            failure.exceptionClass,
            failure.evidenceToken,
            failure.technicalLabel
        )) {
            assertTrue(
                "failure details must not leak provider text: $text",
                !text.contains("private") && !text.contains("12345")
            )
        }
        assertNotEquals(secretLooking, failure.evidenceToken)
    }

    @Test
    fun `every stage produces a classifiable failure`() {
        for (stage in AnkiDroidOperationStage.values()) {
            val failure = classify(SecurityException("denied"), stage)
            assertEquals(AnkiDroidFailureCategory.PERMISSION_DENIED, failure.category)

            val timeout = AnkiDroidFailureClassifier.timedOut(stage)
            assertTrue(timeout.evidenceToken!!.contains(stage.name.lowercase()))
        }
    }
}
