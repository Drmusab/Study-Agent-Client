package com.studyagent.client.core.study

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating

/** Read-only Gate 10 interaction data. No runtime backend, renderer or committed rating. */
data class AnkiStudyInteraction(
    val request: AnkiStudyRequest,
    val reviewSession: AnkiReviewSession? = null,
    val turn: AnkiReviewTurn? = null,
    val selectedRating: Rating? = null,
    val transcript: String? = null,
    val failure: AnkiError? = null,
    val completion: AnkiStudyCompletion? = null
)

/** Caller supplies a new logical session ID; a display deck name is never an identity. */
data class AnkiStudyRequest(
    val studySessionId: String,
    val preference: AnkiBackendMode,
    val deck: AnkiDeckRef,
    val speakQuestion: Boolean = true
) {
    init { require(studySessionId.isNotBlank()) }
}

enum class AnkiStudyCompletion { NO_DUE_CARDS, USER_ENDED }

/** Every read result is scoped to the session epoch; hydration also carries the original turn. */
sealed interface AnkiStudyEvent : StudyEvent {
    data class Start(val request: AnkiStudyRequest) : AnkiStudyEvent
    data class Begun(val epoch: Long, val result: AnkiResult<AnkiReviewSession>) : AnkiStudyEvent
    data class Scheduled(val epoch: Long, val result: NextCardResult) : AnkiStudyEvent
    data class Hydrated(
        val epoch: Long,
        val turnId: ReviewTurnId,
        val result: AnkiResult<AnkiRenderedCard>
    ) : AnkiStudyEvent
    data class SelectRating(val epoch: Long, val turnId: ReviewTurnId, val rating: Rating) : AnkiStudyEvent
}

/** Deliberately has no write/commit/skip operation. */
sealed interface AnkiStudyEffect : StudyEffect {
    data class Begin(val epoch: Long, val request: AnkiStudyRequest, val startedAtMs: Long) : AnkiStudyEffect
    data class Next(val epoch: Long, val session: AnkiReviewSession) : AnkiStudyEffect
    data class Hydrate(val epoch: Long, val turn: AnkiReviewTurn) : AnkiStudyEffect
    data object CancelReads : AnkiStudyEffect
}
