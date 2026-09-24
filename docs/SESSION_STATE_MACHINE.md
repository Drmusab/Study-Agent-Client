# Study Session State Machine — Hardened Specification

**Date:** 2026-09-13  
**Status:** Implemented (event-driven, single serialized reducer)  
**Package:** `com.studyagent.client.core.study`

## 1. Design Philosophy

```
Event  →  Reducer (pure)  →  New State + Effects  →  Executor (I/O)  →  Event ...
```

* No code writes `_studyState.value =` outside the single event loop.
* Every async input becomes an event: voice completion, WebSocket frame, timer, connection change, PTT.
* Effects are explicit (Send, Speak, StartRecognition, ScheduleTimeout) so they are testable without real I/O.

## 2. Authoritative State

```kotlin
data class SessionMachineState(
  epoch: Long,                 // §14 new session bumps epoch; stale callbacks from old epoch are ignored
  phase: SessionPhase,         // §10 formal phase
  session: StudySessionSnapshot?,
  cardTurn: CardTurn?,         // §15-§17 turn identity: epoch:cardId:generation + serverTurnId when available
  ledger: SubmissionLedger,    // §24-§25 exactly-once intent
  recentServerMessageIds: LRU, // §22 dedup bounded 200
  pendingAction: PendingAction?,
  pauseContext: ResumeContext?,// §33 immutable resume snapshot, not Paused(previousState)
  connection: SessionConnectionStatus,
  error: SessionProblemHolder?,
  transitionHistory: Ring(200) // §142
)
```

Derived flows ensure `StudyState` and `StudySession` can never disagree (§75):

```kotlin
val studyState: StateFlow<StudyState> = machineState.map { derive(it) }
val currentSession: StateFlow<StudySession?> = machineState.map { derive(it) }
```

## 3. Session Phases (§10)

```
NO_SESSION (Idle)
  ↓ UserStartRequested
STARTING
  ↓ ServerSessionStarted
WAITING_FOR_FIRST_CARD
  ↓ ServerQuestionReceived
SPEAKING_QUESTION ──► WAITING_FOR_ANSWER ──► PENDING_REVIEW (autoSubmit=false)
                            │                      │
                            ▼                      ▼ submitPending / discard
                     SUBMITTING_ANSWER        SUBMITTING_ANSWER
                            │
                            ▼
                    WAITING_FOR_EVALUATION
                            │
                            ▼ ServerEvaluationReceived
                     SPEAKING_FEEDBACK ──► WAITING_FOR_RATING ──► SUBMITTING_RATING ──► WAITING_FOR_FIRST_CARD
                            │                    │                        │
                            │              hint/explanation          RatingSaved → next Question
                            ▼                    ▼
                       SpeakingHint    SpeakingExplanation → WaitingForRating
                       ShowingAnswer (reveal) ──► WaitingForRating

Side branches:
  PAUSING → PAUSED → RESUMING → (reconcile) → safe restart (§34)
  RECOVERING (connection) → RequestSessionStatus → reconcile (§35-§40)
  FINISHING → FINISHED (terminal §83)
  ERROR(problem) with severity FATAL/RECOVERABLE/DEGRADED (§52)
```

Overlays: `HINT`, `EXPLANATION`, `ANSWER_REVEAL` are temporary phases that always return to a safe answer/rating restart point, not to the exact transient.

## 4. Legal Transition Table (§11 excerpts)

