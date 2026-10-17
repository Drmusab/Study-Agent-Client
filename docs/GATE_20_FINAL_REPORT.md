# GATE 20 — CUSTOM STUDY / FILTERED REVIEW: FINAL REPORT

**Gate result: GATE 20 LOCKED** (locked scope: detection, refusal, disclosure, identity and capability
truth — no new study mode is enabled, because no wired backend can support one safely).

| Phase | Artifact |
|---|---|
| Phase A — discovery | `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` (DISCOVERY-20-01…37) + re-verification delta in `docs/GATE_20B_CONTRACT_LOCK.md` §0 |
| Phase B — contract lock | `docs/GATE_20B_CONTRACT_LOCK.md` (DECISION-20-01…23, lock report) |
| Phase C — implementation | this file §3 |
| Part II — architecture audit | this file §6 |
| Part III — verification | this file §7 |
| Part V — build & regression | this file §10 |
| Part VIII — gate lock | this file §11 |

---

## 1. Discovery report (facts only)

### 1.1 AnkiDroid (pinned v2.24.1, source commit `9f579c10bb151146728220729c510acbbd8faba7`)

| Fact | Value | Evidence |
|---|---|---|
| Custom-study / filtered-deck creation | **UNSUPPORTED.** Provider URI set is exactly: `notes`, `notes_v2`, `notes/#`, `notes/#/cards`, `notes/#/cards/#`, `models`, `models/<id>`, `models/<id>/empty_cards`, `models/<id>/templates`, `models/<id>/templates/#`, `models/<id>/fields`, `schedule/`, `decks/`, `decks/#`, `selected_deck/`, `media`, `cards`, `cards/#`. | `CardContentProvider.addUri` @ v2.24.1 |
| Cards cannot be moved into a filtered deck | `require(!col.decks.isFiltered(did))` on `cards/#` update | same file, line 550 |
| Filtered decks are **visible** | `Deck.DECK_DYN` = `deck_dyn` = `col.decks.isFiltered(id)` | `FlashCardsContract.Deck.DECK_DYN` |
| Filtered cards are **identifiable** | `Card.ORIGINAL_DECK_ID` (`original_deck_id`) ≠ 0 while filtered; `Card.RAW_QUEUE` (`queue`) carries the queue code (4 = preview) | `FlashCardsContract.Card` |
| Existing filtered deck is **reviewable** through the provider | `schedule?deckID=<id>` → `selectDeckWithCheck` (any deck with `getLegacy(did) != null`) → `col.backend.getQueuedCards(fetchLimit = limit)`; the previous current deck is restored after the query (not in a `finally`) | `CardContentProvider.query`, SCHEDULE branch |
| Scheduler authority | Anki: `get_queues()` → `get_current_deck()` → `build_queues(deck.id)` → `child_decks(root)` + root, gathered by `for_each_due_card_in_active_decks` (`queue ∈ {2,3}`), `for_each_intraday_card_in_active_decks` (`queue ∈ {1,4}`) and `for_each_new_card_in_deck` (`queue = 0`), ordered by backend sort options | `rslib` @ 25.09.2: `scheduler/queue/mod.rs`, `queue/builder/mod.rs`, `queue/builder/gathering.rs`, `storage/card/*.sql` |
| Deck scope | **Always deck + descendants**; no "this deck only" option | `QueueBuilder::new` → `child_decks` |
| `limit` | number of rows returned from the queue; not a membership size, not a session length. Repo pins `REVIEW_MAX_LIMIT = 32`, asks for 1 | `AnkiDroidApiContract.kt` §GATE 06 |
| Rating buttons | `buttonCount` **hard-coded to 4** by the provider; labels from `nextIvlStr` | `CardContentProvider.query` SCHEDULE branch |
| Rating commit provability inside a filtered deck | **Not provable.** GATE 11 answers `OutcomeUnknown("filtered_deck_result_unverified")` whenever `originalDeckId != 0`, and `reconcile` always returns `StillAmbiguous` | `AnkiDroidCommitResultClassifier`, `AnkiDroidRatingCommitter.reconcile` (this repo) |
| Rescheduling semantics | Deck-level `reschedule` flag is **not** exposed by any public contract symbol | `FlashCardsContract.Deck` complete column set |
| Cleanup (empty / rebuild / delete) | **UNSUPPORTED** (AnkiDroid-internal only) | URI set above |
| Card browser | Refused by this app at this pin (`UnsupportedAnkiDroidCardBrowserGateway`) | `AnkiDroidBackend` |

### 1.2 PC backend

