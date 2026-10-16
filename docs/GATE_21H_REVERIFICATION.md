# GATE 21H — Re-Execution & Reverification Report (PART VII §1–15)

**Gate:** 21H — Review Commit Routing & Recovery (parent: GATE 21 — Multi-Backend Anki Selection /
Hybrid Mode).
**Execution type:** Implementation → Independent Architecture Audit → Authoritative Verification →
Gate Lock (re-execution over an already-merged implementation).
**Branch:** `arena/f628f0c2-study-agent-client`.
**Companions:** `docs/GATE_21H_REVIEW_COMMIT_ROUTING.md` (contract + decisions),
`docs/GATE_21H_VERIFICATION_REPORT.md` (original lock record of the implementing session).

This session executed GATE 21H's full procedure against the repository as found at
`da5214b79bbff858d0e3fa8788fe9eaa1e3167c7`. Per the gate's own rule ("Do not assume prior
specifications were implemented. Verify actual implementation status."), every checkpoint was
inspected in real source and every verification was **re-executed** in this environment. The
implementation was found complete and locked; **zero production-code changes were required or
made**. The only file added by this session is this report.

**Gate-lock decision: LOCKED (re-affirmed)** — all mandatory items pass on evidence executed here;
VER-21H-35 (real-backend validation) is **BLOCKED** environmentally and is reported, not omitted;
`./gradlew` is **BLOCKED** in this sandbox (wrapper TLS failure), same limitation every prior gate
carried.

---

## §0 Required initial report (as found, before any change)

```text
REPOSITORY HEAD: da5214b79bbff858d0e3fa8788fe9eaa1e3167c7
BRANCH:          arena/f628f0c2-study-agent-client
WORKTREE STATUS: clean at the start of the gate

GATE 11:  IMPLEMENTED  (ReviewCommit.kt, ReviewCommitLedger.kt (958 l, codec v4 + legacy
          normalisation), ReviewCommitTransition.kt, ReviewCommitCoordinator.kt,
          ReviewCommitRecovery.kt, ReviewCommitReconciliation.kt, ReviewCommitRecoveryPolicy.kt,
          DataStoreReviewCommitStore.kt, AnkiStudyEffectExecutor.commitTransaction)

GATE 21A: LOCKED   (docs/GATE_21A_DISCOVERY.md — PASS, audits 10/10, verifications 10/10)
GATE 21B: LOCKED   (docs/GATE_21B_CONTRACT_LOCK.md — DECISION-21B-01…30)
GATE 21C: LOCKED   (docs/GATE_21C_IDENTITY_IMPLEMENTATION.md)
GATE 21D: LOCKED   (docs/GATE_21D_REGISTRY_IMPLEMENTATION.md + REVERIFICATION)
GATE 21E: LOCKED   (docs/GATE_21E_RESOLVER_IMPLEMENTATION.md + REVERIFICATION)
GATE 21F: LOCKED   (docs/GATE_21F_READ_CONTEXT_ISOLATION.md)
GATE 21G: LOCKED   (docs/GATE_21G_SESSION_LOCK_IMPLEMENTATION.md + VERIFICATION_REPORT +
                    POST_21N_REVERIFICATION)

REVIEW COMMIT LEDGER:                EXISTS (single class ReviewCommitLedger,
                                     prepare/claim/markMutationEntered/markResponseReceived/
                                     complete/markRefused/markNotCommitted/markAmbiguous/reconcile)
REVIEW COMMIT COORDINATOR:           EXISTS (interface ReviewCommitCoordinator;
                                     AnkiStudyEffectExecutor implements it)
DURABLE TRANSACTION BACKEND IDENTITY: EXISTS (ReviewCommitId(backendId, studySessionId, turnId),
                                     identity in the durable store keys)
AMBIGUOUS RECOVERY:                  IMPLEMENTED (five-status vocabulary, load-time
                                     SUBMITTING→AMBIGUOUS heal, closed transition table,
                                     nextCardAllowed ⟺ COMMITTED)

MISSING/CONTRADICTORY NORMATIVE CONTRACT: none found — BACKEND_IDENTITY_CONTRACT,
BACKEND_FAILURE_MATRIX, BACKEND_OWNERSHIP_INVARIANTS and GATE 11D cover every cell 21H needs.
GATE 21H: NOT BLOCKED — IMPLEMENTATION AUTHORIZED: YES (and found already implemented).
```

---

## §1 Gate result

```text
GATE 21H — REVIEW COMMIT ROUTING & RECOVERY

STATUS: PASS   (VER-21H-35 BLOCKED-environmental, reported in §10)

REPOSITORY HEAD: da5214b79bbff858d0e3fa8788fe9eaa1e3167c7

PREREQUISITES:
GATE 11: IMPLEMENTED (harness-verified; original gate carried a real-device limitation)
21A: LOCKED      21B: LOCKED      21C: LOCKED      21D: LOCKED
21E: LOCKED      21F: LOCKED      21G: LOCKED
```

## §2 ReviewCommit architecture (actual component names)

