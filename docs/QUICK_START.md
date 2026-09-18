# Quick Start - 5 Minutes to First Card

Goal: New user can reach first study card in five minutes.

## Path A - Test without PC Agent (Mock Mode)

This path requires no PC setup. Use it to verify the Android app works.

```
1. Install Android app
2. Open app -> Connection screen
3. Toggle ON "Mock Agent (development)" at bottom
4. Go back to Home
5. Tap "Start Study Session"
6. First question is read aloud
```

You are now in a simulated study loop with fake cards, evaluation, and ratings.

## Path B - Real PC Agent (Recommended)

### PC Side (2 minutes)

```
1. Open Anki desktop
2. Install AnkiConnect add-on (Anki -> Tools -> Add-ons -> Get Add-ons -> code 2055492159)
3. Verify AnkiConnect: open browser to http://127.0.0.1:8765 (should respond)
4. Start Study Agent on PC:
   - Example: python mock_pc_agent.py --port 8765
   - Or your production agent binary
5. Confirm it says:
   Agent Ready
   Anki Ready
   Listening on port 8765
```

### Android Side (3 minutes)

#### Same Wi-Fi (Simplest)

```
1. Find PC's local IP:
   Windows: ipconfig -> IPv4 address (e.g. 192.168.1.100)
   macOS: ifconfig | grep inet
   Linux: ip addr

2. Android: Open Study Agent app
3. Tap Connection (top bar)
4. Tap "Add PC Manually"
5. Enter:
   Name: Home PC
   Host: 192.168.1.100 (your PC IP from step 1)
   Port: 8765
   Path: /ws
   TLS: OFF
6. Tap Save
7. Select the new profile (tap it)
8. Tap Connect
9. Verify staged status:
   PC found ✓
   Study Agent ✓
   Protocol v2 ✓
   Auth ✓
   Anki ✓
   AI ✓
   Latency 14ms

10. Go Home -> Start Study
```

#### With Tailscale (Remote, No Port Forwarding)

```
1. Install Tailscale on PC and Android, sign in to same tailnet
2. Find PC's Tailscale address:
   - Tailscale admin panel, or
   - On PC: tailscale ip -4
   - Example: 100.82.14.92
   - Or MagicDNS: my-pc.tail-scale.ts.net

3. Android Connection -> Add PC Manually
   Host: 100.82.14.92 or my-pc.tail-scale.ts.net
   Port: 8765
   Path: /ws
   TLS: OFF (Tailscale already encrypts)

4. Connect -> Verify green checks
```

#### Android Emulator

```
Host: 10.0.2.2
Port: 8765
Path: /ws

Note: 10.0.2.2 is ONLY for emulator. Real phone must use actual PC IP.
```

### Verify Ready

After connecting, you should see:

```
STUDY AGENT

Study PC
Ready

Anki       Ready
AI         Ready
Latency    14 ms

[ Disconnect ]
```

If any component shows not ready:

- Anki unavailable -> Open Anki desktop
- AI unavailable -> Check PC Agent AI settings (Ollama running? API key set?)
- Auth failed -> Edit token in profile
- Protocol mismatch -> Update app and PC Agent

### Start Studying

```
Home -> Dashboard shows today's stats
Tap "Start Study" -> Choose deck -> Start
First question is read aloud
Speak your answer -> Evaluation -> Rate card (Again/Hard/Good/Easy)
```

## Troubleshooting Quick Links

- Full guide: docs/USER_CONNECTION_GUIDE.md
- PC integration: docs/PC_AGENT_INTEGRATION_GUIDE.md
- Protocol: docs/PROTOCOL.md
- Security: docs/SECURITY.md
- Diagnostics: docs/DIAGNOSTICS.md

## What User Sees vs What To Do

| What you see | Likely reason | What to do |
|---|---|---|
| PC not found | Different network | Join same Wi-Fi or use Tailscale |
| Connection refused | Agent stopped | Start PC Study Agent |
| Authentication failed | Wrong token | Pair again / update token |
| Anki unavailable | Anki closed | Open Anki |
| AI unavailable | Provider/model issue | Check PC Agent AI settings |
| Protocol incompatible | Version mismatch | Update app/agent |
| Frequent disconnects | Network/VPN issue | Check Wi-Fi/Tailscale |
| Connected but no cards | Deck/config problem | Check deck in Control Center |