| From                 | Event                     | To                   | Effects                         |
|----------------------|---------------------------|----------------------|---------------------------------|
| Idle                 | UserStartRequested        | Starting             | Send StartSession, ScheduleTimeout |
| Starting             | ServerSessionStarted      | WaitingForFirstCard  | CancelTimeout                   |
| WaitingForFirstCard  | ServerQuestionReceived    | SpeakingQuestion / WaitingForAnswer | Speak or StartRecognition |
| SpeakingQuestion     | QuestionSpeechCompleted   | WaitingForAnswer     | StartRecognition(ANSWER)        |
| WaitingForAnswer     | UserSubmitAnswer          | SubmittingAnswer     | Send SubmitAnswer, ScheduleTimeout, CancelRecognition |
| SubmittingAnswer     | ServerEvaluationReceived  | SpeakingFeedback / WaitingForRating | Speak feedback or StartRecognition(RATING) |
| SpeakingFeedback     | FeedbackSpeechCompleted   | WaitingForRating     | StartRecognition(RATING)        |
| WaitingForRating     | UserRateCard              | SubmittingRating     | Send RateCard, CancelRecognition |
| SubmittingRating     | ServerRatingSaved         | WaitingForFirstCard  | CancelTimeout, await next Question |
| Any active           | UserPauseRequested        | Pausing              | CancelSpeech/Recognition, Send Pause |
| Pausing              | ServerSessionPaused       | Paused               | —                               |
| Paused               | UserResumeRequested       | Resuming             | Send Resume                     |
| Pausing/Paused/Resuming | ServerSessionResumed   | (safe restart)       | SpeakQuestion / StartRecognition |
| Any active           | ConnectionLost            | Recovering           | CancelSpeech/Recognition        |
| Recovering           | ConnectionRestored        | Recovering           | Send RequestSessionStatus       |
| Recovering           | SessionStatusReceived     | (reconciled)         | CancelSpeech/Recognition, restart |
| Any active           | ServerSessionFinished     | Finished             | Cancel all                      |
| Any active           | UserEndRequested          | Finishing            | CancelSpeech/Recognition, Send End |
| Finishing            | ServerSessionFinished / Timeout | Finished       | —                               |

Illegal events are classified and rejected (duplicate, stale-card, stale-session, out-of-order, protocol violation) and logged as `SESSION_EVENT_REJECTED` (§12, §141).

## 5. Epoch & Turn Identity (§14-§18)

* **Session epoch** (`Long`): bumped on each `StartSession`. All pending actions and callbacks carry it.
* **Card turn** (`CardTurn`): `generation` monotonic per card, `turnId = serverTurnId ?: "$epoch:$cardId:$generation"`, plus `serverRevision` when available for §96.
* Server messages carry `sessionId`, `cardId`, `review_turn_id`, `session_revision` where possible. Reducer validates `sessionId` matches active session and `cardId`/`turnId` matches current turn; mismatches are rejected or sent to reconciliation.

## 6. Idempotency & Dedup (§21-§29)

* **Server dedup:** LRU of `recentServerMessageIds` (bounded 200). Duplicate `messageId` is ignored unless protocol defines replay.
* **Client idempotency:** `messageId` (UUID) is the idempotency key (§45). Server must not apply same `messageId` twice for `SubmitAnswer`, `RateCard`, `Skip`, `Pause`, `Resume`, `End`, `Start`.
* **SubmissionLedger:** per-turn `answerState/ratingState/skipState ∈ {NOT_STARTED, IN_FLIGHT, ACKNOWLEDGED, FAILED_RETRYABLE, FAILED_FINAL}`. `send() == false` transitions to `FAILED_RETRYABLE` so retry is possible (§27-§28). Duplicate taps race to a single `IN_FLIGHT` winner (§90, §91).
* **PendingRatingConfirmation & PTT ownership:** bound to `turnId+epoch` so stale yes cannot rate next card (§78-§79).

## 7. Pause / Resume (§30-§34, §85)

```
UserPauseRequested → Pausing (local voice stopped immediately, CancelSpeech/Recognition)
  → Server pauses → Paused (holds immutable ResumeContext)
  → UserResumeRequested → Resuming → ServerResumed → safeRestartPhase
```

* No nested `Paused(Paused(...))`; repeated Pause is idempotent.
* Transient phases (`Evaluating`, `SubmittingRating`, `SpeakingQuestion` mid-utterance) are not resumed blindly; `ResumeContext.safeRestartPhase` maps to `WaitingForAnswer` / `WaitingForRating` / repeat question.

## 8. Reconnection & Reconciliation (§35-§44)

```
ConnectionLost → freeze voice → Recovering
ConnectionRestored → RequestSessionStatus / RequestSessionSnapshot (authoritative)
  → SessionReconciler(local, snapshot) → reconciled state + effects
```

* Server is authoritative for `session existence, current card, awaiting answer/rating, finished, scheduler` (§41). Client is authoritative for mic/TTS presentation.
* Reconciler wins server snapshot over local guess (§40). Pending transcript preserved only if same session+card+awaiting-answer.
* In-flight `SubmitAnswer`/`RateCard` during disconnect: ambiguous delivery is not retried blindly; reconciliation via `session_revision` or `review_turn_id` decides (§43, §44).

## 9. Timeouts (§46-§49)

Purpose-specific (not one global):

