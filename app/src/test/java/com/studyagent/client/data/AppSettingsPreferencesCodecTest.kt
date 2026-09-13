package com.studyagent.client.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.AppSettingsPolicy
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import com.studyagent.client.data.preferences.AppSettingsPreferencesCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parity/migration tests for the complete AppSettings contract. */
class AppSettingsPreferencesCodecTest {

    @Test
    fun `empty preferences equal fresh install defaults`() {
        assertEquals(AppSettings(), AppSettingsPreferencesCodec.readSettings(emptyPreferences()))
    }

    @Test
    fun `every non-default field round trips through the codec`() {
        val original = AppSettings(
            selectedProfileId = "profile-42",
            sttLanguage = "en-GB",
            sttLanguageMode = "ARABIC",
            sttEnglishLocale = "en-AU",
            sttArabicLocale = "ar-EG",
            sttAutoFallbackLocale = "ar-EG",
            sttRecognitionMode = "PREFER_ON_DEVICE",
            sttPreferOnDevice = false,
            sttShowPartialTranscript = false,
            sttMedicalBiasing = false,
            sttAnswerLength = "LONG",
            sttDebugTranscriptLogging = true,
            ttsLanguage = "ar-IQ",
            speechRate = 0.82f,
            speechPitch = 1.25f,
            ttsEngineId = "engine.package",
            englishVoiceId = "english-voice",
            arabicVoiceId = "arabic-voice",
            preferOfflineVoices = false,
            questionRate = 0.71f,
            feedbackRate = 1.33f,
            explanationRate = 1.62f,
            ttsAutoLanguageDetection = false,
            ttsMedicalPronunciation = false,
            ttsAcousticGapMs = 800,
            headsetDisconnectBehavior = HeadsetDisconnectBehavior.CONTINUE_ON_PHONE,
            studyAudioMode = StudyAudioMode.PHONE,
            phoneAudioNoticeAcknowledged = true,
            handsFreeMode = false,
            autoPlayQuestion = false,
            autoPlayFeedback = false,
            autoSubmitTranscript = false,
            confirmRating = true,
            listenForSpokenRating = false,
            showTranscriptOnScreen = false,
            useFakeAgent = true,
            debugLogging = false,
            autoReconnect = false,
            maxReconnectAttempts = 37,
            pingIntervalSeconds = 73L
        )
        val prefs = mutablePreferencesOf()

        AppSettingsPreferencesCodec.writeSettings(prefs, original)

        assertEquals(AppSettingsPolicy.normalize(original), AppSettingsPreferencesCodec.readSettings(prefs))
        assertEquals(AppSettingsPreferencesCodec.CURRENT_SCHEMA_VERSION, prefs[AppSettingsPreferencesCodec.Keys.SCHEMA_VERSION])
        // The legacy key is intentionally a compatibility mirror, not runtime authority.
        assertEquals(original.questionRate, prefs[AppSettingsPreferencesCodec.Keys.SPEECH_RATE])
    }

    @Test
    fun `nullable settings can be set and then cleared`() {
        val prefs = mutablePreferencesOf()
        val withValues = AppSettings(
            selectedProfileId = "profile",
            ttsEngineId = "engine",
            englishVoiceId = "en-voice",
            arabicVoiceId = "ar-voice"
        )
        AppSettingsPreferencesCodec.writeSettings(prefs, withValues)
        assertEquals("profile", prefs[AppSettingsPreferencesCodec.Keys.SELECTED_PROFILE_ID])
        assertEquals("engine", prefs[AppSettingsPreferencesCodec.Keys.TTS_ENGINE_ID])

        AppSettingsPreferencesCodec.writeSettings(
            prefs,
            withValues.copy(
                selectedProfileId = null,
                ttsEngineId = null,
                englishVoiceId = null,
                arabicVoiceId = null
            )
        )

        assertNull(prefs[AppSettingsPreferencesCodec.Keys.SELECTED_PROFILE_ID])
        assertNull(prefs[AppSettingsPreferencesCodec.Keys.TTS_ENGINE_ID])
        assertNull(prefs[AppSettingsPreferencesCodec.Keys.ENGLISH_VOICE_ID])
        assertNull(prefs[AppSettingsPreferencesCodec.Keys.ARABIC_VOICE_ID])
        assertNull(AppSettingsPreferencesCodec.readSettings(prefs).selectedProfileId)
        assertNull(AppSettingsPreferencesCodec.readSettings(prefs).ttsEngineId)
    }

    @Test
    fun `legacy Arabic STT and global TTS rate migrate without changing language`() {
        val prefs = mutablePreferencesOf()
        prefs[AppSettingsPreferencesCodec.Keys.STT_LANGUAGE] = "ar-IQ"
        prefs[AppSettingsPreferencesCodec.Keys.SPEECH_RATE] = 0.85f

        val settings = AppSettingsPreferencesCodec.readSettings(prefs)

        assertEquals("ARABIC", settings.sttLanguageMode)
        assertEquals("ar-IQ", settings.sttArabicLocale)
        assertEquals("ar-IQ", settings.sttAutoFallbackLocale)
        assertEquals(0.85f, settings.questionRate)
        assertEquals(0.85f, settings.feedbackRate)
        assertEquals(0.85f, settings.explanationRate)
    }

