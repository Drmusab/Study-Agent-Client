package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiReadContext
import com.studyagent.client.core.anki.AnkiReadContextGeneration
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.data.anki.context.AnkiReadContextAccess
import com.studyagent.client.data.anki.context.AnkiReadContextBinding
import com.studyagent.client.data.anki.context.AnkiReadContextBindingResult
import com.studyagent.client.data.anki.context.AnkiReadContextController
import com.studyagent.client.data.anki.context.AnkiReadContextFailureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic GATE 21F binding/read tests. No Android, provider, scheduler or mutation path. */
class Gate21fReadContextControllerTest {
    private val localId = AnkiBackendId.AnkiDroidLocal
    private val pcId = AnkiBackendId.PcAgent("gate21f-pc")
    private val deckRequirement = setOf(AnkiRequiredCapability.DECKS)
    private val deckCaps = AnkiCapabilities(deckListing = true)

    private fun backend(
        id: AnkiBackendId,
        collectionKey: String? = null,
        caps: AnkiCapabilities = deckCaps,
        deckId: String = "deck-1"
    ): FakeAnkiBackend = FakeAnkiBackend(
        id = id,
        decks = listOf(AnkiDeck(AnkiDeckRef(id, deckId, collectionKey), "Default")),
        initialCapabilities = caps,
        initialAvailability = AnkiAvailability.Ready(caps)
    ).apply {
        currentCollectionIdentity = collectionKey?.let { AnkiCollectionIdentity(id, it) }
    }

    private fun controller(
        backends: List<FakeAnkiBackend>,
        preference: MutableStateFlow<BackendPreference> = MutableStateFlow(BackendPreference.Auto)
    ) = AnkiReadContextController(AnkiBackendRegistry(backends), preference) to preference

    private fun bound(result: AnkiReadContextBindingResult): AnkiReadContextBinding =
        (result as AnkiReadContextBindingResult.Bound).binding

    @Test
    fun `new library context resolves by capability and captures backend collection and generation`() = runTest {
        val incapableLocal = backend(localId, collectionKey = "local", caps = AnkiCapabilities.NONE)
        val eligiblePc = backend(pcId, collectionKey = "pc")
        val (controller, _) = controller(listOf(incapableLocal, eligiblePc))

        val first = bound(controller.bindNewContext(deckRequirement, "library"))
        val second = bound(controller.bindNewContext(deckRequirement, "library"))

        assertEquals(pcId, first.context.backendId)
        assertEquals("pc", first.context.collectionKey)
        assertNotEquals(first.context.generation, second.context.generation)
        assertEquals(2, eligiblePc.refreshAvailabilityCalls)
        assertEquals(0, incapableLocal.refreshAvailabilityCalls)
    }

    @Test
    fun `exact reference binding ignores preference and never falls back to a sibling`() = runTest {
        val local = backend(localId, collectionKey = "local-collection")
        val pc = backend(pcId, collectionKey = "pc-collection")
        val registry = AnkiBackendRegistry(listOf(local, pc))
        val preference = MutableStateFlow<BackendPreference>(BackendPreference.Explicit(pcId))
        val controller = AnkiReadContextController(registry, preference)

        val exact = bound(
            controller.bindReferenceContext(
                backendId = localId,
                collectionKey = "local-collection",
                purpose = "restored_card_details",
                requiredCapabilities = deckRequirement
            )
        )
        assertEquals(localId, exact.context.backendId)
        assertEquals(1, local.refreshAvailabilityCalls)
        assertEquals(0, pc.refreshAvailabilityCalls)

        val missing = controller.bindReferenceContext(
            backendId = AnkiBackendId.PcAgent("not-registered"),
            collectionKey = null,
            purpose = "restored_card_details",
            requiredCapabilities = deckRequirement
        ) as AnkiReadContextBindingResult.Failed
        assertEquals(AnkiReadContextFailureKind.BACKEND_NOT_REGISTERED, missing.failure.kind)
        assertEquals(0, pc.getDecksCalls)
    }

    @Test
    fun `preference race retries before binding but never redirects the returned context`() = runTest {
        val preference = MutableStateFlow<BackendPreference>(BackendPreference.Explicit(localId))
        val local = backend(localId)
        val pc = backend(pcId)
        local.refreshAvailabilityAction = {
            preference.value = BackendPreference.Explicit(pcId)
            refreshAvailabilityAction = {}
        }
        val (controller, _) = controller(listOf(local, pc), preference)

        val binding = bound(controller.bindNewContext(deckRequirement, "library"))
        preference.value = BackendPreference.Explicit(localId)
        val read = AnkiReadContextAccess().read(
            binding = binding,
            requiredCapabilities = deckRequirement,
            operation = { it.getDecks() }
        )

        assertEquals(pcId, binding.context.backendId)
        assertTrue(read is AnkiResult.Success)
        assertEquals(1, local.refreshAvailabilityCalls)
        assertEquals(1, pc.refreshAvailabilityCalls)
        assertEquals(0, local.getDecksCalls)
        assertEquals(1, pc.getDecksCalls)
    }

