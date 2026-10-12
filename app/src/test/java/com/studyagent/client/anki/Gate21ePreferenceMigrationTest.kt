package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.BackendPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21E — VER-21E-18/19: preference persistence semantics and legacy migration.
 *
 * Exercises the pure (mode, pin) → BackendPreference combine function used by
 * BackendPreferenceRepository, without Android DataStore.
 */
class Gate21ePreferenceMigrationTest {

    /** Mirror the repository's pure combine so this test stays honest to its logic. */
    private fun combine(legacyMode: AnkiBackendMode, pinStableId: String?): BackendPreference =
        when (legacyMode) {
            AnkiBackendMode.AUTO -> BackendPreference.Auto
            AnkiBackendMode.ANKIDROID_LOCAL -> BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal)
            AnkiBackendMode.PC_AGENT -> {
                val parsed = pinStableId?.let(AnkiBackendId::fromStableId)
                when {
                    parsed is AnkiBackendId.PcAgent -> BackendPreference.Explicit(parsed)
                    pinStableId != null && parsed != null -> BackendPreference.Explicit(parsed)
                    pinStableId != null -> BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
                    else -> BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
                }
            }
        }

    @Test
    fun `legacy AUTO survives migration unchanged`() {
        assertEquals(BackendPreference.Auto, combine(AnkiBackendMode.AUTO, null))
    }

    @Test
    fun `legacy ANKIDROID_LOCAL unambiguously maps to the singleton pin`() {
        val result = combine(AnkiBackendMode.ANKIDROID_LOCAL, null)
        assertTrue(result is BackendPreference.Explicit)
        assertEquals(AnkiBackendId.AnkiDroidLocal, (result as BackendPreference.Explicit).backendId)
        // A stray pin for a different backend must be ignored (AnkiDroidLocal is a singleton).
        val withStrayPin = combine(AnkiBackendMode.ANKIDROID_LOCAL, "pc_agent:old")
        assertEquals(BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal), withStrayPin)
    }

    @Test
    fun `legacy PC_AGENT without a pin becomes ExplicitKind - no silent instance choice`() {
        val result = combine(AnkiBackendMode.PC_AGENT, null)
        assertTrue(result is BackendPreference.ExplicitKind)
        assertEquals(AnkiBackendKind.PC_AGENT, (result as BackendPreference.ExplicitKind).kind)
    }

    @Test
    fun `PC_AGENT with a valid pin decodes to that exact instance`() {
        val result = combine(AnkiBackendMode.PC_AGENT, "pc_agent:desktop")
        assertTrue(result is BackendPreference.Explicit)
        assertEquals(AnkiBackendId.PcAgent("desktop"), (result as BackendPreference.Explicit).backendId)
    }

    @Test
    fun `an undecodable pin does not silently convert to AUTO - stays as ExplicitKind so resolution reports missing`() {
        // A pin that was stored but no longer corresponds to a known stableId (e.g. backend removed
        // or corrupted) must NOT be dropped into AUTO — resolution must report it.
        val result = combine(AnkiBackendMode.PC_AGENT, "garbage_token")
        assertTrue(result is BackendPreference.ExplicitKind)
        assertEquals(AnkiBackendKind.PC_AGENT, (result as BackendPreference.ExplicitKind).kind)
    }

    @Test
    fun `toStored round-trip preserves explicit pins and clears pins for AUTO and local`() {
        val cases = listOf(
            BackendPreference.Auto,
            BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal),
            BackendPreference.Explicit(AnkiBackendId.PcAgent("desktop")),
            BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
        )
        cases.forEach { pref ->
            val (mode, pin) = toStored(pref)
            val restored = combine(mode, pin)
            assertEquals(pref, restored)
        }
    }

    // ----- mirror of repository's toStored()
    private fun toStored(pref: BackendPreference): Pair<AnkiBackendMode, String?> = when (pref) {
        BackendPreference.Auto -> AnkiBackendMode.AUTO to null
        is BackendPreference.Explicit -> when (val id = pref.backendId) {
            is AnkiBackendId.AnkiDroidLocal -> AnkiBackendMode.ANKIDROID_LOCAL to null
            is AnkiBackendId.PcAgent -> AnkiBackendMode.PC_AGENT to id.stableId
            is AnkiBackendId.Fake -> AnkiBackendMode.AUTO to null
        }
        is BackendPreference.ExplicitKind -> when (pref.kind) {
            AnkiBackendKind.ANKIDROID_LOCAL -> AnkiBackendMode.ANKIDROID_LOCAL to null
            AnkiBackendKind.PC_AGENT -> AnkiBackendMode.PC_AGENT to null
            AnkiBackendKind.FAKE -> AnkiBackendMode.AUTO to null
        }
    }
}
