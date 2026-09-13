package com.studyagent.client.core.study

import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.voice.stt.RecognitionPurpose

/**
 * Authoritative reconciliation after reconnect (§39-§41).
 *
 * Server wins for session existence / current Anki card / scheduler progression.
 * Client wins for local presentation state that server does not own.
 */
object SessionReconciler {

    data class Reconciliation(
        val newState: SessionMachineState,
        val effects: List<StudyEffect>
    )

    fun reconcile(
        local: SessionMachineState,
        snapshot: StudySnapshot,
        clockMs: Long = System.currentTimeMillis()
    ): Reconciliation {
        // Session missing on server -> terminal or error
        if (snapshot.isFinished) {
            val finished = local.copy(
                phase = SessionPhase.Finished,
                pendingAction = null,
                error = null
            )
            return Reconciliation(finished, listOf(StudyEffect.Voice.CancelSpeech("session-finished-reconcile"), StudyEffect.Voice.CancelRecognition("session-finished-reconcile")))
        }

        // Reconciliation may never undo a terminal intent.
        //
        // The frame being reconciled here is normally a `request_session_status` reply, and that
        // reply can be *stale by the time it is processed*: it may have been sent before the user
        // ended the session, and its `awaiting` field still describes the turn that was open then.
        //
        // For a finished session the reducer's terminal rule already rejects such an event, so this
        // is defence in depth. For a session that is still `Finishing` — EndSession sent, server
        // confirmation outstanding, which is exactly what a flaky connection produces — nothing
        // else would catch it: reconciliation would take the phase backwards, re-issue the
        // question and reopen the voice loop for a user who has already walked away.
        if ((local.phase is SessionPhase.Finished || local.phase is SessionPhase.Finishing) &&
            !snapshot.isFinished
        ) {
            return Reconciliation(
                local.copy(connection = SessionConnectionStatus.CONNECTED),
                listOf(
                    StudyEffect.Voice.CancelSpeech("ending-reconcile-stale"),
                    StudyEffect.Voice.CancelRecognition("ending-reconcile-stale")
                )
            )
        }

        // No session but server says we exist -> rehydrate
        val newSession = if (local.session == null) {
            StudySessionSnapshot(
                sessionId = snapshot.sessionId,
                deckName = snapshot.deckName ?: "Study Session",
                currentCard = snapshot.currentCard,
                remainingCards = snapshot.remainingCards ?: 0,
                totalReviewedInSession = snapshot.reviewedCards ?: 0,
                totalCardsInQueue = snapshot.totalCards,
                startedAtEpochMs = clockMs
            )
        } else {
            local.session.copy(
                currentCard = snapshot.currentCard ?: local.session.currentCard,
                remainingCards = snapshot.remainingCards ?: local.session.remainingCards,
                totalReviewedInSession = snapshot.reviewedCards ?: local.session.totalReviewedInSession,
                totalCardsInQueue = snapshot.totalCards ?: local.session.totalCardsInQueue
            )
        }

        // Server is authoritative for current card; if mismatch we realign.
        val cardTurn: CardTurn? = when {
            snapshot.currentCard != null && local.cardTurn?.cardId != snapshot.currentCard.id -> {
                // Server advanced or rewound
                val gen = local.cardGeneration + 1
                CardTurn(
                    generation = gen,
                    card = snapshot.currentCard,
                    turnId = CardTurn.generateTurnId(local.epoch, snapshot.currentCard.id, gen, snapshot.serverTurnId),
                    serverTurnId = snapshot.serverTurnId,
                    serverRevision = snapshot.serverRevision,
                    evaluation = snapshot.evaluation
                )
            }
            snapshot.currentCard != null -> local.cardTurn?.copy(evaluation = snapshot.evaluation) ?: CardTurn(
                generation = local.cardGeneration + 1,
                card = snapshot.currentCard,
                turnId = CardTurn.generateTurnId(local.epoch, snapshot.currentCard.id, local.cardGeneration + 1, snapshot.serverTurnId),
                serverTurnId = snapshot.serverTurnId,
                serverRevision = snapshot.serverRevision,
                evaluation = snapshot.evaluation
            )
            else -> local.cardTurn
        }

        val serverPhase = mapServerPhase(snapshot)
        val localPhase = mapToPhaseForReconcile(serverPhase, local, snapshot)

        var newState = local.copy(
            session = newSession,
            cardTurn = cardTurn,
            phase = localPhase,
            connection = SessionConnectionStatus.CONNECTED,
            pendingAction = null // pending actions ambiguous after reconnect => require fresh intent
        )

        // Validate pending transcript still valid §42
        val pending = local.pendingTranscript
        if (pending != null) {
            val stillValid = snapshot.sessionId == local.sessionId &&
                    snapshot.currentCard?.id == pending.cardId &&
                    snapshot.phase == ServerSessionPhase.AWAITING_ANSWER
            if (!stillValid) {
                newState = newState.copy(pendingTranscript = null, cardTurn = cardTurn?.withPendingTranscript(null))
            }
        }

        // Produce effects: cancel stale voice, then speak/listen per new phase
        val effects = mutableListOf<StudyEffect>()
        effects.add(StudyEffect.Voice.CancelSpeech("reconcile"))
        effects.add(StudyEffect.Voice.CancelRecognition("reconcile"))
        if (snapshot.isPaused) {
            newState = newState.copy(
                phase = SessionPhase.Paused,
                pauseContext = ResumeContext(local.epoch, localPhase, cardTurn, null, clockMs)
            )
        }

        // §38/§41/§96: a reconnect must never park the client in a phase that cannot progress.
        //
        // The server saying "awaiting answer" means the question is still the current turn, but
        // it says nothing about whether the user *heard* it: the app may have been killed, or the
        // drop may have happened mid-question. Opening the microphone immediately would ask the
        // user to answer a question they never heard, so the question is re-issued and the normal
        // speak → silence → listen path takes over from there.
        //
        // Before this, `AWAITING_ANSWER` mapped to `SpeakingQuestion` with *no* speech effect: the
        // machine sat in a phase that only a `QuestionSpeechCompleted` event can leave, and the
        // session was stuck until the user pressed push-to-talk.
        if (!snapshot.isPaused && newState.phase is SessionPhase.SpeakingQuestion) {
            val turn = newState.cardTurn
            if (turn != null) {
                val effectId = EffectIds.next("reconcile-question")
                effects.add(StudyEffect.Voice.Speak(StudyReducer.speechRequestForQuestion(turn.card), effectId))
                newState = newState.copy(
                    phase = SessionPhase.SpeakingQuestion,
                    activeSpeechEffectId = effectId
                )
            } else {
                newState = newState.copy(phase = SessionPhase.WaitingForAnswer)
                val cardId = newState.currentCardId
                if (cardId != null) {
                    effects.add(
                        StudyEffect.Voice.StartRecognition(
                            RecognitionPurpose.ANSWER,
                            cardId,
                            EffectIds.next("reconcile-stt")
                        )
                    )
                }
            }
        }

        return Reconciliation(newState, effects)
    }

