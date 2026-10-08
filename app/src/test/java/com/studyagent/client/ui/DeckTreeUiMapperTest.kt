package com.studyagent.client.ui

import com.studyagent.client.anki.fake.LibraryTestFixtures
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiDeckSummary
import com.studyagent.client.ui.screens.library.DeckListItem
import com.studyagent.client.ui.screens.library.DeckTreeItem
import com.studyagent.client.ui.screens.library.DeckTreeProjection
import com.studyagent.client.ui.screens.library.DeckTreeUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTreeUiMapperTest {
    private val backend = AnkiBackendId.Fake("tree")

    private fun item(id: String, name: String, newCount: Int? = null): DeckListItem {
        val deck = AnkiDeck(AnkiDeckRef(backend, id), name, counts = newCount?.let { AnkiDeckCounts(new = it) })
        return DeckListItem(AnkiDeckSummary(deck, deck.counts, isSelectedByBackend = null, isFiltered = null))
    }

    @Test
    fun `nested decks map into real and virtual hierarchy without changing identities`() {
        val source = listOf(
            item("1", "Medicine"),
            item("2", "Medicine::Cardiology"),
            item("3", "Medicine::Cardiology::ECG"),
            item("4", "Medicine::Neurology")
        )
        val roots = DeckTreeUiMapper.build(source)
        val medicine = roots.single() as DeckTreeItem.Deck
        assertEquals("1", medicine.item.deck.ref.deckId)
        assertEquals(0, medicine.depth)
        val children = medicine.children.filterIsInstance<DeckTreeItem.Deck>()
        assertEquals(listOf("2", "4"), children.map { it.item.deck.ref.deckId })
        val cardiology = children.first()
        assertEquals("3", cardiology.children.single().let { (it as DeckTreeItem.Deck).item.deck.ref.deckId })
    }

    @Test
    fun `search retains virtual ancestors and Arabic mixed script text verbatim`() {
        val source = listOf(
            item("1", "Language::العربية English"),
            item("2", "طب::أمراض القلب")
        )
        val roots = DeckTreeUiMapper.build(source, "القلب")
        val group = roots.single() as DeckTreeItem.Group
        assertEquals("طب", group.fullName)
        val real = group.children.single() as DeckTreeItem.Deck
        assertEquals("طب::أمراض القلب", real.fullName)
        assertEquals("أمراض القلب", real.name)
    }

    @Test
    fun `same visible name with different IDs produces distinct row keys`() {
        val roots = DeckTreeUiMapper.build(listOf(item("41", "Same"), item("42", "Same")))
        val keys = roots.filterIsInstance<DeckTreeItem.Deck>().map { it.key }
        assertEquals(2, keys.distinct().size)
        assertNotEquals(keys[0], keys[1])
        assertEquals(setOf("41", "42"), roots.filterIsInstance<DeckTreeItem.Deck>().map { it.item.deck.ref.deckId }.toSet())
    }

    @Test
    fun `large tree flattens with bounded memory and all stable deck identities`() {
        val source = LibraryTestFixtures.largeCollection(backend)
        val roots = DeckTreeUiMapper.build(source)
        val flattened = DeckTreeProjection.flattenVisible(roots, emptySet(), forceExpanded = true)
        val deckRows = flattened.filterIsInstance<DeckTreeItem.Deck>()
        assertEquals(420, deckRows.size)
        assertEquals(420, deckRows.map { it.item.deck.ref.deckId }.distinct().size)
        assertTrue(deckRows.any { it.item.deck.name.contains("العربية") })
        assertTrue(deckRows.any { it.item.deck.name.length > 100 })
    }
}
