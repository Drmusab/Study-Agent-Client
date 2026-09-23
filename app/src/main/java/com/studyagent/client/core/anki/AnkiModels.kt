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

/**
 * Scheduling metadata with exactly one authoritative home per field (INV-ANKI-CARD-24, STEP 56):
 *
 * - *Presentation labels* ([intervalLabel], [dueStateLabel], [nextReviewTimes]) come from the
 *   scheduler surface (GATE 06's review-info endpoint / future PC agent). The card-content
 *   provider never writes them.
 * - *Stored card facts* ([reps], [lapses], [intervalDays], [lastReviewEpochSeconds], [fsrs])
 *   come from the card-content surface (GATE 07's card row). The review-info endpoint never
 *   writes them.
 *
 * Precedence (documented per STEP 56): for "what will the buttons say" the *turn's* scheduled
 * scheduling info (GATE 06, selection time) is authoritative; for "what has this card stored"
 * the *rendered card's* scheduling info (GATE 07, current stored state) is authoritative. The
 * two never merge into one instance, so they can never contradict each other inside an object.
 *
 * Everything here is read-only informational metadata (INV-ANKI-CARD-18): it never drives a
 * Study-Agent scheduler, and [AnkiFsrsInfo] in particular never feeds an interval calculation
 * (INV-ANKI-CARD-19). Backend-rendered labels (including "4d", "6m") are never parsed into
 * scheduling logic.
 */
data class AnkiSchedulingInfo(
    val intervalLabel: String? = null,
    val dueStateLabel: String? = null,
    val nextReviewTimes: Map<Rating, String> = emptyMap(),
    val fsrs: AnkiFsrsInfo? = null,
    /** Stored Anki repetition count (`reps`) — how many times the card was answered. */
    val reps: Int? = null,
    /** Stored Anki lapse count (`lapses`). */
    val lapses: Int? = null,
    /** Stored interval (`ivl`) in days. A number, not the display label [intervalLabel]. */
    val intervalDays: Int? = null,
    /** Stored last-review time as Unix epoch seconds, when the backend exposes one. */
    val lastReviewEpochSeconds: Long? = null
)

/**
 * Content facts only; tags are not a database or UI selection state.
 *
 * GATE 07 additions (STEP 18/§30/§33) — descriptive card facts with exactly one home each:
 * [templateName] is the card/template display name (`card_name`), never identity (STEP 27);
 * [queueState] is the backend-neutral card/queue state (STEP 33 — never a raw queue integer);
 * [originalDeckRef] preserves filtered-deck home-deck ownership separately from the card's
 * *current* deck (`deckRef`), so a temporary filtered-deck location is never confused with
 * ownership (STEP 29/§30). All three are optional: their absence never breaks review
 * (INV-ANKI-CARD-14).
 */
data class AnkiCardMetadata(
    val deckName: String? = null,
    val noteTypeName: String? = null,
    val tags: Set<String> = emptySet(),
    val templateName: String? = null,
    val queueState: AnkiCardQueueState? = null,
    val originalDeckRef: AnkiDeckRef? = null
)

/**
 * GATE 07 — backend-neutral card/queue state (STEP 33).
 *
 * This is a *display* state ("is this card suspended right now?"), never scheduling arithmetic.
 * Raw provider queue/type integers are mapped inside the AnkiDroid data layer and must not be
 * reconstructed from this enum by anyone (INV-ANKI-CARD-18). Unknown backend codes degrade to
 * [UNKNOWN] (test N) — they are never guessed into a known state.
 */
enum class AnkiCardQueueState {
    NEW,
    LEARNING,
    REVIEW,
    RELEARNING,
    SUSPENDED,

    /** Sibling- or manually-buried. Distinct from [SUSPENDED]: the backend distinguishes them. */
    BURIED,

    /** The backend reported a state this build cannot name (or none at all). */
    UNKNOWN
}

