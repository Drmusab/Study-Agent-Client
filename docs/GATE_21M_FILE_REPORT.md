# GATE 21M — File-by-File Implementation Report

**Date:** 2026-10-18 (UTC)
**Source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Important attribution note:** the GATE 21M production set was already present at this squashed HEAD.
It was **reused and independently audited** by this run. This report distinguishes `REUSED` production
files from the files this run actually `MODIFIED` or `CREATED`. Two production files required
corrections; the rest of the production set was reused as found.

---

## 1. Files changed by this run

| Checkpoint | Actual file | Action | Owner | Change | Why required |
|---|---|---|---|---|---|
| 21M-11 / 21M-15 | `app/src/test/java/com/studyagent/client/ui/Gate21mBackendSelectionControllerTest.kt` | **MODIFIED** (+28 lines) | Verification | Added `AUTO to AnkiDroid to AUTO converges on the authoritative store` | The required persistence sequence had no single executable case; it now asserts order, final authoritative value, selected row, writes, and `Saved(target=auto)` |
| 21M-17 / 21M-19 | `app/src/test/java/com/studyagent/client/ui/Gate21mDiagnosticsSafetyTest.kt` | **MODIFIED** (+7/−1 lines) | Verification | Added an `AMBIGUOUS` `REVIEW_COMMIT` fact owned by `ankidroid_local`, plus its group assertion | The VER-21M-R2-28 claim covered only a PC-owned `SUBMITTING` record; an ambiguous record on a different owner is now proven to keep that owner |
| 21M-17 | `app/src/main/java/com/studyagent/client/ui/screens/diagnostics/DiagnosticsScreen.kt` | **MODIFIED** (+7/−1 lines) | Production wiring | Observe `ankiPendingTransactions` inside the `anki-backends` item and recompute the rows from it | The ledger read is asynchronous; without observation the card could stay on `NOT_PROJECTED` for the screen's lifetime |
| 21M-20 | `app/src/main/java/com/studyagent/client/ui/components/anki/AnkiBackendSectionUi.kt` | **MODIFIED** (+10/−2 lines) | Production wiring | Withhold the `open_ankidroid` action when the registered AnkiDroid backend reports `NotInstalled` | A launcher button that can only log “not installed” is a non-working action; the app is still launchable in `Checking`/permission states |
| 21M-17 | `app/src/test/java/com/studyagent/client/anki/Gate21mArchitectureAuditTest.kt` | **MODIFIED** (+3/−1 lines) | Verification | Assert the new call shape and that the asynchronous read is observed | Keep the source audit aligned with the corrected wiring |
| 21M-20 | `app/src/test/java/com/studyagent/client/ui/Gate21mBackendSelectionUiTest.kt` | **MODIFIED** (+6 lines) | Verification | Extended the launcher-action assertion to `NotInstalled` | A withheld action must be proven absent, not only documented |
| Documentation | `docs/GATE_21M_ENTRY_CONTRACT_R2.md` | **CREATED** | Gate record | Entry contract, source reconciliation, evidence baseline | No 21M documentation existed at HEAD |
| Documentation | `docs/GATE_21M_CAPABILITY_AWARE_UI.md` | **CREATED** | Gate record | Capability presentation contract | Same |
| Documentation | `docs/GATE_21M_BACKEND_SELECTION_CONTRACT.md` | **CREATED** | Gate record | Selection/persistence/ownership contract | Same |
| Documentation | `docs/GATE_21M_AVAILABILITY_PRESENTATION.md` | **CREATED** | Gate record | Availability/readiness/freshness/refresh contract | Same |
| Documentation | `docs/GATE_21M_DIAGNOSTICS_SAFETY.md` | **CREATED** | Gate record | Read-only diagnostics and privacy contract | Same |
| Documentation | `docs/GATE_21M_R20_2_COMPLIANCE.md` | **CREATED** | Gate record | PC Anki boundary evidence | Same |
| Documentation | `docs/GATE_21M_ARCHITECTURE_AUDIT.md` | **CREATED** | Gate record | 26-row independent audit | Same |
| Documentation | `docs/GATE_21M_FILE_REPORT.md` | **CREATED** | Gate record | This report | Same |
| Documentation | `docs/GATE_21M_VERIFICATION_REPORT.md` | **CREATED** | Gate record | 49-row verification matrix | Same |
| Documentation | `docs/GATE_21M_ANDROID_VALIDATION_STATUS.md` | **CREATED** | Gate record | Gradle/lint/build/instrumentation evidence | Same |
| Documentation | `docs/GATE_21M_RESIDUAL_RISKS.md` | **CREATED** | Gate record | Residual risk register | Same |
| Documentation | `docs/GATE_21M_FINAL_STATUS.md` | **CREATED** | Gate record | Truthful gate decision | Same |
| Documentation | `docs/GATE_21N_HANDOFF.md` | **CREATED** | Gate record | Explicit handoff and prerequisites | Same |

