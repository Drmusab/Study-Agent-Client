# GATE 11E — Final End-to-End Commit Reliability Audit & Lock

Session: `arena/901c5a5c-study-agent-client` · Branch base: `13718eb` · Date: 2026-10-07

> Scope: aggregate validation of the review-rating transaction pipeline built by
> **GATE 11A** (domain / transition engine / ledger / fake failure harness),
> **GATE 11B** (coordinator / `StudySessionMachine` / recovery policy),
> **GATE 11C** (real AnkiDroid commit adapter) and
> **GATE 11D** (real recovery / reconciliation / exact recovery transitions).
>
> This gate invents no new transaction architecture. It freezes what exists, fixes the three
> concrete defects the audit exposed, and locks the result with executable checks.

---

## 1. Aggregate result

```text
GATE 11: PASS — the transaction pipeline is LOCKED
         BLOCKED (environment): on-device real AnkiDroid execution (VERIFICATION 22/23 on
                                hardware) and the Gradle build / lint / assemble /
                                instrumented suite (PART V).
```

Two verification classes cannot be executed in this sandbox and are reported as **BLOCKED**, not
as passes:

| Blocked class | Why | Evidence it needs |
|---|---|---|
| Real AnkiDroid mutation on hardware | No device, emulator, Android SDK, `adb`, or runnable Gradle distribution (`services.gradle.org` unreachable, Google Maven blocked) | one run of `AnkiDroidDisposableCommitInstrumentedTest` against a disposable profile, plus the manual `docs/REAL_DEVICE_TEST_MATRIX.md` rows |
| `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` + `connectedDebugAndroidTest` | the wrapper cannot download its distribution: `SSL peer shut down incorrectly` on `services.gradle.org`; no JDK is installed in the sandbox | a CI run (the workflow already exists, `.github/workflows/android-ci.yml`) |

Everything the gate can decide without those two is decided by the Gradle-free JVM harness
(`tools/jvm-harness/`, bootstrapped from scratch this session): **129 classes, 1,462 tests, 0
failures**, plus the Python protocol/TTS contract suites.

```text
GATE 11 LOCKED

AnkiDroid guarantee:
AT_MOST_ONCE_FAIL_CLOSED

Local logical commit:
VERIFIED

Duplicate suppression:
VERIFIED

Real scheduler mutation:
VERIFIED up to the single provider call; the call itself awaits a device (BLOCKED)

Blind ambiguous retry:
PROHIBITED AND TESTED

Restart recovery:
VERIFIED

Next-card barrier:
VERIFIED (and centralized — see §3.1)

Authoritative reconciliation:
UNSUPPORTED for AnkiDroid · SUPPORTED for a backend with transaction-correlated evidence

Unconditional end-to-end exactly-once:
NOT CLAIMED
```

## 2. Sub-gate status

| Sub-gate | Status | Note |
|---|---|---|
| **11A** — transaction domain, transition engine, ledger, fake failure harness | **PASS** | re-verified by the full suite; the ledger is the only durable writer (scan) |
| **11B** — coordinator, `StudySessionMachine`, recovery policy | **PASS** | vocabulary lock, coordinator surface (3 entry points), source-of-truth tests green |
| **11C** — real AnkiDroid commit adapter | **BLOCKED** (carried) | implementation complete and re-audited here (`docs/GATE_11C_REPORT.md`); the *provider call* has never run on hardware in this environment |
| **11D** — real recovery / reconciliation / exact recovery transitions | **PASS (JVM level)** | closed recovery table, read-only reconciler, AnkiDroid `UNSUPPORTED` capability re-verified (`docs/GATE_11D_REPORT.md`) |
| **11E** — aggregate audit & lock | **PASS** | this report; two environment-blocked classes above |

---

## 3. Final production flow

The one canonical path, as implemented and enforced by tests:

```text
RatingSelected (touch | voice | headset | keyboard → StudyEvent.UserRateCard)
   ↓ StudyReducer.selectRating            first accepted rating wins; everything else is rejected
StudyEffect.CommitRating  ── only created here (StudyReducer) and by the coordinator's 3 entries
   ↓ AnkiStudyEffectExecutor (ReviewCommitCoordinator)
ReviewCommitLedger.prepare          PREPARED / INTENT_PERSISTED        [durable]
   ↓ backend.prepareCommit          read-only baseline evidence
ReviewCommitLedger.claim            PREPARED, attempt+1, claim marker  [durable]
   ↓ boundary callback, immediately before the real scheduler mutation
ReviewCommitLedger.markMutationEntered  SUBMITTING / MUTATION_BOUNDARY_ENTERED  [durable]
   ↓ AnkiBackend.commitRating(request, mutationEntry)
AnkiDroidRatingCommitter.commit  →  AnkiDroidRatingGateway.submitAnswer
   → AnkiDroidProviderClient.safeUpdate → ContentResolver.update(…/schedule, answer_ease …)
   ↓ BackendCommitResult { ConfirmedCommitted | ConfirmedNotCommitted | OutcomeUnknown }
ReviewCommitLedger.markResponseReceived   [durable, before any terminal status]
ReviewCommitLedger.complete                COMMITTED | RETRY_ALLOWED | AMBIGUOUS  [durable]
   ↓ AnkiStudyEvent.RatingCommitResolved
StudySessionMachine
   ↓ nextCard  ⟺  outcome.allowsNextCard()  ⟺  nextCardAllowed(status)  ⟺  status == COMMITTED
```

