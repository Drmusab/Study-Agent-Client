package com.studyagent.client.data.anki.ankidroid

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.common.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * GATE 02 — the only Android code in the AnkiDroid integration (INV-ANKI-DET-06).
 *
 * Everything this class does is *discovery* and *one bounded read*:
 *
 * - `PackageManager.resolveContentProvider` — the official mechanism, the same call AnkiDroid's
 *   own `AddContentApi.getAnkiDroidPackageName()` makes. It yields the package that really serves
 *   the authority plus the provider's published metadata (the provider spec).
 * - `PackageManager.getPackageInfo` — used only to distinguish "not installed" from "installed
 *   but no provider", never as the primary signal (§9).
 * - `ContentResolver.query` on the `selected_deck` URI with a one-column projection — the cheapest
 *   read the provider offers (one row, no card data, no mutation).
 *
 * It holds the **application** context only (§42) and runs every platform call on
 * `Dispatchers.IO` (§43).
 *
 * Known, documented behavior: AnkiDroid's provider opens the collection before answering *any*
 * query (`CardContentProvider.query` → `CollectionManager.getColUnsafe()` in v2.24.1), so a probe
 * may cause AnkiDroid to open (or, on a completely fresh install, initialize) its own collection.
 * Study-Agent issues read-only requests and never writes, but this side effect belongs to
 * AnkiDroid's implementation of that read, and is recorded here rather than hidden
 * (INV-ANKI-DET-07; see `docs/ANKIDROID_INTEGRATION.md` §8).
 */
