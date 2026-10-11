# GATE 21A — Evidence Ledger

**Gate:** GATE 21A — Backend Architecture & Contract Discovery
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/47bec43d-study-agent-client`
**HEAD:** `2d2a0a095ac9b50f10bdf101528491dc6b7e68a4` (2d2a0a0)
**Date:** 2026-10-11
**Method:** source read of `app/src/main` + `app/src/test`, no device, no Anki instance, no PC agent run.

This file indexes every significant discovery claim with file, symbol, line range, supporting test if any, and classification. Confidence labels: SOURCE_CONFIRMED, TEST_CONFIRMED, DOCUMENTED_ONLY, UNKNOWN.

---

## 00 — Repository Baseline

| # | Claim | File | Symbol | Line Range | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 00-01 | Repo toplevel is Study-Agent-Client, branch arena/47bec43d, HEAD 2d2a0a0, worktree clean | `.git`, `git rev-parse` | — | — | `git branch --show-current` → arena/47bec43d, `git rev-parse HEAD` → 2d2a0a0, `git status --short` empty | SOURCE_CONFIRMED |
| 00-02 | Android modules: app only (server is mock PC agent python) | `settings.gradle.kts` | — | 1-20 | `include(":app")` only, `server/mock_pc_agent.py` not Anki | SOURCE_CONFIRMED |
| 00-03 | Kotlin root `com.studyagent.client`, core anki 45 files | `app/src/main/java/com/studyagent/client/core/anki/` | — | — | `ls` 45 files including AnkiBackend.kt, AnkiBackendId.kt, etc | SOURCE_CONFIRMED |
| 00-04 | DI root AppContainer 866 lines, registry single entry | `di/AppContainer.kt` | `ankiBackendRegistry` | 492-495 | `AnkiBackendRegistry(listOf(ankiDroidBackend))` | SOURCE_CONFIRMED |
| 00-05 | No PcAnkiBackend class | `app/src` | — | — | `rg -n "PcAnkiBackend" app/src` 0 prod matches | SOURCE_CONFIRMED |

---

## 01 — Backend Implementation Inventory

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 01-01 | AnkiBackend interface exists | `core/anki/AnkiBackend.kt` | `interface AnkiBackend` | 21 | 21 methods, id, availability, capabilities, commitSemantics | SOURCE_CONFIRMED |
| 01-02 | AnkiDroidBackend class exists, full impl | `data/anki/ankidroid/AnkiDroidBackend.kt` | `class AnkiDroidBackend` | 103-1433 | Implements AnkiBackend, 14 constructor deps (gateway, deck, review, card, note, model, creation, rating, reviewerAction, browser, etc) | SOURCE_CONFIRMED |
| 01-03 | AnkiDroidBackend construction owner AppContainer lazy | `di/AppContainer.kt` | `ankiDroidBackend` | 342-400 | `by lazy { AnkiDroidBackend(gateway=..., deckGateway=..., reviewGateway=..., cardGateway=..., noteGateway=..., noteMutationGateway=..., creationGateway=..., modelGateway=..., ratingGateway=..., reviewerActionGateway=..., cardBrowserGateway=...) }` | SOURCE_CONFIRMED |
| 01-04 | API dependency AnkiDroid public provider, authority via endpoints | `data/anki/ankidroid/AnkiDroidApiContract.kt` | `AnkiDroidEndpoints` | 790,814 | `forBuild(includeDebugEndpoints=BuildConfig.DEBUG)` — release cannot resolve debug authority | SOURCE_CONFIRMED |
| 01-05 | Permission via AnkiDroidPermissionManager | `data/anki/ankidroid/AnkiDroidPermissionManager.kt` | `AnkiDroidPermissionManager` | 43 | Interface `state(endpoint): AnkiDroidPermissionState` | SOURCE_CONFIRMED |
| 01-06 | Write serialized by AnkiDroidWritePermit | `data/anki/ankidroid/AnkiDroidWritePermit.kt` | `AnkiDroidWritePermit` | — | Single permit shared by rating, reviewer action, note mutation, creation | SOURCE_CONFIRMED |
| 01-07 | PC Anki backend does NOT exist | `app/src/main` | — | — | `rg -n "PcAnkiBackend" app/src` 0, `rg -n "PcAgent(" app/src/main` only declaration + when branches + UI label | SOURCE_CONFIRMED |
| 01-08 | PC agent transport carries NO Anki traffic | `core/models/ProtocolMessages.kt` | — | — | `grep -rin "anki" core/models/ProtocolMessages.kt` empty | SOURCE_CONFIRMED |
| 01-09 | FakeAnkiBackend test-only | `app/src/test/java/com/studyagent/client/anki/fake/FakeAnkiBackend.kt` | `class FakeAnkiBackend` | 28 | Implements AnkiBackend, in-memory, scripted CommitStep, never registered in prod | SOURCE_CONFIRMED |
| 01-10 | AnkiBackendRegistry immutable, duplicate check | `core/anki/AnkiBackendSelector.kt` | `AnkiBackendRegistry` | 10-20 | `byId = backends.associateBy { it.id }`, `require(byId.size == backends.size) { "Duplicate logical backend identity" }` | SOURCE_CONFIRMED |
| 01-11 | AnkiBackendResolver pure function | `core/anki/AnkiBackendResolver.kt` | `AnkiBackendResolver` | 104-220 | `resolve(request, descriptors)` pure, mode filter → reachable → capable → LOCAL before REMOTE → single remote or Ambiguous | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 01-12 | AnkiBackendSelector session-start adapter | `core/anki/AnkiBackendSelector.kt` | `AnkiBackendSelector` | 40-106 | `REVIEW_REQUIREMENT = STUDY_SESSION_REQUIREMENTS`, `resolve(preference)` calls resolver | SOURCE_CONFIRMED |
| 01-13 | AnkiBackendDescriptor snapshot | `core/anki/AnkiBackendDescriptor.kt` | `AnkiBackendDescriptor` | 55-106 | id, availability, capabilities, kind, locality, `isReadyForReview`, `isReachable`, `supports`, `orderingKey`, `of(backend)` | SOURCE_CONFIRMED |
| 01-14 | AnkiBackendStatusCenter observation | `core/anki/AnkiBackendStatus.kt` | `AnkiBackendStatusCenter` | 67-120 | Combines availability x capabilities per backend, preference, `resolveFor`, `snapshotNow` | SOURCE_CONFIRMED |
| 01-15 | AnkiStudyBackendLockTracker process memory | `core/anki/AnkiBackendStatus.kt` | `AnkiStudyBackendLockTracker` | 128-160 | `MutableStateFlow<Map<String, AnkiSessionContext>>`, `locked()`, `released()`, `lockedBackendFor()` | SOURCE_CONFIRMED |
| 01-16 | AnkiTransactionRecoveryPlanner pure, never resolver | `core/anki/AnkiTransactionRecoveryRouting.kt` | `AnkiTransactionRecoveryPlanner` | 44-105 | `plan(ownerBackendIds, collectionKeys, activeCollectionKeys)` groups by persisted id, returns Routed/Blocked, no resolver/preference | SOURCE_CONFIRMED + TEST_CONFIRMED |

---

## 02 — Backend Identity

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 02-01 | AnkiBackendId sealed interface exists | `core/anki/AnkiBackendId.kt` | `AnkiBackendId` | 8-31 | `AnkiDroidLocal` stableId ankidroid_local, `PcAgent(profileId)` pc_agent:<profileId> require not blank, `Fake(id)` fake:<id> | SOURCE_CONFIRMED |
| 02-02 | Serialization @Serializable + fromStableId | `core/anki/AnkiBackendId.kt` | `fromStableId` | 32-42 | Round-trips all three, null for unknown | SOURCE_CONFIRMED |
| 02-03 | BackendId persisted in every ledger | `core/anki/ReviewCommit.kt`, `ReviewerActionRecord.kt`, `edit/NoteMutationRecord.kt`, `create/NoteCreationRecord.kt` | `backendId` | 272,110,21,47 | All records have backendId field, envelope versioned JSON | SOURCE_CONFIRMED |
| 02-04 | BackendId persisted in navigation tokens | `ui/navigation/Screen.kt` | `createRoute`, `decodeBackendId`, `CardDetails` token | 54,56,95-122 | add-note/{backendId} stableId, card-details token backendId.stableId + collectionKey + cardId + noteId + ord base64 | SOURCE_CONFIRMED |
| 02-05 | Registry refuses duplicate identity | `core/anki/AnkiBackendSelector.kt` | `AnkiBackendRegistry` init | 12 | `require(byId.size == backends.size)` | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 02-06 | PcAgent blank refused at construction | `core/anki/AnkiBackendId.kt` | `PcAgent` init | 20 | `require(profileId.isNotBlank())` | SOURCE_CONFIRMED |
| 02-07 | No production PcAgent producer | `app/src/main` | — | — | `rg -n "PcAgent(" app/src/main` 0 producers, only declaration + when branches + UI label | SOURCE_CONFIRMED |
| 02-08 | Fake never accepted by production modes | `core/anki/AnkiBackendId.kt` | `AnkiBackendMode.accepts` | 49-51 | AUTO accepts AnkiDroidLocal or PcAgent, never Fake | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 02-09 | Backend identity is instance, not kind | `core/anki/AnkiBackendId.kt` | comment | 6 | "Logical identity, not object identity. PC profiles must never share a write ledger." | SOURCE_CONFIRMED |
| 02-10 | Kind/locality derived, not identity | `core/anki/AnkiBackendDescriptor.kt` | `AnkiBackendKind`, `AnkiBackendLocality`, `id.kind` | 11,28,34 | `kind` metadata, `locality` policy input for AUTO ordering, descriptor init refuses contradicting kind/locality | SOURCE_CONFIRMED |
| 02-11 | Session lock not persisted, process memory only | `core/anki/AnkiBackendStatus.kt`, `AnkiSessionContext.kt` | `AnkiStudyBackendLockTracker`, `AnkiSessionContext` | 128-160,10 | `MutableStateFlow`, `AnkiSessionContext` plain data class not @Serializable | SOURCE_CONFIRMED |
| 02-12 | Preference is mode, not identity | `core/anki/AnkiBackendPreference.kt` | `AnkiBackendPreferenceStore` | 17 | `mode: StateFlow<AnkiBackendMode>`, comment "PreferredBackend is NOT the active backend" | SOURCE_CONFIRMED |

---

## 03 — Collection Identity

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 03-01 | AnkiCollectionIdentity exists, collectionKey nullable, null means unknown | `core/anki/AnkiRefs.kt` | `AnkiCollectionIdentity` | 7-11 | `data class AnkiCollectionIdentity(val backendId, val collectionKey: String?)`, doc "Null means unknown, never an invented default." | SOURCE_CONFIRMED |
| 03-02 | AnkiDroid deck mapper always null collectionKey | `data/anki/ankidroid/AnkiDroidDeckMapper.kt` | `mapDecks` | 93,114 | `AnkiDeckRef(backendId, deckId, collectionKey=null)` | SOURCE_CONFIRMED |
| 03-03 | Card mapper inherits request collectionKey which came from decks (null) | `data/anki/ankidroid/AnkiDroidCardMapper.kt` | `mapCard` | 103,136,176 | Inherits whatever request carried, request keys from decks | SOURCE_CONFIRMED |
| 03-04 | collectionMatchesBoundSession fails open when boundKey null | `data/anki/ankidroid/AnkiDroidBackend.kt` | `collectionMatchesBoundSession` | 1336-1340 | `val boundKey = record.session.context.collection?.collectionKey ?: return true` | SOURCE_CONFIRMED |
| 03-05 | Cross-backend collection equivalence unsupported | `core/anki`, `data/anki` | — | — | `grep -rin "sync" core/anki data/anki` no collection identity, no comparison code | SOURCE_CONFIRMED |
| 03-06 | Recovery planner COLLECTION_MISMATCH only when both known | `core/anki/AnkiTransactionRecoveryRouting.kt` | `route` | 71 | `if (recordCollectionKey != null && active != null && recordCollectionKey != active) -> COLLECTION_MISMATCH` | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 03-07 | AnkiSessionContext only refuses structurally foreign collection, not collection check | `core/anki/AnkiSessionContext.kt` | init | 26 | `require(collection == null || collection.backendId == backendId)` — type check, not collection check | SOURCE_CONFIRMED |
| 03-08 | Collection identity classification AnkiDroid UNAVAILABLE, PC UNKNOWN | `docs/BACKEND_IDENTITY_INVENTORY.md` | — | — | Based on 03-02 to 03-04, no PC impl | SOURCE_CONFIRMED |

---

## 04 — Resource Identity

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 04-01 | AnkiDeckRef backend-qualified at construction, stableKey includes backendId | `core/anki/AnkiRefs.kt` | `AnkiDeckRef` | 13-25 | `backendId`, `deckId`, `collectionKey?`, `stableKey = identityKey(backendId.stableId, collectionKey, "deck", deckId)` | SOURCE_CONFIRMED |
| 04-02 | AnkiNoteRef backend-qualified | `core/anki/AnkiRefs.kt` | `AnkiNoteRef` | 27-41 | `backendId`, `noteId`, `collectionKey?`, `stableKey = identityKey(...)` | SOURCE_CONFIRMED |
| 04-03 | AnkiCardRef backend-qualified, rules cardId or noteId+ord, ord requires noteId | `core/anki/AnkiRefs.kt` | `AnkiCardRef` | 49-68 | `require(cardOrd==null||noteId!=null)`, `require(cardId!=null||noteId!=null&&cardOrd!=null)`, `stableKey = identityKey(backendId.stableId, collectionKey, "card", cardId, noteId, ord)` | SOURCE_CONFIRMED + TEST_CONFIRMED (AnkiArchitectureContractTest) |
| 04-04 | AnkiNoteModelRef backend-qualified | `core/anki/AnkiNoteModels.kt` | `AnkiNoteModelRef` | 25-40 | `backendId`, `modelId`, `collectionKey?`, key `"anki:<backend>:<collection|?>:model:<modelId>"` | SOURCE_CONFIRMED |
| 04-05 | AnkiCardTemplateRef nested modelRef + collectionKey | `core/anki/AnkiTemplateModels.kt` | `AnkiCardTemplateRef` | 18-35 | Nested `modelRef`, cross-consistency require | SOURCE_CONFIRMED |
| 04-06 | identityKey length-prefix prevents collision | `core/anki/AnkiBackendId.kt` | `identityKey` | 61-63 | `parts.joinToString("") { if (it==null) "-1:" else "${it.length}:$it" }` | SOURCE_CONFIRMED |
| 04-07 | AnkiMediaRef does NOT exist | `app/src/main` | — | — | `rg -n "AnkiMediaRef" app/src/main` only `AnkiMediaResolver` interface, not ref type | SOURCE_CONFIRMED |
| 04-08 | Media creation request qualified, result bare name | `core/anki/create/NoteCreationBackendResults.kt` | `StoreMediaBackendRequest`, `MediaStoreBackendResult.Stored` | 68-74,93 | Request has `backendId`, result `Stored(mediaName)` bare, no backend qualification | SOURCE_CONFIRMED |
| 04-09 | AnkiFilteredSessionRef does NOT exist, GATE 20 BLOCKED | `core/models/StudyControlModels.kt`, `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` | `StudyMode.CUSTOM_SESSION` | 37 | Enum constant with no client logic, GATE 20 doc says BLOCKED | SOURCE_CONFIRMED |
| 04-10 | Numeric-id collisions different refs by backendId in stableKey | `core/anki/AnkiRefs.kt` | `stableKey` | 19,33,60 | backendId.stableId first component of stableKey | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 04-11 | Route tokens: card-details carries backendId, deck-details raw deckId only | `ui/navigation/Screen.kt` | `Screen` | 17,29,42,51,64,95-122 | card-details token backendId.stableId + collectionKey + cardId + noteId + ord base64, add-note backendId.stableId, deck-details/card-browser raw deckId | SOURCE_CONFIRMED |
| 04-12 | Structural equality includes all fields, unknown not wildcard | `core/anki/AnkiRefs.kt` | comment | 56 | "Unknown collection is not a wildcard." | SOURCE_CONFIRMED |

---

## 05 — Capability Architecture

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 05-01 | AnkiCapabilities 26 flags, defaults false/NONE | `core/anki/AnkiCapabilities.kt` | `AnkiCapabilities` | 118-228 | Flat, small, NONE constant at 223, safe default false | SOURCE_CONFIRMED |
| 05-02 | AnkiCardBrowserCapabilities structured, NONE default | `core/anki/AnkiCapabilities.kt` | `AnkiCardBrowserCapabilities` | 24-76 | canBrowseAllCards, canBrowseDeck, canIncludeChildDecks, canSearchText, supportedFilters, supportedSorts, supportsTotalCount, maxPageSize, NONE | SOURCE_CONFIRMED |
| 05-03 | AnkiRequiredCapability 19 entries, single mapping table | `core/anki/AnkiBackendCapabilityRequirement.kt` | `AnkiRequiredCapability`, `supports` | 15-93 | 19 requirements, `AnkiCapabilities.supports(required)` single function so cannot drift | SOURCE_CONFIRMED |
| 05-04 | STUDY_SESSION_REQUIREMENTS = REVIEW only, DECKS excluded | `core/anki/AnkiBackendCapabilityRequirement.kt` | `STUDY_SESSION_REQUIREMENTS` | 133 | `setOf(REVIEW)`, comment DECKS deliberately not here, locked session reads by CardRef | SOURCE_CONFIRMED |
| 05-05 | AnkiDroid withWriteSupport drops capability if writer not wired | `data/anki/ankidroid/AnkiDroidBackend.kt` | `withWriteSupport` | 211-230 | review && ratingGateway!=null, bury && reviewerActionCommitter!=null, etc, flags=false always, deckCounts=false, media=false, cardBrowser stays NONE | SOURCE_CONFIRMED |
| 05-06 | Card browser NONE on AnkiDroid — no public bounded list URI | `data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt`, `AnkiDroidBackend.kt`, `AppContainer.kt` | `cardBrowser`, `UnsupportedAnkiDroidCardBrowserGateway` | 63,106,326,23-27 | Compatibility policy UNSUPPORTED, backend never sets cardBrowser, AppContainer wires Unsupported gateway whose browseCards returns UnsupportedQueryFeature | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 05-07 | Only non-NONE browser capability in repo is test fixture | `app/src/test` | — | — | `rg -n "cardBrowser = AnkiCardBrowserCapabilities(" app/src` one match in Gate21BackendResolutionTest.kt:139 | SOURCE_CONFIRMED |
| 05-08 | Deck listing implemented | `data/anki/ankidroid/AnkiDroidDeckGateway.kt` | `AnkiDroidDeckGateway` | — | queryDecks | SOURCE_CONFIRMED |
| 05-09 | Scheduled review + rating implemented | `data/anki/ankidroid/AnkiDroidReviewGateway.kt`, `AnkiDroidRatingGateway.kt`, `AnkiDroidRatingCommitter.kt` | — | — | queryNextScheduledCard, answerCard | SOURCE_CONFIRMED |
| 05-10 | Card hydration implemented | `data/anki/ankidroid/AnkiDroidCardGateway.kt` | `AnkiDroidCardGateway` | — | queryCard | SOURCE_CONFIRMED |
| 05-11 | Card details implemented | `data/anki/ankidroid/AnkiDroidCardDetailsMapper.kt`, `AnkiDroidNoteGateway.kt` | — | — | getCardDetails coordinates cardGateway + noteGateway + deck listing for name | SOURCE_CONFIRMED |
| 05-12 | Reviewer actions bury/suspend implemented, flag not | `data/anki/ankidroid/AnkiDroidReviewerActionGateway.kt` | — | — | bury/suspend via provider update, flag no column | SOURCE_CONFIRMED |
| 05-13 | Note edit implemented | `data/anki/ankidroid/AnkiDroidNoteMutationGateway.kt` | `AnkiDroidNoteMutationGateway` | — | fields/tags/deck move | SOURCE_CONFIRMED |
| 05-14 | Note creation + media store implemented | `data/anki/ankidroid/AnkiDroidCreationGateway.kt` | `AnkiDroidCreationGateway` | — | listModels, submitMediaStore, submitNoteCreate, readNoteRow, readNoteCards | SOURCE_CONFIRMED |
| 05-15 | Media read NOT implemented | `core/anki/AnkiCapabilities.kt`, `data/repository/DiagnosticsRepository.kt` | `media` | 737 | media=false, diagnostics renders "Pending GATE 09" | SOURCE_CONFIRMED |
| 05-16 | Models/templates/css implemented GATE 19 | `data/anki/ankidroid/AnkiDroidModelGateway.kt` | `AnkiDroidModelGateway` | — | queryEnrichedModel | SOURCE_CONFIRMED |
| 05-17 | Custom study NOT implemented GATE 20 BLOCKED | `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md`, `core/models/StudyControlModels.kt` | `StudyMode.CUSTOM_SESSION` | 37 | Wire enum no client logic | SOURCE_CONFIRMED |
| 05-18 | PC backend NO capabilities (no code) | `app/src/main` | — | — | `rg -n "PcAnkiBackend" app/src` 0 | SOURCE_CONFIRMED |

---

## 06 — Availability & Readiness

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 06-01 | AnkiAvailability 11 states | `core/anki/AnkiAvailability.kt` | `AnkiAvailability` | 24-99 | Checking, NotInstalled, ProviderUnavailable, PermissionRequired, CollectionNotInitialized, Ready(capabilities), TemporarilyUnavailable, AgentDisconnected, AgentAnkiUnavailable, Unsupported, Fault | SOURCE_CONFIRMED |
| 06-02 | isReadyForReview positive knowledge Ready && review true | `core/anki/AnkiAvailability.kt` | `isReadyForReview` | 77 | `(this as? Ready)?.capabilities?.review == true` | SOURCE_CONFIRMED |
| 06-03 | statusCode stable token | `core/anki/AnkiAvailability.kt` | `statusCode` | 86-99 | CHECKING, NOT_INSTALLED, PROVIDER_UNAVAILABLE, etc | SOURCE_CONFIRMED |
| 06-04 | isReachable = Ready (weaker than review-ready) | `core/anki/AnkiBackendDescriptor.kt` | `isReachable` | 75 | `availability is Ready` | SOURCE_CONFIRMED |
| 06-05 | Availability StateFlow mutable at runtime | `core/anki/AnkiBackend.kt`, `data/anki/ankidroid/AnkiDroidHealthRepository.kt`, `test/.../FakeAnkiBackend.kt` | `availability`, `refreshAvailability`, `setAvailability` | 22,90,194 | StateFlow per backend, health repo owns probing, Fake setAvailability | SOURCE_CONFIRMED |
| 06-06 | Health repository refreshes on foreground + requestRefresh, stale protection | `data/anki/ankidroid/AnkiDroidHealthRepository.kt` | `onAppForeground`, `requestRefresh`, `publicationGuard` | 142,158,48 | Publishes only if still current | SOURCE_CONFIRMED |
| 06-07 | Two capability projections can disagree, supports requires both | `core/anki/AnkiBackendDescriptor.kt` | `supports`, `availabilityCapabilities` | 95,84-87 | `capabilities.supports(required) && availabilityCapabilities.supports(required)` — fail-closed | SOURCE_CONFIRMED + TEST_CONFIRMED (selector test "disagreeing capability projections fail closed") |
| 06-08 | PC availability states exist but no producer | `core/anki/AnkiAvailability.kt` | `AgentDisconnected`, `AgentAnkiUnavailable` | 68-73 | States defined, nothing in main emits them | SOURCE_CONFIRMED |
| 06-09 | PC AI evaluation available vs PC Anki backend available distinct | `core/models/ConnectionState.kt`, `core/network/WebSocketAgentConnection.kt`, `core/anki/AnkiAvailability.kt` | `ConnectionState`, `AnkiAvailability` | — | ConnectionState for AI transport, AnkiAvailability for Anki backend, no Anki traffic in ProtocolMessages | SOURCE_CONFIRMED |
| 06-10 | No measurement of transition frequency | `app/src` | — | — | No latency comparison, no transition counter | SOURCE_CONFIRMED (negative) |

---

## 07 — Backend Selection & Routing

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 07-01 | Preference store interface | `core/anki/AnkiBackendPreference.kt` | `AnkiBackendPreferenceStore` | 17 | mode StateFlow, lastWriteError, setMode | SOURCE_CONFIRMED |
| 07-02 | DataStore impl persists anki_backend_mode key | `data/preferences/DataStoreAnkiBackendPreferenceStore.kt`, `AppSettingsPreferencesCodec.kt` | `anki_backend_mode` | 90,117 | stringPreferencesKey, optimistic mirror, failed write surfaces backend_preference_not_saved | SOURCE_CONFIRMED |
| 07-03 | Settings selection writes preference | `ui/screens/settings/SettingsViewModel.kt` | `selectAnkiBackend` | 82 | Writes mode, KDoc says what switch does NOT touch | SOURCE_CONFIRMED |
| 07-04 | Resolver pure function 5 steps | `core/anki/AnkiBackendResolver.kt` | `resolve` | 104-160 | mode filter → reachable → capable → LOCAL before REMOTE → single remote or Ambiguous, orderingKey deterministic | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 07-05 | Selector thin adapter over resolver | `core/anki/AnkiBackendSelector.kt` | `AnkiBackendSelector` | 40 | `REVIEW_REQUIREMENT = STUDY_SESSION_REQUIREMENTS`, toResolution folds result | SOURCE_CONFIRMED |
| 07-06 | AnkiLocalStudyStarter start samples preference once | `data/repository/AnkiLocalStudyStarter.kt` | `start` | 132-135 | `val preference = options.preference ?: preferenceProvider()` sampled once at :134, then `selector.resolve(preference)` at :135 | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 07-07 | Freshness re-check re-resolves after refresh | `data/repository/AnkiLocalStudyStarter.kt` | `start` | 143-150 | `backend.refreshAvailability()` then re-resolve, changed answer refuses REASON_BACKEND_NOT_READY | SOURCE_CONFIRMED |
| 07-08 | Capability freeze reviewerActionCapabilities | `data/repository/AnkiLocalStudyStarter.kt` | `start` | 159 | `backend.reviewerActionCapabilities()` taken from resolved backend, frozen into request | SOURCE_CONFIRMED |
| 07-09 | EffectExecutor begin only resolution in runtime | `core/study/AnkiStudyEffectExecutor.kt` | `begin` | 544-549 | Probe pass over mode-accepted ids first :544, only one selector.resolve in file (pinned by Gate21ArchitectureAuditTest) | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 07-10 | Deck validation against live collection | `core/study/AnkiStudyEffectExecutor.kt` | `begin` | 571-576 | `request.deck.backendId != id => InvalidRequest("foreign_deck")`, getDecks must contain exact ref | SOURCE_CONFIRMED |
| 07-11 | Session context creation after validation | `core/study/AnkiStudyEffectExecutor.kt` | `begin` | 578 | `AnkiSessionContext(id, collection, deck, startedAt, capability snapshot, studySessionId)` | SOURCE_CONFIRMED |
| 07-12 | Lock publication only after Success | `core/study/AnkiStudyEffectExecutor.kt` | `begin` | 585 | `if (result is Success) sessionLocks?.locked(context)` | SOURCE_CONFIRMED |
| 07-13 | Dashboard pre-flight hard-coded LOCAL_PREFERENCE | `data/repository/AnkiLocalStudyStarter.kt` | `localStudyBackendIdFlow`, `resolveReadyBackendId` | 208-220 | Uses LOCAL_PREFERENCE ANKIDROID_LOCAL, not user preference | SOURCE_CONFIRMED |
| 07-14 | Post-binding nextCard uses registry.find(context.backendId) | `core/study/AnkiStudyEffectExecutor.kt` | `nextCard` | 508 | `registry.find(effect.session.context.backendId)?.nextCard` | SOURCE_CONFIRMED |
| 07-15 | Post-binding hydrate uses registry.find(turn.backendId) | `core/study/AnkiStudyEffectExecutor.kt` | `hydrateCardContent` | 513 | `registry.find(effect.turn.backendId)?.hydrateCardContent` | SOURCE_CONFIRMED |
| 07-16 | Commit dispatch uses recovery planner + fallback registry.find | `core/study/AnkiStudyEffectExecutor.kt` | `commitTransaction` | 664-666 | `transactionRecovery.plan(listOf(commitId.backendId))`, fallback `registry.find(commitId.backendId)` | SOURCE_CONFIRMED |
| 07-17 | Commit semantics freeze uses registry.find | `core/study/AnkiStudyEffectExecutor.kt` | `commitTransaction` | 628 | `registry.find(commitId.backendId)` | SOURCE_CONFIRMED |
| 07-18 | endReview uses registry.find(context.backendId) | `core/study/AnkiStudyEffectExecutor.kt` | `endReview` | 804 | `registry.find(effect.session.context.backendId)?.endReview` | SOURCE_CONFIRMED |
| 07-19 | Card details route OWNED via cardRef.backendId | `ui/navigation/AppNavHost.kt` | `CardDetails` | 167-176 | `container.ankiBackendRegistry.find(cardRef.backendId)` unregistered => error screen | SOURCE_CONFIRMED |
| 07-20 | Edit note OWNED | `ui/navigation/AppNavHost.kt` | `EditNote` | 248-258 | `find(editCardRef.backendId)` | SOURCE_CONFIRMED |
| 07-21 | Add note OWNED for lookup but seed PINNED | `ui/navigation/AppNavHost.kt` | `AddNote` | 94,204-222 | Route seed `createRoute(container.ankiDroidBackend.id)` hard-coded, lookup `find(decodeBackendId)` | SOURCE_CONFIRMED |
| 07-22 | Library PINNED | `ui/navigation/AppNavHost.kt`, `di/AppContainer.kt` | `LibraryViewModel` | 81,492 | `LibraryViewModel(backend=container.ankiDroidBackend)`, `AnkiLibraryRepository(backend=ankiDroidBackend)` | SOURCE_CONFIRMED |
| 07-23 | Deck Details PINNED | `ui/navigation/AppNavHost.kt` | `DeckDetailsViewModel` | 110 | `DeckDetailsViewModel(backend=container.ankiDroidBackend)` | SOURCE_CONFIRMED |
| 07-24 | Card Browser PINNED, switchBackend no prod caller | `ui/navigation/AppNavHost.kt`, `ui/screens/cardbrowser/CardBrowserViewModel.kt` | `CardBrowserViewModel` | 144,163 | `initialBackend=container.ankiDroidBackend`, `switchBackend` method exists but `rg -rn "switchBackend" app/src` only declarations + tests | SOURCE_CONFIRMED |
| 07-25 | No hidden fallback — availability-driven switch NONE | `core/study/AnkiStudyEffectExecutor.kt` | — | — | Lost backend => `NextCardResult.BackendUnavailable` same identity, no re-resolve | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 07-26 | Exception-driven fallback NONE | `core/study/AnkiStudyEffectExecutor.kt` | catch | 520-530 | Converts throw into typed `QueryFailure("unexpected")` same effect, never re-resolves | SOURCE_CONFIRMED |
| 07-27 | Reconnect-driven switch NONE — no Anki reconnect path | `app/src/main` | `reconnect` | — | Every `reconnect` symbol belongs to PC agent transport `core/network/ReconnectController.kt`, `WebSocketAgentConnection.kt`, `AppSettings.autoReconnect` | SOURCE_CONFIRMED |
| 07-28 | Try-next-backend loop NONE | `core/anki/AnkiBackendResolver.kt` | `resolve` | 104-160 | Returns first eligible by orderingKey or Ambiguous, never iterates to find one that answers | SOURCE_CONFIRMED |
| 07-29 | Cache reuse guarded | `data/anki/AnkiLibraryRepository.kt` | `invalidateIfBackendChanged` | 193,210 | Reuses only identical backendId, refresh response whose backendId != expected clears cache | SOURCE_CONFIRMED |
| 07-30 | Cross-context hazard pinned read feeding unpinned start | `ui/screens/home/HomeViewModel.kt`, `ui/screens/library/DeckDetailsViewModel.kt`, `data/repository/AnkiLocalStudyStarter.kt` | `start` | 214,144,132 | Library AnkiDroid deck + PC_AGENT preference => deck resolved against PC backend => deck_not_found or different deck same numeric id | SOURCE_CONFIRMED |

---

## 08 — StudySession Backend Ownership

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 08-01 | AnkiSessionContext data class backendId, collection?, deckRef?, capabilities snapshot, studySessionId | `core/anki/AnkiSessionContext.kt` | `AnkiSessionContext` | 10-28 | Not @Serializable, holds no backend object, init requires collection.backendId==backendId, collectionKey consistency | SOURCE_CONFIRMED |
| 08-02 | Created once per session inside effect executor after deck validation | `core/study/AnkiStudyEffectExecutor.kt` | `begin` | 578 | After deck validation :571-576, context creation :578 | SOURCE_CONFIRMED |
| 08-03 | Published to lock tracker only after beginReview Success | `core/study/AnkiStudyEffectExecutor.kt` | `begin` | 585 | `if (result is Success) sessionLocks?.locked(context)` | SOURCE_CONFIRMED |
| 08-04 | Released only on endReview | `core/study/AnkiStudyEffectExecutor.kt` | `endReview` | 802 | `sessionLocks?.released(studySessionId)` | SOURCE_CONFIRMED |
| 08-05 | Lock tracker process memory only | `core/anki/AnkiBackendStatus.kt` | `AnkiStudyBackendLockTracker` | 128-160 | MutableStateFlow<Map<String, AnkiSessionContext>> | SOURCE_CONFIRMED |
| 08-06 | Backend chosen once, not repeatedly | `core/study/AnkiStudyEffectExecutor.kt` | `begin`, `nextCard`, etc | 544-549,508,513,628,804 | Only one selector.resolve in file, all later ops registry.find(backendId) | SOURCE_CONFIRMED + TEST_CONFIRMED (Gate21ArchitectureAuditTest asserts absence of preferenceStore and resolver except begin) |
| 08-07 | Collection context retained but always null AnkiDroid | `core/anki/AnkiSessionContext.kt`, `data/anki/ankidroid/AnkiDroidDeckMapper.kt` | `collection` | 10,93 | collection field nullable, deck mapper null | SOURCE_CONFIRMED |
| 08-08 | Settings change does NOT affect active session | `ui/screens/study/StudyViewModel.kt` | `lockedAnkiBackendLabel` | 88 | Reads lock tracker, never preference | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 08-09 | Reconnect does NOT re-resolve backend | `app/src/main` | — | — | No Anki-backend reconnect path | SOURCE_CONFIRMED |
| 08-10 | No silent fallback | `core/study/AnkiStudyEffectExecutor.kt` | — | — | Typed failure on same identity | SOURCE_CONFIRMED |

---

## 09 — Durable Transaction Ownership

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 09-01 | ReviewCommit IMPLEMENTED, backendId derived from commitId.backendId persisted | `core/anki/ReviewCommit.kt`, `ReviewCommitLedger.kt` | `ReviewCommitRecord`, `ReviewCommitId` | 175,272,67,78 | Envelope schemaVersion 4, records, tombstones, migration normalizeLegacyVocabulary :157, migrateLegacy :120-123, duplicate commit ids => Unreadable, two unresolved per session => Unreadable :126-131 | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 09-02 | ReviewerAction IMPLEMENTED, backendId + collectionRef persisted | `core/anki/ReviewerActionRecord.kt`, `ReviewerActionLedger.kt` | `ReviewerActionRecord` | 110,43,97,104 | Envelope SCHEMA_VERSION 1, exact match required, mismatch Unreadable | SOURCE_CONFIRMED |
| 09-03 | NoteMutation IMPLEMENTED, backendId persisted, coordinator pinned | `core/anki/edit/NoteMutationRecord.kt`, `NoteMutationLedger.kt`, `NoteMutationCoordinator.kt`, `di/AppContainer.kt` | `NoteMutationRecord`, `NoteMutationCoordinator` | 21,67,151,439 | Snapshot version 1, coordinator `private val backend: AnkiBackend` wired to ankiDroidBackend, BackendMismatch fail-closed | SOURCE_CONFIRMED |
| 09-04 | NoteCreation IMPLEMENTED, backendId persisted, coordinator pinned | `core/anki/create/NoteCreationRecord.kt`, `NoteCreationLedger.kt`, `NoteCreationCoordinator.kt`, `di/AppContainer.kt` | `NoteCreationRecord`, `NoteCreationCoordinator` | 47,69,162,470 | Snapshot version 1, same shape as mutation, pinned, BackendMismatch | SOURCE_CONFIRMED |
| 09-05 | Custom Study BLOCKED no record | `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md`, `core/models/StudyControlModels.kt` | `StudyMode.CUSTOM_SESSION` | 37 | Enum with no client logic, no filtered-session type | SOURCE_CONFIRMED |
| 09-06 | BackendId persisted before mutation, durable boundary mutationEntry callback | `core/anki/AnkiBackend.kt` | `commitRating(request, mutationEntry)` | 131-145 | mutationEntry MUST be called once after preflight immediately BEFORE mutation, returns false => no mutation, certifies no mutation if returns before callback | SOURCE_CONFIRMED |
| 09-07 | Restart reads persisted backend identity, recoveryBlocker before scheduler | `core/study/AnkiStudyEffectExecutor.kt`, `di/AppContainer.kt` | `recoveryBlocker` | 556-567,439 | Startup recovery off splash path, ledgers loaded but study state not mutated, blocker checked before scheduler traffic | SOURCE_CONFIRMED |
| 09-08 | Recovery never uses global preference, never AUTO resolver | `core/anki/AnkiTransactionRecoveryRouting.kt` | `AnkiTransactionRecoveryPlanner` | 44-105 | No reference to resolver, preferenceStore, mode (test-enforced) | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 09-09 | No cross-backend replay, stays blocked | `core/anki/AnkiTransactionRecoveryRouting.kt` | `route` | 66-78 | Returns Blocked(BACKEND_NOT_REGISTERED|BACKEND_UNREACHABLE|COLLECTION_MISMATCH) per owner, never reroutes | SOURCE_CONFIRMED |
| 09-10 | Collection mismatch only when both known | `core/anki/AnkiTransactionRecoveryRouting.kt` | `route` | 71 | `if (recordCollectionKey != null && active != null && recordCollectionKey != active)` | SOURCE_CONFIRMED |
| 09-11 | Uncertain outcome blocks replay | `core/anki/ReviewCommit.kt`, `core/study/AnkiStudyEffectExecutor.kt` | `OutcomeUnknown`, `CommitConflict` | 59-65, etc | OutcomeUnknown blocks progression until reconciled, NextCard Failure CommitConflict | SOURCE_CONFIRMED |

---

## 10 — Read-Only Screen Contexts

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 10-01 | Library pinned ankiDroidBackend | `ui/navigation/AppNavHost.kt`, `di/AppContainer.kt` | `LibraryViewModel` | 81,492 | `LibraryViewModel(backend=container.ankiDroidBackend)`, `AnkiLibraryRepository(backend=ankiDroidBackend)` | SOURCE_CONFIRMED |
| 10-02 | Deck Details pinned | `ui/navigation/AppNavHost.kt` | `DeckDetailsViewModel` | 110 | `DeckDetailsViewModel(backend=container.ankiDroidBackend)` | SOURCE_CONFIRMED |
| 10-03 | Card Browser pinned, switchBackend no prod caller | `ui/navigation/AppNavHost.kt`, `ui/screens/cardbrowser/CardBrowserViewModel.kt` | `CardBrowserViewModel` | 144,163 | initialBackend=container.ankiDroidBackend, switchBackend exists but no prod caller (only tests) | SOURCE_CONFIRMED |
| 10-04 | Card Browser cache key hard-coded ankiDroidBackend.id.stableId | `ui/navigation/AppNavHost.kt` | VM key | 142 | Hard-coded | SOURCE_CONFIRMED |
| 10-05 | Card Details OWNED via cardRef.backendId | `ui/navigation/AppNavHost.kt` | `CardDetails` | 167-176 | `registry.find(cardRef.backendId)` unregistered => error screen | SOURCE_CONFIRMED |
| 10-06 | Edit Note OWNED via editCardRef.backendId | `ui/navigation/AppNavHost.kt` | `EditNote` | 248-258 | `find(editCardRef.backendId)` | SOURCE_CONFIRMED |
| 10-07 | Add Note OWNED lookup but seed PINNED | `ui/navigation/AppNavHost.kt` | `AddNote` | 94,204-222 | Seed `createRoute(container.ankiDroidBackend.id)` hard-coded, lookup `find(decodeBackendId)` | SOURCE_CONFIRMED |
| 10-08 | Library cache invalidated if backend changed | `data/anki/AnkiLibraryRepository.kt` | `invalidateIfBackendChanged` | 193,210 | `cached.backendId == backend.id`, refresh response backendId != expected clears cache | SOURCE_CONFIRMED |
| 10-09 | Browser request correlation RequestToken includes backendId | `ui/screens/cardbrowser/CardBrowserViewModel.kt` | `RequestToken` | 62 | `RequestToken(backend.id, generation, query, cursor)` | SOURCE_CONFIRMED |
| 10-10 | Deck routes raw deckId no backend discriminator gap | `ui/navigation/Screen.kt` | `deck-details`, `card-browser` | 17,64 | Raw deckId only | SOURCE_CONFIRMED |
| 10-11 | Caches NOT collection-scoped (collectionKey null) | `data/anki/AnkiLibraryRepository.kt`, `core/anki/AnkiRefs.kt` | — | — | Cache key backendId only, collectionKey always null AnkiDroid | SOURCE_CONFIRMED |
| 10-12 | No old backend data survives switch (generation token) | `ui/screens/cardbrowser/CardBrowserViewModel.kt` | `RequestToken` | 62 | Generation + backend id prevents stale overwrite | SOURCE_CONFIRMED |

---

## 11 — Reconnect & Process-Restart

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 11-01 | No Anki-backend reconnect path, all reconnect symbols belong to PC agent transport | `app/src/main` | `reconnect`, `Reconnect` | — | `rg -n "reconnect" app/src/main` → `core/network/ReconnectController.kt`, `WebSocketAgentConnection.kt`, `AppSettings.autoReconnect` — AI/TTS transport, not Anki | SOURCE_CONFIRMED |
| 11-02 | Agent disconnect: WebSocketAgentConnection scheduleReconnect exponential backoff | `core/network/WebSocketAgentConnection.kt` | `scheduleReconnect` | 659-706 | `maxReconnectAttempts`, `reconnectController.getNextDelayMs()`, `ConnectionState.Reconnecting(attempt, maxAttempts, nextRetryInMs)` | SOURCE_CONFIRMED |
| 11-03 | Agent reconnect proves transport identity not collection continuity | `core/anki/AnkiTransactionRecoveryRouting.kt` | `COLLECTION_MISMATCH` | 71 | Only when both keys known | SOURCE_CONFIRMED |
| 11-04 | AnkiDroid provider unavailable → ProviderUnavailable/PermissionRequired/CollectionNotInitialized, session gets BackendUnavailable no fallback | `data/anki/ankidroid/AnkiDroidHealthRepository.kt`, `data/anki/ankidroid/AnkiDroidBackend.kt` | `usabilityError` | 90,142,158 | Health repo probes, backend usabilityError returns typed error, nextCard => BackendUnavailable | SOURCE_CONFIRMED |
| 11-05 | AnkiDroid becomes available → health snapshot publishes, transactions become Routable | `data/anki/ankidroid/AnkiDroidBackend.kt` | `updateFromHealthSnapshot` | — | Updates _integrationState from health snapshot | SOURCE_CONFIRMED |
| 11-06 | Process death loses lock tracker, ledgers survive | `core/anki/AnkiBackendStatus.kt`, `core/anki/AnkiSessionContext.kt`, `di/AppContainer.kt` | `AnkiStudyBackendLockTracker` | 128-160,10,439 | MutableStateFlow process memory, AnkiSessionContext not @Serializable, AppContainer comment startup recovery off splash path ledgers loaded but study state not mutated | SOURCE_CONFIRMED |
| 11-07 | Restart SUBMITTING -> AMBIGUOUS, recoveryBlocker before scheduler | `core/anki/ReviewCommitLedger.kt`, `core/study/AnkiStudyEffectExecutor.kt` | `health`, `recoveryBlocker` | 556-567 | First load converts SUBMITTING left by dead process into AMBIGUOUS, blocker checked before any scheduler traffic, availability refreshed inside begin() after recovery scan :544 | SOURCE_CONFIRMED |
| 11-08 | Preference change does NOT affect active lock | `ui/screens/settings/SettingsViewModel.kt`, `ui/screens/study/StudyViewModel.kt` | `selectAnkiBackend`, `lockedAnkiBackendLabel` | 82,88 | Writes mode, locked label reads lock tracker never preference | SOURCE_CONFIRMED + TEST_CONFIRMED |
| 11-09 | RECONNECT != SAME COLLECTION VERIFIED | `core/anki/AnkiTransactionRecoveryRouting.kt`, `data/anki/ankidroid/AnkiDroidBackend.kt` | `collectionMatchesBoundSession` | 71,1336-1340 | Reconnect alone does not prove collection continuity, AnkiDroid fails open | SOURCE_CONFIRMED |

---

## 12 — Security & Trust Boundaries

| # | Claim | File | Symbol | Line | Evidence | Confidence |
|---|---|---|---|---|---|---|
| 12-01 | AnkiDroid provider permission READ_WRITE_DATABASE | `data/anki/ankidroid/AnkiDroidPermissionManager.kt`, `AndroidAnkiDroidProbe.kt` | `AnkiDroidPermissionManager` | 25,43,196 | Permission state, protectionLevel, no interactive grant | SOURCE_CONFIRMED |
| 12-02 | Endpoint allowlist release cannot resolve debug authority | `data/anki/ankidroid/AnkiDroidApiContract.kt`, `di/AppContainer.kt` | `AnkiDroidEndpoints` | 790,814,809 | `forBuild(includeDebugEndpoints=BuildConfig.DEBUG)` | SOURCE_CONFIRMED |
| 12-03 | One write permit shared by every mutation family | `data/anki/ankidroid/AnkiDroidWritePermit.kt`, `di/AppContainer.kt` | `AnkiDroidWritePermit` | — | Serializes rating, reviewer action, note mutation, creation | SOURCE_CONFIRMED |
| 12-04 | No secret in descriptor | `core/anki/AnkiBackendDescriptor.kt` | `AnkiBackendDescriptor` | 55 | id, availability, capabilities, kind, locality only, no host/port/token/path | SOURCE_CONFIRMED |
| 12-05 | PC Anki trust model does NOT exist | `app/src/main` | — | — | No PC Anki backend code, no transport, no auth, `docs/SECURITY.md` and `SecureTokenStorage` cover agent transport only, `grep -rin "anki" ProtocolMessages.kt` empty | SOURCE_CONFIRMED |
| 12-06 | Backend capability checked via descriptor isReachable + supports, not via name | `core/anki/AnkiBackendDescriptor.kt` | `isReachable`, `supports` | 75,95 | Positive knowledge Ready && review, fail-closed both projections | SOURCE_CONFIRMED |
| 12-07 | No credentials exposed in reports | `docs/` | — | — | No token, host, path in any doc | SOURCE_CONFIRMED |

---

## 13 — Existing Test & Coverage Inventory

| # | Claim | File | Test count | Evidence | Confidence |
|---|---|---|---|---|---|
| 13-01 | Gate21ArchitectureAuditTest 15 tests backend selection/routing invariants | `app/src/test/java/com/studyagent/client/anki/Gate21ArchitectureAuditTest.kt` | 15 | registry refuses duplicate, session start uses selector once, no hidden fallback, no preferenceStore in executor, etc | TEST_CONFIRMED |
| 13-02 | Gate21BackendResolutionTest 13 tests AUTO ordering, capability, Ambiguous | `Gate21BackendResolutionTest.kt` | 13 | AUTO prefers local over remote, multiple PC => Ambiguous, capability unsatisfiable => RequiredCapabilityUnavailable, etc | TEST_CONFIRMED |
| 13-03 | Gate21TransactionRecoveryRoutingTest 9 tests grouping, no resolver, collection mismatch | `Gate21TransactionRecoveryRoutingTest.kt` | 9 | groups by persisted backendId, never consults resolver, collection mismatch only when both known | TEST_CONFIRMED |
| 13-04 | Gate21SessionBackendLockTest 7 tests lock survives config, not persisted, label reads lock | `Gate21SessionBackendLockTest.kt` | 7 | lock tracker process memory, locked label reads lock not preference | TEST_CONFIRMED |
| 13-05 | Gate21BackendSelectionUiTest 8 tests AUTO option, ambiguous, displayLabel, stableId round-trip | `Gate21BackendSelectionUiTest.kt` | 8 | AUTO lists local and PC, ambiguous PC shows candidates, displayLabel, fromStableId | TEST_CONFIRMED |
| 13-06 | Gate21BackendRoutingDiagnosticsTest 4 tests four roles distinct, no secret | `Gate21BackendRoutingDiagnosticsTest.kt` | 4 | preferred/resolved/locked/transaction four distinct rows, availability tokens | TEST_CONFIRMED |
| 13-07 | Total GATE 21 tests 56 | `app/src/test` | 56 | 15+13+9+7+8+4 =56 | TEST_CONFIRMED |
| 13-08 | Rating recovery tests | `Gate11dRecoveryAuditTest.kt`, `Gate11bTransactionTest.kt`, `Gate11bRecoveryMatrixTest.kt`, etc | — | SUBMITTING->AMBIGUOUS, OutcomeUnknown blocks, no double apply | TEST_CONFIRMED |
| 13-09 | Reviewer action, note edit, note creation tests | `Gate13ArchitectureAuditTest.kt`, `Gate17ArchitectureAuditTest.kt`, `Gate18ArchitectureAuditTest.kt`, `NoteMutationCoordinatorTest.kt`, `NoteCreationCoordinatorTest.kt` | — | backendId persisted, BackendMismatch, safety policy | TEST_CONFIRMED |
| 13-10 | Custom study NOT_IMPLEMENTED no tests | `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` | 0 | BLOCKED | DOCUMENTED_ONLY |
| 13-11 | Library, Deck Details, Card Browser, Card Details tests | `LibraryViewModelTest.kt`, `DeckDetailsViewModelTest.kt`, `AnkiCardBrowserBackendTest.kt`, `CardDetailsFakeBackendTest.kt` | — | cache invalidated, request correlation, exact lookup, foreign refused | TEST_CONFIRMED |
| 13-12 | AnkiDroid integration tests | `AnkiDroidHealthRepositoryTest.kt`, `AnkiDroidCapabilityProbeTest.kt`, `AnkiDroidBackendTest.kt`, etc | — | health transitions, capability probe, deck mapper, card mapper, rating commit, reviewer action | TEST_CONFIRMED |
| 13-13 | Connection/reconnect agent tests partial | `WebSocketAgentConnectionTest` (if exists) | — | reconnect exponential backoff, max attempts | PARTIAL (agent only, Anki backend reconnect does not exist) |

---

## 14 — Evidence Consolidation Summary

All claims above include file, symbol, line range, evidence, confidence. No claim exceeds evidence. PC column all ✘ because no code. Collection identity AnkiDroid UNAVAILABLE. No latency measurement. No heuristic equivalence. No secret exposed.

Confidence distribution:

- SOURCE_CONFIRMED: majority (direct source read)
- TEST_CONFIRMED: routing invariants, resolution ordering, recovery grouping, lock behavior
- DOCUMENTED_ONLY: GATE 20 BLOCKED, custom study
- UNKNOWN: PC backend behavior (no implementation to inspect)

Limitations:

- No Android device, no Anki instance, no PC agent run — all statements about repository code, not live backend.
- Gradle build not executed in sandbox (no JDK/Android SDK) — harness used previously per `tools/jvm-harness/README.md`, but this investigation re-baselined via source audit only.
- Compose UI wiring (AppContainer, AppNavHost, SettingsScreen, StudyScreen) verified by source audit only, not by execution.
- Real-device mutation verification not performed — maturity IMPLEMENTED not VERIFIED.

---

## Traceability Matrix for GATE 21B

| GATE 21B Decision | Evidence Ledger Entries | Blocker? |
|---|---|---|
| B01 Backend identity contract | 02-01 to 02-12 | YES — PC allocation unauditable |
| B02 Collection identity policy | 03-01 to 03-08 | YES — AnkiDroid UNAVAILABLE fails open |
| B03 Resource qualification | 04-01 to 04-12 | NO — already backend-qualified except media |
| B04 AUTO selection policy | 07-04, 07-05, 13-02 | NO — already pure deterministic |
| B05 Manual backend selection | 07-01 to 07-03, 13-05 | NO |
| B06 Immutable StudySession ownership | 08-01 to 08-10 | NO — but must state process memory |
| B07 Transaction backend ownership | 09-01 to 09-11 | NO — already persisted and pinned, but note coordinators pinned latent |
| B08 Recovery routing | 09-08 to 09-11, 13-03 | NO — already never re-routes |
| B09 Reconnect verification | 11-01 to 11-09 | YES — reconnect != verified continuity |
| B10 Read-context switching | 10-01 to 10-12, 07-30 | YES — pinned read contexts + deck-id reinterpretation hazard |
| B11 Failure/fallback boundaries | 07-25 to 07-30 | NO — no hidden fallback |
| B12 Custom study | 04-09, 09-05 | YES — BLOCKED until GATE 20 |

---

**End of Evidence Ledger**
