# Diagnostics & Observability

**What this document covers:** what the app records about itself, where it goes, what a bug report
contains, and — just as importantly — what it deliberately never records.

Diagnostics exist for one reason: when a study session goes wrong on someone else's phone, weeks
after the fact, the artifact has to be enough to explain it. That requirement shapes every decision
here: structured beats textual, counters beat prose, and a missing measurement is reported as
missing rather than as zero.

---

## 1. The four surfaces

| Surface | Where | Bounded by | Read by |
|---|---|---|---|
| Log rows | `AppLogger` (process-wide ring) | 500 rows, 1500 chars/row, coalesced publication | Diagnostics screen, detailed export |
| Event timeline | `DiagnosticTimeline` (structured) | 500 events (configurable), 200 in an export | Diagnostics screen, exports, tests |
| Metrics | `PerformanceMetrics`, `NetworkStats`, TTS/STT health snapshots | fixed windows (128 samples/family) + plain counters | Performance section, summary |
| Session snapshot | `SessionDiagnosticsSnapshot` + `MachineResourceCounts` | derived on read | Session section, exports, tests |

Nothing here is persisted and nothing is transmitted: diagnostics live and die with the process
(§89/§161). There is no remote analytics, no crash-reporting SDK and no telemetry endpoint, by
design — see §219 in the security notes for the same rule stated as policy.

---

## 2. The event timeline

`core/diagnostics/DiagnosticTimeline.kt`

```
DiagnosticEvent(timestamp, category, event, sessionEpoch, turnId, requestId, metadata, sequence)
```

* **Categories:** `SESSION`, `NETWORK`, `VOICE`, `TTS`, `STT`, `AUDIO`, `PERFORMANCE`, `APP`.
* **Identity:** `sessionEpoch` distinguishes sessions; `turnId` (`epoch:cardId:generation`) ties an
  event to one card turn even when the same card is repeated; `requestId` ties a TTS utterance or an
  STT turn to its caller.
* **`sequence`** is monotonic and unique even for events in the same millisecond — it is the
  `LazyColumn` key on the Diagnostics screen and the stable reference in a failure report.
* **Metadata** is identifiers and counts only, sanitized on the way in, capped at 8 entries ×
  24-char keys × 48-char values.
* **Bounded:** the oldest event is overwritten when the buffer is full, so a 1000-card session leaves
  exactly `capacity` events behind. High-frequency telemetry (RMS levels, partial transcripts) is
  never recorded: only state changes and lifecycle milestones are.

Event vocabulary (the names a report will show):

| Category | Events |
|---|---|
| SESSION | `SESSION_START_REQUESTED`, `SESSION_STARTED`, `QUESTION_RECEIVED`, `EVALUATION_RECEIVED`, `RATING_SAVED`, `SESSION_PAUSED`, `SESSION_RESUMED`, `SESSION_FINISHED`, `PAUSE_SENT`, `RESUME_SENT`, `END_SENT`, `EVENT_REJECTED`, `INVARIANT_VIOLATION` |
| NETWORK | `START_SESSION_SENT`, `ANSWER_SENT`, `RATING_SENT`, `SEND_FAILED`, `CONNECTION_LOST`, `CONNECTION_RESTORED` |
| Voice | `ROUTE_LOST`, `ROUTE_CHANGED` |
| TTS | `TTS_START`, `TTS_DONE`, `TTS_FAILED`, `TTS_CANCELLED` |
| STT | `STT_READY`, `STT_FINAL`, `STT_FAILED` |

**No event carries content.** An answer contributes `chars=57`, a question contributes an
abbreviated card id, a frame contributes its type and size. This is why the export privacy test can
assert that a clinical transcript never appears in an export: the transcript is not there to begin
with, not filtered out afterwards (§83).

---

## 3. Metrics model

`core/diagnostics/PerformanceMetrics.kt` — one object, one snapshot, no scattered counters:

