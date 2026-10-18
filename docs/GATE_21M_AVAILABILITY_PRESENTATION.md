# GATE 21M — Availability and Readiness Presentation

**Date:** 2026-10-18 (UTC)
**Source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Primary sources:**
`core/anki/AnkiAvailability.kt`,
`core/anki/AnkiBackendState.kt`,
`core/anki/AnkiBackendStatus.kt`,
`ui/components/anki/AnkiBackendUiModels.kt`,
`ui/components/anki/AnkiBackendSectionUi.kt`,
`ui/screens/settings/AnkiBackendSelectionController.kt`
**Production changes in this run:** two wiring corrections: `DiagnosticsScreen.kt` (observe the
asynchronous read) and `AnkiBackendSectionUi.kt` (withhold a dead launcher action). Presentation logic otherwise unchanged.

---

## 1. What is displayed

For each configured instance, the Settings row displays:

| Field | Source | Notes |
|---|---|---|
| Stable identity | `AnkiBackendId.stableId` | Never translated, never replaced by a label |
| Display title | `AnkiBackendId.displayLabel()` | Presentation only, never identity |
| Status token | `AnkiAvailability.statusCode` | Stable diagnostics vocabulary |
| Status text | `AnkiAvailability.statusText()` | User-facing wording derived from the state |
| Ready flag | `descriptor.isReachable` | True only for `AnkiAvailability.Ready` |
| Unavailable reason | `AnkiBackendState.unavailableReason.token` | Content-free |
| Retryability | `unavailableReason.retryability.name` | Content-free |
| Capability evidence | `STATIC_CLAIM` / `RUNTIME_NEGOTIATED` / `NONE` | Where the capability knowledge comes from |
| Capabilities | `AnkiBackendState.capability(...)` | Implementation + readiness per requirement |

The UI does not compute availability from Android connectivity, package presence, transport
state, the PC agent connection, or a previous success.

---

## 2. Availability vocabulary

The UI consumes the 12-state domain type unchanged:

| Domain state | Status token | UI text | Can be “Ready”? |
|---|---|---|---|
| `Checking` | `CHECKING` | “Checking…” | no |
| `NotInstalled` | `NOT_INSTALLED` | “AnkiDroid is not installed on this device.” | no |
| `ProviderUnavailable` | `PROVIDER_UNAVAILABLE` | “AnkiDroid's integration provider is not reachable.” | no |
| `PermissionRequired` | `PERMISSION_REQUIRED` | “Study-Agent has not been granted access to AnkiDroid.” | no |
| `CollectionNotInitialized` | `COLLECTION_NOT_INITIALIZED` | “No Anki collection is open yet.” | no |
| `Ready` | `READY` | “Ready” | yes |
| `TemporarilyUnavailable` | `TEMPORARILY_UNAVAILABLE` | “Temporarily busy; it should recover on its own.” | no |
| `AgentDisconnected` | `AGENT_DISCONNECTED` | “The PC agent is not reachable.” | no |
| `AgentAuthenticationRequired` | `AGENT_AUTHENTICATION_REQUIRED` | “The PC agent is up, but it has not accepted this app yet.” | no |
| `AgentAnkiUnavailable` | `AGENT_ANKI_UNAVAILABLE` | “The PC agent is up, but Anki on that PC is not ready.” | no |
| `Unsupported` | `UNSUPPORTED` | “This Anki version is not supported.” | no |
| `Fault` | `FAULT` | “The availability check failed.” | no |

The mapping is total; `Gate21mCapabilityAvailabilityUiTest.every availability state keeps its own
status token and a non-blank text label` iterates all 12 states.

### Explicit negative rules

| Must not happen | Enforced by |
|---|---|
| Android internet available → Anki ready | No connectivity source is read by the UI; readiness comes from `AnkiAvailability` |
| PC agent reachable → PC Anki ready | Registry has no PC adapter; PC row is informational; PC agent state is not fused into Anki availability |
| AnkiDroid installed → Anki ready | `NotInstalled` is distinct; installation alone is not `Ready` |
| Transport connected → Anki ready | `AgentDisconnected` / `AgentAnkiUnavailable` remain distinct |
| `Checking` or `UNKNOWN` → READY | `readyNow` is true only for `Ready`; capability readiness is `UNKNOWN` while checking |
| A timeout → mutation failed | The UI never presents a timeout as a mutation outcome; transaction status is displayed from each family's own vocabulary |

---

## 3. Capability readiness is displayed separately

Availability answers “is this backend usable at all right now?”. Capability readiness answers “can
this specific operation be used right now?”. They are rendered on separate lines:

```text
Availability: PERMISSION_REQUIRED
Review loop:  Supported · Not available now
```

A backend may be `Ready` and still report `UNSUPPORTED` for an operation (for example, card browser on
a backend with no wired browser adapter). A backend may be `NOT_READY` while its static implementation
claim remains `SUPPORTED` for operations it does implement. The UI never collapses these into one
“available” boolean.

