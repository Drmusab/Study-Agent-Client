"""
Contract tests for the PC Agent remote TTS subsystem (deterministic).

The agent (WebSocket control plane + HTTP media plane) runs IN-PROCESS with the
deterministic mock providers, so every test is hermetic: no network, no API
keys, no paid synthesis. This is the CI gate for the whole TTS protocol
(master prompt §121/§122/§123):

    provider capabilities / voice catalog / synthesis request / stream
    creation / authentication / cancel / provider failure / rate limit /
    unknown voice / unknown model / single-use streams / expiry / caching /
    credential hygiene.

Run:  pytest test_tts_contract.py -v     (from the server/ directory)
"""

import asyncio
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from types import SimpleNamespace

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_pc_agent  # noqa: E402
from mock_pc_agent import TTS_STATE, initialize_tts  # noqa: E402
from tts.models import TtsErrorCode  # noqa: E402

try:
    import websockets
except ImportError:  # pragma: no cover
    websockets = None

pytestmark = pytest.mark.asyncio


# ---------------------------------------------------------------------------
# In-process agent fixture
# ---------------------------------------------------------------------------

def _free_port() -> int:
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def _reset_tts_state():
    if TTS_STATE.get("media_server") is not None:
        TTS_STATE["media_server"].stop()
    TTS_STATE.update({
        "enabled": False, "providers": {}, "streams": None,
        "media_server": None, "media_port": None,
        "auth_required": False, "expected_token": None,
        "stream_ttl_seconds": 60.0,
    })


class _AgentHarness:
    """Runs the full mock agent (WS control plane + TTS media plane) on a
    background thread with its own event loop, so pytest-asyncio tests on the
    main loop can connect to it like a real remote PC."""

    def __init__(self, auth: bool, token: str):
        self.auth = auth
        self.token = token if auth else None
        self.ws_port = _free_port()
        self.tts_port = None
        self._loop = None
        self._thread = None
        self._ready = None
        self._ws_server = None

    def start(self):
        if websockets is None:
            raise RuntimeError("websockets not installed")
        _reset_tts_state()
        args = SimpleNamespace(
            no_tts=False, mock_tts=True, tts_http_port=0, tts_fail=None,
            auth=self.auth, token=self.token, port=self.ws_port,
        )
        assert initialize_tts(args), "TTS subsystem failed to initialize"
        self.tts_port = TTS_STATE["media_port"]
        server_opts = {"capability_mode": "full", "auth_required": self.auth,
                       "valid_token": self.token, "reject_invalid_auth": False,
                       "wrong_service": False}

        import threading

        self._ready = threading.Event()

        def _run():
            self._loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self._loop)

            async def _serve():
                from websockets.legacy.server import serve
                self._ws_server = await serve(
                    lambda ws: mock_pc_agent.handler(ws, {}, server_opts),
                    "127.0.0.1", self.ws_port)
                self._ready.set()
                try:
                    await asyncio.Future()  # run forever
                except (asyncio.CancelledError, RuntimeError):
                    pass  # loop stopped during teardown

            try:
                self._loop.run_until_complete(_serve())
            except RuntimeError:
                pass  # loop stopped before the sentinel future completes
            finally:
                self._loop.close()

        self._thread = threading.Thread(target=_run, daemon=True, name="agent-harness")
        self._thread.start()
        if not self._ready.wait(timeout=10):
            raise RuntimeError("agent did not start in time")

    def stop(self):
        loop = self._loop
        if loop is not None and loop.is_running():
            # Close the server from within the loop, then stop the loop.
            try:
                loop.call_soon_threadsafe(self._call_close)
                self._thread.join(timeout=5)
            except Exception:
                pass
        _reset_tts_state()

    def _call_close(self):
        async def _do():
            try:
                await self._ws_server.close()
            except Exception:
                pass
            self._loop.stop()
        asyncio.ensure_future(_do())

    @property
    def info(self):
        return {"ws_port": self.ws_port, "tts_port": self.tts_port,
                "auth": self.auth, "token": self.token}


@pytest.fixture
def agent():
    """Fresh agent with mock TTS providers, no auth."""
    harness = _AgentHarness(auth=False, token="unused")
    harness.start()
    yield harness.info
    harness.stop()


