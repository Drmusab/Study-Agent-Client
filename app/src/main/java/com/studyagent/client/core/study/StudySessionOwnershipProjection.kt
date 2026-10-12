package com.studyagent.client.core.study

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.anki.ReviewerActionStatus
import com.studyagent.client.core.anki.kind

/**
 * GATE 21G — read-only ownership metadata of the active StudySession (CHECKPOINT 21G-24).
 *
 * This is a *projection* for future UI and diagnostics (GATE 21M owns the presentation): it
 * reports the session's locked backend, never the current global preference, and carries no
 * secrets, no credentials, no card content, and no ledger duplication. It decides nothing — in
 * particular, no routing, admission, or recovery input may be derived from it.
 */
data class StudySessionOwnershipProjection(
    /** The StudySession this projection describes, or `null` when no Anki session is active. */
    val studySessionId: String?,
    /** The session's immutable locked backend. `null` = the session has not bound yet. */
    val lockedBackendId: AnkiBackendId?,
    /** The locked backend's kind, derived from [lockedBackendId]. */
    val backendKind: AnkiBackendKind?,
    /**
     * What the lock knows about its collection: [COLLECTION_UNKNOWN] when no key was bound,
     * [COLLECTION_KEY_PRESENT] when one was. This is lock contents, not a continuity proof —
     * it never claims the live backend still serves that collection.
     */
    val collectionState: String?,
    /** The machine lifecycle token (the phase name), e.g. `WaitingForRating`. */
    val lifecycleState: String,
    /**
     * Why the session is blocked, when it is: the commit status, the reviewer-action status, or
     * the held failure's class token — whichever explains the block. `null` = not blocked.
     */
    val blockedReason: String?,
    /** `true` once the backend accepted the session ([lockedBackendId] is non-null). */
    val hasLock: Boolean
) {
    companion object {
        const val COLLECTION_UNKNOWN: String = "collection_unknown"
        const val COLLECTION_KEY_PRESENT: String = "collection_key_present"
    }
}

/**
 * Project the session's ownership from the machine state and the session's own lock.
 *
 * [lock] is the session's [AnkiSessionContext] (or the lock tracker's entry for the session);
 * the global preference is never consulted, so a preference change cannot alter this projection
 * for an active session (INV-21G-04, VER-21G-32).
 */
fun projectSessionOwnership(
    state: SessionMachineState,
    lock: AnkiSessionContext?
): StudySessionOwnershipProjection {
    val anki = state.anki
    val sessionId = anki?.request?.studySessionId
    val lifecycle = state.phase::class.simpleName ?: "Unknown"
    val blocked = when {
        anki == null -> null
        anki.blockedByPriorCommit -> "blocked_by_prior_commit"
        anki.commit != null && anki.commit.status != ReviewCommitStatus.COMMITTED ->
            "commit_${anki.commit.status.name.lowercase()}"
        anki.reviewerAction != null && anki.reviewerAction.status != ReviewerActionStatus.APPLIED ->
            "reviewer_action_${anki.reviewerAction.status.name.lowercase()}"
        anki.failure != null -> "failure_${anki.failure::class.simpleName?.lowercase()}"
        state.phase is SessionPhase.Error -> "error_${state.phase.problem.name.lowercase()}"
        else -> null
    }
    return StudySessionOwnershipProjection(
        studySessionId = sessionId,
        lockedBackendId = lock?.backendId,
        backendKind = lock?.backendId?.kind,
        collectionState = when {
            lock == null -> null
            lock.collection?.collectionKey != null ->
                StudySessionOwnershipProjection.COLLECTION_KEY_PRESENT
            else -> StudySessionOwnershipProjection.COLLECTION_UNKNOWN
        },
        lifecycleState = lifecycle,
        blockedReason = blocked,
        hasLock = lock != null
    )
}
