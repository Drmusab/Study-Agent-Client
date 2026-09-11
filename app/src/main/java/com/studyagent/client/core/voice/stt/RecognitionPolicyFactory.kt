package com.studyagent.client.core.voice.stt

/**
 * Turns a (purpose, settings, card) triple into a fully-specified [RecognitionRequest].
 *
 * This is the single place where purpose-specific behaviour is decided, so "an answer and
 * a rating are different tasks" is enforced by construction rather than by callers
 * remembering to pass the right flags. Study code asks for a *purpose*; it never hand-builds
 * a recognizer configuration.
 */
class RecognitionPolicyFactory(
    private val idFactory: (String) -> String = ::defaultRequestId
) {

    /** Watchdog budgets per purpose (§93). Internal policy, not user-exposed. */
    fun timeoutsFor(purpose: RecognitionPurpose, settings: SttSettings): RecognitionTimeouts =
        when (purpose) {
            RecognitionPurpose.RATING,
            RecognitionPurpose.SHORT_CONFIRMATION -> RecognitionTimeouts(
                readyMs = 4_000L,
                firstSpeechMs = 6_000L,
                finalResultMs = 4_000L,
                totalMs = 15_000L
            )

            RecognitionPurpose.COMMAND,
            RecognitionPurpose.PUSH_TO_TALK_COMMAND -> RecognitionTimeouts(
                readyMs = 4_000L,
                firstSpeechMs = 8_000L,
                finalResultMs = 5_000L,
                totalMs = 20_000L
            )

            RecognitionPurpose.DECK_SELECTION -> RecognitionTimeouts(
                readyMs = 4_000L,
                firstSpeechMs = 10_000L,
                finalResultMs = 6_000L,
                totalMs = 30_000L
            )

            RecognitionPurpose.ANSWER -> when (settings.answerEndpointProfile) {
                AnswerEndpointProfile.SHORT -> RecognitionTimeouts(
                    readyMs = 4_000L,
                    firstSpeechMs = 10_000L,
                    finalResultMs = 7_000L,
                    totalMs = 40_000L
                )

                AnswerEndpointProfile.NORMAL -> RecognitionTimeouts.DEFAULT

                AnswerEndpointProfile.LONG -> RecognitionTimeouts(
                    readyMs = 4_000L,
                    firstSpeechMs = 20_000L,
                    finalResultMs = 12_000L,
                    totalMs = 120_000L
                )
            }

            // Push-to-talk is bounded by the user's finger, so the watchdog only guards
            // against a recognizer that never finalises after release.
            RecognitionPurpose.PUSH_TO_TALK_ANSWER -> RecognitionTimeouts(
                readyMs = 4_000L,
                firstSpeechMs = 60_000L,
                finalResultMs = 10_000L,
                totalMs = 300_000L
            )
        }

    fun endpointPolicyFor(purpose: RecognitionPurpose, settings: SttSettings): EndpointPolicy {
        val profile = when (purpose) {
            RecognitionPurpose.RATING,
            RecognitionPurpose.SHORT_CONFIRMATION,
            RecognitionPurpose.COMMAND,
            RecognitionPurpose.PUSH_TO_TALK_COMMAND -> EndpointProfile.SHORT_COMMAND

            RecognitionPurpose.DECK_SELECTION -> EndpointProfile.NORMAL_ANSWER

            RecognitionPurpose.ANSWER -> settings.answerEndpointProfile.toEndpointProfile()

            RecognitionPurpose.PUSH_TO_TALK_ANSWER -> EndpointProfile.PUSH_TO_TALK
        }
        // Hints are attached but disabled by default: Android documents the silence
        // extras as unreliable, so nothing here may depend on them being honoured (§17).
        return EndpointPolicy.platformDefault(profile)
    }

    /**
     * Build the request for one turn.
     *
     * @param contextTerms question/topic words used for vocabulary biasing. Must never
     *   contain the expected answer (§34).
     */
    fun createRequest(
        purpose: RecognitionPurpose,
        settings: SttSettings,
        cardId: String? = null,
        contextTerms: List<String> = emptyList(),
        vocabulary: MedicalVocabularyProvider = MedicalVocabularyProvider.DEFAULT,
        createdAtMs: Long = System.currentTimeMillis()
    ): RecognitionRequest {
        // Every purpose honours the user's language mode: ratings and commands use a
        // controlled bilingual grammar, so AUTO lets "جيد" and "Good" both work.
        val languageMode = settings.languageMode

        // Two separate rules, and they matter:
        //
        //  1. Context terms only bias answer-like turns. Commands get the small, fixed
        //     command vocabulary — biasing them with medical terms would make a command
        //     window *more* likely to hear medical prose, which is exactly the wrong risk.
        //
        //  2. "Medical vocabulary biasing" off means *no* medical vocabulary reaches the
        //     recognizer — not merely the question-derived terms. Dropping only the context
        //     terms would leave the curated core list active, so the toggle would appear to
        //     do nothing to a user who turned it off. The rating/command vocabularies are
        //     not medical terms and are essential to spoken control, so they are untouched.
        val hints = if (purpose.isAnswerLike && !settings.medicalVocabularyBiasing) {
            emptyList()
        } else {
            vocabulary.biasListFor(
                contextTerms = if (purpose.isAnswerLike) contextTerms else emptyList(),
                purpose = purpose
            )
        }

        return RecognitionRequest(
            id = idFactory(purpose.label),
            purpose = purpose,
            languageMode = languageMode,
            backendPreference = settings.backendPreference,
            preferOnDevice = settings.preferOnDevice,
            // There is no "on-device only" mode in this app (§21): on-device is tried
            // first, and a network recognizer is always an acceptable fallback rather
            // than a hard failure. Offline study works when models are installed.
            allowNetworkFallback = true,
            partialResults = settings.showPartialTranscript,
            maxCandidates = if (purpose.isCommandLike) MAX_COMMAND_CANDIDATES else MAX_ANSWER_CANDIDATES,
            endpointPolicy = endpointPolicyFor(purpose, settings),
            vocabularyHints = hints,
            englishLocale = settings.englishLocale,
            arabicLocale = settings.arabicLocale,
            autoFallbackLocale = settings.autoModeFallbackLocale,
            cardId = cardId,
            debugTranscriptLogging = settings.debugTranscriptLogging,
            timeouts = timeoutsFor(purpose, settings),
            createdAtMs = createdAtMs
        )
    }

    /**
     * Bounded retry decision (§50/§51).
     *
     * Returns the delay before the next attempt, or `null` when the error must not be
     * retried. `attempt` is 0-based for the *retry*, so `attempt == maxRetries` stops.
     */
    fun retryDelayMs(error: RecognitionError, attempt: Int, settings: SttSettings): Long? {
        if (!error.recoverable || !error.recommendedRetry) return null
        if (attempt >= settings.maxRetriesPerTurn) return null
        return when (error.code) {
            // Busy means the previous turn has not reached a terminal callback. Retrying
            // instantly just reproduces the error — back off and let it drain.
            RecognitionErrorCode.BUSY -> settings.retryBackoffMs * (attempt + 2)

            // Throttling is reported straight back to the caller instead of being retried
            // behind its back: an automatic retry would re-introduce exactly the rapid
            // start/cancel pattern the rate limiter exists to prevent (§52).
            RecognitionErrorCode.TOO_MANY_REQUESTS -> null

            RecognitionErrorCode.NETWORK_UNAVAILABLE,
            RecognitionErrorCode.NETWORK_TIMEOUT,
            RecognitionErrorCode.SERVER_DISCONNECTED -> settings.retryBackoffMs * (attempt + 1)

            // A second NO_SPEECH in a row is almost always a quiet room or a dead mic;
            // stop rather than loop.
            RecognitionErrorCode.NO_SPEECH -> if (attempt == 0) 0L else null

            RecognitionErrorCode.NO_MATCH -> if (attempt == 0) 0L else null

            RecognitionErrorCode.WATCHDOG_TIMEOUT -> settings.retryBackoffMs * (attempt + 1)

            RecognitionErrorCode.AUDIO_FAILURE -> settings.retryBackoffMs * (attempt + 1)

            // Never auto-retried: they need the user, or they are not errors at all.
            RecognitionErrorCode.PERMISSION_DENIED,
            RecognitionErrorCode.UNAVAILABLE,
            RecognitionErrorCode.LANGUAGE_UNSUPPORTED,
            RecognitionErrorCode.LANGUAGE_MODEL_UNAVAILABLE,
            RecognitionErrorCode.CANCELLED -> null

            RecognitionErrorCode.SERVER_ERROR,
            RecognitionErrorCode.CLIENT_ERROR,
            RecognitionErrorCode.SUPPORT_CHECK_FAILED,
            RecognitionErrorCode.INTERNAL -> settings.retryBackoffMs
        }
    }

    companion object {
        const val MAX_ANSWER_CANDIDATES = 5
        const val MAX_COMMAND_CANDIDATES = 5

        /**
         * Request identities are readable and ordered: `answer_<card>_<seq>`. The prefix
         * makes stale-callback drops diagnosable from a log line without exposing content.
         */
        fun defaultRequestId(purposeLabel: String): String =
            "${purposeLabel}_${sequence.incrementAndGet()}"

        private val sequence = java.util.concurrent.atomic.AtomicLong(0L)

        /** Stable, test-friendly IDs: `answer_1`, `answer_2`, ... */
        fun counterIdFactory(): (String) -> String {
            val counter = java.util.concurrent.atomic.AtomicLong(0L)
            return { purpose -> "${purpose}_${counter.incrementAndGet()}" }
        }
    }
}
