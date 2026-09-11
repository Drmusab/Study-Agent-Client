package com.studyagent.client.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.voice.tts.SegmentLanguage
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
    private val speechOrchestrator: SpeechOrchestrator
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

    init {
        // Load once, and reload whenever the engine becomes READY (covers delayed init
        // and engine switches — voice lists are engine-specific).
        viewModelScope.launch {
            speechOrchestrator.isReady.collect { ready ->
                if (ready) refreshVoiceData()
            }
        }
        refreshVoiceData()
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
