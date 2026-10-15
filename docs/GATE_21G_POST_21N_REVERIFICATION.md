# GATE 21G — Immutable StudySession Backend Lock (Post-21N Re-Verification)

**Status:** `PASS` — GATE 21G remains **LOCKED** at its stated scope. Gradle verification is **BLOCKED** (environmental, §11).
**Supersedes:** nothing. `docs/GATE_21G_SESSION_LOCK_IMPLEMENTATION.md` (implementation) and
`docs/GATE_21G_VERIFICATION_REPORT.md` (first re-verification) remain the historical records. This
document re-verifies the *same* implementation at a later revision, after GATE 21H/21I/21J/21L/21N
landed on top of it.

| Field | Value |
|---|---|
| Repository | `Drmusab/Study-Agent-Client` |
| Branch | `arena/9d4210fe-study-agent-client` |
| Repository HEAD | `250ee64d7e40c2b08b8ff00a7268c8b42823bd62` |
| Worktree at start | clean (`git status --short` → empty) |
| Date | 2026-10-15 (UTC) |
| Execution type | prerequisite validation → source inventory → executed re-verification → verification-gap closure → audit re-run → gate decision |
| Production files changed | **0** |
| Test files changed | 2 (`Gate21gVerificationTest.kt`, `AnkiCommitHarness.kt`) |

```text
GATE 21G:                      PASS (re-verified at 250ee64; remains LOCKED)
PREREQUISITES 21A–21F:         LOCKED
PRODUCTION DEFECTS FOUND:      0
VERIFICATION DEFECTS FOUND:    2 (VER-21G-30 and VER-21G-31 had no executable case) → closed here
GATE 21G SUITE:                PASS (88/88, 6 classes; was 86/86 before this run)
FULL HARNESS SUITE:            PASS (2645/2645, 218 classes; was 2643/2643 before this run)
COMPILE SCOPES:                PASS (main 328 files, compose 24, tests 250 — 0 errors each)
GRADLE testDebugUnitTest:      BLOCKED — environmental (§11)
GATE 21H:                      NOT EXECUTED by this run
```

---

## 0. What this run found, and what it changed

**GATE 21G was already implemented, verified and locked at this HEAD.** The lock
(`core/anki/StudySessionBackendLock.kt`), the store (`core/anki/StudySessionLockStore.kt` +
`data/preferences/StudySessionLockDataStore.kt`), the projection
(`core/study/StudySessionOwnershipProjection.kt`), the executor integration
(`core/study/AnkiStudyEffectExecutor.kt`) and six test classes were all present at the branch base.
GATE 21H and 21I have since been executed and locked on top of it, and 21N's own report records
`GATE 21G: LOCKED` (`docs/GATE_21N_FINAL_INTEGRATION_REPORT.md:38`).

Executing GATE 21G here therefore meant **re-verifying the landed implementation at a revision its
own report never saw** — 21H/21I rewrote the executor's commit and reviewer-action routing around
the 21G guards, so the 21G invariants needed re-proving, not re-asserting. It did **not** mean
re-implementing: a second lock, resolver or session machine would violate INV-21G-25.

Re-verification produced two changes, both test-only:

| Change | Path | Nature |
|---|---|---|
| 2 new verification cases (`VER-21G-30`, `VER-21G-31`) | `app/src/test/java/com/studyagent/client/study/Gate21gVerificationTest.kt` | Test-only; closes the two `VER-21G-*` cases with no executable coverage at HEAD |
| `registry` property + optional `answerEvaluator` parameter | `app/src/test/java/com/studyagent/client/study/AnkiCommitHarness.kt` | Test-harness only; `answerEvaluator` defaults to `null` and `registry` holds exactly the backends the executor was already built from, so existing behaviour is unchanged |

**No production file was modified.** Zero production defects were found: every 21G invariant this
run could execute held at HEAD, including across the 21H/21I routing changes.

---

## 1. Gate result and prerequisites

