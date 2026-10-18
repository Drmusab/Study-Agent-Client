# GATE 21M — Entry Contract (Revision 2)

**Date:** 2026-10-18 (UTC)
**Repository:** `Drmusab/Study-Agent-Client`
**Source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Branch:** `arena/19fcfcc5-study-agent-client`
**Worktree at gate start:** clean (`git status --short` → empty)
**Execution:** repository inspection → contract reconciliation → source audit → executed verification → documentation.
**Production code changed by this run:** two files — `DiagnosticsScreen.kt` (observe the asynchronous
unresolved read) and `AnkiBackendSectionUi.kt` (withhold `open_ankidroid` when the app is known to be
absent). Both are wiring defects found during verification.
**Test code changed by this run:** one test added to `Gate21mBackendSelectionControllerTest`; one
existing assertion strengthened in `Gate21mDiagnosticsSafetyTest`; one source-audit assertion updated
in `Gate21mArchitectureAuditTest`; one action assertion extended in `Gate21mBackendSelectionUiTest`.
**Prior gate documents changed:** none.

```text
GATE 21M:                    AUTHORIZED (Revision 2 entry contract)
GATE 21L:                    BLOCKED
GATE 21L BLOCKER:            GATE 20 precondition not met
GATE 20:                     DESIGN_ONLY / BLOCKED
PRODUCTION PC ANKI ADAPTER:  ABSENT
CAPABILITY MODEL:            IMPLEMENTED (GATE 21C/21D/21E)
GATE 21 FINAL LOCK:          NOT AUTHORIZED / NOT IMPLIED BY 21M
```

---

## 1. Corrected entry contract

Revision 2 replaces the earlier sequencing rule ("21A–21L must all be locked before 21M").
The current contract is:

```text
1. GATE 21M is explicitly authorized under the current GATE 21 plan.
2. All applicable prior implemented contracts are inspected and reused.
3. GATE 21L remains a documented blocked dependency.
4. No unavailable GATE 20 feature is implemented, simulated or claimed.
5. No production PC Anki functionality is claimed.
6. GATE 21M lock eligibility and GATE 21 final closure remain separate decisions.
```

This file does **not** authorize GATE 21N, GATE 20 or GATE 21L.

### Repository evidence for the two blocking facts

| Fact | Source evidence |
|---|---|
| 21M is the next authorized sub-gate | `docs/GATE_21_FINAL_LOCK.md:538` — `NEXT AUTHORIZED GATE: 21M`; `docs/GATE_21K_NOTE_CREATION_BINDING.md:310` — `NEXT AUTHORIZED GATE: 21M — Backend Selection UI & Diagnostics` |
| 21L is blocked | `docs/GATE_21L_REPORT.md:3` — `BLOCKED — IMPLEMENTATION AUTHORIZED: NO`; `docs/GATE_21L_BLOCKED_REPORT.md`; `docs/BACKEND_OWNERSHIP_INVARIANTS.md` §6.3 |
| 21L's blocker is GATE 20 | `docs/GATE_21L_REPORT.md` §2; `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md:3` — Phase B **BLOCKED**, Phase C **not started** |
| 21M has no prior lock artifact | `docs/GATE_21_FINAL_LOCK.md:532` — 21M recorded as **NOT STARTED**; `docs/GATE_21K_NOTE_CREATION_BINDING.md:305` — same |

Revision 2 explicitly supersedes the older "return to GATE 20" instruction for **21M execution only**.
The older documents are left unchanged as historical records; this file is the current entry contract
for this run. The R2 rule is not a claim that 21L is resolved.

---

## 2. Source reconciliation: what was actually at HEAD

The gate began with a mandatory source inspection rather than trusting the prior reports. Two facts
materially changed the shape of the work:

### 2.1 The checkout has a single root commit

```text
$ git log --oneline --graph -20
* 19c8a0d Merge pull request #93 from Drmusab/arena/06bebb8f-study-agent-client

$ git rev-list --parents -n 1 HEAD
19c8a0d76169b18e4f2bf1787d107fbc96c41069
```

The commit has **no parents**: the entire tree is one squashed snapshot. Consequently:

- no per-file history can attribute a change to a gate;
- `git log --full-history -- <21M file>` returns only `19c8a0d`;
- revision-level comparison to the reported 229/2799 run is not possible in this checkout.