| Fact | Value | Evidence |
|---|---|---|
| PC Anki backend implementation | **Does not exist in this repository.** `AnkiBackendId.PcAgent` has no `AnkiBackend`; the only PC file is `PcAnkiAvailabilitySource.kt`, structure-only, which never fabricates a PC `Ready` | `data/anki/remote/PcAnkiAvailabilitySource.kt` header; `di/AppContainer.kt` registers only `AnkiDroidBackend` |
| Agent protocol | Carries no Anki traffic | `PcAnkiAvailabilitySource.kt`; `docs/ARCHITECTURE.md` |
| AnkiConnect route | Exposes no custom-study, filtered-deck create/rebuild/empty endpoint | `FooSoft/anki-connect` (prior discovery) |
| Anki backend itself (25.09.2) | `CustomStudy` (presets, returns `OpChanges` **without a deck id**), `AddOrUpdateFilteredDeck` (returns an id), `GetOrCreateFilteredDeck`, `RebuildFilteredDeck`, `EmptyFilteredDeck`, `GetQueuedCards`; filtered decks are persistent deck rows; cards keep `original_deck_id`; `reschedule` per deck | `rslib/src/scheduler/filtered/*`, `proto/anki/{scheduler,decks}.proto` (prior discovery, retained) |

**Consequence:** every PC row of the DISCOVERY-20-37 matrix is `UNKNOWN — no PC Anki backend exists in
this repository; nothing can be observed`. This is not a gap in the investigation; it is the finding.

### 1.3 Scheduler, rescheduling, persistence, cleanup — the short form

- **Scheduler authority is Anki in every reachable path.** Nothing in the public provider lets a caller
  choose, order, limit or supply cards; the queue is built by rslib and served by `getQueuedCards`.
- **Rescheduling** is a deck-level flag Anki owns (`reschedule`: true for cram DUE/NEW/REVIEW and ahead;
  false for ALL/forgot/preview). It is not readable through AnkiDroid's public contract; a per-card
  `queue = 4` is observable after the fact only.
- **Persistence:** filtered decks and card membership are ordinary collection rows, so they survive
  process death, app restart and sync as normal collection state. Study-Agent holds no backend
  filtered-session state at all, so there is nothing for it to restore.
- **Cleanup:** Anki returns cards home on `EmptyFilteredDeck` / rebuild / deck removal; revlog is never
  deleted by those paths. None of it is reachable from Study-Agent.

### 1.4 Unknowns that remain

1. Device behaviour of AnkiDroid's filtered-deck queue (no device or disposable-collection run).
2. Whether the temporary `deckID` selection is always restored (the provider's restore is not in a
   `finally`).
3. Sync / cross-client behaviour of filtered decks and concurrent answering.
4. FSRS memory bookkeeping for preview answers in rslib (not separately verified).
5. The PC agent's future route, if a PC Anki backend is ever implemented.

---

## 2. Contract lock report (decisions only)

Full text: `docs/GATE_20B_CONTRACT_LOCK.md`. Summary:

| Decision | Locked answer |
|---|---|
| Supported modes | `DECK_SCOPED_NORMAL_REVIEW` only. `BACKEND_FILTERED_REVIEW` and `BACKEND_PREVIEW` modelled, capability-gated, **disabled** |
| Scheduler authority | One StudySession → one backend → the Anki queue builder. Study-Agent selects nothing |
| Filter ownership | Structured intent only; never backend search syntax; never an approximation |
| Filter vocabulary | Deck scope (stable id) + descendants always included. Tag / state / flag / limit / ordering / no-reschedule / preview: **UNSUPPORTED** |
| Ordering | `BACKEND_DEFAULT`. No local sort, ever |
| Rescheduling | `NORMAL_RESCHEDULING` / `NO_RESCHEDULING`; **no `UNKNOWN` value** — unknown is refused |
| Review-history effects | Per DECISION-20-07; AnkiDroid's observable subset is what GATE 11 verifies |
| Session identity | Backend filtered-deck id; never a name; `StudySessionId ≠ filtered deck id` |
| Lifecycle | No invented lifecycle; session exists / backend queue empty |
| Creation & retry | Nothing is created → nothing is retried (`NO_BLIND_RETRY`) |
| Rebuild | None |
| End / cleanup | End the StudySession; no backend object to clean |
| Rating path | GATE 11, unchanged and singular |
| Capabilities | 15 flags; all `false` on AnkiDroid; defaults `false` everywhere |
| UX disclosure | Source deck, descendants included, backend-owned scheduling, and the rescheduling truth before the first rating |

---

## 3. File-by-file implementation report

