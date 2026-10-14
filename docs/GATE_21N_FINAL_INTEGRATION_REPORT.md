# GATE 21N — Final Multi-Backend Integration: Prerequisite STOP Report

**Date:** 2026-10-14 (UTC)
**Repository:** `Drmusab/Study-Agent-Client`
**Source HEAD:** `43668eff0ea45353b1f8de56cf116c13e4701ffa`
**Branch:** `arena/70fa60bf-study-agent-client`
**Verdict:** **BLOCKED — production implementation and final GATE 21 lock NOT AUTHORIZED.**

This is the source-grounded discrepancy report required by the prompt's hard prerequisite gate,
not a completed integration, chaos-validation report, or production architecture lock. Only
repository/contract reconciliation and read-only prerequisite investigation were performed.
No production code, tests, build configuration, persisted schemas, or prior contracts were changed.
During the audit stage, no commit, push, real Anki mutation, or GATE 22 implementation was performed.
The user subsequently authorized publishing this report in a pull request on the same branch.
That separate publication step does not authorize production implementation or the final gate lock.

The successful architecture/chaos/lock artifacts and GATE 22 handoff are **not issued**. Their
prerequisites are unmet. Findings and unexecuted verification are consolidated here rather than
creating conflicting final documents. Historical narrow locks are not production certification.

## 1. Final gate status and required initial status

```text
GATE 21N — FINAL MULTI-BACKEND INTEGRATION

STATUS:                         BLOCKED
REPOSITORY:                     Study-Agent-Client
SOURCE HEAD:                    43668eff0ea45353b1f8de56cf116c13e4701ffa
BRANCH:                         arena/70fa60bf-study-agent-client
WORKTREE AT INITIAL INSPECTION: CLEAN

GATE 21A: LOCKED
GATE 21B: LOCKED
GATE 21C: LOCKED
GATE 21D: LOCKED
GATE 21E: LOCKED (downstream-recorded user confirmation; primary wording gap)
GATE 21F: LOCKED
GATE 21G: LOCKED
GATE 21H: LOCKED
GATE 21I: LOCKED (recorded scoped lock; contract conflict remains)
GATE 21J: NOT_LOCKED
GATE 21K: NOT_LOCKED
GATE 21L: NOT_LOCKED (explicit BLOCKED verdict)
GATE 21M: NOT_LOCKED

ARCHITECTURE CONTRACT:                 CONTRADICTORY
INTEGRATION AUTHORIZED:                NO
PRODUCTION IMPLEMENTATION AUTHORIZED: NO
FINAL GATE 21 LOCK AUTHORIZED:         NO
FINAL GATE 21 LOCK:                    NOT_AUTHORIZED
NEXT AUTHORIZED PARENT GATE:           NONE
```

`LOCKED` means an actual recorded lock **at its stated scope**, not a new verification pass.
21C–21I predominantly carry non-Gradle JVM-harness limitations; some carry deferred cases.
21E's own report ends with `PASS (harness-verified)`, not an explicit `LOCKED`. The actual 21F
report records that the user confirmed the 21E lock and that 21F accepted that confirmation.
That indirect evidence is credited with its wording limitation, not independently re-approved.

**STOP trigger:** 21J awaits approval of a contract deviation; 21K has no lock artifact and its
multi-backend binding is absent; 21L is explicitly BLOCKED on unimplemented GATE 20; no 21M
lock artifact was found. Locked §3.3 and the 21I/21J reconciliation exceptions also disagree.
These blockers existed before this run and are independently visible in current source.

The initial worktree was clean. This run leaves only this report as a worktree change.

## 2. Prerequisite matrix and revision reconciliation

### 2.1 Actual GATE 21A–21M artifacts

| Sub-Gate | Lock verified | Actual evidence | Current interpretation |
|---|---|---|---|
| 21A | Yes, recorded discovery lock | [GATE_21A_DISCOVERY.md](GATE_21A_DISCOVERY.md), 1009–1021; [evidence ledger](GATE_21A_EVIDENCE_LEDGER.md) | Historical discovery; source subsequently changed. |
| 21B | Yes, normative contract lock | [GATE_21B_CONTRACT_LOCK.md](GATE_21B_CONTRACT_LOCK.md), 10–22; [decision evidence](GATE_21B_DECISION_EVIDENCE.md); [identity contract](BACKEND_IDENTITY_CONTRACT.md) §3.3 | Contract files unchanged since landing; subsequent family exceptions contradict §3.3. |
| 21C | Yes, scoped lock | [GATE_21C_IDENTITY_IMPLEMENTATION.md](GATE_21C_IDENTITY_IMPLEMENTATION.md), 610–619 | Identity foundation present; not current Android/device proof. |
| 21D | Yes, scoped lock | [GATE_21D_REGISTRY_IMPLEMENTATION.md](GATE_21D_REGISTRY_IMPLEMENTATION.md), 774–811 | Registry present; explicitly PC backend ABSENT and release preconditions outstanding. |
| 21E | Indirect recorded confirmation | [GATE_21E_RESOLVER_IMPLEMENTATION.md](GATE_21E_RESOLVER_IMPLEMENTATION.md), 351–367; [21F](GATE_21F_READ_CONTEXT_ISOLATION.md), 38–40 | Primary lock wording incomplete; downstream confirmation record retained. |
| 21F | Yes, scoped lock | [GATE_21F_READ_CONTEXT_ISOLATION.md](GATE_21F_READ_CONTEXT_ISOLATION.md), 3–25 | Read-context implementation present; no current runtime re-verification. |
| 21G | Yes, scoped lock | [GATE_21G_VERIFICATION_REPORT.md](GATE_21G_VERIFICATION_REPORT.md), 451–468; [implementation](GATE_21G_SESSION_LOCK_IMPLEMENTATION.md) | Persistent lock/API exist; report distinguishes API scope from application restore wiring. |
| 21H | Yes, scoped lock | [GATE_21H_VERIFICATION_REPORT.md](GATE_21H_VERIFICATION_REPORT.md), 8–10, 303–313; [routing report](GATE_21H_REVIEW_COMMIT_ROUTING.md) | Rating routing present; device/DI limitations not newly discharged. |
| 21I | Yes, scoped lock statement | [GATE_21I_VERIFICATION_REPORT.md](GATE_21I_VERIFICATION_REPORT.md), 725–743; [routing report](GATE_21I_REVIEWER_ACTION_ROUTING.md), 83 | Lock statement exists; nominal/continuity OP-M3 exception conflicts with 21B/21C. |
| 21J | **No** | [GATE_21J_NOTE_EDIT_BINDING.md](GATE_21J_NOTE_EDIT_BINDING.md), 19–21, 87, 213–218 | IMPLEMENTED / HARNESS-VERIFIED only; DECISION-21J-03 explicitly pending audit. |
| 21K | **No** | No gate-specific tracked report/test artifact; 21J:218 says NOT STARTED; [21L](GATE_21L_REPORT.md), 30–31 | GATE 18 creation exists, not implemented/locked as 21K multi-backend creation. |
| 21L | **No** | [GATE_21L_REPORT.md](GATE_21L_REPORT.md), 3–7, 625–643 | Authoritative verdict BLOCKED; supersedes [earlier blocked report](GATE_21L_BLOCKED_REPORT.md). |
| 21M | **No** | No gate-specific tracked lock/report/test artifact; 21L:7 prohibits progression | Pre-existing Settings/status/diagnostics UI is not a completed 21M. |

Existence searches used `git ls-files` and `rg` across `README.md`, `docs/`, production and test
roots, not presumed document paths. References to future gates are not implementation evidence.
The historical [GATE_21_MULTI_BACKEND_REPORT.md](GATE_21_MULTI_BACKEND_REPORT.md) parent lock
(11, 228: 27 PASS / 2 PARTIAL / 1 BLOCKED) cannot substitute for this prompt's final 21A–21N lock.

### 2.2 Relevant earlier feature contracts

These are documentary/source statuses, **not tests executed by 21N**. Explicit superseding reports
take precedence over historical drafts. In particular, the old 21B baseline is not a current
feature-status ledger for GATE 11 or GATE 15.

