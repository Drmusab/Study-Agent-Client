package com.studyagent.client.core.network

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.SessionStartConfig
import com.studyagent.client.core.models.StudyControlConfig
import java.util.UUID

/**
 * Centralized message creation - avoids scattering protocol metadata.
 * Uses ProtocolContext for version, session, client info.
 */
class MessageFactory(
    private var context: ProtocolContext = ProtocolContext()
) {
    fun updateContext(newContext: ProtocolContext) {
        context = newContext
    }

    fun updateSession(sessionId: String?) {
        context = context.withSession(sessionId)
    }

    fun updateNegotiatedVersion(version: String) {
        context = context.withNegotiatedVersion(version)
    }

    fun hello(): ClientMessage.Hello {
        return ClientMessage.Hello(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId,
            clientName = context.clientName,
            clientVersion = context.clientVersion,
            supportedVersions = context.supportedVersions,
            clientCapabilities = listOf("dashboard", "study_control", "session_recovery")
        )
    }

    fun ping(): ClientMessage.Ping {
        return ClientMessage.Ping(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId
        )
    }

    fun authenticate(token: String): ClientMessage.Authenticate {
        return ClientMessage.Authenticate(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId,
            token = token
        )
    }

    fun startSession(deck: String?, mode: String, config: SessionStartConfig?): ClientMessage.StartSession {
        return createStartSession(deck, mode, config)
    }

    fun createStartSession(deck: String?, mode: String, config: SessionStartConfig?): ClientMessage.StartSession {
        return ClientMessage.StartSession(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId,
            deck = deck,
            mode = mode,
            config = config
        )
    }

    fun createStartSession(deck: String?, messageId: String): ClientMessage.StartSession {
        return ClientMessage.StartSession(
            protocolVersion = context.negotiatedVersion,
            messageId = messageId,
            sessionId = context.sessionId,
            deck = deck
        )
    }

    fun submitAnswer(cardId: String, text: String, reviewTurnId: String?, sessionRevision: Long?): ClientMessage.SubmitAnswer {
        return ClientMessage.SubmitAnswer(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId,
            cardId = cardId,
            text = text,
            reviewTurnId = reviewTurnId,
            sessionRevision = sessionRevision
        )
    }

    fun rateCard(cardId: String, rating: com.studyagent.client.core.models.Rating, reviewTurnId: String?, sessionRevision: Long?): ClientMessage.RateCard {
        return ClientMessage.RateCard(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId,
            cardId = cardId,
            rating = rating,
            reviewTurnId = reviewTurnId,
            sessionRevision = sessionRevision
        )
    }

    fun requestSessionSnapshot(): ClientMessage.RequestSessionSnapshot {
        return ClientMessage.RequestSessionSnapshot(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId
        )
    }

    fun requestDashboard(): ClientMessage.RequestDashboard {
        return ClientMessage.RequestDashboard(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId
        )
    }

    fun requestDecks(): ClientMessage.RequestDecks {
        return ClientMessage.RequestDecks(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId
        )
    }

    fun requestComponentHealth(): ClientMessage.RequestComponentHealth {
        return ClientMessage.RequestComponentHealth(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId
        )
    }

    fun requestStudyConfig(): ClientMessage.RequestStudyConfig {
        return ClientMessage.RequestStudyConfig(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId
        )
    }

    fun updateStudyConfig(config: StudyControlConfig): ClientMessage.UpdateStudyConfig {
        return ClientMessage.UpdateStudyConfig(
            protocolVersion = context.negotiatedVersion,
            messageId = UUID.randomUUID().toString(),
            sessionId = context.sessionId,
            config = config
        )
    }

    fun getContext(): ProtocolContext = context
}
