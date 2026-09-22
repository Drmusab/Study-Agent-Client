"""
ElevenLabs TTS provider (PC-side only).

Verified against the current official API surface (2026):
  * GET  https://api.elevenlabs.io/v1/voices            (account voice catalog)
  * POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}
       ?output_format=pcm_24000                         (native canonical PCM)
       headers: xi-api-key: <key>
       body: {text, model_id, voice_settings, optimize_streaming_latency}
  * models: eleven_flash_v2_5 (ultra-low latency), eleven_turbo_v2_5,
    eleven_multilingual_v2 (32 languages incl. Arabic), eleven_v3 (70+)
  * quality strategies map to models on the AGENT (Android only sees
    friendly "fast/balanced/quality" or an explicit model id).

Auth uses the provider's own `xi-api-key` header — never Authorization, never
sent to Android, never logged.
"""

from __future__ import annotations

import asyncio
import http.client
import json
import os
import socket
import ssl
import threading
import time
from typing import Dict, Iterator, List, Optional
from urllib.parse import quote

from .audio import chunk_bytes
from .models import (
    ProviderCapabilities,
    SynthesisRequest,
    TtsErrorCode,
    TtsSynthesisError,
    TtsVoice,
)
from .provider import TtsProvider, now_iso

API_BASE = os.environ.get("ELEVENLABS_API_BASE", "https://api.elevenlabs.io")

MODELS = {
    "eleven_flash_v2_5": "Fast (lowest latency)",
    "eleven_turbo_v2_5": "Fast+ (balanced speed)",
    "eleven_multilingual_v2": "Balanced (32 languages)",
    "eleven_v3": "Maximum quality (70+ languages)",
}
DEFAULT_MODEL = "eleven_flash_v2_5"
MAX_INPUT_CHARS = 4000

# Friendly quality strategy -> model id (agent-side mapping, §24).
QUALITY_MODEL_MAP = {
    "interactive": "eleven_flash_v2_5",
    "fast": "eleven_flash_v2_5",
    "balanced": "eleven_multilingual_v2",
    "quality": "eleven_v3",
}

# Fallback chain when a model is unavailable on the account tier.
MODEL_FALLBACKS = [
    ["eleven_v3", "eleven_multilingual_v2", "eleven_flash_v2_5"],
    ["eleven_multilingual_v2", "eleven_flash_v2_5"],
    ["eleven_flash_v2_5"],
]


def _decode_error(status: int, body: bytes) -> TtsSynthesisError:
    detail = ""
    try:
        payload = json.loads(body.decode("utf-8", "replace"))
        detail = str(payload.get("detail") or payload)
        if isinstance(payload.get("detail"), dict):
            detail = str(payload["detail"].get("message") or payload["detail"])
    except Exception:
        detail = body[:200].decode("utf-8", "replace")

    if status in (401, 403):
        return TtsSynthesisError(TtsErrorCode.PROVIDER_AUTH_FAILED,
                                 f"ElevenLabs auth failed (HTTP {status}). {detail}",
                                 retryable=False)
    if status == 429:
        return TtsSynthesisError(TtsErrorCode.PROVIDER_RATE_LIMITED,
                                 f"ElevenLabs rate limited (HTTP 429). {detail}",
                                 retryable=True)
    if status == 451 or "quota" in detail.lower() or "tier" in detail.lower():
        return TtsSynthesisError(TtsErrorCode.PROVIDER_QUOTA_EXCEEDED,
                                 f"ElevenLabs quota/plan unavailable. {detail}",
                                 retryable=False)
    if status in (400, 404, 422):
        low = detail.lower()
        if "voice" in low:
            return TtsSynthesisError(TtsErrorCode.VOICE_NOT_FOUND,
                                     f"ElevenLabs voice unavailable: {detail}",
                                     retryable=False)
        if "model" in low:
            return TtsSynthesisError(TtsErrorCode.MODEL_UNAVAILABLE,
                                     f"ElevenLabs model unavailable: {detail}",
                                     retryable=False)
    return TtsSynthesisError(TtsErrorCode.SYNTHESIS_FAILED,
                             f"ElevenLabs synthesis failed (HTTP {status}). {detail}",
                             retryable=status >= 500)


