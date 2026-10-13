# GATE 21J — Note Editing Backend Binding (Implementation and Verification Report)

Status of this document: **implementation report, harness-verified only.** No Gradle, Android SDK, device, real AnkiDroid, or real PC backend was available. Every result below comes from the JVM harness (`tools/jvm-harness`), which the harness README describes as evidence, not proof.

GATE 22 and GATE 21K were not started. Nothing in this change creates notes, migrates drafts, adds a settings control, or adds a backend-switch affordance.

---

## §1 Gate result

| Item | Result |
|---|---|
| Owner binding of a note edit (save, retry, recovery, reconciliation) | **Implemented.** Each touch goes to the record's own backend instance, resolved from the `AnkiBackendRegistry` by `backendId`. There is no fallback, AUTO, or preference lookup. |
| Reuse of GATE 17 ledger, status machine, and coordinator | **Reused.** No new ledger, coordinator, replay queue, or note database. Status vocabulary unchanged (PREPARED, SUBMITTING, APPLIED, RETRY_ALLOWED, AMBIGUOUS, CONFLICT). |
| Collection context on the record | **Implemented** as an optional `collectionRef` field (default `null`, validated against `backendId`). |
| `EditNoteViewModel` backend | **Immutable.** `backend` is a `val`. The `switchBackend` function was removed (it had no production callers). |
| Harness verification | **Passed.** Build: main 0 errors, compose 0, test 0. Tests: **2625 / 2625** (baseline 2584, plus 41 new). |
| Device or real-backend verification | **Not performed.** Out of reach in this sandbox. |
| Audit-pending decision | **DECISION-21J-03** deviates from the locked §3.3 cell for `OP-M3`. It needs audit approval (§5). |

**Gate lock:** IMPLEMENTED and HARNESS-VERIFIED, pending audit approval of DECISION-21J-03. Not device-verified.

---

## §2 Scope and what was not changed

- Owner resolution for note editing is by `record.backendId` (or `base.backendId` for a new save) against the registry. The coordinator no longer holds a pinned instance (`NoteMutationCoordinator.kt`, `DefaultNoteMutationCoordinator(backends: AnkiBackendRegistry, …)`).
- `AppContainer` wires `backends = ankiBackendRegistry`. The `ankiDroidBackend` reference that remains in the same `lazy` block feeds only the safety policy (`unresolvedStudyTurnCards`), not the coordinator's backend.
- The GATE 17 ledger (`NoteMutationLedger`) is reused unchanged except for the additive `collectionRef` field on `NoteMutationRecord`.
- The coordinator's refusal vocabulary gains one typed refusal, `BackendRouteBlocked(token)`. `BackendMismatch` is kept.
- `NoteEditValidationError` is unchanged. The existing `DeckTargetWrongBackend` is the foreign-deck guard (§6.3).

---

## §3 Files changed

| File | Change |
|---|---|
| `core/anki/edit/NoteMutationRoutingPolicy.kt` | **New.** Pure routing policy for the note family (`admit`, `route`, `mutationOperationClass`). |
| `core/anki/edit/NoteMutationRecord.kt` | `collectionRef: AnkiCollectionIdentity? = null`, validated against `backendId`. Factory takes it too. |
| `core/anki/edit/NoteMutationCoordinator.kt` | Registry-based. Every backend touch goes through `routing.admit` or `routeFor(record)`. Blocked routes return `BackendRouteBlocked` before any transition or backend call. Reconciliation (`OP-M3`) and evidence reads (`OP-R2`) go through the owner route. `checkDeckTarget` filters by `backend.id` for both target and source. `collectionRef` is set at PREPARED from the owner's `currentCollectionIdentity`. |
| `ui/screens/editnote/EditNoteViewModel.kt` | `backend` is a `val`. `switchBackend` deleted. |
| `ui/screens/editnote/EditNoteMapper.kt` | Projects `BackendRouteBlocked` into a refusal message. |
| `di/AppContainer.kt` | Passes `backends = ankiBackendRegistry`. |
| `app/src/test/.../anki/edit/FakeNoteMutationBackend.kt` | `collectionIdentity` var and override. |
| `app/src/test/.../anki/edit/NoteMutationCoordinatorTest.kt` | Registry harness. `backendMismatchIsRefused` now expects `BackendRouteBlocked("backend_not_registered")` and asserts no apply call. |
| `app/src/test/.../ui/EditNoteViewModelTest.kt` | Registry constructor. |
| `app/src/test/.../anki/edit/NoteMutationRoutingPolicyTest.kt` | **New.** 12 policy tests. |
| `app/src/test/.../anki/edit/Gate21jNoteEditOwnershipTest.kt` | **New.** 15 two-backend coordinator tests. |
| `app/src/test/.../anki/Gate21jArchitectureAuditTest.kt` | **New.** 14 source audit tests. |

