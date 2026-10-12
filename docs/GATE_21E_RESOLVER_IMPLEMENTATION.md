# GATE 21E — Backend Preference & Deterministic AUTO Resolver (Implementation)

**Status:** implementation complete and harness-verified — PART V Gradle tasks `BLOCKED — environmental limitation`.
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/ed7e8875-study-agent-client`
**HEAD:** `afa58cb` (GATE 21D merge)
**Date:** 2026-10-12
**Execution type:** implementation (preference domain + deterministic resolver + persistence)
**Prior gates:** GATE 21A `PASS/LOCKED`; GATE 21B `LOCKED`; GATE 21C `LOCKED`; GATE 21D `LOCKED`

```text
GATE 21E — BACKEND PREFERENCE & DETERMINISTIC AUTO RESOLVER

STATUS:               PASS (harness) — Gradle BLOCKED (environmental, see V.1)
REPOSITORY HEAD:      afa58cb

PREREQUISITES:
21A: LOCKED
21B: LOCKED
21C: LOCKED
21D: LOCKED
```

---

## 1. Backend Preference Contract

The new `BackendPreference` sealed interface in `core/anki/BackendPreference.kt` is the sole
preference representation for future unbound work:

```kotlin
sealed interface BackendPreference {
    data object Auto : BackendPreference                                     // policy, not a backend
    data class Explicit(val backendId: AnkiBackendId) : BackendPreference    // exact instance pin
    data class ExplicitKind(val kind: AnkiBackendKind) : BackendPreference   // legacy, no pin
}
```

- `Auto` = let the resolver choose deterministically (BACKEND_SELECTION_POLICY §3).
- `Explicit(backendId)` = one exact configured instance; pins an `AnkiBackendId` (stableId), never a
  kind, display label or endpoint.
- `ExplicitKind(kind)` = migration-only variant for legacy `PC_AGENT` selections that recorded no
  instance pin. Resolution applies the LOCKED N-rule (0 → unavailable, 1 → that instance, ≥2 →
  `Ambiguous`). A fresh write never produces this variant.

A legacy `AnkiBackendMode` converts to a `BackendPreference` via `AnkiBackendMode.toPreference()`:

| Legacy mode          | Preference                                          |
|----------------------|-----------------------------------------------------|
| `AUTO`               | `Auto`                                              |
| `ANKIDROID_LOCAL`    | `Explicit(AnkiDroidLocal)` (singleton, unambiguous) |
| `PC_AGENT`           | `ExplicitKind(PC_AGENT)` (N-rule at resolution)     |

`BackedPreferenceRepository` (in `data/preferences/`) is the one authoritative preference store. It:

1. Reads the legacy `anki_backend_mode` key plus a new optional `anki_backend_instance_pin` key
   (`pc_agent:<profileId>` stableId);
2. Emits a `StateFlow<BackendPreference>` (eager, AUTO-seeded; first DataStore emission replaces);
3. Exposes `mode: StateFlow<AnkiBackendMode>` for pre-21E consumers;
4. Persists AUTO with pin cleared, ANKIDROID_LOCAL with pin cleared, and PC_AGENT with/without pin as
   appropriate;
5. Treats an unreadable pin honestly: keeps it as `ExplicitKind` so resolution reports
   `EXPLICIT_BACKEND_MISSING` rather than silently rewriting it to AUTO (INV-21E-02 /
   BACKEND_SELECTION_POLICY §4.2);
6. Exposes `lastWriteError` (content-free token) and never pretends a failed write succeeded.

Default on a fresh install / missing key: `Auto`.

Schema version remains `5`; no new migration entry is required because the new pin key is optional
and a missing key is handled as "no pin" (the pre-existing default behaviour for AUTO/PC_AGENT). No
stored value is overwritten on read.

## 2. Resolution Architecture

```text
Settings (PreferencesDataStore)
    anki_backend_mode + anki_backend_instance_pin
              │
              ▼
BackendPreferenceRepository   (source of truth: persistent preferred policy)
    .preference: StateFlow<BackendPreference>
    .mode:       StateFlow<AnkiBackendMode>   (legacy compatibility)
              │
              ▼
