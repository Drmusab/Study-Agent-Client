package com.studyagent.client.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiIdentityAssurance
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.AnkiStoredMediaRef
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.create.CreationLedgerResult
import com.studyagent.client.core.anki.create.CreateNoteBackendResult
import com.studyagent.client.core.anki.create.MediaStoreBackendResult
import com.studyagent.client.core.anki.create.NoteCreationAttestation
import com.studyagent.client.core.anki.create.NoteCreationFieldAssembly
import com.studyagent.client.core.anki.create.NoteCreationOutcome
import com.studyagent.client.core.anki.create.NoteCreationRecoveryOutcome
import com.studyagent.client.core.anki.create.NoteCreationRecord
import com.studyagent.client.core.anki.create.NoteCreationRefusal
import com.studyagent.client.core.anki.create.NoteCreationRoutingPolicy
import com.studyagent.client.core.anki.create.NoteCreationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GATE 21K — behavioural verification that note/media creation is bound to one immutable backend.
 *
 * Both fakes are built by [Gate21kWorld] with deliberately colliding backend-local identities
 * (model `"100"`, deck `"10"`, first note `"1000"`), so any confusion between the two configured
 * instances shows up as a call log on the wrong backend or as a hydrated payload from the wrong
 * schema. No real Anki is touched.
 *
 * Test names carry the Sub-Gate verification IDs (VER-21K-01 … 06) and the checkpoint IDs (K01–K06)
 * they discharge; the structural half lives in `anki/Gate21kArchitectureAuditTest`.
 */
class Gate21kNoteCreationOwnershipTest {

    // ------------------------------------------------- VER-21K-01 — a draft creates on its own backend

    @Test
    fun `VER-21K-01 a draft bound to A creates only on A`() = runBlocking {
        val world = Gate21kWorld()

        val outcome = world.coordinator.create(world.draftFor(world.a))

        val created = outcome as? NoteCreationOutcome.Created
            ?: fail("expected Created, was $outcome") as Nothing
        assertEquals("record keeps the draft's own backend", world.aId, created.record.backendId)
        assertEquals(1, world.a.createNoteCalls.size)
        assertEquals("the other backend is never even read", 0, world.b.totalCalls())
        assertEquals(world.aId, created.created.noteRef.backendId)
        assertEquals("Deck@A", created.created.cards.first().deckName)
    }

    @Test
    fun `VER-21K-01 a draft bound to B creates only on B`() = runBlocking {
        val world = Gate21kWorld()

        val outcome = world.coordinator.create(world.draftFor(world.b))

        val created = outcome as? NoteCreationOutcome.Created
            ?: fail("expected Created, was $outcome") as Nothing
        assertEquals(world.bId, created.record.backendId)
        assertEquals(1, world.b.createNoteCalls.size)
        assertEquals(0, world.a.totalCalls())
        assertEquals("Deck@B", created.created.cards.first().deckName)
    }

    // ------------------------------------- VER-21K-02 — the global preference cannot redirect a draft

    @Test
    fun `VER-21K-02 flipping the global preference to B does not redirect a draft bound to A`() =
        runBlocking {
            val world = Gate21kWorld()
            // The preference is exactly the value the composition root holds; the coordinator is
            // constructed exactly as production constructs it — registry plus ledger, no preference.
            val preference = MutableStateFlow<BackendPreference>(BackendPreference.Explicit(world.aId))
            val draft = world.draftFor(world.a)

            preference.value = BackendPreference.Explicit(world.bId)
            val first = world.coordinator.create(draft)

            preference.value = BackendPreference.Auto
            val second = world.coordinator.create(world.draftFor(world.a))

            assertTrue("first creation must succeed on A: $first", first is NoteCreationOutcome.Created)
            assertTrue("second creation must succeed on A: $second", second is NoteCreationOutcome.Created)
            assertEquals(2, world.a.createNoteCalls.size)
            assertEquals("preference churn touched nothing on B", 0, world.b.totalCalls())
            assertNotEquals(
                "the preference moved away from A; the drafts bound to A ignored it",
                BackendPreference.Explicit(world.aId),
                preference.value
            )
        }

