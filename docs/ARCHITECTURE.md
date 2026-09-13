# Study Agent Client — Architecture Documentation

## 1. System Overview

The **Study Agent Mobile Client** is a lightweight, voice-first native Android application designed to serve as a mobile audio interface for an intelligent Study/Anki PC Agent.

### Core Architectural Principle: Thin Client Separation
* **PC Study Agent (Server & Source of Truth):**
  - Manages LLM inference (GPT-4o, Claude 3.5 Sonnet, Local Ollama/Hermes).
  - Integrates with Anki / AnkiConnect database.
  - Spaced repetition scheduling & algorithm calculations (FSRS / SM-2).
  - Answer evaluation & pedagogical feedback generation.
  - Session statistics, card queues, and historical memory.

* **Android Mobile Application (Voice Client):**
  - High-resilience WebSocket connection (LAN, Tailscale, WireGuard, WSS).
  - Bidirectional Speech-to-Text (STT) and Text-to-Speech (TTS) pipelines.
  - Study audio routing that is **headset-optional**: headphones when present, the phone
    speaker + built-in microphone otherwise (`docs/AUDIO_ROUTING.md`).
  - Foreground service for background hands-free study (screen off / phone locked).
  - Real-time state machine driving the study loop without requiring screen interaction.

```
                 ┌────────────────────────────┐
                 │       ANKI DATABASE        │
                 └─────────────┬──────────────┘
                               │ AnkiConnect
                 ┌─────────────▼──────────────┐
                 │    PC STUDY AGENT (HOST)   │
                 │ ────────────────────────── │
                 │ • LLM Evaluator (Ollama/API)│
                 │ • Study Session State      │
                 │ • Memory & User Profile    │
                 │ • WebSocket Server (:8765) │
                 └─────────────┬──────────────┘
                               │
                 JSON over WebSocket (WS / WSS)
                               │
                 ┌─────────────▼──────────────┐
                 │    ANDROID VOICE CLIENT    │
                 │ ────────────────────────── │
                 │ • Speech Recognition (STT) │
                 │ • Text-to-Speech (TTS)     │
                 │ • Hands-Free State Machine │
                 │ • Bluetooth Headset Route  │
                 │ • Jetpack Compose UI       │
                 └─────────────┬──────────────┘
                               │
                       Bluetooth A2DP / SCO
                               │
                         🎧 Headset
                               │
                             User
```

---

## 2. Layered Architecture

The Android app follows Clean Architecture principles with unidirectional data flow (MVI / MVVM):

