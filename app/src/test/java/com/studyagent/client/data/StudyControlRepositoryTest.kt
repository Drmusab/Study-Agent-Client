package com.studyagent.client.data

import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.data.repository.CapabilityStore
import com.studyagent.client.data.repository.ConfigSaveResult
import com.studyagent.client.data.repository.ConfigSaveState
import com.studyagent.client.data.repository.DefaultStudyControlRepository
import com.studyagent.client.data.repository.InMemoryManagementCacheStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * §9/§50-§53/§114-§116/§137-§140: control configuration is committed only
 * on a correlated ACK; rejection and timeout keep the authoritative config
 * untouched and preserve the user's draft.
 */
class StudyControlRepositoryTest {

    private class Harness(saveTimeoutMs: Long = 8_000L) {
        val connection = FakeConnectionRepository()
        val storage = InMemoryManagementCacheStorage()
        lateinit var scope: CoroutineScope
        lateinit var capabilities: CapabilityStore
        lateinit var control: DefaultStudyControlRepository

        fun start(scheduler: kotlinx.coroutines.test.TestScheduler) {
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
            capabilities = CapabilityStore(connection, scope, negotiationTimeoutMs = 4_000L)
            control = DefaultStudyControlRepository(
                connectionRepository = connection,
                capabilityStore = capabilities,
                cacheStorage = storage,
                dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(scheduler)),
                scope = scope,
                saveTimeoutMs = saveTimeoutMs
            )
        }

        suspend fun connectWithStudyConfig() {
            connection.setConnected(true)
            connection.emit(ServerMessage.Capabilities(capabilities = listOf(AgentCapability.STUDY_CONFIG)))
        }

        suspend fun answerConfigRequest(config: StudyControlConfig) {
            connection.emit(ServerMessage.StudyConfigResponse(config = config))
        }

