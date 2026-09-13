package com.studyagent.client.data

import com.studyagent.client.core.models.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProfileTest {

    @Test
    fun `normalization rejects malformed or unsafe persisted profile fields`() {
        assertNull(ServerProfile(id = "", name = "x", host = "host").normalized())
        assertNull(ServerProfile(id = "id", name = "x", host = "https://host").normalized())
        assertNull(ServerProfile(id = "id", name = "x", host = "host:8765").normalized())
        assertNull(ServerProfile(id = "id", name = "x", host = "host", port = 0).normalized())
        assertNull(ServerProfile(id = "id", name = "x", host = "host", path = "ws://other").normalized())
    }

    @Test
    fun `normalization keeps valid IPv6 and strips credentials`() {
        val profile = ServerProfile(
            id = "study",
            name = "  Study PC ",
            host = "[::1]",
            path = "ws",
            authToken = "secret"
        ).normalized()

        assertEquals("::1", profile?.host)
        assertEquals("/ws", profile?.path)
        assertEquals("Study PC", profile?.name)
        assertEquals("secret", profile?.authToken)
        assertEquals("ws://[::1]:8765/ws", profile?.toWebSocketUrl())
    }
}
