package com.studyagent.client.core.anki.create

import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiIdentityObservation
import com.studyagent.client.core.anki.AnkiReadContextGeneration
import com.studyagent.client.core.anki.AnkiReadContextGenerations

/**
 * GATE 21K — the immutable identity of one note-creation workflow (DECISION-21B-21).
 *
 * One creation context binds a coherent set: the configured backend instance, the collection
 * context it is working with, and — through the draft assembled under it — the note model, the
 * (backend-determined) deck destination and the media destination. All of them belong to the same
 * backend; the four cross-backend combinations of DECISION-21B-21 stay locked.
 *
 * What it deliberately does **not** contain:
 *
 * ```text
 * the global backend preference / AUTO policy   → consulted only to seed a NEW context (21E)
 * a mutable "current backend"                   → identity is fixed at binding time
 * a transport/connection object or credentials  → runtime state; identity survives it
 * capability or availability snapshots          → observations, re-checked at admission
 * a StudySession / scheduler lock               → creation is not study; it never shares one
 * ```
 *
 * [generation] is this binding lifetime's opaque token (the shared generation type of 21C/21F):
 * two contexts for the same backend and collection are still different workflows when their
 * generations differ, so a stale callback from an earlier context can never be mistaken for the
 * newer one's. The token is not resource identity and is never persisted.
 *
 * A preference change after binding cannot retarget this context; creating on another backend
 * requires a new context seeded by a fresh resolution. An admitted durable creation keeps its own
 * recorded backend (`NoteCreationRecord.backendId`), which — not this value — owns recovery.
 */
data class AnkiNoteCreationContext(
    val backendContext: AnkiBackendContext,
    val generation: AnkiReadContextGeneration
) {
    /** The exact configured instance this workflow is bound to. Immutable. */
    val backendId: AnkiBackendId get() = backendContext.backendId

    /** The backend-issued discriminator, or `null` when it is UNKNOWN. Never a sentinel. */
    val collectionKey: String? get() = backendContext.collectionKey

    /** This context as one side of an identity comparison (admission evidence). */
    val observation: AnkiIdentityObservation get() = backendContext.observation

    companion object {
        /** Bind a new creation context to [backendContext] with a fresh generation. */
        fun create(backendContext: AnkiBackendContext): AnkiNoteCreationContext =
            AnkiNoteCreationContext(backendContext, AnkiReadContextGenerations.next())

        /** Bind a new creation context from a backend and its (possibly unknown) collection key. */
        fun of(backendId: AnkiBackendId, collectionKey: String? = null): AnkiNoteCreationContext =
            create(
                AnkiBackendContext(
                    backendId,
                    collectionKey?.let { AnkiCollectionIdentity(backendId, it) }
                )
            )
    }
}