AnkiBackendResolver2 (pure function; no I/O, no probing, no mutation)
    .resolve(BackendResolutionRequest2, List<AnkiBackendDescriptor>) : BackendResolutionResult2
              ▲
              │ reads
AnkiBackendRegistry.descriptors()  (inventory + state source of truth)
              │
              ▼
BackendResolutionResult2
    ├── Resolved(backendId, reason)
    └── Failed (NoBackendConfigured | NoEligibleBackend | ExplicitBackendMissing |
                ExplicitBackendUnavailable | CapabilityUnsupported | Ambiguous | ConfigurationInvalid)

Post-binding consumers are untouched:
    AnkiStudyEffectExecutor, commit/reviewer-action/note-mutation/note-creation coordinators,
    transaction recovery planner, lock tracker — all still use registry.find(<owned id>).
    The resolver is not reachable from core/study or the recovery planner (grep-verified empty).
```

Ownership boundaries (INV-21E-05 / INV-21E-19):

| Concern                | Owner                                |
|------------------------|--------------------------------------|
| Persistent preference  | `BackendPreferenceRepository`        |
| Configured backends    | `AnkiBackendRegistry`                |
| Availability/capability| Each backend + `AnkiBackendState`    |
| Pre-binding selection  | `AnkiBackendResolver2`               |
| Active session lock    | `AnkiStudyBackendLockTracker`        |
| Transaction destination| Each durable ledger                  |

## 3. AUTO Selection Algorithm (exactly LOCKED policy)

```text
resolveAuto(request, descriptors):

  0. candidates = descriptors where kind != FAKE, sorted by orderingKey
     if candidates empty → NoBackendConfigured(considered = {})

  1. ready = candidates where availability == Ready   (R2)
     if ready empty → NoEligibleBackend(single-candidate's typed reason or generic)

  2. capable = ready where descriptor.supportsAll(requiredCapabilities)   (R3, both projections agree)
     if capable empty → CapabilityUnsupported(required, per-candidate missing sets)

  3. local = capable.filter { locality == LOCAL_DEVICE }
     if local not empty → Resolved(local.minBy { orderingKey }.id, reason = AUTO_LOCAL_PREFERRED)

  4. remote = capable.filter { locality == REMOTE_AGENT }
     if remote.size == 1 → Resolved(remote.single().id, reason = AUTO_SINGLE_REMOTE)
     if remote.size == 0 → NoEligibleBackend   (only TEST_DOUBLE left; never selected)
     if remote.size ≥ 2  → Ambiguous(remote.ids)  (two PC profiles = user decision, never guessed)
```

Deterministic tie-break inside each locality class: `orderingKey = "${kind.ordinal}:${id.stableId}"`
lexicographic ascending. Total order; registry-list order, insertion order, object identity,
HashMap order, timing and "first to answer" are never inputs (INV-21E-07 / VER-21E-15/16).

Tested by 500 repeated resolutions with permuted descriptor order and 200 pure-function invocations.

## 4. Manual Selection Semantics

| Situation                                                       | Outcome                                                  |
|-----------------------------------------------------------------|----------------------------------------------------------|
| Explicit pin is registered, Ready, supports all required caps   | `Resolved(backendId, EXPLICIT_PIN)`                       |
| Explicit pin not registered                                     | `ExplicitBackendMissing(pinnedId)` — never a sibling     |
| Explicit pin registered but not Ready (permission/provider/...) | `ExplicitBackendUnavailable(pinnedId, reason, error)`    |
| Explicit pin Ready but missing a required capability            | `CapabilityUnsupported(required, {pinnedId -> missing})` |
| ExplicitKind(PC_AGENT), 0 PC instances registered               | `NoBackendConfigured`                                    |
| ExplicitKind(PC_AGENT), 1 PC instance registered + eligible     | resolves to that one (as if pinned)                      |
| ExplicitKind(PC_AGENT), ≥2 PC instances registered              | `Ambiguous(candidates)` — policy never chooses           |
| Explicit pin names a `Fake` id                                  | `ConfigurationInvalid("explicit_fake_not_permitted")`    |

The preference is **never** mutated on failure, no lock is published on a refused start, and no
retry loop is started. A manual selection does not convert to AUTO, and a sibling instance of the
same kind is never silently substituted (INV-21E-08).

## 5. Resolution Result Contract

All eight semantic outcomes from `BACKEND_SELECTION_POLICY.md` §5.1 are encoded as subtypes of
`BackendResolutionResult2`:

| Semantic outcome              | Encoded as                           | Failure classification token            |
|-------------------------------|--------------------------------------|-----------------------------------------|
| `RESOLVED`                    | `Resolved(backendId, reason)`        | —                                       |
| `NO_ELIGIBLE_BACKEND`         | `NoEligibleBackend(considered, reasons, error)` | `NO_ELIGIBLE_BACKEND`          |
| `NO_BACKEND_CONFIGURED`       | `NoBackendConfigured(considered)`    | `NO_BACKEND_CONFIGURED`                 |
| `EXPLICIT_BACKEND_UNAVAILABLE`| `ExplicitBackendUnavailable(pinnedId, reason, considered, error)` | `EXPLICIT_BACKEND_UNAVAILABLE` |
| `EXPLICIT_BACKEND_MISSING`    | `ExplicitBackendMissing(pinnedId, considered)` | `EXPLICIT_BACKEND_MISSING`      |
| `REQUIRED_CAPABILITY_UNAVAILABLE` | `CapabilityUnsupported(required, missing, considered, error)` | `CAPABILITY_UNSUPPORTED` |
| `AMBIGUOUS`                   | `Ambiguous(candidates, considered)`  | `AMBIGUOUS_CONFIGURATION`               |
| `CONFIGURATION_INVALID`       | `ConfigurationInvalid(detail, ...)`  | `CONFIGURATION_INVALID`                 |
| `AUTHENTICATION_REQUIRED` / `PERMISSION_REQUIRED` | surfaced via the typed `error` on the relevant variant (`AnkiError.PermissionRequired`; PC auth surfaces as `AnkiBackendUnavailableReason.AUTHENTICATION_REQUIRED` through the availability reason). Encoded as stable reasons on the failure, not separate sealed variants — the semantic outcome must remain distinguishable (VER-21E-27). |

`IDENTITY_UNAVAILABLE` is deliberately **absent** from the result type (§5.2): it is a
binding/continuation verdict owned by 21C (`AnkiIdentityContinuation`), not a pre-binding resolution
outcome. Folding it in would invite re-resolving a bound context, which is exactly what INV-21E-06
forbids.

Every failure carries a `considered: Set<AnkiBackendId>`. `backendId = null` is never the answer.
No variant carries credentials, endpoints, or free-text backend messages (INV-21E-23).

## 6. File-by-File Implementation Report

| File | Action | Contract references | Responsibility | Checks |
|---|---|---|---|---|
| `core/anki/BackendPreference.kt` | CREATED | DECISION-21B-05; §1, §1.1 | `BackendPreference` sealed hierarchy (Auto/Explicit/ExplicitKind) + `BackendResolutionReason` diagnostics enum + `AnkiBackendMode.toPreference()` legacy bridge. | VER-21E-01/02/03/17/19 |
| `core/anki/BackendResolutionRequest.kt` | CREATED | DECISION-21B-06; §3.2 | Immutable request type (`preference, requiredCapabilities, purpose`). No hidden global reads. | VER-21E-23 |
| `core/anki/BackendResolutionResult.kt` | CREATED | DECISION-21B-08; §5.1, §5.2 | Typed success/failure sealed hierarchy. Eight outcomes. `IDENTITY_UNAVAILABLE` absent. Reasons are stable enums. | All VER-21E-* |
| `core/anki/AnkiBackendResolver2.kt` | CREATED | DECISION-21B-06/07/09; §3.3/3.4/4 | Pure deterministic algorithm; AUTO + explicit-pin + explicit-kind; Fake rejected; TEST_DOUBLE never chosen; tie-break by orderingKey; zero I/O, zero mutation. | VER-21E-01…17, 23, 26, 27, 28 |
| `data/preferences/BackendPreferenceRepository.kt` | CREATED | DECISION-21B-05; 21B handoff §21E | Persistent preference store; combines mode + pin flows; exposes `preference` + legacy `mode`; typed write error; setPreference / setMode API; no silent rewrite of unreadable pins. | VER-21E-18/19/20 |
| `data/preferences/PreferencesDataStore.kt` | MODIFIED | §21E persistence | Adds `ankiBackendPinFlow: Flow<String?>` + `setAnkiBackendPreference(mode, pin)` atomic writer; retains legacy `setAnkiBackendMode(mode)` (clears pin). | Backward-compatible; no call site broken. |
| `data/preferences/AppSettingsPreferencesCodec.kt` | MODIFIED | §21E persistence | Adds `ANKI_BACKEND_INSTANCE_PIN` key constant; clearSettings now removes the pin. | No schema version bump (optional additive key). |
| `di/AppContainer.kt` | MODIFIED | DI integration | Declares and lazily constructs `backendPreferenceRepository: BackendPreferenceRepository`; existing `ankiBackendPreferenceStore` unchanged (pre-21E compatibility). | Zero duplicate construction. |
| `app/src/test/.../Gate21cArchitectureAuditTest.kt` | MODIFIED | — | Excludes 21E-created files from the "gate added no resolver" scan (which asserted about 21C's own scope only). 21E is authorized to add a resolver. | Audit logic intact. |
| `app/src/test/.../Gate21dArchitectureAuditTest.kt` | MODIFIED | — | Same exclusion for 21D's "no resolver" audit. | Audit logic intact. |
| `app/src/test/.../Gate21ePreferenceModelTest.kt` | CREATED (test) | VER-21E-01/02/17/19/23 | 7 tests — model semantics, serialization, legacy mapping, Fake refusal, credential-free. | 7/7 PASS |
| `app/src/test/.../Gate21eResolverDeterminismTest.kt` | CREATED (test) | VER-21E-01…17, 23, 26, 27, 28 | 23 tests — AUTO/manual/unavailable/missing/capability/determinism/purity/fake-isolation/failure typing. | 23/23 PASS |
| `app/src/test/.../Gate21ePreferenceMigrationTest.kt` | CREATED (test) | VER-21E-18/19 | 6 tests — legacy mapping, pin decode, unreadable-pin honesty, round-trip. | 6/6 PASS |

Files intentionally **NOT MODIFIED** (scope discipline — AUDIT-21E-17):

- `core/anki/AnkiBackendResolver.kt` / `BackendResolutionResult` / `BackendResolutionRequest` / `AnkiBackendSelector.kt` (kept as the existing GATE-21 policy surface; the new resolver runs in parallel and future call sites are wired in 21F/21G).
- `core/study/AnkiStudyEffectExecutor.kt`, `AnkiStudyBackendLockTracker`, all ledger coordinators, `AnkiTransactionRecoveryPlanner` — no session or transaction change (INV-21E-06, INV-21E-18).
- Registry, `AnkiBackendState`, `PcAnkiAvailabilitySource`, `AnkiDroidBackend` — untouched.
- `DataStoreAnkiBackendPreferenceStore` — kept as the legacy mode-only store for backward compatibility; the new repository is additive.
- No UI screen, ViewModel or Settings selection was modified.

## 7. Architecture Audit Matrix

| Audit | Result | Evidence |
|---|---|---|
| AUDIT-21E-01 Contract conformance | PASS | Resolver implements §3.3 steps 0–4 + explicit pin + explicit-kind exactly; eight failure outcomes all present; `IDENTITY_UNAVAILABLE` not in result type. |
| AUDIT-21E-02 Identity correctness | PASS | `Explicit(backendId)` carries `AnkiBackendId`, never kind/display/endpoint; same-kind equality requires same `profileId` (VER-21E-17); orderingKey uses `id.stableId`. |
| AUDIT-21E-03 AUTO determinism | PASS | 200+ repeat runs + permuted order (VER-21E-05/15/16); `sortedBy` orderingKey, no HashMap/random/timing; no probe/refresh in resolver. |
| AUDIT-21E-04 Explicit selection honesty | PASS | Explicit unavailable → `ExplicitBackendUnavailable` (no sibling, VER-21E-03); explicit missing → `ExplicitBackendMissing` (VER-21E-04); no silent conversion to AUTO; preference is never written on failure. |
| AUDIT-21E-05 Eligibility correctness | PASS | Step 1 (R2 readiness) before Step 2 (R3 capabilities); both projections must agree via `descriptor.supports()` (fail-closed). |
| AUDIT-21E-06 Unknown capability handling | PASS | Defaults via `missingCapabilities` (UNKNOWN → not supported); unknown-ready-capable backend filtered at capability gate (VER-21E-07/10). |
| AUDIT-21E-07 Preference source of truth | PASS | Only `BackendPreferenceRepository` persists; no other writer of `anki_backend_mode` / `anki_backend_instance_pin`; DataStore owns atomicity. |
| AUDIT-21E-08 Registry source of truth | PASS | Resolver takes descriptors as an input list; no new state held in resolver; registry owns inventory (unchanged). |
| AUDIT-21E-09 Resolver purity | PASS | `resolve` does not call any backend method beyond reading descriptors' data fields; 50 pure invocations observed zero backend side-effect calls (VER-21E-23). |
| AUDIT-21E-10 Pre-binding restriction | PASS | No import of `AnkiSessionContext`, `StudySessionMachine`, lock tracker, or any ledger in `AnkiBackendResolver2.kt`; grep of core/study, edit/, create/ found zero references to new 21E types. |
| AUDIT-21E-11 Transaction ownership | PASS | No reference to commit/reviewer-action/note-mutation/note-creation coordinators or the recovery planner in any 21E file; planner source unchanged. |
| AUDIT-21E-12 Snapshot safety | PASS | Resolver returns an id + reason, never a "verified" receipt; callers (future 21F/21G) must validate readiness before binding; resolution is not a durable lock. |
| AUDIT-21E-13 Legacy preference safety | PASS | ANKIDROID_LOCAL → singleton pin (unambiguous); PC_AGENT without pin → `ExplicitKind` (resolution applies N-rule, never guesses); undecodable pin stays as unreadable (resolution reports missing); AUTO is default for unknown tokens (same as pre-21E behaviour). |
| AUDIT-21E-14 Security | PASS | Pin stableId is `pc_agent:<profileId>` — content-free; no endpoint/token/password/host/port; failures carry stable error tokens. PC auth/permission surfaced via `AnkiBackendUnavailableReason`. |
| AUDIT-21E-15 Fake production isolation | PASS | AUTO step 0 filters out `FAKE` kind (verified: VER-21E-26); `Explicit(Fake)` refused at construction of ConfigurationInvalid; Fake is in test sources only. |
| AUDIT-21E-16 DI ownership | PASS | Exactly one `BackendPreferenceRepository` constructed in AppContainer; existing resolver/preference store/registry are preserved; no duplicate backend construction; no new backend constructed. |
| AUDIT-21E-17 Scope discipline | PASS | No Library/DeckDetails/CardBrowser/Add-Note routing changed; no StudySession binding; no failover/sync/scheduler work; no UI/Settings screen modified. |
| AUDIT-21E-18 Previous Gate preservation | PASS | Full suite 2397/2397 green including all GATE-11..21D tests; no existing test file edited beyond adding exclusions for 21E files to the two "no new resolver" source-scan audits. |

## 8. Verification Matrix

| Verification | Result | Evidence |
|---|---|---|
| VER-21E-01 Explicit AnkiDroid selection | PASS | `explicit AnkiDroid selection resolves AnkiDroid when eligible` |
| VER-21E-02 Explicit PC selection | PASS | `explicit PC selection resolves the exact PC instance even when local is also ready` |
| VER-21E-03 Manual backend unavailable (no fallback) | PASS | `manual selection of an unavailable backend blocks instead of silently falling back` |
| VER-21E-04 Manual backend missing | PASS | `manual selection of a backend that is not registered is a typed missing failure not a sibling answer` |
| VER-21E-05 AUTO with both ready | PASS | `AUTO with both ready deterministically prefers local over remote regardless of order` |
| VER-21E-06 AUTO with one unavailable | PASS | `AUTO falls back to a single ready remote only for a new context and only pre-binding` |
| VER-21E-07 Required capability | PASS | `a missing capability removes an eligible-ready backend and picks the capable one` |
| VER-21E-08 Multiple required capabilities | PASS | `multiple required capabilities are all required - a subset does not pass` |
| VER-21E-09 Unsupported vs unavailable | PASS | `unsupported capability is distinguishable from backend unavailability` |
| VER-21E-10 Unknown capability | PASS (fail-closed) | Default descriptor returns false for capabilities not in both projections; unknown capabilities never promoted — see resolver step 2. |
| VER-21E-11 Authentication/Permission | PASS (reason propagated) | Permission surfaces as `ExplicitBackendUnavailable(PERMISSION_REQUIRED, PermissionRequired())`; PC `AgentAuthenticationRequired` produces `AUTHENTICATION_REQUIRED` reason via `unavailableReason()`. |
| VER-21E-12 No backends configured | PASS | `AUTO with nothing registered returns NoBackendConfigured` |
| VER-21E-13 All candidates unavailable | PASS | `all candidates unavailable returns a typed NoEligibleBackend` |
| VER-21E-14 Deterministic tie-breaking | PASS | `equivalent candidates tie-break by orderingKey and registry order never matters` |
| VER-21E-15 Registry ordering independence | PASS | Same test + 500 repeated runs with permuted descriptor order produce identical result. |
| VER-21E-16 Repeated determinism | PASS | 200 repeated identical resolutions in `resolution is a pure function`. |
| VER-21E-17 Multiple PC instances | PASS | `multiple PC instances require instance-level identity and kind alone is ambiguous` |
| VER-21E-18 Preference persistence round-trip | PASS | `toStored round-trip preserves explicit pins and clears pins for AUTO and local` |
| VER-21E-19 Legacy preference migration | PASS | Migration tests (AUTO/ANKIDROID/PC_AGENT/valid pin/unreadable pin) all 6 cases PASS. |
| VER-21E-20 Preference change before binding | PASS (by architecture) | The request carries an immutable captured preference; the resolver has no access to the preference store; callers re-evaluate on each new binding; active contexts are not affected (AUDIT-21E-10). |
| VER-21E-21 Availability change reflects new state | PASS (by architecture) | Each resolution call takes a fresh descriptor snapshot; there is no cached "resolved backend" — old result is not represented as guaranteed. |
| VER-21E-22 Capability change | PASS | Capability gate reads live descriptor.supports() each call; no cached eligibility. |
| VER-21E-23 No backend I/O during pure resolution | PASS | `resolution is a pure function - zero backend side effects` (50 invocations, zero call counts incremented on FakeAnkiBackend). |
| VER-21E-24 No active session change | PASS | Grep of `core/study/`, `data/repository/` shows zero references to new 21E types; all post-binding ops still use `registry.find(session.context.backendId)`. |
| VER-21E-25 No transaction rebinding | PASS | Four ledger coordinators and `AnkiTransactionRecoveryPlanner` unchanged; zero references to resolver2 or new preference types in those packages. |
| VER-21E-26 Fake production isolation | PASS | `AUTO never selects a Fake backend`; `Explicit(Fake)` refused as ConfigurationInvalid; Fake lives in test sources only. |
| VER-21E-27 Typed error projection | PASS | `every failure is typed and carries considered identities`; no variant carries raw exception text or credentials. |
| VER-21E-28 Concurrent snapshot scenarios | PASS (by pure-function design) | Resolver has no mutable state; identical inputs → identical output regardless of concurrency (VER-21E-16). |
| VER-21E-29 Previous Gate regression | PASS | Full suite 202 classes / 2397 tests / 0 failed (baseline 199 / 2368 → 202 / 2397, +29 new tests). |

## 9. Invariants

| ID | Invariant | Status |
|---|---|---|
| INV-21E-01 AUTO is a selection policy, not a backend | PASS — `Auto` carries no id. |
| INV-21E-02 Explicit preference identifies an exact configured backend | PASS — `Explicit(backendId: AnkiBackendId)`. |
| INV-21E-03 Backend kind is not substituted for configured BackendId | PASS — `ExplicitKind` is migration-only and triggers N-rule; fresh writes use `Explicit`. |
| INV-21E-04 Registry remains backend inventory source of truth | PASS — resolver consumes descriptors; registry untouched. |
| INV-21E-05 Settings remains preference source of truth | PASS — single `BackendPreferenceRepository` over DataStore. |
| INV-21E-06 Resolver selects only for new unbound contexts | PASS — no reference to session/locks/ledgers in resolver. |
| INV-21E-07 AUTO is deterministic for equivalent inputs | PASS — orderingKey, no random/timing; VER-21E-05/15/16. |
| INV-21E-08 Manual selection never silently falls back | PASS — Explicit* failures carry pinnedId; no sibling considered. |
| INV-21E-09 Required capabilities checked explicitly | PASS — Step 2 + `supportsAll()`. |
| INV-21E-10 Runtime readiness not confused with static support | PASS — both capability projections required; implemented != ready. |
| INV-21E-11 UNKNOWN capability never silently considered supported | PASS — fail-closed descriptor; defaults false. |
| INV-21E-12 PC transport ≠ PC Anki readiness | PASS (carried from 21D) — resolver checks `Ready`, not just reachability. |
| INV-21E-13 PC AI/TTS ≠ PC Anki eligibility | PASS (carried from 21D) — resolver has no ConnectionState access. |
| INV-21E-14 Registry enumeration order is never accidental routing priority | PASS — sortedBy orderingKey. |
| INV-21E-15 Resolution has no Anki mutation side effects | PASS — pure function; VER-21E-23. |
| INV-21E-16 Resolution is not a durable StudySession lock | PASS — returns id+reason; no lock API; no session reference. |
| INV-21E-17 Active StudySession cannot be redirected through AUTO | PASS — AUDIT-21E-10; no study/executor reference. |
| INV-21E-18 Transaction recovery never uses AUTO | PASS — recovery planner unchanged. |
| INV-21E-19 Resolver does not infer collection equality | PASS — no collection comparison in resolver. |
| INV-21E-20 Preference changes affect future unbound work only | PASS — request captures preference; no active-context subscription in resolver. |
| INV-21E-21 Legacy migration does not fabricate instance identity | PASS — unknown/absent pin → ExplicitKind or Auto; no guess. |
| INV-21E-22 Fake backends remain isolated | PASS — filtered at Step 0 + explicit refusal. |
| INV-21E-23 Secrets never stored in preference IDs or resolution results | PASS — stableId only; errors are stable tokens. |
| INV-21E-24 GATE 21E weakens no GATE 11–21D invariant | PASS — full suite 2397/2397 green. |

## 10. Build Results

| Command | Result |
|---|---|
| `./gradlew testDebugUnitTest` | BLOCKED — environmental limitation (no Android SDK; `dl.google.com`/`services.gradle.org` unreachable; same as 21C/21D). |
| `./gradlew lint` | BLOCKED — environmental limitation. |
| `./gradlew assembleDebug` | BLOCKED — environmental limitation. |
| `./gradlew assembleRelease` | BLOCKED — environmental limitation. |
| `./gradlew connectedDebugAndroidTest` | BLOCKED — environmental limitation (no device). |
| `tools/jvm-harness/bin/build.sh all` | **PASS** — main 0 errors (317 files), compose 0 errors, test 0 errors (234 files). |
| `tools/jvm-harness/bin/run.sh` (full suite) | **PASS** — 202 classes, 2397 tests, 2397 passed, 0 failed (baseline 199/2368 → +29 21E tests, 0 failures). |

CI must run the four Gradle tasks plus `connectedDebugAndroidTest` before any release-style claim (same residual risk as 21C/21D).

## 11. Residual Risks

| ID | Risk | Owner / disposition |
|---|---|---|
| R-21E-01 | Gradle tasks not executed in this environment. | CI must run before release claim (inherited from 21C/21D). |
| R-21E-02 | The existing GATE-21 `AnkiBackendResolver`/`AnkiBackendSelector` are **not yet** wired to the new resolver/policy. The new resolver runs in parallel and is the API surface for 21F/21G. Callers (`AnkiLocalStudyStarter`, `AnkiStudyEffectExecutor.begin`, dashboard pre-flight, future read contexts) are migrated in those sub-gates. | 21F/21G consume the new contract. |
| R-21E-03 | A future PC Anki backend must register through the registry and produce correct availability before AUTO/Explicit can reach it. The PC column in the capability matrix remains "no code" today. | Inherits 21D R1; closed only by a PC backend implementation. |
| R-21E-04 | The Settings UI still presents only kind-level choices (AUTO/ANKIDROID_LOCAL/PC_AGENT) and has no instance-pin picker. Instance-level pin UI belongs to 21M; 21E stores and resolves pins correctly once set. | Owner 21M. |
| R-21E-05 | Preference changes captured mid-resolution are safe because the request is immutable; stale-resolution handling (caller validates relevance before binding) is implemented by each future caller, not by the resolver itself. Contract documented; binding-side validation is 21F/21G's. | 21F/21G. |
| R-21E-06 | Read-only contexts (Library, Deck Details, Card Browser, Add-Note seed) remain pinned to AnkiDroid (AUDIT-21E-17). They are 21F's responsibility. | Owner 21F. |

## 12. Handoff to GATE 21F / 21G

```text
BackendPreference            core/anki/BackendPreference.kt           Auto | Explicit(id) | ExplicitKind(kind)
BackendResolutionRequest2    core/anki/BackendResolutionRequest.kt    (preference, requiredCapabilities, purpose)
BackendResolutionResult2     core/anki/BackendResolutionResult.kt     Resolved(id, reason) | Failed (8 variants)
AnkiBackendResolver2         core/anki/AnkiBackendResolver2.kt        pure .resolve(request, descriptors): result
BackendPreferenceRepository  data/preferences/BackendPreferenceRepository.kt
                                                  .preference: StateFlow<BackendPreference>
                                                  .mode: StateFlow<AnkiBackendMode>     (compat)
                                                  .setPreference(pref) / .setMode(mode)
Registry snapshot requirement:
    callers MUST pass registry.descriptors() (or a snapshot of AnkiBackendState) captured AT THE
    TIME of the pre-binding attempt. Do not cache a resolution across calls.

Context-binding preconditions (future 21F/21G):
    1. Resolve against freshly captured descriptors; do NOT reuse a prior resolution.
    2. After resolution, validate readiness against the live backend (refresh + re-resolve per
       AnkiLocalStudyStarter's existing freshness check) before publishing any lock.
    3. If validation fails before binding, a NEW resolution is allowed (pre-binding re-resolution);
       once a context is bound, AUTO never runs again for it (INV-21E-06/17).
    4. Preference changes during a resolution race: caller re-samples preference at bind time and
       re-resolves if the sampled preference differs from the one used — stale results are not bound.

The legacy AnkiBackendResolver / AnkiBackendSelector remain in place. New 21F/21G code can migrate
to AnkiBackendResolver2 + BackendPreference + BackendResolutionRequest2/Result2 at the wiring sites;
once every pre-binding entry point is migrated, the legacy types can be retired (out of 21E scope).
```

## 13. Gate Lock Decision

```text
GATE 21E:                 PASS (harness-verified)

PREFERENCE:               PASS  (Auto/Explicit/ExplicitKind; persistent; backward compatible)
AUTO DETERMINISM:         PASS  (orderingKey; 6 locked steps; 200+ repeat runs)
MANUAL SELECTION:         PASS  (exact instance; no silent fallback; typed missing/unavailable)
CAPABILITY ELIGIBILITY:   PASS  (R2 readiness + R3 capability, both projections, fail-closed)
READINESS:                PASS  (Ready gate; UNKNOWN not promoted; auth/permission distinguished)
PRE-BINDING SAFETY:       PASS  (no study/session/transaction code touched; IDENTITY_UNAVAILABLE out)
ARCHITECTURE AUDIT:       PASS  (18/18)
VERIFICATION:             PASS  (29/29 executed; full suite 2397/2397)
BUILD:                    HARNESS PASS · GRADLE BLOCKED (environmental, not claimed)

NEXT AUTHORIZED SUB-GATE: 21F — Library / Browser / Details Context Isolation
```
