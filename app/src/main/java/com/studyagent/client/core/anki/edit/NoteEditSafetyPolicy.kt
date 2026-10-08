package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.ReviewCommitLedger
import com.studyagent.client.core.anki.ReviewerActionLedger

/** Why a note edit is blocked by unresolved Study work. Carries a source label only, never content. */
data class NoteEditBlock(val source: String)

/**
 * GATE 17 — decides whether unresolved Study work overlaps a note. Editing is entered only from
 * Card Details, and this policy is consulted both before a mutation is recorded and again
 * immediately before its first backend write.
 */
fun interface NoteEditSafetyPolicy {
    /** Null = no overlap is known. A non-null result refuses the edit. */
    suspend fun blockingStudyActivity(noteRef: AnkiNoteRef): NoteEditBlock?
}

/**
 * Production policy. A card overlaps a note when it belongs to the same backend and note, and it
 * overlaps when its note identity is unknown. Unknown identity is treated as overlapping, so the
 * policy fails closed rather than assuming independence.
 *
 * [unresolvedTurnCards] supplies cards of any presented but uncommitted Study turn. Its default
 * reports none, so callers must wire it if a turn can be open while the editor is reachable.
 */
class StudyActivityNoteEditSafetyPolicy(
    private val reviewCommits: ReviewCommitLedger,
    private val reviewerActions: ReviewerActionLedger,
    private val unresolvedTurnCards: suspend () -> List<AnkiCardRef> = { emptyList() }
) : NoteEditSafetyPolicy {

    override suspend fun blockingStudyActivity(noteRef: AnkiNoteRef): NoteEditBlock? {
        if (reviewCommits.unresolved().any { overlaps(it.card, noteRef) }) return NoteEditBlock("review_commit")
        if (reviewerActions.unresolved().any { overlaps(it.cardRef, noteRef) }) return NoteEditBlock("reviewer_action")
        if (unresolvedTurnCards().any { overlaps(it, noteRef) }) return NoteEditBlock("study_turn")
        return null
    }

    private fun overlaps(card: AnkiCardRef, note: AnkiNoteRef): Boolean =
        card.backendId != note.backendId || card.noteId == null || card.noteId == note.noteId
}
