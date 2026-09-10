package com.studyagent.client

import app.cash.turbine.test
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.network.FakeAgentConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeAgentConnectionTest {

    @Test
    fun testCompleteStudyLoopWithFakeAgent() = runTest(timeout = 10.seconds) {
        val testScope = TestScope(testScheduler)
        val fakeAgent = FakeAgentConnection(testScope, simulateNetworkDelayMs = 0L)

        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        assertTrue(fakeAgent.connectionState.value.isConnected)

        fakeAgent.incomingMessages.test(timeout = 5.seconds) {
            // Start session
            fakeAgent.send(ClientMessage.StartSession(deck = "Toronto Notes"))

            val startMsg = awaitItem()
            assertTrue(startMsg is ServerMessage.SessionStarted)
            assertEquals("Toronto Notes", (startMsg as ServerMessage.SessionStarted).deck)

            val questionMsg = awaitItem()
            assertTrue(questionMsg is ServerMessage.Question)
            val q = questionMsg as ServerMessage.Question
            assertEquals("card-001", q.cardId)
            assertTrue(q.question.contains("indications for evacuation of an epidural hematoma"))

            // Submit spoken answer
            fakeAgent.send(
                ClientMessage.SubmitAnswer(
                    sessionId = (startMsg as ServerMessage.SessionStarted).sessionId,
                    cardId = q.cardId,
                    text = "Volume greater than 30 mL and midline shift greater than 5 mm"
                )
            )

            val evalMsg = awaitItem()
            assertTrue(evalMsg is ServerMessage.EvaluationResponse)
            val eval = evalMsg as ServerMessage.EvaluationResponse
            assertEquals("card-001", eval.cardId)
            assertTrue(eval.correctPoints.size >= 2)
            assertTrue(eval.missingPoints.contains("Neurological deterioration"))

            // Rate card Hard
            fakeAgent.send(
                ClientMessage.RateCard(
                    sessionId = (startMsg as ServerMessage.SessionStarted).sessionId,
                    cardId = q.cardId,
                    rating = Rating.HARD
                )
            )

            val ratingSavedMsg = awaitItem()
            assertTrue(ratingSavedMsg is ServerMessage.RatingSaved)
            assertEquals(Rating.HARD, (ratingSavedMsg as ServerMessage.RatingSaved).rating)

            // Next card delivered
            val nextQuestion = awaitItem()
            assertTrue(nextQuestion is ServerMessage.Question)
            assertEquals("card-002", (nextQuestion as ServerMessage.Question).cardId)
        }
    }
}
