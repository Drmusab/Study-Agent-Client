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
 * CONTRACT-01/CONTRACT-14 — how a backend identifies the field a value belongs to.
 *
 * AnkiDroid's `notes/<id>` write takes ONE `flds` string that is split positionally and checked
 * against the note's *current* field count. Field names are never consulted, so the only honest
 * identity is the ordinal, verified against a fresh read of the note type before every write.
 */
enum class NoteFieldIdentity {
    POSITIONAL_ORDINAL,
    FIELD_NAME,
    UNKNOWN
}

/** CONTRACT-02 — the tag mutation model a backend actually offers. */
enum class NoteTagWriteModel {
    /** One write replaces the whole set, and the backend canonicalizes it (case, order, NFC). */
    REPLACE_ONLY_CANONIFIED,
    /** One write replaces the whole set verbatim. */
    REPLACE_ONLY_VERBATIM,
    /** Separate add and remove operations exist. Not offered at the AnkiDroid pin. */
    ADD_REMOVE_DELTA,
    UNSUPPORTED,
    UNKNOWN
}

/**
 * CONTRACT-15 — which deck a card that a *field edit* generates lands in. AnkiDroid's write reaches
 * rslib `update_note_inner` → `generate_cards_for_existing_note`, which uses the note type's
 * last-added deck (or a template's own target deck), NOT the deck of the card being edited. The UI
 * must say so instead of implying that generated cards join the edited card's deck.
 */
enum class NoteGeneratedCardDeck {
    TEMPLATE_TARGET_ELSE_NOTE_TYPE_LAST_DECK,
    SAME_AS_SOURCE_CARD,
    UNKNOWN
}

/**
 * Per-backend, evidence-based claims about note mutation. Each field is a claim that must be backed
 * by a pinned source reference. Defaults are the safe answer.
 */
data class NoteMutationSemantics(
    /**
     * Fields and tags land in one backend call that is all-or-nothing.
     *
     * This is a claim about the *write*, never about concurrency: an atomic write still offers no
     * protection against another editor (see [conflictGuarantee]).
     */
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
    val trailingEmptyFieldRepresentable: Boolean = false,
    /** CONTRACT-01/14 — how fields are addressed. Defaults to the safe answer. */
    val fieldIdentity: NoteFieldIdentity = NoteFieldIdentity.UNKNOWN,
    /** CONTRACT-02 — the tag model. Defaults to unknown, which refuses tag edits. */
    val tagWriteModel: NoteTagWriteModel = NoteTagWriteModel.UNKNOWN,
    /**
     * CONTRACT-02 — the backend rewrites the tag set it stores (case, order, NFC, blank hierarchy
     * components). When true, a post-write comparison must use [NoteContentCanonicalization].
     */
    val backendCanonifiesTags: Boolean = false,
    /**
     * CONTRACT-15 — the backend rewrites field text on the way in (ASCII control characters other
     * than `\n`/`\t` are removed; NFC is applied when the collection prefers normalized text).
     */
    val backendNormalizesFieldText: Boolean = false,
    /**
     * CONTRACT-09 — an unchanged content write is a proven no-op at the backend (rslib
     * `update_note_inner` returns early when the note does not differ). This makes a repeat
     * *effectively* idempotent; it is still not a contractual replay guarantee, because the caller
     * cannot distinguish "applied now" from "already applied" ([idempotentReplay] stays false).
     */
    val contentWriteNoOpWhenUnchanged: Boolean = false,
    /**
     * CONTRACT-09 — a deck write always writes (rslib `update_card_inner` sets the card modified and
     * stores it even when the deck is unchanged), so repeating it is not a no-op.
     */
    val deckWriteAlwaysWrites: Boolean = false,
    /**
     * CONTRACT-15 — editing fields can make the backend generate additional cards for the same note
     * (a template whose front became non-empty). The write response does not report them.
     */
    val fieldEditCanGenerateSiblingCards: Boolean = false,
    /** Where a generated sibling card lands; only meaningful with [fieldEditCanGenerateSiblingCards]. */
    val generatedSiblingCardDeck: NoteGeneratedCardDeck = NoteGeneratedCardDeck.UNKNOWN,
    /**
     * CONTRACT-10 — the backend durably recognizes a caller-supplied mutation key. False everywhere
     * in GATE 17: [NoteMutationId] is local transaction identity and creates NO backend idempotency.
     */
    val backendHonoursMutationId: Boolean = false
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
         * AnkiDroid v2.24.1 public provider, resolved in `docs/GATE_17_BACKEND_CONTRACT.md`.
         *
         * Evidence (all read at the pins listed in that document):
         * - `contentWriteAtomic = true`: `CardContentProvider.update` mutates the in-memory note for
         *   every key and then calls `col.updateNote(currentNote)` **once**; `Collection.updateNote`
         *   is one `backend.updateNotes(...)` call, which is rslib `transact(Op::UpdateNote, …)`, and
         *   `transact_inner` rolls the whole transaction back on any error. Fields and tags therefore
         *   share one all-or-nothing write. It is still NOT a concurrency guarantee: nothing in the
         *   provider compares the note with a previously read state.
         * - `deckChangeScope = CARD_ONLY`: `notes/<id>/cards/<ord>` sets `currentCard.did` and calls
         *   `col.updateCard(currentCard)` — a separate `transact(Op::UpdateCard)`, so a save that
         *   changes content *and* deck is two transactions.
         * - `conflictGuarantee = BEST_EFFORT_PRE_SAVE_REREAD`: the only protection is Study-Agent's
         *   own re-read immediately before the boundary.
         * - `authoritativeReconciliation = false`, `backendHonoursMutationId = false`: the provider
         *   returns a row count and nothing else — no receipt, no lookup-by-transaction, no version.
         * - `idempotentReplay = false`: rslib returns early when a note does not differ, but the
         *   provider still reports the same count, so a repeat cannot be distinguished from a first
         *   write. Replay is therefore never automatic.
         */
        val ANKIDROID_V2_24_1 = NoteMutationSemantics(
            contentWriteAtomic = true,
            deckChangeScope = NoteDeckChangeScope.CARD_ONLY,
            conflictGuarantee = NoteConflictGuarantee.BEST_EFFORT_PRE_SAVE_REREAD,
            authoritativeReconciliation = false,
            idempotentReplay = false,
            // Utils.splitFields drops trailing empty fields, so a write ending in "" is refused
            // by the provider's count check (CardContentProvider NOTES_ID FLDS `require`).
            trailingEmptyFieldRepresentable = false,
            // One `flds` string, split positionally; the provider checks the COUNT only.
            fieldIdentity = NoteFieldIdentity.POSITIONAL_ORDINAL,
            // One `tags` string replaces the whole set; there is no add/remove operation.
            tagWriteModel = NoteTagWriteModel.REPLACE_ONLY_CANONIFIED,
            backendCanonifiesTags = true,
            backendNormalizesFieldText = true,
            contentWriteNoOpWhenUnchanged = true,
            deckWriteAlwaysWrites = true,
            fieldEditCanGenerateSiblingCards = true,
            generatedSiblingCardDeck = NoteGeneratedCardDeck.TEMPLATE_TARGET_ELSE_NOTE_TYPE_LAST_DECK,
            backendHonoursMutationId = false
        )
    }
}