```text
CardTurn.turnId → ReviewTurnId
    ↓  StudyReducer.selectRating (dedup: "duplicate-anki-rating" / "anki-rating-locked-first-wins")
AnkiStudyEffect.CommitRating(CommitRatingRequest(commitId, rating, collectionRef, …))
    ↓  AnkiStudyEffectExecutor.commitTransaction  (impl of ReviewCommitCoordinator)
    │    ① sessionLockConsistent(session, backendId)          — GATE 21G, refuse before ledger
    │    ② ReviewerActionLedger.findActiveForTurn             — GATE 13 mutual exclusion
    │    ③ ReviewCommitLedger.prepare  → PREPARED durable     — persistence failure ⇒ no call
    │    ④ ReviewCommitRoutingPolicy.route(record, OP-M1/OP-M2)   — record.backendId only
    │         registry.find(commitId.backendId) → exact AnkiBackend instance
    │         AnkiIdentityAssurancePolicy.check + AnkiIdentityContinuation.continuationVerdict
    │         Blocked ⇒ ledger.markRefused(token) with ZERO backend calls
    │    ⑤ backend.prepareCommit (read-only evidence) → ledger.claim → SUBMITTING intent durable
    │    ⑥ backend.commitRating { boundary: ledger.markMutationEntered → then scheduler API }
    │    ⑦ ledger.markResponseReceived → ledger.complete → COMMITTED durable
    ↓  StudyReducer.resolveAnkiCommit (epoch/session/turn correlation)
AnkiCommitOutcome.allowsNextCard()  ⟺  status == COMMITTED  →  Next-card on the locked backend
```

The exact registry lookup is `AnkiBackendRegistry.find(AnkiBackendId)` (GATE 21D); AUTO
(`AnkiBackendResolver`) is used only to bind **new** sessions and never on the commit, retry,
reconcile or recovery path (`ReviewCommitRoutingPolicy.kt` contains no preference/resolver read —
the only occurrences of those words are documentation comments).

## §3 Transaction identity contract

- `ReviewCommitId(backendId: AnkiBackendId, studySessionId: String, turnId: ReviewTurnId)` —
  same backend + session + turn = same retry key; a changed rating payload is
  `PrepareResult.Conflict` ("commit_payload_conflict"), never a second transaction.
- Identity lives in the **durable store keys**, so ownership survives process death and codec
  migration (`ReviewCommitLedgerCodec`, schema v4 + legacy normalisation for schemas 1–4).
- Collection context: `ReviewCommitRecord.collectionRef` (written at admission from
  `session.context.collection`) is the **binding evidence**; a card/deck ref's own collection key is
  resource qualification and never constructs binding evidence (DECISION-21H-02).
- `frozenGuarantee: CommitSemantics` is frozen at preparation (`CommitSemantics.ANKIDROID` =
  `AT_MOST_ONCE_FAIL_CLOSED`, `CommitReceiptKind.NONE`); a locally generated value is never a
  backend receipt (`CommitReceipt` requires a backend-issued id).
- No credentials, card content, audio or transcripts appear in any identity or diagnostics field
  (`CommitTruthSnapshot` is content-free tokens only).

## §4 Durable state machine (five statuses, closed vocabulary)

| Status | Entry evidence | May do next |
| --- | --- | --- |
| `PREPARED` | intent durably written before any dispatch; boundary provably un-entered | submit (explicit), or after restart submit again — the only state that is retry-restorable |
| `SUBMITTING` | `markMutationEntered` durably written **immediately before** the scheduler API | nothing automatic: crash/interrupt here loads back as `AMBIGUOUS` (INV-21H-09) |
| `COMMITTED` | backend-confirmed response durably written (`markResponseReceived` is itself durable proof; `complete` finalises) | next-card when the turn is current; **never** resubmitted |
| `RETRY_ALLOWED` | non-application established by contract (`ConfirmedNotCommitted`, certified pre-dispatch refusal ⇒ `REFUSED_BEFORE_DISPATCH`, or reconciliation negative evidence) | explicit user retry of the *same* commitId/rating on the *same* backend |
| `AMBIGUOUS` | outcome unknown (timeout/lost response/crash after boundary, unsupported reconciliation, integrity failure) | read-only reconciliation on the original backend when supported; otherwise remain blocked |

Transition authority: `ReviewCommitTransition` (closed table) + `ReviewCommitRecoveryPolicy`
(`ResumeCommitted / OfferRetry / Reconcile / RemainBlocked / IntegrityFailure` — recovery *decisions*,
never status names). No status was added or renamed by 21H.

## §5 Backend routing matrix

| Operation | Source of BackendId | May use AUTO? |
| --- | --- | --- |
| New StudySession binding | GATE 21E resolution (before binding) | Yes — before binding only |
| Prepare rating | Locked StudySession (21G) + `ReviewCommitId.backendId` | **No** |
| Submit rating | Persisted transaction (`record.backendId` → `registry.find`) | **No** |
| Retry permitted rating | Persisted transaction | **No** |
| Reconcile ambiguous rating | Persisted transaction (OP-M3, gated before the provider) | **No** |
| Recover after restart | Persisted transaction (`recoveryPlan` groups by owner) | **No** |
| Next card | Locked StudySession | **No** |

## §6 Crash boundary report (observed durability semantics)

