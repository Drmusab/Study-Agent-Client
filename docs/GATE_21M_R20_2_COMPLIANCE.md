# GATE 21M — R-20-2 Compliance

**Date:** 2026-10-18 (UTC)
**Original audit source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Boundary:** R-20-2 — the production PC Anki backend has not been implemented; the capability model
must refuse unsupported operations until a real, audited PC backend supplies a capability claim.

---

## 1. Boundary statement

```text
PRODUCTION PC ANKI BACKEND:         NOT IMPLEMENTED
PC ANKI CUSTOM STUDY:               UNSUPPORTED
PC ANKI OPERATIONAL CLAIM:          PROHIBITED
FAKE PC BACKEND TESTS:              ARCHITECTURAL / UI FIXTURES ONLY
CAPABILITY MODEL:                   IMPLEMENTED
UNSUPPORTED OPERATION BEHAVIOR:     FAIL CLOSED
```

GATE 21M is a presentation gate. It may describe what exists; it may not create a PC backend, a PC
configuration, a filtered-session lifecycle, or a capability claim.

---

## 2. Source evidence

### 2.1 No production PC adapter

```text
$ grep -rnE "PcAnkiBackend|class .*PcAgent.*Backend|AnkiBackendId\.PcAgent\(" app/src/main/java
app/src/main/java/com/studyagent/client/core/anki/AnkiBackendId.kt:19:    data class PcAgent(val profileId: String) : AnkiBackendId
```

This is an **identity type**, not an adapter. There is no production class implementing
`AnkiBackend` for `PC_AGENT`.

### 2.2 Registry contains exactly one backend

```kotlin
// app/src/main/java/com/studyagent/client/di/AppContainer.kt:601
AnkiBackendRegistry(listOf(ankiDroidBackend))
```

No PC instance is registered, no PC profile is configured, no PC address/credential is stored by this
gate.

### 2.3 The PC row is informational and disabled

`AnkiBackendSectionUi.kt` derives informational rows from the **registry**, not from a hard-coded
per-kind exclusion:

```kotlin
val configuredKinds = states.map { it.kind }.toSet()
val unimplementedKinds = USER_SELECTABLE_BACKEND_KINDS
    .filterNot { it in configuredKinds }
    .map { kind -> AnkiBackendUnimplementedKindRowUi(...) }
```

`AnkiBackendUnimplementedKindRowUi` has:

```text
selectable = false
selected   = false
status     = "Not implemented in this build"
token      = BACKEND_KIND_NOT_IMPLEMENTED
```

Its PC explanation states, verbatim:

> “PC Anki support is not implemented in this build: no production PC Anki adapter is wired, so there
> is no PC backend to select. A connected PC agent for AI, speech-to-text or text-to-speech is not an
> Anki backend and never makes this row ready.”

If a real PC adapter is later registered, `configuredKinds` contains `PC_AGENT`, the informational row
disappears, and the generic configured-instance row renders the real identity. No generic domain model
hard-codes permanent PC exclusion.

### 2.4 PC agent ≠ PC Anki

The UI does not read the PC agent connection as an Anki availability source:

- `AnkiBackendStatusCenter.states` contains only registered `AnkiBackend` instances;
- the AI/STT/TTS agent's connection state is a different service;
- `AnkiAvailability.AgentDisconnected` / `AgentAuthenticationRequired` / `AgentAnkiUnavailable` are
  domain states for a future PC Anki adapter, not evidence that one exists;
- the informational PC row never displays “Ready”, regardless of whether the PC agent is reachable.

There is no code path from a PC agent connection to the PC Anki row's status.

### 2.5 Custom Study claim is `NONE`

```kotlin
// data/anki/ankidroid/AnkiDroidBackend.kt:166
override val customStudyCapabilities: AnkiCustomStudyCapabilities =
    AnkiDroidCustomStudyCapabilities.AUDITED
// AnkiDroidCustomStudyCapabilities.AUDITED = AnkiCustomStudyCapabilities.NONE
```

The UI renders:

```text
Custom Study: not implemented
token: CUSTOM_STUDY_NOT_IMPLEMENTED
```

No PC Custom Study claim exists. No filtered-session lifecycle control exists. No active filtered
session is fabricated.

### 2.6 Fake backends are test-only

- `AnkiBackendId.Fake` is not persistable:
  `BackendPreferenceRepository.toEncodedBackendPreference()` returns `null` for it.
- A registered `FAKE` instance is rendered for honesty but is not selectable.
- GATE 21M UI tests use Fake/PC identities to exercise generic identity behavior only.
- Production `AppContainer` never registers a Fake or PC backend.
- `R20_2BoundaryTest` asserts the production registry contains exactly one configured backend and that
  a fixture PC instance would have to be explicitly wired to appear — which this gate does not do.

---

## 3. Fail-closed behavior

| Attempt | Result |
|---|---|
| Select a kind with no registered instance | The row is disabled; no selection event exists |
| Pin a PC instance with no adapter | No such configured instance exists; the UI has no PC row to select |
| Persist a Fake identity | Repository refuses; `InvalidStored`/failure path; never becomes AUTO |
| Start a session on unavailable backend | Existing admission path returns a typed refusal; no fallback |
| Custom Study operation | No UI control; existing `CustomStudyPolicy` refuses unsupported operations |
| Diagnostics for filtered session | `NOT_APPLICABLE`; no fabricated family |

