# Backend Capability Matrix

**Gate:** GATE 21A — Backend Architecture & Contract Discovery
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/47bec43d-study-agent-client`
**HEAD:** `2d2a0a095ac9b50f10bdf101528491dc6b7e68a4`
**Date:** 2026-10-11
**Companion to:** `docs/GATE_21A_DISCOVERY.md`, `docs/BACKEND_IDENTITY_INVENTORY.md`

One hierarchy describes both physical situations (local provider, remote agent). Nothing in `core/anki`
branches on a package name, a host or a connection state to decide availability.

---

## 1. Availability — `core/anki/AnkiAvailability.kt:24`

| State | Meaning | Applies to | `statusCode` | `unavailabilityError()` |
|---|---|---|---|---|
| `Checking` | Probe has not produced a result yet. Neither ready nor failed. | both | `CHECKING` | `BackendUnavailable` |
| `NotInstalled` | AnkiDroid is not on this device. | AnkiDroid | `NOT_INSTALLED` | `BackendUnavailable` |
| `ProviderUnavailable(detail)` | App present, exported integration provider unreachable / unexpected package / removed between checks. | AnkiDroid | `PROVIDER_UNAVAILABLE` | `ProviderUnavailable` |
| `PermissionRequired(detail)` | Installed, but the integration permission was not granted. | AnkiDroid | `PERMISSION_REQUIRED` | `PermissionRequired` |
| `CollectionNotInitialized` | No initialized collection accessible. | AnkiDroid | `COLLECTION_NOT_INITIALIZED` | `CollectionUnavailable` |
| `TemporarilyUnavailable(reason)` | Expected to self-recover (provider locked, collection reopening). | AnkiDroid | `TEMPORARILY_UNAVAILABLE` | `BackendUnavailable` |
| `Ready(capabilities)` | Usable now; each operation still needs its capability. | both | `READY` | `null` |
| `AgentDisconnected` | The PC agent itself is unreachable. | PC | `AGENT_DISCONNECTED` | `BackendUnavailable` |
| `AgentAnkiUnavailable` | Agent reachable, Anki beneath it is not ready. | PC | `AGENT_ANKI_UNAVAILABLE` | `BackendUnavailable` |
| `Unsupported(reason)` | Backend exists but cannot serve this app (version / API shape). | both | `UNSUPPORTED` | `UnsupportedAction("backend_api")` |
| `Fault(error)` | A classified domain error occurred while probing. | both | `FAULT` | the carried `error` |

Readiness is **positive knowledge**, not the absence of an error:

```kotlin
val AnkiAvailability.isReadyForReview: Boolean
    get() = (this as? AnkiAvailability.Ready)?.capabilities?.review == true      // :77
