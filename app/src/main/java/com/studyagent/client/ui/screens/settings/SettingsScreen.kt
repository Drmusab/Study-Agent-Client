package com.studyagent.client.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.DarkBackground
import com.studyagent.client.ui.theme.DarkSurface
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Voice / STT Language Section
            item {
                Text(
                    text = "VOICE RECOGNITION (STT)",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentTeal
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LanguageRadioItem(
                            title = "English (United States)",
                            selected = settings.sttLanguage == "en-US",
                            onClick = { viewModel.updateSettings { it.copy(sttLanguage = "en-US") } }
                        )
                        LanguageRadioItem(
                            title = "Arabic (العربية)",
                            selected = settings.sttLanguage == "ar-SA",
                            onClick = { viewModel.updateSettings { it.copy(sttLanguage = "ar-SA") } }
                        )
                    }
                }
            }

            // Text to Speech Section
            item {
                Text(
                    text = "TEXT TO SPEECH (TTS)",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentTeal
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LanguageRadioItem(
                            title = "English Voice",
                            selected = settings.ttsLanguage == "en-US",
                            onClick = { viewModel.updateSettings { it.copy(ttsLanguage = "en-US") } }
                        )
                        LanguageRadioItem(
                            title = "Arabic Voice (صوت عربي)",
                            selected = settings.ttsLanguage == "ar-SA",
                            onClick = { viewModel.updateSettings { it.copy(ttsLanguage = "ar-SA") } }
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Speech Speed: ${(settings.speechRate * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Slider(
                            value = settings.speechRate,
                            onValueChange = { viewModel.updateSettings { s -> s.copy(speechRate = it) } },
                            valueRange = 0.6f..1.8f
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Pitch: ${(settings.speechPitch * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Slider(
                            value = settings.speechPitch,
                            onValueChange = { viewModel.updateSettings { s -> s.copy(speechPitch = it) } },
                            valueRange = 0.7f..1.4f
                        )
                    }
                }
            }

            // Study Experience Section
            item {
                Text(
                    text = "STUDY EXPERIENCE",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentTeal
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingToggleItem(
                            title = "Hands-Free Automatic Mode",
                            description = "Cycle automatically through question -> answer -> feedback -> rating",
                            checked = settings.handsFreeMode,
                            onCheckedChange = { v -> viewModel.updateSettings { it.copy(handsFreeMode = v) } }
                        )
                        SettingToggleItem(
                            title = "Auto-play Question",
                            description = "Read new questions aloud immediately upon arrival",
                            checked = settings.autoPlayQuestion,
                            onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoPlayQuestion = v) } }
                        )
                        SettingToggleItem(
                            title = "Auto-play Feedback",
                            description = "Read AI evaluation and comments aloud",
                            checked = settings.autoPlayFeedback,
                            onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoPlayFeedback = v) } }
                        )
                        SettingToggleItem(
                            title = "Show Live Transcript",
                            description = "Display speech-to-text words on screen in real time",
                            checked = settings.showTranscriptOnScreen,
                            onCheckedChange = { v -> viewModel.updateSettings { it.copy(showTranscriptOnScreen = v) } }
                        )
                    }
                }
            }

            // Advanced / Diagnostics Section
            item {
                Text(
                    text = "NETWORK & ADVANCED",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentTeal
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingToggleItem(
                            title = "Auto Reconnect",
                            description = "Automatically reconnect with exponential backoff if network drops",
                            checked = settings.autoReconnect,
                            onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoReconnect = v) } }
                        )
                        SettingToggleItem(
                            title = "Diagnostics Logging",
                            description = "Store sanitized session logs for troubleshooting",
                            checked = settings.debugLogging,
                            onCheckedChange = { v -> viewModel.updateSettings { it.copy(debugLogging = v) } }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LanguageRadioItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SettingToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
