package com.studyagent.client.core.anki

import java.util.UUID

/**
 * Identity of one logical, read-only Anki context (GATE 21F, DECISION-21B-18/19).
 *
 * A context is deliberately smaller than a backend/session handle: it contains no preference,
 * backend implementation, Compose state or mutable lifecycle flag. The runtime binding/lease lives
 * in the data layer. A new logical context always gets a new [generation], even when its
 * [backendContext] is structurally identical to an earlier one (including A → B → A).
 */
data class AnkiReadContext(
    val backendContext: AnkiBackendContext,
    val generation: AnkiReadContextGeneration
) {
    val backendId: AnkiBackendId get() = backendContext.backendId
    val collectionKey: String? get() = backendContext.collectionKey

    companion object {
        /** Allocate a process-local generation for a newly bound logical context. */
        fun create(backendContext: AnkiBackendContext): AnkiReadContext =
            AnkiReadContext(backendContext, AnkiReadContextGenerations.next())
    }
}

/** Opaque context generation unique to a binding lifetime; it is not resource identity. */
@JvmInline
value class AnkiReadContextGeneration(val value: String) {
    init { require(value.isNotBlank()) { "A read-context generation must not be blank" } }
}

/**
 * One asynchronous read's correlation tuple. [queryGeneration] is stable across pages of one
 * browser query and changes when scope/search/filter/sort changes; [requestGeneration] changes when
 * a request supersedes another request within that query/context. The opaque [cursor] is carried
 * unchanged and is never parsed here.
 */
data class AnkiReadRequestIdentity(
    val context: AnkiReadContext,
    val queryGeneration: Long,
    val requestGeneration: Long,
    val cursor: AnkiPageCursor? = null
) {
    init {
        require(queryGeneration >= 0L)
        require(requestGeneration >= 0L)
    }
}

/** The counter is identity allocation only; it does not own or publish a "current backend". */
object AnkiReadContextGenerations {
    fun next(): AnkiReadContextGeneration =
        AnkiReadContextGeneration(UUID.randomUUID().toString())
}
