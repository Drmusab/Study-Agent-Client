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

### Phase 4 & 5 — Voice Engine (TTS + STT) ✅ (TTS rebuilt 2026-09-11)
- [x] `AndroidSpeechRecognitionManager` wrapping `SpeechRecognizer`, handling permissions, partial results, and silence timeouts.
- [x] `VoiceCommandManager` with English and Arabic normalized voice matching, incl. "stop speaking".
- [x] **TTS overhaul:** structured `SpeechOrchestrator` (purposes/priorities/queue policies), coroutine-first `TtsEngineAdapter` with init queueing, callback-lifetime guarantees, watchdog timeouts and bounded recovery; installed-voice discovery with deterministic offline-preferring ranking and previews; speech-only HTML/medical preprocessing; Arabic/English segmentation with per-language voices; platform-limit semantic chunking; spoken-audio focus policy; explicit TTS→STT handoff controller; TTS health in Diagnostics. See `docs/TTS_ARCHITECTURE.md` and `docs/TTS_AUDIT.md`.

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
- [x] TTS health card (engine/voices/focus/queue/last-error/metrics) in Diagnostics.
- [x] Expanded unit test suite: speech pipeline component tests + deterministic fake-engine orchestrator tests (see `app/src/test/.../tts/`).

---

## 2. Future Backend Compatibility
The architecture decouples the mobile client from the server backend. Any PC agent can be plugged in as long as it adheres to the JSON WebSocket protocol:
- **Ollama / Local LLMs** (Llama 3.3, Mistral, Gemma)
- **Hermes Agent**
- **Cloud LLMs** (Claude 3.5, GPT-4o)
- **Custom Spaced Repetition Engines** (FSRS, SM-2, AnkiConnect)
- **LAN Discovery via mDNS / NSD** (Zero-configuration PC discovery)

---

## 3. Anki Fusion Roadmap (GATE 00 → GATE 06+)

GATE 00 baselined the repository; **GATE 01 ratified the Anki source-of-truth
architecture contract** (`docs/ANKI_INTEGRATION_ARCHITECTURE.md`, ADRs
`docs/adr/0001`–`0007`, domain types in `core/anki/`); **GATE 02 delivered the
AnkiDroid detection, permission and health foundation** (implementation notes:
`docs/ANKI_INTEGRATION_ARCHITECTURE.md` §25; reference and recovery tables:
`docs/ANKIDROID_INTEGRATION.md`; report: `docs/GATE_02_ANKIDROID_FOUNDATION.md`).
Later gates implement against that contract; anything listed as deferred in its
§24 must not land early.

| Gate | Scope (architecture per contract) | Status |
|---|---|---|
| GATE 00 | Repository baseline, safety lock, green-build evidence | Done (conditionally; CI blocked by account billing lock — see `docs/GATE_00_BASELINE.md`) |
| GATE 01 | Anki fusion architecture & ownership contract; minimal `core/anki` domain types + contract tests; **no** AnkiDroid dependency, no behavior change | Done (this baseline) |
| GATE 02 | AnkiDroid detection & permission foundation: provider/package discovery, provider-spec detection, permission visibility, bounded read-only probe, single health owner — all inside `data/anki/ankidroid/`; **no artifact linked** (decision: `docs/ANKIDROID_INTEGRATION.md` §2) | Done — `docs/GATE_02_ANKIDROID_FOUNDATION.md` (Android build/tests still blocked by environment) |
| GATE 03 | Domain wiring: backend registry + selector + `AnkiBackendMode` preference in settings; PC registered as a backend | Pending |
| GATE 04 | Backends: `PcAnkiBackend` (existing protocol adapted) + `AnkiDroidBackend` (decks/availability/rendered cards) | Pending |
| GATE 05 | Decks & library via the gateway (`AnkiDeckRef` identity, cache discipline) | Pending |
| GATE 06 | Review & rating through the gateway: commit ledger, exactly-once, ambiguous-commit reconciliation, backend-scoped connection handling, offline/hybrid review | Pending |
| GATE 07+ | Presentation modes, media, mistake notebook, analytics, AI semantic search | Pending |

Backward-compatibility rule for the whole roadmap: the PC-agent study path must
stay green at every gate (contract §22 migration strategy).