---

## §4 Routing design (as implemented)

### §4.1 Admission and operation classes

- **Admission (`OP-M1`)**: a new save. Only registry existence is checked. The collection observed now becomes the record's `collectionRef`.
- **`OP-M1` on a record**: `PREPARED` with zero attempts.
- **`OP-M2`**: any other pre-write entry (retry, resume, or a repeat after a proven refusal).
- **`OP-M3`**: reconciliation of an `AMBIGUOUS` record.
- **`OP-R2`**: owner re-check reads that precede showing state (`stateEvidence`).
- `SUBMITTING → AMBIGUOUS` normalization is ledger-only, with no backend call.

### §4.2 Verdict order

1. **Registry existence first.** Missing owner → `Blocked("backend_not_registered")`. No record is read or touched.
2. **Identity check** via `AnkiIdentityAssurancePolicy.check`. Bound = `record.backendId` plus `record.collectionRef?.collectionKey`. Live = `backend.id` plus `backend.currentCollectionIdentity?.collectionKey`. `boundIsLiveInThisProcess` is true only when the in-process pending payload exists.
3. **Per-operation cell.** See §4.3.

### §4.3 Cells actually enforced

| Assurance (bound vs live) | `OP-M1` | `OP-M2` | `OP-M3` (DECISION-21J-03) | `OP-R2` |
|---|---|---|---|---|
| Authoritative match | route | route | route | route (locked matrix) |
| Authoritative mismatch | block `collection_mismatch` | block `collection_mismatch` | block `collection_mismatch` | locked matrix |
| Key appeared where none bound, or key disappeared (DEGRADED) | block `identity_unverifiable_degraded` | block `identity_unverifiable_degraded` | block `identity_unverifiable_degraded` | locked matrix |
| Both unknown, nominal | route | route (DECISION-21J-02) | **route** (DECISION-21J-03) | locked matrix |
| Session continuity (in-process payload live) | route | route (DECISION-21J-02) | **route** (DECISION-21J-03) | locked matrix |

A `Blocked` route never reaches the backend. Recovery returns `StillAmbiguous` with `Unverifiable(token)` and makes no read. Retry and resume refuse before any ledger transition. Pre-write refuses before any backend call.

### §4.4 Decisions recorded

- **DECISION-21J-01 — registry routing by record identity.** Adopted the approach the locked contract named as the required change (`GATE_21B_CONTRACT_LOCK.md` 21J, R9). The pinned instance is removed.
- **DECISION-21J-02 — proof of non-application.** `OP-M2` is permitted under nominal or continuity evidence. The GATE 17 pre-write refusal (`NoteMutationBackendResult.ConfirmedNotApplied`) is made by the backend before its single provider write, and does not rest on collection identity. This is an explicit family decision, stated in the policy's documentation.
- **DECISION-21J-03 — reconciliation cell (deviation, needs audit approval).** The generic matrix blocks `OP-M3` on nominal and continuity evidence. This is in `AnkiIdentityContinuation.kt` around line 210 (`AnkiUnverifiabilityQualifier.NOMINAL → AnkiOperationClass.OP_M3 → blocked(RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH)`). Applying that block to the note family would make the GATE 17 reconciliation path unreachable for an AnkiDroid record (no collection key), because that path reads instance-bound state. This family therefore blocks `OP-M3` only on a verified mismatch or a degraded key, and otherwise lets the owner's own read decide. The coordinator never applies a status on continuity alone. **Inconsistency to resolve:** `DECISION-21I-03` (`GATE_21I_REVIEWER_ACTION_ROUTING.md:83`) says NOMINAL-`OP-M3` is **permitted**, but the 21C matrix code blocks it (`AnkiIdentityContinuation.kt:210`). The 21J cell follows the 21I *text* and records the 21C *code* as the deviation. The audit should decide which is the locked truth.
- **DECISION-21J-04 — `switchBackend` deleted.** It was dead code (no production callers) and a mutable backend setter that contradicts immutable ownership. Audit tokens already forbid it. "Open this note on another backend" is not offered: that would be a new context (`GATE_21B_CONTRACT_LOCK.md`, 21J decisions), which this gate does not build.
- **DECISION-21J-05 — collection context is optional and null means unknown.** `collectionRef` is `null` for AnkiDroid (no authoritative discriminator). Null is never a match. This is the honest reading of the identity contract.

