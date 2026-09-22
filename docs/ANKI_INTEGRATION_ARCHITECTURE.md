# Anki Fusion Architecture — Source-of-Truth Contract

**Gate:** GATE 01 — Anki Fusion Architecture & Source-of-Truth Contract
**Date:** 2026-09-22
**Status:** Ratified architecture contract. Normative for every later Anki gate.
**Integration target:** `https://github.com/ankidroid/Anki-Android`
**Scope of THIS gate:** architecture + ownership contract + minimal domain contract
types (`core/anki/`). **No** AnkiDroid dependency, **no** ContentProvider queries,
**no** permission flow, **no** changes to the running study behavior.
Production code in this repository is authoritative over any older document;

this document is authoritative over all *future* Anki integration work.

---

## 0. Purpose

This contract answers two questions, once, for every later gate:

1. **Which subsystem owns each piece of data, state, behavior and side effect?**
2. **How can Study-Agent use AnkiDroid without creating a second Anki scheduler,
   duplicate review history, stale-card races or competing sources of truth?**

The non-negotiable principle (INV-ANKI-04 and friends, §18):

> Study-Agent is NOT an Anki implementation. Anki scheduling, FSRS, due
> calculation, review history, the collection and Anki sync remain owned by the
> selected Anki backend. Study-Agent owns the intelligent interaction layer.

If any later gate finds itself violating this document, the correct move is to
re-open this contract — not to violate it quietly.

---

## 1. Architecture audit (as-is, from production code)

### 1.1 What exists today

| Area | Today | Where |
|---|---|---|
| Session authority | `StudySessionMachine` — single serialized event loop; reducer = pure state owner; effects carry I/O intent | `core/study/` |
| Session transport | `ConnectionRepository` over `AgentConnection` (WebSocket / Fake) | `data/repository/`, `core/network/` |
| Cards | `StudyCard(id, question, cardNumber, remaining, deckName)` — flat text, protocol-shaped | `core/models/StudyCard.kt`, `ProtocolMessages.kt` (`ServerMessage.Question`) |
| Rating | `Rating(AGAIN/HARD/GOOD/EASY)` — domain-neutral 4-button rating | `core/models/Rating.kt` |
| Review-turn identity | `CardTurn.turnId` = server `review_turn_id` (v2) else `epoch:cardId:generation` | `core/study/CardTurn.kt` |
| Exactly-once | `SubmissionLedger` (per-turn answer/rating state) + `PendingAction` timeouts + `recentServerMessageIds` dedup | `core/study/` |
| Reconciliation | `SessionReconciler` + `StudySnapshot` against PC protocol snapshots | `core/study/` |
| Decks | `DeckSummary(name, due/new/learning/total counts)` via `request_decks`; cached in `ManagementCacheStorage`; picked in `DeckPicker` | `data/repository/DashboardRepository.kt`, `core/models/DashboardModels.kt` |
| Session start | `HomeViewModel.startStudy()` refuses when `!connectionState.value.isConnected`; reducer sends `ClientMessage.StartSession` | `ui/screens/home/HomeViewModel.kt`, `core/study/StudyReducer.kt` |
| Diagnostics | `DiagnosticTimeline`, `PerformanceMetrics`, machine resource snapshot | `core/diagnostics/` |

### 1.2 The current study flow (PC-bound)

```
Start Study (deck name + mode from Control Center)
  ↓ ClientMessage.StartSession  ── PC Agent ──► session_started
  ↓ Question{cardId, question, review_turn_id}          (PC owns the queue)
SpeakingQuestion → WaitingForAnswer (STT)
  ↓ ClientMessage.SubmitAnswer   ── PC Agent ──► evaluation{score, suggested_rating}
SpeakingFeedback → WaitingForRating
  ↓ ClientMessage.RateCard       ── PC Agent ──► rating_saved → next Question
  …
SessionFinished / Recovery via RequestSessionSnapshot
```

### 1.3 Embedded assumptions (to be lifted in later gates, NOT silently now)

Today the design assumes, end to end, that **the PC Agent owns cards, rating and
session state**:

1. Cards arrive only as protocol messages; there is no local card source.
2. `startStudy()` is impossible without a PC connection (`HomeViewModel.startStudy`
   returns `false` when `!isConnected`).
3. Any connection loss during any active session forces `Recovering`
   (`StudySessionMachine.observeConnection`) — correct for PC study, wrong for a
   local AnkiDroid session.
4. Rating commits are protocol sends (`ClientMessage.RateCard`); the ledger
   tracks *transport* acknowledgements, assuming the PC ACKs mean "applied".
5. Deck identity is the deck **name** (`DeckSummary.name`, `DeckPicker`).
6. There is no note/card distinction, no card HTML, no media, no collection
   identity — because `StudyCard` was designed as a rendering of the protocol's
   `Question` message, not as an Anki entity.

These assumptions are now **named**, and §22 maps each to a future gate.

---

## 2. Target system model

### 2.1 The model

```
                         STUDY-AGENT (this app)
                              │
        ┌─────────────────────┼────────────────────────┐
        │                     │                        │
        ▼                     ▼                        ▼
  Study Session           Voice subsystem            AI evaluation
  Machine (UI flow)      (TTS / STT / audio)        (suggestion only)
        │                     │                        │
        └─────────────────────┼────────────────────────┘
                              │
                              ▼
                    Anki domain layer (core/anki)
                    backend-neutral types only
                              │
                              ▼
                      AnkiBackend (gateway)
                ┌─────────────┴─────────────┐
                ▼                           ▼
       AnkiDroidBackend (GATE 04)     PcAnkiBackend (GATE 04/06)
                │                           │
                ▼                           ▼
   AnkiDroid public integration      PC Study Agent (existing
   API (ContentProvider/API)         WS protocol) → AnkiConnect
                │                           │
                ▼                           ▼
        AnkiDroid collection          Anki Desktop collection
```

### 2.2 What this changes and what it does not

- The **existing PC flow stays valid**. `PcAnkiBackend` adapts today's protocol
  behavior into the gateway; today it is the only implemented backend.
- **StudySessionMachine remains the sole owner of user-flow state.** The Anki
  backend is an *effect dependency* the machine calls — never a second
  state-machine owner of the UI (see §10).
- **AI/TTS/STT providers are independent of the Anki backend** (§12). AnkiDroid
  local review + Android TTS + no PC is a valid configuration; AnkiDroid cards +
  PC LLM evaluation is equally valid.

### 2.3 Review-turn lifecycle ownership

