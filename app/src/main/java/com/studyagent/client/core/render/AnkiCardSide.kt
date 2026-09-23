package com.studyagent.client.core.render

/**
 * GATE 08 — which visual state of one review turn is being presented (STEP 05).
 *
 * The side is **always explicit**. Nothing in the renderer may infer "the answer is showing" from
 * the shape of the content (`answerHtml != null`, `answerHtml.isNotBlank()`, a `<hr id=answer>`
 * marker in the payload, a study-phase guess): the parent Study UI owns the reveal decision and
 * passes it down (STEP 93, INV-ANKI-RENDER-04). A card whose answer HTML exists is still a
 * *question* until somebody says so.
 *
 * Both values are two visual states of the **same** `ReviewTurnId` (STEP 31): flipping
 * [QUESTION] → [ANSWER] never mints a new turn and never touches the scheduler
 * (INV-ANKI-RENDER-05/03).
 */
enum class AnkiCardSide {
    /** The card's rendered question side (`AnkiRenderedCard.questionHtml` / `questionText`). */
    QUESTION,

    /**
     * The card's rendered answer side (`AnkiRenderedCard.answerHtml` / `answerText`).
     *
     * By the pinned AnkiDroid contract the answer side *already contains* the question when the
     * template uses `{{FrontSide}}`, followed by `<hr id=answer>`. The renderer therefore never
     * prepends the question to the answer (STEP 28/§29, INV-ANKI-RENDER-12).
     */
    ANSWER
}
