package com.studyagent.client.audio

import com.studyagent.client.core.audio.AcousticProfile
import com.studyagent.client.core.audio.PhoneModeDiagnostics
import com.studyagent.client.core.audio.SelfEchoDetector
import com.studyagent.client.core.audio.TextEchoSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-echo observation and Phone Mode metrics (§18/§52/§53/§92/§110/§111).
 *
 * The detector is deliberately diagnostic-only: it never discards a transcript, because a
 * user may legitimately repeat the question's own words.
 */
class SelfEchoAndPhoneMetricsTest {

    private val question = "What are the indications for evacuation of an epidural hematoma?"

    @Test
    fun `recognizer returning the app's own question right after speech is flagged`() {
        val detector = SelfEchoDetector()
        detector.noteSpoken(question, endedAtMs = 10_000)

        val suspected = detector.isSuspectedSelfEcho(
            transcript = "What are the indications for evacuation of an epidural hematoma?",
            nowMs = 10_120
        )

        assertTrue(suspected)
    }

    @Test
    fun `a real answer is not flagged`() {
        val detector = SelfEchoDetector()
        detector.noteSpoken(question, endedAtMs = 10_000)

        val suspected = detector.isSuspectedSelfEcho(
            transcript = "Volume greater than thirty milliliters and midline shift",
            nowMs = 10_400
        )

        assertFalse(suspected)
    }

    @Test
    fun `nothing is flagged long after the app stopped talking`() {
        // A user legitimately repeating a term minutes later is not self-echo.
        val detector = SelfEchoDetector()
        detector.noteSpoken(question, endedAtMs = 10_000)

        assertFalse(detector.isSuspectedSelfEcho(question, nowMs = 40_000))
    }

    @Test
    fun `short spoken prompts are not used for echo detection`() {
        // §53: feedback like "Good." is short, and a spoken rating legitimately repeats it.
        val detector = SelfEchoDetector()
        detector.noteSpoken("Good.", endedAtMs = 5_000)

        assertFalse(detector.isSuspectedSelfEcho("Good", nowMs = 5_150))
    }

    @Test
    fun `suspected self echo never invalidates the turn`() {
        // The detector is a pure predicate: it has no access to a turn and cannot cancel one.
        val detector = SelfEchoDetector()
        detector.noteSpoken(question, endedAtMs = 1_000)
        assertTrue(detector.isSuspectedSelfEcho(question, 1_100))

        detector.clear()
        assertFalse(detector.isSuspectedSelfEcho(question, 1_100))
    }

    @Test
    fun `similarity is symmetric and bounded`() {
        assertEquals(
            TextEchoSimilarity.similarity("epidural hematoma evacuation", "epidural hematoma evacuation"),
            1.0,
            0.0001
        )
        assertEquals(0.0, TextEchoSimilarity.similarity("", "anything"), 0.0001)
        assertTrue(
            TextEchoSimilarity.similarity(
                "epidural hematoma evacuation indications",
                "indications evacuation hematoma epidural"
            ) > 0.9
        )
    }

    // ---------------------------------------------------------------- metrics (§110/§111)

    @Test
    fun `phone and headset turns are counted separately`() {
        val metrics = PhoneModeDiagnostics()

        metrics.recordListenTurn(AcousticProfile.PHONE_SPEAKER)
        metrics.recordListenTurn(AcousticProfile.PHONE_SPEAKER)
        metrics.recordListenTurn(AcousticProfile.HEADSET)

        assertEquals(2, metrics.metrics.value.phoneTurns)
        assertEquals(1, metrics.metrics.value.headsetTurns)
    }

    @Test
    fun `handoff latency keeps a running average`() {
        val metrics = PhoneModeDiagnostics()

        metrics.recordHandoffLatency(400)
        metrics.recordHandoffLatency(500)

        assertEquals(500L, metrics.metrics.value.lastHandoffLatencyMs)
        assertEquals(450L, metrics.metrics.value.avgHandoffLatencyMs)
    }

    @Test
    fun `a long phone session does not accumulate unbounded counters beyond their counts`() {
        // §105 (lightweight): 1000 turns must leave a bounded, countable state — no growing
        // queues, no per-turn listeners. The metrics object is the only thing that grows, and
        // only as numbers.
        val metrics = PhoneModeDiagnostics()

        repeat(1000) { turn ->
            metrics.recordListenTurn(AcousticProfile.PHONE_SPEAKER)
            metrics.recordHandoffLatency(450L + (turn % 7))
            if (turn % 100 == 0) metrics.recordSuspectedSelfEcho()
            if (turn % 250 == 0) metrics.recordNoSpeech()
        }

        val snapshot = metrics.metrics.value
        assertEquals(1000, snapshot.phoneTurns)
        assertEquals(1000, snapshot.handoffCount)
        assertEquals(10, snapshot.suggestedSelfEcho)
        assertEquals(4, snapshot.sttNoSpeech)
        assertTrue(snapshot.avgHandoffLatencyMs in 450L..456L)
    }

    @Test
    fun `metrics carry no transcript content`() {
        // §111: structural guarantee — the metrics type has no text fields at all.
        val fields = com.studyagent.client.core.audio.PhoneModeMetrics::class.java.declaredFields
            .map { it.name }

        assertTrue(fields.none { it.contains("text", ignoreCase = true) })
        assertTrue(fields.none { it.contains("transcript", ignoreCase = true) })
        assertTrue(fields.none { it.contains("answer", ignoreCase = true) })
    }
}
