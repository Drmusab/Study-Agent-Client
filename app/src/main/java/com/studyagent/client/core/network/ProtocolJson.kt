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
            // Try envelope-first parsing for forward compatibility
            try {
                val element = json.parseToJsonElement(rawJson).jsonObject
                val type = element["type"]?.jsonPrimitive?.content

                if (type != null) {
                    // Unknown type - return Unknown instead of failing
                    AppLogger.w("ProtocolJson", "Unknown server message type: $type (bytes=${rawJson.length})")
                    return ServerMessage.Unknown(
                        rawType = type,
                        messageId = element["message_id"]?.jsonPrimitive?.content,
                        sessionId = element["session_id"]?.jsonPrimitive?.content,
                        timestamp = element["timestamp"]?.jsonPrimitive?.content,
                        inReplyTo = element["in_reply_to"]?.jsonPrimitive?.content
                    )
                }

                // Try generic error parsing
                val msg = element["message"]?.jsonPrimitive?.content ?: "Unknown error"
                if (element["type"]?.jsonPrimitive?.content == "error") {
                    return ServerMessage.ErrorMessage(
                        message = msg,
                        code = element["code"]?.jsonPrimitive?.content,
                        details = element["details"]?.jsonPrimitive?.content,
                        messageId = element["message_id"]?.jsonPrimitive?.content,
                        inReplyTo = element["in_reply_to"]?.jsonPrimitive?.content
                    )
                }

                AppLogger.e(
                    "ProtocolJson",
                    "Failed to decode server message: ${e.message} (type=${peekType(rawJson)} bytes=${rawJson.length})",
                    e
                )
                null
            } catch (_: Exception) {
                AppLogger.e(
                    "ProtocolJson",
                    "Failed to decode server message: ${e.message} (type=${peekType(rawJson)} bytes=${rawJson.length})",
                    e
                )
                null
            }
        }
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