### Verification of the test change

```text
Gate21m subset:  classes=7 tests=115 passed=115 failed=0
Full harness:    classes=237 tests=2924 passed=2924 failed=0
```

Before the changes: GATE 21M 114 tests; full harness 2923 tests. The only count delta is the one
added controller test; the diagnostics change strengthens an existing test without changing the
count.

---

## 2. Production files (audited; two corrected)

The table below covers the implementation the gate is about. `LOCKED CONTRACT REFERENCES` are the
documents whose rules the file must obey. `GATE 21L DEPENDENCY` is `NONE` for every file except the
Custom Study disclosure, which is a read-only statement about the blocked 21L/20 scope, not an
implementation.

### 2.1 `ui/components/anki/AnkiBackendSectionUi.kt` (908 lines after correction; 900 at HEAD)

```text
CHECKPOINT:          21M-02, 03, 04, 05, 06, 07, 08, 09, 12, 13, 14, 15, 20, 25
ACTION:              MODIFIED (+10/−2)
OWNER:               Presentation model
LOCKED CONTRACTS:    21B DECISION-21-15/32/35/36/37; 21C §3.5; 21D observation contract;
                     21E BACKEND_SELECTION_POLICY; 21G session lock
IMPLEMENTATION:      Immutable section state; AUTO row; instance rows keyed by stableId;
                     informational unimplemented-kind rows; unresolved-preference row;
                     capability rows on two dimensions; custom-study disclosure; active-session
                     projection; save/refresh feedback; closed action enum; empty inventory;
                     `open_ankidroid` withheld when the app is known to be absent.
WHY REQUIRED:        One Android-free projection that renders only authoritative facts.
R-20-2:              Compliant. PC is never selectable; exclusion derives from configuredKinds.
GATE 21L:            NONE (only reports NOT_IMPLEMENTED / LIFECYCLE_NOT_IMPLEMENTED).
LOCAL TESTS:         Gate21mBackendSelectionUiTest, Gate21mCapabilityAvailabilityUiTest,
                     Gate21mR20_2BoundaryTest, Gate21mUiCapabilityStressTest.
SAFETY INVARIANTS:   no writes, no probes, no resolution, no identity allocation, stable keys.
RESULT:              PASS
```

### 2.2 `ui/components/anki/AnkiBackendDiagnosticsUi.kt` (489 lines)

```text
CHECKPOINT:          21M-16, 17, 18, 19, 22
ACTION:              REUSED
OWNER:               Read-only diagnostics projection
LOCKED CONTRACTS:    21B DECISION-21-27; 21C §3.5 categories; 21G lock; 21H/21I/21J/21K ledgers
IMPLEMENTATION:      Pure line builder; preference tokens; four routing roles; per-backend
                     capability/availability tokens; collection presence; active owner assurance;
                     read-context NOT_PROJECTED; per-family unresolved status counts;
                     filtered-session NOT_APPLICABLE; report revision.
WHY REQUIRED:        Troubleshooting without mutation authority or content leakage.
R-20-2:              Compliant. PC is reported as 0 configured with no adapter; no PC capability.
GATE 21L:            NONE — states the omission, does not implement it.
LOCAL TESTS:         Gate21mDiagnosticsSafetyTest (19), architecture audit rows 07/08/13/14/15.
SAFETY INVARIANTS:   tokens/enums/counts only; no keys; no free text; no actions.
RESULT:              PASS
```

### 2.3 `ui/screens/settings/AnkiBackendSelectionController.kt` (254 lines)

```text
CHECKPOINT:          21M-11, 13, 21, 23, 24, 28
ACTION:              REUSED
OWNER:               Settings presentation controller
LOCKED CONTRACTS:    21E preference repository; 21D registry refresh; 21G lock tracker;
                     21B DECISION-21-32/37
IMPLEMENTATION:      combine(statusCenter.states, preference, saveFeedback, refresh, locks)
                     .stateIn(WhileSubscribed(5000)); select() with authoritative confirmation and
                     writeGeneration; refreshStatus() with refreshGeneration and read-only probes;
                     custom-study claims keyed by identity; class-name-only failure logging.
WHY REQUIRED:        Race-sensitive presentation logic outside Android, deterministically testable.
R-20-2:              Compliant. No PC construction, no identity creation, no fallback.
GATE 21L:            NONE.
LOCAL TESTS:         Gate21mBackendSelectionControllerTest (20), stress controller phase.
SAFETY INVARIANTS:   one write (preference); no session mutation; no polling; stale rejection.
RESULT:              PASS
```

