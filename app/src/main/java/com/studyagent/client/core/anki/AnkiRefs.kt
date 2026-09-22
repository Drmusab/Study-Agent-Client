package com.studyagent.client.core.anki

import kotlinx.serialization.Serializable

/** Backend-issued collection discriminator. Null means unknown, never an invented default. */
@Serializable
data class AnkiCollectionIdentity(val backendId: AnkiBackendId, val collectionKey: String?) {
    init { requireOptionalId(collectionKey) }
}

/** Display name/path is never identity. Known collection identity participates in equality. */
@Serializable
data class AnkiDeckRef(
    val backendId: AnkiBackendId,
    val deckId: String,
    val collectionKey: String? = null
) {
    init {
        require(deckId.isNotBlank())
        requireOptionalId(collectionKey)
    }
    val stableKey: String get() = identityKey(backendId.stableId, collectionKey, "deck", deckId)
}

/** A note generates cards, but is never itself a card reference. */
@Serializable
data class AnkiNoteRef(
    val backendId: AnkiBackendId,
    val noteId: String,
    val collectionKey: String? = null
) {
    init {
        require(noteId.isNotBlank())
        requireOptionalId(collectionKey)
    }
    val stableKey: String get() = identityKey(backendId.stableId, collectionKey, "note", noteId)

    fun toCardRef(cardOrd: Int?, cardId: String? = null): AnkiCardRef =
        AnkiCardRef(backendId, cardId, noteId, cardOrd, collectionKey)
}

/**
 * Either cardId or noteId + ordinal addresses the card. Structural equality deliberately
 * includes ALL fields, including collection and optional note identity. Adapters must normalize
 * refs consistently; enriching a ref is not proof it denotes the same persisted identity.
 * Unknown collection is not a wildcard. A reference is never a review-turn identifier.
 */
@Serializable
data class AnkiCardRef(
    val backendId: AnkiBackendId,
    val cardId: String? = null,
    val noteId: String? = null,
    val cardOrd: Int? = null,
    val collectionKey: String? = null
) {
    init {
        requireOptionalId(cardId)
        requireOptionalId(noteId)
        requireOptionalId(collectionKey)
        require(cardOrd == null || cardOrd >= 0)
        require(cardOrd == null || noteId != null) { "Ordinal requires a note identity" }
        require(cardId != null || (noteId != null && cardOrd != null)) {
            "Card identity requires cardId or noteId + ordinal"
        }
    }
    val stableKey: String
        get() = identityKey(backendId.stableId, collectionKey, "card", cardId, noteId, cardOrd?.toString())

    fun withCollectionKey(key: String?): AnkiCardRef = copy(collectionKey = key)
}
