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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.AppHeroCard
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.InlineTextButton
import com.studyagent.client.ui.components.PrimaryButton
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.SectionHeader
import com.studyagent.client.ui.components.SettingRow
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.components.connectionVisualOf
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing
import com.studyagent.client.ui.theme.AppTextStyle
import java.util.UUID

/**
 * Connection screen (§40): status first, one primary action, then the saved profiles.
 * Raw transport errors are summarised here; details stay in Diagnostics.
 */
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showProfileDialog by rememberSaveable { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }

    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = {
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
                            tint = AppColors.contentPrimary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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
                // Status hero: the one dominant card on this screen.
                item(key = "status") {
                    StatusHeroCard(
                        connectionState = connectionState,
                        activeProfile = activeProfile,
                        onConnect = { viewModel.connect(activeProfile) },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }

                // Failure explanation (§77): what happened + what to do. No stack traces.
                connectionProblem(connectionState)?.let { (title, message) ->
                    item(key = "problem") {
                        InfoBanner(
                            title = title,
                            message = message,
                            tone = BannerTone.WARNING,
                            actionLabel = "Retry",
                            onAction = { viewModel.connect(activeProfile) }
                        )
                    }
                }

                item(key = "profiles-header") {
                    SectionHeader(title = "Saved server profiles")
                }

                items(profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        isSelected = activeProfile?.id == profile.id,
                        canDelete = profiles.size > 1,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onEdit = {
                            editingProfile = profile
                            showProfileDialog = true
                        },
                        onDelete = { viewModel.deleteProfile(profile.id) }
                    )
                }

                // Developer toggle: last, clearly labelled, not a primary action.
                item(key = "mock") {
                    AppCard {
                        Column(modifier = Modifier.padding(horizontal = AppSpacing.cardPadding, vertical = AppSpacing.XS)) {
                            SettingRow(
                                title = "Mock Agent (development)",
                                description = "Run the full study loop without a PC or AI evaluator.",
                                checked = settings?.useFakeAgent ?: false,
                                onCheckedChange = { viewModel.toggleFakeAgent(it) }
                            )
                        }
                    }
                }
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

/** Short, human explanation for a failed state — or null when there is nothing to explain. */
private fun connectionProblem(state: ConnectionState): Pair<String, String>? = when (state) {
    is ConnectionState.AuthenticationFailed ->
        "Authentication failed" to "The Study Agent rejected the auth token. Check the token in the profile and try again."
    is ConnectionState.ServerUnavailable ->
        "Study Agent not reachable" to "Make sure the Study Agent is running on your PC and both devices are on the same network."
    is ConnectionState.NetworkUnavailable ->
        "No network" to "Turn on Wi-Fi or connect to the same network as your PC."
    is ConnectionState.Error ->
        "Connection problem" to "Couldn't keep the connection open. Details are in Diagnostics."
    else -> null
}

// ---------------------------------------------------------------------------
// Status hero
// ---------------------------------------------------------------------------

@Composable
private fun StatusHeroCard(
    connectionState: ConnectionState,
    activeProfile: ServerProfile?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = connectionVisualOf(connectionState)
    val busy = connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Reconnecting
    val detail: String? = when (connectionState) {
        is ConnectionState.Connected -> buildString {
            append(connectionState.serverName ?: "${connectionState.host}:${connectionState.port}")
            connectionState.latencyMs?.let { append(" • ").append(it).append(" ms") }
        }
        is ConnectionState.Connecting -> "${connectionState.host}:${connectionState.port}"
        is ConnectionState.Reconnecting -> "Attempt ${connectionState.attempt} of ${connectionState.maxAttempts}"
        else -> activeProfile?.let { "${it.name} • ${it.toWebSocketUrl()}" }
    }

    AppHeroCard(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.heroCardPadding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { contentDescription = "Connection status: ${visual.status}" }
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.color,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.SM))
                Column {
                    Text(
                        text = visual.status,
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppColors.contentPrimary
                    )
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.contentSecondary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.MD))
            if (connectionState.isConnected) {
                SecondaryButton(
                    text = "Disconnect",
                    onClick = onDisconnect,
                    contentColor = AppColors.statusDanger,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                PrimaryButton(
                    text = if (busy) "Connecting…" else "Connect",
                    onClick = onConnect,
                    loading = busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Profile row
// ---------------------------------------------------------------------------

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    isSelected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AppCard(color = if (isSelected) AppColors.surfaceElevated else AppColors.surfacePrimary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShape.cardShape)
                .clickable(onClick = onSelect, role = Role.RadioButton)
                .heightIn(min = 64.dp)
                .padding(start = AppSpacing.cardPadding, end = AppSpacing.XXS)
                .semantics {
                    contentDescription = "Server profile ${profile.name}, " +
                        if (isSelected) "active" else "tap to select"
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = AppSpacing.SM, horizontal = 0.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.contentPrimary
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(AppSpacing.XS))
                        Box(
                            modifier = Modifier
                                .clip(AppShape.chipShape)
                                .background(AppColors.statusInfoFill)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Active",
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
                    color = AppColors.contentSecondary
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit ${profile.name}", tint = AppColors.contentSecondary)
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${profile.name}", tint = AppColors.statusDanger)
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

    val portValid = portText.toIntOrNull()?.let { it in 1..65535 } == true
    val hostValid = host.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surfacePrimary,
        shape = AppShape.dialogShape,
        title = {
            Text(
                if (initialProfile == null) "Add Server Profile" else "Edit Server Profile",
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.contentPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                    shape = AppShape.fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host or IP (LAN or Tailscale)") },
                    singleLine = true,
                    isError = !hostValid,
                    shape = AppShape.fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        isError = !portValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = AppShape.fieldShape,
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
                    label = { Text("Auth token (optional)") },
                    singleLine = true,
                    shape = AppShape.fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingRow(
                    title = "Secure connection (WSS / TLS)",
                    checked = useTls,
                    onCheckedChange = { useTls = it }
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Save",
                enabled = portValid && hostValid,
                onClick = {
                    val profile = ServerProfile(
                        id = initialProfile?.id ?: UUID.randomUUID().toString(),
                        name = name.trim().ifEmpty { "PC Agent" },
                        host = host.trim(),
                        port = portText.toIntOrNull() ?: 8765,
                        path = path.trim().ifEmpty { "/ws" },
                        useTls = useTls,
                        authToken = token.trim().ifEmpty { null },
                        isDefault = initialProfile?.isDefault ?: false
                    )
                    onSave(profile)
                }
            )
        },
        dismissButton = {
            InlineTextButton(text = "Cancel", onClick = onDismiss, color = AppColors.contentSecondary)
        }
    )
}

// ---------------------------------------------------------------------------
// Previews (fake static data only)
// ---------------------------------------------------------------------------

@Preview(name = "Status hero — connected", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun StatusHeroConnectedPreview() {
    StatusHeroCard(
        connectionState = ConnectionState.Connected("192.168.1.100", 8765, serverName = "Study PC", latencyMs = 12L),
        activeProfile = null,
        onConnect = {},
        onDisconnect = {}
    )
}

@Preview(name = "Status hero — offline", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun StatusHeroOfflinePreview() {
    StatusHeroCard(
        connectionState = ConnectionState.ServerUnavailable("refused"),
        activeProfile = null,
        onConnect = {},
        onDisconnect = {}
    )
}
