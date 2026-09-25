package com.studyagent.client.core.study

import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard

/**
 * Authoritative identity for one card presentation.
 *
 * §14-§17: `cardId` alone is not a unique interaction — the same Anki card
 * can be redelivered. [turnId] (server-provided `review_turn_id` when
 * available, otherwise `epoch:cardId:generation`) plus [generation] makes
 * every asynchronous callback attributable to the exact turn that produced it.
 */
data class CardTurn(
    val generation: Long,
    val card: StudyCard,
    val turnId: String,
    /** Server-provided `review_turn_id` when v2+; null on v1. */
    val serverTurnId: String? = null,
    /** Revision/sequence from server when available (§96). */
    val serverRevision: Long? = null,
    val evaluation: Evaluation? = null,
    val suggestedRating: Rating? = null,
    val answerRevealed: Boolean = false,
    val hintCount: Int = 0,
    val explanationRequested: Boolean = false,
    val repeatCount: Int = 0,
    /** Transcript parked for user approval when autoSubmit=false. */
    val pendingTranscript: String? = null
) {
    val cardId: String get() = card.id
    val hasPendingTranscript: Boolean get() = !pendingTranscript.isNullOrBlank()

    fun withEvaluation(evaluation: Evaluation): CardTurn = copy(
        evaluation = evaluation,
        suggestedRating = evaluation.suggestedRating
    )

    fun withPendingTranscript(text: String?): CardTurn = copy(pendingTranscript = text)

    fun withAnswerRevealed(): CardTurn = copy(answerRevealed = true)

    fun incrementHint(): CardTurn = copy(hintCount = hintCount + 1)

    companion object {
        /**
         * Turn ids are client-scoped identities: they key the submission ledger, turn-ownership
         * checks and stale-callback rejection, so they must be unique *per epoch*. A server turn
         * id alone is only unique within one server session — after a restart the server may
         * legitimately reuse "turn-1", and a ghost callback from epoch N must never match the
         * fresh turn of epoch N+1. Both branches therefore carry the epoch.
         */
        fun generateTurnId(epoch: Long, cardId: String, generation: Long, serverTurnId: String? = null): String =
            if (serverTurnId.isNullOrBlank()) "$epoch:$cardId:$generation" else "$epoch:$serverTurnId"
    }
}
