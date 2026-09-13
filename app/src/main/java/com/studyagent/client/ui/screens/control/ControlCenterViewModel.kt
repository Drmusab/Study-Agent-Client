package com.studyagent.client.ui.screens.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.DeckSummary
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyPreset
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.data.repository.CapabilityStore
import com.studyagent.client.data.repository.ConfigSaveResult
import com.studyagent.client.data.repository.ConfigSaveState
import com.studyagent.client.data.repository.DashboardRepository
import com.studyagent.client.data.repository.StudyControlRepository
import com.studyagent.client.data.repository.StudySessionRepository
import com.studyagent.client.data.repository.supportsV2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Control Center UI state (§49): server config vs draft, validation, save
 * lifecycle and capability gating in one coherent StateFlow.
 */
data class ControlCenterUiState(
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val decks: List<DeckSummary> = emptyList(),
    val serverConfig: StudyControlConfig? = null,
    val draftConfig: StudyControlConfig = StudyControlConfig(),
    /** True when the draft reflects only local defaults (no server config known yet). */
    val configIsLocalOnly: Boolean = true,
    val selectedPreset: StudyPreset = StudyPreset.CUSTOM,
    val hasUnsavedChanges: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    val saveTimedOut: Boolean = false,
    val lastSavedAt: Long? = null,
    val savedLocallyOnly: Boolean = false,
    val validationErrors: List<String> = emptyList(),
    val sessionActive: Boolean = false,
    val sessionPaused: Boolean = false,
    /** Server changed configuration while the draft has unsaved edits (§115). */
    val serverChangedWhileDirty: Boolean = false,
    val selectedDeckUnavailable: Boolean = false
) {
    val loading: Boolean
        get() = capabilities.status == AgentCapabilities.NegotiationStatus.UNKNOWN ||
            capabilities.status == AgentCapabilities.NegotiationStatus.NEGOTIATING

    val supportsStudyConfig: Boolean
        get() = capabilities.supportsV2(AgentCapability.STUDY_CONFIG)

    val supportsDeckList: Boolean
        get() = capabilities.supportsV2(AgentCapability.DECK_LIST)

    val isLegacyV1: Boolean get() = capabilities.isLegacyV1
}

/**
 * Control Center ViewModel. The authoritative config lives in
 * [StudyControlRepository]; this class only edits the draft and forwards save
 * intents. Device Settings (STT/TTS) are never touched here — the only link is
 * the explicit preset adapter for local hands-free behavior (§74).
 */