```text
GATE 21G — IMMUTABLE STUDYSESSION BACKEND LOCK

STATUS:                PASS (re-verified at 250ee64; Gradle blocked environmentally)
REPOSITORY HEAD:       250ee64d7e40c2b08b8ff00a7268c8b42823bd62

21A: LOCKED   docs/GATE_21A_DISCOVERY.md
21B: LOCKED   docs/GATE_21B_CONTRACT_LOCK.md
21C: LOCKED   docs/GATE_21C_IDENTITY_IMPLEMENTATION.md
21D: LOCKED   docs/GATE_21D_REGISTRY_IMPLEMENTATION.md
21E: LOCKED   docs/GATE_21E_RESOLVER_IMPLEMENTATION.md (wording gap noted by 21N §1; 21F accepted the user confirmation)
21F: LOCKED   docs/GATE_21F_READ_CONTEXT_ISOLATION.md

IMPLEMENTATION STATUS OF THE FEATURES 21G BINDS TO (source-checked at this HEAD):
GATE 10 StudySession:     IMPLEMENTED   StudySessionMachine.kt (87 KB), StudyReducer.kt (162 KB)
GATE 11 Review Commit:    IMPLEMENTED   ReviewCommitLedger/Coordinator/Recovery/RoutingPolicy
GATE 12 Answer Reveal:    IMPLEMENTED   AnswerReviewModel.kt + Gate12AnswerRevealReviewTest
GATE 13 Reviewer Actions: IMPLEMENTED   ReviewerActionCoordinator/Ledger/RoutingPolicy
GATE 20 Custom Study:     DESIGN_ONLY   docs/GATE_20_CUSTOM_STUDY_CONTRACT.md:3 — "Phase B BLOCKED",
                                        "Phase C not started"; AnkiBackend exposes no
                                        filtered-session/custom-study operation (grep: none)
```

Prerequisite verdict: **PASS**. 21G's runtime dependencies are implemented and covered by passing
suites. GATE 20 is design-only, so CHECKPOINT 21G-20 / VER-21G-29 stay **BLOCKED-deferred**, never
passed (§10).

---

## 2. Session ownership architecture (as implemented at this HEAD)

```text
Settings / BackendPreferenceRepository     → preferred backend (future unbound work only)
        |
GATE 21E AnkiBackendResolver2.resolve      → pre-binding selection, once per start attempt
        |                                    AnkiStudyEffectExecutor.kt:770 — the ONLY study-runtime site
GATE 21D AnkiBackendRegistry.find          → exact lookup by configured AnkiBackendId
        |                                    AnkiBackendSelector.kt:42 — the ONLY registry class
GATE 21G StudySessionAdmissionPolicy       → resolution → exact registration → readiness →
        |                                    REVIEW capability → deck ownership → OP-S1 coherence,
        |                                    all BEFORE any scheduler call
GATE 21G StudySessionBackendLock           → immutable owner (identity only), published on backend
        |                                    acceptance and persisted in the same acceptance
GATE 21G sessionLockConsistent             → bound-work guard on Next / Hydrate / CommitRating /
        |                                    PerformReviewerAction, refusing before any backend touch
AnkiBackend                                → scheduler + collection operations
ReviewCommitCoordinator (21H routing)      → rating transaction authority (unchanged by 21G)
ReviewerActionCoordinator (21I routing)    → reviewer-action transaction authority (unchanged by 21G)
```

Source-grounded single-truth checks performed this run:

```text
$ grep -rn "AnkiBackendResolver2.resolve" app/src/main
core/anki/AnkiBackendResolver.kt:81, :94        (the thin stateful 21E adapter, delegating)
core/study/AnkiStudyEffectExecutor.kt:770       (study runtime — exactly one site, inside begin())
data/anki/context/AnkiReadContextController.kt:93, :123, :138   (GATE 21F read contexts)
data/repository/AnkiLocalStudyStarter.kt:234    (pre-session start admission)

$ grep -rn "class AnkiBackendRegistry" app/src/main
core/anki/AnkiBackendSelector.kt:42             (one registry; every consumer takes it as a parameter)

$ grep -rn "var currentBackend\|currentBackend =" app/src/main
(no matches — no global mutable backend)

$ grep -n "filtered\|customStudy" app/src/main/java/com/studyagent/client/core/anki/AnkiBackend.kt
(no operation — GATE 20 surface absent, confirming DESIGN_ONLY)
```

---

## 3. StudySession start sequence and the immutability moment

Actual sequence in `AnkiStudyEffectExecutor.begin()` (line numbers at this HEAD):

