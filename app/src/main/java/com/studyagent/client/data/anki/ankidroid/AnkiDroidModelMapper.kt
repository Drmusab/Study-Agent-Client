package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardTemplateMetadata
import com.studyagent.client.core.anki.AnkiCardTemplateRef
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelEnriched
import com.studyagent.client.core.anki.AnkiNoteModelField
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.AnkiNoteModelRef

/**
 * GATE 19 — pure mapping from AnkiDroid model/template rows to the domain's enriched
 * [AnkiNoteModelEnriched]. Reuses the GATE 18 model-row mapping where possible and adds the
 * CSS/LaTeX/sort-field/template source fields.
 *
 * No Android types, no ContentResolver, no side effects — JVM-testable.
 */
object AnkiDroidModelMapper {

    /** Outcome of mapping one enriched model row + its template rows. */
    sealed interface EnrichedModelOutcome {
        data class Model(val enriched: AnkiNoteModelEnriched, val degradations: List<String> = emptyList()) :
            EnrichedModelOutcome
        data class Malformed(val token: String) : EnrichedModelOutcome
    }

    /**
     * Map one snapshot (enriched model row + template rows) into the domain type.
     *
     * The base model fields use the same disciplined parsing as GATE 18's [AnkiDroidCreationMapper].
     * Template rows are mapped positionally: their ordinal is derived from their position in the
     * list (AnkiDroid returns templates ordered, but an explicit `ord` column is preferred when
     * present). Missing optional columns degrade to null without failing the whole load.
     */
    fun mapEnrichedModel(
        backendId: AnkiBackendId,
        snapshot: AnkiDroidEnrichedModelSnapshot,
        collectionKey: String? = null
    ): EnrichedModelOutcome {
        val base = AnkiDroidCreationMapper.mapModelRow(backendId, snapshot.modelRow)
        val baseModel: AnkiNoteModel = when (base) {
            is AnkiDroidCreationMapper.ModelRowOutcome.Model -> base.model
            is AnkiDroidCreationMapper.ModelRowOutcome.Malformed ->
                return EnrichedModelOutcome.Malformed(base.token)
        }
        // If collectionKey is provided and the base model ref lacks it, replace the ref with a
        // collection-qualified one.
        val model = if (collectionKey != null && baseModel.ref.collectionKey != collectionKey) {
            baseModel.copy(ref = AnkiNoteModelRef(backendId, baseModel.ref.modelId, collectionKey))
        } else baseModel

        val degradations = mutableListOf<String>()
        if (snapshot.templatesFailed && snapshot.templatesFailureToken != null) {
            degradations += snapshot.templatesFailureToken
        }

        // Optional enriched columns (CSS, sort field, note count, LaTeX). All are nullable and
        // degrade silently when the column is missing (AnkiDroid skips unknown projection names
        // per GATE 05/07 contract notes).
        val css = readOptionalString(snapshot.modelRow, AnkiDroidApiContract.MODEL_CSS_COLUMN)
        val sortFieldIndex = readOptionalInt(snapshot.modelRow, AnkiDroidApiContract.MODEL_SORT_FIELD_INDEX_COLUMN)
        val noteCount = readOptionalInt(snapshot.modelRow, AnkiDroidApiContract.MODEL_NOTE_COUNT_COLUMN)
        val latexPreamble = readOptionalString(snapshot.modelRow, AnkiDroidApiContract.MODEL_LATEX_PREAMBLE_COLUMN)
        val latexSvg = readOptionalBoolean(snapshot.modelRow, AnkiDroidApiContract.MODEL_LATEX_SVG_COLUMN)

        // Template rows. Map in returned order. If any row lacks a usable ordinal/name, degrade
        // that template (skip) rather than failing the whole load.
        val templates = ArrayList<AnkiCardTemplateMetadata>(snapshot.templateRows.size)
        val expectedOrdinals = HashSet<Int>()
        snapshot.templateRows.forEachIndexed { index, row ->
            val outcome = mapTemplateRow(model.ref, row, fallbackOrdinal = index, collectionKey = collectionKey)
            when (outcome) {
                is TemplateRowOutcome.Template -> {
                    if (expectedOrdinals.add(outcome.template.ref.ordinal)) {
                        templates += outcome.template
                    } else {
                        degradations += "duplicate_template_ordinal_${outcome.template.ref.ordinal}"
                    }
                }
                is TemplateRowOutcome.Malformed -> {
                    degradations += outcome.token
                }
            }
        }
        // Sort by ordinal to guarantee dense 0..N-1 order regardless of returned order.
        templates.sortBy { it.ref.ordinal }

        // Validate template ordinals are dense starting at 0: if not (some skipped due to
        // malformed rows), record a degradation but keep what we have.
        if (templates.map { it.ref.ordinal } != templates.indices.toList()) {
            degradations += "template_ordinals_not_dense"
        }

        return EnrichedModelOutcome.Model(
            AnkiNoteModelEnriched(
                model = model,
                templates = templates,
                css = css,
                sortFieldIndex = sortFieldIndex,
                noteCount = noteCount,
                latexPreamble = latexPreamble,
                latexSvg = latexSvg
            ),
            degradations = degradations
        )
    }