| Gate | Actual documents inspected | Implemented/current finding |
|---|---|---|
| 10 — StudySession | [GATE_10_STUDY_SESSION.md](GATE_10_STUDY_SESSION.md), 42–57; 21G report | Original work log ends FAIL/partial. Session machinery evolved in 11/21G. Old lock gap is neither a new runtime failure nor proof of a current pass. |
| 11 — ReviewCommit | [GATE_11_RATING_COMMIT.md](GATE_11_RATING_COMMIT.md) supersession notice; [GATE_11E_REPORT.md](GATE_11E_REPORT.md), 19–41 | Latest aggregate locks transaction pipeline at `AT_MOST_ONCE_FAIL_CLOSED`; real device and Gradle classes remain blocked. |
| 13 — ReviewerActions | [GATE_13_REVIEWER_ACTIONS.md](GATE_13_REVIEWER_ACTIONS.md), 11–20; 21I report | Implemented/wired; historical harness evidence; real-device mutation not run. |
| 14 — Library | [GATE_14_LIBRARY_DECK_DETAILS.md](GATE_14_LIBRARY_DECK_DETAILS.md), 349–359 | Implementation complete; lock conditional on CI. 21F changes binding, not fresh device verification. |
| 15 — Browser | [GATE_15B_CARD_BROWSER.md](GATE_15B_CARD_BROWSER.md), 12–30, superseding [older draft](GATE_15_CARD_BROWSER.md) | Contract semantics locked; production lock requires Android/device evidence. Real AnkiDroid browser support honestly unsupported. |
| 16 — Details | [GATE_16_CARD_NOTE_DETAILS.md](GATE_16_CARD_NOTE_DETAILS.md), 3, 275 | Implemented; explicitly BLOCKED from LOCKED pending Gradle/device verification. |
| 17 — Note editing | [GATE_17_BACKEND_CONTRACT.md](GATE_17_BACKEND_CONTRACT.md); [GATE_17_NOTE_EDITING.md](GATE_17_NOTE_EDITING.md), 3–9 | Scoped contract/implementation lock; 21J owner routing landed but collection exception unresolved. |
| 18 — Creation/media | [GATE_18_BACKEND_CREATION_CONTRACT.md](GATE_18_BACKEND_CREATION_CONTRACT.md) §24; production creation ledger/coordinator | Creation contract locked; local pipeline present. No 21K completion inferred. |
| 19 — Models/templates | [GATE_19_CONTRACT_LOCK.md](GATE_19_CONTRACT_LOCK.md); [GATE_19_FINAL_REPORT.md](GATE_19_FINAL_REPORT.md), 273–275 | Read-only foundation locked; deep UI surfaces/device verification deferred. |
| 20 — Custom Study | [GATE_20_CUSTOM_STUDY_CONTRACT.md](GATE_20_CUSTOM_STUDY_CONTRACT.md), 3–5, 526–565, 626 | DESIGN_ONLY / contract BLOCKED / implementation NOT STARTED. Essential blocker, not N/A. |

### 2.3 Source revision reconciliation — executed Git evidence

Initially shallow history contained only HEAD. Executed
`git fetch --unshallow --no-tags origin master` successfully without changing the working branch
or source HEAD. Each report/landing commit below was verified as an ancestor with
`git merge-base --is-ancestor <commit> HEAD` (exit 0). Many reports name a base HEAD while describing
tests against uncommitted implementation; the landing/report commit is distinguished from that base.

| Gate/report | Report/landing commit | Selected contract/source delta to current HEAD |
|---|---|---|
| 21A | `5d2abce` | 64 production paths changed after discovery; original source map is historical. |
| 21B | `9729435` | No changes to 21B, identity, selection, ownership or failure contract files. |
| 21C | `e84fcb2` | No changes to five inspected identity/context/assurance foundation files. |
| 21D | `a4fc9de` | No changes to inspected Registry, backend-state or status-center files. |
| 21E | `15ee8b5` | No changes to inspected resolver2, preference model or preference repository. |
| 21F | `e3c2b56` | No changes to inspected read context/controller package, Library repository or AppNavHost. |
| 21G | `019d68b` | `AnkiStudyEffectExecutor.kt` subsequently changed by 21H/21I. |
| 21H | `7d3666d` | `AnkiStudyEffectExecutor.kt` subsequently changed by 21I. |
| 21I | `30a8418` | Inspected reviewer routing/recovery and executor unchanged afterwards. |
| 21J | `ebf5575` | Inspected editing package/UI and AppContainer unchanged afterwards; no audit approval. |
| 21L | `0877e51` | No later app/server changes; verdict remains BLOCKED. |

These comparisons cover stated paths, not every file in each gate. Empty diffs neither rerun
historical tests nor make a harness equivalent to Android/real-backend verification.

Other executed comparisons:

- `git diff --name-only db69d4d..HEAD -- app/src/main` lists six 21J changes:
  `NoteMutationCoordinator.kt`, `NoteMutationRecord.kt`, `NoteMutationRoutingPolicy.kt`,
  `AppContainer.kt`, `EditNoteMapper.kt`, `EditNoteViewModel.kt`. Their gate is not locked.
- `git diff --name-only 7402e0d..HEAD -- app server` is empty: no app/server changes after the
  21J merge. Later merges document 21L blockers, not implementation of 21K/21M.
- `git diff --stat 42336ad..HEAD` lists only 21L reports and `tools/check-capability-pins.sh`.

### 2.4 Contract-to-code traceability map

**M** = `app/src/main/java/com/studyagent/client/`; **T** =
`app/src/test/java/com/studyagent/client/`. Candidate test paths are **not execution evidence**.
Actual inspected excerpts/negative searches are indexed in §14.2.

| Gate | Required responsibility | Actual implementation | Available evidence, not re-executed | Current status |
|---|---|---|---|---|
| 21C | Identity | M `core/anki/AnkiBackendId.kt`, `AnkiRefs.kt`, `AnkiBackendContext.kt`, `AnkiIdentityAssurance.kt`, `AnkiIdentityContinuation.kt` | 21C report; T `anki/Gate21c*Test.kt` | Present, historical scoped lock; 21N verification BLOCKED. |
| 21D | Registry/state | M `core/anki/AnkiBackendSelector.kt` (`AnkiBackendRegistry`), `AnkiBackendState.kt`, `AnkiBackendStatus.kt`; `data/anki/remote/PcAnkiAvailabilitySource.kt` | 21D report; T `anki/Gate21d*Test.kt` | Registry present; PC readiness structure is not an adapter. |
| 21E | Preference/AUTO | M `core/anki/BackendPreference.kt`, `AnkiBackendResolver2.kt`; `data/preferences/BackendPreferenceRepository.kt` | 21E/21F; T `anki/Gate21e*Test.kt` | Present; lock wording gap/legacy status resolver remain. |
| 21F | Read isolation | M `core/anki/AnkiReadContext.kt`, `data/anki/context/*`, Library repository, Browser/Details ViewModels, `ui/navigation/Screen.kt`/`AppNavHost.kt` | 21F; T `anki/Gate21f*Test.kt` | Present; stale-response/paging/cache behavior not executed. |
| 21G | Session owner | M `core/anki/StudySessionBackendLock.kt`, `StudySessionLockStore.kt`, `data/preferences/StudySessionLockDataStore.kt`, `core/study/AnkiStudyEffectExecutor.kt` | 21G; T `anki/Gate21g*Test.kt`, `study/Gate21gVerificationTest.kt` | Lock/API present; no production restore caller found. |
| 21H | Rating recovery | M `core/anki/ReviewCommitRoutingPolicy.kt`, `ReviewCommitLedger.kt`, `ReviewCommitReconciliation.kt`; study executor | 21H; T `study/Gate21h*Test.kt` | Present; no current crash/device evidence. |
| 21I | Reviewer actions | M `core/anki/ReviewerActionRoutingPolicy.kt`, `ReviewerActionRecovery.kt`, coordinator/ledger; executor | 21I; T `study/Gate21i*Test.kt`, `anki/Gate21iArchitectureAuditTest.kt` | Present; keyless OP-M3 conflicts with locked §3.3. |
| 21J | Note editing | M `core/anki/edit/NoteMutationRoutingPolicy.kt`, `NoteMutationCoordinator.kt`, `NoteMutationRecord.kt`; immutable EditNoteViewModel | 21J; T `anki/edit/Gate21jNoteEditOwnershipTest.kt`, `NoteMutationRoutingPolicyTest.kt` | Implemented, explicitly NOT_LOCKED. |
| 21K | Creation/media | Existing M `core/anki/create/*`; AppContainer writer and Add Note route pinned to exact AnkiDroid | GATE 18 contract/tests; 21L prerequisites | Mandatory 21K integration/lock absent. |
| 21L | Custom Study | No coordinator, filtered-session ref/API, lifecycle ledger or implementation tests found | GATE 20/21L; source/test absence search | Essential implementation missing; BLOCKED. |
| 21M | UI/diagnostics | Baseline AnkiBackendUiModels, SettingsViewModel, status center, Study ownership projection/diagnostics | Parent-gate UI/diagnostics tests; 21L prohibition | Partial baseline UI only; no 21M lock/instance-pin picker. |

## 3. Actual architecture inventory — not an approved final diagram

```text
DefaultAppContainer
  +-- AnkiBackendRegistry [ AnkiDroidBackend ONLY ]
  |      +-- public AnkiDroid gateways / shared write permit
  +-- BackendPreferenceRepository
  |      +-- AnkiBackendResolver2 (new unbound contexts)
  |             +-- AnkiReadContextController / Binding / guarded reads
  |                    +-- Library / Deck Details / Browser / Card Details
  +-- AnkiLocalStudyStarter -> StudySessionMachineRepository / StudyReducer
  |      +-- AnkiStudyEffectExecutor -> exact registry lookups
  |             +-- StudySessionBackendLock -> StudySessionLockDataStore
  |             +-- ReviewCommitLedger -> DataStoreReviewCommitStore
  |             +-- ReviewerActionCoordinator / DurableReviewerActionLedger
  |                    -> DataStoreReviewerActionStore
  +-- DefaultNoteMutationCoordinator(registry) / NoteMutationRoutingPolicy
  |      +-- DefaultNoteMutationLedger -> DataStoreNoteMutationStore
  +-- DefaultNoteCreationCoordinator(ankiDroidBackend) [PINNED]
  |      +-- DefaultNoteCreationLedger -> DataStoreNoteCreationStore
  |      +-- existing creation-media workflow
  +-- AnkiBackendStatusCenter / legacy AnkiBackendResolver (status/compatibility)
  +-- ConnectionRepository / speech / recognition / PC-agent services

Missing: production PC AnkiBackend; Custom Study lifecycle; completed 21K/21M.
```

