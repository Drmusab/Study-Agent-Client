package com.studyagent.client.di

import android.content.Context
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendSelector
import com.studyagent.client.core.audio.AndroidAudioRouteManager
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.audio.DefaultStudyAudioModeResolver
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.audio.toStudyAudioPreferences
import com.studyagent.client.BuildConfig
import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.core.diagnostics.AppDiagnostics
import com.studyagent.client.core.diagnostics.AppPerformanceMetrics
import com.studyagent.client.core.diagnostics.MemoryProbe
import com.studyagent.client.core.diagnostics.MemorySample
import com.studyagent.client.core.diagnostics.PersistenceDiagnostics
import com.studyagent.client.core.security.AndroidSecureTokenStorage
import com.studyagent.client.core.security.SecureTokenStorage
import com.studyagent.client.core.voice.VoiceCommandManager
import com.studyagent.client.core.voice.stt.AndroidSpeechRecognitionBackend
import com.studyagent.client.core.voice.stt.DefaultSpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.stt.SpeechRecognitionBackend
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.tts.AndroidTtsEngineAdapter
import com.studyagent.client.core.voice.tts.AudioFocusController
import com.studyagent.client.core.voice.tts.DefaultSpeechOrchestrator
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.TtsEngineAdapter
import com.studyagent.client.data.anki.ankidroid.AndroidAnkiDroidLauncher
import com.studyagent.client.data.anki.ankidroid.AndroidAnkiDroidPermissionManager
import com.studyagent.client.data.anki.ankidroid.AndroidAnkiDroidProbe
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCapabilityProbe
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoint
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoints
import com.studyagent.client.data.anki.ankidroid.AnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthProbe
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthRepository
import com.studyagent.client.data.anki.ankidroid.AnkiDroidLauncher
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderClient
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidCapabilityProbe
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidDetector
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidHealthCheck
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidHealthProbe
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.data.preferences.DefaultProfileRepository
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.data.preferences.ProfileRepository
import com.studyagent.client.data.repository.CapabilityStore
import com.studyagent.client.data.repository.ConnectionRepository
import com.studyagent.client.data.repository.DashboardRepository
import com.studyagent.client.data.repository.DefaultConnectionRepository
import com.studyagent.client.data.repository.DefaultDashboardRepository
import com.studyagent.client.data.repository.DefaultDiagnosticsRepository
import com.studyagent.client.data.repository.DefaultStudyControlRepository
import com.studyagent.client.data.repository.DefaultStudySessionRepository
import com.studyagent.client.data.repository.DiagnosticsAppInfo
import com.studyagent.client.data.repository.DiagnosticsRepository
import com.studyagent.client.data.repository.ManagementCacheStorage
import com.studyagent.client.data.repository.PreferencesManagementCacheStorage
import com.studyagent.client.data.repository.StudyControlRepository
import com.studyagent.client.data.repository.StudySessionMachineRepository
import com.studyagent.client.data.repository.StudySessionRepository
import com.studyagent.client.core.network.NetworkStatsRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

interface AppContainer {
    val dispatchers: DispatcherProvider
    val secureTokenStorage: SecureTokenStorage
    val preferencesDataStore: PreferencesDataStore
    val profileRepository: ProfileRepository
    val connectionRepository: ConnectionRepository
    val audioRouteManager: AudioRouteManager

    /** Decides which route voice study uses, and how route changes are applied over time. */
    val studyAudioRouteCoordinator: StudyAudioRouteCoordinator

    val ttsEngineAdapter: TtsEngineAdapter
    val speechOrchestrator: SpeechOrchestrator
    val speechRecognitionBackend: SpeechRecognitionBackend
    val recognitionOrchestrator: SpeechRecognitionOrchestrator
    val voiceCommandManager: VoiceCommandManager
    val studySessionRepository: StudySessionRepository
    val diagnosticsRepository: DiagnosticsRepository

    /** One authoritative protocol-capability state shared by every screen (§109). */
    val capabilityStore: CapabilityStore

    /** Dashboard/management data layer (§8). */
    val dashboardRepository: DashboardRepository

    /** Study Control Center data layer (§9). */
    val studyControlRepository: StudyControlRepository

    /** Persistence metadata (schema version, cache ages, write errors) for Diagnostics (§64). */
    val persistenceDiagnostics: PersistenceDiagnostics

    /**
     * GATE 02 — the one authoritative owner of AnkiDroid runtime availability (§26/§86/§87).
     *
     * Application-scoped and created once, like every other shared component here: screens
     * observe it, they never build their own detector, and nothing about it is persisted
     * (INV-ANKI-DET-05). It is deliberately independent of [connectionRepository] — a
     * disconnected PC agent and a ready AnkiDroid are a valid combination (§38/§92).
     */
    val ankiDroidHealthRepository: AnkiDroidHealthRepository

