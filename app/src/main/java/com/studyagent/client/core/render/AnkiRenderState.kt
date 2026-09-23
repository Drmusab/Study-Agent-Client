package com.studyagent.client.core.render

/**
 * GATE 08 — what is on screen for one render request, and how it got there (STEP 16/§17/§69).
 *
 * Two distinctions this type exists to keep apart:
 *
 * 1. **"The card is bound" vs "the page finished"** (STEP 17). [AnkiRenderState.Loading] means a
 *    document was accepted and submitted; [AnkiRenderState.Ready] with `pageFinished = true` means
 *    Chromium reported the load complete. A Compose-text presentation is [Ready] immediately with
 *    `pageFinished = false`: there is no page, and claiming one would be a lie.
 * 2. **"Rendered as authored" vs "rendered as fallback"** (STEP 69). [presentation] says which, so
 *    diagnostics can record `ORIGINAL_FAILED → CLEAN_FALLBACK` and the UI can tell the user that the
 *    fidelity path was not available. A fallback is never silent.
 *
 * The state is renderer-owned presentation state only. It never enters the domain card
 * (STEP 87, INV-ANKI-RENDER-04/07), never touches `StudyState`, and a [AnkiRenderState.Failed] is
 * never an Anki data failure (INV-ANKI-RENDER-18) and never mutates the scheduler
 * (INV-ANKI-RENDER-19): a card that fails to render is still due, still unrated, still the current
 * turn.
 */
sealed interface AnkiRenderState {

    /** The request this state describes; `null` only for [AnkiRenderState.Idle]. */
    val request: AnkiRenderRequestId?

    /** What is on screen, when anything is; `null` while loading or when nothing is presentable. */
    val presentation: AnkiRenderPresentation?
        get() = when (this) {
            is Ready -> presentation
            is Failed -> presentation
            else -> null
        }

    /**
     * Content-free state token for diagnostics, accessibility state descriptions and tests
     * (`ready_original`, `failed_renderer_process_crashed`). Never carries card content.
     */
    val token: String
        get() = when (this) {
            is Idle -> "idle"
            is Loading -> "loading_${request.side.name.lowercase()}"
            is Ready -> "ready_${presentation.token}" + if (pageFinished) "" else "_compose"
            is Failed -> "failed_${failure.token}" +
                if (presentation == AnkiRenderPresentation.CLEAN_FALLBACK) "_with_fallback" else ""
        }

    /** Nothing has been asked of this renderer yet (or it was disposed). */
    data object Idle : AnkiRenderState {
        override val request: AnkiRenderRequestId? get() = null
    }

    /**
     * A document was accepted and submitted to the WebView; the load has not been reported
     * complete. This is the state in which a lightweight placeholder may be shown (STEP 67) — and
     * in which the *previous* card must not be shown under the new turn.
     */
    data class Loading(override val request: AnkiRenderRequestId) : AnkiRenderState

    /**
     * The active request is presentable.
     *
     * @param presentation ORIGINAL (WebView) or CLEAN_FALLBACK (Compose text).
     * @param loadDurationMs document-submit → page-finished for ORIGINAL; `0` for a Compose
     *   presentation, which has no load.
     * @param pageFinished Chromium reported `onPageFinished` for this exact request. `false` means
     *   the content is Compose-presented (no page exists), **not** that a page is still loading.
     */
    data class Ready(
        override val request: AnkiRenderRequestId,
        override val presentation: AnkiRenderPresentation,
        val loadDurationMs: Long?,
        val pageFinished: Boolean
    ) : AnkiRenderState {
        init {
            require(loadDurationMs == null || loadDurationMs >= 0L) {
                "a load duration is never negative"
            }
        }
    }

    /**
     * The requested presentation could not be produced.
     *
     * @param failure the typed reason (STEP 122) — never a bare "unknown".
     * @param presentation [AnkiRenderPresentation.CLEAN_FALLBACK] when the fallback text is on
     *   screen despite the failure (STEP 68/§69), `null` when there is nothing to show and the UI
     *   must say so explicitly instead of leaving a blank surface (STEP 68).
     */
    data class Failed(
        override val request: AnkiRenderRequestId,
        val failure: AnkiRenderFailure,
        override val presentation: AnkiRenderPresentation?
    ) : AnkiRenderState {
        /** True when the user is looking at the normalized text fallback rather than the card HTML. */
        val showingFallback: Boolean get() = presentation == AnkiRenderPresentation.CLEAN_FALLBACK
    }
}

/** Which surface is actually presenting the card (STEP 69). */
enum class AnkiRenderPresentation {
    /** The backend's rendered HTML, executed by the WebView — fidelity first (INV-RENDER-02). */
    ORIGINAL,

    /**
     * Study-Agent's Compose text surface over the backend's *text* channels, because that is what was
     * **asked for** ([AnkiCardRenderMode.CLEAN] / [AnkiCardRenderMode.VOICE_FOCUS]). Not a failure,
     * not a degradation — the requested mode.
     */
    CLEAN,

    /**
     * Study-Agent's Compose text surface over the backend's *text* channels because ORIGINAL was
     * unavailable or failed (STEP 68/§69/§121). Distinct from [CLEAN] on purpose: this one is a
     * marked degradation and diagnostics record `ORIGINAL_FAILED → CLEAN_FALLBACK` for it
     * (INV-RENDER-20 — the content is still the backend's own, never invented).
     */
    CLEAN_FALLBACK;

