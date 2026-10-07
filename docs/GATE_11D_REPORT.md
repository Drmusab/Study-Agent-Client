# GATE 11D — Real Recovery & Reconciliation Audit report

Session: `arena/37584f79-study-agent-client` · Branch base: `7eec42d4` · Date: 2026-10-07

> **Follow-up (session `arena/60ab94ba-study-agent-client`, 2026-10-07):** the locked GATE 11D
> transition model was re-audited against this implementation. The §2 recovery-event vocabulary,
> the §36/§37 pure closed `recoveryTransition` function, SUBMITTING-capable reconciliation (§23),
> the §39 recovery projection and the §40/§41 mandatory test names were added;
> `docs/GATE_11D_TRANSITION_AUDIT.md` is the authoritative conformance record (1434/1434 harness
> tests). The semantics below are unchanged.

## 1. Result

**GATE 11D: PASS (JVM level) — with two documented, fail-closed ceilings.**

Study-Agent can now:

1. restart from a durable ledger and restore PREPARED / SUBMITTING / COMMITTED / RETRY_ALLOWED / AMBIGUOUS into the correct recovery action (PART III VER 1–2);
2. reconcile an AMBIGUOUS transaction **read-only**, using the backend the transaction was locked into, gated by a three-state capability (SUPPORTED / PARTIAL / UNSUPPORTED) (VER 3–9);
3. refuse recovery of a structurally invalid ledger (two unresolved commits per session) instead of guessing (VER 10);
4. keep blocking scoped by backend and collection, keep the next-card barrier up for every non-COMMITTED state, and survive repeated restarts with zero duplicate mutation (VER 11, 13, 15, 16).

Ceilings (fail-closed by design, not defects):

- **AnkiDroid cannot prove either direction.** `reconciliationSupport() == UNSUPPORTED` (re-audited against pinned v2.24.1, §6). An AnkiDroid AMBIGUOUS therefore **stays blocked forever** unless the user ends the session; the client never demotes it to RETRY_ALLOWED on heuristics. A restored retry against AnkiDroid is refused **before the mutation boundary** (`session_invalid`) because the client-side session handle dies with the process — zero backend effects, by construction.
- **End-to-end exactly-once is NOT claimed** for AnkiDroid. The guarantee stays `AT_MOST_ONCE_FAIL_CLOSED` (GATE 11C), re-verified this gate.

## 2. Implementation summary (minimum infrastructure, no redesign)

New file:

- `core/anki/ReviewCommitReconciliation.kt` — `ReconciliationSupport` enum declared in capability order `{UNSUPPORTED, PARTIAL, SUPPORTED}` (ordinal grows with strength, so `min` = "capability can only shrink"); sealed `ReviewCommitReconciliationResult` (ConfirmedCommitted / ConfirmedNotCommitted / Unresolved — **no probabilistic states**); `ReviewCommitReconciler` interface (read-only contract); `AnkiReviewCommitReconciler`:
  - resolves the backend from `record.backendId` — the transaction's locked backend, never the current global preference (INV-11D-12);
  - verifies card/deck identity against that backend → `Unresolved(SessionInvalid)` on mismatch;
  - `effectiveSupport = frozen promise ∩ live capability`; UNSUPPORTED ⇒ **no provider call at all** (`Unresolved(authoritative_reconciliation_unsupported)`);
  - `withTimeoutOrNull` ⇒ `Unresolved(reconcile_timeout)` — a hung query is "still unknown", never "not applied";
  - PARTIAL demotes a positive `Applied` observation to `Unresolved(partial_reconciliation_cannot_confirm_commit)`; negative `NotApplied` is trusted;
  - contains no `Exception`/`Throwable` literals (AnkiDomainIsolation) — the `AnkiBackend` typed-result contract is relied upon, and the coordinator's guard (`AnkiStudyEffectExecutor.reconcileRecord`) converts a contract violation into `Unresolved(reconcile_threw)`, rethrowing `CancellationException`.

Extended (all additive, no redesign of frozen components):

