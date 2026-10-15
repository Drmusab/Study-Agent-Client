# GATE 21D — Backend Registry, Availability & Capabilities (Implementation)

> **Re-verified 2026-10-15 at HEAD `25cc924`.** `docs/GATE_21D_REVERIFICATION.md` re-executes
> AUDIT-21D-01…16 and VER-21D-01…26 against the current tree (focused 99/99, full suite 2629/2629)
> and confirms this lock **unchanged** — the registry, state, taxonomy, observer, PC structure,
> adapter and every `Gate21d*` test are byte-identical to the landing commit, apart from one additive
> `AnkiBackend.kt` default introduced later by GATE 21F. This document remains the authoritative lock
> record for the gate's original execution; that one is the current-tree verification.

**Status:** implementation complete and harness-verified — PART V Gradle tasks `BLOCKED — environmental limitation`.
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/36750f6f-study-agent-client`
**HEAD:** `e5d9abbdd8a6ad3823ca9c0c268295e4d1ffa3b9` (`e5d9abb Merge pull request #66 from Drmusab/arena/39e7739c-study-agent-client` — GATE 21C)
**Change set:** 10 modified + 4 new production files, 8 new test files — implemented and verified as an uncommitted working tree; committed and opened as a pull request afterwards, at the branch owner's explicit request (see PART VIII)
**Date:** 2026-10-12
**Execution type:** implementation (registry state, availability, capability truth) + executed verification
**Prior gates:** GATE 21A `PASS / LOCKED`; GATE 21B `LOCKED` (contract authority); GATE 21C `LOCKED` (identity foundation)

```text
GATE 21D — BACKEND REGISTRY, AVAILABILITY & CAPABILITIES

READ THIS FIRST (three things that are not obvious from the diff)

 1. A PRE-EXISTING PRODUCTION DEFECT WAS FOUND AND FIXED  (D-21D-01, Appendix A)
    Driving the REAL AnkiDroid adapter chain into the EXISTING resolver refused a Ready, fully
    wired AnkiDroid with RequiredCapabilityUnavailable(REVIEW). The fix is ~10 lines in
    AnkiDroidBackend. It makes AnkiDroid study start, the dashboard "study on this phone" pre-flight
    and the Library "Study" label reachable for the first time. Rating commits are still
    IMPLEMENTED-not-VERIFIED (GATE 11 real-device verification is outstanding).

 2. NO PC ANKI BACKEND EXISTS — every PC result here is STRUCTURE-LEVEL
    The PC readiness ladder is verified against the real ConnectionState with explicit fixtures.
    Nothing in production constructs it, so no PC state can reach the registry (audited).
    "PASS†" below means exactly that and never "a PC backend works".

 3. GRADLE WAS NOT RUN (cannot be, here). The repo's JVM harness (Kotlin 2.3) was used.
    Compiled/tested with K2, not with the Gradle toolchain (Kotlin 1.9.24). CI must run before
    any release-style claim (R-21D-02).
```

**Deliverables in this gate**

| Artifact | Path | Contents |
|---|---|---|
| This report | `docs/GATE_21D_REGISTRY_IMPLEMENTATION.md` | PART 0, 21D-00, PART I-A, II, III, IV, V, VI, VII, VIII + Appendices A–D |
| Failure taxonomy | `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendUnavailableReason.kt` | 10 reasons, retryability, total `AnkiAvailability.unavailableReason()` |
| Registration lifecycle | `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendRegistration.kt` | identity gate (A3/A4/A5), typed lifecycle results, immutable membership snapshot |
| Backend state | `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendState.kt` | state, implemented-vs-operational capability model, diagnostics projection |
| Registry (extended in place) | `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendSelector.kt` | `AnkiBackendRegistry` + lifecycle + state lookup + read-only refresh |
| Observation point | `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendStatus.kt` | `AnkiBackendStatusCenter.states` follows membership |
| PC readiness structure | `app/src/main/java/com/studyagent/client/data/anki/remote/PcAnkiAvailabilitySource.kt` | pure ladder over `ConnectionState` + session-bound evidence — **no producer** |
| Verification | `app/src/test/java/com/studyagent/client/anki/Gate21d*.kt` (6 test classes, 99 tests, 2 helpers) | VER-21D-01…26, AUDIT-21D-01…16 |

---

# PART 0 — PREREQUISITE VALIDATION

## 0.1 Required checks (executed, not assumed)

```text
Current HEAD:        e5d9abbdd8a6ad3823ca9c0c268295e4d1ffa3b9
Branch:              arena/36750f6f-study-agent-client   (branched from master @ that commit)
Working-tree status: clean at the start (git status: no changes)

21A: LOCKED   docs/GATE_21A_DISCOVERY.md — PASS, audits 10/10, verifications 10/10
21B: LOCKED   docs/GATE_21B_CONTRACT_LOCK.md — DECISION-21B-01…30, handoff §PART 4 (21D entry)
21C: LOCKED   docs/GATE_21C_IDENTITY_IMPLEMENTATION.md — identity foundation; code is in HEAD
```

| 21C artifact required by 21D | Present at HEAD | Used by 21D |
|---|---|---|
| `AnkiBackendId` (+ `roundTripsStableId`, `discriminatorIsNormalized`) | `core/anki/AnkiBackendId.kt` | registration gate (`AnkiBackendId.registrationRefusal()`) |
| `AnkiBackendContext` + assurance vocabulary | `core/anki/AnkiBackendContext.kt`, `AnkiIdentityAssurance.kt` | reconnect test only (proves the registry asserts nothing about continuity) |
| Test baseline | 5 `Gate21c*` classes | full suite re-run: **193 classes / 2269 tests / 0 failed**, identical to the 21C report |

**Prerequisite verdict: PASS.** `IMPLEMENTATION AUTHORIZED: YES` for what 21B's handoff authorizes 21D to do.

## 0.2 What the locked contract authorizes — and forbids — for 21D

`GATE_21B_CONTRACT_LOCK.md` §PART 4, entry 21D, read in full before any edit:

- **Reuse** `AnkiBackendRegistry`, `AnkiBackendStatusCenter`, `AnkiAvailability` (11 states, 3 reserved for PC), `AnkiBackendDescriptor`.
- **DECISION-21B-10:** *"Create a new BackendRegistry in 21D — Rejected: a second registry is a second source of truth."* The registry is **extended**. The prompt's illustrative `DefaultAnkiBackendRegistry` / registry interface were therefore **not created** (the pinned location is also asserted by `Gate21ArchitectureAuditTest`).
- **BLOCKER:** *"the PC Anki backend does not exist. Until it does, 21D can only prepare registry/availability structure for a second instance; it must not fabricate a PC `Ready` state."*
- Five REQUIRED DECISIONS, answered in Appendix B.

## 0.3 Evidence that shaped the design (all measured)

| # | Finding | Evidence |
|---|---|---|
| E1 | The pre-split GATE 21 already shipped a registry, a pure resolver, a status center, a preference store and a descriptor | `core/anki/AnkiBackendSelector.kt`, `AnkiBackendResolver.kt`, `AnkiBackendStatus.kt`, `AnkiBackendPreference.kt` |
| E2 | No PC Anki backend type is declared or constructed; no Anki message in the agent protocol | no `PcAnkiBackend` declaration or `PcAnkiBackend(` call in `app/src/main` code (comments aside); `AUDIT-21D-14` re-asserts it with comments stripped |
| E3 | The PC *agent connection* already distinguishes transport, authentication, protocol and readiness | `core/models/ConnectionState.kt` (20 variants: `NetworkUnavailable`, `AuthenticationFailed`, `ProtocolMismatch`, `Ready(authenticated, protocolVersion, agentId…)`) — and it carries host, port and free-text reasons that must never leak |
| E4 | The real AnkiDroid chain published `Ready(<all false>)` next to `review = true` | Appendix A |
| E5 | `AnkiAvailability` is matched exhaustively, with user-facing copy, in 6 places | `AnkiDroidHealth.kt`, `AnkiBackendUiModels.kt`, `CardDetailsScreen.kt`, `LibraryUiState.kt` (×2), `SettingsScreen.kt` |
| E6 | Availability is refreshed only by consumers; the foreground hook drives a *different* owner (`AnkiDroidHealthRepository`) | `MainActivity.kt:82`, `grep refreshAvailability` — see R-21D-03 |

## 0.4 Rule compliance (the prompt's skip rules)

| Rule | Compliance |
|---|---|
| Reuse before create; no parallel implementations | Extended `AnkiBackendRegistry`, `AnkiBackendStatusCenter`, `AnkiAvailability`, `AnkiBackend`, `AnkiDroidBackend`. **Not created:** registry interface, `DefaultAnkiBackendRegistry`, `AnkiBackendCapability` enum (the locked vocabulary is `AnkiRequiredCapability`), a second descriptor, a second diagnostics format, an AnkiDroid availability source (the detector → health check → gateway chain already is one). |
| Do not construct a second PC connection or AnkiDroid adapter to register a backend | The registry holds references; `AppContainer` is **unchanged** (one registry, one `AnkiDroidBackend` construction — `AUDIT-21D-04/14`). |
| Do not fabricate PC `Ready` | No producer exists in production (`AUDIT-21D-14`); the ladder yields `Ready` only for `Ready(authenticated)` **and** an explicit Anki-ready report (property-tested over all 21 connection fixtures × 4 reports). |
| Out of scope: resolver, preference, session switching, failover, UI | Untouched (`AUDIT-21D-05/15/16`). The only UI edits are one added `when` branch (+ neutral copy) per exhaustive match, forced by the compiler. |

---

# CHECKPOINT 21D-00 — Repository & dependency mapping

Dependency direction is unchanged: `UI → ViewModel → application service → AnkiBackend (abstraction) → AnkiDroidBackend`.

| Concern | Existing owner | File | Planned action → taken |
|---|---|---|---|
| Backend construction | composition root; `ankiDroidBackend by lazy`, one instance | `di/AppContainer.kt:366` | **NO CHANGE** |
| Registry registration | `AnkiBackendRegistry(listOf(ankiDroidBackend))`, once | `di/AppContainer.kt:495-499` | **NO CHANGE** (pinned by `Gate21cArchitectureAuditTest` and re-asserted) |
| Backend identity | `AnkiBackendId` (21C) | `core/anki/AnkiBackendId.kt` | **REUSE** (gate calls its predicates) |
| Kind / locality | `AnkiBackendKind`, `AnkiBackendLocality` | `core/anki/AnkiBackendDescriptor.kt` | **REUSE** |
| Raw snapshot | `AnkiBackendDescriptor` (id, availability, capabilities) | `core/anki/AnkiBackendDescriptor.kt` | **REUSE** — stays the single raw snapshot |
| Availability vocabulary | `AnkiAvailability` | `core/anki/AnkiAvailability.kt` | **MODIFY** — +`AgentAuthenticationRequired` |
| Availability producer (AnkiDroid) | detector → health check → health probe → gateway → `AnkiDroidBackend` | `data/anki/ankidroid/*` | **REUSE**; one defect fixed in `AnkiDroidBackend` (Appendix A) |
| Availability producer (PC) | none | — | **CREATE (structure only)** `PcAnkiAvailabilitySource.kt` |
| Capability vocabulary | `AnkiCapabilities` + `AnkiRequiredCapability` (+ one `supports` mapping) | `core/anki/AnkiCapabilities.kt`, `AnkiBackendCapabilityRequirement.kt` | **REUSE** |
| Capability claim | `AnkiDroidBackend.withWriteSupport` | `data/anki/ankidroid/AnkiDroidBackend.kt` | **MODIFY** — claim exposed as `implementedCapabilities` |
| Connection state | `ConnectionState` (agent transport) | `core/models/ConnectionState.kt` | **REUSE** as the PC ladder's input; no new connection |
| Observation | `AnkiBackendStatusCenter` | `core/anki/AnkiBackendStatus.kt` | **MODIFY** — follows membership, publishes `AnkiBackendState` |
| DI / composition | `AppContainer` | `di/AppContainer.kt` | **NO CHANGE** |

