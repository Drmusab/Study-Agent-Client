# GATE 21M — Authoritative Verification Report (Revision 2)

**Date:** 2026-10-18 (UTC)
**Source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Branch:** `arena/19fcfcc5-study-agent-client`
**Verification source:** Part V of the R2 prompt (`VER-21M-R2-01` … `VER-21M-R2-49`).
**Status vocabulary:** `PASS`, `PASS (source)`, `BLOCKED`, `NOT_APPLICABLE`, `FAIL`.

```text
PASS:            42
BLOCKED:          7  (VER-16, VER-39, VER-40, VER-41, VER-46, VER-47, VER-48)
NOT_APPLICABLE:   0
FAIL:             0
```

A `BLOCKED` row is not a pass. It means the required environment (device/emulator, Gradle
distribution, Android SDK) was unavailable.

---

## 1. Executed baseline

| Evidence | Command | Result |
|---|---|---|
| Harness bootstrap | `tools/jvm-harness/bin/bootstrap-gate20.sh` | exit 0; main 344 files, compose GATE 16 closure 0 errors, test 271 files |
| GATE 21M suite | `tools/jvm-harness/bin/run.sh 'Gate21m'` | **classes=7 tests=115 passed=115 failed=0** |
| Full harness | `tools/jvm-harness/bin/run.sh` | **classes=237 tests=2924 passed=2924 failed=0** (40.4 s) |
| Capability pins | `tools/check-capability-pins.sh /tmp/gate21m-pins` | **13 hold / 0 disagree / 0 not verifiable**, exit 0 |
| Gradle wrapper | `JAVA_HOME=… ./gradlew --version` | exit 1 — distribution download from `services.gradle.org` failed TLS handshake |
| Gradle tasks | `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease`, `connectedDebugAndroidTest` | each exit 1 before configuration (same wrapper failure) |
| Android SDK / device | environment inspection | absent / absent |

During the matrix pass two production wiring defects were found. `DiagnosticsScreen.kt` did not
observe the asynchronous `ankiPendingTransactions` read, so the card could stay on `NOT_PROJECTED`;
the item now collects the state and recomputes the rows. `AnkiBackendSectionUi.kt` offered
`open_ankidroid` while AnkiDroid was known to be `NotInstalled`; the action is now withheld.
`Gate21mArchitectureAuditTest` and `Gate21mBackendSelectionUiTest` assert both corrections.

The test count includes the one test added by this run, the AMBIGUOUS-ReviewCommit assertion added
inside an existing diagnostics test, the updated source-audit assertion, and the extended
launcher-action assertion. GATE 21M remains 115 tests.

---

## 2. Verification matrix

### A. Entry contract and capability boundary

| ID | Result | Evidence |
|---|---|---|
| **VER-21M-R2-01** 21M proceeds while 21L is BLOCKED | **PASS** | R2 entry doc; `GATE_21L_REPORT.md:3`; `GATE_21L_BLOCKED_REPORT.md`; no 21L implementation in diff |
| **VER-21M-R2-02** No GATE 20 functionality introduced | **PASS** | Matrix rows `AUDIT-21M-R2-12` and `AUDIT-21M-R2-25`; no `CustomStudyCoordinator`, `FilteredDeckReview`, `StudySessionMachine`, scheduler or lifecycle type added; diff is docs, tests and two wiring fixes |
| **VER-21M-R2-03** No production PC Anki adapter → no operational entry | **PASS** | `Gate21mR20_2BoundaryTest.the production inventory yields no operational PC entry…`; `AppContainer.kt:601` |
| **VER-21M-R2-04** Fake PC fixtures do not appear in the production registry | **PASS** | `the composition root still registers exactly one configured backend`; `selectability follows registration…`; `a registered test double…` |
| **VER-21M-R2-05** PC AI service cannot mark PC Anki Ready | **PASS** | No code path from agent connectivity to Anki availability; PC row is informational and never `Ready`; `notImplementedExplanation()` states the distinction |
| **VER-21M-R2-06** PC Custom Study cannot be shown operational | **PASS** | Only `CUSTOM_STUDY_NOT_IMPLEMENTED` / `CUSTOM_STUDY_LIFECYCLE_NOT_IMPLEMENTED`; no lifecycle control; `AUDIT-21M-R2-12` |
| **VER-21M-R2-07** Unsupported operation fails closed | **PASS** | `AnkiCapabilityStatus` constructor invariant; `CustomStudyPolicy` tests in full harness; UI has no control that bypasses the coordinator |
| **VER-21M-R2-08** Future multi-instance UI works with Fake instances | **PASS** | `the section projects the registry inventory…` (AnkiDroid + `pc_agent:home` rows); stress test with two PC identities; `registering a real instance…` |

