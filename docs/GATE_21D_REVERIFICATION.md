# GATE 21D — Backend Registry, Availability & Capabilities: Independent Re-Verification at HEAD `25cc924`

**Status:** **RE-VERIFIED — LOCK CONFIRMED** (unchanged scope). PART V Gradle tasks remain `BLOCKED — environmental limitation`.
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/54fa2b9e-study-agent-client` (branched from `master` @ `25cc924`)
**HEAD:** `25cc924d04420829de5382dc956e42465812b772` (identical to `origin/master`; complete history, 246 commits)
**Date:** 2026-10-15
**Execution type:** prerequisite validation → revision integrity → independent architecture audit → authoritative verification re-execution → lock confirmation
**Prior gates:** GATE 21A `LOCKED`; GATE 21B `LOCKED` (contract authority); GATE 21C `LOCKED` (identity foundation)
**Original lock record (unchanged):** `docs/GATE_21D_REGISTRY_IMPLEMENTATION.md` — landed as commit `a4fc9ded0e6cdaffcb16b641d155fbbc6457e2b9` (`feat(gate21d): add observable Anki backend registry and capabilities`, 2026-10-12, 23 files / +4731 −48)
**Change set of this run:** this document only. No production file, test file, contract document or build file was modified.

```text
GATE 21D — BACKEND REGISTRY, AVAILABILITY & CAPABILITIES