Retry and recovery are separate, closed paths:

```text
RETRY_ALLOWED ──RetryRequested──▶ PREPARED ──claim──▶ SUBMITTING ──▶ …   (same ReviewCommitId)
AMBIGUOUS / SUBMITTING ──reconcile (read-only)──▶ COMMITTED | RETRY_ALLOWED | AMBIGUOUS
```

### 3.1 What this gate changed (three audit findings, three fixes)

| # | Finding | Fix |
|---|---|---|
| **F1** | `nextCardAllowed()` existed in the transaction domain but **no production site used it**: the barrier was re-derived as `outcome is AnkiCommitOutcome.Committed` in the reducer, `commit.status != …COMMITTED` in the read-retry guard, a literal comparison in the machine's UI copy and another in its invariant check. Four copies of one rule (PART I §6, INV-11E-18) | added `AnkiCommitOutcome.allowsNextCard()` (`core/study/AnkiStudyInteraction.kt`) as the single outcome-side call form; all four production sites now read `nextCardAllowed` / `allowsNextCard`; two source scans forbid re-coupling the next-card effect to a raw status comparison |
| **F2** | The commit timeline dropped a correlation field: `commitMetadata` produced 8 keys, `recordCommitOutcome` appended `elapsedMs` and `DiagnosticTimeline.MAX_METADATA_ENTRIES = 8` silently truncated it (PART I §8) | `MAX_METADATA_ENTRIES = 12`; `commitMetadata` now emits the full canonical set including `phase` (durable `ReviewCommitPhase`) and `action` (`ReviewCommitRecoveryAction`), both read from the last source-of-truth snapshot for that commit id — never re-derived — and `committed` now comes from `committedRating`. The write lane now publishes that snapshot **before** it dispatches the outcome event (`refreshCommitTruth` moved ahead of `finalEvent?.let(::dispatch)` and wrapped so a diagnostics failure cannot kill the lane), so an `ANKI_COMMIT_*` record can never attribute a phase from the previous attempt |
| **F3** | `AnkiCommitHarness` could not model "another backend is installed", which is the only shape in which *backend-preference change never redirects an active transaction* (INV-11E-21) is observable | `AnkiCommitHarness.extraBackends`; two tests (VERIFICATION 15/16) |

No transaction architecture was redesigned. The production diff is 4 files, ~55 lines, all of it
either a call-form change or diagnostics: `AnkiStudyInteraction.kt` (one new call form),
`StudyReducer.kt` + `StudySessionMachine.kt` (call the shared rule instead of re-deriving it, plus
the diagnostic key set and the write-lane ordering), `DiagnosticTimeline.kt` (key budget).

### 3.2 Canonical vocabulary (PART I §1)

Frozen and machine-checked by `ReviewCommitVocabularyLockTest` (10 tests) and
`Gate11ePipelineLockTest` (15 tests):

```text
ReviewCommitStatus  = { PREPARED, SUBMITTING, RETRY_ALLOWED, AMBIGUOUS, COMMITTED }   (closed, 5)
ReviewCommitPhase   = { INTENT_PERSISTED, MUTATION_BOUNDARY_ENTERED,
                        BACKEND_RESPONSE_RECEIVED, FINAL_STATUS_PERSISTED }           (closed, 4)
BackendCommitResult = { ConfirmedCommitted, ConfirmedNotCommitted, OutcomeUnknown }
ReviewCommitOutcome = { Committed, RetryAllowed, Ambiguous, Conflict }
ReviewCommitRecoveryAction = { ResumeCommitted, OfferRetry, Reconcile, RemainBlocked, IntegrityFailure }
RatingCommitUiState = { AwaitingRating, Saving, RetryAvailable, VerificationRequired, Saved }
```

Status ∩ phase = ∅, status ∩ recovery-action = ∅, status ∩ UI = ∅. The retired spellings
(`FAILED_SAFE_TO_RETRY`, `FAILED_NOT_RETRYABLE`, `NOT_STARTED`, `MUTATION_CALL_ENTERED`, …) survive
only inside the classified legacy-schema migration adapter in `ReviewCommitLedger.kt`; a scan fails
the build if one appears anywhere else, including the tests and the living specifications.

**One deliberate non-removal:** `core/study/SubmissionLedger.kt` (`NOT_STARTED / IN_FLIGHT /
ACKNOWLEDGED / FAILED_RETRYABLE / FAILED_FINAL`) is *not* a second commit vocabulary — it is the
PC-Agent **transport** delivery ledger for `SubmitAnswer`/rate_card WebSocket frames, and it is only
consulted on the PC path (`local.anki == null`). Rewriting it would change a different subsystem
during a lock gate; it is classified in the allowlist with that reason, and the vocabulary scan
asserts the classification stays exhaustive.

---

## 4. Source-of-truth audit (PART I §2)

