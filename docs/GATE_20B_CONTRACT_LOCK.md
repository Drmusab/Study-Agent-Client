# GATE 20B — Custom Study / Filtered Review: CONTRACT LOCK

**Supersedes:** the contract decision in `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md` (Phase B, which ended
`RESULT: BLOCKED`). Phase A of that document remains the discovery record; **this file re-verifies it,
corrects five of its cells with new evidence, and re-locks it.**

```
RESULT: LOCKED (audited scope)
SCHEDULER AUTHORITY: UNAMBIGUOUS
IMPLEMENTATION AUTHORIZED: YES   — for the locked, contract-enforcing subset
IMPLEMENTATION AUTHORIZED: NO    — for any custom-study creation, rebuild, empty, filter,
                                   ordering, limit, no-reschedule or preview feature
```

The short version: **the only wired Anki backend can neither create a custom-study object nor prove
what a rating did inside an existing one, so GATE 20 enables no new study mode.** What it does enable
is the contract machinery that makes that answer *enforced* rather than assumed: a capability model,
one decision function, two admission guards, and honest UI. That is the whole of "backend-aware
support without reimplementing Anki scheduling".

---

## 0. Phase A re-verification (DISCOVERY-20-38 delta)

The prior discovery was source-only for both backends. This pass re-ran the two load-bearing checks
against the pinned upstream sources and added one that was missing.

| # | Item | Prior finding | Re-verified finding | Evidence |
|---|---|---|---|---|
| A1 | PC backend existence | "PC agent not in this repository; route UNKNOWN" | **No PC Anki backend exists in this repository at all.** `AnkiBackendId.PcAgent` has no `AnkiBackend` implementation; the only PC file is `data/anki/remote/PcAnkiAvailabilitySource.kt`, which is explicitly structure-only ("No PC Anki backend type is declared or constructed anywhere in this repository … never fabricate a PC `Ready`"). The agent protocol carries no Anki traffic. | `app/src/main/java/.../data/anki/remote/PcAnkiAvailabilitySource.kt` header; `AnkiBackendRegistration` wired only with `AnkiDroidBackend` (`di/AppContainer.kt`); `docs/ARCHITECTURE.md` |
| A2 | AnkiDroid creation surface | "no public API" | **Confirmed by enumerating the provider's complete URI set** at v2.24.1: `notes`, `notes_v2`, `notes/#`, `notes/#/cards`, `notes/#/cards/#`, `models`, `models/<id>`, `models/<id>/empty_cards`, `models/<id>/templates`, `models/<id>/templates/#`, `models/<id>/fields`, `schedule/`, `decks/`, `decks/#`, `selected_deck/`, `media`, `cards`, `cards/#`. No custom-study, filtered-deck, rebuild, empty or search endpoint exists. | `AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt@v2.24.1`, `addUri(...)` block |
| A3 | Filtered-deck review reachability | "device behaviour UNKNOWN" | **Reachable and backend-scheduled.** `schedule?deckID=<id>` calls `selectDeckWithCheck(col, did)` (accepts any deck with `getLegacy(did) != null`, filtered decks included), then `col.backend.getQueuedCards(fetchLimit = limit)`. The queue is built by Anki: `get_queues()` → `get_current_deck()` → `build_queues(deck.id)` → `child_decks(root)` + root, gathered by `for_each_due_card_in_active_decks` / `for_each_intraday_card_in_active_decks` / new-card gather, ordered by the backend's own sort options. `buttonCount` is **hard-coded to 4** by the provider. | `CardContentProvider.kt` SCHEDULE branch + `selectDeckWithCheck`; `rslib/src/scheduler/queue/builder/mod.rs`, `queue/mod.rs`, `storage/card/due_cards.sql`, `intraday_due.sql`, `active_new_cards.sql` @ `25.09.2` |
| A4 | Deck scope | "deck: includes children (Anki search)" | **Confirmed for the review queue itself, not just for search:** `QueueBuilder::new` calls `col.storage.child_decks(&root_deck)` and inserts the root, so a deck-scoped review **always** includes descendants. No "this deck only" option exists on either backend. | `rslib/src/scheduler/queue/builder/mod.rs` @ 25.09.2 |
| A5 | Preview / no-reschedule observability | "AnkiDroid: UNKNOWN (no reschedule flag)" | **Partially observable per card, never selectable.** A card in a `reschedule = false` filtered deck sits in `queue = 4` (`PreviewRepeat`); `intraday_due.sql` gathers `queue IN (1, 4)`, so preview cards *are* served by the same queue. AnkiDroid's `Card.RAW_QUEUE` (`queue`) and `Card.ORIGINAL_DECK_ID` (`original_deck_id`) expose both facts per card. The **deck-level** `reschedule` flag is absent from `FlashCardsContract.Deck`, so it cannot be disclosed *before* a session starts. | `intraday_due.sql`; `FlashCardsContract.Card.RAW_QUEUE` KDoc (queue codes −3…4, "4 = preview"); `AnkiDroidCardState` in this repo already reads `queue` and `originalDeckId` |
| A6 | Rating provability inside a filtered deck | not evaluated | **Not provable, by GATE 11's own verdict.** `AnkiDroidCommitResultClassifier.synchronousAnswer` answers `OutcomeUnknown("filtered_deck_result_unverified")` whenever the baseline card has `originalDeckId != 0`, even when the observation is `reps + 1` inside the window; `reconcile` always answers `StillAmbiguous`. An AMBIGUOUS commit blocks progression (`NextCardResult.Failure(CommitConflict)`) and is never replayed. | `data/anki/ankidroid/AnkiDroidCommitResultClassifier.kt`, `AnkiDroidCommitEvidence.kt`, `AnkiDroidRatingCommitter.reconcile`, `AnkiDroidBackend.nextCardLocked` |
| A7 | Card limit | "`schedule` limit = queue fetch size" | Confirmed: `limit` is the number of **rows** returned from `getQueuedCards`, not a filtered-deck membership size and not a session length. This repo pins `REVIEW_MAX_LIMIT = 32`. | `AnkiDroidApiContract.kt` §GATE 06 table; `CardContentProvider` SCHEDULE branch |

