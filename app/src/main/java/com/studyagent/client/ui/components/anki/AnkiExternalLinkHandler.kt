package com.studyagent.client.ui.components.anki

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.render.AnkiExternalLinkRequest
import com.studyagent.client.core.render.AnkiLinkDecision

/**
 * What happened when the renderer handed a card link to the app (STEP 45).
 *
 * A card link is never worth crashing for, so every outcome is a value: [Opened], [Refused] (the policy
 * did not allow it — defence in depth behind [AnkiLinkDecision]) or [Failed] (no browser, or the
 * platform refused the start). None of them changes the card, the turn or the session
 * (INV-ANKI-RENDER-03/04).
 */
sealed interface AnkiExternalLinkResult {
    /** Content-free token for diagnostics; never the URL. */
    val token: String

    data object Opened : AnkiExternalLinkResult {
        override val token: String get() = "opened"
    }

    data class Refused(val reason: String) : AnkiExternalLinkResult {
        override val token: String get() = "refused_$reason"
    }

    data class Failed(val category: String) : AnkiExternalLinkResult {
        override val token: String get() = "failed_$category"
    }
}

/**
 * GATE 08 — the safe external-open policy for card links (STEP 44/§45/§47).
 *
 * The reviewer WebView never navigates away from the card: an `http(s)` link a card contains is opened
 * *outside* Study-Agent, in whatever browser the user has, and the card stays exactly as it was. That is
 * the whole job here.
 *
 * Deliberate restrictions:
 *
 * - **`http` and `https` only.** Everything else — `file:`, `content:`, `intent:`, `market:`,
 *   `javascript:`, `data:`, `mailto:`, `tel:` and any custom scheme — is refused before an `Intent` is
 *   even built (STEP 46, INV-ANKI-RENDER-17). `AnkiCardLinkPolicy` already classifies them as blocked;
 *   this is the second, independent check, because launching an arbitrary scheme from a card is exactly
 *   the kind of thing that must not depend on one classifier being right.
 * - **No URI permissions are granted.** No `FLAG_GRANT_READ_URI_PERMISSION`, no `ClipData`, no
 *   `content://` URI: a card gets a browser, not access to our files.
 * - **`CATEGORY_BROWSABLE` is required**, so the URL lands in a browser rather than in some app that
 *   registered a loose filter.
 * - **New task only when the context is not an `Activity`**, so the review screen is never reparented.
 * - **Never throws.** No browser installed is a normal device state.
 *
 * Full network/resource policy — including whether remote assets may load *inside* a card at all — is
 * GATE 09 (STEP 130, INV-ANKI-RENDER-32).
 */
object AnkiExternalLinkHandler {

    private const val TAG = "AnkiExternalLink"

    private const val REASON_POLICY = "policy"
    private const val REASON_SCHEME = "scheme"
    private const val REASON_UNPARSEABLE = "unparseable"
    private const val CATEGORY_NO_BROWSER = "no_browser"
    private const val CATEGORY_SECURITY = "security"
    private const val CATEGORY_UNEXPECTED = "unexpected"

    fun open(context: Context, request: AnkiExternalLinkRequest): AnkiExternalLinkResult {
        if (request.classification.decision != AnkiLinkDecision.OPEN_EXTERNALLY) {
            return refuse(REASON_POLICY)
        }
        val uri = parse(request.url) ?: return refuse(REASON_UNPARSEABLE)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return refuse(REASON_SCHEME)
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            AnkiExternalLinkResult.Opened
        } catch (missing: ActivityNotFoundException) {
            // A device with no browser is a normal state; the card is unaffected.
            AppLogger.w(TAG, "ANKI_RENDER_EXTERNAL_LINK_FAILED category=$CATEGORY_NO_BROWSER scheme=$scheme")
            AnkiExternalLinkResult.Failed(CATEGORY_NO_BROWSER)
        } catch (security: SecurityException) {
            AppLogger.w(TAG, "ANKI_RENDER_EXTERNAL_LINK_FAILED category=$CATEGORY_SECURITY scheme=$scheme")
            AnkiExternalLinkResult.Failed(CATEGORY_SECURITY)
        } catch (unexpected: RuntimeException) {
            // Never let a card link take the review session down (STEP 54's rule, applied to navigation).
            AppLogger.w(
                TAG,
                "ANKI_RENDER_EXTERNAL_LINK_FAILED category=$CATEGORY_UNEXPECTED scheme=$scheme",
                unexpected
            )
            AnkiExternalLinkResult.Failed(CATEGORY_UNEXPECTED)
        }
    }

    /**
     * `Uri.parse` is lenient and effectively never throws, but a card is hostile input: the parse is
     * guarded anyway, and the scheme check that follows is what actually decides.
     */
    private fun parse(url: String): Uri? = try {
        Uri.parse(url)
    } catch (unexpected: RuntimeException) {
        null
    }

    private fun refuse(reason: String): AnkiExternalLinkResult {
        AppLogger.w(TAG, "ANKI_RENDER_EXTERNAL_LINK_REFUSED reason=$reason")
        return AnkiExternalLinkResult.Refused(reason)
    }
}