| Action            | Timeout |
|-------------------|---------|
| StartSession      | 15s |
| SubmitAnswer(Evaluation) | 30s |
| RateCard          | 15s |
| Pause/Resume/End  | 8s |
| SessionStatus     | 10s |

Each pending action tracks `messageId`, `type`, `epoch`, `cardTurnId`, `timestamp`. Timeout dispatches `ActionTimedOut(messageId)` into reducer; reducer validates IDs and epoch before mutating. Cancel on ACK.

Injectable clock (`() -> Long`) makes timeouts testable with virtual time (§105, §106).

## 10. Effects vs State (§8)

*State = facts; Effects = actions.*

Examples:

```
WaitingForAnswer + HintRequested 
  → WaitingForHint + [CancelSTT, Send RequestHint]

HintReceived 
  → SpeakingHint + [SpeakHint]

AnswerRecognitionCompleted (handsFree, autoSubmit=true)
  → SubmittingAnswer + [Send SubmitAnswer, CancelSTT]
```

All voice/network completions re-enter as events (`SpeechCompleted(effectId)`, `EvaluationReceived`) so late callbacks are validated against current `effectId`/`turnId`.

## 11. Invariants (§139)

1. If `StudyState` contains card A, `currentSession.currentCard` is A.
2. Evaluation belongs to current card turn.
3. At most one answer in-flight per turn.
4. At most one rating per turn.
5. Finished cannot transition back.
6. Paused cannot open STT/TTS.
7. Only active epoch may mutate.
8. Only active generation may mutate card state.
9. Server sessionId must match where applicable.
10. No network effect from illegal state.

Enforced via reducer branches and logged via `SESSION_TRANSITION` / `SESSION_EVENT_REJECTED` plus ring buffer for Diagnostics.

## 12. Visualization

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Starting: UserStartRequested
  Starting --> WaitingForFirstCard: ServerSessionStarted
  WaitingForFirstCard --> SpeakingQuestion: QuestionReceived(speak)
  WaitingForFirstCard --> WaitingForAnswer: QuestionReceived(!speak)
  SpeakingQuestion --> WaitingForAnswer: SpeechCompleted
  WaitingForAnswer --> SubmittingAnswer: UserSubmitAnswer
  WaitingForAnswer --> PendingReview: TranscriptHold(autoSubmit=false)
  PendingReview --> SubmittingAnswer: SubmitPending
  PendingReview --> WaitingForAnswer: DiscardPending
  SubmittingAnswer --> WaitingForEvaluation: (transport)
  WaitingForEvaluation --> SpeakingFeedback: EvaluationReceived(speak)
  WaitingForEvaluation --> WaitingForRating: EvaluationReceived(!speak)
  SpeakingFeedback --> WaitingForRating: SpeechCompleted
  WaitingForRating --> SubmittingRating: UserRateCard
  SubmittingRating --> WaitingForFirstCard: RatingSaved
  WaitingForFirstCard --> SpeakingQuestion: next Question
  SpeakingQuestion --> SpeakingQuestion: Repeat
  WaitingForAnswer --> SpeakingHint: HintReceived
  SpeakingHint --> WaitingForAnswer: HintSpeechCompleted
  WaitingForRating --> SpeakingExplanation: ExplanationReceived
  SpeakingExplanation --> WaitingForRating: ExplanationCompleted
  WaitingForAnswer --> ShowingAnswer: AnswerReceived
  ShowingAnswer --> WaitingForRating: (after speak)
  state Pausing {
    WaitingForAnswer --> Pausing: PauseRequested
    WaitingForRating --> Pausing
    SpeakingFeedback --> Pausing
  }
  Pausing --> Paused: ServerPaused
  Paused --> Resuming: ResumeRequested
  Resuming --> WaitingForAnswer: ServerResumed(safeRestart)
  Resuming --> WaitingForRating: ServerResumed(safeRestart)
  WaitingForAnswer --> Recovering: ConnectionLost
  SubmittingAnswer --> Recovering: ConnectionLost
  WaitingForRating --> Recovering: ConnectionLost
  Recovering --> Recovering: ConnectionRestored -> RequestSnapshot
  Recovering --> WaitingForAnswer: Snapshot(autocratic)
  Recovering --> WaitingForRating: Snapshot(autocratic)
  Recovering --> Finished: Snapshot(finished)
  WaitingForAnswer --> Finishing: EndRequested
  Finishing --> Finished: SessionFinished
  Finished --> Starting: UserStartRequested(new epoch)
  Finished --> [*]
