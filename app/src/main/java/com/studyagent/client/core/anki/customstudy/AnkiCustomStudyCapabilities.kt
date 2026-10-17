package com.studyagent.client.core.anki.customstudy

/**
 * GATE 20 — granular custom-study capability truth (DECISION-20-21).
 *
 * A coarse `CUSTOM_STUDY = true` flag cannot express "this backend can review an existing filtered
 * deck but cannot prove what an answer did inside one", and a capability that lies is worse than a
 * missing one: it is what would let a session start that can never finish honestly. Every field
 * describes one independent fact, and the **constructor refuses combinations that the gate's own
 * invariants forbid**, so a backend cannot advertise filtered review without also proving the two
 * properties that make it safe:
 *
 * 1. `canReviewFilteredDeck` requires [canProveFilteredRatingCommit] — without a provable rating
 *    commit, GATE 11 leaves every rating AMBIGUOUS, progression stops at the first card, and the
 *    session is a dead end (INV-20-11 / DECISION-20-16).
 * 2. `canReviewFilteredDeck` requires [canExposeReschedulingSemantics] — without knowing whether
 *    answers reschedule cards, the UI cannot make the disclosure DECISION-20-22 demands, and
 *    no-reschedule and normal-reschedule modes would be conflated (INV-20-16).
 *
 * The default is "cannot do anything", which is always the safe answer and is what every adapter
 * inherits until it has audited its own public contract ([NONE] is the value
 * [com.studyagent.client.core.anki.AnkiBackend.customStudyCapabilities] returns by default).
 *
 * These flags are a *claim by the wired adapter*, exactly like
 * [com.studyagent.client.core.anki.AnkiCapabilities]: never inferred from the backend's name or
 * from what the underlying application could do in principle (INV-20-13).
 */
data class AnkiCustomStudyCapabilities(
    /** Can review an **existing** backend-native filtered/dynamic deck. Never means "can create one". */
    val canReviewFilteredDeck: Boolean = false,
    /**
     * Can authoritatively prove whether a rating inside a filtered deck was applied. Without it,
     * GATE 11 classifies every filtered-deck answer as `OutcomeUnknown`, which blocks progression
     * with a durable AMBIGUOUS commit and is never replayed.
     */
    val canProveFilteredRatingCommit: Boolean = false,
    /**
     * Can disclose, **before** a session starts, whether answers inside the filtered deck
     * reschedule cards ([AnkiReschedulingDisclosure]). A per-card observation after the fact is
     * not enough: the user is told the truth before the first rating, not after it.
     */
    val canExposeReschedulingSemantics: Boolean = false,
    /** Can create a backend-native custom-study / filtered-deck object (DECISION-20-11/20-12). */
    val canCreateFilteredSession: Boolean = false,
    /** Can rebuild/refresh an existing filtered deck (DECISION-20-13). */
    val canRebuildFilteredSession: Boolean = false,
    /** Can empty/end/delete an existing filtered deck and restore its cards (DECISION-20-14). */
    val canEndFilteredSession: Boolean = false,
    /** Can confine a session to one deck. */
    val canFilterByDeck: Boolean = false,
    /** Can exclude descendant decks from a deck scope. */
    val canExcludeChildDecks: Boolean = false,
    /** Can confine a session to cards carrying tags. */
    val canFilterByTag: Boolean = false,
    /** Can confine a session to card states (new / learning / review / due). */
    val canFilterByState: Boolean = false,
    /** Can confine a session by flag. */
    val canFilterByFlag: Boolean = false,
    /** Can cap the number of cards the session draws (membership limit, not a stop condition). */
    val canLimitCardCount: Boolean = false,
    /** Can choose the order cards enter the session. */
    val canSelectOrdering: Boolean = false,
    /** Can choose "answers do not reschedule cards" for a session it creates. */
    val canSelectNoReschedule: Boolean = false,
    /** Can study cards without changing scheduling at all (preview-only). */
    val canPreviewOnly: Boolean = false
) {
    init {
        require(!canReviewFilteredDeck || canProveFilteredRatingCommit) {
            "Filtered review requires a provable rating commit: GATE 11 would leave every rating ambiguous"
        }
        require(!canReviewFilteredDeck || canExposeReschedulingSemantics) {
            "Filtered review requires disclosed rescheduling semantics: no-reschedule must never be conflated"
        }
        require(!canExcludeChildDecks || canFilterByDeck) {
            "Excluding child decks requires deck scoping"
        }
        require(!canPreviewOnly || canSelectNoReschedule) {
            "Preview-only study is the no-reschedule mode; it cannot exist without it"
        }
        require(!canCreateFilteredSession || canFilterByDeck) {
            "A created session must at least be able to scope itself to a deck"
        }
    }

    /** True when anything at all beyond ordinary deck review is available on this backend. */
    val supportsAnyCustomStudy: Boolean
        get() = canReviewFilteredDeck || canCreateFilteredSession || canFilterByTag ||
            canFilterByState || canFilterByFlag || canLimitCardCount || canSelectOrdering ||
            canPreviewOnly

    /**
     * The filter vocabulary this backend can honour (DECISION-20-04). Empty means "the backend
     * offers no filter at all", which is the truthful answer for AnkiDroid's public provider.
     */
    fun supportedFilterVocabulary(): Set<CustomStudyFilterDimension> = buildSet {
        if (canFilterByDeck) add(CustomStudyFilterDimension.DECK)
        if (canExcludeChildDecks) add(CustomStudyFilterDimension.EXCLUDE_CHILD_DECKS)
        if (canFilterByTag) add(CustomStudyFilterDimension.TAG)
        if (canFilterByState) add(CustomStudyFilterDimension.CARD_STATE)
        if (canFilterByFlag) add(CustomStudyFilterDimension.FLAG)
        if (canLimitCardCount) add(CustomStudyFilterDimension.CARD_LIMIT)
        if (canSelectOrdering) add(CustomStudyFilterDimension.ORDERING)
        if (canSelectNoReschedule) add(CustomStudyFilterDimension.RESCHEDULING)
    }

    companion object {
        /** The safe default: a backend that has audited nothing claims nothing. */
        val NONE: AnkiCustomStudyCapabilities = AnkiCustomStudyCapabilities()
    }
}

/** One dimension of the locked filter vocabulary (DECISION-20-04). Display order is declaration order. */
enum class CustomStudyFilterDimension {
    DECK,
    EXCLUDE_CHILD_DECKS,
    TAG,
    CARD_STATE,
    FLAG,
    CARD_LIMIT,
    ORDERING,
    RESCHEDULING
}
