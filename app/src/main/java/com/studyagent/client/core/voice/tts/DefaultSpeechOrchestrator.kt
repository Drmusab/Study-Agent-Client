package com.studyagent.client.core.voice.tts

import com.studyagent.client.core.models.AppSettingsPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Sequences every spoken request the app makes.
 *
 * Concurrency model — a single coroutine "actor" consumes [ControlMsg] from a
 * bounded channel, so queue mutations are race-free by confinement; the *playback
 * pump* runs as a separate coroutine the actor can cancel for REPLACE / INTERRUPT /
 * stop policies. Ordering guarantees:
 *
 *   endStudy():      stopSpeech(SESSION_END); speak(summary)   → summary plays after drain (§60)
 *   repeat:          speak(REPLACE)                            → old speech dies, question replays
 *   headset pull:    stopSpeech(ROUTE_LOST)                    → current + pending get Failed(ROUTE_LOST)
 *
 * A logical request may render as several engine utterances (language segments ×
 * chunks) but completes exactly once (§27): Completed only after the final chunk,
 * Cancelled if interrupted, Failed with the first typed error otherwise.
 *
 * This class is intentionally free of Android framework imports so the full state
 * machine is unit-testable on the JVM with a fake [TtsEngineAdapter].
 */
class DefaultSpeechOrchestrator(
    private val engine: TtsEngineAdapter,
    private val focusController: SpeechFocusController = NoOpSpeechFocusController(),
    private val preprocessor: SpeechTextPreprocessor = SpeechTextPreprocessor(),
    private val medicalProcessor: MedicalPronunciationProcessor = MedicalPronunciationProcessor(),
    private val segmenter: MixedLanguageSegmenter = MixedLanguageSegmenter(),
    private val chunker: SpeechChunker = SpeechChunker(),
    private val queue: SpeechQueue = SpeechQueue(),
    private val headsetConnected: StateFlow<Boolean>? = null,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SpeechOrchestrator {

    private val scope = CoroutineScope(SupervisorJob() + workDispatcher)

    // ------------------------------------------------------------- observable state

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Uninitialized)
    override val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _health = MutableStateFlow(TtsHealthSnapshot())
    override val health: StateFlow<TtsHealthSnapshot> = _health.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    // ------------------------------------------------------------- actor plumbing

    private sealed interface ControlMsg {
        class Speak(val request: SpeechRequest, val completion: CompletableDeferred<SpeechResult>) : ControlMsg
        class Stop(val reason: StopReason) : ControlMsg
        class Settings(val value: TtsSettings) : ControlMsg
    }

    private val controlChannel = Channel<ControlMsg>(capacity = 64)

    @Volatile
    private var pumpController: Job? = null

    @Volatile
    private var currentEntry: SpeechQueue.Entry? = null

    private val stopReason = AtomicReference(StopReason.USER)

    @Volatile
    private var settings: TtsSettings = TtsSettings()

    /** One-line fingerprint so voice caches invalidate only when voice-relevant input changes. */
    @Volatile
    private var voiceFingerprint: Int = computeVoiceFingerprint(settings)

    private val voiceCache = HashMap<String, TtsVoiceInfo?>()
    private val voiceQueryMutex = Mutex()

    @Volatile
    private var enginePackageCache: String? = null

    // ------------------------------------------------------------- metrics (atomics; lock-free)

    private val createdAtMs = clock()
    private val timeToReadyMs = AtomicReference(-1L)
    private val lastRequestToStartMs = AtomicReference(-1L)
    private val lastRequestDurationMs = AtomicReference(-1L)
    private val completedRequests = AtomicInteger(0)
    private val failedRequests = AtomicInteger(0)
    private val cancelledRequests = AtomicInteger(0)
    private val focusDenials = AtomicInteger(0)
    private val routeInterruptions = AtomicInteger(0)

    private val lastError = AtomicReference<SpeechError?>(null)
    private val requestStartedAtMs = AtomicReference(-1L)

    private val focusPaused = AtomicBoolean(false)
    private val focusResumeSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)

    private val released = AtomicBoolean(false)

    init {
        engine.utteranceStartedListener = { onEngineUtteranceStarted() }

        // Actor loop — every queue/pump mutation happens in this single coroutine.
        scope.launch {
            for (msg in controlChannel) {
                when (msg) {
                    is ControlMsg.Speak -> handleSpeakMessage(msg)
                    is ControlMsg.Stop -> handleStopMessage(msg.reason)
                    is ControlMsg.Settings -> applySettings(msg.value)
                }
            }
        }

        // Engine lifecycle → state/health; also first-time-to-ready metric.
        scope.launch {
            engine.status.collect { status ->
                when (status) {
                    EngineStatus.READY -> {
                        if (timeToReadyMs.get() < 0) timeToReadyMs.set(clock() - createdAtMs)
                        _isReady.value = true
                        if (!_isSpeaking.value) _ttsState.value = readyState()
                        cacheEnginePackageOnce()
                    }
                    EngineStatus.FAILED -> {
                        _isReady.value = false
                        _ttsState.value = TtsState.Error(lastError.get() ?: SpeechError.engineInitFailed(-1))
                    }
                    EngineStatus.INITIALIZING -> {
                        _isReady.value = false
                        if (!_isSpeaking.value) _ttsState.value = TtsState.Initializing
                    }
                    EngineStatus.RELEASED -> {
                        _isReady.value = false
                        _ttsState.value = TtsState.Released
                    }
                    EngineStatus.UNINITIALIZED -> Unit
                }
                refreshHealth()
            }
        }

        // Audio-focus policy (§34/§35): transient → pause at chunk boundary and resume;
        // permanent → stop and report. Ducking is deliberately treated as pause (the
        // spoken study content must stay intelligible, not quieter).
        scope.launch {
            focusController.events.collect { event ->
                when (event) {
                    is SpeechFocusEvent.Gained -> {
                        if (focusPaused.get()) {
                            focusPaused.set(false)
                            focusResumeSignal.get()?.complete(true)
                        }
                    }
                    is SpeechFocusEvent.LossTransient,
                    is SpeechFocusEvent.LossTransientCanDuck -> {
                        if (_isSpeaking.value) {
                            focusPaused.set(true)
                            // Stops the engine utterance but NOT the logical request: the
                            // chunk loop waits for regain and replays the interrupted chunk.
                            engine.stop()
                        }
                    }
                    is SpeechFocusEvent.LossPermanent -> stopSpeech(StopReason.FOCUS_LOST)
                }
                refreshHealth()
            }
        }

        // Headset disconnect policy (§37): the safe pocket-study default pauses speech
        // instead of blasting private content through the loudspeaker.
        headsetConnected?.let { route ->
            scope.launch {
                var wasConnected = route.value
                route.collect { connected ->
                    val dropped = wasConnected && !connected
                    wasConnected = connected
                    if (dropped &&
                        _isSpeaking.value &&
                        settings.headsetDisconnectBehavior == HeadsetDisconnectBehavior.PAUSE_SPEECH
                    ) {
                        routeInterruptions.incrementAndGet()
                        stopSpeech(StopReason.ROUTE_LOST)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- public API

    override suspend fun speak(request: SpeechRequest): SpeechResult {
        if (released.get()) return SpeechResult.Failed(SpeechError.notInitialized())
        if (request.text.isBlank()) return SpeechResult.Completed
        val completion = CompletableDeferred<SpeechResult>()
        val sent = controlChannel.trySend(ControlMsg.Speak(request, completion))
        if (sent.isFailure) {
            return SpeechResult.Failed(
                SpeechError(SpeechErrorCode.QUEUE_FULL, "Speech control channel is saturated.")
            )
        }
        return completion.await()
    }

    override suspend fun speakPreview(language: SegmentLanguage): SpeechResult {
        // REPLACE cancels any previous preview — "cancel previous before starting another one".
        return speak(
            SpeechRequest(
                id = SpeechIds.forPurpose(SpeechPurpose.PREVIEW),
                text = SpeechFormatting.previewText(language),
                purpose = SpeechPurpose.PREVIEW,
                languageHint = when (language) {
                    SegmentLanguage.ARABIC -> LanguageHint.ARABIC
                    else -> LanguageHint.ENGLISH
                },
                priority = SpeechPriority.LOW,
                queuePolicy = QueuePolicy.REPLACE
            )
        )
    }

    override fun stopSpeech(reason: StopReason) {
        if (released.get()) return
        controlChannel.trySend(ControlMsg.Stop(reason))
    }

    override suspend fun getVoices(languageCode: String): List<TtsVoiceInfo> {
        val all = withTimeoutOrNull(VOICE_QUERY_TIMEOUT_MS) { engine.queryVoices() } ?: emptyList()
        return TtsVoiceSelector.rankedForDisplay(
            voices = all,
            language = languageCode,
            selectedVoiceId = null,
            preferOffline = settings.preferOfflineVoices,
            preferredLocaleTag = localeForLanguage(languageCode).toLanguageTag()
        )
    }

    override suspend fun getEngines(): List<TtsEngineInfo> =
        withTimeoutOrNull(VOICE_QUERY_TIMEOUT_MS) { engine.queryEngines() } ?: emptyList()

    override fun updateSettings(settings: TtsSettings) {
        controlChannel.trySend(ControlMsg.Settings(settings))
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        stopReason.set(StopReason.RELEASE)
        queue.drain(SpeechResult.Cancelled)
        // Complete anything still sitting in the actor channel so no caller hangs.
        while (true) {
            val msg = controlChannel.tryReceive().getOrNull() ?: break
            if (msg is ControlMsg.Speak) msg.completion.complete(SpeechResult.Cancelled)
        }
        pumpController?.cancel()
        focusController.abandonFocus()
        engine.release()
        _isSpeaking.value = false
        _ttsState.value = TtsState.Released
        refreshHealth()
        scope.cancel()
    }

    // ------------------------------------------------------------- actor handlers (confined)

    private fun handleSpeakMessage(msg: ControlMsg.Speak) {
        val request = msg.request
        val completion = msg.completion

        // Duplicate suppression (§30): recomposition / duplicate server messages must not
        // re-trigger speech while an identical request is in-flight or pending. A
        // deliberate REPLACE/INTERRUPT (e.g. user said "Repeat") always goes through.
        if (request.queuePolicy != QueuePolicy.REPLACE && request.queuePolicy != QueuePolicy.INTERRUPT) {
            val inFlight = currentEntry?.request
            val duplicatesInFlight = inFlight != null &&
                (inFlight.id == request.id || inFlight.semanticKey == request.semanticKey)
            val duplicatesPending = queue.containsDuplicateOf(request)
            if (duplicatesInFlight || duplicatesPending) {
                completion.complete(SpeechResult.Cancelled)
                return
            }
        }

        when (request.queuePolicy) {
            QueuePolicy.REPLACE -> {
                stopReason.set(StopReason.REPLACED)
                queue.drain(SpeechResult.Cancelled)
                cancelCurrentIfAble(force = false)
                enqueueNow(request, completion)
            }
            QueuePolicy.INTERRUPT -> {
                stopReason.set(StopReason.REPLACED)
                queue.drain(SpeechResult.Cancelled)
                cancelCurrentIfAble(force = true)
                enqueueNow(request, completion)
            }
            QueuePolicy.APPEND,
            QueuePolicy.IGNORE_IF_DUPLICATE -> enqueueNow(request, completion)
        }
    }

    private fun enqueueNow(request: SpeechRequest, completion: CompletableDeferred<SpeechResult>) {
        when (val result = queue.enqueue(request, completion)) {
            SpeechQueue.EnqueueResult.Enqueued -> ensurePumpRunning()
            SpeechQueue.EnqueueResult.DuplicateDropped -> completion.complete(SpeechResult.Cancelled)
            SpeechQueue.EnqueueResult.RejectedFull -> completion.complete(
                SpeechResult.Failed(
                    SpeechError(SpeechErrorCode.QUEUE_FULL, "Speech queue is full; request dropped.")
                )
            )
            is SpeechQueue.EnqueueResult.EvictedLowest -> {
                result.evicted.completion.complete(SpeechResult.Cancelled)
                ensurePumpRunning()
            }
        }
        refreshHealth()
    }

    private fun handleStopMessage(reason: StopReason) {
        stopReason.set(reason)
        val result = resultForStop(reason)
        queue.drain(result)
        pumpController?.cancel()
        pumpController = null
        // Nothing is playing any more → settle global state immediately.
        _isSpeaking.value = false
        focusController.abandonFocus()
        if (_isReady.value) _ttsState.value = readyState()
        refreshHealth()
    }

    private fun cancelCurrentIfAble(force: Boolean) {
        val entry = currentEntry ?: return
        if (force || entry.request.interruptible) {
            pumpController?.cancel()
            pumpController = null
        }
        // A non-interruptible in-flight request is allowed to finish; the new request
        // was already queued and will play right after.
    }

    // ------------------------------------------------------------- playback pump

    private fun ensurePumpRunning() {
        if (pumpController?.isActive == true) return
        // Separate controller Job so identity survives the launch/assignment race and we can
        // distinguish "latest pump" from a cancelled predecessor when settling global state.
        val controller = Job(scope.coroutineContext[Job])
        pumpController = controller
        scope.launch(controller) {
            while (isActive) {
                val entry = queue.poll() ?: break
                currentEntry = entry
                stopReason.set(StopReason.USER)
                val result = runEntry(entry)
                entry.completion.complete(result)
                currentEntry = null
                refreshHealth()
            }
            // Only the latest pump may settle global state/focus — a cancelled predecessor
            // must not tear down the replacement's audio focus or overwrite Speaking state.
            if (pumpController === controller) {
                focusController.abandonFocus()
                if (_isReady.value) _ttsState.value = readyState()
                refreshHealth()
            }
        }
    }

    private suspend fun runEntry(entry: SpeechQueue.Entry): SpeechResult {
        val request = entry.request
        val startedAt = clock()
        requestStartedAtMs.set(startedAt)

        return try {
            val result = executeRequest(request)
            when (result) {
                SpeechResult.Completed -> {
                    completedRequests.incrementAndGet()
                    lastRequestDurationMs.set(clock() - startedAt)
                }
                SpeechResult.Cancelled -> cancelledRequests.incrementAndGet()
                is SpeechResult.Failed -> {
                    failedRequests.incrementAndGet()
                    lastError.set(result.error)
                }
            }
            result
        } catch (ce: CancellationException) {
            // Pump cancelled (REPLACE / stop / route loss): the adapter cancellation path has
            // already silenced the engine; map the stop reason to a terminal result.
            val mapped = resultForStop(stopReason.get())
            if (mapped is SpeechResult.Cancelled) {
                cancelledRequests.incrementAndGet()
            } else {
                failedRequests.incrementAndGet()
                lastError.set((mapped as SpeechResult.Failed).error)
            }
            mapped
        } catch (t: Throwable) {
            failedRequests.incrementAndGet()
            val error = SpeechError(
                SpeechErrorCode.PLAYBACK_ERROR,
                t.message ?: "Unexpected speech pipeline failure"
            )
            lastError.set(error)
            SpeechResult.Failed(error)
        } finally {
            _isSpeaking.value = false
        }
    }

    // ------------------------------------------------------------- request execution pipeline

    private suspend fun executeRequest(request: SpeechRequest): SpeechResult {
        val cfg = settings

        // 1. Speech-only markup/whitespace cleanup (the visible card is never touched).
        val cleaned = preprocessor.preprocess(request.text)
        if (cleaned.isBlank()) return SpeechResult.Completed

        // 2. Centralized spoken lead-ins, then conservative language segmentation.
        val formatted = SpeechFormatting.forPurpose(request.purpose, cleaned)
        val segments = segmenter.segment(
            text = formatted,
            hint = request.languageHint,
            autoDetect = cfg.autoLanguageDetection
        )
        if (segments.isEmpty()) return SpeechResult.Completed

        // 3. Medical pronunciation (English segments only) + semantic chunking against
        // the platform's real input limit — never a hard-coded assumption (§25).
        val maxChunk = engine.maxSpeechInputLength().coerceAtLeast(MIN_CHUNK_LENGTH)
        val items = ArrayList<ChunkItem>()
        for (segment in segments) {
            val normalized = if (segment.language == SegmentLanguage.ENGLISH && cfg.medicalPronunciation) {
                medicalProcessor.applyToEnglish(segment.text)
            } else {
                segment.text
            }
            for (chunk in chunker.split(normalized, maxChunk)) {
                items += ChunkItem(chunk, segment.language)
            }
        }
        if (items.isEmpty()) return SpeechResult.Completed

        // 4. Resolved speech profile (validated/clamped — NaN/extremes never reach the engine).
        val rate = clampRate(request.profile?.rateOverride ?: rateForPurpose(request.purpose, cfg))
        val pitch = clampPitch(request.profile?.pitchOverride ?: cfg.pitch)

        // 5. Audio focus (re-request only when not already held — consecutive requests must
        // not spam focus announcements to other apps). Denial degrades gracefully: OEMs
        // occasionally deny transient focus; we still speak and count it.
        if (!focusController.hasFocus && !focusController.requestFocus()) {
            focusDenials.incrementAndGet()
        }

        // 6. Speak chunks sequentially — one engine utterance at a time (deterministic).
        var index = 0
        while (index < items.size) {
            currentCoroutineContext().ensureActive()

            // Audio-focus pause before the next chunk (phone call / navigation / alarm).
            if (focusPaused.get()) {
                val regained = awaitFocusRegain(FOCUS_RESUME_TIMEOUT_MS)
                if (!regained) {
                    return SpeechResult.Failed(
                        SpeechError(
                            SpeechErrorCode.AUDIO_FOCUS_LOST,
                            "Audio focus was not regained in time.",
                            recoverable = true
                        )
                    )
                }
            }

            val item = items[index]
            val locale = localeForLanguage(item.language, cfg)
            val voice = resolveVoice(item.language, cfg)

            _isSpeaking.value = true
            _ttsState.value = TtsState.Speaking(
                requestId = request.id,
                purpose = request.purpose,
                chunkIndex = index + 1,
                chunkCount = items.size,
                queueDepth = queue.size
            )
            refreshHealth()

            val utterance = EngineUtterance(
                utteranceId = SpeechIds.chunkId(request.id, index),
                text = item.text,
                locale = locale,
                voiceName = voice?.id,
                rate = rate,
                pitch = pitch
            )

            when (val result = engine.speak(utterance)) {
                SpeechResult.Completed -> index++
                SpeechResult.Cancelled ->
                    // Focus-loss pause terminated this utterance → loop replays the chunk
                    // after regain; anything else is a real cancellation.
                    if (focusPaused.get()) continue else return SpeechResult.Cancelled
                is SpeechResult.Failed -> return result
            }
        }
        return SpeechResult.Completed
    }

    private suspend fun awaitFocusRegain(timeoutMs: Long): Boolean {
        val signal = CompletableDeferred<Boolean>()
        focusResumeSignal.set(signal)
        val resumed = withTimeoutOrNull(timeoutMs) { signal.await() }
        focusResumeSignal.compareAndSet(signal, null)
        return resumed == true && !focusPaused.get()
    }

    // ------------------------------------------------------------- voices

    private suspend fun resolveVoice(language: SegmentLanguage, cfg: TtsSettings): TtsVoiceInfo? {
        val langKey = when (language) {
            SegmentLanguage.ARABIC -> "ar"
            else -> "en"
        }
        voiceQueryMutex.withLock {
            if (voiceCache.containsKey(langKey)) return voiceCache[langKey]
        }
        val voices = withTimeoutOrNull(VOICE_QUERY_TIMEOUT_MS) { engine.queryVoices() } ?: emptyList()
        val selectedId = when (langKey) {
            "ar" -> cfg.arabicVoiceId
            else -> cfg.englishVoiceId
        }
        val chosen = TtsVoiceSelector.select(
            voices = voices,
            language = langKey,
            preferredLocaleTag = localeForLanguage(language, cfg).toLanguageTag(),
            selectedVoiceId = selectedId,
            preferOffline = cfg.preferOfflineVoices
        )
        voiceQueryMutex.withLock { voiceCache[langKey] = chosen }
        refreshHealth()
        return chosen
    }

    private fun localeForLanguage(language: SegmentLanguage, cfg: TtsSettings = settings): Locale =
        when (language) {
            SegmentLanguage.ARABIC -> parseLocaleSafe(
                cfg.arabicLocale,
                Locale.forLanguageTag(AppSettingsPolicy.DEFAULT_TTS_ARABIC_LOCALE)
            )
            else -> parseLocaleSafe(
                cfg.englishLocale,
                Locale.forLanguageTag(AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE)
            )
        }

    private fun localeForLanguage(languageCode: String): Locale =
        when (languageCode.lowercase()) {
            "ar" -> parseLocaleSafe(
                settings.arabicLocale,
                Locale.forLanguageTag(AppSettingsPolicy.DEFAULT_TTS_ARABIC_LOCALE)
            )
            else -> parseLocaleSafe(
                settings.englishLocale,
                Locale.forLanguageTag(AppSettingsPolicy.DEFAULT_ENGLISH_LOCALE)
            )
        }

    private fun parseLocaleSafe(tag: String, fallback: Locale): Locale =
        try {
            val locale = Locale.forLanguageTag(tag)
            if (locale.language.isBlank()) fallback else locale
        } catch (e: Exception) {
            fallback
        }

    // ------------------------------------------------------------- settings

    private fun applySettings(newSettings: TtsSettings) {
        val old = settings
        settings = newSettings
        val newFingerprint = computeVoiceFingerprint(newSettings)
        if (newFingerprint != voiceFingerprint) {
            voiceFingerprint = newFingerprint
            voiceCache.clear()
        }
        if (newSettings.engineId != old.engineId) {
            enginePackageCache = null
            engine.setEngine(newSettings.engineId)
        }
        refreshHealth()
    }

    private fun computeVoiceFingerprint(cfg: TtsSettings): Int {
        var result = cfg.engineId?.hashCode() ?: 0
        result = 31 * result + (cfg.englishVoiceId?.hashCode() ?: 0)
        result = 31 * result + (cfg.arabicVoiceId?.hashCode() ?: 0)
        result = 31 * result + cfg.preferOfflineVoices.hashCode()
        result = 31 * result + cfg.englishLocale.hashCode()
        result = 31 * result + cfg.arabicLocale.hashCode()
        return result
    }

    // ------------------------------------------------------------- profiles & clamps

    private fun rateForPurpose(purpose: SpeechPurpose, cfg: TtsSettings): Float =
        when (purpose) {
            SpeechPurpose.QUESTION, SpeechPurpose.HINT -> cfg.questionRate
            SpeechPurpose.FEEDBACK, SpeechPurpose.RATING_CONFIRMATION,
            SpeechPurpose.STATUS, SpeechPurpose.SYSTEM, SpeechPurpose.ERROR -> cfg.feedbackRate
            SpeechPurpose.EXPLANATION, SpeechPurpose.ANSWER,
            SpeechPurpose.SESSION_SUMMARY, SpeechPurpose.PREVIEW -> cfg.explanationRate
        }

    private fun clampRate(rate: Float): Float = when {
        !rate.isFinite() -> AppSettingsPolicy.DEFAULT_TTS_RATE
        else -> rate.coerceIn(MIN_RATE, MAX_RATE)
    }

    private fun clampPitch(pitch: Float): Float = when {
        !pitch.isFinite() -> AppSettingsPolicy.DEFAULT_SPEECH_PITCH
        else -> pitch.coerceIn(MIN_PITCH, MAX_PITCH)
    }

    // ------------------------------------------------------------- state & health

    private fun readyState(): TtsState.Ready {
        return TtsState.Ready(
            enginePackage = enginePackageCache ?: settings.engineId,
            englishVoiceDisplay = voiceCache["en"]?.displayName,
            arabicVoiceDisplay = voiceCache["ar"]?.displayName
        )
    }

    private fun cacheEnginePackageOnce() {
        if (enginePackageCache != null) return
        scope.launch {
            val engines = withTimeoutOrNull(VOICE_QUERY_TIMEOUT_MS) { engine.queryEngines() }
            val resolved = engines?.firstOrNull { it.isSystemDefault }?.packageName
                ?: settings.engineId
            if (resolved != null) {
                enginePackageCache = resolved
                refreshHealth()
            }
        }
    }

    private fun refreshHealth() {
        val current = currentEntry
        _health.value = TtsHealthSnapshot(
            engineStatus = engine.status.value,
            enginePackage = enginePackageCache ?: settings.engineId,
            englishVoiceDisplay = voiceCache["en"]?.displayName,
            arabicVoiceDisplay = voiceCache["ar"]?.displayName,
            englishVoiceOffline = voiceCache["en"]?.let { !it.networkRequired },
            arabicVoiceOffline = voiceCache["ar"]?.let { !it.networkRequired },
            audioFocusHeld = focusController.hasFocus,
            queueDepth = queue.size,
            speakingPurpose = current?.request?.purpose,
            lastError = lastError.get(),
            metrics = TtsMetrics(
                timeToReadyMs = timeToReadyMs.get(),
                lastRequestToStartMs = lastRequestToStartMs.get(),
                lastRequestDurationMs = lastRequestDurationMs.get(),
                completedRequests = completedRequests.get(),
                failedRequests = failedRequests.get(),
                cancelledRequests = cancelledRequests.get(),
                audioFocusDenials = focusDenials.get(),
                routeInterruptions = routeInterruptions.get()
            )
        )
    }

    private fun onEngineUtteranceStarted() {
        val startAt = requestStartedAtMs.get()
        if (startAt > 0) {
            lastRequestToStartMs.set(clock() - startAt)
        }
    }

    private fun resultForStop(reason: StopReason): SpeechResult = when (reason) {
        StopReason.ROUTE_LOST -> SpeechResult.Failed(SpeechError.routeLost())
        StopReason.FOCUS_LOST -> SpeechResult.Failed(
            SpeechError(
                SpeechErrorCode.AUDIO_FOCUS_LOST,
                "Audio focus was permanently lost.",
                recoverable = true
            )
        )
        else -> SpeechResult.Cancelled
    }

    private data class ChunkItem(val text: String, val language: SegmentLanguage)

    companion object {
        const val MIN_RATE = AppSettingsPolicy.MIN_TTS_RATE
        const val MAX_RATE = AppSettingsPolicy.MAX_TTS_RATE
        const val MIN_PITCH = AppSettingsPolicy.MIN_SPEECH_PITCH
        const val MAX_PITCH = AppSettingsPolicy.MAX_SPEECH_PITCH
        const val MIN_CHUNK_LENGTH = 128
        const val VOICE_QUERY_TIMEOUT_MS = 2_000L

        /** How long chunk playback waits for focus regain before failing the request. */
        const val FOCUS_RESUME_TIMEOUT_MS = 5_000L
    }
}
