package com.studyagent.client.data.anki.ankidroid

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * GATE 04 — low-level provider client responsible only for safe Android provider operations.
 *
 * This is internal infrastructure (§6). It never leaks Cursor, ContentResolver, Uri, or
 * FlashCardsContract outside the AnkiDroid integration layer (§4, INV-ANKI-GW-01/02/03).
 *
 * Responsibilities:
 * - safe ContentResolver queries with deterministic Cursor closure (use {})
 * - null Cursor handling (§71)
 * - SecurityException mapping (§76)
 * - cancellation propagation (§33/§34)
 * - package version lookup (diagnostic only, §47/§48)
 * - bounded read probes (no full collection scans, §16)
 *
 * Upper layers must not construct arbitrary URIs — URI construction belongs here (§7).
 */
interface AnkiDroidProviderClient {

    suspend fun providerFacts(endpoint: AnkiDroidEndpoint): AnkiDroidProviderFacts?

    suspend fun isPackageInstalled(packageName: String): Boolean

    suspend fun getPackageVersion(packageName: String): String?

    suspend fun probeCollection(endpoint: AnkiDroidEndpoint): AnkiDroidProbeOutcome

    /**
     * Safe projected query (GATE 05: deck list and selected deck; later gates: cards).
     *
     * Executes off the main thread, closes the Cursor deterministically, classifies platform
     * failures and hands every row to [mapper] as a platform-neutral [AnkiDroidProviderRow]
     * (INV-ANKI-DECK-02). The Cursor itself never reaches the caller. A mapper that throws fails
     * the whole query (`mapping_failed`) — per-row *policies* (skip vs fail) are implemented by
     * mappers returning outcome values instead of throwing.
     */
    suspend fun <T> safeQuery(
        authority: String,
        path: String,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        mapper: (AnkiDroidProviderRow) -> T
    ): ProviderQueryResult<T>

    /**
     * GATE 11 — the one provider *write* primitive (`ContentResolver.update`), used only by the
     * rating gateway for the schedule answer and the temporary deck selection.
     *
     * It never interprets the outcome: it reports exactly what the platform said — a row count
     * (including `-1`, the platform's swallowed-`RemoteException` answer) or the class of the
     * throwable — so the gateway can classify the mutation boundary against the pinned contract.
     * Values are typed ([ProviderValue]); upper layers never build `ContentValues` or URIs.
     *
     * The default refuses without any platform call (provably not dispatched), so a client that
     * does not implement writes can never be mistaken for one that wrote.
     */
    suspend fun safeUpdate(authority: String, path: String, values: List<ProviderValue>): ProviderUpdateResult =
        ProviderUpdateResult.NotDispatched("update_unsupported")
}

/** One typed column value for [AnkiDroidProviderClient.safeUpdate]. */
sealed interface ProviderValue {
    val column: String
    data class LongValue(override val column: String, val value: Long) : ProviderValue
    data class IntValue(override val column: String, val value: Int) : ProviderValue
}

/**
 * What one provider `update` call produced. [Returned] and [Threw] both mean the call was issued;
 * only [NotDispatched] proves no IPC happened.
 */
sealed interface ProviderUpdateResult {
    data class Returned(val rowCount: Int) : ProviderUpdateResult
    data class Threw(val exceptionClass: String, val failure: AnkiDroidFailure) : ProviderUpdateResult
    data class NotDispatched(val reason: String) : ProviderUpdateResult
}

/**
 * Result of a safe provider query — never contains a Cursor.
 *
 * [Empty] is a *successful* query that returned no rows (a valid "zero decks" answer, §17);
 * [Failure] is a classified provider/platform failure. Callers must never fold a failure into an
 * empty list (INV-ANKI-DECK-06).
 */
sealed interface ProviderQueryResult<out T> {
    data class Success<T>(val data: List<T>) : ProviderQueryResult<T>
    data class Empty<T>(val reason: String = "empty") : ProviderQueryResult<T>
    data class Failure<T>(
        val failure: AnkiDroidFailure,
        val operation: String
    ) : ProviderQueryResult<T>
}

