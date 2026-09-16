package com.studyagent.client.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

// ---------------------------------------------------------------------------
// Buttons (§13). Three intents only: primary, secondary, destructive.
// All are ≥48dp tall and use Title Case labels (no ALL CAPS).
// ---------------------------------------------------------------------------

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    tall: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = if (tall) 60.dp else 48.dp),
        shape = AppShape.buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.actionPrimary,
            contentColor = AppColors.onAction,
            disabledContainerColor = AppColors.surfaceInteractive,
            disabledContentColor = AppColors.contentMuted
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = AppColors.onAction
            )
            Spacer(modifier = Modifier.width(AppSpacing.XS))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (tall) 26.dp else 20.dp))
            Spacer(modifier = Modifier.width(AppSpacing.XS))
        }
        Text(
            text = text,
            style = if (tall) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = AppColors.contentPrimary
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = AppShape.buttonShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = AppColors.contentMuted
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(AppSpacing.XS))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    filled: Boolean = false
) {
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = AppShape.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.statusDangerStrong,
                contentColor = Color.White,
                disabledContainerColor = AppColors.surfaceInteractive,
                disabledContentColor = AppColors.contentMuted
            )
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(AppSpacing.XS))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    } else {
        SecondaryButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            icon = icon,
            contentColor = AppColors.statusDanger
        )
    }
}

/** Low-emphasis inline action (e.g. "Change", "Use this deck"). */
@Composable
fun InlineTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = AppColors.actionPrimary
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = AppColors.contentMuted
        )
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------------------
// Rows (§14): SettingRow (switch), ChoiceRow (radio), ExpandableSection.
// ---------------------------------------------------------------------------

/**
 * Title + optional description + trailing switch. The whole row is the toggle target
 * (≥56dp) and is announced as a single switch. [switchModifier] is exposed so screens
 * can attach a stable test tag to the Switch itself.
 */
@Composable
fun SettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    switchModifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = AppSpacing.XS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) AppColors.contentPrimary else AppColors.contentMuted
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentSecondary
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.SM))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = switchModifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.contentPrimary,
                checkedTrackColor = AppColors.actionPrimary,
                uncheckedThumbColor = AppColors.contentSecondary,
                uncheckedTrackColor = AppColors.surfaceElevated
            )
        )
    }
}

/** Single-select row with a leading radio; the whole row is the target. */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(vertical = AppSpacing.XXS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = AppColors.actionPrimary,
                unselectedColor = AppColors.contentSecondary,
                disabledSelectedColor = AppColors.contentMuted,
                disabledUnselectedColor = AppColors.contentMuted
            )
        )
        Spacer(modifier = Modifier.width(AppSpacing.XS))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) AppColors.contentPrimary else AppColors.contentMuted
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentSecondary
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Collapsible section header + body (progressive disclosure, §14). The header is a
 * 48dp button announcing expanded/collapsed state.
 */
@Composable
fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle, role = Role.Button)
                .padding(vertical = AppSpacing.XXS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.contentPrimary
                )
                if (summary != null && !expanded) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentSecondary,
                        maxLines = 1
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = AppColors.contentSecondary
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = AppSpacing.XS)) { content() }
        }
    }
}

/** Two-column "label … value" row used by summary cards and Diagnostics. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AppColors.contentPrimary,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.contentSecondary,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(AppSpacing.SM))
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.4f)
        )
    }
}

/** 1dp hairline for lists inside cards. */
@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppColors.divider)
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF1E293B)
@Composable
private fun ButtonsPreview() {
    Column(modifier = Modifier.padding(AppSpacing.MD), verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
        PrimaryButton(text = "Start Study", onClick = {}, modifier = Modifier.fillMaxWidth(), tall = true)
        SecondaryButton(text = "Pause", onClick = {}, modifier = Modifier.fillMaxWidth())
        DestructiveButton(text = "End Session", onClick = {}, modifier = Modifier.fillMaxWidth())
        SettingRow(title = "Auto-play Question", checked = true, onCheckedChange = {}, description = "Read each question aloud")
        ChoiceRow(title = "English", selected = true, onSelect = {})
    }
}
