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
