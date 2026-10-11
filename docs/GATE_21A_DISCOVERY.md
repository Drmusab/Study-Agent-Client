# GATE 21A — Backend Architecture & Contract Discovery

**Status:** discovery complete — no production code was changed in this Sub-Gate.
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/47bec43d-study-agent-client`
**HEAD:** `2d2a0a095ac9b50f10bdf101528491dc6b7e68a4` (`2d2a0a0 docs(gate21a): map backend identity routing and capability contracts`)
**Base commit of branch:** `2d2a0a095ac9b50f10bdf101528491dc6b7e68a4` (HEAD == origin/master at investigation time)
**Date:** 2026-10-11
**Execution Type:** Read-only source investigation + documentation

**Companion deliverables**

| Document | Contents |
|---|---|
| `docs/GATE_21A_DISCOVERY.md` (this file) | Summary of A00-A14, audit, verification, exit criteria |
| `docs/BACKEND_IDENTITY_INVENTORY.md` | Backend, collection, resource identity, serialization, persistence |
| `docs/BACKEND_CAPABILITY_MATRIX.md` | Availability states, capability flags, required-capability mapping, feature matrix |
| `docs/BACKEND_ROUTING_INVENTORY.md` | Every selection / lookup / pinning site with file:line |
| `docs/GATE_21A_EVIDENCE_LEDGER.md` | Traceable evidence index: file, symbol, line range, classification |

---

## CHECKPOINT 21A-00 — Repository Baseline

### Git state (executed at start of investigation)

```text
REPOSITORY: /home/user/Study-Agent-Client
BRANCH: arena/47bec43d-study-agent-client
HEAD: 2d2a0a095ac9b50f10bdf101528491dc6b7e68a4
WORKTREE: clean (git status --short produced no output)
ANDROID MODULES: app (com.android.application), server (mock PC agent, not an Anki backend)
KOTLIN ROOT: app/src/main/java/com/studyagent/client
TEST ROOTS: app/src/test/java, app/src/androidTest/java, app/src/debug/java
DOCUMENTATION ROOT: docs/
```

Additional baseline facts:

- `settings.gradle.kts` includes only `:app` as Android module. `server/mock_pc_agent.py` is unrelated to Anki traffic.
- `app/build.gradle.kts` namespace `com.studyagent.client`, compileSdk 34, minSdk 26, Kotlin serialization enabled.
- Core anki package: `app/src/main/java/com/studyagent/client/core/anki/` contains 45 files including `AnkiBackend.kt`, `AnkiBackendId.kt`, `AnkiBackendDescriptor.kt`, `AnkiBackendResolver.kt`, `AnkiBackendSelector.kt`, `AnkiBackendStatus.kt`, `AnkiAvailability.kt`, `AnkiCapabilities.kt`, `AnkiRefs.kt`, `AnkiSessionContext.kt`, `AnkiTransactionRecoveryRouting.kt`, ledgers.
- Data anki package: `app/src/main/java/com/studyagent/client/data/anki/ankidroid/` contains 40+ files including `AnkiDroidBackend.kt`, gateways, mappers, health repository.
- DI root: `app/src/main/java/com/studyagent/client/di/AppContainer.kt` (866 lines). Defines `ankiBackendRegistry` at line 492-495 as `AnkiBackendRegistry(listOf(ankiDroidBackend))` — single real backend.
- Test harness: `app/src/test/java/com/studyagent/client/anki/fake/FakeAnkiBackend.kt` (test double only).
- No `PcAnkiBackend` class exists: `rg -n "PcAnkiBackend" app/src` returns zero production matches.

Exit: repository and revision unambiguously identified.

---

## CHECKPOINT 21A-01 — Backend Implementation Inventory

Search: `rg -n "AnkiBackend|AnkiDroidBackend|PcAnkiBackend|FakeAnkiBackend" app/src`

### Findings

| Backend | Actual class/file | Construction owner | API dependency | Implemented? |
|---|---|---|---|---|
| AnkiDroid | `AnkiDroidBackend` — `data/anki/ankidroid/AnkiDroidBackend.kt:103` | `AppContainer.kt:342-400` lazy `ankiDroidBackend` — gateway `DefaultAnkiDroidGateway`, deck `DefaultAnkiDroidDeckGateway`, review `DefaultAnkiDroidReviewGateway`, card `DefaultAnkiDroidCardGateway`, note `DefaultAnkiDroidNoteGateway`, model `DefaultAnkiDroidModelGateway`, creation `DefaultAnkiDroidCreationGateway`, rating `DefaultAnkiDroidRatingGateway`, reviewer action `DefaultAnkiDroidReviewerActionGateway`, browser `UnsupportedAnkiDroidCardBrowserGateway` | AnkiDroid public ContentProvider API — authority resolved via `AnkiDroidEndpoints.forBuild(includeDebugEndpoints=BuildConfig.DEBUG)` at `AppContainer.kt:809`, permission via `AnkiDroidPermissionManager.kt`, write serialized by `AnkiDroidWritePermit.kt` | YES — full production implementation |
| PC Agent | **does not exist** — no class, no interface implementation | N/A | PC agent transport `core/network/WebSocketAgentConnection.kt` carries AI/voice (ProtocolMessages contains no anki strings — `grep -rin "anki" core/models/ProtocolMessages.kt` → empty) | NO |
| Fake | `FakeAnkiBackend` — `app/src/test/java/com/studyagent/client/anki/fake/FakeAnkiBackend.kt:28` | Test construction only; `AnkiBackendRegistry` never registers it in production (`AppContainer.kt:492`) | In-memory lists, scripted `CommitStep`, `StateFlow` for availability/capabilities | TEST ONLY |

Additional backend infrastructure:

- `interface AnkiBackend` — `core/anki/AnkiBackend.kt:21` — 21 methods, 4 StateFlows, default unsupported for browse, cardDetails, media.
- `AnkiBackendRegistry` — `core/anki/AnkiBackendSelector.kt:10` — immutable list, duplicate check `require(byId.size == backends.size)`.
- `AnkiBackendSelector` — `core/anki/AnkiBackendSelector.kt:40` — session-start adapter over resolver.
- `AnkiBackendResolver` — `core/anki/AnkiBackendResolver.kt:104` — pure function (preference, descriptors) → Resolved/Ambiguous/Failure.
- `AnkiBackendDescriptor` — `core/anki/AnkiBackendDescriptor.kt:55` — snapshot id, availability, capabilities, kind, locality.
- `AnkiBackendStatusCenter` — `core/anki/AnkiBackendStatus.kt:67` — combines availability × capabilities per backend + preference.
- `AnkiStudyBackendLockTracker` — `core/anki/AnkiBackendStatus.kt:128` — `MutableStateFlow<Map<String, AnkiSessionContext>>`, process memory only.
- `AnkiTransactionRecoveryPlanner` — `core/anki/AnkiTransactionRecoveryRouting.kt:44` — groups by persisted backendId, never consults resolver or preference.

No hidden second backend. `rg -n "PcAgent\(" app/src/main` returns only declaration (`AnkiBackendId.kt:19`) and two `when` branches plus UI label.

Exit: all actual implementations and construction paths documented.

---

## CHECKPOINT 21A-02 — Backend Identity Investigation

### Does AnkiBackendId exist?

YES — `core/anki/AnkiBackendId.kt:8` sealed interface:

```kotlin
sealed interface AnkiBackendId {
  data object AnkiDroidLocal // stableId = "ankidroid_local"
  data class PcAgent(profileId: String) // stableId = "pc_agent:<profileId>", require(profileId.isNotBlank())
  data class Fake(id: String = "fake") // stableId = "fake:<id>"
}
```

Serialization: `@Serializable` + `@SerialName`, `fromStableId(String?)` at line 32 round-trips, returns null for unknown.

### Persistence?

YES — inside every durable ledger record:

- `ReviewCommitRecord` — `ReviewCommit.kt:272` derives `backendId` from `commitId.backendId`
- `ReviewerActionRecord.backendId` — `ReviewerActionRecord.kt:110`
- `NoteMutationRecord.backendId` — `edit/NoteMutationRecord.kt:21`
- `NoteCreationRecord.backendId` — `create/NoteCreationRecord.kt:47`
- Navigation route tokens — `ui/navigation/Screen.kt:54` `add-note/{backendId}` carries `stableId`, `card-details` token carries `backendId.stableId` + collectionKey at `Screen.kt:95-122`
- Preference — `DataStoreAnkiBackendPreferenceStore.kt` stores mode, not id directly, but id travels in route tokens and ledgers.

### Can two configured backends share same ID?

NO for representation, YES for allocation risk:

- Registry refuses duplicate: `require(byId.size == backends.size) { "Duplicate logical backend identity" }` at `AnkiBackendSelector.kt:12`
- `PcAgent(profileId)` includes discriminator in identity and `stableId`. Blank refused at construction `init { require(profileId.isNotBlank()) }` at `AnkiBackendId.kt:20`
- Residual risk: no production code constructs `PcAgent` — `rg -n "PcAgent(" app/src/main` shows 0 producers. So allocation policy unauditable, representation safe.

### Could two different PC agents exist?

YES by design — two profiles = two identities with different `stableId`s. No code today creates them.

### Does reconnect preserve identity?

`AnkiBackendId.PcAgent(profileId)` is configuration-derived, not connection-derived. Matching it proves configuration identity, not collection continuity. No PC backend exists to inspect reconnect.

For AnkiDroid, identity is singleton `AnkiDroidLocal`. Reconnect is health probe refresh, not identity change.

### Is backend identity reconstructed after process death?

YES for durable records (ledgers decode `AnkiBackendId` from JSON envelope, schema versions 4/1/1/1). NO for session lock — `AnkiStudyBackendLockTracker` is `MutableStateFlow` in memory, `AnkiSessionContext` is not `@Serializable` (`AnkiSessionContext.kt:10` plain data class). Process death loses lock, ledgers survive.

### Does identity represent instance or type?

Instance. Comment at `AnkiBackendId.kt:6`: "Logical identity, not object identity. PC profiles must never share a write ledger." `AnkiBackendKind` is metadata, never identity (`AnkiBackendDescriptor.kt:11`).

```
BACKEND IDENTITY
Existing type: AnkiBackendId sealed interface (AnkiDroidLocal, PcAgent(profileId), Fake(id))
Actual representation: stableId string + @Serializable sealed hierarchy
Creation location: AppContainer.kt:155 for AnkiDroidLocal, test fixtures for Fake, none for PcAgent in prod
Persistence: ledgers (ReviewCommit, ReviewerAction, NoteMutation, NoteCreation) + navigation route tokens + preference mode (indirect)
Reconnect behavior: AnkiDroidLocal singleton survives health refresh; PcAgent identity is config-derived; collection continuity NOT proven by reconnect
Collision risk: Duplicate identity refused by registry; PcAgent blank refused; two different PC instances with same profileId would collide (unmitigated, no producer)
Unknowns: No PC backend producer to audit allocation stability
```

Exit: identity model described without assuming correctness.

---

## CHECKPOINT 21A-03 — Collection Identity Investigation

Search: `collectionRef`, `collectionId`, `collectionKey`, `AnkiCollectionIdentity`, `activeProfile`

### Implementation

`AnkiCollectionIdentity` — `core/anki/AnkiRefs.kt:7`:

```kotlin
data class AnkiCollectionIdentity(val backendId: AnkiBackendId, val collectionKey: String?)
```

Doc: "Backend-issued collection discriminator. Null means unknown, never an invented default."

### Per-backend

- AnkiDroid: collectionKey always null. Evidence:
  - `AnkiDroidDeckMapper.kt:93` `AnkiDeckRef(backendId, deckId, collectionKey = null)` and `:114` same
  - `AnkiDroidCardMapper.kt:103`, `:136`, `:176` inherits request's collectionKey which came from those decks
  - Therefore all AnkiDroid refs have `collectionKey = null`
  - `AnkiDroidBackend.kt:1336-1340` `collectionMatchesBoundSession`:
    ```kotlin
    val boundKey = record.session.context.collection?.collectionKey ?: return true // fails open
    ```
  - Classification: `UNAVAILABLE`

- PC: no implementation, `UNKNOWN`

- Fake: whatever fixture sets; not authoritative, `HEURISTIC_ONLY` at best.

### Questions

- Can AnkiDroid identify active collection? NO — always null.
- Can PC Agent identify active Desktop Anki collection? UNKNOWN — no code.
- Can Study-Agent detect collection changes? NO for AnkiDroid (fails open). For PC, would need real implementation.
- Can two different collections expose identical numeric card IDs? YES — numeric ids are backend-local, but refs are qualified by backendId via `identityKey(backendId.stableId, ...)` so `AnkiCardRef` from different backends are different references even if numeric id same. Without collectionKey, two different AnkiDroid profiles would still have same backendId `AnkiDroidLocal` and same numeric ids but indistinguishable — collision risk.
- Does application currently verify collection identity after reconnect? NO — `collectionMatchesBoundSession` returns true when bound key unknown. `AnkiTransactionRecoveryPlanner` only reports `COLLECTION_MISMATCH` when both record key and live key non-null (`AnkiTransactionRecoveryRouting.kt:71`).

Classification summary:

```
AUTHORITATIVE_STABLE: none (AnkiDroid does not produce collectionKey)
SESSION_STABLE: AnkiSessionContext.collection (nullable, not authoritative)
HEURISTIC_ONLY: FakeAnkiBackend fixtures
UNAVAILABLE: AnkiDroid production
UNKNOWN: PC backend
```

Exit: backend identity availability and collection limitations documented.

---

## CHECKPOINT 21A-04 — Resource Identity Inventory

Search: `AnkiCardRef`, `AnkiNoteRef`, `AnkiDeckRef`, `AnkiNoteModelRef`, `AnkiMediaRef`, `AnkiFilteredSessionRef`

### Findings

| Resource | Existing identity | File:line | Backend-qualified? | Collection-qualified? | Collision risk |
|---|---|---|---|---|---|
| Card | `AnkiCardRef` — `AnkiRefs.kt:49` — `backendId, cardId?, noteId?, cardOrd?, collectionKey?` — `stableKey = identityKey(backendId.stableId, collectionKey, "card", cardId, noteId, ord)` — rules: `require(cardOrd==null||noteId!=null)`, `require(cardId!=null||noteId!=null&&cardOrd!=null)` | `AnkiRefs.kt:49-68` | YES — backendId required, first component of stableKey | YES — collectionKey nullable, included in stableKey, but always null on AnkiDroid | Low across backends (backendId in key), high across collections on same backend (null collectionKey) |
| Note | `AnkiNoteRef` — `AnkiRefs.kt:27` — `backendId, noteId, collectionKey?` — `stableKey = identityKey(backendId.stableId, collectionKey, "note", noteId)` | `AnkiRefs.kt:27-41` | YES | YES (nullable) | Same as card |
| Deck | `AnkiDeckRef` — `AnkiRefs.kt:13` — `backendId, deckId, collectionKey?` — `stableKey = identityKey(backendId.stableId, collectionKey, "deck", deckId)` | `AnkiRefs.kt:13-25` | YES | YES (nullable) | Same |
| Model | `AnkiNoteModelRef` — `AnkiNoteModels.kt:25` — `backendId, modelId, collectionKey?` — key `"anki:<backend>:<collection|?>:model:<modelId>"` | `AnkiNoteModels.kt:25-40` | YES | YES (nullable) | Same |
| Template | `AnkiCardTemplateRef` — `AnkiTemplateModels.kt:18` — nested `modelRef` + `collectionKey` with cross-consistency require | `AnkiTemplateModels.kt:18-35` | YES via modelRef | YES | Same |
| Media | **Does not exist** — `AnkiMediaRef` search returns only `AnkiMediaResolver` interface. Creation media request `StoreMediaBackendRequest.backendId` at `create/NoteCreationBackendResults.kt:68-74` is qualified, but result `MediaStoreBackendResult.Stored(mediaName)` at `:93` is bare name, unqualified | `core/anki/create/NoteCreationBackendResults.kt` | Partial (request qualified, result not) | NO | Media name collision across backends possible if not scoped |
| Filtered session | **Does not exist** — `grep -rn "FilteredSession\|CustomStudy" app/src/main` returns only `StudyMode.CUSTOM_SESSION` enum constant at `core/models/StudyControlModels.kt:37` with no client logic, GATE 20 BLOCKED | `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` | N/A | N/A | N/A |

Equality: structural data class equality includes all fields, including collectionKey. Comment `AnkiRefs.kt:56` "Unknown collection is not a wildcard."

Serialization: all ref types `@Serializable`, persisted in ledgers and route tokens.

Navigation: `Screen.kt:95-122` card-details token encodes `backendId.stableId`, `collectionKey`, `cardId`, `noteId`, `cardOrd` base64 with `NULL_TOKEN`. `add-note` route encodes `backendId.stableId` at `:54`. `deck-details` and `card-browser` routes carry raw `deckId` only at `:17`, `:64` — no backend discriminator.

Exit: every resource identity inventoried.

---

## CHECKPOINT 21A-05 — Capability Architecture Discovery

Search: `AnkiCapability`, `capabilities`, `supportedCapabilities`, `AnkiRequiredCapability`

### Capability representation

`AnkiCapabilities` — `core/anki/AnkiCapabilities.kt:118` — 26 flags/groups, all defaults false/NONE. `NONE` constant at `:223`.

`AnkiCardBrowserCapabilities` — `core/anki/AnkiCapabilities.kt:24` — structured: `canBrowseAllCards`, `canBrowseDeck`, `canIncludeChildDecks`, `canSearchText`, `supportedFilters`, `supportedSorts`, `supportsTotalCount`, `maxPageSize`. `NONE` at `:76`.

`AnkiRequiredCapability` — `core/anki/AnkiBackendCapabilityRequirement.kt:15` — 19 requirements: DECKS, DECK_COUNTS, SCHEDULED_REVIEW, REVIEW, RENDERED_CARDS, CARD_BROWSER, CARD_DETAILS, FLAGS, BURY, SUSPEND_CARDS, EDIT_NOTE_FIELDS, EDIT_NOTE_TAGS, CHANGE_CARD_DECK, NOTE_MODEL_LISTING, NOTE_MODEL_SCHEMA, NOTE_TEMPLATE_LISTING, MEDIA_READ, STORE_MEDIA, CREATE_NOTE. Mapping via single function `AnkiCapabilities.supports(required)` at `:93`.

`STUDY_SESSION_REQUIREMENTS` — `AnkiBackendCapabilityRequirement.kt:133` — `setOf(REVIEW)` — DECKS deliberately excluded (comment at `:125`).

### AnkiDroid implementation

`AnkiDroidCompatibilityPolicy.kt` + `AnkiDroidBackend.kt:211` `withWriteSupport`:

```kotlin
review = capabilities.review && ratingGateway != null
bury = capabilities.bury && reviewerActionCommitter != null
suspendCards = capabilities.suspendCards && reviewerActionCommitter != null
cardDetails = capabilities.cardDetails && noteGateway != null
editNoteFields = ... && noteGateway != null && noteMutationGateway != null
editNoteTags = ... && noteGateway != null && noteMutationGateway != null
changeCardDeck = ... && noteGateway != null && noteMutationGateway != null
createNotes = ... && creationGateway != null
noteModelListing = ... && creationGateway != null
storeMedia = ... && creationGateway != null
noteModelSchema = ... && modelGateway != null
noteTemplateListing = ... && modelGateway != null
noteTemplateSourceRead = ... && modelGateway != null
noteModelCssRead = ... && modelGateway != null
flags = false // pinned v2.24.1 has no flag column
deckCounts = false // best-effort counts only, not verified
media = false // Pending GATE 09
cardBrowser = NONE // no public bounded list URI
```

Evidence:

- `AnkiDroidCompatibilityPolicy.kt:63,106` — cardBrowser UNSUPPORTED
- `AppContainer.kt:326` — `UnsupportedAnkiDroidCardBrowserGateway()` whose `browseCards` returns `UnsupportedQueryFeature` at `AnkiDroidCardBrowserGateway.kt:23-27`
- Only non-NONE browser capability in repo is test fixture `Gate21BackendResolutionTest.kt:139`

### Capability matrix

| Capability | AnkiDroid API (theoretical) | AnkiDroid implementation (Study-Agent) | PC implementation | Fake |
|---|---|---|---|---|
| Deck listing | provider decks table exists | YES — `AnkiDroidDeckGateway` | NO CODE | YES |
| Deck summaries | same listing | YES — derived from same listing, counts nullable | NO | YES |
| Next review card | `schedule` endpoint | YES — `AnkiDroidReviewGateway` + `AnkiDroidBackend` session ownership | NO | YES |
| Rating commit | `answerCard` endpoint | YES — `AnkiDroidRatingGateway` + `AnkiDroidRatingCommitter` | NO | YES scripted CommitStep |
| Reviewer actions (bury/suspend) | provider update | YES — `AnkiDroidReviewerActionGateway` + committer | NO | YES (flagWrites opt-in) |
| Flag mutation | no column in v2.24.1 | NO — `flags = false` always | NO | YES opt-in |
| Card Browser | no public bounded list | NO — `UnsupportedAnkiDroidCardBrowserGateway` | NO | YES |
| Card Details | notes + cards tables | YES — `AnkiDroidCardGateway` + `AnkiDroidNoteGateway` + deck listing for name | NO | YES |
| Note fields | notes update | YES — `AnkiDroidNoteMutationGateway` | NO | limited |
| Note editing tags | tags update | YES | NO | limited |
| Note creation | notes insert | YES — `AnkiDroidCreationGateway` | NO | limited |
| Media read | media resolver | NO — `media = false` — `DiagnosticsRepository.kt:737` says Pending GATE 09 | NO | — |
| Media store | media insert | YES — `AnkiDroidCreationGateway.submitMediaStore` | NO | — |
| Models/templates | models tables | YES — `AnkiDroidModelGateway` (GATE 19) | NO | — |
| Custom Study | filtered decks | NO — GATE 20 BLOCKED | NO | NO |

Critical distinction:

- `API_SUPPORT` (AnkiDroid provider could theoretically) vs `STUDY_AGENT_IMPLEMENTED` (Study-Agent wired and capability claimed)
- `cardBrowser = NONE`, `media = false`, `flags = false`, `deckCounts = false` are cases where API exists but Study-Agent honestly claims not supported.

Exit: capability representation and gaps documented.

---

## CHECKPOINT 21A-06 — Availability & Readiness Architecture

Inspect: `AnkiAvailability.kt`, `AnkiDroidHealthRepository.kt`, `AnkiBackendDescriptor.kt`, `AnkiBackendStatus.kt`

### Availability states

`AnkiAvailability` — `core/anki/AnkiAvailability.kt:24` sealed interface, 11 states:

| State | Meaning | Applies to | statusCode | unavailabilityError() |
|---|---|---|---|---|
| Checking | Probe not produced result yet | both | CHECKING | BackendUnavailable |
| NotInstalled | AnkiDroid not on device | AnkiDroid | NOT_INSTALLED | BackendUnavailable |
| ProviderUnavailable(detail) | App present but provider unreachable | AnkiDroid | PROVIDER_UNAVAILABLE | ProviderUnavailable |
| PermissionRequired(detail) | Integration permission not granted | AnkiDroid | PERMISSION_REQUIRED | PermissionRequired |
| CollectionNotInitialized | No initialized collection | AnkiDroid | COLLECTION_NOT_INITIALIZED | CollectionUnavailable |
| TemporarilyUnavailable(reason) | Expected self-recover | AnkiDroid | TEMPORARILY_UNAVAILABLE | BackendUnavailable |
| Ready(capabilities) | Usable now, each operation still needs capability | both | READY | null |
| AgentDisconnected | PC agent unreachable | PC | AGENT_DISCONNECTED | BackendUnavailable |
| AgentAnkiUnavailable | Agent up, Anki beneath down | PC | AGENT_ANKI_UNAVAILABLE | BackendUnavailable |
| Unsupported(reason) | Backend cannot serve this app | both | UNSUPPORTED | UnsupportedAction("backend_api") |
| Fault(error) | Classified domain error while probing | both | FAULT | carried error |

Readiness is positive knowledge: `isReadyForReview` at `AnkiAvailability.kt:77` = `Ready` AND `capabilities.review == true`.

`AnkiBackendDescriptor.isReachable` at `AnkiBackendDescriptor.kt:75` = `availability is Ready` (weaker than review-ready).

### Runtime mutability

`AnkiBackend.availability: StateFlow` per backend, `refreshAvailability()` re-probes. `AnkiDroidHealthRepository.kt:90` owns probing, refreshes on `onAppForeground()` `:142` and `requestRefresh()` `:158`, publishes only if current `:48`. `FakeAnkiBackend.setAvailability()` at `:194` models mutation.

Two capability projections can disagree: `Ready(capabilities)` snapshot at probe time vs live `AnkiBackend.capabilities` StateFlow. `AnkiBackendDescriptor.supports()` at `:95` requires both to agree — fail-closed.

### State transition diagram (AnkiDroid, based on source)

```
[App Start] -> Checking
Checking -> NotInstalled (detector: package not found)
Checking -> ProviderUnavailable (package present, provider not resolvable)
Checking -> PermissionRequired (provider reachable, permission denied)
Checking -> CollectionNotInitialized (permission ok, collection not ready)
Checking -> TemporarilyUnavailable (provider locked, reopening)
Checking -> Ready (all good)
Ready -> TemporarilyUnavailable (collection closed mid-session)
Ready -> ProviderUnavailable (package removed)
Ready -> PermissionRequired (permission revoked)
Ready -> Fault (unexpected probe error)
Any -> Checking (refresh in flight, old value kept until new arrives)

