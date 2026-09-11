package com.studyagent.client.core.voice.tts

/** Language of one speakable segment, as classified by [MixedLanguageSegmenter]. */
enum class SegmentLanguage {
    ENGLISH,
    ARABIC,
    /** Only digits / punctuation / symbols — inherits the surrounding or hinted language. */
    NEUTRAL
}

data class TextSegment(
    val text: String,
    val language: SegmentLanguage
)

/**
 * Splits mixed Arabic/English study text into conservative single-language runs
 * so each run can be spoken by the correct voice:
 *
 *     "المريض لديه epidural hematoma مع midline shift أكثر من 5 mm"
 *       → [AR "المريض لديه", EN "epidural hematoma", AR "مع", EN "midline shift أكثر من 5 mm"…]
 *
 * Algorithm (deterministic, no ML, ICU-free — no new dependencies):
 *  1. Classify every char: Arabic Unicode blocks → ARABIC; Latin letters → ENGLISH;
 *     everything else (digits, whitespace, punctuation, symbols) → NEUTRAL.
 *  2. Build maximal runs; NEUTRAL runs attach to the *previous* language run
 *     (or to the next run at string start). This keeps "word word" spacing natural
 *     and prevents pathological per-word switching on punctuation/digits.
 *  3. Merge adjacent same-language runs; drop empty segments (each is delivered trimmed).
 *
 * A [LanguageHint] of ENGLISH/ARABIC short-circuits the scan entirely (user override),
 * and when [autoDetect] is disabled the whole text is assigned to its dominant language
 * (legacy single-voice behavior).
 *
 * Guaranteed properties (unit-tested): no characters are lost or duplicated; segment
 * order is preserved; consecutive segments never share a language.
 */
class MixedLanguageSegmenter {

    fun segment(
        text: String,
        hint: LanguageHint = LanguageHint.AUTO,
        autoDetect: Boolean = true
    ): List<TextSegment> {
        if (text.isBlank()) return emptyList()

        // Explicit override wins over detection.
        if (hint == LanguageHint.ENGLISH) return listOf(TextSegment(text.trim(), SegmentLanguage.ENGLISH))
        if (hint == LanguageHint.ARABIC) return listOf(TextSegment(text.trim(), SegmentLanguage.ARABIC))

        // User disabled auto-detection → dominant-language single segment.
        if (!autoDetect) {
            return listOf(TextSegment(text.trim(), dominantLanguage(text)))
        }

        // 1+2. Scan into runs, attaching neutral characters to the previous run.
        val runs = ArrayList<TextSegment>()
        val current = StringBuilder()
        var currentLang = SegmentLanguage.NEUTRAL

        fun flush() {
            if (current.isNotEmpty()) {
                runs += TextSegment(current.toString(), currentLang)
                current.clear()
            }
        }

        for (ch in text) {
            val lang = classifyChar(ch)
            if (lang == SegmentLanguage.NEUTRAL) {
                if (currentLang == SegmentLanguage.NEUTRAL) {
                    if (current.isEmpty()) currentLang = SegmentLanguage.NEUTRAL
                    current.append(ch)
                } else {
                    // Neutral glues onto the current language run (trailing whitespace trimmed later).
                    current.append(ch)
                }
            } else {
                when {
                    currentLang == SegmentLanguage.NEUTRAL && current.isNotEmpty() -> {
                        // Leading neutral run belongs to this first real language run.
                        currentLang = lang
                        current.append(ch)
                    }
                    currentLang == SegmentLanguage.NEUTRAL -> {
                        currentLang = lang
                        current.append(ch)
                    }
                    lang == currentLang -> current.append(ch)
                    else -> {
                        flush()
                        currentLang = lang
                        current.append(ch)
                    }
                }
            }
        }
        flush()

        if (runs.isEmpty()) return emptyList()

        // 3. Merge, trim, drop empties; resolve any all-neutral residue by dominant language.
        val merged = ArrayList<TextSegment>()
        for (run in runs) {
            val trimmed = run.text.trim()
            if (trimmed.isEmpty()) continue
            val lang = if (run.language == SegmentLanguage.NEUTRAL) dominantLanguage(trimmed) else run.language
            val last = merged.lastOrNull()
            if (last != null && last.language == lang) {
                merged[merged.lastIndex] = TextSegment("${last.text} $trimmed", lang)
            } else {
                merged += TextSegment(trimmed, lang)
            }
        }
        return merged
    }

    private fun classifyChar(ch: Char): SegmentLanguage = when {
        ch in '\u0600'..'\u06FF' -> SegmentLanguage.ARABIC   // Arabic
        ch in '\u0750'..'\u077F' -> SegmentLanguage.ARABIC   // Arabic Supplement
        ch in '\u08A0'..'\u08FF' -> SegmentLanguage.ARABIC   // Arabic Extended-A
        ch in '\uFB50'..'\uFDFF' -> SegmentLanguage.ARABIC   // Arabic Presentation Forms-A
        ch in '\uFE70'..'\uFEFF' -> SegmentLanguage.ARABIC   // Arabic Presentation Forms-B
        ch in 'A'..'Z' || ch in 'a'..'z' -> SegmentLanguage.ENGLISH
        ch in '\u00C0'..'\u024F' -> SegmentLanguage.ENGLISH  // Latin-1 letters + Latin Extended-A/B
        else -> SegmentLanguage.NEUTRAL
    }

    /** Majority vote by classified letter count; ties default to English (device default voice). */
    private fun dominantLanguage(text: String): SegmentLanguage {
        var arabic = 0
        var latin = 0
        for (ch in text) {
            when (classifyChar(ch)) {
                SegmentLanguage.ARABIC -> arabic++
                SegmentLanguage.ENGLISH -> latin++
                SegmentLanguage.NEUTRAL -> Unit
            }
        }
        return if (arabic > latin) SegmentLanguage.ARABIC else SegmentLanguage.ENGLISH
    }
}
