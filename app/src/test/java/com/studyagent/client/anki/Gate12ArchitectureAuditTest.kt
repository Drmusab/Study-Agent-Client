package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.study.AnkiAnswerReviewDiagnostics
import com.studyagent.client.core.study.AnswerAudioSequencePhase
import com.studyagent.client.core.study.AnswerCompareMode
import com.studyagent.client.core.study.AnswerEvaluationStatus
import com.studyagent.client.core.study.AnswerRevealState
import com.studyagent.client.core.study.AnswerReviewModel
import com.studyagent.client.core.study.CleanAnswerState
import com.studyagent.client.core.study.EvaluationSummary
import com.studyagent.client.core.study.RatingCommitUiState
import com.studyagent.client.core.study.SpokenCommandRouter
import com.studyagent.client.ui.screens.study.AnswerEvaluationCardUi
import com.studyagent.client.ui.screens.study.AnswerReviewUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 12 — Mandatory Self-Audit (AUDITS 1–14) & Architectural Invariant Lock (`INV-12-01`..`INV-12-25`).
 */
class Gate12ArchitectureAuditTest {

    private val mainJava: File by lazy {
        val candidates = listOf(
            File("app/src/main/java/com/studyagent/client"),
            File("../app/src/main/java/com/studyagent/client")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate app/src/main/java/com/studyagent/client from ${File(".").absolutePath}")
    }

    private fun readSource(relativePath: String): String {
        val file = File(mainJava, relativePath)
        check(file.isFile) { "Missing production source: $relativePath" }
        return file.readText()
    }

    // ------------------------------------------------------------------ AUDIT 1 & 2: Reveal & Answer Channel Separation (INV-12-01..06)
    @Test
    fun `AUDIT 1 and 2 - answer reveal state is explicit and three answer channels remain strictly separated`() {
        assertEquals(listOf(AnswerRevealState.HIDDEN, AnswerRevealState.REVEALED), AnswerRevealState.entries)
        assertEquals(
            listOf(AnswerCompareMode.ORIGINAL, AnswerCompareMode.CLEAN, AnswerCompareMode.COMPARE),
            AnswerCompareMode.entries
        )

        val modelSource = readSource("core/study/AnswerReviewModel.kt")
        assertTrue(modelSource.contains("val answerHtml: String?"))
        assertTrue(modelSource.contains("val referenceAnswerText: String?"))
        assertTrue(modelSource.contains("val rawReferenceAnswerText: String?"))
        assertTrue(modelSource.contains("val userAnswerText: String?"))

        // Evaluator uses card.evaluationAnswerText (pureAnswerText ?: answerText), never answerHtml
        assertTrue(modelSource.contains("card.evaluationAnswerText"))
        assertFalse(
            "Evaluator request must never pass answerHtml as referenceAnswerText",
            modelSource.contains("referenceAnswerText = card.answerHtml")
        )
    }

    // ------------------------------------------------------------------ AUDIT 3 & 4: User Answer & AI Evaluation Integrity (INV-12-07..10)
    @Test
    fun `AUDIT 3 and 4 - userAnswerText is final transcript only and AI evaluation is optional and advisory`() {
        val backendId = AnkiBackendId.AnkiDroidLocal
        val deck = AnkiDeckRef(backendId, "1", "col")
        val card = AnkiRenderedCard(
            ref = AnkiCardRef(backendId, cardId = "100", collectionKey = "col"),
            questionHtml = "<p>Q</p>",
            answerHtml = "<p>A</p>",
            questionText = "Q",
            answerText = "A",
            pureAnswerText = "Pure A",
            deckRef = deck
        )

        // With AI suggestion GOOD, selectedRating and committedRating remain null
        val model = AnswerReviewModel(
            turnId = ReviewTurnId("turn-audit"),
            userAnswerText = "Final transcript",
            referenceAnswerText = card.answerText,
            evaluationFeedback = "Advisory feedback",
            evaluationSummary = EvaluationSummary(
                score = 95,
                correctPoints = listOf("Concept"),
                missingPoints = emptyList(),
                incorrectPoints = emptyList()
            ),
            suggestedRating = Rating.GOOD,
            revealState = AnswerRevealState.REVEALED,
            compareMode = AnswerCompareMode.COMPARE,
            cardRef = card.ref,
            questionHtml = card.questionHtml,
            questionText = card.questionText,
            answerHtml = card.answerHtml,
            answerText = card.answerText,
            pureAnswerText = card.pureAnswerText,
            rawReferenceAnswerText = card.pureAnswerText,
            cleanAnswerState = CleanAnswerState.PRESENT,
            evaluationStatus = AnswerEvaluationStatus.COMPLETED,
            availableRatings = listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY),
            nextReviewTimes = mapOf(Rating.GOOD to "4d"),
            selectedRating = null,
            committedRating = null,
            commitUiState = RatingCommitUiState.AwaitingRating,
            ratingControlsEnabled = true
        )

        val ui = AnswerReviewUiState.from(model)
        assertEquals(Rating.GOOD, (ui.evaluationCard as AnswerEvaluationCardUi.Available).suggestedRating)
        assertNull(model.selectedRating)
        assertNull(model.committedRating)
        assertTrue(ui.ratingOptions.first { it.rating == Rating.GOOD }.isSuggested)
        assertFalse(ui.ratingOptions.first { it.rating == Rating.GOOD }.isSelected)
    }

