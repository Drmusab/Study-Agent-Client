package com.studyagent.client.core.models

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
            is Ping -> "ping"
        }

    @Serializable
    @SerialName("hello")
    data class Hello(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("client_name") val clientName: String = "StudyAgent-Android",
        @SerialName("client_version") val clientVersion: String = "1.0.0"
    ) : ClientMessage

    @Serializable
    @SerialName("authenticate")
    data class Authenticate(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("token") val token: String
    ) : ClientMessage

    @Serializable
    @SerialName("start_session")
    data class StartSession(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("deck") val deck: String? = null,
        @SerialName("mode") val mode: String = "review_due"
    ) : ClientMessage

    @Serializable
    @SerialName("pause_session")
    data class PauseSession(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp()
    ) : ClientMessage

    @Serializable
    @SerialName("resume_session")
    data class ResumeSession(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp()
    ) : ClientMessage

    @Serializable
    @SerialName("end_session")
    data class EndSession(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp()
    ) : ClientMessage

    @Serializable
    @SerialName("submit_answer")
    data class SubmitAnswer(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String,
        @SerialName("text") val text: String
    ) : ClientMessage

    @Serializable
    @SerialName("rate_card")
    data class RateCard(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String,
        @SerialName("rating") val rating: Rating
    ) : ClientMessage

    @Serializable
    @SerialName("repeat_question")
    data class RepeatQuestion(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_hint")
    data class RequestHint(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_explanation")
    data class RequestExplanation(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_answer")
    data class RequestAnswer(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("skip_card")
    data class SkipCard(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp(),
        @SerialName("card_id") val cardId: String? = null
    ) : ClientMessage

    @Serializable
    @SerialName("request_session_status")
    data class RequestSessionStatus(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp()
    ) : ClientMessage

    @Serializable
    @SerialName("ping")
    data class Ping(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String = UUID.randomUUID().toString(),
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String = currentIsoTimestamp()
    ) : ClientMessage
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ServerMessage {
    val protocolVersion: String?
    val messageId: String?
    val sessionId: String?
    val timestamp: String?

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
            is ErrorMessage -> "error"
            is Pong -> "pong"
        }

    @Serializable
    @SerialName("session_started")
    data class SessionStarted(
        @SerialName("protocol_version") override val protocolVersion: String = "1",
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("deck") val deck: String? = null,
        @SerialName("total_cards") val totalCards: Int? = null
    ) : ServerMessage

    @Serializable
    @SerialName("question")
    data class Question(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("card_id") val cardId: String,
        @SerialName("question") val question: String,
        @SerialName("card_number") val cardNumber: Int? = null,
        @SerialName("remaining") val remaining: Int? = null,
        @SerialName("speak") val speak: Boolean = true
    ) : ServerMessage

    @Serializable
    @SerialName("evaluation")
    data class EvaluationResponse(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("card_id") val cardId: String,
        @SerialName("score") val score: Int? = null,
        @SerialName("correct_points") val correctPoints: List<String> = emptyList(),
        @SerialName("missing_points") val missingPoints: List<String> = emptyList(),
        @SerialName("incorrect_points") val incorrectPoints: List<String> = emptyList(),
        @SerialName("short_feedback") val shortFeedback: String = "",
        @SerialName("suggested_rating") val suggestedRating: Rating? = null,
        @SerialName("speak") val speak: Boolean = true
    ) : ServerMessage

    @Serializable
    @SerialName("hint")
    data class Hint(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("hint") val hintText: String,
        @SerialName("speak") val speak: Boolean = true
    ) : ServerMessage

    @Serializable
    @SerialName("explanation")
    data class Explanation(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("explanation") val explanationText: String,
        @SerialName("speak") val speak: Boolean = true
    ) : ServerMessage

    @Serializable
    @SerialName("answer")
    data class Answer(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("card_id") val cardId: String? = null,
        @SerialName("answer") val answerText: String,
        @SerialName("speak") val speak: Boolean = true
    ) : ServerMessage

    @Serializable
    @SerialName("rating_saved")
    data class RatingSaved(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("card_id") val cardId: String,
        @SerialName("rating") val rating: Rating,
        @SerialName("next_interval") val nextInterval: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_paused")
    data class SessionPaused(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_resumed")
    data class SessionResumed(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_finished")
    data class SessionFinished(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("total_reviewed") val totalReviewed: Int = 0,
        @SerialName("summary") val summary: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("session_stats")
    data class SessionStats(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("cards_studied") val cardsStudied: Int = 0,
        @SerialName("recall_rate") val recallRate: Double? = null,
        @SerialName("remaining_due") val remainingDue: Int = 0
    ) : ServerMessage

    @Serializable
    @SerialName("error")
    data class ErrorMessage(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null,
        @SerialName("code") val code: String? = null,
        @SerialName("message") val message: String,
        @SerialName("details") val details: String? = null
    ) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(
        @SerialName("protocol_version") override val protocolVersion: String? = null,
        @SerialName("message_id") override val messageId: String? = null,
        @SerialName("session_id") override val sessionId: String? = null,
        @SerialName("timestamp") override val timestamp: String? = null
    ) : ServerMessage
}
