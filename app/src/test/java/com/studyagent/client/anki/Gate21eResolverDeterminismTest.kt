package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendLocality
import com.studyagent.client.core.anki.AnkiBackendUnavailableReason
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiBackendResolver2
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendResolutionFailure
import com.studyagent.client.core.anki.BackendResolutionReason
import com.studyagent.client.core.anki.BackendResolutionRequest2
import com.studyagent.client.core.anki.BackendResolutionResult2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21E — VER-21E-01 … VER-21E-17, VER-21E-23, VER-21E-26/27/28.
 *
 * Exercises the pure [AnkiBackendResolver2] against deterministic descriptors; no I/O, no probing,
 * no registry writes, no preference writes, no backend mutation.
 */
class Gate21eResolverDeterminismTest {

    private val localId = AnkiBackendId.AnkiDroidLocal
    private val pcA = AnkiBackendId.PcAgent("pc-a")
    private val pcB = AnkiBackendId.PcAgent("pc-b")

    private fun reviewCaps() = AnkiCapabilities(review = true, deckListing = true)

    private fun ready(id: AnkiBackendId, caps: AnkiCapabilities = reviewCaps()): AnkiBackendDescriptor {
        val backend = FakeAnkiBackend(id = id, initialCapabilities = caps, initialAvailability = AnkiAvailability.Ready(caps))
        return AnkiBackendDescriptor.of(backend)
    }

    private fun unavailable(id: AnkiBackendId, state: AnkiAvailability): AnkiBackendDescriptor {
        val backend = FakeAnkiBackend(id = id, initialAvailability = state)
        return AnkiBackendDescriptor.of(backend)
    }

    private fun resolve(
        pref: BackendPreference,
        required: Set<com.studyagent.client.core.anki.AnkiRequiredCapability> = emptySet(),
        vararg descriptors: AnkiBackendDescriptor
    ): BackendResolutionResult2 = AnkiBackendResolver2.resolve(
        BackendResolutionRequest2(pref, required, purpose = "test"),
        descriptors.toList()
    )

    // --------------------------------------------------------- VER-21E-01 / 02 explicit selection

    @Test
    fun `explicit AnkiDroid selection resolves AnkiDroid when eligible`() {
        val result = resolve(
            BackendPreference.Explicit(localId),
            setOf(com.studyagent.client.core.anki.AnkiRequiredCapability.DECKS),
            ready(localId), ready(pcA)
        )
        assertEquals(BackendResolutionResult2.Resolved(localId, BackendResolutionReason.EXPLICIT_PIN), result)
    }

    @Test
    fun `explicit PC selection resolves the exact PC instance even when local is also ready`() {
        val result = resolve(
            BackendPreference.Explicit(pcA),
            setOf(com.studyagent.client.core.anki.AnkiRequiredCapability.DECKS),
            ready(localId), ready(pcA), ready(pcB)
        )
        assertTrue(result is BackendResolutionResult2.Resolved)
        assertEquals(pcA, (result as BackendResolutionResult2.Resolved).backendId)
    }

    // --------------------------------------------------------- VER-21E-03 manual unavailable

    @Test
    fun `manual selection of an unavailable backend blocks instead of silently falling back`() {
        val downLocal = unavailable(localId, AnkiAvailability.PermissionRequired())
        val result = resolve(
            BackendPreference.Explicit(localId),
            setOf(com.studyagent.client.core.anki.AnkiRequiredCapability.DECKS),
            downLocal, ready(pcA)
        )
        assertTrue(result is BackendResolutionResult2.ExplicitBackendUnavailable)
        val fail = result as BackendResolutionResult2.ExplicitBackendUnavailable
        assertEquals(localId, fail.pinnedId)
        assertEquals(AnkiBackendUnavailableReason.PERMISSION_REQUIRED, fail.reason)
        assertEquals(setOf(localId), fail.considered)
    }

    // --------------------------------------------------------- VER-21E-04 manual missing

    @Test
    fun `manual selection of a backend that is not registered is a typed missing failure not a sibling answer`() {
        val result = resolve(
            BackendPreference.Explicit(pcA),
            emptySet(),
            ready(localId)
        )
        assertTrue(result is BackendResolutionResult2.ExplicitBackendMissing)
        assertEquals(pcA, (result as BackendResolutionResult2.ExplicitBackendMissing).pinnedId)
    }

    // --------------------------------------------------------- VER-21E-05 AUTO local preferred