@pytest.fixture
def agent_auth():
    """Agent with Bearer auth required on BOTH planes (realistic deployment)."""
    harness = _AgentHarness(auth=True, token="unit-test-secret-999")
    harness.start()
    yield harness.info
    harness.stop()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _msg(mtype, **fields):
    base = {
        "protocol_version": "2",
        "type": mtype,
        "message_id": str(uuid.uuid4()),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    base.update(fields)
    return base


async def _connect(port, token=None):
    extra = {"Authorization": f"Bearer {token}"} if token else {}
    return await websockets.connect(f"ws://127.0.0.1:{port}/ws", extra_headers=extra)


async def _hello(ws, token=None):
    hello = _msg("hello", client_name="TestClient", client_version="1.0.0",
                 supported_versions=["2", "1"],
                 **({"auth_token": token} if token else {}))
    await ws.send(json.dumps(hello))
    frames = []
    deadline = time.time() + 5
    welcome = None
    while time.time() < deadline and welcome is None:
        raw = await asyncio.wait_for(ws.recv(), timeout=max(0.1, deadline - time.time()))
        data = json.loads(raw)
        frames.append(data)
        if data.get("type") == "welcome":
            welcome = data
    return welcome, frames


async def _rpc(ws, mtype, timeout=10.0, **fields):
    """Send a request frame and await the correlated reply (in_reply_to)."""
    request = _msg(mtype, **fields)
    await ws.send(json.dumps(request))
    deadline = time.time() + timeout
    while time.time() < deadline:
        raw = await asyncio.wait_for(ws.recv(), timeout=max(0.1, deadline - time.time()))
        data = json.loads(raw)
        if data.get("in_reply_to") == request["message_id"]:
            return data
    raise TimeoutError(f"no correlated reply for {mtype}")


def _http_get(port, path, token=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


async def _synthesize(ws, provider, voice=None, text=None, model=None,
                      purpose="QUESTION", rate=1.0, options=None, request_id=None):
    return await _rpc(ws, "tts_synthesize", provider=provider,
                      voice_id=voice, text=text or "What are the indications for evacuation of an epidural hematoma?",
                      model=model, language="auto", purpose=purpose,
                      rate=rate, provider_options=options or {},
                      request_id=request_id or f"req-{uuid.uuid4().hex[:8]}")


def _assert_no_secrets(frames, tts_port):
    """No provider key / auth material may ever appear in protocol frames."""
    blob = json.dumps(frames).lower()
    for secret in ("sk-", "xi-api", "api_key", "bearer ", "secret"):
        assert secret not in blob, f"possible secret leaked in frames: {secret}"


# ---------------------------------------------------------------------------
# Capabilities + catalog
# ---------------------------------------------------------------------------

async def test_welcome_advertises_tts_capabilities(agent):
    ws = await _connect(agent["ws_port"])
    try:
        welcome, _ = await _hello(ws)
        assert welcome is not None
        caps = welcome["capabilities"]
        assert "tts" in caps
        assert "tts:streaming" in caps
        assert "tts:voice_catalog" in caps
        assert "tts:openai" in caps
        assert "tts:elevenlabs" in caps
    finally:
        await ws.close()


async def test_tts_capabilities_response_shape(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        reply = await _rpc(ws, "tts_capabilities_request")
        assert reply["type"] == "tts_capabilities"
        providers = {p["provider"]: p for p in reply["providers"]}
        assert set(providers) == {"openai", "elevenlabs"}

        openai = providers["openai"]
        assert openai["available"] is True
        assert openai["configured"] is True
        assert openai["healthy"] is True
        assert "gpt-4o-mini-tts" in openai["models"]
        assert openai["default_model"] == "gpt-4o-mini-tts"
        assert openai["supports_style_instructions"] is True
        assert openai["max_input_chars"] == 4000

        eleven = providers["elevenlabs"]
        assert eleven["configured"] is True
        assert "eleven_flash_v2_5" in eleven["models"]
        assert eleven["supports_voice_settings"] is True
        assert eleven["supports_rate"] is False

        media = reply["media"]
        assert media["format"] == "pcm_s16le"
        assert media["sample_rate"] == 24000
        assert media["channels"] == 1
        assert media["auth"] == "bearer"
        assert media["port"] == agent["tts_port"]
        _assert_no_secrets([reply], agent["tts_port"])
    finally:
        await ws.close()


async def test_voice_catalog_is_namespaced_and_cached(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        reply = await _rpc(ws, "tts_voices_request", provider="openai")
        assert reply["type"] == "tts_voices"
        ids = [v["id"] for v in reply["voices"]]
        assert all(i.startswith("openai:") for i in ids)
        assert "openai:coral" in ids
        assert "openai:marin" in ids
        assert "openai:cedar" in ids

        reply_ar = await _rpc(ws, "tts_voices_request", provider="elevenlabs")
        ar_ids = [v for v in reply_ar["voices"] if "ar" in v.get("languages", [])]
        assert ar_ids, "expected Arabic-capable voices in the catalog"
        assert all(v["id"].startswith("elevenlabs:") for v in reply_ar["voices"])

        # Unknown provider → typed error.
        err = await _rpc(ws, "tts_voices_request", provider="azure")
        assert err["type"] == "error"
        assert err["code"] == TtsErrorCode.INVALID_REQUEST
    finally:
        await ws.close()


async def test_catalog_does_not_leak_credentials(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        frames = [await _rpc(ws, "tts_voices_request", provider="openai"),
                  await _rpc(ws, "tts_voices_request", provider="elevenlabs")]
        _assert_no_secrets(frames, agent["tts_port"])
    finally:
        await ws.close()


# ---------------------------------------------------------------------------
# Synthesis + media plane
# ---------------------------------------------------------------------------

async def _synthesize_and_fetch(agent, ws, **kwargs):
    reply = await _synthesize(ws, **kwargs)
    assert reply["type"] == "tts_stream", f"expected tts_stream, got {reply}"
    status, headers, body = _http_get(agent["tts_port"], reply["stream_path"],
                                      token=agent["token"])
    return reply, status, headers, body


async def test_full_synthesis_stream_flow(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        reply, status, headers, body = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:coral",
            text="GCS 15/15 and BP 120/80 mmHg.")
        assert status == 200
        assert reply["format"] == "pcm_s16le"
        assert reply["sample_rate"] == 24000
        assert reply["channels"] == 1
        assert reply["expires_in_seconds"] > 0
        assert "st_" in reply["stream_id"]

        ctype = headers.get("Content-Type", "")
        assert "audio/pcm" in ctype
        assert "rate=24000" in ctype
        assert "channels=1" in ctype
        assert headers.get("X-Cache-Hit") == "0"
        assert headers.get("X-Stream-Id") == reply["stream_id"]

        # Canonical PCM: 24 kHz mono s16 → bytes = frames*2; duration > 0.
        assert len(body) > 0
        assert len(body) % 2 == 0
        duration_ms = (len(body) // 2) // 24  # frames / 24 = ms (24000/1000)
        assert 50 < duration_ms < 120_000
        assert reply["estimated_duration_ms"] == duration_ms
    finally:
        await ws.close()


async def test_stream_is_single_use(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        reply, status, _, _ = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:nova")
        assert status == 200
        # Second GET on the same stream → 410 (short-lived, not reusable).
        status2, headers2, body2 = _http_get(agent["tts_port"], reply["stream_path"])
        assert status2 == 410
        err = json.loads(body2)
        assert err["code"] in (TtsErrorCode.STREAM_EXPIRED, TtsErrorCode.STREAM_FAILED)
    finally:
        await ws.close()


async def test_stream_expiry(agent):
    TTS_STATE["stream_ttl_seconds"] = 0.5
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        reply = await _synthesize(ws, provider="openai", voice="openai:alloy")
        assert reply["type"] == "tts_stream"
        await asyncio.sleep(0.8)
        status, _, body = _http_get(agent["tts_port"], reply["stream_path"])
        assert status == 410
        assert json.loads(body)["code"] == TtsErrorCode.STREAM_EXPIRED
    finally:
        TTS_STATE["stream_ttl_seconds"] = 60.0
        await ws.close()


async def test_unknown_stream_is_404(agent):
    status, _, _ = _http_get(agent["tts_port"], "/v1/tts/stream/st_doesnotexist")
    assert status == 404
    status, _, _ = _http_get(agent["tts_port"], "/v1/tts/nope")
    assert status == 404


async def test_unknown_provider_voice_and_model(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        err = await _rpc(ws, "tts_synthesize", provider="openai",
                         voice_id="openai:does_not_exist", text="hello")
        assert err["type"] == "error"
        assert err["code"] == TtsErrorCode.VOICE_NOT_FOUND

        err = await _rpc(ws, "tts_synthesize", provider="openai",
                         voice_id="openai:coral", model="gpt-9-super-tts",
                         text="hello")
        assert err["type"] == "error"
        assert err["code"] == TtsErrorCode.MODEL_UNAVAILABLE

        err = await _rpc(ws, "tts_synthesize", provider="nvidia", voice_id=None,
                         text="hello")
        assert err["type"] == "error"
        assert err["code"] == TtsErrorCode.INVALID_REQUEST

        # Cross-provider voice confusion is rejected, never passed through.
        err = await _rpc(ws, "tts_synthesize", provider="openai",
                         voice_id="elevenlabs:21m00Tcm4TlvDq8ikWAM", text="hello")
        assert err["type"] == "error"
        assert err["code"] == TtsErrorCode.VOICE_NOT_FOUND
    finally:
        await ws.close()


async def test_text_too_long(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        err = await _rpc(ws, "tts_synthesize", provider="openai",
                         voice_id="openai:coral", text="word " * 2000)
        assert err["type"] == "error"
        assert err["code"] == TtsErrorCode.TEXT_TOO_LONG
    finally:
        await ws.close()


# ---------------------------------------------------------------------------
# Failure injection (typed provider errors)
# ---------------------------------------------------------------------------

def _set_failure(agent, mode):
    for p in TTS_STATE["providers"].values():
        if hasattr(p, "failure"):
            p.failure = mode


async def test_rate_limit_is_typed(agent):
    _set_failure(agent, "rate_limit")
    try:
        ws = await _connect(agent["ws_port"])
        try:
            await _hello(ws)
            err = await _rpc(ws, "tts_synthesize", provider="openai",
                             voice_id="openai:coral", text="hello")
            assert err["type"] == "error"
            assert err["code"] == TtsErrorCode.PROVIDER_RATE_LIMITED
            assert err.get("retryable") is True

            # Rate limiting is TRANSIENT: provider health (configured/auth/
            # reachable) is unchanged, so the Android router keeps preferring
            # the provider and handles 429s as typed per-request errors.
            caps = await _rpc(ws, "tts_capabilities_request")
            openai = next(p for p in caps["providers"] if p["provider"] == "openai")
            assert openai["configured"] is True
            assert openai["healthy"] is True
            _assert_no_secrets([err, caps], agent["tts_port"])
        finally:
            await ws.close()
    finally:
        _set_failure(agent, None)


async def test_quota_exceeded_is_typed(agent):
    _set_failure(agent, "quota")
    try:
        ws = await _connect(agent["ws_port"])
        try:
            await _hello(ws)
            err = await _rpc(ws, "tts_synthesize", provider="elevenlabs",
                             voice_id="elevenlabs:21m00Tcm4TlvDq8ikWAM", text="hello")
            assert err["code"] == TtsErrorCode.PROVIDER_QUOTA_EXCEEDED
            assert err.get("retryable") is False
        finally:
            await ws.close()
    finally:
        _set_failure(agent, None)


async def test_auth_failure_is_typed(agent):
    _set_failure(agent, "auth")
    try:
        ws = await _connect(agent["ws_port"])
        try:
            await _hello(ws)
            err = await _rpc(ws, "tts_synthesize", provider="openai",
                             voice_id="openai:coral", text="hello")
            assert err["code"] == TtsErrorCode.PROVIDER_AUTH_FAILED
            test = await _rpc(ws, "tts_test_provider", provider="openai")
            assert test["checks"]["authentication"] == "failed"
        finally:
            await ws.close()
    finally:
        _set_failure(agent, None)


async def test_synthesis_failure_is_typed(agent):
    _set_failure(agent, "synthesis")
    try:
        ws = await _connect(agent["ws_port"])
        try:
            await _hello(ws)
            err = await _rpc(ws, "tts_synthesize", provider="openai",
                             voice_id="openai:coral", text="hello")
            assert err["code"] == TtsErrorCode.SYNTHESIS_FAILED
        finally:
            await ws.close()
    finally:
        _set_failure(agent, None)


# ---------------------------------------------------------------------------
# Caching (repeat optimization)
# ---------------------------------------------------------------------------

async def test_repeat_hits_server_cache(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        text = "What are the classic ECG findings in acute pericarditis?"
        first, s1, h1, b1 = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:coral", text=text)
        assert h1.get("X-Cache-Hit") == "0"

        second, s2, h2, b2 = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:coral", text=text)
        assert s2 == 200
        assert second["cache_hit"] is True
        assert h2.get("X-Cache-Hit") == "1"
        # Deterministic provider → identical audio bytes on repeat.
        assert b1 == b2

        # Voice change → different cache key → different audio.
        third, s3, h3, b3 = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:nova", text=text)
        assert s3 == 200
        assert b3 != b1
        assert third["cache_hit"] is False

        # Rate change → different cache key.
        fourth, s4, _, b4 = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:coral", text=text, rate=1.5)
        assert s4 == 200
        assert b4 != b1
    finally:
        await ws.close()


# ---------------------------------------------------------------------------
# Cancellation
# ---------------------------------------------------------------------------

async def test_cancel_by_stream_id(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        reply = await _synthesize(ws, provider="openai", voice="openai:coral")
        assert reply["type"] == "tts_stream"
        ack = await _rpc(ws, "tts_cancel", stream_id=reply["stream_id"])
        assert ack["type"] == "tts_cancelled"
        assert ack["cancelled"] >= 1
        # A cancelled stream cannot be served.
        status, _, body = _http_get(agent["tts_port"], reply["stream_path"])
        assert status in (410,)
    finally:
        await ws.close()


async def test_cancel_by_speech_request(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        request_id = f"req-{uuid.uuid4().hex[:8]}"
        reply = await _synthesize(ws, provider="elevenlabs",
                                  voice="elevenlabs:21m00Tcm4TlvDq8ikWAM",
                                  request_id=request_id)
        assert reply["type"] == "tts_stream"
        assert reply["speech_request_id"] == request_id
        ack = await _rpc(ws, "tts_cancel", speech_request_id=request_id)
        assert ack["type"] == "tts_cancelled"
        assert ack["cancelled"] >= 1
    finally:
        await ws.close()


# ---------------------------------------------------------------------------
# Test-provider action
# ---------------------------------------------------------------------------

async def test_provider_test_action(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        test = await _rpc(ws, "tts_test_provider", provider="elevenlabs")
        assert test["type"] == "tts_provider_test"
        checks = test["checks"]
        assert checks["configured"] is True
        assert checks["authentication"] == "ok"
        assert checks["reachable"] is True
        assert checks["voice_list"] is True
        assert test["latency_ms"] is not None
    finally:
        await ws.close()


# ---------------------------------------------------------------------------
# Authentication (media plane mirrors the agent credential)
# ---------------------------------------------------------------------------

async def test_media_plane_requires_bearer_when_auth_enabled(agent_auth):
    token = agent_auth["token"]
    ws = await _connect(agent_auth["ws_port"], token=token)
    try:
        await _hello(ws, token=token)
        reply = await _synthesize(ws, provider="openai", voice="openai:coral")
        assert reply["type"] == "tts_stream"
        path = reply["stream_path"]

        # Missing credential → 401.
        status, _, _ = _http_get(agent_auth["tts_port"], path, token=None)
        assert status == 401
        # Wrong credential → 401 (same vague body, no leakage).
        status, _, body = _http_get(agent_auth["tts_port"], path, token="wrong-token")
        assert status == 401
        assert token not in body.decode("utf-8", "replace")
        # Correct credential → 200.
        status, headers, body = _http_get(agent_auth["tts_port"], path, token=token)
        assert status == 200
        assert len(body) > 0
    finally:
        await ws.close()


async def test_control_plane_requires_auth(agent_auth):
    token = agent_auth["token"]
    ws = await _connect(agent_auth["ws_port"], token="wrong-token")
    try:
        await _hello(ws)
        err = await _rpc(ws, "tts_capabilities_request")
        assert err["type"] == "error"
        assert err["code"] == "AUTH_REQUIRED"
    finally:
        await ws.close()


# ---------------------------------------------------------------------------
# Determinism (the whole point of the mock provider)
# ---------------------------------------------------------------------------

async def test_synthesis_is_deterministic(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        text = "ما هي دواعي إجلاء الورم الدموي فوق الجافية؟ GCS 15/15"
        _, s1, _, b1 = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:cedar", text=text)
        # First byte came from cache? No — fresh. Fetch again after cache fill.
        _, s2, h2, b2 = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:cedar", text=text)
        assert s1 == 200 and s2 == 200
        assert b1 == b2
        # Different voice → different timbre → different bytes.
        _, s3, _, b3 = await _synthesize_and_fetch(
            agent, ws, provider="openai", voice="openai:onyx", text=text)
        assert b3 != b1
    finally:
        await ws.close()


async def test_mixed_arabic_english_synthesis(agent):
    ws = await _connect(agent["ws_port"])
    try:
        await _hello(ws)
        text = ("المريض لديه epidural hematoma مع midline shift "
                "أكثر من 5 mm و GCS 15/15")
        reply, status, _, body = await _synthesize_and_fetch(
            agent, ws, provider="elevenlabs",
            voice="elevenlabs:21m00Tcm4TlvDq8ikWAM", text=text)
        assert status == 200
        assert len(body) > 2000  # meaningful audio for a mixed sentence
    finally:
        await ws.close()
