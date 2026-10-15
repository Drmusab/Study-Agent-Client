package com.studyagent.client.core.anki

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GATE 21E — the user's *preferred* backend policy for future, unbound work.
 *
 * This is **PreferredBackend** (one of the four distinct selection roles in
 * `BACKEND_SELECTION_POLICY.md` §1): it never becomes an active backend, a locked StudySession owner,
 * an editor binding, a creation binding, or a transaction destination (INV-21E-01, INV-21E-05).
 *
 * Two variants:
 *  - [Auto] — let the resolver pick deterministically from eligible candidates.
 *  - [Explicit] — one exact configured [AnkiBackendId]; resolution must refuse rather than substitute
 *    if that instance is missing, unavailable, or lacks required capabilities (INV-21E-08).
 *
 * A preference carries no credentials, no transport detail, and no display label. Identity stability
 * is a function of [AnkiBackendId.stableId] alone.
 */
@Serializable
sealed interface BackendPreference {

    /** Let the resolver choose deterministically. AUTO is a policy, not a backend instance. */
    @Serializable
    @SerialName("auto")
    data object Auto : BackendPreference

    /**
     * Pin one exact configured instance.
     *
     * The pinned id identifies a *configured instance*, not a kind or a display label. Two PC
     * profiles share a kind but are separate identities; an [Explicit] pin must name the exact
     * [AnkiBackendId.PcAgent.profileId] (BACKEND_SELECTION_POLICY §1.1, DECISION-21B-05).
     *
     * If the pinned instance is not currently registered, resolution fails with
     * [BackendResolutionFailure.EXPLICIT_BACKEND_MISSING]; if it is registered but unusable,
     * resolution fails with [BackendResolutionFailure.EXPLICIT_BACKEND_UNAVAILABLE] — it never
     * silently falls back to another instance of the same kind.
     */
    @Serializable
    @SerialName("explicit")
    data class Explicit(val backendId: AnkiBackendId) : BackendPreference

    /**
     * Legacy/migration: explicit *kind* preference without an instance pin.
     *
     * This exists only so that pre-pin storage (which recorded only `ANKIDROID_LOCAL` / `PC_AGENT`)
     * can be restored deterministically without fabricating an instance identity. Resolution applies
     * the locked N-rule (BACKEND_SELECTION_POLICY §1.1):
     *   N == 0 configured of that kind → ExplicitBackendKindUnavailable (not configured)
     *   N == 1 → that instance
     *   N >= 2 → Ambiguous (the user must pick an instance; policy must not choose for them)
     *
     * This variant is never produced by new preference writes: the UI is expected to set an
     * [Explicit] pin once an instance is chosen. AnkiDroid is always a singleton data object
     * ([AnkiBackendId.AnkiDroidLocal]), so an ANKIDROID_LOCAL legacy preference unambiguously maps
     * to `Explicit(AnkiDroidLocal)` at the store boundary; [ExplicitKind] is therefore only ever
     * observed for a legacy `PC_AGENT` selection with no pin.
     */
    @Serializable
    @SerialName("explicit_kind")
    data class ExplicitKind(val kind: AnkiBackendKind) : BackendPreference {
        init {
            require(kind != AnkiBackendKind.FAKE) { "FAKE is not a user-selectable kind" }
        }
    }

    /**
     * A persisted value was present but could not be interpreted safely. This is deliberately a
     * typed, content-free state rather than AUTO or an unpinned kind: malformed storage must not
     * silently become a different backend choice. The raw token is never retained here because an
     * invalid string could contain data that is not safe to expose in diagnostics.
     *
     * This value is produced only while decoding persisted settings. The data-layer preference
     * repository refuses to write it; the user must choose a valid preference to clear the error.
     */
    @Serializable
    @SerialName("invalid_stored")
    data class InvalidStored(
        val issue: BackendPreferenceInvalidIssue,
        /** Legacy mode projection only; never used by the resolver. */
        val legacyMode: AnkiBackendMode? = null
    ) : BackendPreference
}

/** Safe, stable reasons for a stored preference that cannot be decoded without guessing. */
@Serializable
enum class BackendPreferenceInvalidIssue {
    UNKNOWN_MODE,
    INVALID_INSTANCE_PIN,
    INSTANCE_PIN_KIND_MISMATCH
}

/**
 * Convert the legacy [AnkiBackendMode] (kind-only) to a [BackendPreference].
 *
 * AnkiDroid-local always maps to the unambiguous [BackendPreference.Explicit] singleton because
 * [AnkiBackendId.AnkiDroidLocal] has exactly one configured instance by construction.
 * `PC_AGENT` maps to [BackendPreference.ExplicitKind] — resolution applies the N-rule.
 */
fun AnkiBackendMode.toPreference(): BackendPreference = when (this) {
    AnkiBackendMode.AUTO -> BackendPreference.Auto
    AnkiBackendMode.ANKIDROID_LOCAL -> BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal)
    AnkiBackendMode.PC_AGENT -> BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
}

/**
 * Content-free reason why a resolver chose a backend — diagnostics only, never a behaviour switch.
 */
enum class BackendResolutionReason {
    /** The user pinned this backend and it is eligible. */
    EXPLICIT_PIN,
    /** AUTO: the on-device collection is preferred over a remote transport. */
    AUTO_LOCAL_PREFERRED,
    /** AUTO: the local backend was not eligible; one ready remote instance remained. */
    AUTO_SINGLE_REMOTE,
    /** Exactly one backend matched the policy, so there was nothing to choose between. */
    ONLY_ELIGIBLE_CANDIDATE
}
