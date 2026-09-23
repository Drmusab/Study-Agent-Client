package com.studyagent.client.core.render

import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.core.common.elapsedSince
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GATE 08 — the renderer's decision core (STEP 88), and the reason the WebView stays boring.
 *
 * One controller per active card surface. It owns:
 *
 * - **the render request identity** ([AnkiRenderRequestId]) and the monotonic generation that makes a
 *   late callback recognisably late (STEP 18/§19, INV-ANKI-RENDER-06);
 * - **the presentation state** ([AnkiRenderState]) as a [StateFlow] the Compose layer collects;
 * - **the document decision** ([AnkiCardRenderPlanner] → [AnkiCardDocumentBuilder]);
 * - **the fallback order** ORIGINAL → CLEAN text → typed failure (STEP 121, INV-RENDER-20);
 * - **recovery**: a dead renderer process or a recreated surface re-presents the *same* turn, card and
 *   side (STEP 71-§73, INV-ANKI-RENDER-05).
 *
 * It does **not** own a WebView (that is [AnkiCardRenderSurface]'s production implementation), and it
 * does not own anything about the study flow: no `nextCard`, no `beginReview`, no `commitRating`, no
 * rating, no bury, no TTS, no STT, no AI, no backend query, no `ContentResolver`
 * (STEP 144-§148, INV-ANKI-RENDER-01/03/04). `AnkiRendererIsolationTest` enforces that as a source
 * scan, not as a good intention. A render failure is a presentation fact: it never becomes an Anki
 * failure (INV-RENDER-18) and never mutates the collection (INV-RENDER-19).
 *
 * **Threading:** main thread only, exactly like the Compose composition and the WebView callbacks it
 * mirrors. There is no internal lock, because there is no second thread to lock against; the state is
 * published through a [MutableStateFlow] so a collector on another dispatcher still sees a consistent
 * value.
 *
 * @param surfaceKind how [AnkiCardRenderMode.ADAPTIVE] resolves (STEP 92). Concrete modes are never
 *   re-decided.
 * @param clock injected for deterministic durations; never a calendar.
 * @param performance optional measurement sink (STEP 125).
 * @param onEvent diagnostics sink — content-free events only (PART IV).
 * @param onExternalLink upward notification for a mediated external link (STEP 45/§86). The URL is
 *   handed to the app; the renderer never navigates and never logs it.
 */
class AnkiCardRenderController(
    private val surfaceKind: AnkiRenderSurfaceKind = AnkiRenderSurfaceKind.BROWSING,
    private val clock: AppClock = SystemAppClock,
    private val performance: AnkiRenderPerformance? = null,
    private val onEvent: (AnkiRenderEvent) -> Unit = {},
    private val onExternalLink: (AnkiExternalLinkRequest) -> Unit = {}
) {

    private val _state = MutableStateFlow<AnkiRenderState>(AnkiRenderState.Idle)

    /** The presentation state. `Idle` until something is submitted, and again after [dispose]. */
    val state: StateFlow<AnkiRenderState> = _state.asStateFlow()

    /**
     * The last accepted presentation target, kept so a recovery path can re-present the *same*
     * turn/card/side (STEP 73) and so an identical re-submission is recognisable as a no-op
     * (INV-ANKI-RENDER-23). Holds a reference to the caller's card — no copy of its content — and is
     * dropped on [dispose] so a disposed surface retains no large card HTML (INV-RENDER-26).
     */
    private var submission: Submission? = null

    private var surface: AnkiCardRenderSurface? = null
    private var generation: Long = 0L
    private var loadStartedAtMs: Long? = null
    private var pendingTransition: PendingTransition? = null
    private var documentsSubmitted: Long = 0L
    private var disposed = false

    /**
     * The category reported by the platform layer when it could not create a WebView at all. Kept so a
     * missing WebView provider is diagnosable as `creation_failed` rather than as the generic
     * `no_surface` of a not-yet-attached composition (STEP 68/§121).
     */
    private var surfaceCreationFailure: String? = null

    /**
     * An ORIGINAL document is waiting for a surface that has not attached yet.
     *
     * Compose applies effects in composition order, so a submission can legitimately be decided a
     * frame before the WebView exists. Waiting is both quieter and cheaper than presenting the text
     * fallback and then upgrading it back to ORIGINAL: [attachSurface] presents the document the moment
     * a surface appears, and [onSurfaceCreationFailed] resolves the wait immediately when the platform
     * says no WebView will ever appear (STEP 12/§13/§68).
     */
    private var awaitingSurface = false

    /** The request currently on screen (or loading); `null` when idle or disposed. */
    val activeRequest: AnkiRenderRequestId? get() = _state.value.request

    /** How many documents this controller submitted since construction (bounded diagnostics). */
    val documentCount: Long get() = documentsSubmitted

    val isDisposed: Boolean get() = disposed

    /** The concrete mode of the active submission, for events and UI copy. */
    val activeMode: AnkiCardRenderMode get() = submission?.config?.mode ?: AnkiCardRenderMode.DEFAULT

    /** The active presentation plan, so the UI can read the fallback text it must show (STEP 68). */
    val activePlan: AnkiCardRenderPlan? get() = submission?.plan()

    // ------------------------------------------------------------------ submission

    /**
     * Present one side of one card for one turn (STEP 26/§27/§93).
     *
     * The side is an input, never an inference: the renderer does not decide to reveal an answer
     * (INV-ANKI-RENDER-04), and `answerHtml != null` is never treated as "the answer is showing"
     * (STEP 05).
     *
     * @return `true` when this call changed what is presented. `false` means the request is identical
     *   to the active one — the recomposition-safety guarantee: a Compose recomposition with the same
     *   card, turn, side and config reloads nothing, resets no JavaScript and moves no scroll
     *   position (STEP 13/§34/§107, INV-RENDER-23).
     */
    fun submit(
        card: AnkiRenderedCard,
        turnId: ReviewTurnId,
        side: AnkiCardSide,
        config: AnkiCardRenderConfig = AnkiCardRenderConfig.DEFAULT
    ): Boolean {
        if (disposed) return false
        val candidate = Submission(card, turnId, side, config.resolvedFor(surfaceKind))
        if (candidate == submission) return false
        return present(candidate, TransitionKind.of(submission, candidate))
    }

    /**
     * Re-present the active target on demand: a user-visible "try again" after a recoverable failure,
     * or the recovery path after a renderer-process death (STEP 71/§72).
     *
     * Same turn, same card, same side — a new generation (INV-RENDER-05). This is *not* a rating, a
     * skip or a bury: the card stays due and unrated (INV-RENDER-19).
     */
    fun retry(): Boolean {
        if (disposed) return false
        val current = submission ?: return false
        return present(current, TransitionKind.RELOAD)
    }

    private fun present(next: Submission, transition: TransitionKind): Boolean {
        submission = next
        generation += 1L
        val request = AnkiRenderRequestId(
            turnId = next.turnId,
            cardRef = next.card.ref,
            side = next.side,
            generation = generation
        )
        pendingTransition = PendingTransition(transition)

        when (val plan = next.plan()) {
            is AnkiCardRenderPlan.Original -> presentOriginal(request, next, plan)
            is AnkiCardRenderPlan.CleanText -> presentComposeText(request, next, plan)
            is AnkiCardRenderPlan.Unavailable -> fail(request, plan.failure)
        }
        return true
    }

    private fun presentOriginal(
        request: AnkiRenderRequestId,
        submission: Submission,
        plan: AnkiCardRenderPlan.Original
    ) {
        val document = AnkiCardDocumentBuilder.build(
            payload = plan.html,
            request = request,
            config = submission.config,
            tokens = plan.tokens
        )
        loadStartedAtMs = clock.nowMillis()
        _state.value = AnkiRenderState.Loading(request)
        emit(
            AnkiRenderEvent.Started(
                request = request,
                mode = submission.config.mode,
                presentation = AnkiRenderPresentation.ORIGINAL,
                javascriptPolicy = submission.config.javascriptPolicy,
                htmlLength = plan.html.length,
                verbatimDocument = document.verbatim
            )
        )

        val currentSurface = surface
        if (currentSurface == null || !currentSurface.isUsable) {
            // No WebView to execute the document. Fall back to the backend's own text channel when
            // there is one; otherwise say so (STEP 68/§121). Content is never invented (INV-RENDER-20).
            val category = surfaceCreationFailure ?: when (currentSurface) {
                // Not attached yet: hold the Loading state for one frame instead of degrading.
                null -> null
                else -> AnkiRenderFailure.WebViewUnavailable.CATEGORY_RELEASED
            }
            if (category == null) {
                awaitingSurface = true
                return
            }
            emit(AnkiRenderEvent.SurfaceUnavailable(request.turnId.value, category))
            val failure = AnkiRenderFailure.WebViewUnavailable(category)
            if (plan.fallbackText != null) {
                presentFallbackText(request, failure.token)
            } else {
                fail(request, failure)
            }
            return
        }

        awaitingSurface = false
        documentsSubmitted += 1L
        currentSurface.presentDocument(document)
    }

    private fun presentComposeText(
        request: AnkiRenderRequestId,
        submission: Submission,
        plan: AnkiCardRenderPlan.CleanText
    ) {
        // A CLEAN/VOICE_FOCUS request is the requested mode; ORIGINAL-with-no-HTML is a degradation.
        // The two are recorded differently because only one of them is a fallback (STEP 69).
        val degraded = plan.reason == AnkiCardRenderPlan.TOKEN_HTML_UNAVAILABLE
        val presentation =
            if (degraded) AnkiRenderPresentation.CLEAN_FALLBACK else AnkiRenderPresentation.CLEAN
        emit(
            AnkiRenderEvent.Started(
                request = request,
                mode = submission.config.mode,
                presentation = presentation,
                javascriptPolicy = submission.config.javascriptPolicy,
                htmlLength = null,
                verbatimDocument = false
            )
        )
        if (degraded) {
            performance?.recordFallbackUsed()
            emit(AnkiRenderEvent.FallbackUsed(request, reason = plan.reason, failureToken = null))
        }
        // A Compose presentation has no page load: the transition really is immediate, so 0 is the
        // measured truth rather than an unmeasured placeholder.
        completeTransition(0L)
        publishReady(request, presentation)
    }

    /**
     * The marked ORIGINAL → CLEAN_FALLBACK degradation (STEP 69).
     *
     * The fallback *content* is deliberately not stored here: the Compose layer reads the normalized
     * text channel from the card it already holds, so the controller never keeps card content of its
     * own (STEP 87, INV-RENDER-26).
     */
    private fun presentFallbackText(request: AnkiRenderRequestId, reason: String) {
        performance?.recordFallbackUsed()
        emit(AnkiRenderEvent.FallbackUsed(request, reason = reason, failureToken = reason))
        completeTransition(0L)
        publishReady(request, AnkiRenderPresentation.CLEAN_FALLBACK)
    }

    private fun fail(request: AnkiRenderRequestId, failure: AnkiRenderFailure) {
        val fallbackAvailable = submission?.plan()?.fallbackText != null
        val presentation =
            if (fallbackAvailable) AnkiRenderPresentation.CLEAN_FALLBACK else null
        if (presentation != null) performance?.recordFallbackUsed()
        // A failed presentation never contributes a latency sample: 0ms would look like an instant
        // success in the p50/p95 this metric exists to protect.
        completeTransition(null)
        _state.value = AnkiRenderState.Failed(request, failure, presentation)
        if (presentation != null) {
            emit(AnkiRenderEvent.FallbackUsed(request, reason = failure.token, failureToken = failure.token))
        }
        emit(AnkiRenderEvent.Failed(request, failure, fallbackShown = presentation != null))
    }

    private fun publishReady(request: AnkiRenderRequestId, presentation: AnkiRenderPresentation) {
        _state.value = AnkiRenderState.Ready(
            request = request,
            presentation = presentation,
            loadDurationMs = 0L,
            pageFinished = false
        )
        emit(
            AnkiRenderEvent.Ready(
                request = request,
                mode = activeMode,
                presentation = presentation,
                loadDurationMs = 0L,
                pageFinished = false
            )
        )
    }

    // ------------------------------------------------------------------ surface lifecycle

    /**
     * Bind a freshly created (or recreated) surface (STEP 12/§13/§72).
     *
     * A new surface has no document, so an active ORIGINAL presentation is re-presented onto it:
     * same turn, same card, same side, new generation (STEP 73, INV-RENDER-05/24). This is also what
     * makes composition ordering harmless — if [submit] ran before the WebView existed, attaching the
     * WebView upgrades the presentation from the text fallback back to ORIGINAL.
     */
    fun attachSurface(newSurface: AnkiCardRenderSurface) {
        if (disposed) {
            newSurface.release()
            return
        }
        surface = newSurface
        awaitingSurface = false
        val current = submission ?: return
        if (current.plan() !is AnkiCardRenderPlan.Original) return
        present(current, TransitionKind.RELOAD)
    }

    /**
     * The platform layer could not create a WebView (STEP 68/§121).
     *
     * Recorded, not acted on: the next [present] for an ORIGINAL plan sees no usable surface and takes
     * the documented fallback path — the text channel when the backend supplied one, a typed
     * [AnkiRenderFailure.WebViewUnavailable] when it did not. The composition order in
     * `AnkiCardWebViewSurface` reports this *before* the first [submit], so the fallback carries the
     * precise category. A card that is already on screen is never reloaded because of this report.
     */
    fun onSurfaceCreationFailed(
        category: String = AnkiRenderFailure.WebViewUnavailable.CATEGORY_CREATION_FAILED
    ) {
        if (disposed || surfaceCreationFailure == category) return
        surfaceCreationFailure = category
        emit(AnkiRenderEvent.SurfaceUnavailable(activeRequest?.turnId?.value, category))
        // A presentation that was waiting for a surface must resolve now: no WebView is coming.
        if (awaitingSurface) {
            awaitingSurface = false
            val current = submission ?: return
            if (current.plan() is AnkiCardRenderPlan.Original) present(current, TransitionKind.RELOAD)
        }
    }

    /**
     * Unbind [detached] if it is the current surface. The *owner* of the surface releases it
     * (STEP 14/§15): the controller never destroys a WebView it did not create, which is what keeps
     * lifecycle ownership explicit and makes a double-destroy impossible.
     */
    fun detachSurface(detached: AnkiCardRenderSurface) {
        if (surface === detached) surface = null
    }

    /**
     * End of the card surface: drop the submission, the timing state and the surface reference so a
     * disposed renderer retains no card HTML and no platform object (STEP 109/§14, INV-RENDER-26).
     * Idempotent; a later callback finds no active request and is stale by construction.
     */
    fun dispose(reason: String = AnkiRenderEvent.SurfaceReleased.REASON_DISPOSED) {
        if (disposed) return
        disposed = true
        val turnId = activeRequest?.turnId?.value
        surface = null
        submission = null
        loadStartedAtMs = null
        pendingTransition = null
        surfaceCreationFailure = null
        awaitingSurface = false
        _state.value = AnkiRenderState.Idle
        emit(AnkiRenderEvent.SurfaceReleased(turnId, reason, documentsSubmitted))
    }

    // ------------------------------------------------------------------ WebView callbacks

    /** Chromium started the document. The state is already [AnkiRenderState.Loading]; this validates. */
    fun onPageStarted(request: AnkiRenderRequestId) {
        if (rejectStale(request, AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_PAGE_STARTED)) return
        // Nothing to publish: Loading was set when the document was submitted, and republishing an
        // equal state would only cost a recomposition.
    }

    /**
     * Chromium finished the document (STEP 17: this is *page* readiness, not card readiness — the card
     * was bound when the document was submitted).
     */
    fun onPageFinished(request: AnkiRenderRequestId) {
        if (rejectStale(request, AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_PAGE_FINISHED)) return
        val durationMs = loadStartedAtMs?.let { clock.elapsedSince(it) }
        loadStartedAtMs = null
        if (durationMs != null) performance?.recordDocumentLoad(durationMs)
        completeTransition(durationMs)
        _state.value = AnkiRenderState.Ready(
            request = request,
            presentation = AnkiRenderPresentation.ORIGINAL,
            loadDurationMs = durationMs,
            pageFinished = true
        )
        // A new document starts at the top (STEP 32/§33), so the previous card's scroll offset cannot
        // leak into this one. Recomposition never reaches this line, so it never resets scroll either
        // (STEP 34, INV-RENDER-23).
        surface?.resetScroll()
        emit(
            AnkiRenderEvent.Ready(
                request = request,
                mode = activeMode,
                presentation = AnkiRenderPresentation.ORIGINAL,
                loadDurationMs = durationMs,
                pageFinished = true
            )
        )
    }

    /** A main-frame load error. Sub-resource failures (an unresolved image) are not this (STEP 59). */
    fun onLoadFailure(request: AnkiRenderRequestId, errorCode: Int) {
        if (rejectStale(request, AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_LOAD_FAILURE)) return
        fail(
            request,
            AnkiRenderFailure.WebViewLoadFailure(
                errorCode = errorCode,
                category = AnkiRenderFailure.WebViewLoadFailure.categoryOf(errorCode)
            )
        )
    }

    /** A main-frame TLS error was refused (never `proceed()`). */
    fun onSslErrorBlocked(request: AnkiRenderRequestId, errorCategory: String) {
        if (rejectStale(request, AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_LOAD_FAILURE)) return
        emit(AnkiRenderEvent.SslErrorBlocked(request, errorCategory))
        fail(request, AnkiRenderFailure.SslErrorBlocked)
    }

    /**
     * The WebView's renderer process terminated (STEP 71/§72).
     *
     * The instance is invalid from here on, so the controller drops it: the Compose layer observes the
     * [AnkiRenderState.Failed] state, recreates the WebView and calls [attachSurface], which
     * re-presents the same turn/card/side (STEP 73). One Chromium crash ends a WebView, never a study
     * session and never a review turn.
     */
    fun onRendererProcessGone(request: AnkiRenderRequestId, didCrash: Boolean) {
        if (rejectStale(request, AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_PROCESS_GONE)) return
        surface = null
        loadStartedAtMs = null
        performance?.recordRendererProcessFailure()
        emit(AnkiRenderEvent.RendererProcessGone(request, didCrash, documentsSubmitted))
        fail(request, AnkiRenderFailure.RendererProcessGone(didCrash))
    }

    /**
     * A card asked to navigate (STEP 44-§47).
     *
     * @return the value the WebView client must return from `shouldOverrideUrlLoading`: `true` for
     *   [AnkiLinkDecision.OPEN_EXTERNALLY] and [AnkiLinkDecision.BLOCKED] (the card never navigates
     *   the reviewer away from itself), `false` for [AnkiLinkDecision.ALLOW_IN_PAGE] (a same-document
     *   anchor the page may honour).
     */
    fun requestNavigation(request: AnkiRenderRequestId, url: String?): Boolean {
        if (rejectStale(request, AnkiRenderEvent.StaleCallbackIgnored.CALLBACK_LINK)) return true
        val classification = AnkiCardLinkPolicy.classify(url)
        return when (classification.decision) {
            AnkiLinkDecision.ALLOW_IN_PAGE -> false

            AnkiLinkDecision.OPEN_EXTERNALLY -> {
                performance?.recordExternalLinkMediated()
                emit(AnkiRenderEvent.ExternalLinkMediated(request, classification.scheme))
                onExternalLink(AnkiExternalLinkRequest(url.orEmpty(), request, classification))
                true
            }

            AnkiLinkDecision.BLOCKED -> {
                performance?.recordExternalLinkBlocked()
                emit(
                    AnkiRenderEvent.ExternalLinkBlocked(
                        request = request,
                        scheme = classification.scheme,
                        reason = classification.reason
                    )
                )
                true
            }
        }
    }

    /** Card JavaScript logged an error. Counted, never transcribed (STEP 54/§55). */
    fun onJavascriptConsoleError(request: AnkiRenderRequestId, level: String) {
        if (request.isStaleFor(activeRequest)) return
        performance?.recordJavascriptError()
        emit(
            AnkiRenderEvent.JavascriptConsoleError(
                request = request,
                level = level,
                errorCount = performance?.javascriptErrorCount ?: 1L
            )
        )
    }

    /** A card script opened a modal dialog; it is suppressed rather than allowed to block the UI. */
    fun onJavascriptDialogSuppressed(request: AnkiRenderRequestId, kind: String) {
        if (request.isStaleFor(activeRequest)) return
        emit(AnkiRenderEvent.JavascriptDialogSuppressed(request, kind))
    }

    /**
     * Card JavaScript asked for a web permission — microphone, camera, geolocation, protected media
     * (STEP 57/§58, INV-ANKI-RENDER-31). The answer is always no, and the platform request object is
     * denied by the caller: Study-Agent's microphone belongs to the native voice subsystem, never to a
     * card script.
     */
    fun onWebPermissionDenied(request: AnkiRenderRequestId, resourceCount: Int) {
        if (request.isStaleFor(activeRequest)) return
        performance?.recordWebPermissionDenied()
        emit(AnkiRenderEvent.WebPermissionDenied(request, resourceCount))
    }

    // ------------------------------------------------------------------ internals

    /**
     * True when [request] is not the active one — i.e. the callback belongs to a superseded document
     * and must be dropped (STEP 19, INV-ANKI-RENDER-06). The drop is *observable*: a race is diagnosed
     * by seeing the ignored callback, not by wondering why a card never became ready.
     */
    private fun rejectStale(request: AnkiRenderRequestId, callback: String): Boolean {
        if (!request.isStaleFor(activeRequest)) return false
        performance?.recordStaleCallbackIgnored()
        emit(AnkiRenderEvent.StaleCallbackIgnored(request, activeRequest, callback))
        return true
    }

    /**
     * Records the transition family this presentation belonged to, once, when it completes.
     *
     * A `null` duration clears the pending transition without recording a sample — the honest answer
     * for a presentation that failed instead of completing.
     */
    private fun completeTransition(durationMs: Long?) {
        val transition = pendingTransition ?: return
        pendingTransition = null
        if (durationMs == null) return
        when (transition.kind) {
            TransitionKind.SIDE -> performance?.recordSideTransition(durationMs)
            TransitionKind.NEW_CARD -> performance?.recordNewCardTransition(durationMs)
            TransitionKind.FIRST -> Unit
            TransitionKind.RELOAD -> Unit
        }
    }

    private fun emit(event: AnkiRenderEvent) {
        onEvent(event)
    }

    /** What changed between two submissions, so the right latency family is measured (STEP 125). */
    private enum class TransitionKind {
        FIRST,
        NEW_CARD,
        SIDE,
        RELOAD;

        companion object {
            fun of(previous: Submission?, next: Submission): TransitionKind = when {
                previous == null -> FIRST
                previous.turnId != next.turnId || previous.card.ref != next.card.ref -> NEW_CARD
                previous.side != next.side -> SIDE
                else -> RELOAD
            }
        }
    }

    private data class PendingTransition(val kind: TransitionKind)

    /**
     * The accepted presentation target. Equality is the recomposition guard (INV-RENDER-23): same card
     * content, same turn, same side, same resolved config → nothing to do.
     *
     * The plan is derived, never stored: [AnkiCardRenderPlanner] is a pure function of the card and the
     * mode, and storing a second copy of the card's HTML would double the memory of the active card for
     * no benefit (STEP 128).
     */
    private data class Submission(
        val card: AnkiRenderedCard,
        val turnId: ReviewTurnId,
        val side: AnkiCardSide,
        val config: AnkiCardRenderConfig
    ) {
        fun plan(): AnkiCardRenderPlan = AnkiCardRenderPlanner.plan(card, side, config.mode)
    }
}
