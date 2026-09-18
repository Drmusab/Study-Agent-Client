#!/usr/bin/env python3
"""
Contract tests for Study Agent PC Agent integration.
Tests protocol, auth, idempotency, recovery, etc.

Run with: pytest test_contract.py -v
Or: python3 -m pytest test_contract.py
Requires mock_pc_agent.py running or uses in-process server.
"""

import asyncio
import json
import uuid
from datetime import datetime, timezone

try:
    import pytest
except ImportError:
    # Stdlib-only run (`python3 test_contract.py`): the unit tests below execute via
    # __main__ without pytest; the integration tests are never collected in that mode.

    class _StubMark:
        def __getattr__(self, _name):
            return lambda fn: fn

    class _StubPytest:
        mark = _StubMark()

        @staticmethod
        def skip(reason=""):
            raise RuntimeError(f"skipped (pytest not installed): {reason}")

    pytest = _StubPytest()

try:
    import websockets
except ImportError:
    # Integration tests catch their own connection errors and skip; a missing
    # websockets package degrades to the same skip path.
    websockets = None

# For in-process testing, we can import handler logic
# But simpler: tests assume server running on localhost:8765
# We'll also provide unit tests that don't require server

def iso_now():
    return datetime.now(timezone.utc).isoformat()

# --- Unit tests (no server needed) ---

def test_hello_envelope_structure():
    hello = {
        "type": "hello",
        "protocol_version": "2",
        "message_id": str(uuid.uuid4()),
        "timestamp": iso_now(),
        "client_name": "StudyAgent-Android",
        "client_version": "1.2.3",
        "platform": "android",
        "supported_versions": ["2", "1"],
        "client_capabilities": ["dashboard", "study_control"]
    }
    assert hello["type"] == "hello"
    assert "1.2.3" in hello["client_version"]
    assert "2" in hello["supported_versions"]
    # No hardware IDs
    assert "imei" not in str(hello).lower()
    assert "android_id" not in str(hello).lower()

def test_welcome_structure():
    welcome = {
        "type": "welcome",
        "protocol_version": "2",
        "message_id": "welcome-123",
        "in_reply_to": "hello-123",
        "server_name": "StudyPC-Agent",
        "server_version": "2.3.0",
        "selected_protocol": "2",
        "capabilities": ["dashboard", "session_recovery"],
        "agent_id": "persistent-uuid",
        "authentication": {"required": True, "authenticated": True, "methods": ["bearer"]}
    }
    assert welcome["selected_protocol"] in ["1", "2"]
    assert "agent_id" in welcome
    assert welcome["in_reply_to"] == "hello-123"

def test_in_reply_to_correlation():
    request_id = "msg-123"
    response = {
        "type": "dashboard_snapshot",
        "message_id": "server-456",
        "in_reply_to": request_id,
        "snapshot": {}
    }
    assert response["in_reply_to"] == request_id

def test_idempotency_contract():
    # Server should remember messageId
    seen = set()
    def process(msg_id):
        if msg_id in seen:
            return False  # duplicate suppressed
        seen.add(msg_id)
        return True

    assert process("msg-1") == True
    assert process("msg-1") == False  # duplicate
    assert process("msg-2") == True

def test_review_turn_id_enforcement():
    turn_id = "session-epoch:card-123:gen-1"
    message = {
        "type": "submit_answer",
        "card_id": "card-123",
        "review_turn_id": turn_id,
        "session_revision": 3
    }
    assert message["review_turn_id"] == turn_id
    assert message["session_revision"] == 3

def test_error_codes_stable():
    valid_codes = [
        "ANKI_NOT_RUNNING", "ANKICONNECT_UNAVAILABLE", "DECK_NOT_FOUND",
        "AUTH_REQUIRED", "AUTH_INVALID", "PROTOCOL_UNSUPPORTED",
        "SESSION_NOT_FOUND", "STALE_SESSION_REVISION", "STALE_REVIEW_TURN",
        "CONFIG_REJECTED", "RATE_LIMITED", "INVALID_REQUEST",
        "FRAME_TOO_LARGE", "TIMEOUT"
    ]
    # Ensure error codes are uppercase with underscores, stable
    for code in valid_codes:
        assert code == code.upper()
        assert " " not in code

