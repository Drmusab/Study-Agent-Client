package com.studyagent.client.core.anki

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One atomic durable cell. `write` must finish the replacement before returning true. */
interface ReviewCommitStore {
    suspend fun read(): ReviewCommitStoreRead
    suspend fun write(snapshot: String): Boolean
}

sealed interface ReviewCommitStoreRead {
    data class Snapshot(val value: String?) : ReviewCommitStoreRead
    /** Never interpret corrupt or unreadable storage as an empty ledger. */
    data class Unreadable(val reason: String) : ReviewCommitStoreRead
}

/** Schema 1 had no attempt phase: its SUBMITTING rows are conservatively treated as CALL_ENTERED. */
object ReviewCommitLedgerCodec {
    const val SCHEMA_VERSION = 2

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
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), snapshot) }.getOrNull()
            ?: return Decoded.Unreadable("malformed_snapshot")
        if (envelope.schemaVersion !in 1..SCHEMA_VERSION) {
            return Decoded.Unreadable("unsupported_schema_${envelope.schemaVersion}")
        }
        val records = if (envelope.schemaVersion == 1) envelope.records.map { record ->
            record.copy(phase = when (record.state) {
                ReviewCommitState.SUBMITTING -> CommitAttemptPhase.MUTATION_CALL_ENTERED
                ReviewCommitState.NOT_STARTED -> null
                ReviewCommitState.FAILED -> if (record.attemptCount == 0) null else CommitAttemptPhase.LOCAL_RESULT_PERSISTED
                ReviewCommitState.COMMITTED, ReviewCommitState.AMBIGUOUS -> CommitAttemptPhase.LOCAL_RESULT_PERSISTED
            })
        } else envelope.records
        if (records.map { it.commitId.stableKey }.distinct().size != records.size) {
            return Decoded.Unreadable("duplicate_commit_ids")
        }
        val unresolved = records.filter { it.state != ReviewCommitState.COMMITTED }
        if (unresolved.map { it.backendId to it.sessionId }.distinct().size != unresolved.size) {
            return Decoded.Unreadable("multiple_unresolved_per_session")
        }
        if (records.map { it.backendId to (it.sessionId to it.turnId) }.distinct().size != records.size) {
            return Decoded.Unreadable("multiple_commits_per_turn")
        }
        if (records.any { !valid(it) }) return Decoded.Unreadable("contradictory_commit_metadata")
        return Decoded.Records(records)
    }

    private fun valid(record: ReviewCommitRecord): Boolean {
        if (record.card.backendId != record.backendId ||
            (record.deckRef != null && (record.deckRef.backendId != record.backendId ||
                (record.deckRef.collectionKey != null && record.card.collectionKey != null &&
                    record.deckRef.collectionKey != record.card.collectionKey)))) return false
        if (record.phase == CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED && record.response == null) return false
        if (record.response != null && record.phase != CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED &&
            record.phase != CommitAttemptPhase.LOCAL_RESULT_PERSISTED) return false
        if (record.response?.kind == CommitResponseKind.CONFIRMED_COMMITTED &&
            record.state in setOf(ReviewCommitState.AMBIGUOUS, ReviewCommitState.FAILED)) return false
        return when (record.state) {
            ReviewCommitState.NOT_STARTED -> record.attemptCount == 0 && record.phase == null && record.response == null
            ReviewCommitState.SUBMITTING -> record.attemptCount > 0 && record.phase in setOf(
                CommitAttemptPhase.PREPARED, CommitAttemptPhase.MUTATION_CALL_ENTERED,
                CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED)
            ReviewCommitState.COMMITTED -> record.attemptCount > 0 &&
                record.phase == CommitAttemptPhase.LOCAL_RESULT_PERSISTED && record.failure == null &&
                (record.response == null || record.response.kind == CommitResponseKind.CONFIRMED_COMMITTED)
            ReviewCommitState.FAILED -> record.failure != null &&
                record.phase == (if (record.attemptCount == 0) null else CommitAttemptPhase.LOCAL_RESULT_PERSISTED)
            ReviewCommitState.AMBIGUOUS -> record.attemptCount > 0 &&
                record.phase == CommitAttemptPhase.LOCAL_RESULT_PERSISTED && !record.safeToRetry
        }
    }
}

