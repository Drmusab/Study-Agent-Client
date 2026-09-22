package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.common.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 02 §46/§59/§60 — the publication rule that keeps a stale check from overwriting a newer
 * one.
 *
 * Every refresh takes a monotonically increasing request id. A result may only be published while
 * it *is* the newest request that was made:
 *
 * ```
 * refresh A (id 1) starts ─────────────────────────────► A finishes, id 1 ≠ newest (2) → discarded
 *          refresh B (id 2) starts ─────► B finishes (id 2 == newest) → published
 * ```
 *
 * Registration and publication share one lock, so there is no window in which an older result can
 * slip in between "a newer request exists" and "the newer result is published". This is what makes
 * INV-ANKI-DET-08 hold even though checks are serialized rather than dropped.
 */
class AnkiDroidHealthPublicationGuard {

    private val lock = Any()
    private var newestRequestId: Long = 0L

    /** Registers a new refresh and returns its id. */
    fun newRequest(): Long = synchronized(lock) {
        newestRequestId += 1
        newestRequestId
    }

    /** Publishes [publish] only when [requestId] is still the newest registered request. */
    fun publishIfCurrent(requestId: Long, publish: () -> Unit) {
        synchronized(lock) {
            if (requestId == newestRequestId) publish()
        }
    }

    /** Newest registered request id; diagnostics/tests only. */
    val currentRequestId: Long get() = synchronized(lock) { newestRequestId }
}

/**
 * GATE 02 — the **one** authoritative runtime owner of AnkiDroid availability (§26/§27/§86/§87).
 *
 * Settings, diagnostics and (later) the backend selector observe this object; none of them
 * re-derives readiness from the package manager, the permission or a stored preference. There is
 * exactly one instance per process, created by `AppContainer` and therefore recreated never —
 * only by process death (INV-ANKI-DET-05: availability is derived at runtime, never persisted).
 *
 * ## Refresh policy (§28/§29/§48)
 *
 * | Trigger | Call |
 * |---|---|
 * | app start / return to foreground | [onAppForeground] |
 * | user taps Refresh | [requestRefresh] |
 * | settings opened | [onAppForeground] |
 *
 * No polling loop, no periodic timer, no package-change receiver, no retry ladder. Health is
 * local and cheap to recompute, so it is recomputed when a human is looking, and stale states are
 * simply re-checked on the next meaningful event (§29: a foreground re-check is the simplest
 * robust option; a manifest-declared receiver for `PACKAGE_REPLACED` would buy a fraction of a
 * second in a case where the user is looking at the screen anyway).
 *
 * ## Concurrency (§45/§46/§47)
 *
 * - Checks are serialized ([refreshLock]): at most one provider probe is in flight per process.
 * - Fire-and-forget refreshes are *single-flight* ([requestRefresh]): while a check is running,
 *   further foreground/tap triggers coalesce into it instead of queueing a ladder of probes, so a
 *   startup burst (Activity recreation) or an impatient Retry tap cannot multiply provider calls.
 * - Results are published only when they are the newest request
 *   ([AnkiDroidHealthPublicationGuard]): a slow check can never overwrite a fast one.
 * - Cancellation propagates to callers, so a scope shutdown stops the work instead of leaking it.
 */
