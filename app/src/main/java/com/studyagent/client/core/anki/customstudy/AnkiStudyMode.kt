package com.studyagent.client.core.anki.customstudy

import com.studyagent.client.core.anki.AnkiDeckRef

/**
 * GATE 20 — how one StudySession is bound to the backend's scheduler (IMPLEMENTATION-20-01).
 *
 * A mode is **not** a filter, a query or a card list. It names which of the backend's own study
 * surfaces the session draws from, and it is the only place where "this session is not ordinary
 * deck study" may be expressed. Every mode resolves to exactly one scheduler authority: the
 * backend. There is deliberately no mode in which Study-Agent selects, orders or prioritises a
 * card (INV-20-03/INV-20-07).
 *
 * Representation rules:
 *
 * - [Normal] is ordinary deck-scoped review: `BeginReviewRequest` → backend queue. Its deck scope
 *   is the deck **and its descendants**, because that is how Anki builds a queue
 *   (`QueueBuilder::new` → `child_decks`), not because Study-Agent computed a set.
 * - [Filtered] is a session bound to a **backend-native filtered/dynamic deck that already
 *   exists**. Study-Agent never created it, never rebuilt it and never empties it
 *   (DECISION-20-14/20-13). The reference is the backend's filtered deck id — never its display
 *   name (DECISION-20-08/DECISION-20-24).
 * - [Filtered] may only be constructed with a known [AnkiReschedulingDisclosure]. A filtered
 *   session whose rescheduling behaviour is unknown is refused at start instead of being described
 *   as if it were normal review (INV-20-16/INV-20-17).
 *
 * No `CUSTOM` mode exists: a mode whose semantics vary silently per backend is exactly what this
 * gate forbids (DECISION-20-01).
 */
sealed interface AnkiStudyMode {

    /** True when the session draws from a backend-native filtered/dynamic deck. */
    val isFiltered: Boolean

    /** Ordinary deck-scoped review through the backend's scheduler. */
    data object Normal : AnkiStudyMode {
        override val isFiltered: Boolean get() = false
    }

    /**
     * Review of an existing backend-native filtered deck.
     *
     * @param filteredDeckRef the backend's own identity for the filtered deck (a deck id), used as
     *   the session's scheduler target and never as a display string.
     * @param rescheduling what the backend says answers do to normal scheduling inside this deck.
     *   It is a *disclosed fact*, never an inference from the deck name or from card counts.
     */
    data class Filtered(
        val filteredDeckRef: AnkiDeckRef,
        val rescheduling: AnkiReschedulingDisclosure
    ) : AnkiStudyMode {
        override val isFiltered: Boolean get() = true

        init {
            require(filteredDeckRef.deckId.isNotBlank()) { "A filtered mode names the backend's filtered deck id" }
        }
    }

    companion object {
        /** The mode every session in this build starts in; see [CustomStudyPolicy] for the gate. */
        val DEFAULT: AnkiStudyMode = Normal
    }
}

/**
 * GATE 20 — what a rating inside a filtered session does to normal scheduling
 * (DECISION-20-06/DECISION-20-07).
 *
 * There is **no `UNKNOWN` value on purpose**. A mode whose rescheduling behaviour is unknown is
 * never represented as a mode at all — it is refused ([CustomStudyPolicy.Refusal]) — so no UI and
 * no documentation can describe it as if it were normal review (INV-20-16/INV-20-17).
 *
 * The two values come from Anki's filtered-deck `reschedule` flag
 * (`rslib/src/scheduler/filtered/custom_study.rs`, 25.09.2):
 *
 * - [NORMAL_RESCHEDULING] (`reschedule = true`): answers apply normal scheduling state,
 *   `reps += 1`, FSRS state and due update normally, and a revlog entry is written.
 * - [NO_RESCHEDULING] (`reschedule = false`, including preview): the card sits in the preview
 *   queue (`queue = 4`, `PreviewRepeat`), a revlog entry is written with `RevlogReviewKind::Filtered`,
 *   `reps` is **not** incremented and the card's normal due/interval are restored when it leaves.
 */
enum class AnkiReschedulingDisclosure {
    NORMAL_RESCHEDULING,
    NO_RESCHEDULING;

    /** User-facing truth for the pre-start summary (DECISION-20-22); never softened. */
    fun schedulingSummary(): String = when (this) {
        NORMAL_RESCHEDULING -> "Answers in this session change your normal scheduling."
        NO_RESCHEDULING -> "Answers in this session do NOT reschedule the cards."
    }
}
