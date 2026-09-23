package com.studyagent.client.ui.components.anki

/*
 * GATE 08 — the one place in Study-Agent that touches `android.webkit` (STEP 11-§15, §132).
 *
 * WHAT THIS FILE IS
 * -----------------
 * The production `AnkiCardRenderSurface`: it creates a WebView, configures it deliberately, loads the
 * document the pure renderer built, forwards page callbacks stamped with the request identity they
 * were loaded with, and releases everything when the card surface goes away. It contains no card
 * logic: no plan, no fallback decision, no state machine, no scheduler, no rating, no TTS, no AI
 * (STEP 144-§148). All of that lives in `core/render`, which never imports `android.webkit`
 * (INV-ANKI-RENDER-07, enforced by `AnkiRendererIsolationTest`).
 *
 * LIFECYCLE (STEP 12-§15, §108/§109)
 * ----------------------------------
 * - Created once per card surface inside `remember(context, surfaceToken)` — never per recomposition
 *   (STEP 12/§13, INV-RENDER-23). Recomposition re-runs `update`, which only touches visibility and
 *   background.
 * - Reused across cards: one WebView, one full document per card (STEP 76). A new document means a new
 *   JavaScript global scope, so card A's globals cannot become card B's (STEP 75, INV-RENDER-25).
 *   Exactly ONE live WebView: no pool (STEP 127, INV-RENDER-22).
 * - Released explicitly by the composition that created it (`DisposableEffect.onDispose`), never by the
 *   controller: ownership stays where the instance was allocated (STEP 14/§15). Release is idempotent
 *   and survives a renderer-process death (the dead instance is still destroyed, never leaked).
 * - Recreated only on demand: a Chromium renderer-process death bumps `surfaceToken`, the old instance
 *   is released, a new one is created, and the controller re-presents the SAME turn, card and side
 *   (STEP 71-§73). The Activity declares
 *   `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`, so rotation does not recreate
 *   the composition and therefore does not recreate the WebView (STEP 108).
 * - No Activity retention: the WebView is built on a `MutableContextWrapper` whose base context is
 *   swapped to the application context before `destroy()`, so a released WebView cannot keep an
 *   Activity alive (STEP 15, INV-RENDER-26). No application-scoped singleton ever holds one.
 *
 * SETTINGS (STEP 132-§136) — every line justified, nothing copied from a tutorial
 * -------------------------------------------------------------------------------
 * javaScriptEnabled                per document, from the explicit policy (STEP 08) — never a default
 * domStorageEnabled/databaseEnabled  false — no demonstrated card-template need (STEP 134), and a card
 *                                    has no business persisting state inside our WebView
 * allowFileAccess/allowContentAccess false — card HTML must not read local files or providers (STEP 09)
 * allowUniversalAccessFromFileURLs   left at the platform default (false) and asserted false by the
 * allowFileAccessFromFileURLs        isolation test: never relaxed "to make a card work" (STEP 133)
 * blockNetworkImageLoads             true — GATE 08 has no media resolver so a remote image cannot work
 *                                    anyway, and a card must not silently beacon to a third party
 *                                    (STEP 59/§130, INV-RENDER-32)
 * mixedContentMode                   untouched: the platform default (NEVER_ALLOW) is the strict one
 * mediaPlaybackRequiresUserGesture   true — no autoplay; audio/video policy is GATE 09 (STEP 60/§61)
 * setSupportZoom/builtInZoomControls false — scaling is `textZoom` (STEP 38), not pinch-zoom of a card
 * textZoom                           per document (STEP 38/§39): accessibility scaling without
 *                                    rewriting a single `font-size` in the card CSS
 * useWideViewPort/loadWithOverviewMode false — the layout viewport equals the view width, i.e.
 *                                    `width=device-width` behaviour (STEP 23)
 * defaultTextEncodingName            UTF-8 — Arabic, diacritics, emoji, math symbols (STEP 79, INV-27)
 * javaScriptCanOpenWindowsAutomatically / setSupportMultipleWindows  false — not a browser (STEP 48/§56)
 * userAgentString                    untouched — no AnkiDroid/desktop-Anki spoofing without evidence
 *                                    (STEP 136)
 * cacheMode                          LOAD_DEFAULT — asset caching only, never collection truth (STEP 74)
 * force-dark / algorithmic darkening NOT USED — blanket inversion destroys images and dark-authored
 *                                    cards (STEP 42, INV-ANKI-RENDER-15); night mode is `color-scheme`
 * setWebContentsDebuggingEnabled     BuildConfig.DEBUG only (STEP 135)
 * addJavascriptInterface             NEVER CALLED — no native bridge for arbitrary card HTML
 *                                    (STEP 09, INV-ANKI-RENDER-08)
 */

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.studyagent.client.BuildConfig
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.render.AnkiCardDocument
import com.studyagent.client.core.render.AnkiCardRenderController
import com.studyagent.client.core.render.AnkiCardRenderSurface
import com.studyagent.client.core.render.AnkiRenderEvent
import com.studyagent.client.core.render.AnkiRenderFailure
import com.studyagent.client.core.render.AnkiRenderPerformance
import com.studyagent.client.core.render.AnkiRenderRequestId

