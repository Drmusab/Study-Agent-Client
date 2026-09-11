package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.SpeechTextPreprocessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §66: markup cleanup while preserving medically meaningful symbols. */
class SpeechTextPreprocessorTest {

    private val preprocessor = SpeechTextPreprocessor()

    @Test
    fun `inline html tags are stripped without reading tags aloud`() {
        assertEquals(
            "What is the treatment of epidural hematoma?",
            preprocessor.preprocess("<b>What</b> is the <i>treatment</i> of epidural hematoma?")
        )
    }

    @Test
    fun `block tags become natural pauses`() {
        assertEquals(
            "Line one. Line two.",
            preprocessor.preprocess("<div>Line one</div><div>Line two</div>")
        )
        assertEquals(
            "Items: first. second.",
            preprocessor.preprocess("Items:<ul><li>first</li><li>second</li></ul>")
        )
    }

    @Test
    fun `html entities and nbsp are decoded`() {
        assertEquals(
            "CT shows EDH >30 mL with MLS <5 mm.",
            preprocessor.preprocess("CT shows EDH &gt;30 mL with MLS &lt;5 mm.")
        )
        assertEquals("a b", preprocessor.preprocess("a&nbsp;b"))
        assertEquals("a b", preprocessor.preprocess("a&#160;b"))
    }

    @Test
    fun `comparison operators in medical text survive cleanup`() {
        // Naive angle-bracket stripping would destroy "< 5 mm" — must not happen.
        assertEquals(
            "Shift < 5 mm and volume > 30 mL",
            preprocessor.preprocess("Shift < 5 mm and volume > 30 mL")
        )
    }

    @Test
    fun `anki cloze markers are unwrapped`() {
        assertEquals(
            "epidural hematoma is",
            preprocessor.preprocess("{{c1::epidural hematoma::bleeding}} is")
        )
    }

    @Test
    fun `arabic text passes through unchanged`() {
        val arabic = "ما هي دواعي إجلاء الورم الدموي فوق الجافية؟"
        assertEquals(arabic, preprocessor.preprocess(arabic))
    }

    @Test
    fun `whitespace collapses deterministically`() {
        assertEquals("a b c", preprocessor.preprocess("  a\t b\n\nc  "))
    }

    @Test
    fun `blank stays blank`() {
        assertTrue(preprocessor.preprocess("   ").isEmpty())
    }
}
