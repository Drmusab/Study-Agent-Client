package com.studyagent.client.core.voice.tts

/**
 * Splits a single (already language-segmented) text into engine-safe chunks.
 *
 * Split priority (semantic, safest first):
 *   paragraph → sentence → clause (comma/semicolon/colon) → word → hard character cut.
 *
 * Sentence splitting is abbreviation- and decimal-aware so we do not break
 * "Dr.", "e.g.", "3.5", or "No." mid-token — the scanner looks at what
 * surrounds each candidate boundary instead of using a naive regex.
 *
 * Guaranteed properties (unit-tested):
 *  - concatenating chunks (space-normalized) reproduces the normalized input
 *  - every chunk length ≤ maxLength (except when maxLength is degenerate)
 *  - chunk order preserved
 */
class SpeechChunker {

    fun split(text: String, maxLength: Int): List<String> {
        val input = text.trim()
        if (input.isEmpty()) return emptyList()
        require(maxLength >= MIN_REASONABLE_LIMIT) { "maxLength too small: $maxLength" }
        if (input.length <= maxLength) return listOf(input)

        val pieces = ArrayList<String>()
        for (paragraph in input.split(PARAGRAPH_REGEX)) {
            val p = paragraph.trim()
            if (p.isEmpty()) continue
            pieces += splitParagraph(p, maxLength)
        }
        // Greedy re-pack: sentence splits sometimes produce many tiny pieces;
        // merging up to the limit reduces engine round-trips (fewer queued utterances).
        return pack(pieces, maxLength)
    }

    private fun splitParagraph(paragraph: String, maxLength: Int): List<String> {
        if (paragraph.length <= maxLength) return listOf(paragraph)
        val sentences = splitSentences(paragraph)
        val out = ArrayList<String>()
        for (sentence in sentences) {
            if (sentence.length <= maxLength) {
                out += sentence
            } else {
                out += splitOversizedSentence(sentence, maxLength)
            }
        }
        return out
    }

    private fun splitOversizedSentence(sentence: String, maxLength: Int): List<String> {
        val clauses = CLAUSE_REGEX.split(sentence).map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.size <= 1) return splitByWords(sentence, maxLength)
        val out = ArrayList<String>()
        for (clause in clauses) {
            if (clause.length <= maxLength) out += clause else out += splitByWords(clause, maxLength)
        }
        return out
    }

    private fun splitByWords(text: String, maxLength: Int): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size <= 1) return hardSplit(text, maxLength)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (word in words) {
            if (word.length > maxLength) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                out += hardSplit(word, maxLength)
                continue
            }
            if (sb.isEmpty()) {
                sb.append(word)
            } else if (sb.length + 1 + word.length <= maxLength) {
                sb.append(' ').append(word)
            } else {
                out += sb.toString()
                sb.clear()
                sb.append(word)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun hardSplit(text: String, maxLength: Int): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            out += text.substring(i, minOf(i + maxLength, text.length))
            i += maxLength
        }
        return out
    }

    private fun pack(pieces: List<String>, maxLength: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (piece in pieces) {
            if (piece.length > maxLength) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                out += piece
                continue
            }
            if (sb.isEmpty()) {
                sb.append(piece)
            } else if (sb.length + 1 + piece.length <= maxLength) {
                sb.append(' ').append(piece)
            } else {
                out += sb.toString()
                sb.clear()
                sb.append(piece)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /**
     * Sentence boundaries: a terminal mark (. ! ? ؟ … :) followed by whitespace,
     * EXCEPT when the mark is part of a protected token:
     *  - "Dr.", "e.g.", "i.e.", "vs.", "approx.", "No.", "Fig.", "St.", "Mr.", "Mrs.", "Ms."
     *  - decimals ("3.5") — period between digits
     *  - ellipsis sequences (the … char or ".." runs): last dot of the run decides
     */
    internal fun splitSentences(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val boundaries = ArrayList<Int>() // index AFTER which to cut
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch in ".!?؟…:") {
                val isProtected = isProtectedPeriod(text, i)
                val isLastOfRun = i + 1 >= n || text[i + 1] !in ".…"
                val followedBySpace = i + 1 < n && text[i + 1].isWhitespace()
                if (!isProtected && isLastOfRun && (followedBySpace || i + 1 >= n)) {
                    boundaries += i + 1
                }
            }
            i++
        }
        if (boundaries.isEmpty()) return listOf(text.trim())
        val out = ArrayList<String>()
        var start = 0
        for (b in boundaries) {
            val piece = text.substring(start, b).trim()
            if (piece.isNotEmpty()) out += piece
            start = b
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    private fun isProtectedPeriod(text: String, index: Int): Boolean {
        if (text[index] != '.') return false
        // Decimal: digit '.' digit
        if (index > 0 && index + 1 < text.length &&
            text[index - 1].isDigit() && text[index + 1].isDigit()
        ) {
            return true
        }
        // Abbreviation token ending at this period ("Dr.", "e.g.")
        val start = maxOf(0, index - MAX_ABBREV_SCAN)
        val wordBefore = text.substring(start, index + 1)
        for (abbr in PROTECTED_ABBREVIATIONS) {
            if (wordBefore.endsWith(abbr, ignoreCase = false)) return true
        }
        return false
    }

    companion object {
        /**
         * Below this, chunks risk being cut mid-word in both scripts and lose meaning;
         * the unit-test contract (max length boundary) exercises 20, so 16 is the floor.
         */
        private const val MIN_REASONABLE_LIMIT = 16
        private const val MAX_ABBREV_SCAN = 10

        private val PARAGRAPH_REGEX = Regex("\\n+")
        /** Split AFTER the delimiter so the comma/semicolon (a spoken micropause) survives. */
        private val CLAUSE_REGEX = Regex("(?<=[,;])")

        /** Tokens whose trailing period does NOT end a sentence. */
        private val PROTECTED_ABBREVIATIONS = listOf(
            "Dr.", "Mr.", "Mrs.", "Ms.", "St.", "No.", "Nos.", "Fig.", "Figs.",
            "vs.", "e.g.", "i.e.", "etc.", "approx.", "ca.", "cf.", "ed.", "vol.",
            "Jan.", "Feb.", "Mar.", "Apr.", "Jun.", "Jul.", "Aug.", "Sep.", "Sept.",
            "Oct.", "Nov.", "Dec."
        )
    }
}
