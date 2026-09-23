# GATE 08 — Original Anki Card Rendering Engine

Input contract: **GATE 07's `AnkiRenderedCard`** (`docs/GATE_07_ANKI_CARD_EXTRACTION.md` §2/§3) —
rendered HTML **fragments**, three separated content channels, honest nullability (`null` = the
backend could not supply it, `""` = legitimately empty), and an answer side that **already contains
the question** through `{{FrontSide}}` + `<hr id=answer>`.

Output: a WebView-based rendering subsystem that presents Anki's own HTML/CSS/JS with fidelity
first, degrades to Study-Agent's own Compose text surface when it cannot, and knows the difference.

---

## 1. Mission and scope

Turn a normalized card into pixels, twice per review turn, without becoming a browser, a scheduler,
a template engine or a media pipeline.

**In scope:** the document shell and its fidelity rules; the WebView configuration and lifecycle;
side switching inside one `ReviewTurnId`; an explicit JavaScript policy with **no native bridge**;
link mediation and scheme blocking; RTL/Arabic, tables, wide content, Unicode; a typed render state
with stale-callback rejection; the fallback order ORIGINAL → CLEAN text → typed failure; render
performance measurement; content-free diagnostics; a Compose entry point; a 28-card compatibility
deck; the JVM and instrumented suites; the isolation scan.

**Out of scope (by decree):** media resolution (`[sound:…]`, images, MathJax, custom fonts) →
**GATE 09**; network/resource policy beyond "blocked" → GATE 09; wiring `AnkiRenderedCard` into the
live review flow (`StudySessionMachine`) → **GATE 10**; rating commit, bury, suspend → **GATE 11**;
TTS/STT/AI — never, from this subsystem.

---

## 2. Architecture

```text
                    AnkiRenderedCard  (GATE 07: normalized, backend-neutral, read-only)
                             │
                             ▼
                     AnkiCardRenderer            Compose entry point (ui/components/anki)
                             │  card, turnId, side, config  →  events, mediated links
             ┌───────────────┴─────────────────┐
             ▼                                 ▼
   AnkiCardRenderController          AnkiCardRenderSurface  ← the seam (3 methods, no Android type)
   (core/render, JVM-pure)                     │
   planner → document → state                  ▼
             │                        AnkiCardWebViewHost  → android.webkit.WebView
             │                                 │
             └──── AnkiRenderState ────────────┴──→ CleanAnkiCardView (Compose text: CLEAN + fallback)
```

Two packages, one rule: **`core/render` is JVM-pure** (no `android.*`, no `androidx.*`, no calendar),
so every decision in this gate is unit-testable without a device; **`ui/components/anki` is the only
place in the app that touches `android.webkit`**, and it contains no card logic.

| Layer | File | Lines | Responsibility |
|---|---|---|---|
| core | `AnkiCardSide.kt` | 28 | the two visual states of one turn; always explicit |
| core | `AnkiCardRenderMode.kt` | 89 | ORIGINAL / CLEAN / VOICE_FOCUS / ADAPTIVE + surface-kind resolution + `AnkiRenderSurfaceKind` |
| core | `AnkiJavascriptPolicy.kt` | 44 | DISABLED / CARD_TEMPLATE_ONLY — there is no bridge level |
| core | `CardTextDirection.kt` | 41 | LTR / RTL / AUTO → the `dir` attribute; AUTO is the default |
| core | `AnkiCardRenderConfig.kt` | 92 | mode, night mode, text scale → `textZoom`, JS policy, direction |
| core | `AnkiRenderRequestId.kt` | 63 | turn + card + side + **generation**: the whole stale-detection mechanism |
| core | `AnkiRenderState.kt` | 248 | Idle / Loading / Ready / Failed, `AnkiRenderPresentation`, `AnkiRenderFailure` taxonomy |
| core | `AnkiCardRenderPlan.kt` | 191 | `(card, side, mode) → Original \| CleanText \| Unavailable` |
| core | `AnkiCardDocument.kt` | 245 | the document shell, base CSS, verbatim passthrough, `AnkiCardDocumentBuilder` |
| core | `AnkiCardLinkPolicy.kt` | 178 | total URL classification: in-page / external / blocked |
| core | `AnkiRenderEvent.kt` | 372 | 14 content-free event types + metadata vocabulary |
| core | `AnkiRenderPerformance.kt` | 196 | 4 latency families, counters, bounded WebView accounting |
| core | `AnkiCardRenderSurface.kt` | 43 | the seam: `presentDocument`, `resetScroll`, `release`, `isUsable` |
| core | `AnkiCardRenderController.kt` | 578 | the decision core: identity, state, fallbacks, recovery |
| ui | `AnkiCardWebView.kt` | 523 | WebView creation/configuration, `AnkiCardWebViewHost`, clients, Compose surface |
| ui | `CleanAnkiCardView.kt` | 147 | Compose text surface (CLEAN + fallback) and the placeholder |
| ui | `AnkiExternalLinkHandler.kt` | 121 | http(s)-only external open; refuses everything else; never throws |
| ui | `AnkiRenderDiagnostics.kt` | 57 | event → `AppLogger` adapter (WARN/DEBUG/INFO by meaning) |
| ui | `AnkiCardRenderer.kt` | 305 | the public Composable: layout, notice, placeholder, effects |
| debug | `AnkiCardRenderFixtures.kt` | 693 | the 28-card compatibility deck (debug source set only) |
| debug | `AnkiCardRenderPreviews.kt` | 133 | controlled previews over the deck |

**2 408 lines of pure core, 1 153 lines of Android/Compose, 826 lines of debug-only fixtures.**

---

## 3. The document contract (`AnkiCardDocument`)

One full HTML document per presentation, built by pure string construction:

```html
<!doctype html>
<html dir="auto">                                  ← LTR | RTL | AUTO, never hard-coded ltr
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>                                            ← the renderer's base CSS, FIRST
html{color-scheme:dark}
html,body{margin:0;padding:0}
body{padding:12px;-webkit-text-size-adjust:100%;background-color:canvas;color:canvasText}
img,video{max-width:100%}
</style>
</head>
<body class="card night_mode nightMode" data-studyagent-side="answer">
…the card's payload, byte for byte…                 ← fragment inserted verbatim, AFTER the base CSS
</body>
</html>
```