/** Log tag for this file. Every message is content-free: identifiers, categories and counts. */
internal const val ANKI_WEBVIEW_TAG: String = "AnkiCardWebView"

/**
 * Creates and configures one card WebView, or returns `null` when the platform cannot provide one.
 *
 * A device without a usable WebView provider is a real state, not a crash: the caller reports
 * [AnkiRenderFailure.WebViewUnavailable] and the card is still readable through the text fallback
 * (STEP 68/§121). The context is wrapped so [AnkiCardWebViewHost.release] can drop the `Activity`
 * reference before destroying the view (STEP 15).
 */
internal fun createAnkiCardWebView(context: Context): WebView? = try {
    WebView(MutableContextWrapper(context)).also { configureAnkiCardWebView(it) }
} catch (failure: RuntimeException) {
    // A WebView provider that cannot be initialised throws RuntimeException subclasses
    // (AndroidRuntimeException / MissingWebViewProviderException). The message is device state, not
    // card content, so it may be logged — but only the category reaches diagnostics.
    AppLogger.e(
        ANKI_WEBVIEW_TAG,
        "ANKI_RENDER_SURFACE_UNAVAILABLE category=${AnkiRenderFailure.WebViewUnavailable.CATEGORY_CREATION_FAILED}",
        failure
    )
    null
}

/** The deliberate settings block, in one function so a review can read every choice in one place. */
private fun configureAnkiCardWebView(webView: WebView) {
    // STEP 135 — debug-only remote inspection; never enabled in a release build.
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

    webView.settings.apply {
        // Set per document from the explicit policy; the default here is the safe one (STEP 08).
        javaScriptEnabled = false
        domStorageEnabled = false
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkImageLoads = true
        cacheMode = WebSettings.LOAD_DEFAULT
        defaultTextEncodingName = AnkiCardDocument.ENCODING
        mediaPlaybackRequiresUserGesture = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        useWideViewPort = false
        loadWithOverviewMode = false
        // Deliberately untouched: mixedContentMode (strict default), allowUniversalAccessFromFileURLs
        // and allowFileAccessFromFileURLs (false by default — STEP 133), userAgentString (STEP 136),
        // and every force-dark / algorithmic-darkening API (INV-ANKI-RENDER-15).
    }

    // STEP 138 — the card may be focused (a hardware keyboard or an interactive widget needs it) but
    // does not grab focus on touch, so native Study-Agent controls keep their focus behaviour.
    webView.isFocusable = true
    webView.isFocusableInTouchMode = false
    // STEP 140 — the WebView keeps its own touch handling; nothing here intercepts gestures.
    webView.overScrollMode = View.OVER_SCROLL_NEVER
    // STEP 63 — a wide table scrolls horizontally inside the WebView instead of breaking the layout.
    webView.isHorizontalScrollBarEnabled = true
    // Transparent until the document paints: no white flash in a dark app, and the page's own
    // background (STEP 40) is what the user sees.
    webView.setBackgroundColor(Color.TRANSPARENT)
}

