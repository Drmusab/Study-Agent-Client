# Security Architecture & Threat Model

## 1. Threat Model & Design Principles

### 1.1 Credential Isolation (Zero Cloud Keys on Mobile)
- Rule: Android NEVER holds LLM API keys (OpenAI, Anthropic, Ollama credentials) or AnkiConnect secrets.
- Architecture: LLM credentials remain exclusively on PC host. Android only speaks Study Protocol.

### 1.2 Local Secure Token Storage
- Tokens stored using EncryptedSharedPreferences backed by Android Keystore (AES-256 GCM + AES-256 SIV)
- Plaintext tokens excluded from backups (data-extraction-rules.xml and backup-rules.xml)
- ServerProfile = non-secret config (host, port, path, name)
- SecureTokenStorage = secret (token)
- Never regress: keep tokens outside normal profile JSON

### 1.3 Safe Remote Connections
- Unencrypted public Internet exposure prohibited: PC Agent must NOT be opened unauthenticated directly to public internet via ws:// port forwarding
- Recommended remote transports:
  1. Private Mesh VPN (Tailscale/WireGuard): encrypted peer-to-peer via WireGuard, no port forwarding
  2. TLS/WSS: reverse proxy via Nginx/Caddy with valid certificates
  3. Local Wi-Fi (LAN): trusted home/private network, allow ws:// with UI label "Local unencrypted connection"

### 1.4 Logging & Sanitization
- All logs pass through AppLogger.sanitize()
- Patterns matching token=, authToken, Bearer, session tokens redacted to ***REDACTED*** before storage/logcat
- Diagnostic exports never reveal credentials
- Never log raw JSON frames, never log auth tokens
- Test leakage: HTTP headers, authenticate frame, profile export, diagnostics export, exceptions

### 1.5 Speech Recognition Privacy
- No transcript logging by default: completion logs only purpose, char count, candidate count, confidence, language, latency
- Full transcript only via explicit sttDebugTranscriptLogging opt-in (developer option)
- No raw audio retention: onBufferReceived empty, never records/stores mic audio
- Privacy-safe metrics: timings, error categories, confidences, purposes, languages - never answer text
- Redacted diagnostics by construction: logger never receives transcript unless opt-in, so export cannot leak answers
- No expected-answer leakage: vocabulary biasing from current question + curated medical list, expected answer never reaches client
- Study answers may contain personal/clinical detail: lifetime is current interaction, memory only, no long-term store
- No mandatory cloud STT: platform recognizer, no third-party API key in APK

### 1.6 Protocol Validation & Defensive Parsing
- Kotlinx Serialization lenient mode with ignoreUnknownKeys = true for forward compatibility
- Strict type verification prevents memory exhaustion from invalid payloads
- Session IDs, Message IDs ensure idempotency and prevent duplicate updates
- Size limits: individual frame 2MB, question 10KB, answer 20KB, feedback 20KB, dashboard 1MB - reject absurd payloads safely
- Unknown message types via envelope-first parser -> UnknownAgentMessage, not connection loss
- Preserve unknown capabilities, ignore safely
- Malformed required fields must not be silently accepted

## 2. Authentication Architecture

### Modern Path (Preferred): Bearer Header

```
WebSocket HTTP upgrade
Authorization: Bearer <token>
```

Server either accepts upgrade or responds 401/403. One clear path, no second auth.

### Legacy Path: authenticate frame

```json
{"type":"authenticate","token":"..."}
```

Keep only for legacy agents if required. Capability/protocol negotiation decides if necessary. Do NOT send both by default.

### Token Lifecycle

- Generation: PC Agent creates long-lived token after pairing
- QR Pairing: QR contains short-lived one-time code (5 min, single-use), NOT long-lived token. Android exchanges code for token via secure endpoint, stores in SecureTokenStorage
- Rotation: if server rejects previously valid token, show "Authentication expired or invalid", allow user to replace token without deleting/recreating entire profile
- Expiry: pairing code expires 5 min, single-use, configurable/documented

### Authentication Result Explicit

UI must know "Connected to socket but authentication rejected" without waiting for unrelated failure. Distinguish:

```
Transport open
Study Agent detected
Authenticated
Agent ready
```

## 3. Transport Security

### WS vs WSS Policy

- Local LAN: allow ws://192.168.x.x for trusted network if product chooses, UI identifies "Local unencrypted connection" without excessive alarm
- Remote Internet: Do NOT recommend exposing unencrypted ws:// port directly to public Internet. Recommend Tailscale or WSS/TLS
- Reduce global cleartext exposure: audit android:usesCleartextTraffic="true" - prefer deliberate Network Security Configuration allowing intended local dev/LAN traffic instead of globally trusting arbitrary cleartext endpoints

### TLS