| Rule | Implementation | Why |
|---|---|---|
| **UTF-8** | `<meta charset="utf-8">` + `encoding = "UTF-8"` on `loadDataWithBaseURL`; `AnkiCardDocument.init` *requires* UTF-8 | Arabic, diacritics, emoji, math symbols (INV-27) |
| **No escaping** | payload appended byte for byte | escaping would render `<b>` as literal text (STEP 80) |
| **No entity decoding** | nothing parses the payload | double-decoding corrupts `&amp;amp;` (STEP 81) |
| **No newline→`<br>`** | nothing rewrites whitespace | that is the *note editor's* behaviour, not a renderer's (STEP 82) |
| **No script stripping** | `<script>` stays in the DOM | disabling JS is a WebView setting; an inert script keeps `<noscript>` semantics honest (STEP 111) |
| **`.card` preserved** | `<body class="card …">` | note-type CSS targets `.card`; dropping it silently restyles every deck (STEP 25) |
| **Base CSS first** | `<style>` in `<head>`, payload in `<body>` | document order makes the card's own rules win (INV-14) |
| **Base CSS minimal** | margin reset, body padding, `text-size-adjust`, scheme-following `canvas`/`canvasText`, `img,video{max-width:100%}` — **no table rules**, no font, no color of our own | a wide table keeps its authored geometry and scrolls (STEP 63); a template's typography survives (STEP 24) |
| **Night mode** | `color-scheme:dark` + `night_mode` + `nightMode` body classes. **No `setForceDark`, no algorithmic darkening, no `filter:invert()`** | blanket inversion destroys images and fights dark-authored cards (INV-15) |
| **Direction** | `dir` from config; default `AUTO` | the card's own direction is never silently overridden (STEP 35/§37) |
| **Text scaling** | `textZoomPercent` from `textScale`, applied as `WebSettings.textZoom` | scales text without rewriting one `font-size` in the card CSS (STEP 38) |
| **Base URL** | `https://card.studyagent.invalid/` (RFC 2606 reserved, unresolvable) | relative references resolve against an origin we own; no real domain, no `file:`, no `about:` (STEP 78) |
| **Side marker** | `data-studyagent-side="question\|answer"` | read-only inspection for tests; grants the page nothing (INV-08) |
| **Verbatim passthrough** | a payload whose first 64 chars (BOM/whitespace-trimmed, lowercased) open `<!doctype`, `<html` or `<body` is returned **unchanged** | wrapping a document in a second document silently drops its attributes and card CSS |

`payloadLength` travels with the document: payload growth is observable in diagnostics without ever
logging content (STEP 124).

---

## 4. WebView configuration (`AnkiCardWebView.kt`)

Every line is a decision, and the instrumented suite reads the settings **back from the platform**
rather than trusting the source.

| Setting | Value | Reason |
|---|---|---|
| `javaScriptEnabled` | per document, from the policy (`false` default, set immediately before each load) | a document can never execute under the previous document's policy (STEP 08) |
| `domStorageEnabled` / `databaseEnabled` | `false` | no demonstrated card-template need; a card has no business persisting state in our WebView (STEP 134) |
| `allowFileAccess` / `allowContentAccess` | `false` | card HTML must not read local files or providers (INV-09) |
| `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` | left at the platform default (`false`), asserted by the isolation scan | never relaxed "to make a card work" (STEP 133) |
| `blockNetworkImageLoads` | `true` | GATE 08 has no media resolver, so a remote image cannot work anyway — and a card must not beacon to a third party (INV-32) |
| `mixedContentMode` | untouched (platform default `NEVER_ALLOW`) | the strict one is already the default |
| `mediaPlaybackRequiresUserGesture` | `true` | no autoplay; audio/video policy is GATE 09 (STEP 60) |
| `setSupportZoom` / `builtInZoomControls` / `displayZoomControls` | `false` / `false` / `false` | scaling is `textZoom`, not pinch-zoom of a card (STEP 38) |
| `useWideViewPort` / `loadWithOverviewMode` | `false` / `false` | the layout viewport equals the view width: `width=device-width` behaviour (STEP 23) |
| `defaultTextEncodingName` | `UTF-8` | INV-27 |
| `textZoom` | per document | accessibility scaling without touching card CSS |
| `cacheMode` | `LOAD_DEFAULT` | asset caching only, never collection truth (STEP 74) |
| `javaScriptCanOpenWindowsAutomatically` / `setSupportMultipleWindows` | `false` / `false` | this is not a browser (STEP 48/§56) |
| `userAgentString` | untouched | no AnkiDroid/desktop-Anki spoofing without evidence (STEP 136) |
| force-dark / algorithmic darkening | **never called** | INV-15 |
| `setWebContentsDebuggingEnabled` | `BuildConfig.DEBUG` only | remote inspection in debug builds, never in release (STEP 135) |
| `addJavascriptInterface` | **never called** | no native bridge for arbitrary card HTML (INV-08) |
| background | `Color.TRANSPARENT` until the document paints | no white flash in a dark app; the page's own background is what the user sees (STEP 40) |
| focus | `isFocusable = true`, `isFocusableInTouchMode = false` | a hardware keyboard or an interactive widget works; native controls keep their focus behaviour (STEP 138) |
| scrollbars | `overScrollMode = NEVER`, `isHorizontalScrollBarEnabled = true` | a wide table scrolls horizontally inside the WebView (STEP 63); no gesture interception (STEP 140) |

---

## 5. Lifecycle and ownership

