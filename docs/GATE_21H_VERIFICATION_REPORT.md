# GATE 21H — Verification Report (PART VII §1–15)

**Gate:** 21H — Review Commit Routing & Recovery (parent: GATE 21 — Multi-Backend Anki Selection /
Hybrid Mode).
**Type:** Implementation → Independent Architecture Audit → Authoritative Verification → Gate Lock.
**Companion:** `docs/GATE_21H_REVIEW_COMMIT_ROUTING.md` (contract + decisions).

**Gate-lock decision: LOCKED** — every mandatory item below passed on the evidence actually
executed; the two carried limitations (no Gradle/device run, DI instantiation not exercised) are
recorded in §12 and are the same limitations GATE 21G carried.

---

## §1 Required initial report (as found, before any 21H change)

| Item | Result |
| --- | --- |
| Repository HEAD | `be8bbd2` (merge of PR #71, shallow clone) |
| Branch | `arena/39363fae-study-agent-client` |
| Worktree status | clean at the start of the gate |
| GATE 11 (review commit) | **IMPLEMENTED** — `ReviewCommit.kt`, `ReviewCommitLedger.kt` (958 l, codec v4 + legacy normalisation), `ReviewCommitTransition.kt`, `ReviewCommitCoordinator.kt`, `ReviewCommitRecovery.kt`, `ReviewCommitReconciliation.kt`, `ReviewCommitRecoveryPolicy.kt`, `DataStoreReviewCommitStore.kt` |
| GATE 21A–21G | **LOCKED** (21G harness-verified; see `docs/GATE_21C..21G_*.md`) |
| GATE 20 | DESIGN_ONLY (no code) |
| Ledger | EXISTS (`ReviewCommitLedger`: `prepare/claim/complete/markRefused/reconcile`, health/unresolved/pendingRecovery/recoveryBlocker) |
| Coordinator | EXISTS (`AnkiStudyEffectExecutor` implements `ReviewCommitCoordinator`) |
| Durable transaction backend identity | EXISTS (`ReviewCommitId(backendId, studySessionId, turnId)`, `ReviewCommitRecord.backendId`, `AnkiBackendContext`) |
| Ambiguous recovery | EXISTS (five-status vocabulary, `SUBMITTING → AMBIGUOUS` on load, closed transition table, `nextCardAllowed ⟺ COMMITTED`) |
| Missing/contradictory normative contract | **none found** — the 21C matrix, the identity contract and the failure matrix cover every cell this gate needed |

## §2 Method and environment (what was actually executed)

- **Builds.** `tools/jvm-harness/bin/build.sh main` → `main errors: 0 (files: 326)`;
  `compose` → `0 errors (files: 24, stub files: 46)`; `test` → `test errors: 0 (files: 244)`.
- **Baseline (before 21H, same day, same environment):** `classes=210 tests=2504 passed=2503
  failed=1 time=39.4s`. The single failure
  (`StudyAudioRouteCoordinatorTest.concurrent device preference and boundary churn stays
  consistent`, `:349`) did **not** reproduce in any later run, including the post-change full suite;
  it is classified **flaky, pre-existing, audio-only** and is not attributed to 21H.
- **After 21H:** full suite `classes=212 tests=2527 passed=2527 failed=0 ignored=0 time=41.9s`;
  focused review-commit sweep (`ReviewCommit|RatingCommit|Gate11|Gate21|Anki`) `classes=112
  tests=1419 passed=1419 failed=0`; new gate classes `classes=2 tests=23 passed=23`.
- **Environment deviations (equivalence evidence only, same as GATE 21G):** Kotlin 2.3 K2 compiler
  (repository: 1.9.24), coroutines 1.10.2, serialization 1.9.0, JUnit/in-memory-DataStore shims, four
  classes excluded from the harness. `./gradlew` is **BLOCKED** in this environment (wrapper TLS
  failure, no Android SDK); no device or CI run was possible. No command in this report is marked
  PASS unless it ran here.

## §3 CHECKPOINT 21H-00…29 — inventory and status

Every checkpoint is reported with the real paths it touched and its result under the executed
evidence. `NO_CHANGE` means the checkpoint was inspected and needed no change; `REUSE` means the
locked prior-gate artifact was used unchanged.

| CP | Subject | Files (real paths) | Status | Evidence |
| --- | --- | --- | --- | --- |
| 21H-00 | Inventory: ledger, coordinator, identity, recovery; contracts | `core/anki/ReviewCommit*.kt`, `core/study/AnkiStudyEffectExecutor.kt`, `docs/BACKEND_*` | REUSE | §1; 21C/21D/21G locked docs |
| 21H-01 | Transaction identity is immutable | `ReviewCommit.kt` (`ReviewCommitId`), `AnkiSessionContext.kt` | NO_CHANGE (already immutable) | `Gate21hReviewCommitRoutingTest` ×4 |
| 21H-02 | Commit routing reads the durable backend only | `core/anki/ReviewCommitRoutingPolicy.kt` (def. of `route`), `AnkiStudyEffectExecutor.commitTransaction` | MODIFY | `a rating bound to A…`, `a preference change before the rating…`, `AUTO eligibility changing…` |
| 21H-03 | No cross-backend replay on outage | `AnkiTransactionRecoveryRouting.kt` | NO_CHANGE | `a removed original backend…`, `one backend's outage…` |
| 21H-04 | Card-id equality is not identity | `ReviewCommit.kt` (`ReviewCommitId`) | NO_CHANGE | `the same numeric card id on two backends…` |
| 21H-05 | Session lock vs effect mismatch | `AnkiStudyEffectExecutor.sessionLockConsistent`, `AnkiBackendStatus.kt` | REUSE | `a commit effect naming another backend…` |
| 21H-06 | Durable intent before any dispatch | `AnkiStudyEffectExecutor.commitTransaction` step 1 | NO_CHANGE | `AnkiRatingCommitFlowTest`, `Gate11bTransactionTest` (re-executed) |
| 21H-07 | Collection mismatch fails closed | `ReviewCommitRoutingPolicy`, `AnkiIdentityAssurance.kt` | MODIFY | `a collection that verifiably changed…` |
| 21H-08 | Degraded key fails closed | `AnkiTransactionRecoveryRouting.kt` (+`COLLECTION_IDENTITY_UNAVAILABLE`), `ReviewCommitRoutingPolicy` | MODIFY | `evidence that degraded…` |
| 21H-09 | Unknown assurance is never a match | `AnkiIdentityAssurance.kt`, `ReviewCommitRoutingPolicy` | REUSE | `an unknown collection on both sides…`, `binding evidence is the admitted collection…` |
| 21H-10 | Backend removed from the build | `AnkiTransactionRecoveryPlanner.route` | REUSE | `a removed original backend…` |
| 21H-11 | Reconnect with a different collection | `AnkiTransactionRecoveryPlanner.route` | MODIFY | `recovery is gated on the original backend returning with the same collection` |
| 21H-12 | Group isolation across backends | `AnkiTransactionRecoveryPlanner.plan` | NO_CHANGE | `one backend's outage never blocks another backend's recovery group` |
| 21H-13 | Ambiguity is preserved through restart | `ReviewCommitLedger` (load-time heal), `ReviewCommitRecoveryPolicy` | NO_CHANGE | `restart preserves ambiguity…` |
| 21H-14 | OP-P recovery pass & lock verdict | `AnkiStudyEffectExecutor.recoveryPlan/restoreSession` | MODIFY | `Gate21gVerificationTest` (re-executed) + §5 |
| 21H-15 | Reconciliation only with authoritative evidence | `AnkiStudyEffectExecutor.reconcileRecord` (OP-M3 gate), `ReviewCommitReconciliation.kt` | MODIFY | `Gate11dRecoveryAuditTest` (all 30+ cases re-executed) |
| 21H-16 | Unsupported reconciliation leaves AMBIGUOUS | `ReviewCommitReconciliation.kt` (locked capability table) | REUSE | `Gate11dAnkiDroidCapabilityTest`, `ver4` of `Gate11dRecoveryAuditTest` |
| 21H-17 | No heuristic inference | `ReviewCommitReconciliation.kt` (evidence is backend-supplied, transaction-correlated) | REUSE | `Gate11dRecoveryAuditTest` (PARTIAL demotion case) |
| 21H-18 | Preference change cannot move an ambiguity | `ReviewCommitRoutingPolicy` | MODIFY | `a preference change cannot move an ambiguous transaction off its backend` |
| 21H-19 | Restart keeps ownership | `ReviewCommitLedgerCodec` (identity in the keys) | REUSE | `restart preserves ambiguity, ownership and the blocked recovery plan` |
| 21H-20 | No retry without proof | `StudyReducer` (`RetryRatingCommit` gate), `ReviewCommitRecoveryPolicy` | REUSE | `an ambiguous transaction is never offered an automatic or accidental retry` |
| 21H-21 | One mutation per logical transaction | `CommitSemantics.kt`, ledger claim/complete | REUSE | `a duplicate execution of the same commit effect…`, `ReviewCommitChaosTest` (re-executed) |
| 21H-22 | Next-card barrier | `AnkiCommitOutcome.allowsNextCard`, `NextCardResult` | REUSE | `Gate11eEnduranceTest`, `Gate11d` (re-executed) |
| 21H-23 | Duplicate/stale rating input | `StudyReducer.selectRating` (`duplicate-anki-rating`, `anki-rating-locked-first-wins`) | REUSE | `repeated and conflicting rating input…` |
| 21H-24 | Unreadable/legacy snapshots | `ReviewCommitLedgerCodec` | NO_CHANGE | `an unreadable ledger routes nothing…`, `a record whose binding collection was dropped…` |
| 21H-25 | Crash windows | `CommitFaultInjection.kt` | REUSE | `Gate21hCommitEnduranceTest` sweep (900 injected crashes), `Version 11E` `unresolvedAt` |
| 21H-26 | Ambiguity survives session end | `ReviewCommitLedger`, `AnkiStudyEffectExecutor.endReview` | NO_CHANGE | `an unresolved transaction survives a session ending…` |
| 21H-27 | Stale callbacks cannot advance a session | `StudyReducer.resolveAnkiCommit` (`stale-anki-commit-result`) | REUSE | `a stale commit result from an ended session…` |
| 21H-28 | 1 000-event endurance | — | MODIFY (new test) | `1000 churn events…`, `1000 injected crashes…` |
| 21H-29 | Documentation & handoff | `docs/GATE_21H_REVIEW_COMMIT_ROUTING.md`, this file | CREATE | §3–§15 |

## §4 File-by-file change report

| File | CREATE / MODIFY / REUSE / NO_CHANGE | Summary |
| --- | --- | --- |
| `app/src/main/java/com/studyagent/client/core/anki/ReviewCommitRoutingPolicy.kt` | **CREATE** | `ReviewCommitFamilyFacts.of()`, `ReviewCommitRecord.mutationOperationClass()`, `ReviewCommitRoute.Routed/Blocked`, `bindingCollectionKey()`, `route(record, operation, boundIsLiveInThisProcess)`, `route(ownerBackendId, key, …)`, `recoveryPlan(records)`; pure, no I/O, no preference/AUTO |
| `app/src/main/java/com/studyagent/client/core/anki/AnkiTransactionRecoveryRouting.kt` | MODIFY (+31/−8) | new `BackendTransactionBlockReason.COLLECTION_IDENTITY_UNAVAILABLE`; `route()` now blocks when a recorded key has no live counterpart (was: "only a *known* pair can disagree") |
| `app/src/main/java/com/studyagent/client/core/study/AnkiStudyEffectExecutor.kt` | MODIFY (+117/−13) | `commitRouting` seam (defaulted, DI-free); commit path routes OP-M1/OP-M2 by durable record before any backend call, blocked ⇒ `markRefused(token)`; `reconcileRecord` judges OP-M3 before the provider (blocked ⇒ zero calls, diagnostics `identityBlockToken`, status stays AMBIGUOUS); `recoveryPlan()`/`restoreSession()` OP-P surface; `sessionBoundHere()` |
| `app/src/test/java/com/studyagent/client/study/AnkiCommitHarness.kt` | MODIFY (+20/−3) | keyed-backend fixture: non-AnkiDroid identities publish their collection key, AnkiDroid publishes none (production reality) |
| `app/src/test/java/com/studyagent/client/study/Gate21hReviewCommitRoutingTest.kt` | **CREATE** | 21 verifications (routing, ownership, identity, ambiguity, duplicate/stale input, legacy bytes) |
| `app/src/test/java/com/studyagent/client/study/Gate21hCommitEnduranceTest.kt` | **CREATE** | 1 000 churn events; 900 injected crashes + 100 controls across every fired fault window |
| `ReviewCommit.kt`, `ReviewCommitLedger.kt`, `ReviewCommitTransition.kt`, `ReviewCommitReconciliation.kt`, `ReviewCommitRecovery.kt`, `ReviewCommitRecoveryPolicy.kt`, `DataStoreReviewCommitStore.kt`, `StudyReducer.kt`, `AppContainer.kt` | NO_CHANGE | Locked artifacts; 21H deliberately added no status, no second ledger, no resolver, no DI requirement |

## §5 AUDIT-21H-01…24 (independent architecture audit)

Each item states the question asked of the code and the verdict with the evidence that settles it.

| ID | Audit question | Verdict | Evidence |
| --- | --- | --- | --- |
| AUDIT-21H-01 | Can `ReviewCommitRecord.backendId` be replaced by the current preference, AUTO, first-available or the visible-Library backend? | PASS | the only routing reads are `record.backendId`/`commitId.backendId`; no code path in `ReviewCommitRoutingPolicy.kt` reads the preference/resolver/AUTO (the only occurrences of those words in the file are documentation comments at lines 10, 22, 116) |
| AUDIT-21H-02 | Is there any path where an A-owned transaction reaches B? | PASS | `recoveryPlan()` groups by persisted identity; commit path uses `registry.find(record.backendId)`; VER-21H-01/24/25 evidence |
| AUDIT-21H-03 | Can a matching card id select a backend? | PASS | identity is `(backendId, studySessionId, turnId)`; VER-21H-05 |
| AUDIT-21H-04 | Is local UUID ever used as an idempotency key? | PASS | `CommitSemantics`/`CommitReceipt`: a locally generated value is never a receipt; AnkiDroid = `AT_MOST_ONCE_FAIL_CLOSED` with `receipt = NONE` |
| AUDIT-21H-05 | Does "no exception + one row" count as success? | PASS | `AnkiDroidRatingCommitter` treats the pinned provider's swallowed scheduler exceptions as *not* proof; the fake mirrors it |
| AUDIT-21H-06 | Is there a blind retry anywhere? | PASS | `RetryRatingCommit` requires `RETRY_ALLOWED` (or a restored `PREPARED`); retry is explicit user intent |
| AUDIT-21H-07 | Can progression happen without a durable `COMMITTED`? | PASS | `allowsNextCard()`; 900-crash sweep asserts `played ≤ durableCommitted` |
| AUDIT-21H-08 | Is a backend success that could not be durably recorded treated as authorization to resubmit? | PASS | `PersistenceFailure` is `SUBMITTING`-shaped, never `RETRY_ALLOWED`; the response is retried as a *write*, not a mutation (`unpersistedResponses`) |
| AUDIT-21H-09 | Was any status added/renamed? | PASS | five-status vocabulary unchanged; `rg 'enum class ReviewCommitStatus'` single definition |
| AUDIT-21H-10 | Is reconciliation ever a heuristic (due/interval/reps/cardId/recent event/reconnect)? | PASS | reconciliation evidence is backend-supplied and transaction-correlated (`ReviewCommitReconciliation`) |
| AUDIT-21H-11 | Does an unsupported reconciliation resolve the record? | PASS | `UNSUPPORTED ⇒ Unresolved`, record stays `AMBIGUOUS` (Gate11d `ver4`) |
| AUDIT-21H-12 | Is a blocked reconciliation issuing provider calls? | PASS | OP-M3 gate precedes the reconciler; a blocked cell records `identityBlockToken` and issues zero calls |
| AUDIT-21H-13 | Is the block token user-visible or status-mutating? | PASS | block token lives in `ReconciliationDiagnostics` only; the durable status is untouched |
| AUDIT-21H-14 | Can a mismatch become a match? | PASS | `AnkiIdentityAssurancePolicy` classifies mismatch/degraded; the matrix blocks every mismatch cell |
| AUDIT-21H-15 | Is session/resource qualification used as transaction evidence? | PASS-with-note | `bindingCollectionKey` = `collectionRef` only (DECISION-21H-02); card/deck keys never construct evidence |
| AUDIT-21H-16 | Does one backend's outage block another backend's group? | PASS | per-group `Blocked` routes; VER-21H-12 |
| AUDIT-21H-17 | Are legacy records with unprovable identity preserved? | PASS | codec keeps the owner in the key; VER-21H-24 |
| AUDIT-21H-18 | Can a legacy record be routed to a guessed backend? | PASS | missing/unresolvable owner ⇒ `BackendTransactionBlockReason.BACKEND_NOT_REGISTERED`; unreadable ⇒ `Health.Unavailable`, empty plan |
| AUDIT-21H-19 | Does process death create a second logical commit? | PASS | `Gate11eEnduranceTest` five-restart matrix (re-executed); crash sweep per window |
| AUDIT-21H-20 | Does ending a session erase an unresolved transaction? | PASS | VER-21H-26 |
| AUDIT-21H-21 | Can a stale session callback advance a new session? | PASS | epoch/session/turn correlation in `resolveAnkiCommit`; VER-21H-27 |
| AUDIT-21H-22 | Can duplicate rating input produce two mutations? | PASS | two reducer rejections + ledger in-flight answer; VER-21H-23/21 |
| AUDIT-21H-23 | Is the user-facing recovery model honest (no "Checking…" promise it cannot keep)? | PASS | `RatingCommitRecoveryUi` offers retry only for provable states and shows verification otherwise |
| AUDIT-21H-24 | Does the gate stay inside its scope (no 21I/21J/21K/22 work, no second architecture)? | PASS | change set in §4; reviewer-action and note families untouched |

**Informational observation (non-blocking):** OBS-21H-01 — `CommitFaultPoint.AFTER_LEDGER_CREATE`
is declared but never fired by any code path (the durable-create window is observed through the
`DURABLE_CREATED` phase sink). It is a test-only seam; no production behaviour depends on it.
Recorded as R-21H-02 for a future maintenance cleanup; it does not invalidate any 21H item.

## §6 VER-21H-01…36 — verification matrix (single source)

All executed in the environment of §2. "Executed" is the test method (all in
`Gate21hReviewCommitRoutingTest` unless another class is named); every item also re-ran inside the
full-suite pass reported in §9.

| VER | Statement under verification | Executed evidence | Result |
| --- | --- | --- | --- |
| VER-21H-01 | A rating admitted on A commits on A; B's commit/next/deck/hydrate/reconcile counters stay 0 | `a rating bound to A commits on A only, with every B counter at zero` | PASS |
| VER-21H-02 | A preference change before the rating cannot move the destination | `a preference change before the rating cannot move the session's destination` | PASS |
| VER-21H-03 | AUTO eligibility changing mid-flight does not reroute | `AUTO eligibility changing while a rating is pending leaves the owner unchanged` | PASS |
| VER-21H-04 | A session locked to A refuses a B-named commit effect before dispatch, creating no durable intent | `a commit effect naming another backend than the session lock is refused before dispatch` | PASS |
| VER-21H-05 | Equal numeric card ids on two backends do not authorize a B mutation | `the same numeric card id on two backends never authorizes a B mutation` | PASS |
| VER-21H-06 | Durable intent precedes dispatch (PREPARED, phase CREATED) | `AnkiRatingCommitFlowTest`, `ReviewCommitDurabilityOrderTest` (re-executed) | PASS |
| VER-21H-07 | Verified collection mismatch refuses the mutation before dispatch (`RETRY_ALLOWED`/`refused_before_dispatch`) | `a collection that verifiably changed refuses the mutation before it is dispatched` | PASS |
| VER-21H-08 | A key that degraded to unknown fails closed | `evidence that degraded from a known collection blocks instead of assuming equality` | PASS |
| VER-21H-09 | Unknown on both sides is not a match and does not authorize `OP-M3` | `an unknown collection on both sides permits a mutation and never proofs a reconciliation` | PASS |
| VER-21H-10 | Binding evidence is the admitted collection, not resource keys | `binding evidence is the admitted collection, never a card or deck resource key` | PASS |
| VER-21H-11 | Ambiguity after preference change stays on its backend and is not reconciled there | `a preference change cannot move an ambiguous transaction off its backend` | PASS |
| VER-21H-12 | Restart preserves ambiguity, ownership and the blocked plan | `restart preserves ambiguity, ownership and the blocked recovery plan` | PASS |
| VER-21H-13 | No automatic/accidental retry of an ambiguity | `an ambiguous transaction is never offered an automatic or accidental retry` | PASS |
| VER-21H-14 | Duplicate execution of one commit effect is not a second mutation | `a duplicate execution of the same commit effect is not a second mutation` | PASS |
| VER-21H-15 | Repeated/conflicting rating input produces exactly one mutation | `repeated and conflicting rating input produces exactly one mutation` | PASS |
| VER-21H-16 | Stale commit results from an ended session cannot advance a new one | `a stale commit result from an ended session cannot advance a new one` | PASS |
| VER-21H-17 | Ending a session preserves an unresolved transaction and its owner | `an unresolved transaction survives a session ending and keeps its owner` | PASS |
| VER-21H-18 | A backend removed from the build blocks its records and lends nothing to the ready backend | `a removed original backend blocks its records and never lends another backend` | PASS |
| VER-21H-19 | Recovery is gated on the original backend returning with the same collection; reconnect is not proof | `recovery is gated on the original backend returning with the same collection` | PASS |
| VER-21H-20 | One backend's outage never blocks another backend's recovery group | `one backend's outage never blocks another backend's recovery group` | PASS |
| VER-21H-21 | An unreadable ledger routes nothing and fabricates no owner | `an unreadable ledger routes nothing and fabricates no owner` | PASS |
| VER-21H-22 | A dropped binding collection keeps the owner and never silently re-owns the record | `a record whose binding collection was dropped keeps its owner and stays unresolved` | PASS |
| VER-21H-23 | Reconciliation is capability-gated: AnkiDroid-class `UNSUPPORTED` issues zero calls and stays AMBIGUOUS | `Gate11dRecoveryAuditTest.ver4…` (re-executed) | PASS |
| VER-21H-24 | `PARTIAL` support cannot confirm a positive observation | `Gate11dRecoveryAuditTest.ver5…` (re-executed) | PASS |
| VER-21H-25 | `PARTIAL` support *can* prove non-application and only then becomes `RETRY_ALLOWED` | `Gate11dRecoveryAuditTest.ver5…negative evidence…` (re-executed) | PASS |
| VER-21H-26 | Reconciliation never demotes to a retry without evidence | `Gate11dRecoveryAuditTest` (all cases, re-executed) | PASS |
| VER-21H-27 | Blind retry is refused from `AMBIGUOUS`/`SUBMITTING` | `Gate11dRecoveryAuditTest`, `Gate11eEnduranceTest` (re-executed) | PASS |
| VER-21H-28 | The next-card barrier holds (no progression without durable `COMMITTED`) | `Gate11eEnduranceTest.a failed next-card read…`, `Gate11e` restart matrix (re-executed) | PASS |
| VER-21H-29 | Restart per durable status never duplicates a mutation (5 restarts × 5 statuses) | `Gate11eEnduranceTest.five restarts…` (re-executed) | PASS |
| VER-21H-30 | Seeded commit chaos (150 seeds × 2 identities) never double-applies nor advances without `COMMITTED` | `ReviewCommitChaosTest` (re-executed) | PASS |
| VER-21H-31 | Ownership survives a 1 000-event preference/availability churn | `1000 churn events never move a commit to the other backend` | PASS |
| VER-21H-32 | A death before the intent leaves nothing | crash sweep, `BEFORE_LEDGER_CREATE` (100 events) | PASS |
| VER-21H-33 | A death after the intent but before the boundary leaves `PREPARED` (provably un-entered) | crash sweep, `AFTER_PREPARED` (100 events) | PASS |
| VER-21H-34 | Any death at/after the boundary leaves `AMBIGUOUS`, never a retry or a fabricated commit | crash sweep, `AFTER_CALL_ENTERED`/`BEFORE_PROVIDER_CALL`/`AFTER_PROVIDER_MUTATION`/`BEFORE_RESPONSE_PERSIST` (400 events) | PASS |
| VER-21H-35 | A durable confirmed response is terminal even if the final write is lost | crash sweep, `AFTER_RESPONSE_PERSIST`/`BEFORE_COMMITTED_PERSIST`/`AFTER_COMMITTED_PERSIST` (300 events) | PASS |
| VER-21H-36 | 100 clean controls commit exactly once (the sweep is not vacuous) | crash sweep controls (100 events) | PASS |

## §7 INV-21H-01…30 — invariant report

| INV | Invariant | Where enforced | Result |
| --- | --- | --- | --- |
| INV-21H-01 | A transaction's destination is the backend in its durable identity | `ReviewCommitRoutingPolicy.route`, `commitTransaction` | HELD |
| INV-21H-02 | No mutation is ever dispatched to a backend that is not the record's owner | commit path + `sessionLockConsistent` | HELD |
| INV-21H-03 | The global preference is never read on the commit/reconcile/recovery path | routing policy (no reference) | HELD |
| INV-21H-04 | AUTO is never consulted for a durable transaction | routing policy (no reference) | HELD |
| INV-21H-05 | A matching card id is never identity | `ReviewCommitId` | HELD |
| INV-21H-06 | Unknown collection evidence is never treated as equality | `AnkiIdentityAssurancePolicy`, matrix | HELD |
| INV-21H-07 | A recorded key that is unknown now fails closed | planner + policy | HELD |
| INV-21H-08 | Resource qualification never constructs binding evidence | `bindingCollectionKey` | HELD |
| INV-21H-09 | Blocked operations issue zero backend calls | commit path; `reconcileRecord` gate | HELD |
| INV-21H-10 | Block reasons are stable, content-free tokens | `ReviewCommitRoute.Blocked.token` | HELD |
| INV-21H-11 | Diagnostics never decide a transaction state | `ReconciliationDiagnostics` | HELD |
| INV-21H-12 | The five-status vocabulary is closed | `ReviewCommitStatus` | HELD |
| INV-21H-13 | `AMBIGUOUS` is never rewritten to `PREPARED`/`RETRY_ALLOWED` without evidence | `ReviewCommitTransition` | HELD |
| INV-21H-14 | Reconciliation evidence must be backend-supplied and correlated | `ReviewCommitReconciliation` | HELD |
| INV-21H-15 | An unsupported read resolves nothing | `ReviewCommitReconciliation` | HELD |
| INV-21H-16 | A retry requires a provable not-applied state | `StudyReducer`, `ReviewCommitRecoveryPolicy` | HELD |
| INV-21H-17 | The retry target is the same commit id/rating | `StudyReducer` (`anki-retry-not-safe`) | HELD |
| INV-21H-18 | No progression without durable `COMMITTED` | `allowsNextCard()` | HELD |
| INV-21H-19 | A successful response that is not durable never becomes `COMMITTED` in the projection | `unpersistedResponses`, `PersistenceFailure` | HELD |
| INV-21H-20 | One logical transaction ⇒ at most one physical application | ledger claim + semantics; chaos + sweep | HELD |
| INV-21H-21 | Process death never creates a second logical commit | `ReviewCommitId` in the durable keys | HELD |
| INV-21H-22 | Restart is a read, never a submission | recovery table | HELD |
| INV-21H-23 | A restored `SUBMITTING`/`AMBIGUOUS` offers no retry | `RatingCommitRecoveryUi`, reducer | HELD |
| INV-21H-24 | Unresolved records are never deleted by recovery | ledger APIs (no delete) | HELD |
| INV-21H-25 | Records of one owner are never migrated to another | planner + codec | HELD |
| INV-21H-26 | Unreadable bytes block only their own recovery | `Health.Unavailable`, empty plan | HELD |
| INV-21H-27 | Blocking is per backend group | `AnkiTransactionRecoveryPlanner.plan` | HELD |
| INV-21H-28 | Ending a session does not erase unresolved transaction truth | `endReview` (releases handles only) | HELD |
| INV-21H-29 | Stale callbacks cannot advance a session | epoch/session/turn correlation | HELD |
| INV-21H-30 | Duplicate rating input cannot produce a second mutation | reducer rejections + in-flight answer | HELD |

## §8 Fault-injection results (1 000 events)

Sweep: 9 fired `CommitFaultPoint` windows × 100 injected crashes = 900, plus 100 clean controls.
Observed durable status after death, per window — exactly the state the process left behind:

| Window | Durable status after death | After restart (ledger view) | Mutations |
| --- | --- | --- | --- |
| `BEFORE_LEDGER_CREATE` | no row | — | 0 |
| `AFTER_PREPARED` | `PREPARED` | `PREPARED` (retry legal, still explicit) | 0 |
| `AFTER_CALL_ENTERED` | `SUBMITTING` | `AMBIGUOUS` | 1 |
| `BEFORE_PROVIDER_CALL` | `SUBMITTING` | `AMBIGUOUS` | 1 |
| `AFTER_PROVIDER_MUTATION` | `SUBMITTING` | `AMBIGUOUS` | 1 |
| `BEFORE_RESPONSE_PERSIST` | `SUBMITTING` | `AMBIGUOUS` | 1 |
| `AFTER_RESPONSE_PERSIST` | `COMMITTED` (response is proof) | `COMMITTED` | 1 |
| `BEFORE_COMMITTED_PERSIST` | `COMMITTED` (response is proof) | `COMMITTED` | 1 |
| `AFTER_COMMITTED_PERSIST` | `COMMITTED` | `COMMITTED` | 1 |

Across all 1 000 events: zero double applications, zero replacement of one owner's records by
another backend, zero progressions without a durable `COMMITTED`, zero substitute-backend calls.

## §9 Build & regression results

| Run | Command | Result |
| --- | --- | --- |
| main | `tools/jvm-harness/bin/build.sh main` | PASS — 326 files, 0 errors |
| compose | `tools/jvm-harness/bin/build.sh compose` | PASS — 24 files, 0 errors |
| test | `tools/jvm-harness/bin/build.sh test` | PASS — 244 files, 0 errors |
| full suite | `bin/run.sh` | PASS — `classes=212 tests=2527 passed=2527 failed=0` (41.9 s) |
| review-commit sweep | `bin/run.sh 'ReviewCommit\|RatingCommit\|Gate11\|Gate21\|Anki'` | PASS — `classes=112 tests=1419 passed=1419` |
| 21H classes | `bin/run.sh 'Gate21h'` | PASS — `classes=2 tests=23 passed=23` |
| baseline for comparison | same environment, before 21H | 1 pre-existing flaky failure (audio), not reproduced |

**Environment limitation carried:** `./gradlew` cannot run here (wrapper TLS failure, no Android
SDK), so no Gradle/CI/device run is claimed. Harness results are equivalence evidence; the same
limitation is recorded in the GATE 21G report.

## §10 Requirements traceability (highlights)

| Requirement | Where satisfied | Evidence |
| --- | --- | --- |
| `backendId` is the immutable destination | §6 VER-21H-01/02/03/04/05 | routing policy + tests |
| Zero mutation calls on B when A is down and B is ready | VER-21H-01, VER-21H-18/20 | counters in every test |
| Timeout/disconnect/death ⇒ possibly applied, no blind retry | VER-21H-13/27, §8 | recovery table + sweep |
| No exactly-once claim without documented idempotency | AUDIT-21H-04 | `CommitSemantics` |
| Local UUID ≠ idempotency key | AUDIT-21H-04 | `CommitReceipt` |
| Progression only after durable `COMMITTED` | VER-21H-28 | `allowsNextCard()` |
| Backend success not durably recorded ⇒ no resubmission | AUDIT-21H-08 | `PersistenceFailure` path |
| Five-status vocabulary preserved | AUDIT-21H-09 | single enum |
| Reconciliation only via the original backend with authoritative evidence | VER-21H-23/24/25 | capability gate + OP-M3 |
| Unsupported reconciliation stays `AMBIGUOUS` | VER-21H-23 | Gate11d |
| No heuristic inference | AUDIT-21H-10 | evidence contract |
| Ownership survives crash/restart; groups isolated | VER-21H-12/18/19/20 | planner + tests |
| Legacy records preserved but blocked | VER-21H-21/22 | codec + planner |
| Duplicate rating input cannot double-mutate | VER-21H-15/14 | reducer + ledger |
| Stale callbacks cannot advance a new session | VER-21H-16 | correlation |

## §11 Out-of-scope confirmation (must NOT be implemented here)

No new ReviewCommit architecture, no second ledger/coordinator/resolver, no new rating state
machine, no local FSRS, no cross-backend card mapping/transfer, no AUTO routing for recovery, no
offline mutation replay queue, no ReviewerAction recovery redesign (GATE 21I), no note edit/create
recovery (21J/21K), no backend-selection UI. The change set in §4 is the complete footprint.

## §12 Residual risks

- **R-21H-01** No binding evidence is not a match (records without `collectionRef` cannot be
  blocked by collection mismatch). Unknown never authorizes a match; revisit if a production writer
  ever omits the context collection.
- **R-21H-02 / OBS-21H-01** `CommitFaultPoint.AFTER_LEDGER_CREATE` declared but unfired (test-only
  seam; window observed via the `DURABLE_CREATED` phase sink).
- **R-21H-03** `recoveryPlan()` judges a backend group by the first record's binding key;
  per-record routing remains authoritative at commit/reconcile time.
- **R-21H-04** No Gradle/device/CI execution in this environment (harness equivalence only).
- **R-21H-05** DI instantiation path (`AppContainer`) not executed here; every new member is
  defaulted so no call site changes.

## §13 GATE 21I handoff

- Copy the pattern, not the code: durable record → §3.5 assurance → §3.3 verdict → routed/blocked →
  decision before any provider call → block token in diagnostics only.
- Evaluate reviewer actions' **own** `AnkiOperationFacts`; do not reuse `ReviewCommitFamilyFacts`.
- Reuse `AnkiTransactionRecoveryPlanner` for identity grouping (`groupByOwnerBackend`) — grouping by
  persisted identity is family-independent; the *verdict* is family-specific.
- The reviewer-action family already has its own capability contract
  (`ReviewerActionCapabilities`), its own ledger and its own tests; GATE 21I owns making its
  recovery identity-aware, and must re-run `Gate21h*`, `Gate11*` and the reviewer-action suites
  before locking.

## §14 Gate-lock decision

**GATE 21H: LOCKED.**

- All 24 audit items PASS (one PASS-with-note on DECISION-21H-02; one informational observation
  OBS-21H-01 that changes no behaviour).
- All 36 VER items PASS on executed evidence; all 30 invariants HELD.
- Full suite 2527/2527, review-commit sweep 1419/1419, new gate classes 23/23, 1 000-event fault
  injection with zero violations.
- The two carried limitations (no Gradle/device run; DI path not exercised) are recorded in §12 and
  are identical in kind to the limitation GATE 21G locked with.

## §15 Post-gate statement

GATE 21H is complete and locked; **GATE 21I is not started** by this gate. The 21H change set,
this report and the companion contract document are the complete deliverable set. Suggested commit
message for the change set (single logical change):

```text
fix(gate21h): bind Anki review commits and recovery to the original backend
```
