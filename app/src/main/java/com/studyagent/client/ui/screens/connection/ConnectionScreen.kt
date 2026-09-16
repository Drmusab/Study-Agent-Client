package com.studyagent.client.ui.screens.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.DarkBackground
import com.studyagent.client.ui.theme.DarkSurface
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.StatusGreen
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.TextSecondary
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.AppHeroCard
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing
import com.studyagent.client.ui.theme.AppTextStyle
import java.util.UUID

@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }

    Scaffold(
        containerColor = DarkBackground,
        containerColor = AppColors.appBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text(
                    text = "PC Connection",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                IconButton(onClick = {
                    editingProfile = null
                    showProfileDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Profile", tint = AccentTeal)
            StudyAgentTopBar(
                title = "PC Connection",
                onBack = onNavigateBack,
                trailing = {
                    IconButton(onClick = {
                        editingProfile = null
                        showProfileDialog = true
                    }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add Profile",
                            tint = AppColors.voiceSpeaking
                        )
                    }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "STATUS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when (connectionState) {
                                is ConnectionState.Connected -> StatusGreen
                                is ConnectionState.Connecting, is ConnectionState.Reconnecting -> StatusAmber
                                is ConnectionState.Disconnected -> TextMuted
                                else -> StatusRed
                            }
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = connectionState.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }
            val contentWidth = maxWidth.coerceAtMost(AppSpacing.dashboardMaxWidth)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .width(contentWidth)
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    start = AppSpacing.contentGutter,
                    end = AppSpacing.contentGutter,
                    top = AppSpacing.XS,
                    bottom = AppSpacing.XL
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.screenSectionGap)
            ) {
                // Live status card — the hero of this screen (§99).
                item {
                    StatusHeroCard(
                        connectionState = connectionState,
                        onConnect = { viewModel.connect(activeProfile) },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }

                        if (connectionState is ConnectionState.Connected) {
                            val conn = connectionState as ConnectionState.Connected
                            conn.latencyMs?.let { lat ->
                                Spacer(modifier = Modifier.height(6.dp))
                // Mock / Fake Agent Mode Toggle
                item {
                    AppCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(AppSpacing.cardPadding),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.SM)) {
                                Text(
                                    text = "Mock Agent (development)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AppColors.contentPrimary
                                )
                                Text(
                                    text = "Roundtrip Latency: ${lat}ms",
                                    text = "Test the full study loop without connecting to a PC or LLM",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentTeal
                                    color = AppColors.contentMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (connectionState.isConnected) {
                                OutlinedButton(
                                    onClick = { viewModel.disconnect() },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Disconnect", color = StatusRed)
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.connect(activeProfile) },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Connect Now")
                                }
                            }
                        }
                    }
                }
            }

            // Mock / Fake Agent Mode Toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Use Fake Agent (Mock Mode)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "Test full study loop without connecting to PC/LLM",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            Switch(
                                checked = settings?.useFakeAgent ?: false,
                                onCheckedChange = { viewModel.toggleFakeAgent(it) }
                            )
                        }
                        Switch(
                            checked = settings?.useFakeAgent ?: false,
                            onCheckedChange = { viewModel.toggleFakeAgent(it) }
                        )
                    }
                }
            }

            // Profile List Header
            item {
                Text(
                    text = "SAVED SERVER PROFILES",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentTeal
                )
            }
                // Profile List Header
                item {
                    Text(
                        text = "SAVED SERVER PROFILES",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.voiceSpeaking
                    )
                }

            items(profiles) { profile ->
                val isSelected = activeProfile?.id == profile.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            viewModel.selectProfile(profile.id)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) DarkSurfaceElevated else DarkSurface
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                items(profiles, key = { it.id }) { profile ->
                    val isSelected = activeProfile?.id == profile.id
                    AppCard(
                        color = if (isSelected) AppColors.surfaceElevated else AppColors.surfacePrimary
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(PrimaryBlue.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = PrimaryBlue)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AppShape.cardShape)
                                .clickable {
                                    viewModel.selectProfile(profile.id)
                                }
                                .padding(AppSpacing.cardPadding)
                                .semantics {
                                    contentDescription = "Server profile ${profile.name}, " +
                                        if (isSelected) "active" else "tap to select"
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.XS)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = AppColors.contentPrimary
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(AppShape.chipShape)
                                                .background(AppColors.actionPrimary.copy(alpha = 0.18f))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "ACTIVE",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AppColors.statusInfo
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = profile.toWebSocketUrl(),
                                    style = AppTextStyle.monoValue,
                                    color = AppColors.contentMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = profile.toWebSocketUrl(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }

                        Row {
                            IconButton(onClick = {
                                editingProfile = profile
                                showProfileDialog = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary)
                            }
                            if (profiles.size > 1) {
                                IconButton(onClick = { viewModel.deleteProfile(profile.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StatusRed)
                            Row {
                                IconButton(onClick = {
                                    editingProfile = profile
                                    showProfileDialog = true
                                }, modifier = Modifier.heightIn(min = 48.dp).width(48.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AppColors.contentSecondary)
                                }
                                if (profiles.size > 1) {
                                    IconButton(
                                        onClick = { viewModel.deleteProfile(profile.id) },
                                        modifier = Modifier.heightIn(min = 48.dp).width(48.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AppColors.statusDanger)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

            // Mock / Fake Agent Mode Toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Use Fake Agent (Mock Mode)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "Test full study loop without connecting to PC/LLM",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings?.useFakeAgent ?: false,
                            onCheckedChange = { viewModel.toggleFakeAgent(it) }
                        )
                    }
                }
            }

            // Profile List Header
            item {
                Text(
                    text = "SAVED SERVER PROFILES",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentTeal
                )
            }

            items(profiles) { profile ->
                val isSelected = activeProfile?.id == profile.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            viewModel.selectProfile(profile.id)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) DarkSurfaceElevated else DarkSurface
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(PrimaryBlue.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = PrimaryBlue)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = profile.toWebSocketUrl(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }

                        Row {
                            IconButton(onClick = {
                                editingProfile = profile
                                showProfileDialog = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary)
                            }
                            if (profiles.size > 1) {
                                IconButton(onClick = { viewModel.deleteProfile(profile.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StatusRed)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showProfileDialog) {
        ProfileDialog(
            initialProfile = editingProfile,
            onDismiss = { showProfileDialog = false },
            onSave = { saved ->
                viewModel.saveProfile(saved)
                showProfileDialog = false
            }
        )
    }
}
// ---------------------------------------------------------------------------
// Status hero
// ---------------------------------------------------------------------------

@Composable
private fun StatusHeroCard(
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dotColor, title) = when (connectionState) {
        is ConnectionState.Connected ->
            AppColors.statusSuccess to "Connected"
        is ConnectionState.Connecting, is ConnectionState.Reconnecting ->
            AppColors.statusWarning to connectionState.label
        is ConnectionState.Disconnected ->
            AppColors.statusNeutral to "Disconnected"
        else ->
            AppColors.statusDanger to connectionState.label
    }

    AppHeroCard(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.heroCardPadding)) {
            Text(
                text = "STATUS",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.contentMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.contentPrimary
                )
            }

            if (connectionState is ConnectionState.Connected) {
                connectionState.latencyMs?.let { lat ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Roundtrip latency: ${lat}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.voiceSpeaking
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.MD))
            if (connectionState.isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = AppShape.buttonShape
                ) {
                    Text("Disconnect", color = AppColors.statusDanger)
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = AppShape.buttonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.actionPrimaryStrong)
                ) {
                    Text("Connect", color = Color.White)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Profile dialog
// ---------------------------------------------------------------------------

@Composable
private fun ProfileDialog(
    initialProfile: ServerProfile?,
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "PC Agent") }
    var host by remember { mutableStateOf(initialProfile?.host ?: "192.168.1.100") }
    var portText by remember { mutableStateOf(initialProfile?.port?.toString() ?: "8765") }
    var path by remember { mutableStateOf(initialProfile?.path ?: "/ws") }
    var useTls by remember { mutableStateOf(initialProfile?.useTls ?: false) }
    var token by remember { mutableStateOf(initialProfile?.authToken ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surfaceElevated,
        shape = AppShape.dialogShape,
        title = {
            Text(if (initialProfile == null) "Add Server Profile" else "Edit Server Profile")
            Text(
                if (initialProfile == null) "Add Server Profile" else "Edit Server Profile",
                color = AppColors.contentPrimary
            )
        text = {
             Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
             Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    shape = AppShape.fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host / IP (LAN or Tailscale)") },
                    singleLine = true,
                    shape = AppShape.fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("Path") },
                        singleLine = true,
                        shape = AppShape.fieldShape,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Auth Token (Optional)") },
                    singleLine = true,
                    shape = AppShape.fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Secure WSS / TLS", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Secure WSS / TLS",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.contentPrimary
                    )
                    Switch(checked = useTls, onCheckedChange = { useTls = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: 8765
                    val profile = ServerProfile(
                        id = initialProfile?.id ?: UUID.randomUUID().toString(),
                        name = name.trim().ifEmpty { "PC Agent" },
                        host = host.trim(),
                        port = port,
                        path = path.trim().ifEmpty { "/ws" },
                        useTls = useTls,
                        authToken = token.trim().ifEmpty { null },
                        isDefault = initialProfile?.isDefault ?: false
                    )
                    onSave(profile)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


// ---------------------------------------------------------------------------
// Previews (fake static data only — no repositories, §90)
// ---------------------------------------------------------------------------

@Preview(name = "Status hero — connected", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun StatusHeroConnectedPreview() {
    StatusHeroCard(
        connectionState = ConnectionState.Connected("192.168.1.100", 8765, serverName = "Musab's PC", latencyMs = 12L),
        onConnect = {},
        onDisconnect = {}
    )
}

@Preview(name = "Status hero — offline", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun StatusHeroOfflinePreview() {
    StatusHeroCard(
        connectionState = ConnectionState.ServerUnavailable("refused"),
        onConnect = {},
        onDisconnect = {}
    )
}             
