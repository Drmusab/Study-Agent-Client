# Backend Ownership Invariants

**Gate:** GATE 21B — Backend Contract Resolution & Architecture Lock (Sub-Gate 21B of 21A–21N)
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/e0f0b8dd-study-agent-client`
**HEAD:** `236cff8f2733203ccacbc479a1463b283fcf2ecf`
**Date:** 2026-10-11
**Status:** LOCKED — normative contract. **No production code was changed to produce this document.**
**Prerequisite:** GATE 21A PASS/LOCKED; zero source change since.
**Companion documents:** `GATE_21B_CONTRACT_LOCK.md`, `BACKEND_IDENTITY_CONTRACT.md`,
`BACKEND_SELECTION_POLICY.md`, `BACKEND_FAILURE_MATRIX.md`, `GATE_21B_DECISION_EVIDENCE.md`.

This document owns **DECISION-21B-10** … **DECISION-21B-16** and **DECISION-21B-18** … **DECISION-21B-25**
as they concern *who owns which backend decision, and when that ownership becomes immutable*.

Labels: **IMPLEMENTED** (source-verified at this HEAD), **LOCKED** (normative), **FUTURE CONTRACT**
(specification for a component that does not exist yet). No statement here is a runtime measurement.

---

## 1. Registry and resolver: two separated authorities

### 1.1 Backend registry — the single source of *what exists* (LOCKED — INV-21B-19)

`AnkiBackendRegistry` (`core/anki/AnkiBackendSelector.kt:10`, built once at
`AppContainer.ankiBackendRegistry`, `AppContainer.kt:492-495`) is the **one** application-level source of
configured backend instances. There must never be a second registry, a second "active backend" holder, a
service-locator lookup, or a per-feature list of backends.

| Registry **owns** | Registry **must never own** |
|---|---|
| Configured instance identities (`ids`, `registered`) | StudySession state (`AnkiStudyBackendLockTracker` owns the projection; the session owns the lock) |
| Concrete implementation lookup by identity (`find(id)`) | ReviewCommit / reviewer-action / note-mutation / creation transaction truth (each ledger owns its own) |
| Availability snapshots per instance (via `AnkiBackend.availability`) | Scheduler calculations (the backend's Anki collection owns them) |
| Capability snapshots per instance (via `descriptors()`) | Mutation retry policy (each transaction family owns its own) |
| Existence refusal on duplicate identity | Cross-backend synchronization, migration, merging, id translation |

**LOCKED.** `AnkiBackendRegistry` is an *immutable explicit registry*, not a mutable "current backend"
singleton. `registered` documents that registration order is never a routing input.

**Boundary owner decision (LOCKED).** The existing component already fulfils this responsibility. GATE 21D
**extends** it (instance registration for PC, availability/capability snapshots per instance); it must not
create a parallel registry, and it must not move routing policy into it.

### 1.2 Backend resolver — the single source of *which one for a new context* (LOCKED)

`AnkiBackendResolver` (pure policy, `core/anki/AnkiBackendResolver.kt:104`) plus its session-start adapter
`AnkiBackendSelector` (`AnkiBackendSelector.kt:40`, `REVIEW_REQUIREMENT = STUDY_SESSION_REQUIREMENTS`) are
the **one** routing policy. `AnkiBackendStatusCenter.resolveFor(...)` is the same policy exposed for
pre-binding preview only.

**The resolver must never:** change an active StudySession; perform scheduler operations; commit ratings;
replay reviewer actions; migrate note edits; recover unknown note creations; probe, refresh or write
preferences; or read/write the registry's contents.

**Boundary owner decision (LOCKED).** No distinct new "backend resolver" component is created by this
gate series. 21E **extends the existing policy inputs** (`BackendResolutionRequest`,
`AnkiBackendResolver`) rather than introducing a second selector. A second policy component is
prohibited, because two policies can disagree and the disagreement would be invisible.

**LOCKED (structural consequence).** Because the resolver is pure and pre-binding, it cannot be reached
from recovery: `AnkiTransactionRecoveryPlanner` contains no reference to `AnkiBackendResolver`,
`AnkiBackendPreferenceStore` or `AnkiBackendMode` (IMPLEMENTED; test-enforced by
`Gate21TransactionRecoveryRoutingTest`).

---

## 2. StudySession ownership

### 2.1 The lock contract (LOCKED — INV-21B-08, INV-21B-09)

```text
StartStudy
  ↓  sample the preference once
  ↓  Resolve exactly one backend (policy of BACKEND_SELECTION_POLICY.md)
  ↓  refresh that backend's availability and re-resolve (fail closed if the answer changed)   [IMPLEMENTED]
  ↓  validate required capabilities (STUDY_SESSION_REQUIREMENTS = { REVIEW })                  [IMPLEMENTED]
  ↓  validate the requested deck against the live collection of that backend                   [IMPLEMENTED]
  ↓  capture backend identity + collection context/assurance + capability snapshot             [IMPLEMENTED]
  ↓  persist session ownership — see §2.4 for the durability rule                              [PARTIAL]
  ↓  begin backend scheduler operations
  ↓  publish the lock ONLY after the backend accepted the session                              [IMPLEMENTED]
