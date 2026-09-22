package com.studyagent.client.core.voice.tts

/**
 * Typed cloud-TTS policies with stable storage ids (master prompt §27/§56/§84).
 *
 * All of these are persisted as their [storageId] string in AppSettings and
 * parsed tolerantly (unknown value → default) so a settings file from another
 * build can never break speech.
 */

/** What to do when a cloud provider fails (master prompt §13). */
enum class CloudTtsFallbackPolicy(val storageId: String) {
    /**
     * Before any audible output: retry the same utterance on the local Android
     * engine. After meaningful playback started: NO local retry (no duplicated
     * speech) — the request fails with a typed error and the study flow's
     * normal recovery applies.
     */
    FALLBACK_TO_ANDROID("fallback_to_android"),

    /** Keep the provider; failures surface as typed errors (no local retry). */
    STOP_VOICE_OUTPUT("stop_voice_output");

    companion object {
        const val DEFAULT = "fallback_to_android"

        fun fromStorage(raw: String?): CloudTtsFallbackPolicy = when (raw?.trim()?.lowercase()) {
            STOP_VOICE_OUTPUT.storageId -> STOP_VOICE_OUTPUT
            else -> FALLBACK_TO_ANDROID
        }
    }
}

/** Friendly quality/latency strategy (maps to provider models on the PC Agent). */
enum class CloudTtsQualityProfile(val storageId: String, val label: String) {
    /** Lowest latency (OpenAI: gpt-4o-mini-tts; ElevenLabs: flash class model). */
    INTERACTIVE("interactive", "Fast (real-time)"),

    /** Balanced latency/quality (ElevenLabs: multilingual v2). */
    BALANCED("balanced", "Balanced"),

    /** Highest quality (ElevenLabs: v3). */
    QUALITY("quality", "Maximum quality");

    companion object {
        const val DEFAULT = "interactive"

        fun fromStorage(raw: String?): CloudTtsQualityProfile = when (raw?.trim()?.lowercase()) {
            BALANCED.storageId -> BALANCED
            QUALITY.storageId -> QUALITY
            else -> INTERACTIVE
        }
    }
}

/** Client-side cloud audio cache (privacy-aware, bounded; §84/§85). */
enum class CloudTtsCachePolicy(val storageId: String) {
    /** No cache: every utterance is synthesized (server-side cache still applies). */
    NONE("none"),

    /** Bounded in-memory, session-scoped cache (default). Never disk by default. */
    SESSION("session");

    companion object {
        const val DEFAULT = "session"

        fun fromStorage(raw: String?): CloudTtsCachePolicy = when (raw?.trim()?.lowercase()) {
            NONE.storageId -> NONE
            else -> SESSION
        }
    }
}

/**
 * Mixed-language strategy for cloud speech (master prompt §65).
 *
 * Default [SINGLE_MULTILINGUAL_VOICE]: one synthesis per request with a
 * multilingual voice — cheaper, lower latency, and modern cloud voices handle
 * Arabic/English code-switching. Local Android segmentation is unchanged.
 */
enum class CloudLanguageStrategy(val storageId: String) {
    SINGLE_MULTILINGUAL_VOICE("single_multilingual_voice"),
    SEGMENT_BY_LANGUAGE("segment_by_language");

    companion object {
        const val DEFAULT = "single_multilingual_voice"

        fun fromStorage(raw: String?): CloudLanguageStrategy = when (raw?.trim()?.lowercase()) {
            SEGMENT_BY_LANGUAGE.storageId -> SEGMENT_BY_LANGUAGE
            else -> SINGLE_MULTILINGUAL_VOICE
        }
    }
}
