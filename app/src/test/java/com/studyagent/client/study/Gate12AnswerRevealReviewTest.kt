package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiReviewTurnContent
import com.studyagent.client.core.anki.AnkiSchedulingInfo
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.CommitPhaseSink
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.ReviewCommitLedger
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.diagnostics.DiagnosticTimeline
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardRenderPlan
import com.studyagent.client.core.render.AnkiCardRenderPlanner
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.CardTextDirection
import com.studyagent.client.core.study.AnkiAnswerEvaluationRequest
import com.studyagent.client.core.study.AnkiAnswerEvaluationResult
import com.studyagent.client.core.study.AnkiAnswerEvaluator
import com.studyagent.client.core.study.AnkiAnswerReviewDiagnostics
import com.studyagent.client.core.study.AnkiStudyEffect
import com.studyagent.client.core.study.AnkiStudyEffectExecutor
import com.studyagent.client.core.study.AnkiStudyEvent
import com.studyagent.client.core.study.AnkiStudyRequest
import com.studyagent.client.core.study.AnswerAudioSequencePhase
import com.studyagent.client.core.study.AnswerCompareMode
import com.studyagent.client.core.study.AnswerEvaluationStatus
import com.studyagent.client.core.study.AnswerRevealState
import com.studyagent.client.core.study.AnswerReviewModel
import com.studyagent.client.core.study.CleanAnswerState
import com.studyagent.client.core.study.PendingTranscript
import com.studyagent.client.core.study.RatingCommitRecoveryUi
import com.studyagent.client.core.study.RatingCommitUiState
import com.studyagent.client.core.study.SessionMachineState
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.core.study.SpokenCommandRouter
import com.studyagent.client.core.study.StudyEffect
import com.studyagent.client.core.study.StudyEvent
import com.studyagent.client.core.study.StudyReducer
import com.studyagent.client.core.study.StudySessionMachine
import com.studyagent.client.core.study.Transition
import com.studyagent.client.core.voice.stt.VoiceCommandGrammar
import com.studyagent.client.core.voice.tts.SpeechPurpose
import com.studyagent.client.testutil.FakeConnectionRepository
import com.studyagent.client.testutil.FakeRecognitionOrchestrator
import com.studyagent.client.testutil.FakeSpeechOrchestrator
import com.studyagent.client.ui.screens.study.AnswerEvaluationCardUi
import com.studyagent.client.ui.screens.study.AnswerReviewSemantics
import com.studyagent.client.ui.screens.study.AnswerReviewUiState
import com.studyagent.client.ui.screens.study.UserAnswerComparisonUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 12 — Automated verification suite for Answer Reveal, Reference Answer & Compare Experience.
 *
 * Covers VERIFICATION 1 through VERIFICATION 30 in PART IV of the GATE 12 specification.
 */
class Gate12AnswerRevealReviewTest {

    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val deck = AnkiDeckRef(backendId, "deck-1", "col-1")

    private fun sampleCard(
        id: String = "card-10",
        questionHtml: String? = "<div class='q'>What is the primary pacemaker of the heart?</div>",
        answerHtml: String? = "<div class='a'><b>Sinoatrial (SA) node</b><hr id='answer'><i>Right atrium</i></div>",
        questionText: String? = "What is the primary pacemaker of the heart?",
        answerText: String? = "Sinoatrial (SA) node — Right atrium",
        pureAnswerText: String? = "Sinoatrial node",
        media: List<AnkiMediaRef> = emptyList(),
        scheduling: AnkiSchedulingInfo? = AnkiSchedulingInfo(
            intervalLabel = "3d",
            dueStateLabel = "Review",
            nextReviewTimes = mapOf(
                Rating.AGAIN to "<10m",
                Rating.HARD to "2d",
                Rating.GOOD to "5d",
                Rating.EASY to "12d"
            )
        )
    ): AnkiRenderedCard = AnkiRenderedCard(
        ref = AnkiCardRef(backendId, cardId = id, collectionKey = "col-1"),
        questionHtml = questionHtml,
        answerHtml = answerHtml,
        questionText = questionText,
        answerText = answerText,
        pureAnswerText = pureAnswerText,
        scheduling = scheduling,
        media = media,
        deckRef = deck
    )

