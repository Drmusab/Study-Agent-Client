"""
Provider abstraction: the ONLY place provider differences may live.

OpenAI and ElevenLabs differences (auth headers, URLs, model ids, voice catalog
shape, retry semantics) stay inside their adapters. Everything upstream — the
stream manager, the WebSocket handlers, the media HTTP server — only sees this
interface, so Android (and the protocol) never needs to know which cloud is
behind the agent.
"""

from __future__ import annotations

import abc
import time
from dataclasses import dataclass
from typing import Dict, Iterator, List, Optional

from .models import (
    ProviderCapabilities,
    SynthesisRequest,
    TtsSynthesisError,
    TtsVoice,
)


class TtsProvider(abc.ABC):
    """Common PC-side provider interface."""

    provider_id: str = ""

    # ------------------------------------------------------------ catalog
    @abc.abstractmethod
    def list_voices(self, refresh: bool = False) -> List[TtsVoice]:
        """Voices available to the configured account (cached by the agent)."""

    @abc.abstractmethod
    def capabilities(self) -> ProviderCapabilities:
        """Live capability + health block (never 'Ready' merely because a key exists)."""

    @abc.abstractmethod
    def probe(self) -> Dict:
        """Cheap health probe: configured / authentication / reachable / latency_ms."""

    # ------------------------------------------------------------ synthesis
    @abc.abstractmethod
    def synthesize(self, request: SynthesisRequest) -> Iterator[bytes]:
        """Yield PCM chunks in the canonical format (s16le mono 24 kHz).

        Must raise TtsSynthesisError with a typed TtsErrorCode on failure.
        Implementations must not retry paid synthesis indefinitely: bounded
        retries before the first byte only (see providers for policy).
        """

    def cancel(self, stream_id: str) -> None:
        """Best-effort upstream cancellation. Default no-op; never blocks."""
        return None

    def invalidate_catalog_cache(self) -> None:
        return None


@dataclass
class ProviderProbe:
    configured: bool
    authentication: str  # "ok" | "failed" | "skipped"
    reachable: bool
    latency_ms: Optional[int] = None
    detail: Optional[str] = None


def now_iso() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


def build_providers(env: Dict[str, str], force_mock: bool = False) -> Dict[str, TtsProvider]:
    """Construct the provider registry from environment.

    Determinism rule for CI: when force_mock is set (or the agent runs in mock
    mode) the *mock* providers are used even if keys exist, so contract tests
    never touch the network. When real keys are present and mock mode is off,
    the real adapters are used.
    """
    from .mock_provider import MockTtsProvider
    providers: Dict[str, TtsProvider] = {}

    if force_mock:
        providers["openai"] = MockTtsProvider("openai")
        providers["elevenlabs"] = MockTtsProvider("elevenlabs")
        return providers

    openai_key = env.get("OPENAI_API_KEY", "").strip()
    if openai_key:
        from .openai_provider import OpenAiTtsProvider
        providers["openai"] = OpenAiTtsProvider(openai_key)
    else:
        # No key: the provider still exists (so Android can show
        # "not configured on Study PC"), but reports configured=False.
        providers["openai"] = MockTtsProvider("openai", configured=False)

    elevenlabs_key = env.get("ELEVENLABS_API_KEY", "").strip()
    if elevenlabs_key:
        from .elevenlabs_provider import ElevenLabsTtsProvider
        providers["elevenlabs"] = ElevenLabsTtsProvider(elevenlabs_key)
    else:
        providers["elevenlabs"] = MockTtsProvider("elevenlabs", configured=False)

    return providers


def parse_voice_id(voice_id: Optional[str], provider_id: str) -> Optional[str]:
    """Validate a namespaced voice id and return the provider-local part.

    Accepts both the namespaced form ("openai:coral") and a bare id when the
    provider already scopes it. Returns None when the voice does not belong to
    [provider_id] (cross-provider confusion must be an error, not a passthrough).
    """
    if voice_id is None:
        return None
    vid = str(voice_id).strip()
    if not vid:
        return None
    if ":" in vid:
        prefix, local = vid.split(":", 1)
        if prefix != provider_id:
            raise TtsSynthesisError(
                "VOICE_NOT_FOUND",
                f"Voice '{vid}' does not belong to provider '{provider_id}'.",
            )
        return local
    return vid