```
PerformanceSnapshot(
  session: SessionPerformance(turns, answersSubmitted, ratingsSubmitted,
              startToRequest, questionToSpeechStart, speechDoneToListen,
              sttFinalize, evaluationRoundTrip, ratingToNextQuestion, sessionDurationMs),
  tts:     TtsPerformance(requestsCompleted, requestsFailed, requestsCancelled,
              queueDepth, lastRequestToStartMs),
  stt:     SttPerformance(turnsCompleted, turnsFailed, noSpeech, noMatch, busy,
              rateLimited, staleCallbacksDropped, activeRequests,
              lastReadyLatencyMs, lastFinalizeLatencyMs),
  network: NetworkPerformance(messagesSent, messagesReceived, sendFailures, reconnects,
              lastPingRttMs, lastMessageAgeMs, messagesPerMinute),
  audio:   AudioPerformance(handoff, routeInterruptions, suspectedSelfEcho,
              phoneTurns, headsetTurns),
  memory:  RuntimePerformance(usedHeapBytes, maxHeapBytes, peakUsedHeapBytes, pssBytes, sampledAtMs)
)
```

Rules that make these numbers trustworthy:

1. **Bounded windows.** Each latency family is a fixed `LatencyWindow` (128 samples) plus lifetime
   counters. Percentiles come from the window; the count comes from the session. Memory cost is
   constant regardless of session length (§54/§137).
2. **Honest absence.** A family with no samples reports `-` (`DiagnosticsFormatting.NOT_MEASURED`),
   never `0ms`. `null` heap/PSS render `Unknown`, never `0 B`. A monitor that cannot determine a
   capability shows `Unknown`, not `No`.
3. **Rates have denominators.** Failure counts are rendered as `15 / 427 (3.5%)`; a bare numerator
   is not a rate (§165).
4. **Local vs remote latency are separate numbers.** `speechDoneToListen` (handoff, local) is never
   mixed with `evaluationRoundTrip` (network + PC agent). Presenting network wait as UI cost is the
   single most common way a latency dashboard lies.
5. **Nothing here changes behaviour.** Metrics are consumed by Diagnostics and tests only; no
   decision in the session machine reads a metric.
6. **Reset is explicit.** `PerformanceMetrics.reset()` clears every counter and window; the object is
   otherwise process-lifetime, so a Diagnostics reader can see a whole app run. Session-scoped
   reading is available through the same snapshot.

`NetworkStats` is separate on purpose: it is written from the connection layer (frames sent,
received, refused; reconnect attempts; ping RTT; last message age/type; message rate per minute) and
read by the Network/Protocol sections. `messagesPerMinute` is the cheapest detector for an
unintended polling loop — accidental chatter shows up as a rate, not as an error.

---

## 4. Privacy model

Two layers, in this order:

**Layer 1 — do not collect it.** The session machine's diagnostics record shapes, never content:
`ANSWER_SENT chars=57`, `STT_FINAL purpose=ANSWER chars=57`, `QUESTION_RECEIVED card=card-1`,
`SEND_FAILED type=submit_answer`. Questions, answers and feedback are never passed to the logger or
the timeline.

**Layer 2 — redact what could still arrive.** Anything a developer logs by hand goes through
`LogSanitizer` *before storage, before logcat and before export*: `Authorization: Bearer …`, JWT-shaped
values, and `token`/`api_key`/`password`/`secret` fields in JSON, query-string or `key=value` form.
Patterns are precompiled once and applied only when they can match. Redaction is visible
(`***REDACTED***`) so a reader can tell "removed" from "never logged".

Never included in an export, by construction:

* auth tokens, bearer headers, API keys, passwords, private server credentials;
* full answer transcripts, full medical questions, full AI feedback;
* raw protocol frames (the export says `Raw frames: not stored (privacy)`);
* device serial numbers or advertising ids — the header carries the app version, build type, Android
  version, device model, protocol version and server version.

An export header looks like:

```
=== Study Agent Diagnostics Export ===
Generated: 1780000000000
App: 1.0.0 (debug)
Android: Android 14 (API 34)
Device: Pixel 7
Protocol: 2 (server 2.4.0)
```