- `core/anki/AnkiBackend.kt` — `reconciliationSupport()` (default derives from frozen `commitSemantics`), `ReconcileCommitRequest` mutation window fields;
- `data/anki/ankidroid/AnkiDroidBackend.kt` — `reconciliationSupport() = UNSUPPORTED` override with the audit reference;
- `core/anki/ReviewCommitLedger.kt` — `reconcile(commitId, result)` (AMBIGUOUS-only, single transition engine), codec invariant `MULTIPLE_UNRESOLVED_PER_SESSION` (decode refuses >1 unresolved per `(backend, session)`), `recoveryBlocker` scoped by backend + session + collection (PREPARED never blocks another session; AMBIGUOUS/SUBMITTING block the same collection), load-time SUBMITTING→AMBIGUOUS (`interrupted_after_mutation_entry`) and PREPARED claim release;
- `core/anki/ReviewCommitRecoveryPolicy.kt` — `IntegrityFailure(reason)` action; `classify(record, proof)` treats proof-for-non-AMBIGUOUS as an integrity failure;
- `core/study/AnkiStudyEffectExecutor.kt` — `reconcile()`/`reconcileRecord()` (capability-gated, read-only, content-free diagnostics `resultToken` + `latencyMs`), `recover()` maps an unreadable multi-unresolved ledger to `IntegrityFailure` instead of `Indeterminate`;
- `core/study/StudyReducer.kt` — `RecoveryBlocked` re-attaches the **interrupted turn by identity** (`restoredTurnFor`: same `ReviewTurnId`, `degradations = ["restored_from_ledger"]`, `ratingOptions = Unmapped`, never a scheduler query); restored PREPARED/RETRY_ALLOWED project to `RetryAvailable` (retry offered, never automatic, same `ReviewCommitId`); `RetryRatingCommit` accepts exactly `RETRY_ALLOWED` or restored-PREPARED with a matching turn; resolved-restore `movedRecovery` begins a **fresh read-only** scheduler session (original turn never resurrected);
- `core/study/RatingCommitRecoveryUi.kt`, `CommitTruthDiagnostics.kt`, `StudySessionMachine.kt` — restored-commit UX state and snapshot rows (backend identity, collection ref, reconciliation token + latency).

Test infrastructure: `AnkiCommitHarness.fullRestart()` (new ledger **and** dead fake session — real topology); `AnkiCommitHarness.reconcilable(commitSteps)` (PC backend identity without the AnkiDroid clamp); fake backend read-only evidence boundaries (unavailable → `Unavailable`, collection mismatch → `StillAmbiguous(collection_mismatch)`, missing card → `StillAmbiguous(card_not_found)`, truth-by-`ReviewCommitId` otherwise).

## 3. Recovery matrix (state → action → verified behaviour)

| Durable status after restart | Action | Machine projection | Verified in |
|---|---|---|---|
| PREPARED | OfferRetry (same commitId) | `RetryAvailable`, `canRetry`, restored turn, no scheduler query | VER 1a (real topology: retry refused `session_invalid` pre-boundary, 0 effects → RETRY_ALLOWED, canRetry stays true); VER 1b (surviving backend session: coordinator resumes, COMMITTED, 1 effect); policy table |
| SUBMITTING | Reconcile | `AMBIGUOUS` at ledger load (`interrupted_after_mutation_entry`) → `ReconciliationRequired`, `VerificationRequired`, `canRetry=false`, restored turn | VER 2 (auto-replay 0, no next card); VER 4 (AnkiDroid: reconcile → 0 backend calls, stays AMBIGUOUS, diagnostics `unresolved`) |
| COMMITTED | ResumeCommitted (no replay) | never blocks (same or new session); fresh `nextCard` legal | VER 1c (new session loads next card, `physicalCommitCalls` untouched, record stable at attemptCount=1) |
| RETRY_ALLOWED | OfferRetry (same commitId) | `RetryAvailable`, `canRetry`, restored turn, no scheduler query | Gate11bSourceOfTruthTest.retryable_ledger_restores_retryable_study_projection (updated to GATE 11D semantics); VER 13 (no Next effect) |
| AMBIGUOUS | Reconcile, else RemainBlocked | `ReconciliationRequired`, `VerificationRequired`, `canCheckAgain`, no retry | VER 2/3/4/5/6/7/8/9/14; VER 15 (exit preserves record; reopen still blocked); VER 16 (3 restarts, zero duplicate mutation) |
| (invalid) >1 unresolved per session | IntegrityFailure | begin → `Error(ANKI_COMMIT_INTEGRITY)`; `recover()` → `IntegrityFailure(multiple_unresolved_per_session)`; no next card, no mutation | VER 10 |

