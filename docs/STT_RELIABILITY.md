# STT Reliability — Guarantees, Invariants & Recovery

This document is the contract for the speech-to-text subsystem's *deterministic behaviour*:
what may never happen, what happens when the recognizer misbehaves, and which component
owns each decision. The design goal is not "speech recognition works once" — it is
**hundreds of consecutive study turns under chaos: late callbacks, duplicate terminals,
Bluetooth changes, network failures, rapid state transitions — with zero duplicate
submissions, zero stale transcripts and zero stuck recognizers.**

Companion documents: `STT_ARCHITECTURE.md` (layer/module map), `VOICE_FLOW.md` (study-loop
UX flow), `ARCHITECTURE.md` (system overview).

---

## 1. Ownership — who controls recognition

```
UI / ViewModels          (may only: press PTT, tap rating, tap retry — never touch a recognizer)
        │
StudySessionRepository  (decides WHEN a window exists and WHAT a transcript means)
        │
SpeechRecognitionOrchestrator  (DefaultSpeechRecognitionOrchestrator — the SINGLE authority
        │                       over the recognition lifecycle: start, finish, cancel,
        │                       watchdogs, retry, rate limiting, stale-callback rejection)
        │
SpeechRecognitionBackend (AndroidSpeechRecognitionBackend — the ONLY code that touches
        │                 android.speech.SpeechRecognizer, on the main thread)
        ▼
Platform SpeechRecognizer
```

* Only the repository opens windows (`beginStt`), and only for an explicit
  `RecognitionPurpose`. UI code never calls the orchestrator directly.
* `stopListening` (finish: finalise captured speech — push-to-talk release, manual
  completion) and `cancel` (discard: pause, end session, new card, route loss, connection
  loss, answer/rating already submitted) are never used interchangeably.
* Every orchestrator state transition runs inside one monitor (`transitionLock`), so the
  busy-check → `backend.start` sequence is atomic with respect to backend events, watchdog
  firings, retry jobs and concurrent UI calls. All critical sections are non-suspending;
  the lock is held only for microseconds.

## 2. Request identity & the recognition state machine

Every turn is a `RecognitionRequest` with a unique, readable id
(`answer_<seq>`, `rating_<seq>`, `push_to_talk_answer_<seq>`, …) and a `RecognitionPurpose`
(`ANSWER`, `RATING`, `COMMAND`, `PUSH_TO_TALK_ANSWER`, `PUSH_TO_TALK_COMMAND`,
`DECK_SELECTION`, `SHORT_CONFIRMATION`). The purpose drives endpoint profile, watchdog
budgets, vocabulary biasing, candidate count and confidence gates — study code never
configures a recognizer by hand.

Lifecycle (`RecognitionState`):

```
IDLE → PREPARING → READY_FOR_SPEECH → LISTENING → SPEECH_DETECTED
     → PROCESSING → COMPLETED → (IDLE)
                   → FAILED   → (IDLE)
```

**`onEndOfSpeech` ≠ completion.** A turn stays *active* through `PROCESSING`; it becomes
terminal only on `onResults` (`Completed`), `onError` (`Failed`), explicit cancellation, or
a watchdog firing. `isReadyForNewRequest` is false the whole time — which is what makes
`ERROR_RECOGNIZER_BUSY` structurally impossible in normal flow.

## 3. Stale-callback protection

Rules, enforced at two layers:

1. **Orchestrator:** every backend event carries the request id that produced it. If the id
   does not match the active request, the event is dropped and counted
   (`metrics.staleCallbacksDropped`). Cancellation invalidates the id *before* `cancel()` is
   issued, so any in-flight callback is already stale when it arrives.
2. **Repository:** a terminal outcome is additionally checked against the *current card*
   (`outcome.cardId`). A transcript that belongs to a card we already left is discarded —
   it can never be submitted, executed as a command, or alter study state.

Tested scenarios: late result after cancel, late result after a *new request started*
(card A → card B), duplicate final for the same id, error after final, final after cancel,
late partials (transcript never regresses). See `SttReliabilityChaosTest` and
`DefaultStudySessionRepositoryTest`.

## 4. Exactly-once guarantees

**Exactly-once terminal per request (orchestrator).** `activeRequest` is nulled before the
terminal is emitted; a second terminal for the same id is stale by definition and dropped.

