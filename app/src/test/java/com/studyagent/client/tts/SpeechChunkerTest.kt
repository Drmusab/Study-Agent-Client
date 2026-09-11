package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.SpeechChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §67: chunk boundaries are safe and nothing is lost or duplicated. */
class SpeechChunkerTest {

    private val chunker = SpeechChunker()

    private fun norm(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun assertLossless(input: String, chunks: List<String>, max: Int) {
        assertEquals("content loss/duplication detected", norm(input), norm(chunks.joinToString(" ")))
        chunks.forEach { assertTrue("chunk $it exceeds max", it.length <= max) }
    }

    @Test
    fun `short sentence is one chunk`() {
        assertEquals(listOf("Hello world."), chunker.split("Hello world.", 100))
    }

    @Test
    fun `max length boundary`() {
        val input = "Sentence one. Sentence two. Sentence three."
        val chunks = chunker.split(input, 20)
        assertLossless(input, chunks, 20)
        assertEquals(listOf("Sentence one.", "Sentence two.", "Sentence three."), chunks)
    }

    @Test
    fun `very long explanation chunkifies semantically`() {
        val sentence = "This is a fairly long explanation about the indications for epidural hematoma evacuation."
        val input = List(60) { "$sentence Note $it of them." }.joinToString(" ")
        val chunks = chunker.split(input, 200)
        assertTrue(chunks.size > 5)
        assertLossless(input, chunks, 200)
    }

    @Test
    fun `multiple paragraphs`() {
        val input = "Para one sentence.\n\nPara two has two sentences. Yes it does."
        val chunks = chunker.split(input, 40)
        assertLossless(input, chunks, 40)
    }

    @Test
    fun `abbreviations and decimals never split mid-token`() {
        val sentences = chunker.splitSentences("Dr. Smith gave 3.5 mg e.g. at noon. The patient recovered.")
        assertEquals(listOf("Dr. Smith gave 3.5 mg e.g. at noon.", "The patient recovered."), sentences)
    }

    @Test
    fun `medical tokens survive clause splitting`() {
        val input = "Give 10 mg/kg of the drug, then check GCS 15/15, and monitor C6-C7 stability."
        val chunks = chunker.split(input, 35)
        assertLossless(input, chunks, 35)
        // commas preserved as spoken micropauses
        assertTrue(chunks.joinToString(" ").contains("drug,"))
    }

    @Test
    fun `no punctuation falls back to word and hard splits`() {
        val words = List(80) { "word$it" }.joinToString(" ")
        val chunks = chunker.split(words, 50)
        assertLossless(words, chunks, 50)

        val solid = "a".repeat(500)
        val hard = chunker.split(solid, 100)
        assertEquals(5, hard.size)
        assertEquals(solid, hard.joinToString(""))
    }

    @Test
    fun `mixed language chunk retains order`() {
        val input = "جرعة الدواء عشرة مليغرامات مرتين يوميا لمدة أسبوع كامل مع مراقبة الأعراض الجانبية بعناية"
        val chunks = chunker.split(input, 30)
        assertLossless(input, chunks, 30)
    }

    @Test
    fun `arabic terminal marks are sentence boundaries`() {
        val sentences = chunker.splitSentences("ما هو العلاج؟ العلاج هو الجراحة. شكرًا")
        assertEquals(listOf("ما هو العلاج؟", "العلاج هو الجراحة.", "شكرًا"), sentences)
    }
}