```text
:767  availability refresh of preference-accepted candidates only
:770  AnkiBackendResolver2.resolve(preference, STUDY_SESSION_REQUIREMENTS, "session_start")   ← AUTO used here, once
:783  StudySessionAdmissionPolicy.admit(resolution, registry.find(id), deck, sessionId, ts)
        refusal → Begun(Failure); nothing published, no scheduler traffic
:795  ledger health + recoveryBlocker(id, deck.collectionKey, sessionId)   (GATE 11 barrier)
:806  actionLedger health + recoveryBlocker(...)                           (GATE 13 barrier)
:815  backend.getDecks() → DeckNotFound check
:822  context = admitted.toSessionContext(backend.capabilities.value)      (live capabilities, never persisted)
:823  backend.beginReview(BeginReviewRequest(context))
:830  sessionLocks.locked(context)                                         ← OWNERSHIP BECOMES IMMUTABLE
:834  sessionLockStore.save(StudySessionBackendLock.from(context))          (same acceptance, NonCancellable)
:838  save failed → released(id) + endReview(handle) + Failure(session_lock_persistence_failure)
```

**The precise immutability moment is line 830**, after `beginReview` succeeded: a refused or
unpersistable start publishes no lock, so no session ever advertises a backend it is not bound to.
From that point the only routing input is the session's own lock — `AnkiBackendResolver2` is never
re-invoked for that session (INV-21G-03).

Duplicate-start protection lives in the reducer, not in a new mechanism:
`StudyReducer.kt:1611` rejects `already-active` and `:1615` rejects `already-starting`;
`StudyReducer.kt:144` rejects a second `AnkiStudyEvent.Start` with `already-active`.

---

## 4. Session backend context — actual types

```kotlin
// core/anki/StudySessionBackendLock.kt
@Serializable data class StudySessionBackendLock(
    val studySessionId: String,
    val backendId: AnkiBackendId,
    val collection: AnkiCollectionIdentity? = null,
    val deckRef: AnkiDeckRef? = null,
    val startedAtEpochMs: Long = 0L,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION          // = 1
)
// init{} refuses: blank session id, negative startedAt, schema < 1, a collection or deck naming
// another backend, and a collection/deck key disagreement.
```

Carried: identity only. **Not** carried: capabilities or availability (re-read live on restore via
`toSessionContext(capabilities)`), preference/AUTO state, transport objects, credentials, scheduler
or turn state. The architecture audit test asserts this by source scan
(`Gate21gArchitectureAuditTest."the persisted lock carries identity, never capability or policy"`).

Three total pure policies over that lock: `StudySessionAdmissionPolicy.admit` (OP-S1 fresh bind),
`StudySessionContinuationPolicy.check` (OP-S2 bound session), `StudySessionRestorePolicy.restore`
(OP-S2 persisted lock). None performs I/O; none consults preference, AUTO or a sibling backend.

---

## 5. Scheduler routing matrix (verified at this HEAD)

| Operation | Backend ownership source | Guard | Status |
|---|---|---|---|
| Start / bind | `AnkiBackendResolver2` once → admission → lock | admission precedes `beginReview` | PASS |
| Next card | session lock (`effect.session.context.backendId`) | `sessionLockConsistent` (executor `:711`) | PASS |
| Card hydration | session lock (`effect.turn.backendId`) | `sessionLockConsistent` (executor `:727`) | PASS |
| Rating | session lock + GATE 11 record identity | `sessionLockConsistent` before the ledger (`:878`) + 21H `OP-M1/M2/M3` routing | PASS |
| Reviewer action | session lock + GATE 13 record identity | `sessionLockConsistent` before anything durable (`:184`) + 21I `OP-M1` routing | PASS |
| Custom / filtered study | — | no backend operation exists | **BLOCKED** (GATE 20 design-only) |

Refusal tokens observed in source: `session_backend_mismatch`, `session_lock_persistence_failure`,
`backend_not_registered`, `backend_unavailable`, `required_capability_unavailable`, `foreign_deck`,
`foreign_collection_identity`, `collection_mismatch`, `collection_identity_degraded`,
`lock_integrity_failure`, `no_persisted_lock`, `unsupported_lock_schema`, `invalid_session_identity`.

---

## 6. Persistence & recovery

