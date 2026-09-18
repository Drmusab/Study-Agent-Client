package com.studyagent.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

data class ConnectionVisual(
    val icon: ImageVector,
    val color: Color,
    val status: String,
    val detail: String
)

fun connectionVisualOf(state: ConnectionState): ConnectionVisual = when (state) {
    is ConnectionState.Ready -> {
        val ping = state.latencyMs?.let { " • ${it}ms" } ?: ""
        ConnectionVisual(
            Icons.Default.Cloud,
            AppColors.statusSuccess,
            "Ready",
            "${state.serverName}$ping"
        )
    }
    is ConnectionState.ReadyLegacy -> {
        val ping = state.latencyMs?.let { " • ${it}ms" } ?: ""
        ConnectionVisual(
            Icons.Default.Cloud,
            AppColors.statusSuccess,
            "Ready (legacy)",
            "${state.serverName ?: "${state.host}:${state.port}"}$ping"
        )
    }
    is ConnectionState.Connected -> {
        val ping = state.latencyMs?.let { " • ${it}ms" } ?: ""
        ConnectionVisual(
            Icons.Default.Cloud,
            AppColors.statusSuccess,
            "Connected",
            "${state.serverName ?: "${state.host}:${state.port}"}$ping"
        )
    }
    is ConnectionState.ConnectingTransport, is ConnectionState.Connecting ->
        ConnectionVisual(Icons.Default.Sync, AppColors.statusWarning, "Connecting", "Connecting…")
    is ConnectionState.TransportConnected ->
        ConnectionVisual(Icons.Default.Sync, AppColors.statusWarning, "Transport open", "Verifying agent…")
    is ConnectionState.Handshaking ->
        ConnectionVisual(Icons.Default.Sync, AppColors.statusWarning, "Handshaking", "Handshaking…")
    is ConnectionState.Authenticating ->
        ConnectionVisual(Icons.Default.Sync, AppColors.statusWarning, "Authenticating", "Authenticating…")
    is ConnectionState.NegotiatingCapabilities ->
        ConnectionVisual(Icons.Default.Sync, AppColors.statusWarning, "Negotiating", "Negotiating…")
    is ConnectionState.Resolving ->
        ConnectionVisual(Icons.Default.Sync, AppColors.statusWarning, "Resolving", "Resolving ${state.host}…")
    is ConnectionState.Reconnecting ->
        ConnectionVisual(
            Icons.Default.Sync,
            AppColors.statusWarning,
            "Reconnecting",
            "Reconnecting (${state.attempt}/${state.maxAttempts})…"
        )
    is ConnectionState.AuthenticationFailed ->
        ConnectionVisual(Icons.Default.Warning, AppColors.statusDanger, "Auth failed", "Authentication failed")
    is ConnectionState.TlsFailure ->
        ConnectionVisual(Icons.Default.Warning, AppColors.statusDanger, "TLS failed", "TLS failure")
    is ConnectionState.ProtocolMismatch ->
        ConnectionVisual(Icons.Default.Warning, AppColors.statusDanger, "Protocol mismatch", "Version mismatch")
    is ConnectionState.AgentUnavailable ->
        ConnectionVisual(Icons.Default.CloudOff, AppColors.statusDanger, "Agent unavailable", "Agent unavailable")
    is ConnectionState.ServerUnavailable ->
        ConnectionVisual(Icons.Default.CloudOff, AppColors.statusDanger, "Offline", "Server offline")
    is ConnectionState.NetworkUnavailable ->
        ConnectionVisual(Icons.Default.CloudOff, AppColors.statusDanger, "No network", "No network")
    is ConnectionState.HandshakeTimeout ->
        ConnectionVisual(Icons.Default.Warning, AppColors.statusDanger, "Handshake timeout", "No agent response")
    is ConnectionState.Error ->
        ConnectionVisual(Icons.Default.Warning, AppColors.statusDanger, "Error", "Connection error")
    is ConnectionState.Disconnected ->
        ConnectionVisual(Icons.Default.CloudOff, AppColors.statusNeutral, "Disconnected", "Disconnected")
}

@Composable
fun ConnectionBadge(
    connectionState: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = connectionVisualOf(connectionState)
    Row(
        modifier = modifier
            .clip(AppShape.chipShape)
            .background(AppColors.surfaceElevated)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS)
            .semantics {
                contentDescription = "Connection: ${visual.status}, ${visual.detail}. Open connection screen"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = visual.detail,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.contentPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun ConnectionBadgePreview() {
    ConnectionBadge(connectionState = ConnectionState.Disconnected, onClick = {})
}