    @Test
    fun `navigation continuation retains owner generation and rejects a foreign collection`() = runTest {
        val local = backend(localId, collectionKey = "collection-a")
        val (controller, _) = controller(listOf(local))
        val source = bound(controller.bindNewContext(deckRequirement, "library"))

        val continued = bound(
            controller.continueContext(
                sourceContext = source.context,
                resourceBackendId = localId,
                resourceCollectionKey = "collection-a",
                purpose = "deck_details",
                requiredCapabilities = deckRequirement
            )
        )
        assertEquals(source.context, continued.context)
        assertEquals(source.context.generation, continued.context.generation)

        val foreign = controller.continueContext(
            sourceContext = source.context,
            resourceBackendId = localId,
            resourceCollectionKey = "collection-b",
            purpose = "deck_details",
            requiredCapabilities = deckRequirement
        ) as AnkiReadContextBindingResult.Failed
        assertEquals(AnkiReadContextFailureKind.COLLECTION_IDENTITY_MISMATCH, foreign.failure.kind)
        assertEquals("read_context_collection_identity_mismatch",
            (foreign.failure.error as AnkiError.InvalidRequest).detail)
    }

    @Test
    fun `releasing one navigation owner preserves a shared context until its sibling releases`() = runTest {
        val local = backend(localId, collectionKey = "collection-a")
        val (controller, _) = controller(listOf(local))
        val source = bound(controller.bindNewContext(deckRequirement, "library"))
        val child = bound(controller.continueContext(
            sourceContext = source.context,
            resourceBackendId = localId,
            resourceCollectionKey = "collection-a",
            purpose = "deck_details",
            requiredCapabilities = deckRequirement
        ))

        controller.release(source)
        assertTrue(child.isValid)
        assertTrue(AnkiReadContextAccess().read(
            binding = child,
            requiredCapabilities = deckRequirement,
            operation = { it.getDecks() }
        ) is AnkiResult.Success)

        controller.release(child)
        assertTrue(!child.isValid)
    }

    @Test
    fun `resource identity may not lose a known context collection on route handoff`() = runTest {
        val local = backend(localId, collectionKey = "collection-a")
        val (controller, _) = controller(listOf(local))
        val source = bound(controller.bindNewContext(deckRequirement, "library"))

        val result = controller.continueContext(
            sourceContext = source.context,
            resourceBackendId = localId,
            resourceCollectionKey = null,
            purpose = "card_details",
            requiredCapabilities = deckRequirement
        ) as AnkiReadContextBindingResult.Failed
        assertEquals(AnkiReadContextFailureKind.COLLECTION_IDENTITY_UNVERIFIABLE, result.failure.kind)
        assertEquals("read_context_collection_identity_unverifiable",
            (result.failure.error as AnkiError.InvalidRequest).detail)
    }

