# GATE 10 — integration work log

## Pre-change production audit (2026-09-24)

The production owner is `AppContainer.studySessionRepository` →
`StudySessionMachineRepository` → `StudySessionMachine` → `StudyReducer`.
`DefaultStudySessionRepository` is retained as a legacy test/migration implementation,
not the production owner. Do not add a parallel Anki machine.

Actual phases: Idle, Starting, WaitingForFirstCard, SpeakingQuestion,
WaitingForAnswer, PendingAnswerReview, SubmittingAnswer, WaitingForEvaluation,
SpeakingFeedback, WaitingForRating, SubmittingRating, SpeakingHint,
SpeakingExplanation, ShowingAnswer, Pausing, Paused, Resuming, Recovering,
Finishing, Finished, Error.

Existing events cover user start/answer/rating/control intents, server session and
card messages, effect-ID-scoped speech completion, recognition completion,
connection/audio changes, timeouts and recovery. Existing effects are Network.Send,
Voice.Speak/CancelSpeech/StartRecognition/CancelRecognition/StopListening,
timeouts, recovery snapshot and diagnostic intents.

### Coupling hotspots (recorded before refactoring)

| File | Symbol | Current assumption | Anki impact | Planned change |
|---|---|---|---|---|
| StudyReducer.kt | handleStart | PC starts the session | local review needs selector and beginReview | typed Anki start/effect results |
| StudyReducer.kt | handleQuestion | server question is complete | scheduler identity is not hydrated content | separate scheduled and hydrated events |
| StudyReducer.kt | handleSubmitAnswer | PC evaluates every answer | manual offline review must work | local answer/reference branch |
| StudyReducer.kt | handleRateCard, handleRatingSaved | remote submission/ACK owns completion | must not send local rating to PC | local pending selection, no advance |
| StudyReducer.kt | handleSkip | remote skip is supported | local skip would hide unresolved turn | reject local skip |
| StudyReducer.kt | pause/resume/end | PC ACK required | local interaction is independent | local control transitions |
| StudySessionMachine.kt | executeEffects | network/voice effects only | backend I/O needs an executor | read-only Anki effect executor |
| StudySessionMachine.kt | observeRecognition | callback mapped using current state | old callbacks can cross operations | require request correlation before local adoption |
| StudySessionMachine.kt | voice effect execution | executor writes active effect IDs | violates strict reducer-only mutation | needs separate correlation cleanup |
| SessionReconciler.kt | reconcile | server is authoritative | cannot restore local turn from PC | reject server reconciliation for bound Anki sessions |
| StudySnapshot.kt | StudySessionSnapshot | remote logical snapshot | no durable local context yet | explicit recovery work remains |
| StudyControlRepository.kt | start/config paths | connectivity and remote deck names | local deck refs require distinct startup handoff | do not reinterpret a deck name as an Anki ref |
| StudyViewModel.kt | repository commands | generic study interaction | preserve UI boundary | expose presentation data, never backend objects |
| StudyScreen.kt | study state rendering | text question and remote rating UI | normalized renderer and scheduler choices needed | pending UI integration |
| AppContainer.kt | studySessionRepository | production machine with voice/connection | backend registry already exists separately | inject read-only executor when ready |

Persistence audit: recovery effect types exist, but the machine executor's catch-all
currently ignores persistence effects; `RecoverPersistedSession` is explicitly
unsupported. No claim of durable process recovery is justified by those types alone.

Build preflight: `./gradlew --stop` cannot run: JAVA_HOME unset and java absent.
No Android SDK location is configured in local.properties or environment.

---

# Final engineering report

## 1. Gate result

**GATE 10: FAIL (partial implementation; validation also BLOCKED).**

This is not a gate lock or a release-ready integration. The implementation gaps
below are independent of the missing build environment. No PASS commit was made.

## 2. StudySession architecture

Implemented opt-in code path (not connected to the dashboard/start UI):

```text
StudySessionMachineRepository.startAnkiStudy(backend-qualified request)
  → StudySessionMachine serialized event channel
  → StudyReducer (same authoritative reducer)
  → AnkiStudyEffectExecutor
      Begin: refresh candidates → selector once → validate deck → beginReview
      Next: locked backend.nextCard
      Hydrate: locked backend.hydrateCardContent
  → correlated result event → reducer
  → SpeakingQuestion → existing SpeechOrchestrator
  → successful audible completion → existing recognition effect
  → final transcript (manual evaluation only)
  → answerRevealed = true → WaitingForRating
  → selectedRating (pending only; NO effects)
```

Anki read jobs are tracked by the machine, cancelled on replacement, stop/error
and close. No backend singleton is cancelled. The executor has no mutation method.

