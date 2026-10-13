# GATE 21H — Review Commit Routing & Recovery (Implementation Record)

Sub-gate of **GATE 21 — Multi-Backend Anki Selection / Hybrid Mode**. This document is the
implementation/contract record: what the rating-review family answers to GATE 21C's deferred
questions, which seams changed, what is deliberately *not* in scope, and the residual risks the
next sub-gates inherit.

Companion: `docs/GATE_21H_VERIFICATION_REPORT.md` (PART VII §1–15: checkpoint inventory, audit,
single VER-21H-01…36 matrix, invariant report, lock decision).

---

## 1. The rule this gate implements

```text
ReviewCommitRecord (durable)
  ├── backendId           commitId.backendId            → the exact registered instance (never AUTO,
  │                                                        never the preference, never "first ready")
  ├── binding collection  collectionRef?.collectionKey   → what the transaction was admitted against
  └── operation class     OP-M1 (first attempt) / OP-M2 (repeat) / OP-M3 (reconcile) / OP-P (recovery)
                ↓
AnkiIdentityAssurancePolicy.check(bound, live, boundIsLiveInThisProcess)      (GATE 21C §3.5)
                ↓
AnkiIdentityContinuation.continuationVerdict(operation, check, facts)        (GATE 21C §3.3, locked)
                ↓
Routed(exact backend) | Blocked(stable token)
```

Three facts about the flow matter more than the diagram:

1. **The only routing input is durable.** `record.backendId` is read from the transaction key; the
   global preference, `AnkiBackendResolver`/AUTO and "which backend is currently ready" are never
   consulted on the commit, reconcile or recovery path. A transaction is never rewritten to another
   backend — it stays blocked until its own backend can accept it.
2. **Collection evidence is compared, never invented.** The binding collection is
   `ReviewCommitRecord.collectionRef` (written at admission from the session context). A card/deck
   ref's own collection key qualifies the *resource*; it is not evidence of what the transaction was
   bound to, so it never constructs binding evidence here.
3. **Unknown is never equal.** A recorded key whose live counterpart is unknown is
   *degraded* — mismatch-grade — and fails closed (failure matrix §6, identity contract §3.5). Only
   a record that recorded **no** key is beyond comparison; that is the normal AnkiDroid case
   (no key exists anywhere, DISCOVERY-21-03) and it blocks nothing by absence alone.

## 2. Artifacts

| Artifact | Status | Notes |
| --- | --- | --- |
| `core/anki/ReviewCommitRoutingPolicy.kt` | **NEW** | Family facts, operation class, route verdicts, recovery pass |
| `core/anki/AnkiTransactionRecoveryRouting.kt` | MODIFIED | `COLLECTION_IDENTITY_UNAVAILABLE` block reason + degraded rule in `AnkiTransactionRecoveryPlanner.route` |
| `core/study/AnkiStudyEffectExecutor.kt` | MODIFIED | `commitRouting` seam; commit-path routing (OP-M1/OP-M2); OP-M3 gate in `reconcileRecord`; OP-P `recoveryPlan()`/`restoreSession()`; `sessionBoundHere` |
| `test/study/AnkiCommitHarness.kt` | MODIFIED | Keyed-backend fixture: non-AnkiDroid identities report their collection key; AnkiDroid reports none |
| `test/study/Gate21hReviewCommitRoutingTest.kt` | **NEW** | 21 routing/ownership/identity/duplicate/stale verifications |
| `test/study/Gate21hCommitEnduranceTest.kt` | **NEW** | 1 000-event churn + crash-window sweep |

Nothing else in the review-commit pipeline changed: `ReviewCommit.kt`, `ReviewCommitCoordinator.kt`,
`ReviewCommitLedger.kt`, `ReviewCommitTransition.kt`, `ReviewCommitReconciliation.kt`,
`ReviewCommitRecovery.kt`, `ReviewCommitRecoveryPolicy.kt` and `DataStoreReviewCommitStore.kt` are
untouched. No status was added, renamed or re-scoped: the five-status vocabulary
(`PREPARED / SUBMITTING / COMMITTED / RETRY_ALLOWED / AMBIGUOUS`) is exactly as GATE 11 locked it.

## 3. Decisions made by this gate

- **DECISION-21H-01 — the rating family's facts.** `ReviewCommitFamilyFacts.of(backend)` states,
  explicitly and per backend rather than by defaulting:
  `nonApplicationProofIsCollectionIndependent = true` (PREPARED provably never entered the boundary;
  a backend refusal is certified for *this* `ReviewCommitId` before the provider write — never a
  scheduler observation, so never due-date/interval/reps inference),
  `familyRecoveryRequiresCollectionProof = false` (recovery is the durable status plus the record's
  own backend), `authoritativeReconciliationAvailable` = the backend's **live**
  `reconciliationSupport()` is not `UNSUPPORTED`, and `backendCurrentlyOffersNoCollectionKey` = the
  backend reports no key *now*.