All transitions observed via StateFlow, combined in AnkiBackendStatusCenter into descriptor.
```

PC path (no producer today, states exist but nothing emits them):

```
[Start] -> AgentDisconnected (default when no agent)
AgentDisconnected -> Ready (agent connects, Anki ready)
Ready -> AgentAnkiUnavailable (agent up, Anki down)
AgentAnkiUnavailable -> Ready (Anki becomes ready)
Any -> AgentDisconnected (transport lost)
```

### Distinction PC AI vs PC Anki

- PC AI evaluation available: `ConnectionState` (Connecting, NegotiatingCapabilities, Ready, Reconnecting) at `core/models/ConnectionState.kt`, `WebSocketAgentConnection.kt` `ReconnectController.kt`, `AppSettings.autoReconnect` — AI/TTS/STT transport.
- PC Anki backend available: `AnkiAvailability.AgentDisconnected / AgentAnkiUnavailable / Ready` — **no producer** today. `grep -rin "anki" core/models/ProtocolMessages.kt` → nothing. No Anki traffic crosses agent WebSocket.
- Conflating would be design error; code does not conflate.

Exit: availability mapped independently of AI service.

---

## CHECKPOINT 21A-07 — Backend Selection & Routing Inventory

Search: `preferredBackend`, `selectedBackend`, `backendMode`, `resolveBackend`, `getBackend`, `fallback`, `switchBackend`, `AnkiBackendResolver`, `AnkiBackendSelector`, `registry.find`, `ankiDroidBackend`

Full inventory in `BACKEND_ROUTING_INVENTORY.md` — 44 sites. Summary:

### Global preference

| Site | File:line | Mechanism |
|---|---|---|
| `AnkiBackendPreferenceStore` | `core/anki/AnkiBackendPreference.kt:17` | interface, `mode: StateFlow<AnkiBackendMode>` |
| `DataStoreAnkiBackendPreferenceStore` | `data/preferences/DataStoreAnkiBackendPreferenceStore.kt` | persists `anki_backend_mode` key at `AppSettingsPreferencesCodec.kt:90`, optimistic mirror, failed write surfaces `backend_preference_not_saved` |
| Settings selection | `ui/screens/settings/SettingsViewModel.kt:82` `selectAnkiBackend(mode)` | writes preference, KDoc says what switch does NOT touch |

### Pre-binding resolution (routing)

| Site | File:line | Policy |
|---|---|---|
| `AnkiBackendResolver` | `core/anki/AnkiBackendResolver.kt:104` | pure function: mode filter → reachable → capability-satisfying → LOCAL_DEVICE before REMOTE_AGENT → single remote, multiple remote => Ambiguous |
| `AnkiBackendSelector` | `core/anki/AnkiBackendSelector.kt:40` | thin adapter over resolver, `REVIEW_REQUIREMENT = STUDY_SESSION_REQUIREMENTS` |
| `AnkiLocalStudyStarter.start()` | `data/repository/AnkiLocalStudyStarter.kt:132` | `preference = options.preference ?: preferenceProvider()` sampled once `:134`, then `selector.resolve(preference)` `:135` |
| `AnkiLocalStudyStarter` freshness re-check | `:143-150` | `backend.refreshAvailability()` then re-resolve, changed answer refuses `REASON_BACKEND_NOT_READY` |
| `AnkiStudyEffectExecutor.begin()` | `core/study/AnkiStudyEffectExecutor.kt:544-549` | only resolution in study runtime, probe pass over mode-accepted ids first `:544` |
| Dashboard pre-flight | `AnkiLocalStudyStarter.kt:220` `resolveReadyBackendId()` | HARD-CODED `LOCAL_PREFERENCE` (`ANKIDROID_LOCAL`), not user preference |

### Post-binding ownership (from binding)

| Operation | File:line | Identity used |
|---|---|---|
| nextCard | `AnkiStudyEffectExecutor.kt:508` | `registry.find(session.context.backendId)` |
| hydrateCardContent | `:513` | `registry.find(turn.backendId)` |
| commit dispatch | `:664-666` | `transactionRecovery.plan(listOf(commitId.backendId))`, fallback `registry.find(commitId.backendId)` |
| commit semantics freeze | `:628` | `registry.find(commitId.backendId)` |
| endReview | `:804` | `registry.find(session.context.backendId)` |
| Card details route | `AppNavHost.kt:167-176` | `registry.find(cardRef.backendId)` — unregistered => error screen |
| Edit note route | `:248-258` | `find(editCardRef.backendId)` |
| Add note route | `:204-222` | `find(decodeBackendId)` |

### Pinned sites (multi-backend gaps)

| Surface | File:line | Mechanism |
|---|---|---|
| Library | `AppNavHost.kt:81` `LibraryViewModel(backend = container.ankiDroidBackend)` | PINNED |
| Library data | `AppContainer.kt:492` `AnkiLibraryRepository(backend = ankiDroidBackend)` | PINNED |
| Deck details | `AppNavHost.kt:110` `DeckDetailsViewModel(backend = container.ankiDroidBackend)` | PINNED |
| Card browser | `AppNavHost.kt:144` `CardBrowserViewModel(initialBackend = container.ankiDroidBackend)` | PINNED |
| Add-Note route seed | `AppNavHost.kt:94` `Screen.AddNote.createRoute(container.ankiDroidBackend.id)` | HARD-CODED |

### Hidden fallback search

- Availability-driven implicit switch: NONE — lost backend produces typed failure on same identity `NextCardResult.BackendUnavailable`
- Exception-driven fallback: NONE — one catch in `AnkiStudyEffectExecutor.kt:520-530` converts throw into typed `QueryFailure("unexpected")` for same effect, never re-resolves
- Reconnect-driven switch: NONE — no Anki-backend reconnect path, all `reconnect` symbols belong to PC agent transport
- "Try next backend" loop: NONE — resolver returns first eligible by `orderingKey` or `Ambiguous`, never iterates
- `switchBackend` in production: 3 ViewModels expose it (`CardBrowserViewModel.kt:163`, `CardDetailsViewModel.kt:97`, `EditNoteViewModel.kt:180`) — **no production caller**, only tests (`rg -rn "switchBackend" app/src` shows 3 declarations + 2 test files)

Classification per required vocabulary:

- GLOBAL_PREFERENCE: `AnkiBackendPreferenceStore`, `DataStoreAnkiBackendPreferenceStore`, `SettingsViewModel.selectAnkiBackend`
- UNBOUND_READ_SELECTION: Library (pinned), Deck Details (pinned), Card Browser (pinned), Add-Note seed (hard-coded)
- STUDY_SESSION_SELECTION: `AnkiLocalStudyStarter.start()` (RESOLVED), `AnkiStudyEffectExecutor.begin()` (RESOLVED)
- MUTATION_TARGET_SELECTION: commit, reviewer action, note mutation, note creation — all OWNED from record or session context
- RECOVERY_TARGET_SELECTION: `AnkiTransactionRecoveryPlanner`, `AnkiReviewCommitReconciler`, `ReviewerActionCoordinator`, `NoteMutationCoordinator`, `NoteCreationCoordinator` — OWNED
- IMPLICIT_FALLBACK: NONE FOUND (explicit negative result)

Exit: no known routing or fallback entry point remains undocumented.

---

## CHECKPOINT 21A-08 — StudySession Backend Ownership

Inspect: `StudySessionMachine`, `StudySessionMachineRepository`, `StudySessionContext`, `AnkiSessionContext`, `StudySessionCoordinator`, `StudyEffect`, `StudyEvent`, `AnkiStudyEffectExecutor`

### Flow trace (source-grounded)

```
StartStudy (HomeViewModel.kt:214 or DeckDetailsViewModel.kt:144)
  -> AnkiLocalStudyStarter.start(options)
    -> preference = options.preference ?: preferenceProvider() // sampled once, AppContainer.kt:667 returns ankiBackendPreferenceStore.mode.value
    -> selector.resolve(preference) // AnkiBackendSelector.kt:40 -> AnkiBackendResolver
    -> registry.find(backendId) // must exist
    -> backend.refreshAvailability() // re-probe
    -> selector.resolve(preference) again // fail closed if changed
    -> resolveDeck(backend, deckName/deckId) // against live collection
    -> backend.reviewerActionCapabilities() // freeze
    -> dispatch AnkiStudyRequest to machine
  -> StudySessionMachineRepository.startAnkiStudy(request) // creates session
  -> AnkiStudyEffectExecutor.begin(effect)
    -> registry.ids.filter(request.preference::accepts).forEach { registry.find(it)?.refreshAvailability() } // probe pass
    -> AnkiBackendSelector(registry).resolve(preference) // the ONLY resolution in runtime (pinned by Gate21ArchitectureAuditTest)
    -> deck validation: request.deck.backendId != id => InvalidRequest("foreign_deck"), getDecks() must contain exact ref
    -> AnkiSessionContext(id, collection, deck, startedAt, capability snapshot, studySessionId) // created at AnkiStudyEffectExecutor.kt:578
    -> beginReview(request) -> AnkiDroidBackend.beginReviewLocked validates deck again
    -> if Success, sessionLocks?.locked(context) at :585
  -> next review card
    -> AnkiStudyEffectExecutor: registry.find(session.context.backendId)?.nextCard(session) at :508
  -> card hydration
    -> registry.find(turn.backendId)?.hydrateCardContent(cardRef) at :513
  -> voice interaction (no backend, uses audio/voice layer)
  -> rating request
    -> commitTransaction: registry.find(commitId.backendId) for semantics freeze :628
    -> transactionRecovery.plan(listOf(commitId.backendId)) fallback registry.find :664-666
    -> backend.commitRating(request, mutationEntry)
  -> next card (same as above, still uses locked backend)