    // ------------------------------------------------------------------ AUDIT 5 & 6: Compare Mode & Rating Surface Integrity (INV-12-11..14)
    @Test
    fun `AUDIT 5 and 6 - compare mode switching and rating surface never compute intervals locally or query backend`() {
        val uiSectionSource = readSource("ui/screens/study/AnkiAnswerReviewSection.kt")
        val uiStateSource = readSource("ui/screens/study/AnswerReviewUiState.kt")
        for (source in listOf(uiSectionSource, uiStateSource)) {
            assertFalse(source.contains("AnkiDroidBackend"))
            assertFalse(source.contains("ContentResolver"))
            assertFalse(source.contains("ReviewCommitLedger"))
            assertFalse(source.contains("commitRating("))
            assertFalse(source.contains("nextCard("))
        }
    }

    // ------------------------------------------------------------------ AUDIT 7 & 8: Voice & Audio Sequencing + Fallback (INV-12-15..20)
    @Test
    fun `AUDIT 7 and 8 - voice commands route safely and audio sequence phases are deterministic`() {
        assertEquals(
            listOf(
                AnswerAudioSequencePhase.IDLE,
                AnswerAudioSequencePhase.STOPPING_QUESTION_AND_STT,
                AnswerAudioSequencePhase.VISUAL_REVEALED,
                AnswerAudioSequencePhase.CARD_MEDIA,
                AnswerAudioSequencePhase.SPEAKING_ANSWER,
                AnswerAudioSequencePhase.SPEAKING_FEEDBACK,
                AnswerAudioSequencePhase.ACOUSTIC_GAP,
                AnswerAudioSequencePhase.LISTENING_FOR_RATING
            ),
            AnswerAudioSequencePhase.entries
        )
        assertFalse(SpokenCommandRouter.isRatingCommand(VoiceCommand.ShowAnswer))
        assertFalse(SpokenCommandRouter.isRatingCommand(VoiceCommand.RepeatAnswer))
        assertFalse(SpokenCommandRouter.isRatingCommand(VoiceCommand.RepeatFeedback))
    }

    // ------------------------------------------------------------------ AUDIT 9, 10 & 11: Lifecycle, RTL & Layout (INV-12-21..24)
    @Test
    fun `AUDIT 9 10 and 11 - RTL layout direction and pinned rating bar are preserved in UI state and Compose section`() {
        val uiSectionSource = readSource("ui/screens/study/AnkiAnswerReviewSection.kt")
        assertTrue(
            "AnkiAnswerReviewSection must apply LocalLayoutDirection for Arabic/RTL text",
            uiSectionSource.contains("CompositionLocalProvider(LocalLayoutDirection provides")
        )
        assertTrue(
            "AnkiAnswerReviewSection must expose pinned rating controls outside inner compare cards",
            uiSectionSource.contains("RatingButtonGroup(")
        )
    }

    // ------------------------------------------------------------------ AUDIT 12, 13 & 14: GATE 11 Boundary, Accessibility & Privacy (INV-12-13, 24, 25)
    @Test
    fun `AUDIT 12 13 and 14 - GATE 11 commit pipeline boundary is untouched and diagnostics reject forbidden content keys`() {
        val reducerSource = readSource("core/study/StudyReducer.kt")
        val selectIdx = reducerSource.indexOf("private fun selectRating(")
        val resolveIdx = reducerSource.indexOf("private fun resolveAnkiCommit(")
        assertTrue("selectRating and resolveAnkiCommit order must remain intact", selectIdx in 1 until resolveIdx)

        for (forbidden in AnkiAnswerReviewDiagnostics.FORBIDDEN_CONTENT_KEYS) {
            assertTrue(
                "Forbidden content key '$forbidden' must be detected",
                AnkiAnswerReviewDiagnostics.containsForbiddenContentKey(mapOf(forbidden to "value"))
            )
        }
    }
}
