package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelField
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.create.CreateNoteBackendRequest
import com.studyagent.client.core.anki.create.CreateNoteBackendResult
import com.studyagent.client.core.anki.create.MediaStoreBackendResult
import com.studyagent.client.core.anki.create.StoreMediaBackendRequest

/**
 * GATE 18 — pure translation between the creation domain and the pinned v2.24.1 provider contract,
 * plus the rules that turn provider answers into boundary classifications. No Android type is used,
 * so every rule here runs on the JVM.
 *
 * Pinned facts this mapper encodes (docs/GATE_18_BACKEND_CREATION_CONTRACT.md, §2/§4/§9/§12):
 *
 * - `notes` insert takes `mid` (Long), `flds` (0x1f-joined ordered values) and optional `tags`
 *   (space-separated). No deck input exists. The provider count-checks `flds` before `col.addNote`,
 *   so a wrong count is a proven non-creation.
 * - `media` insert takes `file_uri` + `preferred_name` and answers with the backend-chosen name
 *   (file:// URI) or null.
 * - Classification mirrors the GATE 17 R1 chain: every `SecurityException` /
 *   `IllegalArgumentException` / `NullPointerException` the pinned NOTES/MEDIA branches can throw
 *   precedes the irreversible write; backend Rust errors arrive as `BackendException` subclasses
 *   (`RuntimeException`) and stay UNKNOWN, because the binder cannot prove where they originated.
 * - A null insert answer is UNKNOWN, never "not inserted": the platform also returns null when the
 *   provider dies mid-call.
 */
object AnkiDroidCreationMapper {

    // ---------------------------------------------------------------- write mapping

    sealed interface NoteCreateMapping {
        data class Ready(val path: String, val values: List<ProviderValue>) : NoteCreateMapping
        data class Refused(val error: AnkiError) : NoteCreateMapping
    }

    sealed interface MediaStoreMapping {
        data class Ready(val path: String, val values: List<ProviderValue>) : MediaStoreMapping
        data class Refused(val error: AnkiError) : MediaStoreMapping
    }

    fun mapNoteCreate(request: CreateNoteBackendRequest): NoteCreateMapping {
        val modelId = request.model.modelId.toLongOrNull()?.takeIf { it > 0L }
            ?: return NoteCreateMapping.Refused(AnkiError.InvalidRequest("model_id_not_provider_numeric"))
        if (request.orderedFields.any { it.indexOf('\u001F') >= 0 }) {
            return NoteCreateMapping.Refused(AnkiError.InvalidRequest("field_contains_separator"))
        }
        val values = ArrayList<ProviderValue>(3)
        values += ProviderValue.LongValue(AnkiDroidApiContract.NOTE_INSERT_MID_COLUMN, modelId)
        values += ProviderValue.StringValue(
            AnkiDroidApiContract.NOTE_FIELDS_COLUMN,
            request.orderedFields.joinToString(AnkiDroidApiContract.FIELD_SEPARATOR)
        )
        if (request.tags.isNotEmpty()) {
            values += ProviderValue.StringValue(
                AnkiDroidApiContract.NOTE_TAGS_COLUMN,
                request.tags.joinToString(AnkiDroidApiContract.TAGS_SEPARATOR)
            )
        }
        return NoteCreateMapping.Ready(AnkiDroidApiContract.NOTES_INSERT_PATH, values)
    }

    fun mapMediaStore(request: StoreMediaBackendRequest): MediaStoreMapping {
        if (request.preferredName.any { it == '/' || it == '\\' }) {
            return MediaStoreMapping.Refused(AnkiError.MediaRejected("preferred_name_has_path_separator"))
        }
        val values = listOf(
            ProviderValue.StringValue(AnkiDroidApiContract.MEDIA_FILE_URI_COLUMN, request.contentUri),
            ProviderValue.StringValue(AnkiDroidApiContract.MEDIA_PREFERRED_NAME_COLUMN, request.preferredName)
        )
        return MediaStoreMapping.Ready(AnkiDroidApiContract.MEDIA_PATH, values)
    }

    // ---------------------------------------------------------------- boundary classification

    /**
     * Maps one provider insert answer for the NOTE boundary. Only a returned URI with a parseable
     * positive id is creation proof; only the proven pre-write refusals are non-creation proof.
     */
    fun classifyNoteInsert(dispatch: AnkiDroidCreationDispatch): CreateNoteBackendResult =
        when (dispatch) {
            is AnkiDroidCreationDispatch.NotDispatched ->
                CreateNoteBackendResult.ConfirmedNotCreated(dispatch.error)
            is AnkiDroidCreationDispatch.Returned -> {
                val noteId = dispatch.uri?.trim()?.substringAfterLast('/')
                    ?.takeIf { it.isNotEmpty() }
                val parsed = noteId?.toLongOrNull()?.takeIf { it > 0L }
                if (parsed != null) {
                    CreateNoteBackendResult.ConfirmedCreated(parsed.toString())
                } else if (dispatch.uri == null) {
                    // Provider answered null: refusal-by-null or provider death — indistinguishable.
                    CreateNoteBackendResult.OutcomeUnknown(AnkiError.QueryFailure("provider_null_insert"))
                } else {
                    CreateNoteBackendResult.OutcomeUnknown(AnkiError.MalformedResponse("note_insert_uri_unparseable"))
                }
            }
            is AnkiDroidCreationDispatch.Threw -> when (dispatch.exceptionClass) {
                "SecurityException" ->
                    CreateNoteBackendResult.ConfirmedNotCreated(AnkiError.PermissionRequired())
                "IllegalArgumentException", "NumberFormatException", "NullPointerException" ->
                    CreateNoteBackendResult.ConfirmedNotCreated(
                        AnkiError.InvalidRequest("provider_refused_before_write")
                    )
                else ->
                    CreateNoteBackendResult.OutcomeUnknown(
                        AnkiError.Unknown(cause = "provider_threw_${dispatch.exceptionClass}")
                    )
            }
            is AnkiDroidCreationDispatch.Unknown ->
                CreateNoteBackendResult.OutcomeUnknown(AnkiError.QueryFailure(dispatch.detail))
        }