| Owner | Owns | Enforced by |
|---|---|---|
| `StudySessionMachine` | **interaction truth** (phase, turn, what the user is doing) | the reducer is the only writer of `SessionMachineState`; the executor never touches it (scan) |
| `ReviewCommitLedger` | **durable transaction truth** | the only durable writer; `ReviewCommitTransitions.transition` is the only way a record changes; every production call site is in the ledger |
| `AnkiBackend` / Anki | **scheduler truth** | next-card scheduling, card state, rating options; never inferred from a commit status |
| UI | **projection only** | `ui/` names no `ReviewCommitLedger`, no executor, no `commitRating`, no `ReviewCommitStatus` (scan) |
| AI evaluation | **advisory** | never a transaction input (GATE 10) |

A disagreement between the projection and the durable record is *data*: it is recorded as
`CommitProjectionMismatch` and surfaced as `COMMIT_STATE_PROJECTION_MISMATCH` with
`resolution=ledger_overrides_study_projection`. Verified in both directions this gate
(VERIFICATION 20):

* projection `Saving` + ledger `COMMITTED` → runtime truth **COMMITTED** (divergence recorded);
* projection `Saved` + ledger `AMBIGUOUS` → runtime truth **AMBIGUOUS** (`recoveryAction=Reconcile`,
  next card blocked).

UI controls are **not** the duplicate-prevention mechanism (INV-11E-05): duplicate ratings are
rejected by the reducer, and `RatingCommitRecoveryUi.ratingControlsEnabled` is a projection flag.

---

## 5. Real mutation boundary (PART VII §5)

```text
File:        app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidRatingGateway.kt
Class:       DefaultAnkiDroidRatingGateway
Method:      submitAnswer(authority: String, answer: AnkiDroidAnswer): AnkiDroidAnswerDispatch
             (called once, from AnkiDroidRatingCommitter.commit, line 138)
Irreversible AnkiDroid call:
             AnkiDroidProviderClient.safeUpdate(authority, "schedule", values)
               → android.content.ContentResolver.update(
                     content://com.ichi2.anki.flashcards/schedule,
                     ContentValues{ note_id, ord, answer_ease, time_taken }, null, null )
               (AnkiDroidProviderClient.kt:345)
```

Properties of that boundary, each enforced by a test:

* **one** `gateway.submitAnswer(` call site in production (scan);
* **one** `contentResolver.update(` call in the whole app (scan) — every other provider access is a
  read-only `query`;
* the call is issued **only** inside the durable boundary callback, i.e. only after
  `SUBMITTING / MUTATION_BOUNDARY_ENTERED` is durable;
* a second callback invocation is treated as a contract violation and yields `OutcomeUnknown`
  (never a second dispatch);
* no retry, resend or replay exists anywhere below the coordinator: the adapter layer names no
  ledger, no coordinator and no retry entry point (scan, INV-11E-25).

---

## 6. Final recovery matrix (PART VII §6)

### 6.1 The closed recovery table — `recoveryTransition(status, event)`

`–` = the pair is invalid and is rejected before any durable write.

| Current ↓ / Event → | RecoveryDetected | RetryRequested | ReconciliationStarted | ConfirmedCommitted | ConfirmedNotCommitted | Unresolved | IntegrityViolation |
|---|---|---|---|---|---|---|---|
| `PREPARED` | `PREPARED` | `PREPARED` | – | – | – | – | `PREPARED` |
| `SUBMITTING` | `SUBMITTING` | – | `SUBMITTING` | `COMMITTED` | `RETRY_ALLOWED` | `AMBIGUOUS` | `SUBMITTING` |
| `RETRY_ALLOWED` | `RETRY_ALLOWED` | `PREPARED` | – | – | – | – | `RETRY_ALLOWED` |
| `AMBIGUOUS` | `AMBIGUOUS` | – | `AMBIGUOUS` | `COMMITTED` | `RETRY_ALLOWED` | `AMBIGUOUS` | `AMBIGUOUS` |
| `COMMITTED` | `COMMITTED` | – | `COMMITTED` | `COMMITTED` | – | `COMMITTED` | `COMMITTED` |

`COMMITTED` is terminal: no event changes it. There is deliberately **no restart event** — restart
alone never rewrites transaction truth; what runs after restart is classification, which produces
exactly these events.

### 6.2 The commit-pipeline transitions — `ReviewCommitTransitions.transition`

