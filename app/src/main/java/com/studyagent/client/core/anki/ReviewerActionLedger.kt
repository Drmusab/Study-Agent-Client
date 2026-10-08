package com.studyagent.client.core.anki

import com.studyagent.client.core.common.orOnStoreFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * GATE 13 §14 — one atomic durable cell for the reviewer-action ledger. `write` must finish the
 * replacement before returning `true`; a `false` means nothing became durable.
 *
 * A separate store from [ReviewCommitStore] by construction: the two ledgers have separate domains
 * (INV-13-02/INV-13-03) and therefore separate files, so a corrupt action ledger can never take the
 * rating transaction truth with it.
 */
interface ReviewerActionStore {
    suspend fun read(): ReviewerActionStoreRead
    suspend fun write(snapshot: String): Boolean
}

sealed interface ReviewerActionStoreRead {
    data class Snapshot(val value: String?) : ReviewerActionStoreRead
    /** Never interpret corrupt or unreadable storage as an empty ledger. */
    data class Unreadable(val reason: String) : ReviewerActionStoreRead
}

/**
 * Durable wire format for the reviewer-action ledger.
 *
 * The record's [ReviewerAction] is written as its stable, content-free [ReviewerAction.key] and
 * re-parsed on read ([actionFromKey]): the wire format never depends on a serialization plugin's
 * polymorphic naming, and a key this build cannot parse makes the whole snapshot
 * [Decoded.Unreadable] instead of being guessed at.
 *
 * Nothing in here is card content (§4): identifiers, a queue code, a flag name, timestamps and
 * small stable tokens.
 */
object ReviewerActionLedgerCodec {
    const val SCHEMA_VERSION = 1

    /** §15 — two active actions for one turn is a structural anomaly, not a decodable state. */
    const val MULTIPLE_ACTIVE_PER_TURN: String = "multiple_active_actions_per_turn"

    /** One unresolved action per study session; more is an integrity anomaly (§15/§23). */
    const val MULTIPLE_UNRESOLVED_PER_SESSION: String = "multiple_unresolved_actions_per_session"

    @Serializable
    private data class ReceiptWire(
        val receiptId: String? = null,
        val queueCode: Int? = null,
        val flag: String? = null,
        val detail: String? = null,
        val observedAtEpochMs: Long? = null
    )

    @Serializable
    private data class RecordWire(
        val actionId: String,
        val sessionId: String,
        val turnId: ReviewTurnId,
        val backendId: AnkiBackendId,
        val collectionKey: String? = null,
        val card: AnkiCardRef,
        val actionKey: String,
        val status: ReviewerActionStatus,
        val attemptCount: Int,
        val receipt: ReceiptWire? = null,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val submittedAtEpochMs: Long? = null,
        val resolvedAtEpochMs: Long? = null,
        val failureCategory: String? = null,
        val resolution: String? = null,
        val frozenIdempotentReplay: Boolean = false,
        val frozenAuthoritativeReconciliation: Boolean = false,
        val version: Long = 0L
    )

    @Serializable
    private data class Envelope(
        val schemaVersion: Int,
        val records: List<RecordWire>
    )

    private val json = Json { encodeDefaults = true }

    sealed interface Decoded {
        data class Records(val records: List<ReviewerActionRecord>) : Decoded
        data class Unreadable(val reason: String) : Decoded
    }

    fun encode(records: Collection<ReviewerActionRecord>): String =
        json.encodeToString(Envelope.serializer(), Envelope(SCHEMA_VERSION, records.map { it.toWire() }))

