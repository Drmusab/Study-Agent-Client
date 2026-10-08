package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GATE 17 — ledger outcome. Deliberately NOT `AnkiResult`/`AnkiError`: a ledger failure is about
 * local durability, and must never be mistaken for a backend answer.
 */
sealed interface NoteLedgerResult<out T> {
    data class Ok<out T>(val value: T) : NoteLedgerResult<T>

    /** Refused before any write: unknown id, stale expectation, illegal transition, duplicate. */
    data class Rejected(val reason: String) : NoteLedgerResult<Nothing>

    /** Durable state could not be read or written. The caller must assume nothing changed. */
    data class Unavailable(val detail: String) : NoteLedgerResult<Nothing>
}

/**
 * GATE 17 — the note-mutation ledger. Separate from the reviewer ledgers on purpose (its own
 * store, its own ids, its own statuses).
 *
 * Every status change goes through [apply] with an *expected* status (compare-and-set), so a stale
 * caller cannot overwrite a newer decision.
 */
interface NoteMutationLedger {
    suspend fun create(record: NoteMutationRecord): NoteLedgerResult<NoteMutationRecord>
    suspend fun get(mutationId: NoteMutationId): NoteLedgerResult<NoteMutationRecord?>

    /** The non-terminal mutation for this note, if any. Enforces one active mutation per note. */
    suspend fun findActiveForNote(backendId: AnkiBackendId, noteId: String): NoteLedgerResult<NoteMutationRecord?>

    suspend fun unresolved(): NoteLedgerResult<List<NoteMutationRecord>>

    suspend fun apply(
        mutationId: NoteMutationId,
        expected: NoteMutationStatus,
        event: NoteMutationEvent
    ): NoteLedgerResult<NoteMutationRecord>
}

/** Storage primitive. Production: a DataStore string preference, separate from every other store. */
interface NoteMutationStore {
    suspend fun read(): NoteMutationStoreRead

    /** Returns true only after the new snapshot is durably committed. */
    suspend fun write(encoded: String): Boolean
}

sealed interface NoteMutationStoreRead {
    /** [encoded] is null when nothing has ever been stored. */
    data class Snapshot(val encoded: String?) : NoteMutationStoreRead
    data class Unreadable(val detail: String) : NoteMutationStoreRead
}

/**
 * JSON snapshot codec. Strict: an unknown version, an unknown key or a malformed record makes the
 * whole snapshot unreadable, and the ledger then fails closed instead of guessing.
 */
object NoteMutationCodec {
    const val VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Serializable
    internal data class Snapshot(val version: Int, val records: List<NoteMutationRecord>)

    fun encode(records: Collection<NoteMutationRecord>): String =
        json.encodeToString(Snapshot.serializer(), Snapshot(VERSION, records.toList()))

    /** Returns null for any unreadable snapshot. */
    fun decode(text: String): List<NoteMutationRecord>? = try {
        val snapshot = json.decodeFromString(Snapshot.serializer(), text)
        if (snapshot.version == VERSION) snapshot.records else null
    } catch (malformed: kotlinx.serialization.SerializationException) {
        null
    } catch (malformed: IllegalArgumentException) {
        null
    }
}

/**
 * Default ledger: an in-memory map, loaded from and written through a [NoteMutationStore].
 *
 * Restart rule (applied on every load, persisted before the ledger is usable):
 * - SUBMITTING -> AMBIGUOUS: the first write may have happened, so the outcome is unknown.
 * - PREPARED / RETRY_ALLOWED -> CONFLICT (ABANDONED_ON_RESTART): nothing was submitted, but the
 *   payload that a retry needs lived only in the old process, so the edit is closed and the user
 *   re-enters it under a new id.
 *
 * Retention: every non-terminal record is kept. Terminal records are capped at [TERMINAL_RETENTION]
 * (newest kept), so the snapshot cannot grow without bound.
 */
