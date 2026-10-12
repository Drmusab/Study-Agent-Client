package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver
import com.studyagent.client.core.anki.AnkiBackendSelector
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendStateLookup
import com.studyagent.client.core.anki.AnkiBackendUnavailableReason
import com.studyagent.client.core.anki.AnkiCapabilityEvidence
import com.studyagent.client.core.anki.AnkiCapabilityReadiness
import com.studyagent.client.core.anki.AnkiCapabilityStatus
import com.studyagent.client.core.anki.AnkiImplementedSupport
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.BackendResolutionRequest
import com.studyagent.client.core.anki.BackendResolutionResult
import com.studyagent.client.core.anki.BackendSelectionReason
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.core.anki.isReadyForReview
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21D — the registry against the **real AnkiDroid adapter chain** (VER-21D-08, 09, 13, 14, 15;
 * CHECKPOINTS 21D-10 and 21D-12; AUDIT-21D-06/07/08).
 *
 * Only the Android surface is faked (package probe, permission manager, provider client). Detector,
 * health check, health probe, gateway and `AnkiDroidBackend` are the production classes, wired as
 * `AppContainer` wires them (see [Gate21dAnkiDroidChain]). That is what lets these tests prove the
 * *real* adapter's availability and capability truth — a `FakeAnkiBackend` could only ever prove what
 * the fake was told to say.
 */
class Gate21dAnkiDroidRegistryTest {

    private val local = AnkiBackendId.AnkiDroidLocal

    /** What a fully wired AnkiDroid adapter implements at the audited provider contract. */
    private val implementedWhenFullyWired = setOf(
        AnkiRequiredCapability.DECKS,
        AnkiRequiredCapability.SCHEDULED_REVIEW,
        AnkiRequiredCapability.REVIEW,
        AnkiRequiredCapability.RENDERED_CARDS,
        AnkiRequiredCapability.CARD_DETAILS,
        AnkiRequiredCapability.BURY,
        AnkiRequiredCapability.SUSPEND_CARDS,
        AnkiRequiredCapability.EDIT_NOTE_FIELDS,
        AnkiRequiredCapability.EDIT_NOTE_TAGS,
        AnkiRequiredCapability.CHANGE_CARD_DECK,
        AnkiRequiredCapability.NOTE_MODEL_LISTING,
        AnkiRequiredCapability.NOTE_MODEL_SCHEMA,
        AnkiRequiredCapability.NOTE_TEMPLATE_LISTING,
        AnkiRequiredCapability.STORE_MEDIA,
        AnkiRequiredCapability.CREATE_NOTE
    )

    /**
     * The four capabilities the audited pin does not provide. Each is refused honestly (21B product
     * decision) — not implemented here and not masked: the third-party API *may* offer some of them,
     * which is exactly why "the API could" must never be read as "the application does".
     */
    private val notImplementedAnywhere = setOf(
        AnkiRequiredCapability.DECK_COUNTS,
        AnkiRequiredCapability.CARD_BROWSER,
        AnkiRequiredCapability.FLAGS,
        AnkiRequiredCapability.MEDIA_READ
    )

    private suspend fun TestScope.observe(chain: Gate21dAnkiDroidChain): Pair<AnkiBackendRegistry, AnkiBackendState> {
        val registry = AnkiBackendRegistry(listOf(chain.backend))
        registry.refresh(local)
        runCurrent() // the backend's derived flows publish one dispatch hop after the probe returns
        val state = (registry.stateOf(local) as AnkiBackendStateLookup.Registered).state
        return registry to state
    }

    // ------------------------------------------------------------------------- VER-21D-08 / 13 / 14

