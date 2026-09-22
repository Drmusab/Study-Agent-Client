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
  - High-resilience WebSocket connection with explicit lifecycle (transport vs agent ready).
  - Typed AgentApi over transport with request correlation and idempotency.
  - Bidirectional Speech-to-Text (STT) and Text-to-Speech (TTS) pipelines.
  - Study audio routing that is **headset-optional**: headphones when present, the phone speaker + built-in microphone otherwise.
  - Foreground service for background hands-free study (screen off / phone locked).
  - Real-time state machine driving the study loop without requiring screen interaction.

```
                 ┌────────────────────────────┐
                 │       ANKI DATABASE        │
                 └─────────────┬──────────────┘
                               │ AnkiConnect (localhost only)
                 ┌─────────────▼──────────────┐
                 │    PC STUDY AGENT (HOST)   │
                 │ ────────────────────────── │
                 │ • LLM Evaluator            │
                 │ • Study Session State      │
                 │ • WebSocket Server (:8765) │
                 │ • Auth + Capabilities      │
                 └─────────────┬──────────────┘
                               │ WebSocket JSON (WS/WSS)
                               │ Bearer auth, welcome handshake
                 ┌─────────────▼──────────────┐
                 │    ANDROID VOICE CLIENT    │
                 │ ────────────────────────── │
                 │ • AgentClient (handshake)  │
                 │ • AgentApi (typed)         │
                 │ • Speech Recognition (STT) │
                 │ • Text-to-Speech (TTS)     │
                 │ • Study Session Machine    │
                 └─────────────┬──────────────┘
                               │ Bluetooth A2DP / SCO
                         🎧 Headset / Phone
                               │ User
```

## 2. Layered Architecture (Updated for Agent API)

```
┌─────────────────────────────────────────────────────────────┐
│                         UI LAYER                            │
│  • Compose Screens (Home, Study, Connection, Control)      │
│  • ViewModels (StateFlow UI state, Event Handlers)         │
│  • Connection test, staged status, help                    │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                    FEATURE REPOSITORIES                     │
│  • StudySessionMachineRepository (study business logic)     │
│  • DashboardRepository (coalescing, cache, freshness)      │
│  • StudyControlRepository (config draft + ACK)             │
│  • ConnectionRepository (profiles, intent)                 │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                     AGENT API LAYER                         │
│  • AgentApi (typed methods: startSession, submitAnswer...) │
│  • AgentEvent (Flow: question, evaluation, progress...)    │
│  • ApiResult (Success/Rejected/Timeout/Disconnected)       │
│  • AgentApiError (stable codes)                            │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                 REQUEST COORDINATION LAYER                  │
│  • RequestCoordinator (messageId correlation, in_reply_to) │
│  • Timeouts purpose-specific (handshake 8s, eval 30s)      │
│  • Bounded map, cancellation, leak prevention              │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                     PROTOCOL LAYER                          │
│  • ProtocolJson (envelope-first, unknown type handling)    │
│  • ProtocolContext (negotiated version, session)           │
│  • MessageFactory (centralized creation, BuildConfig)      │
│  • Size limits (2MB frame, 10KB question, etc)             │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                   CONNECTION MANAGEMENT                     │
│  • AgentClient (handshake, auth, capability negotiation)   │
│  • WebSocketAgentConnection (socket lifecycle, generation) │
│  • NetworkMonitor (connectivity awareness)                 │
│  • ReconnectController (exponential backoff + jitter)      │
│  • ConnectionState (rich lifecycle, not just Connected)    │
│  • AgentConnectionSnapshot (single source of truth)        │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                        DATA LAYER                           │
│  • ProfileRepository (non-secret) + SecureTokenStorage     │
│  • ManagementCacheStorage (dashboard snapshot, decks)      │
│  • PreferencesDataStore (AppSettings)                      │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                    CORE PLATFORM LAYER                      │
│  • SpeechRecognitionOrchestrator, SpeechOrchestrator       │
│  • AudioRouteManager, StudyAudioRouteCoordinator           │
│  • Foreground Service + Notification                       │
└─────────────────────────────────────────────────────────────┘
```

### Responsibility Boundaries

#### WebSocketAgentConnection
Owns: socket lifecycle, frames, transport errors, generation, ping loop, liveness detection, handshake timeout

#### AgentClient (REMOVED 2026-09 — never instantiated; responsibilities now live in WebSocketAgentConnection + CapabilityStore + feature repositories)
Was documented as owning: handshake (hello/welcome), auth (Bearer preferred, legacy frame fallback), protocol negotiation (selected_protocol), request correlation (in_reply_to), capabilities, session recovery (request_session_snapshot after reconnect)

#### ConnectionRepository
Owns: profiles, connect/disconnect intent, effective connection state (ConnectionState + Snapshot), fake mode toggle, test connection

