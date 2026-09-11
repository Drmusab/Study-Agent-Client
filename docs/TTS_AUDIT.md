# TTS Subsystem Audit (Phase A Report)

Date: 2026-09-11
Scope: `core/voice/*`, `core/audio/*`, `data/repository/StudySessionRepository.kt`,
`data/preferences/PreferencesDataStore.kt`, `core/models/*`, Settings & Diagnostics UI,
`StudySessionForegroundService.kt`, `di/AppContainer.kt`, docs.

This audit was performed against the actual source at commit `4a7493f`, **not** the README.
Every finding below was confirmed in code before being listed. Nothing is speculative.

---

## 0. Pre-existing build breakage (found before any refactor)

| # | File | Defect |
|---|------|--------|
| B1 | `core/models/StudyState.kt` | Duplicated constructor line (`val cardsReviewed: Int = 0` twice, first without comma) — the branch did **not compile**. |
| B2 | `core/models/Evaluation.kt` | Duplicated constructor line (`val suggestedRating: Rating?` twice, first without comma) — same class of defect. |

Both were fixed as a precondition for all other work.

---

## 1. Confirmed bugs in `AndroidTextToSpeechManager`

| # | Severity | Confirmed issue |
|---|----------|-----------------|
| T1 | **High** | `speak()` before init completes: callbacks are registered first, then the main-thread `post` discovers the engine is not ready and only invokes/removes the **error** callback — the **completion callback is left orphaned** in `completionCallbacks` (leak). The utterance is also silently lost (no queue-until-ready policy). |
| T2 | **High** | `speak()` invocation failure (`engine.speak(...) != SUCCESS`): error callback fired, completion callback **left orphaned** in the map (leak). |
| T3 | **High** | `stop()` clears **both** callback maps without invoking them; any caller awaiting `onDone` (e.g. the repository's feedback → rating-listen transition) is left hanging / never notified. Cancellation is not reported to upstream. |
| T4 | **High** | Duplicate utterance IDs: default `utteranceId = "study_tts"` reused for many calls (`SessionStats`, preview-like calls), and `q_<cardId>`/`eval_<cardId>`/… IDs repeat on **Repeat** and on reconnect re-delivery. The second registration **overwrites** the first pending callback → leaked callback + wrong completion routing. |
| T5 | **Medium** | `isSpeaking` race: `onDone`/`onError` of *any* utterance flips `isSpeaking=false`, even when later `QUEUE_ADD` utterances are still playing. |
| T6 | **Medium** | Full speech text written to logs: `Speaking: '$text'` and `not ready yet to speak: $text` — leaks complete medical flashcards into logcat and the in-app diagnostic buffer, and bloats the 500-entry log ring. |
| T7 | **Medium** | `setLanguage` / `setSpeechRate` / `setPitch` touch the engine from whatever thread the caller is on (settings collector runs on `Dispatchers.Default`); engine access is not marshalled to one thread. |
| T8 | **Medium** | No timeout / watchdog anywhere: if the engine never emits `onDone` (documented OEM failure mode), the logical request hangs forever and the study loop stalls. |
| T9 | **Medium** | No initialization policy: init failure sets `isInitialized=false` and every later `speak()` fails with a generic string; no bounded retry, no typed error, no re-init after engine death. |
| T10 | **Low** | No audio attributes (no `CONTENT_TYPE_SPEECH`, no usage); no audio focus handling of any kind (grep for `AudioFocus` in main sources: zero hits). |
| T11 | **Low** | `release()` races: `speak()` posted after `release()` hits the T1 orphan path. |

## 2. Confirmed bugs / risks in `StudySessionRepository` speech orchestration

| # | Severity | Confirmed issue |
|---|----------|-----------------|
| R1 | **High** | TTS→STT handoff is a magic `delay(200)` after `onDone` — undocumented, unconfigurable, not route-aware. |
| R2 | **High** | `startListeningForAnswer` / `startListeningForRating` call `ttsManager.stop()` immediately before `sttManager.startListening(...)`; `stop()` is only *posted* to the main handler, and T3 means any pending callbacks vanish silently. STT can open the mic while engine audio is still draining → self-echo risk (the STT hears the tail of the app's own question). |
| R3 | **High** | Content-specific speech behavior is scattered through the repository (`"Hint: ..."` prefix, `"Answer: ..."` prefix, which messages speak, which IDs they use) — no domain model, no purposes, no priorities, no queue policies. |
| R4 | **Medium** | No duplicate-speech protection: a re-delivered `ServerMessage.Question` (e.g. after reconnect) is spoken again; a double `processVoiceCommandDirectly` can double-speak. |
| R5 | **Medium** | `SessionFinished` speaks the summary with `flushQueue=true`, relying on main-handler post ordering against the preceding `stop()`; pending `QUEUE_ADD` items and orphaned callbacks are not handled deterministically. Old queued speech can outlive the session summary in edge interleavings. |
| R6 | **Medium** | Pause (`pauseStudy`) → `ttsManager.stop()` + T3 → pending onDone never fires; resume re-speaks only for `SpeakingQuestion`/`Listening` states; feedback interrupted by pause is not resumed deterministically. |
| R7 | **Medium** | Settings application assumes a single global TTS locale (`ttsLanguage`), one rate for everything, and applies unvalidated floats (DataStore content is trusted; nothing clamps NaN / negative / extreme values). |
| R8 | **Medium** | No response to headset disconnect while speaking: the engine simply reroutes to the loudspeaker mid-question — private medical content can blast from the phone speaker with no policy and no user control. |
| R9 | **Low** | `Unknown` voice command while `Listening` is submitted as an answer with no No-Match retry policy (pre-existing UX choice, kept). |

## 3. Confirmed issues in audio routing

| # | Severity | Confirmed issue |
|---|----------|-----------------|
| A1 | **Medium** | `AndroidAudioRouteManager` picks the *first* headset-looking entry from `GET_DEVICES_OUTPUTS` and reports it as the **active route**, regardless of where audio is actually routed — "detected device" is conflated with "actual route" (docs claim otherwise). `AudioRouteManager` is not consumed by the speech pipeline at all (no disconnect policy possible). |
| A2 | **Low** | `AudioDeviceInfoModel.isMicrophone = dev.isSource` is meaningless for an outputs query (always false). Not user-visible; unchanged in this pass. |
| A3 | **Low** | `HeadsetBroadcastReceiver` is registered from `MainActivity` and only triggers `refreshAudioDevices()` (duplicate of `AudioDeviceCallback`); harmless redundancy left intact. |

## 4. Feature gaps (vs. product target, all confirmed absent)

- No speech request/result/state domain model (single `isSpeaking` Boolean only).
- No voice discovery (`tts.voices` never queried), no voice ranking, no offline preference, no per-language voice selection, no voice preview, no engine discovery.
- No mixed Arabic/English handling: one global locale is applied to the whole engine; a mixed card is read by one voice.
- No speech-only text preprocessing: HTML/Anki markup would be read aloud; no medical abbreviation/unit handling; no number-format handling.
- No chunking: `TextToSpeech.getMaxSpeechInputLength()` never consulted; long AI explanations are passed to a single `speak()` (oversized input is dropped or error `-6` on some engines → hits T2).
- No acoustic echo protection beyond the fixed 200 ms; no early-window guard.
- No TTS health surfaced in Diagnostics; no metrics (time-to-ready, error/cancel counts).
- Settings UI exposes only a locale radio + one rate slider + pitch slider.

## 5. What was verified as *working* (kept)

- Engine init + locale/rate/pitch plumbing (basic path), `UtteranceProgressListener` wiring concept, `QUEUE_FLUSH`/`QUEUE_ADD` distinction, foreground service + wake lock for screen-off study, `AudioDeviceCallback`-driven route refresh, bilingual voice-command parser (extended, not rewritten).

The refactor plan and the mapping of each confirmed issue → fix is in `docs/TTS_ARCHITECTURE.md`.