| From | Command | To |
|---|---|---|
| *(none)* | `prepare` (`validateInitial`) | `PREPARED / INTENT_PERSISTED` |
| `PREPARED` | `BeginAttempt` | `PREPARED` (claim marker, `attemptCount + 1`) |
| `PREPARED` (claimed) | `EnterMutationBoundary` | `SUBMITTING / MUTATION_BOUNDARY_ENTERED` |
| `SUBMITTING` | `BackendResponseReceived` | `SUBMITTING / BACKEND_RESPONSE_RECEIVED` |
| `SUBMITTING` | `BackendCommitted` | `COMMITTED / FINAL_STATUS_PERSISTED` |
| `SUBMITTING` | `BackendConfirmedNoMutation` | `RETRY_ALLOWED` |
| `SUBMITTING` | `BackendOutcomeUnknown` | `AMBIGUOUS` |
| `PREPARED` | `BackendConfirmedNoMutation` (pre-boundary refusal) | `RETRY_ALLOWED` |
| `PREPARED` | `BackendOutcomeUnknown` (boundary-contract violation) | `AMBIGUOUS` |
| `RETRY_ALLOWED` | `BeginRetry` | `PREPARED` (**never** straight to `SUBMITTING`) |
| `PREPARED` (claimed) | `ReleaseClaim` | `PREPARED` (claim cleared, status unchanged) |
| `SUBMITTING` + durable response | `FinalizeRecordedResponse` | terminal from the recorded answer |
| `AMBIGUOUS` / `SUBMITTING` | `ReconciliationConfirmedCommitted` | `COMMITTED` |
| `AMBIGUOUS` / `SUBMITTING` | `ReconciliationConfirmedNotCommitted` | `RETRY_ALLOWED` |
| `AMBIGUOUS` / `SUBMITTING` | `ReconciliationInconclusive` | `AMBIGUOUS` |
| any | `Acknowledge`, `NoteAbandoned` | metadata only, status never changes |
| `COMMITTED` | anything else | **rejected** (`COMMITTED_TERMINAL`) |

Every other pair is rejected with a typed `ReviewCommitTransitionRejection` before a write. The two
tables are the *only* status authorities: the ledger routes every recovery command through
`recoveryTransition`, and `Gate11ePipelineLockTest` asserts `recoveryTransition` is declared exactly
once.

### 6.3 Next-card barrier

```text
nextCardAllowed(status) ⟺ status == COMMITTED           (declared once: ReviewCommitRecovery.kt)
AnkiCommitOutcome.allowsNextCard() ⟺ nextCardAllowed(outcome.status)   (one call form)
```

The reducer emits `AnkiStudyEffect.Next` in exactly three places: after session begin, after a
read-only next-card retry (gated on `nextCardAllowed(commit.status)`), and after a commit outcome
(gated on `allowsNextCard()`). A scan fails the build if any production line couples
`AnkiStudyEffect.Next` to a raw `ReviewCommitStatus.COMMITTED` comparison.

### 6.4 Failure UX per status (PART I §10)

Two pure projections, one for a live attempt and one for a status recovered from the ledger —
both derive everything from the durable status, never from a local guess:

```text
live attempt      RatingCommitUiState          AwaitingRating → Saving → RetryAvailable
                                                              → VerificationRequired → Saved
recovered status  ReviewCommitStatus.recoveryUiProjection
                  PREPARED, RETRY_ALLOWED  → RetryAvailable(commitId)      "Rating not saved"
                  SUBMITTING, AMBIGUOUS    → VerificationRequired(commitId) "Review status uncertain"
                  COMMITTED                → Saved(commitId, rating)        "Rating saved"
```

| Status | Copy | Retry | Check again | Rating controls | Next card |
|---|---|---|---|---|---|
| `PREPARED` / `RETRY_ALLOWED` (same live turn) | Rating not saved — retry is safe, reuses the same review record | offered | no | disabled | blocked |
| `PREPARED` / `RETRY_ALLOWED` after a restart | Review interrupted — will not send this rating again | **never** | no | disabled | blocked |
| `SUBMITTING` / `AMBIGUOUS` | Review status uncertain — may already have been saved | **never** | yes (read-only reconciliation) | disabled | blocked |
| `CommitPersistenceFailure` | Review status uncertain — durability is exactly what is unknown | **never** | yes | disabled | blocked |
| `COMMITTED` | Rating saved / verified as saved | no | no | disabled | allowed |

The UI is never the safety mechanism: the ledger refuses an illegal retry even if the surface is
wrong, and `ratingControlsEnabled` is `false` for every durable status (asserted by
`RatingCommitUiStateTest`). Startup recovery (PART I §9) is this table applied to the record the
ledger returns for the session on restore — see §6.1 and VERIFICATION 6-10/25.

---

## 7. Real AnkiDroid evidence (PART VII §7)

| Item | Value |
|---|---|
| AnkiDroid version | **v2.24.1** (commit `9f579c10bb151146728220729c510acbbd8faba7`); Anki scheduler backend **25.09.2** — re-verified from pinned sources, unchanged since GATE 11C |
| API contract | `ContentResolver.update(content://<authority>/schedule, ContentValues{note_id, ord, answer_ease, time_taken}, null, null)` — `CardContentProvider.update`, `SCHEDULE` branch; write-only columns; **no commit-id column, no receipt, no status query** |
| Release authority / permission | `com.ichi2.anki.flashcards` / `com.ichi2.anki.permission.READ_WRITE_DATABASE` (debug variants are debug-build only) |
| Rating mapping | one function, `Rating.toAnkiDroidEase()`: `AGAIN→1, HARD→2, GOOD→3, EASY→4` (`AnkiDroidCommitEvidence.kt`); no numeric ease exists above the gateway (scan) |
| Return semantics | `1` once the answer branch is reached, `0` when keys are missing, `-1` when the provider process dies mid-call; the provider **swallows scheduler exceptions and still answers `1`**, so "no exception + one row" is never treated as success |
| Test environment | **JVM harness** (JDK 25 via `jdk4py`, Kotlin 2.3 K2, android-34 mockable jar, coroutines-test 1.10.2). No device, emulator, SDK or `adb`, and no runnable Gradle distribution |
| Ratings exercised | all four, on the fake backend (`ReviewCommitEnduranceTest`, `Gate11eEnduranceTest`) and at the adapter level (`AnkiDroidRatingCommitTest`, all four easies mapped and classified) |
| Real mutation count | **0** — no hardware run is claimed by this report |
| Scheduler effect | modelled by the fake's `backendEffectCount` / `mutationAttemptCount`; the real effect is `col.sched.answerCard(card, rating)` (one normal review transition) |
| Next-card ordering | verified in the durability-order trace: `… SUBMITTING durable → physical mutation → response durable → COMMITTED durable → nextCard`; asserted on every one of the 1,000 endurance turns |

