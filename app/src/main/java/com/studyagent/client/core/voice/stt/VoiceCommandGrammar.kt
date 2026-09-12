package com.studyagent.client.core.voice.stt

import com.studyagent.client.core.models.VoiceCommand

/**
 * How strongly a transcript maps onto the controlled command grammar (§78).
 *
 * This exists because a recognizer hypothesis is a *guess*: "could" and "good" are one
 * substitution apart acoustically but one of them silently reschedules an Anki card.
 * Destructive actions demand [EXACT] or [HIGH]; [AMBIGUOUS] is surfaced for confirmation
 * or retry, never executed.
 */
enum class CommandConfidence {
    /** Normalized transcript is verbatim in the grammar. */
    EXACT,

    /** Verbatim after removing a leading/trailing politeness word, or a 1-edit typo on a long word. */
    HIGH,

    /** Matches more than one command, or only loosely. Never auto-executed. */
    AMBIGUOUS,

    /** No grammar entry matched. */
    NONE
}

/** A parsed command with the evidence behind it. */
data class ParsedVoiceCommand(
    val command: VoiceCommand,
    val confidence: CommandConfidence,
    /** The grammar phrase that matched, normalized. Empty for deck-extraction matches. */
    val matchedPhrase: String = "",
    /** The recognizer hypothesis this came from — retained for diagnostics (§78). */
    val sourceHypothesis: RecognitionHypothesis? = null
) {
    val isExact: Boolean get() = confidence == CommandConfidence.EXACT
    val isExecutable: Boolean get() = confidence == CommandConfidence.EXACT || confidence == CommandConfidence.HIGH
}

/** One command and the phrases that produce it. All phrases are stored pre-normalized. */
data class CommandEntry(
    val command: VoiceCommand,
    val phrases: Set<String>,
    /**
     * Explicit multi-word control phrases (§81). Only these may fire while the app is
     * listening for a *medical answer* — a lone word like "good" or "stop" is far more
     * likely to be part of the answer than a command.
     */
    val safeDuringAnswer: Set<String> = emptySet(),
    /** Alters study state or Anki scheduling → requires a stricter confidence bar. */
    val destructive: Boolean = false
) {
    /** True when [phrase] (normalized) may be honoured while an answer is expected. */
    fun isSafeDuringAnswer(phrase: String): Boolean = phrase in safeDuringAnswer
}

/**
 * The controlled command vocabulary (§74/§77/§79).
 *
 * One explicit table instead of a growing chain of `when` branches and ad-hoc
 * `startsWith` calls. The previous implementation used `t.startsWith("again")`, which
 * turned the perfectly ordinary answer *"again, there is a midline shift"* into a card
 * rating; prefix matching is gone, and every phrase here is matched whole.
 */
class VoiceCommandGrammar {

    private val commandEntries: List<CommandEntry> = buildEntries()

    /** Normalized phrase → entry, for O(1) exact lookup. */
    private val exactIndex: Map<String, List<CommandEntry>> = buildMap {
        for (entry in commandEntries) {
            for (phrase in entry.phrases) {
                val existing = get(phrase)
                put(phrase, if (existing == null) listOf(entry) else existing + entry)
            }
        }
    }

    /**
     * Parse one normalized transcript.
     *
     * @param normalized output of [CommandNormalizer.forCommand]
     * @param raw the original transcript, used only to recover a deck name's casing
     */
    fun parse(normalized: String, raw: String? = null, hypothesis: RecognitionHypothesis? = null): ParsedVoiceCommand? {
        if (normalized.isBlank()) return null

        // 1. Verbatim grammar hit.
        exactIndex[normalized]?.let { matches ->
            val distinct = matches.map { it.command.commandName }.distinct()
            return if (distinct.size > 1) {
                // Two different commands claim this phrase — refuse to guess (§79).
                ParsedVoiceCommand(matches.first().command, CommandConfidence.AMBIGUOUS, normalized, hypothesis)
            } else {
                ParsedVoiceCommand(matches.first().command, CommandConfidence.EXACT, normalized, hypothesis)
            }
        }

        // 2. "start studying <deck>" — matched on normalized text, extracted from raw text
        //    so the deck keeps its original casing.
        parseStartStudy(normalized, raw ?: normalized)?.let {
            return ParsedVoiceCommand(it, CommandConfidence.EXACT, "", hypothesis)
        }

        // 3. One-edit typo on a single long word ("goo d" style ASR slips). Bounded to
        //    distance 1 so "could" can never fuzzy-match "good" — that case is resolved by
        //    looking at the *other* recognizer hypotheses instead (§12).
        fuzzyMatch(normalized)?.let { return it.copy(sourceHypothesis = hypothesis) }

        return null
    }

