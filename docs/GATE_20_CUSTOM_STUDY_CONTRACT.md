# GATE 20 — Custom Study / Filtered Review Contract (Discovery + Contract Decision)

**Status:** Phase A (discovery) complete for what can be proven from source. Phase B (contract lock) **BLOCKED**. Phase C (implementation) **not started** — `IMPLEMENTATION AUTHORIZED: NO`.

**Scope of this document:** facts, evidence and a contract decision only. No production code, no UI, no new types. Nothing in this document defines `FilteredStudySession`, `CustomStudyCoordinator`, `FilteredDeckRequest`, `FilteredSessionStatus` or `FilteredSessionLedger`.

## 0. Evidence sources and their limits

| Source | What it is | Pinned version | Limit |
|---|---|---|---|
| Anki Rust backend `rslib` + `proto/anki/*.proto` | Authoritative Anki desktop/backend scheduler | `ankitects/anki` tag **25.09.2** (shallow clone, source read) | Source evidence only. No live Anki instance was run in this sandbox. |
| AnkiDroid app + `api` module | Public provider (`FlashCardsContract`, `CardContentProvider`) and app-internal UI | `ankidroid/Anki-Android` tag **v2.24.1** (same pin as `AnkiDroidApiContract.kt`) | Source evidence only. No device / disposable-collection run. |
| AnkiConnect plugin | Desktop add-on bridge, the documented PC route for Study-Agent (`docs/ARCHITECTURE.md`) | `FooSoft/anki-connect` default branch | Source evidence only. |
| Study-Agent repo | Existing Anki layer, protocol docs, GATE 01–19 docs | branch `arena/571cda3d-study-agent-client` @ `062439a` | The PC agent itself is **not in this repository** (only `server/mock_pc_agent.py`). |

**Consequence:** every PC-agent statement below is about Anki's backend, not about the PC agent. Whether the PC agent calls those APIs is **UNKNOWN**.

---

## PHASE A — DISCOVERY REPORT

### DISCOVERY-20-01 — Existing Study-Agent surfaces

| Capability | Existing Study-Agent | AnkiDroid | PC (Anki backend / AnkiConnect) |
|---|---|---|---|
| Custom study | Wire enum `StudyMode.CUSTOM_SESSION` (`"custom"`, "Custom Session") in `app/.../core/models/StudyControlModels.kt`. No client logic uses it (grep). Semantics are PC-agent-owned and not in this repo. | No public API. App-internal `CustomStudyDialog` exists (AnkiDroid source). | Backend `CustomStudy` RPC exists. Not in AnkiConnect. |
| Filtered deck create / rebuild / empty | None | None via provider. App-internal rebuild/empty in `StudyOptionsFragment`. | Backend `AddOrUpdateFilteredDeck`, `RebuildFilteredDeck`, `EmptyFilteredDeck`. No AnkiConnect equivalent. |
| Filtered review session | None | Existing filtered decks appear in deck list (`deck_dyn`). Queue read via `schedule` (provider). | `GetQueuedCards` serves filtered-deck queues. |
| Deck-scoped study | `start_session.deck` by name (PROTOCOL §2.3). Session-level, PC agent decides cards. | `AnkiDroidBackend` review session bound to `deckRef`, refuses null (`review_requires_deck_ref`). | Deck-scoped via `deck:` search (includes children). |
| Tag-filtered study | None | None | Backend custom-study cram tags only (`tags_to_include`/`tags_to_exclude`). |
| State-filtered study | None (`StudyMode` NEW/DUE/WEAK/INCORRECT are Study-Agent/PC-agent labels, not verified backend filters) | None | Backend cram kinds NEW/DUE/REVIEW/ALL. |
| Preview | None in app. `ANKIDROID_INTEGRATION.md` (answer-classification section, ~lines 1060–1071) documents filtered preview answers as **Ambiguous**, no retry. | Same answer path. | Backend preview states (`PreviewState`), no `reps` change. |
| Temporary deck | None | None | Filtered deck = persistent deck row named by the backend. |
| Cram | None (grep: no match in app code) | None | Backend cram kinds map to `CustomStudyRequest.Cram`. |
| Session card limit | `SessionTargetType.CARDS` 1–999, `MINUTES`, `FINISH_DUE`. Study-Agent-owned termination, not a backend filter. `review_limit_per_day` documented as "NOT an Anki deck-options override". | `schedule` query `limit` = queue fetch size, not a filtered-deck size. | Per-term `limit` in filtered search term. |
| Deck query / scheduler query | GATE 15 browser query (`AnkiCardBrowserCapabilities`) is **browsing**, not scheduling. | Provider `cards` query is browsing. | `deck:` / search syntax in filtered search term. |
| Original-deck ownership | `AnkiCardDetails.originalDeckRef`, `AnkiCardHydration` explains filtered-deck moves (GATE 07/16). | `Card.ORIGINAL_DECK_ID` exposed; `0` = not filtered. | `original_deck_id` (`odid`). |
| Card-level reviewer actions | GATE 13 bury/suspend via `ReviewerAction`. | `schedule` update with `BURY`/`SUSPEND`. | `BuryOrSuspendCards`. |

No existing Study-Agent code creates, rebuilds, empties, or reviews a custom-study object as a backend-native filtered deck.

### DISCOVERY-20-02 — Native filtered-deck support, per backend

**AnkiDroid (v2.24.1)** — no authoritative create/rebuild/empty API exposed to apps.

