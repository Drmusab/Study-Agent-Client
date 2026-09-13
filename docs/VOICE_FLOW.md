# Voice UX, Interaction Flow & Audio Architecture

## 1. Primary Voice UX Principle
> **The user should be able to study hands-free without continuously looking at or touching the phone — with headphones, or with nothing but the phone itself.**

Headphones improve privacy and recognition quality; they are never a requirement. The same
study loop runs on a headset route and on a bare-phone route (`docs/AUDIO_ROUTING.md`).

To achieve this, the entire study loop is orchestrated by a state machine that controls Text-to-Speech playback, speech recognition activation, silence detection, and voice command parsing.

---

## 2. The Complete Voice Interaction Cycle

```
                       ┌─────────────────────────┐
                       │          IDLE           │
                       └────────────┬────────────┘
                                    │ Tap Start Study or Voice "Start"
                       ┌────────────▼────────────┐
                       │ SPEAKING_QUESTION    │ ◄──────────────────────┐
                       │   (TTS reads aloud)     │                        │
                       └────────────┬────────────┘                        │
                                    │ SpeechResult.Completed + Handoff Gap
                       ┌────────────▼────────────┐                        │
                       │        LISTENING        │                        │
                       │  (STT active / PTT)     │                        │
                       └────────────┬────────────┘                        │
                                    │ Final transcript detected           │
                       ┌────────────▼────────────┐                        │
                       │       EVALUATING        │                        │
                       │ (Transmitted to PC LLM) │                        │
                       └────────────┬────────────┘                        │
                                    │ Server returns evaluation           │
                       ┌────────────▼────────────┐                        │
                       │    SHOWING_FEEDBACK     │                        │
                       │ (TTS reads AI feedback) │                        │
                       └────────────┬────────────┘                        │
                                    │ Feedback playback finishes          │
                       ┌────────────▼────────────┐                        │
                       │   WAITING_FOR_RATING    │                        │
                       │ (Listens for rating cmd)│                        │
                       └────────────┬────────────┘                        │
                                    │ "Good" / "Hard" / "Again" / "Easy"  │
                       ┌────────────▼────────────┐                        │
                       │       RATE_SAVED        ├────────────────────────┘
                       │ (PC advances next card) │
                       └─────────────────────────┘
```

---

## 3. Bilingual Voice Command Grammar

The client includes local, low-latency command parsing that operates before sending data to the server, supporting both **English** and **Arabic** natural voice patterns.

