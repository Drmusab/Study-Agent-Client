package com.studyagent.client.core.study

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.voice.stt.CommandNormalizer
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
        if (event is AnkiStudyEvent || (state.anki != null &&
                !(state.isFinished && event is StudyEvent.UserStartRequested))) {
            return reduceAnkiInteraction(state, event, clockMs)
        }
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
            event is StudyEvent.AnswerSpeechCompleted ||
            event is StudyEvent.HintSpeechCompleted || event is StudyEvent.ExplanationSpeechCompleted) {
            val isStale = state.activeSpeechEffectId != (event as? StudyEvent.QuestionSpeechCompleted)?.effectId &&
                state.activeSpeechEffectId != (event as? StudyEvent.FeedbackSpeechCompleted)?.effectId &&
                state.activeSpeechEffectId != (event as? StudyEvent.AnswerSpeechCompleted)?.effectId &&
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
            is StudyEvent.ServerSessionResumed -> handleServerResumed(state, event)
            is StudyEvent.UserEndRequested -> handleEndRequested(state, event, clockMs)
            is StudyEvent.ServerSessionFinished -> handleSessionFinished(state, event)
            is StudyEvent.ConnectionLost -> handleConnectionLost(state, event, clockMs)
            is StudyEvent.ConnectionRestored -> handleConnectionRestored(state, event, clockMs)
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


    /** Backend-specific interaction policy inside the one authoritative reducer, not a machine. */
    private fun reduceAnkiInteraction(state: SessionMachineState, event: StudyEvent, now: Long): Transition {
        fun reject(reason: String) = Transition.reject(state, event, reason)
        fun moved(next: SessionMachineState, effects: List<StudyEffect> = emptyList()): Transition =
            Transition(next.recordTransition(event, state.phase, next.phase), effects)
        val cancelVoice = listOf(
            StudyEffect.Voice.CancelSpeech("anki-control"),
            StudyEffect.Voice.CancelRecognition("anki-control")
        )
        if (event is AnkiStudyEvent.Start) {
            if (!state.isIdle && !state.isFinished) return reject("already-active")
            val epoch = state.epoch + 1
            return moved(SessionMachineState.initial(epoch).copy(
                phase = SessionPhase.Starting,
                anki = AnkiStudyInteraction(event.request)
            ), cancelVoice + AnkiStudyEffect.Begin(epoch, event.request, now))
        }
        val local = state.anki ?: return reject("no-anki-session")
        if (state.isFinished) return reject("terminal-finished")
        fun failed(error: AnkiError): Transition {
            val problem = if (error is AnkiError.CommitLedgerUnavailable)
                SessionProblem.ANKI_COMMIT_INTEGRITY else SessionProblem.ANKI_UNAVAILABLE
            return moved(state.copy(
                phase = SessionPhase.Error(problem),
                anki = local.copy(failure = error),
                error = SessionProblemHolder(problem, error.message, false, now),
                activeSpeechEffectId = null, activeRecognitionEffectId = null
            ), cancelVoice + AnkiStudyEffect.CancelReads)
        }
        fun reveal(transcript: String? = local.transcript, cancelEval: Boolean = false): Transition {
            val card = local.turn?.renderedCard ?: return reject("illegal-reveal")
            if (state.phase !in setOf(
                    SessionPhase.SpeakingQuestion, SessionPhase.WaitingForAnswer,
                    SessionPhase.PendingAnswerReview, SessionPhase.WaitingForEvaluation,
                    SessionPhase.WaitingForRating)) return reject("illegal-reveal")
            if (state.phase == SessionPhase.WaitingForRating &&
                local.revealState == AnswerRevealState.REVEALED &&
                transcript == local.transcript) {
                return reject("answer-already-revealed")
            }
            val autoFallbackToClean = card.answerHtml == null && card.answerText != null &&
                local.compareMode == AnswerCompareMode.ORIGINAL
            val speakAns = local.request.speakAnswer && !card.answerText.isNullOrBlank()
            val ansSpeechId = if (speakAns) EffectIds.next("anki-answer") else null
            val effects = buildList {
                addAll(cancelVoice)
                if (cancelEval) add(AnkiStudyEffect.CancelReads)
                if (ansSpeechId != null && !card.answerText.isNullOrBlank()) {
                    add(StudyEffect.Voice.Speak(speechRequestForAnswer(state.currentCardId ?: "", card.answerText), ansSpeechId))
                }
            }
            return moved(state.copy(
                phase = SessionPhase.WaitingForRating,
                anki = local.copy(
                    transcript = transcript,
                    revealState = AnswerRevealState.REVEALED,
                    compareMode = if (autoFallbackToClean) AnswerCompareMode.CLEAN else local.compareMode,
                    renderFallbackReason = if (autoFallbackToClean) "html_unavailable" else local.renderFallbackReason,
                    activeEvaluationRequestId = null,
                    evaluationStatus = if (cancelEval) AnswerEvaluationStatus.UNAVAILABLE else local.evaluationStatus,
                    evaluationFailureReason = if (cancelEval) "cancelled_by_manual_reveal" else local.evaluationFailureReason,
                    audioSequencePhase = if (ansSpeechId != null) AnswerAudioSequencePhase.SPEAKING_ANSWER else AnswerAudioSequencePhase.VISUAL_REVEALED
                ),
                cardTurn = state.cardTurn?.withAnswerRevealed(),
                activeSpeechEffectId = ansSpeechId,
                activeRecognitionEffectId = null
            ), effects)
        }
        fun submitAnswer(rawAnswer: String): Transition {
            val turn = local.turn ?: return reject("no-turn")
            val card = turn.renderedCard ?: return reject("no-rendered-card")
            val answer = rawAnswer.trim()
            if (answer.isBlank()) return reject("blank-anki-answer")
            val normalizedCmd = CommandNormalizer.forCommand(answer)
            if (normalizedCmd in ANKI_REVEAL_VOICE_PHRASES) {
                return reveal(transcript = local.transcript)
            }
            if (!local.request.evaluateAnswers) {
                return reveal(transcript = answer)
            }
            val reqId = EffectIds.next("anki-eval")
            val evalRequest = AnkiAnswerEvaluationRequest.fromCard(
                requestId = reqId,
                sessionId = local.request.studySessionId,
                turnId = turn.turnId,
                card = card,
                userAnswerText = answer
            )
            // STEP 13 & 23: Never send answerHtml to AI evaluator; if pure/clean answer text is
            // absent, degrade cleanly to manual review on the same turn.
            if (evalRequest == null) {
                val revealed = reveal(transcript = answer)
                val updatedLocal = revealed.newState.anki?.copy(
                    evaluationStatus = AnswerEvaluationStatus.UNAVAILABLE,
                    evaluationFailureReason = "reference_answer_text_unavailable"
                )
                return revealed.copy(newState = revealed.newState.copy(anki = updatedLocal))
            }
            return moved(state.copy(
                phase = SessionPhase.WaitingForEvaluation,
                anki = local.copy(
                    transcript = answer,
                    evaluationStatus = AnswerEvaluationStatus.EVALUATING,
                    evaluationFailureReason = null,
                    activeEvaluationRequestId = reqId,
                    audioSequencePhase = AnswerAudioSequencePhase.STOPPING_QUESTION_AND_STT
                ),
                activeSpeechEffectId = null,
                activeRecognitionEffectId = null,
                error = null
            ), cancelVoice + AnkiStudyEffect.EvaluateAnswer(state.epoch, evalRequest, local.request.speakFeedback))
        }
        fun speak(): Transition {
            val turn = state.cardTurn ?: return reject("no-card")
            if (local.turn?.renderedCard?.questionText.isNullOrBlank()) return reject("no-speech-text")
            val id = EffectIds.next("anki-question")
            return moved(state.copy(
                phase = SessionPhase.SpeakingQuestion, activeSpeechEffectId = id,
                activeRecognitionEffectId = null
            ), cancelVoice + StudyEffect.Voice.Speak(speechRequestForQuestion(turn.card), id))
        }
        return when (event) {
            is AnkiStudyEvent.Begun -> {
                if (event.epoch != state.epoch || state.phase != SessionPhase.Starting || local.reviewSession != null) {
                    return reject("stale-anki-begin")
                }
                when (val result = event.result) {
                    is AnkiResult.Failure -> failed(result.error)
                    is AnkiResult.Success -> {
                        val context = result.value.context
                        if (context.studySessionId != local.request.studySessionId ||
                            context.deckRef != local.request.deck ||
                            !local.request.preference.accepts(context.backendId)) return failed(AnkiError.SessionInvalid())
                        moved(state.copy(
                            phase = SessionPhase.WaitingForFirstCard,
                            session = StudySessionSnapshot(context.studySessionId, "Anki review", startedAtEpochMs = now),
                            anki = local.copy(reviewSession = result.value,
                                priorUnresolvedCommits = event.priorUnresolvedCommits)
                        ), listOf(AnkiStudyEffect.Next(state.epoch, result.value)))
                    }
                }
            }
            is AnkiStudyEvent.RecoveryBlocked -> {
                val record = event.record
                if (event.epoch != state.epoch || state.phase != SessionPhase.Starting ||
                    record.backendId != local.request.deck.backendId ||
                    record.status == ReviewCommitStatus.COMMITTED) return reject("stale-anki-recovery")
                val unresolved = record.status == ReviewCommitStatus.AMBIGUOUS
                val pendingWrite = record.status == ReviewCommitStatus.SUBMITTING
                val phase = when {
                    pendingWrite -> SessionPhase.CommitPersistenceFailure
                    unresolved -> SessionPhase.ReconciliationRequired
                    else -> SessionPhase.RatingCommitFailed
                }
                // GATE 11D §27-29 — the interrupted review turn stays the current one: its
                // presentation identity is re-attached from the durable record (same ReviewTurnId,
                // same ReviewCommitId) without any scheduler query (INV-11D-17).
                val restoredTurn = restoredTurnFor(record)
                moved(state.copy(
                    phase = phase,
                    session = StudySessionSnapshot(local.request.studySessionId, "Anki review", startedAtEpochMs = now),
                    anki = local.copy(commit = AnkiRatingCommit(record.toRequest(), record.status,
                        attempt = record.attemptCount.coerceAtLeast(1),
                        failureCategory = record.failure?.category),
                        turn = restoredTurn,
                        restoredCommit = true,
                        blockedByPriorCommit = record.sessionId != local.request.studySessionId,
                        // PART V: the durable scan overrides whatever this session believed. A
                        // disagreement is recorded, never silently reconciled in favour of the UI.
                        projectionMismatch = CommitTruthDiagnostics.detectMismatch(
                            projected = local.commit?.status, durable = record.status,
                            commitId = record.commitId,
                            source = CommitProjectionMismatch.SOURCE_RESTORE)),
                    error = SessionProblemHolder(when {
                        pendingWrite -> SessionProblem.ANKI_COMMIT_PERSISTENCE_FAILURE
                        unresolved -> SessionProblem.ANKI_RATING_UNCONFIRMED
                        else -> SessionProblem.ANKI_RATING_NOT_SAVED
                    }, when {
                        pendingWrite -> "The rating may already have been saved in Anki. " +
                            "Study-Agent will not submit it again until the review state can be verified."
                        unresolved -> "The rating may already have been saved in Anki. " +
                            "Study-Agent will not submit it again until the review state can be verified."
                        else -> "The rating was not saved. Nothing was changed in Anki."
                    },
                        true, now)
                )) // Critically: no Begin/Next/Commit effect while recovery is unresolved.
            }
            is AnkiStudyEvent.Scheduled -> {
                if (event.epoch != state.epoch || state.phase != SessionPhase.WaitingForFirstCard ||
                    local.reviewSession == null || local.turn != null) return reject("stale-anki-scheduler")
                when (val result = event.result) {
                    is NextCardResult.Failure -> failed(result.error)
                    is NextCardResult.BackendUnavailable -> failed(result.error)
                    NextCardResult.Finished -> moved(state.copy(phase = SessionPhase.Finished,
                        anki = local.copy(completion = AnkiStudyCompletion.NO_DUE_CARDS)))
                    is NextCardResult.Card -> {
                        val turn = result.turn
                        val context = local.reviewSession.context
                        if (turn.studySessionId != context.studySessionId || turn.backendId != context.backendId ||
                            turn.scheduledCard.deckRef != context.deckRef ||
                            turn.cardRef.collectionKey != context.deckRef?.collectionKey) return failed(AnkiError.SessionInvalid())
                        // A new presentation starts a new transaction: the previous (COMMITTED)
                        // commit is gone, so a late duplicate result for it is stale by identity.
                        // Any recorded divergence belonged to that transaction and retires with it.
                        moved(state.copy(anki = local.copy(
                            turn = turn,
                            commit = null,
                            transcript = null,
                            turnPresentedAtMs = null,
                            failure = null,
                            projectionMismatch = null,
                            revealState = AnswerRevealState.HIDDEN,
                            compareMode = local.request.defaultCompareMode,
                            evaluation = null,
                            evaluationStatus = AnswerEvaluationStatus.NOT_REQUESTED,
                            evaluationFailureReason = null,
                            activeEvaluationRequestId = null,
                            audioSequencePhase = AnswerAudioSequencePhase.IDLE,
                            renderFallbackReason = null,
                            showRawReferenceAnswer = false
                        )),
                            listOf(AnkiStudyEffect.Hydrate(state.epoch, turn)))
                    }
                }
            }
            is AnkiStudyEvent.Hydrated -> {
                val turn = local.turn ?: return reject("no-scheduled-turn")
                if (event.epoch != state.epoch || event.turnId != turn.turnId ||
                    state.phase != SessionPhase.WaitingForFirstCard || state.cardTurn != null) return reject("stale-anki-hydration")
                val attached = when (val result = event.result) {
                    is AnkiResult.Failure -> return failed(result.error)
                    is AnkiResult.Success -> AnkiCardHydration.attach(turn, result.value)
                }
                when (attached) {
                    is AnkiResult.Failure -> failed(attached.error)
                    is AnkiResult.Success -> {
                        val card = checkNotNull(attached.value.renderedCard)
                        if (card.questionText.isNullOrBlank() && card.questionHtml.isNullOrBlank()) return failed(
                            AnkiError.MalformedResponse("empty_question"))
                        val speak = local.request.speakQuestion && !card.questionText.isNullOrBlank()
                        // Reuse generic question setup (not a server callback); preserve scheduler turn ID.
                        val ready = handleQuestion(state, StudyEvent.ServerQuestionReceived(
                            state.sessionId, turn.cardRef.stableKey, card.questionText.orEmpty(), turn.position,
                            turn.remaining, speak, null, turn.turnId.value
                        ), now, preserveServerTurnId = true)
                        ready.copy(newState = ready.newState.copy(
                            anki = local.copy(turn = attached.value, turnPresentedAtMs = now),
                            activeSpeechEffectId = if (speak) ready.newState.activeSpeechEffectId else null
                        ), effects = ready.effects.filterNot {
                            // Voice-disabled/manual path never opens a microphone automatically.
                            !speak && it is StudyEffect.Voice.StartRecognition
                        })
                    }
                }
            }
            is StudyEvent.QuestionSpeechCompleted -> {
                if (state.phase != SessionPhase.SpeakingQuestion || event.effectId != state.activeSpeechEffectId ||
                    event.cardId != state.currentCardId) return reject("stale-anki-speech")
                val id = if (event.success) EffectIds.next("anki-listen") else null
                moved(state.copy(phase = SessionPhase.WaitingForAnswer, activeSpeechEffectId = null,
                    activeRecognitionEffectId = id,
                    error = if (event.success) null else SessionProblemHolder(
                        SessionProblem.TTS_UNAVAILABLE, "Question speech failed; read or reveal the card.", true, now)
                ), if (id == null) emptyList() else listOf(
                    StudyEffect.Voice.StartRecognition(RecognitionPurpose.ANSWER, event.cardId, id)))
            }
            is StudyEvent.RecognitionCompleted -> {
                if (state.phase != SessionPhase.WaitingForAnswer || event.turnId != local.turn?.turnId?.value ||
                    event.cardId != state.currentCardId || event.requestId == null ||
                    event.requestId != state.activeRecognitionEffectId || event.isCommand || event.transcript.isBlank()) {
                    reject("stale-or-empty-anki-transcript")
                } else submitAnswer(event.transcript)
            }
            is StudyEvent.RecognitionFailed -> {
                if (state.phase != SessionPhase.WaitingForAnswer || event.requestId == null ||
                    event.requestId != state.activeRecognitionEffectId) reject("stale-anki-recognition-failure")
                else moved(state.copy(activeRecognitionEffectId = null, error = SessionProblemHolder(
                    SessionProblem.RECOGNIZER_UNAVAILABLE, "Recognition failed; repeat or reveal the card.", true, now)))
            }
            is StudyEvent.UserSubmitAnswer -> {
                if (state.phase !in setOf(SessionPhase.SpeakingQuestion, SessionPhase.WaitingForAnswer) ||
                    event.cardId != state.currentCardId || event.transcript.isBlank()) {
                    reject("illegal-anki-answer")
                } else submitAnswer(event.transcript)
            }
            is StudyEvent.UserRequestAnswer -> {
                if (event.cardId != null && event.cardId != state.currentCardId) reject("stale-card")
                else reveal(cancelEval = state.phase == SessionPhase.WaitingForEvaluation)
            }
            is StudyEvent.RevealAnswerRequested -> {
                val turn = local.turn ?: return reject("no-turn")
                if ((event.epoch != null && event.epoch != state.epoch) ||
                    (event.turnId != null && event.turnId != turn.turnId) ||
                    (event.cardId != null && event.cardId != state.currentCardId)
                ) {
                    reject("stale-anki-reveal")
                } else {
                    reveal(cancelEval = state.phase == SessionPhase.WaitingForEvaluation)
                }
            }
            is AnkiStudyEvent.AnswerEvaluationCompleted -> {
                val turn = local.turn
                if (event.epoch != state.epoch ||
                    event.sessionId != local.request.studySessionId ||
                    turn == null ||
                    event.turnId != turn.turnId ||
                    event.cardRef != turn.cardRef ||
                    local.activeEvaluationRequestId == null ||
                    event.requestId != local.activeEvaluationRequestId ||
                    state.phase != SessionPhase.WaitingForEvaluation
                ) {
                    reject("stale-anki-evaluation")
                } else {
                    val eval = event.evaluation
                    val card = turn.renderedCard
                    val autoFallbackToClean = card?.answerHtml == null && card?.answerText != null &&
                        local.compareMode == AnswerCompareMode.ORIGINAL
                    val speakFb = event.speakFeedback && local.request.speakFeedback && eval.shortFeedback.isNotBlank()
                    val fbId = if (speakFb) EffectIds.next("anki-feedback") else null
                    val nextPhase = if (speakFb) SessionPhase.SpeakingFeedback else SessionPhase.WaitingForRating
                    val audioPhase = if (speakFb) AnswerAudioSequencePhase.SPEAKING_FEEDBACK else AnswerAudioSequencePhase.VISUAL_REVEALED
                    val effects = if (fbId != null) {
                        cancelVoice + StudyEffect.Voice.Speak(
                            speechRequestForFeedback(state.currentCardId ?: "", eval.shortFeedback),
                            fbId
                        )
                    } else {
                        cancelVoice
                    }
                    moved(state.copy(
                        phase = nextPhase,
                        anki = local.copy(
                            revealState = AnswerRevealState.REVEALED,
                            compareMode = if (autoFallbackToClean) AnswerCompareMode.CLEAN else local.compareMode,
                            renderFallbackReason = if (autoFallbackToClean) "html_unavailable" else local.renderFallbackReason,
                            evaluation = eval,
                            evaluationStatus = AnswerEvaluationStatus.COMPLETED,
                            evaluationFailureReason = null,
                            activeEvaluationRequestId = null,
                            audioSequencePhase = audioPhase
                        ),
                        cardTurn = state.cardTurn?.withEvaluation(eval)?.withAnswerRevealed(),
                        session = state.session?.copy(lastEvaluation = eval),
                        activeSpeechEffectId = fbId,
                        activeRecognitionEffectId = null,
                        error = null
                    ), effects)
                }
            }
            is AnkiStudyEvent.AnswerEvaluationFailed -> {
                val turn = local.turn
                if (event.epoch != state.epoch ||
                    event.sessionId != local.request.studySessionId ||
                    turn == null ||
                    event.turnId != turn.turnId ||
                    event.cardRef != turn.cardRef ||
                    local.activeEvaluationRequestId == null ||
                    event.requestId != local.activeEvaluationRequestId ||
                    state.phase != SessionPhase.WaitingForEvaluation
                ) {
                    reject("stale-anki-evaluation")
                } else {
                    val card = turn.renderedCard
                    val autoFallbackToClean = card?.answerHtml == null && card?.answerText != null &&
                        local.compareMode == AnswerCompareMode.ORIGINAL
                    // STEP 23: Keep user answer, reveal reference answer, allow manual rating,
                    // show non-blocking status, do not fail the review turn.
                    moved(state.copy(
                        phase = SessionPhase.WaitingForRating,
                        anki = local.copy(
                            revealState = AnswerRevealState.REVEALED,
                            compareMode = if (autoFallbackToClean) AnswerCompareMode.CLEAN else local.compareMode,
                            renderFallbackReason = if (autoFallbackToClean) "html_unavailable" else local.renderFallbackReason,
                            evaluationStatus = AnswerEvaluationStatus.UNAVAILABLE,
                            evaluationFailureReason = event.reason,
                            activeEvaluationRequestId = null,
                            audioSequencePhase = AnswerAudioSequencePhase.VISUAL_REVEALED
                        ),
                        cardTurn = state.cardTurn?.withAnswerRevealed(),
                        activeSpeechEffectId = null,
                        activeRecognitionEffectId = null,
                        error = null
                    ), cancelVoice)
                }
            }
            is StudyEvent.FeedbackSpeechCompleted -> {
                if (state.phase !in setOf(SessionPhase.SpeakingFeedback, SessionPhase.WaitingForRating, SessionPhase.ShowingAnswer) ||
                    state.activeSpeechEffectId == null ||
                    event.effectId != state.activeSpeechEffectId ||
                    event.cardId != state.currentCardId
                ) {
                    reject("stale-anki-speech")
                } else {
                    val openRatingMic = state.phase == SessionPhase.SpeakingFeedback &&
                        event.success && local.request.speakQuestion
                    val listenId = if (openRatingMic) EffectIds.next("anki-rate-listen") else null
                    moved(state.copy(
                        phase = SessionPhase.WaitingForRating,
                        anki = local.copy(
                            audioSequencePhase = if (listenId != null) {
                                AnswerAudioSequencePhase.LISTENING_FOR_RATING
                            } else {
                                AnswerAudioSequencePhase.IDLE
                            }
                        ),
                        activeSpeechEffectId = null,
                        activeRecognitionEffectId = listenId,
                        error = if (event.success) state.error else SessionProblemHolder(
                            SessionProblem.TTS_UNAVAILABLE,
                            "Feedback speech failed; review the feedback on screen and rate the card.",
                            true,
                            now
                        )
                    ), if (listenId == null) emptyList() else listOf(
                        StudyEffect.Voice.StartRecognition(RecognitionPurpose.RATING, event.cardId, listenId)
                    ))
                }
            }
            is StudyEvent.AnswerSpeechCompleted -> {
                if (state.phase !in setOf(SessionPhase.WaitingForRating, SessionPhase.ShowingAnswer, SessionPhase.SpeakingFeedback) ||
                    state.activeSpeechEffectId == null ||
                    event.effectId != state.activeSpeechEffectId ||
                    event.cardId != state.currentCardId
                ) {
                    reject("stale-anki-speech")
                } else {
                    moved(state.copy(
                        phase = SessionPhase.WaitingForRating,
                        anki = local.copy(audioSequencePhase = AnswerAudioSequencePhase.IDLE),
                        activeSpeechEffectId = null,
                        error = if (event.success) state.error else SessionProblemHolder(
                            SessionProblem.TTS_UNAVAILABLE,
                            "Answer speech failed; read the reference answer on screen.",
                            true,
                            now
                        )
                    ))
                }
            }
            is StudyEvent.RepeatAnswerRequested -> {
                val turn = local.turn ?: return reject("no-turn")
                if ((event.epoch != null && event.epoch != state.epoch) ||
                    (event.turnId != null && event.turnId != turn.turnId) ||
                    (event.cardId != null && event.cardId != state.currentCardId)
                ) return reject("stale-anki-repeat-answer")
                if (local.revealState != AnswerRevealState.REVEALED ||
                    state.phase !in setOf(SessionPhase.WaitingForRating, SessionPhase.SpeakingFeedback, SessionPhase.ShowingAnswer)
                ) return reject("answer-not-revealed")
                val answerText = turn.renderedCard?.answerText?.takeIf { it.isNotBlank() }
                    ?: return reject("no-answer-speech-text")
                val id = EffectIds.next("anki-answer")
                moved(state.copy(
                    phase = SessionPhase.WaitingForRating,
                    anki = local.copy(audioSequencePhase = AnswerAudioSequencePhase.SPEAKING_ANSWER),
                    activeSpeechEffectId = id,
                    activeRecognitionEffectId = null
                ), cancelVoice + StudyEffect.Voice.Speak(speechRequestForAnswer(state.currentCardId ?: "", answerText), id))
            }
            is StudyEvent.RepeatFeedbackRequested -> {
                val turn = local.turn ?: return reject("no-turn")
                if ((event.epoch != null && event.epoch != state.epoch) ||
                    (event.turnId != null && event.turnId != turn.turnId) ||
                    (event.cardId != null && event.cardId != state.currentCardId)
                ) return reject("stale-anki-repeat-feedback")
                if (local.revealState != AnswerRevealState.REVEALED ||
                    state.phase !in setOf(SessionPhase.WaitingForRating, SessionPhase.SpeakingFeedback, SessionPhase.ShowingAnswer)
                ) return reject("answer-not-revealed")
                val feedbackText = (local.evaluation ?: state.cardTurn?.evaluation)?.shortFeedback?.takeIf { it.isNotBlank() }
                    ?: return reject("no-feedback-speech-text")
                val id = EffectIds.next("anki-feedback")
                moved(state.copy(
                    phase = SessionPhase.WaitingForRating,
                    anki = local.copy(audioSequencePhase = AnswerAudioSequencePhase.SPEAKING_FEEDBACK),
                    activeSpeechEffectId = id,
                    activeRecognitionEffectId = null
                ), cancelVoice + StudyEffect.Voice.Speak(speechRequestForFeedback(state.currentCardId ?: "", feedbackText), id))
            }
            is StudyEvent.SelectAnswerCompareMode -> {
                val turn = local.turn ?: return reject("no-turn")
                if ((event.epoch != null && event.epoch != state.epoch) ||
                    (event.turnId != null && event.turnId != turn.turnId)
                ) reject("stale-anki-compare-mode")
                else moved(state.copy(anki = local.copy(compareMode = event.mode)))
            }
            is StudyEvent.SetShowRawReferenceAnswer -> {
                val turn = local.turn ?: return reject("no-turn")
                if ((event.epoch != null && event.epoch != state.epoch) ||
                    (event.turnId != null && event.turnId != turn.turnId)
                ) reject("stale-anki-raw-reference")
                else moved(state.copy(anki = local.copy(showRawReferenceAnswer = event.showRaw)))
            }
            is StudyEvent.AnswerRenderFallbackTriggered -> {
                val turn = local.turn ?: return reject("no-turn")
                if ((event.epoch != null && event.epoch != state.epoch) ||
                    (event.sessionId != null && event.sessionId != local.request.studySessionId) ||
                    event.turnId != turn.turnId ||
                    (event.cardRef != null && event.cardRef != turn.cardRef) ||
                    local.revealState != AnswerRevealState.REVEALED
                ) {
                    reject("stale-anki-render-callback")
                } else {
                    val hasCleanText = turn.renderedCard?.answerText != null
                    val nextMode = if (local.compareMode == AnswerCompareMode.ORIGINAL && hasCleanText) {
                        AnswerCompareMode.CLEAN
                    } else {
                        local.compareMode
                    }
                    moved(state.copy(
                        anki = local.copy(
                            compareMode = nextMode,
                            renderFallbackReason = event.reason
                        )
                    ))
                }
            }
            is AnkiStudyEvent.SelectRating -> selectRating(state, local, event, now, cancelVoice)
            is AnkiStudyEvent.ReviewerActionRequested -> reviewerActionRequested(state, local, event)
            is AnkiStudyEvent.ReviewerActionApplied -> reviewerActionApplied(state, local, event, cancelVoice)
            is AnkiStudyEvent.ReviewerActionRejected -> reviewerActionResultFailure(state, local, event)
            is AnkiStudyEvent.ReviewerActionAmbiguous -> reviewerActionUnresolved(state, local, event, now)
            is AnkiStudyEvent.RatingCommitPrepared -> {
                val commit = local.commit
                if (event.epoch != state.epoch || commit == null || commit.commitId != event.commitId) {
                    reject("stale-anki-commit-prepared")
                } else moved(state.copy(anki = local.copy(commit = commit.copy(
                    guaranteeLevel = event.guaranteeLevel ?: commit.guaranteeLevel))))
            }
            is AnkiStudyEvent.RatingCommitStarted -> {
                val commit = local.commit
                // The interaction projection mirrors the durable status: an attempt that has been
                // claimed but has not entered the mutation boundary is still PREPARED (GATE 11B
                // §28). "An attempt is running" is the turn-scoped phase, not a second status.
                if (event.epoch != state.epoch || commit == null || commit.commitId != event.commitId ||
                    state.phase != SessionPhase.SubmittingRating || commit.status != ReviewCommitStatus.PREPARED ||
                    event.attempt != commit.attempt
                ) reject("stale-anki-commit-start")
                else moved(state.copy(anki = local.copy(commit = commit.copy(
                    attempt = event.attempt,
                    guaranteeLevel = event.guaranteeLevel ?: commit.guaranteeLevel))))
            }
            is AnkiStudyEvent.RatingCommitResolved ->
                resolveAnkiCommit(state, local, event, event.epoch, event.commitId, event.outcome, reconciliation = false, now = now)
            is AnkiStudyEvent.RatingCommitReconciled ->
                resolveAnkiCommit(state, local, event, event.epoch, event.commitId, event.outcome, reconciliation = true, now = now)
            is AnkiStudyEvent.RetryRatingCommit -> {
                val commit = local.commit
                if (event.epoch != state.epoch || commit == null || commit.commitId != event.commitId) {
                    reject("stale-anki-retry")
                } else {
                    val current = commit
                    when {
                        // GATE 11D §24 / INV-11D-06 — only proven-safe durable states may expose
                        // a mutation retry: RETRY_ALLOWED (proven not applied) and a *restored*
                        // PREPARED (provably un-entered — the durable status alone says so).
                        // SUBMITTING and AMBIGUOUS never offer a retry, restored or not; the
                        // retry is always explicit user intent, never automatic.
                        state.phase != SessionPhase.RatingCommitFailed ||
                            !(current.status == ReviewCommitStatus.RETRY_ALLOWED ||
                                (local.restoredCommit && current.status == ReviewCommitStatus.PREPARED)) ->
                            reject("illegal-phase-for-anki-retry")
                        // INV-11D-07 / GATE 11D §27-28: the retry must target the *same* review
                        // turn (the commit id embeds the turn id). A live turn or a turn
                        // re-attached from the durable record is fine; no matching turn is not.
                        local.turn == null || local.turn.turnId != current.commitId.turnId ->
                            reject("anki-retry-not-safe")
                        else -> moved(state.copy(
                            phase = SessionPhase.SubmittingRating,
                            anki = local.copy(commit = current.copy(status = ReviewCommitStatus.PREPARED,
                                attempt = current.attempt + 1, failureCategory = null)),
                            error = null
                        ), listOf(AnkiStudyEffect.CommitRating(state.epoch, current.request, retry = true)))
                    }
                }
            }
            is AnkiStudyEvent.ReconcileRatingCommit -> {
                val commit = local.commit
                when {
                    event.epoch != state.epoch || commit == null || commit.commitId != event.commitId ->
                        reject("stale-anki-reconcile")
                    !(state.phase == SessionPhase.ReconciliationRequired && commit.status == ReviewCommitStatus.AMBIGUOUS ||
                        state.phase == SessionPhase.CommitPersistenceFailure) ->
                        reject("illegal-phase-for-anki-reconcile")
                    commit.reconciling -> reject("anki-reconcile-in-flight")
                    else -> moved(state.copy(anki = local.copy(commit = commit.copy(reconciling = true))),
                        listOf(AnkiStudyEffect.ReconcileCommit(state.epoch, commit.commitId)))
                }
            }
            is AnkiStudyEvent.RetryNextCard -> {
                val session = local.reviewSession
                val commit = local.commit
                // Read-only retry: allowed only when no turn is open and nothing is unresolved, so
                // it can never replay a rating (a COMMITTED commit is never re-sent).
                // GATE 11E PART I §6 — the barrier is the shared rule, not a local comparison.
                if (event.epoch != state.epoch || session == null || state.phase !is SessionPhase.Error ||
                    local.turn != null || (commit != null && !nextCardAllowed(commit.status))
                ) reject("illegal-anki-next-retry")
                else moved(state.copy(phase = SessionPhase.WaitingForFirstCard, error = null,
                    anki = local.copy(failure = null)), listOf(AnkiStudyEffect.Next(state.epoch, session)))
            }
            is StudyEvent.UserRateCard -> {
                val turn = local.turn ?: return reject("no-turn")
                if (event.cardId != state.currentCardId) reject("stale-card") else reduceAnkiInteraction(
                    state, AnkiStudyEvent.SelectRating(state.epoch, turn.turnId, event.rating), now)
            }
            is StudyEvent.UserRequestRepeat -> {
                if (event.cardId != state.currentCardId || state.phase !in setOf(
                        SessionPhase.SpeakingQuestion, SessionPhase.WaitingForAnswer)) reject("illegal-repeat") else speak()
            }
            is StudyEvent.UserPauseRequested -> {
                if (state.phase !in setOf(
                        SessionPhase.SpeakingQuestion, SessionPhase.WaitingForAnswer,
                        SessionPhase.SpeakingFeedback, SessionPhase.ShowingAnswer,
                        SessionPhase.WaitingForRating)) return reject("illegal-pause")
                moved(state.copy(phase = SessionPhase.Paused,
                    anki = local.copy(audioSequencePhase = AnswerAudioSequencePhase.IDLE),
                    pauseContext = ResumeContext(state.epoch, state.phase, state.cardTurn, capturedAtMs = now),
                    activeSpeechEffectId = null, activeRecognitionEffectId = null), cancelVoice)
            }
            is StudyEvent.UserResumeRequested -> {
                if (state.phase != SessionPhase.Paused) reject("not-paused") else moved(state.copy(
                    phase = if (local.revealState == AnswerRevealState.REVEALED || state.cardTurn?.answerRevealed == true)
                        SessionPhase.WaitingForRating
                    else SessionPhase.WaitingForAnswer,
                    anki = local.copy(audioSequencePhase = AnswerAudioSequencePhase.IDLE),
                    pauseContext = null
                )) // Safe manual restart; explicit Repeat can restart audio without querying Anki.
            }
            // Ending never cancels an in-flight commit (it runs in its own job, is persisted, and its
            // late result is rejected as terminal/stale) and never sends another mutation.
            is StudyEvent.UserEndRequested -> moved(state.copy(
                phase = SessionPhase.Finished,
                anki = local.copy(
                    completion = AnkiStudyCompletion.USER_ENDED,
                    activeEvaluationRequestId = null,
                    audioSequencePhase = AnswerAudioSequencePhase.IDLE
                ),
                activeSpeechEffectId = null, activeRecognitionEffectId = null
            ), cancelVoice + AnkiStudyEffect.CancelReads +
                listOfNotNull(local.reviewSession?.let { AnkiStudyEffect.EndReview(it) }))
            is StudyEvent.UserStopSpeaking -> {
                when (state.phase) {
                    SessionPhase.SpeakingQuestion -> moved(state.copy(
                        phase = SessionPhase.WaitingForAnswer, activeSpeechEffectId = null,
                        activeRecognitionEffectId = null), cancelVoice)
                    SessionPhase.SpeakingFeedback,
                    SessionPhase.ShowingAnswer -> moved(state.copy(
                        phase = SessionPhase.WaitingForRating,
                        anki = local.copy(audioSequencePhase = AnswerAudioSequencePhase.IDLE),
                        activeSpeechEffectId = null,
                        activeRecognitionEffectId = null), cancelVoice)
                    else -> reject("not-speaking")
                }
            }
            is StudyEvent.ConnectionLost, is StudyEvent.ConnectionRestored,
            is StudyEvent.SettingsChanged, StudyEvent.UiRecreated -> Transition(state)
            // Fail closed: no server reconciliation, remote ratings, skip or uncorrelated callbacks.
            else -> reject("unsupported-anki-interaction")
        }
    }

    /**
     * GATE 11 — RatingSelected → CommitPrepared in one pure step: the first accepted rating becomes
     * the turn's immutable commit request and the machine enters SubmittingRating. Touch, voice,
     * headset and keyboard all arrive here as the same event, so any later rating — identical or
     * different — is rejected ("first accepted rating wins"). The reducer never calls a backend and
     * never advances: the next card is requested only from a COMMITTED outcome.
     */
    /**
     * GATE 13 STEP 16/21/22/24-29 — the one policy gate for a reviewer-action request.
     *
     * The reducer owns the decision (not the UI): a request is correlated with the authoritative
     * turn, the phase must present that turn, and [ReviewerActionPolicy] decides whether the action
     * may be dispatched at all. An allowed action becomes exactly one
     * [AnkiStudyEffect.PerformReviewerAction] — every backend call goes through that effect, so a
     * Compose callback or a voice command can never reach the provider directly (INV-13-05).
     * A blocked request is a *presentation* change (the user is told why) with no effect at all.
     */
    private fun reviewerActionRequested(
        state: SessionMachineState,
        local: AnkiStudyInteraction,
        event: AnkiStudyEvent.ReviewerActionRequested
    ): Transition {
        val turn = local.turn
        if (turn == null ||
            (event.epoch != null && event.epoch != state.epoch) ||
            (event.turnId != null && event.turnId != turn.turnId) ||
            (event.cardId != null && event.cardId != state.currentCardId)
        ) {
            return Transition.reject(state, event, "stale-anki-reviewer-action")
        }
        if (state.phase !in REVIEWER_ACTION_PHASES) {
            return Transition.reject(state, event, "illegal-phase-for-anki-reviewer-action")
        }
        val decision = ReviewerActionPolicy.decide(
            action = event.action,
            capabilities = local.request.reviewerActions,
            commitStatus = local.commit?.status,
            actionInFlight = local.reviewerActionInFlight,
            actionOutcomeUnresolved = local.reviewerActionUnresolved,
            turnResolved = local.ratingResolved
        )
        return when (decision) {
            is ReviewerActionDecision.Allowed -> moved(
                state.copy(anki = local.copy(
                    reviewerAction = ReviewerActionUiState.Applying(event.action)
                )),
                listOf(AnkiStudyEffect.PerformReviewerAction(state.epoch, turn.turnId, turn.cardRef, event.action))
            )
            // Not an error state: nothing was sent, nothing changed, and the next request is
            // re-evaluated from scratch (STEP 24-29).
            is ReviewerActionDecision.Blocked -> moved(state.copy(anki = local.copy(
                reviewerAction = ReviewerActionUiState.Blocked(event.action, decision.reason, decision.detail)
            )))
        }
    }

    /**
     * GATE 13 STEP 17/18/19/35/36/37/38 — the one place a confirmed reviewer action is applied.
     *
     * **Flag** keeps the turn: same [ReviewTurnId], same card, same phase, same transcript,
     * reveal state, compare mode, evaluation and audio — only the flag projection (when the backend
     * can report one) and the action state change. No scheduler query is emitted (STEP 36).
     *
     * **Bury / Suspend** invalidate the current turn only *after* this confirmed result: the turn is
     * closed without a rating, no `ReviewCommitStatus` is created (INV-13-02/11), and a **fresh
     * scheduler query** ([AnkiStudyEffect.Next]) is emitted — never a preselected next card
     * (STEP 12/35, INV-13-09/10/12).
     */
    private fun reviewerActionApplied(
        state: SessionMachineState,
        local: AnkiStudyInteraction,
        event: AnkiStudyEvent.ReviewerActionApplied,
        cancelVoice: List<StudyEffect>
    ): Transition {
        val turn = local.turn
        if (turn == null || event.epoch != state.epoch ||
            event.turnId != turn.turnId || event.cardRef != turn.cardRef
        ) {
            return Transition.reject(state, event, "stale-anki-action-result")
        }
        val applied = (local.reviewerAction as? ReviewerActionUiState.Applying)?.action
        if (applied == null || applied != event.action) {
            return Transition.reject(state, event, "unrequested-anki-action-result")
        }
        if (!event.action.invalidatesTurn) {
            // Minimal projection (STEP 38): the flag itself cannot be read back from the pinned
            // contract, so the projection only applies when the backend reported one — the action
            // carries the value it successfully set. Nothing else about the turn moves.
            val flag = (event.action as? ReviewerAction.SetFlag)?.flag
            val projectedTurn = flag?.let { value -> turn.withFlag(value) } ?: turn
            return moved(state.copy(anki = local.copy(
                turn = projectedTurn,
                reviewerAction = ReviewerActionUiState.Idle
            )))
        }
        val session = local.reviewSession
            ?: return Transition.reject(state, event, "no-anki-review-session")
        // The turn ends here. `commit` stays untouched on purpose: bury/suspend are not ratings,
        // so nothing is created, promoted or recorded (INV-13-02/11).
        return Transition(
            state.copy(
                phase = SessionPhase.WaitingForFirstCard,
                cardTurn = null,
                anki = local.copy(
                    turn = null,
                    transcript = null,
                    turnPresentedAtMs = null,
                    revealState = AnswerRevealState.HIDDEN,
                    compareMode = local.request.defaultCompareMode,
                    evaluation = null,
                    evaluationStatus = AnswerEvaluationStatus.NOT_REQUESTED,
                    evaluationFailureReason = null,
                    activeEvaluationRequestId = null,
                    audioSequencePhase = AnswerAudioSequencePhase.IDLE,
                    renderFallbackReason = null,
                    showRawReferenceAnswer = false,
                    reviewerAction = ReviewerActionUiState.Idle,
                    failure = null
                ),
                activeSpeechEffectId = null,
                activeRecognitionEffectId = null,
                error = null
            ).recordTransition(event, state.phase, SessionPhase.WaitingForFirstCard),
            // Ordering is the contract (INV-13-12): the scheduler is asked only now, after the
            // backend confirmed the mutation — never before, never from a local guess.
            cancelVoice + AnkiStudyEffect.Next(state.epoch, session)
        )
    }

    /**
     * GATE 13 STEP 39 — proven not applied. The same turn continues with no scheduler progression;
     * the failure is surfaced as presentation state and the user may try again (the pin's
     * scheduler-level idempotency makes a repeated bury/suspend harmless).
     */
    private fun reviewerActionResultFailure(
        state: SessionMachineState,
        local: AnkiStudyInteraction,
        event: AnkiStudyEvent.ReviewerActionRejected
    ): Transition {
        val turn = local.turn
        if (turn == null || event.epoch != state.epoch ||
            event.turnId != turn.turnId || event.cardRef != turn.cardRef
        ) {
            return Transition.reject(state, event, "stale-anki-action-result")
        }
        val applied = (local.reviewerAction as? ReviewerActionUiState.Applying)?.action
        if (applied == null || applied != event.action) {
            return Transition.reject(state, event, "unrequested-anki-action-result")
        }
        return moved(state.copy(anki = local.copy(
            reviewerAction = ReviewerActionUiState.Failed(event.action, event.reason)
        )))
    }

    /**
     * GATE 13 STEP 31/40 — the action may have applied. The turn does **not** progress, nothing is
     * replayed, and rating is blocked by [ReviewerActionPolicy.ratingBlockReason] until an explicit
     * session restart resolves the state (INV-13-13). The phase deliberately stays put: claiming
     * "buried" or "not buried" here would be a guess.
     */
    private fun reviewerActionUnresolved(
        state: SessionMachineState,
        local: AnkiStudyInteraction,
        event: AnkiStudyEvent.ReviewerActionAmbiguous,
        now: Long
    ): Transition {
        val turn = local.turn
        if (turn == null || event.epoch != state.epoch ||
            event.turnId != turn.turnId || event.cardRef != turn.cardRef
        ) {
            return Transition.reject(state, event, "stale-anki-action-result")
        }
        val applied = (local.reviewerAction as? ReviewerActionUiState.Applying)?.action
        if (applied == null || applied != event.action) {
            return Transition.reject(state, event, "unrequested-anki-action-result")
        }
        return moved(state.copy(
            anki = local.copy(
                reviewerAction = ReviewerActionUiState.VerificationRequired(
                    event.action, event.reason, event.detail
                )
            ),
            error = SessionProblemHolder(
                SessionProblem.ANKI_REVIEWER_ACTION_UNCONFIRMED,
                "Study-Agent could not confirm whether this action was applied in Anki, so it " +
                    "will not repeat it. End the session to restart safely.",
                false, now
            )
        ))
    }

    private fun selectRating(
        state: SessionMachineState,
        local: AnkiStudyInteraction,
        event: AnkiStudyEvent.SelectRating,
        now: Long,
        cancelVoice: List<StudyEffect>
    ): Transition {
        val turn = local.turn
        val session = local.reviewSession
        val existing = local.commit
        if (event.epoch != state.epoch || turn == null || event.turnId != turn.turnId) {
            return Transition.reject(state, event, "stale-anki-rating")
        }
        if (existing != null) {
            return Transition.reject(state, event,
                if (existing.rating == event.rating) "duplicate-anki-rating" else "anki-rating-locked-first-wins")
        }
        if (state.phase !in setOf(SessionPhase.WaitingForRating, SessionPhase.SpeakingFeedback, SessionPhase.ShowingAnswer) ||
            local.revealState != AnswerRevealState.REVEALED
        ) return Transition.reject(state, event, "illegal-phase-for-anki-rating")
        if (session == null) return Transition.reject(state, event, "no-anki-review-session")
        // GATE 13 §40/INV-13-13 — an in-flight reviewer action mutates the same card, and an
        // unresolved one means the scheduler may already have moved it out of the queue. Both make
        // a rating unsafe, so rating fails closed exactly like an unresolved commit blocks the next
        // card. A *rejected* action leaves nothing to protect and does not appear here.
        ReviewerActionPolicy.ratingBlockReason(
            actionInFlight = local.reviewerActionInFlight,
            actionOutcomeUnresolved = local.reviewerActionUnresolved
        )?.let { return Transition.reject(state, event, "anki-rating-blocked-by-${it.name.lowercase()}") }
        val options = turn.ratingOptions
        if (options !is AnkiRatingOptions.Known) return Transition.reject(state, event, "anki-rating-options-unmapped")
        if (!options.supports(event.rating)) return Transition.reject(state, event, "unsupported-anki-rating")
        val request = CommitRatingRequest(
            commitId = turn.commitId,
            card = turn.cardRef,
            rating = event.rating,
            ratedAtEpochMs = now.coerceAtLeast(0L),
            // Question presentation → rating selection, on the machine clock (never fabricated).
            answerDurationMs = local.turnPresentedAtMs?.let { (now - it).coerceAtLeast(0L) },
            deckRef = turn.scheduledCard.deckRef,
            collectionRef = session.context.collection
        )
        val next = state.copy(
            phase = SessionPhase.SubmittingRating,
            // A new transaction supersedes any divergence recorded for an older one.
            anki = local.copy(
                commit = AnkiRatingCommit(request, ReviewCommitStatus.PREPARED),
                projectionMismatch = null,
                audioSequencePhase = AnswerAudioSequencePhase.IDLE
            ),
            error = null,
            activeSpeechEffectId = null,
            activeRecognitionEffectId = null
        ).recordTransition(event, state.phase, SessionPhase.SubmittingRating)
        return Transition(next, cancelVoice + AnkiStudyEffect.CommitRating(state.epoch, request))
    }

    /**
     * GATE 11 — consumes a persisted commit outcome. Correlation first (epoch, commit id, study
     * session, turn): a result for anything else is rejected — the executor has already written it
     * to the ledger, so a stale result updates the ledger but never the current turn. COMMITTED is
     * the only path to the next card, taken exactly once; a duplicate COMMITTED is rejected.
     */
    /**
     * GATE 11D §27-29 — re-attach the presentation identity of the interrupted review turn from
     * the durable record, without any scheduler query (INV-11D-17). The turn id is the one the
     * commit was made under (INV-11D-07), which is what lets a retry reuse the same
     * ReviewCommitId end to end.
     *
     * The content is deliberately honest: scheduler metadata the ledger does not persist (rating
     * options, position, scheduling labels) is marked [AnkiRatingOptions.Unmapped] instead of
     * being fabricated, and the degradations token records the provenance. Nothing about this
     * turn is a scheduler observation — the backend re-validates card, queue and state before any
     * mutation a retry may dispatch.
     *
     * `null` when the durable record cannot carry a deck identity (nothing may be guessed) or
     * when the record is COMMITTED (there is no interrupted turn to restore).
     */
    private fun restoredTurnFor(record: ReviewCommitRecord): AnkiReviewTurn? {
        if (record.status == ReviewCommitStatus.COMMITTED) return null
        val deckRef = record.deckRef ?: return null
        return AnkiReviewTurn(
            turnId = record.turnId,
            studySessionId = record.sessionId,
            content = AnkiReviewTurnContent.Scheduled(
                AnkiScheduledCard(
                    ref = record.card,
                    noteRef = record.card.noteId?.let {
                        AnkiNoteRef(record.backendId, it, record.card.collectionKey)
                    },
                    deckRef = deckRef,
                    ratingOptions = AnkiRatingOptions.Unmapped(0),
                    degradations = listOf("restored_from_ledger")
                )
            )
        )
    }

    private fun resolveAnkiCommit(
        state: SessionMachineState,
        local: AnkiStudyInteraction,
        event: StudyEvent,
        epoch: Long,
        commitId: ReviewCommitId,
        outcome: AnkiCommitOutcome,
        reconciliation: Boolean,
        now: Long
    ): Transition {
        val commit = local.commit
        val turn = local.turn
        val session = local.reviewSession
        if (epoch != state.epoch || commit == null || commit.commitId != commitId ||
            (!local.restoredCommit && (session == null || commitId.studySessionId != session.context.studySessionId))
        ) return Transition.reject(state, event, "stale-anki-commit-result")
        if (commit.status == ReviewCommitStatus.COMMITTED) return Transition.reject(state, event, "duplicate-anki-commit-result")
        // PART V — durable truth adopted here may contradict the projection this session was
        // showing (a restore scan, a ledger replay after process death, or read-only
        // reconciliation). The ledger wins and the disagreement is recorded, never hidden.
        // A persistence failure is deliberately excluded: "could not write" is not a durable status.
        val overrideSource = when {
            outcome is AnkiCommitOutcome.PersistenceFailure -> null
            reconciliation -> CommitProjectionMismatch.SOURCE_RECONCILE
            outcome is AnkiCommitOutcome.Committed && outcome.source != AnkiCommitOutcome.SOURCE_BACKEND_CONFIRMED ->
                CommitProjectionMismatch.SOURCE_REPLAY
            else -> null
        }
        val owner = local.copy(projectionMismatch = local.projectionMismatch ?: overrideSource?.let {
            CommitTruthDiagnostics.detectMismatch(commit.status, outcome.status, commitId, it)
        })
        if (local.restoredCommit) {
            // A process cannot resurrect an AnkiDroid turn handle. Never create a fresh turn or
            // retry the old rating before resolving the durable transaction. Once resolved, begin
            // a *read-only* new scheduler session; never reissue the old CommitRating effect.
            if (reconciliation) {
                if (!commit.reconciling || state.phase !in setOf(
                        SessionPhase.ReconciliationRequired, SessionPhase.CommitPersistenceFailure)) {
                    return Transition.reject(state, event, "unrequested-anki-recovery")
                }
                return when (outcome) {
                    is AnkiCommitOutcome.Committed -> movedRecovery(state, event, local, now)
                    is AnkiCommitOutcome.Failed -> if (local.blockedByPriorCommit) movedRecovery(state, event, local, now)
                        else Transition(state.copy(phase = SessionPhase.RatingCommitFailed,
                            anki = owner.copy(commit = commit.copy(status = ReviewCommitStatus.RETRY_ALLOWED,
                                reconciling = false))), emptyList())
                    is AnkiCommitOutcome.Ambiguous -> Transition(state.copy(phase = SessionPhase.ReconciliationRequired,
                        anki = owner.copy(commit = commit.copy(status = ReviewCommitStatus.AMBIGUOUS,
                            reconciling = false))), emptyList())
                    is AnkiCommitOutcome.PersistenceFailure -> Transition(state.copy(phase = SessionPhase.CommitPersistenceFailure,
                        anki = owner.copy(commit = commit.copy(reconciling = false))), emptyList())
                }
            }
            // GATE 11D §27/§28 — an explicit user retry of a provably safe restored transaction
            // (restored PREPARED or RETRY_ALLOWED, same commit id) was dispatched from
            // SubmittingRating; accept its durable outcome. Anything else arriving for a
            // restored commit is unrequested and rejected.
            if (state.phase == SessionPhase.SubmittingRating && commit.status == ReviewCommitStatus.PREPARED) {
                return when (outcome) {
                    is AnkiCommitOutcome.Committed -> movedRecovery(state, event, local, now)
                    is AnkiCommitOutcome.Failed -> Transition(state.copy(
                        phase = SessionPhase.RatingCommitFailed,
                        anki = owner.copy(commit = commit.copy(status = ReviewCommitStatus.RETRY_ALLOWED,
                            reconciling = false, failureCategory = outcome.category)),
                        error = SessionProblemHolder(SessionProblem.ANKI_RATING_NOT_SAVED,
                            "Anki did not save this rating. Retry the same rating or end the session.",
                            true, now)
                    ), emptyList())
                    is AnkiCommitOutcome.Ambiguous -> Transition(state.copy(
                        phase = SessionPhase.ReconciliationRequired,
                        anki = owner.copy(commit = commit.copy(status = ReviewCommitStatus.AMBIGUOUS,
                            reconciling = false, failureCategory = outcome.category)),
                        error = SessionProblemHolder(SessionProblem.ANKI_RATING_UNCONFIRMED,
                            "The rating may already have been saved in Anki. Study-Agent will not submit it " +
                                "again until the review state can be verified.", true, now)
                    ), emptyList())
                    is AnkiCommitOutcome.PersistenceFailure -> Transition(state.copy(
                        phase = SessionPhase.CommitPersistenceFailure,
                        anki = owner.copy(commit = commit.copy(reconciling = false, failureCategory = outcome.category))
                    ), emptyList())
                }
            }
            return Transition.reject(state, event, "unrequested-anki-recovery")
        }
        if (session == null || turn == null || turn.turnId != commitId.turnId) {
            return Transition.reject(state, event, "stale-anki-commit-result")
        }
        val expected = if (reconciliation) setOf(SessionPhase.ReconciliationRequired, SessionPhase.CommitPersistenceFailure)
            else setOf(SessionPhase.SubmittingRating)
        if (state.phase !in expected) return Transition.reject(state, event, "illegal-phase-for-anki-commit-result")
        if (reconciliation && !commit.reconciling) return Transition.reject(state, event, "unrequested-anki-reconciliation")

        val next = when (outcome) {
            is AnkiCommitOutcome.Committed -> state.copy(
                phase = SessionPhase.WaitingForFirstCard,
                anki = owner.copy(
                    turn = null,
                    transcript = null,
                    turnPresentedAtMs = null,
                    revealState = AnswerRevealState.HIDDEN,
                    compareMode = owner.request.defaultCompareMode,
                    evaluation = null,
                    evaluationStatus = AnswerEvaluationStatus.NOT_REQUESTED,
                    evaluationFailureReason = null,
                    activeEvaluationRequestId = null,
                    audioSequencePhase = AnswerAudioSequencePhase.IDLE,
                    renderFallbackReason = null,
                    showRawReferenceAnswer = false,
                    commit = commit.copy(status = ReviewCommitStatus.COMMITTED, reconciling = false,
                        failureCategory = null,
                        verifiedByReconciliation = reconciliation)
                ),
                cardTurn = null,
                // Counters move only after COMMITTED (never on selection, failure or ambiguity).
                session = state.session?.copy(totalReviewedInSession = state.session.totalReviewedInSession + 1),
                error = null,
                activeSpeechEffectId = null,
                activeRecognitionEffectId = null
            )
            is AnkiCommitOutcome.Failed -> state.copy(
                phase = SessionPhase.RatingCommitFailed,
                anki = owner.copy(commit = commit.copy(
                    status = ReviewCommitStatus.RETRY_ALLOWED,
                    reconciling = false, failureCategory = outcome.category)),
                error = SessionProblemHolder(
                    SessionProblem.ANKI_RATING_NOT_SAVED,
                    "Anki did not save this rating. Retry the same rating or end the session.",
                    true, now
                )
            )
            is AnkiCommitOutcome.Ambiguous -> state.copy(
                phase = SessionPhase.ReconciliationRequired,
                anki = owner.copy(commit = commit.copy(status = ReviewCommitStatus.AMBIGUOUS, reconciling = false,
                    failureCategory = outcome.category)),
                error = SessionProblemHolder(
                    SessionProblem.ANKI_RATING_UNCONFIRMED,
                    "The rating may already have been saved in Anki. Study-Agent will not submit it " +
                        "again until the review state can be verified.",
                    true, now
                )
            )
            is AnkiCommitOutcome.PersistenceFailure -> state.copy(
                phase = SessionPhase.CommitPersistenceFailure,
                anki = owner.copy(commit = commit.copy(reconciling = false, failureCategory = outcome.category)),
                error = SessionProblemHolder(SessionProblem.ANKI_COMMIT_PERSISTENCE_FAILURE,
                    "Study-Agent could not safely record the review result. It will not retry or " +
                        "load another card until the record is saved.", true, now)
            )
        }.recordTransition(event, state.phase, when (outcome) {
            is AnkiCommitOutcome.Committed -> SessionPhase.WaitingForFirstCard
            is AnkiCommitOutcome.Failed -> SessionPhase.RatingCommitFailed
            is AnkiCommitOutcome.Ambiguous -> SessionPhase.ReconciliationRequired
            is AnkiCommitOutcome.PersistenceFailure -> SessionPhase.CommitPersistenceFailure
        })
        // GATE 11E PART I §6 / INV-11E-18 — the single authoritative next-card rule. An outcome
        // may only advance the session when the durable status it reflects allows it, and no
        // second copy of that rule exists anywhere in the app.
        val effects = if (outcome.allowsNextCard()) listOf(AnkiStudyEffect.Next(state.epoch, session)) else emptyList()
        return Transition(next, effects)
    }

    /** Re-enter the scheduler only after a restored blocker has been durably resolved. */
    private fun movedRecovery(
        state: SessionMachineState, event: StudyEvent, local: AnkiStudyInteraction, now: Long
    ): Transition = Transition(state.copy(
        phase = SessionPhase.Starting,
        anki = local.copy(commit = null, restoredCommit = false, blockedByPriorCommit = false,
            // The transaction is resolved and the durable record now leads a fresh read-only
            // scheduler session, so the recorded divergence has been acted on and is retired.
            failure = null, priorUnresolvedCommits = 0, projectionMismatch = null),
        error = null
    ).recordTransition(event, state.phase, SessionPhase.Starting),
        listOf(AnkiStudyEffect.Begin(state.epoch, local.request, now)))

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

    private fun speechRequestForAnswer(cardId: String, text: String): SpeechRequest =
        SpeechRequest(
            id = SpeechIds.forPurpose(SpeechPurpose.ANSWER, cardId),
            text = text,
            purpose = SpeechPurpose.ANSWER,
            priority = SpeechPriority.NORMAL,
            queuePolicy = QueuePolicy.REPLACE
        )

    /**
     * GATE 13 — the phases whose turn the reviewer-action menu may act on. Every phase here has an
     * open review turn that is not resolved: post-presentation phases (answer/rating/evaluation,
     * hint/explanation speech, reveal) plus the proven-not-applied rating state, where the turn
     * legitimately still exists. Anything else (no turn, paused, recovering, finishing, resolved)
     * is refused before the policy is even consulted.
     */
    private val REVIEWER_ACTION_PHASES: Set<SessionPhase> = setOf(
        SessionPhase.SpeakingQuestion,
        SessionPhase.WaitingForAnswer,
        SessionPhase.PendingAnswerReview,
        SessionPhase.SubmittingAnswer,
        SessionPhase.WaitingForEvaluation,
        SessionPhase.SpeakingFeedback,
        SessionPhase.WaitingForRating,
        SessionPhase.ShowingAnswer,
        SessionPhase.SpeakingHint,
        SessionPhase.SpeakingExplanation,
        SessionPhase.RatingCommitFailed
    )

    private val ANKI_REVEAL_VOICE_PHRASES = setOf(
        "show answer", "show the answer", "give answer", "what is the answer",
        "whats the answer", "reveal answer", "reveal the answer",
        "اظهر الجواب", "ما هو الجواب", "اظهر الحل"
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
            anki = null,
            phase = SessionPhase.Starting,
            session = null,
            cardTurn = null,
            pendingAction = pending,
            error = null,
            ledger = SubmissionLedger(),
            recentServerMessageIds = linkedSetOf(),
            cardGeneration = 0L,
            pendingRatingConfirmation = null,
            pendingTranscript = null,
            // GATE 11: freeze what the agent proved it can do *before* the first mutation of
            // this session. commitSemanticsFromAgent clamps overclaims; null stays fail-closed.
            commitSemantics = com.studyagent.client.core.anki.commitSemanticsFromAgent(event.agentCapabilities)
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

    private fun handleQuestion(
        state: SessionMachineState,
        event: StudyEvent.ServerQuestionReceived,
        clockMs: Long,
        /**
         * Anki hydration reuses this handler but must keep the scheduler's turn id verbatim:
         * the GATE-11 commit pipeline correlates ReviewCommitId.turnId with cardTurn.turnId,
         * so that identity is the scheduler's, not a client-qualified derivative. PC server
         * turn ids get epoch-qualified — a restarted server session may legitimately reuse
         * "turn-1" and a ghost callback from epoch N must never match epoch N+1's turn.
         */
        preserveServerTurnId: Boolean = false
    ): Transition {
        // A pushed next card / changed revision is not a transaction-correlated rating receipt.
        if (state.anki == null && state.cardTurn?.let { state.ledger.hasRatingInFlight(it.turnId) } == true) {
            return Transition.reject(state, event, "pc-rating-unconfirmed")
        }
        // SessionId validation §18
        if (event.sessionId != null && state.session?.sessionId != null && event.sessionId != state.session?.sessionId) {
            return Transition.reject(state, event, "stale-session")
        }
        // Duplicate detection §20: exact same turn already speaking/listening — or already
        // answered and awaiting/holding its rating. A retransmitted question for the live turn
        // must never rewind the turn (re-speak, reopen an answer window, cancel a rating in
        // flight) at any point of the turn's lifecycle. A genuinely new ask of the same card
        // arrives under a different server turn id and is not suppressed.
        val cur = state.cardTurn
        val isExactDuplicate = cur != null && cur.cardId == event.cardId && cur.card.question == event.question &&
                (event.serverTurnId == null || cur.serverTurnId == null || event.serverTurnId == cur.serverTurnId) &&
                (state.phase is SessionPhase.SpeakingQuestion || state.phase is SessionPhase.WaitingForAnswer ||
                    state.phase is SessionPhase.PendingAnswerReview || state.phase is SessionPhase.SubmittingAnswer ||
                    state.phase is SessionPhase.WaitingForEvaluation || state.phase is SessionPhase.ShowingAnswer ||
                    state.phase is SessionPhase.SpeakingFeedback || state.phase is SessionPhase.WaitingForRating ||
                    state.phase is SessionPhase.SubmittingRating)
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
        val turnId = if (preserveServerTurnId && !event.serverTurnId.isNullOrBlank()) event.serverTurnId
        else CardTurn.generateTurnId(state.epoch, card.id, newGen, event.serverTurnId)
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
        // An evaluation answers a submitted answer. In WaitingForFirstCard nothing is in flight
        // for this turn any more — it was skipped, or already rated — so a late evaluation must
        // not resurrect it (§19/§55).
        if (state.phase is SessionPhase.WaitingForFirstCard) {
            return Transition.reject(state, event, "no-answer-in-flight")
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
            // Half duplex: a (re-)evaluation can arrive while the rating microphone of an
            // earlier presentation is open; the feedback utterance owns the voice channel and
            // the rating window reopens when it completes.
            effects.add(StudyEffect.Voice.CancelRecognition("evaluation"))
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
            activeSpeechEffectId = effectId,
            // The feedback utterance cancelled any open microphone; the state must not keep
            // claiming a recognition effect that no longer owns a turn (§22).
            activeRecognitionEffectId = if (effectId != null) null else state.activeRecognitionEffectId
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
                sessionRevision = turn.serverRevision,
                reviewCommitId = state.session?.sessionId?.let { PcRatingReplayPolicy.logicalCommitId(it, turn.turnId) }
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
        // Only an ACK correlated to the original delivery can move the turn. A changed card,
        // next question, revision or unrequested server message is not proof of this rating.
        val pending = state.pendingAction
        // WaitingForRating joins the set for the retryable-timeout rollback only: there the
        // original pendingAction is deliberately retained, so a late ack for *that* delivery is
        // still correlated (and any ack without a matching pending is rejected as before).
        if (pending?.type != PendingAction.ActionType.RATE_CARD || state.phase !in setOf(
                SessionPhase.SubmittingRating, SessionPhase.Error(SessionProblem.RATING_TIMEOUT),
                SessionPhase.WaitingForRating)) {
            return Transition.reject(state, event, "unrequested-rating-ack")
        }
        if (pending.cardId != event.cardId) return Transition.reject(state, event, "stale-pending")
        if (pending.expectedRating != null && pending.expectedRating != event.rating) {
            return Transition.reject(state, event, "unexpected-rating-ack")
        }
        val turn = state.cardTurn ?: return Transition.reject(state, event, "no-card")
        if (pending.cardTurnId != turn.turnId ||
            (event.inReplyTo ?: event.messageId) != pending.messageId ||
            (event.reviewTurnId != null && event.reviewTurnId != turn.turnId)) {
            return Transition.reject(state, event, "unmatched-rating-ack")
        }
        val ledger2 = state.ledger.markRatingAck(turn.turnId)
        val newState = state.copy(
            phase = SessionPhase.WaitingForFirstCard, // waiting for next card; next question will bring SpeakingQuestion
            ledger = ledger2,
            pendingAction = null,
            // The evaluation belongs to the answered turn; the turn is complete and must retire it
            // (StudySessionMachine.checkInvariants: evaluation is legal only while feedback/rating
            // is live for that turn).
            cardTurn = turn.copy(evaluation = null)
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
        // A repeat rewinds the turn to its question. That is only legal while the turn is still
        // in its question/answer window: while paused nothing may speak until the session is
        // resumed (§96), and once the evaluation is on screen the turn belongs to the rating
        // flow — rewinding would strand the evaluation in an illegal phase and reopen an answer
        // window for an answer that was already submitted (§80 choose-contract stays local).
        if (state.phase is SessionPhase.Paused || state.phase is SessionPhase.Pausing ||
            state.isFinished || state.isIdle || state.cardTurn?.evaluation != null
        ) {
            return Transition.reject(state, event, "illegal-phase")
        }
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
            // Half duplex: the repeated question owns the voice channel — any open answer or
            // rating microphone is closed first and reopens when the utterance completes.
            activeRecognitionEffectId = null,
            pendingAction = pending
        ).recordTransition(event, state.phase, SessionPhase.SpeakingQuestion)
        return Transition(newState, listOf(StudyEffect.Voice.CancelRecognition("repeat"), StudyEffect.Voice.Speak(speak, effectId), send, StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.SpeakingQuestion, card.id, state.epoch)))
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
            // The evaluation retires with the turn it answered (§15): a skipped turn waits for
            // the next question with no feedback on screen — the same rule rating_saved follows.
            cardTurn = state.cardTurn?.copy(evaluation = null),
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
        val resumeCtx = ResumeContext(state.epoch, state.phase, state.cardTurn, state.pendingTranscript?.text, clockMs, routeLossBeforePause = event.routeLoss)
        val newState = state.copy(
            phase = SessionPhase.Pausing,
            pauseContext = resumeCtx,
            pendingAction = pending,
            // State hygiene: this transition cancels the voice pipeline, so the published state
            // must not keep claiming live effect ownership — a paused phase holding a recognition
            // effect is exactly what the paused-with-open-mic invariant forbids (§22).
            activeSpeechEffectId = null,
            activeRecognitionEffectId = null
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
            pendingAction = null,
            activeSpeechEffectId = null,
            activeRecognitionEffectId = null
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
        // A pause the disconnect policy forced on the user (§96/§97) interrupted a question the
        // user's new route never finished hearing: resume repeats it once, then reopens the
        // answer window when the utterance completes. Any later turn stage resumes as before.
        val before = ctx?.phaseBeforePause
        val routeLossRePresent = ctx?.routeLossBeforePause == true && ctx.cardTurn?.card != null &&
            (before is SessionPhase.SpeakingQuestion || before is SessionPhase.WaitingForAnswer ||
                before is SessionPhase.PendingAnswerReview)
        val safePhase = if (routeLossRePresent) SessionPhase.SpeakingQuestion
        else ResumeContext.safeRestartPhase(ctx, SessionPhase.WaitingForAnswer)
        val card = ctx?.cardTurn?.card ?: state.cardTurn?.card
        val newCardTurn = ctx?.cardTurn ?: state.cardTurn
        val newState = state.copy(
            phase = safePhase,
            cardTurn = newCardTurn,
            pauseContext = null,
            pendingAction = null,
            activeRecognitionEffectId = null
        ).recordTransition(event, state.phase, safePhase)
            .rememberServerMessageId(event.messageId)

        val effects = mutableListOf<StudyEffect>()
        effects.add(StudyEffect.CancelTimeout(state.pendingAction?.messageId ?: ""))
        effects.add(StudyEffect.LogTransition(state.phase, event.debugName, safePhase, card?.id, state.epoch))
        if (card != null) {
            when (safePhase) {
                SessionPhase.SpeakingQuestion -> {
                    val effId = EffectIds.next("resume-question")
                    // Half duplex: the repeated question replaces any microphone window that
                    // survived the pause; the answer window reopens when the utterance ends.
                    effects.add(StudyEffect.Voice.CancelRecognition("resume-question"))
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
        if (state.anki == null && state.phase !is SessionPhase.Finishing &&
            state.cardTurn?.let { state.ledger.hasRatingInFlight(it.turnId) } == true) {
            return Transition.reject(state, event, "pc-rating-unconfirmed")
        }
        // Terminal guard already handled, but allow idempotent duplicate
        if (state.phase is SessionPhase.Finished && state.session?.sessionId == event.sessionId) {
            return Transition.reject(state, event, "duplicate-finished")
        }
        val newState = state.copy(
            phase = SessionPhase.Finished,
            pendingAction = null,
            pauseContext = null,
            activeSpeechEffectId = null,
            activeRecognitionEffectId = null,
            cardTurn = state.cardTurn?.copy(evaluation = null)
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
            connection = SessionConnectionStatus.DISCONNECTED,
            cardTurn = state.cardTurn?.copy(evaluation = null)
        ).recordTransition(event, state.phase, SessionPhase.Recovering)
        return Transition(newState, listOf(StudyEffect.Voice.CancelSpeech("connection-lost"), StudyEffect.Voice.CancelRecognition("connection-lost"), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Recovering, state.currentCardId, state.epoch)))
    }

    private fun handleConnectionRestored(state: SessionMachineState, event: StudyEvent.ConnectionRestored, clockMs: Long): Transition {
        if (state.phase !is SessionPhase.Recovering && state.phase !is SessionPhase.Error) {
            // If already connected, treat as no-op but request status for safety
            if (state.connection == SessionConnectionStatus.CONNECTED) return Transition.reject(state, event, "already-connected")
        }
        // Request authoritative snapshot §35-§37. The request is the session's one in-flight
        // action until the snapshot answers it: tracked as a pending action so the watchdog,
        // the single-pending rule and diagnostics all see the reconciliation in progress.
        val msgId = UUID.randomUUID().toString()
        val send = StudyEffect.Network.Send(msgId, ClientMessage.RequestSessionStatus(sessionId = state.session?.sessionId, messageId = msgId))
        val pending = newPending(PendingAction.ActionType.REQUEST_SESSION_STATUS, state, msgId, state.cardTurn?.turnId, state.cardTurn?.cardId, clockMs)
        val newState = state.copy(
            connection = SessionConnectionStatus.RECOVERING,
            phase = SessionPhase.Recovering,
            pendingAction = pending,
            cardTurn = state.cardTurn?.copy(evaluation = null)
        ).recordTransition(event, state.phase, SessionPhase.Recovering)
        return Transition(newState, listOf(send, StudyEffect.ScheduleTimeout(msgId, pending.timeoutMs, PendingAction.ActionType.REQUEST_SESSION_STATUS), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.Recovering, state.currentCardId, state.epoch)))
    }

    private fun handleStatusReceived(state: SessionMachineState, event: StudyEvent.SessionStatusReceived, clockMs: Long): Transition {
        // Reconcile §39
        val reconciliation = SessionReconciler.reconcile(state, event.snapshot, clockMs)
        var finalState = reconciliation.newState
        val extraEffects = mutableListOf<StudyEffect>()
        // The authoritative snapshot answers the pending status request: retire it and its
        // watchdog so the session has no phantom in-flight action blocking the next turn.
        val answered = finalState.pendingAction
        if (answered?.type == PendingAction.ActionType.REQUEST_SESSION_STATUS) {
            extraEffects += StudyEffect.CancelTimeout(answered.messageId)
            finalState = finalState.copy(pendingAction = null)
        }
        finalState = finalState.recordTransition(event, state.phase, finalState.phase)
        return Transition(finalState, reconciliation.effects + extraEffects + StudyEffect.LogTransition(state.phase, event.debugName, finalState.phase, finalState.currentCardId, state.epoch))
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
        // A paused session holds no microphone (§22): push-to-talk must never silently reopen
        // one — the user resumes the session explicitly first. Recovering stays allowed: PTT is
        // precisely the path that must work while an automatic window is not open.
        if (state.isIdle || state.isFinished || state.phase is SessionPhase.Error ||
            state.phase is SessionPhase.Paused || state.phase is SessionPhase.Pausing
        ) {
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
            is SessionPhase.SpeakingHint -> RecognitionPurpose.PUSH_TO_TALK_COMMAND

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
        val newState = state.copy(
            phase = SessionPhase.SpeakingQuestion,
            activeSpeechEffectId = effectId,
            // Half duplex: the repeated question owns the voice channel (see handleRepeat).
            activeRecognitionEffectId = null
        ).recordTransition(event, state.phase, SessionPhase.SpeakingQuestion)
        return Transition(newState, listOf(StudyEffect.Voice.CancelRecognition("route-restore"), StudyEffect.Voice.Speak(speak, effectId), StudyEffect.LogTransition(state.phase, event.debugName, SessionPhase.SpeakingQuestion, card.id, state.epoch)))
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
                val turnId = pending.cardTurnId
                if (com.studyagent.client.core.anki.PcRatingReplayPolicy.automaticReplayAllowed(state.commitSemantics)) {
                    // The agent froze `review_commit_idempotency` for this session: a replay of
                    // the same logical commit (same review_commit_id) cannot double-apply, so the
                    // turn may return to the rating window and the *user* may retry. The client
                    // still never resends on its own, and a late correlated ack is still honoured.
                    val ledger2 = if (turnId != null) state.ledger.markRatingFailed(turnId, true) else state.ledger
                    val newState = state.copy(
                        phase = SessionPhase.WaitingForRating,
                        ledger = ledger2,
                        error = SessionProblemHolder(SessionProblem.RATING_TIMEOUT,
                            "Rating acknowledgement timed out. The agent deduplicates this commit; retrying will not apply it twice.",
                            true, clockMs)
                    ).recordTransition(event, state.phase, SessionPhase.WaitingForRating)
                    return Transition(newState, listOf(StudyEffect.LogRejected(event.debugName, "rating-timeout-retryable", state.phase, pending.cardId)))
                }
                // The server may have applied the rating even if its ACK was lost, and nothing
                // proves a replay would be deduplicated. Preserve the original message
                // correlation, do NOT mark it retryable or reopen rating/skip: the only exit is a
                // correlated receipt (GATE 11: ambiguous outcomes never blindly retry).
                val newState = state.copy(
                    phase = SessionPhase.Error(SessionProblem.RATING_TIMEOUT),
                    error = SessionProblemHolder(SessionProblem.RATING_TIMEOUT,
                        "PC rating is unconfirmed. Do not retry or advance without a correlated receipt.",
                        false, clockMs)
                ).recordTransition(event, state.phase, SessionPhase.Error(SessionProblem.RATING_TIMEOUT))
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

    /**
     * How many accepted card-turn ids are retained (§21).
     *
     * Sized for identity/race diagnosis — a stale callback is at most a few turns behind — and
     * deliberately far smaller than a session's card count, so a 1000-card endurance run holds
     * the same 64 strings as a 10-card one.
     */
    const val CARD_TURN_HISTORY_LIMIT = 64

}