class AndroidAnkiDroidProbe(
    context: Context,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
) : AnkiDroidProbe, AnkiDroidProviderClient {

    private val appContext: Context = context.applicationContext

    @Suppress("DEPRECATION") // the typed-flags overload only exists on API 33+
    override suspend fun providerFacts(endpoint: AnkiDroidEndpoint): AnkiDroidProviderFacts? =
        withContext(dispatchers.io) {
            val packageManager = appContext.packageManager
            val providerInfo: ProviderInfo? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.resolveContentProvider(
                        endpoint.authority,
                        PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    packageManager.resolveContentProvider(
                        endpoint.authority,
                        PackageManager.GET_META_DATA
                    )
                }

            if (providerInfo == null) return@withContext null

            val metadata = providerInfo.metaData
            val hasPublishedSpec =
                metadata != null && metadata.containsKey(AnkiDroidApiContract.PROVIDER_SPEC_METADATA_KEY)

            AnkiDroidProviderFacts(
                endpointLabel = endpoint.label,
                // The authority we resolved by, not `ProviderInfo.authority` (which reports the
                // provider's first declared authority and could differ for a multi-authority
                // provider). Diagnostics must state what was actually looked up.
                authority = endpoint.authority,
                providerPackage = providerInfo.packageName,
                packageMatchesExpected = providerInfo.packageName == endpoint.expectedPackage,
                enabled = providerInfo.enabled,
                providerSpec = if (hasPublishedSpec) {
                    metadata.getInt(AnkiDroidApiContract.PROVIDER_SPEC_METADATA_KEY)
                } else {
                    AnkiDroidProviderSpec.IMPLICIT_WHEN_METADATA_ABSENT
                },
                providerSpecSource = if (hasPublishedSpec) {
                    AnkiDroidProviderSpecSource.METADATA
                } else {
                    AnkiDroidProviderSpecSource.IMPLICIT_FALLBACK
                }
            )
        }

    @Suppress("DEPRECATION") // the typed-flags overload only exists on API 33+
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
            // Not installed, or not visible to us because the manifest <queries> entry is missing.
            // Either way the honest answer for detection is "we cannot see it" — and the provider
            // lookup, which is the primary signal, has already failed in that case too.
            false
        }
    }

    override suspend fun probeCollection(endpoint: AnkiDroidEndpoint): AnkiDroidProbeOutcome =
        withContext(dispatchers.io) {
            val uri = Uri.parse(
                "content://${endpoint.authority}/${AnkiDroidApiContract.SELECTED_DECK_PATH}"
            )
            try {
                val cursor = appContext.contentResolver.query(
                    uri,
                    arrayOf(AnkiDroidApiContract.SELECTED_DECK_COLUMN),
                    null,
                    null,
                    null
                )

                if (cursor == null) {
                    // Documented null-return contract of ContentResolver.query: the provider
                    // exists but produced no cursor.
                    return@withContext AnkiDroidProbeOutcome.Failed(
                        AnkiDroidFailure(
                            category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                            evidence = AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE,
                            exceptionClass = null,
                            evidenceToken = "null_cursor"
                        )
                    )
                }

                cursor.use { openCursor ->
                    // The only fact read from the collection: a row exists. Nothing is
                    // extracted, retained or logged (no deck id, no deck name, no counts).
                    AnkiDroidProbeOutcome.Reached(
                        selectedDeckRowPresent = openCursor.moveToFirst()
                    )
                }
            } catch (cancellation: CancellationException) {
                // Cancellation is not a provider failure and must not be classified as one
                // (note: CancellationException extends IllegalStateException on the JVM, so this
                // branch has to come first).
                throw cancellation
            } catch (throwable: Throwable) {
                AnkiDroidProbeOutcome.Failed(
                    AnkiDroidFailureClassifier.classify(
                        throwable,
                        AnkiDroidOperationStage.COLLECTION_PROBE
                    )
                )
            }
        }

    @Suppress(\"DEPRECATION\")
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

    override suspend fun <T> safeQuery(
        authority: String,
        path: String,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        mapper: (android.database.Cursor) -> T
    ): ProviderQueryResult<T> = withContext(dispatchers.io) {
        val uri = Uri.parse(\"content://$authority/$path\")
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
                        evidenceToken = \"null_cursor\"
                    ),
                    operation = \"query:$path\"
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
                        return@withContext ProviderQueryResult.Failure(
                            failure = AnkiDroidFailure(
                                category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                                evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                                exceptionClass = mappingError::class.java.simpleName,
                                evidenceToken = \"mapping_failed\"
                            ),
                            operation = \"query:$path\"
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
            ProviderQueryResult.Failure(failure = failure, operation = \"query:$path\")
        }
    }
}

/**
 * GATE 02 §17/§18 — the platform side of the single permission owner.
 *
 * It answers two things and nothing else: *is the permission granted to this app*, and *what does
 * the platform say the permission is* (protection level, declarer visibility). The second answer
 * exists so that the documented claim "this is an install-time, AnkiDroid-declared permission, not
 * a runtime prompt" is verifiable from the device instead of being an assumption.
 */
class AndroidAnkiDroidPermissionManager(
    context: Context,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
) : AnkiDroidPermissionManager {

    private val appContext: Context = context.applicationContext

    override suspend fun state(endpoint: AnkiDroidEndpoint): AnkiDroidPermissionState =
        withContext(dispatchers.io) {
            val granted = appContext.checkSelfPermission(endpoint.readWritePermission) ==
                PackageManager.PERMISSION_GRANTED
            val info = permissionInfoOrNull(endpoint.readWritePermission)

            AnkiDroidPermissionState(
                permission = endpoint.readWritePermission,
                granted = granted,
                protectionLevel = info?.let { protectionLevelName(it.protection) },
                declaredByInstalledPackage = info != null
            )
        }

    @Suppress("DEPRECATION") // the typed-flags overload only exists on API 33+
    private fun permissionInfoOrNull(permission: String): PermissionInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPermissionInfo(
                permission,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            appContext.packageManager.getPermissionInfo(permission, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        // No installed, visible package declares this permission (for example: AnkiDroid is not
        // installed, or an unexpected application id is installed instead).
        null
    }

    private fun protectionLevelName(protection: Int): String = when (protection and PROTECTION_BASE_MASK) {
        PermissionInfo.PROTECTION_NORMAL -> "normal"
        PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
        PermissionInfo.PROTECTION_SIGNATURE -> "signature"
        PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM -> "signatureOrSystem"
        else -> "unknown"
    }

    private companion object {
        /** `PermissionInfo.protection` packs the base level in its low four bits. */
        const val PROTECTION_BASE_MASK: Int = 0x0f
    }
}
