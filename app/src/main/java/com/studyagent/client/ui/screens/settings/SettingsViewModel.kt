package com.studyagent.client.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendStatusCenter
import com.studyagent.client.core.anki.AnkiStudyBackendLockTracker
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.voice.tts.SegmentLanguage
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.TtsEngineInfo
import com.studyagent.client.core.voice.tts.TtsVoiceInfo
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthRepository
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidLauncher
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.ui.components.anki.AnkiBackendSectionUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesDataStore: PreferencesDataStore,
    private val speechOrchestrator: SpeechOrchestrator,
    private val recognitionOrchestrator: SpeechRecognitionOrchestrator,
    private val ankiDroidHealthRepository: AnkiDroidHealthRepository,
    private val ankiDroidLauncher: AnkiDroidLauncher,
    /**
     * GATE 21 / 21M — backend selection. Optional so existing call sites and tests keep compiling;
     * when absent the section simply does not render. Absent never means "everything available":
     * the UI must not invent a backend list (CHECKPOINT 21M-07).
     *
     * The observation point is GATE 21D's [AnkiBackendStatusCenter.states]; the registry is used for
     * exactly two read-only things — the sanctioned per-instance availability re-check
     * ([AnkiBackendRegistry.refresh]) and each adapter's *static* GATE 20 custom-study claim.
     */
    private val ankiBackendStatusCenter: AnkiBackendStatusCenter? = null,
    private val ankiBackendRegistry: AnkiBackendRegistry? = null,
    /**
     * GATE 21E's authoritative preference, as a flow seam (the same shape GATE 21F's
     * `AnkiReadContextController` takes). The concrete `BackendPreferenceRepository` needs DataStore,
     * so the seam keeps the selection rules deterministically testable without inventing a second
     * store: production passes the repository's own `preference` / `lastWriteError` / `setPreference`.
     */
    private val ankiBackendPreference: StateFlow<BackendPreference>? = null,
    private val ankiBackendPreferenceWriteError: StateFlow<String?>? = null,
    private val ankiBackendPreferenceWriter: (suspend (BackendPreference) -> Boolean)? = null,
    /** GATE 21G's immutable active-session locks, projected — never derived from the preference. */
    private val ankiSessionLocks: AnkiStudyBackendLockTracker? = null,
    /**
     * GATE 21M — the pre-binding AUTO preview, supplied by the composition root
     * (CHECKPOINT 21M-08; AUDIT-21M-R2-05).
     *
     * This ViewModel deliberately holds no selection policy: it must not become a second resolution
     * site next to GATE 21E's one pure policy, and AUDIT-21-07 keeps the list of files that may build a
     * resolution request closed. The supplied function is pure — it resolves the *hypothetical* AUTO
     * preference against exactly the states being rendered, binds nothing, writes nothing, probes
     * nothing, and its answer is displayed as a preview only. Absent means the AUTO row shows no
     * preview claim rather than a guessed one.
     */
    private val ankiBackendAutoPreview:
        ((List<AnkiBackendState>, Set<AnkiRequiredCapability>) -> BackendResolutionResult2)? = null,
    private val preferenceConfirmationTimeoutMs: Long = PREFERENCE_CONFIRMATION_TIMEOUT_MS
) : ViewModel() {

    /**
     * GATE 21M — the Settings **Anki backend** section: instance-exact selection, capability-aware
     * presentation, honest availability and the active session's own owner
     * (CHECKPOINT 21M-02 … 21M-15, 21M-21, 21M-23/24).
     *
     * The race-sensitive logic lives in [AnkiBackendSelectionController], which is ordinary Kotlin over
     * flows and therefore deterministically testable; this ViewModel supplies `viewModelScope` and the
     * Android-bound collaborators. `null` when this screen was built without the backend components —
     * or without the authoritative preference, because a section that had to assume "AUTO" would be
     * fabricating a reading rather than reporting one (CHECKPOINT 21M-07).
     */
    private val backendController: AnkiBackendSelectionController? = run {
        val center = ankiBackendStatusCenter
        val preferenceSource = ankiBackendPreference
        if (center == null || preferenceSource == null) {
            null
        } else {
            AnkiBackendSelectionController(
                scope = viewModelScope,
                statusCenter = center,
                preference = preferenceSource,
                registry = ankiBackendRegistry,
                writeError = ankiBackendPreferenceWriteError,
                writer = ankiBackendPreferenceWriter,
                sessionLocks = ankiSessionLocks,
                autoPreview = ankiBackendAutoPreview,
                confirmationTimeoutMs = preferenceConfirmationTimeoutMs,
                // Class names only ever reach the log: no exception message, no path, no key.
                logFailure = { message -> AppLogger.e(TAG, message) }
            )
        }
    }

    /** The rendered section, or `null` when this screen has no authoritative backend components. */
    val ankiBackendSection: StateFlow<AnkiBackendSectionUiState?> =
        backendController?.section ?: MutableStateFlow<AnkiBackendSectionUiState?>(null)

    /**
     * Persist a new preferred backend (CHECKPOINT 21M-11, DECISION-21-32).
     *
     * This changes *future unbound* work only: an active StudySession keeps its locked backend, a bound
     * editor and a bound creation draft keep the backend they opened against, and every unresolved
     * durable transaction keeps the backend it was written to. Nothing here re-routes anything, and the
     * rendered selection always comes from the authoritative preference flow — never from this call's
     * intent — so a refused or unconfirmed write cannot look like a saved one (VER-21M-R2-14).
     */
    fun selectAnkiBackend(target: BackendPreference) {
        backendController?.select(target)
    }

    /**
     * User-initiated, read-only availability re-check of every configured instance
     * (CHECKPOINT 21M-21). Nothing polls and nothing is triggered by composition.
     */
    fun refreshAnkiBackendStatus() {
        backendController?.refreshStatus()
    }

    /**
     * GATE 02 — live AnkiDroid integration health.
     *
     * Observed, never recomputed: the repository is the single application-scoped owner (§26),
     * and this screen contains no `PackageManager`/`ContentResolver`/permission logic of its own
     * (§103).
     */
    val ankiDroidHealth: StateFlow<AnkiDroidHealthSnapshot> = ankiDroidHealthRepository.health

    /** User-initiated refresh (the Retry/Refresh button, §28). */
    fun refreshAnkiDroidHealth() {
        ankiDroidHealthRepository.requestRefresh()
    }

    /** "Open AnkiDroid" (§31/§98). The result is logged, never surfaced as an exception. */
    fun openAnkiDroid() {
        viewModelScope.launch {
            val result = ankiDroidLauncher.open()
            AppLogger.i(TAG, "Open AnkiDroid: $result")
        }
    }

    val settings: StateFlow<AppSettings> = preferencesDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** Non-terminal write feedback; the flow remains the last committed settings value. */
    val persistenceError: StateFlow<String?> = preferencesDataStore.lastSettingsWriteError

    fun clearPersistenceError() = preferencesDataStore.clearSettingsWriteError()

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
        // Opening the integration settings is a meaningful refresh trigger (§28): the user is
        // looking at the state and may have just changed something in AnkiDroid. The repository
        // debounces it, so this cannot turn into polling.
        ankiDroidHealthRepository.onAppForeground()

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

    fun resetAppSettings() {
        viewModelScope.launch {
            preferencesDataStore.resetAppSettings()
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

    private companion object {
        const val TAG = "SettingsViewModel"

        /**
         * How long a write may wait for the authoritative preference flow to agree before the UI says
         * "written but not confirmed" instead of "saved". Bounded so a stuck storage layer cannot hold
         * the section in a saving state forever; short enough to stay inside one user interaction.
         */
        const val PREFERENCE_CONFIRMATION_TIMEOUT_MS =
            AnkiBackendSelectionController.DEFAULT_CONFIRMATION_TIMEOUT_MS
    }
}
