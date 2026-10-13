# GATE 21M — Backend Selection UI, Availability Indicators & Diagnostics (BLOCKED Report)

**Verdict:** `BLOCKED` — `IMPLEMENTATION AUTHORIZED: NO`
**Repository HEAD:** `43668eff0ea45353b1f8de56cf116c13e4701ffa` (branch `arena/375e8521-study-agent-client`)
**Type of this gate run:** hard-prerequisite validation → UI architecture inventory → source inventory →
executed harness baseline → blocker register → gate lock decision.
**Production code changed by this gate:** none. No UI, no ViewModel, no DI, no test, no build change.
**Next authorized sub-gate:** `NONE`. GATE 21N must not start from this report.

---

## 1. Initial report (required header)

```text
REPOSITORY HEAD:    43668eff0ea45353b1f8de56cf116c13e4701ffa
BRANCH:             arena/375e8521-study-agent-client (from master @ 43668ef)
WORKTREE STATUS:    clean at the start of the gate (`git status --porcelain` → empty); this gate adds
                    this document only

21A: LOCKED          docs/GATE_21A_DISCOVERY.md:790 "STATUS: PASS", :993 "GATE 21A: PASS"
21B: LOCKED          docs/GATE_21B_CONTRACT_LOCK.md:1766 "GATE 21B: LOCKED"
21C: LOCKED          docs/GATE_21C_IDENTITY_IMPLEMENTATION.md:613 "GATE 21C: LOCKED (identity foundation)"
21D: LOCKED          docs/GATE_21D_REGISTRY_IMPLEMENTATION.md:775 "GATE 21D: LOCKED (harness-verified)"
21E: LOCKED          docs/GATE_21E_RESOLVER_IMPLEMENTATION.md:354 "GATE 21E: PASS (harness-verified)"
21F: LOCKED          docs/GATE_21F_READ_CONTEXT_ISOLATION.md:16 "PASS (harness-verified) / LOCKED"
21G: LOCKED          docs/GATE_21G_VERIFICATION_REPORT.md:454 "LOCKED (harness-verified; Gradle blocked environmentally)"
21H: LOCKED          docs/GATE_21H_VERIFICATION_REPORT.md:8 "Gate-lock decision: LOCKED"
21I: LOCKED          docs/GATE_21I_VERIFICATION_REPORT.md:728 "GATE 21I: LOCKED (harness-verified scope)"
21J: NOT_LOCKED      docs/GATE_21J_NOTE_EDIT_BINDING.md:21 "IMPLEMENTED and HARNESS-VERIFIED, pending
                     audit approval of DECISION-21J-03"
21K: NOT_LOCKED      no gate report exists (`ls docs/ | grep 21K` → empty); creation route still
                     hard-pinned to AnkiDroid — app/.../ui/navigation/AppNavHost.kt:121 and :381
21L: NOT_LOCKED      docs/GATE_21L_REPORT.md:47 "GATE 21L: BLOCKED"; :7 "Next authorized sub-gate:
                     NONE — return to GATE 20. GATE 21M must not start from this report (§13)"

BACKEND PREFERENCE:
IMPLEMENTED — two layers over one storage:
  • GATE 21E authoritative `BackendPreferenceRepository` (core/anki/BackendPreference.kt,
    data/preferences/BackendPreferenceRepository.kt), wired at di/AppContainer.kt:545-546 and consumed
    by the 21F read-context controller at di/AppContainer.kt:526.
  • Pre-21E `AnkiBackendPreferenceStore` / `DataStoreAnkiBackendPreferenceStore` (kind-level
    `AnkiBackendMode`), which is what the Settings UI actually writes today.
  Both share the DataStore keys `anki_backend_mode` and `anki_backend_instance_pin`
  (AppSettingsPreferencesCodec.kt:91,:97), so there is one storage authority — but **no UI can write
  the instance pin** (see §4.1).

BACKEND REGISTRY:
IMPLEMENTED — `class AnkiBackendRegistry(backends: List<AnkiBackend>)`,
core/anki/AnkiBackendSelector.kt:42; exposes `members`, `states()`, `find(id)`.

RESOLVER:
IMPLEMENTED — `object AnkiBackendResolver2` (core/anki/AnkiBackendResolver2.kt:42) returning
`BackendResolutionResult2`; consumed in production by `AnkiStudyEffectExecutor`,
`AnkiReadContextController`, `AnkiLocalStudyStarter`. PARTIAL at the UI boundary: Settings previews
through the pre-21E `AnkiBackendStatusCenter.resolveFor` + `AnkiBackendResolver` (v1), not
`AnkiBackendResolver2` (SettingsViewModel.kt:52-66).

SETTINGS SCREEN:
EXISTS — ui/screens/settings/SettingsScreen.kt (1044 lines) + SettingsViewModel.kt (205 lines),
reached through the existing navigation; an "Anki backend" section already renders
(SettingsScreen.kt:481 `item(key = "anki-backend-selection")`, test tag prefix :83).

BACKEND CONFIGURATION UI:
PARTIAL — a kind-level selector only. `buildAnkiBackendSelectionUi`
(ui/components/anki/AnkiBackendUiModels.kt) emits exactly three fixed rows keyed by
`AnkiBackendMode` (:120 `options = listOf(`) and aggregates instances by kind
(:91 `fun statusFor(kind: AnkiBackendKind)`, :94 `matching.filter { it.isReachable }`).
No capability display, no availability-freshness display, no per-instance rows.

STUDYSESSION OWNERSHIP STATE:
NOT_EXPOSED — `grep -rn "StudySessionBackendLock\|backendLock" app/src/main/java/com/studyagent/client/ui`
returns **zero** matches. `lockedBackendIndicator()` (AnkiBackendUiModels.kt:69) is referenced only by
`Gate21BackendSelectionUiTest`, never by a production screen.

SAFE DIAGNOSTICS PROJECTIONS:
PARTIAL — `AnkiBackendRoutingDiagnostics` (core/anki/AnkiBackendPreference.kt:39) is built at
di/AppContainer.kt:796-806 and injected into `DiagnosticsRepository`
(data/repository/DiagnosticsRepository.kt:190), but `DiagnosticsScreen.kt` contains **no** backend,
availability, capability or preference content (`grep -nE "ackend|Availability|Capabilit|Preference"`
→ no matches). The projection exists; the surface does not.
```

**Gate condition met.** The gate's own §4 hard prerequisite is `GATE 21A–21L LOCKED`, and §4 states:
*"If an essential prerequisite is missing or contradictory: `GATE 21M: BLOCKED` /
`IMPLEMENTATION AUTHORIZED: NO`. Do not recreate Registry, Resolver, or StudySession machinery inside
the UI. Produce a source-grounded blocker report and stop."* Three sub-gates are not locked. The
immediately preceding gate report additionally names 21M as not-startable
(`docs/GATE_21L_REPORT.md:7`).

```text
GATE 21M: BLOCKED

IMPLEMENTATION AUTHORIZED: NO
```

---

## 2. Method

| Step | What was done | Where the evidence lives |
|---|---|---|
| 1 | Confirmed HEAD, branch, and a clean worktree before reading anything. | `git rev-parse HEAD`, `git branch --show-current`, `git status --porcelain` |
| 2 | Read every GATE 20/21 artifact in `docs/` and extracted each sub-gate's own lock statement by line number, rather than accepting a summary. | `docs/GATE_21*.md`, `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` |
| 3 | Re-derived the 21K and 21L blocker facts **from source at this HEAD**, not from the 21L document. | §3.2, §3.3 |
| 4 | Inventoried the actual Compose/ViewModel/DI architecture the prompt's CHECKPOINT 21M-00 asks for. | §4 |
| 5 | Unblocked a JVM (none was installed) and ran the repository's own supported fallback runner to obtain an executed baseline. | §5 |
| 6 | Recorded the blocker register and the gate lock decision. | §6, §7 |

---

## 3. Blocker register

### 3.1 GATE 21J is not locked, and its open item is an unresolved contradiction

`docs/GATE_21J_NOTE_EDIT_BINDING.md:21`:

> **Gate lock:** IMPLEMENTED and HARNESS-VERIFIED, pending audit approval of DECISION-21J-03. Not device-verified.

DECISION-21J-03 is not a formality. The same document records that the locked sources **disagree with
each other** about the very policy 21M would have to explain to a user:

> **Inconsistency to resolve:** `DECISION-21I-03` (`GATE_21I_REVIEWER_ACTION_ROUTING.md:83`) says
> NOMINAL-`OP-M3` is **permitted**, but the 21C matrix code blocks it
> (`AnkiIdentityContinuation.kt:210`). The 21J cell follows the 21I *text* and records the 21C *code*
> as the deviation. **The audit should decide which is the locked truth.**

This lands directly on GATE 21M:

* **CHECKPOINT 21M-17 (Backend Error Mapping)** must produce per-refusal copy for `collection_mismatch`,
  `identity_unverifiable_degraded`, and nominal-unknown routes. Writing "this operation is blocked
  because collection identity could not be verified" is a claim about a policy that is currently
  disputed between a locked document and locked code.
* **CHECKPOINT 21M-24 (Collection Identity & Continuity Display)** is the same problem in the
  diagnostics surface, and its own text says *"Follow GATE 21B policy"* — the cell in question is the
  one under dispute.
* **VER-21M-29 / VER-21M-31 / VER-21M-32** would be asserting UI copy against an undecided contract.

A UI that freezes copy for an unresolved policy is not a projection of authority; it becomes a
second, competing statement of it. That is exactly what `AUDIT-21M-23` (Source-of-Truth Separation)
forbids.

### 3.2 GATE 21K is not locked — re-verified from source at this HEAD

There is no GATE 21K report in the repository (`ls docs/ | grep -i 21K` → empty). The 21L report
claimed creation is still pinned to AnkiDroid; that claim was re-derived independently here:

```text
app/src/main/java/com/studyagent/client/ui/navigation/AppNavHost.kt:121
    navController.navigate(Screen.AddNote.createRoute(container.ankiDroidBackend.id))

app/src/main/java/com/studyagent/client/ui/navigation/AppNavHost.kt:381
    if (addNoteBackend == null || addNoteBackend !== container.ankiDroidBackend) {
        ReadContextBindingPlaceholder(
            title = "New note",
            message = "The existing note-creation target is not registered as the exact AnkiDroid backend.",
```

Note creation therefore *refuses* any backend other than the AnkiDroid instance. Consequence for 21M:

* **CHECKPOINT 21M-11 (Capability Display)** would have to show a "Note Creation" row. On a PC backend
  the honest value is not "unsupported by the backend" and not "supported" — it is *"the application
  routes creation to AnkiDroid regardless of your preference."* That is a routing fact about this
  client, and presenting it as a backend capability would violate `INV-21M-12` and `AUDIT-21M-09`.
* **PART VII §6 (Capability Presentation Matrix)** requires a populated "Create notes" row per backend.
  It cannot be populated truthfully until the creation route is owned by a backend.

### 3.3 GATE 21L is BLOCKED and GATE 20 never landed — four 21M checkpoints have no input contract

`docs/GATE_21L_REPORT.md:7`:

> **Next authorized sub-gate:** `NONE` — return to **GATE 20**. GATE 21M must not start from this report (§13).

`docs/GATE_20_CUSTOM_STUDY_CONTRACT.md:3`: Phase B (contract lock) **BLOCKED**, Phase C
(implementation) **not started**, `IMPLEMENTATION AUTHORIZED: NO`.

Re-verified from source at this HEAD — the symbols a Custom Study owner would require do not exist:

```text
$ rg -n "class CustomStudy|FilteredSession|CustomStudyCoordinator|AnkiFilteredSessionRef" app/src server
  → only incidental hits: `filteredDeckCount` (AnkiLibrary.kt:52), `inFilteredDeck`
    (AnkiDroidCommitEvidence.kt:55, AnkiDroidRatingCommitter.kt:205), a comment (AnkiCardHydration.kt:103)

$ rg -n "RebuildFilteredDeck|EmptyFilteredDeck|AddOrUpdateFilteredDeck" app/src server
  → no matches
```

And there is no capability flag to display:

```text
$ rg -niE "customStudy|custom_study|filtered" app/src/main/java/com/studyagent/client/core/anki/AnkiCapabilities.kt
  → one comment on line 150 ("…another existing, non-filtered deck…"); no custom-study capability member
```

Checkpoints that consequently cannot be implemented truthfully:

| Checkpoint | Required input | Actual state |
|---|---|---|
| **21M-14** Custom Study Ownership Indicator | "Use GATE 21L state" | No binding object, no filtered-session ref, no lifecycle status, no persistence |
| **21M-23** Other Pending Operation Diagnostics | "GATE 20 Custom Study lifecycle" projection | No coordinator, no ledger, no read-only projection |
| **21M-11 / PART VII §6** Custom Study capability row | A capability flag | No flag exists — a row would be a false claim |
| **21M-35** Regression scope review | GATE 20 / 21K / 21L features | Not implemented; cannot be runtime-tested |
| **VER-21M-25** Active Custom Study Indicator | Locked filtered-session backend | No such state exists anywhere |

`docs/GATE_21L_REPORT.md` §13.2 states this prohibition in the same terms and lists the exact
surfaces 21M must not present, including: *"Capability rows for custom study (no flag exists —
presenting a row would be a false claim)."*