## 4. Reconciliation evidence ladder

| Backend answer | SUPPORTED | PARTIAL | UNSUPPORTED |
|---|---|---|---|
| `Applied` (commit-correlated receipt) | `ConfirmedCommitted` → COMMITTED (`reconciled_applied`) | demoted → `Unresolved` → stays AMBIGUOUS | provider never called |
| `NotApplied` | `ConfirmedNotCommitted` → RETRY_ALLOWED | trusted → RETRY_ALLOWED | provider never called |
| `StillAmbiguous` / `Unavailable` / timeout | `Unresolved` → stays AMBIGUOUS | `Unresolved` → stays AMBIGUOUS | `Unresolved(authoritative_reconciliation_unsupported)` |
| collection mismatch / missing card / identity mismatch | `Unresolved` — never a redirect, never `RETRY_ALLOWED` | same | same |

Verified: VER 3 (committed / not-committed / inconclusive), VER 4 (UNSUPPORTED: `reconcileCalls == 0`), VER 5 (PARTIAL demotion + trusted negative), VER 6 (external change unattributable), VER 7 (collection change), VER 8 (missing card), VER 9 (unavailable + hung-query timeout), VER 12 (read-only: zero mutations, zero scheduler queries, identity/rating/card untouched), VER 14 (repeated probe idempotent; resolves once when conclusive).

## 5. Verification summary

JVM harness (`tools/jvm-harness`, JDK 4py + Kotlin kernel, Android jar 34):

```
build.sh all   → main errors: 0 (199 files) · test errors: 0 (145 files)
run.sh (all)   → RESULT classes=125 tests=1409 passed=1409 failed=0
```

New this gate (28): `Gate11dRecoveryAuditTest` (24, VER 1–16 matrix) + `Gate11dAnkiDroidCapabilityTest` (4, PART IV capability report). Updated to GATE 11D restore semantics (4): `Gate11bSourceOfTruthTest.retryable_ledger_restores_retryable_study_projection` (now `RetryAvailable` + `canRetry` + restored turn), `AnkiRatingCommitFlowTest.K2`, `Gate11bRecoveryMatrixTest` (AMBIGUOUS-waiting + commit-truth snapshot rows 7→10 with the §35 fields).

Mapping to the requested PART III steps: VER 1 (restarts per status — all five), VER 2 (SUBMITTING→AMBIGUOUS, no auto-replay, nextCard=0), VER 3 (fake reconciliation committed / not-committed / unresolved), VER 4 (AnkiDroid UNSUPPORTED capability report), VER 5 (external activity), VER 6 (collection change), VER 7 (missing card), VER 8 (backend unavailable + timeout), VER 9 (multiple-unresolved integrity), VER 10 (source-of-truth conflict / scoping), VER 11 (read-only proof), VER 12 (next-card barrier all non-COMMITTED), VER 13 (repeated reconciliation), VER 14 (exit/reopen), VER 15 (restart endurance, zero duplicate mutation).

**BLOCKED (environment):** Gradle `clean/test/lint/assemble` (services.gradle.org unreachable in sandbox; Google Maven blocked — carried over from GATE 11C) and instrumented/device tests (no emulator). The JVM harness above is the evidence for this gate; on-device re-verification of the restored-retry path against a real AnkiDroid collection remains the documented follow-up.

## 6. PART IV — AnkiDroid limit questions (pinned v2.24.1, 9f579c10)

