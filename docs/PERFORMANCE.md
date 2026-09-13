# Performance: modelling, measurement and budgets

**Audience:** anyone changing the voice loop, the session machine, the connection layer or the
diagnostics path — and anyone who has to decide whether a release is "fast enough".

> **Measurement status.** No number in this document is presented as a measurement of this app,
> because none has been taken yet: the development sandbox has no JDK/Android SDK and no network
> egress, and a JVM cannot produce phone timings anyway (§2 of the brief forbids inventing budgets,
> §178 forbids benchmarking only debug builds). What follows is the *model*, the *instrumentation*,
> and the *procedure* by which real budgets are established on a device. The placeholders in §7 are
> filled in by the first baseline run and are explicitly marked as unmeasured until then.

---

## 1. Principles

1. **Measure before optimising.** A change that claims to be faster must come with a before/after
   from the same measurement path, or it is a guess.
2. **Budgets come from a baseline, not from ambition.** A budget is the measured p95 on the slowest
   supported device, plus a stated margin — not a round number someone liked.
3. **Measure what the user feels.** Time-to-hear-the-question, the gap between finishing an answer
   and hearing feedback, and whether the app stays responsive over hours.
4. **Never present network wait as UI cost.** Local processing latency and PC-agent latency are
   measured, stored and displayed separately.
5. **Metrics must not become the workload.** Every family is a fixed window plus counters; recording
   is a few array writes under a short lock, and nothing samples on a timer.

---

## 2. What is measured, per family

All of it lives in `PerformanceMetrics` (see `docs/DIAGNOSTICS.md` §3) and is surfaced verbatim in
the Diagnostics *Performance* section and the summary.

| Family | Signal | Why it matters to a user |
|---|---|---|
| Session | `turns`, `answersSubmitted`, `ratingsSubmitted`, `sessionDurationMs` | The denominator for every rate; a session that reports 0 turns is the first sign something never started |
| Start | `startToRequest` (tap → `start_session` written) | "I pressed Start and nothing happened" |
| Question | `questionToSpeechStart` (frame received → engine actually began speaking) | The delay before the first word the user hears |
| Handoff | `speechDoneToListen` (utterance terminal → microphone open, incl. acoustic gap) | The pause between the question ending and the user being able to answer |
| STT | `sttFinalize` (`onEndOfSpeech` → terminal transcript) | The pause after the user stops talking |
| Feedback | `evaluationRoundTrip` (answer written → evaluation received) | **Network + PC agent**, not UI cost |
| Rating | `ratingToNextQuestion` | Perceived responsiveness of the next card |
| TTS | completed / failed / cancelled, queue depth, request→start | Voice reliability at scale |
| STT | completed / failed / no-speech / no-match / busy / rate-limited / stale dropped, active requests, ready latency | Why a turn did not produce an answer |
| Audio | handoff window, route interruptions, suspected self-echo, phone vs headset turns | Half-duplex and echo behaviour on real hardware |
| Network | messages sent/received/failed, reconnects, ping RTT, last message age, msg/min | Chatter, dead sockets, accidental polling |
| Memory | used heap, max heap, **peak observed**, PSS (when available) | Multi-hour stability |

Latency reporting uses `count`, `min`, `average`, `p50`, `p95`, `max` from a **bounded window**
(`LatencyWindow`, 128 samples) plus a lifetime count. The window is what makes p95 stable and
memory constant; the lifetime count is what makes `n=` honest.

---

## 3. Local versus remote

The two must never be conflated:

| Measured locally | Measured remotely (must be labelled as such) |
|---|---|
| `startToRequest`, `questionToSpeechStart`, `speechDoneToListen`, `sttFinalize`, handoff, memory, queue depth, all counters | `evaluationRoundTrip`, `ratingToNextQuestion`, ping RTT, connect latency |

