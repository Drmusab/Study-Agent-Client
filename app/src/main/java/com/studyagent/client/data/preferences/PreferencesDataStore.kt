package com.studyagent.client.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.network.ProtocolJson
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import com.studyagent.client.core.voice.tts.TtsSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "study_agent_settings")

/**
 * Settings persistence.
 *
 * Migration strategy (§82): every key is read with `?: default`, so installs from
 * older versions simply adopt defaults for new fields — no crashes, no version
 * counters. The legacy `speech_rate` value seeds the new per-purpose rates when
 * those keys are absent, preserving the user's tuned speed.
 */
class PreferencesDataStore(
    private val context: Context
) {
    private val tag = "PreferencesDataStore"

    private object Keys {
        val SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        val STT_LANGUAGE = stringPreferencesKey("stt_language")
        val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")

        // Voice engine / voices
        val TTS_ENGINE_ID = stringPreferencesKey("tts_engine_id")
        val ENGLISH_VOICE_ID = stringPreferencesKey("english_voice_id")
        val ARABIC_VOICE_ID = stringPreferencesKey("arabic_voice_id")
        val PREFER_OFFLINE_VOICES = booleanPreferencesKey("prefer_offline_voices")

        // Per-purpose rates
        val QUESTION_RATE = floatPreferencesKey("question_rate")
        val FEEDBACK_RATE = floatPreferencesKey("feedback_rate")
        val EXPLANATION_RATE = floatPreferencesKey("explanation_rate")

        // Speech pipeline behavior
        val TTS_AUTO_LANGUAGE = booleanPreferencesKey("tts_auto_language_detection")
        val TTS_MEDICAL_PRONUNCIATION = booleanPreferencesKey("tts_medical_pronunciation")
        val TTS_ACOUSTIC_GAP_MS = intPreferencesKey("tts_acoustic_gap_ms")
        val HEADSET_DISCONNECT_BEHAVIOR = stringPreferencesKey("headset_disconnect_behavior")

        val HANDS_FREE_MODE = booleanPreferencesKey("hands_free_mode")
        val AUTO_PLAY_QUESTION = booleanPreferencesKey("auto_play_question")
        val AUTO_PLAY_FEEDBACK = booleanPreferencesKey("auto_play_feedback")
        val AUTO_SUBMIT_TRANSCRIPT = booleanPreferencesKey("auto_submit_transcript")
        val CONFIRM_RATING = booleanPreferencesKey("confirm_rating")
        val SHOW_TRANSCRIPT = booleanPreferencesKey("show_transcript")
        val USE_FAKE_AGENT = booleanPreferencesKey("use_fake_agent")
        val DEBUG_LOGGING = booleanPreferencesKey("debug_logging")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val MAX_RECONNECT_ATTEMPTS = intPreferencesKey("max_reconnect_attempts")
        val PING_INTERVAL_SECONDS = longPreferencesKey("ping_interval_seconds")
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
    }

    private fun readSettings(prefs: Preferences): AppSettings {
        val legacyRate = prefs[Keys.SPEECH_RATE] ?: 1.0f
        return AppSettings(
            selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
            sttLanguage = prefs[Keys.STT_LANGUAGE] ?: "en-US",
            ttsLanguage = prefs[Keys.TTS_LANGUAGE] ?: "en-US",
            speechRate = legacyRate,
            speechPitch = prefs[Keys.SPEECH_PITCH] ?: 1.0f,
            ttsEngineId = prefs[Keys.TTS_ENGINE_ID],
            englishVoiceId = prefs[Keys.ENGLISH_VOICE_ID],
            arabicVoiceId = prefs[Keys.ARABIC_VOICE_ID],
            preferOfflineVoices = prefs[Keys.PREFER_OFFLINE_VOICES] ?: true,
            // Migration: per-purpose rates fall back to the previously tuned global rate.
            questionRate = prefs[Keys.QUESTION_RATE] ?: legacyRate,
            feedbackRate = prefs[Keys.FEEDBACK_RATE] ?: legacyRate,
            explanationRate = prefs[Keys.EXPLANATION_RATE] ?: legacyRate,
            ttsAutoLanguageDetection = prefs[Keys.TTS_AUTO_LANGUAGE] ?: true,
            ttsMedicalPronunciation = prefs[Keys.TTS_MEDICAL_PRONUNCIATION] ?: true,
            ttsAcousticGapMs = (prefs[Keys.TTS_ACOUSTIC_GAP_MS] ?: TtsSettings.DEFAULT_ACOUSTIC_GAP_MS)
                .coerceIn(TtsSettings.MIN_ACOUSTIC_GAP_MS, TtsSettings.MAX_ACOUSTIC_GAP_MS),
            headsetDisconnectBehavior = parseHeadsetBehavior(
                prefs[Keys.HEADSET_DISCONNECT_BEHAVIOR]
            ),
            handsFreeMode = prefs[Keys.HANDS_FREE_MODE] ?: true,
            autoPlayQuestion = prefs[Keys.AUTO_PLAY_QUESTION] ?: true,
            autoPlayFeedback = prefs[Keys.AUTO_PLAY_FEEDBACK] ?: true,
            autoSubmitTranscript = prefs[Keys.AUTO_SUBMIT_TRANSCRIPT] ?: true,
            confirmRating = prefs[Keys.CONFIRM_RATING] ?: false,
            showTranscriptOnScreen = prefs[Keys.SHOW_TRANSCRIPT] ?: true,
            useFakeAgent = prefs[Keys.USE_FAKE_AGENT] ?: false,
            debugLogging = prefs[Keys.DEBUG_LOGGING] ?: true,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            maxReconnectAttempts = prefs[Keys.MAX_RECONNECT_ATTEMPTS] ?: 10,
            pingIntervalSeconds = prefs[Keys.PING_INTERVAL_SECONDS] ?: 15L
        )
    }

    private fun writeSettings(prefs: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        s.selectedProfileId?.let { prefs[Keys.SELECTED_PROFILE_ID] = it }
        prefs[Keys.STT_LANGUAGE] = s.sttLanguage
        prefs[Keys.TTS_LANGUAGE] = s.ttsLanguage
        prefs[Keys.SPEECH_RATE] = s.questionRate // legacy key mirrors question rate
        prefs[Keys.SPEECH_PITCH] = s.speechPitch
        writeOrRemove(prefs, Keys.TTS_ENGINE_ID, s.ttsEngineId)
        writeOrRemove(prefs, Keys.ENGLISH_VOICE_ID, s.englishVoiceId)
        writeOrRemove(prefs, Keys.ARABIC_VOICE_ID, s.arabicVoiceId)
        prefs[Keys.PREFER_OFFLINE_VOICES] = s.preferOfflineVoices
        prefs[Keys.QUESTION_RATE] = s.questionRate
        prefs[Keys.FEEDBACK_RATE] = s.feedbackRate
        prefs[Keys.EXPLANATION_RATE] = s.explanationRate
        prefs[Keys.TTS_AUTO_LANGUAGE] = s.ttsAutoLanguageDetection
        prefs[Keys.TTS_MEDICAL_PRONUNCIATION] = s.ttsMedicalPronunciation
        prefs[Keys.TTS_ACOUSTIC_GAP_MS] = s.ttsAcousticGapMs
        prefs[Keys.HEADSET_DISCONNECT_BEHAVIOR] = s.headsetDisconnectBehavior.name
        prefs[Keys.HANDS_FREE_MODE] = s.handsFreeMode
        prefs[Keys.AUTO_PLAY_QUESTION] = s.autoPlayQuestion
        prefs[Keys.AUTO_PLAY_FEEDBACK] = s.autoPlayFeedback
        prefs[Keys.AUTO_SUBMIT_TRANSCRIPT] = s.autoSubmitTranscript
        prefs[Keys.CONFIRM_RATING] = s.confirmRating
        prefs[Keys.SHOW_TRANSCRIPT] = s.showTranscriptOnScreen
        prefs[Keys.USE_FAKE_AGENT] = s.useFakeAgent
        prefs[Keys.DEBUG_LOGGING] = s.debugLogging
        prefs[Keys.AUTO_RECONNECT] = s.autoReconnect
        prefs[Keys.MAX_RECONNECT_ATTEMPTS] = s.maxReconnectAttempts
        prefs[Keys.PING_INTERVAL_SECONDS] = s.pingIntervalSeconds
    }

    private fun writeOrRemove(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String?
    ) {
        if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
    }

    private fun parseHeadsetBehavior(raw: String?): HeadsetDisconnectBehavior =
        try {
            raw?.let { HeadsetDisconnectBehavior.valueOf(it) } ?: HeadsetDisconnectBehavior.PAUSE_SPEECH
        } catch (e: Exception) {
            HeadsetDisconnectBehavior.PAUSE_SPEECH
        }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { e ->
            AppLogger.e(tag, "Error reading settings DataStore: ${e.message}", e)
        }
        .map { prefs -> readSettings(prefs) }

    val profilesFlow: Flow<List<ServerProfile>> = context.dataStore.data
        .catch { e ->
            AppLogger.e(tag, "Error reading profiles: ${e.message}", e)
        }
        .map { prefs ->
            val json = prefs[Keys.PROFILES_JSON]
            if (json.isNullOrBlank()) {
                listOf(ServerProfile.defaultLocalProfile(), ServerProfile.defaultEmulatorProfile())
            } else {
                try {
                    ProtocolJson.json.decodeFromString<List<ServerProfile>>(json)
                } catch (e: Exception) {
                    AppLogger.w(tag, "Failed to parse profiles JSON: ${e.message}")
                    listOf(ServerProfile.defaultLocalProfile(), ServerProfile.defaultEmulatorProfile())
                }
            }
        }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(readSettings(prefs))
            writeSettings(prefs, updated)
        }
    }

    suspend fun saveProfiles(profiles: List<ServerProfile>) {
        val json = ProtocolJson.json.encodeToString(profiles)
        context.dataStore.edit { prefs ->
            prefs[Keys.PROFILES_JSON] = json
        }
    }

    suspend fun setSelectedProfileId(profileId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_PROFILE_ID] = profileId
        }
    }
}
