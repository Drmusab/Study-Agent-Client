package com.studyagent.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.audio.EffectiveStudyAudioMode
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * Compact effective-route chip (§33/§68).
 *
 * Shows what the app is *actually doing*:
 *  - Headphones / Headphones + phone mic
 *  - Phone audio (speaker + built-in microphone)
 *  - Headphones required (the only state that blocks voice study)
 *
 * Phone audio is rendered in the same neutral style as everything else. It is a normal
 * route, not a warning, so there is no red styling.
 */
@Composable
fun AudioRouteIndicator(
    route: EffectiveStudyAudioRoute?,
    modifier: Modifier = Modifier
) {
    val effective = route?.effective ?: EffectiveStudyAudioMode.UNKNOWN
    val label = route?.statusLabel ?: "Audio"
    val detail = route?.let { describeRoute(it) }

    Row(
        modifier = modifier
            .clip(AppShape.chipShape)
            .background(AppColors.surfaceElevated)
            .heightIn(min = 32.dp)
            .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS)
            .semantics { contentDescription = "Audio route: $label${detail?.let { ", $it" } ?: ""}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (effective) {
                EffectiveStudyAudioMode.HEADSET,
                EffectiveStudyAudioMode.HYBRID -> Icons.Default.Headphones
                EffectiveStudyAudioMode.PHONE -> Icons.Default.PhoneAndroid
                EffectiveStudyAudioMode.BLOCKED -> Icons.Default.Lock
                EffectiveStudyAudioMode.UNKNOWN -> Icons.Default.VolumeUp
            },
            contentDescription = null,
            tint = when (effective) {
                EffectiveStudyAudioMode.HEADSET,
                EffectiveStudyAudioMode.HYBRID -> AppColors.voiceSpeaking
                EffectiveStudyAudioMode.BLOCKED -> AppColors.statusWarning
                else -> AppColors.contentSecondary
            },
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.contentSecondary
        )
        if (detail != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "• $detail",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.contentMuted
            )
        }
    }
}

/** One-line clarifier so "Phone" is unambiguous about both directions (§67). */
private fun describeRoute(route: EffectiveStudyAudioRoute): String = when (route.effective) {
    EffectiveStudyAudioMode.PHONE -> "speaker + mic"
    EffectiveStudyAudioMode.HEADSET -> "mic included"
    EffectiveStudyAudioMode.HYBRID -> "phone mic"
    EffectiveStudyAudioMode.BLOCKED -> "connect headphones"
    EffectiveStudyAudioMode.UNKNOWN -> "unknown"
}
