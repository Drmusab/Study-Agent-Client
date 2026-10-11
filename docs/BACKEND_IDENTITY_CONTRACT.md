# Backend Identity Contract

**Gate:** GATE 21B — Backend Contract Resolution & Architecture Lock (Sub-Gate 21B of 21A–21N)
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/e0f0b8dd-study-agent-client`
**HEAD:** `236cff8f2733203ccacbc479a1463b283fcf2ecf`
**Date:** 2026-10-11
**Status:** LOCKED — normative contract. **No production code was changed to produce this document.**
**Prerequisite:** GATE 21A PASS/LOCKED (`docs/GATE_21A_DISCOVERY.md`, HEAD `2d2a0a0` = first parent of this HEAD; the delta contains only `docs/` changes).
**Companion documents:** `GATE_21B_CONTRACT_LOCK.md` (decisions, audit, verification, handoff),
`BACKEND_SELECTION_POLICY.md`, `BACKEND_OWNERSHIP_INVARIANTS.md`, `BACKEND_FAILURE_MATRIX.md`,
`GATE_21B_DECISION_EVIDENCE.md`.

This document owns **DECISION-21B-01** (backend identity), **DECISION-21B-02** (collection identity),
**DECISION-21B-03** (identity assurance), **DECISION-21B-04** (resource qualification), and
**DECISION-21B-17** (historical records and migration). Rationale and rejected alternatives live in
`GATE_21B_CONTRACT_LOCK.md`; this file states the contract itself.

---

## 0. How to read this contract

Three kinds of statement appear below, always labelled:

| Label | Meaning |
|---|---|
| **IMPLEMENTED** | Exercised by production source at this HEAD, with file:line and (where noted) a test that pins it. |
| **LOCKED** | Normative policy for Sub-Gates 21C–21N. It is binding even where no code implements it yet. |
| **FUTURE CONTRACT** | A required contract for a component that does not exist yet (PC backend, media ref, filtered session). It is a specification, **not** a claim about shipped behavior. |

Everything marked IMPLEMENTED in this document was re-verified against source by GATE 21B itself, not
accepted from the 21A report on trust. GATE 21A's claims were confirmed accurate at this HEAD
(`docs/GATE_21B_CONTRACT_LOCK.md` §1.4).

Vocabulary used throughout: **configured backend instance**, **backend kind**, **backend runtime object**,
**display name** — these are four different things and are never interchangeable.

---

## 1. Backend identity

### 1.1 The four concepts, kept apart

| Concept | What it is | Identity? | Example |
|---|---|---|---|
| Backend **kind** | Which family of implementation a backend belongs to. Metadata. | **Never** | `PC_AGENT` |
| Configured backend **instance** | One configured, addressable backend the application may use. | **Yes — this is `AnkiBackendId`** | `PcAgent("pc-a")` |
| Backend **runtime object** | A live object in the process (`AnkiBackend` implementation) that serves one instance. | No — object identity ≠ logical identity | `AnkiDroidBackend@7f3c` |
| Backend **display name** | A user-facing label. | **Never** | "AnkiDroid" / "Study PC" |

**LOCKED (INV-21B-01).** Configuring two instances of one kind produces two identities. The contract
never assumes that "there is only one PC today" makes backend identity globally unique forever; the
one-instance situation is a *current configuration*, not a structural guarantee.

### 1.2 Canonical form (IMPLEMENTED)

`AnkiBackendId` — `app/src/main/java/com/studyagent/client/core/anki/AnkiBackendId.kt:8`

```kotlin
@Serializable sealed interface AnkiBackendId { val stableId: String
    @SerialName("ankidroid_local") data object AnkiDroidLocal // "ankidroid_local"
    @SerialName("pc_agent")        data class PcAgent(val profileId: String) // "pc_agent:<profileId>"
    @SerialName("fake")            data class Fake(val id: String = "fake")  // "fake:<id>"
}
```

Verified properties at this HEAD:

| Property | Evidence |
|---|---|
| Kind-qualified namespace. The `stableId` prefix is the namespace delimiter (`ankidroid_local`, `pc_agent:`, `fake:`). | `AnkiBackendId.kt:12,19,25` |
| Round-trips: `fromStableId(String?)` returns the identity or `null` for anything unrecognized — never a default. | `AnkiBackendId.kt:32-42` |
| Blank discriminators refused at construction. | `AnkiBackendId.kt:20,26` |
| Serializable; the same token is used on disk, in routes and in diagnostics. | `AnkiBackendId.kt:8`, `Screen.kt:57`, `DiagnosticsRepository.kt:597` |
| `AnkiBackendKind` / `AnkiBackendLocality` are derived metadata; a descriptor cannot carry a kind contradicting its identity. | `AnkiBackendDescriptor.kt:34,41,67` |
| Duplicate identities cannot both be registered. | `AnkiBackendSelector.kt:12` — `require(byId.size == backends.size) { "Duplicate logical backend identity" }` |
| `AnkiBackendMode.accepts` never accepts `Fake`. | `AnkiBackendId.kt:49-51` |
| Display labels are one-way and never parsed back into identity. | `AnkiBackendUiModels.kt:56` |

`stableId` is the **canonical wire/disk form** of backend identity. Structural Kotlin equality is the
in-memory form. Both are equivalent for the variants above; no other form is authorized.

### 1.3 Identity creation and allocation (FUTURE CONTRACT — owner: 21D)

No production code constructs `PcAgent(...)`: `grep -rn "PcAgent(" app/src/main` returns only the
declaration at `AnkiBackendId.kt:19`. There is therefore **no allocation policy to audit today**, and the
absence of one is recorded as a blocking prerequisite of this contract rather than papered over
(GATE 21A Safety-Critical Unknown #2).

**LOCKED.** Backend identity is minted by exactly one allocation authority at configuration time and
persisted with the configuration record **before** the instance may be registered. The following rules
bind that authority when it is written (21D):

| # | Rule |
|---|---|
| A1 | The discriminator of a `PcAgent` identity identifies a **configured backend instance**, not a display label and not the raw Anki profile name. |
| A2 | The discriminator must be globally unique across every configuration the app can hold at once — including two different PCs whose desktop Anki both use a profile literally named e.g. `User 1`. A bare profile name is therefore **not** a valid discriminator on its own. |
| A3 | The discriminator must be derivable from persisted configuration alone, so the same instance reconstructs the same identity after process death and after app upgrade. |
| A4 | Connection facts (endpoint, host, port, socket id, session token, connection timestamp, IP) must **never** participate in identity. They are not stable and they are not secrets-free. |
| A5 | Non-ASCII/whitespace edge cases: the discriminator is normalized once at allocation (trim; refuse blank — already enforced by `AnkiBackendId.kt:20`) and never re-normalized at read time, so a stored identity always round-trips byte-identically through `stableId`. |
| A6 | A display name change is not an identity change. Two configurations that differ only by label are two identities; one configuration whose label changed keeps one identity. |

### 1.4 Stability across restart, configuration change, re-registration, removal

| Situation | LOCKED outcome |
|---|---|
| **Restart** | Identity is reconstructed from persisted configuration and/or persisted records. It is never reconstructed from runtime observation. (IMPLEMENTED for durable records: `AnkiBackendId` is `@Serializable` inside every ledger envelope; `fromStableId` round-trips.) |
| **Label change** | Same identity. Ledgers, routes and locks keep referring to the same owner. |
| **Endpoint change on the same configuration record** (e.g. the agent moved to a new address) | Same **logical identity**, because identity is configuration-record-scoped (A4). It is **not** identity continuity of the *collection*: collection assurance must be re-established (see §3). Re-pointing is a configuration change, not a new backend. |
| **Discriminator change** (any edit that changes `profileId`, or deleting + re-adding under a different discriminator) | **New identity.** The old identity's unresolved transaction records belong to the old identity and do not follow it. |
| **Re-registration of the same identity** | Idempotent: the same instance is present once. A second live object claiming the same identity is refused by the registry (`AnkiBackendSelector.kt:12`) and surfaces as `ConfigurationInvalid` — never "last one wins". |
| **Removal** | The instance disappears from the registry. Durable records owned by it become `Blocked(BACKEND_NOT_REGISTERED)` (`AnkiTransactionRecoveryRouting.kt`) — blocked, never reassigned. |
| **Removal + recreation under the same discriminator** | Treated as the **same logical owner**, so its unresolved records are eligible for recovery again — **but** collection continuity must be re-established first (§3.5). Recreating the configuration does not prove the collection is the same one. |
| **Removal + recreation under a different discriminator** | A different owner. The previous identity's records stay blocked/orphaned until that identity is configured again. |

**LOCKED (INV-21B-01).** Two distinct configured backend instances must never receive interchangeable
identities. Interchangeability is prevented at three levels: allocation-time uniqueness (A2),
registration-time duplicate refusal (`AnkiBackendSelector.kt:12`), and identity-in-stable-key resource
qualification (§4).

### 1.5 Collision handling (LOCKED)

| Collision | Handling |
|---|---|
| Two configured instances allocated the same discriminator | **Configuration-time refusal** (allocation authority must reject) → `ConfigurationInvalid`. The user is told which configurations collide. Never auto-renumbered, never "first registered wins", never silently merged into one identity. |
| Two runtime objects claiming one identity | Registry `require` fails → the composition root cannot build a valid registry → `ConfigurationInvalid`. Fail-closed at startup is the intended behavior. |
| An identity that fails to decode (`fromStableId` → `null`) | Treated as *unknown owner*. Persisted records that carry it fail decoding as `Unreadable`; they are never re-attributed (see §6). |
| A `Fake` identity presented to a real mode | Ineligible by construction (`accepts`), so it cannot be selected, locked or persisted by production routing. Test doubles may exist only in test configuration. |

### 1.6 Legacy identity compatibility (LOCKED)

1. **Already-persisted `stableId` strings are immutable.** `ankidroid_local`, `pc_agent:<x>`, `fake:<x>` must keep decoding exactly as they do today, forever.
2. **No rewriting of persisted identity to a "better" scheme.** A future, finer identity scheme (for example a scheme that adds a machine discriminator) must be an **additive variant** with a new namespace prefix. Records already written under the coarser identity keep that coarser owner, and the coarser variant must remain decodable even after the finer variant exists.
3. **No inference.** A record whose identity cannot be decoded is `Unreadable`, never remapped to a neighbouring identity and never to the current preference.
4. **Tombstone repair is the only sanctioned derivation**, and it is a *decode*, not a guess: `CommitIdentityTombstone.backendId` is nullable, and when absent the value is derived from the identity embedded in `commitKey` (`ReviewCommitLedger.kt:138-145, 210-216`). If it cannot be derived, the read fails (`unreadable_tombstone_identity`). No historical repair may consult the registry, the preference or the resolver.

---

## 2. Collection identity

### 2.1 Representation (IMPLEMENTED)

`AnkiCollectionIdentity(backendId, collectionKey: String?)` — `core/anki/AnkiRefs.kt:7`,
with the doc comment *"Backend-issued collection discriminator. Null means unknown, never an invented
default."*

**LOCKED (INV-21B-02).** `collectionKey == null` means **UNKNOWN**. It is never:

- normalized to a sentinel such as `"default"`, `""`, `"unknown"`;
- treated as a wildcard that matches any other key;
- compared against another `null` as if that were an equality proof;
- back-filled by the client.

`AnkiSessionContext` may only hold a collection belonging to its own backend
(`AnkiSessionContext.kt:26`) — a type check. It is **not** a collection check, and must never be
described as one.

### 2.2 What may never constitute collection identity (LOCKED)

The following are **prohibited** as evidence of collection identity or collection equivalence, in
routing, recovery, caching, display or diagnostics:

```text
deck names               matching card IDs          matching note IDs
card counts              similar card counts        model/template names
connection endpoint      agent profile id alone     "the user said it is the same"
sync state (none exists)  timestamps                 same backendId
```

`backendId` proves *which configured backend* is in play. It proves nothing about *which collection*
that backend currently has open.

### 2.3 Per-backend availability at this HEAD (IMPLEMENTED)

| Backend | Authoritative `collectionKey` | Classification | Evidence |
|---|---|---|---|
| AnkiDroid | Never produced. Decks are mapped with `collectionKey = null`; card/note refs inherit the request key, which comes from those decks. | **UNAVAILABLE** | `AnkiDroidDeckMapper.kt:93,114`; `AnkiDroidCardMapper.kt:103,136,176` (re-verified) |
| PC agent | No implementation exists. | **UNKNOWN** | `grep -rn "PcAnkiBackend" app/src` → 0 production matches |
| `FakeAnkiBackend` | Whatever a fixture sets. | **TEST_ONLY / HEURISTIC** | `app/src/test/.../fake/FakeAnkiBackend.kt` |

Consequences that the contract takes as given rather than wishing away:

- An AnkiDroid profile switch while the app is alive is **undetectable** by this code:
  `collectionMatchesBoundSession` returns `true` when the bound key is null
  (`AnkiDroidBackend.kt:1336-1341`, re-verified).
- `AnkiTransactionRecoveryPlanner.route` reports `COLLECTION_MISMATCH` only when **both** the recorded
  key and the live key are non-null (`AnkiTransactionRecoveryRouting.kt:71`, re-verified). With
  AnkiDroid that branch is currently unreachable.
- No sync state, no collection id, no profile id and no cross-backend comparison exists anywhere.
  `"the two collections are synchronized"` is **unprovable in this repository** and must never be
  asserted by the application.

### 2.4 Continuation conditions (LOCKED)

| Situation | Contract |
|---|---|
| Authoritative identity **matches** (both keys known and equal) | Continuation permitted. Conditions: same `backendId`, same key, required capability set still satisfied, and the operation's own binding (session lock / record) is intact. |
| Authoritative identity **mismatches** (both keys known and unequal) | **Fail-closed.** New bindings are refused; existing bound work is blocked, not migrated. Durable transactions route to `Blocked(COLLECTION_MISMATCH)`. A read context is invalidated and must be re-bound explicitly. |
| Collection identity **unavailable** (nominal — backend never had one) | Operation-specific: see the assurance matrix §3.3. Mutation work proceeds under the *same* recorded category as the binding that admitted it; no operation may claim collection verification, and diagnostics must show the category. |
| Identity **cannot be checked after reconnect** | Recovery restrictions apply: reconnect never upgrades assurance, and an ambiguous mutation stays ambiguous (see §3.4 and `BACKEND_FAILURE_MATRIX.md`). |
| Backend instance **recreated** | Identity-continuity rules of §1.4 apply, and collection continuity must be re-established before any bound work or recovery resumes. |

---

## 3. Identity assurance model (LOCKED — conceptual categories)

These are **semantic contract categories**, not mandatory Kotlin enum names. 21C chooses the encoding.

### 3.1 Categories

| Category | Definition | Precondition |
|---|---|---|
| `AUTHORITATIVE_MATCH` | Both the binding/record and the live backend present a collection key, and they are equal. | A key exists on both sides. |
| `SESSION_CONTINUITY_VERIFIED` | No authoritative key exists on either side, **and** the binding is the same live in-process binding on the same `backendId`, with no evidence of a collection change or rebind. | Binding alive in this process. |
| `IDENTITY_UNVERIFIABLE` | The backend cannot present a collection key, so equality can be neither proven nor disproven. Two sub-qualifiers: **(a) nominal** — this backend never produced a key for this binding, so no verification was ever promised; **(b) degraded** — a key was known or expected for this binding and is now unavailable. | (a) key never produced; (b) key lost / unreadable after reconnect or backend replacement. |
| `AUTHORITATIVE_MISMATCH` | Both keys known and unequal. | Both keys non-null. |

**LOCKED.** `SESSION_CONTINUITY_VERIFIED` is **not** collection verification. It says only: *this
process still holds the binding it created, on one backend identity*. It must never be rendered,
logged or reasoned about as "same collection".

**LOCKED.** `IDENTITY_UNVERIFIABLE` is never described as verified. Diagnostics, UI and logs use the
category token verbatim.

### 3.2 No spontaneous downgrade (LOCKED — the exact safety boundary)

```
A binding whose assurance is AUTHORITATIVE_MATCH ...
  ... may NOT continue when the key becomes unavailable or unequal.

