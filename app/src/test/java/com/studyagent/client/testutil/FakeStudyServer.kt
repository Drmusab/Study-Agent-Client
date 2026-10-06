package com.studyagent.client.testutil

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scripted PC agent for the JVM harness (§12/§13).
 *
 * This is a double of the *server*, not of the client: it holds a deck, answers client messages in
 * protocol order, and can be told to misbehave the way a real PC agent (or the LLM pipeline behind
 * it) does — duplicate replies, a dropped acknowledgement, a socket that dies mid-turn, an
 * out-of-order frame.
 *
 * What it deliberately does **not** contain: any study logic. Scheduling, rating semantics and
 * progress through the deck belong to the real PC agent; this double only has to produce frames
 * the client must react to correctly.
 *
 * Replies are scheduled with a small virtual delay ([replyDelayMs]) so they arrive from the
 * coroutine scheduler rather than nested inside the client's own `send()` call — which is both
 * closer to a real socket and a much better test of the client's async boundaries.
 */
class FakeStudyServer(
    private val scope: CoroutineScope,
    var sessionId: String = "s1",
    var deckName: String = "Toronto Notes",
    var totalCards: Int = TestCards.ENDURANCE_DECK_SIZE,
    /** False means the test drives every frame by hand with [push]. */
    var autoRespond: Boolean = true,
    var evaluate: (String) -> Evaluation = { TestEvaluations.good() },
    var speakQuestions: Boolean = true,
    var speakFeedback: Boolean = true,
    /** Extra copies of every reply, to exercise client-side idempotency. */
    var duplicateEvaluations: Int = 0,
    var duplicateRatingAcks: Int = 0,
    var duplicateQuestions: Int = 0,
    /** Drop every n-th reply (0 = never): exercises timeouts and reconnect recovery. */
    var dropEveryNthReply: Int = 0,
    /** Withhold *every* reply while true: parks the session in an in-flight phase. */
    var holdReplies: Boolean = false,
    /** A frame that arrives before the one the client is waiting for. */
    var reorderReplies: Boolean = false,
    /** Virtual latency of each reply. */
    var replyDelayMs: Long = 1L
) {

    /**
     * What this agent advertises at handshake. The default advertises `review_commit_idempotency`
     * because this fake genuinely implements it below: a replayed `rate_card` carrying the same
     * logical commit id is answered from the stored receipt and never applied twice — first write
     * wins, exactly like the mock PC agent's `--commit-store` (PROTOCOL.md §6: rate_card is
     * idempotent). Set to an empty set to play a legacy agent that must not be replayed against.
     */
    var advertisedCapabilities: Set<String> = setOf(
        com.studyagent.client.core.models.AgentCapability.REVIEW_COMMIT_IDEMPOTENCY
    )

    /** Durable per-commit receipts, keyed by the logical review commit id. */
    private val ratingCommits = LinkedHashMap<String, ServerMessage.RatingSaved>()

    /** Where replies go. Wired by the harness to the connection double. */
    var onReply: ((ServerMessage) -> Unit)? = null

    /** Cards handed out so far; also the deck cursor. */
    var cardIndex: Int = 0
        private set

    var currentCardId: String? = null
        private set

    var answeredCards: MutableList<String> = mutableListOf()
        private set

    var ratedCards: MutableList<String> = mutableListOf()
        private set

    val receivedMessages: MutableList<ClientMessage> = mutableListOf()

    /** The most recent question/evaluation/rating-ack frames, so a test can retransmit them. */
    var lastQuestion: ServerMessage.Question? = null
        private set
    var lastEvaluation: ServerMessage.EvaluationResponse? = null
        private set
    var lastRatingSaved: ServerMessage.RatingSaved? = null
        private set

    /**
     * How many `submit_answer` / `rate_card` frames the agent received.
     *
     * Derived from [receivedMessages] rather than kept as separate counters: a duplicate counter
     * that can drift from the actual traffic is exactly the kind of double-bookkeeping that makes
     * an exactly-once assertion pass while the wire carries two frames (§25/§97).
     */
    val answersReceived: Int get() = receivedMessages.count { it is ClientMessage.SubmitAnswer }
    val ratingsReceived: Int get() = receivedMessages.count { it is ClientMessage.RateCard }

    var repliesSent: Int = 0
        private set
    var droppedReplies: Int = 0
        private set

    var sessionPaused: Boolean = false
        private set
    var sessionFinished: Boolean = false
        private set

    // ------------------------------------------------------------------ client → server

    fun onClientMessage(message: ClientMessage) {
        receivedMessages += message
        if (!autoRespond) return
        when (message) {
            is ClientMessage.StartSession -> {
                sessionId = message.sessionId ?: sessionId
                cardIndex = 0
                sessionFinished = false
                sessionPaused = false
                send(
                    ServerMessage.SessionStarted(
                        sessionId = sessionId,
                        deck = message.deck ?: deckName,
                        totalCards = totalCards
                    )
                )
                nextQuestion()
            }

            is ClientMessage.SubmitAnswer -> {
                val cardId = currentCardId
                if (cardId != null && cardId == message.cardId) answeredCards += cardId
                val evaluation = evaluate(message.text)
                val frame = ServerMessage.EvaluationResponse(
                    sessionId = sessionId,
                    cardId = message.cardId,
                    score = evaluation.score,
                    correctPoints = evaluation.correctPoints,
                    missingPoints = evaluation.missingPoints,
                    incorrectPoints = evaluation.incorrectPoints,
                    shortFeedback = evaluation.shortFeedback,
                    suggestedRating = evaluation.suggestedRating,
                    confidence = evaluation.confidence,
                    speak = speakFeedback,
                    reviewTurnId = message.reviewTurnId,
                    // PROTOCOL.md §3.4: evaluation_response correlates via in_reply_to.
                    inReplyTo = message.messageId
                )
                lastEvaluation = frame
                if (reorderReplies) send(ServerMessage.Pong())
                repeat(duplicateEvaluations + 1) { send(frame) }
            }

            is ClientMessage.RateCard -> {
                val commitKey = message.reviewCommitId ?: message.reviewTurnId
                val stored = if (commitKey != null && advertisesIdempotency()) ratingCommits[commitKey] else null
                if (stored != null) {
                    // Idempotent replay: the logical commit was already applied. The card is not
                    // counted again and the deck does not advance again; the stored receipt is
                    // re-answered correlated to *this* request so a user retry completes, and the
                    // current question is re-pushed in case the original push was lost. A retry
                    // that changed the rating still gets the first committed rating back — the
                    // client's rating correlation then fails closed, which is the honest answer.
                    send(stored.copy(messageId = null, inReplyTo = message.messageId))
                    if (!sessionFinished) lastQuestion?.let { send(it.copy(messageId = null)) }
                } else {
                    ratedCards += message.cardId
                    val ack = ServerMessage.RatingSaved(
                        sessionId = sessionId,
                        cardId = message.cardId,
                        rating = message.rating,
                        nextInterval = "1d",
                        reviewTurnId = message.reviewTurnId,
                        // PROTOCOL.md §3.5: rating_saved correlates via in_reply_to, and the
                        // client only accepts an ack that proves it answers this exact rate_card.
                        inReplyTo = message.messageId
                    )
                    lastRatingSaved = ack
                    if (commitKey != null && advertisesIdempotency()) ratingCommits[commitKey] = ack
                    repeat(duplicateRatingAcks + 1) { send(ack) }
                    if (ratedCards.size >= totalCards) {
                        sessionFinished = true
                        send(
                            ServerMessage.SessionFinished(
                                sessionId = sessionId,
                                totalReviewed = ratedCards.size,
                                summary = "Deck complete"
                            )
                        )
                    } else {
                        nextQuestion()
                    }
                }
            }

            is ClientMessage.PauseSession -> {
                sessionPaused = true
                send(ServerMessage.SessionPaused(sessionId = sessionId))
            }

            is ClientMessage.ResumeSession -> {
                sessionPaused = false
                send(ServerMessage.SessionResumed(sessionId = sessionId))
            }

            is ClientMessage.EndSession -> {
                sessionFinished = true
                send(
                    ServerMessage.SessionFinished(
                        sessionId = sessionId,
                        totalReviewed = ratedCards.size,
                        summary = "Session ended by user"
                    )
                )
            }

            is ClientMessage.SkipCard -> nextQuestion()
            is ClientMessage.RepeatQuestion -> lastQuestion?.let { q -> repeat(duplicateQuestions + 1) { send(q) } }
            is ClientMessage.RequestSessionStatus -> send(sessionStatus())
            is ClientMessage.RequestSessionSnapshot -> send(sessionStatus())
            is ClientMessage.Ping -> send(ServerMessage.Pong())
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ explicit control

    /** Emits the next card, whether or not the client asked for one. */
    fun nextQuestion() {
        cardIndex += 1
        val card = TestCards.numbered(cardIndex, deckName = deckName)
        currentCardId = card.id
        val frame = ServerMessage.Question(
            sessionId = sessionId,
            cardId = card.id,
            question = card.question,
            cardNumber = card.cardNumber,
            remaining = card.remaining,
            speak = speakQuestions,
            reviewTurnId = "turn-$cardIndex"
        )
        lastQuestion = frame
        repeat(duplicateQuestions + 1) { send(frame) }
    }

    /** Re-sends the current question under a new message id (a retransmission, not a new card). */
    fun resendCurrentQuestion() {
        lastQuestion?.let { send(it.copy(messageId = null)) }
    }

    /** Re-sends the last evaluation (a duplicate the client must reject by turn identity). */
    fun resendLastEvaluation() {
        lastEvaluation?.let { send(it.copy(messageId = null)) }
    }

    /**
     * Re-sends the last rating acknowledgement.
     *
     * A late/duplicate `rating_saved` must be absorbed by the client — it arrives when the client
     * has already moved on to the next card, and treating it as "a rating was accepted" would
     * double-advance the deck.
     */
    fun resendLastRatingSaved() {
        lastRatingSaved?.let { send(it.copy(messageId = null)) }
    }

    /** Injects an arbitrary frame; used by the chaos and protocol suites. */
    fun push(message: ServerMessage) = send(message)

    private fun advertisesIdempotency(): Boolean =
        com.studyagent.client.core.models.AgentCapability.REVIEW_COMMIT_IDEMPOTENCY in advertisedCapabilities

    fun sessionStatus(): ServerMessage.SessionStatus = ServerMessage.SessionStatus(
        sessionId = sessionId,
        currentCardId = currentCardId,
        currentQuestion = lastQuestion?.question,
        cardNumber = lastQuestion?.cardNumber,
        remaining = lastQuestion?.remaining,
        reviewTurnId = lastQuestion?.reviewTurnId,
        awaiting = when {
            sessionFinished -> "finished"
            sessionPaused -> "paused"
            answeredCards.size > ratedCards.size -> "rating"
            else -> "answer"
        },
        isPaused = sessionPaused,
        isFinished = sessionFinished
    )

    private fun send(message: ServerMessage) {
        val hook = onReply ?: return
        if (holdReplies) return
        repliesSent++
        if (dropEveryNthReply > 0 && repliesSent % dropEveryNthReply == 0) {
            droppedReplies++
            return
        }
        scope.launch {
            if (replyDelayMs > 0L) delay(replyDelayMs)
            hook(message)
        }
    }
}