```

IMPLEMENTED evidence: `AnkiLocalStudyStarter.start()` (`:132-159`), `AnkiStudyEffectExecutor.begin()`
(`:544-549` resolution, `:571-576` foreign-deck refusal, `:578` context creation, `:582` `beginReview`,
`:585` lock publication), `AnkiStudyBackendLockTracker` (`core/anki/AnkiBackendStatus.kt:128`).
Re-verified at this HEAD.

**LOCKED.** Once created, `StudySession.backendId` is **immutable** for the whole session lifetime.

### 2.2 Parameter naming note (LOCKED)

The lock is described in some gates as `StudySession.backendId`. In the current source the equivalent
field is `AnkiSessionContext.backendId`, carried by the study session (`StudySession.sessionId` string is
the session identity — `AnkiSessionContext.kt:3-8`, and **not** a second session identity). 21G must
either keep that field or introduce an unambiguous equivalent; it must **not** introduce a second,
parallel session identity or a second backend field that could disagree with it.

### 2.3 Session-bound operations and their ownership source (LOCKED)

| Operation | Ownership source | Status |
|---|---|---|
| `getNextReviewCard` | session lock | IMPLEMENTED — `registry.find(session.context.backendId)?.nextCard(...)` `AnkiStudyEffectExecutor.kt:508` |
| `getCard` / hydrate card content | the turn's backend, which is the session's | IMPLEMENTED — `registry.find(turn.backendId)?.hydrateCardContent(turn.cardRef)` `:513` |
| `commitRating` | the commit identity's backend, which is the session's | IMPLEMENTED — `registry.find(commitId.backendId)` for semantics freeze `:628`, planner + `registry.find(commitId.backendId)` for dispatch `:664-666` |
| `Bury` | the reviewer-action identity's backend (session-scoped record) | IMPLEMENTED — `ReviewerActionRecord.backendId` + `registry.find(record.backendId)` (`ReviewerActionRecovery.kt:286-320`, `ReviewerActionCoordinator.kt:130`) |
| `Suspend` | same as `Bury` | IMPLEMENTED — same path |
| `SetFlag` | same family as `Bury`/`Suspend`, capability-gated | **NOT IMPLEMENTED** — `flags = false` on AnkiDroid (`AnkiDroidBackend.kt:211`); the routing contract still applies when implemented |
| `endReview` | session lock (and the lock is released here) | IMPLEMENTED — `:802` release, `:804` `endReview` |
| filtered / custom study operations | session lock + filtered-session binding | **NOT IMPLEMENTED** — GATE 20 BLOCKED; contract in §6 |

**LOCKED.** Every session-bound scheduler operation resolves its backend from the session's own identity
(or from a record whose identity was derived from that session), **never** from a fresh resolution, the
preference, the "current" backend, or the first backend that is available.

### 2.4 Durability of the lock (LOCKED — decision recorded honestly)

| Fact today | Consequence |
|---|---|
| `AnkiSessionContext` is a plain data class, **not** `@Serializable` (`AnkiSessionContext.kt:10`). | The lock cannot be persisted as-is. |
| `AnkiStudyBackendLockTracker` is a `MutableStateFlow<Map<String, AnkiSessionContext>>` — **process memory only** (`AnkiBackendStatus.kt:128`). | Process death loses the lock. |
| Durable ledgers survive and are loaded at startup (`AppContainer.kt:486-488`: recovery off the splash path, study state not mutated). | Transaction ownership survives; session ownership does not. |

**LOCKED.** A session is **not restored onto a backend** across process death. What survives is
*transaction* ownership, recovered through the original family (§3). Therefore:

- "process restart preserves the backend lock" is **not** a guarantee of this architecture and must not be
  claimed as one;
- 21G may add durable session context, but only under §2.5 rules, and it must not weaken ownership.

### 2.5 Minimum durable session context (FUTURE CONTRACT — owner 21G)

If (and only if) 21G introduces session persistence, the minimum durable record is:

| Field | Required? | Rule |
|---|---|---|
| `StudySessionId` | **Required** | The existing session identity; never re-minted on restore |
| `BackendId` (`AnkiBackendId`) | **Required, non-defaulted** | A record lacking it is invalid; never defaulted to the current preference or to "whichever backend is online" |
| Collection context (`collectionKey?`) | Required (nullable) | Null means unknown and stays null; never back-filled |
| Deck reference (`AnkiDeckRef?`) | Required (nullable) | Must be backend-qualified if present |
| Study mode | Required | Distinguishes normal vs filtered sessions |
| Filtered-session reference | Required when mode is filtered | Must be `AnkiFilteredSessionRef` (§6), distinct from `StudySessionId` |
| Capability/version metadata | Justified only where it changes a decision | A frozen capability snapshot is already session state; persisting it is allowed, but it must never be used to *upgrade* a decision after restore |

**Restoration ordering (LOCKED for that future work):**

```text
1. read the durable session record
2. require a decodable BackendId           — otherwise INVALIDATE (never guess)
3. require that exact instance registered   — otherwise retain as blocked/invalid, never reassign
4. compute identity assurance for the record's collectionKey
5. permit continuation only if the assurance matrix allows the session's next operation
6. if the session was mid-turn (a scheduler operation may have been issued), do NOT silently resume —
   the durable transaction rules (§3) decide, and an ambiguous mutation keeps its original semantics
