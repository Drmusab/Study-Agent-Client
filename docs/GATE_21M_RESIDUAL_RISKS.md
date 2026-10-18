# GATE 21M — Residual Risks

**Date:** 2026-10-18 (UTC)
**Original audit source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Rule:** only risks supported by actual source inspection or executed evidence are listed. No generic
risk catalogue is copied in.

---

## 1. Blocking risks (prevent a full 21M lock)

| # | Risk | Evidence | Consequence | Owner / next action |
|---|---|---|---|---|
| R-21M-01 | **Android compilation is unverified** | Gradle 8.7 distribution cannot be downloaded; no Android SDK; all Gradle tasks exit before configuration | The Settings/Diagnostics Compose code is not proven to compile under the real Android toolchain; the harness only compiles pure Kotlin and a separate UI closure | Run `./gradlew testDebugUnitTest lint assembleDebug` in a configured Android environment |
| R-21M-02 | **Android/device verification is blocked** | A two-method GATE 21M `androidTest` source file now exists but is uncompiled/unexecuted; no SDK/device; `connectedDebugAndroidTest` blocked before configuration | TalkBack, keyboard, large text, RTL, theme, recreation and real navigation remain unverified | Run the Gradle unit/lint/build/instrumentation checks in a configured Android environment |
| R-21M-03 | **Historical baseline not reproducible** | Current checkout is a parentless snapshot (`e22a327…`); historical `19c8a0d…` is absent locally; 229/2799 revision cannot be checked out | The delta from the reported 229/2799 run cannot be independently attributed per gate; only current 237/2924 is executed | Re-run the same harness at the reported revision, or accept the historical figure as report-only |
| R-21M-04 | **Prior final-lock document is internally inconsistent** | `docs/GATE_21_FINAL_LOCK.md` claims `GATE 21 LOCKED` while line 532 records 21M `NOT STARTED` and 21L blocked | A reader may mistake the banner for a current lock; this gate does not edit prior documents | Resolve in the GATE 21 final-closure gate, not in 21M |

---

## 2. Scope / dependency risks

| # | Risk | Evidence | Consequence | Owner |
|---|---|---|---|---|
| R-21M-05 | **GATE 21L remains blocked pending its prerequisite** | `GATE_21L_REPORT.md:3`; `GATE_20_CUSTOM_STUDY_CONTRACT.md`; limited-scope lock claims in `GATE_20_FINAL_REPORT.md` / `GATE_20B_CONTRACT_LOCK.md` | No filtered-session lifecycle, identity, ledger or Custom Study UI can be implemented or claimed under the current authorization | GATE 20 scope reconciliation, then 21L |
| R-21M-22 | **GATE 20 scope/status artifacts conflict** | Final/contract-lock reports say limited refusal/disclosure/identity/capability truth scope is locked; Custom Study contract and 21L report retain broader BLOCKED status | The limited subset must not be mistaken for filtered-session lifecycle completion or a 21L unlock | Resolve only in the owning Gate process; preserve the current 21L BLOCKED status here |
| R-21M-06 | **Production PC Anki adapter is absent** | no `PcAnkiBackend`; registry is `AnkiBackendRegistry(listOf(ankiDroidBackend))` | PC Anki cannot be selected or shown operational; PC Custom Study is unsupported | Future PC backend gate |
| R-21M-07 | **No real PC integration evidence** | No PC adapter, no PC configuration, no PC endpoint; `FooSoft/anki-connect` pin is unpinned | Every PC capability row is either absent or a generic future UI fixture; no real PC behavior is proven | Future PC integration gate |
| R-21M-08 | **Read-context projection is not exposed** | `AnkiReadContextController.activeLeases` is private; diagnostics input has no `readContextOwners` supplier | The diagnostics surface reports `NOT_PROJECTED` for Library/Browser/Details ownership; it does not display live read-context owners | Future projection owner (21F follow-up or 21N) |
| R-21M-09 | **21M diagnostics are not in the export/copy path** | `DiagnosticsRepository.getExportText()` unchanged; matrix row `AUDIT-21M-R2-25` and `Gate21mArchitectureAuditTest` “the export path is unchanged and the new rows are not silently exported” deliberately leave it unchanged | A support engineer can see backend rows on screen but cannot include them in the existing diagnostic export; widening the export needs a privacy decision | Diagnostics/privacy owner |
| R-21M-10 | **No collection identity assurance on AnkiDroid** | `AnkiDroidDeckMapper` sets `collectionKey = null`; GATE 21B R1 | The production active-session assurance is nominal/unverifiable, never `AUTHORITATIVE_MATCH`; the UI must not claim verified continuity | GATE 21C/21D product decision or device probe |

---

## 3. Presentation and freshness risks

