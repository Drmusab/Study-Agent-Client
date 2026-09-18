# Study Agent Android Voice Client 🎧📱

A native, production-quality Android application designed as the **mobile command center and voice client** for an intelligent Study/Anki PC Agent.

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

- **Dashboard** — live operational home screen: system readiness (PC Agent / Anki / AI / audio), smart Start/Resume, dynamic decks, today's stats, goal progress, weekly performance, rating distribution, server-generated insights and recommendations, AI usage — all sourced from PC agent over Protocol v2, with honest Live/Cached/Stale freshness and offline cache.
- **Study** — hands-free voice study loop: questions read aloud, spoken answers evaluated by PC agent, spoken feedback and voice ratings.
- **Control Center** — how the agent studies: deck, mode, session target, evaluation strictness, feedback depth, Socratic follow-ups, hints, rating automation and transcript privacy, with presets and ACK-verified config sync.

The Android client enables hands-free studying with Bluetooth headphones: it reads questions aloud, captures spoken answers, sends transcripts to PC agent for LLM evaluation, speaks feedback, and updates Anki card ratings via natural voice commands. Protocol v1 agents remain fully supported for basic study; management panels gate off gracefully.

---

## 📚 Documentation Entry Point

- **Quick Start (5 min to first card)**: docs/QUICK_START.md — two paths: mock mode and real PC agent
- **User Connection Guide**: docs/USER_CONNECTION_GUIDE.md — for users who don't know WebSocket/LAN IP/ports/JSON/AnkiConnect
- **PC Agent Integration Guide**: docs/PC_AGENT_INTEGRATION_GUIDE.md — for developers building compatible Study Agent
- **Agent API**: docs/AGENT_API.md — typed API, lifecycle, handshake, idempotency, recovery
- **Protocol Specification**: docs/PROTOCOL.md — v1 + v2 with welcome handshake, in_reply_to, error codes
- **Security**: docs/SECURITY.md — auth, TLS, tokens, cleartext policy, rate limiting
- **Architecture**: docs/ARCHITECTURE.md — layered architecture with AgentClient, RequestCoordinator, Snapshot
- **Diagnostics**: docs/DIAGNOSTICS.md — staged connection test, developer diagnostics, sanitized exports
- **Other**: DASHBOARD.md, CONTROL_CENTER.md, AUDIO_ROUTING.md, TTS_ARCHITECTURE.md, etc.

## 🚀 Key Features

* **Voice-First Hands-Free Loop:** Question Spoken ➔ Voice Answer Capture ➔ STT ➔ PC Evaluation ➔ TTS Feedback ➔ Spoken Rating ➔ Next Card
* **Bilingual Voice Recognition & Synthesis:** English and Arabic commands (Again, Hard, Good, Easy, Repeat, Hint, Explain, Show Answer, Skip, Pause, Resume, Stop)
* **Enhanced Connection Architecture:**
  - Separate states: DISCONNECTED → RESOLVING → CONNECTING_TRANSPORT → TRANSPORT_OPEN → HANDSHAKING → AUTHENTICATING → NEGOTIATING → READY
  - Welcome handshake with protocol negotiation (server chooses selected_protocol)
  - Bearer auth preferred, legacy authenticate frame only when needed
  - Ping correlation via in_reply_to, dead connection detection via liveness
  - Connection generation prevents late callbacks from stale connections
  - Network awareness (ConnectivityManager) distinguishes no network vs agent unreachable
  - Exponential backoff + jitter, reset after stable success, manual override via Retry Now
  - Staged connection test: Network, DNS, Transport, Handshake, Protocol, Auth, Anki, AI
  - Connection snapshot as single source of truth (phase, transport, protocol, capabilities, latency, problem)
* **Multi-Server Profiles:** Home PC (LAN), Tailscale/WireGuard Mesh VPN (100.x.x.x and MagicDNS), Android Emulator (10.0.2.2), custom hosts with WS/WSS and token auth, validation for hostname/IPv4/IPv6/Tailscale, token rotation without deleting profile
* **Typed Agent API:** AgentApi with Success/Rejected/Timeout/Disconnected/ProtocolFailure, stable error codes (ANKI_NOT_RUNNING, AUTH_INVALID, etc.), request correlation via in_reply_to, bounded RequestCoordinator, purpose-specific timeouts (evaluation 30s vs deck_list 5s)
* **Idempotency & Exactly-Once:** message_id as idempotency key, server remembers recent mutations, rating never applies twice, review_turn_id + session_revision enforcement
* **Session Recovery:** On reconnect: handshake → auth → capability negotiation → request_session_snapshot → reconcile → resume, server snapshot authoritative
* **Security:** No LLM API keys in APK, tokens in EncryptedSharedPreferences separate from profile JSON, never log tokens/frames, TLS never trust-all, cleartext via Network Security Config not global true, rate limiting, size limits (2MB frame)
* **Bluetooth Headphone Optimization:** Modern audio device routing with automatic changes and fallback to phone speaker/mic
* **Background & Lock-Screen:** Foreground service with wake-lock and media notifications
* **Offline Mock Mode & Python Mock Server:** Built-in fake agent toggle and Python WebSocket server (server/mock_pc_agent.py) with --chaos, --v1, --partial modes, reference implementation
* **Diagnostics:** Staged test, developer diagnostics (generation, protocol, agent ID, capabilities, ping, last message age), sanitized copy, support ID, observability metrics (connect time, handshake time, ready latency, RTT by type)

