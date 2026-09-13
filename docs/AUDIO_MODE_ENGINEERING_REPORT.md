# Engineering Report — Headset-Optional Study Audio & Phone Mode

Scope: make headphones optional in Study-Agent-Client by supporting two equally valid
environments — Headset Mode and Phone Mode — selecting between them automatically, without a
second study workflow, a second TTS/STT implementation or a second handoff system.

Status: implementation complete and unit-tested by construction; **the Gradle test/assemble
commands could not be executed in the environment this work was produced in** (no JDK, no
Android SDK, no network). See §9.

---

## 1. Headset assumptions found (Phase 1 audit)

| Location | Assumption | Resolution |
|---|---|---|
| `DefaultStudySessionRepository` (`isHeadsetConnected` collectors) | A headset transition drives cancel/retry of the turn | Legacy path retained for tests; the authoritative machine repository now keys on the effective route |
| `DefaultSpeechOrchestrator(..., headsetConnected)` | Any "disconnected" boolean interrupts speech with `ROUTE_LOST` | Now fed `coordinator.headsetRouteActive`, which is true only when the **effective output** was an external headset — starting without headphones can no longer look like a loss |
| `AudioRouteManager` | `isHeadsetConnected` was the only audio state; a headset implied a microphone | Replaced by an input-aware `routeSnapshot`; A2DP is output-only and never proves a microphone |
| `AudioRouteIndicator` / Home & Study screens | Binary "Headset / Phone Speaker" chip, phone rendered as a lesser state | Chip now shows the effective route (🎧 Headset / 🎧 Headphones + phone mic / 📱 Phone / 🔒 Headphones required), neutral styling |
| Settings | Only `headsetDisconnectBehavior` (TTS-specific) | New `studyAudioMode` preference; the legacy key remains and keeps its meaning |
| `VoiceHandoffController` | Fixed 350 ms gap regardless of output device | Route-aware gap policy (phone speaker gets a conservative floor); the completion gate, drained-queue check and request-generation validation remain the actual correctness mechanism |
| Speech handoff | Microphone could be opened directly by an effect | Every start now goes through `StudyVoiceTurnGate` |

No code path was found that *required* a headset to start a session, or that treated the
built-in microphone as an error; the risk was concentrated in route awareness, the loss
semantics and the (absent) phone-speaker echo protection.

## 2. Architecture

```
AppSettings.studyAudioMode ─┐
                            ├─► StudyAudioModeResolver (pure) ─► EffectiveStudyAudioRoute
AudioRouteSnapshot ─────────┘                                        (output, input, certainty,
   ▲                                                                  readiness, acoustic profile)
AudioRouteManager (Android facts)                  │
   ▲                                               ▼
AudioDeviceCallback                     StudyAudioRouteCoordinator
                                        (loss, pending route, overrides, generation)
                                                   │
                                                   ▼
                              StudyVoiceTurnGate ──► SpeechRecognitionOrchestrator
```

* Preference → resolver → effective route is one direction; the effective mode is derived and
  never persisted.
* `AUTO` is the default. `HEADSET_REQUIRED` is the only mode that can refuse a start, and it is
  an explicit advanced opt-in.
* `BUILTIN_MIC` and `PHONE_SPEAKER` are first-class values in the route model; there is no
  "degraded" state for either.
* Hybrid routes (headset output + phone microphone) fall out of resolving output and input
  independently.

## 3. Phone Mode

* Speaker TTS + built-in-mic STT, hands-free, screen on or off (the foreground service keeps
  the session alive).
* Self-echo protection is layered: TTS terminal confirmation → queue drained → route stable →
  **acoustic gap** (`max(450 ms, configured × 1.25)`) → start generation validated → STT
  readiness. The gap is a physical guard, never logical state control.
* Stale gaps are impossible: every pending microphone start carries a generation that a skip,
  pause, end, new card, manual rating or route change invalidates.
* Self-echo suspicion (`SelfEchoDetector`) is diagnostics-only — it never discards a transcript
  and never alters an answer.
* Metrics are local, counts/timings/error categories only; no transcripts, no uploads.

## 4. Dynamic switching

* Non-loss changes are deferred: `pendingRoute` holds the intent until the next safe turn
  boundary (before the next question, after a rating, while paused).
* Explicit user actions (settings change, *Use headphones now*) apply immediately and re-issue
  the current question on the new route instead of swapping hardware mid-word.
* Every applied change bumps a route generation, which invalidates in-flight handoffs.

## 5. Settings added

* **Audio Mode**: Automatic (default) / Prefer Headphones / Phone / Headphones Required.
* **When headphones disconnect**: Pause voice study (default) / Continue on phone.
* Persistence: `study_audio_mode` (+ `phone_audio_notice_acknowledged` for the one-time notice).
  The pre-existing `headsetDisconnectBehavior` key is preserved, and unknown stored values
  migrate to `AUTO` instead of crashing.
