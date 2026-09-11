package com.studyagent.client.di

import android.content.Context
import com.studyagent.client.core.audio.AndroidAudioRouteManager
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.security.AndroidSecureTokenStorage
import com.studyagent.client.core.security.SecureTokenStorage
import com.studyagent.client.core.voice.AndroidSpeechRecognitionManager
import com.studyagent.client.core.voice.SpeechRecognitionManager
import com.studyagent.client.core.voice.VoiceCommandManager
import com.studyagent.client.core.voice.tts.AndroidTtsEngineAdapter
import com.studyagent.client.core.voice.tts.AudioFocusController
import com.studyagent.client.core.voice.tts.DefaultSpeechOrchestrator
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.TtsEngineAdapter
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
    val ttsEngineAdapter: TtsEngineAdapter
    val speechOrchestrator: SpeechOrchestrator
    val sttManager: SpeechRecognitionManager
    val voiceCommandManager: VoiceCommandManager
    val studySessionRepository: StudySessionRepository
    val diagnosticsRepository: DiagnosticsRepository
}

/**
 * Lifetime (§57): `ServiceLocator.initialize` uses the *application* context, so all
 * of these are process-lifetime singletons. Exactly one `TextToSpeech` engine exists
 * (inside [ttsEngineAdapter], owned by [speechOrchestrator].release()); Activities and
 * the foreground service observe the same engine, never create their own.
 */
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

    override val ttsEngineAdapter: TtsEngineAdapter by lazy { AndroidTtsEngineAdapter(context) }

    override val speechOrchestrator: SpeechOrchestrator by lazy {
        DefaultSpeechOrchestrator(
            engine = ttsEngineAdapter,
            focusController = AudioFocusController(context),
            headsetConnected = audioRouteManager.isHeadsetConnected,
            workDispatcher = dispatchers.default
        )
    }

    override val sttManager: SpeechRecognitionManager by lazy { AndroidSpeechRecognitionManager(context) }
    override val voiceCommandManager: VoiceCommandManager by lazy { VoiceCommandManager() }
    override val studySessionRepository: StudySessionRepository by lazy {
        DefaultStudySessionRepository(
            connectionRepository = connectionRepository,
            speechOrchestrator = speechOrchestrator,
            sttManager = sttManager,
            voiceCommandManager = voiceCommandManager,
            preferencesDataStore = preferencesDataStore,
            audioRouteManager = audioRouteManager,
            dispatchers = dispatchers
        )
    }
    override val diagnosticsRepository: DiagnosticsRepository by lazy {
        DefaultDiagnosticsRepository(connectionRepository, audioRouteManager, speechOrchestrator)
    }
}
