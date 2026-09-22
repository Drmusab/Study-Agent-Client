"""
Authenticated media plane: plain HTTP streaming of short-lived PCM streams.

    GET /v1/tts/stream/{stream_id}
        Authorization: Bearer <Study Agent credential>

    200 → audio/pcm (canonical s16le mono 24 kHz), single-use
    401 → missing / wrong credential
    404 → unknown stream
    410 → expired or already consumed

The control plane (WebSocket JSON) stays the ONLY place requests are made and
correlated; this endpoint serves bytes for one already-authorized stream and
contains no request/response semantics of its own. No provider keys, no
secrets in URLs, no base64 — raw PCM over a LAN-bound socket.
"""

from __future__ import annotations

import json
import logging
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional

from .models import (
    CANONICAL_CHANNELS,
    CANONICAL_FORMAT,
    CANONICAL_SAMPLE_RATE,
    TtsErrorCode,
    TtsSynthesisError,
)
from .stream_manager import StreamManager

log = logging.getLogger("tts.media")

_STREAM_RE = re.compile(r"^/v1/tts/stream/([A-Za-z0-9_\-]+)$")


class TtsMediaHttpServer:
    """Threaded HTTP server for the TTS media plane."""

    def __init__(self, host: str, port: int, streams: StreamManager,
                 expected_token: Optional[str] = None, require_auth: bool = False):
        self.host = host
        self.port = port  # actual port resolved after bind (port 0 → ephemeral)
        self.streams = streams
        self.expected_token = expected_token
        self.require_auth = require_auth
        self._httpd: Optional[ThreadingHTTPServer] = None
        self._thread: Optional[threading.Thread] = None

    # ------------------------------------------------------------ lifecycle
    def start(self) -> int:
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            server_version = "StudyAgentTts/1.0"

            def log_message(self, fmt, *args):  # keep the console quiet; no headers!
                log.debug(fmt, *args)

            def _auth_ok(self) -> bool:
                if not outer.require_auth:
                    return True
                header = self.headers.get("Authorization", "")
                if not header.startswith("Bearer "):
                    return False
                token = header[len("Bearer "):].strip()
                return bool(outer.expected_token) and \
                    _const_time_eq(token, outer.expected_token)

            def _send_json(self, status: int, payload: dict) -> None:
                body = json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):  # noqa: N802 (http.server API)
                if self.path == "/v1/tts/health":
                    stats = outer.streams.stats()
                    self._send_json(200, {"ok": True, **stats})
                    return

                match = _STREAM_RE.match(self.path)
                if match is None:
                    self._send_json(404, {"code": TtsErrorCode.STREAM_NOT_FOUND,
                                          "message": "Unknown path."})
                    return
                if not self._auth_ok():
                    # Deliberately vague: never leak which part of the credential
                    # was wrong, and never echo the header back.
                    self._send_json(401, {"code": TtsErrorCode.STREAM_NOT_AUTHENTICATED,
                                          "message": "Authentication required."})
                    return

                stream_id = match.group(1)
                try:
                    record = outer.streams.take(stream_id)
                except TtsSynthesisError as e:
                    status = {
                        TtsErrorCode.STREAM_NOT_FOUND: 404,
                        TtsErrorCode.STREAM_EXPIRED: 410,
                    }.get(e.code, 410)
                    self._send_json(status, {"code": e.code, "message": e.message})
                    return

                pcm = record.pcm
                self.send_response(200)
                self.send_header(
                    "Content-Type",
                    f"audio/pcm; codecs={CANONICAL_FORMAT}, "
                    f"rate={record.sample_rate}, channels={record.channels}")
                self.send_header("X-Stream-Id", record.stream_id)
                self.send_header("X-Speech-Request-Id", record.speech_request_id)
                self.send_header("X-Cache-Hit", "1" if record.cache_hit else "0")
                self.send_header("Content-Length", str(len(pcm)))
                self.end_headers()
                try:
                    # Write in bounded slices (Content-Length keeps this exact).
                    step = 64 * 1024
                    for i in range(0, len(pcm), step):
                        self.wfile.write(pcm[i:i + step])
                except (BrokenPipeError, ConnectionResetError):
                    log.debug("client dropped mid-stream %s", record.stream_id)

        self._httpd = ThreadingHTTPServer((self.host, self.port), Handler)
        self._httpd.daemon_threads = True
        self.port = self._httpd.server_address[1]
        self._thread = threading.Thread(target=self._httpd.serve_forever,
                                        daemon=True, name="tts-media-http")
        self._thread.start()
        log.info("TTS media plane listening on %s:%s (auth_required=%s)",
                 self.host, self.port, self.require_auth)
        return self.port

    def stop(self) -> None:
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
            self._httpd = None


def _const_time_eq(a: str, b: str) -> bool:
    """Constant-time string comparison (credentials)."""
    import hmac
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))
