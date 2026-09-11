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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.voice.stt.AnswerEndpointProfile
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import com.studyagent.client.core.voice.tts.SegmentLanguage
import com.studyagent.client.core.voice.tts.TtsSettings
import com.studyagent.client.core.voice.tts.TtsVoiceInfo
import com.studyagent.client.core.voice.tts.VoiceLatency
import com.studyagent.client.core.voice.tts.VoiceQuality
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
    val englishVoices by viewModel.englishVoices.collectAsState()
    val arabicVoices by viewModel.arabicVoices.collectAsState()
    val previewing by viewModel.previewing.collectAsState()
    val recognitionCapabilities by viewModel.recognitionCapabilities.collectAsState()

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
            // ------------------------------------------------ Speech recognition (STT)
            item { SectionHeader("SPEECH RECOGNITION") }

            item {
                SettingsCard {
                    Text(
                        text = "Language",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    LanguageRadioItem(
                        title = "Auto — English + Arabic",
                        selected = settings.sttLanguageMode == "AUTO_EN_AR",
                        onClick = { viewModel.updateSettings { it.copy(sttLanguageMode = "AUTO_EN_AR") } }
                    )
                    LanguageRadioItem(
                        title = "English only",
                        selected = settings.sttLanguageMode == "ENGLISH",
                        onClick = { viewModel.updateSettings { it.copy(sttLanguageMode = "ENGLISH") } }
                    )
                    LanguageRadioItem(
                        title = "Arabic only (العربية)",
                        selected = settings.sttLanguageMode == "ARABIC",
                        onClick = { viewModel.updateSettings { it.copy(sttLanguageMode = "ARABIC") } }
                    )
                    Text(
                        text = "Auto uses on-device language detection where the recognizer " +
                            "supports it, and otherwise falls back to English.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }
            }

            item {
                SettingsCard {
                    Text(
                        text = "Recognition",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    // Vendor-neutral labels: the app never claims a specific provider,
                    // because it does not control which one is installed.
                    LanguageRadioItem(
                        title = "Auto",
                        selected = settings.sttRecognitionMode == "AUTO",
                        onClick = { viewModel.updateSettings { it.copy(sttRecognitionMode = "AUTO") } }
                    )
                    LanguageRadioItem(
                        title = "Prefer on-device (offline)",
                        selected = settings.sttRecognitionMode == "PREFER_ON_DEVICE",
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(sttRecognitionMode = "PREFER_ON_DEVICE", sttPreferOnDevice = true)
                            }
                        }
                    )
                    LanguageRadioItem(
                        title = "System default",
                        selected = settings.sttRecognitionMode == "SYSTEM_DEFAULT",
                        onClick = {
                            viewModel.updateSettings { it.copy(sttRecognitionMode = "SYSTEM_DEFAULT") }
                        }
                    )
                }
            }

            item {
                SettingsCard {
                    SettingToggleItem(
                        title = "Prefer Offline Recognition",
                        description = offlinePreferenceDescription(recognitionCapabilities),
                        checked = settings.sttPreferOnDevice,
                        onCheckedChange = { v ->
                            viewModel.updateSettings { it.copy(sttPreferOnDevice = v) }
                        }
                    )
                    SettingToggleItem(
                        title = "Auto-submit Answers",
                        description = "Send the transcript to the evaluator as soon as recognition " +
                            "finishes. Off: review, edit or retry before sending.",
                        checked = settings.autoSubmitTranscript,
                        onCheckedChange = { v ->
                            viewModel.updateSettings { it.copy(autoSubmitTranscript = v) }
                        }
                    )
                    SettingToggleItem(
                        title = "Spoken Ratings",
                        description = "Listen for \u0022Again / Hard / Good / Easy\u0022 after feedback. " +
                            "Off: the microphone stays closed and the on-screen buttons are used.",
                        checked = settings.listenForSpokenRating,
                        onCheckedChange = { v ->
                            viewModel.updateSettings { it.copy(listenForSpokenRating = v) }
                        }
                    )
                    SettingToggleItem(
                        title = "Confirm Ambiguous Ratings",
                        description = "Ask before applying a rating the recognizer was not confident " +
                            "about, instead of silently changing the card's schedule.",
                        checked = settings.confirmRating,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(confirmRating = v) } }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Answer Length",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    AnswerEndpointProfile.entries.forEach { profile ->
                        LanguageRadioItem(
                            title = profile.label,
                            selected = settings.sttAnswerLength == profile.name,
                            onClick = {
                                viewModel.updateSettings { it.copy(sttAnswerLength = profile.name) }
                            }
                        )
                    }
                }
            }

            item {
                SettingsCard {
                    Text(
                        text = "Advanced",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    SettingToggleItem(
                        title = "Live Partial Transcript",
                        description = "Show words as they are recognised, before the final result",
                        checked = settings.sttShowPartialTranscript,
                        onCheckedChange = { v ->
                            viewModel.updateSettings { it.copy(sttShowPartialTranscript = v) }
                        }
                    )
                    SettingToggleItem(
                        title = "Medical Vocabulary Biasing",
                        description = "Hint the recognizer with terms from the current question " +
                            "(epidural hematoma, midline shift, GCS, ICP...)",
                        checked = settings.sttMedicalBiasing,
                        onCheckedChange = { v ->
                            viewModel.updateSettings { it.copy(sttMedicalBiasing = v) }
                        }
                    )
                    SettingToggleItem(
                        title = "Log Full Transcripts (developer)",
                        description = "Writes recognised answer text to the log. Leave off: study " +
                            "answers can contain personal or clinical detail.",
                        checked = settings.sttDebugTranscriptLogging,
                        onCheckedChange = { v ->
                            viewModel.updateSettings { it.copy(sttDebugTranscriptLogging = v) }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    CapabilitySummary(
                        capabilities = recognitionCapabilities,
                        settings = settings,
                        onDownload = { locale -> viewModel.requestSpeechModelDownload(locale) },
                        onRefresh = { viewModel.refreshRecognitionCapabilities() }
                    )
                }
            }

            // ------------------------------------------------ Voice output (TTS)
            item { SectionHeader("VOICE OUTPUT (TTS)") }

            // Voice pickers: real installed voices + Auto recommended.
            item {
            SettingsCard {
                    VoicePicker(
                        title = "English Voice",
                        voices = englishVoices,
                        selectedVoiceId = settings.englishVoiceId,
                        onSelect = { id -> viewModel.updateSettings { it.copy(englishVoiceId = id) } }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    VoicePicker(
                        title = "Arabic Voice (الصوت العربي)",
                        voices = arabicVoices,
                        selectedVoiceId = settings.arabicVoiceId,
                        onSelect = { id -> viewModel.updateSettings { it.copy(arabicVoiceId = id) } }
                    )
                }
            }

            // Offline preference + previews.
            item {
            SettingsCard {
                    SettingToggleItem(
                        title = "Prefer Offline Voices",
                        description = "Use on-device voices first; studying keeps working without Internet",
                        checked = settings.preferOfflineVoices,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(preferOfflineVoices = v) } }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.previewVoice(SegmentLanguage.ENGLISH) },
                            enabled = previewing == null,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Preview English voice" }
                        ) {
                            Text(if (previewing == SegmentLanguage.ENGLISH) "Playing..." else "Preview English")
                        }
                        OutlinedButton(
                            onClick = { viewModel.previewVoice(SegmentLanguage.ARABIC) },
                            enabled = previewing == null,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Preview Arabic voice" }
                        ) {
                            Text(if (previewing == SegmentLanguage.ARABIC) "جارٍ التشغيل..." else "Preview Arabic")
                        }
                    }
                }
            }

            // Per-purpose rates + pitch.
            item {
            SettingsCard {
                    RateSlider(
                        label = "Question Speed",
                        value = settings.questionRate,
                        onChange = { v -> viewModel.updateSettings { it.copy(questionRate = v) } }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RateSlider(
                        label = "Feedback Speed",
                        value = settings.feedbackRate,
                        onChange = { v -> viewModel.updateSettings { it.copy(feedbackRate = v) } }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RateSlider(
                        label = "Explanation Speed",
                        value = settings.explanationRate,
                        onChange = { v -> viewModel.updateSettings { it.copy(explanationRate = v) } }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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

            // ------------------------------------------------ Advanced speech behavior
            item { SectionHeader("ADVANCED SPEECH") }

            item {
            SettingsCard {
                    SettingToggleItem(
                        title = "Automatic Language Detection",
                        description = "Switch voices inside mixed Arabic/English cards",
                        checked = settings.ttsAutoLanguageDetection,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(ttsAutoLanguageDetection = v) } }
                    )
                    SettingToggleItem(
                        title = "Medical Pronunciation",
                        description = "Spell abbreviations (G C S), verbalize units (milliliters) and ranges",
                        checked = settings.ttsMedicalPronunciation,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(ttsMedicalPronunciation = v) } }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "On Headset Disconnect", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text(
                        text = "What happens to speech when Bluetooth disconnects mid-question",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary
                    )
                    LanguageRadioItem(
                        title = "Pause speech (recommended)",
                        selected = settings.headsetDisconnectBehavior == HeadsetDisconnectBehavior.PAUSE_SPEECH,
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(headsetDisconnectBehavior = HeadsetDisconnectBehavior.PAUSE_SPEECH)
                            }
                        }
                    )
                    LanguageRadioItem(
                        title = "Continue on phone speaker",
                        selected = settings.headsetDisconnectBehavior == HeadsetDisconnectBehavior.CONTINUE_ON_PHONE,
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(headsetDisconnectBehavior = HeadsetDisconnectBehavior.CONTINUE_ON_PHONE)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Mic handoff gap: ${settings.ttsAcousticGapMs} ms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Pause between speech end and microphone activation (echo protection)",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary
                    )
                    Slider(
                        value = settings.ttsAcousticGapMs.toFloat(),
                        onValueChange = { v ->
                            viewModel.updateSettings {
                                it.copy(
                                    ttsAcousticGapMs = v.roundToInt().coerceIn(
                                        TtsSettings.MIN_ACOUSTIC_GAP_MS,
                                        TtsSettings.MAX_ACOUSTIC_GAP_MS
                                    )
                                )
                            }
                        },
                        valueRange = TtsSettings.MIN_ACOUSTIC_GAP_MS.toFloat()..TtsSettings.MAX_ACOUSTIC_GAP_MS.toFloat()
                    )
                }
            }

            // ------------------------------------------------ Study experience
            item { SectionHeader("STUDY EXPERIENCE") }

            item {
            SettingsCard {
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

            // ------------------------------------------------ Network & advanced
            item { SectionHeader("NETWORK & ADVANCED") }

            item {
            SettingsCard {
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

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------- composables

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = AccentTeal
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun VoicePicker(
    title: String,
    voices: List<TtsVoiceInfo>,
    selectedVoiceId: String?,
    onSelect: (String?) -> Unit
) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
    Spacer(modifier = Modifier.height(4.dp))
    LanguageRadioItem(
        title = "Auto — Recommended",
        selected = selectedVoiceId == null,
        onClick = { onSelect(null) }
    )
    if (voices.isEmpty()) {
        Text(
            text = "Installed voices appear here once the speech engine is ready.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp)
        )
    }
    voices.forEach { voice ->
        LanguageRadioItem(
            title = voiceLabel(voice),
            selected = selectedVoiceId == voice.id,
            onClick = { onSelect(voice.id) }
        )
    }
}

private fun voiceLabel(voice: TtsVoiceInfo): String {
    val badges = buildList {
        if (!voice.networkRequired) add("offline") else add("network")
        if (voice.quality >= VoiceQuality.HIGH) add("HQ")
        if (voice.latency <= VoiceLatency.LOW) add("fast")
    }.joinToString(", ")
    return "${voice.displayName} [$badges]"
}

@Composable
private fun RateSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text(
        text = "$label: ${(value * 100).roundToInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = TextPrimary
    )
    Slider(
        value = value.coerceIn(0.6f, 1.8f),
        onValueChange = onChange,
        valueRange = 0.6f..1.8f
    )
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

/**
 * Honest wording for the offline toggle (§23).
 *
 * The app never claims offline recognition is working unless it has actually established that
 * an on-device recognizer exists — a green toggle over a device with no on-device service is
 * worse than no toggle at all.
 */
private fun offlinePreferenceDescription(capabilities: RecognitionCapabilities): String = when {
    !capabilities.recognitionAvailable ->
        "No speech recognition service was found on this device."

    capabilities.onDeviceAvailable == true ->
        "Use the on-device recognizer when the language model is installed. Works without Internet."

    capabilities.onDeviceAvailable == false ->
        "This device has no on-device recognizer; recognition will use the network."

    else ->
        "Ask for the on-device recognizer when one is available. Whether it is honoured " +
            "depends on the installed recognition service."
}

/**
 * Capability facts plus a model-download affordance (§24/§26/§88).
 *
 * Everything the platform did not tell us renders as "Unknown" — the UI must not lie about
 * offline support, and it must not start a large download without the user asking.
 */
@Composable
private fun CapabilitySummary(
    capabilities: RecognitionCapabilities,
    settings: AppSettings,
    onDownload: (String) -> Unit,
    onRefresh: () -> Unit
) {
    CapabilityRow("Recognizer available", if (capabilities.recognitionAvailable) "Yes" else "No")
    CapabilityRow("On-device recognizer", maybe(capabilities.onDeviceAvailable))
    CapabilityRow("Language detection", maybe(capabilities.languageDetectionSupported))
    CapabilityRow("Language switching", maybe(capabilities.languageSwitchSupported))
    CapabilityRow("Vocabulary biasing", maybe(capabilities.vocabularyBiasingSupported))

    val englishState = languageModelLabel(capabilities, "en")
    val arabicState = languageModelLabel(capabilities, "ar")
    CapabilityRow("English model", englishState)
    CapabilityRow("Arabic model", arabicState)

    if (englishState == NEEDS_DOWNLOAD) {
        OutlinedButton(onClick = { onDownload(settings.sttEnglishLocale) }) {
            Text("Download English model", color = TextPrimary)
        }
    }
    if (arabicState == NEEDS_DOWNLOAD) {
        OutlinedButton(onClick = { onDownload(settings.sttArabicLocale) }) {
            Text("Download Arabic model", color = TextPrimary)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onRefresh) {
        Text("Re-check capabilities", color = TextPrimary)
    }
}

private const val NEEDS_DOWNLOAD = "Supported, not installed"

private fun languageModelLabel(capabilities: RecognitionCapabilities, primary: String): String {
    if (capabilities.installedLanguages.isEmpty() && capabilities.supportedLanguages.isEmpty()) {
        return "Unknown"
    }
    if (capabilities.installedLanguages.any { it.lowercase().startsWith(primary) }) return "Installed"
    return if (capabilities.supportedLanguages.any { it.lowercase().startsWith(primary) }) {
        NEEDS_DOWNLOAD
    } else {
        "Not supported"
    }
}

private fun maybe(value: Boolean?): String = when (value) {
    true -> "Yes"
    false -> "No"
    null -> "Unknown"
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
    }
}