---

## 4. UI architecture inventory (CHECKPOINT 21M-00, completed)

Recorded because it is prerequisite work that costs nothing to hand forward and it changes the shape
of the eventual 21M.

| Concern | Existing component | Source file | Note |
|---|---|---|---|
| Settings navigation | existing settings destination | `ui/screens/settings/SettingsScreen.kt` (1044 lines) | Reuse; do not add a second settings screen |
| Settings state | `SettingsViewModel` | `ui/screens/settings/SettingsViewModel.kt` (205 lines) | Reuse; already holds the backend selector |
| Preference persistence (21E) | `BackendPreferenceRepository` | `data/preferences/BackendPreferenceRepository.kt`; wired `di/AppContainer.kt:545-546`, consumed `:526` | Authoritative; **not** written by any UI |
| Preference persistence (pre-21E) | `DataStoreAnkiBackendPreferenceStore` | `data/preferences/DataStoreAnkiBackendPreferenceStore.kt` | What Settings writes today |
| Shared storage | `anki_backend_mode`, `anki_backend_instance_pin` | `data/preferences/AppSettingsPreferencesCodec.kt:91,:97` | One storage authority |
| Backend list (21D) | `AnkiBackendRegistry` | `core/anki/AnkiBackendSelector.kt:42` | Registry-driven at the data level ✔ |
| Availability | `AnkiBackendStatusCenter` → `AnkiBackendStatusSnapshot` | `core/anki/AnkiBackendStatus.kt:78`, `:25` | Built on the registry ✔ |
| Capabilities | `AnkiCapabilities`, `AnkiBackendState.capabilityEvidence` | `core/anki/AnkiCapabilities.kt`, `AnkiBackendState.kt:96-114` | Exists in domain; **never surfaced in any selector row** |
| Active StudySession (21G) | `StudySessionBackendLock`, `StudySessionLockStore` | `core/anki/StudySessionBackendLock.kt`, `data/preferences/StudySessionLockDataStore.kt` | **Zero references from `ui/`** |
| Read context (21F) | `AnkiReadContextViewModel` | `ui/navigation/AppNavHost.kt:83-103` | Wired ✔ |
| Diagnostics | `DiagnosticsScreen` / `DiagnosticsRepository` | `ui/screens/diagnostics/DiagnosticsScreen.kt` (374 lines), `data/repository/DiagnosticsRepository.kt:190` | Routing projection injected, **not rendered** |
| Design system | `AppColors`, `AppSpacing`, `SettingsCard`, `SectionHeader` | `ui/screens/settings/SettingsScreen.kt` | Reuse |
| Test stack | JVM harness + `Gate21*` audit tests | `tools/jvm-harness`, `app/src/test/.../Gate21*.kt` | Compose UI tests **cannot run here** (§5) |

