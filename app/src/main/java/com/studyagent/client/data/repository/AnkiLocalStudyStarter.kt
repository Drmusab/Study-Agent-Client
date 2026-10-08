package com.studyagent.client.data.repository

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendSelector
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.ReviewerActionCapabilities
import com.studyagent.client.core.anki.reviewerActionCapabilities
import com.studyagent.client.core.study.AnkiStudyRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * GATE 13 §17/§28 (STEP 32.2) — the app's **session-start site** for a backend-local Anki review.
 *
 * ```text
 * resolve the one ready backend   (AnkiBackendSelector — never list order, never a fake)
 *   ↓ read its live capabilities  (read-only refresh)
 *   ↓ resolve the deck the user named against the *live* collection
 *   ↓ freeze the reviewer-action capability set   ← the one call that must not have a second home
 *   ↓ hand one AnkiStudyRequest to the state machine
 * ```
 *
 * Freezing matters because a session is a long-lived decision: if the capability set were re-read
 * per turn, a later preference change or a transient provider outage could silently enable or
 * disable a mutation mid-review, and an in-flight action's recovery semantics would change under it
 * (INV-13-16). The frozen value is derived from the backend's single capability source —
 * [reviewerActionCapabilities] — which pairs each capability with the semantics that backend audited
 * for it (§28/§29); nothing here invents an action the backend has not audited.
 *
 * A start is a *read* plus one dispatch: no card is scheduled, no rating and no action is prepared,
 * and the machine remains interaction truth (INV-13-17). The class is deliberately independent of
 * Android and Compose so the freezing rule is testable with a fake backend.
 */
