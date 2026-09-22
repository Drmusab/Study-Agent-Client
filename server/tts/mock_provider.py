"""
Deterministic mock TTS provider — the backbone of CI.

Produces reproducible, *speech-shaped* PCM (per-word pacing, per-voice timbre,
rate-dependent duration) without any network access, so the full Android ↔
agent contract — capabilities, catalog, synthesis, streaming, cancel, caching,
error mapping — is testable deterministically on every PR.

Failure injection (for contract tests) is set per instance:
    provider.failure = "rate_limit" | "quota" | "auth" | "synthesis"
    | "model" | "voice" | None
"""

from __future__ import annotations

import hashlib
import math
import random
import re
from typing import Dict, Iterator, List, Optional

from .audio import chunk_bytes
from .models import (
    CANONICAL_SAMPLE_RATE,
    ProviderCapabilities,
    SynthesisRequest,
    TtsErrorCode,
    TtsSynthesisError,
    TtsVoice,
)
from .provider import TtsProvider, now_iso

# Built-in voice catalogs (namespaced per provider).
_OPENAI_VOICES = [
    "alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova",
    "sage", "shimmer", "verse", "marin", "cedar",
]
_ELEVENLABS_VOICES = [
    ("21m00Tcm4TlvDq8ikWAM", "Rachel", ["en"], "multilingual", "Bright, versatile narration voice."),
    ("AZnzlk1XvdvUeBnXmlld", "Adam", ["en"], "multilingual", "Deep, calm narration voice."),
    ("EXAVITQu4vr4xnSDxMaL", "Bella", ["en", "es"], "multilingual", "Expressive storytelling voice."),
    ("MF3mGyEYCl7XYWbV9V6O", "Antoni", ["en"], "multilingual", "Smooth conversation voice."),
    ("TxGEqnHWrfWFTfGW9XjX", "Charlotte", ["en", "fr"], "multilingual", "Friendly French-English voice."),
    ("onwK4e9ZLuTAKqWW03F9", "James", ["en"], "multilingual", "Documentary narration voice."),
    ("SAcPv14ZmGJUA7x4MlKE0", "Arabic (Sample)", ["ar"], "multilingual", "عربي: صوت دراستي — عربي فصيح."),
    ("VlXmT0dU0XTIy0iSHK4C1", "Arabic Dialect (Sample)", ["ar"], "multilingual", "عربي: لهجة مريحة للاستذكار."),
]


