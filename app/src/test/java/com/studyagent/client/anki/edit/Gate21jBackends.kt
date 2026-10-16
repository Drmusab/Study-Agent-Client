package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver2
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.AnkiOperationClass
import com.studyagent.client.core.anki.AnkiReadContext
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendResolutionRequest2
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.edit.DefaultNoteMutationCoordinator
import com.studyagent.client.core.anki.edit.DefaultNoteMutationLedger
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditSafetyPolicy
import com.studyagent.client.core.anki.edit.NoteMutationCodec
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationIdSource
import com.studyagent.client.core.anki.edit.NoteMutationOutcome
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationSemantics
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationStep

/**
 * GATE 21J — CHECKPOINT 21J-30 fixtures: **two independent fake backends whose note, card, deck and
 * model ids all collide**, plus the counters the brief asks for.
 *
 * ```text
 * Backend A (AnkiDroid)          Backend B (PC agent)
 *   NoteId  = 456                  NoteId  = 456
 *   CardId  = 123 (+124, +125)     CardId  = 123
 *   DeckId  = 10 / 11              DeckId  = 10 / 11
 *   ModelId = 100                  ModelId = 100
 *   fields  Front, Back            fields  Front, Back, Extra
 *   tags    alpha                  tags    bravo
 *   card 123 in deck 10            card 123 in deck 10
 *   collection: no key (AnkiDroid) collection: "coll-b"
 * ```
 *
 * Nothing here is a production type and nothing touches a real collection. The two schemas under one
 * model id are the point: a schema is backend-local, so neither may be used to validate the other's
 * note (INV-21J-07 / VER-21J-08).
 */
object Gate21jIds {
    const val NOTE = "456"
    const val CARD = "123"
    const val SIBLING_CARD = "124"
    const val THIRD_CARD = "125"
    const val DECK = "10"
    const val OTHER_DECK = "11"
    const val FILTERED_DECK = "12"
    const val UNVERIFIABLE_DECK = "13"
    const val MODEL = "100"
    const val COLLECTION_B = "coll-b"
}

/**
 * Counters for one pair of backends, recomputed from what each fake actually received: mutations by
 * backend, note ref, card ref, deck ref, mutation kind and transaction id. Counting from the calls
 * (rather than incrementing inside the fake) means the numbers cannot drift from the real call log.
 */
class NoteMutationCounters {

    val byBackend = LinkedHashMap<AnkiBackendId, Int>()
    val byNoteRef = LinkedHashMap<String, Int>()
    val byCardRef = LinkedHashMap<String, Int>()
    val byDeckRef = LinkedHashMap<String, Int>()
    val byKind = LinkedHashMap<String, Int>()
    val byTransaction = LinkedHashMap<String, Int>()
    val reconciliationsByBackend = LinkedHashMap<AnkiBackendId, Int>()

    var total: Int = 0
        private set

    fun capture(backend: FakeNoteMutationBackend) {
        for (request in backend.applyCalls) {
            total++
            count(byBackend, backend.id)
            count(byNoteRef, request.noteRef.stableKey)
            count(byCardRef, request.cardRef.stableKey)
            count(byKind, request.step::class.simpleName ?: "?")
            count(byTransaction, request.mutationId.value)
            when (val step = request.step) {
                is NoteMutationStep.ChangeDeck -> count(byDeckRef, step.toDeck.stableKey)
                is NoteMutationStep.UpdateNoteContent -> Unit
            }
        }
        reconciliationsByBackend[backend.id] = backend.reconcileCalls.size
    }

    fun capture(pair: Gate21jBackends) {
        byBackend.clear(); byNoteRef.clear(); byCardRef.clear(); byDeckRef.clear()
        byKind.clear(); byTransaction.clear(); reconciliationsByBackend.clear()
        total = 0
        capture(pair.a)
        capture(pair.b)
    }

    fun countFor(backendId: AnkiBackendId): Int = byBackend[backendId] ?: 0

    private fun <K> count(map: MutableMap<K, Int>, key: K) {
        map[key] = (map[key] ?: 0) + 1
    }
}

/**
 * The two colliding backends, in one value.
 *
 * [bReconcilesAuthoritatively] models the *only* backend shape for which the locked §3.3 matrix
 * permits `OP-M3`: a collection key **and** an authoritative reconciliation read. It defaults to
 * `false`, which is every backend that exists in this repository today.
 */
class Gate21jBackends(val bReconcilesAuthoritatively: Boolean = false) {

    val aId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
    val bId: AnkiBackendId = AnkiBackendId.PcAgent("gate21j-b")
    val events: MutableList<String> = mutableListOf()

    /** AnkiDroid-shaped: the real pin offers no authoritative collection discriminator. */
    val a: FakeNoteMutationBackend = FakeNoteMutationBackend(
        backendId = aId,
        events = events,
        noteId = Gate21jIds.NOTE,
        cardId = Gate21jIds.CARD,
        deckIds = listOf(Gate21jIds.DECK, Gate21jIds.OTHER_DECK),
        filteredDeckId = Gate21jIds.FILTERED_DECK,
        unverifiableDeckId = Gate21jIds.UNVERIFIABLE_DECK
    ).apply {
        noteTypeId = Gate21jIds.MODEL
        fields[0] = AnkiNoteField(name = "Front", value = "A front", ordinal = 0)
        fields[1] = AnkiNoteField(name = "Back", value = "A back", ordinal = 1)
        tags = listOf("alpha")
        addSiblingCard(Gate21jIds.SIBLING_CARD, ord = 1, deckId = Gate21jIds.DECK)
        addSiblingCard(Gate21jIds.THIRD_CARD, ord = 2, deckId = Gate21jIds.DECK)
        collectionIdentity = null
    }

