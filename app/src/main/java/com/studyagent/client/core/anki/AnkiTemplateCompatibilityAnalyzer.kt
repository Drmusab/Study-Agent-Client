package com.studyagent.client.core.anki

/**
 * GATE 19 — read-only static compatibility analyzer for template source / model CSS.
 *
 * This is a CONSERVATIVE heuristic inspection of raw qfmt/afmt/CSS text. It never evaluates
 * template semantics, never predicts card rendering, and never approves weakening GATE 8/9
 * security. It only reports the presence or absence of known constructs so the UI can label
 * compatibility honestly.
 *
 * Outcomes are classified per the Compatibility Levels locked in docs/GATE_19_CONTRACT_LOCK.md:
 *
 * - FULL: plain HTML/CSS only.
 * - SUPPORTED_WITH_LIMITATIONS: JS under CARD_TEMPLATE_ONLY, media resolvable by GATE 9,
 *   LaTeX/MathJax/custom fonts/RTL as rendered by the backend.
 * - DISPLAY_ONLY: uses AnkiDroidJsAPI/pycmd/ankiPlatform bridges that are not provided; the card
 *   still renders but scripted features degrade.
 * - UNSUPPORTED: evidence the card requires something outside GATE 8/9 policy (e.g., it hard-
 *   depends on external network resources that GATE 9 blocks).
 *
 * The analyzer is pure-Kotlin and JVM-testable.
 */
object AnkiTemplateCompatibilityAnalyzer {

    // Regex patterns are deliberately broad (over-detect rather than miss). They are matched
    // case-insensitively against raw source strings.

    /** AnkiDroid JS API / pycmd bridge names. */
    private val ANKI_BRIDGE_PATTERNS = listOf(
        Regex("""AnkiDroidJS""", RegexOption.IGNORE_CASE),
        Regex("""pycmd""", RegexOption.IGNORE_CASE),
        Regex("""ankiPlatform""", RegexOption.IGNORE_CASE),
        Regex("""ankiLink""", RegexOption.IGNORE_CASE),
        Regex("""_ankiOS""", RegexOption.IGNORE_CASE),
        Regex("""AnkiMobile""", RegexOption.IGNORE_CASE)
    )

    private val SCRIPT_TAG_PATTERN = Regex("""<script\b""", RegexOption.IGNORE_CASE)

    private val CLOZE_PATTERN = Regex("""\{\{\s*cloze:""", RegexOption.IGNORE_CASE)

    private val FRONT_SIDE_PATTERN = Regex("""\{\{\s*FrontSide\s*\}\}""", RegexOption.IGNORE_CASE)

    private val CONDITIONAL_PATTERN = Regex("""\{\{\s*[#^]\s*\w+\s*\}\}""", RegexOption.IGNORE_CASE)

    private val TYPE_ANSWER_PATTERN = Regex("""\{\{\s*type:""", RegexOption.IGNORE_CASE)

    private val HINT_PATTERN = Regex("""\{\{\s*hint:""", RegexOption.IGNORE_CASE)

