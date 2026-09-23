package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.CardTextDirection
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.SectionHeader
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

/**
 * GATE 08 — Study-Agent's own Compose surface for card *text* (STEP 90/§91).
 *
 * Two jobs, one component, deliberately basic:
 *
 * 1. **The fallback** when ORIGINAL rendering is unavailable or failed (STEP 68/§121). It shows the
 *    backend's own text channel — `questionText` / `answerText` — and nothing else. It never strips
 *    HTML itself, never rebuilds an answer and never invents content (INV-ANKI-RENDER-20, and GATE 07's
 *    rule that missing simple text is never replaced by HTML-derived pseudo-text).
 * 2. **The CLEAN / VOICE_FOCUS presentation** ([com.studyagent.client.core.render.AnkiCardRenderMode]).
 *    The seam is real and selectable; the final design of those modes belongs to a later gate, so this
 *    is honest, readable text with app typography and no chrome beyond a side label (STEP 90/§91).
 *
 * Accessibility differs from ORIGINAL *by design* (STEP 39): this surface is Compose text, so it
 * inherits the app's typography, the system font scale and TalkBack's text semantics directly, while
 * ORIGINAL prioritises the template's authored appearance inside a WebView.
 *
 * Direction (STEP 35/§36): [CardTextDirection.RTL] flips the layout direction; [CardTextDirection.AUTO]
 * leaves it to Compose and Unicode bidi, which resolve an Arabic paragraph right-to-left on their own —
 * no hard-coded `ltr` anywhere.
 */
@Composable
fun CleanAnkiCardView(
    text: String,
    side: AnkiCardSide,
    modifier: Modifier = Modifier,
    direction: CardTextDirection = CardTextDirection.DEFAULT,
    /** True when this surface stands in for a failed/unavailable ORIGINAL rendering (STEP 69). */
    degraded: Boolean = false
) {
    val layoutDirection = when (direction) {
        CardTextDirection.RTL -> LayoutDirection.Rtl
        CardTextDirection.LTR -> LayoutDirection.Ltr
        CardTextDirection.AUTO -> LocalLayoutDirection.current
    }
    val sideLabel = sideAccessibilityLabel(side, degraded)

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        AppCard(modifier = modifier.semantics { contentDescription = sideLabel }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.cardPadding)
            ) {
                SectionHeader(
                    title = if (side == AnkiCardSide.QUESTION) "Question" else "Answer",
                    color = if (degraded) AppColors.statusWarning else AppColors.voiceSpeaking
                )
                Spacer(modifier = Modifier.height(AppSpacing.XS))
                // STEP 118/§141 — long content scrolls, and text selection stays available: copying a
                // card's text is a legitimate study action, so nothing here disables it.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Top
                ) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.contentPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** One definition of the accessible name of a card side, shared by both presentation surfaces. */
internal fun sideAccessibilityLabel(side: AnkiCardSide, degraded: Boolean = false): String = when (side) {
    AnkiCardSide.QUESTION ->
        if (degraded) "Anki card question, shown as text because the rendered card is unavailable"
        else "Anki card question"

    AnkiCardSide.ANSWER ->
        if (degraded) "Anki card answer, shown as text because the rendered card is unavailable"
        else "Anki card answer"
}

/**
 * The lightweight placeholder shown while a document loads (STEP 67).
 *
 * The caller draws it over an opaque background so the previous card cannot be seen under the new turn
 * while its document is in flight, and only after a short delay so a fast load does not flash a
 * spinner — a clean transition with no stale content.
 */
@Composable
internal fun AnkiRenderPlaceholder(
    modifier: Modifier = Modifier,
    message: String = "Rendering card…",
    /** False for a permanent empty state, where a spinner would promise progress that never comes. */
    showProgress: Boolean = true
) {
    Column(
        modifier = modifier.padding(AppSpacing.LG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = AppColors.voiceSpeaking,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(AppSpacing.SM))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.contentSecondary
        )
    }
}
