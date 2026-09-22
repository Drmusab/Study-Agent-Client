package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiScheduledCard
import com.studyagent.client.core.anki.AnkiSchedulingInfo
import com.studyagent.client.core.models.Rating

/**
 * Why one scheduled-review row could not become an [AnkiScheduledCard] (GATE 06 §51).
 *
 * [structural] problems mean the pinned contract itself is not what this build expects (a column
 * the endpoint must return is absent), so the whole query is reported as `MalformedResponse`;
 * value problems follow the row policy. The [token] is content-free and safe to log — it never
 * carries a note id, an ordinal or a filename.
 */
internal enum class AnkiDroidReviewRowProblem(val structural: Boolean, val token: String) {
    NOTE_ID_COLUMN_MISSING(structural = true, token = "review_note_id_column_missing"),
    CARD_ORD_COLUMN_MISSING(structural = true, token = "review_card_ord_column_missing"),
    NOTE_ID_INVALID(structural = false, token = "review_note_id_invalid"),
    CARD_ORD_INVALID(structural = false, token = "review_card_ord_invalid"),
    BUTTON_COUNT_INVALID(structural = false, token = "review_button_count_invalid")
}

/** Per-row mapping outcome. Returned, never thrown, so the gateway can apply its row policy. */
internal sealed interface AnkiDroidReviewRowOutcome {
    data class Valid(val card: AnkiScheduledCard) : AnkiDroidReviewRowOutcome

    data class Malformed(val problem: AnkiDroidReviewRowProblem) : AnkiDroidReviewRowOutcome
}

/**
 * GATE 06 — maps one `schedule` row to a backend-neutral [AnkiScheduledCard].
 *
 * Strictness follows the consequence of being wrong, not the tidiness of the code:
 *
 * - **Identity is required.** `note_id` and `ord` are read as text and parsed (a typed getter
 *   would turn garbage into `0`). A missing column is a contract mismatch for the *whole* query;
 *   an unreadable value kills *this* row. A card id is never derived from the ordinal, and the
 *   ordinal is never treated as unique on its own — it is only meaningful together with its note
 *   (§17/§18/§43/§44).
 * - **Rating options must be recognisable or absent.** The endpoint documents 2..4 buttons; this
 *   build can only *name* the four-button ordering it verified against the pinned scheduler enum
 *   (`again = 0, hard = 1, good = 2, easy = 3`). Any other in-range count becomes
 *   [AnkiRatingOptions.Unmapped] and a degradation token: the card is still perfectly
 *   identifiable, and guessing which two of the four buttons a bare `2` means would be inventing
 *   a scheduling mapping (§20-§22, INV-ANKI-REV-09). An out-of-range count is a malformed answer,
 *   not a crash (§52).
 * - **Interval labels are presentation data.** They are parsed leniently and attached to ratings
 *   only when both the labels and the ratings are trustworthy. A label list whose length
 *   disagrees with `button_count` is *dropped*, not fatal (§23/§53): the buttons a user may press
 *   are what the UI keys on, and losing a display string is a smaller harm than refusing to show
 *   a card that Anki has already scheduled. The policy is recorded as a token so it is visible.
 * - **Media is best-effort.** A malformed media list degrades to "no media known" plus a
 *   diagnostic token; it never destroys a card whose identity is sound (§54). Entries are
 *   *references* — a name the backend owns — never a path and never an openable file (§25).
 *
 * Pure and JVM-testable: reads go through [AnkiDroidProviderRow] only, and the JSON the provider
 * transports as text is read by [AnkiDroidReviewJson], not by a framework parser.
 */
internal object AnkiDroidReviewMapper {

