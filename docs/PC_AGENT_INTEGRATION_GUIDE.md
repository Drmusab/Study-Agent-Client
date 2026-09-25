# PC Agent Integration Guide

Audience: Developer building a compatible Study Agent PC server.

## 1. Overview

```
Android (StudyAgent-Android)
  │
  │ LAN / Tailscale / WSS
  │ WebSocket JSON
  ▼
PC Study Agent (you build this)
  │
  ├── AnkiConnect localhost (127.0.0.1:8765)
  └── LLM provider / local model (Ollama, OpenAI-compatible, etc)
```

Android is thin client: voice I/O, session UI, dashboard rendering. PC is source of truth: scheduling, evaluation, storage.

## 2. WebSocket Endpoint

- Default: `ws://0.0.0.0:8765/ws` or `wss://...`
- Must support WebSocket upgrade with optional `Authorization: Bearer <token>` header
- Path configurable, default `/ws`
- One endpoint for all protocol versions

## 3. Handshake (v2 Recommended)

### Client sends hello immediately after WebSocket open

```json
{
  "type": "hello",
  "protocol_version": "2",
  "message_id": "uuid",
  "timestamp": "2026-09-18T00:00:00.000Z",
  "client_name": "StudyAgent-Android",
  "client_version": "1.0.0",
  "platform": "android",
  "android_api": 34,
  "supported_versions": ["1", "2"],
  "client_capabilities": ["dashboard", "study_control", "session_recovery"]
}
```

### Server MUST respond with welcome (v2) or capabilities (v1 compat)

Welcome (new in v2):

```json
{
  "type": "welcome",
  "protocol_version": "2",
  "message_id": "server-uuid",
  "in_reply_to": "<hello message_id>",
  "timestamp": "2026-09-18T00:00:00.001Z",
  "server_name": "StudyPC-Agent",
  "server_version": "2.3.0",
  "selected_protocol": "2",
  "capabilities": ["dashboard", "deck_list", "study_config", "session_recovery"],
  "authentication": {
    "required": true,
    "authenticated": true,
    "methods": ["bearer"]
  },
  "agent_id": "persistent-uuid-for-this-pc-agent"
}
```

Rules:
- Server chooses ONE protocol from client's supported_versions (highest mutually supported)
- All subsequent messages use negotiated version
- agent_id is persistent server ID, not hardware ID, allows Android to recognize same agent at new address
- capabilities are plain strings, unknown ones ignored by client
- Do NOT put secrets in welcome

Legacy v1 fallback:
- If server is v1, it may not send welcome, but send capabilities or just start responding to study messages
- Client falls back to ReadyLegacy after handshake timeout (4s) if it received any valid Study Agent response

Handshake timeout handling:
- If transport opens but no welcome/capabilities within 10s, client reports "Server found, but no Study Agent handshake received"
- Do NOT report Ready if wrong service on port

## 4. Authentication

### Modern (Preferred): Bearer header

```
WebSocket upgrade request:
GET /ws HTTP/1.1
Authorization: Bearer <long-lived-token>
```

Server either accepts upgrade or returns 401/403. No second auth needed.

### Legacy: authenticate frame

For backward compat, if server cannot read header or requires legacy:

```json
{
  "type": "authenticate",
  "protocol_version": "2",
  "message_id": "uuid",
  "token": "sec_tok_..."
}
```

Server should indicate in welcome.authentication whether auth required and whether Bearer was accepted.

Policy:
- Do NOT send both Bearer header AND authenticate frame by default
- Capability or protocol negotiation decides if legacy frame needed
- If server rejects previously valid token, return error code AUTH_INVALID or AUTH_EXPIRED, not generic error
- Allow token rotation: user can replace token without recreating profile

### Token Storage

- Android stores token in EncryptedSharedPreferences (Keystore), not in profile JSON
- Never log tokens, never include in diagnostics export
- Test for leakage: HTTP headers, authenticate frame, profile export, diagnostics export, exceptions

## 5. Protocol Versions & Capability Negotiation

- Client advertises supported_versions = ["1","2"]
- Server selects highest mutually supported, returns selected_protocol in welcome
- Then client uses that version for all messages

Capabilities (v2):
- Simple strings: dashboard, deck_list, study_config, history, component_health, learning_insights, ai_usage, session_progress, session_recovery
- For evolving features: study_config:v1 or structured versioning if needed
- Unknown capabilities preserved, ignored safely
- Client must gate every management feature on advertised set

## 6. Message Envelope

Every modern message has:

```
type: string (required) - discriminator
protocol_version: string (required) - negotiated version
message_id: string (required) - UUID, idempotency key
timestamp: string (required) - ISO-8601 UTC
session_id: string? (optional) - active session, null for hello/auth
in_reply_to: string? (optional) - correlation, new in v2
session_revision: long? (optional) - monotonic server revision
review_turn_id: string? (optional) - turn correlation/stale guard, not a durable commit key
```

