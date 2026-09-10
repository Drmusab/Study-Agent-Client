package com.studyagent.client

import com.studyagent.client.core.network.ReconnectController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectControllerTest {

    @Test
    fun testExponentialBackoffIncreases() {
        val controller = ReconnectController(
            initialDelayMs = 1000L,
            maxDelayMs = 20000L,
            factor = 2.0,
            jitterFraction = 0.0 // disable jitter for deterministic math
        )

        val delay1 = controller.getNextDelayMs()
        assertEquals(1, controller.currentAttempt)
        assertEquals(1000L, delay1)

        val delay2 = controller.getNextDelayMs()
        assertEquals(2, controller.currentAttempt)
        assertEquals(2000L, delay2)

        val delay3 = controller.getNextDelayMs()
        assertEquals(3, controller.currentAttempt)
        assertEquals(4000L, delay3)

        val delay4 = controller.getNextDelayMs()
        assertEquals(4, controller.currentAttempt)
        assertEquals(8000L, delay4)

        val delay5 = controller.getNextDelayMs()
        assertEquals(5, controller.currentAttempt)
        assertEquals(16000L, delay5)

        val delay6 = controller.getNextDelayMs()
        assertEquals(6, controller.currentAttempt)
        assertEquals(20000L, delay6) // capped at maxDelayMs
    }

    @Test
    fun testReset() {
        val controller = ReconnectController()
        controller.getNextDelayMs()
        controller.getNextDelayMs()
        assertEquals(2, controller.currentAttempt)

        controller.reset()
        assertEquals(0, controller.currentAttempt)
    }
}
