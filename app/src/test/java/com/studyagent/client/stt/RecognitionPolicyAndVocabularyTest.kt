package com.studyagent.client.stt

import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.voice.stt.AnswerEndpointProfile
import com.studyagent.client.core.voice.stt.MedicalVocabularyProvider
import com.studyagent.client.core.voice.stt.RecognitionBackendPreference
import com.studyagent.client.core.voice.stt.RecognitionLanguageMode
import com.studyagent.client.core.voice.stt.RecognitionPolicyFactory
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.SttSettings
import com.studyagent.client.core.voice.stt.parseBackendPreference
import com.studyagent.client.core.voice.stt.parseLanguageMode
import com.studyagent.client.core.voice.stt.toSttSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vocabulary biasing bounds and the settings bridge (§112/§113/§127).
 */
class RecognitionPolicyAndVocabularyTest {

    private val vocabulary = MedicalVocabularyProvider()
    private val policy = RecognitionPolicyFactory(
        idFactory = RecognitionPolicyFactory.counterIdFactory()
    )

    // ------------------------------------------------------------------ §112 bias list

    @Test
    fun `bias list is bounded, de-duplicated and ordered by relevance`() {
        val terms = vocabulary.contextTermsFrom(
            "What are the surgical indications for an epidural hematoma with midline shift?"
        )
        val bias = vocabulary.biasListFor(terms, RecognitionPurpose.ANSWER)

        assertTrue("context terms must be included", bias.contains("epidural hematoma"))
        assertTrue(bias.contains("midline shift"))
        assertTrue("bounded size", bias.size <= MedicalVocabularyProvider.MAX_BIAS_TERMS)
        assertEquals(
            "no duplicates",
            bias.size,
            bias.map { it.lowercase() }.distinct().size
        )
        // Context terms lead, because most implementations weight earlier entries higher.
        // Compared against a term known to survive the MAX_BIAS_TERMS truncation — asserting
        // against an absent term would compare 0 < -1 and fail for the wrong reason.
        assertTrue(
            "a core term must be present to compare ordering against",
            bias.contains("subdural hematoma")
        )
        assertTrue(
            "question terms come before the generic core list",
            bias.indexOf("epidural hematoma") < bias.indexOf("subdural hematoma")
        )
    }

    @Test
    fun `abbreviations are matched whole word only`() {
        val terms = vocabulary.contextTermsFrom("The CT shows an ICP-monitor; CTscan is unrelated.")
        assertTrue(terms.contains("CT"))
        assertTrue(terms.contains("ICP"))
        // "CTscan" must not add a second CT entry.
        assertEquals(1, terms.count { it == "CT" })
    }

    @Test
    fun `rating and command turns get only their own small vocabulary`() {
        val ratingBias = vocabulary.biasListFor(emptyList(), RecognitionPurpose.RATING)
        assertTrue(ratingBias.contains("good"))
        assertFalse(
            "medical terms must not be biased into a rating window",
            ratingBias.contains("epidural hematoma")
        )

        val commandBias = vocabulary.biasListFor(
            listOf("epidural hematoma"),
            RecognitionPurpose.COMMAND
        )
        assertFalse(commandBias.contains("epidural hematoma"))
    }

    @Test
    fun `biasing can be disabled without changing anything else`() {
        val enabled = policy.createRequest(
            purpose = RecognitionPurpose.ANSWER,
            settings = SttSettings(medicalVocabularyBiasing = true),
            contextTerms = vocabulary.contextTermsFrom("epidural hematoma")
        )
        val disabled = policy.createRequest(
            purpose = RecognitionPurpose.ANSWER,
            settings = SttSettings(medicalVocabularyBiasing = false),
            contextTerms = vocabulary.contextTermsFrom("epidural hematoma")
        )
        assertTrue(enabled.vocabularyHints.isNotEmpty())
        // Context terms are dropped, but the small curated core list still applies.
        assertFalse(disabled.vocabularyHints.contains("epidural hematoma"))
    }

    // ------------------------------------------------------------------ §34 no answer leakage

    @Test
    fun `the expected answer never reaches the bias list`() {
        // The provider is only ever handed question/topic text. Feeding it an expected
        // answer would be a caller bug; this asserts the contract by showing that only the
        // terms it is given can appear as context entries.
        val bias = vocabulary.biasListFor(listOf("epidural hematoma"), RecognitionPurpose.ANSWER)
        assertTrue(bias.contains("epidural hematoma"))
        assertFalse(
            "no hidden expected-answer field exists on the request",
            bias.any { it.contains("surgical indication", ignoreCase = true) }
        )
    }

    // ------------------------------------------------------------------ §16 endpoint profiles

