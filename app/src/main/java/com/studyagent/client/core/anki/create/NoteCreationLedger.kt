package com.studyagent.client.core.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GATE 18 — ledger outcome. Deliberately NOT `AnkiResult`/`AnkiError`: a ledger failure is about
 * local durability, and must never be mistaken for a backend answer.
 */
sealed interface CreationLedgerResult<out T> {
    data class Ok<out T>(val value: T) : CreationLedgerResult<T>

    /** Refused before any write: unknown id, stale expectation, illegal transition, duplicate. */
    data class Rejected(val reason: String) : CreationLedgerResult<Nothing>

    /** Durable state could not be read or written. The caller must assume nothing changed. */
    data class Unavailable(val detail: String) : CreationLedgerResult<Nothing>
}

/**
 * GATE 18 — the note-creation ledger. Separate from the edit, rating and reviewer-action ledgers
 * on purpose: its own store file, its own ids, its own statuses (INV-18-17).
 *
 * Every status change goes through [apply] with an *expected* status (compare-and-set), so a stale
 * caller cannot overwrite a newer decision. There is no unrestricted status setter.
 */
interface NoteCreationLedger {
    suspend fun create(record: NoteCreationRecord): CreationLedgerResult<NoteCreationRecord>
    suspend fun get(creationId: NoteCreationId): CreationLedgerResult<NoteCreationRecord?>

    /** Every record whose outcome is still open (CREATING_NOTE or AMBIGUOUS). */
    suspend fun unresolved(): CreationLedgerResult<List<NoteCreationRecord>>

    /** Every non-terminal record for one backend (recovery surface). */
    suspend fun activeFor(backendId: AnkiBackendId): CreationLedgerResult<List<NoteCreationRecord>>

    suspend fun apply(
        creationId: NoteCreationId,
        expected: NoteCreationStatus,
        event: NoteCreationEvent
    ): CreationLedgerResult<NoteCreationRecord>
}

/** Storage primitive. Production: a DataStore string preference, separate from every other store. */
interface NoteCreationStore {
    suspend fun read(): NoteCreationStoreRead

    /** Returns true only after the new snapshot is durably committed. */
    suspend fun write(encoded: String): Boolean
}

sealed interface NoteCreationStoreRead {
    /** [encoded] is null when nothing has ever been stored. */
    data class Snapshot(val encoded: String?) : NoteCreationStoreRead
    data class Unreadable(val detail: String) : NoteCreationStoreRead
}

/**
 * JSON snapshot codec. Strict: an unknown version, an unknown key or a malformed record makes the
 * whole snapshot unreadable, and the ledger then fails closed instead of guessing. Metadata only:
 * the record type itself carries no field values, tag text or media content.
 */
object NoteCreationCodec {
    const val VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Serializable
    internal data class Snapshot(val version: Int, val records: List<NoteCreationRecord>)

    fun encode(records: Collection<NoteCreationRecord>): String =
        json.encodeToString(Snapshot.serializer(), Snapshot(VERSION, records.toList()))

    /** Returns null for any unreadable snapshot. */
    fun decode(text: String): List<NoteCreationRecord>? = try {
        val snapshot = json.decodeFromString(Snapshot.serializer(), text)
        if (snapshot.version == VERSION) snapshot.records else null
    } catch (malformed: kotlinx.serialization.SerializationException) {
        null
    } catch (malformed: IllegalArgumentException) {
        null
    }
}

/**
 * Default ledger: an in-memory map, loaded from and written through a [NoteCreationStore].
 *
 * Restart rule (applied on every load, persisted before the ledger is usable — CONTRACT-18-41):
 * - CREATING_NOTE -> AMBIGUOUS (RECOVERED_AFTER_RESTART): the note boundary was crossed and the
 *   answer was lost; the note may exist with an unknown id. Never retried.
 * - PREPARED / STORING_MEDIA / RETRY_ALLOWED -> ABANDONED (ABANDONED_ON_RESTART): no note effect is
 *   possible (the boundary had not been crossed) and the payload that a retry needs lived only in
 *   the old process. A STORING_MEDIA record may leave orphan media — disclosed, never cleaned up
 *   automatically (§20 of the contract doc).
 *
 * Retention: every non-terminal record is kept. Terminal records are capped at [TERMINAL_RETENTION]
 * (newest kept), so the snapshot cannot grow without bound.
 */
