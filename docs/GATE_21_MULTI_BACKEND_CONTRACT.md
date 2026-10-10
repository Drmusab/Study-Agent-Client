# GATE 21 — Multi-Backend Contract Lock

**Phase:** B (contract decision & lock). **Decisions only.**
**Discovery evidence:** `docs/GATE_21_MULTI_BACKEND_DISCOVERY.md`.
**Implementation + verification:** `docs/GATE_21_MULTI_BACKEND_REPORT.md`.

```text
GATE 21 CONTRACT: LOCKED

STUDY SESSION BACKEND:            IMMUTABLE
MID-SESSION FAILOVER:             PROHIBITED
TRANSACTION RECOVERY:             ORIGINAL BACKEND ONLY
IMPLEMENTATION AUTHORIZED:        YES
```

---

## DECISION-21-01 — Backend identity

`AnkiBackendId` (`core/anki/AnkiBackendId.kt`) is the canonical identity. It is **logical, not
object identity**, `@Serializable`, and round-trips through `stableId`:

| Variant | `stableId` | Basis |
|---|---|---|
| `AnkiDroidLocal` | `ankidroid_local` | type (one device collection at a time) |
| `PcAgent(profileId)` | `pc_agent:<profileId>` | configuration |
| `Fake(id)` | `fake:<id>` | test double — never selectable through a real mode |

`AnkiBackendKind` (`ANKIDROID_LOCAL` / `PC_AGENT` / `FAKE`) and `AnkiBackendLocality`
(`LOCAL_DEVICE` / `REMOTE_AGENT` / `TEST_DOUBLE`) are **metadata about** an identity, derived
from it (`AnkiBackendId.kind`, `AnkiBackendKind.locality`). A kind is never an identity: two PC
profiles are two backends that share a kind. Backend type is never equated with collection
identity.

Routing, session lock, transaction recovery and diagnostics all use `AnkiBackendId`. UI labels
never serve as identity — `displayLabel()` is one-way.

## DECISION-21-02 — Collection identity

`AnkiCollectionIdentity(backendId, collectionKey)` remains the only collection type, and
`collectionKey == null` means **unknown**, never a default.

Locked position: **collection identity is UNAVAILABLE on every backend in this build**
(Discovery DISCOVERY-21-03). The contract therefore represents absence honestly:

- a null `collectionKey` is never compared, never normalized to a sentinel, never treated as a
  wildcard;
- collection validation only runs when **both** the record and the live backend have a key
  (`AnkiTransactionRecoveryPlanner.route`);
- no code manufactures equality between two backends' collections.

When a backend later reports a real key, the check is already in place and starts applying
without a contract change.

## DECISION-21-03 — Backend-qualified resource identity

Every reference is unusable without backend context, structurally:

```text
AnkiCollectionIdentity, AnkiDeckRef, AnkiNoteRef, AnkiCardRef,
AnkiNoteModelRef, AnkiCardTemplateRef
→ backendId is a required constructor parameter
→ stableKey mixes backendId.stableId (+ collectionKey) into a length-prefixed identity
```

`AnkiMediaRef` and `AnkiFilteredSessionRef` do not exist yet (GATE 18 media uses
`StoreMediaBackendRequest`; GATE 20 is blocked). They must be created backend-qualified when
they are created. Logical identity is `Backend + Collection context (where available) +
backend-local id`.

## DECISION-21-04 — Backend modes

```text
AUTO              "auto"
ANKIDROID_LOCAL   "ankidroid_local"
PC_AGENT          "pc_agent"
```

`AnkiBackendMode.accepts(id)` is the eligibility filter: `AUTO` accepts AnkiDroid and PC
profiles; the explicit modes accept only their own kind; **no mode accepts `Fake`**.

`AUTO` is a *pre-operation / pre-session selection policy*. It is not a runtime hybrid
scheduler.

## DECISION-21-05 — Meaning of hybrid

> Hybrid means several backends can be configured, discovered and used for **different
> operations** or **different sessions**.

