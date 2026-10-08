package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.AnkiNoteRef

/**
 * Why one note/note-type row pair could not become an [AnkiDroidNoteSnapshot] (GATE 16
 * CHECKPOINT 08 rules: unknown ≠ zero, note identity ≠ card identity, source fields ≠ rendered
 * content).
 *
 * [structural] problems mean the relationship between the note and its note type cannot be
 * trusted — the answer becomes a typed `DataIntegrityFailure`/`MalformedResponse`, never
 * fabricated fields (GATE 16 §24). Optional metadata degrades individually instead.
 */
internal enum class AnkiDroidNoteRowProblem(val structural: Boolean, val token: String) {
    IDENTITY_COLUMN_MISSING(structural = true, token = "note_identity_column_missing"),
    IDENTITY_UNREADABLE(structural = true, token = "note_identity_unreadable"),
    FIELD_PAIRING_INCONSISTENT(structural = true, token = "note_field_pairing_inconsistent"),
    FIELD_NAME_BLANK(structural = true, token = "note_field_name_blank"),
    MODEL_IDENTITY_COLUMN_MISSING(structural = true, token = "note_model_identity_column_missing"),
    MODEL_IDENTITY_UNREADABLE(structural = true, token = "note_model_identity_unreadable"),
    MODEL_IDENTITY_MISMATCH(structural = true, token = "note_model_identity_mismatch")
}

/** Per-note mapping outcome. Returned, never thrown. */
internal sealed interface AnkiDroidNoteRowOutcome {
    data class Valid(val note: AnkiDroidNoteSnapshot) : AnkiDroidNoteRowOutcome
    data class Malformed(val problem: AnkiDroidNoteRowProblem) : AnkiDroidNoteRowOutcome
}

/**
 * Everything the pinned public note/note-type surfaces say about one note — source facts only
 * (GATE 16 CHECKPOINT 06). No rendered content lives here: `flds` values are the note's own
 * source values and are never parsed out of question/answer HTML (AUDIT 4).
 *
 * Public because it is the note gateway's boundary value; it carries only domain types — never a
 * provider row, cursor or column name (INV-16-13).
 */
data class AnkiDroidNoteSnapshot(
    val noteRef: AnkiNoteRef,
    val noteTypeId: String?,
    val noteTypeName: String?,
    /** `null` when field names/values could not be paired honestly (see mapping rules). */
    val fields: List<AnkiNoteField>?,
    /** Tags in backend order; `null` unavailable, empty authoritatively means no tags. */
    val tags: List<String>?,
    val noteModifiedEpochSeconds: Long?,
    /** Card-template count of the note type, when the model row reported one. */
    val modelCardCount: Int?,
    /** `true` cloze, `false` normal, `null` the model row did not say. */
    val cloze: Boolean?,
    val degradations: List<String>
)

/**
 * GATE 16 — maps the pinned note row (`notes/<id>`) plus its exact note-type row (`models/<mid>`)
 * into an [AnkiDroidNoteSnapshot] (CHECKPOINT 08).
 *
 * Mapping rules, stated so they cannot drift:
 *
 * - **Field order is the backend's** (`flds` cell order == `field_names` cell order). Fields are
 *   never alphabetically re-sorted and their `ordinal` is the `0`-based backend position.
 * - **Names and values are paired only when they agree in count.** A mismatch is structural
 *   corruption (`FIELD_PAIRING_INCONSISTENT`) — a typed failure, never guessed names like
 *   "Field 1" and never a silently truncated list (GATE 16 §24).
 * - **A blank field name is structural** (`FIELD_NAME_BLANK`): the domain type forbids it and
 *   inventing a display name would be fabrication.
 * - **Field names are required to publish named fields**: when unavailable, `fields` becomes
 *   `null` ("the backend cannot expose note fields") plus a content-free token — values without
 *   trustworthy names are never published as fields. The gateway separately classifies a missing
 *   exact note-type row as data-integrity failure.
 * - **Empty values are legitimate**: a field whose value is `""` is an empty field, not an
 *   unknown one (INV-16-10).
 * - **Tags keep backend order**; blank cells are dropped and exact duplicates collapsed for
 *   display, never re-sorted (§20).
 */
internal object AnkiDroidNoteMapper {

