package com.studyagent.client.core.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.AppSettingsPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Production-hardened wrapper around `android.speech.tts.TextToSpeech`.
 *
 * Reliability design (maps to audit findings T1–T11 in docs/TTS_AUDIT.md):
 *
 *  - **Init queueing (T1):** [speak] during INITIALIZING waits up to [INIT_TIMEOUT_MS] for
 *    onInit instead of dropping the utterance. Failure → typed [SpeechErrorCode] result.
 *  - **Callback lifetime (T2/T3):** every terminal path (done / error / speak-api failure /
 *    init failure / timeout / stop / release / cancellation) removes the utterance's deferred
 *    from [utteranceCallbacks] *and* completes it exactly once. Orphans are impossible by
 *    construction: completion always goes through [completeUtterance].
 *  - **Stop semantics (T3):** [stop] completes all pending callers [SpeechResult.Cancelled]
 *    before touching the engine, so a late onError from the engine is a no-op.
 *  - **Watchdog (T8):** each utterance has a length-aware timeout ([estimateTimeoutMs]);
 *    engines that never call back are stopped and reported TIMEOUT. Two consecutive
 *    timeouts trigger a bounded engine re-initialization.
 *  - **Recovery (T9/T11):** FAILED state allows a bounded number of re-init attempts;
 *    RELEASED is terminal and speaks fail fast with ENGINE_NOT_INITIALIZED.
 *  - **Threading (T7):** every engine interaction (create, config, speak, stop, shutdown,
 *    voice queries) is marshalled to the main thread; listener callbacks complete
 *    deferreds which is thread-safe.
 *  - **Serialization:** one utterance at a time with QUEUE_FLUSH — the orchestrator sequences
 *    chunks, which eliminates the queued-isSpeaking race (T5) by construction.
 *  - **Privacy (T6):** text is never logged — only ids, char counts and codes.
 */