class MockTtsProvider(TtsProvider):
    """Deterministic offline provider. Double-duty: real mock AND 'not
    configured' sentinel when [configured] is False."""

    MAX_INPUT_CHARS = 4000

    def __init__(self, provider_id: str, configured: bool = True, failure: Optional[str] = None):
        self.provider_id = provider_id
        self._configured = configured
        self.failure = failure
        self._catalog: List[TtsVoice] = self._build_catalog()
        self._catalog_ts: Optional[str] = None
        self.synth_count = 0
        self.cancelled_streams: List[str] = []

    # ------------------------------------------------------------ catalog
    def _build_catalog(self) -> List[TtsVoice]:
        if self.provider_id == "openai":
            return [
                TtsVoice(
                    provider="openai",
                    provider_voice_id=v,
                    display_name=v.capitalize(),
                    languages=["en"],
                    locale="en-US",
                    category="built_in",
                    description=f"OpenAI built-in voice '{v}'.",
                )
                for v in _OPENAI_VOICES
            ]
        return [
            TtsVoice(
                provider="elevenlabs",
                provider_voice_id=vid,
                display_name=name,
                languages=languages,
                locale=languages[0],
                category=category,
                description=description,
            )
            for (vid, name, languages, category, description) in _ELEVENLABS_VOICES
        ]

    def list_voices(self, refresh: bool = False) -> List[TtsVoice]:
        if self._fails_auth():
            raise TtsSynthesisError(
                TtsErrorCode.PROVIDER_AUTH_FAILED, "Authentication failed.", retryable=False
            )
        if refresh:
            self._catalog = self._build_catalog()
        self._catalog_ts = now_iso()
        return list(self._catalog)

    def invalidate_catalog_cache(self) -> None:
        self._catalog_ts = None

    # ------------------------------------------------------------ health
    def _fails_auth(self) -> bool:
        return self.failure == "auth"

    def capabilities(self) -> ProviderCapabilities:
        if self.provider_id == "openai":
            models = ["gpt-4o-mini-tts", "tts-1", "tts-1-hd"]
            default = "gpt-4o-mini-tts"
            supports_rate = True
            supports_style = True
            supports_custom = True
            supports_voice_settings = False
        else:
            models = ["eleven_flash_v2_5", "eleven_turbo_v2_5", "eleven_multilingual_v2", "eleven_v3"]
            default = "eleven_flash_v2_5"
            supports_rate = False  # ElevenLabs TTS has no speed parameter
            supports_style = False
            supports_custom = True
            supports_voice_settings = True

        # configured = a key exists on this PC. healthy = configured AND the
        # credential actually works. Transient synthesis failures (rate limit,
        # quota) do NOT change health — they surface as typed per-request
        # errors, exactly like the real providers behave.
        configured = self._configured
        auth_failed = self._fails_auth()
        healthy = configured and not auth_failed
        error = ("not_configured" if not configured
                 else "auth_failed" if auth_failed
                 else None)

        return ProviderCapabilities(
            provider=self.provider_id,
            available=healthy,
            configured=configured,
            healthy=healthy,
            models=models,
            default_model=default,
            max_input_chars=self.MAX_INPUT_CHARS,
            supports_rate=supports_rate,
            supports_style_instructions=supports_style,
            supports_voice_settings=supports_voice_settings,
            supports_custom_voices=supports_custom,
            streaming=True,
            last_checked=now_iso(),
            error=error,
        )

    def probe(self) -> Dict:
        if not self._configured:
            return {
                "configured": False,
                "authentication": "skipped",
                "reachable": False,
                "latency_ms": None,
                "detail": "No provider key configured on this PC Agent.",
            }
        if self.failure == "auth":
            return {
                "configured": True,
                "authentication": "failed",
                "reachable": True,
                "latency_ms": 8,
                "detail": "Provider rejected the stored credential.",
            }
        return {
            "configured": True,
            "authentication": "ok",
            "reachable": True,
            "latency_ms": 8,
            "detail": None,
        }

    # ------------------------------------------------------------ synthesis
    def synthesize(self, request: SynthesisRequest) -> Iterator[bytes]:
        self._enforce(request)
        self.synth_count += 1
        pcm = self._render(request)
        # Stream in bounded chunks like a real provider would.
        yield from chunk_bytes(pcm, size=2400)

    def cancel(self, stream_id: str) -> None:
        # Best-effort: the mock records cancellations for test assertions.
        self.cancelled_streams.append(stream_id)

    def _enforce(self, request: SynthesisRequest) -> None:
        if request.provider != self.provider_id:
            raise TtsSynthesisError(
                TtsErrorCode.INVALID_REQUEST,
                f"Provider mismatch: {request.provider} != {self.provider_id}.",
            )
        if not self._configured:
            raise TtsSynthesisError(
                TtsErrorCode.PROVIDER_NOT_CONFIGURED,
                f"{self.provider_id} is not configured on this PC Agent.",
                retryable=False,
            )
        if self._fails_auth():
            raise TtsSynthesisError(
                TtsErrorCode.PROVIDER_AUTH_FAILED,
                "Provider rejected the stored credential.",
                retryable=False,
            )
        if self.failure == "rate_limit":
            raise TtsSynthesisError(
                TtsErrorCode.PROVIDER_RATE_LIMITED,
                f"{self.provider_id} is rate limiting (HTTP 429).",
                retryable=True,
            )
        if self.failure == "quota":
            raise TtsSynthesisError(
                TtsErrorCode.PROVIDER_QUOTA_EXCEEDED,
                f"{self.provider_id} quota/billing unavailable.",
                retryable=False,
            )
        if self.failure == "synthesis":
            raise TtsSynthesisError(
                TtsErrorCode.SYNTHESIS_FAILED,
                f"{self.provider_id} synthesis failed (injected).",
                retryable=True,
            )
        if not request.text or not request.text.strip():
            raise TtsSynthesisError(
                TtsErrorCode.INVALID_REQUEST, "Empty synthesis text."
            )
        if len(request.text) > self.MAX_INPUT_CHARS:
            raise TtsSynthesisError(
                TtsErrorCode.TEXT_TOO_LONG,
                f"Text exceeds provider limit ({self.MAX_INPUT_CHARS} chars).",
            )
        models = self.capabilities().models
        if request.model is not None and request.model not in models:
            raise TtsSynthesisError(
                TtsErrorCode.MODEL_UNAVAILABLE,
                f"Model '{request.model}' is not available on {self.provider_id}.",
                retryable=False,
            )
        voice = self._resolve_voice(request.voice_id)
        if request.voice_id is not None and voice is None:
            raise TtsSynthesisError(
                TtsErrorCode.VOICE_NOT_FOUND,
                f"Voice '{request.voice_id}' not found on {self.provider_id}.",
                retryable=False,
            )

    def _resolve_voice(self, voice_id: Optional[str]) -> Optional[TtsVoice]:
        if voice_id is None:
            return None
        from .provider import parse_voice_id
        local = parse_voice_id(voice_id, self.provider_id)
        if local is None:
            return None
        for v in self._catalog:
            if v.provider_voice_id == local:
                return v
        return None

    # ------------------------------------------------------------ audio
    def _render(self, request: SynthesisRequest) -> bytes:
        """Deterministic speech-shaped audio.

        The exact bytes are a pure function of
        (text, voice, model, rate, provider_options) — the same inputs always
        produce identical PCM, which is what makes the whole pipeline
        deterministic in CI (repeat/cancel/cache tests rely on it).
        """
        seed_src = "|".join([
            request.provider,
            str(request.voice_id or ""),
            str(request.model or ""),
            f"{float(request.rate):.3f}",
            str(sorted((request.provider_options or {}).items())),
            " ".join(request.text.split()),
        ])
        seed = int(hashlib.sha256(seed_src.encode("utf-8")).hexdigest()[:12], 16)
        rng = random.Random(seed)

        words = request.text.split() or ["."]
        voice = self._resolve_voice(request.voice_id) or self._catalog[0]
        f0 = 110.0 + (int(voice.provider_voice_id.encode("utf-8")[0]) % 12) * 9.0

        rate = min(max(request.rate, 0.25), 4.0) or 1.0
        sr = CANONICAL_SAMPLE_RATE

        # Build a per-word amplitude timeline (speech pacing, rate-dependent).
        amplitudes: List[float] = []
        per_word_ms = lambda w: max(140.0, min(620.0, 60.0 + 55.0 * len(w))) / rate
        for w in words:
            dur_samples = int(per_word_ms(w) / 1000.0 * sr)
            attack = max(1, dur_samples // 8)
            for i in range(dur_samples):
                if i < attack:
                    env = i / attack
                else:
                    env = 1.0 - 0.35 * ((i - attack) / max(1, dur_samples - attack))
                amplitudes.append(env)
            amplitudes.extend([0.0] * int(0.045 * sr / rate))  # inter-word gap

        n = len(amplitudes)
        phase = 0.0
        phase_inc = 2.0 * math.pi * f0 / sr
        second_f = f0 * 2.01  # harmonic with slow beating
        out = bytearray()
        second_phase = 0.0
        second_inc = 2.0 * math.pi * second_f / sr
        for i in range(n):
            phase += phase_inc
            second_phase += second_inc
            amp = amplitudes[i] * 0.42
            sample = (math.sin(phase) + 0.35 * math.sin(second_phase)) * amp
            # tiny deterministic noise for a less "pure sine" feel
            sample += rng.uniform(-0.01, 0.01) * amp
            value = int(max(-1.0, min(1.0, sample)) * 32767)
            out += value.to_bytes(2, "little", signed=True)
        return bytes(out)
