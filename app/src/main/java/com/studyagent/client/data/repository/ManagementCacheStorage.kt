package com.studyagent.client.data.repository

import com.studyagent.client.core.network.ProtocolJson
import com.studyagent.client.data.preferences.PreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * A decoded cache payload. schemaVersion=0 means a legacy raw JSON cache from
 * before envelopes were introduced; repositories may read it once and rewrite it
 * in the current envelope format.
 */
data class CachedPayload(
    val json: String,
    val savedAtEpochMs: Long,
    val schemaVersion: Int = 0
)

object ManagementCacheSchema {
    const val LEGACY_RAW = 0
    const val DASHBOARD = 1
    const val DECKS = 1
    const val CONTROL_CONFIG = 1
    const val CONTROL_DRAFT = 1
}

/** Versioned envelope stored around each optional JSON cache. */
@Serializable
data class PersistedCacheEnvelope(
    val schemaVersion: Int,
    val savedAtEpochMs: Long,
    /** JSON payload is kept as a string to avoid coupling the storage layer to model types. */
    val payload: String
)

/** Versioned Control Center draft envelope. */
@Serializable
data class PersistedControlDraft(
    val schemaVersion: Int,
    val savedAtEpochMs: Long,
    val config: com.studyagent.client.core.models.StudyControlConfig
)

/**
 * Persistence boundary for management data. Dashboard/deck/config caches are
 * display/fallback data; a bad one must never affect AppSettings. The Control
 * draft has a separate accessor because it is user work, not display cache.
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

    /** Raw string for backward compatibility; repository owns draft envelope decoding. */
    fun observeControlDraft(): Flow<String?>
    suspend fun readControlDraft(): String?
    suspend fun saveControlDraft(json: String?)
}

/** Production implementation backed by the app's Preferences DataStore. */
class PreferencesManagementCacheStorage(
    private val preferencesDataStore: PreferencesDataStore
) : ManagementCacheStorage {

    override fun observeDashboardCache(): Flow<CachedPayload?> =
        combine(
            preferencesDataStore.dashboardCacheJson,
            preferencesDataStore.dashboardCacheSavedAt
        ) { json, savedAt -> decodeStoredCache(json, savedAt) }

    override suspend fun readDashboardCache(): CachedPayload? = observeDashboardCache().first()

    override suspend fun saveDashboardCache(payload: CachedPayload?) {
        preferencesDataStore.setDashboardCache(
            json = payload?.let(::encodeStoredCache),
            savedAtEpochMs = payload?.savedAtEpochMs ?: 0L
        )
    }

    override fun observeDecksCache(): Flow<CachedPayload?> =
        combine(
            preferencesDataStore.decksCacheJson,
            preferencesDataStore.decksCacheSavedAt
        ) { json, savedAt -> decodeStoredCache(json, savedAt) }

    override suspend fun readDecksCache(): CachedPayload? = observeDecksCache().first()

    override suspend fun saveDecksCache(payload: CachedPayload?) {
        preferencesDataStore.setDecksCache(
            json = payload?.let(::encodeStoredCache),
            savedAtEpochMs = payload?.savedAtEpochMs ?: 0L
        )
    }

    override fun observeControlConfigCache(): Flow<CachedPayload?> =
        combine(
            preferencesDataStore.controlConfigJson,
            preferencesDataStore.controlConfigSavedAt
        ) { json, savedAt -> decodeStoredCache(json, savedAt) }

    override suspend fun readControlConfigCache(): CachedPayload? = observeControlConfigCache().first()

    override suspend fun saveControlConfigCache(payload: CachedPayload?) {
        preferencesDataStore.setControlConfigCache(
            json = payload?.let(::encodeStoredCache),
            savedAtEpochMs = payload?.savedAtEpochMs ?: 0L
        )
    }

    override fun observeControlDraft(): Flow<String?> = preferencesDataStore.controlDraftJson

    override suspend fun readControlDraft(): String? = preferencesDataStore.controlDraftJson.first()

    override suspend fun saveControlDraft(json: String?) {
        preferencesDataStore.setControlDraft(json)
    }

    private fun encodeStoredCache(payload: CachedPayload): String =
        ProtocolJson.json.encodeToString(
            PersistedCacheEnvelope(
                schemaVersion = payload.schemaVersion,
                savedAtEpochMs = payload.savedAtEpochMs,
                payload = payload.json
            )
        )

    private fun decodeStoredCache(json: String?, separateTimestamp: Long?): CachedPayload? {
        if (json.isNullOrBlank()) return null
        return try {
            val envelope = ProtocolJson.json.decodeFromString<PersistedCacheEnvelope>(json)
            if (envelope.payload.isBlank()) null
            else CachedPayload(
                json = envelope.payload,
                savedAtEpochMs = envelope.savedAtEpochMs,
                schemaVersion = envelope.schemaVersion
            )
        } catch (_: Exception) {
            // Pre-envelope installations stored raw payload JSON. Keep that
            // compatibility path; the repository will validate the model and
            // rewrite it into an envelope after a successful live response.
            separateTimestamp?.let {
                CachedPayload(json = json, savedAtEpochMs = it, schemaVersion = ManagementCacheSchema.LEGACY_RAW)
            }
        }
    }
}

/** In-memory implementation for unit tests and previews. */
class InMemoryManagementCacheStorage : ManagementCacheStorage {
    private var dashboard: CachedPayload? = null
    private var decks: CachedPayload? = null
    private var config: CachedPayload? = null
    private var draft: String? = null

    private val dashboardFlow = MutableStateFlow<CachedPayload?>(null)
    private val decksFlow = MutableStateFlow<CachedPayload?>(null)
    private val configFlow = MutableStateFlow<CachedPayload?>(null)
    private val draftFlow = MutableStateFlow<String?>(null)

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
