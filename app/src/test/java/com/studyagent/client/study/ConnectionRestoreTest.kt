package com.studyagent.client.study

import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.study.SessionConnectionStatus
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.testutil.StudySessionHarness
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session must resume when the *real* transport reports readiness.
 *
 * Regression: the machine only treated the legacy [ConnectionState.Connected] state as
 * "restored", but [WebSocketAgentConnection] emits [ConnectionState.Ready] (v2) or
 * [ConnectionState.ReadyLegacy] (v1) after a handshake — so a production reconnect left the
 * session stuck in `Recovering` while every fake-based test passed.
 */
class ConnectionRestoreTest {

    private fun disconnectAndRestore(h: StudySessionHarness, restore: () -> Unit) {
        val answersBefore = h.sentAnswers().size
        val ratingsBefore = h.sentRatings().size

        h.connection.loseConnection()
        h.advance(500)
        assertTrue(
            "a lost connection must be visible, was ${h.currentPhase}",
            h.currentPhase is SessionPhase.Recovering
        )

        restore()
        h.advance(1_000)

        assertTrue(
            "the client must ask the server for authoritative state",
            h.connection.sentOfType("request_session_status").isNotEmpty()
        )
        assertEquals("reconnecting must not resubmit an answer", answersBefore, h.sentAnswers().size)
        assertEquals("reconnecting must not resubmit a rating", ratingsBefore, h.sentRatings().size)
        assertEquals(
            "the connection status must be honest after reconnecting",
            SessionConnectionStatus.CONNECTED,
            h.machineState.value.connection
        )
        h.assertInvariants("restore")
    }

    @Test
    fun `restores on Ready after a v2 handshake`() = runTest {
        val h = newHarness()
        h.startSession()
        disconnectAndRestore(h) { h.connection.restoreReady() }
    }

    @Test
    fun `restores on ReadyLegacy after a v1 handshake`() = runTest {
        val h = newHarness()
        h.startSession()
        disconnectAndRestore(h) { h.connection.restoreReadyLegacy() }
    }

    @Test
    fun `still restores on legacy Connected`() = runTest {
        val h = newHarness()
        h.startSession()
        disconnectAndRestore(h) { h.connection.restoreConnection() }
    }
}