    private fun mapServerPhase(snapshot: StudySnapshot): SessionPhase {
        if (snapshot.isFinished) return SessionPhase.Finished
        if (snapshot.isPaused) return SessionPhase.Paused
        return when (snapshot.phase) {
            ServerSessionPhase.AWAITING_FIRST_CARD -> SessionPhase.WaitingForFirstCard
            ServerSessionPhase.AWAITING_ANSWER -> SessionPhase.WaitingForAnswer
            ServerSessionPhase.AWAITING_EVALUATION -> SessionPhase.WaitingForEvaluation
            ServerSessionPhase.AWAITING_RATING -> SessionPhase.WaitingForRating
            ServerSessionPhase.PAUSED -> SessionPhase.Paused
            ServerSessionPhase.FINISHED -> SessionPhase.Finished
            ServerSessionPhase.UNKNOWN -> SessionPhase.WaitingForAnswer
        }
    }

    private fun mapToPhaseForReconcile(serverMapped: SessionPhase, local: SessionMachineState, snapshot: StudySnapshot): SessionPhase {
        // Server wins; map directly except retain Speaking* requires fresh TTS
        return when (serverMapped) {
            SessionPhase.WaitingForAnswer -> if (snapshot.currentCard != null) SessionPhase.SpeakingQuestion else SessionPhase.WaitingForAnswer
            SessionPhase.WaitingForRating -> SessionPhase.WaitingForRating
            else -> serverMapped
        }
    }
}
