# GATE 21M — Final Status (Revision 2)

**Date:** 2026-10-18 (UTC)
**Repository:** `Drmusab/Study-Agent-Client`
**Source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Branch:** `arena/19fcfcc5-study-agent-client`
**Worktree at gate start:** clean
**Gate instruction:** GATE 21M Revision 2 (corrected entry contract)

---

## 1. Gate status

```text
GATE 21M — BACKEND SELECTION UI & DIAGNOSTICS

REVISION:
2

SOURCE HEAD:
19c8a0d76169b18e4f2bf1787d107fbc96c41069

BRANCH:
arena/19fcfcc5-study-agent-client

ENTRY AUTHORIZATION:
AUTHORIZED

IMPLEMENTATION:
COMPLETE (present at HEAD; independently audited; two production wiring defects corrected)

ARCHITECTURE AUDIT:
PASS (26/26 source-level; runtime accessibility/RTL rows additionally BLOCKED)

HARNESS:
PASS (237 classes / 2924 tests / 0 failed; GATE 21M 7 classes / 115 tests / 0 failed)

CAPABILITY PINS:
PASS (13 hold / 0 disagree / 0 not verifiable)

GRADLE:
UNVERIFIED

LINT:
UNVERIFIED

ANDROID BUILD:
UNVERIFIED

INSTRUMENTATION:
UNVERIFIED

FINAL 21M STATUS:
BLOCKED (Case B — implementation complete, harness and pins pass, Android evidence missing)
```

This is **not** a GATE 21M lock and **not** a GATE 21 final lock. The R2 Case B rule applies:

```text
GATE 21M:
IMPLEMENTED / PARTIALLY VERIFIED

HARNESS:
PASS

CAPABILITY PINS:
PASS

ANDROID VALIDATION:
UNVERIFIED

GATE 21M LOCK:
BLOCKED PENDING REQUIRED ANDROID EVIDENCE

GATE 21 FINAL:
NOT LOCKED
```

---

## 2. Prerequisite exceptions

```text
GATE 21L:
BLOCKED
  docs/GATE_21L_REPORT.md — BLOCKED / IMPLEMENTATION AUTHORIZED: NO
  docs/GATE_21L_BLOCKED_REPORT.md
  docs/BACKEND_OWNERSHIP_INVARIANTS.md §6.3

GATE 20:
DESIGN_ONLY / BLOCKED
  docs/GATE_20_CUSTOM_STUDY_CONTRACT.md:3
  Phase B BLOCKED, Phase C not started, IMPLEMENTATION AUTHORIZED: NO

R-20-2:
ACTIVE / ENFORCED
  No production PC Anki adapter; PC Custom Study unsupported; capability model fails closed

EXCEPTION AUTHORITY:
GATE 21M Revision 2 entry contract, which explicitly authorizes 21M while 21L remains blocked
```

GATE 21L is not resolved by this gate. GATE 20 is not implemented by this gate.

---

## 3. Production backend inventory

| Backend | Adapter implemented | Registered | Audited capabilities | UI selectable |
|---|---|---|---|---|
| AnkiDroid local | yes — `AnkiDroidBackend` | yes — `AppContainer.kt:601` | static GATE 21D claim; GATE 20 custom-study claim `NONE` | yes, even while unavailable (typed refusal) |
| PC Anki | **no production adapter** | no | none | **no** — disabled informational row only |
| Fake / test | test fixtures only | no production registration | test-only claims | no |

No instance was invented to populate the UI.

---

## 4. Settings UI summary

```text
AUTO behavior:
  Policy row keyed "preference:auto"; description states one eligible backend per new operation;
  no merge, no failover; preview supplied by AppContainer through the existing pure
  AnkiBackendResolver2 policy, resolved over the exact rendered states.

Explicit selection:
  One row per registered instance, keyed by AnkiBackendId.stableId; unavailable instances remain
  selectable; fake instances are not selectable; no duplicate rows.

Persistence:
  Repository.setPreference; Saving → authoritative echo → Saved. Refusal → Failed. Accepted but
  not echoed within 2 s → Unconfirmed. A superseded write's late result is dropped.

Missing backend:
  Exact unresolved preference is preserved and shown as PREFERRED_BACKEND_NOT_CONFIGURED (or kind
  variants); no silent AUTO conversion; no guess by kind; historical transaction owners untouched.

Capability display:
  Every AnkiRequiredCapability row on two dimensions: implementation (Supported / Not implemented /
  Unknown) and readiness (Available now / Not available now / Not checked yet).

Availability display:
  AnkiAvailability.statusCode + user-facing text; Ready only for AnkiAvailability.Ready;
  permission, absent, checking, authentication, collection, protocol, fault are distinct.

Active session indicator:
  Separate "Active study session" row from the GATE 21G lock tracker; None / One / Several;
  never derived from the preference.
```

