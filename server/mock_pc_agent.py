#!/usr/bin/env python3
"""
Study Agent - Mock PC Agent Server (Hardened v2)
Mandatory features for reliability testing (§158-§160):
- duplicate, delayed evaluation, delayed rating ACK, out-of-order,
  connection drop, reconnect snapshot, session missing/finished.
- Chaos mode: randomly delay/duplicate/drop/reorder safe test messages.
- Protocol v2: review_turn_id, session_revision/sequence, idempotency.
"""

import asyncio
import json
import uuid
import sys
import random
import argparse
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
        "explanation": "Surgical evacuation via craniotomy is recommended for epidural hematomas exceeding 30 cm³ volume. In coma patients (GCS < 9), anisocoria or midline shift > 5 mm warrants urgent evacuation."
    },
    {
        "id": "card-002",
        "question": "What are the classic ECG findings in acute pericarditis?",
        "answer": "Diffuse upward concave ST segment elevation and PR segment depression across multiple leads, with reciprocal PR elevation in lead aVR.",
        "hint": "Look at ST elevation and PR depression.",
        "explanation": "Stage 1 pericarditis is characterized by widespread ST elevation and PR depression. Lead aVR uniquely exhibits PR elevation and ST depression."
    },
    {
        "id": "card-003",
        "question": "What is the immediate emergency management for tension pneumothorax?",
        "answer": "Immediate needle thoracostomy decompression in the 2nd intercostal space midclavicular line (or 5th ICS anterior axillary line), followed promptly by tube thoracostomy.",
        "hint": "Do not wait for chest X-ray. Decompress immediately.",
        "explanation": "Tension pneumothorax is a clinical diagnosis. Immediate needle decompression restores venous return and prevents hemodynamic collapse."
    },
    {
        "id": "card-004",
        "question": "What are the three components of Virchow's triad?",
        "answer": "1. Endothelial injury\n2. Stasis or turbulent blood flow\n3. Hypercoagulability of blood",
        "hint": "Think about the vessel wall, blood flow, and clotting factors.",
        "explanation": "Virchow's triad outlines the pathophysiological triad leading to intravascular thrombosis: endothelial injury, stasis, and hypercoagulability."
    },
    {
        "id": "card-005",
        "question": "What is the first-line empiric antibiotic treatment for uncomplicated outpatient community-acquired pneumonia?",
        "answer": "High-dose amoxicillin (1g TID) or doxycycline (100mg BID), or a macrolide (azithromycin) if local pneumococcal resistance is < 25%.",
        "hint": "Think oral monotherapy targeting S. pneumoniae and atypicals.",
        "explanation": "According to ATS/IDSA guidelines, healthy outpatients without comorbidities should receive high-dose amoxicillin or doxycycline."
    }
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
        self.handled_message_ids = set()

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

sessions = {}

# ---------------------------------------------------------------------------
# Protocol v2 management demo data (§124). These values are produced by the
# mock PC agent — the Android client never invents study metrics (§125).
# ---------------------------------------------------------------------------

FAKE_DECKS = [
    {"name": "MCCQE::Cardiology", "due_count": 42, "new_count": 8, "learning_count": 5, "total_count": 620, "is_favorite": True},
    {"name": "MCCQE::Neurology", "due_count": 31, "new_count": 4, "learning_count": 2, "total_count": 540, "is_favorite": False},
    {"name": "MCCQE::Pediatrics", "due_count": 18, "new_count": 12, "learning_count": 1, "total_count": 480, "is_favorite": False},
    {"name": "MCCQE::Hematology", "due_count": 9, "new_count": 2, "learning_count": 0, "total_count": 260, "is_favorite": False},
    {"name": "MCCQE::Respirology", "due_count": 14, "new_count": 6, "learning_count": 3, "total_count": 350, "is_favorite": False},
    {"name": "Surgery::Neurosurgery", "due_count": 22, "new_count": 3, "learning_count": 2, "total_count": 310, "is_favorite": False},
    {"name": "Surgery::Trauma", "due_count": 25, "new_count": 0, "learning_count": 3, "total_count": 280, "is_favorite": False},
    {"name": "Pharmacology", "due_count": 55, "new_count": 20, "learning_count": 8, "total_count": 900, "is_favorite": False},
]

