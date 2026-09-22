package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckFilter
import com.studyagent.client.core.anki.AnkiDeckOrder
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiDeckTreeBuilder
import com.studyagent.client.core.anki.AnkiDeckTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDeckTreeTest {

    private val backend = AnkiBackendId.Fake("tree")

    private fun deck(id: String, name: String) = AnkiDeck(
        ref = AnkiDeckRef(backend, id),
        name = name
    )

    @Test
    fun `empty input yields empty forest not a fake root`() {
        val tree = AnkiDeckTreeBuilder.build(emptyList())
        assertTrue(tree.isEmpty)
        assertEquals(0, tree.deckCount)
        assertEquals(0, tree.virtualGroupCount)
        assertTrue(tree.roots.isEmpty())
    }

    @Test
    fun `single root deck is a real node`() {
        val d = deck("1", "Default")
        val tree = AnkiDeckTreeBuilder.build(listOf(d))
        assertEquals(1, tree.roots.size)
        val root = tree.roots.single() as AnkiDeckTreeNode.Deck
        assertEquals(d.ref, root.ref)
        assertTrue(root.children.isEmpty())
        assertEquals(0, tree.virtualGroupCount)
        assertEquals(0, tree.maxDepth)
    }

    @Test
    fun `nested deck creates a virtual parent when the parent was not returned`() {
        val child = deck("2", "Medicine::Cardiology")
        val tree = AnkiDeckTreeBuilder.build(listOf(child))
        val group = tree.roots.single() as AnkiDeckTreeNode.VirtualGroup
        assertEquals("Medicine", group.name)
        assertTrue(group.children.single() is AnkiDeckTreeNode.Deck)
        assertEquals(1, tree.virtualGroupCount)
        assertEquals(1, tree.maxDepth)
    }

    @Test
    fun `real parent is linked by path not by invented id`() {
        val parent = deck("10", "Medicine")
        val child = deck("11", "Medicine::Cardiology")
        val tree = AnkiDeckTreeBuilder.build(listOf(child, parent))
        val root = tree.roots.single() as AnkiDeckTreeNode.Deck
        assertEquals(parent.ref, root.ref)
        assertEquals(child.ref, (root.children.single() as AnkiDeckTreeNode.Deck).ref)
        assertEquals(0, tree.virtualGroupCount)
    }

    @Test
    fun `deep hierarchy siblings and duplicate leaf names stay distinct`() {
        val decks = listOf(
            deck("1", "Medicine::ECG"),
            deck("2", "Cardiology::ECG"),
            deck("3", "Medicine::Cardiology::Arrhythmias"),
            deck("4", "Medicine")
        )
        val tree = AnkiDeckTreeBuilder.build(decks)
        assertEquals(2, tree.roots.size)
        val medicine = tree.roots.filterIsInstance<AnkiDeckTreeNode.Deck>().single { it.ref.deckId == "4" }
        val cardiologyRoot = tree.roots.filterIsInstance<AnkiDeckTreeNode.VirtualGroup>().single { it.name == "Cardiology" }
        assertEquals("ECG", (cardiologyRoot.children.single() as AnkiDeckTreeNode.Deck).name)
        val medicineLeaves = medicine.children.map { it.name }.toSet()
        assertTrue(medicineLeaves.contains("ECG"))
        assertEquals(4, tree.decksInDisplayOrder().map { it.ref }.distinct().size)
        assertEquals(4, tree.deckCount)
        val ecgRefs = tree.flatten().filterIsInstance<AnkiDeckTreeNode.Deck>().filter { it.name == "ECG" }.map { it.ref }
        assertEquals(2, ecgRefs.distinct().size)
    }

    @Test
    fun `arabic and mixed rtl names are preserved`() {
        val decks = listOf(
            deck("1", "طب"),
            deck("2", "طب::جراحة"),
            deck("3", "طب::جراحة::جملة عصبية"),
            deck("4", "MCCQE::Neurology::إصابات الرأس")
        )
        val tree = AnkiDeckTreeBuilder.build(decks)
        val tibb = tree.roots.filterIsInstance<AnkiDeckTreeNode.Deck>().single { it.fullName == "طب" }
        assertEquals("جراحة", (tibb.children.single() as AnkiDeckTreeNode.Deck).name)
        assertEquals("جملة عصبية", tibb.children.single().children.single().name)
        assertEquals("إصابات الرأس", tree.flatten().filterIsInstance<AnkiDeckTreeNode.Deck>().single { it.ref.deckId == "4" }.name)
        decks.forEach { original ->
            assertEquals(original.name, tree.flatten().filterIsInstance<AnkiDeckTreeNode.Deck>().single { it.ref == original.ref }.fullName)
        }
    }

    @Test
    fun `malformed empty segments do not crash or invent a real deck`() {
        val child = deck("9", "Medicine::::Cardiology")
        val tree = AnkiDeckTreeBuilder.build(listOf(child))
        assertEquals(1, tree.deckCount)
        val real = tree.flatten().filterIsInstance<AnkiDeckTreeNode.Deck>().single()
        assertEquals("Medicine::::Cardiology", real.fullName)
        assertTrue(tree.flatten().filterIsInstance<AnkiDeckTreeNode.VirtualGroup>().none { it.fullName.isEmpty() && it.segments.isEmpty() })
        assertTrue(tree.virtualGroupCount >= 1)
    }

    @Test
    fun `special characters are not hierarchy separators`() {
        val d = deck("1", "A/B-C_D (x) 🧠")
        val tree = AnkiDeckTreeBuilder.build(listOf(d))
        assertEquals(0, tree.maxDepth)
        assertEquals("A/B-C_D (x) 🧠", (tree.roots.single() as AnkiDeckTreeNode.Deck).fullName)
    }

    @Test
    fun `sort is deterministic and uses id as tie breaker`() {
        val a = deck("20", "Alpha")
        val b = deck("3", "Alpha")
        val ordered = listOf(a, b).sortedWith(AnkiDeckOrder)
        assertEquals(listOf("3", "20"), ordered.map { it.ref.deckId })
        val tree1 = AnkiDeckTreeBuilder.build(listOf(a, b))
        val tree2 = AnkiDeckTreeBuilder.build(listOf(b, a))
        assertEquals(tree1.flatten().map { (it as? AnkiDeckTreeNode.Deck)?.ref }, tree2.flatten().map { (it as? AnkiDeckTreeNode.Deck)?.ref })
    }

    @Test
    fun `case is not merged and parent sorts before child`() {
        val decks = listOf(deck("1", "a"), deck("2", "A"), deck("3", "A::B"))
        val tree = AnkiDeckTreeBuilder.build(decks)
        assertEquals(2, tree.roots.size)
        assertEquals(3, tree.deckCount)
        val display = tree.decksInDisplayOrder().map { it.name }
        assertTrue(display.indexOf("A") < display.indexOf("A::B"))
    }

    @Test
    fun `filter matches full name leaf and segments without using identity`() {
        val decks = listOf(
            deck("1", "Medicine::Cardiology"),
            deck("2", "Medicine::Neurology"),
            deck("3", "Zoology")
        )
        assertEquals(2, AnkiDeckFilter.filter(decks, "medicine").size)
        assertEquals(1, AnkiDeckFilter.filter(decks, "cardio").size)
        assertEquals(decks, AnkiDeckFilter.filter(decks, "  "))
        assertTrue(AnkiDeckFilter.matches(decks[0], "Cardiology"))
    }

    @Test
    fun `one thousand decks build a deterministic tree`() {
        val decks = (1..1000).map { i ->
            val parent = "Deck${i % 50}"
            deck(i.toString(), "$parent::Item$i")
        }
        val tree = AnkiDeckTreeBuilder.build(decks)
        assertEquals(1000, tree.deckCount)
        assertEquals(50, tree.virtualGroupCount)
        assertEquals(1000, tree.flatten().filterIsInstance<AnkiDeckTreeNode.Deck>().size)
        val again = AnkiDeckTreeBuilder.build(decks.shuffled())
        assertEquals(
            tree.decksInDisplayOrder().map { it.ref.deckId },
            again.decksInDisplayOrder().map { it.ref.deckId }
        )
    }

    @Test
    fun `virtual group is never a real Anki deck`() {
        val tree = AnkiDeckTreeBuilder.build(listOf(deck("1", "A::B")))
        val group = tree.roots.single() as AnkiDeckTreeNode.VirtualGroup
        assertTrue(group.children.single() is AnkiDeckTreeNode.Deck)
        // Compiles as VirtualGroup, not Deck — no ref to invent.
        assertEquals("A", group.fullName)
    }
}
