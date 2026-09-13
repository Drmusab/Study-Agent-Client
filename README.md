# Study Agent Android Voice Client 🎧📱

A native, production-quality Android application designed as the **mobile command center
and voice client** for an intelligent Study/Anki PC Agent.

Three surfaces, one product:

```
                STUDY AGENT
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
    Dashboard       Study       Control
   (observe &     (active     (configure
    launch)        card)       the agent)
```

- **Dashboard** — a live operational home screen: system readiness (PC Agent / Anki /
  AI / audio), smart Start/Resume, dynamic decks, today's stats, goal progress, weekly
  performance, rating distribution, server-generated insights and recommendations,
  AI usage — all sourced from the PC agent over Protocol v2, with honest
  Live/Cached/Stale freshness and an offline cache. See `docs/DASHBOARD.md`.
- **Study** — hands-free voice study loop: questions read aloud, spoken answers
  evaluated by the PC agent, spoken feedback and voice ratings.
- **Control Center** — how the agent studies: deck, mode, session target, evaluation
  strictness, feedback depth, Socratic follow-ups, hints, rating automation and
  transcript privacy, with presets and ACK-verified config sync. See
  `docs/CONTROL_CENTER.md`.

The Android client enables hands-free studying with Bluetooth headphones: it reads questions aloud, captures spoken answers, sends transcripts to the PC agent for LLM evaluation, speaks feedback, and updates Anki card ratings via natural voice commands. Protocol v1 agents remain fully supported for basic study; management panels gate off gracefully.

---

## 🚀 Key Features

* **Voice-First Hands-Free Loop:**
  `Question Spoken ➔ Voice Answer Capture ➔ STT ➔ PC Evaluation ➔ TTS Feedback ➔ Spoken Rating ➔ Next Card`
* **Bilingual Voice Recognition & Synthesis:**
  Full support for **English** and **Arabic (العربية)** speech recognition and voice commands (Again, Hard, Good, Easy, Repeat, Hint, Explain, Show Answer, Skip, Pause, Resume, Stop).
* **Multi-Server Profiles:**
  Configurable profiles for **Home PC (LAN)**, **Tailscale / WireGuard Mesh VPN**, **Android Emulator (`10.0.2.2`)**, and custom hosts with WebSocket (`ws://` / `wss://`) and token authentication.
* **Bluetooth Headphone Optimization:**
  Modern audio device routing with automatic route changes and graceful fallback to phone speaker/mic when disconnected.
* **Background & Lock-Screen Execution:**
  Foreground service with persistent partial wake-lock and interactive media-style notifications (Pause, Resume, Stop) allowing study sessions while walking or with the screen off.
* **Large Touch Controls:**
  Big **Push-to-Talk** button (press-and-hold or tap) and clear, high-contrast rating buttons (`Again`, `Hard`, `Good`, `Easy`).
* **Offline Mock Mode & Python Mock Server:**
  Built-in **"Use Fake Agent"** toggle and a standalone Python WebSocket server (`server/mock_pc_agent.py`) for rapid testing without requiring a live Anki or LLM setup.
* **Real-time Diagnostics:**
  In-app sanitized log viewer with log export, roundtrip ping latency monitoring, and audio route diagnostics.

---

## 🏗️ Architecture Overview

The system strictly adheres to thin client separation:

```
                    ┌────────────────────────┐
                    │    ANKI / ANKICONNECT  │
                    └───────────┬────────────┘
                                │
                    ┌───────────▼────────────┐
                    │     PC STUDY AGENT     │
                    │                        │
                    │ • LLM Evaluator        │
                    │ • Card Selection       │
                    │ • Anki Spaced Rep (FSRS│
                    │ • Study State & Memory │
                    └───────────┬────────────┘
                                │ WebSocket (WS / WSS)
                    ┌───────────▼────────────┐
                    │  ANDROID VOICE CLIENT  │
                    │                        │
                    │ • Speech-to-Text (STT) │
                    │ • Text-to-Speech (TTS) │
                    │ • Hands-Free State Mach│
                    │ • Jetpack Compose UI   │
                    └───────────┬────────────┘
                                │ Bluetooth SCO / A2DP
                                ▼
                       🎧 Bluetooth Headset
                                │
                              User
```

---

## 📋 Prerequisites & Requirements

