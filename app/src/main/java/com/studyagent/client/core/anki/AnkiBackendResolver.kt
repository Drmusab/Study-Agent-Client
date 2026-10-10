package com.studyagent.client.core.anki

/**
 * GATE 21 — the typed answer to "which backend owns this operation?" (DECISION-21-25).
 *
 * Resolution is **never** nullable: a caller must be able to tell *why* there is no backend, because
 * the honest UI answer differs completely between "nothing is registered", "the one you asked for is
 * down", and "it is up but cannot do this" (DECISION-21-26, VER-21-03). Every failure variant carries
 * the mode-eligible identities it considered ([considered]) and a typed [error], so a screen can show
 * a reason instead of guessing.
 *
 * Resolution is a **pre-binding** decision only (DECISION-21-07/INV-21-09): once a StudySession,
 * editor, creation draft or durable transaction exists, its backend comes from that binding and never
 * from a fresh resolution.
 */
sealed interface BackendResolutionResult {

    /** One backend was chosen. [reason] says why, for diagnostics (DECISION-21-35). */
    data class Resolved(
        val backendId: AnkiBackendId,
        val reason: BackendSelectionReason
    ) : BackendResolutionResult

    /** Nothing matched the requested mode at all (not registered / not implemented). */
    data class NoBackendAvailable(
        override val considered: Set<AnkiBackendId>,
        override val error: AnkiError
    ) : BackendResolutionResult.Failure

    /** The requested mode has a backend, but it is not usable right now. */
    data class PreferredBackendUnavailable(
        val preference: AnkiBackendMode,
        override val considered: Set<AnkiBackendId>,
        override val error: AnkiError
    ) : BackendResolutionResult.Failure

    /**
     * Candidates exist and are reachable, but none offers every required capability
     * (DECISION-21-10). [missing] is per candidate so diagnostics can say exactly what is absent —
     * never a backend-name check in the UI (AUDIT-21-13).
     */
    data class RequiredCapabilityUnavailable(
        val required: Set<AnkiRequiredCapability>,
        val missing: Map<AnkiBackendId, Set<AnkiRequiredCapability>>,
        override val considered: Set<AnkiBackendId>,
        override val error: AnkiError
    ) : BackendResolutionResult.Failure

    /**
     * More than one equally eligible backend and choosing between them is a *user* decision, not a
     * policy one: two PC profiles are two different collections, and picking one silently would be a
     * lie about which collection is about to be mutated. Deterministic in both directions.
     */
    data class Ambiguous(
        val candidates: Set<AnkiBackendId>,
        override val considered: Set<AnkiBackendId>,
        override val error: AnkiError
    ) : BackendResolutionResult.Failure

    /** The request itself is malformed (e.g. an unknown mode token from storage). */
    data class ConfigurationInvalid(
        val detail: String,
        override val considered: Set<AnkiBackendId> = emptySet(),
        override val error: AnkiError = AnkiError.InvalidRequest(detail)
    ) : BackendResolutionResult.Failure

    interface Failure : BackendResolutionResult {
        /** Mode-eligible identities, whether or not they were usable. Empty = nothing is registered. */
        val considered: Set<AnkiBackendId>
        val error: AnkiError
    }
}

/** Why a backend was chosen. Diagnostics only; never a behaviour switch. */
enum class BackendSelectionReason {
    /** The user pinned this backend and it is usable. */
    EXPLICIT_PREFERENCE,
    /** AUTO: the on-device collection is preferred over a remote transport (DECISION-21-08). */
    AUTO_LOCAL_PREFERRED,
    /** AUTO: the local backend was not eligible, one ready PC profile remained. */
    AUTO_SINGLE_REMOTE,
    /** Exactly one backend matched the mode, so there was nothing to choose between. */
    ONLY_CANDIDATE
}

/** What a caller wants resolved. Pure data — resolution must not read anything else. */
data class BackendResolutionRequest(
    val preference: AnkiBackendMode,
    val requiredCapabilities: Set<AnkiRequiredCapability> = emptySet(),
    /** Content-free label for diagnostics ("library", "study_start", "edit_note", …). */
    val purpose: String? = null
) {
    init { require(purpose == null || purpose.isNotBlank()) }
}

/**
 * GATE 21 — the single pre-binding routing policy (IMPLEMENTATION-21-06, DECISION-21-24).
 *
 * It is a **pure function of its inputs**: the same (preference, requirements, descriptors) always
 * resolves the same backend (DECISION-21-09, VER-21-26). There is no probe, no refresh, no "first
 * backend to answer wins", and no preference write — a resolver that mutated state would make AUTO
 * depend on call order.
 *
 * The registry answers *existence and state*; this class answers *routing*. They stay separate so a
 * future second policy cannot quietly become a second registry (AUDIT-21-14).
 */
