package com.studyagent.client.ui.screens.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.audio.StudyAudioAttention
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
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
    private val preferencesDataStore: PreferencesDataStore,
    studyAudioRouteCoordinator: StudyAudioRouteCoordinator? = null
) : ViewModel() {

    val studyState: StateFlow<StudyState> = studySessionRepository.studyState
    val currentSession: StateFlow<StudySession?> = studySessionRepository.currentSession
    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    val activeAudioDevice: StateFlow<AudioDeviceInfoModel> = audioRouteManager.activeOutputDevice
    val isHeadsetConnected: StateFlow<Boolean> = audioRouteManager.isHeadsetConnected

    /** Effective study audio route: preference vs. what is running (§68/§81). */
    val studyAudioRoute: StateFlow<EffectiveStudyAudioRoute>? = studyAudioRouteCoordinator?.effectiveRoute

    /** Non-null only after an *unexpected* headset loss under the pause policy (§96/§118). */
    val audioRouteAttention: StateFlow<StudyAudioAttention?>? = studyAudioRouteCoordinator?.attention

    /**
     * A better route that is waiting for a turn boundary (§41) — e.g. headphones plugged in
     * mid-question. The UI can offer an explicit immediate switch.
     */
    val pendingAudioRoute: StateFlow<EffectiveStudyAudioRoute>? = studyAudioRouteCoordinator?.pendingRoute

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

    // ---------------------------------------------------------------- audio route recovery

    /**
     * *Continue on phone* after headphones disappeared mid-session (§96/§118). Pins the phone
     * route and resumes; the current question is repeated rather than resumed mid-sentence.
     */
    fun onContinueOnPhone() {
        studySessionRepository.continueOnPhone()
    }

    /** *Wait for headphones*: dismiss the prompt. The session stays paused until resumed. */
    fun onWaitForHeadset() {
        studySessionRepository.dismissAudioRouteAttention()
    }

    /** Manual *Use headphones now* (§42): immediate, deliberate route change. */
    fun onUseHeadsetNow() {
        studySessionRepository.useHeadsetNow()
    }

    /**
     * One-time Phone Mode notice (§31/§66). Persisted only when the user asks not to see it
     * again — the notice must never become a per-session interruption.
     */
    fun acknowledgePhoneAudioNotice(suppressForever: Boolean) {
        if (!suppressForever) return
        viewModelScope.launch {
            preferencesDataStore.updateSettings { it.copy(phoneAudioNoticeAcknowledged = true) }
        }
    }
}