* **Android Device or Emulator:** Android 8.0 (API Level 26) or higher (Targeting Android 14 / API Level 34).
* **JDK:** OpenJDK 17 or OpenJDK 21.
* **Android SDK:** Platform 34 and Build-Tools 34.0.0.
* **Gradle:** 8.7+ (Wrapper included).
* **PC Server:** Any PC running the Study Agent (or the included Python mock server).

---

## 🛠️ How to Build & Install

### 1. Build Debug APK
From the root directory:
```bash
./gradlew assembleDebug
```
The generated APK is located at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### 2. Build Release APK / AAB
```bash
./gradlew assembleRelease
```

### 3. Install APK on Connected Device / Emulator
Using ADB:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Run Unit & Integration Tests
```bash
./gradlew testDebugUnitTest
```

---

## 🌐 Connecting to the PC Agent

### A. Local Wi-Fi (LAN)
1. Ensure your Android phone and PC are connected to the same Wi-Fi network.
2. Find your PC's local IP address (e.g. `192.168.1.100` via `ipconfig` on Windows or `ip a` on Linux/macOS).
3. Open the **Connection** screen in the Android app.
4. Add or edit a profile with:
   - **Host:** `192.168.1.100` (your PC IP)
   - **Port:** `8765`
   - **Path:** `/ws`
5. Tap **Connect**.