DEFAULT_STUDY_CONFIG = {
    "active_deck": "MCCQE::Cardiology",
    "study_mode": "due_and_new",
    "session_target_type": "minutes",
    "session_target_value": 45,
    "new_per_day": 20,
    "review_limit_per_day": None,
    "learning_handling": "mixed",
    "evaluation": {
        "strictness": "balanced",
        "semantic_matching": True,
        "require_key_points": True,
        "penalize_incorrect": True,
        "penalize_dangerous": True,
        "partial_credit": True,
    },
    "feedback_depth": "normal",
    "socratic": {"enabled": False, "max_follow_ups": 2, "reveal_after_attempts": 3},
    "hint_policy": "manual_only",
    "rating_mode": "suggest",
    "auto_rate_confidence": 95,
    "transcript_retention": "score_only",
}

FULL_CAPABILITIES = [
    "dashboard", "deck_list", "study_config", "history",
    "component_health", "learning_insights", "ai_usage", "session_progress",
]
PARTIAL_CAPABILITIES = ["dashboard", "deck_list", "study_config", "history"]


def fake_week():
    days = []
    counts = [120, 142, 96, 165, 181, 154, 173]
    today = datetime.now(timezone.utc).date()
    for i, reviewed in enumerate(counts):
        day = today - timedelta(days=(6 - i))
        days.append({
            "date": day.isoformat(),
            "cards_reviewed": reviewed,
            "new_cards": reviewed // 8,
            "recall_rate": 70.0 + i * 2,
            "study_time_seconds": reviewed * 14,
        })
    return days


def fake_dashboard_snapshot(active_session_info=None):
    snapshot = {
        "generated_at": iso_now(),
        "active_deck": next((d for d in FAKE_DECKS if d["name"] == "MCCQE::Cardiology"), FAKE_DECKS[0]),
        "today": {
            "cards_reviewed": 427,
            "new_studied": 32,
            "due_remaining": 47,
            "recall_rate": 84.0,
            "study_time_seconds": 6120,
            "avg_seconds_per_card": 14.3,
            "daily_goal_cards": 500,
            "daily_goal_minutes": 120,
        },
        "current_session": active_session_info,
        "goal": {
            "deck": "MCCQE::Cardiology",
            "target_cards": 2000,
            "target_date": (datetime.now(timezone.utc).date() + timedelta(days=90)).isoformat(),
            "learned_cards": 1240,
            "remaining_cards": 760,
            "percent_complete": 62.0,
            "required_per_day": 9.0,
            "current_per_day": 12.0,
            "estimated_completion_date": (datetime.now(timezone.utc).date() + timedelta(days=64)).isoformat(),
            "pace_status": "ahead",
        },
        "recent_performance": {
            "days": fake_week(),
            "rating_distribution": {"again": 34, "hard": 61, "good": 268, "easy": 64},
            "range": "7d",
        },
        "recommendation": {
            "recommended_deck": "MCCQE::Cardiology",
            "recommended_mode": "weak_cards",
            "reason": "Recall has fallen over the last 7 days.",
            "estimated_cards": 30,
            "estimated_minutes": 20,
        },
        "insight": {
            "deck": "MCCQE::Cardiology",
            "weak_topic": "Cardiology",
            "weak_subtopic": "Arrhythmias",
            "recall_rate": 62.0,
            "missed_points": ["Indications for cardioversion", "Unstable atrial fibrillation management"],
            "advice": "Review this area for 15 minutes.",
            "generated_at": iso_now(),
        },
        "ai_usage": {
            "range": "today",
            "evaluations": 427,
            "input_tokens": 380000,
            "output_tokens": 96000,
            "estimated_cost": 1.84,
            "currency": "$",
        },
        "component_health": {
            "anki": {"name": "anki", "status": "ready", "latency_ms": 12},
            "llm": {"name": "llm", "status": "ready", "message": "Evaluator online", "latency_ms": 240},
            "updated_at": iso_now(),
        },
    }
    return snapshot


