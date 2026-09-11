package com.studyagent.client.core.voice.tts

/**
 * Centralized spoken formatting — the ONLY place labels/prefixes are added
 * (the repository must not sprinkle "Hint: ..." strings through study logic).
 *
 * Labels are intentionally minimal: over-labeling ("Feedback: ...", "Answer: ...")
 * makes long hands-free sessions feel robotic; short natural transitions are used
 * only where the content would otherwise be ambiguous (hint / answer reveal).
 */
object SpeechFormatting {

    /** Wrap [text] with an appropriate spoken lead-in for [purpose]. Most purposes get none. */
    fun forPurpose(purpose: SpeechPurpose, text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        return when (purpose) {
            SpeechPurpose.HINT -> "Here's a hint. $trimmed"
            SpeechPurpose.ANSWER -> "The answer is: $trimmed"
            else -> trimmed
        }
    }

    /** Built-in safe preview sentences. English preview doubles as a medical-pacing sample. */
    fun previewText(language: SegmentLanguage): String = when (language) {
        SegmentLanguage.ARABIC ->
            "هذا هو صوت الدراسة العربي. السؤال الأول. ما هي دواعي إجلاء الورم الدموي فوق الجافية؟"
        SegmentLanguage.ENGLISH, SegmentLanguage.NEUTRAL ->
            "This is your English study voice. Question one. " +
                "What are the indications for evacuation of an epidural hematoma?"
    }
}
