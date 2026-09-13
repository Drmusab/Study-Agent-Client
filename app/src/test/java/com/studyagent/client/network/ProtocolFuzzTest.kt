package com.studyagent.client.network

import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.DashboardSnapshotPayload
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.network.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire is hostile territory (§93/§94/§136).
 *
 * A real client talks to a real PC agent and, sooner or later, to a *different version* of it: a
 * frame with a new field, a capability this build has never heard of, an enum value added
 * yesterday, a field the other side sent as `null` because its serializer does that, a payload
 * that is simply not valid JSON because a proxy mangled it.
 *
 * The client's contract in all of those cases is the same, and it is what this suite pins down:
 *
 * - **Unknown fields and unknown capability strings are ignored and preserved**, never fatal;
 * - **missing optionals take their documented defaults**, never a crash;
 * - **malformed required data fails closed** — the frame is dropped with a diagnostic (no typed
 *   frame, no state change), because half-parsing a card is worse than ignoring it;
 * - **anything the client sends is byte-stable under round-trip**, so an idempotency guarantee
 *   cannot be defeated by a re-encode.
 *
 * No new dependency is needed for this: the fuzz cases are explicit, readable JSON strings; a
 * property-testing library would add a build dependency for a handful of shapes this client
 * actually has to survive (§8).
 */
class ProtocolFuzzTest {

    // ------------------------------------------------------------------ missing optionals

    @Test
    fun `a question without optionals decodes with defaults`() {
        val msg = ProtocolJson.decodeServerMessage("""{"type":"question","card_id":"c9","question":"Q?"}""")
        assertNotNull(msg)
        val question = msg as ServerMessage.Question
        assertEquals("c9", question.cardId)
        assertEquals("Q?", question.question)
        assertNull(question.cardNumber)
        assertNull(question.remaining)
        assertTrue("speak defaults to true so a v1 server still gets audio", question.speak)
        assertNull(question.reviewTurnId)
    }

    @Test
    fun `an evaluation without optionals decodes with empty lists`() {
        val msg = ProtocolJson.decodeServerMessage("""{"type":"evaluation","card_id":"c9"}""")
        assertNotNull(msg)
        val evaluation = msg as ServerMessage.EvaluationResponse
        assertEquals("c9", evaluation.cardId)
        assertEquals(0, evaluation.correctPoints.size)
        assertEquals(0, evaluation.missingPoints.size)
        assertEquals("", evaluation.shortFeedback)
        assertNull(evaluation.suggestedRating)
        // A v1 server that omits `speak` still gets audible feedback; the field is only a switch
        // for servers that know about it.
        assertTrue(evaluation.speak)
    }

    @Test
    fun `unknown fields and unknown capability strings are ignored and preserved`() {
        val json = """
            {
              "type": "capabilities",
              "protocol_version": "2",
              "capabilities": ["dashboard", "study_config", "telepathy", "quantum_sync"],
              "server_name": "PC Agent",
              "server_version": "2.4.0",
              "new_field_from_the_future": {"nested": [1, 2, 3]}
            }
        """.trimIndent()

        val msg = ProtocolJson.decodeServerMessage(json)
        assertNotNull("unknown fields must not break an otherwise valid frame", msg)
        val capabilities = msg as ServerMessage.Capabilities
        assertEquals(4, capabilities.capabilities.size)
        assertTrue(capabilities.capabilities.contains("telepathy"))

        // The negotiated model keeps the unknown strings (so a newer server is not misrepresented)
        // and the known ones answer `supports` correctly.
        val negotiated = AgentCapabilities(
            status = AgentCapabilities.NegotiationStatus.NEGOTIATED_V2,
            protocolVersion = capabilities.protocolVersion ?: "1",
            capabilities = capabilities.capabilities.toSet(),
            serverName = capabilities.serverName,
            serverVersion = capabilities.serverVersion
        )
        assertTrue(negotiated.supports(AgentCapability.DASHBOARD))
        assertTrue(negotiated.supports(AgentCapability.STUDY_CONFIG))
        assertTrue(negotiated.supports("telepathy"))
        assertTrue(negotiated.isResolved)
    }

    @Test
    fun `an unknown capability makes an old client legacy-safe, not broken`() {
        val json = """{"type":"capabilities","capabilities":["something_new"],"server_version":"9.0.0"}"""
        val msg = ProtocolJson.decodeServerMessage(json) as ServerMessage.Capabilities
        val negotiated = AgentCapabilities(
            status = AgentCapabilities.NegotiationStatus.NEGOTIATED_V2,
            capabilities = msg.capabilities.toSet()
        )
        assertTrue("study must still work when the analytics surface is unknown", negotiated.isResolved)
        assertTrue(!negotiated.supports(AgentCapability.DASHBOARD))
    }