### Empty inventory

“No Anki backend is configured” plus an explanation; no fabricated backend list and no Refresh action.

### Safe actions

Refresh status (read-only), View capability details (local), Backend configuration (existing
Connection destination), Diagnostics (existing read-only screen), Open AnkiDroid (existing launcher).
No permission-grant, retry-all, force-commit, clear, delete or reassign control.

---

## 5. R-20-2 compliance

```text
PC Anki Adapter:          ABSENT
PC Custom Study:          UNSUPPORTED
Capability Model:         IMPLEMENTED
Fail-Closed Behavior:     ENFORCED
Fake Backend Separation:  TEST-ONLY; NOT PERSISTABLE / NOT SELECTABLE IN PRODUCTION
Production UI Claims:     NO OPERATIONAL PC ANKI ROW; NO PC CUSTOM STUDY LIFECYCLE
PC Agent Distinction:     PC AI/STT/TTS connectivity is not an Anki readiness source
```

The PC row is informational and disabled:

```text
PC Anki — Not implemented in this build
token: BACKEND_KIND_NOT_IMPLEMENTED
```

The exclusion derives from the registry's configured kinds. A future real PC adapter would activate
the generic instance row without editing a domain model.

---

## 6. File changes

### Production

```text
PRODUCTION FILES MODIFIED BY THIS RUN: 2  (DiagnosticsScreen.kt: observe the async read;
                                          AnkiBackendSectionUi.kt: withhold a dead launcher action)
```

The GATE 21M production implementation was already present at HEAD and was audited. Two wiring defects
were found during verification and corrected: (1) the diagnostics card did not observe the
asynchronous unresolved-transaction read, so it could stay on `NOT_PROJECTED`; (2) the
`open_ankidroid` action was offered even when AnkiDroid was known to be `NotInstalled`, making it a
button that could only log a failure. See `GATE_21M_FILE_REPORT.md` for the complete file inventory.

### Tests

| File | Change |
|---|---|
| `app/src/test/java/com/studyagent/client/ui/Gate21mBackendSelectionControllerTest.kt` | +1 test: AUTO → AnkiDroid → AUTO convergence |
| `app/src/test/java/com/studyagent/client/ui/Gate21mDiagnosticsSafetyTest.kt` | Strengthened existing AMBIGUOUS ReviewCommit owner assertion (no test-count change) |
| `app/src/test/java/com/studyagent/client/anki/Gate21mArchitectureAuditTest.kt` | Updated the diagnostics call-shape assertion and added the observation requirement (no test-count change) |
| `app/src/test/java/com/studyagent/client/ui/Gate21mBackendSelectionUiTest.kt` | Extended the launcher-action assertion to `NotInstalled` (no test-count change) |

### Documentation

`docs/GATE_21M_ENTRY_CONTRACT_R2.md`, `GATE_21M_CAPABILITY_AWARE_UI.md`,
`GATE_21M_BACKEND_SELECTION_CONTRACT.md`, `GATE_21M_AVAILABILITY_PRESENTATION.md`,
`GATE_21M_DIAGNOSTICS_SAFETY.md`, `GATE_21M_R20_2_COMPLIANCE.md`,
`GATE_21M_ARCHITECTURE_AUDIT.md`, `GATE_21M_FILE_REPORT.md`,
`GATE_21M_VERIFICATION_REPORT.md`, `GATE_21M_ANDROID_VALIDATION_STATUS.md`,
`GATE_21M_RESIDUAL_RISKS.md`, `GATE_21M_FINAL_STATUS.md`, `GATE_21N_HANDOFF.md`.

Prior gate documents were not modified.

---

## 7. Architecture audit summary

All 26 mandatory rows are PASS at source level. Full evidence is in
`GATE_21M_ARCHITECTURE_AUDIT.md`.

