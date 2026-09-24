package com.studyagent.client.render

import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardRenderPlan
import com.studyagent.client.core.render.AnkiCardRenderSurface
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiExternalLinkRequest
import com.studyagent.client.core.render.AnkiLinkDecision
import com.studyagent.client.core.render.AnkiRenderEvent
import com.studyagent.client.core.render.AnkiRenderFailure
import com.studyagent.client.core.render.AnkiRenderPerformance
import com.studyagent.client.core.render.AnkiRenderPerformanceSnapshot
import com.studyagent.client.core.render.AnkiRenderPresentation
import com.studyagent.client.core.render.AnkiRenderRequestId
import com.studyagent.client.core.render.AnkiRenderState
import com.studyagent.client.core.render.AnkiRenderSurfaceKind
import com.studyagent.client.core.render.AnkiCardRenderController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 08 STEP 103-§110 — the renderer state machine, tested on the JVM against the recording surface.
 *
 * What these tests pin down is exactly what makes the WebView layer safe to be boring: request identity,
 * the generation that makes a late Chromium callback recognisably late (INV-ANKI-RENDER-06), the
 * no-op-on-recomposition guarantee (INV-RENDER-23), scroll ownership (INV-RENDER-24), recovery from a
 * dead renderer process (INV-RENDER-05), the fallback order (INV-RENDER-20), and the fact that nothing
 * the renderer does is a rating (INV-RENDER-19).
 */
class AnkiCardRenderControllerTest {

    /** One controller, one recording surface, one clock, one event log: a whole render session. */
    private class Harness(
        val surfaceKind: AnkiRenderSurfaceKind = AnkiRenderSurfaceKind.BROWSING
    ) {
        val clock = TestRenderClock()
        val events = RenderEventRecorder()
        val performance = AnkiRenderPerformance()
        val links = mutableListOf<AnkiExternalLinkRequest>()
        val controller = AnkiCardRenderController(
            surfaceKind = surfaceKind,
            clock = clock,
            performance = performance,
            onEvent = events.sink,
            onExternalLink = { links += it }
        )
        val surface = FakeRenderSurface()

        val state: AnkiRenderState get() = controller.state.value
        val request: AnkiRenderRequestId get() = requireNotNull(controller.activeRequest)

        /** Attach the surface and make a card ready on screen — the ordinary steady state. */
        fun show(
            card: AnkiRenderedCard = renderCard(),
            side: AnkiCardSide = AnkiCardSide.QUESTION,
            turn: ReviewTurnId = turnId(),
            config: AnkiCardRenderConfig = AnkiCardRenderConfig.DEFAULT,
            durationMs: Long = 12L
        ): AnkiRenderRequestId {
            controller.attachSurface(surface)
            controller.submit(card, turn, side, config)
            val id = request
            clock.advance(durationMs)
            controller.onPageFinished(id)
            return id
        }

        fun snapshot(): AnkiRenderPerformanceSnapshot = performance.snapshot()
    }

    // ------------------------------------------------------------------ steady state

    @Test
    fun `a fresh controller is idle and has presented nothing`() {
        val harness = Harness()
        assertEquals(AnkiRenderState.Idle, harness.state)
        assertNull(harness.controller.activeRequest)
        assertEquals(0L, harness.controller.documentCount)
        assertFalse(harness.controller.isDisposed)
    }

