# Voice UX, Interaction Flow & Audio Architecture

## 1. Primary Voice UX Principle
> **The user should be able to study while wearing headphones without continuously looking at or touching the phone.**

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

### 4.1 Route Detection
* `AudioRouteManager` registers Android's `AudioDeviceCallback` and monitors **both** `AudioManager.GET_DEVICES_OUTPUTS` and `GET_DEVICES_INPUTS`.
* **Output** routes distinguish:
  - **Bluetooth Headsets** (`TYPE_BLUETOOTH_SCO`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET`)
  - **Wired Headsets** (`TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`)
  - **Built-in Speaker** (`TYPE_BUILTIN_SPEAKER`)
* **Input** routes are tracked separately, because an output device says nothing about the
  microphone. `TYPE_BLUETOOTH_A2DP` is playback-only and never appears in the input list,
  whereas `TYPE_BLUETOOTH_SCO` appearing there means the communication profile — and so a
  usable headset microphone — is actually available.
* The app never claims a Bluetooth microphone is active merely because Bluetooth headphones
  are connected. Where the route is inferred rather than known, `InputRouteInfo.isCertain` is
  `false` and Diagnostics render it as *"… (system-selected)"*.
* Losing the microphone route during recognition cancels the turn; a half-recognised answer
  is never submitted.

### 4.2 Disconnection Resilience
* `AudioRouteManager` reports *device presence* via `AudioDeviceCallback`; the speech pipeline treats a drop while speaking per the user setting (default **Pause speech**):
  1. In-flight and queued speech requests are cancelled with a typed `ROUTE_LOST` failure — nothing continues over the loudspeaker by surprise.
  2. The session remains alive; the study state machine is untouched.
  3. On reconnect, the interrupted question is repeated once (deterministic), only when hands-free mode is on.
* `CONTINUE_ON_PHONE` restores the legacy "Android reroutes everything" behavior for users who want it.

### 4.3 Speech Pipeline Notes (see docs/TTS_ARCHITECTURE.md for the full model)
* All speech goes through `SpeechOrchestrator` as structured `SpeechRequest`s with purposes, priorities and queue policies; results are `Completed / Cancelled / Failed` delivered exactly once.
* The TTS→STT transition is governed by `VoiceHandoffController`: Completed-confirmed end + acoustic gap (default 350 ms, settings-tunable 150–1200 ms) before the microphone opens. There is no fixed sleep anywhere in the loop.
* Mixed Arabic/English cards are segmented per-language and spoken by the matching installed voice; medical numbers/units/abbreviations are normalized for speech only.

### 4.4 Speech Recognition (STT)
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
