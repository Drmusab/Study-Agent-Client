# Agent API Specification

> NOTE (2026-09): the `AgentApi` / `AgentClient` / `Transport` / `NetworkMonitor` Kotlin
> layer described below was removed — it was never instantiated and the live path is
> `WebSocketAgentConnection` + the feature repositories (`StudySessionMachineRepository`,
> `DefaultDashboardRepository`, `DefaultStudyControlRepository`). This document is kept as
> the protocol-level reference (frames, correlation, idempotency) pending a doc rewrite.

High-level typed API over WebSocket transport.

## Architecture

```
UI Features (Session, Dashboard, Control)
       │
       ▼
   AgentApi (typed methods + events Flow)
       │
       ├── Request/Response Coordinator (correlation, timeouts)
       └── Server Event Stream (push events)
               │
               ▼
          Protocol Codec (JSON)
               │
               ▼
          AgentConnection (WebSocket lifecycle)
               │
               ▼
          WebSocket transport
```

## Connection Lifecycle (Authoritative)

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
TRANSPORT_OPEN (socket open, not yet Study Agent)
  │
  ▼
HANDSHAKING (hello sent, waiting welcome)
  │
  ▼
AUTHENTICATING (if required)
  │
  ▼
NEGOTIATING_CAPABILITIES (capabilities/welcome)
  │
  ▼
READY (agent verified, protocol negotiated, authenticated)
  or READY_LEGACY (v1 fallback)
```

Failure branches:

```
NETWORK_UNAVAILABLE
SERVER_UNREACHABLE (DNS, refused, timeout)
TLS_FAILED
AUTHENTICATION_FAILED
INCOMPATIBLE_PROTOCOL
HANDSHAKE_TIMEOUT (wrong service)
AGENT_UNAVAILABLE
```

Reconnect:

```
READY -> connection lost -> RECONNECTING -> HANDSHAKING -> RECONCILING SESSION (request_session_snapshot) -> READY
```

Never collapse all into Connected=true. Model separately:

```
NETWORK AVAILABLE
TRANSPORT OPEN
STUDY AGENT DETECTED
PROTOCOL NEGOTIATED
AUTHENTICATED
AGENT READY
ANKI READY
AI READY
```

## Connection Snapshot (Single Source of Truth)

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

All UI (Dashboard, Study, Control, Connection, Diagnostics) consumes this, not WebSocket != null.

## Handshake

### v2

```
Android                         PC Agent
  │ WebSocket open                │
  ├── hello (supported_versions) ─►│
  │                               ├── welcome (selected_protocol, capabilities, auth, agent_id)
  │◄── welcome ───────────────────┤
  │                               │
  ├── (optional authenticate) ────►│ if Bearer not accepted
  │◄── (auth result via error or welcome) ──┤
  │
  ├── capabilities? ──────────────►│ (if separate)
  │◄── capabilities ──────────────┤
  │
  │ READY