```

**LOCKED invalidation rules.** A durable session is invalidated (not migrated, not repaired) when: the
backend identity is missing or undecodable; the backend is a different instance; the collection
verification is `AUTHORITATIVE_MISMATCH`; the assurance degraded from a recorded key to no key; the
session's capability requirements can no longer be met; or the session record's schema is out of range.

---

## 3. Transaction ownership and recovery target

### 3.1 The rule (LOCKED — INV-21B-10, INV-21B-11, INV-21B-12)

```text
TransactionBackend  ≡  the backend identity recorded when the operation was ADMITTED
```

It is **not**: the global preference; an AUTO result computed after restart; the currently connected
backend; the first available backend; the backend that "should" own it by kind or locality.

### 3.2 Required matrix (LOCKED)

| Transaction family | Source of backend truth | Recovery destination | Cross-backend retry | Implementation status |
|---|---|---|---|---|
| `ReviewCommit` (GATE 11 rating) | Durable record — `ReviewCommitRecord.commitId.backendId` (derived field at `ReviewCommit.kt:272`) | Original backend | **Prohibited** | IMPLEMENTED (durability + recovery). *Real-device mutation verification is BLOCKED per `GATE_11_RATING_COMMIT.md` — carried as a limitation, not as a guarantee.* |
| `ReviewerAction` (GATE 13 bury/suspend) | Durable record — `ReviewerActionRecord.backendId` + `collectionRef` (`:110`) | Original backend | **Prohibited** | IMPLEMENTED |
| `NoteMutation` (GATE 17 note edit) | Durable record — `NoteMutationRecord.backendId` (`edit/NoteMutationRecord.kt:21`) | Original backend | **Prohibited** | IMPLEMENTED with a **latent gap**: the coordinator holds a pinned backend instance (`NoteMutationCoordinator.kt:151`, wired to `ankiDroidBackend` at `AppContainer.kt:439`) and refuses a foreign draft as `BackendMismatch` (`:344`). Fail-closed, but a record owned by a second backend could not be recovered. 21J must make the coordinator resolve by **record identity**. |
| `NoteCreation` (GATE 18 note creation) | Durable record — `NoteCreationRecord.backendId` (`create/NoteCreationRecord.kt:47`) | Original backend | **Prohibited** | IMPLEMENTED with the same latent gap (`NoteCreationCoordinator.kt:162`, `AppContainer.kt:470`, refusal `:277`). Owner 21K. |
| `CustomStudy` mutation | **No record type exists** | Original backend | **Prohibited** | **NOT IMPLEMENTED / BLOCKED** (GATE 20). This row is a **required future contract, not an existing guarantee.** |

**LOCKED.** Where a family is not implemented, this document says so and the entry is a contract for the
future — never a claimed guarantee. Structural guards that already make cross-backend records
unconstructible (IMPLEMENTED): `require(commitId.backendId == card.backendId)` `ReviewCommit.kt:230`;
`require(collectionRef == null || collectionRef.backendId == backendId)` `:233` and
`ReviewerActionRecord.kt:148`; `require(cardRef.backendId == backendId)` `ReviewerActionRecord.kt:147`;
`require(collectionRef == null || collectionRef.backendId == commitId.backendId)` `AnkiBackend.kt:551`;
`require(collection == null || collection.backendId == backendId)` `AnkiSessionContext.kt:26`.

### 3.3 Recovery routing (LOCKED — INV-21B-11)

Normative sequence — the only authorized one:

```text
Application starts
  ↓  load durable unresolved transactions
  ↓  read the persisted transaction backend identity FROM EACH RECORD
  ↓  group by that identity
  ↓  find that EXACT registered instance (never the resolver, never AUTO, never the preference)
  ↓  validate the collection continuity that is actually checkable
       (mismatch only when both sides present a key; a degraded/unknown key blocks — see failure matrix)
  ↓  invoke the ORIGINAL transaction family's recovery for that group
