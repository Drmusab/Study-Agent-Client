package com.studyagent.client.data.anki.ankidroid

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATE 13 — the **one** physical-write permit for AnkiDroid provider mutations.
 *
 * GATE 11 introduced a single write permit inside `DefaultAnkiDroidRatingGateway` because the
 * pinned v2.24.1 provider answers the *selected deck's queue front* rather than an addressed card,
 * and because a provider call that times out keeps running after the caller stops waiting. GATE 13
 * adds a second mutation family (bury/suspend) that touches the same scheduler; two independent
 * mutexes would be two writers, and "one writer" would quietly stop being true.
 *
 * This type is that single permit, shared by construction: the composition root hands the *same*
 * instance to the rating gateway and to the reviewer-action gateway, so an answer can never overlap
 * a bury/suspend (and vice versa), and `writeInFlight` answers for both.
 *
 * It deliberately carries no policy: acquiring, releasing and asking whether a write is in flight.
 * Timeouts are the caller's business because "how long a rating waits" and "how long a bury waits"
 * are different questions with different consequences.
 */
internal class AnkiDroidWritePermit {

    private val mutex = Mutex()

    /**
     * True while a physical provider write issued by *any* gateway sharing this permit has not
     * returned yet. While true, "no change observed" is never evidence of "not applied" — the call
     * may still land (GATE 11 §25, GATE 13 §31).
     */
    val inFlight: Boolean get() = mutex.isLocked

    /**
     * Bounded wait for the permit. `false` means the wait timed out *before* anything was issued,
     * which is always provably pre-dispatch, so a caller may report a refusal rather than an
     * unknown outcome.
     */
    suspend fun acquire(timeoutMs: Long): Boolean {
        var acquired = false
        withTimeoutOrNull(timeoutMs) {
            mutex.lock()
            acquired = true
        }
        return acquired
    }

    /** Releases the permit. Only the holder of a successful [acquire] may call this. */
    fun release() {
        mutex.unlock()
    }
}