    fun mapNoteRow(
        noteRow: AnkiDroidProviderRow,
        modelRow: AnkiDroidProviderRow?,
        backendId: AnkiBackendId,
        collectionKey: String? = null
    ): AnkiDroidNoteRowOutcome {
        val idIndex = noteRow.columnIndex(AnkiDroidApiContract.NOTE_ID_COLUMN)
        if (idIndex < 0) return AnkiDroidNoteRowOutcome.Malformed(AnkiDroidNoteRowProblem.IDENTITY_COLUMN_MISSING)
        val noteId = parsePositiveLong(AnkiDroidMapper.getOptionalString(noteRow, idIndex))
            ?: return AnkiDroidNoteRowOutcome.Malformed(AnkiDroidNoteRowProblem.IDENTITY_UNREADABLE)

        val degradations = mutableListOf<String>()

        val noteTypeId = parsePositiveLong(
            AnkiDroidMapper.getOptionalString(
                noteRow,
                AnkiDroidMapper.optionalColumnIndex(noteRow, AnkiDroidApiContract.NOTE_MID_COLUMN)
            )
        )
        if (noteTypeId == null) degradations.add(DEG_NOTE_TYPE_ID_UNREADABLE)

        val noteModifiedEpochSeconds = parseEpochSeconds(
            AnkiDroidMapper.getOptionalString(
                noteRow,
                AnkiDroidMapper.optionalColumnIndex(noteRow, AnkiDroidApiContract.NOTE_MOD_COLUMN)
            )
        )

        val tags = mapTags(
            AnkiDroidMapper.getOptionalString(
                noteRow,
                AnkiDroidMapper.optionalColumnIndex(noteRow, AnkiDroidApiContract.NOTE_TAGS_COLUMN)
            )
        )
        if (tags == null) degradations.add(DEG_NOTE_TAGS_UNAVAILABLE)

        val fieldsCell = AnkiDroidMapper.getOptionalString(
            noteRow,
            AnkiDroidMapper.optionalColumnIndex(noteRow, AnkiDroidApiContract.NOTE_FIELDS_COLUMN)
        )

        if (modelRow != null) {
            val modelIdIndex = modelRow.columnIndex(AnkiDroidApiContract.MODEL_ID_COLUMN)
            if (modelIdIndex < 0) {
                return AnkiDroidNoteRowOutcome.Malformed(AnkiDroidNoteRowProblem.MODEL_IDENTITY_COLUMN_MISSING)
            }
            val modelId = parsePositiveLong(AnkiDroidMapper.getOptionalString(modelRow, modelIdIndex))
                ?: return AnkiDroidNoteRowOutcome.Malformed(AnkiDroidNoteRowProblem.MODEL_IDENTITY_UNREADABLE)
            if (noteTypeId == null || modelId != noteTypeId) {
                return AnkiDroidNoteRowOutcome.Malformed(AnkiDroidNoteRowProblem.MODEL_IDENTITY_MISMATCH)
            }
        }
        val modelMapping = mapModel(modelRow, degradations)
        val fieldsOutcome = mapFields(fieldsCell, modelMapping, degradations)
        if (fieldsOutcome is FieldsMapping.Malformed) {
            return AnkiDroidNoteRowOutcome.Malformed(fieldsOutcome.problem)
        }

        val snapshot = AnkiDroidNoteSnapshot(
            noteRef = AnkiNoteRef(backendId = backendId, noteId = noteId, collectionKey = collectionKey),
            noteTypeId = noteTypeId,
            noteTypeName = modelMapping?.name?.takeIf { it.isNotBlank() },
            fields = (fieldsOutcome as FieldsMapping.Paired).fields,
            tags = tags,
            noteModifiedEpochSeconds = noteModifiedEpochSeconds,
            modelCardCount = modelMapping?.cardCount,
            cloze = modelMapping?.cloze,
            degradations = degradations.toList()
        )
        return AnkiDroidNoteRowOutcome.Valid(snapshot)
    }

    private data class ModelFacts(
        val name: String?,
        val fieldNames: List<String>,
        val fieldNamesReadable: Boolean,
        val cardCount: Int?,
        val cloze: Boolean?
    )

