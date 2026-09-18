package com.studyagent.client.core.network

import com.studyagent.client.core.models.*
import kotlinx.coroutines.flow.Flow

/**
 * High-level typed API over transport.
 * Replaces low-level Boolean send() as primary API for higher layers.
 */
interface AgentApi {
    // Connection lifecycle
    val connectionSnapshot: Flow<AgentConnectionSnapshot>
    val events: Flow<AgentEvent>

    // Session management
    suspend fun startSession(deck: String?, mode: String, config: SessionStartConfig?): AgentApiResult<ServerMessage.SessionStarted>
    suspend fun pauseSession(): AgentApiResult<ServerMessage.SessionPaused>
    suspend fun resumeSession(): AgentApiResult<ServerMessage.SessionResumed>
    suspend fun endSession(): AgentApiResult<ServerMessage.SessionFinished>
    suspend fun requestSessionSnapshot(): AgentApiResult<ServerMessage.SessionSnapshot>

    // Study actions (mutating, idempotent)
    suspend fun submitAnswer(cardId: String, text: String, reviewTurnId: String?, sessionRevision: Long?): AgentApiResult<ServerMessage.EvaluationResponse>
    suspend fun rateCard(cardId: String, rating: Rating, reviewTurnId: String?, sessionRevision: Long?): AgentApiResult<ServerMessage.RatingSaved>

    // Study helpers
    suspend fun repeatQuestion(cardId: String?, reviewTurnId: String?): AgentApiResult<ServerMessage.Question>
    suspend fun requestHint(cardId: String?, reviewTurnId: String?): AgentApiResult<ServerMessage.Hint>
    suspend fun requestExplanation(cardId: String?, reviewTurnId: String?): AgentApiResult<ServerMessage.Explanation>
    suspend fun requestAnswer(cardId: String?, reviewTurnId: String?): AgentApiResult<ServerMessage.Answer>
    suspend fun skipCard(cardId: String?, reviewTurnId: String?): AgentApiResult<ServerMessage>

    // Dashboard/Control (capability-gated)
    suspend fun getDashboard(): AgentApiResult<ServerMessage.DashboardSnapshotResponse>
    suspend fun getDecks(): AgentApiResult<ServerMessage.DeckListResponse>
    suspend fun getComponentHealth(): AgentApiResult<ServerMessage.ComponentHealthResponse>
    suspend fun getStudyConfig(): AgentApiResult<ServerMessage.StudyConfigResponse>
    suspend fun updateStudyConfig(config: StudyControlConfig): AgentApiResult<ServerMessage.StudyConfigUpdated>
    suspend fun getHistory(range: String): AgentApiResult<ServerMessage.StudyHistoryResponse>
    suspend fun getLearningInsights(): AgentApiResult<ServerMessage.LearningInsightResponse>
    suspend fun getAiUsage(range: String): AgentApiResult<ServerMessage.AiUsageResponse>
}

/** Server push events - separate from request/response */
sealed interface AgentEvent {
    data class Question(val payload: ServerMessage.Question) : AgentEvent
    data class Evaluation(val payload: ServerMessage.EvaluationResponse) : AgentEvent
    data class RatingSaved(val payload: ServerMessage.RatingSaved) : AgentEvent
    data class SessionProgress(val payload: ServerMessage.SessionProgress) : AgentEvent
    data class SessionFinished(val payload: ServerMessage.SessionFinished) : AgentEvent
    data class SessionSnapshot(val payload: ServerMessage.SessionSnapshot) : AgentEvent
    data class ComponentHealth(val payload: ServerMessage.ComponentHealthResponse) : AgentEvent
    data class ConfigUpdated(val payload: ServerMessage.StudyConfigUpdated) : AgentEvent
    data class Dashboard(val payload: ServerMessage.DashboardSnapshotResponse) : AgentEvent
    data class Error(val payload: ServerMessage.ErrorMessage) : AgentEvent
    data class Unknown(val rawType: String) : AgentEvent
}