## 7. Request/Response Table

| Client request | Server response | Push? | Mutating? | Idempotent | Timeout |
|---|---|---|---|---|---|
| hello | welcome | No | No | Yes | 8s |
| ping | pong | No | No | Yes | 5s |
| authenticate | (none or error) | No | No | Yes | 5s |
| start_session | session_started | No | Yes | Yes | 5s |
| submit_answer | evaluation | No | Yes | Yes | 30s (LLM) |
| rate_card | rating_saved | No | Yes | Not established in the mock; needs durable logical-ID dedup | 5s |
| repeat_question | question | No | No | Yes | 5s |
| request_hint | hint | No | No | Yes | 5s |
| request_explanation | explanation | No | No | Yes | 5s |
| request_answer | answer | No | No | Yes | 5s |
| skip_card | question or session_finished | No | Yes | Yes | 5s |
| pause_session | session_paused | No | Yes | Yes | 5s |
| resume_session | session_resumed | No | Yes | Yes | 5s |
| end_session | session_finished | No | Yes | Yes | 5s |
| request_session_snapshot | session_snapshot | No | No | Yes | 5s |
| request_dashboard | dashboard_snapshot | No | No | Yes | 8s |
| request_decks | deck_list | No | No | Yes | 5s |
| request_component_health | component_health | No | No | Yes | 5s |
| request_study_config | study_config | No | No | Yes | 5s |
| update_study_config | study_config_updated | No | Yes | Yes | 5s |
| request_history | study_history | No | No | Yes | 8s |
| request_learning_insights | learning_insight | No | No | Yes | 8s |
| request_ai_usage | ai_usage_stats | No | No | Yes | 8s |

Push events (server-initiated, not in reply to request):

```
question
evaluation (may also be response)
session_progress
session_stats
session_finished
study_config_updated (unsolicited)
component_health (periodic)
```

## 8. In-Reply-To Correlation

v2 uses explicit correlation:

```json
{
  "type": "dashboard_snapshot",
  "message_id": "server-uuid",
  "in_reply_to": "client-request-uuid",
  "snapshot": {...}
}
```

v1 compatibility: server echoes client's message_id as its own message_id. New implementations should use in_reply_to explicitly, but accept legacy echo.

Client's RequestCoordinator tracks request messageId, expected response type, timeout, result, cancellation. Completed/timed-out removed to prevent leak.

## 9. Transport cache vs durable logical rating commit (GATE 11 correction)

`message_id` identifies one **transport delivery**. Reusing that ID may hit a bounded, in-memory response cache, but it does not prove the backend scheduler effect was durably deduplicated. The supplied `server/mock_pc_agent.py` remembers at most 200 message IDs per in-memory session. A new message ID for the same turn, cache eviction, or server restart bypasses that cache. `review_turn_id` and `session_revision` guard stale context, not backend mutation identity. This mock has **no** server-side durable logical `ReviewCommitId` table, atomic effect/receipt write, or read-only status lookup. Do not present it as end-to-end exactly-once.

For a *future* PC Agent implementation to advertise `IDEMPOTENT_REPLAY_SUPPORTED`, the protocol must explicitly carry a stable logical commit ID independent of the WebSocket message ID; server persistence must atomically bind `(backend/collection, logical ID, immutable card+rating payload, result/receipt)` to one scheduler effect, survive restarts for the advertised lifetime, reject changed payloads, and provide a read-only query of that same ID. Replay of a proven identical ID must return the saved result without a second scheduler mutation. Demonstrate with tests using **different** message IDs, concurrent delivery, response loss, cache eviction and server restart. End-to-end exactly-once additionally requires a proven progress guarantee across documented failure windows; this repo makes no such claim.

Until that contract exists, the Android machine fails closed on an unacknowledged PC rating: it keeps the pending delivery, does not offer blind retry/skip or accept next-card/snapshot state as commit proof, and may progress only on a live, matching `rating_saved` ACK correlated to the original message ID and turn. A process death still loses PC-specific in-memory turn state; PC is **not** included in the Anki-local durable ledger. An older direct repository API remains outside this machine guarantee. See ADR [0008](adr/0008-durable-review-attempts-and-exactly-once-limits.md).

## 10. Turn Identity & Session Revision

Continue using:

```
session_id
review_turn_id
card_id
session_revision
```

- review_turn_id: unique per card appearance, e.g. "sessionId:cardId:revision"
- session_revision: monotonically increasing server counter, increments on every state change

Stale operations rejected with STALE_SESSION_REVISION or STALE_REVIEW_TURN.

For v2, mutations should carry review_turn_id:

