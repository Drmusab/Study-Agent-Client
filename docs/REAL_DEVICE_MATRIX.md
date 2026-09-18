# Real Device Validation Matrix

This document lists the scenarios that must be validated on real devices (not just emulator) before release.

## Environment

- Android 8.0+ (API 26+) to Android 14 (API 34)
- Different manufacturers (Samsung, Pixel, Xiaomi) for audio routing quirks
- Wi-Fi, cellular, VPN (Tailscale/WireGuard)
- Screen off, background, incoming call interruption

## Connection Scenarios

| Scenario | Expected Result | Verified |
|---|---|---|
| Same Wi-Fi, correct IP, no auth | Ready, staged test green | ☐ |
| Same Wi-Fi, wrong IP | PC not found, action: Check Wi-Fi | ☐ |
| Same Wi-Fi, wrong port | Connection refused, action: Start PC Agent | ☐ |
| Same Wi-Fi, wrong token (auth mode) | Auth failed, action: Edit token | ☐ |
| Same Wi-Fi, correct token Bearer | Ready, authenticated | ☐ |
| Same Wi-Fi, legacy authenticate frame | ReadyLegacy or Ready with legacy auth | ☐ |
| Tailscale, 100.x.x.x address | Ready, Tailscale chip shown | ☐ |
| Tailscale, MagicDNS .ts.net | Ready, DNS resolved | ☐ |
| Tailscale disconnected | Network unavailable or agent unavailable, reconnect on restore | ☐ |
| Emulator 10.0.2.2 on emulator | Ready | ☐ |
| Emulator 10.0.2.2 on real phone | PC not found (warn user) | ☐ |
| TLS WSS with valid cert | Ready, transport WSS | ☐ |
| TLS WSS with self-signed + pinning | Ready if pinned, else TLS failure | ☐ |
| Cleartext WS on internet (not LAN) | Warning shown, but allowed if user confirms | ☐ |
| Wrong service on port (e.g. HTTP server) | Handshake timeout, action: Verify Study Agent | ☐ |
| Protocol mismatch (server v3 only) | Incompatible protocol, action: Update app | ☐ |

## Network Resilience

| Scenario | Expected |
|---|---|
| Wi-Fi lost during study | Reconnecting, exponential backoff, no crash |
| Wi-Fi restored after 10s | Prompt reconnect, session recovery via snapshot |
| Airplane mode toggle | Network unavailable, then reconnect |
| VPN (Tailscale) disconnect/reconnect | Reconnect, generation prevents stale callbacks |
| Switch from Wi-Fi to cellular | Reconnect with new network type logged |
| Server stops during study | Agent unavailable, session remains recoverable |
| Server restarts | Reconnect, request_session_snapshot, resume |

## Study Flow

| Scenario | Expected |
|---|---|
| Start session, answer, rate | Happy path works |
| Duplicate rating (same messageId) | Rating applied once, second suppressed |
| Stale review_turn_id | Server rejects with STALE_REVIEW_TURN, client reloads |
| Stale session_revision | Server rejects with STALE_SESSION_REVISION, client refreshes |
| Large payload (>2MB) | Rejected with FRAME_TOO_LARGE, not crash |
| Malformed JSON | Rejected with INVALID_REQUEST, connection stays open |
| Unknown message type | Ignored (forward compat), logged as unknown |
| Ping correlation | RTT measured via in_reply_to, not confused by delayed pongs |
| Dead socket (no pong) | Detected via liveness, reconnect triggered |

## Audio Routing

| Scenario | Expected |
|---|---|
| Bluetooth headset connected | Route = headset, auto-switch |
| Headset disconnect mid-study | Fallback to phone speaker, attention shown |
| Wired headset | Detected as headset |
| Phone only (no headset) | Phone is first-class route, not error |
| Incoming call | Audio focus loss handled, pause or duck |

## Security

| Scenario | Expected |
|---|---|
| Token in profile JSON export | Must NOT appear |
| Token in logcat | Must be ***REDACTED*** |
| Token in diagnostics copy | Must be ***REDACTED*** |
| Cleartext traffic to internet | Blocked by Network Security Config unless LAN/Tailscale |
| Trust-all certs | Must NOT exist in codebase |
| AnkiConnect exposed to LAN | Must NOT, only 127.0.0.1 on PC |
| LLM API keys in APK | Must NOT, only PC agent has them |

## Performance

| Metric | Target |
|---|---|
| Connect time (LAN) | <2s |
| Handshake time | <1s |
| Ready latency (tap Connect to Agent Ready) | <3s |
| Ping RTT (LAN) | <50ms |
| Message rate | No polling loop, <5 msg/min idle |
| Memory leak (endurance 1h) | No growth >20MB |

## Manual Test Procedure

1. Install debug APK
2. Start mock server: `python3 server/mock_pc_agent.py --port 8765`
3. Add profile 192.168.1.x:8765
4. Test Connection → verify staged checks
5. Start study → complete 3 cards
6. Toggle Wi-Fi off/on → verify recovery
7. Stop mock server → verify agent unavailable
8. Start server again → verify auto-reconnect and snapshot
9. Test with Tailscale if available
10. Copy diagnostics → verify no token
11. Check logcat → verify no token

## Automated Tests

- `test_contract.py` unit tests: always run
- `test_contract.py` integration: requires server running
- Android unit tests: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lintDebug`
