package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.AnkiCardRenderMode
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiJavascriptPolicy
import com.studyagent.client.core.render.CardTextDirection
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing
import com.studyagent.client.ui.theme.StudyAgentTheme

/**
 * GATE 08 STEP 143 — the controlled preview surface for the renderer.
 *
 * These previews exist so the card surface can be looked at without a study session, a backend or a
 * device attached to AnkiDroid: they drive [AnkiCardRenderer] with the STEP 98 fixture deck and nothing
 * else. The production `StudyScreen` is deliberately **not** rewired in this gate — installing
 * `AnkiRenderedCard` into the live review flow is GATE 10's `StudySessionMachine` work.
 *
 * A WebView inside a static Compose preview paints nothing useful, so the value here is the fallback
 * and CLEAN-mode surfaces plus the layout contract (bounded card area, notice placement). Real WebView
 * behaviour is proven by the instrumented suite, never by a preview (STEP 101).
 */
private val previewTurn = ReviewTurnId("preview:render-fixtures:1")

@Preview(name = "Renderer — basic question (ORIGINAL)", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun AnkiCardRendererBasicQuestionPreview() {
    PreviewRenderer(AnkiCardRenderFixtures.BASIC, AnkiCardSide.QUESTION)
}

@Preview(name = "Renderer — basic answer (ORIGINAL)", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun AnkiCardRendererBasicAnswerPreview() {
    PreviewRenderer(AnkiCardRenderFixtures.BASIC, AnkiCardSide.ANSWER)
}

@Preview(name = "Renderer — Arabic RTL", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun AnkiCardRendererArabicPreview() {
    PreviewRenderer(AnkiCardRenderFixtures.ARABIC_RTL, AnkiCardSide.QUESTION)
}

@Preview(name = "Renderer — wide table", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun AnkiCardRendererWideTablePreview() {
    PreviewRenderer(AnkiCardRenderFixtures.WIDE_TABLE, AnkiCardSide.QUESTION)
}

@Preview(name = "Renderer — CLEAN mode over the text channel", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun AnkiCardRendererCleanModePreview() {
    PreviewRenderer(
        AnkiCardRenderFixtures.HTML_FORMATTING,
        AnkiCardSide.ANSWER,
        config = AnkiCardRenderConfig.DARK_APP.copy(mode = AnkiCardRenderMode.CLEAN)
    )
}

@Preview(name = "Renderer — text-only card falls back to CLEAN", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun AnkiCardRendererTextOnlyFallbackPreview() {
    PreviewRenderer(AnkiCardRenderFixtures.TEXT_ONLY, AnkiCardSide.QUESTION)
}

@Preview(name = "Renderer — answer side with no content", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun AnkiCardRendererNoContentPreview() {
    PreviewRenderer(AnkiCardRenderFixtures.ANSWER_WITHOUT_CONTENT, AnkiCardSide.ANSWER)
}

@Preview(name = "Clean card view — RTL", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun CleanAnkiCardViewRtlPreview() {
    StudyAgentTheme {
        CleanAnkiCardView(
            text = "من ٦٠ إلى ١٠٠ نبضة في الدقيقة.",
            side = AnkiCardSide.ANSWER,
            direction = CardTextDirection.RTL,
            modifier = Modifier.padding(AppSpacing.MD)
        )
    }
}

@Preview(name = "Clean card view — degraded", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun CleanAnkiCardViewDegradedPreview() {
    StudyAgentTheme {
        CleanAnkiCardView(
            text = "Which nerve is compressed in carpal tunnel syndrome?",
            side = AnkiCardSide.QUESTION,
            degraded = true,
            modifier = Modifier.padding(AppSpacing.MD)
        )
    }
}

@Composable
private fun PreviewRenderer(
    fixture: AnkiCardRenderFixtures.Fixture,
    side: AnkiCardSide,
    config: AnkiCardRenderConfig = AnkiCardRenderConfig.DARK_APP.copy(
        javascriptPolicy = AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY,
        direction = CardTextDirection.AUTO
    )
) {
    StudyAgentTheme {
        Column(modifier = Modifier.fillMaxSize().padding(AppSpacing.MD)) {
            Text(
                text = fixture.label,
                color = AppColors.contentSecondary,
                modifier = Modifier.padding(bottom = AppSpacing.SM)
            )
            AnkiCardRenderer(
                card = fixture.card,
                turnId = previewTurn,
                side = side,
                config = config,
                modifier = Modifier.height(360.dp)
            )
        }
    }
}
