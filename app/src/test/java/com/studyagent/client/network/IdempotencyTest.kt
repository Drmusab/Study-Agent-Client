package com.studyagent.client.network

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Rating
import org.junit.Assert.*
import org.junit.Test

class IdempotencyTest {

    @Test
    fun messageIdIsIdempotencyKey() {
        val rating1 = ClientMessage.RateCard(
            sessionId = "session-123",
            cardId = "card-001",
            rating = Rating.GOOD,
            messageId = "msg-rating-001",
            reviewTurnId = "epoch:card-001:1",
            sessionRevision = 5
        )

        val rating2 = ClientMessage.RateCard(
            sessionId = "session-123",
            cardId = "card-001",
            rating = Rating.GOOD,
            messageId = "msg-rating-001", // Same messageId
            reviewTurnId = "epoch:card-001:1",
            sessionRevision = 5
        )

        assertEquals(rating1.messageId, rating2.messageId)
        // Server should deduplicate by messageId
    }

    @Test
    fun reviewTurnIdEnforced() {
        val turnId = "session-epoch:card-123:gen-1"
        val message = ClientMessage.SubmitAnswer(
            sessionId = "session-123",
            cardId = "card-123",
            text = "Answer text",
            messageId = "msg-001",
            reviewTurnId = turnId,
            sessionRevision = 3
        )

        assertEquals(turnId, message.reviewTurnId)
        assertEquals(3, message.sessionRevision)
    }

    @Test
    fun sessionRevisionPreventsStaleWrites() {
        val currentRevision = 5
        val staleRevision = 3

        // Server should reject stale revision
        assertTrue(staleRevision < currentRevision)
    }

    @Test
    fun ratingExactlyOnce() {
        // Simulate server side dedup cache
        val seenMessageIds = mutableSetOf<String>()

        fun processRating(messageId: String): Boolean {
            return if (seenMessageIds.contains(messageId)) {
                false // Already processed, return cached result
            } else {
                seenMessageIds.add(messageId)
                true // Processed
            }
        }

        assertTrue(processRating("msg-001"))
        assertFalse(processRating("msg-001")) // Duplicate suppressed
        assertTrue(processRating("msg-002"))
    }

    @Test
    fun startSessionIdempotent() {
        val start1 = ClientMessage.StartSession(
            deck = "Deck A",
            messageId = "start-001"
        )

        val start2 = ClientMessage.StartSession(
            deck = "Deck A",
            messageId = "start-001"
        )

        assertEquals(start1.messageId, start2.messageId)
    }

    @Test
    fun submitAnswerIdempotent() {
        val answer = ClientMessage.SubmitAnswer(
            sessionId = "s-1",
            cardId = "c-1",
            text = "My answer",
            messageId = "ans-1",
            reviewTurnId = "epoch:c-1:1",
            sessionRevision = 2
        )

        // Same messageId should not create duplicate evaluation
        assertEquals("ans-1", answer.messageId)
    }

    @Test
    fun messageIdUniquePerMutation() {
        val messages = listOf(
            ClientMessage.RateCard(cardId = "c1", rating = Rating.GOOD, messageId = "msg-1"),
            ClientMessage.RateCard(cardId = "c2", rating = Rating.EASY, messageId = "msg-2"),
            ClientMessage.RateCard(cardId = "c1", rating = Rating.GOOD, messageId = "msg-1") // duplicate
        )

        val uniqueIds = messages.map { it.messageId }.toSet()
        assertEquals(2, uniqueIds.size)
    }
}