    // ------------------------------------------------------------------ hostile values

    @Test
    fun `an unknown enum value fails closed instead of guessing`() {
        // "deferred" is not a Rating. Guessing (e.g. mapping anything unknown to GOOD) would
        // silently change scheduling, so the frame must be dropped.
        val json = """
            {"type":"evaluation","card_id":"c1","short_feedback":"ok","suggested_rating":"deferred"}
        """.trimIndent()
        assertNull(ProtocolJson.decodeServerMessage(json))
    }

    @Test
    fun `an unknown frame type is dropped, not misread`() {
        assertNull(ProtocolJson.decodeServerMessage("""{"type":"brainwave_sync","amplitude":3}"""))
    }

    @Test
    fun `a null in a non-nullable field fails closed`() {
        assertNull(ProtocolJson.decodeServerMessage("""{"type":"question","card_id":null,"question":"Q"}"""))
        assertNull(ProtocolJson.decodeServerMessage("""{"type":"question","card_id":"c1","question":null}"""))
    }

    @Test
    fun `missing required data fails closed`() {
        assertNull(ProtocolJson.decodeServerMessage("""{"type":"question","question":"no card id"}"""))
        assertNull(ProtocolJson.decodeServerMessage("""{"type":"evaluation"}"""))
        assertNull(ProtocolJson.decodeServerMessage("""{"type":"rating_saved","card_id":"c1"}"""))
    }

    @Test
    fun `not-json garbage fails closed`() {
        assertNull(ProtocolJson.decodeServerMessage(""))
        assertNull(ProtocolJson.decodeServerMessage("   "))
        assertNull(ProtocolJson.decodeServerMessage("<html>502 Bad Gateway</html>"))
        assertNull(ProtocolJson.decodeServerMessage("{"))
        assertNull(ProtocolJson.decodeServerMessage("\u0000\u0001\u0002"))
    }

    @Test
    fun `an error frame shaped like an error is still usable when the schema is partial`() {
        val json = """{"type":"error","code":"DECK_NOT_FOUND","message":"No such deck"}"""
        val msg = ProtocolJson.decodeServerMessage(json)
        assertNotNull(msg)
        assertTrue(msg is ServerMessage.ErrorMessage)
    }

    @Test
    fun `empty and very large lists are both accepted`() {
        val empty = ProtocolJson.decodeServerMessage(
            """{"type":"evaluation","card_id":"c1","correct_points":[],"missing_points":[]}"""
        ) as ServerMessage.EvaluationResponse
        assertEquals(0, empty.correctPoints.size)

        val many = (1..1_000).joinToString(",") { "\"point $it\"" }
        val json = """{"type":"evaluation","card_id":"c1","missing_points":[$many]}"""
        val decoded = ProtocolJson.decodeServerMessage(json) as ServerMessage.EvaluationResponse
        assertEquals("a large list is data, not an error", 1_000, decoded.missingPoints.size)

        val caps = (1..500).joinToString(",") { "\"cap_$it\"" }
        val capsDecoded = ProtocolJson.decodeServerMessage("""{"type":"capabilities","capabilities":[$caps]}""")
        assertEquals(500, (capsDecoded as ServerMessage.Capabilities).capabilities.size)
    }

    @Test
    fun `very long strings survive decoding and are the caller's problem to bound`() {
        val long = "x".repeat(50_000)
        val json = """{"type":"question","card_id":"c1","question":"$long"}"""
        val question = ProtocolJson.decodeServerMessage(json) as ServerMessage.Question
        assertEquals(50_000, question.question.length)
    }

    // ------------------------------------------------------------------ round trips

