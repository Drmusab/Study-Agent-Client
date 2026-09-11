# STT Architecture

Speech-to-text as a first-class Study Agent subsystem.

This document describes the implementation as it exists in the code, not an aspiration.
Every claim here maps to a file.

---

## 1. Why this exists

A flashcard answer and a card rating are acoustically identical to `SpeechRecognizer`, but
they carry opposite risk. A slightly-wrong answer costs nothing — the PC-side evaluator
absorbs it. A slightly-wrong rating silently reschedules an Anki card. The previous
implementation treated both the same way: one `startListening(languageCode, isHandsFree)`
call, one `Boolean` of state, one code path.

The subsystem below makes that distinction structural: recognition always carries a
**purpose**, and the purpose drives endpointing, watchdog budgets, vocabulary biasing,
candidate selection, acceptance thresholds and retry behaviour.

---

## 2. Layering

```
StudySessionRepository          what to listen for, and what a result means for study state
        │
        ▼
SpeechRecognitionOrchestrator   one owner of the microphone: lifecycle, watchdog, retry,
        │                       rate limit, stale-callback rejection, metrics
        ▼
RecognitionPolicyFactory        purpose → RecognitionRequest (timeouts, endpoint, bias)
        │
        ▼
SpeechRecognitionBackend        contract: one recognizer, request-stamped events
        │
        ▼
AndroidSpeechRecognitionBackend SpeechRecognizer / RecognitionListener / RecognizerIntent
```

Supporting services, all framework-free and unit-tested:

| Component | Responsibility |
|---|---|
| `RecognitionCandidateSelector` | which hypothesis represents the turn |
| `VoiceCommandGrammar` | the controlled command vocabulary |
| `VoiceCommandInterpreter` | context + confidence → a decision |
| `CommandNormalizer` | command vs. answer text normalization |
| `MedicalVocabularyProvider` | bounded contextual bias list |

The repository shrank in responsibility even though it grew in lines: recognizer
lifecycle, retry, configuration, candidate selection, language policy and endpointing all
moved out. It now decides *what to listen for* and *what a result means for the study
session*, and nothing about recognizers.

---

## 3. The state machine

`RecognitionState` (`RecognitionModels.kt`):

```
IDLE → PREPARING → READY_FOR_SPEECH → LISTENING → SPEECH_DETECTED
     → PROCESSING → COMPLETED → (next turn)
```

Any state → `FAILED` → recovery policy → `IDLE` / bounded retry.

**The invariant that matters:** `onEndOfSpeech()` moves to `PROCESSING`, not idle. A turn is
only finished by a terminal callback (`onResults` / `onError`) or an explicit `cancel()`.

`isReadyForNewRequest` is derived from that state, and it — never a boolean — is what
gates the next turn. This is the fix for `ERROR_RECOGNIZER_BUSY`: the old code set
`isListening = false` inside `onEndOfSpeech()` and inside `stopListening()`, so a second
`startListening()` could reach a recognizer that was still finalising.

A derived `isListening: StateFlow<Boolean>` is still exposed for UI compatibility, but no
internal decision reads it.

---

## 4. Request identity and stale callbacks

Every turn gets an id: `answer_<seq>`, `rating_<seq>`, `command_<seq>`, plus the `cardId` it
belongs to.

Two independent guards:

1. **Request id.** The backend stamps every event with the id of the turn that produced it.
   The orchestrator drops any event whose id is not the active one and counts it in
   `metrics.staleCallbacksDropped`.
2. **Card id.** `handleCompletedTurn` compares `outcome.cardId` against the card currently
   on screen and discards a mismatch.

`cancelCurrentTurn(reason)` invalidates the active request *before* calling
`backend.cancel()`, so any in-flight callback is already stale when it lands. Pause, end
session, new question, skip card and connection loss all go through this path — a delayed
final result can no longer submit an answer into a session that has moved on.

---

## 5. Purposes and policies

`RecognitionPurpose`: `ANSWER`, `RATING`, `COMMAND`, `PUSH_TO_TALK_ANSWER`,
`PUSH_TO_TALK_COMMAND`, `DECK_SELECTION`, `SHORT_CONFIRMATION`.

`RecognitionPolicyFactory` resolves each into a `RecognitionRequest`:

| Purpose | Endpoint profile | Total watchdog | First-speech watchdog |
|---|---|---|---|
| `RATING`, `SHORT_CONFIRMATION` | `SHORT_COMMAND` | 15 s | 6 s |
| `COMMAND` | `SHORT_COMMAND` | 20 s | 8 s |
| `ANSWER` (Normal) | `NORMAL_ANSWER` | 45 s | 12 s |
| `ANSWER` (Long) | `LONG_ANSWER` | 120 s | 20 s |
| `PUSH_TO_TALK_ANSWER` | `PUSH_TO_TALK` | user-bounded | 60 s |

