package com.studyagent.client.core.voice.tts

/**
 * First stage of the speech-only text pipeline:
 *
 *   raw study/Anki text
 *     → markup cleanup (HTML tags, entities, non-breaking spaces)
 *     → whitespace normalization
 *     → natural-pause conversion for block-level markup
 *
 * This transformation is applied to what is *spoken* only — the visible card
 * and the transcript sent to the server are never modified.
 *
 * Markup stripping is *tag-list based*, not "strip everything between < and >":
 * medical text legitimately contains comparison operators ("EDH > 30 mL",
 * "MLS < 5 mm") and naive angle-bracket stripping would destroy them.
 * All regexes are compiled once (class-level), never per-request.
 */
class SpeechTextPreprocessor {

    fun preprocess(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw

        // 1. Block-level tags become sentence boundaries so lists/paragraphs get a natural pause.
        t = BLOCK_TAG_REGEX.replace(t, ". ")
        // 2. All remaining known inline tags are removed without adding pauses.
        t = INLINE_TAG_REGEX.replace(t, " ")
        // 3. HTML entities (named + numeric).
        t = decodeEntities(t)
        // 4. Unicode whitespace normalization (incl. NBSP after entity decode).
        t = t.replace('\u00A0', ' ')  // NBSP → ASCII space
        t = WHITESPACE_REGEX.replace(t, " ")
        // 5. Tidy punctuation artifacts produced by markup removal ("word . next" → "word. next").
        t = SPACE_BEFORE_PUNCT_REGEX.replace(t, "$1")
        t = MULTI_PERIOD_REGEX.replace(t, ". ")
        // Drop a pause-period glued right after an already-terminal mark ("items:." → "items:").
        t = REDUNDANT_TERMINAL_REGEX.replace(t, "$1")
        t = LEADING_PUNCT_REGEX.replace(t, "")
        // 6. Anki cloze deletion markers ("{{c1::answer::hint}}" → "answer").
        t = CLOZE_REGEX.replace(t, "$1")

        return t.trim()
    }

    private fun decodeEntities(text: String): String {
        var t = text
        for ((entity, replacement) in NAMED_ENTITIES) {
            t = t.replace(entity, replacement)
        }
        // Decimal numeric entities, e.g. &#160;
        t = NUMERIC_ENTITY_REGEX.replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 32..0x10FFFF) codeToChar(code) else m.value
        }
        // Hex numeric entities, e.g. &#xA0;
        t = HEX_ENTITY_REGEX.replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 32..0x10FFFF) codeToChar(code) else m.value
        }
        return t
    }

    private fun codeToChar(code: Int): String = String(Character.toChars(code))

    companion object {
        /** Known block-level tags → replaced with a pause. */
        private val BLOCK_TAG_REGEX = Regex(
            "(?i)</?\\s*(br|p|div|li|ul|ol|tr|table|h[1-6]|blockquote|pre|hr)\\b[^>]*>"
        )

        /** Known inline/formatting tags → removed silently. */
        private val INLINE_TAG_REGEX = Regex(
            "(?i)</?\\s*(span|b|i|em|strong|u|s|sub|sup|font|a|img|code|mark|small|big|abbr|q|cite|del|ins)\\b[^>]*>"
        )

        private val WHITESPACE_REGEX = Regex("[ \\t\\x0B\\f\\r\\n]+")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([.,;:!?؟،])")
        private val MULTI_PERIOD_REGEX = Regex("(\\.\\s*){2,}")
        private val REDUNDANT_TERMINAL_REGEX = Regex("([:;!?])\\.")
        private val LEADING_PUNCT_REGEX = Regex("^[. ]+")
        private val CLOZE_REGEX = Regex("\\{\\{c\\d+::(.*?)(?:::(.*?))?\\}\\}")
        private val NUMERIC_ENTITY_REGEX = Regex("&#(\\d{1,7});")
        private val HEX_ENTITY_REGEX = Regex("&#[xX]([0-9a-fA-F]{1,6});")

        /** Conservative named-entity table — sufficient for Anki/HTML exports. */
        private val NAMED_ENTITIES: List<Pair<String, String>> = listOf(
            "&nbsp;" to " ",
            "&amp;" to "&",
            "&lt;" to "<",
            "&gt;" to ">",
            "&quot;" to "\"",
            "&apos;" to "'",
            "&#39;" to "'",
            "&ndash;" to "–",
            "&mdash;" to "—",
            "&deg;" to "°",
            "&plusmn;" to "±",
            "&times;" to "×",
            "&divide;" to "÷",
            "&le;" to "≤",
            "&ge;" to "≥",
            "&micro;" to "µ",
            "&hellip;" to "…",
            "&lsquo;" to "'",
            "&rsquo;" to "'",
            "&ldquo;" to "\"",
            "&rdquo;" to "\""
        )
    }
}
