package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AnkiBackendSelectorTest {
    private val localId = AnkiBackendId.AnkiDroidLocal
    private val pcId = AnkiBackendId.PcAgent("desktop")
    private fun backend(id: AnkiBackendId, ready: Boolean = true) = FakeAnkiBackend(id,
        initialAvailability = if (ready) AnkiAvailability.Ready(reviewCaps) else AnkiAvailability.TemporarilyUnavailable())
    private fun select(mode: AnkiBackendMode, vararg backends: AnkiBackend) =
        AnkiBackendSelector(AnkiBackendRegistry(backends.toList())).resolve(mode)

    @Test fun `AUTO readiness matrix is deterministic and does not mutate preference`() {
        val preference = AnkiBackendMode.AUTO
        assertEquals(AnkiBackendSelector.Resolution.Resolved(localId), select(preference, backend(pcId), backend(localId)))
        assertEquals(AnkiBackendSelector.Resolution.Resolved(pcId), select(preference, backend(localId, false), backend(pcId)))
        assertEquals(AnkiBackendSelector.Resolution.Resolved(localId), select(preference, backend(localId), backend(pcId, false)))
        assertTrue(select(preference, backend(localId, false), backend(pcId, false)) is AnkiBackendSelector.Resolution.Unavailable)
        assertEquals(AnkiBackendMode.AUTO, preference)
    }

    @Test fun `explicit unavailable local never falls back and preserves actionable error`() = runTest {
        val local = backend(localId)
        local.setAvailability(AnkiAvailability.PermissionRequired())
        val result = select(AnkiBackendMode.ANKIDROID_LOCAL, local, backend(pcId)) as AnkiBackendSelector.Resolution.Unavailable
        assertEquals(AnkiBackendSelector.UnavailableReason.EXPLICIT_BACKEND_NOT_READY, result.reason)
        assertTrue(result.error is AnkiError.PermissionRequired)
    }

    @Test fun `explicit PC never falls back to ready local`() {
        assertTrue(select(AnkiBackendMode.PC_AGENT, backend(localId), backend(pcId, false))
            is AnkiBackendSelector.Resolution.Unavailable)
        assertEquals(AnkiBackendSelector.Resolution.Resolved(pcId),
            select(AnkiBackendMode.PC_AGENT, backend(localId), backend(pcId)))
    }

    @Test fun `unimplemented explicit backend is typed failure`() {
        val result = select(AnkiBackendMode.ANKIDROID_LOCAL, backend(pcId)) as AnkiBackendSelector.Resolution.Unavailable
        assertEquals(AnkiBackendSelector.UnavailableReason.EXPLICIT_BACKEND_NOT_IMPLEMENTED, result.reason)
        assertTrue(result.error is AnkiError.BackendUnavailable)
    }

    @Test fun `multiple ready PC profiles are ambiguous not registry-order-selected`() {
        val first = backend(pcId)
        val second = backend(AnkiBackendId.PcAgent("second"))
        val a = select(AnkiBackendMode.AUTO, first, second)
        val b = select(AnkiBackendMode.AUTO, second, first)
        assertEquals(a, b)
        assertEquals(setOf(first.id, second.id), (a as AnkiBackendSelector.Resolution.Ambiguous).candidates)
        assertTrue(select(AnkiBackendMode.PC_AGENT, first, second) is AnkiBackendSelector.Resolution.Ambiguous)
        assertEquals(AnkiBackendSelector.Resolution.Resolved(localId), select(AnkiBackendMode.AUTO, first, second, backend(localId)))
    }

    @Test fun `only ready profiles participate and unsupported optional features do not block review`() {
        assertEquals(AnkiBackendSelector.Resolution.Resolved(pcId), select(AnkiBackendMode.PC_AGENT,
            backend(pcId), backend(AnkiBackendId.PcAgent("other"), false)))
        val minimal = FakeAnkiBackend(pcId, initialCapabilities = AnkiCapabilities(review = true))
        assertEquals(AnkiBackendSelector.Resolution.Resolved(pcId), select(AnkiBackendMode.AUTO, minimal))
    }

    @Test fun `ready without review capability is not selected`() {
        val decksOnly = FakeAnkiBackend(localId, initialCapabilities = AnkiCapabilities(deckListing = true))
        val result = select(AnkiBackendMode.ANKIDROID_LOCAL, decksOnly) as AnkiBackendSelector.Resolution.Unavailable
        assertTrue(result.error is AnkiError.UnsupportedAction)
        assertEquals(AnkiBackendSelector.Resolution.Resolved(pcId), select(AnkiBackendMode.AUTO, decksOnly, backend(pcId)))
    }

    @Test fun `disagreeing capability projections fail closed`() {
        val ready = backend(localId)
        val inconsistent = object : AnkiBackend by ready {
            override val capabilities = kotlinx.coroutines.flow.MutableStateFlow(AnkiCapabilities.NONE)
        }
        assertTrue(select(AnkiBackendMode.AUTO, inconsistent) is AnkiBackendSelector.Resolution.Unavailable)
    }

    @Test fun `empty registry and fake identities cannot enable production selection`() {
        assertTrue(select(AnkiBackendMode.AUTO) is AnkiBackendSelector.Resolution.Unavailable)
        assertTrue(select(AnkiBackendMode.AUTO, backend(AnkiBackendId.Fake())) is AnkiBackendSelector.Resolution.Unavailable)
    }

    @Test fun `registry rejects duplicate logical identity and detaches caller list`() {
        val local = backend(localId)
        assertThrows(IllegalArgumentException::class.java) { AnkiBackendRegistry(listOf(local, backend(localId))) }
        val list = mutableListOf<AnkiBackend>(local)
        val registry = AnkiBackendRegistry(list)
        list.clear()
        assertSame(local, registry.find(localId))
        assertNull(registry.find(pcId))
        assertEquals(setOf(localId), registry.ids)
    }

    @Test fun `backend loss can change next resolution but cannot change bound session`() = runTest {
        val local = FakeAnkiBackend(localId, listOf(deck(localId)), listOf(card(id = localId)))
        val selector = AnkiBackendSelector(AnkiBackendRegistry(listOf(local, backend(pcId))))
        assertEquals(AnkiBackendSelector.Resolution.Resolved(localId), selector.resolve(AnkiBackendMode.AUTO))
        val session = local.begin()
        local.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        assertEquals(AnkiBackendSelector.Resolution.Resolved(pcId), selector.resolve(AnkiBackendMode.AUTO))
        assertEquals(localId, session.context.backendId)
        assertTrue(local.nextCard(session) is NextCardResult.BackendUnavailable)
    }
}