| Window (fires in `commitTransaction`) | Durable state after death | Safe interpretation after restart |
| --- | --- | --- |
| before `prepare` | no row | nothing exists; no mutation can have been sent |
| `BEFORE_LEDGER_CREATE` | no row | same |
| `AFTER_PREPARED` | `PREPARED` | boundary provably un-entered; resubmission allowed (explicit) |
| claim / SUBMITTING-intent write fails | `PREPARED` (claim rolled back / never entered) | no call was made (`ReviewCommitDurabilityOrderTest.a failed mutation-entry write means the provider is never touched`) |
| `AFTER_CALL_ENTERED`, `BEFORE_PROVIDER_CALL` | `SUBMITTING` → loads `AMBIGUOUS` | possibly applied; no blind retry |
| `AFTER_PROVIDER_MUTATION`, `BEFORE_RESPONSE_PERSIST` | `SUBMITTING` → `AMBIGUOUS` | possibly applied; no blind retry |
| `AFTER_RESPONSE_PERSIST`, `BEFORE_COMMITTED_PERSIST` | confirmed response is durable proof → `COMMITTED` | terminal; a lost final write never authorises a resubmit (`unpersistedResponses` retries the **write**) |
| `AFTER_COMMITTED_PERSIST` | `COMMITTED` | terminal |
| next-card read fails | `COMMITTED` | retry the **read**, never the rating (`AnkiRatingCommitFlowTest.M`) |

Executed evidence: `Gate21hCommitEnduranceTest` crash sweep — 9 fired windows × 100 injected
crashes = 900, plus 100 clean controls; zero double applications, zero owner rewrites, zero
progression without durable `COMMITTED`. `ReviewCommitCrashWindowTest`, `ReviewCommitDurabilityOrderTest`
and `Gate11eEnduranceTest` (5 restarts × 5 statuses) re-executed in the full suite.

## §7 Recovery & reconciliation report

```text
AnkiDroid reconciliation capability:  UNSUPPORTED (CommitSemantics.ANKIDROID; no transaction-
                                      correlated read). Zero provider calls for OP-M3; record
                                      stays AMBIGUOUS. A swallowed provider exception is never
                                      proof of success.
PC reconciliation capability:         capability-driven (UNSUPPORTED / PARTIAL / SUPPORTED via
                                      AnkiBackend.reconciliationSupport()). PARTIAL proves the
                                      negative direction only; a positive observation under
                                      PARTIAL is demoted to Unresolved. Evidence must be backend-
                                      supplied and correlated to the ReviewCommitId
                                      (ReconcileCommitResult); no due/interval/reps inference.
Original backend unavailable:         transaction stays A-owned; Blocked route
                                      (backend_not_registered / unavailable); zero calls to B.
Backend identity mismatch:            sessionLockConsistent refuses before the ledger
                                      (session_backend_mismatch); FOREIGN_BACKEND never rewrites.
Collection mismatch:                  AUTHORITATIVE_MISMATCH ⇒ blocked
                                      (collection_mismatch); no replay, no reconcile against Y.
Unknown identity:                     IdentityUnverifiable(NOMINAL|DEGRADED); NOMINAL never equals
                                      NOMINAL-as-match; DEGRADED (recorded key, live key gone) fails
                                      closed (collection_identity_degraded). OP-M3 requires
                                      AuthoritativeMatch + authoritative support.
Ambiguous result:                     AMBIGUOUS preserved through restart, preference change,
                                      reconnect and session end; UI projection is
                                      VerificationRequired ("check Anki"), never a retry, never a
                                      false "saved"/"failed".
Legacy record:                        codec keeps the owner in the key; schemas 1–4 normalised
                                      without inventing ownership; unreadable bytes ⇒
                                      Health.Unavailable + empty plan (routes nothing, fabricates
                                      no owner); a dropped binding keeps the owner and stays
                                      unresolved.
```

## §8 File-by-file implementation report (artifact set)

All paths under `app/src/…/com/studyagent/client/`. **ACTION** is this session's action; the
implementation set itself was produced by the original 21H session and merged before this HEAD.

