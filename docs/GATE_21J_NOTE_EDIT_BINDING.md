# GATE 21J — Note Editing Backend Binding (Implementation Record, Audit and Authoritative Verification)

Sub-gate of **GATE 21 — Multi-Backend Anki Selection / Hybrid Mode**. Parent contract:
`docs/GATE_21B_CONTRACT_LOCK.md` §21J (`:1585`), DECISION-21B-20 (`:987`). Transaction authority:
`docs/GATE_17_NOTE_EDITING.md` / `GATE_17_BACKEND_CONTRACT.md`, unchanged.

**Revision 3 (this revision).** This revision executes the gate's **canonical** plan, which revision 2
explicitly declined to reconstruct: `AUDIT-21J-01 … AUDIT-21J-25` and `VER-21J-01 … VER-21J-40` are now
executable IDs, one test per ID, green at this HEAD; the earlier revision's count accounting is
reconciled against a measured baseline; and `OPEN-21J-01` is audited and **closed**. No production source
was changed by this revision — the implementation was found present and complete at HEAD (§1, §5).

**Status of this document.** Implementation, audit and verification report, **harness-verified only**.
No Gradle, Android SDK, device, real AnkiDroid or real PC backend was available in this environment
(§9.2). Nothing below is CI proof, and no device claim is made.

**GATE 21K is not started.** Nothing here creates notes, migrates drafts, adds a settings control, adds a
backend-switch affordance, or introduces a replay queue (§13).

---

## §1 Gate result

```text
REPOSITORY:                  Drmusab/Study-Agent-Client
HEAD BEFORE THIS REVISION:   1fe55cc68d23a2315ed3066a09adbd7d1890e97b  (merge of PR #87)
BRANCH:                      arena/c3b2f3c9-study-agent-client
WORKTREE BEFORE THIS REVISION: clean
SCOPE:                       GATE 21J only; GATE 21K NOT started
PRODUCTION SOURCE CHANGED:   none — the 21J implementation is already at HEAD and was audited as found
```

**§1.1 Required initial report — the state the gate was found in.** The gate's implementation was already
present at HEAD and is complete: the note-mutation family resolves its owner from the registry by
`record.backendId` (new save: `base.backendId`), the editor's backend is an immutable `val`, and the
GATE 17 ledger, status machine, coordinator and refusal vocabulary are reused. What was **missing** was
the gate's *evidence*: revision 2 recorded 2,629 tests and stated it could not map the canonical
`AUDIT-21J-*` / `VER-21J-*` lists. This revision supplies both — canonical, executed, per-ID evidence —
and reconciles the numbers.

**§1.2 Result.**

| Item | Result |
|---|---|
| Owner binding of a note edit (save, retry, recovery, reconciliation) | **Implemented and audited.** Every backend touch goes to the record's own registered instance. No fallback, no AUTO, no preference lookup, no "first ready". |
| Reuse of the GATE 17 ledger, status machine, coordinator | **Reused.** No new ledger, coordinator, replay queue or note database. Status vocabulary unchanged: `PREPARED`, `SUBMITTING`, `APPLIED`, `RETRY_ALLOWED`, `AMBIGUOUS`, `CONFLICT`. |
| Editor bound to the originating backend, never the preference | **Implemented.** `EditNoteViewModel.backend` is a `val` (`:48`); `switchBackend` does not exist; the edit route resolves `registry.find(editCardRef.backendId)`. |
| Backend-qualified `NoteRef` / `CardRef` / `DeckRef` / `ModelRef` | **Implemented** (`AnkiRefs.kt`); identical numeric ids on two backends are different resources (§7 AUDIT-21J-05, VER-21J-06/07/08). |
| Exact-`BackendId` routing for save/retry/recovery; no cross-backend replay | **Implemented and verified** (VER-21J-09/11/21/25/26/27/29/30/36). |
| `CONFLICT` vs `AMBIGUOUS` preserved as distinct durable outcomes | **Implemented and verified** (VER-21J-17/18; AUDIT-21J-14). |
| Partial/composite saves handled conservatively | **Implemented and verified** (VER-21J-22; AUDIT-21J-12): only an all-steps verification yields `APPLIED`; otherwise `PARTIAL_OPERATION_UNKNOWN` → `AMBIGUOUS`, never a retry. |
| Recovery only through the original backend | **Implemented and verified** (VER-21J-27/28/29/30/36). |
| Legacy records and stale callbacks protected | **Implemented and verified** (VER-21J-32/35/36; AUDIT-21J-19/20). |
| Read caches invalidated only for the correct scope | **Audited** (AUDIT-21J-21; §11 INV-21J-20): one scoped library snapshot, owner-scoped invalidation, no second cache. |
| Architecture audit `AUDIT-21J-01…25` | **25 / 25 PASS** (`Gate21jCanonicalAuditTest`), plus the earlier `SRC-21J-*` suite (15 PASS). |
| Authoritative verification `VER-21J-01…40` | **39 executed / 39 PASS; 1 BLOCKED (environment)** — VER-21J-39 needs a device and a real backend. |
| Full JVM regression | **PASS — 222 classes, 2,711 tests, 2,711 passed, 0 failed, 0 ignored** (§9.1). |
| Gradle (`testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease`, `connectedDebugAndroidTest`) | **ENVIRONMENT BLOCKER — BLOCKED** (§9.2), with first-hand evidence. |
| `DECISION-21J-03` (revision-1 deviation) | **REJECTED**, branch deleted and pinned by `AUDIT-21J-15` (§6.4). |
| `OPEN-21J-01` (21I/21C `OP-M3` contradiction) | **CLOSED — verified conforming at HEAD** (§12.2). |

**Gate lock:** **IMPLEMENTED · AUDITED · HARNESS-VERIFIED · NOT DEVICE-VERIFIED.** The gate is locked for
further sub-gates on the strength of audit + harness evidence, with the Gradle/device items recorded as
environmental blockers rather than as passes.

---

## §2 Scope, method and evidence sources

**§2.1 In scope.** Note editing only: the owner of a draft, a save, a durable `NoteMutation`, a retry, a
recovery read, a reconciliation read and an attestation; the qualification of the references they carry;
the projection of a blocked save into the editor; and the verification evidence for all of it.

**§2.2 Out of scope (must not be implemented here, and was not).** No new ledger, coordinator, status
machine or routing framework; no cross-backend migration, note copy, ID translation or merge; no offline
queue or replay; no template/note-type editor; no new settings UI; no change to any locked earlier gate
(`AUDIT-21J-24`, `AUDIT-21J-25`).

**§2.3 Method — how every claim in this report was produced.**