## 3. State machine changes

No phases added, removed or renamed. Reused Starting, WaitingForFirstCard (scheduler
and hydration distinguished by presence of the scheduled turn), SpeakingQuestion,
WaitingForAnswer, WaitingForRating, Paused, Finished and Error.
`SessionMachineState.anki` adds immutable interaction data. No second state owner.

## 4. Events added/changed

`AnkiStudyEvent.Start`: explicit backend-qualified intent.
`Begun`: typed begin outcome plus epoch.
`Scheduled`: typed scheduler outcome plus epoch.
`Hydrated`: typed content result plus epoch and original ReviewTurnId.
`SelectRating`: epoch/turn-scoped pending user selection.

Existing `RecognitionCompleted` and `RecognitionFailed` gain optional request IDs
(default null preserves existing remote callers). Local reducer requires a matching
active request ID; legacy reducer behavior remains unchanged.

## 5. Effects added/changed

`AnkiStudyEffect.Begin`, `Next`, `Hydrate`, `CancelReads` are executed by the
machine using `AnkiStudyEffectExecutor`. Results are events, not state writes.
Existing speech and recognition effects are reused. For local interactions, active
voice effect IDs are reducer-owned rather than overwritten by the effect executor.

## 6. Session context

`AnkiStudyInteraction.request` holds session ID, backend preference, deck ref and
question speech choice. `reviewSession.context` locks backend ID, optional collection
identity, deck ref, capability snapshot, startup time and study session ID.
`reviewSession.backendSessionRef` is the logical backend handle.
`turn` retains `ReviewTurnId`, scheduled card/ref, scheduler options and the hydrated
card. `CardTurn.turnId` is exactly the backend turn ID. Selection/transcript/error/
completion are separate interaction fields. No runtime backend or WebView is stored.

These are in-memory fields, **not a durable persistence implementation**.

## 7. Turn correlation

- Begin/scheduler: epoch + legal phase; result session/backend/deck checked.
- Hydration: epoch + original turn ID + loading phase; Gate 07 identity-verified attach.
- TTS: exact active effect ID + card + SpeakingQuestion phase.
- STT: current turn + card + request ID + WaitingForAnswer phase.
- Renderer: **not integrated**; no renderer callbacks accepted by this path.
- AI: **not integrated**; remote evaluation callbacks rejected for bound Anki sessions.

Central local dispatch in StudyReducer is fail-closed. Remote snapshots, question
messages, rating ACKs, skip and unsupported commands cannot change the local turn.
An old result from a stopped/restarted epoch is rejected. Duplicate terminal events
cannot trigger a second transition.

## 8. Question flow

Hydration confirms the scheduled identity before creating the generic question
presentation. Speech text comes only from `questionText`; never HTML. The existing
speech subsystem's Completed result is the signal to request recognition. Failed
speech leaves the same card in the answer window with no automatic microphone.
Voice-disabled requests produce no Speak or StartRecognition effects.

Visual renderer readiness is not a prerequisite for speech. **Actual renderer/UI
binding is unfinished**, including HTML-only presentation and visual failure fallback.

## 9. Evaluation flow

Only manual evaluation is implemented in this slice: a nonblank final transcript is
stored outside card content and reveals the reference-answer state. No PC request is
sent. AI-assisted/required provider policies, normalized evaluator input, feedback,
AI suggestions and AI failure degradation remain **unimplemented**, not silently
presented as supported configuration.

## 10. Answer reveal

A user reveal or valid final transcript sets `CardTurn.answerRevealed` and enters
WaitingForRating without changing ReviewTurnId. The normalized source card remains
in the turn. **The StudyScreen does not yet read this binding or switch its renderer
to ANSWER.** This is a definition-of-done blocker, not a completed answer experience.

## 11. Rating state

- Suggested rating: existing `CardTurn.suggestedRating`; absent on manual Anki path.
- Selected rating: `AnkiStudyInteraction.selectedRating`, pending only.
- Committed rating: **NOT IMPLEMENTED** for this integration.

Only `AnkiRatingOptions.Known` choices are accepted. Unmapped/unsupported choices
are rejected. Repeated selection is rejected, even if a different rating is chosen.
Scheduler interval metadata survives hydration, but native option/label UI remains
unwired. No review counter or submission ledger claim is created by selection.

## 12. Advancement safety

**Can selecting a rating request the next Anki card? NO.**

The selection transition emits an empty effect list. Next is emitted only when a
new Anki review session begins successfully. There is no commit effect or commit
invocation in the integration. Existing remote rating behavior is left intact.

## 13. Pause / resume

