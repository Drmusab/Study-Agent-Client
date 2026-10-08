package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.ui.screens.cardbrowser.displayLabel
import com.studyagent.client.ui.screens.cardbrowser.sortOptions
import com.studyagent.client.ui.theme.AppColors

/**
 * Sort affordance. It offers the backend's own default order plus every advertised sort key in both
 * explicit directions (§22) — never a sort key the backend would have to fake, and never a
 * client-side re-sort of an already loaded page.
 */
@Composable
fun CardBrowserSortMenu(
    capabilities: AnkiCardBrowserCapabilities,
    selected: AnkiCardSort,
    onSortSelected: (AnkiCardSort) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = sortOptions(capabilities)
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            enabled = options.size > 1,
            modifier = Modifier.semantics { contentDescription = "Sort cards. Current sort ${selected.displayLabel()}" }
        ) {
            Text("Sort: ${selected.displayLabel()}", color = AppColors.actionAccent)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        if (option.sort != selected) onSortSelected(option.sort)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Sort cards by ${option.label}${if (option.sort == selected) ", selected" else ""}"
                    }
                )
            }
        }
    }
}
