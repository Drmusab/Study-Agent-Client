package com.studyagent.client.data.preferences

import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.security.SecureTokenStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

interface ProfileRepository {
    val profiles: Flow<List<ServerProfile>>
    val activeProfile: Flow<ServerProfile?>

    suspend fun addOrUpdateProfile(profile: ServerProfile)
    suspend fun deleteProfile(profileId: String)
    suspend fun selectProfile(profileId: String)
    suspend fun getActiveProfileOnce(): ServerProfile?
}

class DefaultProfileRepository(
    private val dataStore: PreferencesDataStore,
    private val secureTokenStorage: SecureTokenStorage
) : ProfileRepository {

    override val profiles: Flow<List<ServerProfile>> = dataStore.profilesFlow

    override val activeProfile: Flow<ServerProfile?> = combine(
        dataStore.profilesFlow,
        dataStore.settingsFlow
    ) { profilesList, settings ->
        val selectedId = settings.selectedProfileId
        val chosen = if (selectedId != null) {
            profilesList.firstOrNull { it.id == selectedId }
        } else {
            profilesList.firstOrNull { it.isDefault } ?: profilesList.firstOrNull()
        }
        // Hydrate token from secure storage if present
        chosen?.let { p ->
            val storedToken = secureTokenStorage.getToken(p.id)
            if (!storedToken.isNullOrBlank()) p.copy(authToken = storedToken) else p
        }
    }

    override suspend fun addOrUpdateProfile(profile: ServerProfile) {
        val current = dataStore.profilesFlow.first().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }

        // Securely store token
        if (!profile.authToken.isNullOrBlank()) {
            secureTokenStorage.saveToken(profile.id, profile.authToken)
        } else {
            secureTokenStorage.removeToken(profile.id)
        }

        val sanitizedProfile = profile.copy(authToken = null) // Do not write plaintext token to DataStore

        if (index >= 0) {
            current[index] = sanitizedProfile
        } else {
            current.add(sanitizedProfile)
        }

        dataStore.saveProfiles(current)
    }

    override suspend fun deleteProfile(profileId: String) {
        val current = dataStore.profilesFlow.first().toMutableList()
        current.removeAll { it.id == profileId }
        secureTokenStorage.removeToken(profileId)
        dataStore.saveProfiles(current)
    }

    override suspend fun selectProfile(profileId: String) {
        dataStore.setSelectedProfileId(profileId)
    }

    override suspend fun getActiveProfileOnce(): ServerProfile? {
        return activeProfile.first()
    }
}
