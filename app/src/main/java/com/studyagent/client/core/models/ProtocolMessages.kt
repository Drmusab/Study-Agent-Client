package com.studyagent.client.core.models

import com.studyagent.client.core.network.ClientInfoProvider
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

fun currentIsoTimestamp(): String {
    val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    df.timeZone = TimeZone.getTimeZone("UTC")
    return df.format(Date())
}

/**
 * Common envelope fields - every modern message should have:
 * type, protocol_version, message_id, timestamp, session_id, in_reply_to, session_revision
 * Required/optional/conditional documented in PROTOCOL.md
 */
interface MessageEnvelope {
    val protocolVersion: String?
    val messageId: String?
    val sessionId: String?
    val timestamp: String?
    val inReplyTo: String?
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ClientMessage {
    val protocolVersion: String
    val messageId: String
    val sessionId: String?
    val timestamp: String

    val type: String
        get() = when (this) {
            is Hello -> "hello"
            is Authenticate -> "authenticate"
            is StartSession -> "start_session"
            is PauseSession -> "pause_session"
            is ResumeSession -> "resume_session"
            is EndSession -> "end_session"
            is SubmitAnswer -> "submit_answer"
            is RateCard -> "rate_card"
            is RepeatQuestion -> "repeat_question"
            is RequestHint -> "request_hint"
            is RequestExplanation -> "request_explanation"
            is RequestAnswer -> "request_answer"
            is SkipCard -> "skip_card"
            is RequestSessionStatus -> "request_session_status"
            is RequestSessionSnapshot -> "request_session_snapshot"
            is RequestDashboard -> "request_dashboard"
            is RequestDecks -> "request_decks"
            is RequestComponentHealth -> "request_component_health"
            is RequestStudyConfig -> "request_study_config"
            is UpdateStudyConfig -> "update_study_config"
            is RequestHistory -> "request_history"
            is RequestLearningInsights -> "request_learning_insights"
            is RequestAiUsage -> "request_ai_usage"
            is Ping -> "ping"
        }

    @Serializable
    @SerialName("hello")
    data class Hello(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("client_name") val clientName: String = "StudyAgent-Android",
        @SerialName("client_version") val clientVersion: String = ClientInfoProvider.getClientVersion(),
        @SerialName("platform") val platform: String = "android",
        @SerialName("android_api") val androidApi: Int = ClientInfoProvider.getAndroidApi(),
        /** Protocol versions this client understands. v1 servers ignore this field. */
        @SerialName("supported_versions") val supportedVersions: List<String> = listOf("1", "2"),
        /** Optional client-side capabilities advertised to the agent. */
        @SerialName("client_capabilities") val clientCapabilities: List<String> = listOf(
            "dashboard", "study_control", "session_recovery",
            "review_commit_idempotency", "commit_reconciliation"
        )
    ) : ClientMessage

    @Serializable
    @SerialName("authenticate")
    data class Authenticate(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("token") val token: String
    ) : ClientMessage

    @Serializable
    @SerialName("start_session")
    data class StartSession(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("deck") val deck: String? = null,
        @SerialName("mode") val mode: String = "review_due",
        @SerialName("config") val config: SessionStartConfig? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("pause_session")
    data class PauseSession(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("resume_session")
    data class ResumeSession(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("end_session")
    data class EndSession(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("submit_answer")
    data class SubmitAnswer(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String,
        @SerialName("text") val text: String,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("rate_card")
    data class RateCard(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String,
        @SerialName("rating") val rating: Rating,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null,
        /**
         * Logical rating transaction id. Omitted when null so older agents keep the previous frame.
         * Not an auth token and not derived from the rating. Ignored by servers that do not
         * advertise review_commit_idempotency.
         */
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @SerialName("review_commit_id") val reviewCommitId: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("repeat_question")
    data class RepeatQuestion(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_hint")
    data class RequestHint(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_explanation")
    data class RequestExplanation(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_answer")
    data class RequestAnswer(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("skip_card")
    data class SkipCard(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_session_status")
    data class RequestSessionStatus(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_session_snapshot")
    data class RequestSessionSnapshot(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_dashboard")
    data class RequestDashboard(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_decks")
    data class RequestDecks(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_component_health")
    data class RequestComponentHealth(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_study_config")
    data class RequestStudyConfig(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("update_study_config")
    data class UpdateStudyConfig(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("config") val config: StudyControlConfig,
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_history")
    data class RequestHistory(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("range") val range: String = "7d",
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_learning_insights")
    data class RequestLearningInsights(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_ai_usage")
    data class RequestAiUsage(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("range") val range: String = "month",
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("ping")
    data class Ping(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("sent_at") val sentAt: String = currentIsoTimestamp(),
        @SerialName("in_reply_to") val inReplyTo: String? = null
    ) : ClientMessage
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ServerMessage : MessageEnvelope {
    override val protocolVersion: String?
    override val messageId: String?
    override val sessionId: String?
    override val timestamp: String?
    override val inReplyTo: String?

    val type: String
        get() = when (this) {
            is SessionStarted -> "session_started"
            is Question -> "question"
            is EvaluationResponse -> "evaluation"
            is Hint -> "hint"
            is Explanation -> "explanation"
            is Answer -> "answer"
            is RatingSaved -> "rating_saved"
            is SessionPaused -> "session_paused"
            is SessionResumed -> "session_resumed"
            is SessionFinished -> "session_finished"
            is SessionStats -> "session_stats"
            is SessionSnapshot -> "session_snapshot"
            is SessionStatus -> "session_status"
            is SessionProgress -> "session_progress"
            is Capabilities -> "capabilities"
            is DashboardSnapshotResponse -> "dashboard_snapshot"
            is DeckListResponse -> "deck_list"
            is ComponentHealthResponse -> "component_health"
            is StudyConfigResponse -> "study_config"
            is StudyConfigUpdated -> "study_config_updated"
            is StudyHistoryResponse -> "study_history"
            is LearningInsightResponse -> "learning_insight"
            is AiUsageResponse -> "ai_usage_stats"
            is ErrorMessage -> "error"
            is Pong -> "pong"
            is Welcome -> "welcome"
            is Unknown -> "unknown"
        }

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        @SerialName("protocol_version") override val protocolVersion: String = "2",
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("server_name") val serverName: String = "StudyPC-Agent",
        @SerialName("server_version") val serverVersion: String = "2.3.0",
        @SerialName("selected_protocol") val selectedProtocol: String = "2",
        @SerialName("capabilities") val capabilities: List<String> = emptyList(),
        @SerialName("authentication") val authentication: AuthInfo? = null,
        @SerialName("agent_id") val agentId: String? = null
    ) : ServerMessage

    @Serializable
    data class AuthInfo(
        @SerialName("required") val required: Boolean = false,
        @SerialName("authenticated") val authenticated: Boolean = false,
        @SerialName("methods") val methods: List<String> = listOf("bearer")
    )

    @Serializable
    @SerialName("session_started")
    data class SessionStarted(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("deck") val deck: String? = null,
        @SerialName("total_cards") val totalCards: Int? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null,
        @SerialName("supported_versions") val supportedVersions: List<String>? = null
    ) : ServerMessage

    @Serializable
    @SerialName("question")
    data class Question(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("card_id") val cardId: String,
        @SerialName("question") val question: String,
        @SerialName("card_number") val cardNumber: Int? = null,
        @SerialName("remaining") val remaining: Int? = null,
        @SerialName("speak") val speak: Boolean = true,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null,
        @SerialName("sequence") val sequence: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("evaluation")
    data class EvaluationResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("card_id") val cardId: String,
        @SerialName("score") val score: Int? = null,
        @SerialName("correct_points") val correctPoints: List<String> = emptyList(),
        @SerialName("missing_points") val missingPoints: List<String> = emptyList(),
        @SerialName("incorrect_points") val incorrectPoints: List<String> = emptyList(),
        @SerialName("short_feedback") val shortFeedback: String = "",
        @SerialName("suggested_rating") val suggestedRating: Rating? = null,
        @SerialName("confidence") val confidence: Double? = null,
        @SerialName("speak") val speak: Boolean = true,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("hint")
    data class Hint(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("hint") val hintText: String,
        @SerialName("speak") val speak: Boolean = true,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("explanation")
    data class Explanation(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("explanation") val explanationText: String,
        @SerialName("speak") val speak: Boolean = true,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("answer")
    data class Answer(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("answer") val answerText: String,
        @SerialName("speak") val speak: Boolean = true,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("rating_saved")
    data class RatingSaved(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("card_id") val cardId: String,
        @SerialName("rating") val rating: Rating,
        @SerialName("next_interval") val nextInterval: String? = null,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_paused")
    data class SessionPaused(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_resumed")
    data class SessionResumed(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_finished")
    data class SessionFinished(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("total_reviewed") val totalReviewed: Int = 0,
        @SerialName("summary") val summary: String? = null,
        @SerialName("details") val details: SessionSummaryPayload? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_stats")
    data class SessionStats(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("cards_studied") val cardsStudied: Int = 0,
        @SerialName("recall_rate") val recallRate: Double? = null,
        @SerialName("remaining_due") val remainingDue: Int = 0,
        @SerialName("session_revision") val sessionRevision: Long? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_snapshot")
    data class SessionSnapshot(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("exists") val exists: Boolean = true,
        @SerialName("is_paused") val isPaused: Boolean = false,
        @SerialName("is_finished") val isFinished: Boolean = false,
        @SerialName("current_card_id") val currentCardId: String? = null,
        @SerialName("current_question") val currentQuestion: String? = null,
        @SerialName("card_number") val cardNumber: Int? = null,
        @SerialName("remaining") val remaining: Int? = null,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null,
        @SerialName("awaiting") val awaiting: String? = null,
        @SerialName("suggested_rating") val suggestedRating: Rating? = null,
        @SerialName("remaining_due") val remainingDue: Int? = null,
        @SerialName("cards_studied") val cardsStudied: Int? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_status")
    data class SessionStatus(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("exists") val exists: Boolean = true,
        @SerialName("is_paused") val isPaused: Boolean = false,
        @SerialName("is_finished") val isFinished: Boolean = false,
        @SerialName("current_card_id") val currentCardId: String? = null,
        @SerialName("current_question") val currentQuestion: String? = null,
        @SerialName("card_number") val cardNumber: Int? = null,
        @SerialName("remaining") val remaining: Int? = null,
        @SerialName("review_turn_id") val reviewTurnId: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null,
        @SerialName("awaiting") val awaiting: String? = null,
        @SerialName("suggested_rating") val suggestedRating: Rating? = null
    ) : ServerMessage

    @Serializable
    @SerialName("error")
    data class ErrorMessage(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("code") val code: String? = null,
        @SerialName("category") val category: String? = null,
        @SerialName("message") val message: String,
        @SerialName("details") val details: String? = null,
        @SerialName("session_revision") val sessionRevision: Long? = null,
        @SerialName("retryable") val retryable: Boolean? = null
    ) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("sent_at") val sentAt: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_progress")
    data class SessionProgress(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("current_card_index") val currentCardIndex: Int? = null,
        @SerialName("total_cards") val totalCards: Int? = null
    ) : ServerMessage

    @Serializable
    @SerialName("capabilities")
    data class Capabilities(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("capabilities") val capabilities: List<String> = emptyList(),
        @SerialName("server_name") val serverName: String? = null,
        @SerialName("server_version") val serverVersion: String? = null,
        @SerialName("agent_id") val agentId: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("dashboard_snapshot")
    data class DashboardSnapshotResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("snapshot") val snapshot: DashboardSnapshotPayload? = null
    ) : ServerMessage

    @Serializable
    @SerialName("deck_list")
    data class DeckListResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("decks") val decks: List<DeckSummary> = emptyList()
    ) : ServerMessage

    @Serializable
    @SerialName("component_health")
    data class ComponentHealthResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("components") val components: List<ComponentHealthEntry> = emptyList()
    ) : ServerMessage

    @Serializable
    @SerialName("study_config")
    data class StudyConfigResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("config") val config: StudyControlConfig? = null
    ) : ServerMessage

    @Serializable
    @SerialName("study_config_updated")
    data class StudyConfigUpdated(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("config") val config: StudyControlConfig? = null
    ) : ServerMessage

    @Serializable
    @SerialName("study_history")
    data class StudyHistoryResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("history") val history: StudyHistoryPayload? = null
    ) : ServerMessage

    @Serializable
    @SerialName("learning_insight")
    data class LearningInsightResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("insights") val insights: List<LearningInsight> = emptyList()
    ) : ServerMessage

    @Serializable
    @SerialName("ai_usage_stats")
    data class AiUsageResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("usage") val usage: AiUsageSummary? = null
    ) : ServerMessage

    @Serializable
    @SerialName("unknown")
    data class Unknown(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("in_reply_to") override val inReplyTo: String? = null,
        @SerialName("raw_type") val rawType: String? = null
    ) : ServerMessage
}

/** Convenience: envelope fields shared */
val ServerMessage.inReplyToCompat: String?
    get() = inReplyTo
