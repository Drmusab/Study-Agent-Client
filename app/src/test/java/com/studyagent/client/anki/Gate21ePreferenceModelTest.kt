package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendPreferenceInvalidIssue
import com.studyagent.client.core.anki.toPreference
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gate21ePreferenceModelTest {

    private val localId = AnkiBackendId.AnkiDroidLocal
    private val pcA = AnkiBackendId.PcAgent("pc-a")
    private val pcB = AnkiBackendId.PcAgent("pc-b")

    @Test
    fun `AUTO is a policy, not a backend instance`() {
        val auto = BackendPreference.Auto
        // BackendPreference.Auto is a singleton data object with no id field.
        assertTrue(auto is BackendPreference.Auto)
    }

    @Test
    fun `Explicit selection identifies one configured instance, not a kind`() {
        val pinA = BackendPreference.Explicit(pcA)
        val pinB = BackendPreference.Explicit(pcB)
        assertNotEquals(pinA, pinB)
        assertEquals(pcA, pinA.backendId)
    }

    @Test
    fun `ExplicitKind for PC_AGENT is the legacy no-pin variant`() {
        val legacy = BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
        assertEquals(AnkiBackendKind.PC_AGENT, legacy.kind)
    }

    @Test
    fun `AnkiBackendMode toPreference maps legacy modes`() {
        assertEquals(BackendPreference.Auto, AnkiBackendMode.AUTO.toPreference())
        assertEquals(
            BackendPreference.Explicit(localId),
            AnkiBackendMode.ANKIDROID_LOCAL.toPreference()
        )
        assertTrue(AnkiBackendMode.PC_AGENT.toPreference() is BackendPreference.ExplicitKind)
        assertEquals(
            AnkiBackendKind.PC_AGENT,
            (AnkiBackendMode.PC_AGENT.toPreference() as BackendPreference.ExplicitKind).kind
        )
    }

    @Test
    fun `FAKE backend is never a legitimate ExplicitKind`() {
        try {
            BackendPreference.ExplicitKind(AnkiBackendKind.FAKE)
            throw AssertionError("FAKE must not be constructible as an ExplicitKind")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `BackendPreference round-trips through kotlinx serialization`() {
        val json = Json
        val cases = listOf(
            BackendPreference.Auto,
            BackendPreference.Explicit(localId),
            BackendPreference.Explicit(pcA),
            BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT),
            BackendPreference.InvalidStored(
                BackendPreferenceInvalidIssue.INVALID_INSTANCE_PIN,
                legacyMode = AnkiBackendMode.PC_AGENT
            )
        )
        cases.forEach { pref ->
            val encoded = json.encodeToString(BackendPreference.serializer(), pref)
            val decoded = json.decodeFromString(BackendPreference.serializer(), encoded)
            assertEquals(pref, decoded)
        }
    }

    @Test
    fun `Explicit pin does not carry any credential or transport detail`() {
        // The only payload is an AnkiBackendId whose stableId is `pc_agent:<profileId>` —
        // content-free and token-only.
        val pin = BackendPreference.Explicit(pcA)
        assertEquals("pc_agent:pc-a", pin.backendId.stableId)
    }
}
