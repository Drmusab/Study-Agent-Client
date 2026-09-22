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

    data class NoteNotFound(
        override val message: String = "The note no longer exists."
    ) : AnkiError

    data class SessionInvalid(
        override val message: String = "The review session is invalid or no longer owned by this backend."
    ) : AnkiError

    data class StaleTurn(
        override val message: String = "The rating does not belong to the active review turn."
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