#### Feature Repositories
Own: study/dashboard/control business behavior, not transport

#### Network Layer Should NOT Know
- when hint should be shown
- how rating is chosen
- how voice behaves
It only transports and correlates protocol events.

## 3. Connection Lifecycle (New)

### Target Conceptual Lifecycle

```
DISCONNECTED
  │
  ▼
RESOLVING (DNS)
  │
  ▼
CONNECTING_TRANSPORT (TCP/WebSocket)
  │
  ▼
TRANSPORT_OPEN (socket open, NOT yet Study Agent)
  │
  ▼
HANDSHAKING (hello sent, waiting welcome)
  │
  ▼
AUTHENTICATING (Bearer header or legacy frame)
  │
  ▼
NEGOTIATING_CAPABILITIES (capabilities/welcome)
  │
  ▼
READY (agent verified, protocol negotiated, authenticated, capabilities known)
  or READY_LEGACY (v1 fallback)
```

Failure branches:

```
NETWORK_UNAVAILABLE (no Android network)
SERVER_UNREACHABLE (DNS failure, refused, timeout)
TLS_FAILED
AUTHENTICATION_FAILED (401/403 or error code)
INCOMPATIBLE_PROTOCOL (client 1,2 vs server 3)
HANDSHAKE_TIMEOUT (wrong service on port)
AGENT_UNAVAILABLE
```

Reconnect:

```
READY -> connection lost -> RECONNECTING (backoff) -> HANDSHAKING -> RECONCILING SESSION (request_session_snapshot) -> READY
```

### Why Separate States?

Previously:

```
TCP/WebSocket opened
↓
ConnectionState.Connected
```

Before proving:

```
this endpoint is actually a Study Agent
protocol is compatible
authentication succeeded
capabilities are known
agent is ready
```

Now: Transport connectivity and agent readiness are NOT same thing. Model separately:

```
NETWORK AVAILABLE
TRANSPORT OPEN
STUDY AGENT DETECTED (welcome received)
PROTOCOL NEGOTIATED (selected_protocol)
AUTHENTICATED
AGENT READY
ANKI READY (from component_health, not inferred)
AI READY (from component_health)
```

Never collapse all into Connected=true.

### ConnectionState Evolution

```kotlin
sealed interface ConnectionState {
    data object Disconnected
    data class Resolving(val host: String, val port: Int)
    data class ConnectingTransport(val host: String, val port: Int)
    data class TransportConnected(val host: String, val port: Int, val serverName: String? = null)
    data class Handshaking(val host: String, val port: Int)
    data class Authenticating(val host: String, val port: Int)
    data class NegotiatingCapabilities(val host: String, val port: Int, val serverName: String?, val protocolVersion: String?)
    data class Ready(val host: String, val port: Int, val serverName: String, val serverVersion: String?, val protocolVersion: String, val latencyMs: Long?, val capabilities: Set<String>, val agentId: String?, val authenticated: Boolean)
    data class ReadyLegacy(val host: String, val port: Int, val serverName: String?, val latencyMs: Long?)
    data class Reconnecting(val attempt: Int, val maxAttempts: Int, val nextRetryInMs: Long, val reason: String?, val phase: String?)
    data object NetworkUnavailable
    data class AuthenticationFailed(val reason: String, val isExpired: Boolean = false)
    data class TlsFailure(val reason: String)
    data class ProtocolMismatch(val reason: String, val serverVersion: String?, val clientVersions: List<String>)
    data class AgentUnavailable(val reason: String)
    data class ServerUnavailable(val reason: String)
    data class HandshakeTimeout(val host: String, val port: Int)
    data class Error(val message: String, val cause: Throwable?)

    // Legacy compat
    data class Connecting(val host: String, val port: Int)
    data class Connected(val host: String, val port: Int, val serverName: String?, val latencyMs: Long?)
}
```

### Connection Snapshot (Coherent Truth)

```kotlin
data class AgentConnectionSnapshot(
    val phase: ConnectionState,
    val transport: TransportStatus,
    val profile: ServerProfile?,
    val protocolVersion: String?,
    val serverName: String?,
    val serverVersion: String?,
    val agentId: String?,
    val capabilities: Set<String>,
    val authenticated: Boolean?,
    val latencyMs: Long?,
    val lastMessageAgeMs: Long?,
    val retry: ReconnectInfo?,
    val problem: ConnectionProblem?
)
```

One authoritative concept of AgentReady consumed by Dashboard, Study, Control, Connection, Diagnostics - not independently inferring from WebSocket != null.

## 4. Package Structure (Updated)

