package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * GATE 18 — the only AnkiDroid component that creates notes or stores creation media, and the
 * creation surface's read side (model listing, post-create hydration).
 *
 * Scope, deliberately narrow:
 *
 * - **one** provider `insert` per call, on the pinned `notes` or `media` endpoint, carrying
 *   exactly the columns the pinned contract defines — see [AnkiDroidCreationMapper];
 * - the physical call is serialized by the **shared** [AnkiDroidWritePermit], so it can never
 *   overlap a rating answer, a reviewer action or a note edit;
 * - it is never retried here or below. It reports what happened at the creation boundary and never
 *   decides success; the classification lives in [AnkiDroidCreationMapper.classifyNoteInsert] /
 *   [AnkiDroidCreationMapper.classifyMediaInsert].
 *
 * The read side (model listing, note/cards reads) is read-only and needs no permit.
 */
interface AnkiDroidCreationGateway {

    /** Issues exactly one note-create insert, or refuses before any IPC. */
    suspend fun submitNoteCreate(authority: String, values: List<ProviderValue>): AnkiDroidCreationDispatch

    /** Issues exactly one media-store insert, or refuses before any IPC. */
    suspend fun submitMediaStore(authority: String, values: List<ProviderValue>): AnkiDroidCreationDispatch

    /** Read-only: every note type's creation schema (`models` listing). */
    suspend fun listModels(authority: String, backendId: com.studyagent.client.core.anki.AnkiBackendId): CreationModelsRead

    /** Read-only: one note row by id (post-create hydration, GATE 16 pinned columns). */
    suspend fun readNoteRow(authority: String, noteId: String): CreatedNoteRowRead

    /** Read-only: the cards Anki generated for one note (`notes/<id>/cards`). */
    suspend fun readNoteCards(authority: String, noteId: String): CreatedNoteCardsRead

    /** Physical creation `insert` calls issued since construction (diagnostics/test evidence). */
    val physicalInsertCalls: Long

    /** True while a provider insert issued through the shared permit has not returned. */
    val insertInFlight: Boolean
}

/** What one creation `insert` produced, before any classification. */
sealed interface AnkiDroidCreationDispatch {
    /** Refused before any IPC: provably not dispatched, provably no effect. */
    data class NotDispatched(val error: AnkiError) : AnkiDroidCreationDispatch

    /**
     * The provider answered. `uri == null` is the platform's ambiguous answer (refusal-by-null OR
     * provider death mid-call) — classification decides what it proves.
     */
    data class Returned(val uri: String?) : AnkiDroidCreationDispatch

    /** The provider threw. Only the exception class is kept, never a message (it can carry content). */
    data class Threw(val exceptionClass: String) : AnkiDroidCreationDispatch

    /** Issued; no classifiable answer (for example a wait timeout while the call may still run). */
    data class Unknown(val detail: String) : AnkiDroidCreationDispatch
}

/** Model listing outcome — never an empty list standing in for a failure. */
sealed interface CreationModelsRead {
    data class Models(val models: List<com.studyagent.client.core.anki.AnkiNoteModel>) : CreationModelsRead
    data class Failed(val failure: AnkiDroidFailure, val malformed: List<String> = emptyList()) : CreationModelsRead
}

/** One note row read (hydration). */
sealed interface CreatedNoteRowRead {
    data class Found(val noteId: String, val modelId: String?, val tags: List<String>, val fields: List<String>) : CreatedNoteRowRead
    data object Missing : CreatedNoteRowRead
    data class Failed(val failure: AnkiDroidFailure) : CreatedNoteRowRead
}

/** One row of the generated-cards enumeration. */
data class AnkiDroidCreatedCardRow(
    val cardId: String,
    val ord: Int?,
    val name: String?,
    val deckId: String?
)

/** The generated-cards read outcome. Empty = the backend says the note has no cards (not an error). */
sealed interface CreatedNoteCardsRead {
    data class Cards(val rows: List<AnkiDroidCreatedCardRow>) : CreatedNoteCardsRead
    data class Failed(val failure: AnkiDroidFailure) : CreatedNoteCardsRead
}