    fun decode(snapshot: String): Decoded {
        val root = runCatching { json.parseToJsonElement(snapshot) as? JsonObject }.getOrNull()
            ?: return Decoded.Unreadable("malformed_snapshot")
        val schemaVersion = runCatching { root["schemaVersion"]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?: return Decoded.Unreadable("malformed_snapshot")
        if (schemaVersion != SCHEMA_VERSION) {
            return Decoded.Unreadable("unsupported_schema_$schemaVersion")
        }
        val envelope = runCatching {
            json.decodeFromJsonElement(Envelope.serializer(), root)
        }.getOrNull() ?: return Decoded.Unreadable("malformed_snapshot")
        val records = runCatching { envelope.records.map { it.toDomain() } }.getOrNull()
            ?: return Decoded.Unreadable("unreadable_action_record")
        if (records.any { it == null }) return Decoded.Unreadable("unreadable_action_record")
        val decoded = records.filterNotNull()
        if (decoded.map { it.actionId }.distinct().size != decoded.size) {
            return Decoded.Unreadable("duplicate_action_ids")
        }
        // §15 — one active reviewer action per turn at most.
        val active = decoded.filter { it.isUnresolved }
        if (active.map { it.backendId to it.turnId }.distinct().size != active.size) {
            return Decoded.Unreadable(MULTIPLE_ACTIVE_PER_TURN)
        }
        if (active.map { it.backendId to it.sessionId }.distinct().size != active.size) {
            return Decoded.Unreadable(MULTIPLE_UNRESOLVED_PER_SESSION)
        }
        // Re-check every canonical invariant: a snapshot that contradicts itself is unreadable,
        // never partially trusted.
        if (decoded.any { !ReviewerActionTransitions.valid(it) }) {
            return Decoded.Unreadable("contradictory_action_metadata")
        }
        return Decoded.Records(decoded)
    }

    /** The stable action key → the domain action. Unknown keys are unreadable, never guessed. */
    fun actionFromKey(key: String): ReviewerAction? = when {
        key == ReviewerAction.BuryCard.key -> ReviewerAction.BuryCard
        key == ReviewerAction.SuspendCard.key -> ReviewerAction.SuspendCard
        key.startsWith(FLAG_KEY_PREFIX) -> AnkiFlag.entries
            .firstOrNull { it.name.lowercase() == key.removePrefix(FLAG_KEY_PREFIX) }
            ?.takeIf { it != AnkiFlag.UNKNOWN }
            ?.let { ReviewerAction.SetFlag(it) }
        else -> null
    }

    private const val FLAG_KEY_PREFIX = "flag:"

    private fun ReviewerActionRecord.toWire(): RecordWire = RecordWire(
        actionId = actionId.value,
        sessionId = sessionId,
        turnId = turnId,
        backendId = backendId,
        collectionKey = collectionRef?.collectionKey,
        card = cardRef,
        actionKey = action.key,
        status = status,
        attemptCount = attemptCount,
        receipt = backendReceipt?.let {
            ReceiptWire(
                receiptId = it.receiptId,
                queueCode = it.cardState?.queueCode,
                flag = it.flag?.name,
                detail = it.detail,
                observedAtEpochMs = it.observedAtEpochMs
            )
        },
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        submittedAtEpochMs = submittedAtEpochMs,
        resolvedAtEpochMs = resolvedAtEpochMs,
        failureCategory = failure?.category,
        resolution = resolution,
        frozenIdempotentReplay = frozenIdempotentReplay,
        frozenAuthoritativeReconciliation = frozenAuthoritativeReconciliation,
        version = version
    )

    private fun RecordWire.toDomain(): ReviewerActionRecord? {
        val action = actionFromKey(actionKey) ?: return null
        val backend = backendId
        val id = ReviewerActionId.of(backend, sessionId, turnId, action)
        // The stored id must be the one this identity derives to: a rewritten id is not a record
        // this ledger could have produced.
        if (id.value != actionId) return null
        val collection = collectionKey?.let { AnkiCollectionIdentity(backend, it) }
        val receipt = receipt?.let { wire ->
            val flag = wire.flag?.let { name -> AnkiFlag.entries.firstOrNull { it.name == name } }
            if (wire.flag != null && flag == null) return null
            ReviewerActionReceipt(
                backendId = backend,
                actionKey = actionKey,
                receiptId = wire.receiptId,
                cardState = wire.queueCode?.let { ReviewerCardState.fromQueue(it) },
                flag = flag,
                detail = wire.detail,
                observedAtEpochMs = wire.observedAtEpochMs
            )
        }
        return runCatching {
            ReviewerActionRecord(
                actionId = id,
                sessionId = sessionId,
                turnId = turnId,
                backendId = backend,
                collectionRef = collection,
                cardRef = card,
                action = action,
                status = status,
                attemptCount = attemptCount,
                backendReceipt = receipt,
                createdAtEpochMs = createdAtEpochMs,
                updatedAtEpochMs = updatedAtEpochMs,
                submittedAtEpochMs = submittedAtEpochMs,
                resolvedAtEpochMs = resolvedAtEpochMs,
                failure = failureCategory?.let { ReviewerActionFailure(it) },
                resolution = resolution,
                frozenIdempotentReplay = frozenIdempotentReplay,
                frozenAuthoritativeReconciliation = frozenAuthoritativeReconciliation,
                version = version
            )
        }.getOrNull()
    }
}

/** Non-blocking diagnostics. No ids, card text, receipts, collection names or error text. */
data class ReviewerActionLedgerDiagnostics(
    val health: String = "Not checked",
    val records: Int? = null,
    val prepared: Int? = null,
    val submitting: Int? = null,
    val applied: Int? = null,
    val retryAllowed: Int? = null,
    val ambiguous: Int? = null,
    val lastWriteFailed: Boolean = false,
    /** Cumulative counters for this process. Not card content. The ambiguous rate is the signal. */
    val attemptTotal: Long = 0,
    val appliedTotal: Long = 0,
    val notAppliedTotal: Long = 0,
    val ambiguousTotal: Long = 0,
    val conflictTotal: Long = 0,
    val duplicateRejectedTotal: Long = 0,
    val recoveryTotal: Long = 0,
    val reconciliationUnresolvedTotal: Long = 0
)

/**
 * GATE 13 §14 — the reviewer-action ledger contract: reviewer-action transaction truth
 * (INV-13-02), and the only writer of [ReviewerActionStatus].
 *
 * There is deliberately **no** unrestricted `save(record)` or `updateStatus(anything)`: every state
 * change goes through [transition], which applies the closed engine
 * ([ReviewerActionTransitions]) and refuses anything the §11 table does not list.
 *
 * The spec writes `create` returning nothing and `transition` returning the record. A write that
 * could not be made durable must be *reportable*, because §17/INV-13-08 forbid entering the backend
 * mutation before `SUBMITTING` is durable — a caller that cannot tell "refused" from "not persisted"
 * cannot fail closed. Both methods therefore return [ReviewerActionLedgerWrite], whose success
 * cases carry the durable record.
 */
interface ReviewerActionLedger {