> **Context decides whether speech is a command at all.** The table below lists the full
> vocabulary, but a phrase is only *executed* when the study state expects it. While the app
> is listening for a **medical answer**, only the explicit multi-word phrases ("repeat
> question", "show answer", "end session", …) may fire. A lone word — "good", "stop", "next",
> "easy" — is submitted as the answer, never executed. See `docs/STT_ARCHITECTURE.md` §7.

> **Ratings are held to a higher bar.** A spoken rating is applied only on a verbatim grammar
> match, and a low-confidence rating is never allowed to silently reschedule an Anki card —
> it is either confirmed with the user or re-listened for.

> **"Stop speaking"** ("stop speaking", "quiet", "enough" / "اسكت", "توقف عن الكلام") cancels speech output **without** ending the session; plain "stop" still ends it. Both are matched as whole phrases — never as a prefix — so an answer containing the word "stop" does not end the session.

| Action | English Voice Commands | Arabic Voice Commands (الأوامر الصوتية بالعربية) |
| :--- | :--- | :--- |
| **Rate: Again** | "Again", "Repeat card", "Forgot", "Zero" | "مرة أخرى", "مرة اخرى", "اعد", "أعد", "نسيت", "من جديد" |
| **Rate: Hard** | "Hard", "Difficult", "Tough", "Hard card" | "صعب", "شاق", "مش سهل", "صعب جدا" |
| **Rate: Good** | "Good", "Correct", "Got it", "Nice" | "جيد", "تمام", "صحيح", "ممتاز", "مقبول", "جيد جدا" |
| **Rate: Easy** | "Easy", "Simple", "Piece of cake", "Trivial" | "سهل", "بسيط", "واضح", "سهل جدا" |
| **Repeat Question**| "Repeat", "Repeat question", "Say again", "What was the question" | "أعد السؤال", "اعد السؤال", "كرر", "ما هو السؤال", "مرة ثانية" |
| **Request Hint** | "Hint", "Give me a hint", "Need a hint", "Give hint" | "تلميح", "اعطني تلميح", "أعطني تلميح", "ساعدني", "تلميحة" |
| **Explain** | "Explain", "Explanation", "Why", "Tell me more" | "اشرح", "شرح", "وضح", "توضيح", "لماذا", "علل" |
| **Show Answer** | "Show answer", "Give answer", "What's the answer" | "اظهر الجواب", "أظهر الجواب", "ما هو الجواب", "الجواب", "الحل" |
| **Skip Card** | "Skip", "Next", "Next card", "Pass" | "التالي", "تخطي", "تجاوز", "البطاقة التالية", "عدي" |
| **Pause Study** | "Pause", "Pause study", "Hold on", "Wait" | "توقف", "توقف مؤقت", "انتظر", "استراحة" |
| **Resume Study** | "Resume", "Continue", "Keep going" | "اكمل", "استمر", "تابع", "واصل" |
| **End Session** | "Stop", "End", "End session", "Quit" | "انهاء", "إنهاء", "وقف", "خروج", "انهي الجلسة" |
| **Check Status** | "How many cards left?", "Remaining cards" | "كم بطاقة متبقية", "كم باقي", "العدد المتبقي" |

---

## 4. Audio Routing & Bluetooth Subsystem

### 4.1 Preference → Effective Route
* The user chooses a **preference** (`Automatic` — the default, `Prefer Headphones`, `Phone`,
  `Headphones Required`) in Settings → Study Audio.
* `StudyAudioModeResolver` combines that preference with a pure `AudioRouteSnapshot` of the
  devices present and returns an `EffectiveStudyAudioRoute`: output, input, certainty,
  readiness and acoustic profile. The resolver contains no Android APIs, so the full policy
  matrix is unit-tested.
* `EffectiveStudyAudioMode` is **derived** (`HEADSET` / `HYBRID` / `PHONE` / `BLOCKED`) and never
  persisted, so a stale "on headset" value can never survive a restart.
* `AUTO` and `HEADSET_PREFERRED` fall back to the phone. Only the explicit, non-default
  `HEADSET_REQUIRED` can refuse a start — and it says so, recoverably.
* Output and input are resolved **independently**, which is how hybrid routes work: Bluetooth
  A2DP headphones for audio plus the phone's built-in microphone for answers.

### 4.2 Route Detection (Android facts)
* `AudioRouteManager` registers Android's `AudioDeviceCallback` and monitors **both** `AudioManager.GET_DEVICES_OUTPUTS` and `GET_DEVICES_INPUTS`.
* **Output** routes distinguish:
  - **Bluetooth Headsets** (`TYPE_BLUETOOTH_SCO`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET`)
  - **Wired Headsets** (`TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`)
  - **Built-in Speaker / Earpiece** (`TYPE_BUILTIN_SPEAKER`, `TYPE_BUILTIN_EARPIECE`)
* **Input** routes are tracked separately, because an output device says nothing about the
  microphone. `TYPE_BLUETOOTH_A2DP` is playback-only and never appears in the input list,
  whereas `TYPE_BLUETOOTH_SCO` appearing there means the communication profile — and so a
  usable headset microphone — is actually available.
* The app never claims a Bluetooth microphone is active merely because Bluetooth headphones
  are connected. Where the route is inferred rather than known, `InputRouteInfo.isCertain` is
  `false` and Diagnostics render it as *"… (system-selected)"*.
* `AudioRouteSnapshotFactory` turns the enumerated devices into the pure snapshot the resolver
  consumes: A2DP is an output but never proof of a microphone; BLE speakers are excluded
  entirely because they are not private.

### 4.3 Half-Duplex Handoff (`StudyVoiceTurnGate`)
* The microphone opens **only** through the turn gate. It requires, in order: voice interaction
  not paused, a route that is not blocked, a usable microphone, no active or queued speech, an
  acoustic gap sized for the route, and a still-valid turn afterwards.
* The **acoustic gap** is route-aware (`AcousticGapPolicy`): headphones keep the user's tuned
  value (default 350 ms, 150–1200 ms); the phone speaker uses `max(450 ms, value × 1.25)`. The
  gap is a physical guard, never the mechanism that decides correctness.
* **Stale gaps are harmless.** Every pending start carries a generation; a skip, pause, end, new
  card, manual rating or route change invalidates it, so a gap that outlives its turn can never
  open the microphone.
* Push-to-talk is the one case where the user explicitly asked to talk, so it may briefly wait
  (≤600 ms) for the engine to actually stop instead of refusing.
* **Invariant:** `NOT(TTS active AND STT active)`. There is no full-duplex speaker mode.

### 4.4 Phone Mode & Self-Echo Protection
* Phone Mode is the ordinary route on a bare phone: question and feedback through the speaker,
  answers through the built-in microphone, hands-free, screen on or off (the foreground service
  keeps the session alive).
* The speaker's output leaks into the phone's own microphones, so the handoff adds: completed
  TTS + drained queue + stable route + acoustic gap + generation-validated start + readiness.
  "Open the microphone when `onDone` fires" is explicitly **not** the design.
* A `SelfEchoDetector` flags transcripts that closely match what the app just said. It is
  **diagnostics only** (`suspected_self_echo`): it never discards a transcript, because a user
  may legitimately repeat the question's own words, and a feedback sentence containing "Good"
  must never rate a card by itself (only an explicit rating may reschedule).
* Phone Mode metrics are local and contain counts, timings and error categories — never
  transcripts, and nothing is uploaded.

### 4.5 Disconnection Semantics
* "Headset lost" means the previously effective **external output device disappeared** — never
  merely "`isHeadsetConnected == false`". Starting the app without headphones is Phone Mode, not
  a loss, and produces no event, no prompt and no recovery loop.
* A loss cancels the live utterance and recognition turn, then applies the configured policy:
  **Pause voice study** (default) shows a card offering *Continue on phone* / *Wait for
  headphones*; **Continue on phone** cancels the interrupted turn, resolves Phone Mode and
  repeats the current question exactly once. *Continue on phone* is a session-scoped hold that
  is released when headphones return.
* A headset that appears mid-turn is a **deferred** change: the intent is parked in
  `pendingRoute` and applied at the next safe boundary, so the output device never changes
  mid-question. Mode changes the user makes deliberately (Settings, *Use headphones now*) apply
  immediately and re-issue the current question on the new route.

### 4.6 Speech Recognition (STT)
* Recognition is **turn-based**, never continuous: the microphone is open only inside an
  explicit answer or rating window.
* Every turn carries a purpose (`ANSWER`, `RATING`, `COMMAND`, `PUSH_TO_TALK_ANSWER`, …) which
  determines endpointing, watchdog budget, vocabulary biasing and acceptance thresholds.
* A turn ends only on a terminal recognizer callback, so a second turn can never start while
  the recognizer is still working (the `ERROR_RECOGNIZER_BUSY` race).
* Results are stamped with a request id and card id; a late callback from a cancelled turn is
  dropped rather than applied to the next card.
* Push-to-talk release calls `stopListening()` and **waits** for the final result — it never
  submits at the moment of release.
* Full model: `docs/STT_ARCHITECTURE.md`.
* **Reliability contract** (exactly-once submissions, stale-callback rejection, TTS/STT
  interlock, bounded retry, interruption matrix, test map): `docs/STT_RELIABILITY.md`.

---

## 5. Background Execution & Lock Screen Behavior

* When studying with the phone in a pocket or screen off:
  - `StudySessionForegroundService` maintains a persistent partial wake lock.
  - Ongoing user notification displays the current deck name, card number, and live state.
  - Notification action buttons provide quick manual `[Pause]`, `[Resume]`, and `[Stop]` control directly from the lock screen.