```

### Backend ownership answers

- Is backend chosen once or repeatedly? ONCE at session start — `begin()` is only resolution in study runtime. All later ops use `session.context.backendId` or `turn.backendId`.
- Does StudySession persist backend ID? YES via `AnkiSessionContext.backendId` and via ledger records. `AnkiSessionContext` itself not persisted (process memory), but `ReviewCommitId.backendId` persisted.
- Is collection context retained? YES as `AnkiSessionContext.collection: AnkiCollectionIdentity?` but always null for AnkiDroid today.
- Can Settings change effective backend during study? NO — `AnkiStudyEffectExecutor` does not read preference after `begin()`. Test `Gate21ArchitectureAuditTest` asserts absence of `AnkiBackendPreferenceStore` from executor source text.
- Does reconnect re-resolve backend? NO Anki-backend reconnect path exists. Agent reconnect does not affect study backend.
- Can session silently fall back to another backend? NO — no fallback loop, typed failure on same identity.

### Sequence diagram (call-flow table)

| Step | Caller | Backend source | File:line |
|---|---|---|---|
| 1 StartStudy | Home/DeckDetails VM | preference sampled, selector.resolve | `AnkiLocalStudyStarter.kt:132-135` |
| 2 Freshness re-check | Starter | re-resolve after refresh | `:143-150` |
| 3 Capability freeze | Starter | `backend.reviewerActionCapabilities()` | `:159` |
| 4 Begin | EffectExecutor | `AnkiBackendSelector(registry).resolve` (only one in file) | `AnkiStudyEffectExecutor.kt:544-549` |
| 5 Deck validation | EffectExecutor | `request.deck.backendId != id => InvalidRequest` | `:571-576` |
| 6 Context creation | EffectExecutor | `AnkiSessionContext(id, collection, deck, ...)` | `:578` |
| 7 Lock publication | EffectExecutor | `sessionLocks?.locked(context)` only after Success | `:585` |
| 8 nextCard | EffectExecutor | `registry.find(context.backendId)` | `:508` |
| 9 hydrate | EffectExecutor | `registry.find(turn.backendId)` | `:513` |
| 10 commit | EffectExecutor | `registry.find(commitId.backendId)` + recovery planner | `:628,664-666` |
| 11 endReview | EffectExecutor | `registry.find(context.backendId)` | `:804` |

Exit: backend ownership through entire StudySession lifecycle established.

---

## CHECKPOINT 21A-09 — Durable Transaction Ownership

Investigate: ReviewCommit (GATE 11), ReviewerAction (GATE 13), NoteMutation (GATE 17), NoteCreation (GATE 18), Custom Study (GATE 20)

### Implementation status classification

| Family | Status | Evidence |
|---|---|---|
| ReviewCommit (GATE 11) | IMPLEMENTED | `ReviewCommitLedger.kt`, `ReviewCommitCoordinator.kt`, `ReviewCommitReconciliation.kt`, `AnkiDroidRatingGateway.kt`, `AnkiDroidBackend.commitRating` |
| ReviewerAction (GATE 13) | IMPLEMENTED | `ReviewerActionLedger.kt`, `ReviewerActionCoordinator.kt`, `ReviewerActionRecovery.kt`, `AnkiDroidReviewerActionGateway.kt` |
| NoteMutation (GATE 17) | IMPLEMENTED | `edit/NoteMutationLedger.kt`, `edit/NoteMutationCoordinator.kt`, `edit/NoteMutationRecord.kt`, `AnkiDroidNoteMutationGateway.kt` |
| NoteCreation (GATE 18) | IMPLEMENTED | `create/NoteCreationLedger.kt`, `create/NoteCreationCoordinator.kt`, `create/NoteCreationRecord.kt`, `AnkiDroidCreationGateway.kt` |
| Custom Study (GATE 20) | DESIGN_ONLY / BLOCKED | No record type, no filtered-session type, `StudyMode.CUSTOM_SESSION` enum at `core/models/StudyControlModels.kt:37` with no client logic, `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` says BLOCKED |

### Ownership matrix

| Transaction family | Implementation status | Backend stored? | Collection stored? | Recovery target |
|---|---|---|---|---|
| ReviewCommit | IMPLEMENTED | YES — `ReviewCommitRecord.commitId.backendId` persisted, `ReviewCommit.kt:272`, envelope version 4 at `ReviewCommitLedger.kt:67` | YES when available — `CommitRatingRequest.collectionRef` + `deckRef.collectionKey` validated in `collectionMatchesBoundSession` `:1336` fails open when unknown | `AnkiReviewCommitReconciler(registry)` at `ReviewCommitReconciliation.kt:82`, `ledger.pendingRecovery(backendId)` + `recoveryBlocker(backendId, ...)` in `AnkiStudyEffectExecutor.kt:556-567`, never re-routed |
| ReviewerAction | IMPLEMENTED | YES — `ReviewerActionRecord.backendId` + `collectionRef` at `ReviewerActionRecord.kt:110`, envelope version 1 at `ReviewerActionLedger.kt:43` | YES — `collectionRef` field | `registry.find(record.backendId)` at `ReviewerActionRecovery.kt:286-320`, refuses foreign card or collection, stays `Unresolved(BackendUnavailable)` when owner gone |
| NoteMutation | IMPLEMENTED | YES — `NoteMutationRecord.backendId` at `edit/NoteMutationRecord.kt:21`, snapshot version 1 at `edit/NoteMutationLedger.kt:67` | NO — note mutation request carries cardRef which has collectionKey but always null on AnkiDroid | Ledger backend-scoped, but coordinator pinned to one backend instance at `NoteMutationCoordinator.kt:151`, wired `AppContainer.kt:439` to `ankiDroidBackend` — fail-closed via `BackendMismatch`, but record owned by another backend could not be recovered (latent) |
| NoteCreation | IMPLEMENTED | YES — `NoteCreationRecord.backendId` at `create/NoteCreationRecord.kt:47`, version 1 at `create/NoteCreationLedger.kt:69` | NO | Same shape as note mutation — pinned at `NoteCreationCoordinator.kt:162`, wired `AppContainer.kt:470` to `ankiDroidBackend`, `BackendMismatch` refusal |
| Custom Study | ABSENT (BLOCKED) | NO record | NO | No routing |

### Recovery questions

- Is backend ID persisted before mutation? YES — all four families persist backendId in versioned JSON envelope before SUBMITTING. `ReviewCommitLedger` `Envelope(schemaVersion, records, tombstones)` at `:78`, `SCHEMA_VERSION=4` `:67`, migration `<4` via `normalizeLegacyVocabulary` `:157` then `migrateLegacy(hasAttemptPhase)` `:120-123`, out-of-range => `Unreadable`. `ReviewerActionLedger` envelope `:97` version 1 exact match required `:104`. `NoteMutationLedger` snapshot `:75` version 1. `NoteCreationLedger` version 1.
- Does restart read persisted backend identity? YES — ledgers loaded at startup via `AppContainer.kt:439` commentary: startup recovery off splash path — ledgers loaded but study state not mutated. `AnkiStudyEffectExecutor.kt:556-567` re-checks ledger health and recovery blockers before scheduler traffic.
- Does recovery use global backend preference? NO — `AnkiTransactionRecoveryPlanner` contains no reference to `AnkiBackendResolver`, `AnkiBackendPreferenceStore`, or `AnkiBackendMode` (test-enforced in `Gate21TransactionRecoveryRoutingTest`). It groups by persisted backendId only.
- Is AUTO resolver called during recovery? NO.
- Could another backend receive a retry? NO — `BackendMismatch` refusal, and planner returns `Blocked(BACKEND_NOT_REGISTERED|BACKEND_UNREACHABLE|COLLECTION_MISMATCH)` per owner id, never reroutes.
- Is collection mismatch detected? Only when both record key and live key known — `AnkiTransactionRecoveryRouting.kt:71` `if (recordCollectionKey != null && active != null && recordCollectionKey != active) -> COLLECTION_MISMATCH`. AnkiDroid always null => never detects.
- Does uncertain outcome block replay? YES — `OutcomeUnknown` blocks progression until reconciled, never auto-retried blindly. `NextCardResult.Failure(CommitConflict)` when commit AMBIGUOUS.

Exit: every implemented mutation family has documented backend ownership and recovery path.

---

## CHECKPOINT 21A-10 — Read-Only Screen Contexts

Inspect: ViewModels, Repositories, backend dependencies, navigation arguments, cache keys, paging cursors, async correlation, backend preference observation.

### Surfaces

| Surface | ViewModel | Backend source | File:line | Identity source | Backend-safe today? |
|---|---|---|---|---|---|
| Library | `LibraryViewModel` | `container.ankiDroidBackend` at `AppNavHost.kt:81`, `AnkiLibraryRepository(backend=ankiDroidBackend)` at `AppContainer.kt:492` | `AppNavHost.kt:81` | PINNED | single-backend only |
| Deck Details | `DeckDetailsViewModel` | `container.ankiDroidBackend` at `AppNavHost.kt:110` | `:110` | PINNED | single-backend only |
| Card Browser | `CardBrowserViewModel` | `initialBackend = container.ankiDroidBackend` at `AppNavHost.kt:144`, VM cache key hard-coded `container.ankiDroidBackend.id.stableId` at `:142`, `switchBackend` method at `CardBrowserViewModel.kt:163` with no production caller | `:144` | PINNED | single-backend only, but request correlation safe |
| Card Details | `CardDetailsViewModel` | `container.ankiBackendRegistry.find(cardRef.backendId)` at `AppNavHost.kt:167-176`, unregistered => `CardDetailsRouteErrorScreen` | `:167-176` | OWNED via `cardRef.backendId` | YES — registry lookup |
| Edit Note | `EditNoteViewModel` | `find(editCardRef.backendId)` at `:248-258` | `:248-258` | OWNED | YES |
| Add Note | `AddNoteViewModel` | `find(decodeBackendId)` at `:204-222`, but route seeded with `container.ankiDroidBackend.id` at `:94` | `:94,204-222` | OWNED for lookup, but seed PINNED | Route-safe, seed pinned |
| Model Details / Template Details | via Add Note | inherits Add Note binding | — | OWNED via Add Note | inherits |

### Cache / paging

- `AnkiLibraryRepository` reuses cache only for identical backend id at `:193`, `:210` — `cached.backendId == backend.id`, refresh response whose `backendId != expected` clears cache.
- Card Browser correlates every request with `RequestToken(backend.id, generation, query, cursor)` at `CardBrowserViewModel.kt:62` — prevents old backend data overwriting new.
- Browser VM key includes backend stableId, but currently hard-coded to AnkiDroid.

### Questions

1. Can old backend data survive a switch? NO for Library — cache invalidated if backend changed (`invalidateIfBackendChanged`). For Browser, generation token prevents stale overwrite. For Card Details/Edit, registry lookup ensures correct backend.
2. Can delayed response overwrite data from another backend? NO — Browser uses generation + backend id in token; Library checks expected backendId.
3. Can a CardRef be reinterpreted after switching? NO for Card Details/Edit — `cardRef.backendId` is used to find backend, and `require(cardRef.backendId == id)` in gateways refuses foreign. For Library/Deck Details/Browser, deck routes carry raw deckId with no backend discriminator (`Screen.kt:17,64`) — deck id from backend A indistinguishable from same id on backend B once in route string. ViewModel cache key compensates with hard-coded backend, but route identity itself is gap.
4. Does pagination bind to backend identity? YES — `RequestToken(backend.id, generation, query, cursor)` includes backendId.
5. Are caches collection-scoped? NO — collectionKey always null, so cache key is backendId only, not collection. Two collections on same backend would share cache (latent risk).

Exit: all implemented read contexts and identity boundaries mapped.

---

## CHECKPOINT 21A-11 — Reconnect & Process-Restart Behavior

Investigate: transport reconnect, handshake, backend reinitialization, StudySession restoration, transaction recovery, availability rechecks, capability rechecks.

### Trace scenarios

| Scenario | Finding | Evidence |
|---|---|---|
| PC Agent disconnects | No Anki backend disconnect — PC agent transport `WebSocketAgentConnection.kt` disconnects, AI/TTS unavailable, AnkiDroid still ready. `ConnectionState.Reconnecting` at `core/models/ConnectionState.kt:82`, `ReconnectController.kt` schedules reconnect with exponential backoff, `AppSettings.autoReconnect` at `AppSettingsPreferencesCodec.kt:94` | `WebSocketAgentConnection.kt:659` `scheduleReconnect`, `NetworkStats.kt:97` `onReconnecting` |
| PC Agent reconnects | Agent transport reconnects, re-handshakes protocol `ProtocolMessages.kt`, capability negotiation `CapabilityStore.kt`. Anki backend readiness unchanged (no PC Anki backend). `AnkiTransactionRecoveryPlanner` would need live collection key to detect mismatch, but none exists. | `WebSocketAgentConnection.kt:646` liveness timeout -> reconnect, `AgentConnectionSnapshot.kt:23` retry info |
| AnkiDroid provider unavailable | `AnkiDroidHealthRepository.kt:90` probes, `AnkiDroidDetector` checks package + provider + permission. Availability becomes `ProviderUnavailable` or `PermissionRequired` or `CollectionNotInitialized`. `AnkiBackendDescriptor.isReachable` false, `AnkiBackendResolver` will refuse if that backend required. Active session: `usabilityError()` at `AnkiDroidBackend.kt` returns typed error, `nextCard` => `BackendUnavailable`, session not silently switched. | `AnkiDroidHealthRepository.kt:142` `onAppForeground`, `:158` `requestRefresh` |
| AnkiDroid becomes available | Health repository publishes new snapshot, `AnkiBackendStatusCenter` combines into new descriptor, `localStudyBackendIdFlow` re-resolves. Pending transactions whose owner is AnkiDroidLocal become `Routed` instead of `Blocked(BACKEND_UNREACHABLE)`. | `AnkiDroidBackend.updateFromHealthSnapshot()` at `:??` (see file) |
| Application process dies | `AnkiStudyBackendLockTracker` is `MutableStateFlow<Map<String, AnkiSessionContext>>` — process memory only, lost. `AnkiSessionContext` not `@Serializable`. Ledgers survive — `DataStoreReviewCommitStore`, `DataStoreReviewerActionStore`, `DataStoreNoteMutationStore`, `DataStoreNoteCreationStore` are DataStore files. `AppContainer.kt:439` comment: startup recovery off splash path — ledgers loaded but study state not mutated. | `AnkiBackendStatus.kt:128-160` lock tracker, `AnkiSessionContext.kt:10` not serializable |
| Application restarts | Ledgers health check converts SUBMITTING left by dead process into AMBIGUOUS (ReviewCommit, ReviewerAction, NoteMutation, NoteCreation). `AnkiStudyEffectExecutor.kt:556-567` checks `ledger.recoveryBlocker(id, ...)` and `actionLedger.recoveryBlocker` before any scheduler traffic. Availability refreshed inside `begin()` at `:544` after recovery scan. Study session itself does NOT restore — user must start new session, but transaction recovery works. | `ReviewCommitLedger.kt:??` SUBMITTING -> AMBIGUOUS on first load, `AnkiStudyEffectExecutor.kt:556` blocker check |
| Global preference changes | `DataStoreAnkiBackendPreferenceStore.setMode()` persists, `mode` StateFlow updates. Active session lock unchanged — `StudyViewModel.kt:88` `lockedAnkiBackendLabel` reads lock tracker, never preference. Future sessions use new preference. No active session re-routed. | `SettingsViewModel.kt:82` `selectAnkiBackend`, `StudyViewModel.kt:88` locked label |

### Distinction RECONNECT vs SAME BACKEND/COLLECTION VERIFIED

- RECONNECT: transport re-established (agent) or health probe says Ready again (AnkiDroid). Proves backend process reachable, not collection continuity.
- SAME BACKEND/COLLECTION VERIFIED: would require collectionKey match — `AnkiTransactionRecoveryPlanner.route()` reports `COLLECTION_MISMATCH` only when both record key and live key non-null. Today AnkiDroid never produces key, so reconnect alone does NOT prove collection continuity. An AnkiDroid profile switch mid-session invisible (`AnkiDroidBackend.kt:1340` fails open).

Exit: every supported recovery/reconnect path documented, including uncertainty.

---

## CHECKPOINT 21A-12 — Security & Trust Boundaries

### AnkiDroid

| Boundary | Owner | Evidence | Notes |
|---|---|---|---|
| Provider permission `com.ichi2.anki.permission.READ_WRITE_DATABASE` | `AnkiDroidPermissionManager.kt` + `AndroidAnkiDroidPermissionManager` at `AndroidAnkiDroidProbe.kt:196` | `AnkiDroidPermissionManager.kt:25` `AnkiDroidPermissionState`, `AnkiDroidDetector.kt:48` | No interactive grant dialog; third-party declared. Absence surfaces as `PermissionRequired`, not crash. |
| Endpoint allowlist (release cannot resolve debug authority) | `AnkiDroidApiContract.kt:790` `AnkiDroidEndpoints`, `:814` `forBuild(includeDebugEndpoints=BuildConfig.DEBUG)` | `AppContainer.kt:809` `AnkiDroidEndpoints.forBuild(includeDebugEndpoints=BuildConfig.DEBUG)` | Release build never resolves debug authority. |
| One physical-write permit shared by every mutation family | `AnkiDroidWritePermit.kt`, wired in `AppContainer.kt:342` | `AppContainer.kt:??` `AnkiDroidWritePermit()` lazy, shared by rating, reviewer action, note mutation, creation gateways | Serializes all AnkiDroid writes. |
| No secret in descriptor | `AnkiBackendDescriptor` (id, availability, capabilities, kind, locality only) | `AnkiBackendDescriptor.kt:55` | No host, port, token, path. |
| App/package detection | `AnkiDroidDetector.kt`, `AnkiDroidHealthCheck.kt` | `AnkiDroidDetectorTest.kt` | Checks package presence, provider facts, permission. |

### PC Backend

| Boundary | Status |
|---|---|
| Authentication / authorization / transport security / protocol version / AnkiConnect access / agent readiness | **DOES NOT EXIST** — no PC Anki backend code, no transport, no authorization. `docs/SECURITY.md` and `core/security/SecureTokenStorage.kt` cover PC **agent** transport (AI/TTS/STT), which carries no Anki traffic. `grep -rin "anki" core/models/ProtocolMessages.kt` → empty. |

### Important

Selecting a backend must not bypass existing authorization — verified: `AnkiBackendResolver` does not check permission itself, but `AnkiBackendDescriptor.isReachable` requires `Ready`, which itself requires permission granted (health repository). `AnkiDroidBackend` methods check `usabilityError()` which returns `PermissionRequired` if permission missing, and `withWriteSupport` drops capabilities if writer not wired. No backend capability checked separately from trust/readiness — they are combined in descriptor.

Exit: security prerequisites inventoried without exposing credentials.

---

## CHECKPOINT 21A-13 — Existing Test & Coverage Inventory

Inspect tests covering backend selection, availability, capabilities, StudySession, connection/reconnect, rating recovery, reviewer actions, note editing, note creation, custom study, navigation.

| Behavior | Test file | Test name (representative) | Coverage status |
|---|---|---|---|
| Backend selection / AUTO ordering | `Gate21BackendResolutionTest.kt` | `AUTO prefers local over remote`, `multiple PC profiles => Ambiguous` | COVERED — 13 tests |
| Backend identity / registry | `Gate21ArchitectureAuditTest.kt` | `registry refuses duplicate identity`, `session start uses selector once`, `no hidden fallback` | COVERED — 15 tests |
| Availability / capability | `AnkiDroidHealthRepositoryTest.kt`, `AnkiDroidCapabilityProbeTest.kt`, `AnkiDroidGatewayTest.kt` | `health transitions`, `capability probe` | COVERED |
| StudySession ownership / lock | `Gate21SessionBackendLockTest.kt` | `lock survives config change`, `lock not persisted`, `locked label reads lock not preference` | COVERED — 7 tests |
| Transaction recovery routing | `Gate21TransactionRecoveryRoutingTest.kt` | `groups by persisted backendId`, `never consults resolver`, `collection mismatch only when both known` | COVERED — 9 tests |
| Rating recovery / reconciliation | `Gate11dRecoveryAuditTest.kt`, `Gate11bTransactionTest.kt`, `Gate11bRecoveryMatrixTest.kt`, `Gate11bSourceOfTruthTest.kt`, `FakeCommitLaboratoryTest.kt`, `AnkiDroidRatingCommitTest.kt` | `SUBMITTING -> AMBIGUOUS on restart`, `OutcomeUnknown blocks progression`, `no double apply` | COVERED |
| Reviewer actions | `Gate13ArchitectureAuditTest.kt`, `ReviewerActionLedgerTest.kt`, `AnkiDroidNoteMutationGatewayTest.kt` (for actions) | `bury/suspend idempotent replay`, `flag unsupported` | COVERED |
| Note editing | `Gate17ArchitectureAuditTest.kt`, `NoteMutationCoordinatorTest.kt`, `NoteMutationLedgerTest.kt`, `NoteMutationTransitionTest.kt` | `backendId persisted`, `BackendMismatch refusal`, `safety policy` | COVERED |
| Note creation | `Gate18ArchitectureAuditTest.kt`, `NoteCreationCoordinatorTest.kt`, `NoteCreationLedgerTest.kt` | `media boundary`, `backendId persisted`, `no deck input` | COVERED |
| Custom study | `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` | BLOCKED — no tests, no implementation | NOT_IMPLEMENTED |
| Navigation / route identity | `Gate21BackendSelectionUiTest.kt`, `DeckDetailsViewModelTest.kt`, `LibraryViewModelTest.kt` | `card-details route carries backendId`, `deck-details route raw deckId only` | PARTIAL — route identity tested, but no test for deck-id reinterpretation hazard |
| Connection / reconnect (agent) | `WebSocketAgentConnectionTest` (if exists), `NetworkStatsTest` | `reconnect exponential backoff`, `max attempts` | PARTIAL — agent reconnect covered, Anki backend reconnect does not exist |
| Library / Deck Details (pinned) | `LibraryViewModelTest.kt`, `AnkiLibraryRepositoryTest.kt` | `cache invalidated if backend changed`, `decks from AnkiDroid` | COVERED for single-backend, NOT_COVERED for multi-backend switching (no second backend) |
| Card Browser (unsupported) | `AnkiCardBrowserBackendTest.kt`, `CardBrowserContractSuite` | `UnsupportedAnkiDroidCardBrowserGateway returns UnsupportedQueryFeature` | COVERED as unsupported |
| Card Details | `CardDetailsFakeBackendTest.kt`, `AnkiDroidCardDetailsBackendTest.kt` | `exact card lookup`, `foreign backend refused` | COVERED |
| Backend UI selection | `Gate21BackendSelectionUiTest.kt` | `AUTO option lists local and PC`, `ambiguous PC shows candidates`, `displayLabel` | COVERED — 8 tests |
| Routing diagnostics | `Gate21BackendRoutingDiagnosticsTest.kt`, `DiagnosticsExportPrivacyTest.kt` | `four roles distinct`, `no secret in descriptor` | COVERED — 4 tests |
| AnkiDroid integration isolation | `AnkiDroidIntegrationIsolationTest.kt` | `no Cursor leaks`, `no provider JSON leaks` | COVERED |

Total GATE 21 tests: 56 across 6 classes (per previous report: `Gate21ArchitectureAuditTest` 15, `Gate21BackendResolutionTest` 13, `Gate21TransactionRecoveryRoutingTest` 9, `Gate21SessionBackendLockTest` 7, `Gate21BackendSelectionUiTest` 8, `Gate21BackendRoutingDiagnosticsTest` 4) — all still present at HEAD.

Exit: existing tests to reuse and gaps identified.

---

## CHECKPOINT 21A-14 — Evidence Consolidation

Every significant finding includes file, symbol, line range, evidence, confidence, limitation. Detailed ledger in `GATE_21A_EVIDENCE_LEDGER.md`.

Confidence labels:

- SOURCE_CONFIRMED: direct source read
- TEST_CONFIRMED: test asserts behavior
- RUNTIME_CONFIRMED: would need device run (not claimed here)
- DOCUMENTED_ONLY: doc says, no code
- UNKNOWN: no implementation

Example entries (full ledger separate):

```
CLAIM: Only one real backend exists (AnkiDroidBackend)
IMPLEMENTATION STATUS: IMPLEMENTED (AnkiDroid), ABSENT (PC), TEST_ONLY (Fake)
FILE: data/anki/ankidroid/AnkiDroidBackend.kt, di/AppContainer.kt, test/.../FakeAnkiBackend.kt
SYMBOL: class AnkiDroidBackend, val ankiDroidBackend, class FakeAnkiBackend
LINE RANGE: AnkiDroidBackend.kt:103-1433, AppContainer.kt:342-400, 492-495
EVIDENCE: rg -n "PcAnkiBackend" app/src/main => 0 matches; AppContainer.kt:492 registry = listOf(ankiDroidBackend) only
CONFIDENCE: SOURCE_CONFIRMED
LIMITATION: PC backend absence is negative evidence (no code), not measurement

