package com.studyagent.client.core.render

/**
 * GATE 08 — base writing direction of the rendered document (STEP 35/§36).
 *
 * `direction: ltr` is never hard-coded over a card (STEP 35). The default is [AUTO], which means
 * "let the document decide": the renderer emits `dir="auto"` on `<html>`, Chromium resolves the
 * base direction from the first strong character of the content, and any `dir` attribute or CSS
 * `direction` the card itself carries still wins *locally* for its own subtree (STEP 36). An
 * Arabic-only card therefore starts at the right edge, an English card at the left, and a mixed
 * card follows its own markup — with no Study-Agent opinion in between.
 *
 * [LTR] and [RTL] exist for the cases where the app genuinely knows better than the content: an
 * explicit user/deck preference, or a card whose first strong character is a Latin abbreviation
 * while the whole card is Arabic. Forcing them is a policy decision by the caller, not a renderer
 * default (INV-ANKI-RENDER-14 by analogy: no silent override of authored presentation).
 */
enum class CardTextDirection {
    LTR,
    RTL,
    AUTO;

    /**
     * The `dir` attribute value written on `<html>`. [AUTO] maps to the HTML `auto` keyword, which
     * is the standard "resolve from content" behaviour — not a Study-Agent invention and not a
     * guess.
     */
    val htmlDirAttribute: String
        get() = when (this) {
            LTR -> "ltr"
            RTL -> "rtl"
            AUTO -> "auto"
        }

    /** True when the direction is a hard override rather than "whatever the card says". */
    val isForced: Boolean get() = this != AUTO

    companion object {
        val DEFAULT: CardTextDirection = AUTO
    }
}