    /** "Open AnkiDroid" helper (§31). Distribution-neutral and crash-free when it is absent. */
    val ankiDroidLauncher: AnkiDroidLauncher

    /** GATE 04 — provider client, gateway, backend (single source of truth, §45) */
    val ankiDroidProviderClient: AnkiDroidProviderClient
    val ankiDroidGateway: AnkiDroidGateway
    val ankiDroidBackend: AnkiBackend

    /** GATE 03 + GATE 04 — registry now includes real AnkiDroid backend */
    val ankiBackendRegistry: AnkiBackendRegistry
    val ankiBackendSelector: AnkiBackendSelector
}

/**
 * Lifetime (§57): `ServiceLocator.initialize` uses the *application* context, so all
 * of these are process-lifetime singletons. Exactly one `TextToSpeech` engine exists
 * (inside [ttsEngineAdapter], owned by [speechOrchestrator].release()); Activities and
 * the foreground service observe the same engine, never create their own.
 */
class DefaultAppContainer(private val context: Context) : AppContainer {
    private val ankiDroidScope = CoroutineScope(SupervisorJob() + DefaultDispatcherProvider().default)

    override val ankiDroidProviderClient: AnkiDroidProviderClient by lazy {
        AndroidAnkiDroidProbe(context)
    }

    private val ankiDroidProbe: AndroidAnkiDroidProbe by lazy {
        // Single instance that serves as both probe and provider client
        // (AndroidAnkiDroidProbe now implements AnkiDroidProviderClient)
        ankiDroidProviderClient as AndroidAnkiDroidProbe
    }

    private val ankiDroidPermissionManager by lazy {
        AndroidAnkiDroidPermissionManager(context)
    }

    private val ankiDroidDetector by lazy {
        DefaultAnkiDroidDetector(
            probe = ankiDroidProbe,
            permissions = ankiDroidPermissionManager,
            endpoints = ankiDroidEndpoints
        )
    }

    private val ankiDroidHealthCheck by lazy {
        DefaultAnkiDroidHealthCheck(
            detector = ankiDroidDetector,
            clock = SystemAppClock
        )
    }

    private val ankiDroidCapabilityProbe: AnkiDroidCapabilityProbe by lazy {
        DefaultAnkiDroidCapabilityProbe()
    }

    private val ankiDroidHealthProbe: AnkiDroidHealthProbe by lazy {
        DefaultAnkiDroidHealthProbe(
            healthCheck = ankiDroidHealthCheck,
            capabilityProbe = ankiDroidCapabilityProbe,
            providerClient = ankiDroidProviderClient,
            endpoints = ankiDroidEndpoints
        )
    }

    override val ankiDroidGateway: AnkiDroidGateway by lazy {
        DefaultAnkiDroidGateway(
            healthProbe = ankiDroidHealthProbe,
            capabilityProbe = ankiDroidCapabilityProbe,
            providerClient = ankiDroidProviderClient,
            endpoints = ankiDroidEndpoints,
            scope = ankiDroidScope
        )
    }

    override val ankiDroidBackend: AnkiBackend by lazy {
        AnkiDroidBackend(
            gateway = ankiDroidGateway,
            scope = ankiDroidScope
        )
    }

    override val ankiBackendRegistry: AnkiBackendRegistry by lazy {
        AnkiBackendRegistry(listOf(ankiDroidBackend))
    }

    override val ankiBackendSelector: AnkiBackendSelector by lazy {
        AnkiBackendSelector(ankiBackendRegistry)
    }

    override val dispatchers: DispatcherProvider by lazy { DefaultDispatcherProvider() }

    /** Heap + PSS. PSS is reported only when the platform actually provides it (§65). */
    private val androidMemoryProbe = MemoryProbe { atMs ->
        val runtime = Runtime.getRuntime()
        val pss = try {
            val info = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(info)
            info.totalPss.toLong() * 1_024L
        } catch (_: Throwable) {
            null
        }
        MemorySample(
            usedHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            maxHeapBytes = runtime.maxMemory(),
            pssBytes = pss,
            atMs = atMs
        )
    }

    init {
        // Android can report PSS; the JVM default cannot. Install the richer probe once, before
        // any diagnostics snapshot is taken.
        AppPerformanceMetrics.metrics.memoryProbe = androidMemoryProbe
    }

