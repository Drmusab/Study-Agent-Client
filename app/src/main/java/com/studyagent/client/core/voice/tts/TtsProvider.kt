package com.studyagent.client.core.voice.tts

/**
 * The selectable speech providers of ONE speech system (master prompt §7/§9).
 *
 * Android never talks to the cloud providers directly: [OPENAI] and [ELEVENLABS]
 * are always reached through the authenticated PC Study Agent, which holds the
 * provider keys. [ANDROID] is the local, offline, default provider.
 *
 * Persistence contract (§101): settings store the stable [storageId] strings
 * (lowercase, dotted) — never Kotlin enum names. Unknown stored values migrate
 * to [ANDROID] instead of crashing, exactly like the other settings enums.
 */
enum class TtsProvider(
    /** Stable storage id (persisted in settings; part of the migration contract). */
    val storageId: String,
    /** Short label for UI. */
    val label: String,
    /** Whether the provider requires the PC Study Agent + a cloud credential. */
    val isCloud: Boolean
) {
    ANDROID("android", "Android (on-device)", isCloud = false),
    OPENAI("openai", "OpenAI", isCloud = true),
    ELEVENLABS("elevenlabs", "ElevenLabs", isCloud = true);

    companion object {
        /** Default provider: local, offline, free. Cloud is an opt-in upgrade. */
        const val DEFAULT = "android"

        /**
         * Tolerant parse: unknown/legacy/blank values become [ANDROID] — a
         * settings file from another build must never break speech.
         */
        fun fromStorage(raw: String?): TtsProvider = when (raw?.trim()?.lowercase()) {
            OPENAI.storageId -> OPENAI
            ELEVENLABS.storageId -> ELEVENLABS
            ANDROID.storageId, null, "" -> ANDROID
            else -> ANDROID
        }

        fun toStorage(value: TtsProvider): String = value.storageId

        val CLOUD: List<TtsProvider> = listOf(OPENAI, ELEVENLABS)
    }
}
