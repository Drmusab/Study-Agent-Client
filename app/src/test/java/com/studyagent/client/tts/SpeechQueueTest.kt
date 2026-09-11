package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.QueuePolicy
import com.studyagent.client.core.voice.tts.SpeechPriority
import com.studyagent.client.core.voice.tts.SpeechPurpose
import com.studyagent.client.core.voice.tts.SpeechQueue
import com.studyagent.client.core.voice.tts.SpeechRequest
import com.studyagent.client.core.voice.tts.SpeechResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §69: ordering, bounds, duplicates, and exactly-once completion semantics. */
class SpeechQueueTest {

    private fun req(
        id: String,
        purpose: SpeechPurpose = SpeechPurpose.QUESTION,
        priority: SpeechPriority = SpeechPriority.NORMAL,
        policy: QueuePolicy = QueuePolicy.APPEND,
        text: String = "text-$id"
    ) = SpeechRequest(id = id, text = text, purpose = purpose, priority = priority, queuePolicy = policy)

    private fun entry(queue: SpeechQueue, request: SpeechRequest) =
        request to CompletableDeferred<SpeechResult>().also { queue.enqueue(request, it) }

    @Test
    fun `priority ordering beats arrival order`() {
        val queue = SpeechQueue()
        entry(queue, req("low", priority = SpeechPriority.LOW))
        entry(queue, req("critical", priority = SpeechPriority.CRITICAL))
        entry(queue, req("normal"))

        assertEquals("critical", queue.poll()?.request?.id)
        assertEquals("normal", queue.poll()?.request?.id)
        assertEquals("low", queue.poll()?.request?.id)
    }

    @Test
    fun `same priority is fifo and deterministic`() {
        val queue = SpeechQueue()
        entry(queue, req("a"))
        entry(queue, req("b"))
        entry(queue, req("c"))
        assertEquals("a", queue.poll()?.request?.id)
        assertEquals("b", queue.poll()?.request?.id)
        assertEquals("c", queue.poll()?.request?.id)
    }

    @Test
    fun `duplicate id or same content is dropped`() {
        val queue = SpeechQueue()
        val first = req("dup", text = "same content")
        assertEquals(SpeechQueue.EnqueueResult.Enqueued, queue.enqueue(first, CompletableDeferred()))
        assertEquals(
            SpeechQueue.EnqueueResult.DuplicateDropped,
            queue.enqueue(req("other-id", text = "same content"), CompletableDeferred())
        )
        assertEquals(1, queue.size)
    }

    @Test
    fun `queue is bounded and non critical work is rejected when full`() {
        val queue = SpeechQueue(maxSize = 2)
        entry(queue, req("1"))
        entry(queue, req("2"))
        assertEquals(SpeechQueue.EnqueueResult.RejectedFull, queue.enqueue(req("3"), CompletableDeferred()))
    }

    @Test
    fun `critical request evicts lowest priority when full`() {
        val queue = SpeechQueue(maxSize = 2)
        val (_, lowCompletion) = entry(queue, req("low", priority = SpeechPriority.LOW))
        entry(queue, req("normal"))
        val result = queue.enqueue(req("critical", priority = SpeechPriority.CRITICAL), CompletableDeferred())
        assertTrue(result is SpeechQueue.EnqueueResult.EvictedLowest)
        // evicted entry completes Cancelled exactly once
        assertTrue(lowCompletion.isCompleted)
        assertEquals(SpeechResult.Cancelled, lowCompletion.getCompleted())
        assertEquals(2, queue.size)
    }

    @Test
    fun `drain completes every entry exactly once`() {
        val queue = SpeechQueue()
        val completions = (1..5).map {
            CompletableDeferred<SpeechResult>().also { queue.enqueue(req("r$it"), it) }
        }
        val drained = queue.drain(SpeechResult.Cancelled)
        assertEquals(5, drained.size)
        assertTrue(queue.isEmpty())
        completions.forEach {
            assertTrue(it.isCompleted)
            assertEquals(SpeechResult.Cancelled, it.getCompleted())
        }
        // a second drain touches nothing → nobody completes twice
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `poll on empty returns null`() {
        assertTrue(SpeechQueue().poll() == null)
    }
}
