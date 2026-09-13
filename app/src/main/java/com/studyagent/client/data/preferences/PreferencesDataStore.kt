package com.studyagent.client.data.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.toMutablePreferences
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.network.ProtocolJson
import com.studyagent.client.core.security.SecureTokenStorage
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "study_agent_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { listOf(AppSettingsDataMigration()) }
)

/** DataStore migration adapter. The codec owns the actual sequential steps. */
private class AppSettingsDataMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[AppSettingsPreferencesCodec.Keys.SCHEMA_VERSION]
        return version == null || version < AppSettingsPreferencesCodec.CURRENT_SCHEMA_VERSION
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val from = currentData[AppSettingsPreferencesCodec.Keys.SCHEMA_VERSION]
            ?: AppSettingsPreferencesCodec.LEGACY_SCHEMA_VERSION
        val migrated = currentData.toMutablePreferences().also(AppSettingsPreferencesCodec::migrateInPlace)
        val to = migrated[AppSettingsPreferencesCodec.Keys.SCHEMA_VERSION]
        AppLogger.i("SETTINGS_MIGRATION", "from=$from to=$to result=success")
        return migrated
    }

    override suspend fun cleanUp() = Unit
}

private const val PROFILE_SCHEMA_VERSION = 1

/** Profiles got an envelope too, while raw list JSON remains a legacy read path. */
@Serializable
private data class PersistedProfiles(
    val schemaVersion: Int,
    val profiles: List<ServerProfile>
)

/**
 * Atomic persistence boundary for device/app settings and the small management
 * cache references. Mapping and validation live in [AppSettingsPreferencesCodec];
 * this class only owns DataStore access, recovery and transactions.
 */
