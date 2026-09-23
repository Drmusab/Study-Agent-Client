package com.studyagent.client.core.render

/**
 * GATE 08 — the explicit JavaScript policy for card documents (STEP 08/§10).
 *
 * JavaScript in this renderer is never a bare `webView.settings.javaScriptEnabled = true` left
 * undocumented. It is one of exactly two values, and both mean **page-context JavaScript only**:
 * there is no native bridge, no `addJavascriptInterface`, no Study-Agent object of any kind inside
 * the page (STEP 09, INV-ANKI-RENDER-08). Enabling [CARD_TEMPLATE_ONLY] therefore buys a card the
 * ability to run its own template script — show/hide sections, dynamic examples, a local
 * calculation, an interactive widget — and buys it **nothing** else: no rating, no session
 * mutation, no microphone, no filesystem, no AI, no Android `Context` (STEP 10/§52/§53,
 * INV-ANKI-RENDER-09/31).
 *
 * There is deliberately **no** `FULL_TRUSTED_NATIVE_ACCESS` member. A policy level that hands card
 * scripts native privilege is not a configuration this app may offer (STEP 08), and AnkiDroid's own
 * `AnkiDroidJsAPI` is not reimplemented here (STEP 51, INV-ANKI-RENDER-10/21): a card written
 * against that API finds `undefined` and its exception is reported as a renderer diagnostic, not a
 * crash (STEP 54).
 */
enum class AnkiJavascriptPolicy {
    /**
     * Card scripts are inert. The HTML/CSS still renders (STEP 111) — nothing is stripped out of
     * the document (STEP 21): the script stays in the DOM and simply never executes, so
     * `<noscript>` content becomes visible exactly as a browser would show it.
     */
    DISABLED,

    /**
     * Card-template scripts run inside the page, with no native capability attached (STEP 50).
     * The default for [AnkiCardRenderMode.ORIGINAL] because a great many real Anki cards are
     * interactive, and refusing to run their script would be a fidelity regression
     * (INV-ANKI-RENDER-02).
     */
    CARD_TEMPLATE_ONLY;

    /** The one WebView setting this policy controls. Nothing else follows from it. */
    val allowsPageJavascript: Boolean get() = this == CARD_TEMPLATE_ONLY

    companion object {
        /** Fidelity-first default (STEP 08/§50). */
        val DEFAULT: AnkiJavascriptPolicy = CARD_TEMPLATE_ONLY
    }
}