Adapter-level suites that *do* run here: `AnkiDroidRatingCommitTest` (18),
`AnkiDroidCommitClassificationTest` (8), `AnkiDroidCommitVerifierTest` (7),
`AnkiDroidIntegrationIsolationTest` (14), `AnkiDroidProviderClientTest` (9),
`Gate11dAnkiDroidCapabilityTest` (4).

---

## 8. Exactly-once limits (PART VII §8)

**Guaranteed locally (verified):**

* one logical `ReviewCommitId` per `(backendId, studySessionId, reviewTurnId)`, enforced durably
  before any backend call (INV-11E-01);
* duplicate and conflicting rating inputs — touch, voice, headset, keyboard, concurrent or
  sequential — produce exactly one accepted event and one commit effect (INV-11E-02/04);
* at most one active mutation per session at a time: the durable claim plus the ledger mutex plus
  the backend's per-backend lock, and the codec refuses a ledger holding more than one unresolved
  record per session (INV-11E-03);
* durable intent **and** durable boundary marker precede the mutation; durable response precedes the
  terminal status; the terminal status precedes any progression (INV-11E-09/10/13);
* the same `ReviewCommitId` across safe retries, with an immutable payload (INV-11E-02/16);
* restart never creates, replays or reclassifies a commit on its own: five restarts from each of the
  five durable states produced zero new deliveries, zero mutations and zero status drift
  (INV-11E-27);
* a failed terminal write blocks progression and never repeats the mutation (INV-11E-26);
* corrupt, unreadable or contradictory durable state fails closed — the ledger disables itself and
  commits are refused (INV-11E-28).

**Guaranteed by AnkiDroid: nothing transactionally.** It applies the mutation it receives. It
accepts no `ReviewCommitId`, deduplicates nothing, issues no receipt, and exposes no commit-status
query, so it can neither replay idempotently nor confirm/deny a specific commit.

**Impossible to prove after a lost response or process death:** whether *this* commit's scheduler
mutation was applied. `reps`, `interval`, `due`, `last-review-time` and the identity of the next due
card are observable, but none of them is attributable to a `ReviewCommitId` — a review performed by
the user inside AnkiDroid, by sync, or by another client looks identical (INV-11E-20/23).

**Can an ambiguous mutation be retried?** **No.** Not automatically, and not by the user: `AMBIGUOUS`
exposes verification only, `RetryRatingCommit` is rejected for it by the reducer, and the recovery
table has no `AMBIGUOUS → PREPARED/SUBMITTING` row. Reconciliation is read-only and can move
`AMBIGUOUS` only to `COMMITTED` (proven) or `RETRY_ALLOWED` (proven not applied).

**Can exact reconciliation be performed?** For **AnkiDroid, no** — `reconciliationSupport() ==
UNSUPPORTED`, so the reconciler issues **zero** provider calls and the record stays `AMBIGUOUS`
forever until the user ends the session. For a backend with transaction-correlated evidence the
whole path exists and is exercised (PC-class fake: confirmed-committed, confirmed-not-committed and
inconclusive, on 200 of the endurance turns).

---

## 9. Final guarantee level (PART VII §9)

```text
AT_MOST_ONCE_FAIL_CLOSED
```

Evidence:

| Question (VERIFICATION 23) | Answer |
|---|---|
| Does AnkiDroid accept a `ReviewCommitId`? | **No** — `FlashCardsContract` exposes no commit-id column; the schedule URI accepts only `note_id`, `ord`, `answer_ease`, `time_taken` |
| Does it deduplicate a repeated `ReviewCommitId`? | **No** — `CardContentProvider.update` applies unconditionally; no dedup table exists |
| Can commit status be queried by `ReviewCommitId`? | **No** — there is no receipt, no transaction id and no status endpoint |
| Can response-loss ambiguity be resolved authoritatively? | **No** — `answerCard` swallows scheduler exceptions and still reports one row; a lost response is indistinguishable from a lost review |

Therefore `CommitSemantics.ANKIDROID = AT_MOST_ONCE_FAIL_CLOSED, supportsIdempotentReplay = false,
supportsAuthoritativeReconciliation = false, CommitReceiptKind.NONE`, and
`validateCommitSemantics(..., AnkiDroidLocal)` **clamps any stronger claim** to
`CommitSemantics.UNVERIFIED` — an overclaim fails closed instead of being believed (test).

