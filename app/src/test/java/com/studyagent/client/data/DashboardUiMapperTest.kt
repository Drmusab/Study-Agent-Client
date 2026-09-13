package com.studyagent.client.data

import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DashboardSnapshotPayload
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.models.StudyPreset
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.data.repository.DashboardData
import com.studyagent.client.data.repository.StartStudyRequest
import com.studyagent.client.ui.screens.home.DashboardUiMapper
import com.studyagent.client.ui.screens.home.PrimaryAction
import com.studyagent.client.ui.screens.home.SessionPhaseSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §23/§24/§143/§145: dashboard state mapping — the primary smart action is
 * derived, resume never starts a second session, and every lifecycle state is
 * representable.
 */
class DashboardUiMapperTest {

    private val connected = ConnectionState.Connected("h", 1)
    private val capsV2 = AgentCapabilities(
        status = AgentCapabilities.NegotiationStatus.NEGOTIATED_V2,
        capabilities = setOf("dashboard")
    )
    private val startRequest = StartStudyRequest("MCCQE::Cardiology", "due_and_new", null)
    private val config = StudyControlConfig(activeDeck = "MCCQE::Cardiology", studyMode = StudyMode.DUE_AND_NEW)

    private fun build(
        connection: ConnectionState = connected,
        study: StudyState = StudyState.Idle,
        dashboard: DashboardData = DashboardData(),
        capabilities: AgentCapabilities = capsV2
    ) = DashboardUiMapper.build(
        connectionState = connection,
        capabilities = capabilities,
        dashboard = dashboard,
        studyState = study,
        effectiveConfig = config,
        startRequest = startRequest,
        audioRoute = null,
        nowMs = 1_000_000_000_000L
    )

    @Test
    fun `disconnected primary action is CONNECT`() {
        assertEquals(PrimaryAction.CONNECT, build(connection = ConnectionState.Disconnected).primaryAction)
    }

    @Test
    fun `connecting state disables the button`() {
        val state = build(connection = ConnectionState.Connecting("h", 1))
        assertEquals(PrimaryAction.CONNECTING, state.primaryAction)
    }

    @Test
    fun `connected with no session offers START`() {
        assertEquals(PrimaryAction.START, build().primaryAction)
    }

    @Test
    fun `starting state shows STARTING and blocks duplicate taps`() {
        val state = build(study = StudyState.Loading("Starting..."))
        assertEquals(PrimaryAction.STARTING, state.primaryAction)
        assertEquals(SessionPhaseSummary.STARTING, state.sessionPhase)
    }

    @Test
    fun `active session offers RESUME, never a new start`() {
        val card = StudyCard(id = "c1", question = "Q")
        val active = build(study = StudyState.Listening(card = card))
        assertEquals(PrimaryAction.RESUME_STUDY, active.primaryAction)
        assertEquals(SessionPhaseSummary.ACTIVE, active.sessionPhase)

        val paused = build(study = StudyState.Paused(previousState = StudyState.Listening(card = card)))
        assertEquals(PrimaryAction.RESUME_SESSION, paused.primaryAction)
        assertEquals(SessionPhaseSummary.PAUSED, paused.sessionPhase)
    }

    @Test
    fun `server-side active session also drives resume`() {
        val dashboard = DashboardData(
            activeSession = SessionSummaryPayload(sessionId = "s1", deck = "D", cardsReviewed = 37, isPaused = false)
        )
        assertEquals(PrimaryAction.RESUME_STUDY, build(dashboard = dashboard).primaryAction)

        val pausedDashboard = DashboardData(
            activeSession = SessionSummaryPayload(sessionId = "s1", deck = "D", isPaused = true)
        )
        assertEquals(PrimaryAction.RESUME_SESSION, build(dashboard = pausedDashboard).primaryAction)
    }

    @Test
    fun `finished session returns to START`() {
        val state = build(study = StudyState.SessionFinished(summary = "done", cardsReviewed = 10))
        assertEquals(PrimaryAction.START, state.primaryAction)
        assertTrue(state.finishedSession != null)
    }

    @Test
    fun `smart start label reflects the control configuration`() {
        val summary = DashboardUiMapper.startSummary(config)
        assertEquals(StudyMode.DUE_AND_NEW.displayName, summary.modeLabel)
        assertEquals("MCCQE::Cardiology", summary.deckLabel)
        assertTrue(summary.subtitle.contains("Due + New"))
    }

    @Test
    fun `preset-matching config names the preset in the start summary`() {
        val deep = StudyPreset.DEEP_STUDY.applyTo(config)
        val summary = DashboardUiMapper.startSummary(deep)
        assertEquals("Deep Study", summary.presetName)
        assertTrue(summary.subtitle.contains("45 min"))
    }

    @Test
    fun `nested deck display keeps hierarchy available`() {
        assertEquals("Cardiology", DashboardUiMapper.deckDisplayName("MCCQE::Cardiology"))
        assertEquals("MCCQE", DashboardUiMapper.deckHierarchy("MCCQE::Cardiology"))
        assertEquals("Plain Deck", DashboardUiMapper.deckDisplayName("Plain Deck"))
        assertEquals(null, DashboardUiMapper.deckHierarchy("Plain Deck"))
    }

    @Test
    fun `selected deck unavailable is detected against the live deck list`() {
        val dashboard = DashboardData(
            decks = listOf(com.studyagent.client.core.models.DeckSummary(name = "Other"))
        )
        val state = build(dashboard = dashboard)
        assertTrue(state.selectedDeckUnavailable)
        assertEquals(null, state.selectedDeck)
    }
}
