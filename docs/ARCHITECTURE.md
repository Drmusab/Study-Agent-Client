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
  - Bluetooth headphone audio routing and SCO / LE Audio management.
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
│  • Android TextToSpeech (Utterance listener queue)          │
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
│   │   ├── TextToSpeechManager.kt   # TTS abstraction with utterance callbacks
│   │   ├── AndroidTextToSpeechManager.kt # Android TextToSpeech engine
│   │   └── VoiceCommandManager.kt   # English + Arabic fuzzy regex command matcher
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

## 4. Concurrency & Threading Model

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
