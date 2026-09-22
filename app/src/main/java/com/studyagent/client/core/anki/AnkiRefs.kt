package com.studyagent.client.core.anki

/**
 * GATE 01 contract — backend-qualified identity references.
 *
 * Rules these types encode (see `docs/ANKI_INTEGRATION_ARCHITECTURE.md`):
 *
 *  - Every reference carries its [AnkiBackendId]; raw ids are never compared
 *    across backends (INV-ANKI-06).
 *  - Deck identity is a backend-scoped id, never the display name (§49: names
 *    change, duplicate across collections, and nest as paths).
 *  - Notes and cards are distinct (§50): one note produces many cards.
 *  - A card ref may legitimately lack `cardId` (some backends can only address
 *    card-by-note+ord at first); refs never fabricate identifiers — optional
 *    fields stay null when the backend cannot supply them (§9).
 *  - `collectionKey` is an opaque, backend-issued collection discriminator;
 *    null when the backend cannot prove collection identity yet (§24).
 */

/** Opaque identity of one Anki collection as seen by one backend. */
data class AnkiCollectionIdentity(
    val backendId: AnkiBackendId,
    /** Backend-issued opaque token (provider collection id, profile token...); never parsed. */
    val collectionKey: String?
)

/** Backend-qualified deck identity. Display names live on `AnkiDeck`, not here. */
data class AnkiDeckRef(
    val backendId: AnkiBackendId,
    /** Backend-scoped stable deck id (AnkiDroid deck id, or backend-defined key). */
    val deckId: String
) {
    init {
        require(deckId.isNotBlank()) { "AnkiDeckRef.deckId must not be blank" }
    }

    /** Human/audit key — safe for diagnostics, contains no content. */
    val stableKey: String get() = "${backendId.stableId}|deck:$deckId"
}

/** Backend-qualified note identity. A note generates one or more cards. */
data class AnkiNoteRef(
    val backendId: AnkiBackendId,
    val noteId: String,
    val collectionKey: String? = null
) {
    init {
        require(noteId.isNotBlank()) { "AnkiNoteRef.noteId must not be blank" }
    }

    val stableKey: String get() = "${backendId.stableId}|${collectionKey ?: "?"}|note:$noteId"

    fun toCardRef(cardOrd: Int?, cardId: String? = null): AnkiCardRef =
        AnkiCardRef(
            backendId = backendId,
            cardId = cardId,
            noteId = noteId,
            cardOrd = cardOrd,
            collectionKey = collectionKey
        )
}

/**
 * Backend-qualified card identity (§9).
 *
 * Either [cardId], or the pair ([noteId] + [cardOrd]), must be present — a ref
 * that cannot address a card at all is a construction error, not a runtime
 * surprise. Cross-backend equality is meaningless by construction: identical
 * `cardId`s on different backends never compare equal because [backendId] is
 * part of the value.
 */
data class AnkiCardRef(
    val backendId: AnkiBackendId,
    val cardId: String?,
    val noteId: String?,
    val cardOrd: Int?,
    val collectionKey: String?
) {
    init {
        require(cardId != null || (noteId != null && cardOrd != null)) {
            "AnkiCardRef requires cardId, or noteId + cardOrd (backend=$backendId)"
        }
    }

    /** Content-free key for ledgers, logging and equality across layers. */
    val stableKey: String
        get() {
            val cardPart = cardId?.let { "card:$it" } ?: "note:$noteId#ord=$cardOrd"
            return "${backendId.stableId}|${collectionKey ?: "?"}|$cardPart"
        }

    fun withCollectionKey(key: String?): AnkiCardRef = copy(collectionKey = key)
}