class _StreamingHttp:
    """Incremental HTTPS reader (chunks arrive as the provider generates)."""

    def __init__(self, host: str, path: str, headers: Dict[str, str],
                 body: Optional[bytes], timeout: float = 60.0):
        self.host = host
        self.path = path
        self.headers = headers
        self.body = body
        self.timeout = timeout
        self.conn: Optional[http.client.HTTPSConnection] = None
        self.status: Optional[int] = None
        self.error_body = b""

    def open(self) -> None:
        host = self.host
        if host.startswith("https://"):
            host = host[len("https://"):]
        host = host.split("/", 1)[0]
        self.conn = http.client.HTTPSConnection(host, timeout=self.timeout,
                                                context=ssl.create_default_context())
        try:
            self.conn.request("POST", self.path, body=self.body, headers=self.headers)
            resp = self.conn.getresponse()
            self.status = resp.status
            if resp.status != 200:
                self.error_body = resp.read()
        except (socket.timeout, TimeoutError) as e:
            raise TtsSynthesisError(TtsErrorCode.STREAM_TIMEOUT,
                                    f"ElevenLabs stream timed out: {e}", retryable=True)
        except (OSError, ssl.SSLError, ConnectionError) as e:
            raise TtsSynthesisError(TtsErrorCode.PROVIDER_UNAVAILABLE,
                                    f"ElevenLabs unreachable: {e}", retryable=True)

    def chunks(self, size: int = 32 * 1024) -> Iterator[bytes]:
        conn = self.conn
        if conn is None or conn.response is None:
            return
        while True:
            data = conn.response.read(size)
            if not data:
                break
            yield data
        conn.close()

    def close(self) -> None:
        try:
            if self.conn is not None:
                self.conn.close()
        except Exception:
            pass