```
submit_answer
rate_card
request_hint
request_explanation
request_answer
skip_card
```

## 11. Session Recovery After Reconnect

On reconnect:

```
handshake (hello -> welcome)
↓
authentication (Bearer header, or legacy frame if needed)
↓
capability negotiation (capabilities frame)
↓
request_session_snapshot
↓
reconcile (compare server snapshot with local)
↓
resume (repeat current question, not mid-sentence)
```

Server snapshot authoritative:

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
  "awaiting": "answer", // answer | rating | evaluation | none
  "suggested_rating": "good",
  "remaining_due": 10,
  "cards_studied": 4
}
```

Advertise capability session_recovery if you support this.

## 12. Minimum Compliant PC Agent

Must implement:

```
WebSocket endpoint
hello handling -> welcome
ping/pong
start_session -> session_started -> question
submit_answer -> evaluation
rate_card -> rating_saved -> question or session_finished
pause/resume/end
error with stable codes
```

Everything else capability-gated.

## 13. Error Model

Stable codes, never parse English strings:

```
AUTH_REQUIRED
AUTH_INVALID
AUTH_EXPIRED

PROTOCOL_UNSUPPORTED
CAPABILITY_UNSUPPORTED
INCOMPATIBLE_VERSION

ANKI_NOT_RUNNING
ANKICONNECT_UNAVAILABLE
DECK_NOT_FOUND
CARD_NOT_FOUND

LLM_UNAVAILABLE
LLM_TIMEOUT

SESSION_NOT_FOUND
SESSION_ALREADY_ACTIVE
SESSION_CONFLICT
STALE_SESSION_REVISION
STALE_REVIEW_TURN

RATE_LIMITED
CONFIG_REJECTED

