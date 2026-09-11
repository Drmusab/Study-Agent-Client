package com.studyagent.client.core.voice.tts

/**
 * One deterministic pronunciation rewrite.
 * [pattern] must be pre-compiled and reusable; [replacement] may reference groups ($1...).
 */
data class PronunciationRule(
    val pattern: Regex,
    val replacement: String,
    val description: String = ""
)

/**
 * Speech-only pronunciation layer for ENGLISH text segments of medical study content.
 *
 * Design rules (see master prompt §21–§24):
 *  - Never blindly expand ambiguous abbreviations: uppercase ≥2-letter abbreviations are
 *    rendered *letter by letter* ("CT" → "C T"), which is how clinicians actually say them
 *    and can never produce a wrong expansion.
 *  - Matching for abbreviations is case-sensitive with word boundaries, so ordinary words
 *    ("it", "or", "ct" inside prose) are not corrupted.
 *  - Numbers are never converted to words and never reordered — only the *relation* around
 *    them (slash, comparison, range, unit) is verbalized. Precision is preserved.
 *  - Rule application order is fixed and documented; every rule is unit-testable.
 *  - [customRules] run first, providing a future user-dictionary extension point without
 *    touching the built-in tables. Keep custom patterns conservative.
 *
 * The processor is applied ONLY to text segments classified as English by
 * [MixedLanguageSegmenter]; Arabic segments are left for the Arabic voice untouched.
 */