---

## 🏗️ Architecture Overview (Updated)

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
           AgentApi        AgentEvents (question, evaluation, progress...)
                \             /
                 \           /
                  ▼         ▼
              Request Coordinator (correlation, timeouts, bounded)
                       │
                       ▼
                 Protocol Codec (JSON, envelope-first, BuildConfig version)
                       │
                       ▼
               Connection Manager (generation, handshake timeout, liveness)
                /             \
               ▼               ▼
       Network Monitor     WebSocket (Bearer, single ping loop)
                               │
                               ▼
                         PC Study Agent
                               │
                               ├── AnkiConnect localhost (127.0.0.1)
                               └── LLM provider/local model
```

See docs/ARCHITECTURE.md for full layered architecture and responsibility boundaries.

---

## 📋 Prerequisites

* Android Device or Emulator: Android 8.0 (API 26)+, targeting Android 14 (API 34)
* JDK: OpenJDK 17 or 21
* Android SDK: Platform 34 and Build-Tools 34.0.0
* Gradle: 8.7+ (wrapper included)
* PC Server: Any PC running Study Agent (or included Python mock server)

---

## 🛠️ Build & Install

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease

adb install app/build/outputs/apk/debug/app-debug.apk

./gradlew testDebugUnitTest
./gradlew lint
```

---

## 🌐 Connecting to PC Agent (Quick)

See docs/QUICK_START.md for 5-minute guide and docs/USER_CONNECTION_GUIDE.md for full user-friendly guide.

### Same Wi-Fi (Simplest)

1. PC: find IP via ipconfig (Windows) or ip addr (Linux) or ifconfig (macOS) — e.g. 192.168.1.100
2. Android: Connection → Add PC Manually → Host: 192.168.1.100, Port: 8765, Path: /ws, TLS OFF → Save → Select → Connect
3. Verify staged checks green: PC found ✓, Study Agent ✓, Protocol v2 ✓, Auth ✓, Anki ✓, AI ✓
4. Home → Start Study

### Tailscale (Remote, Recommended)

1. Install Tailscale on PC and Android, same tailnet
2. PC Tailscale IP: 100.82.14.92 or MagicDNS name
3. Android: Add PC Manually → Host: 100.x.x.x or MagicDNS → Connect
4. No router port forwarding required

### Emulator

Host: 10.0.2.2, Port: 8765 — ONLY for emulator, real phone uses actual IP

---

## 🧪 Testing with Mock

### Built-in Fake Agent

1. Connection → Toggle ON "Mock Agent (development)"
2. Home → Start Study Session
3. Simulated full study loop

### Python Mock Server

```bash
python3 server/mock_pc_agent.py
# Options:
# --chaos: duplicate/delay/drop simulation
# --v1: simulate v1-only agent (no capabilities)
# --partial: partial v2 capabilities
# --port 8765 --seed 42

python3 server/test_client.py
```

Enhanced to be both testing mock and protocol reference implementation.

---

## 🎙️ Voice Commands

| Intent | English | Arabic |
|---|---|---|
| Again | "Again", "Forgot" | "مرة أخرى", "نسيت" |
| Hard | "Hard", "Difficult" | "صعب" |
| Good | "Good", "Correct" | "جيد", "تمام" |
| Easy | "Easy", "Simple" | "سهل" |
| Repeat | "Repeat", "Say again" | "أعد السؤال", "كرر" |
| Hint | "Hint", "Give me a hint" | "تلميح" |
| Explain | "Explain", "Why" | "اشرح", "لماذا" |
| Show Answer | "Show answer" | "اظهر الجواب" |
| Skip | "Skip", "Next" | "التالي", "تخطي" |
| Pause | "Pause", "Hold on" | "توقف" |
| Resume | "Resume", "Continue" | "اكمل" |
| End Session | "Stop", "End session" | "انهاء" |
| Stop Speaking | "Stop speaking", "Quiet" | "اسكت" |