    @Test
    fun `VER-21K-02 the only way to reach B is a draft bound to B, never a preference change`() =
        runBlocking {
            val world = Gate21kWorld()
            val onA = world.coordinator.create(world.draftFor(world.a))
            val onB = world.coordinator.create(world.draftFor(world.b))

            val a = onA as NoteCreationOutcome.Created
            val b = onB as NoteCreationOutcome.Created
            assertEquals(world.aId, a.record.backendId)
            assertEquals(world.bId, b.record.backendId)
            assertEquals(1, world.a.createNoteCalls.size)
            assertEquals(1, world.b.createNoteCalls.size)
            assertNotEquals(
                "the two records are different transactions on different backends",
                a.record.creationId,
                b.record.creationId
            )
        }

    // ------------------------------------ VER-21K-03 — media and note share the locked destination

    @Test
    fun `VER-21K-03 media store and note creation of one creation share the locked backend`() =
        runBlocking {
            val world = Gate21kWorld()

            val outcome = world.coordinator.create(
                world.draftFor(world.a, media = listOf(Gate21kWorld.image(target = 1)))
            )

            val created = outcome as? NoteCreationOutcome.Created
                ?: fail("expected Created, was $outcome") as Nothing
            assertEquals(1, world.a.mediaStoreCalls.size)
            assertEquals(1, world.a.createNoteCalls.size)
            assertEquals("neither boundary was reached on B", 0, world.b.totalCalls())
            assertTrue(
                "every stored-media ref stays qualified to the creating backend",
                created.record.storedMediaRefs.all { it.backendId == world.aId }
            )
            assertTrue(
                "the note references the name its own backend returned",
                created.created.fields.any { it.contains("<img src=\"image.png\">") }
            )
        }

    @Test
    fun `VER-21K-03 a media store answering under a foreign identity fails closed and creates no note`() =
        runBlocking {
            val world = Gate21kWorld()
            // The invoked backend claims it stored media under the OTHER backend's identity.
            world.a.mediaStoreResults += MediaStoreBackendResult.Stored(
                AnkiStoredMediaRef(world.bId, "image.png")
            )

            val outcome = world.coordinator.create(
                world.draftFor(world.a, media = listOf(Gate21kWorld.image(target = 1)))
            )

            val unclear = outcome as? NoteCreationOutcome.MediaOutcomeUnclear
                ?: fail("expected MediaOutcomeUnclear, was $outcome") as Nothing
            assertEquals(NoteCreationStatus.ABANDONED, unclear.record.status)
            assertTrue(
                "the note boundary is never entered after a foreign media answer",
                world.a.createNoteCalls.isEmpty()
            )
            assertEquals("nothing is stored or created on B either", 0, world.b.totalCalls())
            assertEquals(
                "the orphan-media risk is disclosed, never silently cleaned",
                "stored_media_foreign_backend",
                (unclear.error as AnkiError.InvalidRequest).detail
            )
        }

    @Test
    fun `VER-21K-03 field assembly refuses a stored name qualified to another backend`() {
        val world = Gate21kWorld()
        val draft = world.draftFor(world.a, media = listOf(Gate21kWorld.image(target = 1)))

        val foreign = NoteCreationFieldAssembly.assemble(
            draft,
            listOf(AnkiStoredMediaRef(world.bId, "image.png"))
        )
        val own = NoteCreationFieldAssembly.assemble(
            draft,
            listOf(AnkiStoredMediaRef(world.aId, "image.png"))
        )

        assertNull("an identical name from another backend is never referenced", foreign)
        assertNotNull(own)
        assertTrue(own!!.any { it.contains("<img src=\"image.png\">") })
    }

    // --------------------------------- VER-21K-04 — an unknown result never retries on another backend

