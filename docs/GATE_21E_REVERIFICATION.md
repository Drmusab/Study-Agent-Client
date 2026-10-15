# GATE 21E — Current Checkout Re-verification

**Gate result:** `BLOCKED` from formal lock. The 21E preference/resolver implementation and JVM regression checks pass, but required Gradle tasks could not start because the wrapper could not download Gradle 8.7. There is also an existing consumer-boundary gap: StudySession start still samples the legacy kind-only preference, so this report does not claim exact persisted pins or invalid-storage failures are honored by that path.

| Field | Value |
|---|---|
| Repository | `Drmusab/Study-Agent-Client` |
| Branch | `arena/1a0b4e40-study-agent-client` |
| Base/current HEAD reviewed | `be84077cab329a8194485a60a4855f06a29f6c4c` |
| Re-verification date | 2026-10-15 (UTC) |
| Scope | GATE 21E only; this execution did not start or modify GATE 21F/21G work |
| Historical report | [`GATE_21E_RESOLVER_IMPLEMENTATION.md`](GATE_21E_RESOLVER_IMPLEMENTATION.md), original snapshot at `afa58cb`; this report supersedes its current-status claims |

```text
GATE 21E:                   BLOCKED from formal lock
PREFERENCE / RESOLVER:      PASS at repository and pure-policy layer
ARCHITECTURE AUDIT:         18 checks reviewed; 17 PASS, 1 PARTIAL at consumer boundary
FOCUSED JVM TESTS:          PASS — 96/96
FULL JVM REGRESSION:        PASS — 2,643/2,643
JVM HARNESS COMPILE:        PASS — main 0 errors; Compose 0 errors; tests 0 errors
GRADLE VALIDATION:          BLOCKED — wrapper TLS failure before Gradle or project tasks started
NEXT GATE:                  NOT AUTHORIZED by this report
```

---

## 1. Gate scope and locked prerequisites

The re-verification used the repository's locked contracts, especially `BACKEND_SELECTION_POLICY.md` and `GATE_21B_CONTRACT_LOCK.md`, rather than deriving new routing rules. The stated prerequisites remain:

| Gate | Contract/status source | Current regression evidence |
|---|---|---|
| 21A | `GATE_21A_DISCOVERY.md` — PASS/LOCKED | Included in the passing full JVM suite |
| 21B | `GATE_21B_CONTRACT_LOCK.md`, `BACKEND_SELECTION_POLICY.md` — LOCKED | Selection and architecture tests pass; no locked policy document was changed |
| 21C | `GATE_21C_IDENTITY_IMPLEMENTATION.md` — LOCKED | Included in the passing full JVM suite |
| 21D | `GATE_21D_REGISTRY_IMPLEMENTATION.md` and `GATE_21D_REVERIFICATION.md` — LOCKED | Registry and architecture tests pass; no Registry implementation was changed |

This was not a re-implementation of 21F or 21G. Registry membership, active StudySession lock ownership, and transaction destinations remain separate authorities. No active session is re-resolved, and no ledger/recovery path is routed through AUTO.

### Locked selection behavior checked

- **AUTO:** filter to real configured candidates; require `Ready` (R2), then all requested capabilities in both projections (R3); prefer local; select a sole eligible remote; refuse multiple eligible remotes as ambiguous; never select Fake or use input order as priority.
- **Manual exact pin:** resolve the exact `AnkiBackendId` or fail with a typed missing/unavailable/capability/configuration result; never substitute a sibling.
- **Legacy kind-only PC preference:** 0 configured PC instances gives a typed explicit-unavailable result; 1 instance is evaluated as that exact candidate; 2 or more configured instances are ambiguous even if only one is ready.
- **Ownership:** preference, Registry inventory/state, Resolver result, StudySession lock, and transaction destination remain distinct.

The empty-AUTO-inventory implementation uses the more specific `NoBackendConfigured` failure shape (empty `considered` set); this is treated as the no-eligible-backend case where no accepted descriptor exists. The legacy resolver projects it to the old `NoBackendAvailable` result shape.

---

## 2. Preference, migration, resolver, and DI findings

### Persistent preference

`BackendPreferenceRepository` decodes mode and optional pin from one DataStore snapshot. Storage and decode behavior verified in source and tests:

