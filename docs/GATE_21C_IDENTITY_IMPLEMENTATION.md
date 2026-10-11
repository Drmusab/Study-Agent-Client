# GATE 21C — Backend-Qualified Anki Resource Identity (Implementation)

**Status:** implementation complete and harness-verified — PART V Gradle tasks `BLOCKED — environmental limitation`.
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/39e7739c-study-agent-client`
**HEAD:** `b5e249e36e81cbdecf6a0a48d1671570aa57cb71` (`b5e249e Merge pull request #65 from Drmusab/arena/e0f0b8dd-study-agent-client`)
**Working tree:** 3 new + 4 modified production files, 5 new test files (uncommitted; see PART VIII)
**Date:** 2026-10-11
**Execution type:** implementation (identity/assurance encoding only) + harness verification
**Prior gates:** GATE 21A `PASS / LOCKED`; GATE 21B `LOCKED` (contract authority)

**Deliverables in this gate**

| Artifact | Path | Contents |
|---|---|---|
| This report | `docs/GATE_21C_IDENTITY_IMPLEMENTATION.md` | PART 0, 21C-00, PART I-A, II, III, IV, V, VI, VII, VIII |
| Assurance vocabulary | `app/src/main/java/com/studyagent/client/core/anki/AnkiIdentityAssurance.kt` | 4 categories, 2 qualifiers, observations, §3.5 policy |
| Continuation matrix | `app/src/main/java/com/studyagent/client/core/anki/AnkiIdentityContinuation.kt` | §3.3 matrix, 8 operation classes, typed verdicts |
| Backend context | `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendContext.kt` | immutable context + `resourceRelation` + two projections |
| Verification | `app/src/test/java/com/studyagent/client/anki/Gate21c*.kt` (5 classes, 52 tests) | VER-21C-01…20, AUDIT-21C-01…15 |

---

# PART 0 — PREREQUISITE VALIDATION

## 0.1 Normative documents present

```text
docs/GATE_21A_DISCOVERY.md            discovery baseline + A00–A14
docs/BACKEND_IDENTITY_INVENTORY.md    per-type identity inventory with file:line
docs/GATE_21B_CONTRACT_LOCK.md        DECISION-21B-01…30, handoff §PART 4, risks, INV-21B-01…20
docs/BACKEND_IDENTITY_CONTRACT.md     LOCKED: DECISION-21B-01/02/03/04/17; §1 A1–A6; §2; §3 (3.1/3.3/3.5); §4; §5; §6
docs/BACKEND_OWNERSHIP_INVARIANTS.md  ownership/lock/durability/recovery invariants
```

`GATE 21B_CONTRACT_LOCK.md` §PART 4 states the authorization for this gate verbatim: *"21C may now do.
Encode the identity and assurance contracts of `BACKEND_IDENTITY_CONTRACT.md` (categories, resource
qualification, format compatibility) without changing routing, session or mutation behavior. Nothing
else."*

## 0.2 Unresolved normative decisions

**None.** Every identity decision this gate must encode is LOCKED: DECISION-21B-01 (canonical backend
identity), 21B-02 (collection identity), 21B-03 (assurance model), 21B-04 (resource qualification),
21B-17 (historical records/migration). 21C's own required decisions are *encoding choices*, and each is
resolved in this document (PART I-A) and recorded in §VII.4.

```text
GATE 21C: NOT BLOCKED
IMPLEMENTATION AUTHORIZED: YES
```

## 0.3 HEAD drift check (executed, not assumed)

Local clone is shallow (`depth 1`), so the 21B method was used:

```text
gh api repos/Drmusab/Study-Agent-Client/compare/236cff8...b5e249e36e81cbdecf6a0a48d1671570aa57cb71
→ status: ahead, ahead_by: 2, behind_by: 0
  commits: 9729435  docs(gate21b): lock multi-backend identity selection and ownership contracts
           b5e249e  Merge pull request #65 from Drmusab/arena/e0f0b8dd-study-agent-client
  files:   BACKEND_FAILURE_MATRIX.md +307, BACKEND_IDENTITY_CONTRACT.md +467,
           BACKEND_OWNERSHIP_INVARIANTS.md +491, BACKEND_SELECTION_POLICY.md +385,
           GATE_21B_CONTRACT_LOCK.md +1875, GATE_21B_DECISION_EVIDENCE.md +115
           (6 files added, 0 modified, 0 deleted, 0 deletions)
```

The reverse comparison (`b5e249e...236cff8`) reports `behind_by: 2, ahead_by: 0, files: []`. The two
commits ahead of the 21B baseline SHA are **documentation-only**. **No identity-relevant production
change exists between the 21B contract SHA and this HEAD** → the prerequisite of §0.1 is satisfied.

## 0.4 Rule compliance (the prompt's skip rules)

| Rule | Compliance |
|---|---|
| A proposed file that already exists → extend, never duplicate | Extended `AnkiBackendId.kt`, `AnkiRefs.kt`, `AnkiNoteModels.kt`, `AnkiTemplateModels.kt`. `AnkiMediaRef` already exists (`AnkiModels.kt:37`) and was **not** re-created (21C-10 is document-only). |
| Unnecessary change → explicit `NO CHANGE` | 9 of 19 checkpoints are `NO CHANGE` (01, 06, 07, 08, 10, 11, 14, 16, 17); 4 extend an existing file (02, 03, 09, 13); 4 create a file (04, 05, 15, 18); 1 integrates (19). Every block states its reason. |
| Do not duplicate authoritative verification cases inside file checkpoints | PART I-A cites `VER-21C-xx` / `AUDIT-21C-xx` ids; the assertions live only in PART III / PART II. |
| No out-of-scope components | No registry, resolver, AUTO, preference UI, failover, sync, migration, or new commit algorithm (AUDIT-21C-13). |
| No unused transaction component created | The only new types are the identity/assurance vocabulary and the pure §3.3 verdict function — both are named INPUT CONTRACT items of the 21B handoff for 21C. |

---

# CHECKPOINT 21C-00 — File-by-file identity inventory (post-implementation)

Every row is the *actual* file at this HEAD; `NEW` marks files added by this gate.