- **DECISION-21H-02 — binding evidence is `collectionRef`.** `ReviewCommitRecord.collectionKey`
  (which falls back to card/deck refs) is *not* used to construct binding evidence; using resource
  qualification as transaction evidence would make a fixture's card key authoritative over the
  session context that admitted the transaction. See §4 of the failure-matrix/identity-contract
  cross-check in the verification report.
- **DECISION-21H-03 — OP-P scope.** The recovery pass (`recoveryPlan()`, advisory and read-only)
  and the per-record gates do not replace the locked begin-time rule: an unresolved transaction
  blocks starting a session only for its own session/collection (GATE 11D §26/§30, GATE 21B §6),
  while ambiguity in *another* collection stays informational (`priorUnresolvedCommits`). 21H adds
  identity-aware routing on top of that rule; it does not widen it into a global gate.
- **DECISION-21H-04 — reconciliation is gated before the provider.** `OP-M3` is judged **before**
  the read-only provider call. A blocked cell issues zero provider calls, records the block token
  in diagnostics only (`identityBlockToken`), and leaves the durable status `AMBIGUOUS` — never
  demoted to `RETRY_ALLOWED`, never "resolved" by a collection the transaction was not written
  against.
- **DECISION-21H-05 — refused mutations are honest failures.** A blocked `OP-M1`/`OP-M2` is
  persisted through the existing `markRefused` path: `RETRY_ALLOWED` +
  `ReviewCommitResolution.REFUSED_BEFORE_DISPATCH` + the stable token as the failure category, with
  zero backend calls. Nothing is dispatched, nothing is guessed, and the user's retry is re-validated
  from scratch.

## 4. Deliberately not in scope

New ReviewCommit architecture, a second ledger/coordinator/resolver, any new rating state machine,
a local FSRS scheduler, cross-backend card mapping/transfer, AUTO routing for recovery, an offline
mutation replay queue, ReviewerAction recovery redesign (GATE 21I), note edit/create recovery
(21J/21K) and backend-selection UI. All of those remain where they were; the reviewer-action family
keeps its own ledger and its own `ReviewerActionCapabilities` contract.

## 5. Residual risks handed to later sub-gates

- **R-21H-01 — no binding evidence is not a match.** A record without `collectionRef` cannot be
  blocked by collection mismatch. Rationale: the alternative would be inventing evidence. The
  dimension is unknown, and unknown never authorises anything that needs a match (`OP-M3` stays
  blocked unless a key pair matches). No action today; revisit if a production writer ever omits the
  context collection.
- **R-21H-02 — `CommitFaultPoint.AFTER_LEDGER_CREATE` is declared but never fired** by the executor
  (the durable-create window is observed via the `DURABLE_CREATED` phase sink). Test-only seam; no
  production behaviour depends on it. Suggested cleanup in a future maintenance change (OBS-21H-01).
- **R-21H-03 — group-level recovery key.** `recoveryPlan()` judges a backend group by the first
  record's binding key. Per-record decisions remain authoritative at commit/reconcile time
  (`route()` uses that record's own key), so the worst case is a group that waits instead of
  routing. Documented rather than "fixed" because collapsing keys silently would be worse.
- **R-21H-04 — device/CI evidence.** Gradle/`./gradlew` cannot run in this environment (wrapper TLS
  failure, no Android SDK); verification is the repository's JVM harness, which is *equivalence*
  evidence, not a device run. Same carried limitation GATE 21G recorded.
- **R-21H-05 — DI instantiation path not executed.** Every new member has a default value, so no
  constructor call site changes, but no Android runtime exercised `AppContainer` in this
  environment. Verified by compilation and by the full JVM suite only.

## 6. Handoff to GATE 21I

GATE 21I (reviewer-action recovery) inherits:

- the pattern to copy: durable record → 21C §3.5 assurance → §3.3 continuation verdict → routed or
  blocked, with blocking before any provider call and block tokens in diagnostics only;
- the explicit warning that `ReviewerActionRecord` has its **own** collection/identity fields
  (the reviewer-action family must answer its own `AnkiOperationFacts` — do not reuse
  `ReviewCommitFamilyFacts` wholesale);
- `ReviewCommitRoutingPolicy` as a *shape* to follow, not a base class to extend: a second policy
  per family is the point; a shared mutable "router" is not.