Evidence: AppContainer:430–574, 706–742; Resolver/Selector compatibility source; §2.4.
The legacy resolver retains selection logic; new context code uses resolver2. Its retirement/
compatibility correctness is not certified here; no speculative rewrite was made.

PC AI/voice transport availability is not evidence of a registered PC Anki scheduler. The mock
server's `apply_simulated_anki_review()` increments an in-memory count and returns `"1 day"`
(`server/mock_pc_agent.py:157–173`), not a real Anki API. The mixed AnkiDroid-scheduler/PC-AI
runtime scenario was **not executed**.

## 4. Backend capability inventory

**All runtime readiness: NOT_MEASURED. All 21N backend tests: NOT_EXECUTED.** AnkiDroid entries
are current adapter code/checked-in public-provider evidence, not fresh upstream/device verification.
PC remains NO ADAPTER, never fabricated support.

| Capability | AnkiDroid adapter at this HEAD | PC Anki | Tested in 21N | Source evidence |
|---|---|---|---|---|
| Library read | Deck listing implemented; counts nullable/advisory | No adapter | No | Compatibility policy:164–168; 21F binding |
| Card Browser | Unsupported/refused; no bounded public card-list contract claimed | No adapter | No | AnkiDroidBackend:126; policy:104–107; GATE 15B |
| Card Details | Exact-card public read, gated on note gateway | No adapter | No | AnkiDroidBackend:128–132; policy:169–173 |
| Study / Review | Public scheduler path implemented/wiring-gated | No adapter | No | AnkiBackend:149–175; policy:161–168 |
| Rating | Durable commit path; no native idempotency/transaction receipt claimed | No adapter; server only simulates | No | AnkiDroidBackend:155–177; GATE 11E at-most-once/fail-closed |
| Reviewer actions | Card bury/suspend implemented; flags/note/sibling scope unsupported/not modelled | No adapter | No | Policy:174–179; 21I scope table |
| Note editing | Fields/tags/card-deck; best-effort reread, not conditional atomic update | No adapter | No | Policy:180–187; GATE 17 contract |
| Note creation | Public insert/model listing; creation deck selection not claimed | No adapter | No | Policy:188–197; pinned coordinator; GATE 18 |
| Media | Creation-media storage implemented; general read/stream capability not enabled here | No adapter | No | Policy `storeMedia = true`:196; writer wiring gates |
| Custom Study | No lifecycle API/implementation; filtered-deck awareness is not Custom Study | No adapter | No | GATE 20/21L; lifecycle absence search |

Four distinct evidence layers remain separate:

1. **Public API:** checked-in contract/pin evidence, not re-probed in 21N.
2. **Adapter:** above code inventory, narrower than possible upstream support.
3. **Readiness:** not observed; no application/backend instance created in this run.
4. **Verification:** no Kotlin/JVM, Android, real AnkiDroid or real PC test executed.

No universal exactly-once claim is made. A local transaction ID does not establish backend-native
deduplication. Unsupported browser/flags are honest exclusions; missing 21K/21L/21M are not exclusions.

## 5. Operation routing and ownership inventory

These rows identify source seams, not certified end-to-end execution. AUTO may resolve only genuinely
new unbound contexts. Existing exact assurance labels remain `AUTHORITATIVE_MATCH`,
`SESSION_CONTINUITY_VERIFIED`, `IDENTITY_UNVERIFIABLE` (`NOMINAL`/`DEGRADED`) and
`AUTHORITATIVE_MISMATCH`. No generic Boolean continuity substitute was introduced.

| Operation | Selected/bound when | Original owner / collection | Execution backend | Recovery / durability | AUTO after binding? |
|---|---|---|---|---|---|
| Library read | New context resolution/validation | Read context backend/key/generation | Binding through read guards | Exact context/ref continuation; new process generation; no mutation ledger | No |
| Card Details | Originating qualified card/context route | CardRef and immutable read context | Exact reference owner | Same qualified reference, never preference reinterpretation | No |
| StudySession | Pre-start resolution/admission; successful start persists owner before subsequent progression | StudySessionBackendLock / AnkiSessionContext | Executor exact owner lookup | Lock store/API exist; production restoration caller not found | No |
| Rating | Bound turn; durable GATE 11 prepare/submission | ReviewCommitId backend/session/turn and collection evidence | Original record owner | Original backend; durable COMMITTED barrier; crash tests not rerun | No |
| ReviewerAction | Bound turn/action admission | Action record backend/collection | Exact owner coordinator | Original backend; OP-M3 exception unresolved | No |
| NoteMutation | Editor base/save admission | Record backend/card/note/optional collectionRef | Registry-resolved original owner | Same record owner; restart rules; pending 21J exception | No |
| NoteCreation | Existing route binds **exact AnkiDroid only** | Draft/record backend/collection key | Pinned writer; foreign draft refused | Existing metadata/owner; no 21K registry-routed multi-backend recovery | No; 21K absent |
| Media operation | Creation workflow/per-step boundary | Enclosing creation owner/collection | Same pinned writer | Step metadata/orphan disclosure; no atomicity/cleanup proof | No |
| Custom Study | **Missing** | No filtered resource owner/ref | No implementation | No lifecycle ledger | BLOCKED, not N/A |

AnkiMediaRef's URI/stream/URL variants have no intrinsic backend/collection fields (`AnkiModels.kt:37–47`).
The contract allows a strong enclosing context; this shape alone is **not proof of cross-backend media
loading**. Enclosing-context/media verification remains outstanding; no new abstraction was invented.

## 6. Changes made by this run

```text
FILE: docs/GATE_21N_FINAL_INTEGRATION_REPORT.md
ACTION: CREATE
DEFECT / INTEGRATION NEED: mandatory prerequisite STOP and source-grounded discrepancy report
OWNING GATE: 21N-00
LOCKED CONTRACT: 21B identity/ownership contracts and the 21N hard prerequisite rule
CHANGE: actual locks/conflicts/source inventory; unexecuted verification/counters; lock denial
REGRESSION TEST: not applicable to documentation; all runtime verification remains BLOCKED
RESULT: report produced; no implementation authorization implied
```

Production changes: **0**. Test changes: **0**. Gradle/schema changes: **0**. Prior gate artifacts were
not rewritten. No missing gate was invented; no AUTO policy/status was changed; no persisted user
transaction data was accessed, deleted or reassigned.

## 7. Architecture audit matrix

The independent post-integration adversarial audit was **not authorized/performed**. Known source
contradictions/completeness failures can be classified now; other rows are BLOCKED, not inherited PASS.
**Executed Kotlin/Android test evidence for every audit row: NONE.**

