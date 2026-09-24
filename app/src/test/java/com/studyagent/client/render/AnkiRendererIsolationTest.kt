package com.studyagent.client.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 08 PART VI — the renderer's boundary, enforced as a test instead of a convention.
 *
 * The behavioural suites prove the renderer *works*; this proves it is *contained*. The regressions it
 * exists to catch are the ones that are invisible in review and fatal in production:
 *
 * - a JavaScript bridge appearing "to make one card work" (INV-ANKI-RENDER-08/§09/§10);
 * - the renderer reaching for a scheduler, a rating, TTS, STT, the AI agent or the AnkiDroid gateway
 *   (INV-ANKI-RENDER-01/§03/§04);
 * - Android or Compose types creeping into `core/render`, which would make the decision core
 *   untestable on a JVM and therefore untested (INV-ANKI-RENDER-11);
 * - forced-dark or an inversion filter "for night mode" (INV-ANKI-RENDER-15);
 * - a WebView loaded from AnkiDroid's assets or any `file:`/`content:` origin (INV-ANKI-RENDER-13/§14);
 * - `handler.proceed()` on a TLS error (INV-ANKI-RENDER-17).
 *
 * Like [com.studyagent.client.anki.ankidroid.AnkiDroidIntegrationIsolationTest], this scans *main*
 * sources. Test sources are allowed to name forbidden things — that is how they assert absence.
 */
class AnkiRendererIsolationTest {

    private val appModuleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstNotNullOfOrNull { dir ->
            File(dir, "src/main/java/com/studyagent/client").takeIf { it.isDirectory }?.let { dir }
                ?: File(dir, "app/src/main/java/com/studyagent/client")
                    .takeIf { it.isDirectory }?.let { File(dir, "app") }
        }
        ?: error("could not locate the app module from ${File("").absolutePath}")

    private fun sources(relativePath: String): List<File> = File(appModuleDir, relativePath)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /** The pure decision core: planner, document builder, state, policy, controller. */
    private val coreRender: List<File> = sources("src/main/java/com/studyagent/client/core/render")

    /** The Compose/Android surface: WebView host, fallback view, link handler, diagnostics, entry point. */
    private val uiRender: List<File> = sources("src/main/java/com/studyagent/client/ui/components/anki")

    private val renderer: List<File> = coreRender + uiRender

    private val webViewHost: File = File(appModuleDir, "src/main/java/com/studyagent/client/ui/components/anki/AnkiCardWebView.kt")

    private val fixtureDeck: File =
        File(appModuleDir, "src/debug/java/com/studyagent/client/ui/components/anki/AnkiCardRenderFixtures.kt")

    // ---------------------------------------------------------------- helpers

