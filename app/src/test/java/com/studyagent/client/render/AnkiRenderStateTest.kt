package com.studyagent.client.render

import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiJavascriptPolicy
import com.studyagent.client.core.render.AnkiRenderFailure
import com.studyagent.client.core.render.AnkiRenderPresentation
import com.studyagent.client.core.render.AnkiRenderRequestId
import com.studyagent.client.core.render.AnkiRenderState
import com.studyagent.client.core.render.AnkiRenderSurfaceKind
import com.studyagent.client.core.render.AnkiAdaptiveRenderPolicy
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.CardTextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 08 STEP 111-§113 — render state, request identity and configuration as values.
 *
 * The state machine is already covered end to end in [AnkiCardRenderControllerTest]; this file pins the
 * *contract of the values themselves*: tokens that are content-free, a presentation that says which
 * surface is on screen, a recoverability flag that only ever drives a retry-render affordance, and a
 * config that cannot be constructed into something the WebView layer would have to second-guess.
 */
class AnkiRenderStateTest {

    private val card = renderCard()
    private val turn = turnId()

    private fun request(
        side: AnkiCardSide = AnkiCardSide.QUESTION,
        generation: Long = 1L
    ) = AnkiRenderRequestId(turn, card.ref, side, generation)

    // ------------------------------------------------------------------ request identity

    @Test
    fun `a request is identified by turn, card, side and attempt`() {
        val a = request(AnkiCardSide.QUESTION, 1L)
        val b = request(AnkiCardSide.QUESTION, 1L)
        assertEquals(a, b)
        assertEquals(a.stableKey, b.stableKey)
        assertNotEquals(a, request(AnkiCardSide.ANSWER, 1L))
        assertNotEquals(a, request(AnkiCardSide.QUESTION, 2L))
        assertNotEquals(
            a,
            AnkiRenderRequestId(turnId("other-turn"), card.ref, AnkiCardSide.QUESTION, 1L)
        )
    }

