package com.studyagent.client.core.render

/**
 * GATE 08 — the HTML document handed to the WebView (STEP 20-§25, §75-§82).
 *
 * ## What GATE 07 actually delivers
 *
 * The pinned AnkiDroid card contract (`AnkiDroidApiContract`'s GATE 07 provenance table, verified at
 * v2.24.1) returns `question`/`answer` as **rendered HTML body fragments** produced by Anki's own
 * template renderer — not complete documents, and *without* the note type's `<style>` block. So the
 * renderer must supply the smallest possible shell (STEP 22) and must not assume a shell is already
 * there (STEP 20): [AnkiCardDocumentBuilder.isDocumentShaped] detects a payload that already carries
 * `<!doctype`/`<html`/`<body` and passes it through **verbatim**, because wrapping a document in a
 * second document is how `<body>` attributes and card CSS get silently dropped.
 *
 * ## The shell, and why every line is there
 *
 * ```text
 * <!doctype html>                        standards mode: no quirks-mode layout surprises
 * <html dir="ltr|rtl|auto">              base writing direction (STEP 35/§36), never hard-coded ltr
 * <meta charset="utf-8">                 Arabic / diacritics / emoji / math symbols (STEP 79, INV-27)
 * <meta name="viewport" ...>             device-width, initial-scale=1 (STEP 23)
 * <style> …renderer base… </style>       FOUR rules, before the card, so card CSS always wins (STEP 24)
 * <body class="card" …>                  `.card` semantics preserved (STEP 25) + night_mode classes
 * …payload, byte for byte…               never escaped, never re-decoded, never rewritten (STEP 21/§80/§81)
 * ```
 *
 * The base stylesheet is deliberately tiny and deliberately **first in document order**: CSS of equal
 * specificity resolves by order, so anything a template defines — `font-size`, `color`, `alignment`,
 * table styles, positioning, its own `<style>` block inside the fragment — overrides Study-Agent
 * (STEP 24, INV-ANKI-RENDER-14). What the base does is: remove the UA margin, give the content a
 * small edge inset, stop Chromium's font-boosting from resizing text the template sized itself,
 * constrain images/video to the viewport so one huge image cannot force the whole card to scroll
 * sideways (STEP 64), and pick the color scheme for *unstyled* content. Tables are deliberately
 * **not** constrained: a wide table keeps its authored layout and the WebView scrolls it
 * (STEP 63/§117).
 *
 * ## Night mode without inversion (STEP 41/§42, INV-ANKI-RENDER-15)
 *
 * No `filter: invert(...)`, no `WebSettings.setForceDark`, no algorithmic darkening, no rewritten
 * colors — blanket inversion destroys images, charts and cards that already ship a dark design.
 * Instead: `color-scheme: dark` on `<html>` lets Chromium pick its own dark *UA defaults* for content
 * that specifies no colors, `background-color: canvas` / `color: canvasText` follow that scheme, and
 * the Anki-conventional `night_mode`/`nightMode` body classes are added so a template that has its
 * own night styling can apply it. A card that declares its own background and text color is shown
 * exactly as authored, in a dark app — fidelity first (STEP 43, INV-ANKI-RENDER-02/14).
 *
 * Both spellings of the night class are emitted because the two Anki front-ends have historically
 * differed (`night_mode` in AnkiDroid's CSS, `nightMode` in desktop Anki's reviewer) and the upstream
 * spelling could not be re-verified offline in this gate. Adding a class cannot break a template that
 * ignores it; omitting the one a template uses would. Confirming the exact class contract against a
 * device is a GATE 09 matrix item.
 *
 * ## Base URL (STEP 77/§78)
 *
 * [BASE_URL] is an app-controlled origin on the IANA-reserved `.invalid` TLD (RFC 2606): it can never
 * be registered, resolved or navigated to, so a card cannot be talked into treating a real site as
 * same-origin, and no cookie/storage of any real domain is reachable. It is **not** a disguised
 * public domain (STEP 78). Relative references (`<img src="pic.jpg">`) resolve against it and simply
 * fail in GATE 08, because media resolution, `shouldInterceptRequest` routing and the network/resource
 * policy are GATE 09 (STEP 59/§130, INV-ANKI-RENDER-32). Changing the origin later is a one-constant
 * change, owned by GATE 09.
 *
 * @param request the identity stamped into this document; the WebView client echoes it back on every
 *   callback so a late callback can be recognised as late (STEP 18/§19).
 * @param verbatim `true` when the payload was already document-shaped and was **not** wrapped.
 * @param payloadLength the card HTML length in chars — the sanctioned diagnostics metadata, so
 *   payload growth is observable without ever logging content (STEP 124).
 */
