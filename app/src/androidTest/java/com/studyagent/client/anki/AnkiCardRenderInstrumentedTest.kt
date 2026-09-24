package com.studyagent.client.anki

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ContentDescription
import androidx.compose.ui.semantics.StateDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studyagent.client.awaitNode
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.render.AnkiCardLinkPolicy
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.AnkiCardRenderController
import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiExternalLinkRequest
import com.studyagent.client.core.render.AnkiJavascriptPolicy
import com.studyagent.client.core.render.AnkiLinkClassification
import com.studyagent.client.core.render.AnkiLinkDecision
import com.studyagent.client.core.render.AnkiRenderEvent
import com.studyagent.client.core.render.AnkiRenderFailure
import com.studyagent.client.core.render.AnkiRenderPerformance
import com.studyagent.client.core.render.AnkiRenderPresentation
import com.studyagent.client.core.render.AnkiRenderRequestId
import com.studyagent.client.core.render.AnkiRenderState
import com.studyagent.client.ui.components.anki.AnkiCardRenderFixtures
import com.studyagent.client.ui.components.anki.AnkiCardRenderFixtures.Fixture
import com.studyagent.client.ui.components.anki.AnkiCardRenderer
import com.studyagent.client.ui.components.anki.AnkiCardRendererTags
import com.studyagent.client.ui.components.anki.AnkiCardWebViewHost
import com.studyagent.client.ui.components.anki.AnkiExternalLinkHandler
import com.studyagent.client.ui.components.anki.AnkiExternalLinkResult
import com.studyagent.client.ui.components.anki.createAnkiCardWebView
import com.studyagent.client.ui.theme.StudyAgentTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 08 STEP 101/§110/§128/§129 — the card renderer on a real Android WebView.
 *
 * ## Status
 *
 * **NOT RUN.** The gate environment has no JDK, no Android SDK, no emulator and no device, so nothing
 * in this file has ever executed. It is committed as the harness that turns GATE 08's compatibility
 * matrix from a reading of Chromium's documented behaviour into observed behaviour, and it is written
 * to be safe wherever it runs: no test rates a card, opens a collection, launches a browser, touches the
 * network or leaves the app.
 *
 * ## What only this file can prove
 *
 * The JVM suite (`AnkiCardDocumentTest`, `AnkiCardRenderControllerTest`, `AnkiCardRenderFixturesTest`)
 * proves the document we *hand* to Chromium and every decision around it. It cannot prove what Chromium
 * does with it. These tests can, and that is their whole reason to exist:
 *
 * - `dir="auto"` really resolves an Arabic fragment right-to-left (STEP 35/§37, INV-RENDER-14);
 * - UTF-8 loading really preserves diacritics, entities and emoji (STEP 79-§81, INV-RENDER-27);
 * - a card's own `.card { … }` CSS really wins over the renderer's base stylesheet (STEP 24/§40);
 * - a wide table really scrolls inside the WebView instead of breaking the layout (STEP 63);
 * - card JavaScript really runs under `CARD_TEMPLATE_ONLY`, really does not run under `DISABLED`, and
 *   really finds no native bridge to call (STEP 08-§10, §50-§51, INV-RENDER-08/09/10);
 * - one WebView really is reused across sides and cards, and really is recreated after a renderer
 *   process death (STEP 12/§13, §72-§73, INV-RENDER-22/§05);
 * - night mode really adds the Anki body classes without inverting anything (STEP 41/§42, INV-RENDER-15).
 *
 * ## Why it never fails on a device without a WebView provider
 *
 * [requireWebViewProvider] skips the whole suite with [assumeTrue] when the platform cannot create a
 * WebView at all — a real device state that must produce "skipped", never a red gate about the phone
 * rather than about the code.
 *
 * ## Why `evaluateJavascript` appears here and nowhere in production
 *
 * Reading the live DOM is the only way to observe what Chromium did. It is a *test* capability: the
 * renderer never calls it (`AnkiRendererIsolationTest` fails the build if it ever does), because a
 * renderer that talks to its own page is one step away from a bridge.
 */