    /**
     * The scheduler's own button order, at index `i` for `next_review_times[i]`.
     *
     * Provenance: the provider fills the label array with
     * `nextIvlStr(card, CardAnswer.Rating.forNumber(i))` for `i` in `0 until buttonCount`, and the
     * pinned `anki.scheduler.CardAnswer.Rating` proto enum is `AGAIN = 0, HARD = 1, GOOD = 2,
     * EASY = 3`. This is a *verified* mapping for the four-button case, not a convention: it is
     * the reason nothing else may be mapped to ratings by position (§22).
     */
    val BUTTON_ORDER: List<Rating> = listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY)

    fun mapScheduledRow(
        row: AnkiDroidProviderRow,
        deckRef: AnkiDeckRef,
        backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
    ): AnkiDroidReviewRowOutcome {
        val noteIdIndex = row.columnIndex(AnkiDroidApiContract.REVIEW_NOTE_ID_COLUMN)
        if (noteIdIndex < 0) {
            return AnkiDroidReviewRowOutcome.Malformed(AnkiDroidReviewRowProblem.NOTE_ID_COLUMN_MISSING)
        }
        val cardOrdIndex = row.columnIndex(AnkiDroidApiContract.REVIEW_CARD_ORD_COLUMN)
        if (cardOrdIndex < 0) {
            return AnkiDroidReviewRowOutcome.Malformed(AnkiDroidReviewRowProblem.CARD_ORD_COLUMN_MISSING)
        }

        val noteId = parsePositiveLong(AnkiDroidMapper.getOptionalString(row, noteIdIndex))
            ?: return AnkiDroidReviewRowOutcome.Malformed(AnkiDroidReviewRowProblem.NOTE_ID_INVALID)
        val cardOrd = parseNonNegativeInt(AnkiDroidMapper.getOptionalString(row, cardOrdIndex))
            ?: return AnkiDroidReviewRowOutcome.Malformed(AnkiDroidReviewRowProblem.CARD_ORD_INVALID)

        val degradations = ArrayList<String>(2)

        val buttons = readButtonCount(row)
        if (buttons is ButtonCount.Invalid) {
            // A negative or absurd count is a malformed answer, never something to render (§52).
            return AnkiDroidReviewRowOutcome.Malformed(AnkiDroidReviewRowProblem.BUTTON_COUNT_INVALID)
        }
        val declaredButtonCount = (buttons as ButtonCount.Value).count
        val ratingOptions = ratingOptionsFor(declaredButtonCount, degradations)

        val scheduling = schedulingFor(row, declaredButtonCount, ratingOptions, degradations)
        val media = mediaFor(row, degradations)

        val collectionKey = deckRef.collectionKey
        val card = AnkiScheduledCard(
            ref = AnkiCardRef(
                backendId = backendId,
                cardId = null,
                noteId = noteId,
                cardOrd = cardOrd,
                collectionKey = collectionKey
            ),
            noteRef = AnkiNoteRef(backendId = backendId, noteId = noteId, collectionKey = collectionKey),
            deckRef = deckRef,
            ratingOptions = ratingOptions,
            scheduling = scheduling,
            media = media,
            degradations = degradations.toList()
        )
        return AnkiDroidReviewRowOutcome.Valid(card)
    }

    /** Three-way reading of `button_count`: a count, intentionally absent, or answered nonsense. */
    private sealed interface ButtonCount {
        data class Value(val count: Int?) : ButtonCount
        data object Invalid : ButtonCount
    }

    /**
     * Reads `button_count` without ever turning a nonsense cell into a plausible one.
     *
     * An absent or empty cell is *intentionally* `Value(null)` (the column may be missing on a
     * version skew) and degrades. A cell that reads as a negative number, or as a number far
     * beyond any real Anki card, is [ButtonCount.Invalid]: the provider answered something that
     * cannot be a button count, so the row is refused rather than rendered (§52).
     */
    private fun readButtonCount(row: AnkiDroidProviderRow): ButtonCount {
        val index = AnkiDroidMapper.optionalColumnIndex(row, AnkiDroidApiContract.REVIEW_BUTTON_COUNT_COLUMN)
        val text = AnkiDroidMapper.getOptionalString(row, index)?.trim()
        if (text.isNullOrEmpty()) return ButtonCount.Value(null)
        val value = text.toIntOrNull() ?: return ButtonCount.Invalid
        if (value < 0 || value > SANE_MAX_BUTTON_COUNT) return ButtonCount.Invalid
        return ButtonCount.Value(value)
    }

    /**
     * Maps the provider's `button_count` to a nameable set of buttons, or degrades.
     *
     * A zero count yields [AnkiRatingOptions.Unmapped] with a token rather than an exception: the
     * endpoint is documented to always send this column, so its absence is a version-skew symptom
     * that must not take the card away with it (§51/§52).
     */
    private fun ratingOptionsFor(
        declaredButtonCount: Int?,
        degradations: MutableList<String>
    ): AnkiRatingOptions {
        if (declaredButtonCount == BUTTON_ORDER.size) return AnkiRatingOptions.Known(BUTTON_ORDER)
        degradations.add(DEGRADATION_BUTTON_COUNT_UNMAPPED)
        return AnkiRatingOptions.Unmapped(declaredButtonCount ?: 0)
    }

    /**
     * Interval labels, mapped to ratings only when the array length matches the button count
     * (§23/§53). Every mismatch is a degradation, never a failed turn.
     */
    private fun schedulingFor(
        row: AnkiDroidProviderRow,
        declaredButtonCount: Int?,
        ratingOptions: AnkiRatingOptions,
        degradations: MutableList<String>
    ): AnkiSchedulingInfo? {
        val timesIndex = AnkiDroidMapper.optionalColumnIndex(
            row, AnkiDroidApiContract.REVIEW_NEXT_REVIEW_TIMES_COLUMN
        )
        val labels = AnkiDroidReviewJson.parseScalarArray(AnkiDroidMapper.getOptionalString(row, timesIndex))
        if (labels == null) {
            if (timesIndex >= 0 && declaredButtonCount != null) {
                // The column was requested and the endpoint documents it as always present.
                degradations.add(DEGRADATION_INTERVALS_UNPARSEABLE)
            }
            return null
        }
        if (ratingOptions !is AnkiRatingOptions.Known) return AnkiSchedulingInfo()
        if (labels.size != declaredButtonCount) {
            degradations.add(DEGRADATION_INTERVALS_ARITY_MISMATCH)
            return AnkiSchedulingInfo()
        }
        val nextReviewTimes = LinkedHashMap<Rating, String>(ratingOptions.buttonCount)
        ratingOptions.ratings.forEachIndexed { index, rating ->
            labels.getOrNull(index)?.let { label -> nextReviewTimes[rating] = label }
        }
        return AnkiSchedulingInfo(nextReviewTimes = nextReviewTimes)
    }

    /**
     * Media names become [AnkiMediaRef.BackendStream]: the provider names files the *backend*
     * owns, and resolving one into bytes is GATE 09's problem (§24/§25).
     */
    private fun mediaFor(
        row: AnkiDroidProviderRow,
        degradations: MutableList<String>
    ): List<AnkiMediaRef> {
        val mediaIndex = AnkiDroidMapper.optionalColumnIndex(
            row, AnkiDroidApiContract.REVIEW_MEDIA_FILES_COLUMN
        )
        if (mediaIndex < 0) return emptyList()
        val names = AnkiDroidReviewJson.parseScalarArray(AnkiDroidMapper.getOptionalString(row, mediaIndex))
        if (names == null) {
            degradations.add(DEGRADATION_MEDIA_UNPARSEABLE)
            return emptyList()
        }
        return names
            .filterNot { it.isNullOrBlank() }
            .map { name -> AnkiMediaRef.BackendStream(streamId = name!!) }
    }

    fun parsePositiveLong(text: String?): String? {
        val value = text?.trim()?.toLongOrNull() ?: return null
        return if (value > 0L) value.toString() else null
    }

    private fun parseNonNegativeInt(text: String?): Int? {
        val value = text?.trim()?.toIntOrNull() ?: return null
        return if (value >= 0) value else null
    }

    const val DEGRADATION_BUTTON_COUNT_UNMAPPED: String = "review_button_count_unmapped"
    const val DEGRADATION_INTERVALS_UNPARSEABLE: String = "review_next_review_times_unparseable"
    const val DEGRADATION_INTERVALS_ARITY_MISMATCH: String = "review_next_review_times_arity_mismatch"
    const val DEGRADATION_MEDIA_UNPARSEABLE: String = "review_media_unparseable"

    /**
     * Beyond this, a `button_count` is not a plausible card state (Anki's own answer buttons are
     * four). Kept separate from the *documented* range in [AnkiDroidApiContract] so that widening
     * the accepted range is a deliberate edit rather than a side effect.
     */
    private const val SANE_MAX_BUTTON_COUNT: Int = 8
}
