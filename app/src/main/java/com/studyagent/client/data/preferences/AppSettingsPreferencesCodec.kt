package com.studyagent.client.data.preferences

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.AppSettingsPolicy
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior

/**
 * Stable keys and the pure mapping/migration contract for [AppSettings].
 *
 * This class deliberately knows nothing about Android Context or DataStore I/O.  It
 * is therefore possible to test every setting with an in-memory Preferences object,
 * while [PreferencesDataStore] remains responsible only for atomic edits and flows.
 */
object AppSettingsPreferencesCodec {
    const val CURRENT_SCHEMA_VERSION = 5
    const val LEGACY_SCHEMA_VERSION = 1

    object Keys {
        val SCHEMA_VERSION = intPreferencesKey("settings_schema_version")

        val SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")

        // Legacy STT/TTS values retained as migration contracts.
        val STT_LANGUAGE = stringPreferencesKey("stt_language")
        val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        /** New-only shadow that lets the deprecated model property round-trip exactly. */
        val SPEECH_RATE_MODEL_VALUE = floatPreferencesKey("speech_rate_model_value")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")

        // Speech recognition.
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

        // TTS engine and voices.
        val TTS_ENGINE_ID = stringPreferencesKey("tts_engine_id")
        val ENGLISH_VOICE_ID = stringPreferencesKey("english_voice_id")
        val ARABIC_VOICE_ID = stringPreferencesKey("arabic_voice_id")
        val PREFER_OFFLINE_VOICES = booleanPreferencesKey("prefer_offline_voices")

        // Per-purpose rates.
        val QUESTION_RATE = floatPreferencesKey("question_rate")
        val FEEDBACK_RATE = floatPreferencesKey("feedback_rate")
        val EXPLANATION_RATE = floatPreferencesKey("explanation_rate")

        // Speech pipeline.
        val TTS_AUTO_LANGUAGE = booleanPreferencesKey("tts_auto_language_detection")
        val TTS_MEDICAL_PRONUNCIATION = booleanPreferencesKey("tts_medical_pronunciation")
        val TTS_ACOUSTIC_GAP_MS = intPreferencesKey("tts_acoustic_gap_ms")
        val HEADSET_DISCONNECT_BEHAVIOR = stringPreferencesKey("headset_disconnect_behavior")

        // Audio routing.
        val STUDY_AUDIO_MODE = stringPreferencesKey("study_audio_mode")
        val PHONE_AUDIO_NOTICE_ACK = booleanPreferencesKey("phone_audio_notice_acknowledged")

        // Study behavior and network.
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
    }