```
Anki backend                    Study-Agent                     Anki backend
    │                               │                               │
    │ ── due card (one turn) ─────► │                               │
    │                               │ speak question (TTS)          │
    │                               │ listen (STT)                  │
    │                               │ evaluate (AI, advisory)       │
    │                               │ explain / hint / feedback     │
    │                               │ ask user for rating           │
    │ ◄── final rating (ONE commit) │                               │
    │ ── next due card ───────────► │                               │
```

Anki owns **which card is due and what a rating means**. Study-Agent owns
**everything that happens while the user interacts with that card**. This
boundary is the heart of the contract.

---

## 3. Source-of-truth ownership

> This table is a contract, not documentation trivia. Any later gate that needs
> to move a row must amend THIS table in the same change.

| Domain | Authoritative owner | Notes |
|---|---|---|
| Card content | Selected Anki backend | Rendered HTML + normalized text (§9) |
| Note content | Selected Anki backend | Notes ≠ cards (§13.4) |
| Decks | Selected Anki backend | Identity = backend-qualified id, not name |
| Card due state | Selected Anki backend | Never recomputed client-side |
| Scheduling | Selected Anki backend | FSRS/SM-2 lives in Anki |
| FSRS | Selected Anki backend | Not re-implemented, not approximated |
| Review history | Selected Anki backend | No shadow history locally |
| Rating commit | Selected Anki backend | ONE commit per review turn (INV-02) |
| Anki media | Selected Anki backend | Consumed by ref, never copied (§9.4) |
| Anki sync | AnkiDroid / Desktop Anki | Never Study-Agent (§13.6) |
| Active Study-Agent session | Study-Agent | Session machine (existing) |
| Voice state | Study-Agent | Existing voice subsystem |
| STT transcript | Study-Agent | Transient per turn |
| TTS playback | Study-Agent | Transient |
| AI evaluation | Study-Agent / PC Agent | Advisory; content input only |
| Suggested rating | AI evaluator | NEVER auto-committed (INV-05/13) |
| Final committed rating | Study-Agent → selected Anki backend | User authority (INV-13) |
| Weak-topic analytics | Study-Agent | Derived, separate store (future) |
| Mistake notebook | Study-Agent | References `AnkiCardRef` (§13.7) |
| Study recommendations | Study-Agent | Derived |
| Connection state | Study-Agent | Existing `ConnectionRepository` |
| Provider health | Study-Agent / PC Agent | `component_health` today |

---

## 4. Data classification: AUTHORITATIVE / DERIVED / CACHE / TRANSIENT

| Class | Definition | Examples | Rule |
|---|---|---|---|
| AUTHORITATIVE | The single owner of truth | Card due date, review history, scheduler state, collection | Anki owns; may never be written by Study-Agent caches (INV-09) |
| DERIVED | Computed from authoritative data | AI evaluation, weak-topic score, speech latency stats | Study-Agent owns; annotates, never overwrites Anki facts |
| CACHE | Read-through copies with a freshness class | Deck list (short-lived), rendered card for the ACTIVE turn (turn-scoped), media (bounded), deck counts (refreshable) | Never becomes scheduling authority (INV-09) |
| TRANSIENT | Dies with its turn/episode | Partial STT transcript, TTS playback state, active voice turn, route state | Never persisted as truth |

**Freshness requirements (§25):**

| Datum | Rule |
|---|---|
| Rating commit | Never cached as authoritative; exactly-once by commit id |
| Next due card | Always requested from the scheduler through the session |
| Card HTML/text for the ACTIVE turn | Safe to cache for the turn's lifetime |
| Deck list | Short-lived cache (existing `ManagementCacheStorage`) |
| Deck counts | Refreshable; display may show `asOfEpochMs` staleness |
| AI evaluation | Turn-scoped |

---

## 5. Identity model

Identifiers are the backbone of split-brain prevention. All identity types
exist as code in `core/anki/` (this gate).

### 5.1 Backend identity — `AnkiBackendId`, `AnkiBackendMode`

```kotlin
enum class AnkiBackendId      // ANKIDROID_LOCAL, PC_AGENT — concrete, resolved
enum class AnkiBackendMode    // AUTO, ANKIDROID_LOCAL, PC_AGENT — user preference
```

Internal terminology is fixed (§38): `ANKIDROID_LOCAL` / `PC_AGENT`. User-facing
labels: "AnkiDroid on this phone" / "Desktop Anki through Study Agent".
Ambiguous bare labels ("Local", "Remote", "Server") are forbidden in new code.

### 5.2 Session-bound context — `AnkiSessionContext`

```kotlin
data class AnkiSessionContext(
    val backendId: AnkiBackendId,          // resolved effective backend (never AUTO)
    val collection: AnkiCollectionIdentity?,
    val deckRef: AnkiDeckRef,
    val startedAtEpochMs: Long,
    val capabilities: AnkiCapabilities,
    val studySessionId: String?
)
```

Created **exactly once at session start** by resolving the preference, and locked
for the session (§41, INV-01/07). Every Anki operation of the session carries it.

### 5.3 Collection / deck / note / card identity (§9, §24, §49, §50)

```kotlin
data class AnkiCollectionIdentity(val backendId, val collectionKey: String?)   // opaque token
data class AnkiDeckRef(val backendId, val deckId)        // never the display name
data class AnkiNoteRef(val backendId, val noteId, val collectionKey: String?)
data class AnkiCardRef(val backendId, val cardId: String?, val noteId: String?,
                       val cardOrd: Int?, val collectionKey: String?)
```

Rules enforced by construction:

- **Backend-qualified everything.** `cardId` alone is NOT a global identity.
  `card 123` on AnkiDroid and `card 123` on a disconnected Desktop collection are
  different logical cards in possibly different states. Identity types make a
  cross-backend comparison structurally impossible (INV-06).
- **Never fabricate identifiers.** A ref may omit `cardId` when the backend can
  only address `note + ord`; the type *requires* one usable form
  (`cardId`, or `noteId + cardOrd`) and throws otherwise.
- **Note ≠ Card.** One note generates many cards; both refs exist separately.
- **Deck identity is an id.** Display name lives on `AnkiDeck`; renames and
  nested `Parent::Child` paths cannot corrupt identity.
- **Collection qualification.** When a backend can prove which collection it
  serves, `collectionKey` distinguishes two collections on the same backend.
  When it cannot (yet), the field stays null and reconciliation treats identity
  as unproven rather than guessing (§11.4).

### 5.4 Review-turn identity and commit identity (§10, §30)

