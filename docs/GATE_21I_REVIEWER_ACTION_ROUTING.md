# GATE 21I — Reviewer Action Routing & Recovery (Implementation Record)

Sub-gate of **GATE 21 — Multi-Backend Anki Selection / Hybrid Mode**. This document is the
implementation/contract record: what the reviewer-action family answers to GATE 21C's deferred
questions, which seams carry the rule, what is deliberately *not* in scope, and what the next
sub-gates inherit.

Companion: `docs/GATE_21I_VERIFICATION_REPORT.md` (PART VII §1–17: required initial report,
checkpoint inventory, capability/scope matrix, audit matrix, the single VER-21I-01…44 matrix,
invariant report, build results, lock decision).

Prior gate: `docs/GATE_21H_REVIEW_COMMIT_ROUTING.md` §6 handed this gate its reusable ownership
principles; GATE 13 (`docs/GATE_13_REVIEWER_ACTIONS.md`) remains the **sole** reviewer-action
transaction authority and was not re-designed here.

---

## 1. The rule this gate implements

```text
ReviewerActionRecord (durable, GATE 13)
  ├── backendId           record.backendId             → the exact registered instance (never AUTO,
  │                                                       never the preference, never "first ready")
  ├── binding collection  record.collectionRef?.collectionKey → what the action was admitted against
  ├── target + scope      record.cardRef (backend-qualified), record.action (kind + parameters)
  └── operation class     OP-M1 (new action) / OP-M2 (retry) / OP-M3 (reconcile) / OP-P (recovery)
                ↓
AnkiIdentityAssurancePolicy.check(bound, live, boundIsLiveInThisProcess)      (GATE 21C §3.5)
                ↓
AnkiIdentityContinuation.continuationVerdict(operation, check, facts)        (GATE 21C §3.3, locked)
                ↓
Routed(exact backend) | Blocked(stable token)
```

Four facts matter more than the diagram:

1. **The only routing input is durable.** `record.backendId` is derived from the record's identity
   key (`ReviewerActionId.of(backendId, studySessionId, turnId, action)`); the global preference,
   `AnkiBackendResolver2`/AUTO, "whichever backend is ready" and the currently browsed Library
   backend are never consulted on the action, retry, reconcile or recovery path. An action is never
   rewritten onto another backend — it stays blocked until *its own* backend can accept it.
2. **Binding evidence is the transaction's own collection, never a resource's.**
   `bindingCollectionKey(record) = record.collectionRef?.collectionKey`. A card ref's collection key
   qualifies the *resource*; it never constructs binding evidence (DECISION-21I-02).
3. **Unknown is never equal — and never a retry or reconciliation grant.** A recorded key whose
   live counterpart vanished is *degraded* (mismatch-grade) and fails closed. A binding that recorded
   **no** key is the normal AnkiDroid case (no key exists anywhere, DISCOVERY-21-03): the collection
   dimension is disclosed as `IdentityUnverifiable(NOMINAL)`, or `SessionContinuityVerified` while
   this process still holds the binding. Neither is reported as a verified collection match, and
   both block `OP-M3` under the locked GATE 21B matrix.
4. **One policy per transaction family.** The rating family keeps `ReviewCommitRoutingPolicy`
   (GATE 21H); this family has `ReviewerActionRoutingPolicy`. No shared mutable router was
   introduced — the two families have different evidence, and merging them would be exactly the
   interchangeable status machine INV-21I-30/31 forbid.

## 2. Artifacts

