package com.studyagent.client.core.anki

/**
 * GATE 13 §2 — canonical reviewer-action identity.
 *
 * ```text
 * StudySessionId → ReviewTurnId → ReviewerActionId → backend action mutation
 * ```
 *
 * A [ReviewerActionId] is **not** a [ReviewCommitId], not a card id and not a [ReviewTurnId]
 * (§2): the two mutation families have separate identities because they have separate ledgers
 * (INV-13-02/INV-13-03).
 *
 * Identity is *derived*, never random: the same backend, study session, review turn and action
 * always produce the same id ([of]). That is what makes duplicate input one logical action
 * (VERIFICATION 12/13) — 100 concurrent bury requests on one turn collide on one id, so the ledger
 * holds one record and the backend sees at most one mutation — and it is what lets a retry reuse
 * the same identity after process death (INV-13-11). §12's "a new user action requires a new
 * ReviewerActionId" is satisfied by construction: a different action (a different flag value, bury
 * instead of suspend) or a different turn yields a different id.
 */
@JvmInline
value class ReviewerActionId(val value: String) {
    init { require(value.isNotBlank()) { "A reviewer action id must not be blank" } }

    override fun toString(): String = value

    companion object {
        /**
         * The one identity derivation. Length-prefixed like every other identity key in this
         * package, so no field boundary can be forged by a value that contains the delimiter.
         */
        fun of(
            backendId: AnkiBackendId,
            studySessionId: String,
            turnId: ReviewTurnId,
            action: ReviewerAction
        ): ReviewerActionId = ReviewerActionId(
            identityKey(backendId.stableId, studySessionId, turnId.value, action.key)
        )
    }
}

/**
 * GATE 13 §4 — the backend's own observable evidence that an action was applied.
 *
 * Content-free by contract: identifiers, a queue code, a flag value and a stable token. Never
 * question/answer text, HTML, a transcript or AI feedback — the ledger must stay a transaction
 * record, not a copy of the card.
 *
 * A receipt is *not* necessarily backend-issued: the pinned AnkiDroid public contract has no action
 * receipt, so its gateway produces one from the post-mutation card-state read
 * (`queue == desired queue`). [receiptId] stays null there — a fabricated id would be a lie.
 */
data class ReviewerActionReceipt(
    val backendId: AnkiBackendId,
    val actionKey: String,
    /** A backend-issued correlation id, when the contract has one. Null is honest, never invented. */
    val receiptId: String? = null,
    /** The minimal post-action card projection the backend reported. */
    val cardState: ReviewerCardState? = null,
    /** The flag the backend reports after a confirmed `SetFlag`. */
    val flag: AnkiFlag? = null,
    /** Stable, content-free token for diagnostics (e.g. `confirmed_by_card_state`). */
    val detail: String? = null,
    val observedAtEpochMs: Long? = null
) {
    init {
        require(actionKey.isNotBlank()) { "A receipt must name the action it is evidence for" }
        require(receiptId == null || receiptId.isNotBlank())
        require(detail == null || detail.length <= MAX_DETAIL_LENGTH) { "A receipt detail is a token" }
        require(observedAtEpochMs == null || observedAtEpochMs >= 0)
    }

    companion object {
        const val MAX_DETAIL_LENGTH = 96
    }
}

/**
 * Why an attempt did not apply. [category] is a stable, content-free token — the same discipline as
 * [ReviewCommitFailure]: no provider text, no exception text, no card content.
 */
data class ReviewerActionFailure(val category: String) {
    init { require(category.isNotBlank()) }
}

/**
 * GATE 13 §4 — the canonical durable record.
 *
 * Study-Agent's spec names two types this codebase deliberately does not duplicate:
 *
 * | Spec name | Here | Why |
 * |---|---|---|
 * | `StudySessionId` | [sessionId]: `String` | the study session id is a `String` everywhere (`AnkiStudyRequest.studySessionId`, `ReviewCommitId.studySessionId`); a wrapper would be a second identity for one thing |
 * | `AnkiCollectionRef` | [collectionRef]: [AnkiCollectionIdentity] | the existing collection identity type; a synonym would fork collection identity |
 *
 * [action], [cardRef], [turnId], [sessionId] and [backendId] are fixed at creation: a retry re-sends
 * exactly the same action on exactly the same card, and a different action for the same
 * [actionId] is impossible by construction (§12, INV-13-11).
 *
 * [status] is the durable transaction truth and nothing else in this record competes with it:
 * [attemptCount] counts boundary crossings, [backendReceipt] is evidence, [failure] is a token and
 * [resolution] says *how* the status was reached.
 */