| Question | Answer |
|---|---|
| **When is a WebView created?** | Once per card surface, inside `remember(context, surfaceToken)`. Never per recomposition (INV-23). |
| **How many are live?** | One. `AnkiRenderPerformance.MAX_LIVE_WEBVIEWS = 1`; a recreation window may briefly hold the dying instance and its replacement, and the metric says so (`webViewCountBounded`) (INV-22). |
| **Is it reused across cards?** | Yes — one WebView, one **full document load** per card. A new document means a new JavaScript global scope, so card A's globals cannot become card B's (STEP 75/§76, INV-25). |
| **Who destroys it?** | The composition that created it (`DisposableEffect.onDispose` → `host.release()`), never the controller. Ownership stays where the instance was allocated, which makes a double-destroy impossible (STEP 14/§15). |
| **Can it retain an Activity?** | No. The WebView is built on a `MutableContextWrapper` whose base context is swapped to the application context before `destroy()` (STEP 15, INV-26). No application-scoped singleton ever holds one. |
| **What recreates it?** | Exactly one thing: `onRenderProcessGone`. It bumps `surfaceToken`, the `AndroidView` is **keyed** on that token (an unkeyed `AndroidView` would keep showing the destroyed instance), the old host is released, a new one attaches, and the controller re-presents the **same turn, card and side** with a new generation (STEP 71-§73, INV-05). |
| **What about rotation?** | The Activity declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`, so rotation does not recreate the composition and therefore does not recreate the WebView (STEP 108). |
| **What if there is no WebView provider?** | `createAnkiCardWebView` returns `null` (a real device state, not a crash); the composition reports `onSurfaceCreationFailed()` **before** the first submission, so the fallback carries the precise category `creation_failed` instead of a generic `no_surface` (STEP 68/§121). |
| **Does switching to the text fallback destroy it?** | No. Visibility, not existence, follows the presentation: `View.INVISIBLE`. Switching back does not rebuild (INV-22/23). |

---

## 6. Request identity, state and stale callbacks

```text
AnkiRenderRequestId = (ReviewTurnId, AnkiCardRef, AnkiCardSide, generation)
```

The controller mints a monotonically increasing `generation` for **every** presentation — a side
flip, a new card, a config change, a retry, a re-presentation onto a fresh surface. Every document
is stamped with it, and every WebView callback echoes the stamp it was loaded with. A callback whose
stamp is not the active one is **dropped and reported** (`ANKI_RENDER_STALE_CALLBACK_IGNORED` with
`callback`, `stale_gen`, `stale_side`, `active_gen`, `same_turn`), and counted in
`staleCallbacksIgnored`. A race is therefore diagnosed by seeing the ignored callback, not by
wondering why a card never became ready (STEP 18/§19, INV-06).

| State | Meaning | `token` |
|---|---|---|
| `Idle` | nothing asked yet, or disposed | `idle` |
| `Loading(request)` | a document was submitted (or a Compose presentation is being decided) | `loading_question` / `loading_answer` |
| `Ready(request, presentation, loadDurationMs, pageFinished)` | presentable | `ready_original`, `ready_clean_compose`, `ready_clean_fallback_compose` |
| `Failed(request, failure, presentation)` | not presentable as asked; `presentation != null` when the text fallback is on screen | `failed_<token>[_with_fallback]` |

Tokens are content-free strings, so a diagnostics row can quote the state without quoting a card.

**Recomposition safety (INV-23).** `submit` compares the whole submission — card, turn, side,
resolved config — and returns `false` when nothing changed. Nothing is reloaded, no JavaScript state
is reset, no scroll position moves, no event is emitted. `awaitingSurface` covers the one legitimate
ordering hazard: Compose applies effects in composition order, so a submission can be decided a
frame before the WebView exists. The controller holds `Loading` for that frame instead of degrading
to text and upgrading back; `attachSurface()` presents the document the moment a surface appears,
and `onSurfaceCreationFailed()` resolves the wait immediately when no WebView will ever appear.

**Scroll ownership (INV-24).** `resetScroll()` is called from `onPageFinished` only — once per new
document. A recomposition never reaches that line, so it never resets scroll either.

---

## 7. Fallback order and failure taxonomy

```text
ORIGINAL (WebView)  →  CLEAN text (Compose, backend's own text channel)  →  typed failure (explicit UI)
```

The planner is a pure function of `(card, side, mode)`:

| Situation | Plan | Presentation | Marked as |
|---|---|---|---|
| side's HTML exists (even `""`) | `Original(html)` | `ORIGINAL` | `card_html_empty` when `""` |
| HTML `null`, text exists | `CleanText(text, reason)` | `CLEAN_FALLBACK` | `card_html_unavailable` |
| HTML `null`, text `null` | `Unavailable(HtmlUnavailable)` | none — explicit message | `failed_html_unavailable_<side>` |
| mode = CLEAN/VOICE_FOCUS, text exists | `CleanText` | `CLEAN` | `render_mode_clean` |
| mode = CLEAN/VOICE_FOCUS, no text, HTML exists | `Original(html)` | `ORIGINAL` (degraded request, not degraded content) | `clean_text_unavailable` |
| mode = CLEAN, neither channel | `Unavailable(TextUnavailable)` | none | `failed_text_unavailable_<side>` |
| no usable WebView, text exists | `Original` → surface unavailable | `CLEAN_FALLBACK` | `webview_unavailable_<category>` |
| load error / TLS refusal / renderer death | `Failed` | `CLEAN_FALLBACK` when text exists | `webview_load_<category>_<code>`, `ssl_error_blocked`, `renderer_process_crashed\|killed` |

**No invention (INV-20).** A fallback is always one of the backend's own channels. There is no
HTML-stripping path, no synthesized placeholder text, no "question + answer" concatenation. When
neither channel exists the UI says so — and says that nothing was answered, rated or skipped,
because the card is still due (INV-19).

Failure taxonomy: `HtmlUnavailable(side)`, `TextUnavailable(side)`, `WebViewUnavailable(category)`,
`WebViewLoadFailure(errorCode, category)`, `RendererProcessGone(didCrash)`, `SslErrorBlocked`.
`isRecoverable` drives **only** a retry-render affordance; retrying a render is not rating, skipping
or burying a card. A render failure is a presentation fact and is never mapped onto backend
availability (INV-18). The platform's error `description` is deliberately **not** carried — it can
embed the failing URL, which is deck content.

---

## 8. Links, scripts and device capabilities

**Navigation (STEP 44-§49, INV-16/§17).** `AnkiCardLinkPolicy` is pure and total — it never throws
on hostile input and never touches `android.net.Uri`, so it is JVM-testable:

| Input | Decision |
|---|---|
| `#fragment` | `ALLOW_IN_PAGE` — a same-document anchor the page may honour (`shouldOverrideUrlLoading` returns `false`) |
| `http(s)://host/…` (not our reserved origin) | `OPEN_EXTERNALLY` — reported upward, card unchanged, WebView never navigates |
| `https://card.studyagent.invalid/…` | `BLOCKED` (`renderer_origin_navigation`) |
| `javascript:` `data:` `blob:` `about:` | `BLOCKED` — script/URL-scheme injection surfaces |
| `file:` `content:` `intent:` `market:` `mailto:` `tel:` `anki://` and **every other scheme** | `BLOCKED` — unknown schemes are not automatically trusted |
| empty / blank / `null` / scheme-less / unparseable host | `BLOCKED` — fail closed, never crash |

`AnkiExternalLinkHandler` is the second, independent check on the app side: http/https only,
`ACTION_VIEW` + `CATEGORY_BROWSABLE`, **no URI permissions granted**, `FLAG_ACTIVITY_NEW_TASK` only
when the context is not an `Activity`, and it never throws (a device with no browser is a normal
state). Every outcome is a value: `Opened` / `Refused(reason)` / `Failed(category)`.

**Scripts (STEP 50-§55, INV-08/09/10).** Card-template JavaScript runs inside the page under
`CARD_TEMPLATE_ONLY` — because a great many real Anki cards are interactive and refusing to run
their script would be a fidelity regression (INV-02). What it cannot do: reach a native object
(`addJavascriptInterface` is never called, so `typeof AnkiDroidJSAPI === "undefined"` and a card
written against AnkiDroid's private API fails inside its own `catch` and still renders); open a
window; navigate the reviewer; or write to storage. A console error is **counted, never
transcribed** — a card's console output is deck content, so production logs get the level and a
count, and only debug builds log a sanitized, 200-char-capped message. Modal dialogs
(`alert`/`confirm`/`prompt`/`beforeunload`) are suppressed rather than allowed to block the review
UI.

**Device capabilities (STEP 56-§58, INV-31).** `onPermissionRequest` → `deny()`;
`onGeolocationPermissionsShowPrompt` → `allow=false, retain=false`; `onShowFileChooser` → answered
with `null` (an unanswered callback stays pending forever) and refused; `getDefaultVideoPoster` →
`null`. Study-Agent's microphone belongs to the native voice subsystem, never to a card script.
Resources are counted, never named.

---

## 9. Performance measurement (`AnkiRenderPerformance`)

Four latency families over the app's existing bounded `LatencyWindow` (128 samples), plus counters.
A family that was never measured reports `-`, never `0ms` — an unmeasured renderer must not look
instant.

| Family | Metric name | Measures |
|---|---|---|
| WebView creation | `anki_render_webview_creation` | `createWebView` → instance; the number that decides the reuse strategy |
| Document load | `anki_render_document_load` | document submit → `onPageFinished`; per-card render latency |
| Side transition | `anki_render_side_transition` | question → answer **of the same turn** |
| New-card transition | `anki_render_new_card_transition` | turn A → turn B: submit → ready |

Counters: `documentsLoaded`, `webViewsCreated`/`webViewsReleased` (+ `outstandingWebViews`,
`webViewCountBounded`), `fallbacksUsed`, `rendererProcessFailures`, `staleCallbacksIgnored`,
`externalLinksBlocked`/`externalLinksMediated`, `webPermissionsDenied`, `javascriptErrors`.
`snapshot().describe()` is one content-free diagnostics row.

A **failed** presentation contributes no latency sample: `completeTransition(null)` clears the
pending transition without recording, because 0 ms would look like an instant success in the p50/p95
this metric exists to protect.

**Measured numbers: NOT RUN.** The 100-card endurance run, the reuse-vs-recreate comparison, cold
WebView creation and memory behaviour require a device. They are instrumented, they are asserted for
*shape* by `AnkiRenderPerformanceTest` and `AnkiCardRenderInstrumentedTest`, and the actual numbers
belong in this section the moment hardware exists. No number is reported here that was not measured.

---

## 10. Diagnostics and privacy

14 event types, one `ANKI_RENDER_*` name each, and one shared identity block
(`card`, `ord`, `side`, `gen`) plus per-event facts: identifiers, counts, lengths, durations and
stable tokens. `AnkiRenderEventTest` asserts the property across **every** event type: no card HTML,
no question or answer text, no link URL, no console message, no exception message, no WebView error
description; every metadata value ≤ 48 chars; one bounded log line per event.

`AnkiRenderDiagnostics` maps events to `AppLogger` by meaning — WARN for what a user may have seen
(failure, renderer death, refused navigation, denied capability, blocked TLS), DEBUG for routine
race evidence (stale callback, JS console, suppressed dialog), INFO for lifecycle. The renderer core
never imports `AppLogger`: the pure layer produces events, the adapter decides where they go.

---

## 11. Compatibility matrix (STEP 96-§100)

The deck lives in `app/src/debug/java/…/AnkiCardRenderFixtures.kt` (**debug source set only** — a
release build ships no card content of our invention) on `AnkiBackendId.Fake("render-fixtures")`, so
a fixture can never be mistaken for a real collection row. Every string is written for this
repository; nothing is copied from AnkiDroid's assets (INV-10/§21).

"JVM" = proven by `AnkiCardRenderFixturesTest` / `AnkiCardDocumentTest` (the document we hand
Chromium). "Device" = proven by `AnkiCardRenderInstrumentedTest` (what Chromium does with it) —
**NOT RUN** in this environment.

| # | Fixture (id) | Dimension | Expectation | JVM | Device |
|---|---|---|---|---|---|
| 1 | `basic` | plain text, no markup | PASS | ✅ | ✅ (harness) |
| 2 | `html_formatting` | b/i/u, lists, `<br>`, sub/sup, entities | PASS | ✅ | ✅ |
| 3 | `nested_divs` | nested structure with classes and ids | PASS | ✅ | ✅ |
| 4 | `css_card_class` | template CSS with a `.card` rule | PASS | ✅ | ✅ (computed styles) |
| 5 | `light_authored` | card-authored light theme inside a dark app | PASS | ✅ | ✅ (not darkened) |
| 6 | `dark_authored` | card-authored dark theme | PASS | ✅ | ✅ (not inverted) |
| 7 | `table` | ordinary bordered table | PASS | ✅ | ✅ |
| 8 | `wide_table` | 12 columns × 8 rows, `white-space:nowrap` | PASS | ✅ | ✅ (scrollWidth > innerWidth) |
| 9 | `cloze` | Anki-rendered cloze output | PASS | ✅ | ✅ (no local cloze logic) |
| 10 | `arabic_rtl` | Arabic with authored `dir="rtl"` | PASS | ✅ | ✅ (`direction: rtl`) |
| 11 | `arabic_no_dir` | Arabic with **no** direction markup | PASS | ✅ | ✅ (`dir="auto"` resolves rtl) |
| 12 | `mixed_arabic_english` | bidi mix, numerals, `<span dir=ltr>` | PASS | ✅ | ✅ (both directions) |
| 13 | `long_question` | 40 paragraphs | PASS | ✅ | ✅ (scrolls, no truncation) |
| 14 | `long_answer` | 60-paragraph reveal | PASS | ✅ | ✅ (scroll resets per document) |
| 15 | `unicode_symbols` | Greek, relations, arrows, diacritics, `&amp;amp;` | PASS | ✅ | ✅ (UTF-8 + no double decode) |
| 16 | `emoji` | emoji incl. ZWJ sequence | PASS | ✅ | ✅ |
| 17 | `js_on_load` | script rewrites visible text on load | PASS | ✅ | ✅ (script ran) |
| 18 | `js_on_click` | button toggles a hidden hint | PASS | ✅ | ✅ (works in-page) |
| 19 | `js_ankidroid_api` | script expects `AnkiDroidJSAPI` | PASS | ✅ | ✅ (survives, API absent) |
| 20 | `links` | https / anchor / mailto / custom scheme | PASS | ✅ | ✅ (mediated, in-page, blocked) |
| 21 | `full_document` | complete `<!doctype html>` payload | PASS | ✅ | ✅ (verbatim, one `<html>`) |
| 22 | `visual_only` | HTML but no text channel | PASS | ✅ | ✅ (documented degradation) |
| 23 | `text_only` | text but no HTML channel | PASS | ✅ | ✅ (CLEAN fallback) |
| 24 | `answer_without_content` | answer side with neither channel | PASS | ✅ | ✅ (typed failure, explicit UI) |
| 25 | `image_ref` | `<img src="…jpg">` | **DEFERRED → GATE 09** | ✅ reference preserved | ⛔ media resolution |
| 26 | `audio_tag` | `[sound:…]` / `<audio>` | **DEFERRED → GATE 09** | ✅ reference preserved | ⛔ media resolution |
| 27 | `math_markup` | MathJax-shaped markup | **DEFERRED → GATE 09** | ✅ markup untouched | ⛔ no MathJax shipped |
| 28 | `custom_font` | `@font-face` / font stack | **DEFERRED → GATE 09** | ✅ CSS untouched | ⛔ no font resolution |

24 PASS rows, 4 deferred. The partition is machine-checked
(`AnkiCardRenderFixturesTest.deferred rows are exactly the media, math and font fixtures`), so the
report cannot quietly claim more than the gate proves. For the deferred rows GATE 08 guarantees only
that the **reference survives verbatim** into the document and that the card still renders; whether
the image, sound, formula or font resolves is GATE 09's `shouldInterceptRequest` / media work
(INV-32).

---

## 12. Integration surface (STEP 143)

```kotlin
@Composable
fun AnkiCardRenderer(
    card: AnkiRenderedCard,
    turnId: ReviewTurnId,
    side: AnkiCardSide,                       // explicit input — never inferred (INV-04)
    modifier: Modifier = Modifier,
    config: AnkiCardRenderConfig = AnkiCardRenderConfig.DARK_APP,
    surfaceKind: AnkiRenderSurfaceKind = AnkiRenderSurfaceKind.BROWSING,
    performance: AnkiRenderPerformance? = null,
    onRenderEvent: (AnkiRenderEvent) -> Unit = {},
    onExternalLink: ((AnkiExternalLinkRequest) -> Unit)? = null,   // null → AnkiExternalLinkHandler
    createWebView: (Context) -> WebView? = { createAnkiCardWebView(it) }  // the test seam
)
```

Layout: an outer `Column` (`heightIn(min = 220.dp)`, test tag `anki_card_renderer`, accessible name
+ `stateDescription` = the state token) → an optional `InfoBanner` when the presentation failed →
a `Box` (`weight(1f)`, `heightIn(min = 160.dp)`) holding the WebView (`matchParentSize`), an opaque
placeholder overlay, the Compose text fallback, or the explicit "no displayable content" message.

**Bounded fill, not dynamic measurement (STEP 65/§66).** The card area fills the space the caller
gives it and the WebView scrolls its own document. There is deliberately no "JS reports document
height → Compose resizes → WebView relayouts" loop — that loop is how a renderer ends up measuring
forever. The placeholder appears only after `PLACEHOLDER_DELAY_MS = 120` (no flicker on a fast load)
and the overlay is opaque, so the previous card is never visible under a new turn.

**Nothing else is wired in this gate, on purpose.** The production `StudyScreen` still shows the
protocol `StudyCard`; installing `AnkiRenderedCard` into the live review flow is GATE 10's
`StudySessionMachine` work, and doing it here would mean rewriting the study flow one gate early.
The controlled surfaces for this gate are `AnkiCardRenderPreviews.kt` (9 previews over the deck) and
the instrumented suite. No reveal button and no rating bar is injected into the card HTML
(STEP 94/§95) — that UI stays native.

---

## 13. Tests

**227 tests: 195 JVM + 32 instrumented.**

| Suite | Tests | Covers |
|---|---|---|
| `AnkiCardDocumentTest` | 27 | the shell (doctype/head/body counts, UTF-8 meta + encoding, viewport without `user-scalable=no`, `.card` class, side marker carrying no identity); fidelity (byte-for-byte insertion, no escaping, no double decoding, no `<br>` substitution, Arabic/emoji, tables untouched, cloze passthrough, scripts kept under both JS policies); CSS priority (base before card, minimal base, only `canvas`/`canvasText`); direction (ltr/rtl/auto, authored `dir` preserved); night mode (scheme + classes, no `invert()`, no hex of our own); verbatim passthrough (doctype, `<body>`-only, a fragment that merely mentions `<html>`, BOM/whitespace); `textZoom` carried not rewritten; reserved base URL; blank payload; mode never changes the document |
| `AnkiCardRenderPlanTest` | 14 | ORIGINAL uses the side's own HTML and nothing else; **the answer is never rebuilt from question + answer**; text carried as the marked fallback; `""` rendered with `card_html_empty`; `null` → CLEAN fallback with `card_html_unavailable`; no channel → typed failure; GATE 07 degradation tokens carried; CLEAN/VOICE_FOCUS semantics; CLEAN degrades to ORIGINAL (never to stripped HTML); unresolved ADAPTIVE never reaches a WebView; no plan invents content |
| `AnkiCardRenderControllerTest` | 48 | idle start; Loading→Ready with the measured duration; submit-before-attach waits instead of degrading; side flip inside one turn; the answer document is the backend's answer; new card = new request; monotonic generations; retry re-presents the same target; **stale rejection on every callback path**; out-of-order callbacks with only the latest authoritative; identical resubmission reloads nothing; an equal card from a fresh read is the same presentation; a config change is a real re-presentation; scroll resets once per document and never on recomposition; CLEAN never touches the WebView; ORIGINAL→CLEAN and ADAPTIVE resolution per surface kind; every fallback and failure path with its category; load failure/SSL/renderer death + recovery on a new surface; a failed presentation contributes no latency sample; link mediation, anchors, blocked schemes, stale link refusal; JS console/dialog/permission handling; detach/dispose semantics and ownership; measurement families; **no event carries card content** |
| `AnkiCardLinkPolicyTest` | 16 | anchors in-page; http(s) mediated; case-insensitive scheme/host; our own reserved origin blocked; script/data/blob/about blocked; file/content/intent/market/mailto/tel/sms/geo/anki/ankidroid/whatsapp/studyagent blocked; a card can never reach an Anki provider through a link; empty/blank/null blocked; scheme-less blocked; unparseable host blocked; malformed schemes rejected not guessed; totality over hostile input (5 000-char URLs, NUL, RLO, backslash authority, credentials, absurd port); one decision per input; diagnostics never carry the URL/host/path; scheme tokens length-capped; classification is pure |
| `AnkiRenderStateTest` | 23 | request identity and equality; generation ≥ 1; staleness is identity not timing; turn/target comparisons; log-safe `stableKey`; Idle/Loading/Ready/Failed tokens; WebView page vs Compose presentation; `showingFallback`; non-negative durations; content-free tokens for every state; recoverability (only genuinely retryable failures); stable failure tokens; no exception message or URL in a failure; presentation tokens; config defaults; text-scale bounds and rejection of NaN/∞; night-mode switch touches nothing else; ADAPTIVE resolves once by surface kind and is idempotent; **the JS policy has no native-bridge member**; the four modes and nothing else; direction defaults to AUTO; the two sides |
| `AnkiRenderPerformanceTest` | 11 | unmeasured families report `-` never `0ms`; the four families are named and kept apart; the window is bounded (1 000 samples → 4 in window, honest lifetime count); a negative sample cannot poison a percentile; WebView accounting keeps the live count bounded (incl. the recreation overlap); releases never go negative; `MAX_LIVE_WEBVIEWS == 1`; counters are independent; `describe()` is one content-free line; reset clears windows and counters together |
| `AnkiRenderEventTest` | 11 | one instance of all 14 event types: unique `ANKI_RENDER_*` names; turn identity carried; **no event carries card content** even for a hostile card; a link event carries the scheme and never the URL; a console error carries level + count and never the message; a failure carries a token and never a description; a stale-callback event explains the race with identity only; lifecycle reasons and surface categories are closed vocabularies; one bounded line per event; the metadata vocabulary is bounded and content-free; the `html` key is a length, not the document |
| `AnkiCardRenderFixturesTest` | 19 | the deck is 28 fixtures with unique ids/labels, all addressable, snake_case; every fixture is on the Fake backend; GATE 07 nullability honoured (no `""` for "absent"); the PASS/DEFERRED partition is exactly the four media-and-math rows; every claimed compatibility dimension exists; degradation tokens match the documented fixtures; no fixture names a real collection/provider/package; **the planner is total over 28 × 2 sides × 4 modes**; HTML → ORIGINAL with that exact HTML; missing HTML → the fixture's own text; a side with nothing → typed failure; degradation tokens reach plan diagnostics; every payload survives the document builder byte for byte; only `full_document` is verbatim; night mode never rewrites authored colors (exact shell-delta assertion); RTL fixtures keep authored direction and get an `auto` shell; wide content is never squeezed; scripts stay in every document under both policies; link fixtures classify through the same policy the WebView uses |
| `AnkiRendererIsolationTest` | 26 | the renderer exists where the architecture says (and fixtures are debug-only); **core purity** — no Android/Compose/ui/data/BuildConfig imports, no calendar types, and an explicit allowlist of the six non-project imports the core may use; **no bridge** — 12 forbidden tokens, no `evaluateJavascript`, no `loadUrl(…)`, documents arrive through `loadDataWithBaseURL` only; **no scheduler/gateway/voice/AI vocabulary** — 19 + 16 forbidden tokens, no collection writes, and a project-import allowlist; night mode is never a forced inversion; the WebView host pins all 16 required settings; file/content origins refused; TLS cancelled never proceeded; `onRenderProcessGone` handled and returns `true`; navigation goes through the controller; permissions denied and dialogs suppressed; only two files may name `android.webkit` (and the fallback view may not); logging through `AppLogger` only; the surface seam has **no imports at all**; one surface per composition, destroyed on dispose; the entry point does not recreate a WebView on recomposition; no AnkiDroid assets, MathJax or jQuery; the base URL is the reserved origin |
| `AnkiCardRenderInstrumentedTest` (androidTest) | 32 | **NOT RUN** — see §14. What only a device can prove: the document Chromium received (base URL, side marker, `characterSet`, one `<html>`); side switching on one WebView without a doubled question; recomposition reloading nothing; a new card on the same WebView with scroll reset; **all 24 PASS fixtures on one bounded WebView**; CLEAN creating no WebView; the text-only fallback and the empty-side message; a null WebView factory still showing text; card JS running under the template policy and not running under DISABLED; an interactive widget working in-page; a card expecting `AnkiDroidJSAPI` surviving; **no native bridge and the hardened settings read back from the platform**; a suppressed dialog; Arabic RTL and `dir="auto"` resolution; bidi mixing; UTF-8 diacritics/entities/emoji; a wide table scrolling inside the WebView; a long card scrolling and resetting; card CSS beating the base CSS; night mode classes without inversion; a light card not darkened; `textZoom` without rewritten CSS; a complete document not double-wrapped; a link mediated outward with the card staying put; mailto/custom schemes blocked before any Intent; the external-link handler refusing every non-web scheme; accessibility name and state; renderer-process recovery on a new WebView with the same turn; release destroying the WebView and refusing further work |

Shared test doubles (`AnkiRenderTestDoubles.kt`): a deterministic clock (no sleeping), a recording
`AnkiCardRenderSurface`, an event recorder with a privacy view, and card builders that mirror GATE
07's real contract shape — fragments, an answer side that already contains the question, and
nullability that means something.

---

## 14. Validation results (honest)

| Check | Result |
|---|---|
| `python3 server/test_contract.py` | **PASS** — "All unit contract tests passed!" (untouched by this gate; run to confirm the repo's only executable gate still passes) |
| Static isolation scans (Python mirror of `AnkiRendererIsolationTest` + `AnkiDroidIntegrationIsolationTest` rules over all new `core/render`, `ui/components/anki`, debug fixtures and test sources, comments stripped) | **PASS** — clean: no `com.ichi2.anki`, no provider/permission vocabulary, no scheduler/rating/TTS/STT/AI tokens, no `addJavascriptInterface`/`evaluateJavascript`, no force-dark/inversion, no `java.time`/`Calendar`, no `android.*`/`androidx.*` in `core/render`, no AnkiDroid assets, no `Throwable`/`Exception` in the render core, no `println`/`android.util.Log` |
| Structural scan (brace/paren balance over all 32 new and existing render files; type-resolution check of every `Anki*`/`Card*` symbol referenced by the new tests against the declared symbols in `core/render`, `core/anki`, `core/common`, `core/diagnostics`, `ui/components/anki`; every event/state/failure/plan/config member used by a test re-read from its declaration) | **PASS** — 0 unresolved symbols, 0 unbalanced files |
| Step-citation coverage (grep of `STEP nnn` over the five GATE 08 source roots) | **116 distinct step numbers cited, spanning STEP 05–144** — `AnkiCardWebView.kt` 42, `AnkiCardRenderController.kt` 27, `AnkiCardRenderFixtures.kt` and the instrumented harness 26 each, `AnkiCardDocument.kt` 23, `AnkiCardRenderer.kt` 16. Uncited steps are the gate's orientation steps (01–04), the measurement/closure tail (145–150, discharged by §9, §11 and this table), and steps enforced by a test rather than restated in a comment; every invariant in §15 is cited from at least one production or test file, so no requirement is left unanchored |
| `./gradlew testDebugUnitTest lint assembleDebug assembleRelease` | **BLOCKED_BY_ENVIRONMENT** — no JDK (`java`/`javac` absent, no `JAVA_HOME`, no `/usr/lib/jvm`), no Android SDK, no `kotlinc`, and no network route to `services.gradle.org` (`SSL_ERROR_SYSCALL`); `apt-get` is not permitted. Same block as GATE 00/04/05/06/07 |
| CI (`android-ci.yml`) | **BLOCKED** — `gh` in this sandbox returns `HTTP 401: Bad credentials`; the GitHub connection needs to be re-established before any workflow can be observed or triggered |
| Instrumented suite / real device | **NOT RUN** — no emulator, no device, no WebView |
| Performance numbers (cold WebView creation, document load p50/p95, side and new-card transitions, 100-card endurance, memory) | **NOT MEASURED** — instrumented and asserted for shape only (§9). No number is reported that was not measured |

**No unavailable test is reported as PASS.** The 227 tests are committed as executable
specifications: 195 of them need only a JVM and will run the moment a JDK exists; the 32
instrumented ones need a device and skip themselves (`assumeTrue`) where a WebView provider is
absent.

---

## 15. Invariants — INV-ANKI-RENDER-01 … 32 (verdicts)

| ID | Contract | Verdict |
|---|---|---|
| **01** | The renderer never calls the scheduler, a backend, a gateway, TTS, STT or the AI agent; it presents content it is given. | **HOLD** — `AnkiRendererIsolationTest` (35 forbidden tokens + project-import allowlist); no repository/gateway type is reachable from `core/render` |
| **02** | ORIGINAL renders the backend's own HTML/CSS/JS with fidelity first; nothing is rewritten to suit Study-Agent. | **HOLD** — byte-for-byte insertion (`AnkiCardDocumentTest`), card CSS wins, scripts run under `CARD_TEMPLATE_ONLY`; device-verification **NOT RUN** |
| **03** | The renderer reports upward (events, mediated links); it never commands the app or the session. | **HOLD** — `AnkiExternalLinkRequest` is a value, `onExternalLink` is a callback, `AnkiCardLinkPolicy` has no `Context` in its signature |
| **04** | The visible side is always an explicit input; nothing infers "the answer is showing" from content shape. | **HOLD** — `side` is a required parameter; no `answerHtml != null` / `<hr id=answer>` inference exists (plan tests) |
| **05** | Question and answer are two visual states of the **same** `ReviewTurnId`; a side flip never mints a turn and never touches the scheduler. Recovery re-presents the same turn/card/side. | **HOLD** — `AnkiCardRenderControllerTest` (side flip, renderer-death recovery, `retry`); generation increments, turn identity stable |
| **06** | Every WebView callback carries the request identity it was loaded with; a callback from a superseded document is dropped **and reported**. | **HOLD** — `AnkiRenderRequestId` stamping, `rejectStale` on every callback path, `StaleCallbackIgnored` event + counter, out-of-order test |
| **07** | All card logic is JVM-pure; `android.webkit` appears in exactly one file (plus the entry point's type-only seam). | **HOLD** — core-import allowlist scan; `only the WebView host and the entry point may name android webkit` |
| **08** | No JavaScript bridge: `addJavascriptInterface` is never called and no native object is exposed to a card. | **HOLD** — isolation scan (12 tokens); instrumented `typeof window.*` assertions (**NOT RUN**) |
| **09** | Card HTML/CSS/JS is untrusted: no file or content access, no DOM storage, no network image loads, no capability of any kind. | **HOLD** — settings pinned in source, asserted by the isolation scan, read back from the platform in the instrumented suite (**NOT RUN**) |
| **10** | AnkiDroid's private JS API is absent by design; a card that expects it fails inside its own script and still renders. | **HOLD** — `js_ankidroid_api` fixture + planner/document tests; device behaviour **NOT RUN** |
| **11** | Template and cloze semantics remain the backend's; the renderer never interprets `{{…}}` or expands a cloze. | **HOLD** — no template code exists; the cloze fixture asserts rendered output passes through and `{{c1::` never appears |
| **12** | The answer side is rendered exactly as the backend produced it; the question is never prepended. | **HOLD** — `the answer is never rebuilt from question plus answer` (exactly one front-side marker, exactly one `<hr`), instrumented assertion on the live DOM (**NOT RUN**) |
| **13** | No AnkiDroid internal assets: never loaded from assets, never copied, never reimplemented. | **HOLD** — isolation scan (`android_asset`, `ankidroid.css`, `reviewer.css`, `MathJax`, `jquery`, `getAssets()`), base CSS is 4 lines of our own |
| **14** | The renderer's base CSS is minimal and precedes the card's, so authored card CSS always wins; authored direction is never silently overridden. | **HOLD** — document-order assertion, minimal-base-CSS assertion, `dir` from config with AUTO default; computed-style verification **NOT RUN** |
| **15** | Night mode is `color-scheme: dark` plus the Anki body classes — never `setForceDark`, never algorithmic darkening, never an inversion filter. | **HOLD** — isolation scan + document tests + exact shell-delta assertion over all 28 fixtures; computed `filter: none` verification **NOT RUN** |
| **16** | A card never navigates the reviewer: the WebView is not a browser and app Back is not WebView history. | **HOLD** — `shouldOverrideUrlLoading` delegates to the controller, `clearHistory()` on page finish and on release, single-window settings; device verification **NOT RUN** |
| **17** | Unknown, local and script URL schemes are blocked; only http(s) may leave the app, and only through the app's own handler. | **HOLD** — `AnkiCardLinkPolicyTest` (16 tests incl. totality over hostile input) + the handler's independent second check |
| **18** | A render failure is a presentation fact, never an Anki/data failure, and is never mapped onto backend availability. | **HOLD** — `AnkiRenderFailure` is a render-local hierarchy; no `AnkiError` type is referenced anywhere in `core/render` |
| **19** | Rendering never mutates the collection or the turn: no rating, bury, suspend or skip; a card that fails to render is still due. | **HOLD** — isolation scan (no write tokens, no scheduler vocabulary); the failure notice says so in those words; `retry()` is a presentation attempt only |
| **20** | Fallbacks use only channels the backend supplied; content is never invented, stripped or synthesized. | **HOLD** — planner tests (`no plan invents content`), fallback tests, the explicit "no displayable content" state |
| **21** | No reimplementation of AnkiDroid internals (JS API, reviewer assets, template engine) inside Study-Agent. | **HOLD** — isolation scan + the fixture deck is written for this repository; nothing is copied |
| **22** | The live WebView count is bounded (one active reviewer surface); every creation is matched by a release. | **HOLD** — `remember(context, surfaceToken)`, `MAX_LIVE_WEBVIEWS = 1`, `webViewCountBounded` asserted in JVM and instrumented tests; the 24-card deck assertion is **NOT RUN** |
| **23** | A recomposition with identical inputs reloads nothing, recreates nothing and moves no scroll position. | **HOLD** — `submit` returns `false` on an equal submission (no document, no event, no reset); instrumented recomposition test **NOT RUN** |
| **24** | Scroll position belongs to the document: a new document starts at the top; a recomposition never resets it. | **HOLD** — `resetScroll()` only from `onPageFinished`, counted in tests; instrumented `window.scrollY` assertions **NOT RUN** |
| **25** | One full document load per presentation, so each card gets a fresh JavaScript global scope; nothing merges two cards' state. | **HOLD** — `loadDataWithBaseURL` per presentation, no `loadUrl`, one document per `presentDocument` call (counted in tests) |
| **26** | The renderer holds no card content beyond the presentation and no platform object beyond the composition; disposal drops both. | **HOLD** — `dispose()` clears submission/surface/timing and returns to `Idle`; the controller never calls `release()`; the `MutableContextWrapper` base context is swapped before `destroy()` |
| **27** | Card documents are UTF-8 end to end: no transcoding, no double-escaping, no entity re-decoding. | **HOLD** — `AnkiCardDocument.init` *requires* UTF-8; escaping/decoding/newline tests; live-DOM verification **NOT RUN** |
| **28** | The render mode is presentation only: it never changes which content channels exist and never rewrites the domain card. | **HOLD** — the planner reads the card and returns a plan; `mode never changes the document`; no mutation of `AnkiRenderedCard` anywhere |
| **29** | The render mode never decides anything about speech or evaluation; the visual channel stays visual in every mode. | **HOLD** — `CleanText` uses `questionText`/`answerText` as *display* text only; `pureAnswerText` is never read by the renderer |
| **30** | WebView settings are explicit and hardened: every capability the gate does not need is off, and text scaling is `textZoom`, not pinch-zoom or rewritten CSS. | **HOLD** — 16 settings pinned + isolation assertions + `textZoom` tests; platform read-back **NOT RUN** |
| **31** | A card script gets no device capability: microphone, camera, geolocation, file picker and modal dialogs are denied/suppressed and counted. | **HOLD** — `onPermissionRequest.deny()`, geolocation `allow=false, retain=false`, file chooser refused, dialogs cancelled; `WebPermissionDenied` counter; device verification **NOT RUN** |
| **32** | Media, network and resource policy is out of scope for GATE 08: references are preserved unresolved, network image loads are blocked, and the base origin is reserved. | **HOLD** — `blockNetworkImageLoads = true`, no `shouldInterceptRequest`, `.invalid` base URL, 4 fixtures explicitly `DEFERRED_GATE_09` |

All 32 invariants are **implemented and covered by committed tests**. 24 are proven by JVM tests that
need nothing but a JDK; the device-dependent half of 02, 08, 09, 10, 12, 14, 15, 16, 22, 23, 24, 27,
30 and 31 is instrumented and **NOT RUN** (§14). None is claimed device-verified.

---

## 16. Deliberate deferrals

- **GATE 09 (media & resources):** image/audio/`[sound:…]` resolution, MathJax, custom fonts,
  `shouldInterceptRequest` routing, whether remote assets may load inside a card at all, and the
  base origin's replacement. GATE 08 preserves every reference verbatim and blocks network image
  loads so nothing silently beacons.
- **GATE 10 (`StudySessionMachine`):** installing `AnkiRenderedCard` into the live review flow,
  driving `side` from the reveal decision, choosing `surfaceKind` for the voice loop, and the final
  UX of CLEAN/VOICE_FOCUS. The seams exist (`AnkiCardRenderer`, `AnkiRenderSurfaceKind`,
  `VOICE_FOCUS`); the designs do not, and this gate does not pretend otherwise.
- **GATE 11 (writes):** rating commit, bury, suspend, flag edits. Nothing in the renderer can reach
  them, and a render failure never becomes a scheduling event.
- **Performance numbers:** the endurance run, cold-start latency, memory behaviour and the
  reuse-vs-recreate comparison are instrumented but unmeasured (§9).
- **Accessibility of WebView content:** the surface is labelled and its state is exposed, and nothing
  merges or hides the WebView's own accessibility tree; a full TalkBack pass over rendered cards is
  device work (`docs/REAL_DEVICE_TEST_MATRIX.md`).
- **Sanitization:** deliberately absent. Content is the backend's rendered output and is preserved
  verbatim (INV-02); safety comes from the hardened WebView (no bridge, no file/content access, no
  storage, blocked schemes, denied capabilities), not from rewriting a student's cards.

---

## 17. GATE 09 readiness

**YES — GATE 09 (media & resource resolution) may proceed**, on the strength of: a document builder
with one reserved base origin and one verbatim-passthrough rule, so a media resolver has exactly one
place to hook (`shouldInterceptRequest` in `AnkiCardWebView.kt`, the only file that touches
`android.webkit`); a pure core that already carries media references untouched from GATE 07
(`AnkiRenderedCard.media`) and four fixtures pinning the deferred behaviour; a link policy that is
total and fail-closed, so a `media:`-shaped scheme cannot sneak in; performance windows ready to
measure resolution latency; and an isolation scan that will fail the build if media work reaches for
the scheduler, the collection or a bridge.

Conditions carried forward: **Gradle/CI/device verification remains `BLOCKED_BY_ENVIRONMENT`** and
must be executed the moment the environment allows — the 195 JVM tests first, then the 32
instrumented ones on a real device, then the performance numbers into §9. GATE 09 must not begin
rating, burying or suspending cards (GATE 11), and must not resolve media by reaching into
AnkiDroid's private storage — the pinned provider contract is the only door.