### B. Settings and preference

| ID | Result | Evidence |
|---|---|---|
| **VER-21M-R2-09** Backend Settings screen reachable | **PASS (source)** | `AppNavHost.kt:541` Settings route; `SettingsScreen.kt:525` inserts `AnkiBackendSectionCard`; no instrumented tap available |
| **VER-21M-R2-10** Empty Registry renders truthful state | **PASS** | `an empty inventory says so instead of showing a backend list`; empty inventory model + no Refresh action |
| **VER-21M-R2-11** AUTO selection persists correctly | **PASS** | `a write the authoritative store echoes back is reported as saved`; new `AUTO to AnkiDroid to AUTO…` |
| **VER-21M-R2-12** Exact configured AnkiDroid selection persists | **PASS** | same tests; `selectedKey = "ankidroid_local"`; `preferenceToken = "explicit:ankidroid_local"` |
| **VER-21M-R2-13** Removed selected backend does not silently become AUTO | **PASS** | `a preference naming an unconfigured backend is reported and never rewritten to AUTO`; `PREFERRED_BACKEND_NOT_CONFIGURED` |
| **VER-21M-R2-14** Failed persistence does not display saved success | **PASS** | `a refused write is reported as failed…`; `a write that throws is a refusal…`; `a superseded write…` |
| **VER-21M-R2-15** Rapid preference changes converge | **PASS** | `a superseded write never publishes its late result`; new sequential AUTO→AnkiDroid→AUTO test; generation guard |
| **VER-21M-R2-16** Selection survives supported recreation | **BLOCKED** | Requires a device/Compose recreation test; code reads the authoritative `StateFlow`, but no instrumented test executed. Not claimed as PASS. |

### C. Availability and capability

| ID | Result | Evidence |
|---|---|---|
| **VER-21M-R2-17** Ready only from sufficient evidence | **PASS** | `readyNow` is true only for `AnkiAvailability.Ready`; `every availability state…`; `an unchecked backend…` |
| **VER-21M-R2-18** Permission missing distinct from backend absent | **PASS** | `PERMISSION_REQUIRED` vs `NOT_INSTALLED`; domain `AnkiAvailability` types; vocabulary test |
| **VER-21M-R2-19** Transport connected does not imply Anki ready | **PASS** | `an implemented capability on a disconnected backend…`; `AGENT_DISCONNECTED` + `NOT_READY`; no connectivity source in UI |
| **VER-21M-R2-20** UNKNOWN/CHECKING do not render READY | **PASS** | `an unchecked backend claims neither support nor refusal`; `Checking` → `UNKNOWN` implementation, `NOT_READY`/`UNKNOWN` readiness |
| **VER-21M-R2-21** Stale health distinguished from fresh | **PASS** | No timestamp claimed; `OBSERVATION_NOTICE`; stress freshness-word scan zero; `StateFlow` latest-value observation |
| **VER-21M-R2-22** Supported on unavailable backend ≠ unsupported | **PASS** | `a ready backend still reports the capabilities it does not implement`; `an implemented capability on a disconnected backend is supported and not available now` |
| **VER-21M-R2-23** Unknown capability not represented as implemented | **PASS** | `UNKNOWN` labels “Unknown”; no static claim + not Ready → `UNKNOWN`; `an unchecked backend…` |
| **VER-21M-R2-24** Manual refresh invokes read-only mechanism | **PASS** | `refresh probes each configured instance once, read-only…`; `ProbeOnlyBackend.calls == ["refreshAvailability"]`; no polling test; the launcher action is withheld when AnkiDroid is `NotInstalled` |

### D. StudySession and diagnostics

