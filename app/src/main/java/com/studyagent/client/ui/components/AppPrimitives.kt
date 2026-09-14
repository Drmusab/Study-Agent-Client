package com.studyagent.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing
import com.studyagent.client.ui.theme.AppTextStyle

/**
 * Shared visual primitives (design system, prompt §12).
 *
 * Presentation-only: everything receives plain values (strings, booleans, callbacks). No
 * repository, flow or session knowledge belongs in this file.
 */

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** Standard card: surface, rounded, no shadow (dark theme reads elevation via surface tone). */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = AppColors.surfacePrimary,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.cardShape,
        color = color,
        content = content
    )
}

/**
 * The single dominant card per screen (§16/§17): larger radius, more padding.
 * Every screen has at most one hero card.
 */
@Composable
fun AppHeroCard(
    modifier: Modifier = Modifier,
    color: Color = AppColors.surfacePrimary,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.heroCardShape,
        color = color,
        content = content
    )
}

/** Uppercased section title with optional trailing action (§12: AppSectionHeader). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = AppColors.contentSecondary,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = title }
        )
        action?.invoke()
    }
}

/** One metric: value dominates label (§11). Null values render nothing. */
@Composable
fun MetricTile(
    value: String?,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AppColors.contentPrimary
) {
    if (value == null) return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = AppTextStyle.metricValue,
            color = valueColor,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label.uppercase(),
            style = AppTextStyle.metricLabel,
            color = AppColors.contentMuted
        )
    }
}

/**
 * Status badge: dot + label, color is reinforcement only — the text always says the state
 * (§65). Used for system readiness and connection status.
 */
@Composable
fun StatusBadge(
    label: String,
    status: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .clip(AppShape.chipShape)
            .background(AppColors.surfaceElevated)
            .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS)
            .semantics { contentDescription = "$label: $status" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.contentSecondary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

// ---------------------------------------------------------------------------
// Banners
// ---------------------------------------------------------------------------

enum class BannerTone { NEUTRAL, INFO, WARNING, DANGER }

private fun bannerColors(tone: BannerTone): Pair<Color, Color> = when (tone) {
    BannerTone.NEUTRAL -> AppColors.contentSecondary to AppColors.surfaceElevated
    BannerTone.INFO -> AppColors.statusInfo to AppColors.statusInfoFill
    BannerTone.WARNING -> AppColors.statusWarning to AppColors.statusWarningFill
    BannerTone.DANGER -> AppColors.statusDanger to AppColors.statusDangerFill
}

/**
 * Info/error banner: answers "what happened — can I continue — what do I do" (§77).
 * [title] is the short state; [message] the one-line explanation; [actionLabel/onClick]
 * the single next step when there is one.
 */
@Composable
fun InfoBanner(
    message: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.NEUTRAL,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissible: Boolean = false,
    onDismiss: (() -> Unit)? = null
) {
    val (accentText, accentFill) = bannerColors(tone)
    AppCard(modifier = modifier, color = AppColors.surfacePrimary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accentFill.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (tone) {
                        BannerTone.DANGER -> Icons.Default.Warning
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = accentText,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.SM))
            Column(modifier = Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = accentText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentSecondary
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.width(AppSpacing.XS))
                TextButton(
                    onClick = onAction,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = accentText
                    )
                ) {
                    Text(actionLabel)
                }
            }
            if (dismissible && onDismiss != null) {
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = AppColors.contentMuted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty & loading
// ---------------------------------------------------------------------------

/**
 * Empty state: says what is missing and what happens next (§76).
 * Icon-only + title + one-line help; never a dead "No data".
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.LG),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppColors.contentMuted,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(AppSpacing.XS))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.contentPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.contentMuted
        )
    }
}

/**
 * Skeleton placeholder for a block whose geometry is known (§75).
 * Deliberately static (no shimmer): calmer and cheaper to render.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    color: Color = AppColors.surfaceElevated
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShape.cardShape)
            .background(color.copy(alpha = 0.55f))
    )
}

/** Skeleton card: title line + two metric rows, sized like a real card. */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    lines: Int = 2
) {
    AppCard(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            SkeletonBox(modifier = Modifier.height(12.dp).width(96.dp))
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            repeat(lines) {
                SkeletonBox(modifier = Modifier.height(28.dp))
                Spacer(modifier = Modifier.height(AppSpacing.XS))
            }
        }
    }
}