| Stored state | Domain value |
|---|---|
| No mode | `Auto` (locked fresh-install default) |
| `auto` with any orphan pin | `Auto`; the pin is irrelevant to AUTO |
| `ankidroid_local` | `Explicit(AnkiDroidLocal)`; singleton identity |
| `pc_agent` without pin | `ExplicitKind(PC_AGENT)`; no profile is guessed |
| `pc_agent` with a valid `pc_agent:<profileId>` pin | `Explicit(PcAgent(profileId))` |
| Well-formed pin for a removed profile | Preserved as that exact `Explicit` identity; resolver reports missing |
| Unknown mode, malformed pin, or pin-kind mismatch | Content-free `InvalidStored`; resolver returns `ConfigurationInvalid`, not AUTO |

Writes encode mode and pin atomically. AUTO and AnkiDroid clear the pin; exact PC selection writes the PC stable identity. Invalid persisted preferences and Fake IDs are refused rather than rewritten as AUTO. `DataStoreAnkiBackendPreferenceStore` is now a mode-only facade over the same repository and write/error state, not a second preference owner. `AppContainer` constructs one repository and injects it into that facade.

`PreferencesDataStore.ankiBackendModeFlow` remains a compatibility projection and still maps unknown legacy mode tokens to AUTO. New code must use `BackendPreferenceRepository.preference` when it needs invalid-state or exact-pin semantics; this limitation is material to the consumer-boundary finding below.

### Resolver

`AnkiBackendResolver2` is the authoritative instance-aware policy. `AnkiBackendResolver` retains the old API as an adapter, projects results for old callers, and exposes an instance-aware overload. The duplicate legacy routing algorithm was removed. The typed model now includes explicit-kind-zero-instance failure and nullable unavailable reason for `Checking`; authentication and permission classifications stay distinct. A missing explicit backend keeps the legacy facade's default `AnkiError.BackendUnavailable()` contract.

Capability eligibility checks R2 before R3, and `AnkiBackendDescriptor.supportsAll` requires the live and availability-snapshot projections to agree. Resolution itself has no probe, preference-write, registry-write, or transaction side effect.

### Consumer-boundary limitation — not changed in this gate

The new read-context controller consumes `backendPreferenceRepository.preference`. The existing StudySession-start path does not yet consume the full preference:

- `AppContainer` passes `ankiBackendPreferenceStore.mode.value` to `AnkiLocalStudyStarter`.
- `AnkiStudyEffectExecutor.begin` converts `AnkiStudyRequest.preference` (an `AnkiBackendMode`) using `toPreference()` before calling `AnkiBackendResolver2`.

Consequently, that path cannot preserve a stored exact PC profile pin; an invalid stored mode can also be represented to that legacy path as AUTO. This report deliberately does **not** alter the starter, StudySession request, or executor because the instruction is GATE 21E only and explicitly forbids implementing 21F/21G behavior. Therefore, the resolver and repository support exact pin semantics, but this report does not claim end-to-end exact-pin enforcement for StudySession starts. Resolve this at an authorized later integration gate before claiming that every new-session route consumes the full preference.

---

## 3. File-by-file change report

| File | Change made in this working tree | Contract / evidence |
|---|---|---|
| `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendResolver.kt` | Replaced duplicate selection logic with a compatibility projection over Resolver2; added exact instance-aware overload; corrected missing-explicit-backend error projection to preserve `BackendUnavailable()` | Single policy implementation; legacy architecture contract passes |
| `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendResolver2.kt` | Typed invalid stored preference; explicit-kind N=0 outcome; N=1 reason; nullable `Checking` reason; auth/permission failure classification; deterministic capability error token | Resolver tests and full suite |
| `app/src/main/java/com/studyagent/client/core/anki/BackendPreference.kt` | Added content-free `InvalidStored` state and issue categories | Preference model/migration tests |
| `app/src/main/java/com/studyagent/client/core/anki/BackendResolutionResult.kt` | Added `ExplicitBackendKindUnavailable`; made unavailable reason nullable for `Checking` | Resolver tests; typed failure projection |
| `app/src/main/java/com/studyagent/client/data/preferences/BackendPreferenceRepository.kt` | Decodes an atomic stored snapshot; validates encoding; refuses invalid/Fake writes; exposes full preference and compatibility mode flow | Preference migration tests |
| `app/src/main/java/com/studyagent/client/data/preferences/DataStoreAnkiBackendPreferenceStore.kt` | Changed to a facade over the single repository | DI/source audit |
| `app/src/main/java/com/studyagent/client/data/preferences/PreferencesDataStore.kt` | Added atomic mode+pin snapshot and atomic setter; retained legacy mode projection/writer | Storage mapping tests and source review |
| `app/src/main/java/com/studyagent/client/di/AppContainer.kt` | Constructs one full repository and injects it into the compatibility adapter | DI source audit |
| `app/src/test/java/com/studyagent/client/anki/AnkiBackendSelectorTest.kt` | Verifies AUTO may select one ready remote, while kind-only PC remains ambiguous for multiple configured instances even if one is unavailable | Full suite |
| `app/src/test/java/com/studyagent/client/anki/Gate21ArchitectureAuditTest.kt` | Updated source audit for the compatibility adapter and the one authoritative Resolver2 policy | Full suite |
| `app/src/test/java/com/studyagent/client/anki/Gate21BackendResolutionTest.kt` | Added legacy kind-only PC ambiguity coverage | Full suite |
| `app/src/test/java/com/studyagent/client/anki/Gate21ePreferenceMigrationTest.kt` | Covers unknown mode, malformed/mismatched pin, exact pin restoration, deleted profile, and encode/decode behavior | Full suite |
| `app/src/test/java/com/studyagent/client/anki/Gate21ePreferenceModelTest.kt` | Covers serializable invalid preference state | Full suite |
| `app/src/test/java/com/studyagent/client/anki/Gate21eResolverDeterminismTest.kt` | Covers determinism, N-rule, error projection, facade delegation, purity, `Checking`, and authentication classification | Full suite |
| `docs/GATE_21E_RESOLVER_IMPLEMENTATION.md` | Added a historical-snapshot notice and pointer to this report | Prevents stale status from being mistaken for the current run |
| `docs/GATE_21E_REVERIFICATION.md` | Added this authoritative current-checkout report | Current status/evidence |