```kotlin
data class ReviewTurnId(val value: String)     // turn ≠ card
data class ReviewCommitId(val backendId, val studySessionId, val turnId: ReviewTurnId)
```

- One card CAN appear more than once. `Card A @ turn 101` and
  `Card A @ turn 233` are distinct turns and distinct commits.
- `ReviewTurnId` wraps the existing machine-level turn identity
  (`CardTurn.turnId`: server `review_turn_id` when provided, else
  `epoch:cardId:generation`). The Anki layer adds the *commit* concept on top.
- `ReviewCommitId.stableKey` = `backend | study session | review turn`. It is
  **never** derived from the card id alone (a card may be reviewed many times).

---

## 6. Anki write authority

### 6.1 The invariant

> **One review session has exactly one writable Anki backend.** (INV-ANKI-01)

At session start, `AnkiBackendSelector` resolves
`AnkiBackendMode → AnkiBackendId` **once**, per policy below, and the result is
frozen into `AnkiSessionContext`. That context is the session's write lock.

### 6.2 AUTO selection policy (§39) — decision and rationale

Implemented in `core/anki/AnkiBackendSelector.kt` as a pure, tested function:

```
AUTO:
  ANKIDROID_LOCAL implemented AND review-ready?  ──yes──► ANKIDROID_LOCAL
  PC_AGENT implemented AND review-ready?         ──yes──► PC_AGENT
  otherwise ──► Unavailable (no session on Anki data)

Explicit mode: pinned backend implemented AND review-ready ──► Resolved
               otherwise ──► Unavailable (fail CLOSED — never substitute)
```

**Rationale for AnkiDroid-first AUTO order:** the local path works offline, has
the fewest moving parts (no network, no agent, no AnkiConnect), and keeps the
ownership boundary inside one process. The PC path remains preferred *explicitly*
or whenever local Anki cannot review. Two constraints temper the order:

1. Only backends **implemented in this build** are candidates — so today AUTO
   still resolves `PC_AGENT` (backward compatibility), and the day the
   AnkiDroid backend lands, AUTO starts preferring it without a settings change.
2. `review-ready` requires `AnkiAvailability.Ready` **and**
   `capabilities.review == true` — a deck-browsable-but-not-reviewable backend
   must not receive a rating-writing session.

### 6.3 No silent mid-session backend switch (§6, INV-ANKI-07)

```
Session starts on ANKIDROID_LOCAL → context locked
AnkiDroid becomes unavailable mid-session:
    DO NOT switch rating commits to PC_AGENT in this session.
    INSTEAD: controlled transition → AnkiUnavailable / Recovering / Paused.
Next session (after explicit restart) resolves the backend freshly.
```

Backend **re**-selection happens between sessions, never inside one.

### 6.4 Why hot failover is dangerous (§7) — the canonical scenario

```
Card A loaded from AnkiDroid
  → user answers
  → AnkiDroid disappears
  → PC Agent becomes available
  → rating sent to Desktop Anki        ← UNSAFE
```

Unsafe because: card identities may differ (backend-raw ids are not portable),
collection state may differ (unsynced collections diverge), scheduler state may
differ (the two Ankis disagree about what is due), and review history may
diverge (the same answer recorded in two collections = split-brain). **Failover
is allowed between sessions, never blindly inside one review transaction.**

### 6.5 Backend selection lifetime (§41)

```
resolve (session start) → AnkiSessionContext
      │ locked until: session finishes | session explicitly restarted
      ▼
never re-resolved by availability changes, provider changes or connection flaps
```

---

## 7. Rating authority (§12-§13)

### 7.1 Three distinct ratings — never one field

| Concept | Type (contract) | Owner | Producer |
|---|---|---|---|
| AI **suggested** rating | `SuggestedRating` = `Evaluation.suggestedRating` (existing) | AI evaluator | LLM/rules |
| User **selected** rating | `SelectedRating` (`CommitRatingRequest.rating`) | User | tap/spoken/click |
| Anki **committed** rating | `CommittedRating` = scheduler accept (CommitStatus.COMMITTED) | Anki backend | scheduler |

- `AI suggested GOOD` must not become `Anki commit GOOD` by itself (INV-05/13).
- A future trusted auto-rating mode may exist ONLY as an explicit user setting;
  it is out of scope for initial integration.

### 7.2 Default human-authority policy (§13)

```
AI evaluates → AI suggests → user confirms (tap / speaks "good" / clicks)
            → Study-Agent commits (one commit id) → Anki schedules
```

No fully automatic scheduling from LLM output by default.

### 7.3 The rating transaction (§27-§29)

```
Card loaded → turn starts → user interacts → rating chosen
  → commit sent (ReviewCommitId) → commit acknowledged
  → ONLY THEN next card requested
```

**Transaction boundary:** the next card is never requested/activated before the
rating commit has a *deterministic* outcome.

**Commit outcomes (`CommitStatus`, §28):**

| Status | Meaning | Session behavior |
|---|---|---|
| COMMITTED | Mutation applied + acknowledged | Advance to next turn |
| REJECTED | Deterministically refused pre-mutation | Surface; do NOT auto-retry |
| FAILED_SAFE_TO_RETRY | Mutation provably never happened | Retry with THE SAME commit id |
| AMBIGUOUS | Outcome unknown (e.g. write issued, no ACK) | STOP progression; reconcile before any next card (INV-08) |

`AMBIGUOUS` is the load-bearing case:

```
commit sent → backend applies rating → process/network dies → no ACK
Blind resend = double-commit risk. Study-Agent must not do it.
```

**Exactly-once (INV-02, §29):** one ReviewTurn causes at most one scheduling
mutation. Mechanism, per backend (implemented in later gates):

- PC Agent: idempotency key = `ReviewCommitId.stableKey` attached to the
  commit; agent deduplicates (extends the existing `message_id` discipline).
- AnkiDroid: local **commit ledger** (committed/ambiguous sets, keyed by
  `ReviewCommitId`) + reconciliation query before advancing (card already
  answered ⇒ treat as committed) — GATE 06 owns this.
- GATE 01 defines the invariant; later gates implement it and the **contract
  tests** (§16.2) verify every backend against it.

---

## 8. The `AnkiBackend` contract (§14-§17)

Final contract — designed from THIS repository's needs (`Rating` reuse, turn
identity reuse, `Result`-style outcomes). See code for the normative shape:
`core/anki/AnkiBackend.kt`.