**Exactly-once answer submission (repository).** `submitSpokenAnswer` consults a per-card
ledger (`answerSubmittedForCardId`) inside `submissionLock` *before* sending anything. One
`SubmitAnswer` per card per study turn, regardless of how many paths converge: voice final,
pending-transcript confirm, button, reconnection. The ledger resets only when a *different*
card's `Question` arrives (a re-delivered `Question` for the same card — e.g. server resend
after reconnect — must not re-open the window; it is the exact duplicate the ledger absorbs)
and on session start/end.

**Exactly-once rating submission (repository).** Same mechanism (`ratingSubmittedForCardId`).
A rating can arrive from a voice command, a rating button, or both racing each other; the
first wins, the rest are logged and dropped. `rateCurrentCard` also cancels any live rating
recognition *first*, which is the "manual action wins" invariant: a late voice result after
a button tap is dropped by request id.

**Answer/turn windowing.** `submitOrHoldAnswer` only submits while the study state is
`Listening`. An answer-shaped utterance landing in the *rating* window (the interpreter's
deliberate fallback) is ignored — the card was already answered and evaluated; a second
`SubmitAnswer` would make the PC evaluate the same card twice.

## 5. TTS/STT interlock

Invariant: **during normal study flow, TTS-active and STT-active are never both true.**

Enforced in depth:

1. The repository only opens a window when `speechSettled()` — nothing playing, nothing
   queued.
2. The orchestrator re-checks the same predicate through `canOpenMicrophone` (wired in
   `AppContainer` to the speech orchestrator) and rejects with `BUSY (speech-active)`
   otherwise. Retries re-check it too.
3. The TTS→STT transition itself is owned by `VoiceHandoffController`: completion-confirmed
   end + acoustic gap (default 350 ms, clamped 150–1200 ms) before the microphone opens.
   There are no scattered `delay(...)` calls anywhere else in the flow.
4. The deterministic drain backstop (`maybeResumeListeningAfterSpeech`) re-opens the correct
   window after *every* speech terminal, so unusual orderings can never silence the loop.
5. If TTS starts while STT is active (a queued explanation, a STATUS message), the speech
   pipeline wins and the recognition turn is left to finish or be cancelled by the standard
   paths; the backstop restores listening only after everything drains.

Exception policy: none. If a future feature needs overlapped audio, it must be explicitly
architected behind a new gate.

## 6. Bounded retry & recovery

* Per-turn retry budget lives in `RecognitionPolicyFactory.retryDelayMs`, driven by
  `SttSettings.maxRetriesPerTurn` (default 2). `NO_SPEECH` and `NO_MATCH` retry **once**,
  regardless of the configured budget — a quiet room or dead mic must not loop.
* `PERMISSION_DENIED`, `UNAVAILABLE`, `LANGUAGE_UNSUPPORTED`, `LANGUAGE_MODEL_UNAVAILABLE`,
  `CANCELLED` are never auto-retried. The first two surface actionable UI.
* `TOO_MANY_REQUESTS` is never auto-retried *anywhere*: the orchestrator reports it and the
  repository hands control back to the user instead of re-opening the mic into the throttle.
* Busy is retried only after a backoff (`retryBackoffMs * (attempt + 2)`) and only while the
  previous turn is verifiably terminal.
* The repository additionally bounds *consecutive* failures (`MAX_CONSECUTIVE_RECOGNITION_FAILURES
  = 3`): a persistently broken recognizer degrades to on-screen controls instead of spinning
  for the rest of the session.
* Watchdogs are phase- and purpose-specific (ready / first-speech / final-result / total),
  short for ratings, patient for long answers, user-bounded for push-to-talk. A recognizer
  that never calls back cannot wedge the subsystem.

## 7. Interruptions

| Event | Recognition | Transcript | Study state |
|---|---|---|---|
| Pause | `cancel` (id invalidated) | discarded | card preserved, `Paused` |
| End session | `cancel`, ledgers cleared | discarded | `SessionFinished` |
| New card (`Question`) | `cancel` unless duplicate re-delivery of the same card | discarded | advances |
| Connection lost | `cancel` | never submitted | `Error(recoverable)`; reconnect sends `RequestSessionStatus` first |
| Headset/route lost mid-turn | `cancel` | never submitted | card kept; headset re-repeat policy on reconnect |
| Manual action (rating button, repeat, skip, pause…) | `cancel` first — manual wins | late voice dropped by id | follows the user's action |

Push-to-talk:

* `PTT down` interrupts TTS, waits the (halved) acoustic gap, then opens a
  `PUSH_TO_TALK_ANSWER` (or `PUSH_TO_TALK_COMMAND` in the rating/feedback window) turn.
