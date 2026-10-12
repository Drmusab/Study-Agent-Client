package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendRetryability
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendUnavailableReason
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCapabilityEvidence
import com.studyagent.client.core.anki.AnkiCapabilityReadiness
import com.studyagent.client.core.anki.AnkiCapabilityStatus
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiImplementedSupport
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.isReadyForReview
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.core.anki.unavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21D — what the registry can honestly say about one backend at one instant: the failure
 * taxonomy, and the two **separate** capability dimensions (implemented vs operational)
 * (VER-21D-09/11/12/13/14/15/16/23; CHECKPOINTS 21D-02…06 and 21D-13).
 */
class Gate21dBackendStateTest {

    private val req = AnkiRequiredCapability.REVIEW
    private val create = AnkiRequiredCapability.CREATE_NOTE

    /** One instance of **every** availability state, each carrying a payload that must never leak. */
    private val everyAvailability: List<AnkiAvailability> = listOf(
        AnkiAvailability.Checking,
        AnkiAvailability.NotInstalled,
        AnkiAvailability.ProviderUnavailable("http://user:pw@10.0.0.5:8765/?token=abc"),
        AnkiAvailability.PermissionRequired("secret-permission-detail"),
        AnkiAvailability.CollectionNotInitialized,
        AnkiAvailability.Ready(Gate21dCaps.REVIEW),
        AnkiAvailability.TemporarilyUnavailable("secret-temporary-reason"),
        AnkiAvailability.AgentDisconnected,
        AnkiAvailability.AgentAuthenticationRequired,
        AnkiAvailability.AgentAnkiUnavailable,
        AnkiAvailability.Unsupported("secret-unsupported-reason"),
        AnkiAvailability.Fault(AnkiError.Unknown(cause = "secret-fault-cause"))
    )

    private fun state(
        availability: AnkiAvailability,
        live: AnkiCapabilities = (availability as? AnkiAvailability.Ready)?.capabilities ?: AnkiCapabilities.NONE,
        claim: AnkiCapabilities? = null,
        id: AnkiBackendId = Gate21dIds.pcA
    ) = AnkiBackendState(AnkiBackendDescriptor(id, availability, live), claim)

    // ------------------------------------------------------------------------- taxonomy (21D-02/03)

    @Test
    fun `every availability maps to exactly one reason, or to none when there is nothing to explain`() {
        val expected = listOf(
            null,
            AnkiBackendUnavailableReason.APP_NOT_INSTALLED,
            AnkiBackendUnavailableReason.PROVIDER_UNAVAILABLE,
            AnkiBackendUnavailableReason.PERMISSION_REQUIRED,
            AnkiBackendUnavailableReason.COLLECTION_UNAVAILABLE,
            null,
            AnkiBackendUnavailableReason.TEMPORARILY_UNAVAILABLE,
            AnkiBackendUnavailableReason.TRANSPORT_UNAVAILABLE,
            AnkiBackendUnavailableReason.AUTHENTICATION_REQUIRED,
            AnkiBackendUnavailableReason.ANKI_UNAVAILABLE,
            AnkiBackendUnavailableReason.VERSION_INCOMPATIBLE,
            AnkiBackendUnavailableReason.BACKEND_ERROR
        )
        assertEquals(expected.size, everyAvailability.size)
        everyAvailability.forEachIndexed { index, availability ->
            assertEquals(availability.statusCode, expected[index], availability.unavailableReason())
        }
        // Checking is *not verified yet*, never a failure; Ready has nothing to explain.
        assertNull(AnkiAvailability.Checking.unavailableReason())
        assertNull(AnkiAvailability.Ready(AnkiCapabilities.NONE).unavailableReason())
    }

    @Test
    fun `the status vocabulary stays unique and the new PC state has its own token`() {
        val tokens = everyAvailability.map { it.statusCode }
        assertEquals("every state keeps a distinct stable status token", tokens.size, tokens.toSet().size)
        assertEquals("AGENT_AUTHENTICATION_REQUIRED", AnkiAvailability.AgentAuthenticationRequired.statusCode)
        // Every reason category is produced by some state: nothing in the taxonomy is speculative.
        val produced = everyAvailability.mapNotNull { it.unavailableReason() }.toSet()
        assertEquals(AnkiBackendUnavailableReason.entries.toSet(), produced)
    }