| FILE | ACTION (this session) | RESPONSIBILITY / IMPLEMENTED |
| --- | --- | --- |
| `core/anki/ReviewCommitRoutingPolicy.kt` | REUSED (verified) | Family facts, OP-M1/2/3/P classes, `route()` verdicts, `bindingCollectionKey()`, `recoveryPlan()`; pure, no I/O, no preference/AUTO |
| `core/anki/AnkiTransactionRecoveryRouting.kt` | REUSED (verified) | `BackendTransactionBlockReason` incl. `COLLECTION_IDENTITY_UNAVAILABLE`; degraded-key fails closed; per-owner-group planning |
| `core/anki/AnkiIdentityAssurance.kt` | REUSED (verified) | Locked four-category vocabulary: `AuthoritativeMatch` / `SessionContinuityVerified` / `IdentityUnverifiable(NOMINAL|DEGRADED)` / `AuthoritativeMismatch` |
| `core/anki/AnkiIdentityContinuation.kt` | REUSED (verified) | Locked §3.3 operation matrix (`continuationVerdict`) |
| `core/anki/ReviewCommit.kt` | NO_CHANGE | `ReviewCommitStatus` (5), `ReviewCommitResolution` (`REFUSED_BEFORE_DISPATCH`), record model |
| `core/anki/AnkiSessionContext.kt` | NO_CHANGE | `ReviewTurnId`, `ReviewCommitId` (identity) |
| `core/anki/ReviewCommitLedger.kt` | NO_CHANGE | Single ledger; prepare/claim/markMutationEntered/markResponseReceived/complete/markRefused/markNotCommitted/markAmbiguous/reconcile; codec v4 + legacy normalisation; load-time SUBMITTING→AMBIGUOUS heal |
| `core/anki/ReviewCommitTransition.kt` | NO_CHANGE | Closed transition table |
| `core/anki/ReviewCommitCoordinator.kt` | NO_CHANGE | Coordinator contract |
| `core/anki/ReviewCommitRecovery.kt` / `ReviewCommitRecoveryPolicy.kt` | NO_CHANGE | `nextCardAllowed ⟺ COMMITTED`; recovery decisions |
| `core/anki/ReviewCommitReconciliation.kt` | NO_CHANGE | Capability table: UNSUPPORTED ⇒ zero calls/Unresolved; PARTIAL positive ⇒ Unresolved; negative evidence ⇒ ConfirmedNotCommitted |
| `core/anki/CommitSemantics.kt` / `CommitFailureClassification.kt` | NO_CHANGE | Guarantee levels; `MutationBoundary` + `CommitFailureKind` classification |
| `core/anki/CommitFaultInjection.kt` | NO_CHANGE | `CommitFaultPoint` windows for deterministic crash injection |
| `core/study/AnkiStudyEffectExecutor.kt` | REUSED (verified) | `commitTransaction` pipeline; `commitRouting` seam; OP-M3 gate in `reconcileRecord`; `recoveryPlan()`/`restoreSession()`; `sessionBoundHere`; GATE 13 `findActiveForTurn` exclusion; `endReview` never erases transactions |
| `core/study/StudyReducer.kt` | NO_CHANGE | Dedup (`duplicate-anki-rating`, `anki-rating-locked-first-wins`), `stale-anki-commit-result`, `RetryRatingCommit` gate |
| `core/study/RatingCommitUiState.kt` / `RatingCommitRecoveryUi.kt` / `CommitTruthDiagnostics.kt` | NO_CHANGE | Honest projection (`VerificationRequired` ≠ retry ≠ "saved"); content-free diagnostics |
| `di/AppContainer.kt` | NO_CHANGE | One registry, one resolver, one `ReviewCommitLedger`; no mutation routing in Compose |
| `test/study/AnkiCommitHarness.kt`, `test/…/fake/FakeAnkiBackend.kt`, `test/…/fake/InMemoryReviewCommitStore.kt` | REUSED (verified) | A = AnkiDroid-like fake (no collection key, `AT_MOST_ONCE_FAIL_CLOSED`), B = PC-like keyed fake; both hold the same numeric card ids; counters: `physicalCommitCalls`, `reconcileCalls`, `nextCardCount`, `getDecksCalls`, `hydrateCalls` |
| `test/study/Gate21hReviewCommitRoutingTest.kt` | REUSED (re-executed: 21/21) | Routing/ownership/identity/duplicate/stale/legacy verifications |
| `test/study/Gate21hCommitEnduranceTest.kt` | REUSED (re-executed: 2/2) | 1 000 churn events; 900 injected crashes + 100 controls |
| `docs/GATE_21H_REVERIFICATION.md` | **CREATED** | This report |

### Checkpoint inventory (CHECKPOINT 21H-00 … 29)

| CP | Subject | Status | Evidence |
| --- | --- | --- | --- |
| 21H-00 | Call-graph discovery | REUSE | §2 matrix; `rg ReviewCommit|commitRating|ReviewTurn` |
| 21H-01 | Transaction ownership contract | NO_CHANGE (immutable by construction) | `ReviewCommitId`; tests ×4 (`Gate21h…` routing) |
| 21H-02 | StudySession ownership validation | REUSED | `sessionLockConsistent` + payload conflict + dedup |
| 21H-03 | Durable record compatibility | NO_CHANGE | codec v4 + schemas 1–4 normalisation; legacy tests |
| 21H-04 | Exact-backend routing | REUSED | `ReviewCommitRoutingPolicy.route` → `registry.find` |
| 21H-05 | Collection identity assurance | REUSED | four-category matrix + `continuationVerdict` |
| 21H-06 | Durable PREPARED boundary | REUSED | `ledger.prepare` before claim; `AnkiRatingCommitFlowTest.B` |
| 21H-07 | SUBMITTING/external-call boundary | REUSED | boundary callback; `ReviewCommitDurabilityOrderTest` |
| 21H-08 | Successful commit persistence | REUSED | `markResponseReceived`/`complete`; `unpersistedResponses` |
| 21H-09 | Failure classification | REUSED | `CommitFailureKind` + `MutationBoundary` |
| 21H-10 | RETRY_ALLOWED enforcement | REUSED | claim `allowRetry` + closed table + reducer gate |
| 21H-11 | AMBIGUOUS handling | REUSED | no blind retry / no alternate backend / no progression |
| 21H-12 | Reconciliation contract | REUSED | capability table; no heuristic inference |
| 21H-13 | Reconciliation backend binding | REUSED | OP-M3 gate before provider; original backend only |
| 21H-14 | Recovery after process death | REUSED | load-time heal + `recoveryPlan` per record owner |
| 21H-15 | Multi-backend recovery isolation | REUSED | per-group `Blocked`; VER-21H-23 |
| 21H-16 | Backend removed/reconfigured | REUSED | `backend_not_registered`; never re-homed |
| 21H-17 | Collection/profile change | REUSED | `collection_mismatch`; no replay/reconcile on Y |
| 21H-18 | Duplicate rating protection | REUSED | reducer + ledger claim (one mutation) |
| 21H-19 | Stale callback isolation | REUSED | `stale-anki-commit-result` + epoch correlation |
| 21H-20 | Next-card barrier | REUSED | `allowsNextCard()`; next-card failure retries the read |
| 21H-21 | Reviewer-action mutual exclusion | REUSED | `findActiveForTurn` + reverse rule in `ReviewerActionCoordinator` |
| 21H-22 | Backend reconnection | REUSED | reconnect ≠ replay authority; identity/collection checks first |
| 21H-23 | Error projection | REUSED | `RatingCommitUiState` (honest labels) |
| 21H-24 | Transaction diagnostics | REUSED | `CommitTruthSnapshot` (content-free) |
| 21H-25 | Legacy transaction recovery | REUSED | codec keeps owner; unprovable ⇒ blocked, kept |
| 21H-26 | Fake backend failure-injection | REUSED | `FakeAnkiBackend` ×2 identities + counters + `CommitFaultPoint` |
| 21H-27 | Persistence crash boundaries | REUSED | §6 table; crash sweep |
| 21H-28 | DI & routing integration | REUSED | single registry/resolver/ledger; defaulted `commitRouting` seam |
| 21H-29 | Minimal regression | REUSED | full suite 2645/2645 (§11) |

