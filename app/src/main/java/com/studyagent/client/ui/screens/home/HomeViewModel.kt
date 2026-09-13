package com.studyagent.client.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.data.repository.ConnectionRepository
import com.studyagent.client.data.repository.StudySessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val activeProfile: ServerProfile? = null,
    val activeAudioDevice: AudioDeviceInfoModel = AudioDeviceInfoModel.DEFAULT_SPEAKER,
    val isHeadsetConnected: Boolean = false,
    val lastSelectedDeck: String = "Toronto Notes",
    val cardsDueCount: Int = 183,
    val lastSessionReviewed: Int = 92,
    val lastSessionRecallRate: Int = 81
)

class HomeViewModel(
    private val connectionRepository: ConnectionRepository,
    private val studySessionRepository: StudySessionRepository,
    private val audioRouteManager: AudioRouteManager,
    studyAudioRouteCoordinator: StudyAudioRouteCoordinator? = null
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    val activeProfile: StateFlow<ServerProfile?> = connectionRepository.activeProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeAudioDevice: StateFlow<AudioDeviceInfoModel> = audioRouteManager.activeOutputDevice
    val isHeadsetConnected: StateFlow<Boolean> = audioRouteManager.isHeadsetConnected

    /**
     * Effective study audio route (§80). The dashboard shows *what the app will actually do*
     * — headset, headphones + phone mic, or phone — not a raw headset boolean.
     */
    val studyAudioRoute: StateFlow<EffectiveStudyAudioRoute>? = studyAudioRouteCoordinator?.effectiveRoute

    fun connectToActiveProfile() {
        viewModelScope.launch {
            connectionRepository.connect()
        }
    }

    fun startStudySession(deck: String = "Toronto Notes") {
        viewModelScope.launch {
            studySessionRepository.startStudy(deck)
        }
    }
}
