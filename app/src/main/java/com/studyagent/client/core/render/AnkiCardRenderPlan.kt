package com.studyagent.client.core.render

import com.studyagent.client.core.anki.AnkiRenderedCard

/**
 * GATE 08 — what the renderer will actually present for one card side (STEP 68/§119-§121).
 *
 * The documented fallback order, in one place and in one direction:
 *
 * ```text
 * ORIGINAL HTML                     ← the backend's rendered card, WebView-executed
 *     ↓ HTML unavailable / load failed / no WebView
 * CLEAN text representation         ← the backend's own text channel (questionText / answerText)
 *     ↓ text unavailable too
 * typed render failure              ← AnkiRenderFailure, shown as an explicit message
 * ```
 *
 * Two rules that this type exists to make unbreakable:
 *
 * - **Nothing is invented** (INV-ANKI-RENDER-20). A fallback is always one of the backend's own
 *   channels. There is no HTML-stripping pseudo-text path, no "generate a question from the answer",
 *   no placeholder copy masquerading as card content (GATE 07 already made HTML-stripping illegal).
 * - **A fallback is marked** (STEP 69). [Original.fallbackText] and [CleanText.reason] carry enough
 *   for state and diagnostics to record `ORIGINAL_FAILED → CLEAN_FALLBACK` instead of pretending the
 *   fidelity path worked.
 *
 * The plan is computed from the *normalized card only*: it never queries a backend, never resolves
 * media and never interprets a template (INV-ANKI-RENDER-01/11).
 */
sealed interface AnkiCardRenderPlan {

    val side: AnkiCardSide

    /**
     * The side's text channel, when the backend supplied one — the fallback surface's content. Carried
     * on every variant so a *later* failure (load error, renderer crash, no WebView) can still fall
     * back without re-deriving anything.
     */
    val fallbackText: String?

    /** Content-free tokens explaining a non-obvious choice, for diagnostics (never card content). */
    val tokens: List<String>

    /** The ORIGINAL HTML document payload; `null` unless this plan is [Original]. */
    val html: String? get() = (this as? Original)?.html

    /** Present the backend's rendered HTML in a WebView (INV-ANKI-RENDER-02). */
    data class Original(
        override val side: AnkiCardSide,
        override val html: String,
        override val fallbackText: String? = null,
        override val tokens: List<String> = emptyList()
    ) : AnkiCardRenderPlan

    /**
     * Present the backend's text channel through Compose.
     *
     * @param reason why this is not [Original] — either the requested mode
     *   ([TOKEN_MODE_CLEAN]) or a degradation ([TOKEN_HTML_UNAVAILABLE],
     *   [TOKEN_CLEAN_TEXT_UNAVAILABLE]). Never `null`, never invented.
     */
    data class CleanText(
        override val side: AnkiCardSide,
        val text: String,
        val reason: String,
        override val fallbackText: String? = text,
        override val tokens: List<String> = emptyList()
    ) : AnkiCardRenderPlan

    /** Neither channel exists for this side: a typed failure, not a blank screen (STEP 68). */
    data class Unavailable(
        override val side: AnkiCardSide,
        val failure: AnkiRenderFailure,
        override val fallbackText: String? = null,
        override val tokens: List<String> = emptyList()
    ) : AnkiCardRenderPlan

    companion object {
        /** The visual channel was `null` — GATE 07's "the backend could not supply it". */
        const val TOKEN_HTML_UNAVAILABLE: String = "card_html_unavailable"

        /** The visual channel exists but is empty: a legitimately empty rendering, still shown. */
        const val TOKEN_HTML_EMPTY: String = "card_html_empty"

        /** A Compose-text mode was requested; the HTML channel is deliberately not used. */
        const val TOKEN_MODE_CLEAN: String = "render_mode_clean"

        /** A Compose-text mode was requested but no text channel exists: degraded to ORIGINAL. */
        const val TOKEN_CLEAN_TEXT_UNAVAILABLE: String = "clean_text_unavailable"
    }
}

