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

> **"Stop speaking"** ("stop speaking", "quiet", "enough" / "اسكت", "توقف عن الكلام") cancels speech output **without** ending the session; plain "stop" still ends it. Checked before EndSession during parsing.

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
* `AudioRouteManager` registers Android's `AudioDeviceCallback` and monitors `AudioManager.GET_DEVICES_OUTPUTS`.
* Distinguishes between:
  - **Bluetooth Headsets** (`TYPE_BLUETOOTH_SCO`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET`)
  - **Wired Headsets** (`TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`)
  - **Built-in Speaker** (`TYPE_BUILTIN_SPEAKER`)

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

---

## 5. Background Execution & Lock Screen Behavior

* When studying with the phone in a pocket or screen off:
  - `StudySessionForegroundService` maintains a persistent partial wake lock.
  - Ongoing user notification displays the current deck name, card number, and live state.
  - Notification action buttons provide quick manual `[Pause]`, `[Resume]`, and `[Stop]` control directly from the lock screen.
