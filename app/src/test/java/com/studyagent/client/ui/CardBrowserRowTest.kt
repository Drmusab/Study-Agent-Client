package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardListItem
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardBrowserRowTest {
    @Test
    fun `row projection strips markup clamps text and respects answer preview capability`() {
        val backendId = AnkiBackendId.Fake("row")
        val deck = AnkiDeckRef(backendId, "deck", "collection")
        val cardRef = AnkiCardRef(backendId, "card", "note", 0, "collection")
        val item = AnkiCardListItem(
            cardRef = cardRef,
            noteRef = AnkiNoteRef(backendId, "note", "collection"),
            deckRef = deck,
            deckName = "<b>العربية</b> English",
            questionText = "<script>ignored()</script><b>السؤال</b><br/>QRS &amp; ECG " + "x".repeat(300),
            answerText = "<img src='remote'>Answer <i>text</i>",
            tags = listOf("طب", "cardiology", "ecg", "hidden-fourth")
        )

        val hiddenAnswer = CardBrowserRow.from(item, answerPreviewAllowed = false)
        val visibleAnswer = CardBrowserRow.from(item, answerPreviewAllowed = true)
        assertEquals(cardRef, hiddenAnswer.cardRef)
        assertNull(hiddenAnswer.answerPreview)
        assertEquals("العربية English", hiddenAnswer.deckName)
        assertTrue(hiddenAnswer.questionPreview!!.startsWith("السؤال QRS & ECG"))
        assertFalse(hiddenAnswer.questionPreview.contains("<"))
        assertFalse(hiddenAnswer.questionPreview.contains("ignored"))
        assertTrue(hiddenAnswer.questionPreview.length <= 220)
        assertEquals(listOf("طب", "cardiology", "ecg"), hiddenAnswer.tags)
        assertEquals("Answer text", visibleAnswer.answerPreview)
    }
}
