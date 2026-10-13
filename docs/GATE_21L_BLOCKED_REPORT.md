# GATE 21L — Custom Study & Filtered Session Backend Ownership (BLOCKED Report)

**Status:** `BLOCKED`
**IMPLEMENTATION AUTHORIZED: NO**
**Type:** Prerequisite validation → blocker documentation. No production code, tests, or build changes.
**Next authorized sub-gate:** NONE. GATE 21M must not start from this report (see §8).

---

## 0. Initial Report (required header)

```text
REPOSITORY HEAD:    7402e0dadae5c084725388af1599e8b860f6514e (branch base; this report is the only change on top of it)
BRANCH:             arena/0b046d91-study-agent-client
WORKTREE STATUS:    clean at the start of the gate (git status: nothing to commit)

GATE 20:                    DESIGN_ONLY  (docs/GATE_20_CUSTOM_STUDY_CONTRACT.md: "Phase B BLOCKED", "Phase C not started")
GATE 21A:                   LOCKED       (docs/GATE_21A_DISCOVERY.md, PASS, 10/10 audits)
GATE 21B:                   LOCKED       (docs/GATE_21B_CONTRACT_LOCK.md:13 "RESULT: LOCKED")
GATE 21C:                   LOCKED       (docs/GATE_21C_IDENTITY_IMPLEMENTATION.md, harness-verified)
GATE 21D:                   LOCKED       (docs/GATE_21D_REGISTRY_IMPLEMENTATION.md, harness-verified)
GATE 21E:                   LOCKED       (docs/GATE_21E_RESOLVER_IMPLEMENTATION.md, harness-verified)
GATE 21F:                   LOCKED       (docs/GATE_21F_READ_CONTEXT_ISOLATION.md, harness-verified)
GATE 21G:                   LOCKED       (docs/GATE_21G_VERIFICATION_REPORT.md, harness-verified)
GATE 21H:                   LOCKED       (docs/GATE_21H_VERIFICATION_REPORT.md, harness-verified)
GATE 21I:                   LOCKED       (docs/GATE_21I_VERIFICATION_REPORT.md, harness-verified)
GATE 21J:                   NOT LOCKED   (implemented + harness-verified, "pending audit approval of DECISION-21J-03")
GATE 21K:                   NOT LOCKED   (no gate report; creation still pinned to AnkiDroid, see §2)

CUSTOM STUDY COORDINATOR:           MISSING
FILTERED SESSION BACKEND API:       MISSING  (AnkiBackend interface has no filtered-deck/custom-study operation)
CUSTOM STUDY PERSISTENCE:           MISSING
FILTERED SESSION IDENTITY:          UNKNOWN  (no verified backend identifier; preset CustomStudy returns no id)
LIFECYCLE MUTATION RECOVERY:        UNKNOWN  (no durable creation/rebuild/cleanup ledger exists or is designed)
```

---

## 1. Method

1. Read `README.md` and the `docs/` gate index; located every GATE 20 / 21 artifact.
2. Read the GATE 20 contract (Phase A discovery, Phase B lock report, blocker table).
3. Read the GATE 21B lock entries for 21K, 21L, 21M, and the ownership-invariant §6.3 gate condition.
4. Searched the source tree for the symbols GATE 20 would have introduced, and for the lifecycle operations the prompt names.
5. Checked the `AnkiBackend` interface for filtered-session operations.

Search results (`app/src`, `server/`):

| Search | Result |
|---|---|
| `class CustomStudy*`, `FilteredSession*`, `FilteredDeck*`, `CustomStudyCoordinator`, `AnkiFilteredSessionRef`, `FilteredSessionLedger` | **no matches** |
| `RebuildFilteredDeck`, `EmptyFilteredDeck`, `AddOrUpdateFilteredDeck`, `CustomStudy(` | **no matches** |
| `StudyMode.CUSTOM_SESSION` | one enum constant, `core/models/StudyControlModels.kt:37`, **no client logic** |
| `AnkiBackend` interface methods | decks, selected deck, browse, begin/next review, hydrate, commit rating, reviewer actions, note mutation, media, note creation. **No filtered-session, custom-study, rebuild, empty, or delete operation.** |
| `AnkiCapabilities` / `AnkiBackendCapabilityRequirement` | no custom-study or filtered-deck capability |

---

## 2. Prerequisite Validation

### 2.1 GATE 20 — DESIGN_ONLY (hard blocker)

`docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` states, verbatim in its status line: *"Phase B (contract lock) **BLOCKED**. Phase C (implementation) **not started** — `IMPLEMENTATION AUTHORIZED: NO`."* Its own lock report reads `RESULT: BLOCKED`.

Blocking items, quoted from its exact-blocker table:

