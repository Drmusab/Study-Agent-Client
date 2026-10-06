package com.studyagent.client.core.anki

/**
 * Deterministic crash-window seam. Production must use [NoCommitFaults].
 *
 * This is not a user-facing setting and is not read from configuration. The only way to trip a
 * point is to pass an injector into the coordinator from a test. A thrown [CommitFaultException]
 * is an injected crash, not a backend result.
 */
enum class CommitFaultPoint {
    BEFORE_LEDGER_CREATE,
    AFTER_LEDGER_CREATE,
    AFTER_PREPARED,
    AFTER_CALL_ENTERED,
    BEFORE_PROVIDER_CALL,
    AFTER_PROVIDER_MUTATION,
    BEFORE_RESPONSE_PERSIST,
    AFTER_RESPONSE_PERSIST,
    BEFORE_COMMITTED_PERSIST,
    AFTER_COMMITTED_PERSIST
}

fun interface CommitFaultInjector {
    suspend fun on(point: CommitFaultPoint)
}

object NoCommitFaults : CommitFaultInjector {
    override suspend fun on(point: CommitFaultPoint) = Unit
}

class CommitFaultException(val point: CommitFaultPoint) : RuntimeException("injected ${point.name}")

class ThrowingCommitFault(private val point: CommitFaultPoint) : CommitFaultInjector {
    override suspend fun on(hit: CommitFaultPoint) {
        if (hit == point) throw CommitFaultException(point)
    }
}

/**
 * GATE 11 checkpoint 19 — durable phase marker with correlation. [commitId] is the transaction
 * identity (never card content), so a phase marker can be joined with the session's own events.
 */
fun interface CommitPhaseSink {
    fun onPhase(phase: String, attempt: Int, commitId: ReviewCommitId)
}
