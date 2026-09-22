package com.studyagent.client.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.AppSettingsPolicy
import com.studyagent.client.core.voice.stt.AnswerEndpointProfile
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import com.studyagent.client.core.voice.tts.SegmentLanguage
import com.studyagent.client.core.voice.tts.TtsEngineInfo
import com.studyagent.client.core.voice.tts.TtsSettings
import com.studyagent.client.core.voice.tts.TtsVoiceInfo
import com.studyagent.client.core.voice.tts.VoiceLatency
import com.studyagent.client.core.voice.tts.VoiceQuality
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthSnapshot
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.ChoiceRow
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.InlineTextButton
import com.studyagent.client.ui.components.KeyValueRow
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.SectionHeader
import com.studyagent.client.ui.components.SettingRow
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing
import kotlin.math.roundToInt

/** Semantics tag for the settings list, so a test can scroll to a row that is not composed yet. */
const val SETTINGS_LIST_TEST_TAG = "settings_list"

/**
 * Semantics tag for one toggle row's switch, e.g. `settings_switch_auto_play_question`.
 *
 * Tags are derived from the row title so a test selects a specific setting by identity rather than
 * by list position (§141).
 */
fun settingSwitchTestTag(title: String): String = "settings_switch_" + testTagSlug(title)

/** Semantics tag for the Anki integration Refresh action (GATE 02 §33). */
const val ANKI_REFRESH_TEST_TAG = "settings_anki_refresh"

/** Semantics tag for the Anki integration "Open AnkiDroid" action (GATE 02 §31). */
const val ANKI_OPEN_TEST_TAG = "settings_anki_open"

