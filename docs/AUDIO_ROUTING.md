# Study Audio Routing & Headset-Free Phone Mode

> **Product rule:** headphones improve privacy and recognition quality. They are never a
> requirement. A user with nothing but a phone must be able to press Start and complete a
> hands-free study session — questions through the phone speaker, answers through the built-in
> microphone, including spoken ratings and commands.

This document describes the audio-routing architecture that makes that true, the exact
behaviour of each mode, and how the Phone Mode self-echo protections are layered.

---

## 1. The two supported environments

| | Headset Mode | Phone Mode |
|---|---|---|
| TTS output | Bluetooth / wired / USB headset | Phone speaker |
| STT input | Headset mic, or the phone mic (hybrid) | Built-in microphone |
| Hands-free | Yes | Yes |
| Privacy | Private | Audible to people nearby (educational notice once) |
| Status | Normal | Normal — never degraded, never an error |

Both are first-class. There is **one** study workflow, one TTS implementation and one STT
implementation; only the resolved output/input pair differs.

---

## 2. The route model (no Android APIs)

```
StudyAudioMode            AUTO (default) | HEADSET_PREFERRED | PHONE | HEADSET_REQUIRED
StudyOutputRoute          BLUETOOTH | WIRED | USB | PHONE_SPEAKER | PHONE_EARPIECE | SYSTEM_SELECTED | UNKNOWN
StudyInputRoute           BLUETOOTH_MIC | WIRED_MIC | USB_MIC | BUILTIN_MIC | SYSTEM_SELECTED | UNKNOWN
RouteCertainty            CONFIRMED | LIKELY | UNKNOWN
EffectiveStudyAudioMode   HEADSET | HYBRID | PHONE | BLOCKED | UNKNOWN   ← derived, never persisted
StudyAudioReadiness       READY | OUTPUT_ONLY | BLOCKED
```

| File | Responsibility |
|---|---|
| `core/audio/StudyAudioModels.kt` | Modes, routes, snapshots, readiness, events, preferences |
| `core/audio/StudyAudioModeResolver.kt` | Pure preference + snapshot → effective route |
| `core/audio/AudioRouteSnapshotFactory.kt` | Device classes → snapshot (pure) |
| `core/audio/AudioRouteManager.kt` | Android facts: devices, snapshots, verified input route |
| `core/audio/StudyAudioRouteCoordinator.kt` | Route over time: loss, pending switch, overrides |
| `core/audio/PhoneModeDiagnostics.kt` | Local counts/timings, self-echo detector |
| `core/voice/StudyVoiceTurnGate.kt` | The single place that decides when the mic may open |
| `core/voice/tts/AcousticGapPolicy.kt` | Route-aware acoustic gap |

`EffectiveStudyAudioMode` is always derived from the preference plus the current device set.
It is **never** written to DataStore, so a stale "we are on a headset" value can never survive
a restart.

---

## 3. Resolution matrix

| Preference | Headset present | Effective | Output | Input | Voice study |
|---|---|---|---|---|---|
| AUTO | no | PHONE | phone speaker | built-in mic | starts |
| AUTO | yes | HEADSET / HYBRID | headset | headset mic / phone mic | starts |
| HEADSET_PREFERRED | no | PHONE | phone speaker | built-in mic | starts (fallback) |
| HEADSET_PREFERRED | yes | HEADSET | headset | headset mic | starts |
| PHONE | any | PHONE | phone speaker **or earpiece** | built-in mic | starts |
| HEADSET_REQUIRED | yes | HEADSET | headset | headset mic | starts |
| HEADSET_REQUIRED | no | BLOCKED | – | – | refuses, with a way out |

Two deliberate decisions:

* **`OUTPUT_ONLY` is not an error.** If no microphone exists at all, the question is still
  spoken and the user answers with the on-screen controls. Only `BLOCKED` stops a start, and
  only `HEADSET_REQUIRED` (an explicit, non-default privacy choice) can produce it.
* **The built-in microphone is first-class.** STT gates on "a usable microphone exists" —
  `BUILTIN_MIC` satisfies it. `hasExternalMicrophone` and `isHeadsetConnected` never gate
  study, and neither appears in the route decision.

### Hybrid routes

