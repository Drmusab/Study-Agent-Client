package com.studyagent.client.core.models

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.regex.Pattern

@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 8765,
    val path: String = "/ws",
    val useTls: Boolean = false,
    val authToken: String? = null,
    val isDefault: Boolean = false,
    val agentId: String? = null,
    val lastConnectedAt: Long? = null,
    val lastLatencyMs: Long? = null,
    val lastProtocolVersion: String? = null
) {
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
            (safeHost.count { it == ':' } == 1 && !isValidHostname(safeHost))
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

    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (name.isBlank()) errors.add("Name cannot be empty")
        if (name.length > 100) errors.add("Name too long")

        val trimmedHost = host.trim()
        if (trimmedHost.isBlank()) {
            errors.add("Host cannot be empty")
        } else {
            if (trimmedHost.equals("127.0.0.1", ignoreCase = true) || trimmedHost.equals("localhost", ignoreCase = true)) {
                // Warn but allow - useful for emulator but not for real device
                // This is not an error, but we note it
            }

            if (!isValidHost(trimmedHost)) {
                errors.add("Invalid host format: $trimmedHost")
            }
        }

        if (port !in 1..65_535) errors.add("Port must be between 1 and 65535")

        val trimmedPath = path.trim()
        if (trimmedPath.isNotBlank()) {
            if (!trimmedPath.startsWith("/")) errors.add("Path must start with /")
            if (trimmedPath.contains("://")) errors.add("Path cannot contain ://")
            if (trimmedPath.any { it.isWhitespace() }) errors.add("Path cannot contain whitespace")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun isValidHost(host: String): Boolean {
        val clean = host.removePrefix("[").removeSuffix("]")
        return isValidIpv4(clean) || isValidIpv6(clean) || isValidHostname(clean) || isTailscaleIp(clean) || isMagicDns(clean)
    }

    private fun isValidIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isValidIpv6(host: String): Boolean {
        // Simplified IPv6 check - contains colons and hex chars
        return host.contains(":") && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }

    private fun isValidHostname(host: String): Boolean {
        if (host.length > 253) return false
        // Allow MagicDNS like my-pc.tail-scale.ts.net
        val hostnamePattern = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9\\-\\.]{0,61}[a-zA-Z0-9])?$")
        if (!hostnamePattern.matcher(host).matches()) return false
        if (host.contains("..")) return false
        return true
    }

    private fun isTailscaleIp(host: String): Boolean {
        // Tailscale CGNAT range 100.64.0.0 - 100.127.255.255
        if (!host.startsWith("100.")) return false
        val parts = host.split(".")
        if (parts.size != 4) return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 64..127
    }

    private fun isMagicDns(host: String): Boolean {
        // MagicDNS typically ends with .ts.net or contains tailnet name
        return host.contains(".ts.net") || host.contains(".tailscale")
    }

    val isEmulatorAddress: Boolean
        get() = host == "10.0.2.2"

    val isLocalNetwork: Boolean
        get() {
            val h = host
            return h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("172.") || h == "10.0.2.2"
        }

    val isTailscale: Boolean
        get() = isTailscaleIp(host) || isMagicDns(host)

    val isSecure: Boolean
        get() = useTls

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val errors: List<String>) : ValidationResult

        val isValid: Boolean
            get() = this is Valid
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

        fun fromQrData(qrContent: String): ServerProfile? {
            // Expected format: studyagent://host:port/path?tls=true&code=xxx or JSON
            return try {
                if (qrContent.startsWith("studyagent://")) {
                    val withoutScheme = qrContent.removePrefix("studyagent://")
                    val hostPortPath = withoutScheme.substringBefore("?")
                    val query = withoutScheme.substringAfter("?", "")

                    val hostPort = hostPortPath.substringBefore("/")
                    val path = "/" + hostPortPath.substringAfter("/", "ws")

                    val host = hostPort.substringBefore(":")
                    val port = hostPort.substringAfter(":", "8765").toIntOrNull() ?: 8765

                    val params = query.split("&").associate {
                        val kv = it.split("=")
                        if (kv.size == 2) kv[0] to kv[1] else kv[0] to ""
                    }

                    ServerProfile(
                        name = params["name"] ?: host,
                        host = host,
                        port = port,
                        path = path,
                        useTls = params["tls"] == "true" || params["wss"] == "true"
                    )
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
