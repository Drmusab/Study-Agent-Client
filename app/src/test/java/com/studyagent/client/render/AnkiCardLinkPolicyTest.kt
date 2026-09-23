package com.studyagent.client.render

import com.studyagent.client.core.render.AnkiCardDocument
import com.studyagent.client.core.render.AnkiCardLinkPolicy
import com.studyagent.client.core.render.AnkiLinkDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 08 STEP 114/§115 — the navigation policy (INV-ANKI-RENDER-16/§17).
 *
 * Three rules carry this gate:
 *
 * 1. only a same-document fragment stays in the page;
 * 2. only `http`/`https` to a host that is not our own reserved origin leaves the app, and even then the
 *    renderer only *reports* it;
 * 3. everything else — including everything we did not think of — is blocked. Fail closed, never crash.
 */
class AnkiCardLinkPolicyTest {

    private fun decisionOf(url: String?) = AnkiCardLinkPolicy.classify(url)

    // ------------------------------------------------------------------ in page

    @Test
    fun `same-document anchors stay in the page`() {
        listOf("#answer", "#top", "#", "#cloze-1").forEach { url ->
            val classification = decisionOf(url)
            assertEquals("$url must stay in the page", AnkiLinkDecision.ALLOW_IN_PAGE, classification.decision)
            assertEquals(AnkiCardLinkPolicy.REASON_ANCHOR, classification.reason)
            assertNull("an anchor has no scheme", classification.scheme)
            assertFalse(classification.leavesTheApp)
            assertFalse(classification.isBlocked)
        }
    }

    // ------------------------------------------------------------------ external web

    @Test
    fun `http and https links are mediated outward, not navigated`() {
        listOf(
            "https://en.wikipedia.org/wiki/Heart",
            "http://example.org/page",
            "https://example.org/a?b=c#d",
            "https://sub.domain.example.co.uk/x/y/z"
        ).forEach { url ->
            val classification = decisionOf(url)
            assertEquals("$url must be opened externally", AnkiLinkDecision.OPEN_EXTERNALLY, classification.decision)
            assertEquals(AnkiCardLinkPolicy.REASON_EXTERNAL_WEB, classification.reason)
            assertTrue(classification.leavesTheApp)
            assertFalse(classification.isBlocked)
        }
    }

    @Test
    fun `schemes and hosts are compared case-insensitively`() {
        val classification = decisionOf("HTTPS://EXAMPLE.ORG/Path")
        assertEquals(AnkiLinkDecision.OPEN_EXTERNALLY, classification.decision)
        assertEquals("https", classification.scheme)
        assertEquals("example.org", AnkiCardLinkPolicy.hostOf("HTTPS://EXAMPLE.ORG/Path"))
    }

    @Test
    fun `a link back into the renderer's own reserved origin is blocked`() {
        val url = "${AnkiCardDocument.BASE_URL}some/card/asset"
        val classification = decisionOf(url)
        assertEquals(AnkiLinkDecision.BLOCKED, classification.decision)
        assertEquals(AnkiCardLinkPolicy.REASON_RENDERER_ORIGIN, classification.reason)
        assertEquals("card.studyagent.invalid", AnkiCardLinkPolicy.cardOriginHost)
    }

    // ------------------------------------------------------------------ blocked schemes

    @Test
    fun `script and data URLs are blocked`() {
        listOf(
            "javascript:alert(1)",
            "JavaScript:void(0)",
            "data:text/html,<script>alert(1)</script>",
            "data:image/png;base64,iVBORw0KGgo=",
            "blob:https://example.org/uuid",
            "about:blank",
            "about:config"
        ).forEach { url ->
            val classification = decisionOf(url)
            assertEquals("$url must be blocked", AnkiLinkDecision.BLOCKED, classification.decision)
            assertTrue(classification.isBlocked)
            assertFalse(classification.leavesTheApp)
            assertTrue(
                "$url carries a stable unsupported-scheme token",
                classification.reason.startsWith(AnkiCardLinkPolicy.REASON_UNSUPPORTED_SCHEME_PREFIX)
            )
        }
    }

    @Test
    fun `local and platform schemes are blocked`() {
        listOf(
            "file:///sdcard/Download/x.html",
            "file:///android_asset/card.html",
            "content://media/external/images/1",
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=com.ichi2.anki",
            "mailto:someone@example.org",
            "tel:+15551234567",
            "sms:+15551234567",
            "geo:0,0?q=berlin",
            "anki://x-callback-url/addNote",
            "ankidroid://collection/1",
            "whatsapp://send?text=hi",
            "studyagent://deep/link"
        ).forEach { url ->
            assertEquals("$url must never be handed to the platform", AnkiLinkDecision.BLOCKED, decisionOf(url).decision)
        }
    }

