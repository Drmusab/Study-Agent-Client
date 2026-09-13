package com.studyagent.client.core.models

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 8765,
    val path: String = "/ws",
    val useTls: Boolean = false,
    val authToken: String? = null,
    val isDefault: Boolean = false
) {
    /**
     * Returns a URL without throwing for malformed persisted fields. Repository
     * validation normally removes unusable profiles; this final boundary keeps a
     * corrupt profile from crashing a connection attempt.
     */
    fun toWebSocketUrl(): String {
        val scheme = if (useTls) "wss" else "ws"
        val safeHost = host.trim().ifBlank { "127.0.0.1" }
        val formattedHost = if (safeHost.contains(":") && !safeHost.startsWith("[")) {
            "[$safeHost]"
        } else {
            safeHost
        }
        val safePort = port.coerceIn(1, 65_535)
        val safePath = path.trim().ifBlank { "/ws" }
        val formattedPath = if (safePath.startsWith("/")) safePath else "/$safePath"
        return "$scheme://$formattedHost:$safePort$formattedPath"
    }

    /** Safe runtime representation; null means this entry cannot be connected to. */
    fun normalized(): ServerProfile? {
        val safeId = id.trim().takeIf {
            it.isNotEmpty() && it.length <= 128 && it.none(Char::isWhitespace)
        } ?: return null
        val rawHost = host.trim()
        if (rawHost.startsWith("[") != rawHost.endsWith("]")) return null
        val safeHost = rawHost.removePrefix("[").removeSuffix("]")
            .takeIf { it.isNotEmpty() } ?: return null
        if (
            port !in 1..65_535 ||
            safeHost.any(Char::isWhitespace) ||
            safeHost.any { it == '/' || it == '\\' || it == '@' || it == '?' || it == '#' } ||
            (safeHost.count { it == ':' } == 1)
        ) return null
        val safePath = path.trim().ifBlank { "/ws" }
        if (safePath.contains("://") || safePath.any(Char::isWhitespace)) return null
        return copy(
            id = safeId,
            name = name.trim().ifBlank { safeHost },
            host = safeHost,
            path = if (safePath.startsWith("/")) safePath else "/$safePath",
            authToken = authToken?.takeIf { it.isNotBlank() }
        )
    }

    companion object {
        fun defaultLocalProfile(): ServerProfile = ServerProfile(
            id = "default_local",
            name = "Home PC (LAN)",
            host = "192.168.1.100",
            port = 8765,
            path = "/ws",
            useTls = false,
            isDefault = true
        )

        fun defaultEmulatorProfile(): ServerProfile = ServerProfile(
            id = "default_emulator",
            name = "Android Emulator Host",
            host = "10.0.2.2",
            port = 8765,
            path = "/ws",
            useTls = false,
            isDefault = false
        )
    }
}