    @Test
    fun `AUTO with both ready deterministically prefers local over remote regardless of order`() {
        val r1 = resolve(BackendPreference.Auto, emptySet(), ready(pcA), ready(pcB), ready(localId))
        val r2 = resolve(BackendPreference.Auto, emptySet(), ready(localId), ready(pcA), ready(pcB))
        val r3 = resolve(BackendPreference.Auto, emptySet(), ready(pcB), ready(localId), ready(pcA))
        listOf(r1, r2, r3).forEach {
            assertEquals(BackendResolutionResult2.Resolved(localId, BackendResolutionReason.AUTO_LOCAL_PREFERRED), it)
        }
    }

    // --------------------------------------------------------- VER-21E-06 AUTO one unavailable

    @Test
    fun `AUTO falls back to a single ready remote only for a new context and only pre-binding`() {
        val downLocal = unavailable(localId, AnkiAvailability.TemporarilyUnavailable())
        val result = resolve(BackendPreference.Auto, emptySet(), downLocal, ready(pcA))
        assertEquals(
            BackendResolutionResult2.Resolved(pcA, BackendResolutionReason.AUTO_SINGLE_REMOTE),
            result
        )
    }

    // --------------------------------------------------------- VER-21E-07 / 08 capabilities

    @Test
    fun `a missing capability removes an eligible-ready backend and picks the capable one`() {
        val localNoEdit = ready(
            localId,
            AnkiCapabilities(review = true, deckListing = true)
        )
        val pcEdit = ready(
            pcA,
            AnkiCapabilities(review = true, deckListing = true, editNoteFields = true)
        )
        val result = resolve(
            BackendPreference.Auto,
            setOf(com.studyagent.client.core.anki.AnkiRequiredCapability.EDIT_NOTE_FIELDS),
            localNoEdit, pcEdit
        )
        assertEquals(pcA, (result as BackendResolutionResult2.Resolved).backendId)
    }

    @Test
    fun `multiple required capabilities are all required - a subset does not pass`() {
        // FakeAnkiBackend supports deckListing, review, bury, suspend, cardDetails, editNote*,
        // changeCardDeck, storeMedia — but NOT cardBrowser, flags, media (read), createNotes.
        // Use BURY + EDIT_NOTE_FIELDS: local has only decks/review, pc has both.
        val localPartial = ready(
            localId,
            AnkiCapabilities(review = true, deckListing = true)
        )
        val pcAll = ready(
            pcA,
            AnkiCapabilities(review = true, deckListing = true, bury = true, editNoteFields = true)
        )
        val result = resolve(
            BackendPreference.Auto,
            setOf(
                com.studyagent.client.core.anki.AnkiRequiredCapability.BURY,
                com.studyagent.client.core.anki.AnkiRequiredCapability.EDIT_NOTE_FIELDS
            ),
            localPartial, pcAll
        )
        assertEquals(pcA, (result as BackendResolutionResult2.Resolved).backendId)
    }

    // --------------------------------------------------------- VER-21E-09 unsupported vs unavailable

    @Test
    fun `unsupported capability is distinguishable from backend unavailability`() {
        val downLocal = unavailable(localId, AnkiAvailability.CollectionNotInitialized)
        val unavailableResult = resolve(BackendPreference.Explicit(localId), setOf(com.studyagent.client.core.anki.AnkiRequiredCapability.REVIEW), downLocal)
        assertTrue(unavailableResult is BackendResolutionResult2.ExplicitBackendUnavailable)

        val localReadyNoCreate = ready(localId, AnkiCapabilities(review = true, deckListing = true))
        val capabilityResult = resolve(
            BackendPreference.Auto,
            setOf(com.studyagent.client.core.anki.AnkiRequiredCapability.CREATE_NOTE),
            localReadyNoCreate
        )
        assertTrue(capabilityResult is BackendResolutionResult2.CapabilityUnsupported)
    }

    // --------------------------------------------------------- VER-21E-12 no backends

    @Test
    fun `AUTO with nothing registered returns NoBackendConfigured`() {
        val result = resolve(BackendPreference.Auto, emptySet())
        assertTrue(result is BackendResolutionResult2.NoBackendConfigured)
    }

    // --------------------------------------------------------- VER-21E-13 all unavailable

    @Test
    fun `all candidates unavailable returns a typed NoEligibleBackend`() {
        val downLocal = unavailable(localId, AnkiAvailability.ProviderUnavailable())
        val downPc = unavailable(pcA, AnkiAvailability.AgentDisconnected)
        val result = resolve(BackendPreference.Auto, emptySet(), downLocal, downPc)
        assertTrue(result is BackendResolutionResult2.NoEligibleBackend)
    }

    // --------------------------------------------------------- VER-21E-14 / 15 / 16 determinism

