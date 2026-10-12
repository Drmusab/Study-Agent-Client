package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendIdentityRefusal
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendRefresh
import com.studyagent.client.core.anki.AnkiBackendRegistration
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendRemoval
import com.studyagent.client.core.anki.AnkiBackendStateLookup
import com.studyagent.client.core.anki.AnkiTransactionRecoveryPlanner
import com.studyagent.client.core.anki.BackendTransactionBlockReason
import com.studyagent.client.core.anki.BackendTransactionRoute
import com.studyagent.client.core.anki.kind
import com.studyagent.client.core.anki.registrationRefusal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GATE 21D — registry membership, exact lookup, identity and registration lifecycle
 * (VER-21D-01 … 07 and 25; CHECKPOINTS 21D-07/08/09; AUDIT-21D-02/03/04).
 *
 * The registry here is the **one** pre-existing `AnkiBackendRegistry`, extended — not a new one. The
 * backends are strict recording doubles, so "registration did not construct, probe or call anything"
 * is an observable fact (`calls` stays empty) rather than a hope.
 */
class Gate21dRegistryMembershipTest {

    private fun probe(id: AnkiBackendId, availability: AnkiAvailability = AnkiAvailability.Checking) =
        ProbeOnlyBackend(id, availability)

    // ------------------------------------------------------------------------- VER-21D-01 / 02

    @Test
    fun `configured instances appear once and lookup returns exactly those objects`() {
        val local = probe(Gate21dIds.local)
        val a = probe(Gate21dIds.pcA)
        val b = probe(Gate21dIds.pcB)

        val registry = AnkiBackendRegistry(listOf(local, a, b))

        assertEquals(setOf(Gate21dIds.local, Gate21dIds.pcA, Gate21dIds.pcB), registry.ids)
        assertEquals(3, registry.registered.size)
        assertSame(local, registry.find(Gate21dIds.local))
        assertSame(a, registry.find(Gate21dIds.pcA))
        assertSame(b, registry.find(Gate21dIds.pcB))
        // The registry holds references it was given: it constructed, started and called nothing.
        assertEquals(emptyList<String>(), local.calls + a.calls + b.calls)
    }

    @Test
    fun `the registry detaches the list it was built from`() {
        val local = probe(Gate21dIds.local)
        val source = mutableListOf<AnkiBackend>(local)
        val registry = AnkiBackendRegistry(source)
        source.clear()
        assertSame(local, registry.find(Gate21dIds.local))
        // …and what it hands out cannot be used to change it.
        val handedOut = registry.registered
        assertEquals(1, handedOut.size)
        assertEquals(1, registry.registered.size)
    }

    // ------------------------------------------------------------------------- VER-21D-03 / 25

