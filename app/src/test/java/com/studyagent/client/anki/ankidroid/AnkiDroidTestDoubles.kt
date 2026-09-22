package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDetectionResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDetector
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoint
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoints
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailure
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureClassifier
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthCheck
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidOperationStage
import com.studyagent.client.data.anki.ankidroid.AnkiDroidPermissionManager
import com.studyagent.client.data.anki.ankidroid.AnkiDroidPermissionState
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProbe
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProbeOutcome
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderSpec
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderSpecSource
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderFacts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * GATE 02 test doubles (GATE 01 §16.1 asks for counterpart fakes; this is the AnkiDroid one).
 *
 * These fakes implement *our* platform seam, never AnkiDroid itself (§65: Study-Agent tests its
 * contract with the provider, not AnkiDroid's scheduler). They are programmable enough to
 * reproduce every availability state, every failure class, latency, hangs and concurrent calls —
 * which is what makes the detector, the check and the repository testable without a device.
 */
class FakeAnkiDroidProbe(
    private val endpoints: List<AnkiDroidEndpoint> = AnkiDroidEndpoints.forBuild(includeDebugEndpoints = false)
) : AnkiDroidProbe {

    /** Authority → facts. An entry that exists means "this authority resolves". */
    val factsByAuthority: MutableMap<String, AnkiDroidProviderFacts> = linkedMapOf()

    /** Packages `isPackageInstalled` reports as visible. */
    val installedPackages: MutableSet<String> = linkedSetOf()

    /** Outcomes returned by consecutive probes; when empty, [defaultOutcome] is used. */
    val probeOutcomes: ArrayDeque<AnkiDroidProbeOutcome> = ArrayDeque()

    /** Outcome used when [probeOutcomes] is empty: a successful one-row read. */
    var defaultOutcome: AnkiDroidProbeOutcome =
        AnkiDroidProbeOutcome.Reached(selectedDeckRowPresent = true)

    var providerFactsThrowable: Throwable? = null
    var probeThrowable: Throwable? = null

    /** Virtual-time latency applied inside a probe. */
    var probeDelayMs: Long = 0L

    /** When set, a probe blocks until the gate completes (used for concurrency tests). */
    var probeGate: CompletableDeferred<Unit>? = null

    val providerFactsAuthorities: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val packageLookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

    var probeCalls: Int = 0
        private set

    var maxConcurrentProbes: Int = 0
        private set

    private var activeProbes = 0
    private val concurrencyLock = Mutex()

    override suspend fun providerFacts(endpoint: AnkiDroidEndpoint): AnkiDroidProviderFacts? {
        providerFactsAuthorities += endpoint.authority
        providerFactsThrowable?.let { throw it }
        return factsByAuthority[endpoint.authority]
    }

    override suspend fun isPackageInstalled(packageName: String): Boolean {
        packageLookups += packageName
        return installedPackages.contains(packageName)
    }

    override suspend fun probeCollection(endpoint: AnkiDroidEndpoint): AnkiDroidProbeOutcome {
        concurrencyLock.withLock {
            probeCalls += 1
            activeProbes += 1
            if (activeProbes > maxConcurrentProbes) maxConcurrentProbes = activeProbes
        }
        try {
            if (probeDelayMs > 0L) delay(probeDelayMs)
            probeGate?.await()
            probeThrowable?.let { throw it }
            return probeOutcomes.removeFirstOrNull() ?: defaultOutcome
        } finally {
            concurrencyLock.withLock { activeProbes -= 1 }
        }
    }

    /** Configure this fake as "AnkiDroid present and healthy" at [endpoint]. */
    fun resolves(
        endpoint: AnkiDroidEndpoint = endpoints.first(),
        providerPackage: String? = endpoint.expectedPackage,
        enabled: Boolean = true,
        spec: Int? = 2
    ): FakeAnkiDroidProbe {
        factsByAuthority[endpoint.authority] = AnkiDroidProviderFacts(
            endpointLabel = endpoint.label,
            authority = endpoint.authority,
            providerPackage = providerPackage,
            packageMatchesExpected = providerPackage == endpoint.expectedPackage,
            enabled = enabled,
            providerSpec = spec ?: AnkiDroidProviderSpec.IMPLICIT_WHEN_METADATA_ABSENT,
            providerSpecSource = if (spec == null) {
                AnkiDroidProviderSpecSource.IMPLICIT_FALLBACK
            } else {
                AnkiDroidProviderSpecSource.METADATA
            }
        )
        installedPackages += endpoint.expectedPackage
        return this
    }

    /** Configure the probe to answer with a failure produced by the real classifier. */
    fun failWith(
        throwable: Throwable,
        stage: AnkiDroidOperationStage = AnkiDroidOperationStage.COLLECTION_PROBE
    ): FakeAnkiDroidProbe {
        defaultOutcome = AnkiDroidProbeOutcome.Failed(
            AnkiDroidFailureClassifier.classify(throwable, stage)
        )
        return this
    }
}

/** Programmable permission double: the real component's contract, none of Android's. */
class FakeAnkiDroidPermissionManager(
    var granted: Boolean = true,
    var protectionLevel: String? = "dangerous",
    var throwable: Throwable? = null
) : AnkiDroidPermissionManager {

    var calls: Int = 0
        private set

    override suspend fun state(endpoint: AnkiDroidEndpoint): AnkiDroidPermissionState {
        calls += 1
        throwable?.let { throw it }
        return AnkiDroidPermissionState(
            permission = endpoint.readWritePermission,
            granted = granted,
            protectionLevel = protectionLevel,
            declaredByInstalledPackage = protectionLevel != null
        )
    }
}

/**
 * Detector double for health-check/repository tests: per-call results, latency, gates and
 * concurrency accounting, so "one check at a time" and "a stale result never publishes" are
 * observable facts rather than assumptions.
 */
class FakeAnkiDroidDetector(
    var result: AnkiDroidDetectionResult = testDetection(AnkiAvailability.Ready(AnkiCapabilities.NONE)),
    var throwable: Throwable? = null,
    var delayMs: Long = 0L
) : AnkiDroidDetector {

    /** Per-call results (1-based call index); falls back to [result]. */
    val resultsByCall: MutableMap<Int, AnkiDroidDetectionResult> = linkedMapOf()

    /** Per-call gates: a call blocks until its gate completes. */
    val gatesByCall: MutableMap<Int, CompletableDeferred<Unit>> = linkedMapOf()

    /** Runs when a detection starts, before delay/gates — lets a test order completions. */
    var onDetect: (suspend (Int) -> Unit)? = null

    var calls: Int = 0
        private set

    var maxConcurrentDetects: Int = 0
        private set

    private var active = 0
    private val lock = Mutex()

    override suspend fun detect(): AnkiDroidDetectionResult {
        val call = lock.withLock {
            calls += 1
            active += 1
            if (active > maxConcurrentDetects) maxConcurrentDetects = active
            calls
        }
        try {
            onDetect?.invoke(call)
            if (delayMs > 0L) delay(delayMs)
            gatesByCall[call]?.await()
            throwable?.let { throw it }
            return resultsByCall[call] ?: result
        } finally {
            lock.withLock { active -= 1 }
        }
    }
}

/** Health-check double with the same instrumentation, plus a throw escape hatch. */
class FakeAnkiDroidHealthCheck(
    var snapshot: AnkiDroidHealthSnapshot = testSnapshot(
        testDetection(AnkiAvailability.Ready(AnkiCapabilities.NONE))
    ),
    var throwable: Throwable? = null,
    var delayMs: Long = 0L
) : AnkiDroidHealthCheck {

    val snapshotsByCall: MutableMap<Int, AnkiDroidHealthSnapshot> = linkedMapOf()
    val gatesByCall: MutableMap<Int, CompletableDeferred<Unit>> = linkedMapOf()

    var calls: Int = 0
        private set

    var maxConcurrentChecks: Int = 0
        private set

    private var active = 0
    private val lock = Mutex()

    override suspend fun check(): AnkiDroidHealthSnapshot {
        val call = lock.withLock {
            calls += 1
            active += 1
            if (active > maxConcurrentChecks) maxConcurrentChecks = active
            calls
        }
        try {
            if (delayMs > 0L) delay(delayMs)
            gatesByCall[call]?.await()
            throwable?.let { throw it }
            return snapshotsByCall[call] ?: snapshot
        } finally {
            lock.withLock { active -= 1 }
        }
    }
}

/** Minimal, explicit detection result for tests that only care about a few fields. */
fun testDetection(
    availability: AnkiAvailability,
    installed: Boolean = true,
    packageName: String? = AnkiDroidApiContract.RELEASE_PACKAGE,
    providerAvailable: Boolean = true,
    endpointLabel: String? = "release",
    authority: String? = AnkiDroidApiContract.RELEASE_AUTHORITY,
    providerSpec: Int? = 2,
    providerSpecKnown: Boolean = true,
    permissionGranted: Boolean? = true,
    collectionReady: Boolean? = null,
    capabilities: AnkiCapabilities = AnkiCapabilities.NONE,
    failure: AnkiDroidFailure? = null
): AnkiDroidDetectionResult = AnkiDroidDetectionResult(
    installed = installed,
    packageName = packageName,
    providerAvailable = providerAvailable,
    endpointLabel = endpointLabel,
    authority = authority,
    checkedAuthorities = listOfNotNull(authority),
    providerFacts = null,
    providerSpec = providerSpec,
    providerSpecKnown = providerSpecKnown,
    permissionGranted = permissionGranted,
    permissionProtectionLevel = if (permissionGranted == null) null else "dangerous",
    collectionReady = collectionReady,
    availability = availability,
    capabilities = capabilities,
    failure = failure
)

fun testSnapshot(
    detection: AnkiDroidDetectionResult,
    checkedAtEpochMs: Long = 1_700_000_000_000L,
    durationMs: Long = 12L
): AnkiDroidHealthSnapshot = AnkiDroidHealthSnapshot(
    checkedAtEpochMs = checkedAtEpochMs,
    durationMs = durationMs,
    detection = detection
)

fun testFailure(
    category: AnkiDroidFailureCategory,
    evidence: AnkiDroidFailureEvidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
    exceptionClass: String? = null,
    evidenceToken: String? = null
): AnkiDroidFailure = AnkiDroidFailure(
    category = category,
    evidence = evidence,
    exceptionClass = exceptionClass,
    evidenceToken = evidenceToken
)