private fun testTagSlug(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text.lowercase()) {
        if (c.isLetterOrDigit()) sb.append(c) else if (sb.isNotEmpty() && sb.last() != '_') sb.append('_')
    }
    while (sb.isNotEmpty() && sb.last() == '_') sb.deleteCharAt(sb.length - 1)
    return sb.toString()
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val englishVoices by viewModel.englishVoices.collectAsStateWithLifecycle()
    val arabicVoices by viewModel.arabicVoices.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val previewing by viewModel.previewing.collectAsStateWithLifecycle()
    val recognitionCapabilities by viewModel.recognitionCapabilities.collectAsStateWithLifecycle()
    val persistenceError by viewModel.persistenceError.collectAsStateWithLifecycle()
    val ankiHealth by viewModel.ankiDroidHealth.collectAsStateWithLifecycle()
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = {
            StudyAgentTopBar(title = "Settings", onBack = onNavigateBack)
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
                .align(Alignment.TopCenter)
                .testTag(SETTINGS_LIST_TEST_TAG),
            contentPadding = PaddingValues(
                start = AppSpacing.contentGutter,
                end = AppSpacing.contentGutter,
                top = AppSpacing.XS,
                bottom = AppSpacing.XL
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            persistenceError?.let { error ->
                item(key = "persistence-error") {
                    InfoBanner(
                        title = "Couldn't save settings",
                        message = error,
                        tone = BannerTone.DANGER,
                        dismissible = true,
                        onDismiss = viewModel::clearPersistenceError
                    )
                }
            }

            // ------------------------------------------------ Study audio routing (§34/§79)
            //
            // Headphones are an enhancement, never a requirement. Automatic is the default and
            // studies happily on a bare phone; only "Headphones Required" ever blocks a start.
            item(key = "study-audio") { SettingsSectionLabel("Study audio") }

            item {
                SettingsCard {
                    Text(
                        text = "Audio Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.contentPrimary
                    )
                    StudyAudioMode.entries.forEach { mode ->
                        LanguageRadioItem(
                            title = mode.displayName,
                            description = mode.description,
                            selected = settings.studyAudioMode == mode,
                            onClick = { viewModel.updateSettings { it.copy(studyAudioMode = mode) } }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "When headphones disconnect",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.contentPrimary
                    )
                    Text(
                        text = "Applies only when headphones disappear mid-session. Starting " +
                            "without headphones always uses the phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentMuted
                    )
                    LanguageRadioItem(
                        title = "Pause voice study (recommended)",
                        selected = settings.headsetDisconnectBehavior == HeadsetDisconnectBehavior.PAUSE_SPEECH,
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(headsetDisconnectBehavior = HeadsetDisconnectBehavior.PAUSE_SPEECH)
                            }
                        }
                    )
                    LanguageRadioItem(
                        title = "Continue on phone",
                        selected = settings.headsetDisconnectBehavior == HeadsetDisconnectBehavior.CONTINUE_ON_PHONE,
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(headsetDisconnectBehavior = HeadsetDisconnectBehavior.CONTINUE_ON_PHONE)
                            }
                        }
                    )
                }
            }

            item(key = "study-experience") { SettingsSectionLabel("Study experience") }

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


            item(key = "speech-recognition") { SettingsSectionLabel("Speech recognition") }

            item {
                SettingsCard {
                    Text(
                        text = "Language",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.contentPrimary
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
                        color = AppColors.contentMuted,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }
            }

            item {
                SettingsCard {
                    Text(
                        text = "Recognition",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.contentPrimary
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
                        color = AppColors.contentPrimary
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

            item(key = "voice-output-tts") { SettingsSectionLabel("Voice output") }

            item {
                SettingsCard {
                    EnginePicker(
                        engines = engines,
                        selectedEngineId = settings.ttsEngineId,
                        onSelect = { id -> viewModel.updateSettings { it.copy(ttsEngineId = id) } }
                    )
                }
            }

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
                        SecondaryButton(
                            text = if (previewing == SegmentLanguage.ENGLISH) "Playing…" else "Preview English",
                            onClick = { viewModel.previewVoice(SegmentLanguage.ENGLISH) },
                            enabled = previewing == null,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Preview English voice" }
                        )
                        SecondaryButton(
                            text = if (previewing == SegmentLanguage.ARABIC) "جارٍ التشغيل…" else "Preview Arabic",
                            onClick = { viewModel.previewVoice(SegmentLanguage.ARABIC) },
                            enabled = previewing == null,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Preview Arabic voice" }
                        )
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
                    var pendingPitch by remember(settings.speechPitch) {
                        mutableFloatStateOf(
                            settings.speechPitch.coerceIn(
                                AppSettingsPolicy.MIN_SPEECH_PITCH,
                                AppSettingsPolicy.MAX_SPEECH_PITCH
                            )
                        )
                    }
                    Text(
                        text = "Pitch: ${(pendingPitch * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.contentPrimary
                    )
                    Slider(
                        value = pendingPitch,
                        onValueChange = { pendingPitch = it },
                        onValueChangeFinished = {
                            viewModel.updateSettings { s -> s.copy(speechPitch = pendingPitch) }
                        },
                        valueRange = AppSettingsPolicy.MIN_SPEECH_PITCH..AppSettingsPolicy.MAX_SPEECH_PITCH
                    )
                }
            }


            // ------------------------------------------------------------------------------
            // Anki integration (GATE 02). Status only: which Anki sources exist and whether the
            // local one can be reached. Backend selection is a later gate, and this section never
            // starts or changes a study session (§39/§78).
            // ------------------------------------------------------------------------------
            item(key = "anki-integration") { SettingsSectionLabel("Anki integration") }

            item(key = "anki-integration-status") {
                SettingsCard {
                    val guidance = ankiHealth.guidance
                    Text(
                        text = guidance.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.contentPrimary
                    )
                    guidance.action?.let { action ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = action,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.contentMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Only values we actually know are shown; an unknown spec renders as unknown
                    // rather than as a plausible-looking number (§100).
                    KeyValueRow(label = "AnkiDroid", value = ankiDroidStatusLabel(ankiHealth))
                    KeyValueRow(
                        label = "API spec",
                        value = ankiHealth.providerSpec?.toString() ?: "Unknown"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                        SecondaryButton(
                            text = "Refresh",
                            onClick = { viewModel.refreshAnkiDroidHealth() },
                            modifier = Modifier.testTag(ANKI_REFRESH_TEST_TAG)
                        )
                        SecondaryButton(
                            text = "Open AnkiDroid",
                            onClick = { viewModel.openAnkiDroid() },
                            // Nothing to open when it is provably absent; while the state is still
                            // unknown (or the provider is unreachable) the action stays attempted.
                            enabled = ankiHealth.availability !is AnkiAvailability.NotInstalled,
                            modifier = Modifier.testTag(ANKI_OPEN_TEST_TAG)
                        )
                    }
                }
            }

            // Basic vs Advanced (§36): everyday controls first; engineering knobs behind one switch.
            item(key = "advanced-toggle") {
                SettingsCard {
                    SettingRow(
                        title = "Show advanced settings",
                        description = "Recognition tuning, speech timing, capability checks and network options.",
                        checked = showAdvanced,
                        onCheckedChange = { showAdvanced = it },
                        switchModifier = Modifier.testTag(settingSwitchTestTag("Show advanced settings"))
                    )
                }
            }


            if (showAdvanced) {
                item(key = "advanced-recognition") { SettingsSectionLabel("Advanced recognition") }

                item {
                    SettingsCard {
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


                item(key = "advanced-speech") { SettingsSectionLabel("Advanced speech") }

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
                        Text(
                            text = "Headphone disconnect behaviour is configured under Study audio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.contentMuted
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        var pendingGap by remember(settings.ttsAcousticGapMs) {
                            mutableFloatStateOf(
                                settings.ttsAcousticGapMs.toFloat().coerceIn(
                                    TtsSettings.MIN_ACOUSTIC_GAP_MS.toFloat(),
                                    TtsSettings.MAX_ACOUSTIC_GAP_MS.toFloat()
                                )
                            )
                        }
                        Text(
                            text = "Mic handoff gap: ${pendingGap.roundToInt()} ms",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.contentPrimary
                        )
                        Text(
                            text = "Pause between speech end and microphone activation (echo protection)",
                            style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary
                        )
                        Slider(
                            value = pendingGap,
                            onValueChange = { pendingGap = it },
                            onValueChangeFinished = {
                                viewModel.updateSettings {
                                    it.copy(ttsAcousticGapMs = pendingGap.roundToInt())
                                }
                            },
                            valueRange = TtsSettings.MIN_ACOUSTIC_GAP_MS.toFloat()..TtsSettings.MAX_ACOUSTIC_GAP_MS.toFloat()
                        )
                    }
                }


                item(key = "network-advanced") { SettingsSectionLabel("Network & advanced") }

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

                        var pendingReconnectAttempts by remember(settings.maxReconnectAttempts) {
                            mutableFloatStateOf(
                                settings.maxReconnectAttempts.toFloat().coerceIn(
                                    AppSettingsPolicy.MIN_RECONNECT_ATTEMPTS.toFloat(),
                                    AppSettingsPolicy.MAX_RECONNECT_ATTEMPTS.toFloat()
                                )
                            )
                        }
                        Text(
                            text = "Reconnect attempts: ${pendingReconnectAttempts.roundToInt()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.contentPrimary
                        )
                        Slider(
                            value = pendingReconnectAttempts,
                            onValueChange = { pendingReconnectAttempts = it },
                            onValueChangeFinished = {
                                viewModel.updateSettings {
                                    it.copy(maxReconnectAttempts = pendingReconnectAttempts.roundToInt())
                                }
                            },
                            valueRange = AppSettingsPolicy.MIN_RECONNECT_ATTEMPTS.toFloat()..AppSettingsPolicy.MAX_RECONNECT_ATTEMPTS.toFloat()
                        )

                        var pendingPingInterval by remember(settings.pingIntervalSeconds) {
                            mutableFloatStateOf(
                                settings.pingIntervalSeconds.toFloat().coerceIn(
                                    AppSettingsPolicy.MIN_PING_INTERVAL_SECONDS.toFloat(),
                                    AppSettingsPolicy.MAX_PING_INTERVAL_SECONDS.toFloat()
                                )
                            )
                        }
                        Text(
                            text = "Ping interval: ${pendingPingInterval.roundToInt()} seconds",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.contentPrimary
                        )
                        Slider(
                            value = pendingPingInterval,
                            onValueChange = { pendingPingInterval = it },
                            onValueChangeFinished = {
                                viewModel.updateSettings {
                                    it.copy(pingIntervalSeconds = pendingPingInterval.roundToInt().toLong())
                                }
                            },
                            valueRange = AppSettingsPolicy.MIN_PING_INTERVAL_SECONDS.toFloat()..AppSettingsPolicy.MAX_PING_INTERVAL_SECONDS.toFloat()
                        )

                        Spacer(modifier = Modifier.height(AppSpacing.XS))
                        InlineTextButton(text = "Reset device settings", onClick = { showResetConfirmation = true })
                    }
                }
            }

        }
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            containerColor = AppColors.surfaceElevated,
            title = { Text("Reset device settings?", color = AppColors.contentPrimary) },
            text = {
                Text(
                    "This restores device preferences to defaults. Profiles, tokens, and cached Agent data are kept.",
                    color = AppColors.contentSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAppSettings()
                        showResetConfirmation = false
                    }
                ) { Text("Reset", color = AppColors.actionAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Cancel", color = AppColors.contentSecondary)
                }
            }
        )
    }
}