/**
 * The [AnkiCardRenderSurface] implementation: one WebView, one document at a time, every callback
 * stamped with the document's own request identity (STEP 18/§19).
 *
 * All callback paths begin with [currentRequest], which returns `null` once the host is released — so
 * a teardown navigation or a late Chromium message can never reach the controller as if it belonged to
 * a live card (STEP 109, INV-ANKI-RENDER-06/26).
 *
 * @param onSurfaceInvalidated called when the renderer process died and this instance is unusable; the
 *   Compose layer recreates the surface (STEP 72).
 */
internal class AnkiCardWebViewHost(
    private val webView: WebView,
    private val controller: AnkiCardRenderController,
    private val performance: AnkiRenderPerformance? = null,
    private val onSurfaceInvalidated: () -> Unit = {}
) : AnkiCardRenderSurface {

    /** No more documents, no more callbacks. Set on release *and* on renderer-process death. */
    private var released = false

    /** The platform instance has been destroyed. Distinct from [released]: a dead renderer is
     *  released first and destroyed later, and must not be destroyed twice. */
    private var destroyed = false

    /** The instance is unusable because Chromium's renderer died: teardown must be defensive. */
    private var rendererGone = false

    private var documentRequest: AnkiRenderRequestId? = null

    override val isUsable: Boolean get() = !released

    init {
        webView.webViewClient = CardWebViewClient()
        webView.webChromeClient = CardWebChromeClient()
    }

    override fun presentDocument(document: AnkiCardDocument) {
        if (released) return
        documentRequest = document.request
        applyDocumentSettings(document)
        // STEP 75-§77 — a full document load per presentation: the JavaScript global scope is
        // recreated, so nothing leaks between cards, and the base URL is the renderer's own reserved
        // origin (never a real public domain, STEP 78). UTF-8, never escaped twice (STEP 79-§81).
        webView.loadDataWithBaseURL(
            document.baseUrl,
            document.html,
            document.mimeType,
            document.encoding,
            null
        )
    }

    override fun resetScroll() {
        if (released) return
        webView.scrollTo(0, 0)
    }

    override fun release() {
        if (destroyed) return
        destroyed = true
        released = true
        documentRequest = null
        controller.detachSurface(this)
        performance?.recordWebViewRelease()
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            if (!rendererGone) {
                // STEP 48/§109 — stop work and clear the single-entry history: the renderer is not a
                // browser and leaves no navigable stack behind. Skipped for a dead renderer, whose
                // state cannot be trusted.
                webView.stopLoading()
                webView.clearHistory()
                webView.removeAllViews()
            }
            // STEP 15 — drop the Activity before destroying, so a destroyed WebView cannot retain it.
            val wrapper = webView.context as? MutableContextWrapper
            val application = webView.context.applicationContext
            if (wrapper != null && application != null) wrapper.baseContext = application
            webView.destroy()
        } catch (failure: RuntimeException) {
            // Teardown must never crash a study session (STEP 71). No card content in the message.
            AppLogger.w(ANKI_WEBVIEW_TAG, "ANKI_RENDER_SURFACE_RELEASE_FAILED category=teardown", failure)
        }
    }

    /**
     * Per-document WebView settings the policy owns: JavaScript on/off and text zoom (STEP 08/§38).
     * Applied immediately before the load, so a document can never execute under the previous
     * document's policy.
     */
    private fun applyDocumentSettings(document: AnkiCardDocument) {
        webView.settings.javaScriptEnabled = document.javascriptEnabled
        webView.settings.textZoom = document.textZoomPercent
    }

    private fun currentRequest(): AnkiRenderRequestId? = if (released) null else documentRequest

    // ------------------------------------------------------------------ WebViewClient

    private inner class CardWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            val request = currentRequest() ?: return
            controller.onPageStarted(request)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val request = currentRequest() ?: return
            // STEP 48 — one document per card; history never accumulates across cards.
            view.clearHistory()
            controller.onPageFinished(request)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            // STEP 59 — only the MAIN FRAME fails a card. An unresolved image or a blocked network
            // asset degrades visually (GATE 09 resolves media); it must not blank the card.
            if (!request.isForMainFrame) return
            val current = currentRequest() ?: return
            controller.onLoadFailure(current, error.errorCode)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never proceed(): a card cannot talk Study-Agent into accepting a bad certificate.
            handler.cancel()
            val current = currentRequest() ?: return
            controller.onSslErrorBlocked(current, sslCategoryOf(error.primaryError))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val current = currentRequest() ?: return true
            // STEP 44-§47 — the pure policy decides, the controller reports upward, and the card never
            // navigates the reviewer away from itself.
            return controller.requestNavigation(current, request.url?.toString())
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Returning true is what keeps the app alive: an unhandled renderer death terminates the
            // process (STEP 71).
            val current = currentRequest()
            rendererGone = true
            released = true
            documentRequest = null
            controller.detachSurface(this@AnkiCardWebViewHost)
            current?.let { controller.onRendererProcessGone(it, detail.didCrash()) }
            // The instance is invalid from here on. Compose creates a replacement; the controller
            // re-presents the same turn/card/side on it (STEP 72/§73). Release still happens through
            // the composition's onDispose, so the dead instance is destroyed rather than leaked.
            onSurfaceInvalidated()
            return true
        }
    }

    // ------------------------------------------------------------------ WebChromeClient

    /**
     * The minimum chrome behaviour a card document needs — and nothing more (STEP 56/§57).
     *
     * No camera, no microphone, no geolocation, no file picker, no notifications, no window opening:
     * every permission prompt is denied and every modal dialog is suppressed, because a card script
     * must not reach a device capability or block the review UI (INV-ANKI-RENDER-31, STEP 58).
     */
    private inner class CardWebChromeClient : WebChromeClient() {

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            val level = consoleMessage?.messageLevel()?.name ?: LEVEL_UNKNOWN
            if (BuildConfig.DEBUG) {
                // STEP 55 — debug-friendly, bounded, sanitized. A production log never receives card
                // console output: it is deck content (STEP 54/§124).
                val message = consoleMessage?.message().orEmpty().take(MAX_CONSOLE_MESSAGE_CHARS)
                AppLogger.d(
                    ANKI_WEBVIEW_TAG,
                    "ANKI_RENDER_CONSOLE level=$level line=${consoleMessage?.lineNumber() ?: -1} " +
                        "message=${AppLogger.sanitizeText(message)}"
                )
            }
            val request = currentRequest() ?: return true
            // A card JS exception is reported, counted and survived — never allowed to fail the render
            // or crash the app (STEP 54): the HTML that did render is still on screen.
            if (level == LEVEL_ERROR || level == LEVEL_WARNING) {
                controller.onJavascriptConsoleError(request, level)
            }
            return true
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean = suppressDialog(result, AnkiRenderEvent.JavascriptDialogSuppressed.KIND_ALERT)

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean = suppressDialog(result, AnkiRenderEvent.JavascriptDialogSuppressed.KIND_CONFIRM)

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean = suppressDialog(result, AnkiRenderEvent.JavascriptDialogSuppressed.KIND_PROMPT)

        override fun onJsBeforeUnload(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean = suppressDialog(result, AnkiRenderEvent.JavascriptDialogSuppressed.KIND_BEFORE_UNLOAD)

        override fun onPermissionRequest(request: PermissionRequest?) {
            // STEP 57 / INV-ANKI-RENDER-31 — denied, always, and counted. Study-Agent's microphone
            // belongs to the native voice subsystem, never to a card script (STEP 58).
            request?.deny()
            currentRequest()?.let { controller.onWebPermissionDenied(it, request?.resources?.size ?: 0) }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            // allow = false, retain = false: nothing is granted and nothing is remembered.
            callback?.invoke(origin, false, false)
            currentRequest()?.let { controller.onWebPermissionDenied(it, 1) }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            // A card never gets a file picker (STEP 56). The callback must still be answered, or the
            // platform leaves the request pending forever.
            filePathCallback?.onReceiveValue(null)
            currentRequest()?.let { controller.onWebPermissionDenied(it, 0) }
            return true
        }

        /** No poster is fetched for a `<video>` element: video is GATE 09 (STEP 61). */
        override fun getDefaultVideoPoster(): Bitmap? = null

        private fun suppressDialog(result: JsResult?, kind: String): Boolean {
            result?.cancel()
            currentRequest()?.let { controller.onJavascriptDialogSuppressed(it, kind) }
            return true
        }
    }

    private companion object {
        const val LEVEL_UNKNOWN = "UNKNOWN"
        const val LEVEL_ERROR = "ERROR"
        const val LEVEL_WARNING = "WARNING"

        /** Console text is bounded even in debug: a card cannot flood the log buffer. */
        const val MAX_CONSOLE_MESSAGE_CHARS = 200
    }
}