| # | Risk | Evidence | Consequence | Owner |
|---|---|---|---|---|
| R-21M-11 | **Status is last observation, not live** | `AnkiBackendState` has no timestamp; `OBSERVATION_NOTICE` states it | A user may read “Ready” as current; Refresh is the only re-check; no TTL is invented | GATE 21D contract; UI copy |
| R-21M-12 | **AnkiDroid status can stay `Checking` until a refresh** | GATE 21D R-21D-03: registry availability is only as fresh as `refreshAvailability()`; foreground hook drives a different owner | Settings may show “Checking…” before Library/Study or Refresh; the UI offers the sanctioned Refresh action | 21D/21E/21M follow-up |
| R-21M-13 | **PermissionRequired has no interactive grant** | AnkiDroid integration permission is install-time declared; `AnkiDroidPermissionManager` has no interactive grant | The UI can explain the missing permission but cannot fix it; it deliberately offers no inert “Grant” button | Product/AnkiDroid integration |
| R-21M-14 | **Hard-coded English copy** | Repository convention: only notification strings use `strings.xml`; no `values-ar` | No Arabic UI localization for this section; RTL layout is structurally supported but not rendered | Repository-wide localization effort |
| R-21M-15 | **Runtime accessibility unverified** | No device/TalkBack; source uses `Role.RadioButton`, text status and disabled explanations | Selected/disabled states are likely announced correctly but are not proven on a device | R-21M-02 follow-up |
| R-21M-16 | **Text scaling and truncation unverified** | No device; long identity tokens and explanations can wrap | A small screen or large font could truncate a warning; no executed evidence | R-21M-02 follow-up |
| R-21M-17 | **Dark/light theme contrast unverified** | No visual rendering; uses `AppColors` tokens | Critical status could be low-contrast in one theme; not proven | R-21M-02 follow-up |
| R-21M-21 | **Diagnostics recomposition fix is source-verified only** | The card now observes `ankiPendingTransactions`; no Compose runtime test exists | The fix is logically correct (state collection triggers recomposition), but no UI test proves the card leaves `NOT_PROJECTED` on a device | R-21M-02 follow-up |

---

## 4. Persistence and data risks

| # | Risk | Evidence | Consequence | Owner |
|---|---|---|---|---|
| R-21M-18 | **DataStore path not exercised by the JVM harness** | Harness uses in-memory DataStore shim; no Gradle unit test executed | The repository's real DataStore write/echo behavior is not re-proven by this run; controller tests use an injected preference flow | Gradle/device verification |
| R-21M-19 | **Write error token can be stale** | `lastWriteError` is a single latest token; a new refusal with no repository token falls back to `DEFAULT_WRITE_ERROR_TOKEN` | The UI never shows false success, but the displayed error token may describe an earlier failure rather than the current one | Minor UI polish; not a safety violation |
| R-21M-20 | **Unresolved transaction projection depends on each ledger's `unresolved()` semantics** | Reader reduces `status.name`; family semantics owned by GATE 11/13/17/18 | If a family's `unresolved()` excludes a record the family considers active, diagnostics would undercount; the reader cannot fix that | Family owners |

---

## 5. Risks that were checked and found not to apply

| Risk | Why not applicable |
|---|---|
| PC Anki shown operational | No PC adapter; registry has one AnkiDroid backend; PC row disabled |
| PC Custom Study shown operational | Custom-study claim is `NONE`; no lifecycle control; no filtered family |
| Fake production capability claim | Fake is not persistable or selectable; production registry never contains one |
| Active session migrated by preference change | Controller cannot call `locked()`/`released()`; test asserts lock unchanged |
| Cross-backend transaction replay control | No such action; reader groups by persisted owner |
| Credential/content leak in diagnostics | No free-text fields; key values withheld; tests assert no leak |
| Duplicate registry/resolver/session machine | Matrix row `AUDIT-21M-R2-22`; `Gate21mArchitectureAuditTest` “the gate adds presentation models and one controller, and no new authority” / “no GATE 21M file becomes a second resolution site”; one registry and one resolver |
| `CONFLICT` collapsed into `AMBIGUOUS` | Diagnostics test asserts separate tokens |
| “Ready” from Android connectivity or PC agent | No connectivity/agent source in the UI; `Ready` comes only from `AnkiAvailability.Ready` |

---

## 6. Risk-based lock position

```text
R-21M-01 and R-21M-02 are mandatory-evidence risks, not observed defects.
They block a full GATE 21M lock under the R2 Case B rule.

No risk in this register justifies:
  - claiming GATE 21M LOCKED;
  - claiming GATE 20/21L implemented;
  - claiming PC Anki operational;
  - claiming Gradle/lint/build/instrumentation PASS.
```

The implementation itself has no known production defect after this audit. The residual risk is
concentrated in unexecuted Android/device evidence and explicitly blocked GATE 20/21L scope.

---

## 7. Current checkout revalidation — Arena session (2026-10-18 UTC)

The current source is `e22a32798259c3abd88001963afe511fd950cb2e` on
`arena/95bebe16-study-agent-client`; the original report's `19c8a0d…` object is unavailable locally.
The worktree was clean before revalidation. A new two-method Compose test file was created at
`app/src/androidTest/java/com/studyagent/client/ui/Gate21mBackendSelectionInstrumentedTest.kt`.
It reads and restores the stored preference semantically and uses production row keys; it is **not**
compiled or run. `assertExists()` is used as in existing repository instrumentation tests and does
not require a separate import.

Fresh current-source checks:

```text
GATE 21M JVM:       7 classes / 115 tests / 0 failed
Full JVM harness:  237 classes / 2924 tests / 0 failed
Capability pins:    13 hold / 0 disagree / 0 not verifiable
Architecture audit: 26/26 source-level; runtime checks still blocked
Gradle tasks:       blocked before configuration by Gradle 8.7 download TLS EOF
Android SDK/device: absent
```

No Gradle task reached configuration. The new `androidTest` source is outside the JVM harness, so it
cannot reduce R-21M-01/02. GATE 21M remains eligible for source-level acceptance only; its lock stays
BLOCKED pending Android evidence.

The GATE 20 artifact disagreement is now explicitly recorded as R-21M-22: a limited contract-enforcing
subset is described as locked in the final/contract-lock reports, but lifecycle prerequisite status
remains blocked in the Custom Study contract and GATE 21L report. Current source has no production PC
Anki adapter, no filtered-session lifecycle, and an AnkiDroid custom-study capability claim of NONE.
Per the explicit current instruction, GATE 21L remains BLOCKED; this session does not change earlier
Gate status documents or implement GATE 20/21L.