* No configuration is required to study on a bare phone, and there is no
  "connect headphones to continue" prompt in Automatic, Prefer Headphones or Phone.

## 6. Tests

### Automated (JVM)

New suites (50+ tests): `StudyAudioModeResolverTest` (policy matrix, hybrid, output-only,
migration, labels), `StudyAudioRouteCoordinatorTest` (cold start without a headset produces no
loss; deferred upgrade; loss semantics under both policies; overrides; generations; diagnostics
rows), `StudyVoiceTurnGateTest` (half-duplex, stale gap, pause/end during the gap, route change
during the gap, no-mic/blocked routes, push-to-talk settle),
`AcousticGapPolicyTest` (route-aware gaps, degraded floors, cancelled speech never listens),
`SelfEchoAndPhoneMetricsTest` (echo window, similarity, no transcript filtering, privacy-safe
metrics, 1000-turn bound), `PhoneModeLoopTest` (a full phone turn without overlap, "Good" in
feedback cannot rate, a spoken rating still works, 100 cards without a headset-loss event or a
recovery loop, 1000 bounded gated turns, a headset connecting mid-turn only switches at the
boundary), `SpokenCommandRouterTest` (hands-free commands map onto the same events as the
buttons).

Recommend running: `./gradlew testDebugUnitTest`, then `assembleDebug`, `assembleRelease` and
`lint` if configured.

### Real device (mandatory, still outstanding)

No headless environment can validate speaker coupling or recognizer behaviour. The checklist in
`docs/AUDIO_ROUTING.md` §9 covers: no headphones at a desk and in hand, screen on/off, quiet and
noisy rooms, English/Arabic/mixed cards, short and long answers, spoken rating, hint,
explanation, repeat, pause/resume/end, mid-question headset connection, unplug during TTS and
during STT under both policies, Phone preference with wired headphones attached, output-only
Bluetooth headphones, and `HEADSET_REQUIRED` refusal. Compare Headset vs Phone metrics (handoff
latency, NoMatch rate, suspected self-echo, recognition failures, rating false positives) and
record the exact device models and Android versions tested.

## 7. Android limitations (unchanged by design)

* `SpeechRecognizer` never reports which microphone it used, so inferred routes are `LIKELY`,
  not `CONFIRMED`; diagnostics say so.
* The app does not force audio routing (no `setSpeakerphoneOn`, no SCO initiation, no
  `MODE_IN_COMMUNICATION`, no forced volume): with headphones attached, the platform may keep
  media on them even in Phone preference, and the UI reports the route as likely.
* Speaker self-echo can be excluded from the listening window, not eliminated; a very noisy
  environment can still produce NoMatch.
* Private Phone Mode (earpiece) is modelled but intentionally not implemented.
* The legacy `DefaultStudySessionRepository` still contains its own headset collectors; it is no
  longer the app's session owner and is retained only for the existing test suite and as a
  migration fallback.

## 8. Files changed (main)

New: `core/audio/{StudyAudioModels,StudyAudioModeResolver,AudioRouteSnapshotFactory,StudyAudioRouteCoordinator,PhoneModeDiagnostics,StudyAudioPreferencesMapping}.kt`,
`core/voice/{StudyVoiceTurnGate,tts/AcousticGapPolicy}.kt`,
`core/study/SpokenCommandRouter.kt`, `docs/AUDIO_ROUTING.md`.

Modified: `core/audio/{AudioRouteManager,AudioDeviceInfoModel}.kt`,
`core/study/{StudySessionMachine,StudyReducer,StudyEvent}.kt`,
`core/voice/tts/VoiceHandoffController.kt`,
`core/models/AppSettings.kt`, `data/preferences/PreferencesDataStore.kt`,
`data/repository/{StudySessionRepository,StudySessionMachineRepository,DiagnosticsRepository}.kt`,
`di/AppContainer.kt`, the Home/Study/Settings/Diagnostics screens and view models,
`docs/{VOICE_FLOW,ARCHITECTURE}.md`, plus the new test suites.

## 9. Validation status and honest limitations

* All automated tests were written but **not executed**: this environment has no JDK, no Android
  SDK and no network, so `./gradlew` cannot run. Every test in this report must be treated as
  "written, pending a CI run" until it passes on a machine with the Android toolchain.
* Real-device Phone Mode testing has **not** been performed, so no claim is made about measured
  echo behaviour on any specific handset, and results are not generalised to a manufacturer.
* Two behaviours were found and fixed during review rather than being left in the report as
  known defects: a preference change to Phone while headphones were still attached used to be
  classified as a headset loss (now a normal, immediate route change), and
  `hasUsableMicrophone` used to accept `SYSTEM_SELECTED` as proof of a microphone (now only a
  `READY` route counts).
* A pre-existing gap unrelated to audio routing remains: the machine-backed repository does not
  execute voice commands from the answer window, and the legacy repository is the only one that
  interprets them; `SpokenCommandRouter` now provides the shared mapping the machine uses for
  rating/command windows.
