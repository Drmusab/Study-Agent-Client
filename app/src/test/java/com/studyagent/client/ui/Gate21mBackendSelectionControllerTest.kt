package com.studyagent.client.ui

import com.studyagent.client.anki.FixedPreferenceStore
import com.studyagent.client.anki.ProbeOnlyBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendStatusCenter
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiStudyBackendLockTracker
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendResolutionReason
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.ui.components.anki.ActiveStudySessionUi
import com.studyagent.client.ui.components.anki.AnkiBackendRefreshUi
import com.studyagent.client.ui.components.anki.AnkiBackendSaveFeedbackUi
import com.studyagent.client.ui.components.anki.AnkiBackendSectionUiState
import com.studyagent.client.ui.screens.settings.AnkiBackendSelectionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic settling for this harness.
 *
 * The bundled `kotlinx-coroutines-test` does **not** skip virtual time on `advanceUntilIdle`, so a
 * suspended confirmation window would never elapse. Settling is therefore explicit: dispatch pending
 * work with [runCurrent], optionally advance the test clock, and dispatch again. Every assertion in
 * this file runs after a settle, so no test depends on wall-clock timing (VER-21M-R2-11 … 16).
 */
private fun TestScope.settle(pastConfirmationMs: Long = 0) {
    runCurrent()
    if (pastConfirmationMs > 0) testScheduler.advanceTimeBy(pastConfirmationMs)
    runCurrent()
}

/**
 * GATE 21M Revision 2 — the backend-selection controller's **asynchronous honesty**
 * (CHECKPOINT 21M-11/13/21/23/24; VER-21M-R2-11 … 16, 21, 24).
 *
 * The controller is the only GATE 21M component that writes anything, and it writes exactly one thing:
 * the user's *preference*. These tests pin the four ways that could go wrong and must not:
 *
 * - a refused write reported as saved (the user believes a backend change that did not happen);
 * - an accepted-but-unconfirmed write reported as saved (the store never echoed it back);
 * - a late result of a superseded write published over the newer decision (a race inventing history);
 * - an exception turned into success, or its message turned into a log line that leaks a path or key.
 *
 * The refresh path is pinned as read-only: the probe-only fixture records every call that reaches a
 * backend, so "only `refreshAvailability`" is an assertion, not an intention. A backend removed while
 * its probe ran is reported as absent rather than answered from a sibling's state.
 *
 * Finally, the boundary GATE 21 exists for: changing the preference never rewrites an active session's
 * owner, and the rendered selection always comes from the authoritative preference flow.
 */
class Gate21mBackendSelectionControllerTest {

    private val capabilities = AnkiCapabilities(review = true, scheduledReview = true, deckListing = true)
    private val ankiDroid = AnkiBackendId.AnkiDroidLocal
    private val pcHome = AnkiBackendId.PcAgent("home")

    private fun backend(
        id: AnkiBackendId,
        availability: AnkiAvailability = AnkiAvailability.Ready(capabilities),
        onRefresh: suspend ProbeOnlyBackend.() -> Unit = {}
    ) = ProbeOnlyBackend(
        id = id,
        availability = availability,
        capabilities = capabilities,
        claim = capabilities,
        onRefresh = onRefresh
    )

    private fun lock(id: AnkiBackendId, session: String) = AnkiSessionContext(
        backendId = id,
        collection = AnkiCollectionIdentity(id, "col-1"),
        deckRef = null,
        startedAtEpochMs = 1L,
        capabilities = capabilities,
        studySessionId = session
    )

