package com.studyagent.client.ui.screens.customstudy

import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.core.anki.customstudy.AnkiStudyMode
import com.studyagent.client.core.anki.customstudy.CustomStudyPolicy
import com.studyagent.client.core.anki.customstudy.CustomStudyFilterDimension

/**
 * GATE 20 — presentation models for the custom-study surface (IMPLEMENTATION-20-14/15/16).
 *
 * This file is deliberately free of Compose and of any backend type: the panel is a **pure
 * projection of capability truth**, which is what makes AUDIT-20-13 (capability honesty) and
 * AUDIT-20-14 (study UI truth) testable without a UI.
 *
 * The panel never offers a control the locked contract does not support. An unsupported filter is
 * rendered as an explanation of *why* it is unavailable — never as a disabled control that hints it
 * might work later on this backend, and never as an approximation (INV-20-06).
 */

/** The options the locked contract knows about. Not every backend offers any of them. */
enum class CustomStudyOption {
    TAG_FILTER,
    STATE_FILTER,
    FLAG_FILTER,
    CARD_LIMIT,
    ORDERING,
    NO_RESCHEDULE,
    PREVIEW_ONLY,
    FILTERED_DECK_REVIEW
}

/** One locked option this backend cannot honour, with the reason it cannot. */
data class CustomStudyUnavailableOption(
    val option: CustomStudyOption,
    val title: String,
    val reason: String
)

/**
 * A fact the user must see **before** a session starts because it changes what studying means
 * (DECISION-20-22, AUDIT-20-14). Never rendered as a footnote.
 */
data class CustomStudyBlockingNotice(
    val title: String,
    val message: String,
    /** The stable policy token behind the notice; never parsed, only logged/shown in diagnostics. */
    val token: String
)

/**
 * Everything the custom-study panel renders.
 *
 * [deckName] is passed through **verbatim** from the backend: no trimming, no case folding, no
 * truncation, so Arabic and mixed-script deck names render exactly as Anki stores them (VER-20-27).
 */
data class CustomStudyPanelState(
    val deckName: String,
    /** `true` = backend says filtered, `false` = backend says not, `null` = the backend did not say. */
    val deckIsFiltered: Boolean?,
    val includesChildDecks: Boolean,
    val schedulingSummary: String,
    val unsupportedOptions: List<CustomStudyUnavailableOption>,
    val supportedDimensions: Set<CustomStudyFilterDimension>,
    val blockingNotice: CustomStudyBlockingNotice?,
    /**
     * Whether a session may be started from this deck right now **in some mode**. False only when
     * the policy refused the deck: a filtered deck a backend can review and disclose is `true`
     * (the session starts in [AnkiStudyMode.Filtered], never labelled as ordinary review).
     */
    val canStartStudy: Boolean
) {
    /** True when this backend can do anything at all beyond ordinary deck review. */
    val backendSupportsAnyCustomStudy: Boolean get() = supportedDimensions.isNotEmpty()
}

/** Pure mapper: capabilities + one deck snapshot → exactly what the UI may claim. */
object CustomStudyPanelMapper {

