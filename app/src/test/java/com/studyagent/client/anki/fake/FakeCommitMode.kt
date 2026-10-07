package com.studyagent.client.anki.fake

import com.studyagent.client.core.anki.CommitGuaranteeLevel

/**
 * GATE 11 checkpoint 4 — deterministic failure modes for the commit laboratory.
 *
 * Every mode is a *scheduler* behaviour, not a client behaviour: the mode says what happens to the
 * collection, and the test asserts what the transaction layer does about it. Modes are only applied
 * when no scripted [FakeAnkiBackend.CommitStep] remains, so existing scripted tests are unaffected.
 *
 * | Mode | Effect | Response | Client must classify as |
 * |---|---|---|---|
 * | [SUCCESS] | 1 | committed | COMMITTED |
 * | [FAIL_BEFORE_MUTATION] | 0 | retryable refusal | RETRY_ALLOWED |
 * | [MUTATE_THEN_DROP_RESPONSE] | 1 | lost (throws after the effect) | AMBIGUOUS |
 * | [OUTCOME_UNKNOWN] | 0 | unknown, nothing applied | AMBIGUOUS |
 * | [DELAYED_SUCCESS] | 1 | committed, withheld until released | COMMITTED (after release) |
 * | [IDEMPOTENT_REPLAY] | 1 | replay returns the recorded result | COMMITTED, one effect |
 * | [NON_IDEMPOTENT_REPLAY] | 1 per delivery | replay applies again | client ledger must stop it |
 * | [BACKEND_UNAVAILABLE] | 0 | refused before dispatch | RETRY_ALLOWED (zero submissions) |
 *
 * [OUTCOME_UNKNOWN] is the pessimistic case that matters: a backend that *cannot* know whether it
 * applied must not be turned into a safe retry. The client has to fail closed.
 */
enum class FakeCommitMode(
    /** Guarantee the stand-in advertises; replay modes need matching semantics to be meaningful. */
    val guarantee: CommitGuaranteeLevel = CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED,
    /** True for the modes that write to the simulated collection before answering. */
    val appliesEffect: Boolean = true
) {
    SUCCESS,
    FAIL_BEFORE_MUTATION(appliesEffect = false),
    MUTATE_THEN_DROP_RESPONSE,
    OUTCOME_UNKNOWN(appliesEffect = false),
    DELAYED_SUCCESS,
    IDEMPOTENT_REPLAY(guarantee = CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED),
    NON_IDEMPOTENT_REPLAY(guarantee = CommitGuaranteeLevel.LOCAL_DEDUP_ONLY),
    BACKEND_UNAVAILABLE(guarantee = CommitGuaranteeLevel.LOCAL_DEDUP_ONLY, appliesEffect = false) {
        override val refusesBeforeDispatch: Boolean get() = true
    },
    ;

    /** Modes that the fake refuses in `prepareCommit`, before any mutation boundary is reached. */
    open val refusesBeforeDispatch: Boolean get() = false
}
