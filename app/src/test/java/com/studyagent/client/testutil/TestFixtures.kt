package com.studyagent.client.testutil

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudySession
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.RecognitionError
import com.studyagent.client.core.voice.stt.RecognitionErrorCode
import com.studyagent.client.core.voice.stt.RecognitionHypothesis
import com.studyagent.client.core.voice.stt.RecognitionOutcome
import com.studyagent.client.core.voice.stt.RecognitionPurpose

/**
 * Shared fixtures (§11).
 *
 * Every builder has explicit, readable defaults so an assertion never depends on a value the
 * reader has to go hunting for — and so two tests that both say "a card" are talking about the
 * same card. Tests override only the field they are actually about.
 *
 * These are deliberately *values*, not fakes: no behaviour lives here.
 */
object TestCards {

    fun card(
        id: String = "c1",
        question: String = "What are the indications for evacuation of an epidural hematoma?",
        cardNumber: Int = 1,
        remaining: Int = 9,
        deckName: String? = "Surgery::Neurosurgery"
    ): StudyCard = StudyCard(
        id = id,
        question = question,
        cardNumber = cardNumber,
        remaining = remaining,
        deckName = deckName
    )

    /** The n-th card of a generated deck, used by the multi-card and endurance suites. */
    fun numbered(
        index: Int,
        deckName: String = "Endurance",
        questionPrefix: String = "Question"
    ): StudyCard = StudyCard(
        id = "card-$index",
        question = "$questionPrefix $index: what is the next step?",
        cardNumber = index,
        remaining = (ENDURANCE_DECK_SIZE - index).coerceAtLeast(0),
        deckName = deckName
    )

    /** Deck size for the long-session suites; kept in one place so runs stay comparable. */
    const val ENDURANCE_DECK_SIZE = 1_000
}

object TestEvaluations {

    fun good(
        shortFeedback: String = "Good — you missed midline shift.",
        suggestedRating: Rating = Rating.GOOD
    ): Evaluation = Evaluation(shortFeedback = shortFeedback, suggestedRating = suggestedRating)

    fun again(shortFeedback: String = "Not quite — the epidural space is not the target."): Evaluation =
        Evaluation(shortFeedback = shortFeedback, suggestedRating = Rating.AGAIN)

    /** An evaluation whose feedback is blank: the reducer must not open a speech phase for it. */
    fun silent(): Evaluation = Evaluation(shortFeedback = "", suggestedRating = null)
}

object TestSessions {

    fun session(
        sessionId: String = "s1",
        deckName: String = "Toronto Notes",
        card: StudyCard? = TestCards.card(),
        remaining: Int = 9,
        totalCards: Int? = 10,
        reviewed: Int = 0
    ): StudySession = StudySession(
        sessionId = sessionId,
        deckName = deckName,
        currentCard = card,
        cardNumber = card?.cardNumber ?: 0,
        remainingCards = remaining,
        totalReviewedInSession = reviewed,
        totalCardsInQueue = totalCards
    )
}

object TestSettings {

    /** Study defaults with the noisy developer switches off. */
    fun studyDefaults(
        autoPlayQuestion: Boolean = true,
        autoPlayFeedback: Boolean = true,
        listenForSpokenRating: Boolean = true,
        handsFreeMode: Boolean = true,
        autoSubmitTranscript: Boolean = true,
        acousticGapMs: Int = 450
    ): AppSettings = AppSettings(
        handsFreeMode = handsFreeMode,
        autoPlayQuestion = autoPlayQuestion,
        autoPlayFeedback = autoPlayFeedback,
        autoSubmitTranscript = autoSubmitTranscript,
        listenForSpokenRating = listenForSpokenRating,
        ttsAcousticGapMs = acousticGapMs,
        debugLogging = false,
        sttDebugTranscriptLogging = false,
        useFakeAgent = false
    )
}

object TestRoutes {

    val phone: AudioRouteSnapshot = AudioRouteSnapshot.PHONE_ONLY
    val wired: AudioRouteSnapshot = AudioRouteSnapshot.WIRED_HEADSET
    val bluetooth: AudioRouteSnapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET
}

object TestCapabilities {

    fun available(
        onDevice: Boolean = true,
        languages: Set<String> = setOf("en-US", "ar-IQ"),
        languageDetection: Boolean? = true
    ): RecognitionCapabilities = RecognitionCapabilities(
        recognitionAvailable = true,
        onDeviceAvailable = onDevice,
        supportedLanguages = languages,
        installedLanguages = languages,
        languageDetectionSupported = languageDetection,
        languageSwitchSupported = languageDetection,
        vocabularyBiasingSupported = true
    )

    /** A device whose recognizer exists but whose model state is unknown (§66). */
    fun unknownCapabilities(): RecognitionCapabilities = RecognitionCapabilities(recognitionAvailable = true)
}

object TestTranscripts {

    /** A clinical-looking answer: used to prove transcripts never reach logs or exports (§86). */
    const val CLINICAL_ANSWER = "My patient has a left-sided subdural hematoma with midline shift"

    const val MEDICAL_ANSWER = "Evacuation is indicated if the hematoma exceeds thirty millilitres"

    fun hypotheses(text: String = MEDICAL_ANSWER, confidence: Float? = 0.92f): List<RecognitionHypothesis> =
        listOf(RecognitionHypothesis(text = text, confidence = confidence, rank = 0))

    fun outcome(
        requestId: String = "stt-1",
        purpose: RecognitionPurpose = RecognitionPurpose.ANSWER,
        cardId: String? = "c1",
        text: String = MEDICAL_ANSWER,
        confidence: Float? = 0.92f
    ): RecognitionOutcome {
        val hypothesis = RecognitionHypothesis(text = text, confidence = confidence, rank = 0)
        return RecognitionOutcome(
            requestId = requestId,
            purpose = purpose,
            cardId = cardId,
            hypotheses = listOf(hypothesis),
            selectedText = text,
            selectedHypothesis = hypothesis
        )
    }

    fun error(
        code: RecognitionErrorCode = RecognitionErrorCode.NO_SPEECH,
        requestId: String? = "stt-1"
    ): RecognitionError = RecognitionError(code, requestId)
}
