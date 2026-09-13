# Dashboard — Operational Command Center

The Home screen is a **live operational dashboard**, not a static launch page. It answers:

> Is my system ready? What should I study? What is due? How am I doing? Am I on pace?
> Where am I weak? What does the agent recommend? Is a session already running?

## Data ownership

The PC Study Agent is the **only** source of study truth. Android never invents,
estimates or computes study metrics (Anki counts, recall, pace, AI cost). The data path is:

```
PC Study Agent → Protocol v2 (WebSocket) → DashboardRepository → HomeViewModel (DashboardUiState) → Compose
```

Every panel renders nullable server models; missing fields are hidden, not zero-filled
(unless zero is the authoritative server value).

## Architecture

| Layer | Type | Responsibility |
|---|---|---|
| `CapabilityStore` | repository | One authoritative protocol/capability state for the whole app. |
| `DefaultDashboardRepository` | repository | Requests, coalescing, timeouts, out-of-order guard, cache, push merges. |
| `HomeViewModel` | viewmodel | Combines connection/capabilities/dashboard/session/config into one `DashboardUiState`. |
| `HomeScreen` + `components/` | UI | Capability-gated cards; LazyColumn; accessible charts. |

### Capability negotiation

On connect the store enters `NEGOTIATING`. A v2 server answers the hello exchange with a
`capabilities` frame → `NEGOTIATED_V2` with the advertised set. If no frame arrives within
4 s the server is treated as `LEGACY_V1`: basic study keeps working, the advanced panels
are gated off instead of failing.

Known capabilities: `dashboard`, `deck_list`, `study_config`, `history`,
`component_health`, `learning_insights`, `ai_usage`, `session_progress`.

Panels are rendered only when the capability exists. Unsupported surfaces show
“Not supported by this Study Agent” (or stay hidden when the whole protocol is v1).
Example: AI usage is never rendered as `$0.00` when the server does not provide cost —
it shows “Cost unavailable” or nothing at all.

### Refresh policy (no polling)

A dashboard request happens only on:

- capabilities negotiated (after connect/authentication),
- Home becoming active **if** data is missing/stale (>2 min),
- manual refresh,
- session finished,
- coalesced follow-up after an in-flight request while more refreshes were queued.

Opening Home never triggers LLM/insight generation. Insights are server-generated
artifacts rendered with their generation time; only the explicit refresh button
requests a new one.

### Request hygiene

- **Single-flight + coalescing:** at most one `request_dashboard` in flight; rapid
  refreshes collapse into one follow-up request.
- **Bounded timeout:** 8 s, then `TimedOut` error state (cached data stays visible).
- **Out-of-order protection:** unsolicited/stale snapshots only replace current data
  when their `generated_at` is newer; matched responses complete their generation.
- **Panel-scoped merges:** `deck_list`, `study_history`, `learning_insight`,
  `ai_usage_stats`, `component_health` each update only their own panel.
- **Push updates:** `session_started` / `session_progress` / `session_stats` /
  `session_paused` / `session_resumed` patch the active-session panel live — no
  full-dashboard request per card.

### Freshness (visible, honest)

`FreshnessPolicy` maps `(hasData, isConnected, updatedAt, now)` to
`Loading / Live / Cached / Stale / Unavailable`. The header always shows the label,
e.g. `Live • updated now`, `Cached • updated 12 min ago`. Cached data is **never**
presented as live.

### Offline cache

The last valid snapshot and deck list are persisted (DataStore JSON). While
disconnected the dashboard shows the cached snapshot behind an **Offline** banner
(“Showing dashboard from …”, Reconnect action). On reconnect a fresh snapshot is
requested and replaces the cache — the cache is never the source of truth.

## Information hierarchy

1. System readiness (PC Agent / Anki / AI / Audio)
2. Start / Resume (primary smart action) or Active session controller
3. Active deck (server deck list, nested names preserved)
4. Today stats + daily goal (only when server-configured)
5. Goal progress (server-authoritative pace: ahead/on track/behind)
6. Weekly performance (Canvas chart + accessible text values; Cards/Recall/Time)
7. Rating distribution (Today / 7 days / 30 days)
8. Learning insight (server-generated, with freshness)
9. Recommendation (advisory; “Use recommendation” edits the Control Center draft)
10. AI usage (capability-gated; server-provided cost only)

## Component health rules

- WebSocket connected **≠** Anki ready **≠** LLM ready. Only `component_health`
  from the server counts; without it Anki/AI render as **Unknown**.
- PC Agent readiness is the transport connection itself (the one legitimate derivation).
- Statuses render icon + label + text (Ready/Connecting/Warning/Unavailable/Error/Unknown)
  — never color alone.

## Smart start / resume

Primary button states: `CONNECT → CONNECTING → START → STARTING → RESUME STUDY / RESUME SESSION`.

- Start sends the Control Center’s current configuration (`deck`, `mode`, `config`) —
  never a hardcoded deck, never a forced `review_due` on v2.
- Resume is navigation-only: it never sends another `start_session`.
- Duplicate taps are impossible: the button disables during `STARTING`, and the session
  state machine rejects starts while a session is active or starting.
- While a session runs the dashboard shows the Active Session card (progress, recall,
  time, Resume/Pause) instead of a start button.

## Error & empty states

Each failure mode is actionable: `No connection` (Connect), `Request timed out` (Retry),
`Dashboard request failed` (Retry), `Dashboard not supported` (informational). An empty
Anki collection renders a useful empty state instead of `0 / 0 / 0% / $0` everywhere.