A binding created while the backend had no key (nominal UNVERIFIABLE) ...
  ... CONTINUES under that recorded category, and that category is never upgraded by a reconnect.
```

Rationale: for AnkiDroid there is no key at all, ever, so treating nominal unverifiability as blocking
would make the only real backend unusable — and it would not add safety, because nothing was ever
promised. Treating a *lost* key as unverifiability, by contrast, would silently convert "we could tell
before" into "we cannot tell now", which is exactly how a collection swap becomes invisible. Degradation
is therefore **mismatch-grade** and fails closed.

### 3.3 Operation-by-assurance decision matrix (LOCKED)

Operation classes used here are defined once and reused in `BACKEND_OWNERSHIP_INVARIANTS.md` and
`BACKEND_FAILURE_MATRIX.md`:

| Code | Operation class |
|---|---|
| `OP-R1` | New unbound read (no prior identity to honour) |
| `OP-R2` | Existing bound read (Library row → details, editor base, open browser context) |
| `OP-S1` | New StudySession (binding) |
| `OP-S2` | Existing StudySession continuation (scheduler ops: next card, hydrate, bury/suspend/flag, end) |
| `OP-M1` | New mutation admitted inside a bound context (rating, reviewer action, note edit, creation submit) |
| `OP-M2` | Retry of a known **non-applied** mutation |
| `OP-M3` | Reconciliation of an **ambiguous** mutation |
| `OP-P` | Recovery after process death (durable record → original backend) |

| Operation class | `AUTHORITATIVE_MATCH` | `SESSION_CONTINUITY_VERIFIED` | `IDENTITY_UNVERIFIABLE` (nominal) | `IDENTITY_UNVERIFIABLE` (degraded) | `AUTHORITATIVE_MISMATCH` |
|---|---|---|---|---|---|
| `OP-R1` | Permitted | Permitted (a new binding may be created here) | Permitted; the result records the category it actually had | Permitted for a **new** binding only if the backend currently offers no key at all; otherwise blocked | n/a — no prior binding exists to contradict |
| `OP-R2` | Permitted | Permitted | Permitted; result carries category; may not be cited as collection evidence | **Blocked** — invalidate and re-bind explicitly | **Blocked** — invalidate, never reinterpret refs |
| `OP-S1` | Permitted; session records `AUTHORITATIVE_MATCH` | Permitted; session records `SESSION_CONTINUITY_VERIFIED` | Permitted; session records `IDENTITY_UNVERIFIABLE` and diagnostics show it | **Blocked** | **Blocked** |
| `OP-S2` | Permitted | Permitted | Permitted while the session's recorded category is nominal and no downgrade occurred | **Blocked** — pause/surface, never fail over | **Blocked** — pause/surface, never fail over |
| `OP-M1` | Permitted | Permitted | Permitted under the session's/editor's recorded category; never described as verified | **Blocked** | **Blocked** |
| `OP-M2` | Permitted | Permitted only if the family's non-application proof does not depend on collection identity | Same condition as the previous column; connectivity alone is never proof | **Blocked** | **Blocked** |
| `OP-M3` | Permitted **only** if the family exposes authoritative reconciliation *and* the match is authoritative; otherwise blocked | **Blocked** — continuity cannot establish whether the mutation applied | **Blocked** | **Blocked** | **Blocked** |
| `OP-P` | Permitted | n/a — after process death there is no in-process binding; the durable-record rules apply | Permitted for the families whose recovery rule does not require collection proof (all four today); category recorded | **Blocked** | **Blocked** |

**LOCKED.** A new successful network connection, a fresh health probe, a foreground refresh or an
agent handshake **never** changes a category in this matrix. Mutation recovery is never granted on
connectivity.

### 3.4 Which columns are reachable today

| Backend | Reachable categories today |
|---|---|
| AnkiDroid | `IDENTITY_UNVERIFIABLE` (nominal) only. `SESSION_CONTINUITY_VERIFIED` is an *additional* in-process statement layered on top for live sessions; `AUTHORITATIVE_*` are unreachable because no key is ever produced. |
| PC agent | None — no implementation. All columns become reachable only when 21D/21H implement the backend and its collection reporting. |

This asymmetry is a **residual risk**, recorded in `GATE_21B_CONTRACT_LOCK.md` §6, not a design gap to
be closed by inventing a key.

### 3.5 Assurance computation (LOCKED algorithm, encoding-free)

```text
assuranceFor(binding, liveBackend):
    require binding.backendId == liveBackend.id            # otherwise: foreign binding (structural defect)

    bindingKey = binding.collectionKey                     # may be null
    liveKey    = liveBackend.currentCollectionKey()         # may be null

    if bindingKey != null && liveKey != null:
        return AUTHORITATIVE_MATCH if bindingKey == liveKey else AUTHORITATIVE_MISMATCH

    if bindingKey != null && liveKey == null:
        return IDENTITY_UNVERIFIABLE(degraded)              # we could tell before; we cannot now

    if bindingKey == null && liveKey != null:
        return IDENTITY_UNVERIFIABLE(degraded)              # a key appeared for a binding that had none:
                                                            # the binding cannot be confirmed, and the new
                                                            # key is evidence the collection context moved
    # both null
    if binding.isLiveInThisProcess && liveBackend.id == binding.backendId:
        return SESSION_CONTINUITY_VERIFIED
    return IDENTITY_UNVERIFIABLE(nominal)