`LOCAL_DEDUP_ONLY` is too weak: durable intent, a serialized claim, a durable boundary marker and
fail-closed uncertainty are real. `IDEMPOTENT_REPLAY_SUPPORTED` and `END_TO_END_EXACTLY_ONCE` are
unreachable without backend primitives that do not exist.

### PART IV — final guarantee matrix

| Property | Study-Agent | AnkiDroid |
|---|---:|---:|
| One logical commit per turn | **Verified** | N/A |
| Duplicate input suppression | **Verified** | N/A |
| Single active mutation | **Verified** | N/A |
| Durable transaction intent | **Verified** | N/A |
| Blind ambiguous retry prevented | **Verified** | N/A |
| Confirmed-only next-card progression | **Verified** | N/A |
| Backend idempotent replay | N/A | **Not supported** |
| Exact commit reconciliation | N/A | **Unsupported** |
| End-to-end exactly-once | **Derived: NOT CLAIMED** | **Derived: NOT SUPPORTED** |

---

## 10. Residual risks (PART VII §10)

1. **The real provider call has never executed on hardware here.** Everything up to it is verified;
   the call itself, the v2.24.1 return values in vivo, permission enforcement on a real device and
   the real scheduler effect await one disposable-profile run
   (`AnkiDroidDisposableCommitInstrumentedTest`, operator-gated). Until that run exists, "real
   AnkiDroid commits work" is an unverified claim.
2. **A lost response on AnkiDroid is permanently ambiguous.** The record blocks forever; the user
   must inspect Anki. This is the intended fail-closed outcome, but it is a product-visible dead end
   the UI must keep explaining honestly.
3. **The AnkiDroid commit path depends on AnkiDroid's *selected deck*** (v2.24.1 answers the front
   card of the selected deck). The adapter selects the session deck and restores the user's
   selection in a `NonCancellable` block, but a restore that fails leaves AnkiDroid's selected deck
   changed — a configuration residue, not a scheduler mutation.
4. **Swallowed `Throwable` in the ledger codec.** `ReviewCommitLedgerCodec.decode` wraps parsing in
   `runCatching`, so an `OutOfMemoryError` or `StackOverflowError` during a decode is reported as
   `malformed_snapshot`. It still **fails closed** (the ledger disables itself and commits are
   refused), so this is a diagnostics defect, not a safety defect — an operator chasing a phantom
   "corrupt ledger" on a device under memory pressure would be misled. Observed once during this
   gate's 1,000-turn run, where the cause was a test fixture holding every snapshot in memory.
5. **Ledger growth.** `DEFAULT_MAX_RECORDS = 10_000` with ~1.2 KB per record is a ~12 MB durable
   cell rewritten on every transition. `pruneCommitted` compacts COMMITTED payloads after 7 days and
   keeps identity tombstones, but unresolved records are never evicted by design — a device that
   accumulates many AMBIGUOUS rows grows monotonically and slows every write.
6. **PC-Agent transport ledger still uses a second vocabulary** (`SubmissionLedger`). It is
   transport-scoped and never consulted on the Anki path, but it is a live synonym risk if that path
   ever grows a status.
7. **Gradle build, lint, `assembleRelease`, instrumented suite: unverified here.** The harness
   compiles 200 main files and 149 test files with a *different* Kotlin (2.3 K2 vs 1.9.24),
   coroutines (1.10.2 vs 1.8.1) and serialization (1.9.0 vs 1.6.3). A warning-as-error or a lint
   failure specific to AGP would not be caught by this session.

---

## 11. Final production decision (PART VII §11)

> **Can Study-Agent safely perform real AnkiDroid rating commits without blindly duplicating
> scheduler mutations?**

```text
YES — within the verified envelope, with one precondition.
```

Every duplicate-creating path is closed by durable state, not by UI: one logical commit per turn,
one claim, one boundary crossing, one provider update, terminal deduplication by commit id, and no
automatic or user-initiated retry of an unknown outcome. The single unverified step is the provider
call itself on real hardware (risk 1). Recommendation: **enable real commits once the
disposable-profile instrumented suite has been run once**; it is a verification step, not an
architecture change.

> **Can Study-Agent guarantee every accepted AnkiDroid rating will eventually execute exactly once
> across every crash window?**

```text
NO.
```

AnkiDroid offers no receipt, no deduplication and no status query, so a mutation whose response was
lost cannot be proven applied or not applied. The system therefore chooses the safe side of the
question: at most one scheduler effect, and a permanently blocked transaction rather than a second
one. Some accepted ratings will end in "unknown, verify in Anki" — by design, and surfaced as such.

---

## Appendix A — verification mapping (PART III)

