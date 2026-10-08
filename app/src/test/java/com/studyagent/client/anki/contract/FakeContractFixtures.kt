package com.studyagent.client.anki.contract

import com.studyagent.client.anki.fake.CardBrowserTestFixtures
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiCardType
import kotlinx.coroutines.CompletableDeferred

/**
 * Builds the contract-suite fixture around [FakeAnkiBackend]: the fake gets the deck-tree fixture
 * and the requested capability set, while the suite keeps its own independent description of the
 * same collection.
 */
object FakeContractFixtures {
    const val MISSING_DECK_ID = "deck-does-not-exist"

    fun create(capabilities: AnkiCardBrowserCapabilities): ContractFixture {
        val backendId = AnkiBackendId.Fake("contract")
        val tree = CardBrowserTestFixtures.deckTree(backendId)
        val collectionKey = tree.decks.first().ref.collectionKey
        val deckParents = tree.decks.associate { it.ref.deckId to it.parentRef?.deckId }
        val cards = tree.cards.mapIndexed { index, card ->
            ContractCard(
                cardRef = card.ref,
                noteId = requireNotNull(card.noteRef).noteId,
                deckId = requireNotNull(card.deckRef).deckId,
                tags = card.metadata.tags,
                flag = card.flag,
                type = browserType(card.metadata.queueState),
                suspended = card.metadata.queueState?.let { it == AnkiCardQueueState.SUSPENDED },
                buried = card.metadata.queueState?.let { it == AnkiCardQueueState.BURIED },
                reps = card.scheduling?.reps,
                lapses = card.scheduling?.lapses,
                due = card.scheduling?.dueEpochSeconds,
                created = card.metadata.noteCreatedEpochSeconds,
                modified = card.metadata.noteModifiedEpochSeconds,
                searchableText = listOfNotNull(card.questionText, card.answerText),
                defaultOrder = index
            )
        }
        val backendCapabilities = AnkiCapabilities(
            review = true,
            scheduledReview = true,
            deckListing = true,
            renderedCards = true,
            cardBrowser = capabilities
        )
        val backend = FakeAnkiBackend(
            id = backendId,
            decks = tree.decks,
            cards = tree.cards,
            initialCapabilities = backendCapabilities,
            initialAvailability = AnkiAvailability.Ready(backendCapabilities)
        )
        return ContractFixture(
            backend = backend,
            capabilities = capabilities,
            collectionKey = collectionKey,
            cards = cards,
            deckParents = deckParents,
            missingDeckId = MISSING_DECK_ID,
            emptyDeckId = CardBrowserTestFixtures.DeckTree.EMPTY,
            readOnlyProbe = FakeReadOnlyProbe(backend)
        )
    }

    private fun browserType(state: AnkiCardQueueState?): AnkiCardType? = when (state) {
        AnkiCardQueueState.NEW -> AnkiCardType.NEW
        AnkiCardQueueState.LEARNING -> AnkiCardType.LEARNING
        AnkiCardQueueState.REVIEW -> AnkiCardType.REVIEW
        AnkiCardQueueState.RELEARNING -> AnkiCardType.RELEARNING
        AnkiCardQueueState.UNKNOWN -> AnkiCardType.UNKNOWN
        AnkiCardQueueState.SUSPENDED, AnkiCardQueueState.BURIED, null -> null
    }

    private class FakeReadOnlyProbe(private val backend: FakeAnkiBackend) : ContractReadOnlyProbe {
        override val commitInvocations: Int get() = backend.commitInvocations
        override val backendEffectCount: Int get() = backend.backendEffectCount
        override val browseCalls: Int get() = backend.browseCalls

        override fun holdNextBrowse(): ContractReadOnlyProbe.Gate {
            val gate = CompletableDeferred<Unit>()
            backend.browseGate = gate
            return object : ContractReadOnlyProbe.Gate {
                override fun release() {
                    backend.browseGate = null
                    gate.complete(Unit)
                }
            }
        }
    }
}
