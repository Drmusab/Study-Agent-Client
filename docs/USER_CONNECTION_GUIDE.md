# User Connection Guide

This guide is for users who do NOT know WebSocket, LAN IP, ports, JSON, or AnkiConnect.

## 1. What You Need

- PC (Windows/macOS/Linux) with Anki installed
- Android phone (Android 8+)
- Same Wi-Fi network OR Tailscale for remote
- Study Agent PC software

## 2. Start the PC Study Agent

### Windows

```
1. Install Anki from https://apps.ankiweb.net/
2. Open Anki -> Tools -> Add-ons -> Get Add-ons
3. Enter code: 2055492159 (AnkiConnect)
4. Restart Anki
5. Start Study Agent:
   - If you have mock: python server/mock_pc_agent.py
   - If production: double-click StudyAgent.exe or run from terminal
6. Allow firewall when prompted: "Allow through Private networks"
7. You should see:
   Agent Ready
   Anki Ready
   Listening on 0.0.0.0:8765
```

Find your PC IP:
```
Press Windows+R, type cmd, Enter
Type: ipconfig
Look for "Wireless LAN adapter Wi-Fi" -> IPv4 Address
Example: 192.168.1.100
Do NOT use 127.0.0.1 - that's only for the PC itself
```

Windows Firewall manual fix if needed:
```
Settings -> Privacy & Security -> Windows Security -> Firewall & network protection
-> Allow an app through firewall
-> Find Python or Study Agent -> Check Private
Do NOT disable firewall globally
```

### macOS

```
1. Install Anki, install AnkiConnect add-on same as Windows
2. Open Terminal, find IP:
   ifconfig | grep "inet "
   Look for 192.168.x.x
3. Start Study Agent:
   python3 server/mock_pc_agent.py
4. macOS may ask: "Do you want to allow incoming connections?" -> Allow
5. Verify listening
```

### Linux

```
1. Install Anki, AnkiConnect
2. Find IP:
   ip addr
   Look for wlan0 or eth0 -> inet 192.168.x.x
3. Start agent:
   python3 server/mock_pc_agent.py
4. Firewall (if ufw):
   sudo ufw allow 8765/tcp
   Do not expose to public internet without TLS/Tailscale
```

## 3. Connect on the Same Wi-Fi

```
Android:
1. Open Study Agent app
2. Tap "PC Connection" in top bar
3. Tap "+" or "Add PC Manually"
4. Fill:
   PC name: Home PC (any friendly name)
   Host: 192.168.1.100 (from PC step)
   Port: 8765
   Path: /ws
   Secure (WSS): OFF
5. Save
6. Tap the profile to select it (shows "Active")
7. Tap "Connect"
8. Wait for staged checks:
   PC found ✓
   Study Agent ✓
   Protocol ✓
   Auth ✓
   Anki ✓
   AI ✓
```

If all green, go to Home and Start Study.

## 4. Connect with Tailscale (Remote Study)

Tailscale creates a private encrypted network between your devices. No router port forwarding needed.

```
PC:
1. Install Tailscale from https://tailscale.com/download
2. Sign in
3. Find Tailscale IP:
   - Open Tailscale admin console, or
   - Run: tailscale ip -4
   - Example: 100.82.14.92
   - Or use MagicDNS name: my-pc.tail-scale.ts.net

Android:
1. Install Tailscale from Play Store
2. Sign in to SAME account/tailnet as PC
3. In Study Agent app:
   Add PC Manually
   Host: 100.82.14.92 OR my-pc.tail-scale.ts.net
   Port: 8765
   Path: /ws
   Secure: OFF (Tailscale already encrypts end-to-end)
4. Connect
```

Benefits:
- Works on mobile data, public Wi-Fi, anywhere
- No port forwarding
- Encrypted by WireGuard
- No need for TLS certificates

## 5. Pair Using QR / Automatic Discovery (Optional)

### Automatic Discovery (mDNS)

If your PC Agent advertises `_studyagent._tcp` via mDNS:

```
Android:
1. Connection screen -> "Find My PC Automatically"
2. App scans local network
3. Select your PC from list
4. Connect
```

Note: Some networks/VPNs block mDNS. Manual IP always works as fallback.

### QR Pairing (High Value)

If PC Agent supports `study-agent pair`:

```
PC:
1. Run: study-agent pair
2. QR code appears with:
   - Server address
   - Port
   - Short-lived pairing code (expires in 5 min, single-use)

Android:
1. Connection -> "Scan pairing QR"
2. Scan code
3. App exchanges pairing code for long-lived token
4. Token stored securely, QR code expires

Security: QR contains short-lived code, NOT long-lived token.
```

If QR not available, use manual setup - it's always supported.

## 6. Manual Setup Details

### Fields Explained (Simple Terms)

