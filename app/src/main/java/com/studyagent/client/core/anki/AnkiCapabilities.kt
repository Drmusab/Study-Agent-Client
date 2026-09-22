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
    /** Can serve due cards and commit ratings (the minimum viable backend). */
    val review: Boolean = false,
    val deckListing: Boolean = false,
    val renderedCards: Boolean = false,
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
