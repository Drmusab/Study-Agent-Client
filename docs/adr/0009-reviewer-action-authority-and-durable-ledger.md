# ADR 0009 — Reviewer-action authority and its own durable ledger

**Status:** Accepted (GATE 13, 2026-10-08)
**Related:** ADR 0003 (Anki owns scheduling), ADR 0004 (Study-Agent owns the review interaction),
ADR 0008 (durable review attempts and exactly-once limits).

## Context

A reviewer can do three things to a card during a review besides rating it: set a flag, bury the
card for today, or suspend it. All three are backend mutations with exactly the failure window ADR
0008 describes for ratings — the backend applies the change, the response is lost, the process dies —
but they are **not** ratings. They have no review history, no scheduler interval and no payload that
could be re-derived later, so folding them into `ReviewCommitLedger` would either fabricate review
history or dilute that ledger's meaning.

GATE 11 answered the same question for ratings with a durable, backend-neutral ledger whose status
carries the mutation boundary. GATE 13 applies that answer to the second mutation family, without
merging the two.

## Decision

1. **Separate transaction truth.** `ReviewerActionStatus` (`PREPARED`, `SUBMITTING`, `APPLIED`,
   `RETRY_ALLOWED`, `AMBIGUOUS`) is the only durable reviewer-action state, stored in
   `ReviewerActionLedger`, in its own file and its own codec, with no shared record with
   `ReviewCommitLedger`. A reviewer action never writes a `ReviewCommitStatus`; a rating never
   writes a `ReviewerActionStatus`.
2. **Identity is derived, not random.** `ReviewerActionId = (backendId, studySessionId, turnId,
   actionKey)`. A duplicate intent therefore collapses to one logical action, and a retry reuses the
   same identity.
3. **The durable boundary precedes the mutation.** `PREPARED` (intent, boundary un-entered) is
   written first; the transaction executor's boundary callback then makes `SUBMITTING` durable
   *inside* `AnkiBackend.performReviewerAction(...)`, immediately before the real provider write.
   Without a ledger — or with a write that cannot be made durable — no mutation is dispatched.
4. **The backend answer is evidence, never a Boolean.** `ReviewerActionBackendResult` has exactly
   three states (`ConfirmedApplied`, `ConfirmedNotApplied`, `OutcomeUnknown`), and the mapping to
   durable status is fixed: applied → `APPLIED`, proven not applied → `RETRY_ALLOWED`, unknown →
   `AMBIGUOUS`. A lost response is never reinterpreted as a safe failure, and `AMBIGUOUS` never
   retries directly.
5. **One action at a time.** At most one active action per review turn; before preparing an action
   the coordinator asserts the turn has no rating commit, and before preparing a rating the commit
   pipeline asserts the turn has no unresolved action. Both checks live below the UI.
6. **Turn invalidation is a domain property.** `invalidatesCurrentTurn` is `false` for a flag and
   `true` for bury/suspend; the one next-card rule is
   `nextCardAllowed(action, status) = action.invalidatesCurrentTurn && status == APPLIED`. A flag
   therefore keeps the turn (and keeps rating available); bury/suspend close it and ask the scheduler
   for a fresh card exactly once.
7. **Recovery is read-only and closed.** The recovery table is a pure function of the durable status;
   `SUBMITTING` found after a restart is reconciled (or normalized to `AMBIGUOUS`), `PREPARED` and
   `RETRY_ALLOWED` may be retried with the same identity, and `APPLIED` is never replayed.
8. **Capabilities come from the backend's own contract.** An action a backend has not audited is
   never offered; each capability declares whether its contract supports idempotent replay and
   authoritative reconciliation, and an unaudited claim binds nothing (`UNVERIFIED`).

## Consequences

* Reviewer actions survive process death with a decidable outcome: the user is either told "not
  applied, try again" or "may have been applied — verify in AnkiDroid", never silently replayed.
* The two ledgers are independent: a corrupt or unreadable action ledger blocks actions (fail closed)
  without touching rating truth, and vice versa.
* Flag support is optional by backend: at the pinned AnkiDroid public contract (v2.24.1) there is no
  flag write at all, so `Flag` is refused truthfully (`UnsupportedAction`) rather than faked.
* Cost: one more small durable file, one more status vocabulary, and one more place a mutation path
  must be recorded — paid deliberately, because the alternative is guessing whether a card was
  buried.

## Explicit non-claims

No claim that bury/suspend are idempotent *in the backend* — the verified idempotency is the
committer's desired-state check, not a backend dedup key. No claim that a positive card-state read
proves *when* the mutation happened, or that "not currently buried" proves "was never buried"
(bury is day-scoped; both operations can be undone in AnkiDroid). No automatic retry of an unknown
action, ever. Real-device verification remains outstanding (GATE 13 §7, VERIFICATION 14).
