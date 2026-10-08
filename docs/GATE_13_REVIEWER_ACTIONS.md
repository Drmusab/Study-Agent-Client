# GATE 13 — Reviewer Actions (Flag / Bury / Suspend): Durable Action Transactions

**Repository:** Drmusab/Study-Agent-Client · **Branch:** `arena/cc71d67b-study-agent-client`
**Base commit:** `f0a2eec` (merge of PR #46 — the *uncompiled* GATE 13 work-in-progress)
**Counterpart:** AnkiDroid (ankdroid/Anki-Android), pinned public provider contract v2.24.1
**Revision:** final for this branch, including the wiring pass that §11.2/§11.3 previously listed as
gaps (Compose menu mounted, app session-start capability freeze, UI regression locks).

---

## 0. Verdict

```text
GATE 13 (durable reviewer-action model):        IMPLEMENTED AND WIRED
Build:                                          the app module COMPILES (was broken at f0a2eec)
JVM unit + architecture suites:                 1599 tests / 139 classes — 0 failures
Real AnkiDroid device mutation (VERIFICATION 14): NOT RUN (no device/emulator in this sandbox;
                                                  CI jobs remain billing-locked)
Compose card-action menu + app session-start wiring: WIRED (§3, §11.2-§11.3)
```

**Baseline repair is part of this gate.** At `f0a2eec` the module did **not** compile: 18 Kotlin
errors from the merged PR #46 (an `internal` type in a public constructor, a missing `return`, a
missing `endReview` interface member, two missing imports, five extracted reducer helpers calling
local functions that no longer exist, and UI models referencing a state model that does not exist).
Those are fixed here, in the same files this gate rewrites (§11.1).

---

## 1. Purpose & scope

Reviewer actions are the small family of *non-rating* card mutations a reviewer performs mid-review:

```text
SetFlag     — card metadata only        (never rates, never advances the scheduler)
BuryCard    — leaves today's queues      turn-invalidating
SuspendCard — leaves reviews entirely    turn-invalidating
```

They are **not** ratings and never become ratings: no `ReviewCommitStatus` is written, no review
history is fabricated, and no scheduler query is derived from a guess. What they *are* is a second
family of irreversible backend mutations, which is why this gate gives them their own durable
transaction — the same discipline GATE 11 applied to ratings, in a separate ledger.

---

## 2. The normative action-state model

```text
                no action record
                       │ PrepareAction
                       ▼
                   PREPARED ───────────── EnterMutationBoundary
                    ▲                            │
       RetryRequested│                            ▼
                    │                       SUBMITTING
                    │                    /      |       \
                    │                   ▼       ▼        ▼
              RETRY_ALLOWED         APPLIED  RETRY_   AMBIGUOUS
                    ▲             (terminal) ALLOWED       │
                    │                                     │ reconciliation
                    └─────────────────────────────────────┤
                                        APPLIED ◄─────────┘
```

| Status | Meaning (durable truth) |
|---|---|
| `PREPARED` | Action intent is durably recorded, the mutation boundary has **not** been crossed ⇒ backend effect known absent, safe retry |
| `SUBMITTING` | The boundary **has** been crossed ⇒ the action may already have been applied; never retried automatically |
| `APPLIED` | Backend success confirmed **and durably recorded**; terminal (a new user action needs a new id) |
| `RETRY_ALLOWED` | Authoritative evidence that the action was **not** applied ⇒ the same logical action may be resubmitted |
| `AMBIGUOUS` | It may or may not have been applied and the system cannot prove which ⇒ no blind retry |

**Backend evidence → status is fixed, with no alternative mapping:**

| `ReviewerActionBackendResult` | Durable status |
|---|---|
| `ConfirmedApplied(receipt)` | `APPLIED` |
| `ConfirmedNotApplied(reason)` | `RETRY_ALLOWED` |
| `OutcomeUnknown(reason)` | `AMBIGUOUS` |

The mapping is one function (`toStatus()` / `toTransition()`), and the ledger only ever applies the
command that function produces.