| # | Question | Answer |
|---|---|---|
| 1 | Can the provider be queried by `ReviewCommitId`? | **No.** `FlashcardsContract` exposes no commit-id column; the schedule URI accepts only `note_id`/`ord`/`answer_ease`/`time_taken`. |
| 2 | Does it deduplicate a repeated commit? | **No.** `CardContentProvider.update` (SCHEDULE branch) applies unconditionally; no dedup table exists. |
| 3 | Can it prove a commit **after** the response was lost? | **No.** `answerCard` swallows scheduler exceptions and still reports one updated row; a lost response is indistinguishable from a lost review. |
| 4 | Can it prove a **non**-commit? | **No.** There is no pre-write marker; "no change observed" ≠ "the write did not happen". |
| 5 | Can it distinguish external reviews (user reviewed in Anki itself)? | **No.** reps/interval/due/next-card/last-review-time are observable but cannot be attributed to a `ReviewCommitId`; they are diagnostic hints, never proof (INV-11D-13). |
| 6 | Does it expose a strong collection identity? | **Partially.** The collection is observable via the provider database, but no transaction-scoped identity survives the write — hence mismatch ⇒ `Unresolved`, never redirect. |
| 7 | Can a missing card be interpreted transactionally? | **No.** Card deletion proves nothing about whether the review applied (VER 8). |

Consequence, implemented and tested: `AnkiDroidBackend.reconciliationSupport() == UNSUPPORTED`; the reconciler issues **zero** provider calls for AnkiDroidLocal records (VER 4: `reconcileCalls == 0`); the guarantee stays `AT_MOST_ONCE_FAIL_CLOSED` (capability test asserts `commitSemantics == CommitSemantics.ANKIDROID`).

## 7. Invariant report (PASS = exercised by a test in §5)

| Invariant | Status | Evidence |
|---|---|---|
| INV-11D-01 recovery source order: Ledger → backend/collection validation → reconciliation → action → machine → UI | PASS | `StudyReducer.RecoveryBlocked` reads only the durable record; snapshot §35 rows name one owner per domain (Gate11bRecoveryMatrixTest) |
| INV-11D-02 no commit truth restored from UI / stale snapshot | PASS | Gate11bSourceOfTruthTest (projection contradiction recorded, ledger wins) |
| INV-11D-03 PREPARED → OfferRetry, same commitId | PASS | VER 1a/1b |
| INV-11D-04 SUBMITTING/AMBIGUOUS never expose a blind replay | PASS | VER 2/4 (`canRetry == false`); `RetryRatingCommit` guard rejects both |
| INV-11D-05 RETRY_ALLOWED → OfferRetry, same commitId | PASS | 11b source-of-truth (updated); VER 13 |
| INV-11D-06 retry only from proven-safe states, explicit user intent | PASS | reducer guard: `RETRY_ALLOWED` or restored-PREPARED, matching turn; no auto-retry anywhere |
| INV-11D-07 retry targets the same ReviewTurnId | PASS | `turn.turnId == commitId.turnId` guard + VER 1a/1b/3 assertions |
| INV-11D-09 reconciliation is read-only | PASS | VER 12 (zero mutations, zero scheduler queries, identity unchanged); VER 14 (idempotent) |
| INV-11D-12 backend from the transaction, never the global preference | PASS | `AnkiReviewCommitReconciler.reconcile(record.backendId)`; VER 11 scoping |
| INV-11D-13 collection mismatch ⇒ Unresolved, never redirect | PASS | VER 7 |
| INV-11D-14 only the record's own card is consulted | PASS | VER 8 (missing card); reconciler never queries "the current due card" |
| INV-11D-15 backend unavailable ⇒ Unresolved, never RETRY_ALLOWED | PASS | VER 9 (unavailable + timeout) |
| INV-11D-17 no next-card query from unresolved states | PASS | VER 2/13 (no `Next` effect, `nextCardCount` flat) |
| INV-11D-19 no probabilistic states; inconclusive is first-class | PASS | sealed `ReviewCommitReconciliationResult`; VER 3/6/14 |
| INV-11D-25 session exit during unresolved preserves the ledger record | PASS | VER 15 |
| INV-11D-33 abandonment never rewrites AMBIGUOUS→RETRY_ALLOWED | PASS | VER 15 (`abandonedAtEpochMs` semantics: status untouched) |
| >1 unresolved per session = integrity anomaly, fail closed | PASS | VER 10 (codec + health + begin + `recover()`) |
| backend/collection-scoped blocking | PASS | VER 11 (other backend & other collection never blocked; PREPARED never blocks another session) |
| repeated restarts ⇒ zero duplicate mutation | PASS | VER 16 (3 restarts, `physicalCommitCalls` stable at 1) |
| diagnostics content-free (id/status/backendId/collection availability/action/result+latency) | PASS | `CommitTruthSnapshot` §35 rows; `ReconciliationDiagnostics(resultToken, latencyMs)`; no card content anywhere in the recovery path |
| On-device: restored retry against a real AnkiDroid collection | **NOT YET TESTABLE** (no device in sandbox) | ceiling documented §1; expected outcome: offered → refused `session_invalid` → RETRY_ALLOWED, zero effects |
| Gradle `clean/test/lint/assemble` + instrumented suite | **NOT YET TESTABLE** (network-blocked sandbox) | harness evidence §5 |

