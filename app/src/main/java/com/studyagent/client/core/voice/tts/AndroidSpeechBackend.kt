package com.studyagent.client.core.voice.tts

import java.util.Locale

/**
 * The existing, production-hardened local TextToSpeech path behind the
 * generalized [SpeechBackend] seam (master prompt §8: "move the current local
 * behavior behind the generalized interface with zero functional regression").
 *
 * This is a thin adapter: it converts [BackendUtterance] → [EngineUtterance]
 * 1:1 and delegates everything to the [TtsEngineAdapter] (whose callback
 * lifetime, watchdog, re-init and threading contracts are unchanged and
 * covered by the existing test suite). The local voice catalog is mapped to
 * the generalized [SpeechVoice] model for the unified Settings UI.
 */
class AndroidSpeechBackend(
    private val engine: TtsEngineAdapter
) : SpeechBackend {

    override val id: String = TtsProvider.ANDROID.storageId
    override val kind: SpeechBackendKind = SpeechBackendKind.ANDROID_LOCAL
    override val provider: TtsProvider = TtsProvider.ANDROID

    override val status: kotlinx.coroutines.flow.StateFlow<EngineStatus> = engine.status

    override val capabilities: SpeechBackendCapabilities = SpeechBackendCapabilities(
        streaming = false,
        multilingual = true, // per-segment voice switching (en + ar)
        supportsRate = true,
        supportsPitch = true,
        supportsStyleInstructions = false,
        supportsVoiceSettings = false,
        supportsCustomVoices = false,
        maxInputChars = null // resolved live via maxSpeechInputLength()
    )

    /**
     * Forwarded to the wrapped engine when non-null (the orchestrator sets it
     * per request on the backend it is about to speak on). When null, the
     * engine's own listener (if any) is left untouched.
     */
    override var utteranceStartedListener: ((String) -> Unit)? = null

    override suspend fun speak(utterance: BackendUtterance): SpeechResult {
        val engineUtterance = toEngineUtterance(utterance)
        val listener = utteranceStartedListener
        if (listener == null) return engine.speak(engineUtterance)
        // Swap only around the call: the engine exposes a single listener var,
        // and speech is serialized by the orchestrator (one speak at a time).
        val previous = engine.utteranceStartedListener
        engine.utteranceStartedListener = listener
        try {
            return engine.speak(engineUtterance)
        } finally {
            engine.utteranceStartedListener = previous
        }
    }

    override suspend fun queryVoices(languageCode: String?): List<SpeechVoice> =
        engine.queryVoices().map { it.toSpeechVoice() }
            .let { if (languageCode.isNullOrBlank()) it else it.filter { v -> v.languageCodes.contains(languageCode.lowercase()) } }

    override fun maxSpeechInputLength(): Int = engine.maxSpeechInputLength()

    override fun stop() {
        engine.stop()
    }

    override fun release() {
        engine.release()
    }

    // ------------------------------------------------------------------ mapping

    private fun toEngineUtterance(u: BackendUtterance): EngineUtterance = EngineUtterance(
        utteranceId = u.utteranceId,
        text = u.text,
        locale = u.locale ?: Locale.US,
        voiceName = u.voiceName,
        rate = u.rate,
        pitch = u.pitch
    )

    private fun TtsVoiceInfo.toSpeechVoice(): SpeechVoice = SpeechVoice(
        provider = TtsProvider.ANDROID,
        id = id,
        displayName = displayName,
        languages = listOf(language),
        localeTag = localeTag,
        networkRequired = networkRequired,
        custom = false,
        previewAvailable = true,
        category = if (networkRequired) "network" else "on-device",
        description = null
    )
}

