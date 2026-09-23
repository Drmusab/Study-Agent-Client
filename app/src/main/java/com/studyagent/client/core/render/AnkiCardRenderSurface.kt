package com.studyagent.client.core.render

/**
 * GATE 08 — the platform seam between the pure renderer and whatever executes a document (STEP 11/§88).
 *
 * The controller in `core/render` owns *decisions* (which document, which generation, which callback
 * is stale, which fallback). This interface owns *execution*, and its one production implementation
 * is the Android WebView surface in `ui/components/anki/AnkiCardWebView.kt`. The split is what makes
 * the interesting renderer logic — stale-callback rejection, same-turn side changes, new-turn
 * replacement, recovery after a renderer-process failure — testable on the JVM, with no Chromium, no
 * Compose and no device (STEP 103-§107).
 *
 * Contracts an implementation must keep:
 *
 * - **One document per call, main thread only.** [presentDocument] replaces the whole page: a new
 *   document, a fresh JavaScript global scope (STEP 75/§76, INV-RENDER-25). Nothing may merge two
 *   cards into one page.
 * - **Callbacks carry the document's own request.** Every page event the implementation forwards to
 *   the controller must be stamped with the [AnkiCardDocument.request] it was loaded with — that is
 *   the entire stale-detection mechanism (STEP 18/§19, INV-RENDER-06).
 * - **Idempotent release.** [release] may be called by the owner and by a recovery path; the second
 *   call must be a no-op, and after it [isUsable] is `false` forever (STEP 14/§109, INV-RENDER-26).
 * - **No native bridge.** An implementation must never expose a JavaScript interface object to the
 *   page (STEP 09, INV-RENDER-08).
 *
 * The interface deliberately has no `evaluateJavascript`, no `reload`, no settings and no history
 * access: a surface that can only present a document and reset its scroll cannot become a general
 * browser (STEP 48) or a back door into the page.
 */
interface AnkiCardRenderSurface {

    /** False once released, or once the underlying renderer process is gone. */
    val isUsable: Boolean

    /** Replace the current page with [document] (UTF-8, [AnkiCardDocument.baseUrl] as base). */
    fun presentDocument(document: AnkiCardDocument)

    /** Scroll to the top of the document (STEP 32/§33). Never called for an unchanged request. */
    fun resetScroll()

    /** Release the underlying platform resource. Idempotent; the owner calls it (STEP 14). */
    fun release()
}
