package com.studyagent.client.core.network

/**
 * Structured API errors with stable machine-readable codes.
 * UI maps codes to localized user messages, never parses English strings.
 */
data class AgentApiError(
    val code: String,
    val category: Category,
    val message: String,
    val details: String? = null,
    val retryable: Boolean = false,
    val inReplyTo: String? = null,
    val sessionRevision: Long? = null
) {
    enum class Category {
        AUTH,
        PROTOCOL,
        DEPENDENCY,
        SESSION,
        VALIDATION,
        RATE_LIMIT,
        INTERNAL,
        UNKNOWN
    }

    companion object {
        // Auth
        const val AUTH_REQUIRED = "AUTH_REQUIRED"
        const val AUTH_INVALID = "AUTH_INVALID"
        const val AUTH_EXPIRED = "AUTH_EXPIRED"

        // Protocol
        const val PROTOCOL_UNSUPPORTED = "PROTOCOL_UNSUPPORTED"
        const val CAPABILITY_UNSUPPORTED = "CAPABILITY_UNSUPPORTED"
        const val INCOMPATIBLE_VERSION = "INCOMPATIBLE_VERSION"

        // Dependency
        const val ANKI_NOT_RUNNING = "ANKI_NOT_RUNNING"
        const val ANKICONNECT_UNAVAILABLE = "ANKICONNECT_UNAVAILABLE"
        const val DECK_NOT_FOUND = "DECK_NOT_FOUND"
        const val CARD_NOT_FOUND = "CARD_NOT_FOUND"
        const val LLM_UNAVAILABLE = "LLM_UNAVAILABLE"
        const val LLM_TIMEOUT = "LLM_TIMEOUT"

        // Session
        const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
        const val SESSION_ALREADY_ACTIVE = "SESSION_ALREADY_ACTIVE"
        const val SESSION_CONFLICT = "SESSION_CONFLICT"
        const val STALE_SESSION_REVISION = "STALE_SESSION_REVISION"
        const val STALE_REVIEW_TURN = "STALE_REVIEW_TURN"
        const val CONFIG_REJECTED = "CONFIG_REJECTED"

        // Rate limiting
        const val RATE_LIMITED = "RATE_LIMITED"

        // Validation
        const val INVALID_REQUEST = "INVALID_REQUEST"

        // Internal
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
        const val TIMEOUT = "TIMEOUT"
        const val DISCONNECTED = "DISCONNECTED"
        const val PROTOCOL_FAILURE = "PROTOCOL_FAILURE"

        fun fromCode(code: String?, message: String, details: String? = null): AgentApiError {
            val normalizedCode = code ?: INTERNAL_ERROR
            val category = when (normalizedCode) {
                AUTH_REQUIRED, AUTH_INVALID, AUTH_EXPIRED -> Category.AUTH
                PROTOCOL_UNSUPPORTED, CAPABILITY_UNSUPPORTED, INCOMPATIBLE_VERSION -> Category.PROTOCOL
                ANKI_NOT_RUNNING, ANKICONNECT_UNAVAILABLE, DECK_NOT_FOUND, CARD_NOT_FOUND,
                LLM_UNAVAILABLE, LLM_TIMEOUT -> Category.DEPENDENCY
                SESSION_NOT_FOUND, SESSION_ALREADY_ACTIVE, SESSION_CONFLICT,
                STALE_SESSION_REVISION, STALE_REVIEW_TURN, CONFIG_REJECTED -> Category.SESSION
                RATE_LIMITED -> Category.RATE_LIMIT
                INVALID_REQUEST -> Category.VALIDATION
                INTERNAL_ERROR, TIMEOUT, DISCONNECTED, PROTOCOL_FAILURE -> Category.INTERNAL
                else -> Category.UNKNOWN
            }
            val retryable = when (normalizedCode) {
                ANKI_NOT_RUNNING, ANKICONNECT_UNAVAILABLE, LLM_UNAVAILABLE, LLM_TIMEOUT,
                RATE_LIMITED, TIMEOUT, DISCONNECTED -> true
                else -> false
            }
            return AgentApiError(
                code = normalizedCode,
                category = category,
                message = message,
                details = details,
                retryable = retryable
            )
        }
    }

    val userMessage: String
        get() = when (code) {
            ANKI_NOT_RUNNING -> "Open Anki on your PC and make sure AnkiConnect is enabled."
            ANKICONNECT_UNAVAILABLE -> "AnkiConnect is not reachable. Install/enable AnkiConnect add-on."
            DECK_NOT_FOUND -> "Deck not found. Check deck selection in Control Center."
            CARD_NOT_FOUND -> "Card not found. It may have been deleted."
            LLM_UNAVAILABLE -> "AI evaluator is unavailable. Check PC Agent AI settings."
            LLM_TIMEOUT -> "AI evaluation timed out. Try again."
            AUTH_REQUIRED -> "Authentication required. Pair with PC Agent."
            AUTH_INVALID, AUTH_EXPIRED -> "Authentication expired or invalid. Pair again or update token."
            PROTOCOL_UNSUPPORTED, INCOMPATIBLE_VERSION -> "Incompatible Study Agent version. Update app or PC Agent."
            SESSION_NOT_FOUND -> "Session not found. Start a new session."
            STALE_SESSION_REVISION -> "Session changed. Refreshing..."
            STALE_REVIEW_TURN -> "Card turn is stale. Loading current card..."
            RATE_LIMITED -> "Too many requests. Wait a moment."
            else -> message
        }
}