```text
Persisted identity:  study_session_backend_lock (Preferences key,
                     AppSettingsPreferencesCodec.kt:104) → StudySessionBackendLock JSON
Serialization:       versioned JSON, ignoreUnknownKeys, explicitNulls; schemaVersion > 1 →
                     UnsupportedVersion (never downgraded); malformed/failed init → Invalid
Session restore:     StudySessionRestorePolicy.restore + AnkiStudyEffectExecutor.restoreSession
                     (API + tests). ⚠ No production caller — see §13.
Backend unavailable: Blocked(backend_unavailable); lock preserved, never rewritten, no substitution
Collection mismatch: Blocked(collection_mismatch), reported distinctly from a transport error
Unknown identity:    OP-S2 matrix verdict; boundIsLiveInThisProcess is caller-supplied, never defaulted
Legacy session:      absent/undecodable bytes → Invalid; AUTO is never invoked for an existing session
Pending mutation:    restoreSession reports hasUnresolvedTransactions from BOTH ledgers (commits and
                     reviewer actions, executor :570-586) so the next-card barrier holds
```

---

## 7. Concurrency & stale events

```text
Session generation:  StudySessionId + the reducer's epoch/turn guards; the executor's lock guard is
                     the backstop for effects that outlive their session
ReviewTurn identity: session-qualified ReviewTurnId; a foreign turn is refused before any backend call
Start admission:     reducer "already-active"/"already-starting"/"duplicate-session-started"
Callback validation: sessionLockConsistent at Next/Hydrate/CommitRating/PerformReviewerAction, plus
                     the reducer's epoch, cardId and turnId rejections
Cancellation:        persistence runs in NonCancellable; handle release is best-effort
State-update guards: a refused start publishes nothing; a foreign commit never creates durable intent
```

---

## 8. File-by-file report (this run)

```text
FILE:    app/src/test/java/com/studyagent/client/study/Gate21gVerificationTest.kt
ACTION:  MODIFIED
LOCKED CONTRACT REFERENCES: DECISION-21B-11/12/14, INV-21G-22, INV-21G-23
RESPONSIBILITY: PART III authoritative verification cases
IMPLEMENTED: +2 cases — `a pc ai evaluation service never reaches the pc anki scheduler`
             (VER-21G-30) and `a library read context on b leaves the study session locked to a`
             (VER-21G-31); imports for Evaluation / AnkiAnswerEvaluator / read-context types
LOCAL CHECKS: 19/19 in class; full suite 2645/2645

FILE:    app/src/test/java/com/studyagent/client/study/AnkiCommitHarness.kt
ACTION:  MODIFIED
LOCKED CONTRACT REFERENCES: INV-21G-23, INV-21G-25
RESPONSIBILITY: shared reducer+executor harness
IMPLEMENTED: exposed `registry` (the exact registry the executor was already constructed from) and
             an optional `answerEvaluator` (default null), threaded through both executor
             constructions including restartLedger()
LOCAL CHECKS: every pre-existing harness consumer unchanged (2643 → 2645, no behaviour change)

FILE:    app/src/main/... (all production sources)
ACTION:  NO CHANGE
```

---

## 9. Architecture audit matrix

