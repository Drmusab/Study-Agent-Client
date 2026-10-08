package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardMetadata
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiSchedulingInfo

/**
 * Deterministic Card Browser fixture set; large cases live only in the JVM test source set.
 *
 * Everything a backend reports about these cards is *fixture data*, so a contract test can assert
 * ordering, filtering and totals against an independently known expectation instead of against the
 * code under test.
 */
object CardBrowserTestFixtures {

    // ------------------------------------------------------------------ large collection fixture

    fun largeCardSet(
        backendId: AnkiBackendId,
        deckRefs: List<AnkiDeckRef>,
        count: Int = 1_200
    ): List<AnkiRenderedCard> {
        require(count >= 1_000) { "The performance fixture must exercise at least 1,000 cards" }
        require(deckRefs.isNotEmpty() && deckRefs.all { it.backendId == backendId })

        return List(count) { index ->
            val deck = deckRefs[index % deckRefs.size]
            val question = when (index % 8) {
                0 -> "ما وظيفة العصب المبهم؟ Vagus nerve"
                1 -> "ECG: what does QRS duration measure?"
                2 -> "Duplicate-looking question: identify the structure"
                3 -> "Duplicate-looking question: identify the structure"
                4 -> "Mixed language: التهاب عضلة القلب myocarditis"
                5 -> "What is the half-life of drug $index?"
                6 -> "Numbers ١٢٣ / 123 and abbreviation HTN"
                else -> "Card ${index + 1}: ${if (index % 2 == 0) "question" else "prompt"}"
            }
            val queueState = when (index % 10) {
                0 -> AnkiCardQueueState.SUSPENDED
                1 -> AnkiCardQueueState.BURIED
                2 -> AnkiCardQueueState.NEW
                3 -> AnkiCardQueueState.LEARNING
                4 -> AnkiCardQueueState.RELEARNING
                5 -> AnkiCardQueueState.UNKNOWN
                else -> AnkiCardQueueState.REVIEW
            }
            val tags = when (index % 5) {
                0 -> setOf("cardiology", "طب")
                1 -> setOf("ecg", "review")
                2 -> setOf("mixed-language", "عربي")
                3 -> setOf("pharmacology")
                else -> emptySet()
            }
            AnkiRenderedCard(
                ref = AnkiCardRef(
                    backendId = backendId,
                    cardId = "card-$index",
                    noteId = "note-${index / 2}",
                    cardOrd = index % 2,
                    collectionKey = deck.collectionKey
                ),
                questionHtml = null,
                answerHtml = null,
                questionText = question,
                answerText = when (index % 3) {
                    0 -> "الجواب: parasympathetic; heart rate decreases."
                    1 -> "Answer $index with 120 ms and 2.5 mg."
                    else -> null
                },
                pureAnswerText = null,
                media = emptyList<AnkiMediaRef>(),
                scheduling = AnkiSchedulingInfo(
                    reps = index % 100,
                    lapses = index % 13,
                    dueEpochSeconds = index.toLong() * 60L
                ),
                metadata = AnkiCardMetadata(
                    deckName = "Deck ${deck.deckId}",
                    tags = tags,
                    queueState = queueState,
                    noteCreatedEpochSeconds = 1_700_000_000L + index,
                    noteModifiedEpochSeconds = 1_800_000_000L + index
                ),
                noteRef = AnkiNoteRef(backendId, "note-${index / 2}", deck.collectionKey),
                deckRef = deck,
                flag = AnkiFlag.entries.getOrNull(index % 8)
            )
        }
    }

    // ----------------------------------------------------------------------- deck tree fixture

    /**
     * A small authoritative deck hierarchy with deliberately ambiguous names, tags that must not
     * leak into text search, and sort keys with duplicates so tie-breaking is observable.
     *
     * ```text
     * Medicine
     * ├── Medicine::Cardiology
     * └── Medicine::Neurology
     *     └── Medicine::Neurology::Vascular
     * Zephyr Deck            (deck-name-only token; never searchable text)
     * ```
     */
    object DeckTree {
        const val MEDICINE = "deck-medicine"
        const val CARDIOLOGY = "deck-cardio"
        const val NEUROLOGY = "deck-neuro"
        const val NEURO_VASCULAR = "deck-neuro-vascular"
        const val ZEPHYR = "deck-zephyr"

        /** A real deck that holds no cards at all — distinct from a missing deck (§6 vs §37). */
        const val EMPTY = "deck-empty"

        /** A token that appears in deck names and tags but in no searchable card text. */
        const val NON_SEARCHABLE_TOKEN = "zephyr"

        /** Shared question text; identical rows with distinct identities (§39 duplicate rows). */
        const val DUPLICATE_QUESTION = "Duplicate-looking question: identify the structure"

        /** A literal two-space phrase; user text is never whitespace-collapsed (§9). */
        const val DOUBLE_SPACE_QUESTION = "Spacing  test: keep  internal  runs"
    }

