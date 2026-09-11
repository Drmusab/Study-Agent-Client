package com.studyagent.client.ui.screens.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudySession
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.voice.SpeechRecognitionManager
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.data.repository.ConnectionRepository
import com.studyagent.client.data.repository.StudySessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StudyViewModel(
    private val studySessionRepository: StudySessionRepository,
    private val connectionRepository: ConnectionRepository,
    private val audioRouteManager: AudioRouteManager,
    private val speechRecognitionManager: SpeechRecognitionManager,
    private val speechOrchestrator: SpeechOrchestrator,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    val studyState: StateFlow<StudyState> = studySessionRepository.studyState
    val currentSession: StateFlow<StudySession?> = studySessionRepository.currentSession
    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    val activeAudioDevice: StateFlow<AudioDeviceInfoModel> = audioRouteManager.activeOutputDevice
    val isHeadsetConnected: StateFlow<Boolean> = audioRouteManager.isHeadsetConnected

    val isListening: StateFlow<Boolean> = speechRecognitionManager.isListening
    val isSpeaking: StateFlow<Boolean> = speechOrchestrator.isSpeaking

    val appSettings = preferencesDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun onRateCard(rating: Rating) {
        viewModelScope.launch {
            studySessionRepository.rateCurrentCard(rating)
        }
    }

    fun onRepeatQuestion() {
        viewModelScope.launch {
            studySessionRepository.requestRepeat()
        }
    }

    fun onRequestHint() {
        viewModelScope.launch {
            studySessionRepository.requestHint()
        }
    }

    fun onRequestExplanation() {
        viewModelScope.launch {
            studySessionRepository.requestExplanation()
        }
    }

    fun onRequestAnswer() {
        viewModelScope.launch {
            studySessionRepository.requestAnswer()
        }
    }

    fun onSkipCard() {
        viewModelScope.launch {
            studySessionRepository.skipCard()
        }
    }

    fun onPauseSession() {
        viewModelScope.launch {
            studySessionRepository.pauseStudy()
        }
    }

    fun onResumeSession() {
        viewModelScope.launch {
            studySessionRepository.resumeStudy()
        }
    }

    fun onEndSession() {
        viewModelScope.launch {
            studySessionRepository.endStudy()
        }
    }

    fun onPushToTalkDown() {
        studySessionRepository.startManualPushToTalk()
    }

    fun onPushToTalkUp() {
        studySessionRepository.stopManualPushToTalk(submitIfTranscriptPresent = true)
    }

    fun onPushToTalkToggle() {
        if (isListening.value) {
            studySessionRepository.stopManualPushToTalk(submitIfTranscriptPresent = true)
        } else {
            studySessionRepository.startManualPushToTalk()
        }
    }

    fun onStopSpeaking() {
        studySessionRepository.requestStopSpeaking()
    }

    fun onSubmitTextAnswer(cardId: String, text: String) {
        viewModelScope.launch {
            studySessionRepository.submitSpokenAnswer(cardId, text)
        }
    }
}