    /** True when Compose text is on screen rather than the WebView. */
    val isComposeText: Boolean get() = this != ORIGINAL

    /** Diagnostics token: `original` / `clean` / `clean_fallback`. */
    val token: String get() = name.lowercase()
}

/**
 * GATE 08 — typed renderer failures (STEP 122).
 *
 * Presentation failures, in their own vocabulary on purpose. None of these is an `AnkiError`:
 * a WebView that cannot load a page says nothing about whether the card exists, whether the
 * collection is open or whether the backend is available (INV-ANKI-RENDER-18), and mapping one onto
 * `AnkiBackendUnavailable` would send the user to fix AnkiDroid for a Chromium problem.
 *
 * Every failure carries a content-free [token] for logs/diagnostics — never card HTML, never a
 * question, never a URL (STEP 54/§124).
 */
sealed interface AnkiRenderFailure {

    /** Stable, content-free diagnostics token. */
    val token: String

    /**
     * True when re-presenting the same turn/card/side can plausibly succeed (STEP 71/§72). Drives a
     * *retry-render* affordance only — retrying a render is not rating, skipping or burying a card
     * (INV-ANKI-RENDER-19).
     */
    val isRecoverable: Boolean

    /**
     * The visual channel is absent for this side (`questionHtml`/`answerHtml` was `null` — the
     * backend could not supply it, GATE 07 nullability semantics). Not corruption, not a WebView
     * problem: there is simply no HTML to execute. The renderer falls back to the text channel when
     * one exists (STEP 119/§120, INV-RENDER-20).
     */
    data class HtmlUnavailable(val side: AnkiCardSide) : AnkiRenderFailure {
        override val token: String get() = "html_unavailable_${side.name.lowercase()}"
        override val isRecoverable: Boolean get() = false
    }

    /**
     * The text channel is absent for this side while a Compose-text presentation was requested and
     * no HTML channel exists either. Nothing at all can be shown, so the UI must say so instead of
     * rendering a blank surface (STEP 68). Content is never invented (INV-RENDER-20).
     */
    data class TextUnavailable(val side: AnkiCardSide) : AnkiRenderFailure {
        override val token: String get() = "text_unavailable_${side.name.lowercase()}"
        override val isRecoverable: Boolean get() = false
    }

    /**
     * No usable WebView: creation threw (a missing/broken WebView provider is a real device state),
     * or the surface was never attached. The card can still be *read* through the text fallback.
     *
     * @param category a small stable token (`creation_failed`, `no_surface`, `released`) — never an
     *   exception message, which can embed device or content details.
     */
    data class WebViewUnavailable(val category: String) : AnkiRenderFailure {
        override val token: String get() = "webview_unavailable_$category"
        override val isRecoverable: Boolean get() = true

        companion object {
            const val CATEGORY_CREATION_FAILED: String = "creation_failed"
            const val CATEGORY_NO_SURFACE: String = "no_surface"
            const val CATEGORY_RELEASED: String = "released"
        }
    }

    /**
     * Chromium reported a main-frame load error.
     *
     * @param errorCode the WebView error code, kept because it is the only diagnosable number.
     * @param category a stable token derived from [errorCode]; the platform's `description` is
     *   deliberately **not** carried — it can contain the failing URL and therefore card-derived
     *   content (STEP 54/§124).
     */
    data class WebViewLoadFailure(val errorCode: Int, val category: String) : AnkiRenderFailure {
        override val token: String get() = "webview_load_${category}_$errorCode"
        override val isRecoverable: Boolean get() = true

        companion object {
            /**
             * Error code → stable token. Codes are the documented `WebViewClient.ERROR_*` values;
             * an unknown code stays unknown rather than being guessed into a family.
             */
            fun categoryOf(errorCode: Int): String = when (errorCode) {
                -1 -> "unknown"
                -2 -> "host_lookup"
                -3 -> "unsupported_auth_scheme"
                -4 -> "authentication"
                -6 -> "connect"
                -7 -> "io"
                -8 -> "timeout"
                -9 -> "redirect_loop"
                -10 -> "unsupported_scheme"
                -11 -> "failed_ssl_handshake"
                -12 -> "bad_url"
                -13 -> "file"
                -14 -> "file_not_found"
                -15 -> "too_many_requests"
                else -> "unmapped"
            }
        }
    }

    /**
     * The WebView's renderer process terminated (STEP 71/§72). The WebView instance is invalid from
     * this moment on and must be disposed and recreated — but the *turn* survives: the same turn,
     * card and side are re-presented on the new surface (STEP 73, INV-RENDER-05). One Chromium
     * crash must not end a study session.
     *
     * @param didCrash the platform's own distinction between a crash and a low-memory kill.
     */
    data class RendererProcessGone(val didCrash: Boolean) : AnkiRenderFailure {
        override val token: String get() = if (didCrash) "renderer_process_crashed" else "renderer_process_killed"
        override val isRecoverable: Boolean get() = true
    }

    /**
     * A main-frame TLS error. Always refused (`handler.cancel()`), never `proceed()`: a card cannot
     * talk this app into accepting a bad certificate. Full network/TLS policy is GATE 09
     * (STEP 130, INV-RENDER-32).
     */
    data object SslErrorBlocked : AnkiRenderFailure {
        override val token: String get() = "ssl_error_blocked"
        override val isRecoverable: Boolean get() = false
    }
}