class AnkiBackendResolver(private val registry: AnkiBackendRegistry) {

    fun resolve(request: BackendResolutionRequest): BackendResolutionResult =
        resolve(request, registry.descriptors())

    companion object {

        /**
         * The locked AUTO ordering (DECISION-21-08):
         *
         * 1. honour the explicit mode — a pinned backend is never substituted (DECISION-21-26);
         * 2. drop anything not reachable right now;
         * 3. drop anything missing a required capability (DECISION-21-10);
         * 4. prefer the on-device collection: it has no transport that can vanish mid-review;
         * 5. otherwise a *single* ready PC profile; several are a user decision → [Ambiguous].
         *
         * Ties inside a rank are ordered by [AnkiBackendDescriptor.orderingKey] (kind, then stable
         * identity) so the result never depends on registry list order or timing.
         */
        fun resolve(
            request: BackendResolutionRequest,
            descriptors: List<AnkiBackendDescriptor>
        ): BackendResolutionResult {
            val eligible = descriptors
                .filter { request.preference.accepts(it.id) }
                .sortedBy { it.orderingKey }
            val considered = eligible.map { it.id }.toSet()

            if (eligible.isEmpty()) {
                return BackendResolutionResult.NoBackendAvailable(
                    considered = considered,
                    error = AnkiError.BackendUnavailable()
                )
            }

            val reachable = eligible.filter { it.isReachable }
            if (reachable.isEmpty()) {
                return unavailable(request, eligible, considered)
            }

            val capable = reachable.filter { it.missingCapabilities(request.requiredCapabilities).isEmpty() }
            if (capable.isEmpty()) {
                return BackendResolutionResult.RequiredCapabilityUnavailable(
                    required = request.requiredCapabilities,
                    missing = reachable.associate { it.id to it.missingCapabilities(request.requiredCapabilities) },
                    considered = considered,
                    error = AnkiError.UnsupportedAction(
                        request.requiredCapabilities.firstOrNull()?.name?.lowercase() ?: "backend_api"
                    )
                )
            }

            // Step 4 — the on-device collection wins over any remote transport, deterministically.
            val local = capable.filter { it.locality == AnkiBackendLocality.LOCAL_DEVICE }
            if (local.isNotEmpty()) {
                val chosen = local.minBy { it.orderingKey }
                return BackendResolutionResult.Resolved(chosen.id, reason(request, chosen, capable))
            }

            // Step 5 — one ready remote profile is a decision; several are the user's to make.
            val remote = capable.filter { it.locality == AnkiBackendLocality.REMOTE_AGENT }
            return when (remote.size) {
                1 -> BackendResolutionResult.Resolved(
                    remote.single().id,
                    reason(request, remote.single(), capable)
                )
                0 -> BackendResolutionResult.Resolved(
                    // Only test doubles remain. A real [AnkiBackendMode] never accepts one
                    // (see AnkiBackendMode.accepts), so this branch is reachable only from a test
                    // mode — and it is still deterministic rather than list-order dependent.
                    capable.minBy { it.orderingKey }.id,
                    BackendSelectionReason.ONLY_CANDIDATE
                )
                else -> BackendResolutionResult.Ambiguous(
                    candidates = remote.map { it.id }.toSet(),
                    considered = considered,
                    error = AnkiError.InvalidRequest("ambiguous_backend")
                )
            }
        }

        private fun reason(
            request: BackendResolutionRequest,
            chosen: AnkiBackendDescriptor,
            capable: List<AnkiBackendDescriptor>
        ): BackendSelectionReason = when {
            // An explicit mode with one usable backend did not really "choose": say so.
            request.preference != AnkiBackendMode.AUTO && capable.size == 1 ->
                BackendSelectionReason.ONLY_CANDIDATE
            request.preference != AnkiBackendMode.AUTO -> BackendSelectionReason.EXPLICIT_PREFERENCE
            chosen.locality == AnkiBackendLocality.LOCAL_DEVICE -> BackendSelectionReason.AUTO_LOCAL_PREFERRED
            else -> BackendSelectionReason.AUTO_SINGLE_REMOTE
        }

        private fun unavailable(
            request: BackendResolutionRequest,
            eligible: List<AnkiBackendDescriptor>,
            considered: Set<AnkiBackendId>
        ): BackendResolutionResult.Failure {
            // Exactly one candidate: surface *its* typed reason (permission, provider, collection…).
            // Several: no single reason is honest, so report the capability/operation instead.
            val error = eligible.singleOrNull()?.availability?.unavailabilityError()
                ?: AnkiError.UnsupportedAction(
                    request.requiredCapabilities.firstOrNull()?.name?.lowercase() ?: "backend_api"
                )
            return BackendResolutionResult.PreferredBackendUnavailable(
                preference = request.preference,
                considered = considered,
                error = error
            )
        }
    }
}
