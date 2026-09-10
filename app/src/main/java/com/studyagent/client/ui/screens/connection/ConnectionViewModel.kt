package com.studyagent.client.ui.screens.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.data.preferences.ProfileRepository
import com.studyagent.client.data.repository.ConnectionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConnectionViewModel(
    private val connectionRepository: ConnectionRepository,
    private val profileRepository: ProfileRepository,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    val profiles: StateFlow<List<ServerProfile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeProfile: StateFlow<ServerProfile?> = profileRepository.activeProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val settings = preferencesDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun connect(profile: ServerProfile? = null) {
        viewModelScope.launch {
            connectionRepository.connect(profile)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectionRepository.disconnect()
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            profileRepository.selectProfile(profileId)
        }
    }

    fun saveProfile(profile: ServerProfile) {
        viewModelScope.launch {
            profileRepository.addOrUpdateProfile(profile)
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            profileRepository.deleteProfile(profileId)
        }
    }

    fun toggleFakeAgent(enabled: Boolean) {
        connectionRepository.toggleFakeAgent(enabled)
    }
}
