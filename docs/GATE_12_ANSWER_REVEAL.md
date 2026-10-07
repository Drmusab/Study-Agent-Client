# GATE 12 — Answer Reveal, Reference Answer & Compare Experience

## 1. Purpose & Scope

GATE 12 defines the post-answer review experience for an Anki review turn:

```text
Question
  -> User answer (spoken final transcript or manual reveal)
  -> Optional AI evaluation
  -> Reveal original Anki answer
  -> Compare user answer vs reference answer (Original | Clean | Compare)
  -> Review AI feedback & advisory suggested rating
  -> Choose rating (Again / Hard / Good / Easy)
  -> GATE 11 commit pipeline (SelectRating -> CommitRating -> COMMITTED barrier)
```

GATE 12 is strictly a review-turn presentation, comparison, and audio-orchestration layer:
- It never reopens scheduler authority (GATE 06), card hydration semantics (GATE 07), WebView renderer isolation (GATE 08), media URI resolution (GATE 09), or rating transaction semantics (GATE 11).
- Revealing an answer or switching compare modes never calls `nextCard` or `commitRating`.
- AI `suggestedRating` is strictly advisory; only an explicit user rating action enters the GATE 11 commit pipeline.

---

## 2. Three Answer Representations

Every hydrated `AnkiRenderedCard` carries three distinct answer channels that GATE 12 keeps strictly separated:

| Channel | Field | Purpose | Rules |
|---|---|---|---|
| **Original visual answer** | `answerHtml` | Authoritative formatted card back rendered via GATE 08/09 (`AnkiCardView`) | Preserved verbatim (`{{FrontSide}}`, `<hr id=answer>`, Cloze, tables, CSS, RTL, images/audio). Never flattened to plain text. |
| **Clean / speech / accessible answer** | `answerText` | Clean reference answer for `CLEAN` mode, `COMPARE` reference block, accessibility, and TTS (`RepeatAnswerRequested`) | Never reconstructed by stripping `answerHtml` in the UI. Distinguishes `null` (`CleanAnswerState.MISSING`) from `""` (`CleanAnswerState.EMPTY`). |
| **Evaluator / raw reference answer** | `pureAnswerText` | Primary reference input for `AnkiAnswerEvaluator` (`card.evaluationAnswerText = pureAnswerText ?: answerText`) | Never sends raw `answerHtml` to the AI evaluator. Optionally viewable as raw evaluator reference in `COMPARE` mode. |

---

## 3. Core Models & State Machine Integration

- **`AnswerRevealState`**: `HIDDEN`, `REVEALED`. While `HIDDEN`, `AnswerReviewModel.from(...)` withholds `answerHtml`, `answerText`, `pureAnswerText`, `referenceAnswerText`, and `rawReferenceAnswerText` so the UI cannot leak hidden answer content before reveal.
- **`AnswerCompareMode`**: `ORIGINAL`, `CLEAN`, `COMPARE`. Switching modes via `StudyEvent.SelectAnswerCompareMode` updates `AnkiStudyInteraction.compareMode` on the same `ReviewTurnId` without re-evaluating or re-querying the backend.
- **`AnswerEvaluationStatus`**: `NOT_REQUESTED`, `EVALUATING`, `COMPLETED`, `UNAVAILABLE`. When AI evaluation is disabled or fails, the turn remains valid and manual rating stays enabled.
- **`AnswerAudioSequencePhase`**:
  1. `IDLE`
  2. `STOPPING_QUESTION_AND_STT`
  3. `VISUAL_REVEALED`
  4. `CARD_MEDIA`
  5. `SPEAKING_ANSWER` / `SPEAKING_FEEDBACK`
  6. `ACOUSTIC_GAP`
  7. `LISTENING_FOR_RATING`
- **`AnswerReviewModel` & `AnswerReviewUiState`**: Pure, backend-neutral projections exposed via `StudySessionMachine.answerReview`, `StudySessionRepository.answerReview`, and `StudyViewModel.answerReview`, rendered by `AnkiAnswerReviewSection`.

---

## 4. Architectural Invariants (`INV-12-01` .. `INV-12-25`)

- **INV-12-01**: Answer content is hidden until the active turn enters `AnswerRevealState.REVEALED`.
- **INV-12-02**: Answer reveal operates on the current `ReviewTurnId` and never calls `nextCard` or rehydrates from the scheduler.
- **INV-12-03**: No hidden answer HTML or text is exposed in visible UI state or spoken before reveal.
- **INV-12-04**: `ORIGINAL` answer mode renders `answerHtml` without destructive flattening or HTML stripping.
- **INV-12-05**: `CLEAN` answer mode uses `answerText` and never re-parses `answerHtml` in UI code.
- **INV-12-06**: `COMPARE` mode displays user answer and reference answer as distinct, labeled blocks.
- **INV-12-07**: Canonical `userAnswerText` uses only the final accepted transcript, never partial STT hypotheses.
- **INV-12-08**: AI evaluation uses `pureAnswerText` (or `answerText` fallback), never raw `answerHtml`.
- **INV-12-09**: AI evaluation is optional; review reveal, compare, and rating work without AI.
- **INV-12-10**: AI `suggestedRating` is advisory only and never auto-commits a rating.
- **INV-12-11**: Rating buttons and interval labels come from the active turn's scheduler metadata, never local scheduling math.
- **INV-12-12**: Dynamic scheduler button counts (2, 3, or 4 buttons) are respected.
- **INV-12-13**: Selecting a rating enters the GATE 11 commit pipeline unchanged (`SelectRating` -> `CommitRating`).
- **INV-12-14**: Rating controls lock while a commit is in `Saving`, `RetryAvailable`, or `VerificationRequired`.
- **INV-12-15**: Revealing an answer while STT is listening cleanly cancels recognition.
- **INV-12-16**: Card audio, TTS feedback, and STT never overlap simultaneously.
- **INV-12-17**: Repeat answer (`RepeatAnswerRequested`) and repeat feedback (`RepeatFeedbackRequested`) do not re-evaluate or mutate turn identity.
- **INV-12-18**: Spoken `"show answer"` / `"reveal answer"` reveals the answer and never triggers a rating commit.
- **INV-12-19**: `answerHtml` render failure falls back cleanly to `CLEAN` `answerText` when available.
- **INV-12-20**: Missing (`null`) or empty (`""`) `answerText` is handled honestly without speaking raw HTML.
- **INV-12-21**: Answer media is scoped to the revealed turn and cancelled on turn exit, pause, or session end.
- **INV-12-22**: Pause/resume and UI recomposition preserve reveal state, compare mode, transcript, and evaluation without re-speaking automatically.
- **INV-12-23**: Stale evaluation, render, or speech callbacks from a prior turn are ignored.
- **INV-12-24**: Arabic and mixed RTL/LTR user answers, reference answers, and AI feedback preserve text direction and diacritics.
- **INV-12-25**: GATE 12 diagnostics and logs are metadata-only and never record card HTML, answer text, user transcripts, or AI feedback text.
