# Study Agent Protocol Specification

## 1. Overview

The Study Agent Protocol operates over WebSocket connections (WS or WSS). It uses UTF-8 encoded JSON messages.

### Envelope - Every Modern Message

```
type: string (required) - discriminator
protocol_version: string (required) - negotiated version
message_id: string (required) - UUID, idempotency key
timestamp: string (required) - ISO-8601 UTC
session_id: string? (optional) - active session, null for hello/auth
in_reply_to: string? (optional) - correlation for request/response (v2)
session_revision: long? (optional) - monotonic server revision
review_turn_id: string? (optional) - turn identity for exactly-once
```

Fields marked required/optional/conditional. New implementations should use in_reply_to explicitly; legacy servers may echo client's message_id.

Size limits (protect against huge frames, but allow real content):

```
individual frame: 2MB max
question: 10KB
answer transcript: 20KB
feedback: 20KB
explanation: 20KB
dashboard snapshot: 1MB
```

## 2. Connection Lifecycle (Separate Transport from Agent Ready)

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
AUTHENTICATING (if required)
  │
  ▼
NEGOTIATING_CAPABILITIES (welcome/capabilities)
  │
  ▼
READY (agent verified, protocol negotiated, authenticated, capabilities known)
  or READY_LEGACY (v1 fallback - basic study works, management gated)