data class AnkiCardDocument(
    val request: AnkiRenderRequestId,
    val html: String,
    val baseUrl: String = AnkiCardDocument.BASE_URL,
    val mimeType: String = AnkiCardDocument.MIME_TYPE,
    val encoding: String = AnkiCardDocument.ENCODING,
    val verbatim: Boolean = false,
    val payloadLength: Int = 0,
    val bodyClasses: List<String> = emptyList(),
    val nightMode: Boolean = false,
    val direction: CardTextDirection = CardTextDirection.DEFAULT,
    val javascriptEnabled: Boolean = AnkiJavascriptPolicy.DEFAULT.allowsPageJavascript,
    /**
     * WebView `textZoom` for this document (STEP 38): scaling through the platform's text zoom,
     * never through a rewritten `font-size`, so a template's relative sizing survives.
     */
    val textZoomPercent: Int = 100,
    val tokens: List<String> = emptyList()
) {
    init {
        require(baseUrl.isNotBlank()) { "a document needs a base URL (relative references resolve against it)" }
        require(mimeType.isNotBlank())
        require(encoding.equals("UTF-8", ignoreCase = true)) {
            "card documents are always UTF-8 (STEP 79, INV-ANKI-RENDER-27)"
        }
        require(payloadLength >= 0)
        require(textZoomPercent in 10..1000) { "textZoomPercent is a WebView percentage (got $textZoomPercent)" }
    }

    companion object {
        /** Reserved, unresolvable, app-controlled origin (RFC 2606 `.invalid`) — see the class KDoc. */
        const val BASE_URL: String = "https://card.studyagent.invalid/"

        const val MIME_TYPE: String = "text/html"
        const val ENCODING: String = "UTF-8"

        /**
         * Anki's conventional body class: the note type's `.card { … }` rules target the body, so the
         * shell must keep that semantics instead of dropping card content into an arbitrary wrapper
         * (STEP 25).
         */
        const val BODY_CLASS_CARD: String = "card"

        /** AnkiDroid's night-mode body class. */
        const val BODY_CLASS_NIGHT_MODE: String = "night_mode"

        /** Desktop Anki's night-mode body class. Both are emitted; see the class KDoc. */
        const val BODY_CLASS_NIGHT_MODE_CAMEL: String = "nightMode"

        /**
         * Read-only inspection attribute so a test (or GATE 09 debugging) can confirm which side is
         * loaded without asking native code. It grants the page no capability and exposes no content
         * beyond the side name (INV-ANKI-RENDER-08).
         */
        const val SIDE_ATTRIBUTE: String = "data-studyagent-side"

        /** Diagnostics token: the payload arrived document-shaped and was passed through. */
        const val TOKEN_VERBATIM_DOCUMENT: String = "card_document_verbatim"

        /** Body edge inset, in CSS px. Overridable by any card CSS (it comes later in the document). */
        const val BODY_PADDING_PX: Int = 12
    }
}

/**
 * The document builder (STEP 20/§22). Pure string construction: no Android, no WebView, no I/O — so
 * every claim about the shell (UTF-8, side insertion, body classes, direction, night mode, JS policy,
 * verbatim passthrough, no escaping) is unit-testable on the JVM (STEP 102).
 */
object AnkiCardDocumentBuilder {

