package com.studyagent.client.core.study

import com.studyagent.client.core.anki.*
import kotlinx.coroutines.CancellationException

/** Performs read effects in the caller's owned coroutine; never writes machine state. */
class AnkiStudyEffectExecutor(private val registry: AnkiBackendRegistry) {
    suspend fun execute(effect: AnkiStudyEffect): AnkiStudyEvent? {
        if (effect is AnkiStudyEffect.CancelReads) return null
        return try {
            when (effect) {
                is AnkiStudyEffect.Begin -> AnkiStudyEvent.Begun(effect.epoch, begin(effect))
                is AnkiStudyEffect.Next -> AnkiStudyEvent.Scheduled(
                    effect.epoch,
                    registry.find(effect.session.context.backendId)?.nextCard(effect.session)
                        ?: NextCardResult.BackendUnavailable(AnkiError.BackendUnavailable())
                )
                is AnkiStudyEffect.Hydrate -> AnkiStudyEvent.Hydrated(
                    effect.epoch, effect.turn.turnId,
                    registry.find(effect.turn.backendId)?.hydrateCardContent(effect.turn.cardRef)
                        ?: AnkiResult.Failure(AnkiError.BackendUnavailable())
                )
                AnkiStudyEffect.CancelReads -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No exception text (provider content may be sensitive), and never retry here.
            val failure = AnkiError.QueryFailure("unexpected")
            when (effect) {
                is AnkiStudyEffect.Begin -> AnkiStudyEvent.Begun(effect.epoch, AnkiResult.Failure(failure))
                is AnkiStudyEffect.Next -> AnkiStudyEvent.Scheduled(effect.epoch, NextCardResult.Failure(failure))
                is AnkiStudyEffect.Hydrate -> AnkiStudyEvent.Hydrated(effect.epoch, effect.turn.turnId, AnkiResult.Failure(failure))
                AnkiStudyEffect.CancelReads -> null
            }
        }
    }

    private suspend fun begin(effect: AnkiStudyEffect.Begin): AnkiResult<AnkiReviewSession> {
        val request = effect.request
        // Only startup probes candidates. All subsequent reads use the locked logical identity.
        registry.ids.filter(request.preference::accepts).forEach { registry.find(it)?.refreshAvailability() }
        val resolution = AnkiBackendSelector(registry).resolve(request.preference)
        val id = when (resolution) {
            is AnkiBackendSelector.Resolution.Resolved -> resolution.backendId
            is AnkiBackendSelector.Resolution.Unavailable -> return AnkiResult.Failure(resolution.error)
            is AnkiBackendSelector.Resolution.Ambiguous -> return AnkiResult.Failure(AnkiError.InvalidRequest("ambiguous_backend"))
        }
        if (request.deck.backendId != id) return AnkiResult.Failure(AnkiError.InvalidRequest("foreign_deck"))
        val backend = registry.find(id) ?: return AnkiResult.Failure(AnkiError.BackendUnavailable())
        // Validate before binding, even for implementations whose beginReview is less strict.
        when (val decks = backend.getDecks()) {
            is AnkiResult.Failure -> return decks
            is AnkiResult.Success -> if (decks.value.none { it.ref == request.deck }) {
                return AnkiResult.Failure(AnkiError.DeckNotFound(request.deck))
            }
        }
        val context = AnkiSessionContext(
            id, request.deck.collectionKey?.let { AnkiCollectionIdentity(id, it) }, request.deck,
            effect.startedAtMs, backend.capabilities.value, request.studySessionId
        )
        return backend.beginReview(BeginReviewRequest(context))
    }
}
