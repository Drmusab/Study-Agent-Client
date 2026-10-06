# GATE 11 — Rating Commit, Scheduler & Exactly-Once Reliability — PART X Final Report

**Repository:** Drmusab/Study-Agent-Client · **Branch:** `arena/01a0d3c9-study-agent-client` (from `master` @ `8e12262`, PR #28 merge)
**Date:** 2026-09-24 · **Report revision:** final (supersedes the earlier NO-verdict draft in this file)
**Counterpart:** AnkiDroid (ankidroid/Anki-Android), pinned API contract v2.24.1

---

## PART XI — Continuation report (2026-09-25, branch `arena/01a0d7b4-study-agent-client`, from `be726ca`)

> This part supersedes the verdict and the build/test numbers of the PART X revision below. PART X
> is kept for history. Where the two disagree, this part is based on commands run on this branch.

### XI.0 Verdict

**GATE 11: BLOCKED. REAL MUTATION VERIFICATION NOT RUN.**

- **Real mutation:** no disposable AnkiDroid collection or device was available. `adb`, an emulator
  and AnkiDroid are all absent. No real `ContentResolver.update` against AnkiDroid has run.
- **Build validation:** the mandated Gradle commands could not run. Every Maven mirror is blocked
  from this sandbox. The commands are `./gradlew --stop / clean / testDebugUnitTest / lint /
  assembleDebug / assembleRelease / connectedDebugAndroidTest`.
- **What ran instead:** a Gradle-free JVM harness (`tools/jvm-harness/`, deviations in its README)
  and the Python server tests.
- **Residual failures:** 79 JVM failures remain outside the commit surface (§XI.8).
- **Not claimed:** "exactly-once guaranteed". The gate-lock commit is not created and GATE 12 is not
  started.

### XI.1 Implementation order in this continuation (commits, oldest first)

| # | Commit | Files | Why |
|---|---|---|---|
| 1 | `fix: restore compilation of the app module` | `core/anki/ReviewCommitLedger.kt`, `core/security/SecureTokenStorage.kt`, `data/preferences/PreferencesDataStore.kt` | At `be726ca` the app did not compile. Causes: an unbalanced `)` in `recoveryBlocker`; `EncryptedSharedPreferences.create` called without `Context` (verified against androidx sources); an import of the member function `Preferences.toMutablePreferences`. No Gradle build could have succeeded, so the PART X "0 errors" claim does not hold for this commit. |
| 2 | `test: run AnkiDroidBackend state in backgroundScope` | `AnkiDroidBackendTest.kt` | `stateIn(scope, Eagerly)` in the `runTest` scope hung every test (16) until the runTest timeout. |
| 3 | `fix(ledger): never treat coroutine cancellation as a store failure` | `core/common/StoreFailureBoundary.kt` (new), `ReviewCommitLedger.kt`, `ReviewCommitLedgerTest.kt` | `catch (Exception)` around `store.read()` swallowed `CancellationException`. A cancelled caller during the first load disabled the durable ledger for the process lifetime. The change also removes exception types from `core/anki` (`AnkiDomainIsolationTest`). |
| 4 | `test(gate11): run reconciliation flows under a reconcilable backend identity` | `AnkiCommitHarness.kt`, `AnkiRatingCommitFlowTest.kt` | H2/H3 expected AnkiDroid-identity reconciliation, which enforcement correctly forbids. They now run under a PcAgent identity. New H2b pins the AnkiDroid behaviour: stays AMBIGUOUS, `reconcileCommit` is never called. |
| 5 | `feat(gate11): bound read-only reconciliation with a timeout` | `core/study/AnkiStudyEffectExecutor.kt`, `FakeAnkiBackend.kt`, test H5 | `reconcile()` had no timeout. It now uses `withTimeoutOrNull(10 s)`, and expiry maps to `StillAmbiguous("reconcile_timeout")`. The record stays AMBIGUOUS, there is no advance and no resend. |
| 6 | `test(gate11): record the commit durability order end to end` | `ReviewCommitDurabilityOrderTest.kt` (new), `InMemoryReviewCommitStore.kt` | One trace across ledger writes, backend calls and physical effects (§XI.3). |
| 7 | `test(gate11): seeded commit chaos over a backend with no memory` | `ReviewCommitChaosTest.kt` (new), `FakeAnkiBackend.kt` | 150 seeds × 2 identities, with crash at every fault point, redelivery, duplicates, reconcile and retry. Planted-bug check below. |
| 8 | `feat(server): durable review_commit_id effect dedup for the PC agent` | `server/review_commit_store.py` (new), `server/mock_pc_agent.py`, `server/test_review_commit_store.py` (new) | PC-side contract (§XI.5). |
| 9 | `tools: add a Gradle-free JVM unit-test harness` | `tools/jvm-harness/**` | Makes the evidence reproducible. No jars are committed. |

**Reused unchanged, per the "no parallel concepts" rule:**

- `ReviewCommit*`, `CommitSemantics*` and `CommitFaultInjection` in `core/anki`
- `DataStoreReviewCommitStore`
- `AnkiDroidRatingCommitter` / `AnkiDroidBackend` / `AnkiDroidProviderClient`
- `StudyReducer` / `AnkiStudyEffectExecutor`

No Room, no new manager classes and no project reorganisation.

### XI.2 Exact mutation boundary

| Backend | Call chain (file : symbol) | The irreversible call |
|---|---|---|
| AnkiDroid | `core/study/AnkiStudyEffectExecutor.kt` `commit()` → `backend.commitRating(req) { markMutationEntered }` (l.222) → `data/anki/ankidroid/AnkiDroidBackend.kt` `commitLocked` (l.641) → `AnkiDroidRatingCommitter.commit`: `if (!mutationEntry()) return …` (l.110), then `gateway.submitAnswer(...)` (l.112) → `AnkiDroidProviderClient.safeUpdate` | `contentResolver.update(content://<authority>/schedule, values, null, null)` (`AnkiDroidProviderClient.kt` l.340). Exactly one call, no internal retry. Preflight (queue-front check, deck selection) stays on the PREPARED side. |
| PC agent (client) | `StudyReducer` (l.944) puts `review_commit_id = PcRatingReplayPolicy.logicalCommitId(session, turn)` on `rate_card` | The WebSocket send. The client never resends: `automaticReplayAllowed(null)` is enforced in `SessionReconciler` / `StudySessionMachine`. |
| PC agent (server, mock) | `mock_pc_agent.py` `rate_card` → `ReviewCommitProcessor.commit` | `apply_fn(request)` after the durable INTENT write. A real agent would call Anki's `answerCard` here. |

### XI.3 Durability order and its evidence

Asserted verbatim by `ReviewCommitDurabilityOrderTest`:

```
durable:NOT_STARTED/null                      intent row, before anything else
durable:SUBMITTING/PREPARED                   CAS claim, before the backend call
backend:commitRating(enter)
boundary:mutationEntry-requested
durable:SUBMITTING/MUTATION_CALL_ENTERED      durable BEFORE the effect
boundary:mutationEntry=true physical=0        effect not yet applied
backend:commitRating(return) physical=1       exactly one physical effect
durable:SUBMITTING/MUTATION_RESPONSE_RECEIVED
durable:COMMITTED/LOCAL_RESULT_PERSISTED
backend:nextCard                              only after durable COMMITTED
```

The reducer's `CommitRating` transition contains no `Next` effect. If the `MUTATION_CALL_ENTERED`
write fails, the provider is never called (second test).

### XI.4 Crash-window table (client, AnkiDroid identity)

| Crash point (`CommitFaultPoint`) | Durable state after restart | Recovery | Physical effects | Evidence |
|---|---|---|---|---|
| BEFORE_LEDGER_CREATE | no record | nothing to recover; the user rates again (nothing was sent) | 0 | `ReviewCommitCrashWindowTest` |
| AFTER_LEDGER_CREATE / AFTER_PREPARED | NOT_STARTED or SUBMITTING/PREPARED | FAILED, safe-to-retry (`RECOVERED_PREPARED`); retry only by explicit user action, same id | 0 | `ReviewCommitLedgerTest`, chaos |
| AFTER_CALL_ENTERED / BEFORE_PROVIDER_CALL | SUBMITTING/CALL_ENTERED | **AMBIGUOUS**; never resubmitted | 0 (unknowable to the app) | crash test 1 |
| AFTER_PROVIDER_MUTATION / BEFORE_RESPONSE_PERSIST | SUBMITTING/CALL_ENTERED | **AMBIGUOUS**; never resubmitted | 1 | crash test 2 |
| AFTER_RESPONSE_PERSIST / BEFORE_COMMITTED_PERSIST | SUBMITTING/RESPONSE_RECEIVED | terminal from the durable response | 1 | `ReviewCommitLedgerTest` |
| AFTER_COMMITTED_PERSIST | COMMITTED | redelivery answers `ledger_replay` | 1 | chaos |

**Planted-bug check.** I temporarily treated an orphaned CALL_ENTERED row as retry-safe, the most
dangerous regression, and then reverted it.

- The crash-window tests failed (2/4).
- `ReviewCommitChaosTest` failed with `I1 … applied 3x`.

The chaos test detects this only because its fake backend has *no memory*. With the old default
fake, the fake's own dedup hid the bug. That is why the chaos test uses the no-memory model.

### XI.5 Per-backend guarantee matrix

| Backend | Frozen guarantee | Idempotent replay | Authoritative reconciliation | Automatic resend | Evidence |
|---|---|---|---|---|---|
| AnkiDroid (API v2.24.1) | **AT_MOST_ONCE_FAIL_CLOSED**; overclaims are clamped at freeze time | no | no; AMBIGUOUS stays AMBIGUOUS | never | H2b, `AnkiDroidRatingCommitTest` (18), `AnkiDroidBackendTest` (16) |
| PC agent, client side | whatever the welcome advertises, via `commitSemanticsFromAgent`; never END_TO_END_EXACTLY_ONCE | only if `review_commit_idempotency` is advertised | only if `commit_reconciliation` is advertised | **never** (`automaticReplayAllowed(null)`) | `ReviewCommitCrashWindowTest` "pc capabilities do not upgrade…" |
| Mock PC agent + `--commit-store` | server-side idempotent replay while the table file survives | yes: same id+payload → stored result; different payload → `COMMIT_CONFLICT`; intent without result → `COMMIT_OUTCOME_UNKNOWN` | not advertised | n/a | `server/test_review_commit_store.py` (21) |
| Mock PC agent without store | memory only; capability **not** advertised | per process only | no | n/a | same |
| Fake (tests) | configurable | configurable | configurable | never | contract tests |

**Exactly-once claim category:**

- AnkiDroid is **AT_MOST_ONCE_FAIL_CLOSED**. Duplicate mutation is prevented by the client ledger;
  an unknown outcome is surfaced as UNCONFIRMED and never guessed.
- End-to-end exactly-once is **not claimed** for any backend.

### XI.6 Evidence actually executed (this branch)

**JVM harness** (Kotlin 2.3 K2, coroutines 1.10.2 and stubs; see `tools/jvm-harness/README.md`):

- Compile: main 194 files and tests 130 files, both with **0 errors**.
- **Commit surface: 165/165 pass in 15 classes:**

  | Class | Tests |
  |---|---|
  | ReviewCommitLedgerTest | 23 |
  | ReviewCommitRecoveryPolicyTest | 4 |
  | ReviewCommitBackendContractTest | 4 |
  | ReviewCommitCrashWindowTest | 4 |
  | ReviewCommitDurabilityOrderTest | 2 |
  | ReviewCommitChaosTest | 1 (300 seeded runs) |
  | AnkiRatingCommitFlowTest | 30 |
  | AnkiRatingCommitMachineTest | 3 |
  | AnkiRatingCommitArchitectureTest | 7 |
  | AnkiStudyInteractionTest | 15 |
  | AnkiDroidRatingCommitTest | 18 |
  | AnkiDroidBackendTest | 16 |
  | AnkiDomainIsolationTest | 4 |
  | FakeAnkiBackendTest | 25 |
  | FakeAnkiBackendContractTest | 9 |

- **Full suite: 1267 tests in 111 classes; 1188 pass, 79 fail, 0 timeouts.** No failure is in the
  commit surface. The failing set was identical on two consecutive runs.

**Python** (`server/`, websockets 17.1, pytest 9.1):

- `test_review_commit_store.py`: **21/21**. This includes a WebSocket end-to-end test against the
  real mock process: two deliveries with new `message_id`s → a PC agent restart → a replay returns
  `duplicate: true` and a different payload returns `COMMIT_CONFLICT`. The table shows exactly one
  apply.
- Whole `server/`: 64 pass, 22 fail, 7 skip. All 22 failures are in `test_tts_contract.py` and are
  **pre-existing**: they are identical on untouched `be726ca` (43 pass / 22 fail), with
  `TypeError: BaseEvent…` under websockets 17.

**Not run:**

- any Gradle task, lint, assemble, `connectedDebugAndroidTest`, or CI (`gh` returned
  401 Bad credentials in this session);
- any real AnkiDroid mutation.

### XI.7 Unverified limits and residual risk

1. **Real AnkiDroid behaviour.** Unverified: `update(/schedule)` semantics under contention, the
   provider returning after applying, the process dying inside `update`, and the rating→ease mapping
   (AGAIN/HARD/GOOD/EASY = 1/2/3/4) against a real install. The mapping was pinned from source in an
   earlier session; the re-verification is still owed.
2. **Dual-write limit.** The client ledger and the AnkiDroid collection are separate stores. Between
   durable CALL_ENTERED and the provider's reply, the true outcome is unknowable. The design turns
   this into AMBIGUOUS/UNCONFIRMED; the user can check again or end the session, and the app never
   re-rates. Such a rating can be lost (at most once), but never doubled.
3. **PC agent.** Only the mock implements the durable table. A production PC agent must implement
   §XI.5 before it may advertise `review_commit_idempotency`. If its table file is lost, a replay
   applies again; this is tested and documented. The client does not auto-replay today, so this only
   matters if replay is enabled later.
4. **Harness deviations.** A defect that only appears under Kotlin 1.9 K1, coroutines 1.8.1, real
   DataStore or real JUnit would not be seen here.
5. **Residual failures (79).** These are not investigated to root cause because they are outside
   GATE 11:
   - **PC voice-session stack (~35):** HappyPath, NetworkChaos, Simulation, Endurance,
     StudyAgentChaos, Reconstruction, ReconnectAtEveryPhase, StudyReducerTest (3), PhoneMode. The
     representative case stalls before the answer window (no `STT_READY` after `TTS_DONE`). The
     rating-related ones fail upstream of any rating (e.g. `NetworkChaosTest` l.105), not on a double
     rate.
   - **PC management repositories (~17):** StudyControl, Dashboard, DiagnosticsExport,
     DashboardUiMapper.
   - **AnkiDroid read-side and health (~12):** CardGateway, CardMapper, HealthRepository,
     ReviewSession, CardFlow.
   - **Protocol fuzz and validation (7), render controller (4), TTS (3).**
   - **Confirmed stale test or code-vs-test contradictions, independent of the harness:**
     - `ProtocolJsonTest` expects `protocol_version "1"`, but every message defaults to `"2"`.
     - `AnkiMediaResolverPolicyTest`: `URLDecoder` throws on `"100% ready.webp"`, so the name is
       rejected.
     - `AnkiCardHydrationTest` uses `originalDeckRef == scheduledDeck`, which the code deliberately
       treats as the filtered-deck explanation.

   Whether the remaining ones come from harness versions or are real regressions is **unknown**.
   Master did not compile, so there was no green baseline to compare against.

### XI.8 Final questions

- **Final safety question.** Under the implemented design, can one Study-Agent review turn cause
  more than one scheduler mutation, or advance to the next card without a durable COMMITTED?
  - **No,** for every path exercised: unit, contract, durability-order, crash-window and 300 seeded
    chaos runs against a backend with no memory. The planted-bug check shows these tests catch the
    relevant regression.
  - **Not verified** against real AnkiDroid. So the gate's required "YES, verified" answer cannot be
    given, and the verdict cannot be PASS.
- **Stronger end-to-end question.** Is every rating applied to Anki exactly once?
  - **No, and it cannot be with AnkiDroid API v2.24.1.** The provider offers no commit id, receipt
    or idempotent write. A lost reply after the write is indistinguishable from a write that never
    happened.
  - The strongest honest guarantee is **at most once, fail closed**. Unknown outcomes are shown to
    the user, not guessed.
  - With a PC agent that implements §XI.5 durably, replay becomes safe, but a crash between intent
    and apply is still reported as unknown rather than applied.

### XI.9 Lock checklist

| Item | Status |
|---|---|
| Domain and commit core free of Android and UI imports | ✅ `AnkiDomainIsolationTest`, `AnkiRatingCommitArchitectureTest` |
| Mutation boundary single, marked durably before the call, no internal retry | ✅ §XI.2/§XI.3 |
| `nextCard` only after durable COMMITTED; no `Next` in the CommitRating result | ✅ durability-order test |
| AMBIGUOUS never replayed; recovery never mints a new id nor asks to re-rate | ✅ crash tests, chaos I1/I3, H2b |
| Payload mismatch → CONFLICT (client ledger and PC server) | ✅ ledger tests, `test_same_id_different_payload_is_conflict_without_effect` |
| Reconciliation read-only and bounded, timeout → unresolved | ✅ H5 |
| No secrets or card content in ledger or commit table | ✅ ledger codec tests, `test_table_stores_no_secrets_or_card_content` |
| Seeded chaos with evidence it detects a real regression | ✅ §XI.4 |
| PC server `review_commit_id` dedup + tests | ✅ mock plus reference module; ❌ production PC agent not in this repo |
| `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` | ❌ not run (network) |
| `connectedDebugAndroidTest` | ❌ not run (no device) |
| Real disposable-collection AnkiDroid mutation test | ❌ **REAL MUTATION VERIFICATION NOT RUN** |
| Full JVM suite green | ❌ 79 failures outside the commit surface |
| Gate-lock commit | ❌ not created |

---

# PART X (2026-09-24), kept for history; superseded by PART XI above

## 0. Verdict (read this first)

**OVERALL GATE 11 RESULT: BLOCKED** — not FAIL (the exactly-once machinery exists, is
architecturally correct and passes every dedicated unit/contract suite that could be executed), and
not PASS (three classes of evidence are still owed). BLOCKED on precisely:

1. **CI is billing-locked.** GitHub Actions will not run a single job for this repository
   (billing/disabled-workflows wall; observed across 40+ runs, e.g. run 36006978716 — all queued
   runs fail at 0 steps). The mandated PART VII Gradle pipeline (`clean`, `testDebugUnitTest`,
   `lint`, `assembleDebug`, `assembleRelease`) and the emulator instrumented matrix therefore have
   **no** green artifacts and cannot produce any until billing is restored.
2. **No real disposable-collection AnkiDroid mutation test has run.** Per the gate's own rule we do
   **not** claim production exactly-once until a disposable AnkiDroid collection/profile mutation
   run is executed on real hardware (that is scheduled as GATE 13's device matrix). The JVM suites
   prove the protocol; they cannot prove AnkiDroid's ContentProvider behaviour under real scheduler
   contention.
3. **65 residual JVM failures** remain in *non-commit* areas (voice-test virtual-time plumbing,
   management v2 flows, protocol-fuzz expectation drift, GATE-07-era pre-existing bugs). The
   rating-commit surface itself is fully green; the residual list is itemized in §20 and must reach
   zero (or be waived with documented pre-existing status) before a PASS.

Gate-lock commit `gate11: add exactly-once Anki rating commit reliability` is **NOT** created.
GATE 12 is **NOT** started. Per the gate order: STOP.

---

## 1. Scope and authority

First gate permitted to mutate real Anki scheduler state — via the design/implementation of the
commit pipeline and its verification contract, not via live mutation in this environment (see
verdict item 2). Scope of this gate: exactly-once **rating** (review-answer) commits per Study-Agent
review turn, the durable commit ledger, the commit/scheduler/reconciliation pipeline, and the
instrumentation around it. Explicitly out of scope (unchanged): bury/suspend/flag, undo, note
editing, custom study, Library UI, sync/import/export.

Uniqueness audit (pre-implementation, per the no-parallel-concepts rule): the repository already
contained `core/anki/ReviewCommit.kt`, `ReviewCommitLedger.kt`, `AnkiBackend.kt`, `data/anki/
DataStoreReviewCommitStore.kt`, `data/anki/ankidroid/AnkiDroidRatingCommitter.kt`,
`AnkiDroidCommitEvidence.kt`, `AnkiDroidCommitVerifier`, `core/study/AnkiStudyEffectExecutor`,
`SessionReconciler`, `RatingCommitRecoveryUi` — GATE 11 re-verified and hardened this single
concept family rather than introducing a second one.

## 2. Verification method and evidence channels

| Channel | Status | What it proves |
|---|---|---|
| Pinned-source reading (AnkiDroid v2.24.1 contract per `docs/ankidroid/*`, prior fetch) | partial | rating mapping, provider semantics (see §16 caveat) |
| JVM unit/contract suites (dedicated commit suites) | **GREEN, first full run ever** | protocol, ledger, idempotency, conflicts, Fake contract |
| JVM harness suites (full session stack) | 1175/1240 pass | end-to-end turn flow incl. exactly-once taps; see §17/§20 |
| `./gradlew testDebugUnitTest / lint / assemble*` | **BLOCKED** (billing + network) | — |
| Instrumented/emulator matrix | **BLOCKED** (billing + no emulator) | — |
| Disposable-collection AnkiDroid mutation run | **NOT RUN** (GATE 13) | production exactly-once |

Local validation used a pinned-era toolchain shim harness (Kotlin 1.9.23 K2JVMCompiler, real
`android-34.jar`, thin junit/coroutines-test/okhttp shims, Gradle test-friend semantics via
`-Xfriend-paths`), documented in §22. 187 main files + 126 test files compile with **0 errors**;
the reflective runner executes **1240 tests** across **146** discovered classes.

## 3. Distinct Suggested / Selected / Committed ratings

- **Suggested** = `AnkiStudyInteraction` exposes the scheduler's suggested answer states
  (`schedulingStates` / `states`) as presentation data only. Nothing in the suggest path can write.
- **Selected** = the user's (touch/voice/headset) `UserRateCard` intent — held as the interaction's
  choice and as `PendingAction.RATE_CARD(expectedRating)`; it is not a mutation until claimed.
- **Committed** = exactly one `ReviewCommitLedger` entry reaching `COMMITTED` for the
  `ReviewCommitId`, with durable evidence (§11/§13). The reducer records `RATING_SAVED` and counts
  `totalReviewedInSession` **only** in the `COMMITTED` path (`duplicate-anki-rating` otherwise).
- Verified by: `AnkiStudyInteractionTest`, `AnkiRatingCommitFlowTest`, `StudyReducer` first-wins
  semantics (`duplicate-anki-rating`, `anki-rating-locked-first-wins`), `AnkiRatingCommitMachineTest`.

## 4. Stable `ReviewCommitId(sessionId, turnId, backendId)`

`ReviewCommitId(backendId, studySessionId, turnId)` is minted once when the commit intent is
prepared, is the ledger key (`commitId.stableKey`), and is **reused verbatim across retries** —
`toRequest()` on a retry resends the identical id and payload. Duplicate taps/utterances collapse to
the same id at the reducer (first-wins), so retries can never mint a second mutation identity.
Verified by: `ReviewCommitLedgerTest` (id stability), `AnkiRatingCommitFlowTest` (retry resends
identical `toRequest()`), `AnkiDroidRatingCommitTest` (id travels to the committer).

## 5. Durable `ReviewCommitLedger` (state machine + persistence ordering)

States: `NOT_STARTED → SUBMITTING → COMMITTED` / `FAILED_SAFE_TO_RETRY` / `AMBIGUOUS` — full
diagram now in `docs/ANKIDROID_INTEGRATION.md` §29.3 (transaction state diagram added this run).

Hard ordering guarantees (code + tests agree):

1. The entry is **persisted before any mutation is dispatched** (`prepare`/`claim` writes; commit
   refuses to run with no ledger — fail closed).
2. `SUBMITTING` is claimed by CAS: exactly one claimant wins; the loser is a duplicate and sends
   nothing.
3. `COMMITTED` is **persisted before the session advances** (`complete` runs in `NonCancellable`;
   `Next`/counters fire only after the durable `COMMITTED` write succeeds).
4. Load converts orphan `SUBMITTING` → `AMBIGUOUS` (`PROCESS_RESTART_WHILE_SUBMITTING`).
5. `complete` is legal only from `SUBMITTING`; `reconcile` only from `AMBIGUOUS`.
6. Unreadable ledger ⇒ `Health.Unavailable` + all commits refused; prune cap 200 entries protecting
   8, never dropping `SUBMITTING`, unacked `AMBIGUOUS`, or protected keys.

Verified by: `ReviewCommitLedgerTest` (transition legality, persistence ordering, concurrency,
corruption handling), `DataStoreReviewCommitStore` tests (`SettingsPersistence`-adjacent suites),
`InMemoryReviewCommitStore` contract twin, `FakeAnkiBackendContractTest`.

## 6. At-most-one mutation per turn

Defense in depth: (a) reducer first-wins on `UserRateCard` (`SubmittingRating` rejects further
taps: `illegal-phase-for-rating` — captured live in the harness evidence, §17); (b) ledger CAS on
`claim`; (c) `AnkiDroidRatingCommitter` issues exactly **one** `answer update`, after a queue-front
and baseline-equality precondition, and refuses (nothing sent) on mismatch; (d) the PC backend
message idempotency (`messageId` dedup) is unchanged. A duplicated rating **ack** cannot advance
the deck twice (`stale-card`, `duplicate-messageId`, `unexpected-rating-ack` guards in
`handleRatingSaved`).

## 7. `nextCard()` only after COMMITTED (absolute barrier)

`StudyEffect.Next`/`NextCard` is emitted **exclusively** inside the `COMMITTED` branch of
`RatingCommitResolved` handling in `StudyReducer`/`AnkiStudyEffectExecutor` — verified by reading
the reducer (`Next`+counters only in the committed branch) and by `AnkiRatingCommitMachineTest`
("no next before commit"), `AnkiStudyInteractionTest`. `FAILED_SAFE_TO_RETRY` and `AMBIGUOUS` never
reach `Next`.

## 8. AMBIGUOUS ⇒ fail closed to reconciliation-required

`AMBIGUOUS` is never treated as success (no advance, no counter) nor as failure (no auto-retry).
Any timeout/unknown outcome after the mutation may have been dispatched resolves to `AMBIGUOUS`
unless evidence proves otherwise. `RetryRatingCommit` is gated on `FAILED_SAFE_TO_RETRY +
safeToRetry`; `ReconcileRatingCommit` is gated on `AMBIGUOUS` and is the only way out (evidence
based — may conclude `COMMITTED`, `FAILED_SAFE_TO_RETRY`, or remain `AMBIGUOUS`). UI copy/flow:
`RatingCommitRecoveryUi` (recovery is explicit and explains itself; never a silent second
mutation).

## 9. `CommitConflict` — wrong turn / card / session

`prepareCommit`/baseline comparison rejects with `Rejected(CommitConflict)` when: the card identity
or counters changed since the durable baseline (reviewed elsewhere), the pending rating belongs to
another card/turn, the ack's session/card/rating is foreign (`stale-session`, `stale-card`,
`stale-pending`, `unexpected-rating-ack`), or the AnkiDroid queue front is not this card. Nothing
is sent on any conflict. Verified by `ReviewCommitLedgerTest`, `AnkiDroidRatingCommitTest`,
`AnkiDroidCommitVerifierTest`, reducer stale/duplicate suite.

## 10. One commit pipeline for touch / voice / headset

All three user surfaces dispatch the same `UserRateCard` into the same machine; there is no second
pipeline (pre-implementation uniqueness audit, §1). Voice command routing (`VoiceCommand` →
`UserRateCard`) and phone/headset key events converge before any effect is produced. Verified by:
`PhoneModeLoopTest`, `PhoneModeMachineIntegrationTest`, `AnkiStudyInteractionTest`,
`SessionIdempotencyTest` (double-tap / double-utterance collapse to one wire message).

## 11. Session limit checked **after** commit

The due-count / session-limit accounting advances (`totalReviewedInSession + 1`) only inside the
`COMMITTED` path of `handleRatingSaved`/`RatingCommitResolved`; `AnkiDroidReviewSession` limits are
enforced against committed work (`AnkiDroidReviewSessionTest` limit refusal cases — see §20 on the
two failing expectation cases), and `nextCard` (which consults the limit) cannot run before commit
(§7).

## 12. Next-card failure never undoes a prior commit

The next-card step is dispatched only after the durable `COMMITTED` write; a `NextCardResult.
Failure`/`BackendUnavailable` after that point transitions the session to a failure/recovery phase
**without** touching the ledger entry (already terminal `COMMITTED`) and without re-sending the
rating. The committer never runs "compensating" mutations (out of scope: undo). Verified by
`AnkiRatingCommitFlowTest` (next-failure leaves commit intact), reducer reading (commit result
handling has no reverse edge).

## 13. Fake backend enforces the same idempotency contract

`FakeAnkiBackend` implements `AnkiBackend` with the same contract as the PC/AnkiDroid backends:
same `ReviewCommitId` reuse, duplicate `commitRating` of the same id is absorbed (no second
mutation), conflicting second mutation is rejected, and the scheduling state model (ease 1..4) is
faithful. `FakeAnkiBackendTest` (25) and `FakeAnkiBackendContractTest` are green in the full run.

## 14. PC backend compatibility

The PC path (`WebSocketAgentConnection` protocol messages `rate_card`/`rating_saved` with
`messageId` idempotency and `reviewTurnId`) is byte-identical to the pre-gate behaviour
(`handleRateCard`/`handleRatingSaved`/`handleTimeout` md5-verified earlier and untouched by this
work — only genuinely broken files were repaired, see §20 bug catalog). PC semantics remain
compatible: the ledger sits above the transport and applies to PC commits exactly as to AnkiDroid
commits.

## 15. Diagnostics without card content

Commit instrumentation codes (metadata only — ids, phases, timings, outcomes; never question/answer
text, never card HTML): `ANKI_COMMIT_PREPARED`, `ANKI_COMMIT_STARTED`, `ANKI_COMMIT_COMMITTED`,
`ANKI_COMMIT_FAILED`, `ANKI_COMMIT_AMBIGUOUS`, `ANKI_RETRY_STARTED`,
`ANKI_RECONCILIATION_STARTED`, `ANKI_RECONCILIATION_RESULT`, `ANKI_NEXT_CARD_AFTER_COMMIT_STARTED`.
Harness timeline (`INVARIANT_VIOLATION`, `RATING_SENT`, `RATING_SAVED`, …) shows shapes and ids
only (evidence in §17). Export-privacy suite exists (`DiagnosticsExportPrivacyTest`); its 3
residual failures are listed in §20 (redaction marker expectations) and block the PASS claim.

## 16. Rating mapping (verified against pinned contract — with caveat)

Mapping used everywhere (`AnkiDroidRatingContract.easeFor`, reducer, Fake backend):
**AGAIN→1 · HARD→2 · GOOD→3 · EASY→4**; anything else ⇒ `rating_not_offered_by_scheduler`
(unsupported rating is refused, never guessed). The mapping matches the documented pinned
AnkiDroid v2.24.1 API (`Ease.fromValue`, `CardAnswer.Rating.forNumber(value-1)`).
**Honesty note:** the pinned-source re-fetch (gh api against ankidroid/Anki-Android @ v2.24.1)
could not be repeated in this sandbox session (network policy); the mapping is carried from the
earlier verified extraction recorded in `docs/ankidroid/*` and MUST be re-verified against the tag
during the GATE 13 device work. Rated as PARTIAL evidence for PASS purposes.

## 17. PART III test plan — executed status

| Scenario group | Suite | Result |
|---|---|---|
| Duplicate tap / double utterance collapses to one mutation | `SessionIdempotencyTest` (harness) | behaviour GREEN (harness invariant noise: §20-B) |
| Voice+touch race, first-wins | `AnkiRatingCommitMachineTest`, reducer suite | GREEN |
| Conflicting race (wrong rating ack, second mutation) | `SessionIdempotencyTest`, `ReviewCommitLedgerTest` | GREEN |
| Process death after mutation entry (SUBMITTING→AMBIGUOUS on load) | `ReviewCommitLedgerTest` (persistence/corruption group) | GREEN |
| Process death mid-session reconstruction | `SessionReconstructionTest` | 1 of 3 GREEN; 2 residual (§20-B) |
| Endurance 1000 turns | `StudySessionEnduranceTest`, `StudyReducerTest#1000 card simulation` | residual failures (§20-B) — blocks PASS |
| Chaos (drops/dups/reorder) | `NetworkChaosTest`, `StudyAgentChaosTest` | mixed (§20-B) |
| Ledger transitions/persistence/concurrency/corruption | `ReviewCommitLedgerTest` (22) | **GREEN** |
| Exactly-once commit flow (unit) | `AnkiRatingCommitFlowTest` (25), `AnkiRatingCommitMachineTest` | **GREEN** |
| AnkiDroid committer/verifier contract | `AnkiDroidRatingCommitTest` (13), `AnkiDroidCommitVerifierTest` | **GREEN** |
| Fake/PC contract | `FakeAnkiBackendTest` (25), `FakeAnkiBackendContractTest`, `AnkiRatingCommitArchitectureTest` | **GREEN** |
| Recovery UI contract | `RatingCommitRecoveryUi` suites (non-Compose portions) | GREEN (Compose portion NOT RUN — no Compose toolchain) |
| Real disposable-collection AnkiDroid mutation | instrumented `AnkiDroidReviewInstrumentedTest` | **NOT RUN** (GATE 13; CI billing-locked) |

Live harness evidence of the exactly-once invariant (verbatim from the run log, `SessionIdempotencyTest
#rating twice sends one rating`):

```
NETWORK   RATING_SENT      turn=turn-1  req=7b1a9762  rating=good
SESSION   EVENT_REJECTED   turn=turn-1  event=UserRateCard reason=illegal-phase-for-rating
SESSION   EVENT_REJECTED   turn=turn-1  event=UserRateCard reason=illegal-phase-for-rating
SESSION   RATING_SAVED     turn=turn-1  rating=good
SESSION   QUESTION_RECEIVED turn=turn-2 card=card-2
```

One `RATING_SENT`; the second and third taps rejected; exactly one advance.

## 18. PART VII build validation

**Official pipeline: BLOCKED** (billing-locked Actions + no route to services.gradle.org/
maven.google.com/dl.google.com in this sandbox; a local gradle-8.7 distribution cannot resolve AGP
dependencies). Evidence owed for PASS: `./gradlew --stop && ./gradlew clean testDebugUnitTest lint
assembleDebug assembleRelease` + `connectedDebugAndroidTest` on the disposable profile.

**Local shim-harness substitute (honest, non-equivalent):** main 187 files / 0 errors; tests 126
files / 0 errors; run 1240 tests, **1175 passed / 65 failed** (was 1164/76 before the reducer
fixes in §20-A). All dedicated rating-commit suites at **zero failures**.

## 19. Documentation updates

- `docs/ANKIDROID_INTEGRATION.md` — commit protocol §29.3 + **transaction state diagram added**
  this run (ledger lifecycle incl. process-death and reconciliation edges).
- `docs/SESSION_STATE_MACHINE.md`, `docs/ANKI_INTEGRATION_ARCHITECTURE.md` — GATE 11
  exactly-once / `ReviewCommitLedger` sections present (verified this run).
- `docs/adr/0007-rating-authority-and-exactly-once.md` — authority + exactly-once decision record.
- This report.

## 20. Known issues — full bug catalog and residual failures

### A. Real repository bugs found and FIXED in this gate's validation (16 total)

Compilation-blocker class (the master tree did not compile at all):

1. `core/voice/tts/AndroidSpeechBackend.kt` — file corrupt (two classes concatenated); split, 97-line clean file + `AudioTrackStreamingPlayer.kt` extracted.
2. `core/network/WebSocketAgentConnection.kt` — missing `override` ×2 + `updateSnapshot` signature drift.
3. `core/voice/tts/RemoteSpeechBackend.kt` — duplicate `override` member, stray `val _ =`, `GlobalScope.launch` misuse.
4. `core/voice/tts/ProviderBackendRouter.kt` — `copy(available=…)` on a derived (non-constructor) property → `copy(connected=false, healthy=false)`.
5. `core/voice/tts/AudioTrackStreamingPlayer.kt` — `val`→`var` + `import kotlin.coroutines.resume`.
6. `data/anki/ankidroid/AndroidAnkiDroidProbe.kt` — `getPermissionInfo(permission, 0)` (the `PackageInfoFlags` overload does not exist on the pinned platform).
7. `core/security/SecureTokenStorage.kt` — `EncryptedSharedPreferences.create` 5-arg call matched no API → 4-arg form.
8. `TestScheduler` → `TestCoroutineScheduler` in 3 test files (the class does not exist).

API-shape class (would fail in any toolchain — trailing-lambda binding puts the function parameter last):

9. `core/voice/StudyVoiceTurnGate.kt` — `awaitListenWindow(stillValid, waitForSpeechToSettleMs=0)` uncallable as `awaitListenWindow { }` at 11 call sites → reordered `(waitForSpeechToSettleMs: Long = 0L, stillValid: () -> Boolean)`.
10. `testutil/StudySessionHarness.kt` — same trailing-lambda trap on `awaitPhase`; bare `runCurrent()` without receiver.
11. `testutil/FakeStudyServer.kt` — nested `it` shadowing sent the repeat index instead of the message.

Interface conformance class (test doubles vs `ConnectionRepository`):

12. `data/ManagementTestDoubles.kt` + `study/PhoneModeMachineIntegrationTest.kt` + `study/DefaultStudySessionRepositoryTest.kt` fakes — missing `connectionSnapshot`, `connectWithOverride`, `testConnection`, `getConnectionDiagnostics`.
13. `SettingsPersistenceRegressionTest.kt` — `corruptedCachePreferences(): Preferences` must return `MutablePreferences`.
14. `DiagnosticsExportPrivacyTest.kt` — local extension `diagnostics()` shadowed by the harness member of the same name (renamed `diagnosticsRepo()`).

**State-hygiene class (production behaviour bug) — the important one:**

15. `core/study/StudyReducer.kt` — `handleRatingSaved`, `handleSessionFinished`, `handleConnectionLost`, `handleConnectionRestored` left `cardTurn.evaluation` populated after the turn ended, tripping the production invariant `evaluation-in-illegal-phase` (evaluation visible in `WaitingForCard`/`Finished`/`Recovering`) on 63+ events and failing 11 harness tests. Fixed at all four sites (evaluation retires with the turn). Regression proof: 76→65 failures with zero regressions.

16. `render/AnkiCardRenderControllerTest` / `StudySessionHappyPathTest` / `StudyReducerTest` / `ConnectionLifecycleTest` / `IdempotencyTest` / `StudyControlRepositoryTest` — assorted never-compiled test-only bugs (wrong enum values, `List.indexOf` with `fromIndex`, duplicate named arg, `CardTurn(generation=…)` duplication, non-`val` ctor param) fixed as documented in the working notes.

### B. Residual 65 failures — triage (blocks PASS)

| Cluster | Count | Triage |
|---|---|---|
| `NetworkChaosTest` chaos/dup/ordering cases | 11 | harness virtual-time + invariant setup; needs green |
| `StudyControlRepositoryTest` management-v2 save/ACK (`TimedOut`) | 9 | v2 ACK correlation under shim scheduling; needs green |
| `DashboardRepositoryTest` v2 dashboard request flows | 5 | same family |
| `StudySessionSimulationTest` / `HappyPath` / `Endurance` / `StudyAgentChaos` / `Reconnect` / `SessionReconstruction` / `SessionIdempotency` / `PhoneMode` harness-stack | 18 | voice-flow virtual-time (e.g. `STT_READY` ordering, `question must be spoken first`) + 2 endurance; mostly runTest-shim semantics (no real `TestDispatcher`); must be re-run on real coroutines-test |
| `ProtocolFuzzTest` fail-closed expectations | 4 | test expects `null`, parser now returns `Unknown(...)` (the newer fail-closed contract) — test drift, update expectations |
| `ProtocolJsonTest` / `ProfileValidatorTest` / `IdempotencyTest` / `DashboardUiMapperTest` singles | 4 | per-case review (incl. possible real `DashboardUiMapper` deck-label bug: `MCCQE::Cardiology` → `Cardiology`) |
| `DiagnosticsExportPrivacyTest` | 3 | redaction-marker visibility — treat as privacy-relevant until green |
| `AnkiCardFlow`/`Hydration`/`MediaResolver`/`AnkiDroidCardMapper`×2/`CardGateway`×2/`AnkiDroidReviewSession`×2 | 9 | **pre-existing** at the CI baseline (GATE 07 era; matches the "not our failures" list) |
| `AnkiCardRenderControllerTest` / `AnkiRenderEventTest` | 4 | render-gate expectations (generation counting, fallback flags) |
| TTS (`SpeechOrchestratorTest` deadlock, `SpeechQueueTest`, `TtsVoiceSelectorTest`) | 3 | voice policy + one runTest deadlock |

### C. Environment blockers (repeated for the record)

- GitHub Actions billing lock — zero CI evidence obtainable.
- No egress to maven.google.com / services.gradle.org / raw.githubusercontent.com; Gradle build impossible; pinned-source fetch limited to `gh api`.
- Therefore every "NOT RUN" above stays NOT RUN; nothing is claimed in their place.

## 21. Per-invariant verification — INV-ANKI-COMMIT-01 … 48

Status key: **PASS** = verified by executed suite/code reading here; **PARTIAL** = code-correct,
device/CI evidence owed; **BLOCKED** = cannot execute in this environment.

| # | Invariant | Status | Evidence |
|---|---|---|---|
| 01 | Suggested rating is read-only presentation data | PASS | `AnkiStudyInteractionTest` |
| 02 | Selected rating is intent only, no mutation before claim | PASS | reducer `PendingAction` path, `AnkiRatingCommitFlowTest` |
| 03 | Committed rating requires durable `COMMITTED` | PASS | `ReviewCommitLedgerTest` |
| 04 | The three ratings are distinct concepts in code | PASS | `AnkiRatingCommitArchitectureTest` |
| 05 | `ReviewCommitId(sessionId, turnId, backendId)` minted once per turn | PASS | `ReviewCommitLedgerTest` |
| 06 | `ReviewCommitId` reused verbatim across retries | PASS | `AnkiRatingCommitFlowTest` (identical `toRequest()`) |
| 07 | Ledger key is `commitId.stableKey` | PASS | `ReviewCommitLedgerTest` |
| 08 | Entry persisted before mutation dispatch | PASS | `ReviewCommitLedgerTest` persistence-order group |
| 09 | `SUBMITTING` claim is CAS (one claimant) | PASS | `ReviewCommitLedgerTest` concurrency group |
| 10 | `COMMITTED` persisted before session advance | PASS | `AnkiRatingCommitMachineTest`, reducer reading |
| 11 | `complete` only from `SUBMITTING` | PASS | `ReviewCommitLedgerTest` |
| 12 | `reconcile` only from `AMBIGUOUS` | PASS | `ReviewCommitLedgerTest` |
| 13 | `NOT_STARTED→SUBMITTING→COMMITTED` legal path only | PASS | `ReviewCommitLedgerTest` |
| 14 | `SUBMITTING→FAILED_SAFE_TO_RETRY` only with proof-of-no-mutation | PASS | `AnkiDroidCommitVerifierTest`, ledger tests |
| 15 | Process death in `SUBMITTING` loads as `AMBIGUOUS` | PASS | `ReviewCommitLedgerTest` (`PROCESS_RESTART_WHILE_SUBMITTING`) |
| 16 | Unreadable ledger ⇒ `Health.Unavailable` + refuse commits | PASS | `ReviewCommitLedgerTest` corruption group |
| 17 | Ledger prune bounded (200/8) and protects live entries | PASS | `ReviewCommitLedgerTest` |
| 18 | At-most-one mutation per turn (duplicate tap) | PASS | `SessionIdempotencyTest` (live log §17) |
| 19 | At-most-one mutation per turn (voice+touch race) | PASS | `AnkiRatingCommitMachineTest` |
| 20 | Duplicate ack cannot advance twice | PASS | reducer `duplicate-messageId`/`stale-card` + `NetworkChaosTest` (behaviour green in log) |
| 21 | `nextCard()` strictly after durable `COMMITTED` | PASS | `AnkiRatingCommitMachineTest` |
| 22 | No `Next` effect on `FAILED_SAFE_TO_RETRY`/`AMBIGUOUS` | PASS | reducer reading + tests |
| 23 | Timeout after dispatch ⇒ `AMBIGUOUS` | PASS | `AnkiDroidRatingCommitTest` |
| 24 | Timeout before dispatch ⇒ safe retry | PASS | `AnkiDroidRatingCommitTest` |
| 25 | `AMBIGUOUS` never auto-retried | PASS | `AnkiRatingCommitFlowTest`, `RatingCommitRecoveryUi` contract |
| 26 | `AMBIGUOUS` never counted as success | PASS | reducer counters live only in `COMMITTED` branch |
| 27 | Reconciliation is the only exit from `AMBIGUOUS` | PASS | `SessionReconciler` reading + tests |
| 28 | Reconciliation is evidence-based (3 outcomes) | PASS | `AnkiDroidCommitVerifierTest` |
| 29 | `CommitConflict` on wrong-turn commit | PASS | `ReviewCommitLedgerTest`, reducer `stale-pending` |
| 30 | `CommitConflict` on wrong-card commit | PASS | baseline-equality in `AnkiDroidRatingCommitter` tests |
| 31 | `CommitConflict` on wrong-session commit | PASS | reducer `stale-session` |
| 32 | Conflicts send nothing | PASS | `AnkiDroidRatingCommitTest` |
| 33 | Touch path uses the single pipeline | PASS | `SessionIdempotencyTest`, architecture test |
| 34 | Voice path uses the single pipeline | PASS | `PhoneModeLoopTest`, `AnkiStudyInteractionTest` |
| 35 | Headset/phone path uses the single pipeline | PARTIAL | `PhoneModeLoopTest` green; `PhoneModeMachineIntegrationTest` 1 residual (§20-B) |
| 36 | Session limit counted only after commit | PASS | reducer `totalReviewedInSession` committed-branch only |
| 37 | Session limit refuses over-limit sessions | PARTIAL | `AnkiDroidReviewSessionTest` (2 expectation cases failing — §20-B) |
| 38 | Next-card failure leaves prior commit intact | PASS | `AnkiRatingCommitFlowTest` |
| 39 | No compensating mutation for a committed rating | PASS | scope + committer reading (no undo path exists) |
| 40 | Fake backend dedups by `ReviewCommitId` | PASS | `FakeAnkiBackendTest` (25) |
| 41 | Fake backend rejects conflicting second mutation | PASS | `FakeAnkiBackendTest` |
| 42 | Fake backend matches PC/AnkiDroid contract surface | PASS | `FakeAnkiBackendContractTest` |
| 43 | PC backend semantics unchanged | PASS | byte-identity check (§14) |
| 44 | Commit diagnostics are metadata-only | PASS | code reading (codes §15) + harness timeline (§17) |
| 45 | Rating mapping AGAIN/HARD/GOOD/EASY = 1/2/3/4 | PARTIAL | contract suites green; pinned re-fetch owed (§16) |
| 46 | Unsupported rating refused (`rating_not_offered_by_scheduler`) | PASS | `AnkiStudyInteractionTest`, contract tests |
| 47 | Exactly-once holds under chaos (dup/reorder/loss) | PASS (harness-level, §32) | commit suites + `NetworkChaosTest` 15/15 + 100-seed `StudyAgentChaosTest` + `ReconnectAtEveryPhaseTest` green under real virtual time; production confirmation still owes §0 evidence |
| 48 | Production exactly-once on real AnkiDroid (disposable collection) | **BLOCKED** | no device/CI (verdict §0.2) |

Aggregate: 43 PASS · 4 PARTIAL · 1 BLOCKED. A PASS verdict requires 48/48 with the blocked one
replaced by real device evidence.

## 22. Tooling evidence (for reproduction)

- Harness: `/home/user/tools/harness/build.sh` phases `stubs → main → test → run`; Kotlin 1.9.23
  compiler from the pinned-era fat jar; `android-34.jar` platform stubs; thin shims for
  junit/coroutines-test/turbine/okhttp/androidx (incl. datastore-preferences);
  `-Xfriend-paths` restores Gradle test-module semantics.
- Build: main **187 files, 0 errors**; tests **126 files, 0 errors**; run **1240 tests / 1175
  passed / 65 failed / 0 ignored** (65.7 s). Full run log preserved in the harness work dir
  (`work/run.log`, `work/failures65.txt`).
- The 65 residual failures are the complete list; none are in the rating-commit suites
  (`ReviewCommitLedgerTest`, `AnkiRatingCommitFlowTest`, `AnkiRatingCommitMachineTest`,
  `AnkiDroidRatingCommitTest`, `AnkiDroidCommitVerifierTest`, `FakeAnkiBackendTest`,
  `FakeAnkiBackendContractTest`, `AnkiRatingCommitArchitectureTest`, `AnkiStudyInteractionTest`).

## 23. Verdict, gate lock, and stop

- **GATE 11 = BLOCKED** (see §0 for the three owed items).
- Gate-lock commit `gate11: add exactly-once Anki rating commit reliability`: **not created** (PASS
  not reached).
- **GATE 12 is not started.** Work stops here.

_Prepared by the Arena validation session. No claim in this report rests on CI evidence; CI is
billing-locked and everything above is labelled with its actual evidence class._

---

# PART XII — Capability-driven rating-commit semantics; study/voice cluster green

_Evidence class: JVM harness (tools/jvm-harness, real `kotlinx-coroutines-test` 1.10.2 virtual
time), same limitations as PART X/XI — no Gradle, no device. Full-run logs in the harness work
dir (`run-full2.log`, `run-study.log`)._

## 24. Why this part exists

PART XI left the study/voice cluster red: after the harness was rebuilt on real virtual time and
the fake agent learned PROTOCOL.md §3.4/§3.5 `in_reply_to` correlation, 8 failures remained
across `StudySessionHappyPathTest`, `NetworkChaosTest`, `StudyAgentChaosTest`,
`ReconnectAtEveryPhaseTest`, `SessionReconstructionTest`, `PhoneModeMachineIntegrationTest`,
`StudySessionSimulationTest`, `StudyReducerTest`. Triage exposed one design contradiction and
one wiring gap:

1. **The contradiction.** `StudyReducerTest."PC rating timeout retains original delivery and
   refuses blind replay or next card"` pins the fail-closed contract: an unconfirmed PC rating
   parks the session in `Error(RATING_TIMEOUT)`, retry and next-question are rejected
   (`pc-rating-unconfirmed`), only a correlated receipt advances. `StudyAgentChaosTest."a dropped
   rating acknowledgement times out and leaves a retryable state"` and
   `ReconnectAtEveryPhaseTest."reconnecting while the rating is in flight does not rate again"`
   demand the opposite surface: a *retryable* `WaitingForRating` the user can act on. Both are
   right — for different agents.
2. **The wiring gap.** PART XI built the resolver (`commitSemanticsFromAgent`,
   `PcRatingReplayPolicy.automaticReplayAllowed` in `core/anki/CommitSemanticsValidation.kt`) but
   every machine/reducer/reconciler call site passed `null`: advertised capabilities were never
   frozen into session state, so `automaticReplayAllowed` was permanently false and the
   replay-safe branch was unreachable in production code.

## 25. Design: freeze advertised semantics at session start, branch on them at uncertainty

- `StudyEvent.UserStartRequested` gained `agentCapabilities: AgentCapabilities?` (default null).
  `StudySessionMachineRepository.startOrBlock` fills it from
  `connectionRepository.connectionSnapshot` — the capabilities the agent advertised at handshake
  (`LEGACY_V1` when a v1 agent negotiated nothing, `fromStrings` otherwise, null when no
  handshake happened).
- `handleStart` freezes `commitSemanticsFromAgent(event.agentCapabilities)` into
  `SessionMachineState.commitSemantics` **before the first mutation of the session**. A later
  capability refresh can never flip replay safety mid-turn; `commitSemanticsFromAgent` clamps
  overclaims (no `COMMIT_RECONCILIATION` ⇒ never authoritative; nothing advertised ⇒
  `UNVERIFIED`/`AT_MOST_ONCE_FAIL_CLOSED`).
- **Rating watchdog timeout** (`handleTimeout`, `RATE_CARD`): if
  `PcRatingReplayPolicy.automaticReplayAllowed(state.commitSemantics)` — the agent proved
  `review_commit_idempotency` — the turn returns to `WaitingForRating` with a retryable
  `RATING_TIMEOUT` error and `ledger.markRatingFailed(retryable=true)`; the original pending
  delivery is retained so a late correlated ack still resolves the turn. Otherwise the pinned
  fail-closed behaviour is untouched. **In neither branch does the client ever resend by
  itself**: retry is a user action, replays carry the same deterministic `review_commit_id`, and
  progression stays blocked until a receipt or the user's retry resolves the turn.
- **Reconnect with a rating in flight** (`SessionReconciler`): the same branch. Replay-safe ⇒
  `WaitingForRating` + retryable marker (a legal resume state the user can act on); otherwise ⇒
  `Error(RATING_TIMEOUT)` fail-closed, receipt-or-nothing. The reconciler still never resends.
- `handleRatingSaved` accepts a correlated ack in `WaitingForRating` as well — required only for
  the retryable rollback, where the original `pendingAction` is deliberately kept; correlation
  rules (turn id, `in_reply_to`/`messageId`, expected rating) are unchanged, and an ack without
  a matching pending is still rejected.

## 26. Honest doubles: the fake agent implements what it advertises

- `FakeStudyServer` now advertises `review_commit_idempotency` **and genuinely implements it**:
  receipts are stored per logical commit id (`review_commit_id ?: review_turn_id`); a replay is
  answered from the stored receipt correlated to the retry's `messageId`, the deck does not
  advance twice, the card is not counted twice, and the current question is re-pushed in case
  the original push was lost. A retry that changed the rating receives the first committed
  rating back — client-side correlation then fails closed (`unexpected-rating-ack`), which is
  the honest answer for a conflicting replay. `advertisedCapabilities` is settable so a test can
  play a legacy agent.
- `FakeConnectionRepository.connectionSnapshot` became settable
  (`advertiseCapabilities(caps, protocolVersion)`); the harness wires it from the fake server so
  the machine freezes exactly what the transport advertised.
- Reducer unit tests construct bare states (no capabilities) ⇒ `commitSemantics == null` ⇒ the
  pinned fail-closed contract keeps passing verbatim. The chaos/reconnect harnesses advertise
  the capability ⇒ the retryable contract passes. The contradiction is resolved by evidence, not
  by picking a side.

## 27. Half-duplex and state-hygiene repairs found by the 100-seed chaos sweep

Each item below was a distinct seeded-chaos failure (seed reported in the run log), fixed in the
reducer/machine and re-verified across all 100 seeds:

1. **Repeat is turn-window-only.** `handleRepeat` now rejects while paused/pausing/finished/idle
   and once the evaluation is on screen (seed 100: a paused session spoke again and stranded the
   evaluation in `SpeakingQuestion` — `evaluation-in-illegal-phase`). The repeat re-speak cancels
   an open microphone first (`CancelRecognition` before `Speak`) and clears
   `activeRecognitionEffectId` in the published state.
2. **Evaluation feedback cancels the mic.** A re-evaluation can arrive while the rating window's
   microphone is open (seed 113: TTS/STT overlap); `handleEvaluation` now emits
   `CancelRecognition("evaluation")` before the feedback speak and retires the recognition
   effect id in the published state.
3. **Route-restore re-speak** got the same half-duplex pairing.
4. **Pause/resume state hygiene.** The pause transitions publish with
   `activeSpeechEffectId`/`activeRecognitionEffectId` cleared (the cancels are in the same
   transition's effects) — `paused-with-open-mic` can no longer trip at publication. The
   reconciler's generic path does the same for a **reconciled** pause (seed 141: PTT opened a
   mic during `Recovering`, the snapshot said paused, the published `Paused` state still held
   the effect id).
5. **PTT while paused is rejected** (seed 101: `PttStarted` in `Paused` opened a mic on a paused
   session). PTT during `Recovering` stays allowed — that is precisely the path that must work
   when no automatic window is open.
6. **Route-loss pause → resume repeats the question.** `UserPauseRequested` gained
   `routeLoss: Boolean`; `ResumeContext` records `routeLossBeforePause`; resuming such a pause
   from a question/answer-window phase restarts in `SpeakingQuestion` and repeats the question
   once on the new route (§96/§97) instead of silently reopening a microphone for a question the
   user never heard (`PhoneModeMachineIntegrationTest."losing headphones mid-question…"`).
   Later turn stages still resume through `safeRestartPhase` unchanged.
7. **Skip retires the evaluation** (seed 128 family): `handleSkip` clears `cardTurn.evaluation`
   — the same rule `handleRatingSaved` follows — so `WaitingForFirstCard` never shows feedback
   from a dead turn; a late evaluation for a skipped/rated turn is rejected
   (`no-answer-in-flight`) in `WaitingForFirstCard` (`StudyReducerTest."skip invalidates old
   events"`).
8. **Invariant allow-list completed, not weakened.** `evaluation-in-illegal-phase` now also
   accepts `Error(RATING_TIMEOUT)` (the fail-closed freeze deliberately keeps the turn and its
   evaluation visible while awaiting a receipt) and `Resuming` (the paused turn's feedback
   survives pause→resume; seed 128).

## 28. Turn identity is epoch-scoped; the Anki scheduler id stays verbatim

`CardTurn.generateTurnId` now epoch-qualifies server turn ids (`"$epoch:$serverTurnId"`).
Rationale: turn ids key the submission ledger, turn-ownership checks and stale-callback
rejection; a restarted server session may legitimately reuse `turn-1`, and a ghost callback from
epoch N must never match epoch N+1's fresh turn
(`SessionReconstructionTest."callbacks from the previous epoch cannot mutate the new session"`).
The Anki hydration path passes `preserveServerTurnId = true`: the GATE-11 commit pipeline
correlates `ReviewCommitId.turnId` with `cardTurn.turnId`, so that identity remains the
scheduler's, byte for byte (`AnkiStudyInteractionTest` pins both sides).

## 29. Reconnect reconciliation details

- **Inert status frames.** A `SessionStats`/status frame that claims nothing new (server phase
  `UNKNOWN`, same card, session live and connected, not finished/paused) now only refreshes
  counters — no voice effects, no pending-action teardown. Before, every chatty push ran the
  full reconciliation, cancelling the live mic window and re-speaking the question
  (`NetworkChaosTest."chatty duplicate frames do not wake the voice pipeline"`; the 51-utterance
  flood in the PART XI probe). Real reconnect recovery is untouched: it arrives with an explicit
  server phase or finds the session disconnected/recovering, and reconciles in full.
- **The status request is a tracked pending action.** `handleConnectionRestored` now records
  `REQUEST_SESSION_STATUS` as the session's one in-flight action (watchdog + single-pending rule
  see the reconciliation in progress; `StudyReducerTest."connection loss in-flight answer
  requires reconciliation"`), and `handleStatusReceived` retires it plus its timer when the
  authoritative snapshot answers.

