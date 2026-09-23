package com.studyagent.client.render

import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiJavascriptPolicy
import com.studyagent.client.core.render.AnkiRenderEvent
import com.studyagent.client.core.render.AnkiRenderFailure
import com.studyagent.client.core.render.AnkiRenderPresentation
import com.studyagent.client.core.render.AnkiRenderRequestId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 08 PART IV — diagnostics privacy, tested as a property of every event type (STEP 122-§124).
 *
 * The rule is blunt: a render event may carry identifiers, counts, lengths, durations and stable tokens.
 * It may never carry card HTML, card text, a link URL, a console message, an exception message or a
 * WebView error description — because every one of those is deck content, and a production log is not
 * allowed to collect a student's cards.
 */
class AnkiRenderEventTest {

    private val request = AnkiRenderRequestId(turnId(), renderCard().ref, AnkiCardSide.ANSWER, 3L)

    /** One instance of every event type the renderer can emit. */
    private val all: List<AnkiRenderEvent> = listOf(
        AnkiRenderEvent.Started(
            request = request,
            mode = AnkiCardRenderMode.ORIGINAL,
            presentation = AnkiRenderPresentation.ORIGINAL,
            javascriptPolicy = AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY,
            htmlLength = 1_234,
            verbatimDocument = false
        ),
        AnkiRenderEvent.Ready(
            request = request,
            mode = AnkiCardRenderMode.ORIGINAL,
            presentation = AnkiRenderPresentation.ORIGINAL,
            loadDurationMs = 42L,
            pageFinished = true
        ),
        AnkiRenderEvent.Failed(
            request = request,
            failure = AnkiRenderFailure.WebViewLoadFailure(-8, "timeout"),
            fallbackShown = true
        ),
        AnkiRenderEvent.FallbackUsed(request = request, reason = "card_html_unavailable", failureToken = null),
        AnkiRenderEvent.SurfaceReleased(turnId = turnId().value, reason = "disposed", documentsLoaded = 7L),
        AnkiRenderEvent.RendererProcessGone(request = request, didCrash = true, documentsLoaded = 7L),
        AnkiRenderEvent.SurfaceUnavailable(turnId = turnId().value, category = "creation_failed"),
        AnkiRenderEvent.StaleCallbackIgnored(
            stale = request,
            active = request.copy(generation = 4L),
            callback = AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_PAGE_FINISHED
        ),
        AnkiRenderEvent.JavascriptConsoleError(request = request, level = "error", errorCount = 2L),
        AnkiRenderEvent.JavascriptDialogSuppressed(request = request, kind = "alert"),
        AnkiRenderEvent.WebPermissionDenied(request = request, resourceCount = 1),
        AnkiRenderEvent.ExternalLinkMediated(request = request, scheme = "https"),
        AnkiRenderEvent.ExternalLinkBlocked(request = request, scheme = "javascript", reason = "unsupported_scheme_javascript"),
        AnkiRenderEvent.SslErrorBlocked(request = request, errorCategory = "untrusted")
    )

    @Test
    fun `every event is named in the ANKI_RENDER family and the names are unique`() {
        val names = all.map { it.name }
        assertEquals("one instance per event type", names.distinct().size, names.size)
        assertEquals(all.size, AnkiRenderEventTypeCount)
        names.forEach { name ->
            assertTrue("'$name' must be namespaced", name.startsWith("ANKI_RENDER_"))
            assertTrue("'$name' must be SCREAMING_SNAKE", name.all { it.isUpperCase() || it.isDigit() || it == '_' })
        }
    }

    @Test
    fun `every event carries the turn identity when it has one`() {
        all.forEach { event ->
            if (event is AnkiRenderEvent.SurfaceReleased || event is AnkiRenderEvent.SurfaceUnavailable) return@forEach
            assertEquals(turnId().value, event.turnId)
        }
        assertEquals(turnId().value, all.filterIsInstance<AnkiRenderEvent.SurfaceReleased>().single().turnId)
        // A surface can be unavailable before any turn exists; then the turn is honestly null.
        assertNull(AnkiRenderEvent.SurfaceUnavailable(turnId = null, category = "creation_failed").turnId)
    }

