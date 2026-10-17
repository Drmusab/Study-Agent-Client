package com.studyagent.client.core.anki

import com.studyagent.client.core.anki.customstudy.AnkiStudyMode
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
    val studySessionId: String,
    /**
     * GATE 20 — which of the backend's study surfaces this session draws from
     * (DECISION-20-09, IMPLEMENTATION-20-07). It is part of the *session's* identity for its
     * lifetime and is immutable like every other field here: a session never migrates from filtered
     * review to ordinary review, or the other way round (INV-20-14/INV-20-18).
     *
     * It is deliberately **not** persisted (see [StudySessionBackendLock.studyMode]): a restored
     * session is always [AnkiStudyMode.Normal], and a non-normal mode is unreachable in this build
     * because [com.studyagent.client.core.anki.customstudy.CustomStudyPolicy] refuses every start
     * that would need one. That makes the restore default a documented impossibility rather than a
     * silent downgrade.
     */
    val studyMode: AnkiStudyMode = AnkiStudyMode.DEFAULT
) {
    init {
        require(studySessionId.isNotBlank())
        require(startedAtEpochMs >= 0)
        // A filtered session studies the filtered deck it is bound to; a context that names a
        // different deck is contradictory data, refused at construction (never repaired).
        require(studyMode !is AnkiStudyMode.Filtered || deckRef == null ||
            (deckRef.backendId == studyMode.filteredDeckRef.backendId &&
                deckRef.deckId == studyMode.filteredDeckRef.deckId)) {
            "A filtered study mode must be bound to the filtered deck it names"
        }
        // A collection identity naming a *different* backend than its own context is
        // self-contradictory data — structural nonsense, refused at construction.
        // A foreign *deckRef* is different in kind: it is a cross-backend request that the
        // backend must answer with a typed SessionInvalid / DeckNotFound (beginReview checks
        // `context.backendId != id` first and resolves decks against the provider listing),
        // never a crash before that guard can run (INV-ANKI-DET-09).
        require(collection == null || collection.backendId == backendId)
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