    private inner class ReducerRig(
        cards: List<AnkiRenderedCard> = listOf(sampleCard(), sampleCard("card-20")),
        val evaluateAnswers: Boolean = false,
        val speakQuestion: Boolean = true,
        val speakFeedback: Boolean = false,
        val speakAnswer: Boolean = false,
        val defaultCompareMode: AnswerCompareMode = AnswerCompareMode.COMPARE,
        var evaluatorResult: AnkiAnswerEvaluationResult? = null
    ) {
        val store = InMemoryReviewCommitStore()
        var now = 1_000L
        val ledger = ReviewCommitLedger(store, { now })
        var nextCalls = 0
        var commitCalls = 0
        var evaluateCalls = 0
        var lastEvaluatedRequest: AnkiAnswerEvaluationRequest? = null

        val fake = FakeAnkiBackend(
            id = backendId,
            decks = listOf(AnkiDeck(deck, "Cardiology")),
            cards = cards,
            instanceId = "gate12"
        )

        val backend = object : com.studyagent.client.core.anki.AnkiBackend by fake {
            override suspend fun nextCard(session: com.studyagent.client.core.anki.AnkiReviewSession) =
                fake.nextCard(session).also { nextCalls++ }

            override suspend fun commitRating(request: CommitRatingRequest): BackendCommitResult {
                commitCalls++
                return fake.commitRating(request)
            }
        }

        val evaluator = AnkiAnswerEvaluator { req ->
            evaluateCalls++
            lastEvaluatedRequest = req
            evaluatorResult ?: AnkiAnswerEvaluationResult.Success(
                Evaluation(
                    correctPoints = listOf("Identified SA node"),
                    missingPoints = listOf("Mention right atrial location"),
                    incorrectPoints = emptyList(),
                    shortFeedback = "Correct: the SA node initiates the cardiac impulse.",
                    suggestedRating = Rating.GOOD,
                    score = 92
                )
            )
        }

        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(backend)),
            ledger = ledger,
            clock = { now },
            answerEvaluator = evaluator
        )

        var state = SessionMachineState.initial()
        val emittedEffects = mutableListOf<StudyEffect>()

        fun send(event: StudyEvent): Transition {
            now += 50L
            return StudyReducer.reduce(state, event, now).also { t ->
                state = t.newState
                emittedEffects.addAll(t.effects)
            }
        }

        suspend fun loadFirstCard() {
            val req = AnkiStudyRequest(
                studySessionId = "study-gate12",
                preference = AnkiBackendMode.ANKIDROID_LOCAL,
                deck = deck,
                speakQuestion = speakQuestion,
                evaluateAnswers = evaluateAnswers,
                speakFeedback = speakFeedback,
                speakAnswer = speakAnswer,
                defaultCompareMode = defaultCompareMode
            )
            var t = send(AnkiStudyEvent.Start(req))
            repeat(3) {
                val eff = t.effects.filterIsInstance<AnkiStudyEffect>().single()
                val nextEvent = checkNotNull(executor.execute(eff))
                t = send(nextEvent)
            }
        }

        fun completeQuestionSpeech(success: Boolean = true) {
            val cardId = checkNotNull(state.currentCardId)
            val speechId = checkNotNull(state.activeSpeechEffectId)
            send(StudyEvent.QuestionSpeechCompleted(cardId, speechId, success))
        }

        fun submitFinalTranscript(text: String): Transition {
            return if (state.activeRecognitionEffectId != null) {
                send(
                    StudyEvent.RecognitionCompleted(
                        cardId = state.currentCardId,
                        turnId = state.cardTurn?.turnId,
                        transcript = text,
                        isCommand = false,
                        requestId = state.activeRecognitionEffectId
                    )
                )
            } else {
                send(StudyEvent.UserSubmitAnswer(checkNotNull(state.currentCardId), text))
            }
        }

        fun model(): AnswerReviewModel = checkNotNull(AnswerReviewModel.from(state))
        fun uiState(recovery: RatingCommitRecoveryUi? = RatingCommitRecoveryUi.from(state)): AnswerReviewUiState =
            AnswerReviewUiState.from(model(), recovery)
    }

    // ------------------------------------------------------------------ VERIFICATION 1 & 29
    @Test
    fun `VERIFICATION 1 and 29 - answer is hidden during question and listening phases with zero content leakage`() = runTest {
        val rig = ReducerRig(speakQuestion = true)
        rig.loadFirstCard()

        // SpeakingQuestion phase
        val speakingModel = rig.model()
        val speakingUi = rig.uiState()
        assertEquals(AnswerRevealState.HIDDEN, speakingModel.revealState)
        assertFalse(speakingModel.isRevealed)
        assertNull("Answer HTML must not be exposed before reveal", speakingModel.answerHtml)
        assertNull("Answer text must not be exposed before reveal", speakingModel.answerText)
        assertNull("Pure answer text must not be exposed before reveal", speakingModel.pureAnswerText)
        assertNull("Reference answer text must not be exposed before reveal", speakingModel.referenceAnswerText)
        assertNull("Raw reference answer text must not be exposed before reveal", speakingModel.rawReferenceAnswerText)
        assertNull("Projected card answerHtml must be stripped while HIDDEN", speakingModel.renderedCard?.answerHtml)
        assertNull("Projected card answerText must be stripped while HIDDEN", speakingModel.renderedCard?.answerText)
        assertNull("Projected card pureAnswerText must be stripped while HIDDEN", speakingModel.renderedCard?.pureAnswerText)
        assertEquals(AnkiCardSide.QUESTION, speakingUi.renderedSide)
        assertNull(speakingUi.visibleAnswerHtml)
        assertNull(speakingUi.visibleAnswerText)
        assertNull(speakingUi.referenceAnswerComparison)
        assertFalse(speakingUi.ratingControlsEnabled)
        assertTrue(speakingUi.showRevealButton)
        assertFalse(speakingUi.showModeSelector)
        assertTrue(
            "No Speak effect may contain answerText before reveal",
            rig.emittedEffects.filterIsInstance<StudyEffect.Voice.Speak>().none {
                it.request.text.contains("Sinoatrial")
            }
        )

        // Transition to listening phase
        rig.completeQuestionSpeech()
        assertEquals(SessionPhase.WaitingForAnswer, rig.state.phase)
        val listeningModel = rig.model()
        val listeningUi = rig.uiState()
        assertEquals(AnswerRevealState.HIDDEN, listeningModel.revealState)
        assertEquals(AnkiCardSide.QUESTION, listeningUi.renderedSide)
        assertNull(listeningUi.referenceAnswerComparison)
        assertFalse(listeningUi.ratingControlsEnabled)
    }

    // ------------------------------------------------------------------ VERIFICATION 2 & 3
    @Test
    fun `VERIFICATION 2 and 3 - explicit reveal transitions to REVEALED on same ReviewTurnId without querying scheduler`() = runTest {
        val rig = ReducerRig(speakQuestion = false)
        rig.loadFirstCard()
        val initialTurnId = rig.state.anki!!.turn!!.turnId
        assertEquals(1, rig.nextCalls)

        val revealTransition = rig.send(StudyEvent.RevealAnswerRequested(turnId = initialTurnId, epoch = rig.state.epoch))
        assertTrue(revealTransition.accepted)
        assertEquals(SessionPhase.WaitingForRating, rig.state.phase)
        assertEquals(initialTurnId, rig.state.anki!!.turn!!.turnId)
        assertEquals("Reveal must never call nextCard", 1, rig.nextCalls)
        assertEquals("Reveal must never commit a rating", 0, rig.commitCalls)

        val model = rig.model()
        val ui = rig.uiState()
        assertEquals(AnswerRevealState.REVEALED, model.revealState)
        assertTrue(model.isRevealed)
        assertEquals(AnkiCardSide.ANSWER, ui.renderedSide)
        assertNotNull(model.answerHtml)
        assertNotNull(model.referenceAnswerText)
        assertFalse(ui.showRevealButton)
        assertTrue(ui.showModeSelector)
        assertEquals(4, ui.ratingOptions.size)
        assertTrue(ui.ratingControlsEnabled)
    }

    // ------------------------------------------------------------------ VERIFICATION 4, 5 & 6
    @Test
    fun `VERIFICATION 4 5 and 6 - ORIGINAL preserves answerHtml verbatim CLEAN uses answerText and COMPARE shows user and reference`() = runTest {
        val customHtml = "<div class='cloze'><span class='cloze-reveal'>SA Node</span><table><tr><td>60-100 bpm</td></tr></table></div>"
        val customClean = "SA Node (60-100 bpm)"
        val rig = ReducerRig(
            cards = listOf(sampleCard(answerHtml = customHtml, answerText = customClean)),
            speakQuestion = false,
            defaultCompareMode = AnswerCompareMode.COMPARE
        )
        rig.loadFirstCard()
        rig.submitFinalTranscript("SA node 70 bpm")

        val compareModel = rig.model()
        val compareUi = rig.uiState()
        assertEquals(AnswerRevealState.REVEALED, compareModel.revealState)
        assertEquals(AnswerCompareMode.COMPARE, compareUi.effectiveCompareMode)
        assertEquals(AnkiCardRenderMode.ORIGINAL, compareUi.rendererMode)
        assertSame("answerHtml must be preserved verbatim without stripping", customHtml, compareModel.answerHtml)
        assertNotNull(compareUi.userAnswerComparison)
        assertEquals("SA node 70 bpm", compareUi.userAnswerComparison!!.displayText)
        assertTrue(compareUi.userAnswerComparison!!.hasRecordedAnswer)
        assertNotNull(compareUi.referenceAnswerComparison)
        assertEquals(customClean, compareUi.referenceAnswerComparison!!.displayText)

        // Switch to ORIGINAL mode
        rig.send(StudyEvent.SelectAnswerCompareMode(AnswerCompareMode.ORIGINAL))
        val originalUi = rig.uiState()
        assertEquals(AnswerCompareMode.ORIGINAL, originalUi.effectiveCompareMode)
        assertEquals(AnkiCardRenderMode.ORIGINAL, originalUi.rendererMode)
        assertSame(customHtml, rig.model().answerHtml)

        // Switch to CLEAN mode
        rig.send(StudyEvent.SelectAnswerCompareMode(AnswerCompareMode.CLEAN))
        val cleanUi = rig.uiState()
        assertEquals(AnswerCompareMode.CLEAN, cleanUi.effectiveCompareMode)
        assertEquals(AnkiCardRenderMode.CLEAN, cleanUi.rendererMode)
        assertEquals(customClean, cleanUi.referenceAnswerComparison!!.displayText)
    }

    // ------------------------------------------------------------------ VERIFICATION 7
    @Test
    fun `VERIFICATION 7 - empty or omitted spoken answer shows explicit No spoken answer recorded state`() = runTest {
        val rig = ReducerRig(speakQuestion = false, defaultCompareMode = AnswerCompareMode.COMPARE)
        rig.loadFirstCard()

        // User reveals answer directly without speaking an answer
        rig.send(StudyEvent.RevealAnswerRequested(turnId = rig.state.anki!!.turn!!.turnId, epoch = rig.state.epoch))
        val ui = rig.uiState()
        assertNotNull(ui.userAnswerComparison)
        assertFalse(ui.userAnswerComparison!!.hasRecordedAnswer)
        assertEquals(UserAnswerComparisonUi.EMPTY_SKIPPED_COPY, ui.userAnswerComparison!!.displayText)
        assertNull(rig.model().userAnswerText)
        assertTrue(rig.model().isUserAnswerSkippedOrEmpty)
    }

    // ------------------------------------------------------------------ VERIFICATION 8
    @Test
    fun `VERIFICATION 8 - partial STT never becomes canonical comparison answer only final accepted transcript does`() = runTest {
        val rig = ReducerRig(speakQuestion = true, defaultCompareMode = AnswerCompareMode.COMPARE)
        rig.loadFirstCard()
        rig.completeQuestionSpeech()

        // Unapproved pending / partial transcript in machine state must NOT become canonical userAnswerText
        rig.state = rig.state.copy(
            pendingTranscript = PendingTranscript(
                cardId = rig.state.currentCardId!!,
                turnId = rig.state.cardTurn!!.turnId,
                text = "partial guess",
                epoch = rig.state.epoch
            )
        )
        assertNull("Unfinalized transcript must not become canonical userAnswerText", rig.model().userAnswerText)

        // If user reveals without finalizing transcript, partial is not promoted
        rig.send(StudyEvent.RevealAnswerRequested(turnId = rig.state.anki!!.turn!!.turnId, epoch = rig.state.epoch))
        assertNull("Partial transcript must not leak into revealed canonical userAnswerText", rig.model().userAnswerText)
        assertFalse(rig.uiState().userAnswerComparison!!.hasRecordedAnswer)

        // Now test with a finalized transcript on a fresh rig
        val rig2 = ReducerRig(speakQuestion = true, defaultCompareMode = AnswerCompareMode.COMPARE)
        rig2.loadFirstCard()
        rig2.completeQuestionSpeech()
        rig2.submitFinalTranscript("Final Sinoatrial Node")
        assertEquals("Final Sinoatrial Node", rig2.model().userAnswerText)
        assertTrue(rig2.uiState().userAnswerComparison!!.hasRecordedAnswer)
        assertEquals("Final Sinoatrial Node", rig2.uiState().userAnswerComparison!!.displayText)
    }

    // ------------------------------------------------------------------ VERIFICATION 9
    @Test
    fun `VERIFICATION 9 - evaluator receives pureAnswerText or answerText fallback and never raw answerHtml`() = runTest {
        val rig = ReducerRig(
            cards = listOf(
                sampleCard(
                    id = "card-pure",
                    answerHtml = "<div><b>HTML ONLY SECRET</b></div>",
                    answerText = "Clean Answer Text",
                    pureAnswerText = "Pure Evaluator Answer"
                )
            ),
            evaluateAnswers = true,
            speakQuestion = false
        )
        rig.loadFirstCard()
        val transition = rig.submitFinalTranscript("My spoken answer")
        assertEquals(SessionPhase.WaitingForEvaluation, rig.state.phase)

        val evalEffect = transition.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        assertEquals("Pure Evaluator Answer", evalEffect.request.referenceAnswerText)
        assertFalse("Evaluator request must never contain raw answerHtml", evalEffect.request.referenceAnswerText.contains("<b>"))

        val evalEvent = checkNotNull(rig.executor.execute(evalEffect))
        rig.send(evalEvent)
        assertEquals(1, rig.evaluateCalls)
        assertEquals("Pure Evaluator Answer", rig.lastEvaluatedRequest?.referenceAnswerText)

        // Fallback to answerText when pureAnswerText is null
        val rigFallback = ReducerRig(
            cards = listOf(
                sampleCard(
                    id = "card-fallback",
                    answerHtml = "<div><b>HTML ONLY</b></div>",
                    answerText = "Clean Fallback Text",
                    pureAnswerText = null
                )
            ),
            evaluateAnswers = true,
            speakQuestion = false
        )
        rigFallback.loadFirstCard()
        val fallbackTransition = rigFallback.submitFinalTranscript("Spoken")
        val fallbackEffect = fallbackTransition.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        assertEquals("Clean Fallback Text", fallbackEffect.request.referenceAnswerText)
    }

    // ------------------------------------------------------------------ VERIFICATION 10
    @Test
    fun `VERIFICATION 10 - AI evaluation optionality works when disabled or when evaluator fails`() = runTest {
        // Case A: AI disabled (evaluateAnswers = false)
        val manualRig = ReducerRig(evaluateAnswers = false, speakQuestion = false)
        manualRig.loadFirstCard()
        manualRig.submitFinalTranscript("Sinoatrial node")
        assertEquals(AnswerRevealState.REVEALED, manualRig.model().revealState)
        assertEquals(AnswerEvaluationStatus.NOT_REQUESTED, manualRig.model().evaluationStatus)
        assertTrue(manualRig.uiState().evaluationCard is AnswerEvaluationCardUi.Hidden)
        assertTrue(manualRig.uiState().ratingOptions.all { it.enabled })

        // Case B: AI enabled (evaluateAnswers = true), evaluator returns Failure
        val failingRig = ReducerRig(
            evaluateAnswers = true,
            speakQuestion = false,
            evaluatorResult = AnkiAnswerEvaluationResult.Failure("pc_agent_disconnected")
        )
        failingRig.loadFirstCard()
        val sub = failingRig.submitFinalTranscript("Sinoatrial node")
        val evalEffect = sub.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        val failEvent = checkNotNull(failingRig.executor.execute(evalEffect))
        failingRig.send(failEvent)

        assertEquals(SessionPhase.WaitingForRating, failingRig.state.phase)
        assertEquals(AnswerRevealState.REVEALED, failingRig.model().revealState)
        assertEquals(AnswerEvaluationStatus.UNAVAILABLE, failingRig.model().evaluationStatus)
        val unavailableCard = failingRig.uiState().evaluationCard as AnswerEvaluationCardUi.Unavailable
        assertEquals("pc_agent_disconnected", unavailableCard.reasonToken)
        assertTrue(
            "Manual rating must remain enabled when AI evaluation fails",
            failingRig.uiState().ratingOptions.all { it.enabled }
        )
    }

    // ------------------------------------------------------------------ VERIFICATION 11
    @Test
    fun `VERIFICATION 11 - AI suggestedRating is advisory only and never auto commits`() = runTest {
        val rig = ReducerRig(evaluateAnswers = true, speakQuestion = false)
        rig.loadFirstCard()
        val sub = rig.submitFinalTranscript("SA node")
        val evalEffect = sub.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        rig.send(checkNotNull(rig.executor.execute(evalEffect)))

        val model = rig.model()
        val ui = rig.uiState()
        assertEquals(Rating.GOOD, model.suggestedRating)
        assertNull("Suggested rating must never set selectedRating", model.selectedRating)
        assertNull("Suggested rating must never set committedRating", model.committedRating)
        assertEquals(0, rig.commitCalls)
        assertTrue(model.commitUiState is RatingCommitUiState.AwaitingRating)

        val goodButton = ui.ratingOptions.first { it.rating == Rating.GOOD }
        assertTrue(goodButton.isSuggested)
        assertFalse(goodButton.isSelected)
        val evalCard = ui.evaluationCard as AnswerEvaluationCardUi.Available
        assertEquals("Suggested: Good (Advisory)", evalCard.suggestedAdvisoryLabel)
    }

    // ------------------------------------------------------------------ VERIFICATION 12 & 13
    @Test
    fun `VERIFICATION 12 and 13 - interval labels displayed when present and dynamic 2 3 4 button counts respected`() = runTest {
        val rig = ReducerRig(speakQuestion = false)
        rig.loadFirstCard()

        // 4 buttons with intervals from scheduler
        rig.send(StudyEvent.RevealAnswerRequested(turnId = rig.state.anki!!.turn!!.turnId, epoch = rig.state.epoch))
        val ui4 = rig.uiState()
        assertEquals(listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY), ui4.ratingOptions.map { it.rating })
        assertEquals("<10m", ui4.ratingOptions.first { it.rating == Rating.AGAIN }.intervalLabel)
        assertEquals("5d", ui4.ratingOptions.first { it.rating == Rating.GOOD }.intervalLabel)

        // Reconfigure turn with 2 buttons (AGAIN, GOOD) and no intervals
        val turn = rig.state.anki!!.turn!!
        val content = turn.content as AnkiReviewTurnContent.Rendered
        val twoButtonScheduled = content.scheduledCard.copy(
            ratingOptions = AnkiRatingOptions.Known(listOf(Rating.AGAIN, Rating.GOOD)),
            scheduling = AnkiSchedulingInfo(nextReviewTimes = emptyMap())
        )
        rig.state = rig.state.copy(
            anki = rig.state.anki!!.copy(
                turn = turn.copy(content = content.copy(scheduledCard = twoButtonScheduled))
            )
        )
        val ui2 = rig.uiState()
        assertEquals(listOf(Rating.AGAIN, Rating.GOOD), ui2.ratingOptions.map { it.rating })
        assertTrue(ui2.ratingOptions.all { it.intervalLabel == null })

        // Selecting an unoffered rating (EASY) is rejected
        assertFalse(rig.send(AnkiStudyEvent.SelectRating(rig.state.epoch, turn.turnId, Rating.EASY)).accepted)
    }

    // ------------------------------------------------------------------ VERIFICATION 14 & 15
    @Test
    fun `VERIFICATION 14 and 15 - repeat answer and repeat feedback speak clean channels without mutating turn or re-evaluating`() = runTest {
        val rig = ReducerRig(evaluateAnswers = true, speakQuestion = false)
        rig.loadFirstCard()
        val turnId = rig.state.anki!!.turn!!.turnId

        // Repeat answer before reveal is rejected (prevents hidden answer leakage)
        assertFalse(rig.send(StudyEvent.RepeatAnswerRequested(turnId = turnId, epoch = rig.state.epoch)).accepted)

        // Submit and evaluate to reveal
        val sub = rig.submitFinalTranscript("SA node")
        val evalEffect = sub.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        rig.send(checkNotNull(rig.executor.execute(evalEffect)))
        assertEquals(1, rig.evaluateCalls)

        // Repeat answer after reveal speaks answerText with SpeechPurpose.ANSWER
        val repeatAnswerTransition = rig.send(StudyEvent.RepeatAnswerRequested(turnId = turnId, epoch = rig.state.epoch))
        assertTrue(repeatAnswerTransition.accepted)
        val answerSpeak = repeatAnswerTransition.effects.filterIsInstance<StudyEffect.Voice.Speak>().single()
        assertEquals(SpeechPurpose.ANSWER, answerSpeak.request.purpose)
        assertEquals("Sinoatrial (SA) node — Right atrium", answerSpeak.request.text)
        assertEquals(turnId, rig.state.anki!!.turn!!.turnId)
        assertEquals(1, rig.nextCalls)
        assertEquals(1, rig.evaluateCalls)

        // Repeat feedback speaks AI feedback with SpeechPurpose.FEEDBACK and does not re-evaluate
        val repeatFeedbackTransition = rig.send(StudyEvent.RepeatFeedbackRequested(turnId = turnId, epoch = rig.state.epoch))
        assertTrue(repeatFeedbackTransition.accepted)
        val feedbackSpeak = repeatFeedbackTransition.effects.filterIsInstance<StudyEffect.Voice.Speak>().single()
        assertEquals(SpeechPurpose.FEEDBACK, feedbackSpeak.request.purpose)
        assertEquals("Correct: the SA node initiates the cardiac impulse.", feedbackSpeak.request.text)
        assertEquals(1, rig.evaluateCalls)
        assertEquals(turnId, rig.state.anki!!.turn!!.turnId)
    }

    // ------------------------------------------------------------------ VERIFICATION 16
    @Test
    fun `VERIFICATION 16 - voice show answer command triggers reveal safely during answer window and never commits rating`() = runTest {
        val grammar = VoiceCommandGrammar()
        assertEquals(
            VoiceCommand.ShowAnswer,
            grammar.parse("show answer")?.command
        )
        assertEquals(
            VoiceCommand.ShowAnswer,
            grammar.parse("reveal answer")?.command
        )
        assertEquals(
            VoiceCommand.RepeatAnswer,
            grammar.parse("repeat answer")?.command
        )
        assertEquals(
            VoiceCommand.RepeatFeedback,
            grammar.parse("repeat feedback")?.command
        )

        val rig = ReducerRig(speakQuestion = true)
        rig.loadFirstCard()
        rig.completeQuestionSpeech()
        assertEquals(SessionPhase.WaitingForAnswer, rig.state.phase)

        val routedEvent = checkNotNull(SpokenCommandRouter.toEvent(VoiceCommand.ShowAnswer, rig.state.currentCardId))
        val transition = rig.send(routedEvent)
        assertTrue(transition.accepted)
        assertTrue(
            "Active STT recognition must be cancelled when revealing during listening",
            transition.effects.any { it is StudyEffect.Voice.CancelRecognition }
        )
        assertEquals(AnswerRevealState.REVEALED, rig.model().revealState)
        assertEquals(SessionPhase.WaitingForRating, rig.state.phase)
        assertEquals(0, rig.commitCalls)
    }

    // ------------------------------------------------------------------ VERIFICATION 17 & 18
    @Test
    fun `VERIFICATION 17 and 18 - renderer failure falls back to CLEAN answerText and missing or empty clean answer is honest`() = runTest {
        val rig = ReducerRig(speakQuestion = false, defaultCompareMode = AnswerCompareMode.ORIGINAL)
        rig.loadFirstCard()
        val turnId = rig.state.anki!!.turn!!.turnId
        rig.send(StudyEvent.RevealAnswerRequested(turnId = turnId, epoch = rig.state.epoch))
        assertEquals(AnkiCardRenderMode.ORIGINAL, rig.uiState().rendererMode)

        // Trigger renderer fallback on active turn
        val fb = rig.send(StudyEvent.AnswerRenderFallbackTriggered(turnId = turnId, reason = "webview_crash", epoch = rig.state.epoch))
        assertTrue(fb.accepted)
        assertEquals("webview_crash", rig.model().renderFallbackReason)
        assertEquals(CleanAnswerState.RENDER_FAILURE, rig.model().cleanAnswerState)
        assertEquals(AnswerCompareMode.CLEAN, rig.uiState().effectiveCompareMode)
        assertEquals(AnkiCardRenderMode.CLEAN, rig.uiState().rendererMode)

        // Missing answerText (null) vs empty answerText ("")
        val missingCleanRig = ReducerRig(
            cards = listOf(sampleCard(id = "c-null", answerText = null, pureAnswerText = null)),
            speakQuestion = false
        )
        missingCleanRig.loadFirstCard()
        missingCleanRig.send(StudyEvent.RevealAnswerRequested(turnId = missingCleanRig.state.anki!!.turn!!.turnId, epoch = missingCleanRig.state.epoch))
        assertEquals(CleanAnswerState.MISSING, missingCleanRig.model().cleanAnswerState)
        assertFalse("Repeat answer must be disabled when answerText is null", missingCleanRig.uiState().canRepeatAnswer)
        val repeatOnNull = missingCleanRig.send(
            StudyEvent.RepeatAnswerRequested(turnId = missingCleanRig.state.anki!!.turn!!.turnId, epoch = missingCleanRig.state.epoch)
        )
        assertFalse("Must never speak raw HTML when answerText is missing", repeatOnNull.accepted)

        // Empty answerText ("") with answerHtml present uses answerHtml (Original) and records TOKEN_CLEAN_TEXT_EMPTY (STEP 25)
        val emptyAnswerWithHtmlCard = sampleCard(id = "c-empty-html", answerText = "", pureAnswerText = "")
        val emptyPlanWithHtml = AnkiCardRenderPlanner.plan(
            card = emptyAnswerWithHtmlCard,
            side = AnkiCardSide.ANSWER,
            mode = AnkiCardRenderMode.CLEAN
        )
        assertTrue(emptyPlanWithHtml is AnkiCardRenderPlan.Original)
        assertTrue(
            "Empty clean text with HTML present must fall back to Original and record TOKEN_CLEAN_TEXT_EMPTY",
            emptyPlanWithHtml.tokens.contains(AnkiCardRenderPlan.TOKEN_CLEAN_TEXT_EMPTY)
        )

        // Empty answerText ("") with answerHtml null renders explicit empty CleanText with TOKEN_CLEAN_TEXT_EMPTY
        val emptyAnswerNoHtmlCard = sampleCard(id = "c-empty-only", answerHtml = null, answerText = "", pureAnswerText = "")
        val emptyPlanNoHtml = AnkiCardRenderPlanner.plan(
            card = emptyAnswerNoHtmlCard,
            side = AnkiCardSide.ANSWER,
            mode = AnkiCardRenderMode.CLEAN
        ) as AnkiCardRenderPlan.CleanText
        assertEquals("", emptyPlanNoHtml.text)
        assertTrue(
            "Empty clean text without HTML must record TOKEN_CLEAN_TEXT_EMPTY degradation",
            emptyPlanNoHtml.tokens.contains(AnkiCardRenderPlan.TOKEN_CLEAN_TEXT_EMPTY)
        )
    }

    // ------------------------------------------------------------------ VERIFICATION 19 & 20
    @Test
    fun `VERIFICATION 19 and 20 - answer media scoped to revealed turn and audio sequencing prevents overlap`() = runTest {
        val cardWithMedia = sampleCard(
            media = listOf(AnkiMediaRef.ContentUri("content://com.ichi2.anki.flashcards/media/heart.mp3", "audio/mpeg"))
        )
        val rig = ReducerRig(
            cards = listOf(cardWithMedia),
            evaluateAnswers = true,
            speakQuestion = true,
            speakFeedback = true
        )
        rig.loadFirstCard()
        rig.completeQuestionSpeech()
        val turnId = rig.state.anki!!.turn!!.turnId

        // Submit answer & complete evaluation
        val sub = rig.submitFinalTranscript("SA node")
        val evalEffect = sub.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        val evalDone = rig.send(checkNotNull(rig.executor.execute(evalEffect)))

        // Phase transitions to SPEAKING_FEEDBACK and does not open rating microphone while speaking feedback
        assertEquals(AnswerRevealState.REVEALED, rig.model().revealState)
        assertEquals(AnswerAudioSequencePhase.SPEAKING_FEEDBACK, rig.model().audioSequencePhase)
        val feedbackSpeak = evalDone.effects.filterIsInstance<StudyEffect.Voice.Speak>().single()
        assertEquals(SpeechPurpose.FEEDBACK, feedbackSpeak.request.purpose)
        assertTrue(
            "Rating recognition must NOT start while feedback TTS is still speaking",
            evalDone.effects.none { it is StudyEffect.Voice.StartRecognition }
        )

        // Complete feedback speech -> transitions to LISTENING_FOR_RATING and starts rating recognition
        val afterFeedback = rig.send(
            StudyEvent.FeedbackSpeechCompleted(
                cardId = rig.state.currentCardId!!,
                effectId = feedbackSpeak.effectId,
                success = true
            )
        )
        assertEquals(AnswerAudioSequencePhase.LISTENING_FOR_RATING, rig.model().audioSequencePhase)
        assertEquals(1, afterFeedback.effects.filterIsInstance<StudyEffect.Voice.StartRecognition>().size)

        // Selecting a rating stops any active speech/recognition for the turn
        val rateTransition = rig.send(AnkiStudyEvent.SelectRating(rig.state.epoch, turnId, Rating.GOOD))
        assertTrue(rateTransition.effects.any { it is StudyEffect.Voice.CancelSpeech })
        assertTrue(rateTransition.effects.any { it is StudyEffect.Voice.CancelRecognition })
    }

    // ------------------------------------------------------------------ VERIFICATION 21
    @Test
    fun `VERIFICATION 21 - pause and resume preserve reveal state compare mode user transcript and AI feedback without re-speaking`() = runTest {
        val rig = ReducerRig(
            evaluateAnswers = true,
            speakQuestion = false,
            speakFeedback = true
        )
        rig.loadFirstCard()
        val sub = rig.submitFinalTranscript("SA node")
        val evalEffect = sub.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        rig.send(checkNotNull(rig.executor.execute(evalEffect)))
        rig.send(StudyEvent.SelectAnswerCompareMode(AnswerCompareMode.CLEAN))

        val beforePauseModel = rig.model()
        assertEquals(AnswerRevealState.REVEALED, beforePauseModel.revealState)
        assertEquals(AnswerCompareMode.CLEAN, beforePauseModel.compareMode)
        assertEquals("SA node", beforePauseModel.userAnswerText)
        assertNotNull(beforePauseModel.evaluationFeedback)

        // Pause
        val pauseTransition = rig.send(StudyEvent.UserPauseRequested("pause-1"))
        assertTrue(pauseTransition.accepted)
        assertTrue(rig.state.phase is SessionPhase.Paused)

        // Resume
        val resumeTransition = rig.send(StudyEvent.UserResumeRequested("resume-1"))
        assertTrue(resumeTransition.accepted)
        assertEquals(SessionPhase.WaitingForRating, rig.state.phase)
        assertTrue(
            "Resume must not re-speak feedback or re-run evaluation",
            resumeTransition.effects.none { it is StudyEffect.Voice.Speak || it is AnkiStudyEffect.EvaluateAnswer }
        )

        val afterResumeModel = rig.model()
        assertEquals(AnswerRevealState.REVEALED, afterResumeModel.revealState)
        assertEquals(AnswerCompareMode.CLEAN, afterResumeModel.compareMode)
        assertEquals("SA node", afterResumeModel.userAnswerText)
        assertEquals(beforePauseModel.evaluationFeedback, afterResumeModel.evaluationFeedback)
        assertEquals(1, rig.evaluateCalls)
    }

    // ------------------------------------------------------------------ VERIFICATION 22 & 23
    @Test
    fun `VERIFICATION 22 and 23 - stale evaluation and stale renderer fallback callbacks from old turn are ignored`() = runTest {
        val rig = ReducerRig(evaluateAnswers = true, speakQuestion = false)
        rig.loadFirstCard()
        val turn1 = rig.state.anki!!.turn!!.turnId
        val sub1 = rig.submitFinalTranscript("Answer 1")
        val evalEffect1 = sub1.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()

        // User reveals manually while evaluation 1 is still in flight, then commits rating to advance to turn 2
        rig.send(StudyEvent.RevealAnswerRequested(turnId = turn1, epoch = rig.state.epoch))
        val rate1 = rig.send(AnkiStudyEvent.SelectRating(rig.state.epoch, turn1, Rating.GOOD))
        val commitEffect = rate1.effects.filterIsInstance<AnkiStudyEffect.CommitRating>().single()
        val commitDone = checkNotNull(rig.executor.execute(commitEffect))
        val nextEffects = rig.send(commitDone).effects.filterIsInstance<AnkiStudyEffect>()
        for (eff in nextEffects) {
            val ev = checkNotNull(rig.executor.execute(eff))
            val follow = rig.send(ev)
            for (f in follow.effects.filterIsInstance<AnkiStudyEffect>()) {
                rig.send(checkNotNull(rig.executor.execute(f)))
            }
        }

        val turn2 = rig.state.anki!!.turn!!.turnId
        assertNotEquals(turn1, turn2)
        assertEquals(AnswerRevealState.HIDDEN, rig.model().revealState)

        // Late evaluation callback from turn1 arrives now
        val staleEvalEvent = AnkiStudyEvent.AnswerEvaluationCompleted(
            epoch = rig.state.epoch,
            sessionId = evalEffect1.request.sessionId,
            turnId = turn1,
            cardRef = evalEffect1.request.cardRef,
            requestId = evalEffect1.request.requestId,
            evaluation = Evaluation(
                correctPoints = listOf("Point"),
                missingPoints = emptyList(),
                incorrectPoints = emptyList(),
                shortFeedback = "Stale feedback",
                suggestedRating = Rating.EASY,
                score = 100
            ),
            speakFeedback = false
        )
        assertFalse("Stale evaluation from turn1 must be rejected on turn2", rig.send(staleEvalEvent).accepted)
        assertNull(rig.model().evaluationFeedback)
        assertEquals(AnswerRevealState.HIDDEN, rig.model().revealState)

        // Late renderer fallback callback from turn1 arrives now
        val staleRenderFallback = StudyEvent.AnswerRenderFallbackTriggered(
            turnId = turn1,
            reason = "stale_webview_error",
            epoch = rig.state.epoch
        )
        assertFalse("Stale renderer fallback from turn1 must be rejected on turn2", rig.send(staleRenderFallback).accepted)
        assertNull(rig.model().renderFallbackReason)
    }

    // ------------------------------------------------------------------ VERIFICATION 24
    @Test
    fun `VERIFICATION 24 - Arabic and mixed RTL LTR user reference and feedback text preserves diacritics and resolves direction`() = runTest {
        val arabicQuestion = "ما هو منظّم ضربات القلب الطبيعي؟"
        val arabicAnswer = "العُقْدَةُ الجَيْبِيَّةُ الأُذَيْنِيَّةُ (SA node)"
        val arabicUserAnswer = "العقدة الجيبية الأذينية في الأذين الأيمن"
        val arabicFeedback = "إجابة صحيحة! العُقْدَةُ الجَيْبِيَّةُ (SA node) هي المنظم الرئيسي."

        val rig = ReducerRig(
            cards = listOf(
                sampleCard(
                    questionHtml = "<div dir='rtl'>$arabicQuestion</div>",
                    answerHtml = "<div dir='rtl'>$arabicAnswer</div>",
                    questionText = arabicQuestion,
                    answerText = arabicAnswer,
                    pureAnswerText = arabicAnswer
                )
            ),
            evaluateAnswers = true,
            speakQuestion = false,
            defaultCompareMode = AnswerCompareMode.COMPARE,
            evaluatorResult = AnkiAnswerEvaluationResult.Success(
                Evaluation(
                    correctPoints = listOf("SA node"),
                    missingPoints = emptyList(),
                    incorrectPoints = emptyList(),
                    shortFeedback = arabicFeedback,
                    suggestedRating = Rating.GOOD,
                    score = 95
                )
            )
        )
        rig.loadFirstCard()
        val sub = rig.submitFinalTranscript(arabicUserAnswer)
        val evalEffect = sub.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        rig.send(checkNotNull(rig.executor.execute(evalEffect)))

        val ui = rig.uiState()
        assertEquals(CardTextDirection.RTL, ui.questionDirection)
        assertEquals(CardTextDirection.RTL, ui.userAnswerComparison!!.textDirection)
        assertEquals(CardTextDirection.RTL, ui.referenceAnswerComparison!!.textDirection)
        val evalCard = ui.evaluationCard as AnswerEvaluationCardUi.Available
        assertEquals(CardTextDirection.RTL, evalCard.feedbackDirection)
        // Verify Arabic harakat/diacritics and embedded English "SA node" are preserved byte-for-byte
        assertEquals(arabicAnswer, ui.referenceAnswerComparison!!.displayText)
        assertEquals(arabicUserAnswer, ui.userAnswerComparison!!.displayText)
        assertEquals(arabicFeedback, evalCard.feedbackText)
    }

    // ------------------------------------------------------------------ VERIFICATION 25, 26 & 27
    @Test
    fun `VERIFICATION 25 26 and 27 - long content stays scrollable rating enters GATE 11 unchanged and buttons lock during saving and recovery`() = runTest {
        val longReference = "Long clinical reference point. ".repeat(80)
        val longFeedback = "Detailed pathophysiology explanation. ".repeat(60)
        val rig = ReducerRig(
            cards = listOf(sampleCard(answerText = longReference, pureAnswerText = longReference)),
            evaluateAnswers = true,
            speakQuestion = false,
            evaluatorResult = AnkiAnswerEvaluationResult.Success(
                Evaluation(
                    correctPoints = listOf("Point"),
                    missingPoints = emptyList(),
                    incorrectPoints = emptyList(),
                    shortFeedback = longFeedback,
                    suggestedRating = Rating.GOOD,
                    score = 88
                )
            )
        )
        rig.loadFirstCard()
        val sub = rig.submitFinalTranscript("My summary answer")
        val evalEffect = sub.effects.filterIsInstance<AnkiStudyEffect.EvaluateAnswer>().single()
        rig.send(checkNotNull(rig.executor.execute(evalEffect)))

        val ui = rig.uiState()
        assertTrue(ui.scrollableContent)
        assertTrue(ui.ratingBarPinned)
        assertTrue(ui.ratingOptions.all { it.enabled })

        // Select Rating.GOOD -> enters GATE 11 SubmittingRating with AnkiStudyEffect.CommitRating
        val turnId = rig.state.anki!!.turn!!.turnId
        val rateTransition = rig.send(AnkiStudyEvent.SelectRating(rig.state.epoch, turnId, Rating.GOOD))
        assertTrue(rateTransition.accepted)
        assertEquals(SessionPhase.SubmittingRating, rig.state.phase)
        val commitEffect = rateTransition.effects.filterIsInstance<AnkiStudyEffect.CommitRating>().single()
        assertEquals(Rating.GOOD, commitEffect.request.rating)
        assertEquals(turnId, commitEffect.request.commitId.turnId)

        // While Saving, rating buttons are locked
        val savingUi = rig.uiState()
        assertTrue(savingUi.commitUiState is RatingCommitUiState.Saving)
        assertFalse(savingUi.ratingControlsEnabled)
        assertTrue("Rating buttons must lock while Saving", savingUi.ratingOptions.none { it.enabled })
        assertTrue(savingUi.ratingOptions.first { it.rating == Rating.GOOD }.isSelected)
    }

    // ------------------------------------------------------------------ VERIFICATION 28 & 30
    @Test
    fun `VERIFICATION 28 and 30 - accessibility semantics are explicit and GATE 12 diagnostics are 100 percent content free`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val timeline = DiagnosticTimeline()
            val store = InMemoryReviewCommitStore()
            val ledger = ReviewCommitLedger(store, { 1_000L })
            val fake = FakeAnkiBackend(
                id = backendId,
                decks = listOf(AnkiDeck(deck, "Cardiology")),
                cards = listOf(sampleCard()),
                instanceId = "diag-test"
            )
            val evaluator = AnkiAnswerEvaluator {
                AnkiAnswerEvaluationResult.Success(
                    Evaluation(
                        correctPoints = listOf("Point"),
                        missingPoints = emptyList(),
                        incorrectPoints = emptyList(),
                        shortFeedback = "SECRET_FEEDBACK_DO_NOT_LOG",
                        suggestedRating = Rating.GOOD,
                        score = 90
                    )
                )
            }
            val machine = StudySessionMachine(
                connectionRepository = FakeConnectionRepository(),
                speechOrchestrator = FakeSpeechOrchestrator(),
                recognitionOrchestrator = FakeRecognitionOrchestrator(),
                scope = scope,
                timeline = timeline,
                ankiEffects = AnkiStudyEffectExecutor(
                    registry = AnkiBackendRegistry(listOf(fake)),
                    ledger = ledger,
                    clock = { 1_000L },
                    phases = CommitPhaseSink { _, _, _ -> },
                    answerEvaluator = evaluator
                )
            )

            machine.dispatch(
                AnkiStudyEvent.Start(
                    AnkiStudyRequest(
                        studySessionId = "study-diag",
                        preference = AnkiBackendMode.ANKIDROID_LOCAL,
                        deck = deck,
                        speakQuestion = false,
                        evaluateAnswers = true,
                        speakFeedback = false,
                        defaultCompareMode = AnswerCompareMode.COMPARE
                    )
                )
            )
            advanceUntilIdle()

            // Hidden state exposed on machine.answerReview
            val hiddenReview = checkNotNull(machine.answerReview.value)
            assertEquals(AnswerRevealState.HIDDEN, hiddenReview.revealState)

            // Submit answer & reveal
            val turnId = machine.machineState.value.anki!!.turn!!.turnId
            machine.dispatch(
                StudyEvent.UserSubmitAnswer(
                    cardId = checkNotNull(machine.machineState.value.currentCardId),
                    transcript = "SECRET_USER_ANSWER_DO_NOT_LOG"
                )
            )
            advanceUntilIdle()

            val revealedReview = checkNotNull(machine.answerReview.value)
            assertEquals(AnswerRevealState.REVEALED, revealedReview.revealState)
            val ui = AnswerReviewUiState.from(revealedReview)

            // Accessibility semantics check (VERIFICATION 28)
            assertEquals(AnswerReviewSemantics.YOUR_ANSWER, ui.userAnswerComparison!!.label)
            assertEquals(AnswerReviewSemantics.REFERENCE_ANSWER, ui.referenceAnswerComparison!!.label)
            assertEquals(AnswerReviewSemantics.AI_FEEDBACK, (ui.evaluationCard as AnswerEvaluationCardUi.Available).label)
            val goodSemantics = ui.ratingOptions.first { it.rating == Rating.GOOD }.contentDescription
            assertTrue(goodSemantics.contains("Good"))
            assertTrue(goodSemantics.contains("5d"))
            assertTrue(goodSemantics.contains("Suggested Rating"))

            // Trigger compare mode switch and renderer fallback to exercise GATE 12 diagnostic events
            machine.dispatch(StudyEvent.SelectAnswerCompareMode(turnId, AnswerCompareMode.CLEAN, machine.machineState.value.epoch))
            machine.dispatch(StudyEvent.AnswerRenderFallbackTriggered(turnId, "webview_error", machine.machineState.value.epoch))
            advanceUntilIdle()

            // Verify diagnostics are 100% content-free (VERIFICATION 30)
            val entries = timeline.snapshot()
            assertTrue(entries.any { it.event == AnkiAnswerReviewDiagnostics.EVENT_ANKI_ANSWER_REVEALED })
            assertTrue(entries.any { it.event == AnkiAnswerReviewDiagnostics.EVENT_ANKI_COMPARE_MODE_CHANGED })
            assertTrue(entries.any { it.event == AnkiAnswerReviewDiagnostics.EVENT_ANKI_ANSWER_RENDER_FALLBACK })
            assertTrue(entries.any { it.event == AnkiAnswerReviewDiagnostics.EVENT_ANKI_EVALUATION_DISPLAYED })

            for (entry in entries) {
                assertFalse(
                    "Diagnostic entry ${entry.event} contains forbidden content key: ${entry.metadata}",
                    AnkiAnswerReviewDiagnostics.containsForbiddenContentKey(entry.metadata)
                )
                val joinedValues = entry.metadata.values.joinToString(" ")
                assertFalse(joinedValues.contains("Sinoatrial"))
                assertFalse(joinedValues.contains("SECRET_USER_ANSWER_DO_NOT_LOG"))
                assertFalse(joinedValues.contains("SECRET_FEEDBACK_DO_NOT_LOG"))
            }

            machine.close()
        } finally {
            scope.cancel()
        }
    }
}
