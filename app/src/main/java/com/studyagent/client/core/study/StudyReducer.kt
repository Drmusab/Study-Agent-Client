package com.studyagent.client.core.study

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.tts.QueuePolicy
import com.studyagent.client.core.voice.tts.SpeechIds
import com.studyagent.client.core.voice.tts.SpeechPriority
import com.studyagent.client.core.voice.tts.SpeechPurpose
import com.studyagent.client.core.voice.tts.SpeechRequest
import java.util.UUID

/**
 * Pure reducer (§7): decides legality, next state, and effects.
 * No network, TTS, STT, delay, or logging directly.
 */
object StudyReducer {

    fun reduce(state: SessionMachineState, event: StudyEvent, clockMs: Long = System.currentTimeMillis()): Transition {
        // Terminal guard §83
        if (state.phase is SessionPhase.Finished && event !is StudyEvent.UserStartRequested) {
            return Transition.reject(state, event, "terminal-finished")
        }

        // Duplicate server message guard §21 §22
        val serverMsgId = when (event) {
            is StudyEvent.ServerQuestionReceived -> event.messageId
            is StudyEvent.ServerEvaluationReceived -> event.messageId
            is StudyEvent.ServerHintReceived -> event.messageId
            is StudyEvent.ServerExplanationReceived -> event.messageId
            is StudyEvent.ServerAnswerReceived -> event.messageId
            is StudyEvent.ServerRatingSaved -> event.messageId
            is StudyEvent.ServerSessionStarted -> event.messageId
            is StudyEvent.ServerSessionFinished -> event.messageId
            is StudyEvent.ServerSessionPaused -> event.messageId
            is StudyEvent.ServerSessionResumed -> event.messageId
            else -> null
        }
        if (state.isDuplicateServerMessage(serverMsgId)) {
            return Transition.reject(state, event, "duplicate-messageId")
        }

        // Epoch guard §14
        // All events that carry implicit epoch must not mutate if epoch mismatched; for client
        // user intents we trust epoch of state itself, but timeouts validate pendingAction epoch
        if (event is StudyEvent.ActionTimedOut) {
            val pending = state.pendingAction
            if (pending == null || pending.messageId != event.messageId) {
                return Transition.reject(state, event, "stale-timeout")
            }
            if (pending.sessionEpoch != state.epoch) {
                return Transition.reject(state, event, "stale-epoch")
            }
            return handleTimeout(state, event, clockMs)
        }

        // Voice effect ownership guard §64 §79
        if (event is StudyEvent.QuestionSpeechCompleted || event is StudyEvent.FeedbackSpeechCompleted ||
            event is StudyEvent.HintSpeechCompleted || event is StudyEvent.ExplanationSpeechCompleted) {
            val isStale = state.activeSpeechEffectId != (event as? StudyEvent.QuestionSpeechCompleted)?.effectId &&
                state.activeSpeechEffectId != (event as? StudyEvent.FeedbackSpeechCompleted)?.effectId &&
                state.activeSpeechEffectId != (event as? StudyEvent.HintSpeechCompleted)?.effectId &&
                state.activeSpeechEffectId != (event as? StudyEvent.ExplanationSpeechCompleted)?.effectId
            // If effectId does not match current, it's stale (§15)
            if (state.activeSpeechEffectId != null && isStale) {
                return Transition.reject(state, event, "stale-effect")
            }
        }

        return when (event) {
            is StudyEvent.UserStartRequested -> handleStart(state, event, clockMs)
            is StudyEvent.ServerSessionStarted -> handleSessionStarted(state, event, clockMs)
            is StudyEvent.ServerQuestionReceived -> handleQuestion(state, event, clockMs)
            is StudyEvent.QuestionSpeechCompleted -> handleQuestionSpeechCompleted(state, event, clockMs)
            is StudyEvent.UserSubmitAnswer -> handleSubmitAnswer(state, event, clockMs)
            is StudyEvent.UserSubmitPendingTranscript -> handleSubmitPending(state, event, clockMs)
            is StudyEvent.UserDiscardPendingTranscript -> handleDiscardPending(state, event)
            is StudyEvent.ServerEvaluationReceived -> handleEvaluation(state, event, clockMs)
            is StudyEvent.FeedbackSpeechCompleted -> handleFeedbackCompleted(state, event, clockMs)
            is StudyEvent.UserRateCard -> handleRateCard(state, event, clockMs)
            is StudyEvent.ServerRatingSaved -> handleRatingSaved(state, event, clockMs)
            is StudyEvent.UserRequestHint -> handleHintRequest(state, event, clockMs)
            is StudyEvent.ServerHintReceived -> handleHintReceived(state, event, clockMs)
            is StudyEvent.HintSpeechCompleted -> handleHintCompleted(state, event)
            is StudyEvent.UserRequestExplanation -> handleExplanationRequest(state, event, clockMs)
            is StudyEvent.ServerExplanationReceived -> handleExplanationReceived(state, event, clockMs)
            is StudyEvent.ExplanationSpeechCompleted -> handleExplanationCompleted(state, event)
            is StudyEvent.UserRequestAnswer -> handleAnswerRequest(state, event, clockMs)
            is StudyEvent.ServerAnswerReceived -> handleAnswerReceived(state, event)
            is StudyEvent.UserRequestRepeat -> handleRepeat(state, event, clockMs)
            is StudyEvent.UserSkipRequested -> handleSkip(state, event, clockMs)
            is StudyEvent.UserPauseRequested -> handlePauseRequested(state, event, clockMs)
            is StudyEvent.ServerSessionPaused -> handleServerPaused(state, event)
            is StudyEvent.UserResumeRequested -> handleResumeRequested(state, event, clockMs)
            is StudyEvent.ServerSessionResumed -> handleServerResumed(state, event, clockMs)
            is StudyEvent.UserEndRequested -> handleEndRequested(state, event, clockMs)
            is StudyEvent.ServerSessionFinished -> handleSessionFinished(state, event)
            is StudyEvent.ConnectionLost -> handleConnectionLost(state, event, clockMs)
            is StudyEvent.ConnectionRestored -> handleConnectionRestored(state, event)
            is StudyEvent.SessionStatusReceived -> handleStatusReceived(state, event, clockMs)
            is StudyEvent.ServerSnapshotReceived -> handleSnapshot(state, event, clockMs)
            is StudyEvent.AudioRouteLost -> handleAudioLost(state, event)
            is StudyEvent.AudioRouteRestored -> handleAudioRestored(state, event)
            is StudyEvent.UserStopSpeaking -> handleStopSpeaking(state)
            is StudyEvent.RecognitionCompleted -> handleRecognitionCompleted(state, event)
            is StudyEvent.RecognitionFailed -> handleRecognitionFailed(state, event)
            is StudyEvent.PttStarted -> handlePttStarted(state, event)
            is StudyEvent.PttStopped -> handlePttStopped(state, event)
            is StudyEvent.VoiceRouteBlocked -> handleVoiceRouteBlocked(state, event)
            is StudyEvent.SettingsChanged -> Transition(state, emptyList())
            is StudyEvent.ProtocolError -> Transition.reject(state, event, "protocol-error")
            is StudyEvent.UiRecreated -> Transition(state, emptyList())
            is StudyEvent.ServerError -> Transition.reject(state, event, "server-error:${event.code}")
            is StudyEvent.ServerSessionStats -> Transition(state, emptyList())
            is StudyEvent.VoiceFailure -> handleRecognitionFailed(state, StudyEvent.RecognitionFailed(null, event.reason))
            is StudyEvent.SpeechCancelled -> Transition(state.copy(activeSpeechEffectId = null), emptyList())
            is StudyEvent.RecoverPersistedSession -> Transition.reject(state, event, "not-supported")
            is StudyEvent.ActionTimedOut -> handleTimeout(state, event, clockMs)
            else -> Transition.reject(state, event, "unhandled-event")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun newPending(
        type: PendingAction.ActionType,
        state: SessionMachineState,
        msgId: String,
        cardTurnId: String?,
        cardId: String?,
        clockMs: Long,
        expectedRating: Rating? = null
    ): PendingAction = PendingAction(
        messageId = msgId,
        type = type,
        sessionEpoch = state.epoch,
        cardTurnId = cardTurnId,
        cardId = cardId,
        createdAtMs = clockMs,
        timeoutMs = PendingAction.timeoutFor(type),
        expectedRating = expectedRating
    )

    /**
     * The canonical question utterance. Shared with [SessionReconciler], which re-issues it after
     * a reconnect so the client never claims to be "speaking" a question it is not speaking.
     */
    internal fun speechRequestForQuestion(card: StudyCard): SpeechRequest =
        SpeechRequest(
            id = SpeechIds.forPurpose(SpeechPurpose.QUESTION, card.id),
            text = card.question,
            purpose = SpeechPurpose.QUESTION,
            priority = SpeechPriority.NORMAL,
            queuePolicy = QueuePolicy.REPLACE
        )

    private fun speechRequestForFeedback(cardId: String, text: String): SpeechRequest =
        SpeechRequest(
            id = SpeechIds.forPurpose(SpeechPurpose.FEEDBACK, cardId),
            text = text,
            purpose = SpeechPurpose.FEEDBACK,
            priority = SpeechPriority.NORMAL,
            queuePolicy = QueuePolicy.REPLACE
        )

    // ------------------------------------------------------------------ START

    private fun handleStart(state: SessionMachineState, event: StudyEvent.UserStartRequested, clockMs: Long): Transition {
        if (state.phase !is SessionPhase.Idle && state.phase !is SessionPhase.Finished && state.phase !is SessionPhase.Error) {
            return Transition.reject(state, event, "already-active")
        }
        // Idempotent duplicate start (§86)
        if (state.phase is SessionPhase.Starting) {
            return Transition.reject(state, event, "already-starting")
        }
        val newEpoch = if (state.phase is SessionPhase.Finished) state.epoch + 1 else if (state.isIdle) state.epoch else state.epoch + 1
        val msgId = event.messageId.ifBlank { UUID.randomUUID().toString() }
        val pending = newPending(PendingAction.ActionType.START_SESSION, state.copy(epoch = newEpoch), msgId, null, null, clockMs)
        // Mode + config come from the Study Control Center (§75). v1 servers ignore
        // the structured config field, preserving backward compatibility (§76).
        val send = StudyEffect.Network.Send(
            msgId,
            ClientMessage.StartSession(messageId = msgId, deck = event.deck, mode = event.mode, config = event.config)
        )
        val newState = state.copy(
            epoch = newEpoch,
            phase = SessionPhase.Starting,
            session = null,
            cardTurn = null,
            pendingAction = pending,
            error = null,
            ledger = SubmissionLedger(),
            recentServerMessageIds = linkedSetOf(),
            cardGeneration = 0L,
            pendingRatingConfirmation = null,
            pendingTranscript = null
        ).recordTransition(event, state.phase, SessionPhase.Starting)
            .rememberServerMessageId(msgId)
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Starting, null, newEpoch)))
    }

    private fun handleSessionStarted(state: SessionMachineState, event: StudyEvent.ServerSessionStarted, clockMs: Long): Transition {
        if (state.phase !is SessionPhase.Starting && state.phase !is SessionPhase.WaitingForFirstCard) {
            // Could be duplicate or reconciliation; if already have session with same id, treat as duplicate
            if (state.session?.sessionId == event.sessionId) return Transition.reject(state, event, "duplicate-session-started")
            // If Idle but server sends started without StartRequested (e.g., after reconnect) allow
        }
        // Validate pending action
        if (state.pendingAction?.type == PendingAction.ActionType.START_SESSION && state.pendingAction.messageId != event.messageId) {
            // Not same messageId, but session started is authoritative - accept
        }
        val snapshot = StudySessionSnapshot(
            sessionId = event.sessionId,
            deckName = event.deck ?: "Study Session",
            remainingCards = event.totalCards ?: 0,
            totalCardsInQueue = event.totalCards,
            startedAtEpochMs = clockMs
        )
        val newState = state.copy(
            phase = SessionPhase.WaitingForFirstCard,
            session = snapshot,
            pendingAction = null,
            error = null
        ).recordTransition(event, state.phase, SessionPhase.WaitingForFirstCard)
            .rememberServerMessageId(event.messageId)
        return Transition(newState, listOf(StudyEffect.CancelTimeout(state.pendingAction?.messageId ?: ""), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.WaitingForFirstCard, null, state.epoch)))
    }

    // ------------------------------------------------------------------ QUESTION

    private fun handleQuestion(state: SessionMachineState, event: StudyEvent.ServerQuestionReceived, clockMs: Long): Transition {
        // SessionId validation §18
        if (event.sessionId != null && state.session?.sessionId != null && event.sessionId != state.session?.sessionId) {
            return Transition.reject(state, event, "stale-session")
        }
        // Duplicate detection §20: exact same turn already speaking/listening
        val cur = state.cardTurn
        val isExactDuplicate = cur != null && cur.cardId == event.cardId && cur.card.question == event.question &&
                (state.phase is SessionPhase.SpeakingQuestion || state.phase is SessionPhase.WaitingForAnswer || state.phase is SessionPhase.PendingAnswerReview)
        if (isExactDuplicate && state.isDuplicateServerMessage(event.messageId)) {
            return Transition.reject(state, event, "duplicate-question")
        }
        if (isExactDuplicate) {
            // Suppress side effects but not error
            return Transition.reject(state, event, "duplicate-question-same-turn")
        }
        // Revision guard §96
        if (event.serverRevision != null && state.cardTurn?.serverRevision != null && event.serverRevision < state.cardTurn.serverRevision) {
            return Transition.reject(state, event, "stale-revision")
        }

        val card = StudyCard(event.cardId, event.question, event.cardNumber, event.remaining, state.session?.deckName)
        val newGen = state.cardGeneration + 1
        val turnId = CardTurn.generateTurnId(state.epoch, card.id, newGen, event.serverTurnId)
        val newTurn = CardTurn(newGen, card, turnId, event.serverTurnId, event.serverRevision)
        val newSession = state.session?.copy(
            currentCard = card,
            cardNumber = event.cardNumber ?: (state.session.cardNumber + 1),
            remainingCards = event.remaining ?: state.session.remainingCards
        )

        val effectId = EffectIds.next("question")
        val speakEffect = if (event.speak) StudyEffect.Voice.Speak(speechRequestForQuestion(card), effectId) else null

        val newPhase = if (event.speak) SessionPhase.SpeakingQuestion else SessionPhase.WaitingForAnswer

        // Preserve ledger but advance turn history; prune old.
        //
        // Both structures are explicitly bounded. The ledger only needs the turns that can still
        // receive a late callback, and the turn history only needs an identity window for
        // diagnostics — appending forever (the previous behaviour) meant a 1000-card session kept
        // 1000 turn ids alive for no reader (§21/§137).
        val keepIds = (state.cardTurnHistory.takeLast(20) + newTurn.turnId).toSet()
        val prunedLedger = state.ledger.pruneOld(keepIds)
        val boundedHistory = (state.cardTurnHistory + newTurn.turnId).takeLast(CARD_TURN_HISTORY_LIMIT)

        val newState = state.copy(
            phase = newPhase,
            cardTurn = newTurn,
            session = newSession,
            cardGeneration = newGen,
            cardTurnHistory = boundedHistory,
            pendingTranscript = null,
            pendingRatingConfirmation = null,
            activeSpeechEffectId = effectId,
            ledger = prunedLedger
        ).recordTransition(event, state.phase, newPhase)
            .rememberServerMessageId(event.messageId)

        val effects = mutableListOf<StudyEffect>()
        effects.add(StudyEffect.Voice.CancelRecognition("new-question"))
        if (speakEffect != null) effects.add(speakEffect) else {
            // If not speaking, directly start listening if handsfree; executor decides
            effects.add(StudyEffect.Voice.StartRecognition(RecognitionPurpose.ANSWER, card.id, EffectIds.next("stt")))
        }
        effects.add(StudyEffect.LogTransition(state.phase, event.debugName, newPhase, card.id, state.epoch))

        // Duplicate delivery guard: cancel old STT already handled

        return Transition(newState, effects)
    }

    private fun handleQuestionSpeechCompleted(state: SessionMachineState, event: StudyEvent.QuestionSpeechCompleted, clockMs: Long): Transition {
        if (state.cardTurn?.cardId != event.cardId) return Transition.reject(state, event, "stale-card")
        if (state.phase !is SessionPhase.SpeakingQuestion) return Transition.reject(state, event, "illegal-phase")
        // On speech completed, go to WaitingForAnswer
        val newState = state.copy(
            phase = SessionPhase.WaitingForAnswer,
            activeSpeechEffectId = null
        ).recordTransition(event, state.phase, SessionPhase.WaitingForAnswer)
        val effects = listOf(
            StudyEffect.Voice.StartRecognition(RecognitionPurpose.ANSWER, event.cardId, EffectIds.next("stt")),
            StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.WaitingForAnswer, event.cardId, state.epoch)
        )
        return Transition(newState, effects)
    }

    // ------------------------------------------------------------------ ANSWER

    private fun handleSubmitAnswer(state: SessionMachineState, event: StudyEvent.UserSubmitAnswer, clockMs: Long): Transition {
        // Validity §54
        if (state.phase !is SessionPhase.WaitingForAnswer && state.phase !is SessionPhase.PendingAnswerReview) {
            return Transition.reject(state, event, "illegal-phase-for-answer")
        }
        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-card-turn")
        if (turn.cardId != event.cardId) return Transition.reject(state, event, "card-mismatch")
        // Turn ownership §15 §16
        if (turn.generation != state.cardGeneration && state.cardTurn?.generation != turn.generation) {
            // generation mismatch already captured by cardId check but extra guard
        }
        // Ledger check §26 §27
        if (!state.ledger.canBeginAnswer(turn.turnId)) {
            return Transition.reject(state, event, "duplicate-answer")
        }
        val msgId = UUID.randomUUID().toString()
        val pending = newPending(PendingAction.ActionType.SUBMIT_ANSWER, state, msgId, turn.turnId, turn.cardId, clockMs)
        val ledger2 = state.ledger.tryBeginAnswer(turn.turnId, turn.cardId, msgId)
        val send = StudyEffect.Network.Send(
            msgId,
            ClientMessage.SubmitAnswer(
                sessionId = state.session?.sessionId,
                cardId = event.cardId,
                text = event.transcript,
                messageId = msgId,
                reviewTurnId = turn.turnId,
                sessionRevision = turn.serverRevision
            )
        )

        val newState = state.copy(
            phase = SessionPhase.SubmittingAnswer,
            ledger = ledger2,
            pendingAction = pending,
            pendingTranscript = null,
            cardTurn = turn.withPendingTranscript(null)
        ).recordTransition(event, state.phase, SessionPhase.SubmittingAnswer)

        // Next phase after transport is WaitingForEvaluation; but we go to SubmittingAnswer then
        // executor will on success remain until Evaluation arrives; on timeout we handle separately
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type), StudyEffect.Voice.CancelRecognition("answer-submitted"), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.SubmittingAnswer, event.cardId, state.epoch)))
    }

    private fun handleSubmitPending(state: SessionMachineState, event: StudyEvent.UserSubmitPendingTranscript, clockMs: Long): Transition {
        val pending = state.pendingTranscript ?: return Transition.reject(state, event, "no-pending")
        if (pending.cardId != event.cardId) return Transition.reject(state, event, "stale-pending")
        if (pending.epoch != state.epoch) return Transition.reject(state, event, "stale-epoch")
        // Server still awaits answer?
        if (state.phase !is SessionPhase.PendingAnswerReview) return Transition.reject(state, event, "illegal-phase-pending")
        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-card")
        return handleSubmitAnswer(state, StudyEvent.UserSubmitAnswer(event.cardId, pending.text), clockMs)
    }

    private fun handleDiscardPending(state: SessionMachineState, event: StudyEvent.UserDiscardPendingTranscript): Transition {
        if (state.phase !is SessionPhase.PendingAnswerReview) return Transition.reject(state, event, "illegal-phase")
        if (state.pendingTranscript?.cardId != event.cardId) return Transition.reject(state, event, "stale-card")
        val newState = state.copy(
            phase = SessionPhase.WaitingForAnswer,
            pendingTranscript = null,
            cardTurn = state.cardTurn?.withPendingTranscript(null)
        ).recordTransition(event, state.phase, SessionPhase.WaitingForAnswer)
        return Transition(newState, listOf(StudyEffect.Voice.StartRecognition(RecognitionPurpose.ANSWER, event.cardId, EffectIds.next("stt"))))
    }

    private fun handleEvaluation(state: SessionMachineState, event: StudyEvent.ServerEvaluationReceived, clockMs: Long): Transition {
        // Stale evaluation §19
        if (state.cardTurn?.cardId != event.cardId) {
            return Transition.reject(state, event, "stale-card")
        }
        // Duplicate evaluation §21
        if (state.phase is SessionPhase.SpeakingFeedback || state.phase is SessionPhase.WaitingForRating) {
            // If already showing same evaluation, treat as duplicate
            if (state.cardTurn?.evaluation != null && state.isDuplicateServerMessage(event.messageId)) {
                return Transition.reject(state, event, "duplicate-evaluation")
            }
            if (state.cardTurn?.evaluation?.shortFeedback == event.evaluation.shortFeedback) {
                return Transition.reject(state, event, "duplicate-evaluation-content")
            }
        }
        // Card turn generation check §15
        // SessionId validation
        if (event.sessionId != null && state.session?.sessionId != null && event.sessionId != state.session.sessionId) {
            return Transition.reject(state, event, "stale-session")
        }

        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-card-turn")
        val updatedTurn = turn.withEvaluation(event.evaluation)
        val newSession = state.session?.copy(lastEvaluation = event.evaluation)
        val ledger2 = state.ledger.markAnswerAck(turn.turnId)

        val effects = mutableListOf<StudyEffect>()
        val newPhase: SessionPhase
        val effectId: String?

        if (event.speak && event.evaluation.shortFeedback.isNotBlank()) {
            newPhase = SessionPhase.SpeakingFeedback
            effectId = EffectIds.next("feedback")
            effects.add(StudyEffect.Voice.Speak(speechRequestForFeedback(event.cardId, event.evaluation.shortFeedback), effectId))
        } else {
            newPhase = SessionPhase.WaitingForRating
            effectId = null
            effects.add(StudyEffect.Voice.StartRecognition(RecognitionPurpose.RATING, event.cardId, EffectIds.next("stt")))
        }

        val newState = state.copy(
            phase = newPhase,
            cardTurn = updatedTurn,
            session = newSession,
            ledger = ledger2,
            pendingAction = null, // Clear answer pending
            activeSpeechEffectId = effectId
        ).recordTransition(event, state.phase, newPhase)
            .rememberServerMessageId(event.messageId)

        effects.add(StudyEffect.CancelTimeout(state.pendingAction?.messageId ?: ""))
        effects.add(StudyEffect.LogTransition(state.phase, event.debugName, newPhase, event.cardId, state.epoch))

        return Transition(newState, effects)
    }

    private fun handleFeedbackCompleted(state: SessionMachineState, event: StudyEvent.FeedbackSpeechCompleted, clockMs: Long): Transition {
        if (state.cardTurn?.cardId != event.cardId) return Transition.reject(state, event, "stale-card")
        if (state.phase !is SessionPhase.SpeakingFeedback) return Transition.reject(state, event, "illegal-phase")
        val newState = state.copy(phase = SessionPhase.WaitingForRating, activeSpeechEffectId = null)
            .recordTransition(event, state.phase, SessionPhase.WaitingForRating)
        return Transition(newState, listOf(StudyEffect.Voice.StartRecognition(RecognitionPurpose.RATING, event.cardId, EffectIds.next("stt")), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.WaitingForRating, event.cardId, state.epoch)))
    }

    // ------------------------------------------------------------------ RATING

    private fun handleRateCard(state: SessionMachineState, event: StudyEvent.UserRateCard, clockMs: Long): Transition {
        if (state.phase !is SessionPhase.WaitingForRating && state.phase !is SessionPhase.SpeakingFeedback) {
            return Transition.reject(state, event, "illegal-phase-for-rating")
        }
        // If SpeakingFeedback, optionally allow early rating if product permits - we allow but log
        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-card-turn")
        if (turn.cardId != event.cardId) return Transition.reject(state, event, "card-mismatch")
        if (!state.ledger.canBeginRating(turn.turnId)) return Transition.reject(state, event, "duplicate-rating")
        // Confirmation leakage guard §78: pending confirmation must belong to turn
        if (state.pendingRatingConfirmation != null && state.pendingRatingConfirmation.turnId != turn.turnId) {
            // stale confirmation cleared elsewhere, but reject this rating if it depends on stale
        }

        val msgId = UUID.randomUUID().toString()
        val pending = newPending(
            PendingAction.ActionType.RATE_CARD,
            state,
            msgId,
            turn.turnId,
            turn.cardId,
            clockMs,
            expectedRating = event.rating
        )
        val ledger2 = state.ledger.tryBeginRating(turn.turnId, turn.cardId, msgId)
        val send = StudyEffect.Network.Send(
            msgId,
            ClientMessage.RateCard(
                sessionId = state.session?.sessionId,
                cardId = event.cardId,
                rating = event.rating,
                messageId = msgId,
                reviewTurnId = turn.turnId,
                sessionRevision = turn.serverRevision
            )
        )

        val newState = state.copy(
            phase = SessionPhase.SubmittingRating,
            ledger = ledger2,
            pendingAction = pending,
            pendingRatingConfirmation = null
        ).recordTransition(event, state.phase, SessionPhase.SubmittingRating)

        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type), StudyEffect.Voice.CancelRecognition("rating-submitted"), StudyEffect.Voice.CancelSpeech("rating-dismiss"), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.SubmittingRating, event.cardId, state.epoch)))
    }

    private fun handleRatingSaved(state: SessionMachineState, event: StudyEvent.ServerRatingSaved, clockMs: Long): Transition {
        // Stale rating ack §29
        if (state.cardTurn?.cardId != event.cardId) {
            return Transition.reject(state, event, "stale-card")
        }
        if (event.sessionId != null && state.session?.sessionId != null && event.sessionId != state.session.sessionId) {
            return Transition.reject(state, event, "stale-session")
        }
        // Duplicate
        if (state.isDuplicateServerMessage(event.messageId)) {
            return Transition.reject(state, event, "duplicate-messageId")
        }
        // Pending validation
        val pending = state.pendingAction
        if (pending?.type == PendingAction.ActionType.RATE_CARD && pending.cardId != event.cardId) {
            return Transition.reject(state, event, "stale-pending")
        }
        if (pending?.type == PendingAction.ActionType.RATE_CARD &&
            pending.expectedRating != null && pending.expectedRating != event.rating
        ) {
            return Transition.reject(state, event, "unexpected-rating-ack")
        }
        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-card")
        val ledger2 = state.ledger.markRatingAck(turn.turnId)
        val newState = state.copy(
            phase = SessionPhase.WaitingForFirstCard, // waiting for next card; next question will bring SpeakingQuestion
            ledger = ledger2,
            pendingAction = null
        ).recordTransition(event, state.phase, SessionPhase.WaitingForFirstCard)
            .rememberServerMessageId(event.messageId)

        // Rating counts
        val newSession = newState.session?.copy(totalReviewedInSession = newState.session.totalReviewedInSession + 1)
        val finalState = newState.copy(session = newSession)

        return Transition(finalState, listOf(StudyEffect.CancelTimeout(pending?.messageId ?: ""), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.WaitingForFirstCard, event.cardId, state.epoch)))
    }

    // ------------------------------------------------------------------ HINT etc.

    private fun handleHintRequest(state: SessionMachineState, event: StudyEvent.UserRequestHint, clockMs: Long): Transition {
        if (state.phase !is SessionPhase.WaitingForAnswer && state.phase !is SessionPhase.PendingAnswerReview) {
            return Transition.reject(state, event, "illegal-phase-for-hint")
        }
        val cardId = event.cardId ?: state.cardTurn?.cardId ?: return Transition.reject(state, event, "no-card")
        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-turn")
        val msgId = UUID.randomUUID().toString()
        val pending = newPending(PendingAction.ActionType.REQUEST_HINT, state, msgId, turn.turnId, cardId, clockMs)
        val send = StudyEffect.Network.Send(msgId, ClientMessage.RequestHint(sessionId = state.session?.sessionId, cardId = cardId, messageId = msgId))
        val newState = state.copy(pendingAction = pending).recordTransition(event, state.phase, state.phase) // phase stays WaitingForAnswer until hint received
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type), StudyEffect.Voice.CancelRecognition("hint-requested")))
    }

    private fun handleHintReceived(state: SessionMachineState, event: StudyEvent.ServerHintReceived, clockMs: Long): Transition {
        if (state.cardTurn?.cardId != event.cardId && event.cardId != null) {
            return Transition.reject(state, event, "stale-card")
        }
        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-turn")
        val updated = turn.incrementHint()
        val effectId = EffectIds.next("hint")
        val newState = state.copy(
            phase = SessionPhase.SpeakingHint,
            cardTurn = updated,
            activeSpeechEffectId = effectId,
            pendingAction = null
        ).recordTransition(event, state.phase, SessionPhase.SpeakingHint)
            .rememberServerMessageId(event.messageId)
        val speak = SpeechRequest(
            id = SpeechIds.forPurpose(SpeechPurpose.HINT, event.cardId ?: "hint"),
            text = event.hintText,
            purpose = SpeechPurpose.HINT,
            priority = SpeechPriority.NORMAL,
            queuePolicy = QueuePolicy.REPLACE
        )
        return Transition(newState, listOf(StudyEffect.Voice.Speak(speak, effectId), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.SpeakingHint, event.cardId, state.epoch)))
    }

    private fun handleHintCompleted(state: SessionMachineState, event: StudyEvent.HintSpeechCompleted): Transition {
        if (state.phase !is SessionPhase.SpeakingHint) return Transition.reject(state, event, "illegal-phase")
        // After hint, return to WaitingForAnswer
        val newState = state.copy(phase = SessionPhase.WaitingForAnswer, activeSpeechEffectId = null)
            .recordTransition(event, state.phase, SessionPhase.WaitingForAnswer)
        return Transition(newState, listOf(StudyEffect.Voice.StartRecognition(RecognitionPurpose.ANSWER, state.cardTurn?.cardId, EffectIds.next("stt")), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.WaitingForAnswer, state.cardTurn?.cardId, state.epoch)))
    }

    private fun handleExplanationRequest(state: SessionMachineState, event: StudyEvent.UserRequestExplanation, clockMs: Long): Transition {
        // §57: explain typically allowed after feedback or before rating; we allow from WaitingForRating, SpeakingFeedback, ShowingAnswer
        if (state.phase !is SessionPhase.WaitingForRating && state.phase !is SessionPhase.SpeakingFeedback && state.phase !is SessionPhase.ShowingAnswer && state.phase !is SessionPhase.SpeakingExplanation) {
            return Transition.reject(state, event, "illegal-phase-for-explanation")
        }
        val cardId = event.cardId ?: state.cardTurn?.cardId ?: return Transition.reject(state, event, "no-card")
        val msgId = UUID.randomUUID().toString()
        val pending = newPending(PendingAction.ActionType.REQUEST_EXPLANATION, state, msgId, state.cardTurn?.turnId, cardId, clockMs)
        val send = StudyEffect.Network.Send(msgId, ClientMessage.RequestExplanation(sessionId = state.session?.sessionId, cardId = cardId, messageId = msgId))
        val newState = state.copy(pendingAction = pending).recordTransition(event, state.phase, state.phase)
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type)))
    }

    private fun handleExplanationReceived(state: SessionMachineState, event: StudyEvent.ServerExplanationReceived, clockMs: Long): Transition {
        if (state.cardTurn?.cardId != event.cardId && event.cardId != null) return Transition.reject(state, event, "stale-card")
        val effectId = EffectIds.next("explanation")
        val newState = state.copy(
            phase = SessionPhase.SpeakingExplanation,
            activeSpeechEffectId = effectId,
            pendingAction = null
        ).recordTransition(event, state.phase, SessionPhase.SpeakingExplanation)
            .rememberServerMessageId(event.messageId)
        val speak = SpeechRequest(
            id = SpeechIds.forPurpose(SpeechPurpose.EXPLANATION, event.cardId ?: "expl"),
            text = event.explanationText,
            purpose = SpeechPurpose.EXPLANATION,
            priority = SpeechPriority.NORMAL,
            queuePolicy = QueuePolicy.APPEND,
            interruptible = true
        )
        return Transition(newState, listOf(StudyEffect.Voice.Speak(speak, effectId), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.SpeakingExplanation, event.cardId, state.epoch)))
    }

    private fun handleExplanationCompleted(state: SessionMachineState, event: StudyEvent.ExplanationSpeechCompleted): Transition {
        if (state.phase !is SessionPhase.SpeakingExplanation) return Transition.reject(state, event, "illegal-phase")
        val card = state.cardTurn?.card ?: return Transition.reject(state, event, "no-card")
        val evaluation = state.cardTurn?.evaluation ?: state.session?.lastEvaluation
        val newPhase = if (evaluation != null) SessionPhase.WaitingForRating else SessionPhase.WaitingForAnswer
        val newState = state.copy(phase = newPhase, activeSpeechEffectId = null)
            .recordTransition(event, state.phase, newPhase)
        val voiceEffect = when (newPhase) {
            SessionPhase.WaitingForRating -> StudyEffect.Voice.StartRecognition(RecognitionPurpose.RATING, card.id, EffectIds.next("stt"))
            else -> StudyEffect.Voice.StartRecognition(RecognitionPurpose.ANSWER, card.id, EffectIds.next("stt"))
        }
        return Transition(newState, listOf(voiceEffect, StudyEffect.LogTransition(state.phase, event.debugName, newPhase, card.id, state.epoch)))
    }

    private fun handleAnswerRequest(state: SessionMachineState, event: StudyEvent.UserRequestAnswer, clockMs: Long): Transition {
        if (state.phase !is SessionPhase.WaitingForAnswer && state.phase !is SessionPhase.PendingAnswerReview) {
            return Transition.reject(state, event, "illegal-phase-for-answer-reveal")
        }
        val cardId = event.cardId ?: state.cardTurn?.cardId ?: return Transition.reject(state, event, "no-card")
        val msgId = UUID.randomUUID().toString()
        val pending = newPending(PendingAction.ActionType.REQUEST_ANSWER, state, msgId, state.cardTurn?.turnId, cardId, clockMs)
        val send = StudyEffect.Network.Send(msgId, ClientMessage.RequestAnswer(sessionId = state.session?.sessionId, cardId = cardId, messageId = msgId))
        val newState = state.copy(pendingAction = pending).recordTransition(event, state.phase, state.phase)
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type)))
    }

    private fun handleAnswerReceived(state: SessionMachineState, event: StudyEvent.ServerAnswerReceived): Transition {
        if (event.cardId != null && state.cardTurn?.cardId != event.cardId) return Transition.reject(state, event, "stale-card")
        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-turn")
        val updated = turn.withAnswerRevealed()
        val effectId = EffectIds.next("answer")
        val newState = state.copy(
            phase = SessionPhase.ShowingAnswer,
            cardTurn = updated,
            activeSpeechEffectId = effectId,
            pendingAction = null
        ).recordTransition(event, state.phase, SessionPhase.ShowingAnswer)
            .rememberServerMessageId(event.messageId)
        return if (event.speak) {
            val speak = SpeechRequest(
                id = SpeechIds.forPurpose(SpeechPurpose.ANSWER, event.cardId ?: "ans"),
                text = event.answerText,
                purpose = SpeechPurpose.ANSWER,
                priority = SpeechPriority.NORMAL,
                queuePolicy = QueuePolicy.REPLACE
            )
            Transition(newState, listOf(StudyEffect.Voice.Speak(speak, effectId), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.ShowingAnswer, event.cardId, state.epoch)))
        } else {
            Transition(newState, listOf(StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.ShowingAnswer, event.cardId, state.epoch)))
        }
    }

    private fun handleRepeat(state: SessionMachineState, event: StudyEvent.UserRequestRepeat, clockMs: Long): Transition {
        val card = state.cardTurn?.card ?: state.session?.currentCard ?: return Transition.reject(state, event, "no-card")
        // Choose contract §80: local repeat only + optional server request but duplicate suppressed
        val effectId = EffectIds.next("repeat")
        val speak = SpeechRequest(
            id = SpeechIds.forPurpose(SpeechPurpose.QUESTION, card.id),
            text = card.question,
            purpose = SpeechPurpose.QUESTION,
            priority = SpeechPriority.NORMAL,
            queuePolicy = QueuePolicy.REPLACE
        )
        val msgId = UUID.randomUUID().toString()
        val pending = newPending(PendingAction.ActionType.REPEAT_QUESTION, state, msgId, state.cardTurn?.turnId, card.id, clockMs)
        val send = StudyEffect.Network.Send(msgId, ClientMessage.RepeatQuestion(sessionId = state.session?.sessionId, cardId = card.id, messageId = msgId))
        val newState = state.copy(
            phase = SessionPhase.SpeakingQuestion,
            activeSpeechEffectId = effectId,
            pendingAction = pending
        ).recordTransition(event, state.phase, SessionPhase.SpeakingQuestion)
        return Transition(newState, listOf(StudyEffect.Voice.Speak(speak, effectId), send, StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.SpeakingQuestion, card.id, state.epoch)))
    }

    private fun handleSkip(state: SessionMachineState, event: StudyEvent.UserSkipRequested, clockMs: Long): Transition {
        // Validity §55
        if (state.phase is SessionPhase.Finished || state.phase is SessionPhase.Finishing) return Transition.reject(state, event, "illegal-phase")
        // If rating already submitted, skip is illegal
        if (state.cardTurn?.let { state.ledger.hasRatingInFlight(it.turnId) } == true) {
            return Transition.reject(state, event, "rating-in-flight")
        }
        val cardId = event.cardId ?: state.cardTurn?.cardId
        val msgId = UUID.randomUUID().toString()
        val pending = newPending(PendingAction.ActionType.SKIP_CARD, state, msgId, state.cardTurn?.turnId, cardId, clockMs)
        val send = StudyEffect.Network.Send(msgId, ClientMessage.SkipCard(sessionId = state.session?.sessionId, cardId = cardId, messageId = msgId))
        val newState = state.copy(
            phase = SessionPhase.WaitingForFirstCard, // awaiting next question
            pendingAction = pending,
            pendingTranscript = null,
            pendingRatingConfirmation = null,
            ledger = if (state.cardTurn != null) state.ledger.copy(entries = state.ledger.entries.mapValues { (_, v) ->
                if (v.turnId == state.cardTurn.turnId) v.copy(skipState = SubmissionLedger.SubmissionState.IN_FLIGHT) else v
            }) else state.ledger
        ).recordTransition(event, state.phase, SessionPhase.WaitingForFirstCard)
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type), StudyEffect.Voice.CancelRecognition("card-skipped"), StudyEffect.Voice.CancelSpeech("skip"), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.WaitingForFirstCard, cardId, state.epoch)))
    }

    // ------------------------------------------------------------------ PAUSE / RESUME

    private fun handlePauseRequested(state: SessionMachineState, event: StudyEvent.UserPauseRequested, clockMs: Long): Transition {
        if (state.phase is SessionPhase.Paused || state.phase is SessionPhase.Pausing) {
            return Transition.reject(state, event, "already-paused")
        }
        if (state.isFinished || state.isIdle) return Transition.reject(state, event, "illegal-phase")
        val msgId = event.messageId.ifBlank { UUID.randomUUID().toString() }
        val pending = newPending(PendingAction.ActionType.PAUSE_SESSION, state, msgId, state.cardTurn?.turnId, state.cardTurn?.cardId, clockMs)
        val send = StudyEffect.Network.Send(msgId, ClientMessage.PauseSession(sessionId = state.session?.sessionId, messageId = msgId))
        val resumeCtx = ResumeContext(state.epoch, state.phase, state.cardTurn, state.pendingTranscript?.text, clockMs)
        val newState = state.copy(
            phase = SessionPhase.Pausing,
            pauseContext = resumeCtx,
            pendingAction = pending
        ).recordTransition(event, state.phase, SessionPhase.Pausing)
        return Transition(newState, listOf(StudyEffect.Voice.CancelSpeech("pause"), StudyEffect.Voice.CancelRecognition("pause"), send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Pausing, state.currentCardId, state.epoch)))
    }

    private fun handleServerPaused(state: SessionMachineState, event: StudyEvent.ServerSessionPaused): Transition {
        if (state.phase !is SessionPhase.Pausing && state.phase !is SessionPhase.Paused) {
            // If we never requested but server says paused, honor it
            if (state.phase is SessionPhase.Idle || state.phase is SessionPhase.Finished) return Transition.reject(state, event, "illegal-phase")
        }
        // Prevent nested Paused §85
        if (state.phase is SessionPhase.Paused) return Transition.reject(state, event, "already-paused")
        // Preserve original ResumeContext if exists, else capture
        val ctx = state.pauseContext ?: ResumeContext(state.epoch, state.phase, state.cardTurn, null, System.currentTimeMillis())
        val newState = state.copy(
            phase = SessionPhase.Paused,
            pauseContext = ctx,
            pendingAction = null
        ).recordTransition(event, state.phase, SessionPhase.Paused)
            .rememberServerMessageId(event.messageId)
        return Transition(newState, listOf(StudyEffect.CancelTimeout(state.pendingAction?.messageId ?: ""), StudyEffect.Voice.CancelSpeech("server-paused"), StudyEffect.Voice.CancelRecognition("server-paused"), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Paused, state.currentCardId, state.epoch)))
    }

    private fun handleResumeRequested(state: SessionMachineState, event: StudyEvent.UserResumeRequested, clockMs: Long): Transition {
        if (state.phase !is SessionPhase.Paused) return Transition.reject(state, event, "not-paused")
        val msgId = event.messageId.ifBlank { UUID.randomUUID().toString() }
        val pending = newPending(PendingAction.ActionType.RESUME_SESSION, state, msgId, state.pauseContext?.cardTurn?.turnId, state.pauseContext?.cardTurn?.cardId, clockMs)
        val send = StudyEffect.Network.Send(msgId, ClientMessage.ResumeSession(sessionId = state.session?.sessionId, messageId = msgId))
        val newState = state.copy(
            phase = SessionPhase.Resuming,
            pendingAction = pending
        ).recordTransition(event, state.phase, SessionPhase.Resuming)
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Resuming, state.currentCardId, state.epoch)))
    }

    private fun handleServerResumed(state: SessionMachineState, event: StudyEvent.ServerSessionResumed): Transition {
        if (state.phase !is SessionPhase.Resuming && state.phase !is SessionPhase.Paused) {
            return Transition.reject(state, event, "not-resuming")
        }
        val ctx = state.pauseContext
        val safePhase = ResumeContext.safeRestartPhase(ctx, SessionPhase.WaitingForAnswer)
        val card = ctx?.cardTurn?.card ?: state.cardTurn?.card
        val newCardTurn = ctx?.cardTurn ?: state.cardTurn
        val newState = state.copy(
            phase = safePhase,
            cardTurn = newCardTurn,
            pauseContext = null,
            pendingAction = null
        ).recordTransition(event, state.phase, safePhase)
            .rememberServerMessageId(event.messageId)

        val effects = mutableListOf<StudyEffect>()
        effects.add(StudyEffect.CancelTimeout(state.pendingAction?.messageId ?: ""))
        effects.add(StudyEffect.LogTransition(state.phase, event.debugName, safePhase, card?.id, state.epoch))
        if (card != null) {
            when (safePhase) {
                SessionPhase.SpeakingQuestion -> {
                    val effId = EffectIds.next("resume-question")
                    effects.add(StudyEffect.Voice.Speak(speechRequestForQuestion(card), effId))
                    // update activeSpeechEffectId via state copy? We'll handle executor assignment
                }
                SessionPhase.WaitingForAnswer -> effects.add(StudyEffect.Voice.StartRecognition(RecognitionPurpose.ANSWER, card.id, EffectIds.next("stt")))
                SessionPhase.WaitingForRating -> effects.add(StudyEffect.Voice.StartRecognition(RecognitionPurpose.RATING, card.id, EffectIds.next("stt")))
                SessionPhase.WaitingForEvaluation -> effects.add(StudyEffect.Network.Send(UUID.randomUUID().toString(), ClientMessage.RequestSessionStatus(sessionId = state.session?.sessionId)))
                else -> {}
            }
        }
        return Transition(newState, effects)
    }

    // ------------------------------------------------------------------ END

    private fun handleEndRequested(state: SessionMachineState, event: StudyEvent.UserEndRequested, clockMs: Long): Transition {
        if (state.phase is SessionPhase.Finished || state.phase is SessionPhase.Finishing) return Transition.reject(state, event, "already-finishing")
        if (state.isIdle) return Transition.reject(state, event, "no-session")
        val msgId = event.messageId.ifBlank { UUID.randomUUID().toString() }
        val pending = newPending(PendingAction.ActionType.END_SESSION, state, msgId, state.cardTurn?.turnId, state.cardTurn?.cardId, clockMs)
        val send = StudyEffect.Network.Send(msgId, ClientMessage.EndSession(sessionId = state.session?.sessionId, messageId = msgId))
        val newState = state.copy(
            phase = SessionPhase.Finishing,
            pendingAction = pending
        ).recordTransition(event, state.phase, SessionPhase.Finishing)
        return Transition(newState, listOf(StudyEffect.Voice.CancelSpeech("end"), StudyEffect.Voice.CancelRecognition("end"), send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, pending.type), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Finishing, state.currentCardId, state.epoch)))
    }

    private fun handleSessionFinished(state: SessionMachineState, event: StudyEvent.ServerSessionFinished): Transition {
        // Terminal guard already handled, but allow idempotent duplicate
        if (state.phase is SessionPhase.Finished && state.session?.sessionId == event.sessionId) {
            return Transition.reject(state, event, "duplicate-finished")
        }
        val newState = state.copy(
            phase = SessionPhase.Finished,
            pendingAction = null,
            pauseContext = null,
            activeSpeechEffectId = null,
            activeRecognitionEffectId = null
        ).recordTransition(event, state.phase, SessionPhase.Finished)
            .rememberServerMessageId(event.messageId)
        return Transition(newState, listOf(StudyEffect.Voice.CancelSpeech("session-finished"), StudyEffect.Voice.CancelRecognition("session-finished"), StudyEffect.CancelTimeout(state.pendingAction?.messageId ?: ""), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Finished, state.currentCardId, state.epoch)))
    }

    // ------------------------------------------------------------------ CONNECTION

    private fun handleConnectionLost(state: SessionMachineState, event: StudyEvent.ConnectionLost, clockMs: Long): Transition {
        if (state.isIdle || state.phase is SessionPhase.Finished) return Transition.reject(state, event, "no-active-session")
        // Freeze voice §35
        val newState = state.copy(
            phase = SessionPhase.Recovering,
            connection = SessionConnectionStatus.DISCONNECTED
        ).recordTransition(event, state.phase, SessionPhase.Recovering)
        return Transition(newState, listOf(StudyEffect.Voice.CancelSpeech("connection-lost"), StudyEffect.Voice.CancelRecognition("connection-lost"), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Recovering, state.currentCardId, state.epoch)))
    }

    private fun handleConnectionRestored(state: SessionMachineState, event: StudyEvent.ConnectionRestored): Transition {
        if (state.phase !is SessionPhase.Recovering && state.phase !is SessionPhase.Error) {
            // If already connected, treat as no-op but request status for safety
            if (state.connection == SessionConnectionStatus.CONNECTED) return Transition.reject(state, event, "already-connected")
        }
        // Request authoritative snapshot §35-§37
        val msgId = UUID.randomUUID().toString()
        val send = StudyEffect.Network.Send(msgId, ClientMessage.RequestSessionStatus(sessionId = state.session?.sessionId, messageId = msgId))
        val newState = state.copy(
            connection = SessionConnectionStatus.RECOVERING,
            phase = SessionPhase.Recovering
        ).recordTransition(event, state.phase, SessionPhase.Recovering)
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, PendingAction.timeoutFor(PendingAction.ActionType.REQUEST_SESSION_STATUS), PendingAction.ActionType.REQUEST_SESSION_STATUS), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Recovering, state.currentCardId, state.epoch)))
    }

    private fun handleStatusReceived(state: SessionMachineState, event: StudyEvent.SessionStatusReceived, clockMs: Long): Transition {
        // Reconcile §39
        val reconciliation = SessionReconciler.reconcile(state, event.snapshot, clockMs)
        val finalState = reconciliation.newState.recordTransition(event, state.phase, reconciliation.newState.phase)
        return Transition(finalState, reconciliation.effects + StudyEffect.LogTransition(state.phase, event.debugName, finalState.phase, finalState.currentCardId, state.epoch))
    }

    private fun handleSnapshot(state: SessionMachineState, event: StudyEvent.ServerSnapshotReceived, clockMs: Long): Transition {
        return handleStatusReceived(state, StudyEvent.SessionStatusReceived(event.snapshot), clockMs)
    }

    // ------------------------------------------------------------------ PUSH TO TALK

    /**
     * Manual push-to-talk (§27/§59/§79).
     *
     * Audio mode and interaction mode are independent: this works identically on headphones and
     * on the phone speaker. The window decides what is being listened for — an answer during a
     * question, a rating/command in the feedback and rating windows.
     */
    private fun handlePttStarted(state: SessionMachineState, event: StudyEvent.PttStarted): Transition {
        if (state.isIdle || state.isFinished || state.phase is SessionPhase.Error) {
            return Transition.reject(state, event, "illegal-phase")
        }
        val card = state.cardTurn?.card ?: state.session?.currentCard
            ?: return Transition.reject(state, event, "no-card")
        if (event.cardId != null && event.cardId.isNotBlank() && event.cardId != card.id) {
            return Transition.reject(state, event, "card-mismatch")
        }
        val purpose = when (state.phase) {
            is SessionPhase.WaitingForRating,
            is SessionPhase.SpeakingFeedback,
            is SessionPhase.SpeakingExplanation,
            is SessionPhase.HintShowing -> RecognitionPurpose.PUSH_TO_TALK_COMMAND

            else -> RecognitionPurpose.PUSH_TO_TALK_ANSWER
        }
        val effectId = EffectIds.next("ptt")
        // Speech is interrupted first: the microphone must never open underneath a live
        // utterance, and the turn gate applies the acoustic gap afterwards.
        val newState = state.copy(activeRecognitionEffectId = effectId)
            .recordTransition(event, state.phase, state.phase)
        return Transition(
            newState,
            listOf(
                StudyEffect.Voice.CancelSpeech("ptt"),
                StudyEffect.Voice.StartRecognition(purpose, card.id, effectId),
                StudyEffect.LogTransition(state.phase, event.debugName, state.phase, card.id, state.epoch)
            )
        )
    }

    /** Release: finish the turn, never submit here — the terminal result decides (§18/§105). */
    private fun handlePttStopped(state: SessionMachineState, event: StudyEvent.PttStopped): Transition {
        if (state.isIdle || state.isFinished) return Transition.reject(state, event, "illegal-phase")
        val newState = state.copy(activeRecognitionEffectId = null)
            .recordTransition(event, state.phase, state.phase)
        return Transition(newState, listOf(StudyEffect.Voice.StopListening("ptt-release")))
    }

    // ------------------------------------------------------------------ VOICE ROUTE

    /**
     * The user's audio preference cannot be satisfied (only `HEADSET_REQUIRED` without
     * headphones). Recoverable and explicit: study is not started, and the message says how to
     * proceed (§7/§82). Every other mode falls back to the phone instead of landing here.
     */
    private fun handleVoiceRouteBlocked(state: SessionMachineState, event: StudyEvent.VoiceRouteBlocked): Transition {
        val problem = SessionProblemHolder(SessionProblem.VOICE_ONLY_FAILURE, event.reason, true)
        val newState = state.copy(
            phase = SessionPhase.Error(SessionProblem.VOICE_ONLY_FAILURE),
            error = problem
        ).recordTransition(event, state.phase, SessionPhase.Error(SessionProblem.VOICE_ONLY_FAILURE))
        return Transition(
            newState,
            listOf(
                StudyEffect.Voice.CancelSpeech("route-blocked"),
                StudyEffect.Voice.CancelRecognition("route-blocked"),
                StudyEffect.LogRejected(event.debugName, "voice-route-blocked", state.phase, state.currentCardId)
            )
        )
    }

    // ------------------------------------------------------------------ AUDIO

    private fun handleAudioLost(state: SessionMachineState, event: StudyEvent.AudioRouteLost): Transition {
        if (state.phase is SessionPhase.Paused || state.isFinished || state.isIdle) return Transition.reject(state, event, "illegal-phase")
        // A lost output route must stop the app talking as well as listening: continuing to
        // speak through the loudspeaker after the user's headphones disappeared is exactly the
        // privacy behaviour the disconnect policy exists to prevent (§96).
        val newState = state.copy(connection = SessionConnectionStatus.DISCONNECTED).recordTransition(event, state.phase, state.phase)
        return Transition(
            newState,
            listOf(
                StudyEffect.Voice.CancelSpeech("route-lost"),
                StudyEffect.Voice.CancelRecognition("audio-lost"),
                StudyEffect.LogRejected(event.debugName, "audio-lost", state.phase, event.cardId)
            )
        )
    }

    private fun handleAudioRestored(state: SessionMachineState, event: StudyEvent.AudioRouteRestored): Transition {
        // Repeat current question deterministically
        val card = state.cardTurn?.card ?: return Transition.reject(state, event, "no-card")
        if (state.cardTurn?.cardId != event.cardId && event.cardId != null) return Transition.reject(state, event, "stale-card")
        val effectId = EffectIds.next("route-restore")
        val speak = speechRequestForQuestion(card)
        val newState = state.copy(phase = SessionPhase.SpeakingQuestion, activeSpeechEffectId = effectId).recordTransition(event, state.phase, SessionPhase.SpeakingQuestion)
        return Transition(newState, listOf(StudyEffect.Voice.Speak(speak, effectId), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.SpeakingQuestion, card.id, state.epoch)))
    }

    private fun handleStopSpeaking(state: SessionMachineState): Transition {
        val card = state.cardTurn?.card ?: return Transition(state, emptyList())
        val evaluation = state.cardTurn?.evaluation ?: state.session?.lastEvaluation
        val newPhase = if (evaluation != null) SessionPhase.WaitingForRating else SessionPhase.WaitingForAnswer
        val newState = state.copy(phase = newPhase, activeSpeechEffectId = null).recordTransition(StudyEvent.UserStopSpeaking, state.phase, newPhase)
        val voiceEff = when (newPhase) {
            SessionPhase.WaitingForRating -> StudyEffect.Voice.StartRecognition(RecognitionPurpose.RATING, card.id, EffectIds.next("stt"))
            else -> StudyEffect.Voice.StartRecognition(RecognitionPurpose.ANSWER, card.id, EffectIds.next("stt"))
        }
        return Transition(newState, listOf(StudyEffect.Voice.CancelSpeech("user-stop"), voiceEff))
    }

    // ------------------------------------------------------------------ RECOGNITION

    private fun handleRecognitionCompleted(state: SessionMachineState, event: StudyEvent.RecognitionCompleted): Transition {
        // Turn ownership: only active turn
        if (event.turnId != null && state.cardTurn?.turnId != event.turnId && event.turnId.isNotBlank()) {
            return Transition.reject(state, event, "stale-turn")
        }
        if (event.cardId != null && state.cardTurn?.cardId != event.cardId) {
            return Transition.reject(state, event, "stale-card")
        }
        // If autoSubmit false and in WaitingForAnswer, park transcript
        if (state.phase is SessionPhase.WaitingForAnswer && !event.isCommand) {
            // We don't know autoSubmit setting here; default to submit path, but add pending logic handled upstream
            // For reducer we create PendingAnswerReview if transcript looks like answer
            val card = state.cardTurn ?: return Transition.reject(state, event, "no-card")
            // Heuristic: if pending transcript feature enabled, caller should dispatch UserSubmitAnswer or keep pending.
            // We'll park when state has pendingTranscript handling flag? Simplify: move to pending if not immediate submit.
            // For now, directly submit; UI layer decides autoSubmit via SettingsChanged effect.
            return handleSubmitAnswer(state, StudyEvent.UserSubmitAnswer(card.cardId, event.transcript), System.currentTimeMillis())
        }
        return Transition.reject(state, event, "unhandled-recognition")
    }

    private fun handleRecognitionFailed(state: SessionMachineState, event: StudyEvent.RecognitionFailed): Transition {
        // Voice-only failure should not be fatal §53
        val newState = state.copy(error = SessionProblemHolder(SessionProblem.VOICE_ONLY_FAILURE, event.reason, true)).recordTransition(event, state.phase, state.phase)
        return Transition(newState, listOf(StudyEffect.LogRejected(event.debugName, event.reason, state.phase, event.cardId)))
    }

    private fun handleTimeout(state: SessionMachineState, event: StudyEvent.ActionTimedOut, clockMs: Long): Transition {
        val pending = state.pendingAction ?: return Transition.reject(state, event, "no-pending")
        when (event.type) {
            PendingAction.ActionType.START_SESSION -> {
                val newState = state.copy(
                    phase = SessionPhase.Error(SessionProblem.START_TIMEOUT),
                    error = SessionProblemHolder(SessionProblem.START_TIMEOUT, "Start session timed out", true),
                    pendingAction = null
                ).recordTransition(event, state.phase, SessionPhase.Error(SessionProblem.START_TIMEOUT))
                return Transition(newState, listOf(StudyEffect.LogRejected(event.debugName, "start-timeout", state.phase, state.currentCardId)))
            }
            PendingAction.ActionType.SUBMIT_ANSWER -> {
                // Mark ledger as retryable, rollback to WaitingForAnswer for retry §27
                val turnId = pending.cardTurnId ?: return Transition.reject(state, event, "no-turn")
                val ledger2 = state.ledger.markAnswerFailed(turnId, true)
                val newState = state.copy(
                    phase = SessionPhase.WaitingForAnswer,
                    ledger = ledger2,
                    pendingAction = null,
                    error = SessionProblemHolder(SessionProblem.EVALUATION_TIMEOUT, "Evaluation timed out", true)
                ).recordTransition(event, state.phase, SessionPhase.WaitingForAnswer)
                return Transition(newState, listOf(StudyEffect.LogRejected(event.debugName, "submit-timeout", state.phase, pending.cardId)))
            }
            PendingAction.ActionType.RATE_CARD -> {
                val turnId = pending.cardTurnId ?: return Transition.reject(state, event, "no-turn")
                val ledger2 = state.ledger.markRatingFailed(turnId, true)
                val newState = state.copy(
                    phase = SessionPhase.WaitingForRating,
                    ledger = ledger2,
                    pendingAction = null,
                    error = SessionProblemHolder(SessionProblem.RATING_TIMEOUT, "Rating timed out", true)
                ).recordTransition(event, state.phase, SessionPhase.WaitingForRating)
                return Transition(newState, emptyList())
            }
            PendingAction.ActionType.PAUSE_SESSION -> {
                // Stay in Pausing but mark recoverable
                val newState = state.copy(
                    error = SessionProblemHolder(SessionProblem.PAUSE_TIMEOUT, "Pause ack timed out", true)
                ).recordTransition(event, state.phase, state.phase)
                return Transition(newState, emptyList())
            }
            PendingAction.ActionType.RESUME_SESSION -> {
                val newState = state.copy(error = SessionProblemHolder(SessionProblem.RESUME_TIMEOUT, "Resume ack timed out", true)).recordTransition(event, state.phase, state.phase)
                return Transition(newState, emptyList())
            }
            PendingAction.ActionType.END_SESSION -> {
                // Decide local end anyway (§82): move to Finished but mark uncertain
                val newState = state.copy(
                    phase = SessionPhase.Finished,
                    finishingIsLocalOnly = true,
                    pendingAction = null
                ).recordTransition(event, state.phase, SessionPhase.Finished)
                return Transition(newState, listOf(StudyEffect.Voice.CancelSpeech("end-timeout"), StudyEffect.Voice.CancelRecognition("end-timeout")))
            }
            PendingAction.ActionType.REQUEST_SESSION_STATUS -> {
                val newState = state.copy(error = SessionProblemHolder(SessionProblem.STATUS_TIMEOUT, "Session status timed out", true)).recordTransition(event, state.phase, state.phase)
                return Transition(newState, emptyList())
            }
            else -> {
                val newState = state.copy(pendingAction = null).recordTransition(event, state.phase, state.phase)
                return Transition(newState, emptyList())
            }
        }
    }

    companion object {
        /**
         * How many accepted card-turn ids are retained (§21).
         *
         * Sized for identity/race diagnosis — a stale callback is at most a few turns behind — and
         * deliberately far smaller than a session's card count, so a 1000-card endurance run holds
         * the same 64 strings as a 10-card one.
         */
        const val CARD_TURN_HISTORY_LIMIT = 64
    }
}