class MedicalPronunciationProcessor(
    customRules: List<PronunciationRule> = emptyList()
) {
    private val rules: List<PronunciationRule> =
        customRules + BUILT_IN_RULES

    /** Apply all rules to one English segment. Idempotent-ish, deterministic, never throws. */
    fun applyToEnglish(text: String): String {
        if (text.isBlank()) return text
        var t = text
        for (rule in rules) {
            t = rule.pattern.replace(t, rule.replacement)
        }
        return MULTI_SPACE.replace(t, " ").trim()
    }

    companion object {
        private val MULTI_SPACE = Regex(" {2,}")

        /** "GCS" → "G C S" (insert a space between letters). */
        internal fun spellOut(abbrev: String): String = abbrev.toCharArray().joinToString(" ")

        /** Curated medical abbreviations spoken letter-by-letter. Case-sensitive on purpose. */
        private val SPELLED_ABBREVIATIONS: List<String> = (
            listOf(
                // Neurological / trauma (prioritized by the product)
                "GCS", "ICP", "CPP", "EDH", "SDH", "SAH", "IVH", "IPH", "TBI", "ICH",
                // Imaging
                "CT", "CTA", "CTV", "MRI", "MRA", "PET", "SPECT",
                // Monitoring / vitals
                "BP", "HR", "RR", "ECG", "EEG", "EMG", "SVR",
                // Anatomy / systems
                "CNS", "PNS", "CSF",
                // Routes
                "IV", "IM", "IO", "SC", "PO", "PR", "NG", "NJ",
                // Departments / units
                "ICU", "CCU", "NICU", "PICU", "ED", "EMS",
                // Life support / scores
                "CPR", "DNR", "ACLS", "ATLS", "BLS", "ABC", "AVPU", "FAST", "APGAR",
                // Respiratory / cardiac disease
                "PE", "DVT", "MI", "CHF", "COPD", "ARDS", "PNA", "TB",
                "AF", "VT", "VF", "SVT", "PVC", "PAC", "NSR", "WPW", "LBBB", "RBBB",
                // Metabolic / heme / ids
                "DKA", "HHS", "TIA", "CVA", "GIB", "UTI", "URI", "SOB", "SIRS", "MODS",
                "DIC", "ITP", "TTP", "HUS", "ALL", "AML", "CLL", "CML",
                "MRSA", "VRE", "HIV", "AIDS", "SLE", "RA", "OA", "ESR", "CRP", "ANA", "RF",
                // Labs
                "CBC", "BMP", "CMP", "LFT", "LFTs", "RFT", "TFT", "ABG", "VBG",
                // Mixed-case clinical shorthand (case-sensitive matching makes these safe)
                "MLS", "Tx", "Rx", "Hx", "Dx", "Sx", "Fx", "Cx"
            )
        ).sortedByDescending { it.length } // longest first so "LFTs" beats "LFT"

        private fun wordBoundary(abbr: String) = "\\b${Regex.escape(abbr)}\\b"

        private val SHORTHAND_RULES = listOf(
            PronunciationRule(Regex("\\b(\\d{1,3})\\s*y/o\\b"), "$1 year old", "65 y/o → 65 year old"),
            PronunciationRule(Regex("\\bw/o\\b"), "without", "w/o → without"),
            PronunciationRule(Regex("\\bw/(?=\\s)"), "with", "w/ → with"),
            PronunciationRule(Regex("\\bc/o\\b"), "complains of", "c/o → complains of"),
            PronunciationRule(Regex("\\bs/p\\b"), "status post", "s/p → status post"),
            PronunciationRule(Regex("\\bh/o\\b"), "history of", "h/o → history of"),
            PronunciationRule(Regex("\\br/o\\b"), "rule out", "r/o → rule out")
        )

        private val EXPANSION_RULES = listOf(
            PronunciationRule(Regex("\\be\\.g\\."), "for example", "e.g."),
            PronunciationRule(Regex("\\bi\\.e\\."), "that is", "i.e."),
            PronunciationRule(Regex("\\bvs\\."), "versus", "vs."),
            PronunciationRule(Regex("\\bDr\\."), "Doctor", "Dr."),
            PronunciationRule(Regex("\\bapprox\\."), "approximately", "approx.")
        )

        private val COMPARISON_RULES = listOf(
            PronunciationRule(Regex("≥\\s*(\\d)"), "greater than or equal to $1", "≥30"),
            PronunciationRule(Regex("≤\\s*(\\d)"), "less than or equal to $1", "≤30"),
            PronunciationRule(Regex(">\\s*(\\d)"), "greater than $1", ">30 mL"),
            PronunciationRule(Regex("<\\s*(\\d)"), "less than $1", "<5 mm"),
            PronunciationRule(Regex("±\\s*"), "plus or minus ", "± value"),
            PronunciationRule(Regex("↑\\s*"), "increased ", "up arrow"),
            PronunciationRule(Regex("↓\\s*"), "decreased ", "down arrow")
        )

        /** Electrolyte ions — read as the element name, as done clinically. */
        private val ION_RULES = listOf(
            PronunciationRule(Regex("\\bNa\\+"), "sodium", "Na+ → sodium"),
            PronunciationRule(Regex("\\bK\\+"), "potassium", "K+ → potassium"),
            PronunciationRule(Regex("\\bCa(?:2)?\\+{1,2}"), "calcium", "Ca2+/Ca++/Ca+ → calcium"),
            PronunciationRule(Regex("\\bMg(?:2)?\\+{1,2}"), "magnesium", "Mg2+/Mg++/Mg+ → magnesium")
        )

        /** Blood/respired gases — spoken with letter/number mix. */
        private val GAS_RULES = listOf(
            PronunciationRule(Regex("\\bSpO2\\b"), "S P O two", "SpO2"),
            PronunciationRule(Regex("\\bSaO2\\b"), "S A O two", "SaO2"),
            PronunciationRule(Regex("\\bPaCO2\\b"), "Pa C O two", "PaCO2"),
            PronunciationRule(Regex("\\bPaO2\\b"), "Pa O two", "PaO2"),
            PronunciationRule(Regex("\\bFiO2\\b"), "Fi O two", "FiO2"),
            PronunciationRule(Regex("\\bEtCO2\\b"), "Et C O two", "EtCO2"),
            PronunciationRule(Regex("\\bCO2\\b"), "C O two", "CO2"),
            PronunciationRule(Regex("\\bO2\\b"), "O two", "O2")
        )

        private val SLASH_AND_RANGE_RULES = listOf(
            // Blood pressure: "120/80 mmHg" → "120 over 80 mmHg" (unit rule expands mmHg later).
            PronunciationRule(
                Regex("(\\d{2,3})\\s*/\\s*(\\d{2,3})(\\s*mmHg)"),
                "$1 over $2$3",
                "blood pressure"
            ),
            // Scores / ratings: "GCS 15/15" → "15 out of 15". Adjacent slashed dates are protected
            // by the (?!...) guards: "31/12/2024" never matches.
            PronunciationRule(
                Regex("(?<![/\\d])(\\d{1,3})\\s*/\\s*(\\d{1,3})(?![/\\d])"),
                "$1 out of $2",
                "X/Y score"
            ),
            // Vertebral/disc levels: "C6-C7" → "C6 to C7".
            PronunciationRule(
                Regex("\\b([CTLS])(\\d{1,2})\\s*[-–]\\s*([CTLS])(\\d{1,2})\\b"),
                "$1$2 to $3$4",
                "vertebral level range"
            ),
            // Numeric ranges "30-40" → "30 to 40"; ISO dates (2024-09-11) protected by lookarounds.
            PronunciationRule(
                Regex("(?<![\\d/-])(\\d{1,4})\\s*[-–]\\s*(\\d{1,4})(?![\\d/-])"),
                "$1 to $2",
                "numeric range"
            )
        )

        /**
         * Units — ordered longest-first. Word-boundary and case matching are deliberate:
         * "mL" and "ml" are units, "ML" (machine learning) is not; "mm" lower-case only.
         */
        private val UNIT_RULES = listOf(
            PronunciationRule(Regex("\\bmcg/kg\\b|\\bµg/kg\\b"), "micrograms per kilogram", "mcg/kg"),
            PronunciationRule(Regex("\\bmg/kg\\b"), "milligrams per kilogram", "mg/kg"),
            PronunciationRule(Regex("\\bmmHg\\b"), "millimeters of mercury", "mmHg"),
            PronunciationRule(Regex("\\bg/dL\\b"), "grams per deciliter", "g/dL"),
            PronunciationRule(Regex("\\bmEq/L\\b"), "milliequivalents per liter", "mEq/L"),
            PronunciationRule(Regex("\\bmEq\\b"), "milliequivalents", "mEq"),
            PronunciationRule(Regex("\\bmcg\\b|\\b[µμ]g\\b"), "micrograms", "mcg/µg"),
            PronunciationRule(Regex("\\bmg\\b"), "milligrams", "mg"),
            PronunciationRule(Regex("\\bkg\\b"), "kilograms", "kg"),
            PronunciationRule(Regex("\\b(?:mL|ml)\\b"), "milliliters", "mL/ml (uppercase ML excluded)"),
            PronunciationRule(Regex("\\bdL\\b"), "deciliters", "dL"),
            PronunciationRule(Regex("\\bmm\\b"), "millimeters", "mm"),
            PronunciationRule(Regex("\\bcm\\b"), "centimeters", "cm"),
            PronunciationRule(Regex("\\bkm\\b"), "kilometers", "km"),
            PronunciationRule(Regex("\\bg\\b"), "grams", "g"),
            PronunciationRule(Regex("\\bbpm\\b"), "beats per minute", "bpm"),
            PronunciationRule(Regex("(\\d)\\s*%"), "$1 percent", "98%"),
            PronunciationRule(Regex("(\\d)\\s*°\\s*C\\b"), "$1 degrees Celsius", "°C"),
            PronunciationRule(Regex("(\\d)\\s*°\\s*F\\b"), "$1 degrees Fahrenheit", "°F")
        )

        private val ABBREVIATION_RULES: List<PronunciationRule> =
            SPELLED_ABBREVIATIONS.map { abbr ->
                PronunciationRule(
                    pattern = Regex(wordBoundary(abbr)),
                    replacement = spellOut(abbr),
                    description = "spell out $abbr"
                )
            }

        /** Fixed, documented application order. */
        val BUILT_IN_RULES: List<PronunciationRule> =
            SHORTHAND_RULES +
                EXPANSION_RULES +
                COMPARISON_RULES +
                ION_RULES +
                GAS_RULES +
                SLASH_AND_RANGE_RULES +
                UNIT_RULES +
                ABBREVIATION_RULES
    }
}
