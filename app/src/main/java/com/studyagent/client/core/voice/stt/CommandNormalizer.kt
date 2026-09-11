package com.studyagent.client.core.voice.stt

/**
 * Text normalization, split by domain (§75/§76).
 *
 * Two separate entry points on purpose:
 *
 *  - [forCommand] is aggressive. Command matching is against a small closed grammar, so
 *    Arabic orthographic variants and punctuation can be flattened hard without risk.
 *  - [forAnswer] is minimal. A medical transcript is going to an LLM evaluator; rewriting
 *    it can change meaning ("fifteen" → "fifty" is the canonical disaster, §38). Only
 *    whitespace and stray edge punctuation are touched.
 */
object CommandNormalizer {

    /** Arabic diacritics (tashkeel), superscript alef and tatweel — never semantic. */
    private val ARABIC_DIACRITICS = Regex("[\u064B-\u0652\u0670\u0640\u06D6-\u06ED]")

    private val PUNCTUATION = Regex("[.,!?;:\"'\\[\\](){}`~*_—–-]")
    private val WHITESPACE = Regex("\\s+")
    private val LEADING_FILLERS = setOf(
        "um", "uh", "erm", "ah", "oh", "well", "so", "okay", "ok",
        "طيب", "يعني", "هم", "طيب"
    )

    /**
     * Normalize for **command** matching: case-fold, strip punctuation and diacritics,
     * unify Arabic alef/ya/ta-marbuta orthography, drop filler words.
     */
    fun forCommand(text: String): String {
        val stripped = stripAccents(text)
            .replace(ARABIC_DIACRITICS, "")
            .replace(PUNCTUATION, " ")
            .lowercase()
            .let { unifyArabicOrthography(it) }
            .replace(WHITESPACE, " ")
            .trim()
        return dropLeadingFillers(stripped)
    }

    /**
     * Normalize a transcript for **submission**. Conservative by design: the LLM evaluator
     * understands "thirty milliliters" and "30 mL" equally well, so nothing here rewrites
     * medical content, expands abbreviations or converts numbers (§37/§38).
     */
    fun forAnswer(text: String): String = text
        .replace(WHITESPACE, " ")
        .trim()
        .trim(',', '.', ';', ':', '،', '؛')
        .trim()

    /** True when the transcript is plausibly more than a one-word command. */
    fun looksLikeLongUtterance(text: String): Boolean {
        val words = forAnswer(text).split(' ').filter { it.isNotBlank() }
        return words.size > MAX_COMMAND_WORDS
    }

    /**
     * Remove Latin combining accents without altering base letters.
     *
     * Fully qualified to avoid any ambiguity with `kotlin.text` helpers, and `ch.code`
     * (an Int) so the `Character.getType(int)` overload is selected unambiguously.
     */
    private fun stripAccents(input: String): String {
        val decomposed = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (java.lang.Character.getType(ch.code) != java.lang.Character.NON_SPACING_MARK.toInt()) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Harmless Arabic orthographic unification for a closed command vocabulary.
     *
     * `أ`/`إ`/`آ` → `ا` is what makes `أعد` and `اعد`, or `إنهاء` and `انهاء`, match
     * (§76). `ة`→`ه` and `ى`→`ي` are the two variants recognizers most often emit
     * inconsistently. Applied to commands only — never to answer text.
     */
    private fun unifyArabicOrthography(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    'ة' -> 'ه'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private fun dropLeadingFillers(text: String): String {
        var current = text
        var changed = true
        while (changed) {
            changed = false
            val first = current.substringBefore(' ', "")
            if (first.isNotEmpty() && first in LEADING_FILLERS) {
                current = current.substring(first.length).trim()
                changed = true
            }
        }
        return current
    }

    /** Longest phrase in the command grammar; anything longer is an answer, not a command. */
    const val MAX_COMMAND_WORDS = 5
}
