package com.studyagent.client.anki

import com.studyagent.client.anki.fake.CardDetailsTestFixtures
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDetailsFakeBackendTest {
    @Test
    fun `fake details fixtures preserve siblings and all reads remain mutation free`() = runTest {
        val fixtures = CardDetailsTestFixtures.detailsByScenario
        val backend = FakeAnkiBackend(
            id = CardDetailsTestFixtures.backendId,
            cardDetails = CardDetailsTestFixtures.all()
        )
        val forward = fixtures.getValue("multi_card_forward")
        val reverse = fixtures.getValue("multi_card_reverse")

        val first = backend.getCardDetails(forward.cardRef) as AnkiResult.Success
        val second = backend.getCardDetails(reverse.cardRef) as AnkiResult.Success
        assertEquals(first.value.noteId, second.value.noteId)
        assertEquals(forward.cardRef, first.value.cardRef)
        assertEquals(reverse.cardRef, second.value.cardRef)
        assertEquals(listOf(0, 1), listOf(first.value.cardOrd, second.value.cardOrd))
        assertEquals(0, backend.commitInvocations)
        assertEquals(0, backend.nextCardCount)
        assertEquals(0, backend.reviewerActionInvocations)

        val deleted = backend.getCardDetails(CardDetailsTestFixtures.deletedCardRef)
        assertTrue(deleted is AnkiResult.Failure)
        assertTrue((deleted as AnkiResult.Failure).error is AnkiError.CardNotFound)
    }
}