```

Welcome:

```json
{
  "type": "welcome",
  "protocol_version": "2",
  "message_id": "...",
  "in_reply_to": "<hello message_id>",
  "server_name": "StudyPC-Agent",
  "server_version": "2.3.0",
  "selected_protocol": "2",
  "capabilities": ["dashboard", "deck_list", "study_config", "session_recovery"],
  "authentication": {"required": true, "authenticated": true, "methods": ["bearer"]},
  "agent_id": "persistent-id"
}
```

### v1 Legacy

```
hello
+ ping
↓ receive recognized response/pong
→ ReadyLegacy
Capabilities unknown/limited
```

## Protocol Context & Message Factory

Avoid defaulting to protocol 1 after v2 negotiation. Use ProtocolContext:

```kotlin
data class ProtocolContext(
    val negotiatedVersion: String,
    val sessionId: String?,
    val clientName: String,
    val clientVersion: String, // from BuildConfig.VERSION_NAME, not hard-coded
    val platform: String = "android",
    val androidApi: Int
)
```

MessageFactory centralizes:

```
message_id (UUID)
protocol_version (from context)
timestamp (ISO-8601)
session_id (from context)
```

Strongly typed messages preserved, but factory provides consistent envelope.

## Client Version

- Must come from BuildConfig.VERSION_NAME, not hard-coded "2.0.0" in model
- Optional build info: client_name, client_version, platform, android_api
- No IMEI, Android ID, device serial

## Authentication

### Preferred: Bearer header

```
WebSocket upgrade:
Authorization: Bearer <token>
```

Server accepts or 401/403.

### Legacy: authenticate frame

Keep only for legacy agents, capability/protocol decides if needed. Do NOT send both by default.

Auth result explicit: UI knows "socket open but auth rejected" without waiting for unrelated failure.

### Token Handling

- ServerProfile = non-secret config
- SecureTokenStorage = token
- Never log tokens: test HTTP headers, authenticate frame, profile export, diagnostics export, exceptions
- Support rotation: if server rejects previously valid token, show "Authentication expired or invalid", allow replace token without deleting profile

## AgentApi Interface

```kotlin
interface AgentApi {
    val connectionSnapshot: Flow<AgentConnectionSnapshot>
    val events: Flow<AgentEvent>

    suspend fun startSession(deck: String?, mode: String, config: SessionStartConfig?): ApiResult<SessionStarted>
    suspend fun submitAnswer(cardId: String, text: String, reviewTurnId: String?, sessionRevision: Long?): ApiResult<Evaluation>
    suspend fun rateCard(cardId: String, rating: Rating, reviewTurnId: String?, sessionRevision: Long?): ApiResult<RatingSaved>