**The fresh-next-card rule (§22):**

```kotlin
fun nextCardAllowed(action: ReviewerAction, status: ReviewerActionStatus): Boolean =
    action.invalidatesCurrentTurn && status == ReviewerActionStatus.APPLIED
```

`invalidatesCurrentTurn` is a property of the action — `false` for `SetFlag`, `true` for
`BuryCard`/`SuspendCard` — so no `when (action)` may be scattered through the UI.

---

## 3. Layers, files and vocabularies

| Layer | Type | File |
|---|---|---|
| study workflow | `SessionPhase` (`WaitingForRating`, `ReviewerActionRecoveryRequired`, …) | `core/study/SessionPhase.kt` |
| durable action transaction truth | `ReviewerActionStatus` | `core/anki/ReviewerActionStatus.kt` |
| action family + capabilities + semantics | `ReviewerAction`, `ReviewerActionCapabilities`, `ReviewerActionSemantics` | `core/anki/ReviewerAction.kt` |
| durable record + identity + request | `ReviewerActionRecord`, `ReviewerActionId`, `ReviewerActionRequest` | `core/anki/ReviewerActionRecord.kt` |
| backend evidence | `ReviewerActionBackendResult`, `ReviewerActionReceipt` | `core/anki/ReviewerActionBackendResult.kt` |
| transaction orchestrator | `ReviewerActionCoordinator`, `DefaultReviewerActionCoordinator` | `core/anki/ReviewerActionCoordinator.kt` |
| durable ledger + codec | `ReviewerActionLedger`, `DurableReviewerActionLedger`, `ReviewerActionLedgerCodec` | `core/anki/ReviewerActionLedger.kt` |
| recovery + reconciliation | `ReviewerActionRecoveryAction`, `reviewerActionRecoveryTransition`, `AnkiReviewerActionReconciler` | `core/anki/ReviewerActionRecovery.kt` |
| admission policy | `ReviewerActionPolicy` | `core/anki/ReviewerActionPolicy.kt` |
| presentation projection | `ReviewerActionUiState`, `ReviewerActionRefusal` | `core/study/ReviewerActionUiState.kt` |
| study integration | `AnkiReviewerAction`, `AnkiReviewerActionOutcome`, events/effects | `core/study/AnkiStudyInteraction.kt`, `StudyReducer.kt`, `AnkiStudyEffectExecutor.kt` |
| persistence | `DataStoreReviewerActionStore` | `data/anki/DataStoreReviewerActionStore.kt` |
| AnkiDroid write path | protocol + gateway | `data/anki/ankidroid/AnkiDroidReviewerAction{Committer,Gateway}.kt` |
| UI copy/projection | `ReviewerActionCopy`, `ReviewerActionMenuUi` | `ui/screens/study/ReviewerActionUiModels.kt` |
| mounted card-action menu (renders the projection, dispatches intents only) | `ReviewerActionMenu` | `ui/screens/study/ReviewerActionMenu.kt` (+ `AnkiAnswerReviewSection.kt`, `StudyScreen.kt`, `StudyViewModel.kt`) |
| app session-start capability freeze | `AnkiLocalStudyStarter` (+ container wiring, dashboard offer) | `data/repository/AnkiLocalStudyStarter.kt`, `di/AppContainer.kt`, `ui/screens/home/{DashboardUiState,HomeViewModel,HomeScreen}.kt` |

### 3.1 Deviations from the specification's literal names (with reasons)

