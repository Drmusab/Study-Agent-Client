# GATE 21G — Immutable StudySession Backend Lock (Final Verification Report)

**Status:** `PASS` — recommended `LOCKED` at the harness-verified scope. Gradle verification is **BLOCKED** (environmental, see §11).
**Sub-Gate:** 21G of 21A–21N (parent GATE 21 — Multi-Backend Anki Selection / Hybrid Mode)
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/700fe912-study-agent-client`
**Base / repository HEAD:** `503822a83f2fb492bdf8171073f98be25e5538ad` (PR #70 — GATE 21G implementation already merged)
**Date:** 2026-10-12
**Execution type:** independent re-verification of a landed implementation → verification-gap closure → architecture audit → gate lock
**Next sub-gate:** GATE 21H — Review Commit Routing & Recovery (not started)

```text
GATE 21G:                    PASS (harness-verified)
PREREQUISITES 21A–21F:       LOCKED / PASS
IMPLEMENTATION CHANGES:      none required (0 production defects found)
VERIFICATION CHANGES:        1 new test class (17 cases) + 1 test-harness parameter
GATE 21G TESTS:              PASS (17/17 new; 62/62 pre-existing)
HARNESS FULL SUITE:          PASS (2504/2504; 210 classes)
GRADLE testDebugUnitTest:    BLOCKED — environmental (wrapper distribution TLS failure, no Android SDK)
GATE 21H:                    NOT STARTED
```

---

## 0. What this execution changed, and why

The GATE 21G implementation was already present in the repository at the branch base: `core/anki/StudySessionBackendLock.kt`, `core/anki/StudySessionLockStore.kt`, `core/data/.../StudySessionLockDataStore.kt`, `core/study/StudySessionOwnershipProjection.kt`, the executor/starter integration, and the four `Gate21g*Test` classes, all merged through PR #70 and documented in `docs/GATE_21G_SESSION_LOCK_IMPLEMENTATION.md`.

Executing GATE 21G therefore meant **independently re-verifying it against this prompt's authoritative verification plan (PART III)**, not re-implementing it. That re-verification produced:

| Change | Path | Nature |
|---|---|---|
| New verification class (17 cases) | `app/src/test/java/com/studyagent/client/study/Gate21gVerificationTest.kt` | Test-only; closes unmapped `VER-21G-*` cases (06, 07/08, 09/10/11, 12, 13, 14, 15, 16, 17, 18/19/20, 21, 22, 23, 24–27, 28, 32, 33) |
| Optional `sessionLockStore` parameter | `app/src/test/java/com/studyagent/client/study/AnkiCommitHarness.kt` | Test-harness only; defaults to `null` (existing behaviour byte-identical) |

**No production file was modified.** The implementation was found to satisfy every case the new verification could execute; the two defects found are *verification-quality* defects (§12.3), one of them in a pre-existing GATE 21 test.

---

## 1. Gate result

```text
GATE 21G — IMMUTABLE STUDYSESSION BACKEND LOCK

STATUS:                     PASS (harness-verified; Gradle blocked environmentally)
REPOSITORY HEAD:            503822a83f2fb492bdf8171073f98be25e5538ad

PREREQUISITES:
21A: LOCKED   (docs/GATE_21A_DISCOVERY.md — discovery complete, no production change)
21B: LOCKED   (docs/GATE_21B_CONTRACT_LOCK.md — contract authority: DECISION-21B-11/12/14/36)
21C: LOCKED   (docs/GATE_21C_IDENTITY_IMPLEMENTATION.md — identity/assurance/continuation matrix)
21D: LOCKED   (docs/GATE_21D_REGISTRY_IMPLEMENTATION.md — registry/availability/capabilities)
21E: LOCKED   (docs/GATE_21E_RESOLVER_IMPLEMENTATION.md — preference + deterministic AUTO resolver)
21F: LOCKED   (docs/GATE_21F_READ_CONTEXT_ISOLATION.md — replaceable read contexts)

IMPLEMENTED COMPONENTS CONSUMED BY 21G:
GATE 10 StudySession:       IMPLEMENTED   (StudySessionMachine/StudyReducer, GATE 10 report)
GATE 11 Review Commit:      IMPLEMENTED   (ReviewCommitLedger/Coordinator/Recovery, GATE 11/11B–11E)
GATE 12 Answer Reveal:      IMPLEMENTED   (AnswerReviewModel + Gate12 tests)
GATE 13 Reviewer Actions:   IMPLEMENTED   (ReviewerActionCoordinator/Ledger + Gate13 tests)
GATE 20 Custom Study:       DESIGN_ONLY   (contract doc: implementation authorized = NO)
```

**Prerequisite verdict:** all six prerequisite sub-gates carry locked contracts and implementation reports; the runtime components 21G binds to are implemented and covered by their own passing suites (§11). GATE 20 is design-only, so CHECKPOINT 21G-20 is discharged as documentation + scope statement, never as runtime verification (§9, §10).

---

## 2. Session ownership architecture (as implemented)

```text
Settings / BackendPreferenceRepository        → preferred backend (future unbound work only)
   |
GATE 21E AnkiBackendResolver2                 → pre-binding selection, once per start attempt
   |
GATE 21D AnkiBackendRegistry                  → exact backend lookup by configured AnkiBackendId
   |
GATE 21G StudySessionAdmissionPolicy          → one admitted lock (admission → exact registration →
   |                                             readiness → capability → deck ownership → OP-S1
   |                                             collection coherence) BEFORE any scheduler call
   |
