package com.studyagent.client.core.voice.stt

/**
 * Conservative vocabulary biasing (§33–§36).
 *
 * Android accepts `RecognizerIntent.EXTRA_BIASING_STRINGS` on supported implementations.
 * A bias list is a *hint*, and an oversized one measurably hurts recognition, so this
 * provider is deliberately small, de-duplicated and purpose-specific.
 *
 * Two hard rules:
 *  1. **No expected answers.** The provider is only ever given question/topic text by
 *     [RecognitionPolicyFactory]; the PC agent's answer never reaches it (§34).
 *  2. **No medical rewriting.** Biasing influences what the recognizer *hears*; it never
 *     post-processes a transcript (§38).
 */
class MedicalVocabularyProvider(
    private val coreTerms: List<String> = CORE_MEDICAL_TERMS,
    private val abbreviations: List<String> = MEDICAL_ABBREVIATIONS,
    private val maxBiasTerms: Int = MAX_BIAS_TERMS
) {

    /**
     * Build the bias list for one turn.
     *
     * Ordering matters for most implementations (earlier = stronger), so context terms
     * from the current question come first, then the curated core list. Commands and
     * ratings get only their own small controlled vocabulary.
     */
    fun biasListFor(
        contextTerms: List<String>,
        purpose: RecognitionPurpose
    ): List<String> = when (purpose) {
        RecognitionPurpose.RATING -> RATING_VOCABULARY

        RecognitionPurpose.COMMAND,
        RecognitionPurpose.PUSH_TO_TALK_COMMAND -> COMMAND_VOCABULARY

        RecognitionPurpose.SHORT_CONFIRMATION -> CONFIRMATION_VOCABULARY

        RecognitionPurpose.DECK_SELECTION -> emptyList()

        RecognitionPurpose.ANSWER,
        RecognitionPurpose.PUSH_TO_TALK_ANSWER -> {
            val context = contextTerms
                .map { it.trim() }
                .filter { it.length in MIN_TERM_LENGTH..MAX_TERM_LENGTH }
            bounded(context + coreTerms + abbreviations)
        }
    }

    /**
     * Extract biasable terms from a question string.
     *
     * Only multi-word medical phrases and known abbreviations are kept: biasing on
     * ordinary English words ("patient", "most", "common") adds noise without helping.
     */
    fun contextTermsFrom(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val lower = text.lowercase()
        val found = mutableListOf<String>()
        for (term in coreTerms) {
            if (lower.contains(term.lowercase())) found += term
        }
        // Abbreviations are matched whole-word, so "CT" cannot match inside "CTscan"
        // or inside an unrelated word.
        for (abbrev in abbreviations) {
            if (wholeWordPattern(abbrev).containsMatchIn(text)) found += abbrev
        }
        return bounded(found)
    }

    /** Cached whole-word matcher; abbreviation strings are a fixed, small set. */
    private fun wholeWordPattern(abbrev: String): Regex =
        patternCache.getOrPut(abbrev) {
            Regex("(?<![A-Za-z0-9])${Regex.escape(abbrev)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        }

    private val patternCache = HashMap<String, Regex>()

    private fun bounded(terms: List<String>): List<String> {
        val seen = LinkedHashSet<String>(maxBiasTerms)
        for (raw in terms) {
            val term = raw.trim()
            if (term.length !in MIN_TERM_LENGTH..MAX_TERM_LENGTH) continue
            // Case-insensitive de-duplication: "Midline Shift" and "midline shift" are
            // the same bias entry and would waste two slots.
            val key = term.lowercase()
            if (seen.any { it.lowercase() == key }) continue
            seen += term
            if (seen.size >= maxBiasTerms) break
        }
        return seen.toList()
    }

    companion object {
        /**
         * Bias lists above this size degrade recognition on the implementations that
         * honour them. 40 covers a question's own terms plus the core neuro/critical-care
         * vocabulary without crowding the recognizer.
         */
        const val MAX_BIAS_TERMS = 40
        private const val MIN_TERM_LENGTH = 2
        private const val MAX_TERM_LENGTH = 40

        val RATING_VOCABULARY: List<String> = listOf(
            "again", "hard", "good", "easy",
            "مرة اخرى", "صعب", "جيد", "سهل"
        )

        val COMMAND_VOCABULARY: List<String> = listOf(
            "repeat question", "hint", "explain", "show answer", "skip",
            "pause", "resume", "stop speaking", "end session",
            "أعد السؤال", "تلميح", "اشرح", "اظهر الجواب", "تخطي",
            "توقف", "استمر", "انهاء"
        )

        val CONFIRMATION_VOCABULARY: List<String> = listOf(
            "yes", "no", "correct", "confirm", "cancel",
            "نعم", "لا", "صحيح", "تأكيد", "الغاء"
        )

        /**
         * Curated core vocabulary. Deliberately small and high-yield: these are the terms
         * that general recognizers reliably mangle, not a medical dictionary (§33).
         */
        val CORE_MEDICAL_TERMS: List<String> = listOf(
            "epidural hematoma",
            "subdural hematoma",
            "subarachnoid hemorrhage",
            "intracranial pressure",
            "midline shift",
            "glasgow coma scale",
            "anisocoria",
            "hydrocephalus",
            "ventriculoperitoneal shunt",
            "craniotomy",
            "decompressive craniectomy",
            "intracerebral hemorrhage",
            "ischemic stroke",
            "hemorrhagic stroke",
            "cerebral edema",
            "herniation",
            "uncal herniation",
            "traumatic brain injury",
            "diffuse axonal injury",
            "contrecoup injury",
            "cushing reflex",
            "cerebral perfusion pressure",
            "external ventricular drain",
            "mannitol",
            "hypertonic saline",
            "intubation",
            "mechanical ventilation",
            "positive end-expiratory pressure",
            "permissive hypercapnia",
            "pupillary light reflex",
            "focal neurological deficit",
            "contralateral hemiparesis",
            "papilledema",
            "lumbar puncture",
            "contrast-enhanced CT",
            "non-contrast CT",
            "diffusion-weighted imaging",
            "thrombolysis",
            "thrombectomy",
            "anticoagulation",
            "antiplatelet",
            "myocardial infarction",
            "pulmonary embolism",
            "deep vein thrombosis",
            "septic shock",
            "diabetic ketoacidosis",
            "hyperosmolar hyperglycemic state",
            "acute kidney injury",
            "chronic kidney disease",
            "acute respiratory distress syndrome"
        )

        /**
         * Spoken abbreviations. Recognizers hear "G C S" or "jee-see-ess"; biasing on the
         * written form materially improves the hit rate. These are *never* rewritten in a
         * transcript (§36).
         */
        val MEDICAL_ABBREVIATIONS: List<String> = listOf(
            "GCS", "ICP", "EDH", "SDH", "SAH", "ICH", "CSF", "CT", "MRI", "MRA",
            "SpO2", "PaCO2", "PaO2", "ECG", "EVD", "CPR", "ABC", "BP", "HR", "RR",
            "CPP", "PEEP", "ARDS", "DVT", "PE", "MI", "AKI", "CKD", "DKA", "HHS",
            "mL", "mmHg", "mg", "mcg"
        )

        val DEFAULT: MedicalVocabularyProvider = MedicalVocabularyProvider()
    }
}