**Architecture boundary (all checkpoints):** immutable backend destination — YES; collection
context preserved — YES; GATE 11 ledger reused — YES (single ledger); no AUTO during recovery —
YES; no blind retry — YES; no cross-backend replay — YES; next-card barrier preserved — YES.

## §9 Architecture audit matrix (AUDIT-21H-01 … 24)

| Audit | Result | Evidence | Defect / Remediation |
| --- | --- | --- | --- |
| 01 Contract conformance (GATE 11 + 21B) | PASS | `ReviewCommitRoutingPolicy` implements the 21B identity matrix verbatim; 11B/11D tables intact | none |
| 02 Single ledger | PASS | `rg '^class ReviewCommitLedger'` → exactly one; one `ReviewCommitCoordinator`; no parallel state machine | none |
| 03 Immutable transaction backend | PASS | owner in `ReviewCommitId` + durable keys; no code path rewrites `backendId` | none |
| 04 Session/transaction consistency | PASS | `sessionLockConsistent` refuses mismatched effects before the ledger | none |
| 05 Exact registry lookup | PASS | `registry.find(ownerBackendId)` only; no kind-only lookup, no fallback | none |
| 06 AUTO isolation | PASS | routing policy: preference/resolver/AUTO appear only in comments (lines 10, 22, 116) | none |
| 07 No cross-backend replay | PASS | B counters stay 0 in every A-owned scenario (VER-01/05/14/24) | none |
| 08 Collection assurance | PASS | mismatch/degraded/unknown handled per locked policy; `isConnected` is never the sole gate | none |
| 09 Durable submission barrier | PASS | `prepare` (and claim/SUBMITTING) writes precede dispatch; write failure ⇒ zero calls | none |
| 10 Commit persistence barrier | PASS | `allowsNextCard()` requires durable `COMMITTED`; `committed_persistence_precedes_next_card` | none |
| 11 Ambiguous preservation | PASS | closed table; reconnect/preference/restart never downgrade `AMBIGUOUS` without evidence | none |
| 12 Retry evidence | PASS | `RETRY_ALLOWED` only from proven non-application; timeout ⇒ `AMBIGUOUS` (`OutcomeUnknown`) | none |
| 13 Reconciliation honesty | PASS | evidence is backend-supplied and transaction-correlated; PARTIAL positive demoted; no due/interval/reps inference | none |
| 14 Crash safety | PASS | sweep: every death at/after boundary ⇒ `AMBIGUOUS`; before ⇒ `PREPARED`/no row | none |
| 15 Duplicate suppression | PASS | `duplicate-anki-rating`, `anki-rating-locked-first-wins`, ledger in-flight answer, `commit_payload_conflict` | none |
| 16 Stale callback isolation | PASS | `stale-anki-commit-result` ×2 + epoch/commit-id correlation; old result may update A's record, never advances B's session | none |
| 17 Recovery isolation | PASS | `recoveryPlan` groups by persisted owner; per-group blocking (VER-23) | none |
| 18 Legacy record safety | PASS | unprovable ownership ⇒ blocked + preserved; unreadable ⇒ routes nothing (VER-32/33) | none |
| 19 Scheduler authority | PASS | no local FSRS/next-card scheduler; `Next` reads the locked backend | none |
| 20 Reviewer-action compatibility | PASS | `findActiveForTurn` blocks rating during action and vice versa (GATE 13 §23/§25) | none |
| 21 No offline queue regression | PASS | no deferred mutation replay engine; recovery is read/classify only | none |
| 22 Privacy & security | PASS | diagnostics content-free; no secrets/credentials in records or logs | none |
| 23 Source-of-truth separation | PASS | Session (21G) / Registry (21D) / Resolver (21E) / Ledger (11) / Backend — distinct roles | none |
| 24 Scope discipline | PASS | no 21I–21M work added; reviewer-action and note families untouched by this gate | none |

## §10 Verification matrix (VER-21H-01 … 36, sole authoritative plan)

All items executed in this environment (harness, §11) unless marked BLOCKED. Test classes:
`Gate21hReviewCommitRoutingTest` (21), `Gate21hCommitEnduranceTest` (2), `AnkiRatingCommitFlowTest`,
`Gate11bTransactionTest`, `Gate11dRecoveryAuditTest`, `Gate11eEnduranceTest`,
`ReviewCommitChaosTest`, `ReviewCommitDurabilityOrderTest`, `ReviewerActionCoordinatorTest`,
`Gate21gVerificationTest`, `Gate21cFormatCompatibilityTest`, `AnkiDroidRatingCommitTest`.

