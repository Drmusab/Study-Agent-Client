package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ServerMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProtocolJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
        classDiscriminator = "type"
    }

    // Size limits - protect against huge frames but allow real content
    const val MAX_FRAME_SIZE = 2 * 1024 * 1024 // 2MB max frame
    const val MAX_QUESTION_SIZE = 10_000
    const val MAX_ANSWER_SIZE = 20_000
    const val MAX_FEEDBACK_SIZE = 20_000
    const val MAX_DASHBOARD_SIZE = 1 * 1024 * 1024

    fun encodeClientMessage(message: ClientMessage): String {
        val encoded = json.encodeToString(ClientMessage.serializer(), message)
        // Size check
        if (encoded.length > MAX_FRAME_SIZE) {
            AppLogger.w("ProtocolJson", "Client message exceeds size limit: type=${message.type} size=${encoded.length}")
            // Still send but log warning - server should handle
        }
        return encoded
    }

    fun decodeServerMessage(rawJson: String): ServerMessage? {
        // Size protection
        if (rawJson.length > MAX_FRAME_SIZE) {
            AppLogger.e("ProtocolJson", "Frame too large: ${rawJson.length} bytes, type=${peekType(rawJson)}")
            return ServerMessage.ErrorMessage(
                message = "Frame too large",
                code = "FRAME_TOO_LARGE"
            )
        }

        return try {
            json.decodeFromString(ServerMessage.serializer(), rawJson)
        } catch (e: Exception) {
            // Fail closed (ProtocolFuzz contract): a frame that does not strictly decode is
            // DROPPED (null) — never guessed, never dressed up. That covers unknown frame
            // types ("dropped, not misread"), unknown enum values, nulls in non-nullable
            // fields and missing required data alike; the connection counts the drop as a
            // protocol error. The only recovery path is a best-effort read of a *server
            // error* frame so operator-facing failures stay visible.
            try {
                val element = json.parseToJsonElement(rawJson).jsonObject
                if (element["type"]?.jsonPrimitive?.content == "error") {
                    return ServerMessage.ErrorMessage(
                        message = element["message"]?.jsonPrimitive?.content ?: "Unknown error",
                        code = element["code"]?.jsonPrimitive?.content,
                        details = element["details"]?.jsonPrimitive?.content,
                        messageId = element["message_id"]?.jsonPrimitive?.content,
                        inReplyTo = element["in_reply_to"]?.jsonPrimitive?.content
                    )
                }
            } catch (_: Exception) {
                // fall through to the drop below
            }
            AppLogger.e(
                "ProtocolJson",
                "Failed to decode server message: ${e.message} (type=${peekType(rawJson)} bytes=${rawJson.length})",
                e
            )
            null
        }
    }

    /**
     * Whether [reply] answers the request [outstandingRequestId].
     *
     * Protocol v2 servers send every reply with a *fresh* `message_id` and the
     * request id in `in_reply_to` — comparing reply `message_id` against the
     * request id never matches there. Legacy v1/echo servers send no
     * `in_reply_to`, so an absent or echoed id still completes the single
     * outstanding request. A reply correlated to a *different* request never
     * matches: stale answers must not complete (or fail) the current one.
     */
    fun isReplyTo(reply: ServerMessage, outstandingRequestId: String?): Boolean {
        if (outstandingRequestId == null) return false
        val inReplyTo = reply.inReplyTo
        if (inReplyTo != null) return inReplyTo == outstandingRequestId
        val messageId = reply.messageId
        return messageId == null || messageId == outstandingRequestId
    }

    fun isProtocolVersionCompatible(incomingVersion: String?): Boolean {
        if (incomingVersion == null) return true // Be lenient if omitted in V1
        return incomingVersion == "1" || incomingVersion == "2"
    }

    fun selectProtocolVersion(clientSupported: List<String>, serverSelected: String?): String? {
        if (serverSelected != null && clientSupported.contains(serverSelected)) {
            return serverSelected
        }
        // Server should choose highest mutually supported
        return null
    }

    fun peekType(rawJson: String): String = try {
        json.parseToJsonElement(rawJson).jsonObject["type"]?.jsonPrimitive?.content ?: "missing"
    } catch (_: Exception) {
        "unparseable"
    }

    fun peekEnvelope(rawJson: String): Map<String, String?>? = try {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        mapOf(
            "type" to obj["type"]?.jsonPrimitive?.content,
            "protocol_version" to obj["protocol_version"]?.jsonPrimitive?.content,
            "message_id" to obj["message_id"]?.jsonPrimitive?.content,
            "in_reply_to" to obj["in_reply_to"]?.jsonPrimitive?.content,
            "session_id" to obj["session_id"]?.jsonPrimitive?.content
        )
    } catch (_: Exception) {
        null
    }
}
