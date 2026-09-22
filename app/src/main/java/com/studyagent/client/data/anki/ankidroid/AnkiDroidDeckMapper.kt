package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.core.anki.AnkiDeckRef

/**
 * Why one deck row could not become an [AnkiDeck] (GATE 05 §38).
 *
 * [structural] problems (a required column is absent from the cursor) mean *every* row is
 * unusable and the whole query is reported as `MalformedResponse`; value problems affect one row
 * only and follow the skip-row-with-diagnostics policy. The [token] is content-free and safe to
 * log — it never carries a deck id or name.
 */
enum class AnkiDroidDeckRowProblem(val structural: Boolean, val token: String) {
    ID_COLUMN_MISSING(structural = true, token = "deck_id_column_missing"),
    NAME_COLUMN_MISSING(structural = true, token = "deck_name_column_missing"),
    ID_INVALID(structural = false, token = "deck_id_invalid"),
    NAME_BLANK(structural = false, token = "deck_name_blank")
}

/** Per-row mapping outcome. Returned, never thrown, so the gateway can apply its row policy. */
sealed interface AnkiDroidDeckRowOutcome {
    data class Valid(
        val deck: AnkiDeck,
        /** `deck_count` was present and parsed into [AnkiDeck.counts]. */
        val countsParsed: Boolean,
        /** `deck_dyn` was present and parsed into [AnkiDeck.isFiltered]. */
        val filteredParsed: Boolean
    ) : AnkiDroidDeckRowOutcome

    data class Malformed(val problem: AnkiDroidDeckRowProblem) : AnkiDroidDeckRowOutcome
}

/**
 * GATE 05 — maps AnkiDroid `decks` / `selected_deck` rows to domain types (§56, INV-ANKI-DECK-01/02/07).
 *
 * Rules, in order of strictness:
 * - **Identity is `deck_id`, never the name.** The id is read as text and parsed as a positive
 *   `Long` (Anki deck ids are epoch-millisecond longs; the built-in Default deck is `1`). A
 *   missing, `NULL`, non-numeric or non-positive id makes the row [AnkiDroidDeckRowOutcome.Malformed]
 *   — there is no `0`, no `"Unknown"`, no name-derived surrogate (§37).
 * - **`deck_name` is preserved verbatim** — original `::` separators, whitespace, case, Unicode
 *   and RTL text included. Only a blank name is rejected (a blank label cannot be displayed and
 *   would break hierarchy derivation). Hierarchy is *derived later* by `AnkiDeckTreeBuilder`
 *   from `AnkiDeck.path`; `parentRef` stays `null` because the contract carries no parent id
 *   (INV-ANKI-DECK-03).
 * - **Counts are optional and never guessed.** `deck_count` is the text form of the JSON array
 *   `[learn, review, new]` (contract order, verified against `getDeckCountsFromDueTreeNode`).
 *   Anything else — absent column, `NULL`, wrong arity, non-integers, negatives, the nested
 *   array the `selected_deck` row carries — yields `counts = null` (unknown), never zeros (§13).
 *   `totalDue` is left `null`: the backend does not report a total and this layer does not invent
 *   one (§45).
 * - **`deck_dyn` is optional.** Transported as `"true"`/`"false"` (a `Boolean` crossing the
 *   cursor window) — `"1"`/`"0"` are accepted because the contract KDoc documents the flag that
 *   way. Anything else is `isFiltered = null`; filtered status is never inferred from the name.
 * - `deck_desc` and `options` are never read (see `AnkiDroidApiContract`).
 *
 * Pure and JVM-testable: reads go through [AnkiDroidProviderRow] only.
 */
internal object AnkiDroidDeckMapper {

    private val COUNTS_PATTERN = Regex("""^\s*\[\s*(-?\d{1,10})\s*,\s*(-?\d{1,10})\s*,\s*(-?\d{1,10})\s*]\s*$""")

    fun mapDeckRow(
        row: AnkiDroidProviderRow,
        backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
    ): AnkiDroidDeckRowOutcome {
        val idIndex = row.columnIndex(AnkiDroidApiContract.DECK_ID_COLUMN)
        if (idIndex < 0) return AnkiDroidDeckRowOutcome.Malformed(AnkiDroidDeckRowProblem.ID_COLUMN_MISSING)
        val nameIndex = row.columnIndex(AnkiDroidApiContract.DECK_NAME_COLUMN)
        if (nameIndex < 0) return AnkiDroidDeckRowOutcome.Malformed(AnkiDroidDeckRowProblem.NAME_COLUMN_MISSING)

        val deckId = parseDeckId(AnkiDroidMapper.getOptionalString(row, idIndex))
            ?: return AnkiDroidDeckRowOutcome.Malformed(AnkiDroidDeckRowProblem.ID_INVALID)
        val name = AnkiDroidMapper.getOptionalString(row, nameIndex)
        if (name.isNullOrBlank()) return AnkiDroidDeckRowOutcome.Malformed(AnkiDroidDeckRowProblem.NAME_BLANK)

        val countsText = AnkiDroidMapper.getOptionalString(
            row, AnkiDroidMapper.optionalColumnIndex(row, AnkiDroidApiContract.DECK_COUNTS_COLUMN)
        )
        val counts = parseCounts(countsText)

        val filteredText = AnkiDroidMapper.getOptionalString(
            row, AnkiDroidMapper.optionalColumnIndex(row, AnkiDroidApiContract.DECK_DYN_COLUMN)
        )
        val filtered = parseFiltered(filteredText)

        val deck = AnkiDeck(
            ref = AnkiDeckRef(backendId = backendId, deckId = deckId.toString(), collectionKey = null),
            name = name,
            parentRef = null,
            isFiltered = filtered,
            counts = counts
        )
        return AnkiDroidDeckRowOutcome.Valid(
            deck = deck,
            countsParsed = counts != null,
            filteredParsed = filtered != null
        )
    }

    /** `selected_deck` row → identity only; `null` when the row does not carry a usable id. */
    fun mapSelectedDeckRow(
        row: AnkiDroidProviderRow,
        backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
    ): AnkiDeckRef? {
        val idIndex = row.columnIndex(AnkiDroidApiContract.DECK_ID_COLUMN)
        if (idIndex < 0) return null
        val deckId = parseDeckId(AnkiDroidMapper.getOptionalString(row, idIndex)) ?: return null
        return AnkiDeckRef(backendId = backendId, deckId = deckId.toString(), collectionKey = null)
    }

    /** Positive `Long` or `null`. Text-based on purpose: a typed getter would turn garbage into `0`. */
    fun parseDeckId(text: String?): Long? {
        val value = text?.trim()?.toLongOrNull() ?: return null
        return if (value > 0L) value else null
    }

    /**
     * `"[learn, review, new]"` → [AnkiDeckCounts]; anything else → `null` (unknown).
     * Note the contract order: the array is *learn, review, new*, not new, learn, review.
     */
    fun parseCounts(text: String?): AnkiDeckCounts? {
        if (text == null) return null
        val match = COUNTS_PATTERN.matchEntire(text) ?: return null
        val learn = match.groupValues[1].toIntOrNull() ?: return null
        val review = match.groupValues[2].toIntOrNull() ?: return null
        val new = match.groupValues[3].toIntOrNull() ?: return null
        if (learn < 0 || review < 0 || new < 0) return null
        return AnkiDeckCounts(new = new, learning = learn, review = review, totalDue = null)
    }

    /** `"true"`/`"1"` → `true`, `"false"`/`"0"` → `false`, anything else → `null`. */
    fun parseFiltered(text: String?): Boolean? = when (text?.trim()?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}