class DefaultNoteCreationLedger(
    private val store: NoteCreationStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : NoteCreationLedger {

    private val mutex = Mutex()

    /** Null = not loaded (or last write failed); the next operation reloads from the store. */
    private var cache: LinkedHashMap<NoteCreationId, NoteCreationRecord>? = null

    /**
     * Restart normalization runs exactly once per process, at the first successful load. A reload
     * after a failed write must NOT re-apply it: an in-flight record would otherwise be abandoned
     * out from under the coordinator that still holds it.
     */
    private var startupNormalized: Boolean = false

    override suspend fun create(record: NoteCreationRecord): CreationLedgerResult<NoteCreationRecord> =
        withContext(NonCancellable) {
            mutex.withLock {
                val map = loadedOrFail { return@withLock it }
                if (record.status != NoteCreationStatus.PREPARED) {
                    return@withLock CreationLedgerResult.Rejected("create_requires_prepared")
                }
                if (map.containsKey(record.creationId)) {
                    return@withLock CreationLedgerResult.Rejected("duplicate_creation_id")
                }
                map[record.creationId] = record
                commit(map) { return@withLock it }
                CreationLedgerResult.Ok(record)
            }
        }

    override suspend fun get(creationId: NoteCreationId): CreationLedgerResult<NoteCreationRecord?> =
        mutex.withLock {
            val map = loadedOrFail { return@withLock it }
            CreationLedgerResult.Ok(map[creationId])
        }

    override suspend fun unresolved(): CreationLedgerResult<List<NoteCreationRecord>> = mutex.withLock {
        val map = loadedOrFail { return@withLock it }
        CreationLedgerResult.Ok(map.values.filter { it.status.isUnresolved })
    }

    override suspend fun activeFor(
        backendId: AnkiBackendId
    ): CreationLedgerResult<List<NoteCreationRecord>> = mutex.withLock {
        val map = loadedOrFail { return@withLock it }
        CreationLedgerResult.Ok(
            map.values.filter { !it.status.isTerminal && it.backendId == backendId }
        )
    }

    override suspend fun apply(
        creationId: NoteCreationId,
        expected: NoteCreationStatus,
        event: NoteCreationEvent
    ): CreationLedgerResult<NoteCreationRecord> = withContext(NonCancellable) {
        mutex.withLock {
            val map = loadedOrFail { return@withLock it }
            val current = map[creationId] ?: return@withLock CreationLedgerResult.Rejected("unknown_creation")
            if (current.status != expected) return@withLock CreationLedgerResult.Rejected("stale_expectation")
            val next = when (val transition = NoteCreationTransitions.apply(current, event, nowEpochMs())) {
                is NoteCreationTransitionResult.Accepted -> transition.record
                is NoteCreationTransitionResult.Rejected ->
                    return@withLock CreationLedgerResult.Rejected("transition_rejected")
            }
            map[creationId] = next
            commit(map) { return@withLock it }
            CreationLedgerResult.Ok(next)
        }
    }

    /**
     * Returns the loaded map, loading and normalizing it on first use. [fail] is invoked (through a
     * non-local return at the call site) with the failure result, so a broken store never yields a
     * partial view.
     */
    private suspend inline fun loadedOrFail(
        fail: (CreationLedgerResult<Nothing>) -> Nothing
    ): LinkedHashMap<NoteCreationId, NoteCreationRecord> {
        cache?.let { return it }
        return when (val read = store.read()) {
            is NoteCreationStoreRead.Unreadable -> fail(CreationLedgerResult.Unavailable("store_unreadable:${read.detail}"))
            is NoteCreationStoreRead.Snapshot -> {
                val encoded = read.encoded
                val decoded = if (encoded == null) emptyList() else {
                    NoteCreationCodec.decode(encoded)
                        ?: fail(CreationLedgerResult.Unavailable("snapshot_corrupt"))
                }
                val map = LinkedHashMap<NoteCreationId, NoteCreationRecord>()
                var normalizedAny = false
                decoded.forEach { record ->
                    val normalized = if (startupNormalized) record else normalizeAfterRestart(record)
                    if (normalized !== record) normalizedAny = true
                    map[normalized.creationId] = normalized
                }
                if (normalizedAny) {
                    if (!store.write(NoteCreationCodec.encode(pruned(map).values))) {
                        fail(CreationLedgerResult.Unavailable("restart_normalization_not_persisted"))
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
        map: LinkedHashMap<NoteCreationId, NoteCreationRecord>,
        fail: (CreationLedgerResult<Nothing>) -> Nothing
    ) {
        val kept = pruned(map)
        val ok = store.write(NoteCreationCodec.encode(kept.values))
        if (!ok) {
            cache = null
            fail(CreationLedgerResult.Unavailable("write_not_committed"))
        }
        cache = kept
    }

    private fun normalizeAfterRestart(record: NoteCreationRecord): NoteCreationRecord = when (record.status) {
        NoteCreationStatus.CREATING_NOTE -> NoteCreationTransitions.apply(
            record, NoteCreationEvent.RecoveryNormalizedUnknown, nowEpochMs()
        ).let { (it as? NoteCreationTransitionResult.Accepted)?.record ?: record }
        NoteCreationStatus.PREPARED, NoteCreationStatus.STORING_MEDIA, NoteCreationStatus.RETRY_ALLOWED ->
            NoteCreationTransitions.apply(
                record, NoteCreationEvent.RestartAbandoned, nowEpochMs()
            ).let { (it as? NoteCreationTransitionResult.Accepted)?.record ?: record }
        NoteCreationStatus.CREATED, NoteCreationStatus.AMBIGUOUS, NoteCreationStatus.ABANDONED -> record
    }

    private fun pruned(
        map: LinkedHashMap<NoteCreationId, NoteCreationRecord>
    ): LinkedHashMap<NoteCreationId, NoteCreationRecord> {
        val terminal = map.values.filter { it.status.isTerminal }.sortedByDescending { it.updatedAtEpochMs }
        val keepTerminal = terminal.take(TERMINAL_RETENTION).map { it.creationId }.toSet()
        val result = LinkedHashMap<NoteCreationId, NoteCreationRecord>()
        map.values.forEach { record ->
            if (!record.status.isTerminal || record.creationId in keepTerminal) result[record.creationId] = record
        }
        return result
    }

    companion object {
        const val TERMINAL_RETENTION: Int = 100
    }
}
