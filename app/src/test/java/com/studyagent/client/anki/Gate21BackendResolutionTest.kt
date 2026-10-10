package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21 PART III — VER-21-01 … VER-21-06 and VER-21-26: backend resolution.
 *
 * The rule under test is DECISION-21-06/07/09/10/26: a *preference* is not a *resolved backend*, AUTO
 * is deterministic and only ever runs before a context binds, a required capability is checked before
 * binding, and an explicit manual choice is honoured honestly — an unavailable manual backend blocks
 * rather than being silently replaced (INV-21-10/11).
 */
class Gate21BackendResolutionTest {

    private val localId = AnkiBackendId.AnkiDroidLocal
    private val pcId = AnkiBackendId.PcAgent("desktop")
    private val otherPcId = AnkiBackendId.PcAgent("workstation")

    private fun ready(id: AnkiBackendId, caps: AnkiCapabilities = reviewCaps()) = FakeAnkiBackend(
        id = id,
        initialCapabilities = caps,
        initialAvailability = AnkiAvailability.Ready(caps)
    )

    private fun reviewCaps(extra: AnkiCapabilities.() -> AnkiCapabilities = { this }) =
        AnkiCapabilities(review = true, deckListing = true).extra()

    private fun descriptors(vararg backends: AnkiBackend): List<AnkiBackendDescriptor> =
        backends.map(AnkiBackendDescriptor::of)

    private fun resolve(
        preference: AnkiBackendMode,
        required: Set<AnkiRequiredCapability> = emptySet(),
        vararg backends: AnkiBackend
    ): BackendResolutionResult = AnkiBackendResolver.resolve(
        BackendResolutionRequest(preference, required, purpose = "test"),
        descriptors(*backends)
    )

    // ------------------------------------------------------------------ VER-21-01 / VER-21-02

    @Test
    fun `manual AnkiDroid selection resolves AnkiDroid when it can serve the operation`() {
        val result = resolve(
            AnkiBackendMode.ANKIDROID_LOCAL,
            setOf(AnkiRequiredCapability.DECKS),
            ready(localId), ready(pcId)
        )
        assertEquals(
            BackendResolutionResult.Resolved(localId, BackendSelectionReason.ONLY_CANDIDATE),
            result
        )
    }

    @Test
    fun `manual PC selection resolves PC even though AnkiDroid is also ready`() {
        val result = resolve(
            AnkiBackendMode.PC_AGENT,
            setOf(AnkiRequiredCapability.DECKS),
            ready(localId), ready(pcId)
        )
        assertTrue(result is BackendResolutionResult.Resolved)
        assertEquals(pcId, (result as BackendResolutionResult.Resolved).backendId)
    }

    // -------------------------------------------------------------------------- VER-21-03

    @Test
    fun `manual selection of an unavailable backend blocks instead of falling back`() {
        val unavailableLocal = FakeAnkiBackend(
            id = localId,
            initialAvailability = AnkiAvailability.PermissionRequired()
        )
        val result = resolve(
            AnkiBackendMode.ANKIDROID_LOCAL,
            setOf(AnkiRequiredCapability.DECKS),
            unavailableLocal, ready(pcId)
        )
        // The ready PC backend must NOT be substituted: the user pinned AnkiDroid.
        assertTrue("expected a failure, got $result", result is BackendResolutionResult.Failure)
        val failure = result as BackendResolutionResult.Failure
        assertTrue(failure.error is AnkiError.PermissionRequired)
        assertEquals(setOf(localId), failure.considered)
    }

    @Test
    fun `manual PC selection with no PC registered is not implemented, not redirected`() {
        val result = resolve(AnkiBackendMode.PC_AGENT, emptySet(), ready(localId))
        assertTrue(result is BackendResolutionResult.NoBackendAvailable)
        assertTrue((result as BackendResolutionResult.NoBackendAvailable).considered.isEmpty())
    }

    // -------------------------------------------------------------------------- VER-21-04

    @Test
    fun `AUTO with both available prefers the on-device collection`() {
        val result = resolve(AnkiBackendMode.AUTO, emptySet(), ready(pcId), ready(localId))
        assertEquals(
            BackendResolutionResult.Resolved(localId, BackendSelectionReason.AUTO_LOCAL_PREFERRED),
            result
        )
    }

    // -------------------------------------------------------------------------- VER-21-05

    @Test
    fun `AUTO falls back to a single ready remote profile before binding only`() {
        val downLocal = FakeAnkiBackend(
            id = localId,
            initialAvailability = AnkiAvailability.TemporarilyUnavailable()
        )
        val result = resolve(AnkiBackendMode.AUTO, emptySet(), downLocal, ready(pcId))
        assertEquals(
            BackendResolutionResult.Resolved(pcId, BackendSelectionReason.AUTO_SINGLE_REMOTE),
            result
        )
    }

    // -------------------------------------------------------------------------- VER-21-06

