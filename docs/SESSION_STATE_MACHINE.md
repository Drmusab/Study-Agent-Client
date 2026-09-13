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