    /**
     * Wraps [payload] in the minimal shell, or returns it verbatim when it is already
     * document-shaped.
     *
     * The payload is appended **byte for byte** (STEP 21): no HTML-escaping (that would render
     * `<b>` as literal text — STEP 80), no entity decoding (double-decoding corrupts `&amp;amp;` —
     * STEP 81), no newline→`<br>` substitution (that is Anki's *note editor* behaviour, not a
     * renderer's — STEP 82), no tag stripping, no attribute rewriting, no `<script>` removal.
     * Disabling JavaScript is a WebView setting, not a document edit: an inert script stays in the
     * DOM exactly as authored (STEP 111).
     */
    fun build(
        payload: String,
        request: AnkiRenderRequestId,
        config: AnkiCardRenderConfig,
        tokens: List<String> = emptyList()
    ): AnkiCardDocument {
        val documentShaped = isDocumentShaped(payload)
        val bodyClasses = bodyClasses(config)
        val documentTokens = tokens + if (documentShaped) {
            listOf(AnkiCardDocument.TOKEN_VERBATIM_DOCUMENT)
        } else {
            emptyList()
        }
        return AnkiCardDocument(
            request = request,
            html = if (documentShaped) payload else wrap(payload, request.side, config, bodyClasses),
            verbatim = documentShaped,
            payloadLength = payload.length,
            bodyClasses = if (documentShaped) emptyList() else bodyClasses,
            nightMode = config.nightMode,
            direction = config.direction,
            javascriptEnabled = config.javascriptPolicy.allowsPageJavascript,
            textZoomPercent = config.textZoomPercent,
            tokens = documentTokens
        )
    }

    /**
     * True when [payload] already carries a document shell and must not be wrapped again.
     *
     * Conservative on purpose: only a payload whose **first** meaningful characters open a doctype,
     * `<html>` or `<body>` counts. A fragment that merely *contains* a `<body>`-looking string deeper
     * inside (an escaped example in a card about HTML, say) is still a fragment and still gets the
     * shell — which is the safe direction, since wrapping a fragment is harmless and wrapping a
     * document loses its attributes.
     */
    fun isDocumentShaped(payload: String): Boolean {
        val head = payload.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .take(DETECTION_PREFIX_CHARS)
            .lowercase()
        return DOCUMENT_PREFIXES.any { head.startsWith(it) }
    }

    /** `class="card"` plus the night-mode classes when night mode is on (STEP 25/§41). */
    fun bodyClasses(config: AnkiCardRenderConfig): List<String> = buildList {
        add(AnkiCardDocument.BODY_CLASS_CARD)
        if (config.nightMode) {
            add(AnkiCardDocument.BODY_CLASS_NIGHT_MODE)
            add(AnkiCardDocument.BODY_CLASS_NIGHT_MODE_CAMEL)
        }
    }

    /**
     * The renderer's base CSS. Four concerns, all overridable by later card CSS, no color of our own
     * beyond the scheme-following `canvas`/`canvasText` system colors (STEP 24/§40/§41).
     */
    fun baseCss(nightMode: Boolean): String = buildString(256) {
        append("html{color-scheme:").append(if (nightMode) "dark" else "light").append("}\n")
        append("html,body{margin:0;padding:0}\n")
        append("body{padding:").append(AnkiCardDocument.BODY_PADDING_PX).append("px;")
        append("-webkit-text-size-adjust:100%;")
        append("background-color:canvas;color:canvasText}\n")
        // Images and video are constrained so one oversized asset cannot blow out the layout
        // (STEP 64). Tables are deliberately absent: a wide table keeps its authored geometry and
        // the WebView scrolls it horizontally (STEP 63).
        append("img,video{max-width:100%}\n")
    }

    private fun wrap(
        payload: String,
        side: AnkiCardSide,
        config: AnkiCardRenderConfig,
        bodyClasses: List<String>
    ): String = buildString(payload.length + 384) {
        append("<!doctype html>\n")
        append("<html dir=\"").append(config.direction.htmlDirAttribute).append("\">\n")
        append("<head>\n")
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<style>\n").append(baseCss(config.nightMode)).append("</style>\n")
        append("</head>\n")
        append("<body class=\"").append(bodyClasses.joinToString(" ")).append('"')
        append(' ').append(AnkiCardDocument.SIDE_ATTRIBUTE).append("=\"").append(sideToken(side)).append("\">\n")
        append(payload)
        append("\n</body>\n</html>\n")
    }

    private fun sideToken(side: AnkiCardSide): String = side.name.lowercase()

    /** How far into the payload the shell detection looks. */
    private const val DETECTION_PREFIX_CHARS: Int = 64

    private val DOCUMENT_PREFIXES: List<String> = listOf("<!doctype html", "<!doctype", "<html", "<body")
}
