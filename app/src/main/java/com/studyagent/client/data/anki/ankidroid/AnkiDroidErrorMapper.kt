package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError

/**
 * GATE 04 — error mapping layer (§26).
 *
 * Maps Android/provider failures into typed Anki domain errors.
 * Preserves root cause for diagnostics (§28) without leaking stack traces to UI.
 *
 * Mapping table:
 * SecurityException → PermissionRequired
 * provider not resolved → BackendUnavailable / ProviderUnavailable
 * collection initialization → CollectionUnavailable / CollectionNotInitialized
 * unsupported provider contract → UnsupportedAction / UnsupportedApi
 * timeout → QueryFailure(timeout)
 * null cursor / malformed → QueryFailure / Unknown
 * unknown → BackendFailure / Unknown
 */
object AnkiDroidErrorMapper {

    /**
     * Maps a classified [AnkiDroidFailure] to domain [AnkiError] with operation context (§29).
     */
    fun mapFailure(
        failure: AnkiDroidFailure,
        operation: String
    ): AnkiError = when (failure.category) {
        AnkiDroidFailureCategory.PERMISSION_DENIED ->
            AnkiError.PermissionRequired()

        AnkiDroidFailureCategory.COLLECTION_NOT_READY ->
            AnkiError.CollectionUnavailable()

        AnkiDroidFailureCategory.COLLECTION_UNAVAILABLE ->
            AnkiError.CollectionUnavailable()

        AnkiDroidFailureCategory.COLLECTION_LOCKED ->
            AnkiError.QueryFailure(causeCategory = "collection-locked")

        AnkiDroidFailureCategory.TIMEOUT ->
            AnkiError.QueryFailure(causeCategory = "timeout")

        AnkiDroidFailureCategory.UNSUPPORTED_API ->
            AnkiError.UnsupportedApi(
                specVersion = failure.evidenceToken?.toIntOrNull(),
                minimumSpec = AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC
            )

        AnkiDroidFailureCategory.CONTRACT_MISMATCH ->
            AnkiError.UnsupportedAction(action = "provider_contract_mismatch")

        AnkiDroidFailureCategory.ENDPOINT_UNAVAILABLE ->
            AnkiError.ProviderUnavailable(detail = failure.evidenceToken)

        AnkiDroidFailureCategory.PROVIDER_ERROR ->
            AnkiError.QueryFailure(causeCategory = failure.evidence.name.lowercase())

        AnkiDroidFailureCategory.UNEXPECTED ->
            AnkiError.Unknown(cause = failure.exceptionClass ?: "unexpected:$operation")
    }

    /**
     * Maps a generic Throwable to AnkiError, preserving classification.
     */
    fun mapThrowable(
        throwable: Throwable,
        operation: String,
        stage: AnkiDroidOperationStage = AnkiDroidOperationStage.COLLECTION_PROBE
    ): AnkiError {
        val failure = AnkiDroidFailureClassifier.classify(throwable, stage)
        return mapFailure(failure, operation)
    }

    /**
     * Maps ProviderQueryResult.Failure to AnkiError.
     */
    fun <T> mapQueryResultFailure(
        result: ProviderQueryResult.Failure<T>
    ): AnkiError = mapFailure(result.failure, result.operation)

    /**
     * Sanitized diagnostic info for logging (§28/§67): exception class + operation + URI category.
     * Never logs card/note text.
     */
    fun diagnosticInfo(
        failure: AnkiDroidFailure?,
        operation: String,
        throwable: Throwable? = null
    ): String {
        val parts = mutableListOf<String>()
        parts.add("op=$operation")
        failure?.let {
            parts.add("cat=${it.category.name}")
            parts.add("ev=${it.evidence.name}")
            it.exceptionClass?.let { cls -> parts.add("exc=$cls") }
            it.evidenceToken?.let { token -> parts.add("token=$token") }
        }
        throwable?.let {
            parts.add("throw=${it::class.java.simpleName}")
        }
        return parts.joinToString(" ")
    }
}