---

# PART I-A — PER-CHECKPOINT IMPLEMENTATION REPORT (21D-01 … 21D-20)

Fields: CHECKPOINT · FILE · ACTION · CONTRACT REFERENCE · RESPONSIBILITY · IMPLEMENTATION · LOCAL CHECKS · ARCHITECTURE BOUNDARY · READY FOR NEXT. Assertions are cited by id from PART II/III, never restated.

### CHECKPOINT 21D-01 — Backend descriptor contract
- **FILE:** `core/anki/AnkiBackendDescriptor.kt` (read-only reference)
- **ACTION:** REUSE — **NO CHANGE**
- **CONTRACT REFERENCE:** DECISION-21B-10 (descriptors stay the single snapshot source), DECISION-21B-01 (kind ≠ instance), rule A6
- **RESPONSIBILITY:** public, identity-stable metadata: `id` (stable instance identity), `kind`/`locality` (derived, validated against `id`), availability + live capabilities (snapshot).
- **IMPLEMENTATION:** none. `displayName` was **not** added: a label is presentation, UI-owned (`AnkiBackendId.displayLabel()`), and rule A6 says a label change is not an identity change; putting copy in `core` would invent a second owner. No secret, scheduler state or Compose type exists on the descriptor (unchanged).
- **LOCAL CHECKS:** `AUDIT-21D-02/03`; descriptor still validates `kind == id.kind`.
- **ARCHITECTURE BOUNDARY:** `AnkiBackendState` *wraps* the descriptor; no field is duplicated.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-02 — Availability contract
- **FILE:** `core/anki/AnkiAvailability.kt`
- **ACTION:** MODIFY (+1 state, +1 status token)
- **CONTRACT REFERENCE:** DECISION-21B-28; `BACKEND_FAILURE_MATRIX.md` §10 (R0/R1 are ineligible for every operation)
- **RESPONSIBILITY:** make "reachable but not accepted" its own condition.
- **IMPLEMENTATION:** `data object AgentAuthenticationRequired` (token `AGENT_AUTHENTICATION_REQUIRED`), payload-free so no credential or rejection text can ride in it. Distinct from `PermissionRequired` (Android provider permission). The other PC conditions already existed: transport → `AgentDisconnected`; desktop Anki → `AgentAnkiUnavailable`; collection → `CollectionNotInitialized`; protocol/API version → `Unsupported`; plus `Checking`/`Ready`. AnkiDroid: `NotInstalled`, `ProviderUnavailable`, `PermissionRequired`, `CollectionNotInitialized`, `Unsupported`, `Ready` — all six already existed and are verified through the real chain.
- **LOCAL CHECKS:** compiles with 6 exhaustive consumers updated (E5); UI sweep error set identical to baseline (PART V). `VER-21D-09/10/11`.
- **ARCHITECTURE BOUNDARY:** one availability hierarchy; no `Offline` Boolean introduced. Transport/AI readiness never maps to `Ready` (property-tested).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-03 — Failure reason taxonomy
- **FILE:** `core/anki/AnkiBackendUnavailableReason.kt` (new, 110 lines)
- **ACTION:** CREATE
- **CONTRACT REFERENCE:** DECISION-21B-27 (content-free tokens), DECISION-21B-08 (reasons distinguishable and user-actionable)
- **RESPONSIBILITY:** typed, non-sensitive "why not usable" with a retryability classification.
- **IMPLEMENTATION:** 10 categories, each produced by a real availability state (none speculative): `APP_NOT_INSTALLED`, `PROVIDER_UNAVAILABLE`, `PERMISSION_REQUIRED`, `COLLECTION_UNAVAILABLE`, `TEMPORARILY_UNAVAILABLE`, `TRANSPORT_UNAVAILABLE`, `AUTHENTICATION_REQUIRED`, `ANKI_UNAVAILABLE`, `VERSION_INCOMPATIBLE`, `BACKEND_ERROR`. `AnkiAvailability.unavailableReason()` is a **total `when` with no `else`**: a new availability state cannot compile without a taxonomy decision. Retryability: `EXPECTED_TO_RECOVER`, `MAY_RECOVER`, `AFTER_USER_ACTION`, `UNKNOWN` — a failure nobody classified is `UNKNOWN`, **never** "retryable". `NOT_CONFIGURED` is not a reason: an unheld backend has *no state at all* (type-level, `AnkiBackendStateLookup.NotRegistered`). `Checking` has no reason (not a failure).
- **LOCAL CHECKS:** `Gate21dBackendStateTest` (taxonomy, vocabulary uniqueness, retryability); mutants A8 killed.
- **ARCHITECTURE BOUNDARY:** a projection of the existing hierarchy — not a second state machine.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-04 — Capability representation
- **FILE:** `core/anki/AnkiBackendState.kt` (+ reuse of `AnkiCapabilities`, `AnkiRequiredCapability`)
- **ACTION:** REUSE vocabulary + CREATE the per-capability status
- **CONTRACT REFERENCE:** DECISION-21B-09 (implements / ready for / permitted are three questions)
- **RESPONSIBILITY:** report capability truth along the two dimensions a registry can honestly know.
- **IMPLEMENTATION:** `AnkiCapabilityStatus(implemented: AnkiImplementedSupport, operational: AnkiCapabilityReadiness)`. *Implemented* = the application's wired adapter (static claim) — or, only for a backend with no claim and only while `Ready`, current runtime evidence. *Operational* = positive knowledge only and exactly `AnkiBackendDescriptor.supports` (Ready ∧ live ∧ probe-time projection agree — the locked fail-closed rule, not a second one). **Authorization is deliberately not a field**: it is decided inside the owning binding. The data-layer `CapabilitySupport` ("does the provider API offer it?") is a different question and is not borrowed (`AUDIT-21D-08`).
- **LOCAL CHECKS:** `VER-21D-13/14/15`; mutants A5, B8 killed.
- **ARCHITECTURE BOUNDARY:** no parallel capability enum was introduced.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-05 — Capability support status
- **FILE:** `core/anki/AnkiBackendState.kt`
- **ACTION:** CREATE
- **CONTRACT REFERENCE:** DECISION-21B-09; INV-21B-07
- **RESPONSIBILITY:** SUPPORTED / UNSUPPORTED / UNKNOWN, never optimistic.
- **IMPLEMENTATION:** `AnkiImplementedSupport {SUPPORTED, UNSUPPORTED, UNKNOWN}` and `AnkiCapabilityReadiness {READY, NOT_READY, UNKNOWN}`. The honesty invariant is enforced **at construction**: `READY` requires `SUPPORTED` (`require` in `AnkiCapabilityStatus`). The spec's two examples, both executed: AnkiDroid `CREATE_NOTE`/`FLAGS`/`MEDIA_READ`/`DECK_COUNTS`/`CARD_BROWSER` → `UNSUPPORTED` (the API may offer some; the application does not implement them); an implemented capability of an unreachable backend → `SUPPORTED + NOT_READY`, never the same condition.
- **LOCAL CHECKS:** `Gate21dBackendStateTest.a capability can never be ready without being implemented` (exhaustive over 12 availability × 4 claims × 3 live sets).
- **ARCHITECTURE BOUNDARY:** defaults are `UNKNOWN`/`NOT_READY`; nothing defaults to supported.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-06 — Backend state snapshot
- **FILE:** `core/anki/AnkiBackendState.kt` (new, 208 lines)
- **ACTION:** CREATE
- **CONTRACT REFERENCE:** DECISION-21B-10, DECISION-21B-06 (no atomic read of two flows)
- **RESPONSIBILITY:** one coherent, immutable picture of one backend at one instant.
- **IMPLEMENTATION:** `AnkiBackendState(descriptor, implementedClaim)` with derived `backendId`, `kind`, `locality`, `availability`, `unavailableReason`, `capabilityEvidence`, `capability(req)`, `capabilities()`, `diagnostics()`. Holds a descriptor and a claim — **never the `AnkiBackend`** (`AUDIT-21D-10`). **No timestamp, by decision:** a timestamp reads as "verified at", which no field here can truthfully say; staleness is handled by re-reading or observing, uncertainty by `Checking`/UNKNOWN, never by a quiet carry-over of an older `Ready`. The state is **not a verification receipt, not a continuity claim and not a selection** (no collection/assurance vocabulary exists in it — `AUDIT-21D-11`).
- **LOCAL CHECKS:** `Gate21dBackendStateTest`, `AUDIT-21D-10/11`.
- **ARCHITECTURE BOUNDARY:** reading a state never calls the backend (`ProbeOnlyBackend.calls` stays empty).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-07 — Registry interface
- **FILE:** `core/anki/AnkiBackendSelector.kt`
- **ACTION:** MODIFY (extend the existing class; **no interface twin**)
- **CONTRACT REFERENCE:** DECISION-21B-10; `BACKEND_OWNERSHIP_INVARIANTS.md` §1.1
- **RESPONSIBILITY:** configured identities, exact lookup, per-instance snapshots, lifecycle, read-only refresh — and nothing else.
- **IMPLEMENTATION:** kept: `registered`, `ids`, `find`, `descriptors()`, constructor semantics (duplicate → `IllegalArgumentException("Duplicate logical backend identity")`). Added: `states()`, `stateOf(id)`, `register`, `remove`, `refresh(id)`, and an `internal` `members: StateFlow<AnkiBackendMembership>` for the observation point. Explicit non-responsibilities are in the class KDoc and audited (`AUDIT-21D-05`): no preference, priority, fallback, session, transaction, scheduler, retry, continuity, synchronization, or any backend lifecycle. Raw instance lookup stays at the infrastructure boundary: `find` is unchanged, `members` is `internal`, and everything UI/diagnostics read is instance-free (`states`).
- **LOCAL CHECKS:** `AUDIT-21D-01/02`; `Gate21dRegistryMembershipTest`.
- **ARCHITECTURE BOUNDARY:** `class AnkiBackendRegistry` stays in `AnkiBackendSelector.kt` (existing audits pin it).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-08 — Registry implementation
- **FILE:** `core/anki/AnkiBackendSelector.kt`, `core/anki/AnkiBackendRegistration.kt` (new, 173 lines)
- **ACTION:** MODIFY + CREATE
- **CONTRACT REFERENCE:** DECISION-21B-01 (collisions are configuration errors), INV-21B-01
- **RESPONSIBILITY:** the ten requirements of the checkpoint.
- **IMPLEMENTATION:** (1) registered by stable `AnkiBackendId`; (2/3) an identity already held is **never replaced** — the same instance is idempotent (`AlreadyRegistered`, no new snapshot), a different object is `DuplicateIdentity`; (4) membership is an immutable `AnkiBackendMembership` replaced by one `compareAndSet`; (5) lookup is `membership[id]`; (6/7) absent ≠ unavailable by type; (8) lookups never construct or probe; (9) enumeration is registration order, `states()` is identity-ordered — **order is never a priority**; (10) lock-free, no lost update (16-way contention tests).
- **LOCAL CHECKS:** `VER-21D-01…07`; mutants A1, A4 killed.
- **ARCHITECTURE BOUNDARY:** no "current/active/default" member exists or can be added without breaking `AUDIT-21D-05`.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-09 — Registration lifecycle
- **FILE:** `core/anki/AnkiBackendRegistration.kt`, `core/anki/AnkiBackendSelector.kt`
- **ACTION:** CREATE + MODIFY
- **CONTRACT REFERENCE:** DECISION-21B-10 (open uncertainty), BACKEND_IDENTITY_CONTRACT §1.4
- **RESPONSIBILITY:** the minimal lifecycle, no extra machinery.
- **IMPLEMENTATION:** two operations — `REGISTERED` (`register`) and `REMOVED` (`remove`) — with typed outcomes. `AVAILABILITY_CHANGED` is *observed*, not a registry operation (availability is owned by the backend). `CONFIGURATION_UPDATED` is **intentionally not an operation**: an in-place identity-preserving swap is exactly "silent instance replacement"; a reconfiguration is `remove` + `register` of the same identity, which the identity contract already treats as *the same logical owner whose continuity must be re-established*. Health never changes an id; a new connection never mints one; removal never touches a record (the registry holds no ledger) — a removed owner's records are `Blocked(BACKEND_NOT_REGISTERED)` at recovery, **not reassigned**, and a re-registered identity routes again.
- **LOCAL CHECKS:** `a removed backend's durable owner is blocked not reassigned, and returns with the same identity` (uses the real `AnkiTransactionRecoveryPlanner`).
- **ARCHITECTURE BOUNDARY:** no mutation-record deletion or recovery in this gate.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-10 — AnkiDroid availability adapter
- **FILE:** `data/anki/ankidroid/AnkiDroidBackend.kt` (existing chain reused)
- **ACTION:** REUSE detector/health check/gateway; MODIFY the backend's published projections
- **CONTRACT REFERENCE:** DECISION-21B-06/09; GATE 04 "single source of truth"
- **RESPONSIBILITY:** map real AnkiDroid availability into registry state, using public APIs only.
- **IMPLEMENTATION:** no new availability source — the existing chain already distinguishes the six conditions and uses only the exported provider (no private DB, media or scheduler). Two additions: (a) `implementedCapabilities` — the static claim, derived from the **same** `implementedCapabilitiesFor(MAX_VALIDATED_SPEC, ready = true)` and the **same** `withWriteSupport` gates as the live projection (no hand-written set — `AUDIT-21D-07`); (b) `availability` now publishes a `Ready` snapshot that equals the gated live capabilities, **fixing D-21D-01** (Appendix A). No polling loop was added.
- **LOCAL CHECKS:** `Gate21dAnkiDroidRegistryTest` (11 tests through the real chain); mutant B1 (revert the fix) killed by 4 tests + the audit.
- **ARCHITECTURE BOUNDARY:** installation ≠ operational availability (six distinct answers asserted); only the Android surface is faked.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-11 — PC Anki availability adapter
- **FILE:** `data/anki/remote/PcAnkiAvailabilitySource.kt` (new, 216 lines)
- **ACTION:** CREATE — **STRUCTURE ONLY; production wiring BLOCKED (no PC Anki backend)**
- **CONTRACT REFERENCE:** DECISION-21B-28; 21B handoff 21D BLOCKER; `BACKEND_FAILURE_MATRIX.md` §3.1/§10
- **RESPONSIBILITY:** settle once how PC availability would be produced, without confusing it with agent connectivity.
- **IMPLEMENTATION:** `PcAnkiAvailabilityMapper` — a pure, exhaustive (no `else`) ladder over the **real** `ConnectionState`: transport → authentication → protocol → agent readiness, plus an explicit `PcAnkiReadinessReport` for Anki beneath. It reads only the *kind* of connection state (never host, port, reason, message, cause or the agent's AI `capabilities`), so nothing sensitive can reach availability. `Ready` ⇔ authenticated `ConnectionState.Ready` **and** an explicit `Ready(negotiated)` report. `PcAnkiAvailabilitySource` observes an existing `StateFlow<ConnectionState>` (it can open no connection) and binds Anki evidence to a **connection session** (agent id, protocol version, server version): a disconnect clears it, a reconnect starts with `Checking`, a stale report is rejected, a changed agent/protocol/server voids it, a latency change does not.
- **LOCAL CHECKS:** `Gate21dPcAvailabilityTest` (15 tests); mutants B2, B3, B4, B5, C5, C6 killed. **No production code references this file** (`AUDIT-21D-14`).
- **ARCHITECTURE BOUNDARY:** PC AI/TTS readiness is keyed by the agent connection and is never an input to Anki readiness (INV-21B-18).
- **READY FOR NEXT CHECKPOINT:** yes (for the structure; a PC backend remains absent)

### CHECKPOINT 21D-12 — Static capability mapping
- **FILE:** `data/anki/ankidroid/AnkiDroidBackend.kt` (claim), `Gate21dAnkiDroidRegistryTest.kt` (pinned matrix)
- **ACTION:** MODIFY + test pin
- **CONTRACT REFERENCE:** DECISION-21B-09 product decision (refuse `CARD_BROWSER`, `MEDIA_READ`, `FLAGS`, `DECK_COUNTS` honestly)
- **RESPONSIBILITY:** implemented code decides application-level support; mocks decide nothing.
- **IMPLEMENTATION:** the matrix in §5 is **pinned by a golden test through the real adapter** (15 implemented / 4 refused for the fully wired build; 3 for an unwired one). The claim cannot exceed what a ready backend reports and drops every capability whose writer is unwired. No operation semantics were changed to make the matrix look complete.
- **LOCAL CHECKS:** `VER-21D-13/14`; mutant B8 (hand-widened claim) killed.
- **ARCHITECTURE BOUNDARY:** PC column = "no code" (not a measurement) — see §5.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-13 — Runtime capability negotiation
- **FILE:** `core/anki/AnkiBackendState.kt`, `PcAnkiAvailabilitySource.kt`
- **ACTION:** CREATE (rules only; no discovery added)
- **CONTRACT REFERENCE:** DECISION-21B-09 open uncertainty (R11)
- **RESPONSIBILITY:** versioning and invalidation without ever widening.
- **IMPLEMENTATION:** a static claim is bound to the audited provider spec (`AnkiDroidProviderSpec.MAX_VALIDATED_SPEC`); a runtime spec below the minimum yields `Unsupported` availability (nothing READY while the claim stays). Negotiated evidence exists only while `Ready` and is **dropped to UNKNOWN the moment it is not**; a probe may narrow a claim but never widen it; the PC session fingerprint voids a negotiation on an agent/protocol/server-version change. No artificial network discovery was introduced (neither backend has a negotiation channel to use).
- **LOCAL CHECKS:** `VER-21D-16`; mutants A2, B5, C6 killed.
- **ARCHITECTURE BOUNDARY:** capability gain never retroactively enables a bound context (it only affects new reads of state).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-14 — Readiness observation & refresh
- **FILE:** `core/anki/AnkiBackendSelector.kt` (`refresh`), `core/anki/AnkiBackend.kt` (probe contract), `core/anki/AnkiBackendStatus.kt`
- **ACTION:** MODIFY
- **CONTRACT REFERENCE:** DECISION-21B-06/23; INV-21B-13
- **RESPONSIBILITY:** safe, read-only readiness updates.
- **IMPLEMENTATION:** `AnkiBackend.refreshAvailability()` now **documents** the contract AnkiDroid already honoured: read-only, request-ordered, cancellable. `registry.refresh(id)` sends exactly that one call to exactly that instance and returns *no state* (a snapshot would be mistaken for a receipt of this probe — the backend's derived flows lag the probe by a dispatch hop, observed). It adds no queue, retry, timer or fan-out; coalescing and ordering stay the backend's. A missing identity probes nothing. **Not done (R-21D-03):** wiring a foreground trigger — lifecycle code is not compile-verifiable here and no consumer needs it yet.
- **LOCAL CHECKS:** `VER-21D-21/25`; mutants A3, C4 killed.
- **ARCHITECTURE BOUNDARY:** the registry cannot call a mutating member (`AUDIT-21D-13`).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-15 — Concurrency & state consistency
- **FILE:** `AnkiBackendSelector.kt`, `AnkiBackendRegistration.kt`, `AnkiBackendStatus.kt`
- **ACTION:** CREATE/MODIFY
- **CONTRACT REFERENCE:** DECISION-21B-19; INV-21B-14
- **RESPONSIBILITY:** no stale overwrite, no torn state, no leaked collector.
- **IMPLEMENTATION:** membership = immutable snapshot + CAS; per-backend ordering = the backend's publication guard (AnkiDroid: `AnkiDroidHealthPublicationGuard`, verified through the registry) and the PC session epoch; a state is read in one `combine`, and because two flows cannot be read atomically it is **fail-closed** (disagreement → `NOT_READY`, never a guess). Observation uses `flatMapLatest` over **cold** per-backend flows: a removed backend's collector is cancelled (observer count asserted 1 → 0).
- **LOCAL CHECKS:** `VER-21D-17/18/19`; mutants C1 (leak), A6 (static observation) killed.
- **ARCHITECTURE BOUNDARY:** no distributed lock, no shared mutable collection leaks (`AUDIT-21D-09/10`).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-16 — Backend loss & reconnect representation
- **FILE:** tests (`Gate21dObservationTest`, `Gate21dPcAvailabilityTest`)
- **ACTION:** NO CHANGE in production beyond 21D-15
- **CONTRACT REFERENCE:** DECISION-21B-23, INV-21B-13
- **RESPONSIBILITY:** report facts; consumers enforce operation-specific identity requirements.
- **IMPLEMENTATION:** A down + B ready is published as exactly that — `find(A)` still returns A. A reconnect changes availability and nothing else; the state has **no vocabulary** for continuity (reflection + source audit), and a 21C `resourceRelation` is identical before/after (`IdentityUnverifiable(NOMINAL)`).
- **LOCAL CHECKS:** `VER-21D-20`.
- **ARCHITECTURE BOUNDARY:** reachability ≠ same collection.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-17 — Fake registry & test fixtures
- **FILE:** `Gate21dFixtures.kt`, `Gate21dAnkiDroidChain.kt` (+ existing `FakeAnkiBackend`, untouched)
- **ACTION:** CREATE (test-only)
- **CONTRACT REFERENCE:** contract §5; 21B handoff
- **RESPONSIBILITY:** deterministic scenarios without a network or a real Anki mutation.
- **IMPLEMENTATION:** `ProbeOnlyBackend` — a strict **call-recording** backend (every non-flow member records and refuses; an audit pins that it overrides *every* `AnkiBackend` member, so a member added later cannot slip past) — and `Gate21dAnkiDroidChain`, the real production adapter chain with only the Android surface faked. All 13 required fixtures are covered (§10, VER-21D-17 row).
- **LOCAL CHECKS:** `the recording fixture overrides every member AnkiBackend declares`.
- **ARCHITECTURE BOUNDARY:** test sources only; a Fake is never a production candidate (`VER-21D-22`).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-18 — Dependency injection & app composition
- **FILE:** `di/AppContainer.kt`
- **ACTION:** NO CHANGE
- **CONTRACT REFERENCE:** DECISION-21B-10
- **RESPONSIBILITY:** one registry, existing instances, no duplicate services.
- **IMPLEMENTATION:** none needed — the container already builds exactly one registry from the one configured backend and exposes `ankiBackendRegistry` and `ankiBackendStatusCenter`; the status center constructor is unchanged. Proven, not assumed: `AUDIT-21D-04` counts `AnkiBackendRegistry(` ×1 and `AnkiDroidBackend(` ×1 in the container and pins the literal `AnkiBackendRegistry(listOf(ankiDroidBackend))`; `git diff` touches neither `di/` nor `MainActivity`.
- **LOCAL CHECKS:** `AUDIT-21D-04/14`.
- **ARCHITECTURE BOUNDARY:** no StudySession, ReviewCommit, preference, resolver or Library switching change.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-19 — Diagnostics projection
- **FILE:** `core/anki/AnkiBackendState.kt` (`AnkiBackendDiagnostics`)
- **ACTION:** CREATE
- **CONTRACT REFERENCE:** DECISION-21B-27 (extend the existing format; never a second one)
- **RESPONSIBILITY:** typed, non-sensitive data for 21M.
- **IMPLEMENTATION:** fields are only the stable id, kind, locality, status token, reason, capability evidence and the per-capability status map — every one an identity token or an enum; **no free-text field exists**. The display label is intentionally absent (UI-owned). "Negotiation state" = `capabilityEvidence` (`STATIC_CLAIM`/`RUNTIME_NEGOTIATED`/`NONE`). "Last check status" = the status token; no timestamp is invented. The existing `AnkiBackendStatusEntry` shape is unchanged and now derives from the same observation. No diagnostics UI was implemented.
- **LOCAL CHECKS:** `VER-21D-23`; mutant C3 (payload leak) killed.
- **ARCHITECTURE BOUNDARY:** projection only; never a decision input.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21D-20 — Minimal integration & compilation
- **FILE:** all of the above
- **ACTION:** INTEGRATE (compile-verified, behaviour-preserving except D-21D-01)
- **CONTRACT REFERENCE:** 21B handoff §PART 4
- **RESPONSIBILITY:** make it compile against the real codebase without altering selection or mutation behaviour.
- **IMPLEMENTATION:** see §7 for every changed file and why. Harness: `main errors: 0 (312 files)`, `compose errors: 0 (24 files)`, `test errors: 0 (231 files)`; suite `199 classes / 2368 tests / 0 failed` against a `193 / 2269 / 0` baseline.
- **LOCAL CHECKS:** `VER-21D-24/26`.
- **ARCHITECTURE BOUNDARY:** stable ids preserved; existing sessions and transaction records untouched; no second backend-selection mechanism.
- **READY FOR NEXT CHECKPOINT:** yes

---

# PART II — INDEPENDENT ARCHITECTURE AUDIT (AUDIT-21D-01 … AUDIT-21D-16)

Performed after implementation. Source audits are `Gate21dArchitectureAuditTest` (comments stripped); behavioural evidence is in the other `Gate21d*` classes. A row is `PASS` only because the cited check ran at this tree.

| ID | Audit | RESULT | EVIDENCE / DEFECT / REMEDIATION |
|---|---|---|---|
| AUDIT-21D-01 | Contract conformance | **PASS** | `AUDIT-21D-01` pins the existing surface (`registered`, `ids`, `find`, `descriptors()`) and the five additions; `class AnkiBackendRegistry` still lives in `AnkiBackendSelector.kt`. Ownership/non-ownership matches `BACKEND_OWNERSHIP_INVARIANTS.md` §1.1. |
| AUDIT-21D-02 | Identity integrity | **PASS** | Membership is `Map<AnkiBackendId, AnkiBackend>`; lookup is `membership.value[id]`; the gate calls the 21C predicates. Behavioural: `configured instances appear once and lookup returns exactly those objects`. |
| AUDIT-21D-03 | Kind separation | **PASS** | No `associateBy/groupBy/find/first { it.kind }`, no `== AnkiBackendKind.` in registry/state; the registry body never mentions kind or locality. `two instances of one kind stay separate registry entries`. |
| AUDIT-21D-04 | Single registry ownership | **PASS** | Exactly one registry-shaped declaration under `anki/` (`AnkiBackendRegistry` in `AnkiBackendSelector.kt`); container builds one registry and one `AnkiDroidBackend`; all other constructions are the pre-existing `emptyList()` ones. |
| AUDIT-21D-05 | No selection-policy leakage | **PASS** | Registry body, state, registration, taxonomy and PC structure contain none of: mode, preference store, resolution request, resolver, selector, `AUTO_`, priority, fallback/alternate/switch, `firstOrNull {`, `.first()`, `minBy`, `maxBy`, `random`. |
| AUDIT-21D-06 | Availability accuracy | **PASS** | Provider, transport, authentication, Anki and collection are distinct states; the taxonomy and the PC ladder are exhaustive (no `else`); the ladder reads no host/port/reason/message/cause/`capabilities`. Six AnkiDroid conditions → six answers through the real chain. |
| AUDIT-21D-07 | Capability honesty | **PASS** (1 defect found, remediated) | No parallel capability enum; interface default claims nothing; AnkiDroid's claim is derived from the policy + wiring gates (no `AnkiCapabilities(` literal). **D-21D-01** was found by the real-chain verification and fixed (Appendix A). |
| AUDIT-21D-08 | Support vs readiness | **PASS** | Two enums, one status type, `READY ⇒ SUPPORTED` enforced at construction; no authorization vocabulary in the state model; data-layer `CapabilitySupport` not used. |
| AUDIT-21D-09 | Concurrency safety | **PASS** (1 test defect found, remediated) | Registry uses `compareAndSet` on both mutations, no `synchronized`/mutable collections; observation uses `flatMapLatest` over cold flows. A flaky assertion in *my own* concurrency test (a backend's initial value fell outside its vocabulary) was found in a full-suite run and fixed; the class is now 25/25 stable in isolation and 4/4 in full-suite loops. |
| AUDIT-21D-10 | State consistency | **PASS** | No `var` in the state/registration/taxonomy/PC sources; a state cannot carry an `AnkiBackend`; membership is an `internal` immutable snapshot. |
| AUDIT-21D-11 | Reconnect semantics | **PASS** | No `AnkiCollectionIdentity`, assurance, continuity, `collectionKey` or `AnkiBackendContext` in the state model or the PC structure; reconnect changes availability only (reflection + behavioural). |
| AUDIT-21D-12 | Security | **PASS** | No credential vocabulary; the PC structure imports no network, `java.net`, `android.` or `core.network`; the new availability state has no payload; no authentication mechanism or class was added. |
| AUDIT-21D-13 | No mutation during probing | **PASS** | The registry calls exactly one thing on a backend (`refreshAvailability`); the observer reads exactly `availability`, `capabilities`, `id`, `implementedCapabilities`; no mutating member name appears in any 21D source. A real-adapter test asserts zero provider updates and inserts. |
| AUDIT-21D-14 | No duplicate services | **PASS** | No construction of `AnkiDroidBackend`, `DefaultAnkiDroid*`, `WebSocketAgentConnection`, `PcAnkiBackend`, `OkHttpClient` in 21D code; **nothing in production references the PC structure**; no `PcAgent(` allocation outside the identity declaration. |
| AUDIT-21D-15 | Prior-gate isolation | **PASS** | `core/study`, `edit/`, `create/`, commit/ledger/reconciliation/recovery/session-context files reference none of the new types and never call `registry.register/remove/refresh`. All 2269 pre-existing tests unchanged and green; no existing test file was edited. |
| AUDIT-21D-16 | Scope discipline | **PASS** | No resolver/preference/failover/synchronization component; the resolver and preference files know none of the 21D types; no new file named like a router/failover/synchronizer/merger. |

---

# PART III — AUTHORITATIVE VERIFICATION (VER-21D-01 … VER-21D-26)

Executed with the repository harness (PART V). `PASS` = the named tests ran and passed at this tree. `PASS†` = **structure-level**: verified against the real `ConnectionState` and explicit fixtures; no PC Anki backend exists, so no end-to-end PC verification is claimed.

| ID | Requirement | Evidence (class · test) | RESULT |
|---|---|---|---|
| VER-21D-01 | Registry initialization, no duplicate construction | `Gate21dRegistryMembershipTest` · `configured instances appear once and lookup returns exactly those objects`, `the registry detaches the list it was built from`; `AUDIT-21D-04/14` (container: one registry, one `AnkiDroidBackend`) | PASS |
| VER-21D-02 | Exact lookup returns that instance | same test (`assertSame` ×3) | PASS |
| VER-21D-03 | Missing backend: defined result, no fallback | `an unregistered identity has no state, and no sibling answers for it`, `absent and unavailable are different answers`; `Gate21dObservationTest` · `refreshing a backend that is not there probes no one…` (mutant A3 killed) | PASS |
| VER-21D-04 | Same kind, different identities stay separate | `two instances of one kind stay separate registry entries`, `kind is never the instance identity` | PASS |
| VER-21D-05 | Duplicate protection | `a second registration under a held identity never replaces the first`, `duplicate identities at construction still fail closed`, `registering the very same instance again is idempotent…`, `concurrent claims on one identity admit exactly one instance` (16 threads; mutant A1 killed) | PASS |
| VER-21D-06 | Stable identity across availability change | `availability changes never change the identity or the instance`; `Gate21dAnkiDroidRegistryTest` · `the real adapter keeps its stable identity and kind through every state change` | PASS |
| VER-21D-07 | Backend removal | `removal changes membership only and leaves the others untouched`; `a removed backend's durable owner is blocked not reassigned, and returns with the same identity`; `observation follows registration and a removed backend is no longer collected` | PASS |
| VER-21D-08 | AnkiDroid ready | `Gate21dAnkiDroidRegistryTest` · `a ready fully wired AnkiDroid is READY for exactly what the application implements`, `a ready AnkiDroid is eligible for a study session through the existing resolver` — **real adapter chain** (mutant B1 killed) | PASS |
| VER-21D-09 | AnkiDroid permission missing | `a missing permission is accurate, and implemented is not ready`; `Gate21dBackendStateTest` · `a missing permission is accurate and is never reported as ready` | PASS |
| VER-21D-10 | PC transport vs Anki readiness | `Gate21dPcAvailabilityTest` · `a connected agent without an Anki report is never a ready PC Anki backend`, `ready is produced in exactly one situation and in no other` (21 connection fixtures × 4 reports), `AI and speech readiness of the agent never stands in for Anki readiness` | PASS† |
| VER-21D-11 | PC authentication failure | `an authentication failure is distinguishable, non-ready and leaks nothing`, `no endpoint, credential or rejection text reaches availability`; `authentication is distinct from permission, transport and Anki readiness` | PASS† |
| VER-21D-12 | Protocol compatibility / unavailable negotiated capability | `an incompatible protocol is a version problem, and unnegotiated capabilities are not supported`, `every connection state maps to its own availability…` | PASS† |
| VER-21D-13 | Capability snapshot matches implemented operations | golden matrix through the real adapter (15 implemented / 4 refused fully wired; 3 for an unwired build) + `AUDIT-21D-07` | PASS |
| VER-21D-14 | Unsupported capability not advertised because an API exposes it | `capabilities the public API might offer are not advertised unless implemented` (flags, media, deck counts, card browser, coarse `editNotes`); mutant B8 killed | PASS |
| VER-21D-15 | Availability vs capability | `implemented support and operational readiness are separate facts`, `a checking backend with a static claim keeps its claim and verifies nothing`, and — on the real adapter — `a missing permission is accurate, and implemented is not ready` | PASS |
| VER-21D-16 | Runtime capability change | `negotiated evidence is dropped, not kept, when the backend leaves ready`, `a static claim survives a gap and negotiated evidence does not`, `a different agent or protocol version starts a new session, a latency change does not`, integrated PC walk-through (mutants A2, B5, C6 killed) | PASS (AnkiDroid claim) / PASS† (negotiation) |
| VER-21D-17 | Coherent observable updates for one backend | `states mirror the backends' own projections`, `a change to one backend leaves every other backend's state untouched` | PASS |
| VER-21D-18 | Stale health response | `Gate21dObservationTest` · `an older delayed probe cannot overwrite a newer one, as observed through the registry` — **real `AnkiDroidBackend` publication guard**: the stale `PermissionRequired` is never visible at any point of the history; `Gate21dPcAvailabilityTest` · `a late answer from before the reconnect can neither revive nor overwrite the new session` (exact order: B ready, then A late) (mutant B4 killed in isolation) | PASS / PASS† |
| VER-21D-19 | Concurrent backend changes | `simultaneous updates to two backends only ever publish coherent snapshots` (2×400 racing updates over disjoint vocabularies; every observed snapshot checked) | PASS |
| VER-21D-20 | Reconnect identity | `loss and reconnect are reported per backend and never swapped between backends`, `a reconnect proves reachability and asserts nothing about the collection` | PASS |
| VER-21D-21 | Read-only health checks | strict recording backend: after `registry.refresh` the **only** recorded call is `refreshAvailability` (no rating, reviewer action, edit, creation, media, session); reads/observation record none; real adapter: zero provider updates and inserts; `AUDIT-21D-13` | PASS |
| VER-21D-22 | Fake production isolation | `a test double is visible as such and no production mode will ever select it` (every `AnkiBackendMode` rejects it); `a test double lives in test sources only and is never wired into production`; main contains 0 `FakeAnkiBackend` / `AnkiBackendId.Fake(` | PASS |
| VER-21D-23 | Privacy & diagnostics | `diagnostics are tokens only and carry no payload from any availability state` (12 states, secret payloads), `AnkiDroid diagnostics carry no provider text`, `no endpoint, credential or rejection text reaches availability`, `a refused identity is reported by token and never registered` (mutant C3 killed) | PASS |
| VER-21D-24 | Previous-gate regression | full suite: all **2269 pre-existing tests** green and unmodified (GATE 11–21C included); design-only features are not reported as tested implementations | PASS |
| VER-21D-25 | No routing policy | `an unregistered identity has no state, and no sibling answers for it`, `refreshing a backend that is not there probes no one…`, `AUDIT-21D-05/16` | PASS |
| VER-21D-26 | App composition lifecycle | `AUDIT-21D-04/14` (container unchanged: one registry, one backend, no new service); observer count 1 → 0 on removal; scope cancellation freezes state without corruption; nothing in production constructs the PC structure | PASS |

---

# PART IV — NON-NEGOTIABLE INVARIANTS (INV-21D-01 … INV-21D-20)

| ID | Invariant | Enforced by | Checked by |
|---|---|---|---|
| INV-21D-01 | The registry is an inventory, not an AUTO resolver | no policy vocabulary in registry/state | AUDIT-21D-05/16 |
| INV-21D-02 | Configured `BackendId` is stable across availability changes | state keyed on id; flows never rewrite it | VER-21D-06 |
| INV-21D-03 | Kind ≠ instance identity | membership keyed on `AnkiBackendId` | AUDIT-21D-03; VER-21D-04 |
| INV-21D-04 | Lookup never substitutes another backend | `membership[id]`; `NotRegistered` types | VER-21D-03/25; mutant A3 |
| INV-21D-05 | The registry is not scheduler authority | no session/scheduler member | AUDIT-21D-13/15 |
| INV-21D-06 | The registry is not transaction-recovery authority | no ledger; planner untouched | VER-21D-07; AUDIT-21D-15 |
| INV-21D-07 | Availability and capability support are separate | two dimensions in one status | VER-21D-15 |
| INV-21D-08 | Transport availability ≠ Anki readiness | ladder `Ready` ⇔ auth'd agent ∧ explicit Anki report | VER-21D-10 |
| INV-21D-09 | PC AI/TTS readiness ≠ PC Anki readiness | ladder never reads the agent's AI `capabilities` | VER-21D-10; AUDIT-21D-06 |
| INV-21D-10 | Only implemented capabilities are advertised | claim derived from policy + wiring gates | VER-21D-13/14; mutant B8 |
| INV-21D-11 | Unknown is never promoted to supported | defaults UNKNOWN/NOT_READY; `READY ⇒ SUPPORTED` at construction | VER-21D-16; mutant A2 |
| INV-21D-12 | Published state is immutable | data classes, immutable membership, no `var` | AUDIT-21D-10 |
| INV-21D-13 | Older health observations never overwrite newer evidence | backend publication guard + PC session epoch | VER-21D-18; mutant B4 |
| INV-21D-14 | Reconnect does not establish collection continuity | no continuity vocabulary; reconnect starts at `Checking` | VER-21D-20; AUDIT-21D-11; mutant B5 |
| INV-21D-15 | Registration never rewrites historical ownership | registry holds no ledger | VER-21D-07 |
| INV-21D-16 | Health checks never mutate | one read-only call; strict recorder | VER-21D-21; AUDIT-21D-13 |
| INV-21D-17 | No selection preference is registry truth | preference not referenced in registry/state | AUDIT-21D-05 |
| INV-21D-18 | No private AnkiDroid storage or scheduler internals | no new access path; public provider only | AUDIT-21D-12/13 |
| INV-21D-19 | Registry metadata never exposes credentials | payload-free state, token-only diagnostics, content-free reasons | VER-21D-23 |
| INV-21D-20 | 21D weakens no GATE 11–21C invariant | full suite green, no existing test edited | VER-21D-24 |

---

# PART V — BUILD AND TEST VALIDATION

## V.1 Gradle tasks — attempted, not executable here

| Required command | Status | Measured reason |
|---|---|---|
| `./gradlew testDebugUnitTest` | `BLOCKED — environmental limitation` | `./gradlew --version` with the available JDK fails in `org.gradle.wrapper.Install.forceFetch` (`SSL peer shut down incorrectly`) fetching `gradle-8.7-bin.zip`; `services.gradle.org`, `repo.maven.apache.org`, `dl.google.com` and `plugins.gradle.org` all answer HTTP `000`; no Android SDK |
| `./gradlew lint` | `BLOCKED — environmental limitation` | same |
| `./gradlew assembleDebug` | `BLOCKED — environmental limitation` | same |
| `./gradlew assembleRelease` | `BLOCKED — environmental limitation` | same |
| `./gradlew connectedDebugAndroidTest` | `BLOCKED — environmental limitation` | same, plus no device/emulator |

None is claimed as PASS. CI (`.github/workflows/android-ci.yml` runs unit tests, lint, debug and release assembly) is the open release-style precondition (R-21D-02).

## V.2 Repository harness — executed in this session

```text
bootstrap:  PyPI (jdk4py, kotlin-jupyter-kernel) + GitHub (Sable/android-platforms, Kotlin/kotlinx.coroutines 1.10.2)
            → tools/jvm-harness/bin/bootstrap-sandbox.sh        (the repo's own recipe, unmodified)

baseline (HEAD, before any edit):
            main errors: 0 (files: 308)   compose errors: 0 (files: 24)   test errors: 0 (files: 223)
            RESULT classes=193 tests=2269 passed=2269 failed=0           ← identical to the GATE 21C report

after GATE 21D:
            main errors: 0 (files: 312)   compose errors: 0 (files: 24)   test errors: 0 (files: 231)
            RESULT classes=199 tests=2368 passed=2368 failed=0           (+6 classes, +99 tests)
```

Stability: four consecutive full-suite runs `2368/2368`; the concurrency class `Gate21dObservationTest` 25/25 in isolation.
One full-suite run (of the 12 run in this session) failed `StudyAudioRouteCoordinatorTest.concurrent device preference and boundary churn stays consistent` — a **pre-existing** 8-thread race test in the audio package with no dependency on any `anki` code. Isolated: 60/60 pass; isolated under artificial CPU contention: 1 of 70 fails. It is not modified here (R-21D-09).

UI compile safety beyond the harness's gated scope: the harness's diagnostic sweep of **all 75 framework-importing files** reports `148` errors on pristine baseline and `148` on this tree — the identical set modulo line numbers (all harness-stub limitations). A **negative control** (temporarily removing my `SettingsScreen` branch) made the sweep report `'when' expression must be exhaustive … 'AgentAuthenticationRequired'`, so the clean result is meaningful.

## V.3 Mutation testing — do the tests have teeth?

20 targeted production mutants were applied one batch at a time (each rebuilt, run, and **restored with checksum verification**). Every mutant was killed:

| Mutant | Killed by |
|---|---|
| A1 `register` replaces a held identity | `a second registration under a held identity never replaces the first`, `concurrent claims on one identity admit exactly one instance` |
| A2 negotiated evidence kept across a gap | 4 tests in `Gate21dBackendStateTest` |
| A3 `refresh` falls back to a sibling | `refreshing a backend that is not there probes no one…`, `an unregistered identity has no state…` |
| A4 `states()` ordered by registration | `registration order is preserved…`, `states mirror…` |
| A5 `READY` ignores `implemented` | 3 state tests |
| A6 observation frozen to initial membership | `observation follows registration…` |
| A7 identity gate disabled | 3 membership tests |
| A8 authentication reported as permission | 4 taxonomy tests |
| B1 **revert the AnkiDroid fix** | 4 real-chain tests + `AUDIT-21D-07` |
| B2 PC `Ready` on an authenticated agent alone (run in one batch with A2, B4, B1, so kills are not attributed per mutant) | PC tests including `ready is produced in exactly one situation and in no other`, `a connected agent without an Anki report is never a ready PC Anki backend`, `AI and speech readiness of the agent never stands in for Anki readiness`, `before anything is reported a connected agent is checking, not ready` |
| B3 PC authentication failure reported as disconnected | 2 PC tests |
| B4 stale-session report accepted (run twice, once alone) | `a late answer…`, `an old session's ready answer…`, integrated walk-through |
| B5 report retained across a disconnect (alone) | `a disconnect clears…`, `a different agent or protocol…`, integrated walk-through |
| B8 AnkiDroid claim hand-widened | golden matrix + `capabilities the public API might offer…` |
| C1 observation leaks collectors (`flatMapMerge`) | `observation follows registration and a removed backend is no longer collected` + `AUDIT-21D-09` |
| C2 state drops the static claim | 2 real-chain tests + `states mirror…` |
| C3 diagnostics leak the availability payload | 2 diagnostics tests |
| C4 `refresh` ignores removal during the probe | `a backend removed while its probe runs is reported absent` |
| C5 PC protocol mismatch treated as "checking" | 2 PC tests |
| C6 PC session ignores protocol version | `a different agent or protocol version starts a new session…` |

---

# PART VI — DEFINITION OF DONE

| # | Criterion | Result |
|---|---|---|
| 1 | Prerequisites validated; no normative decision invented | **MET** — PART 0; the five 21B required decisions answered in Appendix B |
| 2 | Descriptors, availability contract, capability representation, registry contract and implementation | **MET** — PART I-A |
| 3 | AnkiDroid state source connected; real adapter verified end-to-end | **MET** — only the Android surface is faked |
| 4 | PC state source connected | **PARTIAL BY CONTRACT** — structure only; no PC backend exists and none may be fabricated (21B BLOCKER) |
| 5 | Runtime changes observable where supported | **MET** — `AnkiBackendStatusCenter.states` |
| 6 | Identity stability preserved; existing DI lifecycle preserved; no duplicate components | **MET** — `AppContainer` untouched |
| 7 | AUDIT-21D-01…16 all pass | **MET** — PART II (one real defect and one test defect found and remediated, both recorded) |
| 8 | VER-21D-01…26 pass with evidence | **MET**, PC rows `PASS†` (structure-level) — PART III |
| 9 | Required build tasks complete | **NOT MET in this environment** — Gradle `BLOCKED`; harness green (PART V). Recorded, not hidden. |
| 10 | No AUTO routing, preference management, failover or selection UI | **MET** — AUDIT-21D-05/16 |
| 11 | Mutation evidence that the tests detect regressions | **MET** — 20/20 killed |

---

# PART VII — FINAL REPORT

## 1. Gate result

```text
GATE 21D — BACKEND REGISTRY, AVAILABILITY & CAPABILITIES

STATUS:               PASS (harness) — Gradle BLOCKED (environmental, see V.1)
REPOSITORY HEAD:      e5d9abbdd8a6ad3823ca9c0c268295e4d1ffa3b9

PREREQUISITES:
21A: LOCKED
21B: LOCKED
21C: LOCKED
```

## 2. Registry architecture (as implemented)

```text
Composition root (AppContainer — unchanged)
   │ builds once
   ▼
AnkiBackendRegistry ── holds the configured instances; owns MEMBERSHIP only
   │   membership = immutable snapshot, replaced by one compareAndSet
   │   find / ids / registered / descriptors() / states() / stateOf() / register / remove / refresh(id)
   │
   │  reads each backend's OWN flows (never writes, never probes except refresh → refreshAvailability)
   ▼
AnkiBackend (AnkiDroidBackend today)  ← owns its own availability, capabilities, publication order
   │   availability: StateFlow<AnkiAvailability>      capabilities: StateFlow<AnkiCapabilities>
   │   implementedCapabilities: AnkiCapabilities?     (static claim; null = states none)
   ▼
AnkiBackendStatusCenter.states ── observes membership × backend flows → List<AnkiBackendState>
   │                              status = states × the user's preference (display only)
   ├──► GATE 21E resolver   (pure; reads descriptors / states at call time)
   └──► Diagnostics / UI    (AnkiBackendState.diagnostics(): tokens only)

PC (structure only, nothing constructs it):
ConnectionState (existing agent connection) ─► PcAnkiAvailabilityMapper ─► AnkiAvailability
                       explicit PcAnkiReadinessReport, bound to a connection session ┘
```

Dependency direction is unchanged: core types know nothing of `data/`; the PC structure sits in `data/anki/remote/` (it may import `core.models.ConnectionState`; `core/anki` may not import `core.network`).

## 3. Backend descriptor contract

`AnkiBackendDescriptor(id, availability, capabilities, kind = id.kind, locality = kind.locality)` — **unchanged**. `id` is the stable configured-instance identity (21C); `kind`/`locality` are derived and validated against it; availability and capabilities are a *snapshot* valid only for the read that produced it. No secret, no scheduler state, no Compose type. No `displayName` (presentation is UI-owned; rule A6). `AnkiBackendState` wraps it with the static claim and the derived views.

## 4. Availability model

| State | Backend | Reason (`AnkiBackendUnavailableReason`) | Retryability |
|---|---|---|---|
| `Checking` | both | *(none — not a failure)* | — |
| `NotInstalled` | AnkiDroid | `APP_NOT_INSTALLED` | `AFTER_USER_ACTION` |
| `ProviderUnavailable` | AnkiDroid | `PROVIDER_UNAVAILABLE` | `AFTER_USER_ACTION` |
| `PermissionRequired` | AnkiDroid | `PERMISSION_REQUIRED` | `AFTER_USER_ACTION` |
| `CollectionNotInitialized` | either | `COLLECTION_UNAVAILABLE` | `AFTER_USER_ACTION` |
| `TemporarilyUnavailable` | either | `TEMPORARILY_UNAVAILABLE` | `EXPECTED_TO_RECOVER` |
| `AgentDisconnected` | PC | `TRANSPORT_UNAVAILABLE` | `MAY_RECOVER` |
| **`AgentAuthenticationRequired`** *(new)* | PC | `AUTHENTICATION_REQUIRED` | `AFTER_USER_ACTION` |
| `AgentAnkiUnavailable` | PC | `ANKI_UNAVAILABLE` | `AFTER_USER_ACTION` |
| `Unsupported` | AnkiDroid API shape / PC protocol | `VERSION_INCOMPATIBLE` | `AFTER_USER_ACTION` |
| `Fault` | either | `BACKEND_ERROR` | `UNKNOWN` |
| `Ready` | either | *(none)* | — |

PC mapping (structure): `Ready` only for an authenticated `ConnectionState.Ready` + explicit Anki-ready report; `Ready(authenticated=false)`/`AuthenticationFailed` → `AgentAuthenticationRequired`; `ProtocolMismatch`, `ReadyLegacy`, `Connected` → `Unsupported`; `Resolving…NegotiatingCapabilities` → `Checking`; every transport failure → `AgentDisconnected`; a report of `AnkiUnavailable` → `AgentAnkiUnavailable`; `CollectionUnavailable` → `CollectionNotInitialized`; no report → `Checking`.

## 5. Capability matrix

Vocabulary: `AnkiRequiredCapability` (the locked one). "Impl." = `implemented`, "Ready" = `operational` when the backend is `Ready`.

| Capability | AnkiDroid (fully wired) | AnkiDroid (no writers wired) | PC | Fake | Evidence |
|---|---|---|---|---|---|
| Read decks (`DECKS`) | SUPPORTED / READY | SUPPORTED / READY | no code → not registered; if one is ever registered with no claim: UNKNOWN until a valid probe, never SUPPORTED | whatever a fixture declares | golden test, `implementedCapabilitiesFor` |
| Deck counts (`DECK_COUNTS`) | **UNSUPPORTED** (semantics unverified) | UNSUPPORTED | — | — | policy: stays `false` |
| Scheduled review / rendered cards | SUPPORTED / READY | SUPPORTED / READY | — | — | golden test |
| Review = rating loop (`REVIEW`) | SUPPORTED / READY *(implemented, not verified on a device)* | **UNSUPPORTED** (no rating gateway) | — | — | `withWriteSupport` |
| Card details | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Card browser (`CARD_BROWSER`) | **UNSUPPORTED** | UNSUPPORTED | — | — | pin has no public bounded list API |
| Reviewer actions (`BURY`, `SUSPEND_CARDS`) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Flags (`FLAGS`) | **UNSUPPORTED** | UNSUPPORTED | — | — | pinned contract has no flag column |
| Edit note (fields, tags, card deck) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Create note (`CREATE_NOTE`) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Media: read (`MEDIA_READ`) | **UNSUPPORTED** | UNSUPPORTED | — | — | policy: `media` stays `false` |
| Media: store (`STORE_MEDIA`) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Models / templates (listing, schema) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Custom Study | no capability exists (GATE 20 `BLOCKED`) | — | — | — | not in `AnkiRequiredCapability` |

Totals (fully wired): **15 implemented / 4 refused** (`DECK_COUNTS`, `CARD_BROWSER`, `FLAGS`, `MEDIA_READ`). If AnkiDroid is not ready, every implemented capability reads `SUPPORTED + NOT_READY` (permission missing) or `SUPPORTED + UNKNOWN` (still checking) — never READY.
**PC column:** there is no code, so there is nothing to measure; a cell would be fabricated. A future PC backend that states no claim is `UNKNOWN` until a valid probe, `UNKNOWN` again the moment it leaves `Ready`.

## 6. Registry API

```kotlin
class AnkiBackendRegistry(backends: List<AnkiBackend>) {
    internal val members: StateFlow<AnkiBackendMembership>      // infrastructure boundary (raw instances)

    val registered: List<AnkiBackend>                           // registration order — never a priority
    val ids: Set<AnkiBackendId>
    fun find(id: AnkiBackendId): AnkiBackend?                   // null = not registered; never a sibling
    fun descriptors(): List<AnkiBackendDescriptor>              // the single raw snapshot

    fun states(): List<AnkiBackendState>                        // identity-ordered; pull-only
    fun stateOf(id: AnkiBackendId): AnkiBackendStateLookup      // Registered(state) | NotRegistered(id)

    fun register(backend: AnkiBackend): AnkiBackendRegistration // Registered | AlreadyRegistered | DuplicateIdentity | IdentityRefused
    fun remove(id: AnkiBackendId): AnkiBackendRemoval           // Removed | NotRegistered
    suspend fun refresh(id: AnkiBackendId): AnkiBackendRefresh  // Requested(id) | NotRegistered(id) — carries no state
}

class AnkiBackendStatusCenter(...) {                            // the observation point (constructor unchanged)
    val states: StateFlow<List<AnkiBackendState>>               // NEW: follows membership
    val status: StateFlow<AnkiBackendStatusSnapshot>            // now derived from `states`
}

interface AnkiBackend { val implementedCapabilities: AnkiCapabilities? get() = null /* static claim */ }
```
Ownership boundary: the composition root registers; everything else looks up by identity; UI/diagnostics observe `states` (instance-free). The registry cannot choose, start a session, commit, recover, merge or fail over.

## 7. File-by-file report

```text
FILE:   core/anki/AnkiAvailability.kt                                      (+18/−2)
ACTION: MODIFIED
LOCKED CONTRACT REFERENCES: DECISION-21B-28, BACKEND_FAILURE_MATRIX §10
RESPONSIBILITY: availability vocabulary
IMPLEMENTED: AgentAuthenticationRequired (payload-free) + status token AGENT_AUTHENTICATION_REQUIRED
LOCAL CHECKS: exhaustive consumers updated; taxonomy/vocabulary tests

FILE:   core/anki/AnkiBackend.kt                                           (+29)
ACTION: MODIFIED
LOCKED CONTRACT REFERENCES: DECISION-21B-09, INV-21B-07
RESPONSIBILITY: backend abstraction
IMPLEMENTED: `implementedCapabilities` (default null) + the documented read-only / ordered / cancellable contract of `refreshAvailability()`
LOCAL CHECKS: every existing implementor unaffected (default body); AUDIT-21D-07

FILE:   core/anki/AnkiBackendSelector.kt                                   (+117/−10)
ACTION: MODIFIED (registry extended in place)
LOCKED CONTRACT REFERENCES: DECISION-21B-10, BACKEND_OWNERSHIP_INVARIANTS §1.1
RESPONSIBILITY: configured instances, exact lookup, snapshots, lifecycle, read-only refresh
IMPLEMENTED: membership snapshot + CAS; states(), stateOf(), register(), remove(), refresh()
LOCAL CHECKS: Gate21dRegistryMembershipTest; AUDIT-21D-01…05, 09

FILE:   core/anki/AnkiBackendStatus.kt                                     (+58/−32)
ACTION: MODIFIED (StatusCenter only; the lock tracker below it is untouched)
LOCKED CONTRACT REFERENCES: DECISION-21B-24 (observation point, never a source of truth)
RESPONSIBILITY: observation
IMPLEMENTED: `states` follows membership via flatMapLatest over cold flows; `status` derived from it; `snapshotNow()` from `registry.states()`
LOCAL CHECKS: Gate21dObservationTest; AUDIT-21D-09

FILE:   core/anki/AnkiBackendUnavailableReason.kt                          (new, 110)
ACTION: CREATED
LOCKED CONTRACT REFERENCES: DECISION-21B-27/08
RESPONSIBILITY: failure taxonomy
IMPLEMENTED: 10 reasons, retryability, total projection of AnkiAvailability
LOCAL CHECKS: taxonomy tests; AUDIT-21D-06

FILE:   core/anki/AnkiBackendRegistration.kt                               (new, 173)
ACTION: CREATED
LOCKED CONTRACT REFERENCES: DECISION-21B-01, BACKEND_IDENTITY_CONTRACT §1.3–1.5
RESPONSIBILITY: identity gate, typed lifecycle results, immutable membership
IMPLEMENTED: registrationRefusal() (A3/A4/A5 subset), Registration/Removal/Refresh results, internal AnkiBackendMembership
LOCAL CHECKS: identity-gate and lifecycle tests; AUDIT-21D-02/10

FILE:   core/anki/AnkiBackendState.kt                                      (new, 208)
ACTION: CREATED
LOCKED CONTRACT REFERENCES: DECISION-21B-09/10, DECISION-21B-06
RESPONSIBILITY: one backend at one instant; capability truth; diagnostics projection
IMPLEMENTED: AnkiBackendState, AnkiImplementedSupport, AnkiCapabilityReadiness, AnkiCapabilityStatus, AnkiCapabilityEvidence, AnkiBackendDiagnostics, AnkiBackendStateLookup
LOCAL CHECKS: Gate21dBackendStateTest; AUDIT-21D-08/10/11

FILE:   data/anki/ankidroid/AnkiDroidBackend.kt                            (+53/−4)
ACTION: MODIFIED
LOCKED CONTRACT REFERENCES: DECISION-21B-06/09
RESPONSIBILITY: the AnkiDroid adapter's published projections
IMPLEMENTED: `implementedCapabilities` (static claim from policy + wiring gates); `availability` publishes a Ready snapshot equal to the gated live capabilities (D-21D-01 fix); `availability` declared below `capabilities`
LOCAL CHECKS: Gate21dAnkiDroidRegistryTest (real chain); mutants B1, B8, C2

FILE:   data/anki/remote/PcAnkiAvailabilitySource.kt                       (new, 216)
ACTION: CREATED — STRUCTURE ONLY, no producer
LOCKED CONTRACT REFERENCES: DECISION-21B-28, 21B handoff 21D BLOCKER
RESPONSIBILITY: PC readiness ladder; session-bound evidence
IMPLEMENTED: PcAnkiReadinessReport, PcAnkiAvailabilityMapper, PcAnkiAvailabilitySource, PcAnkiConnectionSession
LOCAL CHECKS: Gate21dPcAvailabilityTest; AUDIT-21D-06/12/14

FILE:   data/anki/ankidroid/AnkiDroidHealth.kt, ui/components/anki/AnkiBackendUiModels.kt,
        ui/screens/carddetails/CardDetailsScreen.kt, ui/screens/library/LibraryUiState.kt (×2),
        ui/screens/settings/SettingsScreen.kt                              (+1 / +1 / +1 / +2 / +1)
ACTION: MODIFIED — one branch each
LOCKED CONTRACT REFERENCES: none (compile necessity)
RESPONSIBILITY: exhaustive `when` over AnkiAvailability
IMPLEMENTED: AgentAuthenticationRequired branch beside its PC neighbours; neutral placeholder copy (unreachable today; wording owner 21M)
LOCAL CHECKS: harness sweep identical to baseline; negative control proves the sweep sees the construct

FILES:  app/src/test/java/com/studyagent/client/anki/Gate21d*.kt            (8 new: 6 classes / 99 tests, 2 helpers)
ACTION: CREATED (test-only)
NOT CHANGED: di/AppContainer.kt, MainActivity.kt, StudySessionMachine, every ledger/coordinator/recovery file,
             AnkiBackendResolver.kt, AnkiBackendPreference.kt, FakeAnkiBackend.kt, every pre-existing test file.
```

## 8. Concurrency & lifecycle report

```text
State publication:   one `combine` per backend (availability + capabilities), one `combine` over backends,
                     replaced wholesale by flatMapLatest when membership changes; StateFlow is latest-value.
                     The two flows cannot be read atomically → a state is fail-closed: disagreement is
                     NOT_READY, never a guess (the locked DECISION-21B-06 answer, unchanged).
Readiness refresh:   registry.refresh(id) → exactly one call, `refreshAvailability()`, on exactly that
                     instance. No queue, retry, timer, fan-out. Returns no state. Cancellation propagates.
Stale result
rejection:           AnkiDroid — the backend's request-id publication guard (verified through the registry:
                     the stale verdict is never visible). PC structure — session epoch: a report from a
                     previous session is rejected, a reconnect starts at `Checking`.
Backend
registration:        immutable membership snapshot + compareAndSet; idempotent for the same instance;
                     a held identity is never replaced; unsafe identities refused by token.
Backend removal:     membership only. Records are untouched and become Blocked(BACKEND_NOT_REGISTERED);
                     the observation of the removed backend is cancelled (observers 1 → 0).
Connection
lifecycle:           the registry creates, starts, stops and closes nothing. The PC structure observes an
                     existing StateFlow<ConnectionState>; it cannot open a transport. Foreground-driven
                     refresh is NOT wired (R-21D-03).
```

## 9. Architecture audit matrix — see PART II (16/16 PASS)

## 10. Verification matrix — see PART III (26/26; PC rows PASS†)

VER-21D-17 fixtures (13 required): AnkiDroid ready ✔ (real chain) · AnkiDroid unavailable ✔ (not installed, provider, permission, collection, API) · PC ready ✔† · PC transport connected / Anki unavailable ✔† · PC authentication required ✔† · missing permission ✔ · unsupported capability ✔ · unknown capability ✔ · multiple PC instances ✔ · duplicate BackendId ✔ · backend removed ✔ · backend reconnected ✔ · stale health response ✔.

## 11. Build results — see PART V

## 12. Invariant report — see PART IV (20/20 enforced and checked)

## 13. Residual risks

| ID | Risk | Disposition / owner |
|---|---|---|
| R-21D-01 | **No PC Anki backend and no Anki messages in the agent protocol.** All PC results are structure-level; a PC `Ready` is unreachable in production by design; the PC column of the capability matrix is empty because there is no code. | Inherits 21B R4/R7; closed only by a PC backend + protocol (not in the 21A–21N series). |
| R-21D-02 | **Gradle tasks not executed.** Code compiled/tested with the harness (Kotlin 2.3, K2), not the Gradle toolchain (Kotlin 1.9.24, K1). K1-specific constructs were reviewed (explicit casts, `entries`, `data object`, `@OptIn`), but this is not proof. | CI must run `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease` before any release claim (same as R-21C-06). |
| R-21D-03 | **AnkiDroid's registry availability is only as fresh as the last `refreshAvailability()`**; the foreground hook (`MainActivity.onStart`) drives a *different* owner (`AnkiDroidHealthRepository`). The Settings backend selector can therefore show `Checking` for AnkiDroid until Library/Study first refreshes. | Pre-existing. `registry.refresh(id)` is the sanctioned entry point; wiring it into the foreground hook was deliberately not done (lifecycle code is not compile-verifiable here; no consumer yet). Owner 21E/21M. |
| R-21D-04 | **D-21D-01's fix makes the AnkiDroid study path reachable.** Rating commits are `IMPLEMENTED`, not `VERIFIED`; GATE 11's real-device mutation verification is still outstanding. | A device run against a disposable collection is the precondition for any release claim. The change is isolated and revertable (Appendix A). |
| R-21D-05 | Two `StateFlow`s cannot be read atomically; a transient disagreement reads `NOT_READY`. | Locked by 21B (fail-closed); 21E must not add a best-effort read. |
| R-21D-06 | PC identity allocation rules A1 (configured instance, not a label), A2 (global uniqueness) and A6 (label change ≠ identity change) are **unexercised**: no PC configuration producer exists. | R3 narrowed (A3/A4/A5 + duplicates enforced at registration), not closed. Owner: the future allocation authority. |
| R-21D-07 | `host:port`-shaped discriminators are not refused — they are indistinguishable from the 21B-sanctioned composed `<agent>:<profile>` form. Only URLs (`://`) and IPv4 literals are refused. | Allocation authority's duty (A4). |
| R-21D-08 | The 21C predicate `discriminatorIsNormalized` trims the whole token, so it cannot see *leading* whitespace inside a prefixed discriminator (`pc_agent: a`). | The 21D gate adds a stricter discriminator check (superset). **Recommend a 21C amendment** for 21N review; 21C was not edited. |
| R-21D-09 | Pre-existing flaky test: `StudyAudioRouteCoordinatorTest.concurrent device preference and boundary churn stays consistent` (1 of 12 full runs in this session; 1 of 70 isolated under contention; no dependency on `anki`). | Unrelated, unmodified. Owner: audio gate. |
| R-21D-10 | Consumers that snapshot `registry.ids` at construction (e.g. `AnkiLocalStudyStarter.localStudyBackendIdFlow`) do not follow dynamic registration. No production code registers dynamically today. | Owner 21E/21F when the first dynamic consumer appears. |
| R-21D-11 | AnkiDroid *API shape* and PC *protocol* share one category (`VERSION_INCOMPATIBLE`) because one availability state (`Unsupported`) carries both; the taxonomy deliberately does not parse its free-text reason. | `kind` distinguishes them for presentation. |
| R-21D-12 | UI copy for `AgentAuthenticationRequired` in five UI files is neutral placeholder text for an unreachable state. | Wording owner 21M. |
| R-21D-13 | Collection continuity remains unprovable (no collection key on AnkiDroid; PC unknown). The registry asserts nothing about it, by design. | Inherits 21B R1/R2/R5/R6. |
| R-21D-14 | UI files outside the harness's gated scope are compile-checked only against API-shaped stubs (sweep identical to baseline). | CI Gradle build. |
| R-21D-15 | `AnkiBackendStatusCenter.status` for an **empty** registry now reports the stored preference (it previously returned a fixed `AUTO` snapshot). Not reachable in production (the container always registers AnkiDroid). | Documented behaviour alignment with `snapshotNow()`. |

## 14. Downstream contract for GATE 21E

```text
Registered backend identities    registry.ids / registry.find(id) / registry.stateOf(id)
Immutable backend descriptors    registry.descriptors()  →  AnkiBackendDescriptor(id, availability, capabilities, kind, locality)
Availability snapshot            AnkiBackendState.availability  (+ .unavailableReason, retryability)
Capability snapshot              AnkiBackendState.capability(AnkiRequiredCapability) → (implemented, operational)
                                 READY is positive knowledge only and equals descriptor.supports(req)
Exact instance lookup            find(id) / stateOf(id): null / NotRegistered for an absent identity — NEVER a sibling.
                                 A 21E instance pin is therefore `stateOf(pin)`; a missing or unusable pin must
                                 refuse, not substitute (BACKEND_SELECTION_POLICY §1.1).
Stable ordering / tie-break      AnkiBackendDescriptor.orderingKey ("kind.ordinal:stableId") and `locality` are the
                                 resolver's inputs. registry.states() and registered are display order only.
Observation                      AnkiBackendStatusCenter.states (membership-aware); status = states × preference.
Read-only refresh                registry.refresh(id) → Requested(id) | NotRegistered(id); carries no state.
```

What 21E may rely on: the resolver and selector are **unchanged** and now see a coherent AnkiDroid (D-21D-01); `AgentAuthenticationRequired` is a distinct availability state (`unavailabilityError()` still maps it to the generic `BackendUnavailable` through its `else` — whether it becomes its own resolution outcome is 21E's encoding decision, DECISION-21B-08).
What 21E must not assume: that a PC backend exists; that a state carries a timestamp (it does not); that `refresh` returns a verdict; that registration order means anything.

## 15. Gate lock decision

```text
GATE 21D:                 LOCKED  (registry, availability, capability truth — harness-verified)

BACKEND REGISTRY:         PASS
IDENTITY INTEGRITY:       PASS
AVAILABILITY:             PASS   (AnkiDroid end-to-end through the real adapter chain; PC structure-level †)
CAPABILITY TRUTH:         PASS
CONCURRENCY:              PASS
ARCHITECTURE AUDIT:       PASS   (16/16)
VERIFICATION:             PASS   (26/26; PC rows structure-level †)
BUILD:                    HARNESS PASS · GRADLE BLOCKED — environmental, not claimed (V.1, R-21D-02)

NEXT AUTHORIZED SUB-GATE: 21E — Backend Preference & Deterministic AUTO Resolver
                          (conditional on the CI Gradle run for any release-style claim)
```

---

# PART VIII — GATE LOCK

```text
GATE 21D — REGISTRY LOCKED

BACKEND IDENTITY:                 STABLE
BACKEND REGISTRY:                 IMPLEMENTED   (the one existing registry, extended)
BACKEND AVAILABILITY:             OBSERVABLE
CAPABILITIES:                     EVIDENCE-BASED
ANALYTIC/DIAGNOSTIC SNAPSHOTS:    READ-ONLY
PC ANKI BACKEND:                  ABSENT — structure only, no producer, no fabricated Ready
AUTO RESOLVER:                    NOT IMPLEMENTED
BACKEND PREFERENCES:              NOT IMPLEMENTED
MID-SESSION FAILOVER:             NOT IMPLEMENTED
```

- **Suggested commit message:** `feat(gate21d): add observable Anki backend registry and capabilities`
- **Commit created by the gate:** **no.** Consistent with the established workflow recorded by GATE 21B/21C, commit creation is not authorized by the gate itself; every audit, verification and harness run in this report was executed against an uncommitted working tree, and nothing was pushed during the gate. The change set was committed and the pull request opened afterwards, as a separate step, on the branch owner's explicit request. That step added no code: it changed only the three wording lines in this report that described the tree as uncommitted.
- **Files in the change set:** 10 modified production files (`+281/−48`) + 4 new production files (707 lines) under `app/src/main`, 8 new files (2 856 lines) under `app/src/test/java/com/studyagent/client/anki/`, and this document.
- **Do not proceed to GATE 21E from this gate.** The CI Gradle run, a device verification of the AnkiDroid study path (R-21D-04) and the foreground-refresh wiring decision (R-21D-03) are the open release-style preconditions.

---

# APPENDIX A — D-21D-01: a pre-existing defect found by verifying VER-21D-08 against the real adapter

**Symptom (measured).** The real production chain (`FakeAnkiDroidProbe` + `FakeAnkiDroidPermissionManager` → `DefaultAnkiDroidDetector` → `DefaultAnkiDroidHealthCheck` → `DefaultAnkiDroidHealthProbe` → `DefaultAnkiDroidGateway` → `AnkiDroidBackend`, wired as `AppContainer` wires it) made the *existing, unmodified* resolver refuse AnkiDroid:

```text
BEFORE   availability=Ready  liveReview=true  embeddedReview=false
         descriptor.isReachable=true  supports(REVIEW)=false  isReadyForReview=false
         resolver AUTO study → RequiredCapabilityUnavailable(required=[REVIEW], missing={AnkiDroidLocal=[REVIEW]})
         selector AUTO       → Unavailable(reason=NO_READY_BACKEND, error=UnsupportedAction(review))

AFTER    availability=Ready  liveReview=true  embeddedReview=true
         descriptor.supports(REVIEW)=true  isReadyForReview=true
         resolver AUTO study → Resolved(AnkiDroidLocal, AUTO_LOCAL_PREFERRED)
         selector AUTO       → Resolved(AnkiDroidLocal)
```

**Cause.** The detector deliberately publishes `Ready(AnkiCapabilities.NONE)` ("GATE 02 proves reachability, not review"); the capability probe later fills the *live* `capabilities` flow; nothing reconciled the two. `AnkiBackendDescriptor.supports` — correctly, per DECISION-21B-06 — requires **both** projections to agree, so a fully ready, fully wired AnkiDroid could never satisfy any requirement. The existing selector test only used coherent fakes (`FakeAnkiBackend` builds `Ready(live)`), which is why this was never seen; there is no device run in this repository.

**Reach (read from code; the resolver refusal is the measured part).** `AnkiLocalStudyStarter.start` and `AnkiStudyEffectExecutor.begin` both resolve through that selector; the dashboard's "study on this phone" pre-flight uses the same resolution; `LibraryScreen` derives its "Study" label from `availability.isReadyForReview`.

**Fix (the narrowest that keeps the locked rule).** `AnkiDroidBackend.availability` now derives a `Ready` snapshot from the **same state** and the **same `withWriteSupport` gates** as `capabilities`, so a ready backend's two projections are equal by construction. Nothing is widened (a capability appears in the snapshot only if it is in the gated live projection; an unwired rating gateway still yields `review = false` in both); non-`Ready` states pass through untouched; the detector, gateway, resolver, descriptor and selector are untouched.

**Evidence.** Regression test `a ready AnkiDroid is eligible for a study session through the existing resolver`; reverting the fix (mutant B1) is killed by 4 real-chain tests + `AUDIT-21D-07`; the full pre-existing suite stays green.

**Consequence you should weigh.** This makes AnkiDroid study start, the dashboard pre-flight and the Library "Study" label *reachable for the first time*. The mutation path behind it is IMPLEMENTED but not VERIFIED on a device. To revert: restore `availability = _integrationState.map { it.availability }.stateIn(...)` in `AnkiDroidBackend` (one block); `Gate21dAnkiDroidRegistryTest` will then fail exactly where described.

---

# APPENDIX B — Decisions (DECISION-21D-xx) and the five required 21B decisions

| ID | Decision | Rationale |
|---|---|---|
| DECISION-21D-01 | Extend `AnkiBackendRegistry` in place; no registry interface, no `DefaultAnkiBackendRegistry` | 21B-10 prohibits a second registry; ~40 call sites use the constructor; existing audits pin the file |
| DECISION-21D-02 | `AnkiBackendDescriptor` stays the single raw snapshot; `AnkiBackendState` wraps it | answers 21B required decision 5 (`descriptors()` remains the single source) |
| DECISION-21D-03 | No `displayName` in `core` | presentation is UI-owned; rule A6 |
| DECISION-21D-04 | PC authentication is its own availability state, payload-free | 21B §10: R0/R1 ineligible; the real `ConnectionState` already distinguishes it |
| DECISION-21D-05 | The reason taxonomy is a total, `else`-less projection of `AnkiAvailability` | a new state cannot compile without a decision; no second hierarchy |
| DECISION-21D-06 | Capability vocabulary = `AnkiRequiredCapability`; two dimensions (implemented, operational); authorization is not a registry fact | 21B-09 three-way distinction |
| DECISION-21D-07 | Static claim via `AnkiBackend.implementedCapabilities` (null default); AnkiDroid derives it from policy + wiring gates; negotiated evidence exists only while `Ready` | answers 21B required decision 3 (versioning/invalidation, R11) without ever widening |
| DECISION-21D-08 | Membership = immutable snapshot replaced by one CAS; operations REGISTERED/REMOVED; no CONFIGURATION_UPDATED operation | answers 21B required decision 4 without a current pointer |
| DECISION-21D-09 | The registration gate enforces A3/A4/A5 (+ duplicates); A1/A2/A6 remain the allocation authority's | answers 21B required decision 1; R3 narrowed, not closed |
| DECISION-21D-10 | Observation lives in `AnkiBackendStatusCenter.states`; the registry owns no scope, timer or clock | observing is separate from owning |
| DECISION-21D-11 | Probe ordering is the backend's; `registry.refresh` is read-only delegation and returns no state | availability truth is the backend's (21B §8) |
| DECISION-21D-12 | PC readiness = pure ladder over the real `ConnectionState` + explicit Anki report + session-bound evidence; structure only | answers 21B required decision 2 without fabricating `Ready` |
| DECISION-21D-13 | Fake isolation by location (test sources), by identity (no mode accepts it) and by audit | VER-21D-22 |
| DECISION-21D-14 | Fix D-21D-01 in the adapter, not in the descriptor | the locked fail-closed rule must stay; the defect was in the adapter's published data |

**Contract clarifications for 21N review (not contradictions of the lock):** (1) 21B's phrase "immutable explicit registry" is honoured as *immutable membership snapshots replaced atomically, no current pointer* — which is what 21B's own handoff asked 21D to define. (2) `AnkiBackend.refreshAvailability()` now states the read-only/ordered/cancellable contract AnkiDroid already honoured. (3) The 21C normalization predicate gap (R-21D-08).

---

# APPENDIX C — Reproduce

```bash
# one-time (PyPI + GitHub only):  see tools/jvm-harness/bin/bootstrap-sandbox.sh header
HARNESS_WORK=/tmp/h tools/jvm-harness/bin/bootstrap-sandbox.sh

export HARNESS_WORK=/tmp/h HARNESS_JAVA=/tmp/pp/jdk/jdk4py/java-runtime/bin/java \
       HARNESS_KOTLIN_JARS=/tmp/pp/kk/run_kotlin_kernel/jars
tools/jvm-harness/bin/build.sh all                 # main 312 · compose 24 · test 231 — 0 errors each
tools/jvm-harness/bin/run.sh 'Gate21d'             # 6 classes / 99 tests
tools/jvm-harness/bin/run.sh                       # 199 classes / 2368 tests
GATE16_COMPOSE_ALL=1 tools/jvm-harness/bin/build.sh compose   # diagnostic UI sweep: 148 errors, identical to baseline
```

# APPENDIX D — What was deliberately NOT done

- No `AppContainer`, `MainActivity` or lifecycle wiring (R-21D-03).
- No resolver, preference, instance-pin, failover, session or transaction change (21E–21L).
- No PC backend, PC authentication mechanism, PC protocol message or PC configuration store.
- No edit to any earlier gate's document, test or locked contract.
- No polling loop, timer, retry ladder or probe fan-out.
- No commit and no push during the gate's execution and verification (the commit and the pull request were a separate, later step requested by the branch owner).
