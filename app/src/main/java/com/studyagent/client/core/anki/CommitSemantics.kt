package com.studyagent.client.core.anki

import com.studyagent.client.core.models.Rating

/** These are claims about scheduler *effects*, not the number of requests delivered. */
enum class CommitGuaranteeLevel {
    /** Local input deduplication only. No safe replay or crash-window claim. */
    LOCAL_DEDUP_ONLY,
    /** Durable intent, one serialized delivery per attempt, and no replay after uncertainty. */
    AT_MOST_ONCE_FAIL_CLOSED,
    /** The backend durably deduplicates repeated delivery of one ReviewCommitId. */
    IDEMPOTENT_REPLAY_SUPPORTED,
    /** Both eventual execution and at-most-one effect through every documented failure window. */
    END_TO_END_EXACTLY_ONCE
}

enum class CommitReceiptKind { NONE, BACKEND_TRANSACTION_ID }

/** Capability metadata, not a switch that automatically enables transport retries. */
data class CommitSemantics(
    val guaranteeLevel: CommitGuaranteeLevel,
    val supportsIdempotentReplay: Boolean,
    val supportsAuthoritativeReconciliation: Boolean,
    val commitReceiptKind: CommitReceiptKind
) {
    init {
        require(!supportsIdempotentReplay || guaranteeLevel == CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED ||
            guaranteeLevel == CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE)
        require(commitReceiptKind != CommitReceiptKind.BACKEND_TRANSACTION_ID || supportsAuthoritativeReconciliation)
    }

    companion object {
        val UNVERIFIED = CommitSemantics(CommitGuaranteeLevel.LOCAL_DEDUP_ONLY, false, false, CommitReceiptKind.NONE)
        val ANKIDROID = CommitSemantics(CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED, false, false, CommitReceiptKind.NONE)
    }
}

/** A backend-issued ID is optional; a locally generated value is never a backend receipt. */
data class CommitReceipt(
    val backendId: AnkiBackendId,
    val backendReceiptId: String?,
    val committedRating: Rating,
    val scheduling: AnkiSchedulingInfo? = null
) {
    init { require(backendReceiptId == null || backendReceiptId.isNotBlank()) }
}
