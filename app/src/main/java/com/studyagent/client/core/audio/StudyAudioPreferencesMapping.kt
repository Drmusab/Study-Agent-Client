package com.studyagent.client.core.audio

import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.voice.tts.HeadsetDisconnectBehavior

/**
 * Persisted settings → study-audio policy view (§77).
 *
 * The disconnect behaviour key is the pre-existing one (`headsetDisconnectBehavior`), kept so
 * an existing user's choice survives the upgrade rather than silently resetting (§78). The new
 * enum spells the two options in terms of the whole voice loop instead of only TTS.
 */
fun AppSettings.toStudyAudioPreferences(): StudyAudioPreferences = StudyAudioPreferences(
    mode = studyAudioMode,
    disconnectPolicy = when (headsetDisconnectBehavior) {
        HeadsetDisconnectBehavior.PAUSE_SPEECH -> StudyAudioDisconnectPolicy.PAUSE_VOICE
        HeadsetDisconnectBehavior.CONTINUE_ON_PHONE -> StudyAudioDisconnectPolicy.CONTINUE_ON_PHONE
    }
)