    override val secureTokenStorage: SecureTokenStorage by lazy { AndroidSecureTokenStorage(context) }
    override val preferencesDataStore: PreferencesDataStore by lazy { PreferencesDataStore(context) }
    override val profileRepository: ProfileRepository by lazy {
        DefaultProfileRepository(preferencesDataStore, secureTokenStorage)
    }
    override val connectionRepository: ConnectionRepository by lazy {
        DefaultConnectionRepository(profileRepository, preferencesDataStore, dispatchers)
    }
    override val audioRouteManager: AudioRouteManager by lazy { AndroidAudioRouteManager(context) }

    /**
     * One coordinator for the whole process: the study machine, TTS route-loss policy and the
     * UI all read the same effective route (§112).
     */
    override val studyAudioRouteCoordinator: StudyAudioRouteCoordinator by lazy {
        StudyAudioRouteCoordinator(
            snapshots = audioRouteManager.routeSnapshot,
            preferences = preferencesDataStore.settingsFlow.map { it.toStudyAudioPreferences() },
            scope = CoroutineScope(SupervisorJob() + dispatchers.default),
            resolver = DefaultStudyAudioModeResolver()
        )
    }

    override val ttsEngineAdapter: TtsEngineAdapter by lazy { AndroidTtsEngineAdapter(context) }

    override val speechOrchestrator: SpeechOrchestrator by lazy {
        DefaultSpeechOrchestrator(
            engine = ttsEngineAdapter,
            focusController = AudioFocusController(context),
            // Route-aware: speech is only interrupted by a disconnect when the *effective*
            // output was the headset. Starting the app with no headphones — or running Phone
            // mode with a headset attached — never looks like a route loss (§44).
            headsetConnected = studyAudioRouteCoordinator.headsetRouteActive,
            workDispatcher = dispatchers.default
        )
    }

    override val speechRecognitionBackend: SpeechRecognitionBackend by lazy {
        AndroidSpeechRecognitionBackend(context)
    }

    /**
     * Single owner of the microphone's recognition session (§53). The TTS gate is wired to
     * the same "nothing playing, nothing queued" predicate the repository uses for handoff,
     * so the microphone cannot open while the app is still speaking (§57/§58).
     */
    override val recognitionOrchestrator: SpeechRecognitionOrchestrator by lazy {
        DefaultSpeechRecognitionOrchestrator(
            backend = speechRecognitionBackend,
            canOpenMicrophone = {
                !speechOrchestrator.isSpeaking.value &&
                    speechOrchestrator.health.value.queueDepth == 0
            },
            inputRouteLabel = { audioRouteManager.likelyInputRoute.value.displayLabel }
        )
    }

    override val voiceCommandManager: VoiceCommandManager by lazy { VoiceCommandManager() }

    /** Management-layer cache (dashboard snapshot, decks, study config/draft). */
    val managementCacheStorage: ManagementCacheStorage by lazy {
        PreferencesManagementCacheStorage(preferencesDataStore)
    }

    override val capabilityStore: CapabilityStore by lazy {
        CapabilityStore(
            connectionRepository = connectionRepository,
            scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        )
    }

    override val dashboardRepository: DashboardRepository by lazy {
        DefaultDashboardRepository(
            connectionRepository = connectionRepository,
            capabilityStore = capabilityStore,
            cacheStorage = managementCacheStorage,
            dispatchers = dispatchers
        )
    }

    override val studyControlRepository: StudyControlRepository by lazy {
        DefaultStudyControlRepository(
            connectionRepository = connectionRepository,
            capabilityStore = capabilityStore,
            cacheStorage = managementCacheStorage,
            dispatchers = dispatchers
        )
    }

    /**
     * Hardened machine-backed repository (§151). The legacy [DefaultStudySessionRepository]
     * remains available for tests and as a migration fallback, but the app's
     * authoritative session coordinator is now the serialized state machine.
     */
    override val studySessionRepository: StudySessionRepository by lazy {
        StudySessionMachineRepository(
            connectionRepository = connectionRepository,
            speechOrchestrator = speechOrchestrator,
            recognitionOrchestrator = recognitionOrchestrator,
            settingsFlow = preferencesDataStore.settingsFlow,
            audioRouteManager = audioRouteManager,
            dispatchers = dispatchers,
            audioRouteCoordinator = studyAudioRouteCoordinator,
            // Voice-command starts use the Control Center's live configuration (§75).
            startRequestProvider = { studyControlRepository.currentStartRequest() },
            // Bounded technical metrics + structured timeline (§51/§57/§67).
            performance = AppPerformanceMetrics.metrics,
            timeline = AppDiagnostics.timeline
        )
    }
    /** Legacy repository kept for direct testing and gradual migration. */
    val legacyStudySessionRepository: StudySessionRepository by lazy {
        DefaultStudySessionRepository(
            connectionRepository = connectionRepository,
            speechOrchestrator = speechOrchestrator,
            recognitionOrchestrator = recognitionOrchestrator,
            settingsFlow = preferencesDataStore.settingsFlow,
            audioRouteManager = audioRouteManager,
            dispatchers = dispatchers
        )
    }
    override val persistenceDiagnostics: PersistenceDiagnostics by lazy {
        PersistenceDiagnostics(
            scope = CoroutineScope(SupervisorJob() + dispatchers.default),
            lastSettingsWriteError = preferencesDataStore.lastSettingsWriteError,
            dashboardCacheSavedAt = preferencesDataStore.dashboardCacheSavedAt,
            controlCacheSavedAt = preferencesDataStore.controlConfigSavedAt,
            controlDraftJson = preferencesDataStore.controlDraftJson
        )
    }