| Audit ID | Result | Source evidence / finding | Remediation / residual risk |
|---|---|---|---|
| AUDIT-21N-01 | FAIL | Locked OP-M3 matrix disagrees with family exceptions (E04). | Contract reconciliation, not an implementation-only fix. |
| AUDIT-21N-02 | BLOCKED | Identity foundation present (§2.4); enclosing media context unverified. | Current collision/legacy integration not run. |
| AUDIT-21N-03 | BLOCKED | Configured IDs exist; production PC allocation path absent (E07). | Instance/reconfiguration evidence missing. |
| AUDIT-21N-04 | BLOCKED | One registered-backend inventory found (E07). | Health/race behavior not executed. |
| AUDIT-21N-05 | BLOCKED | Resolver2 and legacy status resolver remain (§3). | Selection-authority integration not re-verified. |
| AUDIT-21N-06 | BLOCKED | Explicit preference model exists (§2.4). | No current no-fallback/removal/two-PC execution. |
| AUDIT-21N-07 | BLOCKED | Read guards/generations present (§2.4). | No current cache/cursor/stale-response tests. |
| AUDIT-21N-08 | BLOCKED | Immutable lock/exact owner routes present (§5). | No start/loss/race/restore execution. |
| AUDIT-21N-09 | BLOCKED | Backend scheduler boundary present (§4). | No scheduler-authority runtime scenario. |
| AUDIT-21N-10 | BLOCKED | GATE 11 ledger/coordinator present (§5/§10). | No current barrier/crash execution. |
| AUDIT-21N-11 | BLOCKED | GATE 13 coordinator/ledger present (§5). | No current mutual-exclusion/scope execution. |
| AUDIT-21N-12 | BLOCKED | Registry-routed note coordinator present (§2.4). | 21J not locked; exception unresolved. |
| AUDIT-21N-13 | BLOCKED | Existing GATE 18 writer pinned to AnkiDroid (E05). | 21K missing; multi-backend proof unavailable. |
| AUDIT-21N-14 | BLOCKED | GATE 20 lifecycle owner absent (E06). | Essential implementation missing, not N/A. |
| AUDIT-21N-15 | BLOCKED | Some families have exact owner routes (§5). | All-family routing/recovery proof unavailable. |
| AUDIT-21N-16 | FAIL | Collection-assurance OP-M3 exception contradicts §3.3 (E04). | Approved 21B/21C/21I/21J reconciliation required. |
| AUDIT-21N-17 | BLOCKED | Ambiguity machinery present (§10). | All-family no-blind-replay test not run. |
| AUDIT-21N-18 | BLOCKED | DataStore/JSON stores present (E10). | No crash/migration/actual process test. |
| AUDIT-21N-19 | BLOCKED | Restore API exists; production caller not found (E09). | End-to-end restoration boundary untraceable. |
| AUDIT-21N-20 | BLOCKED | Deterministic gate-specific test sources exist (E13). | 21N race/chaos runner not executed. |
| AUDIT-21N-21 | BLOCKED | Adapter capability gates inspected (E11). | Current runtime/API verification absent. |
| AUDIT-21N-22 | BLOCKED | Baseline UI present; 21M absent (E08). | Concurrent ownership/readiness presentation unverified. |
| AUDIT-21N-23 | BLOCKED | PC transport/mock is not production PC Anki (§3). | Mixed PC AI/AnkiDroid scheduler scenario unexecuted. |
| AUDIT-21N-24 | BLOCKED | No production change in this run (§6). | Existing security/privacy regression not completed. |
| AUDIT-21N-25 | BLOCKED | Strict codecs/optional collection field visible (E10). | Legacy/malformed owner cases not run. |
| AUDIT-21N-26 | BLOCKED | One Registry found; compatibility surfaces remain (§3). | Full duplicate infrastructure/lifecycle audit blocked. |
| AUDIT-21N-27 | BLOCKED | Creation restart discloses orphan media (§10). | Cleanup/crash scenarios unexecuted; lifecycle absent. |
| AUDIT-21N-28 | BLOCKED | Lazy application composition inspected (§3). | No observer/polling/endurance measurements. |
| AUDIT-21N-29 | BLOCKED | This change is documentation-only (§6). | Full independent scope audit not performed; no Gate 22 work here. |
| AUDIT-21N-30 | FAIL | 21J/K/L/M prerequisite/completeness unmet (§2). | Final parent lock prohibited. |

**Totals:** 0 PASS, 3 source-grounded FAIL, 27 BLOCKED. These are not fabricated test outcomes.

## 8. Authoritative verification status matrix

The user's PART III remains the single detailed verification specification. This table records
**execution status only**, without duplicating its procedures. **P0** = hard prerequisite STOP (§1/§2).
No historical test result/source-scanning command is counted as an executed VER-21N pass.
No missing mandatory feature is classified N/A.

| Verification | Result | Executed evidence / limitation |
|---|---|---|
| VER-21N-01 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-02 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-03 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-04 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-05 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-06 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-07 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-08 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-09 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-10 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-11 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-12 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-13 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-14 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-15 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-16 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-17 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-18 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-19 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-20 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-21 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-22 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-23 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-24 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-25 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-26 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-27 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-28 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-29 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-30 | BLOCKED | NOT_EXECUTED: P0; 21I/21J collection contract conflict unresolved (E04). |
| VER-21N-31 | BLOCKED | NOT_EXECUTED: P0; 21I/21J collection contract conflict unresolved (E04). |
| VER-21N-32 | BLOCKED | NOT_EXECUTED: P0; 21I/21J collection contract conflict unresolved (E04). |
| VER-21N-33 | BLOCKED | NOT_EXECUTED: P0; 21I/21J collection contract conflict unresolved (E04). |
| VER-21N-34 | BLOCKED | NOT_EXECUTED: P0; 21I/21J collection contract conflict unresolved (E04). |
| VER-21N-35 | BLOCKED | NOT_EXECUTED: P0; 21I/21J collection contract conflict unresolved (E04). |
| VER-21N-36 | BLOCKED | NOT_EXECUTED: P0; 21I/21J collection contract conflict unresolved (E04). |
| VER-21N-37 | BLOCKED | NOT_EXECUTED: P0; existing local creation is not locked 21K (E05). |
| VER-21N-38 | BLOCKED | NOT_EXECUTED: P0; existing local creation is not locked 21K (E05). |
| VER-21N-39 | BLOCKED | NOT_EXECUTED: P0; existing local creation is not locked 21K (E05). |
| VER-21N-40 | BLOCKED | NOT_EXECUTED: P0; existing local creation is not locked 21K (E05). |
| VER-21N-41 | BLOCKED | NOT_EXECUTED: P0; GATE 20/21L implementation absent (E06), not N/A. |
| VER-21N-42 | BLOCKED | NOT_EXECUTED: P0; GATE 20/21L implementation absent (E06), not N/A. |
| VER-21N-43 | BLOCKED | NOT_EXECUTED: P0; GATE 20/21L implementation absent (E06), not N/A. |
| VER-21N-44 | BLOCKED | NOT_EXECUTED: P0; GATE 20/21L implementation absent (E06), not N/A. |
| VER-21N-45 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-46 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-47 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-48 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-49 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-50 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-51 | BLOCKED | NOT_EXECUTED: P0; no locked 21M or UI/device/RTL execution (E08). |
| VER-21N-52 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-53 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-54 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-55 | BLOCKED | NOT_EXECUTED: P0; source/historical suites are not current execution evidence. |
| VER-21N-56 | BLOCKED | NOT_EXECUTED: P0; no locked 21M or UI/device/RTL execution (E08). |
| VER-21N-57 | BLOCKED | NOT_EXECUTED: P0; no 21N runner added/run; 0 events; counters NOT_MEASURED. |
| VER-21N-58 | BLOCKED | NOT_EXECUTED: P0; no all-family injection or actual Android process termination. |
| VER-21N-59 | BLOCKED | NOT_EXECUTED: P0; mixed-context scenario not instantiated. |
| VER-21N-60 | BLOCKED | NOT_EXECUTED: P0; no device/disposable collection setup; production PC adapter absent. |
| VER-21N-61 | BLOCKED | NOT_EXECUTED: P0; task discovery blocked before Gradle startup; no harness rerun. |

**Totals:** 0 PASS, 0 executed FAIL, 61 BLOCKED, 0 N/A.

## 9. Chaos testing

```text
Test class:                   NONE created/executed for 21N
Command:                      NOT_EXECUTED (prerequisite STOP)
Executed controlled events:   0 (10,000-event requirement NOT MET)
Seeds:                        NONE executed
Backend instances:            NONE instantiated
Injected failure types:       NONE in this run
Duration:                     NOT_APPLICABLE (runner did not execute)
Result:                       BLOCKED
Failure event trace:           NOT_APPLICABLE; source discrepancies in §14
Process-death simulations:    NOT_EXECUTED
Real process-death tests:     NOT_EXECUTED
Total safety violations:      NOT_MEASURED (not zero)
Reproduced runtime defects:   NONE claimed
Fixed production defects:     0 (implementation unauthorized)
Remaining blockers:           §14 register
```

Existing 21H/21I endurance suites and historical 21L harness results are not substituted for
VER-21N-57/58/59. Simulated store reconstruction is not Android process-death testing. No event
count was lowered or test failure suppressed: the runner was not admitted after the STOP.

## 10. Persistence and recovery inventory

This is a **static inventory**, not executed crash verification. Every required before/during/after
external-call, lost-response, durable-result-write and restoration fault boundary is **NOT_INJECTED**
in this run. Actual Android restoration is unverified for all families. A hypothetical crash location
cannot prove a historical backend effect without real durable evidence.

| Family | Durable initial/submission truth visible in source | Outcome/restoration rule visible in source | Ownership / limitation |
|---|---|---|---|
| ReviewCommit | Existing versioned ledger; ID embeds backend/session/turn; durable claim before dispatch | Existing recovery machinery preserves uncertain submission; COMMITTED is progression barrier | Owner routing exists; lost-response/result-write/process cases not rerun; no universal native exactly-once |
| ReviewerAction | Separate ledger and backend/collection-qualified record; coordinator owns ordering | Existing GATE 13 five-state family independent of rating | Exact owner route; keyless OP-M3 exception contradicts generic matrix |
| NoteMutation | Version-1 ledger; explicit backend/ref tuple, optional collectionRef, operation index/attempts | SUBMITTING → AMBIGUOUS; PREPARED/RETRY_ALLOWED → CONFLICT because payload was process-local | Original owner retained in source design; 21J exception/downgrade unresolved |
| NoteCreation | Version-1 ledger; backend/collection, media steps/lastEnteredStep; separate media/note boundaries | CREATING_NOTE → AMBIGUOUS; PREPARED/STORING_MEDIA/RETRY_ALLOWED → ABANDONED; media may already exist | Pinned local writer; metadata is not arbitrary durable replay payload; no completed 21K |
| Media steps | Enclosed creation record/step index/stored name/entered boundary | Unknown store outcome/orphan media possible; no automatic cleanup asserted | No atomic media+note proof; qualified multi-backend recovery not verified |
| Filtered lifecycle | **No record/ledger/filtered-session identity/API found** | No lifecycle restoration implementation | GATE 20/21L BLOCKED; truth not invented |

