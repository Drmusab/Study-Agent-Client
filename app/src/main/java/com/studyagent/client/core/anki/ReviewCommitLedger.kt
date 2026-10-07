package com.studyagent.client.core.anki

import com.studyagent.client.core.common.orOnStoreFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
data class CommitIdentityTombstone(
    val commitKey: String,
    val prunedAtEpochMs: Long,
    /** New schemas retain turn uniqueness after the committed payload is pruned. */
    val backendId: AnkiBackendId? = null,
    val turnId: ReviewTurnId? = null
) {
    init {
        require(commitKey.isNotBlank())
        require(prunedAtEpochMs >= 0)
        require((backendId == null) == (turnId == null))
    }
}

/**
 * Durable wire format for the ledger.
 *
 * Schema history:
 *
 * | Version | Change |
 * |---|---|
 * | 1 | single `FAILED` status plus a retryability bit; no attempt phase |
 * | 2 | adds the attempt phase |
 * | 3 | explicit `FAILED_SAFE_TO_RETRY` / `FAILED_NOT_RETRYABLE` statuses |
 * | 4 | GATE 11B canonical vocabulary: `state` → `status`, no `NOT_STARTED`, one `RETRY_ALLOWED`, [ReviewCommitPhase] names |
 *
 * Migration is conservative and total: a snapshot that cannot be mapped without guessing is
 * reported as [Decoded.Unreadable], never silently re-interpreted.
 */
object ReviewCommitLedgerCodec {
    const val SCHEMA_VERSION = 4

    /**
     * GATE 11D §30 — a stable ledger can never hold more than one unresolved commit per active
     * study session; a snapshot that does is a structural integrity anomaly, not a decodable
     * state. Recovery must treat it as [ReviewCommitRecoveryAction.IntegrityFailure] and never
     * guess which record is "the" transaction.
     */
    const val MULTIPLE_UNRESOLVED_PER_SESSION: String = "multiple_unresolved_per_session"

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
        val root = runCatching { json.parseToJsonElement(snapshot) as? JsonObject }.getOrNull()
            ?: return Decoded.Unreadable("malformed_snapshot")
        val schemaVersion = runCatching { root["schemaVersion"]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?: return Decoded.Unreadable("malformed_snapshot")
        if (schemaVersion !in 1..SCHEMA_VERSION) {
            return Decoded.Unreadable("unsupported_schema_$schemaVersion")
        }
        // Everything below SCHEMA_VERSION is rewritten into the canonical vocabulary before the
        // invariant-checked domain record is constructed: one vocabulary on disk, one in memory.
        val normalizedRoot = if (schemaVersion < SCHEMA_VERSION) {
            normalizeLegacyVocabulary(root) ?: return Decoded.Unreadable("malformed_legacy_records")
        } else root
        val envelope = runCatching {
            json.decodeFromJsonElement(Envelope.serializer(), normalizedRoot)
        }.getOrNull() ?: return Decoded.Unreadable("malformed_snapshot")
        val records = runCatching {
            when (schemaVersion) {
                1 -> envelope.records.map { migrateLegacy(it, hasAttemptPhase = false) }
                2 -> envelope.records.map { migrateLegacy(it, hasAttemptPhase = true) }
                else -> envelope.records
            }
        }.getOrNull() ?: return Decoded.Unreadable("contradictory_commit_metadata")
        if (records.map { it.commitId.stableKey }.distinct().size != records.size) {
            return Decoded.Unreadable("duplicate_commit_ids")
        }
        val unresolved = records.filter { it.status != ReviewCommitStatus.COMMITTED }
        if (unresolved.map { it.backendId to it.sessionId }.distinct().size != unresolved.size) {
            return Decoded.Unreadable(MULTIPLE_UNRESOLVED_PER_SESSION)
        }
        if (records.map { it.backendId to it.turnId }.distinct().size != records.size) {
            return Decoded.Unreadable("multiple_commits_per_turn")
        }
        if (records.any { !valid(it) }) return Decoded.Unreadable("contradictory_commit_metadata")
        if (envelope.tombstones.map { it.commitKey }.distinct().size != envelope.tombstones.size) {
            return Decoded.Unreadable("duplicate_tombstones")
        }
        val tombstones = envelope.tombstones.map { tombstone ->
            if (tombstone.backendId != null && tombstone.turnId != null) tombstone
            else parseTombstoneIdentity(tombstone.commitKey)?.let { (backendId, turnId) ->
                tombstone.copy(backendId = backendId, turnId = turnId)
            } ?: return Decoded.Unreadable("unreadable_tombstone_identity")
        }
        val knownTurnKeys = tombstones.map { it.backendId!! to it.turnId!! }
        if (knownTurnKeys.distinct().size != knownTurnKeys.size ||
            records.any { record -> knownTurnKeys.any { it.first == record.backendId && it.second == record.turnId } }) {
            return Decoded.Unreadable("tombstone_turn_conflict")
        }
        val liveKeys = records.map { it.commitId.stableKey }.toSet()
        if (tombstones.any { it.commitKey in liveKeys }) {
            return Decoded.Unreadable("tombstone_overlaps_record")
        }
        return Decoded.Records(records, tombstones)
    }

