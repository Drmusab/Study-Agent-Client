package com.studyagent.client.core.voice

import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.models.VoiceCommand

class VoiceCommandManager {

    fun parseCommand(input: String): VoiceCommand {
        val normalized = normalizeText(input)
        if (normalized.isBlank()) return VoiceCommand.Unknown("")

        // 1. Exact / High-confidence rating commands
        when {
            matchesAgain(normalized) -> return VoiceCommand.Again
            matchesHard(normalized) -> return VoiceCommand.Hard
            matchesGood(normalized) -> return VoiceCommand.Good
            matchesEasy(normalized) -> return VoiceCommand.Easy

            matchesRepeat(normalized) -> return VoiceCommand.Repeat
            matchesHint(normalized) -> return VoiceCommand.Hint
            matchesExplain(normalized) -> return VoiceCommand.Explain
            matchesShowAnswer(normalized) -> return VoiceCommand.ShowAnswer
            matchesSkip(normalized) -> return VoiceCommand.Skip

            matchesPause(normalized) -> return VoiceCommand.Pause
            matchesResume(normalized) -> return VoiceCommand.Resume
            matchesStopSpeaking(normalized) -> return VoiceCommand.StopSpeaking
            matchesEnd(normalized) -> return VoiceCommand.EndSession
            matchesStatus(normalized) -> return VoiceCommand.StatusQuestion

            matchesStart(normalized) -> {
                val deck = extractDeckFromStartCommand(normalized)
                return VoiceCommand.StartStudy(deck)
            }
        }

        return VoiceCommand.Unknown(input)
    }

