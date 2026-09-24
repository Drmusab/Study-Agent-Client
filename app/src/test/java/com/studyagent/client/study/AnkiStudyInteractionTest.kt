package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Reducer + real read executor + backend spy. No Android renderer or scheduling writes. */
class AnkiStudyInteractionTest {
    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val deck = AnkiDeckRef(backendId, "1", "collection")
    private val card = AnkiRenderedCard(
        AnkiCardRef(backendId, cardId = "10", collectionKey = "collection"),
        "<b>Question</b>", "<b>Reference</b>", "Question", "Reference", "Reference",
        deckRef = deck
    )
    private val request = AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, deck)

    private inner class Harness(cards: List<AnkiRenderedCard> = listOf(card), speak: Boolean = true) {
        private val fake = FakeAnkiBackend(id = backendId, decks = listOf(AnkiDeck(deck, "Deck")), cards = cards,
            instanceId = "test")
        var nextCalls = 0
        var commitCalls = 0
        private val spy = object : AnkiBackend by fake {
            override suspend fun nextCard(session: AnkiReviewSession): NextCardResult {
                nextCalls++
                return fake.nextCard(session)
            }
            override suspend fun commitRating(request: CommitRatingRequest): CommitRatingResult {
                commitCalls++
                // These read-path tests never *execute* a CommitRating effect; GATE 11's commit
                // flow is covered by AnkiRatingCommitFlowTest with a durable ledger.
                error("read-path tests must never execute a commit effect")
            }
        }
        val executor = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(spy)))
        var state = SessionMachineState.initial()
        val start = AnkiStudyEvent.Start(request.copy(speakQuestion = speak))
        val effects = mutableListOf<StudyEffect>()
        fun send(event: StudyEvent): Transition = StudyReducer.reduce(state, event, 100L).also {
            state = it.newState
            effects.addAll(it.effects)
            assertFalse("No PC traffic in Anki interaction", it.effects.any { effect -> effect is StudyEffect.Network })
        }
        suspend fun load() {
            var transition = send(start)
            repeat(3) {
                val effect = transition.effects.filterIsInstance<AnkiStudyEffect>().single()
                transition = send(checkNotNull(executor.execute(effect)))
            }
        }
        fun spoken(success: Boolean = true): StudyEvent.QuestionSpeechCompleted {
            val event = StudyEvent.QuestionSpeechCompleted(checkNotNull(state.currentCardId),
                checkNotNull(state.activeSpeechEffectId), success)
            send(event)
            return event
        }
        fun answer(): StudyEvent.RecognitionCompleted {
            val event = StudyEvent.RecognitionCompleted(state.currentCardId, state.cardTurn?.turnId,
                "My answer", false, state.activeRecognitionEffectId)
            send(event)
            return event
        }
        fun assertNoMutation() {
            assertEquals(0, commitCalls)
            assertEquals(0, state.session?.totalReviewedInSession ?: 0)
        }
    }

    @Test fun `manual evaluation voice turn prepares one commit and never advances before COMMITTED`() = runTest {
        val h = Harness()
        h.load()
        val turnId = h.state.anki!!.turn!!.turnId
        assertEquals(SessionPhase.SpeakingQuestion, h.state.phase)
        assertEquals("Question", h.effects.filterIsInstance<StudyEffect.Voice.Speak>().single().request.text)
        assertEquals(turnId.value, h.state.cardTurn!!.turnId)
        assertFalse(h.state.cardTurn!!.answerRevealed)
        h.spoken()
        assertEquals(SessionPhase.WaitingForAnswer, h.state.phase)
        h.answer()
        assertEquals(SessionPhase.WaitingForRating, h.state.phase)
        assertTrue(h.state.cardTurn!!.answerRevealed)
        assertEquals("My answer", h.state.anki!!.transcript)
        assertEquals(turnId, h.state.anki!!.turn!!.turnId)
        val selected = h.send(AnkiStudyEvent.SelectRating(h.state.epoch, turnId, Rating.GOOD))
        // GATE 11: selection prepares exactly one commit effect and enters SubmittingRating; it
        // never emits a next-card read (the next card waits for a COMMITTED outcome).
        val commit = selected.effects.filterIsInstance<AnkiStudyEffect.CommitRating>().single()
        assertEquals(Rating.GOOD, commit.request.rating)
        assertEquals(turnId, commit.request.commitId.turnId)
        assertTrue(selected.effects.none { it is AnkiStudyEffect.Next })
        assertEquals(SessionPhase.SubmittingRating, h.state.phase)
        assertEquals(Rating.GOOD, h.state.anki!!.selectedRating)
        assertNull(h.state.cardTurn!!.suggestedRating)
        assertEquals(1, h.nextCalls)
        assertFalse(h.send(AnkiStudyEvent.SelectRating(h.state.epoch, turnId, Rating.EASY)).accepted)
        assertEquals(Rating.GOOD, h.state.anki!!.selectedRating)
        h.assertNoMutation()
    }

    @Test fun `voice disabled can reveal without microphone or PC`() = runTest {
        val h = Harness(speak = false)
        h.load()
        assertEquals(SessionPhase.WaitingForAnswer, h.state.phase)
        assertTrue(h.effects.none { it is StudyEffect.Voice.Speak || it is StudyEffect.Voice.StartRecognition })
        h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
        assertEquals(SessionPhase.WaitingForRating, h.state.phase)
        h.assertNoMutation()
    }

    @Test fun `speech failure keeps card and does not open microphone`() = runTest {
        val h = Harness()
        h.load()
        h.spoken(false)
        assertEquals(SessionPhase.WaitingForAnswer, h.state.phase)
        assertEquals(SessionProblem.TTS_UNAVAILABLE, h.state.error!!.problem)
        assertTrue(h.effects.none { it is StudyEffect.Voice.StartRecognition })
        h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
        assertEquals(SessionPhase.WaitingForRating, h.state.phase)
        h.assertNoMutation()
    }

    @Test fun `duplicate voice completions and transcripts do not advance twice`() = runTest {
        val h = Harness()
        h.load()
        val speech = h.spoken()
        assertFalse(h.send(speech).accepted)
        val answer = h.answer()
        assertFalse(h.send(answer).accepted)
        assertEquals(1, h.effects.filterIsInstance<StudyEffect.Voice.StartRecognition>().size)
        assertEquals(1, h.nextCalls)
        h.assertNoMutation()
    }

    @Test fun `same turn old recognition request is rejected after repeat`() = runTest {
        val h = Harness()
        h.load()
        h.spoken()
        val oldRequest = h.state.activeRecognitionEffectId
        h.send(StudyEvent.UserRequestRepeat(h.state.currentCardId))
        h.spoken()
        assertFalse(h.send(StudyEvent.RecognitionCompleted(h.state.currentCardId, h.state.cardTurn!!.turnId,
            "old answer", false, oldRequest)).accepted)
        assertEquals(SessionPhase.WaitingForAnswer, h.state.phase)
        h.assertNoMutation()
    }

    @Test fun `pause rejects late speech resume keeps turn and a submitting rating cannot be paused away`() = runTest {
        val h = Harness()
        h.load()
        val turn = h.state.anki!!.turn
        val speech = StudyEvent.QuestionSpeechCompleted(h.state.currentCardId!!, h.state.activeSpeechEffectId!!, true)
        h.send(StudyEvent.UserPauseRequested("pause"))
        assertFalse(h.send(speech).accepted)
        h.send(StudyEvent.UserResumeRequested("resume"))
        assertEquals(SessionPhase.WaitingForAnswer, h.state.phase)
        assertEquals(turn, h.state.anki!!.turn)
        h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
        h.send(StudyEvent.UserRateCard(Rating.GOOD, h.state.currentCardId!!))
        // GATE 11: once the commit is prepared, pause/resume cannot rewind it to WaitingForRating
        // (that would re-open rating input while a mutation may be in flight).
        assertFalse(h.send(StudyEvent.UserPauseRequested("pause2")).accepted)
        assertFalse(h.send(StudyEvent.UserResumeRequested("resume2")).accepted)
        assertEquals(SessionPhase.SubmittingRating, h.state.phase)
        assertEquals(Rating.GOOD, h.state.anki!!.selectedRating)
        assertEquals(1, h.nextCalls)
        h.assertNoMutation()
    }

    @Test fun `remote callbacks skip and connection changes cannot replace local turn`() = runTest {
        val h = Harness()
        h.load()
        val turn = h.state.anki!!.turn
        assertFalse(h.send(StudyEvent.ServerRatingSaved(h.state.sessionId, h.state.currentCardId!!,
            Rating.GOOD, "1d", "remote")).accepted)
        assertFalse(h.send(StudyEvent.UserSkipRequested(h.state.currentCardId)).accepted)
        assertFalse(h.send(StudyEvent.ServerQuestionReceived(h.state.sessionId, "other", "untrusted",
            null, null, true, "remote-q")).accepted)
        h.send(StudyEvent.ConnectionLost("offline"))
        h.send(StudyEvent.ConnectionRestored("online"))
        assertEquals(turn, h.state.anki!!.turn)
        assertEquals(1, h.nextCalls)
        h.assertNoMutation()
    }

    @Test fun `scheduler exhaustion is successful completion without a review`() = runTest {
        val h = Harness(cards = emptyList())
        val begin = h.send(h.start).effects.filterIsInstance<AnkiStudyEffect>().single()
        val next = h.send(h.executor.execute(begin)!!).effects.filterIsInstance<AnkiStudyEffect>().single()
        h.send(h.executor.execute(next)!!)
        assertEquals(SessionPhase.Finished, h.state.phase)
        assertEquals(AnkiStudyCompletion.NO_DUE_CARDS, h.state.anki!!.completion)
        h.assertNoMutation()
    }

    @Test fun `stop rejects in flight begin and allows fresh epoch`() = runTest {
        val h = Harness()
        val effect = h.send(h.start).effects.filterIsInstance<AnkiStudyEffect>().single()
        val late = h.executor.execute(effect)!!
        h.send(StudyEvent.UserEndRequested("end"))
        assertEquals(AnkiStudyCompletion.USER_ENDED, h.state.anki!!.completion)
        assertFalse(h.send(late).accepted)
        h.send(AnkiStudyEvent.Start(request.copy(studySessionId = "study-2")))
        assertFalse(h.send(late).accepted)
        assertEquals(SessionPhase.Starting, h.state.phase)
        h.assertNoMutation()
    }

    @Test fun `hydration identity mismatch is an error and cannot speak`() = runTest {
        val h = Harness()
        var effects = h.send(h.start).effects
        repeat(2) {
            effects = h.send(h.executor.execute(effects.filterIsInstance<AnkiStudyEffect>().single())!!).effects
        }
        val turn = h.state.anki!!.turn!!
        val result = h.send(AnkiStudyEvent.Hydrated(h.state.epoch, turn.turnId,
            AnkiResult.Success(card.copy(ref = card.ref.copy(cardId = "wrong")))))
        assertTrue(h.state.anki!!.failure is AnkiError.StaleCardReference)
        assertTrue(h.state.phase is SessionPhase.Error)
        assertTrue(result.effects.none { it is StudyEffect.Voice.Speak })
        h.assertNoMutation()
    }

    @Test fun `stale hydration and stale rating cannot change active turn`() = runTest {
        val h = Harness()
        h.load()
        val original = h.state.anki
        assertFalse(h.send(AnkiStudyEvent.Hydrated(h.state.epoch - 1, ReviewTurnId("old"),
            AnkiResult.Success(card))).accepted)
        h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
        assertFalse(h.send(AnkiStudyEvent.SelectRating(h.state.epoch, ReviewTurnId("old"), Rating.GOOD)).accepted)
        assertEquals(original!!.turn, h.state.anki!!.turn)
        assertNull(h.state.anki!!.selectedRating)
        assertNull(h.state.anki!!.commit)
        h.assertNoMutation()
    }

    @Test fun `explicit missing local backend never falls back`() = runTest {
        val executor = AnkiStudyEffectExecutor(AnkiBackendRegistry(emptyList()))
        val event = executor.execute(AnkiStudyEffect.Begin(2, request, 100)) as AnkiStudyEvent.Begun
        assertTrue(event.result is AnkiResult.Failure)
    }

    @Test fun `foreign or deleted deck fails before begin`() = runTest {
        val fake = FakeAnkiBackend(id = backendId, decks = listOf(AnkiDeck(deck, "Deck")))
        val executor = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(fake)))
        val foreign = request.copy(deck = AnkiDeckRef(AnkiBackendId.PcAgent("other"), "1"))
        val foreignResult = executor.execute(AnkiStudyEffect.Begin(2, foreign, 100)) as AnkiStudyEvent.Begun
        assertTrue((foreignResult.result as AnkiResult.Failure).error is AnkiError.InvalidRequest)
        val deleted = request.copy(deck = deck.copy(deckId = "gone"))
        val deletedResult = executor.execute(AnkiStudyEffect.Begin(3, deleted, 100)) as AnkiStudyEvent.Begun
        assertTrue((deletedResult.result as AnkiResult.Failure).error is AnkiError.DeckNotFound)
    }

    @Test fun `only scheduler supplied rating options can be selected`() = runTest {
        val h = Harness()
        h.load()
        val turn = h.state.anki!!.turn!!
        val content = turn.content as AnkiReviewTurnContent.Rendered
        h.state = h.state.copy(anki = h.state.anki!!.copy(turn = turn.copy(content = content.copy(
            scheduledCard = content.scheduledCard.copy(ratingOptions = AnkiRatingOptions.Known(listOf(Rating.AGAIN)))
        ))))
        h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
        assertFalse(h.send(StudyEvent.UserRateCard(Rating.GOOD, h.state.currentCardId!!)).accepted)
        assertTrue(h.send(StudyEvent.UserRateCard(Rating.AGAIN, h.state.currentCardId!!)).accepted)
        assertEquals(1, h.nextCalls)
        h.assertNoMutation()
    }

    @Test fun `early final transcript and old speech effect are illegal`() = runTest {
        val h = Harness()
        h.load()
        assertFalse(h.send(StudyEvent.RecognitionCompleted(h.state.currentCardId, h.state.cardTurn!!.turnId,
            "too early", false, "old-request")).accepted)
        assertFalse(h.send(StudyEvent.QuestionSpeechCompleted(h.state.currentCardId!!, "old-speech", true)).accepted)
        assertEquals(SessionPhase.SpeakingQuestion, h.state.phase)
        h.assertNoMutation()
    }

}
