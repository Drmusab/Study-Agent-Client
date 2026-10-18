package com.studyagent.client.data.repository

import com.studyagent.client.core.anki.AnkiTransactionFamily
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionFact
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionRead
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionReader
import com.studyagent.client.core.anki.ReviewCommitRecord
import com.studyagent.client.core.anki.ReviewerActionRecord
import com.studyagent.client.core.anki.create.CreationLedgerResult
import com.studyagent.client.core.anki.create.NoteCreationRecord
import com.studyagent.client.core.anki.edit.NoteLedgerResult
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import kotlinx.coroutines.CancellationException

/**
 * GATE 21M — the production read of the four durable families' existing `unresolved()` projections
 * (CHECKPOINT 21M-17).
 *
 * It is constructed with **read lambdas only**, so the class cannot write even by accident: no ledger,
 * store, backend or coordinator is reachable from here. Reading a ledger's unresolved projection is a
 * local durable read — it never touches Anki, never probes a backend and never changes a status.
 *
 * Failure honesty (AUDIT-21M-R2-14):
 *
 * - a family with no projection supplied is reported in `notProjectedFamilies`;
 * - a family whose read was refused or whose store was unreadable is reported in
 *   `unavailableFamilies`;
 * - neither is ever reduced to a count of zero, and no exception is swallowed into "all clear".
 *
 * Records are reduced to family + owner identity + the family's own status token, so nothing a
 * troubleshooting surface must not carry (content, ids, keys) can reach one.
 */
class LedgerUnresolvedTransactionReader(
    private val reviewCommits: (suspend () -> List<ReviewCommitRecord>)? = null,
    private val reviewerActions: (suspend () -> List<ReviewerActionRecord>)? = null,
    private val noteMutations: (suspend () -> NoteLedgerResult<List<NoteMutationRecord>>)? = null,
    private val noteCreations: (suspend () -> CreationLedgerResult<List<NoteCreationRecord>>)? = null
) : AnkiUnresolvedTransactionReader {

    override suspend fun read(): AnkiUnresolvedTransactionRead {
        val facts = mutableListOf<AnkiUnresolvedTransactionFact>()
        val unavailable = mutableSetOf<AnkiTransactionFamily>()
        val notProjected = mutableSetOf<AnkiTransactionFamily>()

        readFamily(
            family = AnkiTransactionFamily.REVIEW_COMMIT,
            reader = reviewCommits,
            facts = facts,
            unavailable = unavailable,
            notProjected = notProjected
        ) { records -> records.map { it to it.status.name } }

        readFamily(
            family = AnkiTransactionFamily.REVIEWER_ACTION,
            reader = reviewerActions,
            facts = facts,
            unavailable = unavailable,
            notProjected = notProjected
        ) { records -> records.map { it to it.status.name } }

        readFamily(
            family = AnkiTransactionFamily.NOTE_MUTATION,
            reader = noteMutations?.let { read ->
                {
                    when (val result = read()) {
                        is NoteLedgerResult.Ok -> result.value
                        // Refused or unreadable: report it, never render it as "nothing unresolved".
                        is NoteLedgerResult.Rejected, is NoteLedgerResult.Unavailable -> null
                    }
                }
            },
            facts = facts,
            unavailable = unavailable,
            notProjected = notProjected
        ) { records -> records.map { it to it.status.name } }

        readFamily(
            family = AnkiTransactionFamily.NOTE_CREATION,
            reader = noteCreations?.let { read ->
                {
                    when (val result = read()) {
                        is CreationLedgerResult.Ok -> result.value
                        is CreationLedgerResult.Rejected, is CreationLedgerResult.Unavailable -> null
                    }
                }
            },
            facts = facts,
            unavailable = unavailable,
            notProjected = notProjected
        ) { records -> records.map { it to it.status.name } }

        // `loaded` is true when at least one family was really read. A surface given no projection at
        // all must say so instead of implying "0 everywhere".
        return AnkiUnresolvedTransactionRead(
            facts = facts.toList(),
            loaded = notProjected.size < AnkiTransactionFamily.entries.size,
            unavailableFamilies = unavailable.toSet(),
            notProjectedFamilies = notProjected.toSet()
        )
    }

    private suspend fun <T : Any> readFamily(
        family: AnkiTransactionFamily,
        reader: (suspend () -> List<T>?)?,
        facts: MutableList<AnkiUnresolvedTransactionFact>,
        unavailable: MutableSet<AnkiTransactionFamily>,
        notProjected: MutableSet<AnkiTransactionFamily>,
        reduce: (List<T>) -> List<Pair<T, String>>
    ) {
        if (reader == null) {
            notProjected += family
            return
        }
        val records = try {
            reader()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // A failed durable read is a fact about the read, not evidence that nothing is unresolved.
            null
        }
        if (records == null) {
            unavailable += family
            return
        }
        reduce(records).forEach { (record, statusToken) ->
            facts += AnkiUnresolvedTransactionFact(
                family = family,
                backendId = ownerOf(record),
                statusToken = statusToken
            )
        }
    }

    /** The owner each record was bound to when it was written — never re-derived from any preference. */
    private fun <T : Any> ownerOf(record: T): com.studyagent.client.core.anki.AnkiBackendId? = when (record) {
        is ReviewCommitRecord -> record.backendId
        is ReviewerActionRecord -> record.backendId
        is NoteMutationRecord -> record.backendId
        is NoteCreationRecord -> record.backendId
        else -> null
    }
}
