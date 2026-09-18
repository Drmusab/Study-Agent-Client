package com.studyagent.client.core.models

import java.util.regex.Pattern

/**
 * Separate profile validator - extracted from ServerProfile for testability
 * and single responsibility.
 *
 * Validates:
 * - hostname / IPv4 / IPv6 / Tailscale CGNAT / MagicDNS
 * - port range
 * - path format
 * - name constraints
 * - emulator/local/tailscale detection
 */
object ProfileValidator {

    private val hostnamePattern = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9\\-\\.]{0,61}[a-zA-Z0-9])?$")

    fun validate(profile: ServerProfile): ServerProfile.ValidationResult {
        val errors = mutableListOf<String>()

        if (profile.name.isBlank()) errors.add("Name cannot be empty")
        if (profile.name.length > 100) errors.add("Name too long (max 100)")

        val trimmedHost = profile.host.trim()
        if (trimmedHost.isBlank()) {
            errors.add("Host cannot be empty")
        } else {
            if (!isValidHost(trimmedHost)) {
                errors.add("Invalid host format: $trimmedHost")
            }
            if (trimmedHost.any { it.isWhitespace() }) {
                errors.add("Host cannot contain whitespace")
            }
            if (trimmedHost.contains("://")) {
                errors.add("Host cannot contain ://")
            }
        }

        if (profile.port !in 1..65_535) errors.add("Port must be between 1 and 65535")

        val trimmedPath = profile.path.trim()
        if (trimmedPath.isNotBlank()) {
            if (!trimmedPath.startsWith("/")) errors.add("Path must start with /")
            if (trimmedPath.contains("://")) errors.add("Path cannot contain ://")
            if (trimmedPath.any { it.isWhitespace() }) errors.add("Path cannot contain whitespace")
        }

        if (profile.authToken != null && profile.authToken.isBlank()) {
            errors.add("Auth token cannot be blank if provided")
        }

        return if (errors.isEmpty()) ServerProfile.ValidationResult.Valid else ServerProfile.ValidationResult.Invalid(errors)
    }

    fun isValidHost(host: String): Boolean {
        val clean = host.removePrefix("[").removeSuffix("]")
        return isValidIpv4(clean) || isValidIpv6(clean) || isValidHostname(clean) || isTailscaleIp(clean) || isMagicDns(clean)
    }

    fun isValidIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    fun isValidIpv6(host: String): Boolean {
        return host.contains(":") && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }

    fun isValidHostname(host: String): Boolean {
        if (host.length > 253) return false
        if (!hostnamePattern.matcher(host).matches()) return false
        if (host.contains("..")) return false
        return true
    }

    fun isTailscaleIp(host: String): Boolean {
        if (!host.startsWith("100.")) return false
        val parts = host.split(".")
        if (parts.size != 4) return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 64..127
    }

    fun isMagicDns(host: String): Boolean {
        return host.contains(".ts.net") || host.contains(".tailscale")
    }

    fun isEmulatorAddress(host: String): Boolean = host == "10.0.2.2"

    fun isLocalNetwork(host: String): Boolean {
        return host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.") || host == "10.0.2.2"
    }

    fun isTailscale(host: String): Boolean = isTailscaleIp(host) || isMagicDns(host)

    fun normalize(profile: ServerProfile): ServerProfile? {
        return profile.normalized()
    }

    /**
     * Warn if host is 127.0.0.1 or localhost - won't work on real device, only emulator
     */
    fun isLoopbackWarning(host: String): Boolean {
        return host.equals("127.0.0.1", ignoreCase = true) || host.equals("localhost", ignoreCase = true)
    }
}