    @Test
    fun `appeared collection key rejects an in-flight late error`() = runTest {
        val local = backend(localId)
        val (controller, _) = controller(listOf(local))
        val binding = bound(controller.bindNewContext(deckRequirement, "library"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<AnkiResult<List<AnkiDeck>>>()
        val pending = async {
            AnkiReadContextAccess().read(
                binding = binding,
                requiredCapabilities = deckRequirement,
                operation = {
                    entered.complete(Unit)
                    gate.await()
                }
            )
        }
        entered.await()
        local.currentCollectionIdentity = AnkiCollectionIdentity(localId, "appeared-after-bind")
        local.setAvailability(AnkiAvailability.AgentDisconnected)
        gate.complete(AnkiResult.Failure(AnkiError.QueryFailure("obsolete_read")))

        val result = pending.await() as AnkiResult.Failure
        assertEquals(
            "read_context_collection_identity_unverifiable",
            (result.error as AnkiError.InvalidRequest).detail
        )
        assertTrue(!binding.isValid)
    }

    @Test
    fun `appeared collection key rejects an in-flight late result`() = runTest {
        val local = backend(localId)
        val (controller, _) = controller(listOf(local))
        val binding = bound(controller.bindNewContext(deckRequirement, "library"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<AnkiResult<List<AnkiDeck>>>()
        val pending = async {
            AnkiReadContextAccess().read(
                binding = binding,
                requiredCapabilities = deckRequirement,
                operation = {
                    entered.complete(Unit)
                    gate.await()
                }
            )
        }
        entered.await()
        local.currentCollectionIdentity = AnkiCollectionIdentity(localId, "appeared-after-bind")
        gate.complete(AnkiResult.Success(listOf(AnkiDeck(
            AnkiDeckRef(localId, "late-deck", "appeared-after-bind"), "Late"
        ))))

        val result = pending.await() as AnkiResult.Failure
        assertEquals(
            "read_context_collection_identity_unverifiable",
            (result.error as AnkiError.InvalidRequest).detail
        )
        assertTrue(!binding.isValid)
    }

    @Test
    fun `foreign resources in a successful response revoke the owning read context`() = runTest {
        val local = backend(localId, collectionKey = "collection-local")
        val (controller, _) = controller(listOf(local))
        val binding = bound(controller.bindNewContext(deckRequirement, "library"))
        val access = AnkiReadContextAccess()
        val foreignDeck = AnkiDeck(
            AnkiDeckRef(pcId, "same-local-id", "collection-pc"), "Foreign"
        )

        val result = access.read(
            binding = binding,
            requiredCapabilities = deckRequirement,
            operation = { AnkiResult.Success(listOf(foreignDeck)) },
            validateResult = { decks ->
                decks.firstNotNullOfOrNull { deck ->
                    access.validateResource(binding, deck.ref.backendId, deck.ref.collectionKey)
                }
            }
        ) as AnkiResult.Failure

        assertEquals("read_context_backend_mismatch", (result.error as AnkiError.InvalidRequest).detail)
        assertTrue(!binding.isValid)
    }

    @Test
    fun `invalidated in-flight read cannot publish its late failure`() = runTest {
        val local = backend(localId)
        val (controller, _) = controller(listOf(local))
        val binding = bound(controller.bindNewContext(deckRequirement, "library"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<AnkiResult<List<AnkiDeck>>>()
        val pending = async {
            AnkiReadContextAccess().read(
                binding = binding,
                requiredCapabilities = deckRequirement,
                operation = {
                    entered.complete(Unit)
                    gate.await()
                }
            )
        }
        entered.await()
        controller.release(binding)
        gate.complete(AnkiResult.Failure(AnkiError.QueryFailure("obsolete_read")))

        val result = pending.await() as AnkiResult.Failure
        assertEquals("read_context_invalidated", (result.error as AnkiError.InvalidRequest).detail)
    }

    @Test
    fun `invalidated in-flight read cannot publish its late success`() = runTest {
        val local = backend(localId)
        val (controller, _) = controller(listOf(local))
        val binding = bound(controller.bindNewContext(deckRequirement, "library"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<AnkiResult<List<AnkiDeck>>>()
        val pending = async {
            AnkiReadContextAccess().read(
                binding = binding,
                requiredCapabilities = deckRequirement,
                operation = {
                    entered.complete(Unit)
                    gate.await()
                }
            )
        }
        entered.await()
        controller.release(binding)
        gate.complete(AnkiResult.Success(listOf(AnkiDeck(
            AnkiDeckRef(localId, "stale-deck", null), "Stale"
        ))))

        val result = pending.await() as AnkiResult.Failure
        assertEquals("read_context_invalidated", (result.error as AnkiError.InvalidRequest).detail)
    }

    @Test
    fun `registry removal invalidates old runtime binding without using a replacement`() = runTest {
        val original = backend(localId)
        val registry = AnkiBackendRegistry(listOf(original))
        val controller = AnkiReadContextController(registry, MutableStateFlow(BackendPreference.Auto))
        val binding = bound(controller.bindNewContext(deckRequirement, "library"))
        registry.remove(localId)
        registry.register(backend(localId))

        val result = AnkiReadContextAccess().read(
            binding = binding,
            requiredCapabilities = deckRequirement,
            operation = { it.getDecks() }
        )
        assertTrue(result is AnkiResult.Failure)
        assertEquals("read_context_invalidated", ((result as AnkiResult.Failure).error as AnkiError.InvalidRequest).detail)
        assertEquals(0, original.getDecksCalls)
    }

    @Test
    fun `generation is separate from backend and collection identity`() {
        val context = AnkiReadContext.create(AnkiBackendContext(localId, null))
        val anotherGeneration = context.copy(generation = AnkiReadContextGeneration("another-generation"))
        assertEquals(context.backendContext, anotherGeneration.backendContext)
        assertNotEquals(context.generation, anotherGeneration.generation)
        assertNull(context.collectionKey)
    }
}