| Specification | Here | Why |
|---|---|---|
| `StudySessionId` | `sessionId: String` | The study session id is a `String` everywhere (`AnkiStudyRequest.studySessionId`, `ReviewCommitId.studySessionId`). A wrapper would be a second identity for one thing. |
| `AnkiCollectionRef` | `AnkiCollectionIdentity?` | The existing collection identity type; a synonym would fork collection identity (`INV-13-16` keeps one identity per thing). |
| `ReviewerAction.Bury` / `Suspend` | `BuryCard` / `SuspendCard` | Spec §3 names. |
| `ReviewerActionLedger.create/transition` returning nothing / the record | `ReviewerActionLedgerWrite` (sealed; success cases carry the record) | §17/`INV-13-08` forbid entering the backend mutation before `SUBMITTING` is durable. A caller that cannot distinguish "refused" from "not persisted" cannot fail closed. |
| `ReviewerActionLedger` interface exactly as listed | plus `health()` and `recoveryBlocker()` | Both are recovery queries (§26/§30) and belong to the ledger's contract, not to a free-standing helper. No unrestricted `save`/`updateStatus` was added — asserted by test. |
| `ReviewerActionSemantics` (domain) | kept; the UI copy object was renamed to `ReviewerActionCopy` | Two different things may not share one name (AUDIT 1). |

---

## 4. The closed transition engine

`ReviewerActionTransitions.transition(record, command, now)` is the only way a record's status may
change. Legal rows (§11 plus the §26 recovery rows and the §17 boundary-violation rows):

| Current | Command | Next |
|---|---|---|
| no record | `PrepareAction` (`ledger.create` + `validateInitial`) | `PREPARED` |
| `PREPARED` | `EnterMutationBoundary` | `SUBMITTING` |
| `PREPARED` | `BackendConfirmedNotApplied` / `BackendOutcomeUnknown` | `RETRY_ALLOWED` / `AMBIGUOUS` (pre-entry resolution; see below) |
| `SUBMITTING` | `BackendConfirmedApplied` | `APPLIED` |
| `SUBMITTING` | `BackendConfirmedNotApplied` | `RETRY_ALLOWED` |
| `SUBMITTING` | `BackendOutcomeUnknown` | `AMBIGUOUS` |
| `RETRY_ALLOWED` | `RetryRequested` | `PREPARED` (same `ReviewerActionId`) |
| `AMBIGUOUS` | `ReconciliationConfirmedApplied` | `APPLIED` |
| `AMBIGUOUS` | `ReconciliationConfirmedNotApplied` | `RETRY_ALLOWED` |
| `AMBIGUOUS` | `ReconciliationUnresolved` | `AMBIGUOUS` (only the reason is refreshed) |
| `SUBMITTING` | `ReconciliationConfirmedApplied/NotApplied/Unresolved` | `APPLIED` / `RETRY_ALLOWED` / `AMBIGUOUS` (§26: reconcile a recovered `SUBMITTING`; normalize it if unresolved) |
| `APPLIED` | *any mutation command* | **rejected** (`APPLIED_TERMINAL`) |

Two rows deserve their rationale in the open:

* **Pre-entry resolution** (`PREPARED → RETRY_ALLOWED`/`AMBIGUOUS`). §17 requires `SUBMITTING` to be
  durable before the mutation. If the backend *refuses before* the boundary callback (a pre-dispatch
  refusal) non-application is proven, so the record is resolved to `RETRY_ALLOWED` rather than left
  looking un-entered. If a backend answers `Applied`/`Unknown` **without ever asking to cross the
  boundary**, that is a §17 violation: the coordinator first tries to write the boundary, and only if
  even that fails does it record explicit uncertainty (`PREPARED → AMBIGUOUS`) — because `PREPARED`
  would claim the mutation window never opened, which is the one lie that makes a later read
  unsound. GATE 11 made the same choice for its boundary-violation row.
* **`SUBMITTING` reconciliation rows** are §26, not an extension of §11: a record found in
  `SUBMITTING` after process death has crossed the boundary (or may have), so recovery must
  reconcile it, and an unresolved reconciliation normalizes it to `AMBIGUOUS` (§26/§30).

Every other pair is rejected *before* a storage write, and `validWrite` re-checks the per-status
invariants (attempt counters, window stamps, receipt ownership, identity immutability).

### 4.1 Recovery table (`reviewerActionRecoveryTransition`, declared once)