    suspend fun getDashboard(): ApiResult<DashboardSnapshot>
    suspend fun getDecks(): ApiResult<List<DeckSummary>>
    suspend fun getStudyConfig(): ApiResult<StudyControlConfig>
    suspend fun updateStudyConfig(config: StudyControlConfig): ApiResult<StudyConfigUpdated>
}
```

## ApiResult

```kotlin
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Rejected(val error: AgentApiError) : ApiResult<Nothing>
    data class Timeout(val requestType: String, val elapsedMs: Long) : ApiResult<Nothing>
    data class Disconnected(val reason: String?) : ApiResult<Nothing>
    data class ProtocolFailure(val reason: String) : ApiResult<Nothing>
}
```

## Structured Errors

```json
{
  "type": "error",
  "code": "ANKI_NOT_RUNNING",
  "category": "dependency",
  "retryable": true,
  "message": "AnkiConnect could not be reached.",
  "in_reply_to": "request-id"
}
```

Stable codes:

```
AUTH_REQUIRED, AUTH_INVALID, PROTOCOL_UNSUPPORTED, CAPABILITY_UNSUPPORTED,
ANKI_NOT_RUNNING, ANKICONNECT_UNAVAILABLE, DECK_NOT_FOUND, CARD_NOT_FOUND,
LLM_UNAVAILABLE, LLM_TIMEOUT, SESSION_NOT_FOUND, SESSION_ALREADY_ACTIVE,
SESSION_CONFLICT, STALE_SESSION_REVISION, STALE_REVIEW_TURN, RATE_LIMITED,
INVALID_REQUEST, INTERNAL_ERROR
```

UI maps code to user text, not English parsing.

## In-Reply-To Correlation

- Do NOT rely on server response message_id == request message_id
- Use in_reply_to explicitly (v2), accept legacy echo for backward compat
- RequestTracker centralizes requestId, expected type, timeout, result, cancellation, bounded map

## Timeouts

Purpose-specific, not one global:

```
handshake: 8s
auth: 5s
dashboard: 8s
config: 5s
session action: 5s
evaluation (LLM): 30s
```

## Idempotency

- message_id as idempotency key
- Server remembers recently processed mutation IDs
- Duplicate -> return equivalent ACK, do NOT execute twice
- Critical for rating (must never apply twice) and answer submission

## Turn Identity & Session Revision

- review_turn_id required on v2 mutations: submit_answer, rate_card, hint, explanation, show_answer, skip
- session_revision monotonic, stale rejected with STALE_SESSION_REVISION
- Together with session_id, card_id protect repeated card appearances

## Session Recovery

On reconnect:

```
handshake -> auth -> capability negotiation -> request_session_snapshot -> reconcile -> resume
```

Server snapshot authoritative: session ID, revision, paused, finished, current card, review turn, question, awaiting state, suggested rating, progress.

Capability session_recovery advertises support, else v1 fallback.

Do NOT restart voice from stale Android memory.

## Heartbeat

- App protocol manages ping, OkHttp ping disabled (choose ONE owner)
- Track last ping sent, last pong received, unanswered pings, last server message
- If no pong/server traffic within liveness window, consider unhealthy and reconnect
- Ping correlation via in_reply_to:

```json
{"type":"ping","message_id":"...","sent_at":"..."}
{"type":"pong","in_reply_to":"..."}
```

## Network Awareness

- Integrate Android connectivity awareness
- Distinguish No network vs PC Agent unreachable
- Do NOT burn reconnect attempts while no usable network
- When network returns, reconnect promptly, not waiting long backoff, but avoid storms
- Wi-Fi ↔ Cellular / VPN change invalidates transport, reconnect safely
- Exponential backoff with jitter, bounded, reset after stable success
- Manual Connect overrides backoff (Retry Now)

## Parallel Attempts & Generation

- Rapid Connect taps must not create several WebSockets
- Each attempt gets connectionGeneration, late callback from gen N must not alter gen N+1 (important for OkHttp callbacks)
- Profile switching: disconnect old, invalidate old callbacks, clear handshake state

## Profile Validation

- Host: IPv4, IPv6, hostname, Tailscale 100.x, MagicDNS
- Port 1..65535
- Path must start with /
- TLS flag
- Do NOT silently turn invalid host into localhost - UI should reject clearly before connecting
- toWebSocketUrl() may remain defensive internally, but UI validation explicit

## Connection Test

Tests:

```
DNS/IP reachability
WebSocket upgrade
Study Agent handshake
protocol compatibility
authentication
Anki, AI if capabilities
```

Result:

```
PC found ✓
Study Agent ✓
Protocol v2 ✓
Authentication ✓
Anki ✓
AI evaluator ✓
Latency 14 ms
```

Diagnose failure by stage, not generic "Connection failed".

## Security

- WS vs WSS: allow ws:// for local trusted LAN, label "Local unencrypted connection", do NOT recommend exposing ws:// to public internet, recommend Tailscale or WSS
- Reduce global cleartext exposure: audit usesCleartextTraffic=true, prefer Network Security Config allowing intended LAN traffic
- TLS: never trust all certificates, never disable hostname verification. For self-signed, use fingerprint pinning explicit
- agent_id persistent server ID, not hardware, allows recognizing same agent at new address
- No API keys in Android (LLM creds on PC only)
- No arbitrary Android command execution via protocol
- Server must validate client input
- Rate limiting for expensive actions
- Size limits for frames, questions, etc., but not breaking real content
- Backpressure: slow consumer must not cause unlimited growth, prioritize critical events (session_finished, rating_saved, evaluation, snapshot, errors)
- Do NOT use replay=1 blindly for command events - could process old evaluation/question from replay
- Log sanitization: never raw JSON frames, never auth tokens

## Observability

- DNS/resolve time, connect time, WebSocket upgrade time, handshake time, auth time, ready time, ping RTT, reconnect duration, request RTT by type
- Connection ready latency: Connect tapped -> Agent Ready (separate from socket opened)
- API request latency by category: dashboard, decks, config, session start, evaluation, rating (don't mix LLM latency with local)

## Forward Compatibility

- ignoreUnknownKeys = true for optional future fields, but malformed required fields must not be silently accepted
- Unknown message types via envelope-first parser -> UnknownAgentMessage, not connection loss
- Keep unknown capabilities preserved, ignored safely