READ THIS FIRST (three things that decide what this document may claim)

 1. GATE 21D WAS ALREADY IMPLEMENTED, LOCKED AND CONSUMED BY LATER GATES.
    `a4fc9de` is an ancestor of HEAD, and GATE 21E, 21F, 21G and 21J already build on the registry.
    GATE 21B DECISION-21B-10 prohibits a second registry ("a second registry is a second source of
    truth"), and the gate's own prerequisite rule forbids silently recreating an earlier Sub-Gate.
    NO RE-IMPLEMENTATION WAS PERFORMED. What this run does instead — and what the repository has
    never had — is re-EXECUTE the gate's authoritative verification (PART III) and re-derive its
    architecture audit (PART II) at the CURRENT HEAD, so the lock stops being a historical claim.

 2. GRADLE STILL CANNOT RUN IN THIS ENVIRONMENT (fresh attempt, PART V).
    Executed evidence comes from the repository's Gradle-free JVM harness (Kotlin 2.3/K2).
    That is equivalence evidence, not CI proof.

 3. THE PC ANKI BACKEND STILL DOES NOT EXIST.
    Every PC result below is structure-level (`PASS†`), exactly as in the original lock:
    verified against the real `ConnectionState` + explicit fixtures, with no producer in production.

VERDICT: the original lock holds at this HEAD. Nothing was weakened, and no 21D-owned file drifted
except one additive change owned by a later gate (PART 0.3).
```

---

# PART 0 — PREREQUISITE VALIDATION (EXECUTED)

## 0.1 Required checks

```text
Current HEAD:        25cc924d04420829de5382dc956e42465812b772
Branch:              arena/54fa2b9e-study-agent-client  (branched from master @ 25cc924)
Working-tree status: clean at the start (git status --short: empty)
origin/master:       25cc924d04420829de5382dc956e42465812b772  (identical — no divergence)
History:             complete (246 commits) after `git fetch --unshallow --no-tags origin master`

21A: LOCKED   docs/GATE_21A_DISCOVERY.md
21B: LOCKED   docs/GATE_21B_CONTRACT_LOCK.md — DECISION-21B-01…30; §PART 4 (21D entry)
21C: LOCKED   docs/GATE_21C_IDENTITY_IMPLEMENTATION.md
21D: IMPLEMENTED + LOCKED at a4fc9de (verified as an ancestor with `git merge-base --is-ancestor`)
```

| Required 21C artifact | Present at HEAD | Used by 21D |
|---|---|---|
| `AnkiBackendId` (+ `roundTripsStableId`, `discriminatorIsNormalized`, `stableId`) | `core/anki/AnkiBackendId.kt` | registration gate (`registrationRefusal()`) |
| `AnkiBackendContext` + assurance vocabulary | `core/anki/AnkiBackendContext.kt`, `AnkiIdentityAssurance.kt` | reconnect semantics (the registry asserts nothing about continuity) |
| Identity test baseline | 5 `Gate21c*` classes | green in the fresh full-suite run (PART IV) |

**Prerequisite verdict: PASS.** GATE 21D is not BLOCKED; it is landed and re-verified.

## 0.2 Revision integrity — is the locked artifact still the tested artifact?

`git diff --name-only a4fc9de..HEAD -- <the 21D change set>` was executed against every path the original
lock recorded:

| Path group | Drift since `a4fc9de` | Interpretation |
|---|---|---|
| `core/anki/AnkiBackendState.kt`, `AnkiBackendRegistration.kt`, `AnkiBackendUnavailableReason.kt`, `AnkiBackendSelector.kt` (registry), `AnkiBackendStatus.kt` (observer), `AnkiAvailability.kt`, `data/anki/remote/PcAnkiAvailabilitySource.kt`, `data/anki/ankidroid/AnkiDroidBackend.kt`, `data/anki/ankidroid/AnkiDroidHealth.kt` + the 5 UI `when`-branch files | **none** | the locked registry, state, taxonomy, availability vocabulary, observer, PC structure and adapter are byte-identical to the tested revision |
| `app/src/test/java/com/studyagent/client/anki/Gate21d*` (8 files: 6 classes / 99 tests, 2 helpers) | **none** | every test cited as evidence by the original lock is the same file that ran here |
| `docs/GATE_21D_REGISTRY_IMPLEMENTATION.md` | **none** | the lock record was not rewritten |
| `core/anki/AnkiBackend.kt` | **+13 lines** | **additive, owned by GATE 21F** (`c133fb0 Implement GATE 21F read context isolation`): a default body `val currentCollectionIdentity: AnkiCollectionIdentity? get() = null`. No 21D-member changed; every existing implementor keeps the default. Re-verified green below. |

## 0.3 Drift adjacent to 21D — consumption by later gates (expected, not a defect)

| Consumer | Introduced by | Relationship to the registry |
|---|---|---|
| `core/study/AnkiStudyEffectExecutor.kt:32` | GATE 21G | holds a registry reference (session backend lock) |
| `core/anki/edit/NoteMutationRoutingPolicy.kt:110`, `NoteMutationCoordinator.kt:165` | GATE 21J (NOT LOCKED) | holds a registry reference to resolve an owner |
| `data/repository/AnkiLocalStudyStarter.kt:54` | pre-21D | pre-existing consumer |
| `di/AppContainer.kt` | `15ee8b5` (21E), `c133fb0` (21F), `02f55c2` (21G), `ebf5575` (21J) | registration line **still** `AnkiBackendRegistry(listOf(ankiDroidBackend))` (`:534`) — one registry, one backend |
| `core/study/StudySessionMachine.kt:208,801` | `94a65bf` (GATE 10) and `dc430d5` (GATE 11) — both **pre-21D** | pre-existing `AnkiBackendRegistry(emptyList())` constructions; not introduced by 21D |

Consuming the inventory is the sanctioned dependency direction. What would violate 21D is a consumer
*becoming* membership authority — audited as absent (`AUDIT-21D-15`, PART I/II).

## 0.4 Method of this re-verification (stated, so the strength of each claim is clear)

| Layer | What was done here |
|---|---|
| Revision integrity | executed (`0.2`): the diff proves the audited/verified bytes are unchanged |
| Executed evidence | the gate's own verification classes were **re-run** at this HEAD (99/99), plus the whole suite (2629/2629) |
| Architecture audit | **re-derived independently** from current source with fresh greps/reads (PART I), not copied from the lock record |
| Verification mapping | the 26-row mapping below is the original gate's; every cited test name was confirmed to exist and every cited class passed in this run; the harness ran the unmodified test files |
| Claimed limits | Gradle `BLOCKED` (fresh attempt, PART IV); PC rows `PASS†` (no PC backend exists); device verification is not available here (R-21D-04) |

---

# PART I — INDEPENDENT ARCHITECTURE AUDIT (AUDIT-21D-01 … AUDIT-21D-16)

Re-derived at HEAD `25cc924` on 2026-10-15. Source-level checks were executed as shell queries against
the current files (Appendix A.3); behavioural checks are the `Gate21d*` classes re-run in PART II.

| AUDIT ID | RESULT | EVIDENCE |
|---|---|---|
| AUDIT-21D-01 Contract conformance | **PASS** | `class AnkiBackendRegistry` is still declared in `core/anki/AnkiBackendSelector.kt:42` — **extended in place**, no second registry, exactly as DECISION-21B-10 requires. Surface read at source: `members` (internal), `registered`, `ids`, `find`, `descriptors()`, `states()`, `stateOf()`, `register()`, `remove()`, `refresh(id)`. `Gate21dArchitectureAuditTest` `AUDIT-21D-01…` PASS. |
| AUDIT-21D-02 Identity integrity | **PASS** | Membership is `Map<AnkiBackendId, AnkiBackend>` (`AnkiBackendMembership.byId`, private constructor, `LinkedHashMap`); `find` is `membership.value[id]`; `stateOf` returns `NotRegistered(id)` when absent. Behavioural: `configured instances appear once and lookup returns exactly those objects` (`assertSame`). |
| AUDIT-21D-03 Kind ≠ instance identity | **PASS** | The registry body never mentions `kind` (executed grep; matches only in documentation comments). Keying is on `AnkiBackendId` alone. Behavioural: `kind is never the instance identity`, `two instances of one kind stay separate registry entries`. |
| AUDIT-21D-04 Single registry ownership | **PASS** | Exactly one `AnkiBackendRegistry` declaration under `app/src/main` (`AnkiBackendSelector.kt:42`). Constructions: `di/AppContainer.kt:534` (`listOf(ankiDroidBackend)`) plus two **pre-21D** `emptyList()` calls in `StudySessionMachine` (`94a65bf` GATE 10, `dc430d5` GATE 11). Behavioural: `AUDIT-21D-04…` PASS. |
| AUDIT-21D-05 No selection-policy leakage | **PASS** | Executed grep over `AnkiBackendState.kt`, `AnkiBackendRegistration.kt`, `AnkiBackendUnavailableReason.kt`, `PcAnkiAvailabilitySource.kt` for `AUTO … Preference … preferred … priority … fallback … alternate … switch … firstOrNull … .first() … minBy … maxBy … random` → **no matches**. Behavioural: `AUDIT-21D-05…` PASS. |
| AUDIT-21D-06 Availability accuracy | **PASS** | Provider / transport / authentication / Anki / collection are distinct states; `PcAnkiAvailabilityMapper.availability` and `AnkiAvailability.unavailableReason()` are `when`s with **no `else`** (a new state is a compile error, not a silent mapping). The PC ladder reads only the *kind* of `ConnectionState` — never host, port, reason, message, cause or its AI `capabilities`. Behavioural: `AUDIT-21D-06…` (2 tests), `every connection state maps to its own availability when Anki reports ready`, plus six real-chain AnkiDroid conditions. |
| AUDIT-21D-07 Capability honesty | **PASS** | `AnkiDroidBackend.implementedCapabilities` is **derived**: `withWriteSupport(AnkiDroidCompatibilityPolicy.implementedCapabilitiesFor(MAX_VALIDATED_SPEC, isReady = true))`; executed grep confirms there is **no literal `AnkiCapabilities(` claim** in the adapter, and `withWriteSupport` drops every capability whose writer is not wired (`review`, `bury`, `suspendCards`, `cardDetails`, the three edit dimensions, `createNotes`, `noteModelListing`, `storeMedia`, …). Behavioural: `AUDIT-21D-07…`, `capabilities the public API might offer are not advertised unless implemented`. |
| AUDIT-21D-08 Support vs readiness vs authorization | **PASS** | Two orthogonal enums (`AnkiImplementedSupport`, `AnkiCapabilityReadiness`) in one `AnkiCapabilityStatus` whose `init` **enforces** `READY ⇒ SUPPORTED`; there is deliberately no authorization field (authorization is the binding's, not the registry's). Behavioural: `AUDIT-21D-08…`, `implemented support and operational readiness are separate facts`. |
| AUDIT-21D-09 Concurrency safety | **PASS** | Both mutations are lock-free CAS retry loops on `MutableStateFlow<AnkiBackendMembership>` (`register`, `remove`); `refresh` re-checks membership identity after the probe. Observation is `flatMapLatest { observe(membership.backends) }` over **cold** per-backend `combine`s (no leaked collectors). Behavioural: `concurrent claims on one identity admit exactly one instance` (16 threads), `concurrent registration of distinct identities loses none`, `simultaneous updates to two backends only ever publish coherent snapshots`, `AUDIT-21D-09…`. |
| AUDIT-21D-10 State consistency | **PASS** | Executed grep for `var ` / `mutableListOf` / `mutableMapOf` / `mutableSetOf` in the state, registration, taxonomy and PC sources → **no matches**; published types are `data class`/`enum`/sealed with immutable collections; membership is `internal`. Behavioural: `AUDIT-21D-10…`, `the registry detaches the list it was built from`. |
| AUDIT-21D-11 Reconnect semantics | **PASS** | Executed grep for `AnkiCollectionIdentity`, assurance, continuity, `collectionKey`, `AnkiBackendContext` in the 21D sources → **no matches** (outside documentation). The PC structure has no vocabulary for collection continuity; a reconnect starts at `Checking`. Behavioural: `a reconnect proves reachability and asserts nothing about the collection`, `AUDIT-21D-11…`. |
| AUDIT-21D-12 Security | **PASS** | `PcAnkiAvailabilitySource.kt` imports only `core.anki`, `core.models.ConnectionState` and `kotlinx.coroutines` — no `java.net`, no `android.`, no network client. `AgentAuthenticationRequired` is a payload-free `data object`; the taxonomy is an enum (no payload to leak). No authentication mechanism was added. Behavioural: `no endpoint, credential or rejection text reaches availability`. |
| AUDIT-21D-13 No mutation during probing | **PASS** | The registry calls exactly one member on a backend: `backend.refreshAvailability()` (`AnkiBackendSelector.kt:129`), plus `backend.id` and read-only `descriptor`/`state` projections. Executed grep for `commitRating`, `applyNoteMutation`, `addNote`, `createNote`, `bury`, `storeMedia`, `nextCard`, `beginReview`, `flagCard`, `answerCard` in the registry, the observer and the state → **no matches**. Behavioural: `a refresh requests only the read-only probe of exactly the named instance`, `an AnkiDroid check performs no provider write`, `AUDIT-21D-13…`. |
| AUDIT-21D-14 No duplicate services | **PASS** | Executed grep: `AnkiDroidBackend(` has exactly one construction site in production (`di/AppContainer.kt:394`); **zero** references to `PcAnkiAvailabilitySource` / `PcAnkiReadinessReport` / `PcAnkiAvailabilityMapper` / `PcAnkiConnectionSession` outside that file; **zero** `FakeAnkiBackend` references in `app/src/main`. Behavioural: `AUDIT-21D-14…` (2 tests). |
| AUDIT-21D-15 Prior-gate isolation | **PASS** | The audit test enforces that neither the 21D types nor `registry.register(` / `registry.remove(` / `registry.refresh(` appear in `core/study`, `core/anki/edit`, `core/anki/create`, or in `ReviewCommit*`, `ReviewerAction*`, `AnkiTransactionRecoveryRouting.kt`, `AnkiSessionContext.kt` — re-run PASS. **Drift note:** GATE 21G/21J now hold a registry *reference* in the executor and the note-mutation routing policy; that is a consumer using the inventory (sanctioned direction), not the registry acquiring transaction authority — the audit's forbidden tokens remain absent. |
| AUDIT-21D-16 Scope discipline | **PASS** | No resolver, preference, failover or synchronization component was added by 21D; executed grep over the 21D sources for policy vocabulary → none (`AUDIT-21D-05`). The resolver/preference files that exist today were introduced **after** 21D by GATE 21E and are outside the 21D change set. Behavioural: `AUDIT-21D-16…` PASS. |

**Audit result: 16/16 PASS.** No defect found in this run; no remediation required.

---

# PART II — AUTHORITATIVE VERIFICATION RE-EXECUTED (VER-21D-01 … VER-21D-26)

Executed 2026-10-15 with the repository harness at HEAD `25cc924`:

```text
tools/jvm-harness/bin/run.sh "Gate21d"
RESULT classes=6 tests=99 passed=99 failed=0 ignored=0 time=1.6s
```

The 6 classes are `Gate21dRegistryMembershipTest` (20), `Gate21dArchitectureAuditTest` (20),
`Gate21dBackendStateTest` (18), `Gate21dObservationTest` (15), `Gate21dPcAvailabilityTest` (15),
`Gate21dAnkiDroidRegistryTest` (11); `Gate21dFixtures.kt` and `Gate21dAnkiDroidChain.kt` are helpers.
`PASS†` = structure-level (no PC backend exists — unchanged from the original lock).

| Verification | Result | Evidence at this HEAD |
|---|---|---|
| VER-21D-01 Registry initialization | **PASS** | `Gate21dRegistryMembershipTest` · `configured instances appear once and lookup returns exactly those objects`, `the registry detaches the list it was built from`; `AUDIT-21D-04/14` (container: one registry, one `AnkiDroidBackend`) |
| VER-21D-02 Exact backend lookup | **PASS** | same test (`assertSame` ×3) |
| VER-21D-03 Backend missing, no fallback | **PASS** | `an unregistered identity has no state, and no sibling answers for it`, `absent and unavailable are different answers`; `Gate21dObservationTest` · `refreshing a backend that is not there probes no one, not even a ready sibling` |
| VER-21D-04 Same kind, different identities | **PASS** | `two instances of one kind stay separate registry entries`, `kind is never the instance identity` |
| VER-21D-05 Duplicate ID protection | **PASS** | `a second registration under a held identity never replaces the first`, `duplicate identities at construction still fail closed`, `registering the very same instance again is idempotent and changes nothing`, `concurrent claims on one identity admit exactly one instance` |
| VER-21D-06 Stable registration identity | **PASS** | `availability changes never change the identity or the instance`; `Gate21dAnkiDroidRegistryTest` · `the real adapter keeps its stable identity and kind through every state change` |
| VER-21D-07 Backend removal | **PASS** | `removal changes membership only and leaves the others untouched`, `a removed backend's durable owner is blocked not reassigned, and returns with the same identity`, `observation follows registration and a removed backend is no longer collected` |
| VER-21D-08 AnkiDroid ready | **PASS** | `Gate21dAnkiDroidRegistryTest` · `a ready fully wired AnkiDroid is READY for exactly what the application implements`, `a ready AnkiDroid is eligible for a study session through the existing resolver` — **real adapter chain** (`Gate21dAnkiDroidChain`) |
| VER-21D-09 AnkiDroid permission missing | **PASS** | `a missing permission is accurate, and implemented is not ready`; `Gate21dBackendStateTest` · `a missing permission is accurate and is never reported as ready` |
| VER-21D-10 PC transport vs Anki readiness | **PASS†** | `a connected agent without an Anki report is never a ready PC Anki backend`, `ready is produced in exactly one situation and in no other` (all connection fixtures × 4 reports), `AI and speech readiness of the agent never stands in for Anki readiness` |
| VER-21D-11 PC authentication failure | **PASS†** | `an authentication failure is distinguishable, non-ready and leaks nothing`, `no endpoint, credential or rejection text reaches availability`, `authentication is distinct from permission, transport and Anki readiness` |
| VER-21D-12 Protocol compatibility | **PASS†** | `an incompatible protocol is a version problem, and unnegotiated capabilities are not supported`, `every connection state maps to its own availability when Anki reports ready` |
| VER-21D-13 Capability support | **PASS** | golden matrix through the real adapter (15 implemented / 4 refused fully wired; fewer with unwired writers) + `AUDIT-21D-07` |
| VER-21D-14 Unsupported capability | **PASS** | `capabilities the public API might offer are not advertised unless implemented`; `an AnkiDroid without its writers claims only what it can honour` |
| VER-21D-15 Availability vs capability | **PASS** | `implemented support and operational readiness are separate facts`, `a checking backend with a static claim keeps its claim and verifies nothing`, `a missing permission is accurate, and implemented is not ready` (real chain) |
| VER-21D-16 Runtime capability change | **PASS (AnkiDroid claim) / PASS† (negotiation)** | `negotiated evidence is dropped, not kept, when the backend leaves ready`, `a static claim survives a gap and negotiated evidence does not`, `a different agent or protocol version starts a new session, a latency change does not`, `a report for the live session is accepted and becomes availability` |
| VER-21D-17 Observable state updates | **PASS** | `states mirror the backends' own projections`, `a change to one backend leaves every other backend's state untouched`, `the status snapshot is derived from the same observation and reports the stored preference` |
| VER-21D-18 Stale health response | **PASS / PASS†** | `an older delayed probe cannot overwrite a newer one, as observed through the registry` (real `AnkiDroidBackend` publication guard); `a late answer from before the reconnect can neither revive nor overwrite the new session`, `an old session's ready answer cannot revive a new session either` |
| VER-21D-19 Concurrent backend changes | **PASS** | `simultaneous updates to two backends only ever publish coherent snapshots` (2×400 racing updates; every observed snapshot checked) |
| VER-21D-20 Reconnect identity | **PASS** | `loss and reconnect are reported per backend and never swapped between backends`, `a reconnect proves reachability and asserts nothing about the collection` |
| VER-21D-21 Read-only health checks | **PASS** | `a refresh requests only the read-only probe of exactly the named instance`, `checking AnkiDroid through the registry performs no provider write`, `observing a backend never calls it`; independent grep: the registry's only backend call is `refreshAvailability()` |
| VER-21D-22 Fake backend production isolation | **PASS** | `a test double is visible as such and no production mode will ever select it`, `a test double lives in test sources only and is never wired into production`; independent grep: 0 `FakeAnkiBackend` references in `app/src/main` |
| VER-21D-23 Privacy & diagnostics | **PASS** | `diagnostics are tokens only and carry no payload from any availability state`, `AnkiDroid diagnostics carry no provider text`, `no endpoint, credential or rejection text reaches availability`, `a refused identity is reported by token and never registered` |
| VER-21D-24 Previous-gate regression | **PASS** | full suite at this HEAD: **218 classes / 2629 tests / 2629 passed / 0 failed / 48.6 s** — includes all GATE 11–21J tests; no existing test file was edited by 21D |
| VER-21D-25 No routing policy | **PASS** | `an unregistered identity has no state, and no sibling answers for it`, `refreshing a backend that is not there probes no one…`, `AUDIT-21D-05/16` |
| VER-21D-26 App composition lifecycle | **PASS** | `AUDIT-21D-04/14`; `stopping the observation scope freezes the last state without corrupting it`; observers 1 → 0 on removal; independent grep: nothing in production references the PC structure |

**All required fixtures (CHECKPOINT 21D-17) are present and exercised:** AnkiDroid ready (real chain),
AnkiDroid unavailable (not installed / provider / permission / collection / API), PC ready†, PC transport
connected with Anki unavailable†, PC authentication required†, missing permission, unsupported capability,
unknown capability, multiple PC instances, duplicate BackendId, backend removed, backend reconnected,
stale health response.

---

# PART III — INVARIANTS (INV-21D-01 … INV-21D-20)

| ID | Invariant | Re-checked at this HEAD by |
|---|---|---|
| INV-21D-01 | Registry is an inventory, not an AUTO resolver | AUDIT-21D-05/16 (grep clean) |
| INV-21D-02 | `BackendId` stable across availability changes | VER-21D-06; identity is the map key and never rewritten |
| INV-21D-03 | Kind ≠ instance identity | AUDIT-21D-03; VER-21D-04 |
| INV-21D-04 | Lookup never substitutes another backend | `find`/`stateOf` return `null`/`NotRegistered`; VER-21D-03/25 |
| INV-21D-05 | Not scheduler authority | no scheduler/session member; AUDIT-21D-13/15 |
| INV-21D-06 | Not transaction-recovery authority | registry holds no ledger; VER-21D-07; AUDIT-21D-15 |
| INV-21D-07 | Availability ≠ capability support | two dimensions; VER-21D-15 |
| INV-21D-08 | Transport availability ≠ Anki readiness | ladder `Ready` ⇔ authenticated agent ∧ explicit Anki report; VER-21D-10 |
| INV-21D-09 | PC AI/TTS readiness ≠ PC Anki readiness | ladder never reads the agent's AI `capabilities`; VER-21D-10 |
| INV-21D-10 | Only implemented capabilities advertised | derived claim + wiring gates; VER-21D-13/14 |
| INV-21D-11 | Unknown never promoted to supported | defaults UNKNOWN/NOT_READY; `READY ⇒ SUPPORTED` at construction; VER-21D-16 |
| INV-21D-12 | Published state immutable | AUDIT-21D-10 (grep for `var`/mutable collections clean) |
| INV-21D-13 | Older observations cannot overwrite newer | backend publication guard + PC session epoch; VER-21D-18 |
| INV-21D-14 | Reconnect ≠ collection continuity | no continuity vocabulary; VER-21D-20; AUDIT-21D-11 |
| INV-21D-15 | Registration never rewrites historical ownership | no ledger in the registry; VER-21D-07 |
| INV-21D-16 | Health checks never mutate | one read-only call; VER-21D-21; AUDIT-21D-13 |
| INV-21D-17 | No selection preference as registry truth | AUDIT-21D-05 (grep clean) |
| INV-21D-18 | No private AnkiDroid storage/scheduler internals | no new access path; public provider only; AUDIT-21D-12/13 |
| INV-21D-19 | Metadata never exposes credentials | payload-free states, token-only diagnostics; VER-21D-23 |
| INV-21D-20 | Weakens no GATE 11–21C invariant | full suite 2629/2629; no 21D-owned file drifted |

---

# PART IV — BUILD VALIDATION (FRESH ATTEMPT)

| Command (executed 2026-10-15) | Result |
|---|---|
| `./gradlew --offline -q testDebugUnitTest` (with `JAVA_HOME` from the harness JDK) | **BLOCKED — environmental.** The wrapper cannot fetch its distribution (`gradle-8.7-bin.zip` from `services.gradle.org`): `org.gradle.wrapper.Install.forceFetch` → `java.io.EOFException: SSL peer shut down incorrectly`; exit 1. No `~/.gradle` cache exists in this sandbox. |
| `./gradlew tasks` (no `JAVA_HOME`) | **BLOCKED**: `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.` |
| `tools/jvm-harness/bin/bootstrap-sandbox.sh` | **PASS**: `main errors: 0 (files: 328)` · `compose errors: 0 (files: 24, stub files: 46)` · `test errors: 0 (files: 250)` |
| `tools/jvm-harness/bin/run.sh "Gate21d"` | **PASS**: `classes=6 tests=99 passed=99 failed=0 ignored=0 time=1.6s` |
| `tools/jvm-harness/bin/run.sh` (full) | **PASS**: `classes=218 tests=2629 passed=2629 failed=0 ignored=0 time=48.6s` |

`./gradlew clean / lint / assembleDebug / assembleRelease / connectedDebugAndroidTest` are **not
executable** in this environment for the same reason (no Gradle distribution, no Maven `/ dl.google.com`
access, no JDK/Android SDK, no device). They are reported as **BLOCKED**, never as passing — identical
to R-21D-02 and GATE 21C's R-21C-06. CI (`./gradlew testDebugUnitTest`, `lint`, `assembleDebug`,
`assembleRelease`) remains the precondition for any release-style claim.

---

# PART V — GATE REPORT

## 1. Gate result

```text
GATE 21D — BACKEND REGISTRY, AVAILABILITY & CAPABILITIES

STATUS:             RE-VERIFIED / LOCK CONFIRMED (scale of the original lock unchanged)
REPOSITORY HEAD:    25cc924d04420829de5382dc956e42465812b772  (== origin/master)
ORIGINAL LOCK:      a4fc9ded0e6cdaffcb16b641d155fbbc6457e2b9  (2026-10-12)

PREREQUISITES:
21A: LOCKED
21B: LOCKED
21C: LOCKED
```

## 2. Registry architecture (as re-read at this HEAD)

```text
Composition root — di/AppContainer.kt (one registry, one AnkiDroidBackend)
   │  AnkiBackendRegistry(listOf(ankiDroidBackend))            :534   (AnkiDroidBackend(:394))
   ▼
AnkiBackendRegistry — owns MEMBERSHIP only (immutable snapshot + one compareAndSet)
   │   registered · ids · find(id) · descriptors() · states() · stateOf(id)
   │   register() · remove(id) · refresh(id)          ← the only backend call: refreshAvailability()
   ▼
AnkiBackend (AnkiDroidBackend today) — owns its own availability / capabilities / publication order
   │   availability: StateFlow<AnkiAvailability>   capabilities: StateFlow<AnkiCapabilities>
   │   implementedCapabilities: AnkiCapabilities?  (static claim; derived from policy + wiring gates)
   ▼
AnkiBackendStatusCenter.states — membership-aware observation; status = states × preference (display)
   ├──► GATE 21E resolver (consumed since 21E — descriptors/states read at call time)
   ├──► consumers: AnkiStudyEffectExecutor (21G), NoteMutationRoutingPolicy (21J), study starter
   └──► diagnostics / UI — AnkiBackendState.diagnostics() (tokens only)

PC readiness (STRUCTURE ONLY — no producer in production):
ConnectionState (existing agent connection, handed in) ─► PcAnkiAvailabilityMapper ─► AnkiAvailability
                              explicit PcAnkiReadinessReport + connection session epoch ┘
```

Dependency direction unchanged: `ui → viewmodel → application service → AnkiBackend (abstraction) →
AnkiDroidBackend`; `core/anki` never imports `data/` or a network client.

## 3. Backend descriptor contract

`AnkiBackendDescriptor(id, availability, capabilities, kind = id.kind, locality = kind.locality)` — unchanged
from the lock; `init` requires `kind == id.kind` and `locality == kind.locality`. `id` is the stable
configured-instance identity (21C); availability and capabilities are a snapshot valid only for the read
that produced it; `orderingKey = "<kind.ordinal>:<stableId>"` is the resolver's ordering input.
No secret, no scheduler state, no Compose type, and deliberately **no `displayName`** — a label is
presentation, UI-owned, and never identity (identity rule A6). `AnkiBackendState` wraps the descriptor
with the static claim and the derived capability/readiness views.

## 4. Availability model

| State | Backend | Reason | Retryability |
|---|---|---|---|
| `Checking` | both | *(none — not a failure)* | — |
| `NotInstalled` | AnkiDroid | `APP_NOT_INSTALLED` | `AFTER_USER_ACTION` |
| `ProviderUnavailable` | AnkiDroid | `PROVIDER_UNAVAILABLE` | `AFTER_USER_ACTION` |
| `PermissionRequired` | AnkiDroid | `PERMISSION_REQUIRED` | `AFTER_USER_ACTION` |
| `CollectionNotInitialized` | either | `COLLECTION_UNAVAILABLE` | `AFTER_USER_ACTION` |
| `TemporarilyUnavailable` | either | `TEMPORARILY_UNAVAILABLE` | `EXPECTED_TO_RECOVER` |
| `AgentDisconnected` | PC | `TRANSPORT_UNAVAILABLE` | `MAY_RECOVER` |
| `AgentAuthenticationRequired` | PC | `AUTHENTICATION_REQUIRED` | `AFTER_USER_ACTION` |
| `AgentAnkiUnavailable` | PC | `ANKI_UNAVAILABLE` | `AFTER_USER_ACTION` |
| `Unsupported` | AnkiDroid API shape / PC protocol | `VERSION_INCOMPATIBLE` | `AFTER_USER_ACTION` |
| `Fault` | either | `BACKEND_ERROR` | `UNKNOWN` |
| `Ready` | either | *(none)* | — |

`unavailableReason()` is total and has no `else`; a new availability state fails to compile until its
reason is decided. `NotRegistered` is **not** an availability: an absent identity has no state at all.

## 5. Capability matrix (re-derived from source + executed tests)

Vocabulary `AnkiRequiredCapability`; "Impl." = `implemented`, "Ready" = `operational` while `Ready`.

| Capability | AnkiDroid (fully wired) | AnkiDroid (writers unwired) | PC | Fake | Evidence |
|---|---|---|---|---|---|
| Read decks (`DECKS`) | SUPPORTED / READY | SUPPORTED / READY | no code | fixture-defined | golden test, `implementedCapabilitiesFor` |
| Deck counts (`DECK_COUNTS`) | UNSUPPORTED | UNSUPPORTED | — | — | policy keeps it `false` |
| Scheduled review / rendered cards | SUPPORTED / READY | SUPPORTED / READY | — | — | golden test |
| Review = rating loop (`REVIEW`) | SUPPORTED / READY (implemented, not device-verified) | UNSUPPORTED | — | — | `withWriteSupport` |
| Card details | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Card browser (`CARD_BROWSER`) | UNSUPPORTED | UNSUPPORTED | — | — | pin has no public bounded list API |
| Reviewer actions (`BURY`, `SUSPEND_CARDS`) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Flags (`FLAGS`) | UNSUPPORTED | UNSUPPORTED | — | — | pinned contract has no flag column |
| Edit note (fields, tags, card deck) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Create note (`CREATE_NOTE`) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Media read (`MEDIA_READ`) | UNSUPPORTED | UNSUPPORTED | — | — | policy keeps `media` `false` |
| Media store (`STORE_MEDIA`) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Models / templates (listing, schema) | SUPPORTED / READY | UNSUPPORTED | — | — | golden test |
| Custom Study | no capability exists (GATE 20 `BLOCKED`) | — | — | — | not in `AnkiRequiredCapability` |

Totals (fully wired): **15 implemented / 4 refused** (`DECK_COUNTS`, `CARD_BROWSER`, `FLAGS`,
`MEDIA_READ`). Not-ready backends read `SUPPORTED + NOT_READY` (permission missing) or
`SUPPORTED + UNKNOWN` (still checking) — never `READY`. The PC column is empty **because no PC code
exists**; a value there would be fabricated.

## 6. Registry API (implemented, unchanged)

```kotlin
class AnkiBackendRegistry(backends: List<AnkiBackend>) {
    internal val members: StateFlow<AnkiBackendMembership>      // infrastructure boundary (raw instances)

    val registered: List<AnkiBackend>                           // registration order — never a priority
    val ids: Set<AnkiBackendId>
    fun find(id: AnkiBackendId): AnkiBackend?                   // null = not registered; never a sibling
    fun descriptors(): List<AnkiBackendDescriptor>
    fun states(): List<AnkiBackendState>                        // identity-ordered; pull-only
    fun stateOf(id: AnkiBackendId): AnkiBackendStateLookup      // Registered(state) | NotRegistered(id)
    fun register(backend: AnkiBackend): AnkiBackendRegistration // Registered | AlreadyRegistered | DuplicateIdentity | IdentityRefused
    fun remove(id: AnkiBackendId): AnkiBackendRemoval           // Removed | NotRegistered
    suspend fun refresh(id: AnkiBackendId): AnkiBackendRefresh  // Requested(id) | NotRegistered(id) — carries no state
}

class AnkiBackendStatusCenter(...) {
    val states: StateFlow<List<AnkiBackendState>>               // follows membership (flatMapLatest, cold)
    val status: StateFlow<AnkiBackendStatusSnapshot>            // derived from `states`
}

interface AnkiBackend { val implementedCapabilities: AnkiCapabilities? get() = null }
```

**Ownership boundary:** the composition root registers; application code looks up by identity; UI and
diagnostics observe the instance-free `states`. The registry cannot choose a backend, start a session,
commit a rating, recover a note mutation, merge collections or fail over.

## 7. File-by-file report (this run)

```text
FILE:   docs/GATE_21D_REVERIFICATION.md
ACTION: CREATED (this document)
RESPONSIBILITY: re-execution record of AUDIT-21D-01…16 and VER-21D-01…26 at HEAD 25cc924
IMPLEMENTED: prerequisite validation, revision integrity, independent audit, executed evidence, lock confirmation
LOCAL CHECKS: git diff proves no 21D-owned file drifted; harness run 99/99 + 2629/2629

FILE:   docs/GATE_21D_REGISTRY_IMPLEMENTATION.md
ACTION: NO CHANGE (a one-paragraph pointer to this document was added at the top; the lock record
        itself is byte-identical otherwise — see `git diff`)
LOCAL CHECKS: `git diff a4fc9de..HEAD -- <that file>` is empty apart from the pointer

ALL OTHER FILES: NO CHANGE. No production file, test file, build file or other gate document was
                 modified in this run.
```

The original lock's file-by-file report (11 production paths, 8 test paths) is unchanged and remains
accurate; its per-file claims were re-derived in PART I.

## 8. Concurrency & lifecycle report (re-confirmed)

```text
State publication:  one `combine` per backend (availability + capabilities), one `combine` over
                    backends, replaced wholesale by flatMapLatest on membership change; StateFlow is
                    latest-value. The two flows cannot be read atomically → fail-closed NOT_READY.
Readiness refresh:  registry.refresh(id) → exactly one `refreshAvailability()` on exactly that
                    instance; no queue, retry, timer or fan-out; returns no state; cancellation
                    propagates; membership is re-checked after the probe.
Stale rejection:    AnkiDroid — the adapter's publication guard (verified through the registry);
                    PC structure — session epoch: a report from a previous session is refused and a
                    reconnect starts at `Checking`.
Registration:       immutable membership snapshot + compareAndSet; idempotent for the same instance;
                    a held identity is never replaced; unsafe identities refused by token; the
                    construction path fails closed on an unsafe or duplicate identity.
Removal:            membership only — durable records keep their owner (Blocked, never reassigned);
                    the removed backend's observation is cancelled (observers 1 → 0).
Connection lifecycle: the registry creates, starts, stops and closes nothing; it holds references the
                    composition root created. The PC structure observes a handed-in StateFlow and
                    cannot open a transport. Foreground-driven refresh is still NOT wired (R-21D-03).
```

## 9. Architecture audit matrix

16/16 **PASS** — see PART I (re-derived at this HEAD; no defect, no remediation).

## 10. Verification matrix

26/26 — see PART II (all 26 rows re-executed; PC rows remain structure-level `PASS†`).

## 11. Build results

See PART IV: harness **PASS** (99/99 focused, 2629/2629 full); Gradle **BLOCKED — environmental**
(fresh attempt recorded). No Gradle task is claimed to have passed.

## 12. Invariant report

20/20 — see PART III.

## 13. Residual risks (carried forward; unchanged unless noted)

| ID | Risk | Disposition / owner |
|---|---|---|
| R-21D-01 | **No PC Anki backend and no Anki messages in the agent protocol.** All PC results are structure-level; a PC `Ready` is unreachable in production by design. Re-confirmed at this HEAD (0 production references). | Inherits 21B R4/R7; closed only by a PC backend + protocol (not in the 21A–21N series). |
| R-21D-02 | **Gradle tasks not executed** (fresh proof in PART IV). Harness is Kotlin 2.3/K2, not the Gradle toolchain (Kotlin 1.9.24/K1). | CI must run `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease` before any release claim. |
| R-21D-03 | AnkiDroid's registry availability is only as fresh as the last `refreshAvailability()`; the foreground hook drives a different owner (`AnkiDroidHealthRepository`), so Settings can show `Checking` until Library/Study refreshes. | Pre-existing; `registry.refresh(id)` is the sanctioned entry point. Still unwired by design in 21D (lifecycle code is not compile-verifiable here). |
| R-21D-04 | The D-21D-01 fix makes the AnkiDroid study path reachable; rating commits are `IMPLEMENTED`, **not VERIFIED** — GATE 11's real-device mutation verification is outstanding. | Device run against a disposable collection is the release precondition. |
| R-21D-05 | Two `StateFlow`s cannot be read atomically → a transient disagreement reads `NOT_READY`. | Locked by 21B (fail-closed); consumers must not add a best-effort read. |
| R-21D-06 | PC identity allocation rules A1/A2/A6 are unexercised (no PC configuration producer). | R3 narrowed (A3/A4/A5 + duplicates enforced at registration), not closed. |
| R-21D-07 | `host:port`-shaped discriminators are not refused (indistinguishable from the sanctioned `<agent>:<profile>` form). | Allocation authority's duty (A4). |
| R-21D-08 | The 21C predicate `discriminatorIsNormalized` cannot see *leading* whitespace inside a prefixed discriminator; 21D adds a stricter superset check. | Recommend a 21C amendment for 21N review; 21C was not edited. |
| R-21D-09 | Pre-existing unrelated flaky test (`StudyAudioRouteCoordinatorTest.concurrent device preference and boundary churn stays consistent`) — not observed in this run's full suite (2629/2629) but not fixed. | Owner: audio gate. |
| R-21D-10 | Consumers that snapshot `registry.ids` at construction do not follow dynamic registration (none register dynamically today). | Owner: first dynamic consumer (21E/21F/21M). |
| R-21D-11 | AnkiDroid *API shape* and PC *protocol* share `VERSION_INCOMPATIBLE`. | `kind` distinguishes them for presentation. |
| R-21D-12 | UI copy for `AgentAuthenticationRequired` is neutral placeholder (unreachable state today). | Wording owner 21M. |
| R-21D-13 | Collection continuity remains unprovable (no collection key on AnkiDroid; PC unknown); the registry asserts nothing about it. | Inherits 21B R1/R2/R5/R6. |
| R-21D-14 | UI files outside the harness's gated scope are compile-checked only against API-shaped stubs. | CI Gradle build. |
| R-21D-15 | `AnkiBackendStatusCenter.status` for an empty registry reports the stored preference (not reachable in production). | Documented behaviour alignment with `snapshotNow()`. |
| **R-21D-16 (new, documentation-level)** | GATE 21N's STOP means the parent GATE 21 is not closed: 21J awaits contract-deviation approval, 21K is absent, 21L is `BLOCKED`, 21M has no lock artifact, and 21N explicitly reports that no verification was re-executed there. | **Not a 21D defect.** This document removes the "21D evidence is historical" part of that finding for 21D only; 21E–21M remain as 21N recorded them. |

## 14. Downstream contract for GATE 21E (already consumed — verified, not promised)

GATE 21E is implemented and locked (`docs/GATE_21E_RESOLVER_IMPLEMENTATION.md`) and its tests are green
in the full-suite run, so the following is a description of an exercised interface, not a forward promise:

```text
Registered backend identities    registry.ids / find(id) / stateOf(id)
Immutable descriptors            registry.descriptors() → AnkiBackendDescriptor(id, availability, capabilities, kind, locality)
Availability snapshot            AnkiBackendState.availability (+ .unavailableReason with retryability)
Capability snapshot              AnkiBackendState.capability(AnkiRequiredCapability) → (implemented, operational);
                                 READY equals descriptor.supports(req) — positive knowledge only
Exact instance lookup            find/stateOf: null / NotRegistered for an absent identity — NEVER a sibling
Stable ordering / tie-break      descriptor.orderingKey ("kind.ordinal:stableId") + locality; registered/states order is display only
Observation                      AnkiBackendStatusCenter.states; status = states × preference
Read-only refresh                registry.refresh(id) → Requested | NotRegistered (no state carried)
```

What a consumer may rely on: identity stability, absence ≠ unavailability, and that no registry read or
refresh mutates a collection. What it must not assume: that a PC backend exists, that a state carries a
timestamp (it does not), that `refresh` returns a verdict, or that registration order means anything.

## 15. Gate lock decision

```text
GATE 21D:                 LOCK CONFIRMED (re-verified at 25cc924; scope of the original lock unchanged)

BACKEND REGISTRY:         PASS
IDENTITY INTEGRITY:       PASS
AVAILABILITY:             PASS   (AnkiDroid end-to-end through the real adapter chain; PC structure-level †)
CAPABILITY TRUTH:         PASS
CONCURRENCY:              PASS
ARCHITECTURE AUDIT:       PASS   (16/16, re-derived)
VERIFICATION:             PASS   (26/26 re-executed; PC rows structure-level †)
BUILD:                    HARNESS PASS · GRADLE BLOCKED — environmental, not claimed (PART IV, R-21D-02)

NEXT AUTHORIZED SUB-GATE: 21E — Backend Preference & Deterministic AUTO Resolver
                          (already locked; conditional on the CI Gradle run for any release-style claim)
```

---

# PART VI — LOCK BLOCK

```text
GATE 21D — REGISTRY LOCKED (RE-VERIFIED)

BACKEND IDENTITY:                 STABLE
BACKEND REGISTRY:                 IMPLEMENTED    (one registry, extended in place)
BACKEND AVAILABILITY:             OBSERVABLE
CAPABILITIES:                     EVIDENCE-BASED
ANALYTIC/DIAGNOSTIC SNAPSHOTS:    READ-ONLY
PC ANKI BACKEND:                  ABSENT — structure only, no producer, no fabricated Ready
AUTO RESOLVER:                    NOT IMPLEMENTED BY THIS GATE (implemented later by 21E)
BACKEND PREFERENCES:              NOT IMPLEMENTED BY THIS GATE (implemented later by 21E)
MID-SESSION FAILOVER:             NOT IMPLEMENTED
```

**Do not re-implement GATE 21D.** The registry exists, is locked, and is consumed by GATE 21E–21J; a
second registry would violate DECISION-21B-10 and break those gates. Any future change to a 21D-owned
file is a contract change and must be raised as such (21N review), not folded into this Sub-Gate.

---

# APPENDIX A — REPRODUCE

## A.1 Environment (nothing committed)

```bash
# inputs (PyPI + GitHub only):
pip download --no-deps -d /tmp/pp jdk4py kotlin-jupyter-kernel
(cd /tmp/pp && mkdir -p jdk kk && (cd jdk && unzip -q ../jdk4py-*.whl) \
   && (cd kk && unzip -q ../kotlin_jupyter_kernel-*.whl))
git clone --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms /tmp/ap
(cd /tmp/ap && git sparse-checkout set android-34)
git clone --depth 1 --branch 1.10.2 --filter=blob:none --sparse \
  https://github.com/Kotlin/kotlinx.coroutines /tmp/corp
(cd /tmp/corp && git sparse-checkout set kotlinx-coroutines-test)

export HARNESS_WORK=/tmp/h HARNESS_JAVA=/tmp/pp/jdk/jdk4py/java-runtime/bin/java \
       HARNESS_KOTLIN_JARS=/tmp/pp/kk/run_kotlin_kernel/jars
tools/jvm-harness/bin/bootstrap-sandbox.sh
```

## A.2 Commands executed for this re-verification

```bash
git rev-parse HEAD origin/master
git rev-list --count HEAD                     # 246 (complete history)
git merge-base --is-ancestor a4fc9de HEAD     # exit 0 → the 21D landing commit is an ancestor
git diff --name-only a4fc9de..HEAD -- <21D-owned paths>   # only AnkiBackend.kt (+13, GATE 21F)

tools/jvm-harness/bin/run.sh "Gate21d"        # RESULT classes=6 tests=99 passed=99 failed=0 ignored=0 time=1.6s
tools/jvm-harness/bin/run.sh                  # RESULT classes=218 tests=2629 passed=2629 failed=0 ignored=0 time=48.6s

JAVA_HOME=/tmp/pp/jdk/jdk4py/java-runtime PATH=$JAVA_HOME/bin:$PATH ./gradlew --offline -q testDebugUnitTest
# → wrapper Install.forceFetch: java.io.EOFException: SSL peer shut down incorrectly  (exit 1)
```

## A.3 Independent source probes (this run, output summarized in PART I)

```bash
grep -rn "class AnkiBackendRegistry" app/src/main            # exactly 1 declaration (AnkiBackendSelector.kt:42)
grep -rn "AnkiBackendRegistry(" app/src/main                 # AppContainer:534 + 2 pre-21D emptyList()
grep -rn "PcAnkiAvailabilitySource\|PcAnkiReadinessReport\|PcAnkiAvailabilityMapper\|PcAnkiConnectionSession" \
     app/src/main | grep -v data/anki/remote/PcAnkiAvailabilitySource.kt   # (nothing)
grep -rln "FakeAnkiBackend" app/src/main                     # (nothing)
grep -rn "AnkiDroidBackend(" app/src/main                    # declaration + one construction (AppContainer:394)
grep -nE "\bAUTO\b|Preference|preferred|priority|fallback|alternate|switch|firstOrNull|\.first\(\)|minBy|maxBy|random" \
     core/anki/AnkiBackendState.kt core/anki/AnkiBackendRegistration.kt \
     core/anki/AnkiBackendUnavailableReason.kt data/anki/remote/PcAnkiAvailabilitySource.kt        # (nothing)
grep -rn "^\s*var \|mutableListOf\|mutableMapOf\|mutableSetOf" <the four files above>              # (nothing)
grep -rn "AnkiCollectionIdentity\|assurance\|continuity\|collectionKey\|AnkiBackendContext" \
     core/anki/AnkiBackendState.kt core/anki/AnkiBackendRegistration.kt core/anki/AnkiBackendSelector.kt \
     data/anki/remote/PcAnkiAvailabilitySource.kt                                                  # (nothing)
grep -rn "observedAt\|timestamp\|System.currentTimeMillis\|SystemClock" <registry/state/registration>  # (nothing)
grep -n "AnkiCapabilities(" data/anki/ankidroid/AnkiDroidBackend.kt                                # (nothing — derived claim)
```

---

# APPENDIX B — DELTAS VS THE ORIGINAL LOCK RECORD

| Item | Original lock (`a4fc9de`) | This re-verification (`25cc924`) |
|---|---|---|
| Registry, state, taxonomy, availability vocabulary, observer, PC structure, AnkiDroid adapter, 5 UI branch files | as landed | **byte-identical** |
| `Gate21d*` tests (8 files) and the lock document | as landed | **byte-identical** |
| `AnkiBackend.kt` | `implementedCapabilities` + `refreshAvailability` contract | **+13 lines from GATE 21F** (`currentCollectionIdentity`, default `null`) — additive; does not affect any 21D claim; suite green |
| Registry consumers | none | GATE 21G executor and GATE 21J note-mutation routing hold references (consumption only) |
| Verification evidence | historical (executed 2026-10-12 at `e5d9abb`) | **re-executed 2026-10-15 at `25cc924`**: 99/99 focused, 2629/2629 full |
| Build | Gradle `BLOCKED` | Gradle `BLOCKED` (freshly re-attempted, same cause) |
| Outcome | `LOCKED` | `LOCK CONFIRMED`, scope unchanged |

# APPENDIX C — WHAT THIS RUN DELIBERATELY DID NOT DO

- No re-implementation of GATE 21D (its artifacts are locked, landed and consumed by 21E–21J; a second
  registry is prohibited by DECISION-21B-10).
- No production code, test, build file or other gate document changed; the only change set is this
  document plus a one-paragraph pointer in the original lock record.
- No GATE 21E, 21F, 21G, 21H, 21I, 21J, 21K, 21L or 21M work — those remain exactly as GATE 21N recorded
  them (R-21D-16).
- No Gradle task claimed as executed; no device, emulator or real LeakCanary-style runtime measurement
  claimed (the 21N-21 lifecycle/observer measurements remain unexecuted, as 21N states).
- No commit, push or pull request was created during the execution and verification itself; publishing
  this document is a separate, explicitly requested step.
