# GATE 21 — Multi-Backend Anki Selection / Hybrid Mode — Discovery Report

**Phase:** A (discovery). **Facts only.** No routing design, no recommendations.
**Repository:** `Drmusab/Study-Agent-Client`, branch `arena/d5d0a9b7-study-agent-client`, base commit `791684c`.
**Evidence method:** source read + the repository's own JVM unit-test harness
(`tools/jvm-harness`, the Gradle-free fallback documented in `tools/jvm-harness/README.md`).
No Android device, no PC agent and no Anki instance was run in this sandbox, so every
statement about AnkiDroid or the PC agent is a statement about *this repository's code*,
not about a live backend.

---

## 0. Baseline condition found before any GATE 21 work

The branch did **not compile** at `791684c`. Two independent breaks, both left behind by
GATE 19 widening types without updating every call site:

| Break | Location | Cause |
|---|---|---|
| Non-exhaustive `when` on a sealed type | `core/anki/ReviewCommit.kt:322` — `AnkiError.commitCategory()` | `AnkiError.TemplateNotFound` was added in GATE 19 and never mapped |
| Missing required constructor arguments | `AnkiScheduledReviewContractTest.kt:250`, `AnkiDroidBackendTest.kt:71`, `AnkiDroidGatewayTest.kt:70`, `AnkiDroidReviewSessionTest.kt:109` | `AnkiDroidApiCapabilityReport` gained `modelCss` and `modelEnrichedSchema` (no defaults) |

Harness evidence for the break: `main errors: 1` then `test errors: 8`. After the two fixes:
`main errors: 0 (files: 296)`, `test errors: 0 (files: 212)`,
`RESULT classes=182 tests=2161 passed=2161 failed=0`.

This is recorded as a GATE 21 fact because **every later gate's "green build" claim was
unverifiable at this commit**, and because a green baseline is a precondition for measuring
GATE 21 at all.

---

## DISCOVERY-21-01 — Existing backend architecture

| Concern | Existing implementation | Owner |
|---|---|---|
| Backend instances | `AnkiBackend` interface (`core/anki/AnkiBackend.kt`); one real implementation, `AnkiDroidBackend` (`data/anki/ankidroid/AnkiDroidBackend.kt`); one test double, `FakeAnkiBackend` (`app/src/test/.../anki/fake/`) | `AppContainer.ankiDroidBackend` (`di/AppContainer.kt:319`) |
| Selection | `AnkiBackendSelector` + `AnkiBackendRegistry` (`core/anki/AnkiBackendSelector.kt`), a GATE 01 seam. Before GATE 21 it was used by exactly two production paths: `AnkiLocalStudyStarter.start()` and `AnkiStudyEffectExecutor.begin()` | `core/anki/AnkiBackendSelector.kt` |
| Availability | `AnkiAvailability` sealed hierarchy (`core/anki/AnkiAvailability.kt`) exposed as `StateFlow` per backend; AnkiDroid probing owned by `AnkiDroidHealthRepository` (GATE 02) | backend + `AnkiDroidHealthRepository` |
| Capabilities | `AnkiCapabilities` data class, `StateFlow` per backend; AnkiDroid's own matrix in `AnkiDroidCompatibilityPolicy.kt` (`AnkiDroidApiCapabilityReport`) | backend |
| Study lock | `AnkiSessionContext(backendId, collection, deckRef, startedAtEpochMs, capabilities, studySessionId)` (`core/anki/AnkiSessionContext.kt`), built inside `AnkiStudyEffectExecutor.begin()` | `core/study/AnkiStudyEffectExecutor.kt` |
| Transaction backend identity | `ReviewCommitId.backendId`, `ReviewerActionRecord.backendId`, `NoteMutationRecord.backendId`, `NoteCreationRecord.backendId` — all persisted | the four ledgers |

**No `PcAnkiBackend` exists.** `grep -rn "PcAnkiBackend" app/src` returns nothing. The PC
identity exists only as a *type* (`AnkiBackendId.PcAgent(profileId)`) and appears in tests
only. `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` records the same fact: "The PC agent itself is
**not in this repository** (only `server/mock_pc_agent.py`)".

## DISCOVERY-21-02 — Backend identity

