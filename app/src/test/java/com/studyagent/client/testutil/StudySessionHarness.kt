package com.studyagent.client.testutil

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.StudyAudioDisconnectPolicy
import com.studyagent.client.core.audio.StudyAudioPreferences
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.diagnostics.DiagnosticEvent
import com.studyagent.client.core.diagnostics.DiagnosticTimeline
import com.studyagent.client.core.diagnostics.PerformanceMetrics
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.network.NetworkStatsRegistry
import com.studyagent.client.core.study.EffectIds
import com.studyagent.client.core.study.MachineResourceCounts
import com.studyagent.client.core.study.SessionDiagnosticsSnapshot
import com.studyagent.client.core.study.SessionMachineState
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.core.study.StudyReducer
import com.studyagent.client.core.study.SubmissionLedger
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.RecognitionTurnResult
import com.studyagent.client.data.repository.StudySessionMachineRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

/**
 * The full JVM study session, with no Android hardware anywhere (§14).
 *
 * ```text
 *   StudySessionMachineRepository            ← the production coordinator under test
 *        │
 *   StudySessionMachine ── reducer ── effect executor
 *        │                 │
 *        │        FakeSpeechOrchestrator / FakeRecognitionOrchestrator    (voice)
 *        │        StudyAudioRouteCoordinator + FakeAudioRouteManager       (routing)
 *        │        FakeConnectionRepository ←→ FakeStudyServer             (transport + PC agent)
 *        │
 *   PerformanceMetrics + DiagnosticTimeline                             (observability)
 * ```
 *
 * What is real here: the state machine, the reducer, the reconciler, the effect executor, the
 * submission ledger, the audio-route policy, the protocol JSON and every timeout/decision rule.
 * What is scripted: the hardware boundaries (TTS engine, recognizer, AudioManager) and the peer
 * (the PC agent).
 *
 * Every millisecond is virtual ([TestCoroutineScheduler]), so a 1000-card session runs in seconds
 * and no test ever sleeps (§8/§18).
 *
 * The harness also owns *passive* invariant checking: [checkInvariants] is evaluated after every
 * [advance], and any violation is remembered — so a chaos run that breaks an invariant is reported
 * even when the specific test did not think to look for it (§25/§172).
 */