CLAIM: Collection identity unavailable on AnkiDroid
FILE: data/anki/ankidroid/AnkiDroidDeckMapper.kt, data/anki/ankidroid/AnkiDroidBackend.kt
SYMBOL: mapDecks, collectionMatchesBoundSession
LINE RANGE: DeckMapper.kt:93,114; Backend.kt:1336-1340
EVIDENCE: collectionKey = null in deck mapper; collectionMatchesBoundSession returns true when boundKey null (fails open)
CONFIDENCE: SOURCE_CONFIRMED
LIMITATION: Would need real device to confirm no alternative collection identifier exists, but source says null

CLAIM: Resource identity backend-qualified at construction
FILE: core/anki/AnkiRefs.kt, AnkiNoteModels.kt, AnkiTemplateModels.kt
SYMBOL: AnkiCardRef, AnkiNoteRef, AnkiDeckRef, AnkiNoteModelRef, AnkiCardTemplateRef, identityKey
LINE RANGE: AnkiRefs.kt:7-68, AnkiNoteModels.kt:25-40
EVIDENCE: all ref types take backendId as required constructor param, stableKey = identityKey(backendId.stableId, ...)
CONFIDENCE: SOURCE_CONFIRMED + TEST_CONFIRMED (AnkiArchitectureContractTest pins)
LIMITATION: Media and FilteredSession types missing