    /** Whether the durable store is readable and structurally valid (§14/§26: fail closed). */
    suspend fun health(): ReviewerActionLedgerHealth

    /** The first durable intent (§11 row 1: `no record → PrepareAction → PREPARED`). */
    suspend fun create(record: ReviewerActionRecord): ReviewerActionLedgerWrite

    suspend fun get(actionId: ReviewerActionId): ReviewerActionRecord?

    /**
     * §15 — the one active (not [ReviewerActionStatus.APPLIED]) action of a turn, or `null`.
     * A ledger holding more than one for a turn is structurally invalid and surfaces as
     * [ReviewerActionLedgerWrite.Unavailable], never as an arbitrary pick.
     */
    suspend fun findActiveForTurn(turnId: ReviewTurnId): ReviewerActionRecord?

    /**
     * Applies one command of the closed transition table, refusing `expectedStatus` mismatches
     * before any write (§11/§14).
     */
    suspend fun transition(
        actionId: ReviewerActionId,
        expectedStatus: ReviewerActionStatus,
        event: ReviewerActionTransition
    ): ReviewerActionLedgerWrite

    /** §26 — everything not [ReviewerActionStatus.APPLIED], in a deterministic order. */
    suspend fun unresolved(): List<ReviewerActionRecord>

    /**
     * §26/§30 — the durable action that must block `beginReview`/`nextCard` for this
     * backend/collection/session, or `null` when none does. Called during the startup recovery scan
     * before any scheduler query.
     */
    suspend fun recoveryBlocker(
        backendId: AnkiBackendId,
        collectionKey: String?,
        sessionId: String
    ): ReviewerActionRecord?
}

/** GATE 13 §14 — readiness of the durable action store. */
sealed interface ReviewerActionLedgerHealth {
    data object Ready : ReviewerActionLedgerHealth

