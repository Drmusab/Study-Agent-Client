package com.studyagent.client.render

import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardRenderPlan
import com.studyagent.client.core.render.AnkiCardRenderPlanner
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiRenderFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 08 STEP 119-§121 — the fallback order and the side semantics, as executable rules.
 *
 * The two claims that matter most are here:
 *
 * - the ANSWER side renders `answerHtml` **as the backend produced it** — the question is never
 *   prepended, because Anki's answer already contains it through `{{FrontSide}}` (STEP 28/§29,
 *   INV-ANKI-RENDER-12);
 * - a fallback always uses a channel the backend actually supplied (INV-ANKI-RENDER-20) — there is no
 *   HTML-stripping path and no invented placeholder content.
 */
class AnkiCardRenderPlanTest {

    private fun plan(
        card: AnkiRenderedCard = renderCard(),
        side: AnkiCardSide = AnkiCardSide.QUESTION,
        mode: AnkiCardRenderMode = AnkiCardRenderMode.ORIGINAL
    ): AnkiCardRenderPlan = AnkiCardRenderPlanner.plan(card, side, mode)

    // ------------------------------------------------------------------ ORIGINAL

    @Test
    fun `ORIGINAL uses the side's own HTML and nothing else`() {
        val card = renderCard()
        val question = plan(card, AnkiCardSide.QUESTION) as AnkiCardRenderPlan.Original
        val answer = plan(card, AnkiCardSide.ANSWER) as AnkiCardRenderPlan.Original

        assertEquals(card.questionHtml, question.html)
        assertEquals(card.answerHtml, answer.html)
    }

    @Test
    fun `the answer is never rebuilt from question plus answer`() {
        val card = renderCard()
        val answer = plan(card, AnkiCardSide.ANSWER) as AnkiCardRenderPlan.Original

        // The backend's answer already contains the front side and the separator; concatenating again
        // would show the question twice on every Basic card.
        assertEquals(card.answerHtml, answer.html)
        assertEquals(1, Regex(Regex.escape(QUESTION_MARKER)).findAll(answer.html).count())
        assertEquals("the separator is Anki's own, and there is exactly one", 1, Regex("<hr").findAll(answer.html).count())
        assertTrue(answer.html.contains("<hr id=answer>"))
    }

    @Test
    fun `ORIGINAL carries the text channel as the marked fallback`() {
        val card = renderCard()
        val question = plan(card, AnkiCardSide.QUESTION) as AnkiCardRenderPlan.Original
        val answer = plan(card, AnkiCardSide.ANSWER) as AnkiCardRenderPlan.Original
        assertEquals(card.questionText, question.fallbackText)
        assertEquals(card.answerText, answer.fallbackText)
    }

    @Test
    fun `an empty-but-present HTML channel is rendered, not swapped for text`() {
        val card = renderCard(questionHtml = "", questionText = "text exists")
        val result = plan(card, AnkiCardSide.QUESTION) as AnkiCardRenderPlan.Original
        assertEquals("", result.html)
        assertTrue(
            "an empty rendering is a fact and is recorded as one",
            result.tokens.contains(AnkiCardRenderPlan.TOKEN_HTML_EMPTY)
        )
    }

    @Test
    fun `a missing HTML channel falls back to the backend text, marked`() {
        val card = renderCard(questionHtml = null, questionText = "text only question")
        val result = plan(card, AnkiCardSide.QUESTION) as AnkiCardRenderPlan.CleanText
        assertEquals("text only question", result.text)
        assertEquals(AnkiCardRenderPlan.TOKEN_HTML_UNAVAILABLE, result.reason)
        assertFalse("no placeholder wording is invented by the planner", result.text.isBlank())
    }

    @Test
    fun `a missing answer channel falls back the same way`() {
        val card = renderCard(answerHtml = null, answerText = "answer text only")
        val result = plan(card, AnkiCardSide.ANSWER) as AnkiCardRenderPlan.CleanText
        assertEquals("answer text only", result.text)
        assertEquals(AnkiCardRenderPlan.TOKEN_HTML_UNAVAILABLE, result.reason)
    }