### 2.2 GATE 21M implementation was already present

The source tree contains a complete GATE 21M Revision 2 implementation, including:

| Area | Present at HEAD |
|---|---|
| Selection presentation model | `ui/components/anki/AnkiBackendSectionUi.kt` (900 at HEAD; 908 after correction) |
| Read-only diagnostics model | `ui/components/anki/AnkiBackendDiagnosticsUi.kt` (489 lines) |
| Selection controller | `ui/screens/settings/AnkiBackendSelectionController.kt` (254 lines) |
| Settings integration | `SettingsScreen.kt`, `SettingsViewModel.kt` |
| Diagnostics integration | `DiagnosticsScreen.kt`, `DiagnosticsViewModel.kt` |
| Read-only transaction vocabulary | `core/anki/AnkiUnresolvedTransactions.kt`, `data/repository/LedgerUnresolvedTransactionReader.kt` |
| Composition root / navigation | `di/AppContainer.kt`, `ui/navigation/AppNavHost.kt` |
| GATE 21M tests | 7 classes, 115 tests (114 before the one test added by this run) |

No `docs/GATE_21M_*` file existed. The prior lock documents still said 21M was **NOT STARTED**.
That is a documentation/evidence gap, not proof that the code is correct. This run therefore treated
the existing implementation as an **audit subject**, not as a completed gate:

1. read every GATE 21M production file;
2. checked it against the R-20-2 boundary and the GATE 21B/21C/21D/21E/21F/21G contracts;
3. executed the existing GATE 21M suite;
4. executed the full harness;
5. re-ran the capability pins;
6. attempted Gradle/lint/build/instrumentation;
7. closed one test gap (the required AUTO → AnkiDroid → AUTO persistence sequence);
8. produced this documentation set with honest evidence categories.

The implementation is not described as "created by this run". It is described as **reused and
independently audited at this HEAD**, which is what actually happened.

---

## 3. Prerequisite status

```text
GATE 20:                     DESIGN_ONLY / BLOCKED
GATE 21A–21F:                LOCKED (per their reports; not re-litigated here)
GATE 21G:                    LOCKED (re-verified at a later revision by its own report)
GATE 21H / 21I:              LOCKED
GATE 21J:                    implemented + harness-verified; not locked pending decision approval
GATE 21K:                    implemented + harness-verified
GATE 21L:                    BLOCKED
GATE 21M:                    authorized by Revision 2
GATE 21N:                    has a STOP/BLOCKED report at HEAD; not executed by this gate
```

GATE 21L being blocked does **not** block 21M under Revision 2. It does block:

- any Custom Study / filtered-session lifecycle implementation;
- any claim that PC Custom Study is supported;
- GATE 21 final closure.

### Production PC Anki adapter

```text
$ grep -rnE "PcAnkiBackend|class .*PcAgent.*Backend|AnkiBackendId\.PcAgent\(" app/src/main/java
app/src/main/java/com/studyagent/client/core/anki/AnkiBackendId.kt:19:    data class PcAgent(...)
```

That is an **identity type**, not an adapter. The only production registry construction is:

```kotlin
// app/src/main/java/com/studyagent/client/di/AppContainer.kt:601
AnkiBackendRegistry(listOf(ankiDroidBackend))
```

Therefore:

```text
PC ANKI PRODUCTION ADAPTER: ABSENT
PC ANKI REGISTERED INSTANCE: NONE
PC ANKI OPERATIONAL ROW:    NONE (informational, disabled row only)
PC CUSTOM STUDY:            UNSUPPORTED
```

---

## 4. Evidence baseline and current measurement

### 4.1 Reported history (not re-used as a pass)

| Evidence | Reported value |
|---|---|
| Reproduced baseline | 227 classes / 2756 tests |
| Updated harness (prior report) | 229 classes / 2799 tests / 0 failed |
| Capability pins | 13 hold / 0 disagree / 0 not verifiable |
| Gradle / lint / build / instrumentation | UNVERIFIED |

The 229/2799 figure is retained as historical report data. It is **not** presented as this run's
result and cannot be reproduced as a separate revision in this parentless checkout.

### 4.2 This run (executed)