### 3.1 New production files

**FILE:** `app/src/main/java/com/studyagent/client/core/anki/customstudy/AnkiStudyMode.kt`
**ACTION:** ADDED · **LOCKED CONTRACT REFERENCES:** DECISION-20-01/06/07/09
**RESPONSIBILITY:** the closed set of ways a session may be bound to the backend's scheduler.
**IMPLEMENTED:** `sealed interface AnkiStudyMode { Normal; Filtered(filteredDeckRef, rescheduling) }`
and `enum class AnkiReschedulingDisclosure { NORMAL_RESCHEDULING, NO_RESCHEDULING }` with a
user-facing `schedulingSummary()` per value. `Filtered` is constructible **only** with a disclosure, so
"filtered but nobody knows what it does" has no representation.
**LOCAL CHECKS:** `Gate20CustomStudyContractTest` (5 tests), `Gate20ArchitectureAuditTest` (mode
constructed only by the policy).
**ARCHITECTURAL NOTES:** no backend leakage (domain types only); no scheduler logic; no card.

**FILE:** `app/src/main/java/com/studyagent/client/core/anki/customstudy/AnkiCustomStudyCapabilities.kt`
**ACTION:** ADDED · **REFERENCES:** DECISION-20-21, AUDIT-20-13
**RESPONSIBILITY:** granular custom-study capability truth.
**IMPLEMENTED:** 15 flags + a constructor that **refuses** unsafe combinations (filtered review without
a provable commit or without a disclosed rescheduling behaviour; preview-only without no-reschedule;
child-deck exclusion without deck scoping), `supportedFilterVocabulary()`, `NONE`.
**LOCAL CHECKS:** `Gate20CustomStudyContractTest` (6 tests).
**ARCHITECTURAL NOTES:** the gate's safety rules are encoded in the *type*, so no adapter can advertise
a mode GATE 11 would leave permanently ambiguous.

**FILE:** `app/src/main/java/com/studyagent/client/core/anki/customstudy/CustomStudyPolicy.kt`
**ACTION:** ADDED · **REFERENCES:** DECISION-20-02/08/15, IMPLEMENTATION-20-08
**RESPONSIBILITY:** the single decision function: live deck snapshot + backend capabilities + backend
disclosure → `Proceed(mode)` or `Refused(token)`.
**IMPLEMENTED:** foreign-deck refusal; known-filtered-deck refusal when the backend cannot review one;
undisclosed-rescheduling refusal; ordinary/unknown-filtered → `Normal`. `errorFor()` maps a refusal to a
typed `AnkiError` (`UnsupportedAction("filtered_deck_review")` / `("filtered_deck_rescheduling")`).
**LOCAL CHECKS:** `Gate20CustomStudyContractTest` (7 tests), `Gate20CustomStudyStartTest`,
`Gate20ArchitectureAuditTest` (called at exactly two sites).
**ARCHITECTURAL NOTES:** pure; no card, no query, no scheduler call; no fallback from filtered to normal.

**FILE:** `app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidCustomStudyCapabilities.kt`
**ACTION:** ADDED · **REFERENCES:** DISCOVERY-20-35, DECISION-20-21, AUDIT-20-13
**RESPONSIBILITY:** the audited claim for the pinned public provider, with evidence in-file.
**IMPLEMENTED:** `AUDITED = AnkiCustomStudyCapabilities.NONE`; a `filteredDeckRescheduling` read that
refuses to guess; `DECK_SCOPE_INCLUDES_DESCENDANTS = true` (the one positive scope fact).
**LOCAL CHECKS:** `Gate20ArchitectureAuditTest` (2 tests).
**ARCHITECTURAL NOTES:** the refusal is what makes the policy fail closed on AnkiDroid.

**FILE:** `app/src/main/java/com/studyagent/client/ui/screens/customstudy/CustomStudyModels.kt`
**ACTION:** ADDED · **REFERENCES:** IMPLEMENTATION-20-14/15/16, DECISION-20-22, AUDIT-20-14
**RESPONSIBILITY:** pure projection of capability truth for the UI.
**IMPLEMENTED:** `CustomStudyPanelState`, `CustomStudyUnavailableOption`, `CustomStudyBlockingNotice`,
`CustomStudyPanelMapper.build(...)`; every locked option is either supported or listed with the reason;
deck names pass through verbatim (Arabic/mixed-script safe).
**LOCAL CHECKS:** `CustomStudyPanelTest` (8 tests).
**ARCHITECTURAL NOTES:** no Compose, no backend — so "the UI never claims what the backend cannot do"
is assertable without a renderer.