Actual store files under M: `data/anki/DataStoreReviewCommitStore.kt`,
`DataStoreReviewerActionStore.kt`, `DataStoreNoteMutationStore.kt`, `DataStoreNoteCreationStore.kt`,
and `data/preferences/StudySessionLockDataStore.kt`. Startup loads rating/action ledger health
(`AppContainer.kt:513–516`), not a proof of active-session restoration or all-family recovery.

**Legacy codec limit:** NoteMutationRecord.collectionRef is optional/default-null, but codec VERSION
remains 1, encodeDefaults is true and ignoreUnknownKeys is false (`NoteMutationLedger.kt:66–87`).
21J §5/§8 records that newly written snapshots carry a field the older strict reader cannot decode.
That older reader fails closed with snapshot_corrupt: **unavailability**, not evidence of deletion or
owner reassignment. No downgrade runtime test/migration was performed. Supported downgrade policy
needs an owner decision; speculative schema normalization is not authorized.

Malformed/legacy owner cases and valid-record isolation from corrupt records were not rerun. No
missing owner was guessed from preference/kind/endpoint/deck/numeric ID; no ledger was cleared,
forced to PREPARED, or destructively migrated by this run.

**Collection limit:** AnkiDroid leaves currentCollectionIdentity unknown (AnkiBackend default).
The locked contract permits specific **nominal** operations; it does not establish unknown-collection
equality, blind-replay permission, or generic OP-M3 permission. Reconnect is not collection proof.
Do not invent a discriminator or a one-size-fits-all connectivity Boolean to resolve the conflict.

**Application restore limit:** Persisted lock/store and executor restoreSession() policy exist,
but the complete production call-site search found no caller outside its declaration. End-to-end
active-session restoration and barrier-respecting resume cannot be certified from an API and unit
callers alone (§14 E09).

## 11. Cross-backend safety counters

No scenario or measuring runner executed. **Every counter is NOT_MEASURED, not zero.** No personal
Anki collection was accessed.

| Counter | Measured value |
|---|---|
| Wrong-backend scheduler calls | NOT_MEASURED |
| Wrong-backend rating calls | NOT_MEASURED |
| Wrong-backend reviewer action calls | NOT_MEASURED |
| Wrong-backend note mutation calls | NOT_MEASURED |
| Wrong-backend note creation calls | NOT_MEASURED |
| Wrong-backend media calls | NOT_MEASURED |
| Wrong-backend filtered lifecycle calls | NOT_MEASURED |
| Mid-session backend switches | NOT_MEASURED |
| Cross-backend resource reinterpretations | NOT_MEASURED |
| Stale context contamination / response acceptance | NOT_MEASURED |
| Blind AMBIGUOUS replays | NOT_MEASURED |
| Unjustified RETRY_ALLOWED transitions | NOT_MEASURED |
| Next-card-before-commit violations | NOT_MEASURED |
| Lost unresolved durable records | NOT_MEASURED |
| Unauthorized mutations from diagnostics | NOT_MEASURED |
| Unauthorized backend changes | NOT_MEASURED |

## 12. Build and execution evidence

Project: `:app` only. AGP 8.4.2, Kotlin 1.9.24, Java target 17, SDK 34/minimum 26, Gradle wrapper 8.7.
Root clean is explicitly registered. App unit/lint/APK/instrumentation configuration exists, but actual
available tasks **could not be enumerated**, because the wrapper never started Gradle.

java/javac/gradle/kotlinc/adb/sdkmanager were absent from PATH. Standard SDK paths inspected were absent;
no usable Android/device setup was established. A harness bootstrap was not undertaken after STOP
and would not discharge Android or real-backend verification.

| Command/check | Executed | Exit code | Result | Artifact/log or limitation |
|---|---|---|---|---|
| Git status/branch/HEAD/log baseline | Yes | 0 | PASS, identification only | §1/§2; initial CLEAN; fixed branch retained |
| `git fetch --unshallow --no-tags origin master` | Yes | 0 | PASS, history only | `master -> FETCH_HEAD`; source/branch unchanged |
| `git merge-base --is-ancestor <report-commit> HEAD` for §2.3 | Yes | 0 each | PASS, ancestry only | Actual commits in §2.3; no runtime guarantee |
| Selected Git revision diffs | Yes | 0 | PASS, inventory only | §2.3 actual deltas |
| Tracked-file/source/report inventories | Yes | 0 for completed groups | COMPLETE, static only | §14.2; expected no-match searches not failed tests |
| Exploratory read of candidate `core/anki/AnkiMedia.kt` | Yes | 2 | Corrected lookup | File absent; actual `AnkiModels.kt:37–47` subsequently found/read; no test affected |
| `java -version` | Yes | 127 | BLOCKED | `java: command not found` |
| `./gradlew tasks --all` | **Yes** | **1** | **BLOCKED** | Missing Java output below; no compilation/dependency download began |
| `./gradlew --stop` | No | — | BLOCKED / NOT_EXECUTED | Prerequisite STOP; no Gradle JVM started |
| `./gradlew clean` | No | — | BLOCKED / NOT_EXECUTED | STOP; Java absent |
| `./gradlew testDebugUnitTest` | No | — | BLOCKED / NOT_EXECUTED | STOP; task discovery could not start |
| `./gradlew lint` | No | — | BLOCKED / NOT_EXECUTED | No Android static-analysis result |
| `./gradlew assembleDebug` | No | — | BLOCKED / NOT_EXECUTED | No APK produced |
| `./gradlew assembleRelease` | No | — | BLOCKED / NOT_EXECUTED | No APK; source config uses debug signing, not production signing |
| `./gradlew connectedDebugAndroidTest` | No | — | BLOCKED / NOT_EXECUTED | No usable device/toolchain/test collection setup |
| JVM harness build/run | No | — | BLOCKED / NOT_EXECUTED | Historical compiler/shims not current 21N execution |
| VER-21N-57/58/59 runner | No | — | BLOCKED / NOT_EXECUTED | No runner added/run after STOP |
| Real AnkiDroid/PC integration | No | — | BLOCKED / NOT_EXECUTED | No API/version/collection/readiness/result/cleanup measurement; PC adapter absent |
| `tools/check-capability-pins.sh` | No | — | NOT_EXECUTED | Prior 21L upstream source evidence is historical |
| Initial documentation-output Python helper | Yes | 1 | Corrected tooling error | Unterminated string; no files changed by failed attempt; report then written in bounded sections |

Actual task-discovery output (exit 1; approximately 0.279 seconds):

```text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation.
```

This occurred **before** distribution/dependency download. Historical TLS/CI limitations are not
reported as newly observed failures. No current GitHub CI run was queried/certified. Even successful
future release compilation would not prove production signing or real collection safety.

## 13. Invariant status matrix

BLOCKED means not established by current executed integration evidence, not a synthetic behavioral
failure. INV-21N-38 is an administrative PASS only: lock withheld rather than evidence fabricated.
It certifies no other system invariant.

| Invariant | Result | Current evidence / limitation |
|---|---|---|
| INV-21N-01 | BLOCKED | Stability/reconfiguration execution missing; production PC producer absent. |
| INV-21N-02 | BLOCKED | Kind/instance types present; full instance integration not run. |
| INV-21N-03 | BLOCKED | Unknown collection explicit; real identity proof unavailable. |
| INV-21N-04 | BLOCKED | Qualified refs present; collision cases unexecuted. |
| INV-21N-05 | BLOCKED | Registry seam present; observed-state integration unexecuted. |
| INV-21N-06 | BLOCKED | Resolver2 exists; binding/race integration unexecuted. |
| INV-21N-07 | BLOCKED | Explicit model exists; current no-fallback tests not run. |
| INV-21N-08 | BLOCKED | Immutable lock present; session/race/restore cases not run. |
| INV-21N-09 | BLOCKED | Backend scheduler boundary present; authority scenario not run. |
| INV-21N-10 | BLOCKED | No scheduler introduced here; full runtime audit not performed. |
| INV-21N-11 | BLOCKED | Read guards present; mixed/stale result tests unexecuted. |
| INV-21N-12 | BLOCKED | Paging/context seams present; cache/cursor cases unexecuted. |
| INV-21N-13 | BLOCKED | All-family original-owner proof missing; 21K/21L absent. |
| INV-21N-14 | BLOCKED | Some recovery policies present; all-family execution missing. |
| INV-21N-15 | BLOCKED | No replay exercised; counters NOT_MEASURED. |
| INV-21N-16 | BLOCKED | Ambiguity machinery present; all-family no-blind-retry not tested. |
| INV-21N-17 | BLOCKED | Retry proof/persistence failure cases unexecuted. |
| INV-21N-18 | BLOCKED | No AnkiDroid native receipt/idempotency claimed; API proof incomplete. |
| INV-21N-19 | BLOCKED | GATE 11 barrier present; current crash/progression tests not run. |
| INV-21N-20 | BLOCKED | Action scope/mutual-exclusion tests not run. |
| INV-21N-21 | BLOCKED | Note route present; 21J not locked and cases not run. |
| INV-21N-22 | BLOCKED | Existing local creation/media only; 21K incomplete. |
| INV-21N-23 | BLOCKED | Partial-result/crash classifications not injected. |
| INV-21N-24 | BLOCKED | Custom Study owner absent; GATE 20/21L blocked. |
| INV-21N-25 | BLOCKED | Filtered end/cleanup distinction not implemented. |
| INV-21N-26 | BLOCKED | Stores present; real process death/restoration not executed. |
| INV-21N-27 | BLOCKED | Legacy/malformed historical-owner cases not run. |
| INV-21N-28 | BLOCKED | Mismatch guards present; all-family recovery tests not run. |
| INV-21N-29 | BLOCKED | Assurance labels exist; OP-M3 contract conflict unresolved. |
| INV-21N-30 | BLOCKED | Generation/correlation seams exist; old callback cases not run. |
| INV-21N-31 | BLOCKED | Baseline UI concepts exist; 21M concurrent-display proof missing. |
| INV-21N-32 | BLOCKED | No diagnostics mutation-count measuring run. |
| INV-21N-33 | BLOCKED | PC service/scheduler mixed scenario unexecuted. |
| INV-21N-34 | BLOCKED | No new private access introduced here; full security regression unexecuted. |
| INV-21N-35 | BLOCKED | No secrets inspected/persisted here; diagnostics privacy tests unexecuted. |
| INV-21N-36 | BLOCKED | No new sync/migration here; full architecture audit blocked. |
| INV-21N-37 | BLOCKED | No contract changed here; prior family exceptions require approval. |
| INV-21N-38 | PASS (decision discipline only) | Final lock NOT_AUTHORIZED; no unexecuted verification marked PASS. |