- Never implement trust all certificates, disable hostname verification as production convenience
- Self-signed certificates: if support required, design explicit safe approach such as certificate fingerprint pairing/pinning, do NOT silently trust any self-signed
- TLS failures map to TlsFailure state with actionable message: "Check secure server address/certificate"

### Agent Identity

- Consider server-generated persistent agent_id in welcome - allows Android to recognize same PC Agent at new address without hardware identifiers
- Do NOT trust name as identity - two computers may both be named "Study PC", use server ID separately
- Agent ID stored in ServerProfile as optional field, not hardware identifier

## 4. Protocol Security

### No API Keys in Android

- OpenAI/Anthropic/Ollama/Hermes provider credentials belong on PC Agent, Android gets only Study Agent auth credential

### No Arbitrary Command Execution

- Protocol must remain narrow and typed, no generic {"type":"execute","command":"..."}
- Server must validate client input: deck, card ID, turn ID, session ID, rating, limits, configuration, text length - never trust Android payload blindly

### Rate Limiting

- PC Agent should protect expensive actions (LLM evaluation, insight generation) against accidental duplicate/flood - return RATE_LIMITED with retry guidance

### Replay & Idempotency

- message_id as idempotency key prevents replay causing double rating/evaluation
- Server remembers recently processed mutation IDs
- Rating must never apply twice after reconnect (critical for Anki scheduling)
- Answer submission must never evaluate twice unnecessarily - cache/replay result for repeated same request ID where reasonable

### Oversized Payloads & Malformed Frames

- Define reasonable maximum sizes, reject safely
- Do NOT use limits that break real content (long medical explanations, large deck lists exist)
- Bad JSON: do not crash, sanitized diagnostic only

## 5. Network Security

### Local-First Deployment

```
Android
  │
LAN/Tailscale
  ▼
PC Study Agent
  │
  ├── AnkiConnect localhost (127.0.0.1:8765)
  └── LLM provider/local model
```

- Document clearly
- Never expose AnkiConnect directly to phone - keep PC Agent as security/control boundary
- Architecture: Android -> PC Study Agent -> 127.0.0.1 AnkiConnect, NOT Android -> AnkiConnect directly

### Discovery Security

- mDNS discovers where the Study Agent is, WebSocket handshake determines what the service is - keep separate
- Do NOT put auth secrets in mDNS
- Do NOT build Tailscale into APK - Android client only connects to address, no SDK needed for basic operation

### QR Pairing Security

- QR contains server address, port, path, TLS flag, short-lived pairing code
- Do NOT put long-lived auth token in QR
- Pairing code should expire 5 min, single-use, configurable/documented
- Manual connection remains required - QR is convenience, not protocol dependency

## 6. Cleartext Policy Audit

Current AndroidManifest has android:usesCleartextTraffic="true" for local development/LAN. Preferred:

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">192.168.0.0/16</domain>
    <domain includeSubdomains="true">10.0.0.0/8</domain>
    <domain includeSubdomains="true">100.64.0.0/10</domain> <!-- Tailscale CGNAT -->
  </domain-config>
</network-security-config>
```

And manifest:

```xml
android:networkSecurityConfig="@xml/network_security_config"
```

Instead of globally trusting arbitrary cleartext endpoints. Allows intended local dev/LAN traffic.

## 7. API Security Review Checklist

- [ ] auth: Bearer preferred, legacy frame only when needed, no double-send
- [ ] token storage: EncryptedSharedPreferences, not profile JSON
- [ ] TLS: no trust-all, hostname verification enforced, pinning explicit for self-signed
- [ ] cleartext: Network Security Config, not global true, UI labels local unencrypted
- [ ] replay/idempotency: message_id dedup, rating exactly-once
- [ ] oversized payloads: 2MB frame limit, size checks
- [ ] malformed JSON: no crash, envelope-first unknown handling
- [ ] session ownership: validate session_id, review_turn_id, revision
- [ ] error leakage: no stack traces to user, no token in errors
- [ ] logs: sanitized, no raw frames, no tokens
- [ ] diagnostic export: sanitized, no tokens, no transcripts
- [ ] no API keys in Android
- [ ] no arbitrary command execution
- [ ] rate limiting for expensive actions

## 8. User-Facing Security Messages

Map problem to action:

```
Connection refused -> Start PC Agent
Authentication rejected -> Edit token
TLS failed -> Check secure server address/certificate
Protocol incompatible -> Update app/agent
Network missing -> Check Wi-Fi/Tailscale
Handshake timeout -> Verify endpoint is Study Agent, not other service
Agent busy -> Wait and retry
```

Do NOT display exception strings to users - technical details in Diagnostics.

## 9. Future Considerations

- Certificate pinning UI for self-signed TLS
- mDNS discovery with agent_id verification
- Pairing via QR with short-lived code exchange
- Tailscale MagicDNS support
- Network Security Config refinement