// ---------------------------------------------------------------- composables

/**
 * Short status word for the settings row (GATE 02 §33/§34).
 *
 * Pure mapping, no composition: deliberately a function rather than a composable, so the same
 * vocabulary can be reused by any screen (and unit-tested) without pulling in Compose.
 *
 * Deliberately a vocabulary a user understands: no `ContentProvider`, no `SecurityException`,
 * no exception class names ever reach normal UI. The technical detail lives in Diagnostics, and
 * the actionable sentence lives in the guidance above the row (§37).
 */
private fun ankiDroidStatusLabel(health: AnkiDroidHealthSnapshot): String = when (health.availability) {
    AnkiAvailability.Checking -> "Checking…"
    AnkiAvailability.NotInstalled -> "Not installed"
    is AnkiAvailability.ProviderUnavailable -> "Not reachable"
    is AnkiAvailability.PermissionRequired -> "Access not granted"
    AnkiAvailability.CollectionNotInitialized -> "Setup incomplete"
    is AnkiAvailability.Ready -> "Ready"
    is AnkiAvailability.TemporarilyUnavailable -> "Busy"
    is AnkiAvailability.Unsupported -> "Unsupported version"
    is AnkiAvailability.Fault -> "Check failed"
    AnkiAvailability.AgentDisconnected,
    AnkiAvailability.AgentAnkiUnavailable -> "Not checked"
}