    @Test
    fun `no channel at all is a typed failure, never a blank plan`() {
        val card = renderCard(answerHtml = null, answerText = null)
        val result = plan(card, AnkiCardSide.ANSWER) as AnkiCardRenderPlan.Unavailable
        assertTrue(result.failure is AnkiRenderFailure.HtmlUnavailable)
        assertNull(result.fallbackText)
        assertNull(result.html)
    }

    @Test
    fun `GATE 07 degradation tokens are carried through for diagnostics`() {
        val card = renderCard(
            questionText = null,
            answerText = null,
            degradations = listOf("card_speech_text_unavailable")
        )
        val result = plan(card, AnkiCardSide.QUESTION) as AnkiCardRenderPlan.Original
        assertTrue(result.tokens.contains("card_speech_text_unavailable"))
        assertNull("no text channel means no invented fallback", result.fallbackText)
    }

    // ------------------------------------------------------------------ CLEAN / VOICE_FOCUS

    @Test
    fun `CLEAN uses the text channel even when HTML exists`() {
        val card = renderCard()
        val result = plan(card, AnkiCardSide.QUESTION, AnkiCardRenderMode.CLEAN) as AnkiCardRenderPlan.CleanText
        assertEquals(card.questionText, result.text)
        assertEquals(AnkiCardRenderPlan.TOKEN_MODE_CLEAN, result.reason)
    }

    @Test
    fun `CLEAN degrades to the channel that exists, marked, when there is no text`() {
        val card = renderCard(questionText = null, answerText = null)
        val result = plan(card, AnkiCardSide.QUESTION, AnkiCardRenderMode.CLEAN) as AnkiCardRenderPlan.Original
        assertEquals(card.questionHtml, result.html)
        assertTrue(result.tokens.contains(AnkiCardRenderPlan.TOKEN_CLEAN_TEXT_UNAVAILABLE))
        assertNull("HTML is never stripped into pseudo-text", result.fallbackText)
    }

    @Test
    fun `CLEAN with no channel at all is a typed text failure`() {
        // AnkiRenderedCard requires *some* question representation (GATE 07), so the honest way to build
        // a side with nothing at all is the answer side of a question-only card.
        val answerCard = renderCard(answerHtml = null, answerText = null)
        val result = plan(answerCard, AnkiCardSide.ANSWER, AnkiCardRenderMode.CLEAN) as AnkiCardRenderPlan.Unavailable
        assertTrue(result.failure is AnkiRenderFailure.TextUnavailable)
        assertEquals(AnkiCardSide.ANSWER, result.side)
    }

    @Test
    fun `VOICE_FOCUS plans like CLEAN and is distinguishable in diagnostics`() {
        val result = plan(renderCard(), AnkiCardSide.QUESTION, AnkiCardRenderMode.VOICE_FOCUS)
        assertTrue(result is AnkiCardRenderPlan.CleanText)
        assertTrue(result.tokens.contains(AnkiCardRenderPlanner.TOKEN_MODE_VOICE_FOCUS))
    }

    @Test
    fun `an unresolved ADAPTIVE request never reaches a WebView by accident`() {
        val result = plan(renderCard(), AnkiCardSide.QUESTION, AnkiCardRenderMode.ADAPTIVE)
        assertTrue(
            "the planner treats an unresolved mode as the fidelity default, and the renderer resolves " +
                "ADAPTIVE before planning",
            result is AnkiCardRenderPlan.Original
        )
    }

    // ------------------------------------------------------------------ no invention

    @Test
    fun `no plan invents content that the card does not carry`() {
        val card = renderCard(questionHtml = null, questionText = "only text")
        val result = plan(card, AnkiCardSide.QUESTION)
        val texts = listOfNotNull(result.fallbackText, (result as? AnkiCardRenderPlan.CleanText)?.text)
        texts.forEach { text ->
            assertTrue(
                "every string a plan hands out must come from the card",
                text == card.questionText || text == card.answerText ||
                    text == card.questionHtml || text == card.answerHtml
            )
        }
    }

}