- **PC name**: Friendly name like "Home PC" - for you only
- **Host**: Where to find PC - IP like 192.168.1.100 or Tailscale name
- **Port**: Door number - usually 8765, same as PC agent
- **Path**: Usually /ws - leave default
- **Secure (WSS)**: OFF for local Wi-Fi, ON if you have TLS certificate
- **Auth token**: Password to prove it's you - get from PC agent pairing

### Common Hosts

```
Home Wi-Fi: 192.168.1.100 (your PC's actual IP)
Tailscale: 100.82.14.92 or my-pc.tail-scale.ts.net
Emulator: 10.0.2.2 (ONLY for Android Studio emulator)
Do NOT use 127.0.0.1 from Android phone - that's the phone itself
```

## 7. Verify Anki

```
Anki must be running on PC if your Study Agent uses AnkiConnect.

Check:
- Open Anki desktop - should show your decks
- AnkiConnect should be installed (Tools -> Add-ons)
- Study Agent logs should say "Anki Ready"

Architecture:
Android -> PC Study Agent -> 127.0.0.1 AnkiConnect -> Anki
NOT: Android -> AnkiConnect directly (security boundary)

If Anki shows unavailable:
- Open Anki
- Check AnkiConnect add-on enabled
- Verify AnkiConnect URL is 127.0.0.1:8765 in PC Agent config
- Do NOT expose AnkiConnect to network - keep it localhost
```

## 8. Verify AI

```
AI configuration lives on PC Agent, NOT Android.

Examples PC Agent might use:
- Local Ollama: http://localhost:11434
- OpenAI-compatible endpoint
- Cloud LLM (keys stored on PC only)

Android never holds AI API keys - only Study Agent auth token.

If AI shows unavailable:
- Check PC Agent logs
- If Ollama: is Ollama running? (ollama serve)
- If cloud: is API key set on PC? Is quota available?
- Check PC Agent AI settings file
```

## 9. Start Studying

```
1. Connection shows Ready, Anki Ready, AI Ready
2. Go Home (Dashboard)
3. Dashboard shows:
   - Today's reviewed, remaining
   - Active deck
   - Goal progress
   - Recent performance
4. Tap "Start Study" -> Pick deck -> Start
5. Question is read aloud
6. Speak answer (or use Push-to-Talk button)
7. Evaluation spoken + shown
8. Rate: Again/Hard/Good/Easy (voice or buttons)
9. Next card...
```

## 10. Troubleshooting

### Staged Connection Test

Use "Test Connection" button when creating/editing profile. It tests:

```
1. Network (Android has internet/Wi-Fi)
2. DNS/IP reachability (can find PC)
3. WebSocket upgrade (port open)
4. Study Agent handshake (is it really Study Agent?)
5. Protocol compatibility (v1/v2)
6. Authentication (token valid?)
7. Anki (if capability advertised)
8. AI (if capability advertised)
```

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

Message: The PC Agent is running, but the token was rejected.
Action: Edit token or pair again.
```

### Common Issues

| What you see | Likely reason | What to do |
|---|---|---|
| PC not found | Different Wi-Fi networks | Join same Wi-Fi or use Tailscale |
| Connection refused | Agent stopped or firewall | Start PC Agent, allow through firewall |
| Authentication failed | Wrong/expired token | Edit profile, replace token |
| Anki unavailable | Anki closed | Open Anki desktop |
| AnkiConnect unavailable | Add-on missing | Install AnkiConnect add-on 2055492159 |
| AI unavailable | Provider/model issue | Check PC Agent AI settings, Ollama running? |
| Protocol incompatible | Version mismatch | Update Android app and PC Agent |
| Handshake timeout | Wrong service on port | Verify host/port points to Study Agent, not another service |
| Frequent disconnects | Weak Wi-Fi or VPN issue | Check Wi-Fi signal, Tailscale status |
| Connected but no cards | Deck empty or config | Check deck in Control Center, Anki has due cards? |

### Copy Diagnostics

In Diagnostics screen, tap "Copy Summary" to get sanitized text:

```
App: 1.0.0
Agent: 2.3.0
Protocol: 2
Connection: Ready
Transport: WSS
Host: 192.168.1.100:8765 (sanitized)
Latency: 14ms
Anki: Ready
AI: Ready
Last Error: none
```

No tokens included. Share this when asking for help.

### Still Stuck?

1. Check PC Agent logs for errors
2. Check Android Diagnostics screen (Network section)
3. Try Mock Agent mode to verify Android app works
4. Try emulator address 10.0.2.2 if using emulator
5. Ensure no VPN blocking local network (some VPNs block LAN)
6. Restart both PC Agent and Android app
7. Check firewall on PC

## Security Notes for Users

- Local Wi-Fi ws:// is okay for trusted home network
- For internet, use Tailscale or WSS (TLS)
- Do NOT expose ws:// port directly to public internet via router port forwarding
- Tokens stored securely in Android Keystore, not in profile JSON
- QR pairing codes expire in 5 minutes, single-use
- Diagnostic exports never contain tokens