    @Test
    fun `generations start at one because zero means no request yet`() {
        val failure = runCatching { request(generation = 0L) }.exceptionOrNull()
        assertTrue("a zero generation must not be constructible", failure is IllegalArgumentException)
        assertTrue(runCatching { request(generation = -1L) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `staleness is identity, not timing`() {
        val live = request(AnkiCardSide.ANSWER, 4L)
        val older = request(AnkiCardSide.QUESTION, 3L)
        // A lower generation is stale; so is an *equal* generation for a different side; and when
        // nothing is active, every callback is stale by construction.
        assertTrue(older.isStaleFor(live))
        assertTrue(request(AnkiCardSide.QUESTION, 4L).isStaleFor(live))
        assertFalse(live.isStaleFor(live))
        assertTrue(live.isStaleFor(null))
    }

    @Test
    fun `turn and target comparisons keep side flips inside one turn`() {
        val question = request(AnkiCardSide.QUESTION, 1L)
        val answer = request(AnkiCardSide.ANSWER, 2L)
        assertTrue(question.isSameTurn(answer))
        assertFalse(question.isSameTarget(answer))
        assertTrue(question.isSameTarget(request(AnkiCardSide.QUESTION, 7L)))
        assertEquals(2L, question.nextGeneration().generation)
    }

    @Test
    fun `the stable key is log-safe and carries no content`() {
        val key = request().stableKey
        assertTrue(key.contains(turn.value))
        assertTrue(key.contains(AnkiCardSide.QUESTION.name))
        assertFalse(key.contains(QUESTION_MARKER))
        assertEquals(key, request().toString())
    }

    // ------------------------------------------------------------------ state

    @Test
    fun `idle has no request and no presentation`() {
        assertNull(AnkiRenderState.Idle.request)
        assertNull(AnkiRenderState.Idle.presentation)
        assertEquals("idle", AnkiRenderState.Idle.token)
    }

    @Test
    fun `loading names the side but claims no presentation yet`() {
        val state = AnkiRenderState.Loading(request(AnkiCardSide.ANSWER))
        assertNull(state.presentation)
        assertEquals("loading_answer", state.token)
        assertEquals("loading_question", AnkiRenderState.Loading(request(AnkiCardSide.QUESTION)).token)
    }

    @Test
    fun `ready distinguishes a WebView page from a Compose presentation`() {
        val webView = AnkiRenderState.Ready(request(), AnkiRenderPresentation.ORIGINAL, 42L, pageFinished = true)
        val compose = AnkiRenderState.Ready(request(), AnkiRenderPresentation.CLEAN, 0L, pageFinished = false)
        assertEquals("ready_original", webView.token)
        assertEquals("ready_clean_compose", compose.token)
        assertEquals(AnkiRenderPresentation.ORIGINAL, webView.presentation)
        assertEquals(42L, webView.loadDurationMs)
    }

    @Test
    fun `a failed state says whether a fallback is on screen`() {
        val withFallback = AnkiRenderState.Failed(
            request(),
            AnkiRenderFailure.RendererProcessGone(didCrash = true),
            AnkiRenderPresentation.CLEAN_FALLBACK
        )
        val withoutFallback = AnkiRenderState.Failed(
            request(),
            AnkiRenderFailure.TextUnavailable(AnkiCardSide.ANSWER),
            null
        )
        assertTrue(withFallback.showingFallback)
        assertEquals("failed_renderer_process_crashed_with_fallback", withFallback.token)
        assertFalse(withoutFallback.showingFallback)
        assertEquals("failed_text_unavailable_answer", withoutFallback.token)
        assertNull(withoutFallback.presentation)
    }

    @Test
    fun `a load duration is never negative`() {
        assertTrue(
            runCatching {
                AnkiRenderState.Ready(request(), AnkiRenderPresentation.ORIGINAL, -1L, true)
            }.exceptionOrNull() is IllegalArgumentException
        )
        // `null` is the honest value for a presentation that never measured one.
        assertNull(AnkiRenderState.Ready(request(), AnkiRenderPresentation.CLEAN, null, false).loadDurationMs)
    }

    @Test
    fun `state tokens are content-free for every state`() {
        val states = listOf(
            AnkiRenderState.Idle,
            AnkiRenderState.Loading(request()),
            AnkiRenderState.Ready(request(), AnkiRenderPresentation.ORIGINAL, 5L, true),
            AnkiRenderState.Ready(request(), AnkiRenderPresentation.CLEAN_FALLBACK, 0L, false),
            AnkiRenderState.Failed(request(), AnkiRenderFailure.HtmlUnavailable(AnkiCardSide.QUESTION), null),
            AnkiRenderState.Failed(
                request(),
                AnkiRenderFailure.WebViewLoadFailure(-8, "timeout"),
                AnkiRenderPresentation.CLEAN_FALLBACK
            )
        )
        states.forEach { state ->
            val token = state.token
            assertFalse("'$token' must not contain card content", token.contains(QUESTION_MARKER))
            assertFalse("'$token' must not contain markup", token.contains("<"))
            assertTrue("'$token' must stay short", token.length < 64)
        }
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `only genuinely retryable failures are marked recoverable`() {
        val recoverable = listOf<AnkiRenderFailure>(
            AnkiRenderFailure.WebViewUnavailable(AnkiRenderFailure.WebViewUnavailable.CATEGORY_CREATION_FAILED),
            AnkiRenderFailure.WebViewLoadFailure(-8, "timeout"),
            AnkiRenderFailure.RendererProcessGone(true)
        )
        val terminal = listOf<AnkiRenderFailure>(
            AnkiRenderFailure.HtmlUnavailable(AnkiCardSide.QUESTION),
            AnkiRenderFailure.TextUnavailable(AnkiCardSide.ANSWER),
            // A refused TLS error is a property of the resource, not of this attempt: retrying the same
            // document would refuse it again, so no retry affordance is offered.
            AnkiRenderFailure.SslErrorBlocked
        )
        recoverable.forEach { assertTrue("${it.token} should be retryable", it.isRecoverable) }
        terminal.forEach {
            assertFalse(
                "${it.token} is a missing channel: retrying cannot produce content",
                it.isRecoverable
            )
        }
    }

    @Test
    fun `failure tokens are stable strings a report can quote`() {
        assertEquals("html_unavailable_question", AnkiRenderFailure.HtmlUnavailable(AnkiCardSide.QUESTION).token)
        assertEquals("html_unavailable_answer", AnkiRenderFailure.HtmlUnavailable(AnkiCardSide.ANSWER).token)
        assertEquals("text_unavailable_answer", AnkiRenderFailure.TextUnavailable(AnkiCardSide.ANSWER).token)
        assertEquals("webview_unavailable_creation_failed", AnkiRenderFailure.WebViewUnavailable("creation_failed").token)
        assertEquals("webview_load_timeout_-8", AnkiRenderFailure.WebViewLoadFailure(-8, "timeout").token)
        assertEquals("renderer_process_crashed", AnkiRenderFailure.RendererProcessGone(true).token)
        assertEquals("renderer_process_killed", AnkiRenderFailure.RendererProcessGone(false).token)
    }

    @Test
    fun `a failure never carries an exception message or a URL`() {
        // WebViewLoadFailure keeps the code and a token, deliberately not the platform description
        // (which can embed the failing URL, i.e. deck content).
        val failure = AnkiRenderFailure.WebViewLoadFailure(-12, "bad_url")
        val text = failure.toString()
        assertFalse(text.contains("http"))
        assertTrue(text.contains("-12"))
    }

    // ------------------------------------------------------------------ presentation

    @Test
    fun `presentation tells the UI which surface owns the pixels`() {
        assertTrue(AnkiRenderPresentation.ORIGINAL.isComposeText.not())
        assertTrue(AnkiRenderPresentation.CLEAN.isComposeText)
        assertTrue(AnkiRenderPresentation.CLEAN_FALLBACK.isComposeText)
        assertEquals("original", AnkiRenderPresentation.ORIGINAL.token)
        assertEquals("clean", AnkiRenderPresentation.CLEAN.token)
        assertEquals("clean_fallback", AnkiRenderPresentation.CLEAN_FALLBACK.token)
    }

    // ------------------------------------------------------------------ config

    @Test
    fun `the defaults are the fidelity defaults`() {
        val config = AnkiCardRenderConfig.DEFAULT
        assertEquals(AnkiCardRenderMode.ORIGINAL, config.mode)
        assertEquals(AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY, config.javascriptPolicy)
        assertEquals(CardTextDirection.AUTO, config.direction)
        assertEquals(1f, config.textScale, 0.0001f)
        assertEquals(100, config.textZoomPercent)
        assertFalse("night mode follows the app theme, and this app is dark", config.nightMode)
        assertTrue(AnkiCardRenderConfig.DARK_APP.nightMode)
    }

    @Test
    fun `text scale becomes a WebView text zoom, bounded`() {
        assertEquals(50, AnkiCardRenderConfig(textScale = 0.5f).textZoomPercent)
        assertEquals(300, AnkiCardRenderConfig(textScale = 3f).textZoomPercent)
        assertEquals(135, AnkiCardRenderConfig(textScale = 1.35f).textZoomPercent)
        val rejected = listOf(
            runCatching { AnkiCardRenderConfig(textScale = 0.1f) }.exceptionOrNull(),
            runCatching { AnkiCardRenderConfig(textScale = 10f) }.exceptionOrNull(),
            runCatching { AnkiCardRenderConfig(textScale = Float.NaN) }.exceptionOrNull(),
            runCatching { AnkiCardRenderConfig(textScale = Float.POSITIVE_INFINITY) }.exceptionOrNull()
        )
        rejected.forEach { assertTrue("an unusable scale must not be constructible", it is IllegalArgumentException) }
    }

    @Test
    fun `night mode is switched without touching anything else`() {
        val day = AnkiCardRenderConfig.DEFAULT
        val night = day.withNightMode(true)
        assertTrue(night.nightMode)
        assertEquals(day.mode, night.mode)
        assertEquals(day.direction, night.direction)
        assertEquals(day.textScale, night.textScale, 0.0001f)
        assertEquals(day.javascriptPolicy, night.javascriptPolicy)
        assertEquals(day, night.withNightMode(false))
    }

    @Test
    fun `ADAPTIVE resolves once, by surface kind, and never re-decides a concrete mode`() {
        assertEquals(AnkiCardRenderMode.ORIGINAL, AnkiAdaptiveRenderPolicy.resolve(AnkiRenderSurfaceKind.BROWSING))
        assertEquals(AnkiCardRenderMode.VOICE_FOCUS, AnkiAdaptiveRenderPolicy.resolve(AnkiRenderSurfaceKind.VOICE_STUDY))

        val adaptive = AnkiCardRenderConfig(mode = AnkiCardRenderMode.ADAPTIVE)
        assertTrue(adaptive.needsModeResolution)
        assertEquals(
            AnkiCardRenderMode.ORIGINAL,
            adaptive.resolvedFor(AnkiRenderSurfaceKind.BROWSING).mode
        )
        assertFalse(adaptive.resolvedFor(AnkiRenderSurfaceKind.BROWSING).needsModeResolution)

        // A mode a caller chose on purpose survives resolution untouched, on any surface.
        AnkiCardRenderMode.values()
            .filter { !it.isUnresolved }
            .forEach { mode ->
                AnkiRenderSurfaceKind.values().forEach { kind ->
                    assertEquals(mode, AnkiCardRenderConfig(mode = mode).resolvedFor(kind).mode)
                }
            }
        // Resolution is idempotent.
        val once = adaptive.resolvedFor(AnkiRenderSurfaceKind.VOICE_STUDY)
        assertEquals(once, once.resolvedFor(AnkiRenderSurfaceKind.BROWSING))
    }

    @Test
    fun `javascript policy has no native-bridge option`() {
        // INV-ANKI-RENDER-08/§09: the only policies are "card scripts may run" and "nothing runs".
        val names = AnkiJavascriptPolicy.values().map { it.name }
        assertEquals(2, names.size)
        assertTrue(names.containsAll(listOf("DISABLED", "CARD_TEMPLATE_ONLY")))
        names.forEach { name ->
            assertFalse("no bridge vocabulary in the policy", name.contains("BRIDGE"))
            assertFalse(name.contains("NATIVE"))
        }
        assertEquals(AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY, AnkiJavascriptPolicy.DEFAULT)
    }

    @Test
    fun `render modes cover the four documented surfaces and nothing else`() {
        assertEquals(
            setOf("ORIGINAL", "CLEAN", "VOICE_FOCUS", "ADAPTIVE"),
            AnkiCardRenderMode.values().map { it.name }.toSet()
        )
        assertTrue(AnkiCardRenderMode.ADAPTIVE.isUnresolved)
        AnkiCardRenderMode.values().filter { it != AnkiCardRenderMode.ADAPTIVE }.forEach {
            assertFalse(it.isUnresolved)
        }
        assertEquals(AnkiCardRenderMode.ORIGINAL, AnkiCardRenderMode.DEFAULT)
    }

    @Test
    fun `direction defaults to auto so the card decides`() {
        assertEquals(CardTextDirection.AUTO, CardTextDirection.DEFAULT)
        assertEquals(
            setOf("AUTO", "LTR", "RTL"),
            CardTextDirection.values().map { it.name }.toSet()
        )
    }

    @Test
    fun `the sides are exactly question and answer`() {
        assertEquals(setOf("QUESTION", "ANSWER"), AnkiCardSide.values().map { it.name }.toSet())
    }
}