A2DP headphones with no communication profile, or headphones with a separate phone mic,
resolve to **HYBRID**: headset output, built-in microphone input. Output and input are chosen
independently, and the acoustic profile follows the **output** (headphones isolate the app's
own speech even when the mic is the phone's).

---

## 4. What counts as "headphones were lost"

A loss event requires **both**:

1. the previously effective output was an external headset, **and**
2. that device is gone from the device list.

Therefore:

* starting the app with no headphones is simply Phone Mode — no event, no prompt, no recovery
  loop (§44/§93);
* audio-device re-enumeration while on the phone produces nothing;
* switching the preference to Phone while headphones are still connected is a normal
  user-directed route change, not a disconnect.

A loss always cancels the current utterance and the recognition turn. Then the configured
disconnect policy decides what happens next:

| Policy (Settings → Study Audio) | Behaviour |
|---|---|
| Pause voice study *(default)* | Voice interaction pauses and a card offers **Continue on phone** / **Wait for headphones** |
| Continue on phone | The interrupted turn is cancelled, the route resolves to Phone Mode, and the current question is repeated exactly once |

**Continue on phone** is a *hold*, not a permanent mode change: it is released as soon as a
headset is available again, and the upgrade still waits for a safe turn boundary.

---

## 5. Dynamic switching

* Non-loss changes (a headset appearing, A2DP → SCO) are **deferred** to a safe turn boundary:
  before the next question, after a rating, or while paused. `pendingRoute` holds the intent.
* Explicit user actions apply **immediately** and intentionally cut the current turn short:
  * changing Audio Mode in Settings while studying;
  * pressing **Use headphones now** in the recovery card.
* Every applied change bumps a **route generation**. An in-flight microphone start is validated
  against that generation, so a turn that spans a route change is re-issued on the new route
  instead of opening the microphone on a half-switched pipeline.

---

## 6. Phone Mode and self-echo

Phone speakers leak the app's own speech into the phone's microphones. The protections are
layered, and only the last one is timing:

1. **Completion gate** — the handoff only runs after `SpeechResult.Completed`; `Cancelled`
   never starts a listen, and `Failed` uses a shorter, still-floored gap.
2. **Queue drain** — `speechOrchestrator.health.queueDepth == 0` is required, so a queued
   explanation cannot overlap the rating window.
3. **Route stability** — the route generation must be unchanged across the gap.
4. **Turn validity** — re-checked *after* the gap; skip, pause, end, a new card or a manual
   rating invalidates the pending start (`sttGeneration`).
5. **Acoustic gap** — route-aware and never used as logic:
   * headset: the user's configured gap (default 350 ms, clamped 150–1200 ms);
   * phone speaker: `max(450 ms, configured × 1.25)`, capped at 1200 ms;
   * failed speech: shorter, with a 200 ms floor.
6. **STT readiness** — recognition starts only through `StudyVoiceTurnGate`, which requires a
   `READY` route with a usable microphone.

**Half-duplex invariant:** `NOT(phone-speaker TTS is active AND STT is active)`. The gate is the
only code path that opens the microphone, and it refuses while speech is active or queued
(push-to-talk may wait a bounded 600 ms for the engine to actually stop, because there the user
explicitly asked to talk).

**Self-echo is diagnosis, not a filter.** `SelfEchoDetector` flags a transcript that closely
matches what the app just said, immediately after it said it. It increments
`suspectedSelfEcho` and logs a length-only line. It **never** discards or rewrites a transcript:
a user may legitimately repeat the question's own words.

---

## 7. Settings and UI

**Settings → Study Audio**

* Audio Mode: `Automatic` (default) · `Prefer Headphones` · `Phone` · `Headphones Required`
* When headphones disconnect: `Pause voice study` *(default)* · `Continue on phone`

The stored key is `study_audio_mode`; unknown values migrate to `AUTO` instead of crashing. The
pre-existing `headsetDisconnectBehavior` key is preserved and keeps its meaning, so an upgrade
does not silently change an existing user's choice. The one-time Phone Mode notice is stored as
`phone_audio_notice_acknowledged` and is shown at most once, never before every session.

**UI**

* Compact effective-route chip: `🎧 Headset` · `🎧 Headphones + phone mic` · `📱 Phone` ·
  `🔒 Headphones required`. Phone Mode uses the same neutral styling as everything else.
* Study screen: a `🔊 Speaking` / `🎤 Listening` phase chip (never both), and the recovery card
  after an unexpected loss.
* Diagnostics: `Study audio mode`, `Effective mode`, output, input, external headset, route
  certainty, acoustic profile, pending route, disconnect policy, generation, plus Phone Mode
  counters (turns, route changes, loss events, blocked starts, handoff latency, no-speech /
  no-match / timeouts, mic-unavailable skips, suspected self-echo).

There is no "connect headphones to continue" prompt in `AUTO`, `HEADSET_PREFERRED` or `PHONE`.

---

## 8. Graceful degradation

| Failure | Behaviour |
|---|---|
| TTS engine fails | Question stays on screen; speech failure is recoverable; STT and manual controls still work |
| STT unavailable / no microphone | Question still spoken; answer via keyboard, rating via buttons |
| Unexpected headset loss | Cancel current utterance, apply the disconnect policy, never blast the speaker silently |
| Route blocked (`HEADSET_REQUIRED`) | Start refused with an explanatory, recoverable error and a settings hint |
| No volume change needed | The app never forces volume or communication mode |

---

## 9. Verification

### Automated (JVM, `./gradlew testDebugUnitTest`)

| Test class | Proves |
|---|---|
| `audio/StudyAudioModeResolverTest` | Full policy matrix, Phone Mode READY, hybrid, output-only, migration |
| `audio/StudyAudioRouteCoordinatorTest` | Cold start without a headset produces no loss; deferred switch; loss semantics; policies; overrides |
| `voice/StudyVoiceTurnGateTest` | Half-duplex, stale gap, pause/end/skip during the gap, route change during the gap, PTT settle |
| `audio/AcousticGapPolicyTest` | Route-aware gaps, degraded floors, cancelled speech never listens |
| `audio/SelfEchoAndPhoneMetricsTest` | Echo window, no transcript filtering, privacy-safe metrics, 1000-turn bound |
| `audio/PhoneModeLoopTest` | Full phone turn without overlap, "Good" in feedback cannot rate, 100 cards without a loss event, 1000 gated turns |
| `study/SpokenCommandRouterTest` | Hands-free commands reach the same events as the buttons |
| `audio/StudyAudioPreferencesMappingTest` | Migration: existing installs become AUTO, the old disconnect choice keeps its meaning |

### Real device (mandatory, not a substitute for the above)

Run the following with **no headphones at all** unless stated: desk use, hand-held, screen on
and screen off/locked, quiet room and noisy room, English / Arabic / mixed cards, short and long
answers, spoken rating, hint, explanation, repeat, pause, resume, session end, plus:

* plug headphones in mid-question (must switch only at the boundary);
* unplug during TTS and during STT, under both disconnect policies;
* wired headset present with the preference set to `Phone`;
* output-only Bluetooth headphones (`HYBRID`, phone mic);
* `HEADSET_REQUIRED` with no headphones (must refuse, visibly and recoverably).

Record the device models and Android versions used. Compare Headset vs Phone metrics
(handoff latency, NoMatch rate, suspected self-echo, recognition failures, rating false
positives) using the Diagnostics counters.

---

## 10. Known Android limitations

* **The actual recognizer input is not published.** `SpeechRecognizer` does not report which
  device it used, so a route inferred from the device list is `LIKELY`, not `CONFIRMED`. The UI
  and diagnostics say so instead of over-claiming.
* **Phone Mode does not force routing.** The app does not call `setSpeakerphoneOn`, does not
  start SCO for Bluetooth, and does not enter `MODE_IN_COMMUNICATION`, so with headphones
  attached the platform may still keep media on them. Diagnostics reports this honestly
  (`Phone audio selected while a headset is connected`).
* **Speaker self-echo cannot be eliminated, only excluded from the listening window.** The gap
  floor and the requested-length policy reduce it; a very noisy room can still produce a
  NoMatch.
* **Private Phone Mode (earpiece)** is modelled (`PHONE_EARPIECE`) but deliberately not
  implemented: it needs device-specific testing that this repository's environment cannot
  provide.
