package com.studyagent.client.data

import com.studyagent.client.core.anki.*
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.diagnostics.AppPerformanceMetrics
import com.studyagent.client.data.repository.DefaultDiagnosticsRepository
import com.studyagent.client.data.repository.DiagnosticsAppInfo
import com.studyagent.client.testutil.StudySessionHarness
import com.studyagent.client.testutil.TestTranscripts
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics must be useful to an engineer *and* safe to send to one (§81-§88/§157).
 *
 * Those two goals pull in opposite directions: the more context an export carries, the easier a
 * bug is to explain, and the easier it also is to leak a medical answer, a token or a payload.
 * The resolution this suite enforces is structural rather than cosmetic:
 *
 * - the session machine records *shapes* (character counts, ids, phases) and never content, so the
 *   most sensitive strings in the app are not in the export to begin with;
 * - what *can* reach a log row goes through redaction before it is stored, not on the way out;
 * - the export still says which build, device, protocol and server version produced it, because a
 *   report without that is not actionable (§84).
 *
 * Everything asserted here is asserted on the **export text**, not on an internal field: the text
 * is the artifact a user actually copies into a bug report.
 */
class DiagnosticsExportPrivacyTest {

    @After
    fun tearDown() {
        AppLogger.clear()
    }

    /** Diagnostics over a live harness: the real machine, the real timeline, the real log buffer. */
    private fun StudySessionHarness.diagnosticsRepo(
        appInfo: DiagnosticsAppInfo = testAppInfo(), ledger: ReviewCommitLedger? = null
    ): DefaultDiagnosticsRepository =
        DefaultDiagnosticsRepository(
            connectionRepository = connection,
            audioRouteManager = routeManager,
            speechOrchestrator = speech,
            recognitionOrchestrator = recognition,
            studyAudioRouteCoordinator = coordinator,
            settingsFlow = null,
            scope = null,
            performanceMetrics = performance,
            timeline = timeline,
            networkStats = com.studyagent.client.core.network.NetworkStatsRegistry.current,
            capabilityStore = null,
            sessionDiagnostics = { repository.diagnostics() },
            machineResources = { repository.resourceSnapshot() },
            dashboardRepository = null,
            studyControlRepository = null,
            persistenceDiagnostics = null,
            persistenceSnapshot = null,
            appInfo = { appInfo },
            reviewCommitLedger = ledger
        )

    private fun testAppInfo() = DiagnosticsAppInfo(
        appVersion = "2.5.0-debug",
        buildType = "debug",
        androidVersion = "Android 14 (API 34)",
        deviceModel = "Pixel 7",
        protocolVersion = "2",
        serverVersion = "2.4.0"
    )

    @Test
    fun `a clinical answer handled by the session never appears in the export`() = runTest {
        AppLogger.isDebugEnabled = true
        val h = newHarness(serverDeckSize = 5)
        h.answerText = TestTranscripts.CLINICAL_ANSWER
        h.startSession()
        h.playCard()

        val export = h.diagnosticsRepo().getFormattedLogsText()

        assertFalse(
            "a patient-describing transcript must never reach an export",
            export.contains("My patient has")
        )
        assertFalse(export.contains("subdural hematoma"))
        // The *shape* of the turn is there, which is what makes the export useful.
        assertTrue(export.contains("ANSWER_SENT"))
        assertTrue(export.contains("chars="))
        assertTrue(export.contains("QUESTION_RECEIVED"))
    }