| Audit | Result | Evidence |
|---|---|---|
| AUDIT-21G-01 contract conformance | PASS | Lock/policies cite DECISION-21B-12/14; OP-S1 coherence matches 21F `bindNewContext` |
| AUDIT-21G-02 single ownership | PASS | `StudySessionBackendLock.backendId` is one non-null `AnkiBackendId`; tracker maps session→one context |
| AUDIT-21G-03 immutable binding | PASS | `AnkiStudyBackendLockTracker` exposes only `locked`/`released`/`lockedBackendFor`; no setter, no rebind path |
| AUDIT-21G-04 binding moment | PASS | §3 trace: resolution `:770` → admission `:783` → lock `:830`, before `beginReview` returns success |
| AUDIT-21G-05 AUTO isolation | PASS | exactly one `AnkiBackendResolver2.resolve` in study runtime, inside `begin()`; audit test `gate 21g adds no second resolver registry scheduler ledger or queue` |
| AUDIT-21G-06 scheduler authority | PASS | §5 matrix; every route resolves `registry.find(<locked id>)` |
| AUDIT-21G-07 no local scheduler | PASS | no FSRS/due-order code added; next card is `backend.nextCard(session)` only |
| AUDIT-21G-08 collection integrity | PASS | lock `init{}` refuses cross-backend/contradictory collection; admission records live collection only |
| AUDIT-21G-09 reconnect safety | PASS | `StudySessionContinuationPolicy` runs the 21C OP-S2 matrix; transport ≠ continuity |
| AUDIT-21G-10 backend loss safety | PASS | `Blocked(backend_unavailable)`, lock preserved; test `backend loss leaves the other ready backend untouched on every path` |
| AUDIT-21G-11 session persistence | PASS (scoped) | save on acceptance, clear on end, round-tripped by both stores; **no production read-back** (§13) |
| AUDIT-21G-12 historical session safety | PASS | `Invalid(no_persisted_lock / lock_integrity_failure / unsupported_lock_schema)`; test `a legacy session without ownership is never guessed` |
| AUDIT-21G-13 stale event rejection | PASS | `sessionLockConsistent` + reducer epoch/turn guards; test `stale callbacks from an ended session cannot move a newer session` |
| AUDIT-21G-14 ReviewCommit integration | PASS | guard refuses *before* the ledger; 21H `OP-M1/M2/M3` routing untouched by this run |
| AUDIT-21G-15 ReviewerAction integration | PASS | guard returns `NotRecorded` before anything durable; 21I `OP-M1` routing untouched |
| AUDIT-21G-16 custom study compatibility | **BLOCKED** | no filtered-session operation exists (GATE 20 design-only) |
| AUDIT-21G-17 voice/AI separation | PASS | **new** test `a pc ai evaluation service never reaches the pc anki scheduler`: AI calls = 1, PC Anki calls = 0 |
| AUDIT-21G-18 read context separation | PASS | **new** test `a library read context on b leaves the study session locked to a`: read context binds PC, study stays AnkiDroid |
| AUDIT-21G-19 source-of-truth integrity | PASS | one registry class, one resolver policy, one lock type, ledgers untouched |
| AUDIT-21G-20 scope discipline | PASS | 0 production files changed this run; no failover, replay or recovery engine added |

---

## 10. Verification matrix (PART III, authoritative)

| Verification | Result | Evidence (executed at 250ee64) |
|---|---|---|
| VER-21G-01 initial binding | PASS | `Gate21gLockAdmissionTest` (34 cases) + `Gate21gSessionContinuityTest.begin persists the exact lock…` |
| VER-21G-02 AUTO binding | PASS | `an auto start keeps its resolved backend when the preference and readiness change` |
| VER-21G-03 explicit binding | PASS | admission cells in `Gate21gLockAdmissionTest`; `session start captures the backend identity…` |
| VER-21G-04 missing backend | PASS | `backend_not_registered` admission cell; `a refused start publishes no lock` |
| VER-21G-05 capability failure | PASS | `required_capability_unavailable` admission cell |
| VER-21G-06 duplicate start | PASS | `rapid repeated starts admit exactly one binding` (3 starts → 1 `Begin`, 2 `already-active`) |
| VER-21G-07 preference change during study | PASS | same AUTO test: pre-binding resolution answers PC, session stays AnkiDroid |
| VER-21G-08 AUTO state change during study | PASS | same test, `assertOtherBackendUntouched(pc, "VER-21G-08 after loss")` |
| VER-21G-09 next-card routing | PASS | `next on the locked backend routes normally` + churn counter `cross-backend scheduler calls = 0` |
| VER-21G-10 card identity collision | PASS | `a colliding numeric card id stays bound to the session's own backend` (both fakes expose card 123) |
| VER-21G-11 review information | PASS | same collision test; hydration refuses on `session_backend_mismatch` |
| VER-21G-12 rating context | PASS | `the rating context carries the session's locked backend and turn` |
| VER-21G-13 no next card before commit | PASS | `no next card is queried while the durable commit is outstanding`; churn counter = 0 |
| VER-21G-14 ambiguous rating | PASS | `an ambiguous rating never retries advances or switches` |
| VER-21G-15 commit success / next-card failure | PASS | `a committed rating is never resubmitted when the next card read fails` |
| VER-21G-16 reviewer action context | PASS | `a reviewer action reaches only the locked backend` |
| VER-21G-17 backend A loss | PASS | `backend loss leaves the other ready backend untouched on every path`; `losing the locked backend blocks the session instead of failing over` |
| VER-21G-18 same-backend verified reconnect | PASS | `same-backend reconnect follows continuity mismatch and unknown identity` (continuity branch) |
| VER-21G-19 verified collection mismatch | PASS | same test (mismatch branch, blocked distinctly) |
| VER-21G-20 unknown collection assurance | PASS | same test (unverifiable ≠ verified match); `collection identity is unverifiable` admission cells |
| VER-21G-21 process restoration | PASS (scoped) | `restoration preserves the original backend owner`; `Gate21gLockAdmissionTest.post-death…` — policy/API level, no app-startup path (§13) |
| VER-21G-22 original backend missing after restart | PASS | `restoration never substitutes another ready backend` |
| VER-21G-23 legacy session without identity | PASS | `a legacy session without ownership is never guessed` |
| VER-21G-24 stale next-card callback | PASS | `stale callbacks from an ended session cannot move a newer session` |
| VER-21G-25 stale AI evaluation callback | PASS | same test (AI branch) |
| VER-21G-26 stale rating callback | PASS | same test + `ending a session leaves an unresolved rating record intact` |
| VER-21G-27 A → B → A sequence | PASS | `Gate21gSessionContinuityTest` succession cases |
| VER-21G-28 session end with pending transaction | PASS | `ending a session leaves an unresolved rating record intact` (record survives, status/attemptCount unchanged) |
| VER-21G-29 custom study ownership | **BLOCKED** | GATE 20 design-only; `AnkiBackend` exposes no filtered-session operation. Deferred, not passed |
| VER-21G-30 PC AI service independence | PASS | **NEW** `a pc ai evaluation service never reaches the pc anki scheduler`: AI calls = 1, locked backend nextCard ≥ 1 and 1 mutation, PC Anki counters all 0 |
| VER-21G-31 read context independence | PASS | **NEW** `a library read context on b leaves the study session locked to a`: 21F binding resolves to PC and really reads (`getDecksCalls ≥ 1`), while PC scheduler/mutation/session counters stay 0 and study keeps mutating on AnkiDroid |
| VER-21G-32 diagnostics accuracy | PASS | `ownership diagnostics report the lock not the preference`; `the projection reports the lock, never the preference` |
| VER-21G-33 1,000-event endurance | PASS | `a thousand-event churn holds every ownership counter at zero` (1.2 s): all eight required counters 0, with non-vacuity assertions (≥100 mutations, ≥100 reads, ≥100 resolutions, ≥90 availability flips) |
| VER-21G-34 previous gate regression | PASS | full harness suite **2645/2645, 218 classes**, including all GATE 7–21F contracts. Design-only GATE 20 not runtime-tested |