    @Test
    fun `a card can never reach an Anki content provider through a link`() {
        val classification = decisionOf("content://com.ichi2.anki.flashcards/schedule")
        assertEquals(AnkiLinkDecision.BLOCKED, classification.decision)
        assertEquals("content", classification.scheme)
        // The diagnostics token names the scheme, never the authority or path.
        assertFalse(classification.reason.contains("ichi2"))
        assertFalse(classification.reason.contains("schedule"))
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    fun `empty, blank and null URLs are blocked, not defaulted to allowed`() {
        listOf(null, "", "   ", "\n\t").forEach { url ->
            val classification = decisionOf(url)
            assertEquals(AnkiLinkDecision.BLOCKED, classification.decision)
            assertEquals(AnkiCardLinkPolicy.REASON_EMPTY, classification.reason)
            assertNull(classification.scheme)
        }
    }

    @Test
    fun `a scheme-less reference is blocked`() {
        listOf("example.org/page", "/relative/path", "card.html", "//example.org/x", "?query=1").forEach { url ->
            val classification = decisionOf(url)
            assertEquals("$url has no scheme", AnkiLinkDecision.BLOCKED, classification.decision)
            assertEquals(AnkiCardLinkPolicy.REASON_NO_SCHEME, classification.reason)
            assertNull(classification.scheme)
        }
    }

    @Test
    fun `an http(s) URL without a parseable host is blocked`() {
        listOf("https://", "http://", "https://exa mple.org/x", "https://[bad", "http://:8080/").forEach { url ->
            val classification = decisionOf(url)
            assertEquals("$url cannot be opened safely", AnkiLinkDecision.BLOCKED, classification.decision)
            assertEquals(AnkiCardLinkPolicy.REASON_UNPARSEABLE, classification.reason)
        }
    }

    @Test
    fun `malformed schemes are rejected rather than guessed`() {
        assertNull(AnkiCardLinkPolicy.schemeOf(":https://example.org"))
        assertNull(AnkiCardLinkPolicy.schemeOf("java script:alert(1)"))
        assertNull(AnkiCardLinkPolicy.schemeOf("1http://example.org"))
        assertNull(AnkiCardLinkPolicy.schemeOf("ht tp://example.org"))
        assertEquals("http", AnkiCardLinkPolicy.schemeOf("http://example.org"))
        assertEquals("a+b-c.d", AnkiCardLinkPolicy.schemeOf("a+b-c.d://x"))
    }

    @Test
    fun `the classifier is total - hostile input never throws`() {
        val hostile = listOf(
            "https://example.org/" + "a".repeat(5_000),
            "https://\u0000example.org",
            "https://example.org/\u202E/path",
            "%00https://example.org",
            "https://example.org\\@evil.org",
            "https://user:pass@example.org",
            "https://example.org:99999999/",
            "<script>alert(1)</script>",
            "\"><img src=x onerror=alert(1)>",
            "https://example.org/#" + "#".repeat(500),
            "  https://example.org/x  ",
            "HTTPS://EXAMPLE.ORG"
        )
        hostile.forEach { url ->
            val classification = runCatching { decisionOf(url) }.getOrNull()
            assertTrue("'$url' must classify without throwing", classification != null)
            assertTrue(classification!!.decision in AnkiLinkDecision.values().toSet())
        }
    }

    @Test
    fun `a hostile URL still lands in exactly one decision`() {
        AnkiLinkDecision.values().forEach { /* every classification below must be one of these */ }
        listOf(
            "https://example.org" to AnkiLinkDecision.OPEN_EXTERNALLY,
            "javascript:alert(1)" to AnkiLinkDecision.BLOCKED,
            "#anchor" to AnkiLinkDecision.ALLOW_IN_PAGE
        ).forEach { (url, expected) ->
            assertEquals(expected, decisionOf(url).decision)
        }
    }

    // ------------------------------------------------------------------ diagnostics hygiene

    @Test
    fun `a classification never carries the URL, host or path`() {
        val url = "https://patient-records.example.org/secret/12345?token=abc"
        val classification = decisionOf(url)
        assertEquals(AnkiLinkDecision.OPEN_EXTERNALLY, classification.decision)
        val logged = "${classification.reason}|${classification.scheme}"
        listOf("patient-records", "secret", "12345", "token", "abc", "/").forEach { needle ->
            assertFalse("diagnostics must not leak '$needle'", logged.contains(needle))
        }
    }

    @Test
    fun `an unsupported-scheme token is length-capped`() {
        val longScheme = "s".repeat(200)
        val classification = decisionOf("$longScheme://host/path")
        assertTrue(
            "reason='${classification.reason}' must stay bounded",
            classification.reason.length <= AnkiCardLinkPolicy.REASON_UNSUPPORTED_SCHEME_PREFIX.length + 16
        )
        assertEquals(longScheme, classification.scheme)
    }

    @Test
    fun `classification is a pure function of the URL`() {
        // No Context, no Intent, no clock and no state: the same URL classifies identically every time,
        // which is what keeps the *decision* in core and the *action* in the UI layer (STEP 86,
        // INV-ANKI-RENDER-03). That this file runs on a JVM at all is the proof.
        val url = "https://example.org/x"
        val first = decisionOf(url)
        val second = decisionOf(url)
        assertEquals(first, second)
        assertEquals(AnkiLinkDecision.OPEN_EXTERNALLY, decisionOf(url).decision)
    }
}
