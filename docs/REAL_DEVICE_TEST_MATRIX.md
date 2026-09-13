# Real-Device Test Matrix

**Purpose.** The JVM suites (`docs/TESTING_STRATEGY.md`) prove the *logic* of the session: state
transitions, exactly-once submissions, reconnect behaviour, bounded resources. They cannot prove
anything about a microphone, a Bluetooth stack, a thermal budget or a two-hour session on a phone in
someone's pocket. This document is the part that has to be done by hand, on hardware, and recorded.

**Status.** Nothing in this document has been executed. There is no device connected to the
development sandbox, so every cell below is `not run`, not `pass`. The first execution fills in the
Run Log (§7); from then on this file is the record of what was actually verified on hardware.

---

## 1. What only a real device can tell us

| Question | Why a JVM test cannot answer it |
|---|---|
| Does the recognizer start on this OEM's build? | `SpeechRecognizer` availability/capabilities are device- and vendor-specific |
| Does the answer come out of the phone speaker while the mic is open, without echo? | Half-duplex timing depends on real acoustic paths |
| Does a Bluetooth route change mid-question survive? | SCO/A2DP transitions are asynchronous and vendor-specific |
| Does the session keep working with the screen off? | Requires the real foreground service / wake-lock behaviour |
| Does it still work after two hours? | Thermal throttling, memory pressure, battery savers, Doze |
| Does the TTS engine have a voice for this language? | Engine + voice data are per-device |

Each of these maps to automated coverage of the *policy* that reacts to the condition — the device
run proves the platform actually delivers the condition and that the reaction works end-to-end.

---

## 2. Device matrix

Cover the three archetypes before adding more hardware. "Slowest supported" means the oldest,
lowest-RAM device on this list: it is the one that sets the performance budget
(`docs/PERFORMANCE.md` §6).

| Slot | Role | Example class | Why |
|---|---|---|---|
| D1 | Current flagship, stock-ish Android (latest API) | Pixel-class | Reference behaviour, newest APIs |
| D2 | Mid-tier, API ~30, lowest RAM supported | 4 GB Android 11-class | Perf/battery baseline; memory pressure |
| D3 | OEM-skinned device | One of the majors' skins | Recognizer/permission/background restrictions |
| D4 | Foldable or tablet (optional) | any | Layout/route quirks if the screen is not a phone |
| D5 | Emulator, API 30 and 34 | CI instrumented job | Not a substitute — a smoke check |

Environments to run *every* case in, unless the case says otherwise:

| Code | Environment |
|---|---|
| E-PHONE | Phone speaker + built-in mic, no headset |
| E-WIRED | Wired headset (or USB-C) with mic |
| E-BT-SCO | Bluetooth headset, headset profile with mic |
| E-BT-A2DP | Bluetooth speaker/A2DP only, no mic (hybrid case) |
| E-ECHO | Phone speaker at high volume, quiet room (worst case for self-echo) |
| E-NOISE | Realistic ambient noise |

---

## 3. Test rig

* The PC agent, or `server/mock_pc_agent.py` for a scripted, repeatable server:
  ```bash
  python3 server/mock_pc_agent.py --port 8765        # see server/run_mock_server.sh
  ```
  Configure the client's server profile to the machine's LAN address, not `localhost`.
* A fixed deck per run: **10 cards** for smoke, **100 cards** for the standard run, **1000 cards** for
  endurance. Note which deck was used — the numbers are not comparable across decks.
* Screen-off runs: disable the phone's own battery optimisations only if the product intends to
  require that; otherwise record the restriction as a finding.
* Capture at the end: **Diagnostics → Export detailed** (this is the primary evidence —
  `docs/DIAGNOSTICS.md` §6), plus `dumpsys batterystats`, `dumpsys meminfo` and `logcat`.
* Never paste tokens, server credentials or full transcripts into the run log; the export is already
  sanitized, a `logcat` dump is not — check it before attaching.

---

## 4. Cases

Format: **ID — what is proven — environment — expected**.

### A. Session basics

