package com.studyagent.client.data.repository

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.TimeFormatting
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.AiUsageRange
import com.studyagent.client.core.models.AiUsageSummary
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ComponentHealth
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DashboardSnapshotPayload
import com.studyagent.client.core.models.DataFreshness
import com.studyagent.client.core.models.DeckSummary
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.StatsRange
import com.studyagent.client.core.models.StudyHistoryPayload
import com.studyagent.client.core.network.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** Why a dashboard request could not produce data. Every state is actionable in the UI. */
sealed interface DashboardError {
    data object NotConnected : DashboardError
    data object NotSupported : DashboardError
    data object TimedOut : DashboardError
    data class RequestFailed(val message: String, val code: String? = null) : DashboardError
}

/**
 * Immutable dashboard data container. One coherent StateFlow instead of many
 * unrelated UI booleans (§13). Panel timestamps drive freshness labels; the
 * UI must never present cached values as live (§14).
 */
data class DashboardData(
    val snapshot: DashboardSnapshotPayload? = null,
    val snapshotUpdatedAtMs: Long? = null,
    /** True when [snapshot] was restored from the local cache rather than received live. */
    val snapshotFromCache: Boolean = false,
    val decks: List<DeckSummary> = emptyList(),
    val decksUpdatedAtMs: Long? = null,
    val componentHealth: ComponentHealth? = null,
    val history: StudyHistoryPayload? = null,
    val historyRange: StatsRange = StatsRange.SEVEN_DAYS,
    val historyUpdatedAtMs: Long? = null,
    val aiUsage: AiUsageSummary? = null,
    val aiUsageRange: AiUsageRange = AiUsageRange.MONTH,
    val aiUsageUpdatedAtMs: Long? = null,
    /** Live session summary merged from snapshot + session_progress/session_stats pushes. */
    val activeSession: SessionSummaryPayload? = null,
    val isRefreshing: Boolean = false,
    val error: DashboardError? = null
)

/**
 * Data layer for the operational dashboard (§8).
 *
 * Responsibilities: capability-aware requests, single-flight coalescing (§111),
 * bounded timeouts (§112), out-of-order protection via request generations
 * (§113), panel-scoped merging (§92), offline cache with explicit freshness
 * (§15/§16) and live session push-updates (§18) — so opening Home never polls
 * and never triggers expensive AI work (§17/§40).
 */
interface DashboardRepository {
    val data: StateFlow<DashboardData>

    /** Full dashboard refresh (snapshot + decks + health when supported). Coalesced. */
    fun refresh(reason: String)

    /** Refresh only when current snapshot data is missing or older than [maxAgeMs]. */
    fun refreshIfStale(maxAgeMs: Long, reason: String)

    fun refreshDecks()
    fun refreshHistory(range: StatsRange)
    fun refreshAiUsage(range: AiUsageRange)
    /** Explicit user action only — never called on Home open (§40/§147). */
    fun refreshInsight()
    fun refreshComponentHealth()

    fun clearError()

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 8_000L
    }
}

