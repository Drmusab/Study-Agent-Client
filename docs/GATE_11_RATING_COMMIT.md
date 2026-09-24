# GATE 11 — Rating Commit, Scheduler & Exactly-Once Reliability

**Gate result: FAIL (implementation complete; gate criteria not met in this environment).**
**Final decision: NO — not locked, no gate-lock commit.**

The rating transaction is implemented end to end. In the partial validation run, every GATE 11
test passes (details in §17). The gate still fails on its own criteria: the Gradle build, unit
tests, lint and APK builds could not be run here, and no real AnkiDroid mutation run exists. This
gate also found that the repository at the GATE 10 head does not compile (§17.4); the fixes applied
are listed there.

## 1. Gate result

| Criterion | Status |
|---|---|
| Implementation of every required element | DONE (§2–§16) |
| `./gradlew --stop / clean / testDebugUnitTest / lint / assembleDebug / assembleRelease` | BLOCKED: no JDK/Android SDK; the Gradle 8.7 distribution download fails (TLS terminated) |
| `connectedDebugAndroidTest` | NOT RUN: no emulator or device |
| Partial compile of GATE 11 main code (Kotlin 2.4.0-dev, serialization plugin) | PASS: 0 errors (Android-only files excluded, §17) |
| Partial JVM run of Anki + GATE 11 suites | 481 passed / 9 failed; all 9 failures pre-exist at HEAD (§17.3) |
| Same compile + run with the project's compiler generation (Kotlin 1.9.23 K1, serialization 1.6.3, coroutines 1.8.0) | main: 0 errors; tests: 481 passed / 9 failed, identical to the 2.4.0-dev run (§17.2) |
| Real AnkiDroid validation | NOT RUN — no configured AnkiDroid mutation test environment |

## 2. Commit architecture

UI → `StudySessionMachine`/`StudyReducer` (pure) → `AnkiStudyEffectExecutor` (ledger + backend,
emits events only) → `AnkiBackend.commitRating` → `AnkiDroidBackend` → `AnkiDroidRatingCommitter`
→ `AnkiDroidRatingGateway` (single writer) → provider client. Flow:
WaitingForRating → (select = prepare, pure) SubmittingRating with commit NOT_STARTED → executor
persists NOT_STARTED → baseline evidence → persists SUBMITTING → `RatingCommitStarted` → backend →
persisted outcome → `RatingCommitResolved` → COMMITTED ⇒ `Next` exactly once; FAILED ⇒
`RatingCommitFailed`; AMBIGUOUS ⇒ `ReconciliationRequired`. Write effects run on one ordered lane
that no read, end, stop or UI recreation can cancel. See `docs/SESSION_STATE_MACHINE.md` §17 and
`docs/ANKI_INTEGRATION_ARCHITECTURE.md` (GATE 11).

## 3. Commit identity

`ReviewCommitId(backendId, studySessionId, turnId)` (GATE 03 type). It is never derived from the
card id or a clock. It is stable across retry (the recorded request is re-sent) and restore (ledger
key = `commitId.stableKey`). Events expose `sessionId`/`turnId`/`commitId`.

## 4. Ledger

`core/anki/ReviewCommitLedger.kt` + `ReviewCommit.kt`. Record fields: commitId (contains sessionId,
turnId, backendId), card ref, rating, state, attemptCount, createdAt, updatedAt, plus ratedAt,
answer duration, evidence, submittedAt, resolvedAt, failure(category, safeToRetry), resolution and
acknowledged.

* Mutex compare-and-set; persist before proceed; `NonCancellable` writes.
* SUBMITTING → AMBIGUOUS on the first load in a process.
* Bounded at 200; unresolved AMBIGUOUS and SUBMITTING are never pruned; a full ledger fails closed.
* Durable via `DataStoreReviewCommitStore`: the existing DataStore library, in a dedicated file with
  no replace-on-corruption handler.
* An unreadable snapshot fails closed and is never overwritten. The only way out is
  `resetUnreadable()`, which is explicit and not wired to UI.

## 5. Rating mapping (verified only)

`AnkiDroidRatingContract.easeFor`: AGAIN→1, HARD→2, GOOD→3, EASY→4. Verified against v2.24.1
`Ease.fromValue` and `CardAnswer.Rating.forNumber(value - 1)`. This is the only numeric mapping,
and it sits below the gateway. Scheduler rating options are enforced in the reducer
(`unsupported-anki-rating`, `anki-rating-options-unmapped`) and in the backend
(`rating_not_offered_by_scheduler`).

