package com.studyagent.client.core.anki

/**
 * GATE 13 — NORMATIVE NAMING MODEL for reviewer actions.
 *
 * One name belongs to exactly one layer, exactly like [ReviewCommitNaming] does for ratings:
 *
 * | Layer | Type | Example |
 * |---|---|---|
 * | study workflow | `SessionPhase` | `WaitingForRating` |
 * | durable action transaction truth | [ReviewerActionStatus] | `AMBIGUOUS` |
 * | backend evidence | [ReviewerActionBackendResult] | `OutcomeUnknown` |
 * | backend evidence payload | [ReviewerActionReceipt] | `queue=-3` |
 * | coordinator result | [ReviewerActionOutcome] | `Ambiguous` |
 * | recovery decision | [ReviewerActionRecoveryAction] | `Reconcile` |
 * | reconciliation evidence | [ReviewerActionReconciliationResult] | `Unresolved` |
 * | presentation | `ReviewerActionUiState` | `VerificationRequired` |
 *
 * The two mutation families never share a vocabulary (INV-13-01…INV-13-05):
 *
 * ```text
 * ReviewCommitStatus   / ReviewCommitLedger   → rating transaction truth
 * ReviewerActionStatus / ReviewerActionLedger → reviewer-action transaction truth
 * ```
 */
object ReviewerActionNaming

/**
 * GATE 13 §3 — the canonical reviewer action family.
 *
 * ```text
 * SetFlag     — card metadata only      (never rates, never advances the scheduler)
 * BuryCard    — leaves today's queues    turn-invalidating
 * SuspendCard — leaves reviews entirely  turn-invalidating
 * ```
 *
 * Deliberately **not** part of this family:
 *
 * - **Rating** — owned by GATE 11's transaction pipeline ([ReviewCommitLedger],
 *   [AnkiBackend.commitRating]). There is no `ReviewerAction.Rate`: the two mutation families have
 *   different guarantees, different evidence and different failure semantics, and one enum covering
 *   both would be exactly the generic-mutation shortcut AUDIT 1 forbids (INV-13-04/INV-13-05).
 * - **Unbury / unsuspend** — §3: "Add further actions only after their public backend contracts are
 *   audited." The pinned AnkiDroid v2.24.1 public provider contract exposes no write path for
 *   either, so no such action exists here.
 *
 * [invalidatesCurrentTurn] is the one behavioural classification (§21) and it is a *domain
 * property*, not a `when(action)` scattered through the UI: a flag leaves the presentation and the
 * scheduler untouched, while bury/suspend remove the card from what the scheduler will hand back,
 * so the current study turn cannot continue towards a rating (§18/§19/§20).
 */
sealed interface ReviewerAction {

    val kind: ReviewerActionKind

    /** Stable, content-free token used in action identity, diagnostics and capability maps. */
    val key: String

    /**
     * §21 — true when a *confirmed* (`APPLIED`) action makes the current review turn unratable:
     * the card left the scheduler's queue, so continuing the presentation would rate a card that is
     * no longer being reviewed. Only this property plus [ReviewerActionStatus.APPLIED] opens the
     * fresh next-card query (§22, INV-13-14/INV-13-15).
     */
    val invalidatesCurrentTurn: Boolean

    /**
     * Set (or clear, with [AnkiFlag.NONE]) the card's flag. Metadata only: it must never rate the
     * card, advance the scheduler or complete the review turn (§18, INV-13-13).
     *
     * [AnkiFlag.UNKNOWN] is rejected at construction: "this build could not name the backend's
     * flag" is not something a user can set.
     */
    data class SetFlag(val flag: AnkiFlag) : ReviewerAction {
        init {
            require(flag != AnkiFlag.UNKNOWN) { "An unknown flag is not a settable value" }
        }

        override val kind: ReviewerActionKind get() = ReviewerActionKind.FLAG
        override val key: String get() = "flag:${flag.name.lowercase()}"
        override val invalidatesCurrentTurn: Boolean get() = false
    }

    /** Bury the *card* for today (never the note: §3 keeps the two distinct operations). */
    data object BuryCard : ReviewerAction {
        override val kind: ReviewerActionKind get() = ReviewerActionKind.BURY
        override val key: String get() = "bury"
        override val invalidatesCurrentTurn: Boolean get() = true
    }

    /** Suspend the *card* (never the note: §3/§28). */
    data object SuspendCard : ReviewerAction {
        override val kind: ReviewerActionKind get() = ReviewerActionKind.SUSPEND
        override val key: String get() = "suspend"
        override val invalidatesCurrentTurn: Boolean get() = true
    }

    companion object {
        /** Every action this build models, in menu order. */
        val ALL: List<ReviewerAction> = listOf(SetFlag(AnkiFlag.NONE), BuryCard, SuspendCard)

        /**
         * A representative action of one kind, for capability and semantics projection (a
         * capability is per kind; only the flag payload differs within one kind).
         */
        fun representative(kind: ReviewerActionKind): ReviewerAction = when (kind) {
            ReviewerActionKind.FLAG -> SetFlag(AnkiFlag.NONE)
            ReviewerActionKind.BURY -> BuryCard
            ReviewerActionKind.SUSPEND -> SuspendCard
        }
    }
}

