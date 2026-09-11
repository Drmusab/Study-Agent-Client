package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.LanguageHint
import com.studyagent.client.core.voice.tts.MixedLanguageSegmenter
import com.studyagent.client.core.voice.tts.SegmentLanguage
import com.studyagent.client.core.voice.tts.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §66: mixed Arabic/English segmentation (also validates no char loss/duplication). */
class MixedLanguageSegmenterTest {

    private val segmenter = MixedLanguageSegmenter()

    @Test
    fun `english only`() {
        assertEquals(
            listOf(TextSegment("What is the treatment?", SegmentLanguage.ENGLISH)),
            segmenter.segment("What is the treatment?")
        )
    }

    @Test
    fun `arabic only`() {
        assertEquals(
            listOf(TextSegment("ما هو العلاج؟", SegmentLanguage.ARABIC)),
            segmenter.segment("ما هو العلاج؟")
        )
    }

    @Test
    fun `mixed medical sentence switches voices per run`() {
        val segments = segmenter.segment("المريض لديه epidural hematoma مع midline shift أكثر")
        assertEquals(
            listOf(
                TextSegment("المريض لديه", SegmentLanguage.ARABIC),
                TextSegment("epidural hematoma", SegmentLanguage.ENGLISH),
                TextSegment("مع", SegmentLanguage.ARABIC),
                TextSegment("midline shift", SegmentLanguage.ENGLISH),
                TextSegment("أكثر", SegmentLanguage.ARABIC)
            ),
            segments
        )
    }

    @Test
    fun `question with embedded english medical term`() {
        val segments = segmenter.segment("ما هي indications for surgery؟")
        assertEquals(SegmentLanguage.ARABIC, segments[0].language)
        assertEquals(SegmentLanguage.ENGLISH, segments[1].language)
        assertEquals("ما هي", segments[0].text)
        assertEquals("indications for surgery", segments[1].text)
    }

    @Test
    fun `language hint overrides detection`() {
        assertEquals(
            listOf(TextSegment("ما هو العلاج؟", SegmentLanguage.ENGLISH)),
            segmenter.segment("ما هو العلاج؟", hint = LanguageHint.ENGLISH)
        )
        assertEquals(
            listOf(TextSegment("What is this?", SegmentLanguage.ARABIC)),
            segmenter.segment("What is this?", hint = LanguageHint.ARABIC)
        )
    }

    @Test
    fun `disabled autodetection yields single dominant segment`() {
        val segments = segmenter.segment("مع epidural hematoma مع", autoDetect = false)
        assertEquals(1, segments.size)
        assertEquals(SegmentLanguage.ARABIC, segments[0].language)
    }

    @Test
    fun `segmentation is lossless and ordered`() {
        val source = "المريض لديه epidural hematoma مع midline shift أكثر من 5 mm"
        val joined = segmenter.segment(source).joinToString(" ") { it.text }
        fun norm(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertEquals(norm(source), norm(joined))
    }

    @Test
    fun `numbers and punctuation never form own voice segments`() {
        val segments = segmenter.segment("جرعة 10 mg/kg مرتين")
        // "10 " neutral glues to the Arabic run; "mg/kg" is the only English run.
        assertEquals(3, segments.size)
        assertEquals(SegmentLanguage.ARABIC, segments[0].language)
        assertEquals(SegmentLanguage.ENGLISH, segments[1].language)
        assertEquals(SegmentLanguage.ARABIC, segments[2].language)
        assertEquals("جرعة 10", segments[0].text)
        assertEquals("mg/kg", segments[1].text)
    }

    @Test
    fun `empty input`() {
        assertTrue(segmenter.segment("   ").isEmpty())
    }
}