It does **not** mean one StudySession uses both backends, one mutation changes backend after a
failure, or one card is read from A and committed to B. No browsing surface may present a
dataset merged from two backends.

## DECISION-21-06 — Four distinct concepts, never collapsed

| Concept | Type / owner | Meaning |
|---|---|---|
| `PreferredBackend` | `AnkiBackendPreferenceStore.mode` | global default for *future unbound* operations |
| `ResolvedBackend` | `BackendResolutionResult.Resolved.backendId` | one backend chosen for one not-yet-bound context |
| `LockedSessionBackend` | `AnkiSessionContext.backendId`, projected by `AnkiStudyBackendLockTracker` | immutable backend of one StudySession |
| `TransactionBackend` | `record.backendId` in each durable ledger | backend a mutation was written against |

`AnkiBackendRoutingDiagnostics` carries all four as separate fields so a support report can
never conflate them.

## DECISION-21-07 — AUTO policy scope

AUTO may resolve a backend only:

```text
before starting a StudySession
before beginning a standalone operation
before opening an unbound browsing context
```

AUTO must not re-resolve after a transaction or session lock. Enforced structurally: the only
call sites of the routing policy are `AnkiBackendSelector` (session start) and
`AnkiBackendStatusCenter` (pre-binding preview); `core/study/**` contains exactly one
resolution, inside `AnkiStudyEffectExecutor.begin()`, and no `BackendResolutionRequest`
(`Gate21ArchitectureAuditTest`).

## DECISION-21-08 — AUTO selection inputs

Deterministic inputs only:

```text
1. the requested mode            (AnkiBackendMode.accepts)
2. live availability             (AnkiAvailability.Ready)
3. required capabilities         (AnkiRequiredCapability set)
4. locality                      (LOCAL_DEVICE preferred over REMOTE_AGENT)
5. identity ordering key         (kind ordinal, then stableId) for ties
```

No latency, no "first to respond", no undocumented guess. Locality is a *policy* preference
(a local collection has no transport that can vanish mid-review), not a performance claim —
Discovery DISCOVERY-21-32 records that no latency measurement exists.

## DECISION-21-09 — AUTO is deterministic

`AnkiBackendResolver.resolve` is a **pure function** of `(request, descriptors)`: no probing, no
refresh, no preference write, no I/O. Identical inputs resolve identically; registry order does
not matter (candidates are sorted by `orderingKey`). Pinned by
`Gate21BackendResolutionTest.AUTO is deterministic for identical inputs` (500 repetitions, both
registry orders) and `resolution is pure - it never probes, refreshes or rewrites the
preference`.

## DECISION-21-10 — Capability-constrained resolution

Operations declare requirements; resolution filters on them before binding:

```text
Library          → DECKS
Study start      → REVIEW
Card browser     → CARD_BROWSER
Card details     → CARD_DETAILS
Edit note        → EDIT_NOTE_FIELDS / EDIT_NOTE_TAGS / CHANGE_CARD_DECK
Create note      → CREATE_NOTE, NOTE_MODEL_LISTING, STORE_MEDIA (for attachments)
Models           → NOTE_MODEL_SCHEMA, NOTE_TEMPLATE_LISTING
```

`AnkiRequiredCapability` maps 1:1 onto `AnkiCapabilities` fields in a single table
(`AnkiCapabilities.supports`). No UI branches on a backend name
(AUDIT-21-13, enforced by test).

## DECISION-21-11 — StudySession lock

At session creation, `AnkiStudyEffectExecutor.begin()` persists into `AnkiSessionContext`:

```text
studySessionId, backendId, collection (when known), deckRef,
startedAtEpochMs, capability snapshot
```

After that point the backend cannot change until the session ends. The lock is published to
`AnkiStudyBackendLockTracker` **only when `beginReview` succeeded**, and released only by
`EndReview` — an availability change does not release it.

## DECISION-21-12 — No mid-session failover (normative)