**FILE:** `app/src/main/java/com/studyagent/client/ui/screens/customstudy/CustomStudyPanel.kt`
**ACTION:** ADDED · **REFERENCES:** IMPLEMENTATION-20-15/16/17, AUDIT-20-14
**RESPONSIBILITY:** render that projection.
**IMPLEMENTED:** scope (deck + descendants), scheduling summary, blocking notice, per-option reasons.
**LOCAL CHECKS:** compiles under the harness Compose sweep (0 errors in `customstudy` files); state
covered by `CustomStudyPanelTest`.

### 3.2 Modified production files

**FILE:** `core/anki/AnkiBackend.kt` · **ACTION:** MODIFIED · **REFERENCES:** DECISION-20-06/21
**RESPONSIBILITY:** the backend-neutral contract gains two claims.
**IMPLEMENTED:** `val customStudyCapabilities: AnkiCustomStudyCapabilities` (default `NONE`) and
`suspend fun filteredDeckRescheduling(deckRef): AnkiResult<AnkiReschedulingDisclosure>` (default
`UnsupportedAction`). Both defaults are refusals, so an adapter that has not audited itself cannot look
capable. **LOCAL CHECKS:** full suite 2756/2756; `Gate21dArchitectureAuditTest` (the recording fixture
must override every member). **ARCHITECTURAL NOTES:** read-only, additive, no behaviour change for any
adapter that does not implement them.

**FILE:** `core/anki/AnkiSessionContext.kt` · **ACTION:** MODIFIED · **REFERENCES:** DECISION-20-09
**RESPONSIBILITY:** session identity includes the study mode.
**IMPLEMENTED:** `studyMode: AnkiStudyMode = AnkiStudyMode.DEFAULT` plus a construction rule: a
`Filtered` mode must name the context's own deck. **LOCAL CHECKS:** contract test
("a filtered session context is bound to the filtered deck it names").

**FILE:** `core/anki/StudySessionBackendLock.kt` · **ACTION:** MODIFIED · **REFERENCES:** DECISION-20-09/20-15
**RESPONSIBILITY:** carry the mode in memory; never persist it.
**IMPLEMENTED:** `@Transient val studyMode`; `from(context)` carries it; `toSessionContext()` returns it;
admission accepts it. Durable bytes are unchanged (`ignoreUnknownKeys = true`, no new serialized field).
**LOCAL CHECKS:** contract test asserts `studyMode` does **not** appear in encoded bytes and that a
restored lock is `Normal`.

**FILE:** `core/study/AnkiStudyInteraction.kt` · **ACTION:** MODIFIED · **REFERENCES:** IMPLEMENTATION-20-07/08
**RESPONSIBILITY:** the start request carries the decided mode.
**IMPLEMENTED:** `AnkiStudyRequest.studyMode` (default `Normal`).