* `PTT up` = `stopListening()` and **wait** for the final result. Release never submits.
* A pending delayed start is invalidated by pause/end/connection-loss
  (`isPushToTalkActive = false`), so the mic can never open into a state that no longer
  expects it.
* PTT outside an active study window (Idle/Paused/Loading/Error/Finished) is refused, not
  silently converted into a listening state.

## 8. Purpose-specific interpretation

* **Answer mode:** only explicit multi-word control phrases ("repeat question", "give me a
  hint", "show answer", "pause study", "end session") may interrupt. A lone word — "good",
  "stop", "next", "again" — inside a medical answer is the answer. Alternatives are retained
  as metadata; the transcript is never rewritten (medical numbers are sacred).
* **Rating mode:** Again/Hard/Good/Easy plus a small navigation set. The candidate *set* is
  evaluated, so `["could", "good", "hood"]` resolves to `Good`. A *reported* confidence below
  `ratingMinConfidence` (0.45) never schedules; between the floor and
  `ratingConfirmThreshold` (0.75) the rating is confirmed or re-listened, never guessed.
  Missing scores fall back to exact-grammar evidence only.
* **Confirmation windows:** after a `NeedsConfirmation` prompt, only verbatim yes/no phrases
  (EN + AR, orthography-normalized) resolve the pending rating; a fresh rating replaces the
  pending one; anything else is ignored rather than submitted as a phantom answer.
* **Destructive commands** (Again, Skip, End session) require a *verbatim* grammar hit — a
  one-edit fuzzy match is never enough, whatever the confidence.
* Arabic normalization (`أ`/`إ`/`ا`, `ة`/`ه`, `ى`/`ي`, diacritics) is applied to **command
  matching only**, never to submitted answers.

## 9. Privacy, performance, battery

* Transcripts are never logged by default — only `purpose= chars= candidates= conf=`
  summaries. Full-text logging requires the developer-only `sttDebugTranscriptLogging`
  setting. Raw microphone audio is never retained.
* RMS and partial results live on separate channels from terminal results; RMS is sampled
  (~16 fps) into a conflated `StateFlow`, partials throttled (100 ms), so final results can
  never be crowded out of the event buffer and Compose recomposition stays bounded.
* Recognition runs only inside explicit windows — no continuous listening, ever. The
  recognizer instance is reused across turns (per-card re-creation was explicitly ruled
  out) and only swapped when the on-device/system backend actually changes.
* 1000-turn simulation (`SttReliabilityChaosTest.one thousand turns...`) verifies: every
  turn terminal exactly once, no duplicate terminal ids, metrics add up, no residue
  (empty transcript, idle state, no request age) after the run.

## 10. Known limitations

* Whether the recognizer honours silence-length extras, biasing strings or language-switch
  extras is provider-dependent; the subsystem treats all of them as hints and never depends
  on them for correctness.
* The *actual* input route (built-in mic vs BT SCO) is not observable from the SDK;
  diagnostics label it "(system-selected)" rather than inventing certainty.
* Mid-turn route *changes* between two external microphones (e.g. wired → BT) are not
  cancelled proactively (only full headset loss is): probing would require heuristics that
  can misfire on devices that raise SCO when recognition starts. The recognizer's own
  `ERROR_CLIENT`/watchdog paths bound the damage.
* Some vendor recognizer services emit duplicate terminals or ignore `cancel()`; both are
  absorbed by the guards above, but they still cost one wasted turn.

## 11. Test map

| Guarantee | Test |
|---|---|
| One terminal per request; busy race | `SpeechRecognitionOrchestratorTest` |
| Duplicate final / final-then-error / cancel-then-final / late partial | `SttReliabilityChaosTest` |
| Bounded NO_SPEECH / NO_MATCH retry | `SttReliabilityChaosTest` |
| Concurrent starts cannot open a second turn | `SttReliabilityChaosTest` |
| 1000-turn marathon, metrics add up, no residue | `SttReliabilityChaosTest` |
| Exactly-once answer / rating; button-vs-voice; stale card; PTT; TTS interlock; connection loss; headset loss; rate limit; confirmation loop; settings (auto-submit, spoken-rating); 10-card marathon | `DefaultStudySessionRepositoryTest` |
| Answer-safety / candidate selection / confidence gates / Arabic normalization | `VoiceCommandInterpreterTest`, `RecognitionPolicyAndVocabularyTest` |
