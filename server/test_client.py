#!/usr/bin/env python3
"""
Test client to verify Mock PC Agent protocol implementation.
"""
import asyncio
import json
import websockets

async def test_full_loop():
    uri = "ws://127.0.0.1:8765/ws"
    print(f"Connecting to {uri}...")
    async with websockets.connect(uri) as ws:
        # Hello
        await ws.send(json.dumps({"protocol_version": "1", "type": "hello"}))
        res = await ws.recv()
        print(f"Received: {res}")

        # Start session
        await ws.send(json.dumps({"protocol_version": "1", "type": "start_session", "deck": "Toronto Notes"}))
        started = json.loads(await ws.recv())
        print(f"Session started: {started}")
        session_id = started["session_id"]

        question = json.loads(await ws.recv())
        print(f"Question received: {question['question']}")
        card_id = question["card_id"]

        # Submit answer
        await ws.send(json.dumps({
            "protocol_version": "1",
            "type": "submit_answer",
            "session_id": session_id,
            "card_id": card_id,
            "text": "Volume greater than 30 mL and midline shift greater than 5 mm"
        }))
        eval_res = json.loads(await ws.recv())
        print(f"Evaluation: Score={eval_res.get('score')} | Feedback={eval_res.get('short_feedback')}")

        # Rate card
        await ws.send(json.dumps({
            "protocol_version": "1",
            "type": "rate_card",
            "session_id": session_id,
            "card_id": card_id,
            "rating": "hard"
        }))
        saved = json.loads(await ws.recv())
        print(f"Rating saved: {saved}")

        next_q = json.loads(await ws.recv())
        print(f"Next Question: {next_q['question']}")

        print("\nSUCCESS: End-to-end study protocol cycle verified!")

if __name__ == "__main__":
    asyncio.run(test_full_loop())
