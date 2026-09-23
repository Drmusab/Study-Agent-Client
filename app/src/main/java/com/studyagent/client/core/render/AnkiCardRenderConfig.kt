package com.studyagent.client.core.render

import kotlin.math.roundToInt

/**
 * GATE 08 — the render configuration (STEP 07).
 *
 * Exactly the five settings this gate has a real need for, and not one more: adding a knob before
 * something needs it is how a renderer ends up with a configuration object nobody can reason about.
 *
 * | Field | Why it exists | Who decides |
 * |---|---|---|
 * | [mode] | ORIGINAL fidelity vs Compose text (STEP 06/§43) | the Study surface |
 * | [nightMode] | dark app + light card needs *one* controlled answer (STEP 41) | the app theme |
 * | [textScale] | accessibility scaling without rewriting card CSS (STEP 38/§39) | the caller / system font scale |
 * | [javascriptPolicy] | explicit JS contract instead of a bare boolean (STEP 08) | policy, default fidelity |
 * | [direction] | LTR / RTL / AUTO base direction (STEP 35) | the caller, default the card |
 *
 * The config is a value: it participates in equality, so a renderer can tell "nothing changed" from
 * "the user turned night mode on" without a single `remember`ed flag. It never carries a card, a
 * turn, a WebView or a repository (STEP 85/§87).
 *
 * ### Text scale vs template fidelity (STEP 39)
 *
 * [textScale] is applied through the WebView's own **text zoom**, which multiplies computed font
 * sizes and therefore preserves a template's *relative* sizing (`em`, `%`, `rem`) instead of
 * flattening it — Study-Agent never rewrites a card's `font-size` to fake a zoom (STEP 38). Text
 * declared in absolute `px` by a template does not scale, which is the honest fidelity/accessibility
 * trade-off for [AnkiCardRenderMode.ORIGINAL]: the template's appearance wins by default
 * (`textScale = 1f`) and a caller that wants system font scaling opts in explicitly.
 * [AnkiCardRenderMode.CLEAN] and [AnkiCardRenderMode.VOICE_FOCUS] present Compose `sp` text and
 * therefore honour the system font scale with no configuration at all.
 */
data class AnkiCardRenderConfig(
    val mode: AnkiCardRenderMode = AnkiCardRenderMode.DEFAULT,
    val nightMode: Boolean = false,
    val textScale: Float = 1f,
    val javascriptPolicy: AnkiJavascriptPolicy = AnkiJavascriptPolicy.DEFAULT,
    val direction: CardTextDirection = CardTextDirection.DEFAULT
) {
    init {
        require(textScale.isFinite()) { "textScale must be a finite multiplier" }
        require(textScale in MIN_TEXT_SCALE..MAX_TEXT_SCALE) {
            "textScale must be within $MIN_TEXT_SCALE..$MAX_TEXT_SCALE (got $textScale)"
        }
    }

    /** WebView `WebSettings.textZoom` value for [textScale] (100 = authored size). */
    val textZoomPercent: Int
        get() = (textScale * 100f).roundToInt().coerceIn(MIN_TEXT_ZOOM_PERCENT, MAX_TEXT_ZOOM_PERCENT)

    /** True when [mode] still has to go through [AnkiAdaptiveRenderPolicy]. */
    val needsModeResolution: Boolean get() = mode.isUnresolved

    /**
     * This config with [mode] resolved to a concrete mode for [surface]. Returns `this` when the
     * mode is already concrete, so the resolution point is idempotent and never re-decides a mode a
     * caller chose on purpose (STEP 92).
     */
    fun resolvedFor(surface: AnkiRenderSurfaceKind): AnkiCardRenderConfig =
        if (needsModeResolution) copy(mode = AnkiAdaptiveRenderPolicy.resolve(surface)) else this

    /** This config with a different [nightMode] — the theme-change path (STEP 108). */
    fun withNightMode(enabled: Boolean): AnkiCardRenderConfig = copy(nightMode = enabled)

    /**
     * This config with the system font scale applied as [textScale], clamped to the supported
     * range. The accessibility opt-in for ORIGINAL mode (STEP 39); callers that want template-exact
     * sizing simply do not call it.
     */
    fun withSystemFontScale(fontScale: Float): AnkiCardRenderConfig =
        copy(textScale = fontScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE))

    companion object {
        /** Below this a card is unreadable; above it a card layout stops being meaningful. */
        const val MIN_TEXT_SCALE: Float = 0.5f
        const val MAX_TEXT_SCALE: Float = 3f

        const val MIN_TEXT_ZOOM_PERCENT: Int = 50
        const val MAX_TEXT_ZOOM_PERCENT: Int = 300

        /** Fidelity-first defaults: ORIGINAL, card-authored direction, page-context JS. */
        val DEFAULT: AnkiCardRenderConfig = AnkiCardRenderConfig()

        /**
         * The dark-app configuration: Study-Agent's theme is dark-only (`ui/theme/Theme.kt`), so a
         * card surface inside it wants [nightMode] on. It does **not** invert anything
         * (INV-ANKI-RENDER-15) — see `AnkiCardDocument` for what night mode actually does.
         */
        val DARK_APP: AnkiCardRenderConfig = AnkiCardRenderConfig(nightMode = true)
    }
}