    /** Media: image/audio/video/sound tags referencing a filename. */
    private val MEDIA_REF_PATTERNS = listOf(
        Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<video\b|<audio\b|<source\b""", RegexOption.IGNORE_CASE),
        Regex("""\[sound:""", RegexOption.IGNORE_CASE)
    )

    private val LATEX_PATTERNS = listOf(
        Regex("""\[latex\]""", RegexOption.IGNORE_CASE),
        Regex("""\[\$"""),
        Regex("""\[\$\$""")
    )

    private val MATHJAX_PATTERN = Regex("""MathJax""", RegexOption.IGNORE_CASE)

    private val CUSTOM_FONT_PATTERN = Regex("""@font-face\b""", RegexOption.IGNORE_CASE)

    /** External http(s) references (src/href/url) that GATE 9 may block. */
    private val EXTERNAL_URL_PATTERN = Regex("""(src|href|url)\s*[=:(]\s*["']?https?://""", RegexOption.IGNORE_CASE)

    private val RTL_PATTERNS = listOf(
        Regex("""dir\s*=\s*["']?rtl""", RegexOption.IGNORE_CASE),
        Regex("""direction\s*:\s*rtl""", RegexOption.IGNORE_CASE),
        Regex("""\brabic\b|\bebrew\b|\barsabic\b|\bpersian\b""", RegexOption.IGNORE_CASE)
    )

    /**
     * Analyze one template's qfmt/afmt plus optional model CSS and produce a compatibility
     * classification. Either qfmt or afmt may be null; CSS may be null. The analysis is
     * read-only and produces no side effects.
     */
    fun analyze(
        qfmt: String? = null,
        afmt: String? = null,
        css: String? = null
    ): AnkiTemplateCompatibility {
        val source = buildString {
            qfmt?.let { append(it).append('\n') }
            afmt?.let { append(it).append('\n') }
            css?.let { append(it).append('\n') }
        }

        if (source.isBlank()) {
            return AnkiTemplateCompatibility.NO_SOURCE
        }

        val hasJavaScript = SCRIPT_TAG_PATTERN.containsMatchIn(source)
        val hasAnkiBridge = ANKI_BRIDGE_PATTERNS.any { it.containsMatchIn(source) }
        val hasCloze = CLOZE_PATTERN.containsMatchIn(source)
        val hasFrontSide = FRONT_SIDE_PATTERN.containsMatchIn(source)
        val hasConditionals = CONDITIONAL_PATTERN.containsMatchIn(source)
        val hasTypeAnswer = TYPE_ANSWER_PATTERN.containsMatchIn(source)
        val hasHint = HINT_PATTERN.containsMatchIn(source)
        val hasMedia = MEDIA_REF_PATTERNS.any { it.containsMatchIn(source) }
        val hasLatex = LATEX_PATTERNS.any { it.containsMatchIn(source) }
        val hasMathJax = MATHJAX_PATTERN.containsMatchIn(source)
        val hasCustomFonts = CUSTOM_FONT_PATTERN.containsMatchIn(source)
        val hasExternalUrls = EXTERNAL_URL_PATTERN.containsMatchIn(source)
        val hasRtl = RTL_PATTERNS.any { it.containsMatchIn(source) }

        val notes = mutableListOf<String>()
        if (hasJavaScript) notes += "contains_javascript"
        if (hasAnkiBridge) notes += "uses_anki_js_bridge"
        if (hasCloze) notes += "contains_cloze_directive"
        if (hasFrontSide) notes += "uses_frontside"
        if (hasConditionals) notes += "uses_conditionals"
        if (hasTypeAnswer) notes += "uses_type_answer"
        if (hasHint) notes += "uses_hint"
        if (hasMedia) notes += "references_media"
        if (hasLatex) notes += "contains_latex"
        if (hasMathJax) notes += "references_mathjax"
        if (hasCustomFonts) notes += "uses_custom_fonts"
        if (hasExternalUrls) notes += "references_external_urls"
        if (hasRtl) notes += "contains_rtl_signals"

        // Compatibility level ladder (most restrictive wins):
        //  - external URLs that GATE 9 blocks → UNSUPPORTED only when they appear *required*
        //    (src/href to http(s) in media); otherwise mark DISPLAY_ONLY.
        //  - Anki bridge (pycmd/AnkiDroidJS) → DISPLAY_ONLY (scripts run but the bridge is absent).
        //  - JS / media / latex / mathjax / custom fonts / rtl → SUPPORTED_WITH_LIMITATIONS (all
        //    handled within existing GATE 8/9 policy).
        //  - Plain HTML/CSS with none of those features → FULL.
        val level: AnkiCompatibilityLevel = when {
            hasAnkiBridge -> AnkiCompatibilityLevel.DISPLAY_ONLY
            hasExternalUrls && (hasMedia || hasJavaScript) -> AnkiCompatibilityLevel.DISPLAY_ONLY
            hasJavaScript || hasMedia || hasLatex || hasMathJax || hasCustomFonts ||
                hasTypeAnswer || hasRtl -> AnkiCompatibilityLevel.SUPPORTED_WITH_LIMITATIONS
            else -> AnkiCompatibilityLevel.FULL
        }

        return AnkiTemplateCompatibility(
            level = level,
            hasJavaScript = hasJavaScript,
            hasAnkiBridge = hasAnkiBridge,
            hasClozeDirective = hasCloze,
            hasFrontSide = hasFrontSide,
            hasConditionals = hasConditionals,
            hasTypeAnswer = hasTypeAnswer,
            hasHintField = hasHint,
            hasMediaReferences = hasMedia,
            hasLatex = hasLatex,
            hasMathJax = hasMathJax,
            hasCustomFonts = hasCustomFonts,
            hasExternalUrls = hasExternalUrls,
            hasRtlSignals = hasRtl,
            notes = notes
        )
    }
}
