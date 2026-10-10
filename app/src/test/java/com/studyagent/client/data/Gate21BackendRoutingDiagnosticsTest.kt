package com.studyagent.client.data

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRoutingDiagnostics
import com.studyagent.client.core.anki.BackendSelectionReason
import com.studyagent.client.data.repository.DefaultDiagnosticsRepository
import com.studyagent.client.data.repository.DiagnosticsAppInfo
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21 PART III — VER-21-30 / AUDIT-21-15: the diagnostics surface keeps the four backend roles
 * apart.
 *
 * The failure this prevents is a support report that says "the app used PC". That sentence is
 * ambiguous between four different facts — what the user *prefers*, what a pre-binding resolution
 * *chose*, what an active session is *locked* to, and what a durable transaction was *written
 * against*. Collapsing any two of them makes a report actively misleading (DECISION-21-35).
 *
 * These rows are also the gate's "no card content in diagnostics" boundary: every value asserted
 * here is a stable identity token, a mode name or a count.
 */
class Gate21BackendRoutingDiagnosticsTest {

    private val localId = AnkiBackendId.AnkiDroidLocal
    private val pcId = AnkiBackendId.PcAgent("gate21-desktop")

    private fun kotlinx.coroutines.test.TestScope.repoWith(
        routing: AnkiBackendRoutingDiagnostics?
    ): DefaultDiagnosticsRepository {
        val harness = newHarness()
        return DefaultDiagnosticsRepository(
            connectionRepository = harness.connection,
            audioRouteManager = harness.routeManager,
            speechOrchestrator = harness.speech,
            recognitionOrchestrator = harness.recognition,
            studyAudioRouteCoordinator = harness.coordinator,
            settingsFlow = null,
            scope = null,
            appInfo = { DiagnosticsAppInfo() },
            ankiBackendRouting = { routing }
        )
    }

    private fun kotlinx.coroutines.test.TestScope.rowsOf(
        routing: AnkiBackendRoutingDiagnostics?
    ): Map<String, String> =
        repoWith(routing).ankiDroidDiagnosticsRows().toMap()

    @Test
    fun `preferred resolved and locked are three separate rows and never one another`() = runTest {
        val rows = rowsOf(
            AnkiBackendRoutingDiagnostics(
                preferred = AnkiBackendMode.PC_AGENT,
                resolved = localId,
                resolvedReason = BackendSelectionReason.AUTO_LOCAL_PREFERRED,
                lockedSessionBackend = pcId,
                lockedSessionCount = 1,
                transactionBackend = localId,
                availability = mapOf(localId to "ready", pcId to "agent_disconnected")
            )
        )

        // The user prefers PC...
        // Stable diagnostics tokens, not enum names (AUDIT-21-15): "pc_agent", not "PC_AGENT".
        assertEquals("pc_agent", rows.getValue("Preferred backend"))
        // ...but the last resolution chose the local collection, for a stated reason...
        assertEquals(localId.stableId, rows.getValue("Resolved backend"))
        assertEquals("AUTO_LOCAL_PREFERRED", rows.getValue("Resolution reason"))
        // ...while the session in flight is locked to PC, and a durable transaction belongs to
        // AnkiDroid. Four roles, four rows, no collapsing.
        assertEquals(pcId.stableId, rows.getValue("Locked session backend"))
        assertEquals(localId.stableId, rows.getValue("Transaction backend"))

        assertEquals(setOf(localId, pcId), setOf(localId, pcId))
        assertEquals("ready", rows.getValue("Availability ${localId.stableId}"))
        assertEquals("agent_disconnected", rows.getValue("Availability ${pcId.stableId}"))
    }

    @Test
    fun `roles that have no value are reported as not measured rather than guessed`() = runTest {
        val rows = rowsOf(
            AnkiBackendRoutingDiagnostics(
                preferred = AnkiBackendMode.AUTO,
                resolved = null,
                resolvedReason = null,
                lockedSessionBackend = null,
                lockedSessionCount = 0,
                transactionBackend = null,
                availability = emptyMap()
            )
        )

        // An absent lock must not be rendered as "the preferred backend" or as some default
        // identity: that is exactly the collapse AUDIT-21-15 forbids.
        assertEquals("auto", rows.getValue("Preferred backend"))
        // "-" is the shared "not measured" token; an absent lock is never rendered as the
        // preferred backend or as some default identity.
        assertEquals("-", rows.getValue("Resolved backend"))
        assertEquals("-", rows.getValue("Locked session backend"))
        assertEquals("-", rows.getValue("Resolution reason"))
        assertFalse("no transaction row when no transaction is known", rows.containsKey("Transaction backend"))
        assertEquals("0", rows.getValue("Locked session count"))
    }

    @Test
    fun `several locked sessions report a count instead of naming one backend arbitrarily`() = runTest {
        // Naming one session's authority would state a falsehood about the others (INV-21-05).
        val rows = rowsOf(
            AnkiBackendRoutingDiagnostics(
                preferred = AnkiBackendMode.AUTO,
                resolved = localId,
                resolvedReason = BackendSelectionReason.ONLY_CANDIDATE,
                lockedSessionBackend = null,
                lockedSessionCount = 3,
                transactionBackend = null,
                availability = emptyMap()
            )
        )

        assertEquals("3", rows.getValue("Locked session count"))
        assertEquals("-", rows.getValue("Locked session backend"))
    }

    @Test
    fun `an unwired routing supplier adds no backend rows and claims no identity`() = runTest {
        val rows = rowsOf(null)

        assertFalse(rows.containsKey("Preferred backend"))
        assertFalse(rows.containsKey("Resolved backend"))
        assertFalse(rows.containsKey("Locked session backend"))
        // Nothing here may invent "ankidroid_local" for a backend that was never observed
        // (DISCOVERY-21-34).
        assertFalse(
            "diagnostics must not assert an unobserved backend identity",
            rows.values.any { it == localId.stableId }
        )
    }
}
