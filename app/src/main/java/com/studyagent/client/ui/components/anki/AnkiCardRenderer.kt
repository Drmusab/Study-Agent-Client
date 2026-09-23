package com.studyagent.client.ui.components.anki

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.AnkiCardRenderPlanner
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiExternalLinkRequest
import com.studyagent.client.core.render.AnkiRenderEvent
import com.studyagent.client.core.render.AnkiRenderPerformance
import com.studyagent.client.core.render.AnkiRenderState
import com.studyagent.client.core.render.AnkiCardRenderController
import com.studyagent.client.core.render.AnkiCardRenderPlan
import com.studyagent.client.core.render.AnkiRenderPresentation
import com.studyagent.client.core.render.AnkiRenderSurfaceKind
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing
import kotlinx.coroutines.delay

/**
 * GATE 08 — the Original Anki Card Rendering Engine's public Compose surface (STEP 85/§89/§143).
 *
 * ```text
 *                     AnkiRenderedCard  (GATE 07, normalized, backend-neutral)
 *                              │
 *                              ▼
 *                      AnkiCardRenderer          ← this composable: parameters in, events out
 *                              │
 *              ┌───────────────┴────────────────┐
 *              ▼                                ▼
 *      ORIGINAL mode                     CLEAN / VOICE_FOCUS
 *   AnkiCardWebViewSurface              CleanAnkiCardView
 *              │                        (also the fallback)
 *              ▼
 *       Android WebView → Anki HTML / CSS / JS
 * ```
 *
 * ## The contract (STEP 85/§86/§87/§93)
 *
 * In: the normalized card, the turn identity, the **explicit** side, a render config, and two upward
 * callbacks. Out: presentation events ([AnkiRenderEvent]) and mediated external-link requests. Nothing
 * else crosses this boundary — no repository, no backend, no gateway, no scheduler, no session machine,
 * no TTS/STT/AI (STEP 144-§148, INV-ANKI-RENDER-01/03/04). The renderer never decides to reveal an
 * answer: [side] is an input (STEP 93), and no reveal button and no rating bar are injected into the
 * card HTML (STEP 94/§95) — that UI stays native and belongs to later gates.
 *
 * ## What is stable across recomposition (STEP 12/§13/§34, INV-RENDER-22/23)
 *
 * The controller and the WebView are created once per card surface, not per recomposition. A
 * recomposition with the same card, turn, side and config reloads nothing, resets no JavaScript, moves
 * no scroll position and creates no WebView. Exactly one WebView exists at a time; it is recreated only
 * when Chromium's renderer process dies (STEP 71-§73).
 *
 * ## Height (STEP 65/§66)
 *
 * **Bounded fill, not dynamic measurement.** The card area fills the space the caller gives it (with a
 * minimum so it can never collapse to zero) and the WebView scrolls its own document. There is
 * deliberately no "JS reports document height → Compose resizes → WebView relayouts" loop: that loop is
 * how a renderer ends up measuring forever. Wide content (a large table) scrolls horizontally inside the
 * WebView instead of forcing the Compose hierarchy to infinite width (STEP 63/§64).
 *
 * ## Integration point (STEP 143)
 *
 * This gate integrates the renderer into controlled surfaces only — Compose previews and the
 * instrumented suite over the GATE 07/§98 fixture deck. The production `StudyScreen` still shows the
 * protocol `StudyCard`; wiring `AnkiRenderedCard` into the live review flow is GATE 10's
 * `StudySessionMachine` work, and doing it here would mean rewriting the study flow one gate early.
 */