@Composable
private fun SettingsSectionLabel(title: String) {
    SectionHeader(title = title, color = AppColors.actionAccent, modifier = Modifier.padding(top = AppSpacing.XS))
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    AppCard {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            content()
        }
    }
}

@Composable
private fun EnginePicker(
    engines: List<TtsEngineInfo>,
    selectedEngineId: String?,
    onSelect: (String?) -> Unit
) {
    Text(text = "TTS Engine", style = MaterialTheme.typography.titleSmall, color = AppColors.contentPrimary)
    Spacer(modifier = Modifier.height(4.dp))
    LanguageRadioItem(
        title = "System default",
        selected = selectedEngineId == null,
        onClick = { onSelect(null) }
    )
    engines.forEach { engine ->
        LanguageRadioItem(
            title = engine.label + if (engine.isSystemDefault) " (system default)" else "",
            selected = selectedEngineId == engine.packageName,
            onClick = { onSelect(engine.packageName) }
        )
    }
    if (selectedEngineId != null && engines.none { it.packageName == selectedEngineId }) {
        Text(
            text = "Saved engine is currently unavailable. System default is used temporarily; " +
                "your preference is retained.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.contentMuted,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
        )
    }
}

@Composable
private fun VoicePicker(
    title: String,
    voices: List<TtsVoiceInfo>,
    selectedVoiceId: String?,
    onSelect: (String?) -> Unit
) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, color = AppColors.contentPrimary)
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
            color = AppColors.contentMuted,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp)
        )
    }
    if (selectedVoiceId != null && voices.none { it.id == selectedVoiceId }) {
        Text(
            text = "Saved voice is currently unavailable. A compatible fallback is used; " +
                "your preference is retained.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.contentMuted,
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
    var pendingValue by remember(value) {
        mutableFloatStateOf(value.coerceIn(AppSettingsPolicy.MIN_TTS_RATE, AppSettingsPolicy.MAX_TTS_RATE))
    }
    Text(
        text = "$label: ${(pendingValue * 100).roundToInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.contentPrimary
    )
    Slider(
        value = pendingValue,
        onValueChange = { pendingValue = it },
        // Slider drags are local UI state; commit once at the end rather than
        // issuing a DataStore transaction for every pixel of movement.
        onValueChangeFinished = { onChange(pendingValue) },
        valueRange = AppSettingsPolicy.MIN_TTS_RATE..AppSettingsPolicy.MAX_TTS_RATE
    )
}

@Composable
private fun LanguageRadioItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    ChoiceRow(title = title, selected = selected, onSelect = onClick, description = description)
}

/**
 * One switch row. The switch carries [settingSwitchTestTag] so instrumented tests can address a
 * setting by identity rather than by list position.
 */
@Composable
private fun SettingToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingRow(
        title = title,
        description = description,
        checked = checked,
        onCheckedChange = onCheckedChange,
        switchModifier = Modifier.testTag(settingSwitchTestTag(title))
    )
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
        Spacer(modifier = Modifier.height(AppSpacing.XS))
        SecondaryButton(text = "Download English model", onClick = { onDownload(settings.sttEnglishLocale) })
    }
    if (arabicState == NEEDS_DOWNLOAD) {
        Spacer(modifier = Modifier.height(AppSpacing.XS))
        SecondaryButton(text = "Download Arabic model", onClick = { onDownload(settings.sttArabicLocale) })
    }

    Spacer(modifier = Modifier.height(8.dp))
    SecondaryButton(text = "Re-check capabilities", onClick = onRefresh)
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
    KeyValueRow(label = label, value = value)
}
