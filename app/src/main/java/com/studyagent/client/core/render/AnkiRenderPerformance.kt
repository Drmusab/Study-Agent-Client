package com.studyagent.client.core.render

import com.studyagent.client.core.diagnostics.LatencyStats
import com.studyagent.client.core.diagnostics.LatencyWindow
import java.util.concurrent.atomic.AtomicLong

/**
 * GATE 08 — renderer performance measurement (STEP 125/§126/§128).
 *
 * Four latency families and a handful of counters, all content-free, all reusing the app's existing
 * bounded [LatencyWindow] so the renderer does not grow its own unbounded metric storage:
 *
 * | Family | What it answers |
 * |---|---|
 * | [webViewCreation] | how expensive is a WebView instance on this device — the number that decides the reuse strategy (STEP 126/§127) |
 * | [documentLoad] | document submit → `onPageFinished`: the per-card render latency |
 * | [sideTransition] | question → answer of the *same* turn (STEP 30) |
 * | [newCardTransition] | turn A → turn B: submit → ready for the new turn (STEP 33) |
 *
 * A family that was never measured reports `-` ([LatencyStats.format]) and never `0ms`: an unmeasured
 * renderer must not look instant (the existing `DiagnosticsFormatting.NOT_MEASURED` rule).
 *
 * Nothing here is a claim about a device. The 100-card endurance run, the reuse-vs-recreate
 * comparison and the memory behaviour are measured from these windows on real hardware and reported
 * in `docs/GATE_08_ANKI_CARD_RENDERING.md` — with actual numbers, or with an explicit NOT RUN.
 */
class AnkiRenderPerformance(
    windowSize: Int = LatencyWindow.DEFAULT_WINDOW_SIZE
) {

    val webViewCreation: LatencyWindow = LatencyWindow(NAME_WEBVIEW_CREATION, windowSize)
    val documentLoad: LatencyWindow = LatencyWindow(NAME_DOCUMENT_LOAD, windowSize)
    val sideTransition: LatencyWindow = LatencyWindow(NAME_SIDE_TRANSITION, windowSize)
    val newCardTransition: LatencyWindow = LatencyWindow(NAME_NEW_CARD_TRANSITION, windowSize)

    private val documentsLoaded = AtomicLong(0L)
    private val webViewsCreated = AtomicLong(0L)
    private val webViewsReleased = AtomicLong(0L)
    private val fallbacksUsed = AtomicLong(0L)
    private val rendererProcessFailures = AtomicLong(0L)
    private val staleCallbacksIgnored = AtomicLong(0L)
    private val externalLinksBlocked = AtomicLong(0L)
    private val externalLinksMediated = AtomicLong(0L)
    private val webPermissionsDenied = AtomicLong(0L)
    private val javascriptErrors = AtomicLong(0L)

    /** Lifetime counters, exposed so an event can carry a count without building a snapshot. */
    val javascriptErrorCount: Long get() = javascriptErrors.get()

    val documentsLoadedCount: Long get() = documentsLoaded.get()

    val webViewsCreatedCount: Long get() = webViewsCreated.get()

    fun recordWebViewCreation(millis: Long) {
        webViewsCreated.incrementAndGet()
        webViewCreation.record(millis)
    }

    fun recordWebViewRelease() {
        webViewsReleased.incrementAndGet()
    }

    fun recordDocumentLoad(millis: Long) {
        documentsLoaded.incrementAndGet()
        documentLoad.record(millis)
    }

    fun recordSideTransition(millis: Long) {
        sideTransition.record(millis)
    }

    fun recordNewCardTransition(millis: Long) {
        newCardTransition.record(millis)
    }

    fun recordFallbackUsed() {
        fallbacksUsed.incrementAndGet()
    }

    fun recordRendererProcessFailure() {
        rendererProcessFailures.incrementAndGet()
    }

    fun recordStaleCallbackIgnored() {
        staleCallbacksIgnored.incrementAndGet()
    }

    fun recordExternalLinkBlocked() {
        externalLinksBlocked.incrementAndGet()
    }

    fun recordExternalLinkMediated() {
        externalLinksMediated.incrementAndGet()
    }

    fun recordWebPermissionDenied() {
        webPermissionsDenied.incrementAndGet()
    }

    fun recordJavascriptError() {
        javascriptErrors.incrementAndGet()
    }

    /**
     * True while the number of live WebView instances can still be considered bounded
     * (INV-ANKI-RENDER-22): every creation is matched by a release, or at most one is outstanding —
     * the single active reviewer surface (STEP 127).
     */
    val webViewCountBounded: Boolean
        get() = webViewsCreated.get() - webViewsReleased.get() <= MAX_LIVE_WEBVIEWS

    /** The number of WebViews created but not yet released. Never negative. */
    val outstandingWebViews: Long
        get() = (webViewsCreated.get() - webViewsReleased.get()).coerceAtLeast(0L)

    fun snapshot(): AnkiRenderPerformanceSnapshot = AnkiRenderPerformanceSnapshot(
        webViewCreation = webViewCreation.stats(),
        documentLoad = documentLoad.stats(),
        sideTransition = sideTransition.stats(),
        newCardTransition = newCardTransition.stats(),
        documentsLoaded = documentsLoaded.get(),
        webViewsCreated = webViewsCreated.get(),
        webViewsReleased = webViewsReleased.get(),
        fallbacksUsed = fallbacksUsed.get(),
        rendererProcessFailures = rendererProcessFailures.get(),
        staleCallbacksIgnored = staleCallbacksIgnored.get(),
        externalLinksBlocked = externalLinksBlocked.get(),
        externalLinksMediated = externalLinksMediated.get(),
        webPermissionsDenied = webPermissionsDenied.get(),
        javascriptErrors = javascriptErrors.get()
    )

    fun reset() {
        webViewCreation.reset()
        documentLoad.reset()
        sideTransition.reset()
        newCardTransition.reset()
        documentsLoaded.set(0L)
        webViewsCreated.set(0L)
        webViewsReleased.set(0L)
        fallbacksUsed.set(0L)
        rendererProcessFailures.set(0L)
        staleCallbacksIgnored.set(0L)
        externalLinksBlocked.set(0L)
        externalLinksMediated.set(0L)
        webPermissionsDenied.set(0L)
        javascriptErrors.set(0L)
    }

    companion object {
        const val NAME_WEBVIEW_CREATION: String = "anki_render_webview_creation"
        const val NAME_DOCUMENT_LOAD: String = "anki_render_document_load"
        const val NAME_SIDE_TRANSITION: String = "anki_render_side_transition"
        const val NAME_NEW_CARD_TRANSITION: String = "anki_render_new_card_transition"

        /**
         * One active reviewer WebView (STEP 127). A recreation window may briefly hold the dying
         * instance and its replacement, so the bound is 1 outstanding, never a pool.
         */
        const val MAX_LIVE_WEBVIEWS: Long = 1L
    }
}

