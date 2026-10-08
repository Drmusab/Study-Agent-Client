package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserRow
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/** Pure row rendering: no backend calls, WebView, original HTML, or mutation controls. */
@Composable
fun CardBrowserRow(
    row: CardBrowserRow,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = row.accessibleDescription() }
            .clickable(role = Role.Button, onClick = onOpen),
        color = AppColors.surfacePrimary,
        shape = AppShape.cardShape
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
        ) {
            Text(
                text = row.questionPreview ?: "Question preview unavailable",
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrLtr),
                color = AppColors.contentPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            row.answerPreview?.let { answer ->
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                    color = AppColors.contentSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            row.deckName?.let { deck ->
                Text(
                    text = deck,
                    style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.ContentOrLtr),
                    color = AppColors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (row.tags.isNotEmpty()) {
                Text(
                    text = row.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                    color = AppColors.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val metadata = buildList {
                row.flag?.let { add(it.displayLabel()) }
                row.type?.let { add(it.displayLabel()) }
                row.scheduling?.reps?.let { add("Reps $it") }
                row.scheduling?.lapses?.let { add("Lapses $it") }
                row.scheduling?.intervalDays?.let { add("Interval $it d") }
                if (row.suspended == true) add("Suspended")
                if (row.buried == true) add("Buried")
            }
            if (metadata.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = metadata.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun CardBrowserRow.accessibleDescription(): String = buildList {
    add("Question: ${questionPreview ?: "preview unavailable"}")
    answerPreview?.let { add("Answer: $it") }
    deckName?.let { add("Deck: $it") }
    flag?.let { add("Flag: ${it.displayLabel()}") }
    type?.let { add("Card type: ${it.displayLabel()}") }
    tags.take(3).takeIf { it.isNotEmpty() }?.let { add("Tags: ${it.joinToString(", ")}") }
    if (suspended == true) add("Suspended")
    if (buried == true) add("Buried")
    add("Open card details")
}.joinToString(". ")

private fun AnkiFlag.displayLabel(): String = when (this) {
    AnkiFlag.NONE -> "No flag"
    AnkiFlag.RED -> "Red flag"
    AnkiFlag.ORANGE -> "Orange flag"
    AnkiFlag.GREEN -> "Green flag"
    AnkiFlag.BLUE -> "Blue flag"
    AnkiFlag.PINK -> "Pink flag"
    AnkiFlag.TURQUOISE -> "Turquoise flag"
    AnkiFlag.PURPLE -> "Purple flag"
    AnkiFlag.UNKNOWN -> "Unknown flag"
}

private fun AnkiCardType.displayLabel(): String = when (this) {
    AnkiCardType.NEW -> "New"
    AnkiCardType.LEARNING -> "Learning"
    AnkiCardType.REVIEW -> "Review"
    AnkiCardType.RELEARNING -> "Relearning"
    AnkiCardType.UNKNOWN -> "Unknown type"
}
