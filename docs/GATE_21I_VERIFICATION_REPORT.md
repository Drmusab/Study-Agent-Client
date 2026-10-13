# GATE 21I — Verification Report (PART VII §1–16)

**Gate:** 21I — Reviewer Action Routing & Recovery (parent: GATE 21 — Multi-Backend Anki Selection /
Hybrid Mode; sub-gate 21I of 21A–21N).
**Type:** Implementation → Independent Architecture Audit → Authoritative Verification → Gate Lock.
**Companion:** `docs/GATE_21I_REVIEWER_ACTION_ROUTING.md` (contract, decisions, capability truth,
residual risks, 21J handoff).

**Gate-lock decision: LOCKED** at the harness-verified scope — every mandatory audit and every
applicable verification below passed on evidence actually executed in this environment. Two items are
reported **BLOCKED** and neither is claimable here: real-backend validation (VER-21I-43 — no
device/emulator, no production PC Anki backend) and every Gradle task (PART V — the wrapper cannot
download its distribution; no Android SDK). Both are the same limitations GATE 21G/21H carried.

---

## §0.A Required initial report (as found, before any change in this session)

```text
REPOSITORY HEAD:            db69d4d8bd22f3527c7588ce3b4ba714c39d8ef3 (merge of PR #73; shallow clone)
BRANCH:                     arena/67ab09ab-study-agent-client (from master @ db69d4d)
WORKTREE STATUS:            clean at the start of this gate

GATE 13:                    IMPLEMENTED (and WIRED) — docs/GATE_13_REVIEWER_ACTIONS.md §0;
                            ReviewerAction{,Record,Ledger,Coordinator,Policy,Status,BackendResult,
                            Recovery}.kt, AnkiDroidReviewerActionGateway/Committer,
                            DataStoreReviewerActionStore, Compose ReviewerActionMenu mounted,
                            AppContainer wiring (registry + actionLedger + sessionLockStore).
                            Real-device mutation verification (its VERIFICATION 14) was NOT RUN
                            there and is still not runnable here — see VER-21I-43.

GATE 21A: LOCKED   (docs/GATE_21A_DISCOVERY.md — PASS, audits 10/10, verifications 10/10)
GATE 21B: LOCKED   (docs/GATE_21B_CONTRACT_LOCK.md:13 "RESULT: LOCKED")
GATE 21C: LOCKED   (docs/GATE_21C_IDENTITY_IMPLEMENTATION.md — harness-verified, Gradle blocked)
GATE 21D: LOCKED   (docs/GATE_21D_REGISTRY_IMPLEMENTATION.md — harness-verified)
GATE 21E: LOCKED   (docs/GATE_21E_RESOLVER_IMPLEMENTATION.md — PASS (harness))
GATE 21F: LOCKED   (docs/GATE_21F_READ_CONTEXT_ISOLATION.md:16 "PASS (harness-verified) / LOCKED")
GATE 21G: LOCKED   (docs/GATE_21G_VERIFICATION_REPORT.md — PASS, recommended LOCKED)
GATE 21H: LOCKED   (docs/GATE_21H_VERIFICATION_REPORT.md:8 "Gate-lock decision: LOCKED")

REVIEWER ACTION COORDINATOR:  EXISTS — interface ReviewerActionCoordinator +
                              DefaultReviewerActionCoordinator (core/anki/ReviewerActionCoordinator.kt:126)
REVIEWER ACTION LEDGER:       EXISTS — interface ReviewerActionLedger (:258) +
                              DurableReviewerActionLedger (:380) + ReviewerActionLedgerCodec (v1) +
                              DataStoreReviewerActionStore (separate DataStore file, no corruption reset)
BACKEND IDENTITY IN DURABLE RECORD: YES — ReviewerActionRecord.backendId (:110), derived
                              ReviewerActionId.of(backendId, sessionId, turnId, action) (:33),
                              collectionRef (:111), cardRef (:112)
ACTION SCOPE CONTRACT:        COMPLETE for the actions that exist (card-scoped flag/bury/suspend).
                              No note- or sibling-scoped action is modelled, and none was invented
                              here; AnkiDroid flag is contractually UNSUPPORTED at the pin.
AMBIGUOUS RECOVERY:           SUPPORTED — five-status GATE 13 vocabulary, closed recovery transition
                              table (ReviewerActionRecovery.kt:86), read-only reconciler with an
                              OP-M3 identity gate (:308), per-owner recovery plan, restore barrier.
```

**Gate-documentation state found:** the 21I *implementation* and its two behavioural test classes
were present and committed at HEAD, but **no 21I gate document existed** — `docs/GATE_21H_*` still
recorded "GATE 21I is not started", and there was no audit class, no report and no lock decision.
This session therefore (a) inspected and executed the existing implementation instead of assuming it,
(b) closed the verification gaps it found, (c) added the machine-checked PART II audit, and (d) wrote
the two authoritative documents. Nothing in `app/src/main` needed changing: no defect was found that
the executed evidence did not already cover, and the three production-side mutants used to test the
audit (below) were reverted.

## §0.B Method and environment (what was actually executed)