class DefaultNoteMutationLedger(
    private val store: NoteMutationStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : NoteMutationLedger {

    private val mutex = Mutex()

    /** Null = not loaded (or last write failed); the next operation reloads from the store. */
    private var cache: LinkedHashMap<NoteMutationId, NoteMutationRecord>? = null

    /**
     * Restart normalization runs exactly once per process, at the first successful load. A reload
     * after a failed write must NOT re-apply it: an in-flight PREPARED record would otherwise be
     * abandoned out from under the coordinator that still holds it.
     */
    private var startupNormalized: Boolean = false

    override suspend fun create(record: NoteMutationRecord): NoteLedgerResult<NoteMutationRecord> =
        withContext(NonCancellable) {
            mutex.withLock {
                val map = loadedOrFail { return@withLock it }
                if (record.status != NoteMutationStatus.PREPARED) {
                    return@withLock NoteLedgerResult.Rejected("create_requires_prepared")
                }
                if (map.containsKey(record.mutationId)) {
                    return@withLock NoteLedgerResult.Rejected("duplicate_mutation_id")
                }
                if (map.values.any { it.status.isActive && sameNote(it, record) }) {
                    return@withLock NoteLedgerResult.Rejected("active_mutation_exists")
                }
                map[record.mutationId] = record
                commit(map) { return@withLock it }
                NoteLedgerResult.Ok(record)
            }
        }

    override suspend fun get(mutationId: NoteMutationId): NoteLedgerResult<NoteMutationRecord?> =
        mutex.withLock {
            val map = loadedOrFail { return@withLock it }
            NoteLedgerResult.Ok(map[mutationId])
        }

    override suspend fun findActiveForNote(
        backendId: AnkiBackendId,
        noteId: String
    ): NoteLedgerResult<NoteMutationRecord?> = mutex.withLock {
        val map = loadedOrFail { return@withLock it }
        NoteLedgerResult.Ok(
            map.values.firstOrNull { it.status.isActive && it.backendId == backendId && it.noteRef.noteId == noteId }
        )
    }

    override suspend fun unresolved(): NoteLedgerResult<List<NoteMutationRecord>> = mutex.withLock {
        val map = loadedOrFail { return@withLock it }
        NoteLedgerResult.Ok(map.values.filter { it.status.isActive })
    }

    override suspend fun apply(
        mutationId: NoteMutationId,
        expected: NoteMutationStatus,
        event: NoteMutationEvent
    ): NoteLedgerResult<NoteMutationRecord> = withContext(NonCancellable) {
        mutex.withLock {
            val map = loadedOrFail { return@withLock it }
            val current = map[mutationId] ?: return@withLock NoteLedgerResult.Rejected("unknown_mutation")
            if (current.status != expected) return@withLock NoteLedgerResult.Rejected("stale_expectation")
            val next = when (val transition = NoteMutationTransitions.apply(current, event, nowEpochMs())) {
                is NoteMutationTransitionResult.Accepted -> transition.record
                is NoteMutationTransitionResult.Rejected -> return@withLock NoteLedgerResult.Rejected("transition_rejected")
            }
            map[mutationId] = next
            commit(map) { return@withLock it }
            NoteLedgerResult.Ok(next)
        }
    }

    /**
     * Returns the loaded map, loading and normalizing it on first use. [fail] is invoked (through a
     * non-local return at the call site) with the failure result, so a broken store never yields a
     * partial view.
     */
    private suspend inline fun loadedOrFail(
        fail: (NoteLedgerResult<Nothing>) -> Nothing
    ): LinkedHashMap<NoteMutationId, NoteMutationRecord> {
        cache?.let { return it }
        return when (val read = store.read()) {
            is NoteMutationStoreRead.Unreadable -> fail(NoteLedgerResult.Unavailable("store_unreadable:${read.detail}"))
            is NoteMutationStoreRead.Snapshot -> {
                val encoded = read.encoded
                val decoded = if (encoded == null) emptyList() else {
                    NoteMutationCodec.decode(encoded)
                        ?: fail(NoteLedgerResult.Unavailable("snapshot_corrupt"))
                }
                val map = LinkedHashMap<NoteMutationId, NoteMutationRecord>()
                var normalizedAny = false
                decoded.forEach { record ->
                    val normalized = if (startupNormalized) record else normalizeAfterRestart(record)
                    if (normalized !== record) normalizedAny = true
                    map[normalized.mutationId] = normalized
                }
                if (normalizedAny) {
                    if (!store.write(NoteMutationCodec.encode(pruned(map).values))) {
                        fail(NoteLedgerResult.Unavailable("restart_normalization_not_persisted"))
                    }
                }
                cache = map
                startupNormalized = true
                map
            }
        }
    }

    /** Persists [map]. On failure the cache is dropped so the next operation re-reads durable truth. */
    private suspend inline fun commit(
        map: LinkedHashMap<NoteMutationId, NoteMutationRecord>,
        fail: (NoteLedgerResult<Nothing>) -> Nothing
    ) {
        val kept = pruned(map)
        val ok = store.write(NoteMutationCodec.encode(kept.values))
        if (!ok) {
            cache = null
            fail(NoteLedgerResult.Unavailable("write_not_committed"))
        }
        cache = kept
    }

    private fun normalizeAfterRestart(record: NoteMutationRecord): NoteMutationRecord = when (record.status) {
        NoteMutationStatus.SUBMITTING -> NoteMutationTransitions.apply(
            record, NoteMutationEvent.RecoveryNormalizedUnknown, nowEpochMs()
        ).let { (it as? NoteMutationTransitionResult.Accepted)?.record ?: record }
        NoteMutationStatus.PREPARED, NoteMutationStatus.RETRY_ALLOWED -> NoteMutationTransitions.apply(
            record, NoteMutationEvent.PreBoundaryConflict(NoteMutationReason.ABANDONED_ON_RESTART), nowEpochMs()
        ).let { (it as? NoteMutationTransitionResult.Accepted)?.record ?: record }
        NoteMutationStatus.APPLIED, NoteMutationStatus.AMBIGUOUS, NoteMutationStatus.CONFLICT -> record
    }

    private fun pruned(
        map: LinkedHashMap<NoteMutationId, NoteMutationRecord>
    ): LinkedHashMap<NoteMutationId, NoteMutationRecord> {
        val terminal = map.values.filter { it.status.isTerminal }.sortedByDescending { it.updatedAtEpochMs }
        val keepTerminal = terminal.take(TERMINAL_RETENTION).map { it.mutationId }.toSet()
        val result = LinkedHashMap<NoteMutationId, NoteMutationRecord>()
        map.values.forEach { record ->
            if (!record.status.isTerminal || record.mutationId in keepTerminal) result[record.mutationId] = record
        }
        return result
    }

    private fun sameNote(a: NoteMutationRecord, b: NoteMutationRecord): Boolean =
        a.backendId == b.backendId && a.noteRef.noteId == b.noteRef.noteId

    companion object {
        const val TERMINAL_RETENTION: Int = 100
    }
}