```
com.studyagent.client/
├── core/
│   ├── models/
│   │   ├── ConnectionState.kt (rich lifecycle)
│   │   ├── ServerProfile.kt (enhanced validation: IPv4, IPv6, Tailscale, MagicDNS)
│   │   ├── ProtocolMessages.kt (welcome, in_reply_to, client build info, error codes)
│   │   ├── AgentCapabilities.kt (isProtocolV2, supportsV2)
│   │   └── ...
│   ├── network/
│   │   ├── AgentConnection.kt (interface + snapshot + test)
│   │   ├── WebSocketAgentConnection.kt (generation, handshake timeout, liveness, ping correlation, single auth path)
│   │   ├── AgentClient.kt (handshake, auth, negotiation, correlation, recovery)
│   │   ├── AgentApi.kt (typed API + events)
│   │   ├── AgentApiResult.kt (Success/Rejected/Timeout/Disconnected/ProtocolFailure)
│   │   ├── AgentApiError.kt (stable codes, user messages)
│   │   ├── RequestCoordinator.kt (bounded, timeouts, in_reply_to)
│   │   ├── ProtocolContext.kt (negotiated version, session, BuildConfig version)
│   │   ├── MessageFactory.kt (centralized creation)
│   │   ├── ConnectionProblem.kt (typed reasons + user actions)
│   │   ├── TransportStatus.kt (DISCONNECTED, RESOLVING, CONNECTING, OPEN, CLOSING, FAILED)
│   │   ├── AgentConnectionSnapshot.kt (coherent truth)
│   │   ├── NetworkMonitor.kt (connectivity awareness)
│   │   ├── NetworkStats.kt (counters, latency, message rate)
│   │   ├── ProtocolJson.kt (envelope-first, unknown handling, size limits)
│   │   ├── ReconnectController.kt (exponential backoff + jitter)
│   │   └── FakeAgentConnection.kt (welcome, in_reply_to, enhanced lifecycle, test connection)
│   ├── security/
│   │   └── SecureTokenStorage.kt (EncryptedSharedPreferences, separate from profile JSON)
│   └── ...
├── data/
│   ├── preferences/
│   │   └── ProfileRepository.kt (token separation, validation)
│   └── repository/
│       ├── ConnectionRepository.kt (snapshot, test, diagnostics, override)
│       ├── CapabilityStore.kt (welcome + capabilities, single authority)
│       ├── DashboardRepository.kt (coalescing, bounded timeouts, out-of-order guard, cache)
│       └── StudySessionRepository.kt (idempotency via ledger, turn identity)
└── ui/
    └── screens/connection/
        ├── ConnectionScreen.kt (staged status, test, advanced editor, help)
        └── ConnectionViewModel.kt (snapshot, test results, token rotation)
```

## 5. Study Audio Routing (Unchanged)

See previous doc - headset-optional, effective mode derived, turn gate owns mic, route changes turn-boundary events.

## 6. Concurrency & Threading

- Main: Compose UI, SpeechRecognizer, TextToSpeech
- IO: OkHttp WebSocket, JSON, DataStore, EncryptedSharedPreferences
- Default: voice command matching, state machine, speech pipeline (markup cleanup, pronunciation, segmentation, chunking), recognition lifecycle serialized via monitor

Additional:

- Connection generation AtomicLong prevents late callbacks from stale connections (critical for OkHttp WebSocket callbacks)
- RequestCoordinator uses ConcurrentHashMap + Mutex for bounded pending map, no leak over multi-hour sessions
- NetworkMonitor uses ConnectivityManager.NetworkCallback, distinct flows for network available vs agent unreachable

## 7. Management Layer (Dashboard + Control)

```
ConnectionRepository (transport, message bus, snapshot)
        │
CapabilityStore ◄──── welcome + capabilities (negotiation, 4s fallback to v1)
        │
        ├── DashboardRepository ── DashboardUiState ── HomeScreen
        │     request_dashboard/decks/health/history/insights/ai_usage
        │     single-flight coalescing, 8s timeouts, out-of-order guard via generated_at
        │     offline cache, session push merges (session_progress, session_stats)
        │
        └── StudyControlRepository ── ControlCenterUiState ── ControlCenterScreen
              serverConfig ↔ draft ↔ localConfig
              update_study_config → ACK (in_reply_to echo) / reject / timeout
              StudyPreset.applyTo / matching
```

Data ownership:

1. PC Agent computes everything analytic (pace, weakness, recommendation, AI cost) - Android renders
2. CapabilityStore single capability authority
3. Dashboard data flow server → repository → single StateFlow → Compose, lifecycle/repository-driven, no network I/O on recomposition
4. Control config commits only on correlated server ACK (in_reply_to), rejection/timeout preserve authoritative config + draft
5. Session start uses Control Center's live config; v1 servers receive backward-compatible deck + mode only
6. Settings (device speech/audio) and Control Center (agent behavior) never overlap

## 8. Production Connection Architecture (Target)