| # | Blocker | Consequence for 21L |
|---|---|---|
| B1 | AnkiDroid cannot create, rebuild, or empty a filtered deck through any public API (`FlashCardsContract`, `CardContentProvider`). | No AnkiDroid Custom Study can be locked. Scheduler authority and capabilities are not unambiguous for AnkiDroid. |
| B2 | The PC agent is not in this repository. Its route to Anki's `CustomStudy` / `AddOrUpdateFilteredDeck` / `RebuildFilteredDeck` / `EmptyFilteredDeck` / `GetQueuedCards` is unknown. AnkiConnect exposes none of them. | The PC contract cannot be locked. |
| B3 | Session identity is undefined for presets. `CustomStudy` returns no id, and the reserved deck name `"Custom Study Session"` is localized. | `BackendFilteredSessionRef` cannot be defined. Identity is UNKNOWN. |
| B4 | Creation mutates the collection persistently. A durable creation ledger is indicated (DECISION-20-11) but not designed, and its design depends on B2/B3. | Lifecycle mutation recovery is UNKNOWN. |
| B5 | End/cleanup policy (empty the filtered deck vs. remove it) is a **product decision** (DECISION-20-14). | The "End" and "Cleanup" semantics cannot be implemented. |
| B6 | No device or disposable-collection evidence for AnkiDroid preview, rescheduling flag, restart, sync, or cross-client behavior. | Several capability rows remain UNKNOWN. |

The GATE 20 document also lists the preset-replacement hazard: a preset `CustomStudy` call **replaces** the reserved `"Custom Study Session"` deck, which silently discards a previous session's membership. That is a residual risk that GATE 20 has not resolved.

### 2.2 GATE 21B — 21L is a hard blocker

`docs/GATE_21B_CONTRACT_LOCK.md` (21L entry):

- **BLOCKERS:** "**hard blocker.** 21L is not authorized to start before GATE 20 is unlocked and implemented."
- **REQUIRED DECISIONS:** "all of them, starting with whether a backend-side filtered-session identity is obtainable at all."
- **EXISTING COMPONENTS TO REUSE:** "none yet — GATE 20 must land first."

`docs/BACKEND_OWNERSHIP_INVARIANTS.md` §6.3 (LOCKED): "21L **must not** implement custom-study backend binding before GATE 20 lands."

`docs/GATE_21A_DISCOVERY.md` (B12): "custom study must be marked BLOCKED until GATE 20."

The prompt itself says: *"If GATE 20 is not implemented, do not recreate its complete functionality under 21L."* That rule applies here.

### 2.3 GATE 21C–21I — locked prerequisites (reused, not changed)

These are locked and are the seams 21L would reuse. They are present in source (`AnkiBackendRegistration.kt`, `AnkiBackendSelector.kt`, `AnkiBackendResolver2.kt`, `BackendPreference.kt`, `StudySessionBackendLock.kt`, `StudySessionLockStore.kt`, `ReviewCommitRoutingPolicy.kt`, `ReviewerActionRoutingPolicy.kt`, `AnkiTransactionRecoveryRouting.kt`). No change is needed or made.

### 2.4 GATE 21J and 21K — not locked

- **21J:** implemented and harness-verified, but `docs/GATE_21J_NOTE_EDIT_BINDING.md` says "pending audit approval of DECISION-21J-03." That is not a lock. Note editing is not a Custom Study dependency, but the prompt lists it as a prerequisite, so the state is recorded.
- **21K:** no gate report exists. Note creation is still pinned to the AnkiDroid instance (`ui/navigation/AppNavHost.kt:121` navigates with `container.ankiDroidBackend.id`; `AppNavHost.kt:381` requires `addNoteBackend === container.ankiDroidBackend`). `docs/BACKEND_OWNERSHIP_INVARIANTS.md:343` records this as owned by 21K.

---

## 3. Checkpoint Classification

