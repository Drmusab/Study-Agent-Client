package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.EngineStatus
import com.studyagent.client.core.voice.tts.EngineUtterance
import com.studyagent.client.core.voice.tts.SpeechError
import com.studyagent.client.core.voice.tts.SpeechErrorCode
import com.studyagent.client.core.voice.tts.SpeechResult
import com.studyagent.client.core.voice.tts.TtsEngineAdapter
import com.studyagent.client.core.voice.tts.TtsEngineInfo
import com.studyagent.client.core.voice.tts.TtsVoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deterministic JVM fake engine for orchestrator tests (§65).
 *
 * Simulates: ready / init-wait / complete / failure / cancellation / manual-mode
 * pending utterances, and records everything for assertions:
 *  - every [EngineUtterance] the pipeline emitted (text, locale, voice, rate, pitch)
 *  - stop/release counts (cancel-path audit)
 *  - pending callback count — a test assertion of zero proves no callback leaks.
 */
class FakeTtsEngineAdapter(
    private val maxInputLength: Int = 500,
    var voices: List<TtsVoiceInfo> = emptyList(),
    var engines: List<TtsEngineInfo> = emptyList()
) : TtsEngineAdapter {

    enum class Mode {
        /** speak() completes immediately. */
        AUTO_COMPLETE,

        /** speak() fails immediately with [failWith]. */
        AUTO_FAIL,

        /** speak() parks until [completeNext] / [stop] / [release]. */
        MANUAL
    }

    var mode: Mode = Mode.AUTO_COMPLETE
    var failWith: SpeechError = SpeechError(SpeechErrorCode.PLAYBACK_ERROR, "fake playback error")

    private val _status = MutableStateFlow(EngineStatus.UNINITIALIZED)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    override var utteranceStartedListener: ((String) -> Unit)? = null

    // ---- observability ----
    val spoken = mutableListOf<EngineUtterance>()
    var stopCount = 0
        private set
    var releaseCount = 0
        private set
    val setEngineCalls = mutableListOf<String?>()

    private val pending = LinkedHashMap<String, CompletableDeferred<SpeechResult>>()

    /** Nonzero pending count at test end = callback leak. */
    val pendingCount: Int get() = pending.size

    fun markReady() {
        _status.value = EngineStatus.READY
    }

    fun markInitializing() {
        _status.value = EngineStatus.INITIALIZING
    }

    fun markFailed() {
        _status.value = EngineStatus.FAILED
    }

    override suspend fun speak(utterance: EngineUtterance): SpeechResult {
        // "Queue until ready" simulation mirroring the real adapter's bounded init wait.
        if (_status.value == EngineStatus.INITIALIZING) {
            withTimeoutOrNull(8_000L) {
                _status.first { it == EngineStatus.READY || it == EngineStatus.FAILED }
            }
        }
        if (_status.value != EngineStatus.READY) {
            return SpeechResult.Failed(
                SpeechError(SpeechErrorCode.ENGINE_UNAVAILABLE, "fake engine not ready")
            )
        }
        spoken += utterance
        utteranceStartedListener?.invoke(utterance.utteranceId)
        return when (mode) {
            Mode.AUTO_COMPLETE -> SpeechResult.Completed
            Mode.AUTO_FAIL -> SpeechResult.Failed(failWith)
            Mode.MANUAL -> {
                val deferred = CompletableDeferred<SpeechResult>()
                pending[utterance.utteranceId] = deferred
                try {
                    deferred.await()
                } finally {
                    pending.remove(utterance.utteranceId)
                }
            }
        }
    }

    override fun stop() {
        stopCount++
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.complete(SpeechResult.Cancelled) }
    }

    override fun maxSpeechInputLength(): Int = maxInputLength

    override suspend fun queryVoices(): List<TtsVoiceInfo> = voices

    override suspend fun queryEngines(): List<TtsEngineInfo> = engines

    override fun setEngine(enginePackage: String?) {
        setEngineCalls += enginePackage
    }

    override fun release() {
        releaseCount++
        stop()
        _status.value = EngineStatus.RELEASED
    }

    // ---- manual-mode drivers ----

    fun completeNext(result: SpeechResult = SpeechResult.Completed) {
        val first = pending.entries.firstOrNull() ?: return
        pending.remove(first.key)
        first.value.complete(result)
    }

    fun failNext(error: SpeechError = failWith) {
        completeNext(SpeechResult.Failed(error))
    }
}
