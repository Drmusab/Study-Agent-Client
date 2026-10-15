# GATE 21J — Note Editing Backend Binding (Implementation and Verification Report)

Status of this document: **implementation and audit report, harness-verified only.** No Gradle, Android SDK, device, real AnkiDroid, or real PC backend was available. Every result below comes from the JVM harness (`tools/jvm-harness`), which the harness README describes as evidence, not proof.

**Revision 2 (this revision).** The gate's one open item was the audit approval of `DECISION-21J-03`.
That audit is now complete and the decision is **REJECTED** — see §4.4 and §11. The note family now
follows locked §3.3 for `OP-M3` with no family branch, and the deviation is pinned against by
`AUDIT-21J-15`. Sections §1, §3, §4.3, §4.4, §7, §8.2, §9 and §10 were rewritten for that outcome;
§11 records the audit itself.

GATE 22 and GATE 21K were not started. Nothing in this change creates notes, migrates drafts, adds a settings control, or adds a backend-switch affordance.

---

## §1 Gate result

| Item | Result |
|---|---|
| Owner binding of a note edit (save, retry, recovery, reconciliation) | **Implemented.** Each touch goes to the record's own backend instance, resolved from the `AnkiBackendRegistry` by `backendId`. There is no fallback, AUTO, or preference lookup. |
| Reuse of GATE 17 ledger, status machine, and coordinator | **Reused.** No new ledger, coordinator, replay queue, or note database. Status vocabulary unchanged (PREPARED, SUBMITTING, APPLIED, RETRY_ALLOWED, AMBIGUOUS, CONFLICT). |
| Collection context on the record | **Implemented** as an optional `collectionRef` field (default `null`, validated against `backendId`). |
| `EditNoteViewModel` backend | **Immutable.** `backend` is a `val`. The `switchBackend` function was removed (it had no production callers). |
| Harness verification | **Passed.** Build: main 0 errors (328 files), compose 0 (24), test 0 (250). Tests: **2629 / 2629** (revision-1 baseline 2625, plus 4 new). |
| Device or real-backend verification | **Not performed.** Out of reach in this sandbox. |
| Audit-pending decision | **RESOLVED — `DECISION-21J-03` REJECTED** (§4.4, §11). `OP-M3` follows locked §3.3; the family branch is deleted and `AUDIT-21J-15` keeps it deleted. |
| Gradle / device / real backend | **BLOCKED (environment).** `services.gradle.org` is unreachable from this sandbox and no Android SDK is installed, so `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease` and `connectedDebugAndroidTest` could not run. See §7.1. |

**Gate lock:** IMPLEMENTED, AUDITED and HARNESS-VERIFIED. Not device-verified, and Gradle remains
environmentally blocked, so this is not CI proof.

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

**Revision 2 (audit resolution of DECISION-21J-03):**

| File | Change |
|---|---|
| `core/anki/edit/NoteMutationRoutingPolicy.kt` | The hand-rolled `OP-M3` branch is **deleted**. Every operation class now goes through `AnkiIdentityContinuation.continuationVerdict`. The family's only lever is the `AnkiOperationFacts` it states. |
| `core/anki/edit/NoteMutationCoordinator.kt` | A blocked `OP-M3` route still gathers `stateEvidence(record)` (an `OP-R2` read, decided separately by the matrix) when the in-process payload exists, and reports the block token when it does not. |
| `app/src/test/.../anki/edit/NoteMutationRoutingPolicyTest.kt` | 12 → 13. The two deviation tests replaced with the conforming cells; adds the continuity cell and the "match without the family claim" cell. |
| `app/src/test/.../anki/edit/Gate21jNoteEditOwnershipTest.kt` | 15 → 17. Reconciliation assertions follow the locked cells; adds a post-restart block case and an owner-isolation case under the one cell §3.3 permits. |
| `app/src/test/.../anki/edit/NoteMutationCoordinatorTest.kt` | Adds `reconcilingHarness()`. The GATE 17 fixture previously declared `authoritativeReconciliation = false` while implementing an authoritative read; the reconciliation tests now run against a backend that declares the read it implements and proves its collection. **No GATE 17 assertion was weakened.** |
| `app/src/test/.../anki/Gate21jArchitectureAuditTest.kt` | 14 → 15. `AUDIT-21J-15` forbids a private `OP-M3` branch and requires the shared matrix call. |

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

