package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.core.common.elapsedSince
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 16 — the note gateway (CHECKPOINT 06).
 *
 * One job: given one **exact** note identity, ask the pinned public note surface
 * (`notes/<noteId>`) and — for field names / note-type display facts — the exact note-type surface
 * (`models/<noteTypeId>`), and translate the answer into an [AnkiDroidNoteSnapshot] inside this
 * boundary. Callers receive domain-ready values or a typed [AnkiError] — never a `Cursor`, `Uri`,
 * projection or `ContentResolver` (INV-ANKI-CARD-11).
 *
 * What this type does **not** do, on purpose:
 *
 * - it never lists notes, searches notes or scans the collection (INV-16-12) — the lookups are
 *   `notes/<id>` and `models/<mid>` item reads only;
 * - it never renders, strips or rewrites field values (source values stay source values);
 * - it never writes — no tag edit, no field edit, no note creation (INV-16-01) — and a source
 *   scan enforces it;
 * - it never fabricates: missing/unreadable note or note-type identity and missing/mismatched
 *   exact model rows fail as data integrity; values without trustworthy field names degrade to
 *   `fields = null` plus a content-free token (GATE 16 §24).
 */
interface AnkiDroidNoteGateway {

    /**
     * The source facts of exactly one note, or a typed failure.
     *
     * Failures: [AnkiError.MalformedResponse] (identity/pairing cannot be trusted),
     * [AnkiError.NoteNotFound] (the note itself is gone — the caller decides what that means for
     * a card that still references it), the ordinary provider/permission/collection family, and
     * [AnkiError.InvalidRequest] for a blank identity. Cancellation always propagates.
     */
    suspend fun queryNoteDetails(
        authority: String,
        noteId: String,
        collectionKey: String? = null
    ): AnkiResult<AnkiDroidNoteSnapshot>

    /** Content-free facts about the last completed query, for diagnostics. Never note content. */
    fun lastQueryDiagnostics(): AnkiDroidNoteQueryDiagnostics
}

/** Content-free note-query facts (GATE 16). Identifiers, counts and tokens only. */
data class AnkiDroidNoteQueryDiagnostics(
    val lastStatus: String,
    val lastNoteId: String? = null,
    val lastNoteTypeId: String? = null,
    val fieldCount: Int? = null,
    val tagCount: Int? = null,
    val lastDegradations: List<String> = emptyList(),
    val lastErrorCategory: String? = null,
    val lastQueryDurationMs: Long? = null,
    val providerQueryCount: Long = 0L
) {
    companion object {
        val NONE = AnkiDroidNoteQueryDiagnostics(lastStatus = "NONE")
    }
}

