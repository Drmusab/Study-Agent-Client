package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.data.anki.ankidroid.AnkiDroidErrorMapper
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailure
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.AnkiDroidOperationStage
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 04 — error mapping tests (§26-§28, §76-§78).
 */
class AnkiDroidErrorMapperTest {

    @Test
    fun `SecurityException maps to PermissionRequired`() {
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.PERMISSION_DENIED,
            evidence = AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE,
            exceptionClass = "SecurityException",
            evidenceToken = "Permission not granted"
        )
        val error = AnkiDroidErrorMapper.mapFailure(failure, "test_op")
        assertTrue(error is AnkiError.PermissionRequired)
    }

    @Test
    fun `collection not ready maps to CollectionUnavailable`() {
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.COLLECTION_NOT_READY,
            evidence = AnkiDroidFailureEvidence.COLLECTION_SETUP_SIGNATURE,
            evidenceToken = "storage is not yet configured"
        )
        val error = AnkiDroidErrorMapper.mapFailure(failure, "collection_probe")
        assertTrue(error is AnkiError.CollectionUnavailable)
    }

    @Test
    fun `timeout maps to QueryFailure timeout`() {
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.TIMEOUT,
            evidence = AnkiDroidFailureEvidence.TIMEOUT_BUDGET,
            evidenceToken = "probe_budget_collection_probe"
        )
        val error = AnkiDroidErrorMapper.mapFailure(failure, "health_probe")
        assertTrue(error is AnkiError.QueryFailure)
        assertEquals("timeout", (error as AnkiError.QueryFailure).causeCategory)
    }

    @Test
    fun `endpoint unavailable maps to ProviderUnavailable`() {
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.ENDPOINT_UNAVAILABLE,
            evidence = AnkiDroidFailureEvidence.PROVIDER_NOT_RESOLVED
        )
        val error = AnkiDroidErrorMapper.mapFailure(failure, "provider_resolution")
        assertTrue(error is AnkiError.ProviderUnavailable)
    }

    @Test
    fun `unsupported api maps to UnsupportedApi`() {
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.UNSUPPORTED_API,
            evidence = AnkiDroidFailureEvidence.SPEC_BELOW_MINIMUM,
            evidenceToken = "0"
        )
        val error = AnkiDroidErrorMapper.mapFailure(failure, "spec_check")
        assertTrue(error is AnkiError.UnsupportedApi)
    }

    @Test
    fun `contract mismatch maps to UnsupportedAction`() {
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.CONTRACT_MISMATCH,
            evidence = AnkiDroidFailureEvidence.PROBE_CONTRACT_REJECTED,
            evidenceToken = "Unknown column"
        )
        val error = AnkiDroidErrorMapper.mapFailure(failure, "deck_query")
        assertTrue(error is AnkiError.UnsupportedAction)
    }

    @Test
    fun `unexpected maps to Unknown with exception class`() {
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.UNEXPECTED,
            evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
            exceptionClass = "NullPointerException"
        )
        val error = AnkiDroidErrorMapper.mapFailure(failure, "unknown_op")
        assertTrue(error is AnkiError.Unknown)
        assertEquals("NullPointerException", (error as AnkiError.Unknown).cause)
    }

    @Test
    fun `mapThrowable uses classifier`() {
        val throwable = SecurityException("Permission not granted")
        val error = AnkiDroidErrorMapper.mapThrowable(throwable, "test", AnkiDroidOperationStage.COLLECTION_PROBE)
        assertTrue(error is AnkiError.PermissionRequired)
    }

    @Test
    fun `diagnostic info contains operation and category`() {
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.PERMISSION_DENIED,
            evidence = AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE,
            exceptionClass = "SecurityException"
        )
        val info = AnkiDroidErrorMapper.diagnosticInfo(failure, "health_probe", null)
        assertTrue(info.contains("op=health_probe"))
        assertTrue(info.contains("cat=PERMISSION_DENIED"))
        assertTrue(info.contains("exc=SecurityException"))
    }

    @Test
    fun `diagnostic info never contains card content`() {
        // Ensure we never log card/note text (§67)
        val failure = AnkiDroidFailure(
            category = AnkiDroidFailureCategory.PROVIDER_ERROR,
            evidence = AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE
        )
        val info = AnkiDroidErrorMapper.diagnosticInfo(failure, "deck_query", null)
        // Should not contain question/answer patterns
        assertFalse(info.contains("question"))
        assertFalse(info.contains("answer"))
    }
}