```
┌─────────────────────────────────────────────────────────────┐
│                         UI LAYER                            │
│  • Jetpack Compose Screens (Home, Study, Connection, etc.) │
│  • ViewModels (StateFlow UI state, Event Handlers)         │
│  • Custom Voice Visualizers & Headset Badges                │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                       DOMAIN LAYER                          │
│  • Study State Machine (StudyState sealed hierarchy)        │
│  • Voice Command Normalizer & Bilingual Parser              │
│  • Session Controllers & Audio Route Policy                 │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                        DATA LAYER                           │
│  • Repositories (StudySession, Connection, Profile, Diag)  │
│  • WebSocketAgentConnection & ReconnectController           │
│  • FakeAgentConnection (Standalone Mock Client)            │
│  • Jetpack DataStore Preferences & SecureTokenStorage       │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                    CORE PLATFORM LAYER                      │
│  • Android SpeechRecognizer (Locale-aware STT)              │
│  • TtsEngineAdapter → single Android TextToSpeech instance  │
│  • AudioFocusController (transient spoken-audio focus)      │
│  • AudioManager & AudioDeviceCallback (Route Manager)       │
│  • Foreground Service with Media Actions Notification       │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Package Structure

```text
com.studyagent.client/
│
├── StudyAgentApp.kt                 # Application subclass & dependency bootstrap
├── MainActivity.kt                  # Single Activity host with runtime permissions
│
├── core/
│   ├── audio/                       # Study audio routing (pure policy + Android facts)
│   │   ├── StudyAudioModels.kt      # Modes, routes, snapshots, readiness, route events
│   │   ├── StudyAudioModeResolver.kt# preference + snapshot → effective route (pure)
│   │   ├── AudioRouteSnapshotFactory.kt # enumerated devices → snapshot (pure)
│   │   ├── AudioRouteManager.kt     # Android device callbacks, verified input route
│   │   ├── StudyAudioRouteCoordinator.kt # loss, deferred switch, user overrides
│   │   └── PhoneModeDiagnostics.kt  # Local counters + self-echo detector
│   │
│   ├── models/                      # Strongly typed data models & sealed interfaces
│   │   ├── ConnectionState.kt       # Disconnected, Connecting, Connected, Reconnecting, etc.
│   │   ├── StudyState.kt            # Idle, SpeakingQuestion, Listening, Evaluating, etc.
│   │   ├── ProtocolMessages.kt      # ClientMessage and ServerMessage sealed hierarchies
│   │   ├── StudySession.kt          # Session domain model
│   │   ├── StudyCard.kt             # Thin card model (id, question, index)
│   │   ├── Evaluation.kt            # Feedback, score, correct & missing points
│   │   ├── Rating.kt                # AGAIN, HARD, GOOD, EASY enum & parser
│   │   ├── VoiceCommand.kt          # Normalized voice commands
│   │   ├── ServerProfile.kt         # Server connection profiles
│   │   └── AppSettings.kt           # User preferences
│   │
│   ├── network/                     # Network communication engine
│   │   ├── AgentConnection.kt       # Connection interface
│   │   ├── WebSocketAgentConnection.kt # OkHttp WebSocket implementation
│   │   ├── FakeAgentConnection.kt   # Offline mock agent connection
│   │   ├── ReconnectController.kt   # Exponential backoff + jitter calculator
│   │   └── ProtocolJson.kt          # Kotlinx Serialization engine
│   │
│   ├── voice/                       # Voice interface subsystem
│   │   ├── SpeechRecognitionManager.kt # STT abstraction
│   │   ├── AndroidSpeechRecognitionManager.kt # Android SpeechRecognizer wrapper
│   │   ├── SpeechRecognitionResult.kt  # Partial, Final, NoSpeech, Error
│   │   ├── VoiceCommandManager.kt   # English + Arabic fuzzy regex command matcher
│   │   └── tts/                     # Speech-output subsystem (docs/TTS_ARCHITECTURE.md)
│   │       ├── SpeechModels.kt      # SpeechRequest/Result/Purpose/Priority/QueuePolicy/TtsState
│   │       ├── SpeechOrchestrator.kt# The single speech API (interface + health/metrics)
│   │       ├── DefaultSpeechOrchestrator.kt # Pure-Kotlin conductor (unit-tested)
│   │       ├── TtsEngineAdapter.kt  # Coroutine-first engine abstraction
│   │       ├── AndroidTtsEngineAdapter.kt # Hardened TextToSpeech wrapper (single engine)
│   │       ├── SpeechQueue.kt       # Bounded priority queue with policies
│   │       ├── TtsVoiceSelector.kt  # Deterministic offline-preferring voice ranking
│   │       ├── SpeechTextPreprocessor.kt # HTML/Anki markup cleanup (speech-only)
│   │       ├── MedicalPronunciationProcessor.kt # Abbreviations/units/numbers
│   │       ├── MixedLanguageSegmenter.kt # Arabic/English run segmentation
│   │       ├── SpeechChunker.kt     # Semantic chunking via getMaxSpeechInputLength
│   │       ├── AudioFocusController.kt # Spoken-audio focus policy
│   │       ├── SpeechFormatting.kt  # Centralized spoken labels & preview text
│   │       └── VoiceHandoffController.kt # TTS→STT acoustic-handoff policy
│   │
│   ├── audio/                       # Hardware audio routing
│   │   ├── AudioRouteManager.kt     # Modern AudioDeviceInfo routing manager
│   │   ├── AudioDeviceInfoModel.kt  # Route metadata model
│   │   └── HeadsetBroadcastReceiver.kt # Broadcast listener for headset events
│   │
│   ├── security/                    # Security & encryption
│   │   ├── SecureTokenStorage.kt    # EncryptedSharedPreferences wrapper
│   │   └── LogSanitizer.kt          # Token and credential redactor
│   │
│   └── common/                      # Common utilities
│       ├── AppLogger.kt             # Structured circular buffer logger
│       └── DispatcherProvider.kt    # Coroutine dispatcher abstractions
│
├── data/
│   ├── preferences/                 # Preferences & storage
│   │   ├── PreferencesDataStore.kt  # Jetpack DataStore Preferences
│   │   └── ProfileRepository.kt     # Profile management repository
│   │
│   └── repository/                  # Repositories
│       ├── ConnectionRepository.kt  # Active connection lifecycle & fake mode toggle
│       ├── StudySessionRepository.kt # Primary study orchestrator & hands-free engine
│       └── DiagnosticsRepository.kt # Log aggregator & export manager
│
├── di/                              # Dependency Injection
│   ├── AppContainer.kt              # Concrete dependency container
│   └── ServiceLocator.kt            # Global thread-safe locator
│
├── service/                         # Android Background & Foreground Services
│   ├── StudySessionForegroundService.kt # Foreground service keeping voice loop alive
│   └── NotificationHelper.kt        # Media action notification builder
│
└── ui/                              # Jetpack Compose UI
    ├── theme/                       # Design system (Dark Theme, Typography, Colors)
    ├── navigation/                  # Navigation graph & routes
    ├── components/                  # Reusable UI widgets (PushToTalk, RatingGroup, Waveform)
    └── screens/
        ├── home/                    # Dashboard & Start Study
        ├── study/                   # Primary Voice Study screen
        ├── connection/              # Server Profiles & LAN configuration
        ├── settings/                # Voice, TTS, and study settings
        └── diagnostics/             # Live log inspector & latency monitor
