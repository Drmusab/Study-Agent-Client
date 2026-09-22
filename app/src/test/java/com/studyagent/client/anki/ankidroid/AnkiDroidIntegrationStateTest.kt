package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiCapabilityReport
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCompatibilityPolicy
import com.studyagent.client.data.anki.ankidroid.AnkiDroidIntegrationState
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 04 — integration state and compatibility policy tests.
 */
class AnkiDroidIntegrationStateTest {

    @Test
    fun `initial state is Checking with NONE capabilities`() {
        val state = AnkiDroidIntegrationState.initial(0L)
        assertTrue(state.availability is AnkiAvailability.Checking)
        assertEquals(AnkiCapabilities.NONE, state.capabilities)
        assertNull(state.metadata)
    }

    @Test
    fun `compatibility policy accepts spec >= min`() {
        assertTrue(AnkiDroidCompatibilityPolicy.isSpecSupported(1))
        assertTrue(AnkiDroidCompatibilityPolicy.isSpecSupported(2))
        assertTrue(AnkiDroidCompatibilityPolicy.isSpecSupported(99)) // forward compat §51
        assertFalse(AnkiDroidCompatibilityPolicy.isSpecSupported(0))
    }

    @Test
    fun `api capabilities for spec 1 are all SUPPORTED`() {
        val report = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(1)
        assertEquals(CapabilitySupport.SUPPORTED, report.deckListing)
        assertEquals(CapabilitySupport.SUPPORTED, report.scheduledReview)
        assertEquals(CapabilitySupport.SUPPORTED, report.renderedCards)
    }

    @Test
    fun `api capabilities for null spec are UNKNOWN`() {
        val report = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(null)
        assertEquals(AnkiDroidApiCapabilityReport.UNKNOWN, report)
        assertEquals(CapabilitySupport.UNKNOWN, report.deckListing)
    }

    @Test
    fun `api capabilities for below min are UNSUPPORTED`() {
        val report = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(0)
        assertEquals(CapabilitySupport.UNSUPPORTED, report.deckListing)
    }

    @Test
    fun `implemented capabilities for GATE 04 are NONE even when ready`() {
        val caps = AnkiDroidCompatibilityPolicy.implementedCapabilitiesFor(spec = 2, isReady = true)
        assertEquals(AnkiCapabilities.NONE, caps)
    }

    @Test
    fun `implemented capabilities when not ready are NONE`() {
        val caps = AnkiDroidCompatibilityPolicy.implementedCapabilitiesFor(spec = 2, isReady = false)
        assertEquals(AnkiCapabilities.NONE, caps)
    }

    @Test
    fun `core capability set present when deckListing supported`() {
        val report = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(2)
        assertTrue(AnkiDroidCompatibilityPolicy.isCoreCapabilitySetPresent(report))
    }

    @Test
    fun `core capability set absent when spec unsupported`() {
        val report = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(0)
        assertFalse(AnkiDroidCompatibilityPolicy.isCoreCapabilitySetPresent(report))
    }

    @Test
    fun `integration state isReady reflects availability`() {
        val ready = AnkiDroidIntegrationState.initial(0L).copy(
            availability = AnkiAvailability.Ready(AnkiCapabilities.NONE)
        )
        assertTrue(ready.isReady)

        val notReady = AnkiDroidIntegrationState.initial(0L).copy(
            availability = AnkiAvailability.PermissionRequired()
        )
        assertFalse(notReady.isReady)
    }
}