def evaluate_answer(card, user_text):
    text_lower = user_text.lower()
    card_id = card["id"]
    if card_id == "card-001":
        has_vol = any(k in text_lower for k in ["30", "thirty", "volume", "ml", "cc"])
        has_shift = any(k in text_lower for k in ["5", "five", "midline", "shift", "anisocoria", "pupil"])
        has_neuro = any(k in text_lower for k in ["neuro", "deterioration", "gcs", "coma", "deficit"])
        correct = []
        missing = []
        if has_vol: correct.append("Volume greater than 30 mL")
        else: missing.append("Volume greater than 30 mL")
        if has_shift: correct.append("Midline shift > 5 mm / pupillary anisocoria")
        else: missing.append("Midline shift > 5 mm")
        if has_neuro: correct.append("Neurological deterioration")
        else: missing.append("Neurological deterioration")
        score = int((len(correct) / 3.0) * 100)
        feedback = "Great answer!" if not missing else f"Good answer. You missed {', '.join(missing)}."
        rating = "good" if score >= 85 else ("hard" if score >= 50 else "again")
        return score, feedback, correct, missing, rating
    elif card_id == "card-002":
        has_st = "st" in text_lower or "elevation" in text_lower
        has_pr = "pr" in text_lower or "depression" in text_lower
        correct = []
        missing = []
        if has_st: correct.append("Diffuse ST elevation")
        else: missing.append("Diffuse ST elevation")
        if has_pr: correct.append("PR depression")
        else: missing.append("PR depression")
        score = int((len(correct) / 2.0) * 100)
        feedback = "Accurate ECG recognition." if not missing else f"You missed {', '.join(missing)}."
        rating = "good" if score >= 80 else "hard"
        return score, feedback, correct, missing, rating
    else:
        score = 88
        feedback = f"Good review. Key points covered: {user_text[:40]}..."
        return score, feedback, ["Main clinical concept recognized"], [], "good"