```

## 13. Protocol Enhancements (backward compatible)

* `ClientMessage` now carries optional `review_turn_id` + `session_revision` for all card-scoped actions. v1 servers ignore them.
* `ServerMessage.Question/ EvaluationResponse/ Hint/ Explanation/ Answer/ RatingSaved` carry `review_turn_id`, `session_revision`, `sequence`.
* New `SessionSnapshot` / `SessionStatus` (type `session_snapshot` / `session_status`) for authoritative recovery (§95). v1 `session_stats` still accepted as fallback.
* `messageId` remains the idempotency key for all client actions (§45).

## 14. Testing Strategy

* **Reducer isolation:** pure function tests for every transition (§108).
* **Happy path:** Idle→Finished full loop (§109).
* **Double-start / double-answer / double-rating:** exactly-once ledger (§110-§114, §137 simulated 1000 turns).
* **Send-failure recovery:** ledger becomes FAILED_RETRYABLE (§112, §114).
* **Stale/duplicate guards:** evaluation after card B, rating ACK after B, duplicate question/evaluation (§115-§118).
* **Pause matrix:** during question, STT, evaluation, rating submit (§119-§124).
* **Connection loss:** listening, in-flight answer/rating, reconnect snapshot (§125-§128).
* **Pending transcript:** submitPending exactly once, discard reopens STT (§132).
* **Hint/Explanation/Skip/Answer-reveal:** phase-gated (§133-§136).
* **Chaos:** fake server + reducer fuzz (randomized Pause/Resume/Hint/Skip/Duplicate/ConnectionLost/Reconnect/DelayedCallback) asserts invariants never break (§138).
* **Fake Effect Executor + Deterministic Scheduler (§105-§107):** no real WebSocket/TTS/STT, record effects.

## 15. Remaining Limitations

* Process death recovery is minimal snapshot (sessionId, deck, cardId, timestamp) + server reconciliation; full machine serialization is not persisted (§99-§100).
* `review_turn_id` Depends on server v2; on v1 the client-generated `epoch:cardId:generation` provides equivalent local safety but cannot disambiguate Anki redelivered same card across server restarts beyond generation.
* Foreground service delegates pause/resume/stop via same `dispatch(User*Requested)` events; it does not implement a parallel machine but process still relies on Android lifecycle.
* Voice-turn ownership via `effectId` is enforced; however STT purpose selection still requires up-to-date `AppSettings` (handsFree, listenForSpokenRating) at effect execution time — settings change mid-action is handled by reducer re-evaluating at next transition (§70).

## 16. Anki Composition (GATE 01 contract)

The machine stays the single authority of **user-flow** state when Anki backends beyond the PC agent exist (contract: `docs/ANKI_INTEGRATION_ARCHITECTURE.md` §10, ADR 0004).

* **No mega state machine.** Anki conditions are NOT added to `SessionPhase`/`StudyState`. Presentation derives from orthogonal, separately-owned states: session phase × Anki availability × Anki session binding (`AnkiSessionContext`) × connection state × voice state. No `ListeningWithAnkiConnected…`-style combinatorial states.
* **Backend = effect dependency.** Future effects (`LoadNextAnkiCard`, `CommitAnkiRating`, `BuryAnkiCard`, `SuspendAnkiCard`, `RefreshDeckSummary`) execute through the gateway and complete by dispatching events (`AnkiCardLoaded`, `AnkiCardLoadFailed`, `AnkiRatingCommitted`, `AnkiRatingCommitFailed`, `AnkiBackendUnavailable`). Backend callbacks never mutate state directly.
* **Rating transaction joins the ledger model.** `ReviewCommitId` (backend + study session + review turn) extends the existing exactly-once discipline to the *scheduler*: one turn ⇒ at most one mutation; `AMBIGUOUS` commit outcomes block progression until reconciled (INV-ANKI-02/08/11). The existing `CardTurn.turnId` IS the review-turn identity the commit id builds on.
* **Connection loss becomes backend-scoped (future change, hotspot H4).** Today `observeConnection` forces `Recovering` for any active session. With an `ANKIDROID_LOCAL` session, PC connection loss must not pause local study — the reaction is scoped by the session's resolved Anki backend (GATE 06). Until then the machine's PC behavior is unchanged.
* **Reconciliation gains a second form.** `SessionReconciler` (protocol snapshots) remains the PC form; the Anki form reconciles the session context, commit ledger and current card against the backend before advancing after restart or ambiguous commits (contract §11, implemented GATE 06).

## 17. GATE 11 — The local Anki rating transaction

The rating is the one place where the local Anki path mutates the user's collection. It is a
transaction with a durable ledger, and the machine never advances on selection alone.

```text
WaitingForRating ──SelectRating / UserRateCard (touch, voice, headset, keyboard)──▶ SubmittingRating
   commit = AnkiRatingCommit(request, NOT_STARTED)      effect: CommitRating(epoch, request)
        executor: ledger NOT_STARTED (durable) → baseline evidence → ledger SUBMITTING (durable)
        ──RatingCommitStarted──▶ commit.state = SUBMITTING         (backend call in flight)
        ──RatingCommitResolved(Committed)──▶ WaitingForFirstCard   effect: Next — exactly once
        ──RatingCommitResolved(Failed)─────▶ RatingCommitFailed    (Retry if safe · End)
        ──RatingCommitResolved(Ambiguous)──▶ ReconciliationRequired (Check again · End)
