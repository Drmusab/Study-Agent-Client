package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.unavailabilityError
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.CommitRatingResult
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.common.SystemAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 04 — real backend shell behind GATE 03's AnkiBackend interface (§22).
 *
 * Exposes:
 * - id = ANKIDROID_LOCAL (§23)
 * - availability (StateFlow)
 * - capabilities (StateFlow)
 * - refreshAvailability()
 *
 * GATE 05 implements [getDecks] / [getSelectedDeck] through [AnkiDroidDeckGateway].
 * Review / rating methods still return UnsupportedAction truthfully (§24/§102),
 * never an empty list standing in for "not implemented" (INV-ANKI-DECK-06).
 *
 * Architecture:
 * AnkiDroidGateway (health) + AnkiDroidDeckGateway (decks)
 *   ↓
 * AnkiDroidBackend
 *   ↓
 * AnkiBackend (domain)
 *
 * No Cursor, ContentResolver, Uri or provider JSON escapes (§4/§10).
 * Deck operations are read-only (INV-ANKI-DECK-12). No card or review queries.
 */
class AnkiDroidBackend(
    private val gateway: AnkiDroidGateway,
    private val scope: CoroutineScope,
    private val clock: AppClock = SystemAppClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val deckGateway: AnkiDroidDeckGateway
) : AnkiBackend {

    override val id: AnkiBackendId = AnkiBackendId.AnkiDroidLocal

    // Single source of truth (§45): integration state internally, derived flows externally (§44)
    private val _integrationState = MutableStateFlow(gateway.currentState())

    // Derived flows — never three unrelated mutable stores
    override val availability: StateFlow<AnkiAvailability> = _integrationState
        .map { it.availability }
        .stateIn(scope, SharingStarted.Eagerly, _integrationState.value.availability)

    override val capabilities: StateFlow<AnkiCapabilities> = _integrationState
        .map { it.capabilities }
        .stateIn(scope, SharingStarted.Eagerly, _integrationState.value.capabilities)

    /** Full integration state for diagnostics and settings (internal but observable). */
    val integrationState: StateFlow<AnkiDroidIntegrationState> = _integrationState.asStateFlow()

    private val refreshMutex = Mutex()
    private var generation: Long = 0L
    private val publicationGuard = AnkiDroidHealthPublicationGuard()

    init {
        AppLogger.i("AnkiDroidBackend", "AnkiDroidBackend created id=${id.stableId}")
    }

    override suspend fun refreshAvailability() {
        try {
            val requestId = publicationGuard.newRequest()
            val newState = refreshMutex.withLock {
                // Increment generation for stale protection
                generation += 1
                gateway.refreshIntegrationState()
            }

            // Stale protection: only publish if current
            publicationGuard.publishIfCurrent(requestId) {
                _integrationState.value = newState
            }

            if (publicationGuard.currentRequestId != requestId) {
                AppLogger.i("AnkiDroidBackend", "ANKI_HEALTH_STALE_DISCARDED backend gen=$requestId")
            }
        } catch (cancellation: CancellationException) {
            // Cancellation is never converted into domain failure (§33/§34/§79)
            throw cancellation
        } catch (throwable: Throwable) {
            AppLogger.w("AnkiDroidBackend", "ANKI_HEALTH_CHECK_FAILED ${throwable::class.java.simpleName}", throwable)
            // Do not overwrite state with failure unless we have no state — preserve last known
            // The gateway already produced a fault state; we just ensure it's visible
            val faultState = AnkiDroidIntegrationState(
                availability = AnkiAvailability.Fault(AnkiError.Unknown(cause = throwable::class.java.simpleName)),
                capabilities = AnkiCapabilities.NONE,
                apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN,
                metadata = null,
                lastCheckAtMs = clock.nowMillis(),
                latencyMs = null,
                lastError = AnkiError.Unknown(cause = throwable::class.java.simpleName),
                healthSnapshot = null,
                capabilityDetails = emptyList()
            )
            _integrationState.value = faultState
        }
    }

    /**
     * Read-only deck listing (GATE 05). `Success(emptyList())` is a real empty collection;
     * every other outcome is a typed failure (INV-ANKI-DECK-06).
     */
    override suspend fun getDecks(): AnkiResult<List<AnkiDeck>> {
        try {
            guardDeckRead()?.let { return AnkiResult.Failure(it) }
            val authority = currentAuthority()
                ?: return AnkiResult.Failure(AnkiError.QueryFailure(causeCategory = "authority-unknown"))
            return when (val listing = deckGateway.queryDecks(authority)) {
                is AnkiResult.Success -> AnkiResult.Success(listing.value.decks)
                is AnkiResult.Failure -> listing
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    override suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?> {
        try {
            guardDeckRead()?.let { return AnkiResult.Failure(it) }
            val authority = currentAuthority()
                ?: return AnkiResult.Failure(AnkiError.QueryFailure(causeCategory = "authority-unknown"))
            return deckGateway.querySelectedDeck(authority)
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    fun lastDeckQueryDiagnostics(): AnkiDeckQueryDiagnostics = deckGateway.lastListingDiagnostics()

    private fun guardDeckRead(): AnkiError? {
        val availability = _integrationState.value.availability
        availability.unavailabilityError()?.let { return it }
        if (!_integrationState.value.capabilities.deckListing) {
            return AnkiError.UnsupportedAction(action = "deckListing")
        }
        return null
    }

    private fun currentAuthority(): String? = _integrationState.value.metadata?.authority

    override suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession> {
        try {
            AppLogger.i("AnkiDroidBackend", "beginReview called — not yet implemented (GATE 06)")
            return AnkiResult.Failure(
                AnkiError.UnsupportedAction(action = "reviewIntegrationPending")
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    override suspend fun nextCard(session: AnkiReviewSession): NextCardResult {
        try {
            AppLogger.i("AnkiDroidBackend", "nextCard called — not yet implemented (GATE 06)")
            return NextCardResult.Failure(
                AnkiError.UnsupportedAction(action = "reviewIntegrationPending")
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    override suspend fun commitRating(request: CommitRatingRequest): CommitRatingResult {
        try {
            AppLogger.i("AnkiDroidBackend", "commitRating called — not yet implemented (GATE 11)")
            return CommitRatingResult.Rejected(
                AnkiError.UnsupportedAction(action = "ratingCommitIntegrationPending")
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    /**
     * Updates integration state from external health repository (for lifecycle refresh).
     * Used when AnkiDroidHealthRepository produces a new snapshot.
     */
    suspend fun updateFromHealthSnapshot(snapshot: AnkiDroidHealthSnapshot) {
        try {
            val capResult = try {
                // Capability probe based on detection
                // This is read-only, no mutation (§15)
                DefaultAnkiDroidCapabilityProbe(dispatchers, clock::nowMillis).probe(snapshot.detection)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                AnkiDroidCapabilityProbeResult(
                    implemented = AnkiCapabilities.NONE,
                    apiReport = AnkiDroidApiCapabilityReport.UNKNOWN,
                    details = emptyList(),
                    probedAtMs = clock.nowMillis(),
                    latencyMs = 0L
                )
            }

            val metadata = AnkiDroidMetadata(
                packageName = snapshot.detection.packageName,
                providerPackage = snapshot.detection.providerFacts?.providerPackage ?: snapshot.detection.packageName,
                authority = snapshot.detection.authority,
                endpointLabel = snapshot.detection.endpointLabel,
                providerSpec = snapshot.detection.providerSpec,
                providerSpecSource = snapshot.detection.providerFacts?.providerSpecSource,
                packageVersion = null, // will be filled by gateway if available
                providerReachable = snapshot.detection.providerAvailable,
                permissionGranted = snapshot.detection.permissionGranted,
                collectionReady = snapshot.detection.collectionReady,
                checkedAuthorities = snapshot.detection.checkedAuthorities
            )

            val lastError: AnkiError? = when (val avail = snapshot.detection.availability) {
                is AnkiAvailability.Fault -> avail.error
                is AnkiAvailability.Unsupported -> AnkiError.UnsupportedAction("backend_api")
                is AnkiAvailability.ProviderUnavailable -> AnkiError.ProviderUnavailable(detail = avail.detail)
                is AnkiAvailability.PermissionRequired -> AnkiError.PermissionRequired()
                AnkiAvailability.CollectionNotInitialized -> AnkiError.CollectionUnavailable()
                is AnkiAvailability.TemporarilyUnavailable -> AnkiError.QueryFailure("temporarily_unavailable")
                else -> snapshot.detection.failure?.let { AnkiDroidErrorMapper.mapFailure(it, "health_repo_sync") }
            }

            val newState = AnkiDroidIntegrationState(
                availability = snapshot.detection.availability,
                capabilities = capResult.implemented,
                apiCapabilities = capResult.apiReport,
                metadata = metadata,
                lastCheckAtMs = snapshot.checkedAtEpochMs,
                latencyMs = snapshot.durationMs,
                lastError = lastError,
                healthSnapshot = snapshot,
                capabilityDetails = capResult.details
            )

            _integrationState.value = newState
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }
}