## 8. PART VIII — the two questions

**(1) Can Study-Agent safely perform a real AnkiDroid review rating, survive interruption, avoid duplicate mutation, and resume only when truth permits?**

**YES — within the verified envelope.** Every interruption window tested (before intent, before the boundary, inside the mutation window, after the mutation, after the final write) leaves a durable record whose status alone selects the action; restarts never replay (zero duplicate mutation across repeated restarts, VER 16), resume is allowed only from a durable COMMITTED (VER 1c) or a proven-safe retry (VER 1/3), and everything else fails closed with the scheduler untouched.

**(2) Is unconditional end-to-end exactly-once proven?**

**NO** — and it must not be claimed. AnkiDroid's provider cannot answer questions 1–4 of §6, so a lost response after a real mutation stays AMBIGUOUS until a human verifies it in Anki. The honest guarantee is `AT_MOST_ONCE_FAIL_CLOSED`: at most one scheduler effect per `ReviewCommitId` (dedup + claim + boundary), with the crash-window tail resolved by read-only reconciliation where the backend can prove (PC backends: VER 3) or by the user where it cannot (AnkiDroid: VER 4).

## 9. Gate lock recommendation

Lock GATE 11D at this commit on the JVM evidence above. Carry-forward (non-blocking): on-device re-verification of the restored-retry ceiling against a real AnkiDroid collection; Gradle/instrumented run in a networked environment.

## 10. Reproduction

```bash
HARNESS_WORK=/tmp/h HARNESS_JAVA=/tmp/pp/jdk/jdk4py/java-runtime/bin/java \
HARNESS_KOTLIN_JARS=/tmp/pp/kk/run_kotlin_kernel/jars tools/jvm-harness/bin/build.sh all
(cd app && HARNESS_WORK=/tmp/h ... tools/jvm-harness/bin/run.sh)   # 1409/1409
```

## 11. Files touched

- New: `core/anki/ReviewCommitReconciliation.kt`; `app/src/test/.../study/Gate11dRecoveryAuditTest.kt`; `app/src/test/.../anki/ankidroid/Gate11dAnkiDroidCapabilityTest.kt`; this report.
- Modified: `core/anki/AnkiBackend.kt`, `core/anki/AnkiResults.kt`, `core/anki/ReviewCommitLedger.kt`, `core/anki/ReviewCommitTransition.kt`, `core/anki/ReviewCommitRecoveryPolicy.kt`, `data/anki/ankidroid/AnkiDroidBackend.kt`, `core/study/AnkiStudyEffectExecutor.kt`, `core/study/StudyReducer.kt`, `core/study/RatingCommitRecoveryUi.kt`, `core/study/CommitTruthDiagnostics.kt`, `core/study/StudySessionMachine.kt`; tests: `AnkiCommitHarness.kt`, `FakeAnkiBackend.kt`, `Gate11bSourceOfTruthTest.kt`, `Gate11bRecoveryMatrixTest.kt`, `AnkiRatingCommitFlowTest.kt`.