```kotlin
interface AnkiBackend {
    val id: AnkiBackendId
    val availability: StateFlow<AnkiAvailability>
    val capabilities: StateFlow<AnkiCapabilities>

    suspend fun getDecks(): Result<List<AnkiDeck>>
    suspend fun getDeckSummary(deck: AnkiDeckRef): Result<AnkiDeckSummary>
    suspend fun beginReview(request: BeginReviewRequest): Result<AnkiReviewSession>
    suspend fun nextCard(session: AnkiReviewSession): Result<AnkiReviewTurn?>
    suspend fun commitRating(request: CommitRatingRequest): Result<CommitRatingResult>
    suspend fun bury(request: CardActionRequest): Result<CardActionResult>
    suspend fun suspendCard(request: CardActionRequest): Result<CardActionResult>
}
```

### 8.1 Boundary hygiene (§18-§20, INV-ANKI-06)

- Above the gateway: **no** `FlashCardsContract`, `Cursor`, `ContentResolver`,
  `AddContentApi`. Those are confined to `data/anki/ankidroid/` (future).
- Above the gateway: **no** `ProtocolMessage` / `ServerMessage` / socket types.
  `PcAnkiBackend` translates protocol → domain inside `data/anki/remote/`.
- Domain models are backend-neutral by construction — an `AnkiCard` shaped like
  `data class AnkiCard(val ankiDroidNoteId: Long)` is a contract violation.
  Backend-specific fields belong in backend-private metadata.

### 8.2 Deliberate minimalism (§15, §75)

This is NOT a god interface. Absent on purpose: sync, statistics, templates,
media browsing, note editing, collection search, import/export — all either
delegated (§13) or future capability interfaces. Documented decomposition if
growth demands it: `AnkiReviewBackend`, `AnkiDeckSource`, `AnkiNoteEditor`,
`AnkiSearchSource`. Not before. No `AnkiDeckRepository` /
`AnkiCardRepository` layer is added on top; the gateway IS the boundary
(§75 — no repository explosion).

### 8.3 Capability model (§16, §72)

`AnkiCapabilities(review, deckListing, renderedCards, media, flags, bury,
suspendCards, editNotes, createNotes, search)` — flat booleans, default
`false` (the safe answer). UI derives affordances **only** from capabilities,
never from the backend's name. Fallback semantics: review without
`editNotes` ⇒ study works, the Edit affordance hides and "Open in AnkiDroid"
shows instead. One unsupported optional feature never marks the whole backend
unavailable.

### 8.4 Availability model (§17, §71)

Unified `AnkiAvailability` (sealed): `NotInstalled`, `PermissionRequired`,
`CollectionNotInitialized`, `Ready(capabilities)`, `TemporarilyUnavailable`,
`AgentDisconnected` (PC), `AgentAnkiUnavailable` (PC agent up, Anki beneath it
down), `Unsupported`, `Fault(error)`. Health is plural, never `connected=true`:
local = installed + permission + provider + collection + review-capable; PC =
agent connected + agent Anki adapter + Anki reachable. `isReadyForReview` is
the single gate for session starts.

### 8.5 Domain errors (§36-§37)

`AnkiError`: `BackendUnavailable`, `PermissionRequired`, `CollectionUnavailable`,
`DeckNotFound`, `CardNotFound`, `CommitConflict`, `UnsupportedAction`,
`MediaUnavailable`, `Unknown`. UI never parses SQLite/Cursor/HTTP text.
Recovery classification is per failure (see Failure Ownership Matrix §20.3);
`asCommitFailureClass()` gives the conservative commit mapping
(`Unknown ⇒ AMBIGUOUS` — never silently retried).

---

## 9. Content model (§21-§23, §51-§52)

### 9.1 `AnkiRenderedCard` — normalized once by the backend

```kotlin
data class AnkiRenderedCard(
    val ref: AnkiCardRef,
    val questionHtml: String?, val answerHtml: String?,       // VISUAL
    val questionText: String,  val answerText: String,        // SPEECH
    val pureAnswerText: String?,                              // EVALUATION
    val media: List<AnkiMediaRef>,
    val scheduling: AnkiSchedulingInfo?,                      // display-hints only
    val metadata: AnkiCardMetadata
)
```

### 9.2 Why three representations (§22)

The app consumes each card in three channels with different requirements:
WebView needs Anki's rendered HTML; TTS needs fluent plain text; the AI
evaluator needs the undecorated reference answer. Deriving them ad hoc
throughout the app produces three subtly different "truths". The backend
normalizes ONCE; downstream layers pick their channel. Presentation data flow
(§60):

```
AnkiBackend → AnkiRenderedCard ├─ questionHtml ─────► WebView (visual)
                               ├─ questionText ─────► TTS (speech)
                               └─ pureAnswerText ───► AI evaluator (reference)
```

### 9.3 Cloze and templates (§51-§52)

Study-Agent does NOT interpret cloze syntax and does NOT own template HTML/CSS
semantics. Preferred: Anki renders → Study-Agent displays/reads normalized
output (INV-12). No independent cloze engine. Advanced template editing stays
delegated to AnkiDroid (§13.1).

### 9.4 Media (§23)

`AnkiMediaRef` = `ContentUri | BackendStream | RemoteUrl | Unavailable`. Media
is owned by Anki; Study-Agent renders/plays by reference. Filesystem paths are
NOT part of the architecture contract. Missing media degrades the render, not
the review (§20.3).

---

## 10. State architecture (§31-§35)

### 10.1 Composition, not a mega-state-machine (§31-§32)

`StudyState`/`SessionPhase` must NOT sprout Anki variants. Forbidden by
contract: `StudyAnkiVoiceNetworkMegaState`,
`ListeningWithAnkiConnectedBluetoothCloudTts`, and friends. Presentation is
derived from **orthogonal, separately-owned states**:

```
study session state (SessionPhase — Study-Agent, existing)
  ×  Anki availability (AnkiAvailability — gateway)
  ×  Anki session binding (AnkiSessionContext? — gateway)
  ×  connection state (ConnectionState — Study-Agent, existing)
  ×  voice state (TTS/STT — Study-Agent, existing)
```

UI combines them. Each subsystem's machine stays bounded and coordinates via
explicit events.

### 10.2 The study machine stays UI-flow authority (§33)

Question → listening → evaluation → feedback → rating → pause/resume → finish:
owned by the existing `StudySessionMachine`. The Anki backend is an effect
dependency the machine invokes; backend callbacks must never mutate session
state directly — they complete effects by dispatching events (the existing
effect→event discipline).

### 10.3 Future effects and events (§34-§35) — binding direction, not yet code