    private fun mapModel(row: AnkiDroidProviderRow?, degradations: MutableList<String>): ModelFacts? {
        if (row == null) {
            degradations.add(DEG_NOTE_MODEL_UNAVAILABLE)
            return null
        }
        val name = AnkiDroidMapper.getOptionalString(
            row,
            AnkiDroidMapper.optionalColumnIndex(row, AnkiDroidApiContract.MODEL_NAME_COLUMN)
        )
        val namesCell = AnkiDroidMapper.getOptionalString(
            row,
            AnkiDroidMapper.optionalColumnIndex(row, AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN)
        )
        // A missing names cell means names are unreadable (fields cannot be paired); a present
        // cell splits into exactly the model's names, empties preserved (`split` keeps them).
        val fieldNamesReadable = namesCell != null
        if (namesCell == null) degradations.add(DEG_NOTE_FIELD_NAMES_UNAVAILABLE)
        val fieldNames = namesCell?.let(::splitFieldsPreservingEmpty) ?: emptyList()

        val cardCount = parseNonNegativeInt(
            AnkiDroidMapper.getOptionalString(
                row,
                AnkiDroidMapper.optionalColumnIndex(row, AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN)
            )
        )
        val typeCode = parseBoundedInt(
            AnkiDroidMapper.getOptionalString(
                row,
                AnkiDroidMapper.optionalColumnIndex(row, AnkiDroidApiContract.MODEL_TYPE_COLUMN)
            )
        )
        val cloze = when (typeCode) {
            null -> null
            AnkiDroidApiContract.MODEL_TYPE_NORMAL -> false
            AnkiDroidApiContract.MODEL_TYPE_CLOZE -> true
            else -> {
                degradations.add(DEG_NOTE_MODEL_TYPE_UNMAPPED)
                null
            }
        }
        return ModelFacts(name, fieldNames, fieldNamesReadable, cardCount, cloze)
    }

    private sealed interface FieldsMapping {
        data class Paired(val fields: List<AnkiNoteField>?) : FieldsMapping
        data class Malformed(val problem: AnkiDroidNoteRowProblem) : FieldsMapping
    }

    private fun mapFields(
        fieldsCell: String?,
        model: ModelFacts?,
        degradations: MutableList<String>
    ): FieldsMapping {
        if (fieldsCell == null) {
            // The note row carries no fields cell at all: nothing to publish, and nothing invented.
            degradations.add(DEG_NOTE_FIELDS_UNAVAILABLE)
            return FieldsMapping.Paired(null)
        }
        if (model == null || !model.fieldNamesReadable) {
            // Values without trustworthy names are never shown as named fields (§24).
            degradations.add(DEG_NOTE_FIELDS_UNAVAILABLE)
            return FieldsMapping.Paired(null)
        }
        val values = splitFieldsPreservingEmpty(fieldsCell)
        val names = model.fieldNames
        if (values.size != names.size) {
            return FieldsMapping.Malformed(AnkiDroidNoteRowProblem.FIELD_PAIRING_INCONSISTENT)
        }
        if (names.any { it.isBlank() }) {
            return FieldsMapping.Malformed(AnkiDroidNoteRowProblem.FIELD_NAME_BLANK)
        }
        val fields = names.mapIndexed { index, name ->
            AnkiNoteField(name = name, value = values[index], ordinal = index)
        }
        return FieldsMapping.Paired(fields)
    }

    /** Split source fields preserving every empty cell, including the final one. */
    private fun splitFieldsPreservingEmpty(cell: String): List<String> {
        val separator = AnkiDroidApiContract.FIELD_SEPARATOR
        val values = ArrayList<String>()
        var start = 0
        while (true) {
            val next = cell.indexOf(separator, start)
            if (next < 0) {
                values += cell.substring(start)
                return values
            }
            values += cell.substring(start, next)
            start = next + separator.length
        }
    }

    /** Space-separated tags, backend order, blanks dropped, exact duplicates collapsed. */
    internal fun mapTags(cell: String?): List<String>? {
        if (cell == null) return null
        return cell.split(AnkiDroidApiContract.TAGS_SEPARATOR)
            .filter { it.isNotBlank() }
            .distinct()
    }

    internal fun parsePositiveLong(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val value = trimmed.toLongOrNull()
            ?: trimmed.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it.isFinite() }?.toLong()
            ?: return null
        return if (value > 0L) value.toString() else null
    }

    private fun parseNonNegativeInt(text: String?): Int? {
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

    const val DEG_NOTE_TYPE_ID_UNREADABLE: String = "note_type_id_unreadable"
    const val DEG_NOTE_MODEL_UNAVAILABLE: String = "note_model_unavailable"
    const val DEG_NOTE_MODEL_TYPE_UNMAPPED: String = "note_model_type_unmapped"
    const val DEG_NOTE_FIELD_NAMES_UNAVAILABLE: String = "note_field_names_unavailable"
    const val DEG_NOTE_FIELDS_UNAVAILABLE: String = "note_fields_unavailable"
    const val DEG_NOTE_TAGS_UNAVAILABLE: String = "note_tags_unavailable"
}
