package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ServerMessage
import kotlinx.serialization.json.Json
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

    fun encodeClientMessage(message: ClientMessage): String {
        return json.encodeToString(ClientMessage.serializer(), message)
    }

    fun decodeServerMessage(rawJson: String): ServerMessage? {
        return try {
            json.decodeFromString(ServerMessage.serializer(), rawJson)
        } catch (e: Exception) {
            // §83: never log the raw frame. A protocol frame can legitimately contain study
            // content, and a malformed one can contain a credential. Log the shape instead:
            // frame type (best effort), size, and the decoder's own message.
            AppLogger.e(
                "ProtocolJson",
                "Failed to decode server message: ${e.message} " +
                    "(type=${peekType(rawJson)} bytes=${rawJson.length})",
                e
            )
            // Attempt to parse generic error if possible
            try {
                val element = json.parseToJsonElement(rawJson).jsonObject
                val type = element["type"]?.jsonPrimitive?.content
                val msg = element["message"]?.jsonPrimitive?.content ?: "Unknown error"
                if (type == "error") {
                    ServerMessage.ErrorMessage(
                        message = msg,
                        code = element["code"]?.jsonPrimitive?.content,
                        details = element["details"]?.jsonPrimitive?.content
                    )
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun isProtocolVersionCompatible(incomingVersion: String?): Boolean {
        if (incomingVersion == null) return true // Be lenient if omitted in V1
        return incomingVersion == "1" || incomingVersion == "2"
    }

    /**
     * Best-effort `type` peek without a full deserialization (§83/§61).
     *
     * Used only for diagnostics on the failure path: it answers "what kind of frame was it?"
     * without retaining or rendering the frame itself. Returns "unknown" when the payload is not
     * even a JSON object, which is itself a useful fact.
     */
    fun peekType(rawJson: String): String = try {
        json.parseToJsonElement(rawJson).jsonObject["type"]?.jsonPrimitive?.content ?: "missing"
    } catch (_: Exception) {
        "unparseable"
    }
}
