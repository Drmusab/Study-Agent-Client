package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ServerMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized request/response correlation with bounded lifecycle.
 * Replaces per-repository ad-hoc request maps.
 *
 * Features:
 * - messageId as correlation key + in_reply_to support
 * - Purpose-specific timeouts
 * - Bounded map (completed/timed-out removed)
 * - Cancellation support
 */
class RequestCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
) {
    private val tag = "RequestCoordinator"

    private data class PendingRequest(
        val messageId: String,
        val requestType: String,
        val deferred: CompletableDeferred<ServerMessage>,
        val createdAtMs: Long,
        val timeoutMs: Long,
        var timeoutJob: Job? = null
    )

    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val mutex = Mutex()
    private val totalCompleted = AtomicLong(0)
    private val totalTimedOut = AtomicLong(0)

    // Purpose-specific timeouts
    object Timeouts {
        const val HANDSHAKE_MS = 8_000L
        const val AUTH_MS = 5_000L
        const val DASHBOARD_MS = 8_000L
        const val DECKS_MS = 5_000L
        const val CONFIG_MS = 5_000L
        const val SESSION_ACTION_MS = 5_000L
        const val EVALUATION_MS = 30_000L // LLM may take longer
        const val RATING_MS = 5_000L
        const val DEFAULT_MS = 8_000L
    }

    fun timeoutFor(type: String): Long = when (type) {
        "hello", "welcome" -> Timeouts.HANDSHAKE_MS
        "authenticate" -> Timeouts.AUTH_MS
        "request_dashboard" -> Timeouts.DASHBOARD_MS
        "request_decks" -> Timeouts.DECKS_MS
        "request_study_config", "update_study_config" -> Timeouts.CONFIG_MS
        "start_session", "pause_session", "resume_session", "end_session",
        "repeat_question", "request_hint", "request_explanation", "request_answer", "skip_card" -> Timeouts.SESSION_ACTION_MS
        "submit_answer" -> Timeouts.EVALUATION_MS
        "rate_card" -> Timeouts.RATING_MS
        else -> Timeouts.DEFAULT_MS
    }

    /**
     * Register a request and get a deferred for its response.
     * Caller should send the message after registration.
     */
    suspend fun register(
        messageId: String,
        requestType: String,
        timeoutMs: Long = timeoutFor(requestType)
    ): CompletableDeferred<ServerMessage> {
        val deferred = CompletableDeferred<ServerMessage>()
        val req = PendingRequest(
            messageId = messageId,
            requestType = requestType,
            deferred = deferred,
            createdAtMs = System.currentTimeMillis(),
            timeoutMs = timeoutMs
        )
        req.timeoutJob = scope.launch {
            delay(timeoutMs)
            val removed = pending.remove(messageId)
            if (removed != null) {
                totalTimedOut.incrementAndGet()
                AppLogger.w(tag, "Request timeout type=$requestType id=${messageId.take(8)} after ${timeoutMs}ms")
                deferred.completeExceptionally(
                    RequestTimeoutException(requestType, timeoutMs, messageId)
                )
            }
        }
        pending[messageId] = req
        // Bounded: prevent leak if map grows too large (should not happen with timeouts, but safety)
        if (pending.size > 200) {
            val oldest = pending.values.minByOrNull { it.createdAtMs }
            oldest?.let {
                pending.remove(it.messageId)
                it.timeoutJob?.cancel()
                it.deferred.cancel()
            }
        }
        return deferred
    }

    /**
     * Called when a server message arrives. Attempts to correlate via in_reply_to or messageId.
     * Returns true if correlated to a pending request.
     */
    suspend fun onServerMessage(message: ServerMessage): Boolean {
        val inReplyTo = message.inReplyTo
        val msgId = message.messageId

        // Prefer in_reply_to for correlation (v2), fallback to messageId echo (v1 compat)
        val correlationId = inReplyTo ?: msgId

        if (correlationId != null) {
            val req = pending.remove(correlationId)
            if (req != null) {
                req.timeoutJob?.cancel()
                totalCompleted.incrementAndGet()
                if (!req.deferred.isCompleted) {
                    req.deferred.complete(message)
                }
                return true
            }
        }

        // Also check if server echoed request id in messageId for management responses
        // (some servers echo request id as their messageId)
        return false
    }

    suspend fun cancel(messageId: String) {
        val req = pending.remove(messageId)
        req?.timeoutJob?.cancel()
        req?.deferred?.cancel()
    }

    suspend fun cancelAll() {
        mutex.withLock {
            pending.values.forEach {
                it.timeoutJob?.cancel()
                it.deferred.cancel()
            }
            pending.clear()
        }
    }

    fun pendingCount(): Int = pending.size

    fun stats(): RequestStats = RequestStats(
        pending = pending.size,
        completed = totalCompleted.get(),
        timedOut = totalTimedOut.get()
    )

    data class RequestStats(
        val pending: Int,
        val completed: Long,
        val timedOut: Long
    )

    class RequestTimeoutException(
        val requestType: String,
        val elapsedMs: Long,
        val messageId: String
    ) : Exception("Timeout $requestType id=${messageId.take(8)} after ${elapsedMs}ms")
}

/**
 * Extension to extract in_reply_to from ServerMessage if present.
 * Added via wrapper property - actual field added to ProtocolMessages.
 */
val ServerMessage.inReplyTo: String?
    get() = when (this) {
        is ServerMessage.Welcome -> inReplyTo
        else -> (this as? HasInReplyTo)?.inReplyTo
    }

interface HasInReplyTo {
    val inReplyTo: String?
}
