package com.studyagent.client.core.anki

import kotlinx.coroutines.flow.StateFlow

/**
 * GATE 21 — the user's *default* backend choice (IMPLEMENTATION-21-05, DECISION-21-06).
 *
 * `PreferredBackend` is deliberately NOT the active backend. It answers "what should a brand-new,
 * unbound operation use?" and nothing else: an active StudySession, a bound editor, a bound creation
 * draft and every unresolved durable transaction keep their own backend identity and are unaffected
 * by a preference change (DECISION-21-32, INV-21-08, VER-21-08/15/16/25).
 *
 * The store is an interface so the routing rules are testable without DataStore; the Android
 * implementation lives in `data/preferences` and persists through the app's existing settings
 * mechanism.
 */
interface AnkiBackendPreferenceStore {

    /** Current preference. Never an "effective backend": AUTO stays AUTO until something resolves it. */
    val mode: StateFlow<AnkiBackendMode>

    /** Content-free token for a failed persistence write, or null. */
    val lastWriteError: StateFlow<String?>

    /**
     * Persist a new preference. `false` means storage refused: the in-memory value is unchanged, so
     * the UI must not claim the switch happened.
     */
    suspend fun setMode(mode: AnkiBackendMode): Boolean
}

/**
 * The four backend roles a diagnostic line must keep apart (DECISION-21-35, AUDIT-21-15).
 *
 * Collapsing any two of these is how a support report becomes unreadable: "the app used PC" is a
 * different statement from "the user prefers PC", and neither says which backend an active session is
 * locked to or which one a durable transaction belongs to.
 */
data class AnkiBackendRoutingDiagnostics(
    /** What the user asked for by default. */
    val preferred: AnkiBackendMode,
    /** What the last pre-binding resolution chose, and why (null before any resolution). */
    val resolved: AnkiBackendId?,
    val resolvedReason: BackendSelectionReason?,
    /**
     * The immutable backend of the active StudySession, when exactly one session holds a lock.
     * Null when there is none — and also when there are several, in which case
     * [lockedSessionCount] says how many and no single backend is named.
     *
     * Inventing an identity for the multi-session case would state a falsehood: it would name one
     * session's authority as though it were "the" app authority (INV-21-05).
     */
    val lockedSessionBackend: AnkiBackendId?,
    /** How many study sessions currently hold a backend lock. */
    val lockedSessionCount: Int = 0,
    /** The backend a durable transaction was written against, when the caller has one. */
    val transactionBackend: AnkiBackendId?,
    /** Registry-wide availability at the time of the snapshot, as stable status tokens. */
    val availability: Map<AnkiBackendId, String>
) {
    companion object {
        val EMPTY = AnkiBackendRoutingDiagnostics(
            preferred = AnkiBackendMode.AUTO,
            resolved = null,
            resolvedReason = null,
            lockedSessionBackend = null,
            lockedSessionCount = 0,
            transactionBackend = null,
            availability = emptyMap()
        )
    }
}
