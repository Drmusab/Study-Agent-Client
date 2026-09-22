package com.studyagent.client.core.anki

/**
 * GATE 01 contract — backend-neutral domain models (§20-§22).
 *
 * No AnkiDroid type (`FlashCardsContract`, `Cursor`, `ContentResolver`,
 * `AddContentApi`) and no PC-protocol type (`ProtocolMessage`, `ServerMessage`,
 * socket) may reach this package — those live behind the backend gateways in
 * `data/anki/...` (INV-ANKI-06, §18-§19).
 */

/** One deck as listed by a backend. Identity is [ref]; [name] is display-only. */
data class AnkiDeck(
    val ref: AnkiDeckRef,
    val name: String,
    val newCount: Int = 0,
    val dueCount: Int = 0,
    val learningCount: Int = 0,
    val isDynamic: Boolean = false
)

/**
 * Due/new counts for one deck. Counts are *freshness-graded cache* (§25):
 * they may be shown stale with [asOfEpochMs], but never treated as scheduler
 * authority — the next card always comes from the backend, never from counts
 * (INV-ANKI-04, INV-ANKI-09).
 */
data class AnkiDeckSummary(
    val deck: AnkiDeckRef,
    val newCount: Int = 0,
    val dueCount: Int = 0,
    val learningCount: Int = 0,
    /** When these numbers were obtained; null when the backend did not say. */
    val asOfEpochMs: Long? = null
)

/**
 * Media is *referenced*, never copied into Study-Agent storage (§23).
 * Filesystem paths are intentionally absent from the contract.
 */
sealed interface AnkiMediaRef {
    /** Android content URI served by the backend's provider (string form stays Android-free). */
    data class ContentUri(val uri: String, val mimeType: String? = null) : AnkiMediaRef

    /** Opaque stream the backend opens on demand (gateway-mediated). */
    data class BackendStream(val streamId: String, val mimeType: String? = null) : AnkiMediaRef

    /** Remote URL reachable from this device (PC agent media endpoint). */
    data class RemoteUrl(val url: String, val mimeType: String? = null) : AnkiMediaRef

    /** Media exists on the card but cannot be served right now (render degrades, §37). */
    data class Unavailable(val reason: String? = null) : AnkiMediaRef
}

/**
 * Scheduler-owned *display hints* only (§21). These labels describe what Anki
 * already computed; they are never inputs to any Study-Agent re-scheduling,
 * and they are never persisted as due-state authority (INV-ANKI-04).
 */
data class AnkiSchedulingInfo(
    val intervalLabel: String? = null,
    val dueStateLabel: String? = null,
    /** Per-button preview labels ("10m", "1d"...) exactly as the backend rendered them. */
    val easePreviewLabels: List<String> = emptyList()
)

/** Non-content card facts safe for display and diagnostics. */
data class AnkiCardMetadata(
    val deckName: String? = null,
    val noteTypeName: String? = null,
    val tags: List<String> = emptyList()
)

/**
 * One card, normalized once by the backend into the three representations the
 * app consumes (§21-§22):
 *
 *  - VISUAL: [questionHtml]/[answerHtml] — Anki-rendered, templates/cloze owned
 *    by Anki; Study-Agent displays, never reinterprets (§51-§52, INV-ANKI-12).
 *  - SPEECH: [questionText]/[answerText] — plain text for TTS.
 *  - EVALUATION: [pureAnswerText] — the reference answer for the AI evaluator,
 *    free of cloze/template decoration when the backend can provide it.
 *
 * Privacy (§70): instances of this class are never logged whole; diagnostics
 * log refs and lengths only.
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
    val metadata: AnkiCardMetadata = AnkiCardMetadata()
)