## 14. Defect/discrepancy and remediation register

No runtime failure is invented. “Reproduction” means repeatable source/contract inspection where
specified. Production fixes are withheld because prerequisites forbid implementation. A contract
change requires owning-gate reconciliation, not weaker tests or a silently edited 21B matrix.

### B-21N-01 — Conflicting ambiguous-reconciliation contracts

```text
SEVERITY: HIGH (final architecture safety/contract blocker)
REPRODUCTION: compare locked §3.3 with current generic and family routing branches
ROOT CAUSE: 21I/21J permit keyless OP-M3 outside unchanged 21B/21C matrix
OWNING COMPONENT/GATE: 21B/21C; ReviewerActionRoutingPolicy (21I); NoteMutationRoutingPolicy (21J)
AFFECTED INVARIANT VERIFICATION: collection/recovery/contract integrity, especially 29/37
FIX: NONE; approved contract reconciliation required before implementation authorization
REGRESSION TEST: NOT_EXECUTED; family tests do not approve changes to a locked contract
CURRENT STATUS: OPEN / BLOCKING
```

| Assurance for OP-M3 | Locked §3.3 / generic continuation | Reviewer-action policy | Note-mutation policy |
|---|---|---|---|
| AUTHORITATIVE_MATCH | Requires authoritative reconciliation capability | Exact owner/family evidence rules | Route to owner; coordinator/backend evidence decides |
| SESSION_CONTINUITY_VERIFIED | **Blocked** | **Routed** | **Routed** |
| IDENTITY_UNVERIFIABLE / NOMINAL | **Blocked** | **Routed** | **Routed** |
| DEGRADED / AUTHORITATIVE_MISMATCH | Blocked | Blocked | Blocked |

Concrete source references:

- [Identity contract](BACKEND_IDENTITY_CONTRACT.md):257–266.
- [Generic continuation](../app/src/main/java/com/studyagent/client/core/anki/AnkiIdentityContinuation.kt):165–168, 210–211.
- [Reviewer-action policy](../app/src/main/java/com/studyagent/client/core/anki/ReviewerActionRoutingPolicy.kt):224–279.
- [Note routing policy](../app/src/main/java/com/studyagent/client/core/anki/edit/NoteMutationRoutingPolicy.kt):69–77, 122–135.
- [21J report](GATE_21J_NOTE_EDIT_BINDING.md) §4.4/§10 explicitly calls the exception a deviation pending audit.
- [21I report](GATE_21I_REVIEWER_ACTION_ROUTING.md):83 permits it; no subsequent amendment of the
  normative contract files was found in Git history.

This proves a contract conflict, **not an observed wrong-backend mutation or blind retry**.
Instance-bound state cannot silently become authoritative collection matching.

### B-21N-02 — Missing 21K lock and multi-backend creation/media integration

- **Severity:** HIGH, mandatory integration incompleteness.
- **Reproduction:** no 21K lock/report/test artifact found; creation coordinator constructor takes one
  backend (`NoteCreationCoordinator.kt:161–162`); AppContainer:497–502 passes ankiDroidBackend;
  AppNavHost:121, 381 seeds/admit only exact AnkiDroid.
- **Root cause/owner:** local GATE 18 composition has not been integrated/locked as 21K.
- **Affected verification:** original destination/media coherence and all-family recovery (13, 21–23, 26).
- **Fix/regression:** NONE/NOT_EXECUTED. 21K is a separate prerequisite, not new feature development
  permitted under blocked 21N. Existing foreign-draft refusal is not called silent fallback.
- **Status:** OPEN / BLOCKING.

### B-21N-03 — GATE 20/21L essential lifecycle absent

- **Severity:** HIGH, essential missing implementation.
- **Reproduction:** GATE 20 lock/status BLOCKED; source/test searches find no Custom Study coordinator,
  request/config/record/store, filtered-session ref or lifecycle create/rebuild/empty operation.
  StudyMode.CUSTOM_SESSION is a wire enum, not scheduler/lifecycle ownership.
- **Root cause/owner:** GATE 20 not landed; locked 21B:1605–1613 forbids 21L from inventing it.
- **Affected verification:** Custom Study invariants 24–26 and parent completeness; cases 41–44 are not N/A.
- **Fix/regression:** NONE/NOT_EXECUTED. GATE 20 needs separate authorization/closure, then 21L.
- **Status:** OPEN / BLOCKING. Current 21L report expressly prohibits advancing to 21M.

### B-21N-04 — 21M presentation contract/lock absent

- **Severity:** HIGH, required UI/diagnostics integration/evidence missing.
- **Reproduction:** no 21M artifact; current UI options use AnkiBackendMode and
  SettingsViewModel.selectAnkiBackend(mode) calls store.setMode(mode), not an instance-pin picker.
- **Root cause/owner:** baseline parent-gate UI is not a completed GATE 21M.
- **Affected verification:** independent preference/ownership, truthful readiness/capabilities, parent completeness.
- **Fix/regression:** NONE/NOT_EXECUTED; complete 21M only after prerequisite handoff is authorized.
- **Status:** OPEN / BLOCKING. No assertion that every existing label is wrong: concurrent ownership,
  ambiguous-recovery, accessibility and RTL scenarios were not executed.

### B-21N-05 — Production PC Anki and mandatory real-backend evidence absent

- **Severity:** HIGH for the requested AnkiDroid + configured-PC production lock.
- **Reproduction:** AppContainer:530–534 registers only ankiDroidBackend; production AnkiBackend
  implementation search finds AnkiDroidBackend. PC availability structure/ID is not an adapter;
  mock server review effects are explicitly simulated.
- **Root cause/owner:** inherited PC integration gap; allocation/protocol/API version/capabilities,
  collection assurance and real operations lack a producer in this checkout.
- **Affected verification:** dual-backend identity/routing/scheduler and mandatory VER-21N-60.
- **Fix/regression:** NONE/NOT_EXECUTED. New adapter/protocol development is outside 21N; a separately
  authorized integration and disposable-collection verification are required before final lock.
- **Status:** OPEN / BLOCKING. Earlier contracts honestly record PC as absent; not proof of final support.

### B-21N-06 — Current build/regression/device/chaos evidence unavailable

- **Severity:** HIGH verification blocker, not a demonstrated application compilation defect.
- **Reproduction:** tasks discovery exits 1, missing Java, before Gradle starts (§12); no usable
  Android/device/disposable-backend setup established. Hard prerequisite STOP bars integration.
- **Root cause/owner:** environment plus unmet gates; owning work is 21N verification/CI/device setup.
- **Affected verification:** runtime guarantees cannot be certified; lock discipline requires denial.
- **Fix/regression:** NONE/NOT_EXECUTED. Supply authorized toolchain/CI/device/collection evidence after
  prerequisites, then run actual tasks; a shim harness does not discharge Android execution.
- **Status:** OPEN / BLOCKING. Installing Java alone cannot close B-21N-01…05.

### B-21N-07 — Production active-session restoration boundary untraceable

- **Severity:** HIGH evidence/integration gap for mandatory process-restoration proof.
- **Reproduction:** complete production call-site search finds executor/policy definitions/delegation,
  but no application caller of restoreSession(); startup loads rating/action ledger health only.
- **Root cause/owner:** 21G persists/defines restore; 21H adds OP-P/barrier policy, without a found
  end-to-end application restoration entry point.