**Consequence of A1:** every PC row in DISCOVERY-20-37 becomes `UNKNOWN — no PC Anki backend exists in
this repository; nothing can be observed`. That is a stronger and more honest statement than "PC route
UNKNOWN", and it removes the largest blocker from being a mystery: there is no PC study surface to
lock today.

---

## PHASE B — DECISIONS

### DECISION-20-01 — Supported custom-study modes

| Mode | Locked? | Backend proof |
|---|---|---|
| `DECK_SCOPED_NORMAL_REVIEW` | **LOCKED (existing)** | Ordinary deck review; unchanged by this gate. Deck + descendants, backend-ordered, backend-scheduled. |
| `BACKEND_FILTERED_REVIEW` (existing backend-native filtered deck) | **RECOGNISED, NOT ENABLED** | Requires (a) a filtered deck the backend reports, (b) a provable rating commit, (c) a disclosed rescheduling behaviour. (a) exists on AnkiDroid; (b) is **refused by GATE 11** (A6); (c) is not disclosable (A5). Disabled. |
| `BACKEND_PREVIEW` | **RECOGNISED, NOT ENABLED** | Requires creation or a deck-level no-reschedule flag. Neither exists. |
| Any `CUSTOM` mode | **FORBIDDEN** | A mode whose semantics vary per backend is exactly what the gate excludes. |

`AnkiStudyMode` (`core/anki/customstudy/AnkiStudyMode.kt`) is a sealed `Normal | Filtered` — the
`Filtered` variant is constructible only with a known `AnkiReschedulingDisclosure`, so no code path can
build "filtered but nobody knows what it does".

### DECISION-20-02 — Scheduler authority (locked)

