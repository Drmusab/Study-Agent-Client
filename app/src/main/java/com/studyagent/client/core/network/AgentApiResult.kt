package com.studyagent.client.core.network

/**
 * Typed API result - avoids reducing every request to Boolean.
 */
sealed interface AgentApiResult<out T> {
    data class Success<T>(val value: T, val messageId: String? = null, val sessionRevision: Long? = null) : AgentApiResult<T>
    data class Rejected(val error: AgentApiError) : AgentApiResult<Nothing>
    data class Timeout(val requestType: String, val elapsedMs: Long, val messageId: String) : AgentApiResult<Nothing>
    data class Disconnected(val reason: String? = null) : AgentApiResult<Nothing>
    data class ProtocolFailure(val reason: String, val cause: Throwable? = null) : AgentApiResult<Nothing>

    val isSuccess: Boolean
        get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun exceptionOrNull(): Throwable? = when (this) {
        is Rejected -> Exception("${error.code}: ${error.message}")
        is Timeout -> Exception("Timeout $requestType after ${elapsedMs}ms")
        is Disconnected -> Exception("Disconnected: $reason")
        is ProtocolFailure -> cause ?: Exception(reason)
        is Success -> null
    }
}

inline fun <T, R> AgentApiResult<T>.map(transform: (T) -> R): AgentApiResult<R> = when (this) {
    is AgentApiResult.Success -> AgentApiResult.Success(transform(value), messageId, sessionRevision)
    is AgentApiResult.Rejected -> this
    is AgentApiResult.Timeout -> this
    is AgentApiResult.Disconnected -> this
    is AgentApiResult.ProtocolFailure -> this
}

inline fun <T> AgentApiResult<T>.onSuccess(action: (T) -> Unit): AgentApiResult<T> {
    if (this is AgentApiResult.Success) action(value)
    return this
}

inline fun <T> AgentApiResult<T>.onFailure(action: (AgentApiResult<T>) -> Unit): AgentApiResult<T> {
    if (this !is AgentApiResult.Success) action(this)
    return this
}