---

## 11. Build & regression results (actual commands)

Environment: no JDK on `PATH`, no Android SDK, no `~/.gradle`, and only
`github.com / codeload.github.com / api.github.com / registry.npmjs.org / pypi.org /
files.pythonhosted.org` reachable.

```text
$ pip3 download --no-deps -d /tmp/pp jdk4py kotlin-jupyter-kernel          exit 0  (JDK 25.0.2, Kotlin 2.3.10-RC)
$ git clone --depth 1 --sparse …/Sable/android-platforms /tmp/ap           exit 0  (android-34/android.jar, 26 MB)
$ git clone --branch 1.10.2 --sparse …/Kotlin/kotlinx.coroutines /tmp/corp exit 0
$ tools/jvm-harness/bin/bootstrap-sandbox.sh                               exit 0
    main errors: 0 (files: 328)
    compose errors: 0 [scope: GATE 16 UI closure] (files: 24, stub files: 46)
    test errors: 0 (files: 250)
$ tools/jvm-harness/bin/run.sh 'Gate21g|Gate21SessionBackendLock'
    RESULT classes=6 tests=88 passed=88 failed=0 ignored=0 time=1.9s
      anki.Gate21gArchitectureAuditTest      6
      anki.Gate21gLockAdmissionTest         34
      anki.Gate21gSessionContinuityTest     12
      anki.Gate21gStoreAndProjectionTest    10
      study.Gate21SessionBackendLockTest     7
      study.Gate21gVerificationTest         19
$ tools/jvm-harness/bin/run.sh                       (full suite)
    RESULT classes=218 tests=2645 passed=2645 failed=0 ignored=0 time=42.5s
$ ./gradlew --version                                exit 1  — BLOCKED
    Caused by: java.io.EOFException: SSL peer shut down incorrectly
      at org.gradle.wrapper.Install.forceFetch   (fetching gradle-8.7-bin.zip from services.gradle.org)
```

