# GATE 21G — Immutable StudySession Backend Lock (Implementation Report)

**Status:** `PASS (harness-verified)` — recommended `LOCKED`. Gradle verification was not run (JVM harness is the verified path, as in GATE 21F).

| Field | Value |
|---|---|
| Repository | `Drmusab/Study-Agent-Client` |
| Branch | `arena/081da197-study-agent-client` |
| Base | `12d4e4704927c301dca69f2e7b254cf208ea6b17` |
| Implementation commit | `02f55c2` — `Implement GATE 21G immutable StudySession backend lock` |
| Date | 2026-10-12 |
| Execution | Implementation, architecture audit, deterministic tests, JVM-harness checks |
| Scope | GATE 21G only; no GATE 21H work started |

```text
GATE 21G:                  PASS (harness-verified)
IMPLEMENTATION AUTHORIZED:  YES (GATE 21G only)
ARCHITECTURE AUDIT:        PASS (6/6 new 21G audits; reshaped GATE 21 audits green)
GATE 21G TESTS:            PASS (62/62)
HARNESS FULL SUITE:        PASS (2487/2487; 209 classes)
HARNESS COMPILE SCOPES:    PASS (main, GATE 16 UI closure, tests)
GRADLE testDebugUnitTest:  NOT RUN (harness is the verified path)
GATE 21H:                  NOT STARTED
```

## 1. Prerequisite validation and authority

Before implementation, the locked GATE 21A–21F contracts and reports were inspected. DECISION-21-11/36 require one session → one backend, bound before scheduler work and immutable thereafter. The 21E handoff requires migrating session start to the pure `AnkiBackendResolver2` policy, with fresh resolution per start and live readiness/capability validation before binding. The 21C identity matrix governs continuation and restore; 21F's `bindNewContext` establishes the fresh-bind precedent (coherence + record, no matrix verdict on a binding that does not exist yet).

| Prerequisite | Evidence inspected | Result |
|---|---|---|
| 21A–21B | `docs/GATE_21_MULTI_BACKEND_CONTRACT.md`, `docs/GATE_21B_CONTRACT_LOCK.md` | Session-ownership decisions (DECISION-21-11/14/30/31/36) present and locked. |
| 21C | `docs/GATE_21C_IDENTITY_IMPLEMENTATION.md`, `AnkiIdentityContinuation` matrix | OP-S2 continuation semantics and NOMINAL/DEGRADED handling present. |
| 21D | `docs/GATE_21D_REGISTRY_IMPLEMENTATION.md` | Exact registry lookup and descriptor snapshots present. |
| 21E | `docs/GATE_21E_RESOLVER_IMPLEMENTATION.md`, `AnkiBackendResolver2` | Pure pre-binding policy present; study-start migration still outstanding — done in this gate. |
| 21F | `docs/GATE_21F_READ_CONTEXT_ISOLATION.md`, `AnkiReadContextController.bindNewContext` | Fresh-bind precedent present; no production caller uses OP-R1/OP-S1 matrix cells for fresh binds. |

**Prerequisite verdict:** PASS for GATE 21G. No prior-gate report or contract was changed.

## 2. Implementation outcome

### 2.1 The lock, the codec, and the three pure policies

- Added `core/anki/StudySessionBackendLock.kt`: `StudySessionBackendLock` (session id, backend id, optional collection, optional deck, start time, schema version — identity only), `StudySessionLockCodec` (versioned JSON; absent ≠ invalid; future schemas reported, never decoded; unknown fields tolerated), and three total pure policies:
  - `StudySessionAdmissionPolicy.admit` — OP-S1 fresh bind: resolution → exact registration → reachability → REVIEW capability → deck ownership → collection coherence. A live identity naming another backend, or a deck choice contradicting the live collection, refuses with a typed error + stable token. Either side silent admits; the lock records the **live** collection only.
  - `StudySessionContinuationPolicy.check` — OP-S2 bound session: registration → readiness → capability → the 21C matrix over (lock, live). The caller states `boundIsLiveInThisProcess`; there is no default to get wrong.
  - `StudySessionRestorePolicy.restore` — OP-S2 persisted lock: absent/invalid/future bytes invalidate **without backend contact or substitution**; a healthy lock restores with the unresolved-transaction barrier flag; a gone backend or a collection mismatch blocks while the lock is preserved.
- Added `core/anki/StudySessionLockStore.kt`: the `save`/`load`/`clear` interface plus `InMemoryStudySessionLockStore`, which round-trips codec bytes on every operation.
- Added `core/study/StudySessionOwnershipProjection.kt`: a read-only (state, lock) projection for future UI/diagnostics (21M). It reports the lock — never the preference — and carries presence tokens, never key values or content.

### 2.2 The corrected OP-S1 verdict (mid-gate design fix)

The first implementation ran the continuation matrix at bind with the deck label as bound evidence. Review against the matrix (`assuranceFor`: bound-key + live-null = DEGRADED) and against 21F's `bindNewContext` (fresh binds record live identity; no matrix) showed this would refuse every bind where a deck label outlives backend reporting — including the entire existing suite. The corrected verdict, now implemented and tested: a fresh bind has nothing to continue, so OP-S1 is coherence (foreign live identity / deck-vs-live mismatch refuse) plus recording live-only evidence. The deck label stays choice metadata in `deckRef`. The matrix governs exactly what it was locked for: OP-S2 continuation and restore, where a real bound side exists.