StudySessionBackendLock (immutable)           → { studySessionId, backendId, collection, deckRef,
   |                                             startedAt, schemaVersion }
   |
AnkiStudyBackendLockTracker (process)         → observable lock map, sessionId → AnkiSessionContext
StudySessionLockStore (durable)               → DataStore JSON under `study_session_backend_lock`
   |
AnkiStudyEffectExecutor.sessionLockConsistent → every read/rating/action refuses a foreign backend
   |
AnkiBackend (exact)                           → scheduler + collection operations
   |
GATE 11 ReviewCommitCoordinator               → rating transaction truth (unchanged)
GATE 13 ReviewerActionCoordinator             → reviewer-action transaction truth (unchanged)
```

Distinctions preserved exactly as PART 2 requires: preference ≠ resolution ≠ locked session backend ≠ transaction backend. No new registry, resolver, session machine, scheduler, or ledger exists (§9 `AUDIT-21G-20`, `Gate21gArchitectureAuditTest`).

---

## 3. StudySession start sequence (actual implementation sequence)

```text
1. StartStudyRequested                     StudyReducer.reduceAnkiInteraction (StudyReducer.kt:143)
                                           guard: `if (!state.isIdle && !state.isFinished) reject("already-active")`
2. StudySessionAdmissionPolicy.admit       StudySessionBackendLock.kt (pure; no I/O)
   2a. session identity valid              (blank id / negative timestamp → invalid_session_identity)
   2b. resolution Resolved                 (Failed → the resolver's own typed error passes through)
   2c. exact registration                  (`registry.find(resolvedId)` must be non-null and same id)
   2d. operational readiness               (`descriptor.isReachable`)
   2e. required capabilities               (STUDY_SESSION_REQUIREMENTS: review + scheduled review + …)
   2f. deck ownership                      (deck.backendId == resolved id)
   2g. OP-S1 collection coherence          (foreign live identity / deck-vs-live key mismatch refuse)
3. Recovery scans                          ledger.recoveryBlocker / actionLedger.recoveryBlocker
                                           (before ANY scheduler traffic)
4. Deck validation                          backend.getDecks() must contain the chosen deck
5. Live context build                       admitted.toSessionContext(backend.capabilities.value)
6. Binding                                  backend.beginReview(BeginReviewRequest(context))
7. Durable ownership                        tracker.locked(context)
                                            + store.save(StudySessionBackendLock.from(context))
                                            inside NonCancellable
8. Scheduler work                           only now: Next → Hydrate → … (effects driven by the
                                            reducer's interaction state)
```

**The precise moment ownership becomes immutable:** step 7's `sessionLocks.locked(context)` executed immediately after `beginReview` returns `AnkiResult.Success`. There is no code path after that, in any state, that writes a lock entry for the session — the tracker's `locked()` is idempotent per session and `released()` is the only other writer, reachable only from `AnkiStudyEffect.EndReview`. Before step 7, a refusal publishes nothing (§3 of the prompt's guarantee).

If step 7b (persistence) fails, the start is refused with `AnkiError.Unknown("session_lock_persistence_failure")`, the tracker entry is released and the backend handle is ended best-effort — an apparently-active session without a durable owner cannot exist.

---

## 4. Session backend context (actual types)

`core/anki/StudySessionBackendLock.kt`

| Type | Responsibility |
|---|---|
| `StudySessionBackendLock` | The durable owner: `studySessionId`, `backendId`, `collection: AnkiCollectionIdentity?`, `deckRef: AnkiDeckRef?`, `startedAtEpochMs`, `schemaVersion`. Constructor refuses a foreign collection/deck and disagrees between them. `toSessionContext(capabilities)` rebuilds a live context with **live** capabilities (never a frozen snapshot). `from(context)` captures one. |
| `DecodedSessionLock` | `Valid` / `Invalid(reason)` / `UnsupportedVersion(found)`; absent storage is `null`, never a lock. |
| `StudySessionLockCodec` | Versioned JSON (`ignoreUnknownKeys`, explicit nulls). Absent ≠ invalid; malformed or constructor-refused bytes → `Invalid("lock_integrity_failure")`; `schemaVersion > CURRENT` → `UnsupportedVersion` (never downgraded). |
| `StudySessionAdmission` / `StudySessionAdmissionPolicy` | The OP-S1 admission verdict and its total, I/O-free policy (§3 steps 2a–2g). Refusal tokens: `invalid_session_identity`, `backend_not_registered`, `backend_unavailable`, `required_capability_unavailable`, `foreign_deck`, `foreign_collection_identity`, `collection_mismatch`. |
| `StudySessionContinuation` / `StudySessionContinuationPolicy` | The OP-S2 verdict for a bound session: registration → readiness → capability → the GATE 21C identity matrix over (lock, live). The caller must state `boundIsLiveInThisProcess`; there is no default to get wrong. Block tokens include `collection_mismatch`, `collection_identity_degraded`, `backend_not_registered`, `no_in_process_binding`. |
| `StudySessionRestore` / `StudySessionRestorePolicy` | OP-S2 restoration of persisted bytes: absent/invalid/future bytes invalidate **without any backend contact or substitution**; a healthy lock restores with its `hasUnresolvedTransactions` barrier flag; a missing backend or a mismatching collection blocks while preserving the lock. |

`core/anki/StudySessionLockStore.kt` — `save` / `load` / `clear(studySessionId)`; `clear` removes only the named session's lock (a foreign id is a success no-op). `InMemoryStudySessionLockStore` round-trips codec bytes.

`data/preferences/StudySessionLockDataStore.kt` + `PreferencesDataStore` — the production store: one JSON string under `study_session_backend_lock`, writer failures reported honestly (`false`).

`core/study/StudySessionOwnershipProjection.kt` — read-only `projectSessionOwnership(state, lock)` for GATE 21M: reports the locked backend (never the preference), a collection state that is *lock contents* (`collection_unknown` / `collection_key_present`, never a continuity claim), lifecycle token and blocked reason; no key values, no content, no ledger duplication.

---

## 5. Scheduler routing matrix

| Operation | Backend ownership source | Guard site | Status |
|---|---|---|---|
| Next card | Session lock → `effect.session.context.backendId` + `registry.find(id)` | `sessionLockConsistent` before the call (`session_backend_mismatch` on refusal) | IMPLEMENTED, verified (VER-21G-09/10, 13, 17, 24) |
| Review info | Same authoritative context (rating options travel with the turn: `AnkiScheduledCard.ratingOptions`) | Turn identity is session-qualified; no independent read path | IMPLEMENTED, verified (VER-21G-11) |
| Card hydration | Session lock → `effect.turn.backendId` | `sessionLockConsistent` before the call | IMPLEMENTED, verified (VER-21G-10, 24) |
| Rating | `CommitRatingRequest.commitId.backendId` (GATE 11 identity) + session lock agreement | `sessionLockConsistent(commitId.studySessionId, commitId.backendId)` **before the ledger is touched** | IMPLEMENTED, verified (VER-21G-12, 13, 14, 15) |
| Reviewer action | `cardRef.backendId` + session lock agreement | `sessionLockConsistent` → `NotRecorded(session_backend_mismatch)` before anything durable | IMPLEMENTED, verified (VER-21G-16) |
| Custom Study | Not implemented (GATE 20 design-only) | Contract documented: a filtered session must carry `BackendFilteredSessionId` beside the locked `StudySessionId`; rebuild/end use the original backend | BLOCKED / DEFERRED (no runtime surface) |

No operation consults the preference, the AUTO resolver, or a sibling backend once a session is bound; the resolver is confined to the two pre-binding start sites.

---

## 6. Persistence & recovery

```text
Persisted identity:      StudySessionBackendLock — studySessionId, backendId, collection (optional),
                         deckRef (optional), startedAtEpochMs, schemaVersion. Never capabilities
                         (an observation), never preference/AUTO state, never credentials, never a
                         live backend object, never turn/commit state.
Serialization format:    JSON via StudySessionLockCodec, schemaVersion = 1; unknown fields tolerated
                         (additive evolution), unknown schemas refused, absent bytes ≠ invalid bytes.
Session restore:         StudySessionRestorePolicy.restore(decoded, lookup, hasUnresolved, live?):
                         Valid + registered + reachable + capable + OP-S2 verdict → Restored(lock,
                         assurance, barrier flag). Verified end-to-end over an executor-created
                         lock after a simulated process death (VER-21G-21).
Backend unavailable:     Blocked(backend_unavailable) — lock preserved, never rewritten.
Collection mismatch:     Blocked(collection_mismatch) — fail closed, distinct from a transient error.
Unknown identity:        OP-S2 degraded row → Blocked(collection_identity_degraded); a keyless lock
                         restored after process death is nominal-unverified, never described as
                         continuity.
Legacy session:          Absent bytes → Invalid(no_persisted_lock); unknown schema →
                         Invalid(unsupported_lock_schema). No AUTO resolution, no sibling, no guess
                         (VER-21G-23).
Pending mutation:        Never inferred, never deleted, never replayed by 21G. The restore verdict
                         merely reports hasUnresolvedTransactions so the caller holds the next-card
                         barrier; transaction truth stays in GATE 11/13 ledgers (VER-21G-28).
```

**Production restore wiring does not exist yet** (no caller of `StudySessionLockStore.load()` /
`StudySessionRestorePolicy` outside tests). That is 21H's routing/recovery work; 21G's obligation —
that restoration *cannot* change ownership — is satisfied by construction (the policy has no resolver
and no writer) and verified by tests. Recorded as a residual risk, not a defect.

---

## 7. Concurrency & stale events

```text
Session generation:      StudyReducer epoch. Every Anki result is epoch-guarded at the state-update
                         boundary: `stale-anki-begin`, `stale-anki-scheduler`, `stale-anki-hydration`,
                         `stale-anki-evaluation`, `stale-anki-commit-result`, `stale-anki-reveal`, …
ReviewTurn identity:     ReviewTurnId + studySessionId travel with every effect/event; a turn cannot
                         be accepted for another session.
Start admission:         Single serialized reducer; a second Start while a session is active is
                         rejected (`already-active`) — no duplicate binding, no second Begin effect
                         (VER-21G-06).
Callback validation:     Two independent layers. (1) Reducer epoch/turn/commit-id guards reject stale
                         results and *record* the rejection in transition history. (2) The executor's
                         session-lock guard refuses a stale or forged *effect* before any backend call
                         (`session_backend_mismatch`), which also covers the window after the lock was
                         released.
Cancellation:            Cancellation is treated as insufficient by itself, exactly as PART 21G-11
                         requires: rejection is by identity at the mutation boundary, not by the
                         caller's job having been cancelled.
State-update guards:     VER-21G-24/25/26/27 deliver session A's delayed next-card, hydration,
                         evaluation and rating callbacks after an A → B → A sequence; all four are
                         rejected, and session C's epoch/phase/interaction/session/cardTurn are
                         unchanged.
```

---

## 8. File-by-file implementation report (GATE 21G scope)

| FILE | ACTION | CONTRACT REFS | RESPONSIBILITY | IMPLEMENTED | LOCAL CHECKS |
|---|---|---|---|---|---|
| `core/anki/StudySessionBackendLock.kt` | CREATED (prior execution) / REUSED (this one) | DECISION-21B-11/12/14 | Immutable lock model, codec, admission, continuation, restore policies | Yes — verified by this execution | `Gate21gLockAdmissionTest` (34), `Gate21gSessionContinuityTest` (12), `Gate21gStoreAndProjectionTest` (11), `Gate21gArchitectureAuditTest` (5), + 17 new |
| `core/anki/StudySessionLockStore.kt` | CREATED / REUSED | 21G-06 | `save`/`load`/`clear` interface + in-memory implementation | Yes | store tests + new VER-21G-21/22/23 |
| `data/preferences/StudySessionLockDataStore.kt` | CREATED / REUSED | 21G-06 | DataStore-backed byte-moving adapter | Yes | `Gate21gStoreAndProjectionTest`, `AppSettingsPreferencesCodec` key |
| `data/preferences/PreferencesDataStore.kt` | MODIFIED (prior) | 21G-06 | `study_session_backend_lock` flow/writer/clearer | Yes | codec round-trip |
| `core/study/StudySessionOwnershipProjection.kt` | CREATED / REUSED | 21G-24 | Read-only ownership projection for 21M | Yes | projection tests + new VER-21G-32 |
| `core/study/AnkiStudyEffectExecutor.kt` | MODIFIED (prior) | 21G-03/04/08/17/19 | Admission before `beginReview`; lock publish + persistence in the same acceptance; bound-work guards; `EndReview` release/clear | Yes | continuity tests + new VER-21G-06…28, 33 |
| `data/repository/AnkiLocalStudyStarter.kt` | MODIFIED (prior) | 21E/21G-03 | Pre-binding resolution migrated to `AnkiBackendResolver2`; selector dependency removed | Yes | `AnkiLocalStudyStarterTest` |
| `di/AppContainer.kt` | MODIFIED (prior) | 21G-06/26 | Wires tracker + `studySessionLockStore` into the study executor | Yes | compile + suite |
| `app/src/test/.../study/AnkiCommitHarness.kt` | **MODIFIED (this execution)** | 21G-25 | Optional `sessionLockStore` parameter (default `null`) so verification can observe durable ownership | Yes | full suite unchanged at 2487 before the new class; 2504 after |
| `app/src/test/.../study/Gate21gVerificationTest.kt` | **CREATED (this execution)** | PART III | 17 authoritative verification cases over two independent fakes | Yes | 17/17 |

---

## 9. Architecture audit matrix

| Audit | Result | Evidence |
|---|---|---|
| AUDIT-21G-01 Contract conformance | PASS | Lock model + policies implement the 21B/21C/21E ownership contract; resolver confined to two pre-binding sites (`Gate21gArchitectureAuditTest`, `Gate21ArchitectureAuditTest` reshaped) |
| AUDIT-21G-02 Single backend ownership | PASS | `admit` produces exactly one lock; reducer admits one active session; new VER-21G-06 |
| AUDIT-21G-03 Immutable binding | PASS | Only writers of the tracker map are `locked()` (idempotent per session) and `released()` (only from `EndReview`); no path rewrites a lock. Churn run: 0 switches over 1000 events |
| AUDIT-21G-04 Correct binding moment | PASS | Resolution → admission → recovery scans → deck check precede `beginReview`; audit test asserts admission text precedes `beginReview` |
| AUDIT-21G-05 AUTO isolation | PASS | `resolve(` appears only inside `begin()`; new VER-21G-07/08 churn asserts the session ignores a resolution that answers the other backend |
| AUDIT-21G-06 Scheduler authority | PASS | Every read/rating/action routes through `registry.find(lockedId)`; VER-21G-09/10/11/16 |
| AUDIT-21G-07 No local scheduler | PASS | No FSRS/due-order/queue code added; next card is `NextCardResult` from the backend's own scheduler; capability gate requires `scheduledReview` |
| AUDIT-21G-08 Collection integrity | PASS | Lock records the *live* key only; deck-vs-live mismatch refuses admission; VER-21G-18/19/20 |
| AUDIT-21G-09 Reconnect safety | PASS | `StudySessionContinuationPolicy` requires the 21C matrix verdict; a transport-level reconnect alone never yields a verdict; unknown ≠ verified match |
| AUDIT-21G-10 Backend loss safety | PASS | VER-21G-17: A lost, B ready → B scheduler calls 0, B mutation calls 0, A stays owner, persisted owner unchanged |
| AUDIT-21G-11 Session persistence | PASS (API-level) | Persist-on-bind, clear-on-end, refusal of an unpersistable start; restore over a simulated process death (VER-21G-21/22/23). No production auto-restore caller exists yet — 21H scope (residual risk) |
| AUDIT-21G-12 Historical session safety | PASS | Absent/invalid/future bytes never yield a backend; no AUTO, no sibling (VER-21G-22/23) |
| AUDIT-21G-13 Stale event rejection | PASS | VER-21G-24/25/26/27: A→B→A with A's delayed callbacks delivered last; all rejected; C unchanged |
| AUDIT-21G-14 ReviewCommit integration | PASS | Guard refuses before the ledger; ambiguous outcome → no retry, no progression, no switch (VER-21G-14); commit+read-failure → no resubmission (VER-21G-15); end with pending transaction leaves the record intact (VER-21G-28) |
| AUDIT-21G-15 ReviewerAction integration | PASS | VER-21G-16: bury applied on the locked backend, other backend untouched; foreign action refused as `NotRecorded(session_backend_mismatch)` before anything durable |
| AUDIT-21G-16 Custom Study compatibility | BLOCKED / DEFERRED | GATE 20 is design-only (`IMPLEMENTATION AUTHORIZED: NO`); no custom/filtered study runtime exists to bind. Requirement documented in §5 |
| AUDIT-21G-17 Voice/AI separation | PASS | PC AI/reconciler paths carry no Anki backend selection; `StudyEffect.Voice.*` and evaluation results travel with epoch/turn identity and are rejected when stale (VER-21G-25). No new AI/TTS behaviour added |
| AUDIT-21G-18 Read-context separation | PASS | 21F contexts are replaceable and are never consulted for session routing; the session lock is an `AnkiSessionContext` owned by the session, and the projection is data-only (`Gate21gArchitectureAuditTest`, `Gate21fArchitectureAuditTest`) |
| AUDIT-21G-19 Source-of-truth integrity | PASS | Registry = lookup, Resolver = pre-binding policy, lock = session ownership, ledgers = transaction truth; 21G adds no fourth store; no ledger duplication in the projection/lock |
| AUDIT-21G-20 Scope discipline | PASS | No failover, cross-backend replay, new AUTO policy, registry, scheduler, ledger, or offline queue in the 21G diff (audit test + this execution's diff: test files only) |

---

## 10. Verification matrix (PART III, authoritative)

Result vocabulary: **PASS** (executed evidence; `PASS (structural)` = proven by source/architecture evidence where no runtime path exists) · **BLOCKED/DEFERRED** (prerequisite absent under the locked scope policy) — never a claimed pass.

| Verification | Result | Evidence |
|---|---|---|
| VER-21G-01 Initial session binding | PASS | `Gate21gSessionContinuityTest.begin persists the exact lock…`; new VER-21G-12 context assertions |
| VER-21G-02 AUTO binding | PASS | New `an auto start keeps its resolved backend when the preference and readiness change` (AUTO → AnkiDroid, lock published) |
| VER-21G-03 Explicit backend binding | PASS | `a second session binds independently…` (explicit `PC_AGENT` → PC lock); new VER-21G-16 executor case |
| VER-21G-04 Missing backend | PASS | `admission refuses when the resolved backend is not the registered one`; new `restoration never substitutes another ready backend` |
| VER-21G-05 Capability failure | PASS | `admission refuses a backend without the review capability`; executor refuses with `required_capability_unavailable` and publishes no lock |
| VER-21G-06 Duplicate start | PASS | New `rapid repeated starts admit exactly one binding` (3 Starts → 2 `already-active`, 1 Begin effect, 1 lock, 1 persisted owner) |
| VER-21G-07 Preference change during study | PASS | New VER-21G-07/08 test: preference flips to explicit PC and a fresh resolution *does* answer PC; the session's commit still names the locked backend; PC counters all 0 |
| VER-21G-08 AUTO state change during study | PASS | Same test: locked backend becomes unavailable while PC stays ready → read reports its own unavailability, PC untouched, lock unchanged |
| VER-21G-09 Next-card routing | PASS | `next on the locked backend routes normally`; new churn asserts ≥100 reads, all on the lock |
| VER-21G-10 Card identity collision | PASS | New `a colliding numeric card id stays bound to the session's own backend` (both collections hold card id 123; the session reads its own; a forged foreign route is refused) |
| VER-21G-11 Review information | PASS | Rating options ride the same authoritative turn (`AnkiScheduledCard.ratingOptions`); no independent info path exists; asserted in the collision test's turn identity |
| VER-21G-12 Rating context | PASS | New `the rating context carries the session's locked backend and turn` (commitId backendId/sessionId/turnId + card backend) |
| VER-21G-13 No next card before commit | PASS | New `no next card is queried while the durable commit is outstanding` (read count frozen while the commit is pending; +1 after COMMITTED); churn asserts 0 violations over 1000 events |
| VER-21G-14 Ambiguous rating | PASS | New `an ambiguous rating never retries advances or switches` (no retry effect, no read, no switch, ledger AMBIGUOUS) |
| VER-21G-15 Commit success / next-card failure | PASS | New `a committed rating is never resubmitted when the next card read fails` (1 delivery, 1 mutation, still 1 after the read retry) |
| VER-21G-16 Reviewer action context | PASS | New `a reviewer action reaches only the locked backend` (real ledger + coordinator; applied on the lock; other backend 0 action calls) |
| VER-21G-17 Backend A loss | PASS | New `backend loss leaves the other ready backend untouched on every path` (+ existing test): **B scheduler calls = 0, B hydration = 0, B mutation calls = 0, B deck reads = 0, B handle ends = 0** |
| VER-21G-18 Same-backend verified reconnect | PASS | New continuation cells: original collection → `Permitted` |
| VER-21G-19 Verified collection mismatch | PASS | New continuation cells: different verified key → `Blocked(collection_mismatch)`; restore blocks with the lock preserved |
| VER-21G-20 Unknown collection assurance | PASS | New continuation cells: unverifiable identity → `Blocked(collection_identity_degraded)`; post-death keyless lock → nominal-unverified (`Gate21gLockAdmissionTest`) |
| VER-21G-21 Process restoration | PASS (API scope) | New `restoration preserves the original backend owner` (executor-created lock, simulated process death, restored id/session match, no new binding, 0 scheduler calls) |
| VER-21G-22 Original backend missing after restart | PASS | New `restoration never substitutes another ready backend` (only-PC registry + AnkiDroid lock → `Blocked(backend_not_registered)`, PC untouched, lock preserved) |
| VER-21G-23 Legacy session without backend identity | PASS | New `a legacy session without ownership is never guessed` (absent → `no_persisted_lock`; future schema → `unsupported_lock_schema`; no substitution) |
| VER-21G-24 Stale next-card callback | PASS | New A→B→A test: A's delayed `Scheduled` rejected (`stale-anki-scheduler`), C unchanged |
| VER-21G-25 Stale AI evaluation callback | PASS | Same test: `AnswerEvaluationFailed` from A rejected (`stale-anki-evaluation`) |
| VER-21G-26 Stale rating callback | PASS | Same test: A's `RatingCommitResolved` rejected (`stale-anki-commit-result`); durable truth untouched |
| VER-21G-27 A → B → A session sequence | PASS | Same test performs the full sequence; A/B locks released, C lock correct, A's callbacks inert |
| VER-21G-28 Session end with pending transaction | PASS | New `ending a session leaves an unresolved rating record intact` (status, attempts and failure category identical after the end; lock released) |
| VER-21G-29 Custom Study ownership | BLOCKED / DEFERRED | GATE 20 design-only — no filtered/custom session surface exists to verify. Contract requirement recorded (§5) |
| VER-21G-30 PC AI service independence | PASS (structural) | No Anki routing consults AI/TTS state; evaluation results are epoch/turn-scoped and cannot select a backend (VER-21G-25); the 21G diff adds no AI/TTS code |
| VER-21G-31 Read context independence | PASS (structural) | 21F read contexts are separate objects with their own generations; the executor's lock guard is the only study-routing input; no read-context type appears in any 21G file (audit) |
| VER-21G-32 Session diagnostics accuracy | PASS | New `ownership diagnostics report the lock not the preference` (projection reports the locked AnkiDroid id, never the PC preference) |
| VER-21G-33 1,000-event deterministic endurance | PASS (strengthened) | New `a thousand-event churn holds every ownership counter at zero`: two independent fakes (PC always ready), 1000 events mixing review turns, preference flips, availability loss/recovery probes, collection-identity churn, stale/duplicate callbacks, reviewer-action attempts, restoration checks and forged foreign routes → **mid-session switches 0 · stale state accepted 0 · next-card-before-commit 0 · cross-backend replays 0 · identity reinterpretations 0 · cross-backend scheduler/rating/action calls 0**, with non-vacuity assertions (≥100 real mutations, ≥100 reads, ≥100 resolutions, ≥90 outage probes, ≥40 guard refusals) |
| VER-21G-34 Previous gate regression | PASS (harness) | Full harness suite after the change: **2504/2504 across 210 classes**, including every pre-existing GATE 7–21F suite. Gradle task BLOCKED (§11). Design-only features (GATE 20) are not claimed as runtime-tested |

---

## 11. Build results

| Command | Result | Detail |
|---|---|---|
| `tools/jvm-harness/bin/build.sh all` | PASS | main 325 files / compose closure 24 files / tests 242 files — **0 errors** in every scope |
| `tools/jvm-harness/bin/run.sh` (full suite) | PASS | `RESULT classes=210 tests=2504 passed=2504 failed=0 ignored=0` |
| `tools/jvm-harness/bin/run.sh 'Gate21g'` | PASS | pre-existing 21G suite 62/62 + new class 17/17 |
| `./gradlew --version` | BLOCKED | `JAVA_HOME` unset initially; with the harness JDK: wrapper cannot fetch `gradle-8.7-bin.zip` → `java.io.EOFException: SSL peer shut down incorrectly` (services.gradle.org unreachable from this sandbox — the same blocker recorded by GATE 21C/21D/21E/21F) |
| `./gradlew testDebugUnitTest` / `lint` / `assembleDebug` / `assembleRelease` | BLOCKED | Cannot install the Gradle distribution; `ANDROID_HOME`/`ANDROID_SDK_ROOT` are unset and no Android SDK or emulator exists in this sandbox |
| `./gradlew connectedDebugAndroidTest` | BLOCKED | No device/emulator |

No unexecuted task is claimed as passing. The harness is the verified path for this repository state
(Kotlin 2.3/K2 vs the Gradle toolchain's Kotlin 1.9.24; documented deviations in
`tools/jvm-harness/README.md`). CI must run `testDebugUnitTest` before this gate is treated as
CI-verified.

---

## 12. Invariant report and defects

### 12.1 Invariants

| Invariant | Result | Evidence |
|---|---|---|
| INV-21G-01 one session → one configured backend | PASS | Admission yields one lock; reducer admits one active session (VER-21G-06) |
| INV-21G-02 ownership immutable at binding | PASS | Tracker writers: `locked()` (post-acceptance, idempotent per session+backend) and `released()` (only from `EndReview`); no rewrite path; 0 switches over 1000 churn events; §13 records the same-id re-bind caveat |
| INV-21G-03 AUTO only before binding | PASS | `AnkiBackendResolver2.resolve(` appears only inside `begin()` (audit test) |
| INV-21G-04 preference changes do not alter active sessions | PASS | VER-21G-07/08: a resolution that answers PC does not affect the locked session; commit still names the lock |
| INV-21G-05 availability changes do not alter ownership | PASS | VER-21G-08/17: loss keeps the owner, lock and persisted bytes |
| INV-21G-06 mid-session failover prohibited | PASS | 0 wrong-backend scheduler/rating/action calls in every scenario, including 1000-event churn |
| INV-21G-07 every scheduler request uses the lock | PASS | `sessionLockConsistent` precedes Next/Hydrate/commit/action; refusals carry `session_backend_mismatch` |
| INV-21G-08 FSRS/next-card stay backend-owned | PASS | No client scheduling code introduced; capability gate requires `scheduledReview`; next card is a `NextCardResult` |
| INV-21G-09 collection context bound to the session | PASS | Lock records the live key at bind; `toSessionContext` preserves it; restore reports it |
| INV-21G-10 unknown identity ≠ verified continuity | PASS | `collection_identity_degraded` / nominal-unverified verdicts |
| INV-21G-11 verified mismatch blocks incompatible operations | PASS | `collection_mismatch` at admission, continuation and restore |
| INV-21G-12 restoration preserves original ownership | PASS | VER-21G-21 (simulated process death) |
| INV-21G-13 restoration never invokes AUTO | PASS | `StudySessionRestorePolicy` has no resolver and no writer (audit test) + VER-21G-22/23 |
| INV-21G-14 legacy missing ownership never guessed | PASS | VER-21G-23: absent → `no_persisted_lock`; future schema → `unsupported_lock_schema` |
| INV-21G-15 ReviewTurn identity session-qualified | PASS | `ReviewTurnId` + `studySessionId` on every turn/effect; forged foreign turns refused |
| INV-21G-16 old callbacks cannot mutate new sessions | PASS | VER-21G-24/25/26/27 over A→B→A (all rejected; C unchanged) |
| INV-21G-17 rating uses GATE 11 authority | PASS | Ledger untouched by 21G; ambiguous/committed semantics preserved (VER-21G-14/15) |
| INV-21G-18 no progression before permitted commit | PASS | VER-21G-13 + 0 violations in churn |
| INV-21G-19 ambiguous ratings never blindly retried | PASS | VER-21G-14: no retry effect, no read, no switch |
| INV-21G-20 reviewer actions use lock + GATE 13 pipeline | PASS | VER-21G-16 (real ledger/coordinator) + foreign action refused pre-durably |
| INV-21G-21 Custom Study preserves scheduler authority | PASS (structural) | No custom/filtered runtime exists (GATE 20 design-only); the requirement is documented and no conflicting path was added — runtime case VER-21G-29 remains deferred |
| INV-21G-22 PC AI/TTS independent of PC Anki authority | PASS | No AI/TTS state participates in backend selection; stale AI results rejected (VER-21G-25) |
| INV-21G-23 21F read contexts cannot change session ownership | PASS | Separate objects/generations; no read-context type appears in any 21G file (audit test) |
| INV-21G-24 ending a session preserves unresolved transactions | PASS | VER-21G-28: status/attempts/failure unchanged after end |
| INV-21G-25 no second resolver/registry/machine/ledger | PASS | `Gate21gArchitectureAuditTest` + this execution's diff is test-only |
| INV-21G-26 no private AnkiDroid storage access | PASS | 21G touches no provider/collection storage; the diff contains no `data/anki/ankidroid` change |
| INV-21G-27 session identity ≠ transaction identity | PASS | Distinct types (`studySessionId` vs `ReviewCommitId`/`ReviewerActionId`) and distinct persistence |
| INV-21G-28 21G does not weaken GATE 10–21F | PASS | Full suite green (2504/2504) including every pre-existing contract/audit class |

### 12.2 Production defects

**None found.** No production file was changed in this execution; the 17 new cases passed against the landed implementation once the tests' own expectations were corrected (§12.3). Specifically re-checked and clean: admission ordering, persistence-failure rollback, all four bound-work guards, end-of-session release/clear, restore refusal paths, and the no-failover behaviour under churn.

### 12.3 Verification-quality defects found (and what was done)

1. **Near-vacuous pre-existing endurance test (GATE 21).** `Gate21SessionBackendLockTest.'1000 turns with preference and availability churn never move a session between backends'` degenerates after ~4 turns: the availability flip drives the machine into a terminal `RatingCommitFailed` phase, after which ~996 of its 1000 iterations do nothing. Measured with a throwaway replica: `reachedRating=4 commits=4 finalPhase=RatingCommitFailed mutations=3 nextReads=4` while the test still asserts "0 switches / 0 cross-backend commits". *Not modified* (it is GATE 21's locked verification, not 21G's), but 21G's own endurance requirement (VER-21G-33) is now discharged by the new non-vacuous churn test, which asserts that the run actually reviewed, resolved, probed outages and exercised the guard. Recommended follow-up: add non-vacuity assertions to the GATE 21 test or retire it in favour of the new class.
2. **VER coverage was mapped, not executed, in the prior 21G report.** The implementation report mapped VER-21G-01…34 onto its classes by family, leaving the cases listed in §10 (06, 07/08, 09–15, 16, 21–23, 24–28, 32, 33) without a test that names and exercises them. This execution added direct evidence for each executable case.

---

## 13. Residual risks

```text
No production restore wiring        StudySessionLockStore.load() / StudySessionRestorePolicy have no
                                    production caller yet — 21H owns restore routing and the OP-P
                                    transaction barrier. Process-death restore is therefore verified
                                    at the API level, not end-to-end in the app.
Single lock slot                    The store persists one lock; the reducer admits one active session,
                                    so this matches the app's single-session reality. Two overlapping
                                    sessions leave the earlier session's persisted owner overwritten
                                    (its tracker entry survives) — tracked, not a 21G defect.
Collection identity in production   The real AnkiDroid backend reports no collection identity, so the
                                    AnkiDroid path binds `collection_unknown` (nominal); verified
                                    mismatch handling is exercised against the reporting fake and the
                                    PC path. Real PC-agent collection reporting remains unverified.
Reconnect assurance                 `boundIsLiveInThisProcess` is a caller obligation; a future caller
                                    that passes `true` without an actual in-process binding would
                                    overstate continuity. The API name and the audit test are the
                                    guardrails.
Capability changes after binding    A capability lost mid-session blocks via the continuation policy at
                                    the reconnect/restore boundary; the per-operation guard is the
                                    backend's own response. No dynamic capability re-negotiation exists.
Unimplemented Custom Study          GATE 20 (design-only) means no filtered-session ownership exists to
                                    bind; CHECKPOINT 21G-20 and VER-21G-29 stay BLOCKED/DEFERRED.
Same-session-id re-bind              `AnkiStudyBackendLockTracker.locked()` replaces an existing entry
                                    when `backendId` differs, and `released()` is keyed only by
                                    studySessionId. A caller that re-used a studySessionId for a new
                                    session *before* the old EndReview effect ran would therefore
                                    overwrite the first owner and later release the second. Not
                                    reachable today (the starter mints a fresh UUID per start and the
                                    reducer admits one active session), but the restore wiring GATE 21H
                                    adds must keep minting/validating session ids instead of
                                    re-binding a persisted one.
Long-running unresolved transactions Unfinished commits/actions can outlive a session by design; 21G
                                    neither replays nor deletes them, and their routing/recovery audit
                                    belongs to GATE 21H/21I.
Gradle/CI                           PART V Gradle tasks are environmentally blocked; CI must run them.
```

---

## 14. Handoff to GATE 21H — Review Commit Routing & Recovery

Contracts GATE 21H must consume (all implemented and verified at 21G's scope):

```text
StudySessionId              StudySessionBackendLock.studySessionId ; AnkiSessionContext.studySessionId
Locked BackendId            StudySessionBackendLock.backendId  (immutable for the session's lifetime)
Collection context          StudySessionBackendLock.collection (AnkiCollectionIdentity?) +
                            deckRef ; live capability set is re-read on restore, never persisted
ReviewTurnId                AnkiReviewTurn.turnId ; ReviewCommitId(backendId, studySessionId, turnId)
ReviewCommit request ctx    CommitRatingRequest.commitId (backendId + session + turn) — the session
                            guard agrees with it or the commit is refused undispatched
Durable ReviewCommitRecord  GATE 11 ledger entry (status, attempts, frozen semantics) — untouched by
                            21G; ending a session never deletes or resolves it
Session restoration         StudySessionRestorePolicy.restore(decoded, lookup, hasUnresolved,
                            boundIsLiveInThisProcess) → Restored(lock, assurance, barrier) /
                            Blocked(reason, error) / Invalid(reason)
Blocked session semantics   Blocked keeps the lock, publishes no new binding, performs no backend call,
                            reports the stable reason token; no AUTO, no sibling, no rewrite
```

**Implemented and guaranteed by 21G** (21H may rely on these): immutable per-session ownership; the
resolver confined to pre-binding; exact registry lookup for all scheduler traffic; guards that refuse
foreign/stale effects before any backend or ledger interaction; persistence of ownership on bind and
clear on end; refusal of an unpersistable start; restore verdicts that cannot change ownership.

**Requires 21H audit/remediation** (deliberately not done here): wiring restore into the app's startup
path; the OP-P transaction barrier that consumes `hasUnresolvedTransactions` before the first
next-card; routing unresolved commits/actions of a restored session by their *persisted* backend.
21G does not implement, and must not be read as having implemented, any of that.

---

## 15. Gate lock decision

```text
GATE 21G:                   LOCKED (harness-verified; Gradle blocked environmentally)

SESSION BINDING:            PASS
BACKEND IMMUTABILITY:       PASS
SCHEDULER OWNERSHIP:        PASS
COLLECTION CONTEXT:         PASS
PERSISTENCE:                PASS (API scope; production restore wiring = 21H)
RECONNECT SAFETY:           PASS
RATING INTEGRATION:         PASS
STALE CALLBACK PROTECTION:  PASS
ARCHITECTURE AUDIT:         PASS (19 PASS / 1 BLOCKED-DEFERRED: AUDIT-21G-16)
VERIFICATION:               PASS (33 PASS / 1 BLOCKED-DEFERRED: VER-21G-29; 30/31 are structural passes)
BUILD:                      PASS at the harness scope; Gradle BLOCKED (environmental)

NEXT AUTHORIZED SUB-GATE:   21H — Review Commit Routing & Recovery
```

Successful result, as required by PART VIII:

```text
GATE 21G — STUDY SESSION OWNERSHIP LOCKED

ONE STUDYSESSION:                    ONE BACKEND
BACKEND OWNERSHIP:                   IMMUTABLE
SCHEDULER AUTHORITY:                 BACKEND-OWNED
AUTO RESOLUTION:                     PRE-SESSION ONLY
GLOBAL PREFERENCE CHANGE:            NO ACTIVE SESSION EFFECT
MID-SESSION FAILOVER:                PROHIBITED
COLLECTION MISMATCH:                 FAIL CLOSED
STALE CALLBACKS:                     REJECTED
RATING COMMIT:                       EXISTING GATE 11 AUTHORITY
CROSS-BACKEND TRANSACTION REPLAY:    PROHIBITED
```

**GATE 21H was not started.**