```

Failure branches:

```
NETWORK_UNAVAILABLE
SERVER_UNREACHABLE (DNS failure, connection refused, timeout)
TLS_FAILED
AUTHENTICATION_FAILED
INCOMPATIBLE_PROTOCOL
HANDSHAKE_TIMEOUT (WebSocket open but no Study Agent handshake)
AGENT_UNAVAILABLE
```

Reconnect:

```
READY -> connection lost -> RECONNECTING (exponential backoff + jitter) -> HANDSHAKING -> RECONCILING SESSION (request_session_snapshot) -> READY
```

Never collapse all into Connected=true. Transport connectivity and agent readiness are distinct.

## 3. Handshake

### v2 Recommended

Client sends hello immediately after WebSocket open:

```json
{
  "type": "hello",
  "protocol_version": "2",
  "message_id": "8a32a688-21d4-47d3-8bf6-a4f6bebfcb97",
  "timestamp": "2026-09-18T00:30:00.000Z",
  "client_name": "StudyAgent-Android",
  "client_version": "1.0.0",
  "platform": "android",
  "android_api": 34,
  "supported_versions": ["1", "2"],
  "client_capabilities": ["dashboard", "study_control", "session_recovery"]
}
```

- client_version comes from BuildConfig.VERSION_NAME, not hard-coded
- platform, android_api optional, no IMEI/Android ID/device serial

Server MUST respond with welcome:

```json
{
  "type": "welcome",
  "protocol_version": "2",
  "message_id": "server-uuid",
  "in_reply_to": "<hello message_id>",
  "timestamp": "2026-09-18T00:30:00.001Z",
  "server_name": "StudyPC-Agent",
  "server_version": "2.3.0",
  "selected_protocol": "2",
  "capabilities": ["dashboard", "deck_list", "study_config", "session_recovery"],
  "authentication": {
    "required": true,
    "authenticated": true,
    "methods": ["bearer"]
  },
  "agent_id": "persistent-server-id-uuid"
}
```

- Server chooses ONE protocol from supported_versions (highest mutual)
- All subsequent messages use negotiated version
- agent_id is persistent, allows recognizing same agent at new address without hardware IDs
- Do NOT put secrets in welcome or mDNS

Legacy v1 fallback: if server never sends welcome/capabilities, after timeout client may treat as v1 if it received any valid Study Agent response (pong, session_started). Then ReadyLegacy, capabilities unknown.

Handshake timeout: if transport opens but no welcome/capabilities within 10s, report "Server found, but no Study Agent handshake was received" - do NOT report Ready if wrong service on port.

### Detect Wrong Service

If user enters 192.168.1.5:3000 which is unrelated WebSocket server, do NOT report Connected merely because upgrade succeeded. Report handshake failure.

## 4. Authentication

### Preferred: Bearer header (modern)

```
GET /ws HTTP/1.1
Authorization: Bearer <long-lived-token>
```

Server accepts upgrade or 401/403. One clear path.

### Legacy: authenticate frame

```json
{
  "type": "authenticate",
  "protocol_version": "2",
  "message_id": "uuid",
  "timestamp": "...",
  "token": "sec_tok_..."
}
```

Keep only for legacy agents, capability/protocol decides if needed. Do NOT send both by default.

Auth result explicit: UI knows "socket open but auth rejected" without waiting.

Tokens remain in SecureTokenStorage (EncryptedSharedPreferences), not in ServerProfile JSON. Never log tokens. Support rotation: if server rejects previously valid token, show "Authentication expired or invalid", allow replace without deleting profile.

## 5. Protocol Negotiation

- Client advertises supported_versions = ["1","2"]
- Server selects selected_protocol = "2" (highest mutual)
- Then all new messages use negotiated version
- Stop defaulting every message to protocol 1 because Kotlin model default says so - use ProtocolContext/message factory

Client version from BuildConfig.VERSION_NAME.

## 6. Request/Response Table

| Client request | Server response | Push? | Mutating? | Idempotent | Timeout |
|---|---|---|---|---|---|
| hello | welcome | No | No | Yes | 8s |
| ping | pong | No | No | Yes | 5s |
| authenticate | (none or error) | No | No | Yes | 5s |
| start_session | session_started | No | Yes | Yes | 5s |
| submit_answer | evaluation | No | Yes | Yes | 30s |
| rate_card | rating_saved | No | Yes | Yes | 5s |
| repeat_question | question | No | No | Yes | 5s |
| request_hint | hint | No | No | Yes | 5s |
| request_explanation | explanation | No | No | Yes | 5s |
| request_answer | answer | No | No | Yes | 5s |
| skip_card | question / session_finished | No | Yes | Yes | 5s |
| pause_session | session_paused | No | Yes | Yes | 5s |
| resume_session | session_resumed | No | Yes | Yes | 5s |
| end_session | session_finished | No | Yes | Yes | 5s |
| request_session_status | session_stats + session_snapshot | No | No | Yes | 5s |
| request_session_snapshot | session_snapshot | No | No | Yes | 5s |
| request_dashboard | dashboard_snapshot | No | No | Yes | 8s |
| request_decks | deck_list | No | No | Yes | 5s |
| request_component_health | component_health | No | No | Yes | 5s |
| request_study_config | study_config | No | No | Yes | 5s |
| update_study_config | study_config_updated | No | Yes | Yes | 5s |
| request_history | study_history | No | No | Yes | 8s |
| request_learning_insights | learning_insight | No | No | Yes | 8s |
| request_ai_usage | ai_usage_stats | No | No | Yes | 8s |

Push events (server-initiated):

```
question
evaluation (may also be response)
hint, explanation, answer
session_progress (current_card_index, total_cards)
session_stats (cards_studied, recall_rate, remaining_due)
session_finished (with optional details)
study_config_updated (unsolicited server change)
component_health (periodic)
```

## 7. In-Reply-To Correlation

v2 uses explicit:

```json
{
  "type": "dashboard_snapshot",
  "message_id": "server-uuid",
  "in_reply_to": "client-request-uuid",
  "snapshot": {...}
}
```

v1 compat: server echoes client's message_id as its own message_id. New implementations should use in_reply_to explicitly.

RequestTracker centralizes: requestId, expected type, timeout, result, cancellation, bounded map (completed/timed-out removed, no growth over multi-hour sessions).

Timeouts purpose-specific (handshake, auth, dashboard, config, session action, LLM evaluation). Evaluation is longer than deck_list.

Cancellation: if caller cancels Dashboard refresh, remove tracking, but cancelling local waiting does not cancel server work unless protocol supports cancellation.

## 8. Idempotency

message_id as idempotency identifier. Server remembers recently processed mutation IDs (e.g. 200, TTL 1h). If duplicate arrives, do NOT execute twice, return equivalent ACK/result.

Critical:

```
start_session, submit_answer, rate_card, end_session, update_study_config
```

Rating must never apply twice:

```
RateCard X sent -> network disappears -> Android does not receive ACK -> reconnect -> RateCard X resent (same message_id) -> Anki scheduling must change exactly once
```

Same for answer submission - cache/replay result for same request ID.

Keep review_turn_id, session_id, card_id, session_revision together for protection.

Require turn ID on v2 mutations: submit_answer, rate_card, hint, explanation, show_answer, skip should carry review_turn_id.

## 9. Session Revision & Recovery

Monotonically increasing server revision:

```
revision 120 -> rating accepted -> revision 121
```

Stale operations rejected with STALE_SESSION_REVISION.

Recovery after reconnect:

```
handshake -> auth -> capability negotiation -> request_session_snapshot -> reconcile -> resume
```

Do NOT restart voice from stale Android memory.

Capability session_recovery advertises support, else v1 fallback.

Server snapshot authoritative:

```
session ID, revision, paused, finished, current card, review turn, question, awaiting answer/evaluation/rating, suggested rating, progress
```

## 10. Heartbeat Hardening

Current ping loop measures latency. Enhance dead-connection detection. Track last ping sent, last pong received, unanswered pings, last server message. If no pong/server traffic within liveness window (30s), consider unhealthy and reconnect.

Do NOT create two heartbeat loops - disable OkHttp ping if app protocol manages its own ping. Choose ONE owner, document it.

Ping correlation:

```json
{"type":"ping","message_id":"...","sent_at":"..."}
{"type":"pong","in_reply_to":"..."}
```

So multiple delayed pings cannot confuse latency measurement.

## 11. Network Awareness

- Integrate Android connectivity awareness (ConnectivityManager)
- Distinguish No network interface from Network exists but PC Agent unreachable
- Do NOT burn reconnect attempts while no usable network
- When network returns, reconnect promptly, not waiting long backoff, but avoid storms
- Wi-Fi ↔ Cellular / VPN change invalidates transport, reconnect safely
- Keep exponential backoff + jitter, bounded, reset after stable success
- Manual Connect overrides backoff (Retry Now)
- Prevent parallel connect attempts via generation/identity - late callback from gen N must not alter gen N+1 (OkHttp callbacks)
- Profile switching: disconnect old, invalidate old callbacks, clear handshake state

## 12. Client Messages (v1 + v2)

### 2.1 hello (enhanced)

```json
{
  "protocol_version": "2",
  "message_id": "uuid",
  "type": "hello",
  "timestamp": "...",
  "client_name": "StudyAgent-Android",
  "client_version": "1.0.0",
  "platform": "android",
  "android_api": 34,
  "supported_versions": ["1","2"],
  "client_capabilities": ["dashboard","study_control","session_recovery"]
}
```

### 2.2 authenticate (legacy, keep for compat)

```json
{
  "protocol_version": "2",
  "message_id": "uuid",
  "type": "authenticate",
  "token": "sec_tok_...",
  "timestamp": "..."
}
```

### 2.3 start_session (with optional config v2)

```json
{
  "protocol_version": "2",
  "message_id": "uuid",
  "type": "start_session",
  "deck": "Toronto Notes",
  "mode": "review_due",
  "config": {...},
  "timestamp": "..."
}
```

### 2.4 submit_answer (with turn identity)

```json
{
  "protocol_version": "2",
  "message_id": "uuid",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "type": "submit_answer",
  "text": "Volume greater than thirty...",
  "review_turn_id": "sess-4a81:card-001:42",
  "session_revision": 42,
  "timestamp": "..."
}
```

### 2.5 rate_card (with turn identity)

```json
{
  "protocol_version": "2",
  "message_id": "uuid",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "type": "rate_card",
  "rating": "hard",
  "review_turn_id": "sess-4a81:card-001:43",
  "session_revision": 43,
  "timestamp": "..."
}
```

Other: repeat_question, request_hint, request_explanation, request_answer, skip_card, pause_session, resume_session, end_session, ping (with sent_at for correlation).

## 13. Server Messages

### 3.1 welcome (new v2)

```json
{
  "type": "welcome",
  "protocol_version": "2",
  "message_id": "...",
  "in_reply_to": "<hello message id>",
  "server_name": "StudyPC-Agent",
  "server_version": "2.3.0",
  "selected_protocol": "2",
  "capabilities": ["dashboard","deck_list","study_config","session_recovery"],
  "authentication": {"required": true, "authenticated": true, "methods": ["bearer"]},
  "agent_id": "persistent-id"
}
```

### 3.2 session_started

```json
{
  "protocol_version": "2",
  "type": "session_started",
  "session_id": "sess-4a81",
  "deck": "Toronto Notes",
  "total_cards": 183,
  "session_revision": 1,
  "timestamp": "...",
  "in_reply_to": "request-id"
}
```

### 3.3 question

```json
{
  "protocol_version": "2",
  "type": "question",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "question": "What are indications for evacuation of epidural hematoma?",
  "card_number": 1,
  "remaining": 182,
  "speak": true,
  "review_turn_id": "sess-4a81:card-001:2",
  "session_revision": 2,
  "sequence": 1,
  "timestamp": "..."
}
```

### 3.4 evaluation

```json
{
  "protocol_version": "2",
  "type": "evaluation",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "score": 82,
  "correct_points": ["Volume > 30 mL"],
  "missing_points": ["Neurological deterioration"],
  "short_feedback": "Good answer...",
  "suggested_rating": "hard",
  "review_turn_id": "sess-4a81:card-001:2",
  "session_revision": 3,
  "in_reply_to": "submit_answer id",
  "timestamp": "..."
}
```

### 3.5 rating_saved

```json
{
  "protocol_version": "2",
  "type": "rating_saved",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "rating": "hard",
  "next_interval": "1 day",
  "review_turn_id": "sess-4a81:card-001:3",
  "session_revision": 4,
  "in_reply_to": "rate_card id",
  "timestamp": "..."
}
```

### 3.6 error (with stable codes)

```json
{
  "protocol_version": "2",
  "type": "error",
  "code": "ANKI_NOT_RUNNING",
  "category": "dependency",
  "retryable": true,
  "message": "AnkiConnect could not be reached.",
  "details": "Connection refused",
  "in_reply_to": "request-id",
  "session_revision": 42
}
```

Stable codes:

```
AUTH_REQUIRED, AUTH_INVALID, AUTH_EXPIRED,
PROTOCOL_UNSUPPORTED, CAPABILITY_UNSUPPORTED, INCOMPATIBLE_VERSION,
ANKI_NOT_RUNNING, ANKICONNECT_UNAVAILABLE, DECK_NOT_FOUND, CARD_NOT_FOUND,
LLM_UNAVAILABLE, LLM_TIMEOUT,
SESSION_NOT_FOUND, SESSION_ALREADY_ACTIVE, SESSION_CONFLICT,
STALE_SESSION_REVISION, STALE_REVIEW_TURN,
RATE_LIMITED, CONFIG_REJECTED,
INVALID_REQUEST, INTERNAL_ERROR
```

### 3.7 pong (correlated)

```json
{
  "type": "pong",
  "message_id": "...",
  "in_reply_to": "<ping message_id>",
  "timestamp": "..."
}
```

## 14. Protocol v2 Management (Capability-Gated)

Client hello with supported_versions [1,2] and client_capabilities. Server answers with welcome + capabilities frame; v1 server stays silent, client falls back to v1.

Capabilities frame:

```json
{
  "protocol_version": "2",
  "type": "capabilities",
  "capabilities": ["dashboard","deck_list","study_config","history","component_health","learning_insights","ai_usage","session_progress"],
  "server_name": "StudyPC-Agent",
  "server_version": "2.1",
  "agent_id": "persistent-id"
}
```

Capability names plain strings, unknown preserved and ignored.

Management requests (all carry message_id, server SHOULD echo via in_reply_to):

| type | capability | response |
|---|---|---|
| request_dashboard | dashboard | dashboard_snapshot |
| request_decks | deck_list | deck_list |
| request_component_health | component_health | component_health |
| request_study_config | study_config | study_config |
| update_study_config | study_config | study_config_updated (ACK) or error |
| request_history (range) | history | study_history |
| request_learning_insights | learning_insights | learning_insight |
| request_ai_usage (range) | ai_usage | ai_usage_stats |
| request_session_snapshot | session_recovery | session_snapshot |

See docs/AGENT_API.md for full API layer.

## 15. Session Recovery

Authoritative snapshot for recovery:

```json
{
  "type": "session_snapshot",
  "session_id": "sess-123",
  "exists": true,
  "is_paused": false,
  "is_finished": false,
  "current_card_id": "card-001",
  "current_question": "What are indications...?",
  "card_number": 5,
  "remaining": 10,
  "review_turn_id": "sess-123:card-001:42",
  "session_revision": 42,
  "awaiting": "answer",
  "suggested_rating": "good",
  "remaining_due": 10,
  "cards_studied": 4,
  "in_reply_to": "request-id"
}
```

On reconnect: handshake -> auth -> capability negotiation -> request_session_snapshot -> reconcile -> resume (repeat current question, not mid-sentence).

## 16. Compatibility Policy

- v1: basic study (connect, study, answer, feedback, rating) always works
- v2: management + recovery + strong turn identity
- Future versions must negotiate before use
- Do NOT break v1
- Legacy Ready mode: for v1 servers without welcome, bounded legacy handshake (hello + ping -> recognized response) then ReadyLegacy, capabilities unknown/limited

## 17. Forward Compatibility

- ignoreUnknownKeys = true for optional future fields, but malformed required fields must not be silently accepted
- Unknown server message types via envelope-first parser -> Unknown message, not connection loss
- Preserve unknown capabilities

## 18. Security Considerations

- Auth via Bearer header preferred
- No tokens in logs, diagnostics, profile JSON
- TLS: never trust all certs, never disable hostname verification. Self-signed via fingerprint pinning explicit
- Cleartext: allow ws:// for LAN if product chooses, label "Local unencrypted connection", don't recommend public ws:// port forwarding
- Size limits, backpressure, prioritize critical events
- No API keys in Android (LLM creds on PC only)
- No arbitrary command execution
- Server validates client input

## 19. Observability

- DNS/resolve, connect, WebSocket upgrade, handshake, auth, ready time
- Ping RTT, reconnect duration, request RTT by type
- Connection ready latency: Connect tapped -> Agent Ready (separate from socket opened)
- API request latency by category

## 20. Reference Implementation

See server/mock_pc_agent.py with --chaos, --v1, --partial modes, and FakeAgentConnection.

Golden fixtures in protocol-fixtures/ (hello, welcome, question, evaluation, rating, error, dashboard, config, snapshot).

## 21. Remote TTS Subprotocol (v2 agent capability `tts`)

Agents that offer remote (cloud) speech advertise `tts`, `tts:streaming`,
`tts:voice_catalog` plus one `tts:<provider>` entry per configured provider
(openai, elevenlabs) in the `welcome.capabilities` list. Android treats these
as OPTIONAL: an agent without them is a study-only agent.

Two planes, one credential:

- **Control plane** — the existing WebSocket JSON protocol. All requests are
  correlated with `in_reply_to` like every other v2 message.
- **Media plane** — plain authenticated HTTP streaming of raw PCM
  (`GET /v1/tts/stream/{stream_id}`). It carries NO request/response
  semantics of its own: a stream is always created by a prior
  `tts_synthesize` reply.

### 21.1 Messages

| Client request | Server response | Mutating? | Notes |
|---|---|---|---|
| tts_capabilities_request | tts_capabilities | No | Per-provider capability/health blocks + media plane descriptor |
| tts_voices_request | tts_voices | No | Namespaced catalog (`openai:coral`, `elevenlabs:<id>`); unknown provider → `error INVALID_REQUEST` |
| tts_synthesize | tts_stream \| error | Yes (paid) | Eager synthesis; failures surface as typed WS errors, never a doomed HTTP stream |
| tts_cancel | tts_cancelled | Yes | Cancel by `stream_id` or by `speech_request_id`; `cancelled` = number cancelled |
| tts_test_provider | tts_provider_test | No | Cheap probe: configured / authentication / reachable / voice_list + latency_ms |

`welcome`/`tts_*` while unauthenticated (auth required) → `error` with code
`AUTH_REQUIRED`.

`media` block in `tts_capabilities`:

```json
{"format": "pcm_s16le", "sample_rate": 24000, "channels": 1,
 "auth": "bearer", "auth_required": true, "port": 8766}