/** `SslError` primary code → stable token. No certificate detail ever reaches a log. */
internal fun sslCategoryOf(primaryError: Int): String = when (primaryError) {
    SslError.SSL_EXPIRED -> "expired"
    SslError.SSL_IDMISMATCH -> "id_mismatch"
    SslError.SSL_NOTYETVALID -> "not_yet_valid"
    SslError.SSL_UNTRUSTED -> "untrusted"
    SslError.SSL_DATE_INVALID -> "date_invalid"
    SslError.SSL_INVALID -> "invalid"
    else -> "unmapped"
}

/**
 * The Compose-side WebView surface (STEP 12/§13).
 *
 * Owns instance lifecycle only: create once, reuse across cards, recreate on renderer-process death,
 * release on disposal. *Visibility*, not existence, follows the presentation — so switching a card to
 * the Compose text fallback never destroys the WebView and switching back never rebuilds it
 * (INV-RENDER-22/23).
 *
 * @param visible false while a Compose text presentation is on screen; the WebView stays alive and
 *   simply stops being drawn.
 * @param createWebView the test seam: instrumented tests inject a counting or recording factory
 *   (STEP 101/§110/§128) without production code growing a test-only branch.
 */
@Composable
internal fun AnkiCardWebViewSurface(
    controller: AnkiCardRenderController,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    backgroundColor: Int = Color.TRANSPARENT,
    performance: AnkiRenderPerformance? = null,
    createWebView: (Context) -> WebView? = { createAnkiCardWebView(it) }
) {
    val context = LocalContext.current
    // Bumped by onRenderProcessGone: the only thing that recreates a WebView (STEP 72).
    var surfaceToken by remember { mutableIntStateOf(0) }

    val webView = remember(context, surfaceToken) {
        val startedAt = SystemClock.elapsedRealtime()
        createWebView(context)?.also { created ->
            performance?.recordWebViewCreation(SystemClock.elapsedRealtime() - startedAt)
        }
    }

    DisposableEffect(controller, webView, surfaceToken) {
        if (webView == null) {
            // The platform could not provide a WebView at all. Reported before the controller submits,
            // so the fallback carries the precise category instead of a generic "no surface".
            controller.onSurfaceCreationFailed()
        }
        val host = webView?.let { view ->
            AnkiCardWebViewHost(
                webView = view,
                controller = controller,
                performance = performance,
                onSurfaceInvalidated = { surfaceToken++ }
            )
        }
        // Attach after construction: attaching re-presents the active document onto a surface that has
        // none, which is also what upgrades a text fallback back to ORIGINAL once a WebView exists.
        host?.let { controller.attachSurface(it) }
        onDispose {
            // STEP 14/§109 — the creator releases, exactly once, and the controller is told first so a
            // teardown callback cannot be mistaken for a live one.
            host?.release()
        }
    }

    if (webView != null) {
        // Keyed on the surface token: after a renderer-process death the remembered WebView is a
        // *different instance*, and an unkeyed AndroidView would keep showing the destroyed one because
        // its factory never re-runs (STEP 72/§73).
        key(surfaceToken) {
            AndroidView(
                factory = { webView },
                modifier = modifier,
                update = { view ->
                    view.setBackgroundColor(backgroundColor)
                    view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                }
            )
        }
    }
}