**Silence extras are off by default.** `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS` are
documented by Android as producing "unexpected behavior" and are ignored by several
recognizers, so `EndpointPolicy.applySilenceHints` is `false` for every default profile.
Endpoint behaviour is bounded by the watchdog, not by hints that may do nothing.

---

## 6. Candidates and confidence

`onResults` now reads **all** alternatives plus `RecognizerIntent.EXTRA_CONFIDENCE_SCORES`,
producing `List<RecognitionHypothesis(text, confidence, rank)>`.

Confidence is nullable end-to-end. Many recognizers never populate the scores array, and
inventing a value would make every downstream safety gate meaningless. Where a score is
absent, grammar strength alone decides — otherwise spoken ratings would be impossible on
every device that does not publish scores.

Selection (`RecognitionCandidateSelector`):

- **Answers** — rank 0, always. Confidence scores are not documented as comparable *across*
  alternatives, so a higher-scoring lower-ranked hypothesis is never substituted in; that
  would silently alter a medical transcript. Alternatives are kept as metadata.
- **Ratings/commands** — highest-confidence `EXACT` grammar match, else `HIGH`, else an
  `AMBIGUOUS` match surfaced for confirmation. So `["could" 0.53, "good" 0.92]` while a
  rating is expected resolves to `Good` — and the *same* hypotheses during an answer resolve
  to the answer `"could"`.

---

## 7. Command safety

`VoiceCommandGrammar` is one explicit phrase table. Two changes from the previous parser:

1. **No prefix matching.** `matchesAgain` used `startsWith("again")`, so the ordinary answer
   *"again, there is a midline shift"* rated the card `Again`. Every phrase is matched whole;
   fuzzy matching is bounded to a single edit on a single word of length ≥ 4.
2. **Context is authoritative.** `VoiceCommandInterpreter` decides whether speech is a
   command at all:

| Context | What may fire |
|---|---|
| `ANSWER_EXPECTED` | only explicit multi-word phrases: "repeat question", "end session", "show answer", … |
| `RATING_EXPECTED` | ratings + navigation; anything else is submitted as a re-answer |
| `FEEDBACK_SHOWING` | ratings + navigation |
| `PAUSED` | `Resume`, `EndSession`, `StopSpeaking`, `StatusQuestion` only |
| `IDLE` | `StartStudy`, `Resume`, `StatusQuestion` only |

A lone word — "good", "stop", "next", "easy" — never fires during an answer.

**Destructive commands (`Again`, `Skip`, `EndSession`) require a verbatim grammar hit.** A
one-edit fuzzy match is not enough however confident the recognizer claims to be: "stoop" →
"stop" would otherwise end a study session.

Rating acceptance (`SttSettings`):

| Confidence | Behaviour |
|---|---|
| ≥ 0.75 (`ratingConfirmationThreshold`) | apply |
| 0.45–0.75 | ask for confirmation if enabled, otherwise re-listen |
| < 0.45 | rejected; re-listen |
| absent | apply only on an `EXACT` phrase |

---

## 8. Bilingual recognition

`RecognitionLanguageMode`: `AUTO_EN_AR` (default), `ENGLISH`, `ARABIC`.

On **API 34+** with `AUTO_EN_AR`, the request sets `EXTRA_ENABLE_LANGUAGE_DETECTION`,
`EXTRA_ENABLE_LANGUAGE_SWITCH` (`LANGUAGE_SWITCH_BALANCED`), and both
`*_ALLOWED_LANGUAGES` extras restricted to a two-language allowlist. `onLanguageDetection`
is surfaced as a `LanguageDetected` event for diagnostics only — the user is never
interrupted to be told the language changed.

Below API 34, or when the recognizer ignores the extras (which it is explicitly permitted to
do), the request still works: it runs in `autoFallbackLocale`. **Two recognizers are never
run against the microphone at once.**

Arabic command matching normalizes `أ إ آ ٱ → ا`, `ى → ي`, `ة → ه`, and strips tashkeel and
tatweel — so `أعد`/`اعد` and `إنهاء`/`انهاء` both match. This normalization applies to
**commands only**; answer text is normalized for whitespace and stray edge punctuation and
nothing else.

---

## 9. Backend selection and capabilities