| Kind of evidence | What it means here |
|---|---|
| **Behaviour (`B`)** | Two fake backends whose note, card, deck and model ids **all collide** (`Gate21jBackends`, CHECKPOINT 21J-30) driven through the real `EditNoteViewModel` and the real `DefaultNoteMutationCoordinator` over an in-memory ledger. |
| **Source audit (`S`)** | A property of the repository text, asserted by a test that reads the sources with comment lines stripped, so documentation may name a forbidden spelling while live code may not use it. |
| **Harness run (`H`)** | `tools/jvm-harness` — a Gradle-free JVM build of the app's pure-Kotlin sources and the JVM unit tests. Equivalence evidence, **not** proof of what CI would report. |
| **Not available (`X`)** | Gradle, lint, APK assembly, instrumentation, device, real AnkiDroid, real PC agent backend. Recorded as blocked, never as passed. |

**§2.4 Harness deviations (carried from `tools/jvm-harness/README.md`).** Kotlin 2.3/K2 (Gradle uses
1.9.24/K1); coroutines 1.10.2 (Gradle 1.8.1); serialization 1.9.0 (Gradle 1.6.3); `android.jar` is the
AGP mockable jar rewritten to return default values; DataStore/OkHttp/security-crypto are in-memory
shims. Four test classes are excluded (`FakeAgentConnectionTest`, `WebSocketIntegrationTest`,
`RatingControlsPolicyTest`, `StudyPhaseMappingTest`); no Compose UI, instrumented test, lint or APK is
built. `@Test(timeout)` becomes a per-test join timeout.

**§2.5 What this gate does not claim.** Not that AnkiDroid's real provider behaves as the fake models;
not that a real collection identity is ever proven on AnkiDroid (it is `null` at the pin — §13 R-21J-1);
not that any device has executed a note save; and not that the harness numbers equal CI numbers.

---

## §3 Backend capability and ownership inventory (CHECKPOINT 21J-01)

**§3.1 Note-editing capability, as the repository states it** (`docs/BACKEND_CAPABILITY_MATRIX.md:187`):

| Backend | Note edit (fields/tags/deck) | Evidence |
|---|---|---|
| AnkiDroid (pinned public provider v2.24.1) | **Available** | `AnkiDroidNoteMutationGateway` + `AnkiDroidBackend.applyNoteMutation` (`:302`) and `noteMutationSemantics` (`:310`), both present only when the gateway is present |
| PC agent | **Not implemented** | `data/anki/remote/` contains only `PcAnkiAvailabilitySource.kt`; `BACKEND_IDENTITY_CONTRACT.md` §3.4 records "PC agent: None — no implementation". Every PC note-edit cell in this report is *no code*, not a measurement. |
| Test fixtures | **Limited, opt-in** | `FakeNoteMutationBackend`; semantics are opt-in per test, and authoritative reconciliation is default-**off** (VER-21J-16, AUDIT-21J-13) |

**§3.2 Consequence for this gate's evidence.** Because only one real note-editing backend exists, a
*production* two-backend collision cannot be produced. The gate therefore proves the ownership rules with
two registered fakes whose ids collide exactly, and discloses that the second backend is a fixture
(§13 R-21J-4). This is the same limitation GATE 21E recorded for AUTO.

**§3.3 Pinned AnkiDroid semantics the routing and verification decisions rest on**
(`NoteMutationSemantics.ANKIDROID_V2_24_1`, each value backed by the provider evidence recorded in
`GATE_17_BACKEND_CONTRACT.md`):

| Claim | Value | Why it matters to this gate |
|---|---|---|
| `contentWriteAtomic` | `true` | Fields and tags share one all-or-nothing transaction — but only *within* one operation, so a content+deck save is still two provider transactions. |
| `deckChangeScope` | `CARD_ONLY` | A deck write moves one card; the note's other cards stay (`AUDIT-21J-11`, `VER-21J-13`). |
| `conflictGuarantee` | `BEST_EFFORT_PRE_SAVE_REREAD` | The only pre-write protection is the app's own re-read, which is exactly the `CONFLICT` check (`VER-21J-17`). |
| `authoritativeReconciliation` | `false` | `OP-M3` is blocked for every ambiguous AnkiDroid note; no receipt or lookup-by-transaction exists (`AUDIT-21J-13`). |
| `idempotentReplay` | `false` | A repeat cannot be distinguished from a first write; replay is never automatic (`VER-21J-21/26`). |
| `backendHonoursMutationId` | `false` | `NoteMutationId` is local transaction identity and creates **no** backend idempotency. |
| `fieldIdentity` | `POSITIONAL_ORDINAL` | Field patches are ordinal-addressed and count-checked (`AUDIT-21J-09`). |
| `trailingEmptyFieldRepresentable` | `false` | A write whose last field is empty is refused before the boundary (`AUDIT-21J-08`). |
| `tagWriteModel` | `REPLACE_ONLY_CANONIFIED` | Whole-set replacement only; no add/remove operation exists (`AUDIT-21J-10`). |
| `backendCanonifiesTags`, `backendNormalizesFieldText` | `true`, `true` | Post-write verification must compare canonically, not byte-for-byte. |
| `contentWriteNoOpWhenUnchanged` | `true` | An unchanged content write is an effective no-op — still not a replay guarantee. |
| `deckWriteAlwaysWrites` | `true` | Repeating a deck write is **not** a no-op. |
| `fieldEditCanGenerateSiblingCards` | `true` (+ `generatedSiblingCardDeck = TEMPLATE_TARGET_ELSE_NOTE_TYPE_LAST_DECK`) | A field edit can create additional cards, and the write response does not report them: `APPLIED` must therefore rest on the verified read, never on the response (`VER-21J-22`). |

`UNVERIFIED` (the `AnkiBackend` default) claims nothing: `contentWriteAtomic = false`,
`deckChangeScope = UNKNOWN`, `conflictGuarantee = NONE`, `idempotentReplay = false`, `fieldIdentity =
UNKNOWN`, `tagWriteModel = UNKNOWN` (which refuses tag edits), `authoritativeReconciliation = false`.
A backend that has not declared itself is therefore *stricter*, never looser — and the fake fixtures
inherit those defaults. The one exception is stated rather than assumed: the fixture's
`authoritativeReconciliation` is opt-in **per test**, and default-**off** (`VER-21J-16`,
`AUDIT-21J-13`).

**§3.4 Ownership matrix rows this gate is bound by.** The `NoteMutation` row (owner = the record's
`backendId`; recovery routed by durable identity; refusal is never a redirect) and the `OP-M1`/`OP-M2`
assurance cells of `BACKEND_IDENTITY_CONTRACT.md` §3.3, transcribed once in
`AnkiIdentityContinuation.continuationVerdict` and consumed by the note family through
`NoteMutationRoutingPolicy` (AUDIT-21J-01, AUDIT-21J-13, AUDIT-21J-15).

---

## §4 The note-edit path as implemented

**§4.1 Components and their single responsibility.**

```text
route            Screen.EditNote("edit-note/{cardRef}")        → carries the full backend-qualified AnkiCardRef
resolution       AppNavHost                                     → registry.find(editCardRef.backendId)
                                                                 (+ error screen when unregistered)
editor           EditNoteViewModel.backend: AnkiBackend (val)   → never re-read from the preference
decision         NoteMutationRoutingPolicy                       → admit / route by backendId + locked §3.3
transaction      NoteMutationCoordinator + NoteMutationLedger    → GATE 17, unchanged status vocabulary
write            AnkiBackend.applyNoteMutation(owner)            → exactly one call site
projection       EditNoteMapper / EditNoteModels                 → refusal copy, blocked-mutation state
```

