package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardMetadata
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiFsrsInfo
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiSchedulingInfo

/**
 * Why one card row could not become an [AnkiRenderedCard] (GATE 07 §73/§74).
 *
 * [structural] problems mean the row cannot carry a trustworthy card identity at all — the
 * contract this build pinned and the provider's answer disagree — so the whole query is reported
 * as `MalformedResponse`. The [token] is content-free and safe to log; it never carries a card
 * id, a note id, a template name or any content.
 */
internal enum class AnkiDroidCardRowProblem(val structural: Boolean, val token: String) {
    IDENTITY_COLUMN_MISSING(structural = true, token = "card_identity_column_missing"),
    IDENTITY_UNREADABLE(structural = true, token = "card_identity_unreadable"),
    QUESTION_CONTENT_MISSING(structural = true, token = "card_question_content_missing")
}

/** Per-row mapping outcome. Returned, never thrown, so the gateway can apply its row policy. */
internal sealed interface AnkiDroidCardRowOutcome {
    data class Valid(val card: AnkiRenderedCard) : AnkiDroidCardRowOutcome
    data class Malformed(val problem: AnkiDroidCardRowProblem) : AnkiDroidCardRowOutcome
}

/**
 * GATE 07 — maps one card row to a backend-neutral [AnkiRenderedCard] (STEP 72/§73).
 *
 * Strictness follows the consequence of being wrong, not the tidiness of the code:
 *
 * - **Identity is strict (STEP 15/INV-ANKI-CARD-15).** `_id`, `note_id` and `ord` are read as
 *   text and parsed (a typed getter would turn garbage into `0`). The row must yield at least
 *   one complete addressing path — a card id, or note id + ordinal — and must agree with the
 *   `AnkiCardRef` construction rules; otherwise the row is [AnkiDroidCardRowProblem.IDENTITY_UNREADABLE]
 *   and no card is published. A missing id is never invented (INV-ANKI-CARD-04): an unreadable
 *   card id degrades to `cardId = null` (still addressable by note + ordinal) with a token.
 *   The gateway separately verifies that the row *confirms the scheduled identity* (STEP 54).
 * - **Content is lenient and lossless (STEP 20-§22/§58).** The five representations are copied
 *   **verbatim** — no HTML stripping, no entity decoding, no whitespace collapsing, no Unicode
 *   normalization, no template expansion (INV-ANKI-CARD-08/28/§62-§65). `null` means the
 *   backend could not supply the value; `""` means it supplied an empty rendering — the two are
 *   never conflated (INV-ANKI-CARD-13). There is no HTML-stripping fallback when simple text is
 *   missing: the card becomes visual-only and the gap is recorded (STEP 76).
 * - **Question availability is required (STEP 74).** A row with neither `question` nor
 *   `question_simple` cannot be a reviewable card and is
 *   [AnkiDroidCardRowProblem.QUESTION_CONTENT_MISSING] — never an invented placeholder.
 * - **Answer emptiness is not corruption (STEP 75).** An empty or absent answer is preserved as
 *   reported; only the question decides validity.
 * - **Optional metadata is optional (STEP 16/INV-ANKI-CARD-14).** Template name, deck ids,
 *   reps/lapses/interval, queue/type state and FSRS facts degrade individually (each to `null`/
 *   `UNKNOWN` plus a token) and never take the card down with them. FSRS values are informational
 *   only (INV-ANKI-CARD-19).
 * - **Queue state is mapped, not exposed (STEP 19/§33).** The documented `queue` code wins over
 *   `type` (queue additionally encodes suspended/buried/preview display states); anything
 *   outside the documented codes becomes [AnkiCardQueueState.UNKNOWN] with a token — never a
 *   guessed state and never a raw integer above this layer.
 *
 * Pure and JVM-testable: reads go through [AnkiDroidProviderRow] only. Numbers and floats are
 * read as text and parsed (the transported cell may be `"3"` or `3.0`), exactly like the deck
 * mapper — never via typed getters that silently substitute `0`.
 */
internal object AnkiDroidCardMapper {

