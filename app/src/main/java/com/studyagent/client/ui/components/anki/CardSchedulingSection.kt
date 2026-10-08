package com.studyagent.client.ui.components.anki

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.studyagent.client.ui.screens.carddetails.MetadataRow

/** Shows only authoritative values already returned by the backend; performs no scheduler math. */
@Composable
fun CardSchedulingSection(
    rows: List<MetadataRow>,
    modifier: Modifier = Modifier
) {
    CardMetadataSection(
        title = "Scheduling and review",
        rows = rows,
        emptyMessage = "No scheduling metadata is available from this backend.",
        modifier = modifier
    )
}
