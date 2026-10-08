package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.core.anki.AnkiDeckRef

/** Deterministic fixtures shared by Library hierarchy, performance and details tests. */
object LibraryTestFixtures {
    fun threeDecks(backendId: AnkiBackendId): List<AnkiDeck> = listOf(
        deck(backendId, "101", "Medicine", AnkiDeckCounts(new = 4, learning = 2, review = 8)),
        deck(backendId, "102", "Medicine::Cardiology", AnkiDeckCounts(new = 0, learning = 1, review = 5)),
        deck(backendId, "103", "Language::العربية English", AnkiDeckCounts(new = 0, learning = 0, review = 0))
    )

    /** 420 unique decks: nested and long names, Arabic and mixed-script paths, unknown/zero counts. */
    fun largeCollection(backendId: AnkiBackendId, count: Int = 420): List<AnkiDeck> {
        require(count >= 4)
        return buildList(count) {
            add(deck(backendId, "root", "Medicine", AnkiDeckCounts(new = 0, learning = 0, review = 0)))
            add(deck(backendId, "arabic", "طب::أمراض القلب", AnkiDeckCounts(new = null, learning = 2, review = null)))
            add(deck(backendId, "mixed", "Languages::العربية English", AnkiDeckCounts(new = 3, learning = 0, review = 9)))
            add(deck(backendId, "long", "Reference::" + "Long deck name ".repeat(14), null))
            for (index in 4 until count) {
                val subject = when (index % 5) {
                    0 -> "Medicine::Cardiology"
                    1 -> "Medicine::Neurology"
                    2 -> "Practice::Session ${index / 5}"
                    3 -> "Languages::العربية"
                    else -> "Reference"
                }
                val counts = when (index % 7) {
                    0 -> null
                    1 -> AnkiDeckCounts(new = 0, learning = 0, review = 0)
                    else -> AnkiDeckCounts(new = index % 13, learning = index % 4, review = index % 19)
                }
                add(deck(backendId, index.toString(), "$subject::Deck $index", counts))
            }
        }
    }

    private fun deck(backendId: AnkiBackendId, id: String, name: String, counts: AnkiDeckCounts?) =
        AnkiDeck(ref = AnkiDeckRef(backendId, id), name = name, counts = counts)
}