    /** Phrase set for a purpose, used for vocabulary biasing and diagnostics. */
    fun phrasesFor(purpose: RecognitionPurpose): Set<String> = when (purpose) {
        RecognitionPurpose.RATING -> RATING_COMMANDS.flatMap { it.phrases }.toSet()
        RecognitionPurpose.COMMAND,
        RecognitionPurpose.PUSH_TO_TALK_COMMAND ->
            commandEntries.filter { it.command.commandName !in RATING_COMMAND_NAMES }
                .flatMap { it.phrases }.toSet()

        else -> commandEntries.flatMap { it.phrases }.toSet()
    }

    /** Every phrase that may fire while a medical answer is expected. */
    fun answerSafePhrases(): Set<String> = commandEntries.flatMap { it.safeDuringAnswer }.toSet()

    /** Commands that need the stricter confidence bar. */
    fun destructiveCommands(): Set<String> =
        commandEntries.filter { it.destructive }.map { it.command.commandName }.toSet()

    /**
     * Verbatim-only answers to the "I heard X — is that correct?" prompt (§14/§140).
     *
     * Deliberately tiny and matched exactly (no fuzzy, no substring): a confirmation
     * window's entire job is to resolve one yes/no, so anything it cannot match verbatim
     * falls through to normal rating interpretation instead of being guessed at.
     */
    fun confirmationYesPhrases(): Set<String> = CONFIRMATION_YES
    fun confirmationNoPhrases(): Set<String> = CONFIRMATION_NO

    private fun fuzzyMatch(normalized: String): ParsedVoiceCommand? {
        val words = normalized.split(' ')
        if (words.size != 1) return null
        val word = words[0]
        if (word.length < MIN_FUZZY_LENGTH) return null

        val candidates = exactIndex.keys
            .filter { it.length >= MIN_FUZZY_LENGTH && !it.contains(' ') }
            .filter { editDistanceAtMostOne(it, word) }
            .mapNotNull { exactIndex[it]?.firstOrNull() }

        val distinct = candidates.map { it.command.commandName }.distinct()
        return when (distinct.size) {
            1 -> ParsedVoiceCommand(candidates.first().command, CommandConfidence.HIGH, candidates.first().phrases.first(), null)
            else -> null // 0 or >1: never guess.
        }
    }

    private fun parseStartStudy(normalized: String, raw: String): VoiceCommand? {
        // Bare "start" / "ابدأ" with no deck named: keep the old behaviour of starting
        // the default deck rather than reporting an unknown command.
        if (normalized in BARE_START_PHRASES) return VoiceCommand.StartStudy(null)

        val rawLower = raw.trim()
        for (prefix in START_PREFIXES_RAW) {
            if (!rawLower.startsWith(prefix, ignoreCase = true)) continue
            val remainder = rawLower.substring(prefix.length).trim()
            if (remainder.isEmpty()) continue
            if (remainder.lowercase() in START_NOISE_WORDS) continue
            val deck = remainder.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
            return VoiceCommand.StartStudy(deck)
        }
        // Normalized-only fallback (e.g. Arabic text whose raw casing is irrelevant).
        for (prefix in START_PREFIXES_NORMALIZED) {
            if (!normalized.startsWith(prefix)) continue
            val remainder = normalized.substring(prefix.length).trim()
            if (remainder.isEmpty() || remainder in START_NOISE_WORDS_NORMALIZED) continue
            return VoiceCommand.StartStudy(remainder)
        }
        return null
    }

