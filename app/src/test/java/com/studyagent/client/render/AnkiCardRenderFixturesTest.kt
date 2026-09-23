package com.studyagent.client.render

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.render.AnkiCardDocumentBuilder
import com.studyagent.client.core.render.AnkiCardLinkPolicy
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardRenderPlan
import com.studyagent.client.core.render.AnkiCardRenderPlanner
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiJavascriptPolicy
import com.studyagent.client.core.render.AnkiLinkDecision
import com.studyagent.client.core.render.AnkiRenderFailure
import com.studyagent.client.core.render.AnkiRenderRequestId
import com.studyagent.client.ui.components.anki.AnkiCardRenderFixtures
import com.studyagent.client.ui.components.anki.AnkiCardRenderFixtures.Expectation
import com.studyagent.client.ui.components.anki.AnkiCardRenderFixtures.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 08 STEP 99-§100 — the compatibility deck, validated on the JVM before it is ever loaded in a
 * WebView.
 *
 * The deck is the compatibility matrix in `docs/GATE_08_ANKI_CARD_RENDERING.md` in executable form, so
 * this file checks the things that would otherwise only be checked by a human reading the report:
 *
 * - every fixture is a *valid* GATE 07 card (nullability semantics included), on a Fake backend, so a
 *   debug deck can never be mistaken for a real collection;
 * - the planner is total over the deck: both sides of all 28 fixtures produce a plan, and the plan is
 *   the one the fixture's own contract implies (HTML → ORIGINAL, text-only → CLEAN, nothing → typed
 *   failure);
 * - the document builder preserves every fixture payload byte for byte, which is the fidelity claim
 *   (INV-ANKI-RENDER-02) applied to real-shaped content rather than to a toy string;
 * - the PASS / DEFERRED_GATE_09 partition is exactly the four media-and-math fixtures, so the report
 *   cannot quietly claim more than the gate proves.
 *
 * What this file cannot prove is what Chromium does with the result. That is the instrumented suite's
 * job, and where it could not run it says NOT RUN rather than passing.
 */
class AnkiCardRenderFixturesTest {

    private val all: List<Fixture> = AnkiCardRenderFixtures.all

    private fun request(fixture: Fixture, side: AnkiCardSide) = AnkiRenderRequestId(
        turnId = turnId("fixture:${fixture.id}:1"),
        cardRef = fixture.card.ref,
        side = side,
        generation = 1L
    )

    private fun plan(fixture: Fixture, side: AnkiCardSide, mode: AnkiCardRenderMode = AnkiCardRenderMode.ORIGINAL) =
        AnkiCardRenderPlanner.plan(fixture.card, side, mode)

    // ------------------------------------------------------------------ the deck itself

    @Test
    fun `the deck is the documented size and every fixture is addressable`() {
        assertEquals("the matrix in the report quotes 28 rows", 28, all.size)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertEquals(all.size, all.map { it.label }.distinct().size)
        all.forEach { fixture ->
            assertNotNull("byId must find '${fixture.id}'", AnkiCardRenderFixtures.byId(fixture.id))
            assertEquals(fixture.id, AnkiCardRenderFixtures.byId(fixture.id)!!.id)
            assertTrue("id '${fixture.id}' must be snake_case", fixture.id.matches(Regex("[a-z0-9_]+")))
            assertTrue("label '${fixture.label}' must be human-readable", fixture.label.isNotBlank())
        }
        assertNull(AnkiCardRenderFixtures.byId("not_a_fixture"))
    }

    @Test
    fun `every fixture runs on the fake backend, never on a real collection`() {
        all.forEach { fixture ->
            val backend = fixture.card.ref.backendId
            assertTrue(
                "'${fixture.id}' must be a Fake backend card, got $backend",
                backend is AnkiBackendId.Fake && backend.id == "render-fixtures"
            )
            assertEquals(backend, fixture.card.deckRef?.backendId)
            assertEquals(backend, fixture.card.noteRef?.backendId)
        }
    }