continuationVerdict(operationClass, assurance):
    return the cell of the matrix in §3.3
```

**LOCKED.** The third branch (binding key null, live key now present) is deliberately *degraded*, not
nominal. A backend that starts presenting a key has changed its reporting contract; continuing to treat
the binding as unverified-but-fine would silently accept a change nobody validated. Sub-Gate 21D/21C
must keep this branch fail-closed.

---

## 4. Backend-qualified resource identity

### 4.1 Qualification formula (LOCKED)

```text
logical resource identity
  =  configured backend identity (AnkiBackendId.stableId)
   + collection context (collectionKey, or an explicit "unknown" that is not a wildcard)
   + backend-local resource discriminator (numeric or opaque id, plus type tag)
```

Encoded either as explicit fields or as a strongly typed enclosing context. `AnkiRefs.kt` uses explicit
fields plus a length-prefixed stable key; either is acceptable, both must satisfy §4.4.

### 4.2 The six reference types

| Type | Status at this HEAD | Qualification |
|---|---|---|
| `AnkiCardRef` | IMPLEMENTED — `AnkiRefs.kt:49` | `backendId`, cardId?, noteId?, cardOrd?, `collectionKey` |
| `AnkiNoteRef` | IMPLEMENTED — `AnkiRefs.kt:27` | `backendId`, noteId, `collectionKey` |
| `AnkiDeckRef` | IMPLEMENTED — `AnkiRefs.kt:13` | `backendId`, deckId, `collectionKey` |
| `AnkiNoteModelRef` | IMPLEMENTED — `AnkiNoteModels.kt:25` | `backendId`, modelId, `collectionKey` |
| `AnkiCardTemplateRef` | IMPLEMENTED — `AnkiTemplateModels.kt:18` | nested `modelRef` + `collectionKey`, cross-consistency enforced |
| `AnkiMediaRef` | **DOES NOT EXIST** — FUTURE CONTRACT | must be `backendId` + `collectionKey?` + backend-local media identity. Today the media *request* is qualified (`StoreMediaBackendRequest.backendId`, `NoteCreationBackendResults.kt:68-74`) but the *result* is a bare `Stored(mediaName)` (`:93`). |
| `AnkiFilteredSessionRef` | **DOES NOT EXIST** — FUTURE CONTRACT (GATE 20 BLOCKED) | must be `backendId` + `collectionKey?` + backend-local filtered-deck identity, and must be a *different type* from `StudySessionId`. |

Structural identity rules currently enforced (IMPLEMENTED): `require(cardOrd == null || noteId != null)`
and `require(cardId != null || (noteId != null && cardOrd != null))` (`AnkiRefs.kt:60-63`); a note is
never a card (`AnkiNoteRef.toCardRef` is explicit); `AnkiPublication`-style enrichment never claims to
preserve persisted identity (documented on `AnkiCardRef`).

### 4.3 Prohibited practices (LOCKED)

```text
No implicit cross-backend equality       — A.card(123) ≠ B.card(123), always, even with identical numeric ids
No card ID as a global identifier        — a cardId is meaningful only inside (backendId, collection context)
No note ID as a global identifier        — same rule
No deck-name identity substitution       — names are labels; identity is the qualified ref
No card↔note identity confusion          — a note generating a card is not the card
No reinterpretation during navigation     — a route/token is decoded into exactly the ref it encoded,
                                            or it is invalid; it is never re-bound to the current backend
