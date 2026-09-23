package com.studyagent.client.core.anki

/**
 * GATE 07 — a card flag marker as a backend-neutral concept (STEP 31/§32).
 *
 * One mapping from a backend's raw flag value lives here and nowhere else (STEP 31: "Do not
 * spread numeric flag mappings through code"). The numbering is the Anki ecosystem's standard
 * flag coding, verified against AnkiDroid v2.24.1's own `AnkiDroid/src/main/java/com/ichi2/anki/Flag.kt`
 * (`NONE(0), RED(1), ORANGE(2), GREEN(3), BLUE(4), PINK(5), TURQUOISE(6), PURPLE(7)`) — the same
 * codes Anki Desktop stores in the cards table.
 *
 * Honesty note: the *pinned AnkiDroid public provider contract* does NOT expose flags at
 * v2.24.1 (there is no FLAGS column in `FlashCardsContract.Card`, verified against both the
 * contract and `CardContentProvider.addCardToCursor`), so the AnkiDroid gateway can never supply
 * one and always leaves `AnkiRenderedCard.flag` null. This mapper exists so that backends which
 * *do* expose flags map them through one safe function instead of inventing parallel mappings.
 *
 * Unknown future flag values degrade to [UNKNOWN] (STEP 32) — never to [NONE], which would claim
 * "the backend said unflagged" when it said something this build cannot name.
 */
enum class AnkiFlag {
    NONE,
    RED,
    ORANGE,
    GREEN,
    BLUE,
    PINK,
    TURQUOISE,
    PURPLE,

    /**
     * The backend reported a flag value this build cannot name. Distinct from [NONE]: the card IS
     * flagged, just not recognisably (STEP 32 / test M — never silently map to NONE).
     */
    UNKNOWN;

    companion object {
        /**
         * Maps a backend's raw flag code to the domain flag.
         *
         * `null` (the backend said nothing) stays `null` — "missing is not empty"
         * (INV-ANKI-CARD-13): an absent flag is not a claim of [NONE]. A code outside the
         * verified 0..7 range becomes [UNKNOWN], never a guessed colour and never [NONE].
         */
        fun fromBackendCode(code: Int?): AnkiFlag? = when (code) {
            null -> null
            0 -> NONE
            1 -> RED
            2 -> ORANGE
            3 -> GREEN
            4 -> BLUE
            5 -> PINK
            6 -> TURQUOISE
            7 -> PURPLE
            else -> UNKNOWN
        }
    }
}