class PreferencesDataStore(
    private val context: Context
) {
    private val tag = "PreferencesDataStore"

    private object ManagementKeys {
        val PROFILES_JSON = stringPreferencesKey("profiles_json")

        val DASHBOARD_CACHE_JSON = stringPreferencesKey("dashboard_cache_json")
        val DASHBOARD_CACHE_SAVED_AT = longPreferencesKey("dashboard_cache_saved_at")
        val DECKS_CACHE_JSON = stringPreferencesKey("decks_cache_json")
        val DECKS_CACHE_SAVED_AT = longPreferencesKey("decks_cache_saved_at")
        val CONTROL_CONFIG_JSON = stringPreferencesKey("control_config_json")
        val CONTROL_CONFIG_SAVED_AT = longPreferencesKey("control_config_saved_at")
        val CONTROL_DRAFT_JSON = stringPreferencesKey("control_draft_json")
    }

    private val _lastSettingsWriteError = MutableStateFlow<String?>(null)
    /** A transient, sanitized error for Settings UI/diagnostics; null means the last write succeeded. */
    val lastSettingsWriteError: StateFlow<String?> = _lastSettingsWriteError.asStateFlow()

    /**
     * IOException recovery is deliberately narrow. A programmer/codec error is
     * rethrown so it remains visible in tests and development instead of silently
     * replacing a real bug with defaults.
     */
    private val preferencesFlow: Flow<Preferences> = context.dataStore.data
        .catch { error ->
            if (error is IOException) {
                AppLogger.e(tag, "Settings DataStore I/O read failure; using safe defaults", error)
                emit(emptyPreferences())
            } else {
                throw error
            }
        }

    val settingsFlow: Flow<AppSettings> = preferencesFlow
        .map(AppSettingsPreferencesCodec::readSettings)
        .distinctUntilChanged()

    /**
     * A bad profiles JSON blob is an optional-cache failure. Defaults are exposed
     * for runtime availability, but the invalid raw value is never overwritten here.
     */
    val profilesFlow: Flow<List<ServerProfile>> = preferencesFlow.map { prefs ->
        decodeProfiles(prefs[ManagementKeys.PROFILES_JSON])
            ?: defaultProfiles()
    }.distinctUntilChanged()

    private fun defaultProfiles(): List<ServerProfile> = listOf(
        ServerProfile.defaultLocalProfile(),
        ServerProfile.defaultEmulatorProfile()
    )

    private fun decodeProfiles(json: String?): List<ServerProfile>? {
        if (json.isNullOrBlank()) return defaultProfiles()
        return try {
            val decoded = decodeProfilesWithSecrets(json)
            val safe = decoded.mapNotNull { it.normalized()?.copy(authToken = null) }
            if (decoded.isNotEmpty() && safe.isEmpty()) null else safe
        } catch (error: Exception) {
            AppLogger.w(tag, "Failed to decode server profiles; preserving the raw cache")
            null
        }
    }

    private fun decodeProfilesWithSecrets(json: String): List<ServerProfile> {
        val looksVersioned = try {
            ProtocolJson.json.parseToJsonElement(json).jsonObject.containsKey("schemaVersion")
        } catch (_: Exception) {
            false
        }
        if (!looksVersioned) {
            // Legacy installations stored the list directly.
            return ProtocolJson.json.decodeFromString<List<ServerProfile>>(json)
        }
        val envelope = ProtocolJson.json.decodeFromString<PersistedProfiles>(json)
        require(envelope.schemaVersion in 0..PROFILE_SCHEMA_VERSION) {
            "unsupported profile schema ${envelope.schemaVersion}"
        }
        return envelope.profiles
    }

    private fun encodeProfiles(profiles: List<ServerProfile>): String =
        ProtocolJson.json.encodeToString(
            PersistedProfiles(PROFILE_SCHEMA_VERSION, profiles)
        )

    /** One-time compatibility migration for old builds that embedded tokens in profile JSON. */
    suspend fun migrateLegacyProfileTokens(secureTokenStorage: SecureTokenStorage) {
        if (!secureTokenStorage.isAvailable) {
            AppLogger.w(tag, "Secure storage unavailable; retaining legacy profile data")
            return
        }
        context.dataStore.edit { prefs ->
            val raw = prefs[ManagementKeys.PROFILES_JSON] ?: return@edit
            val profiles = try {
                decodeProfilesWithSecrets(raw)
            } catch (_: Exception) {
                // Preserve corrupt data for diagnostic recovery; never replace it.
                return@edit
            }
            var movedAny = false
            profiles.forEach { profile ->
                if (!profile.authToken.isNullOrBlank()) {
                    secureTokenStorage.saveToken(profile.id, profile.authToken)
                    movedAny = true
                }
            }
            if (movedAny) {
                prefs[ManagementKeys.PROFILES_JSON] = encodeProfiles(sanitizedProfiles(profiles))
                AppLogger.i(tag, "Migrated legacy profile credentials to secure storage")
            }
        }
    }

    private fun sanitizedProfiles(profiles: List<ServerProfile>): List<ServerProfile> =
        profiles.mapNotNull { it.normalized()?.copy(authToken = null) }

    /**
     * Replace profiles atomically and resolve a now-invalid selection in the same
     * edit. Unknown DataStore keys and unrelated settings remain untouched.
     */
    suspend fun saveProfiles(profiles: List<ServerProfile>) {
        val sanitized = sanitizedProfiles(profiles)
        val json = encodeProfiles(sanitized)
        context.dataStore.edit { prefs ->
            val raw = prefs[ManagementKeys.PROFILES_JSON]
            if (raw != null && decodeProfiles(raw) == null) {
                // Explicit callers must repair/export the corrupt blob first;
                // this API never replaces it with defaults implicitly.
                return@edit
            }
            prefs[ManagementKeys.PROFILES_JSON] = json
            resolveSelectedProfile(prefs, sanitized)
        }
    }

    /**
     * Mutate valid profile data without replacing an unreadable JSON blob with
     * defaults. Returns false when the raw profile cache is corrupt.
     */
    suspend fun updateProfiles(transform: (List<ServerProfile>) -> List<ServerProfile>): Boolean {
        var updated = false
        context.dataStore.edit { prefs ->
            val existing = decodeProfiles(prefs[ManagementKeys.PROFILES_JSON])
            // An absent key is a valid fresh-install state; a non-empty invalid
            // key is not permission to destroy the user's recoverable data.
            if (existing != null) {
                val sanitized = sanitizedProfiles(transform(existing))
                prefs[ManagementKeys.PROFILES_JSON] = encodeProfiles(sanitized)
                resolveSelectedProfile(prefs, sanitized)
                updated = true
            }
        }
        return updated
    }

    suspend fun setSelectedProfileId(profileId: String?) {
        context.dataStore.edit { prefs ->
            val profiles = decodeProfiles(prefs[ManagementKeys.PROFILES_JSON]) ?: return@edit
            val selected = profileId?.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull { it.isDefault }?.id
                ?: profiles.firstOrNull()?.id
            if (selected == null) {
                prefs.remove(AppSettingsPreferencesCodec.Keys.SELECTED_PROFILE_ID)
            } else {
                prefs[AppSettingsPreferencesCodec.Keys.SELECTED_PROFILE_ID] = selected
            }
        }
    }

    private fun resolveSelectedProfile(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        profiles: List<ServerProfile>
    ) {
        val selected = prefs[AppSettingsPreferencesCodec.Keys.SELECTED_PROFILE_ID]
        if (selected != null && profiles.none { it.id == selected }) {
            val fallback = profiles.firstOrNull { it.isDefault }?.id ?: profiles.firstOrNull()?.id
            if (fallback == null) {
                prefs.remove(AppSettingsPreferencesCodec.Keys.SELECTED_PROFILE_ID)
            } else {
                prefs[AppSettingsPreferencesCodec.Keys.SELECTED_PROFILE_ID] = fallback
            }
        }
    }

    /**
     * The transform reads the current transaction snapshot, so rapid updates are
     * serialized by DataStore and cannot lose a field changed by another caller.
     */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        try {
            context.dataStore.edit { prefs ->
                AppSettingsPreferencesCodec.migrateInPlace(prefs)
                val current = AppSettingsPreferencesCodec.readSettings(prefs)
                AppSettingsPreferencesCodec.writeSettings(prefs, transform(current))
            }
            _lastSettingsWriteError.value = null
        } catch (error: IOException) {
            val message = "Couldn't save settings (${error.javaClass.simpleName})"
            AppLogger.e(tag, message, error)
            _lastSettingsWriteError.value = message
            // A failed transaction is not reported as success to the UI. The
            // current flow remains the last committed settings value.
        }
    }

    fun clearSettingsWriteError() {
        _lastSettingsWriteError.value = null
    }

    /** Atomic device/app-settings reset; profile/cache/secure-storage domains remain intact. */
    suspend fun resetAppSettings() {
        try {
            context.dataStore.edit { prefs -> AppSettingsPreferencesCodec.clearSettings(prefs) }
            _lastSettingsWriteError.value = null
        } catch (error: IOException) {
            val message = "Couldn't reset settings (${error.javaClass.simpleName})"
            AppLogger.e(tag, message, error)
            _lastSettingsWriteError.value = message
        }
    }

    // ------------------------------------------------------------------ management cache references

    val dashboardCacheJson: Flow<String?> = preferencesFlow
        .map { prefs -> prefs[ManagementKeys.DASHBOARD_CACHE_JSON] }

    val dashboardCacheSavedAt: Flow<Long?> = preferencesFlow
        .map { prefs -> prefs[ManagementKeys.DASHBOARD_CACHE_SAVED_AT] }

    val decksCacheJson: Flow<String?> = preferencesFlow
        .map { prefs -> prefs[ManagementKeys.DECKS_CACHE_JSON] }

    val decksCacheSavedAt: Flow<Long?> = preferencesFlow
        .map { prefs -> prefs[ManagementKeys.DECKS_CACHE_SAVED_AT] }

    val controlConfigJson: Flow<String?> = preferencesFlow
        .map { prefs -> prefs[ManagementKeys.CONTROL_CONFIG_JSON] }

    val controlConfigSavedAt: Flow<Long?> = preferencesFlow
        .map { prefs -> prefs[ManagementKeys.CONTROL_CONFIG_SAVED_AT] }

    val controlDraftJson: Flow<String?> = preferencesFlow
        .map { prefs -> prefs[ManagementKeys.CONTROL_DRAFT_JSON] }

    /** Payload and timestamp are committed together in one DataStore edit. */
    suspend fun setDashboardCache(json: String?, savedAtEpochMs: Long) {
        context.dataStore.edit { prefs ->
            if (json.isNullOrBlank()) {
                prefs.remove(ManagementKeys.DASHBOARD_CACHE_JSON)
                prefs.remove(ManagementKeys.DASHBOARD_CACHE_SAVED_AT)
            } else {
                prefs[ManagementKeys.DASHBOARD_CACHE_JSON] = json
                prefs[ManagementKeys.DASHBOARD_CACHE_SAVED_AT] = savedAtEpochMs
            }
        }
    }

    suspend fun setDecksCache(json: String?, savedAtEpochMs: Long) {
        context.dataStore.edit { prefs ->
            if (json.isNullOrBlank()) {
                prefs.remove(ManagementKeys.DECKS_CACHE_JSON)
                prefs.remove(ManagementKeys.DECKS_CACHE_SAVED_AT)
            } else {
                prefs[ManagementKeys.DECKS_CACHE_JSON] = json
                prefs[ManagementKeys.DECKS_CACHE_SAVED_AT] = savedAtEpochMs
            }
        }
    }

    suspend fun setControlConfigCache(json: String?, savedAtEpochMs: Long) {
        context.dataStore.edit { prefs ->
            if (json.isNullOrBlank()) {
                prefs.remove(ManagementKeys.CONTROL_CONFIG_JSON)
                prefs.remove(ManagementKeys.CONTROL_CONFIG_SAVED_AT)
            } else {
                prefs[ManagementKeys.CONTROL_CONFIG_JSON] = json
                prefs[ManagementKeys.CONTROL_CONFIG_SAVED_AT] = savedAtEpochMs
            }
        }
    }

    suspend fun setControlDraft(json: String?) {
        context.dataStore.edit { prefs ->
            if (json.isNullOrBlank()) {
                prefs.remove(ManagementKeys.CONTROL_DRAFT_JSON)
            } else {
                prefs[ManagementKeys.CONTROL_DRAFT_JSON] = json
            }
        }
    }
}
