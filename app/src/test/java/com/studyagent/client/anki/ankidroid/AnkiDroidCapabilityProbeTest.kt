package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiCapabilityReport
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidCapabilityProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 04 — capability probe tests (§9-§16, §69-§86).
 */
class AnkiDroidCapabilityProbeTest {

    @Test
    fun `capability probe reports deckListing implemented when ready`() = runTest {
        val probe = DefaultAnkiDroidCapabilityProbe(clock = { 0L })
        val detection = testDetection(
            availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
            providerSpec = 2,
            permissionGranted = true,
            collectionReady = true
        )

        val result = probe.probe(detection)

        assertTrue(result.implemented.deckListing)
        assertFalse(result.implemented.deckCounts)
        // GATE 06 — scheduled review is implemented; GATE 11 adds rating commit, so the full
        // review loop is claimed too (the backend masks it when no rating writer is wired).
        assertTrue(result.implemented.scheduledReview)
        assertTrue(result.implemented.reviewIntervals)
        assertTrue(result.implemented.review)
        // GATE 07 — rendered-card hydration is implemented (identity-verified, JVM-tested).
        assertTrue(result.implemented.renderedCards)
        assertFalse(result.implemented.media)
        assertFalse(result.implemented.flags)
        assertEquals(CapabilitySupport.SUPPORTED, result.apiReport.deckListing)
        assertEquals(CapabilitySupport.SUPPORTED, result.apiReport.scheduledReview)
        assertEquals(2, result.apiReport.specVersion)
    }

    @Test
    fun `capability probe returns NONE when not ready`() = runTest {
        val probe = DefaultAnkiDroidCapabilityProbe(clock = { 0L })
        val detection = testDetection(
            availability = AnkiAvailability.PermissionRequired(),
            providerSpec = 2,
            permissionGranted = false
        )

        val result = probe.probe(detection)

        assertEquals(AnkiCapabilities.NONE, result.implemented)
        // API report still shows supported because spec is known
        assertEquals(CapabilitySupport.SUPPORTED, result.apiReport.deckListing)
    }

    @Test
    fun `capability probe returns UNKNOWN when spec unknown`() = runTest {
        val probe = DefaultAnkiDroidCapabilityProbe(clock = { 0L })
        val detection = testDetection(
            availability = AnkiAvailability.NotInstalled,
            installed = false,
            providerSpec = null,
            permissionGranted = null
        )

        val result = probe.probe(detection)

        assertEquals(AnkiCapabilities.NONE, result.implemented)
        assertEquals(AnkiDroidApiCapabilityReport.UNKNOWN, result.apiReport)
    }

    @Test
    fun `capability probe returns UNSUPPORTED when spec below minimum`() = runTest {
        val probe = DefaultAnkiDroidCapabilityProbe(clock = { 0L })
        val detection = testDetection(
            availability = AnkiAvailability.Unsupported("too old"),
            providerSpec = 0,
            permissionGranted = true
        )

        val result = probe.probe(detection)

        assertEquals(AnkiCapabilities.NONE, result.implemented)
        assertEquals(CapabilitySupport.UNSUPPORTED, result.apiReport.deckListing)
    }

    @Test
    fun `capability probe is truthful - does not mark media true merely because constant exists`() = runTest {
        val probe = DefaultAnkiDroidCapabilityProbe(clock = { 0L })
        val detection = testDetection(
            availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
            providerSpec = 2
        )

        val result = probe.probe(detection)

        // API supports media, but implemented is false (GATE 04 truthfulness §11)
        assertEquals(CapabilitySupport.SUPPORTED, result.apiReport.mediaRead)
        assertFalse(result.implemented.media)
    }

    @Test
    fun `capability probe details explain reason`() = runTest {
        val probe = DefaultAnkiDroidCapabilityProbe(clock = { 0L })
        val detection = testDetection(
            availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
            providerSpec = 2
        )

        val result = probe.probe(detection)

        assertTrue(result.details.isNotEmpty())
        val deckDetail = result.details.find { it.name == "deckListing" }
        assertNotNull(deckDetail)
        assertTrue(deckDetail!!.reason.contains("implemented") || deckDetail.reason.contains("verified"))
    }

    @Test
    fun `capability probe handles unknown newer spec as supported`() = runTest {
        // Forward compatibility §51: unknown newer spec should not automatically fail
        val probe = DefaultAnkiDroidCapabilityProbe(clock = { 0L })
        val detection = testDetection(
            availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
            providerSpec = 99 // future spec
        )

        val result = probe.probe(detection)

        // Should be treated as supported, not unsupported
        assertEquals(CapabilitySupport.SUPPORTED, result.apiReport.deckListing)
        assertEquals(99, result.apiReport.specVersion)
    }
}
