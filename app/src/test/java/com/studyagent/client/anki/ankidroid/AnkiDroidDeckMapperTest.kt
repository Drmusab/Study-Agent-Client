package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDeckMapper
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDeckRowOutcome
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDeckRowProblem
import com.studyagent.client.data.anki.ankidroid.InMemoryProviderRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDroidDeckMapperTest {

    private fun row(vararg pairs: Pair<String, Any?>) = InMemoryProviderRow(linkedMapOf(*pairs))

    @Test
    fun `identity is the numeric deck id never the name`() {
        val outcome = AnkiDroidDeckMapper.mapDeckRow(
            row("deck_id" to 1590535532642L, "deck_name" to "Medicine::Cardiology")
        ) as AnkiDroidDeckRowOutcome.Valid
        assertEquals("1590535532642", outcome.deck.ref.deckId)
        assertEquals(AnkiBackendId.AnkiDroidLocal, outcome.deck.ref.backendId)
        assertEquals("Medicine::Cardiology", outcome.deck.name)
        assertEquals("Cardiology", outcome.deck.leafName)
        assertNull(outcome.deck.parentRef)
        assertNull(outcome.deck.counts)
        assertNull(outcome.deck.isFiltered)
    }

    @Test
    fun `counts follow contract order learn review new and never invent totalDue`() {
        val outcome = AnkiDroidDeckMapper.mapDeckRow(
            row("deck_id" to 1L, "deck_name" to "Default", "deck_count" to "[2, 5, 9]")
        ) as AnkiDroidDeckRowOutcome.Valid
        assertEquals(9, outcome.deck.counts?.new)
        assertEquals(2, outcome.deck.counts?.learning)
        assertEquals(5, outcome.deck.counts?.review)
        assertNull(outcome.deck.counts?.totalDue)
        assertTrue(outcome.countsParsed)
    }

    @Test
    fun `zero counts stay zero and missing counts stay null`() {
        val zero = AnkiDroidDeckMapper.mapDeckRow(
            row("deck_id" to 1L, "deck_name" to "Default", "deck_count" to "[0, 0, 0]")
        ) as AnkiDroidDeckRowOutcome.Valid
        assertEquals(0, zero.deck.counts?.new)
        val missing = AnkiDroidDeckMapper.mapDeckRow(
            row("deck_id" to 1L, "deck_name" to "Default")
        ) as AnkiDroidDeckRowOutcome.Valid
        assertNull(missing.deck.counts)
        assertFalse(missing.countsParsed)
    }

    @Test
    fun `malformed or nested counts are unknown not zero`() {
        listOf("[1, 2]", "not-json", "[[1, 2, 3]]", "[1, 2, -1]", "").forEach { raw ->
            val outcome = AnkiDroidDeckMapper.mapDeckRow(
                row("deck_id" to 1L, "deck_name" to "Default", "deck_count" to raw)
            ) as AnkiDroidDeckRowOutcome.Valid
            assertNull(raw, outcome.deck.counts)
        }
    }

    @Test
    fun `filtered flag accepts boolean text and 1 0`() {
        fun dyn(value: Any?) = (
            AnkiDroidDeckMapper.mapDeckRow(
                row("deck_id" to 1L, "deck_name" to "Filtered", "deck_dyn" to value)
            ) as AnkiDroidDeckRowOutcome.Valid
        ).deck.isFiltered
        assertEquals(true, dyn(true))
        assertEquals(false, dyn(false))
        assertEquals(true, dyn("1"))
        assertEquals(false, dyn("0"))
        assertNull(dyn("maybe"))
        assertNull(dyn(null))
    }

    @Test
    fun `invalid identity is malformed never coerced to zero`() {
        val missingId = AnkiDroidDeckMapper.mapDeckRow(row("deck_name" to "Default"))
            as AnkiDroidDeckRowOutcome.Malformed
        assertEquals(AnkiDroidDeckRowProblem.ID_COLUMN_MISSING, missingId.problem)
        assertTrue(missingId.problem.structural)

        val zero = AnkiDroidDeckMapper.mapDeckRow(row("deck_id" to 0L, "deck_name" to "Default"))
            as AnkiDroidDeckRowOutcome.Malformed
        assertEquals(AnkiDroidDeckRowProblem.ID_INVALID, zero.problem)
        assertFalse(zero.problem.structural)

        val blank = AnkiDroidDeckMapper.mapDeckRow(row("deck_id" to 1L, "deck_name" to "  "))
            as AnkiDroidDeckRowOutcome.Malformed
        assertEquals(AnkiDroidDeckRowProblem.NAME_BLANK, blank.problem)
    }

    @Test
    fun `unicode names are preserved verbatim`() {
        val name = "طب::جراحة::🧠 (part-1)/x"
        val outcome = AnkiDroidDeckMapper.mapDeckRow(
            row("deck_id" to 7L, "deck_name" to name)
        ) as AnkiDroidDeckRowOutcome.Valid
        assertEquals(name, outcome.deck.name)
        assertEquals(listOf("طب", "جراحة", "🧠 (part-1)/x"), outcome.deck.path)
    }

    @Test
    fun `same raw id on a different backend stays a different ref`() {
        val ankidroid = AnkiDroidDeckMapper.mapDeckRow(
            row("deck_id" to 123L, "deck_name" to "X"),
            backendId = AnkiBackendId.AnkiDroidLocal
        ) as AnkiDroidDeckRowOutcome.Valid
        val fake = AnkiDroidDeckMapper.mapDeckRow(
            row("deck_id" to 123L, "deck_name" to "X"),
            backendId = AnkiBackendId.Fake("other")
        ) as AnkiDroidDeckRowOutcome.Valid
        assertTrue(ankidroid.deck.ref != fake.deck.ref)
        assertTrue(ankidroid.deck.ref.stableKey != fake.deck.ref.stableKey)
    }

    @Test
    fun `selected deck row maps identity only`() {
        val ref = AnkiDroidDeckMapper.mapSelectedDeckRow(row("deck_id" to 42L, "deck_name" to "Default"))
        assertEquals("42", ref?.deckId)
        assertNull(AnkiDroidDeckMapper.mapSelectedDeckRow(row("deck_name" to "Default")))
        assertNull(AnkiDroidDeckMapper.mapSelectedDeckRow(row("deck_id" to 0L)))
    }
}
