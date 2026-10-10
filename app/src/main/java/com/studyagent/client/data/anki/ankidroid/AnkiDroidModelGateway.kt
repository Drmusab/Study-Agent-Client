package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.core.common.elapsedSince
import kotlinx.coroutines.CancellationException

/**
 * GATE 19 — enriched note-model gateway.
 *
 * One job: given a stable [AnkiNoteModelRef], perform one exact `models/<id>` read with the GATE 19
 * enriched projection (CSS / sort field / note count / LaTeX metadata), one `models/<id>/templates`
 * read for template metadata, and map the outcome into a domain [AnkiDroidEnrichedModelSnapshot].
 *
 * The gateway is strictly read-only and uses only the pinned public ContentProvider contract. No
 * private DB, no AnkiDroid classes and no template rendering. The CSS/template source returned here
 * is inspection metadata (INV-19-12); rendering stays with the backend (GATE 07).
 *
 * Unknown/missing optional columns (latex_*, browser_qfmt, etc.) degrade to null on a per-row
 * basis, because some AnkiDroid releases skip unknown projection names rather than throwing
 * (documented at GATE 05/07).
 */
interface AnkiDroidModelGateway {

    /**
     * Read one enriched model snapshot (model metadata + templates + CSS when exposed).
     *
     * Failures:
     * - [AnkiError.NoteModelNotFound]: no row for [ref].
     * - [AnkiError.MalformedResponse]: required identity/name/field columns are unusable.
     * - Ordinary availability/permission/collection/query failure family.
     * - Template load failure degrades gracefully: if the templates endpoint cannot be read, the
     *   model is returned with an empty template list and a degradation token rather than failing
     *   the whole load (templates are a sub-resource; failing the model for unreadable templates
     *   would make model details unavailable on older AnkiDroid builds).
     */
    suspend fun queryEnrichedModel(
        authority: String,
        ref: AnkiNoteModelRef
    ): AnkiResult<AnkiDroidEnrichedModelSnapshot>

    /** Content-free diagnostics for the last query. */
    fun lastQueryDiagnostics(): AnkiDroidModelQueryDiagnostics
}

data class AnkiDroidModelQueryDiagnostics(
    val lastStatus: String,
    val lastModelId: String? = null,
    val templateCount: Int? = null,
    val cssAvailable: Boolean? = null,
    val degradations: List<String> = emptyList(),
    val lastErrorCategory: String? = null,
    val lastQueryDurationMs: Long? = null,
    val providerQueryCount: Long = 0L
) {
    companion object {
        val NONE = AnkiDroidModelQueryDiagnostics(lastStatus = "NONE")
    }
}

/** Internal gateway-level snapshot; the domain [AnkiNoteModelEnriched] is built from this. */
data class AnkiDroidEnrichedModelSnapshot(
    val modelRow: AnkiDroidProviderRow,
    val templateRows: List<AnkiDroidProviderRow>,
    val templatesFailed: Boolean,
    val templatesFailureToken: String?
)