    /** Source with comments stripped: the scans are about what code does, not about provenance notes. */
    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)(?<!:)//.*$"), " ")

    private fun File.imports(): List<String> = readLines().filter { it.startsWith("import ") }

    private fun offenders(token: String, files: List<File> = renderer): List<String> =
        files.filter { it.code().contains(token) }.map { it.name }

    private fun assertAbsent(token: String, files: List<File> = renderer, because: String = "") {
        assertEquals(
            "forbidden token '$token' found in ${offenders(token, files)}${if (because.isEmpty()) "" else " — $because"}",
            emptyList<String>(),
            offenders(token, files)
        )
    }

    @Test
    fun `the renderer exists where the architecture says it does`() {
        assertTrue("core/render must exist", coreRender.isNotEmpty())
        assertTrue("ui/components/anki must exist", uiRender.isNotEmpty())
        assertTrue("the WebView host must exist", webViewHost.isFile)
        assertTrue("the debug fixture deck must exist", fixtureDeck.isFile)
        // The fixture deck is debug-only: a release build ships no card content of our invention.
        assertFalse(
            "fixtures must not be a main-source file",
            File(appModuleDir, "src/main/java/com/studyagent/client/ui/components/anki/AnkiCardRenderFixtures.kt").exists()
        )
        assertTrue(coreRender.size >= 14)
        assertTrue(uiRender.size >= 5)
    }

    // ---------------------------------------------------------------- core purity (INV-RENDER-11)

    @Test
    fun `the render core imports no Android or Compose type`() {
        coreRender.forEach { file ->
            file.imports().forEach { import ->
                assertFalse(
                    "${file.name} must stay JVM-pure but has '$import'",
                    import.startsWith("import android.") ||
                        import.startsWith("import androidx.") ||
                        import.startsWith("import com.studyagent.client.ui.") ||
                        import.startsWith("import com.studyagent.client.data.") ||
                        import.startsWith("import com.studyagent.client.BuildConfig")
                )
            }
        }
    }

    @Test
    fun `the render core imports no calendar or time-zone type`() {
        // The renderer measures durations through AppClock; it never reasons about dates (the rule the
        // Anki domain layer already lives by).
        listOf("java.time", "java.util.Calendar", "java.util.Date", "java.util.TimeZone", "SimpleDateFormat")
            .forEach { assertAbsent(it, coreRender) }
    }

    @Test
    fun `the render core is allowed exactly the JDK and kotlinx surface it needs`() {
        val allowed = setOf(
            "java.net.URI",
            "java.util.concurrent.atomic.AtomicLong",
            "kotlin.math.roundToInt",
            "kotlinx.coroutines.flow.MutableStateFlow",
            "kotlinx.coroutines.flow.StateFlow",
            "kotlinx.coroutines.flow.asStateFlow"
        )
        coreRender.flatMap { it.imports() }
            .map { it.removePrefix("import ") }
            .filter { !it.startsWith("com.studyagent.client.") }
            .distinct()
            .forEach { import ->
                assertTrue(
                    "'$import' is not part of the render core's allowed non-project surface ($allowed)",
                    import in allowed
                )
            }
    }

    // ---------------------------------------------------------------- no bridge (INV-RENDER-08/§09/§10)

    @Test
    fun `no renderer file exposes a JavaScript bridge`() {
        listOf(
            "addJavascriptInterface",
            "@JavascriptInterface",
            "JavascriptInterface",
            "AnkiDroidJSAPI",
            "AnkiDroidJsAPI",
            "evaluateJavascript",
            "postWebMessage",
            "WebMessagePort",
            "createWebMessageChannel",
            "window.studyAgent",
            "window.StudyAgent",
            "promptResult.stringResult"
        ).forEach { assertAbsent(it) }
    }

    @Test
    fun `the WebView host never injects script into a card`() {
        val host = webViewHost.code()
        listOf("evaluateJavascript", "loadUrl(\"javascript:", "injectJavaScript", "WebViewCompat.addWebMessageListener")
            .forEach { token ->
                assertFalse("the host must not run '$token'", host.contains(token))
            }
        // Documents arrive through one API only, so the base URL and encoding are always the pinned ones.
        assertTrue(host.contains("loadDataWithBaseURL"))
        assertFalse("no loadUrl-based document loading", Regex("loadUrl\\((?!\\))").containsMatchIn(host))
    }

    // ---------------------------------------------------------------- no scheduler / gateway (INV-RENDER-01/§03/§04)

    @Test
    fun `no renderer file speaks scheduling, rating or session vocabulary`() {
        listOf(
            "nextCard",
            "beginReview",
            "commitRating",
            "answerCard",
            "buryCard",
            "suspendCard",
            "StudySessionMachine",
            "StudySessionState",
            "SessionScheduler",
            "AnkiScheduler",
            "ReviewScheduler",
            "\"schedule\"",
            "\"button_count\"",
            "\"next_review_times\"",
            "\"media_files\"",
            "\"deckID\"",
            "\"note_id\"",
            "EaseButton",
            "ratingButtons"
        ).forEach { assertAbsent(it) }
    }

    @Test
    fun `no renderer file reaches a backend, gateway or the AI and voice subsystems`() {
        listOf(
            "com.ichi2",
            "ContentProviderClient",
            "ContentResolver",
            "contentResolver",
            "resolveContentProvider",
            "READ_WRITE_DATABASE",
            "AnkiDroidGateway",
            "AnkiBackendGateway",
            "PcAgentGateway",
            "AgentClient",
            "AgentWebSocket",
            "TtsCoordinator",
            "TextToSpeech",
            "SpeechRecognizer",
            "AudioRecord",
            "StudyAgentViewModel"
        ).forEach { assertAbsent(it) }
    }

    @Test
    fun `the renderer never writes to a collection`() {
        listOf(".update(", ".insert(", ".delete(", ".applyBatch(", ".commit(", "ContentValues").forEach {
            assertAbsent(it)
        }
    }

    @Test
    fun `the renderer imports only presentation-adjacent project packages`() {
        val allowedPrefixes = listOf(
            "com.studyagent.client.core.anki.",
            "com.studyagent.client.core.common.",
            "com.studyagent.client.core.diagnostics.",
            "com.studyagent.client.core.render.",
            "com.studyagent.client.ui.",
            "com.studyagent.client.BuildConfig"
        )
        renderer.flatMap { it.imports() }
            .map { it.removePrefix("import ") }
            .filter { it.startsWith("com.studyagent.client.") }
            .distinct()
            .forEach { import ->
                assertTrue(
                    "'$import' is outside the renderer's allowed project surface",
                    allowedPrefixes.any { import.startsWith(it) }
                )
            }
    }

    // ---------------------------------------------------------------- night mode (INV-RENDER-15)

    @Test
    fun `night mode is never a forced inversion`() {
        listOf(
            "setForceDark",
            "FORCE_DARK",
            "algorithmicDarkeningAllowed",
            "ALGORITHMIC_DARKENING",
            "invert(",
            "filter:invert",
            "-webkit-filter"
        ).forEach { assertAbsent(it) }
    }

    @Test
    fun `the document builder expresses night mode as a scheme and a class`() {
        val builder = File(appModuleDir, "src/main/java/com/studyagent/client/core/render/AnkiCardDocument.kt").code()
        assertTrue(builder.contains("color-scheme"))
        assertTrue(builder.contains("night_mode"))
        assertTrue(builder.contains("nightMode"))
    }

    // ---------------------------------------------------------------- WebView hardening (INV-RENDER-13/§14/§16/§17)

    @Test
    fun `the WebView host pins every setting the gate requires`() {
        val host = webViewHost.code()
        val required = listOf(
            "javaScriptEnabled",
            "domStorageEnabled = false",
            "databaseEnabled = false",
            "allowFileAccess = false",
            "allowContentAccess = false",
            "blockNetworkImageLoads = true",
            "mediaPlaybackRequiresUserGesture = true",
            "defaultTextEncodingName",
            "useWideViewPort = false",
            "loadWithOverviewMode = false",
            "setSupportZoom",
            "builtInZoomControls",
            "displayZoomControls = false",
            "disabledActionModeMenuItems = WebSettings.MENU_ITEM_WEB_SEARCH",
            "clearHistory()",
            "destroy()",
            "setWebContentsDebuggingEnabled"
        )
        required.forEach { token ->
            assertTrue("the WebView host must pin '$token'", host.contains(token))
        }
    }

    @Test
    fun `text selection is bounded and no browser context menu exists`() {
        val host = webViewHost.code()
        // STEP 141 — selection stays available (a reviewer copies a dose, a gene name or an Arabic term)
        // but the selection menu is bounded so selected card text cannot be handed to a search engine.
        assertTrue(
            "the selection menu must disable web search (STEP 141)",
            host.contains("disabledActionModeMenuItems = WebSettings.MENU_ITEM_WEB_SEARCH")
        )
        // STEP 142 — the generic browser menu ("open in new tab", "download image", "save link as") is
        // never offered: no context menu is registered on the card WebView at all.
        assertFalse(
            "registerForContextMenu must never be called on the card WebView (STEP 142)",
            host.contains("registerForContextMenu")
        )
        // Long press is routed to text selection instead, and the link policy stays the only
        // navigation path a card has (INV-ANKI-RENDER-16).
        assertTrue(
            "long press must be left to text selection (STEP 142)",
            host.contains("isLongClickable = true")
        )
    }

    @Test
    fun `the WebView host refuses file and content origins`() {
        val host = webViewHost.code()
        listOf("allowFileAccessFromFileURLs", "allowUniversalAccessFromFileURLs").forEach { token ->
            assertFalse("'$token' must never be enabled", host.contains("$token = true"))
        }
        listOf("file:///android_asset", "file:///sdcard", "content://", "ankidroid://").forEach {
            assertFalse("the host must not load '$it'", host.contains("\"$it"))
        }
        // Web contents debugging is a debug-build affordance only.
        assertTrue(host.contains("BuildConfig.DEBUG"))
    }

    @Test
    fun `a TLS error is cancelled and never proceeded`() {
        val host = webViewHost.code()
        assertTrue(host.contains("onReceivedSslError"))
        assertTrue(host.contains("handler.cancel()"))
        assertFalse(host.contains("proceed()"))
    }

    @Test
    fun `the renderer-process-gone path is handled and returns true`() {
        val host = webViewHost.code()
        val index = host.indexOf("onRenderProcessGone")
        assertTrue("onRenderProcessGone must be overridden", index > 0)
        val body = host.substring(index, minOf(host.length, index + 1_200))
        assertTrue("the override must return true so the app survives", body.contains("return true"))
        assertTrue("the controller must be told", body.contains("onRendererProcessGone"))
    }

    @Test
    fun `navigation goes through the controller and its policy`() {
        val host = webViewHost.code()
        val index = host.indexOf("shouldOverrideUrlLoading")
        assertTrue(index > 0)
        val body = host.substring(index, minOf(host.length, index + 600))
        assertTrue("the decision belongs to the controller", body.contains("controller.requestNavigation"))
        assertFalse("the host must not launch an Intent itself", body.contains("startActivity"))
    }

    @Test
    fun `geolocation and permission requests are denied`() {
        val host = webViewHost.code()
        assertTrue(host.contains("onGeolocationPermissionsShowPrompt"))
        assertTrue(host.contains("onPermissionRequest"))
        assertTrue("a permission request is denied, never granted", host.contains("deny()"))
        assertFalse(host.contains(".grant("))
    }

    @Test
    fun `javascript dialogs are suppressed instead of blocking the UI`() {
        val host = webViewHost.code()
        assertTrue(host.contains("onJsAlert"))
        assertTrue(host.contains("onJsConfirm"))
        assertTrue(host.contains("onJsPrompt"))
        assertTrue(host.contains("JsResult") || host.contains("JsPromptResult"))
    }

    @Test
    fun `only the WebView host and the entry point may name android webkit`() {
        val allowed = setOf("AnkiCardWebView.kt", "AnkiCardRenderer.kt")
        uiRender.filter { file -> file.imports().any { it.startsWith("import android.webkit.") } }
            .map { it.name }
            .forEach { name ->
                assertTrue("'$name' must not depend on android.webkit", name in allowed)
            }
        // The fallback view is pure Compose: it must render even on a device with no WebView provider.
        val fallback = File(appModuleDir, "src/main/java/com/studyagent/client/ui/components/anki/CleanAnkiCardView.kt")
        assertTrue(fallback.imports().none { it.startsWith("import android.webkit.") })
    }

    @Test
    fun `logging goes through AppLogger only`() {
        // `AppLogger.d(` legitimately contains "Log.d(", so the scan is about the platform logger's own
        // import and about stdout, not about a substring of the app's logger.
        listOf("android.util.Log", "println(", "System.out", "System.err", "Timber").forEach { assertAbsent(it) }
        uiRender.forEach { file ->
            if (file.name == "AnkiRenderDiagnostics.kt" || file.name == "AnkiCardWebView.kt" ||
                file.name == "AnkiExternalLinkHandler.kt"
            ) {
                assertTrue("${file.name} must log through AppLogger", file.imports().any { it.endsWith("AppLogger") })
            }
        }
    }

    // ---------------------------------------------------------------- surface ownership (INV-RENDER-22/§26)

    @Test
    fun `the surface seam is the only WebView dependency in the core`() {
        val seam = File(appModuleDir, "src/main/java/com/studyagent/client/core/render/AnkiCardRenderSurface.kt")
        assertTrue(seam.isFile)
        assertTrue(seam.imports().isEmpty())
        val code = seam.code()
        listOf("presentDocument", "resetScroll", "release", "isUsable").forEach { member ->
            assertTrue("the seam must declare '$member'", code.contains(member))
        }
    }

    @Test
    fun `one surface is created per composition and destroyed on dispose`() {
        val host = webViewHost.code()
        assertTrue("creation must be memoised", host.contains("remember("))
        assertTrue("disposal must be tied to the composition", host.contains("DisposableEffect"))
        assertTrue(host.contains("onDispose"))
        assertTrue("the WebView is released, not merely dropped", host.contains("release()"))
        assertTrue(host.contains("removeAllViews()") || host.contains("removeView("))
    }

    @Test
    fun `the entry point does not recreate a WebView on recomposition`() {
        val entry = File(appModuleDir, "src/main/java/com/studyagent/client/ui/components/anki/AnkiCardRenderer.kt").code()
        assertTrue("the controller must be remembered across recompositions", entry.contains("remember"))
        assertTrue(entry.contains("AndroidView") || entry.contains("AnkiCardWebViewSurface"))
        // The WebView is only recreated when the surface token changes (a dead renderer process).
        assertTrue(entry.contains("surfaceToken") || hostTokenBumpExists())
    }

    private fun hostTokenBumpExists(): Boolean = webViewHost.code().contains("surfaceToken")

    // ---------------------------------------------------------------- no AnkiDroid assets (INV-RENDER-13)

    @Test
    fun `no renderer file loads AnkiDroid assets or reimplements its styling`() {
        listOf(
            "android_asset",
            "ankidroid.css",
            "card.css",
            "reviewer.css",
            "MathJax",
            "mathjax",
            "jquery",
            "jQuery",
            "_page.css",
            "getAssets()"
        ).forEach { assertAbsent(it) }
    }

    @Test
    fun `the base URL is the reserved renderer origin`() {
        val document = File(appModuleDir, "src/main/java/com/studyagent/client/core/render/AnkiCardDocument.kt").code()
        assertTrue(document.contains("https://card.studyagent.invalid/"))
        listOf("file:", "about:blank", "data:text/html", "http://localhost").forEach {
            assertFalse("the base URL must not be '$it'", document.contains("BASE_URL: String = \"$it"))
        }
    }
}