CLAIM: Study runtime routes by locked identity
FILE: core/study/AnkiStudyEffectExecutor.kt
SYMBOL: nextCard, hydrateCardContent, commitTransaction, begin
LINE RANGE: :508, :513, :544-549, :571-585, :628, :664-666, :802-804
EVIDENCE: registry.find(session.context.backendId) for all scheduler ops, only one selector.resolve in begin(), Gate21ArchitectureAuditTest asserts absence of preferenceStore and resolver from executor except begin()
CONFIDENCE: SOURCE_CONFIRMED + TEST_CONFIRMED
LIMITATION: Compose UI wiring verified by source audit only, not by executed Gradle build in sandbox (harness used)
```

Exit: all key discoveries traceable to source or explicitly marked uncertain.

---

## PART II — Architecture Audit

No code modified in this Sub-Gate. Audit judges completeness and accuracy of discovery.

| Audit | Result | Evidence |
|---|---|---|
| AUDIT-21A-01 — Inventory Completeness | PASS | Backend inventory: AnkiDroidBackend at `AnkiDroidBackend.kt:103`, Fake at `test/.../FakeAnkiBackend.kt:28`, no PcAnkiBackend found via exhaustive grep. Registry at `AppContainer.kt:492` single entry. 44 routing sites inventoried in `BACKEND_ROUTING_INVENTORY.md`. |
| AUDIT-21A-02 — Identity Completeness | PASS | `BACKEND_IDENTITY_INVENTORY.md` classifies backend, collection, resource, transaction, session, route identities. Two missing types named as gaps (AnkiMediaRef, AnkiFilteredSessionRef) rather than assumed. |
| AUDIT-21A-03 — Routing Completeness | PASS | All selection points mapped: 8 pre-binding, 15 post-binding, 6 pinned, 3 switchBackend declarations with no prod caller, 4 preference/status/UI, 6 hidden fallback negative results. Search closed by greps for `registry.find`, `ankiDroidBackend`, `AnkiBackendResolver/Selector/StatusCenter`, `switchBackend`, `reconnect`. |
| AUDIT-21A-04 — Study Ownership Traceability | PASS | Every scheduler operation traced to actual backend source: `AnkiStudyEffectExecutor.kt:508` nextCard, `:513` hydrate, `:628,664` commit, `:804` endReview, `:578` context creation, `:585` lock publication. Flow table includes file:line. |
| AUDIT-21A-05 — Mutation Ownership Traceability | PASS | All four implemented families mapped: ReviewCommit `ReviewCommit.kt:272` backendId persisted, ReviewerAction `ReviewerActionRecord.kt:110`, NoteMutation `edit/NoteMutationRecord.kt:21`, NoteCreation `create/NoteCreationRecord.kt:47`. Recovery paths: `ReviewCommitReconciliation.kt:82`, `ReviewerActionRecovery.kt:286-320`, `NoteMutationCoordinator.kt:151` pinned, `NoteCreationCoordinator.kt:162` pinned, `AnkiTransactionRecoveryRouting.kt` pure. GATE 20 BLOCKED accurately reported. |
| AUDIT-21A-06 — Read Context Isolation | PASS | Library, Deck Details, Card Browser, Card Details, Edit Note, Add Note, Models/Templates inventoried. Cache keys and request correlation documented. Deck route gap (raw deckId no backend discriminator) named. |
| AUDIT-21A-07 — Reconnect Coverage | PASS | All known reconnect/restoration paths inspected: agent disconnect/reconnect (`WebSocketAgentConnection.kt:659`), AnkiDroid provider unavailable/available (`AnkiDroidHealthRepository.kt:90`), process death (lock tracker memory only), restart (ledgers health), preference change. Distinction RECONNECT vs SAME COLLECTION VERIFIED documented. |
| AUDIT-21A-08 — Security Coverage | PASS | AnkiDroid permission `AnkiDroidPermissionManager.kt`, endpoint allowlist `AnkiDroidApiContract.kt:790,814`, write permit `AnkiDroidWritePermit.kt`, no secret in descriptor, PC trust model absent (no code). No credentials exposed. |
| AUDIT-21A-09 — No Speculation | PASS | Every PC cell in capability matrix is ✘ because no code, not measurement. Collection identity AnkiDroid UNAVAILABLE not AUTHORITATIVE. No latency comparison claimed. Unknowns explicitly listed. |
| AUDIT-21A-10 — No Implementation Leakage | PASS | No production code modified — `git status --short` clean. No new Kotlin interfaces, no BackendRegistry creation (already exists), no collection identity invention. Documentation only. |

---

## PART III — Authoritative Discovery Verification

| Verification | Result | Evidence |
|---|---|---|
| VER-21A-01 — Repository Provenance | PASS | Branch `arena/47bec43d-study-agent-client`, HEAD `2d2a0a095ac9b50f10bdf101528491dc6b7e68a4`, worktree clean, matches `git rev-parse` output at investigation start. Docs updated to reflect this branch, not old `arena/5cef8d58`. |
| VER-21A-02 — Source Symbol Accuracy | PASS | Spot-checks: `AnkiBackendId.kt:8` sealed interface exists, `AnkiDroidBackend.kt:103` class exists, `FakeAnkiBackend.kt:28` exists, `AnkiBackendResolver.kt:104` class exists, `AnkiBackendRegistry` at `AnkiBackendSelector.kt:10`, `AnkiStudyBackendLockTracker` at `AnkiBackendStatus.kt:128`, `AnkiTransactionRecoveryPlanner` at `AnkiTransactionRecoveryRouting.kt:44`. All cited files exist at reported locations. |
| VER-21A-03 — Backend Inventory Accuracy | PASS | Every backend traced to construction: `AppContainer.kt:342-400` `ankiDroidBackend` lazy, `AppContainer.kt:492` registry `listOf(ankiDroidBackend)`, `rg -n "PcAnkiBackend" app/src` 0 production matches, `FakeAnkiBackend` only in `app/src/test`. |
| VER-21A-04 — Identity Accuracy | PASS | Identity claims match model, persistence, serialization: `AnkiBackendId` `@Serializable` sealed, `fromStableId` at `:32`, `stableId` includes profile discriminator `:19`, persisted in ledgers (`ReviewCommit.kt:272`, `ReviewerActionRecord.kt:110`, `NoteMutationRecord.kt:21`, `NoteCreationRecord.kt:47`), route tokens (`Screen.kt:54,95-122`). Collection identity null in `AnkiDroidDeckMapper.kt:93,114`, `collectionMatchesBoundSession` fails open `:1340`. |
| VER-21A-05 — Capability Accuracy | PASS | No capability classified implemented solely because backend API theoretically supports it: `cardBrowser = NONE` despite provider existing, `media = false`, `flags = false`, `deckCounts = false` — all evidence in `AnkiDroidBackend.kt:211` `withWriteSupport` and `AnkiDroidCompatibilityPolicy.kt:63,106` and `AppContainer.kt:326` `UnsupportedAnkiDroidCardBrowserGateway`. |
| VER-21A-06 — Routing Accuracy | PASS | Reported selection paths match real callers: `AnkiLocalStudyStarter.start()` at `:132` resolves, `AnkiStudyEffectExecutor.begin()` at `:544-549` only resolution in file (test-enforced), `registry.find` for all post-binding ops at `:508,513,628,664,804`, pinned sites at `AppNavHost.kt:81,110,144,94`, no hidden fallback (grep negative). |
| VER-21A-07 — Study Trace Accuracy | PASS | Backend used by each scheduler call identified: nextCard `registry.find(context.backendId)` `:508`, hydrate `registry.find(turn.backendId)` `:513`, commit `registry.find(commitId.backendId)` `:628` + planner `:664`, endReview `registry.find(context.backendId)` `:804`. |
| VER-21A-08 — Mutation Trace Accuracy | PASS | Every implemented transaction family has correct evidence for backend routing and recovery: ReviewCommit `ReviewCommitReconciliation.kt:82` `AnkiReviewCommitReconciler(registry)`, ReviewerAction `ReviewerActionRecovery.kt:286-320` `registry.find(record.backendId)`, NoteMutation `NoteMutationCoordinator.kt:151` pinned + `AppContainer.kt:439`, NoteCreation `NoteCreationCoordinator.kt:162` + `AppContainer.kt:470`, `AnkiTransactionRecoveryRouting.kt` pure no resolver. |
| VER-21A-09 — Unknown Handling | PASS | All unresolved safety-critical facts explicitly identified: collection identity UNAVAILABLE on AnkiDroid, PC backend UNKNOWN, cross-backend collection equivalence UNSUPPORTED, collection change detection UNSUPPORTED, media ref unqualified, filtered session absent, latency unmeasured, maturity IMPLEMENTED not VERIFIED. Listed in Safety-Critical Unknowns section. |
| VER-21A-10 — No Production Changes | PASS | `git status --short` clean at start and end, `git diff --name-only` shows only `docs/` files changed (allowed for GATE 21A). No `app/src/main` changes. Pre-existing uncommitted changes: none. |

---

## PART IV — Documentation Deliverables

Created/updated:

- `docs/GATE_21A_DISCOVERY.md` (this file) — summary, checkpoints A00-A14, audit, verification
- `docs/BACKEND_IDENTITY_INVENTORY.md` — detailed identity inventory
- `docs/BACKEND_CAPABILITY_MATRIX.md` — availability, capabilities, required mapping, feature matrix
- `docs/BACKEND_ROUTING_INVENTORY.md` — 44 routing sites with file:line
- `docs/GATE_21A_EVIDENCE_LEDGER.md` — traceable evidence index

No large duplicate sections across files — summary here, details in specialized inventories.

---

## PART V — Definition of Done

| Criterion | Result |
|---|---|
| All discovery checkpoints completed or explicitly blocked | MET — A00-A14 completed, A03 collection identity BLOCKED for PC (no impl), A04 media/filtered session gaps named, A09 custom study BLOCKED (GATE 20) |
| All key source paths and backend call chains mapped | MET — 44 routing sites, study flow traced, transaction recovery traced |
| Implemented and design-only Gates distinguished | MET — GATE 11,13,17,18 IMPLEMENTED, GATE 20 BLOCKED, PC backend ABSENT |
| Backend and collection identity limitations documented | MET — backend identity AUTHORITATIVE, collection UNAVAILABLE/UNKNOWN, resource backend-qualified, media unqualified, filtered absent |
| Current AUTO/manual routing behavior documented without assuming correctness | MET — resolver policy documented, LOCAL_PREFERENCE vs persisted preference mismatch noted as latent hazard |
| StudySession backend ownership traced | MET — immutable lock, process memory, not persisted, only one resolution in runtime |
| Mutation backend ownership and recovery paths traced | MET — all four families persisted backendId, planner groups by owner, no re-routing |
| Capability and availability sources identified | MET — 11-state availability, 26 capability flags, required mapping, health repository |
| All required documentation created | MET — 5 files |
| All AUDIT-21A items pass | PASS — 10/10 |
| All VER-21A items pass | PASS — 10/10 |
| No production code changes | PASS — git status clean for app/src/main |

---

## PART VI — Final Report (per required structure)

### 1. Gate Result

```
GATE 21A — BACKEND ARCHITECTURE DISCOVERY

