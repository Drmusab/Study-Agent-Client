package com.studyagent.client.ui.screens.carddetails

import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiCardMetadata
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiMediaReferencePolicy
import com.studyagent.client.core.anki.AnkiRenderedCard
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * GATE 16 — UI presentation projection (CHECKPOINT 13). Pure Kotlin: no provider types, no
 * Compose types and no Android HTML execution surface (INV-16-13/16).
 *
 * Responsibilities:
 * - human-readable metadata labels/formatting live here, while source/domain values stay raw;
 * - nullable facts remain unavailable, while explicit zero remains visible (INV-16-10);
 * - backend field order is preserved (GATE 16 §4);
 * - field HTML is reduced to safe selectable plain text, never passed to WebView/Compose HTML;
 * - rendered card output is moved, not rewritten, into the existing GATE 08 renderer input.
 *
 * No scheduling decision is made here. Raw `due` and `original_due` are deliberately shown with
 * an explicit "raw, backend-defined" label because their units depend on queue state; this mapper
 * never converts those values to a date, never computes an interval and never predicts FSRS.
 */
object CardDetailsMapper {
    private val scriptOrStyle = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val htmlBreak = Regex("(?i)<br\\s*/?>|</(?:p|div|li|tr|h[1-6]|section|article)\\s*>")
    private val htmlTag = Regex("(?s)<[^>]*>")
    private val soundTag = Regex("(?i)\\[sound:([^\\[\\]]+)]")
    private val sourceAttribute = Regex("(?i)\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']")

    fun map(details: AnkiCardDetails, canOpenNoteEditor: Boolean = false): CardDetailsPresentation {
        val safeMediaFiles = details.mediaFiles.filter(AnkiMediaReferencePolicy::validateLogicalName)
        val fields = details.fields?.map { field ->
            FieldPresentation(
                name = field.name,
                value = safePlainText(field.value),
                ordinal = field.ordinal
            )
        }
        val overviewRows = listOf(
            MetadataRow("Deck", details.deckName),
            MetadataRow("Deck ID", details.deckRef?.deckId),
            MetadataRow("Note type", details.noteTypeName),
            MetadataRow("Card template", details.templateName),
            MetadataRow("Card ordinal (zero-based)", details.cardOrd?.toString()),
            MetadataRow("Card type", details.cardType?.displayLabel()),
            MetadataRow("Queue status", details.queueState?.displayLabel()),
            MetadataRow("Flag", details.flag?.displayLabel())
        )
        val schedulingRows = schedulingRows(details)
        val technicalRows = listOf(
            MetadataRow("Card ID", details.cardRef.cardId),
            MetadataRow("Note ID", details.noteId),
            MetadataRow("Note type ID", details.noteTypeId),
            MetadataRow("Original deck ID", details.originalDeckRef?.deckId),
            MetadataRow("Note created", details.noteCreatedEpochSeconds?.let(::formatTimestamp)),
            MetadataRow("Note last modified", details.noteModifiedEpochSeconds?.let(::formatTimestamp))
        )

        // The exact backend-normalized content channels are forwarded unchanged. Note source field
        // values are deliberately absent here: they are never inferred from either rendered side.
        val originalCard = if (details.questionHtml != null || details.questionText != null) {
            AnkiRenderedCard(
                ref = details.cardRef,
                questionHtml = details.questionHtml,
                answerHtml = details.answerHtml,
                questionText = details.questionText,
                answerText = details.answerText,
                pureAnswerText = details.pureAnswerText,
                media = safeMediaRefs(safeMediaFiles),
                scheduling = details.scheduling,
                metadata = AnkiCardMetadata(
                    deckName = details.deckName,
                    noteTypeName = details.noteTypeName,
                    // The legacy renderer metadata type has a non-null tags set and does not
                    // consume it; the details UI keeps the authoritative nullable tag state in
                    // CardDetailsPresentation.tags (null stays unavailable there).
                    tags = details.tags?.toSet() ?: emptySet(),
                    templateName = details.templateName,
                    queueState = details.queueState,
                    originalDeckRef = details.originalDeckRef,
                    cardType = details.cardType,
                    noteCreatedEpochSeconds = details.noteCreatedEpochSeconds,
                    noteModifiedEpochSeconds = details.noteModifiedEpochSeconds
                ),
                noteRef = details.noteRef,
                deckRef = details.deckRef,
                flag = details.flag,
                degradations = details.degradations
            )
        } else {
            null
        }

        return CardDetailsPresentation(
            cardRef = details.cardRef,
            title = details.templateName ?: "Card details",
            deckName = details.deckName,
            noteId = details.noteId,
            noteTypeName = details.noteTypeName,
            templateName = details.templateName,
            cardOrd = details.cardOrd,
            overviewRows = overviewRows,
            schedulingRows = schedulingRows,
            technicalRows = technicalRows,
            fields = fields,
            tags = details.tags?.toList(),
            flag = details.flag,
            cardType = details.cardType,
            queueState = details.queueState,
            questionText = details.questionText,
            answerText = details.answerText,
            pureAnswerText = details.pureAnswerText,
            originalCard = originalCard,
            mediaFiles = safeMediaFiles,
            degradations = details.degradations.toList(),
            canOpenNoteEditor = canOpenNoteEditor
        )
    }