| ID | Case | Env | Expected |
|---|---|---|---|
| RD-A1 | Start a session, answer hands-free, correct, rate it | E-PHONE, E-WIRED | Question spoken once; mic opens after the speech ends; exactly one evaluation and one rating; next question follows |
| RD-A2 | Answer incorrectly | any | Feedback says so; rating still available; no duplicate submission |
| RD-A3 | "Repeat the question" and "give me a hint" | E-PHONE | Spoken commands handled; no answer submitted; mic re-opens or the user can re-open it |
| RD-A4 | Skip a card | any | Exactly one skip reaches the agent; card advances once |
| RD-A5 | Pause mid-question, resume | any | Speech stops immediately; nothing is submitted while paused; resume restores listening without a stale question |
| RD-A6 | End session mid-turn | any | Local finish; no answer/rating sent afterwards; the Diagnostics export shows `finishingIsLocalOnly` when appropriate |
| RD-A7 | Finish a 10-card deck | any | Session reaches a finished state; the microphone is closed; the screen shows the summary |
| RD-A8 | 100-card run, uninterrupted | E-WIRED | No drift: turns == cards, ratings == answers given; no human-visible stalls |

### B. Voice and routing

| ID | Case | Env | Expected |
|---|---|---|---|
| RD-B1 | Bluetooth headset connected before Start | E-BT-SCO | Output and input route to the headset; Diagnostics shows a confirmed/likely route and the detected mode |
| RD-B2 | Bluetooth headset connected **during** a question | E-BT-SCO | No truncated question; route change is recorded; the session stays consistent; the next turn uses the new route |
| RD-B3 | Bluetooth disconnected mid-answer | E-PHONE | Partial audio is never submitted as an answer; the user can retry; app does not wedge |
| RD-B4 | A2DP-only speaker, phone mic | E-BT-A2DP | Hybrid handling: output to the speaker, input from the phone; no crash, no silent failure |
| RD-B5 | Wired headset unplugged during speech | E-WIRED → E-PHONE | Behaviour is defined and visible; nothing is submitted; session continues or pauses per policy, not arbitrarily |
| RD-B6 | Phone mode at high volume (echo worst case) | E-ECHO | No self-echo transcript is submitted; if echo is suspected, Diagnostics reports it |
| RD-B7 | Ambient noise | E-NOISE | No-speech / no-match paths are handled without a false answer; retry is offered |
| RD-B8 | Screen off during a question and during listening | any | Speech continues, the recognizer still gets the turn, the screen-off period does not end the session |
| RD-B9 | Incoming phone call mid-session | E-BT-SCO | Session pauses or is safely suspended; no answer is submitted from call audio; recovery after the call is defined |
| RD-B10 | Silent mode / media volume at zero | any | Observed and recorded: the app cannot fix a muted device, but must not report success when nothing was audible |

### C. Network and reconnection

| ID | Case | Env | Expected |
|---|---|---|---|
| RD-C1 | Wi-Fi off during waiting-for-answer | any | Question is not replayed as a fresh turn; on restore, exactly one answer is submitted |
| RD-C2 | Wi-Fi off while an answer is in flight | any | The submission is not double-sent; ledger state visible in Diagnostics; a retry is possible |
| RD-C3 | Wi-Fi off while waiting for feedback | any | Evaluation arrives after reconnect or the turn times out with a clear error; no phantom rating |
| RD-C4 | Session ends while offline | any | Local finish survives reconnection; a stale server frame does not reopen the session |
| RD-C5 | 2-minute outage | any | Reconnect succeeds; session is usable afterwards; no duplicate answers/ratings |
| RD-C6 | Server process killed and restarted | any | Reconnect + reconcile; the client follows the server's actual state, not its own stale one |
| RD-C7 | Wi-Fi ↔ cellular handover mid-question | any | One question total; no partial answer; latency recorded in the export |

### D. Diagnostics and privacy

| ID | Case | Env | Expected |
|---|---|---|---|
| RD-D1 | Export after a normal session | any | Header has app/build/Android/device/protocol/server versions; **no serials**; all sections present; "not stored (privacy)" where applicable |
| RD-D2 | Export after a failure | any | The failing turn's timeline events, the reducer's rejection reason and the error are present; no transcript text anywhere |
| RD-D3 | Copy summary | any | Short status block + recent timeline; contains no transcript |
| RD-D4 | Clear logs / clear timeline | any | Each clears what it says; the other survives; cleared timeline shows the empty-state text, not a blank area |
| RD-D5 | "Not measured" honesty | fresh install | Latencies with no samples show `-`/`Unknown`, never `0ms` or a fake `0 B` |

### E. Endurance, battery, memory

