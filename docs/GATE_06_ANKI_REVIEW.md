# GATE 06 — ReviewInfo & Scheduler Integration

Date: 2026-09-22
Gate: GATE 06
Status: Implemented (unit-level verified; real-device verification **NOT RUN**)
Integration target: `https://github.com/ankidroid/Anki-Android` @ tag `v2.24.1`
Depends on: GATE 03 (domain), GATE 04 (gateway/provider client), GATE 05 (decks)

---

## 1. Mission

Study-Agent can now ask the backend:

> "Give me the next card your scheduler says I should review in this deck."

and receive a backend-neutral review turn that carries **identity, scheduler metadata and media
references** — but not content, and not a rating.

It still cannot render a card, speak it, evaluate an answer or change a card's scheduling state.

```
User selects Deck
        │
        ▼
Study-Agent  ── beginReview(deckRef) ──▶ AnkiReviewSession (immutable binding)
        │
        │  nextCard(session)
        ▼
AnkiDroidBackend ──▶ AnkiDroidReviewGateway ──▶ content://<authority>/schedule
        │                                              │
        │                                     Anki scheduler result
        ▼                                              │
   AnkiReviewTurn ◀──── mapped AnkiScheduledCard ◀─────┘
        │
        ▼
StudySessionMachine (GATE 10)
```

---

## 2. Audit of the pinned AnkiDroid contract

### 2.1 What actually exists at v2.24.1

Verified by reading the tagged sources (not documentation, not memory):

| Artifact | Path at `v2.24.1` | Note |
|---|---|---|
| Provider contract | `api/src/main/java/com/ichi2/anki/FlashCardsContract.kt` | contains `object ReviewInfo` |
| Provider implementation | `AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt` | `SCHEDULE = 3000` |
| Scheduler | `libanki/src/main/java/com/ichi2/anki/libanki/sched/Scheduler.kt` | `nextIvlStr`, `nextIvl` |
| Rating enum | `proto/anki/scheduler.proto` (upstream Anki) | `CardAnswer.Rating` |
| `api` module members | `api/src/main/java/com/ichi2/anki/api/` | `AddContentApi`, `BasicModel`, `Basic2Model`, `Ease`, `NoteInfo`, `Utils` |

Two findings that change the integration shape:

1. **There is no `ReviewInfo` *class* in the `api` module at this version.** The review-info
   contract survives only as a nested `object` inside the provider contract. `AddContentApi` —
   read in full — exposes no schedule/scheduler member at all; the convenience wrapper that older
   documentation describes is gone.
2. Consequently the endpoint is reached the same way decks are: a raw, projected
   `ContentResolver.query` through `AnkiDroidProviderClient`, with the column names pinned as
   strings in `AnkiDroidApiContract`. No AnkiDroid class is linked or copied.

### 2.2 Endpoint

```
content://<authority>/schedule            FlashCardsContract.ReviewInfo.CONTENT_URI
                                          (= Uri.withAppendedPath(AUTHORITY_URI, "schedule"))
```

- **Arguments** are parsed out of the `selection` string: split on `,`, then on `=`. Recognised
  keys are `limit` and `deckID`; anything else is silently ignored, and an unparseable value is
  caught and logged while the previous default stands.
- A value of `?` is replaced by the next `selectionArgs` entry, in order. Study-Agent supplies
  literal values and passes `selectionArgs = null`, so the two can never desynchronise.
- `sortOrder` is ignored by the endpoint; `null` is passed.
- Defaults: `limit = 1`; a missing `deckID` means "whatever deck AnkiDroid currently has selected".
- No provider-spec gate exists on this endpoint at v2.24.1 (the provider file contains no
  `requireApiLevel` call), so the spec policy from GATE 02 applies unchanged.
- Permission: the same `READ_WRITE_PERMISSION`, enforced on `query`.

### 2.3 Columns consumed

| Column | Transported as | Meaning | Required |
|---|---|---|---|
| `note_id` | number → text | the note the card belongs to | yes (identity) |
| `ord` | number → text | the card ordinal inside that note | yes (identity) |
| `button_count` | number → text | how many rating buttons the scheduler offers | yes (semantics) |
| `next_review_times` | `JSONArray` → **text** | one interval label per button | optional (display) |
| `media_files` | `JSONArray` → **text** | filenames the card references | optional (display) |

Write-only columns on the same URI — `answer_ease`, `time_taken`, `buried`, `suspended` — are not
named anywhere in this gate's code. A source scan enforces that.

An unknown projection column makes the endpoint **throw**
(`UnsupportedOperationException("Queue \"<col>\" is unknown")`) rather than skip it, unlike the
deck listing. Only the five columns above are ever requested.

