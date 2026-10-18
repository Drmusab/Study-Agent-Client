# GATE 21M — Backend Selection Contract

**Date:** 2026-10-18 (UTC)
**Source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Scope:** Settings preference selection, exact configured-instance identity, persistence, unresolved
preferences, active-session ownership, and the safe action set.
**Production changes in this run:** two wiring corrections — `DiagnosticsScreen.kt` (observe the
asynchronous read) and `AnkiBackendSectionUi.kt` (withhold `open_ankidroid` when AnkiDroid is known to
be absent). The contract below describes the implementation found at
HEAD and independently audited by this gate.

---

## 1. Sources of truth (must remain separate)

| Source | Owns | GATE 21M reads it as | It must never become |
|---|---|---|---|
| `BackendPreferenceRepository.preference` | The user's preference for **new unbound** work | `StateFlow<BackendPreference>` | A description of active work |
| `AnkiBackendRegistry` / `AnkiBackendStatusCenter.states` | Real configured instances and their observed state | `List<AnkiBackendState>` | A UI-owned backend list |
| `AnkiBackendState.capability(...)` | Audited implementation + current readiness | `AnkiCapabilityStatus` per requirement | A capability invented by the UI |
| `AnkiBackendResolver2` | Pre-binding selection policy | Injected **pure preview** only | A second resolver in the UI |
| `AnkiStudyBackendLockTracker.locks` | Immutable owner of each active StudySession | `Map<String, AnkiSessionContext>` | A value derived from the preference |
| `ReviewCommitLedger` / `ReviewerActionLedger` / note ledgers | Durable transaction owner and status | One read-only `unresolved()` projection | A UI-controlled status machine |
| `AnkiIdentityAssurancePolicy` | Collection identity assurance | Verbatim category token | A continuity claim inferred from success |

The implementation imports these sources directly; it creates no second registry, resolver, session
machine, ledger, scheduler or recovery system.

---

## 2. Settings hierarchy actually rendered

`SettingsScreen.kt:525` inserts the section into the real Settings list under **Anki integration**.
The card (`SettingsScreen.kt:814`) renders, in order:

```text
Anki backend
├── Preferred backend summary          (authoritative preference)
├── Save feedback                      (Saving / Saved / Failed / Unconfirmed)
├── Empty-inventory notice             (only when the registry is empty)
├── Automatic (AUTO) row               (selectable policy)
├── One row per configured instance    (exact BackendId key; unavailable still selectable)
├── Unresolved-preference warning      (stored identity preserved; non-selectable)
├── Informational kind rows            (disabled; no adapter in this build)
├── Scope notice                       (what a preference change does not touch)
├── Active study session owner         (the lock, never the preference)
├── Switch-effect notice               (confirmed save + active session only)
├── Observation notice                 (no freshness claim)
├── Refresh state                      (read-only re-check)
└── Closed action set                  (Refresh, Open AnkiDroid, Backend configuration, Diagnostics)
```

No unrelated Settings redesign was introduced. The card uses the existing `SettingsCard`,
`SectionHeader`, `ChoiceRow`, `InfoBanner`, `EmptyState`, `KeyValueRow`, `ExpandableSection`,
`SecondaryButton`, spacing and theme tokens.

---

## 3. AUTO

**Label:** `Automatic (AUTO)`
**Stable row key:** `preference:auto`
**Description:** “Selects one eligible implemented Anki backend when a new operation begins. It never
merges two collections and never moves an active session.”

Implementation facts:

- AUTO is a **policy**, not a backend identity.
- The preview is supplied by the composition root (`AppContainer.kt:624`) and is the existing pure
  `AnkiBackendResolver2.resolve` with a hypothetical `BackendPreference.Auto` request. The UI
  ViewModel holds no selection policy and builds no resolution request
  (`AUDIT-21M-R2-05`).
- The preview is resolved against **exactly the states being rendered**, so it cannot disagree with
  the rows beside it.
- The resolver's existing policy is unchanged: reject Fake; require `Ready`; require all requested
  capabilities in both projections; prefer `LOCAL_DEVICE`; refuse ≥2 equally eligible remotes as
  `Ambiguous`; tie-break by `orderingKey`.
- A preview is a **pre-binding statement only**. It never binds a session, writes a preference or
  probes a backend.

### Empty / ineligible inventory

| Condition | Rendered statement |
|---|---|
| No configured backend | “No Anki backend is configured” |
| Configured but none ready | “No backend is eligible right now (no configured backend is ready)” |
| Ready but missing capability | “No backend offers what a review session needs — missing: …” |
| Ambiguous remotes | “Choose one instance: …” |
| Missing pinned backend | “The saved backend is not configured” |
| Invalid stored preference | “The saved backend preference could not be read” |

No fallback backend is fabricated. AUTO never invents a PC candidate.

---

## 4. Exact configured-instance selection

- One row per **registered** instance. No row is created from a kind list.
- Row key = `AnkiBackendId.stableId` (e.g. `ankidroid_local`, `pc_agent:home`). The display label is
  never identity.
- `backendId.stableId` is also shown verbatim in the row description. It is not translated.
- Duplicate keys are detectable (`AnkiBackendSectionUiState.hasDuplicateKeys`) and the stress test
  asserts zero.
- An unavailable configured instance remains **selectable**. Selecting it must produce the typed
  refusal from the existing resolver/session admission path, never a silent substitute.
- A registered test double is rendered for honesty but is **not selectable**
  (`testDouble = kind == FAKE`).
- A fake identity is not persistable (`BackendPreferenceRepository.toEncodedBackendPreference()`
  returns `null`), so production can never pin one.