    @Test
    fun `no event carries card content`() {
        val hostileCard = renderCard(
            questionHtml = "<div class=\"card\">$QUESTION_MARKER <img src=\"patient-x.png\"></div>",
            questionText = "What does $QUESTION_MARKER mean?",
            answerText = "$ANSWER_MARKER — confidential"
        )
        val hostileRequest = AnkiRenderRequestId(turnId(), hostileCard.ref, AnkiCardSide.QUESTION, 1L)
        val events = listOf<AnkiRenderEvent>(
            AnkiRenderEvent.Started(
                request = hostileRequest,
                mode = AnkiCardRenderMode.ORIGINAL,
                presentation = AnkiRenderPresentation.ORIGINAL,
                javascriptPolicy = AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY,
                htmlLength = hostileCard.questionHtml!!.length,
                verbatimDocument = false
            ),
            AnkiRenderEvent.Ready(
                request = hostileRequest,
                mode = AnkiCardRenderMode.ORIGINAL,
                presentation = AnkiRenderPresentation.ORIGINAL,
                loadDurationMs = 12L,
                pageFinished = true
            ),
            AnkiRenderEvent.ExternalLinkMediated(request = hostileRequest, scheme = "https")
        )

        events.forEach { event ->
            val text = event.logLine() + event.metadata.values.joinToString("|")
            listOf(QUESTION_MARKER, ANSWER_MARKER, "patient-x", "confidential", "<div", "<img", ".png").forEach { needle ->
                assertFalse("${event.name} leaked '$needle'", text.contains(needle))
            }
            assertTrue(
                "${event.name} reports a length, not the thing itself",
                event.metadata.values.all { value -> value.length <= 64 }
            )
        }
    }

    @Test
    fun `a link event carries the scheme and never the URL`() {
        val mediated = AnkiRenderEvent.ExternalLinkMediated(request = request, scheme = "https")
        val blocked = AnkiRenderEvent.ExternalLinkBlocked(
            request = request,
            scheme = "market",
            reason = "unsupported_scheme_market"
        )
        listOf(mediated, blocked).forEach { event ->
            val text = event.logLine()
            assertFalse(text.contains("http"))
            assertFalse(text.contains("//"))
            assertFalse(text.contains("wikipedia"))
            assertTrue(event.metadata.containsKey("scheme"))
        }
        assertEquals("https", mediated.metadata["scheme"])
        assertEquals("unsupported_scheme_market", blocked.metadata["reason"])
    }

    @Test
    fun `a console error carries the level and a count, never the message`() {
        val event = AnkiRenderEvent.JavascriptConsoleError(request = request, level = "error", errorCount = 9L)
        assertEquals("error", event.metadata["level"])
        assertEquals("9", event.metadata["js_errors"])
        assertFalse(event.logLine().contains("Uncaught"))
        assertFalse(event.metadata.keys.any { it.contains("message") || it.contains("text") })
    }

    @Test
    fun `a failure carries a token and a recoverability flag, never a description`() {
        val event = AnkiRenderEvent.Failed(
            request = request,
            failure = AnkiRenderFailure.WebViewLoadFailure(-12, "bad_url"),
            fallbackShown = false
        )
        assertEquals("webview_load_bad_url_-12", event.metadata["failure"])
        assertEquals("true", event.metadata["recoverable"])
        assertEquals("false", event.metadata["fallback_shown"])
        val text = event.logLine()
        assertFalse("the platform description can embed a URL", text.contains("net::ERR"))
        assertFalse(text.contains("http"))
    }