    data class DeckTreeFixture(
        val decks: List<AnkiDeck>,
        val cards: List<AnkiRenderedCard>
    ) {
        fun deck(deckId: String): AnkiDeck = decks.first { it.ref.deckId == deckId }
        fun deckRef(deckId: String): AnkiDeckRef = deck(deckId).ref
        fun cardsIn(deckId: String): List<AnkiRenderedCard> = cards.filter { it.deckRef?.deckId == deckId }
        /** Every card in the deck subtree, computed from the fixture definition, not the backend. */
        fun cardsInSubtree(deckId: String): List<AnkiRenderedCard> {
            val ids = LinkedHashSet<String>()
            val pending = ArrayDeque(listOf(deckId))
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!ids.add(current)) continue
                decks.filter { it.parentRef?.deckId == current }.forEach { pending += it.ref.deckId }
            }
            return cards.filter { it.deckRef?.deckId in ids }
        }
    }

    fun deckTree(backendId: AnkiBackendId, collectionKey: String = "collection-tree"): DeckTreeFixture {
        fun ref(deckId: String) = AnkiDeckRef(backendId, deckId, collectionKey)
        val decks = listOf(
            AnkiDeck(ref(DeckTree.MEDICINE), "Medicine"),
            AnkiDeck(ref(DeckTree.CARDIOLOGY), "Medicine::Cardiology", parentRef = ref(DeckTree.MEDICINE)),
            AnkiDeck(ref(DeckTree.NEUROLOGY), "Medicine::Neurology", parentRef = ref(DeckTree.MEDICINE)),
            AnkiDeck(
                ref(DeckTree.NEURO_VASCULAR),
                "Medicine::Neurology::Vascular",
                parentRef = ref(DeckTree.NEUROLOGY)
            ),
            AnkiDeck(ref(DeckTree.ZEPHYR), "Zephyr Deck"),
            AnkiDeck(ref(DeckTree.EMPTY), "Empty Deck")
        )

        /** question, answer, tags, queue state, flag, reps, lapses, due offset, created offset. */
        data class Row(
            val deckId: String,
            val cardId: String,
            val question: String,
            val answer: String?,
            val tags: Set<String>,
            val queue: AnkiCardQueueState,
            val flag: AnkiFlag,
            val reps: Int?,
            val lapses: Int,
            val due: Long?,
            val created: Long?
        )

        val rows = listOf(
            Row(DeckTree.MEDICINE, "m-1", "Medicine parent deck card on beta blockers", "Answer: bisoprolol",
                setOf("cardiology", "high-yield"), AnkiCardQueueState.REVIEW, AnkiFlag.RED, 12, 3, 500L, 1_000L),
            Row(DeckTree.MEDICINE, "m-2", "General prompt: define homeostasis", "Answer: steady state",
                setOf("physiology"), AnkiCardQueueState.NEW, AnkiFlag.NONE, 0, 0, 100L, 2_000L),
            Row(DeckTree.CARDIOLOGY, "c-1", "ECG: subarachnoid hemorrhage pattern is T-wave inversion", "Answer: cerebral T waves",
                setOf("cardiology", "high-yield"), AnkiCardQueueState.REVIEW, AnkiFlag.RED, 30, 9, 200L, 3_000L),
            Row(DeckTree.CARDIOLOGY, "c-2", "Heart failure: reduced ejection fraction management", "Answer: Subarachnoid bleeding is unrelated",
                setOf("cardiology", "طب"), AnkiCardQueueState.REVIEW, AnkiFlag.BLUE, 12, 4, 300L, 4_000L),
            Row(DeckTree.CARDIOLOGY, "c-3", DeckTree.DUPLICATE_QUESTION, "Answer: shared text",
                setOf("cardiology"), AnkiCardQueueState.SUSPENDED, AnkiFlag.RED, 12, 1, null, 5_000L),
            Row(DeckTree.NEUROLOGY, "n-1", DeckTree.DUPLICATE_QUESTION, "Answer: shared text",
                setOf("neurology", "zephyr-tag"), AnkiCardQueueState.BURIED, AnkiFlag.ORANGE, 5, 1, 400L, 6_000L),
            Row(DeckTree.NEUROLOGY, "n-2", "Cranial nerve examination sequence", "Answer: I to XII in order",
                setOf("neurology"), AnkiCardQueueState.LEARNING, AnkiFlag.ORANGE, 5, 2, 150L, 7_000L),
            Row(DeckTree.NEURO_VASCULAR, "v-1", "Subarachnoid hemorrhage: thunderclap headache", "Answer: worst headache of life",
                setOf("neurology", "high-yield"), AnkiCardQueueState.REVIEW, AnkiFlag.RED, 40, 2, 50L, 8_000L),
            Row(DeckTree.NEURO_VASCULAR, "v-2", DeckTree.DOUBLE_SPACE_QUESTION, "Answer: two  spaces",
                setOf("neurology"), AnkiCardQueueState.UNKNOWN, AnkiFlag.NONE, null, 0, null, null),
            Row(DeckTree.ZEPHYR, "z-1", "Zephyr deck card about zephyr winds", "Answer: zephyr",
                setOf("weather"), AnkiCardQueueState.REVIEW, AnkiFlag.GREEN, 7, 0, 700L, 9_000L)
        )

        val cards = rows.mapIndexed { index, row ->
            val deck = ref(row.deckId)
            AnkiRenderedCard(
                ref = AnkiCardRef(
                    backendId = backendId,
                    cardId = "card-${row.cardId}",
                    noteId = "note-${row.cardId}",
                    cardOrd = 0,
                    collectionKey = collectionKey
                ),
                questionHtml = "<p>${row.question}</p>",
                answerHtml = row.answer?.let { "<p>$it</p>" },
                questionText = row.question,
                answerText = row.answer,
                pureAnswerText = null,
                media = emptyList<AnkiMediaRef>(),
                scheduling = AnkiSchedulingInfo(
                    reps = row.reps,
                    lapses = row.lapses,
                    dueEpochSeconds = row.due
                ),
                metadata = AnkiCardMetadata(
                    deckName = decks.first { it.ref.deckId == row.deckId }.name,
                    tags = row.tags,
                    queueState = row.queue,
                    noteCreatedEpochSeconds = row.created,
                    noteModifiedEpochSeconds = row.created?.plus(1_000L)?.plus(index)
                ),
                noteRef = AnkiNoteRef(backendId, "note-${row.cardId}", collectionKey),
                deckRef = deck,
                flag = row.flag
            )
        }
        return DeckTreeFixture(decks = decks, cards = cards)
    }
}