    sealed interface TemplateRowOutcome {
        data class Template(val template: AnkiCardTemplateMetadata) : TemplateRowOutcome
        data class Malformed(val token: String) : TemplateRowOutcome
    }

    private fun mapTemplateRow(
        modelRef: AnkiNoteModelRef,
        row: AnkiDroidProviderRow,
        fallbackOrdinal: Int,
        collectionKey: String?
    ): TemplateRowOutcome {
        // Prefer an explicit ord column; fall back to positional index.
        val ordinal = readOptionalInt(row, AnkiDroidApiContract.TEMPLATE_ORD_COLUMN) ?: fallbackOrdinal
        if (ordinal < 0) return TemplateRowOutcome.Malformed("template_ordinal_invalid")

        val nameIndex = row.columnIndex(AnkiDroidApiContract.TEMPLATE_NAME_COLUMN)
        val name = if (nameIndex < 0 || row.isNull(nameIndex)) null else row.getString(nameIndex)
        if (name.isNullOrBlank()) return TemplateRowOutcome.Malformed("template_name_unreadable")

        val qfmt = readOptionalString(row, AnkiDroidApiContract.TEMPLATE_QFMT_COLUMN)
        val afmt = readOptionalString(row, AnkiDroidApiContract.TEMPLATE_AFMT_COLUMN)
        val deckId = readOptionalStringLong(row, AnkiDroidApiContract.TEMPLATE_DECK_ID_COLUMN)
        val browserQfmt = readOptionalString(row, AnkiDroidApiContract.TEMPLATE_BROWSER_QFMT_COLUMN)
        val browserAfmt = readOptionalString(row, AnkiDroidApiContract.TEMPLATE_BROWSER_AFMT_COLUMN)

        return TemplateRowOutcome.Template(
            AnkiCardTemplateMetadata(
                ref = AnkiCardTemplateRef(
                    modelRef = modelRef,
                    ordinal = ordinal,
                    collectionKey = collectionKey
                ),
                name = name,
                qfmt = qfmt,
                afmt = afmt,
                targetDeckId = deckId,
                browserQfmt = browserQfmt,
                browserAfmt = browserAfmt
            )
        )
    }

    private fun readOptionalString(row: AnkiDroidProviderRow, column: String): String? {
        val index = row.columnIndex(column)
        if (index < 0 || row.isNull(index)) return null
        return row.getString(index)
    }

    private fun readOptionalInt(row: AnkiDroidProviderRow, column: String): Int? {
        val index = row.columnIndex(column)
        if (index < 0 || row.isNull(index)) return null
        return try {
            row.getInt(index)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readOptionalBoolean(row: AnkiDroidProviderRow, column: String): Boolean? {
        val index = row.columnIndex(column)
        if (index < 0 || row.isNull(index)) return null
        return try {
            when (val value = row.getString(index)?.trim()?.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readOptionalStringLong(row: AnkiDroidProviderRow, column: String): String? {
        val index = row.columnIndex(column)
        if (index < 0 || row.isNull(index)) return null
        return try {
            row.getLong(index).takeIf { it > 0L }?.toString()
        } catch (_: Throwable) {
            row.getString(index)?.takeIf { it.isNotBlank() }
        }
    }
}
