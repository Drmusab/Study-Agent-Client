package com.studyagent.client.core.anki

import java.security.MessageDigest

/**
 * §28/§31 opaque pagination cursor. The UI/ViewModel may store and return it verbatim but must
 * never parse, modify, construct or increment it — only the backend adapter that produced it
 * understands its representation. A cursor is valid for exactly one logical query
 * ([AnkiCardQuery.consistencyKey]) on exactly one backend/collection.
 */
@JvmInline
value class AnkiPageCursor(val value: String)

/**
 * §34 optional stronger snapshot marker. It is `null` for every backend that cannot prove
 * snapshot isolation, and GATE 15 does not require snapshot semantics (§40) — the type exists so a
 * backend that *can* prove them has a domain home instead of fabricating consistency claims.
 */
@JvmInline
value class AnkiQuerySnapshotToken(val value: String)

/**
 * §28 bounded page request. Java-free construction: an out-of-range limit is *representable* and
 * rejected by [AnkiCardQuery.structuralError] with [AnkiError.InvalidQuery] (§29 preferred policy:
 * reject rather than silently change the requested semantics).
 */
data class AnkiPageRequest(
    val limit: Int = DEFAULT_LIMIT,
    val cursor: AnkiPageCursor? = null
) {
    companion object {
        const val MIN_LIMIT: Int = 1
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = 100

        /** The canonical first page of a query. */
        fun firstPage(limit: Int = DEFAULT_LIMIT): AnkiPageRequest = AnkiPageRequest(limit = limit)
    }
}

/**
 * §34 one bounded page of browse results.
 *
 * - `items` are lightweight rows ([AnkiCardListItem]); HTML, media and template data never appear
 *   here (§47).
 * - [nextCursor] is opaque continuation state or `null` when no further page is known to exist.
 *   `nextCursor == null` never implies an empty collection (§35).
 * - [totalCount] is the authoritative number of matches, or `null` when the backend cannot provide
 *   one efficiently — never an estimate presented as exact, and never zero as a stand-in (§36,
 *   INV-15-Q14).
 *
 * §38 is enforced structurally: an empty page can never carry a cursor, because an empty page with
 * a live cursor is the classic infinite-pagination bug. Adapters build pages through [of], which
 * normalizes that case; the primary constructor additionally rejects the inconsistent combination
 * so a violation surfaces in tests instead of in production paging loops.
 */
data class AnkiCardPage(
    val items: List<AnkiCardListItem>,
    val nextCursor: AnkiPageCursor? = null,
    val totalCount: Int? = null,
    val snapshotToken: AnkiQuerySnapshotToken? = null
) {
    init {
        require(totalCount == null || totalCount >= 0) { "A total count is never negative" }
        require(items.isNotEmpty() || nextCursor == null) {
            "An empty page must not carry a continuation cursor"
        }
    }

    /** §35/§38 canonical page construction. `nextCursor` is dropped for an empty page. */
    companion object {
        fun of(
            items: List<AnkiCardListItem>,
            nextCursor: AnkiPageCursor? = null,
            totalCount: Int? = null,
            snapshotToken: AnkiQuerySnapshotToken? = null
        ): AnkiCardPage = AnkiCardPage(
            items = items,
            nextCursor = if (items.isEmpty()) null else nextCursor,
            totalCount = totalCount,
            snapshotToken = snapshotToken
        )
    }
}

/**
 * §43/§44 row identity validation. A browser row must carry enough identity to construct an
 * [AnkiCardRef]; optional presentation metadata (deck name, previews, scheduling) may be missing.
 * Returning a non-null detail means the backend must fail the page with
 * [AnkiError.DataIntegrityFailure] rather than publish an invented or foreign identity.
 *
 * [requiredDeckId] is the deck identity of an exact-deck scope: a page for deck X may only contain
 * rows that authoritatively report deck X.
 */
fun AnkiCardListItem.pageIdentityError(
    backendId: AnkiBackendId,
    requiredDeckId: String? = null
): String? = when {
    cardRef.backendId != backendId -> "foreign_card_ref"
    noteId.isNullOrBlank() -> "card_row_missing_note_identity"
    deckId.isNullOrBlank() -> "card_row_missing_deck_identity"
    requiredDeckId != null && deckId != requiredDeckId -> "card_row_out_of_scope"
    else -> null
}

/**
 * §32 offset-based backend adaptation. A backend that can only page by offset encodes that offset
 * in the opaque cursor, *bound to the query consistency key* so a cursor from another query,
 * another backend or another collection can never silently resume the wrong result set (§30/§33).
 *
 * The representation is backend-internal: the UI layer never references this type (audited).
 */
class AnkiOffsetCursorAdapter(private val consistencyKey: String) {

    private val fingerprint: String = sha256Hex(consistencyKey).take(FINGERPRINT_LENGTH)

    /** Opaque continuation for [offset] within this exact query. */
    fun cursorFor(offset: Int): AnkiPageCursor =
        AnkiPageCursor("v1:o=$offset:k=$fingerprint")

    /**
     * Resolves a cursor produced by this adapter for this query. [Malformed] and [ForeignQuery]
     * both map to [AnkiError.InvalidCursor]; a backend never restarts at page one silently.
     */
    fun offsetFor(cursor: AnkiPageCursor): Resolution {
        val value = cursor.value
        if (value.length > MAX_CURSOR_LENGTH) return Resolution.Malformed
        val prefix = "v1:o="
        if (!value.startsWith(prefix)) return Resolution.Malformed
        val keySeparator = value.indexOf(KEY_SEPARATOR, prefix.length)
        if (keySeparator < 0) return Resolution.Malformed
        val offset = value.substring(prefix.length, keySeparator).toIntOrNull()
            ?: return Resolution.Malformed
        if (offset < 0) return Resolution.Malformed
        val key = value.substring(keySeparator + KEY_SEPARATOR.length)
        // A structurally valid cursor that belongs to another query (or another backend/collection)
        // is a foreign cursor, not a malformed one — both are typed refusals, never a silent
        // restart at page one (§33/§52).
        return if (key == fingerprint) Resolution.Valid(offset) else Resolution.ForeignQuery
    }

    sealed interface Resolution {
        data class Valid(val offset: Int) : Resolution
        data object Malformed : Resolution
        data object ForeignQuery : Resolution
    }

    private companion object {
        const val FINGERPRINT_LENGTH = 32
        const val MAX_CURSOR_LENGTH = 256
        const val KEY_SEPARATOR = ":k="
    }
}

internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