    @Test
    fun `every fixture honours GATE 07 nullability semantics`() {
        all.forEach { fixture ->
            val card = fixture.card
            assertTrue(
                "'${fixture.id}' needs a question representation",
                card.questionHtml != null || card.questionText != null
            )
            // A `null` channel means the backend could not supply it; an empty string means it did and
            // the rendering is legitimately empty. A fixture must never confuse the two by using "" for
            // "absent".
            listOf(
                "questionHtml" to card.questionHtml,
                "answerHtml" to card.answerHtml,
                "questionText" to card.questionText,
                "answerText" to card.answerText
            ).forEach { (channel, value) ->
                assertFalse(
                    "'${fixture.id}' uses an empty string for the absent $channel",
                    value == "" && channel.endsWith("Text")
                )
            }
        }
    }

    @Test
    fun `the deferred rows are exactly the media, math and font fixtures`() {
        val deferred = AnkiCardRenderFixtures.deferredToGate09.map { it.id }.toSet()
        assertEquals(
            setOf("image_ref", "audio_tag", "math_markup", "custom_font"),
            deferred
        )
        assertEquals(24, AnkiCardRenderFixtures.gate08Verified.size)
        assertEquals(all.size, AnkiCardRenderFixtures.gate08Verified.size + deferred.size)
        all.forEach { fixture ->
            assertEquals(
                "'${fixture.id}' must be listed in exactly one partition",
                fixture.expectation == Expectation.PASS,
                fixture in AnkiCardRenderFixtures.gate08Verified
            )
        }
    }

    @Test
    fun `the deck covers every compatibility dimension the gate claims`() {
        val dimensions = mapOf(
            "plain text" to "basic",
            "inline formatting" to "html_formatting",
            "nested structure" to "nested_divs",
            "card CSS class" to "css_card_class",
            "authored light card" to "light_authored",
            "authored dark card" to "dark_authored",
            "table" to "table",
            "wide table (horizontal scroll)" to "wide_table",
            "cloze" to "cloze",
            "Arabic RTL" to "arabic_rtl",
            "Arabic without dir" to "arabic_no_dir",
            "mixed Arabic and English" to "mixed_arabic_english",
            "long question" to "long_question",
            "long answer" to "long_answer",
            "unicode symbols" to "unicode_symbols",
            "emoji" to "emoji",
            "script on load" to "js_on_load",
            "script on click" to "js_on_click",
            "script expecting AnkiDroidJsAPI" to "js_ankidroid_api",
            "links" to "links",
            "complete document" to "full_document",
            "visual only" to "visual_only",
            "text only" to "text_only",
            "empty answer side" to "answer_without_content"
        )
        dimensions.forEach { (dimension, id) ->
            assertNotNull("the deck must cover $dimension ('$id')", AnkiCardRenderFixtures.byId(id))
        }
    }

    @Test
    fun `the fallback fixtures carry the degradation tokens they document`() {
        assertEquals(
            listOf("card_speech_text_unavailable"),
            AnkiCardRenderFixtures.VISUAL_ONLY.card.degradations
        )
        assertEquals(
            listOf("card_visual_html_unavailable"),
            AnkiCardRenderFixtures.TEXT_ONLY.card.degradations
        )
        assertNull(AnkiCardRenderFixtures.VISUAL_ONLY.card.questionText)
        assertNull(AnkiCardRenderFixtures.TEXT_ONLY.card.questionHtml)
        assertNull(AnkiCardRenderFixtures.ANSWER_WITHOUT_CONTENT.card.answerHtml)
        assertNull(AnkiCardRenderFixtures.ANSWER_WITHOUT_CONTENT.card.answerText)
    }

