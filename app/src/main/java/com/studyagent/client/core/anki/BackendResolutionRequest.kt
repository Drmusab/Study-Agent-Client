package com.studyagent.client.core.anki

/**
 * GATE 21E — everything the resolver needs to answer one new, unbound operation.
 *
 * The request is immutable and value-based: resolution must be a pure function of
 * (preference, requiredCapabilities, descriptors). It never reads global preference state, probes a
 * backend, or consults a bound session context (INV-21E-06).
 *
 * @param preference the captured user policy (AUTO or an explicit instance pin) for this attempt.
 * @param requiredCapabilities every capability the chosen backend must provide; empty means any ready
 *     backend is eligible. `STUDY_SESSION_REQUIREMENTS` and other standard sets live in
 *     [AnkiBackendCapabilityRequirement].
 * @param purpose content-free diagnostics label (e.g. `"study_start"`, `"library"`, `"add_note_seed"`).
 */
data class BackendResolutionRequest2(
    val preference: BackendPreference,
    val requiredCapabilities: Set<AnkiRequiredCapability> = emptySet(),
    val purpose: String? = null
) {
    init {
        require(purpose == null || purpose.isNotBlank()) { "purpose must be non-blank when present" }
    }
}
