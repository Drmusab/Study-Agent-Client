# GATE 11D — Recovery & Reconciliation Transition Audit

Session: `arena/60ab94ba-study-agent-client` · Base: `2ff1ced` (merge of the GATE 11D implementation, PR #41) · Date: 2026-10-07

## 1. Result

**GATE 11D transition-model audit: PASS — after closing 5 normative gaps.**

The GATE 11D specification ("Real Recovery & Reconciliation Audit", locked) was re-audited line by
line against the merged implementation. The durable status set, the reconciliation evidence ladder,
the restart semantics and the fail-closed ceilings of the merged GATE 11D work
(`docs/GATE_11D_REPORT.md`) were confirmed conformant. What the implementation still lacked were
the spec's own **normative artifacts for §2/§36/§37** — the canonical recovery-event vocabulary and
the one pure, closed `recoveryTransition(current, event)` function — plus the exact §40/§41
mandatory test names. All are now implemented, wired so the table is the single decision point for
every recovery status change, and pinned by an exhaustive 35-cell matrix test.

## 2. Gaps found and closed

| # | Spec | Gap found | Fix |
|---|---|---|---|
| 1 | §2 | No `ReviewCommitRecoveryEvent` type existed — recovery events were implicit in call sites | New `core/anki/ReviewCommitRecovery.kt`: `ReviewCommitRecoveryEvent` with exactly the seven canonical events (`RecoveryDetected`, `RetryRequested`, `ReconciliationStarted`, `ReconciliationConfirmedCommitted`, `ReconciliationConfirmedNotCommitted`, `ReconciliationUnresolved`, `IntegrityViolationDetected`) |
| 2 | §36/§37 | No pure `recoveryTransition(current, event)`; status decisions were spread between the ledger's load-time recovery, `ReviewCommitRecoveryPolicy` and the command engine | `recoveryTransition(current, event): ReviewCommitStatus?` — the closed 5×7 table, `null` = invalid pair. Every recovery-originated command is now routed through it (see §3 below) |
| 3 | §23/§37 | The reconciliation commands were AMBIGUOUS-only; the spec's exact reconciliation mapping resolves **SUBMITTING** as well (§7: reconciliation is the *only* legal recovery action for SUBMITTING, and §23 defines its three outcomes) | `ReconciliationConfirmedCommitted` / `ConfirmedNotCommitted` / `Inconclusive` now accept SUBMITTING, with the target status **computed from the closed table**; `ledger.reconcile` accepts SUBMITTING and AMBIGUOUS; `ReviewCommitRecoveryPolicy.classify` maps proof for SUBMITTING per §23 (proof for PREPARED/RETRY_ALLOWED/COMMITTED stays `IntegrityFailure`, §32) |
| 4 | §36 | Ledger **load-time** recovery issued a pipeline command (`BackendOutcomeUnknown`) for the SUBMITTING→AMBIGUOUS normalization — recovery decision living in startup code via pipeline vocabulary | Load-time recovery now issues the recovery command `ReconciliationInconclusive("interrupted_after_mutation_entry", PROCESS_RESTART_WHILE_SUBMITTING)`; startup code expresses recovery events, never pipeline commands. Record shape on disk is unchanged |
| 5 | §39/§40/§41 | The §39 recovery projection existed only as inline branches; the 20 mandatory test names of §40/§41 did not exist | Pure `ReviewCommitStatus.recoveryUiProjection(...)` (§39) wired into `RatingCommitRecoveryUi`; new `Gate11dRecoveryTransitionMatrixTest` (25 tests) covers every §40 illegal and §41 legal transition by exact name plus the exhaustive table |

In addition, `nextCardAllowed(status) = (status == COMMITTED)` (§25) is now a formal function in
`core/anki` next to the table, tested for all five statuses (the reducer/executor barrier itself was
already COMMITTED-only and stays pinned by the GATE 11B architecture tests).

## 3. How the one table is enforced

`ReviewCommitTransition.recoveryEventOrNull()` maps each recovery command to its canonical event
(`BeginRetry`→`RetryRequested`, the three reconciliation commands→their events); pipeline and
metadata commands map to `null`. `ReviewCommitTransitions.transition` then:

1. computes `recoveryTransition(record.status, event)` and **rejects** the command before any
   durable write when the pair is invalid;
2. applies the command and **rejects** the result unless its status equals the table's target.

So the appliers cannot drift from the table: they *derive* their target status from
`recoveryTransition`, and the engine cross-checks the outcome. The reconciliation appliers keep
their receipt/failure/phase decoration; the status decision is the table's. The engine remains
stricter than the table where GATE 11B froze it (COMMITTED is terminal for every command, and
`BeginRetry` is accepted only from RETRY_ALLOWED — the table's `PREPARED + RetryRequested →
PREPARED` permission is exercised by `claim()` on a PREPARED record, which needs no status write).

## 4. Section-by-section conformance

| Spec | Status | Evidence |
|---|---|---|
| §1 canonical five statuses, no `NOT_STARTED`/`FAILED`/`CANCELLED`/`UNKNOWN` | conformant (pre-existing) | `ReviewCommitStatus` (INV-11B-01/05); vocabulary-lock test |
| §2 canonical recovery events | **closed this session** | `ReviewCommitRecoveryEvent` (7 events, spec names in KDoc) |
| §3 exact transition graph | conformant | `recoveryTransition` + exhaustive matrix test |
| §4/§12/§13 `PREPARED`/`RETRY_ALLOWED` retry reuses the same identity, frozen rating | conformant (pre-existing) | `sameIdentity` guard; `retry_allowed_retry_request_becomes_prepared` asserts identity + `attemptCount += 1` |
| §5 `PREPARED → SUBMITTING` belongs to the pipeline, only after integrity/backend/card/attempt validation and durable SUBMITTING | conformant (pre-existing) | executor commit flow: ledger replay → backend resolve → `prepareCommit` preflight → durable claim → `markMutationEntered` (NonCancellable) → mutation |
| §6 `PREPARED` forbidden recovery transitions | conformant | table: only `RecoveryDetected`/`RetryRequested`/`IntegrityViolation` keep PREPARED; everything else `null`. The two non-restart paths to RETRY_ALLOWED/AMBIGUOUS from PREPARED (`markRefused`, `markBoundaryViolation`) are backed by a runtime backend operation (§6's exception: the backend certified no dispatch / violated its boundary contract), never by restart alone |
| §7–§11 `SUBMITTING` recovery: reconcile-only, never retry, never back to PREPARED | conformant | table rows; `submitting_cannot_retry_directly`, `submitting_cannot_return_to_prepared`; `claim()` on SUBMITTING → `InFlight` |
| §8–§11 `SUBMITTING` reconciliation mapping | **extended this session** (gap 3) | `submitting_reconciled_committed_becomes_committed`, `submitting_reconciled_not_committed_becomes_retry_allowed`, `submitting_unresolved_becomes_ambiguous` — engine and live-ledger level |
| §14/§24 `RETRY_ALLOWED` only retried through `PREPARED` | conformant (pre-existing) | `claim(allowRetry=true)` → durable `BeginRetry` → claim; `retry_allowed_cannot_become_submitting_directly` |
| §15–§19 `AMBIGUOUS` recovery: reconcile-only | conformant (pre-existing) | `ambiguous_cannot_retry_directly`, `ambiguous_cannot_become_prepared`; ledger claim refuses AMBIGUOUS |
| §20/§21 `COMMITTED` terminal | conformant (pre-existing) | engine rejects every command on COMMITTED (`COMMITTED_TERMINAL`); `committed_cannot_leave_committed`, `committed_remains_committed_after_restart` |
| §22 no RECONCILING/VERIFYING/RECOVERING durable status | conformant (pre-existing) | operational `commit.reconciling` flag + `RatingCommitUiState.VerificationRequired`; durable status stays SUBMITTING/AMBIGUOUS |
| §23 exact reconciliation mapping | **extended this session** | see gap 3; mapping verified for both SUBMITTING and AMBIGUOUS × three evidence kinds |
| §25 next-card = COMMITTED-only | **formalized this session** | `nextCardAllowed`; reducer/executor barrier pre-existing (VER 12, architecture test) |
| §26 restart never rewrites truth by itself | conformant | `restart_does_not_change_status_by_itself` (PREPARED/RETRY_ALLOWED/AMBIGUOUS/COMMITTED byte-stable across a real store restart; SUBMITTING→AMBIGUOUS only via the §28 recovery classification, never to PREPARED/RETRY_ALLOWED) |
| §27/§28 recovered SUBMITTING → AMBIGUOUS (project convention) | conformant (pre-existing), now expressed in recovery vocabulary | load-time `ReconciliationInconclusive(...)` through the closed table; write-back is durable or the ledger fails closed (`recovery_write_failed` → unavailable) |
| §29 collection mismatch / §30 missing card / §31 external scheduler change | conformant (pre-existing) | reconciler → `Unresolved(...)`, never redirect; `scheduler_change_cannot_change_commit_status` at the policy/status level; VER 6–8 end-to-end |
| §32 integrity failure never rewrites status | conformant (pre-existing + test) | `integrity_failure_does_not_rewrite_status`: proof for non-reconcilable statuses → `IntegrityFailure`, record preserved; `IntegrityViolationDetected` → current status for all five |
| §33 no `ERROR` status; §34 storage failure during transition keeps the old durable status | conformant (pre-existing) | ledger keeps last durable snapshot on store failure; a backend success is never exposed before it is durable |
| §35 transition completes only on durable persistence | conformant (pre-existing) | `persistRecordLocked` stamps `version` and swaps the map only after the store write succeeds |
| §36/§37 one pure closed transition function | **closed this session** | `recoveryTransition` + enforcement wiring (§3 above) + 35-cell exhaustive test |
| §38 recovery action derived from status | conformant (pre-existing) | `ReviewCommitRecoveryPolicy.classifyStatus` (AMBIGUOUS → `Reconcile`, `RemainBlocked` after an unresolved probe) — tested in the matrix class |
| §39 UI projection derived from durable status | **formalized this session** | `recoveryUiProjection`; `RatingCommitRecoveryUi` recovery branches now derive through it; `ui_projection_cannot_change_durable_status` |
| §40/§41 mandatory tests | **closed this session** | `Gate11dRecoveryTransitionMatrixTest`, all 20 exact names |

## 5. Documented interpretation decisions (locked with this audit)

1. **§39 is the recovery projection.** The table `PREPARED → RetryAvailable, SUBMITTING →
   VerificationRequired` governs what recovery surfaces for a restored/recovered transaction. The
   live pipeline projection (GATE 11B §14, `PREPARED/SUBMITTING → Saving`) is unchanged: while a
   submission the user just started is in flight, PREPARED/RETRY… show `Saving`. Both are pure
   functions; neither can cause a reverse transition. (`RatingCommitRecoveryUi` previously showed
   `VerificationRequired` for a restored PREPARED that hit a persistence failure — now
   `RetryAvailable`, exactly per §39.)
2. **§13's `attemptCount += 1` lands on the durable attempt claim**, not on `BeginRetry` itself
   (`RETRY_ALLOWED → PREPARED → claim`). One retry = exactly one new counted attempt; counting it at
   `BeginRetry` *and* at the claim would count it twice. The mandatory test asserts
   `attemptCount` grows by exactly one across the full retry sequence, with identity and rating
   frozen.
3. **§6's "unless a separate runtime operation first crosses the proper mutation/reconciliation
   path"** is what licenses `markRefused` (PREPARED → RETRY_ALLOWED on a backend-certified
   no-dispatch) and `markBoundaryViolation` (PREPARED → AMBIGUOUS when the backend reports an
   unknown outcome despite no boundary callback). Both are runtime backend operations with
   evidence — never restart shortcuts.
4. **Live vs recovered SUBMITTING.** Per §28 the project convention: a *recovered* SUBMITTING that
   cannot be immediately authoritatively classified becomes AMBIGUOUS at load, before any
   reconciliation runs. A *live* SUBMITTING (mutation boundary durably entered, process still
   alive) is reconciled per §7/§23 — status remains SUBMITTING while the probe runs, then the
   evidence decides. Both paths go through the same closed table.
5. **COMMITTED + recovery events** (§37's "`COMMITTED` / reject mutating transition"): events that
   would rewrite truth (`RetryRequested`, `ReconciliationConfirmedNotCommitted`) are rejected;
   the rest re-state COMMITTED without mutation. The durable engine additionally refuses *every*
   command on COMMITTED except metadata (`Acknowledge`, `NoteAbandoned`) — stricter than the
   table, as §21 requires.

## 6. GATE 11D §44 checklist

```text
[x] every recovery status transition is defined            — closed 5×7 table, exhaustive test
[x] every unspecified transition is rejected               — table returns null; engine rejects before write
[x] transition completion requires durable persistence     — §35 (versioned store write; ledger invariant)
[x] restart alone never rewrites transaction truth         — §26 test; SUBMITTING via §28 classification only
[x] recovered SUBMITTING is reconciled or AMBIGUOUS        — load-time normalization (durable write or fail-closed)
[x] AMBIGUOUS cannot retry directly                        — table null + claim refuses + engine rejects
[x] RETRY_ALLOWED retries through PREPARED                 — durable BeginRetry before each claim
[x] COMMITTED remains terminal                             — engine + table; no event leaves COMMITTED
[x] nextCard remains COMMITTED-only                        — nextCardAllowed + reducer/executor barrier
[x] UI/recovery actions do not become transaction truth    — projections pure; acknowledge/abandon metadata-only
[x] tests cover the full legal and illegal transition matrix — 35-cell exhaustive + §40 (12) + §41 (8) by exact name
```

## 7. Verification

JVM harness (Gradle remains network-blocked in this sandbox; see GATE 11D report §5):

```text
build.sh all   → main errors: 0 (200 files) · test errors: 0 (146 files)
run.sh (all)   → RESULT classes=126 tests=1434 passed=1434 failed=0
```

New tests (25) in `app/src/test/java/com/studyagent/client/anki/Gate11dRecoveryTransitionMatrixTest.kt`:

- the exhaustive closed table (all 35 `(status, event)` pairs, re-stated from §37);
- the command→event vocabulary mapping;
- §40 by exact name (12): `prepared_cannot_become_committed_without_backend_or_reconciliation`,
  `prepared_cannot_become_ambiguous_on_restart_alone`, `submitting_cannot_retry_directly`,
  `submitting_cannot_return_to_prepared`, `retry_allowed_cannot_become_submitting_directly`,
  `ambiguous_cannot_retry_directly`, `ambiguous_cannot_become_prepared`,
  `committed_cannot_leave_committed`, `restart_does_not_change_status_by_itself`,
  `ui_projection_cannot_change_durable_status`, `scheduler_change_cannot_change_commit_status`,
  `integrity_failure_does_not_rewrite_status`;
- §41 by exact name (8): `retry_allowed_retry_request_becomes_prepared`,
  `submitting_reconciled_committed_becomes_committed`,
  `submitting_reconciled_not_committed_becomes_retry_allowed`,
  `submitting_unresolved_becomes_ambiguous`, `ambiguous_reconciled_committed_becomes_committed`,
  `ambiguous_reconciled_not_committed_becomes_retry_allowed`,
  `ambiguous_unresolved_remains_ambiguous`, `committed_remains_committed_after_restart`;
- §25/§38/§39 derived rules (3): next-card, recovery action, recovery UI projection.

No pre-existing test changed. The 1409 tests of the merged GATE 11D state pass unmodified.

## 8. Files touched

- New: `core/anki/ReviewCommitRecovery.kt` (events + `recoveryTransition` + `nextCardAllowed` +
  `recoveryEventOrNull`); `app/src/test/.../anki/Gate11dRecoveryTransitionMatrixTest.kt`; this report.
- Modified: `core/anki/ReviewCommitTransition.kt` (table-driven reconciliation appliers, recovery
  cross-check, `ReconciliationInconclusive.resolution`), `core/anki/ReviewCommitLedger.kt`
  (load-time recovery vocabulary, SUBMITTING-capable `reconcile`), `core/anki/ReviewCommitRecoveryPolicy.kt`
  (§23 proof mapping for SUBMITTING), `core/study/RatingCommitUiState.kt` (`recoveryUiProjection`),
  `core/study/RatingCommitRecoveryUi.kt` (derive through the projection).

## 9. Reproduction

```bash
HARNESS_WORK=/tmp/h HARNESS_JAVA=/tmp/pp/jdk/jdk4py/java-runtime/bin/java \
HARNESS_KOTLIN_JARS=/tmp/pp/kk/run_kotlin_kernel/jars tools/jvm-harness/bin/build.sh all
HARNESS_WORK=/tmp/h HARNESS_JAVA=/tmp/pp/jdk/jdk4py/java-runtime/bin/java \
HARNESS_KOTLIN_JARS=/tmp/pp/kk/run_kotlin_kernel/jars tools/jvm-harness/bin/run.sh
# subset: ... run.sh Gate11dRecoveryTransitionMatrixTest
```

## 10. Gate lock recommendation

Lock the GATE 11D transition model at this commit. The ceilings documented in
`docs/GATE_11D_REPORT.md` (AnkiDroid reconciliation UNSUPPORTED, `AT_MOST_ONCE_FAIL_CLOSED`,
on-device re-verification, Gradle in a networked environment) are unchanged and remain the
non-blocking carry-forward.