@Composable
fun AnkiCardRenderer(
    card: AnkiRenderedCard,
    turnId: ReviewTurnId,
    side: AnkiCardSide,
    modifier: Modifier = Modifier,
    config: AnkiCardRenderConfig = AnkiCardRenderConfig.DARK_APP,
    surfaceKind: AnkiRenderSurfaceKind = AnkiRenderSurfaceKind.BROWSING,
    performance: AnkiRenderPerformance? = null,
    onRenderEvent: (AnkiRenderEvent) -> Unit = {},
    /**
     * When `null` (the default) a mediated link is opened with [AnkiExternalLinkHandler] — outside the
     * app, never inside the reviewer. Provide it to own that policy yourself; the renderer never
     * navigates its own WebView either way (STEP 44/§45).
     */
    onExternalLink: ((AnkiExternalLinkRequest) -> Unit)? = null,
    /** Test seam (STEP 101/§110/§128): instrumented tests inject a counting or recording factory. */
    createWebView: (Context) -> WebView? = { createAnkiCardWebView(it) }
) {
    val context = LocalContext.current
    // Always remembered, conditionally used: a `remember` inside an elvis would move Compose's slot
    // table around whenever the caller starts or stops supplying its own metrics.
    val ownedMetrics = remember { AnkiRenderPerformance() }
    val metrics = performance ?: ownedMetrics
    // ADAPTIVE is resolved exactly once, here, so no renderer below ever sees an unresolved mode
    // (STEP 92) and so a concrete mode is never re-decided.
    val resolvedConfig = remember(config, surfaceKind) { config.resolvedFor(surfaceKind) }
    val resolvedMode = resolvedConfig.mode

    val currentOnEvent by rememberUpdatedState(onRenderEvent)
    val currentOnLink by rememberUpdatedState(onExternalLink)
    val currentContext by rememberUpdatedState(context)

    val controller = remember(surfaceKind, metrics) {
        AnkiCardRenderController(
            surfaceKind = surfaceKind,
            performance = metrics,
            onEvent = { event ->
                AnkiRenderDiagnostics.log(event)
                currentOnEvent(event)
            },
            onExternalLink = { request ->
                val handler = currentOnLink
                if (handler != null) {
                    handler(request)
                } else {
                    AnkiExternalLinkHandler.open(currentContext, request)
                }
            }
        )
    }

    // STEP 14/§109 — the composition that created the controller ends it: no submission, no surface
    // reference and no card HTML survive disposal (INV-ANKI-RENDER-26).
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    val renderState by controller.state.collectAsStateWithLifecycle()
    val plan = remember(card, side, resolvedMode) {
        AnkiCardRenderPlanner.plan(card, side, resolvedMode)
    }

    // --- what is on screen -----------------------------------------------------------
    val wantsWebView = resolvedMode.usesWebView
    val presentation = renderState.presentation
    val showingFallback = presentation == AnkiRenderPresentation.CLEAN_FALLBACK

    val textToShow: String? = when {
        plan is AnkiCardRenderPlan.CleanText -> plan.text
        showingFallback -> plan.fallbackText
        else -> null
    }
    val showWebView = wantsWebView && !showingFallback
    val isLoading = renderState is AnkiRenderState.Loading
    val failure = (renderState as? AnkiRenderState.Failed)?.failure
    // Neither channel exists: an explicit message instead of an empty surface (STEP 68).
    val nothingToShow = textToShow == null && failure != null && presentation == null

    // STEP 67 — a placeholder only if the load is actually slow, and never the previous card under a
    // new turn: the overlay is opaque and keyed to the loading state of the *active* request.
    var showPlaceholder by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading, renderState.request) {
        if (isLoading) {
            delay(PLACEHOLDER_DELAY_MS)
            showPlaceholder = true
        } else {
            showPlaceholder = false
        }
    }

    val accessibilityLabel = sideAccessibilityLabel(side, degraded = showingFallback)

    Column(
        modifier = modifier
            .heightIn(min = MIN_CARD_SURFACE_HEIGHT_DP.dp)
            .testTag(AnkiCardRendererTags.RENDERER)
            .semantics {
                // STEP 137 — the surface is labelled and its state is exposed, and the WebView's own
                // accessibility tree stays intact underneath: nothing here merges or hides descendants.
                contentDescription = accessibilityLabel
                stateDescription = renderState.token
            }
    ) {
        if (failure != null) {
            InfoBanner(
                title = if (showingFallback) "Rendered card unavailable" else "Card could not be displayed",
                message = if (showingFallback) {
                    "Showing this card's text instead of Anki's rendered layout. Nothing was answered, " +
                        "rated or skipped — the card is still due."
                } else {
                    "This card has no displayable content right now. Nothing was answered, rated or " +
                        "skipped — the card is still due."
                },
                tone = if (showingFallback) BannerTone.WARNING else BannerTone.DANGER,
                actionLabel = if (failure.isRecoverable) "Try again" else null,
                onAction = if (failure.isRecoverable) ({ controller.retry() }) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AnkiCardRendererTags.FAILURE)
            )
            Spacer(modifier = Modifier.height(AppSpacing.SM))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = MIN_CARD_BODY_HEIGHT_DP.dp)
        ) {
            if (wantsWebView) {
                // Composed for the whole ORIGINAL surface, not per presentation: switching a card to the
                // text fallback hides the WebView instead of destroying it, so switching back does not
                // rebuild it (INV-ANKI-RENDER-22/23).
                AnkiCardWebViewSurface(
                    controller = controller,
                    modifier = Modifier
                        .matchParentSize()
                        .testTag(AnkiCardRendererTags.WEBVIEW),
                    visible = showWebView,
                    performance = metrics,
                    createWebView = createWebView
                )
            }

            if (showWebView && showPlaceholder) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(AppColors.surfacePrimary)
                        .testTag(AnkiCardRendererTags.PLACEHOLDER),
                    contentAlignment = Alignment.Center
                ) {
                    AnkiRenderPlaceholder()
                }
            }

            if (textToShow != null) {
                CleanAnkiCardView(
                    text = textToShow,
                    side = side,
                    direction = resolvedConfig.direction,
                    degraded = showingFallback,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .testTag(AnkiCardRendererTags.FALLBACK)
                )
            } else if (nothingToShow) {
                // Neither channel exists and no WebView is composed: say so instead of showing an empty
                // surface (STEP 68/§119/§120). Content is never invented (INV-ANKI-RENDER-20).
                AnkiRenderPlaceholder(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(AnkiCardRendererTags.PLACEHOLDER),
                    message = "No displayable content for this card side",
                    showProgress = false
                )
            }
        }
    }

    // Deliberately the LAST effect in this composable: effects run in composition order, so the WebView
    // surface above has already attached (or reported that it could not be created) before the first
    // submission is decided. The controller also tolerates the opposite order — it waits one frame for a
    // surface rather than falling back prematurely — but ordering it this way keeps the diagnostics of a
    // first card clean.
    LaunchedEffect(turnId, card, side, resolvedConfig) {
        controller.submit(card, turnId, side, resolvedConfig)
    }
}

/** Semantics/test tags for the renderer surface, read by the instrumented suite (STEP 101). */
object AnkiCardRendererTags {
    const val RENDERER: String = "anki_card_renderer"
    const val WEBVIEW: String = "anki_card_webview"
    const val FALLBACK: String = "anki_card_fallback_text"
    const val PLACEHOLDER: String = "anki_card_render_placeholder"
    const val FAILURE: String = "anki_card_render_failure"
}

/** How long a load may take before a spinner is worth showing (STEP 67: no flicker, no stale card). */
private const val PLACEHOLDER_DELAY_MS: Long = 120L

/** Minimum card surface height: a caller that forgets to bound the area still gets a usable card. */
private const val MIN_CARD_SURFACE_HEIGHT_DP: Int = 220

/** Minimum height of the card body inside that surface (a notice may take part of it). */
private const val MIN_CARD_BODY_HEIGHT_DP: Int = 160
