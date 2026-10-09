package com.studyagent.client.core.anki.create

import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiNoteModel

/**
 * GATE 18 — everything that must hold BEFORE any durable record or backend boundary
 * (CONTRACT-18-13/14, PART I §9). Pure and total: validation failure is a list of issues, never an
 * exception, and never a backend call.
 *
 * The rules split into two kinds:
 * - backend representability at the pinned contract (field separator, field count, trailing empty
 *   field, tag shape the backend would rewrite), and
 * - product rules that mirror Anki's own blocking states (empty first field — desktop's *Empty*
 *   state from rslib `note_fields_check`) or protect the provider process (media size/MIME limits).
 * Each issue carries a stable token; the UI wording lives above this.
 */
object NoteCreationValidator {

    fun validate(draft: AddNoteDraft, capabilities: AnkiCapabilities): List<String> {
        val issues = ArrayList<String>()
        if (!capabilities.createNotes) issues += "capability_create_notes_missing"
        if (!capabilities.noteModelListing) issues += "capability_model_listing_missing"
        if (draft.media.isNotEmpty() && !capabilities.storeMedia) issues += "capability_store_media_missing"

        val model = draft.model
        issues += validateFields(model, draft.fieldValues)
        issues += validateTags(draft.tags)
        issues += validateMedia(model, draft.media)
        return issues.distinct()
    }

    /**
     * Validates a freshly re-read schema against the draft's frozen one (drift detection,
     * CONTRACT-18-02/§3). A mismatch means the draft's ordinal mapping cannot be trusted: the only
     * honest answer is refusal — there is no silent remapping (PART I §19).
     */
    fun schemaDrift(draftModel: AnkiNoteModel, fresh: AnkiNoteModel): Boolean =
        !draftModel.schemaMatches(fresh)

    private fun validateFields(model: AnkiNoteModel, values: Map<Int, String>): List<String> {
        val issues = ArrayList<String>()
        val ordinals = model.fields.map { it.ordinal }
        if (values.keys.sorted() != ordinals) {
            issues += "field_ordinals_incomplete"
            return issues
        }
        for (field in model.fields) {
            val value = values.getValue(field.ordinal)
            if (value.indexOf('\u001F') >= 0) issues += "field_contains_separator"
        }
        val first = values.getValue(model.fields.first().ordinal)
        if (first.isBlank()) issues += "first_field_empty"
        val last = values.getValue(model.fields.last().ordinal)
        // Trailing empty fields are unrepresentable through the pinned provider: `splitFields`
        // drops trailing empties and the count check then refuses (docs/GATE_18 §4).
        if (last.isEmpty()) issues += "last_field_not_representable"
        return issues
    }

    private fun validateTags(tags: List<String>): List<String> {
        val issues = ArrayList<String>()
        for (tag in tags) {
            if (tag.isBlank()) {
                issues += "tag_blank"
                continue
            }
            if (tag.any { it.isWhitespace() || it.isISOControl() }) issues += "tag_invalid_character"
            // A blank `::` component would be rewritten by the backend ("blank"); refuse before the
            // boundary instead of writing something the collection will not hold (GATE 17 rule).
            if (tag.split("::").any { it.isBlank() }) issues += "tag_hierarchy_component_blank"
        }
        return issues
    }

    private fun validateMedia(model: AnkiNoteModel, media: List<PendingMedia>): List<String> {
        val issues = ArrayList<String>()
        if (media.size > MAX_MEDIA_PER_CREATION) issues += "media_count_exceeded"
        for (item in media) {
            if (item.sizeBytes > MAX_MEDIA_SIZE_BYTES) issues += "media_size_exceeded"
            if (item.extension.isBlank()) issues += "media_extension_unknown"
            if (item.targetFieldOrdinal >= model.fieldCount) issues += "media_target_field_missing"
            val mimeFamily = item.mimeType.substringBefore('/').lowercase()
            val expected = when (item.kind) {
                CreationMediaKind.IMAGE -> "image"
                CreationMediaKind.AUDIO -> "audio"
            }
            if (mimeFamily != expected) issues += "media_kind_mime_mismatch"
        }
        return issues
    }
}
