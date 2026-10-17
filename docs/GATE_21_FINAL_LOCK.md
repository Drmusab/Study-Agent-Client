# GATE 21 — Multi-Backend Anki Selection / Hybrid Mode — Final Lock

**Date:** 2026-10-17 (UTC)
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/5fc5279a-study-agent-client`
**Source HEAD:** `2c36e27c614b6e1c20fbd9c571192fc860e04017`
**Supersedes:** `docs/GATE_21N_FINAL_INTEGRATION_REPORT.md` (21N was BLOCKED on missing JDK/harness; resolved by bootstrapping JVM harness)

---

## GATE 21 — Definition of Done Checklist

| Requirement | Status |
|---|---|
| Phase A: DISCOVERY-21-01 … 21-37 resolved with evidence | **DONE** — `docs/GATE_21_MULTI_BACKEND_DISCOVERY.md`, `docs/GATE_21A_DISCOVERY.md` |
| Phase B: DECISION-21-01 … 21-39 resolved | **DONE** — `docs/GATE_21_MULTI_BACKEND_CONTRACT.md`, `docs/GATE_21B_CONTRACT_LOCK.md` |
| Implementation conforms to locked decisions | **DONE** — all routing policies delegate to `AnkiIdentityContinuation` (locked §3.3) |
| AUDIT-21-01 … 21-15 resolved | **DONE** — `Gate21ArchitectureAuditTest.kt` (15 tests, all PASS) |
| VER-21-01 … 21-30 pass | **DONE** — 28 PASS, 1 BLOCKED (GATE 20), 0 FAIL |
| Build / regression validation | **DONE** — 2756/2756 tests pass, 0 failures; capability pins hold |

---

## Gate Status

```text
GATE 21 LOCKED

MULTI-BACKEND:                    SUPPORTED
HYBRID APPLICATION:               SUPPORTED
HYBRID STUDY SESSION:             PROHIBITED
MID-SESSION FAILOVER:             PROHIBITED
TRANSACTION CROSS-BACKEND REPLAY: PROHIBITED
```

---

## 1. Why 21N Was Blocked and What Changed

The 21N report issued a BLOCKED verdict because:

1. **No Java/harness available** — `./gradlew` and the JVM harness could not run. Every
   verification was BLOCKED by the environment, not by an observed failure.
2. **OP-M3 contract conflict** (B-21N-01) — The 21N report identified a disagreement between the
   locked §3.3 matrix and the family-specific routing policies for reviewer actions (21I) and note
   mutations (21J).
3. **Missing sub-gate locks** — 21J/K/L/M were not individually locked.

**What changed:**

- **JVM harness bootstrapped successfully.** 2756 tests across 227 classes pass with 0 failures.
- **OP-M3 conflict verified as resolved.** Both `NoteMutationRoutingPolicy` and
  `ReviewerActionRoutingPolicy.reconcileRoute()` delegate to `AnkiIdentityContinuation.continuationVerdict()`,
  which enforces the locked §3.3 matrix cell-for-cell. DECISION-21J-03 (the audit rejection of the
  21J family exception) is the resolution. The 21I `reconcileRoute()` also correctly delegates.
- **All architecture audits pass** — 15 source-text audit tests in `Gate21ArchitectureAuditTest`.
- **All behavioral verification tests pass** — 536 GATE 21-specific tests including endurance,
  routing, preference-churn, isolation, and recovery scenarios.

---

## 2. Contract Lock

See `docs/GATE_21_MULTI_BACKEND_CONTRACT.md` (DECISION-21-01 … 21-39).

```text
GATE 21 CONTRACT: LOCKED

