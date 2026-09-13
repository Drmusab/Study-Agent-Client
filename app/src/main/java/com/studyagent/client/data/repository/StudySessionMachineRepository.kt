package com.studyagent.client.data.repository

import com.studyagent.client.core.audio.AudioRouteManager
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
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
    clock: () -> Long = System::currentTimeMillis
) : StudySessionRepository {

    private val machine = StudySessionMachine(
        connectionRepository = connectionRepository,
        speechOrchestrator = speechOrchestrator,
        recognitionOrchestrator = recognitionOrchestrator,
        settingsFlow = settingsFlow,
        scope = scope,
        clock = clock
    )

    override val studyState: StateFlow<StudyState> = machine.studyState
    override val currentSession: StateFlow<StudySession?> = machine.currentSession
    override val lastRecognizedCommand: Flow<VoiceCommand> = machine.lastRecognizedCommand

    override suspend fun startStudy(deckName: String?) {
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
            is VoiceCommand.StartStudy -> machine.dispatch(StudyEvent.UserStartRequested(command.deck, UUID.randomUUID().toString()))
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
}