    /** Schema < 4 wire names → canonical names. Never guesses: an unmappable row fails the read. */
    private fun normalizeLegacyVocabulary(root: JsonObject): JsonObject? = runCatching {
        val updated = root.toMutableMap()
        val records = root.getValue("records").jsonArray.map { entry ->
            val record = entry.jsonObject.toMutableMap()
            val legacyStatus = record.remove("state")?.jsonPrimitive?.content
                ?: record["status"]?.jsonPrimitive?.content
                ?: error("legacy_status_missing")
            val failure = record["failure"]?.takeIf { it !is JsonNull }?.jsonObject
            val safeToRetry = failure?.get("safeToRetry")?.takeIf { it !is JsonNull }
                ?.jsonPrimitive?.booleanOrNull
            // Schemas 1/2 spelled both not-applied outcomes as one FAILED status plus a bit.
            val resolvedStatus = if (legacyStatus == "FAILED") {
                // Both outcomes are explicit. A missing/invalid legacy bit cannot safely choose.
                val retryable = safeToRetry ?: error("legacy_failed_retryability_missing")
                if (retryable) ReviewCommitStatus.RETRY_ALLOWED.name
                else ReviewCommitStatus.RETRY_ALLOWED.name
            } else when (legacyStatus) {
                "NOT_STARTED" -> ReviewCommitStatus.PREPARED.name
                "FAILED_SAFE_TO_RETRY", "FAILED_NOT_RETRYABLE" -> ReviewCommitStatus.RETRY_ALLOWED.name
                else -> legacyStatus
            }
            record["status"] = JsonPrimitive(resolvedStatus)
            val legacyPhase = record["phase"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            val mappedPhase = when (legacyPhase) {
                // A row written before the attempt phase existed is read by its status: canonical
                // SUBMITTING means the boundary was entered, so it is projected there, not to
                // INTENT_PERSISTED. Everything else is re-derived by migrateLegacy.
                null -> when (resolvedStatus) {
                    ReviewCommitStatus.SUBMITTING.name -> ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED.name
                    else -> null
                }
                "PREPARED" -> ReviewCommitPhase.INTENT_PERSISTED.name
                "MUTATION_CALL_ENTERED" -> ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED.name
                "MUTATION_RESPONSE_RECEIVED" -> ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED.name
                "LOCAL_RESULT_PERSISTED" -> ReviewCommitPhase.FINAL_STATUS_PERSISTED.name
                else -> legacyPhase
            }
            // Re-derived below for schemas 1/2 (migrateLegacy); schema 3 keeps the mapped name.
            if (mappedPhase != null) record["phase"] = JsonPrimitive(mappedPhase) else record.remove("phase")
            // Schema 3 "SUBMITTING/PREPARED" meant "claimed, boundary not entered": canonical PREPARED.
            if (legacyStatus == "SUBMITTING" && legacyPhase == "PREPARED") {
                record["status"] = JsonPrimitive(ReviewCommitStatus.PREPARED.name)
            }
            // The retryability bit has no durable meaning any more: RETRY_ALLOWED is the status.
            failure?.let { legacy ->
                record["failure"] = JsonObject(legacy.toMutableMap().apply { remove("safeToRetry") })
            }
            JsonObject(record)
        }
        updated["records"] = kotlinx.serialization.json.JsonArray(records)
        JsonObject(updated)
    }.getOrNull()

    private fun parseTombstoneIdentity(commitKey: String): Pair<AnkiBackendId, ReviewTurnId>? {
        val parts = parseIdentityParts(commitKey, 3) ?: return null
        if (parts[1].isBlank()) return null
        val backend = AnkiBackendId.fromStableId(parts[0]) ?: return null
        val turn = runCatching { ReviewTurnId(parts[2]) }.getOrNull() ?: return null
        return backend to turn
    }

    private fun parseIdentityParts(encoded: String, count: Int): List<String>? {
        var offset = 0
        val values = ArrayList<String>(count)
        repeat(count) {
            val separator = encoded.indexOf(':', offset)
            if (separator < 0) return null
            val length = encoded.substring(offset, separator).toIntOrNull() ?: return null
            if (length < 0) return null
            val start = separator + 1
            val end = start + length
            if (end > encoded.length) return null
            values += encoded.substring(start, end)
            offset = end
        }
        return values.takeIf { offset == encoded.length }
    }

    /**
     * Schemas 1/2 have no attempt phase at all: schema 1 SUBMITTING means "entered", never
     * "prepared", and that is the conservative reading kept here.
     */
    private fun migrateLegacy(record: ReviewCommitRecord, hasAttemptPhase: Boolean): ReviewCommitRecord = record.copy(
        phase = if (hasAttemptPhase) record.phase else when (record.status) {
            ReviewCommitStatus.PREPARED -> ReviewCommitPhase.INTENT_PERSISTED
            ReviewCommitStatus.SUBMITTING -> ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED
            ReviewCommitStatus.COMMITTED, ReviewCommitStatus.AMBIGUOUS -> ReviewCommitPhase.FINAL_STATUS_PERSISTED
            // A legacy not-retryable/safe failure is a resolved transaction: it was terminal even
            // when the legacy schema recorded no attempt phase for it.
            ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitPhase.FINAL_STATUS_PERSISTED
        },
        committedRating = if (record.status == ReviewCommitStatus.COMMITTED) record.rating else null
    )

    /** The canonical invariants of GATE 11B, re-checked for every record read from storage. */
    private fun valid(record: ReviewCommitRecord): Boolean {
        if (record.card.backendId != record.backendId ||
            (record.deckRef != null && (record.deckRef.backendId != record.backendId ||
                (record.deckRef.collectionKey != null && record.card.collectionKey != null &&
                    record.deckRef.collectionKey != record.card.collectionKey))) ||
            (record.collectionRef != null && record.collectionRef.backendId != record.backendId) ||
            (record.collectionRef?.collectionKey != null && record.card.collectionKey != null &&
                record.collectionRef.collectionKey != record.card.collectionKey) ||
            (record.collectionRef?.collectionKey != null && record.deckRef?.collectionKey != null &&
                record.collectionRef.collectionKey != record.deckRef.collectionKey)) return false
        if (record.phase == ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED && record.response == null) return false
        if (record.response != null && record.phase != ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED &&
            record.phase != ReviewCommitPhase.FINAL_STATUS_PERSISTED) return false
        if (record.response?.kind == CommitResponseKind.CONFIRMED_COMMITTED &&
            record.status in setOf(ReviewCommitStatus.AMBIGUOUS, ReviewCommitStatus.RETRY_ALLOWED)) return false
        if (record.status == ReviewCommitStatus.RETRY_ALLOWED &&
            record.response?.kind?.let { it != CommitResponseKind.CONFIRMED_NOT_APPLIED } == true) return false
        if (record.status == ReviewCommitStatus.AMBIGUOUS &&
            record.response?.kind?.let { it != CommitResponseKind.OUTCOME_UNKNOWN } == true) return false
        return when (record.status) {
            ReviewCommitStatus.PREPARED -> record.phase == ReviewCommitPhase.INTENT_PERSISTED &&
                record.response == null && record.failure == null && record.committedRating == null &&
                record.submittedAtEpochMs == null && record.resolvedAtEpochMs == null
            ReviewCommitStatus.SUBMITTING -> record.attemptCount > 0 && record.failure == null &&
                record.committedRating == null && record.submittedAtEpochMs != null &&
                record.resolvedAtEpochMs == null && record.phase in setOf(
                ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED, ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED)
            ReviewCommitStatus.COMMITTED -> record.attemptCount > 0 &&
                record.phase == ReviewCommitPhase.FINAL_STATUS_PERSISTED && record.failure == null &&
                record.committedRating == record.selectedRating &&
                (record.response == null || record.response.kind == CommitResponseKind.CONFIRMED_COMMITTED)
            ReviewCommitStatus.RETRY_ALLOWED -> record.failure != null && record.committedRating == null &&
                record.phase == ReviewCommitPhase.FINAL_STATUS_PERSISTED
            ReviewCommitStatus.AMBIGUOUS -> record.attemptCount > 0 && record.failure != null &&
                record.committedRating == null &&
                record.phase == ReviewCommitPhase.FINAL_STATUS_PERSISTED
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
        data class Rejected(val reason: ReviewCommitTransitionRejection) : PrepareResult
        data class Unavailable(val reason: String) : PrepareResult
    }

    sealed interface TransitionResult {
        data class Applied(val record: ReviewCommitRecord) : TransitionResult
        data class StatusMismatch(val actual: ReviewCommitRecord) : TransitionResult
        data class VersionMismatch(val actual: ReviewCommitRecord) : TransitionResult
        data class Rejected(
            val actual: ReviewCommitRecord,
            val reason: ReviewCommitTransitionRejection
        ) : TransitionResult
        data class StoreFailed(val reason: String) : TransitionResult
        data object Missing : TransitionResult
        data class Unavailable(val reason: String) : TransitionResult
    }

    sealed interface ClaimResult {
        data class Claimed(val record: ReviewCommitRecord) : ClaimResult
        data class InFlight(val record: ReviewCommitRecord) : ClaimResult
        data class AlreadyCommitted(val record: ReviewCommitRecord) : ClaimResult
        data class NotClaimable(val record: ReviewCommitRecord) : ClaimResult
        data class Rejected(val record: ReviewCommitRecord, val reason: ReviewCommitTransitionRejection) : ClaimResult
        data object Missing : ClaimResult
        data class StoreFailed(val reason: String) : ClaimResult
        data class Unavailable(val reason: String) : ClaimResult
    }

    private sealed interface PersistRecordResult {
        data class Saved(val record: ReviewCommitRecord) : PersistRecordResult
        data class Rejected(val reason: ReviewCommitTransitionRejection) : PersistRecordResult
        data class StoreFailed(val reason: String) : PersistRecordResult
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
            submitting = current.values.count { it.status == ReviewCommitStatus.SUBMITTING },
            ambiguous = current.values.count { it.status == ReviewCommitStatus.AMBIGUOUS },
            committed = current.values.count { it.status == ReviewCommitStatus.COMMITTED },
            safeFailures = current.values.count { it.status == ReviewCommitStatus.RETRY_ALLOWED },
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
     * Includes PREPARED, SUBMITTING, RETRY_ALLOWED and AMBIGUOUS — everything not COMMITTED.
     * Never includes COMMITTED.
     * More than one unresolved record for one session is an integrity error at decode time.
     */
    suspend fun unresolved(): List<ReviewCommitRecord> = mutex.withLock {
        loadedLocked()?.values?.filter { it.status != ReviewCommitStatus.COMMITTED }
            ?.sortedWith(compareBy({ it.collectionKey ?: "" }, { it.sessionId }, { it.createdAtEpochMs }, { it.commitId.stableKey }))
            .orEmpty()
    }

    suspend fun snapshot(): List<ReviewCommitRecord> = mutex.withLock { loadedLocked()?.values?.toList().orEmpty() }

    /** AMBIGUOUS entries remain visible even after the warning is acknowledged. */
    suspend fun pendingRecovery(backendId: AnkiBackendId? = null): List<ReviewCommitRecord> = mutex.withLock {
        loadedLocked()?.values?.filter { it.status == ReviewCommitStatus.AMBIGUOUS &&
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
                record.backendId == backendId && record.status != ReviewCommitStatus.COMMITTED &&
                    (record.sessionId == sessionId ||
                        // The durable status alone says whether the boundary may have been entered;
                        // PREPARED is provably un-entered and therefore never blocks another session.
                        (record.status == ReviewCommitStatus.AMBIGUOUS ||
                            record.status == ReviewCommitStatus.SUBMITTING) &&
                        (collectionKey == null || record.collectionKey == null ||
                            record.collectionKey == collectionKey))
            }
        }

    /**
     * First durable intent. Enforces one logical commit per backend-qualified turn before any backend call.
     * [semantics] is frozen onto a newly created record and is not rewritten if the record exists.
     */
    suspend fun prepare(request: CommitRatingRequest, semantics: CommitSemantics? = null): PrepareResult = mutex.withLock {
        val current = loadedLocked() ?: return@withLock PrepareResult.Unavailable(reason())
        val identity = request.commitId
        tombstones[identity.stableKey]?.let { return@withLock PrepareResult.Tombstoned(it.commitKey) }
        tombstones.values.firstOrNull { it.backendId == identity.backendId && it.turnId == identity.turnId }
            ?.let { return@withLock PrepareResult.Tombstoned(it.commitKey) }
        current[identity.stableKey]?.let { existing ->
            return@withLock if (existing.samePayload(request)) PrepareResult.Existing(existing)
            else {
                conflictTotal += 1
                publishDiagnostics(current)
                PrepareResult.Conflict(existing)
            }
        }
        current.values.firstOrNull { it.backendId == identity.backendId && it.turnId == identity.turnId }?.let {
            conflictTotal += 1
            publishDiagnostics(current)
            return@withLock PrepareResult.Conflict(it)
        }
        current.values.firstOrNull {
            it.backendId == identity.backendId && it.sessionId == request.sessionId &&
                it.status != ReviewCommitStatus.COMMITTED
        }?.let {
            conflictTotal += 1
            publishDiagnostics(current)
            return@withLock PrepareResult.Conflict(it)
        }
        if (current.size >= maxRecords) return@withLock PrepareResult.Full
        val now = clock()
        val frozen = semantics?.enforced(identity.backendId)
        val record = ReviewCommitRecord(
            commitId = identity, card = request.card, rating = request.rating,
            status = ReviewCommitStatus.PREPARED, attemptCount = 0,
            phase = ReviewCommitPhase.INTENT_PERSISTED, deckRef = request.deckRef,
            createdAtEpochMs = now, updatedAtEpochMs = now,
            ratedAtEpochMs = request.ratedAtEpochMs, answerDurationMs = request.answerDurationMs,
            evidence = request.evidence, collectionRef = request.collectionRef,
            frozenGuarantee = frozen?.guaranteeLevel,
            frozenIdempotentReplay = frozen?.supportsIdempotentReplay == true,
            frozenAuthoritativeReconciliation = frozen?.supportsAuthoritativeReconciliation == true
        )
        when (val initial = ReviewCommitTransitions.validateInitial(record)) {
            is ReviewCommitTransitionResult.Rejected -> return@withLock PrepareResult.Rejected(initial.reason)
            is ReviewCommitTransitionResult.Applied -> Unit
        }
        val next = LinkedHashMap(current).apply { put(identity.stableKey, record) }
        persistLocked(next)?.let { return@withLock PrepareResult.StoreFailed(it) }
        PrepareResult.Prepared(record)
    }

    /**
     * The durable attempt claim. [ReviewCommitStatus.PREPARED] stays PREPARED — the mutation
     * boundary is still un-entered — and [ReviewCommitStatus.RETRY_ALLOWED] first returns to
     * PREPARED through [ReviewCommitTransition.BeginRetry], so every attempt repeats the same
     * safety sequence (INV-11B-09). Exactly one claimant wins.
     */
    suspend fun claim(commitId: ReviewCommitId, evidence: ReviewCommitEvidence?, allowRetry: Boolean): ClaimResult =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock ClaimResult.Unavailable(reason())
            val record = current[commitId.stableKey] ?: return@withLock ClaimResult.Missing
            when (record.status) {
                ReviewCommitStatus.PREPARED -> {
                    if (record.claimedAtEpochMs != null) {
                        // GATE 11B §28: one claimed attempt at a time. The claim marker is
                        // durable, so a duplicate claim is refused even across a redelivery.
                        duplicateRejectedTotal += 1
                        publishDiagnostics(current)
                        return@withLock ClaimResult.InFlight(record)
                    }
                    return@withLock claimAttempt(current, record, evidence)
                }
                ReviewCommitStatus.RETRY_ALLOWED -> {
                    if (!allowRetry) return@withLock ClaimResult.NotClaimable(record)
                    // RETRY_ALLOWED → PREPARED is durable before the new attempt is claimed.
                    val prepared = when (val saved = persistTransitionLocked(
                        current, record, ReviewCommitTransition.BeginRetry)) {
                        is PersistRecordResult.Saved -> saved.record
                        is PersistRecordResult.StoreFailed -> return@withLock ClaimResult.StoreFailed(saved.reason)
                        is PersistRecordResult.Rejected -> return@withLock ClaimResult.Rejected(record, saved.reason)
                    }
                    return@withLock claimAttempt(current, prepared, evidence)
                }
                ReviewCommitStatus.SUBMITTING -> {
                    duplicateRejectedTotal += 1
                    publishDiagnostics(current)
                    return@withLock ClaimResult.InFlight(record)
                }
                ReviewCommitStatus.COMMITTED -> return@withLock ClaimResult.AlreadyCommitted(record)
                ReviewCommitStatus.AMBIGUOUS -> return@withLock ClaimResult.NotClaimable(record)
            }
        }

    private suspend fun claimAttempt(
        current: LinkedHashMap<String, ReviewCommitRecord>,
        record: ReviewCommitRecord,
        evidence: ReviewCommitEvidence?
    ): ClaimResult = when (val saved = persistTransitionLocked(
        current, record, ReviewCommitTransition.BeginAttempt(evidence))) {
        is PersistRecordResult.Saved -> ClaimResult.Claimed(saved.record)
        is PersistRecordResult.StoreFailed -> ClaimResult.StoreFailed(saved.reason)
        is PersistRecordResult.Rejected -> ClaimResult.Rejected(record, saved.reason)
    }

    /**
     * Releases the claim of an attempt that never reached the mutation boundary (cancellation or an
     * abandoned preflight). The status stays PREPARED: nothing was dispatched, so the transaction
     * needs no proof and remains claimable — including in this process, immediately.
     */
    suspend fun releaseClaim(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        applyTransitionLocked(current, record, ReviewCommitTransition.ReleaseClaim)
    }

    /** Must durably complete at the callback immediately before the real scheduler mutation. */
    suspend fun markMutationEntered(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        applyTransitionLocked(current, record, ReviewCommitTransition.EnterMutationBoundary)
    }

    /** Durably record the actual classified backend answer before any terminal state transition. */
    suspend fun markResponseReceived(commitId: ReviewCommitId, result: BackendCommitResult): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            applyTransitionLocked(current, record, ReviewCommitTransition.BackendResponseReceived(result))
        }

    /**
     * SUBMITTING + durably recorded response → terminal. A failed write returns null, leaves
     * memory and disk nonterminal and MUST NOT cause a success event or a next-card query.
     */
    suspend fun complete(commitId: ReviewCommitId, result: BackendCommitResult): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            applyTransitionLocked(current, record,
                ReviewCommitTransition.BackendCommitted(result, ReviewCommitResolution.BACKEND_CONFIRMED))
        }