    @Test
    fun `no fixture names a real collection, provider or package`() {
        all.forEach { fixture ->
            val html = listOfNotNull(fixture.card.questionHtml, fixture.card.answerHtml).joinToString("\n")
            listOf("com.ichi2", "content://", "file:///", "ankidroid://", "READ_WRITE_DATABASE").forEach { token ->
                assertFalse("'${fixture.id}' must not contain '$token'", html.contains(token))
            }
        }
    }

    // ------------------------------------------------------------------ planner totality

    @Test
    fun `the planner is total over the whole deck`() {
        all.forEach { fixture ->
            AnkiCardSide.values().forEach { side ->
                AnkiCardRenderMode.values().forEach { mode ->
                    val plan = runCatching { plan(fixture, side, mode) }.getOrNull()
                    assertNotNull("${fixture.id}/$side/$mode must produce a plan", plan)
                    assertEquals(side, plan!!.side)
                }
            }
        }
    }

    @Test
    fun `an HTML channel always plans as ORIGINAL with that exact HTML`() {
        all.forEach { fixture ->
            AnkiCardSide.values().forEach { side ->
                val expected = if (side == AnkiCardSide.QUESTION) fixture.card.questionHtml else fixture.card.answerHtml
                val plan = plan(fixture, side)
                if (expected != null) {
                    assertTrue(
                        "${fixture.id}/$side has HTML so it must plan ORIGINAL, got $plan",
                        plan is AnkiCardRenderPlan.Original
                    )
                    assertEquals(expected, (plan as AnkiCardRenderPlan.Original).html)
                }
            }
        }
    }

    @Test
    fun `a missing HTML channel falls back to the fixture's own text`() {
        val textOnly = AnkiCardRenderFixtures.TEXT_ONLY
        AnkiCardSide.values().forEach { side ->
            val plan = plan(textOnly, side) as AnkiCardRenderPlan.CleanText
            val expected = if (side == AnkiCardSide.QUESTION) textOnly.card.questionText else textOnly.card.answerText
            assertEquals(expected, plan.text)
            assertEquals(AnkiCardRenderPlan.TOKEN_HTML_UNAVAILABLE, plan.reason)
        }
        // The visual-only fixture keeps ORIGINAL, and a CLEAN request degrades to ORIGINAL rather than
        // to stripped HTML.
        val visualOnly = AnkiCardRenderFixtures.VISUAL_ONLY
        assertTrue(plan(visualOnly, AnkiCardSide.QUESTION) is AnkiCardRenderPlan.Original)
        val cleanRequest = plan(visualOnly, AnkiCardSide.QUESTION, AnkiCardRenderMode.CLEAN)
        assertTrue(cleanRequest is AnkiCardRenderPlan.Original)
        assertTrue(cleanRequest.tokens.contains(AnkiCardRenderPlan.TOKEN_CLEAN_TEXT_UNAVAILABLE))
    }

    @Test
    fun `a side with nothing at all is a typed failure`() {
        val plan = plan(AnkiCardRenderFixtures.ANSWER_WITHOUT_CONTENT, AnkiCardSide.ANSWER)
        assertTrue(plan is AnkiCardRenderPlan.Unavailable)
        val failure = (plan as AnkiCardRenderPlan.Unavailable).failure
        assertTrue(failure is AnkiRenderFailure.HtmlUnavailable)
        assertEquals(AnkiCardSide.ANSWER, (failure as AnkiRenderFailure.HtmlUnavailable).side)
        // The same fixture's question side still renders: one empty side never invalidates the card.
        assertTrue(plan(AnkiCardRenderFixtures.ANSWER_WITHOUT_CONTENT, AnkiCardSide.QUESTION) is AnkiCardRenderPlan.Original)
    }

    @Test
    fun `the GATE 07 degradation tokens reach the plan diagnostics`() {
        val plan = plan(AnkiCardRenderFixtures.VISUAL_ONLY, AnkiCardSide.QUESTION)
        assertTrue(plan.tokens.contains("card_speech_text_unavailable"))
    }

    // ------------------------------------------------------------------ document fidelity

