package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.DurableReviewerActionLedger
import com.studyagent.client.core.anki.ReviewerActionLedgerCodec
import com.studyagent.client.core.anki.ReviewerActionRecord
import com.studyagent.client.core.anki.ReviewerActionStore
import com.studyagent.client.core.anki.ReviewerActionStoreRead

/**
 * GATE 13 test store: one durable string cell, like the DataStore adapter.
 *
 * "Process death" is modelled by building a *new* [DurableReviewerActionLedger] over the same store
 * — the old ledger's memory is gone and only [snapshot] survives. [durableRecords] decodes what is
 * on "disk" right now, so a test can assert what was persisted **before** a backend call happened
 * (AUDIT 4: durable ordering).
 */
class InMemoryReviewerActionStore(initial: String? = null) : ReviewerActionStore {

    @Volatile var snapshot: String? = initial
        private set

    /** Every successful write, in order (the persistence log). */
    val writes: MutableList<String> = mutableListOf()

    /** Observer for ordering tests: called after a write became durable, before write() returns. */
    @Volatile var onDurableWrite: ((String) -> Unit)? = null

    /** Makes the next N writes report failure without changing [snapshot]. */
    @Volatile var failNextWrites: Int = 0

    /** Makes [read] return typed unreadable, like a corrupt or unreadable file. */
    @Volatile var unreadable: Boolean = false

    var readCount: Int = 0
        private set

    override suspend fun read(): ReviewerActionStoreRead {
        readCount += 1
        if (unreadable) return ReviewerActionStoreRead.Unreadable("simulated_io_failure")
        return ReviewerActionStoreRead.Snapshot(snapshot)
    }

    override suspend fun write(snapshot: String): Boolean {
        if (failNextWrites > 0) {
            failNextWrites -= 1
            return false // simulated disk-full / IO failure: nothing became durable
        }
        this.snapshot = snapshot
        writes += snapshot
        onDurableWrite?.invoke(snapshot)
        return true
    }

    /** Replaces the durable cell directly (corruption / foreign content). */
    fun overwrite(raw: String?) {
        snapshot = raw
    }

    fun durableRecords(): List<ReviewerActionRecord> {
        val raw = snapshot ?: return emptyList()
        return when (val decoded = ReviewerActionLedgerCodec.decode(raw)) {
            is ReviewerActionLedgerCodec.Decoded.Records -> decoded.records
            is ReviewerActionLedgerCodec.Decoded.Unreadable -> error("durable snapshot unreadable: ${decoded.reason}")
        }
    }

    /** A new ledger over the same durable state: what a restarted process would see. */
    fun restart(
        clock: () -> Long,
        maxRecords: Int = DurableReviewerActionLedger.DEFAULT_MAX_RECORDS
    ): DurableReviewerActionLedger = DurableReviewerActionLedger(this, clock, maxRecords)
}