    @Test
    fun `VER-21K-04 an ambiguous creation is never retried on the other backend`() = runBlocking {
        val world = Gate21kWorld()
        world.a.failCreateWithUnknown = true

        val outcome = world.coordinator.create(world.draftFor(world.a))
        val ambiguous = outcome as? NoteCreationOutcome.AmbiguousCreation
            ?: fail("expected AmbiguousCreation, was $outcome") as Nothing
        assertEquals(NoteCreationStatus.AMBIGUOUS, ambiguous.record.status)
        assertEquals(world.aId, ambiguous.record.backendId)

        val retry = world.coordinator.retry(ambiguous.record.creationId)
        val refused = retry as? NoteCreationOutcome.Refused
            ?: fail("expected Refused, was $retry") as Nothing
        assertEquals(
            NoteCreationRefusal.RetryNotAllowed(NoteCreationStatus.AMBIGUOUS),
            refused.reason
        )
        assertEquals("still exactly one dispatch, on A", 1, world.a.createNoteCalls.size)
        assertEquals(0, world.b.totalCalls())

        // A human attestation closes the record and touches no backend at all.
        val attested = world.coordinator.resolveAmbiguous(
            ambiguous.record.creationId,
            NoteCreationAttestation.ABSENT_FROM_COLLECTION
        )
        assertTrue(attested is NoteCreationRecoveryOutcome.Resolved)
        assertEquals(1, world.a.createNoteCalls.size)
        assertEquals(0, world.b.totalCalls())
    }

    @Test
    fun `VER-21K-04 an ambiguous creation survives a restart still owned by A and never routed to B`() =
        runBlocking {
            val world = Gate21kWorld()
            world.a.failCreateWithUnknown = true
            val ambiguous = world.coordinator.create(world.draftFor(world.a))
                as NoteCreationOutcome.AmbiguousCreation

            // New process: same durable store, both backends configured again.
            val restarted = world.restartedCoordinator(world.a, world.b)
            val unresolved = restarted.unresolvedCreations()

            assertEquals(1, unresolved.size)
            assertEquals(world.aId, unresolved.first().backendId)
            val retry = restarted.retry(unresolved.first().creationId)
            assertEquals(
                NoteCreationRefusal.RetryNotAllowed(NoteCreationStatus.AMBIGUOUS),
                (retry as NoteCreationOutcome.Refused).reason
            )
            assertEquals("B is not touched by recovery of A's transaction", 0, world.b.totalCalls())
        }

    @Test
    fun `VER-21K-04 a proven non-creation is abandoned at restart and never redirected to B`() =
        runBlocking {
            val world = Gate21kWorld()
            world.a.createNoteResults += CreateNoteBackendResult.ConfirmedNotCreated(
                AnkiError.QueryFailure("fake_refused")
            )
            val first = world.coordinator.create(world.draftFor(world.a))
            val retryable = first as? NoteCreationOutcome.RetryAvailable
                ?: fail("expected RetryAvailable, was $first") as Nothing
            assertEquals(NoteCreationStatus.RETRY_ALLOWED, retryable.record.status)
            assertEquals(world.aId, retryable.record.backendId)

            // New process: GATE 18 abandons a RETRY_ALLOWED record because the payload lived in the
            // old process (NoteCreationLedger.normalizeAfterRestart). The point of this assertion is
            // that the abandonment is decided from the record's own state — the still-configured B is
            // never offered as a substitute destination.
            val restarted = world.restartedCoordinator(world.a, world.b)
            val refused = restarted.retry(retryable.record.creationId)
            assertEquals(
                NoteCreationRefusal.RetryNotAllowed(NoteCreationStatus.ABANDONED),
                (refused as NoteCreationOutcome.Refused).reason
            )
            assertEquals("no second dispatch on A", 1, world.a.createNoteCalls.size)
            assertEquals("and none at all on B", 0, world.b.totalCalls())
        }

