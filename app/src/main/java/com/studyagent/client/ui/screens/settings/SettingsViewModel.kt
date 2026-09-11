package com.studyagent.client.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.voice.tts.SegmentLanguage
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.TtsEngineInfo
import com.studyagent.client.core.voice.tts.TtsVoiceInfo
import com.studyagent.client.data.preferences.PreferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesDataStore: PreferencesDataStore,
    private val speechOrchestrator: SpeechOrchestrator,
    private val recognitionOrchestrator: SpeechRecognitionOrchestrator
) : ViewModel() {

    val settings: StateFlow<AppSettings> = preferencesDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _englishVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val englishVoices: StateFlow<List<TtsVoiceInfo>> = _englishVoices.asStateFlow()

    private val _arabicVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val arabicVoices: StateFlow<List<TtsVoiceInfo>> = _arabicVoices.asStateFlow()

    private val _engines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val engines: StateFlow<List<TtsEngineInfo>> = _engines.asStateFlow()

    private val _previewing = MutableStateFlow<SegmentLanguage?>(null)
    val previewing: StateFlow<SegmentLanguage?> = _previewing.asStateFlow()

    /**
     * Live recognizer capabilities (§21/§25). Settings shows "On-device available: Yes/No"
     * from this rather than promising offline recognition the device may not have.
     */
    val recognitionCapabilities: StateFlow<RecognitionCapabilities> =
        recognitionOrchestrator.capabilities

    fun refreshRecognitionCapabilities() {
        viewModelScope.launch {
            recognitionOrchestrator.refreshCapabilities()
        }
    }

    /**
     * Ask the platform to fetch the on-device model for [languageTag] (§26). Never fired
     * automatically — model downloads are large and the user has to choose.
     */
    fun requestSpeechModelDownload(languageTag: String) {
        viewModelScope.launch {
            recognitionOrchestrator.requestModelDownload(languageTag)
            recognitionOrchestrator.refreshCapabilities()
        }
    }

    init {
        // Load once, and reload whenever the engine becomes READY (covers delayed init
        // and engine switches — voice lists are engine-specific).
        viewModelScope.launch {
            speechOrchestrator.isReady.collect { ready ->
                if (ready) refreshVoiceData()
            }
        }
        refreshVoiceData()
        refreshRecognitionCapabilities()
    }

    fun refreshVoiceData() {
        viewModelScope.launch {
            _engines.value = speechOrchestrator.getEngines()
            _englishVoices.value = speechOrchestrator.getVoices("en")
            _arabicVoices.value = speechOrchestrator.getVoices("ar")
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            preferencesDataStore.updateSettings(transform)
        }
    }

    fun previewVoice(language: SegmentLanguage) {
        if (_previewing.value != null) return
        viewModelScope.launch {
            _previewing.value = language
            try {
                speechOrchestrator.speakPreview(language)
            } finally {
                _previewing.value = null
            }
        }
    }
}
