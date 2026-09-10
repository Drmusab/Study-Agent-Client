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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "study_agent_settings")

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

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { e ->
            AppLogger.e(tag, "Error reading settings DataStore: ${e.message}", e)
        }
        .map { prefs ->
            AppSettings(
                selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
                sttLanguage = prefs[Keys.STT_LANGUAGE] ?: "en-US",
                ttsLanguage = prefs[Keys.TTS_LANGUAGE] ?: "en-US",
                speechRate = prefs[Keys.SPEECH_RATE] ?: 1.0f,
                speechPitch = prefs[Keys.SPEECH_PITCH] ?: 1.0f,
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
            val current = AppSettings(
                selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
                sttLanguage = prefs[Keys.STT_LANGUAGE] ?: "en-US",
                ttsLanguage = prefs[Keys.TTS_LANGUAGE] ?: "en-US",
                speechRate = prefs[Keys.SPEECH_RATE] ?: 1.0f,
                speechPitch = prefs[Keys.SPEECH_PITCH] ?: 1.0f,
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
            val updated = transform(current)
            updated.selectedProfileId?.let { prefs[Keys.SELECTED_PROFILE_ID] = it }
            prefs[Keys.STT_LANGUAGE] = updated.sttLanguage
            prefs[Keys.TTS_LANGUAGE] = updated.ttsLanguage
            prefs[Keys.SPEECH_RATE] = updated.speechRate
            prefs[Keys.SPEECH_PITCH] = updated.speechPitch
            prefs[Keys.HANDS_FREE_MODE] = updated.handsFreeMode
            prefs[Keys.AUTO_PLAY_QUESTION] = updated.autoPlayQuestion
            prefs[Keys.AUTO_PLAY_FEEDBACK] = updated.autoPlayFeedback
            prefs[Keys.AUTO_SUBMIT_TRANSCRIPT] = updated.autoSubmitTranscript
            prefs[Keys.CONFIRM_RATING] = updated.confirmRating
            prefs[Keys.SHOW_TRANSCRIPT] = updated.showTranscriptOnScreen
            prefs[Keys.USE_FAKE_AGENT] = updated.useFakeAgent
            prefs[Keys.DEBUG_LOGGING] = updated.debugLogging
            prefs[Keys.AUTO_RECONNECT] = updated.autoReconnect
            prefs[Keys.MAX_RECONNECT_ATTEMPTS] = updated.maxReconnectAttempts
            prefs[Keys.PING_INTERVAL_SECONDS] = updated.pingIntervalSeconds
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