    private fun schedulingRows(details: AnkiCardDetails): List<MetadataRow> {
        val scheduling = details.scheduling ?: return emptyList()
        val rows = mutableListOf<MetadataRow>()
        scheduling.intervalDays?.let {
            rows += MetadataRow("Current interval", "$it ${if (it == 1) "day" else "days"}")
        } ?: scheduling.intervalLabel?.let { rows += MetadataRow("Interval (backend label)", it) }
        scheduling.reps?.let { rows += MetadataRow("Reviews", it.toString()) }
        scheduling.lapses?.let { rows += MetadataRow("Lapses", it.toString()) }
        scheduling.dueEpochSeconds?.let { rows += MetadataRow("Due time", formatTimestamp(it)) }
        scheduling.rawDue?.let {
            rows += MetadataRow("Stored due value", "$it (raw; backend-defined units)")
        }
        scheduling.rawOriginalDue?.let {
            rows += MetadataRow("Stored original due value", "$it (raw; backend-defined units)")
        }
        scheduling.dueStateLabel?.let { rows += MetadataRow("Scheduling state", it) }
        scheduling.lastReviewEpochSeconds?.let { rows += MetadataRow("Last review", formatTimestamp(it)) }
        scheduling.easeFactor?.let {
            rows += MetadataRow("SM-2 ease factor", "${formatNumber(it)} (stored scale ×10)")
        }
        scheduling.fsrs?.stability?.let { rows += MetadataRow("FSRS stability", formatNumber(it)) }
        scheduling.fsrs?.difficulty?.let { rows += MetadataRow("FSRS difficulty", formatNumber(it)) }
        scheduling.fsrs?.desiredRetention?.let {
            rows += MetadataRow("FSRS desired retention", formatNumber(it))
        }
        return rows
    }

    /** Text-only field projection: never a WebView, never a Compose HTML renderer. */
    internal fun safePlainText(source: String): String {
        val withoutActiveBlocks = source.replace(scriptOrStyle, " ")
        val withSeparators = withoutActiveBlocks
            .replace(htmlBreak, " ")
            .replace(htmlTag, " ")
        val decoded = decodeSafeEntities(withSeparators)
        return collapseWhitespace(decoded)
    }

    private fun decodeSafeEntities(source: String): String {
        var text = source
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
        val numericEntity = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
        text = numericEntity.replace(text) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                token.drop(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            if (codePoint == null || !Character.isValidCodePoint(codePoint) ||
                codePoint in 0xD800..0xDFFF
            ) " " else String(Character.toChars(codePoint))
        }
        return text
    }

    private fun collapseWhitespace(value: String): String = buildString(value.length) {
        var pendingSpace = false
        value.forEach { character ->
            if (character.isWhitespace() || Character.isSpaceChar(character)) {
                if (isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }

    private fun safeMediaRefs(names: List<String>): List<AnkiMediaRef> = names
        .filter(AnkiMediaReferencePolicy::validateLogicalName)
        .map { AnkiMediaRef.BackendStream(it) }

    private fun formatTimestamp(epochSeconds: Long): String =
        runCatching { DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epochSeconds)) }
            .getOrDefault(epochSeconds.toString())

    private fun formatNumber(value: Double): String =
        if (value.isFinite() && value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun com.studyagent.client.core.anki.AnkiCardType.displayLabel(): String = when (this) {
        com.studyagent.client.core.anki.AnkiCardType.NEW -> "New"
        com.studyagent.client.core.anki.AnkiCardType.LEARNING -> "Learning"
        com.studyagent.client.core.anki.AnkiCardType.REVIEW -> "Review"
        com.studyagent.client.core.anki.AnkiCardType.RELEARNING -> "Relearning"
        com.studyagent.client.core.anki.AnkiCardType.UNKNOWN -> "Unknown"
    }

    private fun AnkiCardQueueState.displayLabel(): String = when (this) {
        AnkiCardQueueState.NEW -> "New"
        AnkiCardQueueState.LEARNING -> "Learning"
        AnkiCardQueueState.REVIEW -> "Review"
        AnkiCardQueueState.RELEARNING -> "Relearning"
        AnkiCardQueueState.SUSPENDED -> "Suspended"
        AnkiCardQueueState.BURIED -> "Buried"
        AnkiCardQueueState.UNKNOWN -> "Unknown"
    }

    private fun com.studyagent.client.core.anki.AnkiFlag.displayLabel(): String = when (this) {
        com.studyagent.client.core.anki.AnkiFlag.NONE -> "None"
        com.studyagent.client.core.anki.AnkiFlag.RED -> "Red"
        com.studyagent.client.core.anki.AnkiFlag.ORANGE -> "Orange"
        com.studyagent.client.core.anki.AnkiFlag.GREEN -> "Green"
        com.studyagent.client.core.anki.AnkiFlag.BLUE -> "Blue"
        com.studyagent.client.core.anki.AnkiFlag.PINK -> "Pink"
        com.studyagent.client.core.anki.AnkiFlag.TURQUOISE -> "Turquoise"
        com.studyagent.client.core.anki.AnkiFlag.PURPLE -> "Purple"
        com.studyagent.client.core.anki.AnkiFlag.UNKNOWN -> "Unknown"
    }
}