### B. Remote Connection via Tailscale (Recommended)
1. Install [Tailscale](https://tailscale.com/) on both your PC and Android phone.
2. Log into your Tailscale tailnet.
3. Use the PC's Tailscale 100.x.x.x IP address in the Android app (e.g. `100.82.14.92:8765`).
4. Study securely anywhere over encrypted cellular data or public Wi-Fi without port forwarding!

### C. Android Studio Emulator Host
When running in the Android Studio Emulator, use:
* **Host:** `10.0.2.2`
* **Port:** `8765`

---

## 🧪 Testing with Mock PC Agent & Fake Agent Mode

### Option 1: Built-in Fake Agent Mode (Inside the App)
1. Open the Android app.
2. Go to the **Connection** screen.
3. Toggle on **"Use Fake Agent (Mock Mode)"**.
4. Go back to Home and tap **"Start Study Session"**.
5. The app simulates a complete Anki study session with realistic questions, speech recognition, evaluation, and ratings!

### Option 2: Standalone Python Mock PC Agent
Run the standalone mock server on your PC:
```bash
# Start mock agent server on port 8765
python3 server/mock_pc_agent.py
```
To verify the mock agent with an automated client:
```bash
python3 server/test_client.py
```

---

## 🎙️ Voice Controls & Bilingual Command Reference

The voice engine recognizes both English and Arabic commands locally for rapid response:

| Intent | English Voice Command | Arabic Voice Command |
| :--- | :--- | :--- |
| **Spaced Repetition: Again** | "Again", "Forgot", "Repeat card" | "مرة أخرى", "مرة اخرى", "اعد", "نسيت" |
| **Spaced Repetition: Hard** | "Hard", "Difficult", "Tough" | "صعب", "شاق", "مش سهل" |
| **Spaced Repetition: Good** | "Good", "Correct", "Got it" | "جيد", "تمام", "صحيح", "ممتاز" |
| **Spaced Repetition: Easy** | "Easy", "Simple", "Piece of cake" | "سهل", "بسيط", "واضح" |
| **Repeat Question** | "Repeat", "Repeat question", "Say again" | "أعد السؤال", "اعد السؤال", "كرر السؤال" |
| **Request Hint** | "Hint", "Give me a hint", "Need a hint" | "تلميح", "اعطني تلميح", "ساعدني" |
| **Request Explanation** | "Explain", "Why", "Tell me more" | "اشرح", "شرح", "وضح", "لماذا" |
| **Show Answer** | "Show answer", "What is the answer" | "اظهر الجواب", "أظهر الجواب", "ما هو الجواب" |
| **Skip Card** | "Skip", "Next", "Pass" | "التالي", "تخطي", "تجاوز" |
| **Pause Study** | "Pause", "Pause study", "Hold on" | "توقف", "توقف مؤقت", "انتظر" |
| **Resume Study** | "Resume", "Continue", "Keep going" | "اكمل", "استمر", "تابع" |
| **End Session** | "Stop", "End session", "Quit" | "انهاء", "إنهاء", "وقف", "خروج" |
| **Stop Speaking** *(cancels speech only, keeps session)* | "Stop speaking", "Quiet", "Enough" | "اسكت", "توقف عن الكلام", "اكتف" |
| **Remaining Cards** | "How many cards left?", "Status" | "كم بطاقة متبقية", "كم باقي" |

---

## ⚙️ Settings Configuration

From the **Settings** screen:
* **Voice Recognition (STT):** Choose between English (`en-US`) and Arabic (`ar-SA`).
* **Voice Output (TTS):**
  * **Per-language voices:** pick any installed engine voice for English and Arabic, or keep Auto (offline-preferring recommended ranking); buttons play a voice **preview**.
  * **Prefer Offline Voices:** on-device voices are ranked first so studying keeps working without Internet.
  * **Per-purpose speeds:** separate sliders for questions, feedback, and explanations (0.6x – 1.8x), plus pitch.
* **Advanced Speech:** automatic Arabic/English language detection for mixed cards, medical pronunciation (G C S, milliliters, "15 out of 15"), headset-disconnect behavior (pause speech vs. continue on speaker), and the mic handoff gap (150–1200 ms).
* **Hands-Free Mode:** Enable/disable automatic question reading and listening cycles.
* **Show Live Transcript:** Toggle real-time speech transcription display on the study card.
* **Auto Reconnect:** Enable exponential backoff reconnection when switching Wi-Fi networks.

---

## 🔍 Diagnostics & Troubleshooting

### WebSocket Connection Issues
1. **Server Unreachable:** Verify your PC agent is running and listening on `0.0.0.0:8765`.
2. **Firewall Blocking:** Check Windows Firewall / Linux `ufw` to ensure inbound TCP port 8765 is allowed.
3. **In-App Logs:** Tap the **Diagnostics** icon on the Home screen to view real-time timestamped protocol logs, audio routing state, and error traces. Tap **Copy Logs** to export the entire log buffer for troubleshooting.

### Microphone / Speech Recognition Issues
1. Ensure the **Microphone (RECORD_AUDIO)** runtime permission is granted in Android system settings.
2. For Arabic recognition, make sure the Google Speech Services / Language Pack for Arabic is downloaded in Android System Voice Settings if operating offline.

---

## 🔒 Security Summary

* **No LLM API Keys in APK:** All OpenAI / Anthropic / Local LLM keys reside solely on the PC host.
* **Token Protection:** Server authentication tokens are encrypted in hardware-backed `EncryptedSharedPreferences`.
* **Sanitized Logs:** All secret tokens, authorization headers, and credentials are automatically redacted before logging.

---

## 📂 Project Structure

```text
StudyAgentClient/
├── app/                              # Android Application Module
│   ├── src/main/java/com/studyagent/client/
│   │   ├── core/models/              # Domain models & protocol messages
│   │   ├── core/network/             # WebSocket connection & reconnect controller
│   │   ├── core/voice/               # STT manager, command parser & tts/ speech pipeline
│   │   ├── core/audio/               # AudioDevice routing & Bluetooth manager
│   │   ├── core/security/            # Encrypted token storage & log sanitizer
│   │   ├── data/                     # Repositories & Jetpack DataStore
│   │   ├── service/                  # StudySessionForegroundService & Notification
│   │   └── ui/                       # Jetpack Compose theme, navigation & screens
│   └── src/test/                     # Comprehensive Unit & Integration Tests
├── server/                           # PC Mock Agent & Test Utilities
│   ├── mock_pc_agent.py              # Standalone Python PC Study Agent
│   ├── run_mock_server.sh            # Run script for mock agent
│   └── test_client.py                # Automated protocol integration tester
├── docs/                             # Full Architectural Documentation
│   ├── ARCHITECTURE.md               # Deep architectural specification
│   ├── TTS_ARCHITECTURE.md           # Speech pipeline: engine, voices, queueing, handoff
│   ├── TTS_AUDIT.md                  # Pre-refactor audit (confirmed bugs → fixes)
│   ├── PROTOCOL.md                   # Complete Study Agent Protocol v1
│   ├── SECURITY.md                   # Security & threat model
│   ├── VOICE_FLOW.md                 # Voice UX, state machine & audio routing
│   └── IMPLEMENTATION_PLAN.md        # Roadmap & validation checklist
└── README.md                         # Quickstart & user guide
```