    /**
     * Build the panel for a deck.
     *
     * @param deckIsFiltered the live listing's filtered flag; `null` is rendered as "unknown",
     *   never as "not filtered".
     * @param decision the [CustomStudyPolicy] verdict for this deck (pass `null` to have it
     *   recomputed from [capabilities]).
     */
    fun build(
        deckName: String,
        deckIsFiltered: Boolean?,
        capabilities: AnkiCustomStudyCapabilities,
        decision: CustomStudyPolicy.Decision? = null
    ): CustomStudyPanelState {
        val resolved = decision
            ?: if (deckIsFiltered == true) {
                CustomStudyPolicy.Decision.Refused(
                    if (capabilities.canReviewFilteredDeck) {
                        CustomStudyPolicy.Refusal.FILTERED_RESCHEDULING_UNDISCLOSED
                    } else {
                        CustomStudyPolicy.Refusal.FILTERED_DECK_REVIEW_UNSUPPORTED
                    }
                )
            } else {
                CustomStudyPolicy.Decision.Proceed(AnkiStudyMode.Normal)
            }

        val blocking = (resolved as? CustomStudyPolicy.Decision.Refused)?.let { refusal ->
            CustomStudyBlockingNotice(
                title = when (refusal.refusal) {
                    CustomStudyPolicy.Refusal.FILTERED_DECK_REVIEW_UNSUPPORTED ->
                        "This deck is a filtered deck"
                    CustomStudyPolicy.Refusal.FILTERED_RESCHEDULING_UNDISCLOSED ->
                        "Scheduling behaviour is unknown"
                    CustomStudyPolicy.Refusal.FOREIGN_DECK -> "This deck belongs to another backend"
                },
                message = when (refusal.refusal) {
                    CustomStudyPolicy.Refusal.FILTERED_DECK_REVIEW_UNSUPPORTED ->
                        "Study-Agent cannot review a filtered deck through this backend: it could not " +
                            "prove what an answer did, so no session is started. Study or empty this " +
                            "filtered deck in Anki."
                    CustomStudyPolicy.Refusal.FILTERED_RESCHEDULING_UNDISCLOSED ->
                        "This backend cannot say, before a session starts, whether answers inside this " +
                            "filtered deck reschedule the cards. Study-Agent will not guess, so no " +
                            "session is started."
                    CustomStudyPolicy.Refusal.FOREIGN_DECK ->
                        "The deck belongs to a different backend than the one that would run the session."
                },
                token = CustomStudyPolicy.tokenFor(refusal.refusal)
            )
        }

        val mode = (resolved as? CustomStudyPolicy.Decision.Proceed)?.mode
        val schedulingSummary = when (mode) {
            is AnkiStudyMode.Filtered -> mode.rescheduling.schedulingSummary()
            else -> "Answers are scheduled by Anki. Study-Agent never chooses, reorders or " +
                "re-schedules a card."
        }

        return CustomStudyPanelState(
            deckName = deckName,
            deckIsFiltered = deckIsFiltered,
            // Backend fact, not a user preference: the queue is built from the deck and its
            // descendants, and no backend in this gate can exclude them (DISCOVERY-20-28).
            includesChildDecks = true,
            schedulingSummary = schedulingSummary,
            unsupportedOptions = unavailableOptions(capabilities),
            supportedDimensions = capabilities.supportedFilterVocabulary(),
            blockingNotice = blocking,
            canStartStudy = resolved is CustomStudyPolicy.Decision.Proceed
        )
    }

    /**
     * Every locked option this backend cannot honour. The reasons name the *backend fact*
     * ("the public API exposes no tag filter"), never a product apology.
     */
    private fun unavailableOptions(capabilities: AnkiCustomStudyCapabilities): List<CustomStudyUnavailableOption> =
        buildList {
            if (!capabilities.canFilterByTag) {
                add(
                    CustomStudyUnavailableOption(
                        option = CustomStudyOption.TAG_FILTER,
                        title = "Study only cards with chosen tags",
                        reason = "This backend offers no way to build a review queue from tags."
                    )
                )
            }
            if (!capabilities.canFilterByState) {
                add(
                    CustomStudyUnavailableOption(
                        option = CustomStudyOption.STATE_FILTER,
                        title = "New / learning / due only",
                        reason = "Card-state selection is not part of this backend's public review API."
                    )
                )
            }
            if (!capabilities.canFilterByFlag) {
                add(
                    CustomStudyUnavailableOption(
                        option = CustomStudyOption.FLAG_FILTER,
                        title = "Filter by flag",
                        reason = "This backend cannot confine a review queue to flagged cards."
                    )
                )
            }
            if (!capabilities.canLimitCardCount) {
                add(
                    CustomStudyUnavailableOption(
                        option = CustomStudyOption.CARD_LIMIT,
                        title = "Limit how many cards enter the session",
                        reason = "Card limits belong to the backend's session builder, which this " +
                            "backend does not expose. A session-length target is a Study-Agent stop " +
                            "condition and is not a card filter."
                    )
                )
            }
            if (!capabilities.canSelectOrdering) {
                add(
                    CustomStudyUnavailableOption(
                        option = CustomStudyOption.ORDERING,
                        title = "Choose the card order",
                        reason = "Ordering is owned by the backend's scheduler and is never re-sorted " +
                            "locally."
                    )
                )
            }
            if (!capabilities.canSelectNoReschedule) {
                add(
                    CustomStudyUnavailableOption(
                        option = CustomStudyOption.NO_RESCHEDULE,
                        title = "Study without rescheduling",
                        reason = "This backend cannot be asked to leave normal scheduling untouched."
                    )
                )
            }
            if (!capabilities.canPreviewOnly) {
                add(
                    CustomStudyUnavailableOption(
                        option = CustomStudyOption.PREVIEW_ONLY,
                        title = "Preview-only study",
                        reason = "No preview-only session exists in this backend's public contract."
                    )
                )
            }
            if (!capabilities.canReviewFilteredDeck) {
                add(
                    CustomStudyUnavailableOption(
                        option = CustomStudyOption.FILTERED_DECK_REVIEW,
                        title = "Filtered deck review",
                        reason = "Answers inside a filtered deck cannot be proven on this backend, so a " +
                            "session there would stop at the first rating."
                    )
                )
            }
        }
}
