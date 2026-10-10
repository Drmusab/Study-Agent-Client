package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiCompatibilityLevel
import com.studyagent.client.core.anki.AnkiTemplateCompatibilityAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiTemplateCompatibilityAnalyzerTest {

    @Test
    fun `plain html css classifies as FULL`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}",
            afmt = "{{FrontSide}}<hr id=answer>{{Back}}",
            css = ".card { font-family: sans-serif; }"
        )
        assertEquals(AnkiCompatibilityLevel.FULL, result.level)
        assertFalse(result.hasJavaScript)
        assertTrue(result.hasFrontSide)
    }

    @Test
    fun `script tag moves to SUPPORTED_WITH_LIMITATIONS`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}<script>document.body.classList.add('x')</script>"
        )
        assertEquals(AnkiCompatibilityLevel.SUPPORTED_WITH_LIMITATIONS, result.level)
        assertTrue(result.hasJavaScript)
        assertFalse(result.hasAnkiBridge)
    }

    @Test
    fun `anki bridge classifies as DISPLAY_ONLY`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}<script>pycmd('showAnswer')</script>"
        )
        assertEquals(AnkiCompatibilityLevel.DISPLAY_ONLY, result.level)
        assertTrue(result.hasAnkiBridge)
        assertTrue(result.hasJavaScript)
    }

    @Test
    fun `AnkiDroidJS API is detected as bridge`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}<script>if (window.AnkiDroidJS) { AnkiDroidJS.ankidroidGetUrl() }</script>"
        )
        assertEquals(AnkiCompatibilityLevel.DISPLAY_ONLY, result.level)
        assertTrue(result.hasAnkiBridge)
    }

    @Test
    fun `cloze directive is detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{cloze:Text}}"
        )
        assertTrue(result.hasClozeDirective)
        // Cloze alone without JS/etc should still be FULL (cloze rendering is backend-owned;
        // the directive in source is just metadata).
        assertEquals(AnkiCompatibilityLevel.FULL, result.level)
    }

    @Test
    fun `type answer filter detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}",
            afmt = "{{FrontSide}}<hr id=answer>{{type:Back}}"
        )
        assertTrue(result.hasTypeAnswer)
        assertEquals(AnkiCompatibilityLevel.SUPPORTED_WITH_LIMITATIONS, result.level)
    }

    @Test
    fun `media references detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}<img src=\"cat.jpg\">"
        )
        assertTrue(result.hasMediaReferences)
    }

    @Test
    fun `latex markers detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "[latex]\\\\frac{1}{2}[/latex]"
        )
        assertTrue(result.hasLatex)
        assertEquals(AnkiCompatibilityLevel.SUPPORTED_WITH_LIMITATIONS, result.level)
    }

    @Test
    fun `custom font detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}",
            css = "@font-face { font-family: MyFont; src: url(\"_MyFont.ttf\"); } .card { font-family: MyFont; }"
        )
        assertTrue(result.hasCustomFonts)
        assertEquals(AnkiCompatibilityLevel.SUPPORTED_WITH_LIMITATIONS, result.level)
    }

    @Test
    fun `rtl signals detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "<div dir=\"rtl\">{{Front}}</div>"
        )
        assertTrue(result.hasRtlSignals)
        assertEquals(AnkiCompatibilityLevel.SUPPORTED_WITH_LIMITATIONS, result.level)
    }

    @Test
    fun `empty source returns NO_SOURCE`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze()
        assertEquals(AnkiCompatibilityLevel.UNKNOWN, result.level)
        assertTrue(result.notes.contains("template_source_unavailable"))
    }

    @Test
    fun `blank strings return NO_SOURCE`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(qfmt = "", afmt = null, css = "   ")
        assertEquals(AnkiCompatibilityLevel.UNKNOWN, result.level)
    }

    @Test
    fun `external urls in combination with js classify DISPLAY_ONLY`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}<script src=\"https://example.com/ext.js\"></script>"
        )
        assertEquals(AnkiCompatibilityLevel.DISPLAY_ONLY, result.level)
        assertTrue(result.hasExternalUrls)
    }

    @Test
    fun `conditionals are detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{#Hint}}{{Hint}}{{/Hint}}{{^Hint}}{{Front}}{{/Hint}}"
        )
        assertTrue(result.hasConditionals)
    }

    @Test
    fun `hint filter detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}{{hint:Hint}}"
        )
        assertTrue(result.hasHintField)
    }

    @Test
    fun `mathjax reference detected`() {
        val result = AnkiTemplateCompatibilityAnalyzer.analyze(
            qfmt = "{{Front}}<script>MathJax.Hub.Queue(['Typeset', MathJax.Hub])</script>"
        )
        assertTrue(result.hasMathJax)
    }
}