| Verification | Result | Evidence |
| --- | --- | --- |
| VER-21H-01 Normal rating on AnkiDroid | PASS | `AnkiRatingCommitFlowTest.A`; `AnkiDroidRatingCommitTest`; `Gate21h…a rating bound to A commits on A only` (A = `AnkiDroidLocal`) |
| VER-21H-02 Normal rating on PC | PASS | PC-bound harness (`backendId = PcAgent`, `PC_AGENT`) commits through PC in `Gate21h…` keyed tests, `ReviewCommitChaosTest` (150 seeds × 2 identities), `FakeCommitLaboratoryTest` |
| VER-21H-03 Preference change before rating | PASS | `…a preference change before the rating cannot move the session's destination` |
| VER-21H-04 AUTO change during rating | PASS | `…AUTO eligibility changing while a rating is pending leaves the owner unchanged` |
| VER-21H-05 Card-id collision | PASS | `…the same numeric card id on two backends never authorizes a B mutation` (both fakes hold cardId 123-class ids) |
| VER-21H-06 Session/transaction backend mismatch | PASS | `…a commit effect naming another backend than the session lock is refused before dispatch` (no durable intent created) |
| VER-21H-07 Collection mismatch | PASS | `…a collection that verifiably changed refuses the mutation before it is dispatched` (`collection_mismatch`, 0 calls) |
| VER-21H-08 Unknown collection assurance | PASS | `…evidence that degraded from a known collection blocks…`; `…an unknown collection on both sides permits a mutation and never proofs a reconciliation` (UNKNOWN ≠ MATCH; OP-M3 blocked) |
| VER-21H-09 Durable preparation failure | PASS | `ReviewCommitDurabilityOrderTest`; `AnkiRatingCommitFlowTest.O` (unreadable ledger fails closed); `PrepareResult.StoreFailed` ⇒ zero dispatch |
| VER-21H-10 SUBMITTING persistence failure | PASS | `ReviewCommitDurabilityOrderTest.a failed mutation-entry write means the provider is never touched` |
| VER-21H-11 Crash before external call | PASS | sweep `AFTER_PREPARED` ⇒ `PREPARED` (provably un-entered via boundary contract — the one evidence that establishes non-application); `AnkiRatingCommitFlowTest.B2/J2/L3` |
| VER-21H-12 Lost response after application | PASS | sweep `AFTER_PROVIDER_MUTATION`/`BEFORE_RESPONSE_PERSIST` ⇒ `AMBIGUOUS`; `L1 process death while SUBMITTING restores AMBIGUOUS and never calls the backend again` |
| VER-21H-13 Timeout after submission | PASS | `AnkiRatingCommitFlowTest.I` (exception after dispatch ⇒ AMBIGUOUS); `H5` (hung reconcile stays AMBIGUOUS); never `RETRY_ALLOWED` |
| VER-21H-14 Disconnect after submission | PASS | `Gate21h…a removed original backend blocks its records and never lends another backend`; B mutation counters = 0 |
| VER-21H-15 Backend success / persistence failure | PASS | `Gate11bTransactionTest.ledger_persistence_failure_after_backend_success_blocks_progression`; `unpersistedResponses` retried as a **write**; no duplicate, no progression |
| VER-21H-16 Durable COMMITTED | PASS | `Gate11bTransactionTest.committed_persistence_precedes_next_card`; `AnkiRatingCommitFlowTest.A` advances once |
| VER-21H-17 COMMITTED / next-card read failure | PASS | `AnkiRatingCommitFlowTest.M next card failure after COMMITTED never replays the rating`; `Gate21g…a committed rating is never resubmitted when the next card read fails` |
| VER-21H-18 AMBIGUOUS / preference change | PASS | `…a preference change cannot move an ambiguous transaction off its backend` |
| VER-21H-19 AMBIGUOUS / restart | PASS | `…restart preserves ambiguity, ownership and the blocked recovery plan` |
| VER-21H-20 RETRY_ALLOWED evidence | PASS | `Gate11dRecoveryAuditTest` (negative PARTIAL evidence ⇒ RETRY_ALLOWED); `AnkiRatingCommitFlowTest.F/G/H3`; timeout never yields it |
| VER-21H-21 Reconciliation supported | PASS | `Gate11dRecoveryAuditTest` ver5; `AnkiRatingCommitFlowTest.H2`; original backend only (`reconcileRecord` OP-M3 gate) |
| VER-21H-22 Reconciliation unsupported | PASS | `Gate11dRecoveryAuditTest` ver4 (UNSUPPORTED ⇒ zero calls, stays blocked); `H4`; `H2b` (AnkiDroid identity never consults reconciliation) |
| VER-21H-23 Multiple outstanding backends | PASS | `…one backend's outage never blocks another backend's recovery group`; `Gate21g…backend loss leaves the other ready backend untouched` |
| VER-21H-24 Original backend removed | PASS | `…a removed original backend blocks its records and never lends another backend`; `Gate21g…restoration never substitutes another ready backend` |
| VER-21H-25 Original backend reconnects | PASS | `…recovery is gated on the original backend returning with the same collection`; `Gate21g…same-backend reconnect follows continuity mismatch and unknown identity` |
| VER-21H-26 Different collection after reconnect | PASS | `…a collection that verifiably changed refuses…`; `…a record whose binding collection was dropped keeps its owner and stays unresolved` |
| VER-21H-27 Duplicate same rating | PASS | `…repeated and conflicting rating input produces exactly one mutation`; `Gate11bTransactionTest.duplicate_rating_events_create_one_commit_effect` |
| VER-21H-28 Conflicting ratings | PASS | `…conflicting_rating_events_accept_only_one_rating` (`anki-rating-locked-first-wins`); `G2 a different payload…refused before any dispatch` |
| VER-21H-29 Stale session callback | PASS | `…a stale commit result from an ended session cannot advance a new one`; `AnkiRatingCommitFlowTest.E/E2` |
| VER-21H-30 End session with pending commit | PASS | `…an unresolved transaction survives a session ending and keeps its owner`; `Gate21g…ending a session leaves an unresolved rating record intact` |
| VER-21H-31 Reviewer action conflict | PASS | `ReviewerActionCoordinatorTest.a rating transaction for the turn blocks a reviewer action` + `two different actions…`; `commitTransaction` `findActiveForTurn` refusal |
| VER-21H-32 Legacy record without backend ID | PASS | `…an unreadable ledger routes nothing and fabricates no owner`; codec keeps owner in key; unprovable ⇒ blocked, never assigned a current backend (`Gate21g…a legacy session without ownership is never guessed`) |
| VER-21H-33 Legacy record with valid identity | PASS | `ReviewCommitLedger` legacy normalisation (schemas 1–4, `SUBMITTING`+`PREPARED` phase reconciliation); re-executed inside `Gate11b*`/`Gate21cFormatCompatibilityTest` |
| VER-21H-34 1,000-event failure injection | PASS | `Gate21hCommitEnduranceTest`: 1 000 churn events (`physicalCommitCalls(A)=turns`, `B: commit/next/reconcile/decks/hydrate = 0`), 900 crashes + 100 controls. Required counters all 0: cross-backend rating calls, blind AMBIGUOUS replay, wrong-backend reconciliation, unjustified RETRY_ALLOWED, committed resubmissions, next-card-before-durable-commit, lost unresolved records, ownership rewrites |
| VER-21H-35 Real backend validation | **BLOCKED** | No Android device/AnkiDroid content provider and no PC Agent endpoint exist in this sandbox; per plan, reported BLOCKED rather than inventing evidence. Disposable-collection validation remains a device-side task |
| VER-21H-36 Previous gate regression | PASS* | Full suite `classes=218 tests=2645 passed=2645 failed=0`; focused `ReviewCommit|RatingCommit|Gate11|Gate21|Anki` `classes=118 tests=1526 passed=1526`. GATE 10–21G implemented tests all green. *Gradle/`connectedDebugAndroidTest` BLOCKED (§11); design-only features (GATE 20 custom study) are not runtime-tested and not claimed |