/**
 * Backend-normalized content, without AI evaluation, speech normalization or UI state.
 *
 * The five content fields are three *channels* kept deliberately apart (INV-ANKI-CARD-05,
 * STEP 04-§07):
 *
 * - **visual** — [questionHtml] / [answerHtml]: the backend's rendered card output, preserved
 *   verbatim (STEP 20/§21). GATE 08 renders it; nothing here sanitizes, rewrites or flattens it.
 * - **speech** — [questionText] / [answerText]: the backend's own simplified textual view
 *   (AnkiDroid `QUESTION_SIMPLE`/`ANSWER_SIMPLE`). TTS will consume these, never the HTML
 *   (INV-ANKI-CARD-06). No TTS preprocessing happens here (STEP 06).
 * - **evaluation** — [pureAnswerText] first, [answerText] as fallback (INV-ANKI-CARD-07,
 *   STEP 07/§08): the reference answer for the AI evaluator, never raw answer HTML.
 *
 * Nullability is meaning, not decoration (INV-ANKI-CARD-13 / STEP 22): a field is `null` when
 * the backend could not supply it and non-null (possibly `""`, a legitimately empty rendering)
 * when it could. GATE 07 therefore distinguishes "the answer is genuinely empty" from "the
 * answer representation is unavailable" — and a card whose question exists as HTML but whose
 * simple text is missing is a *visual-only* card with speech content unavailable (STEP 76),
 * not a broken card.
 *
 * [degradations] are content-free tokens (for example `card_speech_text_unavailable`) recording
 * how the mapping degraded; diagnostics only — never card content, never a reason to fail a card
 * whose identity and question are trustworthy.
 *
 * Collections supplied to domain values must be immutable snapshots, not mutable backing stores.
 * Do not log or persist whole cards/HTML as part of session identity recovery.
 */
data class AnkiRenderedCard(
    val ref: AnkiCardRef,
    val questionHtml: String?,
    val answerHtml: String?,
    val questionText: String?,
    val answerText: String?,
    val pureAnswerText: String?,
    val media: List<AnkiMediaRef> = emptyList(),
    val scheduling: AnkiSchedulingInfo? = null,
    val metadata: AnkiCardMetadata = AnkiCardMetadata(),
    val noteRef: AnkiNoteRef? = null,
    val deckRef: AnkiDeckRef? = null,
    /** Backend flag marker; `null` = the backend did not say. Never fabricated (STEP 31/§32). */
    val flag: AnkiFlag? = null,
    val degradations: List<String> = emptyList()
) {
    init {
        require(noteRef == null || noteRef.backendId == ref.backendId)
        require(deckRef == null || deckRef.backendId == ref.backendId)
        require(noteRef == null || ref.noteId == null || noteRef.noteId == ref.noteId)
        require(noteRef?.collectionKey == null || ref.collectionKey == null ||
            noteRef.collectionKey == ref.collectionKey)
        require(listOfNotNull(ref.collectionKey, noteRef?.collectionKey, deckRef?.collectionKey)
            .distinct().size <= 1)
        // STEP 74/§78 — a reviewable card must carry some usable question representation. A row
        // without one cannot be published as a card; the mapper reports a typed malformed-card
        // failure instead of ever constructing this object (INV-ANKI-CARD-15 adjacent).
        require(questionHtml != null || questionText != null) {
            "A rendered card needs a question representation (HTML or text)"
        }
    }

    // ---------------------------------------------------------------- derived capabilities
    // STEP 77 — derived on demand, never stored booleans that can disagree with the content.

    val hasVisualQuestion: Boolean get() = questionHtml != null
    val hasVisualAnswer: Boolean get() = answerHtml != null

    /** Speech content exists. False means TTS must degrade, not that the card is broken. */
    val hasSpeechQuestion: Boolean get() = questionText != null
    val hasSpeechAnswer: Boolean get() = answerText != null

    /** An AI reference answer exists (pure first, then the clean answer text). */
    val hasEvaluationAnswer: Boolean get() = pureAnswerText != null || answerText != null

    /**
     * The AI-evaluation reference answer: [pureAnswerText] first, [answerText] as fallback
     * (INV-ANKI-CARD-07). Never HTML — evaluation consumes the clean text channels only (§36).
     */
    val evaluationAnswerText: String? get() = pureAnswerText ?: answerText

    val hasMedia: Boolean get() = media.isNotEmpty()
}