## 6. Mutation boundary

* **Pre-mutation:** validation, baseline equality, deck selection, queue-front check. Also the
  provider exceptions the pinned source raises only before answering (`SecurityException`,
  `IllegalArgumentException`).
* **Possibly mutated:** from the instant the answer `update` is issued.
* **Confirmed:** only by before/after card evidence. At v2.24.1 the provider swallows scheduler
  exceptions and still answers `1` (issue #20763), so a row count alone is never treated as proof.

## 7. Result classification

Committed / RetryableFailure / Rejected / Ambiguous. The full evidence table is in
`docs/ANKIDROID_INTEGRATION.md` §29.3. Timeout, cancellation after dispatch, an unexpected
exception, an unreadable verification and unattributable evidence all classify as AMBIGUOUS.

## 8. Retry policy

| Situation | Automatic retry | Manual retry |
|---|---|---|
| RetryableFailure (proven not applied) | never | yes: same commit id, rating, payload (`RetryRatingCommit`) |
| Rejected | never | no |
| Ambiguous / timeout / cancelled / crash | never | no — reconcile first; NotApplied(safe) then enables retry |
| Next-card read failure after COMMITTED | never | read-only `RetryNextCard`; the rating is never re-sent |

## 9. Ambiguous handling

`ReconciliationRequired` blocks `Next`, retry and re-rating. The UI model says the rating "will not
be sent again" and offers Check again or End session. The ledger keeps the record until it is
reconciled or acknowledged; a later session surfaces it as "earlier rating not confirmed".

## 10. Reconciliation

`AnkiBackend.reconcileCommit` defaults to `Unsupported`. AnkiDroid implements it from public
counters: `reps + 1` with `last_review_time_secs` inside the commit window means Applied. No change
on a normal card means NotApplied (safe to retry). One review outside the window means NotApplied
(not safe). Anything else is StillAmbiguous. It refuses to decide while a provider write is still in
flight, it never writes, and it works after process death. Filtered-deck preview answers cannot be
attributed after the fact and stay AMBIGUOUS.

## 11. Process death per state

NOT_STARTED → restorable (re-execution dispatches once). SUBMITTING → AMBIGUOUS (persisted), never
re-sent. COMMITTED → replayed from the ledger, backend never called. FAILED → the retry decision is
kept. AMBIGUOUS → kept and surfaced. Tests: `ReviewCommitLedgerTest` process-death group and flow
tests L1–L3.

## 12. Duplicate input safety

Touch, voice, headset and keyboard all converge on `UserRateCard`/`SelectRating`. First accepted
wins; everything later is rejected. Duplicate effects are stopped by the ledger CAS, and a repeated
commit is answered from the backend's per-session record. Rating buttons are disabled outside
WaitingForRating/ShowingFeedback and while a transaction is pending.

## 13. Advancement rule

The next card is requested only from the COMMITTED branch of `resolveAnkiCommit`, exactly once; a
duplicate COMMITTED is rejected. Counters increment only there. Enforced by
`AnkiRatingCommitArchitectureTest`.

## 14. Next-card failure

Leads to `Error(ANKI_UNAVAILABLE)` with the commit still COMMITTED and the counter already
incremented. `RetryNextCard` re-reads the scheduler; the rating is never replayed (test M).

## 15. PC backend

`handleRateCard`, `handleRatingSaved` and `handleTimeout` are byte-identical to HEAD (md5 compared).
`SubmissionLedger`, `PendingAction`, the network layer and protocol messages are unchanged. Remote
idempotency (`message_id`, `review_turn_id`) is preserved. The harness-based PC suites could not be
compiled here because of pre-existing errors (§17.4).

## 16. Diagnostics

`ANKI_COMMIT_PREPARED/STARTED/COMMITTED/FAILED/AMBIGUOUS/RETRY_STARTED`,
`ANKI_RECONCILIATION_STARTED/RESULT`, `ANKI_NEXT_CARD_AFTER_COMMIT_STARTED`. Metadata only
(commit-id hash, rating name, attempt, state, category, elapsedMs), verified by
`AnkiRatingCommitMachineTest`.

## 17. Tests (exact commands and results)

### 17.1 Required commands

```text
./gradlew --stop / clean / testDebugUnitTest   → "Please set the JAVA_HOME variable" (no JDK)
JAVA_HOME=<jdk4py 17 JRE> ./gradlew testDebugUnitTest
  → Downloading https://services.gradle.org/distributions/gradle-8.7-bin.zip
    SSLHandshakeException: Remote host terminated the handshake        (BLOCKED)
lint / assembleDebug / assembleRelease / connectedDebugAndroidTest       → BLOCKED / NOT RUN
```

### 17.2 Partial validation actually performed (clearly not the Gradle build)

* **Toolchain:** Temurin 17.0.9 JRE (PyPI `jdk4py`); Kotlin compiler 2.4.0-dev with the
  kotlinx-serialization plugin, coroutines 1.10.2 and serialization 1.9.0 (from the PyPI
  `kotlin-jupyter-kernel` fat jar). The project pins Kotlin 1.9.24 and coroutines 1.8.1.
* **Main set, 163 files for a compile-only check (0 errors); tests ran against its 159-file
  subset:** all of `core/` and all of `data/anki/ankidroid/`, plus
  `data/anki/AnkiLibraryRepository.kt`, `StudySessionMachine.kt` and
  `StudySessionMachineRepository.kt`. Excluded: Android/OkHttp-only files, and four TTS backends
  that depend on an excluded Android file. Mechanically stripped copies (interfaces only) were used
  for `AnkiDroidProviderClient.kt`, `ConnectionRepository.kt`, `AudioRouteManager.kt`,
  `StudySessionRepository.kt` and `StudyControlRepository.kt`. Stubs were used for
  `android.util.Log`, `android.os.Build` and `BuildConfig`. Result: **0 errors**.
* **Test shims (outside the repo):** a JUnit-4 assert/annotation subset, a reflective runner, and a
  virtual-time `kotlinx-coroutines-test` subset (`runTest`, `TestScope`, Standard/Unconfined
  dispatchers, `runCurrent`/`advance*`, a `Delay`-based `withTimeout`). Test copies get an
  `org.junit.Assert.*` → `org.junit.*` import rewrite.
* **Re-run with the project's compiler generation (added when the PR was opened).** The project
  pins Kotlin 1.9.24 (K1 frontend) and the compiler above is K2, so the same main set, shims and
  tests were compiled again with **Kotlin 1.9.23** (plus the serialization plugin, kotlinx-coroutines
  1.8.0 and kotlinx-serialization 1.6.3, all from the PyPI `kotlin-jupyter-kernel` 0.12.0.217 fat
  jar). Main set: **0 errors** and 43 warnings, **none of them on a line added by GATE 11**. Tests:
  **481 passed, 9 failed**, identical per class and per failure to the 2.4.0-dev run. Tests run with
  the working directory set to `app/`, as Gradle does, because the source-scanning suites locate the
  module from it. This is still not the Gradle/AGP build: the Android-dependent files this gate
  changes were never compiled (`DataStoreReviewCommitStore`, `AndroidAnkiDroidProbe`,
  `AnkiDroidProviderClient` (only a stripped interface copy was compiled), `AppContainer`,
  `StudyPhase`, `StudyScreen`, `StudyViewModel`). One pre-existing warning deserves attention
  outside this gate: `StudySessionMachine.isSttStartStillValid` lists `SessionPhase.SpeakingHint`
  in two `when` branches, so the second branch (rating/command recognition during a hint) can
  never match. It is left unchanged because it is out of GATE 11 scope.
* **Result:** 49 test source files (38 test classes executed; 10 of the files are fakes, fixtures
  or harnesses), **481 passed, 9 failed**:

| Suite | Result |
|---|---|
| ReviewCommitLedgerTest | 22/22 |
| AnkiRatingCommitFlowTest (Part V A–P) | 25/25 |
| AnkiRatingCommitMachineTest | 3/3 |
| AnkiDroidRatingCommitTest | 13/13 |
| AnkiDroidCommitVerifierTest | 7/7 |
| AnkiRatingCommitArchitectureTest (Part X) | 7/7 |
| AnkiDroidIntegrationIsolationTest (write allowlist) | 14/14 |
| AnkiStudyInteractionTest (GATE 10, updated) | 15/15 |
| FakeAnkiBackendTest / FakeAnkiBackendContractTest | 25/25, 9/9 |
| AnkiDomainIsolationTest | 4/4 |
| Other Anki suites | pass, except below |
| RatingControlsPolicyTest | not run here (needs Compose on the classpath) |

### 17.3 The 9 remaining failures are pre-existing

The same test classes were run against a `git archive` of HEAD with only the minimal compile fixes.
HEAD gives **385 passed / 8 failed, with the identical 8 failures**:

* AnkiCardFlowTest (memo test)
* AnkiCardHydrationTest (deck-move degradation)
* AnkiDroidCardGatewayTest ×2
* AnkiDroidCardMapperTest ×2
* AnkiDroidReviewSessionTest ×2 (these tests build invalid domain objects)

The 9th, `AnkiMediaResolverPolicyTest`, is GATE 09 code that GATE 11 does not touch.

### 17.4 Pre-existing compile errors found (HEAD does not compile)

Fixed minimally, because they block compiling files GATE 11 changes:

* `SessionPhase.serverPhaseName` (`problem` unresolved)
* `StudyReducer`: wrong-arity `handleServerResumed` call, and a `companion object` inside an
  `object`
* `Transition.stay` (anonymous object implementing a sealed interface; the function was unused and
  is removed)
* `StudySessionMachine` (`RecognitionErrorCode.TIMEOUT` does not exist; now NETWORK/WATCHDOG timeout)
* `AnkiDroidBackend` (missing imports)
* `AnkiDroidDetector` (non-exhaustive `when`)
* `DashboardModels` (member call on a nullable receiver)
* Tests: `AnkiCardFlowTest`, `AnkiDroidCardGatewayTest`, `AnkiDroidCardMapperTest` (wrong/missing
  imports), `AnkiCardHydrationTest` (missing argument), `AnkiScheduledReviewContractTest`
  (invariant list types, private helpers), `FakeConnectionRepository` (4 unimplemented interface
  members)

Not fixed (outside scope, found while compiling):

* `WebSocketAgentConnection` (missing `override`s)
* `RemoteSpeechBackend`: its `private val provider` conflicts with the public
  `SpeechBackend.provider`. Confirmed with Kotlin 1.9.23 when the PR was opened.
* `ProviderBackendRouter`: not confirmed. In the partial compile it only reports unresolved
  references to types that live outside the compiled subset (some of them Android-dependent).
* `IdempotencyTest`, `NetworkChaosTest`
* `StudySessionHarness`, `FakeStudyServer`
* `StudyReducerTest`, `SessionIdempotencyTest`, `StudySessionHappyPathTest`, `StudyAgentChaosTest`

Android/Compose sources could not be compiled here at all.

## 18. Real AnkiDroid validation

NOT RUN — no configured AnkiDroid mutation test environment.

## 19. Files added

* Main: `core/anki/ReviewCommit.kt`, `core/anki/ReviewCommitLedger.kt`,
  `core/study/RatingCommitRecoveryUi.kt`, `data/anki/DataStoreReviewCommitStore.kt`,
  `data/anki/ankidroid/AnkiDroidCommitEvidence.kt`, `data/anki/ankidroid/AnkiDroidRatingGateway.kt`,
  `data/anki/ankidroid/AnkiDroidRatingCommitter.kt`
* Tests: `anki/ReviewCommitLedgerTest.kt`, `anki/AnkiRatingCommitArchitectureTest.kt`,
  `anki/fake/InMemoryReviewCommitStore.kt`, `anki/ankidroid/AnkiDroidRatingCommitTest.kt`,
  `anki/ankidroid/AnkiDroidCommitVerifierTest.kt`, `study/AnkiCommitHarness.kt`,
  `study/AnkiRatingCommitFlowTest.kt`, `study/AnkiRatingCommitMachineTest.kt`,
  `ui/RatingControlsPolicyTest.kt`
* Docs: this file

## 20. Files modified

* Main (GATE 11): `AnkiBackend.kt`, `AnkiResults.kt`, `AnkiStudyInteraction.kt`,
  `AnkiStudyEffectExecutor.kt`, `StudyReducer.kt`, `StudySessionMachine.kt`, `SessionPhase.kt`,
  `SessionProblem.kt`, `StudyState.kt`, `AnkiDroidBackend.kt`, `AnkiDroidReviewSession.kt`,
  `AnkiDroidApiContract.kt`, `AnkiDroidProviderClient.kt`, `AndroidAnkiDroidProbe.kt`,
  `AnkiDroidErrors.kt`, `AnkiDroidCompatibilityPolicy.kt`, `StudySessionRepository.kt`,
  `StudySessionMachineRepository.kt`, `AppContainer.kt`, `StudyPhase.kt`, `StudyScreen.kt`,
  `StudyViewModel.kt`
* Main (pre-existing fixes only): `Transition.kt`, `AnkiDroidDetector.kt`, `DashboardModels.kt`
* Tests: `FakeAnkiBackend.kt`, `AnkiStudyInteractionTest.kt`,
  `AnkiDroidIntegrationIsolationTest.kt`, `AnkiDroidCapabilityProbeTest.kt`,
  `AnkiDroidIntegrationStateTest.kt`, `AnkiDroidReviewSessionTest.kt`, plus the §17.4 test fixes
* Docs: `SESSION_STATE_MACHINE.md` §17, `ANKIDROID_INTEGRATION.md` §29,
  `ANKI_INTEGRATION_ARCHITECTURE.md` (GATE 11)

## 21. Invariants

No INV-ANKI-COMMIT list existed in the repository. The numbering below restates the GATE 11
requirement list in order.

| # | Invariant | Status |
|---|---|---|
| 01 | Commit identity = backend + session + turn; never card id or clock | PASS |
| 02 | Commit id stable across retry and restore | PASS |
| 03 | ≤ 1 intentional scheduler mutation per turn (physical dispatch counted) | PASS |
| 04 | Never RatingSelected → next card | PASS |
| 05 | NOT_STARTED persisted before SUBMITTING | PASS |
| 06 | SUBMITTING persisted before the provider call | PASS |
| 07 | Ledger CAS admits one claimant | PASS |
| 08 | FAILED ≠ AMBIGUOUS | PASS |
| 09 | AMBIGUOUS blocks progression, retry and re-rate | PASS |
| 10 | No blind retry after timeout/ambiguity | PASS |
| 11 | Retry keeps commit id, rating, payload | PASS |
| 12 | Rating immutable once recorded | PASS |
| 13 | Mixed/duplicate input → one mutation, first wins | PASS |
| 14 | Results correlated; stale results never mutate the current turn | PASS |
| 15 | Stale results may update the ledger | PASS |
| 16 | Duplicate COMMITTED never double-advances | PASS |
| 17 | Counters only after COMMITTED | PASS |
| 18 | Next-card failure never replays the rating | PASS |
| 19 | NOT_STARTED restorable after process death | PASS |
| 20 | SUBMITTING → AMBIGUOUS after process death | PASS |
| 21 | COMMITTED → no replay after process death | PASS |
| 22 | Cancellation after dispatch → AMBIGUOUS | PASS |
| 23 | Unexpected exception after dispatch → AMBIGUOUS | PASS |
| 24 | Stop/background/recreation never duplicates or cancels a commit | PASS |
| 25 | Ledger durable across real process death (DataStore) | NOT YET TESTABLE |
| 26 | Bounded retention; AMBIGUOUS never silently dropped | PASS |
| 27 | Unreadable ledger fails closed, never overwritten | PASS |
| 28 | Ledger backend-neutral | PASS |
| 29 | One verified mapping, below the gateway | PASS |
| 30 | "No exception"/row count is not success | PASS |
| 31 | Scheduler rating options enforced | PASS |
| 32 | Reconciliation evidence-based; unattributable stays AMBIGUOUS | PASS |
| 33 | Reconciliation never writes | PASS |
| 34 | Recovery UX: no blind re-rate, End always, fresh session asks scheduler | PASS (model); on-screen NOT YET TESTABLE |
| 35 | Rating controls disabled while pending; UI backend-neutral | NOT YET TESTABLE (Compose test not run; architecture scan PASS) |
| 36 | PC backend remote idempotency unchanged | PASS (byte-identical handlers; harness tests blocked) |
| 37 | Diagnostics metadata only | PASS |
| 38 | Mutation boundary: pre / possibly / confirmed | PASS |
| 39 | Verified against a real AnkiDroid isolated collection | NOT YET TESTABLE |
| 40 | Precise wording: at most one intentional mutation per turn | PASS |

## 22. Deferred work

* **GATE 12:** wire a local-Anki session entry point in the UI so the recovery banner is reachable
  in production; an in-app action for unreadable-ledger reset and for dismissing or reconciling
  earlier unconfirmed ratings; skip the queue-front precondition on verified AnkiDroid ≥ 2.25.
* **GATE 13:** a real-device mutation matrix against an isolated test collection (v2.24.1 and 2.25),
  including provider kill mid-answer.
* **GATE 14:** restore the repository to a compiling state (§17.4 unfixed items) and a green Gradle
  CI run, including lint and both APKs.

## 23. Final decision

**NO.** Blockers:

1. No Gradle build, unit-test run, lint or APK in this environment.
2. The repository had pre-existing compile errors outside GATE 11 that remain unverified.
3. No real AnkiDroid mutation validation.

No gate-lock commit was created, and GATE 12 was not started.