### Production inventory at this HEAD

| Backend | Adapter implemented | Registered | Audited capabilities | UI selectable |
|---|---|---|---|---|
| AnkiDroid local | yes (`AnkiDroidBackend`) | yes (`AppContainer.kt:601`) | static GATE 21D claim; GATE 20 custom-study claim is `NONE` | yes, even while unavailable |
| PC Anki | **no production adapter** | no | none | **no** — informational disabled row only |
| Fake | test-only | no | test fixtures only | no |

### R-20-2 PC row

A user-selectable kind with no registered instance is rendered as
`AnkiBackendUnimplementedKindRowUi`:

```text
title:  PC Anki
status: Not implemented in this build
token:  BACKEND_KIND_NOT_IMPLEMENTED
selectable: false
```

The explanation explicitly separates a PC AI/STT/TTS agent from a PC Anki backend. The exclusion is
derived from `configuredKinds`, not hard-coded per kind: registering a real, audited PC adapter later
removes the informational row and lets the existing instance row path render it. No permanent PC
exclusion is embedded in a generic domain model.

---

## 5. Preference persistence

`AnkiBackendSelectionController.select(target)` is the only write path:

1. publish `Saving(targetToken)` immediately (double tap cannot show two saved states);
2. call the injected writer, which is the repository's own `setPreference`;
3. a thrown exception is a **refusal**; only the exception class name may be logged;
4. a newer write increments `writeGeneration`; a late result of an older write is dropped;
5. on acceptance, the authoritative preference flow must actually reach the target within the
   confirmation window (default `2_000 ms`);
6. only then publish `Saved(targetToken)`. Otherwise publish `Unconfirmed(targetToken)`.

The rendered selection always comes from `BackendPreferenceRepository.preference`, never from the
call's intent. Therefore:

```text
refused write      → Failed, selection does not move
accepted/unconfirmed → Unconfirmed, not Saved
superseded write   → late result dropped
authoritative echo → Saved
```

### Required sequences

| Sequence | Evidence |
|---|---|
| AUTO → AnkiDroid → AUTO | `Gate21mBackendSelectionControllerTest.AUTO to AnkiDroid to AUTO converges on the authoritative store` (added by this run) |
| AUTO → Backend A → Backend B (fake-only generic path) | `a superseded write never publishes its late result`; `the section projects the registry inventory…`; stress controller phase |
| Failed persistence | `a refused write is reported as failed and the selection does not move`; `a write that throws is a refusal…` |
| Unconfirmed persistence | `an accepted write the store never echoes is unconfirmed, never saved` |

---

## 6. Missing / unresolved persisted backend

| Stored preference | Registered inventory | UI behavior |
|---|---|---|
| `Explicit(id)` | `id` missing | Warning row `PREFERRED_BACKEND_NOT_CONFIGURED`; exact id shown; selection key points to the warning; **not** rewritten to AUTO |
| `ExplicitKind(kind)` | no instance of kind | Warning row `PREFERRED_BACKEND_KIND_NOT_CONFIGURED`; no fallback |
| `ExplicitKind(kind)` | ≥2 instances of kind | Warning row `PREFERRED_BACKEND_KIND_AMBIGUOUS`; no row claims selection; user must choose an instance |
| `InvalidStored(issue)` | any | Warning row `PREFERENCE_INVALID_<ISSUE>`; raw stored text is not retained or shown |
| `Explicit(Fake)` | fake registered | Row rendered as test-only, not selectable; preference is shown as stored, never converted to AUTO |

Historical transaction ownership is untouched. No unresolved record is reassigned by the UI.

---

## 7. Active StudySession ownership

- The active owner comes from `AnkiStudyBackendLockTracker.locks`, the GATE 21G immutable projection.
- **Preferred backend** and **Active study session** are rendered as two separate facts.
- `ActiveStudySessionUi` is one of:
  - `None` — “No active study session”;
  - `One` — “Active study session: <backend title>” plus backend id and session id in the model;
  - `Several` — “N study sessions hold a backend lock”; no single owner is named.
- The section never derives the active owner from the preference.
- Changing the preference does not call `locked()` or `released()`; the controller has no such
  reference (`AUDIT-21M-R2-10`).

### Switch-effect notice

Shown only when **both** conditions hold:

```text
saveFeedback is Saved(target)  AND  activeSession is One
```

Copy: “Backend preference updated. Your active study session remains connected to its original Anki
backend.” It is an informational banner, not a blocking dialog. The session is not migrated or
terminated.

---

## 8. Safe action set

`AnkiBackendUiAction` is a closed enum. It contains exactly:

| Token | Backing |
|---|---|
| `refresh_status` | Existing `AnkiBackendRegistry.refresh(id)` read-only probe |
| `view_capability_details` | Local expand/collapse; no backend call |
| `open_backend_configuration` | Existing Connection destination |
| `open_diagnostics` | Existing Diagnostics destination |
| `open_ankidroid` | Existing `AnkiDroidLauncher` |

Deliberately absent:

- no permission-grant action (the AnkiDroid integration permission is install-time declared; no
  interactive grant exists);
- no retry-all, force-commit, clear, delete or reassign action;
- no action that creates a PC adapter or PC configuration.

Actions are rendered only when their backing exists. With an empty registry, Refresh is not offered.
No inert control is added.

---

## 9. Explicit non-goals

This gate does **not**:

- implement an alternative scheduler or any Custom Study lifecycle;
- add a second registry/resolver/session machine/ledger;
- perform cross-backend synchronization or offline replay;
- migrate an active session when the preference changes;
- declare GATE 21 final closure.

GATE 21L remains blocked; GATE 20 remains design-only.
