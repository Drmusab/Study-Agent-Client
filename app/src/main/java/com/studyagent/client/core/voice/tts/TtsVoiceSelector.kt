package com.studyagent.client.core.voice.tts

/**
 * Deterministic automatic voice selection (see docs/TTS_ARCHITECTURE.md §Voice ranking).
 *
 * Priority order:
 *  1. An explicit user-selected voice that is still installed and language-compatible.
 *  2. Exact-locale high-quality offline voice.
 *  3. Exact-locale high-quality voice.
 *  4. Language-compatible offline voice.
 *  5. Any language-compatible voice.
 *
 * Scoring is additive and fully deterministic: ties are broken by voice id so the
 * same input always yields the same output (no "prettiest name" heuristics).
 */
object TtsVoiceSelector {

    private const val SCORE_EXACT_LOCALE = 100
    private const val SCORE_LANGUAGE_MATCH = 50
    private const val SCORE_OFFLINE_PREFERRED = 30
    private const val SCORE_OFFLINE_DEFAULT = 5
    private const val PENALTY_NETWORK_PREFERRED_OFFLINE = -40
    private const val PENALTY_NETWORK_DEFAULT = -10

    fun select(
        voices: List<TtsVoiceInfo>,
        language: String,
        preferredLocaleTag: String? = null,
        selectedVoiceId: String? = null,
        preferOffline: Boolean = true
    ): TtsVoiceInfo? {
        val lang = language.lowercase()
        val compatible = voices.filter { it.language == lang }
        if (compatible.isEmpty()) return null

        // 1. Explicit user selection wins if still installed and compatible.
        if (!selectedVoiceId.isNullOrBlank()) {
            compatible.firstOrNull { it.id == selectedVoiceId }?.let { return it }
            // Voice disappeared after an engine update → graceful fallback below.
        }

        val wantedLocale = preferredLocaleTag?.lowercase()

        fun score(v: TtsVoiceInfo): Int {
            var s = 0
            if (wantedLocale != null && v.localeTag.lowercase() == wantedLocale) {
                s += SCORE_EXACT_LOCALE
            } else {
                s += SCORE_LANGUAGE_MATCH
            }
            s += if (v.networkRequired) {
                if (preferOffline) PENALTY_NETWORK_PREFERRED_OFFLINE else PENALTY_NETWORK_DEFAULT
            } else {
                if (preferOffline) SCORE_OFFLINE_PREFERRED else SCORE_OFFLINE_DEFAULT
            }
            s += v.quality.rank * 10
            s -= v.latency.rank * 2
            return s
        }

        return compatible
            .sortedBy { it.id } // stable base order → deterministic tie-break
            .maxByOrNull { score(it) }
    }

    /** Voices for [language] ordered for display: chosen first, then best-ranked. */
    fun rankedForDisplay(
        voices: List<TtsVoiceInfo>,
        language: String,
        selectedVoiceId: String? = null,
        preferOffline: Boolean = true,
        preferredLocaleTag: String? = null
    ): List<TtsVoiceInfo> {
        val lang = language.lowercase()
        val compatible = voices.filter { it.language == lang }
        val best = select(compatible, language, preferredLocaleTag, null, preferOffline)
        return compatible.sortedWith(
            compareByDescending<TtsVoiceInfo> { it.id == selectedVoiceId }
                .thenByDescending { it.id == best?.id }
                .thenByDescending { !it.networkRequired }
                .thenByDescending { it.quality.rank }
                .thenBy { it.latency.rank }
                .thenBy { it.id }
        )
    }
}