    @Test
    fun `a stale-callback event explains the race with identity only`() {
        val event = AnkiRenderEvent.StaleCallbackIgnored(
            stale = request.copy(generation = 2L, side = AnkiCardSide.QUESTION),
            active = request.copy(generation = 5L),
            callback = AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_PAGE_FINISHED
        )
        assertEquals("page_finished", event.metadata["callback"])
        assertEquals("2", event.metadata["stale_gen"])
        assertEquals("QUESTION", event.metadata["stale_side"])
        assertEquals("5", event.metadata["active_gen"])
        assertEquals("true", event.metadata["same_turn"])
        // Nothing was active at all: the event still says so without inventing an identity.
        val orphan = AnkiRenderEvent.StaleCallbackIgnored(stale = request, active = null, callback = "link")
        assertEquals("-", orphan.metadata["active_gen"])
        assertEquals("false", orphan.metadata["same_turn"])
        assertEquals(request.turnId.value, orphan.turnId)
    }

    @Test
    fun `surface lifecycle reasons are a closed vocabulary`() {
        val reasons = listOf(
            AnkiRenderEvent.SurfaceReleased.REASON_DISPOSED,
            AnkiRenderEvent.SurfaceReleased.REASON_RECREATED,
            AnkiRenderEvent.SurfaceReleased.REASON_SESSION_END
        )
        assertEquals(setOf("disposed", "recreated", "session_end"), reasons.toSet())
        val categories = listOf(
            AnkiRenderFailure.WebViewUnavailable.CATEGORY_CREATION_FAILED,
            AnkiRenderFailure.WebViewUnavailable.CATEGORY_NO_SURFACE,
            AnkiRenderFailure.WebViewUnavailable.CATEGORY_RELEASED
        )
        assertEquals(setOf("creation_failed", "no_surface", "released"), categories.toSet())
    }

    @Test
    fun `a log line is one bounded line per event`() {
        all.forEach { event ->
            val line = event.logLine()
            assertTrue("${event.name} must start with its name", line.startsWith(event.name))
            assertFalse("${event.name} must not span lines", line.contains('\n'))
            assertTrue("${event.name} is too long to be a log line: ${line.length}", line.length <= 256)
        }
    }

    @Test
    fun `metadata keys are a bounded vocabulary`() {
        val keys = all.flatMap { it.metadata.keys }.distinct().sorted()
        keys.forEach { key ->
            assertTrue("'$key' must be a short snake_case key", key.length <= 24)
            assertTrue("'$key' must be lowercase", key.all { it.isLowerCase() || it.isDigit() || it == '_' })
        }
        assertTrue(keys.containsAll(listOf("card", "side", "gen", "mode", "presentation")))
        // Every value in the vocabulary is an identifier, an enum name, a token, a boolean or a number —
        // there is no key whose value could be a passage of a card.
        all.flatMap { it.metadata.entries }.forEach { (key, value) ->
            assertTrue(
                "'$key'='$value' is too long to be a bounded diagnostic value",
                value.length <= 48
            )
            assertFalse("'$key'='$value' looks like markup", value.contains("<") || value.contains(">"))
        }
    }

    @Test
    fun `the html metadata key is a length, not the document`() {
        val event = AnkiRenderEvent.Started(
            request = request,
            mode = AnkiCardRenderMode.ORIGINAL,
            presentation = AnkiRenderPresentation.ORIGINAL,
            javascriptPolicy = AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY,
            htmlLength = 9_999,
            verbatimDocument = true
        )
        assertEquals("9999", event.metadata["html"])
        assertEquals("true", event.metadata["verbatim"])
        // A Compose presentation has no document at all, and says so with a dash.
        val compose = event.copy(presentation = AnkiRenderPresentation.CLEAN, htmlLength = null, verbatimDocument = false)
        assertEquals("-", compose.metadata["html"])
    }

    private companion object {
        /** The number of event types this file constructs one instance of. */
        const val AnkiRenderEventTypeCount = 14
    }
}