SpeakingQuestion, WaitingForAnswer and WaitingForRating can pause locally without
PC ACKs. Voice is cancelled and active effect IDs cleared. Resume keeps the same
turn and returns to a manual answer window or rating window; explicit Repeat can
restart speech. Selection survives pause. No scheduler query occurs.

Pause during startup/hydration is rejected (Stop is available). AI pause is not
implemented because AI is not integrated. PTT, hints and explanations are currently
rejected on this opt-in path. Route/background/headset behavior still needs full
integration and machine-level tests.

## 14. Error recovery

- Backend/hydration failure: retain typed AnkiError; stop read jobs, enter Error.
  No silent backend switch, retry, skip or rating. Stop is supported.
- Wrong hydrated identity: StaleCardReference; never presented or spoken.
- TTS failure: retain card; manual reveal or Repeat.
- STT failure: request-scoped error; retain card; manual reveal or Repeat.
- Renderer/media degradation: existing normalized content retained; UI handling pending.
- AI failure: not implemented.
- Collection change: hydration identity mismatch fails closed; backend typed errors
  retained. No ongoing collection/deck invalidation observer is added.
- Backend loss after hydration: in-memory interaction remains usable; no further
  Anki reads/writes during answer/rating. Live availability UI is pending.

Backend contract has no close/abandon-review operation. Cancelling a read does not
prove the backend discarded its active session/turn. Releasing an unresolved
backend handle on Stop/restart needs an explicit read-only lifecycle contract;
this patch does not invent a scheduler mutation to solve it.

## 15. Offline / hybrid modes

**No runtime provider combination was verified.** Tests are authored for fake local
backend + manual evaluation, both voice-event and voice-disabled paths. These are
not evidence of Android TTS/STT or AnkiDroid device interoperability.

## 16. Diagnostics

Reuses bounded transition/rejection history and machine diagnostic logging. New
Anki event names appear in existing transition traces. No raw card content or
transcript logging was added. Dedicated Anki metadata export, renderer metrics,
per-operation latencies and complete phase diagnostics remain unfinished.

## 17. Tests and commands

15 deterministic tests were added in `AnkiStudyInteractionTest` covering read-executor
integration, no mutations, no advancement after selection, manual/voice-disabled
flow, TTS failure, duplicate/stale events, request generation across Repeat,
pause/resume, remote-message isolation, exhaustion, Stop/restart, hydration
mismatch, missing backend, foreign/deleted decks and scheduler option validation.
They have **NOT RUN**. The suite is reducer/executor-level, not an executed full
machine/Android end-to-end or chaos harness.

| Exact command | Actual result |
|---|---|
| `./gradlew --stop` | exit 1; Java unavailable |
| `./gradlew clean` | exit 1; Java unavailable; clean did not execute |
| `./gradlew testDebugUnitTest` | exit 1; Java unavailable; no tests executed |
| `./gradlew lint` | exit 1; Java unavailable; lint did not execute |
| `./gradlew assembleDebug` | exit 1; Java unavailable; no APK built |
| `./gradlew assembleRelease` | exit 1; Java unavailable; no APK built |
| `git diff --check` | PASS; whitespace check only |

Wrapper error: JAVA_HOME is not set and no java command is in PATH. Attempted JDK
installation could not reach Debian package repositories and could not locate the
package. An HTTPS probe also failed. No Android SDK is configured and adb is absent.
No SDK/build configuration or safety baseline was weakened to work around this.

## 18. Device / hardware validation

**NOT RUN — required device/audio environment unavailable.**
`connectedDebugAndroidTest` not run. No WebView, headset, screen-off, route-loss,
process-death or real AnkiDroid scheduler checks were performed.

## 19. Files added

- `core/study/AnkiStudyInteraction.kt`: immutable binding, requests, typed events/effects.
- `core/study/AnkiStudyEffectExecutor.kt`: selector/begin/scheduler/hydration reads.
- `test/.../study/AnkiStudyInteractionTest.kt`: 15 deterministic safety tests.
- `docs/GATE_10_STUDY_SESSION.md`: pre-change audit, hotspots and this report.

Paths under core are relative to `app/src/main/java/com/studyagent/client`;
the test file is under `app/src/test/java/com/studyagent/client`.

## 20. Files modified

- `core/study/SessionMachineState.kt`: optional local Anki binding.
- `core/study/StudyEvent.kt`: recognition request correlation.
- `core/study/StudyReducer.kt`: local read-only interaction branch in existing reducer.
- `core/study/StudySessionMachine.kt`: owned read job/executor and local voice correlation.
- `data/repository/StudySessionMachineRepository.kt`: explicit qualified startup entry point.
- `di/AppContainer.kt`: inject read executor with existing registry.