ReconciliationRequired ──ReconcileRatingCommit──▶ (read-only) ──RatingCommitReconciled──▶
        Committed → WaitingForFirstCard + Next · Failed → RatingCommitFailed · Ambiguous → stays
RatingCommitFailed ──RetryRatingCommit (safeToRetry only)──▶ SubmittingRating (same request)
```

* **Phases.** Two phases were added — `RatingCommitFailed` (known NOT applied) and
  `ReconciliationRequired` (may have been applied). `SubmittingRating` is reused; the rating is
  data (`anki.commit.request.rating`, `StudyState.Loading.pendingRating`), never a per-rating state.
  "RatingSelected" and "CommitPrepared" are one pure reducer step; "LoadingNextCard" is the existing
  `WaitingForFirstCard` reached only from COMMITTED.
* **Correlation.** Every commit event carries the `ReviewCommitId` (backend + study session +
  review turn) and the epoch. A result for another epoch, session, turn or commit id is rejected
  (`stale-anki-commit-result`); a second COMMITTED for the same commit is rejected
  (`duplicate-anki-commit-result`). The executor has already written the ledger, so a stale result
  updates the ledger but never the current turn.
* **Conflicting ratings.** *First accepted rating wins.* Selection immediately prepares the commit,
  so any later rating for the turn — same or different, from any input — is rejected
  (`duplicate-anki-rating`, `anki-rating-locked-first-wins`). There is no "change my rating"
  window once the commit is prepared; a retry re-sends the recorded request verbatim.
* **Answer time.** `answerDurationMs` = question presentation (hydrated turn shown) → rating
  selection, on the machine clock, including question speech. Anki caps it with the deck's maximum
  answer time. When unknown it is omitted (Anki then records its cap) — never fabricated.
* **Counters.** `totalReviewedInSession` increments only in the COMMITTED branch.
* **Next-card failure.** A failed `Next` after COMMITTED goes to `Error(ANKI_UNAVAILABLE)` with the
  commit still COMMITTED. `RetryNextCard` re-issues the *read* only when no turn is open and the
  commit is COMMITTED or absent — the rating is never re-sent.
* **Write lane.** `CommitRating`, `ReconcileCommit` and `EndReview` run on one ordered lane that is
  independent of the cancellable read job. Ending the session, stopping, backgrounding or recreating
  the UI never cancels an in-flight commit, and `EndReview` cannot release the backend session
  ahead of a commit that was emitted before it.
* **Process death** (ledger survives, the machine does not):

  | Ledger state at death | After restart |
  |---|---|
  | NOT_STARTED | restorable: provably never dispatched; re-executing the same commit claims and dispatches once |
  | SUBMITTING | AMBIGUOUS (persisted on first load); never re-sent; recovery = reconcile or end |
  | COMMITTED | answered from the ledger (`ledger_replay`); backend never called again |
  | FAILED | kept with its `safeToRetry` decision |
  | AMBIGUOUS | kept; surfaced as "earlier rating not confirmed" when the next session begins |

* **Exactly-once wording.** The guarantee is *at most one intentional scheduler mutation per review
  turn issued by Study-Agent*, with every uncertain outcome surfaced as AMBIGUOUS. It is not a claim
  of distributed exactly-once delivery: other actors (AnkiDroid itself, sync) can change a card at
  any time, and a provider transaction that outlives a killed client process is a documented
  residual risk.