| ID | Result | Evidence |
|---|---|---|
| **VER-21M-R2-25** Preference change does not change active session BackendId | **PASS** | `a preference change never rewrites an active session's owner`; lock equality asserted before/after |
| **VER-21M-R2-26** Settings display preference and active owner independently | **PASS** | `the active session owner is its own fact and is never the preference`; `one row per…` + `activeSession` model |
| **VER-21M-R2-27** Library read-context identity remains independent | **PASS** | GATE 21F isolation suite in full harness; diagnostics does not derive read owners from preference; absent projection = `NOT_PROJECTED` |
| **VER-21M-R2-28** AMBIGUOUS ReviewCommit remains on original backend | **PASS** | `unresolved transactions keep their own owner…` now includes `REVIEW_COMMIT @ ankidroid_local = AMBIGUOUS`; owner preserved |
| **VER-21M-R2-29** No unsafe retry/force-commit actions | **PASS** | `the action set is closed…`; `no rendered row offers a mutation, retry-all or reassignment`; `AUDIT-21M-R2-14` |
| **VER-21M-R2-30** NoteMutation CONFLICT/AMBIGUOUS distinct | **PASS** | same diagnostics test asserts `AMBIGUOUS=1, CONFLICT=1`; family status names preserved |
| **VER-21M-R2-31** Missing GATE 20/21L projections do not fabricate diagnostics | **PASS** | `the filtered-session family states its omission…`; `NOT_APPLICABLE`; no lifecycle family; the card observes the async read instead of freezing `NOT_PROJECTED` |
| **VER-21M-R2-32** Verified collection mismatch displayed | **PASS** | `every identity assurance category is rendered verbatim` → `AUTHORITATIVE_MISMATCH` |
| **VER-21M-R2-33** Unknown collection identity not presented as verified | **PASS** | `IDENTITY_UNVERIFIABLE(nominal|degraded)`; `the active session owner line…`; no “same collection” copy |

### E. Async, privacy and UI

| ID | Result | Evidence |
|---|---|---|
| **VER-21M-R2-34** Old refresh cannot update a removed/reconfigured entry | **PASS** | `a backend removed while its probe ran is reported as absent…`; `refreshGeneration` |
| **VER-21M-R2-35** Old StudySession callback cannot replace new owner | **PASS (source)** | UI holds no session callback; it reads the lock map snapshot. GATE 21G lock identity is immutable; no second session machine. No direct UI callback test exists |
| **VER-21M-R2-36** Opening diagnostics triggers zero Anki mutation calls | **PASS** | `DiagnosticsViewModel.init` only calls `reader.read()`; the screen only observes that state; reader is read lambdas only; no backend reference; architecture `AUDIT-21M-R2-13` |
| **VER-21M-R2-37** No credentials/private study data in diagnostics | **PASS** | `availability free text never reaches a diagnostics row`; `a collection key is never measured…`; `a write that throws…`; no content fields in `AnkiUnresolvedTransactionFact` |
| **VER-21M-R2-38** Recomposition does not create duplicate backend services | **PASS** | `nothing is probed or written while the section is merely projected`; one controller per ViewModel; no service construction in Composable bodies |
| **VER-21M-R2-39** TalkBack identifies selected/unsupported/unavailable | **BLOCKED** | Source uses `Role.RadioButton`, text status and disabled explanations, but no device/TalkBack execution exists |
| **VER-21M-R2-40** Text scaling, keyboard, RTL remain usable | **BLOCKED** | Source has no LTR/RTL override and logical padding; no device rendering/keyboard test |
| **VER-21M-R2-41** Existing dark/light theme shows critical status | **BLOCKED** | Uses `AppColors` theme tokens and text labels; no visual/emulator execution |

### F. Existing evidence and regression