async def handler(websocket, chaos_opts=None, server_opts=None):
    client_ip = getattr(websocket, 'remote_address', 'unknown')
    print(f"\n[+] Client connected from {client_ip}")
    current_session = None
    chaos = chaos_opts or {}
    srv = server_opts or {}
    # Per-connection authoritative study config (§116: server is the source of truth).
    connection_config = json.loads(json.dumps(DEFAULT_STUDY_CONFIG))
    capability_mode = srv.get("capability_mode", "full")  # full | partial | v1
    rnd = random.Random(chaos.get('seed', 42))

    async def send_with_chaos(obj):
        # Chaos: duplicate, delay, drop
        if chaos.get('enable'):
            if rnd.random() < chaos.get('drop_prob', 0.05) and obj.get('type') not in ('pong','session_started'):
                print(f"[!] Chaos drop: {obj.get('type')}")
                return
            if rnd.random() < chaos.get('delay_prob', 0.15):
                await asyncio.sleep(rnd.uniform(0.05, chaos.get('max_delay', 0.3)))
        await websocket.send(json.dumps(obj))
        if chaos.get('enable') and rnd.random() < chaos.get('dup_prob', 0.15):
            await asyncio.sleep(rnd.uniform(0.05, 0.15))
            print(f"[!] Chaos duplicate: {obj.get('type')}")
            await websocket.send(json.dumps(obj))

    try:
        async for message in websocket:
            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                print(f"[-] Invalid JSON: {message}")
                continue
            msg_type = data.get("type")
            msg_id = data.get("message_id")
            print(f"[>] Received: type='{msg_type}' id={msg_id} | {data}")

            # Idempotency: if we've seen this messageId, ignore re-application but ack
            sess_for_idem = current_session
            if sess_for_idem and msg_id and msg_id in sess_for_idem.handled_message_ids:
                print(f"[=] Duplicate client messageId suppressed (idempotency) {msg_id}")
                continue
            if sess_for_idem and msg_id:
                sess_for_idem.handled_message_ids.add(msg_id)
                if len(sess_for_idem.handled_message_ids) > 200:
                    sess_for_idem.handled_message_ids.pop()

            if msg_type == "hello":
                await send_with_chaos({"protocol_version": "2", "type": "pong", "timestamp": iso_now(), "server_name": "StudyPC-Agent-v2.0", "capabilities": ["session_snapshot","review_turn_id","idempotency"]})
                # Capabilities frame for v2 negotiation (§127). A v1-only server stays
                # silent so the Android client degrades to Protocol v1 gracefully.
                if capability_mode == "full":
                    caps = FULL_CAPABILITIES
                elif capability_mode == "partial":
                    caps = PARTIAL_CAPABILITIES
                else:
                    caps = None
                if caps is not None:
                    await send_with_chaos({"protocol_version": "2", "type": "capabilities", "capabilities": caps, "server_name": "StudyPC-Agent-v2.0", "server_version": "2.1"})
            elif msg_type == "authenticate":
                print(f"[i] Auth token: {data.get('token')}")
            elif msg_type == "ping":
                await send_with_chaos({"protocol_version": "1", "type": "pong", "timestamp": iso_now()})

            elif msg_type == "start_session":
                sess_id = str(uuid.uuid4())[:8]
                deck = data.get("deck", "Toronto Notes")
                current_session = StudySession(sess_id, deck)
                sessions[sess_id] = current_session
                await send_with_chaos({"protocol_version": "2", "type": "session_started", "session_id": sess_id, "deck": deck, "total_cards": len(current_session.cards), "timestamp": iso_now(), "session_revision": current_session.session_revision, "supported_versions": ["1","2"]})
                card = current_session.get_current_card()
                await send_with_chaos({"protocol_version": "2", "type": "question", "session_id": sess_id, "card_id": card["id"], "question": card["question"], "card_number": current_session.card_index + 1, "remaining": len(current_session.cards) - current_session.card_index, "speak": True, "timestamp": iso_now(), "review_turn_id": f"{sess_id}:{card['id']}:{current_session.next_revision()}", "session_revision": current_session.session_revision, "sequence": current_session.next_sequence()})

            elif msg_type == "submit_answer":
                card_id = data.get("card_id")
                user_text = data.get("text", "")
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                # Optional delayed evaluation chaos
                if chaos.get('enable') and rnd.random() < 0.2:
                    await asyncio.sleep(rnd.uniform(0.2, 0.5))
                score, feedback, correct, missing, suggested = evaluate_answer(card, user_text)
                await send_with_chaos({"protocol_version": "2", "type": "evaluation", "session_id": data.get("session_id"), "card_id": card_id, "score": score, "short_feedback": feedback, "correct_points": correct, "missing_points": missing, "incorrect_points": [], "suggested_rating": suggested, "speak": True, "timestamp": iso_now(), "review_turn_id": data.get("review_turn_id"), "session_revision": current_session.next_revision() if current_session else 1, "confidence": 88.0})

            elif msg_type == "rate_card":
                card_id = data.get("card_id")
                rating = data.get("rating")
                print(f"[★] Card {card_id} rated: {rating.upper()}")
                if chaos.get('enable') and rnd.random() < 0.2:
                    await asyncio.sleep(rnd.uniform(0.15, 0.4))
                await send_with_chaos({"protocol_version": "2", "type": "rating_saved", "session_id": data.get("session_id"), "card_id": card_id, "rating": rating, "next_interval": "1 day", "timestamp": iso_now(), "review_turn_id": data.get("review_turn_id"), "session_revision": current_session.next_revision() if current_session else 1})
                if current_session:
                    current_session.advance()
                    next_card = current_session.get_current_card()
                    if next_card:
                        await asyncio.sleep(0.3)
                        await send_with_chaos({"protocol_version": "2", "type": "question", "session_id": current_session.session_id, "card_id": next_card["id"], "question": next_card["question"], "card_number": current_session.card_index + 1, "remaining": len(current_session.cards) - current_session.card_index, "speak": True, "timestamp": iso_now(), "review_turn_id": f"{current_session.session_id}:{next_card['id']}:{current_session.next_revision()}", "session_revision": current_session.session_revision, "sequence": current_session.next_sequence()})
                        # Live progress push (§18): dashboard updates without a full refresh.
                        await send_with_chaos({"protocol_version": "2", "type": "session_progress", "session_id": current_session.session_id, "timestamp": iso_now(), "current_card_index": current_session.card_index, "total_cards": len(current_session.cards)})
                    else:
                        await send_with_chaos({"protocol_version": "2", "type": "session_finished", "session_id": current_session.session_id, "total_reviewed": current_session.reviewed_count, "summary": f"All {current_session.reviewed_count} cards completed in {current_session.deck_name}!", "timestamp": iso_now(), "session_revision": current_session.session_revision, "details": {
                            "session_id": current_session.session_id,
                            "deck": current_session.deck_name,
                            "cards_reviewed": current_session.reviewed_count,
                            "total_cards": len(current_session.cards),
                            "recall_rate": 86.0,
                            "elapsed_seconds": current_session.reviewed_count * 14,
                            "weak_topics": ["Arrhythmias", "Valvular disease"],
                            "ai_note": "Solid session. Focus next on unstable arrhythmia management.",
                        }})

            elif msg_type == "repeat_question":
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                await send_with_chaos({"protocol_version": "2", "type": "question", "session_id": data.get("session_id"), "card_id": card["id"], "question": card["question"], "card_number": (current_session.card_index + 1) if current_session else 1, "remaining": (len(current_session.cards) - current_session.card_index) if current_session else 1, "speak": True, "timestamp": iso_now(), "review_turn_id": f"{current_session.session_id}:{card['id']}:{current_session.next_revision()}" if current_session else None, "session_revision": current_session.session_revision if current_session else 1, "sequence": current_session.next_sequence() if current_session else 1})

            elif msg_type == "request_hint":
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                await send_with_chaos({"protocol_version": "2", "type": "hint", "session_id": data.get("session_id"), "card_id": card["id"], "hint": card.get("hint", "Review indications carefully."), "speak": True, "timestamp": iso_now(), "review_turn_id": data.get("review_turn_id"), "session_revision": current_session.session_revision if current_session else 1})

            elif msg_type == "request_explanation":
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                await send_with_chaos({"protocol_version": "2", "type": "explanation", "session_id": data.get("session_id"), "card_id": card["id"], "explanation": card.get("explanation", "Clinical explanation from Study PC."), "speak": True, "timestamp": iso_now(), "review_turn_id": data.get("review_turn_id"), "session_revision": current_session.session_revision if current_session else 1})

            elif msg_type == "request_answer":
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                await send_with_chaos({"protocol_version": "2", "type": "answer", "session_id": data.get("session_id"), "card_id": card["id"], "answer": card.get("answer", ""), "speak": True, "timestamp": iso_now(), "review_turn_id": data.get("review_turn_id"), "session_revision": current_session.session_revision if current_session else 1})

            elif msg_type == "skip_card":
                if current_session:
                    current_session.advance()
                    next_card = current_session.get_current_card()
                    if next_card:
                        await send_with_chaos({"protocol_version": "2", "type": "question", "session_id": current_session.session_id, "card_id": next_card["id"], "question": next_card["question"], "card_number": current_session.card_index + 1, "remaining": len(current_session.cards) - current_session.card_index, "speak": True, "timestamp": iso_now(), "review_turn_id": f"{current_session.session_id}:{next_card['id']}:{current_session.session_revision}", "session_revision": current_session.session_revision, "sequence": current_session.next_sequence()})
                    else:
                        await send_with_chaos({"protocol_version": "1", "type": "session_finished", "session_id": current_session.session_id, "total_reviewed": current_session.reviewed_count, "summary": "Deck completed.", "timestamp": iso_now()})
                else:
                    await send_with_chaos({"protocol_version": "1", "type": "session_finished", "session_id": data.get("session_id"), "total_reviewed": 0, "summary": "No session.", "timestamp": iso_now()})

            elif msg_type == "pause_session":
                if current_session: current_session.next_revision()
                await send_with_chaos({"protocol_version": "2", "type": "session_paused", "session_id": data.get("session_id"), "timestamp": iso_now(), "session_revision": current_session.session_revision if current_session else 1})

            elif msg_type == "resume_session":
                if current_session: current_session.next_revision()
                await send_with_chaos({"protocol_version": "2", "type": "session_resumed", "session_id": data.get("session_id"), "timestamp": iso_now(), "session_revision": current_session.session_revision if current_session else 1})

            elif msg_type == "end_session":
                if current_session: current_session.next_revision()
                await send_with_chaos({"protocol_version": "2", "type": "session_finished", "session_id": data.get("session_id"), "summary": "Session ended by user request.", "timestamp": iso_now(), "session_revision": current_session.session_revision if current_session else 1})
                current_session = None

            elif msg_type == "request_session_status":
                # Legacy session_stats + new snapshot
                await send_with_chaos({"protocol_version": "1", "type": "session_stats", "session_id": data.get("session_id"), "cards_studied": current_session.reviewed_count if current_session else 0, "recall_rate": 86.5, "remaining_due": (len(current_session.cards) - current_session.card_index) if current_session else 0, "timestamp": iso_now(), "session_revision": current_session.session_revision if current_session else 0})
                # Also snapshot for v2 recovery
                card = current_session.get_current_card() if current_session else None
                await send_with_chaos({"protocol_version": "2", "type": "session_snapshot", "session_id": data.get("session_id"), "timestamp": iso_now(), "exists": current_session is not None, "is_paused": False, "is_finished": current_session is None or current_session.get_current_card() is None, "current_card_id": card["id"] if card else None, "current_question": card["question"] if card else None, "remaining": len(current_session.cards) - current_session.card_index if current_session else 0, "review_turn_id": f"{current_session.session_id}:{card['id']}:{current_session.session_revision}" if current_session and card else None, "session_revision": current_session.session_revision if current_session else 0, "awaiting": "answer" if current_session and card else "none", "cards_studied": current_session.reviewed_count if current_session else 0})

            elif msg_type == "request_session_snapshot":
                card = current_session.get_current_card() if current_session else None
                await send_with_chaos({"protocol_version": "2", "type": "session_snapshot", "session_id": data.get("session_id"), "timestamp": iso_now(), "exists": current_session is not None, "is_paused": False, "is_finished": current_session is None or current_session.get_current_card() is None, "current_card_id": card["id"] if card else None, "current_question": card["question"] if card else None, "remaining": len(current_session.cards) - current_session.card_index if current_session else 0, "review_turn_id": f"{current_session.session_id}:{card['id']}:{current_session.session_revision}" if current_session and card else None, "session_revision": current_session.session_revision if current_session else 0, "awaiting": "answer" if current_session and card else "none", "cards_studied": current_session.reviewed_count if current_session else 0})

            # ------------------------------------------------------------------
            # Protocol v2 management surface (§124-§126). Demo values are produced
            # here on the "PC agent" side — never by the Android client.
            # ------------------------------------------------------------------

            elif msg_type == "request_dashboard":
                active_info = None
                if current_session:
                    active_info = {
                        "session_id": current_session.session_id,
                        "deck": current_session.deck_name,
                        "cards_reviewed": current_session.reviewed_count,
                        "total_cards": len(current_session.cards),
                        "recall_rate": 86.0,
                        "elapsed_seconds": current_session.reviewed_count * 14,
                        "is_paused": False,
                    }
                await send_with_chaos({"protocol_version": "2", "type": "dashboard_snapshot", "message_id": msg_id, "timestamp": iso_now(), "snapshot": fake_dashboard_snapshot(active_info)})

            elif msg_type == "request_decks":
                await send_with_chaos({"protocol_version": "2", "type": "deck_list", "message_id": msg_id, "timestamp": iso_now(), "decks": FAKE_DECKS})

            elif msg_type == "request_component_health":
                await send_with_chaos({"protocol_version": "2", "type": "component_health", "message_id": msg_id, "timestamp": iso_now(), "components": [
                    {"name": "anki", "status": "ready", "latency_ms": 12},
                    {"name": "llm", "status": "ready", "message": "Evaluator online", "latency_ms": 240},
                ]})

            elif msg_type == "request_study_config":
                await send_with_chaos({"protocol_version": "2", "type": "study_config", "message_id": msg_id, "timestamp": iso_now(), "config": connection_config})

            elif msg_type == "update_study_config":
                new_config = data.get("config")
                # Rejection simulation (§126): an obviously invalid payload is refused.
                if not isinstance(new_config, dict) or new_config.get("session_target_value", 1) == 666:
                    await send_with_chaos({"protocol_version": "2", "type": "error", "message_id": msg_id, "timestamp": iso_now(), "code": "config_rejected", "message": "The Study Agent rejected this configuration."})
                else:
                    connection_config = new_config
                    # ACK echoes the request message_id for correlation (§114).
                    await send_with_chaos({"protocol_version": "2", "type": "study_config_updated", "message_id": msg_id, "timestamp": iso_now(), "config": connection_config})

            elif msg_type == "request_history":
                req_range = data.get("range", "7d")
                days = fake_week()[-1:] if req_range == "today" else fake_week()
                await send_with_chaos({"protocol_version": "2", "type": "study_history", "message_id": msg_id, "timestamp": iso_now(), "history": {
                    "range": req_range,
                    "days": days,
                    "rating_distribution": {"again": 34, "hard": 61, "good": 268, "easy": 64},
                }})

            elif msg_type == "request_learning_insights":
                await send_with_chaos({"protocol_version": "2", "type": "learning_insight", "message_id": msg_id, "timestamp": iso_now(), "insights": [{
                    "deck": "MCCQE::Cardiology",
                    "weak_topic": "Cardiology",
                    "weak_subtopic": "Arrhythmias",
                    "recall_rate": 62.0,
                    "missed_points": ["Indications for cardioversion", "Unstable atrial fibrillation management"],
                    "advice": "Review this area for 15 minutes.",
                    "generated_at": iso_now(),
                }]})

            elif msg_type == "request_ai_usage":
                req_range = data.get("range", "month")
                await send_with_chaos({"protocol_version": "2", "type": "ai_usage_stats", "message_id": msg_id, "timestamp": iso_now(), "usage": {
                    "range": req_range,
                    "evaluations": 427 if req_range == "today" else 5210,
                    "input_tokens": 380000 if req_range == "today" else 4600000,
                    "output_tokens": 96000 if req_range == "today" else 1150000,
                    "estimated_cost": 1.84 if req_range == "today" else 22.60,
                    "currency": "$",
                }})

            else:
                print(f"[?] Unknown type {msg_type}")

    except websockets.exceptions.ConnectionClosed as e:
        print(f"[-] Client disconnected: {e}")

async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chaos", action="store_true", help="Enable chaos mode (duplicate/delay/drop)")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--v1", action="store_true", help="Simulate a Protocol v1-only agent (no capabilities frame)")
    parser.add_argument("--partial", action="store_true", help="Advertise a partial v2 capability set")
    args = parser.parse_args()
    chaos_opts = {"enable": args.chaos, "seed": args.seed, "dup_prob": 0.15, "delay_prob": 0.15, "drop_prob": 0.05, "max_delay": 0.4}
    capability_mode = "v1" if args.v1 else ("partial" if args.partial else "full")
    server_opts = {"capability_mode": capability_mode}
    print("==================================================")
    print("   STUDY AGENT - MOCK PC AGENT SERVER  (v2 Hardened)")
    print(f"   Listening on ws://0.0.0.0:{args.port}")
    print(f"   Chaos: {args.chaos} seed={args.seed}")
    print(f"   Capabilities: {capability_mode}")
    print("==================================================")
    async with websockets.serve(lambda ws: handler(ws, chaos_opts, server_opts), HOST, args.port):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")