`AnkiBackendId` (`core/anki/AnkiBackendId.kt`) is a `@Serializable` sealed interface with a
`stableId`:

| Variant | `stableId` | Stable across restart | Basis |
|---|---|---|---|
| `AnkiDroidLocal` (data object) | `ankidroid_local` | yes | type — one device, one collection at a time |
| `PcAgent(profileId)` | `pc_agent:<profileId>` | yes, if the profile id is | **configuration** — the profile discriminator is supplied by the caller |
| `Fake(id = "fake")` | `fake:<id>` | n/a (test double) | instance |

`fromStableId(String?)` round-trips all three and returns `null` for an unknown token.

Two PC agent configurations **can** exist later: `PcAgent("home")` and `PcAgent("work")` are
distinct identities with distinct stable ids. `PC_BACKEND` as a single global constant is
*not* the model in this repository, and the type's own comment says why: *"Logical identity,
not object identity. PC profiles must never share a write ledger."*

Identity is **logical, not instance-based**: `AnkiBackendRegistry` rejects duplicate logical
identity (`require(byId.size == backends.size) { "Duplicate logical backend identity" }`),
and `AnkiBackendSelectorTest` pins that a caller's list is detached from the registry.

## DISCOVERY-21-03 — Collection identity

`AnkiCollectionIdentity(backendId, collectionKey)` (`core/anki/AnkiRefs.kt:7`), where
`collectionKey` is *"Backend-issued collection discriminator. Null means unknown, never an
invented default."*

| Backend | Authoritative collection identity | Classification |
|---|---|---|
| AnkiDroid | **None is produced.** `AnkiDroidDeckMapper.kt:93` and `:114` construct `AnkiDeckRef(..., collectionKey = null)`; card/note refs inherit whatever the *request* carried (`AnkiDroidCardMapper.kt:103/136/176`, `AnkiDroidReviewMapper.kt:110`) | **UNAVAILABLE** |
| PC agent | Not implemented, so nothing is produced | **UNAVAILABLE** (no code) |

Consequence recorded in code, not inferred: `AnkiDroidBackend.collectionMatchesBoundSession()`
(`AnkiDroidBackend.kt:1336-1344`) reads
`record.session.context.collection?.collectionKey ?: return true` — an unknown bound key
**cannot** detect a collection change. `AnkiSessionContext`'s init only rejects a
*structurally* foreign collection (`collection.backendId == backendId`), which is a type
check, not a collection check.

## DISCOVERY-21-04 — Cross-backend collection equivalence

No code anywhere compares two backends' collections. There is no collection id, profile id,
database identity or account identity on either path (DISCOVERY-21-03).

**Authoritative equivalence: UNSUPPORTED.** No heuristic equivalence exists either — no code
matches deck names, card ids, note ids or card counts across backends.

## DISCOVERY-21-05 — Resource identity domains

Every reference type is backend-qualified at construction, and every `stableKey` mixes the
backend identity in:

| Type | File | Qualification |
|---|---|---|
| `AnkiDeckRef` | `AnkiRefs.kt` | `identityKey(backendId.stableId, collectionKey, "deck", deckId)` |
| `AnkiNoteRef` | `AnkiRefs.kt` | `identityKey(backendId.stableId, collectionKey, "note", noteId)` |
| `AnkiCardRef` | `AnkiRefs.kt` | `identityKey(backendId.stableId, collectionKey, "card", cardId, noteId, cardOrd)` |
| `AnkiNoteModelRef` | `AnkiNoteModels.kt:25` | `"anki:${backendId.stableId}:${collectionKey ?: "?"}:model:$modelId"` |
| `AnkiCardTemplateRef` | `AnkiTemplateModels.kt:18` | nested `modelRef` + `collectionKey`, with `require(collectionKey == null \|\| modelRef.collectionKey == null \|\| collectionKey == modelRef.collectionKey)` |
| `AnkiMediaRef` | — | **does not exist**; GATE 18 media uses `StoreMediaBackendRequest`/`MediaStoreBackendResult` |
| `AnkiFilteredSessionRef` | — | **does not exist**; GATE 20 is blocked, so no filtered-session type was ever added |