    @Test
    fun `every client message the study loop sends survives a round trip`() {
        val messages = listOf(
            ClientMessage.StartSession(sessionId = "s1", deck = "Toronto Notes", mode = "review_due"),
            ClientMessage.SubmitAnswer(
                sessionId = "s1",
                cardId = "card-1",
                text = "Volume over thirty millilitres",
                reviewTurnId = "1:card-1:1"
            ),
            ClientMessage.RateCard(sessionId = "s1", cardId = "card-1", rating = Rating.GOOD, reviewTurnId = "1:card-1:1"),
            ClientMessage.PauseSession(sessionId = "s1"),
            ClientMessage.ResumeSession(sessionId = "s1"),
            ClientMessage.EndSession(sessionId = "s1"),
            ClientMessage.SkipCard(sessionId = "s1", cardId = "card-1"),
            ClientMessage.RequestSessionStatus(sessionId = "s1"),
            ClientMessage.Ping()
        )
        for (message in messages) {
            val json = ProtocolJson.encodeClientMessage(message)
            val decoded = ProtocolJson.json.decodeFromString(ClientMessage.serializer(), json)
            assertEquals("round trip changed ${message::class.simpleName}", message, decoded)
        }
    }

    @Test
    fun `server frames survive a round trip including nested payloads`() {
        val messages: List<ServerMessage> = listOf(
            ServerMessage.SessionStarted(sessionId = "s1", deck = "Toronto Notes", totalCards = 500),
            ServerMessage.Question(
                sessionId = "s1",
                cardId = "card-1",
                question = "When do you evacuate?",
                cardNumber = 4,
                remaining = 96,
                reviewTurnId = "1:card-1:1"
            ),
            ServerMessage.EvaluationResponse(
                sessionId = "s1",
                cardId = "card-1",
                score = 82,
                shortFeedback = "You missed midline shift.",
                suggestedRating = Rating.HARD,
                confidence = 0.71
            ),
            ServerMessage.RatingSaved(sessionId = "s1", cardId = "card-1", rating = Rating.HARD, nextInterval = "2d"),
            ServerMessage.SessionPaused(sessionId = "s1"),
            ServerMessage.SessionResumed(sessionId = "s1"),
            ServerMessage.SessionFinished(sessionId = "s1", totalReviewed = 500, summary = "Done"),
            ServerMessage.SessionStatus(
                sessionId = "s1",
                currentCardId = "card-1",
                awaiting = "answer",
                cardNumber = 4,
                remaining = 96
            ),
            ServerMessage.Capabilities(
                protocolVersion = "2",
                capabilities = listOf("dashboard", "study_config", "unknown_future_capability"),
                serverName = "PC Agent",
                serverVersion = "2.4.0"
            ),
            ServerMessage.DashboardSnapshotResponse(
                protocolVersion = "2",
                snapshot = DashboardSnapshotPayload(
                    generatedAt = "2026-01-01T10:00:00Z",
                    activeDeck = null
                )
            ),
            ServerMessage.StudyConfigResponse(
                protocolVersion = "2",
                config = StudyControlConfig(activeDeck = "Toronto Notes", sessionTargetValue = 200)
            ),
            ServerMessage.ErrorMessage(code = "DECK_NOT_FOUND", message = "No such deck")
        )

        for (message in messages) {
            val json = ProtocolJson.json.encodeToString(ServerMessage.serializer(), message)
            val decoded = ProtocolJson.decodeServerMessage(json)
            assertEquals("round trip changed ${message::class.simpleName}", message, decoded)
        }
    }

    @Test
    fun `a study config round trip keeps every field a user can change`() {
        val config = StudyControlConfig(
            activeDeck = "Toronto Notes",
            sessionTargetValue = 200,
            newPerDay = 40,
            reviewLimitPerDay = 150
        )
        val json = ProtocolJson.json.encodeToString(StudyControlConfig.serializer(), config)
        val decoded = ProtocolJson.json.decodeFromString(StudyControlConfig.serializer(), json)
        assertEquals(config, decoded)
    }

    @Test
    fun `encoding a message twice produces identical bytes`() {
        val message = ClientMessage.RateCard(sessionId = "s1", cardId = "card-1", rating = Rating.EASY, reviewTurnId = "1:card-1:7")
        assertEquals(ProtocolJson.encodeClientMessage(message), ProtocolJson.encodeClientMessage(message))
    }

    @Test
    fun `a frame with a duplicate message id decodes into two identical frames`() {
        // Idempotency is enforced by the *machine* (dedup window), not by the decoder: two frames
        // with the same id must survive decoding so the dedup logic is what rejects the second one.
        val json = """{"type":"question","message_id":"dup-1","session_id":"s1","card_id":"c1","question":"Q"}"""
        val first = ProtocolJson.decodeServerMessage(json) as ServerMessage.Question
        val second = ProtocolJson.decodeServerMessage(json) as ServerMessage.Question
        assertEquals(first, second)
        assertEquals("dup-1", second.messageId)
    }
}
