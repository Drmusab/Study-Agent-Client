package com.studyagent.client.core.render

import java.net.URI

/**
 * What the renderer does with a navigation a card asked for (STEP 44-§47).
 *
 * The review WebView is **not a browser** (STEP 48): a card click never replaces the card with
 * arbitrary web content, and the app's Back button belongs to app navigation, never to WebView
 * history (STEP 49).
 */
enum class AnkiLinkDecision {
    /**
     * Let the WebView handle it in place — a same-document fragment (`#answer`, `#top`) only
     * (STEP 47). Nothing leaves the page, nothing is added to a meaningful history.
     */
    ALLOW_IN_PAGE,

    /**
     * Hand the URL to the app's external-open policy (a browser outside Study-Agent), and keep the
     * card exactly as it is (STEP 45). The renderer never navigates itself.
     */
    OPEN_EXTERNALLY,

    /** Refuse. The default for everything not explicitly understood (STEP 46, INV-RENDER-17). */
    BLOCKED
}

/**
 * The classification of one URL, with a content-free [reason] token for diagnostics.
 *
 * [scheme] is included because a scheme is the *decision input* and is charset-limited and
 * length-capped; the URL itself is never part of a diagnostic (STEP 54/§124 — a card's links are
 * deck content).
 */
data class AnkiLinkClassification(
    val decision: AnkiLinkDecision,
    val scheme: String?,
    val reason: String
) {
    val isBlocked: Boolean get() = decision == AnkiLinkDecision.BLOCKED
    val leavesTheApp: Boolean get() = decision == AnkiLinkDecision.OPEN_EXTERNALLY
}

/**
 * One mediated external-link request, sent **upward** to the Study surface (STEP 86).
 *
 * The renderer reports; it does not decide how the app opens links and it does not command anything
 * (INV-ANKI-RENDER-03/04). The URL travels here because the app needs it to open a browser — it is
 * never written to logs or diagnostics by the renderer.
 */
data class AnkiExternalLinkRequest(
    val url: String,
    val requestId: AnkiRenderRequestId,
    val classification: AnkiLinkClassification
)

/**
 * GATE 08 — the navigation policy for card documents (STEP 44-§49, INV-ANKI-RENDER-16/17).
 *
 * Pure and total: it never throws on a hostile URL, never touches `android.net.Uri` (so it is
 * JVM-testable), and every input lands in exactly one of the three decisions.
 *
 * | Input | Decision | Why |
 * |---|---|---|
 * | `#fragment` | [AnkiLinkDecision.ALLOW_IN_PAGE] | a safe same-document anchor (STEP 47) |
 * | `http(s)://<other host>` | [AnkiLinkDecision.OPEN_EXTERNALLY] | mediated, never in-reviewer (STEP 45) |
 * | `http(s)://card.studyagent.invalid/…` | [AnkiLinkDecision.BLOCKED] | the renderer's own reserved origin: a *relative* card link, which has nowhere to go in GATE 08 — opening a browser at an unresolvable host would be worse than refusing |
 * | `mailto:` `tel:` `sms:` `geo:` | [AnkiLinkDecision.BLOCKED] | plausible, but not a supported card affordance in this gate; explicitly mediated in GATE 09 |
 * | `javascript:` `data:` `blob:` `about:` | [AnkiLinkDecision.BLOCKED] | script/URL-scheme injection surfaces |
 * | `file:` `content:` `intent:` `market:` and every other scheme | [AnkiLinkDecision.BLOCKED] | unknown schemes are not automatically trusted (STEP 46, INV-RENDER-17) |
 * | empty / scheme-less / unparseable | [AnkiLinkDecision.BLOCKED] | fail closed, never crash |
 *
 * Nothing here launches an Intent: the policy classifies, the app decides (`AnkiExternalLinkHandler`
 * in the UI layer).
 */
object AnkiCardLinkPolicy {