class DefaultAnkiDroidCreationGateway(
    private val providerClient: AnkiDroidProviderClient,
    /** Owns an issued provider write so a caller that stops waiting never cancels a live call. */
    private val scope: CoroutineScope,
    /** Required (no default): a second insert permit would be a second writer. */
    private val writePermit: AnkiDroidWritePermit,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val permitTimeoutMs: Long = PERMIT_TIMEOUT_MS
) : AnkiDroidCreationGateway {

    private val insertCalls = AtomicLong(0L)

    override val physicalInsertCalls: Long get() = insertCalls.get()

    override val insertInFlight: Boolean get() = writePermit.inFlight

    override suspend fun submitNoteCreate(
        authority: String,
        values: List<ProviderValue>
    ): AnkiDroidCreationDispatch = submitInsert(authority, AnkiDroidApiContract.NOTES_INSERT_PATH, values, "note")

    override suspend fun submitMediaStore(
        authority: String,
        values: List<ProviderValue>
    ): AnkiDroidCreationDispatch = submitInsert(authority, AnkiDroidApiContract.MEDIA_PATH, values, "media")

    private suspend fun submitInsert(
        authority: String,
        path: String,
        values: List<ProviderValue>,
        label: String
    ): AnkiDroidCreationDispatch {
        if (values.isEmpty()) {
            return AnkiDroidCreationDispatch.NotDispatched(AnkiError.InvalidRequest("no_values"))
        }
        if (!writePermit.acquire(permitTimeoutMs)) {
            // The wait timed out before anything was issued: provably pre-dispatch.
            return AnkiDroidCreationDispatch.NotDispatched(AnkiError.QueryFailure("provider_write_busy"))
        }
        // The one creation-insert entry point per call; never retried here or below.
        insertCalls.incrementAndGet()
        AppLogger.i(TAG, "ANKI_CREATION_PROVIDER_CALL target=$label columns=${values.size}")
        val result = issue(authority, path, values)
            ?: return AnkiDroidCreationDispatch.Unknown("insert_timeout")
        return when (result) {
            is ProviderInsertResult.Returned -> AnkiDroidCreationDispatch.Returned(result.uri)
            is ProviderInsertResult.NotDispatched ->
                AnkiDroidCreationDispatch.NotDispatched(AnkiError.QueryFailure(result.reason))
            is ProviderInsertResult.Threw -> AnkiDroidCreationDispatch.Threw(result.exceptionClass)
        }
    }

    /**
     * Issues one provider insert in [scope] and waits at most [writeTimeoutMs]. Returns `null` on
     * timeout — the call is still running and releases the permit when it returns. The answer of an
     * abandoned wait is always UNKNOWN downstream: the boundary was entered, the effect is possible.
     */
    private suspend fun issue(
        authority: String,
        path: String,
        values: List<ProviderValue>
    ): ProviderInsertResult? {
        val call = scope.async(start = CoroutineStart.ATOMIC) {
            try {
                providerClient.safeInsert(authority, path, values)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                ProviderInsertResult.Threw(
                    exceptionClass = throwable::class.java.simpleName,
                    failure = AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.PROVIDER_UPDATE)
                )
            } finally {
                writePermit.release()
            }
        }
        return withTimeoutOrNull(writeTimeoutMs) { call.await() }
    }

    // ---------------------------------------------------------------- read side

    override suspend fun listModels(
        authority: String,
        backendId: com.studyagent.client.core.anki.AnkiBackendId
    ): CreationModelsRead =
        when (
            val result = providerClient.safeQuery(
                authority = authority,
                path = AnkiDroidApiContract.MODELS_PATH,
                projection = AnkiDroidApiContract.MODEL_CREATION_PROJECTION,
                selection = null,
                selectionArgs = null,
                sortOrder = null
            ) { row -> AnkiDroidCreationMapper.mapModelRow(backendId, row) }
        ) {
            is ProviderQueryResult.Success -> {
                val models = ArrayList<com.studyagent.client.core.anki.AnkiNoteModel>()
                val malformed = ArrayList<String>()
                result.data.forEach { outcome ->
                    when (outcome) {
                        is AnkiDroidCreationMapper.ModelRowOutcome.Model -> models += outcome.model
                        is AnkiDroidCreationMapper.ModelRowOutcome.Malformed -> malformed += outcome.token
                    }
                }
                // A listing where EVERY row is unusable is a contract failure, not an empty list.
                if (models.isEmpty() && malformed.isNotEmpty()) {
                    CreationModelsRead.Failed(
                        AnkiDroidFailure(
                            category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                            evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                            evidenceToken = "model_rows_all_malformed"
                        ),
                        malformed
                    )
                } else {
                    CreationModelsRead.Models(models)
                }
            }
            is ProviderQueryResult.Empty -> CreationModelsRead.Models(emptyList())
            is ProviderQueryResult.Failure -> CreationModelsRead.Failed(result.failure)
        }

    override suspend fun readNoteRow(authority: String, noteId: String): CreatedNoteRowRead {
        val numeric = noteId.toLongOrNull()?.takeIf { it > 0L }
            ?: return CreatedNoteRowRead.Failed(
                AnkiDroidFailure(
                    category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                    evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                    evidenceToken = "note_id_not_numeric"
                )
            )
        return when (
            val result = providerClient.safeQuery(
                authority = authority,
                path = "${AnkiDroidApiContract.NOTE_ITEM_PATH}/$numeric",
                projection = AnkiDroidApiContract.NOTE_PROJECTION,
                selection = null,
                selectionArgs = null,
                sortOrder = null
            ) { row -> mapCreatedNoteRow(numeric, row) }
        ) {
            is ProviderQueryResult.Success -> result.data.firstOrNull() ?: CreatedNoteRowRead.Missing
            is ProviderQueryResult.Empty -> CreatedNoteRowRead.Missing
            is ProviderQueryResult.Failure -> CreatedNoteRowRead.Failed(result.failure)
        }
    }

    override suspend fun readNoteCards(authority: String, noteId: String): CreatedNoteCardsRead {
        val numeric = noteId.toLongOrNull()?.takeIf { it > 0L }
            ?: return CreatedNoteCardsRead.Failed(
                AnkiDroidFailure(
                    category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                    evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                    evidenceToken = "note_id_not_numeric"
                )
            )
        return when (
            val result = providerClient.safeQuery(
                authority = authority,
                path = "${AnkiDroidApiContract.NOTE_ITEM_PATH}/$numeric/${AnkiDroidApiContract.NOTE_CARDS_PATH}",
                projection = AnkiDroidApiContract.NOTE_CARDS_HYDRATION_PROJECTION,
                selection = null,
                selectionArgs = null,
                sortOrder = null
            ) { row -> mapCreatedCardRow(row) }
        ) {
            is ProviderQueryResult.Success -> CreatedNoteCardsRead.Cards(result.data)
            is ProviderQueryResult.Empty -> CreatedNoteCardsRead.Cards(emptyList())
            is ProviderQueryResult.Failure -> CreatedNoteCardsRead.Failed(result.failure)
        }
    }

    // ---------------------------------------------------------------- row mappers (private, pure)

    private fun mapCreatedNoteRow(noteId: Long, row: AnkiDroidProviderRow): CreatedNoteRowRead {
        val midIndex = row.columnIndex(AnkiDroidApiContract.NOTE_MID_COLUMN)
        val modelId = if (midIndex < 0 || row.isNull(midIndex)) null else row.getLong(midIndex).toString()
        val tagsIndex = row.columnIndex(AnkiDroidApiContract.NOTE_TAGS_COLUMN)
        val tagsCell = if (tagsIndex < 0 || row.isNull(tagsIndex)) "" else row.getString(tagsIndex) ?: ""
        val fldsIndex = row.columnIndex(AnkiDroidApiContract.NOTE_FIELDS_COLUMN)
        if (fldsIndex < 0 || row.isNull(fldsIndex)) return CreatedNoteRowRead.Missing
        val flds = row.getString(fldsIndex) ?: return CreatedNoteRowRead.Missing
        return CreatedNoteRowRead.Found(
            noteId = noteId.toString(),
            modelId = modelId,
            tags = tagsCell.split(AnkiDroidApiContract.TAGS_SEPARATOR).filter { it.isNotBlank() },
            fields = flds.split(AnkiDroidApiContract.FIELD_SEPARATOR)
        )
    }

    private fun mapCreatedCardRow(row: AnkiDroidProviderRow): AnkiDroidCreatedCardRow {
        val idIndex = row.columnIndex(AnkiDroidApiContract.CARD_ID_COLUMN)
        val cardId = if (idIndex < 0 || row.isNull(idIndex)) null else row.getLong(idIndex).toString()
        val ordIndex = row.columnIndex(AnkiDroidApiContract.CARD_ORD_COLUMN)
        val ord = if (ordIndex < 0 || row.isNull(ordIndex)) null else row.getInt(ordIndex)
        val nameIndex = row.columnIndex(AnkiDroidApiContract.CARD_NAME_COLUMN)
        val name = if (nameIndex < 0 || row.isNull(nameIndex)) null else row.getString(nameIndex)
        val deckIndex = row.columnIndex(AnkiDroidApiContract.CARD_DECK_ID_COLUMN)
        val deckId = if (deckIndex < 0 || row.isNull(deckIndex)) null else row.getLong(deckIndex).toString()
        return AnkiDroidCreatedCardRow(
            cardId = cardId ?: "",
            ord = ord,
            name = name,
            deckId = deckId
        )
    }

    private companion object {
        const val TAG = "AnkiDroidCreation"

        /** A provider insert normally takes milliseconds; this covers collection-lock contention. */
        const val WRITE_TIMEOUT_MS = 20_000L

        /** How long an insert waits for a previous (possibly stuck) write before refusing. */
        const val PERMIT_TIMEOUT_MS = 5_000L
    }
}
