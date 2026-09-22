"""
Study Agent PC Agent — remote TTS subsystem.

Provider ownership lives here, on the PC. Android never sees provider keys:

    Android (control plane: WebSocket JSON)
        │  tts_capabilities_request / tts_voices_request / tts_synthesize / tts_cancel
        ▼
    PC Agent (this package: provider adapters + stream manager + synthesis cache)
        │  provider adapters (OpenAI / ElevenLabs / deterministic mock)
        ▼
    Cloud provider (keys from environment / local secret store only)

    Android (media plane: authenticated HTTP)
        │  GET /v1/tts/stream/{stream_id}   (Bearer = Study Agent credential)
        ▼
    PC Agent streams normalized PCM s16le mono 24 kHz

Design rules (see docs/CLOUD_TTS.md):
  * Canonical media format: PCM s16le, mono, 24 000 Hz — native for both providers.
  * Every provider difference (auth headers, URLs, model ids, error codes) stays
    inside its adapter behind the TtsProvider interface.
  * Streams are single-use and short-lived; the control plane is the only place
    where request/response correlation happens.
  * Provider keys are NEVER logged, never sent to Android, never embedded in
    URLs. Diagnostics carry codes and latencies only.
"""

from .models import (
    CANONICAL_CHANNELS,
    CANONICAL_FORMAT,
    CANONICAL_SAMPLE_RATE,
    TtsErrorCode,
    TtsSynthesisError,
    TtsVoice,
)

__all__ = [
    "CANONICAL_CHANNELS",
    "CANONICAL_FORMAT",
    "CANONICAL_SAMPLE_RATE",
    "TtsErrorCode",
    "TtsSynthesisError",
    "TtsVoice",
]