class StudySessionHarness internal constructor(
    val testScheduler: TestCoroutineScheduler,
    val scope: CoroutineScope,
    val settings: AppSettings,
    val clock: TestClock,
    val connection: FakeConnectionRepository,
    val speech: FakeSpeechOrchestrator,
    val recognition: FakeRecognitionOrchestrator,
    val routeManager: FakeAudioRouteManager,
    /** The device facts the coordinator observes; publish to simulate a route change. */
    val routeSnapshots: MutableStateFlow<AudioRouteSnapshot>,
    val coordinator: StudyAudioRouteCoordinator,
    val performance: PerformanceMetrics,
    val timeline: DiagnosticTimeline,
    val repository: StudySessionMachineRepository,
    val server: FakeStudyServer,
    /** True when the fake recognizer answers the microphone window without test help. */
    val autoAnswerEnabled: Boolean
) {

    /** Machine state — the authoritative internal snapshot. */
    val machineState: StateFlow<SessionMachineState> get() = repository.machineState()

    /** The public state the UI renders, derived from the machine by production code. */
    val studyState: StateFlow<StudyState> get() = repository.studyState

    val phase: SessionPhase get() = machineState.value.phase

    /** Alias kept for readability in suites that read "the phase the UI is showing". */
    val currentPhase: SessionPhase get() = phase

    val currentCardId: String? get() = machineState.value.currentCardId
    val epoch: Long get() = machineState.value.epoch
    val cardGeneration: Long get() = machineState.value.cardGeneration
    val turnId: String? get() = machineState.value.cardTurn?.turnId

    fun lastTurnId(): String? = machineState.value.cardTurn?.turnId

    /**
     * True when the microphone was ever open while the app was speaking.
     *
     * Measured at the *boundary* (when either side starts), so a one-frame overlap inside a single
     * `advance()` cannot hide between two state reads.
     */
    var overlapDetected: Boolean = false
        internal set

    /** Answer text a hands-free turn "hears". */
    var answerText: String = TestTranscripts.MEDICAL_ANSWER

    /**
     * Invariant violations observed so far. Empty is the only healthy value; a chaos test can
     * therefore fail with *what* broke even when it asserted something narrower.
     */
    val violations: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------ driving

    /** Advances virtual time and runs everything that became ready. */
    fun advance(millis: Long) {
        testScheduler.advanceTimeBy(millis)
        testScheduler.runCurrent()
        observe()
    }

    /** Advances until [predicate] holds; returns whether it held. Never sleeps. */
    fun advanceUntil(maxSteps: Int = 40, stepMs: Long = STEP_MS, predicate: () -> Boolean): Boolean {
        repeat(maxSteps) {
            if (predicate()) return true
            advance(stepMs)
        }
        return predicate()
    }

    /** Advances past the longest single speech + acoustic gap + recognizer start. */
    fun settle() = advance(SETTLE_MS)

    fun awaitPhase(predicate: (SessionPhase) -> Boolean, maxSteps: Int = 40): Boolean =
        advanceUntil(maxSteps) { predicate(phase) }

    fun awaitWaitingForAnswer(): Boolean = awaitPhase { it is SessionPhase.WaitingForAnswer }

    fun awaitWaitingForRating(): Boolean = awaitPhase { it is SessionPhase.WaitingForRating }

    fun awaitPhaseIs(target: SessionPhase, maxSteps: Int = 40): Boolean = awaitPhase({ it == target }, maxSteps)

    suspend fun startSession(deck: String? = "Toronto Notes", mode: String = "review_due") {
        repository.startStudy(deck, mode, null)
        runCurrent()
        awaitPhase({ it is SessionPhase.SpeakingQuestion || it is SessionPhase.WaitingForAnswer }, maxSteps = 10)
    }

    suspend fun answer(text: String = answerText) {
        // The card the *server* asked for is the only one an answer can belong to; falling back to
        // it keeps a deliberate "answer too early" test from failing on a helper error instead of
        // on the behaviour under test.
        val cardId = currentCardId ?: server.currentCardId ?: return
        repository.submitSpokenAnswer(cardId, text)
        runCurrent()
        observe()
    }

    suspend fun rate(rating: Rating = Rating.GOOD) {
        repository.rateCurrentCard(rating)
        runCurrent()
        observe()
    }

    suspend fun pause() {
        repository.pauseStudy()
        runCurrent()
    }

    suspend fun resume() {
        repository.resumeStudy()
        runCurrent()
    }

    suspend fun end() {
        repository.endStudy()
        runCurrent()
    }

    suspend fun skip() {
        repository.skipCard()
        runCurrent()
    }

    suspend fun repeatQuestion() {
        repository.requestRepeat()
        runCurrent()
    }

    suspend fun hint() {
        repository.requestHint()
        runCurrent()
    }

    fun stopSpeaking() {
        repository.requestStopSpeaking()
        runCurrent()
    }

    fun pressToTalk() {
        repository.startManualPushToTalk()
        runCurrent()
    }

    fun releasePushToTalk() {
        repository.stopManualPushToTalk()
        runCurrent()
    }

    /**
     * Delivers a terminal transcript for the live microphone turn.
     *
     * Returns false when there was no turn to complete — the caller then has to decide whether that
     * is an error (it usually is) or the point of the test.
     */
    fun speakAnswer(text: String = answerText, cardId: String? = currentCardId): Boolean =
        recognition.deliverAnswer(text = text, cardId = cardId)

    /** Rates by voice, through the real spoken-command grammar rather than the button path. */
    fun speakRating(word: String = "good"): Boolean =
        recognition.deliverAnswer(
            text = word,
            purpose = RecognitionPurpose.RATING,
            cardId = currentCardId
        )

    /**
     * One card, end to end: question → answer → evaluation → feedback → rating → next card.
     *
     * Returns the card that was answered, or null when the machine never reached a microphone
     * window (that is itself the failure signal, and [report] explains why).
     */
    suspend fun playCard(rating: Rating = Rating.GOOD, text: String = answerText): String? {
        if (!awaitWaitingForAnswer()) return currentCardId
        val cardId = currentCardId
        if (!autoAnswerEnabled) {
            // On a device the transcript arrives from the recognizer, which is also what feeds the
            // STT metrics. The direct submit is only a fallback for the (test-only) case where no
            // turn is open: it exercises the same repository entry point without pretending a
            // recognizer said something.
            if (!speakAnswer(text = text, cardId = cardId)) answer(text)
        }
        if (!awaitWaitingForRating()) return cardId
        rate(rating)
        // Wait for the rating to be acknowledged and the *next* card to arrive; a rating that is
        // silently dropped would otherwise make the next playCard() answer the same card twice.
        advanceUntil(maxSteps = 20) { currentCardId != null && currentCardId != cardId }
        return cardId
    }

    // ------------------------------------------------------------------ inspection

    fun resources(): MachineResourceCounts = repository.resourceSnapshot()

    fun diagnostics(): SessionDiagnosticsSnapshot = repository.diagnostics()

    fun machineInvariantViolations(): Long = repository.invariantViolations()

    fun timelineEvents(): List<DiagnosticEvent> = timeline.snapshot()

    fun timelineEventNames(): List<String> = timeline.snapshot().map { it.event }

    fun eventsOf(event: String): List<DiagnosticEvent> = timeline.snapshot().filter { it.event == event }

    fun sentOfType(type: String): List<ClientMessage> = connection.sentOfType(type)

    fun sentAnswers(): List<ClientMessage> = connection.sentOfType("submit_answer")

    fun sentRatings(): List<ClientMessage> = connection.sentOfType("rate_card")

    fun sentStarts(): List<ClientMessage> = connection.sentOfType("start_session")

    /** Utterances that started but never produced a terminal result — a speech leak detector. */
    fun unfinishedSpeech(): Int = speech.started.size - speech.spoken.size

    // ------------------------------------------------------------------ invariants (§25)

    /**
     * Every cross-system invariant that must hold in *any* state.
     *
     * These are the rules that make the difference between "a card advanced" and "the user's study
     * data is wrong": half-duplex voice, exactly-once submission, a card the UI and the machine
     * agree on, and bounded state. They are checked from the outside on purpose — an invariant the
     * machine enforces internally cannot catch the machine getting it wrong.
     */
    fun checkInvariants(context: String = ""): List<String> {
        val state = machineState.value
        val found = mutableListOf<String>()

        // 1. Half duplex: the microphone may never be open while the app speaks, and vice versa.
        if (overlapDetected || (speech.isActivelySpeaking && recognition.isListening.value)) {
            found += "TTS and STT overlapped: a microphone turn is open while the app is speaking"
        }

        // 2. A finished session cannot come back to life inside the same epoch.
        val finishedAt = finishedEpoch
        if (finishedAt != null && finishedAt == state.epoch && state.phase.isActive) {
            found += "finished session returned to an active phase (${state.phase}) without a new epoch"
        }

        // 3. The card the UI renders must be the card the machine owns.
        val derivedCardId = studyState.value.currentCardOrNull?.id
        val machineCardId = state.cardTurn?.cardId ?: state.session?.currentCard?.id
        if (derivedCardId != null && machineCardId != null && derivedCardId != machineCardId) {
            found += "StudyState card $derivedCardId != machine card $machineCardId"
        }

        // 4. A turn's generation may never exceed the session's card generation.
        val turn = state.cardTurn
        if (turn != null && turn.generation > state.cardGeneration) {
            found += "turn generation ${turn.generation} ahead of card generation ${state.cardGeneration}"
        }

        // 5. At most one submission of each kind in flight.
        val inFlightAnswers = state.ledger.entries.values.count {
            it.answerState == SubmissionLedger.SubmissionState.IN_FLIGHT
        }
        val inFlightRatings = state.ledger.entries.values.count {
            it.ratingState == SubmissionLedger.SubmissionState.IN_FLIGHT
        }
        if (inFlightAnswers > 1) found += "$inFlightAnswers answer submissions in flight"
        if (inFlightRatings > 1) found += "$inFlightRatings rating submissions in flight"

        // 6. A paused session must not be listening.
        if (state.phase.isPaused && recognition.hasActiveTurn) {
            found += "paused session still holds an open microphone turn"
        }

        // 7. Bounded structures stay bounded (§19).
        val resources = resources()
        if (resources.ledgerEntries > 32) found += "ledger grew past its bound (${resources.ledgerEntries})"
        if (resources.recentServerMessageIds > 200) found += "dedup window grew past its bound"
        if (resources.transitionHistory > 200) found += "transition history grew past its bound"
        if (resources.cardTurnHistory > StudyReducer.CARD_TURN_HISTORY_LIMIT) {
            found += "card turn history grew past its bound"
        }

        // 8. The machine's own invariant counter is part of the contract.
        if (machineInvariantViolations() > 0L) {
            found += "machine reported ${machineInvariantViolations()} internal violation(s)"
        }

        if (found.isNotEmpty()) {
            violations += found.map { if (context.isEmpty()) it else "$context: $it" }
            while (violations.size > MAX_RECORDED_VIOLATIONS) violations.removeAt(0)
        }
        return found
    }

    fun assertInvariants(context: String = "", seed: Long? = null) {
        val found = checkInvariants(context)
        if (found.isNotEmpty()) throw AssertionError(report(found, seed))
    }

    // ------------------------------------------------------------------ failure output (§172)

    /** A report that can be pasted into an issue without asking the author anything. */
    fun report(problems: List<String> = emptyList(), seed: Long? = null): String {
        val state = machineState.value
        val sb = StringBuilder(2_048)
        sb.append("=== StudyAgent harness report ===\n")
        seed?.let { sb.append("seed: ").append(it).append('\n') }
        sb.append("epoch: ").append(state.epoch).append('\n')
        sb.append("phase: ").append(SessionPhase.serverPhaseName(state.phase)).append('\n')
        sb.append("card: ").append(state.currentCardId ?: "-")
            .append(" gen=").append(state.cardGeneration).append('\n')
        sb.append("cardTurn: ").append(state.cardTurn?.turnId ?: "-")
            .append(" serverTurn=").append(state.cardTurn?.serverTurnId ?: "-").append('\n')
        sb.append("pendingAction: ").append(
            state.pendingAction?.let { "${it.type}:${it.messageId.take(8)}" } ?: "-"
        ).append('\n')
        sb.append("connection: ").append(connection.currentStateLabel()).append('\n')
        sb.append("speech: speaking=").append(speech.isActivelySpeaking)
            .append(" started=").append(speech.started.size)
            .append(" finished=").append(speech.spoken.size)
            .append(" parked=").append(speech.pendingCount).append('\n')
        sb.append("stt: listening=").append(recognition.isListening.value)
            .append(" active=").append(recognition.hasActiveTurn)
            .append(" started=").append(recognition.started.size)
            .append(" cancelled=").append(recognition.cancelledReasons.size).append('\n')
        sb.append("server: cards=").append(server.cardIndex)
            .append(" answers=").append(server.answersReceived)
            .append(" ratings=").append(server.ratingsReceived)
            .append(" dropped=").append(server.droppedReplies).append('\n')
        sb.append("sent: starts=").append(sentStarts().size)
            .append(" answers=").append(sentAnswers().size)
            .append(" ratings=").append(sentRatings().size).append('\n')
        sb.append("resources: ").append(resources().render()).append('\n')
        if (violations.isNotEmpty()) {
            sb.append("violations:\n")
            violations.take(MAX_PRINTED_VIOLATIONS).forEach { sb.append("  - ").append(it).append('\n') }
        }
        if (problems.isNotEmpty()) {
            sb.append("problems:\n")
            problems.forEach { sb.append("  - ").append(it).append('\n') }
        }
        sb.append("last ").append(REPORT_EVENT_COUNT).append(" timeline events:\n")
        timeline.recent(REPORT_EVENT_COUNT).forEach { sb.append("  ").append(it.render()).append('\n') }
        sb.append("last ").append(REPORT_TRANSITION_COUNT).append(" transitions:\n")
        state.transitionHistory.toList().takeLast(REPORT_TRANSITION_COUNT).forEach { record ->
            sb.append("  epoch=").append(record.epoch)
                .append(' ').append(SessionPhase.serverPhaseName(record.from))
                .append(" --").append(record.event).append("--> ")
                .append(SessionPhase.serverPhaseName(record.to))
                .append(record.reason?.let { reason -> " ($reason)" } ?: "")
                .append('\n')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ internals

    private var finishedEpoch: Long? = null

    /**
     * Passive observation run after every step: records terminal epochs and the two facts that
     * are only observable *while* they happen (an overlap between speech and microphone).
     */
    private fun observe() {
        val state = machineState.value
        if (state.phase is SessionPhase.Finished) finishedEpoch = state.epoch
        if (speech.isActivelySpeaking && recognition.isListening.value) {
            noteOverlap("TTS/STT overlap observed during advance")
        }
        checkInvariants()
    }

    private fun recordOnce(message: String) {
        if (violations.contains(message)) return
        violations += message
    }

    /** Called by the fake voice components the moment either side opens. */
    internal fun noteOverlap(message: String) {
        overlapDetected = true
        recordOnce(message)
    }

    companion object {
        /** Virtual step used by the await helpers: smaller than any watchdog, larger than a gap. */
        const val STEP_MS = 200L

        /** Comfortably past speech completion, the acoustic gap and the recognizer start. */
        const val SETTLE_MS = 1_200L

        private const val REPORT_EVENT_COUNT = 24
        private const val REPORT_TRANSITION_COUNT = 20
        private const val MAX_RECORDED_VIOLATIONS = 25
        private const val MAX_PRINTED_VIOLATIONS = 10
    }
}

/**
 * Builds a harness on the test scheduler.
 *
 * A factory rather than a constructor so the wiring is explicit and a reader can see exactly which
 * production objects are involved (§14).
 */
fun TestScope.newHarness(
    route: AudioRouteSnapshot = TestRoutes.phone,
    settings: AppSettings = TestSettings.studyDefaults(),
    disconnectPolicy: StudyAudioDisconnectPolicy = StudyAudioDisconnectPolicy.PAUSE_VOICE,
    /** Virtual length of one utterance; 0 makes speech instantaneous. */
    speakDurationMs: Long = 400L,
    /**
     * Virtual delay before a hands-free answer. Non-zero so the reply is delivered from the
     * scheduler rather than nested inside the effect that opened the microphone — a real
     * recognizer never answers on the same stack frame either.
     */
    autoAnswerDelayMs: Long = 1L,
    /** When true the fake recognizer answers every microphone window by itself. */
    autoAnswer: Boolean = false,
    /** Timeline capacity for this harness; tests about bounding pass a small one. */
    timelineCapacity: Int = DiagnosticTimeline.DEFAULT_CAPACITY,
    /** False makes the scripted server wait for explicit frames from the test. */
    serverAutoRespond: Boolean = true,
    /** Deck size the scripted server walks through. */
    serverDeckSize: Int = TestCards.ENDURANCE_DECK_SIZE
): StudySessionHarness {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val clock = TestClock()

    // Shared process state must not leak between tests (§150). Debug logging is silenced because a
    // thousand-card simulation would otherwise spend its time formatting rows nothing asserts on;
    // the suites that are *about* logging re-enable it explicitly (§80).
    AppLogger.clear()
    AppLogger.isDebugEnabled = false
    NetworkStatsRegistry.resetForTests()
    EffectIds.resetForTests()

    val timeline = DiagnosticTimeline(capacity = timelineCapacity, clock = clock.asProvider())
    val performance = PerformanceMetrics(clock = clock)

    val connection = FakeConnectionRepository()
    val speech = FakeSpeechOrchestrator(clock = clock.asProvider(), speakDurationMs = speakDurationMs)
    val recognition = FakeRecognitionOrchestrator(
        scope = scope,
        clock = clock.asProvider(),
        autoRespondDelayMs = autoAnswerDelayMs
    )

    val routeManager = FakeAudioRouteManager(route)
    val routeSnapshots = MutableStateFlow(route)
    val coordinator = StudyAudioRouteCoordinator(
        snapshots = routeSnapshots,
        preferences = MutableStateFlow(StudyAudioPreferences(disconnectPolicy = disconnectPolicy)),
        scope = scope,
        initialSnapshot = route
    )

    val repository = StudySessionMachineRepository(
        connectionRepository = connection,
        speechOrchestrator = speech,
        recognitionOrchestrator = recognition,
        settingsFlow = MutableStateFlow(settings),
        audioRouteManager = routeManager,
        dispatchers = TestDispatcherProvider(dispatcher),
        scope = scope,
        clock = clock.asProvider(),
        audioRouteCoordinator = coordinator,
        performance = performance,
        timeline = timeline
    )

    val server = FakeStudyServer(
        scope = scope,
        deckName = "Toronto Notes",
        totalCards = serverDeckSize,
        autoRespond = serverAutoRespond
    )
    server.onReply = { message -> connection.deliver(message) }
    connection.onSendHook = { message -> server.onClientMessage(message) }

    val harness = StudySessionHarness(
        testScheduler = testScheduler,
        scope = scope,
        settings = settings,
        clock = clock,
        connection = connection,
        speech = speech,
        recognition = recognition,
        routeManager = routeManager,
        routeSnapshots = routeSnapshots,
        coordinator = coordinator,
        performance = performance,
        timeline = timeline,
        repository = repository,
        server = server,
        autoAnswerEnabled = autoAnswer
    )

    if (autoAnswer) {
        // The fake recognizer answers whatever the machine asks: the *card* comes from the
        // request the machine built, so a stale turn can never be answered as a newer one.
        recognition.autoRespondWith = { request ->
            when (request.purpose) {
                RecognitionPurpose.ANSWER,
                RecognitionPurpose.PUSH_TO_TALK_ANSWER -> RecognitionTurnResult.Completed(
                    TestTranscripts.outcome(
                        requestId = request.id,
                        purpose = request.purpose,
                        cardId = request.cardId,
                        text = harness.answerText
                    )
                )

                else -> null
            }
        }
    }

    // Half duplex is asserted from the executed order, not from intent.
    speech.onSpeakStart = {
        if (recognition.isListening.value) harness.noteOverlap("microphone open while speech started")
    }
    recognition.onStarted = {
        if (speech.isActivelySpeaking) harness.noteOverlap("speech active while microphone opened")
    }

    testScheduler.runCurrent()
    return harness
}

/** Dispatcher provider backed by the test dispatcher: no real threads anywhere. */
class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val unconfined: CoroutineDispatcher get() = dispatcher
}
