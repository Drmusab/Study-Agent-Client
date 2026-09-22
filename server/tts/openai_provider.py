"""
OpenAI TTS provider (PC-side only).

Verified against the current official API surface (2026):
  * POST https://api.openai.com/v1/audio/speech
  * models: gpt-4o-mini-tts (instructions + speed, 24 kHz PCM), tts-1, tts-1-hd
  * voices: alloy, ash, ballad, coral, echo, fable, onyx, nova, sage, shimmer,
    verse, marin, cedar (built-ins) + account custom voices via
    GET /v1/realtime/voices (best-effort, cached)
  * response_format "pcm" is 16-bit signed little-endian at 24 kHz (OpenAI TTS
    always outputs 24 kHz), so no resampling is needed for the canonical path.

The provider NEVER retries paid synthesis after the first byte may have been
emitted; bounded retries only on connection-level failures before any audio.
"""

from __future__ import annotations

import asyncio
import json
import os
import threading
import time
import urllib.error
import urllib.request
from typing import Dict, Iterator, List, Optional

from .audio import chunk_bytes
from .models import (
    ProviderCapabilities,
    SynthesisRequest,
    TtsErrorCode,
    TtsSynthesisError,
    TtsVoice,
)
from .provider import TtsProvider, now_iso

API_BASE = os.environ.get("OPENAI_API_BASE", "https://api.openai.com/v1")

BUILTIN_VOICES = [
    ("alloy", "Alloy", "Neutral, balanced"),
    ("ash", "Ash", "Dry, grounded"),
    ("ballad", "Ballad", "Warm, melodic"),
    ("coral", "Coral", "Bright, upbeat"),
    ("echo", "Echo", "Clear, even"),
    ("fable", "Fable", "Storyteller"),
    ("onyx", "Onyx", "Deep, rich"),
    ("nova", "Nova", "Crisp, precise"),
    ("sage", "Sage", "Calm, considered"),
    ("shimmer", "Shimmer", "Light, airy"),
    ("verse", "Verse", "Musical, expressive"),
    ("marin", "Marin", "Soft, friendly"),
    ("cedar", "Cedar", "Steady, dependable"),
]

# Models that accept the `instructions` + `speed` parameters.
INSTRUCTION_MODELS = {"gpt-4o-mini-tts"}
SPEED_MODELS = {"gpt-4o-mini-tts", "tts-1", "tts-1-hd"}
MODELS = ["gpt-4o-mini-tts", "tts-1", "tts-1-hd"]
MAX_INPUT_CHARS = 4000