`RecognitionCapabilities` is populated from `isRecognitionAvailable`,
`isOnDeviceRecognitionAvailable` (API 31+) and `checkRecognitionSupport` (API 33+, async).
Note that the platform exposes only the three-argument
`checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)` — there is no
two-argument overload, so a direct executor is supplied; the call already runs on the main
thread, so the callback lands there too.
`null` means "could not determine" and renders as **Unknown** in Diagnostics — never coerced
to `false`, which would tell the user offline recognition is unavailable when nobody asked.

`AUTO` / `PREFER_ON_DEVICE` resolve to the on-device recognizer only when it is available
**and** the requested language's model is not known to be missing. The app never shows
"Offline" unless it actually knows on-device recognition is in use.

One recognizer instance is created and reused across the whole session; it is replaced only
when the backend kind actually changes.

Model downloads (API 33+ `triggerModelDownload`) are **user-initiated only** — a button in
Settings, shown when a language is supported but not installed.

---

## 10. TTS → STT handoff

Three independent gates, no scattered sleeps:

1. `canOpenMicrophone` — supplied by the container as "nothing playing and nothing queued".
   The orchestrator refuses to start while it returns false.
2. `VoiceHandoffController` — the existing acoustic gap (default 350 ms) lets A2DP/SCO
   buffers drain after TTS finishes.
3. Request identity — a late callback from a previous turn is dropped by id.

Push-to-talk interrupts TTS deliberately: `stopSpeech(USER)`, a short gap, then recognition
starts. Releasing the button calls `finishCurrentTurn()` (`stopListening`, not `cancel`) and
**submits nothing**; the transcript is submitted when `onResults` arrives.

---

## 11. Microphone route

`AudioRouteManager` now enumerates `GET_DEVICES_INPUTS` as well as outputs. This is what
makes the distinction real: `TYPE_BLUETOOTH_A2DP` is output-only and never appears in the
input list, whereas `TYPE_BLUETOOTH_SCO` appearing there means the communication profile —
and therefore a usable headset microphone — is actually available.

`InputRouteInfo.isCertain` is `false` for anything inferred. Wired/USB headsets are certain;
Bluetooth is reported as "Bluetooth headset microphone (system-selected)", because Android
does not publish which input a `SpeechRecognizer` picked. Losing the route mid-turn cancels
recognition; it never submits a half-recognised answer.

---

## 12. Failure handling

`RecognitionErrorCode` covers every `SpeechRecognizer.ERROR_*` constant in the target SDK,
including the API 31/33 additions (`ERROR_SERVER_DISCONNECTED`, `ERROR_TOO_MANY_REQUESTS`,
`ERROR_LANGUAGE_NOT_SUPPORTED`, `ERROR_LANGUAGE_UNAVAILABLE`,
`ERROR_CANNOT_CHECK_SUPPORT`). Unknown codes map to `INTERNAL` and degrade safely.

`NO_SPEECH` and `NO_MATCH` are **distinct** — different prompts, different retry budgets.

Retry is bounded by `SttSettings.maxRetriesPerTurn` (default 2) with per-error backoff
(`RecognitionPolicyFactory.retryDelayMs`). Permission, unavailability, unsupported language
and missing model are never auto-retried. The repository adds a second bound
(`MAX_CONSECUTIVE_RECOGNITION_FAILURES = 3`) so a dead microphone cannot re-open itself for
the rest of the session, and falls back to the on-screen controls.

Phase-specific watchdogs (`readyMs`, `firstSpeechMs`, `finalResultMs`, `totalMs`) guarantee a
terminal outcome even from a recognizer that never calls back — without them, one silent
recognizer stalls the study loop permanently.

Rate limiting (`minTurnIntervalMs`, default 250 ms) prevents TTS completion, a UI tap and the
hands-free loop from all opening the microphone within the same instant.

---

## 13. Privacy

- **No transcript logging by default.** Completion logs
  `purpose=ANSWER chars=146 candidates=3 conf=0.87 source=ON_DEVICE finalizeMs=410`.
  Full text requires the explicit `sttDebugTranscriptLogging` opt-in.
- **No raw audio.** `onBufferReceived` is empty; nothing is retained.
- **Metrics store no text** — timings, error categories, confidences, purposes.
- **Diagnostics export is redacted by construction**, since the logger never receives
  transcript text unless the user turned that on.
- **No expected-answer leakage.** The bias list is built from the question and a curated
  term list; the PC agent's answer never reaches the client.

---

## 14. Event volume

| Channel | Type | Volume |
|---|---|---|
| `state` | `StateFlow<RecognitionState>` | one per transition |
| `partialTranscript` | `StateFlow<String>` | conflated |
| `audioLevel` | `StateFlow<Float>` | conflated, sampled to ~16 fps, ≥60 ms apart, ≥0.75 dB delta |
| `turnResults` | `SharedFlow` | one per turn |
| `languageEvents` | `SharedFlow` | rare |