Every one of those lines is asserted by `DiagnosticsExportPrivacyTest`, including the negative
assertions (`My patient has…` and the fake secrets must not appear).

---

## 5. Log-level policy

| Level | Use | Retained |
|---|---|---|
| `DEBUG` | Developer detail; voice/normal retries | Only when `AppLogger.isDebugEnabled` (default: `BuildConfig.DEBUG`, or the in-app debug-logging setting) |
| `INFO` | Lifecycle milestones worth having in a user's export | Always |
| `WARN` | Recoverable failure the user may notice (a refused send, a discarded draft) | Always, published immediately |
| `ERROR` | Something that breaks a user-visible promise | Always, published immediately |

Consequences that the code enforces:

* `DEBUG` rows are dropped before formatting when disabled — a release build pays almost nothing.
* A burst of routine rows is published to the UI at most once per 200 ms; `WARN`/`ERROR` publish
  immediately, because a failure must be visible now, not in 200 ms.
* Normal retries are not errors: a reconnecting socket logs `INFO`/`DEBUG`, an exhausted reconnect
  budget logs `WARN`.
* No study data in release logs is a *structural* property (see §4), not a discipline.

---

## 6. Using diagnostics while debugging

The Diagnostics screen is organised by the question being asked, in the order the questions usually
come up:

1. **Session** — phase, epoch, card, turn (generation + id), pending action and its age, paused /
   recovering, last accepted and last rejected event *with the reducer's reason*, ledger and in-flight
   counts, dedup window size, bounded-history sizes, pending transcript/rating confirmation, current
   error.
2. **Network** — connection label, profile, endpoint, reconnect attempt/count, connect latency, ping
   interval and last RTT, last message age and type, message counters, send failures, traffic rate,
   last protocol error.
3. **Protocol** — negotiated protocol version and status, server name/version, the advertised
   capability list, last server message, last protocol error, and an explicit statement that raw
   frames are not stored.
4. **TTS / Study Audio Routing / Speech Recognition** — engine, voices, queue depth, metrics, last
   error; the effective route, certainty, attention state; recognizer availability, backend, model
   state per language, active request and age, retries, last confidence and error, latency averages,
   turn counts, and how many stale callbacks were dropped.
5. **Performance** — the metric model from §3, including pending timers and events awaiting
   processing (the two counters that answer "is something stuck?").
6. **Dashboard / Control Center / Persistence** — capability state, snapshot source and age, draft
   presence, save state, settings schema version, last settings write error, cache ages.

Operations:

* **Filter/search** the log rows and the timeline (by category on the timeline; free text on rows).
* **Copy summary** (`getSummaryText`) — a short status block plus the last 20 timeline events, no
  log rows. This is what a user should paste into a chat.
* **Export detailed** (`getFormattedLogsText`) — header, every section above, the bounded timeline
  (last 200 events) and the sanitized log rows. The export flushes the coalescer first, so it can
  never show a stale buffer.
* **Clear logs** and **Clear timeline** are separate: logs are troubleshooting chatter, the timeline
  is the session's own history. The UI states which is being cleared, and a cleared timeline renders
  as `(no diagnostic events recorded)` rather than as an empty screen.
* **Metric reset semantics:** `PerformanceMetrics.reset()` and `NetworkStats.reset()` exist for tests
  and for an explicit "start counting from here" action; nothing in the session path calls them, so
  the numbers a user exports describe the whole app run.

---

## 7. Crash breadcrumbs (sanitized)

If a session ends abruptly, what survives is exactly what §2–§4 describe and nothing more: the log
ring (up to 500 sanitized rows) and the timeline (up to 500 structured events) in memory, plus
`finishingIsLocalOnly` and the last error in the session diagnostics if the session had already begun
to shut down. There is no crash-reporting SDK and no upload: a breadcrumb is useful only after a user
chooses to export it. When that export is produced, it is the same artifact described above — which
is why the failure-report tests (`DiagnosticsExportPrivacyTest`) are part of the fast CI gate rather
than an afterthought.

---

## 8. Connection Diagnostics (Enhanced)