## §11 Build results

| Command | Result |
| --- | --- |
| `tools/jvm-harness/bin/bootstrap-sandbox.sh` | PASS (fresh bootstrap in this sandbox) |
| `tools/jvm-harness/bin/build.sh main` | PASS — `main errors: 0 (files: 328)` |
| `tools/jvm-harness/bin/build.sh compose` | PASS — `0 errors (files: 24, stub files: 46)` |
| `tools/jvm-harness/bin/build.sh test` | PASS — `test errors: 0 (files: 250)` |
| `tools/jvm-harness/bin/run.sh` (full suite) | PASS — `classes=218 tests=2645 passed=2645 failed=0 ignored=0 time=40.8s` |
| `run.sh 'ReviewCommit\|RatingCommit\|Gate11\|Gate21\|Anki'` | PASS — `classes=118 tests=1526 passed=1526 failed=0` |
| `run.sh 'Gate21h'` | PASS — `classes=2 tests=23 passed=23 failed=0` |
| `./gradlew testDebugUnitTest` | **BLOCKED** — exit 1; wrapper cannot download `gradle-8.7-bin.zip` (`SSLHandshakeException: Remote host terminated the handshake` — services.gradle.org unreachable in this sandbox) |
| `./gradlew lint` | **BLOCKED** — same wrapper failure |
| `./gradlew assembleDebug` / `assembleRelease` | **BLOCKED** — same wrapper failure |
| `./gradlew connectedDebugAndroidTest` | **BLOCKED** — no device/emulator, no Android SDK |

Harness results are equivalence evidence (Kotlin 2.3 K2 vs 1.9.24, coroutines 1.10.2, serialization
1.9.0, JUnit/DataStore shims; four test classes excluded per the harness README). No command is
reported PASS unless it ran here. Build success is not a substitute for the VER plan (§10).

## §12 Invariant report (INV-21H-01 … 30)