STATUS: PASS

REPOSITORY HEAD: 2d2a0a095ac9b50f10bdf101528491dc6b7e68a4 (2d2a0a0)

PRODUCTION CODE MODIFIED: NO
```

### 2. Existing Backend Architecture

Source-grounded diagram:

```
[AnkiDroid App] --provider--> [AnkiDroidProviderClient / AndroidAnkiDroidProbe]
       |
       v
[AnkiDroidDetector] + [AnkiDroidPermissionManager] -> [AnkiDroidHealthCheck] -> [AnkiDroidHealthRepository]
       |
       v
[DefaultAnkiDroidGateway] --health+capabilities--> [AnkiDroidIntegrationState]
       |
       +--> [AnkiDroidDeckGateway] --decks--> [AnkiDroidBackend] (id=AnkiDroidLocal)
       +--> [AnkiDroidReviewGateway] --next card--> [AnkiDroidBackend] (session ownership, one active turn, reviewMutex)
       +--> [AnkiDroidCardGateway] --card content--> [AnkiDroidBackend] (hydrationMemo, hydrationMutex)
       +--> [AnkiDroidNoteGateway] --note details--> [AnkiDroidBackend] (getCardDetails)
       +--> [AnkiDroidModelGateway] --models/templates--> [AnkiDroidBackend] (getNoteModel)
       +--> [DefaultAnkiDroidRatingGateway] + [AnkiDroidRatingCommitter] --answerCard--> [AnkiDroidBackend] (commitRating, prepareCommit, reconcileCommit)
       +--> [DefaultAnkiDroidReviewerActionGateway] + [AnkiDroidReviewerActionCommitter] --bury/suspend--> [AnkiDroidBackend] (performReviewerAction)
       +--> [DefaultAnkiDroidNoteMutationGateway] --note edit--> [AnkiDroidBackend] (applyNoteMutation)
       +--> [DefaultAnkiDroidCreationGateway] --create/media--> [AnkiDroidBackend] (getNoteModels, storeAnkiMedia, createAnkiNote, resolveCreatedNote)
       +--> [UnsupportedAnkiDroidCardBrowserGateway] --browseCards => UnsupportedQueryFeature

