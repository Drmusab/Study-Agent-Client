package com.studyagent.client.anki.ankidroid

import com.studyagent.client.data.anki.ankidroid.AnkiDroidReviewJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GATE 06 — the JSON-array text reader.
 *
 * The provider transports these columns as `JSONArray.toString()`, so the input shapes below are
 * the shapes AnkiDroid actually produces, plus the malformed shapes that must degrade instead of
 * throwing. "Unparseable" is asserted as `null` everywhere on purpose: the callers distinguish it
 * from an empty array, and a reader that returned `emptyList()` for garbage would silently erase
 * the difference between "this card has no media" and "this card's media could not be read".
 */
class AnkiDroidReviewJsonTest {

    @Test
    fun `parses the provider's own array rendering`() {
        assertEquals(listOf("1m", "6m", "1d", "4d"), AnkiDroidReviewJson.parseScalarArray("[\"1m\",\"6m\",\"1d\",\"4d\"]"))
        assertEquals(listOf("a.png", "b.mp3"), AnkiDroidReviewJson.parseScalarArray("[\"a.png\",\"b.mp3\"]"))
    }

    @Test
    fun `empty array is a successful parse of nothing`() {
        assertEquals(emptyList<String>(), AnkiDroidReviewJson.parseScalarArray("[]"))
        assertEquals(emptyList<String>(), AnkiDroidReviewJson.parseScalarArray("  [  ]  "))
    }

    @Test
    fun `tolerates whitespace inside the array`() {
        assertEquals(
            listOf("1m", "1d"),
            AnkiDroidReviewJson.parseScalarArray("[ \"1m\" , \"1d\" ]")
        )
        assertEquals(
            listOf("1m", "1d"),
            AnkiDroidReviewJson.parseScalarArray("[\n  \"1m\",\n  \"1d\"\n]")
        )
    }

    @Test
    fun `honours escapes a media filename could legally contain`() {
        assertEquals(listOf("a\"b.png"), AnkiDroidReviewJson.parseScalarArray("[\"a\\\"b.png\"]"))
        assertEquals(listOf("dir\\file.png"), AnkiDroidReviewJson.parseScalarArray("[\"dir\\\\file.png\"]"))
        assertEquals(listOf("line\nbreak"), AnkiDroidReviewJson.parseScalarArray("[\"line\\nbreak\"]"))
        assertEquals(listOf("unicode\u00e9"), AnkiDroidReviewJson.parseScalarArray("[\"unicode\\u00e9\"]"))
        assertEquals(listOf("/slash/"), AnkiDroidReviewJson.parseScalarArray("[\"\\/slash\\/\"]"))
    }

    @Test
    fun `keeps a json null element distinguishable from an empty label`() {
        assertEquals(listOf(null as String?, ""), AnkiDroidReviewJson.parseScalarArray("[null,\"\"]"))
    }

    @Test
    fun `non-string scalars widen instead of failing`() {
        // A provider that ever swapped a column's type must degrade to a display oddity, not to a
        // lost card.
        assertEquals(listOf("3", "true"), AnkiDroidReviewJson.parseScalarArray("[3,true]"))
    }

    @Test
    fun `malformed input is null and never an empty list`() {
        assertNull(AnkiDroidReviewJson.parseScalarArray(null))
        assertNull(AnkiDroidReviewJson.parseScalarArray(""))
        assertNull(AnkiDroidReviewJson.parseScalarArray("   "))
        assertNull(AnkiDroidReviewJson.parseScalarArray("[1,2,3"))
        assertNull(AnkiDroidReviewJson.parseScalarArray("\"not-an-array\""))
        assertNull(AnkiDroidReviewJson.parseScalarArray("{\"a\":1}"))
        assertNull(AnkiDroidReviewJson.parseScalarArray("[[1,2]]"))
        assertNull(AnkiDroidReviewJson.parseScalarArray("[\"unterminated]"))
        assertNull(AnkiDroidReviewJson.parseScalarArray("[\"trailing\"] extra"))
        assertNull(AnkiDroidReviewJson.parseScalarArray("[\"a\" \"b\"]"))
        assertNull(AnkiDroidReviewJson.parseScalarArray("[nullish]"))
        assertNull(AnkiDroidReviewJson.parseScalarArray("[\"bad\\qescape\"]"))
    }
}
