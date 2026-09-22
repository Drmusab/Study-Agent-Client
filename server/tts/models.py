"""
Wire + domain models for the PC Agent TTS subsystem.

These are the single source of truth for the canonical media format and the
typed provider error codes. Android maps the error codes (string values) to its
own ``SpeechErrorCode``; the strings are part of the protocol contract, so they
are stable and must never be changed casually (see docs/CLOUD_TTS.md).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

# ---------------------------------------------------------------------------
# Canonical media contract (PC Agent -> Android).
#
# Both OpenAI (pcm, 24 kHz) and ElevenLabs (pcm_24000) can emit this natively,
# so the agent performs NO resampling for the common path. Android feeds the
# bytes straight into an AudioTrack (16-bit, mono, 24 kHz) with low startup
# latency and precise cancellation.
# ---------------------------------------------------------------------------
CANONICAL_FORMAT = "pcm_s16le"
CANONICAL_SAMPLE_RATE = 24000
CANONICAL_CHANNELS = 1
BYTES_PER_SAMPLE = 2  # s16le


class TtsErrorCode:
    """Stable provider error codes. Android maps these to SpeechErrorCode."""

    PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
    PROVIDER_NOT_CONFIGURED = "PROVIDER_NOT_CONFIGURED"
    PROVIDER_AUTH_FAILED = "PROVIDER_AUTH_FAILED"
    PROVIDER_RATE_LIMITED = "PROVIDER_RATE_LIMITED"
    PROVIDER_QUOTA_EXCEEDED = "PROVIDER_QUOTA_EXCEEDED"
    VOICE_NOT_FOUND = "VOICE_NOT_FOUND"
    MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
    SYNTHESIS_FAILED = "SYNTHESIS_FAILED"
    STREAM_FAILED = "STREAM_FAILED"
    STREAM_TIMEOUT = "STREAM_TIMEOUT"
    STREAM_EXPIRED = "STREAM_EXPIRED"
    STREAM_NOT_FOUND = "STREAM_NOT_FOUND"
    STREAM_NOT_AUTHENTICATED = "STREAM_NOT_AUTHENTICATED"
    INVALID_REQUEST = "INVALID_REQUEST"
    TEXT_TOO_LONG = "TEXT_TOO_LONG"


class TtsSynthesisError(Exception):
    """Typed provider failure. [code] is a TtsErrorCode string."""

    def __init__(self, code: str, message: str, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable


@dataclass
class TtsVoice:
    """A single provider voice. ``voice_id`` is the *namespaced* id.

    Namespacing (``openai:coral`` / ``elevenlabs:<id>``) avoids collisions and
    lets Android persist one opaque string per provider.
    """

    provider: str
    provider_voice_id: str
    display_name: str
    languages: List[str] = field(default_factory=list)
    locale: Optional[str] = None
    category: Optional[str] = None  # e.g. "built_in", "multilingual", "cloned"
    description: Optional[str] = None
    preview_available: bool = True
    custom: bool = False

    @property
    def voice_id(self) -> str:
        return f"{self.provider}:{self.provider_voice_id}"


@dataclass
class ProviderCapabilities:
    """Per-provider capability + health block for the tts_capabilities response."""

    provider: str
    available: bool
    configured: bool
    healthy: bool
    models: List[str] = field(default_factory=list)
    default_model: Optional[str] = None
    max_input_chars: Optional[int] = None
    supports_rate: bool = True
    supports_style_instructions: bool = False
    supports_voice_settings: bool = False
    supports_custom_voices: bool = False
    streaming: bool = True
    last_checked: Optional[str] = None
    error: Optional[str] = None

    def to_wire(self) -> Dict:
        return {
            "provider": self.provider,
            "available": self.available,
            "configured": self.configured,
            "healthy": self.healthy,
            "models": self.models,
            "default_model": self.default_model,
            "max_input_chars": self.max_input_chars,
            "supports_rate": self.supports_rate,
            "supports_style_instructions": self.supports_style_instructions,
            "supports_voice_settings": self.supports_voice_settings,
            "supports_custom_voices": self.supports_custom_voices,
            "streaming": self.streaming,
            "last_checked": self.last_checked,
            "error": self.error,
        }


@dataclass
class SynthesisRequest:
    """Normalized synthesis request coming over the control plane.

    The PC Agent owns provider-specific translation; the request only carries
    provider-agnostic fields plus an optional raw ``provider_options`` map for
    provider-specific knobs (style instructions, voice settings) that Android
    passes through opaquely.
    """

    request_id: str
    provider: str
    text: str
    voice_id: Optional[str] = None  # namespaced "provider:voice"
    model: Optional[str] = None
    language: Optional[str] = None  # "en" | "ar" | None
    rate: float = 1.0
    purpose: Optional[str] = None
    provider_options: Dict = field(default_factory=dict)

    @classmethod
    def from_wire(cls, raw: Dict) -> "SynthesisRequest":
        return cls(
            request_id=str(raw.get("request_id", "")),
            provider=str(raw.get("provider", "")),
            text=str(raw.get("text", "")),
            voice_id=raw.get("voice_id"),
            model=raw.get("model"),
            language=raw.get("language"),
            rate=float(raw.get("rate", 1.0) or 1.0),
            purpose=raw.get("purpose"),
            provider_options=dict(raw.get("provider_options") or {}),
        )
