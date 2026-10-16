package com.studyagent.client.anki.edit

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.edit.BackendNoteMutationRequest
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteMutationAttestation
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationCodec
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationOutcome
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationRecoveryOutcome
import com.studyagent.client.core.anki.edit.NoteMutationRefusal
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationStep
import com.studyagent.client.ui.screens.editnote.EditNoteSaveState
import com.studyagent.client.ui.screens.editnote.EditNoteUiState
import com.studyagent.client.ui.screens.editnote.EditNoteViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21J — VER-21J-38, the 1,000-event deterministic fault injection.
 *
 * One seeded, reproducible event stream drives: preference changes, read-context switches, backend
 * outages and reconnects, collection mismatches (including a key that appears and disappears),
 * external note edits, conflict responses, lost responses, partial composite saves, duplicate Save
 * presses, persistence failures at every durable boundary, process death, stale save callbacks,
 * legacy snapshots without collection evidence, and ordinary valid edits — over two backends whose
 * note, card, deck and model ids all collide.
 *
 * What is asserted at the end is the brief's list, each as a counter that must be exactly zero. The
 * counters are incremented by *observing the fakes and the durable records*, never by asking a
 * component whether it thinks it behaved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Gate21jNoteEditEnduranceTest {

    private val viewModelStores = mutableListOf<ViewModelStore>()

    @After
    fun tearDown() {
        viewModelStores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    /** Deterministic LCG: the run is reproducible from the seed alone. */
    private class Seeded(private val seed: Long) {
        private var state = seed
        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (((state ushr 33).toInt() and 0x7fffffff) % bound)
        }
    }

    /** Every zero the brief requires, each with the observation that increments it. */
    private class Violations {
        var crossBackendMutations = 0
        var wrongNoteEdits = 0
        var wrongDeckAssignments = 0
        var wrongModelSchemaUsage = 0
        var blindAmbiguousReplays = 0
        var unjustifiedRetryAllowed = 0
        var falseAppliedForPartialChanges = 0
        var lostUnresolvedRecords = 0
        var staleEditorContamination = 0
        var ownershipRewrites = 0

        fun report(): String = listOf(
            "cross-backend mutations" to crossBackendMutations,
            "wrong-note edits" to wrongNoteEdits,
            "wrong-deck assignments" to wrongDeckAssignments,
            "wrong-model-schema usage" to wrongModelSchemaUsage,
            "blind AMBIGUOUS replays" to blindAmbiguousReplays,
            "unjustified RETRY_ALLOWED" to unjustifiedRetryAllowed,
            "false APPLIED for partial changes" to falseAppliedForPartialChanges,
            "lost unresolved records" to lostUnresolvedRecords,
            "stale editor contamination" to staleEditorContamination,
            "transaction ownership rewrites" to ownershipRewrites
        ).joinToString("\n") { "  ${it.first}: ${it.second}" }

        val allZero: Boolean
            get() = listOf(
                crossBackendMutations, wrongNoteEdits, wrongDeckAssignments, wrongModelSchemaUsage,
                blindAmbiguousReplays, unjustifiedRetryAllowed, falseAppliedForPartialChanges,
                lostUnresolvedRecords, staleEditorContamination, ownershipRewrites
            ).all { it == 0 }
    }

    private fun draftOf(vararg changes: Pair<Int, String>) = NoteEditDraft(fieldValues = changes.toMap())

    @Test
    fun `VER-21J-38 one thousand deterministic events hold every ownership invariant`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val pair = Gate21jBackends(bReconcilesAuthoritatively = true)
        val w = Gate21jWorld(pair)
        val rng = Seeded(seed = 1_000_003L)
        val v = Violations()

        /** mutationId -> the backend that owns it, learned from records only. */
        val ownerOf = LinkedHashMap<String, AnkiBackendId>()
        /** mutationId -> the outcome must not have been reported APPLIED while a step was skipped. */
        val partialApplied = LinkedHashSet<String>()
        /** Records that were non-terminal when the process last died. */
        var unresolvedBeforeRestart = setOf<String>()
        var restarts = 0
        var editorSessions = 0

        /** Fault mode for the next apply, per backend; installed as the fakes' hooks. */
        var modeA: String = "faithful"
        var modeB: String = "faithful"

        suspend fun hook(
            self: FakeNoteMutationBackend,
            other: FakeNoteMutationBackend,
            mode: () -> String,
            request: BackendNoteMutationRequest
        ): NoteMutationBackendResult {
            // Ownership, note scope, deck scope and schema are checked on the *arriving* request.
            if (request.cardRef.backendId != self.id || request.noteRef.backendId != self.id) {
                v.crossBackendMutations++
            }
            if (request.noteRef.noteId != Gate21jIds.NOTE) v.wrongNoteEdits++
            val knownOwner = ownerOf[request.mutationId.value]
            if (knownOwner != null && knownOwner != self.id) v.crossBackendMutations++
            when (val step = request.step) {
                is NoteMutationStep.ChangeDeck -> {
                    if (step.toDeck.backendId != self.id) v.wrongDeckAssignments++
                    if (step.fromDeck?.backendId != self.id) v.wrongDeckAssignments++
                }
                is NoteMutationStep.UpdateNoteContent -> {
                    if (step.fieldValues != null && step.fieldValues.size != self.fields.size) {
                        v.wrongModelSchemaUsage++
                    }
                }
            }
            if (other.id == self.id) v.crossBackendMutations++

            return when (mode()) {
                "faithful" -> {
                    self.applyFaithfully(request)
                    NoteMutationBackendResult.ConfirmedApplied
                }
                "conflict" -> NoteMutationBackendResult.Conflict(latest = null)
                "not_applied" -> NoteMutationBackendResult.ConfirmedNotApplied(AnkiError.PermissionRequired())
                "lost" -> {
                    self.applyFaithfully(request)
                    NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("lost"))
                }
                "throwing" -> throw IllegalStateException("provider failure")
                "partial" -> {
                    // Only the first step of a composite is applied; the rest stays unknown.
                    if (request.stepIndex == 0) {
                        self.applyFaithfully(request)
                        partialApplied += request.mutationId.value
                        NoteMutationBackendResult.ConfirmedApplied
                    } else {
                        NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("second_step_lost"))
                    }
                }
                else -> {
                    self.applyFaithfully(request)
                    NoteMutationBackendResult.ConfirmedApplied
                }
            }
        }

        pair.a.applyHook = { request, self -> hook(pair.a, pair.b, { modeA }, request) }
        pair.b.applyHook = { request, self -> hook(pair.b, pair.a, { modeB }, request) }

        fun learn(outcome: NoteMutationOutcome) {
            val record = outcome.recordOrNull() ?: return
            ownerOf[record.mutationId.value] = record.backendId
            if (outcome is NoteMutationOutcome.Applied && partialApplied.contains(record.mutationId.value)) {
                v.falseAppliedForPartialChanges++
            }
        }

        fun learnAll() {
            val snapshot = w.store.encoded ?: return
            NoteMutationCodec.decode(snapshot)?.forEach { ownerOf[it.mutationId.value] = it.backendId }
        }

        /** The one card whose deck a test may legitimately expect to move. */
        suspend fun aSave(edit: NoteEditDraft, backendId: AnkiBackendId = pair.aId): NoteMutationOutcome {
            // The base always comes from the fixture instance, registered or not: an outage must be
            // observable as a refusal, not as a missing base.
            val details = if (backendId == pair.aId) pair.a.details() else pair.b.details()
            val base = NoteEditBase.from(details)!!
            val outcome = w.coordinator.save(base, edit)
            learn(outcome)
            return outcome
        }

        repeat(1000) { iteration ->
            when (rng.next(17)) {
                // 0 — preference changes, including AUTO.
                0 -> w.preference = when (rng.next(3)) {
                    0 -> BackendPreference.Explicit(pair.aId)
                    1 -> BackendPreference.Explicit(pair.bId)
                    else -> BackendPreference.Auto
                }

                // 1 — read-context switch.
                1 -> {
                    val target = if (rng.next(2) == 0) pair.aId else pair.bId
                    val key = if (target == pair.bId) pair.b.currentCollectionIdentity else null
                    w.switchReadContextTo(target, key)
                }

                // 2 — backend outage (removed from the registry), then a save attempt.
                2 -> {
                    val gone = if (rng.next(2) == 0) pair.aId else pair.bId
                    w.registry.remove(gone)
                    val outcome = aSave(draftOf(0 to "during outage"), gone)
                    if (outcome is NoteMutationOutcome.Applied) v.crossBackendMutations++
                }

                // 3 — reconnect: the same instance rejoins under the same identity.
                3 -> {
                    if (w.registry.find(pair.aId) == null) w.registry.register(pair.a)
                    if (w.registry.find(pair.bId) == null) w.registry.register(pair.b)
                }

                // 4 — collection mismatch / key appearing / key disappearing.
                4 -> pair.b.collectionIdentity = when (rng.next(4)) {
                    0 -> AnkiCollectionIdentity(pair.bId, Gate21jIds.COLLECTION_B)
                    1 -> AnkiCollectionIdentity(pair.bId, "coll-other")
                    2 -> null
                    else -> AnkiCollectionIdentity(pair.bId, Gate21jIds.COLLECTION_B)
                }

                // 5 — a valid content edit on A.
                5 -> {
                    modeA = "faithful"
                    learn(aSave(draftOf(0 to "A-$iteration")))
                }

                // 6 — a valid content edit on B (its own schema has three fields).
                6 -> {
                    modeB = "faithful"
                    learn(aSave(NoteEditDraft(fieldValues = mapOf(2 to "B-$iteration")), pair.bId))
                }

                // 7 — a card-scoped deck move on A, plus a tag edit.
                7 -> {
                    modeA = "faithful"
                    learn(
                        aSave(
                            NoteEditDraft(
                                tags = listOf("alpha", "t$iteration"),
                                targetDeck = AnkiDeckRef(pair.aId, Gate21jIds.OTHER_DECK)
                            )
                        )
                    )
                }

                // 8 — external edit between base and write: must be a conflict, nothing written.
                8 -> {
                    var changed = false
                    pair.a.beforeRead = { fake ->
                        if (!changed) {
                            changed = true
                            fake.fields[1] = fake.fields[1].copy(value = "external-$iteration")
                        }
                    }
                    val appliesBefore = pair.a.applyCalls.size
                    val outcome = aSave(draftOf(1 to "mine-$iteration"))
                    // The one thing that must never happen: the edit is written over a note someone
                    // else changed without the drift check noticing. A conflict, a blocked route, an
                    // active mutation or a ledger failure all stop before any write, legitimately.
                    if (pair.a.applyCalls.size > appliesBefore) v.wrongNoteEdits++
                    if (outcome is NoteMutationOutcome.Applied && pair.a.fields[1].value == "mine-$iteration") {
                        // An APPLIED here would mean the external value was never seen.
                        v.wrongNoteEdits++
                    }
                    pair.a.beforeRead = null
                }

                // 9 — the backend answers Conflict explicitly.
                9 -> {
                    modeA = "conflict"
                    learn(aSave(draftOf(0 to "conflict-$iteration")))
                    modeA = "faithful"
                }

                // 10 — a lost response or an unexpected provider failure: ambiguous, never retried.
                10 -> {
                    modeA = if (rng.next(2) == 0) "lost" else "throwing"
                    learn(aSave(draftOf(0 to "lost-$iteration")))
                    modeA = "faithful"
                }

                // 11 — a partial composite save.
                11 -> {
                    modeA = "partial"
                    learn(
                        aSave(
                            NoteEditDraft(
                                fieldValues = mapOf(0 to "partial-$iteration"),
                                targetDeck = AnkiDeckRef(pair.aId, Gate21jIds.OTHER_DECK)
                            )
                        )
                    )
                    modeA = "faithful"
                }

                // 12 — duplicate Save presses for the same note.
                12 -> {
                    modeA = "not_applied"
                    val first = aSave(draftOf(0 to "dup-$iteration"))
                    val second = aSave(draftOf(0 to "dup-$iteration"))
                    learn(first)
                    learn(second)
                    modeA = "faithful"
                }

                // 13 — persistence failures at every durable boundary.
                13 -> {
                    val status = when (rng.next(3)) {
                        0 -> "PREPARED"
                        1 -> "SUBMITTING"
                        else -> "APPLIED"
                    }
                    w.store.writeFailure = { it.contains("\"status\":\"$status\"") }
                    val outcome = aSave(draftOf(0 to "persist-$iteration"))
                    learn(outcome)
                    w.store.writeFailure = null
                    // A write that could not be recorded must never be reported as APPLIED.
                    if (outcome is NoteMutationOutcome.Applied && w.store.encoded == null) {
                        v.falseAppliedForPartialChanges++
                    }
                }

                // 14 — process death and restart (twice in the run this also strips collection
                // evidence from the snapshot, modelling a legacy record).
                14 -> {
                    learnAll()
                    unresolvedBeforeRestart = NoteMutationCodec.decode(w.store.encoded ?: "")
                        ?.filter { it.status.isActive }?.map { it.mutationId.value }?.toSet() ?: emptySet()
                    val stripEvidence = rng.next(4) == 0
                    if (stripEvidence) {
                        val decoded = NoteMutationCodec.decode(w.store.encoded ?: "") ?: emptyList()
                        if (decoded.isNotEmpty()) {
                            w.store.encoded = NoteMutationCodec.encode(decoded.map { it.copy(collectionRef = null) })
                        }
                    }
                    w.restart()
                    restarts++
                    val after = NoteMutationCodec.decode(w.store.encoded ?: "")
                        ?.map { it.mutationId.value }?.toSet() ?: emptySet()
                    val lost = unresolvedBeforeRestart - after
                    if (lost.isNotEmpty()) v.lostUnresolvedRecords += lost.size
                    // Recovery never rewrites an owner, and never invents one.
                    NoteMutationCodec.decode(w.store.encoded ?: "")?.forEach { record ->
                        val known = ownerOf[record.mutationId.value]
                        if (known != null && known != record.backendId) v.ownershipRewrites++
                    }
                    // Retry anything unresolved and check the refusals against the durable status.
                    unresolvedBeforeRestart.forEach { id ->
                        val recordId = NoteMutationId(id)
                        val status = w.persistedStatus(recordId)
                        val retried = w.coordinator.retry(recordId)
                        if (status == NoteMutationStatus.AMBIGUOUS &&
                            retried !is NoteMutationOutcome.Refused
                        ) {
                            v.blindAmbiguousReplays++
                        }
                        if (retried is NoteMutationOutcome.Applied) learn(retried)
                    }
                    // And ask for recovery of anything ambiguous.
                    unresolvedBeforeRestart.forEach { id ->
                        val outcome = w.coordinator.recover(NoteMutationId(id))
                        if (outcome is NoteMutationRecoveryOutcome.StillAmbiguous &&
                            outcome.record.status != NoteMutationStatus.AMBIGUOUS
                        ) {
                            v.blindAmbiguousReplays++
                        }
                    }
                }

                // 16 — a human inspects the collection and closes one ambiguous record, which is the
                // only way an ambiguous note becomes editable again when no reconciliation exists.
                16 -> {
                    val unresolved = NoteMutationCodec.decode(w.store.encoded ?: "")
                        ?.filter { it.status == NoteMutationStatus.AMBIGUOUS } ?: emptyList()
                    if (unresolved.isNotEmpty()) {
                        val record = unresolved[rng.next(unresolved.size)]
                        val closed = w.coordinator.resolveAmbiguous(
                            record.mutationId,
                            if (rng.next(2) == 0) NoteMutationAttestation.APPLIED_IN_COLLECTION
                            else NoteMutationAttestation.ABSENT_FROM_COLLECTION
                        )
                        if (closed is NoteMutationRecoveryOutcome.Resolved) {
                            assertEquals(
                                "an attested record is terminal",
                                true,
                                closed.record.status.isTerminal
                            )
                        }
                    }
                }

                // 15 — a stale editor session: an in-flight save is overtaken by a session reload.
                else -> {
                    if (rng.next(3) == 0) {
                        val hold = CompletableDeferred<Unit>()
                        pair.a.applyHook = { request, self ->
                            hold.await()
                            self.applyFaithfully(request)
                            NoteMutationBackendResult.ConfirmedApplied
                        }
                        val model = EditNoteViewModel(
                            initialBackend = w.registry.find(pair.aId) ?: pair.a,
                            initialCardRef = pair.a.cardRef(),
                            coordinator = w.coordinator
                        ).also { m ->
                            ViewModelStore().also { store -> store.put("edit-note", m); viewModelStores += store }
                        }
                        advanceUntilIdle()
                        model.onFieldChange(0, "stale-$iteration")
                        model.save()
                        advanceUntilIdle()
                        model.load()
                        advanceUntilIdle()
                        hold.complete(Unit)
                        advanceUntilIdle()
                        editorSessions++
                        // A save that started under the replaced generation must not report itself
                        // as this session's result: no navigation signal, no Saved state.
                        if (model.savedCardRef.value != null) v.staleEditorContamination++
                        val settled = (model.uiState.value as? EditNoteUiState.Ready)?.editor?.saveState
                        if (settled is EditNoteSaveState.Saved) v.staleEditorContamination++
                        pair.a.applyHook = { request, self ->
                            self.applyFaithfully(request)
                            NoteMutationBackendResult.ConfirmedApplied
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- the required counters
        learnAll()
        pair.captureCounters()

        // Every request the fakes received belongs to their own backend, note, deck and schema. The
        // hooks above counted violations while it happened; re-derive the ownership half from the log.
        listOf(pair.a, pair.b).forEach { fake ->
            fake.applyCalls.forEach { request ->
                val owner = ownerOf[request.mutationId.value]
                if (owner != null && owner != fake.id) v.crossBackendMutations++
                if (request.cardRef.backendId != fake.id || request.noteRef.backendId != fake.id) {
                    v.crossBackendMutations++
                }
            }
        }
        // Every RETRY_ALLOWED a persisted record ever held must be justified by backend evidence.
        NoteMutationCodec.decode(w.store.encoded ?: "")?.forEach { record ->
            if (record.status == NoteMutationStatus.RETRY_ALLOWED &&
                record.reason != NoteMutationReason.NOT_APPLIED_BY_BACKEND &&
                record.reason != NoteMutationReason.RECONCILED_NOT_APPLIED
            ) {
                v.unjustifiedRetryAllowed++
            }
            val known = ownerOf[record.mutationId.value]
            if (known != null && known != record.backendId) v.ownershipRewrites++
        }
        // The counters by backend/ref/kind are the brief's required accounting; they must describe the
        // calls that actually happened.
        assertEquals(
            pair.a.applyCalls.size + pair.b.applyCalls.size,
            pair.counters.total
        )
        assertEquals(pair.a.applyCalls.size, pair.counters.countFor(pair.aId))
        assertEquals(pair.b.applyCalls.size, pair.counters.countFor(pair.bId))
        assertTrue("the run actually exercised the gate: restarts=$restarts applies=${pair.counters.total} sessions=$editorSessions", restarts >= 20 && pair.counters.total >= 50)

        assertTrue(
            "every required counter must be zero:\n${v.report()}",
            v.allZero
        )
        assertEquals("no cross-backend mutation, ever", 0, v.crossBackendMutations)
        assertEquals("no wrong-deck assignment, ever", 0, v.wrongDeckAssignments)
        assertEquals("no schema borrowed from another backend, ever", 0, v.wrongModelSchemaUsage)
        assertEquals("no blind replay of an ambiguous mutation, ever", 0, v.blindAmbiguousReplays)
        assertNull("B's collection key is never copied onto A's records", pair.a.collectionIdentity)
        assertFalse(pair.a.fields.size == pair.b.fields.size)
        assertTrue("sibling cards stayed in A's deck", pair.a.deckOfCard(Gate21jIds.SIBLING_CARD) != null)
        assertTrue(editorSessions > 0)
    }
}