    /** Read and normalize untrusted Preferences without mutating the source map. */
    fun readSettings(prefs: Preferences): AppSettings {
        val legacyLanguageRaw = prefs[Keys.STT_LANGUAGE]
        val legacyLanguage = legacyLanguageRaw?.let {
            AppSettingsPolicy.normalizeLocale(it, AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE)
        }
        val modeWasStored = prefs[Keys.STT_LANGUAGE_MODE] != null
        val languageMode = if (modeWasStored) {
            AppSettingsPolicy.normalizeLanguageMode(prefs[Keys.STT_LANGUAGE_MODE])
        } else {
            when {
                legacyLanguage?.startsWith("ar", ignoreCase = true) == true -> "ARABIC"
                legacyLanguage != null -> "ENGLISH"
                else -> AppSettingsPolicy.DEFAULT_STT_LANGUAGE_MODE
            }
        }
        val englishLocale = if (!modeWasStored && legacyLanguage != null &&
            languageMode == "ENGLISH"
        ) {
            legacyLanguage
        } else {
            AppSettingsPolicy.normalizeLocale(
                prefs[Keys.STT_ENGLISH_LOCALE],
                AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE
            )
        }
        val arabicLocale = if (!modeWasStored && legacyLanguage != null &&
            languageMode == "ARABIC"
        ) {
            legacyLanguage
        } else {
            AppSettingsPolicy.normalizeLocale(
                prefs[Keys.STT_ARABIC_LOCALE],
                AppSettingsPolicy.DEFAULT_ARABIC_LOCALE
            )
        }
        val autoFallback = if (!modeWasStored && legacyLanguage != null) {
            legacyLanguage
        } else {
            AppSettingsPolicy.normalizeLocale(
                prefs[Keys.STT_AUTO_FALLBACK_LOCALE],
                AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE
            )
        }

        val hasLegacyRate = prefs[Keys.SPEECH_RATE] != null
        val legacyRate = AppSettingsPolicy.normalizeRate(
            prefs[Keys.SPEECH_RATE],
            AppSettingsPolicy.DEFAULT_TTS_RATE
        )
        val speechRateModelValue = AppSettingsPolicy.normalizeRate(
            prefs[Keys.SPEECH_RATE_MODEL_VALUE] ?: prefs[Keys.SPEECH_RATE],
            AppSettingsPolicy.DEFAULT_TTS_RATE
        )

        val settings = AppSettings(
            selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
            sttLanguage = legacyLanguage ?: englishLocale,
            sttLanguageMode = languageMode,
            sttEnglishLocale = englishLocale,
            sttArabicLocale = arabicLocale,
            sttAutoFallbackLocale = autoFallback,
            sttRecognitionMode = prefs[Keys.STT_RECOGNITION_MODE] ?: AppSettingsPolicy.DEFAULT_STT_RECOGNITION_MODE,
            sttPreferOnDevice = prefs[Keys.STT_PREFER_ON_DEVICE] ?: true,
            sttShowPartialTranscript = prefs[Keys.STT_SHOW_PARTIAL] ?: true,
            sttMedicalBiasing = prefs[Keys.STT_MEDICAL_BIASING] ?: true,
            sttAnswerLength = prefs[Keys.STT_ANSWER_LENGTH] ?: AppSettingsPolicy.DEFAULT_STT_ANSWER_LENGTH,
            sttDebugTranscriptLogging = prefs[Keys.STT_DEBUG_TRANSCRIPT_LOGGING] ?: false,
            ttsLanguage = prefs[Keys.TTS_LANGUAGE] ?: AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE,
            speechRate = speechRateModelValue,
            speechPitch = prefs[Keys.SPEECH_PITCH] ?: AppSettingsPolicy.DEFAULT_SPEECH_PITCH,
            ttsEngineId = prefs[Keys.TTS_ENGINE_ID],
            englishVoiceId = prefs[Keys.ENGLISH_VOICE_ID],
            arabicVoiceId = prefs[Keys.ARABIC_VOICE_ID],
            preferOfflineVoices = prefs[Keys.PREFER_OFFLINE_VOICES] ?: true,
            // A legacy global rate is a migration source only. A fresh install must
            // retain AppSettings' independent feedback default of 1.05.
            questionRate = prefs[Keys.QUESTION_RATE] ?: if (hasLegacyRate) legacyRate else AppSettingsPolicy.DEFAULT_TTS_RATE,
            feedbackRate = prefs[Keys.FEEDBACK_RATE] ?: if (hasLegacyRate) legacyRate else AppSettingsPolicy.DEFAULT_FEEDBACK_RATE,
            explanationRate = prefs[Keys.EXPLANATION_RATE] ?: if (hasLegacyRate) legacyRate else AppSettingsPolicy.DEFAULT_TTS_RATE,
            ttsAutoLanguageDetection = prefs[Keys.TTS_AUTO_LANGUAGE] ?: true,
            ttsMedicalPronunciation = prefs[Keys.TTS_MEDICAL_PRONUNCIATION] ?: true,
            ttsAcousticGapMs = prefs[Keys.TTS_ACOUSTIC_GAP_MS]
                ?: AppSettingsPolicy.DEFAULT_ACOUSTIC_GAP_MS,
            headsetDisconnectBehavior = HeadsetDisconnectBehavior.fromStorage(
                prefs[Keys.HEADSET_DISCONNECT_BEHAVIOR]
            ),
            studyAudioMode = StudyAudioMode.fromStorage(prefs[Keys.STUDY_AUDIO_MODE]),
            phoneAudioNoticeAcknowledged = prefs[Keys.PHONE_AUDIO_NOTICE_ACK] ?: false,
            handsFreeMode = prefs[Keys.HANDS_FREE_MODE] ?: true,
            autoPlayQuestion = prefs[Keys.AUTO_PLAY_QUESTION] ?: true,
            autoPlayFeedback = prefs[Keys.AUTO_PLAY_FEEDBACK] ?: true,
            autoSubmitTranscript = prefs[Keys.AUTO_SUBMIT_TRANSCRIPT] ?: true,
            confirmRating = prefs[Keys.CONFIRM_RATING] ?: false,
            listenForSpokenRating = prefs[Keys.LISTEN_FOR_SPOKEN_RATING] ?: true,
            showTranscriptOnScreen = prefs[Keys.SHOW_TRANSCRIPT] ?: true,
            useFakeAgent = prefs[Keys.USE_FAKE_AGENT] ?: false,
            debugLogging = prefs[Keys.DEBUG_LOGGING] ?: true,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            maxReconnectAttempts = prefs[Keys.MAX_RECONNECT_ATTEMPTS]
                ?: AppSettingsPolicy.DEFAULT_MAX_RECONNECT_ATTEMPTS,
            pingIntervalSeconds = prefs[Keys.PING_INTERVAL_SECONDS]
                ?: AppSettingsPolicy.DEFAULT_PING_INTERVAL_SECONDS
        )
        return AppSettingsPolicy.normalize(settings)
    }