**§4.2 Admission.** A new mutation is admitted against `base.backendId`. The target must be **registered**
at admission (`backend_not_registered` otherwise) — a client-side test, not a network probe. The mutation
class is derived from durable state: a fresh or restored `PREPARED` is `OP-M1`; anything else that may
still reach the backend is `OP-M2`; a reconciliation read is `OP-M3`; the recovery pass is `OP-P`.

**§4.3 The cells actually enforced** (identical to the locked matrix; the family has no branch of its own):

| Evidence | `OP-M1` | `OP-M2` (retry) | `OP-M3` (reconcile) | `OP-P` (recovery) |
|---|---|---|---|---|
| Authoritative collection **match** | permitted | permitted | permitted **only** with the family's authoritative read (`reconciliation_requires_authoritative_match` otherwise) | permitted |
| `SessionContinuityVerified` (in-process key only) | permitted | permitted (non-application proof is collection-independent in this family) | **blocked** (`continuity_is_not_collection_evidence`) | blocked (no in-process binding) |
| `IdentityUnverifiable(DEGRADED)` (a key appeared) | blocked | blocked | blocked | blocked |
| `IdentityUnverifiable(NOMINAL)` (keyless) | permitted (disclosed) | permitted | **blocked** | permitted (family does not require collection proof) |
| Foreign backend | blocked | blocked | blocked | blocked |

`OP-M1`/`OP-M2`/`OP-M3`/`OP-P` are decided by the shared `AnkiIdentityContinuation.continuationVerdict`
(`BACKEND_IDENTITY_CONTRACT.md` §3.3, locked at `:265`); the family supplies facts and a live capability
read, and nothing else (`AUDIT-21J-15`, `AUDIT-21J-13`).

**§4.4 Refusals (typed; a refusal never redirects).** `UnsupportedDimension(dimension)`,
`TrailingEmptyFieldNotRepresentable`, `BackendMismatch`, `BackendRouteBlocked(token)` — tokens
`backend_not_registered`, `collection_mismatch`, `identity_unverifiable_degraded`,
`reconciliation_requires_authoritative_match`, `continuity_is_not_collection_evidence` —
`DeckListingUnavailable`, `SaveInProgress`, `StudyActivityOverlaps`, `RetryNotAllowed(status)`,
`UnknownMutation`, `PayloadUnavailable`, `ConflictNotResolvable(status)`. Each is projected to distinct
user copy in `EditNoteMapper.refusalMessage` (`:361-386`), including the blocked-route copy that states
the edit was not sent and that the app did not switch to another backend.

**§4.5 Editor states.** `EditNoteUiState{Loading, Unavailable, Error, Refused, Ready}`;
`EditNoteSaveState{Idle, Saving, Saved, Blocked, RetryAvailable, Conflicted, Ambiguous}`;
`EditNoteCapabilities.authoritativeReconciliation` is documented in source as "Never true at this pin".
A `Blocked` state carries the unresolved mutation and no note content; `canSave` is false while a
mutation is unresolved, so a possibly-applied write is never overwritten (VER-21J-34, CHECKPOINT-21J-28).

---

## §5 File-by-file report

**§5.1 Production code — as found at HEAD (unchanged by this revision).**

| File | State |
|---|---|
| `core/anki/edit/NoteMutationRoutingPolicy.kt` | Present: pure `admit` / `route` / `mutationOperationClass`; locked-matrix delegation; stable block tokens. |
| `core/anki/edit/NoteMutationRecord.kt` | `collectionRef: AnkiCollectionIdentity? = null`, validated `collectionRef == null \|\| collectionRef.backendId == backendId`; the durable record stores metadata only — never field values or tag text. |
| `core/anki/edit/NoteMutationCoordinator.kt` | Registry-routed (`private val backends: AnkiBackendRegistry`); one `.applyNoteMutation(` call site inside `callBackend`; `routing.route(record, OP_M3, pending.containsKey(record.mutationId))`; pre-write drift check → `CONFLICT`; post-write verification over all steps; `POST_WRITE_READ_ATTEMPTS = 2`; single-holder `withGate` tryLock. |
| `core/anki/edit/NoteMutationLedger.kt` | GATE 17 ledger; codec `VERSION = 1`, `encodeDefaults = true`, `ignoreUnknownKeys = false`; `snapshot_corrupt`, `write_not_committed`; startup normalization of `SUBMITTING` → `AMBIGUOUS`. |
| `ui/screens/editnote/EditNoteViewModel.kt` | `backend` is a `val` (`:48`); generation tokens on every suspending action; `retry` / `resumePrepared` / `checkAgain` (read-only) / `attest` / `reloadAfterConflict`. |
| `ui/screens/editnote/EditNoteMapper.kt`, `EditNoteModels.kt` | Refusal copy per reason; blocked-mutation projection; UI state machine. |
| `di/AppContainer.kt` (`:466-468`), `ui/navigation/AppNavHost.kt` (`:460`), `Screen.kt` (`:64`) | `backends = ankiBackendRegistry`; edit route resolves from the qualified ref. |
| `data/anki/ankidroid/AnkiDroidBackend.kt` (`:302`, `:310`) | The only real `applyNoteMutation` / `noteMutationSemantics` in the tree. |

**§5.2 Changes made by this revision (verification only — no production file touched).**

| File | Change |
|---|---|
| `app/src/test/.../anki/edit/Gate21jBackends.kt` | **New.** The colliding two-backend world + counters used by every behavioural claim (CHECKPOINT 21J-30): identical note/card/deck/model ids on A and B, per-backend ids, registry, mutable preference and read context, `restart()`, persisted-status accessors. |
| `app/src/test/.../anki/edit/Gate21jCanonicalVerificationTest.kt` | **New.** `VER-21J-01…37` + `CHECKPOINT-21J-28`, one test per ID, through the real `EditNoteViewModel` and the real coordinator. |
| `app/src/test/.../anki/Gate21jCanonicalAuditTest.kt` | **New.** `AUDIT-21J-01…25`, one test per ID, source audit + behaviour. |
| `app/src/test/.../anki/edit/Gate21jNoteEditEnduranceTest.kt` | **New.** `VER-21J-38` — the 1,000-event deterministic fault injection (§10). |
| `app/src/test/.../anki/edit/FakeNoteMutationBackend.kt` | Modified (+75/−18): colliding ids, sibling cards, `deckIds`/`noteId`/`cardId` parameters, request-aware faithful apply, per-instance hooks. Defaults are unchanged, so every GATE 17 assertion still holds. |
| `docs/GATE_21J_NOTE_EDIT_BINDING.md` | This document, revision 3. |

