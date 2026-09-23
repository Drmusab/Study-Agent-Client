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

    /**
     * GATE 05 amendment — the backend *answered*, but the answer does not satisfy the pinned
     * contract (required identity column missing, every row unusable, wrong row shape). Distinct
     * from [QueryFailure] (the query itself failed) because the remediation differs: a malformed
     * response points at a contract/version mismatch, not at a transient fault. [detail] is a
     * small stable token (for example `deck_identity_column_missing`), never provider content
     * (GATE 05 §16/§38/§68).
     */
    data class MalformedResponse(
        val detail: String? = null,
        override val message: String = "The Anki backend returned data this app cannot interpret."
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

    /**
     * GATE 06 amendment — the request itself violates the domain contract, so it was rejected
     * before any backend was called (foreign backend reference, unmappable deck id, out-of-range
     * session limit). Distinct from [UnsupportedAction] (the backend was asked and cannot do it)
     * and from [DeckNotFound] (the backend was asked and the deck is genuinely gone): here nothing
     * was asked at all, and the caller has a bug or a stale reference. [detail] is a small stable
     * token (for example `deck_id_unmappable`), never provider text or content.
     */
    data class InvalidRequest(
        val detail: String? = null,
        override val message: String = "The request is not valid for this Anki backend."
    ) : AnkiError

    data class NoteNotFound(
        override val message: String = "The note no longer exists."
    ) : AnkiError

    data class SessionInvalid(
        override val message: String = "The review session is invalid or no longer owned by this backend."
    ) : AnkiError

    data class StaleTurn(
        override val message: String = "The rating does not belong to the active review turn."
    ) : AnkiError

    /**
     * GATE 07 amendment — the card content read back does not belong to the card the scheduler
     * selected, or the collection context moved on between scheduling and hydration. Distinct
     * from [CardNotFound] (the card is genuinely gone) and from [MalformedResponse] (the answer
     * cannot be interpreted): here the answer is perfectly readable and it is the *wrong card*
     * — which must never be attached to the turn (INV-ANKI-CARD-02, STEP 54). [detail] is a
     * small stable token (for example `card_identity_mismatch`, `collection_key_mismatch`),
     * never provider text or content.
     */
    data class StaleCardReference(
        val card: AnkiCardRef? = null,
        val detail: String? = null,
        override val message: String = "The loaded card does not match the scheduled card."
    ) : AnkiError

    data class MediaUnavailable(
        override val message: String = "Card media could not be loaded."
    ) : AnkiError

    /**
     * Anything not classifiable. An error category alone cannot prove whether a write happened.
     * Once dispatched, an unproven outcome MUST be CommitRatingResult.Ambiguous. Never infer
     * retry safety from this category (or from BackendUnavailable).
     */
    data class Unknown(
        val cause: String? = null,
        override val message: String = "An unknown Anki error occurred."
    ) : AnkiError
}
