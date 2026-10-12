# GATE 21F — Read-Context Isolation (Implementation Report)

**Status:** `PASS / LOCKED` at the harness-verified implementation scope; Gradle verification is **BLOCKED** by the wrapper download failure described below.

| Field | Value |
|---|---|
| Repository | `Drmusab/Study-Agent-Client` |
| Branch | `arena/2a2aa7fe-study-agent-client` |
| Base | `0320c2d47923b9cd622274bce87ac5cadef6e70c` |
| Implementation commit | `c133fb0` — `Implement GATE 21F read context isolation` |
| Date | 2026-10-12 |
| Execution | Implementation, architecture audit, deterministic tests, JVM-harness checks |
| Scope | GATE 21F only; no GATE 21G work started |

```text
GATE 21F:                 PASS (harness-verified) / LOCKED
IMPLEMENTATION AUTHORIZED: YES (GATE 21F only)
ARCHITECTURE AUDIT:        PASS (11/11)
GATE 21F TESTS:            PASS (27/27)
RELATED GATE 15/16 AUDITS: PASS (27/27)
TARGETED RUN:              PASS (54/54)
HARNESS FULL SUITE:        PASS (2425/2425; 205 classes)
HARNESS COMPILE SCOPES:    PASS (main, GATE 16 UI closure, tests)
GRADLE testDebugUnitTest:  BLOCKED (Gradle distribution TLS handshake failure)
GATE 21G:                  NOT STARTED
```

## 1. Prerequisite validation and authority

Before implementation, the checked-in GATE 21A–21E contracts and implementation reports were inspected. DECISION-21B-18 requires one backend per read context and a new context when switching; DECISION-21B-19 requires correlation by backend, collection, query/context generation, and request generation. The 21E handoff requires fresh resolution for new unbound work, live readiness/capability validation before binding, and preference re-sampling during a resolution race. Existing exact-resource routes must not consult the preference or fall back to a sibling.

| Prerequisite | Evidence inspected | Result |
|---|---|---|
| 21A | `docs/GATE_21A_DISCOVERY.md`, `docs/GATE_21A_EVIDENCE_LEDGER.md` | Locked discovery and evidence present. |
| 21B | `docs/GATE_21B_CONTRACT_LOCK.md`, including DECISION-21B-18/19 and the 21F handoff | Locked read-isolation and response-correlation contracts present. |
| 21C | `docs/GATE_21C_IDENTITY_IMPLEMENTATION.md` | Identity/resource qualification implementation report records LOCKED. |
| 21D | `docs/GATE_21D_REGISTRY_IMPLEMENTATION.md` | Registry/availability implementation report records LOCKED. |
| 21E | `docs/GATE_21E_RESOLVER_IMPLEMENTATION.md` and its binding preconditions | The report records `PASS (harness-verified)` and authorizes 21F, but does **not** explicitly say `LOCKED`. The user explicitly confirmed 21E is locked. This gate accepted that confirmation and preserves the report wording discrepancy rather than rewriting the prior artifact. |

**Prerequisite verdict:** PASS for GATE 21F, based on the locked contracts and the user-confirmed 21E lock. `AUDIT-21F-01` retains the 21E documentation distinction. No prior-gate report was changed.

## 2. Implementation outcome

### 2.1 One context identity; exact binding boundary

- Added `core/anki/AnkiReadContext.kt`: backend and optional collection identity remain independent of an opaque per-context generation. `AnkiReadRequestIdentity` separately carries context, query generation, request generation, and an opaque cursor.
- Added `data/anki/context/AnkiReadContextController.kt` and lease/binding/ViewModel support. `bindNewContext` uses the existing GATE 21E `BackendPreferenceRepository.preference` and `AnkiBackendResolver2` only for a genuinely new unbound read. It refreshes the candidate, checks live readiness/capabilities/registration/collection identity, re-samples preference, and refuses stale attempts.
- Exact route references use `bindReferenceContext`; navigation continuation uses `continueContext`. Those paths look up the exact `AnkiBackendId`, validate the resource tuple, retain the same generation when its lease is live, and never resolve AUTO or substitute another backend. Restored routes bind the same exact identity with a fresh process-local generation.
- `AnkiReadContextAccess` validates before and after every guarded backend operation, validates returned resource identities, preserves coroutine cancellation, and converts late success/error results from invalidated contexts to context failures. A confirmed context-integrity failure revokes the shared lease.
- Added `AnkiBackend.currentCollectionIdentity` as an observation hook with a default of unknown. No identity is fabricated from deck names, paths, or numeric IDs. Current AnkiDroid resource mappings do not provide an authoritative collection key, so this report makes no claim that AnkiDroid collection continuity is verified.