**§5.3 Pre-existing 21J test batch at HEAD (audited, not written by this revision).**
`NoteMutationRoutingPolicyTest` (**13** tests), `Gate21jNoteEditOwnershipTest` (**17**),
`Gate21jArchitectureAuditTest` (**15**, its own `SRC-21J-*` numbering — *not* a mapping to the canonical
IDs). Revision 2 described this batch as 12 + 15 + 14 = 41 tests; the measured counts are
13 + 17 + 15 = **45** (§12.1 D-21J-03).

---

## §6 Durability, conflict, ambiguity, recovery, partial saves

**§6.1 Ordering.** Durable record → durable submission intent → one backend write per operation →
authoritative read → verified terminal status. A `PREPARED` persistence failure issues **zero** backend
calls (`LedgerUnavailable("write_not_committed")`, VER-21J-19/20).

**§6.2 `CONFLICT` is evidence-based.** The pre-write re-read must actually disagree with the recorded base
(`if (latest != payload.base)`) before `CONFLICT` is recorded; nothing is written. `AMBIGUOUS` is recorded
when the write's outcome is unknown. The two are different statuses from different causes and are never
conflated (VER-21J-17/18, AUDIT-21J-14).

**§6.3 `AMBIGUOUS` is terminal-for-now.** It survives restart, session end, preference change and
reconnect; it can only leave via an authoritative reconciliation (`OP-M3`, which no backend at this pin
supports) or an explicit human attestation, recorded as an attestation and never as backend evidence
(`USER_ATTESTED_APPLIED` / `USER_ATTESTED_NOT_APPLIED`). `checkAgain` re-reads evidence and writes
nothing; it never converts ambiguity into a safe retry (VER-21J-21/26; §8 Q10).

**§6.4 `DECISION-21J-03` — rejected.** Revision 1 let the note family permit `OP-M3` on
`SessionContinuityVerified`/`NOMINAL`. That re-decided a locked cell. The branch is deleted; `OP-M3` now
follows locked §3.3 exactly, and `AUDIT-21J-15` fails if any `operation == OP_M3` branch reappears in the
policy.

**§6.5 Partial and composite saves.** Only when *every* planned operation is verified against the
post-write read is `APPLIED` recorded; a failure after an earlier step becomes `AMBIGUOUS` with
`PARTIAL_OPERATION_UNKNOWN`, never a retry, and a confirmed write whose `APPLIED` persistence fails
becomes `SUBMITTING → AMBIGUOUS` (`applied_not_recorded`) (VER-21J-22/23).

**§6.6 Deck targets are card-scoped and owner-scoped.** A target deck is accepted only if it belongs to
the owner (`it.ref.backendId == backend.id`), and only the edited card moves — the note's other cards do
not (VER-21J-11/13, AUDIT-21J-11).

**§6.7 Legacy and stale traffic.** Pre-21J bytes (no `collectionRef`) decode and stay with their owner; a
record whose owner is gone blocks with `backend_not_registered` and is never reassigned; a save whose
result arrives after the editor's generation moved on is dropped (VER-21J-32/35/36).

---

## §7 Architecture audit — `AUDIT-21J-01 … AUDIT-21J-25`

Executed as `app/src/test/.../anki/Gate21jCanonicalAuditTest.kt` — **25 / 25 PASS**. `B` = behaviour
(two colliding backends), `S` = source audit.

| ID | Audit question | Kind | Result |
|---|---|---|---|
| AUDIT-21J-01 | Are the ownership rules the locked 21B / GATE 17 contracts, and is no draft ever migrated? | S | PASS — policy decides through `AnkiIdentityContinuation.continuationVerdict` + `AnkiIdentityAssurancePolicy`; status vocabulary exactly the six GATE 17 values; no `migrate`/`repoint` in the family. |
| AUDIT-21J-02 | Are the GATE 17 coordinator and ledger still the single mutation authority? | S | PASS — one ledger, one coordinator, one `.applyNoteMutation(` call site; the view model and mapper never call it. |
| AUDIT-21J-03 | Can an editor switch its `BackendId`? | S | PASS — `private val backend`; no `var backend`; no `switchBackend`; route resolves the owner from the qualified ref. |
| AUDIT-21J-04 | Is an admitted transaction's destination immutable? | S | PASS — `NoteMutationRecord.backendId` is a `val`; no `.copy(… backendId …)` anywhere in the family; the route is always the record's own owner. |
| AUDIT-21J-05 | Are `NoteRef`/`CardRef`/`DeckRef`(+collection identity) backend- and collection-qualified? | S+B | PASS — all four carry `backendId` and an optional `collectionKey`; identical numeric ids on A and B are different `stableKey`s and resolve to their own instances. |
| AUDIT-21J-06 | Is every write and recovery read routed to the exact instance? | S+B | PASS — `callBackend(backend: AnkiBackend, …)`; `routing.admit(base.backendId)`; `routeFor(record)`; a save on A produces 1 apply on A and 0 on B. |
| AUDIT-21J-07 | Does any preference/AUTO/resolver path exist in the note path? | S | PASS — none of `AnkiBackendMode`, `AnkiBackendResolver`, `AnkiBackendSelector`, `preferenceStore`, `AUTO`, `resolveBackend(` appears in the family, the view model or the mapper; the coordinator never walks the registry to find a substitute. |
| AUDIT-21J-08 | Is an unsupported operation refused rather than substituted? | S | PASS — the coordinator refuses an unadvertised dimension before the patch; the AnkiDroid adapter refuses a step its capabilities do not advertise (`ConfirmedNotApplied`). |
| AUDIT-21J-09 | Is field/model identity ever borrowed from another backend? | S | PASS — the base carries its own `noteTypeId`; field identity is the authoritative ordinal; no model listing is consulted by an edit. |
| AUDIT-21J-10 | Do tag writes follow the pinned replace-only contract? | S | PASS — `REPLACE_ONLY_CANONIFIED` declared; drafts are normalized into a whole-set patch; no `ADD_REMOVE_DELTA` path and no add/remove tag call exist in the family. |
| AUDIT-21J-11 | Are deck operations card-scoped and owner-scoped? | S+B | PASS — `CARD_ONLY` required, foreign scope refused, both deck ids compared inside the owner; a move leaves the sibling card where it was. |
| AUDIT-21J-12 | Do composite saves claim unproven atomicity? | S | PASS — the plan is an ordered operation list; atomicity is a declared per-backend property; `APPLIED` needs verification over all steps; a later failure is `AMBIGUOUS`. |
| AUDIT-21J-13 | Does collection assurance follow the locked rule, with "unknown" never a wildcard? | S | PASS — the family's facts are stated, not invented; the reconciliation claim comes from the backend's own semantics; `collectionKey == null` is never a match. |
| AUDIT-21J-14 | Is `CONFLICT` evidence-based and distinct from `AMBIGUOUS`? | S+B | PASS — conflict requires an actual drift; an unknown outcome records ambiguity; the two produce different durable statuses from different causes. |
| AUDIT-21J-15 | Is no irreversible Save issued before the durable record and intent? | S | PASS — `ledger.create` precedes `EnterMutationBoundary`, which precedes the write; one write call site; `write_not_committed` blocks with zero calls. |
| AUDIT-21J-16 | Is a possibly-applied mutation ever replayed blindly? | S+B | PASS — `retry` refuses any status other than `RETRY_ALLOWED` *before* any transition; a submitted-but-unrecorded write normalizes to `AMBIGUOUS`; a retry of the ambiguous record is refused and issues no second write. |
| AUDIT-21J-17 | Is retry permission an evidence decision? | S | PASS — `BackendConfirmedNotApplied` is the transition; reconciliation is the only automatic exit; human attestation is recorded as an attestation; connectivity is not modelled as evidence. |
| AUDIT-21J-18 | Do unresolved transactions on different backends stay independent? | S | PASS — recovery asks exactly one owner (`route(record, OP_M3, …)`); the active lookup is keyed by owner **and** note; the only record-wide pass is the ledger's own startup normalization. |
| AUDIT-21J-19 | Is a historical record without provable ownership ever guessed? | S | PASS — unknown codec keys fail closed; a corrupt snapshot is a refusal; the owner comes from the record; a missing owner is `backend_not_registered`, never a fallback; `collectionRef` defaults to `null` so legacy bytes decode. |
| AUDIT-21J-20 | Can a stale result be published into a newer editor session? | S | PASS — every suspending editor action re-checks `token != generation`; a save captures the generation it was issued under. |
| AUDIT-21J-21 | Does refresh after a mutation stay inside the confirmed owner? | S+B | PASS — refresh is the owner's own post-write read; the editor refreshes through its immutable backend; no second cache system (no `LibrarySnapshot`, no note/card/resource cache) exists in the family. The one scoped read cache in the app (`AnkiLibraryRepository.cached`, `:61`) is cleared by owner/registry disposal (`:253`), by a confirmed identity-integrity failure (`:261`), by `invalidateContext()` (`:295-299`) and by `invalidateIfBackendChanged(expected: AnkiBackendId)` (`:305-308`) — a bound-backend comparison, never a global flush. |
| AUDIT-21J-22 | Is a draft kept out of the transaction, and can discarding one delete a mutation? | S | PASS — the durable record stores metadata, never field values or tag text; the in-process payload is memory-only; an unresolved record blocks the editor instead of being dropped. |
| AUDIT-21J-23 | Does the new surface leak credentials or private content? | S | PASS — no secret token appears in the family, view model or mapper; the blocked projection carries a routing token, never a payload; refusal copy never interpolates note content. |
| AUDIT-21J-24 | Do the earlier gates keep their own authority? | S | PASS — 21H/21I routing policies, 21C identity seams and 21G transaction-recovery routing are untouched; the GATE 17 suite still passes unmodified. |
| AUDIT-21J-25 | Was any creation, migration or replay infrastructure added? | S | PASS — no such identifier exists in the family, and GATE 18's creation ledger remains separate. |

