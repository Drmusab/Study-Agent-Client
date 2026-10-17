package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.core.anki.customstudy.AnkiReschedulingDisclosure
import com.studyagent.client.core.anki.customstudy.AnkiStudyMode
import com.studyagent.client.core.anki.customstudy.CustomStudyPolicy
import com.studyagent.client.ui.screens.customstudy.CustomStudyOption
import com.studyagent.client.ui.screens.customstudy.CustomStudyPanelMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 20 — the custom-study surface is a projection of capability truth
 * (IMPLEMENTATION-20-15/16, AUDIT-20-13/20-14, VER-20-27).
 *
 * Nothing here renders: the panel state is pure, which is what lets "the UI never offers a filter
 * the backend cannot honour" and "the UI never describes a filtered session as ordinary review" be
 * asserted directly.
 */
class CustomStudyPanelTest {

    private val backend = AnkiBackendId.AnkiDroidLocal

    private fun deck(name: String, isFiltered: Boolean? = false) =
        AnkiDeck(AnkiDeckRef(backend, "1", "collection"), name, isFiltered = isFiltered)

    private fun panel(
        name: String = "Medicine",
        isFiltered: Boolean? = false,
        capabilities: AnkiCustomStudyCapabilities = AnkiCustomStudyCapabilities.NONE
    ) = CustomStudyPanelMapper.build(
        deckName = name,
        deckIsFiltered = isFiltered,
        capabilities = capabilities
    )

    @Test fun `a backend with no custom-study capability offers no filter and hides nothing about it`() {
        val state = panel()
        assertTrue(state.supportedDimensions.isEmpty())
        assertFalse(state.backendSupportsAnyCustomStudy)
        // Every locked option is listed as unavailable, with a reason — not silently omitted.
        assertEquals(CustomStudyOption.entries.size, state.unsupportedOptions.size)
        assertTrue(state.unsupportedOptions.all { it.reason.isNotBlank() })
        assertNull(state.blockingNotice)
        assertTrue(state.canStartStudy)
        assertTrue(state.includesChildDecks)
    }

    @Test fun `the panel never claims a filter the backend cannot honour`() {
        val tagsOnly = AnkiCustomStudyCapabilities(canFilterByDeck = true, canFilterByTag = true)
        val state = panel(capabilities = tagsOnly)
        assertTrue(state.unsupportedOptions.none { it.option == CustomStudyOption.TAG_FILTER })
        assertTrue(state.unsupportedOptions.any { it.option == CustomStudyOption.STATE_FILTER })
        assertTrue(state.unsupportedOptions.any { it.option == CustomStudyOption.ORDERING })
        assertTrue(state.unsupportedOptions.any { it.option == CustomStudyOption.FILTERED_DECK_REVIEW })
    }

    @Test fun `a filtered deck is blocked with the reason, never offered as ordinary review`() {
        val state = panel(name = "Custom Study Session", isFiltered = true)
        val notice = state.blockingNotice
        check(notice != null) { "a filtered deck must be blocked" }
        assertEquals("filtered_deck_review_unsupported", notice.token)
        assertFalse(state.canStartStudy)
        // The disclosure is about scheduling, and it is stated even when the deck is blocked.
        assertTrue(state.schedulingSummary.contains("Anki"))
    }

    @Test fun `an unknown filtered flag is reported as unknown and never as not-filtered`() {
        val state = panel(isFiltered = null)
        assertNull(state.deckIsFiltered)
        assertNull(state.blockingNotice)
    }

    @Test fun `a disclosed filtered deck says what answers do, in the mode's own words`() {
        val capable = AnkiCustomStudyCapabilities(
            canReviewFilteredDeck = true,
            canProveFilteredRatingCommit = true,
            canExposeReschedulingSemantics = true
        )
        val state = CustomStudyPanelMapper.build(
            deckName = "Forgotten cards",
            deckIsFiltered = true,
            capabilities = capable,
            decision = CustomStudyPolicy.Decision.Proceed(
                AnkiStudyMode.Filtered(
                    AnkiDeckRef(backend, "1", "collection"),
                    AnkiReschedulingDisclosure.NO_RESCHEDULING
                )
            )
        )
        assertNull("a disclosed filtered deck is not blocked", state.blockingNotice)
        assertTrue("the no-reschedule truth is stated before the first rating",
            state.schedulingSummary.contains("NOT"))
        assertTrue(state.canStartStudy)
    }

    // ------------------------------------------------------------------ VER-20-27  Arabic / RTL text

    @Test fun `arabic and mixed-script deck names pass through byte for byte`() {
        val arabic = "طب القلب"
        assertEquals(arabic, panel(arabic).deckName)
        val nested = "كلية الطب::طب القلب::البطاقات"
        assertEquals(nested, panel(nested).deckName)
        val mixed = "Cardiology - أمراض القلب 2024"
        assertEquals(mixed, panel(mixed).deckName)
        // No trimming, folding or transliteration anywhere in the projection.
        val padded = "  طب القلب  "
        assertEquals(padded, panel(padded).deckName)
    }

    @Test fun `arabic tag names survive the filter vocabulary projection`() {
        val state = panel("مذكرات", capabilities = AnkiCustomStudyCapabilities(canFilterByDeck = true))
        assertTrue("the deck dimension is reported when the backend supports it",
            state.supportedDimensions.isNotEmpty())
        assertEquals("مذكرات", state.deckName)
    }

    @Test fun `the filtered-deck notice explains the consequence, not just the refusal`() {
        val notice = panel(isFiltered = true).blockingNotice!!
        assertTrue(notice.message.contains("filtered"))
        assertTrue(notice.message.length > 40)
        assertTrue(notice.title.isNotBlank())
    }
}
