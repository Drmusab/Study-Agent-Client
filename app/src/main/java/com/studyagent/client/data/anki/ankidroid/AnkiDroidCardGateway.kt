package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardHydration
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.core.common.elapsedSince
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 07 — the card-content gateway (STEP 09/§10).
 *
 * One job, stated so it cannot drift: given a *domain* card identity, ask AnkiDroid's public
 * card endpoint for the card's content and translate the answer into an [AnkiRenderedCard]
 * **inside this boundary**. Callers receive domain values or a typed [AnkiError] — never a
 * `Cursor`, a `Uri`, a selection string, a projection array or a `ContentResolver`
 * (INV-ANKI-CARD-11).
 *
 * What this type does **not** do, on purpose:
 *
 * - it does not render, sanitize or "fix" card HTML (STEP 20/§21 — GATE 08 owns rendering);
 * - it does not expand Anki templates (`{{Field}}`, `{{cloze:…}}`, `{{FrontSide}}` — the backend
 *   is the template authority, INV-ANKI-CARD-08/09);
 * - it does not resolve media, open streams or touch the filesystem (GATE 09, STEP 38);
 * - it does not attach content to a review turn or mutate session state (STEP 52 — the caller
 *   composes via `AnkiCardHydration.attach`);
 * - it never writes: no rating, no bury, no suspend, no note edit (INV-ANKI-CARD-10) — and a
 *   source scan enforces it.
 *
 * Upper layers depend on `AnkiBackend.hydrateCardContent`, not on this type.
 */
interface AnkiDroidCardGateway {

    /**
     * The normalized content of exactly one card, or a typed failure.
     *
     * Lookup follows the reference shape (STEP 11): a stable card id uses `cards/<cardId>`;
     * `noteId + ordinal` (no card id — the GATE 06 schedule surface's shape) uses the public
     * `notes/<noteId>/cards/<ord>`. Never a question-text search, never a deck-name search.
     *
     * Failures: [AnkiError.CardNotFound] (gone — including the provider's documented not-found
     * signatures), [AnkiError.StaleCardReference] (the row does not confirm the requested
     * identity — STEP 54), [AnkiError.MalformedResponse] (contract/identity/content cannot be
     * trusted — STEP 15/§74), [AnkiError.InvalidRequest] (foreign backend ref — STEP 12), and
     * the ordinary provider/permission/collection family. Cancellation always propagates as
     * `CancellationException` (INV-ANKI-CARD-29).
     */
    suspend fun queryCard(authority: String, cardRef: AnkiCardRef): AnkiResult<AnkiRenderedCard>

    /** Content-free facts about the last completed query, for diagnostics. Never card content. */
    fun lastQueryDiagnostics(): AnkiDroidCardQueryDiagnostics
}

/**
 * Content-free card-hydration facts (STEP 87/§90/PART IV).
 *
 * Identifiers, counts and lengths only — never a question, an answer, raw HTML, a note field, a
 * template name or a filename (STEP 89). Lengths are the sanctioned metadata that makes payload
 * growth observable without exposing content (STEP 59).
 */
data class AnkiDroidCardQueryDiagnostics(
    val lastStatus: String,
    val lastCardId: String? = null,
    val lastNoteId: String? = null,
    val lastCardOrd: Int? = null,
    val questionHtmlLength: Int? = null,
    val answerHtmlLength: Int? = null,
    val questionTextLength: Int? = null,
    val answerTextLength: Int? = null,
    val pureAnswerAvailable: Boolean? = null,
    val mediaRefCount: Int? = null,
    val lastDegradations: List<String> = emptyList(),
    val lastQueryDurationMs: Long? = null,
    val lastMappingDurationMs: Long? = null,
    val lastErrorCategory: String? = null,
    val lastAtMs: Long? = null,
    /** How many provider queries this gateway has issued since construction (§40/§90). */
    val providerQueryCount: Long = 0L
) {
    companion object {
        val NONE = AnkiDroidCardQueryDiagnostics(lastStatus = "NONE")
    }
}

