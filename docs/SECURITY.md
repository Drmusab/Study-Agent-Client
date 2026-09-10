# Security Architecture & Threat Model

## 1. Threat Model & Design Principles

The Study Agent system distributes work between a user's PC (server) and Android device (client). Because this architecture involves local network communication and speech processing, the following security requirements are enforced:

### 1.1 Credential Isolation (Zero Cloud Keys on Mobile)
* **Rule:** Android NEVER holds LLM API keys (e.g. OpenAI, Anthropic, Ollama credentials) or AnkiConnect secrets.
* **Architecture:** LLM credentials remain exclusively on the user's PC host. The Android client only speaks the domain-specific Study Protocol.

### 1.2 Local Secure Token Storage
* Authentication tokens used to authenticate the mobile client to the PC agent are stored using Android Jetpack Security (`EncryptedSharedPreferences`) backed by Android Keystore hardware-backed encryption (AES-256 GCM + AES-256 SIV).
* Plaintext authentication tokens are excluded from standard Android backups (`data-extraction-rules.xml` and `backup-rules.xml`).

### 1.3 Safe Remote Connections
* **Unencrypted Public Internet Exposure is Prohibited:** The PC Agent WebSocket must NOT be opened unauthenticated directly to the public internet.
* **Recommended Remote Transports:**
  1. **Private Mesh VPN (Tailscale / WireGuard):** Enables encrypted peer-to-peer traffic using WireGuard end-to-end encryption without port forwarding.
  2. **TLS / WSS:** Reverse proxy via Nginx / Caddy with valid TLS certificates for domain setups.
  3. **Local Wi-Fi (LAN):** Trusted home/private Wi-Fi network.

### 1.4 Logging & Sanitization
* All application logs pass through `AppLogger.sanitize()`.
* Patterns matching `token=`, `authToken`, `Bearer`, or session tokens are automatically redacted to `***REDACTED***` before writing to in-memory buffers or Android Logcat.
* Diagnostic log exports never reveal credentials.

### 1.5 Protocol Validation & Defensive Parsing
* Every incoming frame is parsed with Kotlinx Serialization in lenient mode with `ignoreUnknownKeys = true`.
* Strict type verification prevents memory exhaustion from invalid or malicious payloads.
* Session IDs and Message IDs ensure idempotency and prevent duplicate updates.
