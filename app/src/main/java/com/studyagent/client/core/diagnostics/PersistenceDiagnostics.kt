package com.studyagent.client.core.diagnostics

import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.data.preferences.AppSettingsPreferencesCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * What persistence looks like right now (§64).
 *
 * Only shapes and ages — never values. A cache age tells you whether a stale dashboard is the
 * reason a screen looks wrong; the cached payload itself never enters Diagnostics.
 */
data class PersistenceDiagnosticsSnapshot(
    val settingsSchemaVersion: Int = AppSettingsPreferencesCodec.CURRENT_SCHEMA_VERSION,
    val lastSettingsWriteError: String? = null,
    val dashboardCacheAgeMs: Long? = null,
    val controlCacheAgeMs: Long? = null,
    val draftPresent: Boolean = false,
    val draftAgeMs: Long? = null
)

/**
 * Observes the persistence layer's *metadata* and exposes it as one bounded snapshot.
 *
 * Every input is a [Flow] of primitives, so this class is unit-testable on the JVM with plain
 * flows — no DataStore, no Android context, no disk.
 */
class PersistenceDiagnostics(
    scope: CoroutineScope,
    private val clock: AppClock = SystemAppClock,
    lastSettingsWriteError: Flow<String?>? = null,
    dashboardCacheSavedAt: Flow<Long?>? = null,
    controlCacheSavedAt: Flow<Long?>? = null,
    controlDraftJson: Flow<String?>? = null
) {
    private val _snapshot = MutableStateFlow(PersistenceDiagnosticsSnapshot())
    val snapshot: StateFlow<PersistenceDiagnosticsSnapshot> = _snapshot.asStateFlow()

    init {
        val errorFlow = lastSettingsWriteError ?: MutableStateFlow(null)
        val dashboardFlow = dashboardCacheSavedAt ?: MutableStateFlow(null)
        val controlFlow = controlCacheSavedAt ?: MutableStateFlow(null)
        val draftFlow = controlDraftJson ?: MutableStateFlow(null)

        combine(errorFlow, dashboardFlow, controlFlow, draftFlow) { error, dashboardAt, controlAt, draft ->
            val now = clock.nowMillis()
            PersistenceDiagnosticsSnapshot(
                settingsSchemaVersion = AppSettingsPreferencesCodec.CURRENT_SCHEMA_VERSION,
                lastSettingsWriteError = error,
                dashboardCacheAgeMs = ageOrNull(dashboardAt, now),
                controlCacheAgeMs = ageOrNull(controlAt, now),
                draftPresent = !draft.isNullOrBlank(),
                // A draft has no stored timestamp of its own; the envelope inside the JSON does.
                // The age is therefore reported only when that envelope is readable (§66).
                draftAgeMs = draftAgeFrom(draft, now)
            )
        }.let { flow ->
            scope.launch { flow.collect { _snapshot.value = it } }
        }
    }

    private fun ageOrNull(savedAt: Long?, nowMs: Long): Long? {
        if (savedAt == null || savedAt <= 0L) return null
        return (nowMs - savedAt).coerceAtLeast(0L)
    }

    private fun draftAgeFrom(draftJson: String?, nowMs: Long): Long? {
        if (draftJson.isNullOrBlank()) return null
        return try {
            val envelope = com.studyagent.client.core.network.ProtocolJson.json
                .decodeFromString(
                    com.studyagent.client.data.repository.PersistedControlDraft.serializer(),
                    draftJson
                )
            ageOrNull(envelope.savedAtEpochMs, nowMs)
        } catch (_: Throwable) {
            null
        }
    }
}