No Registry, StudySession lock, StudySession executor, transaction coordinator, ledger, or recovery-planner implementation was modified in this GATE 21E working diff. The existing 21F/21G integration gap is documented above rather than changed.

---

## 4. Architecture audit

| Audit | Result | Evidence / scope note |
|---|---|---|
| AUDIT-21E-01 Contract conformance | PASS | Resolver implements local-first AUTO, single-remote resolution, multi-remote ambiguity, and locked kind-level N-rule |
| AUDIT-21E-02 Identity correctness | PASS | Exact preference carries `AnkiBackendId`; valid removed-profile pin remains exact |
| AUDIT-21E-03 AUTO determinism | PASS | Stable `orderingKey`; permuted input and repeated-resolution tests pass |
| AUDIT-21E-04 Explicit selection honesty | PASS | Exact pin missing/unavailable never resolves a sibling; legacy kind N>=2 refuses |
| AUDIT-21E-05 Eligibility correctness | PASS | Ready gate precedes required-capability gate; descriptor checks both projections |
| AUDIT-21E-06 Unknown capability handling | PASS | Safe all-false capability default and disagreement fail-closed tests pass |
| AUDIT-21E-07 Preference source of truth | PASS | One repository owns decode/write; legacy store delegates to it |
| AUDIT-21E-08 Registry source of truth | PASS | Resolver receives descriptor snapshots; Registry code unchanged |
| AUDIT-21E-09 Resolver purity | PASS | 50-resolution side-effect test and existing pure-resolution test pass |
| AUDIT-21E-10 Pre-binding restriction | PASS | Resolver has no session/ledger ownership; architecture audit passes |
| AUDIT-21E-11 Transaction ownership | PASS | No transaction/recovery code changed; source audit says recovery routes by persisted identity |
| AUDIT-21E-12 Snapshot safety | PASS | Resolver returns a selected identity/result, not a durable lock; admission rechecks remain separate |
| AUDIT-21E-13 Legacy preference safety | PARTIAL | Repository represents invalid storage honestly, but legacy mode projection still maps unknown values to AUTO and StudySession start still consumes mode-only preference |
| AUDIT-21E-14 Security | PASS | Stored pin is the backend stable identity; invalid issue and resolver errors do not retain raw malformed text or credentials |
| AUDIT-21E-15 Fake isolation | PASS | AUTO filters Fake; explicit Fake and Fake writes are rejected |
| AUDIT-21E-16 DI ownership | PASS | One `BackendPreferenceRepository` in `AppContainer`; mode-only interface is an adapter |
| AUDIT-21E-17 Scope discipline | PASS | No active-session failover, transaction rerouting, or 21F/21G implementation change in this diff |
| AUDIT-21E-18 Prior-gate regression | PASS (JVM) | Full suite passes 2,643/2,643; Android Gradle validation remains blocked |

The consumer-boundary limitation is intentionally recorded as PARTIAL, not silently counted as a full end-to-end pass.

---

## 5. Verification matrix (VER-21E-01 through VER-21E-29)