class DefaultDashboardRepository(
    private val connectionRepository: ConnectionRepository,
    private val capabilityStore: CapabilityStore,
    private val cacheStorage: ManagementCacheStorage,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
    private val requestTimeoutMs: Long = DashboardRepository.DEFAULT_REQUEST_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis
) : DashboardRepository {

    private val tag = "DashboardRepo"

    private val _data = MutableStateFlow(DashboardData())
    override val data: StateFlow<DashboardData> = _data.asStateFlow()

    private val lock = Any()

    // -- snapshot request bookkeeping (§110-§113)
    private var snapshotInFlight = false
    private var snapshotPendingAfterFlight = false
    private var snapshotGeneration = 0L
    private var snapshotAppliedGeneration = 0L
    private var outstandingSnapshotMessageId: String? = null
    private var snapshotTimeoutJob: Job? = null
    private var cacheLoaded = false

    // Cache writes are optional persistence, but they must still be ordered: an
    // older response completing late must not overwrite a newer snapshot.
    private val snapshotCacheWriteMutex = Mutex()
    private var snapshotCacheWriteGeneration = 0L
    private val decksCacheWriteMutex = Mutex()
    private var decksCacheWriteGeneration = 0L

    // -- panel request bookkeeping
    private var decksInFlight = false
    private var outstandingDecksMessageId: String? = null
    private var decksTimeoutJob: Job? = null
    private var historyInFlight = false
    private var outstandingHistoryMessageId: String? = null
    private var outstandingHistoryRange: StatsRange = StatsRange.SEVEN_DAYS
    private var historyTimeoutJob: Job? = null
    private var aiUsageInFlight = false
    private var outstandingAiUsageMessageId: String? = null
    private var outstandingAiUsageRange: AiUsageRange = AiUsageRange.MONTH
    private var aiUsageTimeoutJob: Job? = null

    init {
        scope.launch { loadCache() }
        scope.launch {
            connectionRepository.incomingMessages.collect { message -> onServerMessage(message) }
        }
        scope.launch {
            capabilityStore.capabilities.collect { caps ->
                if (caps.isProtocolV2 && caps.supports(AgentCapability.DASHBOARD)) {
                    // Negotiation just completed for this connection (§17).
                    refreshInternal("capabilities-negotiated")
                    if (caps.supports(AgentCapability.DECK_LIST)) refreshDecks()
                    if (caps.supports(AgentCapability.COMPONENT_HEALTH)) refreshComponentHealth()
                }
            }
        }
        scope.launch {
            connectionRepository.connectionState.collect { state ->
                if (!state.isConnected) {
                    // Drop in-flight bookkeeping; cached data stays visible (§15).
                    synchronized(lock) {
                        snapshotInFlight = false
                        snapshotPendingAfterFlight = false
                        snapshotTimeoutJob?.cancel()
                        decksInFlight = false
                        decksTimeoutJob?.cancel()
                        historyInFlight = false
                        historyTimeoutJob?.cancel()
                        aiUsageInFlight = false
                        aiUsageTimeoutJob?.cancel()
                    }
                    _data.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ cache

    private suspend fun loadCache() {
        // Each optional cache is isolated. A malformed dashboard payload must not
        // prevent decks from loading, and neither can affect AppSettings.
        try {
            val cachedSnapshot = cacheStorage.readDashboardCache()
            if (cachedSnapshot != null &&
                cachedSnapshot.schemaVersion in ManagementCacheSchema.LEGACY_RAW..ManagementCacheSchema.DASHBOARD
            ) {
                try {
                    val payload = ProtocolJson.json.decodeFromString(
                        DashboardSnapshotPayload.serializer(),
                        cachedSnapshot.json
                    )
                    _data.update { current ->
                        if (current.snapshot != null) current else current.copy(
                            snapshot = payload,
                            snapshotUpdatedAtMs = cachedSnapshot.savedAtEpochMs,
                            snapshotFromCache = true,
                            activeSession = payload.currentSession
                        )
                    }
                } catch (error: Exception) {
                    AppLogger.w(tag, "Discarding corrupt dashboard cache payload")
                    cacheStorage.saveDashboardCache(null)
                }
            } else if (cachedSnapshot != null) {
                AppLogger.w(tag, "Discarding unsupported dashboard cache schema=${cachedSnapshot.schemaVersion}")
                cacheStorage.saveDashboardCache(null)
            }
        } catch (error: Exception) {
            AppLogger.w(tag, "Dashboard cache unavailable; requesting fresh data")
        }

        try {
            val cachedDecks = cacheStorage.readDecksCache()
            if (cachedDecks != null &&
                cachedDecks.schemaVersion in ManagementCacheSchema.LEGACY_RAW..ManagementCacheSchema.DECKS
            ) {
                try {
                    val decks = ProtocolJson.json.decodeFromString(
                        ListSerializer(DeckSummary.serializer()),
                        cachedDecks.json
                    )
                    _data.update { current ->
                        if (current.decks.isNotEmpty()) current else current.copy(
                            decks = decks,
                            decksUpdatedAtMs = cachedDecks.savedAtEpochMs
                        )
                    }
                } catch (error: Exception) {
                    AppLogger.w(tag, "Discarding corrupt decks cache payload")
                    cacheStorage.saveDecksCache(null)
                }
            } else if (cachedDecks != null) {
                AppLogger.w(tag, "Discarding unsupported decks cache schema=${cachedDecks.schemaVersion}")
                cacheStorage.saveDecksCache(null)
            }
        } catch (error: Exception) {
            AppLogger.w(tag, "Decks cache unavailable; continuing without cached decks")
        } finally {
            cacheLoaded = true
        }
    }

    private fun persistSnapshotCache(payload: DashboardSnapshotPayload) {
        val generation = synchronized(lock) {
            snapshotCacheWriteGeneration += 1
            snapshotCacheWriteGeneration
        }
        scope.launch {
            snapshotCacheWriteMutex.withLock {
                if (generation != synchronized(lock) { snapshotCacheWriteGeneration }) return@withLock
                try {
                    val json = ProtocolJson.json.encodeToString(
                        DashboardSnapshotPayload.serializer(),
                        payload
                    )
                    cacheStorage.saveDashboardCache(
                        CachedPayload(json, clock(), ManagementCacheSchema.DASHBOARD)
                    )
                } catch (error: Exception) {
                    AppLogger.w(tag, "Failed to persist dashboard cache")
                }
            }
        }
    }

    private fun persistDecksCache(decks: List<DeckSummary>) {
        val generation = synchronized(lock) {
            decksCacheWriteGeneration += 1
            decksCacheWriteGeneration
        }
        scope.launch {
            decksCacheWriteMutex.withLock {
                if (generation != synchronized(lock) { decksCacheWriteGeneration }) return@withLock
                try {
                    val json = ProtocolJson.json.encodeToString(
                        ListSerializer(DeckSummary.serializer()),
                        decks
                    )
                    cacheStorage.saveDecksCache(
                        CachedPayload(json, clock(), ManagementCacheSchema.DECKS)
                    )
                } catch (error: Exception) {
                    AppLogger.w(tag, "Failed to persist decks cache")
                }
            }
        }
    }

    // ------------------------------------------------------------------ public API

    override fun refresh(reason: String) {
        scope.launch { refreshInternal(reason) }
    }

    override fun refreshIfStale(maxAgeMs: Long, reason: String) {
        val state = _data.value
        val age = state.snapshotUpdatedAtMs?.let { clock() - it }
        val needsRefresh = state.snapshot == null || age == null || age > maxAgeMs
        if (needsRefresh) refresh(reason)
    }

    override fun refreshDecks() {
        scope.launch {
            val caps = capabilityStore.capabilities.value
            if (!caps.supportsV2(AgentCapability.DECK_LIST)) return@launch
            if (!connectionRepository.connectionState.value.isConnected) return@launch
            synchronized(lock) {
                if (decksInFlight) return@launch
                decksInFlight = true
            }
            val msg = ClientMessage.RequestDecks()
            synchronized(lock) {
                outstandingDecksMessageId = msg.messageId
                decksTimeoutJob?.cancel()
                decksTimeoutJob = scope.launch {
                    kotlinx.coroutines.delay(requestTimeoutMs)
                    synchronized(lock) {
                        if (outstandingDecksMessageId == msg.messageId) {
                            decksInFlight = false
                            outstandingDecksMessageId = null
                        }
                    }
                }
            }
            val sent = connectionRepository.send(msg)
            if (!sent) {
                synchronized(lock) {
                    decksInFlight = false
                    outstandingDecksMessageId = null
                    decksTimeoutJob?.cancel()
                }
            }
        }
    }

    override fun refreshHistory(range: StatsRange) {
        scope.launch {
            val caps = capabilityStore.capabilities.value
            if (!caps.supportsV2(AgentCapability.HISTORY)) return@launch
            if (!connectionRepository.connectionState.value.isConnected) return@launch
            synchronized(lock) {
                if (historyInFlight) return@launch
                historyInFlight = true
            }
            val msg = ClientMessage.RequestHistory(range = range.wireValue)
            synchronized(lock) {
                outstandingHistoryMessageId = msg.messageId
                outstandingHistoryRange = range
                historyTimeoutJob?.cancel()
                historyTimeoutJob = scope.launch {
                    kotlinx.coroutines.delay(requestTimeoutMs)
                    synchronized(lock) {
                        if (outstandingHistoryMessageId == msg.messageId) {
                            historyInFlight = false
                            outstandingHistoryMessageId = null
                        }
                    }
                }
            }
            val sent = connectionRepository.send(msg)
            if (!sent) {
                synchronized(lock) {
                    historyInFlight = false
                    outstandingHistoryMessageId = null
                    historyTimeoutJob?.cancel()
                }
            }
        }
    }

    override fun refreshAiUsage(range: AiUsageRange) {
        scope.launch {
            val caps = capabilityStore.capabilities.value
            if (!caps.supportsV2(AgentCapability.AI_USAGE)) return@launch
            if (!connectionRepository.connectionState.value.isConnected) return@launch
            synchronized(lock) {
                if (aiUsageInFlight) return@launch
                aiUsageInFlight = true
            }
            val msg = ClientMessage.RequestAiUsage(range = range.wireValue)
            synchronized(lock) {
                outstandingAiUsageMessageId = msg.messageId
                outstandingAiUsageRange = range
                aiUsageTimeoutJob?.cancel()
                aiUsageTimeoutJob = scope.launch {
                    kotlinx.coroutines.delay(requestTimeoutMs)
                    synchronized(lock) {
                        if (outstandingAiUsageMessageId == msg.messageId) {
                            aiUsageInFlight = false
                            outstandingAiUsageMessageId = null
                        }
                    }
                }
            }
            val sent = connectionRepository.send(msg)
            if (!sent) {
                synchronized(lock) {
                    aiUsageInFlight = false
                    outstandingAiUsageMessageId = null
                    aiUsageTimeoutJob?.cancel()
                }
            }
        }
    }

    override fun refreshInsight() {
        scope.launch {
            val caps = capabilityStore.capabilities.value
            if (!caps.supportsV2(AgentCapability.LEARNING_INSIGHTS)) return@launch
            if (!connectionRepository.connectionState.value.isConnected) return@launch
            connectionRepository.send(ClientMessage.RequestLearningInsights())
        }
    }

    override fun refreshComponentHealth() {
        scope.launch {
            val caps = capabilityStore.capabilities.value
            if (!caps.supportsV2(AgentCapability.COMPONENT_HEALTH)) return@launch
            if (!connectionRepository.connectionState.value.isConnected) return@launch
            connectionRepository.send(ClientMessage.RequestComponentHealth())
        }
    }

    override fun clearError() {
        _data.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------ snapshot refresh

    private suspend fun refreshInternal(reason: String) {
        val caps = capabilityStore.capabilities.value
        if (!caps.isProtocolV2) {
            if (caps.isLegacyV1) {
                _data.update { it.copy(error = DashboardError.NotSupported, isRefreshing = false) }
            }
            return
        }
        if (!caps.supports(AgentCapability.DASHBOARD)) {
            _data.update { it.copy(error = DashboardError.NotSupported, isRefreshing = false) }
            return
        }
        if (!connectionRepository.connectionState.value.isConnected) {
            _data.update { it.copy(error = DashboardError.NotConnected) }
            return
        }
        val generation: Long
        val msg: ClientMessage.RequestDashboard
        synchronized(lock) {
            if (snapshotInFlight) {
                // Coalesce (§111): one extra refresh after the current flight lands.
                snapshotPendingAfterFlight = true
                AppLogger.d(tag, "Dashboard refresh coalesced (already in flight, reason=$reason)")
                return
            }
            snapshotInFlight = true
            snapshotPendingAfterFlight = false
            snapshotGeneration += 1
            generation = snapshotGeneration
            msg = ClientMessage.RequestDashboard()
            outstandingSnapshotMessageId = msg.messageId
            snapshotTimeoutJob?.cancel()
            snapshotTimeoutJob = scope.launch {
                kotlinx.coroutines.delay(requestTimeoutMs)
                onSnapshotTimeout(generation)
            }
        }
        _data.update { it.copy(isRefreshing = true, error = null) }
        AppLogger.d(tag, "Dashboard refresh gen=$generation reason=$reason")
        val sent = connectionRepository.send(msg)
        if (!sent) {
            synchronized(lock) {
                snapshotInFlight = false
                snapshotTimeoutJob?.cancel()
                outstandingSnapshotMessageId = null
            }
            _data.update { it.copy(isRefreshing = false, error = DashboardError.NotConnected) }
        }
    }

    private fun onSnapshotTimeout(generation: Long) {
        val stillThisRequest = synchronized(lock) {
            if (snapshotInFlight && snapshotGeneration == generation) {
                snapshotInFlight = false
                outstandingSnapshotMessageId = null
                true
            } else false
        }
        if (stillThisRequest) {
            AppLogger.w(tag, "Dashboard request gen=$generation timed out")
            _data.update { it.copy(isRefreshing = false, error = DashboardError.TimedOut) }
        }
    }

    // ------------------------------------------------------------------ message handling

    private fun onServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.DashboardSnapshotResponse -> onSnapshotResponse(message)
            is ServerMessage.DeckListResponse -> onDeckList(message)
            is ServerMessage.ComponentHealthResponse -> onComponentHealth(message)
            is ServerMessage.StudyHistoryResponse -> onHistory(message)
            is ServerMessage.LearningInsightResponse -> onInsight(message)
            is ServerMessage.AiUsageResponse -> onAiUsage(message)
            is ServerMessage.ErrorMessage -> onServerError(message)
            is ServerMessage.SessionStarted -> onSessionStarted(message)
            is ServerMessage.SessionProgress -> onSessionProgress(message)
            is ServerMessage.SessionStats -> onSessionStats(message)
            is ServerMessage.SessionPaused -> patchActiveSession { it.copy(isPaused = true) }
            is ServerMessage.SessionResumed -> patchActiveSession { it.copy(isPaused = false) }
            is ServerMessage.SessionFinished -> onSessionFinished(message)
            else -> Unit
        }
    }

    private fun onSnapshotResponse(message: ServerMessage.DashboardSnapshotResponse) {
        val payload = message.snapshot ?: return
        var completesInFlight = false
        var shouldApply = false
        var runFollowUp = false
        synchronized(lock) {
            // A response completes the in-flight request when it correlates to it
            // (v2 in_reply_to, or an echoed/absent id on legacy servers).
            completesInFlight = snapshotInFlight &&
                ProtocolJson.isReplyTo(message, outstandingSnapshotMessageId)
            if (completesInFlight) {
                snapshotAppliedGeneration = snapshotGeneration
                snapshotInFlight = false
                outstandingSnapshotMessageId = null
                snapshotTimeoutJob?.cancel()
            }
            // Out-of-order guard (§113): an unsolicited/stale snapshot may only win
            // when the server says it is newer than what we already show.
            shouldApply = completesInFlight ||
                TimeSorting.isNewerGeneratedAt(payload.generatedAt, _data.value.snapshot?.generatedAt)
            runFollowUp = snapshotPendingAfterFlight
            snapshotPendingAfterFlight = false
        }
        if (!shouldApply) {
            AppLogger.d(tag, "Ignoring stale dashboard snapshot (out-of-order protection)")
            return
        }
        val now = clock()
        _data.update { current ->
            current.copy(
                snapshot = payload,
                snapshotUpdatedAtMs = now,
                snapshotFromCache = false,
                componentHealth = payload.componentHealth ?: current.componentHealth,
                activeSession = payload.currentSession ?: current.activeSession,
                isRefreshing = false,
                error = null
            )
        }
        persistSnapshotCache(payload)
        if (runFollowUp) refresh("coalesced-follow-up")
    }

    private fun onDeckList(message: ServerMessage.DeckListResponse) {
        synchronized(lock) {
            if (decksInFlight && ProtocolJson.isReplyTo(message, outstandingDecksMessageId)) {
                decksInFlight = false
                outstandingDecksMessageId = null
                decksTimeoutJob?.cancel()
            }
        }
        val now = clock()
        _data.update { it.copy(decks = message.decks, decksUpdatedAtMs = now) }
        persistDecksCache(message.decks)
        // Selected-deck validity is checked by the UI against this live list (§118).
    }

    private fun onComponentHealth(message: ServerMessage.ComponentHealthResponse) {
        val health = ComponentHealth.fromEntries(message.components, updatedAt = message.timestamp)
        _data.update { it.copy(componentHealth = it.componentHealth?.mergedWith(health) ?: health) }
    }

    private fun onHistory(message: ServerMessage.StudyHistoryResponse) {
        val payload = message.history ?: return
        var responseRange: StatsRange? = null
        var ignore = false
        synchronized(lock) {
            val isOutstanding = historyInFlight &&
                ProtocolJson.isReplyTo(message, outstandingHistoryMessageId)
            if (isOutstanding) {
                responseRange = outstandingHistoryRange
                historyInFlight = false
                outstandingHistoryMessageId = null
                historyTimeoutJob?.cancel()
                // A response for a superseded range must not overwrite newer data (§113).
                if (payload.range != null && StatsRange.fromWire(payload.range) != outstandingHistoryRange) {
                    ignore = true
                }
            } else {
                responseRange = StatsRange.fromWire(payload.range)
            }
        }
        if (ignore) return
        // Only replace the history panel (§92) — other snapshot sections are untouched.
        val range = responseRange ?: StatsRange.fromWire(payload.range)
        _data.update {
            it.copy(history = payload, historyRange = range, historyUpdatedAtMs = clock())
        }
    }

    private fun onInsight(message: ServerMessage.LearningInsightResponse) {
        val insight = message.insights.firstOrNull() ?: return
        _data.update { current ->
            val snapshot = current.snapshot?.copy(insight = insight)
            current.copy(snapshot = snapshot)
        }
    }

    private fun onAiUsage(message: ServerMessage.AiUsageResponse) {
        val usage = message.usage ?: return
        synchronized(lock) {
            if (aiUsageInFlight && ProtocolJson.isReplyTo(message, outstandingAiUsageMessageId)) {
                aiUsageInFlight = false
                outstandingAiUsageMessageId = null
                aiUsageTimeoutJob?.cancel()
            }
        }
        _data.update {
            it.copy(
                aiUsage = usage,
                aiUsageRange = AiUsageRange.fromWire(usage.range ?: outstandingAiUsageRange.wireValue),
                aiUsageUpdatedAtMs = clock()
            )
        }
    }

    private fun onServerError(message: ServerMessage.ErrorMessage) {
        // Only an error correlated to the outstanding request fails it; anything
        // else (study errors, pushes, other panels) must not kill this refresh.
        val failedSnapshot = synchronized(lock) {
            if (snapshotInFlight && ProtocolJson.isReplyTo(message, outstandingSnapshotMessageId)) {
                snapshotInFlight = false
                snapshotTimeoutJob?.cancel()
                outstandingSnapshotMessageId = null
                true
            } else false
        }
        if (failedSnapshot) {
            _data.update {
                it.copy(
                    isRefreshing = false,
                    error = DashboardError.RequestFailed(message.message, message.code)
                )
            }
        }
    }

    // ------------------------------------------------------------------ live session pushes (§18)

    private fun onSessionStarted(message: ServerMessage.SessionStarted) {
        _data.update {
            it.copy(
                activeSession = SessionSummaryPayload(
                    sessionId = message.sessionId,
                    deck = message.deck ?: it.activeSession?.deck,
                    cardsReviewed = 0,
                    totalCards = message.totalCards,
                    elapsedSeconds = 0,
                    isPaused = false
                )
            )
        }
    }

    private fun onSessionProgress(message: ServerMessage.SessionProgress) {
        patchActiveSession { session ->
            session.copy(
                cardsReviewed = message.currentCardIndex ?: session.cardsReviewed,
                totalCards = message.totalCards ?: session.totalCards
            )
        }
    }

    private fun onSessionStats(message: ServerMessage.SessionStats) {
        patchActiveSession { session ->
            session.copy(
                cardsReviewed = message.cardsStudied,
                recallRate = message.recallRate ?: session.recallRate
            )
        }
    }

    private fun onSessionFinished(message: ServerMessage.SessionFinished) {
        _data.update { it.copy(activeSession = null) }
        // Stats changed; refresh once the server settles (coalesced, not chatty).
        refresh("session-finished")
    }

    private fun patchActiveSession(transform: (SessionSummaryPayload) -> SessionSummaryPayload) {
        _data.update { current ->
            val session = current.activeSession ?: return@update current
            current.copy(activeSession = transform(session))
        }
    }
}

/** Small helper comparing server `generated_at` timestamps defensively. */
internal object TimeSorting {
    fun isNewerGeneratedAt(candidate: String?, current: String?): Boolean {
        if (candidate == null || current == null) return true
        val candidateMs = TimeFormatting.parseIsoToEpochMs(candidate) ?: return true
        val currentMs = TimeFormatting.parseIsoToEpochMs(current) ?: return true
        return candidateMs >= currentMs
    }
}

/**
 * Pure freshness policy (§14): decides how to label data given age and
 * connectivity. UI renders the result; it never invents freshness itself.
 */
object FreshnessPolicy {
    /** Data newer than this on a live connection is presented as Live. */
    const val LIVE_MAX_AGE_MS = 90_000L

    /** Connected data older than this is Stale until a refresh lands. */
    const val STALE_CONNECTED_AFTER_MS = 10 * 60_000L

    /** Disconnected data older than this is labelled Stale instead of Cached. */
    const val STALE_CACHED_AFTER_MS = 24 * 60 * 60_000L

    fun evaluate(
        hasData: Boolean,
        isConnected: Boolean,
        updatedAtEpochMs: Long?,
        nowMs: Long,
        isRefreshing: Boolean = false
    ): DataFreshness {
        if (!hasData || updatedAtEpochMs == null) {
            return if (isRefreshing) DataFreshness.Loading else DataFreshness.Unavailable
        }
        val age = nowMs - updatedAtEpochMs
        return when {
            isConnected && age <= LIVE_MAX_AGE_MS -> DataFreshness.Live(updatedAtEpochMs)
            isConnected -> DataFreshness.Stale(updatedAtEpochMs)
            age <= STALE_CACHED_AFTER_MS -> DataFreshness.Cached(updatedAtEpochMs)
            else -> DataFreshness.Stale(updatedAtEpochMs)
        }
    }
}
