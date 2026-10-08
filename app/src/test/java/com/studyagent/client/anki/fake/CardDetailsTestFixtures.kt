package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.*

/**
 * GATE 16 fixture matrix for the fake backend. Content is test-only and never shipped to DI.
 * Scenarios cover normal/multi-field/multi-card/HTML/media/Cloze/Arabic/mixed-direction content,
 * every normalized card type/status family, flags, absent metadata, deletion and corrupt note links.
 */
object CardDetailsTestFixtures {
    val backendId: AnkiBackendId = AnkiBackendId.Fake("gate16-fixtures")
    private const val COLLECTION = "fixture-collection"
    private val deck = AnkiDeckRef(backendId, "deck-1", COLLECTION)

    private fun ref(noteId: String, cardId: String, ordinal: Int) = AnkiCardRef(
        backendId = backendId,
        cardId = cardId,
        noteId = noteId,
        cardOrd = ordinal,
        collectionKey = COLLECTION
    )

    private fun note(noteId: String) = AnkiNoteRef(backendId, noteId, COLLECTION)

    private fun card(
        noteId: String,
        cardId: String,
        ordinal: Int,
        template: String,
        questionHtml: String?,
        answerHtml: String?,
        questionText: String?,
        answerText: String?,
        pureAnswerText: String? = answerText,
        fields: List<AnkiNoteField>?,
        tags: List<String>? = emptyList(),
        type: AnkiCardType? = AnkiCardType.NEW,
        queue: AnkiCardQueueState? = AnkiCardQueueState.NEW,
        scheduling: AnkiSchedulingInfo? = AnkiSchedulingInfo(reps = 0, lapses = 0, intervalDays = 0),
        flag: AnkiFlag? = null,
        media: List<String> = emptyList(),
        modelName: String? = "Basic"
    ) = AnkiCardDetails(
        cardRef = ref(noteId, cardId, ordinal),
        noteRef = note(noteId),
        cardOrd = ordinal,
        deckRef = deck,
        deckName = "Gate 16::Fixtures",
        noteTypeId = "model-1",
        noteTypeName = modelName,
        templateName = template,
        questionHtml = questionHtml,
        answerHtml = answerHtml,
        questionText = questionText,
        answerText = answerText,
        pureAnswerText = pureAnswerText,
        fields = fields,
        tags = tags,
        flag = flag,
        cardType = type,
        queueState = queue,
        scheduling = scheduling,
        originalDeckRef = null,
        noteCreatedEpochSeconds = null,
        noteModifiedEpochSeconds = 1_700_000_000L,
        mediaFiles = media
    )