data class ReviewerActionRecord(
    val actionId: ReviewerActionId,
    val sessionId: String,
    val turnId: ReviewTurnId,
    val backendId: AnkiBackendId,
    val collectionRef: AnkiCollectionIdentity?,
    val cardRef: AnkiCardRef,

    val action: ReviewerAction,

    val status: ReviewerActionStatus,
    val attemptCount: Int,

    val backendReceipt: ReviewerActionReceipt?,

    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,

    /** When the latest attempt crossed the mutation boundary (the start of the mutation window). */
    val submittedAtEpochMs: Long? = null,
    /** When the latest attempt (or reconciliation) resolved. */
    val resolvedAtEpochMs: Long? = null,
    val failure: ReviewerActionFailure? = null,
    /** Stable token saying how [status] was reached ([ReviewerActionResolution]). */
    val resolution: String? = null,
    /**
     * §28 — the action's verified backend semantics, frozen when the record is created so a later
     * capability change cannot re-shape an in-flight transaction. Never rewritten afterwards.
     */
    val frozenIdempotentReplay: Boolean = false,
    val frozenAuthoritativeReconciliation: Boolean = false,
    /**
     * Optimistic concurrency token. The ledger increments it on every successful durable write;
     * `0` is a freshly prepared record that has not transitioned yet.
     */
    val version: Long = 0L
) {
    init {
        require(sessionId.isNotBlank()) { "A reviewer action belongs to a study session" }
        require(createdAtEpochMs >= 0 && updatedAtEpochMs >= 0)
        require(attemptCount >= 0)
        require(cardRef.backendId == backendId) { "Card and action must name the same backend" }
        require(collectionRef == null || collectionRef.backendId == backendId)
        require(collectionRef?.collectionKey == null || cardRef.collectionKey == null ||
            collectionRef.collectionKey == cardRef.collectionKey)
    }

    /** Everything that is not [ReviewerActionStatus.APPLIED]: the startup recovery scan (§26). */
    val isUnresolved: Boolean get() = status != ReviewerActionStatus.APPLIED

    /** True while the record may still become a mutation: `PREPARED` or `SUBMITTING`. */
    val isInFlight: Boolean
        get() = status == ReviewerActionStatus.PREPARED || status == ReviewerActionStatus.SUBMITTING

    /** The read-only reconciliation request built from durable truth (works after process death). */
    fun toReconcileRequest(windowEndEpochMs: Long): ReconcileReviewerActionRequest =
        ReconcileReviewerActionRequest(
            actionId = actionId,
            cardRef = cardRef,
            action = action,
            submittedAtEpochMs = submittedAtEpochMs ?: createdAtEpochMs,
            windowEndEpochMs = windowEndEpochMs
        )
}

/**
 * GATE 13 §16 — what the coordinator is asked to do. It carries no [ReviewerActionId]: the
 * coordinator derives it (§17 step 2), which is what makes a duplicate request the *same* logical
 * action instead of a second one (VERIFICATION 13).
 */
data class ReviewerActionRequest(
    val sessionId: String,
    val turnId: ReviewTurnId,
    val cardRef: AnkiCardRef,
    val action: ReviewerAction,
    val backendId: AnkiBackendId = cardRef.backendId,
    val collectionRef: AnkiCollectionIdentity? = null,
    /** The audited semantics frozen with the record (§28); null = the backend declares its own. */
    val semantics: ReviewerActionSemantics? = null
) {
    init {
        require(sessionId.isNotBlank())
        require(backendId == cardRef.backendId) { "Card and request must name the same backend" }
        require(collectionRef == null || collectionRef.backendId == backendId)
        require(collectionRef?.collectionKey == null || cardRef.collectionKey == null ||
            collectionRef.collectionKey == cardRef.collectionKey)
    }

    /** §17 step 2 — the deterministic identity of this logical action. */
    val actionId: ReviewerActionId
        get() = ReviewerActionId.of(backendId, sessionId, turnId, action)

    /** The durable `PREPARED` record for this request (§17 step 3). */
    fun toRecord(now: Long, semantics: ReviewerActionSemantics? = this.semantics): ReviewerActionRecord =
        ReviewerActionRecord(
            actionId = actionId,
            sessionId = sessionId,
            turnId = turnId,
            backendId = backendId,
            collectionRef = collectionRef,
            cardRef = cardRef,
            action = action,
            status = ReviewerActionStatus.PREPARED,
            attemptCount = 0,
            backendReceipt = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            frozenIdempotentReplay = semantics?.supportsIdempotentReplay == true,
            frozenAuthoritativeReconciliation = semantics?.supportsAuthoritativeReconciliation == true
        )
}

/**
 * GATE 13 §27 — everything a backend needs to decide an unresolved action *read-only*, taken from
 * the durable record so it still works after process death, without the original session handle.
 * The mutation window is `[submittedAtEpochMs, windowEndEpochMs]`.
 */
data class ReconcileReviewerActionRequest(
    val actionId: ReviewerActionId,
    val cardRef: AnkiCardRef,
    val action: ReviewerAction,
    val submittedAtEpochMs: Long,
    val windowEndEpochMs: Long
) {
    init {
        require(submittedAtEpochMs >= 0 && windowEndEpochMs >= submittedAtEpochMs)
    }
}