| INV | Statement | Result | Where enforced |
| --- | --- | --- | --- |
| 01 | Immutable original backend ownership per record | HELD | `ReviewCommitId` + durable keys |
| 02 | Destination is never current preference | HELD | routing policy has no preference read |
| 03 | Recovery never uses AUTO | HELD | `recoveryPlan` uses persisted owners only |
| 04 | Matching card IDs ≠ identity | HELD | identity triple; VER-05 |
| 05 | Collection uncertainty preserved | HELD | `IdentityUnverifiable` stays explicit |
| 06 | Verified mismatch blocks mutation | HELD | `collection_mismatch`; VER-07 |
| 07 | Durable preparation precedes submission | HELD | `prepare` write before claim |
| 08 | Durable submission intent precedes the call | HELD | `markMutationEntered` in boundary callback |
| 09 | SUBMITTING after interruption ≠ proof of non-application | HELD | load-time ⇒ `AMBIGUOUS` |
| 10 | Timeout ≠ safe retry | HELD | `OutcomeUnknown` ⇒ `AMBIGUOUS` |
| 11 | Lost response ≠ safe retry | HELD | VER-12 |
| 12 | AMBIGUOUS never blindly retried | HELD | reducer + closed table |
| 13 | RETRY_ALLOWED requires verified eligibility | HELD | contract evidence only; VER-20 |
| 14 | Local transaction ID ≠ backend idempotency | HELD | `CommitReceipt` requires backend id |
| 15 | COMMITTED durable before next-card | HELD | `allowsNextCard()` |
| 16 | Committed rating never resubmitted after a read failure | HELD | VER-17 |
| 17 | Reconciliation uses original backend only | HELD | OP-M3 gate; VER-21 |
| 18 | No fabricated reconciliation evidence | HELD | capability table; AUDIT-13 |
| 19 | Multi-backend records isolated | HELD | per-group plans; VER-23 |
| 20 | Backend removal rewrites no ownership | HELD | VER-24 |
| 21 | Historical missing ownership never guessed | HELD | VER-32 |
| 22 | Duplicate input ⇒ no uncontrolled duplicate commits | HELD | VER-27/28 |
| 23 | Old callbacks never advance new sessions | HELD | VER-29 |
| 24 | Session end never erases unresolved truth | HELD | VER-30 |
| 25 | GATE 11 is the sole ReviewCommit pipeline | HELD | single ledger/coordinator |
| 26 | GATE 13 mutual exclusion intact | HELD | VER-31 |
| 27 | Anki remains scheduling authority | HELD | no local scheduler |
| 28 | No offline blind replay mechanism | HELD | recovery is read/classify |
| 29 | No private AnkiDroid scheduler/DB access | HELD | public gateway APIs only |
| 30 | 21H cannot weaken GATE 10–21G invariants | HELD | regression 2645/2645 |

## §13 Residual risks

1. **Backend reconciliation unsupported (AnkiDroid):** ratings that die after the mutation boundary
   stay `AMBIGUOUS` until the user resolves them in Anki; at-most-once means a rare lost rating is
   preferable to a duplicate scheduler mutation. Accepted by `CommitSemantics.ANKIDROID`.
2. **Collection identity unavailable (AnkiDroid):** no collection key exists, so the collection
   dimension is `IdentityUnverifiable(NOMINAL)`; nothing blocks by absence, but `OP-M3` can never
   be authorized there (R-21C-04 carried).
3. **Historical records with missing backend identity:** codec keeps what it cannot prove and routes
   nothing; such rows may sit unresolved indefinitely.
4. **Long-term ambiguous ratings:** no TTL, no auto-expiry; resolution requires authoritative
   evidence or explicit user action.
5. **Sync/concurrent clients:** another Anki client can change scheduling state; reconciliation
   deliberately refuses to infer from it — which also means some applied ratings cannot be proven.
6. **Persistence corruption/failure:** fail-closed (`Health.Unavailable`, `IntegrityFailure`); a
   corrupt ledger blocks rather than guesses.
7. **Unverifiable application outcome:** the honest end-state of an ambiguous row is a blocked
   session turn, surfaced as `VerificationRequired`.
8. **Environmental (this run):** Gradle and device verification could not run (§11, VER-35); the
   lock carries the same limitation 21A–21G carried.

## §14 Handoff to GATE 21I (Reviewer Action Routing & Recovery)

Reusable ownership contracts (already consumed by 21I in-tree, which keeps its **own**
`ReviewerActionLedger` — the two ledgers are never combined):

```text
- Immutable BackendId in the durable identity (ReviewerActionId carries backendId/turn/action)
- Collection assurance validation via AnkiIdentityAssurancePolicy / AnkiIdentityContinuation
  (four-category vocabulary; operation classes OP-*)
- Exact backend lookup: AnkiBackendRegistry.find(backendId) only
- Original-backend-only recovery (AnkiTransactionRecoveryRouting; per-group blocking)
- Durable mutation-intent boundary (write-before-effect; boundary callback before the scheduler API)
- Ambiguous handling: no blind retry, no alternate backend, no false "applied/failed"
- Stale callback correlation (session/epoch/turn/transaction ids)
- Session/transaction identity separation (StudySessionBackendLock vs durable records)
```

## §15 Gate lock decision

```text
GATE 21H:                 LOCKED   (re-affirmed at da5214b)

TRANSACTION OWNERSHIP:    PASS
EXACT BACKEND ROUTING:    PASS
COLLECTION SAFETY:        PASS
DURABLE SUBMISSION:       PASS
AMBIGUOUS RECOVERY:       PASS
RETRY SAFETY:             PASS
NEXT-CARD BARRIER:        PASS
MULTI-BACKEND ISOLATION:  PASS
ARCHITECTURE AUDIT:       PASS   (24/24)
VERIFICATION:             PASS   (35/36 executed PASS; VER-21H-35 BLOCKED-environmental, reported)
BUILD:                    PASS   (available tasks; Gradle BLOCKED-environmental, reported)

NEXT AUTHORIZED SUB-GATE: 21I   (already present in-tree; not executed or modified by this session)
```

```text
GATE 21H — REVIEW COMMIT ROUTING LOCKED

RATING TRANSACTION:        ORIGINAL BACKEND ONLY
BACKEND OWNERSHIP:         DURABLY IMMUTABLE
AUTO RECOVERY ROUTING:     PROHIBITED
CROSS-BACKEND RATING REPLAY: PROHIBITED
AMBIGUOUS COMMIT:          NO BLIND RETRY
RETRY_ALLOWED:             EVIDENCE-BASED
NEXT CARD:                 AFTER DURABLE COMMITTED BARRIER
ANKI SCHEDULER:            AUTHORITATIVE
REVIEW COMMIT LEDGER:      SINGLE EXISTING OWNER
```