    /**
     * Maps one provider insert answer for the MEDIA boundary. The stored name comes from the
     * returned URI's last segment — the backend is the naming authority (CONTRACT-18-27).
     */
    fun classifyMediaInsert(dispatch: AnkiDroidCreationDispatch): MediaStoreBackendResult =
        when (dispatch) {
            is AnkiDroidCreationDispatch.NotDispatched ->
                MediaStoreBackendResult.ConfirmedNotStored(dispatch.error)
            is AnkiDroidCreationDispatch.Returned -> {
                val name = dispatch.uri?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                if (name != null) {
                    MediaStoreBackendResult.Stored(name)
                } else if (dispatch.uri == null) {
                    MediaStoreBackendResult.OutcomeUnknown(AnkiError.QueryFailure("provider_null_insert"))
                } else {
                    MediaStoreBackendResult.OutcomeUnknown(AnkiError.MalformedResponse("media_insert_uri_unparseable"))
                }
            }
            is AnkiDroidCreationDispatch.Threw -> when (dispatch.exceptionClass) {
                "SecurityException" ->
                    MediaStoreBackendResult.ConfirmedNotStored(AnkiError.PermissionRequired())
                "IllegalArgumentException", "NumberFormatException", "NullPointerException" ->
                    MediaStoreBackendResult.ConfirmedNotStored(
                        AnkiError.MediaRejected("provider_refused_before_store")
                    )
                else ->
                    MediaStoreBackendResult.OutcomeUnknown(
                        AnkiError.Unknown(cause = "provider_threw_${dispatch.exceptionClass}")
                    )
            }
            is AnkiDroidCreationDispatch.Unknown ->
                MediaStoreBackendResult.OutcomeUnknown(AnkiError.QueryFailure(dispatch.detail))
        }

    // ---------------------------------------------------------------- model listing rows

    sealed interface ModelRowOutcome {
        data class Model(val model: AnkiNoteModel) : ModelRowOutcome
        data class Malformed(val token: String) : ModelRowOutcome
    }

    /** Maps one `models` row into a creation schema. Ordinal identity; names are labels. */
    fun mapModelRow(backendId: AnkiBackendId, row: AnkiDroidProviderRow): ModelRowOutcome {
        val idIndex = row.columnIndex(AnkiDroidApiContract.MODEL_ID_COLUMN)
        if (idIndex < 0) return ModelRowOutcome.Malformed("model_id_column_missing")
        if (row.isNull(idIndex)) return ModelRowOutcome.Malformed("model_id_unreadable")
        val modelId = row.getLong(idIndex)
        if (modelId <= 0L) return ModelRowOutcome.Malformed("model_id_unreadable")

        val nameIndex = row.columnIndex(AnkiDroidApiContract.MODEL_NAME_COLUMN)
        val name = if (nameIndex < 0 || row.isNull(nameIndex)) null else row.getString(nameIndex)
        if (name.isNullOrBlank()) return ModelRowOutcome.Malformed("model_name_unreadable")

        val fieldsIndex = row.columnIndex(AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN)
        if (fieldsIndex < 0) return ModelRowOutcome.Malformed("model_fields_column_missing")
        val joined = if (row.isNull(fieldsIndex)) null else row.getString(fieldsIndex)
        if (joined == null) return ModelRowOutcome.Malformed("model_fields_unreadable")
        // The provider joins with 0x1f; splitting on it preserves order and exposes blank names.
        val fieldNames = joined.split(AnkiDroidApiContract.FIELD_SEPARATOR)
        if (fieldNames.isEmpty() || fieldNames.any { it.isBlank() }) {
            return ModelRowOutcome.Malformed("model_field_names_malformed")
        }

        val typeIndex = row.columnIndex(AnkiDroidApiContract.MODEL_TYPE_COLUMN)
        val typeCode = if (typeIndex < 0 || row.isNull(typeIndex)) null else row.getInt(typeIndex)
        val kind = when (typeCode) {
            AnkiDroidApiContract.MODEL_TYPE_NORMAL -> AnkiNoteModelKind.NORMAL
            AnkiDroidApiContract.MODEL_TYPE_CLOZE -> AnkiNoteModelKind.CLOZE
            else -> AnkiNoteModelKind.UNKNOWN
        }

        val numCardsIndex = row.columnIndex(AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN)
        val templateCount = if (numCardsIndex < 0 || row.isNull(numCardsIndex)) null else row.getInt(numCardsIndex)

        val deckIndex = row.columnIndex(AnkiDroidApiContract.MODEL_DECK_ID_COLUMN)
        val defaultDeckId = if (deckIndex < 0 || row.isNull(deckIndex)) null else row.getLong(deckIndex).toString()

        return ModelRowOutcome.Model(
            AnkiNoteModel(
                ref = AnkiNoteModelRef(backendId, modelId.toString()),
                name = name,
                kind = kind,
                fields = fieldNames.mapIndexed { ordinal, fieldName ->
                    AnkiNoteModelField(ordinal, fieldName)
                },
                templateCount = templateCount?.takeIf { it >= 0 },
                defaultDeckId = defaultDeckId
            )
        )
    }
}
