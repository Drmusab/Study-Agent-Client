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
import com.studyagent.client.core.audio.StudyAudioMode
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

        // Speech recognition (STT)
        val STT_LANGUAGE_MODE = stringPreferencesKey("stt_language_mode")
        val STT_ENGLISH_LOCALE = stringPreferencesKey("stt_english_locale")
        val STT_ARABIC_LOCALE = stringPreferencesKey("stt_arabic_locale")
        val STT_AUTO_FALLBACK_LOCALE = stringPreferencesKey("stt_auto_fallback_locale")
        val STT_RECOGNITION_MODE = stringPreferencesKey("stt_recognition_mode")
        val STT_PREFER_ON_DEVICE = booleanPreferencesKey("stt_prefer_on_device")
        val STT_SHOW_PARTIAL = booleanPreferencesKey("stt_show_partial_transcript")
        val STT_MEDICAL_BIASING = booleanPreferencesKey("stt_medical_biasing")
        val STT_ANSWER_LENGTH = stringPreferencesKey("stt_answer_length")
        val STT_DEBUG_TRANSCRIPT_LOGGING = booleanPreferencesKey("stt_debug_transcript_logging")
        val LISTEN_FOR_SPOKEN_RATING = booleanPreferencesKey("listen_for_spoken_rating")
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

        // Study audio routing (§77). Absent on existing installs → AUTO, which keeps
        // headphones-when-available behaviour and never blocks a headset-less user.
        val STUDY_AUDIO_MODE = stringPreferencesKey("study_audio_mode")
        val PHONE_AUDIO_NOTICE_ACK = booleanPreferencesKey("phone_audio_notice_acknowledged")

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

        // Management-layer caches (Protocol v2 dashboard/control). These are
        // display caches only — the PC Study Agent always remains authoritative.
        val DASHBOARD_CACHE_JSON = stringPreferencesKey("dashboard_cache_json")
        val DASHBOARD_CACHE_SAVED_AT = longPreferencesKey("dashboard_cache_saved_at")
        val DECKS_CACHE_JSON = stringPreferencesKey("decks_cache_json")
        val DECKS_CACHE_SAVED_AT = longPreferencesKey("decks_cache_saved_at")
        val CONTROL_CONFIG_JSON = stringPreferencesKey("control_config_json")
        val CONTROL_CONFIG_SAVED_AT = longPreferencesKey("control_config_saved_at")
        val CONTROL_DRAFT_JSON = stringPreferencesKey("control_draft_json")
    }

    private fun readSettings(prefs: Preferences): AppSettings {
        val legacyRate = prefs[Keys.SPEECH_RATE] ?: 1.0f

        // ---- STT language migration (§126) ----
        // Installs from before language modes existed only have a single `stt_language`.
        // Honour it: an Arabic user must not be silently switched to Auto-English on upgrade.
        val legacySttLanguage = prefs[Keys.STT_LANGUAGE]
        val storedMode = prefs[Keys.STT_LANGUAGE_MODE]
        val languageMode: String
        val englishLocale: String
        val arabicLocale: String
        val autoFallback: String
        if (storedMode != null) {
            languageMode = storedMode
            englishLocale = prefs[Keys.STT_ENGLISH_LOCALE] ?: "en-US"
            arabicLocale = prefs[Keys.STT_ARABIC_LOCALE] ?: "ar-IQ"
            autoFallback = prefs[Keys.STT_AUTO_FALLBACK_LOCALE] ?: "en-US"
        } else if (legacySttLanguage != null) {
            val isArabic = legacySttLanguage.lowercase().startsWith("ar")
            languageMode = if (isArabic) "ARABIC" else "ENGLISH"
            englishLocale = if (isArabic) "en-US" else legacySttLanguage
            arabicLocale = if (isArabic) legacySttLanguage else "ar-IQ"
            autoFallback = if (isArabic) legacySttLanguage else "en-US"
        } else {
            // Fresh install: Auto English + Arabic is the intended default experience.
            languageMode = "AUTO_EN_AR"
            englishLocale = "en-US"
            arabicLocale = "ar-IQ"
            autoFallback = "en-US"
        }

        return AppSettings(
            selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
            sttLanguage = legacySttLanguage ?: englishLocale,
            sttLanguageMode = languageMode,
            sttEnglishLocale = englishLocale,
            sttArabicLocale = arabicLocale,
            sttAutoFallbackLocale = autoFallback,
            sttRecognitionMode = prefs[Keys.STT_RECOGNITION_MODE] ?: "AUTO",
            sttPreferOnDevice = prefs[Keys.STT_PREFER_ON_DEVICE] ?: true,
            sttShowPartialTranscript = prefs[Keys.STT_SHOW_PARTIAL] ?: true,
            sttMedicalBiasing = prefs[Keys.STT_MEDICAL_BIASING] ?: true,
            sttAnswerLength = prefs[Keys.STT_ANSWER_LENGTH] ?: "NORMAL",
            sttDebugTranscriptLogging = prefs[Keys.STT_DEBUG_TRANSCRIPT_LOGGING] ?: false,
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
            // Migration §78: no stored value (all existing users) → AUTO. A value written by a
            // different build that we do not recognise also falls back to AUTO instead of
            // throwing — a settings file can never brick study.
            studyAudioMode = StudyAudioMode.fromStorage(prefs[Keys.STUDY_AUDIO_MODE]),
            phoneAudioNoticeAcknowledged = prefs[Keys.PHONE_AUDIO_NOTICE_ACK] ?: false,
            handsFreeMode = prefs[Keys.HANDS_FREE_MODE] ?: true,
            autoPlayQuestion = prefs[Keys.AUTO_PLAY_QUESTION] ?: true,
            autoPlayFeedback = prefs[Keys.AUTO_PLAY_FEEDBACK] ?: true,
            autoSubmitTranscript = prefs[Keys.AUTO_SUBMIT_TRANSCRIPT] ?: true,
            confirmRating = prefs[Keys.CONFIRM_RATING] ?: false,
            // Previously model-only: this key did not exist, so the spoken-rating toggle
            // silently reset to its default on every launch.
            listenForSpokenRating = prefs[Keys.LISTEN_FOR_SPOKEN_RATING] ?: true,
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
        prefs[Keys.STT_LANGUAGE_MODE] = s.sttLanguageMode
        prefs[Keys.STT_ENGLISH_LOCALE] = s.sttEnglishLocale
        prefs[Keys.STT_ARABIC_LOCALE] = s.sttArabicLocale
        prefs[Keys.STT_AUTO_FALLBACK_LOCALE] = s.sttAutoFallbackLocale
        prefs[Keys.STT_RECOGNITION_MODE] = s.sttRecognitionMode
        prefs[Keys.STT_PREFER_ON_DEVICE] = s.sttPreferOnDevice
        prefs[Keys.STT_SHOW_PARTIAL] = s.sttShowPartialTranscript
        prefs[Keys.STT_MEDICAL_BIASING] = s.sttMedicalBiasing
        prefs[Keys.STT_ANSWER_LENGTH] = s.sttAnswerLength
        prefs[Keys.STT_DEBUG_TRANSCRIPT_LOGGING] = s.sttDebugTranscriptLogging
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
        prefs[Keys.STUDY_AUDIO_MODE] = s.studyAudioMode.name
        prefs[Keys.PHONE_AUDIO_NOTICE_ACK] = s.phoneAudioNoticeAcknowledged
        prefs[Keys.HANDS_FREE_MODE] = s.handsFreeMode
        prefs[Keys.AUTO_PLAY_QUESTION] = s.autoPlayQuestion
        prefs[Keys.AUTO_PLAY_FEEDBACK] = s.autoPlayFeedback
        prefs[Keys.AUTO_SUBMIT_TRANSCRIPT] = s.autoSubmitTranscript
        prefs[Keys.CONFIRM_RATING] = s.confirmRating
        prefs[Keys.LISTEN_FOR_SPOKEN_RATING] = s.listenForSpokenRating
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

    // ------------------------------------------------------------------
    // Management-layer cache accessors (dashboard snapshot, decks, study
    // control config/draft). Stored as raw JSON so the repository layer can
    // version its own models without touching [AppSettings].
    // ------------------------------------------------------------------

    val dashboardCacheJson: Flow<String?> = context.dataStore.data
        .catch { }
        .map { prefs -> prefs[Keys.DASHBOARD_CACHE_JSON] }

    val dashboardCacheSavedAt: Flow<Long?> = context.dataStore.data
        .catch { }
        .map { prefs -> prefs[Keys.DASHBOARD_CACHE_SAVED_AT] }

    val decksCacheJson: Flow<String?> = context.dataStore.data
        .catch { }
        .map { prefs -> prefs[Keys.DECKS_CACHE_JSON] }

    val decksCacheSavedAt: Flow<Long?> = context.dataStore.data
        .catch { }
        .map { prefs -> prefs[Keys.DECKS_CACHE_SAVED_AT] }

    val controlConfigJson: Flow<String?> = context.dataStore.data
        .catch { }
        .map { prefs -> prefs[Keys.CONTROL_CONFIG_JSON] }

    val controlConfigSavedAt: Flow<Long?> = context.dataStore.data
        .catch { }
        .map { prefs -> prefs[Keys.CONTROL_CONFIG_SAVED_AT] }

    val controlDraftJson: Flow<String?> = context.dataStore.data
        .catch { }
        .map { prefs -> prefs[Keys.CONTROL_DRAFT_JSON] }

    suspend fun setDashboardCache(json: String?, savedAtEpochMs: Long) {
        context.dataStore.edit { prefs ->
            if (json.isNullOrBlank()) {
                prefs.remove(Keys.DASHBOARD_CACHE_JSON)
                prefs.remove(Keys.DASHBOARD_CACHE_SAVED_AT)
            } else {
                prefs[Keys.DASHBOARD_CACHE_JSON] = json
                prefs[Keys.DASHBOARD_CACHE_SAVED_AT] = savedAtEpochMs
            }
        }
    }

    suspend fun setDecksCache(json: String?, savedAtEpochMs: Long) {
        context.dataStore.edit { prefs ->
            if (json.isNullOrBlank()) {
                prefs.remove(Keys.DECKS_CACHE_JSON)
                prefs.remove(Keys.DECKS_CACHE_SAVED_AT)
            } else {
                prefs[Keys.DECKS_CACHE_JSON] = json
                prefs[Keys.DECKS_CACHE_SAVED_AT] = savedAtEpochMs
            }
        }
    }

    suspend fun setControlConfigCache(json: String?, savedAtEpochMs: Long) {
        context.dataStore.edit { prefs ->
            if (json.isNullOrBlank()) {
                prefs.remove(Keys.CONTROL_CONFIG_JSON)
                prefs.remove(Keys.CONTROL_CONFIG_SAVED_AT)
            } else {
                prefs[Keys.CONTROL_CONFIG_JSON] = json
                prefs[Keys.CONTROL_CONFIG_SAVED_AT] = savedAtEpochMs
            }
        }
    }

    suspend fun setControlDraft(json: String?) {
        context.dataStore.edit { prefs ->
            if (json.isNullOrBlank()) prefs.remove(Keys.CONTROL_DRAFT_JSON) else prefs[Keys.CONTROL_DRAFT_JSON] = json
        }
    }
}
