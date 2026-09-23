package com.studyagent.client.core.render

/**
 * GATE 08 — the renderer's observability vocabulary (PART IV, STEP 124/§55).
 *
 * The renderer reports *upward* and never commands anything (STEP 86): these events are how a
 * presentation makes itself visible to diagnostics, and how the Study surface learns that a card
 * wants to open a link. No event carries an instruction to the scheduler, the session machine, TTS,
 * STT or the AI (INV-ANKI-RENDER-03/04).
 *
 * ### Privacy is a structural property here, not a promise
 *
 * Metadata is identifiers, counts, lengths, tokens and durations — never card HTML, never a
 * question, never an answer, never a user's card JavaScript, never a link URL (STEP 54/§55/§124).
 * `AnkiRenderEventTest` asserts that against the real fixture deck: an event built from a card
 * containing Arabic text, a table and a script must not contain any of it.
 *
 * The UI layer writes these through the existing `AppLogger` convention
 * (`EVENT_NAME key=value …`), so a render failure is diagnosable from the same log/diagnostics
 * surface as every other subsystem.
 */
sealed interface AnkiRenderEvent {

    /** The `ANKI_RENDER_*` event name. */
    val name: String

    /** Turn identity, content-free by construction (GATE 06 `ReviewTurnId`). */
    val turnId: String?

    /** Content-free facts. Bounded: identifiers, counts, lengths, tokens, durations. */
    val metadata: Map<String, String>

    /** One log line: `ANKI_RENDER_READY turn=… side=ANSWER gen=3 ms=42`. */
    fun logLine(): String = buildString(name.length + 96) {
        append(name)
        turnId?.let { append(" turn=").append(it) }
        metadata.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
    }

    // ------------------------------------------------------------------ lifecycle

