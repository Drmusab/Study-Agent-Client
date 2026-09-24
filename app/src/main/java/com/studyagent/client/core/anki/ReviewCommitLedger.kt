package com.studyagent.client.core.anki

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GATE 11 — durable persistence port for the [ReviewCommitLedger].
 *
 * A store is one durable string cell. [write] must replace the snapshot atomically (all-or-nothing)
 * and answers `true` only once the new snapshot is durable. [read] answers
 * [ReviewCommitStoreRead.Unreadable] when the storage exists but cannot be read — never an empty
 * snapshot for a corrupt file, because an empty ledger would silently forget AMBIGUOUS commits.
 *
 * Backend-neutral on purpose: no ContentResolver, Cursor or FlashCardsContract anywhere near it.
 */
interface ReviewCommitStore {
    suspend fun read(): ReviewCommitStoreRead

    /** Atomically replaces the snapshot. `true` only once the new snapshot is durable. */
    suspend fun write(snapshot: String): Boolean
}

/** Typed store read: adapters translate their own IO failures, so the domain never sees them. */
sealed interface ReviewCommitStoreRead {
    /** The last durable snapshot; `null` = nothing was ever written. */
    data class Snapshot(val value: String?) : ReviewCommitStoreRead

    /** The storage exists but cannot be read (corrupt, IO failure). Never "empty". */
    data class Unreadable(val reason: String) : ReviewCommitStoreRead
}

/** Versioned JSON envelope. Unknown versions and malformed content are *unreadable*, never empty. */
object ReviewCommitLedgerCodec {
    const val SCHEMA_VERSION = 1

    @Serializable
    private data class Envelope(val schemaVersion: Int, val records: List<ReviewCommitRecord>)

    private val json = Json { encodeDefaults = true }

    sealed interface Decoded {
        data class Records(val records: List<ReviewCommitRecord>) : Decoded
        data class Unreadable(val reason: String) : Decoded
    }

    fun encode(records: Collection<ReviewCommitRecord>): String =
        json.encodeToString(Envelope.serializer(), Envelope(SCHEMA_VERSION, records.toList()))

    fun decode(snapshot: String): Decoded {
        // Malformed JSON, or a record whose invariants no longer hold: unreadable, never empty.
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), snapshot) }.getOrNull()
            ?: return Decoded.Unreadable("malformed_snapshot")
        if (envelope.schemaVersion != SCHEMA_VERSION) {
            return Decoded.Unreadable("unsupported_schema_${envelope.schemaVersion}")
        }
        val keys = envelope.records.map { it.commitId.stableKey }
        if (keys.distinct().size != keys.size) return Decoded.Unreadable("duplicate_commit_ids")
        return Decoded.Records(envelope.records)
    }
}

/** What the ledger found when it was first loaded in this process (STEP 97-§101). */
data class ReviewCommitRecoveryReport(
    /** SUBMITTING records left by a previous process, now AMBIGUOUS. */
    val interruptedSubmissions: Int = 0,
    /** NOT_STARTED records: provably never dispatched, restorable with the same identity. */
    val restorable: Int = 0,
    /** AMBIGUOUS records the user has not resolved or dismissed. */
    val unresolvedAmbiguous: Int = 0,
    val committed: Int = 0,
    val failed: Int = 0
)

/**
 * GATE 11 — the durable, backend-neutral review-commit ledger (STEP 14-§17).
 *
 * Guarantees, and how each is enforced:
 *
 * - **Atomic compare-and-set.** Every transition runs under one [Mutex] and checks the current
 *   state first; e.g. only one caller can move NOT_STARTED → SUBMITTING, so duplicate effects,
 *   double taps and concurrent coroutines produce at most one [ClaimResult.Claimed] (INV: one
 *   physical dispatch per claim).
 * - **Persist before proceed.** NOT_STARTED and SUBMITTING are written durably *before* the
 *   operation reports success, so the backend is never called for a commit whose SUBMITTING marker
 *   is not on disk. Writes run in [NonCancellable] once started, keeping memory and disk identical.
 * - **Process death.** The first load in a process converts every SUBMITTING record to AMBIGUOUS
 *   ("may have been applied") and persists that; NOT_STARTED stays restorable; COMMITTED is kept so
 *   a replayed effect answers from the ledger instead of calling the backend again.
 * - **Bounded retention.** At most [maxRecords]. Only resolved, safe-to-forget records are pruned
 *   (oldest first); SUBMITTING and unacknowledged AMBIGUOUS records are never dropped. A ledger
 *   that cannot make room fails closed ([PrepareResult.Full]) instead of evicting evidence.
 * - **Fail closed.** An unreadable snapshot makes the ledger [Health.Unavailable]: commits are
 *   refused before dispatch, and the unreadable snapshot is never overwritten by an empty one.
 */