A UI that shows "feedback latency 4.2s" when the PC agent took 4.1s of that is actively misleading —
it sends an engineer to optimise the phone. The Diagnostics export labels the two groups separately,
and `DiagnosticsFormatting.rate` exists so a failure is never shown without its denominator (§165).

---

## 4. Memory and long sessions

What "memory stable" means here, and how it is proven:

* **Bounded by construction** — the log ring (500 rows), the timeline (500 events), the dedup window
  (200 ids), the transition ring (200), the turn-history ring (64), the submission ledger (pruned to
  the last 20 turn ids), the timeout-job map (entries removed on completion), latency windows
  (7 × 128 longs), `NetworkStats` (counters only). None of these grow with session length.
* **Proven by the endurance suite** (`study/StudySessionEnduranceTest`) — 1000 cards with resource
  assertions every 100 cards and a final quiescence check (`pendingTimers == 0`,
  `eventsAwaitingProcessing == 0`, no active speech/recognition effects).
* **Proven for the log buffer specifically** (`core/AppLoggerHardeningTest`) — 10 000 rows leave
  exactly 500 retained, and the per-row cost does not grow with buffer size (timing guards are two
  orders of magnitude above JVM reality, so they catch a return to an O(n) append, not a slow CI box).
* **Peak tracking** — `RuntimePerformance.peakUsedHeapBytes` remembers the highest heap value seen
  across snapshots, which is what a "the app got fat over two hours" report needs.

What is *not* claimed: exact PSS/leak behaviour. That needs a device, `Debug.MemoryInfo` sampling and
ideally a heap dump after a multi-hour run (procedure in §6). LeakCanary is optional and debug-only;
it is never a release dependency (§150/§177).

---

## 5. Battery and radio discipline

These are properties to *check*, not numbers to assert in a unit test:

* **No polling.** The client reacts to the socket and to user actions. `NetworkStats.messagesPerMinute`
  exists to make an accidental poll visible — a study session should show a rate in the low single
  digits, not hundreds.
* **Ping interval is a setting, not a constant of nature** (`pingIntervalSeconds`, floor enforced by
  `AppSettingsPolicy.MIN_PING_INTERVAL_SECONDS`); the reconnect backoff is bounded with jitter
  (`ReconnectController`), so a hundred phones waking up after a Wi-Fi drop do not stampede the agent.
* **Wake locks / foreground service**: the session is expected to keep running with the screen off, so
  whatever holds the CPU must be scoped to the session and released on pause, finish and route loss.
  Audit points: acquire/release symmetry, release on `SessionPhase.Finished`, release on
  `ConnectionState` loss, and no wake lock held while `Paused`. (Audit results belong in the
  real-device report, not here.)
* **Screen-off behaviour** is a device-level claim: the session must keep speaking and listening, and
  battery drain must be measured over a fixed multi-hour run (§6).
* **No background work when idle**: with no session and no socket, the app should be at rest.

---

## 6. How to take a baseline (procedure)

On a **release build, screen off, same deck, same network** — one variable at a time:

```bash
# 1. Build and install a release-like build (minification is currently off; state it in the report)
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# 2. Clear, then start the run
adb shell am force-stop com.studyagent.client
adb logcat -c
adb shell dumpsys batterystats --reset

# 3. Run the session (scripted answers via push-to-talk, or hands-free with the mock agent)
#    For a repeatable load, point the client at server/mock_pc_agent.py and use a fixed deck.

# 4. Capture at the end, from the app: Diagnostics → Export detailed
#    Then: adb shell dumpsys batterystats com.studyagent.client > batterystats.txt
#          adb shell dumpsys meminfo com.studyagent.client  > meminfo.txt
#          adb logcat -d > logcat.txt

# 5. Record 'post-session memory' after 10 minutes idle, to separate a leak from a cache.
```

Record, for each run: device + Android version, build type, session length, cards reviewed,
utterances and transcripts, the export (which contains every metric above), and the raw
`batterystats`/`meminfo` output. A baseline is only comparable to another run with the same
device, build type and load.

