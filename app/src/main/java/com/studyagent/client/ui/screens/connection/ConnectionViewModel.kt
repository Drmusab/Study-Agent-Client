package com.studyagent.client.ui.screens.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.network.AgentConnectionSnapshot
import com.studyagent.client.core.network.ConnectionTestResult
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.data.preferences.ProfileRepository
import com.studyagent.client.data.repository.ConnectionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConnectionViewModel(
    private val connectionRepository: ConnectionRepository,
    private val profileRepository: ProfileRepository,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    val connectionSnapshot: StateFlow<AgentConnectionSnapshot> = connectionRepository.connectionSnapshot
    val profiles: StateFlow<List<ServerProfile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeProfile: StateFlow<ServerProfile?> = profileRepository.activeProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val settings = preferencesDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _testResults = MutableStateFlow<List<ConnectionTestResult>>(emptyList())
    val testResults: StateFlow<List<ConnectionTestResult>> = _testResults.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private var testJob: Job? = null

    fun connect(profile: ServerProfile? = null) {
        viewModelScope.launch {
            connectionRepository.connect(profile)
        }
    }

    fun connectWithOverride(profile: ServerProfile? = null) {
        viewModelScope.launch {
            connectionRepository.connectWithOverride(profile)
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

    fun testConnection(profile: ServerProfile) {
        testJob?.cancel()
        _testResults.value = emptyList()
        _isTesting.value = true
        testJob = viewModelScope.launch {
            try {
                connectionRepository.testConnection(profile).collect { result ->
                    _testResults.value = _testResults.value + result
                }
            } finally {
                _isTesting.value = false
            }
        }
    }

    fun clearTestResults() {
        _testResults.value = emptyList()
    }

    fun getDiagnostics(): String {
        return connectionRepository.getConnectionDiagnostics()
    }

    fun updateToken(profileId: String, newToken: String) {
        viewModelScope.launch {
            val currentProfiles = profiles.value
            val target = currentProfiles.firstOrNull { it.id == profileId } ?: return@launch
            val updated = target.copy(authToken = newToken.ifBlank { null })
            profileRepository.addOrUpdateProfile(updated)
        }
    }
}