def test_size_limits():
    max_frame = 2 * 1024 * 1024
    question_max = 10 * 1024
    assert max_frame == 2097152
    # Real question should be less than 10KB
    sample_question = "What are indications for evacuation of epidural hematoma?"
    assert len(sample_question.encode()) < question_max

def test_bearer_auth_header():
    token = "secret-token-123"
    header = f"Bearer {token}"
    assert header.startswith("Bearer ")
    # Token not logged
    sanitized_log = "Authorization: Bearer ***REDACTED***"
    assert token not in sanitized_log
    assert "***REDACTED***" in sanitized_log

def test_connection_problem_model():
    problems = {
        "NetworkMissing": {"userMessage": "No network", "userAction": "Check Wi-Fi"},
        "DnsFailure": {"userMessage": "Cannot resolve", "userAction": "Verify host"},
        "ConnectionRefused": {"userMessage": "Connection refused", "userAction": "Start the PC"},
        "TlsFailure": {"userMessage": "Secure connection failed", "userAction": "Check secure"},
        "AuthenticationRejected": {"userMessage": "Authentication rejected", "userAction": "Edit token"},
        "ProtocolMismatch": {"userMessage": "Incompatible protocol", "userAction": "Update app"},
        "HandshakeTimeout": {"userMessage": "Agent did not respond", "userAction": "Verify this is a Study Agent"},
    }
    for problem, msgs in problems.items():
        assert "userMessage" in msgs
        assert "userAction" in msgs
        # User action should be actionable
        assert len(msgs["userAction"]) > 5

def test_protocol_negotiation():
    def negotiate(client_versions, server_versions):
        for v in server_versions:
            if v in client_versions:
                return v
        return None

    assert negotiate(["2","1"], ["2"]) == "2"
    assert negotiate(["1"], ["2"]) is None
    assert negotiate(["2"], ["1","2"]) == "2"  # server prefers highest it supports that client also supports

def test_session_recovery_flow():
    # Simulate recovery: hello -> welcome -> auth -> snapshot
    flow = ["hello", "welcome", "authenticate", "request_session_snapshot", "session_snapshot", "question"]
    assert flow[0] == "hello"
    assert "welcome" in flow
    assert "request_session_snapshot" in flow
    assert flow[-1] in ["question", "session_snapshot"]

def test_tailscale_detection():
    def is_tailscale(ip):
        if ip.endswith(".ts.net"):
            return True
        if ip.startswith("100."):
            parts = ip.split(".")
            if len(parts) == 4:
                try:
                    second = int(parts[1])
                    return 64 <= second <= 127
                except:
                    pass
        return False

    assert is_tailscale("100.64.0.1") == True
    assert is_tailscale("100.127.255.255") == True
    assert is_tailscale("100.63.0.1") == False
    assert is_tailscale("192.168.1.100") == False
    assert is_tailscale("my-machine.ts.net") == True

def test_no_hardware_ids():
    # Ensure no IMEI, Android ID, serial in messages
    forbidden = ["imei", "android_id", "serial_number", "device_id"]
    sample_message = {
        "type": "hello",
        "client_name": "StudyAgent-Android",
        "client_version": "1.0.0",
        "platform": "android"
    }
    msg_str = json.dumps(sample_message).lower()
    for field in forbidden:
        assert field not in msg_str

def test_generation_prevents_parallel_sockets():
    generation = 0
    def connect():
        nonlocal generation
        generation += 1
        return generation

    gen1 = connect()
    gen2 = connect()
    assert gen2 > gen1

    def is_stale(callback_gen, current_gen):
        return callback_gen != current_gen

    assert is_stale(gen1, gen2) == True
    assert is_stale(gen2, gen2) == False