| Checkpoint | Action | Reason |
|---|---|---|
| 21L-00 Existing Custom Study inventory | **NO_CHANGE** (read-only, completed) | §1 search. No CustomStudy/FilteredSession lifecycle exists. Lifecycle operations are GATE 20 DISCOVERY-20-02 / 20-37 only. |
| 21L-01 Backend capability discovery | **BLOCKED** | Capability evidence is source-only (`GATE_20` DISCOVERY-20-02, 20-37). AnkiDroid has no public create/rebuild/empty API (B1). PC route is UNKNOWN (B2). |
| 21L-02 Custom Study mode contract | **BLOCKED** | GATE 20 DECISION-20-01 is "PROPOSED ONLY (not locked)". |
| 21L-03 Immutable Custom Study context | **BLOCKED** | Needs identity and mode contracts (B3). |
| 21L-04 Source deck qualification | **BLOCKED** | Depends on the locked mode and the Custom Study context. |
| 21L-05 Request validation | **BLOCKED** | GATE 20 DECISION-20-03/04/05 are "PROPOSED". |
| 21L-06 Initial backend resolution | **BLOCKED** | Would reuse GATE 21E. Blocked by the missing Custom Study context. |
| 21L-07 Atomic ownership admission | **BLOCKED** | Needs the creation boundary and durable record (B4). |
| 21L-08 Filtered resource identity | **BLOCKED** | B3. No verified identifier. The name is not identity (DECISION-20-08). |
| 21L-09 Filtered session creation | **BLOCKED** | B1, B2, B4. |
| 21L-10 Creation partial failure | **BLOCKED** | No creation ledger designed (B4). |
| 21L-11 StudySession ownership | **BLOCKED** | Depends on 21L-08. GATE 21G is locked and can be reused later. |
| 21L-12 Backend next card | **BLOCKED** | No filtered-session queue API in the repo. The backend queue (`GetQueuedCards`) is not reachable from the repo (B2). |
| 21L-13 Review info and rating | **BLOCKED** | Depends on 21L-12. GATE 11 is locked and reusable later. |
| 21L-14 Reviewer actions | **BLOCKED** | Filtered-deck bury/suspend semantics are not verified (DISCOVERY-20-26). |
| 21L-15 Rebuild semantics | **BLOCKED** | Requires B2. Rebuild is `RebuildFilteredDeck` (backend), which is not reachable. |
| 21L-16 Rebuild ambiguity | **BLOCKED** | B4. |
| 21L-17 Empty / exhausted session | **BLOCKED** | Requires the lifecycle contract. |
| 21L-18 End semantics | **BLOCKED** | B5 (product decision). |
| 21L-19 Cleanup safety | **BLOCKED** | B5, B4. |
| 21L-20 Backend loss | **BLOCKED** | No Custom Study binding to protect. |
| 21L-21 Same-backend reconnect | **BLOCKED** | No binding. |
| 21L-22 Collection mismatch | **BLOCKED** | AnkiDroid collection identity fails open (GATE_21A risk 5). Not 21L's to fix. |
| 21L-23 External filtered-resource changes | **BLOCKED** | Restore needs a filtered identity. |
| 21L-24 Process-death restoration | **BLOCKED** | No persisted Custom Study record. `AnkiSessionContext` is not `@Serializable` (GATE_21A). |
| 21L-25 Lifecycle mutation ownership | **BLOCKED** | B4. No generic ledger may be invented (prompt Part III and §5). |
| 21L-26 Legacy compatibility | **NO_CHANGE** | No legacy Custom Study records exist. Nothing to migrate. |
| 21L-27 Concurrent admission | **BLOCKED** | Depends on the above. |
| 21L-28 Stale callback isolation | **BLOCKED** | No Custom Study callbacks exist. |
| 21L-29 Read-context independence | **BLOCKED** | Nothing to isolate yet. GATE 21F is locked and remains unchanged. |
| 21L-30 UI / diagnostics projection | **BLOCKED** | Pre-empts GATE 21M. Out of scope. |
| 21L-31 Deterministic fake backends | **BLOCKED** | The fake filtered-session API does not exist. Writing it would define an unlocked protocol. |
| 21L-32 DI integration | **NO_CHANGE** | No Custom Study coordinator to wire. |
| 21L-33 Regression review | **NO_CHANGE** | No code changed, so no regression surface changed. |

No checkpoint is classified CREATE or MODIFY.

---

## 4. Audit and Verification Status

Per the prompt, audits and verifications are reported against implementation. No implementation exists, so none can pass.

- **AUDIT-21L-01 … AUDIT-21L-30:** **BLOCKED** (nothing to audit). AUDIT-21L-27 (security/privacy) and AUDIT-21L-29 (prior-gate preservation) are **PASS** for this report's scope only, because no code was changed.
- **VER-21L-01 … VER-21L-47:** **BLOCKED**. Each requires a Custom Study binding, a filtered identity, or a lifecycle operation, and none exists.
- **Build / lint / test:** **not run**. No code changed, and the prior gates record the Gradle wrapper as environmentally blocked (TLS failure, no Android SDK). Running a build would not change the BLOCKED verdict.

A BLOCKED verdict is not a PASS. Nothing in this report is claimed as runtime-tested.

---

## 5. Why Implementation Is Not Authorized

Each of the following would be a forbidden substitute for the missing contract:

