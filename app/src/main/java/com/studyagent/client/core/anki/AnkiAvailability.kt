package com.studyagent.client.core.anki

/**
 * GATE 01 contract — unified availability of an Anki backend (§17, §71).
 * Amended by GATE 02: [Checking] and [ProviderUnavailable] were added because
 * the runtime detector needs to express "not resolved yet" and "the app is
 * present but its integration provider is not" without inventing a second,
 * parallel state hierarchy (GATE 02 §13/§94, architecture contract §8.4's
 * "health is plural" rule).
 *
 * One domain representation for two very different physical situations:
 *
 *  - AnkiDroid-local: [NotInstalled], [ProviderUnavailable],
 *    [PermissionRequired], [CollectionNotInitialized],
 *    [TemporarilyUnavailable], [Ready].
 *  - PC path: [AgentDisconnected] (no PC agent), [AgentAnkiUnavailable]
 *    (agent up, Anki beneath it down), [Ready].
 *
 * UI renders from this state plus [AnkiCapabilities]; it never re-derives
 * "available" from connection state or package names itself. `ready` is NOT
 * the absence of an error — it is the positive knowledge that review work can
 * start.
 */
sealed interface AnkiAvailability {

    /**
     * The availability probe has not produced a result yet (app start, first
     * refresh in flight). Deliberately NOT `Ready` and NOT a failure: the
     * startup path must never block the first frame on an AnkiDroid query
     * (GATE 02 §89).
     */
    data object Checking : AnkiAvailability

    /** AnkiDroid is not installed on this device (local backend only). */
    data object NotInstalled : AnkiAvailability

    /**
     * The AnkiDroid app is present but its exported integration provider could
     * not be reached — provider not exported/resolvable, disabled, served by an
     * unexpected package, or the package was removed between checks
     * (GATE 02 §9/§10). Package presence alone is never [Ready]
     * (INV-ANKI-DET-02).
     */
    data class ProviderUnavailable(val detail: String? = null) : AnkiAvailability

    /** Installed, but the integration permission has not been granted. */
    data class PermissionRequired(val detail: String? = null) : AnkiAvailability

    /** Installed, but no initialized collection is accessible yet. */
    data object CollectionNotInitialized : AnkiAvailability

    /** The backend can accept new review sessions right now. */
    data class Ready(val capabilities: AnkiCapabilities) : AnkiAvailability

    /** Expected to recover by itself (provider locked, collection closed and reopening). */
    data class TemporarilyUnavailable(val reason: String? = null) : AnkiAvailability

    /** PC path: the agent itself is unreachable (mirrors connection lifecycle). */
    data object AgentDisconnected : AnkiAvailability

    /** PC path: agent reachable, but it reports Anki/AnkiConnect not ready. */
    data object AgentAnkiUnavailable : AnkiAvailability

    /** The backend exists but cannot serve this app (version/API shape). */
    data class Unsupported(val reason: String) : AnkiAvailability

    /** A classified domain error occurred while probing the backend. */
    data class Fault(val error: AnkiError) : AnkiAvailability
}

/**
 * True only when a new review session may start against this backend:
 * positively ready AND review-capable. Browsing decks may be possible under
 * weaker conditions; starting a rating-writing session may not (§72).
 */
val AnkiAvailability.isReadyForReview: Boolean
    get() = (this as? AnkiAvailability.Ready)?.capabilities?.review == true

/**
 * Stable status token for diagnostics, logs and support exports (GATE 02 §35/§100).
 *
 * A token, not a sentence: user-facing wording belongs to the UI layer (and is localizable),
 * while diagnostics correlate on a vocabulary that does not change when copy does.
 */
val AnkiAvailability.statusCode: String
    get() = when (this) {
        AnkiAvailability.Checking -> "CHECKING"
        AnkiAvailability.NotInstalled -> "NOT_INSTALLED"
        is AnkiAvailability.ProviderUnavailable -> "PROVIDER_UNAVAILABLE"
        is AnkiAvailability.PermissionRequired -> "PERMISSION_REQUIRED"
        AnkiAvailability.CollectionNotInitialized -> "COLLECTION_NOT_INITIALIZED"
        is AnkiAvailability.Ready -> "READY"
        is AnkiAvailability.TemporarilyUnavailable -> "TEMPORARILY_UNAVAILABLE"
        AnkiAvailability.AgentDisconnected -> "AGENT_DISCONNECTED"
        AnkiAvailability.AgentAnkiUnavailable -> "AGENT_ANKI_UNAVAILABLE"
        is AnkiAvailability.Unsupported -> "UNSUPPORTED"
        is AnkiAvailability.Fault -> "FAULT"
    }