**Prior audit suite.** `Gate21jArchitectureAuditTest` (15 tests, `SRC-21J-*` numbering) also passes and is
retained: it pins the coordinator's constructor and call sites, the absence of resolver-mode names in
`core/anki/edit`, and the exact `continuationVerdict` argument list. It is *not* a mapping to the
canonical IDs; `Gate21jCanonicalAuditTest` is.

---

## §8 Authoritative verification — `VER-21J-01 … VER-21J-40`

Executed in `Gate21jCanonicalVerificationTest` (VER-01…37 + CHECKPOINT-21J-28),
`Gate21jNoteEditEnduranceTest` (38) and the full regression run (40). **39 / 39 executable IDs PASS;
VER-21J-39 BLOCKED** (no device, no real backend in this environment).

| ID | Verification | Result |
|---|---|---|
| VER-21J-01 | A note read from AnkiDroid opens an editor bound to AnkiDroid | PASS |
| VER-21J-02 | A note bound to the PC identity is never substituted by AnkiDroid | PASS (refusal, zero writes) |
| VER-21J-03 | A preference change during the edit never redirects the save | PASS |
| VER-21J-04 | An AUTO availability change cannot redirect a bound editor | PASS |
| VER-21J-05 | A Library context switch does not move the edit context | PASS |
| VER-21J-06 | Identical note ids on two backends are two different notes | PASS |
| VER-21J-07 | Colliding card and deck ids cannot be used across backends | PASS |
| VER-21J-08 | An identical numeric model id never borrows the other backend's schema | PASS |
| VER-21J-09 | A valid field edit executes through the original backend only | PASS (1 apply on A, 0 on B) |
| VER-21J-10 | A tag edit keeps replace-only semantics and the original backend | PASS |
| VER-21J-11 | A valid deck reassignment uses the source card and the owner's deck | PASS |
| VER-21J-12 | An unsupported operation is refused, never silently substituted | PASS |
| VER-21J-13 | A single-card move never moves the note's other cards | PASS |
| VER-21J-14 | Invalid field input is rejected before any mutation | PASS |
| VER-21J-15 | A verified collection mismatch blocks the write | PASS |
| VER-21J-16 | Unknown collection assurance is never upgraded to a match | PASS |
| VER-21J-17 | An external edit before the write is a conflict with nothing written | PASS |
| VER-21J-18 | A verified conflict and an uncertain write are distinct durable outcomes | PASS |
| VER-21J-19 | A `PREPARED` persistence failure causes zero backend calls | PASS |
| VER-21J-20 | No irreversible mutation before the durable submission intent | PASS |
| VER-21J-21 | A lost save response is ambiguous and is never replayed | PASS |
| VER-21J-22 | A partial composite save is never fully applied and is not replayed | PASS (`PARTIAL_OPERATION_UNKNOWN`) |
| VER-21J-23 | A confirmed save whose `APPLIED` write fails is not replayed | PASS (`applied_not_recorded` → `AMBIGUOUS`) |
| VER-21J-24 | A durable `APPLIED` refreshes only the owner's data | PASS |
| VER-21J-25 | After a restart an ambiguous record keeps owner, identity and ambiguity | PASS |
| VER-21J-26 | Retry permission comes from backend evidence, not transport recovery | PASS |
| VER-21J-27 | An unavailable original backend blocks and never fails over | PASS |
| VER-21J-28 | Reconnect alone never completes a recovery | PASS |
| VER-21J-29 | A historical transaction is never reassigned to a new backend | PASS |
| VER-21J-30 | Two unresolved backends recover independently | PASS |
| VER-21J-31 | A repeated save never produces an uncontrolled duplicate mutation | PASS (`SaveInProgress` / one transaction) |
| VER-21J-32 | An old save result cannot reach a new editor session | PASS (generation drop) |
| VER-21J-33 | A restored draft keeps its owner, note and model context | PASS |
| VER-21J-34 | Discarding an unsaved draft cannot delete an unresolved mutation | PASS |
| VER-21J-35 | A record without provable ownership is preserved and blocked | PASS |
| VER-21J-36 | A historical record with a provable owner recovers through that owner only | PASS |
| VER-21J-37 | Only the owner's read data is refreshed after a confirmed mutation | PASS |
| VER-21J-38 | 1,000-event deterministic fault injection holds every ownership invariant | PASS (§10) |
| VER-21J-39 | Verification with the real AnkiDroid and real PC backends | **BLOCKED — ENVIRONMENT** (no device; no PC backend exists; §13 R-21J-4) |
| VER-21J-40 | The full previous-gate regression suite still passes | PASS — 222 classes / 2,711 tests / 2,711 passed (§9.1) |