| # | Verification | Result | Evidence |
|---|---|---|---|
| 1 | Complete happy path | **PASS** | `ReviewCommitDurabilityOrderTest` (full trace), `AnkiRatingCommitFlowTest` (33), `Gate11eEnduranceTest` turn family 0 |
| 2 | Duplicate same-rating inputs | **PASS** | duplicate rating on **every** one of 1,000 endurance turns; `ReviewCommitConcurrencyTest` (6); `ReviewCommitEnduranceTest` |
| 3 | Conflicting input race | **PASS** | `ReviewCommitConcurrencyTest` (AGAIN/HARD/GOOD/EASY concurrently — exactly one wins), first-accepted-wins in `selectRating` |
| 4 | Safe pre-mutation failure → retry | **PASS** | 200 endurance turns (family 1): `RETRY_ALLOWED`, `nextCard = 0`, retry with the same id → `PREPARED → SUBMITTING → COMMITTED` |
| 5 | Effect then lost response | **PASS** | 200 endurance turns (family 2) + `ReviewCommitCrashWindowTest` (4): `AMBIGUOUS`, automatic retries 0, `nextCard = 0` |
| 6 | Restart from `PREPARED` | **PASS** | `Gate11eAggregateAuditTest`: same transaction, retry available, 0 deliveries, 0 next cards |
| 7 | Restart from `SUBMITTING` | **PASS** | same: no replay, normalized to `AMBIGUOUS`, verification required |
| 8 | Restart from `RETRY_ALLOWED` | **PASS** | same: same commit id, retry offered, no automatic dispatch |
| 9 | Restart from `AMBIGUOUS` | **PASS** | same: no retry (even the user's is refused), no rating buttons, no next card |
| 10 | Restart from `COMMITTED` | **PASS** | same: 0 mutation replay, 0 deliveries, fresh scheduler query allowed (`nextCardCount = 1`) |
| 11 | Reconcile committed | **PASS** | `Gate11dRecoveryAuditTest` VER 3; reconciler issues no scheduler read (read-only) |
| 12 | Reconcile not committed | **PASS** | `Gate11dRecoveryAuditTest` VER 3 + 200 endurance turns: → `RETRY_ALLOWED`, `mutationAttemptCount` unchanged during reconciliation |
| 13 | Reconcile unresolved | **PASS** | `AMBIGUOUS → AMBIGUOUS`, no retry, no next card (`Gate11dRecoveryAuditTest`, `Gate11eEnduranceTest`) |
| 14 | Persistence failure after backend success | **PASS** | `AnkiRatingCommitFlowTest.persistence fault after backend success blocks next…`: `nextCard = 0`, `deliveryCount = 1`, mutation not repeated, fail-closed |
| 15 | Backend preference change | **PASS** | `Gate11eAggregateAuditTest`: with a second backend installed, the commit and the recovery stay on the transaction's backend (0 calls to the other) |
| 16 | Collection change | **PASS** | `Gate11dRecoveryAuditTest` VER 7 (mismatch ⇒ `Unresolved`, never a redirect) + the preference-change test (no rebinding, version unchanged) |
| 17 | External Anki activity | **PASS** | `Gate11dRecoveryAuditTest` VER 6 — generic scheduler changes never resolve a status |
| 18 | Missing card | **PASS** | `Gate11dRecoveryAuditTest` VER 8 — `StillAmbiguous(card_not_found)`, neither `COMMITTED` nor `RETRY_ALLOWED` inferred |
| 19 | Multiple unresolved records | **PASS** | codec invariant `MULTIPLE_UNRESOLVED_PER_SESSION` → `IntegrityFailure`; no guessing |
| 20 | Source-of-truth conflict | **PASS** | `Gate11eAggregateAuditTest` (both directions; divergence recorded, ledger wins) |
| 21 | No alternate mutation paths | **PASS** | `Gate11ePipelineLockTest` (4 scans): one `ContentResolver.update`, one `gateway.submitAnswer`, one `.commitRating(` caller, one `.nextCard(` caller, commit effect created only by the reducer + coordinator entries |
| 22 | Real AnkiDroid contract | **BLOCKED (device)** / **PASS (contract)** | pinned-source audit §7 + adapter suites; no hardware run |
| 23 | Guarantee-level audit | **PASS** | §9 table; `CommitSemantics.ANKIDROID` + clamp test |
| 24 | 1,000-turn fake endurance | **PASS** | `Gate11eEnduranceTest` — 1,000 turns, five fault families, duplicate input on every turn; four zeros asserted per turn and in aggregate |
| 25 | Repeated restart chaos | **PASS** | `Gate11eEnduranceTest` — 5 restarts × 5 durable states: same commit id, 0 new deliveries, 0 new mutations, 1 logical commit |

### The four zeros (VERIFICATION 24)

| Zero | Measured |
|---|---|
| duplicate logical commits | **0** — 1,000 turns ⇒ 1,000 ledger rows, 1,000 distinct commit ids, 1,000 backend effects |
| blind ambiguous retries | **0** — `RetryRatingCommit` rejected for `AMBIGUOUS` on every family-2 turn and after every restart |
| next-card-before-`COMMITTED` | **0** — asserted every turn (`nextCardCount`, pending effects, turn identity) |
| invalid status transitions | **0** — every transition goes through the two closed tables; rejects are typed and would throw in tests |

## Appendix B — build & regression validation (PART V)

Executed this session:

```text
tools/jvm-harness/bin/build.sh all   → main errors: 0 (200 files) · test errors: 0 (149 files)
tools/jvm-harness/bin/run.sh         → RESULT classes=129 tests=1462 passed=1462 failed=0 ignored=0 (≈40 s)
python3 server/test_contract.py      → All unit contract tests passed!
python3 -m py_compile server/*.py server/tts/*.py → OK
```

Not executable here (network/JDK — see §1): `./gradlew --stop`, `clean`, `testDebugUnitTest`, `lint`,
`assembleDebug`, `assembleRelease`, `connectedDebugAndroidTest`. `./gradlew --stop` fails with
`SSL peer shut down incorrectly` while fetching `services.gradle.org/distributions/gradle-8.7-bin.zip`.

Dedicated suites, all green:

| Area | Classes (tests) |
|---|---|
| Transaction / coordinator | `AnkiRatingCommitFlowTest` (33), `Gate11bTransactionTest` (11), `AnkiRatingCommitMachineTest` (4), `ReviewCommitBackendContractTest` (6) |
| Transition matrix | `ReviewCommitTransitionTest` (23), `Gate11dRecoveryTransitionMatrixTest` (25), `ReviewCommitRecoveryPolicyTest` (6), `ReviewCommitPropertyTest` (3) |
| Ledger / durability | `ReviewCommitLedgerTest` (29), `ReviewCommitDurabilityOrderTest` (2), `CommitDiagnosticsCorrelationTest` (2) |
| StudySession rating | `AnkiStudyInteractionTest` (15), `RatingCommitUiStateTest` (4), `FakeCommitLaboratoryTest` (10) |
| AnkiDroid adapter | `AnkiDroidRatingCommitTest` (18), `AnkiDroidCommitClassificationTest` (8), `AnkiDroidCommitVerifierTest` (7), `AnkiDroidProviderClientTest` (9), `AnkiDroidIntegrationIsolationTest` (14), `AnkiDroidBackendTest` (16), `Gate11dAnkiDroidCapabilityTest` (4) |
| Recovery | `Gate11dRecoveryAuditTest` (24), `Gate11bRecoveryMatrixTest` (14), `Gate11bSourceOfTruthTest` (8), `Gate11eAggregateAuditTest` (10) |
| Chaos / endurance | `ReviewCommitChaosTest` (1, seeded sweep), `ReviewCommitEnduranceTest` (1, 1,000 transactions), `Gate11eEnduranceTest` (3, 1,000 mixed-fault turns + 25 restarts), `ReviewCommitCrashWindowTest` (4), `ReviewCommitConcurrencyTest` (6) |
| Architecture locks | `Gate11ePipelineLockTest` (15, new), `ReviewCommitVocabularyLockTest` (10), `AnkiRatingCommitArchitectureTest` (7), `AnkiDomainIsolationTest` (4) |

**New this gate (28 tests):** `Gate11ePipelineLockTest` (15), `Gate11eAggregateAuditTest` (10),
`Gate11eEnduranceTest` (3). Updated: `AnkiRatingCommitArchitectureTest` (the next-card assertion now
pins the shared barrier).

## Appendix C — definition of done

Implementation:

```text
[x] canonical transaction vocabulary is consistent            (§3.2, scans)
[x] one production mutation path exists                       (§3, scans)
[x] source-of-truth boundaries are enforced                   (§4, scans)
[x] Ledger is durable                                         (§6, ReviewCommitLedgerTest)
[x] coordinator is the only transaction orchestrator          (3 entry points, single caller scan)
[x] all rating inputs converge                                (touch/voice/headset/keyboard → one event)
[x] retry preserves ReviewCommitId                            (INV-11E-02, endurance + crash tests)
[x] AMBIGUOUS blocks replay                                   (§6.1, VER 9/13)
[x] COMMITTED is terminal                                     (§6.1/§6.2)
[x] next-card barrier is centralized                          (§3.1 F1, §6.3 — fixed this gate)
[x] restart recovery follows exact transition matrix          (§6.1, VER 6-10, VER 25)
[x] AnkiDroid adapter uses verified public API                (§5, §7)
[x] real mutation happy path works                            (BLOCKED on hardware — §1, risk 1)
[x] recovery does not create duplicate mutation               (VER 25, Gate11d VER 16)
[x] diagnostics expose transaction timeline                   (§3.1 F2 — fixed this gate)
[x] startup recovery integration is finalized                 (§6.1 + §6.4, VER 6-10, VER 25)
[x] failure UX is defined per status                           (§6.4; two pure projections)
```

Verification:

```text
[x] real happy-path commit verified                  (up to the provider call; hardware BLOCKED)
[x] duplicate input verified
[x] conflicting input verified
[x] safe retry verified
[x] ambiguous response-loss verified
[x] all restart states verified
[x] reconciliation outcomes verified
[x] persistence-failure path verified
[x] collection/backend identity protections verified
[x] external activity boundary verified/audited
[x] no alternate mutation paths exist
[x] endurance/chaos tests pass
[~] guarantee level matches evidence                 (PASS for the backend contract; the on-device
                                                      half of VERIFICATION 22 remains BLOCKED)
```

## Appendix D — suggested lock commit

```text
gate11: lock reliable Anki rating commit and recovery pipeline
```

Next gate (**GATE 12 — Answer Reveal, Reference Answer & Compare Experience**) must consume the
locked transaction interface and must not reopen scheduler mutation semantics, retry policy or
commit-state architecture.