| ID | Result | Evidence |
|---|---|---|
| **VER-21M-R2-42** Locate/validate reported 229/2799 harness evidence | **PASS** | `docs/GATE_21_FINAL_LOCK.md` records 227/2756 at `2c36e27`; prompt/report records 229/2799. This checkout is a parentless squash, so the 229/2799 revision is **historical, not re-executed**; current execution reported below |
| **VER-21M-R2-43** Re-run applicable harness on current HEAD | **PASS** | **237 classes / 2924 tests / 0 failed**, 40.4 s; GATE 21M 7/115 |
| **VER-21M-R2-44** Re-run capability pins, no unauthorized claim change | **PASS** | 13 hold / 0 disagree / 0 not verifiable; source SHAs printed in `GATE_21M_ANDROID_VALIDATION_STATUS.md` |
| **VER-21M-R2-45** Applicable previous-gate regressions | **PASS** | Full harness includes GATE 11–21 suites; 0 failures. GATE 20/21L are not claimed tested because they are not implemented |
| **VER-21M-R2-46** Attempt Gradle unit tests | **BLOCKED** | `./gradlew testDebugUnitTest --offline` exit 1; Gradle 8.7 distribution unavailable |
| **VER-21M-R2-47** Attempt Android lint/build | **BLOCKED** | `lint`, `assembleDebug`, `assembleRelease` exit 1 before configuration; no Android SDK |
| **VER-21M-R2-48** Execute instrumentation | **BLOCKED** | `connectedDebugAndroidTest` exit 1; no device/emulator and no Gradle distribution |

### G. Deterministic stress

| ID | Result | Evidence |
|---|---|---|
| **VER-21M-R2-49** UI/capability stress | **PASS** | `Gate21mUiCapabilityStressTest`: seed `21_000_049`; 600 model events + 120 controller events; 3 tests PASS. Safety counters (see §3) all zero |

---

## 3. Stress-test safety counters

The stress test accumulates violations and asserts the sum is zero. The measured result was zero for
all counters. The mapping below records where each required counter is enforced.

| Required safety counter | Measured | Where enforced |
|---|---:|---|
| Nonexistent PC Anki shown operational | 0 | `Gate21mR20_2BoundaryTest` + stress `inventedInstances` |
| Unsupported PC Custom Study shown operational | 0 | custom-study tests + stress `customStudyControls` |
| Wrong active StudySession backend display | 0 | stress `silentAutoConversion` + controller lock test |
| Preference silently rewritten | 0 | stress `silentAutoConversion`; unresolved-preference tests |
| Stale health state accepted | 0 | generation tests; no freshness claims; stress freshness scan |
| Unauthorized mutation calls | 0 | `ProbeOnlyBackend.calls`; architecture `AUDIT-21M-R2-13`; no mutation action |
| Cross-backend transaction controls | 0 | diagnostics tests; `AUDIT-21M-R2-14`; no action |
| Fake production capability claims | 0 | R20_2 boundary tests; fake not persistable/selectable |
| Credential leaks | 0 | diagnostics `assertNothingLeaks`; controller class-name-only logs |
| Duplicate backend service creation | 0 | one controller per VM; matrix row `AUDIT-21M-R2-22`; `Gate21mArchitectureAuditTest` “the gate adds presentation models and one controller, and no new authority”; stress duplicate keys |

Additional stress counters: `duplicateKeys`, `selectableInformationalRow`, `selectedRowCountViolations`,
`unknownSelectionKey`, `capabilityOverPromise`, `fakeSelectable`, `freshnessClaims`, `blankCopy`,
`missingKindDisclosure`, `unavailableNotSelectable` — all zero.

The exact measured totals are not printed on success, but the assertion is:

```kotlin
assertEquals(..., 0, totals.values.sum())
```

Any nonzero counter would fail the test with the iteration and full input. The test passed.

---

## 4. Explicit non-claims

- No claim that Gradle, lint, APK build or instrumentation passed.
- No claim that the 229/2799 historical revision was re-executed; only the current 237/2924 run is
  executed evidence.
- No claim that a production PC Anki backend exists.
- No claim that GATE 20 or GATE 21L is implemented.
- No claim that GATE 21M is locked; seven verification rows are BLOCKED.
- No claim that GATE 21 is finally locked.

---

## 5. Result

```text
IMPLEMENTATION:       COMPLETE AT HEAD (audited; one test hardened; two wiring defects corrected)
HARNESS:              PASS (237 classes / 2924 tests / 0 failed)
GATE 21M SUITE:       PASS (7 classes / 115 tests / 0 failed)
CAPABILITY PINS:      PASS (13 / 0 / 0)
ARCHITECTURE AUDIT:   PASS (26/26 source-level)
ANDROID VALIDATION:   BLOCKED / UNVERIFIED
MANDATORY FAILURES:   0
GATE 21M LOCK:        BLOCKED PENDING ANDROID EVIDENCE
GATE 21 FINAL:        NOT LOCKED
```
