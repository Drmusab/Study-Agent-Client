package com.studyagent.client.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

/**
 * The one top bar for all secondary screens (prompt §56): fixed height, consistent
 * horizontal padding, auto-mirrored back icon, title + optional subtitle, trailing slot.
 *
 * RTL-safe: the back icon is [Icons.AutoMirrored.Filled.ArrowBack] and all spacing is
 * logical (start/end).
 */
@Composable
fun StudyAgentTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    containerColor: Color = AppColors.appBackground
) {
    Surface(modifier = modifier.fillMaxWidth(), color = containerColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.XS, vertical = AppSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(start = AppSpacing.XXS)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AppColors.contentPrimary
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = AppSpacing.XXS),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.contentPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                trailing?.invoke()
            }
        }
    }
}
