# Implementation Plan, Roadmap & Validation

## 1. Phased Delivery Roadmap

### Phase 1 — Foundation & Project Setup ✅
- [x] Android Studio project structure with Clean Modular Architecture.
- [x] Kotlin Coroutines + Flow + Jetpack Compose + Material 3.
- [x] Data models (`ConnectionState`, `StudyState`, `StudySession`, `StudyCard`, `Evaluation`, `Rating`, `VoiceCommand`, `ServerProfile`, `AppSettings`).
- [x] Jetpack DataStore Preferences for settings & EncryptedSharedPreferences for secure token storage.
- [x] Verified clean compilation & unit tests.

### Phase 2 — WebSocket & Network Resilience ✅
- [x] `AgentConnection` interface and `WebSocketAgentConnection` implementation using OkHttp.
- [x] Exponential backoff reconnect controller with jitter.
- [x] Heartbeat ping/pong with real-time latency measurement.
- [x] `FakeAgentConnection` mock agent for offline local development & CI testing.
- [x] Kotlinx Serialization with defensive parsing and type discriminator.

### Phase 3 — Study Protocol Engine ✅
- [x] Support for all Client -> Server messages (`start_session`, `submit_answer`, `rate_card`, `repeat_question`, `request_hint`, `request_explanation`, `request_answer`, `skip_card`, `pause_session`, `resume_session`, `end_session`, `ping`).
- [x] Support for all Server -> Client messages (`session_started`, `question`, `evaluation`, `hint`, `explanation`, `answer`, `rating_saved`, `session_paused`, `session_resumed`, `session_finished`, `session_stats`, `error`, `pong`).

### Phase 4 & 5 — Voice Engine (TTS + STT) ✅
- [x] `AndroidTextToSpeechManager` with utterance completion listeners, pitch, rate, and locale selection.
- [x] `AndroidSpeechRecognitionManager` wrapping `SpeechRecognizer`, handling permissions, partial results, and silence timeouts.
- [x] `VoiceCommandManager` with English and Arabic normalized voice matching.

### Phase 6 & 7 — Full Hands-Free Study Loop ✅
- [x] Unidirectional study state machine:
  `Question -> Spoken Question -> User Speech -> Transcript -> PC -> Evaluation -> Spoken Feedback -> Rating -> Next Card`.
- [x] Large Push-to-Talk touch button for manual voice capture.
- [x] Direct Rating buttons (`Again`, `Hard`, `Good`, `Easy`) and quick tools (`Repeat`, `Hint`, `Explain`, `Skip`).

### Phase 8 & 9 — Hardware Audio Routing & Background Execution ✅
- [x] `AndroidAudioRouteManager` with modern `AudioDeviceCallback` and fallback.
- [x] `StudySessionForegroundService` with ongoing notification and lock-screen media actions.
- [x] Wake lock management for continuous screen-off voice sessions.

### Phase 10 — Hardening, Diagnostics & Mock PC Server ✅
- [x] Standalone Python Mock PC Agent (`mock_pc_agent.py`) simulating Anki sessions.
- [x] Automated integration test client (`test_client.py`).
- [x] In-app real-time diagnostic log inspector and copy-to-clipboard export.
- [x] 100% passing unit & integration test suite.

---

## 2. Future Backend Compatibility
The architecture decouples the mobile client from the server backend. Any PC agent can be plugged in as long as it adheres to the JSON WebSocket protocol:
- **Ollama / Local LLMs** (Llama 3.3, Mistral, Gemma)
- **Hermes Agent**
- **Cloud LLMs** (Claude 3.5, GPT-4o)
- **Custom Spaced Repetition Engines** (FSRS, SM-2, AnkiConnect)
- **LAN Discovery via mDNS / NSD** (Zero-configuration PC discovery)
