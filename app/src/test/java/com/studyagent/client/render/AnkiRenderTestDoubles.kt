package com.studyagent.client.render

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.render.AnkiCardDocument
import com.studyagent.client.core.render.AnkiCardRenderSurface
import com.studyagent.client.core.render.AnkiRenderEvent

/**
 * GATE 08 test doubles for the renderer.
 *
 * Everything the pure renderer needs to be tested without Chromium, Compose or a device: a controllable
 * clock, a recording [AnkiCardRenderSurface] (the seam the WebView implements in production), an event
 * recorder, and card builders that mirror GATE 07's real contract shape — fragments, an answer side that
 * already contains the question through `{{FrontSide}}` + `<hr id=answer>`, and nullability that means
 * something.
 */

internal val renderBackend = AnkiBackendId.Fake("render-tests")
private const val RENDER_COLLECTION = "render-collection"
private val renderDeck = AnkiDeckRef(renderBackend, "deck-render", RENDER_COLLECTION)

/** Deterministic clock: durations in tests are chosen, never slept for. */
internal class TestRenderClock(var now: Long = 1_000L) : AppClock {
    override fun nowMillis(): Long = now
    fun advance(millis: Long) {
        require(millis >= 0L) { "a clock never goes backwards in these tests" }
        now += millis
    }
}

/** The [AnkiCardRenderSurface] seam, recording instead of rendering. */
internal class FakeRenderSurface(
    override var isUsable: Boolean = true
) : AnkiCardRenderSurface {

    val documents = mutableListOf<AnkiCardDocument>()
    var resetScrollCalls = 0
        private set
    var releaseCalls = 0
        private set

    override fun presentDocument(document: AnkiCardDocument) {
        documents += document
    }

    override fun resetScroll() {
        resetScrollCalls++
    }

    override fun release() {
        releaseCalls++
    }

    val lastDocument: AnkiCardDocument? get() = documents.lastOrNull()
    val lastHtml: String? get() = lastDocument?.html
}

/** Collects renderer events so assertions are about facts, not about parsing log text. */
internal class RenderEventRecorder {
    val events = mutableListOf<AnkiRenderEvent>()

    val sink: (AnkiRenderEvent) -> Unit = { events += it }

    val names: List<String> get() = events.map { it.name }

    internal inline fun <reified T : AnkiRenderEvent> only(): List<T> = events.filterIsInstance<T>()

    internal inline fun <reified T : AnkiRenderEvent> last(): T? = only<T>().lastOrNull()

    fun count(name: String): Int = events.count { it.name == name }

    /** Every metadata value and log line the renderer produced, for privacy assertions. */
    val allLoggedText: String
        get() = events.joinToString("\n") { event ->
            buildString {
                append(event.name).append('\n')
                event.turnId?.let { append(it).append('\n') }
                event.metadata.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
                append(event.logLine())
            }
        }
}

internal fun turnId(value: String = "7:card-1:1") = ReviewTurnId(value)

/**
 * A card shaped exactly like GATE 07's AnkiDroid output: rendered **fragments**, and an answer side that
 * already contains the question (`{{FrontSide}}`) followed by `<hr id=answer>`.
 */
internal fun renderCard(
    id: String = "card-1",
    questionHtml: String? = QUESTION_HTML,
    answerHtml: String? = ANSWER_HTML,
    questionText: String? = QUESTION_TEXT,
    answerText: String? = ANSWER_TEXT,
    pureAnswerText: String? = PURE_ANSWER_TEXT,
    degradations: List<String> = emptyList()
): AnkiRenderedCard {
    val noteRef = AnkiNoteRef(renderBackend, "note-$id", RENDER_COLLECTION)
    return AnkiRenderedCard(
        ref = AnkiCardRef(renderBackend, id, noteRef.noteId, 0, RENDER_COLLECTION),
        questionHtml = questionHtml,
        answerHtml = answerHtml,
        questionText = questionText,
        answerText = answerText,
        pureAnswerText = pureAnswerText,
        metadata = com.studyagent.client.core.anki.AnkiCardMetadata(
            deckName = "Rendering::Tests",
            templateName = "Card 1"
        ),
        noteRef = noteRef,
        deckRef = renderDeck,
        degradations = degradations
    )
}

/** Distinctive markers so a test can prove what was loaded without dumping whole documents. */
internal const val QUESTION_MARKER = "QUESTION-MARKER-9f3a"
internal const val ANSWER_MARKER = "ANSWER-MARKER-7c11"

internal const val QUESTION_HTML = "<div class=\"card\"><b>$QUESTION_MARKER</b></div>"
internal const val ANSWER_HTML =
    "<div class=\"card\"><b>$QUESTION_MARKER</b></div>\n<hr id=answer>\n<p>$ANSWER_MARKER</p>"
internal const val QUESTION_TEXT = "plain $QUESTION_MARKER text"
internal const val ANSWER_TEXT = "plain $ANSWER_MARKER text"
internal const val PURE_ANSWER_TEXT = ANSWER_MARKER

/** Arabic + emoji fixture: the Unicode fidelity check (STEP 37/§79, INV-RENDER-27). */
internal const val ARABIC_HTML = "<div dir=\"rtl\">ما هو القلب؟ — عضلة ❤️ 🫀 تضخ الدم</div>"