```text
PASS:     26
FAIL:      0
BLOCKED:   0 audit rows (two rows have separately BLOCKED runtime verification:
           AUDIT-21M-R2-20 accessibility and AUDIT-21M-R2-21 RTL/text)
```

No R-20-2 violation. No duplicate infrastructure. No mutation authority in the UI. No Custom Study
lifecycle. No unsupported PC claim.

---

## 8. Verification summary

Full evidence is in `GATE_21M_VERIFICATION_REPORT.md`.

```text
VER-21M-R2-01 … 49

PASS:             42
BLOCKED:           7  (VER-16, VER-39, VER-40, VER-41, VER-46, VER-47, VER-48)
NOT_APPLICABLE:    0
FAIL:              0
```

The blocked rows are all environment/device-evidence rows. No blocked row is reported as PASS.

---

## 9. Harness and capability evidence

```text
BASELINE (historical report):
227 classes / 2756 tests

REPORTED PREVIOUS:
229 classes / 2799 tests / 0 failed

CURRENT RUN (this HEAD):
237 classes / 2924 tests / 2924 passed / 0 failed / 40.4 s

GATE 21M SUBSET:
7 classes / 115 tests / 115 passed / 0 failed

CAPABILITY PINS CURRENT:
13 hold / 0 disagree / 0 not verifiable

PINNED SOURCE REVISIONS:
ankitects/anki          25.09.2   3890e12
ankidroid/Anki-Android  v2.24.1   9f579c10bb151146728220729c510acbbd8faba7
FooSoft/anki-connect    master    4064fa142785975255457abd6a496015f5b71f38 (UNPINNED by design)
```

The 229/2799 figure is historical and cannot be re-executed as a separate revision in this parentless
checkout. It is not presented as this run's result.

---

## 10. Android verification

Independent results, not collapsed with harness results:

| Task | Result |
|---|---|
| `./gradlew testDebugUnitTest` | **BLOCKED** — Gradle 8.7 distribution unavailable |
| `./gradlew lint` | **BLOCKED** — same |
| `./gradlew assembleDebug` | **BLOCKED** — same; no Android SDK |
| `./gradlew assembleRelease` | **BLOCKED** — same |
| `./gradlew connectedDebugAndroidTest` | **BLOCKED** — same; no device/emulator |

Full commands and failure evidence: `GATE_21M_ANDROID_VALIDATION_STATUS.md`.

```text
Harness PASS      ≠ Gradle PASS
Kotlin compile    ≠ APK build
Fake PC test      ≠ Real PC Anki
```

---

## 11. Residual risks

The mandatory blockers are:

- Android compilation/verification unverified (R-21M-01);
- no GATE 21M instrumentation/device evidence (R-21M-02);
- historical 229/2799 revision not reproducible in this squash (R-21M-03);
- prior final-lock document is internally inconsistent (R-21M-04);
- GATE 21L remains blocked by GATE 20 (R-21M-05);
- production PC Anki adapter remains absent (R-21M-06).

Additional scope limitations: no read-context projection, 21M diagnostics not in the export path, no
authoritative collection identity on AnkiDroid, hard-coded English copy, and unverified runtime
accessibility/RTL/theme. Full register: `GATE_21M_RESIDUAL_RISKS.md`.

---

## 12. Next-gate handoff

GATE 21N must not assume:

```text
GATE 21L LOCKED
GATE 20 IMPLEMENTED
PC ANKI IMPLEMENTED
PC CUSTOM STUDY SUPPORTED
ANDROID BUILD VERIFIED
GATE 21M LOCKED
```

The verified 21M contracts and outstanding prerequisites are listed in `GATE_21N_HANDOFF.md`.

---

## 13. Decision record

```text
CASE A (full lock):        NOT APPLICABLE — Android validation missing
CASE B (harness pass, Android missing): APPLIED
CASE C (PC capability violation):       NOT TRIGGERED
CASE D (21M scope passes, 21L blocked): PARTIAL — 21M scope passes at source/harness level,
                                        but mandatory Android evidence is still absent

GATE 21M EXECUTION AUTHORIZATION:       YES
GATE 21M LOCK ELIGIBILITY:              NOT YET — blocked by Android evidence
GATE 21 FINAL ARCHITECTURE LOCK:        NO
```

No claim in this document is a substitute for a future executed Android/device run.