---

## §5 Durable state and codec

- `NoteMutationRecord.collectionRef` is an additive, optional field with a `null` default. It is `@Serializable` through `AnkiCollectionIdentity`. The record's `init` requires `collectionRef.backendId == backendId`.
- `NoteMutationCodec.VERSION` is still `1`. The codec is `encodeDefaults = true`, so every newly written record carries `"collectionRef":null` (or a value). `ignoreUnknownKeys = false`.
- **Forward compatibility (new snapshots read by old code):** works for records written before 21J, since their key is absent and the field defaults. It fails for any record written by 21J, because old code rejects the unknown key. See §8.1.
- **Legacy records** (key absent) decode with `collectionRef = null`. Tested by stripping `,"collectionRef":null` from a real snapshot and recovering the record on its own owner (§7).

---

## §6 Refusal, deck, and conflict behaviour

### §6.1 Refusals

`NoteMutationRefusal.BackendRouteBlocked(token)` is returned for a blocked route, before any transition or backend call. The token is a stable, content-free name (`backend_not_registered`, `collection_mismatch`, `identity_unverifiable_degraded`). The mapper shows it as a refusal, not as a silent failure. `BackendMismatch` is still returned by `save` when `base.backendId != backend.id` and by `startAfterConflict` when a superseding base belongs to another backend.

### §6.2 Conflict

A conflict is evidence-based: a pre-write read that differs from the base (`CONFLICT_BEFORE_WRITE`) or a backend-reported conflict. It stays distinct from `AMBIGUOUS`. A `CONFLICT` record can be superseded only by a save on its own owner (tested in §7).

### §6.3 Deck targets: the unresolved item from the previous session

The earlier concern was that `NoteEditPlanner.buildPatch` silently drops a `targetDeck` whose backend differs from the base. **Checked, and no code change was needed for the save path.**

- `NoteEditPlanner.validateDraft` (`NoteEditPlanner.kt:43–46`) adds `NoteEditValidationError.DeckTargetWrongBackend` when `target.backendId != base.backendId`.
- `saveLocked` calls `validateDraft` before `buildPatch`. A non-empty error list returns `ValidationFailed` and nothing is written or recorded.
- Pinned by `Gate21jNoteEditOwnershipTest.aForeignDeckTargetIsRefusedNotSilentlyDropped`.

Residual: `buildPatch` on its own still omits a foreign target. Its documented precondition is that validation has passed. The save path honours the precondition, but a future caller could violate it. Hardening (make `buildPatch` return the error) is a candidate follow-up, not part of this gate.

**Deck-id collision (fixed in this gate):** `checkDeckTarget` used to look up the source deck by bare `deckId`. A foreign filtered deck with the same id, listed first, would block a legitimate move with `SourceDeckFiltered`. The lookup is now filtered by `backend.id`. Pinned by `aForeignFilteredDeckWithTheSameIdListedFirstDoesNotBlockAMoveWithinItsOwnBackend`. **Mutation check:** removing the owner filter from that lookup fails exactly this test; the code was then restored and rebuilt.

---

## §7 Verification

### §7.1 Environment and method

- Gradle, the Android SDK, and devices are unavailable in this sandbox. Verification is JVM-harness only: `tools/jvm-harness/bin/build.sh all` and `tools/jvm-harness/bin/run.sh`.
- Fake backends only (`FakeNoteMutationBackend`, with `collectionIdentity` as the scripting knob). No real personal collection was used.
- Build results (final): main 0 errors (328 files), compose 0 (24 files), test 0 (250 files).
- Test results (final): **RESULT classes=218 tests=2625 passed=2625 failed=0 ignored=0** (55.0 s). Baseline before 21J was 2584; the 41 new tests are all in the three new files.

### §7.2 New tests (41)