/**
 * The pure planner: `(card, side, mode) → plan` (STEP 121).
 *
 * Side selection is explicit — [AnkiCardSide.ANSWER] renders `answerHtml` *as Anki produced it*,
 * which by the pinned AnkiDroid contract already includes the question through `{{FrontSide}}`
 * followed by `<hr id=answer>`. The renderer therefore never concatenates `questionHtml + answerHtml`
 * and never prepends the question to the answer (STEP 27/§28/§29, INV-ANKI-RENDER-12): doing so would
 * show the question twice on every Basic card.
 *
 * `null` and `""` stay distinct (GATE 07 nullability semantics, INV-ANKI-CARD-13): `null` means the
 * backend could not supply the channel and triggers the fallback; `""` is a legitimately empty
 * rendering, which ORIGINAL presents as an empty body plus the [TOKEN_HTML_EMPTY][AnkiCardRenderPlan.TOKEN_HTML_EMPTY]
 * diagnostic token — honest, and never silently swapped for text the card did not ask for.
 */
object AnkiCardRenderPlanner {

    fun plan(
        card: AnkiRenderedCard,
        side: AnkiCardSide,
        mode: AnkiCardRenderMode
    ): AnkiCardRenderPlan {
        val html = if (side == AnkiCardSide.QUESTION) card.questionHtml else card.answerHtml
        val text = if (side == AnkiCardSide.QUESTION) card.questionText else card.answerText
        val tokens = card.degradations.filter { it.startsWith(DEGRADATION_PREFIX) }

        // A concrete mode was already chosen for us; ADAPTIVE must be resolved before planning so a
        // plan can never depend on an unresolved request (STEP 92).
        val concrete = if (mode.isUnresolved) AnkiCardRenderMode.ORIGINAL else mode

        return if (concrete.usesComposeText) {
            planText(concrete, side, html, text, tokens)
        } else {
            planOriginal(side, html, text, tokens)
        }
    }

    private fun planOriginal(
        side: AnkiCardSide,
        html: String?,
        text: String?,
        degradations: List<String>
    ): AnkiCardRenderPlan = when {
        html != null -> AnkiCardRenderPlan.Original(
            side = side,
            html = html,
            fallbackText = text,
            tokens = degradations + if (html.isBlank()) listOf(AnkiCardRenderPlan.TOKEN_HTML_EMPTY) else emptyList()
        )
        // No visual channel: the text channel is the honest fallback, marked as such (STEP 119/§120).
        text != null -> AnkiCardRenderPlan.CleanText(
            side = side,
            text = text,
            reason = AnkiCardRenderPlan.TOKEN_HTML_UNAVAILABLE,
            tokens = degradations
        )
        else -> AnkiCardRenderPlan.Unavailable(
            side = side,
            failure = AnkiRenderFailure.HtmlUnavailable(side),
            tokens = degradations
        )
    }

    private fun planText(
        mode: AnkiCardRenderMode,
        side: AnkiCardSide,
        html: String?,
        text: String?,
        degradations: List<String>
    ): AnkiCardRenderPlan = when {
        text != null -> AnkiCardRenderPlan.CleanText(
            side = side,
            text = text,
            reason = AnkiCardRenderPlan.TOKEN_MODE_CLEAN,
            tokens = degradations + modeToken(mode)
        )
        // A CLEAN/VOICE_FOCUS request for a card the backend only rendered visually: degrade to the
        // channel that *does* exist rather than showing nothing (STEP 121, marked, never invented).
        html != null -> AnkiCardRenderPlan.Original(
            side = side,
            html = html,
            fallbackText = null,
            tokens = degradations + AnkiCardRenderPlan.TOKEN_CLEAN_TEXT_UNAVAILABLE
        )
        else -> AnkiCardRenderPlan.Unavailable(
            side = side,
            failure = AnkiRenderFailure.TextUnavailable(side),
            tokens = degradations
        )
    }

    private fun modeToken(mode: AnkiCardRenderMode): List<String> =
        if (mode == AnkiCardRenderMode.VOICE_FOCUS) listOf(TOKEN_MODE_VOICE_FOCUS) else emptyList()

    /** GATE 07 degradation tokens relevant to presentation (content-free by construction). */
    private const val DEGRADATION_PREFIX = "card_"

    /** Recorded so diagnostics can tell VOICE_FOCUS apart from CLEAN without a second field. */
    const val TOKEN_MODE_VOICE_FOCUS: String = "render_mode_voice_focus"
}
