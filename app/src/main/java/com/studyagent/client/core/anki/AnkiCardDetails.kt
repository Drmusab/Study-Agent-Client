package com.studyagent.client.core.anki

/**
 * GATE 16 — the deep, read-only card/note details model (CHECKPOINT 01).
 *
 * One exact card, everything a details screen may honestly show, and nothing a details screen may
 * do. This gate is strictly read-only (INV-16-01): no type in this file can mutate a note, a card,
 * a flag, a tag, a deck, a queue or the scheduler.
 *
 * Identity discipline (INV-16-02/03/04):
 *
 * - [cardRef] is the card identity. It is never replaced by [noteRef], never reconstructed from
 *   question text, deck name or list position, and it is backend/collection qualified
 *   (see [AnkiCardRef]).
 * - [noteRef] is the note identity. One note generates many cards; [cardOrd] keeps the
 *   card/template instance distinguishable and is preserved for later template-aware gates.
 * - A note id is never a card id and a card id is never a note id.
 *
 * Content discipline (INV-16-05): the four content channels stay separate and are never derived
 * from one another —
 *
 * - rendered presentation: [questionHtml] / [answerHtml] (what GATE 08/09 renders verbatim);
 * - normalized readable text: [questionText] / [answerText] (speech/display text channels);
 * - evaluator-oriented text: [pureAnswerText] (question-side content removed);
 * - source note values: [fields] — the note's own field values in backend order. Note fields are
 *   NEVER reconstructed by parsing rendered HTML (AUDIT 4).
 *
 * Nullability is meaning (INV-16-10): a `null` channel means the backend did not or could not
 * supply it; a non-null empty string is a legitimately empty value. `reps = 0` is a confirmed
 * zero; `reps = null` is unavailable. Nothing here is ever a guessed default.
 *
 * Scheduling discipline (INV-16-08/09): [scheduling] is backend-reported fact only (see
 * [AnkiSchedulingInfo]). This file contains no scheduler arithmetic — no next interval, no next
 * review, no FSRS prediction, no queue ordering, no ease transition. Labels for these values are
 * produced only in the UI presentation layer.
 *
 * Pure Kotlin: no Android, no Compose, no provider types (INV-16-13).
 */
