# ADR 0007 — Rating authority and exactly-once scheduling mutation

**Status:** Accepted (GATE 01, 2026-09-22) — amended by GATE 11B (2026-10-07), see
[Naming amendment](#naming-amendment-gate-11b) below
**Invariants:** INV-ANKI-02, INV-ANKI-03, INV-ANKI-05, INV-ANKI-08,
INV-ANKI-11, INV-ANKI-13

## Context

Three different "ratings" exist in the system: what the AI suggests, what the
user selects, and what Anki's scheduler applies. Conflating them has two failure
modes: an LLM silently rescheduling cards (authority violation), and a commit
applied twice or zero times when the network/process dies after the write but
before the ACK (the AMBIGUOUS window).

The existing machine already guarantees exactly-once *intent* over the wire
(`SubmissionLedger`, timeouts, `review_turn_id`). The Anki fusion layer needs
the same guarantee against the *scheduler*.

## Decision

1. `SuggestedRating` (AI) / `SelectedRating` (user) / `CommittedRating` (Anki)
   are three distinct concepts and never one field. Default policy: AI suggests
   → user confirms (tap / spoken / click) → Study-Agent commits → Anki
   schedules. Fully automatic scheduling from LLM output is disabled by
   default; any future trusted auto-rating mode is explicit opt-in.
2. Every commit carries a `ReviewCommitId` = backend id + study session id +
   review-turn id — never derived from the card id alone (a card may be
   legitimately reviewed many times).
3. One ReviewTurn ⇒ at most one scheduling mutation. PC backend: idempotency
   key on the commit. AnkiDroid backend: local commit ledger + reconciliation.
4. Commit outcomes are four-valued: COMMITTED / REJECTED /
   FAILED_SAFE_TO_RETRY (same commit id may retry) / AMBIGUOUS (stop
   progression, reconcile before any next card; never blind-resend).
5. The next card is never requested before the previous commit has a
   deterministic outcome (the rating transaction boundary).
6. Failure classification is conservative: unknown causes classify AMBIGUOUS;
   implementations may upgrade to AMBIGUOUS, never downgrade to silent retry.

## Consequences

- The user remains the final authority on their own review history.
- The worst-case crash window (write applied, ACK lost) is handled by design —
  via reconciliation — instead of by hoping duplicates are harmless.
- Reconciliation logic is mandatory implementation work in GATE 06, not an
  optional hardening task.

## Alternatives considered

- *At-least-once with "Anki dedups by card"*: rejected — same card, two turns,
  one dedup = a lost legitimate review.
- *Auto-commit the AI suggestion when confidence is high*: rejected as default —
  authority stays human; revisited only as an explicit future user setting.

## Naming amendment (GATE 11B)

Decision item 4 spelled the outcome vocabulary as it stood in GATE 01. GATE 11B locked one name per
layer, and the item is now read in those terms — the *decision* is unchanged, only the words:

| This ADR (GATE 01 wording) | Canonical GATE 11B name | Layer |
|---|---|---|
| `COMMITTED` | `ReviewCommitStatus.COMMITTED` | durable transaction truth |
| `REJECTED` | `BackendCommitResult.ConfirmedNotCommitted` → `ReviewCommitStatus.RETRY_ALLOWED` | backend evidence → ledger |
| `FAILED_SAFE_TO_RETRY` | `ReviewCommitStatus.RETRY_ALLOWED` | durable transaction truth |
| `AMBIGUOUS` | `ReviewCommitStatus.AMBIGUOUS` | durable transaction truth |

Two consequences worth restating: the backend never names a ledger status (it reports
`ConfirmedNotCommitted`; the coordinator concludes `RETRY_ALLOWED`), and the presentation layer
calls the same facts `Saved` / `RetryAvailable` / `VerificationRequired` (`RatingCommitUiState`) —
never `Committed`, `Retryable` or `Ambiguous`. There is no durable `NOT_STARTED`: no record means no
transaction. Full table: `docs/GATE_11_RATING_COMMIT.md` PART XV/XVII.