If the locked backend disappears, the session **pauses / blocks / recovers against the same
backend**. It never switches. `core/study/**` contains no `alternateBackend`,
`tryOtherBackend`, `fallbackBackend` or `switchBackend` spelling (AUDIT-21-03, enforced).

## DECISION-21-13 — Why

Another backend may differ in scheduler state, FSRS state, revlog, due queue, sync freshness,
card existence and filtered-session state — even with identical-looking ids. Availability does
not establish semantic equivalence (INV-21-06), and matching ids do not establish identity
(INV-21-07). In this repository the argument is stronger than theoretical: AnkiDroid collection
changes are **undetectable** (DISCOVERY-21-22), so a failover could not even be validated.

## DECISION-21-14 — Transaction backend lock

Every durable mutation record carries its backend identity:

| Record | Field |
|---|---|
| `ReviewCommitRecord` | `commitId.backendId` (record's `backendId` is derived from it) |
| `ReviewerActionRecord` | `backendId` + `collectionRef` |
| `NoteMutationRecord` | `backendId` |
| `NoteCreationRecord` | `backendId` |

Recovery routes to `record.backendId` — never to the current preference, an AUTO result, or the
first available backend.

## DECISION-21-15 — No cross-backend mutation replay

If a transaction belongs to A and A is unavailable, it **remains blocked** until A returns. It
is never replayed through B. `AnkiTransactionRecoveryPlanner` returns
`BackendTransactionRoute.Blocked(backendId, reason)` and exposes no alternative target; the two
note coordinators refuse `BackendMismatch` rather than re-route.

## DECISION-21-16 — Collection lock

When a transaction or session carries collection identity, continuation requires the same
collection to be active. A mismatch fails closed
(`BackendTransactionBlockReason.COLLECTION_MISMATCH`). Transaction ownership is never rewritten.
When either side is unknown, the check does not run — absence is recorded, never guessed
(DECISION-21-02).

## DECISION-21-17 — Read context behaviour

Read-only surfaces (Library, Browser, Details, Models) operate against **one** resolved backend
context. When that backend changes: invalidate previous data, cancel in-flight requests, clear
paging cursors, reload. Existing mechanisms kept and reused:
`AnkiLibraryRepository.invalidateIfBackendChanged`, `LibraryViewModel`'s
`snapshot.backendId != backendId` guard, `CardBrowserViewModel.switchBackend`.

## DECISION-21-18 — No mixed read dataset

A Library/Browser/Details dataset comes from exactly one backend. No federated library.
`AnkiLibraryRepository.publishFailure` reuses a cache **only** when
`cached.backendId == backend.id`.

## DECISION-21-19 — Backend switch during browsing

Switching creates a new browse context: clear card refs, clear cursors, clear results, re-run
the query. Old ids are never reinterpreted. Cursors are bound to a backend-qualified
consistency key (`AnkiCardQuery.consistencyKey(backendId, …)` +
`AnkiOffsetCursorAdapter`), so a cursor from another backend is `Resolution.ForeignQuery` →
`AnkiError.InvalidCursor`, never a silent restart at page one (INV-21-13).

## DECISION-21-20 — Backend switch during editing

An Edit/Add context binds to its backend when opened (route-carried `cardRef.backendId` /
`backendId`). A preference change cannot redirect Save/Create. The choices are *continue with
the bound backend* or *cancel and reopen* — never silent redirection (INV-21-14/15).

## DECISION-21-21 — Preference change during an unresolved mutation

Zero effect on transaction destination. Pinned by
`Gate11eAggregateAuditTest.an unresolved transaction is never rebound to the newly preferred
backend` and by `Gate21SessionBackendLockTest.a preference change during study never re-routes
an active session`.

## DECISION-21-22 — Custom study context

GATE 20 is blocked and unimplemented, so there is nothing to bind. When it lands, its context
inherits the same session lock and cannot migrate backend.

## DECISION-21-23 — Backend registry

`AnkiBackendRegistry` is the one application-level source of registered backend instances
(`AppContainer.ankiBackendRegistry`). It owns existence, identity, availability, capabilities
and lookup by `backendId`; it exposes `descriptors()` snapshots. It does **not** route and does
not decide scheduler operations.

## DECISION-21-24 — Backend resolver

`AnkiBackendResolver` is the separate routing policy. Registry and policy stay apart so a second
policy cannot quietly become a second registry. `AnkiBackendSelector` is now a thin adapter over
the resolver, so **one** policy serves both study start and read-context resolution.

## DECISION-21-25 — Typed resolution result

```kotlin
Resolved(backendId, reason)
NoBackendAvailable(considered, error)
PreferredBackendUnavailable(preference, considered, error)
RequiredCapabilityUnavailable(required, missing, considered, error)
Ambiguous(candidates, considered, error)
ConfigurationInvalid(detail, considered, error)
```

No nullable backend. Every failure carries the identities it considered and a typed error, so a
screen shows a reason instead of guessing.

## DECISION-21-26 — Explicit manual mode

`ANKIDROID_LOCAL` with AnkiDroid unavailable → **blocked**, never silently PC.
`PC_AGENT` with PC unavailable → **blocked**, never silently AnkiDroid.
Manual selection means manual selection (INV-21-10). Pinned by
`Gate21BackendResolutionTest.manual selection of an unavailable backend blocks instead of
falling back` and `manual PC selection with no PC registered is not implemented, not
redirected`.

## DECISION-21-27 — AUTO fallback

AUTO may choose another eligible backend **only before context lock**. That is the only place
backend fallback exists in GATE 21.

## DECISION-21-28 — Capability snapshot

A locked context captures the capability snapshot at lock time
(`AnkiSessionContext.capabilities`, plus GATE 13's frozen `ReviewerActionCapabilities`).
Capability changes after lock must not silently redirect a backend, and irreversible operations
keep the frozen semantics.

## DECISION-21-29 — Backend reconnect

Reconnect alone is not proof of the same context. Resumption requires the same `backendId` and,
where collection identity exists, the same collection (DECISION-21-16). A PC reconnect under the
same profile id resolves to the same *logical* identity and proves nothing about the collection
(DISCOVERY-21-21).

## DECISION-21-30 — Unresolved transaction startup

```text
load unresolved durable transactions
  ↓ group by the backendId persisted IN each record
  ↓ locate that exact backend in the registry (never the resolver)
  ↓ validate collection identity where both sides have one
  ↓ recover through that record's own transaction family
```

AUTO selection never runs for transaction recovery (INV-21-04). Encoded in
`AnkiTransactionRecoveryPlanner`, which has no reference to the resolver, the preference store or
`AnkiBackendMode` (AUDIT-21-04, enforced by test).

## DECISION-21-31 — Multiple backend recoveries

Recovery is per group. `Blocked(pcId, …)` leaves the AnkiDroid group routed. A PC failure cannot
corrupt AnkiDroid recovery or vice-versa. Pinned by
`Gate21TransactionRecoveryRoutingTest.a missing backend blocks only its own group and never
borrows the other one` and `an unreachable backend keeps its transactions blocked while another
backend is ready`.

## DECISION-21-32 — Preference change semantics

A preference change affects **future unbound operations and future sessions**. It does not touch
an active StudySession, an active editor binding, an active creation draft, or any unresolved
transaction record.

## DECISION-21-33 — Cross-backend equality

GATE 21 proves nothing about `Card A on AnkiDroid == Card B on PC`. No heuristic merging, no id
translation, no name matching (INV-21-07/19).

## DECISION-21-34 — Sync is not routing

Neither backend exposes sync state (DISCOVERY-21-27), so the question cannot even arise here.
Even with sync, similarity would not authorize alternating backends inside one session
(INV-21-18).

## DECISION-21-35 — Diagnostics contract

`AnkiBackendRoutingDiagnostics` exposes, as separate fields:

```text
preferred             AnkiBackendMode
resolved              AnkiBackendId? + BackendSelectionReason?
lockedSessionBackend  AnkiBackendId?
transactionBackend    AnkiBackendId?
availability          Map<AnkiBackendId, statusCode>
```

Plus the commit-phase timeline's existing `"backend" to commitId.backendId.stableId`. No card
content, no provider text, no exception messages — only stable tokens.

## DECISION-21-36 — UI backend indicator

Backend context is visible where materially relevant: Settings (selection + per-backend status
and reason), Study (**locked** backend), Library/Browser/Details (route/backend-qualified
context), Diagnostics (all four roles). Study shows the lock, never the preference.

## DECISION-21-37 — Backend switch UX

The selector states its own effect, always:

> "Applies to new browsing and new study sessions. An active session, an open editor and any
> unfinished save keep the backend they started on."

No hidden switching.

## DECISION-21-38 — Offline boundary

GATE 21 decides backend ownership only. Offline degradation, queues, read caches and
connectivity recovery belong to GATE 22. Nothing in GATE 21 makes a mutation replayable.

## DECISION-21-39 — Contract lock result

```text
GATE 21 — MULTI-BACKEND CONTRACT LOCK

RESULT:                        LOCKED

BACKEND IDENTITY:              AnkiBackendId (logical, serializable, stableId round-trip);
                               kind/locality are metadata, never identity
COLLECTION IDENTITY:           UNAVAILABLE on every backend in this build; null means unknown;
                               validated only when both sides have a key; never fabricated
RESOURCE IDENTITY:             backend-qualified by construction (card/note/deck/model/template);
                               stableKey mixes backendId + collectionKey
SUPPORTED MODES:               AUTO / ANKIDROID_LOCAL / PC_AGENT  (Fake is never selectable)
HYBRID DEFINITION:             multi-backend application; never a multi-scheduler session
PREFERRED BACKEND:             AnkiBackendPreferenceStore (persisted, AUTO default)
RESOLVED BACKEND:              AnkiBackendResolver — pure, deterministic, capability-constrained
STUDY SESSION LOCK:            AnkiSessionContext.backendId, immutable, published on success only
TRANSACTION BACKEND LOCK:      record.backendId for all four durable families
AUTO POLICY:                   mode → availability → capability → local-first → single remote;
                               ambiguity is a deterministic refusal
MANUAL MODE POLICY:            never silently substituted; typed failure with a reason
FAILOVER:                      PROHIBITED mid-session; allowed only before context lock, AUTO only
RECONNECT:                     same backendId required; collection validated where possible
READ CONTEXT SWITCHING:        one backend per context; invalidate, cancel, clear cursors, reload
EDITOR / CREATION BINDING:     bound at open; a preference change cannot redirect Save/Create
TRANSACTION RECOVERY:          grouped by persisted backendId; per-group blocking; never AUTO
CAPABILITY RESOLUTION:         required capabilities checked before binding; one mapping table
DIAGNOSTICS:                   preferred / resolved / locked / transaction kept distinct
UNSUPPORTED:                   cross-backend sync, migration, merging, id translation,
                               mid-session failover, cross-backend replay, federated library,
                               custom-study backend binding (GATE 20 unimplemented)
RESIDUAL RISKS:                see docs/GATE_21_MULTI_BACKEND_REPORT.md §10
IMPLEMENTATION AUTHORIZED:     YES
```

### Contract-lock gate question

> Which exact backend owns this session or mutation?

Answerable in every case:

- **session** → `AnkiSessionContext.backendId` (and its projection,
  `AnkiStudyBackendLockTracker.lockedBackendFor(studySessionId)`)
- **rating** → `ReviewCommitRecord.commitId.backendId`
- **reviewer action** → `ReviewerActionRecord.backendId`
- **note mutation** → `NoteMutationRecord.backendId`
- **note creation** → `NoteCreationRecord.backendId`
- **read context** → the resolved backend captured when the context bound

```text
GATE 21 CONTRACT: LOCKED
```