class DefaultAnkiDroidCardGateway(
    private val providerClient: AnkiDroidProviderClient,
    private val clock: AppClock = SystemAppClock,
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
) : AnkiDroidCardGateway {

    /**
     * Serialises provider traffic (STEP 79/§80). Two consumers asking for the same active card
     * converge instead of stampeding the provider; the entry is never held by anything but one
     * bounded projected query.
     */
    private val mutex = Mutex()
    private var diagnostics: AnkiDroidCardQueryDiagnostics = AnkiDroidCardQueryDiagnostics.NONE

    override fun lastQueryDiagnostics(): AnkiDroidCardQueryDiagnostics = diagnostics

    override suspend fun queryCard(
        authority: String,
        cardRef: AnkiCardRef
    ): AnkiResult<AnkiRenderedCard> {
        validate(cardRef)?.let { return AnkiResult.Failure(it) }
        val path = pathFor(cardRef)

        return try {
            mutex.withLock { performQuery(authority, cardRef, path) }
        } catch (cancellation: CancellationException) {
            // Cancellation is never a domain failure (INV-ANKI-CARD-29).
            throw cancellation
        } catch (throwable: Throwable) {
            fail(
                AnkiDroidErrorMapper.mapThrowable(throwable, "card", AnkiDroidOperationStage.PROVIDER_QUERY),
                cardRef
            )
        }
    }

    /** STEP 12 — foreign backend references fail before any provider traffic. */
    private fun validate(cardRef: AnkiCardRef): AnkiError? = when {
        cardRef.backendId != backendId ->
            AnkiError.InvalidRequest(detail = "card_ref_foreign_backend")
        else -> null
    }

    /**
     * STEP 11 — address the card the way the reference addresses it. A card id is the stable
     * lookup; `noteId + ordinal` is the public API's supported alternative. The ordinal path
     * cannot be built for an id-less note+ord-less ref, but `AnkiCardRef` makes such a ref
     * unconstructible, so this is total.
     */
    private fun pathFor(cardRef: AnkiCardRef): String = when {
        cardRef.cardId != null ->
            "${AnkiDroidApiContract.CARDS_PATH}/${cardRef.cardId}"
        else ->
            "${AnkiDroidApiContract.NOTES_PATH}/${cardRef.noteId}" +
                "/${AnkiDroidApiContract.NOTE_CARDS_PATH}/${cardRef.cardOrd}"
    }

    private suspend fun performQuery(
        authority: String,
        cardRef: AnkiCardRef,
        path: String
    ): AnkiResult<AnkiRenderedCard> {
        val startedAt = clock.nowMillis()
        AppLogger.i(
            TAG,
            "ANKI_CARD_HYDRATION_STARTED card=${cardRef.cardId ?: "-"} note=${cardRef.noteId ?: "-"} ord=${cardRef.cardOrd ?: "-"}"
        )

        val query = providerClient.safeQuery(
            authority = authority,
            path = path,
            projection = AnkiDroidApiContract.CARD_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = null,
            mapper = { row -> AnkiDroidCardMapper.mapCardRow(row, cardRef, backendId) }
        )
        val queryMs = clock.elapsedSince(startedAt)

        return when (query) {
            is ProviderQueryResult.Failure -> {
                val error = AnkiDroidErrorMapper.mapQueryResultFailure(query)
                // The documented not-found signatures mean "the card is gone", not "the query
                // broke" (STEP 44/§46) — never a blank card, never a retry loop.
                if (error is AnkiError.CardNotFound) {
                    fail(AnkiError.CardNotFound(card = cardRef), cardRef, queryMs, notFound = true)
                } else {
                    fail(error, cardRef, queryMs)
                }
            }

            // A singular lookup that answers with no row is answering "that card does not exist"
            // (STEP 44) — deliberately not folded into a successful empty card.
            is ProviderQueryResult.Empty ->
                fail(AnkiError.CardNotFound(card = cardRef), cardRef, queryMs, notFound = true)

            is ProviderQueryResult.Success -> foldRows(query.data, cardRef, queryMs)
        }
    }

    private fun foldRows(
        outcomes: List<AnkiDroidCardRowOutcome>,
        cardRef: AnkiCardRef,
        queryMs: Long
    ): AnkiResult<AnkiRenderedCard> {
        val mapStart = clock.nowMillis()
        // Exactly one row is expected from a singular URI. If the provider sends more, the first
        // (and only addressable) row is the answer; a second row is never presented as content.
        for (outcome in outcomes) {
            when (outcome) {
                is AnkiDroidCardRowOutcome.Malformed ->
                    return fail(
                        AnkiError.MalformedResponse(detail = outcome.problem.token),
                        cardRef, queryMs
                    )

                is AnkiDroidCardRowOutcome.Valid -> {
                    // STEP 54 / GATE 07 §15 — the row must confirm the identity that was asked
                    // for before its content may go anywhere (INV-ANKI-CARD-02). Enrichment (a
                    // card id the request lacked) is fine; an unconfirmed claim is not.
                    if (!AnkiCardHydration.identityMatches(cardRef, outcome.card.ref)) {
                        return fail(
                            AnkiError.StaleCardReference(card = cardRef, detail = "card_identity_mismatch"),
                            cardRef, queryMs
                        )
                    }
                    val mappingMs = clock.elapsedSince(mapStart)
                    recordCard(outcome.card, cardRef, queryMs, mappingMs)
                    return AnkiResult.Success(outcome.card)
                }
            }
        }
        return fail(AnkiError.MalformedResponse(detail = "card_no_usable_row"), cardRef, queryMs)
    }

    private fun recordCard(
        card: AnkiRenderedCard,
        cardRef: AnkiCardRef,
        queryMs: Long,
        mappingMs: Long
    ) {
        diagnostics = AnkiDroidCardQueryDiagnostics(
            lastStatus = "HYDRATED",
            lastCardId = card.ref.cardId,
            lastNoteId = card.ref.noteId,
            lastCardOrd = card.ref.cardOrd,
            questionHtmlLength = card.questionHtml?.length,
            answerHtmlLength = card.answerHtml?.length,
            questionTextLength = card.questionText?.length,
            answerTextLength = card.answerText?.length,
            pureAnswerAvailable = card.pureAnswerText != null,
            mediaRefCount = card.media.size,
            lastDegradations = card.degradations,
            lastQueryDurationMs = queryMs,
            lastMappingDurationMs = mappingMs,
            lastErrorCategory = null,
            lastAtMs = clock.nowMillis(),
            providerQueryCount = diagnostics.providerQueryCount + 1
        )
        // STEP 88 — metadata only: lengths and availability, never content (STEP 89).
        AppLogger.i(
            TAG,
            "ANKI_CARD_HYDRATION_SUCCEEDED qHtml=${card.questionHtml?.length ?: -1} " +
                "aHtml=${card.answerHtml?.length ?: -1} qText=${card.questionText?.length ?: -1} " +
                "aText=${card.answerText?.length ?: -1} pure=${card.pureAnswerText != null} " +
                "media=${card.media.size} degraded=${card.degradations.size} durationMs=$queryMs"
        )
        if (card.degradations.isNotEmpty()) {
            AppLogger.i(TAG, "ANKI_CARD_CONTENT_DEGRADED tokens=${card.degradations.joinToString(",")}")
        }
    }

    private fun <T> fail(
        error: AnkiError,
        cardRef: AnkiCardRef,
        queryMs: Long? = null,
        notFound: Boolean = false
    ): AnkiResult<T> {
        diagnostics = AnkiDroidCardQueryDiagnostics(
            lastStatus = if (notFound) "NOT_FOUND" else "FAILED",
            lastCardId = cardRef.cardId,
            lastNoteId = cardRef.noteId,
            lastCardOrd = cardRef.cardOrd,
            lastQueryDurationMs = queryMs,
            lastErrorCategory = error::class.simpleName,
            lastAtMs = clock.nowMillis(),
            providerQueryCount = diagnostics.providerQueryCount + 1
        )
        AppLogger.w(
            TAG,
            if (notFound) "ANKI_CARD_HYDRATION_FAILED reason=not_found"
            else "ANKI_CARD_HYDRATION_FAILED error=${error::class.simpleName}"
        )
        return AnkiResult.Failure(error)
    }

    private companion object {
        const val TAG = "AnkiDroidCardGateway"
    }
}

