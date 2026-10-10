package com.studyagent.client.ui

import com.studyagent.client.core.anki.*
import com.studyagent.client.ui.components.anki.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21 — backend selection and context-indication presentation rules
 * (IMPLEMENTATION-21-18/19, DECISION-21-36/37).
 *
 * Two product rules are pinned here because they are exactly the ones a "helpful" UI would quietly
 * break: a manual choice of an unavailable backend stays selectable (so the user gets a blocked
 * operation, not a silent substitute — INV-21-10), and the Study indicator reports the *lock*, never
 * the preference (INV-21-08).
 */
class Gate21BackendSelectionUiTest {

    private val localId = AnkiBackendId.AnkiDroidLocal
    private val pcId = AnkiBackendId.PcAgent("desktop")

    private fun entry(
        id: AnkiBackendId,
        availability: AnkiAvailability,
        capabilities: AnkiCapabilities = AnkiCapabilities(review = true, deckListing = true)
    ) = AnkiBackendStatusEntry(
        descriptor = AnkiBackendDescriptor(id, availability, capabilities),
        statusToken = availability.statusCode,
        unavailabilityToken = (availability as? AnkiAvailability.Ready)?.let { null } ?: availability.statusCode
    )

    private fun snapshot(
        preferred: AnkiBackendMode,
        vararg entries: AnkiBackendStatusEntry
    ) = AnkiBackendStatusSnapshot(preferred, entries.toList())

    @Test
    fun `every mode stays selectable even when nothing answers`() {
        val ui = buildAnkiBackendSelectionUi(
            snapshot(
                AnkiBackendMode.AUTO,
                entry(localId, AnkiAvailability.PermissionRequired()),
                entry(pcId, AnkiAvailability.AgentDisconnected)
            )
        )
        // Three modes, all present: the selector never hides a backend the user might still want.
        assertEquals(
            listOf(AnkiBackendMode.AUTO, AnkiBackendMode.ANKIDROID_LOCAL, AnkiBackendMode.PC_AGENT),
            ui.options.map { it.mode }
        )
        val local = ui.options.first { it.mode == AnkiBackendMode.ANKIDROID_LOCAL }
        assertTrue("the reason must be explained: ${local.statusLabel}", local.statusLabel.isNotBlank())
        assertTrue("permission loss must be named", local.detail!!.contains("granted"))
    }

    @Test
    fun `a missing backend says it is not in this build rather than implying it is broken`() {
        val ui = buildAnkiBackendSelectionUi(snapshot(AnkiBackendMode.AUTO, entry(localId, AnkiAvailability.Ready(AnkiCapabilities(review = true)))))
        val pc = ui.options.first { it.mode == AnkiBackendMode.PC_AGENT }
        assertEquals("Not available in this build", pc.statusLabel)
    }

    @Test
    fun `automatic explains what it would pick without promising merged data`() {
        val ui = buildAnkiBackendSelectionUi(
            snapshot(
                AnkiBackendMode.AUTO,
                entry(localId, AnkiAvailability.Ready(AnkiCapabilities(review = true))),
                entry(pcId, AnkiAvailability.Ready(AnkiCapabilities(review = true)))
            ),
            resolution = BackendResolutionResult.Resolved(localId, BackendSelectionReason.AUTO_LOCAL_PREFERRED)
        )
        val auto = ui.options.first { it.mode == AnkiBackendMode.AUTO }
        assertEquals("Would use AnkiDroid", auto.statusLabel)
        // DECISION-21-05/VER-21-27 — hybrid application, never a hybrid dataset.
        assertTrue(auto.detail!!.contains("Never merges"))
        assertTrue(ui.switchEffectNotice.contains("active session"))
    }

    @Test
    fun `several ready PC profiles ask the user to choose instead of picking one`() {
        val ui = buildAnkiBackendSelectionUi(
            snapshot(
                AnkiBackendMode.AUTO,
                entry(pcId, AnkiAvailability.Ready(AnkiCapabilities(review = true))),
                entry(AnkiBackendId.PcAgent("work"), AnkiAvailability.Ready(AnkiCapabilities(review = true)))
            ),
            resolution = BackendResolutionResult.Ambiguous(
                candidates = setOf(pcId, AnkiBackendId.PcAgent("work")),
                considered = setOf(pcId, AnkiBackendId.PcAgent("work")),
                error = AnkiError.InvalidRequest("ambiguous_backend")
            )
        )
        val auto = ui.options.first { it.mode == AnkiBackendMode.AUTO }
        assertTrue(auto.statusLabel.startsWith("Choose a PC profile"))
        val pc = ui.options.first { it.mode == AnkiBackendMode.PC_AGENT }
        assertEquals("2 ready", pc.statusLabel)
    }

    @Test
    fun `a failed resolution still renders an honest sentence`() {
        val ui = buildAnkiBackendSelectionUi(
            snapshot(AnkiBackendMode.AUTO),
            resolution = BackendResolutionResult.NoBackendAvailable(
                considered = emptySet(),
                error = AnkiError.BackendUnavailable()
            )
        )
        assertEquals("No Anki backend is registered", ui.options.first().statusLabel)
    }

    // ------------------------------------------------------------- IMPLEMENTATION-21-19

    @Test
    fun `the study indicator reports the locked backend, not the preference`() {
        // The user prefers PC, the session is locked to AnkiDroid: the indicator must say AnkiDroid.
        assertEquals("Using AnkiDroid", lockedBackendIndicator(localId))
        // A non-default profile is named, so two PC collections are never shown as the same one.
        assertEquals("Using PC Agent (desktop)", lockedBackendIndicator(pcId))
        assertEquals("Using PC Agent", lockedBackendIndicator(AnkiBackendId.PcAgent("default")))
        assertEquals("Using PC Agent (work)", lockedBackendIndicator(AnkiBackendId.PcAgent("work")))
        assertEquals("No Anki backend bound", lockedBackendIndicator(null))
    }

    @Test
    fun `a display label is never an identity`() {
        // Two different identities render differently, and the identity stays distinct.
        assertEquals("PC Agent (desktop)", pcId.displayLabel())
        assertEquals("PC Agent (work)", AnkiBackendId.PcAgent("work").displayLabel())
        assertEquals("PC Agent", AnkiBackendId.PcAgent("default").displayLabel())
        assertFalse(pcId == AnkiBackendId.PcAgent("work"))
        assertEquals("Test backend", AnkiBackendId.Fake("harness").displayLabel())
        assertNull(AnkiBackendId.fromStableId("not-a-backend"))
        assertEquals(pcId, AnkiBackendId.fromStableId(pcId.stableId))
    }

    @Test
    fun `mode titles are distinct and none of them claims to merge backends`() {
        val titles = AnkiBackendMode.entries.map { it.displayTitle() }
        assertEquals(3, titles.distinct().size)
        assertFalse(AnkiBackendMode.AUTO.displayTitle().contains("All"))
    }
}
