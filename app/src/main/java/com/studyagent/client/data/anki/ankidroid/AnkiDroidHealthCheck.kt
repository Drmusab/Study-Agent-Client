package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.common.AppClock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * GATE 02 — one bounded, timed health check (§24/§25/§44/§101).
 *
 * The check adds exactly three things to a detection pass: a **time budget**, a **duration
 * measurement** and a **total-failure guarantee**. It contains no business logic — deck and card
 * questions belong to later gates.
 */
interface AnkiDroidHealthCheck {

    /**
     * Performs one check. Never throws for a failure of AnkiDroid or of the platform: a broken
     * provider produces a snapshot describing the breakage (§55/§90). Only *caller* cancellation
     * propagates, because callers own their coroutine's lifecycle (§45).
     */
    suspend fun check(): AnkiDroidHealthSnapshot
}

/**
 * @param timeoutMs upper bound on how long a single check may take before it is reported as a
 *  timeout. The default is [DEFAULT_TIMEOUT_MS]; see the constant's documentation for why it is
 *  not smaller.
 */
class DefaultAnkiDroidHealthCheck(
    private val detector: AnkiDroidDetector,
    private val clock: AppClock,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : AnkiDroidHealthCheck {

    override suspend fun check(): AnkiDroidHealthSnapshot {
        val startedAtMs = clock.nowMillis()

        val detection = try {
            withTimeout(timeoutMs) { detector.detect() }
        } catch (timeout: TimeoutCancellationException) {
            timeoutSnapshotDetection(timeoutMs)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Our caller was cancelled (screen gone, app shutting down, newer check superseded
            // this one). Cancellation is not a health state; it must propagate unchanged (§45).
            throw cancellation
        } catch (throwable: Throwable) {
            // A defect inside our own integration layer must not reach the app: the snapshot
            // reports it, the app keeps running (INV-ANKI-DET-09, §90).
            val failure = AnkiDroidFailureClassifier.classify(
                throwable,
                AnkiDroidOperationStage.COLLECTION_PROBE
            )
            AnkiDroidDetectionResult(
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

        val finishedAtMs = clock.nowMillis()
        return AnkiDroidHealthSnapshot(
            checkedAtEpochMs = finishedAtMs,
            durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L),
            detection = detection
        )
    }

    private fun timeoutSnapshotDetection(budgetMs: Long): AnkiDroidDetectionResult {
        val failure = AnkiDroidFailureClassifier.timedOut(AnkiDroidOperationStage.COLLECTION_PROBE)
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
            availability = AnkiAvailability.Fault(AnkiError.QueryFailure(causeCategory = "timeout")),
            capabilities = AnkiCapabilities.NONE,
            failure = failure
        )
    }

    companion object {
        /**
         * Upper bound for one check: **3 000 ms**.
         *
         * Rationale (§44 — "reasonable, bounded, not arbitrarily huge"): a *cold* AnkiDroid
         * provider call can be slow, because the provider opens the collection before it answers
         * any query, and a large collection on a slow device takes time to open. A 1-second
         * budget would therefore produce false timeouts on exactly the devices that need the most
         * patience, while anything much larger would stop being an upper bound at all. The check
         * runs on the application scope and never blocks a frame (§43/§89), so this budget
         * protects against a *hanging* provider, not against ordinary slowness.
         *
         * Known limitation: the budget bounds how long *we* wait, not the binder call itself —
         * a provider that never returns keeps its thread in AnkiDroid's process. The check is
         * still cancelled from our side, and the next check starts from a clean state.
         */
        const val DEFAULT_TIMEOUT_MS: Long = 3_000L
    }
}