| Mode | Scheduler authority | Next-card authority | Rating authority |
|---|---|---|---|
| `DECK_SCOPED_NORMAL_REVIEW` | Anki backend (queue builder) | `getQueuedCards` / provider `schedule` | GATE 11 → backend `answerCard` |
| `BACKEND_FILTERED_REVIEW` *(if ever enabled)* | Anki backend (same queue builder, filtered deck's cards) | same | same |
| `BACKEND_PREVIEW` *(if ever enabled)* | Anki backend (preview states) | same | same |

One StudySession → one backend → **one** scheduler authority, in every row. Study-Agent never selects,
sorts, prioritises, limits or re-queues a card. `SessionTargetType` (cards/minutes/finish-due) remains a
Study-Agent-owned *stop condition* and is deliberately not a scheduler input.

### DECISION-20-03 — Filter ownership (locked)

Study-Agent expresses **structured intent** only; it never exposes Anki search syntax to the UI and
never builds a query string. At this pin the intent has exactly one expressible dimension (a deck
reference), because that is all the backend can honour. Any dimension a backend cannot reproduce is
reported as unsupported, never translated into an approximation.

### DECISION-20-04 — Filter vocabulary (locked)

| Dimension | Locked | Note |
|---|---|---|
| Deck scope | **YES** | One deck by stable backend deck id. |
| Include descendants | **ALWAYS ON, cannot be turned off** | Backend fact (A4). UI states it. |
| Exclude descendants | **UNSUPPORTED** | No backend in this gate can. |
| Tags | **UNSUPPORTED** (capability exists, no backend claims it) | `canFilterByTag` is modelled so a future backend can claim it truthfully. |
| Card states (new/learning/due/review) | **UNSUPPORTED** | Same. |
| Flags | **UNSUPPORTED** | Same. |
| Suspended / buried selection | **UNSUPPORTED, and never needed** | Anki excludes `-is:suspended -is:buried -deck:filtered` from every filtered build anyway. |
| Note type / template / text / rated-recently / created-recently | **OUT OF VOCABULARY** | Not reachable through any public review API here. |
| Card-id lists | **FORBIDDEN** | A caller-supplied card list would make Study-Agent the scheduler (INV-20-03). |

### DECISION-20-05 — Ordering (locked)

`BACKEND_DEFAULT` only. Card order is produced by the backend's queue builder (review order, new-gather
priority, new/review mix, load balancer, intraday learning sort, `fnvhash` tie-break where relevant) and
is never re-sorted locally. `DUE` / `ADDED` / `RANDOM` exist in Anki's `FilteredSearchOrder` but are
**filter-build** orders, unreachable without filtered-deck creation, so they are not offered.

### DECISION-20-06 — Rescheduling modes (locked)

Two values, both from Anki's filtered-deck `reschedule` flag, never inferred:

- `NORMAL_RESCHEDULING` — answers apply normal scheduling state; `reps += 1`; FSRS state and due update; revlog written.
- `NO_RESCHEDULING` (including preview) — card sits in `queue = 4`; revlog written with `RevlogReviewKind::Filtered`; `reps` **not** incremented; normal due/interval restored when the card leaves.

There is deliberately **no `UNKNOWN` value**: an unknown rescheduling behaviour is refused as a start,
never represented as a mode (INV-20-16/20-17).

### DECISION-20-07 — Review-history semantics (locked, per mode)

| Mode | revlog | reps | lapses | FSRS | due |
|---|---|---|---|---|---|
| `DECK_SCOPED_NORMAL_REVIEW` (this build) | yes | +1 | normal | normal | normal |
| `NORMAL_RESCHEDULING` *(if enabled)* | yes | +1 | normal | normal | normal |
| `NO_RESCHEDULING` / preview *(if enabled)* | yes, kind Filtered | **no** | no | not applied on the preview path | preview timestamp, restored on exit |

AnkiDroid's *observable* subset of this table is what GATE 11 already uses: `reps`, `lapses`, `interval`,
`queue`, `due`, `last_review_time`. The UI never claims an effect the backend did not disclose.

### DECISION-20-08 — Session identity (locked)

Backend identity for a custom session is the **filtered deck id** (an `AnkiDeckRef`), never a display
name, never a query string, never a session handle invented by Study-Agent. `StudySessionId ≠
backend filtered-deck id` (DECISION-20-09).

### DECISION-20-09 — StudySession vs backend identity (locked)

`AnkiSessionContext.studyMode` carries the mode for the session's lifetime; `StudySessionBackendLock`
carries it **in memory only** (`@Transient`), so durable bytes and every older reader are unchanged.
A restored session is therefore always `Normal` — which is sound precisely because a non-normal mode
cannot be started in this build (the policy refuses it), so the default is never a silent downgrade.

### DECISION-20-10 — Session lifecycle (locked)

No lifecycle machinery is added. Anki has no custom-study session object: a filtered deck is a
persistent deck with membership. Only the states the backend really has are modelled:

- a session exists (the backend accepted `beginReview`), and
- the backend's queue is empty (`NextCardResult.Finished`) — the only real completion.

`CREATED / ACTIVE / COMPLETED / ENDED / INVALIDATED` are **not** introduced (they would be symmetry, not
truth).

