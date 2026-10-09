package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiDeckRef

/**
 * GATE 17 — pure, pre-transaction editing rules: validate a draft, compute the minimal patch, and
 * derive the ordered plan. Nothing here touches a backend, a ledger or a clock.
 */
object NoteEditPlanner {

    /** The Anki field separator (U+001F). A value containing it would silently split into fields. */
    const val FIELD_SEPARATOR: Char = '\u001F'

    /** Validates the draft against the base. Empty list = the draft may proceed to a patch. */
    fun validateDraft(base: NoteEditBase, draft: NoteEditDraft): List<NoteEditValidationError> {
        val errors = mutableListOf<NoteEditValidationError>()

        draft.fieldValues.keys.sorted().forEach { ordinal ->
            if (ordinal !in base.fields.indices) {
                errors += NoteEditValidationError.UnknownField(ordinal)
                return@forEach
            }
            val value = draft.fieldValues.getValue(ordinal)
            if (value.indexOf(FIELD_SEPARATOR) >= 0) {
                errors += NoteEditValidationError.FieldContainsSeparator(ordinal)
            }
        }
        val fieldWillChange = draft.fieldValues.any { (ordinal, value) ->
            ordinal in base.fields.indices && base.fields[ordinal].value != value
        }
        if (fieldWillChange && base.noteTypeId.isNullOrBlank()) {
            errors += NoteEditValidationError.FieldIdentityUnavailable
        }

        val tagsRequested = draft.tags
        if (tagsRequested != null) {
            val normalized = normalizeTags(tagsRequested)
            if (normalized is TagNormalization.Invalid) {
                errors += NoteEditValidationError.InvalidTag(normalized.tag)
            }
        }

        val target = draft.targetDeck
        if (target != null) {
            if (target.backendId != base.backendId) {
                errors += NoteEditValidationError.DeckTargetWrongBackend
            } else if (!sameDeck(target, base.deckRef) && base.deckRef == null) {
                errors += NoteEditValidationError.SourceDeckUnknown
            }
        }

        return errors
    }

    /**
     * Computes the minimal patch. Precondition: [validateDraft] returned no errors. Unchanged
     * values, tag sets equal as sets, and a target equal to the current deck produce no change.
     *
     * Field values are canonicalized on the way in ([canonicalFieldValue]): the patch then carries
     * exactly what the backend can store, so "no change" and the post-write comparison are both
     * decided on storable text. Tag text is kept as the user wrote it (NFC-normalized, de-duplicated)
     * — case and order are the backend's to canonicalize, and a case-only difference is a real
     * intention, not a no-op.
     */
    fun buildPatch(base: NoteEditBase, draft: NoteEditDraft): NoteMutationPatch {
        val fieldChanges = draft.fieldValues.keys.sorted().mapNotNull { ordinal ->
            val field = base.fields.getOrNull(ordinal) ?: return@mapNotNull null
            val newValue = canonicalFieldValue(draft.fieldValues.getValue(ordinal))
            if (newValue == field.value) null
            else NoteFieldChange(ordinal = ordinal, name = field.name, oldValue = field.value, newValue = newValue)
        }

        val tagChange = draft.tags?.let { raw ->
            val normalized = (normalizeTags(raw) as? TagNormalization.Valid)?.tags ?: return@let null
            if (normalized.toSet() == base.tags.toSet()) null
            else TagChange(oldTags = base.tags, newTags = normalized)
        }

        val deckChange = draft.targetDeck?.let { target ->
            if (target.backendId != base.backendId || sameDeck(target, base.deckRef)) null
            else DeckChange(fromDeck = base.deckRef, toDeck = target)
        }

        return NoteMutationPatch(
            noteTypeId = base.noteTypeId,
            fieldChanges = fieldChanges,
            tagChange = tagChange,
            deckChange = deckChange
        )
    }

    /**
     * Derives the ordered plan. Content first, then deck: a deck move is the last, least important
     * write, so a failure there cannot hide a content write that already applied.
     */
    fun buildPlan(patch: NoteMutationPatch): NoteMutationPlan {
        require(!patch.isEmpty) { "An empty patch never becomes a plan" }
        val operations = buildList<NoteMutationOperation> {
            if (patch.touchesContent) {
                add(
                    NoteMutationOperation.UpdateNoteContent(
                        updatesFields = patch.fieldChanges.isNotEmpty(),
                        updatesTags = patch.tagChange != null
                    )
                )
            }
            patch.deckChange?.let { add(NoteMutationOperation.ChangeDeck(it.toDeck)) }
        }
        return NoteMutationPlan(operations)
    }

    /** Stable deck identity: backend + deck id. The display name is never identity. */
    fun sameDeck(a: AnkiDeckRef, b: AnkiDeckRef?): Boolean =
        b != null && a.backendId == b.backendId && a.deckId == b.deckId

    /**
     * Tag rules, derived from the pinned backend rather than from taste (CONTRACT-02):
     * trim each entry; drop entries that are empty after trimming; reject any entry that still
     * contains whitespace or a control character (Anki splits tags on space/U+3000 and strips ASCII
     * control characters, so such an entry would not be stored as written); reject an entry with a
     * blank `::` component (rslib rewrites it to the literal `blank`, so the stored tag would not be
     * the requested tag); NFC-normalize (the backend stores NFC); and keep the first occurrence of
     * each tag compared case-insensitively (rslib de-duplicates with UniCase, first instance wins).
     */
    fun normalizeTags(raw: List<String>): TagNormalization {
        val seen = LinkedHashMap<String, String>()
        for (entry in raw) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return TagNormalization.Invalid(trimmed)
            val tag = NoteContentCanonicalization.nfc(trimmed)
            if (NoteContentCanonicalization.tagHasBlankHierarchyComponent(tag)) {
                return TagNormalization.Invalid(tag)
            }
            seen.putIfAbsent(tag.lowercase(), tag)
        }
        return TagNormalization.Valid(seen.values.toList())
    }

    /**
     * The field value the pinned backend can actually store: ASCII control characters other than
     * `\n` and `\t` are removed by rslib `normalize_field` on the way in, so they are removed here
     * too — the payload then equals the stored value, which is what makes the post-write comparison
     * meaningful. The field separator U+001F is rejected earlier ([validateDraft]) and never stripped,
     * because stripping it would silently join two fields.
     */
    fun canonicalFieldValue(raw: String): String = NoteContentCanonicalization.fieldValue(raw)
}

sealed interface TagNormalization {
    data class Valid(val tags: List<String>) : TagNormalization
    data class Invalid(val tag: String) : TagNormalization
}
