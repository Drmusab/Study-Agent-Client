package com.studyagent.client.render

import com.studyagent.client.core.render.AnkiCardDocument
import com.studyagent.client.core.render.AnkiCardDocumentBuilder
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiJavascriptPolicy
import com.studyagent.client.core.render.AnkiRenderRequestId
import com.studyagent.client.core.render.CardTextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 08 STEP 102 — the HTML document builder, unit-tested.
 *
 * These assertions are the executable form of the "minimal wrapper" contract: UTF-8, the right side
 * inserted verbatim, `.card` semantics preserved, direction configured rather than hard-coded, the JS
 * policy reflected, night mode expressed as a color scheme and a class rather than an inversion, and a
 * document-shaped payload passed through instead of double-wrapped.
 */
class AnkiCardDocumentTest {

    private val request = AnkiRenderRequestId(
        turnId = turnId(),
        cardRef = renderCard().ref,
        side = AnkiCardSide.QUESTION,
        generation = 1L
    )

    private fun build(
        payload: String,
        config: AnkiCardRenderConfig = AnkiCardRenderConfig.DEFAULT,
        side: AnkiCardSide = AnkiCardSide.QUESTION
    ): AnkiCardDocument = AnkiCardDocumentBuilder.build(
        payload = payload,
        request = request.copy(side = side),
        config = config
    )

    // ------------------------------------------------------------------ shell

    @Test
    fun `a fragment gets the smallest predictable shell`() {
        val document = build(QUESTION_HTML)
        val html = document.html

        assertTrue("doctype first", html.startsWith("<!doctype html>"))
        assertTrue("one html element", html.contains("<html "))
        assertTrue("head present", html.contains("<head>"))
        assertTrue("body present", html.contains("<body "))
        assertTrue("closes html", html.trimEnd().endsWith("</html>"))
        // Exactly one of each: a second shell would mean the payload was wrapped twice.
        assertEquals(1, Regex("<html[ >]").findAll(html).count())
        assertEquals(1, Regex("<body[ >]").findAll(html).count())
        assertFalse("no doctype inside the payload region", html.drop(20).contains("<!doctype"))
    }

    @Test
    fun `the document is UTF-8 in both the meta tag and the load encoding`() {
        val document = build(QUESTION_HTML)
        assertTrue(document.html.contains("<meta charset=\"utf-8\">"))
        assertEquals("UTF-8", document.encoding)
        assertEquals("text/html", document.mimeType)
    }

    @Test
    fun `a mobile viewport is declared without breaking authored scaling`() {
        val html = build(QUESTION_HTML).html
        assertTrue(html.contains("name=\"viewport\""))
        assertTrue(html.contains("width=device-width"))
        assertTrue(html.contains("initial-scale=1"))
        // No maximum-scale / user-scalable=no: a card must not be locked out of scaling by us.
        assertFalse(html.contains("user-scalable=no"))
        assertFalse(html.contains("maximum-scale"))
    }

    @Test
    fun `the body keeps the card class so template CSS still targets it`() {
        val html = build(QUESTION_HTML).html
        assertTrue(html.contains("class=\"${AnkiCardDocument.BODY_CLASS_CARD}\""))
        assertEquals(listOf("card"), build(QUESTION_HTML).bodyClasses)
    }

    @Test
    fun `the side is inspectable but grants the page nothing`() {
        val question = build(QUESTION_HTML, side = AnkiCardSide.QUESTION).html
        val answer = build(ANSWER_HTML, side = AnkiCardSide.ANSWER).html
        assertTrue(question.contains("${AnkiCardDocument.SIDE_ATTRIBUTE}=\"question\""))
        assertTrue(answer.contains("${AnkiCardDocument.SIDE_ATTRIBUTE}=\"answer\""))
        // The DOM carries the side name and nothing else: no turn id, no card id, no native handle.
        assertFalse(question.contains(turnId().value))
        assertFalse(question.contains("AnkiDroid"))
        assertFalse(question.contains("StudyAgentBridge"))
    }

