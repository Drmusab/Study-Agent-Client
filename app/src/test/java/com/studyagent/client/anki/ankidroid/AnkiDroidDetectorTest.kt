package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.isReadyForReview
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDetectionResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoint
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoints
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureClassifier
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.AnkiDroidOperationStage
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProbeOutcome
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 02 §11/§12/§13/§19/§20/§21 — one detection pass, every branch.
 *
 * The detector is pure orchestration over two seams, so the whole availability matrix is
 * reachable from a JVM test: no Android framework, no AnkiDroid, no emulator. That is the
 * property that makes INV-ANKI-DET-01…04 provable in normal CI.
 */
class AnkiDroidDetectorTest {

    private val release = AnkiDroidEndpoints.RELEASE
    private val debug = AnkiDroidEndpoints.DEBUG

    private fun detector(
        probe: FakeAnkiDroidProbe = FakeAnkiDroidProbe(),
        permissions: FakeAnkiDroidPermissionManager = FakeAnkiDroidPermissionManager(),
        endpoints: List<AnkiDroidEndpoint> = AnkiDroidEndpoints.forBuild(includeDebugEndpoints = false)
    ) = DefaultAnkiDroidDetector(probe, permissions, endpoints)

    // ---------------------------------------------------------------- absence (§112.1)

    @Test
    fun `nothing installed is a normal state, not a failure`() = kotlinx.coroutines.test.runTest {
        val probe = FakeAnkiDroidProbe()

        val detection = detector(probe).detect()

        assertEquals(AnkiAvailability.NotInstalled, detection.availability)
        assertEquals("NOT_INSTALLED", detection.availability.statusCode)
        assertFalse(detection.installed)
        assertFalse(detection.providerAvailable)
        assertNull("not installed is not an error", detection.failure)
        assertNull("the permission question is moot without a provider", detection.permissionGranted)
        assertNull("the collection question is unanswered", detection.collectionReady)
        assertEquals(listOf(release.authority), detection.checkedAuthorities)
        assertEquals(release.authority, detection.authority)
        assertEquals(AnkiCapabilities.NONE, detection.capabilities)
    }

    @Test
    fun `package present without a provider is provider unavailable, never ready`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().apply {
                installedPackages += release.expectedPackage
            }

            val detection = detector(probe).detect()