    @Test
    fun `legacy English locale retains its useful locale semantics`() {
        val prefs = mutablePreferencesOf()
        prefs[AppSettingsPreferencesCodec.Keys.STT_LANGUAGE] = "en-GB"

        val settings = AppSettingsPreferencesCodec.readSettings(prefs)

        assertEquals("ENGLISH", settings.sttLanguageMode)
        assertEquals("en-GB", settings.sttEnglishLocale)
        assertEquals("en-GB", settings.sttAutoFallbackLocale)
    }

    @Test
    fun `sequential migration is idempotent and preserves unknown keys`() {
        val unknownKey = androidx.datastore.preferences.core.stringPreferencesKey("future_key")
        val prefs = mutablePreferencesOf()
        prefs[unknownKey] = "do-not-delete"
        prefs[AppSettingsPreferencesCodec.Keys.STT_LANGUAGE] = "ar-IQ"
        prefs[AppSettingsPreferencesCodec.Keys.SPEECH_RATE] = 0.85f

        AppSettingsPreferencesCodec.migrateInPlace(prefs)
        val afterFirst = prefs.asMap()
        AppSettingsPreferencesCodec.migrateInPlace(prefs)

        assertEquals(afterFirst, prefs.asMap())
        assertEquals("do-not-delete", prefs[unknownKey])
        assertEquals(AppSettingsPreferencesCodec.CURRENT_SCHEMA_VERSION, prefs[AppSettingsPreferencesCodec.Keys.SCHEMA_VERSION])
        assertEquals("ARABIC", prefs[AppSettingsPreferencesCodec.Keys.STT_LANGUAGE_MODE])
    }

    @Test
    fun `unknown enum and invalid numbers normalize safely`() {
        val prefs = mutablePreferencesOf()
        prefs[AppSettingsPreferencesCodec.Keys.STUDY_AUDIO_MODE] = "FUTURE_SUPER_MODE"
        prefs[AppSettingsPreferencesCodec.Keys.HEADSET_DISCONNECT_BEHAVIOR] = "FUTURE_POLICY"
        prefs[AppSettingsPreferencesCodec.Keys.STT_RECOGNITION_MODE] = "future_backend"
        prefs[AppSettingsPreferencesCodec.Keys.STT_ANSWER_LENGTH] = "future_length"
        prefs[AppSettingsPreferencesCodec.Keys.TTS_ACOUSTIC_GAP_MS] = -99
        prefs[AppSettingsPreferencesCodec.Keys.QUESTION_RATE] = Float.NaN
        prefs[AppSettingsPreferencesCodec.Keys.SPEECH_PITCH] = Float.POSITIVE_INFINITY
        prefs[AppSettingsPreferencesCodec.Keys.MAX_RECONNECT_ATTEMPTS] = -4
        prefs[AppSettingsPreferencesCodec.Keys.PING_INTERVAL_SECONDS] = -10L

        val settings = AppSettingsPreferencesCodec.readSettings(prefs)

        assertEquals(StudyAudioMode.AUTO, settings.studyAudioMode)
        assertEquals(HeadsetDisconnectBehavior.PAUSE_SPEECH, settings.headsetDisconnectBehavior)
        assertEquals("AUTO", settings.sttRecognitionMode)
        assertEquals("NORMAL", settings.sttAnswerLength)
        assertEquals(AppSettingsPolicy.MIN_ACOUSTIC_GAP_MS, settings.ttsAcousticGapMs)
        assertEquals(AppSettingsPolicy.DEFAULT_TTS_RATE, settings.questionRate)
        assertEquals(AppSettingsPolicy.DEFAULT_SPEECH_PITCH, settings.speechPitch)
        assertEquals(AppSettingsPolicy.MIN_RECONNECT_ATTEMPTS, settings.maxReconnectAttempts)
        assertEquals(AppSettingsPolicy.MIN_PING_INTERVAL_SECONDS, settings.pingIntervalSeconds)
    }

    @Test
    fun `independent per-purpose rates do not overwrite one another`() {
        val prefs = mutablePreferencesOf()
        val original = AppSettings(questionRate = 0.7f, feedbackRate = 1.4f, explanationRate = 1.7f)

        AppSettingsPreferencesCodec.writeSettings(prefs, original)

        val restored = AppSettingsPreferencesCodec.readSettings(prefs)
        assertEquals(0.7f, restored.questionRate)
        assertEquals(1.4f, restored.feedbackRate)
        assertEquals(1.7f, restored.explanationRate)
        assertFalse(restored.questionRate == restored.feedbackRate)
        assertTrue(restored.speechRate == original.speechRate)
    }
}
