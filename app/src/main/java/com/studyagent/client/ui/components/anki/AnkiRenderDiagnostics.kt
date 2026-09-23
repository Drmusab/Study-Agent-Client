package com.studyagent.client.ui.components.anki

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.render.AnkiRenderEvent

/**
 * GATE 08 — the renderer's diagnostics adapter (PART IV, STEP 124/§55).
 *
 * One job: write a content-free [AnkiRenderEvent] through the app's existing bounded logger, at a level
 * that matches what the event means, using the same `EVENT_NAME key=value …` shape the Anki data layer
 * already uses. The renderer itself never imports `AppLogger`: the pure layer produces events, this
 * adapter decides where they go — which keeps `core/render` JVM-only and lets a test collect events
 * instead of parsing log text.
 *
 * Privacy is inherited, not re-implemented: [AnkiRenderEvent.metadata] is identifiers, counts, lengths,
 * tokens and durations by construction, and [AppLogger] sanitizes and length-caps every row again. No
 * event carries card HTML, a question, an answer, a card script or a link URL — so there is nothing here
 * to redact (STEP 54/§124, INV-ANKI-CARD-31 by analogy).
 */
object AnkiRenderDiagnostics {

    /** Log tag for renderer rows, distinct from the WebView layer's tag. */
    const val TAG: String = "AnkiRender"

    fun log(event: AnkiRenderEvent) {
        val line = event.logLine()
        when (event) {
            // Something the user may have seen: a failure, a crash recovery, a refused navigation or a
            // denied capability. WARN so it survives a filtered log view.
            is AnkiRenderEvent.Failed,
            is AnkiRenderEvent.RendererProcessGone,
            is AnkiRenderEvent.SurfaceUnavailable,
            is AnkiRenderEvent.ExternalLinkBlocked,
            is AnkiRenderEvent.SslErrorBlocked,
            is AnkiRenderEvent.WebPermissionDenied -> AppLogger.w(TAG, line)

            // Normal race evidence: a late callback that was correctly dropped. Routine, and exactly
            // what a "card never became ready" investigation needs — debug level so it cannot flood a
            // release log buffer.
            is AnkiRenderEvent.StaleCallbackIgnored,
            is AnkiRenderEvent.JavascriptConsoleError,
            is AnkiRenderEvent.JavascriptDialogSuppressed -> AppLogger.d(TAG, line)

            // Lifecycle: what was presented, how long it took, what was released.
            else -> AppLogger.i(TAG, line)
        }
    }

    /**
     * A compact, content-free summary line for the Diagnostics screen (STEP 125): the performance
     * snapshot plus the renderer's own state token.
     */
    fun summaryLine(
        stateToken: String,
        performance: com.studyagent.client.core.render.AnkiRenderPerformanceSnapshot
    ): String = "ANKI_RENDER_SUMMARY state=$stateToken ${performance.describe()}"
}