Every class below is answered by `AnkiIdentityContinuation.continuationVerdict`, the pure
transcription of locked §3.3. The family re-decides no cell.

| Assurance (bound vs live) | `OP-M1` | `OP-M2` | `OP-M3` | `OP-R2` |
|---|---|---|---|---|
| Authoritative match | route | route | route **only if** `authoritativeReconciliation`, else block `reconciliation_requires_authoritative_match` | route (locked matrix) |
| Authoritative mismatch | block `collection_mismatch` | block `collection_mismatch` | block `collection_mismatch` | locked matrix |
| Key appeared where none bound, or key disappeared (DEGRADED) | block `identity_unverifiable_degraded` | block `identity_unverifiable_degraded` | block `identity_unverifiable_degraded` | locked matrix |
| Both unknown, nominal | route | route (DECISION-21J-02) | **block `reconciliation_requires_authoritative_match`** | route; result carries the category |
| Session continuity (in-process payload live) | route | route (DECISION-21J-02) | **block `continuity_is_not_collection_evidence`** | route; result carries the category |

Because `authoritativeReconciliation = false` in both `NoteMutationSemantics.UNVERIFIED` and
`ANKIDROID_V2_24_1`, and no backend overrides `reconcileNoteMutation`, the `OP-M3` row is
**blocked in every cell for every backend that exists today**. That is the locked contract's own
answer, and it agrees with the semantics object: with no authoritative reconciliation read there is
nothing to permit. An ambiguous note mutation therefore stays ambiguous and says why.

A `Blocked` route never reaches the backend. Recovery returns `StillAmbiguous` with `Unverifiable(token)` and makes no read. Retry and resume refuse before any ledger transition. Pre-write refuses before any backend call.

### §4.4 Decisions recorded