### DECISION-20-11 — Durable mutation tracking (locked)

**Not needed, because no creation path is enabled.** Custom-study creation would be
`PERSISTENT_COLLECTION_MUTATION` with an ambiguous outcome and would require a creation ledger; since no
backend here can create one, no ledger is added. The moment a backend claims `canCreateFilteredSession`,
this decision must be revisited before that claim is enabled.

### DECISION-20-12 — Creation retry policy (locked)

| Operation | Classification |
|---|---|
| No enabled operation | `NO_BLIND_RETRY` by default: nothing is created, so nothing is retried. |
| `AddOrUpdateFilteredDeck` (future) | `NO_BLIND_RETRY` — a replay with `id = 0` creates a second deck. |
| Preset `CustomStudy` (future) | `RETRY_ONLY_AFTER_PROVEN_NON_CREATION` — it replaces the reserved deck and would destroy the prior session's membership. |
| `RebuildFilteredDeck` (future) | `NO_BLIND_RETRY` while a session is active. |

### DECISION-20-13 — Rebuild policy (locked)

No rebuild, ever, in this build: nothing is created, so nothing is rebuilt. Future rule: no automatic
rebuild; manual rebuild only from an explicit action that states cards will be returned home and
re-selected; never while a session is active.

### DECISION-20-14 — End / cleanup policy (locked)

"End custom study" = end the StudySession (`endReview` handle release). There is **no backend object to
empty, delete or restore**, because Study-Agent never created one. A future backend that creates one
must pair every creation with the locked cleanup (`EmptyFilteredDeck` or deck removal) — ending a
Study-Agent session alone would leave cards in the filtered deck.

### DECISION-20-15 — External-change handling (locked)

If the backend's live listing stops reporting the deck, or reports it as filtered when the session was
not, or the session handle is refused, the session is **invalidated and surfaced** — never continued as
ordinary review. The binding-moment guard re-reads the listing immediately before `beginReview`, and the
executor refuses (typed error) instead of falling back (INV-20-18).

### DECISION-20-16 — Rating pipeline (locked)

GATE 11 remains the only rating transaction pipeline. A filtered/custom card still produces a normal
`ReviewCommitId` and goes through `prepareCommit → commitRating(+mutationEntry) → reconcileCommit`. The
only change is *context*: the session's mode travels with the session context, so a filtered rating is
classified by the backend's own evidence (today: `OutcomeUnknown`, which blocks progression — the
correct answer, not a bug to route around).

### DECISION-20-17 — ReviewTurn identity (locked)

`ReviewTurnId` is unchanged and shared: a filtered card produces an ordinary turn id. No separate
transaction identity family is created for custom study.

### DECISION-20-18 — Next-card rule (locked)

The next card comes only from `AnkiBackend.nextCard` on the session's locked backend. GATE 15 browse
results are browsing; on AnkiDroid the browser is refused outright
(`UnsupportedAnkiDroidCardBrowserGateway`), and on every path the custom-study layer contains no card
handling at all (test: `Gate20ArchitectureAuditTest`).

### DECISION-20-19 — Card-limit meaning (locked)

A "limit" in this product is a Study-Agent **stop condition** (`SessionTargetType`), never a backend
membership limit: the backend exposes none. The provider's `limit` argument is a row-fetch size and is
pinned to 1 per scheduler query by this app.

### DECISION-20-20 — Empty session meaning (locked)

"No due cards" (`NextCardResult.Finished`) is a **valid** end state, distinct from a failed start. A
refused start produces a typed error and no session.

### DECISION-20-21 — Capability model (locked)

`AnkiCustomStudyCapabilities` with 15 granular flags (see the type). Its constructor **refuses**
combinations the gate forbids: filtered review without a provable commit, filtered review without a
disclosed rescheduling behaviour, preview-only without no-reschedule, child-deck exclusion without deck
scoping. Defaults are all `false`, and `AnkiBackend.customStudyCapabilities` defaults to `NONE`.

Enabled at this pin: **none**. AnkiDroid's audited claim is `AnkiCustomStudyCapabilities.NONE`
(`AnkiDroidCustomStudyCapabilities.AUDITED`), with the evidence recorded in that file.

### DECISION-20-22 — UX safety disclosure (locked)