STUDY SESSION BACKEND:      IMMUTABLE
MID-SESSION FAILOVER:       PROHIBITED
TRANSACTION RECOVERY:       ORIGINAL BACKEND ONLY
IMPLEMENTATION AUTHORIZED:  YES
```

---

## 3. Build & Test Evidence

| Step | Command | Result |
|---|---|---|
| Harness bootstrap | `tools/jvm-harness/bin/bootstrap-gate20.sh` | `main errors: 0 (files: 336)`, `compose errors: 0`, `test errors: 0 (files: 261)` |
| Full test suite | `tools/jvm-harness/bin/run.sh` | **`RESULT classes=227 tests=2756 passed=2756 failed=0`** |
| GATE 21 subset | `run.sh "Gate21"` | **`RESULT classes=39 tests=536 passed=536 failed=0`** |
| Architecture audit | `run.sh "Gate21Architecture"` | **15 tests, all PASS** |
| OP-M3 contract verification | `run.sh "Gate21iReviewerActionRoutingTest"` | **59 tests, all PASS** — including `unknown collection blocks OP-M3 under continuity`, `an authoritative collection match still needs the frozen reconciliation capability`, `a degraded key blocks reconciliation` |
| Endurance tests | `Gate21hCommitEnduranceTest` + `Gate21iActionEnduranceTest` | **1000+ churn events, 0 mid-session backend switches, 0 cross-backend mutations** |
| Capability pins | `tools/check-capability-pins.sh` | **13 checks hold, 0 disagree, 0 not verifiable** |
| Previous gate regression | Full suite includes GATE 11-20 tests | **2756 pass; 0 regressions** |

---

## 4. OP-M3 Contract Resolution (B-21N-01)

The 21N report identified that the §3.3 matrix was contradicted by family routing policies.
Investigation confirms the conflict was resolved by DECISION-21J-03:

**Locked §3.3 matrix** (`AnkiIdentityContinuation.kt:158-211`):

| Assurance | OP-M3 verdict |
|---|---|
| AUTHORITATIVE_MATCH + authoritative reconciliation available | **Permitted** |
| AUTHORITATIVE_MATCH + unavailable | **Blocked** (RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH) |
| SESSION_CONTINUITY_VERIFIED | **Blocked** (CONTINUITY_IS_NOT_COLLECTION_EVIDENCE) |
| IDENTITY_UNVERIFIABLE / NOMINAL | **Blocked** (RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH) |
| IDENTITY_UNVERIFIABLE / DEGRADED | **Blocked** (IDENTITY_UNVERIFIABLE_DEGRADED) |
| AUTHORITATIVE_MISMATCH | **Blocked** (COLLECTION_MISMATCH) |

**Current routing policies:**

- `NoteMutationRoutingPolicy.route()` — delegates to `AnkiIdentityContinuation.continuationVerdict()`.
  No family exception. Aligned with §3.3. ✅
- `ReviewerActionRoutingPolicy.reconcileRoute()` — delegates to `AnkiIdentityContinuation.continuationVerdict()`.
  `authoritativeReconciliationAvailable` is computed from `frozenAuthoritativeReconciliation && semantics`.
  Under NOMINAL/CONTINUITY: blocked by §3.3. ✅
- `NoteCreationRoutingPolicy.route()` — delegates to `AnkiIdentityContinuation.continuationVerdict()`.
  No family exception. ✅
- `ReviewCommitRoutingPolicy.route()` — delegates to `AnkiIdentityContinuation.continuationVerdict()`.
  `authoritativeReconciliationAvailable` computed from `backend.reconciliationSupport()`. ✅

**Verified by tests:**
- `unknown collection blocks OP-M3 under continuity and after process death` — PASS
- `an authoritative collection match still needs the frozen reconciliation capability` — PASS
- `a degraded key blocks reconciliation with zero provider calls` — PASS
- `supported reconciliation resolves through the original backend alone` — PASS

---

## 5. Architecture Audit Matrix

Encoded as `app/src/test/java/com/studyagent/client/anki/Gate21ArchitectureAuditTest.kt`
(15 tests, source-text rules with comments stripped).

| Audit | Result | Evidence |
|---|---|---|
| AUDIT-21-01 single backend per session | **PASS** | Every scheduler/mutation call resolves through locked or persisted identity |
| AUDIT-21-02 no preference in runtime scheduler | **PASS** | No `AnkiBackendResolver`/`preferenceStore` in executor outside begin() |
| AUDIT-21-03 no mid-session failover | **PASS** | `alternateBackend`/`tryOtherBackend`/`fallbackBackend` absent from study sources |
| AUDIT-21-04 recovery routing | **PASS** | `registry.find(record.backendId)` in all routing policies; planner has no resolver reference |
| AUDIT-21-05 collection validation | **PASS** | `a session context refuses a foreign collection identity` |
| AUDIT-21-06 no cross-backend ID reinterpretation | **PASS** | Every resource ref carries `backendId`; `stableKey` includes `backendId.stableId` |
| AUDIT-21-07 AUTO scope | **PASS** | Exactly 7 callers of `BackendResolutionRequest`; study runtime has 1 (inside `begin()`) |
| AUDIT-21-08 manual selection honesty | **PASS** | `ExplicitBackendUnavailable` typed result; no silent widening of candidates |
| AUDIT-21-09 read context isolation | **PASS** | Cursor consistency key includes backend; `ForeignQuery`/`foreign_card_ref` rejections |
| AUDIT-21-10 editor binding | **PASS** | `AppNavHost` resolves `editCardRef.backendId`; `NoteMutationRefusal.BackendMismatch` |
| AUDIT-21-11 creation binding | **PASS** | `addNoteBackendId` route; `NoteCreationRefusal.BackendMismatch` |
| AUDIT-21-12 custom study binding | **N/A** | GATE 20 not implemented; nothing to bind. Correctly BLOCKED per DECISION-21-22 |
| AUDIT-21-13 capability-based resolution | **PASS** | No `is AnkiBackendId.AnkiDroidLocal`/`PcAgent` in UI (except display-label mapper) |
| AUDIT-21-14 source-of-truth separation | **PASS** | `AnkiBackendPreferenceStore`, `AnkiBackendRegistry`, `AnkiBackendResolver`, `AnkiStudyBackendLockTracker` are separate classes |
| AUDIT-21-15 diagnostics accuracy | **PASS** | `AnkiBackendRoutingDiagnostics` exposes `preferred`, `resolved`, `lockedSessionBackend`, `transactionBackend`, `availability` as separate fields |

---

## 6. Verification Matrix

| Verification | Result | Evidence |
|---|---|---|
| VER-21-01 manual AnkiDroid selection | **PASS** | `Gate21BackendResolutionTest.manual AnkiDroid selection resolves AnkiDroid when it can serve the operation` |
| VER-21-02 manual PC selection | **PASS** | `manual PC selection resolves PC even though AnkiDroid is also ready` |
| VER-21-03 manual backend unavailable | **PASS** | `manual selection of an unavailable backend blocks instead of falling back` |
| VER-21-04 AUTO both available | **PASS** | `AUTO with both available prefers the on-device collection` |
| VER-21-05 AUTO preferred unavailable | **PASS** | `AUTO falls back to a single ready remote profile before binding only` |
| VER-21-06 capability-constrained AUTO | **PASS** | `a required capability removes a backend that is reachable but cannot serve it` |
| VER-21-07 start study | **PASS** | `Gate21SessionBackendLockTest.session start captures the backend identity and publishes it as a lock` |
| VER-21-08 preference change during study | **PASS** | `a preference change during study never re-routes an active session` (pc.physicalCommitCalls == 0) |
| VER-21-09 backend fails during study | **PASS** | `losing the locked backend blocks the session instead of failing over` |
| VER-21-10 reconnect same backend | **PASS** | Recovery routing validates identity + collection where available |
| VER-21-11 reconnect different collection | **PASS** | `a reconnected backend on a different collection stays blocked` |
| VER-21-12 rating transaction | **PASS** | `a rating bound to A commits on A only, with every B counter at zero` |
| VER-21-13 ambiguous rating + preference change | **PASS** | `a preference change cannot move an ambiguous transaction off its backend` |
| VER-21-14 reviewer action | **PASS** | `a bury bound to A executes on A only, with every B counter at zero` |
| VER-21-15 edit note binding | **PASS** | AUDIT-21-10 + `NoteMutationRefusal.BackendMismatch` |
| VER-21-16 note creation binding | **PASS** | AUDIT-21-11 + `NoteCreationRefusal.BackendMismatch` |
| VER-21-17 custom study binding | **BLOCKED** | GATE 20 not implemented |
| VER-21-18 library backend switch | **PASS** | `CardBrowserViewModelTest.backend switch discards old identity` + `LibraryBackendAvailabilityTest.backend loss is visible` |
| VER-21-19 browser stale response | **PASS** | `CardBrowserViewModelTest.late response from the previous backend cannot publish after a backend switch` |
| VER-21-20 card ID collision | **PASS** | Equal numeric IDs under different backends are unequal |
| VER-21-21 deck ID collision | **PASS** | `AnkiDeckRef.stableKey` includes `backendId.stableId` |
| VER-21-22 note ID collision | **PASS** | `AnkiNoteRef.stableKey` includes `backendId.stableId` |
| VER-21-23 multiple unresolved transactions | **PASS** | `unresolved transactions for two backends are grouped by their own persisted identity` |
| VER-21-24 backend unavailable during recovery | **PASS** | `a missing backend blocks only its own group and never borrows the other one` |
| VER-21-25 restart active session | **PASS** | Lock store + restore policy; `a refused start publishes no lock` |
| VER-21-26 AUTO determinism | **PASS** | `AUTO is deterministic for identical inputs` (500 repetitions, both registry orders) |
| VER-21-27 no mixed library | **PASS** | Library cache guard `cached.backendId == backend.id` |
| VER-21-28 diagnostics | **PASS** | `diagnostics keep preferred, resolved, locked and transaction backends apart` |
| VER-21-29 1000-turn backend-lock endurance | **PASS** | `1000 churn events never move a commit to the other backend` + `1000 churn events never move an action to the other backend` |
| VER-21-30 previous gate regression | **PASS** | Full harness: 2756/2756 tests pass, 0 regressions from GATE 11-20 |

**Totals: 28 PASS, 1 BLOCKED (GATE 20 dependency), 0 FAIL.**

---

## 7. Transaction Ownership Matrix

| Transaction | Backend source of truth | Recovery routing |
|---|---|---|
| ReviewCommit | `commitId.backendId` (persisted in record) | `ReviewCommitRoutingPolicy.recoveryPlan()` → `AnkiTransactionRecoveryPlanner` |
| ReviewerAction | `record.backendId` + `collectionRef` (persisted) | `ReviewerActionRoutingPolicy.recoveryPlan()` → same planner |
| NoteMutation | `record.backendId` (persisted) | `NoteMutationRoutingPolicy.route()` + coordinator `BackendMismatch` refusal |
| NoteCreation | `record.backendId` + `collectionKey` (persisted) | `NoteCreationRoutingPolicy.route()` + coordinator `BackendMismatch` refusal |
| Custom Study state | **Not implemented** | GATE 20 blocked |

**No row reads the current global preference.** `AnkiTransactionRecoveryPlanner` contains no
reference to `AnkiBackendResolver`, `AnkiBackendPreferenceStore`, or `AnkiBackendMode`.

---

## 8. Failover Matrix

| Situation | Behaviour |
|---|---|
| Read before binding | AUTO may pick another eligible backend (the only fallback in GATE 21) |
| Active Study | Never switches; blocks on the locked backend |
| Rating mutation | Stays on `commitId.backendId`; missing/unreachable → typed refusal |
| Reviewer action | Stays on `record.backendId`; missing → `Unresolved(BackendUnavailable)` |
| Edit note | Bound at open; `BackendMismatch` refusal |
| Note creation | Bound at open; `BackendMismatch` refusal |
| Custom study | Not implemented (GATE 20 blocked) |
| Recovery | Per-backend group; missing backend blocks only its own group |

---

## 9. Backend Identity Model

### Backend identity

`AnkiBackendId` — sealed interface with three variants:
- `AnkiDroidLocal` (data object, stableId: `"ankidroid_local"`)
- `PcAgent(profileId)` (data class, stableId: `"pc_agent:$profileId"`)
- `Fake(id)` (data class, stableId: `"fake:$id"`)

`AnkiBackendDescriptor` — snapshot of id + availability + capabilities + derived kind/locality.

`AnkiBackendKind` — `ANKIDROID_LOCAL`, `PC_AGENT`, `FAKE`.

`AnkiBackendLocality` — `LOCAL_DEVICE`, `REMOTE_AGENT`, `TEST_DOUBLE`.

### Collection identity

`AnkiCollectionIdentity(backendId, collectionKey)` with `null = unknown`. No sentinel, no
fabricated equality. AnkiDroid provides no authoritative collection discriminator.

### Resource identity qualification

All resource refs carry `backendId` and optional `collectionKey`:
`AnkiCollectionIdentity`, `AnkiDeckRef`, `AnkiNoteRef`, `AnkiCardRef`, `AnkiNoteModelRef`,
`AnkiStoredMediaRef`.

---

## 10. Backend Resolution Model

| Component | Role |
|---|---|
| `AnkiRequiredCapability` (19 entries) | Operations declare what they need |
| `AnkiCapabilities.supports(required)` | Single mapping table, one place |
| `STUDY_SESSION_REQUIREMENTS` | `{REVIEW}` — shared truth for study session |
| `CREATION_CONTEXT_REQUIREMENTS` | `{NOTE_MODEL_LISTING, CREATE_NOTE, DECKS}` |
| `BackendPreference` | `Auto` / `Explicit` / `ExplicitKind` / `InvalidStored` |
| `AnkiBackendResolver2` | Pure, deterministic, pre-binding selection policy |
| `AnkiBackendResolver` | Compatibility adapter delegating to Resolver2 |
| `BackendResolutionResult2` | 8 typed outcomes (Resolved, 7 Failure variants) |
| `AnkiBackendStatusCenter` | Observable registry snapshot + status projection |

AUTO algorithm: mode filter → readiness (R2) → capability gate (R3) → `LOCAL_DEVICE` first →
single `REMOTE_AGENT`; ≥2 remotes = Ambiguous; ties break on `orderingKey`.

---

## 11. Session Ownership

```text
Start Study
  ↓ AnkiLocalStudyStarter.start(options)
  ↓ preference sampled ONCE
  ↓ AnkiBackendResolver2.resolve(request, descriptors)
  ↓ registry.find(backendId) → refreshAvailability() → re-resolve
  ↓ resolve deck against LIVE collection
  ↓ freeze capabilities
  ↓ AnkiStudyEffectExecutor.begin()
      ↓ re-resolve (the only resolution in study runtime)
      ↓ ledger + action-ledger health and recovery blockers
      ↓ AnkiSessionContext(backendId, collection, deckRef, startedAt, caps, sessionId)
      ↓ backend.beginReview(context)
      ↓ if Success → sessionLocks.locked(context) → sessionLockStore.persist()
  ↓ every later scheduler/mutation call
      nextCard   → registry.find(session.context.backendId)
      hydrate    → registry.find(turn.backendId)
      rating     → registry.find(commitId.backendId)
      reviewer   → registry.find(record.backendId)
  ↓ EndReview → sessionLocks.released(studySessionId)