`button_count` is **hard-coded to `4`** by the provider at this version. The domain still models
the full space (§20), because the *contract* documents 2..4 and a future provider may honour it.

### 2.4 Interval-label order (verified, not assumed)

The provider fills the label array with

```kotlin
nextIvlStr(card, CardAnswer.Rating.forNumber(i))     // i in 0 until buttonCount
```

and the pinned proto enum is

```proto
enum Rating { AGAIN = 0; HARD = 1; GOOD = 2; EASY = 3; }
```

so index `0..3` is **again/hard/good/easy** for the four-button case. Any other button count is
mapped to `AnkiRatingOptions.Unmapped` and loses its labels, because the endpoint does not say
which two of the four a bare `2` means, and inventing that mapping would be inventing scheduling.

---

## 3. Verified scheduler semantics

The audit §3 of the brief asks what the query *does*. Answers below are from source, with the
places to look named.

| Question | Verified answer | Evidence |
|---|---|---|
| Does requesting the next card mutate review history? | **No.** The read path fetches the queue; the answer path (`answerCard`, `buryCards`, `suspendCards`) lives only in the `update` branch. | `CardContentProvider.query` `SCHEDULE` vs `update` `SCHEDULE` |
| Does a second read before rating return the same card? | **The same queue is rebuilt and returned**, so the same card comes back — except that ties among equally due cards may be broken differently. AnkiDroid's own test loops up to ten times to find the card because "`sched.reset()` randomly chooses between multiple cards". | `ContentProviderTest.testQueryNextCard` |
| Provider-side current-card state? | **None a caller can depend on.** The query does temporarily `select` the requested deck and restore the previous selection — a persisted, undoable collection-config write (`Op::SetCurrentDeck`). The restore is the last statement of the branch, not a `finally`. | `CardContentProvider` `SCHEDULE` branch; `Collection.set_current_deck` |
| What does `limit` mean? | The maximum number of **rows**. The provider forwards it to the queue fetch and stops after `limit` rows. `limit = 0` reaches the queue builder as `take(0)`, i.e. "nothing due" — which is why Study-Agent rejects non-positive limits instead of forwarding them. | `ReviewInfo` KDoc; `getQueuedCards(fetch_limit)` → `queues.iter().take(fetch_limit)` |
| How are parent decks handled? | By Anki's queue builder, which gathers from the selected deck and its active descendants. Study-Agent passes the deck the user chose and performs no inclusion, exclusion or ordering of its own. | `QueueBuilder` gathering; the provider selects the deck then calls `getQueuedCards` with no deck argument |
| Unknown `deckID`? | The endpoint returns an **empty cursor** — indistinguishable from "nothing due". | `if (!selectDeckWithCheck(col, deckId)) return rv` |

### 3.1 The one ambiguity, and how it is resolved

Because "deck missing" and "nothing due" produce the same answer, Study-Agent does not treat an
empty cursor as proof. `AnkiDroidBackend.nextCard()` re-checks the deck against the deck listing
when — and only when — the scheduler answers empty, and reports `DeckNotFound` if the deck is gone.
That keeps both §32 (exhausted deck is a valid end state) and §46 (deleted deck is a typed failure)
true at the same time, at a cost of one extra query at session end.

### 3.2 What cannot be detected

AnkiDroid's public provider exposes **no collection identity** (recorded in GATE 05). A profile or
collection switch therefore cannot be detected *by identity*. What is detected, and what the tests
assert, is every consequence that matters: the availability state changes to a typed failure, and
deck references stop resolving. `AnkiSessionContext.collection` remains `null` and reserved for the
day an identity appears.

---

## 4. Domain flow

```
AnkiDeckRef
    │  BeginReviewRequest(context(deckRef), limit?)
    ▼
AnkiReviewSession            immutable: backend + collection + deck + capabilities + studySessionId
    │  nextCard(session)
    ▼
AnkiDroidReviewGateway       schedule endpoint, projection, selection, row policy
    │
    ▼
AnkiScheduledCard            ref (noteId + ord), deckRef, ratingOptions, scheduling, media, degradations
    │  + new ReviewTurnId (Study-Agent's own identity)
    ▼
AnkiReviewTurn(content = Scheduled(card), position, …)
```

`AnkiReviewTurnContent` distinguishes the two phases: `Scheduled` (identity only, GATE 06) and
`Rendered` (content, GATE 07+). Nothing constructs an `AnkiRenderedCard` with placeholder
question/answer strings, because "the answer is genuinely empty" and "the answer is not loaded
yet" must not look the same.

### 4.1 Turn identity vs card identity