```
                        UI (Compose)
                         │
                         ▼
               ConnectionRepository (profiles, intent, snapshot)
                         │
                         ▼
                  AgentClient (handshake, auth, negotiation, recovery)
                  /         \
                 /           \
                ▼             ▼
           AgentApi        AgentEvents (Flow: question, evaluation, progress...)
                \             /
                 \           /
                  ▼         ▼
              Request Coordinator (correlation, timeouts, bounded)
                       │
                       ▼
                 Protocol Codec (JSON, envelope-first, size limits, BuildConfig version)
                       │
                       ▼
               Connection Manager (generation, handshake timeout, liveness)
                /             \
               ▼               ▼
       Network Monitor     WebSocket (OkHttp, Bearer header, single ping loop)
                               │
                               ▼
                         PC Study Agent (reference impl in mock_pc_agent.py)
                               │
                               ├── AnkiConnect localhost
                               └── LLM provider/local model
```

## 9. Security Architecture

See docs/SECURITY.md for full threat model.

Key points:

- No LLM API keys in APK, only Study Agent auth token
- Tokens in EncryptedSharedPreferences, not profile JSON, never logged
- WS vs WSS: allow ws:// for trusted LAN with UI label, recommend Tailscale or WSS for internet, no public ws:// port forwarding
- TLS: never trust all certs, pinning explicit for self-signed
- agent_id persistent server ID, not hardware, recognizes same agent at new address
- Size limits, backpressure, critical events prioritized
- No arbitrary Android command execution
- Server validates all client input
- Rate limiting for expensive actions

## 10. Testing

- Protocol contract: Android Hello -> server Welcome, StartSession -> SessionStarted -> Question, SubmitAnswer -> Evaluation, RateCard -> RatingSaved -> Question
- Auth: no token, correct token, missing, wrong, expired, Bearer vs legacy frame, token leakage in logs
- Wrong endpoint: socket opens but no Study Agent handshake -> handshake failure, not Ready
- Protocol mismatch: server only v3, client 1,2 -> incompatible, no infinite retry
- Handshake timeout, auth timeout
- Ping: normal, late, duplicate, missing, correlation via in_reply_to
- Network loss during idle, question, answer submission, evaluation wait, rating -> recovery via snapshot
- Exactly-once: rating sent, server applies, ACK lost, reconnect, same request resent -> Anki rating applied once
- Large payloads, malformed frames (no crash), unknown message type (no destroy)
- Buffer stress: many progress/dashboard updates, critical preserved
- Endurance: 24h virtual, many pings, reconnects, 1000+ turns, no socket leak, no request-tracker leak, no coroutine leak, no duplicate heartbeat
- Diagnostics: richer, sanitized, no tokens

See docs/TESTING_STRATEGY.md and server/mock_pc_agent.py --chaos mode.

## 11. Anki Fusion Layer (GATE 01 contract)

The PC Study Agent is no longer the only Anki provider. The ratified contract
lives in `docs/ANKI_INTEGRATION_ARCHITECTURE.md`; the normative domain types
live in `core/anki/`. Summary of what changed architecturally (no runtime
behavior change yet):

```
StudySessionMachine (user flow — unchanged authority)
        │  effects invoke gateway; callbacks return as events
        ▼
core/anki domain (backend-neutral: refs, availability, capabilities,
                  session context, rendered card, errors)
        ▼
AnkiBackend ──┬── AnkiDroidBackend (GATE 04; AnkiDroid integration API)
              └── PcAnkiBackend      (GATE 04/06; existing PC protocol)
```

Key rules, all with stable invariant IDs (INV-ANKI-01…14) in the contract:

- **Anki owns learning-state truth** (scheduling, FSRS, due state, review
  history, collection, sync). Study-Agent owns interaction-state truth
  (session machine, voice, evaluation, analytics). No second scheduler.
- **One review session = one writable Anki backend**, resolved once at session
  start into `AnkiSessionContext`; no silent mid-session failover.
- **One review turn = at most one scheduling mutation**, keyed by
  `ReviewCommitId` (backend + session + turn — never card id alone); ambiguous
  commits block progression until reconciled.
- **Suggested ≠ selected ≠ committed rating**; the user is the final rating
  authority by default.
- **Backend-specific types never cross the gateway**: no AnkiDroid
  provider/contract types and no PC protocol types above `core/anki`.
- **Anki backend choice is independent of AI/TTS/STT providers**; offline
  AnkiDroid review without a PC connection and hybrid configurations are
  first-class.

ADRs: `docs/adr/0001`–`0007`. GATE 01 addendum for the layered diagram: the
Anki domain layer sits between "FEATURE REPOSITORIES" and the backends; the PC
"AGENT API LAYER" becomes the transport for `PcAnkiBackend` (data/anki/remote),
not a second study path.
