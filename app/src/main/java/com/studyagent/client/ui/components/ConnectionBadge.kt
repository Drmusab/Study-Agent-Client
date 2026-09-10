package com.studyagent.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.StatusGreen
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary

@Composable
fun ConnectionBadge(
    connectionState: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dotColor, text) = when (connectionState) {
        is ConnectionState.Connected -> {
            val pingText = connectionState.latencyMs?.let { " (${it}ms)" } ?: ""
            Pair(StatusGreen, "${connectionState.serverName ?: "${connectionState.host}:${connectionState.port}"}$pingText")
        }
        is ConnectionState.Connecting -> Pair(StatusAmber, "Connecting...")
        is ConnectionState.Reconnecting -> Pair(StatusAmber, "Reconnecting (${connectionState.attempt}/${connectionState.maxAttempts})...")
        is ConnectionState.AuthenticationFailed -> Pair(StatusRed, "Auth Failed")
        is ConnectionState.ServerUnavailable -> Pair(StatusRed, "Server Offline")
        is ConnectionState.NetworkUnavailable -> Pair(StatusRed, "No Network")
        is ConnectionState.Error -> Pair(StatusRed, "Connection Error")
        is ConnectionState.Disconnected -> Pair(TextMuted, "Disconnected")
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary
        )
    }
}
