package com.studyagent.client.ui.screens.control.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.ExpandableSection
import com.studyagent.client.ui.components.SettingRow
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/** Grouping card matching the dashboard visual language (§16). */
@Composable
fun ControlSection(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    content: @Composable () -> Unit
) {
    AppCard(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.contentSecondary,
                    modifier = Modifier.semantics { contentDescription = title }
                )
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.statusWarning,
                        modifier = Modifier
                            .clip(AppShape.chipShape)
                            .background(AppColors.statusWarningFill)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            content()
        }
    }
}

/** Chip-based single selection. Every chip is ≥40dp tall with 8dp gaps (§80). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), style = MaterialTheme.typography.labelLarge) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.surfaceInteractive,
                    selectedLabelColor = AppColors.actionPrimary,
                    labelColor = AppColors.contentSecondary
                ),
                modifier = Modifier.heightIn(min = 40.dp)
            )
        }
    }
}

@Composable
fun FieldLabel(text: String, description: String? = null) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, color = AppColors.contentPrimary)
    if (description != null) {
        Text(text = description, style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
    }
}

/** +/- numeric editor. Used for card targets, minutes, limits and Socratic knobs. */
@Composable
fun StepperRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    step: Int = 1,
    suffix: String = "",
    enabled: Boolean = true,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = AppSpacing.XXS)
            .semantics { contentDescription = "$label: $value $suffix" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            FieldLabel(label, description)
        }
        IconButton(
            onClick = { onValueChange((value - step).coerceIn(range)) },
            enabled = enabled && value > range.first
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease $label", tint = AppColors.contentSecondary)
        }
        Text(
            text = "$value$suffix",
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.contentPrimary,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.Center
        )
        IconButton(
            onClick = { onValueChange((value + step).coerceIn(range)) },
            enabled = enabled && value < range.last
        ) {
            Icon(Icons.Default.Add, contentDescription = "Increase $label", tint = AppColors.contentSecondary)
        }
    }
}

/** Switch row — thin wrapper over the design-system [SettingRow]. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    SettingRow(
        title = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        description = description,
        enabled = enabled,
        modifier = modifier
    )
}

@Composable
fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    valueLabel: String,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.XXS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) { FieldLabel(label, description) }
            Text(valueLabel, style = MaterialTheme.typography.titleMedium, color = AppColors.actionAccent)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = AppColors.actionPrimary,
                activeTrackColor = AppColors.actionPrimary,
                inactiveTrackColor = AppColors.surfaceElevated
            ),
            modifier = Modifier.semantics { contentDescription = "$label $valueLabel" }
        )
    }
}

/** Collapsible Advanced container (§62) so defaults stay uncluttered. */
@Composable
fun ExpandableAdvanced(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    ExpandableSection(title = title, expanded = expanded, onToggle = onToggle, content = content)
}
