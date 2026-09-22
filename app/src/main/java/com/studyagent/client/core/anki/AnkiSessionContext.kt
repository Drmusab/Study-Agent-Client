package com.studyagent.client.core.anki

import kotlinx.serialization.Serializable

/**
 * Immutable session-start ownership lock. The future session coordinator holds this, not UI.
 * Reuses StudySession.sessionId (String); this is NOT a second Anki study-session identity.
 * No backend object is retained. Availability changes cannot rewrite this value.
 */
data class AnkiSessionContext(
    val backendId: AnkiBackendId,
    val collection: AnkiCollectionIdentity?,
    val deckRef: AnkiDeckRef?,
    val startedAtEpochMs: Long,
    val capabilities: AnkiCapabilities,
    val studySessionId: String
) {
    init {
        require(studySessionId.isNotBlank())
        require(startedAtEpochMs >= 0)
        require(collection == null || collection.backendId == backendId)
        require(deckRef == null || deckRef.backendId == backendId)
        require(collection?.collectionKey == null || deckRef?.collectionKey == null ||
            collection.collectionKey == deckRef.collectionKey)
    }
}

/** Wraps the existing CardTurn.turnId at the boundary; never derives identity from card alone. */
@Serializable
data class ReviewTurnId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

/** Same backend + study session + turn = same retry key; changed rating is a conflict. */
@Serializable
data class ReviewCommitId(
    val backendId: AnkiBackendId,
    val studySessionId: String,
    val turnId: ReviewTurnId
) {
    init { require(studySessionId.isNotBlank()) }
    val stableKey: String get() = identityKey(backendId.stableId, studySessionId, turnId.value)
}