Before a session starts the surface states: the source deck, that descendants are included, that the
backend owns order and scheduling, and — for any non-normal mode — whether answers reschedule cards, in
those words. A deck that cannot be studied is blocked with the reason in the UI, not at the first
rating. Implemented by `CustomStudyPanel` on deck details.

### DECISION-20-23 — Lock result

See the report at the top of this file and `docs/GATE_20_FINAL_REPORT.md`.

---

## CONTRACT LOCK REPORT

```
GATE 20 — CUSTOM STUDY CONTRACT LOCK

RESULT:
LOCKED — for the audited scope (detection, refusal, disclosure, identity, capability truth).
Every custom-study *feature* (create / rebuild / empty / filter / order / limit / no-reschedule /
preview) is UNSUPPORTED at this pin and is not implemented.

SUPPORTED MODES:
- DECK_SCOPED_NORMAL_REVIEW (existing; deck + descendants; backend-ordered; backend-scheduled).
- None else. BACKEND_FILTERED_REVIEW and BACKEND_PREVIEW are modelled, capability-gated and disabled.

SCHEDULER AUTHORITY:
- One StudySession → one backend → one scheduler authority: the Anki backend's queue builder.
- Study-Agent selects, orders, limits and re-queues nothing.
- Unambiguous: yes. There is no code path in which Study-Agent decides which card comes next.

FILTER VOCABULARY:
- Deck scope (stable deck id). Descendants always included (backend fact, not an option).
- Everything else UNSUPPORTED and reported as such.

ORDERING:
- BACKEND_DEFAULT only. No local sort, ever.

RESCHEDULING:
- NORMAL_RESCHEDULING / NO_RESCHEDULING, never inferred, never conflated, no UNKNOWN value.
- Currently unreachable: no backend discloses it.

REVIEW HISTORY EFFECTS:
- Normal review: revlog + reps + lapses + FSRS + due, all backend-owned.
- Filtered modes (if enabled): as DECISION-20-07; AnkiDroid's observable subset is what GATE 11 verifies.

SESSION IDENTITY:
- StudySessionId ≠ backend filtered-deck id.
- AnkiSessionContext.studyMode: Normal | Filtered(deckRef, disclosure); immutable; not persisted.

SESSION LIFECYCLE:
- No invented lifecycle. Session exists / backend queue empty. Nothing else.

CREATE/RETRY POLICY:
- Nothing is created → nothing is retried. NO_BLIND_RETRY for every future creation path.

REBUILD POLICY:
- No rebuild. (Future: manual only, never automatic, never while active.)

END/CLEANUP POLICY:
- End = end the StudySession. No backend object exists to clean up because none was created.

RATING PIPELINE:
- GATE 11 unchanged and singular. Session mode travels as context; filtered commits stay
  AMBIGUOUS because the backend cannot prove them — progression stops, nothing is replayed.

NEXT CARD AUTHORITY:
- AnkiBackend.nextCard on the locked backend. GATE 15 results are never a queue.

CAPABILITIES:
- AnkiCustomStudyCapabilities: 15 flags, all false on AnkiDroid, defaults false everywhere.

UNSUPPORTED (locked as unsupported, never approximated):
- filtered/dynamic deck creation, rebuild, empty, delete, restore
- tag / state / flag / note-type / template / text / recency filters and card-id lists
- membership card limits, ordering selection, no-reschedule selection, preview-only selection
- cross-backend or cross-collection custom sessions; any session that survives a backend switch

RESIDUAL RISKS:
- See docs/GATE_20_FINAL_REPORT.md §8 (unparseable deck_dyn, PC backend absent, external mutation,
  sync, concurrent clients, preview FSRS bookkeeping).

IMPLEMENTATION AUTHORIZED: YES (contract-enforcing subset)
```

## What would widen this lock (not started)

1. A PC Anki backend is implemented **and** an audit shows it can prove a filtered rating commit
   (`canProveFilteredRatingCommit`) and disclose rescheduling before a start.
2. AnkiDroid exposes a public create/rebuild/empty surface — or the reserved-name path is proven safe
   end to end, including the "replaces the previous custom-study deck" behaviour.
3. `StudySessionBackendLock` is extended to persist a non-normal mode **and** refuses a restore that
   cannot reconstruct it (DECISION-20-09's contract note).
4. A creation ledger is designed and tested (DECISION-20-11) before any creation claim is enabled.