def _http_json(method: str, url: str, headers: Dict[str, str],
               body: Optional[bytes], timeout: float) -> tuple:
    """Blocking HTTP helper (run in a worker thread). Returns (status, body_bytes)."""
    req = urllib.request.Request(url, data=body, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        try:
            data = e.read()
        except Exception:
            data = b""
        return e.code, data
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        raise TtsSynthesisError(
            TtsErrorCode.PROVIDER_UNAVAILABLE,
            f"OpenAI unreachable: {getattr(e, 'reason', e)}",
            retryable=True,
        )


def _decode_error(status: int, body: bytes) -> TtsSynthesisError:
    detail = ""
    try:
        payload = json.loads(body.decode("utf-8", "replace"))
        err = payload.get("error") or {}
        detail = str(err.get("message") or err.get("code") or "")
    except Exception:
        detail = body[:200].decode("utf-8", "replace")

    if status in (401, 403):
        return TtsSynthesisError(TtsErrorCode.PROVIDER_AUTH_FAILED,
                                 f"OpenAI auth failed (HTTP {status}). {detail}", retryable=False)
    if status == 429:
        return TtsSynthesisError(TtsErrorCode.PROVIDER_RATE_LIMITED,
                                 f"OpenAI rate limited (HTTP 429). {detail}", retryable=True)
    if status == 402 or "insufficient" in detail.lower() or "quota" in detail.lower():
        return TtsSynthesisError(TtsErrorCode.PROVIDER_QUOTA_EXCEEDED,
                                 f"OpenAI quota/billing unavailable. {detail}", retryable=False)
    if status == 400:
        low = detail.lower()
        if "voice" in low:
            return TtsSynthesisError(TtsErrorCode.VOICE_NOT_FOUND,
                                     f"OpenAI rejected the voice: {detail}", retryable=False)
        if "model" in low:
            return TtsSynthesisError(TtsErrorCode.MODEL_UNAVAILABLE,
                                     f"OpenAI rejected the model: {detail}", retryable=False)
    return TtsSynthesisError(TtsErrorCode.SYNTHESIS_FAILED,
                             f"OpenAI synthesis failed (HTTP {status}). {detail}",
                             retryable=status >= 500)


class OpenAiTtsProvider(TtsProvider):
    provider_id = "openai"

    def __init__(self, api_key: str, api_base: str = API_BASE):
        self._key = api_key
        self._api_base = api_base.rstrip("/")
        self._catalog: Optional[List[TtsVoice]] = None
        self._catalog_ts: float = 0.0
        self._catalog_lock = threading.Lock()

    # ------------------------------------------------------------ catalog
    def list_voices(self, refresh: bool = False) -> List[TtsVoice]:
        with self._catalog_lock:
            if self._catalog is not None and not refresh and \
                    time.time() - self._catalog_ts < 3600:
                return list(self._catalog)
            voices = [
                TtsVoice(provider="openai", provider_voice_id=vid, display_name=name,
                         languages=["en"], locale="en-US", category="built_in",
                         description=desc)
                for (vid, name, desc) in BUILTIN_VOICES
            ]
            voices.extend(self._fetch_custom_voices())
            self._catalog = voices
            self._catalog_ts = time.time()
            return list(voices)

    def invalidate_catalog_cache(self) -> None:
        with self._catalog_lock:
            self._catalog = None

    def _fetch_custom_voices(self) -> List[TtsVoice]:
        """Best-effort account custom voices (Realtime voices API). Failures are
        swallowed: the built-ins remain the catalog, and the probe reports it."""
        try:
            loop = asyncio.new_event_loop()
            status, body = loop.run_until_complete(
                asyncio.to_thread(
                    _http_json, "GET", f"{self._api_base}/realtime/voices",
                    {"Authorization": f"Bearer {self._key}"}, None, 8.0,
                )
            )
            loop.close()
        except TtsSynthesisError:
            return []
        if status != 200:
            return []
        try:
            data = json.loads(body.decode("utf-8", "replace"))
            items = data.get("data") or []
            out: List[TtsVoice] = []
            for item in items[:64]:
                vid = item.get("id")
                if not vid:
                    continue
                out.append(TtsVoice(
                    provider="openai", provider_voice_id=vid,
                    display_name=str(item.get("name") or vid),
                    languages=item.get("language") or ["en"],
                    category="custom", custom=True,
                    description=item.get("description"),
                ))
            return out
        except Exception:
            return []

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
            models=list(MODELS),
            default_model="gpt-4o-mini-tts",
            max_input_chars=MAX_INPUT_CHARS,
            supports_rate=True,
            supports_style_instructions=True,
            supports_voice_settings=False,
            supports_custom_voices=True,
            streaming=True,
            last_checked=now_iso(),
            error=None if healthy else "unreachable",
        )

    def probe(self) -> Dict:
        # GET /models is the cheapest authenticated reachability check.
        try:
            started = time.time()
            status, _ = _http_json("GET", f"{self._api_base}/models",
                                   {"Authorization": f"Bearer {self._key}"}, None, 8.0)
            latency = int((time.time() - started) * 1000)
            if status in (401, 403):
                return {"configured": True, "authentication": "failed",
                        "reachable": True, "latency_ms": latency,
                        "detail": "Stored credential rejected."}
            return {"configured": True, "authentication": "ok",
                    "reachable": status == 200, "latency_ms": latency,
                    "detail": None if status == 200 else f"HTTP {status}"}
        except TtsSynthesisError as e:
            return {"configured": True, "authentication": "skipped",
                    "reachable": False, "latency_ms": None, "detail": e.message}

    # ------------------------------------------------------------ synthesis
    def synthesize(self, request: SynthesisRequest) -> Iterator[bytes]:
        if not request.text.strip():
            raise TtsSynthesisError(TtsErrorCode.INVALID_REQUEST, "Empty synthesis text.")
        if len(request.text) > MAX_INPUT_CHARS:
            raise TtsSynthesisError(
                TtsErrorCode.TEXT_TOO_LONG,
                f"Text exceeds OpenAI limit ({MAX_INPUT_CHARS} chars).")
        model = request.model or "gpt-4o-mini-tts"
        if model not in MODELS:
            raise TtsSynthesisError(TtsErrorCode.MODEL_UNAVAILABLE,
                                    f"OpenAI model '{model}' not available.", retryable=False)
        from .provider import parse_voice_id
        voice = parse_voice_id(request.voice_id, "openai")
        if voice is None:
            voice = "alloy"
        # Validate the voice before paying for synthesis.
        if not any(v.provider_voice_id == voice for v in self.list_voices()):
            raise TtsSynthesisError(TtsErrorCode.VOICE_NOT_FOUND,
                                    f"OpenAI voice '{voice}' not found.", retryable=False)

        payload: Dict = {
            "model": model,
            "input": request.text,
            "voice": voice,
            "response_format": "pcm",
        }
        if model in SPEED_MODELS:
            payload["speed"] = min(max(float(request.rate or 1.0), 0.25), 4.0)
        instructions = (request.provider_options or {}).get("instructions")
        if instructions and model in INSTRUCTION_MODELS:
            payload["instructions"] = str(instructions)[:2048]

        body = json.dumps(payload).encode("utf-8")
        headers = {
            "Authorization": f"Bearer {self._key}",
            "Content-Type": "application/json",
        }

        # Bounded retries ONLY before the first byte (connection-level failures).
        last_error: Optional[TtsSynthesisError] = None
        for attempt in range(2):
            try:
                status, data = _http_json("POST", f"{self._api_base}/audio/speech",
                                          headers, body, 60.0)
            except TtsSynthesisError as e:
                last_error = e
                if e.retryable and attempt == 0:
                    time.sleep(0.3)
                    continue
                raise
            break
        else:
            raise last_error or TtsSynthesisError(TtsErrorCode.SYNTHESIS_FAILED,
                                                  "OpenAI request failed.")

        if status != 200 or not data:
            raise _decode_error(status, data)

        # PCM s16le mono 24 kHz — already canonical; stream in chunks.
        yield from chunk_bytes(data, size=32 * 1024)
