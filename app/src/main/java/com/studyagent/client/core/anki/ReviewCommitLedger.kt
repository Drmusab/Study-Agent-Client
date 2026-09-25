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

/**
 * Identity retained after a committed payload is pruned. Knowing the key is enough to refuse a
 * second mutation. It is not a backend receipt and not card content.
 */
@Serializable
data class CommitIdentityTombstone(val commitKey: String, val prunedAtEpochMs: Long) {
    init {
        require(commitKey.isNotBlank())
        require(prunedAtEpochMs >= 0)
    }
}

/** Schema 1 had no attempt phase: its SUBMITTING rows are conservatively treated as CALL_ENTERED. */
object ReviewCommitLedgerCodec {
    const val SCHEMA_VERSION = 2

    @Serializable
    private data class Envelope(
        val schemaVersion: Int,
        val records: List<ReviewCommitRecord>,
        val tombstones: List<CommitIdentityTombstone> = emptyList()
    )

    private val json = Json { encodeDefaults = true }

    sealed interface Decoded {
        data class Records(
            val records: List<ReviewCommitRecord>,
            val tombstones: List<CommitIdentityTombstone> = emptyList()
        ) : Decoded
        data class Unreadable(val reason: String) : Decoded
    }

    fun encode(
        records: Collection<ReviewCommitRecord>,
        tombstones: Collection<CommitIdentityTombstone> = emptyList()
    ): String = json.encodeToString(
        Envelope.serializer(), Envelope(SCHEMA_VERSION, records.toList(), tombstones.toList())
    )

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
        if (envelope.tombstones.map { it.commitKey }.distinct().size != envelope.tombstones.size) {
            return Decoded.Unreadable("duplicate_tombstones")
        }
        val liveKeys = records.map { it.commitId.stableKey }.toSet()
        if (envelope.tombstones.any { it.commitKey in liveKeys }) {
            return Decoded.Unreadable("tombstone_overlaps_record")
        }
        return Decoded.Records(records, envelope.tombstones)
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
    val lastWriteFailed: Boolean = false,
    /** Cumulative counters for this process. Not card content. Ambiguous rate is the reliability signal. */
    val attemptTotal: Long = 0,
    val successTotal: Long = 0,
    val safeFailureTotal: Long = 0,
    val ambiguousTotal: Long = 0,
    val conflictTotal: Long = 0,
    val duplicateRejectedTotal: Long = 0,
    val recoveryTotal: Long = 0,
    val reconciliationUnresolvedTotal: Long = 0
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
        /** Identity was pruned after COMMITTED. Do not mutate again. */
        data class Tombstoned(val commitKey: String) : PrepareResult
        data object Full : PrepareResult
        data class StoreFailed(val reason: String) : PrepareResult
        data class Unavailable(val reason: String) : PrepareResult
    }

    sealed interface TransitionResult {
        data class Applied(val record: ReviewCommitRecord) : TransitionResult
        data class StateMismatch(val actual: ReviewCommitRecord) : TransitionResult
        data class VersionMismatch(val actual: ReviewCommitRecord) : TransitionResult
        data class Rejected(val actual: ReviewCommitRecord) : TransitionResult
        data object Missing : TransitionResult
        data class Unavailable(val reason: String) : TransitionResult
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
    private var tombstones: LinkedHashMap<String, CommitIdentityTombstone> = linkedMapOf()
    private var unavailableReason: String? = null
    private var report = ReviewCommitRecoveryReport()
    private var attemptTotal = 0L
    private var successTotal = 0L
    private var safeFailureTotal = 0L
    private var ambiguousTotal = 0L
    private var conflictTotal = 0L
    private var duplicateRejectedTotal = 0L
    private var recoveryTotal = 0L
    private var reconciliationUnresolvedTotal = 0L
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
            lastWriteFailed = writeFailed,
            attemptTotal = attemptTotal,
            successTotal = successTotal,
            safeFailureTotal = safeFailureTotal,
            ambiguousTotal = ambiguousTotal,
            conflictTotal = conflictTotal,
            duplicateRejectedTotal = duplicateRejectedTotal,
            recoveryTotal = recoveryTotal,
            reconciliationUnresolvedTotal = reconciliationUnresolvedTotal
        )
    }

    suspend fun health(): Health = mutex.withLock {
        if (loadedLocked() != null) Health.Ready else Health.Unavailable(reason())
    }

    suspend fun recoveryReport(): ReviewCommitRecoveryReport = mutex.withLock { loadedLocked(); report }
    suspend fun get(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        loadedLocked()?.get(commitId.stableKey)
    }

    /** One match, or null when the turn id is absent or ambiguous across sessions. */
    suspend fun getByTurn(turnId: ReviewTurnId): ReviewCommitRecord? = mutex.withLock {
        loadedLocked()?.values?.filter { it.turnId == turnId }?.singleOrNull()
    }

    /**
     * Unresolved transactions in a deterministic order: collection, session, createdAt.
     * Includes NOT_STARTED, SUBMITTING, FAILED and AMBIGUOUS. Never includes COMMITTED.
     * More than one unresolved record for one session is an integrity error at decode time.
     */
    suspend fun unresolved(): List<ReviewCommitRecord> = mutex.withLock {
        loadedLocked()?.values?.filter { it.state != ReviewCommitState.COMMITTED }
            ?.sortedWith(compareBy({ it.card.collectionKey ?: "" }, { it.sessionId }, { it.createdAtEpochMs }, { it.commitId.stableKey }))
            .orEmpty()
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

    /**
     * First durable intent. Enforces one logical commit per session/turn before any backend call.
     * [semantics] is frozen onto a newly created record and is not rewritten if the record exists.
     */
    suspend fun prepare(request: CommitRatingRequest, semantics: CommitSemantics? = null): PrepareResult = mutex.withLock {
        val current = loadedLocked() ?: return@withLock PrepareResult.Unavailable(reason())
        tombstones[request.commitId.stableKey]?.let { return@withLock PrepareResult.Tombstoned(it.commitKey) }
        current[request.commitId.stableKey]?.let { existing ->
            return@withLock if (existing.samePayload(request)) PrepareResult.Existing(existing)
            else {
                conflictTotal += 1
                publishDiagnostics(current)
                PrepareResult.Conflict(existing)
            }
        }
        current.values.firstOrNull { it.backendId == request.commitId.backendId &&
            it.sessionId == request.sessionId &&
            (it.turnId == request.turnId || it.state != ReviewCommitState.COMMITTED) }?.let {
            conflictTotal += 1
            publishDiagnostics(current)
            return@withLock PrepareResult.Conflict(it)
        }
        if (current.size >= maxRecords) return@withLock PrepareResult.Full
        val now = clock()
        val frozen = semantics?.enforced(request.commitId.backendId)
        val record = ReviewCommitRecord(
            commitId = request.commitId, card = request.card, rating = request.rating,
            state = ReviewCommitState.NOT_STARTED, attemptCount = 0, deckRef = request.deckRef,
            createdAtEpochMs = now, updatedAtEpochMs = now,
            ratedAtEpochMs = request.ratedAtEpochMs, answerDurationMs = request.answerDurationMs,
            evidence = request.evidence,
            frozenGuarantee = frozen?.guaranteeLevel,
            frozenIdempotentReplay = frozen?.supportsIdempotentReplay == true,
            frozenAuthoritativeReconciliation = frozen?.supportsAuthoritativeReconciliation == true
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
                ReviewCommitState.SUBMITTING -> {
                    duplicateRejectedTotal += 1
                    publishDiagnostics(current)
                    return@withLock ClaimResult.InFlight(record)
                }
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

    /** A backend violated its boundary callback contract, or reported unknown before entry. No safe retry. */
    suspend fun markBoundaryViolation(
        commitId: ReviewCommitId,
        category: String = "backend_boundary_violation"
    ): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.state != ReviewCommitState.SUBMITTING ||
            record.phase != CommitAttemptPhase.PREPARED) return@withLock null
        persistRecordLocked(current, record.copy(state = ReviewCommitState.AMBIGUOUS,
            phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = ReviewCommitFailure(category.take(96), false),
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

    /**
     * Records that the UI left the transaction. Does not change [ReviewCommitState] and does not
     * make an ambiguous or in-flight mutation retryable.
     */
    suspend fun noteAbandoned(commitId: ReviewCommitId, abandonedAtEpochMs: Long): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        persistRecordLocked(current, record.copy(abandonedAtEpochMs = abandonedAtEpochMs, updatedAtEpochMs = clock()))
    }

    /**
     * Optimistic transition. Rejected before any write when the state or version is stale, or when
     * [ReviewCommitTransitions.apply] says the command is illegal. In-process callers are also
     * serialized by [mutex]; the version check is the explicit concurrency token.
     */
    suspend fun transition(
        commitId: ReviewCommitId,
        expectedState: ReviewCommitState,
        expectedVersion: Long,
        transition: ReviewCommitTransition
    ): TransitionResult {
        return mutex.withLock {
            val current = loadedLocked() ?: return@withLock TransitionResult.Unavailable(reason())
            val record = current[commitId.stableKey] ?: return@withLock TransitionResult.Missing
            if (record.state != expectedState) return@withLock TransitionResult.StateMismatch(record)
            if (record.version != expectedVersion) return@withLock TransitionResult.VersionMismatch(record)
            val next = ReviewCommitTransitions.apply(record, transition, clock())
                ?: return@withLock TransitionResult.Rejected(record)
            val saved = persistRecordLocked(current, next) ?: return@withLock TransitionResult.Rejected(record)
            TransitionResult.Applied(saved)
        }
    }

    /**
     * Drops old COMMITTED payloads only. Unresolved rows are never removed. Identity is kept as a
     * tombstone so the same commit id cannot be prepared again. Not called from the mutation path.
     * Returns the number of payloads removed. `0` if the store is unavailable or the write fails.
     */
    suspend fun pruneCommitted(olderThanEpochMs: Long, activeSessionIds: Set<String> = emptySet()): Int = mutex.withLock {
        val current = loadedLocked() ?: return@withLock 0
        val victims = current.values.filter { record ->
            record.state == ReviewCommitState.COMMITTED &&
                record.sessionId !in activeSessionIds &&
                (record.resolvedAtEpochMs ?: record.updatedAtEpochMs) < olderThanEpochMs
        }
        if (victims.isEmpty() || tombstones.size + victims.size > MAX_TOMBSTONES) return@withLock 0
        val now = clock()
        val added = victims.map { CommitIdentityTombstone(it.commitId.stableKey, now) }
        added.forEach { tombstones[it.commitKey] = it }
        val next = LinkedHashMap(current).apply { victims.forEach { remove(it.commitId.stableKey) } }
        if (persistLocked(next) != null) {
            added.forEach { tombstones.remove(it.commitKey) }
            return@withLock 0
        }
        victims.size
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
            is ReviewCommitLedgerCodec.Decoded.Records -> {
                tombstones = LinkedHashMap<String, CommitIdentityTombstone>().apply {
                    decoded.tombstones.forEach { put(it.commitKey, it) }
                }
                decoded.records
            }
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
        recoveryTotal = interrupted.toLong()
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

    private fun responseFor(record: ReviewCommitRecord, result: CommitRatingResult): CommitResponseEvidence? =
        ReviewCommitTransitions.responseEvidence(record, result)

    private fun terminalFromResponse(record: ReviewCommitRecord, resolution: String): ReviewCommitRecord =
        ReviewCommitTransitions.terminalFromResponse(record, resolution, clock())

    private suspend fun persistRecordLocked(
        current: LinkedHashMap<String, ReviewCommitRecord>, record: ReviewCommitRecord
    ): ReviewCommitRecord? {
        val previous = current[record.commitId.stableKey]
        val stamped = if (previous == null) {
            // First durable intent. Anything else appearing without a predecessor is a bug, not a write.
            if (record.state != ReviewCommitState.NOT_STARTED || record.attemptCount != 0 || record.response != null) {
                return null
            }
            record.copy(version = 1)
        } else {
            record.copy(version = previous.version + 1)
        }
        if (previous != null && !ReviewCommitTransitions.allowed(previous, stamped)) return null
        if (previous != null) countTransition(previous, stamped)
        return if (persistLocked(LinkedHashMap(current).apply { put(stamped.commitId.stableKey, stamped) }) == null) {
            stamped
        } else {
            if (previous != null) undoTransitionCount(previous, stamped)
            null
        }
    }

    private fun countTransition(before: ReviewCommitRecord, after: ReviewCommitRecord) {
        if (before.state != ReviewCommitState.SUBMITTING && after.state == ReviewCommitState.SUBMITTING) attemptTotal += 1
        if (before.state != ReviewCommitState.COMMITTED && after.state == ReviewCommitState.COMMITTED) successTotal += 1
        if (before.state != ReviewCommitState.FAILED && after.state == ReviewCommitState.FAILED && after.safeToRetry) {
            safeFailureTotal += 1
        }
        if (before.state != ReviewCommitState.AMBIGUOUS && after.state == ReviewCommitState.AMBIGUOUS) ambiguousTotal += 1
        if (before.state == ReviewCommitState.AMBIGUOUS && after.state == ReviewCommitState.AMBIGUOUS &&
            after.resolution == ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE) {
            reconciliationUnresolvedTotal += 1
        }
    }

    private fun undoTransitionCount(before: ReviewCommitRecord, after: ReviewCommitRecord) {
        if (before.state != ReviewCommitState.SUBMITTING && after.state == ReviewCommitState.SUBMITTING) attemptTotal -= 1
        if (before.state != ReviewCommitState.COMMITTED && after.state == ReviewCommitState.COMMITTED) successTotal -= 1
        if (before.state != ReviewCommitState.FAILED && after.state == ReviewCommitState.FAILED && after.safeToRetry) {
            safeFailureTotal -= 1
        }
        if (before.state != ReviewCommitState.AMBIGUOUS && after.state == ReviewCommitState.AMBIGUOUS) ambiguousTotal -= 1
        if (before.state == ReviewCommitState.AMBIGUOUS && after.state == ReviewCommitState.AMBIGUOUS &&
            after.resolution == ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE) {
            reconciliationUnresolvedTotal -= 1
        }
    }

    private suspend fun writeLocked(next: LinkedHashMap<String, ReviewCommitRecord>): String? {
        // DataStore's suspending edit completes before returning. Never launch this in another job.
        // Tombstones travel with every snapshot so a later commit write cannot forget pruned ids.
        val written = try {
            withContext(NonCancellable) {
                store.write(ReviewCommitLedgerCodec.encode(next.values, tombstones.values))
            }
        } catch (_: Exception) { false }
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
        /** Committed payloads may be compacted after this age. Identity tombstones are kept. */
        const val COMMITTED_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_TOMBSTONES = 50_000
    }
}
