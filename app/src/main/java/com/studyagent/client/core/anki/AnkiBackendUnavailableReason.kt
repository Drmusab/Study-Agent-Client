package com.studyagent.client.core.anki

/**
 * GATE 21D — can re-running the (read-only) availability check change the outcome, and what would
 * it take? A claim about the *cause*, never a promise about the future.
 *
 * Deliberately no "definitely retryable": a cause nobody classified is [UNKNOWN], because telling a
 * user to retry something that can only be fixed by an update is as wrong as telling them to fix
 * something that was about to recover.
 */
enum class AnkiBackendRetryability {
    /** The backend says it recovers by itself (provider busy, collection reopening). */
    EXPECTED_TO_RECOVER,

    /** Plausibly transient (a lost transport may reconnect) but nothing guarantees it. */
    MAY_RECOVER,

    /** Re-checking cannot help until a person acts: install, grant, set up, authenticate, update. */
    AFTER_USER_ACTION,

    /** The cause was not classified. Nothing is claimed about retry or remediation. */
    UNKNOWN
}

/**
 * GATE 21D — why a registered backend is not usable right now (CHECKPOINT 21D-03).
 *
 * This is a **projection of [AnkiAvailability]**, not a second state hierarchy: the registry's one
 * availability truth stays the backend's own flow, and [unavailableReason] is the single total
 * function that classifies it. That function has no `else`, so adding an availability state without
 * deciding its reason is a compile error rather than a silent `UNKNOWN`.
 *
 * Each category is evidence-based — it exists because some availability state produces it:
 *
 * | Reason | Produced by | Applies to |
 * |---|---|---|
 * | [APP_NOT_INSTALLED] | [AnkiAvailability.NotInstalled] | AnkiDroid |
 * | [PROVIDER_UNAVAILABLE] | [AnkiAvailability.ProviderUnavailable] | AnkiDroid |
 * | [PERMISSION_REQUIRED] | [AnkiAvailability.PermissionRequired] | AnkiDroid |
 * | [COLLECTION_UNAVAILABLE] | [AnkiAvailability.CollectionNotInitialized] | either |
 * | [TEMPORARILY_UNAVAILABLE] | [AnkiAvailability.TemporarilyUnavailable] | either |
 * | [TRANSPORT_UNAVAILABLE] | [AnkiAvailability.AgentDisconnected] | PC |
 * | [AUTHENTICATION_REQUIRED] | [AnkiAvailability.AgentAuthenticationRequired] | PC |
 * | [ANKI_UNAVAILABLE] | [AnkiAvailability.AgentAnkiUnavailable] | PC |
 * | [VERSION_INCOMPATIBLE] | [AnkiAvailability.Unsupported] | AnkiDroid API shape / PC protocol |
 * | [BACKEND_ERROR] | [AnkiAvailability.Fault] | either |
 *
 * There is no `NOT_CONFIGURED` here: a backend that is not registered has **no state at all**, and
 * that absence is a different type ([AnkiBackendStateLookup.NotRegistered]) — absent is not
 * unavailable. There is no `UNKNOWN` either: [AnkiAvailability.Checking] is not a failure, so it
 * has no reason (it is "not verified yet", and is never a positive).
 *
 * Content-free by construction: an enum has no payload, so no credential, host, path, provider text
 * or card content can travel inside a reason (DECISION-21B-27).
 */
enum class AnkiBackendUnavailableReason(val retryability: AnkiBackendRetryability) {
    /** The backend's host application is not on this device. */
    APP_NOT_INSTALLED(AnkiBackendRetryability.AFTER_USER_ACTION),

    /** The application is present but its exported integration provider cannot be reached. */
    PROVIDER_UNAVAILABLE(AnkiBackendRetryability.AFTER_USER_ACTION),

    /** The integration permission has not been granted; the app cannot grant it to itself. */
    PERMISSION_REQUIRED(AnkiBackendRetryability.AFTER_USER_ACTION),

    /** Anki is reachable but no initialized collection is open. */
    COLLECTION_UNAVAILABLE(AnkiBackendRetryability.AFTER_USER_ACTION),

    /** The backend expects to recover on its own. */
    TEMPORARILY_UNAVAILABLE(AnkiBackendRetryability.EXPECTED_TO_RECOVER),

    /** The PC agent cannot be reached (network, transport or agent down). */
    TRANSPORT_UNAVAILABLE(AnkiBackendRetryability.MAY_RECOVER),

    /** The PC agent is reachable but has not accepted this client. Reachability is not trust. */
    AUTHENTICATION_REQUIRED(AnkiBackendRetryability.AFTER_USER_ACTION),

    /** The PC agent is reachable and authenticated, but Anki beneath it is not ready. */
    ANKI_UNAVAILABLE(AnkiBackendRetryability.AFTER_USER_ACTION),

    /** The peer's API shape (AnkiDroid provider spec) or protocol version cannot serve this build. */
    VERSION_INCOMPATIBLE(AnkiBackendRetryability.AFTER_USER_ACTION),

    /** A classified-as-failed probe whose cause was not classified further. */
    BACKEND_ERROR(AnkiBackendRetryability.UNKNOWN);

    /** Stable diagnostics token. Never localized copy: wording belongs to the UI layer. */
    val token: String get() = name
}

/**
 * Why this availability is not a positive, or `null` when there is nothing to explain
 * ([AnkiAvailability.Ready]) or nothing has been decided yet ([AnkiAvailability.Checking]).
 *
 * Exhaustive on purpose — see [AnkiBackendUnavailableReason].
 */
fun AnkiAvailability.unavailableReason(): AnkiBackendUnavailableReason? = when (this) {
    AnkiAvailability.Checking -> null
    is AnkiAvailability.Ready -> null
    AnkiAvailability.NotInstalled -> AnkiBackendUnavailableReason.APP_NOT_INSTALLED
    is AnkiAvailability.ProviderUnavailable -> AnkiBackendUnavailableReason.PROVIDER_UNAVAILABLE
    is AnkiAvailability.PermissionRequired -> AnkiBackendUnavailableReason.PERMISSION_REQUIRED
    AnkiAvailability.CollectionNotInitialized -> AnkiBackendUnavailableReason.COLLECTION_UNAVAILABLE
    is AnkiAvailability.TemporarilyUnavailable -> AnkiBackendUnavailableReason.TEMPORARILY_UNAVAILABLE
    AnkiAvailability.AgentDisconnected -> AnkiBackendUnavailableReason.TRANSPORT_UNAVAILABLE
    AnkiAvailability.AgentAuthenticationRequired -> AnkiBackendUnavailableReason.AUTHENTICATION_REQUIRED
    AnkiAvailability.AgentAnkiUnavailable -> AnkiBackendUnavailableReason.ANKI_UNAVAILABLE
    is AnkiAvailability.Unsupported -> AnkiBackendUnavailableReason.VERSION_INCOMPATIBLE
    is AnkiAvailability.Fault -> AnkiBackendUnavailableReason.BACKEND_ERROR
}