- `NoteMutationRoutingPolicyTest` (12): operation classes; admission needs only registry existence; admission routes to the same-identity instance; unregistered blocked for M1/M2/M3; verified mismatch blocks all; matching collection routes all; a key that disappears is DEGRADED and blocked for M2/M3; a key that appears is DEGRADED and blocked; DECISION-21J-03 reconciliation routes with unknown or matching keys; DECISION-21J-02 repeat routes without collection evidence; a route never names another backend.
- `Gate21jNoteEditOwnershipTest` (15):
  - owner isolation: an edit on B writes only B; the same with reversed registry order;
  - colliding ids: A's ambiguous record does not block a save on B for the same `note-1`; `activeMutationFor` is per backend;
  - recovery of A reconciles only A;
  - recovery of B reconciles only B;
  - retry of a B record goes to B and never to A;
  - an unregistered owner refuses with no record and no call, and no store write;
  - B's ambiguous record after restart without B: unresolved (`Unverifiable("backend_not_registered")`), no call on either backend, status unchanged;
  - collection mismatch blocks retry before any write (no second apply, no ledger write, status unchanged);
  - a key that appears after unknown admission blocks retry (`identity_unverifiable_degraded`);
  - a key that disappears after admission blocks retry;
  - a foreign filtered deck with the same id, listed first, does not block a same-backend move (regression);
  - a foreign deck target is `ValidationFailed(DeckTargetWrongBackend)`, not dropped;
  - a conflict owned by B: a superseding save on A is refused (`BackendMismatch`) with no call; the same on B applies;
  - legacy record without `collectionRef` decodes, keeps its owner B, and is reconciled only by B.
- `Gate21jArchitectureAuditTest` (14): no pinned backend in the coordinator; one `applyNoteMutation` call site inside `callBackend`; admission, retry, and reconciliation consult the policy; no registry walking; no preference, mode, resolver, or AUTO in `edit/`; the policy is pure (no ledger, no backend call, no UI); the editor's backend is a `val` with no `var`; the container passes the registry and no `backend = ankiDroidBackend`; deck lookups are owner-scoped; the foreign-deck error exists and validation precedes `buildPatch`; `collectionRef` is optional and owner-validated; codec version is 1 and strictness unchanged; the status enum is exactly the GATE 17 set; the mapper projects `BackendRouteBlocked`.

### §7.3 Existing suites

The GATE 17 suites (`NoteMutationCoordinatorTest`, `NoteMutationLedgerTest`, `NoteMutationTransitionTest`, `NoteMutationVerificationTest`), the GATE 21 audits (12 through 21I), and `EditNoteViewModelTest` all pass unchanged in the 2625 run. Only the three constructor and harness edits listed in §3 were made to existing tests. `backendMismatchIsRefused` changed its expectation from `BackendMismatch` to `BackendRouteBlocked("backend_not_registered")`, because the route is now resolved before the base check. Its intent (no write to a foreign backend) is kept, and it now also asserts no apply call.

---

## §8 Residual risks and limits

### §8.1 Codec downgrade (not mitigated)

Any snapshot written by the 21J build contains `"collectionRef":null` (or a value). A pre-21J build reads with `ignoreUnknownKeys = false`, so `NoteMutationCodec.decode` returns `null`. The ledger then reports `LedgerUnavailable("snapshot_corrupt")` (`NoteMutationLedger.kt`, the `decoded` read), and note-mutation save and recovery are blocked until the app is upgraded again. The snapshot is not rewritten, so the data is not lost. This is **fail-closed**, but a rollback makes the note ledger unusable. A safe fix is a versioned codec, or a read path that tolerates the one optional key. Neither was built here; both are a codec-policy decision.

### §8.2 Collection-unknown limits

- **Both sides unknown (AnkiDroid today):** with no key ever bound and none observed, the route is NOMINAL. For `OP-M2` this is acceptable because the refusal classification is collection-independent (DECISION-21J-02). For `OP-M3` (DECISION-21J-03), the owner's own read decides. **That read is instance-bound, not collection-proven.** If two AnkiDroid collections both lack a key and share a card id, a reconciliation read could in principle observe the wrong collection. This is the largest honest limit of the gate, and the reason DECISION-21J-03 needs audit approval.
- **Key appears or disappears:** DEGRADED. Blocked for `OP-M2` and `OP-M3`. Fail-closed.
- **Null is never a match.** The code never treats two unknowns as equal proof.

### §8.3 Verification limits