| Artifact | Status | Notes |
| --- | --- | --- |
| `core/anki/ReviewerActionRoutingPolicy.kt` (281 l) | MODIFIED | `ReviewerActionFamilyFacts`, `mutationOperationClass()`, `ReviewerActionRoute`, `route()` (OP-M1/OP-M2), `reconcileRoute()` (OP-M3, shared locked matrix), `recoveryPlan()` (OP-P) |
| `core/anki/ReviewerActionRecovery.kt` | MODIFIED | `AnkiReviewerActionReconciler` consults the OP-M3 gate **before** any provider read; GATE 13's recovery table/policy untouched |
| `core/study/AnkiStudyEffectExecutor.kt` | UNCHANGED | Existing `actionRouting` seam; OP-M1/OP-M2 gates, OP-P recovery plan, and action-aware restore barrier reused unchanged |
| `core/anki/ReviewerActionRecord.kt` | MODIFIED (KDoc only) | Clarifies that an immediate mutation receipt is not historical OP-M3 evidence |
| `core/anki/ReviewerAction{,Ledger,Coordinator,Policy,Status,BackendResult}.kt` | UNCHANGED | GATE 13 remains the transaction authority: five statuses, derived ids, durable ordering, §23 mutual exclusion |
| `core/anki/AnkiTransactionRecoveryRouting.kt`, `AnkiIdentityAssurance.kt`, `AnkiIdentityContinuation.kt`, `StudySessionBackendLock.kt` | REUSED | Locked 21B/21C/21D/21G seams; no family-specific fork |
| `data/anki/ankidroid/AnkiDroidBackend.kt`, `AnkiDroidReviewerActionCommitter.kt` | MODIFIED | Bury/suspend mutation remains; historical `OP-M3` capability is false and current card-state recovery returns `Unresolved` without a provider read; no private DB access, no new action |
| `test/study/Gate21iReviewerActionRoutingTest.kt` (1 478 l, 44 tests) | **21I** | Routing, OP-M3 matrix, crash/recovery, backend isolation, and session barriers |
| `test/study/Gate21iActionEnduranceTest.kt` (285 l, 4 tests) | **21I** | 1 000-event churn + six crash windows × 100 fresh processes; keyless OP-M3 remains blocked |
| `test/anki/Gate21iArchitectureAuditTest.kt` (555 l, 10 tests) | **21I** | Machine-checked structural audit, including shared OP-M3 matrix and AnkiDroid no-read refusal |
| `test/anki/Gate21iAnkiDroidReconciliationTest.kt` (new, 1 test) | **21I** | Real AnkiDroid adapter semantics and direct `Unresolved`/zero-query behavior |
| `test/study/ReviewerActionHarness.kt`, `test/anki/fake/FakeAnkiBackend.kt`, `test/anki/fake/InMemoryReviewerActionStore.kt` | MODIFIED | Multi-backend topology, duplicate card ids, keyed/keyless collections, crash-after-boundary / crash-after-apply, scripted reconciliation, write-failure injection, per-backend counters |

No status was added, renamed or re-scoped: `PREPARED / SUBMITTING / APPLIED / RETRY_ALLOWED /
AMBIGUOUS` is exactly what GATE 13 locked, and `ReviewerActionStatus` still has exactly one
declaration in production (asserted by the audit test).

## 3. Decisions made by this gate

`DECISION-21I-03` is referenced from source; the others are recorded here as the family's answers to
questions GATE 21C deliberately left open, and each one describes behaviour that exists in the tree.

| ID | Decision | Where it lives |
| --- | --- | --- |
| **DECISION-21I-01** | The family's `AnkiOperationFacts`: `nonApplicationProofIsCollectionIndependent = true` (a proven non-application is a durable, transaction-correlated fact — `PREPARED` means the boundary was provably never entered, and a backend refusal is certified for *this* `ReviewerActionId` before the provider write); live reconciliation capability is read for *this exact action*, then OP-M3 intersects it with the record's frozen promise; `familyRecoveryRequiresCollectionProof = false` lets OP-P plan by durable owner/status but grants no OP-M3 permission; `backendCurrentlyOffersNoCollectionKey` is passed explicitly, never defaulted. | `ReviewerActionFamilyFacts.of()` |
| **DECISION-21I-02** | Binding evidence is `record.collectionRef` only. A card/deck ref's own collection key is *resource qualification* and never constructs binding evidence. Absence is honest unknown, not a wildcard or an authoritative match; the operation matrix decides the result, and OP-M3 blocks without a match. | `bindingCollectionKey()` |
| **DECISION-21I-03 (corrected to locked GATE 21B)** | `OP-M3` is permitted only with **both** an authoritative collection match and the exact action's authoritative reconciliation capability, frozen at admission and still present live. `AuthoritativeMismatch`, `IdentityUnverifiable(DEGRADED)`, `IdentityUnverifiable(NOMINAL)`, and `SessionContinuityVerified` all **block** before a provider call. A current card flag/queue state does not, by itself, prove whether a particular historical request applied. AnkiDroid has no transaction-correlated action receipt and no collection key, so its ambiguous actions remain unresolved. | `reconcileRoute()` delegates to the locked `AnkiIdentityContinuation` matrix; `AnkiReviewerActionReconciler.reconcile()` gates before provider access |
| **DECISION-21I-04** | Operation class is derived from durable state only: `PREPARED ∧ attemptCount == 0` → `OP-M1` (a freshly admitted or restored, provably un-entered action); anything else that may still reach the backend → `OP-M2`. Reconciliation is always `OP-M3`; the recovery pass is always `OP-P`. | `mutationOperationClass()` |
| **DECISION-21I-05** | Routing decisions and recovery *decisions* stay in different hands: the policy says whether an identity may be reached at all; GATE 13's `ReviewerActionRecoveryPolicy` + closed recovery table say what a record of a given status may do. `actionRecoveryPlan()` is therefore read-only — it issues no backend call, mutates no record, and retries nothing. | `actionRecoveryPlan()`, `ReviewerActionRecoveryPolicy` |
| **DECISION-21I-06** | Capability can only shrink: reconciliation and idempotent-replay eligibility are `frozen*` at admission ∩ the backend's live claim. An action admitted without verified authoritative reconciliation capability can never be reconciled later, and a backend that gains a claim mid-transaction cannot retro-authorize one. Even with the capability, the locked OP-M3 matrix requires an authoritative collection match. | `familyReconciliationAvailable()`, `AnkiReviewerActionReconciler.effectiveSupport()` |
| **DECISION-21I-07** | An unresolved *reviewer action* holds the session-restore next-card barrier exactly like an unresolved commit: `restoreSession()` reports `hasUnresolvedTransactions` when a non-`APPLIED` action record belongs to the lock's backend and session/collection. Rationale: a bury/suspend may already have left the scheduler's queue, so fetching a fresh card before the action's own barrier decides would progress past an unresolved mutation. | `AnkiStudyEffectExecutor.restoreSession()` |

