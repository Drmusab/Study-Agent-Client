package com.studyagent.client.data.anki.ankidroid

/**
 * GATE 06 — the smallest reader for the JSON arrays the AnkiDroid provider sends as text.
 *
 * Why not `org.json`: a `MatrixCursor` cell that is not a number or a byte array crosses the
 * cursor window as its `toString()`, so `next_review_times` and `media_files` arrive as the
 * *text* of a JSON array (GATE 05 hit the same fact with `[learn, review, new]`). `org.json`
 * is part of the Android framework, which makes it unusable from the JVM suite that this layer
 * must stay testable in — and an `org.json` failure mode is an exception, where this layer needs
 * "parsed / did not parse" as a value it can degrade from.
 *
 * Scope, deliberately narrow: a JSON array whose elements are scalars. Nested arrays and objects
 * are *not* part of the pinned review contract, so they are reported as unparseable rather than
 * guessed at. Escapes are honoured, because a media filename may legitimately contain one.
 *
 * Returns `null` — never throws, never invents an empty list — when the text is absent, empty or
 * malformed. `[]` is a successful parse of zero elements and is distinct from "could not read".
 */
internal object AnkiDroidReviewJson {

    /**
     * Parses [text] as a JSON array of scalars.
     *
     * A JSON `null` element becomes a `null` entry (the caller decides whether that is a blank
     * label or a missing filename). Numbers and booleans are returned as their literal text, so a
     * provider that widens a column's type degrades to a display oddity instead of a failure.
     */
    fun parseScalarArray(text: String?): List<String?>? {
        if (text == null) return null
        val input = text.trim()
        if (input.isEmpty()) return null

        var index = 0
        index = skipWhitespace(input, index)
        if (index >= input.length || input[index] != '[') return null
        index++
        index = skipWhitespace(input, index)

        val values = ArrayList<String?>()
        if (index < input.length && input[index] == ']') {
            index++
            return if (skipWhitespace(input, index) == input.length) values else null
        }

        while (true) {
            index = skipWhitespace(input, index)
            if (index >= input.length) return null

            val element = readScalar(input, index) ?: return null
            values.add(element.first)
            index = skipWhitespace(input, element.second)
            if (index >= input.length) return null

            when (input[index]) {
                ',' -> index++
                ']' -> {
                    index++
                    return if (skipWhitespace(input, index) == input.length) values else null
                }
                else -> return null
            }
        }
    }

    private fun skipWhitespace(input: String, from: Int): Int {
        var index = from
        while (index < input.length && input[index].isWhitespace()) index++
        return index
    }

    /** One array element: value text plus the index just past it, or `null` when not a scalar. */
    private fun readScalar(input: String, start: Int): Pair<String?, Int>? = when {
        input.startsWith(JSON_NULL, start) -> null to (start + JSON_NULL.length)
        input[start] == '"' -> readString(input, start)
        input[start] in JSON_LITERAL_START -> readLiteral(input, start)
        else -> null
    }

    private fun readString(input: String, start: Int): Pair<String, Int>? {
        val builder = StringBuilder()
        var index = start + 1
        while (index < input.length) {
            when (val character = input[index]) {
                '"' -> return builder.toString() to (index + 1)
                '\\' -> {
                    if (index + 1 >= input.length) return null
                    when (val escape = input[index + 1]) {
                        '"', '\\', '/' -> builder.append(escape)
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 5 >= input.length) return null
                            val code = input.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    index += 2
                }
                else -> {
                    builder.append(character)
                    index++
                }
            }
        }
        return null
    }

    /** A bare `true`/`false`/number token, returned as its literal text. */
    private fun readLiteral(input: String, start: Int): Pair<String, Int>? {
        var index = start
        while (index < input.length && input[index] !in JSON_VALUE_TERMINATORS) index++
        if (index == start) return null
        return input.substring(start, index) to index
    }

    private const val JSON_NULL = "null"
    private const val JSON_LITERAL_START = "-0123456789tfn"
    private const val JSON_VALUE_TERMINATORS = ",] \t\r\n"
}