Answer: **yes — numeric ids are meaningful only inside one backend**, and the types make it
structurally impossible to build an unqualified reference. `AnkiArchitectureContractTest`
already pins that `AnkiCardRef(PcAgent("profile-1"), "123", "45", 0, "col-b")` and an
AnkiDroid ref with the same numeric ids are unequal.

`length`-prefixed `identityKey(vararg parts)` is used precisely so `"1"` + `"23"` cannot
collide with `"12"` + `"3"`.

## DISCOVERY-21-06 — Backend availability states

`AnkiAvailability` (GATE 01, amended GATE 02) has 11 states, one hierarchy for both paths:

- shared: `Checking`, `Ready(capabilities)`, `Unsupported(reason)`, `Fault(error)`, `TemporarilyUnavailable(reason)`
- AnkiDroid-local: `NotInstalled`, `ProviderUnavailable(detail)`, `PermissionRequired(detail)`, `CollectionNotInitialized`
- PC path: `AgentDisconnected`, `AgentAnkiUnavailable`

`statusCode` maps every state to a stable token (`READY`, `PERMISSION_REQUIRED`,
`PROVIDER_UNAVAILABLE`, `COLLECTION_NOT_INITIALIZED`, `AGENT_DISCONNECTED`,
`AGENT_ANKI_UNAVAILABLE`, `UNSUPPORTED`, `FAULT`, `CHECKING`, `NOT_INSTALLED`,
`TEMPORARILY_UNAVAILABLE`).

`isReadyForReview` is `(this as? Ready)?.capabilities?.review == true` — readiness for
*review* is positive knowledge, not the absence of an error.

## DISCOVERY-21-07 — Availability stability

Availability is a `StateFlow` per backend and is mutable at runtime
(`FakeAnkiBackend.setAvailability` models it; `AnkiDroidHealthRepository` owns the real
probing and refreshes on foreground events). `AnkiBackendSelectorTest` pins the transition
that matters: *"backend loss can change next resolution but cannot change bound session"* —
after `setAvailability(TemporarilyUnavailable())`, resolution answers PC while
`session.context.backendId` is still AnkiDroid.

Expected transitions recorded in the availability docs: permission revocation, provider
lock/close, collection closed and reopening, package removed between checks (GATE 02 §9/§10).
For the PC path the states mirror connection lifecycle. **No measurement of transition
frequency exists in this repository.**

## DISCOVERY-21-08 — Capability discovery

Capabilities are **runtime-negotiated per backend** and exposed as
`StateFlow<AnkiCapabilities>`; they are never inferred from a backend name.
`AnkiCapabilities` carries 26 flags/groups, each defaulting to the safe `false`/`NONE`,
including GATE 15 `cardBrowser: AnkiCardBrowserCapabilities` (a nested capability object with
`canBrowseAllCards`, `canBrowseDeck`, `canIncludeChildDecks`, `canSearchText`,
`supportedFilters`, `supportedSorts`, `supportsTotalCount`, `maxPageSize`) and GATE 17–19
`editNoteFields`, `editNoteTags`, `changeCardDeck`, `authoritativeMutationReconciliation`,
`noteEditConflictGuarantee`, `noteModelListing`, `storeMedia`,
`authoritativeCreationReconciliation`, `noteModelSchema`, `noteTemplateListing`,
`noteTemplateSourceRead`, `noteModelCssRead`.

AnkiDroid's claim is derived, not asserted: `AnkiDroidCapabilityProbe` +
`AnkiDroidCompatibilityPolicy` produce an `AnkiDroidApiCapabilityReport` with per-feature
`CapabilitySupport` (`SUPPORTED` / `UNSUPPORTED` / `UNKNOWN`) and a `maturityFor(...)`
derivation, clamped for GATE 11 (`Gate11dAnkiDroidCapabilityTest` pins
*"the guarantee stays clamped to AT_MOST_ONCE_FAIL_CLOSED"*).

**Before GATE 21 there was no `AnkiRequiredCapability` concept**: no operation declared what
it needed, so no resolution could be capability-constrained.

## DISCOVERY-21-09 — Capability changes

Yes. `capabilities` is a `StateFlow`, and `AnkiAvailability.Ready(capabilities)` carries a
*second* snapshot taken at probe time. The two can disagree mid-transition, and the existing
selector fails closed on disagreement:

```kotlin
if (state is AnkiAvailability.Ready && !backend.capabilities.value.review) {
    AnkiAvailability.Ready(backend.capabilities.value)   // review=false → not ready
} else state
```

`AnkiBackendSelectorTest.disagreeing capability projections fail closed` pins it.

## DISCOVERY-21-10 — Backend selection today (every selection point)

| # | Site | Mechanism before GATE 21 |
|---|---|---|
| 1 | `AnkiLocalStudyStarter.Options.preference` | **hard-coded** `LOCAL_PREFERENCE = AnkiBackendMode.ANKIDROID_LOCAL` |
| 2 | `AnkiLocalStudyStarter.localStudyBackendId` | hard-coded `LOCAL_PREFERENCE` (dashboard pre-flight) |
| 3 | `AnkiStudyEffectExecutor.begin()` | `AnkiBackendSelector(registry).resolve(request.preference)` — the preference carried in the request, i.e. from #1 |
| 4 | `AnkiLibraryRepository` | **constructor-pinned** `backend = ankiDroidBackend` (`AppContainer.kt:445`) |
| 5 | `LibraryViewModel` | `backend = container.ankiDroidBackend` (`AppNavHost.kt:81`) |
| 6 | `CardBrowserViewModel` | `initialBackend = container.ankiDroidBackend` (`AppNavHost.kt:144`); has `switchBackend(next)` but no production caller supplied a second backend |
| 7 | Card Details / Edit Note / Add Note routes | `container.ankiBackendRegistry.find(cardRef.backendId)` / `find(editCardRef.backendId)` / `find(addNoteBackendId)` — resolved from the **route identity** |
| 8 | `Screen.AddNote.createRoute(container.ankiDroidBackend.id)` | hard-coded AnkiDroid (`AppNavHost.kt:94`) |
| 9 | `NoteMutationCoordinator` / `NoteCreationCoordinator` | **constructor-pinned** `backend = ankiDroidBackend` (`AppContainer.kt:393`, `:424`) |
| 10 | `DiagnosticsRepository` | `ankiDroidBackend = ankiDroidBackend as? AnkiDroidBackend` (`AppContainer.kt:678`) |

**There was no persisted backend preference.** `grep -rn "anki_backend\|backendMode"
app/src/main` found only the `AnkiBackendMode` enum itself and
`AnkiLocalStudyStarter.LOCAL_PREFERENCE`. `AppSettingsPreferencesCodec.Keys` had no backend
key, and `AppSettings` had no backend field.

## DISCOVERY-21-11 — Hidden backend routing

None found in the sense the gate means (availability/connection/exception-driven implicit
routing). Every site above is either explicit-hard-coded or explicit-route-derived. The one
conditional-looking path is `AnkiLocalStudyStarter`, whose `AUTO` refusal is documented as
deliberate: *"`AUTO` would fall back to a PC agent, and a PC agent is not 'this phone'"*.

## DISCOVERY-21-12 — StudySession backend ownership

`AnkiSessionContext` stores `backendId`, `collection` (nullable), `deckRef` (nullable),
`startedAtEpochMs`, `capabilities` (snapshot), `studySessionId`. It is created once, inside
`begin()`, and carried by `AnkiReviewSession`.

Survival:

| Event | Survives? | Evidence |
|---|---|---|
| configuration change | yes — the machine is process-scoped (`AppContainer` is a process-lifetime singleton, `StudySessionMachineRepository` holds the machine) | `di/AppContainer.kt` lifetime doc |
| process death | **no** — the session object is in-memory; only the durable ledgers survive. `Gate11eAggregateAuditTest` covers "restart from PREPARED/SUBMITTING/COMMITTED/AMBIGUOUS/RETRY_ALLOWED" as *ledger* restores, not session restores |
| session recovery | the **transaction** recovers (ledger), the session does not |

## DISCOVERY-21-13 — Rating transaction backend identity (GATE 11)

`ReviewCommitId(backendId, studySessionId, turnId)` — `stableKey =
identityKey(backendId.stableId, studySessionId, turnId.value)`. `ReviewCommitRecord.backendId`
is a derived property (`get() = commitId.backendId`), and the record's init enforces
`commitId.backendId == card.backendId`, `deckRef.backendId == backendId`,
`collectionRef.backendId == backendId` (`ReviewCommit.kt:230-233`).