    @Test
    fun `an ORIGINAL submission loads before it reports ready`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)

        val loading = harness.state as AnkiRenderState.Loading
        assertEquals(AnkiCardSide.QUESTION, loading.request.side)
        assertEquals(1L, loading.request.generation)
        assertEquals(1, harness.surface.documents.size)
        assertTrue(harness.controller.activePlan is AnkiCardRenderPlan.Original)
    }

    @Test
    fun `page finished publishes ready with the measured duration`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)
        harness.clock.advance(23L)
        harness.controller.onPageFinished(harness.request)

        val ready = harness.state as AnkiRenderState.Ready
        assertEquals(AnkiRenderPresentation.ORIGINAL, ready.presentation)
        assertEquals(23L, ready.loadDurationMs)
        assertTrue(ready.pageFinished)
        assertTrue(harness.surface.lastHtml!!.contains(QUESTION_MARKER))
    }

    @Test
    fun `a submission before the surface exists waits instead of degrading`() {
        val harness = Harness()
        // Compose applies effects in composition order, so this ordering is real, not exotic (STEP 13).
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)
        val waiting = harness.state as AnkiRenderState.Loading
        assertEquals(0, harness.surface.documents.size)

        harness.controller.attachSurface(harness.surface)
        assertEquals("attaching presents the same request", waiting.request.side, harness.request.side)
        assertEquals(1, harness.surface.documents.size)
        assertTrue(harness.surface.lastHtml!!.contains(QUESTION_MARKER))
    }

    // ------------------------------------------------------------------ sides and turns

    @Test
    fun `flipping the side stays inside the same review turn`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.show(card, AnkiCardSide.QUESTION, turn)
        val questionRequest = harness.request

        harness.controller.submit(card, turn, AnkiCardSide.ANSWER)
        harness.clock.advance(9L)
        harness.controller.onPageFinished(harness.request)

        val ready = harness.state as AnkiRenderState.Ready
        // INV-ANKI-RENDER-05: same turn, same card, new side, new generation.
        assertEquals(turn, ready.request.turnId)
        assertEquals(questionRequest.cardRef, ready.request.cardRef)
        assertEquals(AnkiCardSide.ANSWER, ready.request.side)
        assertEquals(questionRequest.generation + 1L, ready.request.generation)
        assertTrue(harness.surface.lastHtml!!.contains(ANSWER_MARKER))
    }

    @Test
    fun `the answer document is the backend's answer, never a concatenation`() {
        val harness = Harness()
        harness.show(renderCard(), AnkiCardSide.ANSWER)
        val html = harness.surface.lastHtml!!
        assertEquals(
            "the question appears exactly once, because Anki's answer already contains it",
            1,
            Regex(Regex.escape(QUESTION_MARKER)).findAll(html).count()
        )
        assertEquals(1, Regex(Regex.escape(ANSWER_MARKER)).findAll(html).count())
        assertTrue(html.contains("<hr id=answer>"))
    }

    @Test
    fun `a new card in a new turn is a new render request`() {
        val harness = Harness()
        harness.show(renderCard("card-1"), AnkiCardSide.QUESTION, turnId("1:card-1:1"))
        val first = harness.request

        val nextCard = renderCard("card-2")
        harness.controller.submit(nextCard, turnId("2:card-2:1"), AnkiCardSide.QUESTION)

        // INV-ANKI-RENDER-06: a new card never inherits the previous request's identity.
        assertFalse(first == harness.request)
        assertEquals(turnId("2:card-2:1"), harness.request.turnId)
        assertEquals(nextCard.ref, harness.request.cardRef)
        assertTrue(first.generation < harness.request.generation)
        assertTrue(harness.surface.lastHtml!!.contains(QUESTION_MARKER))
        assertEquals(2L, harness.controller.documentCount)
    }

    @Test
    fun `generations are monotonic and never reused, even after failures`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        val seen = mutableListOf<Long>()
        repeat(5) { index ->
            harness.controller.submit(renderCard("card-$index"), turnId("t-$index"), AnkiCardSide.QUESTION)
            seen += harness.request.generation
        }
        assertEquals(seen.sorted().distinct(), seen)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), seen)
    }

    @Test
    fun `retry re-presents the same turn, card and side with a new generation`() {
        val harness = Harness()
        harness.show()
        val before = harness.request

        harness.controller.onRendererProcessGone(before, didCrash = true)
        assertTrue(harness.controller.retry())

        val after = harness.request
        assertEquals(before.turnId, after.turnId)
        assertEquals(before.cardRef, after.cardRef)
        assertEquals(before.side, after.side)
        assertTrue(after.generation > before.generation)
        // A retry is a presentation attempt, not a rating: the renderer emits no scheduler vocabulary
        // at all (INV-ANKI-RENDER-01/19), only presentation facts.
        harness.events.names.forEach { name -> assertTrue(name.startsWith("ANKI_RENDER_")) }
        assertEquals(2, harness.surface.documents.size)
    }

    // ------------------------------------------------------------------ stale callbacks

    @Test
    fun `a page-finished callback from a superseded document is ignored and reported`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.controller.attachSurface(harness.surface)

        harness.controller.submit(card, turn, AnkiCardSide.QUESTION)
        val stale = harness.request
        harness.controller.submit(card, turn, AnkiCardSide.ANSWER)
        val current = harness.request

        // Chromium finishes the *first* document after the second was already submitted (STEP 18).
        harness.controller.onPageFinished(stale)

        val still = harness.state as AnkiRenderState.Loading
        assertEquals(current, still.request)
        assertEquals(1, harness.events.count(AnkiRenderEvent.StaleCallbackIgnored.NAME))
        val ignored = harness.events.last<AnkiRenderEvent.StaleCallbackIgnored>()!!
        assertEquals(stale, ignored.stale)
        assertEquals(current, ignored.active)
        assertEquals(AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_PAGE_FINISHED, ignored.callback)
        assertEquals(1L, harness.snapshot().staleCallbacksIgnored)
    }

    @Test
    fun `every WebView callback path rejects a stale request`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(card, turn, AnkiCardSide.QUESTION)
        val stale = harness.request
        harness.controller.submit(card, turn, AnkiCardSide.ANSWER)

        harness.controller.onPageStarted(stale)
        harness.controller.onLoadFailure(stale, errorCode = -8)
        harness.controller.onSslErrorBlocked(stale, "untrusted")
        harness.controller.onRendererProcessGone(stale, didCrash = true)
        harness.controller.onJavascriptConsoleError(stale, "error")
        harness.controller.onWebPermissionDenied(stale, 1)
        harness.controller.onJavascriptDialogSuppressed(stale, "alert")

        // Nothing above may have touched the live presentation: the answer side is still loading, the
        // renderer-process failure never landed, and no permission was ever counted.
        assertTrue(harness.state is AnkiRenderState.Loading)
        assertEquals(AnkiCardSide.ANSWER, harness.request.side)
        assertTrue(
            "each stale callback that goes through rejectStale is observable",
            harness.events.count(AnkiRenderEvent.StaleCallbackIgnored.NAME) >= 4
        )
        assertEquals(0L, harness.snapshot().rendererProcessFailures)
        assertEquals(0L, harness.snapshot().webPermissionsDenied)
        assertEquals(0L, harness.snapshot().javascriptErrors)
    }

    @Test
    fun `rapid side toggling leaves only the latest request authoritative`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.controller.attachSurface(harness.surface)

        val pending = mutableListOf<AnkiRenderRequestId>()
        listOf(
            AnkiCardSide.QUESTION,
            AnkiCardSide.ANSWER,
            AnkiCardSide.QUESTION,
            AnkiCardSide.ANSWER
        ).forEach { side ->
            harness.controller.submit(card, turn, side)
            pending += harness.request
        }

        // Callbacks arrive out of order, as they do on a real device: the three superseded documents
        // finish first, in reverse order, and only then does the live one.
        val latest = pending.last()
        pending.dropLast(1).reversed().forEach { harness.controller.onPageFinished(it) }
        harness.controller.onPageFinished(latest)

        val ready = harness.state as AnkiRenderState.Ready
        assertEquals(latest, ready.request)
        assertEquals(AnkiCardSide.ANSWER, ready.request.side)
        assertTrue(harness.surface.lastHtml!!.contains(ANSWER_MARKER))
        assertEquals(pending.size - 1, harness.snapshot().staleCallbacksIgnored)
    }

    // ------------------------------------------------------------------ recomposition safety

    @Test
    fun `an identical resubmission reloads nothing`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        val config = AnkiCardRenderConfig.DEFAULT
        harness.show(card, AnkiCardSide.QUESTION, turn, config)
        val documentsBefore = harness.surface.documents.size
        val resetsBefore = harness.surface.resetScrollCalls
        val requestBefore = harness.request
        val eventsBefore = harness.events.events.size

        // INV-ANKI-RENDER-23: a recomposition with the same inputs must not disturb a rendered card.
        val changed = harness.controller.submit(card, turn, AnkiCardSide.QUESTION, config)

        assertFalse("an identical submission is a no-op", changed)
        assertEquals(documentsBefore, harness.surface.documents.size)
        assertEquals(resetsBefore, harness.surface.resetScrollCalls)
        assertEquals(requestBefore, harness.request)
        assertEquals(eventsBefore, harness.events.events.size)
        assertTrue(harness.state is AnkiRenderState.Ready)
    }

    @Test
    fun `an equal card object from a different backend read is still the same presentation`() {
        val harness = Harness()
        val turn = turnId()
        harness.show(renderCard("card-1"), AnkiCardSide.QUESTION, turn)
        // A fresh AnkiRenderedCard instance carrying identical content (the session state re-emitting).
        val again = harness.controller.submit(renderCard("card-1"), turn, AnkiCardSide.QUESTION)
        assertFalse(again)
        assertEquals(1, harness.surface.documents.size)
    }

    @Test
    fun `a config change is a real re-presentation, not a silent one`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.show(card, AnkiCardSide.QUESTION, turn)
        val zoomed = harness.controller.submit(
            card,
            turn,
            AnkiCardSide.QUESTION,
            AnkiCardRenderConfig.DEFAULT.copy(textScale = 1.4f)
        )
        assertTrue(zoomed)
        assertEquals(2, harness.surface.documents.size)
        assertEquals(140, harness.surface.lastDocument!!.textZoomPercent)
    }

    // ------------------------------------------------------------------ scroll ownership

    @Test
    fun `scroll resets once per new document and never on recomposition`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.show(card, AnkiCardSide.QUESTION, turn)
        assertEquals(1, harness.surface.resetScrollCalls)

        // Recomposition: no reset.
        harness.controller.submit(card, turn, AnkiCardSide.QUESTION)
        assertEquals(1, harness.surface.resetScrollCalls)

        // Side flip: a new document, so one more reset (INV-RENDER-24).
        harness.controller.submit(card, turn, AnkiCardSide.ANSWER)
        harness.controller.onPageFinished(harness.request)
        assertEquals(2, harness.surface.resetScrollCalls)

        // New card: one more.
        harness.controller.submit(renderCard("card-9"), turnId("9:card-9:1"), AnkiCardSide.QUESTION)
        harness.controller.onPageFinished(harness.request)
        assertEquals(3, harness.surface.resetScrollCalls)
    }

    // ------------------------------------------------------------------ CLEAN mode

    @Test
    fun `CLEAN never touches the WebView`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        val card = renderCard()
        harness.controller.submit(
            card,
            turnId(),
            AnkiCardSide.ANSWER,
            AnkiCardRenderConfig.DEFAULT.copy(mode = AnkiCardRenderMode.CLEAN)
        )

        val ready = harness.state as AnkiRenderState.Ready
        assertEquals(AnkiRenderPresentation.CLEAN, ready.presentation)
        assertTrue(ready.pageFinished.not())
        assertEquals(0, harness.surface.documents.size)
        assertEquals(0L, harness.controller.documentCount)
        assertEquals(0L, harness.snapshot().documentsLoaded)
        assertEquals(card.answerText, (harness.controller.activePlan as? AnkiCardRenderPlan.CleanText)?.text)
    }

    @Test
    fun `switching from ORIGINAL to CLEAN is a new request on the same turn`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.show(card, AnkiCardSide.QUESTION, turn)
        val originalRequest = harness.request

        harness.controller.submit(
            card,
            turn,
            AnkiCardSide.QUESTION,
            AnkiCardRenderConfig.DEFAULT.copy(mode = AnkiCardRenderMode.CLEAN)
        )
        val cleanRequest = harness.request

        assertEquals(turn, cleanRequest.turnId)
        assertTrue(cleanRequest.generation > originalRequest.generation)
        assertEquals(AnkiRenderPresentation.CLEAN, (harness.state as AnkiRenderState.Ready).presentation)
        assertEquals(1, harness.surface.documents.size)
    }

    @Test
    fun `VOICE_STUDY surfaces resolve ADAPTIVE to voice focus`() {
        val harness = Harness(surfaceKind = AnkiRenderSurfaceKind.VOICE_STUDY)
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(
            renderCard(),
            turnId(),
            AnkiCardSide.QUESTION,
            AnkiCardRenderConfig.DEFAULT.copy(mode = AnkiCardRenderMode.ADAPTIVE)
        )
        assertEquals(AnkiCardRenderMode.VOICE_FOCUS, harness.controller.activeMode)
        assertEquals(AnkiRenderPresentation.CLEAN, (harness.state as AnkiRenderState.Ready).presentation)
        assertEquals(0, harness.surface.documents.size)
    }

    @Test
    fun `BROWSING surfaces resolve ADAPTIVE to original`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(
            renderCard(),
            turnId(),
            AnkiCardSide.QUESTION,
            AnkiCardRenderConfig.DEFAULT.copy(mode = AnkiCardRenderMode.ADAPTIVE)
        )
        assertEquals(AnkiCardRenderMode.ORIGINAL, harness.controller.activeMode)
        assertTrue(harness.controller.activePlan is AnkiCardRenderPlan.Original)
        assertTrue(harness.state is AnkiRenderState.Loading)
        assertEquals(1, harness.surface.documents.size)
    }

    // ------------------------------------------------------------------ failures and recovery

    @Test
    fun `a missing HTML channel falls back to text and is measured`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(renderCard(questionHtml = null), turnId(), AnkiCardSide.QUESTION)

        val ready = harness.state as AnkiRenderState.Ready
        assertEquals(AnkiRenderPresentation.CLEAN_FALLBACK, ready.presentation)
        assertEquals(0, harness.surface.documents.size)
        assertEquals(1L, harness.snapshot().fallbacksUsed)
        val event = harness.events.last<AnkiRenderEvent.FallbackUsed>()!!
        assertEquals(AnkiCardRenderPlan.TOKEN_HTML_UNAVAILABLE, event.reason)
        assertNull("a Compose-text degradation carries no WebView failure token", event.failureToken)
        assertFalse("the event names a token, never the content", event.metadata.any { it.value.contains(QUESTION_MARKER) })
    }

    @Test
    fun `a WebView that could not be created falls back with the precise category`() {
        val harness = Harness()
        harness.controller.onSurfaceCreationFailed()
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)

        assertEquals(AnkiRenderPresentation.CLEAN_FALLBACK, (harness.state as AnkiRenderState.Ready).presentation)
        val unavailable = harness.events.last<AnkiRenderEvent.SurfaceUnavailable>()!!
        assertEquals(AnkiRenderFailure.WebViewUnavailable.CATEGORY_CREATION_FAILED, unavailable.category)
    }

    @Test
    fun `a released surface is reported as released, not as a generic failure`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        harness.surface.isUsable = false
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)

        assertEquals(AnkiRenderPresentation.CLEAN_FALLBACK, (harness.state as AnkiRenderState.Ready).presentation)
        assertEquals(
            AnkiRenderFailure.WebViewUnavailable.CATEGORY_RELEASED,
            harness.events.last<AnkiRenderEvent.SurfaceUnavailable>()!!.category
        )
    }

    @Test
    fun `with no channel left the state fails instead of showing invented content`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        // GATE 07 requires *some* question representation, so the side with nothing at all is the answer.
        harness.controller.submit(
            renderCard(answerHtml = null, answerText = null),
            turnId(),
            AnkiCardSide.ANSWER
        )

        val failed = harness.state as AnkiRenderState.Failed
        assertEquals(AnkiRenderFailure.HtmlUnavailable(AnkiCardSide.ANSWER), failed.failure)
        assertNull("no fallback text exists, so none is claimed", failed.presentation)
        assertEquals(0L, harness.snapshot().fallbacksUsed)
        assertEquals(1, harness.events.count(AnkiRenderEvent.Failed.NAME))
    }

    @Test
    fun `a main-frame load failure fails the presentation but keeps the text fallback`() {
        val harness = Harness()
        val id = harness.show()
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)
        harness.controller.onLoadFailure(harness.request, errorCode = -8)

        val failed = harness.state as AnkiRenderState.Failed
        val failure = failed.failure as AnkiRenderFailure.WebViewLoadFailure
        assertEquals(-8, failure.errorCode)
        assertEquals("timeout", failure.category)
        assertTrue(failure.isRecoverable)
        assertEquals(AnkiRenderPresentation.CLEAN_FALLBACK, failed.presentation)
        assertTrue(id.generation < harness.request.generation)
    }

    @Test
    fun `an unmapped WebView error code stays unmapped instead of being guessed`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)
        harness.controller.onLoadFailure(harness.request, errorCode = -4711)
        val failure = (harness.state as AnkiRenderState.Failed).failure as AnkiRenderFailure.WebViewLoadFailure
        assertEquals("unmapped", failure.category)
    }

    @Test
    fun `a TLS error is blocked and never proceeded`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)
        harness.controller.onSslErrorBlocked(harness.request, "untrusted")

        val failed = harness.state as AnkiRenderState.Failed
        assertSame(AnkiRenderFailure.SslErrorBlocked, failed.failure)
        assertEquals(1, harness.events.count(AnkiRenderEvent.SslErrorBlocked.NAME))
        assertEquals("ssl_error_blocked", failed.failure.token)
    }

    @Test
    fun `a dead renderer process fails, keeps the turn, and recovers on a new surface`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        val id = harness.show(card, AnkiCardSide.ANSWER, turn)

        harness.controller.onRendererProcessGone(id, didCrash = true)

        val failed = harness.state as AnkiRenderState.Failed
        val failure = failed.failure as AnkiRenderFailure.RendererProcessGone
        assertTrue(failure.didCrash)
        assertEquals("renderer_process_crashed", failure.token)
        assertTrue(failure.isRecoverable)
        assertEquals(AnkiRenderPresentation.CLEAN_FALLBACK, failed.presentation)
        assertEquals(1L, harness.snapshot().rendererProcessFailures)
        assertEquals(turn, failed.request.turnId)

        // INV-ANKI-RENDER-05 / STEP 73: a new surface re-presents the SAME turn, card and side.
        val replacement = FakeRenderSurface()
        harness.controller.attachSurface(replacement)

        val recovered = harness.request
        assertEquals(turn, recovered.turnId)
        assertEquals(card.ref, recovered.cardRef)
        assertEquals(AnkiCardSide.ANSWER, recovered.side)
        assertTrue(recovered.generation > id.generation)
        assertEquals(1, replacement.documents.size)
        assertTrue(replacement.lastHtml!!.contains(ANSWER_MARKER))
        assertEquals("the dead surface received nothing further", 1, harness.surface.documents.size)
    }

    @Test
    fun `a low-memory renderer kill is distinguished from a crash`() {
        val harness = Harness()
        val id = harness.show()
        harness.controller.onRendererProcessGone(id, didCrash = false)
        val failure = (harness.state as AnkiRenderState.Failed).failure as AnkiRenderFailure.RendererProcessGone
        assertFalse(failure.didCrash)
        assertEquals("renderer_process_killed", failure.token)
    }

    @Test
    fun `a failed presentation contributes no latency sample`() {
        val harness = Harness()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)
        harness.clock.advance(500L)
        harness.controller.onLoadFailure(harness.request, errorCode = -11)
        assertEquals("a failure is not a fast success", 0L, harness.snapshot().documentLoad.lifetimeSamples)
    }

    // ------------------------------------------------------------------ links and scripts

    @Test
    fun `an https card link is mediated outward and never navigated in the WebView`() {
        val harness = Harness()
        val id = harness.show()
        val overridden = harness.controller.requestNavigation(id, "https://en.wikipedia.org/wiki/Heart")

        assertTrue("the WebView must not navigate away from the card", overridden)
        assertEquals(1, harness.links.size)
        assertEquals("https://en.wikipedia.org/wiki/Heart", harness.links.single().url)
        assertEquals(id, harness.links.single().requestId)
        assertEquals(AnkiLinkDecision.OPEN_EXTERNALLY, harness.links.single().classification.decision)
        assertEquals(1L, harness.snapshot().externalLinksMediated)
        // The card is still exactly as it was: the request never changed.
        assertEquals(id, harness.request)
        assertTrue(harness.state is AnkiRenderState.Ready)
    }

    @Test
    fun `a same-document anchor stays in the page`() {
        val harness = Harness()
        val id = harness.show()
        assertFalse(harness.controller.requestNavigation(id, "#answer"))
        assertTrue(harness.links.isEmpty())
        assertEquals(0L, harness.snapshot().externalLinksBlocked)
    }

    @Test
    fun `unknown and dangerous schemes are blocked without an intent`() {
        val harness = Harness()
        val id = harness.show()
        listOf("javascript:alert(1)", "file:///sdcard/x.html", "intent://scan#Intent;end", "market://details?id=x")
            .forEach { url ->
                assertTrue(harness.controller.requestNavigation(id, url))
            }
        assertTrue("nothing is handed to the platform", harness.links.isEmpty())
        assertEquals(4L, harness.snapshot().externalLinksBlocked)
        val schemes = harness.events.only<AnkiRenderEvent.ExternalLinkBlocked>().map { it.scheme }
        assertEquals(listOf("javascript", "file", "intent", "market"), schemes)
    }

    @Test
    fun `a navigation request from a stale document is refused and reported`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(card, turn, AnkiCardSide.QUESTION)
        val stale = harness.request
        harness.controller.submit(card, turn, AnkiCardSide.ANSWER)

        assertTrue("a stale link must not open anything", harness.controller.requestNavigation(stale, "https://example.org"))
        assertTrue(harness.links.isEmpty())
        assertEquals(1, harness.events.count(AnkiRenderEvent.StaleCallbackIgnored.NAME))
    }

    @Test
    fun `a javascript console error is counted but never transcribed`() {
        val harness = Harness()
        val id = harness.show()
        harness.controller.onJavascriptConsoleError(id, "error")
        harness.controller.onJavascriptConsoleError(id, "error")

        assertEquals(2L, harness.performance.javascriptErrorCount)
        val event = harness.events.last<AnkiRenderEvent.JavascriptConsoleError>()!!
        assertEquals("error", event.level)
        assertEquals(2L, event.errorCount)
        assertFalse(
            "a console message can echo card content, so it is never carried",
            event.metadata.values.any { it.contains(QUESTION_MARKER) || it.contains("<") }
        )
        assertTrue("the card stays rendered", harness.state is AnkiRenderState.Ready)
    }

    @Test
    fun `a suppressed dialog is reported without its text`() {
        val harness = Harness()
        val id = harness.show()
        harness.controller.onJavascriptDialogSuppressed(id, "alert")
        val event = harness.events.last<AnkiRenderEvent.JavascriptDialogSuppressed>()!!
        assertEquals("alert", event.kind)
        assertTrue(harness.state is AnkiRenderState.Ready)
    }

    @Test
    fun `a card script asking for the microphone is denied and counted`() {
        val harness = Harness()
        val id = harness.show()
        harness.controller.onWebPermissionDenied(id, resourceCount = 1)

        assertEquals(1L, harness.snapshot().webPermissionsDenied)
        assertEquals(1, harness.events.count(AnkiRenderEvent.WebPermissionDenied.NAME))
        // INV-ANKI-RENDER-31: Study-Agent's microphone belongs to the native voice subsystem.
        assertTrue(harness.state is AnkiRenderState.Ready)
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun `detaching a surface stops presentations but keeps the request`() {
        val harness = Harness()
        val id = harness.show()
        harness.controller.detachSurface(harness.surface)

        val replacement = FakeRenderSurface()
        harness.controller.attachSurface(replacement)
        assertEquals(id.turnId, harness.request.turnId)
        assertEquals(1, replacement.documents.size)
    }

    @Test
    fun `detaching a different surface is ignored`() {
        val harness = Harness()
        harness.show()
        val stranger = FakeRenderSurface()
        harness.controller.detachSurface(stranger)
        // The bound surface still receives documents.
        harness.controller.submit(renderCard("card-77"), turnId("77:card-77:1"), AnkiCardSide.QUESTION)
        assertEquals(2, harness.surface.documents.size)
        assertEquals(0, stranger.documents.size)
    }

    @Test
    fun `dispose returns to idle, ignores callbacks and releases nothing it does not own`() {
        val harness = Harness()
        val id = harness.show()

        harness.controller.dispose()

        assertEquals(AnkiRenderState.Idle, harness.state)
        assertNull(harness.controller.activeRequest)
        assertTrue(harness.controller.isDisposed)
        // STEP 14: the owner of the WebView destroys it — the controller never does.
        assertEquals(0, harness.surface.releaseCalls)
        assertEquals(1, harness.events.count(AnkiRenderEvent.SurfaceReleased.NAME))

        // Anything arriving after dispose is stale by construction.
        harness.controller.onPageFinished(id)
        harness.controller.onRendererProcessGone(id, didCrash = true)
        assertFalse(harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION))
        assertFalse(harness.controller.retry())
        assertEquals(AnkiRenderState.Idle, harness.state)
        assertEquals(1, harness.surface.documents.size)
    }

    @Test
    fun `dispose is idempotent`() {
        val harness = Harness()
        harness.show()
        harness.controller.dispose()
        harness.controller.dispose()
        assertEquals(1, harness.events.count(AnkiRenderEvent.SurfaceReleased.NAME))
    }

    @Test
    fun `attaching a surface after dispose releases it immediately`() {
        val harness = Harness()
        harness.controller.dispose()
        val late = FakeRenderSurface()
        harness.controller.attachSurface(late)
        assertEquals("the controller cannot keep a WebView it will never use", 1, late.releaseCalls)
        assertEquals(AnkiRenderState.Idle, harness.state)
    }

    // ------------------------------------------------------------------ measurement

    @Test
    fun `document loads and side transitions are measured separately`() {
        val harness = Harness()
        val card = renderCard()
        val turn = turnId()
        harness.show(card, AnkiCardSide.QUESTION, turn, durationMs = 40L)
        harness.controller.submit(card, turn, AnkiCardSide.ANSWER)
        harness.clock.advance(15L)
        harness.controller.onPageFinished(harness.request)

        val snapshot = harness.snapshot()
        assertEquals(2L, snapshot.documentsLoaded)
        assertEquals(2L, snapshot.documentLoad.lifetimeSamples)
        assertEquals(40L, snapshot.documentLoad.p95Ms)
        assertEquals("the second transition was a side flip", 1L, snapshot.sideTransition.lifetimeSamples)
        assertEquals(15L, snapshot.sideTransition.p50Ms)
        assertEquals(0L, snapshot.newCardTransition.lifetimeSamples)
    }

    @Test
    fun `a new card transition is measured as its own family`() {
        val harness = Harness()
        harness.show(renderCard("card-1"), AnkiCardSide.QUESTION, turnId("1:card-1:1"), durationMs = 30L)
        harness.controller.submit(renderCard("card-2"), turnId("2:card-2:2"), AnkiCardSide.QUESTION)
        harness.clock.advance(60L)
        harness.controller.onPageFinished(harness.request)

        val snapshot = harness.snapshot()
        assertEquals(1L, snapshot.newCardTransition.lifetimeSamples)
        assertEquals(60L, snapshot.newCardTransition.p50Ms)
        assertEquals(0L, snapshot.sideTransition.lifetimeSamples)
    }

    @Test
    fun `the first presentation is not counted as a transition`() {
        val harness = Harness()
        harness.show()
        val snapshot = harness.snapshot()
        assertEquals(0L, snapshot.sideTransition.lifetimeSamples)
        assertEquals(0L, snapshot.newCardTransition.lifetimeSamples)
        assertEquals(1L, snapshot.documentLoad.lifetimeSamples)
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun `no event carries card content`() {
        val harness = Harness()
        val card = renderCard(
            questionHtml = ARABIC_HTML,
            answerHtml = "$ARABIC_HTML<hr id=answer><p>$ANSWER_MARKER</p>",
            questionText = "ما هو القلب؟",
            answerText = ANSWER_TEXT
        )
        harness.controller.attachSurface(harness.surface)
        harness.controller.submit(card, turnId(), AnkiCardSide.QUESTION)
        harness.controller.onPageFinished(harness.request)
        harness.controller.submit(card, turnId(), AnkiCardSide.ANSWER)
        harness.controller.onPageFinished(harness.request)
        harness.controller.requestNavigation(harness.request, "https://example.org/secret-path?q=$ANSWER_MARKER")
        harness.controller.onJavascriptConsoleError(harness.request, "error")

        val logged = harness.events.allLoggedText
        listOf(QUESTION_MARKER, ANSWER_MARKER, "ما هو القلب", "<div", "<hr", "secret-path").forEach { needle ->
            assertFalse("renderer events must never contain '$needle'", logged.contains(needle))
        }
        assertTrue("the turn id is fine to log", logged.contains(turnId().value))
    }

    @Test
    fun `the document handed to the surface is the only place card content appears`() {
        val harness = Harness()
        harness.show()
        assertTrue(harness.surface.lastHtml!!.contains(QUESTION_MARKER))
        assertFalse(harness.events.allLoggedText.contains(QUESTION_MARKER))
    }

    // ------------------------------------------------------------------ surface seam

    @Test
    fun `the surface seam is the only way the controller reaches a WebView`() {
        val harness = Harness()
        val surface: AnkiCardRenderSurface = harness.surface
        harness.controller.attachSurface(surface)
        harness.controller.submit(renderCard(), turnId(), AnkiCardSide.QUESTION)
        // If the controller ever constructed a WebView itself, this test could not compile: the seam is
        // an interface with three members and no Android type in its signature.
        assertEquals(1, harness.surface.documents.size)
        assertTrue(harness.surface.documents.single().html.startsWith("<!doctype html>"))
    }
}
