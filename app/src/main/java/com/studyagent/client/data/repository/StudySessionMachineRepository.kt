package com.studyagent.client.data.repository

import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudySession
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.study.*
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Machine-backed StudySessionRepository.
 * Delegates all state to [StudySessionMachine] and dispatches user intents as [StudyEvent]s.
 * This satisfies §61-§62: UI/voice/notification all converge to dispatch.
 */
class StudySessionMachineRepository(
    connectionRepository: ConnectionRepository,
    speechOrchestrator: SpeechOrchestrator,
    recognitionOrchestrator: SpeechRecognitionOrchestrator,
    settingsFlow: Flow<AppSettings>,
    audioRouteManager: AudioRouteManager,
    dispatchers: DispatcherProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
    clock: () -> Long = System::currentTimeMillis,
    /**
     * Study audio routing policy (§70/§112). Optional so the machine stays constructible in
     * headless tests; without it the app behaves like a bare phone in Auto mode.
     */
    private val audioRouteCoordinator: StudyAudioRouteCoordinator? = null
) : StudySessionRepository {

    private val machine = StudySessionMachine(
        connectionRepository = connectionRepository,
        speechOrchestrator = speechOrchestrator,
        recognitionOrchestrator = recognitionOrchestrator,
        settingsFlow = settingsFlow,
        scope = scope,
        clock = clock,
        audioRouteCoordinator = audioRouteCoordinator
    )

    override val studyState: StateFlow<StudyState> = machine.studyState
    override val currentSession: StateFlow<StudySession?> = machine.currentSession
    override val lastRecognizedCommand: Flow<VoiceCommand> = machine.lastRecognizedCommand

    override suspend fun startStudy(deckName: String?) {
        startOrBlock(deckName)
    }

    /**
     * Start study, unless the user's audio preference forbids a headset-less start (§7/§82).
     * Only `HEADSET_REQUIRED` can block; Auto and Phone always start, on the phone when there
     * are no headphones.
     */
    private fun startOrBlock(deckName: String?) {
        val route = audioRouteCoordinator?.effectiveRoute?.value
        if (route != null && !route.canStartVoiceStudy) {
            val reason = route.reason
                ?: "No usable audio output on this device. Check the audio route in Settings."
            audioRouteCoordinator?.metrics?.recordBlockedStart()
            machine.dispatch(StudyEvent.VoiceRouteBlocked(reason))
            return
        }
        machine.dispatch(StudyEvent.UserStartRequested(deckName ?: "Toronto Notes", UUID.randomUUID().toString()))
    }

    override suspend fun submitSpokenAnswer(cardId: String, transcript: String) {
        machine.dispatch(StudyEvent.UserSubmitAnswer(cardId, transcript))
    }

    override suspend fun rateCurrentCard(rating: Rating) {
        val cardId = machine.machineState.value.cardTurn?.cardId ?: machine.machineState.value.session?.currentCard?.id ?: return
        machine.dispatch(StudyEvent.UserRateCard(rating, cardId))
    }

    override suspend fun requestRepeat() {
        val cardId = machine.machineState.value.currentCardId
        machine.dispatch(StudyEvent.UserRequestRepeat(cardId))
    }

    override suspend fun requestHint() {
        val cardId = machine.machineState.value.currentCardId
        machine.dispatch(StudyEvent.UserRequestHint(cardId))
    }

    override suspend fun requestExplanation() {
        val cardId = machine.machineState.value.currentCardId
        machine.dispatch(StudyEvent.UserRequestExplanation(cardId))
    }

    override suspend fun requestAnswer() {
        val cardId = machine.machineState.value.currentCardId
        machine.dispatch(StudyEvent.UserRequestAnswer(cardId))
    }

    override suspend fun skipCard() {
        val cardId = machine.machineState.value.currentCardId
        machine.dispatch(StudyEvent.UserSkipRequested(cardId))
    }

    override suspend fun pauseStudy() {
        machine.dispatch(StudyEvent.UserPauseRequested(UUID.randomUUID().toString()))
    }

    override suspend fun resumeStudy() {
        machine.dispatch(StudyEvent.UserResumeRequested(UUID.randomUUID().toString()))
    }

    override suspend fun endStudy() {
        machine.dispatch(StudyEvent.UserEndRequested(UUID.randomUUID().toString()))
        // A "continue on phone" override is session-scoped; the next session resolves freshly
        // (§38/§41).
        audioRouteCoordinator?.resetSessionOverrides()
    }

    override fun continueOnPhone() {
        val coordinator = audioRouteCoordinator ?: return
        coordinator.continueOnPhone()
        // Resuming repeats the current question on the newly resolved phone route; the resume
        // path never continues mid-sentence (§38/§96/§118).
        val phase = machine.machineState.value.phase
        when {
            phase is SessionPhase.Paused -> resumeNow()
            phase is SessionPhase.Pausing -> scope.launch {
                // The pause request is still in flight (the loss just happened). Resume as soon
                // as the server confirms it instead of dropping the user's choice.
                withTimeoutOrNull(RESUME_AFTER_PAUSE_TIMEOUT_MS) {
                    machine.machineState.first { it.phase is SessionPhase.Paused }
                }
                resumeNow()
            }

            else -> Unit
        }
    }

    private fun resumeNow() {
        machine.dispatch(StudyEvent.UserResumeRequested(UUID.randomUUID().toString()))
    }

    override fun useHeadsetNow(): Boolean {
        val coordinator = audioRouteCoordinator ?: return false
        // The coordinator applies the switch immediately and emits a route change; the machine
        // repeats the current question from the start on the new route (§42).
        return coordinator.useHeadsetNow()
    }

    override fun dismissAudioRouteAttention() {
        audioRouteCoordinator?.dismissAttention()
    }

    override fun requestStopSpeaking() {
        machine.dispatch(StudyEvent.UserStopSpeaking)
    }

    override fun startManualPushToTalk() {
        val st = machine.machineState.value
        val cardId = st.currentCardId
        val turnId = st.cardTurn?.turnId
        machine.dispatch(StudyEvent.PttStarted(cardId, turnId))
        // PTT start also triggers STT via effect; for now we also dispatch a hint to start recognition if needed
        // The reducer will handle PTT via voice effects; this is placeholder.
    }

    override fun stopManualPushToTalk() {
        val st = machine.machineState.value
        machine.dispatch(StudyEvent.PttStopped(st.currentCardId, st.cardTurn?.turnId))
    }

    override fun submitPendingTranscript() {
        val pending = machine.machineState.value.pendingTranscript ?: return
        machine.dispatch(StudyEvent.UserSubmitPendingTranscript(pending.cardId, pending.text))
    }

    override fun discardPendingTranscript() {
        val pending = machine.machineState.value.pendingTranscript ?: return
        machine.dispatch(StudyEvent.UserDiscardPendingTranscript(pending.cardId))
    }

    override fun processVoiceCommandDirectly(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Again -> { val id = machine.machineState.value.currentCardId ?: return; machine.dispatch(StudyEvent.UserRateCard(Rating.AGAIN, id)) }
            is VoiceCommand.Hard -> { val id = machine.machineState.value.currentCardId ?: return; machine.dispatch(StudyEvent.UserRateCard(Rating.HARD, id)) }
            is VoiceCommand.Good -> { val id = machine.machineState.value.currentCardId ?: return; machine.dispatch(StudyEvent.UserRateCard(Rating.GOOD, id)) }
            is VoiceCommand.Easy -> { val id = machine.machineState.value.currentCardId ?: return; machine.dispatch(StudyEvent.UserRateCard(Rating.EASY, id)) }
            is VoiceCommand.Repeat -> machine.dispatch(StudyEvent.UserRequestRepeat(machine.machineState.value.currentCardId))
            is VoiceCommand.Hint -> machine.dispatch(StudyEvent.UserRequestHint(machine.machineState.value.currentCardId))
            is VoiceCommand.Explain -> machine.dispatch(StudyEvent.UserRequestExplanation(machine.machineState.value.currentCardId))
            is VoiceCommand.ShowAnswer -> machine.dispatch(StudyEvent.UserRequestAnswer(machine.machineState.value.currentCardId))
            is VoiceCommand.Skip -> machine.dispatch(StudyEvent.UserSkipRequested(machine.machineState.value.currentCardId))
            is VoiceCommand.Pause -> machine.dispatch(StudyEvent.UserPauseRequested(UUID.randomUUID().toString()))
            is VoiceCommand.Resume -> machine.dispatch(StudyEvent.UserResumeRequested(UUID.randomUUID().toString()))
            is VoiceCommand.StopSpeaking -> machine.dispatch(StudyEvent.UserStopSpeaking)
            is VoiceCommand.Stop, is VoiceCommand.EndSession -> machine.dispatch(StudyEvent.UserEndRequested(UUID.randomUUID().toString()))
            is VoiceCommand.StartStudy -> startOrBlock(command.deck)
            is VoiceCommand.StatusQuestion -> machine.dispatch(StudyEvent.ConnectionRestored("voice-status"))
            is VoiceCommand.SubmitAnswer -> {
                val cid = machine.machineState.value.currentCardId ?: return
                machine.dispatch(StudyEvent.UserSubmitAnswer(cid, command.answer))
            }
            is VoiceCommand.Unknown -> {
                val cid = machine.machineState.value.currentCardId ?: return
                // Heuristic: if in rating window, treat as rating?
                machine.dispatch(StudyEvent.UserSubmitAnswer(cid, command.rawText))
            }
        }
    }

    fun diagnostics(): SessionDiagnosticsSnapshot = machine.machineState.value.toDiagnostics()
    fun machineState(): StateFlow<SessionMachineState> = machine.machineState

    private companion object {
        /** How long *Continue on phone* waits for an in-flight pause to land (§96). */
        const val RESUME_AFTER_PAUSE_TIMEOUT_MS = 5_000L
    }
}