    /**
     * One deterministic wiring of the controller over real GATE 21 components (registry, status center,
     * lock tracker) and a preference seam the test drives by hand — the same shape production passes
     * (`BackendPreferenceRepository.preference` / `lastWriteError` / `setPreference`).
     */
    private class Harness(
        private val scope: TestScope,
        backends: List<ProbeOnlyBackend>,
        initialPreference: BackendPreference = BackendPreference.Auto,
        /** A writer with the harness as receiver, so it can echo into the authoritative flow. */
        writerBehavior: (suspend Harness.(BackendPreference) -> Boolean)? = null,
        writeErrorToken: String? = null,
        confirmationTimeoutMs: Long = 1_000L,
        val sessionLocks: AnkiStudyBackendLockTracker = AnkiStudyBackendLockTracker(),
        preview: ((List<AnkiBackendState>, Set<AnkiRequiredCapability>) -> BackendResolutionResult2)? = null,
        withRegistry: Boolean = true
    ) {
        val registry = AnkiBackendRegistry(backends)
        val preference = MutableStateFlow(initialPreference)
        val writeError = MutableStateFlow(writeErrorToken)
        val writes = mutableListOf<BackendPreference>()
        val logs = mutableListOf<String>()
        var previewCalls = 0

        private val center = AnkiBackendStatusCenter(
            registry = registry,
            preferenceStore = FixedPreferenceStore(AnkiBackendMode.AUTO),
            resolver = AnkiBackendResolver(registry),
            scope = scope.backgroundScope
        )

        val controller = AnkiBackendSelectionController(
            scope = scope.backgroundScope,
            statusCenter = center,
            preference = preference,
            registry = if (withRegistry) registry else null,
            writeError = writeError,
            writer = writerBehavior?.let { behavior ->
                { target ->
                    writes += target
                    behavior(target)
                }
            },
            sessionLocks = sessionLocks,
            autoPreview = preview?.let { resolve ->
                { states, required ->
                    previewCalls++
                    resolve(states, required)
                }
            },
            confirmationTimeoutMs = confirmationTimeoutMs,
            logFailure = { logs += it }
        )

        private val observed = mutableListOf<AnkiBackendSectionUiState?>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                controller.section.collect { observed += it }
            }
        }

        /** The latest projected section, after pending work on the test scheduler has run. */
        fun section(): AnkiBackendSectionUiState? {
            scope.runCurrent()
            return observed.lastOrNull()
        }
    }

    private fun TestScope.harness(
        backends: List<ProbeOnlyBackend> = listOf(backend(ankiDroid)),
        initialPreference: BackendPreference = BackendPreference.Auto,
        writerBehavior: (suspend Harness.(BackendPreference) -> Boolean)? = null,
        writeErrorToken: String? = null,
        confirmationTimeoutMs: Long = 1_000L,
        withRegistry: Boolean = true,
        sessionLocks: AnkiStudyBackendLockTracker = AnkiStudyBackendLockTracker(),
        preview: ((List<AnkiBackendState>, Set<AnkiRequiredCapability>) -> BackendResolutionResult2)? = null
    ) = Harness(
        scope = this,
        backends = backends,
        initialPreference = initialPreference,
        writerBehavior = writerBehavior,
        writeErrorToken = writeErrorToken,
        confirmationTimeoutMs = confirmationTimeoutMs,
        sessionLocks = sessionLocks,
        preview = preview,
        withRegistry = withRegistry
    )

    // ------------------------------------------------------------------ projection

    @Test
    fun `the section projects the registry inventory and the authoritative preference`() = runTest {
        val h = harness(backends = listOf(backend(ankiDroid), backend(pcHome)))
        settle()

        val section = h.section()
        assertNotNull(section)
        // One row per configured instance, keyed by identity, plus AUTO. Both kinds are configured, so
        // no informational "not implemented" row exists.
        assertEquals(
            listOf("preference:auto", "ankidroid_local", "pc_agent:home"),
            section!!.rows.map { it.key }
        )
        assertEquals("preference:auto", section.selectedKey)
        assertEquals("auto", section.preferenceToken)
        assertFalse(section.hasDuplicateKeys)
    }

    @Test
    fun `the rendered selection follows the authoritative preference flow, not an intent`() = runTest {
        val h = harness()
        settle()
        assertEquals("preference:auto", h.section()!!.selectedKey)

        h.preference.value = BackendPreference.Explicit(ankiDroid)
        settle()

        val section = h.section()!!
        assertEquals("ankidroid_local", section.selectedKey)
        assertEquals("explicit:ankidroid_local", section.preferenceToken)
        assertTrue("nothing was written to get there", h.writes.isEmpty())
    }

    @Test
    fun `the AUTO preview is supplied by the composition root over the very states being rendered`() =
        runTest {
            val previews = mutableListOf<List<AnkiBackendState>>()
            val h = harness(
                preview = { states, required ->
                    previews += states
                    assertEquals(STUDY_SESSION_REQUIREMENTS, required)
                    BackendResolutionResult2.Resolved(
                        ankiDroid,
                        BackendResolutionReason.AUTO_LOCAL_PREFERRED
                    )
                }
            )
            settle()

            val auto = h.section()!!.rows.first()
            assertTrue(auto.statusLabel, auto.statusLabel.startsWith("Would use AnkiDroid"))
            assertEquals("AUTO_PREVIEW_RESOLVED", auto.statusToken)
            assertTrue("the preview saw the rendered inventory", previews.isNotEmpty())
            assertEquals(listOf(ankiDroid), previews.last().map { it.backendId })
        }

    @Test
    fun `custom-study claims are read from the registry and keyed by identity`() = runTest {
        val withRegistry = harness()
        settle()
        val row = withRegistry.section()!!.configuredInstanceRows.single()
        assertNotNull("a wired adapter's static claim is disclosed", row.customStudy)
        assertEquals("CUSTOM_STUDY_NOT_IMPLEMENTED", row.customStudy!!.token)

        val withoutRegistry = harness(withRegistry = false)
        settle()
        assertNull(
            "no registry, no invented claim",
            withoutRegistry.section()!!.configuredInstanceRows.single().customStudy
        )
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-11 / 24

    @Test
    fun `a write the authoritative store echoes back is reported as saved`() = runTest {
        val h = harness(writerBehavior = { target ->
            preference.value = target
            true
        })
        settle()

        h.controller.select(BackendPreference.Explicit(ankiDroid))
        settle()

        val feedback = h.controller.saveFeedback.value
        assertTrue("expected Saved, was $feedback", feedback is AnkiBackendSaveFeedbackUi.Saved)
        assertEquals("explicit:ankidroid_local", (feedback as AnkiBackendSaveFeedbackUi.Saved).targetToken)
        assertEquals(listOf<BackendPreference>(BackendPreference.Explicit(ankiDroid)), h.writes)
        assertEquals("ankidroid_local", h.section()!!.selectedKey)
        // No active session, so no switch-effect notice either.
        assertNull(h.section()!!.switchEffectNotice)
    }

    @Test
    fun `a refused write is reported as failed and the selection does not move`() = runTest {
        val h = harness(writerBehavior = { false }, writeErrorToken = "backend_preference_invalid")
        settle()

        h.controller.select(BackendPreference.Explicit(ankiDroid))
        settle()

        val feedback = h.controller.saveFeedback.value as AnkiBackendSaveFeedbackUi.Failed
        assertEquals("backend_preference_invalid", feedback.errorToken)
        assertEquals("explicit:ankidroid_local", feedback.targetToken)
        assertTrue(feedback.message.contains("not saved"))
        assertEquals("a refusal must not move the selection", "preference:auto", h.section()!!.selectedKey)
        assertEquals(BackendPreference.Auto, h.preference.value)
        assertNull(h.section()!!.switchEffectNotice)
    }

    @Test
    fun `a refusal with no error token falls back to the documented default token`() = runTest {
        val h = harness(writerBehavior = { false }, writeErrorToken = null)
        settle()

        h.controller.select(BackendPreference.Auto)
        settle()

        assertEquals(
            AnkiBackendSelectionController.DEFAULT_WRITE_ERROR_TOKEN,
            (h.controller.saveFeedback.value as AnkiBackendSaveFeedbackUi.Failed).errorToken
        )
    }

    @Test
    fun `an accepted write the store never echoes is unconfirmed, never saved`() = runTest {
        val h = harness(writerBehavior = { true }, confirmationTimeoutMs = 5_000L)
        settle()

        h.controller.select(BackendPreference.Explicit(pcHome))
        // Advance the test clock past the confirmation window: the store never echoes, so the honest
        // answer is Unconfirmed rather than Saved.
        settle(pastConfirmationMs = 6_000)

        val feedback = h.controller.saveFeedback.value
        assertTrue("expected Unconfirmed, was $feedback", feedback is AnkiBackendSaveFeedbackUi.Unconfirmed)
        assertEquals(
            "explicit:pc_agent:home",
            (feedback as AnkiBackendSaveFeedbackUi.Unconfirmed).targetToken
        )
        assertTrue(feedback.message.isNotBlank())
        assertEquals("preference:auto", h.section()!!.selectedKey)
        assertNull(h.section()!!.switchEffectNotice)
    }

    @Test
    fun `a write that throws is a refusal and only the exception class name is logged`() = runTest {
        val h = harness(writerBehavior = {
            throw IllegalStateException("content://com.ichi2.anki/key-9f3a could not be opened")
        })
        settle()

        h.controller.select(BackendPreference.Explicit(ankiDroid))
        settle()

        assertTrue(h.controller.saveFeedback.value is AnkiBackendSaveFeedbackUi.Failed)
        assertEquals(1, h.logs.size)
        assertTrue(h.logs.single(), h.logs.single().contains("IllegalStateException"))
        assertFalse("no exception message may be logged", h.logs.single().contains("content://"))
        assertFalse(h.logs.single().contains("key-9f3a"))
        assertEquals("preference:auto", h.section()!!.selectedKey)
    }

    @Test
    fun `a superseded write never publishes its late result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var firstAccepted = true
        val h = harness(writerBehavior = { target ->
            if (target == BackendPreference.Explicit(pcHome)) {
                gate.await()
                firstAccepted
            } else {
                preference.value = target
                true
            }
        })
        settle()

        h.controller.select(BackendPreference.Explicit(pcHome))
        runCurrent()
        assertEquals(
            "the first write is in flight",
            AnkiBackendSaveFeedbackUi.Saving("explicit:pc_agent:home"),
            h.controller.saveFeedback.value
        )

        h.controller.select(BackendPreference.Explicit(ankiDroid))
        settle()
        val second = h.controller.saveFeedback.value
        assertTrue("the newer write is saved, was $second", second is AnkiBackendSaveFeedbackUi.Saved)

        // The superseded write now finishes and *refuses*: its result must be dropped, not published.
        firstAccepted = false
        gate.complete(Unit)
        settle()

        assertEquals(
            "the stale refusal must not overwrite the newer decision",
            second,
            h.controller.saveFeedback.value
        )
        assertEquals("ankidroid_local", h.section()!!.selectedKey)
    }

    @Test
    fun `AUTO to AnkiDroid to AUTO converges on the authoritative store`() = runTest {
        val h = harness(writerBehavior = { target ->
            preference.value = target
            true
        })
        settle()

        h.controller.select(BackendPreference.Explicit(ankiDroid))
        settle()
        assertTrue(h.controller.saveFeedback.value is AnkiBackendSaveFeedbackUi.Saved)
        assertEquals("ankidroid_local", h.section()!!.selectedKey)

        // The sequence the checkpoint pins: the second choice must be written after the first, and
        // the rendered selection must follow the store rather than either intent.
        h.controller.select(BackendPreference.Auto)
        settle()

        assertEquals(BackendPreference.Auto, h.preference.value)
        assertEquals("preference:auto", h.section()!!.selectedKey)
        assertEquals(
            listOf(BackendPreference.Explicit(ankiDroid), BackendPreference.Auto),
            h.writes
        )
        val feedback = h.controller.saveFeedback.value as AnkiBackendSaveFeedbackUi.Saved
        assertEquals("auto", feedback.targetToken)
    }

    @Test
    fun `a selection with no writer changes nothing at all`() = runTest {
        val h = harness(writerBehavior = null)
        settle()

        h.controller.select(BackendPreference.Explicit(ankiDroid))
        settle()

        assertEquals(AnkiBackendSaveFeedbackUi.Idle, h.controller.saveFeedback.value)
        assertTrue(h.writes.isEmpty())
        assertEquals("preference:auto", h.section()!!.selectedKey)
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-21

    @Test
    fun `refresh probes each configured instance once, read-only, and reports completion`() = runTest {
        val first = backend(ankiDroid)
        val second = backend(pcHome)
        val h = harness(backends = listOf(second, first))
        settle()

        h.controller.refreshStatus()
        settle()

        val state = h.controller.refreshState.value as AnkiBackendRefreshUi.Completed
        assertEquals(AnkiBackendRefreshUi.REQUESTED, state.resultToken)
        assertEquals(listOf("refreshAvailability"), first.calls)
        assertEquals(listOf("refreshAvailability"), second.calls)
        assertEquals(
            "the section reports the re-check",
            AnkiBackendRefreshUi.REQUESTED,
            (h.section()!!.refresh as AnkiBackendRefreshUi.Completed).resultToken
        )
    }

    @Test
    fun `refresh without a registry says the mechanism is unavailable instead of pretending`() =
        runTest {
            val h = harness(withRegistry = false)
            settle()

            h.controller.refreshStatus()
            settle()

            val state = h.controller.refreshState.value as AnkiBackendRefreshUi.Failed
            assertEquals(AnkiBackendRefreshUi.UNAVAILABLE, state.errorToken)
        }

    @Test
    fun `a backend removed while its probe ran is reported as absent, never as a sibling's state`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val removed = backend(pcHome, onRefresh = { gate.await() })
            val surviving = backend(ankiDroid)
            val h = harness(backends = listOf(removed, surviving))
            settle()

            h.controller.refreshStatus()
            runCurrent()
            assertEquals(AnkiBackendRefreshUi.Running(null), h.controller.refreshState.value)

            h.registry.remove(pcHome)
            gate.complete(Unit)
            settle()

            val state = h.controller.refreshState.value as AnkiBackendRefreshUi.Completed
            assertEquals(AnkiBackendRefreshUi.NOT_REGISTERED, state.resultToken)
            assertEquals(listOf("refreshAvailability"), surviving.calls)
            assertTrue(h.section()!!.configuredInstanceRows.none { it.backendId == pcHome })
        }

    @Test
    fun `a failing probe is reported as a failure and never as a success`() = runTest {
        val failing = backend(ankiDroid, onRefresh = { throw RuntimeException("binder died") })
        val h = harness(backends = listOf(failing))
        settle()

        h.controller.refreshStatus()
        settle()

        val state = h.controller.refreshState.value as AnkiBackendRefreshUi.Failed
        assertEquals(AnkiBackendRefreshUi.FAILED, state.errorToken)
        assertEquals(1, h.logs.size)
        assertTrue(h.logs.single().contains("RuntimeException"))
        assertFalse("no exception message may be logged", h.logs.single().contains("binder died"))
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-13

    @Test
    fun `a preference change never rewrites an active session's owner`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        locks.locked(lock(ankiDroid, "study-1"))
        val h = harness(
            backends = listOf(backend(ankiDroid), backend(pcHome)),
            sessionLocks = locks,
            writerBehavior = { target ->
                preference.value = target
                true
            }
        )
        settle()

        val before = h.section()!!.activeSession as ActiveStudySessionUi.One
        assertEquals(ankiDroid, before.backendId)

        h.controller.select(BackendPreference.Explicit(pcHome))
        settle()

        val section = h.section()!!
        val after = section.activeSession as ActiveStudySessionUi.One
        assertEquals("the session keeps the backend it started on", ankiDroid, after.backendId)
        assertEquals("study-1", after.studySessionId)
        assertEquals("pc_agent:home", section.selectedKey)
        // The switch-effect notice appears exactly here: a confirmed write plus a really active session.
        assertNotNull(section.switchEffectNotice)
        assertTrue(section.switchEffectNotice!!.contains("remains connected to its original"))
        // The lock itself was never touched.
        assertEquals(ankiDroid, locks.lockedBackendFor("study-1"))
        assertEquals(mapOf("study-1" to lock(ankiDroid, "study-1")), locks.locks.value)
    }

    @Test
    fun `an unconfirmed write with an active session claims no switch effect`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        locks.locked(lock(ankiDroid, "study-1"))
        val h = harness(
            sessionLocks = locks,
            writerBehavior = { true }
        )
        settle()

        h.controller.select(BackendPreference.Explicit(pcHome))
        settle(pastConfirmationMs = 6_000)

        val section = h.section()!!
        assertTrue(section.saveFeedback is AnkiBackendSaveFeedbackUi.Unconfirmed)
        assertNull(section.switchEffectNotice)
        assertEquals(ankiDroid, (section.activeSession as ActiveStudySessionUi.One).backendId)
    }

    @Test
    fun `the controller reads the lock tracker and can never write it`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        locks.locked(lock(pcHome, "study-9"))
        val h = harness(sessionLocks = locks, backends = listOf(backend(pcHome)))
        settle()

        h.controller.select(BackendPreference.Auto)
        h.controller.refreshStatus()
        settle()

        // The only writable surface the controller holds is the preference writer, and it was not given.
        assertTrue(h.writes.isEmpty())
        assertEquals(mapOf("study-9" to lock(pcHome, "study-9")), locks.locks.value)
        assertEquals(pcHome, (h.section()!!.activeSession as ActiveStudySessionUi.One).backendId)
    }

    @Test
    fun `nothing is probed or written while the section is merely projected`() = runTest {
        val first = backend(ankiDroid)
        val second = backend(pcHome)
        val h = harness(
            backends = listOf(first, second),
            writerBehavior = { true },
            preview = { _, _ -> BackendResolutionResult2.NoBackendConfigured(considered = emptySet()) }
        )
        settle()
        assertNotNull(h.section())
        val previewsAfterFirstEmission = h.previewCalls

        // Re-running the scheduler with no new emission must not probe, write or re-resolve.
        repeat(3) { settle() }
        h.section()

        assertEquals("no extra preview for an unchanged inventory", previewsAfterFirstEmission, h.previewCalls)
        assertTrue("no probe from projection", first.calls.isEmpty())
        assertTrue("no probe from projection", second.calls.isEmpty())
        assertTrue("no write from projection", h.writes.isEmpty())
    }
}
