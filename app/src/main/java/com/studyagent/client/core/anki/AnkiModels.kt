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
    /** `::`-split of [name]; empty segments from `::::` are preserved, never dropped. */
    val path: List<String> get() = name.split("::")
    /** Last path segment; equal to [name] for a top-level deck. Not an identity. */
    val leafName: String get() = path.last()
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

/**
 * GATE 06 — which rating buttons the *scheduler* is currently offering for one scheduled card.
 *
 * The number of available buttons is a scheduler fact, not a UI constant (§20/§21): Anki shows
 * fewer buttons for some card states, and a future backend may expose a different set. UI must
 * render [Known.ratings] and never hard-code all four.
 *
 * [Unmapped] is the honest alternative to guessing. When a backend announces a button count whose
 * identity or order this build has not verified, no rating may be offered: picking `[AGAIN, HARD]`
 * out of a bare `2` would be inventing a scheduling mapping (§22/INV-ANKI-REV-09).
 */
sealed interface AnkiRatingOptions {
    /** Buttons this build can name, in the scheduler's own order. */
    data class Known(val ratings: List<Rating>) : AnkiRatingOptions {
        init {
            require(ratings.isNotEmpty()) { "At least one rating button must be available" }
            require(ratings.distinct().size == ratings.size) { "Rating buttons must be distinct" }
        }

        val buttonCount: Int get() = ratings.size

        fun supports(rating: Rating): Boolean = ratings.contains(rating)
    }

    /** The scheduler announced [buttonCount] buttons whose identity/order this build cannot map. */
    data class Unmapped(val buttonCount: Int) : AnkiRatingOptions {
        init { require(buttonCount >= 0) { "A button count is never negative" } }
    }
}

/**
 * GATE 06 — a card the backend's scheduler selected for review, *before* its content is rendered.
 *
 * This is deliberately not an [AnkiRenderedCard]: this gate does not have the question/answer and
 * will not fabricate empty ones (§27/§122). GATE 07 hydrates a scheduled card into rendered
 * content; until then the turn carries identity, scheduler metadata and media references only.
 *
 * Identity is whatever the backend actually reported. When a backend addresses a card by
 * `noteId + ordinal` and does not expose a card id, [ref] carries exactly that pair — a missing
 * card id is never synthesized (§17/§18).
 *
 * [media] entries are *references*: a name the backend owns, not a path, not an open file, and
 * not a promise that the file is readable from this process (§24/§25).
 */
data class AnkiScheduledCard(
    val ref: AnkiCardRef,
    val noteRef: AnkiNoteRef?,
    val deckRef: AnkiDeckRef,
    val ratingOptions: AnkiRatingOptions,
    /** Backend-rendered labels for display. Never parsed into scheduling arithmetic (§23). */
    val scheduling: AnkiSchedulingInfo? = null,
    val media: List<AnkiMediaRef> = emptyList(),
    /**
     * Content-free tokens for metadata this build could not read (for example
     * `review_media_unparseable`). Diagnostics only — never shown as card content, never a
     * reason to fail a card whose identity is trustworthy (§51/§54).
     */
    val degradations: List<String> = emptyList()
) {
    init {
        require(noteRef == null || noteRef.backendId == ref.backendId)
        require(deckRef.backendId == ref.backendId)
        require(noteRef == null || ref.noteId == null || noteRef.noteId == ref.noteId)
        // Same rule as AnkiRenderedCard: at most one *known* collection across the three refs.
        // Unknown (null) is not a wildcard, so two different known collections are unrepresentable.
        require(listOfNotNull(ref.collectionKey, noteRef?.collectionKey, deckRef.collectionKey)
            .distinct().size <= 1)
    }
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