    @Test
    fun `the request identity travels with the document`() {
        val document = build(QUESTION_HTML)
        assertEquals(request, document.request)
        assertEquals(AnkiCardSide.QUESTION, document.request.side)
        assertEquals(1L, document.request.generation)
    }

    // ------------------------------------------------------------------ fidelity

    @Test
    fun `card HTML is inserted byte for byte`() {
        val payload = ANSWER_HTML
        val document = build(payload, side = AnkiCardSide.ANSWER)
        assertTrue(
            "the payload must appear unchanged inside the document",
            document.html.contains(payload)
        )
        assertEquals(payload.length, document.payloadLength)
    }

    @Test
    fun `markup is never escaped and entities are never decoded twice`() {
        val payload = "<p>a &lt; b &amp;&amp; c &amp;amp; d</p><table><tr><td>x</td></tr></table>"
        val html = build(payload).html
        assertTrue(html.contains(payload))
        assertFalse("no double escaping of <", html.contains("&amp;lt;"))
        assertFalse("no decoding of entities", html.contains("a < b && c"))
    }

    @Test
    fun `newlines are preserved instead of being turned into breaks`() {
        val payload = "line one\nline two\n\nline four"
        val html = build(payload).html
        assertTrue(html.contains(payload))
        assertFalse("the builder must not insert <br> for newlines", html.contains("<br>"))
    }

    @Test
    fun `Arabic, diacritics and emoji survive the round trip`() {
        val payload = "$ARABIC_HTML<p>الْعِلْمُ نُورٌ α β γ ≤ ≥ → µ 😀🫀</p>"
        val html = build(payload).html
        assertTrue(html.contains(payload))
        assertTrue(html.contains("ما هو القلب؟"))
        assertTrue(html.contains("🫀"))
    }

    @Test
    fun `tables and wide content are left alone`() {
        val payload = "<table style=\"width:2400px\"><tr><td>wide</td></tr></table>"
        val html = build(payload).html
        assertTrue(html.contains(payload))
        // The base stylesheet constrains images and video only; a wide table keeps its geometry and the
        // WebView scrolls it (STEP 63).
        val baseCss = AnkiCardDocumentBuilder.baseCss(nightMode = false)
        assertFalse(baseCss.contains("table"))
        assertTrue(baseCss.contains("img,video{max-width:100%}"))
    }

    @Test
    fun `cloze output is passed through without local cloze logic`() {
        val payload = "The <span class=\"cloze\">[…]</span> is between the left atrium and ventricle."
        val html = build(payload).html
        assertTrue(html.contains(payload))
        assertFalse("no {{c1::…}} handling exists in the renderer", html.contains("{{c1::"))
    }

    @Test
    fun `card scripts stay in the document even when JavaScript is disabled`() {
        val payload = "<p id=\"x\">before</p><script>document.getElementById('x').textContent='after';</script>"
        val enabled = build(payload, AnkiCardRenderConfig(javascriptPolicy = AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY))
        val disabled = build(payload, AnkiCardRenderConfig(javascriptPolicy = AnkiJavascriptPolicy.DISABLED))

        assertTrue(enabled.html.contains("<script>"))
        assertTrue(disabled.html.contains("<script>"))
        assertTrue(enabled.javascriptEnabled)
        assertFalse(disabled.javascriptEnabled)
    }

    // ------------------------------------------------------------------ CSS priority

    @Test
    fun `the renderer base CSS comes before the card so card CSS wins`() {
        val payload = "<style>.card{font-size:30px;text-align:right}</style><p>x</p>"
        val html = build(payload).html
        val baseIndex = html.indexOf("html,body{margin:0;padding:0}")
        val cardIndex = html.indexOf(".card{font-size:30px")
        assertTrue("base CSS must exist", baseIndex > 0)
        assertTrue("card CSS must exist", cardIndex > 0)
        assertTrue(
            "the card's own rules must come later in document order (STEP 24)",
            baseIndex < cardIndex
        )
    }