| ID | Verification | Result | Evidence |
|---|---|---|---|
| VER-21E-01 | Explicit AnkiDroid selection | PASS | `Gate21eResolverDeterminismTest.explicit AnkiDroid selection resolves AnkiDroid when eligible` |
| VER-21E-02 | Exact explicit PC selection | PASS | `...explicit PC selection resolves the exact PC instance even when local is also ready` |
| VER-21E-03 | Explicit unavailable backend has no fallback | PASS | `...manual selection of an unavailable backend blocks instead of silently falling back` |
| VER-21E-04 | Missing exact identity does not select a sibling | PASS | `...backend that is not registered is a typed missing failure not a sibling answer` |
| VER-21E-05 | AUTO local-first when both are ready | PASS | `...AUTO with both ready deterministically prefers local over remote regardless of order` |
| VER-21E-06 | AUTO selects a sole eligible remote pre-binding | PASS | `...AUTO falls back to a single ready remote only for a new context and only pre-binding` |
| VER-21E-07 | Required capability filters candidates | PASS | `...a missing capability removes an eligible-ready backend and picks the capable one` |
| VER-21E-08 | All required capabilities are required | PASS | `...multiple required capabilities are all required - a subset does not pass` |
| VER-21E-09 | Unsupported capability differs from unavailable backend | PASS | `...unsupported capability is distinguishable from backend unavailability` |
| VER-21E-10 | Unknown/unsupported capability claims fail closed | PASS | Safe all-false defaults and disagreeing capability projections are covered by `AnkiArchitectureContractTest` and `AnkiBackendSelectorTest` |
| VER-21E-11 | Authentication/permission causes stay typed | PASS | Permission failure and explicit PC authentication failure tests assert distinct failure classifications; availability reason mapping is covered by 21D tests |
| VER-21E-12 | No acceptable AUTO backend | PASS | Empty accepted inventory returns typed `NoBackendConfigured` (specific no-eligible case); legacy projection remains `NoBackendAvailable` |
| VER-21E-13 | All candidates unavailable | PASS | `...all candidates unavailable returns a typed NoEligibleBackend` |
| VER-21E-14 | Deterministic ordering key | PASS | `...equivalent candidates tie-break by orderingKey and registry order never matters` |
| VER-21E-15 | Registry order independence | PASS | Permuted descriptors in the determinism tests yield the same resolution |
| VER-21E-16 | Repeated identical resolution | PASS | 200 repeated identical resolver calls are asserted in the determinism test |
| VER-21E-17 | Multiple PC profiles are ambiguous | PASS | Kind-only N>=2 remains ambiguous even when one profile is unavailable; exact pin selects the named profile |
| VER-21E-18 | Preference encoding/decoding round trip | PASS | Valid Auto, local, PC pin, and legacy PC kind round-trip tests |
| VER-21E-19 | Legacy preference migration | PASS | Missing mode, unknown mode, orphan pin, old local/PC values, malformed/mismatched pin, and removed profile tests |
| VER-21E-20 | Preference change affects future unbound work only | PARTIAL | Read-context controller samples/revalidates preference before publishing a new binding; the existing StudySession path remains mode-only and is not changed here |
| VER-21E-21 | Availability changes affect new resolutions | PASS (structural) | Resolver takes fresh descriptor input and holds no resolution cache; bound session lock remains unchanged |
| VER-21E-22 | Capability changes affect new resolutions | PASS (structural) | Capability gate reads descriptor projections on each call; no cached eligibility |
| VER-21E-23 | No backend I/O during pure resolution | PASS | 50 resolver calls leave fake backend read counters and readiness unchanged |
| VER-21E-24 | No active-session change | PASS | Architecture audit passes; no session lock or executor file modified |
| VER-21E-25 | No transaction rebinding | PASS | Recovery and ledger code unchanged; architecture audit passes |
| VER-21E-26 | Fake isolation | PASS | AUTO does not select Fake; explicit Fake preference is configuration-invalid and cannot be persisted |
| VER-21E-27 | Typed errors carry considered identities | PASS | Typed failure test covers missing identity, permission, and considered IDs; authentication classification has dedicated coverage |
| VER-21E-28 | Concurrent/snapshot determinism | PASS (by purity; no stress claim) | Resolver is stateless and only uses immutable request/descriptor values; no concurrency stress test was run |
| VER-21E-29 | Previous-gate regression | PASS (JVM harness) | Full suite: 218 classes, 2,643 tests, 2,643 passed, 0 failed, 0 ignored |

`PARTIAL` rows are not treated as passes for formal gate lock.

---

## 6. Invariants