---

## ⚙️ Settings

- Voice Recognition (STT): English/Arabic, recognition mode, medical biasing, answer length
- Voice Output (TTS): per-language voices, offline preferring, per-purpose speeds (question, feedback, explanation), pitch, acoustic gap, headset behavior
- Study Audio: AUTO, HEADSET_PREFERRED, PHONE, HEADSET_REQUIRED — phone is first-class route
- Study Behavior: hands-free mode, auto-play, auto-submit, confirm rating, listen for spoken rating, show transcript
- Connection: auto-reconnect, max attempts, ping interval

---

## 🔍 Diagnostics & Troubleshooting

### Staged Connection Test

Connection → Test Connection checks:

1. Network
2. DNS/IP reachability
3. WebSocket upgrade
4. Study Agent handshake (welcome)
5. Protocol compatibility
6. Authentication
7. Anki (if capability)
8. AI (if capability)

Example success:

```
PC found                 ✓
Study Agent              ✓
Protocol v2              ✓
Authentication           ✓
Anki                     ✓
AI evaluator             ✓
Latency                   14 ms
```

Example failure:

```
PC reachable              ✓
WebSocket                  ✓
Study Agent handshake      ✓
Authentication             ✕
→ The PC Agent is running, but the token was rejected.
```

### Common Issues

| What you see | Likely reason | What to do |
|---|---|---|
| PC not found | Different network | Join same Wi-Fi or use Tailscale |
| Connection refused | Agent stopped | Start PC Study Agent |
| Authentication failed | Wrong token | Pair again / update token |
| Anki unavailable | Anki closed | Open Anki |
| AI unavailable | Provider/model issue | Check PC Agent AI settings |
| Protocol incompatible | Version mismatch | Update app/agent |
| Handshake timeout | Wrong service on port | Verify host/port is Study Agent |
| Frequent disconnects | Network/VPN issue | Check Wi-Fi/Tailscale |
| Connected but no cards | Deck/config problem | Check deck in Control Center |

### Copy Diagnostics (Sanitized)

Diagnostics → Copy Summary:

```
App: 1.0.0
Agent: 2.3.0
Protocol: 2
Connection: Ready
Transport: WSS
Host: private/redacted
Latency: 14ms
Anki: Ready
AI: Ready
Last Error: none
```

No tokens. Support ID local to export, no remote tracking.

See docs/DIAGNOSTICS.md for full observability.

---

## 🔒 Security Summary

- No LLM API keys in APK — only Study Agent auth token, LLM creds on PC only
- Tokens in EncryptedSharedPreferences (Keystore), not profile JSON, never logged
- Bearer auth preferred, legacy frame only when needed, no double-send
- WS allowed for trusted LAN with label "Local unencrypted connection", recommend Tailscale/WSS for internet, no public ws:// port forwarding
- TLS: never trust all certs, pinning explicit for self-signed
- Size limits: 2MB frame max, question 10KB, etc., reject safely but allow real content
- No arbitrary Android command execution, server validates all input, rate limiting for expensive actions
- See docs/SECURITY.md for full threat model and checklist

---

## 📂 Project Structure

```
StudyAgentClient/
├── app/src/main/java/com/studyagent/client/
│   ├── core/
│   │   ├── models/ (ConnectionState rich lifecycle, ServerProfile validation, ProtocolMessages welcome/in_reply_to, AgentCapabilities)
│   │   ├── network/ (AgentConnection, WebSocketAgentConnection generation/handshake/liveness, AgentClient, AgentApi, ApiResult, ApiError, RequestCoordinator, ProtocolContext, MessageFactory, ConnectionProblem, TransportStatus, Snapshot, NetworkMonitor, ProtocolJson envelope-first/size limits, FakeAgentConnection)
│   │   ├── security/ (SecureTokenStorage)
│   │   ├── audio/ (AudioRouteManager, StudyAudioRouteCoordinator)
│   │   ├── voice/ (STT, TTS, voice commands)
│   │   ├── diagnostics/ (timeline, metrics)
│   │   └── common/ (logger, dispatchers)
│   ├── data/
│   │   ├── preferences/ (ProfileRepository token separation)
│   │   └── repository/ (ConnectionRepository snapshot/test/diagnostics, CapabilityStore welcome, DashboardRepository, StudySessionRepository idempotency)
│   ├── di/ (AppContainer)
│   ├── service/ (foreground service)
│   └── ui/ (Compose screens: connection staged test, dashboard, study, control, diagnostics)
├── server/
│   ├── mock_pc_agent.py (full v2, partial v2, v1-only, chaos, reference impl)
│   └── test_client.py
└── docs/
    ├── QUICK_START.md (5 min to first card)
    ├── USER_CONNECTION_GUIDE.md (for non-technical users)
    ├── PC_AGENT_INTEGRATION_GUIDE.md (for developers building agent)
    ├── AGENT_API.md (typed API, lifecycle, idempotency, recovery)
    ├── PROTOCOL.md (v1+v2, welcome, in_reply_to, error codes, size limits)
    ├── SECURITY.md (auth, TLS, tokens, cleartext policy)
    ├── ARCHITECTURE.md (layered with AgentClient, RequestCoordinator, Snapshot)
    ├── DIAGNOSTICS.md (staged test, developer diagnostics, metrics)
    └── others (DASHBOARD.md, CONTROL_CENTER.md, AUDIO_ROUTING.md, etc.)
```