### 2.4 `ui/screens/settings/SettingsViewModel.kt` (262 lines)

```text
CHECKPOINT:          21M-23, 28
ACTION:              REUSED
OWNER:               Settings ViewModel
LOCKED CONTRACTS:    existing Settings architecture; 21G; 21E
IMPLEMENTATION:      Optional backend collaborators; one controller per ViewModel over
                     viewModelScope; delegates selectAnkiBackend / refreshAnkiBackendStatus.
WHY REQUIRED:        Lifecycle-scoped collection and Android adapter only.
R-20-2:              Compliant; no backend construction.
GATE 21L:            NONE.
LOCAL TESTS:         source audit; controller tests exercise the same seam.
SAFETY INVARIANTS:   no policy in the ViewModel; no new service; no write on recomposition.
RESULT:              PASS
```

### 2.5 `ui/screens/settings/SettingsScreen.kt` (1352 lines)

```text
CHECKPOINT:          21M-02, 04, 05, 06, 09, 12, 13, 15, 20, 25, 26, 27
ACTION:              REUSED
OWNER:               Settings Compose screen
LOCKED CONTRACTS:    UI design system; existing Settings routes; 21M presentation model
IMPLEMENTATION:      Anki backend card in the existing LazyColumn; ChoiceRow selection; exact
                     identity display; capability expansion with two dimensions; custom-study
                     disclosure; active-session row; save/refresh state; warning/empty states;
                     closed action buttons; stable test tags.
WHY REQUIRED:        Integrates the section into the real Settings surface without redesign.
R-20-2:              Compliant; PC row disabled and explained; no invented instance.
GATE 21L:            NONE.
LOCAL TESTS:         source audit only; Compose execution BLOCKED (no device / Gradle).
SAFETY INVARIANTS:   no probe/write in composition; logical padding; text labels; 52/48 dp minima.
RESULT:              PASS (source audit); runtime composition BLOCKED
```

### 2.6 `ui/screens/diagnostics/DiagnosticsViewModel.kt` (173 lines)

```text
CHECKPOINT:          21M-16, 17, 23
ACTION:              REUSED
OWNER:               Diagnostics ViewModel
LOCKED CONTRACTS:    21B DECISION-21-27; 21G; each ledger's unresolved projection
IMPLEMENTATION:      One read of AnkiUnresolvedTransactionReader in init; builds
                     AnkiBackendDiagnosticsInput from existing flows/services; exposes
                     ankiBackendRows(); no ledger/coordinator type.
WHY REQUIRED:        Read-only diagnostics without a second data store.
R-20-2:              Compliant; no PC construction.
GATE 21L:            NONE; FILTERED_SESSION is NOT_APPLICABLE.
LOCAL TESTS:         Gate21mDiagnosticsSafetyTest; architecture audit 13.
SAFETY INVARIANTS:   no writes; no probes; one read; no per-recomposition read.
RESULT:              PASS
```

### 2.7 `ui/screens/diagnostics/DiagnosticsScreen.kt` (401 lines after correction; 395 at HEAD)

```text
CHECKPOINT:          21M-16, 17, 22
ACTION:              MODIFIED (+7/−1)
OWNER:               Diagnostics Compose screen
LOCKED CONTRACTS:    existing diagnostics screen; 21M model
IMPLEMENTATION:      One "Anki backends" card, rendered only when the surface is wired;
                     observes the asynchronous unresolved read before building rows;
                     read-only rows; existing copy/export helpers unchanged.
WHY REQUIRED:        Surface safe diagnostics to the user.
R-20-2:              Compliant; no PC operational claim.
GATE 21L:            NONE.
LOCAL TESTS:         source audit (call shape + observation); Compose execution BLOCKED.
SAFETY INVARIANTS:   no new action; no export widening; no secret path; no read on recomposition.
RESULT:              PASS (source audit); runtime composition BLOCKED
```

### 2.8 `core/anki/AnkiUnresolvedTransactions.kt` (76 lines)

```text
CHECKPOINT:          21M-17
ACTION:              REUSED (new in 21M; present at HEAD)
OWNER:               Core read-only transaction vocabulary
LOCKED CONTRACTS:    21B ownership invariants; each family's own status enum
IMPLEMENTATION:      AnkiTransactionFamily; AnkiUnresolvedTransactionFact; Read with loaded /
                     unavailable / notProjected; one-method reader interface; NOT_READ sentinel.
WHY REQUIRED:        Expose unresolved state without exposing a ledger.
R-20-2:              Compliant.
GATE 21L:            NONE.
LOCAL TESTS:         Gate21mDiagnosticsSafetyTest; architecture audit 13/14.
SAFETY INVARIANTS:   owner preserved; status token preserved; no content; no write method.
RESULT:              PASS
```

