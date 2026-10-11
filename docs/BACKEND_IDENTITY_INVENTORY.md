# Backend Identity Inventory

**Gate:** GATE 21A — Backend Architecture & Contract Discovery
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/5cef8d58-study-agent-client` (base commit `28eec915cc198dd1a1b24f29dc2790fcd3938616`)
**Method:** source read of `app/src/main` + `app/src/test`. No Android device, no Anki instance and no PC
agent was run, so every statement below is a statement about **this repository's code**, not about a live
backend.

This is the companion inventory for `docs/GATE_21A_DISCOVERY.md`. It lists every identity type that
participates in backend routing, what it is derived from, and how it is classified.

## Classification vocabulary

| Class | Meaning |
|---|---|
| `AUTHORITATIVE` | Issued by the backend (or by this app as transaction owner) and stable across restarts. |
| `SESSION_STABLE` | Stable for the lifetime of one process/session object; not persisted. |
| `HEURISTIC` | A guess or a display convenience. Never used for routing in this repo; would be a defect if it were. |
| `UNAVAILABLE` | The code cannot produce this identity at all. |
| `UNKNOWN` | No implementation exists, so the question cannot be answered from source. |

---

## 1. Backend identity

### 1.1 `AnkiBackendId` — `core/anki/AnkiBackendId.kt:8`

```kotlin
@Serializable
sealed interface AnkiBackendId { val stableId: String
    @SerialName("ankidroid_local") data object AnkiDroidLocal   // stableId = "ankidroid_local"
    @SerialName("pc_agent")        data class PcAgent(profileId) // stableId = "pc_agent:<profileId>"
    @SerialName("fake")            data class Fake(id = "fake")  // stableId = "fake:<id>"
}
```

| Property | Value |
|---|---|
| Classification | `AUTHORITATIVE` (AnkiDroid), `AUTHORITATIVE-BY-CONFIGURATION` (PC), `SESSION_STABLE` (Fake) |
| Kind vs instance | **Separated.** Two PC configurations are two identities (`PcAgent("home")` ≠ `PcAgent("work")`). |
| Serialization | `@Serializable` sealed interface + `@SerialName`; `fromStableId(String?)` (`:32`) round-trips all three and returns `null` for an unrecognized token. |
| Persistence | Persisted inside every durable ledger record (§4) and inside navigation route tokens (§5). |

**The type comment states the intent explicitly:** *"Logical identity, not object identity. PC profiles
must never share a write ledger."*

**Identity is logical, not instance-based.** `AnkiBackendRegistry` (`core/anki/AnkiBackendSelector.kt:10-12`)
builds `associateBy { it.id }` and then `require(byId.size == backends.size) { "Duplicate logical backend
identity" }`, so two objects claiming one identity cannot both be registered.

### 1.2 `AnkiBackendMode` — `core/anki/AnkiBackendId.kt:45`

An **enum of preferences**, deliberately not an identity:

| Mode | `accepts(id)` |
|---|---|
| `AUTO` | `AnkiDroidLocal` **or** `PcAgent` — never `Fake` |
| `ANKIDROID_LOCAL` | `AnkiDroidLocal` only |
| `PC_AGENT` | `PcAgent` only |

`AUTO` therefore never resolves to a test double through a production mode.

### 1.3 `AnkiBackendKind` / `AnkiBackendLocality` — `core/anki/AnkiBackendDescriptor.kt:11,28`

Derived metadata, never identity:

- `AnkiBackendId.kind` (`:34`) → `ANKIDROID_LOCAL` / `PC_AGENT` / `FAKE`
- `AnkiBackendKind.locality` (`:41`) → `LOCAL_DEVICE` / `REMOTE_AGENT` / `TEST_DOUBLE`

`AnkiBackendDescriptor.init` refuses a kind or locality that contradicts the identity
(`require(kind == id.kind)`, `:67`), so a descriptor cannot carry self-refuting facts.

### 1.4 Residual identity risks (A02)

| Risk | Status | Evidence |
|---|---|---|
| Multiple PC configurations sharing one identity | **Mitigated** — the profile discriminator is part of the identity and of `stableId`. | `AnkiBackendId.kt:19` |
| A PC backend constructed with an unstable/blank profile id | **Refused at construction** — `init { require(profileId.isNotBlank()) }`. | `AnkiBackendId.kt:20` |
| Two distinct PC *instances* configured with the same profile id | **Real risk, unmitigated by code.** No producer exists yet (`grep -rn "PcAgent(" app/src/main` returns only the declaration and the two `when` branches), so there is no allocation policy to audit. | `AnkiBackendId.kt:19`; `AnkiBackendDescriptor.kt:37`; `AnkiBackendUiModels.kt:58` |
| A `Fake` identity leaking into production routing | **Mitigated** — `AnkiBackendMode.accepts` rejects `Fake` for every mode. | `AnkiBackendId.kt:49-51` |
| Treating the kind as the identity | **Mitigated in `core`** (nothing branches on kind to authorize an operation) but the kind *is* an input to AUTO ordering (`AnkiBackendDescriptor.orderingKey`, `AnkiBackendResolver` locality steps). | `AnkiBackendDescriptor.kt:88`; `AnkiBackendResolver.kt` step 4/5 |
| Backend identity inferred from a display label | **No such code.** `AnkiBackendId.displayLabel()` is UI-only (`ui/components/anki/AnkiBackendUiModels.kt:50`). | — |

---

## 2. Collection identity

### 2.1 `AnkiCollectionIdentity` — `core/anki/AnkiRefs.kt:7`

```kotlin
@Serializable
data class AnkiCollectionIdentity(val backendId: AnkiBackendId, val collectionKey: String?)
```

Doc comment: *"Backend-issued collection discriminator. Null means unknown, never an invented default."*

### 2.2 Per-backend availability

| Backend | Authoritative collection identity | Classification |
|---|---|---|
| AnkiDroid | **None produced.** Decks are built with `collectionKey = null` (`data/anki/ankidroid/AnkiDroidDeckMapper.kt:93`, `:114`); card/note refs inherit whatever the *request* carried (`AnkiDroidCardMapper.kt:103`, `:136`, `:176`), and the request keys come from those same decks. | `UNAVAILABLE` |
| PC agent | No implementation exists. | `UNKNOWN` |

### 2.3 Consequence, stated in code — `AnkiDroidBackend.kt:1336`

```kotlin
private fun collectionMatchesBoundSession(...): Boolean {
    val boundKey = record.session.context.collection?.collectionKey ?: return true   // ← fails open
    ...
}
```

An unknown bound key **cannot** detect a collection change. An AnkiDroid profile switch during a session
is therefore invisible to this code. This is the single most important discovery for GATE 21B's reconnect
policy (B10): it is the reason a reconnect must not be classified as verified continuity.

### 2.4 Cross-backend collection equivalence

| Question | Answer |
|---|---|
| Can two backends' collections be proven equal? | **No.** No collection id, profile id, database identity or account identity exists on either path. |
| Is there a heuristic equivalence (deck names, counts, card ids)? | **No.** No code compares two backends' collections. |
| Is there sync state (hkey, server id, last-sync timestamp)? | **No.** `grep -rin "sync" core/anki data/anki` returns nothing that identifies a collection. |
| Does the recovery planner invent equivalence? | **No.** `AnkiTransactionRecoveryPlanner.route` reports `COLLECTION_MISMATCH` only when **both** the record key and the live key are non-null (`core/anki/AnkiTransactionRecoveryRouting.kt:71`). |

`AnkiSessionContext.init` only refuses a *structurally* foreign collection
(`require(collection == null || collection.backendId == backendId)`, `core/anki/AnkiSessionContext.kt:26`) —
a type check, not a collection check.

---

## 3. Resource identity

Every persisted reference type is backend-qualified **at construction** and mixes the backend identity into
its stable key via `identityKey(vararg parts)` (`core/anki/AnkiBackendId.kt:61`), which length-prefixes each
part so `"1"+"23"` cannot collide with `"12"+"3"`.

| Type | File:line | Qualification | Stable key ingredients |
|---|---|---|---|
| `AnkiCollectionIdentity` | `AnkiRefs.kt:7` | `backendId` + nullable `collectionKey` | (identity itself) |
| `AnkiDeckRef` | `AnkiRefs.kt:13` | `backendId`, `deckId`, `collectionKey` | backend, collection, `"deck"`, deckId |
| `AnkiNoteRef` | `AnkiRefs.kt:27` | `backendId`, `noteId`, `collectionKey` | backend, collection, `"note"`, noteId |
| `AnkiCardRef` | `AnkiRefs.kt:49` | `backendId`, `cardId?`, `noteId?`, `cardOrd?`, `collectionKey` | backend, collection, `"card"`, cardId, noteId, ord |
| `AnkiNoteModelRef` | `AnkiNoteModels.kt:25` | `backendId`, `modelId`, `collectionKey` | `"anki:<backend>:<collection|?>:model:<modelId>"` |
| `AnkiCardTemplateRef` | `AnkiTemplateModels.kt:18` | nested `modelRef` + `collectionKey`, with a cross-consistency `require` | nested |
| `AnkiMediaRef` | — | **Does not exist.** Creation media is addressed by `StoreMediaBackendRequest.backendId` (`core/anki/create/NoteCreationBackendResults.kt:68-74`); the *result* is a bare `MediaStoreBackendResult.Stored(mediaName)` (`:93`) with no backend qualification. | — |
| `AnkiFilteredSessionRef` | — | **Does not exist.** GATE 20 is BLOCKED (`docs/GATE_20_CUSTOM_STUDY_CONTRACT.md`), so no filtered-session type was ever added. `StudyMode.CUSTOM_SESSION` exists as a wire enum with no client logic (`core/models/StudyControlModels.kt:37`). | — |

### 3.1 Card identity rules — `AnkiRefs.kt:49-68`

```
require(cardOrd == null || noteId != null)                     // an ordinal needs a note identity
require(cardId != null || (noteId != null && cardOrd != null)) // cardId, or noteId + ord
```

Structural equality includes **all** fields, including the collection key. The comment is explicit:
*"Unknown collection is not a wildcard."*

### 3.2 Numeric-id collisions

Identical numeric ids under different backends are **different references** by construction, because
`backendId.stableId` is the first length-prefixed component of every stable key. `AnkiArchitectureContractTest`
already pins this. No correlation/mapping/translation table exists anywhere in `app/src`.

---

## 4. Transaction identity (durable ownership)

| Transaction (Gate) | Record type | Backend owner field | Persisted? |
|---|---|---|---|
| GATE 11 rating | `ReviewCommitRecord` (`core/anki/ReviewCommit.kt:175`) | `backendId` derived from `commitId.backendId` (`:272`) | yes |
| GATE 13 reviewer action | `ReviewerActionRecord` (`core/anki/ReviewerActionRecord.kt:110`) | `backendId` field, plus `collectionRef` | yes |
| GATE 17 note edit | `NoteMutationRecord` (`core/anki/edit/NoteMutationRecord.kt:21`) | `backendId` | yes |
| GATE 18 note creation | `NoteCreationRecord` (`core/anki/create/NoteCreationRecord.kt:47`) | `backendId` | yes |
| GATE 20 custom study | **No record type** — GATE 20 unimplemented | — | — |

Cross-family identity keys:

- `ReviewCommitId(backendId, studySessionId, turnId)` — `core/anki/AnkiSessionContext.kt:42`,
  `stableKey = identityKey(backendId.stableId, studySessionId, turnId.value)`
- `ReviewerActionId` — `identityKey(backendId.stableId, studySessionId, turnId.value, action.key)`
  (`core/anki/ReviewerActionRecord.kt:39`)

Structural guards that make a cross-backend record impossible to build:

| Guard | Location |
|---|---|
| `require(commitId.backendId == card.backendId)` | `ReviewCommit.kt:230` |
| `require(cardRef.backendId == backendId)` | `ReviewerActionRecord.kt:147` |
| `require(collectionRef == null \|\| collectionRef.backendId == backendId)` | `ReviewerActionRecord.kt:148` |
| `require(card.backendId == session.context.backendId)` | `AnkiBackend.kt` (`CardActionRequest.init`) |
| `require(collection == null \|\| collection.backendId == backendId)` | `AnkiSessionContext.kt:27` |

---

## 5. Session identity and lock

`AnkiSessionContext` — `core/anki/AnkiSessionContext.kt:10`

```kotlin
data class AnkiSessionContext(
    val backendId: AnkiBackendId,
    val collection: AnkiCollectionIdentity?,
    val deckRef: AnkiDeckRef?,
    val startedAtEpochMs: Long,
    val capabilities: AnkiCapabilities,
    val studySessionId: String
)
```

| Fact | Evidence |
|---|---|
| It is **not** `@Serializable`. | `AnkiSessionContext.kt:10` — plain `data class`, unlike the ref types. |
| It holds no backend *object*. | Fields are identities + a capability snapshot. |
| Created exactly once per session, inside the study effect executor, after the deck is validated against the live collection. | `core/study/AnkiStudyEffectExecutor.kt:578` |
| Published to the lock tracker only after `beginReview` succeeded. | `AnkiStudyEffectExecutor.kt:585` |
| Released only on end-review. | `AnkiStudyEffectExecutor.kt:802` |
| The tracker (`AnkiStudyBackendLockTracker`, `core/anki/AnkiBackendStatus.kt`) is a `MutableStateFlow<Map<String, AnkiSessionContext>>` — **process memory only**. | `AnkiBackendStatus.kt`, `locked()` / `released()` / `lockedBackendFor()` |

**Classification:** the lock is `AUTHORITATIVE` for the process lifetime and **not persisted**. Process
death loses it; only the durable ledgers survive (see §6 of `GATE_21A_DISCOVERY.md`).

---

## 6. Persisted identity formats (compatibility surface for 21C/C06)

All four ledgers persist through `kotlinx.serialization` JSON envelopes with an explicit schema version.
`AnkiBackendId` travels inside every record, so **backend ownership is part of the on-disk format today**.

| Ledger | Envelope | Version | Migration path |
|---|---|---|---|
| `ReviewCommitLedger` (`core/anki/ReviewCommitLedger.kt`) | `Envelope(schemaVersion, records, tombstones)` (`:78`) | `SCHEMA_VERSION = 4` (`:67`) | `schemaVersion in 1..4`; `<4` rewritten by `normalizeLegacyVocabulary` (`:157`) then `migrateLegacy(hasAttemptPhase)` (`:120-123`). Out-of-range ⇒ `Unreadable`, never an empty ledger. |
| `ReviewerActionLedger` (`core/anki/ReviewerActionLedger.kt`) | `Envelope(SCHEMA_VERSION, records.map { it.toWire() })` (`:97`) | `SCHEMA_VERSION = 1` (`:43`) | Exact match required (`:104`); a mismatch is `Unreadable`. |
| `NoteMutationLedger` (`core/anki/edit/NoteMutationLedger.kt`) | `Snapshot(version, records)` (`:75`) | `VERSION = 1` (`:67`) | see file |
| `NoteCreationLedger` (`core/anki/create/NoteCreationLedger.kt`) | `Snapshot(version, records)` | `VERSION = 1` (`:69`) | see file |

Two invariants are enforced at *decode* time and matter for any future identity migration
(`ReviewCommitLedger.kt:126-131`):

```
duplicate commit ids                       → Unreadable("duplicate_commit_ids")
two unresolved records for one session     → Unreadable(MULTIPLE_UNRESOLVED_PER_SESSION)
```

**Compatibility note for 21C:** because `backendId` is a `@Serializable` sealed interface, an unknown
future variant (or a removed one) fails decoding as `Unreadable` rather than decoding into a guessed
backend. That is the desired fail-closed behaviour and must not be replaced by a default.

### 6.1 Backend preference

| Key | Location |
|---|---|
| `stringPreferencesKey("anki_backend_mode")` | `data/preferences/AppSettingsPreferencesCodec.kt:90` |
| `ankiBackendModeFlow` | `data/preferences/PreferencesDataStore.kt:117` |
| `DataStoreAnkiBackendPreferenceStore` | `data/preferences/DataStoreAnkiBackendPreferenceStore.kt` |

The store seeds `AUTO` and mirrors writes optimistically; a failed write leaves storage authoritative and
surfaces `"backend_preference_not_saved"` rather than pretending the switch happened.

---

## 7. Route / navigation identity

| Route | Token contents | File |
|---|---|---|
| `card-details` | `backendId.stableId`, `collectionKey`, `cardId`, `noteId`, `cardOrd` (base64, `NULL_TOKEN` for nulls) | `ui/navigation/Screen.kt:29`, `:95-122` |
| `edit-note` | same card-ref token | `Screen.kt:42` |
| `add-note/{backendId}` | `backendId.stableId` | `Screen.kt:51-57` |
| `deck-details` / `card-browser` | raw `deckId` **only** — no backend discriminator | `Screen.kt:17`, `:64` |

**Gap (F07 / 21F):** the deck-level routes carry no backend identity. `Screen.CardBrowser`'s ViewModel key
compensates by embedding `container.ankiDroidBackend.id.stableId` (`ui/navigation/AppNavHost.kt:142`), but
that is a *ViewModel cache key*, not route identity, and it is currently derived from a hard-coded backend.
A deck id from backend A is therefore structurally indistinguishable from the same deck id on backend B
once it is in the route string.

---

## 8. Identity classification summary

| Identity | Class |
|---|---|
| `AnkiBackendId.AnkiDroidLocal` | `AUTHORITATIVE` |
| `AnkiBackendId.PcAgent(profileId)` | `AUTHORITATIVE` **iff** the profile id is stable; no producer exists yet |
| `AnkiBackendId.Fake(id)` | `SESSION_STABLE` (test double) |
| `AnkiBackendKind` / `AnkiBackendLocality` | metadata, `AUTHORITATIVE` (derived, non-routing) |
| `AnkiCollectionIdentity` — AnkiDroid | `UNAVAILABLE` (always `collectionKey = null`) |
| `AnkiCollectionIdentity` — PC | `UNKNOWN` (no implementation) |
| `AnkiCardRef` / `AnkiNoteRef` / `AnkiDeckRef` / `AnkiNoteModelRef` / `AnkiCardTemplateRef` | `AUTHORITATIVE` (backend-qualified at construction) |
| Media after creation (`Stored(mediaName)`) | **Unqualified** — no `AnkiMediaRef` type exists |
| Filtered-session reference | **Does not exist** (GATE 20 BLOCKED) |
| `AnkiSessionContext` (the lock) | `AUTHORITATIVE` in-process, **not persisted** |
| Durable transaction `backendId` (4 families) | `AUTHORITATIVE`, persisted, versioned envelope |