`onRmsChanged` and `onPartialResults` are sampled in the backend (60 ms / 100 ms) before
emission. Levels and partials travel on conflated `StateFlow`s, so a chatty recognizer
cannot evict a final result from a shared buffer.

---

## 15. Settings

| Setting | Persisted | Effect |
|---|---|---|
| Language (Auto EN+AR / English / Arabic) | `stt_language_mode` | language mode + API 34 detection |
| Recognition (Auto / Prefer on-device / System) | `stt_recognition_mode` | backend selection |
| Prefer Offline Recognition | `stt_prefer_on_device` | on-device preference |
| Auto-submit Answers | `auto_submit_transcript` | submit vs. hold for review |
| Spoken Ratings | `listen_for_spoken_rating` | whether the mic opens for ratings at all |
| Confirm Ambiguous Ratings | `confirm_rating` | ask vs. re-listen below threshold |
| Answer Length (Short/Normal/Long) | `stt_answer_length` | endpoint profile + watchdog |
| Live Partial Transcript | `stt_show_partial_transcript` | partial results |
| Medical Vocabulary Biasing | `stt_medical_biasing` | `EXTRA_BIASING_STRINGS` |
| Log Full Transcripts | `stt_debug_transcript_logging` | developer opt-in |

Migration: installs predating language modes keep their single `stt_language` — an `ar-*`
value becomes `ARABIC` with that locale, anything else `ENGLISH`. Every key is read with a
default, so a missing key can never crash.

`listen_for_spoken_rating` previously had **no DataStore key at all**: the toggle existed in
`AppSettings` and was silently reset on every launch. It is now persisted and honoured.

---

## 16. Testing

`FakeSpeechRecognitionBackend` scripts every Android callback, so nothing needs a
microphone, emulator or provider.

53 tests exist. **32 were executed and pass** (`VoiceCommandInterpreterTest`,
`RecognitionPolicyAndVocabularyTest`); the 21 in `SpeechRecognitionOrchestratorTest` were not
run, because they need `kotlinx-coroutines-test`. See `docs/STT_ENGINEERING_REPORT.md` §0 and §6
for exactly what was and was not verified, and §4.16 for the defects the compiler and the
executed tests caught.

Coverage in `app/src/test/java/com/studyagent/client/stt/`:

- normal answer → exactly one terminal result
- **PTT does not submit at release**, only on the final result
- second start while a turn is in flight → `BUSY`, recognizer sees one request
- **stale callback from a cancelled turn cannot reach the next card**
- busy errors retried with backoff, stopping at the configured bound
- recognition never starts while speech is active
- silent recognizer bounded by the watchdog
- rate limiting
- 500 partials collapse to one conflated value and still yield one result
- on-device chosen only when actually available; not claimed for a missing model
- permission denied → no retry, no recognizer contact
- no-speech and no-match stay distinct
- candidate alternatives and confidences preserved
- language detection events surfaced
- rating alternatives resolved contextually; low confidence never silently rates
- destructive commands require a verbatim phrase
- Arabic orthographic normalization
- bias list bounded, de-duplicated, purpose-scoped, no answer leakage
- settings bridge and safe fallbacks for unknown values

---

## 17. Compatibility

| | Behaviour |
|---|---|
| **API 26–30** | System recognizer only. No on-device, no capability query, no biasing. Everything else — state machine, purposes, candidate selection, command safety, watchdogs, retry — is identical. |
| **API 31–32** | Adds on-device recognizer selection and the modern error codes. |
| **API 33** | Adds `checkRecognitionSupport`, vocabulary biasing, model download. |
| **API 34+** | Adds language detection and language switching. |

No feature is required for core function; every one degrades to the plain system
recognizer.

---

## 18. Known limitations

- Android does not publish which input route a `SpeechRecognizer` selected, so Bluetooth
  microphone status is always reported as inferred, never certain.
- `EXTRA_PREFER_OFFLINE`, `EXTRA_BIASING_STRINGS` and the silence-length extras may be
  ignored by any given recognizer. Nothing depends on them.
- Language detection/switching depends on installed models; where they are absent, `AUTO`
  falls back to a single locale.
- Segmented recognition and word-level timing are **not** implemented. Neither is needed for
  the current turn-based flow, and both would add capability-gating complexity without a
  demonstrated benefit for flashcard-length answers.
- Recognition is turn-based by design. There is no continuous open-microphone mode.