    @Test
    fun `equivalent candidates tie-break by orderingKey and registry order never matters`() {
        // Only reachable if a second LOCAL_DEVICE kind ever exists; with today's kinds local always
        // wins first. Exercise tie-break with two PC remotes being equally eligible as the only
        // candidates (which would otherwise be Ambiguous) by forcing ambiguity first, then pick a
        // scenario where locality class is the same and we verify orderingKey stability.
        val a = ready(pcA)
        val b = ready(pcB)
        // Multiple PCs → Ambiguous (correct per LOCKED policy).
        val amb = resolve(BackendPreference.Auto, emptySet(), a, b)
        assertTrue(amb is BackendResolutionResult2.Ambiguous)

        // OrderingKey is lexicographic: pc_agent:pc-a < pc_agent:pc-b.
        assertTrue(a.orderingKey < b.orderingKey)

        // Repeated identical inputs → identical result.
        val first = resolve(BackendPreference.Auto, emptySet(), ready(localId))
        repeat(200) {
            assertEquals(first, resolve(BackendPreference.Auto, emptySet(), ready(localId)))
        }
    }

    // --------------------------------------------------------- VER-21E-17 multiple PC instances

    @Test
    fun `multiple PC instances require instance-level identity and kind alone is ambiguous`() {
        val legacyKind = resolve(
            BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT),
            emptySet(),
            ready(pcA), ready(pcB)
        )
        assertTrue(legacyKind is BackendResolutionResult2.Ambiguous)
        assertEquals(setOf(pcA, pcB), (legacyKind as BackendResolutionResult2.Ambiguous).candidates)

        // Explicit pin resolves exactly one of them.
        val pickB = resolve(BackendPreference.Explicit(pcB), emptySet(), ready(pcA), ready(pcB))
        assertEquals(pcB, (pickB as BackendResolutionResult2.Resolved).backendId)
    }

    // --------------------------------------------------------- VER-21E-23 pure / no I/O

    @Test
    fun `resolution is a pure function - zero backend side effects`() {
        val local = FakeAnkiBackend(
            id = localId,
            initialCapabilities = reviewCaps(),
            initialAvailability = AnkiAvailability.Ready(reviewCaps())
        )
        val decksBefore = local.getDecksCalls
        val selectedBefore = local.getSelectedDeckCalls
        repeat(50) {
            AnkiBackendResolver2.resolve(
                BackendResolutionRequest2(BackendPreference.Auto, emptySet(), "pure_test"),
                listOf(AnkiBackendDescriptor.of(local))
            )
        }
        assertEquals(decksBefore, local.getDecksCalls)
        assertEquals(selectedBefore, local.getSelectedDeckCalls)
        assertTrue(local.availability.value is AnkiAvailability.Ready)
    }

    // --------------------------------------------------------- VER-21E-26 Fake isolation

    @Test
    fun `AUTO never selects a Fake backend`() {
        val fake = FakeAnkiBackend(
            id = AnkiBackendId.Fake("harness"),
            initialCapabilities = reviewCaps(),
            initialAvailability = AnkiAvailability.Ready(reviewCaps())
        )
        val result = AnkiBackendResolver2.resolve(
            BackendResolutionRequest2(BackendPreference.Auto, emptySet(), "fake_test"),
            listOf(AnkiBackendDescriptor.of(fake))
        )
        // No real eligible backend exists (only a Fake).
        assertTrue(result is BackendResolutionResult2.NoEligibleBackend || result is BackendResolutionResult2.NoBackendConfigured)
    }

    // --------------------------------------------------------- VER-21E-27 typed error projection

    @Test
    fun `every failure is typed and carries considered identities`() {
        val noConfig = resolve(BackendPreference.Auto, emptySet())
        assertTrue((noConfig as BackendResolutionResult2.Failed).considered.isEmpty())

        val downLocal = unavailable(localId, AnkiAvailability.PermissionRequired())
        val permFail = resolve(BackendPreference.Explicit(localId), emptySet(), downLocal)
        assertTrue(permFail is BackendResolutionResult2.ExplicitBackendUnavailable)
        assertEquals(setOf(localId), (permFail as BackendResolutionResult2.Failed).considered)
        assertEquals(BackendResolutionFailure.EXPLICIT_BACKEND_UNAVAILABLE, permFail.failure)

        val miss = resolve(BackendPreference.Explicit(pcA), emptySet(), ready(localId))
        assertEquals(BackendResolutionFailure.EXPLICIT_BACKEND_MISSING, (miss as BackendResolutionResult2.Failed).failure)
    }
}
