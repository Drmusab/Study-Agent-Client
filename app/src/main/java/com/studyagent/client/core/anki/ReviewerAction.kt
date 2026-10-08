package com.studyagent.client.core.anki

/**
 * GATE 13 — the backend-neutral reviewer action family (STEP 2/§3, STEP 16).
 *
 * ```text
 * Flag   — card metadata only        (never rates, never advances the scheduler)
 * Bury   — removes the card from today's queues, turn-invalidating
 * Suspend— removes the card from reviews entirely, turn-invalidating
 * ```
 *
 * Deliberately **not** part of this family:
 *
 * - **Rating** — owned by GATE 11's transaction pipeline (`ReviewCommitLedger`,
 *   `AnkiBackend.commitRating`). There is no `ReviewerAction.Rate` for the same reason
 *   `BackendCommitResult` has no `ReviewerActionResult` variant: the two mutation families have
 *   different guarantees, different evidence and different failure semantics, and one enum covering
 *   both would be exactly the "generic unsafe mutation shortcut" AUDIT 1 forbids
 *   (INV-13-01/INV-13-02).
 * - **Unbury / unsuspend** — the pinned AnkiDroid v2.24.1 public provider contract exposes no
 *   write path for either (STEP 11/14/20: "Do not add actions whose public backend contract is
 *   not yet verified"), so no such action exists here. Restoring is a *browse* operation, not a
 *   reviewer action at the pinned contract.
 *
 * [invalidatesTurn] is the one behavioural classification (STEP 16): a flag leaves the presentation
 * and the scheduler untouched, while bury/suspend remove the card from what the scheduler will
 * hand back, so the current study turn cannot continue towards a rating (STEP 12/18/19).
 */
sealed interface ReviewerAction {

    val kind: ReviewerActionKind

    /** Stable, content-free token used in diagnostics, block reasons and capability maps. */
    val key: String

    /**
     * True when a *confirmed* action makes the current review turn unratable (STEP 16): the card
     * left the scheduler's queue, so continuing the presentation would rate a card that is no
     * longer being reviewed.
     */
    val invalidatesTurn: Boolean

    /**
     * Set (or clear, with [AnkiFlag.NONE]) the card's flag. Metadata only: it must never rate the
     * card, advance the scheduler or complete the review turn (STEP 10, INV-13-07/08).
     *
     * [flag] is the audited [AnkiFlag] domain value; [AnkiFlag.UNKNOWN] is rejected at construction
     * because "this build could not name the backend's flag" is not something a user can set.
     */
    data class SetFlag(val flag: AnkiFlag) : ReviewerAction {
        init {
            require(flag != AnkiFlag.UNKNOWN) { "An unknown flag is not a settable value" }
        }

        override val kind: ReviewerActionKind get() = ReviewerActionKind.FLAG
        override val key: String get() = "flag:${flag.name.lowercase()}"
        override val invalidatesTurn: Boolean get() = false
    }

    /** Bury the *card* for today (never the note: STEP 11 keeps the two distinct operations). */
    data object Bury : ReviewerAction {
        override val kind: ReviewerActionKind get() = ReviewerActionKind.BURY
        override val key: String get() = "bury"
        override val invalidatesTurn: Boolean get() = true
    }

    /** Suspend the *card* (never the note: STEP 11/14). */
    data object Suspend : ReviewerAction {
        override val kind: ReviewerActionKind get() = ReviewerActionKind.SUSPEND
        override val key: String get() = "suspend"
        override val invalidatesTurn: Boolean get() = true
    }

    companion object {
        /** Every action this build models, in menu order. */
        val ALL: List<ReviewerAction> = listOf(SetFlag(AnkiFlag.NONE), Bury, Suspend)
    }
}

/** The action kinds, for capability maps and UI state that must not depend on flag payloads. */
enum class ReviewerActionKind {
    FLAG,
    BURY,
    SUSPEND
}

/**
 * The minimal, backend-reported card state a reviewer action needs (STEP 38: "Do not rehydrate
 * card unnecessarily" — this is the small projection the action result may carry back).
 *
 * [queueCode] is the raw scheduler queue code from the *public* card contract, mapped by [fromQueue]
 * into the small set of states a reviewer action can produce. Anything unrecognised is
 * [ReviewerCardAvailability.UNKNOWN] — never guessed into "reviewable" or "buried"
 * (the same rule as [AnkiFlag.UNKNOWN]).
 */
