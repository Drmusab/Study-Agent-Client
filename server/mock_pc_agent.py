#!/usr/bin/env python3
"""
Study Agent - Mock PC Agent Server
Acts as the PC counterpart for the Android Voice Client.
Simulates Anki study sessions, question serving, answer evaluation, and spaced repetition updates.
"""

import asyncio
import json
import uuid
import sys
from datetime import datetime, timezone

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

    def get_current_card(self):
        if self.card_index < len(self.cards):
            return self.cards[self.card_index]
        return None

    def advance(self):
        self.card_index += 1
        self.reviewed_count += 1

sessions = {}

def evaluate_answer(card, user_text):
    text_lower = user_text.lower()
    card_id = card["id"]

    if card_id == "card-001":
        has_vol = any(k in text_lower for k in ["30", "thirty", "volume", "ml", "cc"])
        has_shift = any(k in text_lower for k in ["5", "five", "midline", "shift", "anisocoria", "pupil"])
        has_neuro = any(k in text_lower for k in ["neuro", "deterioration", "gcs", "coma", "deficit"])

        correct = []
        missing = []

        if has_vol:
            correct.append("Volume greater than 30 mL")
        else:
            missing.append("Volume greater than 30 mL")

        if has_shift:
            correct.append("Midline shift > 5 mm / pupillary anisocoria")
        else:
            missing.append("Midline shift > 5 mm")

        if has_neuro:
            correct.append("Neurological deterioration")
        else:
            missing.append("Neurological deterioration")

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
        # Default generous evaluation for testing
        score = 88
        feedback = f"Good review. Key points covered: {user_text[:40]}..."
        return score, feedback, ["Main clinical concept recognized"], [], "good"

