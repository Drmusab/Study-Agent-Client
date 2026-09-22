"""
Live provider tests (OpenAI / ElevenLabs) — skipped unless API keys exist.

CI (no keys) skips these automatically. With real keys they exercise the full
adapters: catalog fetch, capabilities probe, and a tiny paid synthesis in the
canonical PCM format. Keep the synthesis text tiny (cost).

Run:  OPENAI_API_KEY=... pytest test_tts_providers_live.py -v
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from tts.audio import pcm_duration_ms  # noqa: E402
from tts.models import SynthesisRequest  # noqa: E402

OPENAI_KEY = os.environ.get("OPENAI_API_KEY", "").strip()
ELEVENLABS_KEY = os.environ.get("ELEVENLABS_API_KEY", "").strip()

requires_openai = pytest.mark.skipif(not OPENAI_KEY,
                                     reason="OPENAI_API_KEY not set (CI uses mocks)")
requires_elevenlabs = pytest.mark.skipif(not ELEVENLABS_KEY,
                                         reason="ELEVENLABS_API_KEY not set (CI uses mocks)")


@requires_openai
def test_openai_catalog_and_probe():
    from tts.openai_provider import OpenAiTtsProvider
    p = OpenAiTtsProvider(OPENAI_KEY)
    probe = p.probe()
    assert probe["configured"] is True
    assert probe["reachable"] is True, probe
    voices = p.list_voices()
    ids = {v.provider_voice_id for v in voices}
    assert "coral" in ids and "alloy" in ids
    caps = p.capabilities()
    assert caps.configured and caps.healthy


@requires_openai
def test_openai_synthesis_canonical_pcm():
    from tts.openai_provider import OpenAiTtsProvider
    p = OpenAiTtsProvider(OPENAI_KEY)
    pcm = b"".join(p.synthesize(SynthesisRequest(
        request_id="live-test", provider="openai",
        text="Hello from the study agent live test.",
        voice_id="openai:alloy", model="gpt-4o-mini-tts", rate=1.0)))
    assert len(pcm) > 1000
    assert len(pcm) % 2 == 0
    assert pcm_duration_ms(pcm) > 200


@requires_elevenlabs
def test_elevenlabs_catalog_and_probe():
    from tts.elevenlabs_provider import ElevenLabsTtsProvider
    p = ElevenLabsTtsProvider(ELEVENLABS_KEY)
    probe = p.probe()
    assert probe["configured"] is True
    assert probe["reachable"] is True, probe
    voices = p.list_voices()
    assert voices
    assert all(v.voice_id.startswith("elevenlabs:") for v in voices)


@requires_elevenlabs
def test_elevenlabs_synthesis_canonical_pcm():
    from tts.elevenlabs_provider import ElevenLabsTtsProvider
    p = ElevenLabsTtsProvider(ELEVENLABS_KEY)
    voices = p.list_voices()
    voice = next((v for v in voices if "ar" in v.languages), voices[0])
    pcm = b"".join(p.synthesize(SynthesisRequest(
        request_id="live-test", provider="elevenlabs",
        text="Hello from the study agent live test.",
        voice_id=voice.voice_id, model="eleven_flash_v2_5", rate=1.0,
        provider_options={"quality_profile": "interactive"})))
    assert len(pcm) > 1000
    assert len(pcm) % 2 == 0
    assert pcm_duration_ms(pcm) > 200