| | Owner | Lifetime | Reused? |
|---|---|---|---|
| `AnkiCardRef` (`noteId` + `cardOrd`) | AnkiDroid | the card's persistence | yes — the same card recurs across sessions |
| `ReviewTurnId` | Study-Agent | one presentation | **never** — the same card appearing again is a new turn |

`ReviewTurnId` gains authority only *after* a scheduled card has been accepted as current, so a
failed provider read leaves no orphaned turn identity behind. Ids are minted by an injectable
`ReviewTurnIdSource` (default: instance prefix + monotonic counter), never by a bare random call.

---

## 5. Scheduler metadata support

| Fact | Support | Notes |
|---|---|---|
| Button count | mapped | `Known([again, hard, good, easy])` for 4; `Unmapped(n)` otherwise — never guessed |
| Rating options | mapped | `AnkiRatingOptions`; UI must render what it is given and never assume four buttons |
| Next review times | mapped as display metadata | `AnkiSchedulingInfo.nextReviewTimes`; a label/count mismatch drops the labels and records a token |
| Media references | preserved | filenames → `AnkiMediaRef.BackendStream`; nothing is opened, resolved or copied |
| Card type (new/learning/review) | **not** inferred | the endpoint does not report it, and guessing from `1m`/`1d` is forbidden |

---

## 6. Concurrency

- **One active turn per session.** `AnkiDroidBackend` holds one `ReviewSessionRecord` per open
  session, and that record holds at most one unresolved `AnkiReviewTurn`.
- **Duplicate `nextCard()`** returns the *same* turn. The scheduler is not consulted again, so a
  double tap, a voice command and a reconnect callback converge on one card.
- **Serialization** is a single mutex spanning "read the session, ask the scheduler, install the
  turn". Two callers cannot interleave, which is what makes "no stale installation" structural
  rather than a hopeful check.
- **Stale results** are further refused by identity: a handle this backend did not issue (a
  previous process instance, a different backend) is rejected with `SessionInvalid` before any
  provider traffic.
- **Concurrent provider entry** is additionally excluded inside the gateway, so one logical
  `nextCard()` is one provider query — asserted by counting queries, not by inspection.
- **Cancellation** propagates untouched, and leaves no lock held and no turn installed.

---

## 7. Error mapping

| Situation | Domain outcome |
|---|---|
| Foreign backend deck ref | `AnkiError.InvalidRequest("review_deck_foreign_backend")` — before any provider call |
| Unmappable deck id | `AnkiError.InvalidRequest("deck_id_unmappable")` |
| Limit ≤ 0 or above the maximum | `AnkiError.InvalidRequest("review_limit_not_positive" / "review_limit_above_maximum")` |
| Deck not in the collection | `AnkiError.DeckNotFound(deckRef)` |
| Deck deleted mid-session | `AnkiError.DeckNotFound(deckRef)`; the session record is dropped |
| Permission revoked | `AnkiError.PermissionRequired` (via `AnkiAvailability`), session preserved |
| Provider/collection unavailable | `AnkiError.ProviderUnavailable` / `AnkiError.CollectionUnavailable` |
| Provider query failed | mapped through the existing `AnkiDroidErrorMapper` (`QueryFailure`, `PermissionRequired`, `UnsupportedApi`, …) |
| Malformed identity (unreadable note id / ordinal) | `AnkiError.MalformedResponse("review_note_id_invalid" / "review_card_ord_invalid")` |
| Missing required column | `AnkiError.MalformedResponse("review_<col>_column_missing")` |
| Impossible button count | `AnkiError.MalformedResponse("review_button_count_invalid")` |
| Malformed interval labels | **degraded**, never fatal: labels dropped, token recorded |
| Malformed media list | **degraded**, never fatal: no media, token recorded |
| Scheduler has nothing | `NextCardResult.Finished` — a success, not an error |
| Rating commit | `CommitRatingResult.Rejected(UnsupportedAction("ratingCommitIntegrationPending"))` — truthful, until GATE 11 |

Token vocabulary is stable and content-free; no provider message, path or card text crosses the
boundary.

---

## 8. Diagnostics

Events (`AppLogger`, content-free identifiers only):

```
ANKI_REVIEW_SESSION_STARTED      deck, limit
ANKI_NEXT_CARD_REQUESTED         deck, limit
ANKI_NEXT_CARD_AVAILABLE         button count, media count, duration
ANKI_REVIEW_QUEUE_EMPTY          deck, duration
ANKI_REVIEW_SESSION_FINISHED     deck, presented count
ANKI_NEXT_CARD_FAILED            error category
ANKI_REVIEW_DECK_GONE            deck
ANKI_REVIEW_SESSION_ENDED        session ref
ANKI_RATING_COMMIT_REFUSED       —
```