class AnkiDroidHealthRepository(
    private val check: AnkiDroidHealthCheck,
    private val scope: CoroutineScope,
    private val clock: AppClock,
    /** Foreground re-checks closer together than this are ignored (see [onAppForeground]). */
    private val minRefreshIntervalMs: Long = DEFAULT_MIN_REFRESH_INTERVAL_MS,
    private val publicationGuard: AnkiDroidHealthPublicationGuard = AnkiDroidHealthPublicationGuard()
) {

    private val _health = MutableStateFlow(AnkiDroidHealthSnapshot.checking(clock.nowMillis()))

    /** Full snapshot (state + facts + timing) for Settings and Diagnostics. */
    val health: StateFlow<AnkiDroidHealthSnapshot> = _health.asStateFlow()

    /**
     * Availability alone, as a `StateFlow` for consumers that only need "can we talk to it"
     * (§27) — for example the future backend selector. Same single source, projected, never
     * recomputed.
     */
    val availability: StateFlow<AnkiAvailability> = health
        .map { it.availability }
        .stateIn(scope, SharingStarted.Eagerly, AnkiAvailability.Checking)

    private val refreshLock = Mutex()

    /**
     * The currently running fire-and-forget check, if any (single-flight, §46). Guarded by its own
     * lock so two foreground events cannot both observe "nothing running" and launch two probes.
     * `refresh()` never takes this lock, so holding it across `scope.launch` cannot deadlock even
     * on a dispatcher that starts the coroutine inline.
     */
    private val inFlightLock = Any()
    private var inFlight: Job? = null

    /**
     * When the last check completed. Written by the app-scoped checks and read by foreground
     * debouncing, hence `@Volatile` (the value is advisory: worst case a redundant check runs,
     * which cannot produce a wrong state because old results cannot publish — §46).
     */
    @Volatile
    private var lastCheckAtMs: Long? = null

    /**
     * Foreground trigger (§30/§57): called from the Activity's `onStart`, so it also covers app
     * start and the "user opened AnkiDroid, finished setup, came back" flow without restarting
     * Study-Agent.
     *
     * Debounced: while a first result is still pending, or when the last check is younger than
     * [minRefreshIntervalMs], nothing new is started. This is deliberately generous — AnkiDroid
     * state only changes when the user changes it, and the common case (app resume) is already
     * covered by the previous check.
     */
    fun onAppForeground() {
        val last = lastCheckAtMs
        val nowMs = clock.nowMillis()
        val withinDebounce = last != null && (nowMs - last) < minRefreshIntervalMs
        if (withinDebounce && _health.value.availability != AnkiAvailability.Checking) return
        requestRefresh()
    }

    /**
     * Fire-and-forget refresh on the application scope; returns the job so callers can observe it.
     *
     * Single-flight: if a check is already running, that job is returned and no second check is
     * started. The running check was started at most one debounce window ago, so its result is the
     * answer the caller is waiting for; the alternative (queueing) would turn a burst of triggers
     * into a burst of provider calls for the same state.
     */
    fun requestRefresh(): Job = synchronized(inFlightLock) {
        val running = inFlight
        if (running != null && running.isActive) {
            running
        } else {
            val job = scope.launch { refresh() }
            inFlight = job
            job
        }
    }

    /**
     * Refresh and wait for the result (§102). Cancellation is never converted into a state: if the
     * caller (or the scope) is cancelled, the exception propagates (§45).
     */
    suspend fun refresh(): AnkiDroidHealthSnapshot {
        val requestId = publicationGuard.newRequest()

        val snapshot = try {
            refreshLock.withLock { check.check() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // The check guarantees it does not throw; this is the last line of defence before the
            // app, and it reports rather than crashes (INV-ANKI-DET-09).
            AnkiDroidHealthSnapshot(
                checkedAtEpochMs = clock.nowMillis(),
                durationMs = 0L,
                detection = faultDetection(throwable)
            )
        }

        lastCheckAtMs = snapshot.checkedAtEpochMs
        publicationGuard.publishIfCurrent(requestId) { _health.value = snapshot }
        return snapshot
    }

    private fun faultDetection(throwable: Throwable): AnkiDroidDetectionResult {
        val failure = AnkiDroidFailureClassifier.classify(
            throwable,
            AnkiDroidOperationStage.COLLECTION_PROBE
        )
        return AnkiDroidDetectionResult(
            installed = false,
            packageName = null,
            providerAvailable = false,
            endpointLabel = null,
            authority = null,
            checkedAuthorities = emptyList(),
            providerFacts = null,
            providerSpec = null,
            providerSpecKnown = false,
            permissionGranted = null,
            permissionProtectionLevel = null,
            collectionReady = null,
            availability = AnkiAvailability.Fault(AnkiError.Unknown(cause = failure.exceptionClass)),
            capabilities = AnkiCapabilities.NONE,
            failure = failure
        )
    }

    companion object {
        /**
         * Minimum interval between foreground-driven checks: 2 000 ms. Long enough to collapse
         * the resume/`onStart` burst and a quick Settings visit into one provider call, short
         * enough that a user who fixes AnkiDroid and comes straight back still sees the new state
         * immediately (§30).
         */
        const val DEFAULT_MIN_REFRESH_INTERVAL_MS: Long = 2_000L
    }
}
