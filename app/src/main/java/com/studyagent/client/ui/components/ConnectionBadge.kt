package com.studyagent.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.StatusGreen
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

@Composable
fun ConnectionBadge(
    connectionState: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dotColor, text) = when (connectionState) {
    val (dotColor, text, status) = when (connectionState) {
        is ConnectionState.Connected -> {
                        val pingText = connectionState.latencyMs?.let { " (${it}ms)" } ?: ""
            Pair(StatusGreen, "${connectionState.serverName ?: "${connectionState.host}:${connectionState.port}"}$pingText")
            val pingText = connectionState.latencyMs?.let { " • ${it}ms" } ?: ""
            Triple(
                AppColors.statusSuccess,
                "${connectionState.serverName ?: "${connectionState.host}:${connectionState.port}"}$pingText",
                "Connected"
            )
        }
        is ConnectionState.Connecting -> Pair(StatusAmber, "Connecting...")
        is ConnectionState.Reconnecting -> Pair(StatusAmber, "Reconnecting (${connectionState.attempt}/${connectionState.maxAttempts})...")
        is ConnectionState.AuthenticationFailed -> Pair(StatusRed, "Auth Failed")
        is ConnectionState.ServerUnavailable -> Pair(StatusRed, "Server Offline")
        is ConnectionState.NetworkUnavailable -> Pair(StatusRed, "No Network")
        is ConnectionState.Error -> Pair(StatusRed, "Connection Error")
        is ConnectionState.Disconnected -> Pair(TextMuted, "Disconnected")
        is ConnectionState.Connecting ->
            Triple(AppColors.statusWarning, "Connecting…", "Connecting")
        is ConnectionState.Reconnecting ->
            Triple(
                AppColors.statusWarning,
                "Reconnecting (${connectionState.attempt}/${connectionState.maxAttempts})…",
                "Reconnecting"
            )
        is ConnectionState.AuthenticationFailed ->
            Triple(AppColors.statusDanger, "Authentication failed", "Auth failed")
        is ConnectionState.ServerUnavailable ->
            Triple(AppColors.statusDanger, "Server offline", "Offline")
        is ConnectionState.NetworkUnavailable ->
            Triple(AppColors.statusDanger, "No network", "No network")
        is ConnectionState.Error ->
            Triple(AppColors.statusDanger, "Connection error", "Error")
        is ConnectionState.Disconnected ->
            Triple(AppColors.statusNeutral, "Disconnected", "Disconnected")
    }

    Row(
                    .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
            .clip(AppShape.chipShape)
            .background(AppColors.surfaceElevated)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS)
            .semantics { contentDescription = "Connection: $status, $text. Open connection screen" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
                Spacer(modifier = Modifier.width(8.dp))
                Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary
            color = AppColors.contentPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
