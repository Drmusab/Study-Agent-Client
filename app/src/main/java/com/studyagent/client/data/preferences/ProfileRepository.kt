package com.studyagent.client.data.preferences

import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.security.SecureTokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

interface ProfileRepository {
    val profiles: Flow<List<ServerProfile>>
    val activeProfile: Flow<ServerProfile?>

    suspend fun addOrUpdateProfile(profile: ServerProfile)
    suspend fun deleteProfile(profileId: String)
    suspend fun selectProfile(profileId: String)
    suspend fun getActiveProfileOnce(): ServerProfile?
}

/**
 * Server connection profiles are non-secret identity/configuration. Tokens are
 * stored separately in [SecureTokenStorage] and are never serialized into the
 * normal Preferences DataStore profile JSON.
 */
class DefaultProfileRepository(
    private val dataStore: PreferencesDataStore,
    private val secureTokenStorage: SecureTokenStorage
) : ProfileRepository {

    override val profiles: Flow<List<ServerProfile>> = dataStore.profilesFlow

    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        migrationScope.launch {
            dataStore.migrateLegacyProfileTokens(secureTokenStorage)
        }
    }

    override val activeProfile: Flow<ServerProfile?> = combine(
        dataStore.profilesFlow,
        dataStore.settingsFlow
    ) { profilesList, settings ->
        val selectedId = settings.selectedProfileId
        val chosen = if (selectedId != null) {
            profilesList.firstOrNull { it.id == selectedId }
                ?: profilesList.firstOrNull { it.isDefault }
                ?: profilesList.firstOrNull()
        } else {
            profilesList.firstOrNull { it.isDefault } ?: profilesList.firstOrNull()
        }
        // Hydrate only from secure storage. A corrupt/legacy profile blob cannot
        // make a plaintext auth token part of the normal settings model.
        chosen?.let { profile ->
            val storedToken = secureTokenStorage.getToken(profile.id)
            if (!storedToken.isNullOrBlank()) profile.copy(authToken = storedToken) else profile
        }
    }

    override suspend fun addOrUpdateProfile(profile: ServerProfile) {
        if (!secureTokenStorage.isAvailable && !profile.authToken.isNullOrBlank()) {
            // Never turn a keystore outage into silent credential loss.
            return
        }
        val safeProfile = profile.normalized() ?: return
        val sanitizedProfile = safeProfile.copy(authToken = null)
        val updated = dataStore.updateProfiles { current ->
            val index = current.indexOfFirst { it.id == sanitizedProfile.id }
            if (index >= 0) {
                current.toMutableList().also { it[index] = sanitizedProfile }
            } else {
                current + sanitizedProfile
            }
        }
        if (!updated) {
            // Do not save a token for a profile that could not be persisted. Most
            // importantly, do not overwrite a corrupt profile blob with defaults.
            return
        }

        // Store credentials only after the profile identity/configuration is
        // committed. The profile JSON itself always contains authToken = null.
        if (!profile.authToken.isNullOrBlank()) {
            secureTokenStorage.saveToken(profile.id, profile.authToken)
        } else {
            secureTokenStorage.removeToken(profile.id)
        }
    }

    override suspend fun deleteProfile(profileId: String) {
        val updated = dataStore.updateProfiles { current ->
            current.filterNot { it.id == profileId }
        }
        if (updated) secureTokenStorage.removeToken(profileId)
    }

    override suspend fun selectProfile(profileId: String) {
        // PreferencesDataStore validates the id and atomically falls back to a
        // valid default if the requested profile no longer exists.
        dataStore.setSelectedProfileId(profileId)
    }

    override suspend fun getActiveProfileOnce(): ServerProfile? = activeProfile.first()
}