    @Test
    fun `the base stylesheet is minimal and never overrides authored typography`() {
        val css = AnkiCardDocumentBuilder.baseCss(nightMode = false)
        listOf("font-size", "font-family", "text-align", "position", "display:none", "line-height", "float")
            .forEach { assertFalse("base CSS must not set '$it'", css.contains(it)) }
        // The only sizing rule is the replaced-content guard of STEP 64 — nothing that would reflow a
        // table or a paragraph.
        assertEquals(1, Regex("max-width:100%").findAll(css).count())
        assertFalse("no unconditional width constraint", Regex("(?<!max-)width:").containsMatchIn(css))
        assertTrue(css.contains("margin:0"))
        assertTrue(css.contains("-webkit-text-size-adjust:100%"))
        // The only colors the renderer ever names are the scheme-following system colors, so a template
        // that sets its own foreground/background still wins (STEP 40/§41).
        assertEquals(
            setOf("background-color:canvas", "color:canvasText"),
            Regex("(?:background-)?color:[a-zA-Z]+").findAll(css).map { it.value }.toSet()
        )
        assertFalse("no hex color of our own", Regex("#[0-9a-fA-F]{3,8}").containsMatchIn(css))
        assertFalse("no rgba() of our own", css.contains("rgba("))
    }

    // ------------------------------------------------------------------ direction

    @Test
    fun `direction is configured, never hard-coded to ltr`() {
        assertEquals("ltr", build(QUESTION_HTML, AnkiCardRenderConfig(direction = CardTextDirection.LTR)).html.let { dirOf(it) })
        assertEquals("rtl", build(QUESTION_HTML, AnkiCardRenderConfig(direction = CardTextDirection.RTL)).html.let { dirOf(it) })
        assertEquals("auto", build(QUESTION_HTML, AnkiCardRenderConfig(direction = CardTextDirection.AUTO)).html.let { dirOf(it) })
        assertEquals(
            "AUTO is the default so the card's own direction wins",
            "auto",
            dirOf(build(QUESTION_HTML).html)
        )
    }

    @Test
    fun `an authored dir attribute inside the fragment is preserved`() {
        val payload = ARABIC_HTML
        val html = build(payload).html
        assertTrue(html.contains("dir=\"rtl\""))
        assertTrue(html.contains(payload))
    }

    private fun dirOf(html: String): String =
        Regex("<html dir=\"([a-z]+)\"").find(html)?.groupValues?.get(1)
            ?: error("no dir attribute on <html>")

    // ------------------------------------------------------------------ night mode

    @Test
    fun `night mode is a color scheme and a class, never an inversion`() {
        val dark = build(QUESTION_HTML, AnkiCardRenderConfig(nightMode = true)).html
        val light = build(QUESTION_HTML, AnkiCardRenderConfig(nightMode = false)).html

        assertTrue(dark.contains("color-scheme:dark"))
        assertTrue(light.contains("color-scheme:light"))
        assertTrue(dark.contains(AnkiCardDocument.BODY_CLASS_NIGHT_MODE))
        assertTrue(dark.contains(AnkiCardDocument.BODY_CLASS_NIGHT_MODE_CAMEL))
        assertFalse(light.contains(AnkiCardDocument.BODY_CLASS_NIGHT_MODE))

        // INV-ANKI-RENDER-15: no blanket inversion anywhere in the document.
        assertFalse(dark.contains("invert("))
        assertFalse(dark.contains("filter:"))
        assertFalse(light.contains("invert("))
    }