    /**
     * Write every AppSettings field in one caller-owned DataStore transaction.
     * Nullable values remove old keys; they are never left stale by a null model.
     */
    fun writeSettings(prefs: MutablePreferences, value: AppSettings) {
        val settings = AppSettingsPolicy.normalize(value)
        writeOrRemove(prefs, Keys.SELECTED_PROFILE_ID, settings.selectedProfileId)

        prefs[Keys.STT_LANGUAGE] = settings.sttLanguage
        prefs[Keys.STT_LANGUAGE_MODE] = settings.sttLanguageMode
        prefs[Keys.STT_ENGLISH_LOCALE] = settings.sttEnglishLocale
        prefs[Keys.STT_ARABIC_LOCALE] = settings.sttArabicLocale
        prefs[Keys.STT_AUTO_FALLBACK_LOCALE] = settings.sttAutoFallbackLocale
        prefs[Keys.STT_RECOGNITION_MODE] = settings.sttRecognitionMode
        prefs[Keys.STT_PREFER_ON_DEVICE] = settings.sttPreferOnDevice
        prefs[Keys.STT_SHOW_PARTIAL] = settings.sttShowPartialTranscript
        prefs[Keys.STT_MEDICAL_BIASING] = settings.sttMedicalBiasing
        prefs[Keys.STT_ANSWER_LENGTH] = settings.sttAnswerLength
        prefs[Keys.STT_DEBUG_TRANSCRIPT_LOGGING] = settings.sttDebugTranscriptLogging
        prefs[Keys.TTS_LANGUAGE] = settings.ttsLanguage

        // `speech_rate` is an old-app compatibility mirror. The runtime never
        // reads it as authority; the shadow retains the deprecated model field
        // so codec round trips do not erase information.
        prefs[Keys.SPEECH_RATE] = settings.questionRate
        prefs[Keys.SPEECH_RATE_MODEL_VALUE] = settings.speechRate
        prefs[Keys.SPEECH_PITCH] = settings.speechPitch

        writeOrRemove(prefs, Keys.TTS_ENGINE_ID, settings.ttsEngineId)
        writeOrRemove(prefs, Keys.ENGLISH_VOICE_ID, settings.englishVoiceId)
        writeOrRemove(prefs, Keys.ARABIC_VOICE_ID, settings.arabicVoiceId)
        prefs[Keys.PREFER_OFFLINE_VOICES] = settings.preferOfflineVoices
        prefs[Keys.QUESTION_RATE] = settings.questionRate
        prefs[Keys.FEEDBACK_RATE] = settings.feedbackRate
        prefs[Keys.EXPLANATION_RATE] = settings.explanationRate
        prefs[Keys.TTS_AUTO_LANGUAGE] = settings.ttsAutoLanguageDetection
        prefs[Keys.TTS_MEDICAL_PRONUNCIATION] = settings.ttsMedicalPronunciation
        prefs[Keys.TTS_ACOUSTIC_GAP_MS] = settings.ttsAcousticGapMs
        prefs[Keys.HEADSET_DISCONNECT_BEHAVIOR] =
            HeadsetDisconnectBehavior.toStorage(settings.headsetDisconnectBehavior)
        prefs[Keys.STUDY_AUDIO_MODE] = StudyAudioMode.toStorage(settings.studyAudioMode)
        prefs[Keys.PHONE_AUDIO_NOTICE_ACK] = settings.phoneAudioNoticeAcknowledged
        prefs[Keys.HANDS_FREE_MODE] = settings.handsFreeMode
        prefs[Keys.AUTO_PLAY_QUESTION] = settings.autoPlayQuestion
        prefs[Keys.AUTO_PLAY_FEEDBACK] = settings.autoPlayFeedback
        prefs[Keys.AUTO_SUBMIT_TRANSCRIPT] = settings.autoSubmitTranscript
        prefs[Keys.CONFIRM_RATING] = settings.confirmRating
        prefs[Keys.LISTEN_FOR_SPOKEN_RATING] = settings.listenForSpokenRating
        prefs[Keys.SHOW_TRANSCRIPT] = settings.showTranscriptOnScreen
        prefs[Keys.USE_FAKE_AGENT] = settings.useFakeAgent
        prefs[Keys.DEBUG_LOGGING] = settings.debugLogging
        prefs[Keys.AUTO_RECONNECT] = settings.autoReconnect
        prefs[Keys.MAX_RECONNECT_ATTEMPTS] = settings.maxReconnectAttempts
        prefs[Keys.PING_INTERVAL_SECONDS] = settings.pingIntervalSeconds
        prefs[Keys.SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
    }

    /**
     * Remove only device/app settings. Profiles, secure tokens, management
     * caches, unknown future keys and their authority domains are intentionally
     * outside this reset scope.
     */
    fun clearSettings(prefs: MutablePreferences) {
        prefs.remove(Keys.SCHEMA_VERSION)
        prefs.remove(Keys.SELECTED_PROFILE_ID)
        prefs.remove(Keys.STT_LANGUAGE)
        prefs.remove(Keys.TTS_LANGUAGE)
        prefs.remove(Keys.SPEECH_RATE)
        prefs.remove(Keys.SPEECH_RATE_MODEL_VALUE)
        prefs.remove(Keys.SPEECH_PITCH)
        prefs.remove(Keys.STT_LANGUAGE_MODE)
        prefs.remove(Keys.STT_ENGLISH_LOCALE)
        prefs.remove(Keys.STT_ARABIC_LOCALE)
        prefs.remove(Keys.STT_AUTO_FALLBACK_LOCALE)
        prefs.remove(Keys.STT_RECOGNITION_MODE)
        prefs.remove(Keys.STT_PREFER_ON_DEVICE)
        prefs.remove(Keys.STT_SHOW_PARTIAL)
        prefs.remove(Keys.STT_MEDICAL_BIASING)
        prefs.remove(Keys.STT_ANSWER_LENGTH)
        prefs.remove(Keys.STT_DEBUG_TRANSCRIPT_LOGGING)
        prefs.remove(Keys.LISTEN_FOR_SPOKEN_RATING)
        prefs.remove(Keys.TTS_ENGINE_ID)
        prefs.remove(Keys.ENGLISH_VOICE_ID)
        prefs.remove(Keys.ARABIC_VOICE_ID)
        prefs.remove(Keys.PREFER_OFFLINE_VOICES)
        prefs.remove(Keys.QUESTION_RATE)
        prefs.remove(Keys.FEEDBACK_RATE)
        prefs.remove(Keys.EXPLANATION_RATE)
        prefs.remove(Keys.TTS_AUTO_LANGUAGE)
        prefs.remove(Keys.TTS_MEDICAL_PRONUNCIATION)
        prefs.remove(Keys.TTS_ACOUSTIC_GAP_MS)
        prefs.remove(Keys.HEADSET_DISCONNECT_BEHAVIOR)
        prefs.remove(Keys.STUDY_AUDIO_MODE)
        prefs.remove(Keys.PHONE_AUDIO_NOTICE_ACK)
        prefs.remove(Keys.HANDS_FREE_MODE)
        prefs.remove(Keys.AUTO_PLAY_QUESTION)
        prefs.remove(Keys.AUTO_PLAY_FEEDBACK)
        prefs.remove(Keys.AUTO_SUBMIT_TRANSCRIPT)
        prefs.remove(Keys.CONFIRM_RATING)
        prefs.remove(Keys.SHOW_TRANSCRIPT)
        prefs.remove(Keys.USE_FAKE_AGENT)
        prefs.remove(Keys.DEBUG_LOGGING)
        prefs.remove(Keys.AUTO_RECONNECT)
        prefs.remove(Keys.MAX_RECONNECT_ATTEMPTS)
        prefs.remove(Keys.PING_INTERVAL_SECONDS)
        prefs[Keys.SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
    }

    /**
     * Sequential, in-place upgrade of known settings keys. Unknown keys are not
     * touched, and a newer schema is never downgraded or cleared.
     */
    fun migrateInPlace(prefs: MutablePreferences) {
        var version = prefs[Keys.SCHEMA_VERSION] ?: LEGACY_SCHEMA_VERSION
        if (version > CURRENT_SCHEMA_VERSION) return

        if (version < 2) {
            migrateV1ToV2(prefs)
            version = 2
            prefs[Keys.SCHEMA_VERSION] = version
        }
        if (version < 3) {
            migrateV2ToV3(prefs)
            version = 3
            prefs[Keys.SCHEMA_VERSION] = version
        }
        if (version < 4) {
            migrateV3ToV4(prefs)
            version = 4
            prefs[Keys.SCHEMA_VERSION] = version
        }
        if (version < 5) {
            migrateV4ToV5(prefs)
            version = 5
            prefs[Keys.SCHEMA_VERSION] = version
        }
    }

    private fun migrateV1ToV2(prefs: MutablePreferences) {
        val legacy = prefs[Keys.SPEECH_RATE] ?: return
        if (prefs[Keys.QUESTION_RATE] == null) prefs[Keys.QUESTION_RATE] = legacy
        if (prefs[Keys.FEEDBACK_RATE] == null) prefs[Keys.FEEDBACK_RATE] = legacy
        if (prefs[Keys.EXPLANATION_RATE] == null) prefs[Keys.EXPLANATION_RATE] = legacy
    }

    private fun migrateV2ToV3(prefs: MutablePreferences) {
        if (prefs[Keys.STT_LANGUAGE_MODE] != null) return
        val legacy = prefs[Keys.STT_LANGUAGE]
        val normalizedLegacy = legacy?.let {
            AppSettingsPolicy.normalizeLocale(it, AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE)
        }
        val isArabic = normalizedLegacy?.startsWith("ar", ignoreCase = true) == true
        prefs[Keys.STT_LANGUAGE_MODE] = when {
            isArabic -> "ARABIC"
            normalizedLegacy != null -> "ENGLISH"
            else -> AppSettingsPolicy.DEFAULT_STT_LANGUAGE_MODE
        }
        prefs[Keys.STT_ENGLISH_LOCALE] = if (!isArabic && normalizedLegacy != null) {
            normalizedLegacy
        } else {
            AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE
        }
        prefs[Keys.STT_ARABIC_LOCALE] = if (isArabic) {
            normalizedLegacy!!
        } else {
            AppSettingsPolicy.DEFAULT_ARABIC_LOCALE
        }
        prefs[Keys.STT_AUTO_FALLBACK_LOCALE] =
            normalizedLegacy ?: AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE
    }

    private fun migrateV3ToV4(prefs: MutablePreferences) {
        if (prefs[Keys.STUDY_AUDIO_MODE] == null) {
            prefs[Keys.STUDY_AUDIO_MODE] = StudyAudioMode.toStorage(StudyAudioMode.AUTO)
        }
    }

    private fun migrateV4ToV5(prefs: MutablePreferences) {
        if (prefs[Keys.LISTEN_FOR_SPOKEN_RATING] == null) {
            prefs[Keys.LISTEN_FOR_SPOKEN_RATING] = true
        }
    }

    private fun <T> writeOrRemove(
        prefs: MutablePreferences,
        key: Preferences.Key<T>,
        value: T?
    ) {
        if (value == null) prefs.remove(key) else prefs[key] = value
    }
}