| # | Identity/type | Actual file (line) | Identity components | Persisted? | Used by |
|---|---|---|---|---|---|
| 1 | Backend identity | `core/anki/AnkiBackendId.kt:8` (+`:45` `AnkiBackendMode`, `:61` `identityKey`, `:77` `roundTripsStableId`, `:89` `discriminatorIsNormalized`) `NEW members` | `AnkiDroidLocal` / `PcAgent(profileId)` / `Fake(id)` + `stableId`; kind is *not* identity | Yes — inside every ledger record + route tokens | all ledgers, registry, routes, diagnostics |
| 2 | Backend kind / locality | `core/anki/AnkiBackendDescriptor.kt:11`, `:28` | `AnkiBackendKind{ANKIDROID_LOCAL,PC_AGENT,FAKE}`, `AnkiBackendLocality` | No (descriptor) | registry, capability snapshots |
| 3 | Collection identity | `core/anki/AnkiRefs.kt:15` (+`:22` `hasAuthoritativeKey` `NEW`) | `backendId` + `collectionKey?` (null = UNKNOWN, never a sentinel) | Yes (refs, records, contexts) | refs, assurance, recovery routing |
| 4 | Backend context | `core/anki/AnkiBackendContext.kt:31` `NEW` (+`:90` `AnkiSessionContext.backendContext`, `:101` `ReviewCommitRecord.backendContext`) | instance + at most one collection of that instance | Derived / `@Serializable` value; not a second store | 21D–21M handoff, `resourceRelation` |
| 5 | Card ref | `core/anki/AnkiRefs.kt:63` | backendId + cardId + noteId + cardOrd + collectionKey | Yes (records, route token) | commits, editor, routes, browser |
| 6 | Note ref | `core/anki/AnkiRefs.kt:41` | backendId + noteId + collectionKey | Yes (mutation/creation records) | edit/creation coordination |
| 7 | Deck ref | `core/anki/AnkiRefs.kt:27` | backendId + deckId + collectionKey | Yes (commit record, creation) | library, session start, creation |
| 8 | Model ref | `core/anki/AnkiNoteModels.kt:25` (+`:37` `stableKey` *changed*) | backendId + modelId + collectionKey | No (in-memory; `stableKey` unpersisted — see AUDIT-21C-04) | note creation, template refs |
| 9 | Template ref | `core/anki/AnkiTemplateModels.kt:18` (+`:33` `stableKey` *changed*) | model ref + ordinal (+ own or model's collectionKey) | No (same) | template surfaces, creation |
| 10 | Media ref | `core/anki/AnkiModels.kt:37` | URI / stream id / URL / unavailable — **no backendId** | In-memory maps + hydration dedup | hydration, resolver, scheduler card |
| 11 | Filtered-session ref | *absent* — GATE 20 `BLOCKED` | — | — | — |
| 12 | StudySession context | `core/anki/AnkiSessionContext.kt:10` (+`:35` `ReviewTurnId`, `:42` `ReviewCommitId`) | one immutable backend + collection + turn | In-memory lock (`AnkiBackendStatus.kt:140`) | scheduler, begin/end review |
| 13 | Rating mutation record | `core/anki/ReviewCommit.kt:175` | `ReviewCommitId{backendId,session,turn}` + card/deck refs + `collectionRef?` | Yes — `ReviewCommitLedgerCodec` (`ReviewCommitLedger.kt:66`, schema 4) | commit/retry/reconcile/route |
| 14 | Pruned identity | `core/anki/ReviewCommitLedger.kt:37` | `commitKey` (= persisted `stableKey`) + `backendId?`/`turnId?` | Yes (tombstones in the envelope) | duplicate-write refusal |
| 15 | Reviewer action record | `core/anki/ReviewerActionRecord.kt:106` | `backendId` + `cardRef` + `collectionRef?` + action | Yes — `ReviewerActionLedgerCodec` (`ReviewerActionLedger.kt:42`, schema 1) | reviewer-action recovery |
| 16 | Note mutation record | `core/anki/edit/NoteMutationRecord.kt:19` | `backendId` + note/card refs + target deck | Yes — `NoteMutationCodec` (`edit/NoteMutationLedger.kt:66`, v1) | edit recovery |
| 17 | Note creation record | `core/anki/create/NoteCreationRecord.kt:45` | `backendId` + model + target deck + media | Yes — `NoteCreationCodec` (`create/NoteCreationLedger.kt:68`, v1) | creation recovery |
| 18 | Navigation tokens | `ui/navigation/Screen.kt:26,61,93` | card token = 5 base64url fields incl. backend + collection; deck routes unqualified (interim rule) | Yes (SavedState/back-stack) | `AppNavHost`, card details, editor |
| 19 | Adapter mapping | `data/anki/ankidroid/AnkiDroidDeckMapper.kt:93,114` | backend-local id + `collectionKey = null` | — | AnkiDroid reads |
| 20 | Assurance vocabulary | `core/anki/AnkiIdentityAssurance.kt:28,43,58,111,136` `NEW` | 4 categories, 2 qualifiers, observations, typed check | **Never persisted** (contextual) | verdicts, diagnostics (21M) |
| 21 | Continuation vocabulary | `core/anki/AnkiIdentityContinuation.kt:23,57,83,116,132` `NEW` | 8 operation classes, 4 facts, 10 block reasons, 2 verdicts | No (pure function) | 21H–21L handoff |

Composition anchor: `ReviewCommitId.stableKey` (21C-00 row 13) equals
`<len>:<backendId.stableId><len>:<session><len>:<turn>` — asserted byte-for-byte in
`Gate21cFormatCompatibilityTest`.`a pruned identity is carried inside the persisted key…` (VER-21C-14).

---

# PART I-A — PER-FILE / PER-CHECKPOINT IMPLEMENTATION REPORT (21C-01 … 21C-19)

Every block uses the required fields. `NO CHANGE` blocks state why the locked contract requires no
edit. Assertions are cited by PART III id, never restated.

### CHECKPOINT 21C-01 — Canonical backend kind
- **FILE:** `core/anki/AnkiBackendDescriptor.kt:11,28` (read-only reference)
- **ACTION:** NO CHANGE
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-01; `BACKEND_IDENTITY_CONTRACT.md` §1 (A1–A6)
- **RESPONSIBILITY:** `AnkiBackendKind`/`AnkiBackendLocality` already exist and already require kind-locality coherence in `AnkiBackendDescriptor`; 21C must not fork them and must never use a kind as an instance.
- **IMPLEMENTATION:** none. The new vocabulary references instances (`AnkiBackendId`), never kinds; the audit test asserts neither `AnkiBackendKind` nor `AnkiBackendLocality` appears in the identity model.
- **DEPENDENCIES:** `AnkiBackendId`, `AnkiBackendDescriptor`
- **LOCAL CHECKS:** AUDIT-21C-02
- **ARCHITECTURE BOUNDARY:** registry/descriptor concern (owner 21D). Identity layer holds no kind.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-02 — Stable `AnkiBackendId`
- **FILE:** `core/anki/AnkiBackendId.kt:77,89` (extended)
- **ACTION:** EXTEND (2 read-only properties; no type, field or wire change)
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-01; §1.3 rules A3 (persistability), A5 (normalize once at allocation)
- **RESPONSIBILITY:** give the allocation authority (21D) and any writer a checkable predicate instead of a documented hope.
- **IMPLEMENTATION:** `roundTripsStableId` = `fromStableId(stableId) == this`; `discriminatorIsNormalized` = `stableId == stableId.trim()`. Both are pure reads; neither rewrites a stored value.
- **DEPENDENCIES:** existing `stableId`, `fromStableId`
- **LOCAL CHECKS:** VER-21C-02
- **ARCHITECTURE BOUNDARY:** no allocation, no persistence, no registry — predicates only.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-03 — Collection identity representation
- **FILE:** `core/anki/AnkiRefs.kt:15,22` (extended)
- **ACTION:** EXTEND (KDoc term contract + `hasAuthoritativeKey`)
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-02; §2.1 (null = UNKNOWN, no sentinel), §4.5.2
- **RESPONSIBILITY:** make "the backend actually issued a discriminator" checkable without interpreting the token.
- **IMPLEMENTATION:** `hasAuthoritativeKey = collectionKey != null` plus the documented invariant that the key is opaque, compared byte-wise, never trimmed/case-folded/sentinel-substituted and never back-filled. No new collection type was introduced (`AnkiCollectionRef` remains a rejected synonym: 0 declarations).
- **DEPENDENCIES:** `requireOptionalId`
- **LOCAL CHECKS:** VER-21C-09
- **ARCHITECTURE BOUNDARY:** identity value only; no probing, no comparison policy (that is 21C-15).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-04 — Identity assurance model
- **FILE:** `core/anki/AnkiIdentityAssurance.kt:28,43,58,136` (new, 203 lines)
- **ACTION:** CREATE
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-03; §3.1 (4 categories), §3.2 (no spontaneous downgrade), §3.5 (algorithm)
- **RESPONSIBILITY:** encode the four categories and two qualifiers as types, and implement §3.5 exactly once.
- **IMPLEMENTATION:** `AnkiUnverifiabilityQualifier{NOMINAL,DEGRADED}`; `AnkiIdentityObservation(backendId, collectionKey?)`; sealed `AnkiIdentityAssurance{AuthoritativeMatch(key), SessionContinuityVerified, IdentityUnverifiable(qualifier), AuthoritativeMismatch(bound,live)}`; `AnkiIdentityAssurancePolicy.check(bound, live, boundIsLiveInThisProcess)` returning `ForeignBackend` for two instances and `SameBackend(assuranceFor(...))` otherwise. `assuranceFor` implements the five branches of §3.5 including the LOCKED "a key appeared" → `DEGRADED` decision. Categories are contextual values: no `@Serializable`, no field on any record (AUDIT-21C-06).
- **DEPENDENCIES:** `AnkiBackendId`, `requireOptionalId`
- **LOCAL CHECKS:** VER-21C-01/08/09; AUDIT-21C-01/02/06
- **ARCHITECTURE BOUNDARY:** pure identity semantics — no I/O, no registry, no preference, no capability read, no session or mutation effect.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-05 — Backend context
- **FILE:** `core/anki/AnkiBackendContext.kt:31` (new, 102 lines)
- **ACTION:** CREATE
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §2.1; ownership invariants §1.1/§2.3
- **RESPONSIBILITY:** the single enclosing qualification for backend-bound work: one configured instance + the collection evidence it actually has.
- **IMPLEMENTATION:** `AnkiBackendContext(backendId, collection: AnkiCollectionIdentity? = null)` with `require(collection.backendId == backendId)`, `collectionKey`, `hasAuthoritativeCollectionIdentity`, `observation`, `withCollection`, and `resourceRelation(backendId, collectionKey)` = `AnkiIdentityAssurancePolicy.check(..., boundIsLiveInThisProcess = false)`. Two projections: `AnkiSessionContext.backendContext` (:90) and `ReviewCommitRecord.backendContext` (:101, derived from the record's own `collectionRef ?: card ?: deckRef`). Deliberately absent: current selection, AUTO, UI state, transport, capability snapshots.
- **DEPENDENCIES:** `AnkiIdentityAssurancePolicy`, `AnkiSessionContext`, `ReviewCommitRecord`
- **LOCAL CHECKS:** VER-21C-10/16/18
- **ARCHITECTURE BOUNDARY:** projections are read-only views over existing sources of truth; the context is *not* a second store and never assigns an owner.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-06 — Card qualification
- **FILE:** `core/anki/AnkiRefs.kt:63`
- **ACTION:** NO CHANGE
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §4.1
- **RESPONSIBILITY:** card identity = backend instance + collection + backend-local id, in equality and in `stableKey`.
- **IMPLEMENTATION:** none. `AnkiCardRef` already carries `backendId`, `collectionKey` and already composes its key from them; `identityKey` was already in use.
- **DEPENDENCIES:** —
- **LOCAL CHECKS:** VER-21C-04/11/12
- **ARCHITECTURE BOUNDARY:** refs stay value types; no adapter or persistence behaviour added.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-07 — Note qualification
- **FILE:** `core/anki/AnkiRefs.kt:41`
- **ACTION:** NO CHANGE
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §4.1; §1 (card↔note distinction)
- **RESPONSIBILITY:** note identity is not card identity, and both are backend-qualified.
- **IMPLEMENTATION:** none (already qualified, already `identityKey`-composed).
- **DEPENDENCIES:** —
- **LOCAL CHECKS:** VER-21C-05
- **ARCHITECTURE BOUNDARY:** unchanged; note refs never borrow a card's identity.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-08 — Deck qualification
- **FILE:** `core/anki/AnkiRefs.kt:27`
- **ACTION:** NO CHANGE
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §4.1; §4.3 (deck names are never identity)
- **RESPONSIBILITY:** deck identity = instance + collection + backend-local deck id; names are labels.
- **IMPLEMENTATION:** none. Deck-ref consumers (INTERIM), the library, session start and creation already carry the qualified ref; the *route* token gap is 21F's and is recorded in PART I-A 21C-17.
- **DEPENDENCIES:** —
- **LOCAL CHECKS:** VER-21C-06/17
- **ARCHITECTURE BOUNDARY:** identity unchanged; no route migration attempted here (out of scope).
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-09 — Model / template qualification
- **FILE:** `core/anki/AnkiNoteModels.kt:37`, `core/anki/AnkiTemplateModels.kt:33` (extended)
- **ACTION:** EXTEND (`stableKey` composition only)
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §4.5.3 (byte-stable composition if persisted); §2.1 (null is never a sentinel)
- **RESPONSIBILITY:** remove the `"?"` sentinel that made an unknown collection indistinguishable from a literal `"?"` key, and make both keys segment-unambiguous.
- **IMPLEMENTATION:** model `stableKey = identityKey(backendId.stableId, collectionKey, "model", modelId)`; template `stableKey = identityKey(backendId.stableId, collectionKey ?: modelRef.collectionKey, "model", modelId, "template", ordinal)` (template collection falls back to its model's, preserving the previous effective semantics). Template type/dimension/ordinal validation untouched.
- **DEPENDENCIES:** `identityKey`
- **LOCAL CHECKS:** VER-21C-07; AUDIT-21C-04 (defect found and remediated in-gate)
- **ARCHITECTURE BOUNDARY:** value-type key composition only. GATE 19's model/template subsystem remains design-only: no repository, no mapper, no UI added.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-10 — Media qualification
- **FILE:** `core/anki/AnkiModels.kt:37` (read-only reference)
- **ACTION:** NO CHANGE — documented future constraint only
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §5 row "Media file name"; contract §4.5.5 (enrichment is not identity proof)
- **RESPONSIBILITY:** record that `AnkiMediaRef` (`ContentUri`/`BackendStream`/`RemoteUrl`/`Unavailable`) is **unqualified**: it has no `backendId`, so a media name must not travel outside its creating context, and dedup (`AnkiCardHydration.mergeMedia`) must not treat equal strings from two backends as one resource.
- **IMPLEMENTATION:** none — the contract forbids implementing the media feature in 21C. A `backendId`-carrying media ref is deferred to the media owner; recorded as residual risk R-21C-02.
- **DEPENDENCIES:** `AnkiMediaResolver`, `AnkiMediaReferencePolicy`
- **LOCAL CHECKS:** AUDIT-21C-04 (documented gap, not a defect claim)
- **ARCHITECTURE BOUNDARY:** no resolver, policy or hydration edit; no serialized media type created.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-11 — Filtered-session qualification
- **FILE:** *no file* — `AnkiFilteredSessionRef` does not exist (GATE 20 `BLOCKED`)
- **ACTION:** NO CHANGE — documented future constraint only
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-22; ownership invariants §6
- **RESPONSIBILITY:** record the identity rule for Custom Study: a filtered session must name the instance + collection it was built from and must never migrate scheduler authority; "filtered session" is not a backend.
- **IMPLEMENTATION:** none (nothing to extend; inventing the type would be an unused component). Residual risk R-21C-03.
- **DEPENDENCIES:** —
- **LOCAL CHECKS:** AUDIT-21C-13 (no speculative subsystem added)
- **ARCHITECTURE BOUNDARY:** scheduler authority untouched.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-12 — Equality and hashing
- **FILE:** `core/anki/AnkiRefs.kt`, `AnkiNoteModels.kt`, `AnkiTemplateModels.kt`, `AnkiBackendContext.kt`, `AnkiIdentityAssurance.kt`, `AnkiIdentityContinuation.kt`
- **ACTION:** VERIFY EXISTING + CREATE (new types use data classes / sealed values only)
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §5 (equal ids on two backends are different resources)
- **RESPONSIBILITY:** structural equality over identity components; no hand-rolled or backend-blind equality anywhere.
- **IMPLEMENTATION:** all identity types are `data class`/`sealed` with zero `equals`/`hashCode` overrides; ordering (`collect`-time set behaviour) therefore follows the components. New collection/observation/context/vocabulary types follow the same rule.
- **DEPENDENCIES:** —
- **LOCAL CHECKS:** VER-21C-11
- **ARCHITECTURE BOUNDARY:** no custom comparators, no cross-backend translation table.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-13 — Serialization contract
- **FILE:** `core/anki/AnkiRefs.kt:15`, `AnkiNoteModels.kt`, `AnkiTemplateModels.kt`, `AnkiBackendContext.kt:31`, `AnkiSessionContext.kt:42`
- **ACTION:** VERIFY + EXTEND (new `@Serializable` value only where a durable/route boundary already needs it)
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-17; §4.5.1 (fields frozen, additions need defaults), §4.5.3
- **RESPONSIBILITY:** every serialized identity keeps its owner across a round trip; assurance is not serialized at all.
- **IMPLEMENTATION:** no field was added to, removed from or reordered in any ledger record; new optional values use defaults (`AnkiBackendContext.collection = null`, `CommitIdentityTombstone.backendId/turnId = null`). `AnkiIdentityAssurance`, `AnkiIdentityCheck`, `AnkiBackendContext`-derived assurance and the continuation vocabulary carry no `@Serializable` on the assurance side (audit-enforced).
- **DEPENDENCIES:** kotlinx.serialization (already in use)
- **LOCAL CHECKS:** VER-21C-12; AUDIT-21C-06
- **ARCHITECTURE BOUNDARY:** no codec behaviour change; both ledger codecs keep their existing version constants and failure modes.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-14 — Historical data compatibility and persistence migration safety
- **FILE:** `core/anki/ReviewCommitLedger.kt:37,66,101,210`, `ReviewerActionLedger.kt:42`, `edit/NoteMutationLedger.kt:66`, `create/NoteCreationLedger.kt:68` (read-only reference)
- **ACTION:** NO CHANGE — **migration was not required, and none was written**
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-17; §4.5.1/§4.5.2/§4.5.4; contract §6
- **RESPONSIBILITY:** prove that historical records keep their owner and that no local storage is deleted, rewritten or re-attributed.
- **IMPLEMENTATION:** none — deliberately. No schema version was bumped; legacy vocabulary normalization (`normalizeLegacyVocabulary`), the tombstone identity re-derivation (`parseTombstoneIdentity`) and the four codecs' failure modes already satisfy the contract. This gate *proved* it instead of editing it: legacy schema 1 rows keep owner + collection, absent collection stays `null`, future identity variants are `Unreadable`, corrupt snapshots never yield a default owner, tombstones re-derive their identity from the persisted key, and a record's assurance is bounded by its own evidence.
- **DEPENDENCIES:** —
- **LOCAL CHECKS:** VER-21C-14/15/16; AUDIT-21C-07/08
- **ARCHITECTURE BOUNDARY:** no storage deletion, no back-fill, no reassignment, no new migration entry point.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-15 — Typed validation helpers (no Boolean)
- **FILE:** `core/anki/AnkiIdentityAssurance.kt:111,136` + `core/anki/AnkiBackendContext.kt:77` + `core/anki/AnkiIdentityContinuation.kt:116,132` (new)
- **ACTION:** CREATE
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-03; §3.3 (matrix), §3.5 (`continuationVerdict`)
- **RESPONSIBILITY:** replace any Boolean identity answer with a typed, evidence-carrying result: MATCH / MISMATCH / UNVERIFIABLE(+qualifier) / FOREIGN, and a verdict that is either `Permitted(assurance)` or `Blocked(reason, observed)`.
- **IMPLEMENTATION:** `AnkiIdentityCheck{ForeignBackend, SameBackend(assurance)}`; `AnkiBackendContext.resourceRelation(...)` returns it for resource-level questions (`boundIsLiveInThisProcess = false`, so nominal — never continuity); `AnkiIdentityContinuation.continuationVerdict(operation, check, facts)` returns the §3.3 cell for all 8 operation classes × 5 identity outcomes, with 10 stable, content-free block reasons. `OP-R1`+degraded encodes the cell literally: permitted **only** when the caller states the backend currently offers no key (`AnkiOperationFacts.backendCurrentlyOffersNoCollectionKey`, default `false` = fail-closed). "n/a" cells (`OP-R1`+mismatch and `OP-P`+continuity) are answered as refusals, never as permissions.
- **DEPENDENCIES:** `AnkiIdentityAssurancePolicy`
- **LOCAL CHECKS:** VER-21C-08/10/18; AUDIT-21C-01/14
- **ARCHITECTURE BOUNDARY:** pure functions. No connection, probe, retry, resolution, preference, registry lookup or write; no cell grants recovery on connectivity.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-16 — Adapter compatibility
- **FILE:** `data/anki/ankidroid/AnkiDroidDeckMapper.kt:93,114`, `data/anki/ankidroid/AnkiDroidBackend.kt` (read-only reference)
- **ACTION:** NO CHANGE
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §4.5.5
- **RESPONSIBILITY:** the adapter keeps backend-local ids and invents no collection key; enrichment must not be mistaken for identity proof.
- **IMPLEMENTATION:** none. Verified: AnkiDroid maps `"10"` verbatim with `collectionKey = null`, and enrichment/dedup paths never fabricate a key.
- **DEPENDENCIES:** —
- **LOCAL CHECKS:** VER-21C-17; AUDIT-21C-09
- **ARCHITECTURE BOUNDARY:** `core/anki` stays provider-free; the adapter stays the only place provider ids are read.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-17 — Navigation identity safety
- **FILE:** `ui/navigation/Screen.kt:26,61,93` (read-only reference)
- **ACTION:** NO CHANGE — decode path verified, deck-token gap left to its owner
- **LOCKED CONTRACT REFERENCE:** DECISION-21B-04; §4.4 (qualified card/edit tokens; interim unqualified-deck rule), §5 (route reuse)
- **RESPONSIBILITY:** a route token carries exactly the identity it encoded and is never re-bound to a backend chosen at read time.
- **IMPLEMENTATION:** none. Verified: the card token is 5 base64url fields including the backend and collection; malformed tokens decode to `null` and are never guessed; deck routes stay unqualified under the LOCKED interim rule and are resolved by the AnkiDroid-pinned surface that produced them — never by a preference, registry or policy (audit-enforced). Route format change (deck token) is 21F's, and this gate did not pre-empt it.
- **DEPENDENCIES:** `AnkiCardRef`, `AnkiBackendId.fromStableId`
- **LOCAL CHECKS:** VER-21C-13; AUDIT-21C-10
- **ARCHITECTURE BOUNDARY:** no navigation, ViewModel or route-format change in this gate.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-18 — Fake fixtures (Backend A / Backend B)
- **FILE:** `app/src/test/java/com/studyagent/client/anki/Gate21c*.kt` (5 classes) + existing `anki/fake/FakeAnkiBackend.kt`
- **ACTION:** CREATE (test-only)
- **LOCKED CONTRACT REFERENCE:** contract §5; 21B handoff PART 3
- **RESPONSIBILITY:** exercise every collision the contract names, with two instances that share a kind, identical card/note/deck/model/template ids, different verified collection ids, an unknown collection, a known mismatch and legacy serialized rows.
- **IMPLEMENTATION:** Backend A = `PcAgent("pc-a")`, Backend B = `PcAgent("pc-b")` (same kind, distinct instances) plus `AnkiDroidLocal` for the adapter case; identical numeric ids across instances and across two verified collections; `collectionKey = null` as the unknown case; `AuthoritativeMismatch("collection-a","collection-b")` as the verified mismatch; legacy fixtures built by JSON surgery (schema 1 records, tombstone rows without identity fields, future identity variant, corrupt/empty snapshots, unimplemented schema versions). No fixture is production code (`FakeAnkiBackend` never appears in `main`).
- **DEPENDENCIES:** JUnit shim (`Test`/`Assert` only), kotlinx.serialization JSON
- **LOCAL CHECKS:** VER-21C-01…20 (all rows are these fixtures)
- **ARCHITECTURE BOUNDARY:** test sources only; no `androidTest` addition; no production seam introduced for testability.
- **READY FOR NEXT CHECKPOINT:** yes

### CHECKPOINT 21C-19 — Minimal integration and compilation
- **FILE:** all of the above; produced sources = 3 new + 4 extended `core/anki` files
- **ACTION:** INTEGRATE (compile-verified; no behaviour wiring)
- **LOCKED CONTRACT REFERENCE:** 21B handoff §PART 4 *"without changing routing, session or mutation behavior"*
- **RESPONSIBILITY:** make the identity vocabulary compile against the real codebase and stay wired to nothing.
- **IMPLEMENTATION:** every integration point is a pure projection or predicate: two extension properties (`AnkiSessionContext.backendContext`, `ReviewCommitRecord.backendContext`), two allocation predicates, one `hasAuthoritativeKey`, `hasAuthoritativeCollectionIdentity`, `resourceRelation`, `continuationVerdict`. No production call site was changed; no file was touched outside `core/anki` and the test tree (verified by `git status`). Harness: `main errors: 0 (files: 308)`, `compose errors: 0 (files: 24)`, `test errors: 0 (files: 223)`; suite `193 classes / 2269 tests / 0 failed`.
- **DEPENDENCIES:** none beyond the existing module graph
- **LOCAL CHECKS:** VER-21C-19/20; AUDIT-21C-12/13
- **ARCHITECTURE BOUNDARY:** zero routing, session, mutation, UI or scheduling change; no new dependency; no DI registration.
- **READY FOR NEXT CHECKPOINT:** yes — the identity foundation is ready to be *consumed* by 21D–21M.

---

# PART II — ARCHITECTURE AUDIT (AUDIT-21C-01 … AUDIT-21C-15)

Every row states the executed check. Evidence names the test class + test (assertions are in PART III)
or the exact command whose output is quoted. A row is `PASS` only because the cited check ran in this
session at this HEAD.

| ID | Audit dimension | RESULT | EVIDENCE / DEFECT / REMEDIATION |
|---|---|---|---|
| AUDIT-21C-01 | Contract conformance (categories, qualifiers, matrix, algorithm) | **PASS** | `Gate21cArchitectureAuditTest`.`the locked assurance vocabulary and operation classes are exactly what is encoded` (4 category names, 2 qualifiers, 8 operation classes, all `OP_*` codes present; no extra category). `Gate21cIdentityAssuranceTest`.`the assurance computation implements every branch of the locked algorithm` and `the strict algorithm refuses to compare two configured instances` cover the five §3.5 branches. **Defect found and remediated in-gate (D-21C-01):** `OP-R1` + degraded was answered as an unconditional refusal, while §3.3 grants a conditional permission ("only if the backend currently offers no key at all"). Remediated by `AnkiOperationFacts.backendCurrentlyOffersNoCollectionKey` (default `false`, so the unstated case still fails closed) and reason token `NEW_BINDING_BLOCKED_BY_APPEARED_KEY`. |
| AUDIT-21C-02 | Backend kind vs backend identity | **PASS** | `Gate21cArchitectureAuditTest`.`the identity model never substitutes a kind for a configured instance` — the identity files contain neither `AnkiBackendKind` nor `AnkiBackendLocality`. `Gate21cIdentityAssuranceTest`.`two configured instances can share one kind without becoming equal` (same kind, different profile ⇒ not equal, foreign on comparison). |
| AUDIT-21C-03 | Collection identity semantics (null = UNKNOWN) | **PASS** | `no collection key sentinel is ever constructed in production sources` — `grep -rn 'collectionKey *= *"' app/src/main` = **0 hits**. `Gate21cIdentityAssuranceTest`.`an unknown collection identity is never an authoritative match` + `an unknown collection key survives serialization as unknown`. `Gate21cFormatCompatibilityTest`.`a restored record's own evidence is the upper bound of the assurance it can support` (null stays null after comparisons). |
| AUDIT-21C-04 | Resource qualification of every reference type | **PASS (2 defects remediated)** | **D-21C-02:** `AnkiNoteModelRef.stableKey`/`AnkiCardTemplateRef.stableKey` composed a literal `"?"` for an unknown collection, making `collectionKey = null` indistinguishable from a literal `"?"` key (contract §2.1 forbids a sentinel). Remediated to `identityKey(...)` (`-1:` for null), asserted by `Gate21cResourceQualificationTest`.`model and template keys distinguish an absent collection from any literal key`. Non-persistence of both keys was *proved before* the change: `grep -rn 'stableKey' app/src/main` lists no reader/writer of either model/template key (only their declarations), so §4.5.3 imposed no migration. **Recorded gap (not a defect claim):** `AnkiMediaRef` has no `backendId` (21C-10, document-only), and `AnkiFilteredSessionRef` does not exist (21C-11). |
| AUDIT-21C-05 | Equality and hashing | **PASS** | `identity values are immutable and never hand-roll their equality` (no `override fun equals`/`hashCode`/`contains` in the identity files). Collision behaviour asserted per type: `identical card ids…`, `identical note ids…`, `identical deck ids…`, `identical model and template ids on two backends stay distinct`, `identical ids in two verified collections are not the same resource`. |
| AUDIT-21C-06 | Serialization safety / assurance is not state | **PASS** | `assurance is contextual evidence and is never persisted` — neither the assurance file nor `AnkiIdentityCheck`/`AnkiContinuationVerdict` is `@Serializable`, and no ledger record declares a field of those types. `Gate21cIdentityAssuranceTest`.`assurance follows the connection while the identity value does not change`. Ledger schema constants unchanged (commit 4, reviewer action 1, mutation 1, creation 1). |
| AUDIT-21C-07 | Historical record safety (owner + collection of stored rows) | **PASS** | `Gate21cIdentityPersistenceTest`.`a record is restored with the backend it was written against` (identity subtree byte-identical after re-encode), `a legacy schema record keeps its owner and its unknown collection`, `the reviewer action ledger preserves the owning backend and collection`; `Gate21cFormatCompatibilityTest`.`a pruned identity is carried inside the persisted key and survives re-encoding`. |
| AUDIT-21C-08 | Migration safety (no rewrite, no deletion, fail-closed) | **PASS** | `migration is versioned, total and parameterless beyond the snapshot itself` (no `deleteUnreadable`/`dropUnreadable`/`assignOwner`; `decode(snapshot)` only). `Gate21cIdentityPersistenceTest`.`an identity this build cannot decode is unreadable and never re-attributed`, `a corrupt snapshot fails closed instead of producing a default owner`, `an unknown collection key is never back-filled while reading`; `Gate21cFormatCompatibilityTest`.`a key that names no configured instance is unreadable and never re-attributed`, `the format refuses an unimplemented schema with a stable token`. **No migration code was written because no migration was required; no storage is deleted or bypassed.** |
| AUDIT-21C-09 | Backend adapter mapping | **PASS** | `backend adapters preserve local ids and invent no context` + `Gate21cResourceQualificationTest`.`the AnkiDroid adapter keeps backend-local ids and invents no collection key` (`mapDeckRow` returns `deckId = "10"`, `collectionKey = null`). |
| AUDIT-21C-10 | Navigation identity | **PASS** | `route decoding never consults a preference, a registry or a policy` (card-ref decode path contains no resolver/preference/registry symbol) + `Gate21cResourceQualificationTest`.`navigation tokens keep their own backend and cannot be re-bound` (5-field token round trip; deck id tokens stay unqualified). Deck-token qualification is not started (owner 21F). |
| AUDIT-21C-11 | Public API boundary / no provider, storage or transport reach | **PASS** | `the identity domain reaches no provider, storage or transport` (no `android.`/`androidx.`/`java.io.`/`com.ichi2.`/`okhttp3`/`data.`/`core.network.` symbols in the identity files) and `AnkiDomainIsolationTest` (whole-`core/anki` scan) remains green in the full suite. No AnkiDroid DB, private media store or internal scheduler access introduced. |
| AUDIT-21C-12 | Source-of-truth separation | **PASS** | `the identity module is not a second resolver, registry or settings owner`; `identity vocabulary never becomes transaction truth` (`core/study` sources reference no identity symbol); `AnkiBackendContext` projections are read-only views of `AnkiSessionContext` / `ReviewCommitRecord`. Registry still `AnkiBackendRegistry(listOf(ankiDroidBackend))` (`di/AppContainer.kt:499`), unchanged by this gate. |
| AUDIT-21C-13 | Scope discipline (no out-of-scope component) | **PASS** | `gate 21c adds no registry, resolver, auto routing or failover` — across **all** main sources: `class BackendRegistry` 0, `class BackendResolver` 0, `class AnkiBackendAutoResolver` 0, `fun resolveAuto` 0, `AUTO_RESOLUTION` 0; in the identity model: `alternateBackend`, `fallbackBackend`, `switchBackend`, `tryOtherBackend` 0; `PcAnkiBackend(` 0. Cross-checks: `AnkiCollectionRef` 0 declarations; `FakeAnkiBackend` 0 hits in `main`; `git status` shows no file touched outside `core/anki` + `src/test/java/com/studyagent/client/anki`. (**Correction recorded:** the earlier idea of banning `switchBackend` globally was dropped — it legitimately exists in three read-screen ViewModels; the ban applies to the identity model only.) |
| AUDIT-21C-14 | Transaction semantics | **PASS** | `Gate21cIdentityContinuation` verdict matrix: `a verified mismatch blocks every operation class and every cell is answered`, `an authoritative match authorizes reconciliation only with the family's own read` (`OP-M3` needs `authoritativeReconciliationAvailable`), `nominal unverifiability continues bound work but never reconciles an ambiguity`, `degraded evidence is mismatch-grade and fails closed`, `continuity is neither collection proof nor reconciliation authority`, `a foreign backend is blocked for every operation and never becomes unverifiable`, `the verdict is a pure function of its inputs and carries its evidence`. No transaction algorithm was added or changed; the function is pure and wired to nothing. |
| AUDIT-21C-15 | Privacy of identity and diagnostics | **PASS** | `identity strings carry no secret, endpoint or credential` — no `password`/`passphrase`/`secret`/`credential`/`apiKey`/`authToken`, and (whole-word) no `host`/`port`/`token`/`endpoint`/`credentials`/`http(s)://` in the identity model. Block reasons are fixed enum tokens with no interpolated data; observations carry only instance + key. |

---

# PART III — AUTHORITATIVE VERIFICATION (VER-21C-01 … VER-21C-20)

Executed with the repository harness in this session (see PART V for the exact commands).
`RESULT: PASS` means the named test ran and passed at this HEAD.

| ID | Requirement | Evidence (class · test) | RESULT |
|---|---|---|---|
| VER-21C-01 | Backend kind is separated from backend identity | `Gate21cIdentityAssuranceTest` · `two configured instances can share one kind without becoming equal`; AUDIT-21C-02 | PASS |
| VER-21C-02 | A stable backend ID survives serialization/reconstruction | `Gate21cIdentityAssuranceTest` · `a stable identity survives reconstruction from its own stable id`; `Gate21cResourceQualificationTest` · `representative identities survive a serialization round trip unchanged`; `Gate21cIdentityPersistenceTest` · `a record is restored with the backend it was written against` | PASS |
| VER-21C-03 | Two configured instances are distinct identities | `Gate21cIdentityAssuranceTest` · `two configured instances can share one kind without becoming equal`; `Gate21cIdentityPersistenceTest` · `a legacy schema record keeps its owner and its unknown collection` (other instance) | PASS |
| VER-21C-04 | Cross-backend card-ID collision | `Gate21cResourceQualificationTest` · `identical card ids on two backends are different logical cards` | PASS |
| VER-21C-05 | Cross-backend note-ID collision | `Gate21cResourceQualificationTest` · `identical note ids on two backends are different logical notes` | PASS |
| VER-21C-06 | Cross-backend deck-ID collision | `Gate21cResourceQualificationTest` · `identical deck ids on two backends are different logical decks` | PASS |
| VER-21C-07 | Cross-backend model/template-ID collision | `Gate21cResourceQualificationTest` · `identical model and template ids on two backends stay distinct`; `model and template keys distinguish an absent collection from any literal key` | PASS |
| VER-21C-08 | Verified collection mismatch | `Gate21cResourceQualificationTest` · `identical ids in two verified collections are not the same resource`; `Gate21cIdentityAssuranceTest` · `a verified mismatch blocks every operation class and every cell is answered` | PASS |
| VER-21C-09 | UNKNOWN is never upgraded | `Gate21cIdentityAssuranceTest` · `an unknown collection identity is never an authoritative match`, `an unknown collection key survives serialization as unknown`; `Gate21cIdentityPersistenceTest` · `an unknown collection key is never back-filled while reading` | PASS |
| VER-21C-10 | Assurance changes never rewrite identity | `Gate21cIdentityAssuranceTest` · `assurance follows the connection while the identity value does not change`; `Gate21cFormatCompatibilityTest` · `a restored record's own evidence is the upper bound of the assurance it can support` (identity untouched after every comparison) | PASS |
| VER-21C-11 | equals/hashCode follow the qualified identity | `Gate21cResourceQualificationTest` · all four collision tests assert `equals`, `hashCode`, `stableKey` and set cardinality; AUDIT-21C-05 | PASS |
| VER-21C-12 | Serialization round trip of representative identities | `Gate21cResourceQualificationTest` · `representative identities survive a serialization round trip unchanged` (card/note/deck refs + `AnkiBackendContext`, null key preserved) | PASS |
| VER-21C-13 | SavedState/navigation keeps identity | `Gate21cResourceQualificationTest` · `navigation tokens keep their own backend and cannot be re-bound`; AUDIT-21C-10 | PASS |
| VER-21C-14 | Legacy record with complete identity | `Gate21cIdentityPersistenceTest` · `a legacy schema record keeps its owner and its unknown collection`; `Gate21cFormatCompatibilityTest` · `a pruned identity is carried inside the persisted key and survives re-encoding`, `a tombstone written before the identity fields existed is re-derived from its key alone` | PASS |
| VER-21C-15 | Legacy/unknown identity that cannot be decoded | `Gate21cIdentityPersistenceTest` · `an identity this build cannot decode is unreadable and never re-attributed`; `Gate21cFormatCompatibilityTest` · `a key that names no configured instance is unreadable and never re-attributed`, `the format refuses an unimplemented schema with a stable token` | PASS |
| VER-21C-16 | Migration failure safety | `Gate21cIdentityPersistenceTest` · `a corrupt snapshot fails closed instead of producing a default owner`; `Gate21cFormatCompatibilityTest` · `a pruned identity conflicts only with its own instance and its own turn` | PASS |
| VER-21C-17 | Adapter identity compatibility | `Gate21cResourceQualificationTest` · `the AnkiDroid adapter keeps backend-local ids and invents no collection key`; AUDIT-21C-09 | PASS |
| VER-21C-18 | Type boundary of the identity foundation | `Gate21cResourceQualificationTest` · `a card identity that omits its backend cannot be constructed`; AUDIT-21C-06/11/12 | PASS |
| VER-21C-19 | Scope non-regression (nothing outside identity changed) | AUDIT-21C-13 (+ `git status` showing only `core/anki` + new tests; registry line intact) | PASS |
| VER-21C-20 | GATE 11–20 regression | Full harness suite: **193 classes, 2269 tests, 2269 passed, 0 failed** — the entire pre-existing 2217-test baseline is green, unmodified (no existing test file was edited to accommodate this gate) | PASS |

---

# PART IV — NON-NEGOTIABLE INVARIANTS (INV-21C-01 … INV-21C-20)

Each invariant is enforced by the code and checked by the cited row; the parenthetical is the inherited
21B invariant.

| ID | Invariant | Enforced by | Checked by |
|---|---|---|---|
| INV-21C-01 | The configured instance is never replaced by a kind, a name, a locality or a runtime object. (01) | `AnkiBackendId`; descriptors untouched | AUDIT-21C-02; VER-21C-01 |
| INV-21C-02 | An unknown collection key is never a match, a wildcard or a sentinel. (02) | `AnkiCollectionIdentity`; `assuranceFor` | VER-21C-09 |
| INV-21C-03 | Equal backend-local ids in two instances or two collections are different resources. (03) | qualified refs + `identityKey` | VER-21C-04/05/06/07/08 |
| INV-21C-04 | Assurance is contextual evidence: never persisted, never a record field, never upgraded by a reconnect. (13) | assurance types are not `@Serializable`; policy is pure | AUDIT-21C-06 |
| INV-21C-05 | `SESSION_CONTINUITY_VERIFIED` is never collection verification and never authorizes reconciliation. (13) | `IdentityUnverifiable`/`SessionContinuityVerified` split; `OP-M3` cell | VER-21C-10; AUDIT-21C-14 |
| INV-21C-06 | Degraded evidence is mismatch-grade and fails closed. (02, 13) | §3.5 branch + degraded column | AUDIT-21C-01; AUDIT-21C-14 |
| INV-21C-07 | A resource question is never answered with continuity (a resource is not the live binding). (02) | `resourceRelation(boundIsLiveInThisProcess = false)` | VER-21C-10 |
| INV-21C-08 | Two configured instances are never comparable; a foreign backend is never "unverifiable". (01) | `AnkiIdentityCheck.ForeignBackend` | VER-21C-03 |
| INV-21C-09 | Every serialized identity survives its own round trip unchanged, or is `Unreadable`. (17) | codecs + `fromStableId` returning null | VER-21C-12/14/15 |
| INV-21C-10 | A migration may rewrite vocabulary, never ownership; no record is assigned a default owner. (17) | existing normalization; no new migration | VER-21C-15/16 |
| INV-21C-11 | A durable record's assurance is bounded by its own evidence. (17) | `ReviewCommitRecord.backendContext` | VER-21C-10 |
| INV-21C-12 | A pruned identity is re-derived from the persisted key, never from a preference or a default. (17) | `parseTombstoneIdentity` | VER-21C-14 |
| INV-21C-13 | Route tokens carry exactly the identity they encoded; malformed tokens are refused, not guessed. (04) | `Screen.kt` token codec | VER-21C-13 |
| INV-21C-14 | Identity values never hand-roll equality or hashing. (04) | data classes / sealed values | VER-21C-11 |
| INV-21C-15 | `core/anki` identity reaches no provider, storage, transport or platform type. (29) | import boundary | AUDIT-21C-11 |
| INV-21C-16 | The assurance/continuation vocabulary never becomes transaction truth or a permission granted elsewhere. (19, 24) | pure vocabulary, no call sites | AUDIT-21C-12/14 |
| INV-21C-17 | No failover, AUTO, registry or resolver symbol exists in the identity model. (05, 09, 11) | absence | AUDIT-21C-13 |
| INV-21C-18 | No private AnkiDroid DB, private media storage or internal scheduler access is introduced. (28) | no new access path | AUDIT-21C-11 |
| INV-21C-19 | No identity string, reason token or diagnostic carries a secret, host, port or credential. (27) | fixed tokens | AUDIT-21C-15 |
| INV-21C-20 | GATE 21C weakens no GATE 11–20 invariant; the pre-existing suite is untouched and green. (20) | full-suite result | VER-21C-20 |

---

# PART V — BUILD AND TEST VALIDATION

## V.1 Gradle tasks — NOT executable in this environment

| Required command | Status | Reason (measured, not assumed) |
|---|---|---|
| `./gradlew testDebugUnitTest` | `BLOCKED — environmental limitation` | No Android SDK installed; Maven Central and `dl.google.com` are unreachable from this sandbox, so the Android Gradle Plugin and its dependency graph cannot be resolved. The wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) is present and untouched. |
| `./gradlew lint` | `BLOCKED — environmental limitation` | same |
| `./gradlew assembleDebug` | `BLOCKED — environmental limitation` | same |
| `./gradlew assembleRelease` | `BLOCKED — environmental limitation` | same |
| `./gradlew connectedDebugAndroidTest` | `BLOCKED — environmental limitation` | same, plus no device/emulator is available |

These are **not** claimed as PASS, and they are not omitted: a CI run of the four buildable tasks plus
`connectedDebugAndroidTest` (where configured) remains an open release-style precondition
(R-21C-06). On Windows the wrapper is invoked as `gradlew.bat`.

## V.2 Repository harness — executed in this session

The harness (`tools/jvm-harness`) compiles `app/src/main`, the GATE-16 UI closure set and
`app/src/test` against a JDK + Kotlin compiler obtained from public package indices, and runs the real
JUnit test classes. It is used because Gradle is unavailable; **it is equivalence evidence, not CI
proof** (the harness itself documents its deviations in `docs/GATE_21_MULTI_BACKEND_REPORT.md:246-292`).

```text
build:  HARNESS_WORK=<work> HARNESS_JAVA=<jdk> HARNESS_KOTLIN_JARS=<jars> \
        tools/jvm-harness/bin/build.sh all
        → main errors: 0 (files: 308)
        → compose errors: 0 [scope: GATE 16 UI closure] (files: 24, stub files: 46)
        → test errors: 0 (files: 223)

run:    HARNESS_WORK=<work> HARNESS_JAVA=<jdk> HARNESS_KOTLIN_JARS=<jars> \
        tools/jvm-harness/bin/run.sh
        → RESULT classes=193 tests=2269 passed=2269 failed=0 ignored=0 time=33.5s

baseline (same harness, HEAD before any 21C edit):
        → RESULT classes=188 tests=2217 passed=2217 failed=0 ignored=0 time=31.9s
```

Delta: **+5 classes, +52 tests, 0 failures** — of which the 5 new classes are exactly the 5
`Gate21c*` classes and the +52 tests are their assertions (14 + 10 + 7 + 6 + 15). No pre-existing test
file was modified to make this gate pass.

---

# PART VI — DEFINITION OF DONE

| # | Criterion | Result |
|---|---|---|
| 1 | Prerequisite validation executed; no normative decision invented | **MET** — PART 0 (5 docs, all decisions LOCKED; HEAD-drift check executed) |
| 2 | CHECKPOINT 21C-00 inventory complete with the required columns | **MET** — 21 rows, real file:line, persisted?, used by |
| 3 | Checkpoints 21C-01…21C-19 each reported in PART I-A format | **MET** — 19 blocks, 10 fields each |
| 4 | Every `NO CHANGE` explicitly justified | **MET** — 9 `NO CHANGE` blocks + 4 `EXTEND`/`VERIFY` justifications |
| 5 | No duplicate file/component created; existing types extended | **MET** — 3 new files (2 vocabulary + 1 context), 4 extended, 0 replaced |
| 6 | Assurance encoded as 4 categories + 2 qualifiers, no Boolean identity answer | **MET** — `AnkiIdentityAssurance`/`AnkiIdentityCheck`/`AnkiContinuationVerdict` |
| 7 | §3.3 matrix encoded cell-for-cell, both `n/a` cells fail-closed | **MET** — AUDIT-21C-01/14, D-21C-01 remediated |
| 8 | Resource qualification applied to every existing ref type | **MET** — AUDIT-21C-04 (card/note/deck verified; model/template sentinel removed) |
| 9 | Media + filtered-session recorded as document-only constraints | **MET** — 21C-10/21C-11; R-21C-02/03 |
| 10 | Historical data safety proven; no migration bypass by deleting storage | **MET** — VER-21C-14/15/16; no migration written |
| 11 | Audit matrix AUDIT-21C-01…15 with evidence, no unsupported PASS | **MET** — PART II (2 defects found & remediated are recorded, not hidden) |
| 12 | Verification matrix VER-21C-01…20, every row executed | **MET** — PART III, all `PASS`, evidence = test names from the run above |
| 13 | Invariants INV-21C-01…20 stated with enforcement + check | **MET** — PART IV |
| 14 | Build validation executed or correctly reported as blocked | **MET** — PART V: 5 Gradle tasks `BLOCKED — environmental limitation`, harness executed |
| 15 | No out-of-scope component, no UI/scheduling refactor, no cross-backend migration | **MET** — AUDIT-21C-13/19; `git status` scope |
| 16 | No secret/token/host introduced into identity or diagnostics | **MET** — AUDIT-21C-15 |
| 17 | GATE 11–20 regression unchanged | **MET** — VER-21C-20 (2217 baseline tests green) |
| 18 | Commit created only if the workflow authorizes it | **MET** — no commit created (PART VIII) |

---

# PART VII — FINAL REPORT

## VII.1 Gate result

```text
GATE 21C: PASS — backend-qualified Anki resource identity implemented and verified.
GATE 21D: NOT STARTED (out of scope; this gate stops here).
PART V Gradle tasks: BLOCKED — environmental limitation (see V.1) — not claimed as PASS.
```

The identity and assurance contracts of `BACKEND_IDENTITY_CONTRACT.md` are now encoded in the
codebase, verified by 52 executed assertions, and consume no routing, session or mutation behaviour.
Two conformance defects were found during this gate and remediated inside it (D-21C-01 §3.3 `OP-R1`
degraded cell; D-21C-02 model/template `"?"` collection sentinel).

## VII.2 Identity architecture (as implemented)

```text
one configured instance        AnkiBackendId { AnkiDroidLocal | PcAgent(profileId) | Fake(id) }
   + its collection evidence   AnkiCollectionIdentity(backendId, collectionKey?)     null = UNKNOWN
   = the resource identity     AnkiCardRef / AnkiNoteRef / AnkiDeckRef /
                               AnkiNoteModelRef / AnkiCardTemplateRef               stableKey = identityKey(...)

context (immutable)            AnkiBackendContext(backendId, collection?)           no preference, no transport,
                               ← AnkiSessionContext.backendContext                   no capability snapshot
                               ← ReviewCommitRecord.backendContext (own evidence)

assurance (contextual, never persisted)
                               AnkiIdentityAssurance { AUTHORITATIVE_MATCH | SESSION_CONTINUITY_VERIFIED |
                                                       IDENTITY_UNVERIFIABLE(NOMINAL|DEGRADED) | AUTHORITATIVE_MISMATCH }
                               computed once by AnkiIdentityAssurancePolicy (§3.5)

permission (pure)              AnkiIdentityContinuation.continuationVerdict(operation, check, facts) → §3.3
                               Permitted(assurance) | Blocked(reason, observed)
```

Layering rule enforced by audit: identity is a *value*, assurance is an *observation*, permission is a
*verdict* — and no layer may be used as another layer's truth (AUDIT-21C-06/12/14).

## VII.3 Before → after

| Concern | Before | After |
|---|---|---|
| Model/template `stableKey` | `anki:<instance>:<?|key>:model:<id>[:template:<ord>]` — a literal `"?"` made an unknown collection indistinguishable from a literal key | `identityKey(instance, collection?, "model", id[, "template", ord])` — length-prefixed, null = `-1:` |
| Unknown collection semantics | documented intent only | `hasAuthoritativeKey` / `hasAuthoritativeCollectionIdentity` predicates; sentinel construction is audit-banned (0 hits) |
| Identity assurance | absent (no typed vocabulary) | 4 categories + 2 qualifiers, one pure §3.5 function |
| Resource-vs-context question | absent | `AnkiBackendContext.resourceRelation(...)` → typed `ForeignBackend` / `SameBackend(assurance)` |
| Continuation matrix §3.3 | absent | all 8 operation classes × 5 outcomes encoded, incl. fact-gated `OP-M2`/`OP-M3`/`OP-P` cells and 10 stable block reasons |
| Allocation safety (A3/A5) | documented rules | `roundTripsStableId`, `discriminatorIsNormalized` predicates |
| Backend context | implicit inside session/record fields | `AnkiBackendContext` + two read-only projections |
| Verification | 0 identity tests | 5 classes / 52 tests; suite 188→193 classes, 2217→2269 tests, 0 failures |
| Routing/session/mutation behaviour | — | **unchanged** (no production call site edited) |

## VII.4 File-by-file report and necessity categories

Full per-file detail is PART I-A; the identity inventory is CHECKPOINT 21C-00.

| File | Change | Necessity category |
|---|---|---|
| `core/anki/AnkiIdentityAssurance.kt` (new) | assurance vocabulary + §3.5 policy | Resource identity correctness |
| `core/anki/AnkiIdentityContinuation.kt` (new) | §3.3 matrix + typed verdicts | Resource identity correctness |
| `core/anki/AnkiBackendContext.kt` (new) | immutable context + projections + `resourceRelation` | Type compatibility (adapts existing session/record types without changing them) + Resource identity correctness |
| `core/anki/AnkiNoteModels.kt` (extended) | `stableKey` sentinel removal | Resource identity correctness |
| `core/anki/AnkiTemplateModels.kt` (extended) | `stableKey` sentinel removal (effective collection preserved) | Resource identity correctness |
| `core/anki/AnkiBackendId.kt` (extended) | A3/A5 predicates | Persisted data compatibility (guards durable ownership) |
| `core/anki/AnkiRefs.kt` (extended) | collection-key term contract + `hasAuthoritativeKey` | Resource identity correctness |
| `src/test/.../Gate21c*.kt` (5 new) | verification + audit | (test-only) |

**No file required a Serialization-compatibility or Persisted-data-compatibility *edit*:** no field,
version, codec behaviour or stored byte was changed; both compatibility categories are satisfied by
*proof* rather than by modification (VER-21C-12/14/15/16).

## VII.5 Historical data safety report

| Question | Answer | Evidence |
|---|---|---|
| Do existing records still decode? | Yes — schema 1–4 normalize forward, owner preserved | VER-21C-14 |
| Is any stored owner reassigned? | No — a record's backend context comes from its own evidence | VER-21C-10/14 |
| Is an absent collection key back-filled? | No — `null` stays `null` through read, decode and re-encode | VER-21C-09 |
| What happens to an undecodable identity? | `Unreadable`/`Required` refusal for that record only; no neighbour owner, no default owner | VER-21C-15/16 |
| Is migration code needed? | No. The frozen field sets, the vocabulary normalization and the tombstone key re-derivation already satisfy §4.5; adding a migration would have rewritten bytes that must stay stable | AUDIT-21C-08, 21B DECISION-21B-17 |
| Was local storage deleted or reset to force a pass? | **No** — explicitly forbidden; not done | AUDIT-21C-08 |
| Are pruned identities still safe? | Yes — re-derived from the persisted length-prefixed key; a foreign instance's key resolves to its own owner; unparsable keys are `unreadable_tombstone_identity`; same-(instance, turn) collisions are refused | VER-21C-14/16 |

## VII.6 Audit matrix, verification matrix, invariant report

References: **PART II** (AUDIT-21C-01…15 — 15 PASS, 2 remediated defects, 0 FAIL, 0 unsupported PASS),
**PART III** (VER-21C-01…20 — 20 PASS, each with an executed test), **PART IV**
(INV-21C-01…20 — each with enforcement point and check).

## VII.7 Build results

PART V. Summary: 5 Gradle tasks `BLOCKED — environmental limitation`; harness `build.sh all` →
`0 errors` in all three scopes; harness suite → `193 classes / 2269 tests / 0 failed` against a
`188 / 2217 / 0` baseline.

## VII.8 Residual risks

| ID | Risk | Owner / disposition |
|---|---|---|
| R-21C-01 | AnkiDroid never produces a collection key, so `AUTHORITATIVE_MATCH`/`AUTHORITATIVE_MISMATCH` and `DEGRADED` are unreachable in production today; only `NOMINAL` (± continuity) is. No code or test claims otherwise. | recorded residual risk (inherits 21B) — closed only by a key-producing backend (21D/21H) |
| R-21C-02 | `AnkiMediaRef` remains unqualified; a media name must not cross a backend boundary, and hydration dedup is not identity proof. | owner 21F/21M |
| R-21C-03 | `AnkiFilteredSessionRef` does not exist; Custom Study identity is a design constraint only. | owner 21L |
| R-21C-04 | The §3.3 `OP-R1` degraded cell depends on a caller-supplied observation (`backendCurrentlyOffersNoCollectionKey`, default `false` = refuse). A caller that omits it fails closed rather than permitting. | 21H–21L must pass it deliberately |
| R-21C-05 | Two AnkiDroid profiles on one device remain undetectable; the identity foundation neither detects nor claims to. | recorded in contract §5 |
| R-21C-06 | Gradle `testDebugUnitTest`/`lint`/`assembleDebug`/`assembleRelease`/`connectedDebugAndroidTest` have not been executed in any environment available to this gate. | must run in CI before release-style claims |
| R-21C-07 | The matrix's two `n/a` cells (`OP-R1` + `AUTHORITATIVE_MISMATCH`, `OP-P` + `SESSION_CONTINUITY_VERIFIED`) are answered as refusals — a deliberate fail-closed reading of unreachable combinations. | recorded for 21N review |
| R-21C-08 | Model/template `stableKey` composition changed. Proven unpersisted (no production reader/writer; 0 references outside their declarations), so no migration was required; an out-of-repo consumer persisting that string would need its own migration. | documented (§4.5.3 terms) |

## VII.9 Downstream handoff

| Sub-gate | What it consumes from 21C |
|---|---|
| 21D Registry/availability | `AnkiBackendId.roundTripsStableId` / `discriminatorIsNormalized` at allocation; `AnkiBackendDescriptor` unchanged; `AnkiBackendContext` as the binding shape |
| 21E Preference/resolver | nothing operational: 21C added no resolution path and did not define `BindingVerdict` as a resolution result |
| 21F Read contexts | collection-key semantics (null = UNKNOWN) for cache/query keys; **owner of the deck-token qualification** and of the unqualified `AnkiMediaRef` |
| 21G StudySession lock | `AnkiSessionContext.backendContext` for the immutable owner; assurance matrix for `OP-S1`/`OP-S2` |
| 21H–21L Recovery families | `AnkiIdentityContinuation.continuationVerdict` + `AnkiOperationFacts` (per-family facts, incl. R-21C-04); `ReviewCommitRecord.backendContext` for "what it was written against" |
| 21M Diagnostics/UI | category tokens verbatim (`AUTHORITATIVE_MATCH`, `SESSION_CONTINUITY_VERIFIED`, `IDENTITY_UNVERIFIABLE(nominal|degraded)`, `AUTHORITATIVE_MISMATCH`, `ForeignBackend`); never render continuity as "same collection"; never print keys or secrets |
| 21N Final audit | this document + the 5 `Gate21c*` test classes as the acceptance surface of the identity foundation |

## VII.10 Gate lock decision

```text
GATE 21C: LOCKED (identity foundation)
LOCKED SCOPE: backend identity encoding, collection identity semantics, resource qualification,
              identity assurance, backend context, §3.3 continuation vocabulary, format compatibility.
NOT LOCKED HERE (still open by design): deck-token qualification (21F), media/filtered-session
              identity (21F/21L), registry/availability (21D), resolution policy (21E),
              session durability (21G), recovery family facts (21H–21L), UI wording (21M).
```

---

# PART VIII — GATE LOCK

- **Suggested commit message:** `feat(gate21c): add backend-qualified Anki resource identities`
- **Commit created:** **no.** The established workflow for this session records that commit creation is
  not authorized by the gate itself (GATE 21B recorded the same); the working tree is left for the
  branch owner to commit, and nothing was pushed.
- **Files in the change set:** 3 new + 4 modified `app/src/main/java/com/studyagent/client/core/anki/`
  sources, 5 new `app/src/test/java/com/studyagent/client/anki/Gate21c*.kt` test classes, this document.
- **Do not proceed to GATE 21D from this gate.** PART V's Gradle execution and R-21C-06 remain the only
  open release-style precondition.
