#!/usr/bin/env python3
"""
Study Agent - Mock PC Agent Server (Enhanced v2)

Implements full v2 handshake with welcome, Bearer auth, protocol negotiation,
capability versioning, idempotency, session recovery, and chaos testing.

Modes:
- full v2 (default): welcome + capabilities + dashboard
- --partial: partial v2 capabilities
- --v1: v1-only (no welcome, no capabilities)
- --chaos: duplicate/delay/drop/reorder
- --auth: require token validation
- --wrong-service: simulate wrong WebSocket service (no handshake response)

Reference implementation for PC_AGENT_INTEGRATION_GUIDE.md
"""

import asyncio
import json
import uuid
import sys
import random
import argparse
import base64
from datetime import datetime, timezone, timedelta

try:
    import websockets
except ImportError:
    print("Installing websockets library...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "websockets"])
    import websockets

PORT = 8765
HOST = "0.0.0.0"

SAMPLE_DECK = [
    {
        "id": "card-001",
        "question": "What are the indications for evacuation of an epidural hematoma?",
        "answer": "1. Volume > 30 cm³ regardless of GCS\n2. GCS < 9 with pupillary anisocoria or midline shift > 5 mm\n3. Neurological deterioration",
        "hint": "Consider hematoma volume over 30 mL, midline shift, and progressive neuro deficit.",
        "explanation": "Surgical evacuation via craniotomy is recommended for epidural hematomas exceeding 30 cm³ volume."
    },
    {
        "id": "card-002",
        "question": "What are the classic ECG findings in acute pericarditis?",
        "answer": "Diffuse upward concave ST segment elevation and PR segment depression across multiple leads.",
        "hint": "Look at ST elevation and PR depression.",
        "explanation": "Stage 1 pericarditis is characterized by widespread ST elevation and PR depression."
    },
    {
        "id": "card-003",
        "question": "What is the immediate emergency management for tension pneumothorax?",
        "answer": "Immediate needle thoracostomy decompression in the 2nd intercostal space midclavicular line.",
        "hint": "Do not wait for chest X-ray. Decompress immediately.",
        "explanation": "Tension pneumothorax is a clinical diagnosis. Immediate needle decompression restores venous return."
    },
]

def iso_now():
    return datetime.now(timezone.utc).isoformat()

class StudySession:
    def __init__(self, session_id, deck_name):
        self.session_id = session_id
        self.deck_name = deck_name
        self.card_index = 0
        self.cards = list(SAMPLE_DECK)
        self.reviewed_count = 0
        self.ratings = []
        self.session_revision = 1
        self.sequence = 0
        self.handled_message_ids = {}
        self.agent_id = f"agent-{uuid.uuid4().hex[:8]}"

    def get_current_card(self):
        if self.card_index < len(self.cards):
            return self.cards[self.card_index]
        return None

    def advance(self):
        self.card_index += 1
        self.reviewed_count += 1
        self.session_revision += 1

    def next_revision(self):
        self.session_revision += 1
        return self.session_revision

    def next_sequence(self):
        self.sequence += 1
        return self.sequence

    def remember_message(self, message_id, response):
        self.handled_message_ids[message_id] = {
            "response": response,
            "timestamp": datetime.now(timezone.utc)
        }
        if len(self.handled_message_ids) > 200:
            oldest = min(self.handled_message_ids.keys(), key=lambda k: self.handled_message_ids[k]["timestamp"])
            del self.handled_message_ids[oldest]

    def get_cached_response(self, message_id):
        entry = self.handled_message_ids.get(message_id)
        return entry["response"] if entry else None

sessions = {}

FAKE_DECKS = [
    {"name": "MCCQE::Cardiology", "due_count": 42, "new_count": 8, "learning_count": 5, "total_count": 620, "is_favorite": True},
    {"name": "MCCQE::Neurology", "due_count": 31, "new_count": 4, "learning_count": 2, "total_count": 540, "is_favorite": False},
]

DEFAULT_STUDY_CONFIG = {
    "active_deck": "MCCQE::Cardiology",
    "study_mode": "due_and_new",
    "session_target_type": "minutes",
    "session_target_value": 45,
    "new_per_day": 20,
    "evaluation": {"strictness": "balanced"},
    "feedback_depth": "normal",
}

FULL_CAPABILITIES = ["dashboard", "deck_list", "study_config", "history", "component_health", "learning_insights", "ai_usage", "session_progress", "session_recovery"]
PARTIAL_CAPABILITIES = ["dashboard", "deck_list", "study_config"]

def fake_dashboard_snapshot(active_session_info=None):
    return {
        "generated_at": iso_now(),
        "active_deck": FAKE_DECKS[0],
        "today": {"cards_reviewed": 427, "new_studied": 32, "due_remaining": 47, "recall_rate": 84.0, "study_time_seconds": 6120},
        "current_session": active_session_info,
        "component_health": {
            "anki": {"name": "anki", "status": "ready", "latency_ms": 12},
            "llm": {"name": "llm", "status": "ready", "message": "Evaluator online", "latency_ms": 240},
            "updated_at": iso_now(),
        },
    }

def evaluate_answer(card, user_text):
    text_lower = user_text.lower()
    card_id = card["id"]
    if card_id == "card-001":
        has_vol = any(k in text_lower for k in ["30", "volume", "ml"])
        has_shift = any(k in text_lower for k in ["5", "midline", "shift"])
        has_neuro = any(k in text_lower for k in ["neuro", "gcs", "deficit"])
        correct = []
        missing = []
        if has_vol: correct.append("Volume > 30 mL")
        else: missing.append("Volume > 30 mL")
        if has_shift: correct.append("Midline shift > 5 mm")
        else: missing.append("Midline shift > 5 mm")
        if has_neuro: correct.append("Neurological deterioration")
        else: missing.append("Neurological deterioration")
        score = int((len(correct) / 3.0) * 100)
        feedback = "Great!" if not missing else f"Missed {', '.join(missing)}"
        rating = "good" if score >= 85 else ("hard" if score >= 50 else "again")
        return score, feedback, correct, missing, rating
    else:
        return 88, "Good review.", ["Main concept"], [], "good"

# Global agent ID persistent across connections
PERSISTENT_AGENT_ID = f"agent-{uuid.uuid4().hex[:8]}"
SERVER_VERSION = "2.3.0"
SERVER_NAME = "StudyPC-Agent"

async def handler(websocket, chaos_opts=None, server_opts=None):
    # Try to get token from header for Bearer auth test
    request_headers = {}
    try:
        # websockets library stores headers in websocket.request.headers
        if hasattr(websocket, 'request') and hasattr(websocket.request, 'headers'):
            request_headers = dict(websocket.request.headers)
    except:
        pass

    client_ip = getattr(websocket, 'remote_address', 'unknown')
    print(f"\n[+] Client connected from {client_ip}")
    if request_headers:
        auth_header = request_headers.get('authorization', request_headers.get('Authorization', ''))
        if auth_header:
            print(f"[i] Authorization header present: {auth_header[:20]}... (redacted)")
        else:
            print(f"[i] No Authorization header")

    current_session = None
    chaos = chaos_opts or {}
    srv = server_opts or {}
    connection_config = json.loads(json.dumps(DEFAULT_STUDY_CONFIG))
    capability_mode = srv.get("capability_mode", "full")
    auth_required = srv.get("auth_required", False)
    valid_token = srv.get("valid_token", "test-token-123")
    wrong_service = srv.get("wrong_service", False)
    rnd = random.Random(chaos.get('seed', 42))
    authenticated = not auth_required
    negotiated_protocol = None

    # Check Bearer token if auth required
    if auth_required:
        auth_header = request_headers.get('authorization', request_headers.get('Authorization', ''))
        if auth_header.startswith('Bearer '):
            token = auth_header[7:]
            if token == valid_token:
                authenticated = True
                print(f"[✓] Bearer auth succeeded")
            else:
                print(f"[✗] Bearer auth failed: invalid token")
                if srv.get("reject_invalid_auth", False):
                    await websocket.close(code=4001, reason="AUTH_INVALID")
                    return
        else:
            print(f"[i] Auth required but no Bearer token in header")

    async def send_with_chaos(obj):
        if chaos.get('enable'):
            if rnd.random() < chaos.get('drop_prob', 0.05) and obj.get('type') not in ('pong','session_started','welcome'):
                print(f"[!] Chaos drop: {obj.get('type')}")
                return
            if rnd.random() < chaos.get('delay_prob', 0.15):
                await asyncio.sleep(rnd.uniform(0.05, chaos.get('max_delay', 0.3)))
        # Enforce size limits
        json_str = json.dumps(obj)
        if len(json_str) > 2 * 1024 * 1024:
            print(f"[!] Frame too large {len(json_str)} bytes, dropping")
            return
        await websocket.send(json_str)
        if chaos.get('enable') and rnd.random() < chaos.get('dup_prob', 0.15):
            await asyncio.sleep(rnd.uniform(0.05, 0.15))
            print(f"[!] Chaos duplicate: {obj.get('type')}")
            await websocket.send(json_str)

    try:
        async for message in websocket:
            # Size limit check
            if len(message) > 2 * 1024 * 1024:
                print(f"[!] Received oversized frame {len(message)} bytes, rejecting")
                await send_with_chaos({
                    "protocol_version": "2",
                    "type": "error",
                    "code": "FRAME_TOO_LARGE",
                    "message": "Frame exceeds 2MB limit"
                })
                continue

            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                print(f"[-] Invalid JSON: {message[:200]}")
                await send_with_chaos({
                    "protocol_version": "2",
                    "type": "error",
                    "code": "INVALID_REQUEST",
                    "message": "Invalid JSON"
                })
                continue

            msg_type = data.get("type")
            msg_id = data.get("message_id")
            in_reply_to = data.get("in_reply_to")
            print(f"[>] Received: type='{msg_type}' id={msg_id} in_reply_to={in_reply_to}")

            # Wrong service simulation
            if wrong_service:
                print(f"[!] Wrong service mode: ignoring {msg_type}")
                continue

            # Idempotency check
            sess_for_idem = current_session
            if sess_for_idem and msg_id:
                cached = sess_for_idem.get_cached_response(msg_id)
                if cached:
                    print(f"[=] Duplicate messageId suppressed (idempotency) {msg_id}, returning cached")
                    await send_with_chaos(cached)
                    continue

            if msg_type == "hello":
                client_versions = data.get("supported_versions", data.get("supported_protocols", ["1"]))
                print(f"[i] Client hello: versions={client_versions} client={data.get('client_name')} v={data.get('client_version')}")
                
                # Protocol negotiation: server chooses
                if "2" in client_versions:
                    negotiated_protocol = "2"
                elif "1" in client_versions:
                    negotiated_protocol = "1"
                else:
                    await send_with_chaos({
                        "protocol_version": "2",
                        "type": "error",
                        "code": "PROTOCOL_UNSUPPORTED",
                        "message": f"Server supports [1,2], client supports {client_versions}",
                        "in_reply_to": msg_id
                    })
                    continue

                # Auth check
                token_in_hello = data.get("auth_token") or data.get("token")
                if auth_required and not authenticated:
                    if token_in_hello == valid_token:
                        authenticated = True
                        print(f"[✓] Auth via hello token succeeded")
                    else:
                        print(f"[✗] Auth required, no valid token in hello")
                        if srv.get("reject_invalid_auth", False):
                            await send_with_chaos({
                                "protocol_version": negotiated_protocol,
                                "type": "error",
                                "code": "AUTH_INVALID",
                                "message": "Invalid authentication token",
                                "in_reply_to": msg_id
                            })
                            continue

                if capability_mode == "v1":
                    # v1 server does NOT send welcome, only capabilities as legacy
                    print(f"[i] v1 mode: sending pong only")
                    await send_with_chaos({
                        "protocol_version": "1",
                        "type": "pong",
                        "timestamp": iso_now(),
                        "in_reply_to": msg_id,
                        "server_name": SERVER_NAME
                    })
                else:
                    # v2 welcome handshake
                    if capability_mode == "full":
                        caps = FULL_CAPABILITIES
                    else:
                        caps = PARTIAL_CAPABILITIES

                    welcome = {
                        "protocol_version": "2",
                        "type": "welcome",
                        "message_id": f"welcome-{uuid.uuid4().hex[:6]}",
                        "in_reply_to": msg_id,
                        "timestamp": iso_now(),
                        "server_name": SERVER_NAME,
                        "server_version": SERVER_VERSION,
                        "selected_protocol": negotiated_protocol,
                        "capabilities": caps,
                        "agent_id": PERSISTENT_AGENT_ID,
                        "authentication": {
                            "required": auth_required,
                            "authenticated": authenticated,
                            "methods": ["bearer", "legacy_frame"]
                        }
                    }
                    await send_with_chaos(welcome)

                    # Also send legacy capabilities for backward compat
                    await send_with_chaos({
                        "protocol_version": "2",
                        "type": "capabilities",
                        "message_id": f"caps-{uuid.uuid4().hex[:6]}",
                        "capabilities": caps,
                        "server_name": SERVER_NAME,
                        "server_version": SERVER_VERSION,
                        "agent_id": PERSISTENT_AGENT_ID,
                        "timestamp": iso_now()
                    })

            elif msg_type == "authenticate":
                token = data.get("token")
                print(f"[i] Legacy authenticate frame: token={token[:10] if token else None}... (redacted)")
                if auth_required:
                    if token == valid_token:
                        authenticated = True
                        await send_with_chaos({
                            "protocol_version": negotiated_protocol or "2",
                            "type": "auth_result",
                            "message_id": f"auth-{uuid.uuid4().hex[:6]}",
                            "in_reply_to": msg_id,
                            "success": True,
                            "method": "legacy_frame",
                            "timestamp": iso_now()
                        })
                        print(f"[✓] Legacy auth succeeded")
                    else:
                        await send_with_chaos({
                            "protocol_version": negotiated_protocol or "2",
                            "type": "auth_result",
                            "message_id": f"auth-{uuid.uuid4().hex[:6]}",
                            "in_reply_to": msg_id,
                            "success": False,
                            "method": "legacy_frame",
                            "error": "Invalid token",
                            "timestamp": iso_now()
                        })
                        print(f"[✗] Legacy auth failed")
                else:
                    await send_with_chaos({
                        "protocol_version": negotiated_protocol or "2",
                        "type": "auth_result",
                        "message_id": f"auth-{uuid.uuid4().hex[:6]}",
                        "in_reply_to": msg_id,
                        "success": True,
                        "method": "legacy_frame",
                        "timestamp": iso_now()
                    })

            elif msg_type == "ping":
                await send_with_chaos({
                    "protocol_version": negotiated_protocol or "1",
                    "type": "pong",
                    "message_id": f"pong-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "timestamp": iso_now(),
                    "sent_at": data.get("sent_at")
                })

            elif msg_type == "start_session":
                if auth_required and not authenticated:
                    await send_with_chaos({
                        "protocol_version": "2",
                        "type": "error",
                        "code": "AUTH_REQUIRED",
                        "message": "Authentication required",
                        "in_reply_to": msg_id
                    })
                    continue

                sess_id = str(uuid.uuid4())[:8]
                deck = data.get("deck", "Toronto Notes")
                current_session = StudySession(sess_id, deck)
                sessions[sess_id] = current_session

                response = {
                    "protocol_version": negotiated_protocol or "2",
                    "type": "session_started",
                    "message_id": f"ss-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "session_id": sess_id,
                    "deck": deck,
                    "total_cards": len(current_session.cards),
                    "timestamp": iso_now(),
                    "session_revision": current_session.session_revision,
                    "supported_versions": ["1","2"]
                }
                if current_session and msg_id:
                    current_session.remember_message(msg_id, response)
                await send_with_chaos(response)

                card = current_session.get_current_card()
                await send_with_chaos({
                    "protocol_version": negotiated_protocol or "2",
                    "type": "question",
                    "message_id": f"q-{uuid.uuid4().hex[:6]}",
                    "session_id": sess_id,
                    "card_id": card["id"],
                    "question": card["question"],
                    "card_number": current_session.card_index + 1,
                    "remaining": len(current_session.cards) - current_session.card_index,
                    "speak": True,
                    "timestamp": iso_now(),
                    "review_turn_id": f"{sess_id}:{card['id']}:{current_session.next_revision()}",
                    "session_revision": current_session.session_revision,
                    "sequence": current_session.next_sequence()
                })

            elif msg_type == "submit_answer":
                card_id = data.get("card_id")
                user_text = data.get("text", "")
                review_turn_id = data.get("review_turn_id")
                session_revision = data.get("session_revision")
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]

                # Validate turn identity
                if current_session and review_turn_id:
                    expected_prefix = f"{current_session.session_id}:{card['id']}"
                    if not review_turn_id.startswith(expected_prefix):
                        await send_with_chaos({
                            "protocol_version": negotiated_protocol or "2",
                            "type": "error",
                            "code": "STALE_REVIEW_TURN",
                            "message": f"Stale turn {review_turn_id}, expected {expected_prefix}",
                            "in_reply_to": msg_id,
                            "session_revision": current_session.session_revision
                        })
                        continue

                if chaos.get('enable') and rnd.random() < 0.2:
                    await asyncio.sleep(rnd.uniform(0.2, 0.5))

                score, feedback, correct, missing, suggested = evaluate_answer(card, user_text)
                response = {
                    "protocol_version": negotiated_protocol or "2",
                    "type": "evaluation",
                    "message_id": f"eval-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "session_id": data.get("session_id"),
                    "card_id": card_id,
                    "score": score,
                    "short_feedback": feedback,
                    "correct_points": correct,
                    "missing_points": missing,
                    "incorrect_points": [],
                    "suggested_rating": suggested,
                    "speak": True,
                    "timestamp": iso_now(),
                    "review_turn_id": review_turn_id,
                    "session_revision": current_session.next_revision() if current_session else 1,
                    "confidence": 88.0
                }
                if current_session and msg_id:
                    current_session.remember_message(msg_id, response)
                await send_with_chaos(response)

            elif msg_type == "rate_card":
                card_id = data.get("card_id")
                rating = data.get("rating")
                print(f"[★] Card {card_id} rated: {rating.upper()}")

                if chaos.get('enable') and rnd.random() < 0.2:
                    await asyncio.sleep(rnd.uniform(0.15, 0.4))

                response = {
                    "protocol_version": negotiated_protocol or "2",
                    "type": "rating_saved",
                    "message_id": f"rated-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "session_id": data.get("session_id"),
                    "card_id": card_id,
                    "rating": rating,
                    "next_interval": "1 day",
                    "timestamp": iso_now(),
                    "review_turn_id": data.get("review_turn_id"),
                    "session_revision": current_session.next_revision() if current_session else 1
                }
                if current_session and msg_id:
                    # Check if already processed (idempotency)
                    cached = current_session.get_cached_response(msg_id)
                    if cached:
                        await send_with_chaos(cached)
                        continue
                    current_session.remember_message(msg_id, response)

                await send_with_chaos(response)

                if current_session:
                    current_session.advance()
                    next_card = current_session.get_current_card()
                    if next_card:
                        await asyncio.sleep(0.3)
                        await send_with_chaos({
                            "protocol_version": negotiated_protocol or "2",
                            "type": "question",
                            "message_id": f"q-{uuid.uuid4().hex[:6]}",
                            "session_id": current_session.session_id,
                            "card_id": next_card["id"],
                            "question": next_card["question"],
                            "card_number": current_session.card_index + 1,
                            "remaining": len(current_session.cards) - current_session.card_index,
                            "speak": True,
                            "timestamp": iso_now(),
                            "review_turn_id": f"{current_session.session_id}:{next_card['id']}:{current_session.next_revision()}",
                            "session_revision": current_session.session_revision,
                            "sequence": current_session.next_sequence()
                        })
                        await send_with_chaos({
                            "protocol_version": negotiated_protocol or "2",
                            "type": "session_progress",
                            "message_id": f"prog-{uuid.uuid4().hex[:6]}",
                            "session_id": current_session.session_id,
                            "timestamp": iso_now(),
                            "current_card_index": current_session.card_index,
                            "total_cards": len(current_session.cards)
                        })
                    else:
                        await send_with_chaos({
                            "protocol_version": negotiated_protocol or "2",
                            "type": "session_finished",
                            "message_id": f"fin-{uuid.uuid4().hex[:6]}",
                            "session_id": current_session.session_id,
                            "total_reviewed": current_session.reviewed_count,
                            "summary": f"All {current_session.reviewed_count} cards completed!",
                            "timestamp": iso_now(),
                            "session_revision": current_session.session_revision
                        })

            elif msg_type == "request_session_snapshot":
                card = current_session.get_current_card() if current_session else None
                await send_with_chaos({
                    "protocol_version": negotiated_protocol or "2",
                    "type": "session_snapshot",
                    "message_id": f"snap-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "session_id": data.get("session_id"),
                    "timestamp": iso_now(),
                    "exists": current_session is not None,
                    "is_paused": False,
                    "is_finished": current_session is None or current_session.get_current_card() is None,
                    "current_card_id": card["id"] if card else None,
                    "current_question": card["question"] if card else None,
                    "remaining": len(current_session.cards) - current_session.card_index if current_session else 0,
                    "review_turn_id": f"{current_session.session_id}:{card['id']}:{current_session.session_revision}" if current_session and card else None,
                    "session_revision": current_session.session_revision if current_session else 0,
                    "awaiting": "answer" if current_session and card else "none",
                    "cards_studied": current_session.reviewed_count if current_session else 0
                })

            elif msg_type == "request_dashboard":
                active_info = None
                if current_session:
                    active_info = {
                        "session_id": current_session.session_id,
                        "deck": current_session.deck_name,
                        "cards_reviewed": current_session.reviewed_count,
                        "total_cards": len(current_session.cards),
                    }
                await send_with_chaos({
                    "protocol_version": negotiated_protocol or "2",
                    "type": "dashboard_snapshot",
                    "message_id": f"dash-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "timestamp": iso_now(),
                    "snapshot": fake_dashboard_snapshot(active_info)
                })

            elif msg_type == "request_decks":
                await send_with_chaos({
                    "protocol_version": negotiated_protocol or "2",
                    "type": "deck_list",
                    "message_id": f"decks-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "timestamp": iso_now(),
                    "decks": FAKE_DECKS
                })

            elif msg_type == "request_study_config":
                await send_with_chaos({
                    "protocol_version": negotiated_protocol or "2",
                    "type": "study_config",
                    "message_id": f"cfg-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "timestamp": iso_now(),
                    "config": connection_config
                })

            elif msg_type == "update_study_config":
                new_config = data.get("config")
                if not isinstance(new_config, dict) or new_config.get("session_target_value", 1) == 666:
                    await send_with_chaos({
                        "protocol_version": negotiated_protocol or "2",
                        "type": "error",
                        "message_id": f"err-{uuid.uuid4().hex[:6]}",
                        "in_reply_to": msg_id,
                        "timestamp": iso_now(),
                        "code": "CONFIG_REJECTED",
                        "message": "The Study Agent rejected this configuration."
                    })
                else:
                    connection_config = new_config
                    await send_with_chaos({
                        "protocol_version": negotiated_protocol or "2",
                        "type": "study_config_updated",
                        "message_id": f"cfgupd-{uuid.uuid4().hex[:6]}",
                        "in_reply_to": msg_id,
                        "timestamp": iso_now(),
                        "config": connection_config
                    })

            elif msg_type == "request_component_health":
                await send_with_chaos({
                    "protocol_version": negotiated_protocol or "2",
                    "type": "component_health",
                    "message_id": f"health-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "timestamp": iso_now(),
                    "components": [
                        {"name": "anki", "status": "ready", "latency_ms": 12},
                        {"name": "llm", "status": "ready", "message": "Evaluator online", "latency_ms": 240},
                    ]
                })

            elif msg_type in ("pause_session", "resume_session", "end_session", "repeat_question", "request_hint", "request_explanation", "request_answer", "skip_card", "request_session_status"):
                # Simplified handling for other types
                if current_session and msg_type != "request_session_status":
                    current_session.next_revision()
                t = msg_type.replace("request_", "").replace("pause_session", "session_paused").replace("resume_session", "session_resumed").replace("end_session", "session_finished")
                if msg_type == "request_session_status":
                    card = current_session.get_current_card() if current_session else None
                    await send_with_chaos({
                        "protocol_version": negotiated_protocol or "2",
                        "type": "session_snapshot",
                        "message_id": f"snap-{uuid.uuid4().hex[:6]}",
                        "in_reply_to": msg_id,
                        "session_id": data.get("session_id"),
                        "timestamp": iso_now(),
                        "exists": current_session is not None,
                        "current_card_id": card["id"] if card else None,
                        "session_revision": current_session.session_revision if current_session else 0
                    })
                else:
                    await send_with_chaos({
                        "protocol_version": negotiated_protocol or "2",
                        "type": t if t in ["session_paused", "session_resumed", "session_finished"] else "pong",
                        "message_id": f"{t[:4]}-{uuid.uuid4().hex[:6]}",
                        "in_reply_to": msg_id,
                        "session_id": data.get("session_id"),
                        "timestamp": iso_now(),
                        "session_revision": current_session.session_revision if current_session else 1
                    })
            else:
                print(f"[?] Unknown type {msg_type}")
                await send_with_chaos({
                    "protocol_version": negotiated_protocol or "2",
                    "type": "error",
                    "message_id": f"err-{uuid.uuid4().hex[:6]}",
                    "in_reply_to": msg_id,
                    "code": "INVALID_REQUEST",
                    "message": f"Unknown message type: {msg_type}",
                    "timestamp": iso_now()
                })

    except websockets.exceptions.ConnectionClosed as e:
        print(f"[-] Client disconnected: {e}")
    except Exception as e:
        print(f"[!] Handler error: {e}")
        import traceback
        traceback.print_exc()