- **Not verified on a device.** No Android run, no real AnkiDroid, no real PC backend. The owner-routing behaviour is verified against `FakeNoteMutationBackend` only.
- **The owner's `reconcileNoteMutation` is only exercised by the fake.** The AnkiDroid adapter's reconciliation behaviour in production is not measured here.
- **UI blocked-save message:** the mapper branch is audited by source, not exercised by a behavioural test. The `EditNoteViewModel` blocked-save path is not tested end to end.
- **Stale-callback and persistence-failure ordering:** inherited from the GATE 17 suites. Not re-derived in this gate. The GATE 17 suites still pass.
- **`buildPatch` precondition** (§6.3): a future caller can bypass validation. Recorded as a hardening candidate.

### §8.4 Unchanged behaviour that matters

- A pre-21J record with no `collectionRef` is treated as unknown. It is routed by its owner under the NOMINAL rules, and it is not treated as a collection match.
- The owner, not the preference, decides. The global AUTO/preference does not appear in the note path (audit AUDIT-21J-05).

---

## §9 Verification coverage (VER-21J and AUDIT-21J)

**Limitation.** The canonical numbered lists (VER-21J-01…40 and AUDIT-21J-01…25) are defined in the GATE 21J brief. They are not reproduced in this repository. This report does **not** reconstruct them from memory and does **not** claim per-ID pass/fail. Coverage is given by theme. The audit suite labels its own tests `AUDIT-21J-01…14`, which are this suite's numbering and not a mapping to the canonical IDs.

| Theme | Evidence | Status |
|---|---|---|
| Save targets the base note's owner only | `editOpenedOnBIsWrittenOnlyToB`, `registryOrderNeverChangesTheOwner`, `AUDIT-21J-02/03` | Harness-verified |
| Retry, resume, and recovery go to the owner only | `retryOfARecordOwnedByBGoesToBAndNeverToA`, `ambiguousRecordOfBIsReconciledOnlyByB`, `recoveryOfAnAmbiguousAOnlyReconcilesAWhenBIsAlsoPresent` | Harness-verified |
| Unregistered owner: refuse, no record, no call | `unregisteredOwnerRefusesBeforeAnyRecordOrCall`, `ambiguousRecordOfBIsUnresolvedAfterRestartWithoutB` | Harness-verified |
| No AUTO, preference, or resolver in the note path | `AUDIT-21J-04/05`, `AUDIT-21J-07/08` | Source-audited |
| Collision of ids across backends | `collidingIdsOnAnotherBackendAreNeverTheSameNote` | Harness-verified |
| Collection match, mismatch, unknown, appeared, disappeared | `NoteMutationRoutingPolicyTest` (12), `collectionMismatchBlocksRetryBeforeAnyWrite`, `keyThatAppears…`, `keyThatDisappears…` | Harness-verified (fake collection identities only) |
| DECISION-21J-02 and -03 cells | `NoteMutationRoutingPolicyTest` reconciliation and repeat tests | Harness-verified; **-03 pending audit** |
| Deck target: owner-scoped, refused not dropped | `aForeignDeckTargetIsRefusedNotSilentlyDropped`, `aForeignFilteredDeck…`, `AUDIT-21J-09/10`; mutation check | Harness-verified |
| Conflict owner continuity | `conflictOwnedByBCanOnlyBeContinuedOnB` | Harness-verified |
| Legacy snapshot compatibility | `legacyRecordWithoutCollectionRefDecodesAndStaysWithItsOwner`, `AUDIT-21J-12` | Harness-verified (synthetic legacy JSON) |
| Status vocabulary reused | `AUDIT-21J-13` | Source/behaviour-audited |
| Editor backend immutable | `AUDIT-21J-07` | Source-audited |
| Blocked-save UI message | `AUDIT-21J-14` (source only) | **Gap:** no behavioural UI test |
| Stale callbacks, persistence-failure ordering, cancellation | GATE 17 suites (unchanged, passing) | Inherited, not re-derived |
| Downgrade behaviour | §8.1 | **Gap:** not mitigated; documented |
| Device, real AnkiDroid, real PC | — | **Not verified** (no environment) |

---

## §10 Gate lock decision

- **IMPLEMENTED and HARNESS-VERIFIED.** Owner binding works under the harness; the registry is the only route to a backend; the ledger, status machine, and coordinator are reused.
- **PENDING AUDIT:** DECISION-21J-03 (reconciliation cell deviation), including the 21I/21C inconsistency in §4.4.
- **NOT VERIFIED:** device, real AnkiDroid, real PC backend.
- **NOT STARTED:** GATE 21K.