    @Test
    fun `night mode does not invent colors that would fight an authored card`() {
        val css = AnkiCardDocumentBuilder.baseCss(nightMode = true)
        // Scheme-following system colors only: no hard-coded hex that could clash with a template.
        assertTrue(css.contains("background-color:canvas"))
        assertTrue(css.contains("color:canvasText"))
        assertFalse(Regex("#[0-9a-fA-F]{3,8}").containsMatchIn(css))
    }

    // ------------------------------------------------------------------ verbatim passthrough

    @Test
    fun `a complete document is passed through verbatim instead of double-wrapped`() {
        val payload = "<!doctype html>\n<html dir=\"rtl\"><head><meta charset=\"utf-8\"></head>" +
            "<body class=\"card\"><p>سؤال</p></body></html>"
        val document = build(payload)

        assertTrue(document.verbatim)
        assertEquals(payload, document.html)
        assertEquals(1, Regex("<html[ >]").findAll(document.html).count())
        assertTrue(document.tokens.contains(AnkiCardDocument.TOKEN_VERBATIM_DOCUMENT))
        assertTrue("body classes are not claimed for a document we did not build", document.bodyClasses.isEmpty())
    }

    @Test
    fun `a body-only payload is treated as document-shaped too`() {
        val payload = "<body class=\"card\"><p>x</p></body>"
        val document = build(payload)
        assertTrue(document.verbatim)
        assertEquals(payload, document.html)
    }

    @Test
    fun `a fragment that merely mentions html tags is still wrapped`() {
        val payload = "<p>Escape &lt;html&gt; and &lt;body&gt; in a card about HTML.</p>"
        val document = build(payload)
        assertFalse(document.verbatim)
        assertTrue(document.html.startsWith("<!doctype html>"))
        assertTrue(document.html.contains(payload))
    }

    @Test
    fun `leading whitespace and BOM do not defeat document detection`() {
        assertTrue(AnkiCardDocumentBuilder.isDocumentShaped("\uFEFF  <!DOCTYPE html><html></html>"))
        assertTrue(AnkiCardDocumentBuilder.isDocumentShaped("\n\n<HTML lang=\"en\"></HTML>"))
        assertFalse(AnkiCardDocumentBuilder.isDocumentShaped("<div>html</div>"))
        assertFalse(AnkiCardDocumentBuilder.isDocumentShaped(""))
    }

    // ------------------------------------------------------------------ policy plumbing

    @Test
    fun `text zoom is carried so the WebView scales text instead of the CSS`() {
        val document = build(
            QUESTION_HTML,
            AnkiCardRenderConfig(textScale = 1.3f)
        )
        assertEquals(130, document.textZoomPercent)
        // Scaling must not be implemented by rewriting the card's font sizes.
        assertFalse(document.html.contains("font-size:130%"))
    }

    @Test
    fun `the base URL is the renderer's reserved origin, never a real public domain`() {
        val document = build(QUESTION_HTML)
        assertEquals(AnkiCardDocument.BASE_URL, document.baseUrl)
        assertTrue(document.baseUrl.endsWith(".invalid/"))
        assertFalse(document.baseUrl.contains("ankidroid"))
        assertFalse(document.baseUrl.contains("ankiweb"))
        assertFalse(document.baseUrl.startsWith("file:"))
        assertFalse(document.baseUrl.startsWith("about:"))
    }

    @Test
    fun `a blank payload is still a document, honestly empty`() {
        val document = build("")
        assertFalse(document.verbatim)
        assertEquals(0, document.payloadLength)
        assertTrue(document.html.contains("<body class=\"card\""))
    }

    @Test
    fun `mode never changes the document - CLEAN is a different surface, not a rewrite`() {
        val original = build(QUESTION_HTML, AnkiCardRenderConfig(mode = AnkiCardRenderMode.ORIGINAL))
        val clean = build(QUESTION_HTML, AnkiCardRenderConfig(mode = AnkiCardRenderMode.CLEAN))
        assertEquals(original.html, clean.html)
    }
}
