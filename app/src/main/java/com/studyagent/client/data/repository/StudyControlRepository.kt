package com.studyagent.client.data.repository

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.SessionStartConfig
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.network.ProtocolJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

/** Terminal result of a save attempt. The UI maps this to user-visible states. */
sealed interface ConfigSaveResult {
    /** Server acknowledged the change (ACK correlated). */
    data object Saved : ConfigSaveResult

    /** No `study_config` capability (e.g. Protocol v1): persisted locally only. */
    data object SavedLocally : ConfigSaveResult

    /** Server explicitly rejected the change; authoritative config unchanged. */
    data class Rejected(val reason: String) : ConfigSaveResult

    /** Bounded wait elapsed without an ACK; authoritative config unchanged (§53). */
    data object TimedOut : ConfigSaveResult

    /** A save was already in flight — rapid taps must not duplicate updates (§140). */
    data object AlreadySaving : ConfigSaveResult

    /** Local model validation failed — nothing was sent (§54/§136). */
    data class Invalid(val errors: List<String>) : ConfigSaveResult

    data object NotConnected : ConfigSaveResult
}

/** Observable save lifecycle for the Control Center UI. */
sealed interface ConfigSaveState {
    data object Idle : ConfigSaveState
    data object Saving : ConfigSaveState
    data class Error(val reason: String, val timedOut: Boolean = false) : ConfigSaveState
    data class Saved(val atEpochMs: Long, val localOnly: Boolean) : ConfigSaveState
}

/** What Smart Start sends when the user presses Start (§75/§76). */
data class StartStudyRequest(
    val deck: String?,
    val mode: String,
    /** Null on Protocol v1 servers — the v1 start only carries deck + mode (§76). */
    val config: SessionStartConfig?
)

/**
 * Data layer for the Study Control Center (§9).
 *
 * Keeps three distinct truths:
 *  - [serverConfig]: the PC agent's authoritative configuration (null until known).
 *  - [draft]: the user's unsaved edits — persisted locally so leaving the screen
 *    never loses work (§123).
 *  - [localConfig]: last-known safe fallback used on v1 servers / while offline.
 *
 * Saving is ACK-correlated: `update_study_config(messageId=X)` must be answered by
 * `study_config_updated` echoing `message_id=X` (or an `error` frame). Rejection and
 * timeout leave [serverConfig] untouched — no optimistic commits (§51-§53/§114).
 */
interface StudyControlRepository {
    val serverConfig: StateFlow<StudyControlConfig?>
    val localConfig: StateFlow<StudyControlConfig>
    val draft: StateFlow<StudyControlConfig?>
    val saveState: StateFlow<ConfigSaveState>

    /** Unsolicited server-side config changes (§115). */
    val serverPushedConfig: SharedFlow<StudyControlConfig>

    /** Request the authoritative config (requires `study_config` capability). */
    fun requestConfig()

    /** Edit the persisted draft. Draft stays when the user navigates away. */
    fun updateDraft(transform: (StudyControlConfig) -> StudyControlConfig)

    /** Drop unsaved edits and re-sync the draft from authoritative data. */
    fun discardDraft()

    suspend fun saveConfig(candidate: StudyControlConfig): ConfigSaveResult

    /**
     * Deck selection from Dashboard/Control: applies on top of the current draft and
     * attempts a synchronized save when the server supports it. Never starts a session.
     */
    fun setActiveDeck(deckName: String?)

    /**
     * Configuration used by the visible Control Center and Smart Start:
     * draft -> serverConfig -> localConfig. This intentionally lets Start use
     * visible unsaved edits; it never silently ignores a user's draft.
     */
    fun effectiveConfig(): StudyControlConfig

    /** Deck/mode/config payload for `start_session`, respecting protocol version. */
    fun currentStartRequest(): StartStudyRequest

    companion object {
        const val DEFAULT_SAVE_TIMEOUT_MS = 8_000L
    }
}

