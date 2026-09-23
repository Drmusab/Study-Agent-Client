package com.studyagent.client.core.render

/**
 * GATE 08 — how a card is *presented* (STEP 06).
 *
 * One enum, four honest levels of maturity:
 *
 * - [ORIGINAL] — production-ready in this gate. The backend's rendered HTML is executed by a
 *   WebView with fidelity first (INV-ANKI-RENDER-02).
 * - [CLEAN] — Study-Agent's own Compose typography over the backend's *text* channels
 *   (`questionText` / `answerText`). Implemented here as the fallback surface and as a real,
 *   selectable mode; its final UX is a later gate (STEP 90).
 * - [VOICE_FOCUS] — the mode the voice-first study flow will want (minimal visual chrome, speech
 *   content emphasised). The seam exists, the design does not (STEP 91). It currently resolves to
 *   the same Compose text surface as [CLEAN] rather than pretending to be something it is not.
 * - [ADAPTIVE] — a *request* for somebody else to choose. It is never presented as-is: it is
 *   resolved to a concrete mode by [AnkiAdaptiveRenderPolicy] before a renderer sees it
 *   (STEP 92).
 *
 * The mode is presentation only. It never changes which content channels exist, never rewrites the
 * domain card (STEP 87) and never decides anything about speech or evaluation:
 * `questionHtml`/`answerHtml` are the visual channel and stay the visual channel in every mode
 * (INV-ANKI-RENDER-28/29).
 */
enum class AnkiCardRenderMode {
    ORIGINAL,
    CLEAN,
    VOICE_FOCUS,
    ADAPTIVE;

    /** True for the modes that present the backend's HTML through a WebView. */
    val usesWebView: Boolean get() = this == ORIGINAL

    /** True for the modes that present the backend's text channels through Compose. */
    val usesComposeText: Boolean get() = this == CLEAN || this == VOICE_FOCUS

    /**
     * True when the mode still has to be resolved before presentation. A renderer must never
     * receive [ADAPTIVE] unresolved — [AnkiAdaptiveRenderPolicy.resolve] runs first.
     */
    val isUnresolved: Boolean get() = this == ADAPTIVE

    companion object {
        /** The only mode this gate claims as production-ready (STEP 06). */
        val DEFAULT: AnkiCardRenderMode = ORIGINAL
    }
}

/**
 * Where a card surface is being shown. The whole vocabulary [ADAPTIVE] resolution needs — two
 * values, both real, no speculative third case (STEP 92).
 */
enum class AnkiRenderSurfaceKind {
    /** Reading/browsing a card: fidelity matters most. */
    BROWSING,

    /** A voice-driven study loop: the spoken channels matter most, the visual is support. */
    VOICE_STUDY
}

/**
 * The deliberately tiny [AnkiCardRenderMode.ADAPTIVE] resolution rule (STEP 92).
 *
 * Predictable on purpose:
 *
 * ```text
 * BROWSING     → ORIGINAL      (read the card as Anki rendered it)
 * VOICE_STUDY  → VOICE_FOCUS   (the voice loop leads; the visual supports it)
 * ```
 *
 * A pure function of one input so it is unit-testable and so no renderer ever has to guess. This is
 * *initial* policy only — per-card adaptation (long tables, media-heavy cards, low-vision users) is
 * explicitly deferred and must not be invented here.
 */
object AnkiAdaptiveRenderPolicy {

    fun resolve(surface: AnkiRenderSurfaceKind): AnkiCardRenderMode = when (surface) {
        AnkiRenderSurfaceKind.BROWSING -> AnkiCardRenderMode.ORIGINAL
        AnkiRenderSurfaceKind.VOICE_STUDY -> AnkiCardRenderMode.VOICE_FOCUS
    }

    /**
     * [mode] when it is already concrete, otherwise the [ADAPTIVE][AnkiCardRenderMode.ADAPTIVE]
     * resolution for [surface]. The single place where "who decides" is answered, so a concrete
     * mode is never silently re-decided (INV-ANKI-RENDER-04 by construction).
     */
    fun concrete(mode: AnkiCardRenderMode, surface: AnkiRenderSurfaceKind): AnkiCardRenderMode =
        if (mode.isUnresolved) resolve(surface) else mode
}