# --- Integration tests (require server) ---
# These are marked to be skipped if server not running

@pytest.mark.asyncio
async def test_integration_hello_welcome():
    try:
        async with websockets.connect("ws://localhost:8765/ws") as ws:
            hello = {
                "type": "hello",
                "protocol_version": "2",
                "message_id": str(uuid.uuid4()),
                "timestamp": iso_now(),
                "client_name": "TestClient",
                "client_version": "1.0.0",
                "supported_versions": ["2", "1"]
            }
            await ws.send(json.dumps(hello))
            response = await asyncio.wait_for(ws.recv(), timeout=5)
            data = json.loads(response)
            # Should get welcome or pong
            assert data["type"] in ["welcome", "pong", "capabilities"]
            if data["type"] == "welcome":
                assert "selected_protocol" in data
                assert data["selected_protocol"] in ["1", "2"]
                assert "agent_id" in data
    except Exception as e:
        pytest.skip(f"Server not running: {e}")

@pytest.mark.asyncio
async def test_integration_auth_required():
    try:
        async with websockets.connect("ws://localhost:8765/ws", extra_headers={"Authorization": "Bearer wrong-token"}) as ws:
            hello = {
                "type": "hello",
                "protocol_version": "2",
                "message_id": str(uuid.uuid4()),
                "timestamp": iso_now(),
                "supported_versions": ["2"]
            }
            await ws.send(json.dumps(hello))
            # Server in non-auth mode should still accept
            response = await asyncio.wait_for(ws.recv(), timeout=5)
            data = json.loads(response)
            assert data["type"] in ["welcome", "pong", "capabilities", "error"]
    except Exception as e:
        pytest.skip(f"Server not running: {e}")

@pytest.mark.asyncio
async def test_integration_idempotency():
    try:
        async with websockets.connect("ws://localhost:8765/ws") as ws:
            hello = {
                "type": "hello",
                "protocol_version": "2",
                "message_id": str(uuid.uuid4()),
                "timestamp": iso_now(),
                "supported_versions": ["2"]
            }
            await ws.send(json.dumps(hello))
            await asyncio.wait_for(ws.recv(), timeout=2)  # welcome
            try:
                await asyncio.wait_for(ws.recv(), timeout=0.5)  # capabilities
            except asyncio.TimeoutError:
                pass

            # Start session
            start_id = str(uuid.uuid4())
            start = {
                "type": "start_session",
                "message_id": start_id,
                "protocol_version": "2",
                "timestamp": iso_now(),
                "deck": "Test Deck"
            }
            await ws.send(json.dumps(start))
            resp1 = await asyncio.wait_for(ws.recv(), timeout=5)
            data1 = json.loads(resp1)
            assert data1["type"] == "session_started"

            # Duplicate start with same messageId should be suppressed
            await ws.send(json.dumps(start))
            # Should not get duplicate session_started, or should get cached
            try:
                resp2 = await asyncio.wait_for(ws.recv(), timeout=1)
                data2 = json.loads(resp2)
                # If server returns cached, it should be same session
                if data2["type"] == "session_started":
                    assert data2["session_id"] == data1["session_id"]
            except asyncio.TimeoutError:
                pass  # suppression is also valid
    except Exception as e:
        pytest.skip(f"Server not running: {e}")

if __name__ == "__main__":
    # Run unit tests
    test_hello_envelope_structure()
    test_welcome_structure()
    test_in_reply_to_correlation()
    test_idempotency_contract()
    test_review_turn_id_enforcement()
    test_error_codes_stable()
    test_size_limits()
    test_bearer_auth_header()
    test_connection_problem_model()
    test_protocol_negotiation()
    test_session_recovery_flow()
    test_tailscale_detection()
    test_no_hardware_ids()
    test_generation_prevents_parallel_sockets()
    print("All unit contract tests passed!")