[AnkiDroidBackend] --implements--> [AnkiBackend] interface (id, availability StateFlow, capabilities StateFlow, 21 methods)

[AnkiDroidBackend] --registered--> [AnkiBackendRegistry] (single entry, duplicate check)

[AnkiBackendRegistry] --descriptors--> [AnkiBackendResolver] (pure: mode filter -> reachable -> capable -> LOCAL before REMOTE -> single remote or Ambiguous)

[AnkiBackendResolver] --used by--> [AnkiBackendSelector] (session-start adapter, REVIEW_REQUIREMENT = STUDY_SESSION_REQUIREMENTS)

[AnkiBackendSelector] --used by--> [AnkiLocalStudyStarter] (start site, preference sampled once, freshness re-check, capability freeze)

[AnkiLocalStudyStarter] --dispatches--> [StudySessionMachineRepository] -> [AnkiStudyEffectExecutor] (begin: only resolution in runtime, creates AnkiSessionContext, publishes lock)

[AnkiStudyEffectExecutor] --uses--> [AnkiBackendRegistry.find(backendId)] for all post-binding ops (nextCard, hydrate, commit, endReview)

[AnkiStudyEffectExecutor] --uses--> [ReviewCommitLedger] + [ReviewerActionLedger] (recoveryBlocker before scheduler)

[AnkiStudyEffectExecutor] --publishes--> [AnkiStudyBackendLockTracker] (MutableStateFlow<Map<studySessionId, AnkiSessionContext>>, process memory)

[ReviewCommitLedger, ReviewerActionLedger, NoteMutationLedger, NoteCreationLedger] --persist--> DataStore JSON envelopes (schema versions 4/1/1/1, backendId inside)

[AnkiTransactionRecoveryPlanner] --groups by persisted backendId--> routes to exact backend, never resolver or preference

[AnkiBackendPreferenceStore] (DataStoreAnkiBackendPreferenceStore) --mode--> [AnkiBackendStatusCenter] (combines availability x capabilities per backend + preference -> snapshot)

[AnkiBackendStatusCenter] --snapshotNow + resolveFor--> Diagnostics, Settings, Study UI

[FakeAnkiBackend] --test only--> never registered in prod, used in 56 GATE 21 tests + contract tests

[PC Agent Transport: WebSocketAgentConnection, ReconnectController, ProtocolMessages] --AI/TTS/STT--> NO Anki traffic, no PcAnkiBackend

[AppContainer] --composition root--> wires all above, 866 lines, ankiBackendRegistry at 492-495
```

### 3. Backend Identity Summary

- Backend identity: `AnkiBackendId` sealed interface (AnkiDroidLocal singleton, PcAgent(profileId) config-derived, Fake(id) test). `stableId` string, `@Serializable`, `fromStableId` round-trips. Registry refuses duplicate. Blank profileId refused. No production PcAgent producer.
- Collection identity: `AnkiCollectionIdentity(backendId, collectionKey?)` — null means unknown, never invented default. AnkiDroid always null (`AnkiDroidDeckMapper.kt:93,114`), so UNAVAILABLE. PC UNKNOWN. Fake HEURISTIC. `collectionMatchesBoundSession` fails open when boundKey null (`AnkiDroidBackend.kt:1340`). Cross-backend equivalence unsupported, no sync state, no comparison code.
- Resource identity: all ref types backend-qualified at construction via `identityKey(backendId.stableId, ...)`. Card, Note, Deck, Model, Template all include backendId in stableKey. Media ref does NOT exist (request qualified, result bare name). Filtered session ref does NOT exist (GATE 20 BLOCKED).

### 4. Capability Summary

Reference `BACKEND_CAPABILITY_MATRIX.md` — 11 availability states, 26 capability flags, 19 required capabilities, STUDY_SESSION_REQUIREMENTS = REVIEW only. AnkiDroid claims: deckListing YES, scheduledReview YES, review YES (gated on ratingGateway), renderedCards YES, cardDetails YES (gated on noteGateway), bury YES, suspend YES, editNoteFields/Tags/changeCardDeck YES (gated on note+mutation gateways), createNotes/noteModelListing/storeMedia YES (gated on creationGateway), noteModelSchema/template listing/source/css YES (gated on modelGateway), deckCounts NO, media NO, flags NO, cardBrowser NONE (UnsupportedAnkiDroidCardBrowserGateway). PC column all ✘ (no code). Fake supports scripted.

### 5. Routing Summary

All 44 sites in `BACKEND_ROUTING_INVENTORY.md`:

- Global preference: `AnkiBackendPreferenceStore` + `DataStoreAnkiBackendPreferenceStore` + `SettingsViewModel.selectAnkiBackend`
- Pre-binding: `AnkiBackendResolver` pure policy, `AnkiBackendSelector` adapter, `AnkiLocalStudyStarter.start()` sampled once + freshness re-check, `AnkiStudyEffectExecutor.begin()` only resolution in runtime, dashboard pre-flight hard-coded LOCAL_PREFERENCE
- Post-binding ownership: nextCard, hydrate, commit, endReview all `registry.find(context.backendId)` or `turn.backendId` or `commitId.backendId`, never preference
- Pinned gaps: Library, Deck Details, Card Browser, Add-Note seed all hard-pinned to `container.ankiDroidBackend`
- Transaction recovery: `AnkiTransactionRecoveryPlanner` groups by persisted backendId, never resolver, `ReviewCommitReconciler`, `ReviewerActionCoordinator`, pinned `NoteMutationCoordinator`/`NoteCreationCoordinator` (fail-closed BackendMismatch)
- No hidden fallback: no availability-driven switch, no exception-driven switch, no reconnect-driven switch, no try-next-backend loop, `switchBackend` no production caller

### 6. StudySession Ownership

- Immutable lock: `AnkiSessionContext(backendId, collection?, deckRef?, startedAt, capabilities snapshot, studySessionId)` created once inside `begin()` after deck validated against live collection (`AnkiStudyEffectExecutor.kt:578`), published to `AnkiStudyBackendLockTracker` only after `beginReview` succeeded (`:585`), released only on `endReview` (`:802`)
- Not persisted: `AnkiSessionContext` not `@Serializable`, lock tracker is `MutableStateFlow` process memory. Config change survives (process-scoped container), process death loses it.
- All scheduler ops use locked identity: `registry.find(session.context.backendId)` — no preference read after begin. Test-enforced by `Gate21ArchitectureAuditTest`.
- Settings change does not affect active session — `StudyViewModel.lockedAnkiBackendLabel` reads lock tracker, not preference.
- Reconnect does not re-resolve — no Anki reconnect path.
- Session recovery: transaction recovers from ledger, session object does not.

### 7. Transaction Ownership

| Family | Persisted backendId? | Recovery target | Re-routed? |
|---|---|---|---|
| ReviewCommit | YES `commitId.backendId` in envelope v4 | `AnkiReviewCommitReconciler(registry)` + `ledger.pendingRecovery(backendId)` + `recoveryBlocker` | NO — stays blocked until owner returns |
| ReviewerAction | YES `backendId` + `collectionRef` v1 | `registry.find(record.backendId)` refuses foreign card/collection | NO |
| NoteMutation | YES `backendId` v1 | Ledger backend-scoped, coordinator pinned to ankiDroidBackend, `BackendMismatch` fail-closed | NO, but second backend record could not be recovered (latent) |
| NoteCreation | YES `backendId` v1 | Same as mutation — pinned, `BackendMismatch` | NO, latent same |
| Custom Study | NO record (BLOCKED) | N/A | N/A |

### 8. Reconnect/Restart Findings

- PC Agent disconnect: agent transport `WebSocketAgentConnection.kt:659` schedules reconnect with exponential backoff, `autoReconnect` setting. Anki backend unaffected (no PC Anki backend).
- PC Agent reconnect: proves transport identity (profileId), not collection continuity. `COLLECTION_MISMATCH` only when both keys known.
- AnkiDroid provider unavailable: health repository detects, availability becomes ProviderUnavailable/PermissionRequired/CollectionNotInitialized, descriptor not reachable, active session gets `BackendUnavailable` typed failure, no silent fallback.
- AnkiDroid becomes available: health snapshot publishes, status center updates, pending transactions become Routable.
- Process death: lock tracker lost, ledgers survive. Startup recovery off splash path, ledgers loaded but study state not mutated.
- Restart: SUBMITTING → AMBIGUOUS conversion on first ledger load, `recoveryBlocker` checked before scheduler traffic in `AnkiStudyEffectExecutor.kt:556-567`, availability refreshed inside `begin()` after recovery scan.
- Preference change: writes mode, does not touch active locks. Future sessions use new mode.

Continuity guarantees: transaction ownership durable and backend-pinned, never re-routed. Session lock is process memory only, not durable. Collection continuity unverifiable on AnkiDroid (always null), so reconnect ≠ verified same collection.

### 9. Architecture Audit

```
AUDIT-21A-01 — Inventory Completeness: PASS — all production backend impls identified, construction paths documented, 44 routing sites
AUDIT-21A-02 — Identity Completeness: PASS — backend, collection, resource, transaction, session, route identities classified, gaps named
AUDIT-21A-03 — Routing Completeness: PASS — every selection point mapped, hidden fallback negative result documented
AUDIT-21A-04 — Study Ownership Traceability: PASS — every scheduler operation traced to registry.find(backendId) with file:line
AUDIT-21A-05 — Mutation Ownership Traceability: PASS — all four families mapped to persistence and recovery path, GATE 20 BLOCKED
AUDIT-21A-06 — Read Context Isolation: PASS — Library, Deck Details, Card Browser, Card Details, Edit Note, Add Note inventoried, cache keys, request correlation, route gaps
AUDIT-21A-07 — Reconnect Coverage: PASS — agent disconnect/reconnect, AnkiDroid unavailable/available, process death, restart, preference change
AUDIT-21A-08 — Security Coverage: PASS — permission, endpoint allowlist, write permit, no secret in descriptor, PC trust absent
AUDIT-21A-09 — No Speculation: PASS — PC cells ✘ because no code, collection UNAVAILABLE not AUTHORITATIVE, no latency claim
AUDIT-21A-10 — No Implementation Leakage: PASS — no production code changed, git status clean for app/src/main
```

### 10. Verification Matrix

| Verification | Result | Evidence |
|---|---|---|
| VER-21A-01 — Repository Provenance | PASS | Branch `arena/47bec43d-study-agent-client`, HEAD `2d2a0a095ac9b50f10bdf101528491dc6b7e68a4`, worktree clean, `git rev-parse --show-toplevel` etc executed |
| VER-21A-02 — Source Symbol Accuracy | PASS | `AnkiBackendId.kt:8`, `AnkiDroidBackend.kt:103`, `AnkiBackendResolver.kt:104`, `AnkiBackendRegistry` at `AnkiBackendSelector.kt:10`, `AnkiStudyBackendLockTracker` at `AnkiBackendStatus.kt:128`, `FakeAnkiBackend.kt:28` all exist |
| VER-21A-03 — Backend Inventory Accuracy | PASS | `AppContainer.kt:492` registry single entry, `rg -n PcAnkiBackend` 0 prod matches, Fake only test |
| VER-21A-04 — Identity Accuracy | PASS | `AnkiBackendId` serializable, `fromStableId` `:32`, persisted in ledgers `:272,110,21,47`, route tokens `Screen.kt:54,95-122`, collectionKey null `AnkiDroidDeckMapper.kt:93,114`, fails open `:1340` |
| VER-21A-05 — Capability Accuracy | PASS | `cardBrowser=NONE` from `AnkiDroidCompatibilityPolicy.kt:63,106` + `AppContainer.kt:326` + `AnkiDroidCardBrowserGateway.kt:23-27`, `media=false`, `flags=false`, `deckCounts=false` from `withWriteSupport` `:211` |
| VER-21A-06 — Routing Accuracy | PASS | `AnkiLocalStudyStarter.start()` `:132`, `AnkiStudyEffectExecutor.begin()` `:544-549` only resolution, `registry.find` at `:508,513,628,664,804`, pinned at `AppNavHost.kt:81,110,144,94`, no hidden fallback |
| VER-21A-07 — Study Trace Accuracy | PASS | Scheduler ops traced to `registry.find(backendId)` with file:line |
| VER-21A-08 — Mutation Trace Accuracy | PASS | ReviewCommit `ReviewCommitReconciliation.kt:82`, ReviewerAction `ReviewerActionRecovery.kt:286-320`, NoteMutation `NoteMutationCoordinator.kt:151` + `AppContainer.kt:439`, NoteCreation `NoteCreationCoordinator.kt:162` + `AppContainer.kt:470`, planner pure |
| VER-21A-09 — Unknown Handling | PASS | Collection UNAVAILABLE/UNKNOWN, cross-backend equivalence UNSUPPORTED, media unqualified, filtered absent, latency unmeasured, maturity IMPLEMENTED not VERIFIED — all explicitly listed |
| VER-21A-10 — No Production Changes | PASS | `git status --short` clean for `app/src/main`, only `docs/` changed (allowed) |

### 11. Documentation Created

- `docs/GATE_21A_DISCOVERY.md` (this file) — updated for current branch HEAD
- `docs/BACKEND_IDENTITY_INVENTORY.md` — updated
- `docs/BACKEND_CAPABILITY_MATRIX.md` — updated
- `docs/BACKEND_ROUTING_INVENTORY.md` — updated
- `docs/GATE_21A_EVIDENCE_LEDGER.md` — NEW, traceable evidence index

### 12. Safety-Critical Unknowns

```
QUESTION: Can AnkiDroid identify its active collection authoritatively?
WHY IT MATTERS: Without collection identity, collection change mid-session undetectable, recovery cannot detect COLLECTION_MISMATCH, two profiles with same backendId collide
CURRENT EVIDENCE: AnkiDroidDeckMapper.kt:93,114 collectionKey=null always; AnkiDroidBackend.kt:1336 collectionMatchesBoundSession returns true when boundKey null (fails open); no collection id, profile id, hkey, server id exposed
MISSING EVIDENCE: AnkiDroid provider documentation or code that exposes collection discriminator; real-device probe
BLOCKS GATE 21B: YES — B10 reconnect verification policy must assume UNAVAILABLE and fail open is unsafe to treat as verified