    /** The store could not be read, or holds a structurally invalid snapshot: fail closed. */
    data class Unavailable(val reason: String) : ReviewerActionLedgerHealth
}

/** The typed result of a ledger write. Success cases carry the durable record. */
sealed interface ReviewerActionLedgerWrite {

    val recordOrNull: ReviewerActionRecord?
        get() = when (this) {
            is Created -> record
            is Transitioned -> record
            is Existing -> record
            is Conflict -> record
            is StatusMismatch -> actual
            is Rejected -> actual
            is Missing -> null
            is Full -> null
            is StoreFailed -> null
            is Unavailable -> null
        }

    /** True only when this write is durable. Nothing else may be treated as a state change. */
    val isDurable: Boolean get() = this is Created || this is Transitioned || this is Existing

    /** The record was created and is durable. */
    data class Created(val record: ReviewerActionRecord) : ReviewerActionLedgerWrite

    /** A command was applied and is durable. */
    data class Transitioned(val record: ReviewerActionRecord) : ReviewerActionLedgerWrite

    /**
     * The same logical action is already durably recorded with the same payload: [create] is
     * idempotent, so a duplicate request never produces a second record (§15, VERIFICATION 13).
     */
    data class Existing(val record: ReviewerActionRecord) : ReviewerActionLedgerWrite

    /**
     * Refused before any write: a different payload for the same id, another active action for the
     * turn (§15), or an unresolved rating transaction for the turn (§25).
     */
    data class Conflict(val record: ReviewerActionRecord) : ReviewerActionLedgerWrite

    /** The durable status is not the one the caller expected; nothing was written. */
    data class StatusMismatch(val actual: ReviewerActionRecord) : ReviewerActionLedgerWrite

    /** The closed transition engine rejected the command (§11/§12/§13); nothing was written. */
    data class Rejected(
        val actual: ReviewerActionRecord?,
        val reason: ReviewerActionTransitionRejection
    ) : ReviewerActionLedgerWrite

    /** No record with this id. Unknown is not the same as "not applied". */
    data object Missing : ReviewerActionLedgerWrite

    /** At capacity with nothing prunable: no new action is accepted (never an eviction of truth). */
    data object Full : ReviewerActionLedgerWrite

    /** The store refused the write. Memory stays at the last durable snapshot. */
    data class StoreFailed(val reason: String) : ReviewerActionLedgerWrite