**CHECKPOINT 21J-28** (blocked-save projection) is executed inside this suite: a blocked save is
projected as an `EditNoteSaveState.Blocked` refusal whose copy states that the app did not switch to
another backend, with **zero** writes and **zero** records. Revision 2 recorded this as a source-audit
gap; it is now behavioural and closed.

**Cross-check on revision 2's claim set.** Every refusal token, status, cell and boundary claimed in
revision 2 is reproduced above by an executed ID; where revision 2 recorded a *gap* (blocked-save UI,
per-ID mapping) this revision closes it. Revision 2's audit-pending item (`DECISION-21J-03`) stays
rejected and is pinned here.

---

## §9 Build and regression results

**§9.1 JVM harness (executed in this sandbox).**

| Check | Command | Result |
|---|---|---|
| Main scope | `HARNESS_WORK=/tmp/h tools/jvm-harness/bin/build.sh all` | **PASS** — `main errors: 0 (files: 328)` |
| Compose closure | same | **PASS** — `compose errors: 0 (files: 24, stub files: 46)` |
| Test scope | same | **PASS** — `test errors: 0 (files: 255)` |
| GATE 21J canonical audit | `run.sh 'Gate21jCanonicalAuditTest'` | **PASS** — 1 class, 25 tests, 25 passed |
| GATE 21J canonical verification | `run.sh 'Gate21jCanonicalVerificationTest'` | **PASS** — 1 class, 38 tests, 38 passed |
| GATE 21J endurance | `run.sh 'Gate21jNoteEditEnduranceTest'` | **PASS** — 1 class, 1 test, 1 passed |
| GATE 21J suite (every 21J class: canonical audit + canonical verification + endurance + the two pre-existing 21J suites) | `run.sh 'Gate21j'` | **PASS** — 5 classes, 96 tests, 96 passed |
| **Full regression** | `run.sh` (no filter) | **PASS — `RESULT classes=222 tests=2711 passed=2711 failed=0 ignored=0 time=45.5s`, exit 0** |

Baseline at the same HEAD **before** this revision's four new test files: `classes=219 tests=2647
passed=2647 failed=0 ignored=0 time=44.0s`. This revision adds 3 classes / 64 tests (25 + 38 + 1);
2,647 + 64 = 2,711 ✔.

**§9.2 Gradle and Android — ENVIRONMENT BLOCKER (BLOCKED, not passed).** Attempted in this sandbox and
failed for environmental reasons, with first-hand evidence:

```text
$ ./gradlew testDebugUnitTest --no-daemon --offline
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

$ JAVA_HOME=<sandbox Temurin 25> ./gradlew testDebugUnitTest --no-daemon
… org.gradle.wrapper.Install.forceFetch(SourceFile:2)
Caused by: java.io.EOFException: SSL peer shut down incorrectly
           at sun.security.ssl.SSLTransport.decode(…)      ← gradle-8.7 distribution not downloadable