    @Test
    fun `secrets logged anywhere are redacted before they reach the export`() = runTest {
        AppLogger.isDebugEnabled = true
        AppLogger.i("Connection", "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.body.signature")
        AppLogger.w("Settings", "token=fake-device-token-1234567890")
        AppLogger.e("Protocol", """{"password":"not-a-real-password","api_key":"fake-key-abcdef"}""")

        val h = newHarness(serverDeckSize = 5)
        h.startSession()

        val export = h.diagnosticsRepo().getFormattedLogsText()
        assertFalse(export.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(export.contains("fake-device-token-1234567890"))
        assertFalse(export.contains("not-a-real-password"))
        assertFalse(export.contains("fake-key-abcdef"))
        assertTrue("the reader must be able to see that a value was removed", export.contains("***REDACTED***"))
    }

    @Test
    fun `the export says which build device and protocol produced it`() = runTest {
        val h = newHarness(serverDeckSize = 5)
        h.startSession()
        val export = h.diagnosticsRepo().getFormattedLogsText()

        assertTrue(export.contains("2.5.0-debug"))
        assertTrue(export.contains("debug"))
        assertTrue(export.contains("Android 14 (API 34)"))
        assertTrue(export.contains("Pixel 7"))
        assertTrue(export.contains("Protocol: 2"))
        assertTrue(export.contains("server 2.4.0"))
    }

    @Test
    fun `unmeasured metrics are reported as unknown, never as zero milliseconds`() = runTest {
        // A freshly constructed metrics object has measured nothing. "0ms" would be a lie that
        // sends an engineer looking for a latency bug that does not exist (§66).
        com.studyagent.client.core.diagnostics.PerformanceMetrics().let { fresh ->
            val repository = DefaultDiagnosticsRepository(
                connectionRepository = com.studyagent.client.testutil.FakeConnectionRepository(),
                audioRouteManager = com.studyagent.client.testutil.FakeAudioRouteManager(),
                speechOrchestrator = com.studyagent.client.testutil.FakeSpeechOrchestrator(),
                recognitionOrchestrator = com.studyagent.client.testutil.FakeRecognitionOrchestrator(),
                performanceMetrics = fresh,
                appInfo = { testAppInfo() }
            )
            val rows = repository.performanceDiagnosticsRows()
            assertTrue("performance rows must exist even with no samples", rows.isNotEmpty())
            val text = rows.joinToString(" ") { "${it.first}=${it.second}" }
            assertFalse("an unmeasured family must not render as 0ms: $text", text.contains("0ms"))
            assertTrue("unmeasured families render as '-': $text", text.contains("-"))
        }
    }

    @Test
    fun `every diagnostics section renders without its optional collaborators`() = runTest {
        // The Diagnostics screen must never crash because a subsystem is missing in a given build.
        val repository = DefaultDiagnosticsRepository(
            connectionRepository = com.studyagent.client.testutil.FakeConnectionRepository(),
            audioRouteManager = com.studyagent.client.testutil.FakeAudioRouteManager(),
            speechOrchestrator = com.studyagent.client.testutil.FakeSpeechOrchestrator(),
            recognitionOrchestrator = com.studyagent.client.testutil.FakeRecognitionOrchestrator(),
            performanceMetrics = AppPerformanceMetrics.metrics,
            appInfo = { testAppInfo() }
        )

        assertTrue(repository.sessionDiagnosticsRows().isNotEmpty())
        assertTrue(repository.networkDiagnosticsRows().isNotEmpty())
        assertTrue(repository.protocolDiagnosticsRows().isNotEmpty())
        assertTrue(repository.performanceDiagnosticsRows().isNotEmpty())
        assertTrue(repository.dashboardDiagnosticsRows().isNotEmpty())
        assertTrue(repository.controlDiagnosticsRows().isNotEmpty())
        assertTrue(repository.persistenceDiagnosticsRows().isNotEmpty())
        assertTrue(repository.studyAudioDiagnosticsRows().isNotEmpty())
        assertTrue(repository.recognitionDiagnosticsRows().isNotEmpty())

        val export = repository.getFormattedLogsText()
        assertTrue(export.contains("--- Session ---"))
        assertTrue(export.contains("--- Network ---"))
        assertTrue(export.contains("--- Performance ---"))
        assertTrue(export.contains("--- Persistence ---"))
    }

    @Test
    fun `ledger health and unresolved count are exported without transaction identifiers`() = runTest {
        val id = AnkiBackendId.Fake("private-backend")
        val commitId = ReviewCommitId(id, "private-session", ReviewTurnId("private-turn"))
        val card = AnkiCardRef(id, "private-card", "private-note", 0, "private-collection")
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), { 123L })
        val request = CommitRatingRequest(commitId, card, Rating.GOOD, 123L)
        ledger.prepare(request)
        ledger.claim(commitId, null, false)
        ledger.markMutationEntered(commitId)
        ledger.markAmbiguous(commitId, "unknown_response")
        val h = newHarness(serverDeckSize = 1)
        val export = h.diagnosticsRepo(ledger = ledger).getFormattedLogsText()
        assertTrue(export.contains("Ledger health: Ready"))
        assertTrue(export.contains("Ledger ambiguous: 1"))
        for (secret in listOf("private-backend", "private-session", "private-turn", "private-card",
                "private-note", "private-collection", "unknown_response")) {
            assertFalse("ledger export must not include $secret", export.contains(secret))
        }
    }

    @Test
    fun `clearing logs and the timeline is honoured by the export`() = runTest {
        AppLogger.isDebugEnabled = true
        val h = newHarness(serverDeckSize = 5)
        h.startSession()
        h.playCard()
        val repository = h.diagnosticsRepo()

        assertTrue(repository.timelineEvents().isNotEmpty())
        assertTrue(repository.logs.value.isNotEmpty())

        repository.clearLogs()
        repository.clearTimeline()

        assertTrue("cleared logs must not come back", repository.logs.value.isEmpty())
        assertTrue("cleared timeline must be empty", repository.timelineEvents().isEmpty())

        val export = repository.getFormattedLogsText()
        assertTrue(export.contains("(no diagnostic events recorded)"))
    }

    @Test
    fun `the compact summary carries status without the log dump`() = runTest {
        AppLogger.isDebugEnabled = true
        val h = newHarness(serverDeckSize = 5)
        h.startSession()
        AppLogger.i("Marker", "a row that must not be copied into the compact summary")

        val repository = h.diagnosticsRepo()
        val summary = repository.getSummaryText()
        val detailed = repository.getFormattedLogsText()

        assertTrue(summary.contains("=== Study Agent Diagnostics Summary ==="))
        assertTrue(summary.contains("Connection"))
        assertTrue(summary.contains("--- Performance ---"))
        assertFalse("the compact summary must not carry log rows", summary.contains("a row that must not be copied"))
        assertTrue("the detailed export does carry them", detailed.contains("a row that must not be copied"))
    }

    @Test
    fun `the timeline export is bounded even after a long session`() = runTest {
        val h = newHarness(serverDeckSize = 100, autoAnswer = true, timelineCapacity = 300)
        h.startSession()
        repeat(20) { h.playCard() }

        val repository = h.diagnosticsRepo()
        val events = repository.timelineEvents()
        assertTrue("the timeline is bounded by construction", events.size <= 300)
        assertEquals("events must be ordered oldest → newest", events.map { it.sequence }.sorted(), events.map { it.sequence })
        assertTrue("recent events are the ones retained", events.last().sequence > 0)
    }
}