### 2.2 Existing read surfaces

| Surface | Context and correlation change |
|---|---|
| Library | A new Library entry resolves a new context requiring `DECKS`. `AnkiLibraryRepository` is created for that binding; each refresh carries the context and request generation. `LibrarySnapshot` records its exact context. Cache publication requires matching context/generation and a valid lease; context-safety failures clear that context's cache rather than showing it as fresh. |
| Deck Details | Route carries a backend-qualified `AnkiDeckRef` and optional context token. Entry/continuation binds the exact resource; its repository and ViewModel require the matching context and resolve the deck by the full reference, not a raw ID. Existing study-start ownership remains `AnkiLocalStudyStarter`; no StudySession, scheduler, or mutation path was changed. |
| Card Browser | New all-cards routes and deck continuations bind through the same controller. Browser request tokens now include the binding/context, query and request generations, and the cursor. A context change or integrity error invalidates rows/cursor; a late page cannot publish. Navigation to Card Details carries the exact context. |
| Card Details | Route binds/continues from the exact backend-qualified card reference. Details requests are guarded by the binding and correlate context, card, and request generation. Returned card, note, deck, and original-deck identities are checked before publication. The details ViewModel no longer supports in-place backend switching. |
| Add Note model/deck seed reads | The existing creation destination remains explicitly the exact AnkiDroid backend. `getNoteModels()` and the deck-name seed read now pass through the read-context guard and validate returned references. The existing `NoteCreationCoordinator` and media probe remain the only creation owners; their transaction behavior and destination were not changed. |

`Screen.kt` encodes backend-qualified card/deck references and the independent context tuple (backend, collection, generation). Invalid tokens fail at the route boundary. Adjacent routes preserve context identity; restoration does not reinterpret a raw resource ID against the current preference.

The Add Note route already exposes existing note-creation and note-mutation dependencies through `AppContainer`'s interface. Those existing lazy instances were added to the interface contract and marked `override`; this is compile-time DI exposure only and does not create or change a writer.

### 2.3 Honest refusals / unimplemented surfaces

- The production registry still has no PC Anki backend. Tests use deterministic fake backends to exercise cross-backend isolation; they do not demonstrate a production PC backend.
- GATE 15 is still open and the current real AnkiDroid capability does not support `CARD_BROWSER`. The browser route requires that capability and presents an honest refusal; this gate does not enable or implement card browsing.
- The only implemented UI model-schema read found is Add Note's model listing. No UI surface calls deep note-model schema or template reads, so no Model/Template screens or new deep-read behavior were built. Existing reads internal to the note-creation coordinator remain within that coordinator's exact existing writer workflow and were not changed.

## 3. Architecture audit

`Gate21fArchitectureAuditTest`: **11/11 PASS**.

| Audit | Result | Evidence enforced |
|---|---|---|
| AUDIT-21F-01 — prerequisite/authorization | PASS | 21A–21D locked artifacts, 21E PASS + 21F authorization, and the explicit 21E lock wording discrepancy. |
| AUDIT-21F-02 — identity separation | PASS | Backend, collection, context generation, query generation, request generation, and cursor remain separate. |
| AUDIT-21F-03 — binding policy | PASS | Only new contexts resolve; route/reference/continued contexts bind the exact backend and resource identity. |
| AUDIT-21F-04 — lease/read guards | PASS | Registration-backed leases and pre/post operation validation; result validation and revocation. |
| AUDIT-21F-05 — route tokens | PASS | Card/deck references and context generation are serialized independently. |
| AUDIT-21F-06 — navigation ownership | PASS | Route contexts are wired; existing Study and Add Note owners remain in place. |
| AUDIT-21F-07 — required capabilities | PASS | Library, deck, browser, details, and Add Note seed reads declare their operation capability. |
| AUDIT-21F-08 — Library cache | PASS | Atomic publication only for the exact read context and request; context failure clears cache. |
| AUDIT-21F-09 — pagination/details | PASS | Browser cursor and Card Details request correlation includes immutable context identity. |
| AUDIT-21F-10 — design-only surfaces | PASS | No deep Model/Template UI surface was added. |
| AUDIT-21F-11 — ownership boundaries | PASS | No second resolver/registry or mutation/session owner was introduced. |