### Staged Connection Test

The Connection screen provides a staged test that reports per-stage success/failure:

```
1. Network (Android has usable network)
2. DNS/IP reachability (can resolve host)
3. Transport (WebSocket upgrade)
4. Study Agent handshake (welcome received)
5. Protocol (negotiated version)
6. Authentication (Bearer or legacy frame)
7. Anki (if capability advertised)
8. AI (if capability advertised)
```

Each stage maps to ConnectionProblem with userMessage and userAction:

```
PC reachable ✓
WebSocket ✓
Study Agent handshake ✓
Authentication ✕ -> "The PC Agent is running, but the token was rejected." + "Edit token or pair again"
```

Technical details in Diagnostics, not in user-facing banner.

### Developer Diagnostics (Connection Screen -> Diagnostics)

```
Transport: WebSocket
Connection generation: 42 (prevents late callbacks from stale connections)
Protocol: 2 (selected via welcome.selected_protocol)
Agent: StudyPC-Agent 2.3.0 (server_name + server_version from welcome)
Agent ID: persistent-uuid (recognizes same agent at new address)
Capabilities: dashboard, study_config, session_recovery, etc.
Authentication: Bearer • authenticated (never token)
Ping: 14ms (correlated via in_reply_to, not confused by delayed pongs)
Last message: 2.1s ago (liveness detection)
Reconnect attempts: 0 (bounded exponential backoff + jitter)
Network type: WIFI/CELLULAR/VPN
Problem: typed ConnectionProblem if any
```

### Copy Diagnostics (Sanitized)

Generates:

```
App: 1.0.0
Agent: 2.3.0
Protocol: 2
Connection: Ready
Transport: WSS
Host: private/redacted where appropriate
Latency: 14ms
Anki: Ready
AI: Ready
Last Error: none
Support ID: short random local to export, no remote tracking
```

No token, no raw frames, no transcripts.

### Observability Metrics

- DNS/resolve time where observable
- Connect time
- WebSocket upgrade time
- Handshake time (hello -> welcome)
- Auth time
- Ready time (Connect tapped -> Agent Ready, separate from socket opened)
- Ping RTT (correlated)
- Reconnect duration
- Request RTT by type (dashboard, decks, config, session start, evaluation, rating) - don't mix LLM evaluation latency with simple local API

NetworkStats enhanced with:

```
state, profileName, host, port, transport,
reconnectAttempt, maxReconnectAttempts, reconnectCount,
pingIntervalSeconds, lastPingRttMs, connectLatencyMs,
lastMessageAgeMs, messagesSent, messagesReceived, sendFailures,
lastMessageType, lastProtocolError, messagesPerMinute (detects polling loops)
```

### Connection Problem Model

Typed reasons:

```
NetworkMissing, DnsFailure, ConnectionRefused, Timeout, TlsFailure,
AuthenticationRejected, ProtocolMismatch, HandshakeTimeout, AgentBusy,
AgentNotStudyAgent (wrong service), Unknown
```

UI maps problem to action:

```
Connection refused -> Start PC Agent
Authentication rejected -> Edit token
TLS failed -> Check secure server address/certificate
Protocol incompatible -> Update app/agent
Network missing -> Check Wi-Fi/Tailscale
Handshake timeout -> Verify endpoint is Study Agent, not other service
```

Do NOT display exception strings to users - technical details in Diagnostics.

## 9. What is not instrumented (and why)

* **Per-frame RMS levels and partial transcripts.** They are the highest-volume signals in the app and
  the least diagnostic after the fact; recording them would drown the timeline (§70/§77/§78).
* **Every timestamp.** Time is injected only where a decision depends on it; wrapping every
  timestamp in a `TimeProvider` would be ceremony without a testable behaviour.
* **Memory or CPU sampled on a timer.** Sampling every few milliseconds changes what it measures.
  Memory is sampled when a snapshot is requested, and the peak is tracked from those samples.
* **Question/answer text, anywhere.** See §4.
* **User identity, device identifiers, location.** Not collected at all.