class ElevenLabsTtsProvider(TtsProvider):
    provider_id = "elevenlabs"

    def __init__(self, api_key: str, api_base: str = API_BASE):
        self._key = api_key
        self._api_base = api_base.rstrip("/")
        self._catalog: Optional[List[TtsVoice]] = None
        self._catalog_ts: float = 0.0
        self._catalog_lock = threading.Lock()
        self._active_streams: Dict[str, _StreamingHttp] = {}
        self._streams_lock = threading.Lock()

    # ------------------------------------------------------------ catalog
    def list_voices(self, refresh: bool = False) -> List[TtsVoice]:
        with self._catalog_lock:
            if self._catalog is not None and not refresh and \
                    time.time() - self._catalog_ts < 3600:
                return list(self._catalog)
            loop = asyncio.new_event_loop()
            try:
                status, body = loop.run_until_complete(asyncio.to_thread(
                    self._http_get, "/v1/voices?limit=100"
                ))
            finally:
                loop.close()
            if status != 200:
                raise _decode_error(status, body)
            try:
                data = json.loads(body.decode("utf-8", "replace"))
            except Exception as e:
                raise TtsSynthesisError(TtsErrorCode.SYNTHESIS_FAILED,
                                        f"Voice catalog parse failed: {e}")
            voices: List[TtsVoice] = []
            for v in data.get("voices") or []:
                vid = v.get("voice_id")
                if not vid:
                    continue
                voices.append(TtsVoice(
                    provider="elevenlabs",
                    provider_voice_id=vid,
                    display_name=str(v.get("name") or vid),
                    languages=[v.get("language") or "en"] if v.get("language") else ["en"],
                    category=(v.get("labels") or {}).get("category") or "built_in",
                    description=v.get("description"),
                    custom=bool(v.get("labels", {}).get("cloned_voice")),
                ))
            self._catalog = voices
            self._catalog_ts = time.time()
            return list(voices)

    def invalidate_catalog_cache(self) -> None:
        with self._catalog_lock:
            self._catalog = None

    def _http_get(self, path: str) -> tuple:
        host = self._api_base[len("https://"):] if self._api_base.startswith("https://") else \
            self._api_base[len("http://"):]
        conn = http.client.HTTPSConnection(host.split("/", 1)[0], timeout=10.0)
        try:
            conn.request("GET", path, headers={"xi-api-key": self._key})
            resp = conn.getresponse()
            return resp.status, resp.read()
        finally:
            conn.close()

    # ------------------------------------------------------------ health
    def capabilities(self) -> ProviderCapabilities:
        probe = self.probe()
        configured = probe["configured"]
        healthy = configured and probe["reachable"]
        return ProviderCapabilities(
            provider=self.provider_id,
            available=healthy,
            configured=configured,
            healthy=healthy,
            models=list(MODELS.keys()),
            default_model=DEFAULT_MODEL,
            max_input_chars=MAX_INPUT_CHARS,
            supports_rate=False,  # ElevenLabs has no speed parameter on TTS
            supports_style_instructions=False,
            supports_voice_settings=True,
            supports_custom_voices=True,
            streaming=True,
            last_checked=now_iso(),
            error=None if healthy else "unreachable",
        )

    def probe(self) -> Dict:
        try:
            started = time.time()
            status, _ = self._http_get("/v1/user")
            latency = int((time.time() - started) * 1000)
            if status in (401, 403):
                return {"configured": True, "authentication": "failed",
                        "reachable": True, "latency_ms": latency,
                        "detail": "Stored credential rejected."}
            return {"configured": True, "authentication": "ok",
                    "reachable": status == 200, "latency_ms": latency,
                    "detail": None if status == 200 else f"HTTP {status}"}
        except (TtsSynthesisError, Exception) as e:
            return {"configured": True, "authentication": "skipped",
                    "reachable": False, "latency_ms": None,
                    "detail": getattr(e, "message", str(e))}

    # ------------------------------------------------------------ synthesis
    def synthesize(self, request: SynthesisRequest) -> Iterator[bytes]:
        if not request.text.strip():
            raise TtsSynthesisError(TtsErrorCode.INVALID_REQUEST, "Empty synthesis text.")
        if len(request.text) > MAX_INPUT_CHARS:
            raise TtsSynthesisError(
                TtsErrorCode.TEXT_TOO_LONG,
                f"Text exceeds ElevenLabs limit ({MAX_INPUT_CHARS} chars).")

        from .provider import parse_voice_id
        voice = parse_voice_id(request.voice_id, "elevenlabs")
        if voice is None:
            raise TtsSynthesisError(TtsErrorCode.VOICE_NOT_FOUND,
                                    "A voice id is required for ElevenLabs. "
                                    "Select a voice from the catalog.", retryable=False)

        options = request.provider_options or {}
        quality = str(options.get("quality_profile") or "interactive").lower()
        model = request.model or options.get("model") or QUALITY_MODEL_MAP.get(quality, DEFAULT_MODEL)
        if model not in MODELS:
            raise TtsSynthesisError(TtsErrorCode.MODEL_UNAVAILABLE,
                                    f"ElevenLabs model '{model}' not available.",
                                    retryable=False)

        voice_settings = {
            "stability": float(options.get("stability", 0.5)),
            "similarity_boost": float(options.get("similarity_boost", 0.75)),
            "style": float(options.get("style", 0.0)),
            "use_speaker_boost": bool(options.get("use_speaker_boost", True)),
        }
        optimize_latency = {"interactive": 4, "fast": 4, "balanced": 2,
                            "quality": 0}.get(quality, 4)

        payload = json.dumps({
            "text": request.text,
            "model_id": model,
            "voice_settings": voice_settings,
            "optimize_streaming_latency": optimize_latency,
        }).encode("utf-8")

        last_error: Optional[TtsSynthesisError] = None
        for attempt, try_model in enumerate(self._model_chain(model)):
            stream = _StreamingHttp(
                self._api_base,
                f"/v1/text-to-speech/{quote(voice, safe='')}"
                f"?output_format=pcm_24000&optimize_streaming_latency={optimize_latency}",
                {"xi-api-key": self._key, "Content-Type": "application/json"},
                payload.replace(b'"model_id": "%s"' % try_model.encode(),
                                b'"model_id": "%s"' % try_model.encode()),
            )
            # Rebuild payload for the fallback model (body was serialized above).
            payload = json.dumps({
                "text": request.text,
                "model_id": try_model,
                "voice_settings": voice_settings,
                "optimize_streaming_latency": optimize_latency,
            }).encode("utf-8")
            stream.body = payload
            try:
                stream.open()
            except TtsSynthesisError as e:
                stream.close()
                last_error = e
                if e.code == TtsErrorCode.MODEL_UNAVAILABLE and attempt < 2:
                    continue  # try fallback model
                if e.retryable and attempt == 0:
                    time.sleep(0.3)
                    continue
                raise
            if stream.status != 200:
                stream.close()
                err = _decode_error(stream.status, stream.error_body)
                if err.code == TtsErrorCode.MODEL_UNAVAILABLE and attempt < 2:
                    last_error = err
                    continue
                raise err
            # Success: stream PCM 24 kHz (already canonical).
            with self._streams_lock:
                self._active_streams[request.request_id] = stream
            try:
                for chunk in stream.chunks():
                    yield chunk
            finally:
                with self._streams_lock:
                    self._active_streams.pop(request.request_id, None)
                stream.close()
            return

        raise last_error or TtsSynthesisError(TtsErrorCode.SYNTHESIS_FAILED,
                                              "ElevenLabs synthesis failed.")

    def _model_chain(self, model: str) -> List[str]:
        for chain in MODEL_FALLBACKS:
            if model in chain:
                return chain[chain.index(model):]
        return [model]

    def cancel(self, stream_id: str) -> None:
        """Best-effort: drop the upstream connection to stop generation."""
        with self._streams_lock:
            stream = self._active_streams.pop(stream_id, None)
        if stream is not None:
            stream.close()
