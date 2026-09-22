package com.studyagent.client.anki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyagent.client.appContainer
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoints
import com.studyagent.client.data.anki.ankidroid.AnkiDroidOpenResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProbeOutcome
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderSpec
import com.studyagent.client.data.anki.ankidroid.AndroidAnkiDroidProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 02 §106/§107 — the *optional* instrumented half of the AnkiDroid suite.
 *
 * ## Why this file is safe to run on an emulator without AnkiDroid
 *
 * These tests never require AnkiDroid to be installed and never assert that it is *ready*: an
 * emulator image has no AnkiDroid, and a gate that goes red because of that would be a false
 * alarm about the phone rather than about the code (§108). What only a device can prove is
 * asserted instead: that the real `AndroidAnkiDroidProbe` (the one file allowed to touch
 * `PackageManager`/`ContentResolver`) returns a *coherent* verdict instead of throwing, that the
 * application-scoped owner completes a bounded refresh twice in a row, that its vocabulary never
 * drifts into PC-backend states, and that the launcher is wired without crashing.
 *
 * The behaviour of every branch (installed/missing/permission denied/collection not initialized/
 * unsupported spec) is proven by the JVM suite with fakes, because those branches must be provable
 * on every push, not only on a nightly emulator.
 */
@RunWith(AndroidJUnit4::class)
class AnkiDroidIntegrationInstrumentedTest {

    @Test
    fun `the real platform probe answers on the published contract and never throws`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = AndroidAnkiDroidProbe(context)
        val endpoint = AnkiDroidEndpoints.RELEASE

        val outcome = runBlocking {
            // Discovery must not perform a read and must not throw: `null` means "this authority
            // does not resolve", which is a fact, not an error (§9/§20).
            val facts = probe.providerFacts(endpoint)
            if (facts != null) {
                assertEquals(AnkiDroidApiContract.RELEASE_AUTHORITY, facts.authority)
                assertEquals(endpoint.expectedPackage, facts.providerPackage)
                assertTrue("a published spec is never negative", facts.providerSpec >= 0)
                assertNotNull(facts.providerSpecSource)
            }
            probe.probeCollection(endpoint)
        }

        when (outcome) {
            is AnkiDroidProbeOutcome.Reached ->
                // Whether the provider had a selected-deck row is AnkiDroid's business; the
                // assertion is that the bounded read completed and returned a fact.
                Unit

            is AnkiDroidProbeOutcome.Failed ->
                assertTrue(
                    "a provider failure must arrive classified, never as a raw exception",
                    outcome.failure.technicalLabel.isNotBlank()
                )
        }
    }

    @Test
    fun `detection is coherent on this device, with or without AnkiDroid installed`() {
        val snapshot = runBlocking { appContainer().ankiDroidHealthRepository.refresh() }
        val detection = snapshot.detection

        assertTrue("the check reports a non-negative duration", snapshot.durationMs >= 0L)
        assertTrue(
            "an installed verdict implies a visible package",
            !detection.installed || detection.packageName != null
        )

        when (detection.availability) {
            is AnkiAvailability.Ready -> {
                assertTrue("Ready requires a resolved provider", detection.providerAvailable)
                assertTrue("Ready implies the permission was granted", detection.permissionGranted == true)
                assertTrue("Ready implies the collection answered", detection.collectionReady != false)
                assertEquals(AnkiCapabilities.NONE, detection.capabilities)
                assertTrue(
                    "the observed spec is inside the supported range",
                    (detection.providerSpec ?: 0) >= AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC
                )
                assertNull("a Ready verdict carries no failure", detection.failure)
            }

            AnkiAvailability.NotInstalled -> {
                assertFalse(detection.providerAvailable)
                assertNull(detection.packageName)
                // Questions that were never reachable are unknown, never guessed (§12).
                assertNull(detection.permissionGranted)
                assertNull(detection.collectionReady)
            }

            is AnkiAvailability.PermissionRequired -> {
                assertTrue("a permission verdict implies a provider", detection.providerAvailable)
                assertTrue("a permission verdict reports the denial", detection.permissionGranted == false)
            }

            // Every other verdict is a legitimate outcome on an arbitrary device; the assertion
            // is that one was produced instead of an exception escaping the integration (§90).
            else -> Unit
        }
    }

    @Test
    fun `repeated refreshes and foreground triggers stay bounded and never wedge the owner`() {
        val repository = appContainer().ankiDroidHealthRepository

        runBlocking {
            val first = repository.refresh()
            // Two foreground events back to back: the debounce must swallow the second one
            // without starving the next explicit refresh (§28/§30).
            repository.onAppForeground()
            repository.onAppForeground()
            val second = repository.refresh()

            assertTrue(
                "a completed check is never older than the previous one",
                second.checkedAtEpochMs >= first.checkedAtEpochMs
            )
            assertTrue(
                "one check stays inside its documented budget",
                second.durationMs <= 10_000L
            )
            assertEquals(second.availability, repository.health.value.availability)
            assertEquals(second.availability, repository.availability.value)
        }
    }

    @Test
    fun `AnkiDroid health never reports a PC-backend state and never stays unknown`() {
        val snapshot = runBlocking { appContainer().ankiDroidHealthRepository.refresh() }

        assertFalse(
            "an AnkiDroid check must not borrow the PC backend's vocabulary (§92)",
            snapshot.availability is AnkiAvailability.AgentDisconnected
        )
        assertFalse(
            snapshot.availability is AnkiAvailability.AgentAnkiUnavailable
        )
        assertTrue(
            "once refresh() returns, the state is no longer 'checking'",
            snapshot.availability !is AnkiAvailability.Checking
        )
    }

    @Test
    fun `opening AnkiDroid is wired and reports NotInstalled when it is absent`() {
        val container = appContainer()
        val health = runBlocking { container.ankiDroidHealthRepository.refresh() }

        // Launching a real AnkiDroid would steal focus from the test app, so the launch path is
        // asserted only where it is provably absent (the emulator case). A device that has
        // AnkiDroid skips instead of failing — this suite must never require it (§107).
        assumeTrue(
            "AnkiDroid is installed here; skipping the launch assertion",
            !health.detection.installed
        )

        assertEquals(
            AnkiDroidOpenResult.NotInstalled,
            runBlocking { container.ankiDroidLauncher.open() }
        )
    }

    @Test
    fun `deck listing is a typed result and never throws`() {
        val container = appContainer()
        runBlocking { container.ankiDroidHealthRepository.refresh() }
        val backend = container.ankiDroidBackend
        val result = runBlocking { backend.getDecks() }
        when (result) {
            is AnkiResult.Success -> {
                assertTrue(result.value.all { it.ref.backendId == AnkiBackendId.AnkiDroidLocal })
                assertEquals(result.value.map { it.ref }.distinct(), result.value.map { it.ref })
                result.value.forEach { deck ->
                    assertTrue(deck.ref.deckId.isNotBlank())
                    assertTrue(deck.name.isNotBlank())
                }
            }
            is AnkiResult.Failure -> {
                assertTrue(result.error.message.isNotBlank())
            }
        }
        val selected = runBlocking { backend.getSelectedDeck() }
        assertTrue(selected is AnkiResult.Success || selected is AnkiResult.Failure)
    }
}