data class AnkiCardDetails(
    /** The exact card this details snapshot describes. Never a display identity. */
    val cardRef: AnkiCardRef,
    /** The generating note, when the backend can address it. Never a substitute for [cardRef]. */
    val noteRef: AnkiNoteRef?,
    /**
     * The card/template ordinal inside its note (`0`-based, as backends report it). Preserved so a
     * later template-aware gate can distinguish which card instance of the note this is
     * (INV-16-04). `null` = the backend did not report one; never invented.
     */
    val cardOrd: Int?,
    val deckRef: AnkiDeckRef?,
    /** Display name only — never identity (INV-ANKI-DECK-01). */
    val deckName: String?,
    /** Note type ("model") identity, when exposed. String like every backend-issued id here. */
    val noteTypeId: String?,
    val noteTypeName: String?,
    /** Card/template display name (`card_name`), never identity (STEP 27). */
    val templateName: String?,

    // ---- content channels (kept apart — INV-16-05) ------------------------------------------
    /** Rendered question, verbatim backend output. Rendered only by GATE 08/09 (INV-16-06/07). */
    val questionHtml: String?,
    /** Rendered answer, verbatim backend output (may include the question side via FrontSide). */
    val answerHtml: String?,
    /** Normalized readable question text. */
    val questionText: String?,
    /** Normalized readable answer text. */
    val answerText: String?,
    /** Evaluator-oriented answer (question-side content removed). Never HTML. */
    val pureAnswerText: String?,

    // ---- source note content ------------------------------------------------------------------
    /**
     * Source note fields in backend-authoritative order (never alphabetically re-sorted — GATE 16
     * §4). `null` = the backend cannot expose note fields at all (unsupported capability surfaced
     * as absence, CHECKPOINT 10); an empty list is a note that genuinely has no fields. Field
     * values are raw source values (may contain HTML) and are never executed as HTML by
     * presentation (INV-16-16).
     */
    val fields: List<AnkiNoteField>?,
    /**
     * Read-only tag list in backend order. `null` = unavailable/unsupported; empty = the backend
     * authoritatively reported no tags (GATE 16 backend capability-aware presentation). Editing is
     * not this gate (INV-16-17).
     */
    val tags: List<String>?,

    // ---- descriptive facts --------------------------------------------------------------------
    /** Backend flag marker; `null` = the backend did not say. Read-only here (INV-16-17). */
    val flag: AnkiFlag?,
    /** Backend-reported card type. Never inferred from due values (GATE 16 §14). */
    val cardType: AnkiCardType?,
    /**
     * Backend-reported queue/display state. Modeled separately from [cardType] — the two are not
     * interchangeable (GATE 16 §15); raw provider integers are mapped inside the data layer only.
     */
    val queueState: AnkiCardQueueState?,
    /** Informational scheduling facts straight from the backend (INV-16-08/09). */
    val scheduling: AnkiSchedulingInfo?,
    /** Home deck while the card sits in a filtered deck; distinct from the current [deckRef]. */
    val originalDeckRef: AnkiDeckRef?,
    /** Note creation time, Unix epoch seconds, when the backend reports one. */
    val noteCreatedEpochSeconds: Long?,
    /** Note modification time, Unix epoch seconds, when the backend reports one. */
    val noteModifiedEpochSeconds: Long?,

    // ---- media & diagnostics -------------------------------------------------------------------
    /**
     * Logical media names referenced by the note/card content (GATE 16 §22). References only —
     * never paths, never open files (INV-ANKI-CARD-20). Resolution stays with the GATE 09 media
     * resolver; this list is display metadata.
     */
    val mediaFiles: List<String> = emptyList(),
    /** Content-free degradation tokens (for example `card_deck_name_unavailable`). */
    val degradations: List<String> = emptyList()
) {
    init {
        require(noteRef == null || noteRef.backendId == cardRef.backendId) {
            "Note identity must belong to the same backend as the card"
        }
        require(deckRef == null || deckRef.backendId == cardRef.backendId) {
            "Deck identity must belong to the same backend as the card"
        }
        require(originalDeckRef == null || originalDeckRef.backendId == cardRef.backendId) {
            "Original deck identity must belong to the same backend as the card"
        }
        require(noteRef == null || cardRef.noteId == null || noteRef.noteId == cardRef.noteId) {
            "Note identity must agree with the card reference"
        }
        require(cardOrd == null || cardOrd >= 0) { "A card ordinal is never negative" }
        require(cardOrd == null || cardRef.cardOrd == null || cardOrd == cardRef.cardOrd) {
            "Card ordinal must agree with the card reference"
        }
        // At most one *known* collection across every ref; unknown is not a wildcard.
        require(
            listOfNotNull(cardRef.collectionKey, noteRef?.collectionKey, deckRef?.collectionKey, originalDeckRef?.collectionKey)
                .distinct().size <= 1
        ) { "Card, note and deck identities must share one collection" }
        require(deckName == null || deckName.isNotBlank()) { "A deck name is blank or absent, never both" }
        require(noteTypeName == null || noteTypeName.isNotBlank())
        require(templateName == null || templateName.isNotBlank())
        require(tags == null || tags.none(String::isBlank)) { "Tags are never blank entries" }
        require(tags == null || tags.distinct().size == tags.size) { "Tags are shown once, in backend order" }
        require(fields == null || fields.all { it.ordinal == null || it.ordinal >= 0 })
        require(noteCreatedEpochSeconds == null || noteCreatedEpochSeconds >= 0)
        require(noteModifiedEpochSeconds == null || noteModifiedEpochSeconds >= 0)
    }

    val noteId: String? get() = noteRef?.noteId ?: cardRef.noteId

    /** Derived, never stored: the backend said the card is suspended right now. `null` = unknown. */
    val isSuspended: Boolean? get() = queueState?.let { it == AnkiCardQueueState.SUSPENDED }

    /** Derived, never stored: the backend said the card is buried right now. `null` = unknown. */
    val isBuried: Boolean? get() = queueState?.let { it == AnkiCardQueueState.BURIED }
}

/**
 * One source note field value with its authoritative name and position (GATE 16 §4).
 *
 * [value] is the raw source value exactly as the backend stores it — it may contain HTML and must
 * be displayed through a safe plain/normalized preview, never executed (INV-16-16). An empty
 * [value] is a legitimately empty field; it is not "unknown" (INV-16-10).
 */
data class AnkiNoteField(
    val name: String,
    val value: String,
    /** `0`-based position in backend field order; `null` = the backend did not report one. */
    val ordinal: Int?
) {
    init {
        require(name.isNotBlank()) { "A field name is never blank" }
        require(ordinal == null || ordinal >= 0) { "A field ordinal is never negative" }
    }
}