/** Snapshot of *all* unfinished work found on first load, not only unacknowledged ambiguity. */
data class ReviewCommitRecoveryReport(
    val interruptedSubmissions: Int = 0,
    val restorable: Int = 0,
    val unresolvedAmbiguous: Int = 0,
    val committed: Int = 0,
    val failed: Int = 0
)

/** Non-blocking diagnostics. No IDs, card text, evidence tokens, collection names or error text. */
data class ReviewCommitLedgerDiagnostics(
    val health: String = "Not checked",
    val records: Int? = null,
    val submitting: Int? = null,
    val ambiguous: Int? = null,
    val committed: Int? = null,
    val safeFailures: Int? = null,
    val interruptedOnRestore: Int? = null,
    val lastWriteFailed: Boolean = false
)

/**
 * All durable state transitions and process-restore decisions live here, under one mutex. No
 * caller may copy a record to another state. If any write fails, memory stays at the last known
 * durable snapshot; especially a backend success is NOT exposed as COMMITTED before it is durable.
 * No unresolved (including acknowledged AMBIGUOUS) or COMMITTED identity is evicted: at capacity
 * we stop accepting new transactions, not quietly weaken the at-most-once guarantee.
 */
class ReviewCommitLedger(
    private val store: ReviewCommitStore,
    private val clock: () -> Long,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val recoveryPolicy: ReviewCommitRecoveryPolicy = ReviewCommitRecoveryPolicy()
) {
    init { require(maxRecords > 0) }

    sealed interface Health {
        data object Ready : Health
        data class Unavailable(val reason: String) : Health
    }

    sealed interface PrepareResult {
        data class Prepared(val record: ReviewCommitRecord) : PrepareResult
        data class Existing(val record: ReviewCommitRecord) : PrepareResult
        data class Conflict(val record: ReviewCommitRecord) : PrepareResult
        data object Full : PrepareResult
        data class StoreFailed(val reason: String) : PrepareResult
        data class Unavailable(val reason: String) : PrepareResult
    }

    sealed interface ClaimResult {
        data class Claimed(val record: ReviewCommitRecord) : ClaimResult
        data class InFlight(val record: ReviewCommitRecord) : ClaimResult
        data class AlreadyCommitted(val record: ReviewCommitRecord) : ClaimResult
        data class NotClaimable(val record: ReviewCommitRecord) : ClaimResult
        data object Missing : ClaimResult
        data class StoreFailed(val reason: String) : ClaimResult
        data class Unavailable(val reason: String) : ClaimResult
    }

    private val mutex = Mutex()
    private var records: LinkedHashMap<String, ReviewCommitRecord>? = null
    private var unavailableReason: String? = null
    private var report = ReviewCommitRecoveryReport()
    @Volatile private var diagnostics = ReviewCommitLedgerDiagnostics()

    /** Safe to call from synchronous diagnostics/UI code. Never triggers a storage read. */
    fun diagnosticsSnapshot(): ReviewCommitLedgerDiagnostics = diagnostics

    private fun publishDiagnostics(current: Map<String, ReviewCommitRecord>, writeFailed: Boolean = false) {
        diagnostics = ReviewCommitLedgerDiagnostics(
            health = if (writeFailed) "Last write failed" else "Ready",
            records = current.size,
            submitting = current.values.count { it.state == ReviewCommitState.SUBMITTING },
            ambiguous = current.values.count { it.state == ReviewCommitState.AMBIGUOUS },
            committed = current.values.count { it.state == ReviewCommitState.COMMITTED },
            safeFailures = current.values.count { it.safeToRetry },
            interruptedOnRestore = report.interruptedSubmissions,
            lastWriteFailed = writeFailed
        )
    }

    suspend fun health(): Health = mutex.withLock {
        if (loadedLocked() != null) Health.Ready else Health.Unavailable(reason())
    }

    suspend fun recoveryReport(): ReviewCommitRecoveryReport = mutex.withLock { loadedLocked(); report }
    suspend fun get(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        loadedLocked()?.get(commitId.stableKey)
    }
    suspend fun snapshot(): List<ReviewCommitRecord> = mutex.withLock { loadedLocked()?.values?.toList().orEmpty() }

    /** AMBIGUOUS entries remain visible even after the warning is acknowledged. */
    suspend fun pendingRecovery(backendId: AnkiBackendId? = null): List<ReviewCommitRecord> = mutex.withLock {
        loadedLocked()?.values?.filter { it.state == ReviewCommitState.AMBIGUOUS &&
            (backendId == null || it.backendId == backendId) }.orEmpty()
    }

    /**
     * Called before beginReview/nextCard: block the affected session and, for an AMBIGUOUS
     * mutation, any new session in its known collection. Unknown collection scopes to backend.
     * Other backends and known different collections are never blocked by this record.
     */
    suspend fun recoveryBlocker(backendId: AnkiBackendId, collectionKey: String?, sessionId: String): ReviewCommitRecord? =
        mutex.withLock {
            loadedLocked()?.values?.firstOrNull { record ->
                record.backendId == backendId && record.state != ReviewCommitState.COMMITTED &&
                    (record.sessionId == sessionId ||
                        (record.state == ReviewCommitState.AMBIGUOUS ||
                            (record.state == ReviewCommitState.SUBMITTING &&
                                record.phase != CommitAttemptPhase.PREPARED)) &&
                        (collectionKey == null || record.card.collectionKey == null ||
                            record.card.collectionKey == collectionKey)))
            }
        }

    /** First durable intent. Enforces one logical commit per session/turn before any backend call. */
    suspend fun prepare(request: CommitRatingRequest): PrepareResult = mutex.withLock {
        val current = loadedLocked() ?: return@withLock PrepareResult.Unavailable(reason())
        current[request.commitId.stableKey]?.let { existing ->
            return@withLock if (existing.samePayload(request)) PrepareResult.Existing(existing)
            else PrepareResult.Conflict(existing)
        }
        current.values.firstOrNull { it.backendId == request.commitId.backendId &&
            it.sessionId == request.sessionId &&
            (it.turnId == request.turnId || it.state != ReviewCommitState.COMMITTED) }?.let {
            return@withLock PrepareResult.Conflict(it)
        }
        if (current.size >= maxRecords) return@withLock PrepareResult.Full
        val now = clock()
        val record = ReviewCommitRecord(
            commitId = request.commitId, card = request.card, rating = request.rating,
            state = ReviewCommitState.NOT_STARTED, attemptCount = 0, deckRef = request.deckRef,
            createdAtEpochMs = now, updatedAtEpochMs = now,
            ratedAtEpochMs = request.ratedAtEpochMs, answerDurationMs = request.answerDurationMs,
            evidence = request.evidence
        )
        val next = LinkedHashMap(current).apply { put(request.commitId.stableKey, record) }
        persistLocked(next)?.let { return@withLock PrepareResult.StoreFailed(it) }
        PrepareResult.Prepared(record)
    }

    /** NOT_STARTED / proven-not-applied FAILED → SUBMITTING+PREPARED; only one claimant wins. */
    suspend fun claim(commitId: ReviewCommitId, evidence: ReviewCommitEvidence?, allowRetry: Boolean): ClaimResult =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock ClaimResult.Unavailable(reason())
            val record = current[commitId.stableKey] ?: return@withLock ClaimResult.Missing
            when (record.state) {
                ReviewCommitState.NOT_STARTED -> Unit
                ReviewCommitState.FAILED -> if (!(record.safeToRetry && allowRetry))
                    return@withLock ClaimResult.NotClaimable(record)
                ReviewCommitState.SUBMITTING -> return@withLock ClaimResult.InFlight(record)
                ReviewCommitState.COMMITTED -> return@withLock ClaimResult.AlreadyCommitted(record)
                ReviewCommitState.AMBIGUOUS -> return@withLock ClaimResult.NotClaimable(record)
            }
            val now = clock()
            val claimed = record.copy(state = ReviewCommitState.SUBMITTING,
                attemptCount = record.attemptCount + 1, phase = CommitAttemptPhase.PREPARED,
                response = null, evidence = record.evidence ?: evidence, submittedAtEpochMs = now,
                updatedAtEpochMs = now, resolvedAtEpochMs = null, failure = null, resolution = null)
            persistLocked(LinkedHashMap(current).apply { put(commitId.stableKey, claimed) })?.let {
                return@withLock ClaimResult.StoreFailed(it)
            }
            ClaimResult.Claimed(claimed)
        }

    /** Must durably complete at the callback immediately before the real scheduler mutation. */
    suspend fun markMutationEntered(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.SUBMITTING || record.phase != CommitAttemptPhase.PREPARED) return@withLock null
        persistRecordLocked(current, record.copy(phase = CommitAttemptPhase.MUTATION_CALL_ENTERED,
            updatedAtEpochMs = clock()))
    }

    /** Durably record the actual classified backend answer before any terminal state transition. */
    suspend fun markResponseReceived(commitId: ReviewCommitId, result: CommitRatingResult): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            if (record.state != ReviewCommitState.SUBMITTING ||
                record.phase != CommitAttemptPhase.MUTATION_CALL_ENTERED) return@withLock null
            val proof = responseFor(record, result) ?: return@withLock null
            persistRecordLocked(current, record.copy(phase = CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED,
                response = proof, updatedAtEpochMs = clock()))
        }

    /**
     * SUBMITTING + durably recorded response → terminal. A failed write returns null, leaves
     * memory and disk nonterminal and MUST NOT cause a success event or a next-card query.
     */
    suspend fun complete(commitId: ReviewCommitId, result: CommitRatingResult): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            if (record.state != ReviewCommitState.SUBMITTING ||
                record.phase != CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED ||
                record.response != responseFor(record, result)) return@withLock null
            persistRecordLocked(current, terminalFromResponse(record, ReviewCommitResolution.BACKEND_CONFIRMED))
        }

    /** Finish a previously persisted response (e.g. after a COMMITTED write failed). No backend call. */
    suspend fun finalizeRecordedResponse(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.SUBMITTING ||
            record.phase != CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED || record.response == null) return@withLock null
        persistRecordLocked(current, terminalFromResponse(record, ReviewCommitResolution.RECOVERED_RESPONSE))
    }

    /** PREPARED -> FAILED-safe, but only because the marker proves no mutation call began. */
    suspend fun markPreparedFailure(
        commitId: ReviewCommitId, category: String = "mutation_not_entered", safeToRetry: Boolean = true
    ): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.SUBMITTING ||
            record.phase != CommitAttemptPhase.PREPARED) return@withLock null
        persistRecordLocked(current, record.copy(state = ReviewCommitState.FAILED,
            phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = ReviewCommitFailure(category.take(96), safeToRetry),
            resolution = ReviewCommitResolution.RECOVERED_PREPARED,
            updatedAtEpochMs = clock(), resolvedAtEpochMs = clock()))
    }

    /** A backend violated its boundary callback contract. No safe retry, even if still PREPARED. */
    suspend fun markBoundaryViolation(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.SUBMITTING ||
            record.phase != CommitAttemptPhase.PREPARED) return@withLock null
        persistRecordLocked(current, record.copy(state = ReviewCommitState.AMBIGUOUS,
            phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = ReviewCommitFailure("backend_boundary_violation", false),
            resolution = ReviewCommitResolution.INTERRUPTED_AFTER_DISPATCH,
            updatedAtEpochMs = clock(), resolvedAtEpochMs = clock()))
    }

    /** Known interruption after entering a backend method; never mark PREPARED as ambiguous. */
    suspend fun markAmbiguous(commitId: ReviewCommitId, category: String): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.SUBMITTING ||
            record.phase != CommitAttemptPhase.MUTATION_CALL_ENTERED) return@withLock null
        persistRecordLocked(current, record.copy(state = ReviewCommitState.AMBIGUOUS,
            phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = ReviewCommitFailure(category.take(96), false),
            resolution = ReviewCommitResolution.INTERRUPTED_AFTER_DISPATCH,
            updatedAtEpochMs = clock(), resolvedAtEpochMs = clock()))
    }

    /** Read-only preparation failed; no mutation was dispatched in this attempt. */
    suspend fun markRefused(commitId: ReviewCommitId, category: String, safeToRetry: Boolean): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            if (record.state != ReviewCommitState.NOT_STARTED && !record.safeToRetry) return@withLock null
            persistRecordLocked(current, record.copy(state = ReviewCommitState.FAILED,
                phase = if (record.attemptCount == 0) null else CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
                response = null, failure = ReviewCommitFailure(category.take(96), safeToRetry),
                resolution = ReviewCommitResolution.REFUSED_BEFORE_DISPATCH,
                updatedAtEpochMs = clock(), resolvedAtEpochMs = clock()))
        }

    /** Only the backend adapter may supply authoritative reconciliation; callers gate on its semantics. */
    suspend fun reconcile(commitId: ReviewCommitId, result: ReconcileCommitResult): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            if (record.state != ReviewCommitState.AMBIGUOUS) return@withLock record
            val action = recoveryPolicy.classify(record, result)
            val now = clock()
            val next = when (action) {
                CommitRecoveryAction.ResumeCommitted -> record.copy(state = ReviewCommitState.COMMITTED,
                    response = null, failure = null, resolution = ReviewCommitResolution.RECONCILED_APPLIED)
                CommitRecoveryAction.RetryAllowed -> record.copy(state = ReviewCommitState.FAILED,
                    response = null, failure = ReviewCommitFailure("reconciled_not_applied", true),
                    resolution = ReviewCommitResolution.RECONCILED_NOT_APPLIED)
                CommitRecoveryAction.BlockedUnresolved -> if (result is ReconcileCommitResult.NotApplied)
                    record.copy(state = ReviewCommitState.FAILED, response = null,
                        failure = ReviewCommitFailure("reconciled_not_applied", false),
                        resolution = ReviewCommitResolution.RECONCILED_NOT_APPLIED)
                    else record.copy(failure = ReviewCommitFailure("reconciliation_inconclusive", false),
                        resolution = ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE)
                else -> return@withLock null
            }.copy(phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
                updatedAtEpochMs = now, resolvedAtEpochMs = now)
            persistRecordLocked(current, next)
        }

    /** Acknowledging never changes the truth, allows a retry or makes this record prunable. */
    suspend fun acknowledge(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.AMBIGUOUS && record.state != ReviewCommitState.FAILED) return@withLock record
        persistRecordLocked(current, record.copy(acknowledged = true, updatedAtEpochMs = clock()))
    }

    private fun reason(): String = unavailableReason ?: "unavailable"

    private fun disable(reason: String): Nothing? {
        unavailableReason = reason.take(96)
        diagnostics = ReviewCommitLedgerDiagnostics(health = "Unavailable")
        return null
    }

    private suspend fun loadedLocked(): LinkedHashMap<String, ReviewCommitRecord>? {
        records?.let { return it }
        if (unavailableReason != null) return null
        val read = try { store.read() } catch (_: Exception) {
            return disable("store_read_failed")
        }
        val raw = when (read) {
            is ReviewCommitStoreRead.Unreadable -> return disable("store_unreadable:${read.reason}")
            is ReviewCommitStoreRead.Snapshot -> read.value
        }
        val decoded = if (raw == null) ReviewCommitLedgerCodec.Decoded.Records(emptyList())
            else ReviewCommitLedgerCodec.decode(raw)
        val initial = when (decoded) {
            is ReviewCommitLedgerCodec.Decoded.Unreadable -> return disable(decoded.reason)
            is ReviewCommitLedgerCodec.Decoded.Records -> decoded.records
        }
        val map = LinkedHashMap<String, ReviewCommitRecord>()
        val interrupted = initial.count { it.state == ReviewCommitState.SUBMITTING }
        val now = clock()
        for (record in initial) {
            val recovered = if (record.state == ReviewCommitState.SUBMITTING) when (recoveryPolicy.classify(record)) {
                CommitRecoveryAction.RetryAllowed -> if (record.phase == CommitAttemptPhase.PREPARED)
                    record.copy(state = ReviewCommitState.FAILED, phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
                        failure = ReviewCommitFailure("interrupted_before_mutation", true),
                        resolution = ReviewCommitResolution.RECOVERED_PREPARED,
                        updatedAtEpochMs = now, resolvedAtEpochMs = now)
                    else terminalFromResponse(record, ReviewCommitResolution.RECOVERED_RESPONSE)
                CommitRecoveryAction.ResumeCommitted -> terminalFromResponse(record, ReviewCommitResolution.RECOVERED_RESPONSE)
                CommitRecoveryAction.ReconciliationRequired -> record.copy(state = ReviewCommitState.AMBIGUOUS,
                    phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
                    failure = ReviewCommitFailure("interrupted_after_mutation_entry", false),
                    resolution = ReviewCommitResolution.PROCESS_RESTART_WHILE_SUBMITTING,
                    updatedAtEpochMs = now, resolvedAtEpochMs = now)
                CommitRecoveryAction.BlockedUnresolved -> terminalFromResponse(record, ReviewCommitResolution.RECOVERED_RESPONSE)
                CommitRecoveryAction.IntegrityError -> return disable("invalid_attempt_phase")
            } else record
            map[recovered.commitId.stableKey] = recovered
        }
        if (interrupted > 0 || (raw != null && raw.contains("\"schemaVersion\":1"))) {
            if (writeLocked(map) != null) return disable("recovery_write_failed")
        }
        report = ReviewCommitRecoveryReport(
            interruptedSubmissions = interrupted,
            restorable = map.values.count { it.state == ReviewCommitState.NOT_STARTED },
            unresolvedAmbiguous = map.values.count { it.state == ReviewCommitState.AMBIGUOUS },
            committed = map.values.count { it.state == ReviewCommitState.COMMITTED },
            failed = map.values.count { it.state == ReviewCommitState.FAILED })
        records = map
        publishDiagnostics(map)
        return map
    }

    private fun responseFor(record: ReviewCommitRecord, result: CommitRatingResult): CommitResponseEvidence? = when (result) {
        is CommitRatingResult.Committed -> {
            val receipt = result.receipt
            if (receipt != null && (receipt.backendId != record.backendId || receipt.committedRating != record.rating)) null
            else CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED, backendReceiptId = receipt?.backendReceiptId)
        }
        is CommitRatingResult.RetryableFailure -> CommitResponseEvidence(CommitResponseKind.CONFIRMED_NOT_APPLIED,
            ReviewCommitFailure(result.error.commitCategory(), true))
        is CommitRatingResult.Rejected -> CommitResponseEvidence(CommitResponseKind.CONFIRMED_NOT_APPLIED,
            ReviewCommitFailure(result.error.commitCategory(), false))
        is CommitRatingResult.Ambiguous -> CommitResponseEvidence(CommitResponseKind.OUTCOME_UNKNOWN,
            ReviewCommitFailure(result.error?.commitCategory() ?: "unknown_outcome", false))
    }

    private fun terminalFromResponse(record: ReviewCommitRecord, resolution: String): ReviewCommitRecord {
        val response = checkNotNull(record.response)
        val state = when (response.kind) {
            CommitResponseKind.CONFIRMED_COMMITTED -> ReviewCommitState.COMMITTED
            CommitResponseKind.CONFIRMED_NOT_APPLIED -> ReviewCommitState.FAILED
            CommitResponseKind.OUTCOME_UNKNOWN -> ReviewCommitState.AMBIGUOUS
        }
        return record.copy(state = state, phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = response.failure, resolution = resolutionFor(state, resolution, response),
            updatedAtEpochMs = clock(), resolvedAtEpochMs = clock())
    }

    private fun resolutionFor(state: ReviewCommitState, source: String, response: CommitResponseEvidence): String =
        if (source != ReviewCommitResolution.BACKEND_CONFIRMED) source else when (state) {
            ReviewCommitState.COMMITTED -> ReviewCommitResolution.BACKEND_CONFIRMED
            ReviewCommitState.FAILED -> if (response.failure?.safeToRetry == true)
                ReviewCommitResolution.BACKEND_NOT_APPLIED else ReviewCommitResolution.BACKEND_REJECTED
            ReviewCommitState.AMBIGUOUS -> ReviewCommitResolution.BACKEND_AMBIGUOUS
            else -> source
        }

    private suspend fun persistRecordLocked(
        current: LinkedHashMap<String, ReviewCommitRecord>, record: ReviewCommitRecord
    ): ReviewCommitRecord? = if (persistLocked(LinkedHashMap(current).apply {
            put(record.commitId.stableKey, record)
        }) == null) record else null

    private suspend fun writeLocked(next: LinkedHashMap<String, ReviewCommitRecord>): String? {
        // DataStore's suspending edit completes before returning. Never launch this in another job.
        val written = try { withContext(NonCancellable) { store.write(ReviewCommitLedgerCodec.encode(next.values)) } }
            catch (_: Exception) { false }
        return if (written) null else "store_write_failed"
    }

    private suspend fun persistLocked(next: LinkedHashMap<String, ReviewCommitRecord>): String? {
        val failure = writeLocked(next)
        if (failure == null) {
            records = next
            publishDiagnostics(next)
        } else {
            publishDiagnostics(requireNotNull(records), writeFailed = true)
        }
        return failure
    }

    companion object {
        /** No unsafe eviction; raise the ceiling rather than deleting unresolved transaction truth. */
        const val DEFAULT_MAX_RECORDS = 10_000
    }
}
