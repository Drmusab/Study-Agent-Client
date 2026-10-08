package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.NoteConflictGuarantee

/**
 * GATE 17 — what one backend write step reported at the mutation boundary.
 *
 * The four variants are the only classifications a backend may return. A backend must choose
 * [ConfirmedNotApplied] only when it can prove that no part of the write was applied (for example a
 * refusal before dispatch). Anything weaker is [OutcomeUnknown].
 */
sealed interface NoteMutationBackendResult {
    /** The step's write was observed to apply completely. */
    data object ConfirmedApplied : NoteMutationBackendResult

    /** Proven: this step applied nothing. Its meaning depends on the operation index (see coordinator). */
    data class ConfirmedNotApplied(val error: AnkiError) : NoteMutationBackendResult

    /** The backend refused because the note no longer matches what the edit was based on. */
    data class Conflict(val latest: NoteEditBase? = null) : NoteMutationBackendResult

    /** The write may or may not have applied. Never retried directly. */
    data class OutcomeUnknown(val error: AnkiError) : NoteMutationBackendResult
}

/** Result of read-only reconciliation of an ambiguous mutation. */
sealed interface NoteMutationReconciliationResult {
    data object ConfirmedApplied : NoteMutationReconciliationResult
    data object ConfirmedNotApplied : NoteMutationReconciliationResult

    /** Evidence is missing or inconclusive. The mutation stays AMBIGUOUS. */
    data class Unresolved(val error: AnkiError? = null) : NoteMutationReconciliationResult
}

/** How a deck move is scoped by the backend. */
enum class NoteDeckChangeScope {
    /** Only the source card moves (AnkiDroid `notes/<id>/cards/<ord>`). */
    CARD_ONLY,
    /** Every card of the note moves. Not allowed in GATE 17. */
    NOTE_WIDE,
    /** Not verified. Deck edits are refused. */
    UNKNOWN
}

/**
 * Per-backend, evidence-based claims about note mutation. Each field is a claim that must be backed
 * by a pinned source reference. Defaults are the safe answer.
 */
data class NoteMutationSemantics(
    /** Fields and tags land in one backend call that is all-or-nothing. */
    val contentWriteAtomic: Boolean,
    val deckChangeScope: NoteDeckChangeScope,
    val conflictGuarantee: NoteConflictGuarantee,
    /** A read-only check can prove whether a previously submitted write applied. */
    val authoritativeReconciliation: Boolean,
    /** Repeating the same write is provably safe (idempotent). Always false in GATE 17. */
    val idempotentReplay: Boolean,
    /**
     * Whether a field-value write whose LAST field is empty can be represented. AnkiDroid's provider
     * splits `flds` with a trailing-empty drop (`Utils.splitFields` → `dropLastWhile { it.isEmpty() }`),
     * so the count check refuses such a write. Defaults to false: a backend must prove otherwise.
     */
    val trailingEmptyFieldRepresentable: Boolean = false
) {
    companion object {
        /** Nothing is claimed. Used by any backend that has not supplied evidence. */
        val UNVERIFIED = NoteMutationSemantics(
            contentWriteAtomic = false,
            deckChangeScope = NoteDeckChangeScope.UNKNOWN,
            conflictGuarantee = NoteConflictGuarantee.NONE,
            authoritativeReconciliation = false,
            idempotentReplay = false
        )

        /**
         * AnkiDroid v2.24.1 public provider. Field and tag writes are one `notes/<id>` update, but
         * atomicity is not verified, so `contentWriteAtomic` stays false. The re-read before a write
         * is best effort, not a compare-and-set. Deck moves are card-scoped. There is no receipt or
         * lookup-by-id, so reconciliation is not authoritative.
         */
        val ANKIDROID_V2_24_1 = NoteMutationSemantics(
            contentWriteAtomic = false,
            deckChangeScope = NoteDeckChangeScope.CARD_ONLY,
            conflictGuarantee = NoteConflictGuarantee.BEST_EFFORT_PRE_SAVE_REREAD,
            authoritativeReconciliation = false,
            idempotentReplay = false,
            // Utils.splitFields drops trailing empty fields, so a write ending in "" is refused
            // by the provider's count check (CardContentProvider NOTES_ID FLDS `require`).
            trailingEmptyFieldRepresentable = false
        )
    }
}