## 30. The harness under real virtual time

The doubles were stabilized so that assertions observe defined boundaries, not scheduler races:

- **Mic-window-aware awaits.** `awaitWaitingForAnswer/Rating` wait for the *live* window (the
  acoustic gap opens the mic ~450–560 ms after the phase flip), with escapes for phase-moved-on,
  parked utterances and routes without a usable microphone.
- **`playCard` returns at the turn boundary with the next question caught parked.** The server
  pushes the next question together with the receipt and a real engine would start speaking at
  once; the caller's assertions belong to the turn just rated ("exactly one question + one
  feedback utterance", "no microphone window the user did not ask for", generation advanced).
  `playCard` therefore switches the speech double to PARK across the rating boundary: the card
  change is observable while the new question's utterance has neither started nor completed.
- **PARK means "accepted, not yet voicing".** The fake speech orchestrator no longer claims the
  voice channel for parked requests (no `started`/`speaking` bookkeeping until release) — parked
  ≠ overlapping, which keeps half-duplex observations honest; on release the utterance runs to
  its result immediately, exactly as the chaos drivers expect. The harness's `advance()`
  releases parked utterances in normal COMPLETE mode ("a real engine always terminates
  eventually"); chaos holds PARK explicitly and drives completion by hand, unchanged.
- **PTT retries respect the acoustic gap** (`NetworkChaosTest."a failed send never becomes a
  submitted answer"` advanced 500 ms < the 562 ms phone gap before speaking into the window).
- **Stale bounded-memory assertions updated to the documented bounds.** The 1000-card reducer
  simulation asserted an *unbounded* ledger/history (pre-§21/§137 expectations): now it asserts
  the real bounds (ledger ≤ 33 entries around the prune threshold of 32, live turn always
  survives pruning, history capped at `CARD_TURN_HISTORY_LIMIT = 64`), and the long-session
  simulation uses a timeline capacity (500) smaller than a hundred cards' worth of events so the
  rotation it asserts actually happens.
- `AppLogger` publication is coalesced for INFO rows behind the publish executor; a clean
  (rejection-free) session therefore needs the documented `AppLogger.flush()` before asserting
  on the observable log stream (`DiagnosticsExportPrivacyTest."clearing logs…"` — it previously
  passed only on rejected-event WARN spam, which immediate-publishes).

## 31. Results

- **Study/voice cluster: 190/190 green** (20 classes: HappyPath, NetworkChaos ×15,
  Simulation ×5, Endurance, Idempotency, Chaos (incl. all 100 seeds of the seeded sweep),
  ReconnectAtEveryPhase ×7, SessionReconstruction ×8, PhoneMode, Reducer ×24, Control/Anki
  interaction suites).
- **Named contract tests, all PASS:** "a dropped rating acknowledgement times out and leaves a
  retryable state" · "PC rating timeout retains original delivery and refuses blind replay or
  next card" (unchanged, bare state) · "reconnecting while the rating is in flight does not rate
  again" · "reconnecting while waiting for a rating opens a rating window again" · "callbacks
  from the previous epoch cannot mutate the new session" · "skip invalidates old events" ·
  "connection loss in-flight answer requires reconciliation" · "1000 card simulation no duplicate
  submissions bounded memory" · "a full card turn sends exactly one answer and one rating and
  advances one card" · "chatty duplicate frames do not wake the voice pipeline" · "a paused
  session never opens the microphone".
- **Commit-surface suites all still green** (ReviewCommitLedger, AnkiRatingCommit{Flow,Machine,
  Architecture}, AnkiDroidRatingCommit, AnkiDroidCommitVerifier, FakeAnkiBackend{,Contract},
  ReviewCommit{Chaos,CrashWindow,DurabilityOrder}, AnkiStudyInteraction): the durability order
  (intent → claim → `MUTATION_CALL_ENTERED` durable before the effect → `COMMITTED` → next card)
  and the frozen per-backend guarantees are untouched. The PC client still never auto-resends —
  it merely stops *stranding* the user when the agent proved dedup.
- **Full JVM harness run: 1267 tests / 1221 passed / 46 failed / 111 classes** (baseline this
  gate: 1188/79). Every remaining failure is in the pre-existing non-study catalogue (§20/
  Appendix B) with reasons byte-identical to the baseline triage: StudyControlRepository ×9,
  DashboardRepository ×5, AnkiDroidHealthRepository ×5, ProtocolFuzz ×4, DiagnosticsExportPrivacy
  ×3, AnkiCardRenderController ×3, AnkiDroidReviewSession ×2, AnkiDroidCardMapper ×2,
  AnkiDroidCardGateway ×2, and 11 singles (TtsVoiceSelector, SpeechQueue, SpeechOrchestrator,
  AnkiRenderEvent, ProfileValidator, Idempotency, DashboardUiMapper — incl. the real
  `MCCQE::Cardiology` mapper bug — AnkiMediaResolverPolicy, AnkiCardHydration, AnkiCardFlow,
  ProtocolJson). One flake observed once under full-run load and green on isolated + repeat runs:
  `StudyAudioRouteCoordinatorTest."concurrent device preference and boundary churn…"` (raw
  `Thread`/wall-clock concurrency).
- **Server side:** `server/test_review_commit_store.py` — 21 tests, OK (2 skipped), unchanged.

## 32. Invariant table delta

| # | Invariant | Was | Now | Evidence |
|---|-----------|-----|-----|----------|
| 47 | Exactly-once holds under chaos (dup/reorder/loss) | PARTIAL (unit-level; NetworkChaos residual) | **PASS (harness-level)** | commit suites + NetworkChaos 15/15 + 100-seed chaos + ReconnectAtEveryPhase green under real virtual time (§31) |

No other row changes. Production-level confirmation of row 47 still hinges on the §0 evidence
class (Gradle command run + real device), which this environment cannot produce.

## 33. Verdict — unchanged

**GATE 11 = BLOCKED.** The owed items are exactly the PART X/XI ones:

1. INV-48: real AnkiDroid mutation validation on a disposable collection (no device/emulator in
   this environment).
2. Gradle command evidence (`clean/testDebugUnitTest/lint/assembleDebug/assembleRelease/
   connectedDebugAndroidTest`) — Maven/Gradle mirrors are unreachable from this sandbox; the JVM
   harness is equivalence evidence only.
3. The residual non-study failure catalogue (§20/Appendix B) — 46 failures, untouched by this
   part, reasons unchanged.

What changed: the study/voice/commit behavioural surface this gate is about is now green under
real virtual time, and the rating-timeout semantics are capability-driven instead of
contradictory — fail-closed by default, replay-safe only where the agent froze
`review_commit_idempotency` at session start, and never auto-resending anywhere.

---

# PART XIII — THE RESIDUAL-46 REMEDIATION SWEEP (FULL SUITE GREEN)

Status: complete. This part discharges owed item 3 of §33 (the residual non-study failure
catalogue). Owed items 1 (INV-48 real-device mutation validation) and 2 (Gradle command
evidence) are environmental and remain owed — see §40.

## 34. Scope and method

Run #4 (commit `f109104`) left 1267 tests / 1221 passed / 46 failed. The 46 were triaged into
seven clusters (Appendix B). Each cluster was driven to green in isolation (`run.sh <regex>`),
then the full suite was re-run. Every fix was classified as production-side (the code lied or
crashed on inputs it must tolerate) or test-side (the test encoded a false expectation, a
fixture omission, or fell into a known virtual-time/background-scope trap). No production
behaviour was weakened to make a test pass; where two tests contradicted each other, the side
matching the documented contract and the production enforcement path that already existed was
kept, and the stale side was corrected with a comment saying why.

## 35. Cluster results

| Cluster | Surface | Root causes | Fix side | Result |
|---|---|---|---|---|
| A/E/G | `ProtocolJson`, render, TTS | decode fallback invented values for unknown frames; render/TTS assertions hit virtual-time and priority-rank traps; `ProtocolJson` fallback rewritten to null-for-all except error-frame recovery | production (ProtocolJson) + tests | network 70/70, render 196/196, tts 76/76 |
| ProfileValidator | profile labels | all-digit final label accepted; contract rejects it | production | green |
| B | `StudyControlRepository`, `DashboardRepository` | `advanceUntilIdle()` advances past pending `withTimeoutOrNull` deadlines — production timeouts are correct | tests (`runCurrent()`, 11 sites) + `DashboardUiState.startSummary.deckLabel` = raw `config.activeDeck` (production) | green |
| F | `DiagnosticsRepository` | Session section conditionally omitted; export-privacy asserts ran before harness reset and against padded-column rendering | production (unconditional section) + tests | data 92/92 |
| C | `AnkiDroidHealthRepositoryTest` | vanilla coroutines-test 1.10.2: `advanceUntilIdle()` never runs `backgroundScope`/`stateIn` projections | tests only (`runCurrent` + `advanceTimeBy`) | 13/13 |
| D | `client.anki.*` (9 failures) | see §36 | mixed | 461/461 |

## 36. Cluster D in detail (the Anki surface)

1. **`require()`-in-constructor vs typed refusal.** `BeginReviewRequest` required `limit > 0`
   and `AnkiSessionContext` required collection/deckRef backendId agreement. Both pre-empted
   the backends' own typed enforcement — `InvalidRequest("review_limit_out_of_range")`,
   `SessionInvalid` for a foreign `context.backendId`, `DeckNotFound` against the provider
   listing — by crashing with `IllegalArgumentException` on exactly the inputs those guards
   exist to refuse (INV-ANKI-DET-09). Boundary kept: the *collection*-backendId require stays
   (a collection identity naming another backend than its own context is self-contradictory
   data, not a request); the *deckRef*-backendId require is gone (a cross-backend deck is a
   refusable request); `studySessionId`, `startedAtEpochMs`, collectionKey-consistency requires
   stay. `AnkiDomainModelTest` pinned the limit IAEs — corrected to assert constructibility,
   because `AnkiDroidReviewSessionTest` pins the typed refusal end-to-end and the backend's
   `limit <= 0` branch is unreachable dead code while the constructor throws first.
2. **`identityMatches` — contradiction-only semantics.** A hydrated card is refused only when
   BOTH sides know an identity component (cardId/noteId/cardOrd) and the values differ; null on
   either side is unconfirmed, not contradicted (§53: absence ≠ empty ≠ error). A degraded cell
   (`_id` unreadable) no longer fails a card whose note+ord confirm identity — it surfaces
   `card_id_unreadable` as metadata. Collection key and backendId remain strict equality.
3. **Unit-lie rule for scheduling info.** A negative raw Anki interval is a learning-step
   encoding in minutes; rendering it as `intervalDays = -3` is a unit lie on an informational
   surface (INV-ANKI-CARD-18/19). `schedulingFor` now parses reps/lapses/interval with
   `parseNonNegativeInt`; when nothing readable remains the section is null, never a shell.
4. **Degradation tokens are per-card, not per-side.** `card_speech_text_unavailable` is added
   once per card even when both question and answer lack simple text.
5. **`validateLogicalName` and malformed escapes.** `URLDecoder` throws on a broken percent
   escape (the `"% r"` in `"100% ready.webp"`); that is not a reason to reject the name — no
   conforming decoder turns a broken escape into traversal and a lenient one passes it through
   literally. Validation now continues on the name as-is, and every candidate including the
   literal name is traversal-checked (the old loop only checked decoded forms). All encoded
   traversal cases (`..%2F`, `%2E%2E%2F`, double-encoded) still reject.
6. **Fixture truths corrected (test-side).** Gateway diagnostics asserted
   `questionHtmlLength == 9` for `"<b>Front</b>"` (12). The gateway `cardRow()` omitted the
   queue column the pinned projection always serves, so every hydration carried
   `card_queue_state_unmapped` noise (contract: mapper test — no queue AND no type ⇒ token).
   `AnkiCardFlowTest.realBackend` served an empty deck listing, making every `beginReview` a
   `DeckNotFound` (CCE at the Success cast). The hydration compose test's "unexplained move"
   used home deck `deck-1` — structurally identical to the scheduled deck (all `deck()`
   fixtures are `deck-1`), i.e. accidentally the filtered-deck explanation asserted two lines
   later; corrected to `deck-0`.

## 37. Changed files (24)

Production (12): `AnkiBackend.kt`, `AnkiSessionContext.kt`, `AnkiCardHydration.kt`,
`AnkiMediaResolver.kt`, `AnkiDroidCardMapper.kt`, `ProfileValidator.kt`, `ProtocolJson.kt`,
`DefaultSpeechOrchestrator.kt`, `SpeechQueue.kt`, `TtsVoiceSelector.kt`,
`DiagnosticsRepository.kt`, `DashboardUiState.kt`.
Tests (12): `ProtocolJsonTest.kt`, `IdempotencyTest.kt`, `AnkiCardRenderControllerTest.kt`,
`AnkiRenderEventTest.kt`, `AnkiCardFlowTest.kt`, `AnkiCardHydrationTest.kt`,
`AnkiDomainModelTest.kt`, `AnkiDroidCardGatewayTest.kt`, `AnkiDroidHealthRepositoryTest.kt`,
`DashboardRepositoryTest.kt`, `DiagnosticsExportPrivacyTest.kt`, `StudyControlRepositoryTest.kt`.

## 38. Evidence (JVM harness, this environment's equivalence evidence class — §0)

- Full suite: **1267 tests / 1267 passed / 0 failed / 0 ignored, 111 classes**.
- `client\.anki\.`: **461/461** (was 452/461 at run #4's residual catalogue).
- Commit surface (ReviewCommitLedger/RatingCommitFlow/Machine/AnkiDroidRatingCommit/
  CommitVerifier/Chaos/CrashWindow/DurabilityOrder/Architecture/StudyInteraction +
  FakeAnkiBackend suites): **103/103 across 11 classes** — frozen guarantees untouched:
  AnkiDroid `AT_MOST_ONCE_FAIL_CLOSED`, PC branches on frozen semantics and never
  auto-resends, `END_TO_END_EXACTLY_ONCE` never claimed.
- Main build 194 files / test build 130 files, 0 compile errors.
- Server suite `server/test_review_commit_store.py`: 21 tests OK (unchanged, PART XII §31).

## 39. Invariant table delta

No row changes. The sweep repaired crash-on-refusable-input paths (INV-ANKI-DET-09), tightened
the informational-surface unit rule (INV-ANKI-CARD-18/19) and made degradation metadata honest
(§53 absence semantics); all were already the documented invariants — the code now matches them.

## 40. Verdict

**GATE 11 = BLOCKED** — unchanged, but the owed list is shorter. §33 item 3 (residual failure
catalogue) is **discharged**: the full suite is green. Still owed, both environmental:

1. INV-48: real AnkiDroid mutation validation on a disposable collection/profile (no
   device/emulator/AnkiDroid in this sandbox).
2. Gradle command evidence (`--stop/clean/testDebugUnitTest/lint/assembleDebug/assembleRelease/
   connectedDebugAndroidTest`) — Maven/Gradle mirrors unreachable from this sandbox; the JVM
   harness remains equivalence evidence only.

Production readiness for safe real rating commits is unchanged by this part: the commit state
machine, ledger durability order, crash-window and chaos suites were green before it and remain
green under it.

---

# PART XIV — GATE 11 checkpoint audit (2026-10-06, branch `arena/e9f6f9e5-study-agent-client`, from `22e5026`)

> This part audits the implementation against the twenty execution checkpoints of the GATE 11
> document, adds the evidence those checkpoints ask for, and re-runs everything from a rebuilt
> harness. PARTs X–XIII are kept for history. Where they disagree with this part about *harness
> numbers*, this part was measured on this branch with the harness inputs documented below.

## XIV.0 Verdict

**GATE 11 = BLOCKED**, for the same two environmental reasons PART XIII recorded, and no others:

1. **Real AnkiDroid mutation not run.** No device, emulator, AnkiDroid install or `adb` in this
   environment, so checkpoint 16 (real disposable mutation) and the whole-device half of
   checkpoint 11 stay unexecuted.
2. **Gradle/CI not available.** Every Maven mirror is still unreachable (`repo1.maven.org`,
   `dl.google.com`, `maven.aliyun.com`, `repo.huaweicloud.com`, `mirrors.cloud.tencent.com`,
   `jitpack.io`, `repo.spring.io`, `packages.jetbrains.team`, `plugins.gradle.org` all returned
   `000`). The harness remains equivalence evidence, not CI evidence.

What this part changes: the JVM-side evidence is now **1305/1305 green in 116 classes** (PART XIII
had 1267/1267 in 111), five checkpoint-specific test classes were added, the fake backend gained
the eight named failure modes and the counters checkpoint 4 requires, and the diagnostics surface
now carries the full checkpoint 19 correlation. Checkpoints 1–15 and 17–20 are **implemented and
evidenced on the JVM**; 16 is owed to a real device.

## XIV.1 Harness rebuild in this sandbox (evidence class)

The harness from PART XI could not simply be re-run: PyPI and GitHub are reachable here, Maven is
not. Everything below is reproducible with `tools/jvm-harness/bin/bootstrap-sandbox.sh`, which is
committed with this part (and `bin/prep-coroutines-test.py`, its one non-obvious step).

| Input | Source used | Note |
|---|---|---|
| Java runtime | `jdk4py` wheel (Java 25) | `HARNESS_JAVA` |
| Kotlin compiler 2.3.10-RC | `kotlin-jupyter-kernel` wheel (fat jar) | also ships coroutines-core 1.10.2 + serialization 1.9.0 + plugin |
| `android.jar` (26,361,808 B) | GitHub `Sable/android-platforms`, sparse `android-34` | mockable jar 25,398,684 B via `mockgen` |
| `kotlinx-coroutines-test` 1.10.2 | GitHub `Kotlin/kotlinx.coroutines` tag 1.10.2 | compiled from source; KMP `expect`/`actual` collapsed by the new script; **the jar must carry `jvm/resources/META-INF/services/*`** or every `runTest` fails with `Exception handler was not found via a ServiceLoader` |
| `kotlinx-coroutines-core` 1.10.2 | carved out of the kernel fat jar | the carve must keep `META-INF/kotlinx-coroutines-core.kotlin_module`, or top-level factories resolve to nothing |
| atomicfu | repo shim `shims/atomicfu` | compiled into its own jar; coroutines-test needs it at compile **and** run time |

With those fixed, the three groups PART XI reported as "harness deviations" (79 failures there) are
gone: the entire suite compiles and passes unchanged. The deviations that remain are the version
skews (K2, coroutines 1.10.2, shimmed DataStore/JUnit), listed in the harness README.

**Executed evidence (this branch, this environment):**

| Suite | Result |
|---|---|
| Main compile | 194 files, **0 errors** |
| Test compile | 136 files, **0 errors** |
| Full JVM suite | **1305 tests / 116 classes / 1305 passed / 0 failed / 0 ignored** (8.5 s) |
| Commit surface (19 classes) | **198 / 198** |
| `server/test_review_commit_store.py` | **21 / 21** |
| Whole `server/` suite | **85 passed / 1 failed / 7 skipped** (`python3 -m pytest server -q -o asyncio_mode=auto`, after `pip install pytest-asyncio 'websockets<14'` per the CI job in `docs/GATE_00_BASELINE.md`). The single failure is `test_client.py::test_full_loop`, which is a manual protocol client script rather than a unit test: it connects to a live agent at `ws://127.0.0.1:8765` and fails with `ConnectionRefusedError` when none is running. The 7 skips are 3 integration tests needing a running server and 4 live-provider tests needing API keys. **PART XIII's 26 failures were all the missing pytest-asyncio plugin**, not product defects. |

The earlier `FileNotFoundError` on the mock agent's store file is not reproducible with the async
plugin installed: `server/test_review_commit_store.py` runs `21 passed` in isolation and in the full
suite. It was an artefact of the missing-plugin cascade, not a port collision.

## XIV.2 What this part added

| File | Checkpoint | What it proves |
|---|---|---|
| `app/src/test/.../anki/ReviewCommitTransitionTest.kt` (16 tests) | 2 | the pure transition table, including every forbidden move: COMMITTED terminal, AMBIGUOUS↛SUBMITTING, no downgrade, attempt count strictly +1 |
| `app/src/test/.../anki/fake/FakeCommitMode.kt` | 4 | the eight named modes, each with the guarantee it implies |
| `app/src/test/.../study/FakeCommitLaboratoryTest.kt` (10 tests) | 4 | the mandatory *mutation happens → response disappears* case, plus one test per mode and the counter contract |
| `app/src/test/.../study/ReviewCommitConcurrencyTest.kt` (6 tests) | 8 | 100 simultaneous rating events → 1 accepted / 1 row / 1 effect; 10 racing executors → 1 dispatch; GOOD+HARD → exactly one accepted; 100 racing claims → 1 `Claimed` + 99 `InFlight` |
| `app/src/test/.../study/ReviewCommitPropertyTest.kt` (3 tests) | 15 | the five global properties over 12 seeds × 24 steps of mixed actions, plus the frozen-ambiguity case |
| `app/src/test/.../study/CommitDiagnosticsCorrelationTest.kt` (2 tests) | 19 | phase markers carry the transaction identity; a safe failure never claims a committed result |
| `FakeAnkiBackend` (`mode`, counters) | 4 | `deliveryCount`, `mutationAttemptCount`, `backendEffectCount`, `nextCardCount`, `redeliveryCount` |
| `CommitPhaseSink`, `RatingCommitStarted`, `RatingCommitPrepared`, `AnkiRatingCommit`, `commitMetadata` | 19 | `REVIEW_COMMIT_*` markers with commit/backend/session/attempt, frozen guarantee on the event, and exactly the eight correlation keys the timeline keeps |
| `AnkiStudyEvent.RatingCommitPrepared` + `ANKI_RATING_SELECTED` | 19 | the durable row publishes the guarantee the ledger froze with it, so a preflight refusal — which never claims the write and therefore never emits `RatingCommitStarted` — is still diagnosed against its real guarantee, and "prepared" now means *durably persisted*, not "the user tapped a rating" |

## XIV.3 Checkpoint audit

| # | Checkpoint | Status | Evidence (code → proof) |
|---|---|---|---|
| 1 | Transaction domain only | ✅ | `ReviewCommitId` `core/anki/AnkiSessionContext.kt:42`; `ReviewCommitState`/`CommitAttemptPhase`/`ReviewCommitRecord` `core/anki/ReviewCommit.kt`; `CommitGuaranteeLevel`/`CommitSemantics`/`CommitReceipt` `core/anki/CommitSemantics.kt`; coordinator outcome `AnkiCommitOutcome` `core/study/AnkiStudyInteraction.kt:78` (the roadmap's `ReviewCommitOutcome`, named once for the whole commit surface — `Committed` / `Failed` / `Ambiguous` / `PersistenceFailure`). No Android/UI imports (`AnkiDomainIsolationTest`, 4 tests). Suggested (`Evaluation.suggestedRating`) / selected (`AnkiStudyInteraction.selectedRating`) / committed (`ReviewCommitRecord.state == COMMITTED`) are three different facts. **Deviation:** the files live in `core/anki/`, not `core/anki/commit/` — one commit surface, no parallel package (PART XI §XI.1 "no parallel concepts"). |
| 2 | Pure transition engine | ✅ | `ReviewCommitTransitions.apply/allowed` `core/anki/ReviewCommitTransition.kt:74/135`; the whole required table plus rejections is now pinned by `ReviewCommitTransitionTest` (16). Unreachable commands return a typed `null` (never a state change), and `allowed` refuses a write before storage. |
| 3 | Durable commit ledger | ✅ | `ReviewCommitLedger` `core/anki/ReviewCommitLedger.kt:161` behind `ReviewCommitStore`; production `DataStoreReviewCommitStore` `data/anki/DataStoreReviewCommitStore.kt:29`; ordering asserted by `ReviewCommitDurabilityOrderTest`; process recreation, one-commit-per-turn, unique ids, serialized writes and "storage failure before mutation ⇒ zero backend calls" in `ReviewCommitLedgerTest` (23). Content-free: `ReviewCommitLedgerCodec` stores identity, rating, counters and timing only. |
| 4 | Fake failure laboratory | ✅ | `FakeCommitMode` (8 modes) + counters; `FakeCommitLaboratoryTest` (10) including the mandatory case: 1 delivery, 1 mutation attempt, 1 physical effect, state AMBIGUOUS, `redeliveryCount == 0`, `nextCardCount` frozen. `OUTCOME_UNKNOWN` (no effect, unknown) still fails closed; `IDEMPOTENT_REPLAY` re-delivers with no second effect; `NON_IDEMPOTENT_REPLAY` would re-apply and only the ledger stops it. |
| 5 | Coordinator | ✅ | `AnkiStudyEffectExecutor.commit` `core/study/AnkiStudyEffectExecutor.kt:144-286`: prepare → evidence → claim → `markMutationEntered` → backend → durable response → durable terminal → outcome. It calls no `nextCard`, writes no machine state, renders nothing, starts no TTS/STT (architecture test + `AnkiRatingCommitArchitectureTest`). Duplicate/conflict/ambiguous replays answered from the ledger before any call. |
| 6 | Next-card barrier | ✅ | The only `Next` effect after a commit is `StudyReducer.kt:566` (`if (outcome is AnkiCommitOutcome.Committed)`), and the resolver only emits it after the durable record; `ReviewCommitDurabilityOrderTest` shows `backend:nextCard` strictly after `durable:COMMITTED`; `AnkiRatingCommitMachineTest` proves rating selection and SUBMITTING never advance; the new property test asserts `nextCardCount ≤ COMMITTED rows + 1` after every step. |
| 7 | StudySessionMachine integration | ✅ | Backend-neutral phases `SubmittingRating` / `RatingCommitFailed` / `ReconciliationRequired` / `CommitPersistenceFailure` (`SessionPhase.kt`); touch, voice, headset and keyboard all reach the same `StudyEvent.UserRateCard` → same pipeline (harness D, machine test "touch voice and keyboard bursts"). AI suggestion cannot bypass selection: only `AnkiStudyEvent.SelectRating` for a scheduler-offered rating creates a commit (`P only ratings the scheduler offered can be committed`). |
| 8 | Concurrency protection | ✅ | Ledger mutex + version CAS `ReviewCommitLedger.kt:335/512`; 100-event, 10-executor, GOOD+HARD, 100-claim and 50-prepare races all in `ReviewCommitConcurrencyTest`; the losing rating is never delivered (asserted against `recordedCommits`). No reliance on disabled buttons, debounce or recomposition. |
| 9 | Recovery policy | ✅ | `ReviewCommitRecoveryPolicy.classify` `core/anki/ReviewCommitRecoveryPolicy.kt:15` implements the decision model; v1 rows cannot masquerade as PREPARED; `ReviewCommitRecoveryPolicyTest` (4) + `ReviewCommitCrashWindowTest` (4) + restart tests in the ledger and flow suites. |
| 10 | Reconciliation | ✅ | `AnkiStudyEffectExecutor.reconcile` `:295` → `backend.reconcileCommit` `:342`; result space is exactly `Applied` / `NotApplied` / `StillAmbiguous` / `Unsupported` / `Unavailable` (no "probably"); AnkiDroid identity is refused reconciliation (`H2b`) and stays AMBIGUOUS (Unresolved); timeout bounded (`H5`). |
| 11 | Real AnkiDroid adapter | ✅ (JVM) / ⛔ (device) | One entry point: `AnkiDroidRatingCommitter.commit` → `gateway.submitAnswer` `data/anki/ankidroid/AnkiDroidRatingCommitter.kt:112` → `safeUpdate` → the single `contentResolver.update` `data/anki/ankidroid/AnkiDroidProviderClient.kt:340`. No retry loop (an `attempt` is executed once), no direct UI-provider call, no DB access, no exception-text guessing (`AnkiDroidErrorMapper`/`AnkiDroidFailureClassifier` + `AnkiDroidRatingCommitTest` 18). The AGAIN/HARD/GOOD/EASY→1/2/3/4 mapping is pinned to API v2.24.1 and asserted; **still owed:** confirmation against a real install. |
| 12 | Guarantee declaration | ✅ | `CommitSemantics.ANKIDROID = AT_MOST_ONCE_FAIL_CLOSED` `core/anki/CommitSemantics.kt:38`; `CommitSemanticsValidation` clamps any adapter that over-claims for an AnkiDroid identity; docs and diagnostics use the same four-level vocabulary. `END_TO_END_EXACTLY_ONCE` is never claimed for AnkiDroid or for the PC link. |
| 13 | PC backend audit | ✅ | Client sends `review_commit_id` + `review_turn_id` + card + rating (`ProtocolMessages.kt:176`, `StudyReducer.kt:975`); server table `server/review_commit_store.py` returns the previous result for same id+payload and rejects a different payload with `COMMIT_CONFLICT`; 21/21 tests including a real-process restart. The server→Anki window is documented, not claimed: PC stays "whatever the welcome advertises, never END_TO_END_EXACTLY_ONCE". |
| 14 | Process-crash matrix | ✅ | `CommitFaultPoint` (10 points, `core/anki/CommitFaultInjection.kt`) + `ReviewCommitCrashWindowTest`, `ReviewCommitDurabilityOrderTest`, chaos with a crash injected at every point, and the PART XI §XI.4 table. The "before nextCard" case is covered by the durable-COMMITTED restart tests (`L2`, `M`) and the new property invariant. |
| 15 | Property tests | ✅ | `ReviewCommitPropertyTest`: `logicalCommitCount(turn) ≤ 1`, `activeMutationAttempts(session) ≤ 1`, `nextCard → COMMITTED`, `AMBIGUOUS → no new attempt`, `COMMITTED → always COMMITTED`, re-checked after every step of seeded sequences. |
| 16 | Real disposable AnkiDroid test | ⛔ BLOCKED | No device/emulator/adb/AnkiDroid here. The test plan (test profile/deck/cards, duplicate input, barrier, scheduler progression, AGAIN/HARD/GOOD/EASY) is unchanged from PART XI and remains the gate's outstanding item. |
| 17 | Endurance & chaos | ✅ (JVM) | `ReviewCommitChaosTest`: 150 seeds × 2 identities with duplicate input, safe failure, response loss, ambiguity, restart, stale callbacks and next-card failure; the new property suite adds 12 deterministic mixed-action sequences. Zero duplicate logical commits, zero blind ambiguous retries, zero barrier violations. (The gate's "1000 transactions" is met as 300 seeded scenario runs, not 1000 single-turn runs — stated, not glossed.) |
| 18 | UI safety | ✅ | `RatingCommitRecoveryUi` (`core/study/RatingCommitRecoveryUi.kt`): "Saving rating", "Could not save the rating. Nothing was changed in Anki. Retry is safe…" (only after a proven-not-applied failure), "The rating may already have been saved. Study-Agent will not submit it again until the review state is verified."; rating controls stay disabled (`ratingControlsEnabled = false` default) for SAVING, UNCONFIRMED, CHECKING and PERSISTENCE_FAULT, and there is no blind Retry for ambiguity. (The Compose-facing `RatingControlsPolicyTest` is outside the harness — stated, not implied; the wording and the enable/disable policy are additionally asserted through the machine's `ratingCommitRecovery` in `AnkiRatingCommitMachineTest`.) |
| 19 | Diagnostics | ✅ | New correlation: `commitMetadata` now emits exactly the eight keys the timeline keeps — commit, session, backend, guarantee, rating, committed, state(+failure token), attempt — plus the event's own `turnId`; `REVIEW_COMMIT_CREATED/PREPARED/CALL_ENTERED/LOCAL_COMMIT_PERSISTED` markers carry the transaction identity; events `ANKI_RATING_SELECTED` (intent, nothing frozen yet), `ANKI_COMMIT_PREPARED` (durable row + frozen guarantee), `ANKI_COMMIT_STARTED`, `ANKI_COMMIT_COMMITTED`, `ANKI_COMMIT_SAFE_FAILURE`, `ANKI_COMMIT_FAILED`, `ANKI_COMMIT_AMBIGUOUS`, `ANKI_COMMIT_PERSISTENCE_FAILURE`, `ANKI_RECONCILIATION_STARTED/RESULT`, `ANKI_NEXT_CARD_AFTER_COMMIT_STARTED`. The guarantee is read from the frozen ledger value (and clamped fail-closed for an AnkiDroid identity that over-claims), never restated from configuration. No card content (`AnkiRatingCommitMachineTest`, `CommitDiagnosticsCorrelationTest`). |
| 20 | Final code audit | ✅ | The ten-item location map below. |

## XIV.4 Checkpoint 20 — locate in one minute

| # | Question | Answer |
|---|---|---|
| 1 | Where `ReviewCommitId` is created | `AnkiReviewTurn.commitId` — `core/anki/AnkiBackend.kt:222` (`backend + study session + review turn`); `stableKey` in `core/anki/AnkiSessionContext.kt:42-49` |
| 2 | Where transaction intent is persisted | `ReviewCommitLedger.prepare` — `core/anki/ReviewCommitLedger.kt:298`, called by the coordinator at `core/study/AnkiStudyEffectExecutor.kt:157`; the durable row is then published with its frozen guarantee at `:175` |
| 3 | Where `CALL_ENTERED` is persisted | `ReviewCommitLedger.markMutationEntered` — `core/anki/ReviewCommitLedger.kt:363`, invoked from the boundary callback at `core/study/AnkiStudyEffectExecutor.kt:237` (AnkiDroid adapter calls it at `AnkiDroidRatingCommitter.kt:110`) |
| 4 | The exact irreversible AnkiDroid mutation | `appContext.contentResolver.update(uri, contentValues, null, null)` — `data/anki/ankidroid/AnkiDroidProviderClient.kt:340`, reached only through `AnkiDroidRatingCommitter.commit` → `gateway.submitAnswer` (`:112`) |
| 5 | Where an unknown outcome becomes AMBIGUOUS | `classifyCommitFailure` `core/anki/CommitFailureClassification.kt:34-52` + the coordinator's post-boundary mapping `core/study/AnkiStudyEffectExecutor.kt:257,263-291` + `ReviewCommitLedger.markAmbiguous` `:438`; restart case in `ReviewCommitRecoveryPolicy` `:27` |
| 6 | Where COMMITTED becomes durable | `ReviewCommitLedger.complete` `core/anki/ReviewCommitLedger.kt:387` (from `AnkiStudyEffectExecutor.kt:287`), classification in `ReviewCommitTransitions.terminalFromResponse` `core/anki/ReviewCommitTransition.kt:56` |
| 7 | Where `nextCard` is gated | `StudyReducer.kt:573` — the only post-commit `AnkiStudyEffect.Next`, emitted only for a durable `AnkiCommitOutcome.Committed`; recovery blocking in `ReviewCommitLedger.recoveryBlocker` `:281` |
| 8 | Where duplicate input is rejected | `StudyReducer.handleRateCard`/`selectRating` (`StudyReducer.kt:430,958`) via `ledger.canBeginRating`, then `ReviewCommitLedger.prepare` conflict `:308` and the one-winner claim CAS `claim` `:335`; legal state moves only in `ReviewCommitTransitions.allowed` `:135` |
| 9 | Where restart recovery occurs | `ReviewCommitLedger.loadedLocked` `:562` (classifies every non-COMMITTED row through `ReviewCommitRecoveryPolicy`) and the executor's in-process recovery in `reconcile` `core/study/AnkiStudyEffectExecutor.kt:295-325` |
| 10 | Where reconciliation occurs | `AnkiStudyEffectExecutor.reconcile` `:295` → `backend.reconcileCommit` `:342` → AnkiDroid evidence in `data/anki/ankidroid/AnkiDroidCommitEvidence.kt`; server-phase reconciliation in `core/study/SessionReconciler.kt:13` |

## XIV.5 GO / NO-GO checklist (this part)

**GO items — met:** domain stable ✅ · ledger durable ✅ · coordinator tested ✅ · AMBIGUOUS blocks
replay ✅ · next-card barrier proven ✅ · recovery survives restart ✅ · chaos + property suites pass ✅

**NO-GO items — none triggered:** post-boundary failure is never retryable by default ✅ · no
next-card race with persistence ✅ · two rating events cannot reach the backend ✅ · AMBIGUOUS cannot
be resubmitted (no UI path; ledger refuses) ✅ · restart does not lose an unresolved transaction
(persisted, surfaced, blocking) ✅ · guarantee level matches evidence ✅

**Still owed to close the gate (unchanged):** AnkiDroid adapter verified on a real disposable
collection (checkpoint 16 / INV-48) and Gradle/CI evidence. Until then the AnkiDroid guarantee
stays **AT_MOST_ONCE_FAIL_CLOSED**, which is a statement about local duplicate suppression, one
mutation path, safe pre-mutation retry and no blind uncertain retry — not about crash-safe
exactly-once execution on the device.