| Required task | Outcome |
|---|---|
| `./gradlew testDebugUnitTest` | **BLOCKED** — wrapper distribution unreachable; no Android SDK |
| `./gradlew lint` | **BLOCKED** — same |
| `./gradlew assembleDebug` / `assembleRelease` | **BLOCKED** — same |
| `./gradlew connectedDebugAndroidTest` | **BLOCKED** — no device/emulator |
| JVM harness (documented fallback) | **PASS** — 2645/2645, all compile scopes clean |

The harness is equivalence evidence, not CI proof: Kotlin 2.3/K2 vs the project's 1.9.24/K1,
shimmed JUnit/DataStore/OkHttp, and no Compose UI, instrumented, lint or APK coverage
(`tools/jvm-harness/README.md`). `docs/GATE_21L_REPORT.md` and the 21C–21I reports carry the same
limitation. Nothing in this run changes the build.

---

## 12. Invariant report

| Invariant | Result | Basis |
|---|---|---|
| INV-21G-01 one session → one configured backend | PASS | lock holds exactly one `AnkiBackendId`; tracker maps one context per session |
| INV-21G-02 immutable at binding | PASS | published at `:830` after acceptance; no rebind API exists |
| INV-21G-03 AUTO pre-binding only | PASS | one resolver site, inside `begin()` |
| INV-21G-04 preference changes inert | PASS | VER-21G-07/08; projection never reads preference |
| INV-21G-05 availability changes inert | PASS | VER-21G-17 |
| INV-21G-06 no mid-session failover | PASS | churn counter `mid-session backend switches = 0` over 1,000 events |
| INV-21G-07 every scheduler request uses the lock | PASS | §5 matrix |
| INV-21G-08 FSRS/next-card backend-owned | PASS | no client scheduler added |
| INV-21G-09 collection context bound | PASS | lock `init{}` + admission records live collection |
| INV-21G-10 unknown ≠ verified | PASS | OP-S2 matrix; `collection_identity_degraded` |
| INV-21G-11 mismatch fails closed | PASS | `collection_mismatch` blocks, lock preserved |
| INV-21G-12 restore preserves owner | PASS (scoped) | VER-21G-21; no production read-back yet |
| INV-21G-13 restore never invokes AUTO | PASS | audit test `continuation and restore consult no resolver registry preference or sibling` |
| INV-21G-14 legacy ownership never guessed | PASS | VER-21G-23 |
| INV-21G-15 turn identity session-qualified | PASS | VER-21G-12/24/26 |
| INV-21G-16 old callbacks cannot mutate new state | PASS | VER-21G-24…27 |
| INV-21G-17 GATE 11 authority retained | PASS | guard precedes the ledger; 21H routing untouched |
| INV-21G-18 no next card before commit | PASS | VER-21G-13, churn counter 0 |
| INV-21G-19 ambiguous never blind-retried | PASS | VER-21G-14 |
| INV-21G-20 reviewer actions locked + GATE 13 | PASS | VER-21G-16 |
| INV-21G-21 custom study authority preserved | **BLOCKED** | GATE 20 design-only |
| INV-21G-22 AI/TTS independent of Anki authority | PASS | VER-21G-30 (new) |
| INV-21G-23 read-context replacement cannot rebind | PASS | VER-21G-31 (new) |
| INV-21G-24 ending does not erase transactions | PASS | VER-21G-28 |
| INV-21G-25 no second resolver/registry/machine/ledger | PASS | §2 greps + architecture audit test |
| INV-21G-26 no private AnkiDroid storage access | PASS | the four 21G files import only `kotlinx.serialization`, `kotlinx.coroutines` and `core.anki` types — no `android.*`, no `androidx.*`, no AnkiDroid provider symbol |
| INV-21G-27 session vs transaction identity distinct | PASS | lock carries no commit/action id; ledgers carry their own |
| INV-21G-28 no weakening of GATE 10–21F | PASS | full suite 2645/2645, 0 production files changed |

---

## 13. Residual risks and non-claims

1. **Persisted lock is never read back in production.** `sessionLockStore.save` (executor `:834`)
   and `.clear` (`:1087`) are wired through `AppContainer.kt:743`, but `restoreSession`
   (executor `:563`) has **no production caller** — the only callers are tests
   (`Gate21iArchitectureAuditTest`, `Gate21iReviewerActionRoutingTest`). Process-death restore is
   therefore policy + API + tests, not an end-to-end app path. The 21G implementation report
   assigned this wiring to 21H; 21H is now LOCKED and did not add it. **This is the single largest
   open item behind VER-21G-21/22/23 and it is not closed by this gate.**
