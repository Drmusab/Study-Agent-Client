package com.studyagent.client.core.voice.stt

import com.studyagent.client.core.models.AppSettings

/**
 * Maps persisted [AppSettings] onto the structured [SttSettings] the STT subsystem consumes.
 *
 * Mirrors `toTtsSettings()`: settings are stored as flat, serialization-friendly primitives
 * and translated once, here, so recognition code never has to parse a string enum or know
 * which DataStore key anything came from. Unknown or corrupt values fall back to defaults
 * rather than throwing — a bad preference must never break the study loop.
 */
fun AppSettings.toSttSettings(): SttSettings = SttSettings(
    backendPreference = parseBackendPreference(sttRecognitionMode),
    languageMode = parseLanguageMode(sttLanguageMode, sttLanguage),
    englishLocale = sttEnglishLocale.ifBlank { SttSettings.DEFAULT_ENGLISH_LOCALE },
    arabicLocale = sttArabicLocale.ifBlank { SttSettings.DEFAULT_ARABIC_LOCALE },
    autoModeFallbackLocale = sttAutoFallbackLocale.ifBlank {
        parseLanguageMode(sttLanguageMode, sttLanguage).let { mode ->
            if (mode == RecognitionLanguageMode.ARABIC) {
                sttArabicLocale.ifBlank { SttSettings.DEFAULT_ARABIC_LOCALE }
            } else {
                sttEnglishLocale.ifBlank { SttSettings.DEFAULT_ENGLISH_LOCALE }
            }
        }
    },
    preferOnDevice = sttPreferOnDevice,
    autoSubmitAnswers = autoSubmitTranscript,
    spokenRatings = listenForSpokenRating,
    showPartialTranscript = sttShowPartialTranscript,
    medicalVocabularyBiasing = sttMedicalBiasing,
    answerEndpointProfile = AnswerEndpointProfile.fromName(sttAnswerLength),
    handsFreeMode = handsFreeMode,
    confirmAmbiguousRating = confirmRating,
    debugTranscriptLogging = sttDebugTranscriptLogging
)

/** Vendor-neutral mode names — the UI never names a provider (§86). */
fun parseBackendPreference(raw: String?): RecognitionBackendPreference = when (raw?.uppercase()) {
    "PREFER_ON_DEVICE" -> RecognitionBackendPreference.PREFER_ON_DEVICE
    "SYSTEM_DEFAULT" -> RecognitionBackendPreference.SYSTEM_DEFAULT
    else -> RecognitionBackendPreference.AUTO
}

/**
 * Falls back to the legacy single-locale `sttLanguage` when no mode is stored, so an
 * upgraded install keeps the language it was already using.
 */
fun parseLanguageMode(raw: String?, legacyLanguage: String?): RecognitionLanguageMode =
    when (raw?.uppercase()) {
        "AUTO_EN_AR" -> RecognitionLanguageMode.AUTO_EN_AR
        "ENGLISH" -> RecognitionLanguageMode.ENGLISH
        "ARABIC" -> RecognitionLanguageMode.ARABIC
        else -> RecognitionLanguageMode.fromTag(legacyLanguage)
    }