Recovery targets the original backend:

- `ReviewCommitLedger.pendingRecovery(backendId)` filters `it.backendId == backendId`
- `ReviewCommitLedger.recoveryBlocker(backendId, collectionKey, sessionId)` scopes to that backend and, for a known collection, to that collection — *"Other backends and known different collections are never blocked by this record"*
- `AnkiReviewCommitReconciler` is constructed with the registry and dispatches by the transaction's own identity (executor doc: *"dispatches by the transaction's own backend identity"*)
- `Gate11eAggregateAuditTest` already pins *"a backend preference change never redirects an active transaction"* and *"an unresolved transaction is never rebound to the newly preferred backend"*

**Confirmed: recovery can target the original backend independently of any preference.**

## DISCOVERY-21-14 — Reviewer action backend identity (GATE 13)

`ReviewerActionRecord.backendId` + `collectionRef` are persisted
(`ReviewerActionRecord.kt:111`), and the wire codec round-trips them
(`ReviewerActionLedger.kt:150`, `:176-202`). `AnkiReviewerActionReconciler`
(`ReviewerActionRecovery.kt:285-320`) states the rule in source:

```kotlin
// The backend locked into the original action, not the current preference.
val backend = registry.find(record.backendId) ?: return Unresolved(BackendUnavailable())
if (record.cardRef.backendId != backend.id || record.collectionRef?.let { it.backendId != backend.id } == true)
    return Unresolved(SessionInvalid())
```

Registry-routed by persisted identity. **Compliant.**

## DISCOVERY-21-15 / 21-16 — Note mutation and creation backend identity (GATE 17 / GATE 18)

`NoteMutationRecord.backendId` (`edit/NoteMutationRecord.kt:21`) and
`NoteCreationRecord.backendId` (`create/NoteCreationRecord.kt:47`) are persisted, and the
ledgers are backend-scoped (`findActiveForNote(backendId, noteId)`, `activeFor(backendId)`).

**But the coordinators are not registry-routed.** Both take a single
`private val backend: AnkiBackend` (`NoteMutationCoordinator.kt:151`,
`NoteCreationCoordinator.kt:162`), pinned in DI to `ankiDroidBackend`. They fail closed on a
foreign identity — `NoteMutationRefusal.BackendMismatch` (`:344`),
`NoteCreationRefusal.BackendMismatch` (`:277`) — so **no cross-backend replay is possible**,
but a record belonging to any *other* backend could never be recovered at all. With one
registered backend that is unobservable; it becomes a real defect the day a second backend is
registered.

## DISCOVERY-21-17 — Custom study backend identity (GATE 20)