async def main():
    parser = argparse.ArgumentParser(description="Study Agent Mock PC Agent (Enhanced v2)")
    parser.add_argument("--chaos", action="store_true", help="Enable chaos mode (duplicate/delay/drop)")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--v1", action="store_true", help="Simulate Protocol v1-only agent (no welcome)")
    parser.add_argument("--partial", action="store_true", help="Advertise partial v2 capability set")
    parser.add_argument("--auth", action="store_true", help="Require Bearer auth")
    parser.add_argument("--token", type=str, default="test-token-123", help="Valid token when --auth enabled")
    parser.add_argument("--reject-auth", action="store_true", help="Reject invalid auth immediately")
    parser.add_argument("--wrong-service", action="store_true", help="Simulate wrong WebSocket service (no handshake)")
    args = parser.parse_args()

    chaos_opts = {"enable": args.chaos, "seed": args.seed, "dup_prob": 0.15, "delay_prob": 0.15, "drop_prob": 0.05, "max_delay": 0.4}
    capability_mode = "v1" if args.v1 else ("partial" if args.partial else "full")
    server_opts = {
        "capability_mode": capability_mode,
        "auth_required": args.auth,
        "valid_token": args.token,
        "reject_invalid_auth": args.reject_auth,
        "wrong_service": args.wrong_service
    }

    print("==================================================")
    print("   STUDY AGENT - MOCK PC AGENT SERVER (Enhanced v2)")
    print(f"   Listening on ws://0.0.0.0:{args.port}")
    print(f"   Mode: {capability_mode} | Auth: {args.auth} | Chaos: {args.chaos} | WrongService: {args.wrong_service}")
    print(f"   Agent ID: {PERSISTENT_AGENT_ID} | Version: {SERVER_VERSION}")
    print(f"   Protocol: Server chooses selected_protocol via welcome")
    print(f"   Features: welcome handshake, Bearer auth, in_reply_to, idempotency, review_turn_id, session_revision, recovery")
    print("==================================================")

    async with websockets.serve(lambda ws: handler(ws, chaos_opts, server_opts), HOST, args.port):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")
