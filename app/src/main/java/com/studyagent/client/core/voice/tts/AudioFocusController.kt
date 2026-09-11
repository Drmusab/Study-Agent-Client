package com.studyagent.client.core.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Audio-focus events relevant to spoken study playback. */
sealed interface SpeechFocusEvent {
    data object Gained : SpeechFocusEvent
    data object LossTransient : SpeechFocusEvent
    data object LossTransientCanDuck : SpeechFocusEvent
    data object LossPermanent : SpeechFocusEvent
}

/**
 * Framework-free gate used by the orchestrator (unit-testable seam).
 */
interface SpeechFocusController {
    val events: SharedFlow<SpeechFocusEvent>

    /** @return true when focus was granted. */
    fun requestFocus(): Boolean
    fun abandonFocus()
    val hasFocus: Boolean
}

/**
 * No-op focus controller (tests, and graceful no-op when AudioManager is missing).
 */
class NoOpSpeechFocusController : SpeechFocusController {
    override val events: SharedFlow<SpeechFocusEvent> = MutableSharedFlow<SpeechFocusEvent>(0)
    override val hasFocus: Boolean = true
    override fun requestFocus(): Boolean = true
    override fun abandonFocus() {}
}

/**
 * Proper spoken-audio focus for study TTS.
 *
 * Policy decisions (documented in docs/TTS_ARCHITECTURE.md §Audio focus):
 *  - Uses AUDIOFOCUS_GAIN_TRANSIENT with CONTENT_TYPE_SPEECH / USAGE_ASSISTANT:
 *    the app speaks a question, then goes silent while listening — we do not
 *    hold the audio channel the whole session.
 *  - LOSS_TRANSIENT_CAN_DUCK is surfaced as a *pause* signal, not a duck:
 *    playing a quieter card question under navigation guidance destroys
 *    intelligibility; pausing the current chunk and resuming is safer.
 *  - The phone-call case (permanent/transient loss) pauses speech entirely —
 *    never speak over a call.
 */
class AudioFocusController(
    context: Context,
    private val loggerTag: String = "AudioFocusCtrl"
) : SpeechFocusController {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _events = MutableSharedFlow<SpeechFocusEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<SpeechFocusEvent> = _events.asSharedFlow()

    @Volatile
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var focusHeld = false
    override val hasFocus: Boolean get() = focusHeld

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                _events.tryEmit(SpeechFocusEvent.Gained)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                _events.tryEmit(SpeechFocusEvent.LossPermanent)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusHeld = false
                _events.tryEmit(SpeechFocusEvent.LossTransient)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusHeld = false
                _events.tryEmit(SpeechFocusEvent.LossTransientCanDuck)
            }
        }
    }

    override fun requestFocus(): Boolean {
        val am = audioManager ?: return true // no AudioManager → pretend granted (emulator edge)
        abandonInternal()
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .build()
            focusRequest = request
            val result = am.requestAudioFocus(request)
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) {
                AppLogger.w(loggerTag, "Audio focus request denied ($result)")
            }
            focusHeld
        } catch (e: Exception) {
            AppLogger.w(loggerTag, "Audio focus request failed: ${e.message}")
            false
        }
    }

    override fun abandonFocus() {
        abandonInternal()
    }

    private fun abandonInternal() {
        val am = audioManager ?: return
        val request = focusRequest ?: return
        focusRequest = null
        focusHeld = false
        try {
            am.abandonAudioFocusRequest(request)
        } catch (e: Exception) {
            AppLogger.w(loggerTag, "Error abandoning audio focus: ${e.message}")
        }
    }
}