GATE 20 is **BLOCKED** and unimplemented (`docs/GATE_20_CUSTOM_STUDY_CONTRACT.md`: *"Phase C
(implementation) not started — IMPLEMENTATION AUTHORIZED: NO"*). `grep -rn
"FilteredSessionRef\|FilteredSession" app/src/main` returns nothing. `StudyMode.CUSTOM_SESSION`
exists as a wire enum with no client logic.

Nothing to bind. Recorded so GATE 21 does not claim coverage it cannot have.

## DISCOVERY-21-18 — Backend loss during normal reads

| Surface | Behaviour |
|---|---|
| Library read | `AnkiLibraryRepository.performRefresh` returns a typed failure; `publishFailure` falls back to a cached snapshot **only if** `cached.backendId == backend.id` (`:193`). Never switches backend. |
| Card Browser query | `CardBrowserViewModel` observes its one backend; a failure surfaces as a typed error state. `switchBackend(next)` exists and invalidates cursors/queries (`:160-171`) but nothing called it in production. |
| Card Details load | resolved from the route's `cardRef.backendId`; a missing backend renders `CardDetailsRouteErrorScreen` (`AppNavHost.kt:167`). No fallback. |
| Model/schema read | `AnkiDroidBackend.getNoteModel` propagates the typed availability family. No fallback. |

No read path falls back to another backend, and none retries across backends.

## DISCOVERY-21-19 — Backend loss during study

`AnkiStudyEffectExecutor` resolves every scheduler call through the locked identity:
`registry.find(effect.session.context.backendId)?.nextCard(...)` (`:490`),
`registry.find(effect.turn.backendId)?.hydrateCardContent(...)` (`:495`),
`registry.find(commitId.backendId)` for the mutation (`:640`). A vanished backend therefore
produces a typed failure on the *same* backend; `nextCard` returns
`NextCardResult.BackendUnavailable` (`AnkiBackendSelectorTest` pins this). TTS/STT phases are
backend-independent (audio layer).

## DISCOVERY-21-20 — Backend loss during mutation

| Family | Behaviour after the backend disappears |
|---|---|
| ReviewCommit | ledger-routed by `commitId.backendId`; a missing backend yields `BackendUnavailable` / an unresolved reconciliation. Never re-routed. |
| ReviewerAction | `registry.find(record.backendId) ?: Unresolved(BackendUnavailable())`. Never re-routed. |
| NoteMutation | pinned backend; foreign identity → `BackendMismatch` refusal. |
| NoteCreation | pinned backend; foreign identity → `BackendMismatch` refusal. |
| Custom study | not implemented |

**No code anywhere reroutes a mutation to another backend.**

## DISCOVERY-21-21 — PC reconnect identity

Not implemented — there is no PC backend, so there is no reconnect path to inspect. What is
verifiable in code: the *identity* a reconnect would have to match is
`AnkiBackendId.PcAgent(profileId)`, which is configuration-derived, so a reconnect under the
same profile id resolves to the same logical identity **without proving the same collection**
(DISCOVERY-21-03).

## DISCOVERY-21-22 — AnkiDroid collection change while Study-Agent is alive

Not detectable. `AnkiDroidBackend.collectionMatchesBoundSession` returns `true` when the bound
collection key is null, and AnkiDroid refs are always created with `collectionKey = null`
(DISCOVERY-21-03). AnkiDroid profile switching mid-session is therefore **invisible** to this
code.

## DISCOVERY-21-23 — Global preference persistence

Before GATE 21: **none**. No key, no field, no session-only store. The effective preference was
the compile-time constant `AnkiLocalStudyStarter.LOCAL_PREFERENCE`.

## DISCOVERY-21-24 — AUTO selection inputs actually available

| Input | Available? | Source |
|---|---|---|
| backend availability | yes | `AnkiBackend.availability: StateFlow<AnkiAvailability>` |
| capabilities | yes (two projections) | `AnkiBackend.capabilities` + `AnkiAvailability.Ready.capabilities` |
| locality (local vs remote) | **derivable from the identity kind** — not measured | `AnkiBackendId` variant |
| required capability of the operation | **no** (added by GATE 21) | — |
| latency | **no measurement exists** | — |
| connection readiness | PC path only, and unimplemented | — |
| current session | yes | `AnkiSessionContext` |

## DISCOVERY-21-25 — Feature asymmetry

Only one real backend exists, so a two-column matrix would be fabricated. What *is* recorded is
AnkiDroid's own audited report shape (`AnkiDroidApiCapabilityReport`), whose per-feature values
are `SUPPORTED` / `UNSUPPORTED` / `UNKNOWN` and are derived by `AnkiDroidCompatibilityPolicy`
from the pinned provider spec — including the GATE 19 additions `modelCss` and
`modelEnrichedSchema`. `Gate11dAnkiDroidCapabilityTest` pins the commit-guarantee clamp.

| Feature | AnkiDroid (this repo) | PC |
|---|---|---|
| Library / decks | implemented (`AnkiDroidDeckGateway`) | no code |
| Review + rating | implemented (`AnkiDroidRatingGateway`) | no code |
| Browser | `AnkiDroidCardBrowserGateway` exists, capability-gated | no code |
| Card details | implemented (`AnkiDroidCardDetailsMapper`) | no code |
| Edit note | implemented (`AnkiDroidNoteMutationGateway`) | no code |
| Create note | implemented (`AnkiDroidCreationGateway`) | no code |
| Media | `AnkiDroidMediaResolver` (public contract only) | no code |
| Models/templates | `AnkiDroidModelGateway` | no code |
| Custom study | not implemented anywhere | not implemented anywhere |

## DISCOVERY-21-26 — Same resource across backends

No correlation logic exists. No mapping table, no id translation, no "same card on both
backends" concept. `AnkiArchitectureContractTest` pins the opposite: equal numeric ids under
different backends are different references.

## DISCOVERY-21-27 — Sync state

Neither backend exposes sync information. There is no sync timestamp, server id or hkey
anywhere in `core/anki` or `data/anki`. Nothing can prove two collections are synchronized.

## DISCOVERY-21-28 — Cross-backend mutation possibility

**No code allows "read through AnkiDroid, write through PC" for one logical operation.** The
structural reasons: refs are backend-qualified (DISCOVERY-21-05), `ReviewCommitId` embeds the
backend, `AnkiSessionContext` refuses a foreign collection identity, and both note coordinators
refuse `BackendMismatch`.

The residual architectural concern is the *pinned-backend* design of the two note coordinators
(DISCOVERY-21-15/16): it is fail-closed, but it makes "one backend per app" an implicit
invariant rather than an enforced one.

## DISCOVERY-21-29 — Recovery before backend ready

`AppContainer.kt:439`: *"Startup recovery is off the splash path: load both ledgers, do not
mutate study state."* Ledgers are loaded lazily; the study-start path re-checks ledger health
before any scheduler traffic (`AnkiStudyEffectExecutor.begin` returns
`RecoveryBlocked` / `ReviewerActionRecoveryBlocked` before `getDecks()`).
Availability is refreshed at start (`registry.ids.filter(request.preference::accepts).forEach
{ registry.find(it)?.refreshAvailability() }`), i.e. **after** the recovery scan, not before.

## DISCOVERY-21-30 — Multiple unresolved backends

Structurally possible: all four ledgers key on `backendId`, and
`ReviewCommitLedger.unresolved()` / `pendingRecovery(backendId)` /
`recoveryBlocker(backendId, …)` are all backend-scoped. `Gate11eAggregateAuditTest` pins that
an unresolved transaction is not rebound by a preference change.

**Isolation was partial before GATE 21**: commit and reviewer-action recovery is
registry-routed, but note-mutation and note-creation recovery is pinned to one backend
(DISCOVERY-21-15/16), so a PC-owned note transaction would have been unrecoverable rather than
independently recoverable.

## DISCOVERY-21-31 — Backend-specific security

| Backend | Prerequisite | Owner |
|---|---|---|
| AnkiDroid | third-party-declared `com.ichi2.anki.permission.READ_WRITE_DATABASE`; no interactive grant dialog exists; endpoint allowlist (`AnkiDroidEndpoints.forBuild(includeDebugEndpoints = BuildConfig.DEBUG)`) so a release build can never resolve the debug authority | `AnkiDroidPermissionManager`, `AnkiDroidEndpoints` |
| PC agent | not implemented; `docs/SECURITY.md` and `docs/ANKI_WEBVIEW_SECURITY.md` cover transport/token storage (`AndroidSecureTokenStorage`) but no backend-selection trust model exists | — |

No code equates the two. Selection cannot imply security equivalence because only one backend
is selectable today.

## DISCOVERY-21-32 — Performance characteristics

No latency measurement exists for either backend path. `AnkiLibraryRepository` records a
`latencyMs` for deck refresh and `docs/PERFORMANCE.md` covers UI metrics, but there is no
local-vs-remote comparison and no PC path to measure. **Backend selection must not be based on
assumed performance** — and in this repository it cannot be.

## DISCOVERY-21-33 — Offline characteristics

AnkiDroid is the only backend and needs no LAN, no PC and no internet; the PC path does not
exist. So today the app is offline-capable for every implemented Anki feature by construction.

## DISCOVERY-21-34 — Backend UI surfaces

| Screen | Backend context shown before GATE 21 |
|---|---|
| Settings | "Anki integration" section with AnkiDroid status + API spec; the source comment said *"Backend selection is a later gate"* |
| Connection | none (`ConnectionViewModel` has no Anki/backend reference) |
| Library | none — `LibraryUiState` carries a `backendId` in every variant but no screen rendered it |
| Study | none — `StudyScreen` is explicitly backend-neutral (*"the screen never asks which backend"*) |
| Diagnostics | AnkiDroid-specific rows: `"Backend" to (backend?.id?.stableId ?: "ankidroid_local")`, commit-guarantee rows |

**Inconsistency recorded:** Diagnostics defaulted the backend label to `"ankidroid_local"`
even when `backend` was null, i.e. it could report a backend it had not observed.

## DISCOVERY-21-35 — Diagnostic support

Captured before GATE 21: commit phase timeline with `"backend" to commitId.backendId.stableId`
(`AppContainer.kt:621`, `StudySessionMachine.kt:1418`), AnkiDroid capability matrix, ledger
diagnostics. **Not** captured: the preferred backend (it did not exist), the resolved backend
and its reason, the locked session backend as a distinct field, and any routing decision.

## DISCOVERY-21-36 — Discovery matrix

| Property | AnkiDroid | PC |
|---|---|---|
| Stable backend identity | **SUPPORTED** — `AnkiDroidLocal`, `stableId="ankidroid_local"`, `@Serializable`, round-trips via `fromStableId` | **PARTIAL** — `PcAgent(profileId)` is stable *if* the profile id is stable; no code supplies one |
| Collection identity | **UNSUPPORTED** — refs built with `collectionKey = null` (`AnkiDroidDeckMapper.kt:93/114`) | **UNKNOWN** — no implementation |
| Availability detection | **SUPPORTED** — 11-state hierarchy, `statusCode` tokens, `AnkiDroidHealthRepository` owner | **PARTIAL** — `AgentDisconnected`/`AgentAnkiUnavailable` states exist; no producer |
| Runtime capabilities | **SUPPORTED** — `StateFlow<AnkiCapabilities>` + `AnkiDroidApiCapabilityReport` derived per spec | **UNKNOWN** — no implementation |
| Library | **SUPPORTED** — `AnkiDroidDeckGateway`, `AnkiLibraryRepository` | **UNSUPPORTED** — no code |
| Review | **SUPPORTED** — `AnkiDroidReviewGateway`, `AnkiDroidRatingGateway`, `AnkiDroidReviewSession` | **UNSUPPORTED** — no code |
| Mutations | **SUPPORTED** — rating, reviewer actions, note mutation, note creation | **UNSUPPORTED** — no code |
| Model/schema | **SUPPORTED** — `AnkiDroidModelGateway`, GATE 19 templates/CSS | **UNSUPPORTED** — no code |
| Custom study | **UNSUPPORTED** — GATE 20 blocked, no type exists | **UNSUPPORTED** |
| Restart recovery | **PARTIAL** — durable ledgers survive; the session object does not | **UNSUPPORTED** — no code |
| Identity after reconnect | **PARTIAL** — identity is stable, but collection continuity is unverifiable (DISCOVERY-21-22) | **UNKNOWN** — no reconnect path |
| Offline behavior | **SUPPORTED** — local provider, no network dependency in the Anki path | **UNSUPPORTED** |
| Security prerequisites | **SUPPORTED** — `AnkiDroidPermissionManager`, build-gated endpoint allowlist | **UNKNOWN** — not implemented |

## DISCOVERY-21-37 — Unsupported assumptions and safety-critical unknowns

**Assumptions that are NOT supported by this repository:**

1. That AnkiDroid and a desktop collection are the same collection. No identity, no sync state.
2. That a numeric card/note/deck id means the same thing on two backends. Refs are qualified.
3. That a PC reconnect means the same collection. Only the profile id is verified.
4. That AnkiDroid's active profile is stable during a session. It is not observable.
5. That any backend is faster than another. No measurement exists.
6. That GATE 20 custom-study state has a backend binding. GATE 20 is unimplemented.

**Safety-critical unknowns:**

1. AnkiDroid collection/profile change mid-session is **undetectable** — the strongest argument
   against mid-session failover and against treating availability as equivalence.
2. Collection identity is unavailable on the only implemented backend, so collection-lock
   validation (DECISION-21-16) can only fail *open* today; it must be encoded so that a future
   backend with real collection identity gets the check for free.
3. Note-mutation/creation recovery was pinned to one backend — a latent multi-backend defect.
4. Diagnostics could not distinguish preferred / resolved / locked / transaction backend.
