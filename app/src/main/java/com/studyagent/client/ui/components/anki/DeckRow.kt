package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.core.anki.AnkiDeckSummary
import com.studyagent.client.ui.screens.library.DeckTreeItem
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * GATE 14 — the reusable deck row (checkpoint 13).
 *
 * Pure presentation: it renders one [DeckTreeItem.Deck] — display name, hierarchy indentation,
 * the backend-reported counts and the open affordance — and only dispatches the two intents it is
 * given. It never reads from a backend, never computes scheduling and never invents a count:
 * a `null` count renders as “unavailable”, an actual zero renders as `0` (INV-14-03). Study is
 * started from Deck Details through the session-start site, so the row exposes no study intent of
 * its own; counts are hidden entirely when the backend capability says they are unverified
 * (INV-14-11). Semantics expose name, counts and the available action without relying on color.
 */
@Composable
fun DeckRow(
    item: DeckTreeItem.Deck,
    showCounts: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = item.item.summary
    val counts = summary.counts
    val countDescription = deckCountDescription(summary, showCounts)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (AppSpacing.MD * item.depth.coerceAtMost(6).toFloat()), end = AppSpacing.XXS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.children.isNotEmpty()) {
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier.semantics {
                    contentDescription = "${if (expanded) "Collapse" else "Expand"} ${item.fullName}"
                }
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = AppColors.contentSecondary
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Surface(
            color = AppColors.surfacePrimary,
            shape = AppShape.cardShape,
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${item.fullName}. $countDescription. Open deck details."
                        role = Role.Button
                    }
                    .padding(horizontal = AppSpacing.MD, vertical = AppSpacing.SM)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrLtr),
                        color = AppColors.contentPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (summary.isFiltered == true || summary.isSelectedByBackend == true) {
                        Spacer(Modifier.width(AppSpacing.XS))
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)) {
                            if (summary.isFiltered == true) {
                                Surface(color = AppColors.statusInfoFill, shape = AppShape.chipShape) {
                                    Text(
                                        "Filtered",
                                        modifier = Modifier.padding(horizontal = AppSpacing.XS, vertical = AppSpacing.XXS),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.statusInfo
                                    )
                                }
                            }
                            if (summary.isSelectedByBackend == true) {
                                Surface(color = AppColors.statusSuccessFill, shape = AppShape.chipShape) {
                                    Text(
                                        "Selected",
                                        modifier = Modifier.padding(horizontal = AppSpacing.XS, vertical = AppSpacing.XXS),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.statusSuccess
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(AppSpacing.XS))
                if (showCounts) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                        DeckCountChip("New", counts?.new, Modifier.weight(1f))
                        DeckCountChip("Learning", counts?.learning, Modifier.weight(1f))
                        DeckCountChip("Review", counts?.review, Modifier.weight(1f))
                    }
                    counts?.totalDue?.let { due ->
                        Spacer(Modifier.height(AppSpacing.XS))
                        Text(
                            text = if (due == 0) "No cards currently due" else "$due due",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (due == 0) AppColors.contentMuted else AppColors.actionAccent
                        )
                    }
                } else {
                    Text("Counts unavailable", style = MaterialTheme.typography.bodySmall, color = AppColors.contentMuted)
                }
            }
        }
    }
}

/** One due-count cell. `null` renders as “—” (unknown), never as a fabricated zero. */
@Composable
fun DeckCountChip(label: String, count: Int?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = AppColors.surfaceElevated,
        shape = AppShape.chipShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.XS, vertical = AppSpacing.XXS),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count?.toString() ?: "—",
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.contentPrimary,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Accessibility text for one row: distinguishes an unknown count from a confirmed zero. */
fun deckCountDescription(summary: AnkiDeckSummary, showCounts: Boolean): String {
    if (!showCounts) return "New count unavailable; learning count unavailable; review count unavailable"
    val counts: AnkiDeckCounts? = summary.counts
    fun value(name: String, count: Int?): String =
        if (count == null) "$name count unavailable" else "$name $count"
    return listOf(
        value("New", counts?.new),
        value("Learning", counts?.learning),
        value("Review", counts?.review)
    ).joinToString(", ")
}