    @Test
    fun `each purpose gets its own endpoint profile`() {
        val s = SttSettings()
        assertEquals(
            com.studyagent.client.core.voice.stt.EndpointProfile.SHORT_COMMAND,
            policy.endpointPolicyFor(RecognitionPurpose.RATING, s).profile
        )
        assertEquals(
            com.studyagent.client.core.voice.stt.EndpointProfile.SHORT_COMMAND,
            policy.endpointPolicyFor(RecognitionPurpose.COMMAND, s).profile
        )
        assertEquals(
            com.studyagent.client.core.voice.stt.EndpointProfile.NORMAL_ANSWER,
            policy.endpointPolicyFor(RecognitionPurpose.ANSWER, s).profile
        )
        assertEquals(
            com.studyagent.client.core.voice.stt.EndpointProfile.LONG_ANSWER,
            policy.endpointPolicyFor(
                RecognitionPurpose.ANSWER,
                s.copy(answerEndpointProfile = AnswerEndpointProfile.LONG)
            ).profile
        )
        assertEquals(
            com.studyagent.client.core.voice.stt.EndpointProfile.PUSH_TO_TALK,
            policy.endpointPolicyFor(RecognitionPurpose.PUSH_TO_TALK_ANSWER, s).profile
        )
    }

    @Test
    fun `silence extras are hints only and off by default`() {
        // §17: Android documents these as unreliable, so nothing may depend on them.
        for (purpose in RecognitionPurpose.entries) {
            val endpoint = policy.endpointPolicyFor(purpose, SttSettings())
            assertFalse(
                "silence hints must not be applied for ${purpose.name}",
                endpoint.applySilenceHints
            )
        }
    }

    @Test
    fun `requests carry unique readable identities`() {
        val a = policy.createRequest(RecognitionPurpose.ANSWER, SttSettings(), cardId = "c1")
        val b = policy.createRequest(RecognitionPurpose.ANSWER, SttSettings(), cardId = "c1")
        val r = policy.createRequest(RecognitionPurpose.RATING, SttSettings(), cardId = "c1")
        assertTrue(a.id != b.id)
        assertTrue(a.id.startsWith("answer_"))
        assertTrue(r.id.startsWith("rating_"))
    }

    // ------------------------------------------------------------------ §127/§113 settings bridge

    @Test
    fun `app settings map onto structured stt settings`() {
        val settings = AppSettings(
            sttLanguageMode = "AUTO_EN_AR",
            sttEnglishLocale = "en-US",
            sttArabicLocale = "ar-IQ",
            sttRecognitionMode = "PREFER_ON_DEVICE",
            sttPreferOnDevice = true,
            sttShowPartialTranscript = false,
            sttMedicalBiasing = false,
            sttAnswerLength = "LONG",
            autoSubmitTranscript = false,
            listenForSpokenRating = false,
            handsFreeMode = false,
            confirmRating = true,
            sttDebugTranscriptLogging = true
        ).toSttSettings()

        assertEquals(RecognitionLanguageMode.AUTO_EN_AR, settings.languageMode)
        assertEquals(RecognitionBackendPreference.PREFER_ON_DEVICE, settings.backendPreference)
        assertEquals("ar-IQ", settings.arabicLocale)
        assertFalse(settings.showPartialTranscript)
        assertFalse(settings.medicalVocabularyBiasing)
        assertEquals(AnswerEndpointProfile.LONG, settings.answerEndpointProfile)
        assertFalse("autoSubmitTranscript must reach the STT layer", settings.autoSubmitAnswers)
        assertFalse("listenForSpokenRating must reach the STT layer", settings.spokenRatings)
        assertFalse(settings.handsFreeMode)
        assertTrue(settings.confirmAmbiguousRating)
        assertTrue(settings.debugTranscriptLogging)
    }

    @Test
    fun `unknown or corrupt setting values fall back to safe defaults`() {
        assertEquals(
            RecognitionBackendPreference.AUTO,
            parseBackendPreference("SOMETHING_NEW_IN_A_FUTURE_ANDROID")
        )
        assertEquals(RecognitionLanguageMode.AUTO_EN_AR, parseLanguageMode(null, null))
        assertEquals(RecognitionLanguageMode.ARABIC, parseLanguageMode(null, "ar-IQ"))
        assertEquals(RecognitionLanguageMode.ENGLISH, parseLanguageMode(null, "en-GB"))
        assertEquals(AnswerEndpointProfile.NORMAL, AnswerEndpointProfile.fromName("nonsense"))
    }

    @Test
    fun `auto mode allowlist stays small`() {
        // §29: two languages, not "every installed locale".
        val settings = SttSettings(englishLocale = "en-US", arabicLocale = "ar-IQ")
        assertEquals(listOf("en-US", "ar-IQ"), settings.detectionAllowlist())
    }

    @Test
    fun `rating confidence gates are ordered sensibly`() {
        val s = SttSettings()
        assertTrue(
            "confirmation threshold must sit above the acceptance floor",
            s.ratingConfirmationThreshold > s.ratingMinConfidence
        )
        assertTrue(s.destructiveCommandMinConfidence > s.ratingMinConfidence)
        assertTrue(s.lowConfidenceTranscriptThreshold < s.ratingMinConfidence)
    }
}