`AnkiCapabilityStatus` enforces the honesty invariant at construction: `READY` is impossible unless
`implemented == SUPPORTED`.

---

## 4. Freshness and staleness

### Contract fact

`AnkiBackendState` deliberately has **no timestamp**. Its KDoc states that a timestamp would read as
“verified at”, which the observation cannot truthfully claim. GATE 21M therefore:

- displays no age, TTL, “last checked”, “still connected” or “up to date” wording;
- shows a permanent observation notice:

```text
Status is the last observation each backend published; it is not a live connection and carries no
timestamp. Use Refresh to re-check.
```

- treats a new observation as replacing the old one via `StateFlow` latest-value semantics;
- drops runtime-negotiated capability evidence when the backend is no longer `Ready` (the domain
  type does this before the UI sees it). A reconnect cannot resurrect yesterday's negotiation.

The stress test scans every rendered row for freshness words (“ago”, “last checked”, “still
connected”, “up to date”) and asserts zero occurrences.

### Stale asynchronous results

The controller does not subscribe to backend callbacks or timers. It renders the status center's
latest `states`. For the two asynchronous actions it owns:

| Path | Guard | Behavior |
|---|---|---|
| Preference write | `writeGeneration` (`AtomicInteger`) | A late result from an older write is dropped; a newer write wins |
| Availability refresh | `refreshGeneration` (`AtomicInteger`) | A late completion is dropped; a removed backend is reported, not resurrected |

Cancellation is rethrown, not converted into success. The implementation has five guarded publish
decisions, asserted by the matrix row `AUDIT-21M-R2-16` (stale callback safety) and by
`Gate21mArchitectureAuditTest` “stale asynchronous results are rejected by a monotonic generation
guard”.

### Removed backend during refresh

Scenario:

```text
Backend A refresh starts
Backend A configuration is removed
Old refresh completes
```

Result:

- `AnkiBackendRegistry.refresh(A)` returns `NotRegistered` for the removed instance;
- the controller publishes `Completed(REFRESH_BACKEND_NOT_REGISTERED)`;
- the section no longer contains a row for A;
- surviving backends keep their own states; no sibling state is substituted.

Evidence: `a backend removed while its probe ran is reported as absent, never as a sibling's state`.

---

## 5. Health refresh

### Mechanism

`AnkiBackendSelectionController.refreshStatus()` calls the existing
`AnkiBackendRegistry.refresh(id)` for each registered instance, in `stableId` order. That is GATE
21D's sanctioned read-only entry point; it delegates to `AnkiBackend.refreshAvailability()`.

### Properties

| Property | Implementation |
|---|---|
| Read-only | No mutation method is reachable from the controller; the only registry members touched are `registered` and `refresh` |
| User-initiated | Only the Refresh button or an explicit ViewModel call; no `LaunchedEffect`, no polling |
| No duplicate probes on recomposition | Refresh is not called from composition; test `nothing is probed or written while the section is merely projected` |
| Cancellation-safe | `CancellationException` is rethrown |
| Stale-response rejection | Monotonic generation guard |
| Loading indicator | `AnkiBackendRefreshUi.Running` disables the Refresh button and shows “Checking backends…” |
| Failure honesty | `FAILED`; never reported as success |
| Missing mechanism | `REFRESH_MECHANISM_NOT_AVAILABLE` when no registry is wired |
| Removal honesty | `REFRESH_BACKEND_NOT_REGISTERED` when an instance disappears mid-probe |

The test fixture `ProbeOnlyBackend` records every call that reaches a backend. The refresh test asserts
exactly `listOf("refreshAvailability")` per instance, proving no read/write operation was attempted.

---

## 6. Domain evidence for “Ready”

`AnkiBackendState.capability(...)` is fail-closed:

- If a static claim exists, it answers for implementation, whatever the backend is doing now.
- If no static claim exists, capability is known only while `Ready`; when the backend leaves `Ready`,
  negotiated evidence drops to `UNKNOWN`.
- Operational `READY` requires **all** of:
  1. `availability is Ready`;
  2. the live capability projection supports the requirement;
  3. the probe-time capability projection supports the requirement.
- A claim can only narrow readiness; it cannot make an unimplemented operation ready.

This is the contract the UI renders. The UI does not add a second readiness rule.

---

## 7. Residual limitations

- `AnkiBackendStatusCenter.states` is only as fresh as the last publication/refresh. The UI states
  this and does not invent a timestamp.
- The GATE 21D registry availability for AnkiDroid can remain `Checking` until Library/Study or the
  user's Refresh drives a probe. This is a pre-existing contract property, recorded as R-21D-03 and
  inherited by 21M; the Settings section offers the sanctioned Refresh action.
- `PermissionRequired` has no interactive grant action because the AnkiDroid integration permission is
  declared at install time. The UI says what is missing; it does not pretend to grant it.
- Real device verification of the visual state remains BLOCKED; see
  `GATE_21M_ANDROID_VALIDATION_STATUS.md`.