1. **Inventing a filtered-session identifier.** The contract forbids it (prompt §2.5, Rule C, INV-21L-07/08). GATE 20 DECISION-20-08 is unresolved.
2. **Using the reserved deck name as identity.** It is localized, and display names are not identity.
3. **Adding a generic mutation ledger.** The prompt forbids it, and GATE 20 has not designed one (DECISION-20-11).
4. **Calling `RebuildFilteredDeck`, `EmptyFilteredDeck`, or `AddOrUpdateFilteredDeck` through an assumed PC route.** The PC route is UNKNOWN (B2). Doing so would be the "guessed operation" the prompt forbids.
5. **Choosing empty vs. delete for cleanup.** That is a product decision (B5).
6. **Calling AnkiDroid filtered-deck creation.** There is no public API (B1). The `deckID` side-door in GATE 20 is undevice-verified.
7. **Implementing a local next-card queue as a stopgap.** Forbidden by Rule B.

---

## 6. Blockers and the Owning Gate for Each

| Blocker | Owner | What is needed |
|---|---|---|
| B1 AnkiDroid filtered-deck API | GATE 20 (contract) | Decide whether AnkiDroid is in scope for review of **existing** filtered decks only. Verify `deckID` side effects on a disposable collection. Creation stays out of scope. |
| B2 PC route to CustomStudy / filtered RPCs | GATE 20 (contract), with the PC agent repo | Pick an in-process Anki backend or plugin. Verify it against the PC agent's own repo, which is not in this repo. |
| B3 Identity | GATE 20 (DECISION-20-08) | Pick raw `AddOrUpdateFilteredDeck` (returns id) or prove preset identity. |
| B4 Creation / rebuild / cleanup ledger | GATE 20 (DECISION-20-11/12/13) | Design and lock a durable lifecycle ledger inside GATE 20's existing operation model. Do not invent a new one in 21L. |
| B5 End / cleanup product decision | Product owner, via GATE 20 (DECISION-20-14) | Choose empty-only vs. remove-deck, and the reserved-deck "replace" behavior. |
| B6 Device evidence | GATE 20 / a disposable-collection test run | Run DISCOVERY-20-18 to 20-34 on AnkiDroid v2.24.1 and Anki 25.09.2. |
| 21J DECISION-21J-03 | 21J audit | Approve or reject the `OP-M3` deviation. |
| 21K note creation binding | 21K | Seed `AppNavHost` from resolution; lock the creation route. |

GATE 20's own "What would unblock a re-lock" list is the authoritative sequence (items 1–5 in that document).

---

## 7. Mandatory Stop Conditions Verified

- **Prerequisite GATE 20: not implemented, not locked.** ✔ verified in docs and source.
- **Essential backend lifecycle semantics unresolved (identity, PC route, cleanup policy).** ✔ verified in GATE 20 §Phase B.
- **21B hard blocker stated.** ✔ verified in `GATE_21B_CONTRACT_LOCK.md`.

Result: **`GATE 21L: BLOCKED` — `IMPLEMENTATION AUTHORIZED: NO`.**

---

## 8. Next Step

1. Return to **GATE 20**. Resolve B1–B6 and produce a locked contract. The lock must include the PC route, the identity path, the lifecycle ledger design, and the product decision on cleanup.
2. After GATE 20 is locked and implemented, re-open GATE 21L against this report. Re-run the prerequisite validation from scratch.
3. Do not start GATE 21M from this report. GATE 21M depends on 21L's handoff contracts, which do not exist.

---

## 9. Gate Lock Decision

```text
GATE 21L:                           BLOCKED
CUSTOM STUDY BINDING:               NOT_IMPLEMENTED (BLOCKED)
BACKEND IMMUTABILITY:               NOT_APPLICABLE (no binding exists)
FILTERED RESOURCE IDENTITY:         BLOCKED (B3)
CAPABILITY HONESTY:                 PASS (no capability was advertised; none was added)
SCHEDULER OWNERSHIP:                NOT_APPLICABLE (no Custom Study scheduler path)
RATING / REVIEWER ACTION:           NO_CHANGE (GATE 11 / GATE 13 untouched)
CREATE / REBUILD / END:             BLOCKED (B1, B2, B4, B5)
LIFECYCLE AMBIGUITY:                BLOCKED (B4)
RESTORATION:                        BLOCKED
COLLECTION SAFETY:                  UNCHANGED (AnkiDroid fail-open risk, GATE_21A risk 5, remains open)
STALE CALLBACK ISOLATION:           NOT_APPLICABLE
ARCHITECTURE AUDIT:                 BLOCKED
VERIFICATION:                       BLOCKED
BUILD:                              NOT_RUN (no code change; Gradle environmentally blocked in prior gates)
NEXT AUTHORIZED SUB-GATE:           NONE (return to GATE 20)
```

Files changed by this gate: `docs/GATE_21L_BLOCKED_REPORT.md` (new, documentation only). No production code, test, or build file was modified.
