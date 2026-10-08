package com.studyagent.client.core.anki.edit

import kotlinx.serialization.Serializable

/**
 * GATE 17 — the note-edit lifecycle. This enum is NOT the rating or reviewer-action status type and
 * must never be used to represent either of them (GATE 17 §4).
 *
 * - [PREPARED]: durably recorded; no backend write has been issued.
 * - [SUBMITTING]: durably recorded as "the first backend write may already have happened".
 * - [APPLIED]: terminal; the backend confirmed the full plan.
 * - [RETRY_ALLOWED]: the backend confirmed that the write did not apply; retry with the same id.
 * - [AMBIGUOUS]: the outcome is unknown; cannot be retried directly.
 * - [CONFLICT]: terminal; the edit was not applied and will not be retried under this id.
 */
@Serializable
enum class NoteMutationStatus {
    PREPARED,
    SUBMITTING,
    APPLIED,
    RETRY_ALLOWED,
    AMBIGUOUS,
    CONFLICT;

    /** APPLIED and CONFLICT never change again. */
    val isTerminal: Boolean get() = this == APPLIED || this == CONFLICT

    /** Any non-terminal status blocks another edit to the same note (one active mutation per note). */
    val isActive: Boolean get() = !isTerminal
}