```

`auth` names the scheme (always `bearer`); `auth_required` says whether the
agent actually enforces it. The media plane is served by the same host as the
agent's WebSocket connection — only the port differs.

`stream_path` in `tts_synthesize` is a PATH only (`/v1/tts/stream/st_...`);
Android builds the URL from the agent host + `media.port`.

### 21.2 Media plane

```
GET /v1/tts/stream/{stream_id}
Authorization: Bearer <Study Agent credential>   (only when auth_required)

200 → audio/pcm; codecs=pcm_s16le, rate=24000, channels=1
      headers: X-Stream-Id, X-Speech-Request-Id, X-Cache-Hit (0|1)
401 → missing / wrong credential (vague body, never echoes the header)
404 → unknown stream
410 → expired, cancelled, or already consumed (streams are SINGLE-USE, short-lived)
```

Canonical format: PCM s16le, mono, 24 kHz — the agent normalizes provider
output; Android does no decoding.

### 21.3 Stable provider error codes

`error.code` values (stable contract, mirrored by Android's `SpeechErrorCode`):

```
PROVIDER_UNAVAILABLE      PROVIDER_NOT_CONFIGURED   PROVIDER_AUTH_FAILED
PROVIDER_RATE_LIMITED     PROVIDER_QUOTA_EXCEEDED   VOICE_NOT_FOUND
MODEL_UNAVAILABLE         SYNTHESIS_FAILED          STREAM_FAILED
STREAM_TIMEOUT            STREAM_EXPIRED            STREAM_NOT_FOUND
STREAM_NOT_AUTHENTICATED  INVALID_REQUEST           TEXT_TOO_LONG
```

`PROVIDER_RATE_LIMITED` carries `retryable: true`; rate limits do NOT change
provider health (they are transient, per-request).

### 21.4 Credential hygiene

Provider keys (OpenAI / ElevenLabs) live ONLY on the PC running the agent.
They never appear in protocol frames, logs, URLs, or diagnostics — the agent
sends capabilities and error codes only. The single credential Android
ever holds (the Study Agent credential) travels in a Bearer header,
never in the URL.

Contract tests: `server/test_tts_contract.py` (in-process, hermetic — the CI
gate for this subprotocol).

## 22. Version History

- v1: basic study
- v2: welcome handshake, selected_protocol, in_reply_to correlation, session_recovery, management surface, component health, idempotency, turn identity, agent_id
- v2 + `tts` capability: remote TTS subprotocol (section 21)