No silent resource migration              — a ref never acquires a new backendId or a new collectionKey
                                            without a durable, user-visible rebinding step
```

### 4.4 Navigation and route identity (LOCKED)

| Route | Carries today | Contract |
|---|---|---|
| `card-details/{cardRef}` | `backendId.stableId`, `collectionKey`, `cardId`, `noteId`, `cardOrd` | Correct. Keep. A malformed token decodes to `null` and surfaces an error screen — never a guess (`Screen.kt:95-122`). |
| `edit-note/{cardRef}` | same card-ref token | Correct. Keep. |
| `add-note/{backendId}` | `backendId.stableId` | Correct shape; the contract requires the **seed** to come from the resolved backend for the *new* context rather than a hard-coded one (owner: 21K). |
| `deck-details/{deckId}` | raw `deckId` only | **Gap.** Must become a backend-qualified deck token (owner: 21F). |
| `card-browser?deckId=…` | raw `deckId` only | **Gap.** Same. |

**LOCKED interim rule (binding until 21F lands).** An unqualified deck token must be resolved against
the **backend that could have produced it** — today, deterministically, the AnkiDroid-pinned read
surfaces that created it — and **never** against the current preference. No new surface may consume an
unqualified deck id. This keeps today's behavior (a single registered backend) correct and closes the
latent AnkiDroid-deck-id-versus-PC-deck-id hazard recorded in 21A
(`BACKEND_ROUTING_INVENTORY.md` §7) without inventing a migration that would itself reinterpret ids.

### 4.5 Compatibility of already-serialized references (LOCKED)

1. Existing field sets are frozen. A new optional field must be additive with a default, so that records written by older builds still decode.
2. `collectionKey` values that are `null` **stay `null`**. They are never back-filled, never re-derived, never "upgraded" from a live backend during read or migration.
3. `stableKey` compositions are persisted *inside* records (tombstones, ids). Their length-prefixed encoding must remain byte-stable; a change to the composition is a schema change requiring an explicit migration, not a silent rewrite.
4. An unknown/future identity variant inside an old record decodes as `Unreadable` and blocks only that record's recovery — it must never decode into a neighbouring identity.
5. Enrichment (`withCollectionKey`, mapper re-derivation) is an adapter concern and is explicitly **not** proof that two refs denote the same persisted resource — that rule already exists on `AnkiCardRef` and is hereby extended to every ref type, including the future media/filtered-session types.

---

## 5. Identity collision catalogue (LOCKED)

| Collision | Why it is dangerous | Contract |
|---|---|---|
| Equal numeric card ids on two backends | Cross-backend identity confusion | Different logical resources by construction (backendId is the first component of every stable key). No translation table exists or may be added for equality purposes. |
| Equal numeric note/deck/model ids | same | same |
| Two desktop PCs, same Anki profile name | Two collections, one identity → shared write ledger | Prevented by A2 (discriminator must be globally unique). |
| Two AnkiDroid profiles on one device | Same `backendId`, same numeric ids, no key | **Undetectable today.** Recorded residual risk; the contract forbids claiming detection and forbids inferring it. |
| Media file name | `Stored(mediaName)` is unqualified | FUTURE: `AnkiMediaRef` must be backend-qualified before any media name travels outside its creating context. |
| Route token reuse across backends | Reinterpretation | Qualified tokens only (§4.4). |
| ViewModel / repository cache keys | Stale data from another backend | Cache key must include `backendId`; Library and Browser already do (`AppContainer.kt:492`, `AppNavHost.kt:142`, `AnkiLibraryRepository.kt:193,208-210`, `CardBrowserViewModel.kt:62`). 21F extends the rule to every read context. |
| Deck name identity | Labels drift and repeat | Names are never identity (§4.3). |

---

## 6. Persistence and migration rules

### 6.1 What is persisted today (IMPLEMENTED)

| Store | Identity carried | Envelope | Version |
|---|---|---|---|
| `ReviewCommitLedger` | `commitId.backendId` (+ optional `collectionRef`, `deckRef`) | `Envelope(schemaVersion, records, tombstones)` | `SCHEMA_VERSION = 4` |
| `ReviewerActionLedger` | `backendId` + `collectionRef` | `Envelope(SCHEMA_VERSION, records)` | `1` |
| `NoteMutationLedger` | `backendId` | `Snapshot(version, records)` | `1` |
| `NoteCreationLedger` | `backendId` | `Snapshot(version, records)` | `1` |
| Navigation routes | `backendId.stableId` (+ card ref fields) | route strings | n/a |
| Preference | `AnkiBackendMode` only — a **mode**, never an identity | DataStore key `anki_backend_mode` | n/a |

Decode-time rules that already protect identity (IMPLEMENTED): unknown schema → `Unreadable`
(`ReviewCommitLedger.kt:106`); duplicate commit ids → `Unreadable`; more than one unresolved record per
session → `Unreadable(MULTIPLE_UNRESOLVED_PER_SESSION)`; more than one commit per turn → `Unreadable`;
tombstone identity that cannot be derived → `Unreadable`.

### 6.2 Historical cases (LOCKED)

| Case | Does it occur in current on-disk formats? | Contract |
|---|---|---|
| **`backendId` present, `collectionKey` missing** | **Yes — this is the normal AnkiDroid case.** Every AnkiDroid record. | **Safe deterministic handling.** Absence is honoured as unknown. No back-fill, no invented key, no added block. The record recovers under its family's rules with the collection check inert. Uncertainty is preserved, not erased. |
| **Backend *kind* present, *instance* missing** | **No.** Every durable record stores a full `AnkiBackendId`, not a kind. | If a future format ever stores only a kind, that is a **schema defect**: such a record must be `Unreadable`, not recovered by choosing an instance of that kind. |
| **Only a card id persisted (no backend identity)** | **No.** Every persisted mutation record carries a full identity; `ReviewCommitRecord.backendId` is derived from `commitId` (`ReviewCommit.kt:272`). | **LOCKED:** a record whose owner cannot be established is `Unrecoverable` and is surfaced as such. It must never be assigned to whichever backend happens to be selected. |
| **`collectionKey` known in the record, `collectionKey` unknown live** | Possible for any future keyed backend | Treated as `IDENTITY_UNVERIFIABLE(degraded)` (§3.5) → recovery blocked for that group. It is **not** "no key, therefore fine". |
| **Session restored with no backend identity** | **Cannot occur today**: `AnkiSessionContext` is not `@Serializable` and the lock tracker is process memory only. | If 21G introduces durable sessions, `backendId` is a **required, non-defaulted** field. A durable session record lacking it must be **invalidated**, never defaulted to the current preference or to "whichever backend is online". |
| **Unknown/future identity variant inside a record** | Possible after any future scheme change | Decode fails → `Unreadable`. Fail-closed by design; a default is prohibited. |
| **Legacy unqualified deck route token** (system-restored navigation) | Possible | Resolved against the producing backend only (today: AnkiDroid) and never against the preference (§4.4). |

### 6.3 Migration musts and must-nots (LOCKED)

**Must:**

1. Preserve the original evidence, including its *absence*. A null key stays null; an unknown owner stays unknown.
2. Derive ownership only from evidence already inside the record (identity fields, or an identity embedded in a key that the record itself carries).
3. Fail closed: an ambiguous record becomes `Unreadable`/`Unrecoverable` rather than being re-attributed.
4. Be versioned and explicit: a schema change increments the envelope version and is rejected by out-of-range readers rather than silently tolerated.
5. Keep the identity vocabulary of an old record readable after new variants are added.

**Must not:**

```text
assign an unqualified historical mutation to the current preferred backend
rewrite a backendId during migration
rewrite a collectionKey during migration
fill a null collectionKey from the live backend
collapse two identities into one because their stableIds look similar
delete uncertainty evidence (a null key, an unknown owner, an ambiguous status) to make a record loadable
```

---

## 7. Residual risk (owned by later sub-gates)

1. **No authoritative collection identity exists on the only real backend** — the guard machinery exists
   and is inert; nothing in GATE 21B may convert "no key" into "verified same collection".
2. **AnkiDroid profile change is undetectable** — the strongest single argument against any
   cross-backend substitution, and an unremediable one from client code alone.
3. **PC identity allocation is unauditable** — 21D must implement §1.3 before a PC backend may join the
   registry; until then `PC_AGENT` is legitimately unselectable.
4. **`AnkiMediaRef` and `AnkiFilteredSessionRef` do not exist** — media names and filtered-session ids
   are unqualified today, and must not travel across contexts until qualified.
5. **Unqualified deck route tokens remain** — owner 21F.