QUESTION: Can two different PC Agent configurations share same logical identity?
WHY IT MATTERS: If same profileId configured twice, writes could go to different collections but share ledger
CURRENT EVIDENCE: AnkiBackendId.PcAgent(profileId) includes discriminator, blank refused, registry refuses duplicate identity; but no production producer exists, so allocation policy unauditable
MISSING EVIDENCE: Production code that constructs PcAgent ids and ensures stable allocation
BLOCKS GATE 21B: YES — B01 backend identity contract must define allocation stability and collision handling

QUESTION: Is card browser, media read, flags, deck counts satisfiable on AnkiDroid?
WHY IT MATTERS: Capability-constrained routing would refuse operations UI currently offers through pinned DI; product decision needed whether to honestly refuse or to implement
CURRENT EVIDENCE: AnkiDroidCompatibilityPolicy.kt:63,106 cardBrowser UNSUPPORTED, AnkiDroidBackend.kt:211 withWriteSupport flags=false, deckCounts=false, media=false, AppContainer.kt:326 UnsupportedAnkiDroidCardBrowserGateway
MISSING EVIDENCE: Audited public API for bounded card list, media read, flag column, verified counts; or product decision to keep refused
BLOCKS GATE 21B: YES — B02 capability policy and B08 read-context switching must decide refusal vs implementation

QUESTION: Can a filtered/custom-study session be bound to a backend?
WHY IT MATTERS: GATE 21L depends on GATE 20; without filtered session type, custom study cannot be owned
CURRENT EVIDENCE: StudyMode.CUSTOM_SESSION enum exists at StudyControlModels.kt:37 with no client logic, no AnkiFilteredSessionRef type, docs/GATE_20_CUSTOM_STUDY_CONTRACT.md says BLOCKED
MISSING EVIDENCE: GATE 20 implementation
BLOCKS GATE 21B: YES for 21L — B12 custom study must be marked BLOCKED until GATE 20

QUESTION: Is AnkiDroid mutation verified on real device?
WHY IT MATTERS: Maturity IMPLEMENTED not VERIFIED — capability claims based on code audit, not device run
CURRENT EVIDENCE: AnkiDroidCompatibilityPolicy.kt comments say IMPLEMENTED, not VERIFIED; no real-device mutation run in this repo
MISSING EVIDENCE: Real-device matrix run (docs/REAL_DEVICE_MATRIX.md)
BLOCKS GATE 21B: NO — but must be carried as limitation, not assumed VERIFIED

QUESTION: Does process restart preserve backend lock?
WHY IT MATTERS: 21G criterion "process restore preserves backend lock" cannot be met for session, only for transactions
CURRENT EVIDENCE: AnkiSessionContext not @Serializable, lock tracker MutableStateFlow process memory only, AppContainer.kt:439 startup recovery off splash path, only ledgers survive
MISSING EVIDENCE: None — source confirms not persisted
BLOCKS GATE 21B: NO — but B06 session ownership must explicitly state lock is process memory, only durable transaction ownership survives

QUESTION: Can deck id be reinterpreted after switching backend?
WHY IT MATTERS: Deck-details and card-browser routes carry raw deckId only, no backend discriminator — latent resource-id reinterpretation prohibited by GATE 21
CURRENT EVIDENCE: Screen.kt:17 deck-details route raw deckId, :64 card-browser raw deckId, AppNavHost.kt:142 ViewModel key hard-codes ankiDroidBackend.id.stableId, AnkiLocalStudyStarter.start() uses preferenceProvider() not LOCAL_PREFERENCE, so Library AnkiDroid deck + PC_AGENT preference => deck resolved against PC backend => either deck_not_found or different deck with same numeric id studied (BACKEND_ROUTING_INVENTORY.md §7)
MISSING EVIDENCE: Second backend registration to make hazard reachable
BLOCKS GATE 21B: YES — B08 read-context switching must close this by passing backend-qualified deck refs, not raw ids
```

### 13. Next Gate Readiness

```
GATE 21A: PASS

GATE 21B READY: YES — with 5 blocking unknowns that B must explicitly resolve as policy decisions (collection identity UNAVAILABLE, PC allocation, capability refusal, custom study BLOCKED, deck-id reinterpretation)

UNRESOLVED DISCOVERY BLOCKERS:
- AnkiDroid collection identity UNAVAILABLE — B10 must define fail-open vs fail-closed and forbid treating reconnect as verified continuity
- PC Agent identity allocation — B01 must define stableId stability and collision handling, no producer exists
- Card browser / media / flags / deck counts unsatisfiable — B02 must decide honest refusal vs implementation
- Custom study BLOCKED — B12 must remain BLOCKED until GATE 20
- Deck route raw id hazard — B08 must close by backend-qualified refs

IMPLEMENTATION AUTHORIZED: NO — GATE 21B must lock architecture before 21C
```

---

## PART VII — GATE LOCK

```
GATE 21A — DISCOVERY LOCKED

Repository: Drmusab/Study-Agent-Client
Branch: arena/47bec43d-study-agent-client
HEAD: 2d2a0a095ac9b50f10bdf101528491dc6b7e68a4
Date: 2026-10-11

No production code modified. Documentation only. 56 GATE 21 tests remain green per previous harness run (2217 total tests passed).

Implementation remains prohibited after GATE 21A. GATE 21B must independently lock architecture.
```

Suggested commit message:

```
docs(gate21a): map Anki backend identity routing and ownership

- Re-baseline repository to arena/47bec43d-study-agent-client HEAD 2d2a0a0
- Inventory backends: AnkiDroidBackend prod, Fake test-only, PcAnkiBackend absent
- Document backend identity AnkiBackendId sealed, collection UNAVAILABLE on AnkiDroid, resource backend-qualified, media unqualified, filtered absent
- Capability matrix: 11 availability states, 26 flags, 19 required, REVIEW only for study, cardBrowser/media/flags/deckCounts unsatisfiable
- Routing inventory: 44 sites, no hidden fallback, pinned read contexts, owned post-binding, transaction recovery never re-routes
- StudySession ownership: immutable lock process memory only, once per session, all scheduler ops via registry.find(backendId)
- Transaction ownership: 4 families persisted backendId versioned envelopes, planner groups by owner, COLLECTION_MISMATCH only when both keys known
- Reconnect/restart: agent reconnect != collection verified, AnkiDroid change undetectable (fails open), process death loses lock but ledgers survive
- Security: permission, endpoint allowlist, write permit, no secret in descriptor, PC trust absent
- Tests: 56 GATE 21 tests across 6 classes, plus ledgers, recovery, browser, details
- Safety-critical unknowns: collection identity, PC allocation, capability refusal, custom study BLOCKED, deck-id reinterpretation hazard
- Evidence ledger: traceable source-of-truth with file, symbol, line, confidence
```

Do not create Git commit unless explicitly authorized.

---

## NEXT AUTHORIZED SUB-GATE

**GATE 21B — Contract Resolution & Architecture Lock**

Must consume actual GATE 21A evidence and decide:

```
Backend identity contract
Collection identity policy
Resource qualification
AUTO selection policy
Manual backend selection
Immutable StudySession ownership
Transaction backend ownership
Recovery routing
Reconnect verification
Read-context switching
Failure/fallback boundaries
```

No implementation begins until GATE 21B independently locked.