| Check | Command | Result |
|---|---|---|
| Harness bootstrap | `tools/jvm-harness/bin/bootstrap-gate20.sh` | exit 0; main 344 files, compose (GATE 16 closure) 0 errors, test 271 files |
| GATE 21M suite | `tools/jvm-harness/bin/run.sh 'Gate21m'` | **classes=7 tests=115 passed=115 failed=0** |
| Full harness | `tools/jvm-harness/bin/run.sh` | **classes=237 tests=2924 passed=2924 failed=0** (40.4 s) |
| Capability pins | `tools/check-capability-pins.sh /tmp/gate21m-pins` | **13 hold / 0 disagree / 0 not verifiable**, exit 0 |
| Gradle wrapper | `JAVA_HOME=… ./gradlew --version` | exit 1: Gradle 8.7 distribution download failed (`services.gradle.org` SSL handshake) |
| Gradle tasks | `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease`, `connectedDebugAndroidTest` | all exit 1 before configuration for the same reason |
| Android SDK | environment inspection | absent (`ANDROID_HOME` unset, no SDK directory) |
| Device/emulator | environment inspection | absent |

The current classes/tests include 21M (7 classes / 115 tests). The rest of the delta from the
historical report is present at HEAD and cannot be attributed per gate in a squashed snapshot.

---

## 5. Stop conditions

The R2 stop rule is: stop only if a safety-critical prerequisite **for 21M itself** is absent or
contradictory. None applies:

| Prerequisite | Status |
|---|---|
| Existing registry | present (`AnkiBackendRegistry`, GATE 21D) |
| Existing preference contract | present (`BackendPreferenceRepository`, GATE 21E) |
| Existing resolver | present (`AnkiBackendResolver2`, GATE 21E) |
| Existing capability model | present (`AnkiBackendState`, `AnkiCapabilityStatus`) |
| Existing session lock | present (`AnkiStudyBackendLockTracker`, GATE 21G) |
| Existing Settings/Compose architecture | present |
| Existing diagnostics projections | partially present; absent projections are reported as `NOT_PROJECTED` |
| GATE 21L | blocked; explicitly allowed to remain blocked |
| GATE 20 | absent; explicitly kept absent |

No new backend, resolver, registry, scheduler, ledger, session machine or recovery system was created.

---

## 6. What this gate changed, and what it did not

### Changed by this run

| File | Nature |
|---|---|
| `docs/GATE_21M_*.md` (12 files) + `docs/GATE_21N_HANDOFF.md` | Gate documentation and evidence |
| `app/src/test/java/com/studyagent/client/ui/Gate21mBackendSelectionControllerTest.kt` | One deterministic test: AUTO → AnkiDroid → AUTO converges on the authoritative store |
| `app/src/test/java/com/studyagent/client/ui/Gate21mDiagnosticsSafetyTest.kt` | One existing AMBIGUOUS-ReviewCommit assertion strengthened with a second owner |
| `app/src/main/java/com/studyagent/client/ui/screens/diagnostics/DiagnosticsScreen.kt` | Observe `ankiPendingTransactions` so asynchronous ledger counts replace `NOT_PROJECTED` |
| `app/src/test/java/com/studyagent/client/anki/Gate21mArchitectureAuditTest.kt` | Source audit updated for the corrected call shape and the observation requirement |
| `app/src/main/java/com/studyagent/client/ui/components/anki/AnkiBackendSectionUi.kt` | Withhold `open_ankidroid` when the registered AnkiDroid backend is `NotInstalled` |
| `app/src/test/java/com/studyagent/client/ui/Gate21mBackendSelectionUiTest.kt` | Assert the launcher action is absent in `NotInstalled` |

### Not changed by this run

- `GATE_20_*`, `GATE_21L_*`, `GATE_21N_*`, `GATE_21_FINAL_LOCK.md` — preserved verbatim.
- Production GATE 21M files other than `DiagnosticsScreen.kt` and `AnkiBackendSectionUi.kt` — audited,
  not rewritten.
- Any backend, ledger, schema or persisted format.
- Any prior gate status.

The prior `GATE_21_FINAL_LOCK.md` banner remains internally inconsistent (it says `GATE 21 LOCKED`
while its own matrix records 21M not started and 21L blocked). That inconsistency is recorded here and
is **not** repaired by editing a previous gate's document. GATE 21M does not make GATE 21 final.