/**
 * The one Cursor → [AnkiDroidProviderRow] adapter (GATE 05 §7). Private to this platform file so
 * `android.database.Cursor` can never be referenced by a mapper by accident.
 */
private class CursorProviderRow(private val cursor: Cursor) : AnkiDroidProviderRow {
    override fun columnIndex(name: String): Int = cursor.getColumnIndex(name)
    override fun isNull(index: Int): Boolean = cursor.isNull(index)
    override fun getLong(index: Int): Long = cursor.getLong(index)
    override fun getInt(index: Int): Int = cursor.getInt(index)
    override fun getString(index: Int): String? = cursor.getString(index)
}

/**
 * Android implementation — the ONLY place that touches ContentResolver/Cursor for AnkiDroid
 * beyond the legacy AndroidAnkiDroidProbe (which will delegate here).
 *
 * Threading: all platform calls on Dispatchers.IO (§31/§32).
 * Cancellation: never swallowed (§33/§34).
 * Resource safety: Cursor.use {} always (§5/§70).
 */
internal class AndroidAnkiDroidProviderClient(
    context: Context,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
) : AnkiDroidProviderClient {

    private val appContext: Context = context.applicationContext

    @Suppress("DEPRECATION")
    override suspend fun providerFacts(endpoint: AnkiDroidEndpoint): AnkiDroidProviderFacts? =
        withContext(dispatchers.io) {
            try {
                val pm = appContext.packageManager
                val providerInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.resolveContentProvider(
                        endpoint.authority,
                        PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    pm.resolveContentProvider(endpoint.authority, PackageManager.GET_META_DATA)
                } ?: return@withContext null

                val metadata = providerInfo.metaData
                val hasSpec = metadata != null && metadata.containsKey(AnkiDroidApiContract.PROVIDER_SPEC_METADATA_KEY)

                AnkiDroidProviderFacts(
                    endpointLabel = endpoint.label,
                    authority = endpoint.authority,
                    providerPackage = providerInfo.packageName,
                    packageMatchesExpected = providerInfo.packageName == endpoint.expectedPackage,
                    enabled = providerInfo.enabled,
                    providerSpec = if (hasSpec) metadata.getInt(AnkiDroidApiContract.PROVIDER_SPEC_METADATA_KEY)
                    else AnkiDroidProviderSpec.IMPLICIT_WHEN_METADATA_ABSENT,
                    providerSpecSource = if (hasSpec) AnkiDroidProviderSpecSource.METADATA
                    else AnkiDroidProviderSpecSource.IMPLICIT_FALLBACK
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
        }

    @Suppress("DEPRECATION")
    override suspend fun isPackageInstalled(packageName: String): Boolean = withContext(dispatchers.io) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                appContext.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun getPackageVersion(packageName: String): String? = withContext(dispatchers.io) {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                appContext.packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun probeCollection(endpoint: AnkiDroidEndpoint): AnkiDroidProbeOutcome =
        withContext(dispatchers.io) {
            val uri = Uri.parse("content://${endpoint.authority}/${AnkiDroidApiContract.SELECTED_DECK_PATH}")
            try {
                val cursor = appContext.contentResolver.query(
                    uri,
                    arrayOf(AnkiDroidApiContract.SELECTED_DECK_COLUMN),
                    null,
                    null,
                    null
                )

                if (cursor == null) {
                    return@withContext AnkiDroidProbeOutcome.Failed(
                        AnkiDroidFailure(
                            category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                            evidence = AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE,
                            exceptionClass = null,
                            evidenceToken = "null_cursor"
                        )
                    )
                }

                cursor.use { c ->
                    AnkiDroidProbeOutcome.Reached(selectedDeckRowPresent = c.moveToFirst())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                AnkiDroidProbeOutcome.Failed(
                    AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.COLLECTION_PROBE)
                )
            }
        }

    override suspend fun <T> safeQuery(
        authority: String,
        path: String,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        mapper: (AnkiDroidProviderRow) -> T
    ): ProviderQueryResult<T> = withContext(dispatchers.io) {
        val uri = Uri.parse("content://$authority/$path")
        try {
            val cursor = appContext.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            if (cursor == null) {
                return@withContext ProviderQueryResult.Failure(
                    failure = AnkiDroidFailure(
                        category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                        evidence = AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE,
                        evidenceToken = "null_cursor"
                    ),
                    operation = "query:$path"
                )
            }

            cursor.use { c ->
                if (!c.moveToFirst()) {
                    return@withContext ProviderQueryResult.Empty()
                }

                val row = CursorProviderRow(c)
                val results = ArrayList<T>()
                do {
                    try {
                        results.add(mapper(row))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (mappingError: Throwable) {
                        // Mapping failure still closes Cursor via use {}, but reports typed failure
                        return@withContext ProviderQueryResult.Failure(
                            failure = AnkiDroidFailure(
                                category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                                evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                                exceptionClass = mappingError::class.java.simpleName,
                                evidenceToken = "mapping_failed"
                            ),
                            operation = "query:$path"
                        )
                    }
                } while (c.moveToNext())

                if (results.isEmpty()) ProviderQueryResult.Empty()
                else ProviderQueryResult.Success(results)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val failure = AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.PROVIDER_QUERY)
            ProviderQueryResult.Failure(failure = failure, operation = "query:$path")
        }
    }

    /**
     * GATE 11 — `ContentResolver.update`, off the main thread. Runs [NonCancellable] once started:
     * a binder call cannot be interrupted, so cancelling the caller must not also discard the
     * answer of a call that did happen — the caller decides what an abandoned wait means.
     */
    override suspend fun safeUpdate(
        authority: String,
        path: String,
        values: List<ProviderValue>
    ): ProviderUpdateResult {
        if (values.isEmpty()) return ProviderUpdateResult.NotDispatched("no_values")
        // A caller cancelled *before* dispatch must not dispatch; after this line the call is issued.
        currentCoroutineContext().ensureActive()
        return withContext(dispatchers.io + NonCancellable) {
            val uri = Uri.parse("content://$authority/$path")
            val contentValues = ContentValues(values.size).apply {
                values.forEach { value ->
                    when (value) {
                        is ProviderValue.LongValue -> put(value.column, value.value)
                        is ProviderValue.IntValue -> put(value.column, value.value)
                    }
                }
            }
            try {
                ProviderUpdateResult.Returned(appContext.contentResolver.update(uri, contentValues, null, null))
            } catch (throwable: Throwable) {
                ProviderUpdateResult.Threw(
                    exceptionClass = throwable::class.java.simpleName,
                    failure = AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.PROVIDER_UPDATE)
                )
            }
        }
    }
}

/**
 * Scripted answer of [FakeAnkiDroidProviderClient.safeQuery] for one provider path.
 *
 * [Rows] serves in-memory rows through the same mapper contract the Android client uses, so deck
 * mapping is exercised end-to-end on the JVM; [Fail] simulates a classified provider failure;
 * [Throw] simulates a throwable escaping the client (used for cancellation propagation tests).
 */
sealed interface FakeProviderQueryResponse {
    data class Rows(val rows: List<Map<String, Any?>>) : FakeProviderQueryResponse
    data class Fail(val failure: AnkiDroidFailure) : FakeProviderQueryResponse
    data class Throw(val throwable: Throwable) : FakeProviderQueryResponse
}

/**
 * Fake provider client for JVM tests — no Android dependencies.
 */
internal class FakeAnkiDroidProviderClient(
    private val probe: AnkiDroidProbe? = null
) : AnkiDroidProviderClient {

    var providerFactsMap: MutableMap<String, AnkiDroidProviderFacts> = mutableMapOf()
    var installedPackages: MutableSet<String> = mutableSetOf()
    var packageVersions: MutableMap<String, String> = mutableMapOf()
    var probeOutcome: AnkiDroidProbeOutcome = AnkiDroidProbeOutcome.Reached(true)
    var probeThrowable: Throwable? = null

    val queryLog: MutableList<String> = mutableListOf()

    /** Scripted responses keyed by provider path (`decks`, `selected_deck`). Unscripted → Empty. */
    val queryResponses: MutableMap<String, FakeProviderQueryResponse> = mutableMapOf()

    /** Simulated provider latency, applied through the caller's (test) scheduler. */
    var queryDelayMs: Long = 0L

    /** Projections observed per query, in call order — lets tests pin the requested columns. */
    val projectionLog: MutableList<List<String>?> = mutableListOf()

    /** Selections observed per query, in call order. */
    val selectionLog: MutableList<String?> = mutableListOf()

    /** GATE 11 — every physical update call, in order: `path` + the typed values. */
    val updateLog: MutableList<Pair<String, List<ProviderValue>>> = mutableListOf()

    /**
     * GATE 11 — scripted update behaviour keyed by path. The handler runs *as* the provider: it may
     * mutate [queryResponses] (the observable collection) before returning, which is how a test
     * models "applied", "swallowed and not applied" or "died after applying".
     */
    val updateHandlers: MutableMap<String, suspend (List<ProviderValue>) -> ProviderUpdateResult> = mutableMapOf()

    fun scriptRows(path: String, rows: List<Map<String, Any?>>) {
        queryResponses[path] = FakeProviderQueryResponse.Rows(rows)
    }

    fun scriptFailure(path: String, failure: AnkiDroidFailure) {
        queryResponses[path] = FakeProviderQueryResponse.Fail(failure)
    }

    override suspend fun providerFacts(endpoint: AnkiDroidEndpoint): AnkiDroidProviderFacts? {
        return providerFactsMap[endpoint.authority] ?: probe?.providerFacts(endpoint)
    }

    override suspend fun isPackageInstalled(packageName: String): Boolean {
        return installedPackages.contains(packageName) || probe?.isPackageInstalled(packageName) == true
    }

    override suspend fun getPackageVersion(packageName: String): String? {
        return packageVersions[packageName]
    }

    override suspend fun probeCollection(endpoint: AnkiDroidEndpoint): AnkiDroidProbeOutcome {
        probeThrowable?.let { throw it }
        return probe?.probeCollection(endpoint) ?: probeOutcome
    }

    override suspend fun <T> safeQuery(
        authority: String,
        path: String,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        mapper: (AnkiDroidProviderRow) -> T
    ): ProviderQueryResult<T> {
        queryLog.add("$authority/$path")
        projectionLog.add(projection?.toList())
        selectionLog.add(selection)
        if (queryDelayMs > 0L) delay(queryDelayMs)
        return when (val response = queryResponses[path]) {
            null -> ProviderQueryResult.Empty("fake_empty")
            is FakeProviderQueryResponse.Throw -> throw response.throwable
            is FakeProviderQueryResponse.Fail -> ProviderQueryResult.Failure(response.failure, "query:$path")
            is FakeProviderQueryResponse.Rows -> {
                if (response.rows.isEmpty()) return ProviderQueryResult.Empty()
                val results = ArrayList<T>(response.rows.size)
                for (values in response.rows) {
                    try {
                        results.add(mapper(InMemoryProviderRow(values)))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (mappingError: Throwable) {
                        return ProviderQueryResult.Failure(
                            failure = AnkiDroidFailure(
                                category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                                evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                                exceptionClass = mappingError::class.java.simpleName,
                                evidenceToken = "mapping_failed"
                            ),
                            operation = "query:$path"
                        )
                    }
                }
                ProviderQueryResult.Success(results)
            }
        }
    }

    override suspend fun safeUpdate(
        authority: String,
        path: String,
        values: List<ProviderValue>
    ): ProviderUpdateResult {
        updateLog.add(path to values)
        val handler = updateHandlers[path] ?: return ProviderUpdateResult.Returned(0)
        return handler(values)
    }
}
