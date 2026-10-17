package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.core.anki.customstudy.AnkiReschedulingDisclosure

/**
 * GATE 20 — what the **wired AnkiDroid adapter** claims, after auditing AnkiDroid's public
 * provider contract at the pinned release (DISCOVERY-20-35, DECISION-20-21, AUDIT-20-13).
 *
 * The pinned release is **AnkiDroid v2.24.1** (`api` module 2.0.0, source commit
 * `9f579c10bb151146728220729c510acbbd8faba7`); the scheduler beneath it is Anki `25.09.2`.
 * Claims below are about the *public* provider (`FlashCardsContract` / `CardContentProvider`),
 * because that is the only surface this app may use — never about what the AnkiDroid app does with
 * its own internal classes.
 *
 * ## Why every flag is `false`
 *
 * | Property | Evidence |
 * |---|---|
 * | No custom-study / filtered-deck **creation** | The provider's complete URI set is `notes`, `notes_v2`, `notes/#`, `notes/#/cards`, `notes/#/cards/#`, `models`, `models/<id>`, `models/<id>/empty_cards`, `models/<id>/templates`, `models/<id>/templates/#`, `models/<id>/fields`, `schedule/`, `decks/`, `decks/#`, `selected_deck/`, `media`, `cards`, `cards/#` (`CardContentProvider.addUri`, v2.24.1). No custom-study, rebuild, empty or search URI exists. `cards/#` `update` even refuses a filtered target: `require(!col.decks.isFiltered(did)) { "Cards cannot be moved to a filtered deck" }`. |
 * | No filter, limit, ordering or rescheduling input | `schedule` accepts exactly two selection keys, `limit` and `deckID` (`CardContentProvider.query`, SCHEDULE branch). `limit` is the number of rows returned (`getQueuedCards(fetchLimit = limit)`), not a filtered-deck size and not a session length. |
 * | Filtered decks are *visible* but not *selectable as a mode* | `Deck.DECK_DYN` (`deck_dyn`) reports `col.decks.isFiltered(id)` per deck, so the client can **detect** a filtered deck. Detection is not support: nothing in the public contract lets the client ask for a filtered session, and the deck's `reschedule` config is not exposed. |
 * | Ratings inside a filtered deck cannot be proven | A card in a filtered deck carries `original_deck_id != 0`; GATE 11's classifier treats exactly that case as `OutcomeUnknown` (`filtered_deck_result_unverified`) and its reconciliation stays `StillAmbiguous` forever. `canReviewFilteredDeck` therefore cannot be claimed — the capability type refuses the combination, and the session would stop at its first rating. |
 * | Rescheduling semantics are not disclosable before a start | The deck-level `reschedule` flag is absent from `FlashCardsContract.Deck`. A per-card `queue = 4` (`PreviewRepeat`) can be observed **after** a card is scheduled, which is too late for the pre-start disclosure DECISION-20-22 requires, and it says nothing about the deck as a whole. |
 *
 * What remains available is ordinary deck review — see [DECK_SCOPED] — whose scheduler authority is
 * AnkiDroid's own scheduler.
 */
object AnkiDroidCustomStudyCapabilities {

    /**
     * The audited claim. Every custom-study capability is `false` at this pin: the adapter can
     * *detect* filtered decks (through `deck_dyn`) and must refuse to study one, and it can neither
     * create, rebuild, empty, filter, order nor de-schedule anything.
     */
    val AUDITED: AnkiCustomStudyCapabilities = AnkiCustomStudyCapabilities.NONE

    /**
     * GATE 20 — the honest answer for "what do answers do in this filtered deck?" on AnkiDroid.
     *
     * The public contract exposes no deck-level `reschedule` flag, so the adapter refuses to
     * answer rather than infer one from the deck name, from a card's queue value observed later, or
     * from what AnkiDroid's own UI would do. A refusal here is what makes
     * [com.studyagent.client.core.anki.customstudy.CustomStudyPolicy] fail closed.
     */
    suspend fun filteredDeckRescheduling(deckRef: AnkiDeckRef): AnkiResult<AnkiReschedulingDisclosure> =
        AnkiResult.Failure(AnkiError.UnsupportedAction(action = "filtered_deck_rescheduling"))

    /**
     * The one thing this adapter *can* truthfully say about scope: a deck-scoped review on AnkiDroid
     * always includes descendant decks, because the queue is built from
     * `child_decks(current_deck)` + the deck itself (`QueueBuilder::new`, rslib 25.09.2) after the
     * provider temporarily selects `deckID` (`selectDeckWithCheck`). There is no "this deck only"
     * option anywhere in the public contract (DISCOVERY-20-28, DECISION-20-04).
     */
    const val DECK_SCOPE_INCLUDES_DESCENDANTS: Boolean = true
}