/** The action kinds, for capability maps and UI state that must not depend on flag payloads. */
enum class ReviewerActionKind {
    FLAG,
    BURY,
    SUSPEND
}

/**
 * The minimal, backend-reported card state a reviewer action needs (§4: the ledger stores identity
 * and evidence, never question/answer/HTML/transcript/AI feedback).
 *
 * [queueCode] is the raw scheduler queue code from the *public* card contract, mapped by [fromQueue]
 * into the small set of states a reviewer action can produce. Anything unrecognised is
 * [ReviewerCardAvailability.UNKNOWN] — never guessed into "reviewable" or "buried" (the same rule
 * as [AnkiFlag.UNKNOWN]).
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
         * Maps a raw `queue` code. The codes themselves are pinned in [AnkiDroidApiContract]
         * (`Card.RAW_QUEUE`: −3 manually buried, −2 sibling buried, −1 suspended, 0..4 the ordinary
         * queues) and re-declared here for the backend-neutral domain, exactly like
         * `AnkiFlag.fromBackendCode` re-declares the flag coding.
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
 * GATE 13 §28 — the *verified* backend semantics of one action capability.
 *
 * The shared status model does not mean all backend operations behave alike, so each capability
 * declares what its public contract was audited to guarantee:
 *
 * - [supportsIdempotentReplay] — replaying the same action cannot produce a second effect. Even
 *   when true, §29 forbids *automatic* replay of an unknown outcome; this flag only says a future
 *   gate may relax that with proof.
 * - [supportsAuthoritativeReconciliation] — a read-only query can prove applied / not applied, so
 *   `AMBIGUOUS` can be resolved (§27). Without it, reconciliation stays
 *   [ReviewerActionReconciliationResult.Unresolved] and the record remains `AMBIGUOUS`.
 *
 * The honest default is "nothing verified": an unaudited capability claims no replay safety and no
 * reconciliation, so it can only ever fail closed.
 */
data class ReviewerActionSemantics(
    val supportsIdempotentReplay: Boolean = false,
    val supportsAuthoritativeReconciliation: Boolean = false
) {
    companion object {
        /** Nothing is claimed until a backend contract has been audited (§28/§29). */
        val UNVERIFIED = ReviewerActionSemantics()
    }
}

/**
 * GATE 13 §5/§17 — the capability projection for reviewer actions.
 *
 * Derived from the *one* capability source ([AnkiCapabilities]) instead of being a second,
 * freely-writable capability store: a backend that cannot bury cannot advertise bury here either
 * (INV-13-16). A backend-neutral value type so the study layer can freeze the set at session start
 * (a later global preference change must not re-shape an in-flight action).
 *
 * [semantics] carries the per-kind audited backend semantics (§28); the default claims nothing.
 */
data class ReviewerActionCapabilities(
    val flag: Boolean = false,
    val bury: Boolean = false,
    val suspend: Boolean = false,
    val semantics: Map<ReviewerActionKind, ReviewerActionSemantics> = emptyMap()
) {
    fun supports(action: ReviewerAction): Boolean = when (action.kind) {
        ReviewerActionKind.FLAG -> flag
        ReviewerActionKind.BURY -> bury
        ReviewerActionKind.SUSPEND -> suspend
    }

    /** The audited semantics of one kind; [ReviewerActionSemantics.UNVERIFIED] when unclaimed. */
    fun semanticsOf(kind: ReviewerActionKind): ReviewerActionSemantics =
        semantics[kind] ?: ReviewerActionSemantics.UNVERIFIED

    /** The kinds the UI may present, in a stable order (never a disabled lie). */
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
 * Kotlin keyword; the reviewer-action projection keeps the plain domain name. [semantics] is
 * supplied by the backend that audited its own contract ([AnkiBackend.reviewerActionSemantics]).
 */
fun AnkiCapabilities.reviewerActions(
    semantics: Map<ReviewerActionKind, ReviewerActionSemantics> = emptyMap()
): ReviewerActionCapabilities = ReviewerActionCapabilities(
    flag = flags,
    bury = bury,
    suspend = suspendCards,
    semantics = semantics
)

/**
 * GATE 13 §17/§28 — the capability set a **session start** should freeze.
 *
 * It is derived from the backend's own live capabilities ([AnkiCapabilities]) plus the audited
 * per-action semantics that backend declares ([AnkiBackend.reviewerActionSemantics]), so a session
 * cannot invent an action the backend has not audited (INV-13-16) and a later capability or
 * preference change cannot re-shape an in-flight action (the value is a backend-neutral snapshot).
 * This is the one call a session-start path needs; nothing else may assemble a capability set.
 */
fun AnkiBackend.reviewerActionCapabilities(): ReviewerActionCapabilities =
    capabilities.value.reviewerActions(
        semantics = ReviewerActionKind.entries.associateWith { kind ->
            reviewerActionSemantics(ReviewerAction.representative(kind))
        }
    )