    @Test
    fun `VER-21K-04 a retry is blocked, not redirected, when the owning backend leaves the registry`() =
        runBlocking {
            val world = Gate21kWorld()
            world.a.createNoteResults += CreateNoteBackendResult.ConfirmedNotCreated(
                AnkiError.QueryFailure("fake_refused")
            )
            val retryable = world.coordinator.create(world.draftFor(world.a))
                as NoteCreationOutcome.RetryAvailable

            // Same process, so the payload and the RETRY_ALLOWED status both survive; only A's
            // registration is withdrawn, which is exactly the "chosen backend disappears" case.
            world.registry.remove(world.aId)
            val blocked = world.coordinator.retry(retryable.record.creationId)

            val refused = blocked as? NoteCreationOutcome.Refused
                ?: fail("expected Refused, was $blocked") as Nothing
            assertEquals(
                NoteCreationRefusal.BackendRouteBlocked(NoteCreationRoutingPolicy.TOKEN_NOT_REGISTERED),
                refused.reason
            )
            assertEquals("B performed no work for A's creation", 0, world.b.totalCalls())
            val preserved = world.coordinator.activeCreationsFor(world.aId)
            assertEquals("the record keeps its owner while the owner is unregistered", 1, preserved.size)
            assertEquals(NoteCreationStatus.RETRY_ALLOWED, preserved.first().status)
            assertEquals(world.aId, preserved.first().backendId)

            // Re-registering the SAME identity restores the same logical owner: the retry then runs
            // on A, which is the only thing that could ever have been allowed.
            world.registry.register(world.a)
            val resumed = world.coordinator.retry(retryable.record.creationId)
            assertTrue("expected the retry to succeed on A, was $resumed", resumed is NoteCreationOutcome.Created)
            assertEquals(2, world.a.createNoteCalls.size)
            assertEquals(0, world.b.totalCalls())
        }

    @Test
    fun `K05 the routing policy answers a retry for the record's own backend under a live binding`() =
        runBlocking {
            val world = Gate21kWorld()
            world.a.createNoteResults += CreateNoteBackendResult.ConfirmedNotCreated(
                AnkiError.QueryFailure("fake_refused")
            )
            val retryable = world.coordinator.create(world.draftFor(world.a))
                as NoteCreationOutcome.RetryAvailable
            val policy = NoteCreationRoutingPolicy(world.registry)

            val routed = policy.route(retryable.record, boundIsLiveInThisProcess = true)
                as? com.studyagent.client.core.anki.create.NoteCreationRoute.Routed
                ?: fail("expected Routed for the record's own backend") as Nothing

            assertEquals(world.aId, routed.backend.id)
            assertTrue(
                "no key on either side under a live binding is continuity, disclosed as such",
                routed.assurance is AnkiIdentityAssurance.SessionContinuityVerified
            )
        }

    @Test
    fun `K05 an unregistered owner yields the typed not-registered token, never a substitute`() {
        val world = Gate21kWorld()
        val policy = NoteCreationRoutingPolicy(world.registry)
        val record = preparedOn(AnkiBackendId.PcAgent("pc-profile-absent"))

        val route = policy.route(record, boundIsLiveInThisProcess = false)

        assertEquals(
            com.studyagent.client.core.anki.create.NoteCreationRoute.Blocked(
                NoteCreationRoutingPolicy.TOKEN_NOT_REGISTERED,
                com.studyagent.client.core.anki.AnkiOperationClass.OP_M1
            ),
            route
        )
    }

    // ---------------------------------------- VER-21K-05 — hydration reads the creating backend only

    @Test
    fun `VER-21K-05 post-create hydration reads from the backend that created the note`() = runBlocking {
        val world = Gate21kWorld()

        val outcome = world.coordinator.create(world.draftFor(world.a))

        assertTrue(outcome is NoteCreationOutcome.Created)
        assertEquals(1, world.a.hydrationCalls.size)
        assertEquals(world.aId, world.a.hydrationCalls.first().backendId)
        assertEquals("B was never read, not even for hydration", 0, world.b.totalCalls())
    }

