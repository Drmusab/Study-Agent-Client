package com.studyagent.client.core.anki

import com.studyagent.client.core.models.Rating

/** No guessed zeros or totals. Values are backend-reported display snapshots, not scheduler input. */
data class AnkiDeckCounts(
    val new: Int? = null,
    val learning: Int? = null,
    val review: Int? = null,
    val totalDue: Int? = null
) {
    init { require(listOf(new, learning, review, totalDue).all { it == null || it >= 0 }) }
}

/** Name and parsed hierarchy are display-only; renaming never changes ref. */
data class AnkiDeck(
    val ref: AnkiDeckRef,
    val name: String,
    val parentRef: AnkiDeckRef? = null,
    val isFiltered: Boolean? = null,
    val counts: AnkiDeckCounts? = null
) {
    init {
        require(name.isNotBlank())
        require(parentRef == null || parentRef.backendId == ref.backendId)
        require(parentRef == null || parentRef != ref)
        require(parentRef?.collectionKey == null || ref.collectionKey == null ||
            parentRef.collectionKey == ref.collectionKey)
    }
    val path: List<String> get() = name.split("::")
}

/** References only: never File, Android Uri, audio/image bytes or copied media. */
sealed interface AnkiMediaRef {
    data class ContentUri(val uri: String, val mimeType: String? = null) : AnkiMediaRef {
        init { require(uri.isNotBlank()) }
    }
    data class BackendStream(val streamId: String, val mimeType: String? = null) : AnkiMediaRef {
        init { require(streamId.isNotBlank()) }
    }
    data class RemoteUrl(val url: String, val mimeType: String? = null) : AnkiMediaRef {
        init { require(url.isNotBlank()) }
    }
    data class Unavailable(val reason: String? = null) : AnkiMediaRef
}

/** FSRS facts are informational only; Study-Agent does not calculate scheduling. */
data class AnkiFsrsInfo(
    val stability: Double? = null,
    val difficulty: Double? = null,
    val desiredRetention: Double? = null
)

/** Backend-rendered labels (including "4d", "6m") are never parsed into scheduling logic. */
data class AnkiSchedulingInfo(
    val intervalLabel: String? = null,
    val dueStateLabel: String? = null,
    val nextReviewTimes: Map<Rating, String> = emptyMap(),
    val fsrs: AnkiFsrsInfo? = null
)

/** Content facts only; tags are not a database or UI selection state. */
data class AnkiCardMetadata(
    val deckName: String? = null,
    val noteTypeName: String? = null,
    val tags: Set<String> = emptySet()
)

/**
 * Backend-normalized content, without AI evaluation, speech normalization or UI state.
 * answerText: clean display/plain-text view. pureAnswerText: optional evaluator reference,
 * without front-side/template repetition when the backend can supply it. Never fabricate it.
 * Collections supplied to domain values must be immutable snapshots, not mutable backing stores.
 * Do not log or persist whole cards/HTML as part of session identity recovery.
 */
data class AnkiRenderedCard(
    val ref: AnkiCardRef,
    val questionHtml: String?,
    val answerHtml: String?,
    val questionText: String,
    val answerText: String,
    val pureAnswerText: String?,
    val media: List<AnkiMediaRef> = emptyList(),
    val scheduling: AnkiSchedulingInfo? = null,
    val metadata: AnkiCardMetadata = AnkiCardMetadata(),
    val noteRef: AnkiNoteRef? = null,
    val deckRef: AnkiDeckRef? = null
) {
    init {
        require(noteRef == null || noteRef.backendId == ref.backendId)
        require(deckRef == null || deckRef.backendId == ref.backendId)
        require(noteRef == null || ref.noteId == null || noteRef.noteId == ref.noteId)
        require(noteRef?.collectionKey == null || ref.collectionKey == null ||
            noteRef.collectionKey == ref.collectionKey)
        require(listOfNotNull(ref.collectionKey, noteRef?.collectionKey, deckRef?.collectionKey)
            .distinct().size <= 1)
    }
}
