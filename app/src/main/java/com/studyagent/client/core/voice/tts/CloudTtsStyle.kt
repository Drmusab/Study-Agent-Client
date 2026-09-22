package com.studyagent.client.core.voice.tts

/**
 * Cloud TTS delivery instructions, built per [SpeechPurpose] (master prompt §21).
 *
 * Short and stable: these are *style* instructions for OpenAI's promptable
 * voices (and a hint to the agent for any future promptable provider). They
 * never contain user content, and they are part of the cache key, so they
 * must stay deterministic for a given purpose.
 *
 * ElevenLabs does not take free-text instructions; for it we send the
 * [CloudTtsQualityProfile] and voice settings instead (see [providerOptions]).
 */
object CloudTtsStyle {

    fun instructionsForPurpose(purpose: SpeechPurpose): String? = when (purpose) {
        SpeechPurpose.QUESTION ->
            "Clear, focused exam-question delivery: steady pace, neutral professional tone, pause briefly before asking the question."
        SpeechPurpose.HINT ->
            "Helpful, calm hint: slightly gentler pace, encouraging but concise."
        SpeechPurpose.FEEDBACK ->
            "Concise study feedback: neutral, encouraging tone, clear articulation of any medical terms."
        SpeechPurpose.EXPLANATION ->
            "Educational explanation: measured pace, clear pauses between points, precise articulation of medical terminology."
        SpeechPurpose.ANSWER ->
            "Answer reveal: clear and direct, slightly slower than normal, emphasize the key clinical point."
        SpeechPurpose.SESSION_SUMMARY ->
            "Warm summary of a completed study session: relaxed, positive, concise."
        SpeechPurpose.STATUS,
        SpeechPurpose.SYSTEM,
        SpeechPurpose.ERROR ->
            "Short system notification: neutral, brief, clear."
        SpeechPurpose.RATING_CONFIRMATION ->
            "Brief confirmation: short, neutral, clear."
        SpeechPurpose.PREVIEW ->
            "Neutral voice preview sample: natural, steady pacing."
    }

    /**
     * Provider options map for a remote utterance (opaque pass-through to the
     * PC Agent, which owns provider-specific interpretation):
     *  - OpenAI:   "instructions" (when the model supports it)
     *  - ElevenLabs: "quality_profile" + voice settings (stability etc.)
     */
    fun providerOptions(
        provider: TtsProvider,
        purpose: SpeechPurpose,
        quality: CloudTtsQualityProfile,
        capabilities: SpeechBackendCapabilities
    ): Map<String, Any> {
        val options = LinkedHashMap<String, Any>()
        when (provider) {
            TtsProvider.OPENAI -> {
                if (capabilities.supportsStyleInstructions) {
                    instructionsForPurpose(purpose)?.let { options["instructions"] = it }
                }
            }
            TtsProvider.ELEVENLABS -> {
                options["quality_profile"] = quality.storageId
                if (capabilities.supportsVoiceSettings) {
                    options["stability"] = 0.5
                    options["similarity_boost"] = 0.75
                    options["style"] = 0.0
                    options["use_speaker_boost"] = true
                }
            }
            TtsProvider.ANDROID -> Unit // local backend ignores these
        }
        return options
    }

    /** Language wire value for a segment ("auto" | "en" | "ar"). */
    fun languageCode(language: SegmentLanguage): String = when (language) {
        SegmentLanguage.ARABIC -> "ar"
        SegmentLanguage.ENGLISH -> "en"
        SegmentLanguage.NEUTRAL -> "auto"
    }
}
