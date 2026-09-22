"""
Unit tests: deterministic mock provider, audio normalization, bounded cache.

These pin the properties the whole pipeline relies on:
  * determinism (same input → identical bytes),
  * canonical PCM contract (s16le mono 24 kHz),
  * resampler correctness,
  * cache key hygiene (no plaintext content in keys),
  * cache bounds (entries / bytes / age),
  * typed error mapping for every provider failure mode.

Run:  pytest test_tts_mock_provider.py -v
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from tts.audio import chunk_bytes, normalize_to_canonical, pcm_duration_ms, resample_linear
from tts.cache import BoundedPcmCache, synthesis_cache_key
from tts.mock_provider import MockTtsProvider
from tts.models import (
    CANONICAL_SAMPLE_RATE,
    ProviderCapabilities,
    SynthesisRequest,
    TtsErrorCode,
    TtsSynthesisError,
)


def _req(provider="openai", voice="openai:coral", text="Hello study agent.",
         model=None, rate=1.0, options=None, request_id="r1"):
    return SynthesisRequest(
        request_id=request_id, provider=provider, text=text,
        voice_id=voice, model=model, rate=rate,
        provider_options=options or {})


def _pcm(provider):
    pid = provider.provider_id
    voice = f"{pid}:coral" if pid == "openai" else "elevenlabs:21m00Tcm4TlvDq8ikWAM"
    return b"".join(provider.synthesize(_req(provider=pid, voice=voice)))


# ---------------------------------------------------------------------------
# Mock provider determinism + contract
# ---------------------------------------------------------------------------

def test_deterministic_output_identical_bytes():
    p1, p2 = MockTtsProvider("openai"), MockTtsProvider("openai")
    assert _pcm(p1) == _pcm(p2)


def test_different_voice_different_bytes():
    p = MockTtsProvider("openai")
    a = b"".join(p.synthesize(_req(voice="openai:coral")))
    b = b"".join(p.synthesize(_req(voice="openai:onyx")))
    assert a != b


def test_rate_changes_duration():
    p = MockTtsProvider("openai")
    text = " ".join(["cardiovascular"] * 20)
    slow = b"".join(p.synthesize(_req(text=text, rate=0.5)))
    fast = b"".join(p.synthesize(_req(text=text, rate=2.0)))
    assert pcm_duration_ms(fast) < pcm_duration_ms(slow)


def test_arabic_text_produces_audio():
    p = MockTtsProvider("elevenlabs")
    pcm = b"".join(p.synthesize(_req(
        provider="elevenlabs", voice="elevenlabs:SAcPv14ZmGJUA7x4MlKE0",
        text="ما هي دواعي إجلاء الورم الدموي فوق الجافية؟")))
    assert len(pcm) > 1000
    assert pcm_duration_ms(pcm) > 100


def test_mixed_language_text_produces_audio():
    p = MockTtsProvider("elevenlabs")
    pcm = b"".join(p.synthesize(_req(
        provider="elevenlabs", voice="elevenlabs:21m00Tcm4TlvDq8ikWAM",
        text="المريض لديه epidural hematoma مع GCS 15/15")))
    assert len(pcm) > 1000


def test_synthesis_count_and_cancel_recording():
    p = MockTtsProvider("openai")
    list(p.synthesize(_req()))
    assert p.synth_count == 1
    p.cancel("st_test")
    assert "st_test" in p.cancelled_streams


# ---------------------------------------------------------------------------
# Typed error mapping
# ---------------------------------------------------------------------------

def _expect_error(provider, failure, code):
    p = MockTtsProvider(provider, failure=failure)
    try:
        list(p.synthesize(_req(provider=provider,
                               voice=f"openai:coral" if provider == "openai"
                               else "elevenlabs:21m00Tcm4TlvDq8ikWAM")))
        raise AssertionError("expected TtsSynthesisError")
    except TtsSynthesisError as e:
        assert e.code == code, f"{e.code} != {code}"


def test_error_rate_limit():
    _expect_error("openai", "rate_limit", TtsErrorCode.PROVIDER_RATE_LIMITED)


def test_error_quota():
    _expect_error("elevenlabs", "quota", TtsErrorCode.PROVIDER_QUOTA_EXCEEDED)


def test_error_auth():
    _expect_error("openai", "auth", TtsErrorCode.PROVIDER_AUTH_FAILED)


def test_error_synthesis():
    _expect_error("openai", "synthesis", TtsErrorCode.SYNTHESIS_FAILED)


def test_error_unknown_voice():
    p = MockTtsProvider("openai")
    try:
        list(p.synthesize(_req(voice="openai:nope")))
        raise AssertionError("expected TtsSynthesisError")
    except TtsSynthesisError as e:
        assert e.code == TtsErrorCode.VOICE_NOT_FOUND


def test_error_cross_provider_voice():
    p = MockTtsProvider("openai")
    try:
        list(p.synthesize(_req(voice="elevenlabs:21m00Tcm4TlvDq8ikWAM")))
        raise AssertionError("expected TtsSynthesisError")
    except TtsSynthesisError as e:
        assert e.code == TtsErrorCode.VOICE_NOT_FOUND


def test_error_unknown_model():
    p = MockTtsProvider("openai")
    try:
        list(p.synthesize(_req(model="gpt-9")))
        raise AssertionError("expected TtsSynthesisError")
    except TtsSynthesisError as e:
        assert e.code == TtsErrorCode.MODEL_UNAVAILABLE


def test_error_text_too_long():
    p = MockTtsProvider("openai")
    try:
        list(p.synthesize(_req(text="x " * 5000)))
        raise AssertionError("expected TtsSynthesisError")
    except TtsSynthesisError as e:
        assert e.code == TtsErrorCode.TEXT_TOO_LONG


def test_not_configured_sentinel():
    p = MockTtsProvider("openai", configured=False)
    caps = p.capabilities()
    assert caps.configured is False
    assert caps.available is False
    assert caps.error == "not_configured"
    try:
        list(p.synthesize(_req()))
        raise AssertionError("expected TtsSynthesisError")
    except TtsSynthesisError as e:
        assert e.code == TtsErrorCode.PROVIDER_NOT_CONFIGURED
    assert p.probe()["configured"] is False


def test_capabilities_shape_openai():
    caps = MockTtsProvider("openai").capabilities()
    assert isinstance(caps, ProviderCapabilities)
    assert caps.provider == "openai"
    assert caps.default_model == "gpt-4o-mini-tts"
    assert caps.supports_style_instructions is True
    assert caps.max_input_chars == 4000
    wire = caps.to_wire()
    assert wire["provider"] == "openai"
    assert "models" in wire and "default_model" in wire
    # No secrets in the wire block.
    blob = str(wire).lower()
    assert "sk-" not in blob and "key" not in blob.replace("keys", "")


def test_capabilities_shape_elevenlabs():
    caps = MockTtsProvider("elevenlabs").capabilities()
    assert caps.default_model == "eleven_flash_v2_5"
    assert caps.supports_voice_settings is True
    assert caps.supports_rate is False


def test_catalog_namespacing():
    for provider in ("openai", "elevenlabs"):
        p = MockTtsProvider(provider)
        voices = p.list_voices()
        assert voices, "catalog must not be empty"
        for v in voices:
            assert v.voice_id.startswith(f"{provider}:")


# ---------------------------------------------------------------------------
# Audio utilities
# ---------------------------------------------------------------------------

def _sine_24k(duration_ms=100, freq=220.0):
    import array, math
    n = CANONICAL_SAMPLE_RATE * duration_ms // 1000
    out = array.array("h")
    for i in range(n):
        out.append(int(math.sin(2 * math.pi * freq * i / CANONICAL_SAMPLE_RATE) * 16000))
    return out.tobytes()


def test_pcm_duration_ms():
    pcm = _sine_24k(200)
    assert pcm_duration_ms(pcm) == 200


def test_resample_identity():
    pcm = _sine_24k()
    assert resample_linear(pcm, 24000, 24000) == pcm


def test_resample_48k_to_24k_halves_length():
    # Build a 48k signal by up-sampling the 24k one (insert zeros → 48k).
    import array
    base = array.array("h"); base.frombytes(_sine_24k(100))
    up = array.array("h")
    for s in base:
        up.append(s)
        up.append(0)
    pcm48 = up.tobytes()
    out = resample_linear(pcm48, 48000, 24000)
    assert len(out) == len(base.tobytes())
    # The signal energy is preserved (sine stays a sine).
    restored = array.array("h"); restored.frombytes(out)
    assert any(abs(s) > 5000 for s in restored)


def test_normalize_multichannel_to_mono():
    import array
    # Stereo: left +1000, right -1000 per frame → mono average 0.
    frames = array.array("h")
    for _ in range(100):
        frames.append(1000)
        frames.append(-1000)
    mono = normalize_to_canonical(frames.tobytes(), src_rate=24000, src_channels=2)
    out = array.array("h"); out.frombytes(mono)
    assert all(s == 0 for s in out)


def test_chunk_bytes():
    data = b"a" * 100
    assert chunk_bytes(data, 30) == [b"a" * 30, b"a" * 30, b"a" * 30, b"a" * 10]
    assert b"".join(chunk_bytes(data, 30)) == data


# ---------------------------------------------------------------------------
# Cache
# ---------------------------------------------------------------------------

def test_cache_key_no_plaintext():
    text = "What are the indications for evacuation of an epidural hematoma?"
    k1 = synthesis_cache_key("openai", "gpt-4o-mini-tts", "openai:coral", 1.0, {}, text)
    k2 = synthesis_cache_key("openai", "gpt-4o-mini-tts", "openai:coral", 1.0, {}, text)
    assert k1 == k2
    assert text not in k1
    assert "epidural" not in k1
    assert len(k1) == 64  # sha256 hex

    # Any differing parameter changes the key.
    assert synthesis_cache_key("openai", "gpt-4o-mini-tts", "openai:nova", 1.0, {}, text) != k1
    assert synthesis_cache_key("openai", "gpt-4o-mini-tts", "openai:coral", 1.5, {}, text) != k1
    assert synthesis_cache_key("openai", "gpt-9", "openai:coral", 1.0, {}, text) != k1
    assert synthesis_cache_key("openai", "gpt-4o-mini-tts", "openai:coral", 1.0,
                               {"instructions": "calm"}, text) != k1
    # Whitespace normalization: cosmetic differences share a key.
    assert synthesis_cache_key(
        "openai", "gpt-4o-mini-tts", "openai:coral", 1.0, {},
        "  " + text.replace(" ", "   ") + "  ") == k1


def test_cache_lru_and_byte_bounds():
    cache = BoundedPcmCache(max_entries=2, max_bytes=1024)
    a, b, c = b"x" * 500, b"y" * 500, b"z" * 500
    cache.put("a", a)
    cache.put("b", b)
    assert cache.get("a") == a          # touch a
    cache.put("c", c)                   # evicts b (LRU)
    assert cache.get("a") == a
    assert cache.get("b") is None
    assert cache.get("c") == c
    assert cache.stats()["entries"] == 2


def test_cache_byte_bound_eviction():
    cache = BoundedPcmCache(max_entries=100, max_bytes=1000)
    cache.put("big1", b"x" * 600)
    cache.put("big2", b"y" * 600)  # forces big1 out
    assert cache.get("big2") is not None
    assert cache.get("big1") is None


def test_cache_age_expiry():
    cache = BoundedPcmCache(max_age_seconds=0.1)
    cache.put("k", b"v" * 10)
    assert cache.get("k") == b"v" * 10
    time.sleep(0.15)
    assert cache.get("k") is None


def test_cache_stats_and_clear():
    cache = BoundedPcmCache()
    cache.put("k", b"v")
    cache.get("k")
    stats = cache.stats()
    assert stats["hits"] == 1 and stats["entries"] == 1
    cache.clear()
    assert cache.stats()["entries"] == 0