```

`AnkiBackendDescriptor.isReachable` (`core/anki/AnkiBackendDescriptor.kt:75`) is weaker — `availability is
Ready` — because browsing may be possible under conditions where a rating-writing session may not.

### 1.1 Availability is mutable at runtime

`AnkiBackend.availability` is a `StateFlow` per backend; `refreshAvailability()` re-probes.
`AnkiDroidHealthRepository` (`data/anki/ankidroid/AnkiDroidHealthRepository.kt:90`) owns the real probing
and refreshes on `onAppForeground()` (`:142`) and `requestRefresh()` (`:158`), publishing only if still
current (`:48`). `FakeAnkiBackend.setAvailability` (`app/src/test/.../fake/FakeAnkiBackend.kt:194`) models
the same mutation for tests.

**No measurement of transition frequency exists in this repository.**

### 1.2 Two capability projections can disagree

`AnkiAvailability.Ready(capabilities)` carries a snapshot taken at probe time; `AnkiBackend.capabilities`
is the live projection. `AnkiBackendDescriptor.supports` (`:95`) requires **both** to agree — the same
fail-closed rule GATE 01 applied, now in one place.

---

## 2. Capabilities — `core/anki/AnkiCapabilities.kt`

26 flags/groups, every default the safe `false` / `NONE` / `NoteConflictGuarantee.NONE`.
`AnkiCapabilities.NONE` is the all-off constant.

| Flag | What it claims | AnkiDroid claim source |
|---|---|---|
| `review` | due cards **and** rating commits | `implementedCapabilitiesFor` |
| `scheduledReview` | can ask the scheduler for the next card without writing | `implementedCapabilitiesFor` |
| `deckListing` | read-only deck listing | `implementedCapabilitiesFor` |
| `deckCounts` | counts exposed **and semantics verified** | **stays `false`** — counts are mapped best-effort (`AnkiDroidCompatibilityPolicy.kt:125`) |
| `renderedCards` | backend-normalized card content | `implementedCapabilitiesFor` |
| `reviewIntervals` | scheduler interval metadata | `implementedCapabilitiesFor` |
| `media` | media read | **stays `false`** — Diagnostics renders it as "Pending GATE 09" (`DiagnosticsRepository.kt:737`) |
| `flags` | flag **mutation** | **stays `false`** — the pinned v2.24.1 card contract has no flag column |
| `bury` | bury through the audited public update | `implementedCapabilitiesFor` (GATE 13) |
| `suspendCards` | suspend through the audited public update | `implementedCapabilitiesFor` (GATE 13) |
| `editNotes` | coarse legacy flag | **stays `false`** — not a substitute for the GATE 17 trio |
| `createNotes` | create one note | `implementedCapabilitiesFor` (GATE 18) |
| `search` | collection-wide search | **stays `false`** |
| `cardDetails` | deep read-only details of one exact card | `implementedCapabilitiesFor` (GATE 16) |
| `cardBrowser` (`AnkiCardBrowserCapabilities`) | bounded browser read | **stays `NONE`** (see §2.2) |
| `editNoteFields` / `editNoteTags` / `changeCardDeck` | GATE 17 trio | `implementedCapabilitiesFor` |
| `authoritativeMutationReconciliation` | can prove whether an ambiguous edit applied | **stays `false`** |
| `noteEditConflictGuarantee` | concurrent-edit protection | `BEST_EFFORT_PRE_SAVE_REREAD` |
| `noteModelListing` | note types + authoritative creation schema | `implementedCapabilitiesFor` (GATE 18) |
| `storeMedia` | store one file through the **public** media API | `implementedCapabilitiesFor` (GATE 18) |
| `authoritativeCreationReconciliation` | can prove whether an ambiguous creation produced a note | **stays `false`** everywhere at this pin |
| `noteModelSchema` / `noteTemplateListing` / `noteTemplateSourceRead` / `noteModelCssRead` | GATE 19 enriched model reads | `implementedCapabilitiesFor` |

### 2.1 Claims are dropped when the writer is not wired — `AnkiDroidBackend.kt:211`

```kotlin
private fun withWriteSupport(capabilities: AnkiCapabilities): AnkiCapabilities = capabilities.copy(
    review          = capabilities.review          && ratingGateway != null,
    bury            = capabilities.bury            && reviewerActionCommitter != null,
    suspendCards    = capabilities.suspendCards    && reviewerActionCommitter != null,
    cardDetails     = capabilities.cardDetails     && noteGateway != null,
    editNoteFields  = capabilities.editNoteFields  && noteGateway != null && noteMutationGateway != null,
    editNoteTags    = capabilities.editNoteTags    && noteGateway != null && noteMutationGateway != null,
    changeCardDeck  = capabilities.changeCardDeck  && noteGateway != null && noteMutationGateway != null,
    createNotes     = capabilities.createNotes     && creationGateway != null,
    noteModelListing= capabilities.noteModelListing&& creationGateway != null,
    storeMedia      = capabilities.storeMedia      && creationGateway != null,
    noteModelSchema = capabilities.noteModelSchema && modelGateway != null,
    noteTemplateListing/noteTemplateSourceRead/noteModelCssRead = ... && modelGateway != null
)
```

A capability is therefore a claim **about the wired object graph**, not about a backend name.

### 2.2 The card-browser capability is `NONE` on the only real backend

Three independent pieces of evidence:

1. `AnkiDroidCompatibilityPolicy.kt:63` and `:106` — the audited report says
   `cardBrowser = CapabilitySupport.UNSUPPORTED` ("no public bounded card-list URI in the pinned contract").
2. `implementedCapabilitiesFor` never sets `cardBrowser`, so `AnkiCapabilities.cardBrowser` stays
   `AnkiCardBrowserCapabilities.NONE`.
3. `AppContainer.kt:326` wires `UnsupportedAnkiDroidCardBrowserGateway()`, whose `browseCards` always
   returns `AnkiError.UnsupportedQueryFeature` (`AnkiDroidCardBrowserGateway.kt:23-27`).

The only place a non-`NONE` browser capability appears in the whole source tree is a test fixture
(`Gate21BackendResolutionTest.kt:139`).

**Consequence for 21F/21B:** `AnkiRequiredCapability.CARD_BROWSER` is currently unsatisfiable on a real
backend. Any capability-constrained resolution of a browsing context must be specified so that it refuses
honestly (`RequiredCapabilityUnavailable`) rather than silently falling back to the hard-coded DI pin that
the Card Browser uses today.

---

## 3. Required capabilities — `core/anki/AnkiBackendCapabilityRequirement.kt`

19 requirements, mapped to flags by **one** function `AnkiCapabilities.supports(required)` (`:93`) so the
requirement and its enforcement cannot drift apart.

| `AnkiRequiredCapability` | Maps to | AnkiDroid today |
|---|---|---|
| `DECKS` | `deckListing` | ✔ |
| `DECK_COUNTS` | `deckCounts` | ✘ (best-effort counts only) |
| `SCHEDULED_REVIEW` | `scheduledReview` | ✔ |
| `REVIEW` | `review` | ✔ (gated on `ratingGateway`) |
| `RENDERED_CARDS` | `renderedCards` | ✔ |
| `CARD_BROWSER` | `cardBrowser.canBrowseAllCards \|\| canBrowseDeck` | ✘ |
| `CARD_DETAILS` | `cardDetails` | ✔ (gated on `noteGateway`) |
| `FLAGS` | `flags` | ✘ |
| `BURY` | `bury` | ✔ |
| `SUSPEND_CARDS` | `suspendCards` | ✔ |
| `EDIT_NOTE_FIELDS` | `editNoteFields` | ✔ |
| `EDIT_NOTE_TAGS` | `editNoteTags` | ✔ |
| `CHANGE_CARD_DECK` | `changeCardDeck` | ✔ |
| `NOTE_MODEL_LISTING` | `noteModelListing` | ✔ |
| `NOTE_MODEL_SCHEMA` | `noteModelSchema` | ✔ |
| `NOTE_TEMPLATE_LISTING` | `noteTemplateListing` | ✔ |
| `MEDIA_READ` | `media` | ✘ |
| `STORE_MEDIA` | `storeMedia` | ✔ |
| `CREATE_NOTE` | `createNotes` | ✔ |

```kotlin
val STUDY_SESSION_REQUIREMENTS: Set<AnkiRequiredCapability> = setOf(AnkiRequiredCapability.REVIEW)
```

`DECKS` is deliberately **excluded** from the study requirement (the file's own comment): a locked session
reads its next card by `AnkiCardRef` from the backend it is already locked to, so requiring deck listing
would narrow which backends may hold a session without adding safety. `AnkiBackendSelector.REVIEW_REQUIREMENT`
aliases this constant so the selector cannot drift from it.

---

## 4. Feature matrix

"PC" is **not** a second column of measurements — **no PC Anki backend exists in this repository**
(`grep -rn "PcAnkiBackend" app/src` → nothing; only `server/mock_pc_agent.py`, unrelated to Anki).

| Feature | AnkiDroid (`AnkiDroidBackend`) | PC Anki backend | `FakeAnkiBackend` (test) |
|---|---|---|---|
| Existence | ✔ `data/anki/ankidroid/AnkiDroidBackend.kt` | ✘ no type | ✔ `app/src/test/.../anki/fake/FakeAnkiBackend.kt` |
| Availability probing | ✔ 11-state, `AnkiDroidHealthRepository` | states exist, no producer | ✔ `setAvailability()` (`:194`) |
| Capability negotiation | ✔ `AnkiDroidCompatibilityPolicy` + gateway gating | no producer | ✔ constructor + `setCapabilities()` (`:198`) |
| Deck listing / Library | ✔ `AnkiDroidDeckGateway` | ✘ | ✔ fixture decks |
| Deck counts | mapped best-effort, **not verified** | ✘ | ✔ |
| Scheduled review + rating | ✔ `AnkiDroidReviewGateway` / `AnkiDroidRatingGateway` | ✘ | ✔ scripted `CommitStep`s |
| Card hydration | ✔ `AnkiDroidCardGateway` | ✘ | ✔ |
| Card browser | ✘ (`UnsupportedAnkiDroidCardBrowserGateway`) | ✘ | ✔ |
| Card details | ✔ `AnkiDroidCardDetailsMapper` | ✘ | ✔ `cardDetails` fixtures |
| Reviewer actions (bury/suspend) | ✔ | ✘ | ✔ (`flagWrites` opt-in) |
| Flags | ✘ no column in the pinned contract | ✘ | ✔ opt-in |
| Note edit (fields/tags/deck) | ✔ `AnkiDroidNoteMutationGateway` | ✘ | limited |
| Note creation + media | ✔ `AnkiDroidCreationGateway` | ✘ | limited |
| Media read | ✘ (`media = false`) | ✘ | — |
| Models / templates / CSS | ✔ `AnkiDroidModelGateway` (GATE 19) | ✘ | — |
| Custom study / filtered deck | ✘ (GATE 20 BLOCKED) | ✘ | ✘ |
| Collection identity | ✘ `collectionKey = null` | ✘ | ✔ (whatever the fixture sets) |
| Sync state | ✘ none exposed | ✘ | ✘ |

---

## 5. Capability change propagation

| Path | Behaviour |
|---|---|
| `AnkiBackend.capabilities: StateFlow` | live per-backend projection. |
| `AnkiAvailability.Ready(capabilities)` | probe-time snapshot. |
| `AnkiBackendStatusCenter` (`core/anki/AnkiBackendStatus.kt`) | combines `availability` × `capabilities` per registered backend into an eagerly-started `StateFlow<AnkiBackendDescriptor>`, then combines those with the preference into `AnkiBackendStatusSnapshot`. |
| `AnkiSessionContext.capabilities` | **frozen at session start** (`AnkiStudyEffectExecutor.kt:578`) — a later capability change cannot rewrite a running session. |
| `AnkiLocalStudyStarter` | re-reads and freezes `reviewerActionCapabilities()` once per start (GATE 13 §28). |
| Resolver | reads the descriptor snapshot at call time; it never probes or refreshes. |

Capability *regression* mid-session therefore cannot enable/disable a mutation under a running session.

---

## 6. Security and permission boundaries (A05 / A10)

| Backend | Prerequisite | Owner | Notes |
|---|---|---|---|
| AnkiDroid | third-party-declared `com.ichi2.anki.permission.READ_WRITE_DATABASE`; no interactive grant dialog exists. | `data/anki/ankidroid/AnkiDroidPermissionManager.kt` | Absence surfaces as `AnkiAvailability.PermissionRequired`, not as a crash. |
| AnkiDroid | Endpoint allowlist. | `AnkiDroidApiContract.kt:790` `AnkiDroidEndpoints`, `:814` `forBuild(includeDebugEndpoints = BuildConfig.DEBUG)` | A release build can never resolve the debug authority. |
| AnkiDroid | One physical-write permit shared by every mutation family. | `AnkiDroidWritePermit.kt`, `AppContainer.kt` wiring | Serializes rating / reviewer-action / note-mutation / creation writes. |
| PC Anki backend | **Not implemented.** No trust model, no transport, no authorization code exists for an Anki-over-agent path. | — | `docs/SECURITY.md` and `core/security/SecureTokenStorage.kt` cover the **PC agent** transport (AI/TTS/STT), which carries no Anki traffic — `grep -rin "anki" core/models/ProtocolMessages.kt` returns nothing. |
| Diagnostics | No secret in any descriptor. | `AnkiBackendDescriptor` doc comment | A descriptor carries id, availability, capabilities, kind, locality — no host, port, token or path. |

**Hybrid distinction confirmed by source:** the PC agent (`core/network/WebSocketAgentConnection.kt`,
`ReconnectController.kt`) is an **AI/voice service transport**. `AnkiAvailability.AgentDisconnected` /
`AgentAnkiUnavailable` are backend-availability states that **nothing produces today**. Conflating the two
would be a design error, and the code does not conflate them.

---

## 7. Honest gaps in this matrix

1. **No PC column can be filled.** Every "PC" cell above is `✘` because there is no code, not because a
   measurement showed absence. Anything else would be fabricated.
2. **`CARD_BROWSER`, `MEDIA_READ`, `FLAGS`, `DECK_COUNTS` are unsatisfiable on the only real backend.**
   Capability-constrained routing will therefore *refuse* operations that the UI currently offers through
   hard-coded DI pins. This must be resolved as a product decision in GATE 21B, not silently in 21F.
3. **No latency or performance measurement exists** for any backend path
   (`AnkiLibraryRepository` records a `latencyMs` for deck refresh; there is no local-vs-remote comparison).
4. **No sync state** is exposed by any backend, so "the two collections are synchronized" is unprovable.
5. **Maturity is `IMPLEMENTED`, not `VERIFIED`** for every AnkiDroid mutation capability: no real-device
   mutation run has happened in this repository (stated in `AnkiDroidCompatibilityPolicy.kt` comments).