    @Test
    fun `a ready fully wired AnkiDroid is READY for exactly what the application implements`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope).also { it.probe.resolves() }
        val (_, state) = observe(chain)

        assertTrue(state.availability is AnkiAvailability.Ready)
        assertEquals(null, state.unavailableReason)
        assertEquals(AnkiCapabilityEvidence.STATIC_CLAIM, state.capabilityEvidence)
        AnkiRequiredCapability.entries.forEach { capability ->
            val expected = if (capability in implementedWhenFullyWired) {
                AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.READY)
            } else {
                AnkiCapabilityStatus(AnkiImplementedSupport.UNSUPPORTED, AnkiCapabilityReadiness.NOT_READY)
            }
            assertEquals("$capability", expected, state.capability(capability))
        }
        assertEquals(
            "the pinned matrix: 15 implemented, 4 honestly refused",
            15 to 4,
            implementedWhenFullyWired.size to notImplementedAnywhere.size
        )
        assertEquals(AnkiRequiredCapability.entries.toSet(), implementedWhenFullyWired + notImplementedAnywhere)
    }

    @Test
    fun `capabilities the public API might offer are not advertised unless implemented`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope).also { it.probe.resolves() }
        val (_, state) = observe(chain)

        notImplementedAnywhere.forEach { capability ->
            val status = state.capability(capability)
            assertEquals("$capability", AnkiImplementedSupport.UNSUPPORTED, status.implemented)
            assertEquals("$capability", AnkiCapabilityReadiness.NOT_READY, status.operational)
        }
        // The adapter's own claim agrees with the live projection of a ready backend.
        val claim = chain.backend.implementedCapabilities!!
        assertFalse(claim.flags)
        assertFalse(claim.media)
        assertFalse(claim.deckCounts)
        assertFalse(claim.cardBrowser.canBrowseAllCards || claim.cardBrowser.canBrowseDeck)
        assertFalse("the coarse legacy flag is never a substitute for the audited trio", claim.editNotes)
    }

    @Test
    fun `an AnkiDroid without its writers claims only what it can honour`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope, fullyWired = false).also { it.probe.resolves() }
        val (_, state) = observe(chain)

        val readOnly = setOf(
            AnkiRequiredCapability.DECKS,
            AnkiRequiredCapability.SCHEDULED_REVIEW,
            AnkiRequiredCapability.RENDERED_CARDS
        )
        AnkiRequiredCapability.entries.forEach { capability ->
            val status = state.capability(capability)
            if (capability in readOnly) {
                assertEquals("$capability", AnkiCapabilityReadiness.READY, status.operational)
            } else {
                assertEquals("$capability is not wired, so it is not claimed", AnkiImplementedSupport.UNSUPPORTED, status.implemented)
                assertEquals("$capability", AnkiCapabilityReadiness.NOT_READY, status.operational)
            }
        }
        assertFalse(state.descriptor.isReadyForReview)
    }

    // ------------------------------------------------------------------------- the defect found by 21D

    /**
     * Regression for **D-21D-01**, found while verifying VER-21D-08 against the real chain.
     *
     * The detector deliberately publishes `Ready(NONE)` ("reachability, not review"), the capability
     * probe fills the *live* projection later, and nothing reconciled the two. The descriptor requires
     * both to agree, so a Ready, fully wired AnkiDroid was refused by the resolver with
     * `RequiredCapabilityUnavailable(REVIEW)`. Before the fix this test observed exactly that.
     */
    @Test
    fun `a ready AnkiDroid is eligible for a study session through the existing resolver`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope).also { it.probe.resolves() }
        val (registry, state) = observe(chain)

        assertTrue(state.descriptor.isReachable)
        assertTrue("review-ready: Ready snapshot and live projection agree", state.descriptor.isReadyForReview)
        assertTrue(state.descriptor.supports(AnkiRequiredCapability.REVIEW))

        val resolved = AnkiBackendResolver(registry)
            .resolve(BackendResolutionRequest(AnkiBackendMode.AUTO, STUDY_SESSION_REQUIREMENTS, "gate21d"))
        assertEquals(
            BackendResolutionResult.Resolved(local, BackendSelectionReason.AUTO_LOCAL_PREFERRED),
            resolved
        )
        assertEquals(
            AnkiBackendSelector.Resolution.Resolved(local),
            AnkiBackendSelector(registry).resolve(AnkiBackendMode.ANKIDROID_LOCAL)
        )
    }

    @Test
    fun `the ready snapshot never claims more than the live projection`() = runTest {
        listOf(true, false).forEach { fullyWired ->
            val chain = Gate21dAnkiDroidChain(backgroundScope, fullyWired = fullyWired).also { it.probe.resolves() }
            observe(chain)

            val ready = chain.backend.availability.value as AnkiAvailability.Ready
            assertEquals("fullyWired=$fullyWired: the two projections agree exactly", chain.backend.capabilities.value, ready.capabilities)
            assertEquals(
                "an unwired rating gateway can never make a backend review-ready",
                fullyWired,
                ready.capabilities.review
            )
        }
    }

    @Test
    fun `a backend that is not ready is passed through untouched and is not review-ready`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope)
        chain.probe.resolves()
        chain.permissions.granted = false
        val (_, state) = observe(chain)

        assertTrue(state.availability is AnkiAvailability.PermissionRequired)
        assertFalse(state.availability.isReadyForReview)
        assertFalse(state.descriptor.isReachable)
    }

    // ------------------------------------------------------------------------- VER-21D-09 / 15

    @Test
    fun `a missing permission is accurate, and implemented is not ready`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope)
        chain.probe.resolves()
        chain.permissions.granted = false
        val (_, state) = observe(chain)

        assertEquals(AnkiBackendUnavailableReason.PERMISSION_REQUIRED, state.unavailableReason)
        AnkiRequiredCapability.entries.forEach {
            assertFalse("$it must not be ready without the permission", state.capability(it).operational == AnkiCapabilityReadiness.READY)
        }
        // The application *implements* review and creation; that did not change because a permission is
        // missing — and it is not the same fact as being able to use them right now.
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.NOT_READY),
            state.capability(AnkiRequiredCapability.REVIEW)
        )
        assertEquals(
            AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.NOT_READY),
            state.capability(AnkiRequiredCapability.CREATE_NOTE)
        )
    }

    // ------------------------------------------------------------------------- CHECKPOINT 21D-02 (AnkiDroid)

    @Test
    fun `every AnkiDroid availability condition is distinguished and none is collapsed`() = runTest {
        data class Case(val name: String, val expected: AnkiBackendUnavailableReason?, val arrange: Gate21dAnkiDroidChain.() -> Unit)

        val cases = listOf(
            Case("app not installed", AnkiBackendUnavailableReason.APP_NOT_INSTALLED) { },
            Case("provider unavailable", AnkiBackendUnavailableReason.PROVIDER_UNAVAILABLE) {
                probe.installedPackages += endpoints.first().expectedPackage
            },
            Case("permission missing", AnkiBackendUnavailableReason.PERMISSION_REQUIRED) {
                probe.resolves()
                permissions.granted = false
            },
            Case("collection unavailable", AnkiBackendUnavailableReason.COLLECTION_UNAVAILABLE) {
                probe.resolves()
                probe.failWith(IllegalStateException("AnkiDroid's storage is not yet configured"))
            },
            Case("API unsupported", AnkiBackendUnavailableReason.VERSION_INCOMPATIBLE) {
                probe.resolves(spec = 0)
            },
            Case("ready", null) { probe.resolves() }
        )

        val reasons = cases.map { case ->
            val chain = Gate21dAnkiDroidChain(backgroundScope).also { case.arrange(it) }
            val (_, state) = observe(chain)
            assertEquals(case.name, case.expected, state.unavailableReason)
            state.unavailableReason
        }
        assertEquals("six conditions, six distinct answers", 6, reasons.toSet().size)
    }

    // ------------------------------------------------------------------------- VER-21D-21 / 23

    @Test
    fun `checking AnkiDroid through the registry performs no provider write`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope).also { it.probe.resolves() }
        val (registry, _) = observe(chain)
        registry.refresh(local)
        runCurrent()
        registry.states().forEach { it.diagnostics() }

        assertEquals("no update (rating, bury, suspend, note edit, deck move)", emptyList<Any>(), chain.providerClient.updateLog)
        assertEquals("no insert (note creation, media storage)", emptyList<Any>(), chain.providerClient.insertLog)
    }

    @Test
    fun `AnkiDroid diagnostics carry no provider text`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope)
        chain.probe.installedPackages += chain.endpoints.first().expectedPackage
        val (_, state) = observe(chain)

        val text = state.diagnostics().toString()
        assertEquals(AnkiBackendUnavailableReason.PROVIDER_UNAVAILABLE, state.unavailableReason)
        listOf("com.ichi2", "flashcards", "content://").forEach {
            assertFalse("diagnostics leaked `$it`: $text", text.contains(it))
        }
    }

    @Test
    fun `the real adapter keeps its stable identity and kind through every state change`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope)
        val registry = AnkiBackendRegistry(listOf(chain.backend))
        val seen = mutableSetOf<AnkiBackendId>()

        listOf<() -> Unit>(
            { chain.permissions.granted = false; chain.probe.resolves() },
            { chain.permissions.granted = true },
            { chain.probe.factsByAuthority.clear() }
        ).forEach { arrange ->
            arrange()
            registry.refresh(local)
            runCurrent()
            seen += (registry.stateOf(local) as AnkiBackendStateLookup.Registered).state.backendId
        }

        assertEquals(setOf(local), seen)
        assertEquals("ankidroid_local", local.stableId)
    }
}