| Step | Command | Result |
| --- | --- | --- |
| Bootstrap | `tools/jvm-harness/bin/bootstrap-sandbox.sh` (PyPI `jdk4py` + `kotlin-jupyter-kernel`, GitHub `Sable/android-platforms`, `Kotlin/kotlinx.coroutines` 1.10.2) | OK |
| Compile main | `tools/jvm-harness/bin/build.sh main` | `main errors: 0 (files: 327)` |
| Compile Compose layer | `build.sh compose` (during bootstrap) | `compose errors: 0 (files: 24, stub files: 46)` |
| Compile tests | `build.sh test` | `test errors: 0 (files: 247)` |
| Baseline full suite (before this session's test additions) | `run.sh` | `classes=214 tests=2571 passed=2571 failed=0 ignored=0 time=57.6s` |
| **Final full suite** | `run.sh` | **`classes=215 tests=2584 passed=2584 failed=0 ignored=0 time=64.0s`** |
| New gate classes | `run.sh 'Gate21i'` | `classes=3 tests=57 passed=57 failed=0` |
| Reviewer-action family + GATE 13 | `run.sh 'ReviewerAction\|Gate13'` | `classes=8 tests=147 passed=147 failed=0` |
| Transaction/routing regression sweep | `run.sh 'ReviewCommit\|RatingCommit\|Gate11\|Gate21\|Anki'` | `classes=116 tests=1478 passed=1478 failed=0` |
| Study-session machine sweep | `run.sh 'Study\|Session'` | `classes=22 tests=250 passed=250 failed=0` |
| Audit mutants | 6 mutants (3 audit-only rebuilds, 2 full main rebuilds) | 6/6 killed — see §11 |

**Environment deviations (equivalence evidence, identical to GATE 21G/21H):** Kotlin 2.3 K2 compiler
(repository pins 1.9.24), coroutines 1.10.2, serialization 1.9.0, JUnit/DataStore/OkHttp shims,
mockable `android-34.jar`, four test classes excluded from the harness
(`FakeAgentConnectionTest`, `WebSocketIntegrationTest`, `RatingControlsPolicyTest`,
`StudyPhaseMappingTest`). `build.sh test` compiles tests against the existing `main-out`, so every
main-source mutant in §11 was verified with an explicit `build.sh main` first. No command in this
report is marked PASS unless it ran here. No unit test touches a real Anki collection.

## §0.C CHECKPOINT 21I-00…32 — inventory and status

`REUSE` = the locked prior-gate artifact was used unchanged; `NO_CHANGE` = inspected and needed no
change; `MODIFIED`/`CREATED` = changed by this session (test/verification side only).

| CP | Subject | Real paths | Status | Evidence |
| --- | --- | --- | --- | --- |
| 21I-00 | Architecture inventory + required trace | `core/anki/ReviewerAction*.kt`, `core/study/AnkiStudyEffectExecutor.kt`, `data/anki/ankidroid/AnkiDroidReviewerAction*.kt`, `data/anki/DataStoreReviewerActionStore.kt` | REUSE | §0.A, §2, §3 trace |
| 21I-01 | Per-backend capability evidence | `AnkiBackend.kt` defaults (:311/:330/:348/:360), `AnkiDroidBackend.kt` (:264 `flags = false`, :1155 semantics), `docs/BACKEND_CAPABILITY_MATRIX.md`, `docs/GATE_21A_DISCOVERY.md` | REUSE | §4 matrix + audit test |
| 21I-02 | Action identity & scope | `ReviewerAction.kt` (3 variants, `kind`/`key`/`invalidatesCurrentTurn`), `ReviewerActionRecord.kt` init requires | NO_CHANGE | audit: "capability and scope claims…" |
| 21I-03 | Durable record ownership | `ReviewerActionRecord.kt:106` (all `val`, backend-qualified card/collection, frozen semantics) | NO_CHANGE | audit: "the durable action record carries one immutable backend…" |
| 21I-04 | Session/ReviewTurn admission | `AnkiStudyEffectExecutor.reviewerAction()` :176 (`sessionLockConsistent` → `actionRouting.route(OP-M1)` → coordinator), `ReviewerActionPolicy.kt`, `ReviewerActionCoordinator.validate/commitBlocker` | NO_CHANGE | VER-21I-09/31/32/33 |
| 21I-05 | Exact-backend routing | `ReviewerActionRoutingPolicy.route()` :169/:187, `registry.find(ownerBackendId)` | NO_CHANGE | VER-21I-03…06, 10–13 |
| 21I-06 | Collection identity assurance | `AnkiIdentityAssurancePolicy.check` (21C §3.5), `AnkiIdentityContinuation.continuationVerdict` (21B §3.3), `bindingCollectionKey()` | REUSE | VER-21I-14/15/28 |
| 21I-07 | Durable preparation barrier | `ReviewerActionCoordinator.perform()` :143 → `ledger.create(...)` before `submit()` :275 | NO_CHANGE | VER-21I-16/17 |
| 21I-08 | SUBMITTING crash boundary | `submit()`'s `mutationEntry` writes `EnterMutationBoundary` before the provider write; `FakeAnkiBackend` crash windows | NO_CHANGE | VER-21I-18, endurance W1–W6 |
| 21I-09 | Result classification | `ReviewerActionBackendResult.kt` → `toTransition()`; `persistResult()` (boundary-write failure ≠ retry grant) | NO_CHANGE | VER-21I-19/20/21 |
| 21I-10 | APPLIED persistence barrier | `nextCardAllowed(action, status)` (`ReviewerActionStatus.kt:569`), `StudyReducer` :1106 | NO_CHANGE | VER-21I-21/34/35/36 |
| 21I-11 | AMBIGUOUS handling | closed recovery table (`ReviewerActionRecovery.kt:86`), reducer `VerificationRequired` projection | NO_CHANGE | VER-21I-19/22/23 |
| 21I-12 | Action-specific retry eligibility | `frozenIdempotentReplay`/`frozenAuthoritativeReconciliation` ∩ live semantics (GATE 13 §28) | NO_CHANGE | VER-21I-23/25 |
| 21I-13 | Reconciliation | `AnkiReviewerActionReconciler` :290 (read-only, capability-gated, timeout → `Unresolved`) | NO_CHANGE | VER-21I-24/25/41 |
| 21I-14 | Original-backend-only reconciliation | OP-M3 gate `reconcileRoute()` :224 runs **before** `backend.reconcileReviewerAction(` | MODIFIED (gate added by 21I) | VER-21I-24/26/28; audit + mutant M5 |
| 21I-15 | Backend loss / reconnect | `AnkiTransactionRecoveryPlanner` (21D) + per-record `registry.find(record.backendId)` | REUSE | VER-21I-26/27/28 |
| 21I-16 | Process-death recovery | `actionRecoveryPlan()` :547, `restoreSession()` :563 (action-aware barrier), `begin()`'s `actionLedger.recoveryBlocker(...)` before any scheduler traffic | MODIFIED (21I) | VER-21I-22/29/38 + restore-barrier test |
| 21I-17 | Multi-backend recovery isolation | `recoveryPlan()` groups `groupByOwnerBackend(records) { it.backendId }`, per-group `Blocked` | MODIFIED (21I) | VER-21I-29 |
| 21I-18 | Backend removed / reconfigured | `BACKEND_NOT_REGISTERED` route; record preserved, owner never re-guessed | NO_CHANGE | VER-21I-26/39 |
| 21I-19 | Collection/profile mismatch | `COLLECTION_MISMATCH` / `IDENTITY_UNVERIFIABLE_DEGRADED` tokens block mutation, retry and reconciliation | REUSE | VER-21I-14/15/28 |
| 21I-20 | Legacy transaction compatibility | `ReviewerActionLedgerCodec` (schema v1 exact, `unreadable_action_record`, derived-id equality check), no `?: AnkiBackendId.` default anywhere in main | NO_CHANGE | VER-21I-39/40; audit |
| 21I-21 | Duplicate action admission | derived `ReviewerActionId`, `attemptsInFlight`, ledger `sameAction` / one-active-per-turn / one-unresolved-per-session | NO_CHANGE | VER-21I-30/31 |
| 21I-22 | Rating ↔ action mutual exclusion | `ReviewerActionPolicy.commitBlockReason`/`ratingBlockReason`, `DefaultReviewerActionCoordinator.commitBlocker` (reads the *rating* ledger read-only) | NO_CHANGE | VER-21I-32/33 |
| 21I-23 | Post-action session effects | `nextCardAllowed` + reducer Applied branch (`AnkiStudyEffect.Next` only after the barrier) | NO_CHANGE | VER-21I-34/35/36 |
| 21I-24 | Stale callback protection | epoch + `actionId` checks in `StudyReducer` (`stale-anki-action-result`/`-retry`), `sessionLockConsistent` in the executor | NO_CHANGE | VER-21I-37; mutant M4 |
| 21I-25 | Card/note scope integrity | only card-scoped actions exist; no note/sibling token in `ReviewerAction.kt` | NO_CHANGE | VER-21I-08; audit |
| 21I-26 | Read-context independence | no `AnkiReadContext*` token in the executor, policy, coordinator or recovery | NO_CHANGE | VER-21I-13; audit |
| 21I-27 | Result/error projection | `AnkiReviewerActionOutcome` (Applied/RetryAvailable/Ambiguous/PersistenceFailure/NotRecorded) → `ReviewerActionUiState` + `ReviewerActionRefusal`; block tokens surface verbatim as categories | NO_CHANGE | VER-21I-07/14/15; `ReviewerActionUiProjectionTest`, `ReviewerActionMenuUiTest` |
| 21I-28 | Safe diagnostics metadata | `ReviewerActionLedgerDiagnostics` (content-free aggregates), `failureCategory` tokens, `BackendTransactionRoute.Blocked(reason)`; no card text, no credentials | NO_CHANGE | audit (privacy rule); R-21I-06 |
| 21I-29 | Deterministic fake fixtures | `FakeAnkiBackend` (two-backend topology, duplicate card ids, keyed/keyless collections, availability flips, `crashAfterBoundary`/`crashAfterApply`, scripted reconciliation, per-backend counters), `InMemoryReviewerActionStore` (`failNextWrites`, `onDurableWrite`), `ReviewerActionHarness` (`extraBackends`, `sessionLocks`, `restart()`) | NO_CHANGE (21I additions already present) | every VER below |
| 21I-30 | Crash-boundary fault injection | six windows × 100 fresh processes + 100 controls | NO_CHANGE | §8 |
| 21I-31 | DI & integration | `AppContainer.studySessionRepository` wires `registry` + `reviewCommitLedger` + `reviewerActionLedger` + `sessionLocks` + `sessionLockStore` + `transactionRecovery`; `actionRouting` defaults to the production policy over the same registry — no new DI surface, no second registry/resolver/coordinator | NO_CHANGE | audit; DI instantiation itself is not exercised by the harness (same limitation as 21G/21H) |
| 21I-32 | Regression scope | GATE 10/11/12/13/15/16/17/18/20/21C–21H suites re-executed | NO_CHANGE | §12, §13 |
| — | PART II machine-checked audit | `test/anki/Gate21iArchitectureAuditTest.kt` (536 l, 10 tests) | **CREATED** | §11 |
| — | PART III coverage gaps | `test/study/Gate21iReviewerActionRoutingTest.kt` (+188/−3: VER-21I-04, VER-21I-06, VER-21I-15 unknown cell; two VER labels corrected to the prompt's numbering) | **MODIFIED** | §12 |
| — | Gate documentation | `docs/GATE_21I_REVIEWER_ACTION_ROUTING.md`, this report | **CREATED** | — |

**Required trace (CHECKPOINT 21I-00), with real symbols:**

```text
User requests action                     ReviewerActionMenu → StudyViewModel intent
    ↓                                    StudyEvent.UserRateCard / AnkiStudyEvent.ReviewerActionRequested
Validate ReviewTurn + capability         ReviewerActionPolicy.decide (frozen ReviewerActionCapabilities,
    ↓                                    active turn, turn-resolved, §23 commit/action blockers)
Admission / mutual exclusion             DefaultReviewerActionCoordinator.perform → validate() +
    ↓                                    commitBlocker() + attemptsInFlight
Persist PREPARED                         DurableReviewerActionLedger.create(record)  [durable write #1]
    ↓
(OP-M1 identity gate)                    AnkiStudyEffectExecutor.reviewerAction →
    ↓                                    actionRouting.route(effect.cardRef.backendId, OP-M1)
Persist SUBMITTING                       ledger.transition(PREPARED, EnterMutationBoundary) inside the
    ↓                                    backend's mutationEntry callback      [durable write #2]
Call original backend                    registry.find(record.backendId) →
    ↓                                    AnkiDroidBackend.performReviewerAction(cardRef, action) { … } →
    ↓                                    AnkiDroidReviewerActionCommitter.perform → public provider update
Classify outcome                         ReviewerActionBackendResult.toTransition() (Confirmed{Applied,
    ↓                                    NotApplied} / OutcomeUnknown) → persistResult()
Persist APPLIED / RETRY_ALLOWED /        ledger.transition(SUBMITTING, …)                        [#3]
AMBIGUOUS
    ↓
Permitted session effect                 AnkiStudyEvent.ReviewerActionResolved → StudyReducer
                                         → nextCardAllowed(action, APPLIED) ? AnkiStudyEffect.Next
                                           (fresh scheduler query on the locked backend) : keep the turn
```

**Required matrix (concern → owner):**

| Concern | Actual source | Backend source | Durable owner | Risk |
| --- | --- | --- | --- | --- |
| Action admission | `ReviewerActionPolicy.decide`, `AnkiStudyEffectExecutor.reviewerAction()` (21G lock + 21I OP-M1 gate) | frozen `ReviewerActionCapabilities` from the locked session | — (refusals are never durable) | stale effect from another session → refused (`SESSION_BACKEND_MISMATCH`) |
| Action preparation | `DefaultReviewerActionCoordinator.perform()` | none yet | `DurableReviewerActionLedger.create` | write failure ⇒ no backend call (VER-21I-16) |
| Flag | refused on AnkiDroid by capability; `AnkiBackend` default refuses with `UnsupportedAction` | no AnkiDroid write path at the pin; no PC backend | record exists only where a backend supports it | R-21I-02/04 |
| Bury | `AnkiDroidReviewerActionCommitter.perform` (card scope) | public provider `SCHEDULE_PATH` update, bury column | action ledger | day-scoped in Anki; other clients may unbury |
| Suspend | same committer, suspend column | public provider update | action ledger | other clients may unsuspend |
| Result persistence | `persistResult()` / `persistBoundaryViolation()` | — | action ledger (`SUBMITTING → APPLIED / RETRY_ALLOWED / AMBIGUOUS`) | APPLIED write failure ⇒ never resubmitted (VER-21I-20) |
| Recovery | `ReviewerActionRecoveryPolicy`, closed transition table, `AnkiReviewerActionReconciler` (+ OP-M3 gate), `actionRecoveryPlan()`, `begin()`'s `recoveryBlocker` scan | read-only card-state probe on the owner backend only | action ledger | R-21I-01/03/05 |
| Session progression | `nextCardAllowed(action, status)` + `StudyReducer` Applied branch | fresh `nextCard` on the locked session backend | — | no local card fabrication (audit-asserted) |

---

## §1 Gate result

```text
GATE 21I — REVIEWER ACTION ROUTING & RECOVERY

STATUS:                     PASS (harness-verified) — Gradle and real-backend items BLOCKED
                            environmentally, as in GATE 21G/21H

REPOSITORY HEAD:            db69d4d8bd22f3527c7588ce3b4ba714c39d8ef3 (start of gate)

PREREQUISITES:
GATE 13:  IMPLEMENTED AND WIRED (real-device mutation verification still outstanding — its own
          VERIFICATION 14; carried here as VER-21I-43 BLOCKED)
21A: LOCKED     21B: LOCKED     21C: LOCKED     21D: LOCKED
21E: LOCKED     21F: LOCKED     21G: LOCKED     21H: LOCKED

IMPLEMENTATION AUTHORIZED:  YES (no essential ownership contract was missing)
```

## §2 Reviewer action architecture (actual operation path, real symbols)

```text
StudySession / ReviewTurn        AnkiSessionContext (21G lock: backendId, collection, deckRef,
    ↓                            studySessionId) + AnkiReviewTurn(cardRef, turnId)
ReviewerActionCoordinator        DefaultReviewerActionCoordinator.perform/retry/recover
    ↓                            (core/anki/ReviewerActionCoordinator.kt:126/143/197/236)
ReviewerActionLedger             DurableReviewerActionLedger (core/anki/ReviewerActionLedger.kt:380)
    ↓                            over DataStoreReviewerActionStore (own file, no corruption reset)
21I identity gate                ReviewerActionRoutingPolicy.route / reconcileRoute / recoveryPlan
    ↓                            (core/anki/ReviewerActionRoutingPolicy.kt:149/169/224/306)
    ↓                            → AnkiIdentityAssurancePolicy.check (21C §3.5)
    ↓                            → AnkiIdentityContinuation.continuationVerdict (21B §3.3)
    ↓                            → ReviewerActionFamilyFacts.of (DECISION-21I-01)
Exact original BackendId         registry.find(record.backendId) — AnkiBackendRegistry (21D), exact
    ↓                            configured instance; never AUTO, never the preference
Backend Registry                 AnkiBackendRegistry.find / AnkiTransactionRecoveryPlanner (OP-P)
    ↓
Supported Anki backend action    AnkiDroidBackend.performReviewerAction(cardRef, action, mutationEntry)
                                 (:1101/:1112) → AnkiDroidReviewerActionCommitter.perform (:61) →
                                 AnkiDroidReviewerActionGateway.submitAction → public provider update
    ↓
Durable result                   ReviewerActionBackendResult → toTransition() → ledger.transition
    ↓
Permitted session effect         AnkiStudyEvent.ReviewerActionResolved → StudyReducer (:899/:1051) →
                                 nextCardAllowed(action, APPLIED) (:569) → AnkiStudyEffect.Next on the
                                 locked backend, or the turn is kept (flag)
```

Reconciliation (read-only, `OP-M3`): `AnkiReviewerActionReconciler.reconcile`
(`core/anki/ReviewerActionRecovery.kt:308`) → identity gate → capability gate (frozen ∩ live) →
`backend.reconcileReviewerAction(...)` on the owner backend → `AnkiDroidReviewerActionCommitter.reconcile`
(:141) card-state read. Recovery decisions stay with `ReviewerActionRecoveryPolicy` (:192) and the
closed table `reviewerActionRecoveryTransition` (:86).

## §3 Capability and semantic scope matrix

| Action | AnkiDroid | PC | Target scope | Evidence |
| --- | --- | --- | --- | --- |
| Flag (set/change) | **UNSUPPORTED** — `flags = false` always (`AnkiDroidBackend.kt:264`), `reviewerActionSemantics(FLAG) = UNVERIFIED` (:1155); no flag column in the pinned v2.24.1 provider contract | **NO CODE** — no PC Anki backend exists (`AUDIT-21D-14`); `AnkiBackend` default returns `ConfirmedNotApplied(UnsupportedAction)` | card metadata; `invalidatesCurrentTurn = false` | VER-21I-01/07 (typed refusal, zero calls, nothing durable); VER-21I-02/34 on the keyed fixture that *models* a flag write; `docs/BACKEND_CAPABILITY_MATRIX.md` |
| Bury card | **SUPPORTED** — `AnkiDroidReviewerActionCommitter.perform` → public provider `SCHEDULE_PATH` update (bury column); semantics verified both directions (`supportsIdempotentReplay`, `supportsAuthoritativeReconciliation`) | **NO CODE** (default refusal); fixture-verified routing only | **card** scope; turn-invalidating | VER-21I-03 (AnkiDroid), VER-21I-04 (PC fixture), VER-21I-08/35, endurance W1–W6 |
| Suspend card | **SUPPORTED** — same committer (suspend column), same verified semantics | **NO CODE** (default refusal); fixture-verified routing only | **card** scope; turn-invalidating | VER-21I-05 (AnkiDroid), VER-21I-06 (PC fixture), VER-21I-36 |
| Bury note / siblings | **NOT MODELLED** — no such action exists | **NOT MODELLED** | — | `ReviewerAction.kt` has exactly 3 variants; audit asserts no note/sibling token |
| Suspend note / siblings | **NOT MODELLED** | **NOT MODELLED** | — | same |
| Clear / reverse (unbury, unsuspend, clear flag on AnkiDroid) | **NOT MODELLED** — the pinned public contract exposes no write path (GATE 13 §3: "add further actions only after their public backend contracts are audited") | **NOT MODELLED** | — | `ReviewerAction.kt` KDoc + absence of any such variant; no substitute operation exists to fall back to |

Actual support vs unavailable behaviour is therefore explicit: **two** actions are implemented
against a real backend contract (AnkiDroid bury, AnkiDroid suspend); flag exists in the domain model
and is exercised only against a fixture that opts into modelling a flag write; note/sibling scope and
reverse operations do not exist at all. Nothing was widened, substituted or invented to make a cell
look supported.

## §4 Transaction identity contract (implemented)

| Element | Where it lives | Immutability |
| --- | --- | --- |
| `TransactionId` | `ReviewerActionId` = `identityKey(backendId.stableId, studySessionId, turnId.value, action.key)` — derived, never random | derived; a rewritten id fails the codec's equality check (`if (id.value != actionId) return null`) |
| `StudySessionId` | `ReviewerActionRecord.sessionId` | `val`; no `copy(sessionId =` anywhere in the family |
| `ReviewTurnId` | `ReviewerActionRecord.turnId` | `val` |
| `BackendId` | `ReviewerActionRecord.backendId`, also the first component of the id | `val`; the routing policy reads only this |
| Collection context | `ReviewerActionRecord.collectionRef: AnkiCollectionIdentity?` (`backendId` + nullable `collectionKey`); `require(collectionRef == null \|\| collectionRef.backendId == backendId)` | `val`; nullable by design (AnkiDroid has no key) |
| Target resource | `ReviewerActionRecord.cardRef: AnkiCardRef` (backend-qualified, optional resource collection key); `require(cardRef.backendId == backendId)` | `val` |
| Action kind | `ReviewerActionRecord.action.kind` (`FLAG`/`BURY`/`SUSPEND`) | `val` |
| Action scope | the action variant itself (`BuryCard`/`SuspendCard` are card-scoped; no note/sibling variant exists) + `invalidatesCurrentTurn` | `val`; structurally un-widenable |
| Action parameters | `ReviewerAction.SetFlag(flag)` payload (`AnkiFlag.UNKNOWN` rejected at construction) | `val` |
| Frozen semantics | `frozenIdempotentReplay`, `frozenAuthoritativeReconciliation` (GATE 13 §28) | `val`; can only shrink against the live claim |
| Recovery evidence | `status`, `attemptCount`, `submittedAtEpochMs`, `resolvedAtEpochMs`, `backendReceipt`, `failure.category`, `resolution`, `version` | appended by the ledger's single transition engine only |
| Credentials | **none** — no host, port, token, password or provider payload in the record or the ledger | audit-asserted |

## §5 Durable state machine (unchanged from GATE 13; evidence per transition)

```text
                       create()  [durable write #1]
   (nothing) ─────────────────────────────────────────► PREPARED
                                                            │
                        EnterMutationBoundary (inside the backend's mutationEntry,
                        i.e. the write is durable *before* the provider write) [write #2]
                                                            ▼
                                                        SUBMITTING ──────┐
                                                            │            │ reconciliation unresolved /
                       ConfirmedApplied + durable write #3  │            │ response lost / restart
                                                            ▼            ▼
                                                         APPLIED     AMBIGUOUS
                                               (terminal; never       │   ▲
                                                downgraded, never     │   │ RECONCILIATION_UNRESOLVED
                                                replayed)             │   │
                                          ConfirmedNotApplied ────────┴──►RETRY_ALLOWED
                                          (proved non-application)         │  explicit user retry only
                                                                           └─► PREPARED (same identity)
```

| Transition | Evidence required | Refused when |
| --- | --- | --- |
| `∅ → PREPARED` | validated request (turn, capability, §23 exclusion) + successful durable write + OP-M1 identity verdict | preparation write fails ⇒ **no backend call** (VER-21I-16); identity blocked ⇒ typed refusal, nothing durable |
| `PREPARED → SUBMITTING` | the backend itself asked to cross the mutation boundary (`mutationEntry` returned true) | boundary write fails ⇒ the backend was told not to mutate; `ConfirmedNotApplied` keeps `PREPARED`/`RETRY_ALLOWED`, anything else becomes explicit uncertainty (`persistBoundaryViolation`) |
| `SUBMITTING → APPLIED` | `ConfirmedApplied` **and** a durable write; or authoritative read-only reconciliation (`RECONCILIATION_CONFIRMED_APPLIED`) | APPLIED write fails ⇒ never resubmitted; the response is retried as a *write* (VER-21I-20) |
| `SUBMITTING → RETRY_ALLOWED` | `ConfirmedNotApplied` (backend certifies no mutation was dispatched by this attempt) or reconciliation proving non-application | never from a timeout, a transport error or a restart |
| `SUBMITTING → AMBIGUOUS` | lost response, `OutcomeUnknown`, or a recovered record whose reconciliation was unresolved | — (this is the conservative default after process death) |
| `AMBIGUOUS → APPLIED / RETRY_ALLOWED` | authoritative reconciliation evidence only | a retry request is rejected outright (`RETRY_REQUESTED` from `AMBIGUOUS` → `null`) |
| `RETRY_ALLOWED → PREPARED` | explicit user retry, re-validated from scratch (OP-M2 identity verdict, §23 exclusion, same id) | automatic/implicit retry does not exist |
| `APPLIED → *` | none — terminal | every event re-states the truth without mutating it |

A restored `SUBMITTING` is **not** treated as proof of non-application (INV-21I-13): recovery
classifies it as `Reconcile`, and an unresolved reconciliation normalizes it to `AMBIGUOUS`. The
projection maps a restored `SUBMITTING` to `VerificationRequired` and a restored `PREPARED` to
`RetryAvailable` (`ReviewerActionUiState.from(..., recovered = true)`).

## §6 Action routing matrix

| Operation | Backend source of truth | AUTO permitted? | Where enforced |
| --- | --- | --- | --- |
| New StudySession start | pre-binding resolution (21E resolver over the preference) | **Before binding only** | `AnkiStudyEffectExecutor.begin()` → `AnkiBackendResolver2.resolve(...)` → 21G admission → lock |
| Prepare ReviewerAction | the locked session's backend (`effect.cardRef.backendId` + `sessionLockConsistent`) | No | `reviewerAction()` OP-M1 gate, before anything durable |
| Submit action | `record.backendId` | No | `DefaultReviewerActionCoordinator.submit()` → `registry.find(record.backendId)` |
| Retry action (if authorized) | `record.backendId` + record's binding collection | No | `retryReviewerAction()` OP-M2 gate → `coordinator.retry()` |
| Reconcile ambiguous action | `record.backendId` (OP-M3) | No | `AnkiReviewerActionReconciler.reconcile()` gate before the provider read |
| Recover after restart | each record's own `backendId`, grouped per owner (OP-P) | No | `actionRecoveryPlan()`, `begin()`'s `actionLedger.recoveryBlocker(...)` |
| Next card after action | the locked session context's backend | No | `read()` → `registry.find(effect.session.context.backendId)?.nextCard(...)` |

## §7 Crash & recovery report

Six windows, each run **100× on a fresh process** over the same durable bytes, plus 100 clean
controls (`Gate21iActionEnduranceTest`), with per-backend counters asserted in every run:

| Window | Injected fault | Durable truth after crash | After the recovery pass | Backend calls |
| --- | --- | --- | --- | --- |
| W1 | death before the `PREPARED` write (`failNextWrites`) | **no record exists** | nothing to recover | A: 0 crossings; B: 0 |
| W2 | death after the `PREPARED` write | `PREPARED` | `PREPARED` (provably un-entered ⇒ offered as a safe retry) | A: 0 crossings; B: 0 |
| W3 | death after the boundary, before the provider effect (`crashAfterBoundary`) | `SUBMITTING` | `AMBIGUOUS` (read-only reconciliation found no authoritative evidence) | A: 1 crossing, 1 reconciliation read; B: 0 |
| W4 | death after the effect, before the response (`crashAfterApply`) | `SUBMITTING` | `APPLIED` (reconciliation proved the effect) — **no second mutation** | A: 1 invocation ever, 1 read; B: 0 |
| W5 | response lost, no crash (`OutcomeUnknown`) | `AMBIGUOUS` | `AMBIGUOUS` (unresolved is a first-class answer) | A: 1 crossing; B: 0 |
| W6 | `APPLIED` write fails after a confirmed effect | `SUBMITTING` | `APPLIED` via reconciliation — never resubmitted | A: 1 invocation ever; B: 0 |
| control | clean confirmed action | `APPLIED` | `APPLIED` | A: 1 invocation, 1 crossing, 0 reads; B: 0 |

```text
Durable preparation:        write #1 (PREPARED) precedes every backend call; failure ⇒ zero calls
Submission-intent barrier:  write #2 (SUBMITTING) is performed *inside* the backend's mutationEntry,
                            so the intent is durable before the provider write; a failed write tells
                            the backend not to mutate, and a backend that mutates anyway yields
                            explicit uncertainty, never a retry grant
Lost response behavior:     OutcomeUnknown / crash ⇒ AMBIGUOUS; never replayed, never re-routed
APPLIED persistence:        APPLIED is durable before any session effect; a failed APPLIED write is
                            retried as a *write* (or resolved by reconciliation), never as a mutation
AMBIGUOUS behavior:         survives restart, session end, preference change, backend disconnect and
                            Library browsing; the projection is VerificationRequired, no retry offered
RETRY_ALLOWED evidence:     per action and per backend: frozen promise ∩ live claim
                            (AnkiDroid bury/suspend = verified idempotent preflight + card-state read;
                            flag = UNVERIFIED ⇒ never retryable, never reconcilable)
Reconciliation support:     AnkiDroid bury/suspend = instance-bound card state (supported);
                            flag anywhere = unsupported ⇒ Unresolved with zero provider calls
Backend reconnect:          reconnect re-runs the identity contract; it never authorizes replay by itself
Collection mismatch:        verified mismatch or degraded key ⇒ Blocked(mismatch/degraded token),
                            zero provider calls, record and owner preserved
Legacy records:             schema v1 exact; an undecodable owner or a rewritten id makes the snapshot
                            Unreadable ⇒ Health.Unavailable, empty recovery plan, nothing guessed,
                            nothing erased
```

## §8 Action / rating mutual exclusion (GATE 11 ↔ GATE 13)

Two separate authorities, coordinated below the UI, in both directions:

* **Action defers to rating** — `DefaultReviewerActionCoordinator.commitBlocker(turnId, sessionId)`
  reads the *rating* ledger read-only; `ReviewerActionPolicy.commitBlockReason` maps
  `PREPARED → COMMIT_PENDING`, `SUBMITTING → COMMIT_MUTATION_IN_FLIGHT`,
  `RETRY_ALLOWED → COMMIT_RETRY_PENDING`, `AMBIGUOUS → COMMIT_UNRESOLVED`,
  `COMMITTED → TURN_RESOLVED`. A cross-session commit yields `rating_commit_other_session`.
* **Rating defers to action** — `ReviewerActionPolicy.ratingBlockReason(activeActionStatus)` maps
  `PREPARED/SUBMITTING → ACTION_IN_FLIGHT`, `RETRY_ALLOWED → ACTION_RETRY_AVAILABLE`,
  `AMBIGUOUS → ACTION_OUTCOME_UNRESOLVED`; the reducer refuses the rating intent with those reasons.
* **Exclusion scope is the narrowest safe one** — the ledger's rules are per turn
  (`it.turnId == record.turnId && it.isUnresolved`) and per session/backend
  (`it.backendId == record.backendId && it.sessionId == record.sessionId && it.isUnresolved`), plus a
  process-local `attemptsInFlight` set keyed by `ReviewerActionId`. No global serialization across
  unrelated cards or sessions was introduced.
* **Neither vocabulary collapsed into the other** — an action never creates, promotes or records a
  `ReviewCommitStatus`, and no revlog/interval/FSRS state is manufactured (INV-21I-25; audit-asserted).
  `restoreSession()` treats an unresolved action and an unresolved commit as *equally* barrier-holding
  without merging their status machines (DECISION-21I-07).
* **Selecting a different backend cannot bypass a barrier** — the barrier is evaluated on the durable
  records of the session's own backend before the identity gate; a preference change reaches neither
  (VER-21I-11/32/33).

## §9 File-by-file implementation report

```text
FILE:   app/src/main/java/com/studyagent/client/core/anki/ReviewerActionRoutingPolicy.kt (319 l)
ACTION: REUSED / VERIFIED (21I implementation found in the tree at HEAD; no defect found)
LOCKED CONTRACT REFERENCES: DECISION-21B-03 (four assurance categories), DECISION-21B-15/16 (routing
        rules 1–8), GATE 21C §3.3/§3.5, GATE 13 §13/§16/§17/§26–§30, DECISION-21I-01…06
RESPONSIBILITY: the reviewer-action family's admission/retry/reconcile/recovery verdicts, from durable
        identity only
IMPLEMENTED: ReviewerActionFamilyFacts.of (facts), mutationOperationClass (OP-M1/OP-M2),
        bindingCollectionKey (DECISION-21I-02), route(record|ownerBackendId, …) → Routed|Blocked,
        reconcileRoute (OP-M3, DECISION-21I-03), familyReconciliationAvailable (frozen ∩ live),
        recoveryPlan (OP-P, per-owner grouping via the 21D planner)
DEPENDENCIES: AnkiBackendRegistry (21D), AnkiIdentityAssurancePolicy/AnkiIdentityContinuation (21C/21B),
        AnkiTransactionRecoveryPlanner (21D), ReviewerActionRecord/ReviewerAction (GATE 13)
LOCAL CHECKS: Gate21iReviewerActionRoutingTest (43), Gate21iArchitectureAuditTest, mutants M1/M2 killed
ARCHITECTURE BOUNDARY:
- original backend identity preserved: YES (only registry.find(ownerBackendId|record.backendId|it))
- resource/action scope preserved: YES (record.action/cardRef are read, never rewritten)
- ledger reused: YES (no ledger, coordinator, registry or resolver declared here)
- no AUTO recovery: YES (no resolver/preference/mode/AUTO token in the file's code)
- no blind replay: YES (a verdict never issues a backend call)
- rating mutual exclusion preserved: YES (untouched; owned by the coordinator/policy)
- session effect follows authoritative result: YES (this file produces no session effect)
READY FOR NEXT CHECKPOINT: YES

FILE:   app/src/main/java/com/studyagent/client/core/anki/ReviewerActionRecovery.kt (384 l)
ACTION: REUSED / VERIFIED (21I's OP-M3 gate present; GATE 13's table and policy untouched)
LOCKED CONTRACT REFERENCES: GATE 13 §11/§26/§27/§28/§30, DECISION-21I-03/05/06
RESPONSIBILITY: recovery events, the one pure recovery transition function, the recovery decision
        policy, and the read-only reconciler
IMPLEMENTED: ReviewerActionRecoveryEvent + reviewerActionRecoveryTransition (closed table),
        ReviewerActionRecoveryPolicy.classify/classifyStatus, ReviewerActionReconciliationResult,
        AnkiReviewerActionReconciler (OP-M3 identity gate → owner check → frozen ∩ live capability →
        bounded read-only probe → Unresolved on timeout)
LOCAL CHECKS: VER-21I-18/19/22/23/24/25/28/41; endurance W3/W4; mutant M5 killed
ARCHITECTURE BOUNDARY: original backend only (record.backendId, cardRef.backendId, collectionRef
        .backendId all checked); no mutation call in the reconciler; no AUTO/preference; unresolved is
        a first-class answer
READY FOR NEXT CHECKPOINT: YES

FILE:   app/src/main/java/com/studyagent/client/core/study/AnkiStudyEffectExecutor.kt (1 154 l)
ACTION: REUSED / VERIFIED (21I seams present: actionRouting, OP-M1/OP-M2 gates, actionRecoveryPlan,
        action-aware restore barrier)
LOCKED CONTRACT REFERENCES: GATE 13 §16/§17/§22/§26, GATE 21G lock, GATE 21H separation,
        DECISION-21I-05/07, INV-21I-02/05/21/24/28
RESPONSIBILITY: effect execution — correlation and event shaping only; the coordinator owns durable
        ordering
IMPLEMENTED: reviewerAction() (21G lock check → OP-M1 gate → coordinator.perform), retryReviewerAction()
        (OP-M2 gate → coordinator.retry), recoverReviewerAction() (read-only), actionRecoveryPlan()
        (OP-P), restoreSession() (unresolved actions hold the next-card barrier), begin()
        (actionLedger.health + recoveryBlocker before any scheduler traffic)
LOCAL CHECKS: VER-21I-03…06/09/11/12/16…23/26…29/37/38; mutants M4/M6 killed
ARCHITECTURE BOUNDARY: resolver/preference appear only in begin() (pre-binding) — asserted per member
        body; no read-context token; no second ledger or coordinator; rating path untouched
READY FOR NEXT CHECKPOINT: YES

FILE:   app/src/main/java/com/studyagent/client/core/anki/ReviewerAction{,Record,Ledger,Coordinator,
        Policy,Status,BackendResult}.kt
ACTION: NO_CHANGE (GATE 13 remains the sole transaction authority — INV-21I-30)
LOCAL CHECKS: ReviewerActionLedgerTest, ReviewerActionTransitionTest, ReviewerActionMachineTest,
        ReviewerActionCoordinatorTest, ReviewerActionUiProjectionTest, Gate13ArchitectureAuditTest,
        Gate21iArchitectureAuditTest (vocabulary + single-authority rules)

FILE:   app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidBackend.kt,
        AnkiDroidReviewerActionCommitter.kt, AnkiDroidReviewerActionGateway.kt
ACTION: NO_CHANGE (pinned public provider contract; flags = false; card-scoped bury/suspend;
        read-only reconcile; no private DB access)
LOCAL CHECKS: AnkiDroidIntegrationIsolationTest, Gate13ArchitectureAuditTest, the 21I audit's privacy
        and capability rules

FILE:   app/src/main/java/com/studyagent/client/di/AppContainer.kt
ACTION: NO_CHANGE (registry + reviewCommitLedger + reviewerActionLedger + sessionLocks +
        sessionLockStore + transactionRecovery already wired; actionRouting defaults to the production
        policy over the same registry — no new DI surface, no duplicate registry/resolver/coordinator)
LOCAL CHECKS: audit (single-declaration counts); DI instantiation is not exercised by the harness
        (carried limitation, as in 21G/21H)

FILE:   app/src/test/java/com/studyagent/client/study/Gate21iReviewerActionRoutingTest.kt (1 419 l)
ACTION: MODIFIED (+188/−3) — VER labels corrected to the prompt's numbering (bury-on-AnkiDroid is
        VER-21I-03; the AnkiDroid flag case is VER-21I-01, capability-blocked; the reconnect case is
        labelled VER-21I-27 + VER-21I-28) and three verifications added:
        VER-21I-04 `a bury bound to a keyed PC backend executes on that backend only`,
        VER-21I-06 `a suspend bound to a keyed PC backend keeps its card scope and its owner`,
        VER-21I-15 `an unknown collection is disclosed as unverifiable and authorizes no blind retry`
LOCAL CHECKS: 43/43 pass; mutant M6 (admission hard-coded to AnkiDroidLocal) is killed by the two new
        PC tests plus the existing PC flag test

FILE:   app/src/test/java/com/studyagent/client/anki/Gate21iArchitectureAuditTest.kt (536 l)
ACTION: CREATED — the machine-checked half of PART II (AUDIT-21I-01…27), following the GATE 12–21G
        source-audit convention (comments stripped, so documentation cannot satisfy a rule)
LOCAL CHECKS: 10/10 pass; mutants M1 (substituted backend in reconcileRoute), M2 (binding evidence
        taken from the resource ref), M3 (progression barrier weakened to status only),
        M5 (reconciler claims in-process continuity) all killed

FILE:   app/src/test/java/com/studyagent/client/study/Gate21iActionEnduranceTest.kt,
        ReviewerActionHarness.kt, anki/fake/FakeAnkiBackend.kt, anki/fake/InMemoryReviewerActionStore.kt
ACTION: NO_CHANGE (21I fixtures already present: two-backend topology with duplicate card ids,
        keyed/keyless collections, availability churn, preference churn through the real resolver,
        crash-after-boundary / crash-after-apply, scripted reconciliation, write-failure injection,
        per-backend counters for action/boundary/reconciliation/next-card/commit/deck/hydrate)
LOCAL CHECKS: 4/4 endurance tests pass (1 000-event churn; 6 windows × 100 processes; 2 × 100 sweeps)

FILE:   docs/GATE_21I_REVIEWER_ACTION_ROUTING.md, docs/GATE_21I_VERIFICATION_REPORT.md
ACTION: CREATED — the gate's contract record and its single authoritative verification source
```

## §10 Architecture audit matrix (PART II)

Structural evidence = `Gate21iArchitectureAuditTest` (source rules, comments stripped); behavioural
evidence = `Gate21iReviewerActionRoutingTest` / `Gate21iActionEnduranceTest` / prior-gate suites.

| Audit | Result | Evidence |
| --- | --- | --- |
| AUDIT-21I-01 contract conformance | **PASS** | the policy routes through `AnkiIdentityAssurancePolicy.check` + `AnkiIdentityContinuation.continuationVerdict` with `OP_M1/OP_M2/OP_M3`; the five GATE 13 statuses exist verbatim, in order, with exactly one declaration (audit test 1) |
| AUDIT-21I-02 single transaction authority | **PASS** | exactly one each: `DurableReviewerActionLedger`, `interface ReviewerActionLedger`, `DefaultReviewerActionCoordinator`, `interface ReviewerActionCoordinator`, `interface ReviewerActionStore`, `ReviewerActionRoutingPolicy`; the rating family keeps its own (audit test 1) |
| AUDIT-21I-03 immutable backend destination | **PASS** | all record fields `val`, no `var` in the record body, no `copy(backendId =`/`copy(action =`/`copy(cardRef =`/`copy(collectionRef =`/`copy(turnId =`/`copy(sessionId =`/`copy(actionId =` in the family (audit test 2); VER-21I-11/22/26 |
| AUDIT-21I-04 correct action target | **PASS** | construction requires `cardRef.backendId == backendId`, `collectionRef.backendId == backendId`, and key agreement; binding evidence is `collectionRef` only (audit tests 2–3); VER-21I-08/09/10 |
| AUDIT-21I-05 exact registry lookup | **PASS** | exactly three registry reads in the policy, all `find(...)` by identity; no `registry.ids`, no `firstOrNull`, no kind-only fallback (audit test 3); VER-21I-26 |
| AUDIT-21I-06 no AUTO recovery | **PASS** | no `AnkiBackendResolver`/`.resolve(`/`AUTO`/`BackendPreference`/`preference`/`AnkiBackendMode`/`AnkiBackendSelector` token in the policy, the reconciler or any transaction member of the executor — the resolver appears only inside `begin()` (audit tests 3–4); VER-21I-11/12 |
| AUDIT-21I-07 no cross-backend replay | **PASS** | `submit()` uses `registry.find(record.backendId)` and reports `backend_missing` when the owner is gone; B's counters are zero in every routing, recovery and endurance test; mutants M1/M6 killed |
| AUDIT-21I-08 capability truth | **PASS** | `AnkiBackend` defaults refuse (`UnsupportedAction`, `UNVERIFIED`, `Unresolved`); AnkiDroid claims `flags = false` and `FLAG → UNVERIFIED`; the frozen session capability set gates the menu (audit test 5); VER-21I-01/07 |
| AUDIT-21I-09 semantic scope | **PASS** | exactly three actions, all card-scoped; no `BuryNote`/`SuspendNote`/`Sibling`/`noteId`/`bulk` token exists (audit test 5); VER-21I-06/08 |
| AUDIT-21I-10 collection assurance | **PASS** | mismatch and degraded block `OP-M3`; `RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH` is a real refusal; NOMINAL/continuity permit only under the frozen ∩ live capability and are disclosed verbatim (audit test 6); VER-21I-14/15/28 |
| AUDIT-21I-11 durable preparation | **PASS** | `ledger.create(...)` precedes `backend.performReviewerAction(...)`; the boundary transition is written inside the backend's own `mutationEntry` (audit test 7); VER-21I-16/17 |
| AUDIT-21I-12 APPLIED persistence barrier | **PASS** | `nextCardAllowed(action, status)` is the single rule and the reducer's Applied branch evaluates it before emitting `AnkiStudyEffect.Next`; no fabricated `AnkiReviewTurn(` in that branch (audit test 8); mutant M3 killed; VER-21I-20/21/35 |
| AUDIT-21I-13 ambiguous safety | **PASS** | `RETRY_REQUESTED` is legal only from `PREPARED`/`RETRY_ALLOWED`; `RECONCILIATION_UNRESOLVED` never yields `RETRY_ALLOWED`; the reducer rejects an unsafe retry with `anki-action-retry-not-safe` (audit test 9); VER-21I-19/22/23 |
| AUDIT-21I-14 retry evidence | **PASS** | eligibility is `frozenIdempotentReplay`/`frozenAuthoritativeReconciliation` ∩ the live per-action claim (can only shrink); flag is never retryable/reconcilable (audit test 6); VER-21I-23/25 |
| AUDIT-21I-15 reconciliation honesty | **PASS** | the reconciler reads only `backend.reconcileReviewerAction`; no `performReviewerAction`/`commitRating`/`nextCard`/`updateNote`/`createNote`/`buryCards`/`suspendCards` token; timeouts and missing capability are `Unresolved`, never `ConfirmedNotApplied` (audit test 6); VER-21I-25/41 |
| AUDIT-21I-16 crash safety | **PASS** | interrupted `SUBMITTING` reconciles or normalizes to `AMBIGUOUS`; endurance W3 (100 processes) never produces `RETRY_ALLOWED` |
| AUDIT-21I-17 duplicate protection | **PASS** | identity is derived via `identityKey(...)`; no `UUID`/`randomUUID`/`Random(`/`random(` token in the family; the ledger collapses `sameAction`, one active per turn, one unresolved per session/backend (audit test 9); VER-21I-30/31 |
| AUDIT-21I-18 rating mutual exclusion | **PASS** | `commitLedger` is read-only inside the action coordinator; both directions of `ReviewerActionPolicy` exist; no action status is expressed in commit vocabulary; the two routing policies and two recovery plans stay separate (audit test 10); VER-21I-32/33 |
| AUDIT-21I-19 session progression | **PASS** | progression only via a fresh `nextCard` on the locked session backend after `APPLIED`; a flag keeps the turn (audit test 8); VER-21I-34/35/36 |
| AUDIT-21I-20 stale callback safety | **PASS** | `sessionLockConsistent` before dispatch, epoch + `actionId` validation in the reducer (`stale-anki-action-result`/`-retry`); mutant M4 (lock check bypassed) is killed by VER-21I-09 |
| AUDIT-21I-21 multi-backend recovery isolation | **PASS** | `recoveryPlan` groups by `it.backendId`; blocking is per group; VER-21I-29 shows X→A staying `AMBIGUOUS` while Y→B resolves |
| AUDIT-21I-22 legacy record safety | **PASS** | schema v1 exact (`unsupported_schema_$schemaVersion`), derived-id equality check, `unreadable_action_record`; no `?: AnkiBackendId.` default owner anywhere in main; an unreadable ledger routes nothing (audit test 9); VER-21I-39/40 |
| AUDIT-21I-23 read-context independence | **PASS** | no `AnkiReadContextController`/`AnkiReadContextBinding`/`libraryBackendId`/`cardBrowserBackend` token in the executor, policy, coordinator or recovery (audit test 10); VER-21I-13 |
| AUDIT-21I-24 security / privacy | **PASS** | no `password`/`Bearer`/`apiKey`/`authorization`/`SQLiteDatabase`/`rawQuery`/`content://` token in the family; the AnkiDroid action path uses `AnkiDroidApiContract.SCHEDULE_PATH` via `providerClient.safeUpdate(...)`; ledger diagnostics are content-free aggregates (audit test 10) |
| AUDIT-21I-25 no scheduler duplication | **PASS** | no `fsrs`/`easeFactor`/`computeInterval`/`dueQueue`/`scheduleCard`/`answerCard` token in the family; the next card is always a backend query |
| AUDIT-21I-26 scope discipline | **PASS** | note edit/create coordinators and records contain no `ReviewerActionStatus`; no UI source references `ReviewerActionRoutingPolicy`/`ReviewerActionRoute`/`actionRecoveryPlan`; no offline queue/WorkManager token (audit tests 1, 10) |
| AUDIT-21I-27 prior-gate preservation | **PASS** | 21H's `commitRouting` + `recoveryPlan()` intact; 21G's `sessionLockConsistent`/`sessionBoundHere` intact; 21F read contexts untouched; 21C/21B seams reused, not forked; full-suite regression 2 584/2 584 |

**Mutant kills (audit teeth):** M1 substituted backend in `reconcileRoute` → killed by audit test 3.
M2 binding evidence from `cardRef` → killed by audit test 3. M3 progression barrier weakened to
`status == APPLIED` → killed by audit test 8. M5 reconciler claiming `boundIsLiveInThisProcess = true`
→ killed by audit test 6. M4 executor's 21G lock check bypassed → killed by VER-21I-09. M6 OP-M1
admission hard-coded to `AnkiDroidLocal` → killed by VER-21I-02/04/06. All six were reverted; the
final suite ran against pristine main (`git status` shows test/doc changes only).

## §11 Verification matrix (PART III — single authoritative source)

"PC" rows run against a keyed `FakeAnkiBackend` standing in for a PC instance: they verify routing
and ownership, **not** a working PC Anki backend (none exists — `AUDIT-21D-14`).

| Verification | Result | Evidence (test name / artifact) |
| --- | --- | --- |
| VER-21I-01 flag on AnkiDroid | **PASS (scope-excluded)** | No flag write path exists at the pinned contract (`flags = false`, `FLAG → UNVERIFIED`), so there is no mutation to route. Verified as the honest alternative: `an unsupported action is refused with a typed refusal, never a substitute` — typed refusal, zero perform effects, zero calls on A and B, nothing durable |
| VER-21I-02 flag on PC | **PASS (fixture)** | `a flag bound to a keyed PC A executes on A only` — owner = `PcAgent("gate21i-pca")`, receipt flag `RED`, substitute counters zero |
| VER-21I-03 bury on AnkiDroid | **PASS** | `a bury bound to A executes on A only, with every B counter at zero` — APPLIED, 1 crossing, B's action/boundary/reconciliation/next-card/commit/deck/hydrate counters all 0 |
| VER-21I-04 bury on PC | **PASS (fixture)** | **NEW** `a bury bound to a keyed PC backend executes on that backend only` — durable owner, target, binding key, `AuthoritativeMatch` verdict, exactly one fresh scheduler query on the owner, all substitute counters 0 |
| VER-21I-05 suspend on AnkiDroid | **PASS** | `a suspend bound to A executes on A only with the card scope` |
| VER-21I-06 suspend on PC | **PASS (fixture)** | **NEW** `a suspend bound to a keyed PC backend keeps its card scope and its owner` — dispatched action byte-identical to the admitted one, sibling card on the same backend never targeted, duplicate card id on the substitute never touched |
| VER-21I-07 unsupported action | **PASS** | same test as VER-21I-01: typed refusal, no substitute operation on any backend |
| VER-21I-08 card vs note scope | **PASS** | `a card-scoped bury never reaches a sibling carrying the same id on B` + VER-21I-06 + audit rule "no note/sibling-scoped action may exist" |
| VER-21I-09 ownership mismatch | **PASS** | `an action effect naming another backend than the session lock is refused before dispatch` — `SESSION_BACKEND_MISMATCH`, zero calls on A and B, nothing durable; kills mutant M4 |
| VER-21I-10 card-id collision | **PASS** | `a matching card id on two backends never authorizes a B mutation` (+ the sibling test above): both backends hold `cardId = 123` |
| VER-21I-11 preference change | **PASS** | `a preference change mid-session cannot move a pending action`; endurance churn flips the preference to B every third event and asserts the resolver really resolves to B |
| VER-21I-12 AUTO availability change | **PASS** | `AUTO eligibility changing while an action is pending leaves the owner unchanged` |
| VER-21I-13 Library read-context switch | **PASS** | `browsing B's library while an action is pending on A does not redirect it` + audit rule (no read-context token in the mutation path) |
| VER-21I-14 verified collection mismatch | **PASS** | `a verified collection mismatch blocks a new action before dispatch` — `collection_mismatch`, zero calls, nothing durable |
| VER-21I-15 unknown collection assurance | **PASS** | **NEW** `an unknown collection is disclosed as unverifiable and authorizes no blind retry` — keyless binding yields `SessionContinuityVerified` (live) / `IdentityUnverifiable(NOMINAL)` (restored), never `AuthoritativeMatch`, and grants no retry; plus `a degraded key blocks a proven-not-applied retry / a brand-new action / reconciliation with zero provider calls` (`collection_identity_degraded`) |
| VER-21I-16 PREPARED persistence failure | **PASS** | `a failed durable preparation blocks the backend call entirely` — 0 records, 0 calls, 0 crossings |
| VER-21I-17 SUBMITTING persistence failure | **PASS** | `a failed durable boundary write refuses the mutation and leaves PREPARED` — the backend was invoked but told not to mutate (0 crossings); durable row stays `PREPARED`; projection offers the same identity |
| VER-21I-18 crash before backend call | **PASS** | `a crash before the provider call leaves SUBMITTING and recovery assumes nothing` + endurance W3 ×100 (`SUBMITTING → AMBIGUOUS`, one read, no replay) |
| VER-21I-19 applied action / lost response | **PASS** | `an applied action with a lost response is ambiguous and never replayed` + endurance W4/W5 ×100 (exactly one invocation ever) |
| VER-21I-20 applied / APPLIED persistence failure | **PASS** | `a confirmed action whose APPLIED persistence failed is not resubmitted` + endurance W6 ×100 (resolved by reconciliation, never resubmitted) |
| VER-21I-21 durable APPLIED | **PASS** | `a confirmed bury is durable and produces exactly one fresh scheduler query` |
| VER-21I-22 AMBIGUOUS on restart | **PASS** | `an ambiguous action keeps backend target parameters and ambiguity after death` — backend, card, action, parameters and status all survive `restart()` |
| VER-21I-23 RETRY_ALLOWED evidence | **PASS** | `a retry from proven non-application reuses the identity, and a blind retry is refused` — retry only after a certified non-application; `AMBIGUOUS` retry rejected by the machine |
| VER-21I-24 supported reconciliation | **PASS** | `supported reconciliation resolves through the original backend alone` — 1 read on A, 0 on B |
| VER-21I-25 unsupported reconciliation | **PASS** | `unsupported reconciliation leaves the outcome unresolved with zero provider calls` (flag: frozen promise false) |
| VER-21I-26 original backend removed | **PASS** | `a removed original backend blocks its actions and lends nothing to the ready backend` — `Blocked(BACKEND_NOT_REGISTERED)`, record and owner preserved, B untouched |
| VER-21I-27 original backend reconnect | **PASS** | `reconnect validates identity before safe recovery and a changed collection stays blocked` (second half: same key ⇒ the read resolves it) |
| VER-21I-28 different collection after reconnect | **PASS** | same test, first half: `key-2` ⇒ still `AMBIGUOUS`, zero reconciliation calls on A and B |
| VER-21I-29 multiple outstanding backends | **PASS** | `actions for A and B recover independently during a recovery pass` — A down ⇒ `Blocked(BACKEND_UNREACHABLE)`; B routed and resolved; X unchanged |
| VER-21I-30 duplicate same action | **PASS** | `repeated bury input produces one logical action and at most one mutation` |
| VER-21I-31 conflicting actions | **PASS** | `conflicting actions on one turn obey admission ordering` (GATE 13 §15 one active action per turn) |
| VER-21I-32 rating during action | **PASS** | `a rating cannot bypass an unresolved reviewer action` |
| VER-21I-33 action during rating | **PASS** | `an action cannot bypass an active conflicting rating transaction` |
| VER-21I-34 flag session effect | **PASS** | `a confirmed flag keeps the turn without fabricated progression` — same turn/card/phase, no scheduler query |
| VER-21I-35 bury session effect | **PASS** | `a confirmed bury closes the turn and asks for exactly one fresh card` |
| VER-21I-36 suspend session effect | **PASS** | `a confirmed suspend behaves exactly like bury at the session level` |
| VER-21I-37 stale callback | **PASS** | `an old action callback cannot advance or modify a newer session` |
| VER-21I-38 end session with pending action | **PASS** | `an unresolved action survives session termination and keeps its owner` (+ `an unresolved action holds the session-restore barrier like a commit would`, DECISION-21I-07) |
| VER-21I-39 legacy without backend identity | **PASS** | `an unreadable action ledger routes nothing and fabricates no owner` + audit rules (schema exact, derived-id equality, no default owner) |
| VER-21I-40 legacy with provable identity | **PASS** | `a legacy record with a full identity routes to its own backend` |
| VER-21I-41 read-only status verification | **PASS** | `reconciliation verifies state read-only and never mutates` — 1 invocation ever, 1 boundary crossing ever, 1 read; B untouched |
| VER-21I-42 1 000-event failure injection | **PASS** | `a thousand-event session stays on the admitted backend under preference and availability churn` + `the six crash windows hold their durable truth across one hundred fresh processes each` + `recovery of an ambiguous action is read-only, per window, across a hundred crashes` + `an applied-then-lost effect is reconciled to APPLIED without a second mutation`. Required counters — see §12 |
| VER-21I-43 real backend validation | **BLOCKED** | No device/emulator in this sandbox, no Android SDK, Gradle cannot fetch its distribution, and **no production PC Anki backend exists** to validate against. GATE 13's own real-device mutation verification (its VERIFICATION 14) is likewise still NOT RUN. No personal study collection was touched; no real Anki mutation was performed by any test in this gate |
| VER-21I-44 previous-gate regression | **PASS** | full suite `classes=215 tests=2584 passed=2584 failed=0`; sweeps: `ReviewerAction\|Gate13` 147/147, `ReviewCommit\|RatingCommit\|Gate11\|Gate21\|Anki` 1478/1478, `Study\|Session` 250/250. Design-only features (GATE 20 Custom Study, a PC Anki backend) are **not** reported as runtime-tested |

**VER-21I-42 required counters (all asserted, all zero):**

```text
Cross-backend action calls:                     0   (every B counter: invocations, boundary crossings,
                                                     reconciliation reads, next-card, commit, deck, hydrate)
Wrong-card mutations:                           0   (duplicate cardId 123 on A and B; sibling card untouched)
Wrong-scope mutations:                          0   (no note/sibling action exists; dispatched action == admitted action)
Blind replay of AMBIGUOUS actions:              0   (retry request rejected; invocations freeze at the crash point)
Unjustified RETRY_ALLOWED transitions:          0   (only certified non-application or proven reconciliation)
Wrong-backend reconciliation:                   0   (B's reconciliation counter is 0 in every case)
Lost unresolved action records:                 0   (records survive restart, session end, backend removal,
                                                     preference change and an unreadable-ledger control)
Session progression barrier violations:         0   (next-card only after durable APPLIED; restore barrier holds)
Stale callback contamination:                   0   (epoch + actionId + session-lock validation)
ReviewCommit mutual exclusion violations:       0   (both directions refused; no commit status written by an action)
```

## §12 Build results (PART V)

| Command | Executed? | Exit code | Result |
| --- | --- | --- | --- |
| `tools/jvm-harness/bin/bootstrap-sandbox.sh` | YES | 0 | harness built (jars, mockable android.jar, shims, main, compose, tests) |
| `tools/jvm-harness/bin/build.sh main` | YES | 0 | `main errors: 0 (files: 327)` |
| `tools/jvm-harness/bin/build.sh compose` | YES (bootstrap) | 0 | `compose errors: 0 (files: 24, stub files: 46)` |
| `tools/jvm-harness/bin/build.sh test` | YES | 0 | `test errors: 0 (files: 247)` |
| `tools/jvm-harness/bin/run.sh` (full) | YES | 0 | `classes=215 tests=2584 passed=2584 failed=0 ignored=0 time=64.0s` |
| `run.sh 'Gate21i'` | YES | 0 | `classes=3 tests=57 passed=57 failed=0` |
| `run.sh 'ReviewerAction\|Gate13'` | YES | 0 | `classes=8 tests=147 passed=147 failed=0` |
| `run.sh 'ReviewCommit\|RatingCommit\|Gate11\|Gate21\|Anki'` | YES | 0 | `classes=116 tests=1478 passed=1478 failed=0` |
| `run.sh 'Study\|Session'` | YES | 0 | `classes=22 tests=250 passed=250 failed=0` |
| `./gradlew --version` | YES | 1 | **BLOCKED** — `java.io.EOFException: SSL peer shut down incorrectly` while downloading `gradle-8.7-bin.zip` from `services.gradle.org` (host unreachable from this sandbox) |
| `./gradlew testDebugUnitTest` | YES | 1 | **BLOCKED** — same wrapper distribution failure; not executed, therefore **not claimed** |
| `./gradlew lint` | YES | 1 | **BLOCKED** — same; additionally no Android SDK in the sandbox |
| `./gradlew assembleDebug` | YES | 1 | **BLOCKED** — same |
| `./gradlew assembleRelease` | YES | 1 | **BLOCKED** — same |
| `./gradlew clean` / `--stop` | NO | — | pointless while the distribution cannot be fetched (no Gradle user home exists) |
| `./gradlew connectedDebugAndroidTest` | NO | — | **BLOCKED** — no device/emulator, no Android SDK |

Build success and `VER-21I-*` results are reported separately, as required: the harness compiles and
runs the pure-Kotlin production sources and the JVM unit tests, but it is **equivalence evidence, not
CI proof** (Kotlin 2.3 K2 vs the repository's 1.9.24, shimmed JUnit/DataStore/OkHttp, four excluded
test classes, no Compose UI test, no lint, no APK). CI must re-run `./gradlew testDebugUnitTest lint
assembleDebug assembleRelease` before this gate's claims are treated as device-grade.

## §13 Invariant report (PART IV)

| Invariant | Result | Evidence |
| --- | --- | --- |
| INV-21I-01 one immutable original BackendId | **PASS** | record field is `val`, part of the derived id; audit test 2; VER-21I-11/22 |
| INV-21I-02 backend/collection-qualified target | **PASS** | construction requires card/collection backend equality; audit test 2 |
| INV-21I-03 kind/scope/parameters immutable after admission | **PASS** | no `copy(action =`/`copy(cardRef =` in the family; audit test 2 |
| INV-21I-04 matching numeric ids ≠ resource equality | **PASS** | VER-21I-08/10 (both backends hold `123`) |
| INV-21I-05 recovery never invokes AUTO | **PASS** | no resolver/AUTO/preference token outside `begin()`; audit tests 3–4 |
| INV-21I-06 no cross-backend action replay | **PASS** | B's counters zero across 43 + 4 tests; VER-21I-26/29 |
| INV-21I-07 explicit capability governs availability | **PASS** | frozen session capability set + `reviewerActionSemantics`; VER-21I-01/07/25 |
| INV-21I-08 card/note/sibling scope never substituted | **PASS** | no note/sibling action exists; audit test 5; VER-21I-06/08 |
| INV-21I-09 unknown collection ≠ verified continuity | **PASS** | VER-21I-15 (new): NOMINAL/continuity disclosed, never `AuthoritativeMatch` |
| INV-21I-10 verified mismatch blocks mutation | **PASS** | VER-21I-14/28 (`collection_mismatch`, zero calls) |
| INV-21I-11 durable preparation precedes submission | **PASS** | audit test 7; VER-21I-16 |
| INV-21I-12 durable submission intent precedes execution | **PASS** | boundary write inside `mutationEntry`; VER-21I-17/18 |
| INV-21I-13 interrupted SUBMITTING ≠ non-application | **PASS** | endurance W3 ×100 → `AMBIGUOUS`, never `RETRY_ALLOWED` |
| INV-21I-14 network timeout ≠ safe retry | **PASS** | `OutcomeUnknown`/timeout → `AMBIGUOUS`/`Unresolved`; VER-21I-19 |
| INV-21I-15 AMBIGUOUS never blindly retried | **PASS** | closed table rejects `RETRY_REQUESTED`; reducer `anki-action-retry-not-safe` |
| INV-21I-16 RETRY_ALLOWED needs action/backend evidence | **PASS** | frozen ∩ live semantics; flag never eligible; VER-21I-23/25 |
| INV-21I-17 apparent API idempotency not assumed | **PASS** | `supportsIdempotentReplay` is an audited per-action claim, not a default (`UNVERIFIED` otherwise) |
| INV-21I-18 APPLIED durable before gated effects | **PASS** | audit test 8; VER-21I-20/21; mutant M3 killed |
| INV-21I-19 reconciliation uses the original backend only | **PASS** | OP-M3 gate before the provider read; VER-21I-24/26; mutant M1 killed |
| INV-21I-20 current card state ≠ automatic proof | **PASS** | capability gate + attribution by `record.backendId`/`cardRef.backendId`; unsupported ⇒ `Unresolved` with zero calls |
| INV-21I-21 per-backend records independently owned | **PASS** | per-owner grouping and per-group blocking; VER-21I-29 |
| INV-21I-22 missing historical identity never guessed | **PASS** | codec fails closed; no default owner in main; VER-21I-39 |
| INV-21I-23 duplicate input ≠ duplicate transactions | **PASS** | derived id + ledger exclusion rules; VER-21I-30/31 |
| INV-21I-24 GATE 11/GATE 13 admission mutually safe | **PASS** | both directions; VER-21I-32/33; audit test 10 |
| INV-21I-25 actions never manufacture rating/revlog | **PASS** | reducer writes no `ReviewCommitStatus`; audit test 10; GATE 13 INV-13-04/05 re-asserted |
| INV-21I-26 next-card stays backend-authoritative | **PASS** | `AnkiStudyEffect.Next` → `registry.find(session.context.backendId)?.nextCard(...)`; audit test 8 |
| INV-21I-27 old callbacks cannot modify new sessions | **PASS** | epoch + actionId + session-lock validation; VER-21I-37; mutant M4 killed |
| INV-21I-28 ending a session cannot erase truth | **PASS** | VER-21I-38 + the restore-barrier test (DECISION-21I-07) |
| INV-21I-29 read-context switching cannot redirect mutations | **PASS** | VER-21I-13; audit test 10 |
| INV-21I-30 GATE 13 is the sole action authority | **PASS** | single ledger/coordinator/status declarations; audit test 1 |
| INV-21I-31 GATE 11 stays a separate authority | **PASS** | separate policy, plan, ledger, vocabulary; audit test 10 |
| INV-21I-32 no offline blind-mutation queue | **PASS** | no `OfflineQueue`/`WorkManager` token; audit test 1 |
| INV-21I-33 no private AnkiDroid DB/scheduler access | **PASS** | public `AnkiDroidApiContract` provider path only; audit test 10 |
| INV-21I-34 GATE 10–21H guarantees not weakened | **PASS** | 2 584/2 584 full-suite regression; 21G/21H seams untouched; DECISION-21I-03 defers to the GATE 13 contract rather than overriding it |

## §14 Residual risks

```text
Action-specific reconciliation unsupported   AnkiDroid flag has no write path at the pin, and even a
                                             fixture that models one reports
                                             supportsAuthoritativeReconciliation = false: a lost flag
                                             response can only ever remain AMBIGUOUS (R-21I-02).
Backend flag/bury/suspend asymmetry          AnkiDroid: bury/suspend verified both directions, flag
                                             impossible. PC: no backend at all, so every PC action is a
                                             default typed refusal in production and a fixture in tests
                                             (R-21I-04). Nothing was harmonized by inventing behaviour.
Unknown collection identity                  AnkiDroid exposes no collection key anywhere, so its
                                             actions carry no binding evidence: OP-M3 proceeds under
                                             NOMINAL/continuity with instance-bound card-state evidence,
                                             disclosed verbatim, and an authoritative match is
                                             unreachable for that backend (R-21I-01, DECISION-21I-03).
Historical records missing action scope      Schema v1 is matched exactly; a record whose action key or
                                             derived id does not resolve makes the snapshot Unreadable.
                                             Nothing is migrated by inference and nothing is erased to
                                             unblock startup (R-21I-07).
Concurrent edits from another Anki client    Card-state reconciliation can observe a state another
                                             client produced. Positive evidence is accepted only under
                                             the frozen ∩ live capability and the record's own
                                             backend/card; the opposite state never proves
                                             non-application (R-21I-03).
Ambiguous actions that cannot be resolved    AMBIGUOUS is terminal-for-now: it survives restart,
    automatically                            session end, preference change and disconnect, blocks
                                             progression, and is surfaced as VerificationRequired.
                                             Only authoritative evidence or the user ends it.
Backend-specific bury/sibling behavior       Anki's bury is day-scoped and both operations can be undone
                                             outside Study-Agent, so absence of the desired state proves
                                             nothing; sibling/note scope is deliberately not modelled
                                             because no audited public contract for it exists here.
Diagnostics asymmetry                        The action family has no per-transaction identity-block
                                             diagnostics object like the rating family's
                                             ReconciliationDiagnostics.identityBlockToken; the token
                                             survives as failureCategory / NotRecorded.category plus
                                             aggregate ledger diagnostics (R-21I-06, GATE 21M).
Unwired recovery plan                        actionRecoveryPlan() (like the rating family's
                                             recoveryPlan()) has no production caller yet; recovery
                                             behaviour does not depend on it because begin() already
                                             scans actionLedger.recoveryBlocker(...) (R-21I-05).
Environment                                  No Gradle, no Android SDK, no device/emulator, no CI run:
                                             K2/shim equivalence evidence only, and DI instantiation is
                                             not exercised (same carried limitation as GATE 21G/21H).
```

## §15 Handoff to GATE 21J

See `docs/GATE_21I_REVIEWER_ACTION_ROUTING.md` §7 for the reusable ownership principles. The binding
constraint for the next sub-gate:

> **NoteMutation uses its own GATE 17 contract.** It must not reuse `ReviewerActionStatus` as its
> transaction state machine, must not share `ReviewerActionRoutingPolicy`, `ReviewerActionLedger` or
> this family's recovery pass, and must not be recovered by `actionRecoveryPlan()`. What it should
> reuse is the *pattern*: one pure routing policy per transaction family over the locked 21B/21C/21D
> identity seams, its own `AnkiOperationFacts` answers (note editing has different non-application
> proof and different reconciliation evidence than a card action), the same fail-closed treatment of
> unknown collection identity, and the same stale-callback isolation. The only cross-family coupling
> that exists today is one-way and read-only: `NoteEditSafetyPolicy` consults the reviewer-action
> ledger to refuse an edit overlapping an unresolved action.

## §16 Gate lock decision

```text
GATE 21I:                       LOCKED (harness-verified scope)

ACTION OWNERSHIP:               PASS
EXACT BACKEND ROUTING:          PASS
ACTION SCOPE:                   PASS
COLLECTION SAFETY:              PASS
DURABLE SUBMISSION:             PASS
AMBIGUOUS RECOVERY:             PASS
RETRY SAFETY:                   PASS
RATING MUTUAL EXCLUSION:        PASS
SESSION PROGRESSION:            PASS
ARCHITECTURE AUDIT:             PASS  (AUDIT-21I-01…27, machine-checked where structural)
VERIFICATION:                   PASS  (VER-21I-01…42, 44; VER-21I-43 BLOCKED — no device, no PC backend)
BUILD:                          PASS at the harness scope / BLOCKED for every Gradle task (environmental)

NEXT AUTHORIZED SUB-GATE:       21J — Note Editing Backend Binding
```

```text
GATE 21I — REVIEWER ACTION ROUTING LOCKED

REVIEWER ACTION:                ORIGINAL BACKEND ONLY
BACKEND OWNERSHIP:              DURABLY IMMUTABLE
ACTION TARGET/SCOPE:            BACKEND-QUALIFIED (card scope only; no note/sibling action exists)
AUTO RECOVERY ROUTING:          PROHIBITED
CROSS-BACKEND ACTION REPLAY:    PROHIBITED
AMBIGUOUS ACTION:               NO BLIND RETRY
RETRY_ALLOWED:                  ACTION-SPECIFIC EVIDENCE REQUIRED (frozen ∩ live semantics)
POST-ACTION SESSION EFFECT:     BACKEND-AUTHORITATIVE (nextCardAllowed ∧ APPLIED)
RATING TRANSACTION:             SEPARATE GATE 11 AUTHORITY
REVIEWER ACTION LEDGER:         SINGLE EXISTING GATE 13 OWNER
```

**Post-gate statement.** This session changed no production source: the 21I implementation found in
the tree at `db69d4d` was inspected, executed and audited rather than assumed, and it held. What this
session added is verification and documentation — three missing verifications (VER-21I-04, VER-21I-06,
the VER-21I-15 unknown-assurance cell), corrected VER labelling, the machine-checked PART II audit
(`Gate21iArchitectureAuditTest`, 10 tests, 6 mutants killed across both audit and behavioural halves),
and the two authoritative gate documents. The gate is locked at harness scope; CI must re-run the
Gradle tasks, and real-backend validation (VER-21I-43) remains outstanding for the whole reviewer-action
family exactly as it does for GATE 11 and GATE 13.

Suggested commit message (used): `fix(gate21i): preserve original backend for reviewer actions and recovery`