## 4. Capability and semantic scope (what this gate claims, and what it does not)

| Action | AnkiDroid (pinned public provider v2.24.1) | PC | Target scope modelled |
| --- | --- | --- | --- |
| Set / change flag | **UNSUPPORTED** — no flag column at the pin, `AnkiCapabilities.flags = false` always; `reviewerActionSemantics(FLAG) = UNVERIFIED` | **NO CODE** — no PC Anki backend exists (`AUDIT-21D-14`); the `AnkiBackend` default refuses with `UnsupportedAction` | card metadata only, never turn-invalidating |
| Bury card | **MUTATION SUPPORTED** — `AnkiDroidReviewerActionCommitter` → public `SCHEDULE_PATH` provider update; **historical OP-M3 unsupported** (no transaction-correlated receipt; current queue state is not proof) | **NO CODE** (same default refusal) | **card** scope only |
| Suspend card | **MUTATION SUPPORTED** — same committer, suspend column; **historical OP-M3 unsupported** for the same reason | **NO CODE** (same default refusal) | **card** scope only |
| Bury note / siblings | **NOT MODELLED** | **NOT MODELLED** | — |
| Suspend note / siblings | **NOT MODELLED** | **NOT MODELLED** | — |
| Clear / reverse (unbury, unsuspend, flag → none on AnkiDroid) | **NOT MODELLED** — the pinned contract exposes no write path (GATE 13 §3) | **NOT MODELLED** | — |

Consequences this gate relies on, stated plainly:

* `ReviewerAction` has exactly three variants (`SetFlag`, `BuryCard`, `SuspendCard`) and **no**
  note- or sibling-scoped action exists, so CHECKPOINT 21I-25's "card action silently widened into a
  note mutation" is structurally impossible rather than merely avoided
  (`Gate21iArchitectureAuditTest` asserts the absence of note/sibling action tokens).
* Every "PC" verification in this gate runs against a keyed `FakeAnkiBackend` standing in for a PC
  instance. It proves the **routing and ownership** guarantee; it is *not* evidence that a PC Anki
  backend works, because none exists in production.
* AnkiDroid flag support is not "temporarily off": it is a contract fact. A flag request on AnkiDroid
  is refused by the frozen capability set before any effect is enqueued, before any durable record
  exists and before any backend call — on any backend.

## 5. Deliberately not in scope

No new `ReviewerActionLedger`, no new coordinator architecture, no second durable action ledger, no
review scheduler, no FSRS/due-queue logic, no rating transaction system, no cross-backend
synchronization or `CardRef` mapping, no offline action queue, no undo/redo, no bulk edit, no note
editing or creation pipeline, no backend-selection UI, no diagnostics UI (GATE 21M owns presentation).
GATE 21J/21K own note editing and creation binding; GATE 22 owns broader offline behaviour.

