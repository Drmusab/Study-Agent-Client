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
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
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
    private val recognitionOrchestrator: SpeechRecognitionOrchestrator,
    private val speechOrchestrator: SpeechOrchestrator,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    val studyState: StateFlow<StudyState> = studySessionRepository.studyState
    val currentSession: StateFlow<StudySession?> = studySessionRepository.currentSession
    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    val activeAudioDevice: StateFlow<AudioDeviceInfoModel> = audioRouteManager.activeOutputDevice
    val isHeadsetConnected: StateFlow<Boolean> = audioRouteManager.isHeadsetConnected

    /** Full recognition lifecycle — the study screen renders this, not a bare boolean. */
    val recognitionState = recognitionOrchestrator.state

    /** Sampled microphone level for the waveform; a separate channel from results (§123). */
    val audioLevel: StateFlow<Float> = recognitionOrchestrator.audioLevel

    /** Derived convenience kept for existing call sites. */
    val isListening: StateFlow<Boolean> = recognitionOrchestrator.isListening
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

    /**
     * Release push-to-talk. Submits nothing itself: the repository finishes the recognition
     * turn and submits when the recognizer's final result arrives (§18/§105).
     */
    fun onPushToTalkUp() {
        studySessionRepository.stopManualPushToTalk()
    }

    fun onPushToTalkToggle() {
        if (isListening.value) {
            studySessionRepository.stopManualPushToTalk()
        } else {
            studySessionRepository.startManualPushToTalk()
        }
    }

    /** Auto-submit off: send the transcript the user has just reviewed. */
    fun onSubmitPendingTranscript() {
        studySessionRepository.submitPendingTranscript()
    }

    /** Auto-submit off: throw the transcript away and listen again. */
    fun onDiscardPendingTranscript() {
        studySessionRepository.discardPendingTranscript()
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