class AndroidTtsEngineAdapter(
    context: Context,
    private val tag: String = "AndroidTtsAdapter"
) : TtsEngineAdapter, TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(EngineStatus.UNINITIALIZED)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    /** All mutable engine bookkeeping is confined to [lock]; callbacks use deferred completion. */
    private val lock = Any()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var requestedEnginePackage: String? = null

    /** Runtime choice may temporarily be system default while the preferred package is absent. */
    @Volatile
    private var effectiveEnginePackage: String? = null

    /** Completed by onInit (true=SUCCESS). Re-created on every (re-)initialization. */
    private var initDeferred: CompletableDeferred<Boolean>? = null

    private var initError: SpeechError? = null
    private var released = false

    @Volatile
    private var recoveryAttempts = 0

    @Volatile
    private var consecutiveTimeouts = 0

    /** utteranceId → pending completion. The ONLY map of callbacks in the class. */
    private val utteranceCallbacks = HashMap<String, CompletableDeferred<SpeechResult>>()

    // Last applied engine config — avoids re-setting voice/rate per chunk (perf + prevents pops).
    private var lastAppliedVoiceName: String? = null
    private var lastAppliedLocale: Locale? = null
    private var lastAppliedRate: Float = -1f
    private var lastAppliedPitch: Float = -1f

    /** Optional hook used by the orchestrator for request→start latency metrics. */
    @Volatile
    override var utteranceStartedListener: ((String) -> Unit)? = null

    init {
        initializeEngine()
    }

    // ------------------------------------------------------------------ init/lifecycle

    private fun initializeEngine() {
        synchronized(lock) {
            if (released) return
            if (_status.value == EngineStatus.INITIALIZING) return
            initDeferred = CompletableDeferred()
            initError = null
            _status.value = EngineStatus.INITIALIZING
        }
        mainHandler.post {
            try {
                val pkg = effectiveEnginePackage
                val engine = if (pkg.isNullOrBlank()) {
                    TextToSpeech(appContext, this)
                } else {
                    TextToSpeech(appContext, this, pkg)
                }
                synchronized(lock) { tts = engine }
            } catch (e: Exception) {
                AppLogger.e(tag, "Engine construction failed (engine=$requestedEnginePackage): ${e.message}", e)
                failInitialization(SpeechError.engineInitFailed(-1))
            }
        }
    }

    override fun onInit(status: Int) {
        // TextToSpeech invokes onInit on the main thread.
        if (status == TextToSpeech.SUCCESS) {
            val engine = synchronized(lock) { tts }
            if (engine == null) {
                failInitialization(SpeechError.engineInitFailed(status))
                return
            }
            try {
                applyAudioAttributes(engine)
                engine.setOnUtteranceProgressListener(createProgressListener())
            } catch (e: Exception) {
                AppLogger.w(tag, "Listener/attribute setup failed: ${e.message}")
            }
            synchronized(lock) {
                recoveryAttempts = 0
                consecutiveTimeouts = 0
                lastAppliedVoiceName = null
                lastAppliedLocale = null
                lastAppliedRate = -1f
                lastAppliedPitch = -1f
                _status.value = EngineStatus.READY
                initDeferred?.complete(true)
            }
            AppLogger.i(tag, "TTS engine ready (engine=${engine.defaultEngine ?: "system"})")
        } else {
            AppLogger.e(tag, "TTS engine init failed with status $status")
            failInitialization(SpeechError.engineInitFailed(status))
        }
    }

    private fun failInitialization(error: SpeechError) {
        var fallbackToSystem = false
        synchronized(lock) {
            initError = error
            // Keep the preferred package in memory as user intent, but do not let
            // a removed/broken engine prevent speech. The next initialization uses
            // the platform default without changing the persisted preference.
            if (!released && requestedEnginePackage != null &&
                effectiveEnginePackage == requestedEnginePackage
            ) {
                effectiveEnginePackage = null
                _status.value = EngineStatus.UNINITIALIZED
                fallbackToSystem = true
            } else {
                _status.value = EngineStatus.FAILED
            }
            initDeferred?.complete(false)
        }
        if (fallbackToSystem) {
            AppLogger.w(tag, "Preferred TTS engine unavailable; using system default temporarily")
            initializeEngine()
        }
    }

    private fun applyAudioAttributes(engine: TextToSpeech) {
        val speech = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (engine.setAudioAttributes(speech) != TextToSpeech.SUCCESS) {
            // Some OEM engines reject USAGE_ASSISTANT; media fallback keeps BT routing sane.
            val media = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            engine.setAudioAttributes(media)
        }
    }

    // ------------------------------------------------------------------ speak

    override suspend fun speak(utterance: EngineUtterance): SpeechResult {
        if (utterance.text.isBlank()) return SpeechResult.Completed

        val engine = awaitEngineReady()
        if (engine == null) {
            val error = synchronized(lock) { initError }
            return SpeechResult.Failed(
                error
                    ?: if (_status.value == EngineStatus.RELEASED) {
                        SpeechError.notInitialized()
                    } else {
                        SpeechError.initTimeout()
                    }
            )
        }

        val deferred = CompletableDeferred<SpeechResult>()
        synchronized(lock) {
            if (_status.value != EngineStatus.READY) {
                return SpeechResult.Failed(
                    SpeechError(
                        SpeechErrorCode.ENGINE_UNAVAILABLE,
                        "Engine became unavailable before speak could start."
                    )
                )
            }
            utteranceCallbacks[utterance.utteranceId] = deferred
        }

        // Launch on the main thread. Any failure removes + completes the deferred immediately.
        mainHandler.post {
            val current = synchronized(lock) { tts }
            if (current == null) {
                completeUtterance(
                    utterance.utteranceId,
                    SpeechResult.Failed(SpeechError.notInitialized())
                )
            } else {
                try {
                    applyUtteranceConfig(current, utterance)
                    val params = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utterance.utteranceId)
                    }
                    // QUEUE_FLUSH: the orchestrator serializes logical requests; an engine
                    // queue here would only re-introduce ordering races.
                    val result = current.speak(
                        utterance.text,
                        TextToSpeech.QUEUE_FLUSH,
                        params,
                        utterance.utteranceId
                    )
                    if (result != TextToSpeech.SUCCESS) {
                        AppLogger.e(tag, "speak() rejected by engine (code=$result)")
                        completeUtterance(
                            utterance.utteranceId,
                            SpeechResult.Failed(
                                SpeechError(
                                    SpeechErrorCode.SPEAK_FAILED,
                                    "Engine rejected the utterance (code $result)."
                                )
                            )
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.e(tag, "speak() threw: ${e.message}", e)
                    completeUtterance(
                        utterance.utteranceId,
                        SpeechResult.Failed(
                            SpeechError(SpeechErrorCode.SPEAK_FAILED, e.message ?: "speak() failed")
                        )
                    )
                }
            }
        }

        return try {
            withTimeout(estimateTimeoutMs(utterance)) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            handleUtteranceTimeout(utterance.utteranceId)
        } catch (e: CancellationException) {
            // Orchestrator cancelled this utterance — stop audio, clean up, propagate.
            cancelUtterance(utterance.utteranceId)
            throw e
        }
    }

    private fun applyUtteranceConfig(engine: TextToSpeech, utterance: EngineUtterance) {
        // Voice / locale only when actually switching — re-setting per chunk is wasted
        // binder traffic and can restart synthesis on some engines.
        val voiceName = utterance.voiceName
        if (voiceName != null) {
            if (voiceName != lastAppliedVoiceName) {
                val voice = try {
                    engine.voices?.firstOrNull { it.name == voiceName }
                } catch (e: Exception) { null }
                if (voice != null) {
                    engine.setVoice(voice)
                } else {
                    // Selected voice vanished (engine update) → locale fallback, engine default voice.
                    AppLogger.w(tag, "Requested voice unavailable; falling back to locale")
                    engine.setLanguage(utterance.locale)
                }
                lastAppliedVoiceName = voiceName
                lastAppliedLocale = utterance.locale
            }
        } else if (utterance.locale != lastAppliedLocale) {
            engine.setLanguage(utterance.locale)
            lastAppliedLocale = utterance.locale
            lastAppliedVoiceName = null
        }
        if (utterance.rate != lastAppliedRate) {
            engine.setSpeechRate(utterance.rate)
            lastAppliedRate = utterance.rate
        }
        if (utterance.pitch != lastAppliedPitch) {
            engine.setPitch(utterance.pitch)
            lastAppliedPitch = utterance.pitch
        }
    }

    private fun createProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null) utteranceStartedListener?.invoke(utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) completeUtterance(utteranceId, SpeechResult.Completed)
                consecutiveTimeouts = 0
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                AppLogger.w(tag, "Engine playback error (code=$errorCode)")
                if (utteranceId != null) {
                    completeUtterance(
                        utteranceId,
                        SpeechResult.Failed(
                            SpeechError(
                                SpeechErrorCode.PLAYBACK_ERROR,
                                "Engine playback error (code $errorCode)."
                            )
                        )
                    )
                }
            }
        }
    }

    /** Single funnel for completing utterances: idempotent remove + complete. */
    private fun completeUtterance(utteranceId: String, result: SpeechResult) {
        val deferred = synchronized(lock) { utteranceCallbacks.remove(utteranceId) }
        deferred?.complete(result)
    }

    private fun handleUtteranceTimeout(utteranceId: String): SpeechResult {
        AppLogger.w(tag, "Utterance timed out; stopping engine playback")
        synchronized(lock) {
            utteranceCallbacks.remove(utteranceId)
            consecutiveTimeouts++
        }
        val engine = synchronized(lock) { tts }
        mainHandler.post {
            try {
                engine?.stop()
            } catch (e: Exception) {
                AppLogger.w(tag, "stop() after timeout failed: ${e.message}")
            }
        }
        if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
            AppLogger.e(tag, "Engine appears stuck; reinitializing (bounded)")
            recoverEngine()
        }
        return SpeechResult.Failed(SpeechError.timeout())
    }

    private fun cancelUtterance(utteranceId: String) {
        val deferred = synchronized(lock) { utteranceCallbacks.remove(utteranceId) }
        deferred?.complete(SpeechResult.Cancelled)
        val engine = synchronized(lock) { tts }
        mainHandler.post {
            try {
                engine?.stop()
            } catch (e: Exception) {
                AppLogger.w(tag, "stop() during cancellation failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ ready-wait & recovery

    /**
     * Wait (bounded, non-blocking) for the engine to reach READY.
     * Implements the "queue until Ready" policy for speaks arriving during init.
     */
    private suspend fun awaitEngineReady(): TextToSpeech? {
        if (synchronized(lock) { released }) return null

        when (_status.value) {
            EngineStatus.READY -> return synchronized(lock) { tts }
            EngineStatus.RELEASED -> return null
            EngineStatus.FAILED -> {
                // Bounded recovery: a crashed engine gets a limited number of re-init chances;
                // when the budget is gone, speaks fail fast instead of looping forever.
                if (!recoverEngine()) return null
            }
            EngineStatus.UNINITIALIZED,
            EngineStatus.INITIALIZING -> Unit // fall through to bounded wait
        }

        val deferred = synchronized(lock) { initDeferred } ?: return null
        val success = withTimeoutOrNull(INIT_TIMEOUT_MS) { deferred.await() }
        if (success == null) {
            AppLogger.w(tag, "Engine init wait timed out")
            return null
        }
        return if (success) synchronized(lock) { tts } else null
    }

    /** Bounded re-initialization after failure. Returns false when the budget is exhausted. */
    private fun recoverEngine(): Boolean {
        synchronized(lock) {
            if (released) return false
            if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
                AppLogger.e(tag, "TTS recovery budget exhausted; speech unavailable")
                return false
            }
            recoveryAttempts++
        }
        val old = synchronized(lock) {
            val e = tts
            tts = null
            e
        }
        mainHandler.post { shutdownQuietly(old) }
        if (_status.value != EngineStatus.INITIALIZING) initializeEngine()
        return true
    }

    private fun shutdownQuietly(engine: TextToSpeech?) {
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (e: Exception) {
            AppLogger.w(tag, "Error shutting down engine: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ stop / release / config

    override fun stop() {
        val pending: List<CompletableDeferred<SpeechResult>>
        synchronized(lock) {
            pending = utteranceCallbacks.values.toList()
            utteranceCallbacks.clear()
        }
        // Callers are informed BEFORE the engine is touched — no dangling callbacks, ever.
        pending.forEach { it.complete(SpeechResult.Cancelled) }

        val engine = synchronized(lock) { tts }
        mainHandler.post {
            try {
                engine?.stop()
            } catch (e: Exception) {
                AppLogger.w(tag, "stop() failed: ${e.message}")
            }
        }
    }

    override fun setEngine(enginePackage: String?) {
        val normalized = enginePackage?.takeIf { it.isNotBlank() }
        if (normalized == requestedEnginePackage && normalized == effectiveEnginePackage) return
        stop()
        val old = synchronized(lock) {
            val e = tts
            tts = null
            requestedEnginePackage = normalized
            effectiveEnginePackage = normalized
            e
        }
        mainHandler.post { shutdownQuietly(old) }
        initializeEngine()
    }

    override fun release() {
        stop()
        val engine = synchronized(lock) {
            released = true
            val e = tts
            tts = null
            _status.value = EngineStatus.RELEASED
            e
        }
        mainHandler.post { shutdownQuietly(engine) }
    }

    // ------------------------------------------------------------------ discovery

    override fun maxSpeechInputLength(): Int = TextToSpeech.getMaxSpeechInputLength()

    override suspend fun queryVoices(): List<TtsVoiceInfo> = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val engine = synchronized(lock) { tts }
            if (engine == null || _status.value != EngineStatus.READY) {
                cont.resume(emptyList())
                return@post
            }
            val result = try {
                val enginePkg = engine.defaultEngine
                engine.voices.orEmpty().map { voice -> voice.toModel(enginePkg) }
            } catch (e: Exception) {
                AppLogger.w(tag, "Voice query failed: ${e.message}")
                emptyList()
            }
            cont.resume(result)
        }
    }

    override suspend fun queryEngines(): List<TtsEngineInfo> = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val result = try {
                val engine = synchronized(lock) { tts }
                val defaultPkg = try { engine?.defaultEngine } catch (e: Exception) { null }
                engine?.engines.orEmpty().map { info ->
                    TtsEngineInfo(
                        packageName = info.name,
                        label = info.label ?: info.name,
                        isSystemDefault = info.name == defaultPkg
                    )
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Engine query failed: ${e.message}")
                emptyList()
            }
            cont.resume(result)
        }
    }

    private fun Voice.toModel(enginePackage: String?): TtsVoiceInfo {
        return TtsVoiceInfo(
            id = name,
            displayName = humanizeVoiceName(name, locale.toLanguageTag()),
            localeTag = locale.toLanguageTag(),
            quality = mapQuality(quality),
            latency = mapLatency(latency),
            networkRequired = isNetworkConnectionRequired,
            features = features?.toSet() ?: emptySet(),
            enginePackage = enginePackage
        )
    }

    /**
     * Voice names like "en-US-x-sfg#female_2-local" are unreadable in Settings;
     * surface locale + network status instead.
     */
    private fun humanizeVoiceName(name: String, localeTag: String): String {
        val kind = when {
            name.endsWith("-local") -> "On-device"
            name.endsWith("-network") -> "Network"
            else -> null
        }
        return if (kind != null) "$localeTag ($kind)" else "$localeTag — $name"
    }

    private fun mapQuality(q: Int): VoiceQuality = when (q) {
        Voice.QUALITY_VERY_HIGH -> VoiceQuality.VERY_HIGH
        Voice.QUALITY_HIGH -> VoiceQuality.HIGH
        Voice.QUALITY_NORMAL -> VoiceQuality.NORMAL
        Voice.QUALITY_LOW -> VoiceQuality.LOW
        else -> VoiceQuality.VERY_LOW
    }

    private fun mapLatency(l: Int): VoiceLatency = when (l) {
        Voice.LATENCY_VERY_LOW -> VoiceLatency.VERY_LOW
        Voice.LATENCY_LOW -> VoiceLatency.LOW
        Voice.LATENCY_NORMAL -> VoiceLatency.NORMAL
        Voice.LATENCY_HIGH -> VoiceLatency.HIGH
        else -> VoiceLatency.VERY_HIGH
    }

    // ------------------------------------------------------------------ timeouts

    private fun estimateTimeoutMs(utterance: EngineUtterance): Long {
        val rate = utterance.rate.coerceIn(MIN_VALID_RATE, MAX_VALID_RATE)
        val estimated = (utterance.text.length * MS_PER_CHAR_AT_1X / rate).toLong()
        return estimated.coerceIn(MIN_UTTERANCE_TIMEOUT_MS, MAX_UTTERANCE_TIMEOUT_MS)
    }

    companion object {
        /** "Queue until ready" bound for speaks issued while the engine initializes. */
        const val INIT_TIMEOUT_MS = 8_000L

        /** Conservative speech duration estimate per character at 1.0× rate. */
        const val MS_PER_CHAR_AT_1X = 110L
        const val MIN_UTTERANCE_TIMEOUT_MS = 4_000L
        const val MAX_UTTERANCE_TIMEOUT_MS = 360_000L
        const val MIN_VALID_RATE = AppSettingsPolicy.MIN_TTS_RATE
        const val MAX_VALID_RATE = AppSettingsPolicy.MAX_TTS_RATE

        const val MAX_CONSECUTIVE_TIMEOUTS = 2
        const val MAX_RECOVERY_ATTEMPTS = 3
    }
}