class AnkiLocalStudyStarter(
    private val registry: AnkiBackendRegistry,
    private val selector: AnkiBackendSelector,
    private val scope: CoroutineScope,
    /** The machine dispatch. A lambda so this class never depends on the repository implementation. */
    private val dispatch: (AnkiStudyRequest) -> Unit,
    private val studySessionIdFactory: () -> String = { UUID.randomUUID().toString() }
) {

    /** Everything a caller may choose about the local session; the rest is the request's own default. */
    data class Options(
        /**
         * The on-device backend only. `AUTO` is deliberately *not* the default here: `AUTO` would fall
         * back to a PC agent, and a PC agent is not "this phone" — a caller that chooses this starter
         * has already decided that the session must run against the device's own collection.
         */
        val preference: AnkiBackendMode = LOCAL_PREFERENCE,
        /** `null` = the deck the backend itself has selected, when it exposes one. */
        val deckName: String? = null,
        /** Stable ID from Library navigation. Preferred over a mutable/display-only name. */
        val deckId: String? = null,
        val speakQuestion: Boolean = true,
        val evaluateAnswers: Boolean = false,
        val speakFeedback: Boolean = true,
        val speakAnswer: Boolean = false
    ) {
        init {
            require(deckName == null || deckId == null) { "Choose a deck by stable ID or legacy name, not both" }
            require(deckId == null || deckId.isNotBlank())
        }
    }

    sealed interface Result {

        /**
         * The machine accepted a fully formed request. [reviewerActions] is the *frozen* set, echoed
         * so diagnostics and tests can assert on exactly what the session was started with.
         */
        data class Started(
            val studySessionId: String,
            val backendId: AnkiBackendId,
            val deck: AnkiDeckRef,
            val reviewerActions: ReviewerActionCapabilities
        ) : Result

        /**
         * Nothing was started and nothing was dispatched. [reason] is a stable, content-free token;
         * [error] carries the backend's typed reason when there is one.
         */
        data class Refused(val reason: String, val error: AnkiError? = null) : Result
    }

    /**
     * True while the required local AnkiDroid backend is resolved and ready for review.
     *
     * It is a *pre*-flight signal for the UI's primary action, not a promise: the same resolution runs
     * again inside [start], so a backend that went away between the tap and the freeze is refused
     * honestly instead of half-starting a session.
     */
    val localStudyBackendId: StateFlow<AnkiBackendId?> = localStudyBackendIdFlow()

    /** Pre-flight convenience for existing dashboard projections. */
    val localStudyReady: StateFlow<Boolean> = localStudyBackendId
        .map { it != null }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = localStudyBackendId.value != null
        )

    /**
     * Start a local Anki review, or refuse.
     *
     * The deck is validated against the live collection *before* the request exists, so a stale or
     * misspelled deck fails here rather than mid-session (§45/§46 of the Anki contract).
     */
    suspend fun start(options: Options = Options()): Result {
        val backendId = when (val resolution = selector.resolve(options.preference)) {
            is AnkiBackendSelector.Resolution.Resolved -> resolution.backendId
            is AnkiBackendSelector.Resolution.Unavailable ->
                return Result.Refused(REASON_NO_READY_BACKEND, resolution.error)
            is AnkiBackendSelector.Resolution.Ambiguous ->
                return Result.Refused(REASON_AMBIGUOUS_BACKEND, null)
        }
        val backend = registry.find(backendId)
            ?: return Result.Refused(REASON_BACKEND_NOT_REGISTERED, null)

        // A read-only freshness check: the selector reads availability *values*, so a stale
        // "unavailable" from a previous foreground must not block a session the user just asked for.
        backend.refreshAvailability()

        // Fail closed if the refresh changed the answer: resolution is re-run, never remembered.
        if (selector.resolve(options.preference) !is AnkiBackendSelector.Resolution.Resolved) {
            return Result.Refused(REASON_BACKEND_NOT_READY, null)
        }

        val deck = resolveDeck(backend, options.deckName, options.deckId)
            ?: return Result.Refused(REASON_DECK_NOT_FOUND, null)

        // STEP 32.2 — the freeze. Session capabilities come from the backend's own audited contract at
        // this instant and are immutable for the session's lifetime.
        val reviewerActions = backend.reviewerActionCapabilities()

        val studySessionId = studySessionIdFactory()
        dispatch(
            AnkiStudyRequest(
                studySessionId = studySessionId,
                preference = options.preference,
                deck = deck,
                speakQuestion = options.speakQuestion,
                evaluateAnswers = options.evaluateAnswers,
                speakFeedback = options.speakFeedback,
                speakAnswer = options.speakAnswer,
                reviewerActions = reviewerActions
            )
        )
        return Result.Started(
            studySessionId = studySessionId,
            backendId = backendId,
            deck = deck,
            reviewerActions = reviewerActions
        )
    }

    /** `null` = the user's deck name is not in this backend's collection (or it said nothing). */
    private suspend fun resolveDeck(
        backend: AnkiBackend,
        deckName: String?,
        deckId: String?
    ): AnkiDeckRef? {
        if (deckName == null && deckId == null) {
            // "The deck AnkiDroid has selected", when the contract exposes that notion; otherwise the
            // backend's own current deck is not ours to guess, and the caller must name one.
            return when (val selected = backend.getSelectedDeck()) {
                is AnkiResult.Success -> selected.value
                is AnkiResult.Failure -> null
            }
        }
        return when (val decks = backend.getDecks()) {
            is AnkiResult.Success -> decks.value.firstOrNull { deck ->
                if (deckId != null) {
                    deck.ref.backendId == backend.id && deck.ref.deckId == deckId
                } else {
                    deck.ref.backendId == backend.id && deck.name == deckName
                }
            }?.ref
            is AnkiResult.Failure -> null
        }
    }

    private fun localStudyBackendIdFlow(): StateFlow<AnkiBackendId?> {
        val availability = registry.ids.mapNotNull { registry.find(it)?.availability }
        if (availability.isEmpty()) return MutableStateFlow<AnkiBackendId?>(null)
        return combine(availability) { resolveReadyBackendId() }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = resolveReadyBackendId()
            )
    }

    private fun resolveReadyBackendId(): AnkiBackendId? =
        (selector.resolve(LOCAL_PREFERENCE) as? AnkiBackendSelector.Resolution.Resolved)?.backendId

    companion object {
        /** "Study on this phone": the device's own Anki collection, never a remote agent. */
        val LOCAL_PREFERENCE: AnkiBackendMode = AnkiBackendMode.ANKIDROID_LOCAL

        /** Content-free refusal token the UI/diagnostics may show. */
        const val REASON_NO_READY_BACKEND = "no_ready_backend"
        const val REASON_AMBIGUOUS_BACKEND = "ambiguous_backend"
        const val REASON_BACKEND_NOT_REGISTERED = "backend_not_registered"
        const val REASON_BACKEND_NOT_READY = "backend_not_ready"
        const val REASON_DECK_NOT_FOUND = "deck_not_found"
    }
}