- **DECISION-21J-01 — registry routing by record identity.** Adopted the approach the locked contract named as the required change (`GATE_21B_CONTRACT_LOCK.md` 21J, R9). The pinned instance is removed.
- **DECISION-21J-02 — proof of non-application.** `OP-M2` is permitted under nominal or continuity evidence. The GATE 17 pre-write refusal (`NoteMutationBackendResult.ConfirmedNotApplied`) is made by the backend before its single provider write, and does not rest on collection identity. This is an explicit family decision, stated in the policy's documentation.
- **DECISION-21J-03 — reconciliation cell: REJECTED by the audit (revision 2).** Revision 1 shipped a
  family branch that permitted `OP-M3` under nominal and continuity evidence, blocking only on a
  verified mismatch or a degraded key. **The audit rejected it and the branch is deleted.** Two
  findings decided it:
  1. **§3.3 is LOCKED and it says Blocked.** `BACKEND_IDENTITY_CONTRACT.md:265` blocks `OP-M3` under
     `SESSION_CONTINUITY_VERIFIED` ("continuity cannot establish whether the mutation applied") and
     under `IDENTITY_UNVERIFIABLE` (nominal), and permits it under `AUTHORITATIVE_MATCH` *only* when
     the family also exposes an authoritative reconciliation read. `AnkiIdentityContinuation.kt:158`
     and `:210` transcribe exactly that, so **the 21C code was already the locked truth** and the
     family branch was the deviation. A sub-gate cannot unilaterally override a LOCKED matrix; that
     requires the 21B contract owner.
  2. **The exception bought nothing.** `reconcileNoteMutation` has exactly one definition in all of
     `app/src/main` — the interface default returning `Unresolved()` (`AnkiBackend.kt:396`). No
     backend overrides it, and `authoritativeReconciliation = false` in both `UNVERIFIED` and
     `ANKIDROID_V2_24_1` (`NoteMutationBackendResult.kt:152`, `:181`). The permitted route therefore
     always reached a read that answers `Unresolved`, and the record stayed `AMBIGUOUS` either way.
     The revision-1 justification ("the owner's own read decides") described a read that does not
     exist in production.

  Conforming is fail-closed and functionally lossless. The refusal now names the real reason
  (`reconciliation_requires_authoritative_match`, or `continuity_is_not_collection_evidence` for a
  still-in-process record) instead of performing a read that cannot resolve anything.

  **One regression the audit caught and fixed.** The first conforming draft also dropped the
  read-only comparison from `StillAmbiguous`, which broke
  `EditNoteViewModelTest.anUnresolvedMutationBlocksTheEditorAndIsNeverWrittenOver` — the editor's
  "here is what Anki holds, and it does not prove the transaction applied" evidence. That read is a
  *different* operation class (`OP-R2`), which nominal evidence permits, so a blocked `OP-M3` must
  not suppress it. `reconcileAmbiguous` now still calls `stateEvidence(record)` when the payload is
  in process, and reports the block token when it is not (`NoteMutationCoordinator.kt`, the
  `NoteMutationRoute.Blocked` branch). Removing that call fails exactly that test.

  **The 21I/21C inconsistency is NOT resolved by this gate.** `DECISION-21I-03`
  (`GATE_21I_REVIEWER_ACTION_ROUTING.md:83`) still permits `OP-M3` on nominal/continuity for the
  reviewer-action family through its own `ReviewerActionRoutingPolicy.reconcileRoute()`. 21I is a
  locked gate, so its code is untouched here and the discrepancy is **referred to the 21B contract
  owner** (§11, OPEN-21J-01). The audit's finding is that the *structural* pattern is the defect:
  two families each hand-rolled the same locked cell. The note family no longer does; migrating 21I
  is a 21B/21I decision, not a 21J one.

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
- Gradle was re-attempted in revision 2 with a JDK on `JAVA_HOME`. It fails at distribution download: `services.gradle.org` is unreachable (`java.io.EOFException: SSL peer shut down incorrectly`), and `ANDROID_HOME`/`ANDROID_SDK_ROOT` are unset. Every `./gradlew` task in PART V of the brief is therefore **BLOCKED (environment)**, not passed.
- Fake backends only (`FakeNoteMutationBackend`, with `collectionIdentity` as the scripting knob). No real personal collection was used.
- Build results (revision 2, final): main 0 errors (328 files), compose 0 (24 files), test 0 (250 files).
- Test results (revision 2, final): **RESULT classes=218 tests=2629 passed=2629 failed=0 ignored=0** (34.4 s). Revision 1 was 2625; the net +4 is routing policy 12→13, ownership 15→17, architecture audit 14→15.
- **Mutation checks (revision 2).** Both changes were falsified deliberately and then restored:
  - Reintroducing the `OP-M3` family branch fails **8** tests across all three suites (`AUDIT-21J-15`, both routing-policy cell tests, the continuity cell test, and four ownership tests).
  - Removing the `stateEvidence` call from the blocked-reconciliation branch fails exactly `EditNoteViewModelTest.anUnresolvedMutationBlocksTheEditorAndIsNeverWrittenOver`.
  The code was restored after each and the full suite re-run green.

### §7.2 New tests (41)