INVALID_REQUEST
INTERNAL_ERROR
```

Wire format:

```json
{
  "type": "error",
  "protocol_version": "2",
  "message_id": "server-uuid",
  "in_reply_to": "request-id",
  "code": "ANKI_NOT_RUNNING",
  "category": "dependency",
  "retryable": true,
  "message": "AnkiConnect could not be reached.",
  "details": "Connection refused 127.0.0.1:8765",
  "session_revision": 42
}
```

Android maps codes to user messages:

```
ANKI_NOT_RUNNING -> "Open Anki on your PC and make sure AnkiConnect is enabled."
```

Wire stays language-neutral.

## 14. Heartbeat

- Client sends ping every configurable interval (default 15s)
- Prefer ping with message_id and sent_at, pong with in_reply_to for correlation
- Track last ping sent, last pong received, unanswered pings, last server message
- If no pong/server traffic within liveness window (30s), consider unhealthy and reconnect
- Only ONE heartbeat owner: app protocol manages ping, OkHttp ping disabled (0)

Ping correlation:

```json
{
  "type": "ping",
  "message_id": "uuid",
  "sent_at": "2026-09-18T00:00:00.000Z"
}
{
  "type": "pong",
  "in_reply_to": "ping-uuid",
  "timestamp": "..."
}
```

## 15. Size Limits & Backpressure

Define reasonable max sizes:

```
individual JSON frame: 2MB
question: 10KB
answer transcript: 20KB
feedback: 20KB
explanation: 20KB
dashboard snapshot: 1MB
```

Reject absurd payloads safely with error.

Backpressure: slow consumer must not cause unlimited growth. Use bounded SharedFlow, separate critical vs non-critical channels if needed. Critical events must not be lost because dashboard traffic filled buffer:

Critical:
- session_finished
- rating_saved
- evaluation
- session_snapshot
- errors

## 16. Security

- No API keys in Android - LLM credentials belong on PC Agent
- Do NOT allow PC Agent to execute arbitrary Android commands - protocol narrow and typed, no generic execute
- Server must validate client input: deck, card_id, turn_id, session_id, rating, limits, config, text length
- Rate limiting for expensive actions (LLM evaluation) - return RATE_LIMITED
- Tokens: secure storage, no logging, rotation support
- TLS: never trust all certificates, never disable hostname verification. For self-signed, use fingerprint pinning with explicit user consent
- Cleartext: allow ws:// for local LAN if product chooses, but UI should label "Local unencrypted connection". Do NOT recommend exposing ws:// directly to public internet. Prefer Tailscale or WSS.
- AndroidManifest: avoid global usesCleartextTraffic=true, prefer Network Security Config allowing intended local dev/LAN traffic

## 17. Agent Discovery (Optional, Next to Protocol)

- mDNS: PC Agent may advertise _studyagent._tcp with metadata: server name, port, TLS support, protocol version
- Do NOT put secrets in mDNS
- Manual IP always available, discovery optional
- Tailscale: support 100.x.x.x and MagicDNS hostname

## 18. Example Flows

### Basic Study v2

```
C -> S: hello (supported_versions [1,2])
S -> C: welcome (selected_protocol 2, capabilities [...], agent_id)
C -> S: start_session (deck="MCCQE::Cardiology", mode="review_due")
S -> C: session_started (session_id, total_cards, revision 1)
S -> C: question (card_id, question, review_turn_id, revision 2)
C -> S: submit_answer (card_id, text, review_turn_id, revision)
S -> C: evaluation (score, feedback, suggested_rating, in_reply_to)
C -> S: rate_card (card_id, rating=good, review_turn_id, revision)
S -> C: rating_saved (in_reply_to, revision)
S -> C: question (next card)
...
S -> C: session_finished
```

### Reconnect Recovery

```
C: connection lost
C -> S: WebSocket open (Bearer header)
C -> S: hello
S -> C: welcome (same agent_id as before)
C -> S: request_session_snapshot
S -> C: session_snapshot (exists=true, current_card_id, awaiting=answer, revision 42)
C: reconcile - same card as before, repeat question
```

### Dashboard

```
C -> S: request_dashboard (message_id X)
S -> C: dashboard_snapshot (message_id Y, in_reply_to X, snapshot={today, decks, goal...})
```

### Config Update with ACK

```
C -> S: update_study_config (message_id X, config={active_deck...})
S -> C: study_config_updated (message_id Y, in_reply_to X, config=committed) -> client commits
OR
S -> C: error (code=CONFIG_REJECTED, in_reply_to X) -> client rolls back, draft survives
```

## 19. Reference Implementation

See server/mock_pc_agent.py - enhanced to be both testing mock and protocol reference.

Structure:

```
server/
├── mock_pc_agent.py (full v2, partial v2, v1-only modes, chaos mode)
├── reference_agent/
│   ├── protocol.py
│   ├── websocket_server.py
│   ├── auth.py
│   └── session.py
└── test_client.py
```

Mock server supports:

```
--chaos: enable duplicate/delay/drop simulation
--v1: simulate v1-only agent (no capabilities frame)
--partial: partial v2 capabilities
```

## 20. Testing Your Agent

Use Android's FakeAgentConnection as client test double, and test_client.py as server test.

Contract tests should cover:

- Hello -> Welcome
- StartSession -> SessionStarted -> Question
- SubmitAnswer -> Evaluation
- RateCard -> RatingSaved -> Question
- Auth: no token, correct token, wrong token, expired, Bearer vs legacy frame
- Wrong endpoint: socket opens but no Study Agent handshake -> handshake failure, not Ready
- Protocol mismatch: server supports only v3, client 1,2 -> incompatible
- Handshake timeout
- Ping: normal, late, duplicate, missing
- Network loss during idle, question, answer submission, evaluation wait, rating
- Logical-commit audit: different message IDs, ACK loss, restart and cache eviction must not duplicate a scheduler effect **before** advertising idempotent replay (not implemented in this mock)
- Large payloads
- Malformed JSON: do not crash
- Unknown message type: must not destroy connection
- Buffer stress: many progress updates, ensure critical preserved
- Endurance: 24h virtual, many pings, reconnects, 1000+ turns, no leak
```

## 21. Forward Compatibility

- ignoreUnknownKeys = true for optional future fields
- Malformed required fields must not be silently accepted
- Unknown message types handled via envelope-first parser -> UnknownAgentMessage, not connection loss
- Preserve unknown capabilities, ignore safely

## 22. Local-First Deployment

Default:

```
Android --LAN/Tailscale--> PC Study Agent --127.0.0.1--> AnkiConnect
                                      \--localhost--> LLM
```

Never expose AnkiConnect directly to phone.

## 23. QR Pairing

If implementing:

```
PC: study-agent pair -> displays QR with:
  server address, port, path, TLS flag, short-lived pairing code (5 min, single-use)

Android: scans QR, connects, exchanges pairing code for long-lived token via secure endpoint,
stores token in SecureTokenStorage

Do NOT put long-lived token in QR
```

## 24. Checklist for Your Agent

- [ ] WebSocket endpoint with Bearer auth
- [ ] Hello -> Welcome with selected_protocol and capabilities
- [ ] Protocol negotiation (highest mutual)
- [ ] Ping/pong with in_reply_to correlation
- [ ] Stable error codes
- [ ] Idempotency via message_id
- [ ] review_turn_id enforcement
- [ ] session_revision monotonic
- [ ] Session snapshot authoritative for recovery
- [ ] Rate limiting with RATE_LIMITED
- [ ] Size limits
- [ ] Input validation
- [ ] No arbitrary command execution
- [ ] TLS support, no trust-all
- [ ] agent_id persistent
- [ ] Dashboard/health/config if capabilities advertised
