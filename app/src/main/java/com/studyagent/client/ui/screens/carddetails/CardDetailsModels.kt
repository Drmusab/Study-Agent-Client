package com.studyagent.client.ui.screens.carddetails

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiRenderedCard

/** Canonical, read-only details screen state (GATE 16 CHECKPOINT 12). */
sealed interface CardDetailsUiState {
    data object Loading : CardDetailsUiState

    data class Ready(
        val details: CardDetailsPresentation
    ) : CardDetailsUiState

    data class Unavailable(
        val reason: AnkiAvailability
    ) : CardDetailsUiState

    data class Error(
        val error: AnkiError
    ) : CardDetailsUiState
}

/**
 * Compose-independent presentation projection. Contains no backend/provider objects and no
 * Compose types (INV-16-13). It keeps each source content channel explicit and the original card
 * as the existing renderer's backend-neutral input; no second representation parses/rewrites HTML.
 */
data class CardDetailsPresentation(
    val cardRef: AnkiCardRef,
    val title: String,
    val deckName: String?,
    val noteId: String?,
    val noteTypeName: String?,
    val templateName: String?,
    val cardOrd: Int?,
    val overviewRows: List<MetadataRow>,
    val schedulingRows: List<MetadataRow>,
    val technicalRows: List<MetadataRow>,
    val fields: List<FieldPresentation>?,
    val tags: List<String>?,
    val flag: AnkiFlag?,
    val cardType: AnkiCardType?,
    val queueState: AnkiCardQueueState?,
    val questionText: String?,
    val answerText: String?,
    val pureAnswerText: String?,
    val originalCard: AnkiRenderedCard?,
    val mediaFiles: List<String>,
    val degradations: List<String>,
    /**
     * GATE 17 — the connected backend offers at least one note-edit operation for this card, so the
     * details screen may show the editor entry. Capability truth only: this screen stays read-only
     * (INV-16-01) and the entry navigates away to the editor, which owns every write.
     */
    val canOpenNoteEditor: Boolean = false
) {
    /**
     * `null` = backend did not expose source fields; empty = no fields. Do not collapse them.
     */
    val hasFields: Boolean get() = fields != null
    /** `null` = tags unavailable; empty = backend confirms there are no tags. */
    val hasTagMetadata: Boolean get() = tags != null
}

/** One safe Compose text projection of a source note field. [value] is never executed as HTML. */
data class FieldPresentation(
    val name: String,
    val value: String,
    val ordinal: Int?
)

/** Key/value metadata row. A null value means unsupported/unavailable, never numeric zero. */
data class MetadataRow(
    val label: String,
    val value: String?,
    val contentDescription: String = if (value == null) "$label, not available" else "$label: $value"
)
