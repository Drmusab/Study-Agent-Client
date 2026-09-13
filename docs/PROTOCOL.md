# Study Agent Protocol Specification (v1)

## 1. Overview
The Study Agent Protocol operates over WebSocket connections (WS or WSS). It uses UTF-8 encoded JSON messages.

Every envelope message contains standard metadata:
* `protocol_version`: `"1"`
* `message_id`: A unique UUID string generated for idempotency.
* `session_id`: Active session UUID string (nullable for initial connection messages).
* `type`: The message discriminator string.
* `timestamp`: ISO-8601 UTC timestamp string (e.g. `2026-09-10T00:30:00.000Z`).

---

## 2. Client → Server Messages

### 2.1 `hello`
Sent immediately upon WebSocket connection to establish identity.
```json
{
  "protocol_version": "1",
  "message_id": "8a32a688-21d4-47d3-8bf6-a4f6bebfcb97",
  "type": "hello",
  "timestamp": "2026-09-10T00:30:00.000Z",
  "client_name": "StudyAgent-Android",
  "client_version": "1.0.0"
}
```

### 2.2 `authenticate`
Sent if the server requires an API auth token.
```json
{
  "protocol_version": "1",
  "message_id": "b18274a2-cf29-4ee4-90a4-325fb080f7aa",
  "type": "authenticate",
  "token": "sec_tok_991823abf892",
  "timestamp": "2026-09-10T00:30:01.000Z"
}
```

### 2.3 `start_session`
Requests the PC agent to start a new study session.
```json
{
  "protocol_version": "1",
  "message_id": "592a8cf2-ecf5-4dc1-a20d-85f0e9b9cfca",
  "type": "start_session",
  "deck": "Toronto Notes",
  "mode": "review_due",
  "timestamp": "2026-09-10T00:30:02.000Z"
}
```

### 2.4 `submit_answer`
Transfers the user's spoken transcript for evaluation.
```json
{
  "protocol_version": "1",
  "message_id": "2da123f8-62d4-4a4b-ba85-3bc556481cc5",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "type": "submit_answer",
  "text": "Volume greater than thirty cubic centimeters and midline shift over five millimeters.",
  "timestamp": "2026-09-10T00:30:15.000Z"
}
```

### 2.5 `rate_card`
Submits the spaced repetition rating (`again`, `hard`, `good`, `easy`).
```json
{
  "protocol_version": "1",
  "message_id": "d13f990a-17cf-451e-9273-df01b7a63445",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "type": "rate_card",
  "rating": "hard",
  "timestamp": "2026-09-10T00:30:25.000Z"
}
```

### 2.6 Interactive Study Requests
* `repeat_question`: Asks the server to resend the question.
* `request_hint`: Asks for a clinical/conceptual hint.
* `request_explanation`: Asks for an expanded rationale.
* `request_answer`: Requests full answer reveal.
* `skip_card`: Skips the current card without updating Anki schedule.
* `pause_session` / `resume_session`: Pauses or resumes session timer and state.
* `end_session`: Concludes active session.
* `ping`: Heartbeat ping to measure latency and test connection liveness.

---

## 3. Server → Client Messages

### 3.1 `session_started`
Confirms session creation.
```json
{
  "protocol_version": "1",
  "type": "session_started",
  "session_id": "sess-4a81",
  "deck": "Toronto Notes",
  "total_cards": 183,
  "timestamp": "2026-09-10T00:30:03.000Z"
}
```

### 3.2 `question`
Delivers a card question.
```json
{
  "protocol_version": "1",
  "type": "question",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "question": "What are the indications for evacuation of an epidural hematoma?",
  "card_number": 1,
  "remaining": 182,
  "speak": true,
  "timestamp": "2026-09-10T00:30:04.000Z"
}
```

### 3.3 `evaluation`
Delivers structured LLM evaluation and pedagogical feedback.
```json
{
  "protocol_version": "1",
  "type": "evaluation",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "score": 82,
  "correct_points": [
    "Volume greater than 30 mL",
    "Midline shift greater than 5 mm"
  ],
  "missing_points": [
    "Neurological deterioration"
  ],
  "incorrect_points": [],
  "short_feedback": "Good answer. You missed neurological deterioration.",
  "suggested_rating": "hard",
  "speak": true,
  "timestamp": "2026-09-10T00:30:18.000Z"
}
```