- **Affected verification:** immutable ownership/process restoration/durable progression; VER-21N-22.
- **Fix/regression:** NONE/NOT_EXECUTED. Confirm product restoration policy and authorized wiring,
  then execute Android termination/relaunch tests; policy output is not an active restored session.
- **Status:** OPEN / BLOCKING for final evidence. No simulated crash counted as real process death.

### R-21N-08 — Note ledger downgrade compatibility limit

- **Severity:** MEDIUM availability/compatibility, not asserted data deletion.
- **Reproduction:** new optional field plus encode-defaults and unchanged strict version-1 codec;
  pre-21J reader lacks collectionRef. See §10 and 21J §5/§8.
- **Root cause/owner:** no versioned/tolerant downgrade reader; 21J/codec policy.
- **Affected verification:** legacy persistence/supported-version policy.
- **Fix/regression:** NONE/NOT_EXECUTED; determine supported downgrade contract before changing schemas.
- **Status:** OPEN / source-grounded; no downgrade runtime test performed.

### 14.2 Inspectable evidence ledger

Available test sources and execution are deliberately separate. Test names without results are
not safety passes. Path prefixes M/T are defined in §2.4; all source paths below exist at HEAD.

| Evidence ID | Actual inspected source/command | Claim supported | Limit |
|---|---|---|---|
| E01 | Git baseline commands (§12) | Exact HEAD/branch; initial clean tree; session branch retained | No runtime guarantee |
| E02 | Actual lock/feature reports and contract excerpts (§2.1/§2.2) | Scoped historical locks; missing/pending gates; authoritative 21L BLOCKED | Historical results not rerun |
| E03 | Full-history fetch, ancestry and selected diffs (§2.3) | Report ancestry; unchanged normative contracts; post-lock code changes/no source closure after 21J | Selected paths, not executed tests |
| E04 | [Identity contract](BACKEND_IDENTITY_CONTRACT.md) §3.3; [generic continuation](../app/src/main/java/com/studyagent/client/core/anki/AnkiIdentityContinuation.kt):165–168/210–211; [action policy](../app/src/main/java/com/studyagent/client/core/anki/ReviewerActionRoutingPolicy.kt):224–279; [note policy](../app/src/main/java/com/studyagent/client/core/anki/edit/NoteMutationRoutingPolicy.kt):122–135 | Concrete OP-M3 conflict | No mutation outcome claimed |
| E05 | [AppContainer](../app/src/main/java/com/studyagent/client/di/AppContainer.kt):497–502; [AppNavHost](../app/src/main/java/com/studyagent/client/ui/navigation/AppNavHost.kt):118–121/373–385; [creation coordinator](../app/src/main/java/com/studyagent/client/core/anki/create/NoteCreationCoordinator.kt) constructor/owner guard | Creation remains exact-AnkiDroid pinned; no completed 21K | Local refusal is not wrong-backend execution |
| E06 | [GATE 20](GATE_20_CUSTOM_STUDY_CONTRACT.md):3–5/526–565; [21L](GATE_21L_REPORT.md):3–7/625–643; [21B](GATE_21B_CONTRACT_LOCK.md):1605–1613; source/test absence search; [wire enum](../app/src/main/java/com/studyagent/client/core/models/StudyControlModels.kt):36–37 | Lifecycle implementation missing/prohibited | Upstream APIs not a client implementation |
| E07 | [AppContainer](../app/src/main/java/com/studyagent/client/di/AppContainer.kt):530–534; [AnkiDroidBackend](../app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidBackend.kt):103–155; implementation search; [mock server](../server/mock_pc_agent.py):157–173 | One production Anki backend; server effects simulated | PC AI/transport readiness unobserved |
| E08 | [SettingsViewModel](../app/src/main/java/com/studyagent/client/ui/screens/settings/SettingsViewModel.kt):82–84; [UI models](../app/src/main/java/com/studyagent/client/ui/components/anki/AnkiBackendUiModels.kt):27–40/122–136; artifact absence search | Kind-level selection; no completed 21M | No Compose/RTL/device tests |
| E09 | [Executor](../app/src/main/java/com/studyagent/client/core/study/AnkiStudyEffectExecutor.kt):550–594; production call-site search; [AppContainer](../app/src/main/java/com/studyagent/client/di/AppContainer.kt):513–516/570–574/729–742 | Persisted store/API exist; production restore caller not found | No termination/relaunch observation |
| E10 | [Note ledger](../app/src/main/java/com/studyagent/client/core/anki/edit/NoteMutationLedger.kt):66–101; [note record](../app/src/main/java/com/studyagent/client/core/anki/edit/NoteMutationRecord.kt):20–55; [creation ledger](../app/src/main/java/com/studyagent/client/core/anki/create/NoteCreationLedger.kt):68–103; [creation record](../app/src/main/java/com/studyagent/client/core/anki/create/NoteCreationRecord.kt); actual stores (§10) | Encoding/restart metadata and documented downgrade/orphan limits | No fault injection/migration test |
| E11 | [AnkiBackend](../app/src/main/java/com/studyagent/client/core/anki/AnkiBackend.kt):21–71/149–175; [compatibility policy](../app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt):157–205; backend wiring/semantics | Source capability/scheduler boundary and unknown collection default | Not fresh public API/device certification |
| E12 | [Gradle settings](../settings.gradle.kts), [app build](../app/build.gradle.kts), [wrapper](../gradle/wrapper/gradle-wrapper.properties); output (§12) | Actual versions/module; task discovery blocked before Gradle | No unit/lint/APK/instrumentation result |
| E13 | Tracked test inventory (254 unit-root files/12 instrumentation-root files, including helpers), feature/Gate21c–j suites; [harness README](../tools/jvm-harness/README.md) | Candidate test sources exist; no Gate21n-named file at baseline; harness deviations disclosed | No tests executed; file counts not case counts |

Repeatable source inventory commands (expected absence is not a failed runtime test):

```bash
git ls-files | grep -Ei 'gate[_ -]?21[km]'
rg -n 'CustomStudy(Coordinator|Request|Config|Record|Store)|AnkiFilteredSessionRef|Filtered(Session|Deck)Ref|RebuildFilteredDeck|EmptyFilteredDeck|AddOrUpdateFilteredDeck' app/src/main app/src/test app/src/androidTest server
rg -n 'restoreSession\(|recoveryPlan\(|actionRecoveryPlan\(' app/src/main
```

First two: no gate artifact/lifecycle implementation match. Third: definitions/delegation, no
production restoreSession() caller. This is an inspectable source boundary, not a test pass.

## 15. Residual risk register

High/critical unverified guarantees remain **blockers**, not minor residual risks deferred to GATE 22
in order to claim a final lock.

| Risk | Severity | Feature | Mitigation / owning work |
|---|---|---|---|
| Unapproved reconciliation exception | HIGH / BLOCKING | Actions/edit recovery | Reconcile 21B/21C/21I/21J; preserve ambiguity; no invented discriminator |
| Missing 21K/21L/21M and PC adapter | HIGH / BLOCKING | Creation/media/Custom Study/UI/PC | Separately complete owning prerequisites; no new architecture under 21N |
| Android/real-backend evidence absent | HIGH / BLOCKING | All production guarantees | Authorized CI/toolchain/device/disposable collections and actual measured results |
| Active-session restore untraced | HIGH / BLOCKING | Process death/progression | Confirm/wire authorized restore path and run real process tests |
| AnkiDroid collection discriminator unavailable | HIGH assurance limitation for affected recovery claims | Collection continuity/reconciliation | Preserve exact categories/operation-specific rules; no equality guessed from IDs/connectivity |
| Ambiguous rating may remain unresolved | Safe fail-closed behavior; operational impact unmeasured | ReviewCommit/UI | No native reconciliation/idempotency assumed; truthful future 21M/22 presentation |
| Separate media/note boundaries | Source-grounded partial-outcome limit; rate unmeasured | Creation/media | Retain step evidence/orphan disclosure; no unsafe cleanup; 21K verification |
| External/concurrent Anki edits and best-effort reread | Capability limitation; incidence unmeasured | Note editing/actions | No atomic/conditional-update claim; owning-gate conflict/ambiguity tests |
| Strict version-1 note downgrade | MEDIUM availability | Legacy persistence | Explicit supported-version/codec policy; no destructive reset (21J) |
| Health staleness/observer lifecycle | NOT_MEASURED; no final acceptance | Registry/UI/lifecycle | Execute race/health/lifecycle verification; transport ≠ Anki readiness |

## 16. Final parent gate decision and unblock requirements

The PASS/FAIL fields below are **final acceptance conditions**, not fabricated runtime outcomes.
FAIL means the required condition was not established or a mandatory prerequisite is absent;
actual unexecuted tests are BLOCKED in §8. Source conflict failures are distinguished in §7.