UI, provider implementations, WebView renderer, scheduler mutation, preference schema
and legacy repository are not modified.

## 21. Invariants verification

Statuses below are **implementation review**, not runtime certification. PASS means
the new code path structurally enforces the property; none of the new tests ran.
FAIL denotes a known incomplete requirement. NOT YET TESTABLE means runtime/product
integration is absent or validation is blocked.

| INV-ANKI-SESSION | Status | Evidence / limitation |
|---|---|---|
| 01 | PASS | Same machine/reducer, no second state owner |
| 02 | PASS | Next delegates to backend scheduler; no local due selection |
| 03 | PASS | Immutable review context; one logical backend |
| 04 | PASS | Next/hydrate look up only locked ID |
| 05 | PASS | Scheduled event accepted only with no existing turn |
| 06 | PASS | CardRef and ReviewTurnId remain separate |
| 07 | NOT YET TESTABLE | Preserved in manual flow; AI/presentation integration incomplete |
| 08 | PASS | Gate 07 attach retains original turn |
| 09 | PASS | answerRevealed changes CardTurn only; renderer wiring pending |
| 10 | PASS | Local branch transitions and active voice IDs owned by reducer |
| 11 | PASS | Added read executor returns typed events |
| 12 | FAIL | Read/TTS/STT correlated; renderer/AI not integrated |
| 13 | NOT YET TESTABLE | Guards authored; full subsystem matrix not run |
| 14 | NOT YET TESTABLE | Phase/epoch/effect guards; chaos suite not executed |
| 15 | NOT YET TESTABLE | Duplicate tests authored but not run |
| 16 | NOT YET TESTABLE | Completed success gates listening; audible hardware invariant unverified |
| 17 | PASS | Speak receives questionText only |
| 18 | NOT YET TESTABLE | No AI adapter yet |
| 19 | PASS | No AI or scheduler write path added |
| 20 | PASS | suggestedRating separate from selectedRating; committed absent |
| 21 | PASS | No commit/write call in integration executor or reducer |
| 22 | PASS | Selection does not update ledger/review count |
| 23 | PASS | Selection emits zero effects |
| 24 | PASS | No renderer authority introduced (renderer integration unfinished) |
| 25 | PASS | No renderer commit route introduced |
| 26 | PASS | Voice failures retain card and cannot request Next/rating |
| 27 | NOT YET TESTABLE | AI failure policy not implemented |
| 28 | FAIL | Core manual path has no PC calls, but local product startup/UI is unwired |
| 29 | NOT YET TESTABLE | Speech choice independent of content; evaluation configuration missing |
| 30 | PASS | Pause only cancels voice, retains turn |
| 31 | PASS | Repeat speaks existing CardTurn; no Next/hydrate |
| 32 | PASS | Local hint is rejected, no Anki mutation |
| 33 | PASS | Local explanation is rejected, no Anki mutation |
| 34 | NOT YET TESTABLE | No local renderer failure event/policy integrated |
| 35 | NOT YET TESTABLE | Media/UI degradation integration absent |
| 36 | PASS | Stop cancels jobs/voice; no rating effect |
| 37 | PASS | State has only logical backend identity; no runtime implementation object |
| 38 | PASS | No WebView/Context in state |
| 39 | FAIL | Durable restore/reconciliation not implemented; no recovery Next path added |
| 40 | NOT YET TESTABLE | Remote branch preserved; regression suite could not execute |

## 22. Legacy PC regression

**NOT VERIFIED.** Existing remote start/rating/reconciliation branches are retained,
and optional constructor/event parameters preserve call sites. The PC regression
suite did not execute; this is not a green regression claim.

## 23. Deferred work

Before Gate 10 can pass: finish local deck/start UI handoff and repository API,
normalized Original/Clean/Voice presentation and answer-side binding, native
scheduler-option pending-rating UI, optional/required AI adapter and correlation,
manual/PTT/route/lifecycle recovery, backend handle release semantics, durable
same-turn recovery, complete diagnostics and full machine/chaos/regression/device
validation. These are **unfinished Gate 10 work**, not shifted to later gates.

Still intentionally out of scope:
- **GATE 11 — Rating Commit, Scheduler & Exactly-Once Reliability**
- **GATE 12 — Answer Reveal, Reference Answer & Compare Experience polish**
- **GATE 13 — Reviewer Actions**

## 24. Final decision

**NO — not ready for Gate 11.** UI/renderer and AI integration, durable recovery,
lifecycle completion and validation are outstanding. Gate 11 was not started.
The partial read-only path stops at pending selection and does not claim an Anki
review was completed. No gate-lock commit was created.