Measurements: begin latency, provider query duration and review mapping duration are recorded per
call (`AnkiDroidReviewQueryDiagnostics`, `AnkiReviewDiagnostics`). A `providerQueryCount` makes
"one logical `nextCard()` = one provider query" observable rather than assumed.

Never logged: question, answer, note fields, media filenames, interval labels, collection paths.
Note ids, ordinals and deck ids appear because they are opaque backend numbers and are what makes a
session followable in support.

---

## 9. Invariants

| ID | Contract |
|---|---|
| **INV-ANKI-REV-01** | Only AnkiDroid's scheduler decides the next scheduled card. |
| **INV-ANKI-REV-02** | Study-Agent never reconstructs Anki scheduling locally. |
| **INV-ANKI-REV-03** | One active review session has at most one active uncommitted turn. |
| **INV-ANKI-REV-04** | Every presented scheduled card receives a unique `ReviewTurnId`. |
| **INV-ANKI-REV-05** | The same card may legitimately appear in multiple distinct turns. |
| **INV-ANKI-REV-06** | A no-card result is distinct from a backend failure. |
| **INV-ANKI-REV-07** | The review query performs no rating mutation. |
| **INV-ANKI-REV-08** | A current scheduled card is not replaced by a second scheduler query before its turn is resolved. |
| **INV-ANKI-REV-09** | Rating options come from scheduler/provider semantics, never from hard-coded UI assumptions. |
| **INV-ANKI-REV-10** | Review interval labels are presentation metadata, not scheduling logic. |
| **INV-ANKI-REV-11** | An active session never silently changes backend or deck. |
| **INV-ANKI-REV-12** | All provider resources are closed before the gateway returns. |
| **INV-ANKI-REV-13** | Cancellation never becomes a domain failure. |
| **INV-ANKI-REV-14** | PC connectivity is not required for local scheduler access. |
| **INV-ANKI-REV-15** | GATE 06 performs zero review-rating mutations. |

---

## 10. Deliberate deferrals

- `AnkiReviewSession.finishReason` — the difference between "the scheduler ran out" and "the
  user's own session limit was reached" is modelled as session *data*
  (`AnkiReviewSessionProgress.schedulerExhausted`); a `NextCardResult` variant cannot be reached
  until a turn can be resolved, so adding one now would be untestable decoration.
- Process-death recovery — identity is sufficient to reconstruct a session later, but reconciling
  "was a card presented and not rated?" needs the commit path (GATE 11).
- Full `StudySessionMachine` wiring — GATE 10.
- A debug harness screen (§149/§150) — the backend exposes `reviewDiagnostics()`,
  `reviewProgress()` and the gateway's diagnostics, which a debug screen can read; no UI was added
  in this gate, so nothing in production can rate a card.

---

## 11. Tests

| Suite | Covers |
|---|---|
| `AnkiDroidReviewJsonTest` | JSON-array text parsing: escapes, `null` elements, empty array vs garbage |
| `AnkiDroidReviewMapperTest` | every row fixture in §100 — 4-button, 2-button, no/multiple media, malformed identity, malformed/mismatched intervals, missing columns, collection identity |
| `AnkiDroidReviewGatewayTest` | endpoint + projection pinning, argument construction, limit validation, one-provider-query-per-call, empty vs failure, error mapping, serialization, diagnostics privacy |
| `AnkiDroidReviewSessionTest` | begin/next lifecycle, idempotency, foreign/absent deck, idempotent and conflicting opens, single active turn, 20-way concurrency, backend/permission/collection loss, deck deletion, end session, cancellation, no-mutation |
| `AnkiScheduledReviewContractTest` | the same scheduled-review contract run against **both** the AnkiDroid backend and the test stand-in (parity, §101/§167) |
| `AnkiDroidIntegrationIsolationTest` | source scans: no mutation tokens, no scheduler reimplementation, no prefetch, no endpoint vocabulary above the layer, no calendar arithmetic in either Anki layer |
| `AnkiDomainModelTest`, `FakeAnkiBackendTest`, `AnkiDroidIntegrationStateTest`, `AnkiDroidCapabilityProbeTest` | domain invariants, stand-in parity, capability-matrix truthfulness |

**Not run in this gate: the real-AnkiDroid instrumented suite** (`§105-§109`, `§152-§158`). The
sandbox has no JDK, no Android SDK and no network route to the toolchain or Maven Central (the
same constraint GATE 00/04/05 recorded as `BLOCKED_BY_ENVIRONMENT`), so **no real scheduler
behaviour was executed**. Everything in §3 is read from the pinned sources and is marked as such.