/** An immutable, content-free reading of [AnkiRenderPerformance] for diagnostics/UI. */
data class AnkiRenderPerformanceSnapshot(
    val webViewCreation: LatencyStats,
    val documentLoad: LatencyStats,
    val sideTransition: LatencyStats,
    val newCardTransition: LatencyStats,
    val documentsLoaded: Long,
    val webViewsCreated: Long,
    val webViewsReleased: Long,
    val fallbacksUsed: Long,
    val rendererProcessFailures: Long,
    val staleCallbacksIgnored: Long,
    val externalLinksBlocked: Long,
    val externalLinksMediated: Long,
    val webPermissionsDenied: Long,
    val javascriptErrors: Long
) {
    /** WebViews created but not released at snapshot time. */
    val outstandingWebViews: Long get() = (webViewsCreated - webViewsReleased).coerceAtLeast(0L)

    /** INV-ANKI-RENDER-22 as a boolean a test can assert. */
    val webViewCountBounded: Boolean get() = outstandingWebViews <= AnkiRenderPerformance.MAX_LIVE_WEBVIEWS

    /** Compact, honest, content-free summary — one diagnostics row. */
    fun describe(): String =
        "webview_create=${webViewCreation.format()} doc_load=${documentLoad.format()} " +
            "side=${sideTransition.format()} new_card=${newCardTransition.format()} " +
            "docs=$documentsLoaded webviews=$webViewsCreated/$webViewsReleased " +
            "fallbacks=$fallbacksUsed process_gone=$rendererProcessFailures " +
            "stale=$staleCallbacksIgnored links_blocked=$externalLinksBlocked " +
            "links_opened=$externalLinksMediated web_perms_denied=$webPermissionsDenied " +
            "js_errors=$javascriptErrors"
}