    @Test
    fun `every fixture payload survives the document builder byte for byte`() {
        all.forEach { fixture ->
            AnkiCardSide.values().forEach { side ->
                val payload = (if (side == AnkiCardSide.QUESTION) fixture.card.questionHtml else fixture.card.answerHtml)
                    ?: return@forEach
                val document = AnkiCardDocumentBuilder.build(
                    payload = payload,
                    request = request(fixture, side),
                    config = AnkiCardRenderConfig.DARK_APP
                )
                if (document.verbatim) {
                    assertEquals("${fixture.id}/$side is document-shaped and passed through", payload, document.html)
                } else {
                    assertTrue(
                        "${fixture.id}/$side payload must appear unchanged in the document",
                        document.html.contains(payload)
                    )
                    assertEquals(payload.length, document.payloadLength)
                }
                assertEquals("UTF-8", document.encoding)
                assertEquals(request(fixture, side), document.request)
                assertFalse("${fixture.id}/$side must not be double-escaped", document.html.contains("&amp;lt;"))
            }
        }
    }

    @Test
    fun `only the complete-document fixture is passed through verbatim`() {
        val verbatim = all.filter { fixture ->
            AnkiCardSide.values().any { side ->
                val payload = if (side == AnkiCardSide.QUESTION) fixture.card.questionHtml else fixture.card.answerHtml
                payload != null && AnkiCardDocumentBuilder.isDocumentShaped(payload)
            }
        }.map { it.id }
        assertEquals(listOf("full_document"), verbatim)

        val document = AnkiCardDocumentBuilder.build(
            payload = AnkiCardRenderFixtures.FULL_DOCUMENT.card.questionHtml!!,
            request = request(AnkiCardRenderFixtures.FULL_DOCUMENT, AnkiCardSide.QUESTION),
            config = AnkiCardRenderConfig.DEFAULT
        )
        assertTrue(document.verbatim)
        assertEquals(AnkiCardRenderFixtures.FULL_DOCUMENT.card.questionHtml, document.html)
        assertEquals(1, Regex("<html[ >]").findAll(document.html).count())
    }

    @Test
    fun `night mode never rewrites a fixture's authored colors`() {
        all.forEach { fixture ->
            val payload = fixture.card.questionHtml ?: return@forEach
            val dark = AnkiCardDocumentBuilder.build(payload, request(fixture, AnkiCardSide.QUESTION), AnkiCardRenderConfig(nightMode = true))
            val light = AnkiCardDocumentBuilder.build(payload, request(fixture, AnkiCardSide.QUESTION), AnkiCardRenderConfig(nightMode = false))
            assertTrue("${fixture.id} payload must survive night mode", dark.html.contains(payload))
            assertTrue("${fixture.id} payload must survive day mode", light.html.contains(payload))
            assertFalse("${fixture.id} must not be inverted", dark.html.contains("invert("))
            // The difference between the two documents is the shell, never the card: exactly the
            // `color-scheme` keyword (one character shorter when dark) and the two night-mode body
            // classes. A verbatim document has no shell of ours at all, so it must not change.
            val expectedDelta = if (dark.verbatim) 0 else " night_mode nightMode".length - 1
            assertEquals(
                "${fixture.id}: night mode changed more than the shell",
                expectedDelta,
                dark.html.length - light.html.length
            )
        }
    }