```

`ANDROID_HOME` / `ANDROID_SDK_ROOT` are unset, there is no Android SDK and no cached Gradle
distribution, so `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease` and
`connectedDebugAndroidTest` **could not be executed at all**. This is an environment verdict, identical
to the one GATE 21G/21H/21I recorded, and it is not evidence of a defect in the gate.

**§9.3 What the harness numbers are and are not.** They are equivalence evidence for the same source
tree under a different compiler and shim set (§2.4). They are **not** CI results, and four test classes
plus all Compose/instrumented coverage are outside the run.

---

## §10 Endurance and fault injection — VER-21J-38

`Gate21jNoteEditEnduranceTest` runs **1,000 deterministic events** (seeded LCG, `seed = 1_000_003`, so
the run is reproducible) against the colliding two-backend world, through the real
`EditNoteViewModel` + coordinator, with `runTest` and a restart (`restart()`) of the world/ledger in the
mix. Event kinds include: field/tag/deck saves on either owner, external drift before a save, faithful
applies, **refused-then-applied** retries, `Conflict`, `ConfirmedNotApplied`, lost responses
(`OutcomeUnknown`), provider exceptions, partial composites, restart with unresolved records, stale
editor sessions (save overtaken by a session reload), and human attestation of an ambiguous record.

Counters asserted to be **exactly zero** at the end (all measured zero on the passing run):

| Counter | Meaning |
|---|---|
| `crossBackendMutations` | a mutation, note ref, deck ref or route addressed to the other backend |
| `wrongNoteEdits` | an edit written without the drift check seeing another writer's value |
| `wrongDeckAssignments` | a deck write carrying a foreign ref or the wrong scope |
| `wrongModelSchemaUsage` | a field patch whose arity does not match the owner's own schema |
| `blindAmbiguousReplays` | any second write for an id whose outcome was unknown |
| `unjustifiedRetryAllowed` | `RETRY_ALLOWED` without proven non-application |
| `falseAppliedForPartialChanges` | `APPLIED` when only part of a composite landed |
| `lostUnresolvedRecords` | an unresolved record that disappeared from the ledger |
| `staleEditorContamination` | a replaced generation publishing a result (signal or `Saved` state) |
| `transactionOwnershipRewrites` | any change of a record's owning backend |

The run's own activeness assertions (`restarts ≥ 20`, applies ≥ 50) failed on the first attempt, which
exposed a real defect **in the test**, not in the code: event 9 counted an externally changed note as a
false edit when the save was legitimately refused by a pre-write pathway. The check now asserts the only
thing that matters — no write occurred past the drift check — and the endurance run passes. The incident
is recorded because a weaker assertion would have hidden the distinction between "refused before the
write" and "written anyway".

---

## §11 Invariant report (selected; INV-21J)

| ID | Invariant | Enforcement point | Evidence |
|---|---|---|---|
| INV-21J-01 | A save, retry or recovery read never reaches a backend other than the record's owner | `NoteMutationRoutingPolicy.admit/route`, `callBackend` | AUDIT-21J-06/07, VER-21J-09/27/29/36, VER-21J-38 counters |
| INV-21J-02 | The editor's backend is fixed for the edit context | `EditNoteViewModel.backend` (`val`), route resolution | AUDIT-21J-03, VER-21J-01…05 |
| INV-21J-03 | No draft is migrated, copied or re-pointed to another backend | absent behaviour + audit | AUDIT-21J-01/25 |
| INV-21J-04 | A record's owner is immutable once admitted | `NoteMutationRecord.backendId` (`val`) | AUDIT-21J-04, VER-21J-29 |
| INV-21J-05 | Identical numeric ids on two backends are different resources | `AnkiRefs` qualification | AUDIT-21J-05, VER-21J-06/07/08 |
| INV-21J-06 | No AUTO, preference or resolver participates in the note path | source audit | AUDIT-21J-07, VER-21J-03/04/05 |
| INV-21J-07 | A refusal is never a redirect | refusal vocabulary + copy | AUDIT-21J-07, CHECKPOINT-21J-28 |
| INV-21J-08 | Unknown collection evidence is never upgraded to a match | locked §3.3 via the continuation matrix | AUDIT-21J-13, VER-21J-15/16 |
| INV-21J-09 | `OP-M3` requires an authoritative match *and* the family's read | shared matrix; no family branch | AUDIT-21J-15, `DECISION-21J-03` rejected |
| INV-21J-10 | A possibly-applied mutation is never replayed | `retry` pre-check; `RecoveryNormalizedUnknown` | AUDIT-21J-16, VER-21J-21/26 |
| INV-21J-11 | `CONFLICT` requires observed drift; `AMBIGUOUS` never means "conflict" | pre-write comparison; transition table | AUDIT-21J-14, VER-21J-17/18 |
| INV-21J-12 | `APPLIED` requires verification over **all** plan operations | `NoteMutationVerifier` over the plan | AUDIT-21J-12, VER-21J-22/23 |
| INV-21J-13 | No backend write precedes the durable record and submission intent | `ledger.create` → boundary → write | AUDIT-21J-15, VER-21J-19/20 |
| INV-21J-14 | Deck writes are card-scoped and owner-scoped | plan + coordinator guards | AUDIT-21J-11, VER-21J-11/13 |
| INV-21J-15 | Tag writes are whole-set replace-only | planner normalization + pinned semantics | AUDIT-21J-10, VER-21J-10 |
| INV-21J-16 | Sibling cards of the same note are not moved by a card move | payload scope | AUDIT-21J-11, VER-21J-13 |
| INV-21J-17 | Unresolved transactions on different backends stay independent | per-record routing; owner-and-note lookup | AUDIT-21J-18, VER-21J-30 |
| INV-21J-18 | Legacy bytes without `collectionRef` decode and stay owned | additive optional field, `encodeDefaults`, strict decode | AUDIT-21J-19, VER-21J-35 |
| INV-21J-19 | An unregistered owner blocks; the record is never reassigned | `backend_not_registered` | AUDIT-21J-19, VER-21J-27/35 |
| INV-21J-20 | Only the correct scope is refreshed or invalidated | owner-scoped post-write read; `AnkiLibraryRepository.invalidateIfBackendChanged` / `invalidateContext`; no second cache | AUDIT-21J-21, VER-21J-24/37 |
| INV-21J-21 | A stale result never reaches a newer editor session | generation tokens | AUDIT-21J-20, VER-21J-32 |
| INV-21J-22 | A draft is not a transaction, and discarding a draft deletes nothing durable | record holds metadata only | AUDIT-21J-22, VER-21J-33/34 |
| INV-21J-23 | GATE 17 authority is not re-implemented or weakened | single ledger/coordinator/status vocabulary | AUDIT-21J-02/24, `NoteMutationCoordinatorTest` unchanged |
| INV-21J-24 | The gate adds no secret exposure | source audit | AUDIT-21J-23 |
| INV-21J-25 | GATE 21K's scope is untouched | source audit | AUDIT-21J-25, §13 |

---

## §12 Defects, discrepancies and remediation register

**§12.1 Discrepancies found and their resolution.**

| ID | Discrepancy | Evidence | Resolution |
|---|---|---|---|
| **D-21J-01** | Revision 2's regression figure (**2,629 / 2,629**) is not reproducible at HEAD | measured baseline at the same HEAD is **2,647 / 2,647**; after this revision's tests, 2,711 | 2,647 is the figure to quote. The 18-test gap matches the GATE 21I re-verification batch that landed on the same branch (its report records the same 2,647 full-suite result), so revision 2 most likely measured a pre-21I-correction tree. **Not a defect in the tree**: the tree is fully green. Recorded as a documentation-accounting defect in revision 2. |
| **D-21J-02** | Revision 2 declined to map the canonical `AUDIT-21J-01…25` / `VER-21J-01…40` lists, so its coverage table was thematic only | `docs/GATE_21J_NOTE_EDIT_BINDING.md` revision 2 §9, "Limitation" paragraph | **Closed by this revision**: both lists are executable IDs, one test per ID, green (§7, §8). |
| **D-21J-03** | Revision 2 §3/§7.2 under-counted its own new tests (claimed 12 + 15 + 14 = 41) | measured: `NoteMutationRoutingPolicyTest` **13**, `Gate21jNoteEditOwnershipTest` **17**, `Gate21jArchitectureAuditTest` **15** = **45** | Corrected in §5.3. No test changed; the count was wrong. |
| **D-21J-04** | `OPEN-21J-01` (21I/21C `OP-M3` contradiction) was recorded as still open | see §12.2 | **Closed** — the contradiction no longer exists at HEAD; the referring record was stale. |
| **D-21J-05** | Documentation line drift: `BACKEND_OWNERSHIP_INVARIANTS.md` §5.1 cites `Screen.kt:42,95-122` / `AppNavHost.kt:248-253` for the edit route; at HEAD the route constant is `Screen.kt:64` and the route is hosted at `AppNavHost.kt:460` | grep of current sources | Recorded, not silently rewritten: the citations are *illustrative* and the invariants they support are enforced and audited (AUDIT-21J-03/06). Owner: whoever next edits the 21B documents. |
| **D-21J-06** | Revision 2 recorded "blocked-save UI" as source-audited only (no behavioural test) | revision 2 §9 table | **Closed** by `CHECKPOINT-21J-28`, which now projects a blocked save through the real view model and asserts the copy plus zero writes and zero records. |
| **D-21J-07** | The endurance test's drift-scenario assertion was initially wrong (it counted a legitimate pre-write refusal as a false edit) | first two endurance runs failed on `wrong-note-edits: 6` | Fixed in the test to assert the invariant that actually matters (no write past the drift check); the production code was never wrong. Third run passes. |

**§12.2 `OPEN-21J-01` — audited and closed.** Revision 2 recorded that `DECISION-21I-03` permitted
`OP-M3` on nominal/continuity evidence while the shared locked matrix blocks it, and referred the item to
the 21B contract owner. Audit at HEAD shows the contradiction **does not exist any more**:

- `ReviewerActionRoutingPolicy.reconcileRoute()` (`:215-243`) delegates to
  `AnkiIdentityContinuation.continuationVerdict(operation, check, facts)` with the capability reduced to
  the record's frozen promise ∩ the backend's live claim — it no longer hand-rolls a cell.
- `GATE_21I_VERIFICATION_REPORT.md` §17 («Current re-verification and contract correction»,
  2026-10-16) records the correction, and `GATE_21I_REVIEWER_ACTION_ROUTING.md` `:86` now reads
  "DECISION-21I-03 (corrected to locked GATE 21B)".
- The only `OP_M3` decisions in production are the shared matrix, the reviewer-action family (delegating)
  and the two note-family call sites (`NoteMutationCoordinator.kt:296`,
  `AnkiStudyEffectExecutor.kt:617`) — all of which now route through the same matrix.

**Verdict:** `OPEN-21J-01` = **CLOSED (verified conforming at HEAD)**. The 21B contract lock needed no
amendment: the locked matrix was already the single decider, and 21I was conformed to it. This gate did
**not** touch 21I (AUDIT-21J-24, INV-21J-23).

**§12.3 Defects found in the implementation by this audit.** None. Twenty-five canonical audits, 39
executed verifications and a 1,000-event endurance run produced no failing invariant in the tree. The
only defects found were in *evidence* (D-21J-01…D-21J-07), and all are closed in this revision.

**§12.4 Residual gaps that are not defects.** (a) `NoteMutationCodec` downgrade is not mitigated: a
downgrade to a pre-`collectionRef` build reads the snapshot strictly but the *older* build's behaviour
toward an unknown field is outside this app's control; recorded, fail-closed on this side
(`ignoreUnknownKeys = false`, `snapshot_corrupt`). (b) The blocked-route copy is asserted in the view
model and mapper, not on a rendered screen. (c) AnkiDroid's real provider behaviour is modelled, never
observed (§9.2).

---

## §13 Residual risks and non-claims

| ID | Risk | Why it exists | Consequence under this gate | Owner |
|---|---|---|---|---|
| R-21J-1 | AnkiDroid exposes **no collection key** (`collectionKey = null`) | `AnkiDroidDeckMapper` sets none; capability matrix "Collection identity ✘" | Collection equals/mismatch can never be *proven* on the only real note backend: every write runs in the `NOMINAL` (disclosed-unverifiable) cell, and `OP-M3` is blocked for every ambiguous AnkiDroid note. | 21C (represent) / product decision |
| R-21J-2 | No real second backend exists, so no production collision can be exercised | `data/anki/remote/` has no backend class; §3.1 | The two-backend proofs are fixture proofs. | 21D / 21E once a PC backend exists |
| R-21J-3 | Reconciliation is unimplementable at this pin | `AnkiBackend.reconcileNoteMutation` default returns `Unresolved()`; `authoritativeReconciliation = false` in every existing semantics set | An ambiguous note edit can only be closed by a human attestation; the UI must say so truthfully. | 21M (disclose) / future backend |
| R-21J-4 | Device, real AnkiDroid and real PC verification did not happen | no device, no SDK, Gradle blocked (§9.2) | VER-21J-39 stays BLOCKED; no exactly-once *device* claim may be made. | CI + GATE 11/16/21N |
| R-21J-5 | Codec downgrade is unmitigated | §12.4(a) | A pre-21J build reading a 21J snapshot is undefined from this side. | 21N / release policy |
| R-21J-6 | The GATE 21E prerequisite-status wording conflict noted by 21I is untouched | `GATE_21I_VERIFICATION_REPORT.md` §17.1 | Prerequisite caveat only; 21J changed nothing in 21E. | 21N |

**Explicit non-claims.** This gate does not claim: that a note save has ever executed on a device; that
AnkiDroid's collection identity is verified; that PC Anki note editing works; that the harness numbers
equal CI numbers; that any cross-backend continuation, migration or replay is safe (it is forbidden); or
that GATE 21K is started.

---

## §14 Handoff

**To GATE 21K (Note Creation Backend Binding) — not started here.**

- Reuse, do not recreate: `NoteCreationLedger`, `NoteCreationCoordinator`, `AddNoteViewModel`,
  `AddNoteMapper`, the `add-note/{backendId}` route and its seed (`AppNavHost.kt:94` is still hard-coded
  to AnkiDroid — the seed is a **pre-binding** decision and is 21K's to make).
- Inherit the pattern proven here: one decider (locked §3.3 via `AnkiIdentityContinuation`), a typed
  refusal instead of a redirect, an immutable owner on the context and the record, and a
  could-not-have-applied vs may-have-applied split.
- `DECISION-21B-21` governs; media ownership (`AnkiMediaRef`, DECISION-21B-04) is a 21K blocker and must
  not be invented here.
- `NoteMutationCoordinator.kt:151`-style pinning still exists for creation
  (`NoteCreationCoordinator.kt:162`) — the same class of latent gap R9 recorded.

**To GATE 21M (UI indicators and diagnostics).** The refusal tokens are stable strings; the blocked-save
state (`EditNoteSaveState.Blocked`, `EditNoteBlockedMutation`) is the vocabulary an indicator should use.
Do not collapse `NOMINAL` (disclosed-unverifiable) into "not connected".

**To GATE 21N / CI.** VER-21J-39 and the whole Gradle block (§9.2) remain owed. The canonical ID suites
added here are the regression harness for the note family; `AUDIT-21J-15` in particular will fail if any
future change re-introduces a family-local `OP-M3` branch.

**Contract-lock bookkeeping.** `GATE_21B_CONTRACT_LOCK.md` needs no amendment for this gate: §21J's
required decisions are answered, the output contract holds, and R9's note half is now implemented
(creation half → 21K). `OPEN-21J-01` is closed (§12.2). No decision of this gate contradicts the lock's
12-question consistency review (Q7 editor save on another backend: no; Q10 ambiguity never a safe retry;
Q12 unevidenced legacy stays unknown).

---

## §15 Gate lock decision

```text
GATE 21J IMPLEMENTATION:      PRESENT AND COMPLETE AT HEAD — audited as found, no production change
                              required or made by this revision