            assertTrue(detection.installed)
            assertFalse(detection.providerAvailable)
            assertTrue(detection.availability is AnkiAvailability.ProviderUnavailable)
            assertEquals("PROVIDER_UNAVAILABLE", detection.availability.statusCode)
            assertEquals(
                AnkiDroidFailureCategory.ENDPOINT_UNAVAILABLE,
                detection.failure?.category
            )
            assertEquals(
                AnkiDroidFailureEvidence.PACKAGE_PRESENT_PROVIDER_MISSING,
                detection.failure?.evidence
            )
            assertFalse((detection.availability as AnkiAvailability).isReadyForReview)
        }

    @Test
    fun `a provider lookup failure is reported instead of thrown`() = kotlinx.coroutines.test.runTest {
        val probe = FakeAnkiDroidProbe().apply { providerFactsThrowable = RuntimeException("boom") }

        val detection = detector(probe).detect()

        assertTrue(detection.availability is AnkiAvailability.ProviderUnavailable)
        assertEquals(AnkiDroidFailureCategory.UNEXPECTED, detection.failure?.category)
    }

    // ---------------------------------------------------------------- provider shape (§9/§10)

    @Test
    fun `an unexpected provider package is never reported as ready`() = kotlinx.coroutines.test.runTest {
        val probe = FakeAnkiDroidProbe().resolves(providerPackage = "com.example.impostor")

        val detection = detector(probe).detect()

        assertTrue(detection.availability is AnkiAvailability.ProviderUnavailable)
        assertEquals("com.example.impostor", detection.packageName)
        assertEquals(
            AnkiDroidFailureEvidence.UNEXPECTED_PROVIDER_PACKAGE,
            detection.failure?.evidence
        )
        assertEquals("com.example.impostor", detection.failure?.evidenceToken)
    }

    @Test
    fun `a disabled provider is not reachable even though everything else is present`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves(enabled = false)

            val detection = detector(probe).detect()

            assertTrue(detection.availability is AnkiAvailability.ProviderUnavailable)
            assertFalse(detection.providerAvailable)
            assertEquals(AnkiDroidFailureEvidence.PROVIDER_DISABLED, detection.failure?.evidence)
            assertTrue(detection.installed)
        }

    // ---------------------------------------------------------------- spec (§15/§16)

    @Test
    fun `a provider spec below the minimum is unsupported and no probe is issued`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves(spec = 0)
            val permissions = FakeAnkiDroidPermissionManager()

            val detection = detector(probe, permissions).detect()

            assertTrue(detection.availability is AnkiAvailability.Unsupported)
            assertEquals("UNSUPPORTED", detection.availability.statusCode)
            assertEquals(0, detection.providerSpec)
            assertTrue(detection.providerSpecKnown)
            assertEquals(AnkiDroidFailureCategory.UNSUPPORTED_API, detection.failure?.category)
            assertEquals(AnkiDroidFailureEvidence.SPEC_BELOW_MINIMUM, detection.failure?.evidence)
            assertEquals(0, probe.probeCalls)
            assertEquals("permission must not be asked once the API is unsupported", 0, permissions.calls)
        }

    @Test
    fun `an absent spec metadata falls back to spec one and is marked as implicit`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves(spec = null)

            val detection = detector(probe).detect()

            // The documented fallback (DEFAULT_PROVIDER_SPEC_VALUE = 1) is inside the supported
            // range, so an older install is usable — but diagnostics must show it was not read.
            assertEquals(1, detection.providerSpec)
            assertFalse(detection.providerSpecKnown)
            assertTrue(detection.availability is AnkiAvailability.Ready)
        }

    @Test
    fun `a newer-than-validated spec is still reachable but claims no capabilities`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves(spec = 99)

            val detection = detector(probe).detect()

            assertEquals(99, detection.providerSpec)
            assertTrue(detection.availability is AnkiAvailability.Ready)
            assertEquals(AnkiCapabilities.NONE, detection.capabilities)
            assertFalse("a reachable provider is not a review capability", detection.availability.isReadyForReview)
        }

    // ---------------------------------------------------------------- permission (§17/§18)

    @Test
    fun `permission missing short-circuits before any provider read`() = kotlinx.coroutines.test.runTest {
        val probe = FakeAnkiDroidProbe().resolves()
        val permissions = FakeAnkiDroidPermissionManager(granted = false, protectionLevel = "dangerous")

        val detection = detector(probe, permissions).detect()

        assertTrue(detection.availability is AnkiAvailability.PermissionRequired)
        assertEquals("PERMISSION_REQUIRED", detection.availability.statusCode)
        assertEquals(0, probe.probeCalls)
        assertEquals(false, detection.permissionGranted)
        assertEquals("dangerous", detection.permissionProtectionLevel)
        assertEquals(AnkiDroidFailureCategory.PERMISSION_DENIED, detection.failure?.category)
        assertEquals(AnkiDroidFailureEvidence.PLATFORM_PERMISSION_CHECK, detection.failure?.evidence)
        assertNull("the collection question was not asked", detection.collectionReady)
    }

    @Test
    fun `a provider refusing despite a granted permission is classified, not swallowed`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves().apply {
                probeThrowable = SecurityException("Permission not granted for: $release")
            }

            val detection = detector(probe).detect()

            assertTrue(detection.availability is AnkiAvailability.PermissionRequired)
            assertEquals(AnkiDroidFailureCategory.PERMISSION_DENIED, detection.failure?.category)
            assertEquals(AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE, detection.failure?.evidence)
            assertEquals(true, detection.permissionGranted)
        }

    @Test
    fun `a permission lookup failure is reported as permission required`() = kotlinx.coroutines.test.runTest {
        val permissions = FakeAnkiDroidPermissionManager(throwable = SecurityException("denied"))
        val probe = FakeAnkiDroidProbe().resolves()

        val detection = detector(probe, permissions).detect()

        assertTrue(detection.availability is AnkiAvailability.PermissionRequired)
        assertEquals(AnkiDroidFailureCategory.PERMISSION_DENIED, detection.failure?.category)
    }

    // ---------------------------------------------------------------- collection (§19/§22)

    @Test
    fun `provider, permission and an answering collection are ready`() = kotlinx.coroutines.test.runTest {
        val probe = FakeAnkiDroidProbe().resolves()

        val detection = detector(probe).detect()

        assertTrue(detection.availability is AnkiAvailability.Ready)
        assertEquals("READY", detection.availability.statusCode)
        assertEquals(1, probe.probeCalls)
        assertEquals(true, detection.collectionReady)
        assertEquals(true, detection.permissionGranted)
        assertEquals(true, detection.providerAvailable)
        assertEquals(2, detection.providerSpec)
        assertEquals("release", detection.endpointLabel)
        assertEquals(release.authority, detection.authority)
        assertEquals(AnkiCapabilities.NONE, detection.capabilities)
        assertNull(detection.failure)
        assertFalse(
            "GATE 02 proves reachability, not review; the PC study path stays in charge",
            detection.availability.isReadyForReview
        )
    }

    @Test
    fun `a documented setup failure maps to collection not initialized`() = kotlinx.coroutines.test.runTest {
        val probe = FakeAnkiDroidProbe().resolves().apply {
            failWith(IllegalStateException("AnkiDroid's storage is not yet configured"))
        }

        val detection = detector(probe).detect()

        assertEquals(AnkiAvailability.CollectionNotInitialized, detection.availability)
        assertEquals("COLLECTION_NOT_INITIALIZED", detection.availability.statusCode)
        assertEquals(false, detection.collectionReady)
        assertEquals(AnkiDroidFailureCategory.COLLECTION_NOT_READY, detection.failure?.category)
        assertEquals(AnkiDroidFailureEvidence.COLLECTION_SETUP_SIGNATURE, detection.failure?.evidence)
    }

    @Test
    fun `an undocumented IllegalStateException becomes a fault, never a setup message`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves().apply {
                failWith(IllegalStateException("Backend returned an unexpected state"))
            }

            val detection = detector(probe).detect()

            assertTrue(detection.availability is AnkiAvailability.Fault)
            assertEquals("FAULT", detection.availability.statusCode)
            assertNull("a fault must not claim the collection is unusable", detection.collectionReady)
            assertEquals(AnkiDroidFailureCategory.PROVIDER_ERROR, detection.failure?.category)
            assertFalse(detection.availability == AnkiAvailability.CollectionNotInitialized)
        }

    @Test
    fun `a timeout becomes a fault with a timeout cause and leaves the collection unknown`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves().apply {
                defaultOutcome = AnkiDroidProbeOutcome.Failed(
                    AnkiDroidFailureClassifier.timedOut(AnkiDroidOperationStage.COLLECTION_PROBE)
                )
            }

            val detection = detector(probe).detect()

            val availability = detection.availability as AnkiAvailability.Fault
            assertEquals(AnkiError.QueryFailure(causeCategory = "timeout"), availability.error)
            assertNull(detection.collectionReady)
            assertEquals("FAULT", detection.availability.statusCode)
        }

    @Test
    fun `an unavailable collection is a fault about the collection, and says so`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves().apply {
                defaultOutcome = AnkiDroidProbeOutcome.Failed(
                    testFailure(
                        category = AnkiDroidFailureCategory.COLLECTION_UNAVAILABLE,
                        evidence = AnkiDroidFailureEvidence.STORAGE_EXCEPTION,
                        exceptionClass = "SystemStorageException"
                    )
                )
            }

            val detection = detector(probe).detect()

            assertEquals(
                AnkiAvailability.Fault(AnkiError.CollectionUnavailable()),
                detection.availability
            )
            assertEquals(false, detection.collectionReady)
        }

    @Test
    fun `a locked collection is temporary, and the collection is reported unusable meanwhile`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves().apply {
                defaultOutcome = AnkiDroidProbeOutcome.Failed(
                    testFailure(
                        category = AnkiDroidFailureCategory.COLLECTION_LOCKED,
                        evidence = AnkiDroidFailureEvidence.LOCK_SIGNATURE
                    )
                )
            }

            val detection = detector(probe).detect()

            assertTrue(detection.availability is AnkiAvailability.TemporarilyUnavailable)
            assertEquals("TEMPORARILY_UNAVAILABLE", detection.availability.statusCode)
            assertEquals(false, detection.collectionReady)
        }

    @Test
    fun `a foreign provider answering on our authority is provider unavailable`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().apply {
                defaultOutcome = AnkiDroidProbeOutcome.Failed(
                    testFailure(
                        category = AnkiDroidFailureCategory.ENDPOINT_UNAVAILABLE,
                        evidence = AnkiDroidFailureEvidence.UNEXPECTED_PROVIDER_PACKAGE
                    )
                )
                resolves()
            }

            val detection = detector(probe).detect()

            // ENDPOINT_UNAVAILABLE maps to ProviderUnavailable with no collection claim.
            assertTrue(detection.availability is AnkiAvailability.ProviderUnavailable)
            assertNull(detection.collectionReady)
        }

    @Test
    fun `an unexpected failure class is a fault carrying only the exception name`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves().apply {
                failWith(RuntimeException("mystery"))
            }

            val detection = detector(probe).detect()

            val availability = detection.availability as AnkiAvailability.Fault
            assertEquals(AnkiError.Unknown(cause = "RuntimeException"), availability.error)
        }

    // ---------------------------------------------------------------- endpoints (§10)

    @Test
    fun `the release endpoint wins when both endpoints answer`() = kotlinx.coroutines.test.runTest {
        val probe = FakeAnkiDroidProbe()
            .resolves(endpoint = debug)
            .resolves(endpoint = release)

        val detection = detector(probe, endpoints = AnkiDroidEndpoints.forBuild(true)).detect()

        assertEquals("release", detection.endpointLabel)
        assertEquals(release.authority, detection.authority)
    }

    @Test
    fun `debug builds fall back to the debug endpoint when only that one exists`() =
        kotlinx.coroutines.test.runTest {
            val probe = FakeAnkiDroidProbe().resolves(endpoint = debug)

            val detection = detector(probe, endpoints = AnkiDroidEndpoints.forBuild(true)).detect()

            assertEquals("debug", detection.endpointLabel)
            assertEquals(debug.authority, detection.authority)
            assertEquals(
                listOf(release.authority, debug.authority),
                detection.checkedAuthorities
            )
            assertTrue(detection.availability is AnkiAvailability.Ready)
        }

    @Test
    fun `release builds never resolve the debug endpoint`() = kotlinx.coroutines.test.runTest {
        val probe = FakeAnkiDroidProbe().resolves(endpoint = debug)

        val detection = detector(probe, endpoints = AnkiDroidEndpoints.forBuild(false)).detect()

        assertEquals(listOf(release.authority), probe.providerFactsAuthorities)
        assertEquals(AnkiAvailability.NotInstalled, detection.availability)
    }

    // ---------------------------------------------------------------- invariants

    @Test
    fun `no non-ready state is ever ready for review`() = kotlinx.coroutines.test.runTest {
        val scenarios: List<AnkiDroidDetectionResult> = listOf(
            // 1 — nothing installed
            detector(FakeAnkiDroidProbe()).detect(),
            // 2 — package present, provider missing
            detector(FakeAnkiDroidProbe().apply { installedPackages += release.expectedPackage }).detect(),
            // 3 — permission missing
            detector(FakeAnkiDroidProbe().resolves(), FakeAnkiDroidPermissionManager(granted = false)).detect(),
            // 4 — collection not initialized
            detector(
                FakeAnkiDroidProbe().resolves().apply {
                    failWith(IllegalStateException("collection has not been initialized"))
                }
            ).detect(),
            // 5 — supported and ready
            detector(FakeAnkiDroidProbe().resolves()).detect(),
            // 6 — unsupported provider spec
            detector(FakeAnkiDroidProbe().resolves(spec = 0)).detect()
        )

        assertEquals(
            listOf(
                AnkiAvailability.NotInstalled,
                AnkiAvailability.ProviderUnavailable(
                    "AnkiDroid is installed but its integration provider is not available."
                ),
                AnkiAvailability.PermissionRequired(
                    "Permission ${release.readWritePermission} is not granted."
                ),
                AnkiAvailability.CollectionNotInitialized,
                AnkiAvailability.Ready(AnkiCapabilities.NONE),
                AnkiAvailability.Unsupported(
                    "Provider spec 0 is below the minimum supported spec 1."
                )
            ),
            scenarios.map { it.availability }
        )
        assertTrue(scenarios.map { it.availability.statusCode }.all { it.isNotBlank() })
        assertTrue(scenarios.none { it.availability.isReadyForReview })
        assertNull("a Ready verdict carries no failure", scenarios[4].failure)
    }
}