### 3.4 `rating_saved`
Confirms Anki database update.
```json
{
  "protocol_version": "1",
  "type": "rating_saved",
  "session_id": "sess-4a81",
  "card_id": "card-001",
  "rating": "hard",
  "next_interval": "1 day",
  "timestamp": "2026-09-10T00:30:26.000Z"
}
```

### 3.5 `hint` and `explanation`
```json
{
  "protocol_version": "1",
  "type": "hint",
  "card_id": "card-001",
  "hint": "Consider hematoma volume over 30 mL and midline shift.",
  "speak": true
}
```

### 3.6 `error`
Delivers error diagnostics.
```json
{
  "protocol_version": "1",
  "type": "error",
  "code": "ANKI_DISCONNECTED",
  "message": "AnkiConnect not reachable at 127.0.0.1:8765. Ensure Anki is open.",
  "details": "Connection refused"
}
```

---

# Protocol v2 — Management & Dashboard Extension

Protocol v2 extends v1 without breaking it. A client sends `hello` with
`supported_versions: ["1","2"]` and `client_capabilities: ["dashboard","study_control"]`.
A v2 server answers the hello exchange with a `capabilities` frame; a v1 server stays
silent, so clients fall back to v1 behavior (basic study, voice loop) and simply do not
use the management surface.

## 4. Capability negotiation

### 4.1 `capabilities` (server → client)
```json
{
  "protocol_version": "2",
  "type": "capabilities",
  "capabilities": ["dashboard","deck_list","study_config","history","component_health","learning_insights","ai_usage","session_progress"],
  "server_name": "StudyPC-Agent",
  "server_version": "2.1"
}
```
Capability names are plain strings; unknown names are preserved and ignored. Clients
must gate every management feature on the advertised set.

## 5. Management requests (client → server)

All requests carry `message_id`. Servers SHOULD echo it in the matching response so
clients can correlate ACKs (§ACK).

| type | capability | response |
|---|---|---|
| `request_dashboard` | `dashboard` | `dashboard_snapshot` |
| `request_decks` | `deck_list` | `deck_list` |
| `request_component_health` | `component_health` | `component_health` |
| `request_study_config` | `study_config` | `study_config` |
| `update_study_config` | `study_config` | `study_config_updated` (ACK) or `error` |
| `request_history` (`range`: today/7d/30d) | `history` | `study_history` |
| `request_learning_insights` | `learning_insights` | `learning_insight` |
| `request_ai_usage` (`range`: today/month/all_time) | `ai_usage` | `ai_usage_stats` |

`request_dashboard` returns the consolidated snapshot (today stats, active deck, current
session, goal, recent performance, recommendation, insight, AI usage, component health).
Dedicated requests exist for refreshing individual panels (history ranges, AI usage
ranges, decks) without re-fetching everything.

## 6. Management responses (server → client)

### 6.1 `dashboard_snapshot`
```json
{
  "type": "dashboard_snapshot",
  "message_id": "<echoed request id>",
  "snapshot": {
    "generated_at": "2026-09-13T07:42:00.000Z",
    "active_deck": {"name": "MCCQE::Cardiology", "due_count": 42, "new_count": 8, "learning_count": 5, "total_count": 620, "is_favorite": true},
    "today": {"cards_reviewed": 427, "new_studied": 32, "due_remaining": 47, "recall_rate": 84.0, "study_time_seconds": 6120, "avg_seconds_per_card": 14.3, "daily_goal_cards": 500, "daily_goal_minutes": 120},
    "current_session": {"session_id": "s1", "deck": "MCCQE::Cardiology", "cards_reviewed": 37, "total_cards": 183, "recall_rate": 86.0, "elapsed_seconds": 1260, "is_paused": false},
    "goal": {"deck": "MCCQE::Cardiology", "target_cards": 2000, "learned_cards": 1240, "percent_complete": 62.0, "pace_status": "ahead"},
    "recent_performance": {"days": [{"date": "2026-09-12", "cards_reviewed": 154}], "rating_distribution": {"again": 3, "hard": 9, "good": 31, "easy": 11}, "range": "7d"},
    "recommendation": {"recommended_deck": "MCCQE::Cardiology", "recommended_mode": "weak_cards", "estimated_cards": 30, "estimated_minutes": 20, "reason": "Recall fell."},
    "insight": {"weak_topic": "Cardiology", "weak_subtopic": "Arrhythmias", "recall_rate": 62.0, "advice": "Review 15 minutes.", "generated_at": "..."},
    "ai_usage": {"range": "today", "evaluations": 427, "input_tokens": 380000, "output_tokens": 96000, "estimated_cost": 1.84, "currency": "$"},
    "component_health": {"anki": {"name": "anki", "status": "ready"}, "llm": {"name": "llm", "status": "ready"}}
  }
}
```
All snapshot fields are optional; clients render partial snapshots gracefully and never
invent missing values.