    override val diagnosticsRepository: DiagnosticsRepository by lazy {
        DefaultDiagnosticsRepository(
            connectionRepository,
            audioRouteManager,
            speechOrchestrator,
            recognitionOrchestrator,
            studyAudioRouteCoordinator,
            settingsFlow = preferencesDataStore.settingsFlow,
            scope = CoroutineScope(SupervisorJob() + dispatchers.default),
            performanceMetrics = AppPerformanceMetrics.metrics,
            timeline = AppDiagnostics.timeline,
            networkStats = NetworkStatsRegistry.current,
            capabilityStore = capabilityStore,
            // The session section reads the machine's own sanitized snapshot (§59) and its
            // bounded resource inventory (§19) — never the raw card or transcript.
            sessionDiagnostics = { machineBackedSession?.diagnostics() },
            machineResources = { machineBackedSession?.resourceSnapshot() },
            dashboardRepository = dashboardRepository,
            studyControlRepository = studyControlRepository,
            persistenceDiagnostics = persistenceDiagnostics,
            appInfo = { diagnosticsAppInfo() },
            // GATE 02: the AnkiDroid integration section, owned by one application-scoped
            // repository — Diagnostics renders its snapshot, it does not probe anything itself.
            ankiDroidHealthRepository = ankiDroidHealthRepository,
            // GATE 04: gateway + backend for capability matrix (§64/§121)
            ankiDroidGateway = ankiDroidGateway,
            ankiDroidBackend = ankiDroidBackend as? AnkiDroidBackend
        )
    }

    /**
     * Endpoints this build is allowed to look at.
     *
     * Official AnkiDroid always; the AnkiDroid *debug* endpoint (a different application id,
     * authority and permission) only in debug builds, so a QA device can exercise the integration
     * against a locally built AnkiDroid without any chance of a release build ever resolving it
     * (GATE 02 §10).
     */
    private val ankiDroidEndpoints: List<AnkiDroidEndpoint> by lazy {
        AnkiDroidEndpoints.forBuild(includeDebugEndpoints = BuildConfig.DEBUG)
    }

    /**
     * Probe → detector → bounded check → one application-scoped repository.
     *
     * Nothing here performs I/O at construction time: the first provider call happens only when
     * the activity reports a foreground event, so app start is never blocked on AnkiDroid
     * (§89/§90).
     *
     * GATE 04: reuses the same detector/healthCheck that the gateway uses, so health repository
     * and gateway remain consistent (single source of truth).
     */
    override val ankiDroidHealthRepository: AnkiDroidHealthRepository by lazy {
        AnkiDroidHealthRepository(
            check = ankiDroidHealthCheck,
            scope = CoroutineScope(SupervisorJob() + dispatchers.default),
            clock = SystemAppClock
        )
    }

    override val ankiDroidLauncher: AnkiDroidLauncher by lazy {
        AndroidAnkiDroidLauncher(context, ankiDroidEndpoints)
    }

    /** The machine-backed session repository, when that is the active implementation. */
    private val machineBackedSession: StudySessionMachineRepository?
        get() = studySessionRepository as? StudySessionMachineRepository

    /**
     * §84: version strings and a device *model*. Deliberately no serial number, no advertising
     * id, no account identifier — enough to reproduce, not enough to identify.
     */
    private fun diagnosticsAppInfo(): DiagnosticsAppInfo {
        val capabilities = capabilityStore.capabilities.value
        return DiagnosticsAppInfo(
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidVersion = try {
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
            } catch (_: Throwable) {
                "unknown"
            },
            deviceModel = try {
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
            } catch (_: Throwable) {
                "unknown"
            },
            protocolVersion = capabilities.protocolVersion,
            serverVersion = capabilities.serverVersion
        )
    }
}