    fun mapCardRow(
        row: AnkiDroidProviderRow,
        request: AnkiCardRef,
        backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
    ): AnkiDroidCardRowOutcome {
        val idIndex = row.columnIndex(AnkiDroidApiContract.CARD_ID_COLUMN)
        val noteIdIndex = row.columnIndex(AnkiDroidApiContract.CARD_NOTE_ID_COLUMN)
        val ordIndex = row.columnIndex(AnkiDroidApiContract.CARD_ORD_COLUMN)
        if (idIndex < 0 && noteIdIndex < 0 && ordIndex < 0) {
            // Not one identity column came back: the pinned contract is not what this build
            // expects, or the mapper and the projection disagree. Either way the whole query is
            // untrustworthy (structural).
            return AnkiDroidCardRowOutcome.Malformed(AnkiDroidCardRowProblem.IDENTITY_COLUMN_MISSING)
        }

        val degradations = ArrayList<String>(3)

        val cardId = parsePositiveLong(AnkiDroidMapper.getOptionalString(row, idIndex))
        val noteId = parsePositiveLong(AnkiDroidMapper.getOptionalString(row, noteIdIndex))
        val cardOrd = parseNonNegativeInt(AnkiDroidMapper.getOptionalString(row, ordIndex))
            ?.takeIf { noteId != null } // an ordinal is only meaningful with its note (AnkiCardRef)

        if (cardId == null && idIndex >= 0) degradations.add(DEG_CARD_ID_UNREADABLE)
        if (noteId == null && noteIdIndex >= 0) degradations.add(DEG_NOTE_IDENTITY_UNREADABLE)

        val ref = try {
            AnkiCardRef(
                backendId = backendId,
                cardId = cardId,
                noteId = noteId,
                cardOrd = cardOrd,
                collectionKey = request.collectionKey
            )
        } catch (_: IllegalArgumentException) {
            // No complete addressing path survived — the identity of this row cannot be trusted
            // (INV-ANKI-CARD-15). Never a surrogate id.
            return AnkiDroidCardRowOutcome.Malformed(AnkiDroidCardRowProblem.IDENTITY_UNREADABLE)
        }

        // ---- content channels (verbatim — STEP 20) -------------------------------------------
        val questionHtml = AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_QUESTION_COLUMN))
        val answerHtml = AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_ANSWER_COLUMN))
        val questionText = AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_QUESTION_SIMPLE_COLUMN))
        val answerText = AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_ANSWER_SIMPLE_COLUMN))
        val pureAnswerText = AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_ANSWER_PURE_COLUMN))

        // STEP 74 — some usable question representation must exist; otherwise this is not a
        // reviewable card and no placeholder may be invented for it.
        if (questionHtml == null && questionText == null) {
            return AnkiDroidCardRowOutcome.Malformed(AnkiDroidCardRowProblem.QUESTION_CONTENT_MISSING)
        }
        // STEP 76 — HTML without simple text is a visual-only card: speech is unavailable and no
        // HTML-derived substitute is fabricated. The gap is a recorded degradation, not a failure.
        if (questionText == null && questionHtml != null) degradations.add(DEG_SPEECH_TEXT_UNAVAILABLE)
        if (answerText == null && answerHtml != null) degradations.add(DEG_SPEECH_TEXT_UNAVAILABLE)

        // ---- optional metadata (lenient — STEP 16) --------------------------------------------
        val templateName = AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_NAME_COLUMN))
            ?.takeIf { it.isNotEmpty() }

        val deckId = parsePositiveLong(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_DECK_ID_COLUMN)))
        val deckRef = deckId?.let { AnkiDeckRef(backendId = backendId, deckId = it, collectionKey = request.collectionKey) }
        if (deckRef == null) degradations.add(DEG_DECK_ID_UNREADABLE)

        val originalDeckId = parsePositiveLong(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_ORIGINAL_DECK_ID_COLUMN)))
        // `0` is the contract's "not in a filtered deck" sentinel and maps to null (unknown-free):
        // a positive id is the home deck worth preserving for later Card Details (STEP 30).
        val originalDeckRef = originalDeckId?.let {
            AnkiDeckRef(backendId = backendId, deckId = it, collectionKey = request.collectionKey)
        }

        val queueState = mapQueueState(
            queueCode = parseBoundedInt(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_QUEUE_COLUMN))),
            typeCode = parseBoundedInt(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_TYPE_COLUMN))),
            degradations = degradations
        )

        val scheduling = schedulingFor(row, degradations)

        val card = AnkiRenderedCard(
            ref = ref,
            questionHtml = questionHtml,
            answerHtml = answerHtml,
            questionText = questionText,
            answerText = answerText,
            pureAnswerText = pureAnswerText,
            // The card surface exposes no media column at v2.24.1 — media references come from
            // the GATE 06 review-info surface and are merged at attach time (STEP 36).
            media = emptyList(),
            scheduling = scheduling,
            metadata = AnkiCardMetadata(
                templateName = templateName,
                queueState = queueState,
                originalDeckRef = originalDeckRef
            ),
            noteRef = noteId?.let { AnkiNoteRef(backendId = backendId, noteId = it, collectionKey = request.collectionKey) },
            deckRef = deckRef,
            // The pinned contract exposes no flags column (STEP 31): always null on this backend.
            flag = null,
            degradations = degradations.toList()
        )
        return AnkiDroidCardRowOutcome.Valid(card)
    }

    /**
     * Fused card/queue state (STEP 33). The documented `queue` code wins over `type` because
     * queue additionally encodes the temporary display states (suspended, buried, preview) that
     * `type` cannot express. Unknown codes — including the documented preview code, which has no
     * domain equivalent in this build — degrade to [AnkiCardQueueState.UNKNOWN] with a token
     * (test N); they are never guessed into a known state.
     */
    internal fun mapQueueState(
        queueCode: Int?,
        typeCode: Int?,
        degradations: MutableList<String>
    ): AnkiCardQueueState {
        if (queueCode != null) {
            return when (queueCode) {
                AnkiDroidApiContract.CARD_QUEUE_MANUALLY_BURIED,
                AnkiDroidApiContract.CARD_QUEUE_SIBLING_BURIED -> AnkiCardQueueState.BURIED
                AnkiDroidApiContract.CARD_QUEUE_SUSPENDED -> AnkiCardQueueState.SUSPENDED
                AnkiDroidApiContract.CARD_QUEUE_NEW -> AnkiCardQueueState.NEW
                AnkiDroidApiContract.CARD_QUEUE_LEARNING -> AnkiCardQueueState.LEARNING
                AnkiDroidApiContract.CARD_QUEUE_REVIEW -> AnkiCardQueueState.REVIEW
                AnkiDroidApiContract.CARD_QUEUE_DAY_LEARNING -> AnkiCardQueueState.RELEARNING
                else -> {
                    degradations.add(DEG_QUEUE_STATE_UNMAPPED)
                    AnkiCardQueueState.UNKNOWN
                }
            }
        }
        if (typeCode != null) {
            return when (typeCode) {
                AnkiDroidApiContract.CARD_TYPE_NEW -> AnkiCardQueueState.NEW
                AnkiDroidApiContract.CARD_TYPE_LEARNING -> AnkiCardQueueState.LEARNING
                AnkiDroidApiContract.CARD_TYPE_REVIEW -> AnkiCardQueueState.REVIEW
                AnkiDroidApiContract.CARD_TYPE_RELEARNING -> AnkiCardQueueState.RELEARNING
                else -> {
                    degradations.add(DEG_QUEUE_STATE_UNMAPPED)
                    AnkiCardQueueState.UNKNOWN
                }
            }
        }
        degradations.add(DEG_QUEUE_STATE_UNMAPPED)
        return AnkiCardQueueState.UNKNOWN
    }

    /**
     * Stored scheduling facts (STEP 34) — informational only (INV-ANKI-CARD-18/19). Built when
     * at least one field is present; `null` means the provider said nothing. Never combined with
     * the GATE 06 label surface inside one instance (INV-ANKI-CARD-24).
     */
    private fun schedulingFor(
        row: AnkiDroidProviderRow,
        degradations: MutableList<String>
    ): AnkiSchedulingInfo? {
        val reps = parseBoundedInt(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_REPS_COLUMN)))
        val lapses = parseBoundedInt(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_LAPSES_COLUMN)))
        val intervalDays = parseBoundedInt(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_INTERVAL_COLUMN)))
        val lastReviewEpochSeconds = parseEpochSeconds(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_LAST_REVIEW_TIME_COLUMN)))
        val stability = parseDouble(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_FSRS_STABILITY_COLUMN)))
        val difficulty = parseDouble(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_FSRS_DIFFICULTY_COLUMN)))
        val retention = parseDouble(AnkiDroidMapper.getOptionalString(row, optional(row, AnkiDroidApiContract.CARD_FSRS_DESIRED_RETENTION_COLUMN)))

        val fsrs = if (stability != null || difficulty != null || retention != null) {
            AnkiFsrsInfo(stability = stability, difficulty = difficulty, desiredRetention = retention)
        } else {
            null
        }

        if (reps == null && lapses == null && intervalDays == null && lastReviewEpochSeconds == null &&
            fsrs == null
        ) {
            return null
        }
        return AnkiSchedulingInfo(
            fsrs = fsrs,
            reps = reps,
            lapses = lapses,
            intervalDays = intervalDays,
            lastReviewEpochSeconds = lastReviewEpochSeconds
        )
    }

    private fun optional(row: AnkiDroidProviderRow, column: String): Int =
        AnkiDroidMapper.optionalColumnIndex(row, column)

    /**
     * Anki ids are positive 64-bit values; blank, zero and negatives are not identities
     * (INV-ANKI-CARD-04). Read as text so a transported `3.0` or `"3"` parses but `""` does not
     * silently become `0`. The canonical decimal form is returned so equal ids compare equally.
     */
    internal fun parsePositiveLong(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val value = trimmed.toLongOrNull()
            ?: trimmed.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it.isFinite() }?.toLong()
            ?: return null
        return if (value > 0L) value.toString() else null
    }

    /** Optional counts/indices: a non-negative int, or null when the cell is absent or garbage. */
    internal fun parseNonNegativeInt(text: String?): Int? {
        val value = parseBoundedInt(text) ?: return null
        return if (value >= 0) value else null
    }

    private fun parseBoundedInt(text: String?): Int? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        return trimmed.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            ?: trimmed.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
                ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    private fun parseEpochSeconds(text: String?): Long? {
        val value = text?.trim()?.toLongOrNull() ?: return null
        return if (value >= 0L) value else null
    }

    private fun parseDouble(text: String?): Double? {
        val value = text?.trim()?.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    const val DEG_CARD_ID_UNREADABLE: String = "card_id_unreadable"
    const val DEG_NOTE_IDENTITY_UNREADABLE: String = "card_note_identity_unreadable"
    const val DEG_SPEECH_TEXT_UNAVAILABLE: String = "card_speech_text_unavailable"
    const val DEG_DECK_ID_UNREADABLE: String = "card_deck_id_unreadable"
    const val DEG_QUEUE_STATE_UNMAPPED: String = "card_queue_state_unmapped"
}