data class ReviewerCardState(
    val queueCode: Int,
    val availability: ReviewerCardAvailability
) {
    val isBuried: Boolean get() = availability == ReviewerCardAvailability.BURIED
    val isSuspended: Boolean get() = availability == ReviewerCardAvailability.SUSPENDED

    /** True only when the scheduler could still hand this card to a reviewer. */
    val isReviewable: Boolean get() = availability == ReviewerCardAvailability.REVIEWABLE

    companion object {
        /**
         * Maps a raw `queue` code. The codes themselves are pinned in
         * [AnkiDroidApiContract] (`Card.RAW_QUEUE`: −3 manually buried, −2 sibling buried,
         * −1 suspended, 0..4 the ordinary queues) and re-declared here for the backend-neutral
         * domain, exactly like `AnkiFlag.fromBackendCode` re-declares the flag coding.
         */
        fun fromQueue(code: Int): ReviewerCardState = ReviewerCardState(code, when (code) {
            QUEUE_MANUALLY_BURIED, QUEUE_SIBLING_BURIED -> ReviewerCardAvailability.BURIED
            QUEUE_SUSPENDED -> ReviewerCardAvailability.SUSPENDED
            QUEUE_NEW, QUEUE_LEARNING, QUEUE_REVIEW, QUEUE_DAY_LEARNING, QUEUE_PREVIEW ->
                ReviewerCardAvailability.REVIEWABLE
            else -> ReviewerCardAvailability.UNKNOWN
        })

        const val QUEUE_MANUALLY_BURIED: Int = -3
        const val QUEUE_SIBLING_BURIED: Int = -2
        const val QUEUE_SUSPENDED: Int = -1
        const val QUEUE_NEW: Int = 0
        const val QUEUE_LEARNING: Int = 1
        const val QUEUE_REVIEW: Int = 2
        const val QUEUE_DAY_LEARNING: Int = 3
        const val QUEUE_PREVIEW: Int = 4
    }
}

/** What a reviewer action can say about a card's continued availability for the active session. */
enum class ReviewerCardAvailability {
    REVIEWABLE,
    BURIED,
    SUSPENDED,
    UNKNOWN
}

/**
 * GATE 13 STEP 5/§17 — the capability projection for reviewer actions.
 *
 * Derived from the *one* capability source ([AnkiCapabilities]) instead of being a second,
 * freely-writable capability store: a backend that cannot bury cannot advertise bury here either
 * (INV-13-17). A backend-neutral value type so the study layer can freeze the set at session start
 * (INV-13-15: a later global preference change must not re-shape an in-flight action).
 */
data class ReviewerActionCapabilities(
    val flag: Boolean = false,
    val bury: Boolean = false,
    val suspend: Boolean = false
) {
    fun supports(action: ReviewerAction): Boolean = when (action.kind) {
        ReviewerActionKind.FLAG -> flag
        ReviewerActionKind.BURY -> bury
        ReviewerActionKind.SUSPEND -> suspend
    }

    /** The kinds the UI may present, in a stable order (STEP 22: never a disabled lie). */
    val availableKinds: List<ReviewerActionKind>
        get() = ReviewerActionKind.entries.filter { kind ->
            when (kind) {
                ReviewerActionKind.FLAG -> flag
                ReviewerActionKind.BURY -> bury
                ReviewerActionKind.SUSPEND -> suspend
            }
        }

    val hasAny: Boolean get() = flag || bury || suspend

    companion object {
        /** Nothing is offered until a backend has proven it can do it. */
        val NONE = ReviewerActionCapabilities()
    }
}

/**
 * The one mapping from backend capabilities to reviewer-action capabilities.
 *
 * AnkiDroid's [AnkiCapabilities] names the suspend flag `suspendCards` because `suspend` is a
 * Kotlin keyword; the reviewer-action projection keeps the plain domain name.
 */
fun AnkiCapabilities.reviewerActions(): ReviewerActionCapabilities = ReviewerActionCapabilities(
    flag = flags,
    bury = bury,
    suspend = suspendCards
)