class DefaultStudyControlRepository(
    private val connectionRepository: ConnectionRepository,
    private val capabilityStore: CapabilityStore,
    private val cacheStorage: ManagementCacheStorage,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
    private val saveTimeoutMs: Long = StudyControlRepository.DEFAULT_SAVE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis
) : StudyControlRepository {

    private val tag = "StudyControlRepo"

    private val _serverConfig = MutableStateFlow<StudyControlConfig?>(null)
    override val serverConfig: StateFlow<StudyControlConfig?> = _serverConfig.asStateFlow()

    private val _localConfig = MutableStateFlow(StudyControlConfig())
    override val localConfig: StateFlow<StudyControlConfig> = _localConfig.asStateFlow()

    private val _draft = MutableStateFlow<StudyControlConfig?>(null)
    override val draft: StateFlow<StudyControlConfig?> = _draft.asStateFlow()

    private val _saveState = MutableStateFlow<ConfigSaveState>(ConfigSaveState.Idle)
    override val saveState: StateFlow<ConfigSaveState> = _saveState.asStateFlow()

    private val _serverPushedConfig = MutableSharedFlow<StudyControlConfig>(extraBufferCapacity = 8)
    override val serverPushedConfig: SharedFlow<StudyControlConfig> = _serverPushedConfig.asSharedFlow()

    private val lock = Any()
    private var pendingSave: PendingSave? = null
    private var configRequestInFlight = false
    private val retiredSaveMessageIds = LinkedHashSet<String>()
    private val retiredSaveCandidates = LinkedHashSet<StudyControlConfig>()

    // DataStore edits are atomic, but draft/cache persistence is launched from
    // UI callbacks. Generation checks prevent an older coroutine from winning
    // after a newer draft has already been produced.
    private val draftWriteMutex = Mutex()
    private var draftWriteGeneration = 0L
    private val localWriteMutex = Mutex()
    private var localWriteGeneration = 0L

    private class PendingSave(
        val messageId: String,
        val candidate: StudyControlConfig,
        val result: CompletableDeferred<ConfigSaveResult>,
        /** Config echoed by the ACK, when the server provides one. */
        @Volatile var ackedConfig: StudyControlConfig? = null
    )

    private fun rememberRetiredSaveLocked(messageId: String, candidate: StudyControlConfig) {
        retiredSaveMessageIds.add(messageId)
        retiredSaveCandidates.add(candidate)
        while (retiredSaveMessageIds.size > 32) {
            retiredSaveMessageIds.remove(retiredSaveMessageIds.first())
        }
        while (retiredSaveCandidates.size > 32) {
            retiredSaveCandidates.remove(retiredSaveCandidates.first())
        }
    }

    private fun retireSave(pending: PendingSave) {
        // Removing the pending entry and recording its retired identity must be
        // one critical section. Otherwise a late ACK can land in the tiny gap
        // and be mistaken for an unsolicited server push.
        synchronized(lock) {
            if (pendingSave === pending) pendingSave = null
            rememberRetiredSaveLocked(pending.messageId, pending.candidate)
        }
    }

    init {
        scope.launch { loadLocalCache() }
        scope.launch {
            connectionRepository.incomingMessages.collect { message -> onServerMessage(message) }
        }
        scope.launch {
            capabilityStore.capabilities.collect { caps ->
                if (caps.supportsV2(AgentCapability.STUDY_CONFIG)) {
                    requestConfig()
                }
            }
        }
        scope.launch {
            connectionRepository.connectionState.collect { state ->
                if (!state.isConnected) {
                    synchronized(lock) { configRequestInFlight = false }
                }
            }
        }
    }

    private suspend fun loadLocalCache() {
        // Config cache and user draft are independent recovery domains. A bad
        // draft must not hide a valid last-known config, and vice versa.
        try {
            val cached = cacheStorage.readControlConfigCache()
            if (cached != null &&
                cached.schemaVersion in ManagementCacheSchema.LEGACY_RAW..ManagementCacheSchema.CONTROL_CONFIG
            ) {
                try {
                    val config = ProtocolJson.json.decodeFromString(
                        StudyControlConfig.serializer(),
                        cached.json
                    )
                    if (config.validate().isNotEmpty()) {
                        throw IllegalArgumentException("invalid cached control config")
                    }
                    _localConfig.value = config
                } catch (error: Exception) {
                    AppLogger.w(tag, "Discarding corrupt local control config cache")
                    cacheStorage.saveControlConfigCache(null)
                }
            } else if (cached != null) {
                AppLogger.w(tag, "Discarding unsupported control config cache schema=${cached.schemaVersion}")
                cacheStorage.saveControlConfigCache(null)
            }
        } catch (error: Exception) {
            AppLogger.w(tag, "Control config cache unavailable; using model defaults")
        }

        try {
            val rawDraft = cacheStorage.readControlDraft()
            if (!rawDraft.isNullOrBlank()) {
                val draft = decodeDraft(rawDraft)
                if (draft != null && draft.validate().isEmpty()) {
                    _draft.value = draft
                } else {
                    // Only the invalid draft is removed. Server/local config
                    // remains untouched and can still be used for Start.
                    AppLogger.w(tag, "Discarding corrupt control draft")
                    cacheStorage.saveControlDraft(null)
                }
            }
        } catch (error: Exception) {
            AppLogger.w(tag, "Control draft unavailable; preserving authoritative config")
        }
    }

    private fun decodeDraft(raw: String): StudyControlConfig? {
        // A schemaVersion member identifies an envelope. Do not reinterpret a
        // future/corrupt envelope as a legacy config with all defaults.
        val isEnvelope = try {
            ProtocolJson.json.parseToJsonElement(raw).jsonObject.containsKey("schemaVersion")
        } catch (_: Exception) {
            false
        }
        if (isEnvelope) {
            return try {
                val envelope = ProtocolJson.json.decodeFromString<PersistedControlDraft>(raw)
                if (envelope.schemaVersion !in ManagementCacheSchema.LEGACY_RAW..ManagementCacheSchema.CONTROL_DRAFT) null
                else envelope.config
            } catch (_: Exception) {
                null
            }
        }
        // Legacy installs stored raw StudyControlConfig JSON. It remains a
        // read-only compatibility path and is upgraded on the next edit.
        return try {
            ProtocolJson.json.decodeFromString(StudyControlConfig.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ requests

    override fun requestConfig() {
        scope.launch {
            val caps = capabilityStore.capabilities.value
            if (!caps.supportsV2(AgentCapability.STUDY_CONFIG)) return@launch
            if (!connectionRepository.connectionState.value.isConnected) return@launch
            synchronized(lock) {
                if (configRequestInFlight) return@launch
                configRequestInFlight = true
            }
            val sent = connectionRepository.send(ClientMessage.RequestStudyConfig())
            if (!sent) {
                synchronized(lock) { configRequestInFlight = false }
            }
        }
    }

    // ------------------------------------------------------------------ draft

    override fun updateDraft(transform: (StudyControlConfig) -> StudyControlConfig) {
        val base = _draft.value ?: _serverConfig.value ?: _localConfig.value
        val next = transform(base)
        _draft.value = next
        persistDraft(next)
    }

    override fun discardDraft() {
        _draft.value = null
        persistDraft(null)
    }

    private fun persistDraft(config: StudyControlConfig?) {
        val generation = synchronized(lock) {
            draftWriteGeneration += 1
            draftWriteGeneration
        }
        scope.launch {
            draftWriteMutex.withLock {
                if (generation != synchronized(lock) { draftWriteGeneration }) return@withLock
                try {
                    val json = config?.let {
                        ProtocolJson.json.encodeToString(
                            PersistedControlDraft(
                                schemaVersion = ManagementCacheSchema.CONTROL_DRAFT,
                                savedAtEpochMs = clock(),
                                config = it
                            )
                        )
                    }
                    cacheStorage.saveControlDraft(json)
                } catch (error: Exception) {
                    AppLogger.w(tag, "Failed to persist control draft")
                }
            }
        }
    }

    private fun persistLocal(config: StudyControlConfig) {
        _localConfig.value = config
        val generation = synchronized(lock) {
            localWriteGeneration += 1
            localWriteGeneration
        }
        scope.launch {
            localWriteMutex.withLock {
                if (generation != synchronized(lock) { localWriteGeneration }) return@withLock
                try {
                    val json = ProtocolJson.json.encodeToString(
                        StudyControlConfig.serializer(),
                        config
                    )
                    cacheStorage.saveControlConfigCache(
                        CachedPayload(json, clock(), ManagementCacheSchema.CONTROL_CONFIG)
                    )
                } catch (error: Exception) {
                    AppLogger.w(tag, "Failed to persist local control config")
                }
            }
        }
    }

    // ------------------------------------------------------------------ save

    override suspend fun saveConfig(candidate: StudyControlConfig): ConfigSaveResult {
        // Validation lives in the model — the repository never duplicates rules (§54).
        val errors = candidate.validate()
        if (errors.isNotEmpty()) {
            _saveState.value = ConfigSaveState.Error(errors.joinToString("\n"))
            return ConfigSaveResult.Invalid(errors)
        }

        val caps = capabilityStore.capabilities.value
        if (!caps.supportsV2(AgentCapability.STUDY_CONFIG)) {
            // Protocol v1 fallback: keep behavior locally (§12/§116).
            persistLocal(candidate)
            if (_draft.value == candidate) {
                _draft.value = null
                persistDraft(null)
            }
            _saveState.value = ConfigSaveState.Saved(clock(), localOnly = true)
            return ConfigSaveResult.SavedLocally
        }
        if (!connectionRepository.connectionState.value.isConnected) {
            _saveState.value = ConfigSaveState.Error("Not connected to the Study Agent")
            return ConfigSaveResult.NotConnected
        }
        synchronized(lock) {
            if (pendingSave != null) return ConfigSaveResult.AlreadySaving
        }

        val message = ClientMessage.UpdateStudyConfig(config = candidate)
        val pending = PendingSave(message.messageId, candidate, CompletableDeferred())
        synchronized(lock) { pendingSave = pending }
        _saveState.value = ConfigSaveState.Saving

        val sent = connectionRepository.send(message)
        if (!sent) {
            retireSave(pending)
            _saveState.value = ConfigSaveState.Error("Could not reach the Study Agent")
            return ConfigSaveResult.NotConnected
        }

        val result = withTimeoutOrNull(saveTimeoutMs) { pending.result.await() }
        // A late ACK after timeout/rejection is a response to an already
        // completed request, not a new server push. Retire every id after the
        // waiter has completed so duplicate frames cannot mutate local truth.
        retireSave(pending)
        return when (result) {
            null -> {
                _saveState.value = ConfigSaveState.Error("Could not confirm settings.", timedOut = true)
                ConfigSaveResult.TimedOut
            }

            ConfigSaveResult.Saved -> {
                // Prefer the config echoed by the ACK; fall back to what we sent.
                val committed = pending.ackedConfig ?: candidate
                _serverConfig.value = committed
                persistLocal(committed)
                // A user may have continued editing while this candidate was
                // in flight. Only clear the draft that was actually committed;
                // a newer draft is valuable user work and must survive.
                if (_draft.value == candidate) {
                    _draft.value = null
                    persistDraft(null)
                }
                _saveState.value = ConfigSaveState.Saved(clock(), localOnly = false)
                ConfigSaveResult.Saved
            }

            is ConfigSaveResult.Rejected -> {
                // Draft is retained by the caller; authoritative config untouched (§52).
                _saveState.value = ConfigSaveState.Error(result.reason)
                result
            }

            else -> result
        }
    }

    // ------------------------------------------------------------------ deck selection

    override fun setActiveDeck(deckName: String?) {
        updateDraft { it.copy(activeDeck = deckName) }
        val candidate = _draft.value ?: return
        scope.launch {
            val caps = capabilityStore.capabilities.value
            if (caps.supportsV2(AgentCapability.STUDY_CONFIG) &&
                connectionRepository.connectionState.value.isConnected
            ) {
                saveConfig(candidate)
            }
            // On v1/offline the draft already persists the choice locally (§116).
        }
    }

    // ------------------------------------------------------------------ startup payload

    override fun effectiveConfig(): StudyControlConfig =
        _draft.value ?: _serverConfig.value ?: _localConfig.value

    override fun currentStartRequest(): StartStudyRequest {
        val config = effectiveConfig()
        val caps = capabilityStore.capabilities.value
        return StartStudyRequest(
            deck = config.activeDeck,
            mode = config.studyMode.wireValue,
            config = if (caps.isProtocolV2) config.toSessionStartConfig() else null
        )
    }

    // ------------------------------------------------------------------ server messages

    private fun onServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.StudyConfigResponse -> onConfigResponse(message)
            is ServerMessage.StudyConfigUpdated -> onConfigUpdated(message)
            is ServerMessage.ErrorMessage -> onError(message)
            else -> Unit
        }
    }

    private fun onConfigResponse(message: ServerMessage.StudyConfigResponse) {
        synchronized(lock) { configRequestInFlight = false }
        val config = message.config ?: return
        _serverConfig.value = config
        persistLocal(config)
        // Keep the draft only when it carries genuine unsaved edits (§115).
        val currentDraft = _draft.value
        if (currentDraft == null || currentDraft == config) {
            _draft.value = null
            persistDraft(null)
        }
    }

    private fun onConfigUpdated(message: ServerMessage.StudyConfigUpdated) {
        val config = message.config
        val retired = synchronized(lock) {
            message.messageId?.let { it in retiredSaveMessageIds } == true ||
                (message.messageId == null && config != null && config in retiredSaveCandidates)
        }
        if (retired) return
        val pending = synchronized(lock) { pendingSave }
        val ackMatchesPending = pending != null &&
            (message.messageId == pending.messageId ||
                (message.messageId == null && config != null && config == pending.candidate))
        if (ackMatchesPending && pending != null) {
            // ACK correlation (§114): complete the awaiting save.
            pending.ackedConfig = config
            pending.result.complete(ConfigSaveResult.Saved)
            return
        }
        // Server-initiated change (§115): commit authoritative config; the UI decides
        // what happens to unsaved drafts.
        if (config != null) {
            _serverConfig.value = config
            persistLocal(config)
            _serverPushedConfig.tryEmit(config)
        }
    }

    private fun onError(message: ServerMessage.ErrorMessage) {
        val pending = synchronized(lock) { pendingSave } ?: return
        // Protocol v2 correlates errors. A null id is retained only as the
        // backward-compatible v1/v2 fallback; an unrelated id must not reject a
        // save that is still waiting for its own ACK.
        if (message.messageId != null && message.messageId != pending.messageId) {
            AppLogger.d(tag, "Ignoring uncorrelated config error")
            return
        }
        // A rejection ends the save attempt; the draft survives (§52).
        pending.result.complete(ConfigSaveResult.Rejected(message.message))
    }
}
