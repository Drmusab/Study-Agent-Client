package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendPreferenceInvalidIssue
import com.studyagent.client.data.preferences.decodeStoredBackendPreference
import com.studyagent.client.data.preferences.toEncodedBackendPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GATE 21E — preference storage decoding, deterministic legacy migration, and safe failure. */
class Gate21ePreferenceMigrationTest {

    @Test
    fun `missing persisted mode uses the locked AUTO default`() {
        assertEquals(BackendPreference.Auto, decodeStoredBackendPreference(null, null))
    }

    @Test
    fun `unknown mode token is represented as invalid instead of silently becoming AUTO`() {
        val result = decodeStoredBackendPreference("future-or-corrupt-mode", null)
        assertEquals(
            BackendPreference.InvalidStored(BackendPreferenceInvalidIssue.UNKNOWN_MODE),
            result
        )
        assertFalse(result == BackendPreference.Auto)
        // Raw persisted text is not copied into the typed domain value or its diagnostic rendering.
        assertFalse(result.toString().contains("future-or-corrupt-mode"))
    }

    @Test
    fun `AUTO ignores an orphaned pin because AUTO is the stored policy`() {
        assertEquals(
            BackendPreference.Auto,
            decodeStoredBackendPreference(AnkiBackendMode.AUTO.stableId, "not-an-instance-id")
        )
    }

    @Test
    fun `legacy ANKIDROID_LOCAL maps to the singleton exact identity and ignores a stray pin`() {
        assertEquals(
            BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal),
            decodeStoredBackendPreference(AnkiBackendMode.ANKIDROID_LOCAL.stableId, "pc_agent:old")
        )
    }

    @Test
    fun `legacy PC_AGENT without a pin remains kind-level and cannot guess an instance`() {
        assertEquals(
            BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT),
            decodeStoredBackendPreference(AnkiBackendMode.PC_AGENT.stableId, null)
        )
    }

    @Test
    fun `PC_AGENT with a valid pin restores that exact configured identity`() {
        assertEquals(
            BackendPreference.Explicit(AnkiBackendId.PcAgent("desktop")),
            decodeStoredBackendPreference(AnkiBackendMode.PC_AGENT.stableId, "pc_agent:desktop")
        )
    }

    @Test
    fun `well-formed pin for a deleted PC profile remains explicit for missing-backend reporting`() {
        assertEquals(
            BackendPreference.Explicit(AnkiBackendId.PcAgent("removed-profile")),
            decodeStoredBackendPreference(AnkiBackendMode.PC_AGENT.stableId, "pc_agent:removed-profile")
        )
    }

    @Test
    fun `malformed pin is not downgraded to an unpinned PC kind`() {
        assertEquals(
            BackendPreference.InvalidStored(
                BackendPreferenceInvalidIssue.INVALID_INSTANCE_PIN,
                legacyMode = AnkiBackendMode.PC_AGENT
            ),
            decodeStoredBackendPreference(AnkiBackendMode.PC_AGENT.stableId, "garbage_token")
        )
    }

    @Test
    fun `pin whose identity kind contradicts PC_AGENT mode is invalid not a different backend`() {
        assertEquals(
            BackendPreference.InvalidStored(
                BackendPreferenceInvalidIssue.INSTANCE_PIN_KIND_MISMATCH,
                legacyMode = AnkiBackendMode.PC_AGENT
            ),
            decodeStoredBackendPreference(AnkiBackendMode.PC_AGENT.stableId, "ankidroid_local")
        )
    }

    @Test
    fun `valid persisted preferences round-trip through the real storage mapping`() {
        val cases = listOf(
            BackendPreference.Auto,
            BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal),
            BackendPreference.Explicit(AnkiBackendId.PcAgent("desktop")),
            BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
        )
        cases.forEach { preference ->
            val stored = preference.toEncodedBackendPreference()
            assertTrue("$preference must be writable", stored != null)
            val restored = decodeStoredBackendPreference(
                stored!!.mode.stableId,
                stored.instancePinStableId
            )
            assertEquals(preference, restored)
        }
    }

    @Test
    fun `invalid persisted values and Fake pins cannot be rewritten as AUTO`() {
        assertNull(BackendPreference.InvalidStored(BackendPreferenceInvalidIssue.UNKNOWN_MODE)
            .toEncodedBackendPreference())
        assertNull(BackendPreference.Explicit(AnkiBackendId.Fake("test"))
            .toEncodedBackendPreference())
    }
}