    /** Finish a previously persisted response (e.g. after a COMMITTED write failed). No backend call. */
    suspend fun finalizeRecordedResponse(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        applyTransitionLocked(current, record, ReviewCommitTransition.FinalizeRecordedResponse)
    }

    /**
     * [ReviewCommitStatus.PREPARED] → [ReviewCommitStatus.RETRY_ALLOWED], only because the backend
     * certified that no scheduler mutation was dispatched for this attempt.
     */
    suspend fun markNotCommitted(
        commitId: ReviewCommitId,
        category: String = "mutation_not_entered",
        resolution: String = ReviewCommitResolution.REFUSED_BEFORE_DISPATCH
    ): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.status != ReviewCommitStatus.PREPARED) return@withLock null
        applyTransitionLocked(current, record,
            ReviewCommitTransition.BackendConfirmedNoMutation(category = category, resolution = resolution))
    }

    /** A backend violated its boundary callback contract, or reported unknown before entry. */
    suspend fun markBoundaryViolation(
        commitId: ReviewCommitId,
        category: String = "backend_boundary_violation"
    ): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.status != ReviewCommitStatus.PREPARED) return@withLock null
        applyTransitionLocked(current, record, ReviewCommitTransition.BackendOutcomeUnknown(
            category = category, resolution = ReviewCommitResolution.INTERRUPTED_AFTER_DISPATCH))
    }

    /** Known interruption after entering a backend method; never mark PREPARED as safe. */
    suspend fun markAmbiguous(commitId: ReviewCommitId, category: String): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.status != ReviewCommitStatus.SUBMITTING) return@withLock null
        applyTransitionLocked(current, record, ReviewCommitTransition.BackendOutcomeUnknown(
            category = category, resolution = ReviewCommitResolution.INTERRUPTED_AFTER_DISPATCH))
    }

    /** Read-only preparation failed; no mutation was dispatched in this attempt. */
    suspend fun markRefused(commitId: ReviewCommitId, category: String): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            applyTransitionLocked(current, record, ReviewCommitTransition.BackendConfirmedNoMutation(
                category = category, resolution = ReviewCommitResolution.REFUSED_BEFORE_DISPATCH))
        }

    /**
     * Only the backend adapter may supply authoritative reconciliation; callers gate on its semantics.
     *
     * GATE 11D §23 — the exact reconciliation mapping applies to the two unfinished statuses whose
     * outcome an authoritative query may resolve: SUBMITTING (the mutation boundary was crossed)
     * and AMBIGUOUS. Evidence moves SUBMITTING/AMBIGUOUS → COMMITTED / RETRY_ALLOWED / AMBIGUOUS
     * through the one closed recovery table ([recoveryTransition]); any other current status is
     * not a reconciliation input and is returned unchanged.
     */
    suspend fun reconcile(commitId: ReviewCommitId, result: ReconcileCommitResult): ReviewCommitRecord? =
        mutex.withLock {
            val current = loadedLocked() ?: return@withLock null
            val record = current[commitId.stableKey] ?: return@withLock null
            if (record.status != ReviewCommitStatus.AMBIGUOUS &&
                record.status != ReviewCommitStatus.SUBMITTING
            ) return@withLock record
            val command = when (recoveryPolicy.classify(record, result)) {
                ReviewCommitRecoveryAction.ResumeCommitted -> ReviewCommitTransition.ReconciliationConfirmedCommitted(
                    // A backend-issued receipt travels through when the backend provided one; a
                    // null receipt is never fabricated for backends without one (GATE 11D §9).
                    (result as? ReconcileCommitResult.Applied)?.receipt)
                ReviewCommitRecoveryAction.OfferRetry -> ReviewCommitTransition.ReconciliationConfirmedNotCommitted
                ReviewCommitRecoveryAction.RemainBlocked ->
                    ReviewCommitTransition.ReconciliationInconclusive("reconciliation_inconclusive")
                ReviewCommitRecoveryAction.Reconcile,
                is ReviewCommitRecoveryAction.IntegrityFailure -> return@withLock null
            }
            applyTransitionLocked(current, record, command)
        }

    /** Acknowledging never changes the truth, allows a retry or makes this record prunable. */
    suspend fun acknowledge(commitId: ReviewCommitId): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        if (record.status != ReviewCommitStatus.AMBIGUOUS && record.status != ReviewCommitStatus.RETRY_ALLOWED) {
            return@withLock record
        }
        applyTransitionLocked(current, record, ReviewCommitTransition.Acknowledge)
    }

    /** Leaving the UI is metadata only; it never infers that a mutation did not happen. */
    suspend fun noteAbandoned(commitId: ReviewCommitId, abandonedAtEpochMs: Long): ReviewCommitRecord? = mutex.withLock {
        val current = loadedLocked() ?: return@withLock null
        val record = current[commitId.stableKey] ?: return@withLock null
        applyTransitionLocked(current, record, ReviewCommitTransition.NoteAbandoned(abandonedAtEpochMs))
    }

    /** Optimistic transition through the single pure engine, then durable versioned storage. */
    suspend fun transition(
        commitId: ReviewCommitId,
        expectedStatus: ReviewCommitStatus,
        expectedVersion: Long,
        transition: ReviewCommitTransition
    ): TransitionResult = mutex.withLock {
        val current = loadedLocked() ?: return@withLock TransitionResult.Unavailable(reason())
        val record = current[commitId.stableKey] ?: return@withLock TransitionResult.Missing
        if (record.status != expectedStatus) return@withLock TransitionResult.StatusMismatch(record)
        if (record.version != expectedVersion) return@withLock TransitionResult.VersionMismatch(record)
        val applied = ReviewCommitTransitions.transition(record, transition, clock())
        if (applied is ReviewCommitTransitionResult.Rejected) {
            return@withLock TransitionResult.Rejected(record, applied.reason)
        }
        val proposed = (applied as ReviewCommitTransitionResult.Applied).record
        when (val saved = persistRecordLocked(current, proposed)) {
            is PersistRecordResult.Saved -> TransitionResult.Applied(saved.record)
            is PersistRecordResult.Rejected -> TransitionResult.Rejected(record, saved.reason)
            is PersistRecordResult.StoreFailed -> TransitionResult.StoreFailed(saved.reason)
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
            record.status == ReviewCommitStatus.COMMITTED &&
                record.sessionId !in activeSessionIds &&
                (record.resolvedAtEpochMs ?: record.updatedAtEpochMs) < olderThanEpochMs
        }
        if (victims.isEmpty() || tombstones.size + victims.size > MAX_TOMBSTONES) return@withLock 0
        val now = clock()
        val added = victims.map { CommitIdentityTombstone(
            it.commitId.stableKey, now, backendId = it.backendId, turnId = it.turnId
        ) }
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
        // A throwing store fails closed; a cancelled caller does not disable the ledger.
        val read = orOnStoreFailure<ReviewCommitStoreRead?>(null) { store.read() }
            ?: return disable("store_read_failed")
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
        val recoveredTransitions = mutableListOf<Pair<ReviewCommitRecord, ReviewCommitRecord>>()
        // Canonical SUBMITTING means "the mutation boundary was entered", so an interrupted
        // SUBMITTING row is always unknown — except when the backend answer itself is already
        // durable, which is proof and is finalized without ever calling the backend again.
        val interrupted = initial.count { it.status == ReviewCommitStatus.SUBMITTING }
        val now = clock()
        for (record in initial) {
            // GATE 11B §28: a PREPARED row still carrying the durable claim marker was
            // interrupted before the mutation boundary, so nothing can have been applied. The
            // claim is released so the attempt can be claimed again (OfferRetry), never replayed.
            val unclaimed = if (record.status == ReviewCommitStatus.PREPARED &&
                record.claimedAtEpochMs != null) record.copy(claimedAtEpochMs = null) else record
            val recovered = if (unclaimed.status != ReviewCommitStatus.SUBMITTING) unclaimed else {
                val command = if (record.phase == ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED) {
                    ReviewCommitTransition.FinalizeRecordedResponse
                } else when (val action = recoveryPolicy.classify(record)) {
                    // GATE 11D §28 — a recovered SUBMITTING that cannot be immediately
                    // authoritatively classified is normalized into explicit uncertainty through
                    // the one closed recovery table: SUBMITTING + ReconciliationUnresolved →
                    // AMBIGUOUS. Startup code issues recovery events, never pipeline commands.
                    ReviewCommitRecoveryAction.Reconcile -> ReviewCommitTransition.ReconciliationInconclusive(
                        category = "interrupted_after_mutation_entry",
                        resolution = ReviewCommitResolution.PROCESS_RESTART_WHILE_SUBMITTING)
                    // A SUBMITTING row can only be Reconcile-eligible; anything else is unusable.
                    ReviewCommitRecoveryAction.ResumeCommitted,
                    ReviewCommitRecoveryAction.RemainBlocked,
                    ReviewCommitRecoveryAction.OfferRetry,
                    is ReviewCommitRecoveryAction.IntegrityFailure ->
                        return disable("invalid_attempt_phase:${action::class.simpleName}")
                }
                val proposed = when (val result = ReviewCommitTransitions.transition(record, command, now)) {
                    is ReviewCommitTransitionResult.Applied -> result.record
                    is ReviewCommitTransitionResult.Rejected -> return disable("invalid_attempt_transition")
                }
                val stamped = proposed.copy(version = record.version + 1)
                if (!ReviewCommitTransitions.validWrite(record, stamped)) return disable("invalid_attempt_transition")
                stamped
            }
            if (recovered != record) recoveredTransitions += record to recovered
            map[recovered.commitId.stableKey] = recovered
        }
        val schemaNeedsMigration = raw != null && Regex("\"schemaVersion\"\\s*:\\s*[123]").containsMatchIn(raw)
        val needsRecoveryWrite = map.values.any { it.claimedAtEpochMs == null && it.status == ReviewCommitStatus.PREPARED &&
            initial.firstOrNull { r -> r.commitId == it.commitId }?.claimedAtEpochMs != null }
        if (interrupted > 0 || schemaNeedsMigration || needsRecoveryWrite) {
            if (writeLocked(map) != null) return disable("recovery_write_failed")
        }
        recoveredTransitions.forEach { (before, after) -> countTransition(before, after) }
        recoveryTotal = interrupted.toLong()
        report = ReviewCommitRecoveryReport(
            interruptedSubmissions = interrupted,
            restorable = map.values.count { it.status == ReviewCommitStatus.PREPARED },
            unresolvedAmbiguous = map.values.count { it.status == ReviewCommitStatus.AMBIGUOUS },
            committed = map.values.count { it.status == ReviewCommitStatus.COMMITTED },
            failed = map.values.count { it.status == ReviewCommitStatus.RETRY_ALLOWED })
        records = map
        publishDiagnostics(map)
        return map
    }

    /** Applies exactly one domain transition before any durable write. */
    private suspend fun applyTransitionLocked(
        current: LinkedHashMap<String, ReviewCommitRecord>,
        record: ReviewCommitRecord,
        command: ReviewCommitTransition
    ): ReviewCommitRecord? = when (val result = persistTransitionLocked(current, record, command)) {
        is PersistRecordResult.Saved -> result.record
        is PersistRecordResult.Rejected, is PersistRecordResult.StoreFailed -> null
    }

    private suspend fun persistTransitionLocked(
        current: LinkedHashMap<String, ReviewCommitRecord>,
        record: ReviewCommitRecord,
        command: ReviewCommitTransition
    ): PersistRecordResult {
        val proposed = when (val result = ReviewCommitTransitions.transition(record, command, clock())) {
            is ReviewCommitTransitionResult.Applied -> result.record
            is ReviewCommitTransitionResult.Rejected -> return PersistRecordResult.Rejected(result.reason)
        }
        return persistRecordLocked(current, proposed)
    }

    private suspend fun persistRecordLocked(
        current: LinkedHashMap<String, ReviewCommitRecord>, record: ReviewCommitRecord
    ): PersistRecordResult {
        val previous = current[record.commitId.stableKey]
        if (previous == null) return PersistRecordResult.Rejected(
            ReviewCommitTransitionRejection.INVALID_RESULT_RECORD)
        val stamped = record.copy(version = previous.version + 1)
        if (!ReviewCommitTransitions.validWrite(previous, stamped)) {
            return PersistRecordResult.Rejected(ReviewCommitTransitionRejection.ILLEGAL_STATUS_TRANSITION)
        }
        countTransition(previous, stamped)
        val failure = persistLocked(LinkedHashMap(current).apply { put(stamped.commitId.stableKey, stamped) })
        // Keep the in-lock view current: several ledger operations apply more than one transition
        // while holding the mutex (e.g. RETRY_ALLOWED → PREPARED → claimed), and every later
        // `allowed(before, after)` check and version stamp must see the write that just happened.
        if (failure == null) current[stamped.commitId.stableKey] = stamped
        return if (failure == null) PersistRecordResult.Saved(stamped) else {
            undoTransitionCount(previous, stamped)
            publishDiagnostics(requireNotNull(records), writeFailed = true)
            PersistRecordResult.StoreFailed(failure)
        }
    }

    private fun countTransition(before: ReviewCommitRecord, after: ReviewCommitRecord) {
        if (before.status != ReviewCommitStatus.SUBMITTING && after.status == ReviewCommitStatus.SUBMITTING) attemptTotal += 1
        if (before.status != ReviewCommitStatus.COMMITTED && after.status == ReviewCommitStatus.COMMITTED) successTotal += 1
        if (before.status != ReviewCommitStatus.RETRY_ALLOWED &&
            after.status == ReviewCommitStatus.RETRY_ALLOWED) safeFailureTotal += 1
        if (before.status != ReviewCommitStatus.AMBIGUOUS && after.status == ReviewCommitStatus.AMBIGUOUS) ambiguousTotal += 1
        if (before.status == ReviewCommitStatus.AMBIGUOUS && after.status == ReviewCommitStatus.AMBIGUOUS &&
            after.resolution == ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE) {
            reconciliationUnresolvedTotal += 1
        }
    }

    private fun undoTransitionCount(before: ReviewCommitRecord, after: ReviewCommitRecord) {
        if (before.status != ReviewCommitStatus.SUBMITTING && after.status == ReviewCommitStatus.SUBMITTING) attemptTotal -= 1
        if (before.status != ReviewCommitStatus.COMMITTED && after.status == ReviewCommitStatus.COMMITTED) successTotal -= 1
        if (before.status != ReviewCommitStatus.RETRY_ALLOWED &&
            after.status == ReviewCommitStatus.RETRY_ALLOWED) safeFailureTotal -= 1
        if (before.status != ReviewCommitStatus.AMBIGUOUS && after.status == ReviewCommitStatus.AMBIGUOUS) ambiguousTotal -= 1
        if (before.status == ReviewCommitStatus.AMBIGUOUS && after.status == ReviewCommitStatus.AMBIGUOUS &&
            after.resolution == ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE) {
            reconciliationUnresolvedTotal -= 1
        }
    }

    private suspend fun writeLocked(next: LinkedHashMap<String, ReviewCommitRecord>): String? {
        // DataStore's suspending edit completes before returning. Never launch this in another job.
        // Tombstones travel with every snapshot so a later commit write cannot forget pruned ids.
        val written = orOnStoreFailure(false) {
            withContext(NonCancellable) {
                store.write(ReviewCommitLedgerCodec.encode(next.values, tombstones.values))
            }
        }
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