class ReviewCommitLedger(
    private val store: ReviewCommitStore,
    private val clock: () -> Long,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS
) {
    init { require(maxRecords > PROTECTED_RECENT_RECORDS) }

    sealed interface Health {
        data object Ready : Health
        data class Unavailable(val reason: String) : Health
    }

    sealed interface PrepareResult {
        /** A new NOT_STARTED record is durable. */
        data class Prepared(val record: ReviewCommitRecord) : PrepareResult
        /** The same transaction already exists (any state) — never a second record. */
        data class Existing(val record: ReviewCommitRecord) : PrepareResult
        /** Same commit id, different card or rating: the recorded rating is immutable. */
        data class Conflict(val record: ReviewCommitRecord) : PrepareResult
        data object Full : PrepareResult
        data class StoreFailed(val reason: String) : PrepareResult
        data class Unavailable(val reason: String) : PrepareResult
    }

    sealed interface ClaimResult {
        /** SUBMITTING is durable; the caller now owns the single dispatch of this attempt. */
        data class Claimed(val record: ReviewCommitRecord) : ClaimResult
        /** Another caller owns an in-flight attempt; do not dispatch. */
        data class InFlight(val record: ReviewCommitRecord) : ClaimResult
        data class AlreadyCommitted(val record: ReviewCommitRecord) : ClaimResult
        /** FAILED-not-safe, AMBIGUOUS, or FAILED-safe without an explicit retry request. */
        data class NotClaimable(val record: ReviewCommitRecord) : ClaimResult
        data object Missing : ClaimResult
        data class StoreFailed(val reason: String) : ClaimResult
        data class Unavailable(val reason: String) : ClaimResult
    }

    private val mutex = Mutex()
    private var records: LinkedHashMap<String, ReviewCommitRecord>? = null
    private var unavailableReason: String? = null
    private var report = ReviewCommitRecoveryReport()

    /** Loads (once per process) and reports health. */
    suspend fun health(): Health = mutex.withLock {
        if (loadedLocked() != null) Health.Ready else Health.Unavailable(unavailableReason ?: "unavailable")
    }

    suspend fun recoveryReport(): ReviewCommitRecoveryReport = mutex.withLock {
        loadedLocked()
        report
    }

    suspend fun get(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        loadedLocked()?.get(commitId.stableKey)
    }

    suspend fun snapshot(): List<ReviewCommitRecord> = mutex.withLock {
        loadedLocked()?.values?.toList().orEmpty()
    }

    /** AMBIGUOUS commits the user has neither reconciled nor dismissed, oldest first. */
    suspend fun pendingRecovery(backendId: AnkiBackendId? = null): List<ReviewCommitRecord> = mutex.withLock {
        loadedLocked()?.values?.filter {
            it.state == ReviewCommitState.AMBIGUOUS && !it.acknowledged &&
                (backendId == null || it.backendId == backendId)
        }.orEmpty()
    }

    /** CommitPrepared: create NOT_STARTED durably, or return the existing record for this id. */
    suspend fun prepare(request: CommitRatingRequest): PrepareResult = mutex.withLock {
        val current = loadedLocked() ?: return@withLock PrepareResult.Unavailable(reason())
        val key = request.commitId.stableKey
        current[key]?.let { existing ->
            return@withLock if (existing.samePayload(request)) PrepareResult.Existing(existing)
            else PrepareResult.Conflict(existing)
        }
        val now = clock()
        val record = ReviewCommitRecord(
            commitId = request.commitId,
            card = request.card,
            rating = request.rating,
            state = ReviewCommitState.NOT_STARTED,
            attemptCount = 0,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            ratedAtEpochMs = request.ratedAtEpochMs,
            answerDurationMs = request.answerDurationMs,
            evidence = request.evidence
        )
        val next = LinkedHashMap(current).apply { put(key, record) }
        if (!pruneToCapacity(next, protectKey = key)) return@withLock PrepareResult.Full
        persistLocked(next)?.let { return@withLock PrepareResult.StoreFailed(it) }
        PrepareResult.Prepared(record)
    }

    /**
     * NOT_STARTED (or FAILED-safe with [allowRetry]) → SUBMITTING, durably, before any dispatch.
     * [evidence] is attached only if the record has none: the first baseline is immutable.
     */
    suspend fun claim(
        commitId: ReviewCommitId,
        evidence: ReviewCommitEvidence?,
        allowRetry: Boolean
    ): ClaimResult = mutex.withLock {
        val current = loadedLocked() ?: return@withLock ClaimResult.Unavailable(reason())
        val record = current[commitId.stableKey] ?: return@withLock ClaimResult.Missing
        when (record.state) {
            ReviewCommitState.NOT_STARTED -> Unit
            ReviewCommitState.FAILED ->
                if (!(record.safeToRetry && allowRetry)) return@withLock ClaimResult.NotClaimable(record)
            ReviewCommitState.SUBMITTING -> return@withLock ClaimResult.InFlight(record)
            ReviewCommitState.COMMITTED -> return@withLock ClaimResult.AlreadyCommitted(record)
            ReviewCommitState.AMBIGUOUS -> return@withLock ClaimResult.NotClaimable(record)
        }
        val now = clock()
        val claimed = record.copy(
            state = ReviewCommitState.SUBMITTING,
            attemptCount = record.attemptCount + 1,
            evidence = record.evidence ?: evidence,
            submittedAtEpochMs = now,
            updatedAtEpochMs = now,
            resolvedAtEpochMs = null,
            failure = null,
            resolution = null
        )
        val next = LinkedHashMap(current).apply { put(commitId.stableKey, claimed) }
        persistLocked(next)?.let { return@withLock ClaimResult.StoreFailed(it) }
        ClaimResult.Claimed(claimed)
    }

    /**
     * SUBMITTING → COMMITTED / FAILED / AMBIGUOUS from the backend's classified result.
     *
     * Only a SUBMITTING record changes: a late or duplicate completion can never downgrade a
     * COMMITTED record or silently resolve an AMBIGUOUS one. If the final write fails the in-memory
     * record still resolves (the in-process dedup stays correct) and the durable copy stays
     * SUBMITTING, which the next process load conservatively turns into AMBIGUOUS.
     */
    suspend fun complete(commitId: ReviewCommitId, result: CommitRatingResult): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            if (record.state != ReviewCommitState.SUBMITTING) return@withLock record
            val now = clock()
            val resolved = when (result) {
                is CommitRatingResult.Committed -> record.copy(
                    state = ReviewCommitState.COMMITTED, failure = null,
                    resolution = ReviewCommitResolution.BACKEND_CONFIRMED
                )
                is CommitRatingResult.RetryableFailure -> record.copy(
                    state = ReviewCommitState.FAILED,
                    failure = ReviewCommitFailure(result.error.commitCategory(), safeToRetry = true),
                    resolution = ReviewCommitResolution.BACKEND_NOT_APPLIED
                )
                is CommitRatingResult.Rejected -> record.copy(
                    state = ReviewCommitState.FAILED,
                    failure = ReviewCommitFailure(result.error.commitCategory(), safeToRetry = false),
                    resolution = ReviewCommitResolution.BACKEND_REJECTED
                )
                is CommitRatingResult.Ambiguous -> record.copy(
                    state = ReviewCommitState.AMBIGUOUS,
                    failure = ReviewCommitFailure(result.error?.commitCategory() ?: "ambiguous", safeToRetry = false),
                    resolution = ReviewCommitResolution.BACKEND_AMBIGUOUS
                )
            }.copy(updatedAtEpochMs = now, resolvedAtEpochMs = now)
            resolveLocked(current, resolved)
        }

    /** SUBMITTING → AMBIGUOUS when the attempt was interrupted after dispatch may have started. */
    suspend fun markAmbiguous(commitId: ReviewCommitId, category: String): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.SUBMITTING) return@withLock record
        val now = clock()
        resolveLocked(current, record.copy(
            state = ReviewCommitState.AMBIGUOUS,
            failure = ReviewCommitFailure(category, safeToRetry = false),
            resolution = ReviewCommitResolution.INTERRUPTED_AFTER_DISPATCH,
            updatedAtEpochMs = now, resolvedAtEpochMs = now
        ))
    }

    /** NOT_STARTED → FAILED when preparation was refused. Nothing was dispatched. */
    suspend fun markRefused(commitId: ReviewCommitId, category: String, safeToRetry: Boolean): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            // Only a never-dispatched record, or a safe retry whose re-preparation was refused.
            val refusable = record.state == ReviewCommitState.NOT_STARTED || record.safeToRetry
            if (!refusable) return@withLock record
            val now = clock()
            resolveLocked(current, record.copy(
                state = ReviewCommitState.FAILED,
                failure = ReviewCommitFailure(category, safeToRetry),
                resolution = ReviewCommitResolution.REFUSED_BEFORE_DISPATCH,
                updatedAtEpochMs = now, resolvedAtEpochMs = now
            ))
        }

    /** AMBIGUOUS → COMMITTED / FAILED from reconciliation evidence; anything else stays AMBIGUOUS. */
    suspend fun reconcile(commitId: ReviewCommitId, result: ReconcileCommitResult): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            if (record.state != ReviewCommitState.AMBIGUOUS) return@withLock record
            val now = clock()
            val next = when (result) {
                is ReconcileCommitResult.Applied -> record.copy(
                    state = ReviewCommitState.COMMITTED, failure = null,
                    resolution = ReviewCommitResolution.RECONCILED_APPLIED
                )
                is ReconcileCommitResult.NotApplied -> record.copy(
                    state = ReviewCommitState.FAILED,
                    failure = ReviewCommitFailure("reconciled:${result.detail}".take(96), result.safeToRetry),
                    resolution = ReviewCommitResolution.RECONCILED_NOT_APPLIED
                )
                // Inconclusive: the state stays AMBIGUOUS; only the diagnostic token changes.
                is ReconcileCommitResult.StillAmbiguous -> record.copy(
                    failure = ReviewCommitFailure("inconclusive:${result.detail}".take(96), false),
                    resolution = ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE
                )
                is ReconcileCommitResult.Unsupported -> record.copy(
                    failure = ReviewCommitFailure("inconclusive:${result.detail}".take(96), false),
                    resolution = ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE
                )
                is ReconcileCommitResult.Unavailable -> record.copy(
                    failure = ReviewCommitFailure("inconclusive:${result.error.commitCategory()}".take(96), false),
                    resolution = ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE
                )
            }.copy(updatedAtEpochMs = now, resolvedAtEpochMs = now)
            resolveLocked(current, next)
        }

    /**
     * The user saw a FAILED/AMBIGUOUS outcome and dismissed it. The state does not change — an
     * acknowledged AMBIGUOUS commit is still AMBIGUOUS — it only becomes eligible for pruning.
     */
    suspend fun acknowledge(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.AMBIGUOUS && record.state != ReviewCommitState.FAILED) {
            return@withLock record
        }
        resolveLocked(current, record.copy(acknowledged = true, updatedAtEpochMs = clock()))
    }

    /**
     * Explicit, user-invoked recovery from an unreadable snapshot. Only allowed while
     * [Health.Unavailable]; the scheduler stays the source of truth for what was reviewed.
     */
    suspend fun resetUnreadable(): Boolean = mutex.withLock {
        if (loadedLocked() != null || unavailableReason == null) return@withLock false
        val empty = LinkedHashMap<String, ReviewCommitRecord>()
        val written = withContext(NonCancellable) { store.write(ReviewCommitLedgerCodec.encode(empty.values)) }
        if (!written) return@withLock false
        unavailableReason = null
        records = empty
        report = ReviewCommitRecoveryReport()
        true
    }

    // ---------------------------------------------------------------- internals (mutex held)

    private fun reason(): String = unavailableReason ?: "unavailable"

    private suspend fun loadedLocked(): LinkedHashMap<String, ReviewCommitRecord>? {
        records?.let { return it }
        if (unavailableReason != null) return null
        val snapshot = when (val read = store.read()) {
            is ReviewCommitStoreRead.Unreadable -> {
                unavailableReason = "store_unreadable:${read.reason}".take(96)
                return null
            }
            is ReviewCommitStoreRead.Snapshot -> read.value
        }
        val decoded = if (snapshot == null) ReviewCommitLedgerCodec.Decoded.Records(emptyList())
            else ReviewCommitLedgerCodec.decode(snapshot)
        val loaded = when (decoded) {
            is ReviewCommitLedgerCodec.Decoded.Unreadable -> {
                unavailableReason = decoded.reason
                return null
            }
            is ReviewCommitLedgerCodec.Decoded.Records -> decoded.records
        }
        val map = LinkedHashMap<String, ReviewCommitRecord>()
        loaded.forEach { map[it.commitId.stableKey] = it }
        // Nothing in *this* process has claimed yet, so every SUBMITTING record belongs to a process
        // that died with the call possibly in flight: "may have been applied" (STEP 100).
        val interrupted = map.values.filter { it.state == ReviewCommitState.SUBMITTING }
        if (interrupted.isNotEmpty()) {
            val now = clock()
            interrupted.forEach { record ->
                map[record.commitId.stableKey] = record.copy(
                    state = ReviewCommitState.AMBIGUOUS,
                    failure = ReviewCommitFailure("interrupted_while_submitting", safeToRetry = false),
                    resolution = ReviewCommitResolution.PROCESS_RESTART_WHILE_SUBMITTING,
                    updatedAtEpochMs = now,
                    resolvedAtEpochMs = now
                )
            }
            // Best effort: if this write fails the durable copy still says SUBMITTING, and the next
            // load converts it again. Memory already blocks the commit either way.
            withContext(NonCancellable) { store.write(ReviewCommitLedgerCodec.encode(map.values)) }
        }
        report = ReviewCommitRecoveryReport(
            interruptedSubmissions = interrupted.size,
            restorable = map.values.count { it.state == ReviewCommitState.NOT_STARTED },
            unresolvedAmbiguous = map.values.count { it.state == ReviewCommitState.AMBIGUOUS && !it.acknowledged },
            committed = map.values.count { it.state == ReviewCommitState.COMMITTED },
            failed = map.values.count { it.state == ReviewCommitState.FAILED }
        )
        records = map
        return map
    }

    /** Writes [next] durably; on success it becomes the in-memory truth. Returns a failure token. */
    private suspend fun persistLocked(next: LinkedHashMap<String, ReviewCommitRecord>): String? {
        val written = withContext(NonCancellable) { store.write(ReviewCommitLedgerCodec.encode(next.values)) }
        if (!written) return "store_write_failed"
        records = next
        return null
    }

    /** Resolution writes keep memory authoritative even if the disk write fails (see [complete]). */
    private suspend fun resolveLocked(
        current: LinkedHashMap<String, ReviewCommitRecord>,
        resolved: ReviewCommitRecord
    ): ReviewCommitRecord {
        val next = LinkedHashMap(current).apply { put(resolved.commitId.stableKey, resolved) }
        // On a failed write the durable copy lags behind (conservatively: SUBMITTING becomes
        // AMBIGUOUS on the next load); memory stays authoritative for this process either way.
        persistLocked(next)
        records = next
        return resolved
    }

    /**
     * Drops the oldest *safe-to-forget* records until [next] fits. Never drops SUBMITTING,
     * unacknowledged AMBIGUOUS, the record being added, or the most recent records (the live turn's
     * FAILED record must survive for its retry). Returns false when it cannot make room.
     */
    private fun pruneToCapacity(next: LinkedHashMap<String, ReviewCommitRecord>, protectKey: String): Boolean {
        if (next.size <= maxRecords) return true
        val recent = next.keys.toList().takeLast(PROTECTED_RECENT_RECORDS).toSet()
        val iterator = next.entries.iterator()
        while (next.size > maxRecords && iterator.hasNext()) {
            val (key, record) = iterator.next()
            val protected = key == protectKey || key in recent ||
                record.state == ReviewCommitState.SUBMITTING ||
                (record.state == ReviewCommitState.AMBIGUOUS && !record.acknowledged)
            if (!protected) iterator.remove()
        }
        return next.size <= maxRecords
    }

    companion object {
        /** Small: one record per rated turn, pruned once resolved and old (STEP 16). */
        const val DEFAULT_MAX_RECORDS = 200
        const val PROTECTED_RECENT_RECORDS = 8
    }
}