**FILE:** `core/study/AnkiStudyEffectExecutor.kt` · **ACTION:** MODIFIED · **REFERENCES:** IMPLEMENTATION-20-08/11, INV-20-14/20-18
**RESPONSIBILITY:** the binding-moment guard.
**IMPLEMENTED:** after the live deck listing is read (and the deck is confirmed to exist), the policy is
evaluated again with the backend's capabilities and, for a filtered deck, its rescheduling disclosure; a
refusal fails the start with a typed error **before** `beginReview`. The admitted lock carries the mode.
**LOCAL CHECKS:** `Gate20CustomStudyStartTest` ("the binding moment refuses a filtered deck even when
the request claims normal mode"), endurance test.

**FILE:** `data/anki/ankidroid/AnkiDroidBackend.kt` · **ACTION:** MODIFIED · **REFERENCES:** checkpoint 05/06
**RESPONSIBILITY:** state the audited claim.
**IMPLEMENTED:** `override val customStudyCapabilities = AnkiDroidCustomStudyCapabilities.AUDITED`;
`filteredDeckRescheduling` refuses (with a foreign-deck guard first).

**FILE:** `data/repository/AnkiLocalStudyStarter.kt` · **ACTION:** MODIFIED · **REFERENCES:** IMPLEMENTATION-20-08/10
**RESPONSIBILITY:** the pre-binding gate.
**IMPLEMENTED:** `resolveDeck` now returns the deck (not just its ref) so the filtered flag is available;
the policy is evaluated before dispatch; a refusal produces
`Result.Refused(REASON_CUSTOM_STUDY_REFUSED, error)` and dispatches nothing; a start echoes the admitted
mode in `Result.Started.studyMode`. The rescheduling read is issued **only** for a deck the backend calls
filtered, so an ordinary start performs the same provider traffic as before.
**LOCAL CHECKS:** `Gate20CustomStudyStartTest` (4 start-site tests) + existing `AnkiLocalStudyStarterTest`
unchanged and passing.

**FILE:** `ui/screens/library/DeckDetailsUiState.kt`, `DeckDetailsViewModel.kt`, `DeckDetailsScreen.kt`
**ACTION:** MODIFIED · **REFERENCES:** IMPLEMENTATION-20-15/16/17, DECISION-20-22
**RESPONSIBILITY:** show the truth before the user presses Start.
**IMPLEMENTED:** `DeckDetailsUiState.Ready.customStudy` carries the pure panel state; the ViewModel
projects it from the backend's capabilities and the deck's own filtered flag; the screen renders it above
the Start button, and the button is disabled when the policy refuses the deck.
**LOCAL CHECKS:** `CustomStudyPanelTest`; Compose sweep 0 errors.

### 3.3 Test files

| File | Action | Purpose |
|---|---|---|
| `test/.../anki/customstudy/Gate20CustomStudyContractTest.kt` | ADDED | capability honesty, the decision matrix, session identity, non-persistence (15 tests) |
| `test/.../anki/customstudy/Gate20ArchitectureAuditTest.kt` | ADDED | PART II structural audit on source text with comments stripped (12 tests) |
| `test/.../study/Gate20CustomStudyStartTest.kt` | ADDED | start-site + binding-moment behaviour (7 tests) |
| `test/.../study/Gate20CustomStudyEnduranceTest.kt` | ADDED | VER-20-28, 500 events (2 tests) |
| `test/.../ui/CustomStudyPanelTest.kt` | ADDED | capability-honest UI + Arabic/RTL names (8 tests) |
| `test/.../anki/fake/FakeAnkiBackend.kt` | MODIFIED | `customStudyCapabilities`, `filteredRescheduling`, read-only `schedulerQueueCardIds` |
| `test/.../study/AnkiCommitHarness.kt` | MODIFIED | optional `customBackend` so a filtered collection can be driven through the real machine |
| `test/.../anki/Gate21dFixtures.kt` | MODIFIED | the probe-only backend must record the new member too |
| `tools/jvm-harness/bin/bootstrap-gate20.sh` | ADDED | sandbox bootstrap wrapper for the Gradle-free harness (no production impact) |

---

## 4. Scheduler authority report

| Mode | Scheduler authority | Next-card authority | Rating authority |
|---|---|---|---|
| `DECK_SCOPED_NORMAL_REVIEW` (enabled) | Anki backend queue builder, reached through `schedule?deckID=` → `getQueuedCards` | `AnkiBackend.nextCard` on the locked backend | GATE 11 → backend `answerCard` |
| `BACKEND_FILTERED_REVIEW` (disabled) | *same single authority* — the queue builder over the filtered deck's cards | *same* | *same* |
| `BACKEND_PREVIEW` (disabled) | *same single authority* — preview states | *same* | *same* |

No row has two authorities. Study-Agent contributes: the deck reference, the stop condition, and — for a
refused deck — a refusal.

---

## 5. Rescheduling matrix

| Backend / mode | due | interval | review history | FSRS | reps / lapses |
|---|---|---|---|---|---|
| AnkiDroid · normal deck review | updated by Anki | updated by Anki | revlog written | backend-owned | `reps + 1`, lapses per Anki rules |
| AnkiDroid · filtered deck, `reschedule = true` *(unreachable here)* | updated | updated | revlog written | backend-owned | `reps + 1` |
| AnkiDroid · filtered deck, `reschedule = false` / preview *(unreachable)* | preview timestamp; restored on exit | unchanged | revlog, kind `Filtered` | not applied on the preview path | unchanged |
| PC / Anki 25.09.2 · same rows | identical (same rslib), **observed through no implementation in this repo** | | | | |

AnkiDroid's observable evidence for any of this is what GATE 11 reads: `reps`, `lapses`, `interval`,
`queue`, `due`, `last_review_time`, `original_deck_id`.

---

## 6. Architecture audit matrix

| Audit | Result | Evidence |
|---|---|---|
| AUDIT-20-01 Contract conformance | **PASS** | every behaviour traces to a locked DECISION-20-*; `Gate20ArchitectureAuditTest.the decision is asked at both admission points and nowhere else` |
| AUDIT-20-02 Single scheduler authority | **PASS** | §4; no code path selects a card; `Gate20ArchitectureAuditTest.the custom-study package never touches a card, a queue or the scheduler` |
| AUDIT-20-03 No local review queue | **PASS** | `no client-side review queue exists anywhere in production sources` (7 forbidden spellings over all main sources, comments stripped); `assertEquals(0, a.browseCalls)` in the endurance run |
| AUDIT-20-04 Backend session lock | **PASS** | endurance: 0 calls on the substitute backend, `backendSwitches = 0`, every session context keeps backend A |
| AUDIT-20-05 Rating path | **PASS** | `custom study adds no second rating path`; endurance `committed == physicalCommitCalls` |
| AUDIT-20-06 Next-card barrier | **PASS** | endurance `nextCardBeforeCommit = 0` (the presented card does not change between a rating and its durable commit) |
| AUDIT-20-07 Rescheduling semantics | **PASS** | no mode is reachable without a disclosure; the two values are never conflated (contract test) |
| AUDIT-20-08 Review-history claims | **PASS** | the panel and the docs state only backend-backed effects; unproven effects are stated as unproven |
| AUDIT-20-09 Session lifecycle | **PASS** | no invented states; completion remains `NextCardResult.Finished` |
| AUDIT-20-10 Creation retry | **PASS** | nothing is created, so nothing is replayed; `NO_BLIND_RETRY` recorded for every future path |
| AUDIT-20-11 External invalidation | **PASS** | no silent fallback: the binding-moment guard refuses, the start site refuses, and a vanished deck fails as `DeckNotFound` |
| AUDIT-20-12 Reviewer-action compatibility | **BLOCKED (not applicable)** | bury/suspend inside a filtered session cannot occur because no filtered session can start; GATE 13 is untouched |
| AUDIT-20-13 Capability honesty | **PASS** | `AnkiCustomStudyCapabilities` refuses unsafe combinations; AnkiDroid claims `NONE` with in-file evidence; the interface default is `NONE` |
| AUDIT-20-14 Study UI truth | **PASS** | `CustomStudyPanelTest` (8 tests): unsupported options are listed with reasons, a filtered deck is blocked with the consequence, Arabic names pass through |

---

## 7. Verification matrix

| Verification | Result | Evidence |
|---|---|---|
| VER-20-01 Create supported custom session | **BLOCKED** | No backend enables creation; `AnkiDroidCustomStudyCapabilities.AUDITED == NONE`; the creation path is deliberately not implemented (DECISION-20-11/21) |
| VER-20-02 Deck filter | **PASS** | `Gate20CustomStudyStartTest.an ordinary deck still starts…` binds the named deck id; `the binding moment lets an ordinary deck through…` |
| VER-20-03 Child deck inclusion | **PASS** | `rslib QueueBuilder::new → child_decks` (upstream, 25.09.2); `AnkiDroidCustomStudyCapabilities.DECK_SCOPE_INCLUDES_DESCENDANTS`; no client deck walk exists (audit test) |
| VER-20-04 Tag filter | **BLOCKED** | `canFilterByTag` unsupported on every wired backend; the panel lists it as unavailable with a reason |
| VER-20-05 State filter | **BLOCKED** | same |
| VER-20-06 Card limit | **PASS** | no membership limit is used; `schedule` `limit` is a row count (pinned 1); `SessionTargetType` stays a Study-Agent stop condition (DECISION-20-19) |
| VER-20-07 Ordering | **PASS** | `no client-side review queue…`; `the card came from the backend scheduler and never from a client-side list` (presented order == scheduler queue order) |
| VER-20-08 No matching cards | **PASS** | `NextCardResult.Finished` is a valid end state; unchanged GATE 06/11 suites still pass (2756/2756) |
| VER-20-09 Normal rescheduling mode | **BLOCKED** | unreachable: no backend discloses rescheduling before a start (DECISION-20-06) |
| VER-20-10 No-reschedule / preview | **BLOCKED** | same; preview is only observable per card after scheduling |
| VER-20-11 Available ratings | **PASS** | rating options come from the backend turn in every run; AnkiDroid's provider hard-codes 4 buttons (source) |
| VER-20-12 Rating commit | **PASS** | endurance: durable `COMMITTED` count == `physicalCommitCalls`, every record owned by backend A |
| VER-20-13 Next card | **PASS** | endurance: `localSchedulerSelections = 0` (79 presented cards matched the scheduler queue order exactly, 80 next-card calls) |
| VER-20-14 Session completion | **PASS** | backend-defined (`Finished`); no local card counting was added |
| VER-20-15 Bury | **BLOCKED (n/a in custom mode)** | no filtered session can start; GATE 13 unchanged and still 100% green |
| VER-20-16 Suspend | **BLOCKED (n/a in custom mode)** | same |
| VER-20-17 Duplicate start input | **PASS** | every filtered Start attempt in the endurance run (≥25) produced no session; `a known filtered deck is refused…nothing is dispatched` |
| VER-20-18 Creation failure | **PASS** | refusal surfaces as a typed `UnsupportedAction`; no session, no `nextCard`, no commit |
| VER-20-19 Ambiguous creation | **PASS by construction** | nothing is created, so no ambiguous creation can exist (DECISION-20-11/20-12) |
| VER-20-20 App restart | **PASS** | mode is never persisted (`@Transient`); restored locks decode to `Normal`; endurance includes ≥4 ledger restarts |
| VER-20-21 Backend session deleted externally | **PASS** | the start re-reads the live listing (typed `DeckNotFound`); an active session's backend disappearance is handled by GATE 21G continuation (no fallback) |
| VER-20-22 Backend context change | **PASS** | endurance: preference and availability churn never moved the session (`backendSwitches = 0`, 0 calls on the other backend) |
| VER-20-23 Rebuild | **BLOCKED** | unsupported on every wired backend; no rebuild path exists |
| VER-20-24 End session | **PASS** | `endReview` handle release; nothing to empty or restore because nothing was created (DECISION-20-14) |
| VER-20-25 Normal study isolation | **PASS** | `an ordinary deck still starts, in normal mode, with no extra provider traffic`; full suite 2756/2756 |
| VER-20-26 Browser isolation | **PASS** | `assertEquals("browsing is never a scheduler", 0, a.browseCalls)`; audit test finds no queue spellings; AnkiDroid browser is refused at this pin |
| VER-20-27 Arabic / RTL | **PASS** | `CustomStudyPanelTest.arabic and mixed-script deck names pass through byte for byte` (4 names, no trimming/folding) |
| VER-20-28 500-turn fake endurance | **PASS** | `Gate20CustomStudyEnduranceTest.500 events…` — 500 events, 79 turns, ≥25 filtered-start attempts, ≥4 restarts, 80 next-card calls; all six violation counters **0** |
| VER-20-29 Previous-gate regression | **PASS** | 2756 tests, 2756 passed, 0 failed (baseline before GATE 20: 2712/2712) |

---

## 8. Residual risks

| # | Risk | Why it remains | Mitigation in place |
|---|---|---|---|
| R-20-1 | A deck whose `deck_dyn` could not be parsed (`isFiltered == null`) is started as ordinary review | the backend did not say, and there is no other public read; refusing would break study for a malformed row | the session is `Normal` by construction and the rating still goes through GATE 11 (unprovable filtered ratings stay AMBIGUOUS rather than silently advancing) |
| R-20-2 | No PC Anki backend exists, so GATE 20 says nothing about PC custom study | out of repository | the capability model is in place; a PC backend must claim flags and satisfy the same constructor rules before any mode appears |
| R-20-3 | AnkiDroid's temporary `deckID` selection is restored outside a `finally` | provider implementation | not a new behaviour of this gate; the existing review path is unchanged |
| R-20-4 | A filtered deck can be created externally (AnkiDroid UI, desktop, AnkiConnect) while Study-Agent runs | collection-level object | start re-reads the live listing; a session whose deck becomes filtered is refused/invalidated, never continued as ordinary review |
| R-20-5 | Sync, cross-client and concurrent answering of filtered decks are unverified | no device / two-client run | no Study-Agent state depends on them; the commit remains ambiguous where it cannot be proven |
| R-20-6 | FSRS memory bookkeeping for preview answers in rslib is not separately verified | upstream detail outside this pin's audit | no UI or doc claims an effect for preview |
| R-20-7 | A future backend must extend `StudySessionBackendLock` before persisting a non-normal mode | deliberate deferral | documented at the field itself and in DECISION-20-09; a non-normal mode is unreachable today |
| R-20-8 | The 500-event run used the Gradle-free harness (Kotlin 2.3, coroutines 1.10.2) instead of `./gradlew testDebugUnitTest` | no JVM/Gradle in this sandbox; Maven Central unreachable | harness deviations are documented in `tools/jvm-harness/README.md`; the same suite must be re-run in CI |

---

## 9. Invariants

| Invariant | Status | Note |
|---|---|---|
| INV-20-01 Discovery separate from implementation | **PASS** | Phase A closed before any code; the lock precedes every checkpoint |
| INV-20-02 Contract lock precedes implementation | **PASS** | `docs/GATE_20B_CONTRACT_LOCK.md` first |
| INV-20-03 Exactly one scheduler authority per session | **PASS** | §4; endurance `localSchedulerSelections = 0` |
| INV-20-04 Browser results never substitute scheduler output | **PASS** | audit + `browseCalls = 0` |
| INV-20-05 Filter semantics from the locked backend contract | **PASS** | only a deck scope is expressible, and it comes from the backend's own identity |
| INV-20-06 Unsupported filters never approximated | **PASS** | panel lists them with reasons; capability type refuses unsafe claims |
| INV-20-07 Ordering backend-owned | **PASS** | no sort, no walk, no queue |
| INV-20-08 FSRS backend-owned | **PASS** | no FSRS code added |
| INV-20-09 Learning steps backend-owned | **PASS** | none simulated |
| INV-20-10 Ratings backend-owned | **PASS** | options come from the backend turn |
| INV-20-11 GATE 11 remains the rating pipeline | **PASS** | no second path; audit test |
| INV-20-12 Backend immutable per session | **PASS** | endurance: 0 switches, 0 foreign records |
| INV-20-13 Custom session identity distinct from StudySessionId | **PASS** | `AnkiSessionContext.studyMode` vs `studySessionId`; filtered ref is a backend deck id |
| INV-20-14 Next card only from the locked backend context | **PASS** | endurance `nextCardBeforeCommit = 0` |
| INV-20-15 Completion from backend truth | **PASS** | `Finished`, never a local count |
| INV-20-16 No-reschedule and normal-reschedule never conflated | **PASS** | two enum values, no `UNKNOWN`, refused when undisclosed |
| INV-20-17 Review-history effects described honestly | **PASS** | §5 + panel strings |
| INV-20-18 No silent normal-review fallback | **PASS** | refusals at both admission points; endurance `filteredSessionsStarted = 0` |
| INV-20-19 No blind replay after ambiguous creation | **PASS** | nothing is created |
| INV-20-20 GATE 20 cannot weaken GATE 11–19 | **PASS** | 2756/2756; GATE 11's filtered-deck verdict is now *enforced* rather than discovered mid-session |

---

## 10. Build & regression validation

`./gradlew` cannot run in this sandbox (no JVM, no Maven Central / `dl.google.com` reachability). The
repository's documented substitute, `tools/jvm-harness`, was bootstrapped and used instead
(`tools/jvm-harness/README.md` lists its deviations; it is equivalence evidence, not CI proof).

| Step | Result |
|---|---|
| `build.sh shims` | built |
| `build.sh main` (336 files) | **0 errors** |
| `build.sh compose` (GATE 16 UI closure, 24 files) | **0 errors** |
| `GATE16_COMPOSE_ALL=1 build.sh compose` (full sweep, diagnostic) | 147 pre-existing harness-stub errors in 17 files; **0 in any `customstudy` file** |
| `build.sh test` (261 files) | **0 errors** |
| `run.sh` (full suite) | **classes 227 · tests 2756 · passed 2756 · failed 0** (baseline 2712/2712) |
| `run.sh Gate20` | 44 tests, all pass |

**Required in CI before the gate is considered validated there:** `./gradlew testDebugUnitTest`,
`./gradlew lint`, `./gradlew assembleDebug`, `./gradlew assembleRelease`, and
`./gradlew connectedDebugAndroidTest` where configured.

---

## 11. Gate lock

```
DISCOVERY COMPLETE .................... YES (DISCOVERY-20-01…37 + 20-38 report; §1)
CONTRACT LOCKED ....................... YES (DECISION-20-01…23; docs/GATE_20B_CONTRACT_LOCK.md)
SCHEDULER AUTHORITY UNAMBIGUOUS ....... YES (one authority, three rows, no hybrid)
IMPLEMENTATION COMPLETE ............... YES (locked scope; 6 new production files, 10 modified)
ARCHITECTURE AUDIT COMPLETE ........... YES (AUDIT-20-01…14; 13 PASS, 1 BLOCKED-not-applicable)
VERIFICATION COMPLETE ................. YES (VER-20-01…29; 18 PASS, 9 BLOCKED-by-contract, 2 n/a)
BUILD / REGRESSION VALIDATION ......... PARTIAL — harness green (2756/2756); ./gradlew pending in CI

GATE 20 LOCKED
```

`BLOCKED` rows are not defects: each is a capability the locked contract records as unsupported, and each
is enforced (the UI says so, the policy refuses it, and no code path can reach it). No custom-study
feature was implemented by reproducing Anki's scheduler — there is no scheduler code in this gate.