**Percentiles, not averages.** Compare p50 (the typical answer) and p95 (the answer that makes a user
say "it feels slow"). A change that improves the average while worsening p95 is a regression.

---

## 7. Budgets (to be filled from the baseline)

These are the *slots* a budget must fill; they are deliberately empty rather than invented. Each row
gets its number from the first baseline run on the slowest supported device, plus the stated margin.

| Budget | Source metric | Target | Measured |
|---|---|---|---|
| Speech starts within X ms of the question frame | `questionToSpeechStart.p95` | p95 ≤ X ms | *unmeasured* |
| Microphone open within X ms of the utterance ending (incl. acoustic gap) | `speechDoneToListen.p95` | p95 ≤ X ms | *unmeasured* |
| Transcript finalised within X ms of end-of-speech | `sttFinalize.p95` | p95 ≤ X ms | *unmeasured* |
| Start-tap acknowledged within X ms | `startToRequest.p95` | p95 ≤ X ms | *unmeasured* |
| Memory growth over a 2-hour session | `memory.peakUsedHeapBytes` delta | ≤ X MB | *unmeasured* |
| No drop in session throughput over 4 hours | `session.turns` vs elapsed | no plateau | *unmeasured* |
| Idle battery drain with a live session, screen off | `batterystats` | ≤ X %/hour | *unmeasured* |
| Message rate while idle-but-connected | `network.messagesPerMinute` | ≤ X/min | *unmeasured* |

The comparison rule once the table is filled: a change may not make any p95 worse by more than 10 %
or any budget's absolute value exceed the target, unless the PR states why and what was traded.

---

## 8. Benchmarking: debug vs release-like, and what we do not do

* Debug builds are for correctness (they include assertions, unminified code and a live Compose
  tooling connection) — a debug number is a smoke signal, never a budget evidence. Any budget claim
  must come from a release build, and the build type is part of the report (§177: no debug-only
  benchmarks).
* Microbenchmarking of trivial helpers is out of scope; a targeted benchmark is added only when a
  measurement path exists to justify it. Baseline Profiles are optional (they are never a correctness
  prerequisite).
* No profiling dependency ships in release: heap/CPU tooling is `debugImplementation` only, and
  LeakCanary (if enabled at all) must be impossible to include in a release build.
* The only timing assertions in the fast suite are *guards*, not benchmarks: the endurance suite fails
  if a virtual-time run needs real minutes (i.e. if someone introduced a real sleep), and
  `AppLoggerHardeningTest` fails if appending becomes O(n). Both are stated as guards in their
  comments so nobody mistakes them for performance budgets.

---

## 9. Perf alerts

There is no automatic alerting: the app has no telemetry, and adding a threshold that pings somebody
is how a mobile app ends up with a monitoring bill and a privacy incident (§177: no remote telemetry
by default). What exists instead:

* the Diagnostics *Performance* section, which a user can export when something feels wrong;
* a written record in this document when a budget is missed on a real device run;
* a CI-visible canary for the two pathologies that are detectable without a device: unbounded
  structures (endurance suite) and per-append cost growth (logger suite).

If a threshold is ever alerted on, it must come from a measured baseline on a named device with a
stated margin — and it must be reviewable, so it can be raised when the hardware changes.

---

## 10. Known gaps

* No p95 numbers yet (see the status note at the top).
* Thermal throttling and low-battery behaviour are not modelled; they belong to the device matrix.
* Audio latency (engine start, output buffer) is only measured end-to-end as
  `questionToSpeechStart`; per-stage engine timings are the TTS adapter's own metrics and are not
  merged into the session view.
* No baseline for a Wi-Fi↔LTE handover mid-turn; the reconnect path is covered by tests and by the
  device matrix, but the *latency distribution* of that switch is unmeasured.
