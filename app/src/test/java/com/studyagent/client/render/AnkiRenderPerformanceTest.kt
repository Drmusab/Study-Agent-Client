package com.studyagent.client.render

import com.studyagent.client.core.render.AnkiRenderPerformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 08 STEP 128 — the renderer's measurement surface.
 *
 * Two things are being protected here. First, the four latency families stay separate: mixing a
 * question→answer flip into the new-card number would make the report meaningless. Second, an unmeasured
 * family reports `-` and never `0ms`, so a renderer that never ran cannot look instant in a report
 * (the app's existing `NOT_MEASURED` rule).
 */
class AnkiRenderPerformanceTest {

    @Test
    fun `an untouched renderer reports nothing as measured`() {
        val performance = AnkiRenderPerformance()
        val snapshot = performance.snapshot()

        listOf(snapshot.webViewCreation, snapshot.documentLoad, snapshot.sideTransition, snapshot.newCardTransition)
            .forEach { stats ->
                assertFalse("'${stats.name}' was never measured", stats.measured)
                assertEquals(0L, stats.lifetimeSamples)
                assertEquals(0, stats.windowSamples)
                // The app's DiagnosticsFormatting.NOT_MEASURED: an unmeasured family says "-", never "0ms".
                assertEquals("-", stats.format())
            }
        assertEquals(0L, snapshot.documentsLoaded)
        assertEquals(0L, snapshot.fallbacksUsed)
        assertEquals(0L, snapshot.rendererProcessFailures)
        assertEquals(0L, snapshot.staleCallbacksIgnored)
        assertEquals(0L, snapshot.externalLinksBlocked)
        assertEquals(0L, snapshot.externalLinksMediated)
        assertEquals(0L, snapshot.webPermissionsDenied)
        assertEquals(0L, snapshot.javascriptErrors)
    }

    @Test
    fun `the four families are named for the report and kept apart`() {
        assertEquals("anki_render_webview_creation", AnkiRenderPerformance.NAME_WEBVIEW_CREATION)
        assertEquals("anki_render_document_load", AnkiRenderPerformance.NAME_DOCUMENT_LOAD)
        assertEquals("anki_render_side_transition", AnkiRenderPerformance.NAME_SIDE_TRANSITION)
        assertEquals("anki_render_new_card_transition", AnkiRenderPerformance.NAME_NEW_CARD_TRANSITION)

        val performance = AnkiRenderPerformance()
        performance.recordWebViewCreation(30L)
        performance.recordDocumentLoad(10L)
        performance.recordSideTransition(20L)
        performance.recordNewCardTransition(40L)
        val snapshot = performance.snapshot()

        assertEquals(30L, snapshot.webViewCreation.p50Ms)
        assertEquals(10L, snapshot.documentLoad.p50Ms)
        assertEquals(20L, snapshot.sideTransition.p50Ms)
        assertEquals(40L, snapshot.newCardTransition.p50Ms)
        assertEquals(AnkiRenderPerformance.NAME_DOCUMENT_LOAD, snapshot.documentLoad.name)
    }

    @Test
    fun `a window is bounded, so a long session cannot grow it without limit`() {
        val performance = AnkiRenderPerformance(windowSize = 4)
        repeat(1_000) { index -> performance.recordDocumentLoad(index.toLong()) }
        val stats = performance.snapshot().documentLoad

        assertEquals("lifetime count is honest", 1_000L, stats.lifetimeSamples)
        assertEquals("the window stays bounded", 4, stats.windowSamples)
        assertTrue(stats.p50Ms in 996L..999L)
        assertEquals(999L, stats.maxMs)
    }

    @Test
    fun `a negative sample cannot poison a percentile`() {
        val performance = AnkiRenderPerformance()
        performance.recordDocumentLoad(-500L)
        performance.recordDocumentLoad(20L)
        val stats = performance.snapshot().documentLoad
        assertEquals(0L, stats.minMs)
        assertEquals(20L, stats.maxMs)
    }

    @Test
    fun `WebView accounting keeps the live count bounded`() {
        val performance = AnkiRenderPerformance()
        // INV-ANKI-RENDER-22: one active reviewer WebView, and a recreation may briefly overlap.
        performance.recordWebViewCreation(25L)
        assertEquals(1L, performance.webViewsCreatedCount)
        assertEquals(1L, performance.snapshot().outstandingWebViews)
        assertTrue(performance.webViewCountBounded)

        // Recreation: the replacement exists before the dying one is released.
        performance.recordWebViewCreation(18L)
        assertEquals(2L, performance.snapshot().outstandingWebViews)
        assertFalse("two outstanding WebViews exceeds the bound", performance.webViewCountBounded)

        performance.recordWebViewRelease()
        assertEquals(1L, performance.snapshot().outstandingWebViews)
        assertTrue(performance.webViewCountBounded)

        performance.recordWebViewRelease()
        assertEquals(0L, performance.snapshot().outstandingWebViews)
        assertEquals(2L, performance.snapshot().webViewsCreated)
        assertEquals(2L, performance.snapshot().webViewsReleased)
        assertTrue(performance.snapshot().webViewCountBounded)
    }

    @Test
    fun `releases can never make the outstanding count negative`() {
        val performance = AnkiRenderPerformance()
        performance.recordWebViewRelease()
        performance.recordWebViewRelease()
        assertEquals(0L, performance.snapshot().outstandingWebViews)
        assertTrue(performance.snapshot().webViewCountBounded)
    }

    @Test
    fun `the maximum live WebView count is one`() {
        assertEquals(1L, AnkiRenderPerformance.MAX_LIVE_WEBVIEWS)
    }

    @Test
    fun `counters are independent`() {
        val performance = AnkiRenderPerformance()
        performance.recordFallbackUsed()
        performance.recordFallbackUsed()
        performance.recordRendererProcessFailure()
        performance.recordStaleCallbackIgnored()
        performance.recordStaleCallbackIgnored()
        performance.recordStaleCallbackIgnored()
        performance.recordExternalLinkBlocked()
        performance.recordExternalLinkMediated()
        performance.recordWebPermissionDenied()
        performance.recordJavascriptError()

        val snapshot = performance.snapshot()
        assertEquals(2L, snapshot.fallbacksUsed)
        assertEquals(1L, snapshot.rendererProcessFailures)
        assertEquals(3L, snapshot.staleCallbacksIgnored)
        assertEquals(1L, snapshot.externalLinksBlocked)
        assertEquals(1L, snapshot.externalLinksMediated)
        assertEquals(1L, snapshot.webPermissionsDenied)
        assertEquals(1L, snapshot.javascriptErrors)
        assertEquals(1L, performance.javascriptErrorCount)
        assertEquals(0L, snapshot.documentsLoaded)
    }

    @Test
    fun `the snapshot describes itself in one content-free line`() {
        val performance = AnkiRenderPerformance()
        performance.recordDocumentLoad(42L)
        val line = performance.snapshot().describe()
        assertTrue(line.contains("doc_load=p50=42ms"))
        assertTrue(line.contains("docs=1"))
        assertFalse("no card content in a metrics line", line.contains(QUESTION_MARKER))
        assertFalse(line.contains("<"))
    }

    @Test
    fun `an unmeasured family renders as a dash inside the summary`() {
        val line = AnkiRenderPerformance().snapshot().describe()
        assertTrue(line.contains("side=- new_card=-"))
        assertTrue(line.contains("webview_create=-"))
    }

    @Test
    fun `reset clears windows and counters together`() {
        val performance = AnkiRenderPerformance()
        performance.recordDocumentLoad(10L)
        performance.recordSideTransition(10L)
        performance.recordNewCardTransition(10L)
        performance.recordWebViewCreation(10L)
        performance.recordFallbackUsed()
        performance.recordStaleCallbackIgnored()

        performance.reset()
        val snapshot = performance.snapshot()
        assertEquals(0L, snapshot.documentLoad.lifetimeSamples)
        assertEquals(0L, snapshot.sideTransition.lifetimeSamples)
        assertEquals(0L, snapshot.newCardTransition.lifetimeSamples)
        assertEquals(0L, snapshot.webViewCreation.lifetimeSamples)
        assertEquals(0L, snapshot.fallbacksUsed)
        assertEquals(0L, snapshot.staleCallbacksIgnored)
        assertEquals(0L, snapshot.webViewsCreated)
        assertEquals(0L, snapshot.webViewsReleased)
    }
}