    companion object {
        private const val MIN_FUZZY_LENGTH = 4

        private val RATING_COMMAND_NAMES = setOf("Again", "Hard", "Good", "Easy")

        /**
         * Stored pre-normalized (§41): `أكيد` and `اكيد` collapse to one entry, so both
         * orthographies answer the confirmation prompt. Checked verbatim only.
         */
        private val CONFIRMATION_YES: Set<String> by lazy {
            setOf(
                "yes", "yeah", "yep", "sure", "confirm", "that is correct",
                "نعم", "ايوه", "أيوه", "ايوا", "اكيد", "أكيد", "موافق"
            ).map { CommandNormalizer.forCommand(it) }.toSet()
        }

        private val CONFIRMATION_NO: Set<String> by lazy {
            setOf(
                "no", "nope", "cancel", "never mind", "wrong",
                "لا", "الغاء", "الغي", "مش مظبوط"
            ).map { CommandNormalizer.forCommand(it) }.toSet()
        }

        private val RATING_COMMANDS: List<CommandEntry> by lazy {
            buildEntries().filter { it.command.commandName in RATING_COMMAND_NAMES }
        }

        private val START_PREFIXES_RAW = listOf(
            "start study on ", "start studying ", "start session ", "start ",
            "ابدأ دراسة ", "ابدأ الدراسه ", "ابدأ "
        )
        private val START_PREFIXES_NORMALIZED = listOf(
            "start study on ", "start studying ", "start session ", "start ",
            "ابدا دراسه ", "ابدا "
        )
        private val START_NOISE_WORDS = setOf("study", "session", "studying", "الدراسة", "الجلسة")
        private val START_NOISE_WORDS_NORMALIZED = setOf("study", "session", "studying", "الدراسه", "الجلسه")

        /** Normalized bare start phrases (no deck). Normalized so Arabic variants collapse. */
        private val BARE_START_PHRASES: Set<String> = setOf(
            "start", "start study", "start session", "start studying", "let's study",
            "lets study", "ابدأ", "ابدأ الدراسة", "ابدأ الجلسة", "يلا ندرس"
        ).map { CommandNormalizer.forCommand(it) }.toSet()

        /**
         * Levenshtein distance ≤ 1, computed without allocating a matrix: the strings must
         * be equal, differ by one substitution, or differ by one insertion/deletion.
         */
        internal fun editDistanceAtMostOne(a: String, b: String): Boolean {
            if (a == b) return true
            val diff = a.length - b.length
            if (diff > 1 || diff < -1) return false
            if (diff == 0) {
                var mismatches = 0
                for (i in a.indices) if (a[i] != b[i]) mismatches++
                return mismatches == 1
            }
            val longer = if (diff > 0) a else b
            val shorter = if (diff > 0) b else a
            var i = 0
            var j = 0
            var skipped = false
            while (i < longer.length && j < shorter.length) {
                if (longer[i] == shorter[j]) {
                    i++
                    j++
                } else if (skipped) {
                    return false
                } else {
                    skipped = true
                    i++
                }
            }
            return true
        }

        private fun buildEntries(): List<CommandEntry> {
            val n = { phrases: List<String> -> phrases.map { CommandNormalizer.forCommand(it) }.toSet() }
            return listOf(
                // ---- ratings (Again is destructive: it reschedules the card) ----
                CommandEntry(
                    command = VoiceCommand.Again,
                    phrases = n(listOf(
                        "again", "forgot", "forgot it", "i forgot", "zero", "once more",
                        "مرة اخرى", "مرة أخرى", "نسيت", "من جديد", "اعد", "أعد"
                    )),
                    destructive = true
                ),
                CommandEntry(
                    command = VoiceCommand.Hard,
                    phrases = n(listOf(
                        "hard", "difficult", "tough", "hard card", "very hard",
                        "صعب", "شاق", "مش سهل", "صعب جدا"
                    ))
                ),
                CommandEntry(
                    command = VoiceCommand.Good,
                    phrases = n(listOf(
                        "good", "correct", "got it", "nice", "it was good", "good card",
                        "جيد", "تمام", "صحيح", "ممتاز", "مقبول", "جيد جدا"
                    ))
                ),
                CommandEntry(
                    command = VoiceCommand.Easy,
                    phrases = n(listOf(
                        "easy", "simple", "trivial", "piece of cake", "very easy",
                        "سهل", "بسيط", "واضح", "سهل جدا"
                    ))
                ),

                // ---- navigation / help ----
                CommandEntry(
                    command = VoiceCommand.Repeat,
                    phrases = n(listOf(
                        "repeat", "repeat question", "repeat the question", "say again",
                        "say it again", "what was the question", "one more time", "pardon",
                        "أعد", "اعد", "أعد السؤال", "اعد السؤال", "كرر", "كرر السؤال",
                        "ما هو السؤال", "مرة ثانية"
                    )),
                    safeDuringAnswer = n(listOf(
                        "repeat question", "repeat the question", "say the question again",
                        "what was the question", "أعد السؤال", "اعد السؤال", "كرر السؤال",
                        "ما هو السؤال"
                    ))
                ),
                CommandEntry(
                    command = VoiceCommand.Hint,
                    phrases = n(listOf(
                        "hint", "give me a hint", "need a hint", "give hint", "a hint please",
                        "تلميح", "اعطني تلميح", "أعطني تلميح", "ساعدني", "تلميحة"
                    )),
                    safeDuringAnswer = n(listOf(
                        "give me a hint", "need a hint", "give hint", "a hint please",
                        "اعطني تلميح", "أعطني تلميح"
                    ))
                ),
                CommandEntry(
                    command = VoiceCommand.Explain,
                    phrases = n(listOf(
                        "explain", "explanation", "why", "tell me more", "elaborate", "explain it",
                        "اشرح", "شرح", "وضح", "توضيح", "لماذا", "علل", "اشرح لي"
                    )),
                    safeDuringAnswer = n(listOf("explain it", "tell me more", "اشرح لي"))
                ),
                CommandEntry(
                    command = VoiceCommand.ShowAnswer,
                    phrases = n(listOf(
                        "show answer", "show the answer", "give answer", "what is the answer",
                        "what's the answer", "reveal answer", "answer",
                        "اظهر الجواب", "أظهر الجواب", "ما هو الجواب", "الجواب", "الحل", "اظهر الحل"
                    )),
                    safeDuringAnswer = n(listOf(
                        "show answer", "show the answer", "give answer", "what is the answer",
                        "what's the answer", "reveal answer",
                        "اظهر الجواب", "أظهر الجواب", "ما هو الجواب", "اظهر الحل"
                    ))
                ),
                CommandEntry(
                    command = VoiceCommand.Skip,
                    phrases = n(listOf(
                        "skip", "next", "next card", "pass", "skip card", "skip this",
                        "التالي", "تخطي", "تجاوز", "البطاقة التالية", "عدي"
                    )),
                    safeDuringAnswer = n(listOf("next card", "skip card", "skip this", "البطاقة التالية")),
                    destructive = true
                ),

                // ---- session control ----
                CommandEntry(
                    command = VoiceCommand.Pause,
                    phrases = n(listOf(
                        "pause", "pause study", "pause session", "hold on", "wait",
                        "توقف", "توقف مؤقت", "انتظر", "استراحة"
                    )),
                    safeDuringAnswer = n(listOf("pause study", "pause session", "توقف مؤقت"))
                ),
                CommandEntry(
                    command = VoiceCommand.Resume,
                    phrases = n(listOf(
                        "resume", "continue", "resume study", "resume session", "keep going",
                        "اكمل", "استمر", "تابع", "واصل"
                    )),
                    safeDuringAnswer = n(listOf("resume study", "resume session", "keep going"))
                ),
                // Checked before EndSession so "stop speaking" never ends the session.
                CommandEntry(
                    command = VoiceCommand.StopSpeaking,
                    phrases = n(listOf(
                        "stop speaking", "stop talking", "be quiet", "quiet", "enough",
                        "shut up", "silence", "hush",
                        "توقف عن الكلام", "اكتف", "اكتفي", "اسكت", "اسكتي", "كفى", "كلام كفاية"
                    )),
                    safeDuringAnswer = n(listOf(
                        "stop speaking", "stop talking", "be quiet", "توقف عن الكلام", "كلام كفاية"
                    ))
                ),
                CommandEntry(
                    command = VoiceCommand.EndSession,
                    phrases = n(listOf(
                        "stop", "end", "end session", "stop session", "finish", "quit",
                        "انهاء", "إنهاء", "وقف", "خروج", "انهي الجلسة"
                    )),
                    safeDuringAnswer = n(listOf("end session", "stop session", "انهي الجلسة")),
                    destructive = true
                ),
                CommandEntry(
                    command = VoiceCommand.StatusQuestion,
                    phrases = n(listOf(
                        "how many cards left", "remaining cards", "cards remaining", "status",
                        "how many left",
                        "كم بطاقة متبقية", "كم باقي", "العدد المتبقي", "كم تبقى", "الحالة"
                    )),
                    safeDuringAnswer = n(listOf(
                        "how many cards left", "remaining cards", "cards remaining",
                        "how many left", "كم بطاقة متبقية", "العدد المتبقي"
                    ))
                )
            )
        }
    }
}