Effects (executed, complete as events): `LoadNextAnkiCard`, `CommitAnkiRating`,
`BuryAnkiCard`, `SuspendAnkiCard`, `RefreshDeckSummary`.
Events: `AnkiCardLoaded`, `AnkiCardLoadFailed`, `AnkiRatingCommitted`,
`AnkiRatingCommitFailed(outcome)`, `AnkiBackendUnavailable`.
These land in the gate that wires the gateway into the machine (GATE 06),
behind the same reducer/effect separation every other effect uses.

---

## 11. Session resume & reconciliation (§47-§48)

### 11.1 Resume semantics after process restart

| Resume point | Safe behavior |
|---|---|
| Before rating commit | Same review turn may resume (re-render/re-ask) |
| After CONFIRMED rating commit | Request next card |
| AMBIGUOUS commit | Reconcile FIRST, then decide |

Restoring old UI state blindly is forbidden; resume = re-derive from machine
epoch + backend truth.

### 11.2 Anki session reconciliation — requirements (implementation: GATE 06)

Must answer, in order: Is the backend still the same (`AnkiSessionContext`
match)? Does the session context still exist on the backend? Was the rating
committed (commit ledger / backend query)? Is this card still current?
Replay the question or advance? Only then does the session continue.

---

## 12. Provider independence — hybrid and offline (§42-§46)

### 12.1 The rule (INV-ANKI-10)

Anki backend selection is INDEPENDENT of AI / TTS / STT provider selection.
There is no one "mode" enum bundling providers. A valid configuration:

```
Anki: AnkiDroid  |  AI: PC Agent  |  TTS: ElevenLabs  |  STT: Android
```

### 12.2 Offline flow (§43, §84) — architecturally possible NOW

```
AnkiDroid ready, PC Agent unavailable
  → local card from AnkiDroid
  → Android TTS speaks question
  → Android STT recognizes answer
  → manual rating (buttons/voice)
  → commit to AnkiDroid scheduler
```

`ConnectionState.Ready` must NOT be a prerequisite for all Study activity.
Known blocker: `StudySessionMachine.observeConnection` currently forces
`Recovering` on ANY connection loss regardless of backend — future gates must
scope connection-loss reactions by the session's resolved backend (hotspot H4).

### 12.3 Hybrid flow (§85)

```
Cards: AnkiDroid │ Speech: ElevenLabs via PC │ STT: Android
Evaluation: PC Agent LLM │ Scheduling: AnkiDroid
```

No ambiguity: each capability flows through its own provider boundary; the
Anki session binding only governs cards and commits.

### 12.4 AI optionality (§45)

AI evaluation is an enhancement. Policy modes for later settings work:
`MANUAL` / `AI_ASSISTED` / `AI_REQUIRED`. If AI disappears mid local session:
`AI_ASSISTED` continues manually; `AI_REQUIRED` pauses. Default for initial
integration: `AI_ASSISTED`.

### 12.5 TTS/STT optionality (§46)

Cloud TTS failure must NEVER change which card is due or whether a rating was
committed. Voice providers do not own study state (existing degraded-mode
behavior already honors this locally).

---

## 13. Delegated and Study-Agent-owned satellite domains

### 13.1 Intentional delegation (§73) — architecture, not missing work

| Feature | Owner |
|---|---|
| Anki sync (AnkiWeb) | AnkiDroid / Anki Desktop — Study-Agent never syncs (§53) |
| Import/export collections | AnkiDroid / Desktop (§54); shortcuts may delegate later |
| Full template editor | AnkiDroid (§52) |
| Advanced deck options, shared decks | AnkiDroid |
| Traditional Anki search | The Anki backend where supported (§57) |

### 13.2 Analytics (§55)

Anki facts (ratings, intervals, due state, lapses) come FROM Anki. Study-Agent
analytics (AI correctness, speech latency, confidence, weak topics, hint
dependence) are derived and stored separately. AI-derived interpretations never
overwrite Anki history.

### 13.3 Mistake notebook (§56)