    @Test
    fun `an unregistered identity has no state, and no sibling answers for it`() = runTest {
        val a = probe(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val local = probe(Gate21dIds.local, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val registry = AnkiBackendRegistry(listOf(a, local))
        val missing = AnkiBackendId.PcAgent("not-configured")

        assertNull(registry.find(missing))
        assertEquals(AnkiBackendStateLookup.NotRegistered(missing), registry.stateOf(missing))
        assertEquals(AnkiBackendRefresh.NotRegistered(missing), registry.refresh(missing))
        assertEquals(AnkiBackendRemoval.NotRegistered(missing), registry.remove(missing))
        // Ready siblings of the same kind (and of another kind) were neither probed nor substituted.
        assertEquals(emptyList<String>(), a.calls + local.calls)
    }

    @Test
    fun `absent and unavailable are different answers`() {
        val down = probe(Gate21dIds.pcA, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(down))

        val registered = registry.stateOf(Gate21dIds.pcA)
        val absent = registry.stateOf(Gate21dIds.pcB)

        assertTrue("a held-but-down backend has a state", registered is AnkiBackendStateLookup.Registered)
        assertTrue("an unheld one has none", absent is AnkiBackendStateLookup.NotRegistered)
        assertNotEquals(registered::class, absent::class)
    }

    // ------------------------------------------------------------------------- VER-21D-04

    @Test
    fun `two instances of one kind stay separate registry entries`() {
        val a = probe(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val b = probe(Gate21dIds.pcB, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(a, b))

        val states = registry.states()

        assertEquals(2, states.size)
        assertEquals(AnkiBackendKind.PC_AGENT, states[0].kind)
        assertEquals(states[0].kind, states[1].kind)
        assertNotEquals(states[0].backendId, states[1].backendId)
        // Same kind, different instance: the two never share a state.
        assertTrue((registry.stateOf(Gate21dIds.pcA) as AnkiBackendStateLookup.Registered).state.descriptor.isReachable)
        assertFalse((registry.stateOf(Gate21dIds.pcB) as AnkiBackendStateLookup.Registered).state.descriptor.isReachable)
        assertSame(a, registry.find(Gate21dIds.pcA))
        assertSame(b, registry.find(Gate21dIds.pcB))
    }

    // ------------------------------------------------------------------------- VER-21D-05

    @Test
    fun `a second registration under a held identity never replaces the first`() {
        val first = probe(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val impostor = probe(Gate21dIds.pcA, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(first))

        val result = registry.register(impostor)

        assertEquals(AnkiBackendRegistration.DuplicateIdentity(Gate21dIds.pcA), result)
        assertSame("the original stays exactly where it was", first, registry.find(Gate21dIds.pcA))
        assertEquals(listOf<AnkiBackend>(first), registry.registered)
    }

    @Test
    fun `registering the very same instance again is idempotent and changes nothing`() {
        val a = probe(Gate21dIds.pcA)
        val registry = AnkiBackendRegistry(listOf(a))
        val before = registry.members.value

        val result = registry.register(a)

        assertEquals(AnkiBackendRegistration.AlreadyRegistered(Gate21dIds.pcA), result)
        assertSame("no new membership snapshot was published", before, registry.members.value)
    }

    @Test
    fun `duplicate identities at construction still fail closed`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AnkiBackendRegistry(listOf(probe(Gate21dIds.pcA), probe(Gate21dIds.pcA)))
        }
        assertEquals("Duplicate logical backend identity", error.message)
    }

    // ------------------------------------------------------------------------- VER-21D-06

    @Test
    fun `availability changes never change the identity or the instance`() {
        val a = probe(Gate21dIds.pcA)
        val registry = AnkiBackendRegistry(listOf(a))

        val seen = listOf(
            AnkiAvailability.Checking,
            AnkiAvailability.AgentAuthenticationRequired,
            AnkiAvailability.AgentAnkiUnavailable,
            AnkiAvailability.Ready(Gate21dCaps.REVIEW),
            AnkiAvailability.AgentDisconnected
        ).map { availability ->
            a.publish(availability)
            (registry.stateOf(Gate21dIds.pcA) as AnkiBackendStateLookup.Registered).state.also {
                assertSame(a, registry.find(Gate21dIds.pcA))
                assertEquals(Gate21dIds.pcA, it.backendId)
                assertEquals(AnkiBackendKind.PC_AGENT, it.kind)
            }
        }

        assertEquals("every observation really was a different availability", 5, seen.map { it.availability }.distinct().size)
        assertEquals(setOf(Gate21dIds.pcA), registry.ids)
    }

    // ------------------------------------------------------------------------- VER-21D-07

    @Test
    fun `removal changes membership only and leaves the others untouched`() {
        val a = probe(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val b = probe(Gate21dIds.pcB, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val registry = AnkiBackendRegistry(listOf(a, b))
        val bBefore = registry.stateOf(Gate21dIds.pcB)

        assertEquals(AnkiBackendRemoval.Removed(Gate21dIds.pcA), registry.remove(Gate21dIds.pcA))

        assertNull(registry.find(Gate21dIds.pcA))
        assertEquals(AnkiBackendStateLookup.NotRegistered(Gate21dIds.pcA), registry.stateOf(Gate21dIds.pcA))
        assertEquals(setOf(Gate21dIds.pcB), registry.ids)
        assertEquals("the sibling's state is unchanged", bBefore, registry.stateOf(Gate21dIds.pcB))
        assertSame(b, registry.find(Gate21dIds.pcB))
        // Removing is not a backend lifecycle event: nothing was called on the removed instance.
        assertEquals(emptyList<String>(), a.calls)
        assertEquals(AnkiBackendRemoval.NotRegistered(Gate21dIds.pcA), registry.remove(Gate21dIds.pcA))
    }

    @Test
    fun `a removed backend's durable owner is blocked not reassigned, and returns with the same identity`() {
        val a = probe(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val b = probe(Gate21dIds.pcB, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val registry = AnkiBackendRegistry(listOf(a, b))
        val planner = AnkiTransactionRecoveryPlanner(registry)
        // The owner a durable record carries is a plain identity value; the registry cannot touch it.
        val recordOwner = AnkiBackendId.fromStableId(Gate21dIds.pcA.stableId)!!

        val routed = planner.plan(listOf(recordOwner))[recordOwner]
        assertTrue("owner registered and ready: routed to exactly that instance", routed is BackendTransactionRoute.Routed)
        assertSame(a, (routed as BackendTransactionRoute.Routed).backend)

        registry.remove(Gate21dIds.pcA)

        val blocked = planner.plan(listOf(recordOwner))[recordOwner]
        assertEquals(
            "the owner is gone: blocked, and *not* handed to the ready sibling",
            BackendTransactionRoute.Blocked(recordOwner, BackendTransactionBlockReason.BACKEND_NOT_REGISTERED),
            blocked
        )
        assertEquals(Gate21dIds.pcA, recordOwner)

        // Re-registering the same configured identity is the same logical owner again (routing resumes
        // for it); proving the *collection* is the same one is not the registry's claim to make.
        assertEquals(AnkiBackendRegistration.Registered(Gate21dIds.pcA), registry.register(a))
        assertSame(a, (planner.plan(listOf(recordOwner))[recordOwner] as BackendTransactionRoute.Routed).backend)
    }

    // ------------------------------------------------------------------------- identity gate

    @Test
    fun `the registry refuses an identity that cannot be persisted safely`() {
        val whitespace = listOf(
            AnkiBackendId.PcAgent("desktop "),
            AnkiBackendId.PcAgent(" desktop"),
            AnkiBackendId.PcAgent("  desktop-a  "),
            AnkiBackendId.Fake(" x")
        )
        whitespace.forEach {
            assertEquals("$it", AnkiBackendIdentityRefusal.NOT_NORMALIZED, it.registrationRefusal())
        }

        val connectionFacts = listOf(
            AnkiBackendId.PcAgent("https://user:secret@desk.example:8765"),
            AnkiBackendId.PcAgent("ws://10.0.0.5:8765"),
            AnkiBackendId.PcAgent("192.168.1.20")
        )
        connectionFacts.forEach {
            assertEquals("$it", AnkiBackendIdentityRefusal.CONNECTION_FACT_AS_DISCRIMINATOR, it.registrationRefusal())
        }
    }

    @Test
    fun `the registry accepts every legitimate identity shape`() {
        listOf(
            AnkiBackendId.AnkiDroidLocal,
            AnkiBackendId.PcAgent("desktop-a"),
            AnkiBackendId.PcAgent("User 1"),
            // The composed `<agent>:<profile>` discriminator 21B explicitly left open must stay valid,
            // including a numeric profile: a `host:port` look-alike is the allocator's duty (A4), not a
            // reason to refuse a legitimate composed identity.
            AnkiBackendId.PcAgent("agent-7:1"),
            AnkiBackendId.PcAgent("agent-7:العربية"),
            AnkiBackendId.Fake("x")
        ).forEach { assertNull("$it", it.registrationRefusal()) }
    }

    @Test
    fun `a refused identity is reported by token and never registered`() {
        val registry = AnkiBackendRegistry(emptyList())
        val bad = probe(AnkiBackendId.PcAgent("https://user:secret@host"))

        val result = registry.register(bad)

        assertEquals(
            AnkiBackendRegistration.IdentityRefused(bad.id, AnkiBackendIdentityRefusal.CONNECTION_FACT_AS_DISCRIMINATOR),
            result
        )
        assertEquals(emptySet<AnkiBackendId>(), registry.ids)
        // The token is fixed text: it cannot echo the identity (and the credential inside it).
        val token = (result as AnkiBackendRegistration.IdentityRefused).refusal.token
        assertFalse(token.contains("secret"))
        assertFalse(token.contains("host"))
    }

    @Test
    fun `construction fails closed on an unsafe identity instead of holding it`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AnkiBackendRegistry(listOf(probe(AnkiBackendId.PcAgent("10.0.0.5"))))
        }
        assertEquals("Backend identity refused: connection_fact_as_discriminator", error.message)
    }

    // ------------------------------------------------------------------------- ordering is not priority

    @Test
    fun `registration order is preserved for enumeration but never decides anything`() {
        val local = probe(Gate21dIds.local)
        val a = probe(Gate21dIds.pcA)
        val b = probe(Gate21dIds.pcB)

        val forward = AnkiBackendRegistry(listOf(b, local, a))
        val reverse = AnkiBackendRegistry(listOf(a, local, b))

        assertEquals("enumeration follows the registration history", listOf<AnkiBackend>(b, local, a), forward.registered)
        // …but the published states are identity-ordered, so the same set always reads the same way.
        assertEquals(
            listOf(Gate21dIds.local, Gate21dIds.pcA, Gate21dIds.pcB),
            forward.states().map { it.backendId }
        )
        assertEquals(forward.states().map { it.backendId }, reverse.states().map { it.backendId })
    }

    // ------------------------------------------------------------------------- reads never probe

    @Test
    fun `reading the registry never probes or calls a backend`() {
        val a = probe(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW))
        val registry = AnkiBackendRegistry(listOf(a))

        registry.find(Gate21dIds.pcA)
        registry.ids
        registry.registered
        registry.descriptors()
        registry.states()
        registry.stateOf(Gate21dIds.pcA)
        registry.stateOf(Gate21dIds.pcB)

        assertEquals(emptyList<String>(), a.calls)
    }

    // ------------------------------------------------------------------------- concurrency

    @Test
    fun `concurrent claims on one identity admit exactly one instance`() {
        val registry = AnkiBackendRegistry(emptyList())
        val contenders = 16
        val pool = Executors.newFixedThreadPool(contenders)
        val start = CountDownLatch(1)
        val done = CountDownLatch(contenders)
        val registered = AtomicInteger()
        val duplicates = AtomicInteger()
        val other = AtomicInteger()
        try {
            repeat(contenders) {
                pool.execute {
                    start.await()
                    when (registry.register(probe(Gate21dIds.pcA))) {
                        is AnkiBackendRegistration.Registered -> registered.incrementAndGet()
                        is AnkiBackendRegistration.DuplicateIdentity -> duplicates.incrementAndGet()
                        else -> other.incrementAndGet()
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals("exactly one registration wins", 1, registered.get())
        assertEquals(contenders - 1, duplicates.get())
        assertEquals(0, other.get())
        assertEquals(setOf(Gate21dIds.pcA), registry.ids)
    }

    @Test
    fun `concurrent registration of distinct identities loses none`() {
        val registry = AnkiBackendRegistry(emptyList())
        val ids = (1..24).map { AnkiBackendId.PcAgent("pc-$it") }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(ids.size)
        try {
            ids.forEach { id ->
                pool.execute {
                    start.await()
                    registry.register(probe(id))
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals("no lost update under contention", ids.toSet(), registry.ids)
    }

    @Test
    fun `kind is never the instance identity`() {
        val a = AnkiBackendId.PcAgent("pc-a")
        val b = AnkiBackendId.PcAgent("pc-b")
        assertEquals(a.kind, b.kind)
        assertNotEquals(a, b)
        assertNotEquals(a.stableId, b.stableId)
    }
}
