package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*

internal val fakeId = AnkiBackendId.Fake("contract")
internal val reviewCaps = FakeAnkiBackend.REVIEW_CAPABILITIES
internal fun deck(id: AnkiBackendId = fakeId, key: String? = "collection") =
    AnkiDeck(AnkiDeckRef(id, "deck-1", key), "Medicine::Cardiology::Arrhythmias")

internal fun card(rawId: String = "A", id: AnkiBackendId = fakeId, key: String? = "collection") =
    AnkiRenderedCard(
        ref = AnkiCardRef(id, rawId, "note-$rawId", 0, key),
        questionHtml = "<b>Question $rawId</b>", answerHtml = null,
        questionText = "Question $rawId", answerText = "Answer $rawId", pureAnswerText = null,
        noteRef = AnkiNoteRef(id, "note-$rawId", key), deckRef = deck(id, key).ref
    )

internal fun context(id: AnkiBackendId = fakeId, sessionId: String = "study-1") =
    AnkiSessionContext(id, AnkiCollectionIdentity(id, "collection"), deck(id).ref,
        startedAtEpochMs = 100, capabilities = reviewCaps, studySessionId = sessionId)

internal suspend fun FakeAnkiBackend.begin(context: AnkiSessionContext = context(id)): AnkiReviewSession =
    (beginReview(BeginReviewRequest(context)) as AnkiResult.Success).value

class FakeAnkiBackendContractTest : AnkiBackendContract() {
    override fun fixture(): Fixture {
        val cards = listOf(card("A"), card("B"), card("A"))
        return Fixture(FakeAnkiBackend(fakeId, listOf(deck()), cards, instanceId = "contract"),
            context(), deck(), cards)
    }
}
