package com.studyagent.client.core.voice.tts

import kotlinx.coroutines.CompletableDeferred

/**
 * Bounded priority queue of pending (not yet speaking) [SpeechRequest]s.
 *
 * Ordering: [SpeechPriority] first, arrival order second — deterministic for
 * identical inputs. Bounded so a misbehaving producer can never grow memory
 * without limit (§29/§76): when full, an incoming CRITICAL request evicts the
 * lowest-priority tail; anything else is rejected so the caller gets a typed
 * QUEUE_FULL failure instead of silent loss.
 *
 * This type holds *pending* work only. The orchestrator owns the in-flight
 * request and applies REPLACE / INTERRUPT cancellation to it.
 */
class SpeechQueue(
    private val maxSize: Int = DEFAULT_MAX_SIZE
) {

    class Entry(
        val request: SpeechRequest,
        val completion: CompletableDeferred<SpeechResult>,
        val sequence: Long
    ) : Comparable<Entry> {
        override fun compareTo(other: Entry): Int {
            val byPriority = request.priority.rank.compareTo(other.request.priority.rank)
            return if (byPriority != 0) byPriority else sequence.compareTo(other.sequence)
        }
    }

    sealed interface EnqueueResult {
        data object Enqueued : EnqueueResult

        /** An identical request (same id or semantic key) already exists → dropped. */
        data object DuplicateDropped : EnqueueResult

        /** Queue is full and the request was not critical enough to evict. */
        data object RejectedFull : EnqueueResult

        /** Enqueued by evicting a lower-priority pending request. */
        data class EvictedLowest(val evicted: Entry) : EnqueueResult
    }

    private val entries = ArrayList<Entry>()
    private var nextSequence = 0L

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun peek(): Entry? = entries.firstOrNull()

    /** Deterministic next request: highest priority, oldest first. */
    fun poll(): Entry? {
        val best = entries.minOrNull() ?: return null
        entries.remove(best)
        return best
    }

    fun enqueue(request: SpeechRequest, completion: CompletableDeferred<SpeechResult>): EnqueueResult {
        if (containsDuplicateOf(request)) return EnqueueResult.DuplicateDropped
        val entry = Entry(request, completion, nextSequence++)
        if (entries.size < maxSize) {
            entries += entry
            return EnqueueResult.Enqueued
        }
        // Full — CRITICAL work may evict the *worst* pending entry (lowest priority, newest).
        if (request.priority == SpeechPriority.CRITICAL) {
            // Entry's natural order is rank-ascending/sequence-ascending (poll takes the MIN:
            // highest priority, oldest). The MAX is therefore the eviction target: the lowest
            // priority, and among equals the newest arrival. An inverted comparator here would
            // evict the BEST pending entry and keep the worst.
            val worst = entries.maxOrNull()
            if (worst != null && worst.request.priority != SpeechPriority.CRITICAL) {
                entries.remove(worst)
                // The queue owns the completion of every entry it removes (same rule as drain):
                // an evicted caller is resolved exactly once here, so no call site can forget
                // it and leave a speak() hanging forever. complete() is idempotent — an
                // orchestrator that also completes the evicted entry is a harmless no-op.
                worst.completion.complete(SpeechResult.Cancelled)
                entries += entry
                return EnqueueResult.EvictedLowest(worst)
            }
        }
        return EnqueueResult.RejectedFull
    }

    fun containsDuplicateOf(request: SpeechRequest): Boolean =
        entries.any { it.request.id == request.id || it.request.semanticKey == request.semanticKey }

    /** Remove everything; each entry is completed [result] exactly once. Returns dropped entries. */
    fun drain(result: SpeechResult = SpeechResult.Cancelled): List<Entry> {
        val all = entries.toList()
        entries.clear()
        all.forEach { it.completion.complete(result) }
        return all
    }

    fun snapshot(): List<Entry> = entries.sorted()

    companion object {
        const val DEFAULT_MAX_SIZE = 8
    }
}