    fun parseInStudyContext(input: String, currentState: StudyState): VoiceCommand {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return VoiceCommand.Unknown("")

        val cmd = parseCommand(trimmed)

        return when (currentState) {
            is StudyState.WaitingForRating -> {
                when (cmd) {
                    is VoiceCommand.Again,
                    is VoiceCommand.Hard,
                    is VoiceCommand.Good,
                    is VoiceCommand.Easy,
                    is VoiceCommand.Repeat,
                    is VoiceCommand.Hint,
                    is VoiceCommand.Explain,
                    is VoiceCommand.ShowAnswer,
                    is VoiceCommand.Skip,
                    is VoiceCommand.Pause,
                    is VoiceCommand.EndSession -> cmd
                    else -> cmd // Return the recognized command or Unknown
                }
            }

            is StudyState.Listening,
            is StudyState.SpeakingQuestion -> {
                // If the user spoke a short navigation/session command
                when (cmd) {
                    is VoiceCommand.Repeat,
                    is VoiceCommand.Hint,
                    is VoiceCommand.Explain,
                    is VoiceCommand.ShowAnswer,
                    is VoiceCommand.Skip,
                    is VoiceCommand.Pause,
                    is VoiceCommand.StopSpeaking,
                    is VoiceCommand.EndSession,
                    is VoiceCommand.StatusQuestion -> cmd
                    else -> VoiceCommand.SubmitAnswer(trimmed)
                }
            }

            is StudyState.ShowingFeedback,
            is StudyState.HintShowing,
            is StudyState.ExplanationShowing -> {
                when (cmd) {
                    is VoiceCommand.Again,
                    is VoiceCommand.Hard,
                    is VoiceCommand.Good,
                    is VoiceCommand.Easy,
                    is VoiceCommand.Repeat,
                    is VoiceCommand.Explain,
                    is VoiceCommand.Skip,
                    is VoiceCommand.Pause,
                    is VoiceCommand.StopSpeaking,
                    is VoiceCommand.EndSession -> cmd
                    else -> cmd
                }
            }

            is StudyState.Idle,
            is StudyState.SessionFinished,
            is StudyState.Error -> {
                when (cmd) {
                    is VoiceCommand.StartStudy,
                    is VoiceCommand.Resume -> cmd
                    else -> cmd
                }
            }

            is StudyState.Paused -> {
                when (cmd) {
                    is VoiceCommand.Resume,
                    is VoiceCommand.EndSession -> cmd
                    else -> cmd
                }
            }

            else -> cmd
        }
    }

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace(Regex("[.,!?;:\"'\\[\\]()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun matchesAgain(t: String): Boolean {
        return t in setOf("again", "repeat card", "forgot", "zero", "مرة اخرى", "مرة أخرى", "اعد", "أعد", "نسيت", "من جديد") ||
                t.startsWith("again") || t.startsWith("مرة اخرى")
    }

    private fun matchesHard(t: String): Boolean {
        return t in setOf("hard", "difficult", "tough", "صعب", "شاق", "مش سهل") ||
                t == "hard card" || t == "صعب جدا"
    }

    private fun matchesGood(t: String): Boolean {
        return t in setOf("good", "correct", "got it", "nice", "جيد", "تمام", "صحيح", "ممتاز", "مقبول") ||
                t == "it was good" || t == "جيد جدا"
    }

    private fun matchesEasy(t: String): Boolean {
        return t in setOf("easy", "simple", "piece of cake", "trivial", "سهل", "بسيط", "واضح", "سهل جدا") ||
                t == "very easy"
    }

    private fun matchesRepeat(t: String): Boolean {
        return t in setOf(
            "repeat", "repeat question", "say again", "what was the question", "one more time", "pardon",
            "أعد", "اعد", "أعد السؤال", "اعد السؤال", "كرر", "كرر السؤال", "ما هو السؤال", "مرة ثانية"
        ) || t.startsWith("repeat question") || t.startsWith("اعد السؤال")
    }

    private fun matchesHint(t: String): Boolean {
        return t in setOf("hint", "give me a hint", "need a hint", "give hint", "تلميح", "اعطني تلميح", "أعطني تلميح", "ساعدني", "تلميحة") ||
                t.startsWith("hint") || t.startsWith("تلميح")
    }

    private fun matchesExplain(t: String): Boolean {
        return t in setOf("explain", "explanation", "why", "tell me more", "elaborate", "اشرح", "شرح", "وضح", "توضيح", "لماذا", "علل") ||
                t.startsWith("explain") || t.startsWith("اشرح")
    }

    private fun matchesShowAnswer(t: String): Boolean {
        return t in setOf(
            "show answer", "give answer", "what is the answer", "what's the answer", "reveal answer", "answer",
            "اظهر الجواب", "أظهر الجواب", "ما هو الجواب", "الجواب", "الحل", "اظهر الحل"
        )
    }

    private fun matchesSkip(t: String): Boolean {
        return t in setOf("skip", "next", "next card", "pass", "skip card", "التالي", "تخطي", "تجاوز", "البطاقة التالية", "عدي")
    }

    private fun matchesPause(t: String): Boolean {
        return t in setOf("pause", "pause study", "pause session", "hold on", "wait", "توقف", "توقف مؤقت", "انتظر", "استراحة")
    }

    private fun matchesResume(t: String): Boolean {
        return t in setOf("resume", "continue", "resume study", "resume session", "keep going", "اكمل", "استمر", "تابع", "واصل")
    }

    /**
     * "Stop talking" commands (§63) cancel speech but keep the session alive.
     * Checked BEFORE [matchesEnd] so "stop speaking" never ends the session,
     * while plain "stop" still maps to EndSession for backward compatibility.
     */
    private fun matchesStopSpeaking(t: String): Boolean {
        return t in setOf(
            "stop speaking", "stop talking", "be quiet", "quiet", "enough", "shut up", "silence", "hush",
            "توقف عن الكلام", "اكتف", "اكتفي", "اسكت", "اسكتي", "كفى", "كلام كفاية"
        )
    }

    private fun matchesEnd(t: String): Boolean {
        return t in setOf("stop", "end", "end session", "stop session", "finish", "quit", "انهاء", "إنهاء", "وقف", "خروج", "انهي الجلسة")
    }

    private fun matchesStatus(t: String): Boolean {
        return t in setOf(
            "how many cards left", "remaining cards", "cards remaining", "status", "how many left",
            "كم بطاقة متبقية", "كم باقي", "العدد المتبقي", "كم تبقى", "الحالة"
        )
    }

    private fun matchesStart(t: String): Boolean {
        return t in setOf("start", "start study", "start studying", "start session", "let's study", "ابدأ", "ابدأ الدراسة", "ابدأ الجلسة", "يلا ندرس") ||
                t.startsWith("start ") || t.startsWith("ابدأ ")
    }

    private fun extractDeckFromStartCommand(text: String): String? {
        val prefixes = listOf("start study on ", "start studying ", "start session ", "start ", "ابدأ دراسة ", "ابدأ ")
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                val rem = text.substring(prefix.length).trim()
                if (rem.isNotEmpty() && rem != "study" && rem != "session" && rem != "الدراسة" && rem != "الجلسة") {
                    return rem.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                }
            }
        }
        return null
    }
}