### 4.1 Defects 21M must fix, found while inventorying (not blockers themselves)

These are recorded so the eventual gate does not rediscover them. None is authorized to be fixed
under this gate.

1. **The wired selector is kind-level, so it cannot satisfy the gate's core requirement.**
   `buildAnkiBackendSelectionUi` returns three fixed options keyed by `AnkiBackendMode`
   (`AnkiBackendUiModels.kt:120`), and `statusFor(kind)` collapses instances with
   `entries.filter { it.descriptor.kind == kind }` (`:91-96`). Two PC profiles render as one row
   labelled "2 ready". That directly conflicts with `INV-21M-04` (*"BackendKind does not uniquely
   identify configured instances"*), `INV-21M-03`, CHECKPOINT 21M-05/21M-08 and VER-21M-06. The 21E
   repository already supports the instance pin; no UI writes it.

2. **The legacy write path erases an instance pin.** `PreferencesDataStore.setAnkiBackendMode` is
   `setAnkiBackendPreference(mode, pin = null)` (`PreferencesDataStore.kt:143-144`), and `pin = null`
   *removes* the stored pin (`:155-158`). Every write from the current Settings selector therefore
   deletes any `anki_backend_instance_pin`. Once instance pinning is added, leaving this call site in
   place would silently discard the user's pin — the opposite of `INV-21M-06`.

3. **No freshness field exists to satisfy CHECKPOINT 21M-10.** `AnkiBackendStatusEntry`
   (`AnkiBackendStatus.kt:25-36`) carries `descriptor`, `statusToken`, `unavailabilityToken` and a
   derived `isReachable`. There is no observation time or staleness marker. Note also that
   `docs/GATE_21B_CONTRACT_LOCK.md:1619` sets 21M's OUTPUT CONTRACT as *"no 'connected' boolean that
   collapses R0–R4"* — and `isReachable` is precisely such a boolean, so the projection 21M would
   consume needs extending, not just the UI.

4. **`lockedBackendIndicator()` is documented as reusable but is not used.**
   `docs/GATE_21B_CONTRACT_LOCK.md:1620` lists "Study locked-backend indicator" under *EXISTING
   COMPONENTS TO REUSE* for 21M. In this tree the function (`AnkiBackendUiModels.kt:69`) has no
   production caller — only `Gate21BackendSelectionUiTest:120-125`. The 21M-13 indicator must be built,
   not reused.

5. **Capability evidence is dropped at the UI boundary.** `AnkiBackendState` distinguishes
   `STATIC_CLAIM` / `RUNTIME_NEGOTIATED` / `NONE` and supported-vs-operational
   (`AnkiBackendState.kt:109-126`), but `AnkiBackendStatusEntry.toEntry()` keeps only two tokens. The
   "Supported / Unsupported / Unknown / supported-but-unavailable" distinction CHECKPOINT 21M-11
   requires exists in the domain and is lost before the UI can see it.

---

## 5. Build and regression validation (executed)

### 5.1 Environment as found

```text
$ java -version                      → command not found
$ ls /usr/lib/jvm                    → absent
$ echo $ANDROID_HOME $ANDROID_SDK_ROOT → both empty
$ ls local.properties                → absent
$ apt-get install openjdk-17-...     → Permission denied (not root)
```

So `./gradlew` could not run at all, and neither could the repository's JVM harness, which also needs
a JVM.

### 5.2 Unblocking

A JVM was obtained from an allowed host (PyPI) rather than reporting everything unverified:

```text
pip download --no-deps jdk4py kotlin-jupyter-kernel   → Temurin 25.0.2 + Kotlin 2.3.10 compiler
git clone --sparse Sable/android-platforms (android-34) → android.jar
git clone --branch 1.10.2 Kotlin/kotlinx.coroutines    → kotlinx-coroutines-test source
```

then the repository's own bootstrap, unchanged:

```text
COMMAND:  tools/jvm-harness/bin/bootstrap-sandbox.sh
EXECUTED: yes
EXIT CODE: 0
RESULT:   PASS — "main errors: 0 (files: 328)"
                   "compose errors: 0 [scope: GATE 16 UI closure] (files: 24, stub files: 46)"
                   "test errors: 0 (files: 250)"
ENVIRONMENT BLOCKER: none for this step
```

### 5.3 Executed test baseline

```text
COMMAND:  tools/jvm-harness/bin/run.sh
EXECUTED: yes
EXIT CODE: 0
RESULT:   PASS — "RESULT classes=218 tests=2625 passed=2625 failed=0 ignored=0 time=33.9s"
ENVIRONMENT BLOCKER: none
```

This is an **executed** baseline at HEAD `43668ef`, and it matches the count GATE 21J recorded
(`docs/GATE_21J_NOTE_EDIT_BINDING.md`: "Tests: **2625 / 2625**"). Per
`tools/jvm-harness/README.md` it is equivalence evidence, **not** a substitute for
`./gradlew testDebugUnitTest`; the harness compiles with Kotlin 2.3/K2 against Gradle's 1.9.24/K1,
shims JUnit, DataStore and OkHttp, and excludes `ui/`, `service/`, `di/` and all instrumented tests.

### 5.4 Tasks that could not be executed

| Command | Executed | Exit code | Result | Environment blocker |
|---|---|---|---|---|
| `./gradlew testDebugUnitTest` | no | — | **BLOCKED** | no Android SDK, no `local.properties`, Gradle cannot configure |
| `./gradlew lint` | no | — | **BLOCKED** | same |
| `./gradlew assembleDebug` | no | — | **BLOCKED** | same |
| `./gradlew assembleRelease` | no | — | **BLOCKED** | same |
| `./gradlew connectedDebugAndroidTest` | no | — | **BLOCKED** | no device or emulator |

This matters independently of §3: 21M's Definition of Done requires *"Build: All required executable
build and regression checks must pass"* and CHECKPOINT 21M-33 is a **Compose UI test** checkpoint.
The harness compiles Compose sources for closure only and cannot run a Compose test; instrumented
accessibility/RTL/theme checks (VER-21M-38, -39, -40, -41, -45) have no runner here. Even with every
prerequisite locked, GATE 21M could not reach `LOCKED` in this environment.

---

## 6. Verification matrix (this gate run)

Only the prerequisites and the environment were verifiable. Every functional verification is
unreachable because no implementation was authorized.

| Verification | Result | Evidence |
|---|---|---|
| Prerequisite validation (21A–21L) | **FAIL** — 21J NOT_LOCKED, 21K NOT_LOCKED, 21L BLOCKED | §1, §3 |
| UI architecture inventory (21M-00) | **PASS** | §4 |
| Executed regression baseline | **PASS** — 2625/2625 | §5.3 |
| Gradle build / lint / assemble | **BLOCKED** | §5.4 |
| VER-21M-01 … VER-21M-45 | **BLOCKED** — no implementation authorized | §3, §5.4 |
| VER-21M-44 (previous-gate regression) | **PASS (harness scope)** — 2625/2625; Gradle scope BLOCKED | §5.3, §5.4 |
| VER-21M-45 (device/emulator) | **BLOCKED** — no device, emulator or Android SDK | §5.1, §5.4 |
| AUDIT-21M-01 … AUDIT-21M-25 | **BLOCKED** — nothing to audit | §3 |

---

## 7. Gate lock decision

```text
GATE 21M:                             BLOCKED
IMPLEMENTATION AUTHORIZED:            NO

PREFERENCE UI:                        NOT_ASSESSED   (blocked)
PERSISTENCE:                          NOT_ASSESSED   (blocked)
MULTIPLE BACKEND INSTANCES:           NOT_ASSESSED   (blocked; defect §4.1.1 recorded)
AVAILABILITY ACCURACY:                NOT_ASSESSED   (blocked; defect §4.1.3 recorded)
CAPABILITY HONESTY:                   NOT_ASSESSED   (blocked; defect §4.1.5 recorded)
SESSION OWNERSHIP DISPLAY:            NOT_ASSESSED   (blocked; defect §4.1.4 recorded)
DIAGNOSTICS SAFETY:                   NOT_ASSESSED   (blocked)
ERROR HANDLING:                       NOT_ASSESSED   (blocked; §3.1 unresolved policy)
ACCESSIBILITY:                        NOT_ASSESSED   (blocked; no runner)
LOCALIZATION:                         NOT_ASSESSED   (blocked)
ARCHITECTURE AUDIT:                   BLOCKED        (nothing implemented)
VERIFICATION:                         BLOCKED        (nothing implemented; no Gradle/device)
BUILD:                                PARTIAL        (harness 2625/2625 PASS; Gradle BLOCKED)

NEXT AUTHORIZED SUB-GATE:             NONE
```

### 7.1 What must happen before 21M can start

In dependency order:

1. **GATE 20** — land the Custom Study / filtered-session contract and implementation. 21B records it
   as a *hard blocker* for 21L (`docs/GATE_21B_CONTRACT_LOCK.md:1613`).
2. **GATE 21L** — bind Custom Study backend ownership, then lock.
3. **GATE 21K** — give note creation a backend owner (remove the `container.ankiDroidBackend` pin at
   `AppNavHost.kt:121`/`:381`) and write its gate report.
4. **GATE 21J audit** — decide whether `DECISION-21I-03` (text) or `AnkiIdentityContinuation.kt:210`
   (code) is the locked truth for NOMINAL-`OP-M3`, then lock 21J.
5. **Environment** — an Android SDK (or a device) so Gradle, lint, `assembleDebug`, Compose UI tests
   and `connectedDebugAndroidTest` can actually execute.

### 7.2 Handoff that is safe to use today

`docs/GATE_21L_REPORT.md` §13.1 remains the accurate list of contracts 21M *may* consume
(`BackendPreference`, `AnkiBackendId`, `AnkiBackendState`, `AnkiCapabilities`,
`BackendResolutionResult2`, `StudySessionBackendLock`, `AnkiBackendUnavailableReason`,
`AnkiCollectionIdentity`, `AnkiTransactionRecoveryRouting`). This report adds three corrections to
that handoff, all verified at this HEAD:

* the "Study locked-backend indicator" is **not** wired to any screen (§4.1.4);
* the selector that exists is **kind-level** and must be rebuilt around `AnkiBackendId` (§4.1.1);
* the availability/capability projections feeding the UI need extending before 21M-10 and 21M-11 have
  anything honest to render (§4.1.3, §4.1.5).

Nothing in this document recreates Registry, Resolver, StudySession or transaction machinery, and no
UI, ViewModel, DI, test or build file was modified.