class DefaultAnkiDroidModelGateway(
    private val providerClient: AnkiDroidProviderClient,
    private val clock: AppClock = SystemAppClock,
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
) : AnkiDroidModelGateway {

    private var diagnostics: AnkiDroidModelQueryDiagnostics = AnkiDroidModelQueryDiagnostics.NONE
    private var operationProviderQueries: Long = 0L

    override fun lastQueryDiagnostics(): AnkiDroidModelQueryDiagnostics = diagnostics

    override suspend fun queryEnrichedModel(
        authority: String,
        ref: AnkiNoteModelRef
    ): AnkiResult<AnkiDroidEnrichedModelSnapshot> {
        if (ref.backendId != backendId) {
            return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "model_ref_foreign_backend"))
        }
        val numericModelId = ref.modelId.toLongOrNull()?.takeIf { it > 0L }
            ?: return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "model_id_not_provider_numeric"))
        return try {
            operationProviderQueries = 0L
            performQuery(authority, numericModelId.toString(), ref)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            record(
                status = "ERROR",
                modelId = ref.modelId,
                errorCategory = throwable::class.java.simpleName
            )
            AnkiResult.Failure(
                AnkiDroidErrorMapper.mapThrowable(throwable, "model_schema", AnkiDroidOperationStage.PROVIDER_QUERY)
            )
        }
    }

    private suspend fun performQuery(
        authority: String,
        modelId: String,
        ref: AnkiNoteModelRef
    ): AnkiResult<AnkiDroidEnrichedModelSnapshot> {
        val startedAt = clock.nowMillis()
        operationProviderQueries += 1L

        // 1) Enriched model row (base + CSS/LaTeX/sort fields).
        val modelQuery = providerClient.safeQuery(
            authority = authority,
            path = "${AnkiDroidApiContract.MODELS_PATH}/$modelId",
            projection = AnkiDroidApiContract.MODEL_ENRICHED_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = null,
            mapper = { row -> row }
        )
        val modelRow = when (modelQuery) {
            is ProviderQueryResult.Failure -> {
                val error = AnkiDroidErrorMapper.mapQueryResultFailure(modelQuery)
                record(
                    status = "ERROR",
                    modelId = modelId,
                    errorCategory = modelQuery.failure.category.name,
                    durationMs = clock.elapsedSince(startedAt)
                )
                return AnkiResult.Failure(error)
            }
            is ProviderQueryResult.Empty -> {
                record(
                    status = "NOT_FOUND",
                    modelId = modelId,
                    durationMs = clock.elapsedSince(startedAt)
                )
                return AnkiResult.Failure(AnkiError.NoteModelNotFound(modelId))
            }
            is ProviderQueryResult.Success -> modelQuery.data.firstOrNull()
        } ?: run {
            record(
                status = "NOT_FOUND",
                modelId = modelId,
                durationMs = clock.elapsedSince(startedAt)
            )
            return AnkiResult.Failure(AnkiError.NoteModelNotFound(modelId))
        }

        // 2) Templates sub-resource. Fail-soft: if the templates endpoint errors (unknown column,
        // old AnkiDroid, provider throws), degrade to an empty template list with a degradation
        // token rather than failing the entire model read. We still report the model metadata + CSS.
        var templatesFailed = false
        var templatesFailureToken: String? = null
        val templateRows: List<AnkiDroidProviderRow> = try {
            operationProviderQueries += 1L
            val templatesQuery = providerClient.safeQuery(
                authority = authority,
                path = "${AnkiDroidApiContract.MODELS_PATH}/$modelId/${AnkiDroidApiContract.MODEL_TEMPLATES_PATH_SEGMENT}",
                projection = AnkiDroidApiContract.TEMPLATE_PROJECTION,
                selection = null,
                selectionArgs = null,
                sortOrder = null,
                mapper = { row -> row }
            )
            when (templatesQuery) {
                is ProviderQueryResult.Success -> templatesQuery.data
                is ProviderQueryResult.Empty -> emptyList()
                is ProviderQueryResult.Failure -> {
                    templatesFailed = true
                    templatesFailureToken = "templates_query_failed:${templatesQuery.failure.category.name}"
                    emptyList()
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            templatesFailed = true
            templatesFailureToken = "templates_query_threw:${throwable::class.java.simpleName}"
            emptyList()
        }

        record(
            status = "OK",
            modelId = modelId,
            templateCount = templateRows.size,
            cssAvailable = true, // treat as readable; mapper degrades column absence to null
            degradations = if (templatesFailed) listOfNotNull(templatesFailureToken) else emptyList(),
            durationMs = clock.elapsedSince(startedAt)
        )

        return AnkiResult.Success(
            AnkiDroidEnrichedModelSnapshot(
                modelRow = modelRow,
                templateRows = templateRows,
                templatesFailed = templatesFailed,
                templatesFailureToken = templatesFailureToken
            )
        )
    }

    private fun record(
        status: String,
        modelId: String? = null,
        templateCount: Int? = null,
        cssAvailable: Boolean? = null,
        degradations: List<String> = emptyList(),
        errorCategory: String? = null,
        durationMs: Long? = null
    ) {
        diagnostics = AnkiDroidModelQueryDiagnostics(
            lastStatus = status,
            lastModelId = modelId,
            templateCount = templateCount,
            cssAvailable = cssAvailable,
            degradations = degradations,
            lastErrorCategory = errorCategory,
            lastQueryDurationMs = durationMs,
            providerQueryCount = diagnostics.providerQueryCount + operationProviderQueries
        )
        operationProviderQueries = 0L
    }
}
