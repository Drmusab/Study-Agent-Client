package com.studyagent.client.di

import android.content.Context
import com.studyagent.client.core.audio.AndroidAudioRouteManager
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.security.AndroidSecureTokenStorage
import com.studyagent.client.core.security.SecureTokenStorage
import com.studyagent.client.core.voice.AndroidSpeechRecognitionManager
import com.studyagent.client.core.voice.AndroidTextToSpeechManager
import com.studyagent.client.core.voice.SpeechRecognitionManager
import com.studyagent.client.core.voice.TextToSpeechManager
import com.studyagent.client.core.voice.VoiceCommandManager
import com.studyagent.client.data.preferences.DefaultProfileRepository
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.data.preferences.ProfileRepository
import com.studyagent.client.data.repository.ConnectionRepository
import com.studyagent.client.data.repository.DefaultConnectionRepository
import com.studyagent.client.data.repository.DefaultDiagnosticsRepository
import com.studyagent.client.data.repository.DefaultStudySessionRepository
import com.studyagent.client.data.repository.DiagnosticsRepository
import com.studyagent.client.data.repository.StudySessionRepository

interface AppContainer {
    val dispatchers: DispatcherProvider
    val secureTokenStorage: SecureTokenStorage
    val preferencesDataStore: PreferencesDataStore
    val profileRepository: ProfileRepository
    val connectionRepository: ConnectionRepository
    val audioRouteManager: AudioRouteManager
    val ttsManager: TextToSpeechManager
    val sttManager: SpeechRecognitionManager
    val voiceCommandManager: VoiceCommandManager
    val studySessionRepository: StudySessionRepository
    val diagnosticsRepository: DiagnosticsRepository
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val dispatchers: DispatcherProvider by lazy { DefaultDispatcherProvider() }
    override val secureTokenStorage: SecureTokenStorage by lazy { AndroidSecureTokenStorage(context) }
    override val preferencesDataStore: PreferencesDataStore by lazy { PreferencesDataStore(context) }
    override val profileRepository: ProfileRepository by lazy {
        DefaultProfileRepository(preferencesDataStore, secureTokenStorage)
    }
    override val connectionRepository: ConnectionRepository by lazy {
        DefaultConnectionRepository(profileRepository, preferencesDataStore, dispatchers)
    }
    override val audioRouteManager: AudioRouteManager by lazy { AndroidAudioRouteManager(context) }
    override val ttsManager: TextToSpeechManager by lazy { AndroidTextToSpeechManager(context) }
    override val sttManager: SpeechRecognitionManager by lazy { AndroidSpeechRecognitionManager(context) }
    override val voiceCommandManager: VoiceCommandManager by lazy { VoiceCommandManager() }
    override val studySessionRepository: StudySessionRepository by lazy {
        DefaultStudySessionRepository(
            connectionRepository = connectionRepository,
            ttsManager = ttsManager,
            sttManager = sttManager,
            voiceCommandManager = voiceCommandManager,
            preferencesDataStore = preferencesDataStore,
            dispatchers = dispatchers
        )
    }
    override val diagnosticsRepository: DiagnosticsRepository by lazy {
        DefaultDiagnosticsRepository(connectionRepository, audioRouteManager)
    }
}
