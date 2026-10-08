package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiMediaReferencePolicy
import com.studyagent.client.core.anki.AnkiRenderedCard

/**
 * GATE 16 — assembles one [AnkiCardDetails] from the exact card row mapping (GATE 07's
 * [AnkiDroidCardMapper], already identity-checked by the card gateway) and the exact note/note-type
 * mapping ([AnkiDroidNoteMapper]) (CHECKPOINT 08).
 *
 * The rules the checkpoint calls out, enforced here:
 *
 * - **unknown ≠ zero** — nothing in this assembly substitutes a default; every absent fact stays
 *   `null`/absent (INV-16-10);
 * - **card type ≠ queue type** — both are carried from where GATE 07 already kept them apart;
 * - **noteId ≠ cardRef** — the note snapshot supplies `noteRef`, never a card identity;
 * - **rendered HTML ≠ source fields** — content channels are copied verbatim from the rendered
 *   card and `fields` come from the note row; fields are never derived from HTML (AUDIT 4);
 * - **no duplication of normalization** — values are moved, never re-parsed or re-cased.
 *
 * Pure and JVM-testable. No provider types, no UI types.
 */
internal object AnkiDroidCardDetailsMapper {

    /**
     * @param deckName display name for the card's current deck, when the deck surface could
     * supply one; `null` + [deckNameUnavailable] records the degradation instead of inventing one.
     */
    fun map(
        rendered: AnkiRenderedCard,
        note: AnkiDroidNoteSnapshot,
        deckName: String?,
        deckNameUnavailable: Boolean
    ): AnkiCardDetails {
        val degradations = ArrayList<String>(rendered.degradations.size + note.degradations.size + 1)
        degradations.addAll(rendered.degradations)
        degradations.addAll(note.degradations)
        if (deckNameUnavailable) degradations.add(DEG_DECK_NAME_UNAVAILABLE)

        val mediaFiles = extractMediaNames(
            sources = listOfNotNull(
                note.fields?.joinToString(AnkiDroidApiContract.FIELD_SEPARATOR) { it.value },
                rendered.questionHtml,
                rendered.answerHtml
            )
        )

        return AnkiCardDetails(
            cardRef = rendered.ref,
            noteRef = note.noteRef,
            cardOrd = rendered.ref.cardOrd,
            deckRef = rendered.deckRef,
            deckName = deckName,
            noteTypeId = note.noteTypeId,
            noteTypeName = note.noteTypeName,
            templateName = rendered.metadata.templateName,
            questionHtml = rendered.questionHtml,
            answerHtml = rendered.answerHtml,
            questionText = rendered.questionText,
            answerText = rendered.answerText,
            pureAnswerText = rendered.pureAnswerText,
            fields = note.fields,
            tags = note.tags,
            flag = rendered.flag,
            cardType = rendered.metadata.cardType,
            queueState = rendered.metadata.queueState,
            scheduling = rendered.scheduling,
            originalDeckRef = rendered.metadata.originalDeckRef,
            noteCreatedEpochSeconds = rendered.metadata.noteCreatedEpochSeconds,
            noteModifiedEpochSeconds = note.noteModifiedEpochSeconds,
            mediaFiles = mediaFiles,
            degradations = degradations.distinct()
        )
    }

    private val SOUND_TAG = Regex("""(?i)\[sound:([^\[\]]+)]""")
    private val SRC_ATTRIBUTE = Regex("""(?i)\bsrc\s*=\s*["']([^"']+)["']""")

    /**
     * Logical media names referenced by source/rendered content (GATE 16 §22). Conservative text
     * scanning only — no HTML execution, no path access. Names are validated with the shared
     * [AnkiMediaReferencePolicy] (a logical filename, never a URI or traversal) and kept in
     * first-seen order; resolution remains the GATE 09 media resolver's job (INV-ANKI-CARD-20).
     */
    internal fun extractMediaNames(sources: List<String>): List<String> {
        val names = LinkedHashSet<String>()
        for (source in sources) {
            for (match in SOUND_TAG.findAll(source)) {
                names += match.groupValues[1].trim()
            }
            for (match in SRC_ATTRIBUTE.findAll(source)) {
                val candidate = match.groupValues[1].trim()
                // Remote/data URLs and paths are not backend media names; the policy rejects them.
                if (AnkiMediaReferencePolicy.validateLogicalName(candidate)) names += candidate
            }
        }
        return names
            .filter { AnkiMediaReferencePolicy.validateLogicalName(it) }
            .toList()
    }

    const val DEG_DECK_NAME_UNAVAILABLE: String = "card_deck_name_unavailable"
}