```text
GATE 21 — MULTI-BACKEND ANKI ARCHITECTURE

IDENTITY:                    FAIL (final evidence incomplete)
REGISTRY:                    FAIL (final evidence incomplete)
AUTO RESOLVER:               FAIL (final evidence incomplete)
READ CONTEXT ISOLATION:      FAIL (final evidence incomplete)
STUDYSESSION LOCK:           FAIL (final evidence incomplete)
REVIEWCOMMIT RECOVERY:       FAIL (final evidence incomplete)
REVIEWERACTION RECOVERY:     FAIL (contract conflict / verification incomplete)
NOTEMUTATION RECOVERY:       FAIL (21J not locked / contract conflict)
NOTECREATION / MEDIA:        FAIL (21K missing)
CUSTOM STUDY:                FAIL (GATE 20/21L blocked / implementation absent)
UI / DIAGNOSTICS:            FAIL (21M missing)
PROCESS-DEATH SAFETY:        FAIL (application boundary / verification incomplete)
CHAOS / CONCURRENCY:         FAIL (not executed)
PERSISTENCE COMPATIBILITY:   FAIL (required current verification incomplete)
SECURITY / PRIVACY:          FAIL (required current regression incomplete)
ARCHITECTURE AUDIT:          FAIL (3 source-grounded failures; remainder blocked)
VERIFICATION:               FAIL (61 BLOCKED; no executed pass)
BUILD:                      FAIL (task discovery blocked; builds not executed)

FINAL GATE 21:              BLOCKED
FINAL LOCK AUTHORIZATION:   NOT_AUTHORIZED
NEXT AUTHORIZED PARENT GATE: NONE
```

Required closure, **not automatic authorization to implement other gates**:

1. Explicitly reconcile locked OP-M3 policy with 21I/21J, and obtain a real 21J lock.
2. Complete/verify/lock 21K. Resolve/implement GATE 20 before 21L; complete/lock 21L and 21M.
   Missing lifecycle work may not be recreated under 21N.
3. Supply production PC Anki integration outside this final validation scope, including exact
   instance/collection/capability evidence; do not substitute the mock server.
4. Resolve the application's real session restoration boundary and its permitted product behavior.
5. Provide runnable toolchain/CI/device and authorized disposable Anki collections; rerun 21N's
   full regression, deterministic 10,000-event chaos/crash/mixed-context tests and real APIs.
6. Issue a final lock/handoff only after mandatory evidence passes. Do not reinterpret unknown
   ownership, force-commit, replay ambiguity, synchronize collections or relax contracts to get green.

**No successful GATE 22 handoff is issued.** Any later handoff must preserve immutable ownership,
original-backend recovery, no AUTO after binding, no blind ambiguity replay, Anki scheduler authority,
explicit collection uncertainty, no cross-backend resource equivalence, durable evidence,
capability-aware degradation and truthful readiness UI. No GATE 22 implementation was started.

## 17. Checkpoint STOP reports

### CHECKPOINT: 21N-00

```text
STATUS: PARTIAL (baseline/prerequisite reconciliation executed; complete integration traceability blocked)
ACTUAL FILES INSPECTED:
  Git status/history; root/app Gradle configuration; tracked source/test/document inventories;
  lock/feature documents listed in §2; actual source excerpts indexed in §14.2.
ACTUAL FILES MODIFIED:
  docs/GATE_21N_FINAL_INTEGRATION_REPORT.md only (documentation).
OWNING GATE: 21N-00, with prerequisite responsibilities in 21B/21I/21J/21K/21L/21M and GATE 20.
LOCKED CONTRACT REFERENCES:
  BACKEND_IDENTITY_CONTRACT.md §3.3; GATE_21B_CONTRACT_LOCK.md 21J/21K/21L/21M/21N handoffs;
  source/lock references in §2 and the 21N prompt's mandatory STOP rule.
FINDINGS:
  Source HEAD/branch identified, history reconciled; 21J/K/L/M lock requirements unmet;
  OP-M3 contract conflict; creation pinned; Custom Study/PC adapter absent;
  restoration boundary not traceable; Java unavailable.
INTEGRATION DEFECTS: §14 blocker register (source-grounded, not invented runtime failures).
TARGETED FIXES: NONE; implementation unauthorized.
LOCAL EVIDENCE: §2.3, §12 and E01–E13. No Kotlin/Android/backend test result generated.
UNRESOLVED RISKS: §14/§15; high blockers prohibit final lock.
READY FOR NEXT CHECKPOINT: NO
```

### CHECKPOINTS: 21N-01 through 21N-23

Each row is an individual blocked checkpoint record. The following fields apply to **every row**:

```text
STATUS: BLOCKED (not entered after mandatory prerequisite STOP)
ACTUAL FILES INSPECTED:
  None as an admitted execution of this checkpoint. Prerequisite-only samples remain credited to 21N-00/E01–E13.
ACTUAL FILES MODIFIED: NONE
INTEGRATION DEFECTS: §14 register; no checkpoint-specific runtime defect reproduced.
TARGETED FIXES: NONE
LOCAL EVIDENCE: prerequisite STOP (§1/§2), not execution of this checkpoint's verification.
UNRESOLVED RISKS: §15 and the row-specific blocker below.
READY FOR NEXT CHECKPOINT: NO
```

Owning-gate/contract/findings fields are supplied per checkpoint below. These are statuses, not a
duplicate of PART III's authoritative verification procedures.

| Checkpoint | Owning Gate | Locked contract reference | Findings / why not ready |
|---|---|---|---|
| 21N-01 — Dependency architecture | 21B / 21N | 21B source-of-truth/compatibility boundaries | Inventory exists (§3), independent final audit not admitted. |
| 21N-02 — Identity integration | 21C | Identity contract §§1–5 | Production PC instance path absent; collision/restoration tests not executed. |
| 21N-03 — Registry integration | 21D | 21D registry/state contract; 21B readiness ladder | Health/capability races not executed. |
| 21N-04 — Preference/resolver | 21E | Selection policy; 21E binding preconditions | Scoped artifacts only; current selection/bind races not executed. |
| 21N-05 — Read isolation | 21F | 21B read/correlation decisions; 21F lock | Current cache/paging/navigation/stale callbacks not tested. |
| 21N-06 — Study/scheduler | 21G | 21B session ownership; 21G lock | Current immutable-owner/PC-AI scenario not executed. |
| 21N-07 — Context independence | 21F/21G/21H | 21B source-of-truth/ownership matrix | Mixed-context runtime scenario not instantiated. |
| 21N-08 — ReviewCommit | 21H / GATE 11 | GATE 11E, 21H routing/barrier | Current crash/ambiguity/barrier verification unavailable. |
| 21N-09 — ReviewerAction | 21I / GATE 13 | GATE 13; identity §3.3; 21I exception | Contract conflict requires approval, not a final lock. |
| 21N-10 — Note editing | 21J / GATE 17 | GATE 17; identity §3.3 | 21J explicitly pending audit; contract conflict. |
| 21N-11 — Creation/media | 21K / GATE 18 | GATE 18; 21B creation coherence | 21K missing; only pinned local workflow. |
| 21N-12 — Custom Study | 21L / GATE 20 | 21B:1605–1613 prohibition | Essential lifecycle absent; GATE 20/21L blocked. |
| 21N-13 — Collection continuity | 21B/21C/families | Identity §3.3/§3.5 | OP-M3 contradictory; current operation matrix not executed. |
| 21N-14 — Process restoration | 21G/families | Session envelope/OP-P contracts | No real process-death tests; restore caller not found. |
| 21N-15 — Backend-loss chaos | 21D/21G/families | Failure matrix / original-owner rules | No chaos runner admitted. |
| 21N-16 — Concurrency | 21F–21L | Correlation, barriers and immutable ownership | Deterministic cross-component races not run. |
| 21N-17 — Mutation crash matrix | GATE 11/13/17/18/20 | Durable family contracts | All-family boundaries not injected; filtered ledger absent. |
| 21N-18 — Legacy migration | 21C/families | Identity historical-record contract | No current migration/downgrade/malformed isolation execution. |
| 21N-19 — Security/privacy | 21B / feature gates | 21B security/privacy decisions | No full existing-system regression; no production change introduced here. |
| 21N-20 — UI truthfulness | 21M | 21B diagnostics/ownership presentation | Missing 21M lock and UI runtime proof. |
| 21N-21 — Lifecycle/performance | 21D/21M/21N | Existing lifecycle limits | No observer/service/polling measurements. |
| 21N-22 — Targeted remediation | Defect owner / 21N | Hard STOP; locked-contract change restrictions | No production remediation authorized; no speculative rewrite. |
| 21N-23 — Final traceability | 21N | Mandatory source + executed evidence rule | Preliminary map (§2.4) exists, but complete evidence absent; lock denied. |

## 18. Documentation validation boundary

Report validation checks only Markdown link targets, identifier/table completeness, current HEAD/branch,
and documentation-only worktree scope. It is **not** a Kotlin/Android regression test, crash simulation,
capability probe or VER-21N pass. All runtime verification/counters above retain their blocked/unmeasured
classification regardless of documentation validation.

Executed documentation-only validator: `python3` inline validation, **exit 0**. Results:
30/30 unique audit rows; 61/61 verification rows (all BLOCKED); 38/38 invariant rows;
24/24 checkpoint records; 69 existing local Markdown link targets; balanced code fences;
HEAD/branch unchanged; exactly one new report and no tracked/staged source changes.
New-file `git diff --no-index --check /dev/null <report>` returned **1** for a differing new file,
with **no whitespace diagnostics**. That exit is not an Android build or test failure/pass.