- `NoteMutationRoutingPolicyTest` (13, revision 2): operation classes; admission needs only registry existence; admission routes to the same-identity instance; unregistered blocked for M1/M2/M3; verified mismatch blocks all; matching collection routes all; a key that disappears is DEGRADED and blocked for M2/M3; a key that appears is DEGRADED and blocked; `OP-M3` blocked on nominal evidence even with an authoritative read; `OP-M3` blocked on in-process continuity; `OP-M3` blocked on a match when the family claims no authoritative read, and routed when it does; DECISION-21J-02 repeat routes without collection evidence; a route never names another backend.
- `Gate21jNoteEditOwnershipTest` (17, revision 2):
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
- `Gate21jArchitectureAuditTest` (15, revision 2): adds `AUDIT-21J-15` (no private `OP-M3` branch; the shared matrix call is present; the reconciliation claim is read from the backend's own semantics). Previously: no pinned backend in the coordinator; one `applyNoteMutation` call site inside `callBackend`; admission, retry, and reconciliation consult the policy; no registry walking; no preference, mode, resolver, or AUTO in `edit/`; the policy is pure (no ledger, no backend call, no UI); the editor's backend is a `val` with no `var`; the container passes the registry and no `backend = ankiDroidBackend`; deck lookups are owner-scoped; the foreign-deck error exists and validation precedes `buildPatch`; `collectionRef` is optional and owner-validated; codec version is 1 and strictness unchanged; the status enum is exactly the GATE 17 set; the mapper projects `BackendRouteBlocked`.

### §7.3 Existing suites

The GATE 17 suites (`NoteMutationCoordinatorTest`, `NoteMutationLedgerTest`, `NoteMutationTransitionTest`, `NoteMutationVerificationTest`), the GATE 21 audits (12 through 21I), and `EditNoteViewModelTest` all pass in the 2629 run. No GATE 17 assertion was deleted or weakened in revision 2: the only edit to `NoteMutationCoordinatorTest` is the additive `reconcilingHarness()`, and the two tests that use it assert exactly what they asserted before. `EditNoteViewModelTest` was not edited at all in revision 2 — it is the test that caught the dropped read-only evidence. `backendMismatchIsRefused` changed its expectation from `BackendMismatch` to `BackendRouteBlocked("backend_not_registered")`, because the route is now resolved before the base check. Its intent (no write to a foreign backend) is kept, and it now also asserts no apply call.

---

## §8 Residual risks and limits

### §8.1 Codec downgrade (not mitigated)

Any snapshot written by the 21J build contains `"collectionRef":null` (or a value). A pre-21J build reads with `ignoreUnknownKeys = false`, so `NoteMutationCodec.decode` returns `null`. The ledger then reports `LedgerUnavailable("snapshot_corrupt")` (`NoteMutationLedger.kt`, the `decoded` read), and note-mutation save and recovery are blocked until the app is upgraded again. The snapshot is not rewritten, so the data is not lost. This is **fail-closed**, but a rollback makes the note ledger unusable. A safe fix is a versioned codec, or a read path that tolerates the one optional key. Neither was built here; both are a codec-policy decision.

### §8.2 Collection-unknown limits

- **Both sides unknown (AnkiDroid today):** with no key ever bound and none observed, the route is NOMINAL. For `OP-M2` this is acceptable because the refusal classification is collection-independent (DECISION-21J-02). For `OP-M3` it is now **blocked**, per locked §3.3.
- **Revision-1 risk retired.** Revision 1 permitted the `OP-M3` read on nominal evidence and recorded the honest worry that "if two AnkiDroid collections both lack a key and share a card id, a reconciliation read could in principle observe the wrong collection". Rejecting DECISION-21J-03 removes that exposure entirely: the read is never made on nominal evidence. The cost is that an `AMBIGUOUS` note mutation on AnkiDroid can only be closed by a human attestation, which is what `authoritativeReconciliation = false` already implied and what the editor already discloses (`EditNoteMapper.kt:268`, "Study Agent cannot prove afterwards whether it applied").
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
| DECISION-21J-02 cell, and -03 rejection | `reconciliationIsBlockedWhenNeitherSideHasAKeyEvenWithAnAuthoritativeRead`, `reconciliationIsBlockedOnInProcessContinuityAlone`, `matchingCollectionRoutes…` pair, `AUDIT-21J-15` | Harness-verified; **-03 resolved (rejected)** |
| Deck target: owner-scoped, refused not dropped | `aForeignDeckTargetIsRefusedNotSilentlyDropped`, `aForeignFilteredDeck…`, `AUDIT-21J-09/10`; mutation check | Harness-verified |
| Conflict owner continuity | `conflictOwnedByBCanOnlyBeContinuedOnB` | Harness-verified |
| Legacy snapshot compatibility | `legacyRecordWithoutCollectionRefDecodesAndStaysWithItsOwner`, `AUDIT-21J-12` | Harness-verified (synthetic legacy JSON) |
| Status vocabulary reused | `AUDIT-21J-13` | Source/behaviour-audited |
| Editor backend immutable | `AUDIT-21J-07` | Source-audited |
| Blocked-save UI message | `AUDIT-21J-14` (source only) | **Gap:** no behavioural UI test |
| Blocked reconciliation keeps read-only evidence | `anUnresolvedMutationBlocksTheEditorAndIsNeverWrittenOver` (mutation-checked) | Harness-verified |
| Stale callbacks, persistence-failure ordering, cancellation | GATE 17 suites (unchanged, passing) | Inherited, not re-derived |
| Downgrade behaviour | §8.1 | **Gap:** not mitigated; documented |
| Device, real AnkiDroid, real PC | — | **Not verified** (no environment) |

---

## §10 Gate lock decision

- **IMPLEMENTED, AUDITED and HARNESS-VERIFIED.** Owner binding works under the harness; the registry is the only route to a backend; the ledger, status machine, and coordinator are reused; and the one open deviation has been audited and removed.
- **RESOLVED:** DECISION-21J-03 — **REJECTED**; `OP-M3` follows locked §3.3 with no family branch (§4.4, §11).
- **STILL OPEN, NOT THIS GATE'S TO CLOSE:** the 21I/21C `OP-M3` discrepancy. 21I is locked, so its `reconcileRoute()` is untouched and the item is referred to the 21B contract owner as **OPEN-21J-01** (§11).
- **NOT VERIFIED:** Gradle (environment-blocked), device, real AnkiDroid, real PC backend. Harness results are equivalence evidence, not CI proof.
- **NOT STARTED:** GATE 21K.

---

## §11 Audit record (revision 2)

The gate's independent architecture audit, run against source and against the harness. Only the
`OP-M3` question is reproduced in full here; the other `AUDIT-21J-*` items are unchanged from
revision 1 and are pinned by `Gate21jArchitectureAuditTest` (15 tests).

```text
AUDIT ID:   AUDIT-21J-03 / AUDIT-21J-04 — immutable edit binding and transaction destination
RESULT:     PASS
EVIDENCE:   routing is by record.backendId against the registry; 2629/2629 harness tests, including
            editOpenedOnBIsWrittenOnlyToB and registryOrderNeverChangesTheOwner.
DEFECT:     none found in revision 2.
REMEDIATION: n/a
```

```text
AUDIT ID:   DECISION-21J-03 — the note family's OP-M3 cell versus locked §3.3
RESULT:     FAIL (revision 1) → remediated → PASS (revision 2)
EVIDENCE:   BACKEND_IDENTITY_CONTRACT.md:265 blocks OP-M3 on continuity and nominal evidence and
            conditions the match cell on the family's own authoritative read;
            AnkiIdentityContinuation.kt:158,:210 transcribe that; NoteMutationBackendResult.kt:152,:181
            set authoritativeReconciliation = false for UNVERIFIED and ANKIDROID_V2_24_1;
            AnkiBackend.kt:396 is the only definition of reconcileNoteMutation and returns Unresolved.
DEFECT:     a family branch re-decided a LOCKED cell, so the matrix could be overridden without
            amending the contract. The branch also permitted a read that no backend implements.
REMEDIATION: branch deleted; every operation class routed through the shared matrix;
            AUDIT-21J-15 pins it; 8 tests fail if the branch returns (mutation-checked).
```

```text
AUDIT ID:   OPEN-21J-01 — 21I/21C OP-M3 discrepancy (referred, not resolved)
RESULT:     BLOCKED (owned by GATE 21B / GATE 21I, outside this gate's authority)
EVIDENCE:   DECISION-21I-03 (GATE_21I_REVIEWER_ACTION_ROUTING.md:83) permits OP-M3 on nominal and
            continuity evidence; AnkiIdentityContinuation.kt:210 blocks it. ReviewerActionRoutingPolicy
            .reconcileRoute() hand-rolls the permitted cell, so 21I's code follows 21I's text and
            contradicts 21C's transcription of §3.3.
DEFECT:     the locked matrix and one locked family disagree. Both cannot be the locked truth.
REMEDIATION: the 21B contract owner must either amend §3.3 with an explicit, typed family-exception
            clause (making the shared matrix the single decider, as the note family now is) or
            conform 21I. 21J did not touch 21I: it is a locked gate and INV-21J-31 forbids weakening it.
```

```text
AUDIT ID:   GATE 17 preservation (INV-21J-28, INV-21J-31)
RESULT:     PASS
EVIDENCE:   the ledger, status machine, coordinator and refusal vocabulary are unchanged; the only
            edit to NoteMutationCoordinatorTest is a new reconcilingHarness() for two reconciliation
            tests, and every GATE 17 assertion still passes unchanged in the 2629 run.
DEFECT:     the pre-existing fixture declared authoritativeReconciliation = false while implementing
            an authoritative read. The fixture was made self-consistent; the assertions were not
            weakened.
REMEDIATION: n/a
```
