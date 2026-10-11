# GATE 21A — Backend Architecture & Contract Discovery

**Status:** discovery complete — no production code was changed in this Sub-Gate.
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/5cef8d58-study-agent-client`
**Base commit:** `28eec915cc198dd1a1b24f29dc2790fcd3938616` (merge of PR #62)
**Date:** 2026-10-11

**Companion deliverables**

| Document | Contents |
|---|---|
| `docs/GATE_21A_DISCOVERY.md` (this file) | A01–A10 findings, architecture audit, verification, exit criteria |
| `docs/BACKEND_IDENTITY_INVENTORY.md` | every identity type, its qualification and its classification |
| `docs/BACKEND_CAPABILITY_MATRIX.md` | availability states, capability flags, required-capability mapping, feature matrix |
| `docs/BACKEND_ROUTING_INVENTORY.md` | every selection / lookup / pinning site with file:line |

---

## 0. Executive summary

The repository is **not** greenfield for GATE 21. A prior GATE 21 pass is already merged
(PR #61 *"GATE 21 — immutable multi-backend routing and session ownership"*, PR #62 *"gate21: align the
report with the merged wiring"*, both merged into the base commit of this branch). It shipped a registry,
a resolver, a persisted preference, a status centre, a transaction-recovery planner, a session lock
tracker, a Settings selector, a Study lock indicator and four-role routing diagnostics, plus 56 GATE 21
tests and three report documents.

This Sub-Gate therefore re-baselines discovery against the code **as it exists now**, and reports what is
already there, what is missing relative to the 21A–21N decomposition, and what is unsafe to assume.

### The five facts that dominate every later Sub-Gate

1. **Only one real backend exists.** `AnkiDroidBackend` is the sole production implementation;
   `FakeAnkiBackend` is a test double. `grep -rn "PcAnkiBackend" app/src` returns nothing and
   `AnkiBackendId.PcAgent` is produced by **no** production code. The PC *agent* in this repository
   (`core/network/WebSocketAgentConnection.kt`) is an AI/voice transport that carries **no Anki traffic**
   (`grep -rin "anki" core/models/ProtocolMessages.kt` → nothing). The plan's distinction between a PC AI
   service and a PC Anki backend is therefore already true of the code, and the PC Anki backend does not
   exist.
2. **Collection identity is unavailable on the only implemented backend.** AnkiDroid refs are built with
   `collectionKey = null` (`AnkiDroidDeckMapper.kt:93`, `:114`), and `collectionMatchesBoundSession`
   returns `true` when the bound key is unknown (`AnkiDroidBackend.kt:1340`). An AnkiDroid profile switch
   mid-session is **undetectable**.
3. **Resource identity is already backend-qualified at construction** and cannot be built unqualified.
   Nothing needs to be invented for 21C; 21C is an audit plus two real gaps (`AnkiMediaRef` and
   `AnkiFilteredSessionRef` do not exist).
4. **Study runtime already routes by locked identity**, and this is test-enforced. The gaps are elsewhere:
   the *read* contexts (Library, Deck Details, Card Browser, Add-Note route seed) are hard-pinned to
   `container.ankiDroidBackend`, and the two note coordinators pin a single backend instance.
5. **Several operations the UI offers are unsatisfiable under capability-constrained routing** — most
   importantly the card browser (`AnkiCapabilities.cardBrowser` is `NONE` on AnkiDroid), plus
   `MEDIA_READ`, `FLAGS` and `DECK_COUNTS`. This is a product decision GATE 21B must make explicitly.

---

## PART I — Discovery

### A01 — Repository map

| Concern | Implementation | Location |
|---|---|---|
| Backend contract | `interface AnkiBackend` | `core/anki/AnkiBackend.kt` |
| AnkiDroid backend | `AnkiDroidBackend` | `data/anki/ankidroid/AnkiDroidBackend.kt` |
| PC backend | **does not exist** | — |
| Fake backend | `FakeAnkiBackend` (test only) | `app/src/test/.../anki/fake/FakeAnkiBackend.kt` |
| Registry | `AnkiBackendRegistry` | `core/anki/AnkiBackendSelector.kt:10` |
| Routing policy | `AnkiBackendResolver`, `BackendResolutionRequest/Result` | `core/anki/AnkiBackendResolver.kt` |
| Session-start adapter | `AnkiBackendSelector` | `core/anki/AnkiBackendSelector.kt:40` |
| Kind / locality / descriptor | `AnkiBackendKind`, `AnkiBackendLocality`, `AnkiBackendDescriptor` | `core/anki/AnkiBackendDescriptor.kt:11` |
| Capability requirements | `AnkiRequiredCapability`, `AnkiCapabilities.supports`, `STUDY_SESSION_REQUIREMENTS` | `core/anki/AnkiBackendCapabilityRequirement.kt` |
| Preference | `AnkiBackendMode`, `AnkiBackendPreferenceStore`, `DataStoreAnkiBackendPreferenceStore` | `core/anki/AnkiBackendId.kt:45`, `core/anki/AnkiBackendPreference.kt`, `data/preferences/DataStoreAnkiBackendPreferenceStore.kt` |
| Status observation | `AnkiBackendStatusCenter`, `AnkiBackendStatusSnapshot` | `core/anki/AnkiBackendStatus.kt` |
| Session lock | `AnkiStudyBackendLockTracker` | `core/anki/AnkiBackendStatus.kt` |
| Recovery routing | `AnkiTransactionRecoveryPlanner`, `BackendTransactionRoute` | `core/anki/AnkiTransactionRecoveryRouting.kt` |
| Session context | `AnkiSessionContext` | `core/anki/AnkiSessionContext.kt:10` |
| Study runtime | `AnkiStudyEffectExecutor`, `StudySessionMachine`, `StudySessionMachineRepository` | `core/study/`, `data/repository/` |
| Session start site | `AnkiLocalStudyStarter` | `data/repository/AnkiLocalStudyStarter.kt` |
| Mutation ledgers | `ReviewCommitLedger`, `ReviewerActionLedger`, `NoteMutationLedger`, `NoteCreationLedger` | `core/anki/`, `core/anki/edit/`, `core/anki/create/` |
| Recovery coordinators | `AnkiReviewCommitReconciler`, `AnkiReviewerActionReconciler`, `NoteMutationCoordinator`, `NoteCreationCoordinator` | `core/anki/ReviewCommitReconciliation.kt`, `core/anki/ReviewerActionRecovery.kt`, `core/anki/edit/`, `core/anki/create/` |
| Composition root | `AppContainer` (865 lines; `ankiBackendRegistry` at `:495`) | `di/AppContainer.kt` |
| Existing GATE 21 tests | 56 tests in 6 classes | `Gate21ArchitectureAuditTest` (15), `Gate21BackendResolutionTest` (13), `Gate21TransactionRecoveryRoutingTest` (9), `Gate21SessionBackendLockTest` (7), `Gate21BackendSelectionUiTest` (8), `Gate21BackendRoutingDiagnosticsTest` (4) |

### A02 — Backend identity

See `BACKEND_IDENTITY_INVENTORY.md` §1.

`AnkiBackendId` represents **a concrete persistent backend instance**, not merely a kind:
`AnkiDroidLocal` (one device, one collection at a time), `PcAgent(profileId)` (a configured instance —
two profiles are two identities with two `stableId`s), `Fake(id)`.

**Risk of multiple PC configurations sharing one identity: mitigated**, because the profile discriminator
is part of the identity and of `stableId`, and a blank profile id is refused at construction. The residual
risk is allocation, not representation: nothing in `app/src/main` constructs a `PcAgent` id, so there is
no producer whose stability can be audited.

`AnkiBackendMode` (AUTO / ANKIDROID_LOCAL / PC_AGENT) is a **preference enumeration**, deliberately
separate from identity, and `accepts()` never admits `Fake`.

### A03 — Collection identity

See `BACKEND_IDENTITY_INVENTORY.md` §2.

| Backend | Classification |
|---|---|
| AnkiDroid | `UNAVAILABLE` — `collectionKey` is always `null` |
| PC | `UNKNOWN` — no implementation |
| Fake | whatever the fixture sets; not authoritative |

No sync state, no hkey, no server id and no cross-backend comparison code exists. Authoritative
equivalence is unsupported; heuristic equivalence is not attempted.

### A04 — Resource identity

See `BACKEND_IDENTITY_INVENTORY.md` §3.

All of `AnkiCollectionIdentity`, `AnkiDeckRef`, `AnkiNoteRef`, `AnkiCardRef`, `AnkiNoteModelRef`,
`AnkiCardTemplateRef` take `backendId` as a required constructor parameter and mix it into the stable key
through the length-prefixed `identityKey(...)` helper. Two gaps:

- **`AnkiMediaRef` does not exist.** `StoreMediaBackendRequest` is backend-qualified (`:68-74`), but
  `MediaStoreBackendResult.Stored(mediaName)` returns a bare name with no backend qualification
  (`core/anki/create/NoteCreationBackendResults.kt:93`). Relevant to 21K (K02/K03).
- **`AnkiFilteredSessionRef` does not exist**, because GATE 20 is BLOCKED and unimplemented.

### A05 — Availability and capabilities

See `BACKEND_CAPABILITY_MATRIX.md`.

One 11-state hierarchy covers both physical situations; `statusCode` gives each state a stable token;
`isReadyForReview` is positive knowledge (`Ready` **and** `capabilities.review`). Capabilities are
runtime-negotiated per backend, exposed as a `StateFlow`, and additionally snapshotted inside
`AnkiAvailability.Ready` — `AnkiBackendDescriptor.supports` requires both projections to agree.

Security/permission boundaries:

| Boundary | Owner |
|---|---|
| AnkiDroid provider permission | `AnkiDroidPermissionManager.kt` → `AnkiAvailability.PermissionRequired` |
| Endpoint allowlist (release cannot resolve the debug authority) | `AnkiDroidApiContract.kt:790`, `:814` |
| One physical-write permit shared by every mutation family | `AnkiDroidWritePermit.kt`, wired in `AppContainer` |
| No secret in a descriptor | `AnkiBackendDescriptor` (id, availability, capabilities, kind, locality only) |
| PC Anki trust model | **does not exist** — no code, no transport, no authorization |

### A06 — Current backend routing

See `BACKEND_ROUTING_INVENTORY.md` for all 44 sites.

Headline: **no hidden fallback exists.** No availability-driven switch, no exception-driven switch, no
reconnect-driven switch, no "try the next backend" loop, and no cross-backend cache reuse. Three
ViewModels expose `switchBackend`; none has a production caller.

The pre-binding policy (`AnkiBackendResolver`) is a pure function of (preference, required capabilities,
descriptors): mode filter → reachable → capability-satisfying → `LOCAL_DEVICE` before `REMOTE_AGENT` → a
single remote candidate, with several remote candidates producing a deterministic `Ambiguous` refusal
rather than a list-order pick.

### A07 — StudySession backend ownership

`AnkiSessionContext` (see inventory §5) captures `backendId`, collection (nullable), deck, start time, a
capability snapshot and the study session id. It is created once, inside `begin()`, after the deck has
been validated against the live collection, and is published to the lock tracker only after `beginReview`
succeeded (`AnkiStudyEffectExecutor.kt:585`). It is released only on end-review (`:802`).

| Event | Does the lock survive? |
|---|---|
| configuration change | **yes** — process-scoped container, machine held by `StudySessionMachineRepository` |
| process death | **no** — `AnkiSessionContext` is not `@Serializable` and the tracker is a `MutableStateFlow` in memory; only the durable ledgers survive |
| session recovery | the **transaction** recovers from its ledger; the session object does not |

### A08 — Transaction ownership

| Gate | Implemented? | Backend source of truth | Recovery routing |
|---|---|---|---|
| GATE 11 — ReviewCommit | **yes** | `ReviewCommitRecord.commitId.backendId` (persisted; `ReviewCommit.kt:272`) | `AnkiReviewCommitReconciler(registry)`; `ledger.pendingRecovery(backendId)`, `recoveryBlocker(backendId, …)` |
| GATE 13 — ReviewerAction | **yes** | `ReviewerActionRecord.backendId` + `collectionRef` (persisted; `ReviewerActionRecord.kt:110`) | `registry.find(record.backendId)`; refuses a foreign card or collection (`ReviewerActionRecovery.kt:286-320`) |
| GATE 17 — NoteMutation | **yes** | `NoteMutationRecord.backendId` (persisted; `edit/NoteMutationRecord.kt:21`) | ledger is backend-scoped, but the **coordinator is pinned** to one backend instance (`NoteMutationCoordinator.kt:151`, wired `AppContainer.kt:439`) — fail-closed via `BackendMismatch`, but a record owned by another backend could not be recovered |
| GATE 18 — NoteCreation | **yes** | `NoteCreationRecord.backendId` (persisted; `create/NoteCreationRecord.kt:47`) | same shape as GATE 17 (`NoteCreationCoordinator.kt:162`, wired `AppContainer.kt:470`) |
| GATE 20 — Custom study | **BLOCKED / not implemented** | no record, no filtered-session type | `StudyMode.CUSTOM_SESSION` is a wire enum with no client logic (`core/models/StudyControlModels.kt:37`) |

These are real integration points, not designs — all four ledgers exist, persist through versioned JSON
envelopes, and are keyed by `backendId`. GATE 20 is accurately reported as unimplemented.

### A09 — Read contexts

| Surface | Identity source | Backend-safe today? |
|---|---|---|
| Library | pinned `container.ankiDroidBackend` (`AppNavHost.kt:81`, `AppContainer.kt:492`) | single-backend only |
| Deck Details | pinned (`AppNavHost.kt:110`) | single-backend only |
| Card Browser | pinned (`AppNavHost.kt:144`); `switchBackend` exists with no production caller | single-backend only |
| Card Details | **registry lookup by `cardRef.backendId`** (`AppNavHost.kt:167-176`) | yes |
| Edit Note | **registry lookup by `editCardRef.backendId`** (`AppNavHost.kt:248-258`) | yes |
| Add Note | **registry lookup by the route's backend id** (`AppNavHost.kt:204-222`), but the route is seeded with `container.ankiDroidBackend.id` (`:94`) | route-safe, seed pinned |
| Models / templates | reached only through the Add Note screen (`AddNoteViewModel`) | inherits the Add Note binding |
| Cache / paging | `AnkiLibraryRepository` reuses a cache only for an identical backend id (`:193`, `:210`); the browser correlates every request with `RequestToken(backend.id, generation, query, cursor)` (`CardBrowserViewModel.kt:62`) | yes for the surfaces that can switch |

Navigation identity: card/edit routes carry `backendId.stableId` + `collectionKey` in a base64 token
(`Screen.kt:95-122`) and Add Note carries `backendId.stableId` (`:54`). **`deck-details` and
`card-browser` routes carry a raw deck id with no backend discriminator** (`Screen.kt:17`, `:64`) — a
deck id from backend A is indistinguishable from the same id on backend B once it is in the route string.

### A10 — Reconnect, restart and security

| Question | Finding |
|---|---|
| Is there an Anki-backend reconnect path? | **No.** Every `reconnect`/`Reconnect` symbol belongs to the PC **agent** transport (`core/network/ReconnectController.kt`, `WebSocketAgentConnection.kt`, `AppSettings.autoReconnect`), which carries AI/TTS/STT traffic. |
| What identity would a reconnect have to match? | `AnkiBackendId.PcAgent(profileId)` — configuration-derived. Matching it proves identity, not collection continuity. `AnkiTransactionRecoveryPlanner` reports `COLLECTION_MISMATCH` only when both the record key and the live key are known. |
| Can AnkiDroid's collection change be detected mid-session? | **No** (`AnkiDroidBackend.kt:1340` fails open on an unknown key). |
| Restart sequencing | `AppContainer.kt:439` commentary: startup recovery is off the splash path — ledgers are loaded but study state is not mutated. The study start path re-checks ledger health and recovery blockers **before** any scheduler traffic (`AnkiStudyEffectExecutor.kt:556-567`); availability is refreshed inside `begin()` (`:544`), i.e. after the recovery scan, not before. |
| Permission / authorization preconditions | AnkiDroid: third-party-declared `READ_WRITE_DATABASE`, surfaced as `PermissionRequired`; endpoint allowlist gated by `BuildConfig.DEBUG`. PC Anki: **none exist** — no code. |
| Protocol security | No Anki traffic crosses the agent WebSocket. Token storage is covered by `core/security/SecureTokenStorage.kt` for the agent transport. |

---

## PART II — Architecture Audit

No code was modified in this Sub-Gate. The audit below judges the **completeness and accuracy of the
discovery**, and the safety of the invariants the later Sub-Gates will depend on.

| Audit | Result | Evidence |
|---|---|---|
| AUD-A01 — All backend paths identified | **PASS** | `BACKEND_ROUTING_INVENTORY.md` lists 44 sites; the set is closed by exhaustive greps for `registry.find`, `ankiDroidBackend`, `AnkiBackendResolver/Selector/StatusCenter`, `switchBackend`, `reconnect`. |
| AUD-A02 — All selection paths identified | **PASS** | Two pre-binding resolvers (`AnkiBackendResolver`, `AnkiBackendSelector`), one preference writer (`SettingsViewModel.selectAnkiBackend`), one dashboard pre-flight constant, and four pinned DI sites. No implicit path found (§A06 table). |
| AUD-A03 — Identity limitations documented | **PASS** | `BACKEND_IDENTITY_INVENTORY.md` §1.4, §2.3, §8 classify every identity; the two missing types (`AnkiMediaRef`, `AnkiFilteredSessionRef`) are named as gaps rather than assumed. |
| AUD-A04 — Mutation recovery ownership mapped | **PASS** | A08 table covers all four implemented families plus an accurate BLOCKED for GATE 20. |
| AUD-A05 — Safety-critical unknowns surfaced | **PASS** | §0 facts 1, 2 and 5, plus §PART III "unknowns that must not be assumed away". |
| AUD-A06 — No claim exceeds the evidence | **PASS** | Every PC cell in the capability matrix is `✘` because there is no code, and the matrix says so explicitly. |
| AUD-A07 — Prior GATE 21 artifacts reconciled | **PASS (with obligation)** | Prior merged work is documented in §0 and mapped to the new decomposition in §"Reconciliation" below. The obligation: the prior "GATE 21 LOCKED" report is a **different gate-management scope** and must not be inherited by this 21A–21N run. |
| AUD-A08 — Contradiction with GATE 11–20 | **none found** | The routing rules added by the prior pass are additive: `AnkiBackendSelector.REVIEW_REQUIREMENT` aliases `STUDY_SESSION_REQUIREMENTS`, and the report states all pre-existing selector/contract tests still pass. |

### Reconciliation with the prior merged GATE 21

| Prior artifact | Maps to | State |
|---|---|---|
| `docs/GATE_21_MULTI_BACKEND_DISCOVERY.md` (DISCOVERY-21-01…37) | 21A | **superseded for scope** — it describes the tree *before* the prior implementation; it is still accurate about AnkiDroid facts |
| `docs/GATE_21_MULTI_BACKEND_CONTRACT.md` (DECISION-21-01…39) | 21B | **input, not a lock** — the new 21B must re-issue decisions in the B01–B12 shape and reconcile them against this file |
| `docs/GATE_21_MULTI_BACKEND_REPORT.md` | 21N | **not a final audit** under the new decomposition |
| `core/anki/AnkiBackendId.kt`, `AnkiRefs.kt`, `AnkiSessionContext.kt` | 21C | present; 21C audits rather than creates |
| `core/anki/AnkiBackendDescriptor.kt:11`, `AnkiBackendSelector.kt` (registry) | 21D | present, but at `core/anki/`, **not** `core/anki/backend/` as the plan names it |
| `core/anki/AnkiBackendResolver.kt`, `AnkiBackendPreference.kt` | 21E | present |
| `AnkiBackendStatus.kt` (status centre), `AnkiTransactionRecoveryRouting.kt` | 21D/21E/21H–21K | present |
| `ui/components/anki/AnkiBackendUiModels.kt`, Settings/Study/Diagnostics wiring | 21M | partial — **no Library indicator** |
| 56 GATE 21 tests across 6 classes | 21C/21D/21E/21G/21H–21K/21M/21N | present |

Sub-Gates with **no prior implementation at all**: 21F (read-context switching at the composition root),
21L (custom study — blocked upstream by GATE 20), and the Library portion of 21M.

---

## PART III — Verification

Method: cross-check every documentation claim against an actual symbol in `app/src`, and compile the
whole main + test source set with the repository's own Gradle-free harness (`tools/jvm-harness`).

### V-A01 — Build baseline

Gradle cannot run in this sandbox (no JDK in `PATH`, no Android SDK). The harness is the repository's
documented fallback; its deviations from `./gradlew` are listed in `tools/jvm-harness/README.md`, so its
results are **equivalence evidence, not CI proof**.

Commands actually executed (bootstrap via `tools/jvm-harness/bin/bootstrap-sandbox.sh`; inputs from PyPI
`jdk4py` / `kotlin-jupyter-kernel` and GitHub `Sable/android-platforms`, `Kotlin/kotlinx.coroutines`):

| Step | Command | Result |
|---|---|---|
| compile main | `HARNESS_WORK=/tmp/h HARNESS_JAVA=... tools/jvm-harness/bin/build.sh main` | `main errors: 0 (files: 305)` |
| compile Compose closure | (same step) | `compose errors: 0 [scope: GATE 16 UI closure] (files: 24, stub files: 46)` |
| compile tests | `... build.sh test` | `test errors: 0 (files: 218)` |
| run whole suite | `cd app && ... run.sh` | `RESULT classes=188 tests=2217 passed=2217 failed=0 ignored=0 time=106.6s` |
| run GATE 21 subset | `... run.sh 'Gate21'` | `RESULT classes=6 tests=56 passed=56 failed=0 ignored=0 time=2.0s` |
| run selector/contract regression | `... run.sh 'AnkiBackendSelectorTest\|AnkiArchitectureContractTest\|AnkiRatingCommitArchitectureTest'` | `RESULT classes=3 tests=32 passed=32 failed=0 ignored=0 time=2.3s` |

**Not run, and not claimed:** `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` and
`./gradlew connectedDebugAndroidTest`. By design the harness compiles neither `di/` nor the Compose UI, so
the GATE 21 wiring in `AppContainer`, `AppNavHost`, `SettingsScreen` and `StudyScreen` is verified here by
**source audit only**, not by execution. Those four files and the Gradle tasks remain open obligations for
21N.

### V-A02 — Symbol cross-check

Spot-checks performed during discovery (all executed, all as reported above):

| Claim | Command | Result |
|---|---|---|
| No PC Anki backend type exists | `grep -rn "PcAnkiBackend" app/src` | no production match |
| No production code constructs a PC identity | `grep -rn "PcAgent" app/src/main` | 4 matches: the declaration, two `when` branches, one UI label |
| The agent protocol carries no Anki traffic | `grep -rin "anki" core/models/ProtocolMessages.kt` | no match |
| Library/DeckDetails/Browser are pinned | `grep -rn "ankiDroidBackend" app/src/main` | 5 `AppNavHost` sites + `AppContainer` + `DiagnosticsRepository` |
| `switchBackend` has no production caller | `grep -rn "switchBackend" app/src` | 3 declarations + 2 test files, no production call |
| No filtered-session type exists | `grep -rn "FilteredSession\|CustomStudy" app/src/main` | one `StudyMode.CUSTOM_SESSION` enum constant |
| The card-browser capability is never enabled in production | `grep -rn "cardBrowser = AnkiCardBrowserCapabilities(" app/src` | one match, in `Gate21BackendResolutionTest.kt:139` |
| Every reference type is backend-qualified | read of `AnkiRefs.kt`, `AnkiNoteModels.kt`, `AnkiTemplateModels.kt` | confirmed |
| Ledgers persist `backendId` in a versioned envelope | read of the four ledger `encode`/`decode` paths | confirmed (schema versions 4/1/1/1) |

### V-A03 — Classification of unsupported / unknown behaviour

| Question | Classification | Why |
|---|---|---|
| Are two backend collections ever the same collection? | **UNSUPPORTED** | no identity, no sync state, no comparison code |
| Can a collection change be detected mid-session? | **UNSUPPORTED (AnkiDroid)** | `collectionMatchesBoundSession` fails open on an unknown key |
| What does a PC reconnect prove? | **UNKNOWN** | no PC backend exists to inspect |
| Is one backend faster than another? | **UNMEASURED** | no latency comparison anywhere |
| Can a filtered/custom-study session be bound? | **BLOCKED** | GATE 20 unimplemented |
| Is any AnkiDroid mutation verified on a real device? | **NO** | capability maturity is `IMPLEMENTED`, not `VERIFIED`, throughout `AnkiDroidCompatibilityPolicy.kt` |

### V-A04 — Unknowns that must not be assumed away by later Sub-Gates

1. AnkiDroid collection/profile change is undetectable — the strongest argument against treating
   availability as equivalence and against any mid-session failover.
2. Collection validation can only fail **open** today (no backend produces a key). It must be encoded so
   a future backend with real identity gets the check for free, and must never be replaced by a default.
3. Capability-constrained routing would refuse the card browser, media read, flags and verified deck
   counts on the only real backend. 21B must decide whether that refusal is correct or whether those
   capabilities must be honestly claimed first.
4. The session lock is process memory. 21G's "process restore preserves backend lock" criterion cannot be
   met for the *session*; only durable transaction ownership can be restored.
5. `AnkiLocalStudyStarter.localStudyBackendId` uses `LOCAL_PREFERENCE`, not the persisted preference, so a
   dashboard "ready to study" signal and an actual session start can mean different backends.
6. **A pinned read context currently feeds an unpinned session start.** Both start sites
   (`HomeViewModel.kt:214`, `DeckDetailsViewModel.kt:144`) take their deck from AnkiDroid-pinned Library
   data and then start with the persisted preference. Once a second backend is registered, the deck id is
   resolved against whatever backend the preference selects — producing either `deck_not_found` or, worse,
   a *different* deck with the same numeric id on a different collection. Full chain in
   `BACKEND_ROUTING_INVENTORY.md` §7. This is latent, not currently reachable, but it is precisely the
   resource-id reinterpretation this Gate prohibits and it must be closed by 21F/21G, not discovered by 21N.

---

## Exit criteria

| Criterion | Result |
|---|---|
| All current backend paths identified | **MET** — `BACKEND_ROUTING_INVENTORY.md`, 44 sites |
| All current backend selection paths identified | **MET** — §A06 and inventory §5, including an explicit negative result for hidden fallbacks |
| Identity limitations documented | **MET** — `BACKEND_IDENTITY_INVENTORY.md` §1.4, §2.3, §8 |
| Mutation recovery ownership mapped | **MET** — §A08, all four implemented families + GATE 20 BLOCKED |
| Safety-critical unknowns surfaced | **MET** — §PART III V-A03 / V-A04 |

**No production code was modified.** The four deliverables are documentation only.

## Handoff

21B must resolve, before any code is written:

1. Whether to adopt, amend or replace `docs/GATE_21_MULTI_BACKEND_CONTRACT.md` (DECISION-21-01…39) as the
   B01–B12 decision record.
2. The capability question in V-A04 #3 — the single decision with the widest blast radius.
3. The intended home for the registry/resolver: the plan names `core/anki/backend/`, the code has
   `core/anki/`. Moving it is a refactor of merged code and must be justified against regression risk.
4. Whether the persisted-preference mismatch in V-A04 #5 is a defect to fix in 21E or an intended
   "this phone" semantic to keep.
