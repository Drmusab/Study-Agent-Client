package com.studyagent.client.data.repository

import com.studyagent.client.data.preferences.PreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * A cached JSON payload with the wall-clock time it was saved.
 * The timestamp drives Live/Cached/Stale freshness labels (§14) — cached data
 * must never be presented as live.
 */
data class CachedPayload(
    val json: String,
    val savedAtEpochMs: Long
)

/**
 * Persistence boundary for the management layer (dashboard + study control).
 *
 * These caches exist purely for UX: they let the dashboard show the last
 * known snapshot while disconnected and let the Control Center recover a
 * draft. The PC Study Agent always remains the source of truth (§16): on any
 * capable connection the server data replaces the cache.
 */
interface ManagementCacheStorage {
    fun observeDashboardCache(): Flow<CachedPayload?>
    suspend fun readDashboardCache(): CachedPayload?
    suspend fun saveDashboardCache(payload: CachedPayload?)

    fun observeDecksCache(): Flow<CachedPayload?>
    suspend fun readDecksCache(): CachedPayload?
    suspend fun saveDecksCache(payload: CachedPayload?)

    fun observeControlConfigCache(): Flow<CachedPayload?>
    suspend fun readControlConfigCache(): CachedPayload?
    suspend fun saveControlConfigCache(payload: CachedPayload?)

    fun observeControlDraft(): Flow<String?>
    suspend fun readControlDraft(): String?
    suspend fun saveControlDraft(json: String?)
}

/** Production implementation backed by the app's preferences DataStore. */
class PreferencesManagementCacheStorage(
    private val preferencesDataStore: PreferencesDataStore
) : ManagementCacheStorage {

    override fun observeDashboardCache(): Flow<CachedPayload?> =
        combine(
            preferencesDataStore.dashboardCacheJson,
            preferencesDataStore.dashboardCacheSavedAt
        ) { json, savedAt ->
            if (json.isNullOrBlank() || savedAt == null) null else CachedPayload(json, savedAt)
        }

    override suspend fun readDashboardCache(): CachedPayload? = observeDashboardCache().first()

    override suspend fun saveDashboardCache(payload: CachedPayload?) {
        preferencesDataStore.setDashboardCache(payload?.json, payload?.savedAtEpochMs ?: 0L)
    }

    override fun observeDecksCache(): Flow<CachedPayload?> =
        combine(
            preferencesDataStore.decksCacheJson,
            preferencesDataStore.decksCacheSavedAt
        ) { json, savedAt ->
            if (json.isNullOrBlank() || savedAt == null) null else CachedPayload(json, savedAt)
        }

    override suspend fun readDecksCache(): CachedPayload? = observeDecksCache().first()

    override suspend fun saveDecksCache(payload: CachedPayload?) {
        preferencesDataStore.setDecksCache(payload?.json, payload?.savedAtEpochMs ?: 0L)
    }

    override fun observeControlConfigCache(): Flow<CachedPayload?> =
        combine(
            preferencesDataStore.controlConfigJson,
            preferencesDataStore.controlConfigSavedAt
        ) { json, savedAt ->
            if (json.isNullOrBlank() || savedAt == null) null else CachedPayload(json, savedAt)
        }

    override suspend fun readControlConfigCache(): CachedPayload? = observeControlConfigCache().first()

    override suspend fun saveControlConfigCache(payload: CachedPayload?) {
        preferencesDataStore.setControlConfigCache(payload?.json, payload?.savedAtEpochMs ?: 0L)
    }

    override fun observeControlDraft(): Flow<String?> = preferencesDataStore.controlDraftJson

    override suspend fun readControlDraft(): String? = preferencesDataStore.controlDraftJson.first()

    override suspend fun saveControlDraft(json: String?) {
        preferencesDataStore.setControlDraft(json)
    }
}

/** In-memory implementation for unit tests and previews. */
class InMemoryManagementCacheStorage : ManagementCacheStorage {
    private var dashboard: CachedPayload? = null
    private var decks: CachedPayload? = null
    private var config: CachedPayload? = null
    private var draft: String? = null

    private val dashboardFlow = kotlinx.coroutines.flow.MutableStateFlow<CachedPayload?>(null)
    private val decksFlow = kotlinx.coroutines.flow.MutableStateFlow<CachedPayload?>(null)
    private val configFlow = kotlinx.coroutines.flow.MutableStateFlow<CachedPayload?>(null)
    private val draftFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun observeDashboardCache(): Flow<CachedPayload?> = dashboardFlow
    override suspend fun readDashboardCache(): CachedPayload? = dashboard
    override suspend fun saveDashboardCache(payload: CachedPayload?) {
        dashboard = payload
        dashboardFlow.value = payload
    }

    override fun observeDecksCache(): Flow<CachedPayload?> = decksFlow
    override suspend fun readDecksCache(): CachedPayload? = decks
    override suspend fun saveDecksCache(payload: CachedPayload?) {
        decks = payload
        decksFlow.value = payload
    }

    override fun observeControlConfigCache(): Flow<CachedPayload?> = configFlow
    override suspend fun readControlConfigCache(): CachedPayload? = config
    override suspend fun saveControlConfigCache(payload: CachedPayload?) {
        config = payload
        configFlow.value = payload
    }

    override fun observeControlDraft(): Flow<String?> = draftFlow
    override suspend fun readControlDraft(): String? = draft
    override suspend fun saveControlDraft(json: String?) {
        draft = json
        draftFlow.value = json
    }
}
