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
    fun toWebSocketUrl(): String {
        val scheme = if (useTls) "wss" else "ws"
        val formattedPath = if (path.startsWith("/")) path else "/$path"
        return "$scheme://$host:$port$formattedPath"
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
