# GATE 21B — Decision Evidence Traceability

**Gate:** GATE 21B — Backend Contract Resolution & Architecture Lock (Sub-Gate 21B of 21A–21N)
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/e0f0b8dd-study-agent-client`
**HEAD:** `236cff8f2733203ccacbc479a1463b283fcf2ecf`
**Date:** 2026-10-11
**Prerequisite:** GATE 21A PASS/LOCKED at `2d2a0a0` (= first parent of this HEAD; the whole delta since is `docs/`-only, proven via the upstream compare in `GATE_21B_CONTRACT_LOCK.md` §0.2).
**Status:** LOCKED. No production code was changed to produce this document.

This file exists to answer one question per decision: **what evidence is this contract standing on, what did
we decide that no evidence could decide for us, and what remains risky?** It is deliberately a matrix, not
prose; the explanation lives in `GATE_21B_CONTRACT_LOCK.md`, and the contracts themselves live in
`BACKEND_IDENTITY_CONTRACT.md`, `BACKEND_SELECTION_POLICY.md`, `BACKEND_OWNERSHIP_INVARIANTS.md` and
`BACKEND_FAILURE_MATRIX.md`.

## How to read the evidence column

| Notation | Meaning |
|---|---|
| `L-xx-yy` | Entry in `docs/GATE_21A_EVIDENCE_LEDGER.md` (ledger section `xx`, item `yy`) |
| `D-21-zz` | Finding `DISCOVERY-21-zz` in `docs/GATE_21_MULTI_BACKEND_DISCOVERY.md` |
| `file:line` | Production source at this HEAD, re-verified by GATE 21B (`GATE_21B_CONTRACT_LOCK.md` §0.4) |
| `TEST` | A test in this repository that pins the behavior the decision relies on (named, not re-executed) |
| `POLICY` | **Product/architecture policy choice** — no evidence could decide it; it is declared explicitly |

## How to read the risk column

`R1`–`R13` refer to PART 5 of `GATE_21B_CONTRACT_LOCK.md`. `—` means the decision carries no residual risk
beyond the general ones.

---

## Traceability matrix

| Decision | GATE 21A evidence | Policy choice (declared, not derived) | Risk | Downstream Sub-Gate |
|---|---|---|---|---|
| **21B-01** Canonical backend identity | `L-02-01…02-12`; `AnkiBackendId.kt:8-42`; `AnkiBackendSelector.kt:12`; `AnkiBackendDescriptor.kt:34,41,67`; 21A unknown #2 (no `PcAgent` producer) | **POLICY:** keep the existing representation and freeze it; require allocation discipline (A1–A6) instead of adding a finer identity scheme now. **POLICY:** collisions are configuration errors, never renumbered/merged | R3 | 21C (encode), 21D (allocation/registration) |
| **21B-02** Canonical collection identity | `L-03-01…03-08`; `AnkiRefs.kt:7`; `AnkiDroidDeckMapper.kt:93,114`; `AnkiDroidBackend.kt:1336-1341`; `AnkiTransactionRecoveryRouting.kt:71`; `D-21-03/04` | **POLICY:** unknown is never a wildcard; **POLICY:** the safety boundary is drawn per operation class rather than "block everything" or "trust everything" | R1, R2, R5, R6 | 21C, 21F, 21G, 21H–21K |
| **21B-03** Identity assurance model | as 21B-02, plus the existing "cannot tell" vs "told differently" distinction in `AnkiTransactionRecoveryRouting.kt:57-71` | **POLICY:** four categories with nominal/degraded split; **POLICY:** no spontaneous downgrade; **POLICY:** connectivity never grants mutation recovery | R1, R2, R5 | 21C (encode), 21G–21K (consume), 21M (disclose) |
| **21B-04** Backend-qualified resource identity | `L-04-01…04-12`; `AnkiRefs.kt:13,27,49`; `AnkiNoteModels.kt:25`; `AnkiTemplateModels.kt:18`; `NoteCreationBackendResults.kt:68-74,93`; `Screen.kt:17,64,95-122`; `TEST AnkiArchitectureContractTest` | **POLICY:** media and filtered-session refs must be qualified *before* their features exist; **POLICY:** the interim deck-token rule resolves against the producing backend, not the preference | R7 | 21C, 21F (routes), 21K (media), 21L (filtered) |
| **21B-05** Selection vocabulary | `L-02-12`, `L-08`, `L-09`, `L-10`; `AnkiBackendId.kt:44` ("resolution must never overwrite AUTO with an effective identity"); `AnkiBackendPreference.kt:41-57`; `TEST` four separate diagnostics fields | **POLICY:** explicit selection must resolve to an *instance* (instance pin) when a kind has several instances; **POLICY:** a mode-only preference keeps today's meaning | R3 | 21E (preference + pin), 21M (display) |
| **21B-06** Deterministic AUTO | `L-07-04/07-05/13-02`; `AnkiBackendResolver.kt:104-215`; `AnkiBackendDescriptor.kt:88`; `AnkiBackendSelector.kt:13`; `TEST AUTO is deterministic for identical inputs`, `resolution is pure…` | **POLICY:** `LOCAL_DEVICE` before `REMOTE_AGENT` (a local collection has no transport that can vanish) — *not* a latency claim (`D-21-32`: no latency measurement exists); **POLICY:** never select a `TEST_DOUBLE` | R4 | 21E |
| **21B-07** Manual selection contract | `L-07-01…07-03`, `L-13-05`; `TEST manual selection of an unavailable backend blocks instead of falling back`; `TEST manual PC selection with no PC registered is not implemented, not redirected`; `AnkiBackendSelector.UnavailableReason` | **POLICY:** manual failure surfaces a typed refusal with a refresh action; **POLICY:** a manual selection binds only future unbound work | — | 21E, 21M (copy/actions) |
| **21B-08** Typed resolution result | `L-07-25…07-30`, `L-13-04`; `BackendResolutionResult` (`AnkiBackendResolver.kt:13-72`); `TEST every failure explains itself with a typed error and the identities it considered` | **POLICY:** eight semantic outcomes; **POLICY:** `IDENTITY_UNAVAILABLE` is demoted out of resolution into a binding verdict (resolution precedes any context that could have an identity) | — | 21E (encoding), 21F/21G (binding verdicts) |
| **21B-09** Capability contract | `L-05`, `L-13-06`; `AnkiCapabilities`; `AnkiBackendCapabilityRequirement.kt:93`; `AnkiDroidBackend.kt:211`; `AnkiBackendDescriptor.kt:95-103`; `AnkiDroidCompatibilityPolicy.kt:63,106`; `AppContainer.kt:326`; `TEST capability resolution never inspects a backend name` | **POLICY (product):** `CARD_BROWSER`, `MEDIA_READ`, `FLAGS`, `DECK_COUNTS` are refused honestly rather than implemented or masked by pins; **POLICY:** implement/ready/permitted are three separate questions | R4, R7, R11 | 21D (versioning), 21F (refusals), 21I (flags) |
| **21B-10** Backend registry ownership | `L-01-10/13/14`; `AnkiBackendSelector.kt:10`; `AppContainer.kt:492-495`; 21A audit 14 (registry = existence/state, never routing) | **POLICY:** extend the existing registry; a second registry is prohibited | — | 21D |
| **21B-11** Resolver ownership | `L-01-11/12`, `L-07-39/07-40`; `AnkiBackendResolver.kt:104`; `AnkiBackendSelector.kt:40`; `TEST the only backend selection in study runtime happens at session begin` | **POLICY:** no new resolver component; exactly one policy implementation | — | 21E |
| **21B-12** Immutable StudySession ownership | `L-08-01…08-10`, `L-01-15`; `AnkiStudyEffectExecutor.kt:544-549,571-576,578,582,585,508,513,628,664-666,802-804`; `AnkiBackendStatus.kt:128`; `StudyViewModel.kt:88` | **POLICY:** session identity is the existing `sessionId` (no second identity); **POLICY:** PC AI/TTS stays usable and is not a resolution input | R8, R9 | 21G, 21M |
| **21B-13** No mid-session backend failover | `L-11`, `L-07-25…30`, `L-08-04`; `TEST losing the locked backend blocks the session instead of failing over`; `TEST no cross-backend failover exists anywhere in study runtime`; `D-21-13` | **POLICY:** reads *inside* a turn are part of the session and may not fail over either | — | 21G |
| **21B-14** Session persistence & restoration | `L-02-11`, `L-08-08`, `L-11`; `AnkiSessionContext.kt:10`; `AnkiBackendStatus.kt:128`; `AppContainer.kt:486-488`; 21A unknown #6 | **POLICY:** sessions are not restored today; **POLICY:** if durability is added it must satisfy the §2.5 envelope (required, non-defaulted backend id; invalidation instead of guessing) | R8 | 21G |
| **21B-15** Transaction backend ownership | `L-09-01…09-11`; `ReviewCommit.kt:272`; `ReviewerActionRecord.kt:110,147,148`; `edit/NoteMutationRecord.kt:21`; `create/NoteCreationRecord.kt:47`; `NoteMutationCoordinator.kt:151,344`; `NoteCreationCoordinator.kt:162,277`; `AppContainer.kt:439,470` | **POLICY:** coordinators must be resolved by record identity (the pinning is recorded as a latent gap, not as a guarantee); **POLICY:** the CustomStudy row is a future contract, not a shipped guarantee | R7, R9 | 21H, 21I, 21J, 21K, 21L |
| **21B-16** Transaction recovery routing | `L-09-08…09-11`, `L-13-03`; `AnkiTransactionRecoveryRouting.kt:44-105`; `AnkiStudyEffectExecutor.kt:556-567`; `ReviewCommitReconciliation.kt:82`; `ReviewerActionRecovery.kt:286-320`; `TEST groups by persisted backendId`, `TEST never consults resolver`, per-group isolation tests | **POLICY:** blocking is per owner group and has no expiry/escalation/queue (inherited from the parent gate) | R5 | 21H–21K |
| **21B-17** Historical records & migration | `L-09-06`, `L-02-03`, `L-04`; `ReviewCommitLedger.kt:100-155,157,239` (vocabulary migration, identity preserved); `CommitIdentityTombstone` (`:37-49`) repaired from `commitKey` evidence (`:138-145,210-216`) | **POLICY:** uncertainty evidence is never erased to make a record loadable; **POLICY:** ownership may be derived only from evidence the record itself carries | — | 21C (format), 21H–21K |
| **21B-18** Read context isolation | `L-10-01…10-12`, `L-07-23…07-33`; `AppNavHost.kt:81,94,110,142,144,167-176,204-222,248-253`; `AnkiLibraryRepository.kt:193,208-210`; `CardBrowserViewModel.kt:62,142,387`; `Screen.kt:17,64` | **POLICY:** switching creates a new context instead of mutating one; **POLICY:** pins are removed rather than kept as an implicit second routing system | R10, R7 | 21F |
| **21B-19** Asynchronous response correlation | `L-10-10…10-12`; `CardBrowserViewModel.kt:62,387`; `AnkiLibraryRepository.kt:193,208-210`; `TEST browser paging state cannot cross a backend boundary`; `D-21-19` | **POLICY:** explicit discard (latest-request-wins) instead of relying on cancellation; **POLICY:** Compose recomposition is not a correctness mechanism | — | 21F |
| **21B-20** Editor binding | `L-07-30`, `L-10`; `Screen.kt:42,95-122`; `AppNavHost.kt:248-253`; `NoteMutationCoordinator.kt:344`; `EditNoteMapper.kt:372`; `TEST bound editors and drafts resolve their backend from the route, not the preference`; `D-21-20` | **POLICY:** blocked-with-reason + preserved draft is preferred over read-only-without-explanation; **POLICY:** switching targets requires a new context | — | 21J |
| **21B-21** Creation binding | `L-04`, `L-18`; `Screen.kt:51-57`; `AppNavHost.kt:94,204-222`; `NoteCreationCoordinator.kt:162,277`; `NoteCreationBackendResults.kt:68-74,93`; `TEST bound editors and drafts…`; `D-21-15/16` | **POLICY:** the five-element coherence rule is enforced as one binding, not per element; **POLICY:** a new context is the only way to change the creation target | R7 (media ref absent) | 21K |
| **21B-22** Custom Study ownership | `L-04-09`, `L-09-05`; `StudyControlModels.kt:37` (wire enum only); no `AnkiFilteredSessionRef`; `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` BLOCKED; `D-21-17` | **POLICY:** everything in this decision is a required future contract; **POLICY:** no fallback to normal review, ever | R7 | 21L (**blocked** on GATE 20) |
| **21B-23** Reconnect & identity revalidation | `L-11-01…11-09`; `AnkiDroidHealthRepository.kt:48,90,142,158`; `AnkiDroidBackend.kt:1336-1341`; `AnkiTransactionRecoveryRouting.kt:71`; no Anki-backend reconnect path exists; 21A unknown #1; `D-21-21/22` | **POLICY:** five-level ladder instead of a "connected" boolean; **POLICY:** continuation is a user decision that must pass the assurance matrix | R1, R2, R5, R6 | 21C, 21G, 21H–21K, 21M |
| **21B-24** Source-of-truth matrix | `L-01…L-13` per row; `TEST the four sources of truth are separate components`; `D-21-23/24/35` | **POLICY:** one authority per concern, with explicit exclusions (registry ≠ router; status center ≠ decision source; diagnostics ≠ input) | — | all of 21C–21N |
| **21B-25** Operation routing matrix | `L-07`, `L-08`, `L-09`, `L-10` and the per-operation call sites listed in `BACKEND_OWNERSHIP_INVARIANTS.md` §9 | **POLICY:** operations whose capability is currently unsatisfiable are specified now so that enabling them later cannot invent a new selection path | R7 | 21F–21L |
| **21B-26** Failure & failover matrix | `L-07-25…30`, `L-11`; `D-21-27/32/38`; 21A "IMPLICIT_FALLBACK: NONE FOUND"; `TEST losing the locked backend blocks the session…` | **POLICY:** no "fallback if possible" language anywhere; the only fallback is AUTO for a new unbound context | — | 21F–21M |
| **21B-27** Diagnostics & privacy | `L-13-06`; `AnkiBackendPreference.kt:41-57`; `DiagnosticsRepository.kt:594-605`; `TEST DiagnosticsExportPrivacyTest`; `AnkiBackendDescriptor.kt` (no secret fields) | **POLICY:** assurance categories are logged verbatim (never "matched"); **POLICY:** identity in diagnostics is always `stableId`, never a raw endpoint | — | 21M |
| **21B-28** Security preconditions | `L-12`; `AnkiDroidPermissionManager`; `AnkiDroidApiContract.kt:790,814`; `AnkiDroidWritePermit`; `AnkiAvailability.PermissionRequired`; PC trust model absent | **POLICY:** selection gated through the readiness ladder rather than a new parallel permission check; **POLICY:** network location is never authorization | R3, R7 | 21D, 21H, 21M |
| **21B-29** Architecture compatibility | `§0.5` gate status table; `GATE_11` "BLOCKED", `GATE_13` IMPLEMENTED, `GATE_15` "OPEN — NOT LOCKED", `GATE_16` "BLOCKED from LOCKED", `GATE_17/18/19` LOCKED, `GATE_20` BLOCKED, `GATE_21` LOCKED | **POLICY:** no compatibility claim about nonexistent code; every blocked dependency is named | R7, R12 | all |
| **21B-30** Contract consistency review | the 29 decisions above + the six companion matrices | **POLICY:** where two readings conflicted, the stricter reading was adopted (four cases, listed in DECISION-21B-30) | — | 21N |

---

## Policy choices that no evidence could decide (collected)

These are the points where GATE 21B made a **deliberate product/architecture choice**. They are listed
together so that a future reader can see exactly where evidence stopped and judgement began — and can
revisit them without disturbing the evidence-backed parts of the contract.

| # | Policy choice | Where | Revisitable? |
|---|---|---|---|
| P1 | `LOCAL_DEVICE` is preferred over `REMOTE_AGENT` in AUTO | `BACKEND_SELECTION_POLICY.md` §3.3 step 3 | Yes, by changing the ordering — but never to non-determinism |
| P2 | Nominal collection unverifiability continues to operate; degraded and mismatch block | `BACKEND_IDENTITY_CONTRACT.md` §3.2–§3.3 | Only by finding a real collection discriminator |
| P3 | `CARD_BROWSER`, `MEDIA_READ`, `FLAGS`, `DECK_COUNTS`: refuse honestly, do not implement here | DECISION-21B-09 | Yes, as a separate future feature decision |
| P4 | Sessions are not durable today; if made durable, the §2.5 envelope is mandatory | DECISION-21B-14 | Yes (product decision), within the envelope |
| P5 | An instance pin is required for unambiguous explicit selection when a kind has several instances | `BACKEND_SELECTION_POLICY.md` §1.1 | Yes, as a storage/UX decision; not the semantics |
| P6 | Assurance categories are surfaced verbatim and never summarised as "verified" | DECISION-21B-03/27 | No — this is a safety requirement, not a preference |
| P7 | Blocking has no expiry, escalation or queue | DECISION-21B-16 | Only via GATE 22, and never by making a record replayable |
| P8 | A legacy unqualified deck token resolves against the producing backend, not the preference | `BACKEND_IDENTITY_CONTRACT.md` §4.4 | Superseded by 21F's qualified tokens |

---

## What GATE 21A evidence could **not** establish (carried into 21C–21N)

| Open question | Why it stayed open | Where it must be resolved |
|---|---|---|
| Does AnkiDroid expose any collection discriminator? | Requires a device probe; source says none is used | 21C/21D (or a product decision to keep nominal) |
| Can the PC agent report a collection identity? | No implementation exists | 21D/21H |
| Which discriminator scheme PC identities will use | No producer exists | 21D (within A1–A6) |
| Whether session durability is required | Product decision | 21G |
| Whether a filtered-session identity is obtainable | GATE 20 BLOCKED | GATE 20 → 21L |
| Whether capability claims need persisted version metadata | Depends on the PC protocol and AnkiDroid contract evolution | 21D |
| Whether the existing test suite still passes at this HEAD | No JDK/Android SDK in the sandbox; tests not re-executed in this gate | 21N / CI |

---

## Implementation authorization outcome (restated for completeness)

```text
GATE 21B:                   LOCKED
IMPLEMENTATION AUTHORIZED:  NO — this sub-gate produced contracts only
NEXT AUTHORIZED SUB-GATE:   21C — Backend Identity & Resource Qualification

Production Kotlin changes:  0
Gradle changes:             0
Persistence migrations:     0
Runtime behavior changes:  0
```
