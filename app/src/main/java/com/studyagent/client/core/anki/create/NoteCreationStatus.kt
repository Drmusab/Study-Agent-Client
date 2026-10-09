package com.studyagent.client.core.anki.create

import kotlinx.serialization.Serializable

/**
 * GATE 18 — the note-creation lifecycle (CONTRACT-18-41, locked). This enum is NOT the edit, rating
 * or reviewer-action status type and must never be used to represent any of them (INV-18-17).
 *
 * - [PREPARED]: durably recorded; no boundary has been entered.
 * - [STORING_MEDIA]: at least one media-store boundary may already have been entered; per-step
 *   stored names are persisted as they arrive. The note has NOT been created yet.
 * - [CREATING_NOTE]: the note boundary was entered; the backend answer is not durably recorded.
 * - [CREATED]: terminal positive — the backend confirmed the note AND the final write succeeded.
 * - [RETRY_ALLOWED]: the backend PROVED no note was created; the user may retry under the same id.
 * - [AMBIGUOUS]: the outcome is unknown (the note may exist with an unknown id). Never retried
 *   (INV-18-08); only a human attestation closes it. It cannot block a specific note — there is no
 *   id to block — so it is surfaced instead (docs/GATE_18 §18).
 * - [ABANDONED]: terminal negative — closed without any confirmed note: abandoned at restart
 *   (payload gone), a confirmed non-creation the user did not retry, a media step with an unknown
 *   outcome (no note effect possible), or a human attestation that no note exists. [reason] tells
 *   which; an orphan-media note attaches when media boundaries were entered (docs/GATE_18 §18/§20).
 *
 * Procedural phase (which media step, which boundary) is tracked by [NoteCreationRecord]'s
 * `lastEnteredStep` / `mediaSteps`, never by overloading this enum (PART I §3).
 */
@Serializable
enum class NoteCreationStatus {
    PREPARED,
    STORING_MEDIA,
    CREATING_NOTE,
    CREATED,
    RETRY_ALLOWED,
    AMBIGUOUS,
    ABANDONED;

    /** CREATED and ABANDONED never change again. */
    val isTerminal: Boolean get() = this == CREATED || this == ABANDONED

    /** Everything except CREATED keeps the record resolvable by recovery. */
    val isUnresolved: Boolean get() = this == CREATING_NOTE || this == AMBIGUOUS

    /** Statuses under which the same creation may be re-attempted by an explicit user action. */
    val userRetryAllowed: Boolean get() = this == RETRY_ALLOWED
}

/** Why a creation record reached its current state. Recorded, never inferred at display time. */
@Serializable
enum class NoteCreationReason {
    NONE,

    /** The note boundary was entered and the outcome could not be recorded. */
    OUTCOME_UNKNOWN,

    /** A CREATING_NOTE record found at restart: the boundary was crossed, the answer was lost. */
    RECOVERED_AFTER_RESTART,

    /** The backend proved no note was created (a pre-write refusal). */
    NOT_CREATED_BY_BACKEND,

    /** A human attested that the possibly-created note exists in the collection. */
    USER_ATTESTED_CREATED,

    /** A human attested that no such note exists; the record is closed. */
    USER_ATTESTED_ABSENT,

    /** A PREPARED/STORING_MEDIA record abandoned at restart (payload lived in the old process). */
    ABANDONED_ON_RESTART,

    /** The creation was confirmed but the post-create hydration read failed. Still CREATED. */
    HYDRATION_FAILED,

    /**
     * A pre-boundary re-check (schema drift, model gone) refused the note boundary. No backend
     * effect occurred; stored media, if any, remains as disclosed orphan risk (§20).
     */
    PRE_BOUNDARY_REFUSED
}