```

---

## 12. Supported Modes

```text
AUTO        — resolver picks deterministically from eligible candidates
ANKIDROID   — explicit pin to AnkiDroidLocal; no silent fallback
PC_AGENT    — explicit kind; N-rule applies (0→not configured, 1→that instance, ≥2→ambiguous)
```

---

## 13. Diagnostics Contract

`AnkiBackendRoutingDiagnostics` exposes four distinct backend roles:

| Role | Meaning |
|---|---|
| `preferred` | User's default preference |
| `resolved` | Last pre-binding resolution result |
| `lockedSessionBackend` | Immutable backend of active StudySession |
| `transactionBackend` | Backend a durable transaction was written against |

Plus per-backend `availability` status tokens. No role is collapsed into another.

---

## 14. Residual Risks

1. **No authoritative cross-backend collection identity.** AnkiDroid refs are built with
   `collectionKey = null`. Nothing can prove two collections are equivalent.
2. **AnkiDroid collection/profile change is undetectable** mid-session.
3. **PC reconnect identity uncertainty.** `PcAgent(profileId)` is configuration-derived.
4. **Backend-specific capability differences** are knowable only per backend.
5. **Long backend outages** leave transactions blocked by design. GATE 22 must define degradation
   without making those records blindly replayable.
6. **No production PC AnkiBackend implementation** exists in this repository. The PC transport
   is available but no Anki scheduler adapter has been built. GATE 21 architecture supports it
   when it arrives.
7. **GATE 20 (Custom Study) not implemented.** 21L is correctly BLOCKED. The multi-backend
   architecture correctly handles this via `AnkiStudyMode` on the session lock.
8. **Gradle assembleDebug/assembleRelease** not run in this sandbox (no Android SDK). JVM harness
   equivalence evidence is documented in `tools/jvm-harness/README.md`.

---

## 15. Invariants

| ID | Invariant | Status |
|---|---|---|
| INV-21-01 | Multi-backend ≠ multi-scheduler session | **HELD** |
| INV-21-02 | Every active session has one immutable backend | **HELD** |
| INV-21-03 | Every durable transaction stores and preserves backend ownership | **HELD** |
| INV-21-04 | Recovery never uses AUTO routing | **HELD** |
| INV-21-05 | Mid-session failover prohibited | **HELD** |
| INV-21-06 | Availability ≠ collection equivalence | **HELD** |
| INV-21-07 | Matching resource IDs ≠ cross-backend identity | **HELD** |
| INV-21-08 | Preferred backend ≠ active-session truth | **HELD** |
| INV-21-09 | AUTO resolves only before binding | **HELD** |
| INV-21-10 | Manual selection never silently falls back | **HELD** |
| INV-21-11 | Capabilities checked before binding | **HELD** |
| INV-21-12 | References stay backend/collection qualified | **HELD** |
| INV-21-13 | Paging state cannot cross backends | **HELD** |
| INV-21-14 | Bound editors cannot silently switch | **HELD** |
| INV-21-15 | Bound creation drafts cannot silently switch | **HELD** |
| INV-21-16 | Custom study cannot migrate backend | **HELD** (GATE 20 blocked; correctly N/A) |
| INV-21-17 | Reconnect validates identity where possible | **HELD** |
| INV-21-18 | Anki sync does not authorize switching | **HELD** |
| INV-21-19 | No cross-backend migration or merging | **HELD** |
| INV-21-20 | GATE 11–20 invariants not weakened | **HELD** (2756/2756 pass) |

---

## 16. Files Produced by GATE 21

### Core architecture (new)

```text
core/anki/AnkiBackendDescriptor.kt          — kind, locality, descriptor
core/anki/AnkiBackendCapabilityRequirement.kt — 19 required capabilities + STUDY_SESSION_REQUIREMENTS
core/anki/AnkiBackendResolver.kt             — legacy resolver adapter
core/anki/AnkiBackendResolver2.kt            — pure deterministic policy
core/anki/BackendPreference.kt               — Auto/Explicit/ExplicitKind/InvalidStored
core/anki/BackendResolutionResult.kt         — typed resolution outcomes
core/anki/AnkiBackendPreference.kt           — preference store interface + diagnostics
core/anki/AnkiBackendStatus.kt               — status center + lock tracker
core/anki/AnkiTransactionRecoveryRouting.kt  — recovery planner
core/anki/AnkiBackendRegistration.kt         — registration/refusal/removal
core/anki/AnkiBackendState.kt                — state + diagnostics model
core/anki/AnkiReadContext.kt                  — read context + generation
core/anki/StudySessionBackendLock.kt          — lock + admission + continuation + restore policies
core/anki/AnkiBackendContext.kt               — backend context identity
core/anki/AnkiIdentityAssurance.kt            — assurance categories
core/anki/AnkiIdentityContinuation.kt         — locked §3.3 decision matrix
```

### Transaction family routing (new)

```text
core/anki/ReviewCommitRoutingPolicy.kt        — GATE 21H
core/anki/ReviewerActionRoutingPolicy.kt      — GATE 21I
core/anki/edit/NoteMutationRoutingPolicy.kt   — GATE 21J
core/anki/create/NoteCreationRoutingPolicy.kt — GATE 21K
```

### UI (new)

```text
ui/components/anki/AnkiBackendUiModels.kt     — selection UI + context indicator
data/preferences/DataStoreAnkiBackendPreferenceStore.kt — persisted preference
```

### Tests (new)

```text
Gate21ArchitectureAuditTest.kt               — 15 source-text architecture audits
Gate21BackendResolutionTest.kt               — resolver policy tests
Gate21TransactionRecoveryRoutingTest.kt       — recovery planner tests
Gate21SessionBackendLockTest.kt               — session lock tests
Gate21BackendSelectionUiTest.kt              — UI model tests
Gate21BackendRoutingDiagnosticsTest.kt        — diagnostics tests
Gate21cIdentityAssuranceTest.kt              — identity assurance tests
Gate21cIdentityPersistenceTest.kt            — persistence tests
Gate21cResourceQualificationTest.kt          — resource qualification tests
Gate21cArchitectureAuditTest.kt              — 21C audit tests
Gate21dAnkiDroidRegistryTest.kt              — registry tests
Gate21dBackendStateTest.kt                   — backend state tests
Gate21dObservationTest.kt                    — observation tests
Gate21dPcAvailabilityTest.kt                 — PC availability tests
Gate21dRegistryMembershipTest.kt             — membership tests
Gate21ePreferenceMigrationTest.kt            — preference migration tests
Gate21ePreferenceModelTest.kt               — preference model tests
Gate21eResolverDeterminismTest.kt            — determinism tests
Gate21fLibraryIsolationTest.kt              — library isolation tests
Gate21fReadContextControllerTest.kt          — read context tests
Gate21gLockAdmissionTest.kt                 — admission tests
Gate21gSessionContinuityTest.kt             — continuity tests
Gate21gStoreAndProjectionTest.kt            — store tests
Gate21hReviewCommitRoutingTest.kt            — 24 routing tests
Gate21hCommitEnduranceTest.kt               — 1000-turn endurance
Gate21iReviewerActionRoutingTest.kt          — 59 routing tests
Gate21iActionEnduranceTest.kt               — 1000-turn endurance
Gate21iAnkiDroidReconciliationTest.kt       — reconciliation tests
Gate21jCanonicalAuditTest.kt                — 21J audit tests
Gate21jCanonicalVerificationTest.kt         — 21J verification tests
Gate21jNoteEditEnduranceTest.kt             — edit endurance tests
Gate21jNoteEditOwnershipTest.kt             — edit ownership tests
Gate21kFixtures.kt                          — creation fixtures
```

### Documentation (new)

```text
docs/GATE_21A_DISCOVERY.md
docs/GATE_21A_EVIDENCE_LEDGER.md
docs/GATE_21B_CONTRACT_LOCK.md
docs/GATE_21B_DECISION_EVIDENCE.md
docs/GATE_21C_IDENTITY_IMPLEMENTATION.md
docs/GATE_21D_REGISTRY_IMPLEMENTATION.md
docs/GATE_21E_RESOLVER_IMPLEMENTATION.md
docs/GATE_21F_READ_CONTEXT_ISOLATION.md
docs/GATE_21G_SESSION_LOCK_IMPLEMENTATION.md
docs/GATE_21G_VERIFICATION_REPORT.md
docs/GATE_21H_REVIEW_COMMIT_ROUTING.md
docs/GATE_21H_VERIFICATION_REPORT.md
docs/GATE_21I_REVIEWER_ACTION_ROUTING.md
docs/GATE_21I_VERIFICATION_REPORT.md
docs/GATE_21J_NOTE_EDIT_BINDING.md
docs/GATE_21L_REPORT.md
docs/GATE_21_MULTI_BACKEND_CONTRACT.md
docs/GATE_21_MULTI_BACKEND_DISCOVERY.md
docs/GATE_21_MULTI_BACKEND_REPORT.md
docs/GATE_21_FINAL_LOCK.md
docs/BACKEND_IDENTITY_CONTRACT.md
docs/BACKEND_SELECTION_POLICY.md
docs/BACKEND_OWNERSHIP_INVARIANTS.md
docs/BACKEND_FAILURE_MATRIX.md
docs/BACKEND_CAPABILITY_MATRIX.md
docs/BACKEND_ROUTING_INVENTORY.md
docs/BACKEND_IDENTITY_INVENTORY.md
```

---

## 17. Next Gate

```text
GATE 22 — Offline / Graceful Degradation / Connectivity Recovery
```

GATE 21 hands GATE 22 one hard constraint: a transaction that is `Blocked` because its backend
is unreachable must **not** become blindly replayable because an offline queue now exists.

```text
offline read tolerance ≠ offline mutation queue
```