```

Explicit rules:

1. **Never** call AUTO to route a persisted transaction.
2. **Never** rewrite a backend identity during recovery.
3. **Never** rewrite a collection identity during recovery.
4. **Never** replay a mutation through another backend.
5. A backend being unavailable does **not** make its transaction a failure — it stays blocked.
6. A reconnect does **not** prove the backend did not apply the mutation.
7. Ambiguous outcomes retain their original safety semantics: `AMBIGUOUS` stays blocking until the owning
   family's mechanism resolves it *on the original backend*.
8. One backend's recovery failure must not corrupt another backend's records: blocking is **per group**
   (IMPLEMENTED — `AnkiTransactionRecoveryPlanner.plan` returns one route per owner identity, and
   `Blocked` is per identity).

IMPLEMENTED evidence: `AnkiTransactionRecoveryPlanner` (`AnkiTransactionRecoveryRouting.kt:44-105`),
`AnkiReviewCommitReconciler(registry)` (`ReviewCommitReconciliation.kt:82`),
`ReviewerActionRecovery.kt:286-320`, and the pre-scheduler blocker check
(`AnkiStudyEffectExecutor.kt:556-567`).

### 3.4 Ambiguity semantics are owned by the family, not by routing (LOCKED)

Recovery routing decides *where* a record goes. Whether an attempt applied, and whether a retry is safe,
is decided by the owning family's status machine and its proof rules. Routing must never "resolve"
ambiguity by finding a healthy backend — that is exactly the substitution this gate prohibits.

---

## 4. Read context ownership

### 4.1 One backend per read context (LOCKED — INV-21B-14)

Surfaces and their contract:

| Surface | Contract | Current status |
|---|---|---|
| Library | One resolved backend context | **PINNED** in DI to `ankiDroidBackend` (`AppNavHost.kt:81`, `AppContainer.kt:492`) — owner 21F |
| Deck Details | One resolved backend context; deck identity must be backend-qualified | **PINNED** (`AppNavHost.kt:110`); route carries a raw deck id — owner 21F |
| Card Browser | One resolved backend context; query + cursor bound to backend identity | **PINNED** (`AppNavHost.kt:144`); `switchBackend` exists with **no production caller**; `RequestToken(backend.id, generation, query, cursor)` already correlates — owner 21F |
| Card Details | Bound from the route's `cardRef.backendId` | IMPLEMENTED and correct (`AppNavHost.kt:167-176`) |
| Model Details | Inherits the creation/edit context that opened it | Partially IMPLEMENTED via Add Note binding |
| Template Details | Same | Same |

### 4.2 Switching creates a new logical read context (LOCKED)

Switching the backend does **not** mutate a context. It creates a new one, and the old context's data is
invalidated. Mandatory invalidation list:

```text
old backend results            old card / note / deck references
old paging cursor              old total count and paging metadata
old model / template cache     old in-flight requests
old "selected deck" / filters  old query generation
```

**LOCKED.** No surface may merge two backends' datasets, and no surface may present one backend's counts
beside another backend's rows. IMPLEMENTED precedents to reuse: `AnkiLibraryRepository.invalidateIfBackendChanged`
(`:208-210`) and the `cached.backendId == backend.id` reuse rule (`:193`); the Browser's generation token
(`CardBrowserViewModel.kt:62,142`).

### 4.3 Asynchronous response correlation (LOCKED — INV-21B-14)

A result may be published only if it is correlated with **all** of:

```text
BackendId
collection context (key or the recorded "unknown" category)
the requested resource identity / query identity
query generation
request generation (per context)
```

Policy: **latest-request-wins with explicit rejection**, not "best effort cancellation".

- Switching backend increments the generation and invalidates every outstanding request.
- A late response whose token is stale is **discarded**, and its discard is silent to the user but
  visible in diagnostics as a stale-drop count where such a counter exists.
- Correctness must **not** depend on cancellation succeeding; a backend that answers after cancellation
  must still fail the token check.
- Compose recomposition is **not** a correlation mechanism and must never be relied on for this.

IMPLEMENTED evidence: `RequestToken(backend.id, generation, query, cursor)` and
`isCurrent(token, targetBackend)` (`CardBrowserViewModel.kt:62,142,387`); Library's
`snapshot.backendId != expected` guard. `Gate21ArchitectureAuditTest.browser paging state cannot cross a
backend boundary` pins the cursor rule.

---

## 5. Editor and creation binding

### 5.1 Editor binding (LOCKED — INV-21B-15)

```text
editor loads note X from backend A     →     Edit context ⇒ backend A
changing the global preference to B    →     Save still targets A
```

**LOCKED.** The editor's write target is the **base note's** backend, for the whole lifetime of that edit
context. The draft cannot be migrated to B, and the note identity cannot be re-pointed to B's collection.
If A becomes unusable, the editor **blocks** with a typed error (the draft is preserved locally); it never
redirects to B. To edit through B the user must cancel and open a new edit context from B's data.

IMPLEMENTED evidence: the edit route carries the full backend-qualified `AnkiCardRef`
(`Screen.kt:42,95-122`); `AppNavHost.kt:248-253` resolves `find(editCardRef.backendId)` and shows an error
screen when unregistered; `NoteMutationCoordinator` refuses a foreign base note as `BackendMismatch`
(`:344`); the mapper surfaces it as "The selected backend changed. Close Add Note and open it again."
(`AddNoteMapper.kt:209`, `EditNoteMapper.kt:372`).

### 5.2 Creation binding (LOCKED — INV-21B-16)

A creation context binds **one coherent set**: backend + collection context + note model + deck + media
destination. The following combinations are **prohibited**:

```text
model schema read from A + note created on B
media stored on A + note created on B
deck chosen from A + note created on B
note created on A + media stored on B when the note references that media
```

**LOCKED.** Media stored for a note must be stored on the same backend that creates the note that
references it. Until `AnkiMediaRef` exists (`BACKEND_IDENTITY_CONTRACT.md` §4.2), a media name must not
travel outside the context that created it.

**How an explicit backend switch affects an unsaved draft (LOCKED):**

| Action | Effect |
|---|---|
| User changes global preference to B while a draft is open for A | **None.** The draft stays bound to A. |
| User explicitly wants to create on B | Must open a **new** Add Note context seeded from B; the old draft is not migrated, copied or re-pointed |
| A becomes unavailable | Creation blocks with a typed refusal; the draft is preserved; nothing is created on B |
| An unresolved creation transaction exists for A | It stays owned by A and recovers only through A (§3) |

IMPLEMENTED evidence: `add-note/{backendId}` route with `decodeBackendId`
(`Screen.kt:51-57`); `AppNavHost.kt:204-222` resolves `find(addNoteBackendId)`; the seed at
`AppNavHost.kt:94` is hard-coded to AnkiDroid — **owner 21K must seed it from the resolved backend for
the new context**, which is a pre-binding decision and therefore legal.

---

## 6. Custom Study ownership (LOCKED — INV-21B-17)

**Status: NOT IMPLEMENTED.** GATE 20 is BLOCKED (`GATE_20_CUSTOM_STUDY_CONTRACT.md`), `StudyMode.CUSTOM_SESSION`
exists as a wire enum with no client logic (`StudyControlModels.kt:37`), and no `AnkiFilteredSessionRef`
type exists. Everything in this section is a **required future contract**, not a guarantee.

### 6.1 The identity distinction (LOCKED)

```text
StudySessionId          ≠   BackendFilteredSessionId
(the study session)          (the backend-side filtered deck / custom study object)
```

A filtered session may outlive one study session (rebuild), and one study session may traverse several
backend-side turns. Conflating them would make "which context does this card belong to" unanswerable.

### 6.2 Backend binding per operation (LOCKED)

| Operation | Backend source |
|---|---|
| Create filtered session | The owning `StudySession`'s locked backend (created only as part of a session that already holds a lock, or of an explicit new pre-binding context) |
| Get next card | Session lock |
| Rate card | Session lock, and the resulting transaction record carries that same identity |
| Rebuild | The **filtered session's** backend identity (`AnkiFilteredSessionRef.backendId`), which must equal the owning session's lock; a recorded filtered session is a durable binding wherever it survives the process |
| End | Session lock (and the filtered-session reference is released, not migrated) |
| Restore | Only if the identity is decodable, the instance is registered, and the assurance rules allow; otherwise invalidate |

**LOCKED.** No cross-backend filtered-session migration. No silent fallback to normal review when the
filtered context becomes unavailable: the session pauses/blocks with a typed reason. Substituting a
normal review queue for a filtered one would change scheduling semantics invisibly, and it is prohibited
even when the same deck is involved.

### 6.3 Gate condition (LOCKED)

21L **must not** implement custom-study backend binding before GATE 20 lands. 21B authorizes no work here
beyond preserving the prohibition.

---

## 7. Cross-cutting services are not backend ownership (LOCKED — INV-21B-18)

The PC agent transport (`core/network/WebSocketAgentConnection.kt`, `ReconnectController.kt`,
`ProtocolMessages.kt`) carries AI/voice traffic. It carries **no** Anki traffic today
(`grep -rin "anki" core/models/ProtocolMessages.kt` → empty). The PC may provide AI evaluation, TTS and
STT while AnkiDroid owns the Anki scheduler.

| Concern | Owner | Relationship to Anki backend ownership |
|---|---|---|
| AI evaluation / TTS / STT | The PC agent connection (`ConnectionState`, `CapabilityStore`) | **None.** Keyed by the agent connection, never by an `AnkiBackendId`. Must never be used to decide Anki routing. |
| Anki scheduler (next card, rating, bury/suspend) | The session's locked Anki backend | Absolute within the session |
| PC *Anki* backend readiness | `AnkiAvailability.AgentDisconnected` / `AgentAnkiUnavailable` / `Ready` — states exist, **no producer** | Must be produced only by a real implementation, after authentication and readiness (21D/21H) |

**LOCKED boundary statements:**

1. PC AI/TTS availability **never** influences Anki backend resolution, session locking, or transaction
   ownership. It is not an input to `BackendResolutionRequest`.
2. An AnkiDroid session whose backend is unavailable **does not** gain or lose AI services; the two have
   independent availability models.
3. A PC AI service failing **never** pauses an AnkiDroid session, and an AnkiDroid outage **never**
   disables PC AI/TTS.
4. "PC agent connected" is **not** evidence that a PC Anki backend exists, is authenticated, or owns
   any Anki resource. The two must remain separately observable in diagnostics.

This separation is what makes "hybrid" safe: several backends may serve different *operations* while
exactly one owns the Anki scheduler of a given session.

---

## 8. Source-of-truth matrix (LOCKED — DECISION-21B-24)

Exactly one authoritative owner per concern. Two components must never be authoritative for the same
concern, and no component may be authoritative for a concern it is not listed against.

| Concern | Authoritative source | Implementation at this HEAD |
|---|---|---|
| Configured backends (what exists) | Backend registry / configuration | `AnkiBackendRegistry` (`AppContainer.ankiBackendRegistry`) — one entry today |
| Preferred backend | Settings (`AnkiBackendPreferenceStore`) | DataStore `anki_backend_mode` (a **mode**, not an identity) |
| New-context selection | Backend resolver (pure policy) | `AnkiBackendResolver` + `AnkiBackendSelector` |
| Active study backend | The persisted/owned StudySession lock | `AnkiSessionContext.backendId` projected by `AnkiStudyBackendLockTracker` (process memory) |
| Rating transaction owner | `ReviewCommitRecord` | `commitId.backendId` |
| Reviewer action owner | `ReviewerActionRecord` | `backendId` + `collectionRef` |
| Note edit owner | `NoteMutationRecord` | `backendId` |
| Note creation owner | `NoteCreationRecord` | `backendId` |
| Custom-study owner | (no record type yet) | FUTURE — must follow §6 |
| Anki card/scheduler data | The selected backend's Anki collection | AnkiDroid provider today; PC FUTURE |
| Collection identity evidence | The backend itself | AnkiDroid: none (always null) |
| Draft edits | Editor / creation state | ViewModel-held draft |
| Display state | UI projections | Compose state; never authoritative |
| Availability / readiness | The backend's own health projection | `AnkiBackend.availability` via `AnkiDroidHealthRepository` |
| Capability claims | The backend implementation (about its wired graph) | `AnkiCapabilities` + `withWriteSupport` gating |
| Recovery routing | The recovery planner, from persisted identities | `AnkiTransactionRecoveryPlanner` |

**LOCKED.** `AnkiBackendStatusCenter` is an **observation** point (it combines availability × capability ×
preference for display and for pre-binding preview). It is not a source of truth for any concern above,
and it must never be read to decide ownership.

---

## 9. Operation ownership matrix (LOCKED — DECISION-21B-25)

Backend-selection source per operation. "Binding moment" = the point after which the identity may not change.

| Operation | Required capability | Backend selection source | Binding moment | Identity requirements | Switch before invocation? | Switch after invocation? | Failure behavior |
|---|---|---|---|---|---|---|---|
| List decks | `DECKS` | AUTO/manual resolution (pre-binding) | Context open | Backend-qualified deck refs | Yes (new context) | Yes (new context + invalidate) | Typed refusal; no substitution |
| Get deck summary | `DECKS` (counts only when `DECK_COUNTS`) | Same context as the list | Context open | Same | Yes (new context) | Yes (new context) | Counts unavailable ⇒ `null`, never fabricated |
| Browse cards | `CARD_BROWSER` | Resolution, capability-gated | Context open | Query + cursor bound to backend | Yes (new context) | Yes (new context + invalidate) | **`RequiredCapabilityUnavailable` on AnkiDroid today** — honest refusal |
| Get card details | `CARD_DETAILS` | **Route's `cardRef.backendId`** | Route decode | Backend-qualified card ref | Not applicable | No | Typed error screen; never re-resolve |
| Read model schema | `NOTE_MODEL_SCHEMA` / `NOTE_TEMPLATE_LISTING` | The context that opened the model (edit/creation/read) | Context open | Backend-qualified model ref | Yes (new context) | No | Typed refusal |
| Start normal study | `REVIEW` | AUTO/manual resolution | `beginReview` success | One instance; capability snapshot captured | Yes (before start) | **No** | Refused start publishes no lock |
| Start custom study | (GATE 20, undefined) | Session lock / new pre-binding context | Session creation | Filtered-session ref, distinct from session id | Yes (before start) | **No** | BLOCKED (not implemented) |
| Get next review card | `SCHEDULED_REVIEW` (implied by session) | **Session lock** | Session creation | Session identity | No | **No** | Typed `BackendUnavailable`; session blocks |
| Commit rating | `REVIEW` | **`commitId.backendId`** (equals the session lock) | Commit identity creation | Commit identity + card identity agree | No | **No** | Status machine owns ambiguity; no cross-backend retry |
| Bury card | `BURY` | **Reviewer-action record identity** | Action record creation | Card ref belongs to that backend | No | **No** | Record stays unresolved; no replay elsewhere |
| Suspend card | `SUSPEND_CARDS` | Same as Bury | Same | Same | No | **No** | Same |
| Set flag | `FLAGS` | Same family | Same | Same | No | **No** | **`RequiredCapabilityUnavailable`** (flags unsupported) |
| Update note | `EDIT_NOTE_FIELDS` / `EDIT_NOTE_TAGS` / `CHANGE_CARD_DECK` | **Editor's bound base note backend** | Editor open (route decode) | Base note ref qualified | No | **No** | `BackendMismatch` refusal; draft preserved |
| Create note | `CREATE_NOTE` (+ `STORE_MEDIA` when attachments) | **Creation context's bound backend** | Context open (route decode) | Backend + collection + model + deck + media coherent | Yes, only by opening a **new** context | **No** | Refusal; nothing created elsewhere |
| Store media | `STORE_MEDIA` | **The note's creation context backend** | Same as creation | Media destined for the same backend as the note | No | **No** | Refusal; never store on another backend |
| Recover transaction | The family's own capability set | **Persisted record identity only** | Record creation | Decodable backend id; collection check where possible | No | **No** | `Blocked(BACKEND_NOT_REGISTERED | BACKEND_UNREACHABLE | COLLECTION_MISMATCH)` |

**LOCKED.** Every irreversible operation preserves the backend identity under which it was admitted.
`set flag`, `browse cards` and `media read` are listed with their required capabilities even though those
capabilities are unsatisfiable today — the routing contract is fixed now so that enabling them later
cannot introduce a new selection path.

---

## 10. Consolidated ownership invariants (INV-21B-08 … INV-21B-19)

```text
INV-21B-08  Every StudySession has exactly one immutable Anki scheduler backend.
INV-21B-09  Mid-session cross-backend failover is prohibited, including after a reconnect.
INV-21B-10  Every mutation retains the backend identity recorded when it was admitted.
INV-21B-11  Recovery never invokes AUTO and never rewrites an identity.
INV-21B-12  Unknown mutation outcomes cannot trigger cross-backend replay.
INV-21B-13  A backend reconnect does not by itself prove collection continuity.
INV-21B-14  Bound read contexts cannot merge data; stale responses are rejected explicitly.
INV-21B-15  Editors cannot redirect writes after a preference change.
INV-21B-16  Note creation and media storage preserve one backend binding.
INV-21B-17  Custom Study cannot migrate scheduler authority.
INV-21B-18  PC AI/TTS services are distinct from PC Anki scheduling ownership.
INV-21B-19  Settings, Registry, Resolver, Session and Transaction Ledger maintain distinct authority.
```