The existing GATE 15/16 static audits were updated to assert the new guarded-read and navigation contracts rather than old constructor text. Those audits pass without weakening their read-only, identity, rendering, or mutation-prohibition checks.

## 4. Verification actually run

| Command/check | Result |
|---|---|
| `git diff --check` | PASS — no whitespace errors before commit. |
| `tools/jvm-harness/bin/build.sh main` with `HARNESS_EXTRA_MAIN_SRC=.../di/AppContainer.kt` | PASS — 0 errors across 322 selected files. The extra source explicitly includes `AppContainer.kt`, which the harness otherwise excludes. |
| `tools/jvm-harness/bin/build.sh compose` (default GATE 16 UI closure) | PASS — 0 errors across 24 files with the harness's 46 framework stubs. This is the documented closure, not a full Android Compose build. |
| `tools/jvm-harness/bin/build.sh test` | PASS — 0 errors across 237 selected test files. |
| `tools/jvm-harness/bin/run.sh 'Gate(15|16|21f)'` | PASS — 54/54 tests across 5 classes; GATE 21F's 3 classes contribute 27/27. |
| `tools/jvm-harness/bin/run.sh` | PASS — 2,425/2,425 tests across 205 classes. The repository harness excludes `FakeAgentConnectionTest`, `WebSocketIntegrationTest`, `RatingControlsPolicyTest`, and `StudyPhaseMappingTest`; it is not a substitute for Gradle. |
| `GATE16_COMPOSE_ALL=1 ... tools/jvm-harness/bin/build.sh compose` | **Diagnostic only; 147 errors.** The repository documents this broad sweep as non-gating. Failures are in incomplete framework stubs (Compose animation/gesture signatures; Navigation's nullable entry and missing SavedStateHandle APIs; Material3 `role`). The default documented UI-closure compile above passed. This diagnostic is not reported as an app or Gradle compile pass. |
| `./gradlew testDebugUnitTest` | **BLOCKED.** First invocation lacked `JAVA_HOME`; after configuring the available Java 17 runtime, the wrapper attempted to fetch `gradle-8.7-bin.zip` and failed with `SSLHandshakeException` / `EOFException` from `services.gradle.org`. The Gradle task did not run. |
| `./gradlew lint`, `assembleDebug`, `assembleRelease`, `connectedDebugAndroidTest` | Not run. No result is claimed. |

## 5. Residual risks and non-claims

1. Gradle/Kotlin 1.9.24 compilation, lint, APK assembly, and device/instrumented tests remain unverified. CI must run them; the wrapper download failure is environmental, not a passing Gradle result.
2. The current AnkiDroid provider path does not expose an authoritative collection discriminator. The context preserves unknown as unknown and rejects provable mismatches; it cannot prove continuity that the provider does not expose.
3. No production PC Anki backend exists. Cross-backend behavior is verified only with deterministic fakes.
4. Card Browser remains honestly unsupported on the real backend until its own capability/product gate is completed.
5. No deep Model/Template UI was implemented. The existing note-creation workflow remains the owner of its internal model validation reads.
6. The 21E report itself still says PASS and authorizes 21F without an explicit LOCKED line; the user-confirmed lock is recorded here, not retroactively inserted into that report.

## 6. Gate lock decision

```text
GATE 21F:                 PASS (harness-verified) / LOCKED
MANDATORY 21F AUDITS:     PASS (11/11)
GATE 21F TESTS:           PASS (27/27)
RELATED AUDITS:           PASS (GATE 15/16, 27/27)
FULL JVM HARNESS:         PASS (2425/2425)
SELECTED BUILD CHECKS:    PASS (main + default UI closure + tests)
GRADLE:                   BLOCKED — no Gradle result claimed
RELEASE / DEVICE CLAIM:   NOT MADE
GATE 21G:                 NOT STARTED
```

This lock records the 21F contract and implementation evidence available in this environment. It does not waive the outstanding Gradle/CI and device checks and does not claim that unsupported or design-only features exist.