class DefaultAnkiDroidNoteGateway(
    private val providerClient: AnkiDroidProviderClient,
    private val clock: AppClock = SystemAppClock,
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
) : AnkiDroidNoteGateway {

    private val mutex = Mutex()
    private var diagnostics: AnkiDroidNoteQueryDiagnostics = AnkiDroidNoteQueryDiagnostics.NONE
    /** Count provider calls in the currently serialized operation for content-free diagnostics. */
    private var operationProviderQueries: Long = 0L

    override fun lastQueryDiagnostics(): AnkiDroidNoteQueryDiagnostics = diagnostics

    override suspend fun queryNoteDetails(
        authority: String,
        noteId: String,
        collectionKey: String?
    ): AnkiResult<AnkiDroidNoteSnapshot> {
        if (noteId.isBlank()) {
            return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "note_id_blank"))
        }
        val canonicalNoteId = AnkiDroidNoteMapper.parsePositiveLong(noteId)
            ?: return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "note_id_invalid"))
        if (collectionKey != null && collectionKey.isBlank()) {
            return AnkiResult.Failure(AnkiError.InvalidRequest(detail = "collection_key_blank"))
        }
        return try {
            mutex.withLock {
                operationProviderQueries = 0L
                performQuery(authority, canonicalNoteId, collectionKey)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            record(status = "ERROR", noteId = noteId, errorCategory = throwable::class.java.simpleName)
            AnkiResult.Failure(
                AnkiDroidErrorMapper.mapThrowable(throwable, "note", AnkiDroidOperationStage.PROVIDER_QUERY)
            )
        }
    }

    private suspend fun performQuery(
        authority: String,
        noteId: String,
        collectionKey: String?
    ): AnkiResult<AnkiDroidNoteSnapshot> {
        val startedAt = clock.nowMillis()
        operationProviderQueries += 1L
        val noteQuery = providerClient.safeQuery(
            authority = authority,
            path = "${AnkiDroidApiContract.NOTE_ITEM_PATH}/$noteId",
            projection = AnkiDroidApiContract.NOTE_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = null,
            mapper = { row -> row }
        )

        val noteRow = when (noteQuery) {
            is ProviderQueryResult.Failure -> {
                val error = AnkiDroidErrorMapper.mapQueryResultFailure(noteQuery)
                if (error is AnkiError.CardNotFound) {
                    record(status = "NOT_FOUND", noteId = noteId, errorCategory = "note_not_found")
                    return AnkiResult.Failure(AnkiError.NoteNotFound())
                }
                record(status = "ERROR", noteId = noteId, errorCategory = noteQuery.failure.category.name)
                return AnkiResult.Failure(error)
            }
            // A singular note lookup answering with no row is "that note does not exist" —
            // never an empty snapshot (GATE 16 §24/INV-16-10).
            is ProviderQueryResult.Empty -> {
                record(status = "NOT_FOUND", noteId = noteId, durationMs = clock.elapsedSince(startedAt))
                return AnkiResult.Failure(AnkiError.NoteNotFound())
            }
            is ProviderQueryResult.Success -> noteQuery.data.firstOrNull()
        }
        if (noteRow == null) {
            record(status = "NOT_FOUND", noteId = noteId, durationMs = clock.elapsedSince(startedAt))
            return AnkiResult.Failure(AnkiError.NoteNotFound())
        }

        // Exact note-type follow-up for field names / note-type display facts. Note type identity
        // is parsed as a positive backend ID, never copied as an arbitrary path segment.
        val noteTypeId = AnkiDroidNoteMapper.parsePositiveLong(
            AnkiDroidMapper.getOptionalString(
                noteRow,
                AnkiDroidMapper.optionalColumnIndex(noteRow, AnkiDroidApiContract.NOTE_MID_COLUMN)
            )
        ) ?: run {
            record(
                status = "MALFORMED",
                noteId = noteId,
                errorCategory = "note_type_id_unreadable",
                durationMs = clock.elapsedSince(startedAt)
            )
            return AnkiResult.Failure(AnkiError.DataIntegrityFailure(detail = "note_type_id_unreadable"))
        }
        val modelRow = when (val model = queryModelRow(authority, noteTypeId)) {
            is AnkiResult.Failure -> {
                if (model.error is AnkiError.CardNotFound) {
                    record(
                        status = "MALFORMED",
                        noteId = noteId,
                        noteTypeId = noteTypeId,
                        errorCategory = "note_type_missing_for_note",
                        durationMs = clock.elapsedSince(startedAt)
                    )
                    return AnkiResult.Failure(
                        AnkiError.DataIntegrityFailure(detail = "note_type_missing_for_note")
                    )
                }
                record(
                    status = "ERROR",
                    noteId = noteId,
                    noteTypeId = noteTypeId,
                    errorCategory = model.error::class.java.simpleName
                )
                return model
            }
            is AnkiResult.Success -> model.value ?: run {
                record(
                    status = "MALFORMED",
                    noteId = noteId,
                    noteTypeId = noteTypeId,
                    errorCategory = "note_type_missing_for_note",
                    durationMs = clock.elapsedSince(startedAt)
                )
                return AnkiResult.Failure(
                    AnkiError.DataIntegrityFailure(detail = "note_type_missing_for_note")
                )
            }
        }

        val outcome = AnkiDroidNoteMapper.mapNoteRow(noteRow, modelRow, backendId, collectionKey)
        return when (outcome) {
            is AnkiDroidNoteRowOutcome.Malformed -> {
                record(
                    status = "MALFORMED",
                    noteId = noteId,
                    durationMs = clock.elapsedSince(startedAt)
                )
                AnkiResult.Failure(
                    AnkiError.DataIntegrityFailure(detail = outcome.problem.token)
                )
            }
            is AnkiDroidNoteRowOutcome.Valid -> {
                // The row must confirm the note identity that was asked for (STEP 54's rule): a
                // different note id in the row is a stale/foreign answer, never published.
                if (outcome.note.noteRef.noteId != noteId) {
                    record(status = "MALFORMED", noteId = noteId, durationMs = clock.elapsedSince(startedAt))
                    return AnkiResult.Failure(
                        AnkiError.DataIntegrityFailure(detail = "note_identity_mismatch")
                    )
                }
                record(
                    status = "OK",
                    noteId = noteId,
                    noteTypeId = outcome.note.noteTypeId,
                    fieldCount = outcome.note.fields?.size,
                    tagCount = outcome.note.tags?.size,
                    degradations = outcome.note.degradations,
                    durationMs = clock.elapsedSince(startedAt)
                )
                AnkiResult.Success(outcome.note)
            }
        }
    }

    /** One exact `models/<id>` row. Provider failures stay typed; empty is data-integrity failure. */
    private suspend fun queryModelRow(
        authority: String,
        noteTypeId: String
    ): AnkiResult<AnkiDroidProviderRow?> = when (
        val query = providerQuery(
            authority = authority,
            path = "${AnkiDroidApiContract.MODELS_PATH}/$noteTypeId",
            projection = AnkiDroidApiContract.MODEL_PROJECTION
        )
    ) {
        is ProviderQueryResult.Success -> AnkiResult.Success(query.data.firstOrNull())
        is ProviderQueryResult.Empty -> AnkiResult.Success(null)
        is ProviderQueryResult.Failure -> AnkiResult.Failure(AnkiDroidErrorMapper.mapQueryResultFailure(query))
    }

    private suspend fun providerQuery(
        authority: String,
        path: String,
        projection: Array<String>
    ): ProviderQueryResult<AnkiDroidProviderRow> {
        operationProviderQueries += 1L
        return providerClient.safeQuery(
            authority = authority,
            path = path,
            projection = projection,
            selection = null,
            selectionArgs = null,
            sortOrder = null,
            mapper = { row -> row }
        )
    }

    private fun record(
        status: String,
        noteId: String? = null,
        noteTypeId: String? = null,
        fieldCount: Int? = null,
        tagCount: Int? = null,
        degradations: List<String> = emptyList(),
        errorCategory: String? = null,
        durationMs: Long? = null
    ) {
        // Content-free: identifiers, counts and tokens only (never a field, tag or name).
        diagnostics = AnkiDroidNoteQueryDiagnostics(
            lastStatus = status,
            lastNoteId = noteId,
            lastNoteTypeId = noteTypeId,
            fieldCount = fieldCount,
            tagCount = tagCount,
            lastDegradations = degradations,
            lastErrorCategory = errorCategory,
            lastQueryDurationMs = durationMs,
            providerQueryCount = diagnostics.providerQueryCount + operationProviderQueries
        )
        operationProviderQueries = 0L
    }
}
