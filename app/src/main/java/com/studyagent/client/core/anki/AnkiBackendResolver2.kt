package com.studyagent.client.core.anki

/**
 * GATE 21E — the single, pure, deterministic pre-binding selection policy.
 *
 * Answers "which configured backend instance owns this new unbound operation?" with exactly one
 * [AnkiBackendId] or a typed [BackendResolutionResult2.Failed] (BACKEND_SELECTION_POLICY §3, §5.1).
 *
 * ## Purity
 *
 * Resolution is a pure function of (request, descriptors). It never performs I/O, never probes or
 * refreshes a backend, never writes a preference, never mutates registry state, and never consults
 * an active StudySession, bound editor, bound creation, or durable transaction (INV-21E-15).
 *
 * ## Determinism
 *
 * Identical (request, descriptors) always produce an identical result, independent of registry
 * iteration order, object identity, hash ordering, thread timing, or probe arrival order
 * (INV-21E-07). Ties are broken by [AnkiBackendDescriptor.orderingKey]
 * (`kind.ordinal + ":" + stableId`, lexicographic ascending) — total, stable, identity-sorted.
 *
 * ## AUTO algorithm (BACKEND_SELECTION_POLICY §3.3)
 *
 *  1. mode-eligibility filter (rejects Fake for real preferences, accepts only the pinned id for
 *     [BackendPreference.Explicit]);
 *  2. readiness gate R2: availability must be [AnkiAvailability.Ready];
 *  3. capability gate R3: descriptor must satisfy every required capability in both projections;
 *  4. locality preference: [AnkiBackendLocality.LOCAL_DEVICE] before [AnkiBackendLocality.REMOTE_AGENT];
 *     [AnkiBackendLocality.TEST_DOUBLE] is never selected by AUTO;
 *  5. ambiguity refusal: ≥2 equally eligible remotes ⇒ [BackendResolutionResult2.Ambiguous];
 *  6. final tie-break by `orderingKey` within each locality class.
 *
 * ## Explicit pin semantics (BACKEND_SELECTION_POLICY §4)
 *
 * A pinned backend that is not registered ⇒ [BackendResolutionResult2.ExplicitBackendMissing].
 * A pinned backend registered but not reachable ⇒ [BackendResolutionResult2.ExplicitBackendUnavailable]
 * with its own typed reason (never a sibling of the same kind).
 * A pinned backend ready but missing a capability ⇒ [BackendResolutionResult2.CapabilityUnsupported].
 *
 * No silent substitution ever happens (INV-21E-08).
 */
object AnkiBackendResolver2 {

    /**
     * Resolve against a list of descriptors. Deterministic and total.
     */
    fun resolve(
        request: BackendResolutionRequest2,
        descriptors: List<AnkiBackendDescriptor>
    ): BackendResolutionResult2 {
        val sortedDescriptors = descriptors.sortedBy { it.orderingKey }

        return when (val pref = request.preference) {
            is BackendPreference.Auto -> resolveAuto(request, sortedDescriptors)
            is BackendPreference.Explicit -> resolveExplicit(pref, request, sortedDescriptors)
            is BackendPreference.ExplicitKind -> resolveExplicitKind(pref, request, sortedDescriptors)
        }
    }

    // --------------------------------------------------------------- Explicit kind (legacy / no pin)

    private fun resolveExplicitKind(
        pref: BackendPreference.ExplicitKind,
        request: BackendResolutionRequest2,
        descriptors: List<AnkiBackendDescriptor>
    ): BackendResolutionResult2 {
        val requestedKind = pref.kind
        if (requestedKind == AnkiBackendKind.FAKE) {
            return BackendResolutionResult2.ConfigurationInvalid(
                detail = "fake_not_user_selectable"
            )
        }

        val eligible = descriptors.filter { it.kind == requestedKind }
        val considered = eligible.map { it.id }.toSet()

        if (eligible.isEmpty()) {
            // N == 0: no instance of the requested kind is configured.
            return BackendResolutionResult2.NoBackendConfigured(considered = considered)
        }
        if (eligible.size >= 2) {
            // N >= 2: ambiguous. Two profiles are two collections; policy must not pick.
            return BackendResolutionResult2.Ambiguous(
                candidates = eligible.map { it.id }.toSet(),
                considered = considered
            )
        }

        // N == 1: delegate to the explicit-instance logic so readiness + capability semantics are
        // identical between pinned and unambiguous-kind selection (one policy, two entry points).
        val single = eligible.single()
        return resolveExplicit(
            BackendPreference.Explicit(single.id),
            request,
            listOf(single)
        )
    }

    // --------------------------------------------------------------- AUTO