### 6.2 `deck_list`
```json
{"type": "deck_list", "message_id": "...", "decks": [
  {"name": "MCCQE::Cardiology", "due_count": 42, "new_count": 8, "learning_count": 5, "total_count": 620, "is_favorite": true}
]}
```
Deck names use Anki's `::` nesting; clients display the hierarchy but always send the
original identifier back to the server.

### 6.3 `component_health`
```json
{"type": "component_health", "message_id": "...", "timestamp": "...", "components": [
  {"name": "anki", "status": "ready", "latency_ms": 12},
  {"name": "llm", "status": "warning", "message": "quota low"}
]}
```
`status` ∈ `ready | connecting | warning | unavailable | error | unknown`. Clients must
not infer Anki/LLM readiness from the WebSocket connection; components the server does
not report are displayed as **Unknown**.

### 6.4 `study_config` / `update_study_config` / `study_config_updated`
`update_study_config` carries the full configuration object:
```json
{"type": "update_study_config", "message_id": "X", "config": {
  "active_deck": "MCCQE::Cardiology",
  "study_mode": "due_and_new",
  "session_target_type": "minutes", "session_target_value": 45,
  "new_per_day": 20, "review_limit_per_day": null, "learning_handling": "mixed",
  "evaluation": {"strictness": "balanced", "semantic_matching": true, "require_key_points": true,
                  "penalize_incorrect": true, "penalize_dangerous": true, "partial_credit": true},
  "feedback_depth": "normal",
  "socratic": {"enabled": true, "max_follow_ups": 2, "reveal_after_attempts": 3},
  "hint_policy": "manual_only",
  "rating_mode": "suggest", "auto_rate_confidence": 95,
  "transcript_retention": "score_only"
}}
```
The server MUST answer with `study_config_updated` **echoing `message_id` X** (the ACK,
optionally echoing the committed config) or an `error` frame. The client commits nothing
before the ACK and rolls back nothing on rejection — the draft survives either way.
Unsolicited `study_config_updated` (server-initiated change) is surfaced to the user
instead of silently overwriting unsaved edits.

### 6.5 `study_history`, `learning_insight`, `ai_usage_stats`
```json
{"type": "study_history", "history": {"range": "7d", "days": [{"date": "2026-09-12", "cards_reviewed": 154, "recall_rate": 82.0, "study_time_seconds": 2100}], "rating_distribution": {"again": 3, "hard": 9, "good": 31, "easy": 11}}}
{"type": "learning_insight", "insights": [{"weak_topic": "Cardiology", "weak_subtopic": "Arrhythmias", "recall_rate": 62.0, "missed_points": ["..."], "advice": "...", "generated_at": "..."}]}
{"type": "ai_usage_stats", "usage": {"range": "month", "evaluations": 5210, "input_tokens": 4600000, "output_tokens": 1150000, "estimated_cost": 22.6, "currency": "$"}}
```
Cost is always server-computed; clients never calculate provider billing.

## 7. Live session pushes

`session_progress` (`current_card_index`, `total_cards`) and `session_stats`
(`cards_studied`, `recall_rate`, `remaining_due`) update dashboards live between full
refreshes. `session_finished` MAY include a `details` object (`SessionSummaryPayload`:
rating distribution, weak topics, `ai_note`) for the post-session summary.

## 8. `start_session` configuration (v2)

`start_session` gains an optional structured `config` object (target, limits,
evaluation strictness, feedback depth, socratic mode, hint policy, rating mode,
transcript retention). v1 servers ignore it; v2 servers apply it to the session.
`mode` values: `review_due`, `new_cards`, `due_and_new`, `weak_cards`,
`incorrect_cards`, `custom`.