Study-Agent data referencing `AnkiCardRef`. Independent store; Anki-side
deletion/reorganization ⇒ orphaned refs degrade gracefully ("card no longer
available" + preserved notes). Deleting notebook entries never touches Anki.

### 13.4 UI ownership & presentation modes (§58-§59)

AnkiDroid supplies data/rendered content; Study-Agent owns the surrounding UX.
NO embedding whole AnkiDroid activities as the primary study interface.
Three presentation modes documented for later gates: **ORIGINAL** (Anki
HTML/CSS), **CLEAN** (Study-Agent presentation), **VOICE_FOCUS** (minimal
voice-first); optional **ADAPTIVE**.

### 13.5 Future navigation (§74)

Dashboard / Library (decks, card browser, search) / Study / Control /
Connection / Settings / Diagnostics. Library is a later-gate surface.

---

## 14. Security & licensing boundary (§61-§62)

- AnkiDroid access ONLY through supported public APIs. Forbidden: reading
  AnkiDroid's SQLite directly, `/data/data/com.ichi2.anki`, copying
  `collection.anki2`, touching private media dirs outside the contract.
- The intended shape is: separate Study-Agent app ↔ public AnkiDroid
  integration API — NOT a copy/fork of AnkiDroid source (ADR-0005).
- No legal conclusions are made here. **Licensing review is recorded as a
  release requirement** before public distribution of the integration.

---

## 15. Package & dependency architecture (§63-§65, §76-§77)

### 15.1 Packages

```
core/anki/                 ★ this gate — pure domain, JVM-only, backend-neutral
├── AnkiBackendId.kt       (id + preference mode)
├── AnkiRefs.kt            (collection/deck/note/card refs)
├── AnkiCapabilities.kt
├── AnkiAvailability.kt
├── AnkiErrors.kt          (domain errors + commit failure classes)
├── AnkiSessionContext.kt  (context + ReviewTurnId + ReviewCommitId)
├── AnkiBackend.kt         (gateway interface + request/result types)
├── AnkiModels.kt          (deck/rendered card/media/scheduling models)
└── AnkiBackendSelector.kt (resolution policy — the one behavior this gate ships)

data/anki/ankidroid/       ★ GATE 02 — the only package that may name AnkiDroid
├── AnkiDroidApiContract.kt        (authority/permission/spec constants + provenance, endpoints)
├── AnkiDroidErrors.kt             (failure categories/evidence + classifier)
├── AnkiDroidHealth.kt             (facts, detection result, snapshot, user guidance)
├── AnkiDroidProbe.kt              (platform seam: the only interface the logic sees)
├── AnkiDroidPermissionManager.kt  (permission seam)
├── AnkiDroidDetector.kt           (one detection pass: the availability state machine)
├── AnkiDroidHealthCheck.kt        (bounded, timed, total-failure-guaranteed check)
├── AnkiDroidHealthRepository.kt   (single app-scoped owner, StateFlows, single-flight)
├── AndroidAnkiDroidProbe.kt       (PackageManager/ContentResolver — platform file 1 of 2)
└── AnkiDroidLauncher.kt           (Open-AnkiDroid helper — platform file 2 of 2)
data/anki/remote/          GATE 04+ —  PC protocol types may live)
```

### 15.2 Dependency direction (strict)

```
UI → study/domain → Anki domain interfaces (core/anki) → backend
implementations (data/anki/…) → AnkiDroid API / PC protocol
```

Never: `UI → ContentResolver`, never `reducer → FlashCardsContract`. (INV-06)

### 15.3 DI strategy (§65)

No new framework. The existing hand-wired `AppContainer` gains, in later gates:
an `AnkiBackendRegistry` (implemented backends + their availability/capability
flows) and the selector; current PC behavior is registered as `PcAnkiBackend`.
Registry, not fork-on-write wiring.

### 15.4 Scope discipline (§76-§77)

This contract solves Anki backends. It does NOT build a universal
learning-content plugin SDK (Notion/Quizlet/RemNote…). Extensibility bar: add a
THIRD Anki backend without touching `StudySessionMachine`. Anything more
abstract is out of scope.

---

## 16. Test strategy (§66-§68, §96)

### 16.1 Doubles

- `FakeAnkiBackend` (required in the implementation gate): decks, scheduled
  cards, ratings, failures, latency, **duplicate-commit simulation**, **backend
  disappearance** — the chaos-testing levers.
- The existing `FakeAgentConnection` is the model to imitate.
- Core session-machine tests must never require AnkiDroid installed.

### 16.2 Backend contract tests (§67) — every backend must pass the same suite

Deck ids stable across calls; `nextCard` returns domain models; one turn
commits one rating (repeat commit id ⇒ one mutation); invalid card action ⇒
typed failure, never crash; unavailability ⇒ typed error, never crash;
capabilities match reality. These live in a shared harness the AnkiDroid and PC
backends both run against.

### 16.3 What THIS gate adds (§96)

`AnkiArchitectureContractTest` (JVM, `app/src/test/.../anki/`): stable-id
round-trips, backend-qualified identity equality, turn-scoped commit ids,
AUTO/explicit resolution incl. fail-closed explicit mode, readiness
truthfulness, commit-failure classification, session-context immutability. No
tests for documentation-only concepts.

---

## 17. Observability, privacy & health (§69-§71)

- Expose: preferred backend, effective backend, availability, capabilities,
  collection identity (when safe), deck ref, review turn, commit state, last
  operation, last error. NOT: full card content (§70 — refs + lengths + error
  codes only; card content is user-created sensitive data).
- Backend health mapping into unified semantics: local = installed / permission
  / provider / collection / review-capable; PC = agent connection / agent Anki
  adapter / Anki reachable (existing `component_health` concept extends here).

---

## 18. Core invariants (§78) — stable IDs, referenced everywhere

| ID | Invariant |
|---|---|
| **INV-ANKI-01** | One active review session has exactly one writable Anki backend. |
| **INV-ANKI-02** | One review turn commits at most one scheduling mutation. |
| **INV-ANKI-03** | Card identity and review-turn identity are distinct. |
| **INV-ANKI-04** | Anki owns scheduling and due-state authority. |
| **INV-ANKI-05** | AI may suggest a rating but does not own scheduling. |
| **INV-ANKI-06** | Backend-specific classes do not cross the Anki gateway boundary. |
| **INV-ANKI-07** | Backend switching does not occur silently during an active session. |
| **INV-ANKI-08** | A rating with ambiguous commit status blocks progression until reconciled. |
| **INV-ANKI-09** | Study-Agent caches never become scheduling authority. |
| **INV-ANKI-10** | Anki backend selection is independent of AI/TTS/STT provider selection. |
| **INV-ANKI-11** | Every commit carries a `ReviewCommitId`; no commit is anonymous. |
| **INV-ANKI-12** | Card rendering/cloze/template semantics stay Anki-owned; Study-Agent displays normalized output. |
| **INV-ANKI-13** | The committed rating requires explicit user authority (default policy; auto mode is opt-in future work). |
| **INV-ANKI-14** | Missing backend identifiers stay absent — they are never fabricated client-side. |

---

## 19. Ownership matrices

### 19.1 State ownership matrix (§79)

| State | Owner | Persistence |
|---|---|---|
| Active card | Anki (truth) + session copy (render) | session |
| Review turn | Study-Agent | session |
| Rating pending | Study-Agent | session |
| Rating committed | Anki | Anki (collection) |
| Commit idempotency record | Study-Agent (ledger) + backend dedup | session→persistent ledger (GATE 06) |
| TTS state | Study-Agent | transient |
| STT state | Study-Agent | transient |
| AI evaluation | Study-Agent | turn/session |
| Due date | Anki | Anki |
| Weak-topic score | Study-Agent | persistent analytics (future) |
| Anki availability | Gateway | live probe |
| Backend preference | Study-Agent settings (GATE 03) | persisted |
| Effective backend | Session context | session |
| Connection state | Study-Agent | live |

### 19.2 Command ownership matrix (§80)

| Command | Executor |
|---|---|
| Get next card | Anki backend |
| Show answer | Study-Agent presentation |
| Rate | Anki backend (commit) — user authority selects the value |
| Repeat question | Study-Agent |
| Hint / Explain | AI / Study-Agent |
| Bury / Suspend | Anki backend |
| Pause/Resume study | Study-Agent |
| End study | Study-Agent (+ backend session close) |
| Sync | Delegated Anki app |
| Edit note / templates | Delegated (AnkiDroid) |

### 19.3 Failure ownership matrix (§81)

| Failure | Recovery owner | Policy |
|---|---|---|
| TTS failure | Voice subsystem | degrade to visual, never block rating |
| STT failure | Voice subsystem | bounded retries → manual controls |
| AI unavailable | Study policy (`AI_ASSISTED`/`AI_REQUIRED`) | continue manual OR pause |
| Anki unavailable mid-session | Anki/session coordinator | pause; no backend switch (INV-07) |
| Rating commit ambiguous | Anki reconciliation | STOP → reconcile → then advance |
| Rating commit safe-to-retry | Session coordinator | retry same commit id |
| Media missing | Renderer | continue without media |
| Deck refresh failure | Session/library coordinator | retry allowed; cached list usable with staleness marker |
| PC disconnected | Network/recovery (existing) | backend-scoped (H4 fix) |

---

## 20. Sequence flows (§82-§85)

### 20.1 Normal flow

```
User → Study-Agent: start study
Study-Agent → AnkiBackendSelector: resolve(mode)        ← once (INV-01)
Study-Agent → AnkiBackend: beginReview(context)
Study-Agent → AnkiBackend: nextCard          → Anki scheduler → AnkiReviewTurn
Study-Agent: render HTML ┃ TTS question ┃ STT answer ┃ AI evaluate (suggested)
Study-Agent: user selects rating
Study-Agent → AnkiBackend: commitRating(ReviewCommitId)
Anki scheduler: commit (exactly once) → COMMITTED
Study-Agent → AnkiBackend: nextCard …
```

### 20.2 Failure flow — ambiguous commit (the required scenario)

```
Rating selected → commit starts → backend unavailable mid-commit
  → outcome uncertain → DO NOT request next card
  → outcome = AMBIGUOUS → session pauses progression
  → reconcile (was it applied?) → then advance or retry-by-rule
```

### 20.3 Offline and hybrid flows

See §12.2 (offline) and §12.3 (hybrid) — both are first-class, neither requires
`ConnectionState.Ready` for Anki-local operation.

---

## 21. Coupling hotspots (§93) — audit result, DO NOT refactor in GATE 01

| ID | File | Symbol | Current assumption | Future change | Gate |
|---|---|---|---|---|---|
| H1 | `ui/screens/home/HomeViewModel.kt` | `startStudy()` | PC connection required (`!isConnected → false`) | Gate start on resolved backend readiness; local Anki starts offline | 03/06 |
| H2 | `core/study/StudyEvent.kt` | `StudyEventMapper.fromServerMessage` | Protocol `Question` IS the card | PC protocol mapped inside `PcAnkiBackend`; mapper never invents domain facts | 04/06 |
| H3 | `data/repository/StudySessionRepository.kt` | `DefaultStudySessionRepository.startStudy` | "Check PC connection" is the failure model | Legacy path stays PC-bound; machine path routes via backend | 06 |
| H4 | `core/study/StudySessionMachine.kt` | `observeConnection` | ANY connection loss ⇒ `Recovering` | Scope by session's effective backend (offline mode: H1 of §12.2) | 06 |
| H5 | `core/study/StudyReducer.kt` | `RATE_CARD`/`SUBMIT_ANSWER` effects | Answers/ratings are protocol sends | Effect targets gateway (`CommitAnkiRating`) when backend ≠ PC-protocol semantics | 06 |
| H6 | `core/models/StudyCard.kt` | `StudyCard` | Flat text card, deck **name**, no note/card split, no HTML | Adapt: PC backend projects `AnkiRenderedCard→StudyCard`; local path carries rendered card | 04-06 |
| H7 | `core/models/DashboardModels.kt` | `DeckSummary.name` identity | Deck name = identity | `AnkiDeckRef` identity; name becomes display-only | 05 |
| H8 | `data/repository/DashboardRepository.kt` | `request_decks` flow | PC is the only deck source | Deck source = selected backend (`getDecks`) with same cache discipline | 05 |
| H9 | `data/preferences/… ManagementCacheStorage` | decks cache | Caches PC deck payload | Cache remains CACHE-only per backend id (INV-09) | 05 |
| H10 | `core/study/StudySnapshot.kt`, `SessionReconciler.kt` | reconciliation | PC protocol snapshots the only truth | Backend-aware reconciliation + commit ledger (§11) | 06 |
| H11 | `di/AppContainer.kt` | `studySessionRepository` wiring | One session path (protocol) | Registry wires gateway + selector; PC registered first | 03/04 |
| H12 | `core/study/CardTurn.kt` | `turnId` | Review-turn identity exists ✓ | Reused as `ReviewTurnId` basis — this ALIGNS, low risk | 06 |
| H13 | `data/repository/StudyControlRepository.kt` | `currentStartRequest` | Start payload = deck name + mode for server | Start payload resolves deck name → `AnkiDeckRef` via selected backend | 05/06 |

---

## 22. Migration strategy (§92) — incremental, always green

```
Today:   StudySessionRepository → ConnectionRepository → PC Agent
Step 1:  Core/anki contract (THIS GATE — no behavior change)
Step 2:  AnkiDroid detection + permission foundation (GATE 02 — DONE; no AnkiDroid artifact linked)
Step 3:  Registry + selector + preference settings (GATE 03); PC registered
Step 4:  PcAnkiBackend adapts existing protocol (GATE 04) — same behavior
Step 5:  Decks via gateway (GATE 05)
Step 6:  Review/rating via gateway + commit ledger + reconciliation (GATE 06)
```

Each step ships independently; the PC path must stay green at every step (§91).

## 23. Gate dependency map (§94)

| Gate | Depends on (from this contract) |
|---|---|
| GATE 02 — AnkiDroid detection/permission foundation | boundary hygiene (§8.1), availability model, permission error taxonomy, package layout — **delivered, see §25** |
| GATE 03 — domain wiring (registry/selector/settings) | selector policy (§6.2), preference≠effective (§6), DI strategy (§15.3) |
| GATE 04 — backends (`PcAnkiBackend`, `AnkiDroidBackend`) | `AnkiBackend` contract, capabilities, domain models, content model (§9) |
| GATE 05 — decks/library | deck identity (§5.3), cache policy (§4), deck-source ownership (H7-9) |
| GATE 06 — review commit + reconciliation | rating transaction (§7), exactly-once (INV-02/08/11), session context lock, resume semantics (§11) |
| GATE 07+ — presentation/media/mistake notebook/analytics/search | content model (§9), satellite ownership (§13), privacy (§17) |

## 24. Deferred to GATE 02 (explicit; §89)

AnkiDroid api artifact dependency · `FlashCardsContract` / `AddContentApi` ·
`READ_WRITE_DATABASE` permission · installation/permission detection · any
ContentResolver query · real API calls. Also deferred: settings persistence of
`AnkiBackendMode` (GATE 03), commit ledger persistence (GATE 06), licensing
review (release gate).

> **Status after GATE 02:** detection, permission visibility, a bounded read-only probe and the
> health owner are implemented (§25). Still deferred: the *compile-time* artifact (decision and
> evaluation: `docs/ANKIDROID_INTEGRATION.md` §2), deck/card/review calls, settings persistence of
> the backend mode (GATE 03), commit ledger (GATE 06), licensing review (release gate).

## 25. GATE 02 implementation notes — detection, permission, provider, collection readiness

Delivered by GATE 02 (code: `data/anki/ankidroid/`, ten files listed in §15.1). Detailed reference
and the user-facing tables: `docs/ANKIDROID_INTEGRATION.md`. This section records only what later
gates must treat as the *established* design.

### 25.1 The pipeline

```
MainActivity.onStart / Settings opened / Retry tap
        │  (debounced 2 000 ms, single-flight, no polling)
        ▼
AnkiDroidHealthRepository  ── StateFlow<AnkiDroidHealthSnapshot> + StateFlow<AnkiAvailability>
        │  one Mutex: at most one check at a time
        ▼
AnkiDroidHealthCheck (3 000 ms budget, duration, never throws)
        ▼
AnkiDroidDetector ── an ordered, evidence-driven state machine
        ├─ resolveContentProvider(authority, GET_META_DATA)      [release; debug only in debug]
        ├─ package mismatch / disabled provider  → ProviderUnavailable
        ├─ spec < minimum                        → Unsupported
        ├─ permission not granted                → PermissionRequired (no probe issued)
        └─ probeCollection: selected_deck, 1 row → Ready  |  classified failure
        ▼
AnkiDroidProbe (interface) ◄── AndroidAnkiDroidProbe (the only Android code besides the launcher)
```

### 25.2 What "Ready" means in this gate

`Ready` = provider resolved **and** served by the expected package **and** enabled **and**
permission granted **and** provider spec ≥ minimum **and** the collection answered a bounded
read-only probe. It carries `AnkiCapabilities.NONE` and leaves `isReadyForReview == false`,
because no deck/review capability has been probed yet — an unprobed capability must be reported as
unprobed, not assumed (§95/§96, INV-ANKI-DET-02/03). Later gates widen `Ready` only by *proving*
more.

### 25.3 Availability model amendments (GATE 01 types, reused — no second hierarchy)

`AnkiAvailability` gained exactly two members so detection can express itself without inventing a
parallel vocabulary: `Checking` (no result yet — never `Ready`, never a failure) and
`ProviderUnavailable(detail)` (app present, integration provider not reachable — distinct from
both `NotInstalled` and `Fault`). A `statusCode` extension supplies stable diagnostics tokens
(`CHECKING`, `NOT_INSTALLED`, `PROVIDER_UNAVAILABLE`, `PERMISSION_REQUIRED`,
`COLLECTION_NOT_INITIALIZED`, `READY`, `TEMPORARILY_UNAVAILABLE`, `AGENT_DISCONNECTED`,
`AGENT_ANKI_UNAVAILABLE`, `UNSUPPORTED`, `FAULT`). `AnkiError` gained `ProviderUnavailable`,
`UnsupportedApi(specVersion, minimumSpec)` and `QueryFailure(causeCategory)`.

### 25.4 Permission taxonomy (replaces "assume dangerous-runtime")

The permission is **third-party-declared** by AnkiDroid and **enforced dynamically** by its
provider; the grant is resolved by Android at install/update, there is no runtime dialog, and the
provider's refusal appears as `SecurityException("Permission not granted for: …")` at call time.
Mapping: `SecurityException` → `PERMISSION_DENIED` (never swallowed); the documented signature →
`PERMISSION_DENIED`; a *granted* permission plus the provider refusing is still classified, not
ignored. Study-Agent declares the permission itself (no artifact merges it) and asks no storage or
database permission. Full detail: `docs/ANKIDROID_INTEGRATION.md` §3.

### 25.5 Failure taxonomy and the mapping table

One classifier, ten categories, evidence-token based, content-free; category → availability
mapping is normative in `docs/ANKIDROID_INTEGRATION.md` §5. The two rules later gates must not
soften: **only documented setup signatures may produce `CollectionNotInitialized`**, and an
unclassified `IllegalStateException` stays a visible provider fault (§61).

### 25.6 Health owner and refresh policy

One application-scoped owner (`AnkiDroidHealthRepository`, built in `AppContainer`) is the single
source of runtime availability: `StateFlow` snapshot + availability projection, one check at a
time (Mutex), monotonic publication guard (a superseded result cannot publish), 2 000 ms foreground
debounce, fire-and-forget single-flight, cancellation propagated and never converted into a state.
Availability is **never persisted** — no cached `Ready` can survive a restart or a user
uninstalling AnkiDroid. `Checking` is the initial state so the first frame never blocks on an
optional integration (INV-ANKI-DET-04/08/09).

### 25.7 Boundary enforcement

Only `data/anki/ankidroid/` may name the AnkiDroid contract; within it, only
`AndroidAnkiDroidProbe.kt` and `AnkiDroidLauncher.kt` may import `android.*`. The layer reads
through one bounded, read-only query and contains no mutation API, no Room/SQLite, no WorkManager,
no `GlobalScope`, no blocking calls. These are enforced by `AnkiDroidIntegrationIsolationTest`
(source scans) in addition to review, so INV-ANKI-DET-06/07 do not rely on discipline. The
complement is the device-only `AnkiDroidIntegrationInstrumentedTest` (package
`com.studyagent.client.anki`), which proves the *platform* half — the real probe answering on the
published contract, coherence of the verdict, bounded refreshes, no PC-backend vocabulary — while
requiring no AnkiDroid installation (GATE 02 §106/§107/§108).

### 25.8 Obligations this contract now places on GATE 03+

- Consume availability **only** through the health repository/StateFlows; never re-derive it, never
  read the package manager or the permission again, never persist `Ready`.
- Register the AnkiDroid backend in the selector only once it is actually implemented and
  **review-ready**; `Ready` today means "we can communicate", so the AUTO policy (from the Pc
  backend's perspective) is unchanged by this gate.
- Keep the PC path independent: AnkiDroid health must never gate connection, session start or
  startup (§39/§91).
- If the official `api` artifact is ever linked, adoption must not change detection semantics, and
  it must be pinned exactly (no dynamic versions) — evaluation and recipe:
  `docs/ANKIDROID_INTEGRATION.md` §2.

---

*Normative cross-references: ADRs under `docs/adr/`; code contract under
`app/src/main/java/com/studyagent/client/core/anki/`; contract tests under
`app/src/test/java/com/studyagent/client/anki/`.*
