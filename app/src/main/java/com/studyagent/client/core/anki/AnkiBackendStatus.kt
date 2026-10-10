package com.studyagent.client.core.anki

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * GATE 21 — what the app knows about every registered backend right now
 * (IMPLEMENTATION-21-04/18, DECISION-21-23/36).
 *
 * One entry per registered backend, each with a stable content-free status token and the reason it
 * cannot be used yet, so the Connection/Settings screens can say *"AnkiDroid — permission required"*
 * instead of a bare "unavailable" (VER-21-03). Nothing here is a secret: no host, port, token or
 * filesystem path.
 */
data class AnkiBackendStatusEntry(
    val descriptor: AnkiBackendDescriptor,
    /** Stable diagnostics vocabulary ([AnkiAvailability.statusCode]), never localized copy. */
    val statusToken: String,
    /**
     * Content-free reason this backend is not usable *right now*, or null when it is reachable.
     * A token, not a sentence: UI wording is localizable, tokens are not.
     */
    val unavailabilityToken: String?
) {
    val id: AnkiBackendId get() = descriptor.id
    val isReachable: Boolean get() = descriptor.isReachable
}

data class AnkiBackendStatusSnapshot(
    val preferred: AnkiBackendMode,
    val entries: List<AnkiBackendStatusEntry>
) {
    fun entry(id: AnkiBackendId): AnkiBackendStatusEntry? = entries.firstOrNull { it.id == id }

    companion object {
        val EMPTY = AnkiBackendStatusSnapshot(AnkiBackendMode.AUTO, emptyList())
    }
}

/**
 * GATE 21 — the observation point for backend state and the entry point for pre-binding resolution.
 *
 * It combines, and decides nothing on its own: the ordering policy is [AnkiBackendResolver]'s, the
 * registry owns existence, and an active session's backend is owned by that session. Keeping these
 * apart is what makes "which backend owns this?" answerable (AUDIT-21-14).
 *
 * Resolution here is only valid **before** a context binds (DECISION-21-07/INV-21-09). A caller that
 * already holds a session, editor or transaction must not call [resolveFor] to re-route it.
 */
class AnkiBackendStatusCenter(
    private val registry: AnkiBackendRegistry,
    private val preferenceStore: AnkiBackendPreferenceStore,
    private val resolver: AnkiBackendResolver,
    scope: CoroutineScope
) {

    /** One live descriptor per registered backend, in a deterministic (identity-sorted) order. */
    private val observations: List<StateFlow<AnkiBackendDescriptor>> =
        registry.registered.sortedBy { it.id.stableId }.map { backend ->
            combine(backend.availability, backend.capabilities) { availability, capabilities ->
                AnkiBackendDescriptor(backend.id, availability, capabilities)
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = AnkiBackendDescriptor.of(backend)
            )
        }

    val status: StateFlow<AnkiBackendStatusSnapshot> = run {
        if (observations.isEmpty()) {
            MutableStateFlow(AnkiBackendStatusSnapshot.EMPTY)
        } else {
            combine(preferenceStore.mode, combine(observations) { it.toList() }) { preferred, descriptors ->
                AnkiBackendStatusSnapshot(
                    preferred = preferred,
                    entries = descriptors.map { it.toEntry() }
                )
            }
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = snapshotNow()
    )

    /**
     * Resolve one backend for an operation that has **not bound yet**. Deterministic for identical
     * inputs (DECISION-21-09); never probes, refreshes or writes a preference.
     */
    fun resolveFor(request: BackendResolutionRequest): BackendResolutionResult =
        resolver.resolve(request)

    /** Resolve against the user's current preference. */
    fun resolveFor(
        requiredCapabilities: Set<AnkiRequiredCapability>,
        purpose: String? = null
    ): BackendResolutionResult = resolveFor(
        BackendResolutionRequest(
            preference = preferenceStore.mode.value,
            requiredCapabilities = requiredCapabilities,
            purpose = purpose
        )
    )

    /** Synchronous snapshot for diagnostics and first frames (no suspension, no probing). */
    fun snapshotNow(): AnkiBackendStatusSnapshot = AnkiBackendStatusSnapshot(
        preferred = preferenceStore.mode.value,
        entries = registry.descriptors()
            .sortedBy { it.id.stableId }
            .map { it.toEntry() }
    )

    private fun AnkiBackendDescriptor.toEntry(): AnkiBackendStatusEntry = AnkiBackendStatusEntry(
        descriptor = this,
        statusToken = availability.statusCode,
        unavailabilityToken = availability.unavailabilityToken()
    )

    private fun AnkiAvailability.unavailabilityToken(): String? = when (this) {
        is AnkiAvailability.Ready -> null
        else -> statusCode
    }
}

/**
 * GATE 21 — the immutable backend lock of every active StudySession (DECISION-21-11/36,
 * IMPLEMENTATION-21-19).
 *
 * The session's own [AnkiSessionContext] remains the truth; this is its **observable projection** so
 * Study UI can say "Using AnkiDroid" from the lock rather than from the global preference
 * (INV-21-08). It is written once per session start and cleared when the session ends — it can never
 * rewrite a lock, and a preference change never touches it (VER-21-08).
 */
class AnkiStudyBackendLockTracker {

    private val _locks = MutableStateFlow<Map<String, AnkiSessionContext>>(emptyMap())

    /** studySessionId → the context captured at session start. */
    val locks: StateFlow<Map<String, AnkiSessionContext>> = _locks.asStateFlow()

    /** Records the lock captured at start. Re-recording the same session is idempotent. */
    fun locked(context: AnkiSessionContext) {
        _locks.update { current ->
            if (current[context.studySessionId]?.backendId == context.backendId) current
            else current + (context.studySessionId to context)
        }
    }

    /** Drops the lock when the session ends. Ending a session is the only way to release a backend. */
    fun released(studySessionId: String) {
        _locks.update { current -> if (studySessionId in current) current - studySessionId else current }
    }

    fun lockedBackendFor(studySessionId: String): AnkiBackendId? =
        _locks.value[studySessionId]?.backendId
}