The UI does not catch a refusal and turn it into success. It renders the authoritative preference and
the typed state it receives.

---

## 4. Required test fixtures and results

`Gate21mR20_2BoundaryTest` (12 tests, all PASS in the executed harness):

| Fixture / assertion | Result |
|---|---|
| Production inventory yields no operational PC entry and one informational PC row | PASS |
| Composition root registers exactly one configured backend | PASS |
| Exclusion follows the registry, so a wired adapter would activate the row | PASS |
| The only production backend claims no custom study | PASS |
| Section discloses the production claim and never a filtered-session operation | PASS |
| A fixture that claims filtered sessions still gets no lifecycle in this build | PASS |
| A registered test double is rendered as one and cannot be selected | PASS |
| Selectability follows registration — a fixture PC instance must never be wired | PASS |
| A fake identity cannot be persisted, so production can never pin one | PASS |
| A pinned fake is shown as stored and unselectable, never converted to AUTO | PASS |
| The settings surface has no PC-specific branch and no hard-coded exclusion | PASS |
| An unavailable production backend stays selectable so choosing it yields a typed refusal | PASS |

Additional evidence:

- `Gate21mUiCapabilityStressTest`: 600 randomized inventories including `PcAgent("home")`,
  `PcAgent("work")` and `Fake("chaos")`; counters for invented instances, fake selectability and
  capability over-promise all zero.
- Matrix row `AUDIT-21M-R2-04` (registry authority), via `Gate21mArchitectureAuditTest`
  “the unimplemented-kind row is derived from the registry, not hard-coded”.
- Matrix rows `AUDIT-21M-R2-12` and `AUDIT-21M-R2-22`, via `Gate21mArchitectureAuditTest`
  “GATE 20, 21L and 21N behaviour is not implemented by this gate”: no `PcAnkiBackend`,
  `FilteredDeckReview`, `StudySessionMachine` constructor or new backend is added by the gate.

---

## 5. Future PC activation (not authorized here)

When a real PC backend is implemented, activation requires all of:

1. a registered production `AnkiBackend` adapter for `PC_AGENT`;
2. a stable, persistable configured `AnkiBackendId.PcAgent(profileId)`;
3. a verified backend protocol and transport;
4. audited capability declarations (`implementedCapabilities`);
5. availability/readiness implementation including `AgentDisconnected`,
   `AgentAuthenticationRequired`, `AgentAnkiUnavailable`, `Ready`;
6. backend-specific integration tests (not Fake fixtures);
7. its own gate authorization and regression verification.

Only then may `AnkiBackendStatusCenter.states` contain a PC instance and the Settings UI render it as a
selectable operational row. GATE 21M does not perform any of these steps.

---

## 6. Compliance verdict

```text
AUDIT-21M-R2-02: PASS
AUDIT-21M-R2-04: PASS
AUDIT-21M-R2-12: PASS
VER-21M-R2-03 … 08: PASS (see verification report)
CASE C (unsupported PC capability operational): NOT TRIGGERED
```

No unsupported PC Anki operation is displayed as operational. No PC Custom Study lifecycle is
displayed. No Fake production capability claim exists. The capability model fails closed.

---

## 7. Current checkout revalidation and Gate 20 scope reconciliation

Revalidated 2026-10-18 UTC at `e22a32798259c3abd88001963afe511fd950cb2e` on
`arena/95bebe16-study-agent-client` (the source HEAD recorded in the original audit is absent from
this parentless Git snapshot).

Current source inspection confirms:

- `AppContainer` registers only the production AnkiDroid backend; there is no production PC Anki
  adapter or configured PC profile.
- PC AI/agent availability remains independent of Anki availability; it cannot make the informational
  PC Anki row operational.
- `AnkiDroidCustomStudyCapabilities.AUDITED` is `AnkiCustomStudyCapabilities.NONE`.
- No Custom Study / filtered-session create, rebuild, empty, or lifecycle operation was added by this
  gate; no scheduler, session machine, PC integration, or backend was added.
- `tools/check-capability-pins.sh /tmp/arena21m-r2-current` was rerun: **13 checks held, 0 disagreed,
  0 unverifiable**, exit 0. This is source evidence only, not behavioral evidence.

**Gate 20 status is scope-split in the repository artifacts, not wholly absent.**
`GATE_20_FINAL_REPORT.md` and `GATE_20B_CONTRACT_LOCK.md` describe a narrow contract-enforcing subset
(detection/refusal/disclosure/identity/capability truth) as locked, while
`GATE_20_CUSTOM_STUDY_CONTRACT.md` and `GATE_21L_REPORT.md` still describe the broader lifecycle
prerequisite as blocked/not available. The present revalidation preserves both facts without editing
those prior-gate documents. Under the user's explicit direction, GATE 21L remains **BLOCKED** pending
its prerequisite; this gate does not decide or implement the missing lifecycle behavior.

R-20-2 remains **PASS at source level**: PC Anki is not selectable or represented as operational;
PC AI availability does not imply PC Anki readiness; capability/refusal claims are fail-closed. This
is not Android runtime verification and does not constitute a GATE 21M or GATE 21 final lock.
