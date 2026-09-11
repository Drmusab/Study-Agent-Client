package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.MedicalPronunciationProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §66: clinically important values must survive transformation — numbers are
 * never reworded or reordered, only relations/units are verbalized.
 */
class MedicalPronunciationProcessorTest {

    private val processor = MedicalPronunciationProcessor()

    private fun p(text: String) = processor.applyToEnglish(text)

    @Test
    fun `scores read as out of`() {
        assertEquals("G C S 15 out of 15", p("GCS 15/15"))
        assertEquals("5 out of 5 power", p("5/5 power"))
    }

    @Test
    fun `oxygen saturation`() {
        assertEquals("S P O two 98 percent", p("SpO2 98%"))
    }

    @Test
    fun `blood pressure`() {
        assertEquals("B P 120 over 80 millimeters of mercury", p("BP 120/80 mmHg"))
    }

    @Test
    fun `volume and length units`() {
        assertEquals("30 milliliters", p("30 mL"))
        assertEquals("5 millimeters", p("5 mm"))
        assertEquals("10 milligrams per kilogram", p("10 mg/kg"))
        assertEquals("2 milligrams I V", p("2 mg IV"))
    }

    @Test
    fun `electrolyte ions`() {
        assertEquals("sodium", p("Na+"))
        assertEquals("potassium 4.0", p("K+ 4.0"))
        assertEquals("calcium", p("Ca2+"))
        assertEquals("magnesium", p("Mg2+"))
    }

    @Test
    fun `vertebral levels`() {
        assertEquals("C6 to C7", p("C6-C7"))
        assertEquals("L4 to L5 disc", p("L4-L5 disc"))
    }

    @Test
    fun `common imaging and monitoring abbreviations spell out`() {
        assertEquals("C T brain", p("CT brain"))
        assertEquals("M R I", p("MRI"))
        assertEquals("I C P", p("ICP"))
        assertEquals("E D H", p("EDH"))
        assertEquals("S D H", p("SDH"))
        assertEquals("M L S greater than 5 millimeters", p("MLS >5 mm"))
    }

    @Test
    fun `comparison symbols verbalize without touching values`() {
        assertEquals("greater than 30 milliliters", p(">30 mL"))
        assertEquals("less than 5 millimeters", p("<5 mm"))
        assertEquals("greater than or equal to 3", p("≥3"))
    }

    @Test
    fun `numeric ranges verbalize but iso dates survive`() {
        assertEquals("range 20 to 30 milliequivalents", p("range 20-30 mEq"))
        assertEquals("Date 2024-09-11 review", p("Date 2024-09-11 review"))
        assertEquals("Follow-up on 31/12/2024", p("Follow-up on 31/12/2024"))
    }

    @Test
    fun `clinical shorthand`() {
        assertEquals("65 year old male with headache", p("65 y/o male w/ headache"))
        assertEquals("rule out stroke, history of migraine", p("r/o stroke, h/o migraine"))
    }

    @Test
    fun `ordinary prose is not corrupted`() {
        assertEquals("It is normal to feel tired.", p("It is normal to feel tired."))
        // lower-case "ct" and the word "cat" are not abbreviations
        assertEquals("the cat sat", p("the cat sat"))
    }

    @Test
    fun `full spec example round trip`() {
        assertEquals(
            "G C S 13 out of 15, S P O two 98 percent, B P 140 over 90 millimeters of mercury, " +
                "C T shows E D H greater than 30 milliliters with M L S greater than 5 millimeters.",
            p("GCS 13/15, SpO2 98%, BP 140/90 mmHg, CT shows EDH >30 mL with MLS >5 mm.")
        )
    }

    @Test
    fun `custom rules run before built-ins`() {
        val custom = MedicalPronunciationProcessor(
            customRules = listOf(
                com.studyagent.client.core.voice.tts.PronunciationRule(
                    Regex("\\bEDH\\b"), "epidural hematoma", "user expansion"
                )
            )
        )
        assertEquals("epidural hematoma confirmed", custom.applyToEnglish("EDH confirmed"))
    }
}