    private fun resolveAuto(
        request: BackendResolutionRequest2,
        descriptors: List<AnkiBackendDescriptor>
    ): BackendResolutionResult2 {
        // Step 0 — mode filter: AUTO accepts AnkiDroidLocal + PcAgent, never Fake.
        val candidates = descriptors.filter { d -> d.id.kind != AnkiBackendKind.FAKE }
        val considered = candidates.map { it.id }.toSet()

        if (candidates.isEmpty()) {
            return BackendResolutionResult2.NoBackendConfigured(considered = emptySet())
        }

        // Step 1 — readiness gate (R2)
        val ready = candidates.filter { it.isReachable }
        if (ready.isEmpty()) {
            return noEligible(candidates, considered, request)
        }

        // Step 2 — capability gate (R3), fail-closed (both projections must agree)
        val capable = ready.filter { it.supportsAll(request.requiredCapabilities) }
        if (capable.isEmpty()) {
            return BackendResolutionResult2.CapabilityUnsupported(
                required = request.requiredCapabilities,
                missing = ready.associate { d -> d.id to d.missingCapabilities(request.requiredCapabilities) },
                considered = considered
            )
        }

        // Step 3 — locality: LOCAL_DEVICE first. Reason reflects AUTO's local-first policy even when
        // only one eligible candidate exists (mirrors the pre-existing GATE-21 resolver's reasons so
        // diagnostics stay stable).
        val local = capable.filter { it.locality == AnkiBackendLocality.LOCAL_DEVICE }
        if (local.isNotEmpty()) {
            val chosen = local.minBy { it.orderingKey }
            return BackendResolutionResult2.Resolved(
                backendId = chosen.id,
                reason = BackendResolutionReason.AUTO_LOCAL_PREFERRED
            )
        }

        // Step 4 — remotes: single → resolved, ≥2 → Ambiguous, 0 → only TEST_DOUBLE (refuse).
        val remote = capable.filter { it.locality == AnkiBackendLocality.REMOTE_AGENT }
        return when {
            remote.size == 1 -> BackendResolutionResult2.Resolved(
                backendId = remote.single().id,
                reason = BackendResolutionReason.AUTO_SINGLE_REMOTE
            )
            remote.isEmpty() -> BackendResolutionResult2.NoEligibleBackend(
                considered = considered,
                consideredReasons = candidates.associate { d -> d.id to reasonFor(d) }
                    .filterValues { it != null }.mapValues { it.value!! },
                error = AnkiError.BackendUnavailable("no_real_backend_eligible")
            )
            else -> BackendResolutionResult2.Ambiguous(
                candidates = remote.map { it.id }.toSet(),
                considered = considered
            )
        }
    }

    // --------------------------------------------------------------- Explicit pin

    private fun resolveExplicit(
        pref: BackendPreference.Explicit,
        request: BackendResolutionRequest2,
        descriptors: List<AnkiBackendDescriptor>
    ): BackendResolutionResult2 {
        val pinnedId = pref.backendId

        // Explicit pin must name an instance of an acceptable kind — Fake is never acceptable in
        // production resolution.
        if (pinnedId.kind == AnkiBackendKind.FAKE) {
            return BackendResolutionResult2.ConfigurationInvalid(
                detail = "explicit_fake_not_permitted",
                considered = setOf(pinnedId)
            )
        }

        val candidate = descriptors.firstOrNull { it.id == pinnedId }
        val considered = setOf(pinnedId)

        // Pin names an instance that is not currently registered.
        if (candidate == null) {
            return BackendResolutionResult2.ExplicitBackendMissing(pinnedId = pinnedId, considered = considered)
        }

        // Ready gate (R2). A pinned instance that is not reachable fails with its own reason — no sibling.
        if (!candidate.isReachable) {
            val reason = reasonFor(candidate)
                ?: AnkiBackendUnavailableReason.BACKEND_ERROR
            return BackendResolutionResult2.ExplicitBackendUnavailable(
                pinnedId = pinnedId,
                reason = reason,
                considered = considered,
                error = errorFor(candidate)
            )
        }

        // Capability gate (R3).
        val missing = candidate.missingCapabilities(request.requiredCapabilities)
        if (missing.isNotEmpty()) {
            return BackendResolutionResult2.CapabilityUnsupported(
                required = request.requiredCapabilities,
                missing = mapOf(pinnedId to missing),
                considered = considered
            )
        }

        return BackendResolutionResult2.Resolved(
            backendId = pinnedId,
            reason = BackendResolutionReason.EXPLICIT_PIN
        )
    }

    // --------------------------------------------------------------- helpers

    private fun noEligible(
        candidates: List<AnkiBackendDescriptor>,
        considered: Set<AnkiBackendId>,
        request: BackendResolutionRequest2
    ): BackendResolutionResult2 {
        val reasons = candidates.associate { d -> d.id to reasonFor(d) }.filterValues { it != null }
            .mapValues { it.value!! }
        // If there is exactly one candidate, surface its typed error verbatim.
        val single = candidates.singleOrNull()
        val error = if (single != null) {
            errorFor(single)
        } else {
            AnkiError.BackendUnavailable(
                request.requiredCapabilities.firstOrNull()?.name?.lowercase() ?: "backend_unavailable"
            )
        }
        return BackendResolutionResult2.NoEligibleBackend(
            considered = considered,
            consideredReasons = reasons,
            error = error
        )
    }

    private fun reasonFor(d: AnkiBackendDescriptor): AnkiBackendUnavailableReason? =
        d.availability.unavailableReason()

    private fun errorFor(d: AnkiBackendDescriptor): AnkiError =
        d.availability.unavailabilityError() ?: AnkiError.BackendUnavailable()
}
