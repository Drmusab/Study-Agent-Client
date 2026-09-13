package com.studyagent.client.audio

import com.studyagent.client.core.audio.StudyAudioDisconnectPolicy
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.audio.toStudyAudioPreferences
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings migration (§77/§78).
 *
 * An existing install has no `studyAudioMode` key and does have a `headsetDisconnectBehavior`
 * key. Upgrading must therefore:
 *  1. land on Automatic (never on Headphones Required),
 *  2. keep the disconnect choice the user already made, with the same meaning,
 *  3. never throw on a settings file it does not recognise.
 */
class StudyAudioPreferencesMappingTest {

    @Test
    fun `an existing install without the new key becomes automatic`() {
        val preferences = AppSettings().toStudyAudioPreferences()

        assertEquals(StudyAudioMode.AUTO, preferences.mode)
    }

    @Test
    fun `the existing pause choice keeps its meaning`() {
        val settings = AppSettings(headsetDisconnectBehavior = HeadsetDisconnectBehavior.PAUSE_SPEECH)

        assertEquals(StudyAudioDisconnectPolicy.PAUSE_VOICE, settings.toStudyAudioPreferences().disconnectPolicy)
    }

    @Test
    fun `the existing continue-on-phone choice keeps its meaning`() {
        val settings = AppSettings(headsetDisconnectBehavior = HeadsetDisconnectBehavior.CONTINUE_ON_PHONE)

        assertEquals(StudyAudioDisconnectPolicy.CONTINUE_ON_PHONE, settings.toStudyAudioPreferences().disconnectPolicy)
    }

    @Test
    fun `an explicit audio mode survives the mapping`() {
        assertEquals(
            StudyAudioMode.PHONE,
            AppSettings(studyAudioMode = StudyAudioMode.PHONE).toStudyAudioPreferences().mode
        )
        assertEquals(
            StudyAudioMode.HEADSET_REQUIRED,
            AppSettings(studyAudioMode = StudyAudioMode.HEADSET_REQUIRED).toStudyAudioPreferences().mode
        )
    }

    @Test
    fun `headphones-required is never reachable by a default or a migration`() {
        val migrated = AppSettings().toStudyAudioPreferences()
        val defaultMode = StudyAudioMode.DEFAULT

        assertEquals(StudyAudioMode.AUTO, migrated.mode)
        assertEquals(StudyAudioMode.AUTO, defaultMode)
        assertEquals(StudyAudioMode.AUTO, StudyAudioMode.fromStorage("some-future-value"))
        assertEquals(StudyAudioMode.AUTO, StudyAudioMode.fromStorage(null))
    }

    @Test
    fun `every persisted mode name round-trips`() {
        StudyAudioMode.entries.forEach { mode ->
            assertEquals(mode, StudyAudioMode.fromStorage(mode.name))
        }
    }
}