### 2.3 Executor, starter, stores, and wiring

- `AnkiStudyEffectExecutor.begin` now resolves through `AnkiBackendResolver2` (fresh per start), admits through the 21G policy **before** `beginReview`, publishes the tracker entry only on acceptance, and — when a store is wired — persists the lock in the same acceptance; an unpersistable start is refused (`session_lock_persistence_failure`) and the backend handle released. `EndReview` clears the store for that session and releases the tracker. The legacy selector adapter no longer appears anywhere in study runtime.
- Bound-work guards (`sessionLockConsistent`, tracker-backed): a stale `Next`/`Hydrate` for another backend is refused as `InvalidRequest(session_backend_mismatch)` before any backend call; a foreign `CommitRating` returns `Failed(session_backend_mismatch, dispatched=false)` before the ledger; a foreign `PerformReviewerAction` returns `NotRecorded(session_backend_mismatch)` before anything durable exists. With no tracker entry the historical behavior is kept.
- `AnkiLocalStudyStarter` migrated to Resolver2 (freshness + selected-deck checks preserved); its `selector` dependency is deleted. `AnkiStudyRequest.preference` stays `AnkiBackendMode`, converted at the boundary.
- Durable store: `study_session_backend_lock` key + `StudySessionLockDataStore` adapter over `PreferencesDataStore`, wired in `AppContainer` as `studySessionLockStore`. `clear` removes only the named session's lock; a foreign id is a success-noop (only storage failure returns `false`). No auto-restore wiring — restore API + store + tests only, per scope.
- 21G adds no resolver, registry, scheduler, ledger, offline queue, or failover path. GATE 11 rating and GATE 13 reviewer-action transaction authority are untouched: the guards refuse **before** the coordinators; they never reorder, replay, or reinterpret a transaction.

### 2.4 Refusal vocabulary (stable tokens)

`invalid_session_identity`, `backend_not_registered`, `backend_unavailable`, `required_capability_unavailable`, `foreign_deck`, `foreign_collection_identity`, `collection_mismatch`, `collection_identity_degraded`, `lock_integrity_failure`, `no_persisted_lock`, `unsupported_lock_schema`, `session_backend_mismatch`, `session_lock_persistence_failure` — plus the 21E failure tokens passed through from a failed resolution.

## 3. Architecture audit

Six new `Gate21gArchitectureAuditTest` checks pass: no second resolver/registry/scheduler/ledger/queue in 21G files; the lock body carries the six identity fields and no capability/policy/content spelling; admission precedes `beginReview` and refusal fails the start; continuation/restore mention no resolver/registry/preference/sibling/failover spelling; the store exposes `save`/`load`/`clear` only; the projection is data-only with no `data/` dependency and no content/ledger types.

Three locked GATE 21 audit tests were reshaped to the migrated shape (precedent: 21F's reshaping of the 15/16 audits): the single study-runtime resolution site is now `AnkiBackendResolver2.resolve(` inside `begin()`; scheduler/mutation routing still resolves through locked/persisted identity with the resolver confined to `begin()`; the pre-binding caller list gains the two session-start sites. Intent is unchanged and, for the resolver-confinement rule, tightened from "absent" to "exactly one site, inside `begin()`."

## 4. Verification actually run

- New coverage (62 tests, 4 classes): `Gate21gLockAdmissionTest` (codec/model/admission/continuation/restore), `Gate21gSessionContinuityTest` (executor-driven: ownership, staleness, unavailability, teardown, succession), `Gate21gStoreAndProjectionTest` (both stores + projection), `Gate21gArchitectureAuditTest`.
- VER-21G-01…34 map to the suite as follows: 01–06 codec/model law; 07–12 admission cells; 13–18 continuation cells + no-routing audit; 19–22 restore cells + no-AUTO audit; 23–29 executor ownership/staleness/teardown + single-site audit; 30–34 stores + projection + no-second-truth audits.
- Full harness suite: **2487/2487 across 209 classes**, including all pre-existing GATE 11/12/13/15/16/21A–21F contracts. Compile scopes pass: main (325 files), GATE 16 UI closure, tests (241 files).
- Gradle `testDebugUnitTest` was not run: the JVM harness is the verified path for this repository state (same posture as the 21F report).

## 5. Residual risks and non-claims

- The restore policy is API + tests only; no auto-restore path is wired (21H owns restore wiring and the OP-P transaction barrier). Process-death restore end-to-end is therefore unexercised.
- The store holds one lock slot: a second `begin` in the same process overwrites the first session's persisted owner. The reducer admits one active session, so this matches the app's single-session reality, but overlapping sessions (as constructed in tests) leave the earlier session store-orphaned while its tracker entry survives.
- Collection behavior against a *reporting* production backend (PC agent) is fake-covered only; the real AnkiDroid backend reports no collection identity, and production AnkiDroid deck refs are unkeyed, so the AnkiDroid path binds nominal in production as in tests.
- Gradle verification remains outstanding; nothing in this gate changes the build.

## 6. Gate lock decision

GATE 21G implementation is complete at the specified scope: one session binds one exact backend + collection context before scheduler work, immutable across preference/AUTO/availability/process-death/stale-callback hazards, with GATE 11/13 authority preserved and no new routing truth introduced. **Recommendation: LOCK GATE 21G.** GATE 21H is not started.
