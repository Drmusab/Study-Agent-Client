package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.ReviewCommitLedger
import com.studyagent.client.core.anki.ReviewCommitLedgerCodec
import com.studyagent.client.core.anki.ReviewCommitRecord
import com.studyagent.client.core.anki.ReviewCommitStore
import com.studyagent.client.core.anki.ReviewCommitStoreRead

/**
 * GATE 11 test store: one durable string cell, like the DataStore adapter.
 *
 * "Process death" is modelled by building a *new* [ReviewCommitLedger] over the same store — the
 * old ledger's memory is gone, only [snapshot] survives. [durableRecords] decodes what is on
 * "disk" right now, so a test can assert what was persisted *before* a backend call happened.
 */
class InMemoryReviewCommitStore(initial: String? = null) : ReviewCommitStore {
    @Volatile var snapshot: String? = initial
        private set

    /** Every successful write, in order (the persistence log). */
    val writes: MutableList<String> = mutableListOf()

    /** Makes the next N writes throw (disk full / IO error) without changing [snapshot]. */
    /** Observer for ordering tests: called after a write became durable, before write() returns. */
    @Volatile var onDurableWrite: ((String) -> Unit)? = null
    @Volatile var failNextWrites: Int = 0

    /** Makes [read] throw, like a corrupt or unreadable file. */
    @Volatile var unreadable: Boolean = false

    var readCount: Int = 0
        private set

    override suspend fun read(): ReviewCommitStoreRead {
        readCount += 1
        if (unreadable) return ReviewCommitStoreRead.Unreadable("simulated_io_failure")
        return ReviewCommitStoreRead.Snapshot(snapshot)
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

    fun durableRecords(): List<ReviewCommitRecord> {
        val raw = snapshot ?: return emptyList()
        return when (val decoded = ReviewCommitLedgerCodec.decode(raw)) {
            is ReviewCommitLedgerCodec.Decoded.Records -> decoded.records
            is ReviewCommitLedgerCodec.Decoded.Unreadable -> error("durable snapshot unreadable: ${decoded.reason}")
        }
    }

    /** A new ledger over the same durable state: what a restarted process would see. */
    fun restart(clock: () -> Long, maxRecords: Int = ReviewCommitLedger.DEFAULT_MAX_RECORDS): ReviewCommitLedger =
        ReviewCommitLedger(this, clock, maxRecords)
}
