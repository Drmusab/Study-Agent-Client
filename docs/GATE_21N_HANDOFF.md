# GATE 21N — Handoff from GATE 21M (Revision 2)

**Date:** 2026-10-18 (UTC)
**From:** GATE 21M — Backend Selection UI & Diagnostics (Revision 2)
**To:** the next authorized sub-gate under the current GATE 21 plan
**Status of this handoff:** informational. It does **not** authorize GATE 21N and does **not** lock
GATE 21M or GATE 21.

---

## 1. What GATE 21M leaves behind

### Verified at source + executed JVM level

| Contract | Where implemented | Verification |
|---|---|---|
| Settings **Anki backend** section reachable in the real Settings route | `AppNavHost.kt:541`; `SettingsScreen.kt:525/814` | source audit; no instrumented tap |
| AUTO policy row | `AnkiBackendSectionUi.kt` (`AnkiBackendAutoRowUi`) | 21 selection/capability tests |
| Exact configured-instance rows keyed by `stableId` | `AnkiBackendInstanceRowUi` | identity/duplicate-key tests |
| Unavailable instance remains selectable → typed refusal | `selectable = !testDouble` | source + UI tests |
| Unimplemented kind row (PC) disabled and derived from registry | `AnkiBackendUnimplementedKindRowUi` | R20_2 12 tests |
| Preference persistence with authoritative confirmation | `AnkiBackendSelectionController.select` | controller 20 tests |
| Failed / unconfirmed / superseded write honesty | `Failed`, `Unconfirmed`, `writeGeneration` | controller tests |
| Missing/unresolved persisted preference preserved | `AnkiBackendUnresolvedRowUi` | UI tests 13 |
| Capability implementation vs readiness | `AnkiBackendCapabilityRowUi` | capability/availability 14 tests |
| Custom Study disclosure, no lifecycle | `AnkiCustomStudyStatusUi` | custom-study tests + audit 12 |
| Active StudySession owner from lock | `ActiveStudySessionUi` + controller locks | lock tests |
| Preference vs active owner separation | section model | UI tests 26 |
| Read-only diagnostics projection | `AnkiBackendDiagnosticsUi`; the screen observes the async read | diagnostics 19 tests; source audit |
| Four transaction families preserved with owner + status | `AnkiUnresolvedTransactionReader` | diagnostics tests |
| Collection assurance tokens verbatim | `assuranceToken` | diagnostics tests |
| Safe action set | `AnkiBackendUiAction` | closed-set tests |
| No duplicate registry/resolver/session machine/ledger | architecture audit | 26/26 PASS |

### Production inventory

```text
AnkiDroid:  implemented, registered, selectable
PC Anki:    no adapter, not registered, not selectable; informational disabled row only
Fake:       test-only, not persistable, not selectable
```

### Evidence

```text
GATE 21M suite:  7 classes / 115 tests / 0 failed
Full harness:    237 classes / 2924 tests / 0 failed
Capability pins: 13 hold / 0 disagree / 0 not verifiable
Gradle/lint/build/instrumentation: UNVERIFIED (environment)
Production corrections: DiagnosticsScreen.kt observes the asynchronous ledger read;
                       AnkiBackendSectionUi.kt withholds the launcher when AnkiDroid is absent
```

---

## 2. Explicit non-assumptions for the next gate

The next sub-gate must not assume any of the following without newer concrete evidence:

```text
GATE 21L LOCKED                    — false; BLOCKED by GATE 20
GATE 20 IMPLEMENTED                — false; DESIGN_ONLY / BLOCKED
PC ANKI IMPLEMENTED                — false; no production adapter
PC CUSTOM STUDY SUPPORTED          — false; no lifecycle
ANDROID BUILD VERIFIED             — false; Gradle/lint/build/instrumentation UNVERIFIED
GATE 21M LOCKED                    — false; implementation complete but lock BLOCKED
GATE 21 FINAL LOCKED               — false; prior banner is overstated and internally inconsistent
```

Also do not assume:

- that a GATE 21M instrumentation test exists (it does not);
- that the 21M diagnostics rows are included in the export/copy path (they are not);
- that a read-context ownership projection exists (it does not);
- that collection continuity is proven on AnkiDroid (it is not; nominal/unverifiable);
- that the 229/2799 historical harness revision is the current revision (current is 237/2924).

---

## 3. Outstanding prerequisites before a parent GATE 21 lock

| # | Prerequisite | Current state | Required evidence |
|---|---|---|---|
| P1 | GATE 20 Custom Study contract/implementation | blocked / design-only | GATE 20 unlock + audited implementation |
| P2 | GATE 21L Custom Study backend ownership | blocked | GATE 20 first; then 21L authorization and implementation |
| P3 | 21M Android compilation | unverified | `./gradlew testDebugUnitTest lint assembleDebug` (and release where applicable) |
| P4 | 21M device/instrumentation | unverified | `connectedDebugAndroidTest` on an emulator/device |
| P5 | Accessibility/RTL/large-text execution | unverified | device or Compose test evidence |
| P6 | Production PC Anki adapter | absent | separate PC backend gate and audited capability claim |
| P7 | Read-context ownership projection | absent | an explicit read-only projection from GATE 21F |
| P8 | 21M diagnostics export decision | intentionally unchanged | privacy review + test if export is widened |
| P9 | Historical evidence reconciliation | partially blocked by squashed checkout | re-run at the reported revision or accept as historical only |
| P10 | Prior final-lock document consistency | inconsistent | resolve in the final closure gate; do not silently rewrite |

---

## 4. Handoff contract for GATE 21N

The next gate may rely on these **implemented** contracts:

1. `AnkiBackendSectionUiState` is the single presentation model for the Settings section.
2. `AnkiBackendSelectionController` is the only GATE 21M write path, and it writes only the
   preference.
3. `AnkiBackendDiagnosticsInput` is the read-only diagnostics input; no ledger or backend handle is
   exposed.
4. `AnkiUnresolvedTransactionReader` is the only transaction authority available to the UI, and it
   has one read method.
5. `AnkiBackendUiAction` is a closed action set with no mutation/recovery control.
6. PC Anki is represented only by an informational, disabled row derived from the registry.
7. GATE 21L/GATE 20 are not implemented; any Custom Study UI is a disclosure, not a lifecycle.

The next gate must independently re-run the applicable checks. It may cite this handoff for context,
but must not cite it as executed evidence for a later revision.

---

## 5. Recommended next actions (not authorization)

In order:

1. Run the Android Gradle/lint/build tasks in a configured environment and update
   `GATE_21M_ANDROID_VALIDATION_STATUS.md` with real results.
2. Add and run a GATE 21M instrumentation test for selection, disabled PC row, TalkBack semantics,
   recreation and RTL/large text.
3. Keep GATE 21L blocked until GATE 20 is implemented and audited.
4. Do not implement a PC adapter or PC Custom Study under 21N.
5. Resolve the final-lock document inconsistency only in the final GATE 21 closure process.

---

## 6. Final statement

```text
GATE 21M IMPLEMENTATION:   COMPLETE AT HEAD
GATE 21M HARNESS:          PASS
GATE 21M PINS:             PASS
GATE 21M ARCHITECTURE:     PASS
GATE 21M ANDROID EVIDENCE: UNVERIFIED
GATE 21M LOCK:             BLOCKED
GATE 21L:                  BLOCKED
GATE 21 FINAL:             NOT LOCKED
NEXT GATE:                 subject to the current GATE 21 plan and prerequisite resolution
```

This handoff does not authorize GATE 21N. It records what exists and what remains.
