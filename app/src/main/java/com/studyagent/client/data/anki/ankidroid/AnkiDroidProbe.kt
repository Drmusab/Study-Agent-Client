package com.studyagent.client.data.anki.ankidroid

/**
 * GATE 02 — the single seam between Study-Agent's AnkiDroid logic and Android.
 *
 * Everything above this interface (detector, health check, repository, UI) is platform-free and
 * runs in JVM tests with a fake; the one Android implementation
 * ([AndroidAnkiDroidProbe]) is the *only* place in the app that calls `PackageManager` or
 * `ContentResolver` for AnkiDroid (GATE 02 §41/§64/§103, INV-ANKI-DET-06).
 *
 * Implementations must never throw for expected conditions: "the authority does not resolve" is
 * `null`, "the provider refused/errored" is a classified [AnkiDroidProbeOutcome.Failed]. Throwing
 * is reserved for genuinely surprising platform errors, which the detector still catches and
 * classifies rather than letting them escape (§20/§55).
 */
interface AnkiDroidProbe {

    /**
     * Resolve [endpoint]'s provider, returning platform facts, or `null` when the authority does
     * not resolve at all.
     *
     * Must not perform a provider *read*: this is discovery only (name, package, enabled flag,
     * published spec metadata). Implementations run on `Dispatchers.IO` (§43).
     */
    suspend fun providerFacts(endpoint: AnkiDroidEndpoint): AnkiDroidProviderFacts?

    /** Whether [packageName] is installed *and visible to this app* (see the manifest `<queries>`). */
    suspend fun isPackageInstalled(packageName: String): Boolean

    /**
     * The bounded, non-mutating read probe (GATE 02 §22/§23).
     *
     * Contract: read-only, at most one row, no card/deck content retained. On AnkiDroid's
     * provider the cheapest such read is the `selected_deck` URI, which answers with exactly one
     * row. Implementations must not add, rate, edit, delete or insert anything — a health check
     * that mutates the user's collection is a defect, not a trade-off (INV-ANKI-DET-07).
     */
    suspend fun probeCollection(endpoint: AnkiDroidEndpoint): AnkiDroidProbeOutcome
}