    /** The ledger could not be read (or is structurally invalid): fail closed. */
    data class Unavailable(val reason: String) : ReviewerActionLedgerWrite
}

/**
 * GATE 13 §14 — the durable reviewer-action ledger.
 *
 * All durable state transitions and process-restore decisions live here, under one mutex. No caller
 * may copy a record to another state. If any write fails, memory stays at the last known durable
 * snapshot; especially a backend success is NOT exposed as [ReviewerActionStatus.APPLIED] before it
 * is durable (§17: "persist final status ↓ emit StudyEvent").
 *
 * Unresolved records are never evicted: at capacity the ledger prunes only `APPLIED` records of
 * *other* turns, and refuses new actions when nothing is prunable — the at-most-once guarantee is
 * never quietly weakened.
 */
class DurableReviewerActionLedger(
    private val store: ReviewerActionStore,
    private val clock: () -> Long,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS
) : ReviewerActionLedger {

    init { require(maxRecords > 0) }

    private val mutex = Mutex()
    private var records: LinkedHashMap<ReviewerActionId, ReviewerActionRecord>? = null
    private var unavailableReason: String? = null
    private var attemptTotal = 0L
    private var appliedTotal = 0L
    private var notAppliedTotal = 0L
    private var ambiguousTotal = 0L
    private var conflictTotal = 0L
    private var duplicateRejectedTotal = 0L
    private var recoveryTotal = 0L
    private var reconciliationUnresolvedTotal = 0L

    @Volatile private var diagnostics = ReviewerActionLedgerDiagnostics()

    /** Safe to call from synchronous diagnostics/UI code. Never triggers a storage read. */
    fun diagnosticsSnapshot(): ReviewerActionLedgerDiagnostics = diagnostics

    override suspend fun health(): ReviewerActionLedgerHealth = mutex.withLock {
        if (loadedLocked() != null) ReviewerActionLedgerHealth.Ready
        else ReviewerActionLedgerHealth.Unavailable(reason())
    }

    override suspend fun get(actionId: ReviewerActionId): ReviewerActionRecord? = mutex.withLock {
        loadedLocked()?.get(actionId)
    }

    override suspend fun findActiveForTurn(turnId: ReviewTurnId): ReviewerActionRecord? = mutex.withLock {
        loadedLocked()?.values?.filter { it.turnId == turnId && it.isUnresolved }?.singleOrNull()
    }

    /** Every active (non-`APPLIED`) action, in a deterministic order. */
    suspend fun active(): List<ReviewerActionRecord> = mutex.withLock {
        loadedLocked()?.values?.filter { it.isUnresolved }?.sortedDeterministically().orEmpty()
    }

    override suspend fun unresolved(): List<ReviewerActionRecord> = active()

    suspend fun snapshot(): List<ReviewerActionRecord> = mutex.withLock {
        loadedLocked()?.values?.sortedDeterministically().orEmpty()
    }

    /** AMBIGUOUS entries, for the "prior unresolved" surface at session start. */
    suspend fun pendingRecovery(backendId: AnkiBackendId? = null): List<ReviewerActionRecord> =
        mutex.withLock {
            loadedLocked()?.values?.filter {
                it.status == ReviewerActionStatus.AMBIGUOUS && (backendId == null || it.backendId == backendId)
            }?.sortedDeterministically().orEmpty()
        }

    /**
     * Called before `beginReview`/`nextCard`: block the affected session and, for an action whose
     * outcome is unknown, any new session in its known collection (§30: after process death, do not
     * issue the action again and do not load the next card — reconcile, or remain blocked).
     *
     * A `PREPARED` record is provably un-entered and therefore never blocks another session;
     * `RETRY_ALLOWED` is proven not applied and blocks only its own session's turn.
     */
    override suspend fun recoveryBlocker(
        backendId: AnkiBackendId,
        collectionKey: String?,
        sessionId: String
    ): ReviewerActionRecord? = mutex.withLock {
        loadedLocked()?.values?.firstOrNull { record ->
            record.backendId == backendId && record.isUnresolved &&
                (record.sessionId == sessionId ||
                    (record.status == ReviewerActionStatus.AMBIGUOUS ||
                        record.status == ReviewerActionStatus.SUBMITTING) &&
                    (collectionKey == null || record.collectionRef?.collectionKey == null ||
                        record.collectionRef.collectionKey == collectionKey))
        }
    }

    override suspend fun create(record: ReviewerActionRecord): ReviewerActionLedgerWrite = mutex.withLock {
        val current = loadedLocked() ?: return@withLock ReviewerActionLedgerWrite.Unavailable(reason())
        current[record.actionId]?.let { existing ->
            return@withLock if (existing.sameAction(record)) {
                // Idempotent by identity: a duplicate request is one logical action, never a second
                // record and never a second mutation (§15, VERIFICATION 13).
                duplicateRejectedTotal += 1
                publishDiagnostics(current)
                ReviewerActionLedgerWrite.Existing(existing)
            } else refused(current, ReviewerActionLedgerWrite.Conflict(existing))
        }
        // §15 — one active reviewer action per turn at most.
        current.values.firstOrNull {
            it.backendId == record.backendId && it.turnId == record.turnId && it.isUnresolved
        }?.let { return@withLock refused(current, ReviewerActionLedgerWrite.Conflict(it)) }
        // One unresolved action per study session: turns are sequential, so a second unresolved
        // action means the projection and the ledger disagree.
        current.values.firstOrNull {
            it.backendId == record.backendId && it.sessionId == record.sessionId && it.isUnresolved
        }?.let { return@withLock refused(current, ReviewerActionLedgerWrite.Conflict(it)) }
        when (val initial = ReviewerActionTransitions.validateInitial(record)) {
            is ReviewerActionTransitionResult.Rejected ->
                return@withLock ReviewerActionLedgerWrite.Rejected(null, initial.reason)
            is ReviewerActionTransitionResult.Applied -> Unit
        }
        val next = LinkedHashMap(current)
        if (next.size >= maxRecords) {
            val prunable = next.values
                .filter { it.status == ReviewerActionStatus.APPLIED && it.turnId != record.turnId }
                .minByOrNull { it.updatedAtEpochMs }
                ?: return@withLock ReviewerActionLedgerWrite.Full
            next.remove(prunable.actionId)
        }
        next[record.actionId] = record
        persistLocked(next)?.let { return@withLock ReviewerActionLedgerWrite.StoreFailed(it) }
        publishDiagnostics(next)
        ReviewerActionLedgerWrite.Created(record)
    }

    override suspend fun transition(
        actionId: ReviewerActionId,
        expectedStatus: ReviewerActionStatus,
        event: ReviewerActionTransition
    ): ReviewerActionLedgerWrite = mutex.withLock {
        val current = loadedLocked() ?: return@withLock ReviewerActionLedgerWrite.Unavailable(reason())
        val record = current[actionId] ?: return@withLock ReviewerActionLedgerWrite.Missing
        if (record.status != expectedStatus) {
            return@withLock ReviewerActionLedgerWrite.StatusMismatch(record)
        }
        when (val result = ReviewerActionTransitions.transition(record, event, clock())) {
            is ReviewerActionTransitionResult.Rejected -> {
                if (record.status == ReviewerActionStatus.SUBMITTING &&
                    event is ReviewerActionTransition.EnterMutationBoundary
                ) {
                    // A second boundary crossing for one attempt is a duplicate dispatch attempt.
                    duplicateRejectedTotal += 1
                }
                publishDiagnostics(current)
                return@withLock ReviewerActionLedgerWrite.Rejected(record, result.reason)
            }
            is ReviewerActionTransitionResult.Applied -> {
                val next = result.record
                if (!ReviewerActionTransitions.validWrite(record, next)) {
                    return@withLock ReviewerActionLedgerWrite.Rejected(
                        record, ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION)
                }
                val stamped = next.copy(version = record.version + 1)
                val updated = LinkedHashMap(current).apply { put(actionId, stamped) }
                persistLocked(updated)?.let { return@withLock ReviewerActionLedgerWrite.StoreFailed(it) }
                count(event)
                publishDiagnostics(updated)
                ReviewerActionLedgerWrite.Transitioned(stamped)
            }
        }
    }

    private fun count(event: ReviewerActionTransition) {
        when (event) {
            ReviewerActionTransition.EnterMutationBoundary -> attemptTotal += 1
            is ReviewerActionTransition.BackendConfirmedApplied,
            is ReviewerActionTransition.ReconciliationConfirmedApplied -> appliedTotal += 1
            is ReviewerActionTransition.BackendConfirmedNotApplied,
            ReviewerActionTransition.ReconciliationConfirmedNotApplied -> notAppliedTotal += 1
            is ReviewerActionTransition.BackendOutcomeUnknown -> ambiguousTotal += 1
            is ReviewerActionTransition.ReconciliationUnresolved -> reconciliationUnresolvedTotal += 1
            ReviewerActionTransition.RetryRequested -> Unit
        }
        if (event.recoveryEventOrNull() != null) recoveryTotal += 1
    }

    private fun refused(
        current: Map<ReviewerActionId, ReviewerActionRecord>,
        write: ReviewerActionLedgerWrite
    ): ReviewerActionLedgerWrite {
        conflictTotal += 1
        publishDiagnostics(current)
        return write
    }

    private fun publishDiagnostics(current: Map<ReviewerActionId, ReviewerActionRecord>, writeFailed: Boolean = false) {
        diagnostics = ReviewerActionLedgerDiagnostics(
            health = if (writeFailed) "Last write failed" else "Ready",
            records = current.size,
            prepared = current.values.count { it.status == ReviewerActionStatus.PREPARED },
            submitting = current.values.count { it.status == ReviewerActionStatus.SUBMITTING },
            applied = current.values.count { it.status == ReviewerActionStatus.APPLIED },
            retryAllowed = current.values.count { it.status == ReviewerActionStatus.RETRY_ALLOWED },
            ambiguous = current.values.count { it.status == ReviewerActionStatus.AMBIGUOUS },
            lastWriteFailed = writeFailed,
            attemptTotal = attemptTotal,
            appliedTotal = appliedTotal,
            notAppliedTotal = notAppliedTotal,
            ambiguousTotal = ambiguousTotal,
            conflictTotal = conflictTotal,
            duplicateRejectedTotal = duplicateRejectedTotal,
            recoveryTotal = recoveryTotal,
            reconciliationUnresolvedTotal = reconciliationUnresolvedTotal
        )
    }

    /** First load wins; a corrupt or unreadable store is never an empty ledger. */
    private suspend fun loadedLocked(): LinkedHashMap<ReviewerActionId, ReviewerActionRecord>? {
        records?.let { return it }
        if (unavailableReason != null) return null
        when (val read = orOnStoreFailure<ReviewerActionStoreRead>(
            ReviewerActionStoreRead.Unreadable("store_read_threw")
        ) { store.read() }) {
            is ReviewerActionStoreRead.Unreadable -> {
                unavailableReason = read.reason
                publishDiagnostics(emptyMap())
                return null
            }
            is ReviewerActionStoreRead.Snapshot -> {
                val raw = read.value
                if (raw == null) {
                    val empty = linkedMapOf<ReviewerActionId, ReviewerActionRecord>()
                    records = empty
                    publishDiagnostics(empty)
                    return empty
                }
                when (val decoded = ReviewerActionLedgerCodec.decode(raw)) {
                    is ReviewerActionLedgerCodec.Decoded.Unreadable -> {
                        unavailableReason = decoded.reason
                        publishDiagnostics(emptyMap())
                        return null
                    }
                    is ReviewerActionLedgerCodec.Decoded.Records -> {
                        val loaded = linkedMapOf<ReviewerActionId, ReviewerActionRecord>()
                        decoded.records.forEach { loaded[it.actionId] = it }
                        records = loaded
                        publishDiagnostics(loaded)
                        return loaded
                    }
                }
            }
        }
    }

    private fun reason(): String = unavailableReason ?: "action_ledger_unavailable"

    /**
     * The whole-snapshot atomic write. On failure, memory is rolled back to the last durable
     * snapshot: a status that is not durable is not truth (§17, INV-13-08).
     */
    private suspend fun persistLocked(
        next: LinkedHashMap<ReviewerActionId, ReviewerActionRecord>
    ): String? {
        val snapshot = ReviewerActionLedgerCodec.encode(next.values)
        // A store whose contract says "report failure as a value" may still throw (IO, corrupt
        // file, a buggy adapter). That is a storage failure, not a state change: fail closed.
        val written = orOnStoreFailure(false) { store.write(snapshot) }
        if (!written) {
            publishDiagnostics(records.orEmpty(), writeFailed = true)
            return "store_write_failed"
        }
        records = next
        return null
    }

    private fun ReviewerActionRecord.sameAction(other: ReviewerActionRecord): Boolean =
        actionId == other.actionId && backendId == other.backendId && sessionId == other.sessionId &&
            turnId == other.turnId && cardRef == other.cardRef && collectionRef == other.collectionRef &&
            action == other.action

    private fun Iterable<ReviewerActionRecord>.sortedDeterministically(): List<ReviewerActionRecord> =
        sortedWith(compareBy(
            { it.collectionRef?.collectionKey ?: "" },
            { it.sessionId },
            { it.createdAtEpochMs },
            { it.actionId.value }
        ))

    companion object {
        const val DEFAULT_MAX_RECORDS = 64
    }
}