    /** Empty URL. */
    const val REASON_EMPTY: String = "empty_url"

    /** Same-document fragment. */
    const val REASON_ANCHOR: String = "same_document_anchor"

    /** Ordinary external web link, mediated upward. */
    const val REASON_EXTERNAL_WEB: String = "external_web_link"

    /** A link back into the renderer's own reserved origin (a relative card reference). */
    const val REASON_RENDERER_ORIGIN: String = "renderer_origin_navigation"

    /** No scheme at all, or a scheme-less relative reference the callback should not have produced. */
    const val REASON_NO_SCHEME: String = "no_scheme"

    /** An http(s) URL whose host could not be parsed. */
    const val REASON_UNPARSEABLE: String = "unparseable_url"

    /** Prefix for "this scheme is not supported in GATE 08" (`unsupported_scheme_file`). */
    const val REASON_UNSUPPORTED_SCHEME_PREFIX: String = "unsupported_scheme_"

    /** Maximum scheme length carried into a diagnostics token. */
    private const val MAX_SCHEME_TOKEN_CHARS: Int = 16

    /**
     * The host of [AnkiCardDocument.BASE_URL], derived rather than duplicated so the policy and the
     * document builder can never disagree about what "our own origin" means.
     */
    val cardOriginHost: String? = runCatching { URI(AnkiCardDocument.BASE_URL).host?.lowercase() }.getOrNull()

    fun classify(url: String?): AnkiLinkClassification {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return AnkiLinkClassification(AnkiLinkDecision.BLOCKED, scheme = null, reason = REASON_EMPTY)
        }
        if (candidate.startsWith("#")) {
            return AnkiLinkClassification(AnkiLinkDecision.ALLOW_IN_PAGE, scheme = null, reason = REASON_ANCHOR)
        }

        val scheme = schemeOf(candidate)
            ?: return AnkiLinkClassification(AnkiLinkDecision.BLOCKED, scheme = null, reason = REASON_NO_SCHEME)

        if (scheme != "http" && scheme != "https") {
            return AnkiLinkClassification(
                decision = AnkiLinkDecision.BLOCKED,
                scheme = scheme,
                reason = REASON_UNSUPPORTED_SCHEME_PREFIX + scheme.take(MAX_SCHEME_TOKEN_CHARS)
            )
        }

        val host = hostOf(candidate)
            ?: return AnkiLinkClassification(
                decision = AnkiLinkDecision.BLOCKED,
                scheme = scheme,
                reason = REASON_UNPARSEABLE
            )

        val origin = cardOriginHost
        if (origin != null && host == origin) {
            return AnkiLinkClassification(
                decision = AnkiLinkDecision.BLOCKED,
                scheme = scheme,
                reason = REASON_RENDERER_ORIGIN
            )
        }

        return AnkiLinkClassification(
            decision = AnkiLinkDecision.OPEN_EXTERNALLY,
            scheme = scheme,
            reason = REASON_EXTERNAL_WEB
        )
    }

    /**
     * The lowercased scheme of [url], or `null` when there is none.
     *
     * Hand-rolled rather than `URI(...).scheme` because `URI` throws on the malformed and hostile
     * inputs a WebView actually forwards, and a classifier that throws would turn a bad link into a
     * crash (STEP 46 must fail closed, not fail loudly).
     */
    fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        for (index in 0 until colon) {
            val character = url[index]
            // A scheme ends at the first ':' — but only if no path/query/fragment started first.
            if (character == '/' || character == '?' || character == '#') return null
        }
        val candidate = url.substring(0, colon)
        if (candidate.isEmpty()) return null
        if (!candidate[0].isLetter()) return null
        if (!candidate.all { it.isLetterOrDigit() || it == '+' || it == '.' || it == '-' }) return null
        return candidate.lowercase()
    }

    /** The lowercased host of an absolute URL, or `null` when it cannot be parsed. */
    fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}
