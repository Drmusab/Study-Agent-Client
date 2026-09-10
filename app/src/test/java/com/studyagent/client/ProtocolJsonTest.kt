package com.studyagent.client

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.network.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolJsonTest {

    @Test
    fun testClientStartSessionSerialization() {
        val msg = ClientMessage.StartSession(
            deck = "Toronto Notes",
            mode = "review_due"
        )
        val json = ProtocolJson.encodeClientMessage(msg)
        assertTrue(json.contains("\"type\":\"start_session\""))
        assertTrue(json.contains("\"deck\":\"Toronto Notes\""))
        assertTrue(json.contains("\"protocol_version\":\"1\""))
    }

    @Test
    fun testClientSubmitAnswerSerialization() {
        val msg = ClientMessage.SubmitAnswer(
            sessionId = "sess-123",
            cardId = "card-456",
            text = "Volume greater than 30 mL"
        )
        val json = ProtocolJson.encodeClientMessage(msg)
        assertTrue(json.contains("\"type\":\"submit_answer\""))
        assertTrue(json.contains("\"card_id\":\"card-456\""))
        assertTrue(json.contains("\"text\":\"Volume greater than 30 mL\""))
    }

    @Test
    fun testClientRateCardSerialization() {
        val msg = ClientMessage.RateCard(
            sessionId = "sess-123",
            cardId = "card-456",
            rating = Rating.GOOD
        )
        val json = ProtocolJson.encodeClientMessage(msg)
        assertTrue(json.contains("\"type\":\"rate_card\""))
        assertTrue(json.contains("\"rating\":\"good\""))
    }

    @Test
    fun testServerQuestionDeserialization() {
        val json = """
            {
              "type": "question",
              "session_id": "abc123",
              "card_id": "12345",
              "question": "What are the indications for evacuation of an epidural hematoma?",
              "card_number": 25,
              "remaining": 420,
              "speak": true
            }
        """.trimIndent()

        val msg = ProtocolJson.decodeServerMessage(json)
        assertNotNull(msg)
        assertTrue(msg is ServerMessage.Question)
        val question = msg as ServerMessage.Question
        assertEquals("12345", question.cardId)
        assertEquals("What are the indications for evacuation of an epidural hematoma?", question.question)
        assertEquals(25, question.cardNumber)
        assertEquals(420, question.remaining)
        assertTrue(question.speak)
    }

    @Test
    fun testServerEvaluationDeserialization() {
        val json = """
            {
              "type": "evaluation",
              "card_id": "12345",
              "score": 82,
              "correct_points": [
                "Volume greater than 30 mL",
                "Midline shift greater than 5 mm"
              ],
              "missing_points": [
                "Neurological deterioration"
              ],
              "incorrect_points": [],
              "short_feedback": "Good answer. You missed neurological deterioration.",
              "suggested_rating": "hard"
            }
        """.trimIndent()

        val msg = ProtocolJson.decodeServerMessage(json)
        assertNotNull(msg)
        assertTrue(msg is ServerMessage.EvaluationResponse)
        val eval = msg as ServerMessage.EvaluationResponse
        assertEquals("12345", eval.cardId)
        assertEquals(82, eval.score)
        assertEquals(2, eval.correctPoints.size)
        assertEquals(1, eval.missingPoints.size)
        assertEquals(Rating.HARD, eval.suggestedRating)
        assertEquals("Good answer. You missed neurological deterioration.", eval.shortFeedback)
    }

    @Test
    fun testServerErrorFallback() {
        val json = """
            {
              "type": "error",
              "code": "DECK_NOT_FOUND",
              "message": "Specified deck does not exist in Anki"
            }
        """.trimIndent()

        val msg = ProtocolJson.decodeServerMessage(json)
        assertNotNull(msg)
        assertTrue(msg is ServerMessage.ErrorMessage)
        val error = msg as ServerMessage.ErrorMessage
        assertEquals("DECK_NOT_FOUND", error.code)
        assertEquals("Specified deck does not exist in Anki", error.message)
    }
}