| ID | Case | Env | Expected |
|---|---|---|---|
| RD-E1 | 1000-card / multi-hour session, screen off, mostly hands-free | E-WIRED | Session completes; no plateau in throughput; no crash; Diagnostics still responsive |
| RD-E2 | Memory after RD-E1 + 10 min idle | any | Heap returns to a stable band; Diagnostics reports peak vs current; no growth proportional to cards |
| RD-E3 | Battery over a fixed 1-hour session, screen off | any | Drain recorded in %/hour in the run log; comparable to the next run of the same case |
| RD-E4 | Same as RD-E1 on the slowest supported device (D2) | E-PHONE | No ANR, no watchdog kill; user-visible pace remains acceptable |
| RD-E5 | 100+ TTS utterances and 100+ STT turns in one session | any | No engine/recognizer leak; TTS/STT never overlap audibly; queue depth returns to 0 between turns |
| RD-E6 | Hot device (sunlight / charging) | any | Thermal throttling is observed and recorded, not silently mis-reported as a network problem |

### F. UX, accessibility, lifecycle

| ID | Case | Env | Expected |
|---|---|---|---|
| RD-F1 | Every control reachable with TalkBack | any | Controls announce their purpose; push-to-talk, rating buttons, pause, end, save, export are all usable |
| RD-F2 | Text scaling at 200 % | any | No clipped or overlapping controls on Dashboard, Study, Settings, Diagnostics |
| RD-F3 | Rotate during a question / during listening | any | Session survives; no duplicate question; microphone state is preserved correctly |
| RD-F4 | Background the app mid-question, return | any | Session state is consistent; no double speech, no orphaned recognizer |
| RD-F5 | Fresh install: permission flows | any | Microphone permission requested in context; denial produces a usable explanation, not a dead screen |
| RD-F6 | Settings round-trip on a real DataStore | any | Toggles persist across process death; corrupt/legacy data does not wipe unrelated settings |
| RD-F7 | Server unreachable at Start | any | A clear, actionable error; no half-started session left in the UI |

---

## 5. Recording results

For each executed case, add a row to §7 with:

```
case | device slot (model + Android version) | env code | build (version + build type + git sha)
     | result PASS/FAIL/BLOCKED | deck size | evidence (export file name, dumpsys, logcat)
     | notes (expected-vs-observed when failing)
```

Rules:

* **`FAIL` requires evidence**: the exported diagnostics (or the relevant section pasted verbatim)
  and, where possible, a logcat window around the failure.
* **`BLOCKED` is a real result** (no headset available, no second device). Record who/what is needed
  rather than silently marking PASS.
* A failure that reproduces in the mock-agent configuration should be turned into a JVM or
  instrumented test at the lowest layer that can reproduce it, then fixed — the device run is not a
  substitute for the fix (`docs/TESTING_STRATEGY.md`, layer rule).
* A failing case is never removed from the matrix; it becomes the reason the next build is red.

**Release gate (per release, not per commit):** all of §A and §C cases on D1 in E-PHONE and E-WIRED;
all of §B on D1 where the hardware exists; §D on D1; one endurance run (§E1–E3); and §F on D1. D2
must complete §A and §E4 at least once per release cycle, since it sets the budget.

---

## 6. Ownership of the device runs

| Area | Cases | Notes |
|---|---|---|
| Session correctness | A, C | Highest risk (`docs/TESTING_STRATEGY.md` risk priority) — run before any release |
| Audio/voice | B | Needs the actual headsets; collect them once and keep them with the test phone |
| Diagnostics/privacy | D | Can be done by anyone with a build; the export is the evidence |
| Endurance/battery | E | Long-running; schedule, do not squeeze in |
| UX/accessibility | F | TalkBack run must be done by someone who uses it, or with the accessibility checklist open |

---

## 7. Run log

| Case | Device | Env | Build (sha, type) | Result | Deck | Evidence | Notes |
|---|---|---|---|---|---|---|---|
| — | — | — | — | *not run — no device available in the development sandbox* | — | — | This matrix has not been executed yet; the first execution populates this table |

---

## 8. Known platform risks to watch while running this matrix

* **OEM background restrictions** can kill a foreground service or delay a wake lock — record the OEM
  build number when a case fails only on D3.
* **Recognizer availability** is not universal; `capabilities` may legitimately show `Unknown`. The
  product must degrade to push-to-talk rather than fail.
* **Bluetooth transitions are not atomic**: expect a window where output and input disagree. The
  policy is to never submit partial audio during that window, and to make the window visible in
  Diagnostics.
* **A2DP-only devices have no microphone**, so "headset mode" is not a single state; the hybrid path
  (§B4) must stay a supported configuration.
* **Thermal throttling looks like a network problem** if you only read latency. Check
  `NetworkPerformance.lastPingRttMs` against local latencies before blaming the PC agent.