## 6. Residual risks handed to later sub-gates

| ID | Risk | Consequence today |
| --- | --- | --- |
| R-21I-01 | AnkiDroid has no collection key or transaction-correlated action receipt | Its `OP-M3` reconciliation is blocked: a current bury/suspend state cannot establish whether a particular historical request applied. Ambiguous actions remain `AMBIGUOUS` with zero reconciliation provider calls. |
| R-21I-02 | Flag is unsupported on AnkiDroid and unreconcilable everywhere (`supportsAuthoritativeReconciliation = false` even on the fixture that models a flag write) | A lost flag response can only ever stay `AMBIGUOUS`; the user must check Anki. Never retried, never guessed. |
| R-21I-03 | Card-state reconciliation cannot attribute state to a *specific historical request* when another Anki client edits the same card | No current-state observation is accepted as historical evidence on AnkiDroid. Recovery stays unresolved; only a future audited backend contract may advertise transaction-correlated `OP-M3` evidence, and the locked matrix additionally requires an authoritative collection match. |
| R-21I-04 | No production PC Anki backend exists | PC-owned actions are reachable only in fixtures. When a PC backend lands (GATE 21J+ / a future gate), it must publish `reviewerActionSemantics` per action before any PC action is offered; the defaults refuse safely until then. |
| R-21I-05 | `actionRecoveryPlan()` has no production caller yet (same as the rating family's `recoveryPlan()`) | The per-owner recovery verdict is available to diagnostics but not surfaced; GATE 21M owns that UI. Recovery *behaviour* does not depend on it: `begin()` already scans `actionLedger.recoveryBlocker(...)` before any scheduler traffic. |
| R-21I-06 | The action family has no per-transaction diagnostics object like the rating family's `ReconciliationDiagnostics.identityBlockToken` | The identity block token still survives, but as the record's `failureCategory` / `NotRecorded.category` (e.g. `invalid_request:collection_mismatch`) plus the ledger's aggregate `ReviewerActionLedgerDiagnostics`. Sufficient for ownership investigation; a dedicated per-action diagnostics view belongs to GATE 21M. |
| R-21I-07 | Legacy action records are schema v1 with exact-version matching | A record whose owner cannot be decoded makes the whole snapshot `Unreadable` and the ledger fails closed (`Health.Unavailable`, empty recovery plan). Nothing is guessed and nothing is erased; a future schema needs an explicit, evidence-backed migration. |

## 7. Handoff to GATE 21J — Note Editing Backend Binding

Reusable, already-proven ownership principles (all harness-verified in this gate):

```text
Backend-qualified resource identity      record.cardRef.backendId == record.backendId, enforced at
                                         construction; matching numeric ids on another backend are
                                         not resource equality (INV-21I-04)
Original-backend-only durable routing    the durable record's own backendId is the only routing input
Collection assurance                     bound vs live, four categories, unknown ≠ match, degraded =
                                         mismatch-grade; binding evidence is the transaction's own
                                         collection ref, never a resource ref
Exact Registry lookup                    registry.find(ownerBackendId) — no kind-only fallback, no
                                         "first ready", no AUTO after binding
Ambiguous recovery                       no blind retry; OP-M3 requires transaction-correlated,
                                         action-specific reconciliation evidence **and** an
                                         authoritative collection match; unresolved is a first-class
                                         answer and survives restart, session end and preference change
Stale callback isolation                 epoch + transaction id + session lock validated before any
                                         effect is applied to session state
No AUTO after mutation binding           AUTO/preference are legal only *before* a session binds
                                         (session start), never for a transaction
```

**Explicit boundary for 21J:** `NoteMutation` uses its own GATE 17 contract — its own ledger
(`NoteMutationLedger`), its own identity (`NoteMutationId`) and its own status vocabulary. It must
**not** reuse `ReviewerActionStatus` as its transaction state machine, must not share
`ReviewerActionRoutingPolicy`, and must not be recovered by this family's recovery pass. What it
*should* reuse is the family pattern: one pure routing policy per transaction family over the locked
21C/21D identity seams, its own `AnkiOperationFacts` answers (note editing has different
non-application proof and different reconciliation evidence than a card action), and the same
fail-closed treatment of unknown collection identity. `NoteEditSafetyPolicy` already reads the
reviewer-action ledger *read-only* to refuse an edit that overlaps an unresolved action; that
direction (edit defers to action) is the only cross-family coupling that exists and it must stay
one-way.
