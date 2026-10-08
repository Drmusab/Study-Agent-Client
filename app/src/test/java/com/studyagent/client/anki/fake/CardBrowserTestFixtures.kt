package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiSchedulingInfo
import com.studyagent.client.core.anki.AnkiCardMetadata

/** Deterministic Card Browser fixture set; large cases live only in the JVM test source set. */
object CardBrowserTestFixtures {
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
                scheduling = AnkiSchedulingInfo(reps = index % 100, lapses = index % 13),
                metadata = AnkiCardMetadata(
                    deckName = "Deck ${deck.deckId}",
                    tags = tags,
                    queueState = queueState
                ),
                noteRef = AnkiNoteRef(backendId, "note-${index / 2}", deck.collectionKey),
                deckRef = deck,
                flag = AnkiFlag.entries.getOrNull(index % 8)
            )
        }
    }
}
