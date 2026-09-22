package com.studyagent.client.core.anki

/**
 * GATE 01 contract — what one Anki backend can actually do (§16).
 *
 * UI and policy must consult these flags, never infer capability from the
 * backend name (INV-ANKI: capability truthfulness; §72 feature fallback).
 * A backend reporting `review = true` but `editNotes = false` is fully usable —
 * the edit affordance hides, the backend is not "unavailable".
 *
 * `capabilities` is deliberately flat and small. New capabilities are added
 * as new flags (defaulting to `false`, which is always the safe answer for a
 * backend that cannot do something).
 */
data class AnkiCapabilities(
    /**
     * Can serve due cards **and commit ratings** — the complete review loop (the minimum viable
     * backend).
     *
     * GATE 06 split this from [scheduledReview] rather than widening it: an implementation that
     * can ask the scheduler for the next card but cannot yet write a rating is genuinely usable
     * and genuinely *not* able to run a review loop, and a single flag would have had to lie about
     * one of the two (§75/§146/§147).
     */
    val review: Boolean = false,
    /**
     * Can ask the backend's scheduler which card to review next and represent it
     * (GATE 06 — scheduled review without rating mutation). Implies nothing about [review].
     */
    val scheduledReview: Boolean = false,
    /** Can list decks with backend-qualified identity (GATE 05 — read-only deck foundation). */
    val deckListing: Boolean = false,
    /**
     * Deck due counts are exposed *and their semantics were verified against the backend*.
     * A backend may still populate `AnkiDeck.counts` best-effort while this stays `false`; UI
     * must treat such counts as advisory and nullable (GATE 05 §13/§45).
     */
    val deckCounts: Boolean = false,
    val renderedCards: Boolean = false,
    val reviewIntervals: Boolean = false,
    val media: Boolean = false,
    val flags: Boolean = false,
    val bury: Boolean = false,
    /** Named `suspendCards` because `suspend` is a Kotlin keyword (Anki "suspend card"). */
    val suspendCards: Boolean = false,
    val editNotes: Boolean = false,
    val createNotes: Boolean = false,
    val search: Boolean = false
) {
    companion object {
        /** Safe default for a backend whose probe has not completed. */
        val NONE = AnkiCapabilities()
    }
}
