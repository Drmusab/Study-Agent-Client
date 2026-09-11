package com.studyagent.client.core.voice.tts

/**
 * Normalized, framework-free description of an installed TTS voice.
 * Produced by the engine adapter from `android.speech.tts.Voice` metadata
 * (API 21+, available on our minSdk 26) so UI and tests never touch framework types.
 */
enum class VoiceQuality(val rank: Int) {
    VERY_LOW(0), LOW(1), NORMAL(2), HIGH(3), VERY_HIGH(4)
}

enum class VoiceLatency(val rank: Int) {
    VERY_LOW(0), LOW(1), NORMAL(2), HIGH(3), VERY_HIGH(4)
}

data class TtsVoiceInfo(
    /** Stable engine voice name (e.g. "en-US-x-sfg#female_2-local"). Persisted as the setting. */
    val id: String,
    val displayName: String,
    /** BCP-47 tag, e.g. "en-US". */
    val localeTag: String,
    val quality: VoiceQuality,
    val latency: VoiceLatency,
    val networkRequired: Boolean,
    val features: Set<String> = emptySet(),
    val enginePackage: String? = null
) {
    /** ISO 639-1 language part of [localeTag] ("en" for "en-US"). */
    val language: String
        get() = localeTag.substringBefore('-').lowercase()
}

/** One installed TTS engine (e.g. system Google Speech Services), discovered via the platform. */
data class TtsEngineInfo(
    val packageName: String,
    val label: String,
    val isSystemDefault: Boolean
)
