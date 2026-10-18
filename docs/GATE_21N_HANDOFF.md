# GATE 21N — Handoff from GATE 21M (Revision 2)

**Date:** 2026-10-18 (UTC)
**From:** GATE 21M — Backend Selection UI & Diagnostics (Revision 2)
**To:** the next authorized sub-gate under the current GATE 21 plan
**Status of this handoff:** informational. It does **not** authorize GATE 21N and does **not** lock
GATE 21M or GATE 21.

> Sections 1–6 preserve the original handoff record. The current Arena-checkout revalidation in §7
> supersedes statements about present instrumentation and the GATE 20 scope split only; it does not
> alter prior Gate status documents.

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
GATE 21L LOCKED                    — false; remains BLOCKED pending its prerequisite
GATE 20 LIFECYCLE AVAILABLE        — false; the broader Custom Study/lifecycle prerequisite remains blocked
GATE 20 NARROW ENFORCEMENT SUBSET  — reported locked by the final/contract-lock artifacts; do not treat as lifecycle completion
PC ANKI IMPLEMENTED                — false; no production adapter
PC CUSTOM STUDY SUPPORTED          — false; no lifecycle
ANDROID BUILD VERIFIED             — false; Gradle/lint/build/instrumentation remain unverified
GATE 21M LOCKED                    — false; implementation complete but lock BLOCKED
GATE 21 FINAL LOCKED               — false; prior banner is overstated and internally inconsistent
```

Also do not assume:

- that a GATE 21M instrumentation test is absent: a two-method Compose test file now exists, but it is uncompiled and unexecuted;
- that the 21M diagnostics rows are included in the export/copy path (they are not);
- that a read-context ownership projection exists (it does not);
- that collection continuity is proven on AnkiDroid (it is not; nominal/unverifiable);
- that the 229/2799 historical harness revision is the current revision (current is 237/2924).

---

## 3. Outstanding prerequisites before a parent GATE 21 lock

| # | Prerequisite | Current state | Required evidence |
|---|---|---|---|
| P1 | Broader GATE 20 Custom Study/lifecycle prerequisite | blocked / scope-split in prior artifacts | Resolve the owning GATE 20 contract and audited lifecycle scope; do not infer it from the limited refusal/capability subset |
| P2 | GATE 21L Custom Study backend ownership | blocked | Keep blocked until its prerequisite and separate authorization are satisfied |
| P3 | 21M Android compilation | blocked before Gradle configuration | Run `testDebugUnitTest`, `lint`, `assembleDebug` (and release where applicable) in a configured Android environment |
| P4 | 21M device/instrumentation | unverified | Compile and run the new test file via `connectedDebugAndroidTest` on an emulator/device |
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
2. Compile and run `Gate21mBackendSelectionInstrumentedTest.kt` for AUTO, disabled-PC and persisted
   selection behavior; separately add/run device scenarios for TalkBack, recreation and RTL/large text.
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

---

## 7. Current checkout revalidation — Arena session (2026-10-18 UTC)

```text
HEAD:   e22a32798259c3abd88001963afe511fd950cb2e
BRANCH: arena/95bebe16-study-agent-client
```

The starting worktree was clean. The current focused JVM harness was rerun: **7 classes / 115 tests /
0 failures**. The full harness was rerun: **237 classes / 2924 tests / 0 failures**. The capability-pin
script was rerun: **13 hold / 0 disagree / 0 not verifiable**. These are source/JVM results only.

One Compose instrumentation file with two tests was created at
`app/src/androidTest/java/com/studyagent/client/ui/Gate21mBackendSelectionInstrumentedTest.kt`.
It checks the production AUTO row, the disabled/informational PC row, and persisted selection between
AUTO and the exact configured AnkiDroid instance. The setup reads the atomic preference snapshot and
uses production row tags. The file was not compiled or run.

Gradle wrapper download failed for `--version`, `testDebugUnitTest`, `lint`, `assembleDebug`,
`assembleRelease`, and `connectedDebugAndroidTest` before project configuration (`SSL peer shut down
incorrectly` fetching Gradle 8.7). No Android SDK or device was found. Therefore Android compilation,
lint, APK builds, recreation, TalkBack, keyboard, RTL, text scaling and instrumentation remain
UNVERIFIED/BLOCKED; GATE 21M lock remains BLOCKED.

**Current GATE 20 reconciliation:** the final report and GATE 20B contract lock record a limited
refusal/disclosure/identity/capability-truth subset as locked. The Custom Study contract and GATE 21L
report still record the broader lifecycle prerequisite as blocked/not available. Current source confirms no PC Anki adapter, no filtered-session lifecycle, and
`AnkiDroidCustomStudyCapabilities.AUDITED = NONE`. In line with the explicit instruction, GATE 21L
remains BLOCKED; this revalidation does not change prior Gate documents, add lifecycle behavior, or
claim a GATE 21 final lock.

This current handoff is informational only and still does not authorize GATE 21N.
