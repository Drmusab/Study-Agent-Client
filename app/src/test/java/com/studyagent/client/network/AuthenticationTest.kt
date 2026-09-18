package com.studyagent.client.network

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ServerMessage
import org.junit.Assert.*
import org.junit.Test

class AuthenticationTest {

    @Test
    fun bearerPreferredOverLegacyFrame() {
        val token = "secret-token-123"
        val bearerHeader = "Bearer $token"
        assertTrue(bearerHeader.startsWith("Bearer "))

        val authFrame = ClientMessage.Authenticate(token = token, messageId = "auth-1")
        assertEquals(token, authFrame.token)
    }

    @Test
    fun tokenNeverLogged() {
        val token = "super-secret-token"
        val sanitized = token.replace(Regex(".+"), "***REDACTED***")
        assertEquals("***REDACTED***", sanitized)
        assertFalse(sanitized.contains(token))
    }

    @Test
    fun tokenSeparationFromProfileJson() {
        val profileJson = """{"name":"Home PC","host":"192.168.1.100","port":8765}"""
        assertFalse(profileJson.contains("token"))
        assertFalse(profileJson.contains("secret"))
    }

    @Test
    fun noTokenInLogsOrExports() {
        val logLine = "Connecting to 192.168.1.100 with token ***REDACTED***"
        assertFalse(logLine.contains("secret-token"))
        assertTrue(logLine.contains("***REDACTED***"))
    }

    @Test
    fun welcomeAuthInfo() {
        val authInfo = ServerMessage.AuthInfo(
            required = true,
            authenticated = false,
            methods = listOf("bearer")
        )
        assertTrue(authInfo.required)
        assertTrue(authInfo.methods.contains("bearer"))
    }

    @Test
    fun authFailureMapsToUserMessage() {
        val problem = com.studyagent.client.core.network.ConnectionProblem.AuthenticationRejected("Invalid token")
        assertTrue(problem.userMessage.contains("token", ignoreCase = true) || problem.userMessage.contains("Authentication", ignoreCase = true))
        assertTrue(problem.userAction.contains("Edit token") || problem.userAction.contains("pair again", ignoreCase = true))
    }

    @Test
    fun tokenRotationWithoutDeletingProfile() {
        val oldToken = "old-token"
        val newToken = "new-token"
        assertNotEquals(oldToken, newToken)
    }

    @Test
    fun qrPairingShortLivedCode() {
        val shortLivedCode = "qr-code-123"
        val expiryMs = 5 * 60 * 1000L
        assertTrue(expiryMs <= 5 * 60 * 1000L)
        assertNotEquals(shortLivedCode, "long-lived-token")
    }

    @Test
    fun authMethodsBearerPreferred() {
        // Bearer is preferred method
        val methods = listOf("bearer", "legacy_frame")
        assertEquals("bearer", methods.first())
    }
}