### 2.9 `data/repository/LedgerUnresolvedTransactionReader.kt` (143 lines)

```text
CHECKPOINT:          21M-17
ACTION:              REUSED (new in 21M; present at HEAD)
OWNER:               Data-layer read-only adapter
LOCKED CONTRACTS:    the four ledger `unresolved()` projections
IMPLEMENTATION:      Constructed from read lambdas only; reduces records to family + owner +
                     status name; reports unavailable vs notProjected; rethrows cancellation.
WHY REQUIRED:        Read four durable families without exposing a writable ledger.
R-20-2:              Compliant.
GATE 21L:            NONE.
LOCAL TESTS:         diagnostics tests; full harness.
SAFETY INVARIANTS:   no ledger reference; no backend call; no exception-to-zero.
RESULT:              PASS
```

### 2.10 `di/AppContainer.kt` (1005 lines)

```text
CHECKPOINT:          21M-08, 16, 17, 28
ACTION:              REUSED
OWNER:               Composition root
LOCKED CONTRACTS:    21D one registry; 21E one resolver; 21G one lock tracker; 21B ownership
IMPLEMENTATION:      One registry (AnkiDroid only); exposes autoPreview (existing resolver),
                     routing snapshot, read-only unresolved reader, status center, lock tracker.
WHY REQUIRED:        Wire 21M without creating a second authority.
R-20-2:              Compliant. No PC backend or configuration is constructed.
GATE 21L:            NONE.
LOCAL TESTS:         R20_2 composition-root test; architecture audit 01/05/26.
SAFETY INVARIANTS:   one registry; one resolver; ledgers stay private behind the reader.
RESULT:              PASS
```

### 2.11 `ui/navigation/AppNavHost.kt` (599 lines)

```text
CHECKPOINT:          21M-02, 16, 20, 28
ACTION:              REUSED
OWNER:               Navigation/composition
LOCKED CONTRACTS:    existing routes; 21M safe action set
IMPLEMENTATION:      Settings route passes registry/status/preference/writer/locks/autoPreview;
                     Diagnostics route passes states/preference/routing/locks/registry/reader;
                     Settings destinations for Diagnostics and Connection.
WHY REQUIRED:        Make the section reachable and wire only existing services.
R-20-2:              Compliant. No Fake/PC backend in production navigation.
GATE 21L:            NONE.
LOCAL TESTS:         source audit; R20_2 composition-root test.
SAFETY INVARIANTS:   no new route; no mutation callback; no service construction.
RESULT:              PASS
```

---

## 3. Files explicitly not changed

| File / area | Reason |
|---|---|
| `docs/GATE_20_*` | GATE 20 remains design-only; prior status must not be rewritten |
| `docs/GATE_21L_*` | BLOCKED status preserved |
| `docs/GATE_21N_*` | 21N not executed; its STOP report preserved |
| `docs/GATE_21_FINAL_LOCK.md` | Prior gate record; its internal inconsistency is reported, not edited |
| `AnkiBackend`, `AnkiDroidBackend`, ledgers, resolvers, registry, session machine | Out of 21M scope; no new backend or authority |
| `DiagnosticsRepository` export | Deliberately unchanged; 21M rows are not silently exported |
| Persisted schemas / DataStore keys | No migration or format change |

---

## 4. Test inventory (executed)

| Class | Tests | Focus |
|---|---:|---|
| `Gate21mArchitectureAuditTest` | 26 | 26 source-level audits |
| `Gate21mBackendSelectionControllerTest` | 20 | writes, confirmation, races, refresh, locks |
| `Gate21mBackendSelectionUiTest` | 21 | rows, identity, unresolved, notices, actions |
| `Gate21mCapabilityAvailabilityUiTest` | 14 | two dimensions, 12 states, custom study, AUTO copy |
| `Gate21mDiagnosticsSafetyTest` | 19 | privacy, roles, assurance, family counts, no mutation |
| `Gate21mUiCapabilityStressTest` | 3 | 600 model + 120 controller seeded events |
| `Gate21mR20_2BoundaryTest` | 12 | PC Anki / fake / custom-study boundary |
| **Total** | **115** | **all PASS** |

The full harness also executes all prior-gate suites: 237 classes / 2924 tests / 0 failed.

---

## 5. Result

```text
PRODUCTION FILES MODIFIED BY THIS RUN: 2
TEST FILES MODIFIED BY THIS RUN:       4
DOCUMENTATION FILES CREATED:           13
PRODUCTION DEFECTS FOUND / CLOSED:     2
VERIFICATION DEFECTS FOUND / CLOSED:   1
GATE 21M IMPLEMENTATION:               COMPLETE AT HEAD
GATE 21M LOCK:                         NOT CLAIMED (Android evidence missing)
```
