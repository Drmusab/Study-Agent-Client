# TTS & Voice Architecture (Post-Refactor, 2026-09-11)

This document describes the rebuilt speech-output subsystem. For what was broken
before and how each defect maps to a fix, see `docs/TTS_AUDIT.md`.

---

## 1. Design goals

Hours-long, hands-free, bilingual (Arabic/English) medical study over Bluetooth
headphones, screen off, without:

- TTS↔STT overlap (the recognizer hearing the app's own voice),
- leaked / orphaned engine callbacks or hung speech requests,
- utterances lost during engine init,
- one engine instance per screen (there is exactly **one** engine per app),
- private content blasting over the loudspeaker after a headset pull,
- full medical text ever reaching the logs.

## 2. Layering and ownership

```
Study logic (StudySessionRepository) ── what to say, when to listen, recovery
        │  SpeechRequest / SpeechResult
        ▼
SpeechOrchestrator ── the ONLY speech API (DefaultSpeechOrchestrator)
        │  preprocessing → medical pronunciation → segmentation → voices →
        │  chunking → focus → queue policies → metrics → TtsState
        ▼
TtsEngineAdapter ── coroutine-first engine abstraction
        │  (production: AndroidTtsEngineAdapter, tests: FakeTtsEngineAdapter)
        ▼
android.speech.tts.TextToSpeech (one instance, main-thread confined)
```

Cross-cutting: `AudioFocusController` (focus), `AudioRouteManager` (headset
state), `VoiceHandoffController` (TTS→STT transition policy), `TtsSettings`
(from `PreferencesDataStore`), Diagnostics (`TtsHealthSnapshot`).

**The PC agent cannot inject speech content.** Study payloads are text-only;
Android decides locale, voice, rate, pitch, chunking, and playback.

> **Baseline note (Gate 00, 2026-09-22):** a *remote TTS* subsystem now exists
> in `core/voice/tts/` (`SpeechBackend` / `ProviderBackendRouter` /
> `RemoteSpeechBackend`, protocol in `docs/PROTOCOL.md` §21) so the PC agent
> can serve cloud speech (OpenAI / ElevenLabs) over the authenticated media
> plane. In this baseline it is **dormant**: `AppContainer` still wires the
> local-only orchestrator, the cloud settings are not persisted yet, and the
> local engine remains the active path with the behavior documented below.

## 3. Domain model (`core/voice/tts/SpeechModels.kt`)

| Type | Purpose |
|---|---|
| `SpeechRequest` | id, text, purpose, languageHint, priority, queuePolicy, interruptible, profile |
| `SpeechPurpose` | QUESTION, FEEDBACK, HINT, EXPLANATION, ANSWER, STATUS, SYSTEM, SESSION_SUMMARY, ERROR, RATING_CONFIRMATION, PREVIEW |
| `SpeechPriority` | CRITICAL > HIGH > NORMAL > LOW (ranked, deterministic) |
| `QueuePolicy` | REPLACE / APPEND / INTERRUPT / IGNORE_IF_DUPLICATE |
| `SpeechResult` | `Completed` / `Cancelled` / `Failed(SpeechError)` — exactly once per accepted request |
| `SpeechErrorCode` | ENGINE_NOT_INITIALIZED, ENGINE_INITIALIZATION_FAILED, ENGINE_UNAVAILABLE, LANGUAGE_UNSUPPORTED, VOICE_UNAVAILABLE, MISSING_LANGUAGE_DATA, AUDIO_FOCUS_DENIED, AUDIO_FOCUS_LOST, SPEAK_FAILED, PLAYBACK_ERROR, TIMEOUT, ROUTE_LOST, QUEUE_FULL, PROVIDER_UNAVAILABLE, PROVIDER_NOT_CONFIGURED, PROVIDER_AUTH_FAILED, PROVIDER_RATE_LIMITED, PROVIDER_QUOTA_EXCEEDED, MODEL_UNAVAILABLE, CLOUD_STREAM_FAILED (the cloud members are part of the remote-TTS contract, see `docs/PROTOCOL.md` §21.3) |
| `TtsState` | Uninitialized / Initializing / Ready(engine,enVoice,arVoice) / Speaking(id,purpose,chunk,chunks,queue) / Error / Released |
| `SpeechIds` | unique utterance ids `q_<cardId>_<uuid8>`, chunks `…#3` — never contain content |

## 4. Execution pipeline (one logical request)

1. **Markup cleanup** (`SpeechTextPreprocessor`) — tag-list-based HTML stripping
   (block tags become pauses), entity decode, NBSP/whitespace normalize, Anki
   cloze unwrap. Medical `<`/`>` comparisons survive (validated by tests).
2. **Centralized labels** (`SpeechFormatting`) — only HINT/ANSWER get short
   lead-ins; content otherwise verbatim (backend-provided feedback preferred).
3. **Segmentation** (`MixedLanguageSegmenter`) — Unicode-block scan producing
   conservative AR/EN runs; neutral chars (digits/punct/spaces) glue to the
   previous run; `LanguageHint` overrides; disabled autodetection → dominant
   language single segment. Lossless, order-preserving, per-run voice switch
   at *clause* granularity (embedded medical terms like `epidural hematoma`
   inside Arabic questions get the English voice).
4. **Medical pronunciation** (`MedicalPronunciationProcessor`, English segments
   only) — letter-by-letter abbreviations (GCS→"G C S"), units verbalized
   (mL→"milliliters"), `120/80 mmHg`→"120 over 80 …", `15/15`→"15 out of 15",
   Na+/K+→sodium/potassium, ranges `C6-C7`→"C6 to C7", protected ISO dates and
   decimals, custom-rule extension point. Numbers are never reworded/reordered.
5. **Voice resolution** (`TtsVoiceSelector`) — deterministic score:
   explicit selection → exact locale(100) > quality(×10) > offline(30/5 vs −40/−10)
   > latency(−2×rank); stable id tie-break; graceful fallback to locale when a
   selected voice disappears.
6. **Chunking** (`SpeechChunker`) — engine-safe pieces sized by the real
   `TextToSpeech.getMaxSpeechInputLength()`; paragraph → sentence
   (abbreviation/decimal-aware) → clause (delimiters kept) → word → hard cut.
7. **Audio focus** (`AudioFocusController`) — TRANSIENT + CONTENT_TYPE_SPEECH/
   USAGE_ASSISTANT per request; transient loss & duck → pause at chunk boundary,
   replay chunk on regain (5 s bound); permanent loss → `Failed(AUDIO_FOCUS_LOST)`.
8. **Engine** — one utterance at a time, QUEUE_FLUSH, suspends to completion.

## 5. Engine adapter contract (`AndroidTtsEngineAdapter`)

- **Init queueing:** speak during INITIALIZING waits ≤ 8 s — never dropped (audit T1).
- **Callback lifetime:** completion deferreds are registered under a lock and
  funneled through one `completeUtterance` remove-then-complete; every terminal
  path (done/error/speak-fail/init-fail/timeout/stop/release/cancel) executes it
  exactly once — orphans are structurally impossible (T2/T3/T11).
- **`stop()`** completes all pending callers `Cancelled` *before* touching the
  engine, so late engine callbacks are no-ops (T3).
- **Unique ids** from `SpeechIds` — no map overwrites (T4).
- **Watchdog:** per-utterance timeout ≈ `chars × 110 ms ÷ rate` (4 s–360 s);
  TIMEOUT → engine.stop + `Failed(TIMEOUT)`; 2 consecutive timeouts → bounded
  re-init (≤ 3 attempts) (T8/T9).
- **Serialization** eliminates the queued-`isSpeaking` race (T5). Text is never
  logged (T6); all engine calls marshalled to the main thread (T7).
- **Audio attributes:** CONTENT_TYPE_SPEECH + USAGE_ASSISTANT (fallback
  USAGE_MEDIA when the engine rejects assistant usage).
- `setEngine()` (user-selected engine package) tears down and re-initializes.

## 6. Queue policy semantics (`SpeechQueue` + actor)

- REPLACE — drain pending, cancel in-flight (if interruptible), play now.
- INTERRUPT — same, ignoring interruptibility (critical announcements).
- APPEND — priority queue (priority, then insertion order); bounded at 8:
  non-critical overflow → `Failed(QUEUE_FULL)`; CRITICAL evicts lowest-priority tail.
- IGNORE_IF_DUPLICATE — silent no-op (result `Cancelled`) on duplicate id or
  purpose+text, in-flight or pending. Sections end: `stopSpeech(SESSION_END)`
  then `speak(summary APPEND)` — the actor's FIFO makes the drain land first.

## 7. TTS→STT handoff (`VoiceHandoffController`)

The fixed `delay(200)` is gone. Policy: listen starts only after
**Completed-confirmed speech end + acoustic gap** (default 350 ms, tunable
150–1200 ms via Settings, clamped); after **Failed** the gap halves (degraded
mode, still usable); after **Cancelled** the mic never opens. A second gate,
`speechSettled()`, defers the mic whenever speech is queued/playing, and
`maybeResumeListeningAfterSpeech()` re-opens it exactly when the pipeline
drains — so ordered combinations (APPEND explanations, queued STATUS lines,
route recovery) can never deadlock the study loop.

Self-echo defense: completion gate ⇒ the engine reported playback finished;
gap ⇒ A2DP/SCO drain time. (Recognizer-level early-window discarding was
considered and left out: Android's SpeechRecognizer already buffers audio
focus changes, and arbitrary transcript truncation would eat real answers.)

## 8. Headset disconnect / route changes

`AudioRouteManager.isHeadsetConnected` feeds the orchestrator. On drop while
speaking with `PAUSE_SPEECH` (default): current + queued requests receive
`Failed(ROUTE_LOST)` — the session pauses acoustically instead of broadcasting
over the speaker. `CONTINUE_ON_PHONE` opts out (Android reroutes natively).
On reconnect, the repository repeats the interrupted question (one explicit
transition), never a mid-word auto-restart.

## 9. Failure handling & graceful degradation

- Engine not ready / init failed / released → typed `Failed` immediately.
- Playback error / speak rejected / timeout → typed `Failed`, metrics counted.
- Every `Failed` still drives the study machine forward (feedback → rating,
  question → listening) after the shortened gap: **visual study never breaks**.
- Errors surface as user-actionable messages; raw codes only in Diagnostics.

## 10. Diagnostics & metrics (`TtsHealthSnapshot`)

engine status + package, chosen EN/AR voices (display + offline flags), focus
held, queue depth, speaking purpose, last typed error; metrics: time-to-ready,
last request→start, last request duration, completed / failed / cancelled
counts, focus denials, route interruptions. Rendered on the Diagnostics screen
and included in log export. Recommendations stay user-safe ("speech engine
unavailable — check system TTS settings"); no content is exposed.

## 11. Performance / memory notes

- One `TextToSpeech` instance per process (container-owned); no engine-per-card.
- Voice queries cached per language; invalidated by a settings fingerprint
  (engine/voice ids, offline flag, locales) — no per-chunk binder churn.
- Voice/locale/rate/pitch are only re-applied on the engine when changed.
- Regexes compiled once as class constants; preprocessing is O(n) per request.
- Queue bounded (8), log ring bounded (500), utterance callbacks bounded by
  construction (at most one in-flight); preview replaces itself.
- `RATE`/`PITCH` clamped (0.5–2.0 / 0.6–1.5); NaN → defaults.

## 12. Future providers (without overengineering)

`TtsEngineAdapter` is the single seam: a remote/non-Android TTS backend can be
added as another adapter implementation behind `SpeechOrchestrator` without
touching study logic, queueing, focus, or handoff. Nothing in the domain model
references Android types (the orchestrator, preprocessors, segmenter, chunker,
selector and queue are pure Kotlin/JVM — covered by unit tests).

## 13. Testing map

| Component | Tests |
|---|---|
| Preprocessor | tags/entities/operators/cloze/Arabic passthrough |
| Medical processor | abbreviations, units, BP, scores, ions, ranges, dates, prose safety, custom rules |
| Segmenter | EN/AR/mixed/digits/hints/autodetect-off/lossless |
| Chunker | boundaries, abbreviations+decimals, paragraphs, lossless, arabic marks |
| Voice selector | ladder, offline pref, ties, missing voice, network-only |
| SpeechQueue | ordering, bounds, dedup, eviction, exactly-once drain |
| Orchestrator (fake engine) | init queueing, chunk aggregation, REPLACE/APPEND, cancel mapping, release cleanup, locales, medical, rate clamp, engine switch, preview |
| Handoff | Completed/Cancelled/Failed gap policy, clamps |
| Commands | StopSpeaking vs EndSession bilingual |
