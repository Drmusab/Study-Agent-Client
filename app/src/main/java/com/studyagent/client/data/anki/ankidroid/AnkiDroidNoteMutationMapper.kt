package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.edit.BackendNoteMutationRequest
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationStep

/** One provider write, fully typed. Column and path names stay below the gateway. */
data class AnkiDroidNoteWrite(
    val path: String,
    val values: List<ProviderValue>,
    /** The row count a complete write reports: one per key for `notes/<id>`, one for a card. */
    val expectedRows: Int
)

sealed interface AnkiDroidNoteWriteMapping {
    data class Ready(val write: AnkiDroidNoteWrite) : AnkiDroidNoteWriteMapping
    data class Refused(val error: AnkiError) : AnkiDroidNoteWriteMapping
}

/**
 * GATE 17 — pure translation between the domain step and the pinned v2.24.1 provider write, plus
 * the rule that turns a provider answer into a mutation-boundary classification. No Android type is
 * used, so every rule here runs on the JVM.
 *
 * Pinned facts this mapper encodes (AnkiDroid `CardContentProvider.update`):
 *
 * - `notes/<id>` accepts `flds` (full positional overwrite, count must match) and `tags` (full
 *   replacement) in one update. Each key adds one to the returned count; `updateNote` is the only
 *   write and runs after every `require`.
 * - `Utils.splitFields` drops trailing empty entries, so a `flds` value whose LAST field is empty is
 *   refused by the count check. Refused here, before any call.
 * - `notes/<id>/cards/<ord>` accepts only `deck_id`, rejects filtered decks and negative ids, and
 *   returns 1 after `updateCard`.
 */
object AnkiDroidNoteMutationMapper {

    private const val FIELD_SEPARATOR_CHAR = '\u001F'

    fun map(request: BackendNoteMutationRequest): AnkiDroidNoteWriteMapping {
        val noteId = request.noteRef.noteId.toLongOrNull()?.takeIf { it > 0L }
            ?: return AnkiDroidNoteWriteMapping.Refused(AnkiError.InvalidRequest("note_id_not_provider_numeric"))
        val cardNoteId = request.cardRef.noteId
        if (cardNoteId != null && cardNoteId != request.noteRef.noteId) {
            return AnkiDroidNoteWriteMapping.Refused(AnkiError.InvalidRequest("card_note_mismatch"))
        }
        return when (val step = request.step) {
            is NoteMutationStep.UpdateNoteContent -> mapContent(noteId, step)
            is NoteMutationStep.ChangeDeck -> mapDeck(noteId, request.cardRef.cardOrd, step)
        }
    }

    private fun mapContent(noteId: Long, step: NoteMutationStep.UpdateNoteContent): AnkiDroidNoteWriteMapping {
        val values = ArrayList<ProviderValue>(2)
        step.fieldValues?.let { fields ->
            if (fields.isEmpty()) return refused("no_fields")
            if (fields.any { it.indexOf(FIELD_SEPARATOR_CHAR) >= 0 }) return refused("field_contains_separator")
            if (fields.last().isEmpty()) return refused("trailing_empty_field_not_representable")
            values += ProviderValue.StringValue(
                AnkiDroidApiContract.NOTE_FIELDS_COLUMN,
                fields.joinToString(AnkiDroidApiContract.FIELD_SEPARATOR)
            )
        }
        step.tags?.let { tags ->
            if (tags.any { tag -> tag.isEmpty() || tag.any { it.isWhitespace() || it.isISOControl() } }) {
                return refused("invalid_tag")
            }
            values += ProviderValue.StringValue(
                AnkiDroidApiContract.NOTE_TAGS_COLUMN,
                tags.joinToString(AnkiDroidApiContract.TAGS_SEPARATOR)
            )
        }
        if (values.isEmpty()) return refused("empty_content_step")
        return AnkiDroidNoteWriteMapping.Ready(
            AnkiDroidNoteWrite(
                path = "${AnkiDroidApiContract.NOTE_ITEM_PATH}/$noteId",
                values = values,
                expectedRows = values.size
            )
        )
    }

    private fun mapDeck(noteId: Long, cardOrd: Int?, step: NoteMutationStep.ChangeDeck): AnkiDroidNoteWriteMapping {
        val ord = cardOrd?.takeIf { it >= 0 } ?: return refused("card_ord_missing")
        val deckId = step.toDeck.deckId.toLongOrNull()?.takeIf { it > 0L }
            ?: return refused("deck_id_not_provider_numeric")
        return AnkiDroidNoteWriteMapping.Ready(
            AnkiDroidNoteWrite(
                path = "${AnkiDroidApiContract.NOTE_ITEM_PATH}/$noteId/${AnkiDroidApiContract.NOTE_CARDS_PATH}/$ord",
                values = listOf(ProviderValue.LongValue(AnkiDroidApiContract.CARD_DECK_ID_COLUMN, deckId)),
                expectedRows = 1
            )
        )
    }

    /**
     * Maps one provider answer to the mutation boundary. Only two answers are proof of non-application:
     * a refusal before dispatch, and a thrown `SecurityException` / `IllegalArgumentException`, which the
     * pinned provider raises in its `require` and permission guards before the single `updateNote` /
     * `updateCard`. A matching row count is ConfirmedApplied. Everything else is OutcomeUnknown.
     */
    fun classify(dispatch: AnkiDroidNoteWriteDispatch, expectedRows: Int): NoteMutationBackendResult =
        when (dispatch) {
            is AnkiDroidNoteWriteDispatch.NotDispatched -> NoteMutationBackendResult.ConfirmedNotApplied(dispatch.error)
            is AnkiDroidNoteWriteDispatch.Returned ->
                if (dispatch.rowCount == expectedRows) NoteMutationBackendResult.ConfirmedApplied
                else NoteMutationBackendResult.OutcomeUnknown(AnkiError.QueryFailure("provider_row_count_mismatch"))
            is AnkiDroidNoteWriteDispatch.Threw -> when (dispatch.exceptionClass) {
                "SecurityException" -> NoteMutationBackendResult.ConfirmedNotApplied(AnkiError.PermissionRequired())
                "IllegalArgumentException" -> NoteMutationBackendResult.ConfirmedNotApplied(
                    AnkiError.InvalidRequest("provider_refused_before_write")
                )
                else -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown(cause = "provider_threw_${dispatch.exceptionClass}"))
            }
            is AnkiDroidNoteWriteDispatch.Unknown ->
                NoteMutationBackendResult.OutcomeUnknown(AnkiError.QueryFailure(dispatch.detail))
        }

    private fun refused(reason: String): AnkiDroidNoteWriteMapping =
        AnkiDroidNoteWriteMapping.Refused(AnkiError.InvalidRequest(reason))
}