    @Test
    fun `VER-21K-05 a hydration resume after restart reads the original backend only`() = runBlocking {
        val world = Gate21kWorld()
        world.a.hydrationFailures += AnkiError.QueryFailure("fake_hydration_down")
        val outcome = world.coordinator.create(world.draftFor(world.a))
        val failed = outcome as? NoteCreationOutcome.CreatedHydrationFailed
            ?: fail("expected CreatedHydrationFailed, was $outcome") as Nothing
        assertEquals(NoteCreationStatus.CREATED, failed.record.status)

        val restarted = world.restartedCoordinator(world.a, world.b)
        val resumed = restarted.hydrate(failed.record.creationId)

        val hydrated = resumed as? NoteCreationRecoveryOutcome.Hydrated
            ?: fail("expected Hydrated, was $resumed") as Nothing
        assertEquals(world.aId, hydrated.created.noteRef.backendId)
        assertEquals("Deck@A", hydrated.created.cards.first().deckName)
        assertEquals(0, world.b.totalCalls())
    }

    @Test
    fun `VER-21K-05 hydration is blocked when the owning backend is not registered`() = runBlocking {
        val world = Gate21kWorld()
        world.a.hydrationFailures += AnkiError.QueryFailure("fake_hydration_down")
        val failed = world.coordinator.create(world.draftFor(world.a))
            as NoteCreationOutcome.CreatedHydrationFailed

        val withoutA = world.restartedCoordinator(world.b)
        val outcome = withoutA.hydrate(failed.record.creationId)

        val blocked = outcome as? NoteCreationRecoveryOutcome.HydrationFailed
            ?: fail("expected HydrationFailed, was $outcome") as Nothing
        assertEquals(
            NoteCreationRoutingPolicy.TOKEN_NOT_REGISTERED,
            (blocked.error as AnkiError.InvalidRequest).detail
        )
        assertEquals("the same note id on B was never read", 0, world.b.totalCalls())
        assertEquals(world.aId, blocked.record.backendId)
        assertEquals(NoteCreationStatus.CREATED, blocked.record.status)
    }

    // ------------------------------ VER-21K-06 — colliding numeric ids stay distinct across backends

    @Test
    fun `VER-21K-06 identical numeric model and deck ids are distinct identities per backend`() {
        val world = Gate21kWorld()
        val modelA = AnkiNoteModelRef(world.aId, "100")
        val modelB = AnkiNoteModelRef(world.bId, "100")
        val deckA = AnkiDeckRef(world.aId, "10")
        val deckB = AnkiDeckRef(world.bId, "10")

        assertEquals("100", modelA.modelId)
        assertEquals("100", modelB.modelId)
        assertNotEquals(modelA, modelB)
        assertNotEquals(modelA.stableKey, modelB.stableKey)
        assertEquals("10", deckA.deckId)
        assertEquals("10", deckB.deckId)
        assertNotEquals(deckA, deckB)
        assertNotEquals(deckA.stableKey, deckB.stableKey)
    }

    @Test
    fun `VER-21K-06 two creations using the same numeric ids land on their own backends and schemas`() =
        runBlocking {
            val world = Gate21kWorld()
            assertEquals("100", world.modelOf(world.a).ref.modelId)
            assertEquals("100", world.modelOf(world.b).ref.modelId)

            val onA = world.coordinator.create(world.draftFor(world.a)) as NoteCreationOutcome.Created
            val onB = world.coordinator.create(world.draftFor(world.b)) as NoteCreationOutcome.Created

            // The backend-local note id collides by construction; only the qualification differs.
            assertEquals(onA.created.noteRef.noteId, onB.created.noteRef.noteId)
            assertNotEquals(onA.created.noteRef.stableKey, onB.created.noteRef.stableKey)
            assertEquals("Deck@A", onA.created.cards.first().deckName)
            assertEquals("Deck@B", onB.created.cards.first().deckName)
            assertEquals(world.aId, onA.created.cards.first().deckRef?.backendId)
            assertEquals(world.bId, onB.created.cards.first().deckRef?.backendId)
            // Each creation was validated against its own backend's schema read, never the other's.
            assertEquals(listOf("Front", "Back"), world.modelOf(world.a).fields.map { it.name })
            assertEquals(listOf("Question", "Answer"), world.modelOf(world.b).fields.map { it.name })
            assertEquals(2, world.a.modelListingCalls)
            assertEquals(2, world.b.modelListingCalls)
            assertEquals(
                "each note keeps its own backend's model schema",
                world.modelOf(world.a).ref,
                onA.created.model
            )
            assertEquals(world.modelOf(world.b).ref, onB.created.model)
        }