    @Test
    fun `RTL fixtures keep their authored direction and get an auto shell`() {
        val arabic = listOf(AnkiCardRenderFixtures.ARABIC_RTL, AnkiCardRenderFixtures.MIXED_ARABIC_ENGLISH)
        arabic.forEach { fixture ->
            val payload = fixture.card.questionHtml!!
            val document = AnkiCardDocumentBuilder.build(
                payload,
                request(fixture, AnkiCardSide.QUESTION),
                AnkiCardRenderConfig.DEFAULT
            )
            assertTrue("${fixture.id} must keep dir=rtl", document.html.contains("dir=\"rtl\""))
            assertTrue(document.html.contains("<html dir=\"auto\""))
        }
        // The no-dir fixture relies on `auto`, which is the standard "resolve from content" behaviour.
        val noDir = AnkiCardDocumentBuilder.build(
            AnkiCardRenderFixtures.ARABIC_NO_DIR.card.questionHtml!!,
            request(AnkiCardRenderFixtures.ARABIC_NO_DIR, AnkiCardSide.QUESTION),
            AnkiCardRenderConfig.DEFAULT
        )
        assertTrue(noDir.html.contains("<html dir=\"auto\""))
        assertFalse(noDir.html.contains("dir=\"rtl\""))
    }

    @Test
    fun `wide content is never squeezed by the renderer's own CSS`() {
        val payload = AnkiCardRenderFixtures.WIDE_TABLE.card.questionHtml!!
        val document = AnkiCardDocumentBuilder.build(
            payload,
            request(AnkiCardRenderFixtures.WIDE_TABLE, AnkiCardSide.QUESTION),
            AnkiCardRenderConfig.DEFAULT
        )
        assertTrue(document.html.contains(payload))
        // The authored geometry survives: nowrap, all 12 columns, all 8 rows.
        assertTrue(document.html.contains("white-space:nowrap"))
        assertEquals(12, Regex("<th>Column ").findAll(document.html).count())
        assertEquals(96, Regex("<td>value-").findAll(document.html).count())
        assertTrue(document.html.contains("value-8-12"))
        // And the renderer's own CSS constrains nothing but replaced content, so the WebView scrolls
        // horizontally instead of crushing the table (STEP 63).
        val baseCss = AnkiCardDocumentBuilder.baseCss(nightMode = false)
        assertFalse(baseCss.contains("table"))
        assertFalse(baseCss.contains("overflow-x:hidden"))
        assertFalse(baseCss.contains("table-layout:fixed"))
    }

    @Test
    fun `scripts stay in every fixture document, whatever the JavaScript policy`() {
        val scripted = listOf(
            AnkiCardRenderFixtures.JS_ON_LOAD,
            AnkiCardRenderFixtures.JS_ON_CLICK,
            AnkiCardRenderFixtures.JS_ANKIDROID_API
        )
        scripted.forEach { fixture ->
            val payload = fixture.card.questionHtml!!
            assertTrue("'${fixture.id}' must contain a script", payload.contains("<script"))
            listOf(AnkiJavascriptPolicy.DISABLED, AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY).forEach { policy ->
                val document = AnkiCardDocumentBuilder.build(
                    payload,
                    request(fixture, AnkiCardSide.QUESTION),
                    AnkiCardRenderConfig(javascriptPolicy = policy)
                )
                assertTrue(
                    "'${fixture.id}' script must stay in the document under $policy",
                    document.html.contains("<script")
                )
                assertEquals(policy.allowsPageJavascript, document.javascriptEnabled)
            }
        }
    }

    @Test
    fun `link fixtures classify through the same policy the WebView uses`() {
        val payload = AnkiCardRenderFixtures.LINKS.card.questionHtml!!
        val urls = Regex("href=\"([^\"]+)\"").findAll(payload).map { it.groupValues[1] }.toList()
        assertTrue("the fixture must exercise several link shapes", urls.size >= 4)
        val decisions = urls.map { url -> url to AnkiCardLinkPolicy.classify(url).decision }
        assertTrue(decisions.any { it.second == AnkiLinkDecision.OPEN_EXTERNALLY })
        assertTrue(decisions.any { it.second == AnkiLinkDecision.ALLOW_IN_PAGE })
        assertTrue(decisions.any { it.second == AnkiLinkDecision.BLOCKED })
        // The renderer mediates; it never navigates. Nothing here launched an Intent, and the policy has
        // no Context in its signature at all.
        assertTrue(decisions.all { it.second != null })
    }
}
