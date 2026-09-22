package com.studyagent.client.core.anki

/**
 * GATE 01 contract — unified availability of an Anki backend (§17, §71).
 *
 * One domain representation for two very different physical situations:
 *
 *  - AnkiDroid-local: [NotInstalled], [PermissionRequired],
 *    [CollectionNotInitialized], [TemporarilyUnavailable], [Ready].
 *  - PC path: [AgentDisconnected] (no PC agent), [AgentAnkiUnavailable]
 *    (agent up, Anki beneath it down), [Ready].
 *
 * UI renders from this state plus [AnkiCapabilities]; it never re-derives
 * "available" from connection state or package names itself. `ready` is NOT
 * the absence of an error — it is the positive knowledge that review work can
 * start.
 */
sealed interface AnkiAvailability {

    /** AnkiDroid is not installed on this device (local backend only). */
    data object NotInstalled : AnkiAvailability

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