```

---

## 4. Study Audio Routing (Headset-Optional)

The client supports two equally valid environments: **Headset Mode** (headset output, headset
or phone microphone) and **Phone Mode** (phone speaker, built-in microphone). Which one runs is
decided by a small, pure decision layer, and nothing else in the app branches on
"is a headset connected".

```
AppSettings.studyAudioMode ─┐
                            ├─► StudyAudioModeResolver ─► EffectiveStudyAudioRoute
AudioRouteSnapshot ─────────┘        (pure)                (output, input, certainty,
    ▲                                                        readiness, acoustic profile)
    │
AudioRouteManager (Android)                     │
    ▲                                           ▼
AudioDeviceCallback                    StudyAudioRouteCoordinator
                                       (loss, pending route, overrides, generation)
                                                │
                                                ▼
                       StudyVoiceTurnGate ──► SpeechRecognitionOrchestrator
                       (gap + half-duplex)      (microphone opens here, and only here)
```

* **One workflow.** There is no `PhoneStudySession`; the same reducer, TTS pipeline and STT
  pipeline run on both routes. `StudyEffect`s, events and effects are identical.
* **Effective mode is derived, never stored.** `AUTO` is the default, `HEADSET_REQUIRED` is an
  advanced opt-in that is the only thing that may refuse a start.
* **The turn gate owns the microphone.** TTS completion, drained queue, stable route, acoustic
  gap and generation validation are all checked in one place, which is what keeps the
  half-duplex invariant true on the phone speaker.
* **Route changes are turn-boundary events.** A headset appearing is parked in `pendingRoute`
  until the next safe boundary; an unexpected loss cancels the live turn and applies the
  disconnect policy (pause with a *Continue on phone* offer, or continue on the phone directly).

---

## 5. Concurrency & Threading Model

1. **Main Thread (`Dispatchers.Main`):**
   - Jetpack Compose UI updates.
   - Android `SpeechRecognizer` lifecycle calls (Android requires `SpeechRecognizer` to be invoked from the main thread).
   - Android `TextToSpeech` lifecycle calls.

2. **IO Thread (`Dispatchers.IO`):**
   - OkHttp WebSocket socket read/write operations.
   - JSON serialization & deserialization.
   - DataStore preferences reading and writing.
   - EncryptedSharedPreferences access.

3. **Default Computation Thread (`Dispatchers.Default`):**
   - Voice command regex normalization and string matching.
   - State machine transition computations.
   - Speech pipeline: markup cleanup, pronunciation, segmentation, chunking
     (the `SpeechOrchestrator` actor + pump run here; engine calls are posted
     to the main thread by the engine adapter).
   - Recognition lifecycle: all `SpeechRecognitionOrchestrator` transitions (start, finish,
     cancel, backend events, watchdog, retry) are serialized through one monitor, so the
     study loop, the event collector, the watchdog and UI-triggered calls can never
     interleave a check-then-act sequence.