@RunWith(AndroidJUnit4::class)
class AnkiCardRenderInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------ test-owned surface state

    private val cardState = mutableStateOf<AnkiRenderedCard?>(null)
    private val turnState = mutableStateOf(ReviewTurnId("instrumented:initial:1"))
    private val sideState = mutableStateOf(AnkiCardSide.QUESTION)
    private val configState = mutableStateOf(AnkiCardRenderConfig.DARK_APP)

    /** Bumped to force a recomposition of the renderer with *identical* inputs (INV-RENDER-23). */
    private val parentPadding = mutableStateOf(0.dp)

    private val events = CopyOnWriteArrayList<AnkiRenderEvent>()
    private val links = CopyOnWriteArrayList<AnkiExternalLinkRequest>()
    private val webViews = CopyOnWriteArrayList<WebView>()
    private val performance = AnkiRenderPerformance()

    /** The counting factory of STEP 110: every WebView the renderer creates is recorded here. */
    private val createWebView: (Context) -> WebView? = { context ->
        createAnkiCardWebView(context)?.also { webViews += it }
    }

    @Before
    fun requireWebViewProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = runCatching { createAnkiCardWebView(context) }.getOrNull()
        assumeTrue("this device has no usable WebView provider", probe != null)
        onMain { probe?.destroy() }

        compose.setContent {
            StudyAgentTheme {
                Box(modifier = Modifier.fillMaxSize().padding(parentPadding.value)) {
                    val card = cardState.value
                    if (card != null) {
                        AnkiCardRenderer(
                            card = card,
                            turnId = turnState.value,
                            side = sideState.value,
                            config = configState.value,
                            performance = performance,
                            onRenderEvent = { events += it },
                            onExternalLink = { links += it },
                            createWebView = createWebView,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ presenting

    private fun present(
        fixture: Fixture,
        side: AnkiCardSide = AnkiCardSide.QUESTION,
        config: AnkiCardRenderConfig = AnkiCardRenderConfig.DARK_APP,
        turn: ReviewTurnId = ReviewTurnId("instrumented:${fixture.id}:1")
    ) = presentCard(fixture.card, side, config, turn)

    private fun presentCard(
        card: AnkiRenderedCard,
        side: AnkiCardSide = AnkiCardSide.QUESTION,
        config: AnkiCardRenderConfig = AnkiCardRenderConfig.DARK_APP,
        turn: ReviewTurnId = ReviewTurnId("instrumented:${card.ref.cardId}:1")
    ) {
        events.clear()
        links.clear()
        configState.value = config
        sideState.value = side
        turnState.value = turn
        cardState.value = card
        compose.waitForIdle()
    }

    private fun awaitReady(timeoutMillis: Long = RENDER_TIMEOUT_MS): AnkiRenderEvent.Ready {
        compose.waitUntil(timeoutMillis) { events.any { it.name == AnkiRenderEvent.Ready.NAME } }
        return events.filterIsInstance<AnkiRenderEvent.Ready>().last()
    }

    private fun awaitEvent(name: String, timeoutMillis: Long = RENDER_TIMEOUT_MS): AnkiRenderEvent {
        compose.waitUntil(timeoutMillis) { events.any { it.name == name } }
        return events.last { it.name == name }
    }

    private fun eventCount(name: String): Int = events.count { it.name == name }

    /** The state token the renderer publishes for accessibility (STEP 137). */
    private fun stateToken(): String? = compose.onNodeWithTag(AnkiCardRendererTags.RENDERER)
        .fetchSemanticsNode()
        .config
        .getOrNull(StateDescription)

    private fun accessibleName(): List<String> = compose.onNodeWithTag(AnkiCardRendererTags.RENDERER)
        .fetchSemanticsNode()
        .config
        .getOrNull(ContentDescription)
        .orEmpty()

    private fun bodyText(): String = dom("document.body.innerText")

    // ------------------------------------------------------------------ DOM and main-thread access

    private fun dom(script: String, timeoutMillis: Long = RENDER_TIMEOUT_MS): String {
        val webView = webViews.lastOrNull() ?: throw AssertionError("no WebView was created")
        val latch = CountDownLatch(1)
        val value = AtomicReference<String?>(null)
        onMain {
            webView.evaluateJavascript(
                "(function(){try{return String($script);}catch(e){return 'ERROR:'+e.name;}})()"
            ) { result ->
                value.set(result)
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out for '$script'", latch.await(timeoutMillis, TimeUnit.MILLISECONDS))
        return unquote(value.get())
    }

    private fun unquote(raw: String?): String {
        val text = raw ?: return "null"
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\\\", "\\")
        }
        return text
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    /** Reads a WebView property on the main thread and returns it, so assertions stay on the test thread. */
    private fun <T> onMainValue(block: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(block()) }
        return value.get()
    }

    private fun currentWebView(): WebView = webViews.lastOrNull() ?: throw AssertionError("no WebView was created")

    // ------------------------------------------------------------------ ORIGINAL rendering

    @Test
    fun original_question_renders_in_a_webview_and_reports_ready() {
        present(AnkiCardRenderFixtures.BASIC)

        val ready = awaitReady()
        assertEquals(AnkiRenderPresentation.ORIGINAL, ready.presentation)
        assertTrue("the WebView must have finished the document", ready.pageFinished)
        assertNotNull("a real load has a real duration", ready.loadDurationMs)

        compose.awaitNode(hasTestTag(AnkiCardRendererTags.WEBVIEW))
        compose.onNodeWithTag(AnkiCardRendererTags.FALLBACK).assertDoesNotExist()
        compose.onNodeWithTag(AnkiCardRendererTags.FAILURE).assertDoesNotExist()
        assertEquals(1, webViews.size)
        assertEquals("ready_original", stateToken())

        // The document Chromium received is the one the pure builder produced: our reserved origin, the
        // side marker, UTF-8 and exactly one document shell.
        assertEquals(RENDERER_BASE_URL, onMainValue { currentWebView().url })
        assertEquals("UTF-8", onMainValue { currentWebView().settings.defaultTextEncodingName })
        assertEquals("question", dom("document.body.getAttribute('data-studyagent-side')"))
        assertEquals("utf-8", dom("document.characterSet").lowercase())
        assertEquals("1", dom("document.querySelectorAll('html').length"))
        assertTrue(bodyText().contains("normal resting heart rate"))
        assertEquals("https://card.studyagent.invalid/", dom("document.baseURI"))
    }

    @Test
    fun answer_side_switch_keeps_one_webview_and_stays_inside_the_turn() {
        val fixture = AnkiCardRenderFixtures.BASIC
        present(fixture, AnkiCardSide.QUESTION)
        awaitReady()
        val turn = turnState.value

        present(fixture, AnkiCardSide.ANSWER, turn = turn)
        val ready = awaitReady()

        // STEP 30/§31, INV-ANKI-RENDER-05: the same turn, a new document, one WebView.
        assertEquals(1, webViews.size)
        assertEquals(turn, ready.request.turnId)
        assertEquals(AnkiCardSide.ANSWER, ready.request.side)
        assertEquals("answer", dom("document.body.getAttribute('data-studyagent-side')"))
        val text = bodyText()
        assertTrue(text.contains("60-100 beats per minute"))
        assertEquals(
            "the answer already contains the question, and must not be shown twice (INV-RENDER-12)",
            1,
            text.split("normal resting heart rate").size - 1
        )
        assertEquals("1", dom("document.querySelectorAll('hr#answer').length"))
    }

    @Test
    fun recomposition_neither_reloads_the_document_nor_recreates_the_webview() {
        present(AnkiCardRenderFixtures.HTML_FORMATTING)
        awaitReady()
        assertEquals(1, eventCount(AnkiRenderEvent.Started.NAME))

        // A parent-level change recomposes the renderer with byte-identical inputs (STEP 13/§34).
        repeat(3) { index ->
            parentPadding.value = (index + 1).dp
            compose.waitForIdle()
        }

        assertEquals("no second document was submitted", 1, eventCount(AnkiRenderEvent.Started.NAME))
        assertEquals("no second WebView was created", 1, webViews.size)
        assertEquals(1, eventCount(AnkiRenderEvent.Ready.NAME))
        assertEquals(0L, performance.snapshot().staleCallbacksIgnored)
        assertEquals("1", dom("document.querySelectorAll('html').length"))
    }

    @Test
    fun a_new_card_is_presented_on_the_same_webview() {
        present(AnkiCardRenderFixtures.TABLE, turn = ReviewTurnId("instrumented:table:1"))
        awaitReady()
        val firstText = bodyText()

        present(AnkiCardRenderFixtures.CLOZE, turn = ReviewTurnId("instrumented:cloze:1"))
        val ready = awaitReady()

        assertEquals("one WebView serves the whole review flow (INV-RENDER-22)", 1, webViews.size)
        assertEquals(2, eventCount(AnkiRenderEvent.Started.NAME))
        assertEquals(2L, performance.snapshot().documentsLoaded)
        assertTrue(ready.request.generation > 1L)
        assertFalse("the new card replaced the old one", bodyText() == firstText)
        assertEquals("a new document starts at the top (INV-RENDER-24)", "0", dom("window.scrollY"))
    }

    @Test
    fun the_whole_verified_deck_renders_on_one_bounded_webview() {
        val verified = AnkiCardRenderFixtures.gate08Verified
        assertEquals(24, verified.size)

        verified.forEachIndexed { index, fixture ->
            present(fixture, turn = ReviewTurnId("instrumented:deck:${fixture.id}:$index"))
            val ready = awaitReady()
            assertTrue(
                "'${fixture.id}' must present something, got ${ready.presentation}",
                ready.presentation == AnkiRenderPresentation.ORIGINAL ||
                    ready.presentation == AnkiRenderPresentation.CLEAN_FALLBACK
            )
            compose.onNodeWithTag(AnkiCardRendererTags.FAILURE).assertDoesNotExist()
        }

        assertEquals(1, webViews.size)
        assertEquals(1L, performance.snapshot().webViewsCreated)
        assertEquals(24L, performance.snapshot().documentsLoaded)
        assertTrue("the WebView count stays bounded", performance.webViewCountBounded)
        assertTrue("document load must have been measured", performance.snapshot().documentLoad.measured)
        assertEquals(0L, performance.snapshot().rendererProcessFailures)
    }

    // ------------------------------------------------------------------ CLEAN and fallbacks

    @Test
    fun clean_mode_never_creates_a_webview() {
        present(
            AnkiCardRenderFixtures.HTML_FORMATTING,
            config = AnkiCardRenderConfig.DARK_APP.copy(mode = AnkiCardRenderMode.CLEAN)
        )
        val ready = awaitReady()

        assertEquals(AnkiRenderPresentation.CLEAN, ready.presentation)
        assertEquals("CLEAN must not need Chromium", 0, webViews.size)
        compose.onNodeWithTag(AnkiCardRendererTags.FALLBACK).assertExists()
        compose.onNodeWithText(AnkiCardRenderFixtures.HTML_FORMATTING.card.questionText!!).assertExists()
        assertEquals("ready_clean_compose", stateToken())
    }

    @Test
    fun a_text_only_card_falls_back_to_compose_text_and_says_why() {
        present(AnkiCardRenderFixtures.TEXT_ONLY)
        val ready = awaitReady()

        assertEquals(AnkiRenderPresentation.CLEAN_FALLBACK, ready.presentation)
        val fallback = awaitEvent(AnkiRenderEvent.FallbackUsed.NAME) as AnkiRenderEvent.FallbackUsed
        assertEquals("card_html_unavailable", fallback.reason)
        compose.onNodeWithTag(AnkiCardRendererTags.FALLBACK).assertExists()
        compose.onNodeWithText(AnkiCardRenderFixtures.TEXT_ONLY.card.questionText!!).assertExists()
        assertEquals("ready_clean_fallback_compose", stateToken())
        assertEquals(1L, performance.snapshot().fallbacksUsed)
    }

    @Test
    fun an_answer_side_without_content_says_so_instead_of_showing_a_blank_surface() {
        present(AnkiCardRenderFixtures.ANSWER_WITHOUT_CONTENT, AnkiCardSide.ANSWER)
        val failed = awaitEvent(AnkiRenderEvent.Failed.NAME) as AnkiRenderEvent.Failed

        assertTrue(failed.failure is AnkiRenderFailure.HtmlUnavailable)
        assertFalse("no fallback text exists to show", failed.fallbackShown)
        compose.awaitNode(hasTestTag(AnkiCardRendererTags.FAILURE))
        compose.onNodeWithText("No displayable content for this card side").assertExists()
        // INV-ANKI-RENDER-19: a card that cannot be displayed is still due and unrated, and the notice
        // says so in those words.
        compose.onNodeWithText("still due", substring = true).assertExists()
    }

    @Test
    fun a_card_without_a_webview_provider_still_shows_its_text() {
        // The factory seam returns null: exactly what a device with a broken WebView provider does.
        events.clear()
        compose.setContent {
            StudyAgentTheme {
                AnkiCardRenderer(
                    card = AnkiCardRenderFixtures.BASIC.card,
                    turnId = ReviewTurnId("instrumented:no-webview:1"),
                    side = AnkiCardSide.QUESTION,
                    onRenderEvent = { events += it },
                    createWebView = { null },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        val ready = awaitReady()

        assertEquals(AnkiRenderPresentation.CLEAN_FALLBACK, ready.presentation)
        val unavailable = awaitEvent(AnkiRenderEvent.SurfaceUnavailable.NAME) as AnkiRenderEvent.SurfaceUnavailable
        assertEquals(AnkiRenderFailure.WebViewUnavailable.CATEGORY_CREATION_FAILED, unavailable.category)
        compose.onNodeWithTag(AnkiCardRendererTags.FALLBACK).assertExists()
        compose.onNodeWithTag(AnkiCardRendererTags.WEBVIEW).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ JavaScript policy

    @Test
    fun card_javascript_runs_under_the_template_policy() {
        present(AnkiCardRenderFixtures.JS_ON_LOAD)
        awaitReady()

        assertEquals(
            "the card's own script must have run (INV-RENDER-02)",
            "computed-by-card-script",
            dom("document.getElementById('computed').textContent")
        )
        assertTrue(onMainValue { currentWebView().settings.javaScriptEnabled })
        assertEquals(0L, performance.javascriptErrorCount)
    }

    @Test
    fun card_javascript_does_not_run_when_the_policy_disables_it() {
        present(
            AnkiCardRenderFixtures.JS_ON_LOAD,
            config = AnkiCardRenderConfig.DARK_APP.copy(javascriptPolicy = AnkiJavascriptPolicy.DISABLED)
        )
        val ready = awaitReady()

        // With JavaScript disabled the page cannot be queried through evaluateJavascript either, so the
        // observable facts are the setting itself and the fact that the HTML/CSS still rendered
        // (STEP 111): a disabled script is an inert node in the DOM, not a reason to fall back.
        assertFalse(onMainValue { currentWebView().settings.javaScriptEnabled })
        assertEquals(AnkiRenderPresentation.ORIGINAL, ready.presentation)
        assertTrue(ready.pageFinished)
        assertEquals(0L, performance.javascriptErrorCount)
        assertEquals(0, eventCount(AnkiRenderEvent.FallbackUsed.NAME))
    }

    @Test
    fun an_interactive_card_widget_works_inside_the_page() {
        present(AnkiCardRenderFixtures.JS_ON_CLICK)
        awaitReady()

        assertEquals("none", dom("document.getElementById('hint').style.display"))
        assertEquals("Show hint", dom("document.getElementById('toggle').textContent"))

        // The card's own handler, invoked the way a tap invokes it — no native help of any kind.
        dom("document.getElementById('toggle').click()")
        compose.waitForIdle()
        assertEquals("block", dom("document.getElementById('hint').style.display"))
        assertEquals("Hide hint", dom("document.getElementById('toggle').textContent"))
    }

    @Test
    fun a_card_written_against_the_ankidroid_api_survives_without_it() {
        present(AnkiCardRenderFixtures.JS_ANKIDROID_API)
        val ready = awaitReady()

        // STEP 51, INV-ANKI-RENDER-10: the API is absent by design, the card's own catch handles it, the
        // card still displays and the app does not crash. The exception is a renderer diagnostic, not a
        // render failure (STEP 54).
        assertEquals("api-absent", dom("document.getElementById('api').textContent"))
        assertEquals("undefined", dom("typeof AnkiDroidJSAPI"))
        assertEquals(AnkiRenderPresentation.ORIGINAL, ready.presentation)
        assertEquals(0, eventCount(AnkiRenderEvent.Failed.NAME))
    }

    @Test
    fun no_native_bridge_and_no_device_capability_is_reachable_from_a_card() {
        present(AnkiCardRenderFixtures.JS_ON_LOAD)
        awaitReady()

        // INV-ANKI-RENDER-08/§09: addJavascriptInterface is never called, so there is nothing to find.
        listOf("AnkiDroidJSAPI", "AnkiDroid", "StudyAgent", "StudyAgentBridge", "Android", "nativeBridge")
            .forEach { name ->
                assertEquals("window.$name must not exist", "undefined", dom("typeof window.$name"))
            }

        val settings = onMainValue {
            currentWebView().settings.let {
                Settings(
                    javaScriptCanOpenWindows = it.javaScriptCanOpenWindowsAutomatically,
                    allowFileAccess = it.allowFileAccess,
                    allowContentAccess = it.allowContentAccess,
                    domStorage = it.domStorageEnabled,
                    database = it.databaseEnabled,
                    blockNetworkImages = it.blockNetworkImageLoads,
                    mediaNeedsGesture = it.mediaPlaybackRequiresUserGesture,
                    wideViewPort = it.useWideViewPort,
                    overviewMode = it.loadWithOverviewMode,
                    supportZoom = it.supportZoom(),
                    builtInZoom = it.builtInZoomControls,
                    actionModeMenuItems = it.disabledActionModeMenuItems,
                    encoding = it.defaultTextEncodingName
                )
            }
        }
        // INV-ANKI-RENDER-09/§30: the hardened configuration the gate specifies, read back from the
        // platform rather than trusted from the source.
        assertFalse(settings.javaScriptCanOpenWindows)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.domStorage)
        assertFalse(settings.database)
        assertTrue(settings.blockNetworkImages)
        assertTrue(settings.mediaNeedsGesture)
        assertFalse(settings.wideViewPort)
        assertFalse(settings.overviewMode)
        assertFalse(settings.supportZoom)
        assertFalse(settings.builtInZoom)
        assertEquals("UTF-8", settings.encoding)

        // STEP 141/§142 — selection stays available (a reviewer must be able to copy a dose, a gene name
        // or an Arabic term out of a card) but its menu is bounded: "Web search" is switched off so
        // selected card text cannot be handed to a third-party search engine. Long press is routed to
        // that selection, and `registerForContextMenu` is never called, so the generic browser menu
        // (open in new tab, download image, save link as) does not exist and the link policy stays the
        // card's only navigation path (INV-ANKI-RENDER-16).
        assertEquals(
            "web search must be the disabled action-mode item (STEP 141)",
            WebSettings.MENU_ITEM_WEB_SEARCH,
            settings.actionModeMenuItems
        )
        assertTrue(
            "long press must reach text selection (STEP 142)",
            onMainValue { currentWebView().isLongClickable }
        )
    }

    private data class Settings(
        val javaScriptCanOpenWindows: Boolean,
        val allowFileAccess: Boolean,
        val allowContentAccess: Boolean,
        val domStorage: Boolean,
        val database: Boolean,
        val blockNetworkImages: Boolean,
        val mediaNeedsGesture: Boolean,
        val wideViewPort: Boolean,
        val overviewMode: Boolean,
        val supportZoom: Boolean,
        val builtInZoom: Boolean,
        val actionModeMenuItems: Int,
        val encoding: String
    )

    @Test
    fun a_card_script_cannot_open_a_dialog() {
        presentCard(
            testCard("dialog", "<p id=\"state\">before</p><script>alert('card');</script>"),
            turn = ReviewTurnId("instrumented:dialog:1")
        )
        val ready = awaitReady()

        val suppressed = awaitEvent(AnkiRenderEvent.JavascriptDialogSuppressed.NAME)
            as AnkiRenderEvent.JavascriptDialogSuppressed
        assertEquals("alert", suppressed.kind)
        assertEquals(AnkiRenderPresentation.ORIGINAL, ready.presentation)
        // The card behind the suppressed dialog is still there and still readable: a dialog must not
        // block the review UI (STEP 56/§57).
        assertEquals("before", dom("document.getElementById('state').textContent"))
    }

    // ------------------------------------------------------------------ direction, text and layout

    @Test
    fun arabic_cards_render_right_to_left() {
        present(AnkiCardRenderFixtures.ARABIC_RTL)
        awaitReady()
        assertEquals("rtl", dom("getComputedStyle(document.body).direction"))
        assertTrue(bodyText().contains("معدل ضربات القلب"))

        // No authored direction at all: `dir="auto"` must resolve it from the content (STEP 35/§37,
        // INV-RENDER-14 — the card's own direction is never silently overridden), which is the
        // assertion no JVM test can make.
        present(AnkiCardRenderFixtures.ARABIC_NO_DIR, turn = ReviewTurnId("instrumented:arabic_no_dir:1"))
        awaitReady()
        assertEquals("auto", dom("document.documentElement.dir"))
        assertEquals("rtl", dom("getComputedStyle(document.body).direction"))
    }

    @Test
    fun mixed_arabic_and_english_keeps_both_directions() {
        present(AnkiCardRenderFixtures.MIXED_ARABIC_ENGLISH, AnkiCardSide.ANSWER)
        awaitReady()

        val text = bodyText()
        assertTrue(text.contains("STEMI"))
        assertTrue(text.contains("مريض"))
        assertTrue(text.contains("RCA"))
        assertEquals("rtl", dom("getComputedStyle(document.body).direction"))
        assertEquals("ltr", dom("getComputedStyle(document.querySelector('span[dir=ltr]')).direction"))
    }

    @Test
    fun unicode_diacritics_and_emoji_survive_utf8_loading() {
        present(AnkiCardRenderFixtures.UNICODE_SYMBOLS)
        awaitReady()
        val symbols = bodyText()
        listOf("α", "β", "γ", "Δ", "μ", "≤", "≥", "→").forEach { character ->
            assertTrue("'$character' must survive (INV-RENDER-27)", symbols.contains(character))
        }

        // The answer side carries the entity test and Arabic diacritics.
        present(
            AnkiCardRenderFixtures.UNICODE_SYMBOLS,
            AnkiCardSide.ANSWER,
            turn = ReviewTurnId("instrumented:unicode_symbols:1")
        )
        awaitReady()
        val answer = bodyText()
        assertTrue("Arabic diacritics must survive", answer.contains("الْعِلْمُ نُورٌ"))
        // STEP 81: `&amp;amp;` renders as the literal text "&amp;", never as "&".
        assertTrue("entities must not be double-decoded", answer.contains("&amp;"))
        assertFalse(answer.contains("&amp;amp;"))

        present(AnkiCardRenderFixtures.EMOJI, turn = ReviewTurnId("instrumented:emoji:1"))
        awaitReady()
        val emoji = bodyText()
        listOf("🧠", "😀", "🩺", "💊", "🏥").forEach { character ->
            assertTrue("'$character' must survive", emoji.contains(character))
        }
    }

    @Test
    fun a_wide_table_scrolls_inside_the_webview_instead_of_breaking_the_layout() {
        present(AnkiCardRenderFixtures.WIDE_TABLE)
        awaitReady()

        assertEquals("12", dom("document.querySelectorAll('thead th').length"))
        assertEquals("96", dom("document.querySelectorAll('tbody td').length"))
        val scrollWidth = dom("document.documentElement.scrollWidth").toInt()
        val innerWidth = dom("window.innerWidth").toInt()
        assertTrue("the table must be wider than the viewport ($scrollWidth vs $innerWidth)", scrollWidth > innerWidth)
        assertEquals("nowrap", dom("getComputedStyle(document.querySelector('table')).whiteSpace"))
        // Horizontal scrolling is the WebView's own job, so the Compose hierarchy is never asked for
        // infinite width (STEP 63/§64).
        assertTrue(onMainValue { currentWebView().isHorizontalScrollBarEnabled })
    }

    @Test
    fun a_long_card_scrolls_and_never_truncates() {
        present(AnkiCardRenderFixtures.LONG_ANSWER, AnkiCardSide.ANSWER)
        awaitReady()

        val scrollHeight = dom("document.documentElement.scrollHeight").toInt()
        val innerHeight = dom("window.innerHeight").toInt()
        assertTrue("the document must be taller than the viewport", scrollHeight > innerHeight)
        assertTrue("all paragraphs are in the DOM", dom("document.querySelectorAll('p').length").toInt() >= 20)

        // Scrolling belongs to the WebView…
        dom("window.scrollTo(0, 400)")
        compose.waitForIdle()
        assertTrue(dom("window.scrollY").toInt() > 0)

        // …and a new document resets it, so the previous card's offset cannot leak (INV-RENDER-24).
        present(AnkiCardRenderFixtures.LONG_QUESTION, turn = ReviewTurnId("instrumented:long_question:1"))
        awaitReady()
        assertEquals("0", dom("window.scrollY"))
    }

    // ------------------------------------------------------------------ CSS, night mode, zoom

    @Test
    fun card_css_beats_the_renderer_base_css() {
        present(AnkiCardRenderFixtures.CSS_CARD_CLASS)
        awaitReady()

        // STEP 24/§40: the renderer's stylesheet comes first, so the card's own rules win.
        assertEquals("22px", dom("getComputedStyle(document.body).fontSize"))
        assertEquals("center", dom("getComputedStyle(document.body).textAlign"))
        assertTrue(dom("getComputedStyle(document.body).backgroundColor").contains("255, 248, 231"))
        assertEquals("2px", dom("getComputedStyle(document.getElementById('unique')).letterSpacing"))
        // The `.card` semantics a template relies on are intact (STEP 25).
        assertEquals("true", dom("document.body.classList.contains('card')"))
        assertTrue(dom("getComputedStyle(document.querySelector('.hint')).fontSize").endsWith("px"))
        assertEquals("24px", dom("getComputedStyle(document.body).padding"))
    }

    @Test
    fun night_mode_adds_the_anki_classes_without_inverting_anything() {
        present(AnkiCardRenderFixtures.DARK_AUTHORED_CARD)
        awaitReady()

        assertEquals("true", dom("document.body.classList.contains('night_mode')"))
        assertEquals("true", dom("document.body.classList.contains('nightMode')"))

        val scheme = dom("getComputedStyle(document.documentElement).colorScheme")
        assumeTrue("this WebView does not expose a computed color-scheme ('$scheme')", scheme.isNotBlank())
        assertTrue("color-scheme must be dark, got '$scheme'", scheme.contains("dark"))

        // INV-ANKI-RENDER-15: no blanket inversion anywhere, and an authored dark card keeps its own
        // colors instead of being flipped into a light one (STEP 42).
        assertEquals("none", dom("getComputedStyle(document.body).filter"))
        assertEquals("none", dom("getComputedStyle(document.documentElement).filter"))
        assertTrue(dom("getComputedStyle(document.body).backgroundColor").contains("16, 20, 24"))
        assertTrue(dom("getComputedStyle(document.querySelector('.accent')).color").contains("79, 209, 197"))
        assertTrue(bodyText().contains("S3 gallop"))
    }

    @Test
    fun a_light_authored_card_is_not_darkened_by_the_app_theme() {
        present(AnkiCardRenderFixtures.LIGHT_AUTHORED_CARD)
        awaitReady()

        // STEP 40/§41: a card authored white stays white; the renderer adds no color of its own beyond
        // the scheme-following system colors, which the card's rule overrides.
        assertTrue(dom("getComputedStyle(document.body).backgroundColor").contains("255, 255, 255"))
        assertTrue(dom("getComputedStyle(document.body).color").contains("0, 0, 0"))
        assertTrue(bodyText().contains("four lobes of the liver"))
    }

    @Test
    fun text_zoom_scales_the_page_without_rewriting_card_css() {
        present(
            AnkiCardRenderFixtures.CSS_CARD_CLASS,
            config = AnkiCardRenderConfig.DARK_APP.copy(textScale = 1.5f)
        )
        awaitReady()

        // STEP 38/§39: scaling is the platform's textZoom, so not one `font-size` in the card CSS is
        // rewritten and a template's relative sizing survives.
        assertEquals(150, onMainValue { currentWebView().settings.textZoom })
        assertTrue(dom("document.querySelector('style').textContent").contains("font-size: 22px"))
        assertTrue(dom("getComputedStyle(document.body).fontSize").endsWith("px"))
    }

    @Test
    fun a_complete_document_is_passed_through_and_not_double_wrapped() {
        present(AnkiCardRenderFixtures.FULL_DOCUMENT)
        awaitReady()

        assertEquals("1", dom("document.querySelectorAll('html').length"))
        assertEquals("1", dom("document.querySelectorAll('body').length"))
        // The authored document's own attributes survive, which is the whole point of the passthrough.
        assertEquals("rtl", dom("document.documentElement.dir"))
        assertEquals("ar", dom("document.documentElement.lang"))
        assertEquals("24px", dom("getComputedStyle(document.body).fontSize"))
        assertTrue(bodyText().contains("سؤال كامل المستند"))
    }

    // ------------------------------------------------------------------ links

    @Test
    fun a_card_link_is_mediated_outward_and_the_card_stays_put() {
        present(AnkiCardRenderFixtures.LINKS)
        awaitReady()

        // Ask for the navigation the way a tap would. The policy mediates it upward and the WebView does
        // not navigate, so the reviewer never becomes a browser (STEP 45/§48, INV-RENDER-16).
        dom("window.location.href = document.querySelector('a[href^=\"https\"]').href")
        val mediated = awaitEvent(AnkiRenderEvent.ExternalLinkMediated.NAME)
            as AnkiRenderEvent.ExternalLinkMediated
        assertEquals("https", mediated.scheme)
        assertEquals(1, links.size)
        assertTrue(links.single().url.startsWith("https://"))
        assertEquals(AnkiLinkDecision.OPEN_EXTERNALLY, links.single().classification.decision)
        assertEquals(1L, performance.snapshot().externalLinksMediated)

        compose.waitForIdle()
        assertEquals("the card is still the card", RENDERER_BASE_URL, onMainValue { currentWebView().url })
        assertTrue(bodyText().isNotEmpty())
        assertEquals(1, eventCount(AnkiRenderEvent.Ready.NAME))
    }

    @Test
    fun unknown_and_local_schemes_are_blocked_before_any_intent_exists() {
        present(AnkiCardRenderFixtures.LINKS)
        awaitReady()

        dom("window.location.href = 'mailto:someone@example.com'")
        awaitEvent(AnkiRenderEvent.ExternalLinkBlocked.NAME)
        dom("window.location.href = 'studyagent-custom://open'")
        compose.waitUntil(10_000) { eventCount(AnkiRenderEvent.ExternalLinkBlocked.NAME) >= 2 }

        val blocked = events.filterIsInstance<AnkiRenderEvent.ExternalLinkBlocked>()
        assertEquals(listOf("mailto", "studyagent-custom"), blocked.map { it.scheme })
        assertTrue("nothing is handed to the platform", links.isEmpty())
        assertEquals(2L, performance.snapshot().externalLinksBlocked)
        assertTrue("the card survived both attempts", bodyText().isNotEmpty())
        assertEquals(RENDERER_BASE_URL, onMainValue { currentWebView().url })
    }

    @Test
    fun the_external_link_handler_refuses_everything_that_is_not_a_web_link() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = AnkiRenderRequestId(
            turnId = ReviewTurnId("instrumented:links:1"),
            cardRef = AnkiCardRenderFixtures.LINKS.card.ref,
            side = AnkiCardSide.QUESTION,
            generation = 1L
        )

        // A blocked classification is refused on policy grounds, before the scheme is even looked at.
        listOf("javascript:alert(1)", "file:///sdcard/x.html", "intent://scan#Intent;end", "market://details?id=x")
            .forEach { url ->
                val classification = AnkiCardLinkPolicy.classify(url)
                assertEquals(AnkiLinkDecision.BLOCKED, classification.decision)
                assertEquals(
                    AnkiExternalLinkResult.Refused("policy"),
                    AnkiExternalLinkHandler.open(context, AnkiExternalLinkRequest(url, request, classification))
                )
            }

        // Defence in depth (STEP 46): even a classification that claims OPEN_EXTERNALLY is re-checked
        // here, so a non-web scheme can never be launched because one classifier was wrong.
        assertEquals(
            AnkiExternalLinkResult.Refused("scheme"),
            AnkiExternalLinkHandler.open(
                context,
                AnkiExternalLinkRequest(
                    "mailto:someone@example.com",
                    request,
                    AnkiLinkClassification(AnkiLinkDecision.OPEN_EXTERNALLY, "mailto", "test")
                )
            )
        )
        assertTrue(
            AnkiExternalLinkHandler.open(
                context,
                AnkiExternalLinkRequest(
                    "https://",
                    request,
                    AnkiLinkClassification(AnkiLinkDecision.OPEN_EXTERNALLY, "https", "test")
                )
            ) is AnkiExternalLinkResult.Refused
        )
        // Opening a real web link is deliberately NOT asserted here: it would leave the app under test.
        // That path is in the real-device matrix (docs/REAL_DEVICE_TEST_MATRIX.md).
    }

    // ------------------------------------------------------------------ accessibility

    @Test
    fun the_card_surface_is_labelled_and_its_state_is_exposed() {
        present(AnkiCardRenderFixtures.BASIC)
        awaitReady()

        assertTrue(accessibleName().any { it.contains("Anki card question") })
        assertEquals("ready_original", stateToken())

        present(AnkiCardRenderFixtures.BASIC, AnkiCardSide.ANSWER, turn = turnState.value)
        awaitReady()
        assertTrue(accessibleName().any { it.contains("Anki card answer") })
        // STEP 137: the label changes with the side, and the WebView's own accessibility tree is left
        // intact underneath — nothing is merged away.
        assertFalse(accessibleName().any { it.contains("rendered card is unavailable") })
    }

    @Test
    fun a_degraded_surface_is_announced_as_degraded() {
        present(AnkiCardRenderFixtures.TEXT_ONLY)
        awaitReady()
        assertTrue(accessibleName().any { it.contains("rendered card is unavailable") })
        assertEquals("ready_clean_fallback_compose", stateToken())
    }

    // ------------------------------------------------------------------ recovery and ownership

    @Test
    fun a_dead_renderer_process_recovers_on_a_new_webview_with_the_same_turn() {
        // Driven at the host level, because Chromium's renderer cannot be killed on demand from a test.
        // This is the same code path `onRenderProcessGone` runs, and it decides whether a card survives
        // a Chromium crash (STEP 71-§73, INV-ANKI-RENDER-05).
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = CopyOnWriteArrayList<AnkiRenderEvent>()
        val controller = AnkiCardRenderController(performance = performance, onEvent = { recorder += it })
        val fixture = AnkiCardRenderFixtures.BASIC
        val turn = ReviewTurnId("instrumented:recovery:1")

        val first = createAnkiCardWebView(context) ?: throw AssertionError("no WebView provider")
        val invalidated = AtomicInteger(0)
        val firstHost = AnkiCardWebViewHost(
            webView = first,
            controller = controller,
            performance = performance,
            onSurfaceInvalidated = { invalidated.incrementAndGet() }
        )
        onMain {
            controller.attachSurface(firstHost)
            controller.submit(fixture.card, turn, AnkiCardSide.ANSWER)
        }
        val deadRequest = controller.activeRequest ?: throw AssertionError("no active request")
        assertEquals(AnkiCardSide.ANSWER, deadRequest.side)

        onMain { controller.onRendererProcessGone(deadRequest, didCrash = true) }

        val failed = controller.state.value as AnkiRenderState.Failed
        assertTrue(failed.failure is AnkiRenderFailure.RendererProcessGone)
        assertEquals("renderer_process_crashed", failed.failure.token)
        assertEquals(AnkiRenderPresentation.CLEAN_FALLBACK, failed.presentation)
        assertEquals(turn, failed.request.turnId)
        assertEquals(1, invalidated.get())
        assertEquals(1, recorder.count { it.name == AnkiRenderEvent.RendererProcessGone.NAME })

        // The composition would now create a replacement; the controller re-presents the same target.
        val second = createAnkiCardWebView(context) ?: throw AssertionError("no WebView provider")
        assertFalse("the replacement must be a different instance", second === first)
        val secondHost = AnkiCardWebViewHost(second, controller, performance)
        onMain { controller.attachSurface(secondHost) }

        val recovered = controller.activeRequest ?: throw AssertionError("no active request after recovery")
        assertEquals(turn, recovered.turnId)
        assertEquals(deadRequest.cardRef, recovered.cardRef)
        assertEquals(AnkiCardSide.ANSWER, recovered.side)
        assertTrue(recovered.generation > deadRequest.generation)
        assertEquals(1L, performance.snapshot().rendererProcessFailures)

        onMain {
            firstHost.release()
            secondHost.release()
            controller.dispose()
        }
        assertEquals(AnkiRenderState.Idle, controller.state.value)
        assertEquals(2L, performance.snapshot().webViewsReleased)
    }

    @Test
    fun releasing_a_surface_destroys_the_webview_and_refuses_further_work() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val webView = createAnkiCardWebView(context) ?: throw AssertionError("no WebView provider")
        val controller = AnkiCardRenderController(performance = performance)
        val host = AnkiCardWebViewHost(webView, controller, performance)

        onMain {
            controller.attachSurface(host)
            controller.submit(
                AnkiCardRenderFixtures.BASIC.card,
                ReviewTurnId("instrumented:release:1"),
                AnkiCardSide.QUESTION
            )
        }
        assertTrue(host.isUsable)

        onMain { host.release() }
        assertFalse("a released surface must refuse further work", host.isUsable)

        // Idempotent: a double release must not destroy twice or throw (STEP 14/§15).
        onMain { host.release() }
        assertEquals(1L, performance.snapshot().webViewsReleased)
        assertTrue(performance.webViewCountBounded)

        onMain { controller.dispose() }
        assertEquals(AnkiRenderState.Idle, controller.state.value)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A card that is not part of the shared deck, for the one behaviour the deck does not cover: a
     * script that opens a modal dialog (STEP 56/§57).
     */
    private fun testCard(id: String, questionHtml: String): AnkiRenderedCard {
        val backend = AnkiBackendId.Fake("render-instrumented")
        val noteRef = AnkiNoteRef(backend, "note-$id", INSTRUMENTED_COLLECTION)
        return AnkiRenderedCard(
            ref = AnkiCardRef(backend, id, noteRef.noteId, 0, INSTRUMENTED_COLLECTION),
            questionHtml = questionHtml,
            answerHtml = "$questionHtml<hr id=answer><p>answer</p>",
            questionText = "instrumented question",
            answerText = "instrumented answer",
            pureAnswerText = "instrumented answer",
            noteRef = noteRef
        )
    }

    private companion object {
        /** A real document load on a real device: generous, because a cold WebView is slow. */
        const val RENDER_TIMEOUT_MS: Long = 20_000L

        /** The renderer's reserved origin (STEP 78), spelled out so a test never derives it. */
        const val RENDERER_BASE_URL: String = "https://card.studyagent.invalid/"

        const val INSTRUMENTED_COLLECTION: String = "instrumented-collection"
    }
}
