package com.studyagent.client.core.anki

/**
 * GATE 01 contract — domain errors at the Anki boundary (§36).
 *
 * Backends translate their native failures (Cursor exceptions, HTTP bodies,
 * SQLite errors, protocol error frames) into these types inside the gateway.
 * Nothing above the gateway parses backend-native error text (INV-ANKI-06).
 */
sealed interface AnkiError {
    val message: String

    data class BackendUnavailable(
        override val message: String = "The selected Anki backend is currently unavailable."
    ) : AnkiError

    /**
     * GATE 02 amendment — the local AnkiDroid *provider* is absent/not
     * resolvable while the app itself is installed. Distinct from
     * [BackendUnavailable] (a healthy backend that is momentarily out of
     * reach) because the remediation differs: the user must re-open/update
     * AnkiDroid rather than retry a network-ish operation (GATE 02 §9/§21).
     */
    data class ProviderUnavailable(
        val detail: String? = null,
        override val message: String = "The AnkiDroid integration provider is not available."
    ) : AnkiError

    /**
     * GATE 02 amendment — the installed AnkiDroid exposes an API/provider
     * contract this build does not know how to use. [specVersion] is the
     * observed provider spec (`null` when it could not be read) and
     * [minimumSpec] the lowest spec this build supports (GATE 02 §15/§16/§53).
     */
    data class UnsupportedApi(
        val specVersion: Int?,
        val minimumSpec: Int,
        override val message: String = "This AnkiDroid installation exposes an unsupported API."
    ) : AnkiError

    /**
     * GATE 02 amendment — a read/probe against the backend failed in a way
     * that is neither permission, nor provider, nor collection state.
     * [causeCategory] is a small, stable, loggable token (for example
     * `timeout`, `illegal-state`, `illegal-argument`, `remote`, `unexpected`);
     * it never contains provider text, paths or content (GATE 02 §36/§68).
     */
    data class QueryFailure(
        val causeCategory: String,
        override val message: String = "The Anki backend could not answer the request."
    ) : AnkiError

    data class PermissionRequired(
        override val message: String = "Anki access permission has not been granted."
    ) : AnkiError

    data class CollectionUnavailable(
        override val message: String = "The Anki collection is not accessible right now."
    ) : AnkiError

    data class DeckNotFound(
        val deck: AnkiDeckRef? = null,
        override val message: String = "The requested deck no longer exists in this collection."
    ) : AnkiError

    data class CardNotFound(
        val card: AnkiCardRef? = null,
        override val message: String = "The card no longer exists in this collection."
    ) : AnkiError

    /**
     * The backend refused the commit because the collection state moved on
     * (card already answered elsewhere, session superseded). Never retried
     * blindly — reconcile first.
     */
    data class CommitConflict(
        val card: AnkiCardRef? = null,
        override val message: String = "The commit conflicts with the current collection state."
    ) : AnkiError

    data class UnsupportedAction(
        val action: String,
        override val message: String = "This Anki backend does not support the requested action."
    ) : AnkiError

    data class MediaUnavailable(
        override val message: String = "Card media could not be loaded."
    ) : AnkiError

    /**
     * Anything not classifiable. Treated as [CommitFailureClass.AMBIGUOUS] by
     * [asCommitFailureClass]: an unknown failure during a rating commit must
     * never be retried silently, because the scheduler may already have
     * applied it (§28, INV-ANKI-08).
     */
    data class Unknown(
        val cause: String? = null,
        override val message: String = "An unknown Anki error occurred."
    ) : AnkiError
}

/**
 * §28 — the three ways a rating commit can fail, plus success.
 *
 * - [REJECTED]: the backend deterministically refused *before* any scheduler
 *   mutation. Safe to surface; never auto-retry.
 * - [FAILED_SAFE_TO_RETRY]: the mutation provably never happened (the gateway
 *   detected the failure before issuing it). Retry with the same commit id.
 * - [AMBIGUOUS]: the mutation may or may not have been applied (no ACK, crash
 *   window, unknown failure). The review session must stop advancing and
 *   reconcile before any further card is served (INV-ANKI-08).
 */
enum class CommitFailureClass {
    REJECTED,
    FAILED_SAFE_TO_RETRY,
    AMBIGUOUS
}

/**
 * Conservative default classification for a failed rating commit.
 *
 * Backend implementations may *upgrade* a failure to [CommitFailureClass.AMBIGUOUS]
 * when they cannot prove where it happened; they must never downgrade an
 * ambiguous outcome to a silent retry.
 */
fun AnkiError.asCommitFailureClass(): CommitFailureClass = when (this) {
    is AnkiError.PermissionRequired,
    is AnkiError.DeckNotFound,
    is AnkiError.CardNotFound,
    is AnkiError.CommitConflict,
    is AnkiError.UnsupportedAction,
    // Api-shape mismatch is a deterministic refusal: the gateway refuses before
    // it issues anything, so the turn is rejected rather than retried blindly.
    is AnkiError.UnsupportedApi -> CommitFailureClass.REJECTED

    // The gateway knows the mutation was never issued (backend/collection was
    // already gone before the call). Determined-before-mutation is the only
    // failure that may be retried without reconciliation.
    is AnkiError.BackendUnavailable,
    is AnkiError.ProviderUnavailable,
    is AnkiError.CollectionUnavailable,
    is AnkiError.MediaUnavailable -> CommitFailureClass.FAILED_SAFE_TO_RETRY

    // A query/probe failure carries no proof about where it happened. During a
    // rating commit that means "the scheduler may already have applied it"
    // (INV-ANKI-08) — reconcile, never silently retry.
    is AnkiError.QueryFailure,
    is AnkiError.Unknown -> CommitFailureClass.AMBIGUOUS
}