class ControlCenterViewModel(
    private val studyControlRepository: StudyControlRepository,
    private val dashboardRepository: DashboardRepository,
    private val capabilityStore: CapabilityStore,
    private val studySessionRepository: StudySessionRepository,
    private val preferencesDataStore: PreferencesDataStore? = null
) : ViewModel() {

    private val sessionActiveFlow = studySessionRepository.studyState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), studySessionRepository.studyState.value)

    private val _serverChangedWhileDirty = MutableStateFlow(false)

    val uiState: StateFlow<ControlCenterUiState> = combine(
        capabilityStore.capabilities,
        dashboardRepository.data,
        studyControlRepository.serverConfig,
        studyControlRepository.localConfig,
        studyControlRepository.draft
    ) { caps, dashboard, server, local, draft ->
        ConfigBundle(caps, dashboard.decks, server, local, draft)
    }.let { bundleFlow ->
        combine(bundleFlow, studyControlRepository.saveState, sessionActiveFlow, _serverChangedWhileDirty) {
            bundle, saveState, studyState, dirtyNotice ->
            buildState(bundle, saveState, studyState, dirtyNotice)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ControlCenterUiState()
    )

    private data class ConfigBundle(
        val capabilities: AgentCapabilities,
        val decks: List<DeckSummary>,
        val server: StudyControlConfig?,
        val local: StudyControlConfig,
        val draft: StudyControlConfig?
    )

    private fun buildState(
        bundle: ConfigBundle,
        saveState: ConfigSaveState,
        studyState: StudyState,
        dirtyNotice: Boolean
    ): ControlCenterUiState {
        val base = bundle.server ?: bundle.local
        val draft = bundle.draft ?: base
        val sessionActive = when (studyState) {
            is StudyState.SpeakingQuestion, is StudyState.Listening, is StudyState.Evaluating,
            is StudyState.ShowingFeedback, is StudyState.WaitingForRating, is StudyState.HintShowing,
            is StudyState.ExplanationShowing, is StudyState.Paused, is StudyState.Loading -> true

            else -> false
        }
        return ControlCenterUiState(
            capabilities = bundle.capabilities,
            decks = bundle.decks,
            serverConfig = bundle.server,
            draftConfig = draft,
            configIsLocalOnly = bundle.server == null,
            selectedPreset = StudyPreset.matching(draft),
            hasUnsavedChanges = bundle.draft != null && bundle.draft != base,
            saving = saveState is ConfigSaveState.Saving,
            saveError = (saveState as? ConfigSaveState.Error)?.reason,
            saveTimedOut = (saveState as? ConfigSaveState.Error)?.timedOut == true,
            lastSavedAt = (saveState as? ConfigSaveState.Saved)?.atEpochMs,
            savedLocallyOnly = (saveState as? ConfigSaveState.Saved)?.localOnly == true,
            validationErrors = draft.validate(),
            sessionActive = sessionActive,
            sessionPaused = studyState is StudyState.Paused,
            serverChangedWhileDirty = dirtyNotice,
            selectedDeckUnavailable = bundle.decks.isNotEmpty() &&
                draft.activeDeck != null &&
                bundle.decks.none { it.name == draft.activeDeck }
        )
    }

    init {
        // Load authoritative config + decks once the server can answer (§17).
        studyControlRepository.requestConfig()
        dashboardRepository.refreshDecks()

        viewModelScope.launch {
            studyControlRepository.serverPushedConfig.collect { pushed ->
                // §115: never silently overwrite unsaved edits.
                val dirty = studyControlRepository.draft.value != null &&
                    studyControlRepository.draft.value != studyControlRepository.serverConfig.value
                _serverChangedWhileDirty.value = dirty
            }
        }
    }

    // ------------------------------------------------------------------ draft editing (§50)

    fun updateDraft(transform: (StudyControlConfig) -> StudyControlConfig) {
        studyControlRepository.updateDraft(transform)
    }

    fun selectDeck(deckName: String) {
        // Changing the deck changes the draft (§55); it never starts a session.
        studyControlRepository.updateDraft { it.copy(activeDeck = deckName) }
    }

    // ------------------------------------------------------------------ presets (§70-§73)

    /** Applies a preset through the centralized model — no scattered UI conditionals (§71). */
    fun applyPreset(preset: StudyPreset) {
        studyControlRepository.updateDraft { preset.applyTo(it) }
        // The explicit preset adapter: device-level hands-free behavior only (§74).
        val behavior = preset.localBehavior()
        val prefs = preferencesDataStore
        if (behavior != null && prefs != null) {
            viewModelScope.launch {
                prefs.updateSettings { settings -> behavior.applyTo(settings) }
            }
        }
    }

    // ------------------------------------------------------------------ save / rollback (§51-§53)

    fun applyDraft() {
        val candidate = studyControlRepository.draft.value
            ?: studyControlRepository.serverConfig.value
            ?: studyControlRepository.localConfig.value
        viewModelScope.launch { studyControlRepository.saveConfig(candidate) }
    }

    fun discardDraft() {
        studyControlRepository.discardDraft()
        _serverChangedWhileDirty.value = false
    }

    fun reloadServerConfig() {
        studyControlRepository.discardDraft()
        studyControlRepository.requestConfig()
        _serverChangedWhileDirty.value = false
    }

    fun keepMyDraft() {
        _serverChangedWhileDirty.value = false
    }

    /** Reset to model defaults (§122) — never duplicated constants in the UI. */
    fun resetToDefaults() {
        studyControlRepository.updateDraft { StudyControlConfig(activeDeck = it.activeDeck) }
    }

    // ------------------------------------------------------------------ active session (§82)

    fun pauseSession() {
        viewModelScope.launch { studySessionRepository.pauseStudy() }
    }

    fun resumeSession() {
        viewModelScope.launch { studySessionRepository.resumeStudy() }
    }

    fun endSession() {
        viewModelScope.launch { studySessionRepository.endStudy() }
    }

    fun refreshDecks() = dashboardRepository.refreshDecks()
}
