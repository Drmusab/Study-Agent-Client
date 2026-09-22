package com.studyagent.client.data.anki.ankidroid

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.CancellationException
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
internal interface AnkiDroidProviderClient {

    suspend fun providerFacts(endpoint: AnkiDroidEndpoint): AnkiDroidProviderFacts?

    suspend fun isPackageInstalled(packageName: String): Boolean

    suspend fun getPackageVersion(packageName: String): String?

    suspend fun probeCollection(endpoint: AnkiDroidEndpoint): AnkiDroidProbeOutcome

    /**
     * Safe query helper for future deck/card queries.
     * Executes off main thread, closes Cursor deterministically, maps errors.
     * Does NOT expose Cursor to callers.
     */
    suspend fun <T> safeQuery(
        authority: String,
        path: String,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        mapper: (Cursor) -> T
    ): ProviderQueryResult<T>
}

/**
 * Result of a safe provider query — never contains Cursor.
 */
internal sealed interface ProviderQueryResult<out T> {
    data class Success<T>(val data: List<T>) : ProviderQueryResult<T>
    data class Empty<T>(val reason: String = "empty") : ProviderQueryResult<T>
    data class Failure<T>(
        val failure: AnkiDroidFailure,
        val operation: String
    ) : ProviderQueryResult<T>
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
        mapper: (Cursor) -> T
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

                val results = mutableListOf<T>()
                do {
                    try {
                        results.add(mapper(c))
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
            val failure = AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.COLLECTION_PROBE)
            ProviderQueryResult.Failure(failure = failure, operation = "query:$path")
        }
    }
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
        mapper: (Cursor) -> T
    ): ProviderQueryResult<T> {
        queryLog.add("$authority/$path")
        // In JVM tests we cannot create a real Cursor, so return empty by default.
        // Tests that need specific rows should override this fake or use a dedicated test double.
        return ProviderQueryResult.Empty("fake_empty")
    }
}