    /**
     * A second configured instance. It reports a collection key (so the gate can exercise the
     * `AUTHORITATIVE_*` cells) and its own model schema under the **same** numeric model id.
     */
    val b: FakeNoteMutationBackend = FakeNoteMutationBackend(
        backendId = bId,
        noteMutationSemantics = NoteMutationSemantics.ANKIDROID_V2_24_1.copy(
            authoritativeReconciliation = bReconcilesAuthoritatively
        ),
        events = events,
        noteId = Gate21jIds.NOTE,
        cardId = Gate21jIds.CARD,
        deckIds = listOf(Gate21jIds.DECK, Gate21jIds.OTHER_DECK),
        filteredDeckId = Gate21jIds.FILTERED_DECK,
        unverifiableDeckId = Gate21jIds.UNVERIFIABLE_DECK
    ).apply {
        noteTypeId = Gate21jIds.MODEL
        fields[0] = AnkiNoteField(name = "Front", value = "B front", ordinal = 0)
        fields[1] = AnkiNoteField(name = "Back", value = "B back", ordinal = 1)
        fields += AnkiNoteField(name = "Extra", value = "B extra", ordinal = 2)
        tags = listOf("bravo")
        collectionIdentity = AnkiCollectionIdentity(bId, Gate21jIds.COLLECTION_B)
    }

    val counters = NoteMutationCounters()

    fun captureCounters() {
        counters.capture(this)
    }

    fun all(): List<AnkiBackend> = listOf(a, b)
}

/**
 * One process's world for the note-edit gate: a live registry, one durable store, one coordinator,
 * the global preference and the read contexts. Everything the gate needs to model a preference
 * change, a read-context switch, a restart or a backend removal without touching production state.
 */
class Gate21jWorld(val pair: Gate21jBackends = Gate21jBackends()) {

    val registry = AnkiBackendRegistry(pair.all())
    val store = InMemoryNoteMutationStore(pair.events)

    /** The global setting GATE 21E owns. Changes are observable, and they must change nothing here. */
    var preference: BackendPreference = BackendPreference.Explicit(pair.aId)

    /** The Library/Browser read context currently bound (GATE 21F), replaced to model a switch. */
    var readContext: AnkiReadContext =
        AnkiReadContext.create(AnkiBackendContext(pair.aId, pair.a.currentCollectionIdentity))

    private var counter = 0

    var nowEpochMs: Long = 1_000L

    var coordinator = build()

    fun build(): DefaultNoteMutationCoordinator = DefaultNoteMutationCoordinator(
        registry,
        DefaultNoteMutationLedger(store, nowEpochMs = { nowEpochMs }),
        NoteEditSafetyPolicy { null },
        NoteMutationIdSource { NoteMutationId("m-${++counter}") },
        nowEpochMs = { nowEpochMs }
    )

    /** Process death and restart: a new coordinator over the same bytes, with the same registry. */
    fun restart(): DefaultNoteMutationCoordinator {
        nowEpochMs += 1_000L
        coordinator = build()
        return coordinator
    }

    /** What the GATE 21E resolver would answer right now, from the live descriptors only. */
    fun resolved(): BackendResolutionResult2 = AnkiBackendResolver2.resolve(
        BackendResolutionRequest2(preference, emptySet(), purpose = "gate21j-test"),
        registry.descriptors()
    )

    /** A read context switch: a NEW logical context with a new generation (GATE 21F). */
    fun switchReadContextTo(backendId: AnkiBackendId, collection: AnkiCollectionIdentity?) {
        readContext = AnkiReadContext.create(AnkiBackendContext(backendId, collection))
    }

    /** The editable base of A's note, exactly as the edit route would build it. */
    fun base(): NoteEditBase = NoteEditBase.from(pair.a.details())!!

    fun descriptorsOfOthers(than: AnkiBackendId): List<AnkiBackendDescriptor> =
        registry.descriptors().filter { it.id != than }

    fun persistedStatus(id: NoteMutationId): NoteMutationStatus? {
        val text = store.encoded ?: return null
        return NoteMutationCodec.decode(text)?.firstOrNull { it.mutationId == id }?.status
    }

    fun persistedRecord(id: NoteMutationId): NoteMutationRecord? {
        val text = store.encoded ?: return null
        return NoteMutationCodec.decode(text)?.firstOrNull { it.mutationId == id }
    }

    /** Every operation class the note family uses, so a matrix sweep cannot silently omit one. */
    val operationClasses: List<AnkiOperationClass> = listOf(
        AnkiOperationClass.OP_M1,
        AnkiOperationClass.OP_M2,
        AnkiOperationClass.OP_M3,
        AnkiOperationClass.OP_R2
    )
}

/** The record carried by an outcome, when it carries one. */
fun NoteMutationOutcome.recordOrNull(): NoteMutationRecord? = when (this) {
    is NoteMutationOutcome.Applied -> record
    is NoteMutationOutcome.Conflict -> record
    is NoteMutationOutcome.RetryAvailable -> record
    is NoteMutationOutcome.VerificationRequired -> record
    is NoteMutationOutcome.ReadFailed -> record
    is NoteMutationOutcome.ActiveMutationExists -> record
    else -> null
}
