package com.studyagent.client.ui.screens.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.studyagent.client.core.network.AgentConnectionSnapshot
import com.studyagent.client.core.network.ConnectionTestResult
import com.studyagent.client.core.network.TransportStatus
import com.studyagent.client.ui.components.*
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
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectionSnapshot by viewModel.connectionSnapshot.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()

    var showProfileDialog by rememberSaveable { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showTestDialog by rememberSaveable { mutableStateOf(false) }
    var testingProfile by remember { mutableStateOf<ServerProfile?>(null) }

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
                        Icon(Icons.Default.Add, contentDescription = "Add Profile", tint = AppColors.contentPrimary)
                    }
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = modifier.fillMaxSize().padding(innerPadding)
        ) {
            val contentWidth = maxWidth.coerceAtMost(AppSpacing.dashboardMaxWidth)
            LazyColumn(
                modifier = Modifier.fillMaxSize().width(contentWidth).align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    start = AppSpacing.contentGutter,
                    end = AppSpacing.contentGutter,
                    top = AppSpacing.XS,
                    bottom = AppSpacing.XL
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.screenSectionGap)
            ) {
                // Status hero with staged info
                item(key = "status") {
                    EnhancedStatusCard(
                        connectionState = connectionState,
                        snapshot = connectionSnapshot,
                        activeProfile = activeProfile,
                        onConnect = { viewModel.connect(activeProfile) },
                        onConnectOverride = { viewModel.connectWithOverride(activeProfile) },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }

                // Staged connection test results
                if (testResults.isNotEmpty()) {
                    item(key = "test-results") {
                        ConnectionTestResultsCard(
                            results = testResults,
                            isTesting = isTesting,
                            onDismiss = { viewModel.clearTestResults() }
                        )
                    }
                }

                // Failure explanation with stage
                connectionProblem(connectionState, connectionSnapshot)?.let { (title, message) ->
                    item(key = "problem") {
                        InfoBanner(
                            title = title,
                            message = message,
                            tone = BannerTone.WARNING,
                            actionLabel = "Retry Now",
                            onAction = { viewModel.connectWithOverride(activeProfile) }
                        )
                    }
                }

                // Quick actions
                item(key = "quick-actions") {
                    QuickActionsCard(
                        onFindPc = { /* TODO: NSD discovery */ },
                        onTestConnection = {
                            activeProfile?.let {
                                testingProfile = it
                                viewModel.testConnection(it)
                                showTestDialog = true
                            }
                        },
                        hasActiveProfile = activeProfile != null
                    )
                }

                item(key = "profiles-header") {
                    SectionHeader(title = "Saved PCs")
                }

                items(profiles, key = { it.id }) { profile ->
                    EnhancedProfileRow(
                        profile = profile,
                        isSelected = activeProfile?.id == profile.id,
                        canDelete = profiles.size > 1,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onEdit = {
                            editingProfile = profile
                            showProfileDialog = true
                        },
                        onDelete = { viewModel.deleteProfile(profile.id) },
                        onTest = {
                            testingProfile = profile
                            viewModel.testConnection(profile)
                            showTestDialog = true
                        }
                    )
                }

                item(key = "add-manual") {
                    SecondaryButton(
                        text = "Add PC Manually",
                        onClick = {
                            editingProfile = null
                            showProfileDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item(key = "help") {
                    HelpCard()
                }

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
        EnhancedProfileDialog(
            initialProfile = editingProfile,
            showAdvanced = showAdvanced,
            onToggleAdvanced = { showAdvanced = !showAdvanced },
            onDismiss = { showProfileDialog = false },
            onSave = { saved ->
                viewModel.saveProfile(saved)
                showProfileDialog = false
            }
        )
    }

    if (showTestDialog) {
        ConnectionTestDialog(
            profile = testingProfile,
            results = testResults,
            isTesting = isTesting,
            onDismiss = {
                showTestDialog = false
                viewModel.clearTestResults()
            },
            onRetest = {
                testingProfile?.let { viewModel.testConnection(it) }
            }
        )
    }
}

private fun connectionProblem(state: ConnectionState, snapshot: AgentConnectionSnapshot): Pair<String, String>? = when (state) {
    is ConnectionState.AuthenticationFailed ->
        "Authentication failed" to "The Study Agent rejected the auth token. Check the token in the profile and tap 'Edit' to replace it without deleting the profile."
    is ConnectionState.TlsFailure ->
        "Secure connection failed" to "Check secure server address/certificate. ${state.reason}"
    is ConnectionState.ProtocolMismatch ->
        "Protocol incompatible" to "Update app and PC Agent to compatible versions. Server: ${state.serverVersion ?: "unknown"}"
    is ConnectionState.HandshakeTimeout ->
        "Server found, but no Study Agent handshake was received" to "Verify host/port points to Study Agent, not another WebSocket service. PC reachable but handshake timeout."
    is ConnectionState.AgentUnavailable ->
        "Agent unavailable" to state.reason
    is ConnectionState.ServerUnavailable ->
        "Study Agent not reachable" to "Make sure the Study Agent is running on your PC and both devices are on the same network. ${state.reason}"
    is ConnectionState.NetworkUnavailable ->
        "No network" to "Turn on Wi-Fi or connect to Tailscale."
    is ConnectionState.Error ->
        "Connection problem" to "Couldn't keep the connection open. Details in Diagnostics. ${state.message}"
    else -> snapshot.problem?.let { problem ->
        problem.userMessage to problem.userAction
    }
}

@Composable
private fun EnhancedStatusCard(
    connectionState: ConnectionState,
    snapshot: AgentConnectionSnapshot,
    activeProfile: ServerProfile?,
    onConnect: () -> Unit,
    onConnectOverride: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = connectionVisualOf(connectionState)
    val busy = connectionState is ConnectionState.ConnectingTransport || connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Resolving || connectionState is ConnectionState.TransportConnected ||
            connectionState is ConnectionState.Handshaking || connectionState is ConnectionState.Authenticating ||
            connectionState is ConnectionState.NegotiatingCapabilities || connectionState is ConnectionState.Reconnecting

    val detail: String? = when (connectionState) {
        is ConnectionState.Ready -> buildString {
            append(connectionState.serverName)
            append(" v${connectionState.serverVersion ?: "?"}")
            append(" • Protocol ${connectionState.protocolVersion}")
            connectionState.latencyMs?.let { append(" • ${it}ms") }
        }
        is ConnectionState.ReadyLegacy -> buildString {
            append(connectionState.serverName ?: "${connectionState.host}:${connectionState.port}")
            append(" • Legacy v1")
            connectionState.latencyMs?.let { append(" • ${it}ms") }
        }
        is ConnectionState.Connected -> buildString {
            append(connectionState.serverName ?: "${connectionState.host}:${connectionState.port}")
            connectionState.latencyMs?.let { append(" • ${it}ms") }
        }
        is ConnectionState.ConnectingTransport, is ConnectionState.Connecting -> "${(connectionState as? ConnectionState.ConnectingTransport)?.host ?: (connectionState as? ConnectionState.Connecting)?.host}:${(connectionState as? ConnectionState.ConnectingTransport)?.port ?: (connectionState as? ConnectionState.Connecting)?.port}"
        is ConnectionState.TransportConnected -> "Transport open • Verifying Study Agent…"
        is ConnectionState.Handshaking -> "WebSocket open • Handshaking…"
        is ConnectionState.Authenticating -> "Authenticating…"
        is ConnectionState.NegotiatingCapabilities -> "Negotiating protocol…"
        is ConnectionState.Resolving -> "Resolving ${connectionState.host}…"
        is ConnectionState.Reconnecting -> "Attempt ${connectionState.attempt} of ${connectionState.maxAttempts} • Next in ${connectionState.nextRetryInMs}ms"
        else -> activeProfile?.let { "${it.name} • ${it.toWebSocketUrl()}" }
    }

    AppHeroCard(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.heroCardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Connection status: ${visual.status}" }) {
                Icon(imageVector = visual.icon, contentDescription = null, tint = visual.color, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(AppSpacing.SM))
                Column {
                    Text(text = visual.status, style = MaterialTheme.typography.headlineSmall, color = AppColors.contentPrimary)
                    if (detail != null) {
                        Text(text = detail, style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
                    }
                }
            }

            // Detailed staged status when ready
            if (connectionState is ConnectionState.Ready) {
                Spacer(modifier = Modifier.height(AppSpacing.MD))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusRow("Agent", "Ready ✓", true)
                    StatusRow("Protocol", "v${connectionState.protocolVersion} ✓", true)
                    StatusRow("Auth", if (connectionState.authenticated) "Authenticated ✓" else "Not authenticated", connectionState.authenticated)
                    if (connectionState.capabilities.isNotEmpty()) {
                        StatusRow("Capabilities", "${connectionState.capabilities.size} available", true)
                    }
                    snapshot.latencyMs?.let { StatusRow("Latency", "${it}ms", true) }
                    connectionState.agentId?.let { StatusRow("Agent ID", it.take(12) + "…", true) }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.MD))
            if (connectionState.isAgentReady) {
                SecondaryButton(text = "Disconnect", onClick = onDisconnect, contentColor = AppColors.statusDanger, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(text = if (busy) "Connecting…" else "Connect", onClick = onConnect, loading = busy, modifier = Modifier.weight(1f))
                    if (connectionState is ConnectionState.Reconnecting) {
                        SecondaryButton(text = "Retry Now", onClick = onConnectOverride, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, success: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = if (success) AppColors.statusSuccess else AppColors.contentPrimary)
    }
}

@Composable
private fun QuickActionsCard(onFindPc: () -> Unit, onTestConnection: () -> Unit, hasActiveProfile: Boolean) {
    AppCard {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
            Text(text = "Quick Setup", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            PrimaryButton(text = "Find My PC Automatically", onClick = onFindPc, modifier = Modifier.fillMaxWidth(), enabled = false)
            Text(text = "Automatic discovery via mDNS (optional) - manual IP always available", style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(text = "Test Connection", onClick = onTestConnection, modifier = Modifier.fillMaxWidth(), enabled = hasActiveProfile)
        }
    }
}

@Composable
private fun ConnectionTestResultsCard(results: List<ConnectionTestResult>, isTesting: Boolean, onDismiss: () -> Unit) {
    AppCard {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Connection Test", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
            }
            if (isTesting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            results.forEach { result ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Icon(
                        imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (result.success) AppColors.statusSuccess else AppColors.statusDanger,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "${result.stage.name}: ${result.message}", style = MaterialTheme.typography.bodySmall, color = AppColors.contentPrimary)
                        result.latencyMs?.let { Text(text = "${it}ms", style = MaterialTheme.typography.labelSmall, color = AppColors.contentSecondary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionTestDialog(profile: ServerProfile?, results: List<ConnectionTestResult>, isTesting: Boolean, onDismiss: () -> Unit, onRetest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test: ${profile?.name ?: "Unknown"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isTesting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                results.forEach { result ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (result.success) AppColors.statusSuccess else AppColors.statusDanger,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "${result.stage}: ${result.message} ${result.latencyMs?.let { "(${it}ms)" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (results.isEmpty() && !isTesting) {
                    Text("No results yet", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onRetest) { Text("Retest") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun EnhancedProfileRow(profile: ServerProfile, isSelected: Boolean, canDelete: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onTest: () -> Unit) {
    AppCard(color = if (isSelected) AppColors.surfaceElevated else AppColors.surfacePrimary) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(AppShape.cardShape).clickable(onClick = onSelect, role = Role.RadioButton).heightIn(min = 64.dp).padding(start = AppSpacing.cardPadding, end = AppSpacing.XXS).semantics { contentDescription = "Server profile ${profile.name}, " + if (isSelected) "active" else "tap to select" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = AppSpacing.SM, horizontal = 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = profile.name, style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(AppSpacing.XS))
                        Box(modifier = Modifier.clip(AppShape.chipShape).background(AppColors.statusInfoFill).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Active", style = MaterialTheme.typography.labelSmall, color = AppColors.statusInfo)
                        }
                    }
                    if (profile.isTailscale) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(modifier = Modifier.clip(AppShape.chipShape).background(AppColors.statusSuccess.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("Tailscale", style = MaterialTheme.typography.labelSmall, color = AppColors.statusSuccess)
                        }
                    }
                    if (profile.isSecure) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.Lock, contentDescription = "Secure", tint = AppColors.statusSuccess, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = profile.toWebSocketUrl(), style = AppTextStyle.monoValue, color = AppColors.contentSecondary)
                if (profile.lastConnectedAt != null || profile.lastLatencyMs != null) {
                    Text(text = buildString {
                        profile.lastProtocolVersion?.let { append("Protocol $it • ") }
                        profile.lastLatencyMs?.let { append("${it}ms • ") }
                        append("Last connected")
                    }, style = MaterialTheme.typography.labelSmall, color = AppColors.contentSecondary)
                }
            }
            IconButton(onClick = onTest) { Icon(Icons.Default.NetworkCheck, contentDescription = "Test ${profile.name}", tint = AppColors.contentSecondary) }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit ${profile.name}", tint = AppColors.contentSecondary) }
            if (canDelete) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete ${profile.name}", tint = AppColors.statusDanger) }
            }
        }
    }
}

@Composable
private fun HelpCard() {
    AppCard {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Having trouble connecting?", style = MaterialTheme.typography.titleSmall, color = AppColors.contentPrimary)
            Text(text = "• Same Wi-Fi? Check both devices on same network\n• Firewall? Allow Study Agent through Private networks\n• Tailscale? Use 100.x address or MagicDNS name\n• Emulator? Use 10.0.2.2, real phone uses actual IP\n• Token? Edit profile to replace expired token", style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
        }
    }
}

@Composable
private fun EnhancedProfileDialog(initialProfile: ServerProfile?, showAdvanced: Boolean, onToggleAdvanced: () -> Unit, onDismiss: () -> Unit, onSave: (ServerProfile) -> Unit) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "PC Agent") }
    var host by remember { mutableStateOf(initialProfile?.host ?: "192.168.1.100") }
    var portText by remember { mutableStateOf(initialProfile?.port?.toString() ?: "8765") }
    var path by remember { mutableStateOf(initialProfile?.path ?: "/ws") }
    var useTls by remember { mutableStateOf(initialProfile?.useTls ?: false) }
    var token by remember { mutableStateOf(initialProfile?.authToken ?: "") }

    val validation = remember(name, host, portText, path) {
        val portValid = portText.toIntOrNull()?.let { it in 1..65535 } == true
        val hostValid = host.isNotBlank()
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
        profile.validate()
    }

    val portValid = portText.toIntOrNull()?.let { it in 1..65535 } == true
    val hostValid = host.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surfacePrimary,
        shape = AppShape.dialogShape,
        title = { Text(if (initialProfile == null) "Add Server Profile" else "Edit Server Profile", style = MaterialTheme.typography.titleLarge, color = AppColors.contentPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("PC name") }, singleLine = true, shape = AppShape.fieldShape, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host or IP (LAN or Tailscale)") }, singleLine = true, isError = !hostValid, shape = AppShape.fieldShape, modifier = Modifier.fillMaxWidth())
                if (host == "127.0.0.1" || host == "localhost") {
                    Text("⚠️ 127.0.0.1 works only on emulator. Real phone needs PC's LAN/VPN address.", style = MaterialTheme.typography.labelSmall, color = AppColors.statusWarning)
                }
                if (showAdvanced) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                        OutlinedTextField(value = portText, onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Port") }, singleLine = true, isError = !portValid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = AppShape.fieldShape, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Path") }, singleLine = true, shape = AppShape.fieldShape, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Auth token (optional)") }, singleLine = true, shape = AppShape.fieldShape, modifier = Modifier.fillMaxWidth())
                    SettingRow(title = "Secure connection (WSS / TLS)", checked = useTls, onCheckedChange = { useTls = it })
                    if (!useTls) {
                        Text("Local unencrypted connection - ok for trusted LAN, use Tailscale or WSS for internet", style = MaterialTheme.typography.labelSmall, color = AppColors.contentSecondary)
                    }
                    if (validation is ServerProfile.ValidationResult.Invalid) {
                        Column {
                            validation.errors.forEach { err ->
                                Text("• $err", style = MaterialTheme.typography.labelSmall, color = AppColors.statusDanger)
                            }
                        }
                    }
                } else {
                    TextButton(onClick = onToggleAdvanced) { Text("Advanced: host, port, path, TLS, token") }
                }
            }
        },
        confirmButton = {
            PrimaryButton(text = "Save", enabled = portValid && hostValid && validation.isValid, onClick = {
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
            })
        },
        dismissButton = {
            Row {
                if (showAdvanced) TextButton(onClick = onToggleAdvanced) { Text("Simple") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Preview(name = "Status hero — connected", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun StatusHeroConnectedPreview() {
    EnhancedStatusCard(
        connectionState = ConnectionState.Ready("192.168.1.100", 8765, serverName = "Study PC", serverVersion = "2.3.0", protocolVersion = "2", latencyMs = 12L, capabilities = setOf("dashboard")),
        snapshot = AgentConnectionSnapshot.disconnected(),
        activeProfile = null,
        onConnect = {},
        onConnectOverride = {},
        onDisconnect = {}
    )
}