| Invariant | Result |
|---|---|
| INV-21E-01 AUTO is policy, not identity | PASS |
| INV-21E-02 explicit preference identifies an instance | PASS at model/resolver/storage layer |
| INV-21E-03 kind-only legacy selection does not substitute a profile | PASS; N-rule enforced |
| INV-21E-04 Registry remains inventory authority | PASS |
| INV-21E-05 preference remains separate from Registry/session/transaction | PASS |
| INV-21E-06 resolver is pre-binding only | PASS |
| INV-21E-07 identical inputs yield deterministic output | PASS |
| INV-21E-08 manual selection never silently falls back | PASS |
| INV-21E-09 required capabilities are checked | PASS |
| INV-21E-10 readiness and static support remain distinct | PASS |
| INV-21E-11 unknown capability support fails closed | PASS |
| INV-21E-12 transport is not Anki readiness | PASS (inherited 21D state model + regression) |
| INV-21E-13 PC AI/TTS availability is not Anki readiness | PASS (no connection-state routing added) |
| INV-21E-14 registry iteration order is not routing priority | PASS |
| INV-21E-15 selection has no Anki mutation side effect | PASS |
| INV-21E-16 resolution is not a durable lock | PASS |
| INV-21E-17 active StudySession cannot be redirected by AUTO | PASS; no active-context routing change |
| INV-21E-18 transaction recovery never uses AUTO | PASS; recovery source audit passes |
| INV-21E-19 resolver does not infer collection equality | PASS |
| INV-21E-20 preference changes affect future unbound work only | PARTIAL at global consumer boundary (VER-21E-20) |
| INV-21E-21 migration never fabricates an instance identity | PASS in repository; legacy StudySession consumer limitation remains |
| INV-21E-22 Fake backends remain isolated | PASS |
| INV-21E-23 secrets are absent from preference/result values | PASS for explicit fields and diagnostics; stable profile identity remains the only PC discriminator |
| INV-21E-24 no prior 21A–21D invariant weakened | PASS in the JVM regression suite; Gradle validation not run |

---

## 7. Build and regression results

| Command | Result |
|---|---|
| `tools/jvm-harness/bin/build.sh all` | **PASS** — main 0 errors (328 files), Compose 0 errors (24 files; 46 stubs), tests 0 errors (250 files) |
| Focused harness: `run.sh 'Gate21e\|Gate21BackendResolutionTest\|AnkiBackendSelectorTest\|Gate21ArchitectureAuditTest\|AnkiArchitectureContractTest'` | **PASS** — 7 classes, 96 tests, 0 failures |
| Full `tools/jvm-harness/bin/run.sh` | **PASS** — 218 classes, 2,643 tests, 2,643 passed, 0 failed, 0 ignored |
| `git diff --check` | **PASS** before report creation; final check is required after documentation update |
| `./gradlew --version` | **BLOCKED** — wrapper attempted to fetch `gradle-8.7-bin.zip`; TLS ended with `SSLHandshakeException` / `EOFException` from `services.gradle.org` |
| `./gradlew --stop clean testDebugUnitTest lint assembleDebug assembleRelease connectedDebugAndroidTest` | **BLOCKED before task execution** — wrapper distribution download failed; none of these Gradle tasks ran |

The JVM harness is not evidence that Android Gradle, lint, APK packaging, or connected-device tests passed. No connected device was exercised. Do not state otherwise.

---

## 8. Residual blockers and lock decision

1. **Gradle validation is mandatory but unavailable in this sandbox.** A Gradle 8.7 distribution download was attempted; the wrapper failed during TLS setup before any project task could start. The required Gradle tasks remain unexecuted.
2. **Legacy StudySession preference consumer remains mode-only.** It cannot honor an exact persisted PC profile pin and can receive the AUTO compatibility projection for an unknown stored mode. This was intentionally not changed because the task is GATE 21E only and explicitly excludes GATE 21F/21G behavior.
3. **No runtime Android/Anki device validation.** All passing executable checks used the JVM harness; no real AnkiDroid collection or PC agent was exercised.
4. **Instance-pin UI is not part of this change.** The repository/resolver contract supports exact pins, but the existing Settings control remains kind-level.
5. **Startup hydration is not synchronized by a loading state.** The repository's `StateFlow` is seeded with AUTO until the first DataStore snapshot arrives; this implementation preserves the existing eager-default behavior, but the harness did not test an operation racing DataStore startup.

**Decision:** keep the working changes uncommitted and do not claim `GATE 21E LOCKED`. The pure preference/resolver layer, focused checks, and complete JVM regression are green; formal lock is withheld until required Gradle validation succeeds and the exact-pin/invalid-state consumer boundary is addressed at an authorized scope. No GATE 21F or 21G work was started by this report.