    @Test
    fun `an unclassified failure is never claimed retryable, and user-fixable ones say so`() {
        assertEquals(AnkiBackendRetryability.UNKNOWN, AnkiBackendUnavailableReason.BACKEND_ERROR.retryability)
        assertEquals(
            AnkiBackendRetryability.EXPECTED_TO_RECOVER,
            AnkiBackendUnavailableReason.TEMPORARILY_UNAVAILABLE.retryability
        )
        // A lost transport *may* come back; nothing guarantees it.
        assertEquals(AnkiBackendRetryability.MAY_RECOVER, AnkiBackendUnavailableReason.TRANSPORT_UNAVAILABLE.retryability)
        listOf(
            AnkiBackendUnavailableReason.APP_NOT_INSTALLED,
            AnkiBackendUnavailableReason.PROVIDER_UNAVAILABLE,
            AnkiBackendUnavailableReason.PERMISSION_REQUIRED,
            AnkiBackendUnavailableReason.COLLECTION_UNAVAILABLE,
            AnkiBackendUnavailableReason.AUTHENTICATION_REQUIRED,
            AnkiBackendUnavailableReason.ANKI_UNAVAILABLE,
            AnkiBackendUnavailableReason.VERSION_INCOMPATIBLE
        ).forEach {
            assertEquals("$it cannot be fixed by waiting", AnkiBackendRetryability.AFTER_USER_ACTION, it.retryability)
        }
    }

    // ------------------------------------------------------------------------- VER-21D-09 / 10 / 11

    @Test
    fun `a missing permission is accurate and is never reported as ready`() {
        val s = state(AnkiAvailability.PermissionRequired(), claim = Gate21dCaps.WITH_CREATE, id = Gate21dIds.local)

        assertEquals(AnkiBackendUnavailableReason.PERMISSION_REQUIRED, s.unavailableReason)
        assertFalse(s.descriptor.isReachable)
        AnkiRequiredCapability.entries.forEach {
            assertNotEquals("$it", AnkiCapabilityReadiness.READY, s.capability(it).operational)
        }
    }

    @Test
    fun `authentication is distinct from permission, transport and Anki readiness`() {
        val auth = AnkiAvailability.AgentAuthenticationRequired
        val others = listOf(
            AnkiAvailability.PermissionRequired(),
            AnkiAvailability.AgentDisconnected,
            AnkiAvailability.AgentAnkiUnavailable
        )

        assertEquals(AnkiBackendUnavailableReason.AUTHENTICATION_REQUIRED, auth.unavailableReason())
        others.forEach {
            assertNotEquals(it.statusCode, auth.statusCode)
            assertNotEquals(it.unavailableReason(), auth.unavailableReason())
        }
        assertFalse("not ready", state(auth).descriptor.isReachable)
        assertFalse(auth.isReadyForReview)
    }

    // ------------------------------------------------------------------------- VER-21D-13 / 14 / 15

