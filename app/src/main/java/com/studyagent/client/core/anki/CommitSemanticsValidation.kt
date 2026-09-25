package com.studyagent.client.core.anki

import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.AgentCapability

/**
 * Capability metadata must not claim a guarantee the backend cannot prove.
 *
 * [END_TO_END_EXACTLY_ONCE][CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE] requires idempotent
 * replay, authoritative reconciliation, and a backend-issued receipt. AnkiDroid's public provider
 * has none of those, so it cannot advertise anything stronger than
 * [CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED]. A failed validation is a configuration error:
 * callers must fail closed (treat the backend as [CommitSemantics.UNVERIFIED]) rather than retry.
 */
sealed interface CommitSemanticsValidation {
    data object Valid : CommitSemanticsValidation
    data class Invalid(val reasons: List<String>) : CommitSemanticsValidation {
        init { require(reasons.isNotEmpty()) }
    }
}

fun validateCommitSemantics(
    semantics: CommitSemantics,
    backendId: AnkiBackendId? = null
): CommitSemanticsValidation {
    val reasons = mutableListOf<String>()
    if (semantics.supportsIdempotentReplay &&
        semantics.guaranteeLevel != CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED &&
        semantics.guaranteeLevel != CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE) {
        reasons += "idempotent_replay_overclaim"
    }
    if (semantics.guaranteeLevel == CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED &&
        !semantics.supportsIdempotentReplay) {
        reasons += "idempotent_replay_flag_missing"
    }
    if (semantics.guaranteeLevel == CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE) {
        if (!semantics.supportsIdempotentReplay) reasons += "exactly_once_requires_idempotent_replay"
        if (!semantics.supportsAuthoritativeReconciliation) reasons += "exactly_once_requires_authoritative_reconciliation"
        if (semantics.commitReceiptKind != CommitReceiptKind.BACKEND_TRANSACTION_ID) {
            reasons += "exactly_once_requires_backend_receipt"
        }
    }
    if (semantics.commitReceiptKind == CommitReceiptKind.BACKEND_TRANSACTION_ID &&
        !semantics.supportsAuthoritativeReconciliation) {
        reasons += "receipt_without_reconciliation"
    }
    if (backendId == AnkiBackendId.AnkiDroidLocal && semantics != CommitSemantics.ANKIDROID) {
        reasons += "ankidroid_must_stay_at_most_once_fail_closed"
    }
    if (backendId == AnkiBackendId.AnkiDroidLocal &&
        (semantics.guaranteeLevel == CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE ||
            semantics.supportsIdempotentReplay || semantics.supportsAuthoritativeReconciliation)) {
        reasons += "ankidroid_cannot_advertise_end_to_end_exactly_once"
    }
    return if (reasons.isEmpty()) CommitSemanticsValidation.Valid
    else CommitSemanticsValidation.Invalid(reasons.distinct())
}

/**
 * Invalid or overclaimed metadata becomes [CommitSemantics.UNVERIFIED] (no replay, no proof).
 * A valid value is returned unchanged. Never upgrades a backend.
 */
fun CommitSemantics.enforced(backendId: AnkiBackendId? = null): CommitSemantics =
    if (validateCommitSemantics(this, backendId) is CommitSemanticsValidation.Valid) this
    else CommitSemantics.UNVERIFIED

/**
 * PC Agent capabilities are protocol facts, not a version-string guess.
 *
 * Advertising both commit capabilities is still not end-to-end exactly-once: this client has not
 * proven a progress guarantee across the transport and Desktop Anki crash windows. Missing
 * capabilities — including a legacy agent that sends none — downgrade to fail-closed.
 */
fun commitSemanticsFromAgent(capabilities: AgentCapabilities?): CommitSemantics {
    if (capabilities == null || !capabilities.isResolved || capabilities.isLegacyV1) return CommitSemantics.UNVERIFIED
    val idempotent = capabilities.supports(AgentCapability.REVIEW_COMMIT_IDEMPOTENCY)
    val reconcile = capabilities.supports(AgentCapability.COMMIT_RECONCILIATION)
    val semantics = when {
        idempotent && reconcile -> CommitSemantics(
            CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED,
            supportsIdempotentReplay = true,
            supportsAuthoritativeReconciliation = true,
            commitReceiptKind = CommitReceiptKind.BACKEND_TRANSACTION_ID
        )
        idempotent -> CommitSemantics(
            CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED,
            supportsIdempotentReplay = true,
            supportsAuthoritativeReconciliation = false,
            commitReceiptKind = CommitReceiptKind.NONE
        )
        else -> CommitSemantics.UNVERIFIED.copy(guaranteeLevel = CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED)
    }
    return semantics.enforced()
}

/**
 * Transport replay of a rating mutation. Disabled unless the frozen semantics of *this*
 * transaction advertise idempotent replay. A capability refresh after the mutation started must
 * not flip this on. AnkiDroid never qualifies.
 */
object PcRatingReplayPolicy {
    fun automaticReplayAllowed(semantics: CommitSemantics?): Boolean {
        val frozen = semantics?.enforced() ?: return false
        return frozen.supportsIdempotentReplay &&
            frozen.guaranteeLevel == CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED
    }

    /**
     * Stable logical id for one PC review turn. Not derived from the rating, not a secret, and
     * not a backend receipt. The WebSocket `message_id` remains a delivery key only.
     */
    fun logicalCommitId(sessionId: String, turnId: String): String {
        require(sessionId.isNotBlank() && turnId.isNotBlank())
        return "pc-review-commit:${sessionId.length}:$sessionId:${turnId.length}:$turnId"
    }
}