    @Test
    fun `a required capability removes a backend that is reachable but cannot serve it`() {
        // Local is reachable and can list decks, but has no browser; the PC backend has one.
        val localNoBrowser = FakeAnkiBackend(
            id = localId,
            initialCapabilities = AnkiCapabilities(deckListing = true, review = true),
            initialAvailability = AnkiAvailability.Ready(
                AnkiCapabilities(deckListing = true, review = true)
            )
        )
        val browserCaps = AnkiCapabilities(
            deckListing = true,
            review = true,
            cardBrowser = AnkiCardBrowserCapabilities(canBrowseAllCards = true)
        )
        val pcWithBrowser = FakeAnkiBackend(
            id = pcId,
            initialCapabilities = browserCaps,
            initialAvailability = AnkiAvailability.Ready(browserCaps)
        )

        val browserResult = resolve(
            AnkiBackendMode.AUTO,
            setOf(AnkiRequiredCapability.CARD_BROWSER),
            localNoBrowser, pcWithBrowser
        )
        assertEquals(pcId, (browserResult as BackendResolutionResult.Resolved).backendId)

        // …and asking for something *neither* can do is a typed capability refusal, not a guess.
        val noneResult = resolve(
            AnkiBackendMode.AUTO,
            setOf(AnkiRequiredCapability.STORE_MEDIA),
            localNoBrowser, pcWithBrowser
        )
        assertTrue(noneResult is BackendResolutionResult.RequiredCapabilityUnavailable)
        val missing = (noneResult as BackendResolutionResult.RequiredCapabilityUnavailable).missing
        assertEquals(
            setOf(AnkiRequiredCapability.STORE_MEDIA),
            missing.getValue(localId)
        )
    }

    @Test
    fun `capability resolution never inspects a backend name`() {
        // Same capability, different identity: the answer must follow the capability, not the kind.
        val caps = AnkiCapabilities(editNoteFields = true)
        val local = FakeAnkiBackend(localId, initialCapabilities = caps, initialAvailability = AnkiAvailability.Ready(caps))
        val pc = FakeAnkiBackend(pcId, initialCapabilities = caps, initialAvailability = AnkiAvailability.Ready(caps))
        assertTrue(
            resolve(AnkiBackendMode.PC_AGENT, setOf(AnkiRequiredCapability.EDIT_NOTE_FIELDS), local, pc)
                is BackendResolutionResult.Resolved
        )
        assertTrue(
            resolve(AnkiBackendMode.ANKIDROID_LOCAL, setOf(AnkiRequiredCapability.EDIT_NOTE_FIELDS), local, pc)
                is BackendResolutionResult.Resolved
        )
    }

    // -------------------------------------------------------------------------- VER-21-26

    @Test
    fun `AUTO is deterministic for identical inputs`() {
        val local = ready(localId)
        val pc = ready(pcId)
        val request = BackendResolutionRequest(AnkiBackendMode.AUTO, setOf(AnkiRequiredCapability.REVIEW))
        val first = AnkiBackendResolver.resolve(request, descriptors(local, pc))
        repeat(500) {
            assertEquals(first, AnkiBackendResolver.resolve(request, descriptors(local, pc)))
            // Registry order must not matter either.
            assertEquals(first, AnkiBackendResolver.resolve(request, descriptors(pc, local)))
        }
    }

    @Test
    fun `several ready remote profiles stay a user decision in both directions`() {
        val a = resolve(AnkiBackendMode.AUTO, emptySet(), ready(pcId), ready(otherPcId))
        val b = resolve(AnkiBackendMode.AUTO, emptySet(), ready(otherPcId), ready(pcId))
        assertEquals(a, b)
        assertEquals(setOf(pcId, otherPcId), (a as BackendResolutionResult.Ambiguous).candidates)
    }

    @Test
    fun `resolution is pure - it never probes, refreshes or rewrites the preference`() {
        val local = ready(localId)
        val decksBefore = local.getDecksCalls
        val selectedBefore = local.getSelectedDeckCalls
        repeat(50) { resolve(AnkiBackendMode.AUTO, emptySet(), local) }
        assertEquals(decksBefore, local.getDecksCalls)
        assertEquals(selectedBefore, local.getSelectedDeckCalls)
        // The availability projection is read, never written.
        assertTrue(local.availability.value is AnkiAvailability.Ready)
    }

    @Test
    fun `a fake identity can never be selected through a real mode`() {
        val fake = ready(AnkiBackendId.Fake("harness"))
        assertTrue(resolve(AnkiBackendMode.AUTO, emptySet(), fake) is BackendResolutionResult.NoBackendAvailable)
        assertTrue(
            resolve(AnkiBackendMode.ANKIDROID_LOCAL, emptySet(), fake)
                is BackendResolutionResult.NoBackendAvailable
        )
        assertFalse(AnkiBackendMode.PC_AGENT.accepts(AnkiBackendId.Fake("harness")))
    }

    @Test
    fun `every failure explains itself with a typed error and the identities it considered`() {
        val noBackend = resolve(AnkiBackendMode.AUTO)
        assertTrue(noBackend is BackendResolutionResult.NoBackendAvailable)

        val downLocal = FakeAnkiBackend(localId, initialAvailability = AnkiAvailability.CollectionNotInitialized)
        val notReady = resolve(AnkiBackendMode.ANKIDROID_LOCAL, emptySet(), downLocal)
        val failure = notReady as BackendResolutionResult.Failure
        assertTrue(failure.error is AnkiError.CollectionUnavailable)
        assertEquals(setOf(localId), failure.considered)
    }
}