    @Test
    fun `VER-21K-06 a draft cannot be assembled from another backend's model`() {
        val world = Gate21kWorld()
        val foreignModel = world.modelOf(world.b)

        val thrown = try {
            com.studyagent.client.core.anki.create.AddNoteDraft(
                context = com.studyagent.client.core.anki.create.AnkiNoteCreationContext.of(world.aId),
                model = foreignModel,
                fieldValues = mapOf(0 to "front", 1 to "back"),
                tags = emptyList(),
                media = emptyList()
            )
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertNotNull("a cross-backend model must not become a draft", thrown)
    }

    @Test
    fun `VER-21K-06 a creation request cannot carry another backend's model reference`() {
        val world = Gate21kWorld()

        val thrown = try {
            com.studyagent.client.core.anki.create.CreateNoteBackendRequest(
                backendId = world.aId,
                model = world.modelOf(world.b).ref,
                orderedFields = listOf("front", "back"),
                tags = emptyList()
            )
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertNotNull(thrown)
    }

    // ------------------------------------------- K01/K04 — collection evidence gates the destination

    @Test
    fun `K01 admission refuses a draft whose collection evidence contradicts the live backend`() =
        runBlocking {
            val world = Gate21kWorld()
            world.a.collectionIdentity = AnkiCollectionIdentity(world.aId, "col-live")

            val outcome = world.coordinator.create(world.draftFor(world.a, collectionKey = "col-bound"))

            val refused = outcome as? NoteCreationOutcome.Refused
                ?: fail("expected Refused, was $outcome") as Nothing
            assertEquals(
                NoteCreationRefusal.BackendRouteBlocked("collection_mismatch"),
                refused.reason
            )
            assertEquals("nothing was recorded", 0, world.store.writes)
            assertEquals("nothing was sent", 0, world.a.totalCalls())
            assertEquals(0, world.b.totalCalls())
        }

    @Test
    fun `K01 admission refuses a binding whose key the backend can no longer present`() = runBlocking {
        val world = Gate21kWorld()
        // The draft carries a key; the backend presents none — that is degraded, not "no problem".
        val outcome = world.coordinator.create(world.draftFor(world.a, collectionKey = "col-bound"))

        val refused = outcome as? NoteCreationOutcome.Refused
            ?: fail("expected Refused, was $outcome") as Nothing
        assertEquals(
            NoteCreationRefusal.BackendRouteBlocked("identity_unverifiable_degraded"),
            refused.reason
        )
        assertEquals(0, world.store.writes)
        assertEquals(0, world.a.totalCalls())
    }

    @Test
    fun `K01 admission permits a draft whose collection key matches the live backend`() = runBlocking {
        val world = Gate21kWorld()
        world.a.collectionIdentity = AnkiCollectionIdentity(world.aId, "col-bound")

        val outcome = world.coordinator.create(world.draftFor(world.a, collectionKey = "col-bound"))

        val created = outcome as? NoteCreationOutcome.Created
            ?: fail("expected Created, was $outcome") as Nothing
        assertEquals("col-bound", created.record.collectionKey)
        assertTrue(
            "stored media inherits the verified collection key",
            created.record.storedMediaRefs.all { it.collectionKey == "col-bound" }
        )
        assertEquals(0, world.b.totalCalls())
    }

    @Test
    fun `K04 a verified collection mismatch blocks a retry instead of redirecting it`() = runBlocking {
        val world = Gate21kWorld()
        world.a.collectionIdentity = AnkiCollectionIdentity(world.aId, "col-bound")
        world.a.createNoteResults += CreateNoteBackendResult.ConfirmedNotCreated(
            AnkiError.QueryFailure("fake_refused")
        )
        val retryable = world.coordinator.create(world.draftFor(world.a, collectionKey = "col-bound"))
            as NoteCreationOutcome.RetryAvailable

        // The collection provably moved under the same configured instance.
        world.a.collectionIdentity = AnkiCollectionIdentity(world.aId, "col-moved")
        val outcome = world.coordinator.retry(retryable.record.creationId)

        val refused = outcome as? NoteCreationOutcome.Refused
            ?: fail("expected Refused, was $outcome") as Nothing
        assertEquals(
            NoteCreationRefusal.BackendRouteBlocked("collection_mismatch"),
            refused.reason
        )
        assertEquals("no second dispatch anywhere", 1, world.a.createNoteCalls.size)
        assertEquals(0, world.b.totalCalls())
        val preserved = world.coordinator.activeCreationsFor(world.aId)
        assertEquals(NoteCreationStatus.RETRY_ALLOWED, preserved.first().status)
    }

    // ------------------------------------------------------------- recovery surface stays per-backend

    @Test
    fun `K05 the recovery surface partitions unresolved creations by owning backend`() = runBlocking {
        val world = Gate21kWorld()
        world.a.failCreateWithUnknown = true
        val ambiguousA = world.coordinator.create(world.draftFor(world.a))
            as NoteCreationOutcome.AmbiguousCreation
        world.b.failCreateWithUnknown = true
        val ambiguousB = world.coordinator.create(world.draftFor(world.b))
            as NoteCreationOutcome.AmbiguousCreation

        val unresolved = world.coordinator.unresolvedCreations()
        val forA = world.coordinator.activeCreationsFor(world.aId)
        val forB = world.coordinator.activeCreationsFor(world.bId)
        val forMissing = world.coordinator.activeCreationsFor(AnkiBackendId.PcAgent("pc-profile-2"))

        assertEquals(2, unresolved.size)
        assertEquals(listOf(ambiguousA.record.creationId), forA.map(NoteCreationRecord::creationId))
        assertEquals(listOf(ambiguousB.record.creationId), forB.map(NoteCreationRecord::creationId))
        assertTrue("an unconfigured instance owns nothing", forMissing.isEmpty())
        assertFalse(
            "neither record is visible under the other's backend",
            forA.any { it.backendId == world.bId } || forB.any { it.backendId == world.aId }
        )
    }

    @Test
    fun `K05 a durable record round-trips its owning backend through the store`() = runBlocking {
        val world = Gate21kWorld()
        world.a.failCreateWithUnknown = true
        val ambiguous = world.coordinator.create(world.draftFor(world.a))
            as NoteCreationOutcome.AmbiguousCreation

        val reloaded = world.restartedCoordinator(world.a, world.b)
            .unresolvedCreations()
            .single()

        assertEquals(ambiguous.record.backendId, reloaded.backendId)
        assertEquals(world.aId, reloaded.backendId)
        assertEquals(ambiguous.record.creationId, reloaded.creationId)
        assertEquals(0, world.b.totalCalls())
    }

    @Test
    fun `K06 the ledger stays readable after a creation so recovery can route at all`() = runBlocking {
        val world = Gate21kWorld()
        val created = world.coordinator.create(world.draftFor(world.a)) as NoteCreationOutcome.Created

        val read = world.ledger.get(created.record.creationId)

        val ok = read as? CreationLedgerResult.Ok
            ?: fail("expected a readable record, was $read") as Nothing
        assertEquals(world.aId, ok.value?.backendId)
        assertEquals(NoteCreationStatus.CREATED, ok.value?.status)
        assertEquals(created.record.createdNoteId, ok.value?.createdNoteId)
    }

    // ------------------------------------------------------------------ helpers

    /** A minimal PREPARED record owned by [backendId], for pure routing-policy assertions. */
    private fun preparedOn(backendId: AnkiBackendId): NoteCreationRecord =
        com.studyagent.client.core.anki.create.newPreparedNoteCreationRecord(
            creationId = com.studyagent.client.core.anki.create.NoteCreationId("creation-x"),
            backendId = backendId,
            collectionKey = null,
            modelId = "100",
            modelName = "Basic",
            fieldCount = 2,
            tagCount = 0,
            mediaSteps = emptyList(),
            nowEpochMs = 1L
        )
}