/**
 * Scripted card gateway for JVM tests: no Android, no provider, no rendering.
 *
 * [results] is consumed in order; the last entry repeats. [throwable] short-circuits every call,
 * which is how cancellation propagation is exercised (INV-ANKI-CARD-29).
 */
class FakeAnkiDroidCardGateway(
    var results: MutableList<AnkiResult<AnkiRenderedCard>> = mutableListOf(),
    var throwable: Throwable? = null,
    var diagnostics: AnkiDroidCardQueryDiagnostics = AnkiDroidCardQueryDiagnostics.NONE
) : AnkiDroidCardGateway {

    var queryCalls: Int = 0
        private set
    val requestedCardIds: MutableList<String?> = mutableListOf()
    val requestedPaths: MutableList<String> = mutableListOf()
    val queriedAuthorities: MutableList<String> = mutableListOf()

    override fun lastQueryDiagnostics(): AnkiDroidCardQueryDiagnostics = diagnostics

    override suspend fun queryCard(
        authority: String,
        cardRef: AnkiCardRef
    ): AnkiResult<AnkiRenderedCard> {
        queryCalls++
        queriedAuthorities.add(authority)
        requestedCardIds.add(cardRef.cardId)
        requestedPaths.add(
            if (cardRef.cardId != null) {
                "${AnkiDroidApiContract.CARDS_PATH}/${cardRef.cardId}"
            } else {
                "${AnkiDroidApiContract.NOTES_PATH}/${cardRef.noteId}" +
                    "/${AnkiDroidApiContract.NOTE_CARDS_PATH}/${cardRef.cardOrd}"
            }
        )
        throwable?.let { throw it }
        if (results.isEmpty()) {
            return AnkiResult.Failure(AnkiError.CardNotFound(card = cardRef))
        }
        val result = results.removeAt(0)
        if (results.isEmpty()) results.add(result)
        return result
    }
}