GATE 21J ARCHITECTURE AUDIT:  PASS — AUDIT-21J-01 … AUDIT-21J-25 (25/25), plus SRC-21J-* (15/15)
GATE 21J AUTHORITATIVE VERIF: PASS — VER-21J-01 … VER-21J-38 + CHECKPOINT-21J-28 (39/39 executed)
                              BLOCKED — VER-21J-39 (device / real backends): ENVIRONMENT
GATE 21J REGRESSION:          PASS — 222 classes, 2,711 tests, 2,711 passed, 0 failed, 0 ignored
                              (baseline before this revision: 219 / 2,647 / 2,647)
GRADLE / ANDROID BUILD:       BLOCKED (ENVIRONMENT) — wrapper cannot download the distribution;
                              no JAVA_HOME, no Android SDK, no device
DECISION-21J-03:              REJECTED (revision 1 deviation removed; pinned by AUDIT-21J-15)
OPEN-21J-01:                  CLOSED — verified conforming at HEAD (21I delegates to the locked matrix)
GATE 21K:                     NOT STARTED
OVERALL GATE STATUS:          LOCKED — implemented, audited and harness-verified; device and Gradle
                              verification remain environmentally blocked and are recorded as such
```

**What "locked" means here.** The gate's contract is implemented in the tree and its ownership rules are
enforced by executable evidence at the canonical IDs; later sub-gates may build on it. It does **not**
mean the gate is release-verified: `VER-21J-39`, `lint`, `assembleDebug`, `assembleRelease` and
`connectedDebugAndroidTest` have never run in this environment.