async def handler(websocket):
    client_ip = websocket.remote_address
    print(f"\n[+] Client connected from {client_ip}")
    current_session = None

    try:
        async for message in websocket:
            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                print(f"[-] Invalid JSON: {message}")
                continue

            msg_type = data.get("type")
            print(f"[>] Received message: type='{msg_type}' | {data}")

            if msg_type == "hello":
                response = {
                    "protocol_version": "1",
                    "type": "pong",
                    "timestamp": iso_now(),
                    "server_name": "StudyPC-Agent-v1.0"
                }
                await websocket.send(json.dumps(response))

            elif msg_type == "authenticate":
                print(f"[i] Client authenticated with token: {data.get('token')}")

            elif msg_type == "ping":
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "pong",
                    "timestamp": iso_now()
                }))

            elif msg_type == "start_session":
                sess_id = str(uuid.uuid4())[:8]
                deck = data.get("deck", "Toronto Notes")
                current_session = StudySession(sess_id, deck)
                sessions[sess_id] = current_session

                # 1. Send session_started
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "session_started",
                    "session_id": sess_id,
                    "deck": deck,
                    "total_cards": len(current_session.cards),
                    "timestamp": iso_now()
                }))

                # 2. Send first question
                card = current_session.get_current_card()
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "question",
                    "session_id": sess_id,
                    "card_id": card["id"],
                    "question": card["question"],
                    "card_number": current_session.card_index + 1,
                    "remaining": len(current_session.cards) - current_session.card_index,
                    "speak": True,
                    "timestamp": iso_now()
                }))

            elif msg_type == "submit_answer":
                card_id = data.get("card_id")
                user_text = data.get("text", "")
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]

                score, feedback, correct, missing, suggested = evaluate_answer(card, user_text)

                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "evaluation",
                    "session_id": data.get("session_id"),
                    "card_id": card_id,
                    "score": score,
                    "short_feedback": feedback,
                    "correct_points": correct,
                    "missing_points": missing,
                    "incorrect_points": [],
                    "suggested_rating": suggested,
                    "speak": True,
                    "timestamp": iso_now()
                }))

            elif msg_type == "rate_card":
                card_id = data.get("card_id")
                rating = data.get("rating")
                print(f"[★] Card {card_id} rated: {rating.upper()}")

                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "rating_saved",
                    "session_id": data.get("session_id"),
                    "card_id": card_id,
                    "rating": rating,
                    "next_interval": "1 day",
                    "timestamp": iso_now()
                }))

                if current_session:
                    current_session.advance()
                    next_card = current_session.get_current_card()
                    if next_card:
                        await asyncio.sleep(0.3)
                        await websocket.send(json.dumps({
                            "protocol_version": "1",
                            "type": "question",
                            "session_id": current_session.session_id,
                            "card_id": next_card["id"],
                            "question": next_card["question"],
                            "card_number": current_session.card_index + 1,
                            "remaining": len(current_session.cards) - current_session.card_index,
                            "speak": True,
                            "timestamp": iso_now()
                        }))
                    else:
                        await websocket.send(json.dumps({
                            "protocol_version": "1",
                            "type": "session_finished",
                            "session_id": current_session.session_id,
                            "total_reviewed": current_session.reviewed_count,
                            "summary": f"All {current_session.reviewed_count} cards completed in {current_session.deck_name}!",
                            "timestamp": iso_now()
                        }))

            elif msg_type == "repeat_question":
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "question",
                    "session_id": data.get("session_id"),
                    "card_id": card["id"],
                    "question": card["question"],
                    "card_number": (current_session.card_index + 1) if current_session else 1,
                    "remaining": (len(current_session.cards) - current_session.card_index) if current_session else 1,
                    "speak": True,
                    "timestamp": iso_now()
                }))

            elif msg_type == "request_hint":
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "hint",
                    "session_id": data.get("session_id"),
                    "card_id": card["id"],
                    "hint": card.get("hint", "Review indications carefully."),
                    "speak": True,
                    "timestamp": iso_now()
                }))

            elif msg_type == "request_explanation":
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "explanation",
                    "session_id": data.get("session_id"),
                    "card_id": card["id"],
                    "explanation": card.get("explanation", "Clinical explanation from Study PC."),
                    "speak": True,
                    "timestamp": iso_now()
                }))

            elif msg_type == "request_answer":
                card = current_session.get_current_card() if current_session else SAMPLE_DECK[0]
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "answer",
                    "session_id": data.get("session_id"),
                    "card_id": card["id"],
                    "answer": card.get("answer", ""),
                    "speak": True,
                    "timestamp": iso_now()
                }))

            elif msg_type == "skip_card":
                if current_session:
                    current_session.advance()
                    next_card = current_session.get_current_card()
                    if next_card:
                        await websocket.send(json.dumps({
                            "protocol_version": "1",
                            "type": "question",
                            "session_id": current_session.session_id,
                            "card_id": next_card["id"],
                            "question": next_card["question"],
                            "card_number": current_session.card_index + 1,
                            "remaining": len(current_session.cards) - current_session.card_index,
                            "speak": True,
                            "timestamp": iso_now()
                        }))

            elif msg_type == "pause_session":
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "session_paused",
                    "session_id": data.get("session_id"),
                    "timestamp": iso_now()
                }))

            elif msg_type == "resume_session":
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "session_resumed",
                    "session_id": data.get("session_id"),
                    "timestamp": iso_now()
                }))

            elif msg_type == "end_session":
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "session_finished",
                    "session_id": data.get("session_id"),
                    "summary": "Session ended by user request.",
                    "timestamp": iso_now()
                }))
                current_session = None

            elif msg_type == "request_session_status":
                await websocket.send(json.dumps({
                    "protocol_version": "1",
                    "type": "session_stats",
                    "session_id": data.get("session_id"),
                    "cards_studied": current_session.reviewed_count if current_session else 0,
                    "recall_rate": 86.5,
                    "remaining_due": (len(current_session.cards) - current_session.card_index) if current_session else 0,
                    "timestamp": iso_now()
                }))

    except websockets.exceptions.ConnectionClosed as e:
        print(f"[-] Client disconnected: {e}")

async def main():
    print(f"==================================================")
    print(f"   STUDY AGENT - MOCK PC AGENT SERVER             ")
    print(f"   Listening on ws://0.0.0.0:{PORT}/ws            ")
    print(f"==================================================")
    async with websockets.serve(handler, HOST, PORT):
        await asyncio.Future()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")