    /** A document was accepted and submitted (or a Compose presentation was chosen). */
    data class Started(
        val request: AnkiRenderRequestId,
        val mode: AnkiCardRenderMode,
        val presentation: AnkiRenderPresentation,
        val javascriptPolicy: AnkiJavascriptPolicy,
        val htmlLength: Int?,
        val verbatimDocument: Boolean
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_MODE to mode.name,
            KEY_PRESENTATION to presentation.token,
            KEY_JS_POLICY to javascriptPolicy.name,
            KEY_HTML_LENGTH to (htmlLength?.toString() ?: "-"),
            KEY_VERBATIM to verbatimDocument.toString()
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_STARTED"
        }
    }

    /** The active request is presentable (page finished, or Compose text shown). */
    data class Ready(
        val request: AnkiRenderRequestId,
        val mode: AnkiCardRenderMode,
        val presentation: AnkiRenderPresentation,
        val loadDurationMs: Long?,
        val pageFinished: Boolean
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_MODE to mode.name,
            KEY_PRESENTATION to presentation.token,
            KEY_DURATION_MS to (loadDurationMs?.toString() ?: "-"),
            KEY_PAGE_FINISHED to pageFinished.toString()
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_READY"
        }
    }

    /** The requested presentation could not be produced. A presentation failure, never an Anki one. */
    data class Failed(
        val request: AnkiRenderRequestId,
        val failure: AnkiRenderFailure,
        val fallbackShown: Boolean
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_FAILURE to failure.token,
            KEY_RECOVERABLE to failure.isRecoverable.toString(),
            KEY_FALLBACK_SHOWN to fallbackShown.toString()
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_FAILED"
        }
    }

    /**
     * ORIGINAL was unavailable or failed and the normalized text channel is on screen instead
     * (STEP 69). Recorded separately from [Failed] because the two facts matter independently: a
     * fallback can be used without a failure (no HTML channel at all), and a failure can happen
     * without a fallback (no text channel either).
     */
    data class FallbackUsed(
        val request: AnkiRenderRequestId,
        val reason: String,
        val failureToken: String?
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_REASON to reason,
            KEY_FAILURE to (failureToken ?: "-"),
            KEY_PRESENTATION to AnkiRenderPresentation.CLEAN_FALLBACK.token
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_FALLBACK_USED"
        }
    }

    /** Disposal of the card surface (STEP 109/§14): what was released, and why. */
    data class SurfaceReleased(
        override val turnId: String?,
        val reason: String,
        val documentsLoaded: Long
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val metadata: Map<String, String> get() = linkedMapOf(
            KEY_REASON to reason,
            KEY_DOCUMENTS_LOADED to documentsLoaded.toString()
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_SURFACE_RELEASED"
            const val REASON_DISPOSED: String = "disposed"
            const val REASON_RECREATED: String = "recreated"
            const val REASON_SESSION_END: String = "session_end"
        }
    }

    // ------------------------------------------------------------------ failures and recovery

    /** The WebView's renderer process died (STEP 71/§72). */
    data class RendererProcessGone(
        val request: AnkiRenderRequestId,
        val didCrash: Boolean,
        val documentsLoaded: Long
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_DID_CRASH to didCrash.toString(),
            KEY_DOCUMENTS_LOADED to documentsLoaded.toString()
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_PROCESS_GONE"
        }
    }

    /** No usable WebView: creation failed or no surface was ever attached. */
    data class SurfaceUnavailable(
        override val turnId: String?,
        val category: String
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val metadata: Map<String, String> get() = linkedMapOf(KEY_CATEGORY to category)

        companion object {
            const val NAME: String = "ANKI_RENDER_SURFACE_UNAVAILABLE"
        }
    }

    /** A callback arrived for a superseded document and was dropped (STEP 19, INV-RENDER-06). */
    data class StaleCallbackIgnored(
        val stale: AnkiRenderRequestId,
        val active: AnkiRenderRequestId?,
        val callback: String
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = active?.turnId?.value ?: stale.turnId.value
        override val metadata: Map<String, String> get() = linkedMapOf(
            KEY_CALLBACK to callback,
            KEY_STALE_GENERATION to stale.generation.toString(),
            KEY_STALE_SIDE to stale.side.name,
            KEY_ACTIVE_GENERATION to (active?.generation?.toString() ?: "-"),
            KEY_SAME_TURN to (active?.isSameTurn(stale)?.toString() ?: "false")
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_STALE_CALLBACK_IGNORED"
            const val CALLBACK_PAGE_STARTED: String = "page_started"
            const val CALLBACK_PAGE_FINISHED: String = "page_finished"
            const val CALLBACK_LOAD_FAILURE: String = "load_failure"
            const val CALLBACK_PROCESS_GONE: String = "process_gone"
            const val CALLBACK_LINK: String = "link"
        }
    }

    // ------------------------------------------------------------------ page-context incidents

    /**
     * Card JavaScript logged an error. The **message text is never carried** (STEP 54/§55): a card's
     * console output is deck content, and a production log must not collect it. Debug builds log a
     * sanitized, truncated message through `AppLogger` at the WebView layer instead.
     */
    data class JavascriptConsoleError(
        val request: AnkiRenderRequestId,
        val level: String,
        val errorCount: Long
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_LEVEL to level,
            KEY_JS_ERROR_COUNT to errorCount.toString()
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_JS_ERROR"
        }
    }

    /** A card script tried to open a modal dialog; it was suppressed instead of blocking the UI. */
    data class JavascriptDialogSuppressed(
        val request: AnkiRenderRequestId,
        val kind: String
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_KIND to kind
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_JS_DIALOG_SUPPRESSED"
            const val KIND_ALERT: String = "alert"
            const val KIND_CONFIRM: String = "confirm"
            const val KIND_PROMPT: String = "prompt"
            const val KIND_BEFORE_UNLOAD: String = "before_unload"
        }
    }

    /**
     * Card JavaScript asked for a web permission (microphone, camera, …) and was denied
     * (STEP 57/§58, INV-ANKI-RENDER-31). Resources are counted, never named: the platform's resource
     * strings are stable but the count is what matters — the answer is always "no".
     */
    data class WebPermissionDenied(
        val request: AnkiRenderRequestId,
        val resourceCount: Int
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_RESOURCE_COUNT to resourceCount.toString()
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_WEB_PERMISSION_DENIED"
        }
    }

    // ------------------------------------------------------------------ navigation

    /** An http(s) link was handed to the app's external-open policy (STEP 45). No URL is recorded. */
    data class ExternalLinkMediated(
        val request: AnkiRenderRequestId,
        val scheme: String?
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_SCHEME to (scheme ?: "-")
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_EXTERNAL_LINK_MEDIATED"
        }
    }

    /** A navigation was refused: unknown scheme, renderer-origin relative link, or unparseable URL. */
    data class ExternalLinkBlocked(
        val request: AnkiRenderRequestId,
        val scheme: String?,
        val reason: String
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_SCHEME to (scheme ?: "-"),
            KEY_REASON to reason
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_EXTERNAL_LINK_BLOCKED"
        }
    }

    /** A main-frame TLS error was refused rather than proceeded through (INV-RENDER-32 adjacent). */
    data class SslErrorBlocked(
        val request: AnkiRenderRequestId,
        val errorCategory: String
    ) : AnkiRenderEvent {
        override val name: String get() = NAME
        override val turnId: String? get() = request.turnId.value
        override val metadata: Map<String, String> get() = request.requestMetadata() + linkedMapOf(
            KEY_CATEGORY to errorCategory
        )

        companion object {
            const val NAME: String = "ANKI_RENDER_SSL_BLOCKED"
        }
    }

    // ------------------------------------------------------------------ shared keys

    companion object {
        const val KEY_CARD: String = "card"
        const val KEY_ORD: String = "ord"
        const val KEY_SIDE: String = "side"
        const val KEY_GENERATION: String = "gen"
        const val KEY_MODE: String = "mode"
        const val KEY_PRESENTATION: String = "presentation"
        const val KEY_JS_POLICY: String = "js"
        const val KEY_HTML_LENGTH: String = "html"
        const val KEY_VERBATIM: String = "verbatim"
        const val KEY_DURATION_MS: String = "ms"
        const val KEY_PAGE_FINISHED: String = "page_finished"
        const val KEY_FAILURE: String = "failure"
        const val KEY_RECOVERABLE: String = "recoverable"
        const val KEY_FALLBACK_SHOWN: String = "fallback_shown"
        const val KEY_REASON: String = "reason"
        const val KEY_CATEGORY: String = "category"
        const val KEY_CALLBACK: String = "callback"
        const val KEY_STALE_GENERATION: String = "stale_gen"
        const val KEY_STALE_SIDE: String = "stale_side"
        const val KEY_ACTIVE_GENERATION: String = "active_gen"
        const val KEY_SAME_TURN: String = "same_turn"
        const val KEY_DID_CRASH: String = "did_crash"
        const val KEY_DOCUMENTS_LOADED: String = "documents"
        const val KEY_LEVEL: String = "level"
        const val KEY_JS_ERROR_COUNT: String = "js_errors"
        const val KEY_KIND: String = "kind"
        const val KEY_RESOURCE_COUNT: String = "resources"
        const val KEY_SCHEME: String = "scheme"
    }
}

/**
 * The identity block every request-scoped event carries.
 *
 * One definition, shared by all events, so no event can accidentally start logging content: card id
 * (or note id when the backend gave no card id), ordinal, side and render generation — all
 * content-free identifiers, plus nothing else (STEP 124).
 */
private fun AnkiRenderRequestId.requestMetadata(): Map<String, String> = linkedMapOf(
    AnkiRenderEvent.KEY_CARD to (cardRef.cardId ?: cardRef.noteId ?: "-"),
    AnkiRenderEvent.KEY_ORD to (cardRef.cardOrd?.toString() ?: "-"),
    AnkiRenderEvent.KEY_SIDE to side.name,
    AnkiRenderEvent.KEY_GENERATION to generation.toString()
)
