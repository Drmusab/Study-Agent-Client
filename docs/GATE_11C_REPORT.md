# GATE 11C — AnkiDroid rating commit report

**Report date:** 2026-10-07 (second verification pass; implementation base `d3b2d7a`, PR #39)
**Decision:** **GATE 11C: BLOCKED — NOT LOCKED**

Implementation is complete and every environment-independent verification item passed again in this
session, independently reproduced. The Gate stays blocked because this sandbox has no Android
device, emulator, Android SDK, `adb`, or any runnable Gradle distribution: the wrapper host
`services.gradle.org` and the Google Maven hosts are outside the sandbox egress allowlist. No real
AnkiDroid mutation is claimed by this report.

---

## 1. Result

```text
GATE 11C: BLOCKED
```

Per PART V: implementation complete, but real-backend verification is unavailable → **BLOCKED**,
not PASS. GATE 11C is therefore **not locked** (PART VII).

## 2. Implementation Summary

Only what exists in production code at `d3b2d7a`. **This session changed no production code**;
the audit below confirmed every PART I step is already satisfied, so no edits were required.

* **Gateway implemented** — `DefaultAnkiDroidRatingGateway`
  (`data/anki/ankidroid/AnkiDroidRatingGateway.kt`): the only component that writes to the
  AnkiDroid provider. One typed answer entry point (`submitAnswer`), one physical write primitive
  call per invocation, a single-write `Mutex` permit, and an in-flight flag
  (`writeInFlight`) that blocks safe-retry conclusions while an earlier write may still land.
* **Commit executor** — `AnkiDroidRatingCommitter`
  (`data/anki/ankidroid/AnkiDroidRatingCommitter.kt`): pre-boundary validation chain (backend/
  session/turn/card identity, positive note/ordinal, bound deck, collection keys, permission,
  collection readiness, capability, queue front), the durable `mutationEntry()` gate immediately
  before the answer, and evidence-based classification. It never decides retry policy and never
  owns StudyState or the ledger.
* **Rating mapper** — `Rating.toAnkiDroidEase()`
  (`data/anki/ankidroid/AnkiDroidCommitEvidence.kt`): the single domain-rating → public-ease
  mapping (AGAIN→1, HARD→2, GOOD→3, EASY→4); no raw ease integer exists anywhere above it.
* **Failure classifier** — `AnkiDroidCommitResultClassifier` + `AnkiDroidCommitVerifier`
  (`data/anki/ankidroid/AnkiDroidCommitResultClassifier.kt`, `AnkiDroidCommitEvidence.kt`): the one
  adapter-level exception/outcome classification boundary producing exactly the three
  `BackendCommitResult` cases.
* **CommitSemantics** — `CommitSemantics.ANKIDROID`
  (`core/anki/CommitSemantics.kt`): `AT_MOST_ONCE_FAIL_CLOSED`, `supportsIdempotentReplay = false`,
  `supportsAuthoritativeReconciliation = false`, `CommitReceiptKind.NONE`, validated at backend
  construction by `validateCommitSemantics` (`AnkiDroidBackend.kt:96`).
* **`BackendCommitResult`** (`core/anki/AnkiResults.kt`): `ConfirmedCommitted(receipt = null for
  AnkiDroid)` / `ConfirmedNotCommitted(reason)` / `OutcomeUnknown(reason)`. No Boolean, no
  `Result<Unit>`.
* **Production call path (no alternate route):**

  ```text
  StudySessionMachine
    -> AnkiStudyEffectExecutor (ReviewCommitCoordinator)
    -> AnkiBackend.commitRating(request, mutationEntry)
    -> AnkiDroidBackend.commitLocked            (reviewMutex; identity validation)
    -> AnkiDroidRatingCommitter.commit          (preconditions; mutationEntry() gate)
    -> AnkiDroidRatingGateway.submitAnswer      (single answer entry point)
    -> AnkiDroidProviderClient.safeUpdate
    -> ContentResolver.update(content://<authority>/schedule, values, null, null)
  ```

* Repository-wide scan re-run this session: the only `ContentResolver.update` call in the app is
  `AnkiDroidProviderClient.safeUpdate` (`AnkiDroidProviderClient.kt:345`); every other provider
  access is `query` (read-only). No insert/applyBatch/bulkInsert/call write path exists.

## 3. Verification Summary

### Tests performed (JVM harness — this session, independently reproduced)

The Gradle-free harness (`tools/jvm-harness/`) was bootstrapped from scratch in this session
(JDK 25 runtime via `jdk4py`, Kotlin 2.3 K2 compiler, android-34 mockable jar, coroutines-test
1.10.2 compiled from the pinned tag). It is equivalence evidence, not a Gradle run.

| Suite | Result |
|---|---|
| Main compilation | **0 errors** (198 Kotlin files) |
| Test compilation | **0 errors** (143 Kotlin files) |
| Full JVM suite | **123 classes, 1,381 tests: 1,381 passed, 0 failed, 0 ignored** (16.6 s) |
| Focused GATE 11C adapter classes (`AnkiDroidRatingCommitTest`, `AnkiDroidCommitClassificationTest`, `AnkiDroidIntegrationIsolationTest`) | **3 classes, 40 tests passed** |
| Ledger / durability-order / recovery (`ReviewCommitLedgerTest`, `ReviewCommitDurabilityOrderTest`, `Gate11bTransactionTest`, coordinator recovery) | **4 classes, 48 tests passed** |
| Source-of-truth (`Gate11bSourceOfTruthTest`) | **8 tests passed** |

Verification-step mapping (details in the sections below):

| Step | Outcome |
|---|---|
| V1 API contract evidence | **PASS** — re-verified this session from the pinned source (see §4) |
| V2 rating mapping | **PASS** (JVM tests; every domain rating → audited ease) |
| V3 invalid-rating rejection | **PASS** (JVM: `ratings the scheduler did not offer ... are refused before mutation`; provider calls = 0) |
| V4 single mutation entry point | **PASS** (repository scan + tests) |
| V5 boundary ordering | **PASS** (code inspection + `ReviewCommitDurabilityOrderTest`) |
| V6 success classification | **PASS** (JVM harness) |
| V7 proven-no-mutation classification | **PASS** (JVM harness) |
| V8 unknown-outcome classification | **PASS** (JVM harness; crash windows remain fake-evidence only) |
| V9 no internal retry | **PASS** (gateway counter tests; source inspection) |
| V10 backend identity lock | **PASS** (JVM; durable `commitId.backendId` drives dispatch) |
| V11 card identity lock | **PASS** (JVM; turn ref equality + queue-front + state identity) |
| V12 adapter/next-card isolation | **PASS** (source scan + JVM tests) |
| V13 disposable real environment | **BLOCKED** (no device/emulator/AnkiDroid in sandbox) |
| V14 real happy-path commit | **BLOCKED** (opt-in instrumentation test ready, not runnable here) |
| V15 real duplicate-input suppression | **BLOCKED** for the real provider; JVM duplicate suppression verified |
| V16 real next-card barrier | **BLOCKED** for the real provider; durable-ordering verified on JVM |
| V17 no next card on non-success | **BLOCKED** for the real provider; JVM tests verify `nextCard = 0` |
| V18 commit-semantics audit | **PASS** — answered from v2.24.1 source: no ReviewCommitId, no dedup, no lookup, no authoritative reconciliation |
| V19 idempotency audit | **PASS** — `supportsIdempotentReplay = false`, from source evidence, no experimental double-submit |
| V20 reconciliation audit | **PASS** — classified **UNSUPPORTED** (heuristic reconciliation deliberately not implemented) |
| V21 source-of-truth audit | **PASS** (8 tests + architecture inspection) |
| V22 regression of GATE 11A/11B | **PASS** — full 1,381-test suite green, incl. duplicate-input, ambiguous-response, safe-retry, restart, recovery, next-card-barrier tests |

### Real AnkiDroid tests performed

**None.** This environment cannot run `connectedDebugAndroidTest`. The opt-in destructive suite
(`AnkiDroidDisposableCommitInstrumentedTest`) exists, is operator-gated
(`studyagent.ankidroid.allowMutation`, `studyagent.ankidroid.disposableProfileConfirmed`,
`studyagent.ankidroid.disposableDeckId`), and awaits a disposable profile/collection.

### Unverified cases

See §9.

## 4. AnkiDroid API Contract

Re-verified **this session** by fetching the pinned sources from `ankidroid/Anki-Android` tag
`v2.24.1` (commit `9f579c10bb151146728220729c510acbbd8faba7`):

* `api/src/main/java/com/ichi2/anki/api/Ease.kt` — `EASE_1(1) … EASE_4(4)`
* `api/src/main/java/com/ichi2/anki/FlashCardsContract.kt` — `ReviewInfo.CONTENT_URI =
  content://$AUTHORITY/schedule`; `NOTE_ID = "note_id"`, `CARD_ORD = "ord"`,
  `EASE = "answer_ease"`, `TIME_TAKEN = "time_taken"` (ms)
* `api/build.gradle.kts` — release authority `com.ichi2.anki.flashcards`, permission
  `com.ichi2.anki.permission.READ_WRITE_DATABASE`; debug variants
  `com.ichi2.anki.debug.flashcards` / `com.ichi2.anki.debug.permission.READ_WRITE_DATABASE`
* `AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt` — `update()` SCHEDULE
  branch (see below)
* `gradle/libs.versions.toml` — `ankiBackend = '0.1.64-anki25.09.2'` → pinned Anki scheduler
  backend **25.09.2**

| Item | Value |
|---|---|
| Version/source | AnkiDroid `v2.24.1` (`9f579c10…`); Anki backend `25.09.2` |
| Mutation symbol | `ContentResolver.update(Uri, ContentValues, null, null)` on `content://<authority>/schedule` (`CardContentProvider.update`, `SCHEDULE` branch) |
| Required card identity | `note_id` (long) + `ord` (int); resolved by `getCard(noteId, ord)` which throws `IllegalArgumentException` pre-mutation when absent |
| Rating/ease argument | `answer_ease` int 1..4 (`Ease` enum); provider converts `Ease.fromValue` → `CardAnswer.Rating.forNumber(value - 1)` |
| Required permissions | `com.ichi2.anki.permission.READ_WRITE_DATABASE` (release); enforced at the top of `update()` → `SecurityException` before any branch runs |
| Return semantics | `updated` count: `1` once the answer branch is reached; `0` when keys are missing or no row updated; the platform may deliver `-1` when the provider process dies mid-call |
| Mutation side effect | `col.sched.answerCard(card, rating)` — one normal review state transition; optional `time_taken` back-dates `timerStarted` |
| Documented failure modes | (a) `SecurityException` — thrown **before** dispatch, non-mutating; (b) missing/invalid note+ord → `updated = 0` or a propagating `IllegalArgumentException`, both pre-mutation; (c) **`answerCard` swallows `RuntimeException`** (logs + crash report) and `update()` still returns `1` — a row count of `1` is therefore **not** proof of mutation; (d) provider/Binder/process death — lost response, `-1`, or `DeadObject`-class failure; mutation state unknowable |

Full audited detail: `docs/GATE_11C_ANKIDROID_COMMIT_AUDIT.md` (its claims were independently
re-checked against the pinned source in this session; the debug-authority/permission variants and
`shouldEnforceUpdateSecurity = true` are also confirmed there and in
`AnkiDroidApiContract.kt`).

## 5. Irreversible Mutation Boundary

```text
File:        app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidProviderClient.kt
Class:       AndroidAnkiDroidProviderClient
Method:      override suspend fun safeUpdate(authority, path, values)
Exact call:  appContext.contentResolver.update(
                 Uri.parse("content://$authority/$path"), contentValues, null, null)
             — for the rating answer: path = "schedule", i.e.
             content://com.ichi2.anki.flashcards/schedule  (debug: .debug.flashcards)
```

Logical boundary (the last gate before that call):
`AnkiDroidRatingCommitter.commit` → `if (!mutationEntry()) return … ConfirmedNotCommitted`
immediately before `gateway.submitAnswer(...)`. `mutationEntry()` durably persists
`PREPARED → SUBMITTING` in the ledger (coordinator callback `ledger.markMutationEntered`), so the
real mutation can never run on a record that is not durably `SUBMITTING`. The coordinator rejects a
second `mutationEntry()` callback in one attempt (`boundary_called_twice` → AMBIGUOUS).

## 6. Failure Classification

Centralized in `AnkiDroidCommitResultClassifier` (single table; no exception-type logic elsewhere):

| Backend Evidence | Backend Result | Ledger Status |
|---|---|---|
| success confirmed (synchronous return `1` **and** immediate card-state read consistent with exactly one normal answer: `reps +1` with `last_review_time` inside the mutation window; filtered decks stay unknown) | `ConfirmedCommitted` (receipt `null`) | `COMMITTED` |
| no mutation proven — refusal/validation/identity/permission/capability/queue-front failure, or `NotDispatched`, all strictly before the provider entry | `ConfirmedNotCommitted` | `RETRY_ALLOWED` |
| outcome uncertain — timeout (`callMayStillBeRunning`), provider threw after entry, row count ≠ 1, inconclusive swallowed scheduler result, unreadable verification read, any exception after `mutationEntry()`, provider `-1`/process death | `OutcomeUnknown` | `AMBIGUOUS` |

Fail-closed by construction: an exception class is never a transaction receipt, so post-entry
failures are never downgraded to `ConfirmedNotCommitted` (INV-11C-17). Unknown is never converted
to safe retry by heuristics, and card counters/timestamps are explicitly not treated as a
transaction-correlated receipt (`AnkiDroidRatingCommitter.reconcile` returns
`StillAmbiguous("no_transaction_correlated_receipt")`).

## 7. Commit Guarantee

```text
guaranteeLevel:                       AT_MOST_ONCE_FAIL_CLOSED
supportsIdempotentReplay:             false
supportsAuthoritativeReconciliation:  false
commitReceiptKind:                    NONE
```

Evidence (V18/V19/V20, from the pinned v2.24.1 source — not inferred from desired architecture):

* The provider has no transaction token: `update()` accepts no caller commit id, exposes no
  receipt, and offers no lookup-by-id. → no idempotent replay primitive.
  `supportsIdempotentReplay = false`.
* `answerCard` swallows scheduler `RuntimeException` while `update()` still returns `1`; a lost
  response can also land late (gateway keeps `writeInFlight` true). No public read can attribute a
  card-state change to one specific `ReviewCommitId`. → reconciliation of `AMBIGUOUS` is
  **UNSUPPORTED**; `AnkiDroidBackend.reconcileCommit` can only ever answer `StillAmbiguous` or
  `Unavailable`, never `Applied`/`NotApplied`.
* Consequence enforced in code: one durable attempt at a time; no replay after uncertainty; the
  `require(...)` chain in `CommitSemantics` makes any stronger claim unrepresentable.

## 8. Real Mutation Verification

```text
environment:              none available in this sandbox
                          (no Android device/emulator, no SDK/adb; Gradle cannot bootstrap)
AnkiDroid version:        not exercised (implementation target pin: v2.24.1)
ratings exercised:        none on a real scheduler
provider mutation count:  n/a (no real provider entry occurred)
ledger final state:       n/a (no real run)
scheduler evidence:       none collected
next-card ordering:       verified on JVM only (durable COMMITTED strictly before next-card effect)
```

PART IV commands and their honest outcome:

```text
./gradlew --stop  ./gradlew clean  ./gradlew testDebugUnitTest  ./gradlew lint
./gradlew assembleDebug  ./gradlew assembleRelease  ./gradlew connectedDebugAndroidTest
→ ALL UNAVAILABLE: the Gradle wrapper fails downloading
  https://services.gradle.org/distributions/gradle-8.7-bin.zip with
  javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
  (reproduced twice in this session with JAVA_HOME set; `--stop` and `clean` never reached a
  Gradle runtime). The Google Maven / SDK hosts are equally outside the egress allowlist.
```

No real-backend verification is marked passed, because no real disposable environment existed
(PART IV rule).

## 9. Unverified Scenarios

Verified **only** via Fake backend / JVM unit tests / source audit — never against real AnkiDroid:

* Real happy-path commit on a scheduled card (V14) and the observable scheduler effect (`reps +1`).
* Real duplicate-input suppression end-to-end (touch/voice/headset) reaching the real provider once
  (V15); JVM fake-gateway evidence exists (`a repeated commit answers from memory …`).
* Real next-card barrier timing (V16) and no-next-card on `ConfirmedNotCommitted`/`OutcomeUnknown`
  against a real provider (V17).
* Real crash/process-death windows around the provider call (V8) — fake-evidence only by design;
  no deliberate real double-answer.
* Filtered-deck (preview) answer behavior on a device — classified unknown conservatively.
* Permission revocation or collection close between preflight and provider entry on a real system.
* Behavior of AnkiDroid versions other than the audited `v2.24.1` pin.
* `lint`, `assembleDebug`, `assembleRelease`, `connectedDebugAndroidTest` — no Gradle result at all.

## 10. Invariant Report

Every invariant below was checked in this session by code inspection plus the green JVM suite;
none is claimed to be device-verified.

| Invariant | Status | Evidence |
|---|---|---|
| INV-11C-01 public API only | PASS | Only `ContentResolver` + `FlashCardsContract` paths/columns from `AnkiDroidApiContract`; no private DB/app-private file access in the integration layer |
| INV-11C-02 one mutation entry point | PASS | Repository scan: single `ContentResolver.update`; single `submitAnswer`; V4 tests |
| INV-11C-03 no private storage access | PASS | grep: no AnkiDroid private path/package access anywhere |
| INV-11C-04 no adapter retry | PASS | No loops/replay in gateway/committer; `physicalAnswerCalls` monotone; V9 tests |
| INV-11C-05 mapping from audited API | PASS | `toAnkiDroidEase` ↔ v2.24.1 `Ease.kt` re-verified from source |
| INV-11C-06 only scheduler-offered ratings | PASS | `validateCommit`: `options.supports(rating)` else `rating_not_offered_by_scheduler` (V3) |
| INV-11C-07 exact active-turn card mutated | PASS | `turn.cardRef != request.card → StaleTurn`; queue-front equality; state identity match |
| INV-11C-08 backend identity immutable during commit | PASS | Dispatch by durable `commitId.backendId`; `commitId.backendId != id → refusal`; preference changes affect only future sessions |
| INV-11C-09 adapter never queries next card | PASS | Adapter queries are identity/counter evidence reads only; no turn hydration; V12 tests |
| INV-11C-10 adapter never mutates StudyState | PASS | Backend only clears its own `activeTurn` cache on `ConfirmedCommitted`; StudyState transitions live in the machine |
| INV-11C-11 coordinator owns transactions | PASS | `AnkiStudyEffectExecutor` is the sole `ReviewCommitCoordinator`; adapter has no ledger access |
| INV-11C-12 ledger is durable authority | PASS | All status writes via `ReviewCommitLedger`; `markMutationEntered` gates the boundary |
| INV-11C-13 Anki remains scheduler authority | PASS | Adapter observes scheduler state, never computes scheduling |
| INV-11C-14 Committed → only COMMITTED | PASS | `toReviewCommitStatus` total mapping + ledger transition tests |
| INV-11C-15 NotCommitted → only RETRY_ALLOWED | PASS | same |
| INV-11C-16 Unknown → only AMBIGUOUS | PASS | same |
| INV-11C-17 unknown never becomes safe retry | PASS | `writeInFlight`, swallowed-exception and timeout paths return `OutcomeUnknown`; recovery policy never retries AMBIGUOUS |
| INV-11C-18 next card impossible before durable COMMITTED | PASS | `ReviewCommitDurabilityOrderTest`; backend releases the turn only on `ConfirmedCommitted` |
| INV-11C-19 no fabricated receipt | PASS | `receipt = null`, `CommitReceiptKind.NONE`; classifier returns `ConfirmedCommitted()` with no synthetic token |
| INV-11C-20 guarantee ≤ verified evidence | PASS | `CommitSemantics.ANKIDROID` is the weakest nontrivial level, enforced by `validateCommitSemantics` at construction |

---

## Evidence still required before GATE 11C can lock

1. Run the opt-in instrumentation suite on a disposable AnkiDroid profile/collection with operator
   arguments `-e studyagent.ankidroid.allowMutation true -e
   studyagent.ankidroid.disposableProfileConfirmed true -e studyagent.ankidroid.disposableDeckId
   <id>`; record the real scheduler effect and one-mutation-per-logical-commit evidence (V13–V17).
2. Record real `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease
   connectedDebugAndroidTest` results from an unrestricted machine (PART IV).
3. If a real post-boundary loss experiment is ever performed, keep it on the disposable profile and
   record the outcome as ambiguous; never double-answer a valuable card.

Until then this report intentionally leaves **GATE 11C BLOCKED / NOT LOCKED** rather than
overstating device or build evidence.

**NEXT PHASE (unchanged):** `GATE 11D — Real Recovery & Reconciliation Audit` (restart around real
transaction states, AnkiDroid reconciliation limits, external Anki activity, collection changes,
AMBIGUOUS recovery behavior, end-to-end commit → next-card reliability).