| Current | RecoveryDetected | RetryRequested | ReconciliationStarted | ConfirmedApplied | ConfirmedNotApplied | Unresolved | IntegrityViolation |
|---|---|---|---|---|---|---|---|
| `PREPARED` | `PREPARED` | `PREPARED` | – | – | – | – | `PREPARED` |
| `SUBMITTING` | `SUBMITTING` | – | `SUBMITTING` | `APPLIED` | `RETRY_ALLOWED` | `AMBIGUOUS` | `SUBMITTING` |
| `RETRY_ALLOWED` | `RETRY_ALLOWED` | `PREPARED` | – | – | – | – | `RETRY_ALLOWED` |
| `AMBIGUOUS` | `AMBIGUOUS` | – | `AMBIGUOUS` | `APPLIED` | `RETRY_ALLOWED` | `AMBIGUOUS` | `AMBIGUOUS` |
| `APPLIED` | `APPLIED` | – | `APPLIED` | `APPLIED` | – | `APPLIED` | `APPLIED` |

`APPLIED` is terminal: no event rewrites it, and the two events that would (`RetryRequested`,
`ReconciliationConfirmedNotApplied`) are rejected outright. Restart is deliberately **not** an event
of this table — restart alone never rewrites truth.

---

## 5. The coordinator: ordering, exclusion, and fail-closed defaults

`DefaultReviewerActionCoordinator` is the only transaction orchestrator (§16). `perform()` runs
exactly §17's order:

```text
validate (request integrity + backend contract + live capability)
  ↓ derive ReviewerActionId  (deterministic, never random)
  ↓ ledger.create             → durable PREPARED
  ↓ backend.performReviewerAction(card, action, mutationEntry)
        … the backend's read-only preflight happens here, still PREPARED …
        mutationEntry → ledger.transition(PREPARED, EnterMutationBoundary)  → durable SUBMITTING
        … one real backend mutation …
  ↓ ledger.transition(SUBMITTING, classified answer) → durable final status
  ↓ return the durable outcome (the executor turns it into a StudyEvent)
```

Consequences that the tests pin:

* **No ledger, no mutation.** Without a durable action ledger every action is refused before
  dispatch (`ActionLedgerUnavailable`); a `SUBMITTING` write that cannot be made durable refuses the
  backend the mutation (the fake asserts zero boundary crossings).
* **One logical action per turn.** `ReviewerActionId` is derived from
  `(backend, session, turn, action)`, so 100 concurrent identical requests collide on one id: one
  record, at most one backend mutation.
* **Mutual exclusion below the UI (both directions).** Before preparing an action the coordinator
  asserts there is no rating commit for the turn (any status — `COMMITTED` means the turn is already
  resolved). Before preparing a rating the executor asserts this turn has no unresolved action.
* **No replay, ever, by itself.** A `SUBMITTING`/`APPLIED`/`AMBIGUOUS` record is answered from the
  ledger; only an explicit `retry()` (from `RETRY_ALLOWED` or a restored `PREPARED`) or `recover()`
  (read-only) may move it.

### 5.1 AnkiDroid backend facts behind the semantics (§28)