        fun lastUpdateRequestId(): String? =
            connection.sentOfType("update_study_config").lastOrNull()?.messageId
    }

    private val serverConfig = StudyControlConfig(
        activeDeck = "MCCQE::Cardiology",
        studyMode = StudyMode.DUE_AND_NEW,
        newPerDay = 20
    )

    @Test
    fun `edit save ACK commits server config and clears draft`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectWithStudyConfig()
        advanceUntilIdle()
        h.answerConfigRequest(serverConfig)
        advanceUntilIdle()
        assertEquals(serverConfig, h.control.serverConfig.value)

        // §50: edits go to the draft, never straight to the authoritative config.
        h.control.updateDraft { it.copy(newPerDay = 33) }
        assertEquals(33, h.control.draft.value?.newPerDay)
        assertEquals(20, h.control.serverConfig.value?.newPerDay)

        val candidate = h.control.draft.value!!
        val saveJob = h.scope.async { h.control.saveConfig(candidate) }
        advanceUntilIdle()

        val requestId = h.lastUpdateRequestId()
        assertTrue(requestId != null)
        // §114: ACK echoes the request message_id.
        h.connection.emit(ServerMessage.StudyConfigUpdated(messageId = requestId, config = candidate))
        advanceUntilIdle()

        assertEquals(ConfigSaveResult.Saved, saveJob.await())
        assertEquals(33, h.control.serverConfig.value?.newPerDay)
        assertNull(h.control.draft.value)
        assertTrue(h.control.saveState.value is ConfigSaveState.Saved)
    }

    @Test
    fun `server rejection keeps draft and leaves authoritative config untouched`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectWithStudyConfig()
        advanceUntilIdle()
        h.answerConfigRequest(serverConfig)
        advanceUntilIdle()

        h.control.updateDraft { it.copy(newPerDay = 44) }
        val candidate = h.control.draft.value!!
        val saveJob = h.scope.async { h.control.saveConfig(candidate) }
        advanceUntilIdle()

        h.connection.emit(
            ServerMessage.ErrorMessage(
                messageId = h.lastUpdateRequestId(),
                code = "config_rejected",
                message = "The Study Agent rejected this configuration."
            )
        )
        advanceUntilIdle()

        val result = saveJob.await()
        assertTrue(result is ConfigSaveResult.Rejected)
        assertEquals("The Study Agent rejected this configuration.", (result as ConfigSaveResult.Rejected).reason)
        // §52: authoritative config unchanged, draft retained.
        assertEquals(20, h.control.serverConfig.value?.newPerDay)
        assertEquals(44, h.control.draft.value?.newPerDay)
        assertTrue(h.control.saveState.value is ConfigSaveState.Error)
    }

    @Test
    fun `missing ACK times out instead of spinning Saving forever`() = runTest(timeout = 30.seconds) {
        val h = Harness(saveTimeoutMs = 8_000L)
        h.start(testScheduler)
        h.connectWithStudyConfig()
        advanceUntilIdle()
        h.answerConfigRequest(serverConfig)
        advanceUntilIdle()

        h.control.updateDraft { it.copy(newPerDay = 51) }
        val saveJob = h.scope.async { h.control.saveConfig(h.control.draft.value!!) }
        advanceUntilIdle()
        assertTrue(h.control.saveState.value is ConfigSaveState.Saving)

        // §53/§139: bounded wait, then a clean exit.
        advanceTimeBy(9_000)
        assertEquals(ConfigSaveResult.TimedOut, saveJob.await())
        assertEquals(20, h.control.serverConfig.value?.newPerDay)
        assertEquals(51, h.control.draft.value?.newPerDay)
        val state = h.control.saveState.value
        assertTrue(state is ConfigSaveState.Error && state.timedOut)
    }

    @Test
    fun `rapid saves cannot flood the server with duplicate updates`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectWithStudyConfig()
        advanceUntilIdle()
        h.answerConfigRequest(serverConfig)
        advanceUntilIdle()

        h.control.updateDraft { it.copy(newPerDay = 61) }
        val candidate = h.control.draft.value!!
        val first = h.scope.async { h.control.saveConfig(candidate) }
        advanceUntilIdle()
        assertEquals(1, h.connection.sentOfType("update_study_config").size)

        // §140: second and third taps while the ACK is in flight are refused.
        assertEquals(ConfigSaveResult.AlreadySaving, h.control.saveConfig(candidate))
        assertEquals(ConfigSaveResult.AlreadySaving, h.control.saveConfig(candidate))
        assertEquals(1, h.connection.sentOfType("update_study_config").size)

        h.connection.emit(ServerMessage.StudyConfigUpdated(messageId = h.lastUpdateRequestId(), config = candidate))
        advanceUntilIdle()
        assertEquals(ConfigSaveResult.Saved, first.await())
    }

    @Test
    fun `invalid config never leaves the device`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectWithStudyConfig()
        advanceUntilIdle()
        h.answerConfigRequest(serverConfig)
        advanceUntilIdle()

        // §136: 0 cards / 500 minutes / 20% confidence are rejected by the model.
        val invalids = listOf(
            serverConfig.copy(sessionTargetValue = 0),
            serverConfig.copy(
                sessionTargetType = com.studyagent.client.core.models.SessionTargetType.MINUTES,
                sessionTargetValue = 500
            ),
            serverConfig.copy(
                ratingMode = com.studyagent.client.core.models.AutoRatingMode.AUTO_CONFIDENT,
                autoRateConfidence = 20
            )
        )
        invalids.forEach { invalid ->
            val result = h.control.saveConfig(invalid)
            assertTrue("Expected Invalid for $invalid", result is ConfigSaveResult.Invalid)
        }
        assertEquals(0, h.connection.sentOfType("update_study_config").size)
    }

    @Test
    fun `protocol v1 stores config locally and sends nothing`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connection.setConnected(true)
        // No capabilities frame: legacy v1 after negotiation timeout (§133).
        advanceTimeBy(5_000)
        advanceUntilIdle()
        assertTrue(h.capabilities.capabilities.value.isLegacyV1)

        val candidate = serverConfig.copy(newPerDay = 27)
        val result = h.control.saveConfig(candidate)
        assertEquals(ConfigSaveResult.SavedLocally, result)
        assertEquals(27, h.control.localConfig.value.newPerDay)
        assertEquals(0, h.connection.sentOfType("update_study_config").size)
        // Start payload on v1 carries deck + mode only (§76).
        val start = h.control.currentStartRequest()
        assertEquals("MCCQE::Cardiology", start.deck)
        assertNull(start.config)
    }

    @Test
    fun `v2 start request carries deck mode and structured config`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectWithStudyConfig()
        advanceUntilIdle()
        h.answerConfigRequest(serverConfig.copy(studyMode = StudyMode.WEAK_CARDS))
        advanceUntilIdle()

        val start = h.control.currentStartRequest()
        assertEquals("MCCQE::Cardiology", start.deck)
        assertEquals("weak_cards", start.mode)
        assertTrue(start.config != null)
    }

    @Test
    fun `unsolicited server config update is committed and surfaced`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectWithStudyConfig()
        advanceUntilIdle()
        h.answerConfigRequest(serverConfig)
        advanceUntilIdle()

        val pushed = serverConfig.copy(newPerDay = 12)
        var received: StudyControlConfig? = null
        val job = h.scope.async {
            h.control.serverPushedConfig.collect { received = it }
        }
        h.connection.emit(ServerMessage.StudyConfigUpdated(messageId = "server-side-change", config = pushed))
        advanceUntilIdle()

        assertEquals(12, h.control.serverConfig.value?.newPerDay)
        assertEquals(pushed, received)
        job.cancel()
    }

    @Test
    fun `setActiveDeck updates draft and syncs when supported`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectWithStudyConfig()
        advanceUntilIdle()
        h.answerConfigRequest(serverConfig)
        advanceUntilIdle()

        // §126/§134: deck list changes; selection stays valid through sync.
        h.control.setActiveDeck("Pharmacology")
        advanceUntilIdle()
        val ackId = h.lastUpdateRequestId()
        assertTrue(ackId != null)
        h.connection.emit(
            ServerMessage.StudyConfigUpdated(messageId = ackId, config = h.control.draft.value ?: serverConfig)
        )
        advanceUntilIdle()

        assertEquals("Pharmacology", h.control.serverConfig.value?.activeDeck)
        assertNull(h.control.draft.value)
    }
}