| Concept | Symbol / URI | Input | Result | Persistence | Scheduler interaction | Cleanup |
|---|---|---|---|---|---|---|
| Filtered deck creation | none on `FlashCardsContract`; app-internal `col.decks.newFiltered` (used by AnkiDroid's own provider test) | — | — | — | — | — |
| Deck list (read) | `content://<auth>/decks` | projection `deck_id, deck_name, deck_count, deck_dyn` | rows; `deck_dyn` = filtered flag | collection | none | none |
| Selected deck (read/write) | `content://<auth>/selected_deck` | update with `deck_id` | sets AnkiDroid current deck | config write (`Op::SetCurrentDeck`) | changes which deck `schedule` draws from | n/a |
| Queue read | `content://<auth>/schedule` with `selection="limit=?, deckID=?"` | `deckID` **optional** | one row per call (`limit`), `button_count` **hard-coded 4** | none | `deckID` **temporarily selects** the deck, then restores the previous selection (`CardContentProvider.query` SCHEDULE branch). `selectDeckWithCheck` accepts any id with `getLegacy(id) != null`, so a filtered deck id is accepted by source. | restore selection |
| Answer / bury / suspend | `schedule` **update** with `ReviewInfo.EASE` (`answer_ease`), `BURY` (`buried`), `SUSPEND` (`suspended`) | note id + ord | calls `col.sched.answerCard` / `buryCards` / `suspendCards`; `RuntimeException` is caught and logged (`answerCard`) | scheduler commit | backend | n/a |
| Card membership | `cards/#` update with `deck_id` | `deck_id` | `require(!col.decks.isFiltered(did))` — **cannot move a card into a filtered deck** | n/a | n/a | n/a |

**PC / Anki backend (rslib 25.09.2)** — authoritative concepts exist.

| Concept | Symbol (proto / rslib) | Input | Result | Persistence | Scheduler interaction | Cleanup |
|---|---|---|---|---|---|---|
| Custom study (preset kinds) | `SchedulerService.CustomStudy(CustomStudyRequest) → OpChanges`; `custom_study()` in `rslib/src/scheduler/filtered/custom_study.rs` | `deck_id` + `oneof value {new_limit_delta, review_limit_delta, forgot_days, review_ahead_days, preview_days, cram{kind, card_limit, tags_to_include, tags_to_exclude}}` | **`OpChanges` only — no deck id returned** | Creates or reuses one filtered deck (name below) | Filtered deck's own config (`reschedule`, order, limit) | Deck persists until emptied/rebuilt/removed |
| Custom study defaults | `CustomStudyDefaults(deck_id) → CustomStudyDefaultsResponse` | deck id | tags (with include/exclude memory), available counts | read-only | none | n/a |
| Filtered deck create/update | `DecksService.AddOrUpdateFilteredDeck(FilteredDeckForUpdate{id, name, config, allow_empty}) → OpChangesWithId` | `id=0` create; else update | new/updated deck id | persistent deck row | rebuilds immediately (`rebuild_filtered_deck_inner`) | deck removal |
| Get-or-create (no build) | `DecksService.GetOrCreateFilteredDeck(DeckId) → FilteredDeckForUpdate` | `0` = template | template (search terms pre-filled from selected deck name + `is:due`/`is:new`) | none until Add/Update | none | n/a |
| Rebuild | `SchedulerService.RebuildFilteredDeck(DeckId) → OpChangesWithCount` | deck id | number of cards moved in | cards mutated (`deck_id`, `original_deck_id`, `due`, `queue`) | returns all cards home **first**, then rebuilds from search | n/a |
| Empty | `SchedulerService.EmptyFilteredDeck(DeckId) → OpChanges` | deck id | all cards returned home (`return_all_cards_in_filtered_deck`) | deck row remains (empty) | none | self |
| Review queue | `SchedulerService.GetQueuedCards(fetch_limit, intraday_learning_only)` | limit | ordered queue entries with `SchedulingStates` | none | **backend queue**; filtered decks use default queue sort options (no sibling spacing) | n/a |
| Answer | `SchedulerService.AnswerCard(CardAnswer) → OpChanges` | card, current/new state, rating | revlog + card update | commit | backend | n/a |
| Remove deck | `remove_single_deck` (`DecksService.RemoveDecksAndChildDecks`) | deck id | filtered deck: `return_all_cards_in_filtered_deck` then removes deck row | deck removed (grave) | none | self |

### DISCOVERY-20-03 — Persistent vs ephemeral

| Object | Classification | Evidence |
|---|---|---|
| Anki filtered deck | **Persistent collection object** (a deck row with `DeckKind::Filtered`), not in-memory, not a session-only query | `rslib/src/scheduler/filtered/mod.rs` `add_or_update_filtered_deck` writes via `add_deck_inner` / `update_deck_inner`. |
| Anki custom-study deck | **Persistent normal-looking filtered deck with a reserved display name** `"Custom Study Session"` (localized, `ftl/core/custom-study.ftl`). Reused by name on the next custom study. | `create_custom_study_deck`. |
| Membership | Stored on each card (`deck_id` = filtered deck, `original_deck_id` = home). Persistent until returned. | `card.rs::move_into_filtered_deck`. |
| AnkiDroid view | Filtered decks are collection rows; AnkiDroid exposes only `deck_dyn` and `original_deck_id`. | `FlashCardsContract.Deck.DECK_DYN`, `Card.ORIGINAL_DECK_ID`. |
| Study-Agent session | Study-Agent's own state, no backend object. | Repo. |

### DISCOVERY-20-04 — Scheduler authority (who determines the next card)

| Mechanism | Observed answer | Evidence |
|---|---|---|
| Normal deck study (Anki) | **Anki scheduler** (queue builder) | `GetQueuedCards` / provider `schedule`. |
| Anki filtered deck review (PC backend) | **Anki scheduler** over the filtered deck's queue. The filtered deck holds the card set; ordering is backend-defined. | `queue/builder`, `GetQueuedCards`. |
| AnkiDroid existing filtered deck (via `deckID`) | **Anki scheduler** (same queue function), but reached by temporarily selecting the deck. | Provider `SCHEDULE` branch. Device verification **absent**. |
| Browser / card list | **Not a scheduler** — `cards` query returns rows, not the next card. | GATE 15 docs, provider `cards`. |
| Caller-provided list | **Not supported** by any backend API in this sandbox's sources. | none found. |
| Study-Agent choosing next card | **Not used by any mechanism above.** Study-Agent's `SessionTargetType` only decides when to stop, not which card comes next. | `StudyControlModels.kt`. |

### DISCOVERY-20-05 — Filter query semantics (backend)

Source: `custom_study.rs`, `filtered/mod.rs`, `sqlwriter.rs`.

- **Built-in exclusions** for every filtered build: `-is:suspended -is:buried -deck:filtered`. Suspended, buried and already-filtered cards are never pulled in.
- **Deck:** `deck:<name>` matches the deck **and its children** (regex `^name($|\x1f)`), and also cards whose **home** deck (`odid`) matches. `deck_id_with_children` also exists as a node.
- **Tag:** `tag:<name>` (Anki's own tag semantics, including hierarchical `a::b`). Cram include = OR of tags; exclude = AND of `-tag:`.
- **State:** `is:new`, `is:due` (cram DUE), `-is:new` (cram REVIEW), `is:new added:N` (preview), `rated:N:1` (forgot = rated "Again" in last N days), `prop:due<=N` (ahead).
- **Flags, note type, card template, text search, created/modified recently:** available as Anki search syntax inside `AddOrUpdateFilteredDeck` search terms. **Not exposed** through `CustomStudyRequest` (cram has no flag/notetype/template fields). Not claimed for AnkiDroid.
- Search terms are normalized via `normalize_search` and invalid searches are rejected. Max **2** search terms are used (`take(2)`).

### DISCOVERY-20-06 — Search language

- Backend accepts **Anki search syntax** through `FilteredSearchTerm.search` (normalized) and through `CustomStudyRequest` only as preset fields (deck id + tag list + kind).
- Deck **ids**: `CustomStudyRequest.deck_id` (source deck). Tag **names**: `tags_to_include` / `tags_to_exclude`. Card **ids**: not accepted by custom study.
- AnkiDroid: no search or id-list input for filtered construction.
- Per the gate, backend search syntax is **not** approved for common UI. Nothing here proposes it.

### DISCOVERY-20-07 — Card selection limit

| Mode | Backend semantics | Evidence |
|---|---|---|
| Max cards | Per search term `limit` (`Cram.card_limit`). Default `99_999` when absent (`custom_study_config`). | `custom_study.rs`. |
| All matching | Achieved with a large limit or cram `ALL` (limit = card_limit). | same. |
| Random N | `FilteredSearchOrder::Random` (`random()`) with `limit`. Used by cram REVIEW and ALL. | `storage/card/filtered.rs`. |
| Oldest N | `OldestReviewedFirst` exists in the enum; **not used** by any custom-study preset. | same. |
| Due-first N | `FilteredSearchOrder::Due`, used by cram DUE and ahead. | same. |
| Newest N | `FilteredSearchOrder::ReverseAdded`. Not used by presets; `Added` used by cram NEW and preview. | same. |
| Ordering owner | **Filter-owned** (SQL `ORDER BY` in the build query), then `fnvhash(c.id, c.mod)` tie-break, then `LIMIT`. Backend-defined, not caller-sorted. | `order_and_limit_for_search`. |

### DISCOVERY-20-08 — Ordering semantics

Backend `FilteredSearchOrder` (from `storage/card/filtered.rs`):
`OldestReviewedFirst` (max revlog id), `Random`, `IntervalsAscending` / `IntervalsDescending` (`ivl`), `Lapses` (`lapses desc`), `Added` (`n.id, c.ord`), `ReverseAdded`, `Due` (`due`, normalized to position), `RetrievabilityAscending` / `Descending` (FSRS, uses `fsrs` flag).

These orders decide **which cards enter** the filtered deck (build). The **review order inside** the deck is set by the queue builder and stored position (`due = position` on move); exact review order is **backend-owned and not reproduced here**. No local ordering is permitted by any contract in this document.

### DISCOVERY-20-09 — Rescheduling semantics

| Mode | Backend | Exact behavior | Evidence |
|---|---|---|---|
| Reschedule based on answers | Filtered deck `reschedule = true` (cram DUE/NEW/REVIEW, ahead) | Answers apply normal scheduling state (`apply_normal_study_state`), `reps += 1`, card remains in the filtered deck until emptied/rebuilt/removed. | `scheduler/answering/current.rs` (`filtered.reschedule` → `ReschedulingFilterState`); `answering/mod.rs` `apply_study_state` `FilteredState::Rescheduling`; `states/rescheduling_filter.rs` keeps Filtered next-states while `in_filtered_deck`. |
| Do not reschedule | Filtered deck `reschedule = false` (cram ALL, forgot, preview) | On move, card queue is set to `Review` (`move_into_filtered_deck`). Current state is `PreviewState` (`current.rs`, `reschedule == false`). Answers go through the preview path. | `filtered/card.rs`; `scheduler/answering/current.rs`; `preview.rs`. |
| Preview-only | Same as no-reschedule (`PreviewState`), with `preview_again_secs=60`, `preview_hard_secs=600`, `preview_good_secs=0` | Again/Hard → `PreviewRepeat` queue with short due. Good/Easy → `finished`, card returned home with restored queue. | `preview.rs` + preview unit test; `custom_study_config`. |
| Cram (kind-specific) | DUE/NEW/REVIEW = reschedule; ALL = no reschedule | Mapping from `cram_config`. | `custom_study.rs`. |

**"Cram" is not a separate scheduler.** It is a preset of the same filtered-deck machinery.

### DISCOVERY-20-10 — Review history semantics

| Effect | Rescheduling answer (backend) | Preview answer (backend) | Evidence |
|---|---|---|---|
| revlog entry | Yes; normal revlog kind (filtered-aware) | Yes; `RevlogReviewKind::Filtered`, `ease` as rated | `preview_filter.rs` (`RevlogReviewKind::Filtered`); `review.rs`. |
| reps increment | **Yes** (`self.card.reps += 1` in `apply_normal_study_state`) | **No** (preview path does not call `apply_normal_study_state`); the repo's AnkiDroid notes say the same (`ANKIDROID_INTEGRATION.md`, ~line 1060: "Filtered preview reviews may not change `reps` at all") | `answering/mod.rs`; `preview.rs`. |
| lapses | Normal scheduling rules apply (lapse on Again in review) | Not updated by preview path | `preview.rs` has no lapse update; scheduling-states code for normal review. |
| FSRS state | Normal FSRS path (memory state from `fsrs_next_states`) | Not applied by preview path (no normal state) | `answering/mod.rs`. Exact FSRS memory bookkeeping for preview: **not separately verified**. |
| due | Normal due for the card's next state (stored, deck-internal) | `due` set to a learning timestamp for Again/Hard; restored on finish | `preview.rs`; `remove_from_filtered_deck_restoring_queue`. |
| last review time | Updated | **Not updated** (`answering/mod.rs`: `last_review_time` is skipped when `current_state` is `Preview`) | `answering/mod.rs` ~L344–347. |
| interval | Normal next interval | Not changed by preview | `preview.rs` (`IntervalKind::InSecs` only). |

### DISCOVERY-20-11 — Rating semantics

- **Backend (rescheduling mode):** Again/Hard/Good/Easy are normal scheduler ratings (`Rating` in `answering`).
- **Backend (preview mode):** Again and Hard keep the card in preview (`PreviewRepeat`). Good **and** Easy finish the preview (`finished = true`, `scheduled_secs = 0` in the unit test). The **meaning differs** from normal review.
- **AnkiDroid:** provider always passes `CardAnswer.Rating.forNumber(ease - 1)` to `answerCard`. Filtered preview answers are classified **Ambiguous** by the repo's own commit classifier (`AnkiDroidCommitResultClassifier`; `ANKIDROID_INTEGRATION.md` §13). So GATE 11 rating commit semantics are **not sufficient** for preview without session context.

### DISCOVERY-20-12 — Available rating buttons

- **Backend:** `SchedulingStates` is per card and per state, with four rating fields (`again`, `hard`, `good`, `easy`). Preview states return different `next` states (finished vs repeat). A finished preview state has `scheduled_secs = 0` (unit test). The source does not reduce the set of rating fields for preview.
- **AnkiDroid:** `schedule` query hard-codes `buttonCount = 4` (`CardContentProvider.query`), and `nextIvlStr` supplies labels. It does **not** distinguish preview from normal review (source).
- Study-Agent must keep using backend-provided options (INV-ANKI-CARD-23 in repo).

### DISCOVERY-20-13 — Filtered session identity

| Backend | Identity | Stable? | Evidence |
|---|---|---|---|
| Anki | Filtered deck **id** (`DeckId`). The custom-study deck id is **not returned** by `CustomStudy`; it must be re-resolved by the reserved name. | Id is stable once it exists. Name is localized and reserved. | `custom_study.rs` (`create_custom_study_deck` uses `tr.custom_study_custom_study_session()`); `CustomStudy → OpChanges`. |
| Anki (non-preset) | `AddOrUpdateFilteredDeck` returns the id. | Yes | `OpChangesWithId`. |
| AnkiDroid | Deck id only (`deck_id` / `deck_dyn`). | Yes, for existing decks | `FlashCardsContract.Deck`. |
| Session id / query id | **None** in any source. | — | No such symbol found. |

### DISCOVERY-20-14 — Source deck preservation

- Cards keep **their home deck** in `original_deck_id` (`odid`) while in a filtered deck; `deck_id` = filtered deck. The card does not move in the normal-deck sense. `original_due` keeps the pre-move due.
- AnkiDroid exposes `ORIGINAL_DECK_ID` and `RAW_ORIGINAL_DUE` (`0` when not filtered).
- Study-Agent's `AnkiCardDetails.originalDeckRef` already maps this. Card Details and recovery must therefore use `original_deck_id`, not `deck_id`.

### DISCOVERY-20-15 — Session end

| Trigger | Backend result | Evidence |
|---|---|---|
| No matching cards at creation | `CustomStudyError::NoMatchingCards` — creation fails. | `custom_study.rs` `create_custom_study_deck`. |
| Filtered deck empty (no more due cards) | `GetQueuedCards` returns no entries. Deck remains a deck. | Queue builder; `EmptyFilteredDeck` keeps the row. |
| Card limit consumed | Not a session end in the backend. The limit only bounds **initial membership**. Queue continues until queue is empty. | `order_and_limit_for_search`: limit is applied at build time. |
| Backend session closed | **No backend "close" call exists for custom study.** The closest are `EmptyFilteredDeck` and deck removal. | proto. |

Backend completion is therefore "queue empty", not a session-close event.

### DISCOVERY-20-16 — Rebuild / refresh / refill

- `RebuildFilteredDeck(did)`: **returns all cards home first**, then rebuilds from the stored search with the stored `limit` and `order`. Counts as an Anki operation (`Op::RebuildFilteredDeck`). Cards in the old membership that no longer match **leave** the deck; new matches **enter**.
- `EmptyFilteredDeck(did)`: returns all cards home; deck stays; no refill.
- Refill = rebuild. There is no separate refill API.
- Side effects: `return_cards_to_home_deck` marks cards modified (`update_card_inner`), restores queue from card type, restores `due` from `original_due`. Restoring a suspended or buried queue is **skipped** (`queue < 0`), so a card suspended inside the deck stays suspended after return (see DISCOVERY-20-26).

### DISCOVERY-20-17 — Delete / end filtered session

- **Empty:** cards return home; scheduling state of the review is retained (the stored card state is updated by answers, and restore keeps `due` only when `original_due != 0`).
- **Delete deck (remove_single_deck):** filtered → cards return home, then the deck row is removed (grave). Review history (`revlog`) is **not deleted** by these paths (no revlog delete in the code paths read).
- **Reset to normal scheduling:** not an operation Anki offers for filtered cards; the card re-enters its home deck's scheduling with its last committed scheduling state.

### DISCOVERY-20-18 — Crash / restart

| Event | Backend persistence | Evidence / status |
|---|---|---|
| Study-Agent process death | Filtered deck and card membership are collection rows → **persist**. Study-Agent holds no backend state for it. | Source. Device **UNKNOWN**. |
| AnkiDroid restart | Collection rows persist (source). `selected_deck` is a config write, so the current-deck selection persists too. | Source. Device **UNKNOWN**. |
| PC agent restart | Same as above, if Anki stays open; Anki commits per op (`transact` commit). | `transact` commits on success and rolls back on error (`transact.rs`). Restart behavior of the external PC agent: **UNKNOWN** (not in repo). |
| Anki restart | Collection persists. | Source. Device **UNKNOWN**. |

### DISCOVERY-20-19 — Cross-client visibility

- Filtered decks are **collection-level rows**, so they are visible to any client that reads the collection: desktop Anki (deck list), AnkiDroid (`deck_dyn`), and AnkiConnect (`deckNamesAndIds`, `findCards` on `deck:` search).
- **Existing exposure:** AnkiDroid's deck list shows filtered decks (`DeckRow` renders "Filtered"), and `AnkiDroidBackend` review sessions accept any `deckRef`. No guard against filtered decks in review selection was found by grep. **This is an existing behavior to verify on device.**
- Filtered-session ownership is **not** local to Study-Agent.

### DISCOVERY-20-20 — Sync

- Filtered decks and card membership are stored in the normal collection tables. No filtered-specific exclusion was found in `rslib/src/sync` (grep for `filtered` in the sync module returned nothing).
- **Classification: UNKNOWN for multi-device behavior** (no device or two-client run). Partial by source: deck and card rows are collection data.

### DISCOVERY-20-21 — Mutation safety

| Operation | Classification | Reason |
|---|---|---|
| Read-only queries (`CustomStudyDefaults`, `deck list`, `cards`, `GetQueuedCards`) | `READ_ONLY_SESSION` | No writes (`GetQueuedCards` builds in-memory queues). |
| `CustomStudy` / `AddOrUpdateFilteredDeck` / `RebuildFilteredDeck` | `PERSISTENT_COLLECTION_MUTATION` | Creates or updates a deck row, moves cards (`deck_id`, `odid`, `due`, `queue`), commits with usn/mod. |
| `EmptyFilteredDeck` / deck removal | `PERSISTENT_COLLECTION_MUTATION` | Returns cards home; deck removed (remove path). |
| AnkiDroid `selected_deck` write and `deckID` selection | `REVERSIBLE_COLLECTION_MUTATION` | Changes current deck config; restored after the query. |
| AnkiDroid answer / bury / suspend | `PERSISTENT_COLLECTION_MUTATION` | Already covered by GATE 11/13. |

Because creation is `PERSISTENT_COLLECTION_MUTATION`, transaction and recovery machinery is required (see DECISION-20-11).

### DISCOVERY-20-22 — Session creation atomicity

- **Backend:** `custom_study` runs inside `transact(Op::CreateCustomStudy, …)`. On error, `transact_inner` calls `rollback_trx` / `rollback_rust_trx` (`rslib/src/collection/transact.rs`). Create + assign query + set limit + set order + move cards happen in **one** Rust transaction. A failed build is expected to leave no partial deck (`transact` rolls back on error). The unit test confirms only that tag memory is unchanged after `NoMatchingCards`; no deck-absence test was found.
- **Study-Agent side:** a single UI "Start" is one backend call, not several. Atomicity is therefore backend-evidenced for **one** call. Anki desktop's own transaction boundary is `transact`, and the commit semantics for an ambiguous return (a response lost after commit) are **not** proven here.
- **AnkiDroid:** no creation call exists, so atomicity is N/A.

### DISCOVERY-20-23 — Duplicate session creation

- **`CustomStudy` (presets):** a single reserved deck `"Custom Study Session"`. If a filtered deck with that name exists it is **reused and rebuilt** with the new config; if a **normal** deck has that name → `CustomStudyError::ExistingDeck`. So the second creation **replaces** the first session's card set (first session's cards are returned home). It does not create two decks. **Replace, not duplicate.**
- **`AddOrUpdateFilteredDeck` with `id = 0`:** creates a new deck. The default name is `"Filtered Deck <time>"`. The name is made unique by `+` suffix (`ensure_deck_name_unique`). Two calls create **two** decks.
- **Error:** duplicate names produce a suffix, not an error. `NoMatchingCards` is an error when the build is empty (unless `allow_empty`).

### DISCOVERY-20-24 — Session naming

- Preset custom study: name is **reserved and localized** (`custom-study-custom-study-session`); identity by name is therefore language-dependent. Not usable as a transaction identity.
- Non-preset: name is user-supplied (`NativeDeckName::from_human_name`). Uniqueness: `+` suffix on collision (`decks/name.rs`). Renames: `update_deck_inner` renames children when the name changes (`rename_child_decks`).
- Reserved names: `"Default"` (deck id 1) is special in `remove_single_deck`. No other reserved list was found.

### DISCOVERY-20-25 — Empty filter result

- **Preset:** `NoMatchingCards` error. **Creation failure**, not an empty session. The unit test asserts the tag memory is unchanged after the failure; no-partial-deck follows from `transact` rollback but was **not** tested separately.
- **`AddOrUpdateFilteredDeck` with `allow_empty = true`:** valid empty filtered deck.
- **`RebuildFilteredDeck` with zero matches:** returns count `0` without error (no `allow_empty` check on that path).

### DISCOVERY-20-26 — Unsupported card states

| State | Build inclusion | Evidence |
|---|---|---|
| Suspended | **Excluded** by the built-in search | `-is:suspended` |
| Buried (user or scheduler) | **Excluded** | `-is:buried` |
| Learning / relearning | **Included** (the build search has no state exclusion for them) | `move_into_filtered_deck` sets queue to Review when `reschedule=false`. |
| Already in a filtered deck | **Excluded** | `-deck:filtered` (`odid != 0`). |
| Suspended/buried **while inside** the deck | Stays suspended/buried after return (`queue < 0` is not restored) | `remove_from_filtered_deck_restoring_queue`. |

### DISCOVERY-20-27 — Existing filtered cards

- A card already in filtered deck A is **excluded** from a new build (`-deck:filtered`). It cannot be double-included.
- Rebuilding the **same** deck first returns its cards (`return_all_cards_in_filtered_deck`), so they can re-enter.
- Study-Agent has no way to add a card to a second filtered deck through public APIs (AnkiDroid cannot move cards into filtered decks; `cards/#` update rejects them).

### DISCOVERY-20-28 — Nested deck scope

- `deck:X` = X **and** children (name prefix with `\x1f` separator, regex-based, not plain string prefix). Matches on current deck **or** home deck (`odid`).
- `deck_id_with_children` gives an id-based children set for node searches.
- Backend does not offer an "X only, no children" switch for presets. A search can express it, but that is not a preset, and no source in this sandbox confirms a supported "exclude children" path for custom study.

### DISCOVERY-20-29 — Tag semantics

- **Presets:** include = OR (`(tag:a OR tag:b)`); exclude = AND of negations (`-tag:c -tag:d`). Stored per deck (`CustomStudyIncludeTags`/`ExcludeTags`).
- **Hierarchical:** `tag:a` matches `a::*` in Anki's tag search (backend standard). Not separately verified here.
- **Wildcards:** Anki search wildcards are escaped for names (`escape_anki_wildcards_for_search_node`); not offered as a Study-Agent feature.

### DISCOVERY-20-30 — Preview-only study

- **Supported** by backend (`reschedule = false` presets: ALL, FORGOT, PREVIEW; and `preview_days`).
- **Rating behavior:** Again/Hard repeat in preview; Good/Easy finish (DISCOVERY-20-11).
- **History:** revlog written with kind Filtered; `reps` not incremented; lapses not updated (DISCOVERY-20-10).
- **Available buttons:** same 4-button structure; labels from preview states.
- **Session end:** card returned home when finished; deck remains until empty/rebuild.
- **AnkiDroid:** preview answers classified **Ambiguous** by this repo (`ANKIDROID_INTEGRATION.md`); device behavior **UNKNOWN**.

### DISCOVERY-20-31 — Browse vs review

- **GATE 15 browser query** (`cards` provider URI, `AnkiCardBrowserCapabilities`) returns rows. It never advances the scheduler, never orders a review queue, and never answers cards.
- **Filtered scheduler review** (backend `GetQueuedCards` / provider `schedule`) is a different domain. A browser result is **not** a review queue.
- Backend difference: browsing is `find_cards`-style search; review is `GetQueuedCards` with `SchedulingStates`.

### DISCOVERY-20-32 — Normal scheduler interaction

- A card in a filtered deck has `deck_id` = filtered deck, so **normal deck study** of its home deck no longer lists it (it is no longer in the home deck; `deck:` search matches via `odid`, so deck-scoped **browse** still finds it).
- A card can be in only one filtered deck at a time (DISCOVERY-20-27).
- **Other device:** state is in the collection; a second client that reads the same collection sees the same membership. Concurrent answering from two clients: **UNKNOWN** (no two-client run).

### DISCOVERY-20-33 — Session restoration data

- Backend-side minimum identity: **filtered deck id** (`DeckId`). That is enough to reload deck config, membership (`cards where did = ?`), and queue (`GetQueuedCards`).
- Presets do not return the id. The reserved name must be re-resolved, which is language-dependent. So the safe restoration key is the **id obtained at creation**, which the CustomStudy response does **not** provide → restoration after a preset creation needs `GetDeckIdByName` on the localized name, and this is **ambiguous**.
- No query id or session id exists.

### DISCOVERY-20-34 — Backend failure during review

| External change | Possible? | Evidence |
|---|---|---|
| Deleted externally | Yes (`RemoveDecksAndChildDecks`, AnkiConnect `deleteDecks`) | proto; AnkiConnect `deleteDecks`. |
| Rebuilt externally | Yes (`RebuildFilteredDeck`, AnkiDroid app-internal, AnkiConnect not exposed) | proto; `StudyOptionsFragment`. |
| Renamed | Yes (`UpdateDeck`); name must still be unique | `update_deck_inner`. |
| Emptied | Yes (`EmptyFilteredDeck`, AnkiDroid app-internal, AnkiConnect `changeDeck` calls `remFromDyn`, which returns cards) | AnkiConnect `changeDeck`. |
| Query changed | Yes (`AddOrUpdateFilteredDeck` on the same id) | proto. |

These are all **possible from other clients** (desktop, AnkiDroid app, AnkiConnect). No event notification from Anki to Study-Agent was found.

### DISCOVERY-20-35 — AnkiDroid semantics (factual)

- **Creation:** none via public API. App-internal: `CustomStudyDialog`, `rebuildFilteredDeck`, `emptyFilteredDeck`.
- **Filter query:** none via public API.
- **Ordering:** queue order from `getQueuedCards`; no filter-level order control exposed.
- **Rescheduling:** not exposed (no `reschedule` field in `Deck` contract). Answers via `schedule` update call `answerCard`.
- **Ratings:** `button_count` hard-coded to 4; labels from `nextIvlStr`.
- **Review history:** written by the backend answer path (same as normal review); preview answers are Ambiguous in this repo; device **UNKNOWN**.
- **Identity:** `deck_id` for existing filtered decks; no session id.
- **Persistence:** collection rows.
- **Cleanup:** none via public API (`emptyFilteredDeck` is app-internal).
- **Rebuild:** none via public API.
- **Recovery:** repo's commit classifier treats filtered preview as Ambiguous; no session-level recovery exists.

### DISCOVERY-20-36 — PC backend semantics (factual, backend-only)

- **Creation:** `CustomStudy` (presets), `AddOrUpdateFilteredDeck` (raw), `GetOrCreateFilteredDeck` (template). All in-process backend calls.
- **Filter query:** cram kinds + tags + `deck_id`; raw Anki search (≤2 terms) for `AddOrUpdateFilteredDeck`.
- **Ordering:** `FilteredSearchOrder` per term (listed in DISCOVERY-20-08).
- **Rescheduling:** `reschedule` bool per filtered deck (true for DUE/NEW/REVIEW/ahead; false for ALL/forgot/preview).
- **Ratings:** normal states for rescheduling; Again/Hard repeat and Good/Easy finish for preview.
- **Review history:** revlog written in both modes; `reps` increments only in rescheduling mode.
- **Identity:** deck id; preset creation does not return it.
- **Persistence:** collection rows.
- **Cleanup:** `EmptyFilteredDeck`, deck removal.
- **Rebuild:** `RebuildFilteredDeck` (returns all cards home, then rebuilds).
- **Recovery:** backend re-query (`GetQueuedCards`, `GetDeckIdByName`). No session object.
- **Route to the PC agent:** AnkiConnect has no custom study, filtered create, rebuild or empty endpoint. Reaching these APIs needs an in-process Anki backend or a different plugin. **Not in repo; UNKNOWN for the PC agent.**

### DISCOVERY-20-37 — Contract matrix

Legend: **SUPPORTED** = evidence in source; **PARTIAL** = evidence for a subset or with a gap; **UNSUPPORTED** = no such API / behavior in source; **UNKNOWN** = reason given.

| Property | AnkiDroid (v2.24.1) | PC backend (Anki 25.09.2) |
|---|---|---|
| Native custom study | **UNSUPPORTED** — no provider URI for CustomStudy; app-internal `CustomStudyDialog` only (`FlashCardsContract.kt`, `CardContentProvider.kt`). | **PARTIAL** — `SchedulerService.CustomStudy` exists (`scheduler.proto`). Not reachable from AnkiConnect; PC agent route **UNKNOWN**. |
| Native filtered deck | **PARTIAL** — existing filtered decks visible (`deck_dyn`); creation **UNSUPPORTED**. | **PARTIAL** — `AddOrUpdateFilteredDeck` / `GetOrCreateFilteredDeck` exist (`decks.proto`); PC route **UNKNOWN**. |
| Structured filter | **UNSUPPORTED** — no filter input. | **PARTIAL** — cram kind + tags + limit; no structured flag/notetype/template filter. |
| Native search query | **UNSUPPORTED** — no filtered build. | **SUPPORTED** (backend) — `FilteredSearchTerm.search`, ≤2 terms, normalized. Not exposed to UI. |
| Card limit | **UNSUPPORTED** for filtered build. `schedule` `limit` is a queue fetch size only. | **SUPPORTED** — `Cram.card_limit` / `FilteredSearchTerm.limit`. |
| Ordering modes | **UNSUPPORTED** for build. Review order is backend queue. | **SUPPORTED** — `FilteredSearchOrder` (10 values); presets use Due/Added/Random. |
| Rescheduling mode | **UNKNOWN** — no `reschedule` field in provider; behavior depends on the deck's flag, which is not readable. | **SUPPORTED** — `FilteredDeck.reschedule` (true/false). |
| Preview-only mode | **UNKNOWN** — preview answers are Ambiguous per repo; no device run. | **SUPPORTED** — `reschedule=false` presets + preview delays. |
| Review history effects | **PARTIAL** — same answer path as normal review (source); device **UNKNOWN**. | **SUPPORTED** — revlog written; reps only in rescheduling. |
| FSRS effects | **PARTIAL** — FSRS state comes from the backend answer path; device **UNKNOWN**. | **PARTIAL** — rescheduling applies normal FSRS path (`apply_normal_study_state`); preview FSRS bookkeeping **not separately verified**. |
| Available rating buttons | **PARTIAL** — always 4 (hard-coded), not mode-aware. | **SUPPORTED** — `SchedulingStates` per card; preview states differ. |
| Next-interval information | **PARTIAL** — `nextIvlStr` for four buttons. | **SUPPORTED** — states + `DescribeNextStates`. |
| Stable filtered identity | **PARTIAL** — deck id for existing decks; no session id. | **PARTIAL** — deck id stable after creation; preset creation does not return it; reserved localized name. |
| Original deck retained | **SUPPORTED** — `Card.ORIGINAL_DECK_ID`. | **SUPPORTED** — `original_deck_id`. |
| Session survives restart | **PARTIAL** — collection rows persist (source); device **UNKNOWN**. | **PARTIAL** — collection rows persist; PC agent restart behavior **UNKNOWN** (not in repo). |
| Rebuild supported | **UNSUPPORTED** via provider; app-internal exists. | **SUPPORTED** — `RebuildFilteredDeck`. |
| End / delete supported | **UNSUPPORTED** via provider; app-internal `emptyFilteredDeck`. | **SUPPORTED** — `EmptyFilteredDeck`, deck removal. No "close session" API. |
| Duplicate session behavior | **UNSUPPORTED** (no creation). | **SUPPORTED** (defined) — presets **replace** the reserved deck; raw create with id 0 **adds `+` suffix** (two decks). |
| Cross-client visible | **PARTIAL** — collection rows; `deck_dyn` visible; device **UNKNOWN**. | **PARTIAL** — collection rows visible to desktop deck list; desktop run **UNKNOWN**. |
| Sync behavior | **UNKNOWN** — no device/two-client run; no filtered-specific sync exclusion found in backend sync module. | **UNKNOWN** — same reason; backend sync source not exhaustively read. |
| Authoritative next card | **SUPPORTED** — backend queue via provider `schedule`. | **SUPPORTED** — `GetQueuedCards` (backend). PC agent use **UNKNOWN**. |
| Recovery possible | **PARTIAL** — commit classifier + ambiguous preview; no session identity. | **PARTIAL** — backend re-query possible; no session identity; preset id not returned. |

---

## PHASE B — CONTRACT DECISION & LOCK

### DECISION-20-01 — Supported custom-study modes

**Proposed (not locked):**

| Mode (proposed) | Backend proof | Status |
|---|---|---|
| `BACKEND_FILTERED_REVIEW` (reschedule = true) | Cram DUE/NEW/REVIEW, ahead; `reschedule = true` | **PC backend only**, route **UNKNOWN**. |
| `BACKEND_PREVIEW` (reschedule = false) | Cram ALL, forgot, preview; preview states | **PC backend only**, route **UNKNOWN**. Preview behavior on AnkiDroid **UNKNOWN**. |
| `DECK_SCOPED_NORMAL_REVIEW` | Already exists (normal deck study) | Not new; not part of GATE 20. |

No generic `CUSTOM` mode is proposed.

### DECISION-20-02 — Scheduler authority (proposed)

| Mode | Scheduler authority | Next-card authority | Rating authority |
|---|---|---|---|
| Normal deck study (existing) | Anki scheduler | Anki queue | GATE 11 → backend `answerCard` |
| `BACKEND_FILTERED_REVIEW` (PC backend) | Anki scheduler (filtered deck queue) | Anki `GetQueuedCards` | GATE 11 → backend `answerCard` |
| `BACKEND_PREVIEW` (PC backend) | Anki scheduler (preview states) | Anki `GetQueuedCards` | GATE 11 → backend `answerCard` |
| AnkiDroid existing filtered deck (`deckID`) | Anki scheduler (via provider) | Anki queue via `schedule` | GATE 11 → `schedule` update |

**Exactly one** scheduler authority per row. **Study-Agent does not choose a card in any row.** `SessionTargetType` only decides when to stop.

### DECISION-20-03 — Filter ownership (proposed)

- Study-Agent provides **structured intent only** (cram kind, deck scope, tags, limit). The backend translates it to native search. Study-Agent never passes raw Anki search to the UI.
- Structured fields that the backend cannot reproduce (flags, note types, templates, text search, rated-recently, created-recently for review) are **not** part of the proposed vocabulary.

### DECISION-20-04 — Filter vocabulary (proposed)

- Deck scope: source deck id; include children (backend `deck:` semantics).
- Tags: include (OR), exclude (AND-NOT). Backend-defined.
- Card states: NEW / DUE / REVIEW (cram kinds). **ALL** as preview kind.
- Limit: `card_limit` (initial membership cap).
- **Not** in vocabulary: flags, suspended/buried selection, note type, template, text search, deck-exclude-children (no backend preset).

### DECISION-20-05 — Ordering (proposed)

- Allowed values only where the backend preset defines them: **DUE** (cram DUE), **ADDED** (cram NEW / preview), **RANDOM** (cram REVIEW / ALL).
- **BACKEND_DEFAULT** is not a separate value; it is not needed when the preset fixes the order.
- No local sort is permitted.

### DECISION-20-06 — Rescheduling modes (proposed)

- `NORMAL_RESCHEDULING` = filtered deck `reschedule = true` (cram DUE/NEW/REVIEW).
- `NO_RESCHEDULING` = filtered deck `reschedule = false` (cram ALL, preview, forgot). Preview states apply.
- `BACKEND_DEFINED` — not proposed. The mode must be explicit at setup.

### DECISION-20-07 — Review history semantics (proposed, per mode)

| Mode | revlog written | reps | lapses | FSRS | due | Source |
|---|---|---|---|---|---|---|
| `NORMAL_RESCHEDULING` (PC) | yes | +1 | normal | normal | normal | DISCOVERY-20-10 |
| `NO_RESCHEDULING` / preview (PC) | yes (kind Filtered) | no | no | not applied on preview path (see note) | preview learning timestamp, restored on finish | DISCOVERY-20-10 |
| AnkiDroid (any filtered) | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | no device run |

**UX note:** the preview row must be shown to the user before start (DECISION-20-22).

### DECISION-20-08 — Session identity (proposed)

- Backend identity = **filtered deck id** (`DeckId`), obtained from `AddOrUpdateFilteredDeck`. Preset `CustomStudy` does **not** return the id.
- **Blocker:** the preset path returns no id and identity by reserved name is localized. Until the contract picks one path (raw create with returned id) or proves the preset identity, the identity is not locked.
- Display name is never identity (DECISION-20-08 wording).

### DECISION-20-09 — StudySession vs backend identity

- `StudySessionId ≠ BackendFilteredDeckId`. Keep them separate (no backend type is proposed here).

### DECISION-20-10 — Session lifecycle

Anki has **no custom-study session lifecycle**: a filtered deck is a persistent deck with membership and a config. Only `CREATED` (deck exists with membership) and `REMOVED/EMPTIED` (deck gone or empty) are real backend states. `ACTIVE`, `COMPLETED`, `ENDED`, `INVALIDATED` are **not** backend-proven as persistent states. Do not add lifecycle machinery for symmetry.

### DECISION-20-11 — Durable mutation tracking

Creation is `PERSISTENT_COLLECTION_MUTATION` and an ambiguous outcome (response lost after commit) cannot be excluded from source alone. **A durable creation ledger is indicated** for any implementation that creates a backend filtered deck; the ledger design is not part of this lock. Its design is **not** part of this lock, because the creation route is not proven for the PC agent.

### DECISION-20-12 — Creation retry policy (proposed)

- Preset `CustomStudy`: re-running with the same request **replaces** the reserved deck. Replay is therefore **not a duplicate**, but it **destroys** the prior session's membership. Classification: **RETRY_ONLY_AFTER_PROVEN_NON_CREATION** (no blind retry).
- Raw `AddOrUpdateFilteredDeck` with id 0: **NO_BLIND_RETRY** (a retry creates a second deck).
- `RebuildFilteredDeck`: **IDEMPOTENT_REPLAY_SUPPORTED** in outcome, but it changes the card set and is **NO_BLIND_RETRY** for an active session.

### DECISION-20-13 — Rebuild policy (proposed)

- **No rebuild while a session is active** (default; avoids unexpected composition changes).
- Manual rebuild only from an explicit user action that states that cards will be returned home and re-selected.
- No automatic rebuild.

### DECISION-20-14 — End / cleanup policy (proposed)

- Backend cleanup = `EmptyFilteredDeck` (cards home, deck kept) or deck removal (cards home, deck gone).
- "End session" must not discard Study-Agent state without the backend cleanup, or else the cards stay in the filtered deck.
- The exact user-facing choice (empty only vs remove deck) is **not decided**, so this is **BLOCKED** on a product decision.

### DECISION-20-15 — External change handling (proposed)

- If identity is stable (deck id still exists, still filtered, same `reschedule` flag, same membership source): **continue**.
- Otherwise: **invalidate** the Study-Agent session and surface it. Never fall back to normal review.
- Detection requires a backend read on each `GetQueuedCards` path. No notification exists.

### DECISION-20-16 — Rating pipeline

- GATE 11 remains the only rating transaction pipeline. The rating request must carry **mode context** (preview vs rescheduling) so the commit classifier can treat preview as Ambiguous until proven. No second pipeline is proposed.

### DECISION-20-17 — ReviewTurn identity

- A filtered card keeps a normal `ReviewTurnId`. No separate identity family.

### DECISION-20-18 — Next-card rule

- Only the backend queue (`GetQueuedCards` or provider `schedule`) produces the next review card. GATE 15 browse results are never a queue.

### DECISION-20-19 — Card limit meaning

- Limit = **maximum initial matching cards** for the filtered deck (backend `limit` per term). It is **not** a maximum number of reviews. Queue continues until the deck queue is empty. Study-Agent's own `SessionTargetType.CARDS` remains a separate, Study-Agent-owned stop condition.

### DECISION-20-20 — Empty session meaning

- Valid empty session = filtered deck with `allow_empty` (AddOrUpdate) **or** an existing filtered deck with no queued cards. Creation failure = `NoMatchingCards` (preset) or `SearchReturnedNoCards` (raw). These must be presented differently.

### DECISION-20-21 — Capability model (proposed, only where justified)

`CUSTOM_STUDY`, `FILTERED_REVIEW`, `FILTER_BY_DECK`, `FILTER_BY_TAG`, `FILTER_BY_STATE`, `FILTER_LIMIT`, `FILTER_ORDER`, `FILTER_NO_RESCHEDULE`, `FILTER_REBUILD`, `FILTER_END`, `PREVIEW_ONLY_STUDY`.

- **Excluded:** `FILTER_BY_FLAG` (not in preset API), `FILTER_ORDER` as general (only preset orders), `FILTER_BY_CARD_ID` (no backend support).
- **Each capability is `false` for AnkiDroid** until a creation route exists.

### DECISION-20-22 — UX safety disclosure (proposed)

Before start, the UI must state:
- **Reviews WILL affect scheduling** (`NORMAL_RESCHEDULING`).
- **Reviews WILL NOT reschedule cards** (`NO_RESCHEDULING`/preview), and `reps` will not change.
- The source deck and the reserved deck name, and that a preset **replaces** an existing custom-study deck.

### DECISION-20-23 — Lock result

See the Contract Lock Report below.

---

## CONTRACT LOCK REPORT

```
GATE 20 — CUSTOM STUDY CONTRACT LOCK

RESULT: BLOCKED

SUPPORTED MODES:
- PROPOSED ONLY (not locked): BACKEND_FILTERED_REVIEW (reschedule=true), BACKEND_PREVIEW (reschedule=false).
  Backend-proven in Anki 25.09.2 source. PC agent route UNKNOWN. AnkiDroid creation UNSUPPORTED.
- Not new: DECK_SCOPED_NORMAL_REVIEW (existing normal study).

SCHEDULER AUTHORITY:
- Every proposed mode resolves to one authority: Anki scheduler (filtered-deck queue / GetQueuedCards).
- Study-Agent chooses no card in any mode. Unambiguous at backend level.
- NOT UNAMBIGUOUS for the product: PC agent wiring is outside this repo; AnkiDroid existing-filtered-deck review
  uses a temporary deck selection (`deckID`) whose effect on the user's current deck is verified only by source.

FILTER VOCABULARY:
- Proposed: deck scope (incl. children), tags (include OR / exclude AND-NOT), states NEW/DUE/REVIEW/ALL, limit.
- Excluded: flags, note type, template, text, rated/created recently, card-id lists.

ORDERING:
- Proposed: DUE, ADDED, RANDOM (preset-defined only). No local ordering.

RESCHEDULING:
- Proposed: NORMAL_RESCHEDULING (reschedule=true), NO_RESCHEDULING (reschedule=false, preview).
- AnkiDroid: UNKNOWN (no reschedule flag exposed).

REVIEW HISTORY EFFECTS:
- PC rescheduling: revlog yes, reps +1, normal FSRS and due.
- PC preview: revlog yes (kind Filtered), reps no, lapses no; FSRS preview bookkeeping not separately verified.
- AnkiDroid: UNKNOWN (no device run).

SESSION IDENTITY:
- Candidate: filtered deck id. BLOCKED: preset CustomStudy returns no id; identity by reserved localized name is ambiguous.

SESSION LIFECYCLE:
- Only backend-real states: deck exists with membership; emptied; removed. No ACTIVE/COMPLETED/ENDED/INVALIDATED
  persisted states are backend-proven. Completion = backend queue empty.

CREATE/RETRY POLICY:
- Preset CustomStudy: replace-by-name; RETRY_ONLY_AFTER_PROVEN_NON_CREATION (no blind retry).
- Raw AddOrUpdateFilteredDeck(id=0): NO_BLIND_RETRY (creates second deck).
- Creation is PERSISTENT_COLLECTION_MUTATION -> durable creation ledger indicated (not designed here).

REBUILD POLICY:
- No rebuild while a session is active. Manual only, with explicit disclosure. No automatic rebuild.

END/CLEANUP POLICY:
- Backend cleanup = EmptyFilteredDeck or deck removal. Which one the user gets is a PRODUCT DECISION: BLOCKED.

RATING PIPELINE:
- GATE 11 only. Requires mode context. Preview answers Ambiguous until proven on device.

NEXT CARD AUTHORITY:
- Backend queue only (GetQueuedCards / provider schedule). Browser results never a queue.

CAPABILITIES:
- Candidate set in DECISION-20-21. All false for AnkiDroid until a creation route exists.

UNSUPPORTED:
- AnkiDroid: custom study creation, filtered deck creation, rebuild, empty (provider), reschedule flag read, session id.
- PC (AnkiConnect route): custom study, filtered create/rebuild/empty, queue read.
- Any backend: flag/notetype/template/text filters in custom study presets; "exclude children" preset; session id; session close.

RESIDUAL RISKS:
- Preset creation REPLACES the reserved "Custom Study Session" deck (silently discards a previous session's membership).
- Localized reserved name makes identity fragile.
- AnkiDroid existing filtered decks are reviewable through deck selection with no guard found (verify on device).
- Filtered-preview answers are Ambiguous in the commit pipeline (no retry, no advance).
- Restart, sync, cross-client, and external mutation are UNKNOWN on device.

IMPLEMENTATION AUTHORIZED:
NO
```

### Why the lock is BLOCKED (exact blockers)

| # | Blocker | Gate condition it fails |
|---|---|---|
| B1 | **AnkiDroid cannot create, rebuild or empty a filtered deck through any public API** (`FlashCardsContract`, `CardContentProvider`). No AnkiDroid-native custom-study mode can be locked. | SCHEDULER AUTHORITY UNAMBIGUOUS (for AnkiDroid), CAPABILITIES |
| B2 | **The PC agent is not in this repository.** Its route to Anki's custom-study RPCs is unknown. AnkiConnect exposes none of them. | CONTRACT LOCKED (PC) |
| B3 | **Session identity is undefined for presets** (no returned id; reserved localized name). | SESSION IDENTITY, DECISION-20-08 |
| B4 | **Creation mutates the collection persistently** and needs a durable ledger design that depends on B2/B3. | DECISION-20-11, DECISION-20-12 |
| B5 | **End/cleanup policy is a product decision** (empty vs remove deck). | DECISION-20-14 |
| B6 | **No device evidence** for AnkiDroid preview, rescheduling flag, restart, sync or cross-client behavior. The repo's own GATE 11 note requires a disposable-collection run. | FSRS / history / sync rows in DISCOVERY-20-37 |

### What would unblock a re-lock (not started)

1. Decide the PC route (in-process Anki backend, or a plugin exposing `CustomStudy` / `AddOrUpdateFilteredDeck` / `GetQueuedCards`) and verify it in the PC agent's own repository.
2. Decide whether AnkiDroid is in scope for **review of existing filtered decks** only, with a device test of `deckID` selection side effects. Creation stays out of scope on AnkiDroid.
3. Pick the identity path: raw `AddOrUpdateFilteredDeck` (returns id) rather than preset `CustomStudy` (returns none), or prove the preset identity.
4. Product decision on end/cleanup and on the "replace" behavior of the reserved deck.
5. Run the device / disposable-collection tests listed in DISCOVERY-20-18 to 20-34 on AnkiDroid v2.24.1 and on Anki 25.09.2.

---

## PHASE C — IMPLEMENTATION GUIDANCE

**Not started.** Implementation is not authorized (`IMPLEMENTATION AUTHORIZED: NO`). No file checkpoints (01–20) were executed. No code was changed.

---

## PART II — ARCHITECTURE AUDIT

Not applicable before implementation. Recorded as **BLOCKED** (nothing to audit).

| Audit | Result | Note |
|---|---|---|
| AUDIT-20-01 Contract conformance | BLOCKED | No locked decisions to trace. |
| AUDIT-20-02 Single scheduler authority | BLOCKED | No session exists. Proposed table in DECISION-20-02 has one authority per row. |
| AUDIT-20-03 No local review queue | PASS (in the repo, existing code) | Existing GATE 15 browser is not a scheduler; no `nextLocalCard` added. |
| AUDIT-20-04 Backend session lock | BLOCKED | No session. |
| AUDIT-20-05 Rating path | BLOCKED | No new rating path. |
| AUDIT-20-06 Next-card barrier | BLOCKED | No new path. |
| AUDIT-20-07 Rescheduling semantics | BLOCKED | Not implemented. |
| AUDIT-20-08 Review-history claims | PASS (docs) | This document states AnkiDroid history as UNKNOWN. |
| AUDIT-20-09 Session lifecycle | BLOCKED | Not implemented. |
| AUDIT-20-10 Creation retry | BLOCKED | Not implemented; policy recorded in DECISION-20-12. |
| AUDIT-20-11 External invalidation | BLOCKED | Not implemented. |
| AUDIT-20-12 Reviewer action compatibility | BLOCKED | Bury/suspend semantics inside filtered decks are documented (DISCOVERY-20-26); GATE 13 not rechecked in a custom session. |
| AUDIT-20-13 Capability honesty | PASS (docs) | Unsupported items marked UNSUPPORTED/UNKNOWN. |
| AUDIT-20-14 Study UI truth | BLOCKED | No UI. |

## PART III — VERIFICATION MATRIX

No VER-20-* was executed. No tests were added or run.

| Verification | Result | Evidence |
|---|---|---|
| VER-20-01 Create supported custom session | BLOCKED | Not implemented (B1, B2). |
| VER-20-02 Deck filter | BLOCKED | Not implemented. Source semantics in DISCOVERY-20-28. |
| VER-20-03 Child deck inclusion | BLOCKED | Not implemented. Source: `deck:` includes children. |
| VER-20-04 Tag filter | BLOCKED | Not implemented. Source: DISCOVERY-20-29. |
| VER-20-05 State filter | BLOCKED | Not implemented. Source: DISCOVERY-20-05. |
| VER-20-06 Card limit | BLOCKED | Not implemented. Source: DISCOVERY-20-07. |
| VER-20-07 Ordering | BLOCKED | Not implemented. Source: DISCOVERY-20-08. |
| VER-20-08 No matching cards | BLOCKED | Not implemented. Source: `NoMatchingCards`, DISCOVERY-20-25. |
| VER-20-09 Normal rescheduling mode | BLOCKED | No device/backend run. |
| VER-20-10 No-reschedule / preview | BLOCKED | No device/backend run. |
| VER-20-11 Available ratings | BLOCKED | No device run. AnkiDroid hard-codes 4 (source). |
| VER-20-12 Rating commit | BLOCKED | Not implemented. |
| VER-20-13 Next card | BLOCKED | Not implemented. |
| VER-20-14 Session completion | BLOCKED | Not implemented. Source: queue empty. |
| VER-20-15 Bury | BLOCKED | Not implemented. |
| VER-20-16 Suspend | BLOCKED | Not implemented. |
| VER-20-17 Duplicate start input | BLOCKED | Not implemented. Source: replace-by-name (preset). |
| VER-20-18 Creation failure | BLOCKED | Not implemented. |
| VER-20-19 Ambiguous creation | BLOCKED | Not implemented. |
| VER-20-20 App restart | BLOCKED | Device UNKNOWN. |
| VER-20-21 External deletion | BLOCKED | Not implemented. |
| VER-20-22 Backend context change | BLOCKED | Not implemented. |
| VER-20-23 Rebuild | BLOCKED | Not implemented. |
| VER-20-24 End session | BLOCKED | Not implemented. |
| VER-20-25 Normal study isolation | BLOCKED | Not implemented. |
| VER-20-26 Browser isolation | BLOCKED | Not implemented. |
| VER-20-27 Arabic / RTL | BLOCKED | Not implemented. |
| VER-20-28 500-turn fake endurance | BLOCKED | Not implemented. |
| VER-20-29 Previous-gate regression | NOT RUN | Gradle not run in this discovery-only pass. |

Build validation (`./gradlew testDebugUnitTest`, `lint`, `assembleDebug`) was **not run** because no code changed.

## PART VII — INVARIANTS

| Invariant | Status | Note |
|---|---|---|
| INV-20-01 Discovery separate from implementation | PASS | This document has no implementation. |
| INV-20-02 Contract lock precedes implementation | PASS | No implementation exists. |
| INV-20-03 Exactly one scheduler authority per session | NOT YET TESTABLE | No session. Proposed table is single-authority. |
| INV-20-04 Browser results never substitute scheduler output | PASS (existing code) | No new path. |
| INV-20-05 Filter semantics from backend contract | PASS (docs) | Only backend-proven filters proposed. |
| INV-20-06 Unsupported filters never approximated | PASS (docs) | Flags, notetype, template excluded. |
| INV-20-07 Ordering backend-owned | PASS (docs) | No local ordering proposed. |
| INV-20-08 FSRS backend-owned | PASS (docs) | No Study-Agent FSRS. |
| INV-20-09 Learning steps backend-owned | PASS (docs) | None simulated. |
| INV-20-10 Ratings backend-owned | PASS (docs) | Buttons from backend states / provider. |
| INV-20-11 GATE 11 remains rating pipeline | PASS (docs) | No new pipeline. |
| INV-20-12 Backend immutable per session | NOT YET TESTABLE | No session. |
| INV-20-13 Custom session identity distinct from StudySessionId | NOT YET TESTABLE | Identity blocked (B3). |
| INV-20-14 Next card only from locked backend context | NOT YET TESTABLE | No context. |
| INV-20-15 Completion from backend truth | NOT YET TESTABLE | No session. |
| INV-20-16 No-reschedule and normal-reschedule never conflated | PASS (docs) | Separate modes; separate UX rule. |
| INV-20-17 Review-history effects described honestly | PASS (docs) | AnkiDroid marked UNKNOWN. |
| INV-20-18 No silent normal-review fallback | NOT YET TESTABLE | No external-invalidation path. |
| INV-20-19 No blind replay after ambiguous creation | PASS (docs) | Policy recorded (DECISION-20-12). |
| INV-20-20 GATE 20 cannot weaken GATE 11–19 | PASS | No change to GATE 11–19 code. |

---

## Existing-behavior findings to triage (outside GATE 20 implementation)

1. **Filtered decks are reviewable from AnkiDroid selection with no guard found.** `AnkiDroidBackend` review sessions accept any `deckRef`. Verify on device whether a filtered deck can be reviewed through the current path, and whether `deckID` selection changes the user's AnkiDroid current deck.
2. **Filtered preview answers are Ambiguous in GATE 11 commit classification.** This means a rating in a preview deck would not be retried or advanced. Intended, but it must be known to UX.
3. **`StudyMode.CUSTOM_SESSION` is a wire value with no client semantics.** Any PC-agent use of `custom` needs its own contract before the client shows it as a real mode.

## Unresolved facts (summary)

- PC agent's actual Anki route and restart behavior (not in repository).
- AnkiDroid device behavior for: preview answers, `deckID` selection side effects on current deck, filtered-deck review, restart, cross-client, sync.
- Two-client concurrent answering on a filtered deck.
- Whether a preset's replace-by-name behavior is acceptable to product.
- Whether `FSRS` memory state is updated for preview answers (source suggests not; not separately verified).
- Exact review order inside a filtered deck (backend-owned; not reproduced).

## Safety-critical unknowns

1. **Preset creation silently replaces** the reserved "Custom Study Session" deck and returns its cards home, which discards a previous session's membership.
2. **Creation mutates the collection** and has no proven idempotent identity on the preset path.
3. **AnkiDroid preview answers** are Ambiguous; device behavior of commit classification is unverified.
4. **Restart/sync/cross-client** states are unverified, so a session could diverge silently from another client.
5. **Localized reserved name** makes identity-by-name fragile across languages.