2. **One lock slot.** The DataStore key holds a single lock; a second `begin` in the same process
   overwrites the earlier session's persisted owner. The reducer admits one active session, so this
   matches the app's single-session reality, but overlapping sessions (as constructed in tests)
   leave the earlier one store-orphaned while its tracker entry survives.
3. **Collection identity is unavailable on the real AnkiDroid path.** Production AnkiDroid reports
   no collection key, so an AnkiDroid-bound lock records no collection and the collection dimension
   stays genuinely UNKNOWN. Reporting-backend behaviour (PC) is fake-covered only.
4. **Custom Study is unverifiable.** GATE 20 is design-only; VER-21G-29 / AUDIT-21G-16 /
   INV-21G-21 are deferred, not passed.
5. **Gradle remains unverified.** No `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease`
   or instrumented run was executed here (§11). Build success is not claimed.
6. **Long-running unresolved transactions** are reported by `restoreSession` as a barrier flag only;
   their recovery routing is 21H/21I territory and was not re-audited by this gate.

---

## 14. Handoff to GATE 21H

Contracts already implemented and safe to consume:

```text
StudySessionId            StudySessionBackendLock.studySessionId (non-blank, enforced)
Locked BackendId          StudySessionBackendLock.backendId (immutable, exact configured id)
Collection context        StudySessionBackendLock.collection / .deckRef (nullable = genuinely unknown)
ReviewTurnId              session-qualified; executor guard refuses a foreign turn pre-dispatch
ReviewCommit context      commitId carries studySessionId + backendId; 21H OP-M1/M2/M3 routing owns it
Durable ReviewCommitRecord ReviewCommitLedger, unchanged by 21G
Blocked session semantics  StudySessionContinuation.Blocked / StudySessionRestore.Blocked|Invalid
                           with the stable tokens listed in §5
```

Requires 21H (or a later gate) — **not** implemented at this HEAD:

```text
Production restore wiring        restoreSession has no production caller (§13.1)
OP-P transaction barrier at startup   the barrier flag exists; nothing consumes it on app start
Store slot semantics for overlapping sessions (§13.2)
```

This run deliberately performed none of 21H's recovery-routing work.

---

## 15. Gate lock decision

```text
GATE 21G:                       LOCKED (re-verified at 250ee64; scope unchanged)

SESSION BINDING:                PASS
BACKEND IMMUTABILITY:           PASS
SCHEDULER OWNERSHIP:            PASS
COLLECTION CONTEXT:             PASS
PERSISTENCE:                    PASS (write/clear wired; production read-back outstanding, §13.1)
RECONNECT SAFETY:               PASS
RATING INTEGRATION:             PASS
STALE CALLBACK PROTECTION:      PASS
ARCHITECTURE AUDIT:             PASS (19/20; AUDIT-21G-16 BLOCKED on GATE 20)
VERIFICATION:                   PASS (33/34; VER-21G-29 BLOCKED on GATE 20)
BUILD:                          BLOCKED (environmental — Gradle unreachable, no Android SDK)

NEXT AUTHORIZED SUB-GATE:       21H — but note 21H is already recorded LOCKED at this HEAD;
                                the outstanding item is the restore wiring in §13.1, not a
                                re-execution of 21H.
```

```text
GATE 21G — STUDY SESSION OWNERSHIP LOCKED

ONE STUDYSESSION:                  ONE BACKEND
BACKEND OWNERSHIP:                 IMMUTABLE
SCHEDULER AUTHORITY:               BACKEND-OWNED
AUTO RESOLUTION:                   PRE-SESSION ONLY
GLOBAL PREFERENCE CHANGE:          NO ACTIVE SESSION EFFECT
MID-SESSION FAILOVER:              PROHIBITED
COLLECTION MISMATCH:               FAIL CLOSED
STALE CALLBACKS:                   REJECTED
RATING COMMIT:                     EXISTING GATE 11 AUTHORITY
CROSS-BACKEND TRANSACTION REPLAY:  PROHIBITED
PC AI SERVICE ≠ PC ANKI SCHEDULER: VERIFIED (new, VER-21G-30)
READ CONTEXT ≠ SESSION LOCK:       VERIFIED (new, VER-21G-31)
```