| Action | Expressibility at the pin | Idempotent replay | Authoritative reconciliation |
|---|---|---|---|
| Flag | **none** — no flag column exists | n/a | n/a |
| Bury | `ReviewInfo.BURY = "buried"`, card-scoped | yes (the committer checks the card's queue *before* dispatching; a second identical action writes nothing) | yes (immediate read-only card-state read) |
| Suspend | `ReviewInfo.SUSPEND = "suspended"`, card-scoped | yes (same desired-state check) | yes (same read) |

Failure semantics are per action and audited in `AnkiDroidReviewerActionCommitter`: a returned row
count is never success (the provider swallows scheduler exceptions), a refusal before IPC is
provably-not-applied, and an unattributable post-mutation state is `OutcomeUnknown`. Reconciliation
only claims `ConfirmedApplied` when the card is observed in the desired state — the pinned semantics
are day-scoped (a bury expires at rollover, a suspend can be undone in AnkiDroid), so "not currently
buried" cannot prove "was never buried".

---

## 6. PART II — Architecture audit (AUDIT 1-8)

| Audit | Result | Where it is enforced |
|---|---|---|
| **1. One action-state model** | **PASS** — `ReviewerActionStatus` is the only durable action state; the UI's `Idle/Saving/RetryAvailable/VerificationRequired` is derived in one function (`ReviewerActionUiState.from`) and a policy refusal is a separate, non-transactional `ReviewerActionRefusal`. No `Applying`/`Failed` business state exists. | `Gate13ArchitectureAuditTest.AUDIT 1 …` |
| **2. Rating separation** | **PASS** — separate statuses, separate ids, separate stores, separate files. The action ledger/status/record/backend-result never name `ReviewCommitStatus`; the *policy* reads it deliberately (§23's table is defined in terms of the rating transaction); no action file calls `commitRating`/`ReviewCommitLedger`. | `AUDIT 2 …` |
| **3. One mutation entry point** | **PASS** — the only production callers of `performReviewerAction` are the coordinator (caller) and the AnkiDroid backend (implementation); the executor is the only caller of the coordinator; the action effect is produced only by the reducer and consumed only by the machine/executor; no UI file references the gateway, the backend or the coordinator; the repository's intent is deliberately named `requestReviewerAction` so the mutation name stays exclusive. | `AUDIT 3 …`, `AUDIT 3 and INV-13-18 …` |
| **4. Durable ordering** | **PASS** — proven behaviourally (the store is inspected *inside* the boundary callback: it already says `SUBMITTING`) and structurally (PREPARED → boundary-callback write → final status). | `ReviewerActionCoordinatorTest.durable ordering …`, `AUDIT 4 …` |
| **5. Turn progression** | **PASS** — a flag keeps the turn (same turn id, card, phase, flag projected); bury/suspend close it and emit exactly one `AnkiStudyEffect.Next`, gated by the shared §22 rule. | `ReviewerActionMachineTest …`, `AUDIT 5 …` |
| **6. Rating/action exclusion** | **PASS** — enforced in the coordinator (rating ledger consulted) *and* in the rating pipeline (action ledger consulted), never by a disabled button. | `AUDIT 6 …`, `ReviewerActionCoordinatorTest.a rating transaction …`, `ReviewerActionMachineTest.a rating transaction blocks …` |
| **7. Automatic retry** | **PASS** — the retry command is named only by the state machine, the recovery table, the coordinator and the ledger's counter; retry is reached only from an explicit user event; no backoff/loop/replay machinery exists anywhere in the family. | `AUDIT 7 …` |
| **8. Recovery** | **PASS** — an unfinished action survives restart (separate store, no corruption reset, unresolved records never evicted), `beginReview`/`nextCard` are blocked by a recovery scan *before any scheduler query*, and an unreadable ledger is fail-closed, never "empty". | `AUDIT 8 …`, `ReviewerActionMachineTest.an unfinished action blocks startup …` |

---

## 7. PART III — Verification (1-14)

| # | Verification | Evidence |
|---|---|---|
| 1 | Legal transition matrix | `ReviewerActionTransitionTest` (19 tests: every legal row, retry identity reuse, attempt counter, windows) |
| 2 | Illegal transitions rejected | `ReviewerActionTransitionTest`: `APPLIED` terminal for all 8 commands, `AMBIGUOUS → PREPARED/SUBMITTING` rejected, `RETRY_ALLOWED → SUBMITTING` rejected, `PREPARED → APPLIED` rejected, foreign receipts refused |
| 3 | Flag success | `ReviewerActionMachineTest.a confirmed flag keeps the turn …` — status `APPLIED`, same turn, `nextCard = 0` (effect count 0), rating ledger untouched |
| 4 | Bury success | `…a confirmed bury closes the turn and asks for a fresh card exactly once` — `APPLIED`, turn invalidated, exactly one fresh `nextCard` query, no rating commit |
| 5 | Suspend success | `…a confirmed suspend behaves exactly like bury` |
| 6 | Bury response loss | `ReviewerActionCoordinatorTest.a lost bury response is ambiguous and never replayed`, `ReviewerActionMachineTest.a lost bury response blocks progression and never replays` — `AMBIGUOUS`, automatic retry 0, `nextCard = 0` |
| 7 | Suspend response loss | `…a lost suspend response is ambiguous too` |
| 8 | Flag response loss | `…a lost flag response is ambiguous because the backend claims no reconciliation` — `AMBIGUOUS`; recovery stays blocked (no verified reconciliation for that capability) |
| 9 | Restart from `SUBMITTING` | `…restarting from submitting replays nothing and requires reconciliation` + `ReviewerActionMachineTest.an unfinished action blocks startup before any scheduler query` — no replay, no card, reconciliation required |
| 10 | Restart from `APPLIED` | `…restarting from applied never replays and answers from the ledger` (bury/suspend resume through a fresh read-only session; flag restores the projection) |
| 11 | Rating/action race | `ReviewerActionCoordinatorTest.a rating transaction for the turn blocks a reviewer action` + `ReviewerActionMachineTest.a rating transaction blocks a reviewer action in both directions` — exactly one family acquires the slot |
| 12 | Bury/suspend race | `…two different actions for one turn never both reach the backend` and `…concurrent different actions leave exactly one logical action behind` |
| 13 | Duplicate bury ×100 | `…one hundred concurrent identical requests are one logical action and at most one mutation` — 1 id, 1 record, ≤1 backend mutation, 1 boundary crossing |
| 14 | Real AnkiDroid | **NOT RUN** — no AnkiDroid/device/emulator in this sandbox and CI jobs are billing-locked, exactly as GATE 11's report records for its own device matrix. Procedure unchanged: `androidTest/.../AnkiDroidDisposableCommitInstrumentedTest`-style disposable collection, now extended in spirit by `AnkiDroidReviewSessionTest`-style session setup; a reviewer-action instrumented test is **not** added this pass (it could not be compiled or run here, and an unverifiable test is worse than an honest gap). |

Structural evidence for the whole family: `Gate13ArchitectureAuditTest` (17 tests: AUDIT 1-8, the
INV-13 pack, the UI projection-only scan, the menu-mount/intent path, and the session-start freeze
lock) plus the AnkiDroid isolation suite that now lists the reviewer-action gateway as the second
sanctioned writer (and keeps `buried`/`suspended` owned by the pinned contract module only).

Behavioural evidence for the wiring pass: `AnkiLocalStudyStarterTest` (6 tests — the frozen set equals
the backend's declared set, the freeze is a snapshot not a live view, no-ready-backend/deck/agent-only
refusals dispatch nothing, readiness follows the on-device backend), `ReviewerActionMenuUiTest`
(9 tests — pure projection: hidden/disabled rules, the flag chooser never offers `UNKNOWN`, retry only
after proven non-application, verify only for an unproven outcome), `ReviewerActionUiProjectionTest`
(4 tests — the real reducer + executor + coordinator projected through `AnswerReviewModel.from`), and
`DashboardUiMapperTest` (+3 tests — the local offer appears only when disconnected, ready and idle).

---

## 8. Invariants (`INV-13-01` … `INV-13-20`)

| Invariant | Enforced by |
|---|---|
| 01 `ReviewerActionStatus` is the only durable action status | the enum is the only action status type; audited |
| 02 `ReviewerActionLedger` is the action transaction truth | separate store + codec; the reducer's projection is corrected by durable outcomes |
| 03 `ReviewCommitLedger` remains rating truth | untouched; audited (no action vocabulary in it) |
| 04 Actions never create `ReviewCommitStatus` | coordinator/executor never call the commit pipeline; audited |
| 05 Ratings never create `ReviewerActionStatus` | the commit path only *reads* the action ledger (exclusion); audited |
| 06 One active action per turn | `DurableReviewerActionLedger.create` + the coordinator's in-flight set; 100-request test |
| 07 Rating/action mutations are mutually exclusive | both directions, in orchestration |
| 08 No backend mutation before durable `SUBMITTING` | boundary callback + `AnkiBackend.performReviewerAction(…, mutationEntry)`; ordering test |
| 09 `APPLIED` is terminal | transition engine + recovery table + ledger; tests |
| 10 `AMBIGUOUS` cannot retry directly | engine rows; tests |
| 11 `RETRY_ALLOWED` retry reuses the id | `RetryRequested` keeps identity; ledger + coordinator tests |
| 12 Unknown outcomes never replay | coordinator answers from the ledger; no auto-retry machinery |
| 13 Flag does not advance the turn | `invalidatesCurrentTurn = false`; machine test |
| 14 Only confirmed `APPLIED` bury/suspend invalidate the turn | reducer uses `nextCardAllowed(action, status)`; audited + tested |
| 15 Only a confirmed `APPLIED` turn-invalidating action opens a fresh query | the single `AnkiStudyEffect.Next` in the applied branch of the outcome handler |
| 16 Anki remains the scheduler/card source of truth | the family never orders cards; capabilities derive from the backend's own contract |
| 17 `StudySessionMachine` remains interaction truth | actions flow machine → effect → executor → coordinator |
| 18 UI is projection only | `ReviewerActionUiState.from` + audit (no dispatch/backend references in UI) |
| 19 Recovery is durable across process death | separate store, unresolved records never evicted, startup scan before any query |
| 20 Public AnkiDroid API only | provider `update` on the pinned `schedule` path, card-scoped; no private DB access anywhere |

---

## 9. Definition of Done

```text
[x] ReviewerActionId implemented (value class, deterministic derivation)
[x] ReviewerActionStatus implemented (closed enum)
[x] ReviewerActionRecord implemented (identity + evidence only; no content)
[x] closed transition engine implemented (legal rows + rejections + per-status invariants)
[x] ReviewerActionLedger implemented (durable, capacity-safe, fail-closed, codec v1)
[x] ReviewerActionCoordinator implemented (ordering + exclusion + retry/recover)
[x] ReviewerActionBackendResult implemented (three states, never a Boolean)
[x] backend action semantics implemented per action (flag unverified, bury/suspend verified)
[x] rating/action mutual exclusion implemented (both directions, below the UI)
[x] Flag implemented (flow + projection; refused truthfully where the contract cannot write it)
[x] Bury implemented (AnkiDroid public provider contract)
[x] Suspend implemented (same path)
[x] recovery/reconciliation implemented (policy table, read-only reconciler, startup blocking)
[x] UI projection derived from durable status (Idle / Saving / RetryAvailable / VerificationRequired)
[x] Compose card-action menu mounted in the study screen      (ReviewerActionMenu in AnkiAnswerReviewSection; mount/intent locked by Gate13ArchitectureAuditTest, projection by ReviewerActionMenuUiTest + ReviewerActionUiProjectionTest)
[x] app-level session-start capability wiring                 (AnkiLocalStudyStarter + AppContainer + HomeViewModel; AnkiLocalStudyStarterTest, DashboardUiMapperTest)
[ ] real-device action run (VERIFICATION 14)                  (environment-blocked, see §7)
```

---

## 10. Evidence commands

```bash
tools/jvm-harness/bin/bootstrap-sandbox.sh                 # one-time toolchain (PyPI + GitHub)
HARNESS_WORK=/tmp/h tools/jvm-harness/bin/build.sh all     # main + tests, prints error counts
HARNESS_WORK=/tmp/h tools/jvm-harness/bin/run.sh           # all tests
HARNESS_WORK=/tmp/h tools/jvm-harness/bin/run.sh 'ReviewerAction|Gate13'
HARNESS_WORK=/tmp/h tools/jvm-harness/bin/run.sh 'AnkiLocalStudyStarterTest'
```

Observed on this branch: `main errors: 0 (217 files)`, `test errors: 0 (161 files)`,
`RESULT classes=139 tests=1599 passed=1599 failed=0` (32.7 s), and the filtered
`'ReviewerAction|Gate13'` group green at `classes=7 tests=104 passed=104 failed=0`. The harness deviations from Gradle are
listed in `tools/jvm-harness/README.md`; the Gradle commands (`./gradlew testDebugUnitTest lint
assembleDebug`) still cannot run here because every Maven mirror is unreachable — the same
environment limitation GATE 11 documented, and the reason `di/` and Compose sources are verified by
inspection plus the audit tests rather than by compilation in this sandbox.

---

## 11. Honest gaps (and what the wiring pass closed)

**11.1 Baseline repair.** The module did not compile at `f0a2eec`. Fixed here: `endReview` added to
`AnkiBackend` (the executor called it and the AnkiDroid backend overrode it, but the interface never
declared it); the executor's `reviewerAction` missing `return`; the missing imports in
`AnswerReviewModel`; the five extracted reviewer-action reducer helpers rewritten (they called local
functions of another scope); `AnkiDroidReviewerActionGateway`/`AnkiDroidWritePermit` visibility; the
UI models rebuilt on the new projection.

**11.2 Compose menu — wired.** `ReviewerActionMenu` renders `ReviewerActionMenuUi` and nothing else:
it decides nothing (no dispatch, no backend, no ledger, no status type is named in it), every row and
every flag choice is disabled while a durable action exists, rows appear in the frozen capability
order, bury/suspend confirm, and a refusal is a `blockMessage`, not a state. It is mounted with the
question in `AnkiAnswerReviewSection` and reaches the machine only through
`StudyViewModel.onReviewerAction/onRetry/onRecover` → `StudySessionRepository.request…ReviewerAction`.
Flag rows are text + semantics labels (`contentDescription`/`stateDescription`): the codebase has no
flag icon mapping, and colour is never the sole identifier.

**11.3 App session-start wiring — wired.** `AnkiLocalStudyStarter` is the one session-start site:
resolve → `refreshAvailability()` → re-resolve (fail closed) → deck lookup → **freeze**
`backend.reviewerActionCapabilities()` (capabilities *and* audited semantics) into
`AnkiStudyRequest.reviewerActions` → dispatch. It never mutates, never prepares an action and never
schedules anything. `AppContainer` exposes it lazily with
`dispatch = { request -> machineBackedSession?.startAnkiStudy(request) }`; Home offers
`PrimaryAction.START_LOCAL_ANKI` only when disconnected + `localAnkiReady` + idle, navigates only on
`Result.Started`, and renders refusals as one honest banner line.

**11.4 Device verification.** VERIFICATION 14 needs a disposable AnkiDroid collection; see §7.

**11.5 What the sandbox still cannot check.** The harness excludes Compose/AndroidX sources from
compilation (they are covered by the audit scans and by the *pure* UI projection tests), so the
composable, the ViewModel and the `di/` wiring are type-checked only by Gradle/CI — which cannot run
here. The reviewer-action instrumented test remains the one intentionally absent artefact.

---

## 12. Final model (one page)

```text
PREPARED     = durable intent, mutation boundary NOT entered  (backend effect known absent)
SUBMITTING   = boundary entered, the action may already have been applied  (never auto-retried)
APPLIED      = backend success confirmed and durable          (terminal)
RETRY_ALLOWED= non-application proven                         (same id may be resubmitted)
AMBIGUOUS    = outcome cannot be proven                       (reconcile, or remain blocked)

SetFlag     + APPLIED → same turn, flag projected, rating still available
BuryCard    + APPLIED → turn closed → one fresh scheduler query
SuspendCard + APPLIED → turn closed → one fresh scheduler query
any         + AMBIGUOUS → no replay, no next card, rating blocked
any         + RETRY_ALLOWED → the same action identity may retry

ReviewCommitLedger  = rating transaction truth      (GATE 11)
ReviewerActionLedger= reviewer-action truth         (GATE 13)
```

No second business-state vocabulary exists, and the UI only ever projects the durable truth.