---

## ✅ Definition of Done (Connection Architecture)

- [x] WebSocket transport and Agent Ready separate states
- [x] Compatible Study Agent verified before Ready (welcome handshake)
- [x] Protocol version explicitly negotiated (selected_protocol)
- [x] Actual app version advertised (BuildConfig.VERSION_NAME)
- [x] Modern auth one clear path (Bearer preferred, legacy compat)
- [x] Tokens securely stored (EncryptedSharedPreferences, not profile JSON)
- [x] Typed AgentApi with request/response/event separation
- [x] Request correlation centralized (RequestCoordinator, in_reply_to)
- [x] Timeouts bounded and purpose-specific
- [x] API errors stable codes, UI maps to user text
- [x] Mutating operations idempotent (message_id dedup)
- [x] Rating cannot apply twice
- [x] review_turn_id enforced
- [x] Session recovery authoritative (request_session_snapshot after reconnect)
- [x] Heartbeat detects dead sockets (liveness, unanswered pings, correlation)
- [x] Network changes trigger sane reconnect (NetworkMonitor, backoff reset, prompt reconnect)
- [x] Parallel attempts prevented (generation counter)
- [x] Wrong WebSocket service not reported as Ready (handshake timeout)
- [x] Connection UI explains exact failed stage (staged test, problem model)
- [x] User can test profile before studying (Test Connection)
- [x] v1 basic study remains supported (ReadyLegacy)
- [x] v2 Dashboard/Control supported (capability-gated)
- [x] Connection diagnostics richer and sanitized (generation, protocol, agent ID, latency, problem)
- [x] Local LAN documented (ipconfig, firewall)
- [x] Tailscale documented (100.x, MagicDNS, no port forwarding)
- [x] Emulator documented (10.0.2.2 only for emulator)
- [x] Windows setup documented (ipconfig, firewall)
- [x] Anki/AnkiConnect documented (localhost boundary, add-on install)
- [x] AI provider location documented (PC only, not APK)
- [x] Quick Start exists (Path A mock, Path B real)
- [x] PC Agent integration guide exists (handshake, auth, capabilities, idempotency, recovery, etc.)
- [x] Protocol contract tests (hello->welcome, start->question, etc.)
- [x] Auth tests (no token, correct, wrong, expired, Bearer vs legacy, leakage)
- [x] Idempotency tests (duplicate rating suppressed)
- [x] Reconnect/recovery tests (snapshot authoritative)
- [x] Endurance tests (no leak)

---

## 🔧 Validation

```bash
./gradlew testDebugUnitTest
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
python server/test_client.py
# pytest if reference agent suite exists
```

Real-device validation scenarios:

- same Wi-Fi
- Tailscale
- wrong IP
- wrong port
- wrong token
- PC Agent stopped
- Anki stopped
- AI unavailable
- network loss
- Wi-Fi reconnect
- screen off
- active study reconnect

---

## 🎯 Product Principle

Do NOT optimize for "WebSocket opened". Optimize for "The correct Study Agent is authenticated, compatible, healthy, and ready for this user to study."

At any moment Android should answer:

```
Do I have a network?
Can I reach the PC?
Did WebSocket open?
Is this actually a Study Agent?
Which protocol did we negotiate?
Am I authenticated?
Which capabilities exist?
Is Anki ready?
Is the AI evaluator ready?
Is there an active session?
Can that session be recovered?
What exactly failed?
What should the user do next?
```

And new user should go from "I installed the app" to "My first question is being read aloud" without needing to understand WebSocket protocols, JSON, ports, or Android networking.