    val detailsByScenario: Map<String, AnkiCardDetails> = linkedMapOf(
        "basic" to card(
            "note-basic", "card-basic", 0, "Card 1",
            "<p>Capital of France?</p>", "<hr id=answer>Paris", "Capital of France?", "Paris",
            fields = listOf(AnkiNoteField("Front", "Capital of France?", 0), AnkiNoteField("Back", "Paris", 1)),
            tags = emptyList(), scheduling = AnkiSchedulingInfo(reps = 0, lapses = 0, intervalDays = 0)
        ),
        "multi_field" to card(
            "note-multi", "card-multi", 0, "Card 1",
            "<b>Term</b>", "<b>Term</b><hr id=answer>Definition", "Term", "Definition",
            fields = listOf(
                AnkiNoteField("Front", "<b>Term</b>", 0),
                AnkiNoteField("Back", "Definition", 1),
                AnkiNoteField("Notes", "Context", 2)
            ), tags = listOf("vocabulary", "chapter::one")
        ),
        "multi_card_forward" to card(
            "note-two-cards", "card-two-forward", 0, "Forward",
            "Word?", "Word?<hr id=answer>Meaning", "Word?", "Meaning",
            fields = listOf(AnkiNoteField("Word", "word", 0), AnkiNoteField("Meaning", "meaning", 1))
        ),
        "multi_card_reverse" to card(
            "note-two-cards", "card-two-reverse", 1, "Reverse",
            "Meaning?", "Meaning?<hr id=answer>Word", "Meaning?", "Word",
            fields = listOf(AnkiNoteField("Word", "word", 0), AnkiNoteField("Meaning", "meaning", 1))
        ),
        "html" to card(
            "note-html", "card-html", 0, "HTML",
            "<table><tr><td>left</td><td>right</td></tr></table>", "<div class=answer>authored</div>",
            "left right", "authored", fields = listOf(AnkiNoteField("Front", "<b>left</b>", 0))
        ),
        "media" to card(
            "note-media", "card-media", 0, "Media",
            "<img src=\"card.png\">[sound:prompt.mp3]", "<img src=\"answer.webp\">",
            "Image and sound", "Answer image", fields = listOf(AnkiNoteField("Front", "<img src=\"card.png\">", 0)),
            media = listOf("card.png", "prompt.mp3", "answer.webp")
        ),
        "cloze" to card(
            "note-cloze", "card-cloze", 0, "Cloze",
            "The {{c1::mitochondrion}} is the powerhouse.", "The mitochondrion is the powerhouse.",
            "The … is the powerhouse.", "The mitochondrion is the powerhouse.",
            fields = listOf(AnkiNoteField("Text", "The {{c1::mitochondrion}} is the powerhouse.", 0)),
            modelName = "Cloze"
        ),
        "arabic" to card(
            "note-arabic", "card-arabic", 0, "Arabic",
            "<div dir=rtl>ما عاصمة مصر؟</div>", "<div dir=rtl>القاهرة</div>", "ما عاصمة مصر؟", "القاهرة",
            fields = listOf(AnkiNoteField("السؤال", "ما عاصمة مصر؟", 0), AnkiNoteField("الإجابة", "القاهرة", 1))
        ),
        "mixed_rtl_ltr" to card(
            "note-mixed", "card-mixed", 0, "Mixed",
            "<p dir=auto>ECG — ما معنى QRS؟</p>", "<p>QRS مركب البطينين</p>",
            "ECG — ما معنى QRS؟", "QRS مركب البطينين",
            fields = listOf(AnkiNoteField("Front / الواجهة", "ECG — ما معنى QRS؟", 0))
        ),
        "new" to card(
            "note-new", "card-new", 0, "New", "New question", "New answer", "New question", "New answer",
            fields = listOf(AnkiNoteField("Front", "New question", 0)),
            type = AnkiCardType.NEW, queue = AnkiCardQueueState.NEW,
            scheduling = AnkiSchedulingInfo(reps = 0, lapses = 0, intervalDays = 0)
        ),
        "learning" to card(
            "note-learning", "card-learning", 0, "Learning", "Learning question", "Learning answer",
            "Learning question", "Learning answer", fields = listOf(AnkiNoteField("Front", "Learning question", 0)),
            type = AnkiCardType.LEARNING, queue = AnkiCardQueueState.LEARNING,
            scheduling = AnkiSchedulingInfo(reps = 2, lapses = 0, intervalDays = 0)
        ),
        "review" to card(
            "note-review", "card-review", 0, "Review", "Review question", "Review answer",
            "Review question", "Review answer", fields = listOf(AnkiNoteField("Front", "Review question", 0)),
            type = AnkiCardType.REVIEW, queue = AnkiCardQueueState.REVIEW,
            scheduling = AnkiSchedulingInfo(reps = 12, lapses = 2, intervalDays = 21, rawDue = 30000L)
        ),
        "suspended" to card(
            "note-suspended", "card-suspended", 0, "Suspended", "Suspended?", "Yes", "Suspended?", "Yes",
            fields = listOf(AnkiNoteField("Front", "Suspended?", 0)),
            type = AnkiCardType.REVIEW, queue = AnkiCardQueueState.SUSPENDED
        ),
        "flagged" to card(
            "note-flagged", "card-flagged", 0, "Flagged", "Flagged?", "Yes", "Flagged?", "Yes",
            fields = listOf(AnkiNoteField("Front", "Flagged?", 0)), flag = AnkiFlag.BLUE
        ),
        "missing_metadata" to card(
            "note-unknown", "card-unknown", 0, "Unknown", "Question", "Answer", "Question", "Answer",
            fields = null, tags = null, type = null, queue = null, scheduling = null,
            modelName = null
        )
    )

    val deletedCardRef: AnkiCardRef = ref("note-deleted", "card-deleted", 0)
    val corruptRelationshipError: AnkiError = AnkiError.DataIntegrityFailure("note_missing_for_card")

    fun all(): List<AnkiCardDetails> = detailsByScenario.values.toList()
}