    @Test
    fun `implemented support and operational readiness are separate facts`() {
        // Implemented (a static claim) but the service is not reachable: SUPPORTED + NOT_READY.
        val implementedButDown = state(
            AnkiAvailability.AgentAnkiUnavailable,
            claim = Gate21dCaps.WITH_CREATE,
            id = Gate21dIds.pcA
        )
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.NOT_READY),
            implementedButDown.capability(create)
        )

        // Not implemented by the application at all, though the backend is up: UNSUPPORTED + NOT_READY.
        val upButNotImplemented = state(
            AnkiAvailability.Ready(Gate21dCaps.REVIEW.copy(createNotes = true)),
            live = Gate21dCaps.REVIEW.copy(createNotes = true),
            claim = Gate21dCaps.REVIEW // the application never wired creation
        )
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.UNSUPPORTED, AnkiCapabilityReadiness.NOT_READY),
            upButNotImplemented.capability(create)
        )

        // Implemented and up and agreed: the only way to READY.
        val ready = state(AnkiAvailability.Ready(Gate21dCaps.REVIEW), claim = Gate21dCaps.REVIEW)
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.READY),
            ready.capability(req)
        )
        assertEquals(
            "a capability nobody claims is unsupported even while everything is up",
            AnkiCapabilityStatus(AnkiImplementedSupport.UNSUPPORTED, AnkiCapabilityReadiness.NOT_READY),
            ready.capability(create)
        )
    }

    @Test
    fun `a checking backend with a static claim keeps its claim and verifies nothing`() {
        val s = state(AnkiAvailability.Checking, claim = Gate21dCaps.REVIEW)
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.UNKNOWN),
            s.capability(req)
        )
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.UNSUPPORTED, AnkiCapabilityReadiness.UNKNOWN),
            s.capability(create)
        )
    }

    @Test
    fun `a probe can narrow a claim but never widen it`() {
        // The claim says review only; a (mistaken or optimistic) probe also reports creation.
        val widened = state(
            AnkiAvailability.Ready(Gate21dCaps.WITH_CREATE),
            live = Gate21dCaps.WITH_CREATE,
            claim = Gate21dCaps.REVIEW
        )
        assertEquals(AnkiImplementedSupport.UNSUPPORTED, widened.capability(create).implemented)
        assertEquals(AnkiCapabilityReadiness.NOT_READY, widened.capability(create).operational)

        // The claim says review; the probe (while Ready) says it is not currently available.
        val narrowed = state(
            AnkiAvailability.Ready(AnkiCapabilities.NONE),
            live = AnkiCapabilities.NONE,
            claim = Gate21dCaps.REVIEW
        )
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.NOT_READY),
            narrowed.capability(req)
        )
    }

    @Test
    fun `projections that disagree are never ready`() {
        // availability's own snapshot says nothing; the live flow says review (the transient window).
        val tornOne = state(AnkiAvailability.Ready(AnkiCapabilities.NONE), live = Gate21dCaps.REVIEW, claim = Gate21dCaps.REVIEW)
        // and the reverse
        val tornTwo = state(AnkiAvailability.Ready(Gate21dCaps.REVIEW), live = AnkiCapabilities.NONE, claim = Gate21dCaps.REVIEW)

        listOf(tornOne, tornTwo).forEach {
            assertEquals(AnkiCapabilityReadiness.NOT_READY, it.capability(req).operational)
            assertEquals(
                "the descriptor's own fail-closed rule agrees",
                false,
                it.descriptor.supports(req)
            )
        }
    }

    // ------------------------------------------------------------------------- VER-21D-14 / unknown

    @Test
    fun `with no claim, capabilities are unknown until a valid probe says otherwise`() {
        everyAvailability.filterNot { it is AnkiAvailability.Ready }.forEach { availability ->
            val s = state(availability, claim = null)
            AnkiRequiredCapability.entries.forEach { capability ->
                val status = s.capability(capability)
                assertEquals("$availability / $capability", AnkiImplementedSupport.UNKNOWN, status.implemented)
                assertNotEquals("$availability / $capability", AnkiCapabilityReadiness.READY, status.operational)
            }
            assertEquals(AnkiCapabilityEvidence.NONE, s.capabilityEvidence)
        }
    }

    @Test
    fun `an unknown capability is never promoted to supported by an unrelated state`() {
        val checking = state(AnkiAvailability.Checking, claim = null)
        AnkiRequiredCapability.entries.forEach {
            val status = checking.capability(it)
            assertEquals(AnkiImplementedSupport.UNKNOWN, status.implemented)
            assertEquals(AnkiCapabilityReadiness.UNKNOWN, status.operational)
        }
    }

    // ------------------------------------------------------------------------- VER-21D-16

    @Test
    fun `negotiated evidence is dropped, not kept, when the backend leaves ready`() {
        val negotiated = Gate21dCaps.REVIEW
        val before = state(AnkiAvailability.Ready(negotiated), claim = null)
        assertEquals(AnkiCapabilityEvidence.RUNTIME_NEGOTIATED, before.capabilityEvidence)
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.READY),
            before.capability(req)
        )

        // Disconnected: the *same live flow value* is still there, but it is no longer evidence.
        val gap = AnkiBackendState(
            AnkiBackendDescriptor(Gate21dIds.pcA, AnkiAvailability.AgentDisconnected, negotiated),
            implementedClaim = null
        )
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.UNKNOWN, AnkiCapabilityReadiness.NOT_READY),
            gap.capability(req)
        )

        // Back after a version change that negotiated *less*: the old success is not resurrected.
        val after = state(AnkiAvailability.Ready(AnkiCapabilities.NONE), claim = null)
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.UNSUPPORTED, AnkiCapabilityReadiness.NOT_READY),
            after.capability(req)
        )
    }

    @Test
    fun `a static claim survives a gap and negotiated evidence does not`() {
        val claimed = state(AnkiAvailability.AgentDisconnected, claim = Gate21dCaps.REVIEW)
        val negotiatedOnly = state(AnkiAvailability.AgentDisconnected, claim = null)
        assertEquals(AnkiImplementedSupport.SUPPORTED, claimed.capability(req).implemented)
        assertEquals(AnkiImplementedSupport.UNKNOWN, negotiatedOnly.capability(req).implemented)
        assertEquals(AnkiCapabilityEvidence.STATIC_CLAIM, claimed.capabilityEvidence)
    }

    // ------------------------------------------------------------------------- honesty invariant

    @Test
    fun `a capability can never be ready without being implemented`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnkiCapabilityStatus(AnkiImplementedSupport.UNSUPPORTED, AnkiCapabilityReadiness.READY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnkiCapabilityStatus(AnkiImplementedSupport.UNKNOWN, AnkiCapabilityReadiness.READY)
        }
        // And no reachable state ever tries: every cell of the matrix constructs.
        val claims = listOf<AnkiCapabilities?>(null, AnkiCapabilities.NONE, Gate21dCaps.REVIEW, Gate21dCaps.WITH_CREATE)
        val lives = listOf(AnkiCapabilities.NONE, Gate21dCaps.REVIEW, Gate21dCaps.WITH_CREATE)
        for (availability in everyAvailability) for (claim in claims) for (live in lives) {
            val s = state(availability, live = live, claim = claim)
            s.capabilities().forEach { (_, status) ->
                if (status.operational == AnkiCapabilityReadiness.READY) {
                    assertEquals(AnkiImplementedSupport.SUPPORTED, status.implemented)
                }
            }
        }
    }

    @Test
    fun `the capability view is total and ordered`() {
        val view = state(AnkiAvailability.Ready(Gate21dCaps.REVIEW), claim = Gate21dCaps.REVIEW).capabilities()
        assertEquals(AnkiRequiredCapability.entries, view.keys.toList())
    }

    // ------------------------------------------------------------------------- VER-21D-23

    @Test
    fun `diagnostics are tokens only and carry no payload from any availability state`() {
        everyAvailability.forEach { availability ->
            val text = state(availability, claim = Gate21dCaps.REVIEW).diagnostics().toString()
            listOf("secret", "user:pw", "10.0.0.5", "token=abc", "8765").forEach { leak ->
                assertFalse("${availability.statusCode} leaked `$leak` into diagnostics: $text", text.contains(leak))
            }
        }
    }

    @Test
    fun `diagnostics expose the stable id, the status token and the reason`() {
        val d = state(AnkiAvailability.AgentAuthenticationRequired, claim = Gate21dCaps.REVIEW).diagnostics()
        assertEquals(Gate21dIds.pcA, d.backendId)
        assertEquals("pc_agent:pc-a", d.backendId.stableId)
        assertEquals("AGENT_AUTHENTICATION_REQUIRED", d.statusToken)
        assertEquals(AnkiBackendUnavailableReason.AUTHENTICATION_REQUIRED, d.unavailableReason)
        assertEquals(AnkiCapabilityEvidence.STATIC_CLAIM, d.capabilityEvidence)
        assertEquals(AnkiRequiredCapability.entries.size, d.capabilities.size)
    }

    @Test
    fun `a state is built by reading the backend and never by calling it`() {
        val backend = ProbeOnlyBackend(
            Gate21dIds.pcA,
            AnkiAvailability.Ready(Gate21dCaps.REVIEW),
            Gate21dCaps.REVIEW,
            claim = Gate21dCaps.REVIEW
        )
        val s = AnkiBackendState.of(backend)
        s.diagnostics()
        s.capabilities()
        assertEquals(emptyList<String>(), backend.calls)
        assertEquals(AnkiCapabilityReadiness.READY, s.capability(req).operational)
        assertEquals(1, AnkiBackendRegistry(listOf(backend)).states().size)
    }
}
