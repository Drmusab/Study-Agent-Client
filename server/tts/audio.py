"""
Pure-stdlib PCM helpers: canonical normalization and lightweight resampling.

All decoding / resampling happens on the PC Agent (never on Android). The
resampler is a simple linear interpolator — adequate for the 48k -> 24k (and
other integer/half-rate) cases that arise from provider defaults, and cheap
enough to run per chunk.
"""

from __future__ import annotations

import array
from typing import List, Optional

from .models import CANONICAL_CHANNELS, CANONICAL_SAMPLE_RATE, BYTES_PER_SAMPLE


def pcm_duration_ms(pcm: bytes, sample_rate: int = CANONICAL_SAMPLE_RATE,
                    channels: int = CANONICAL_CHANNELS,
                    bytes_per_sample: int = BYTES_PER_SAMPLE) -> int:
    """Wall duration of a PCM payload in whole milliseconds."""
    frame_bytes = bytes_per_sample * channels
    if frame_bytes <= 0 or sample_rate <= 0:
        return 0
    frames = len(pcm) // frame_bytes
    return (frames * 1000) // sample_rate


def resample_linear(pcm: bytes, src_rate: int, dst_rate: int,
                    channels: int = CANONICAL_CHANNELS) -> bytes:
    """Resample s16le mono/multi-channel PCM from src_rate to dst_rate.

    Linear interpolation per channel. Identity (byte-copied) when rates match.
    Raises ValueError on invalid input rather than emitting silent audio.
    """
    if src_rate <= 0 or dst_rate <= 0:
        raise ValueError("invalid sample rate")
    if src_rate == dst_rate:
        return bytes(pcm)
    if channels <= 0:
        raise ValueError("invalid channel count")

    n = len(pcm) // (BYTES_PER_SAMPLE * channels)
    if n == 0:
        return b""

    samples = array.array("h")
    samples.frombytes(pcm[: n * BYTES_PER_SAMPLE * channels])
    total = len(samples)
    if total % channels != 0:
        total -= total % channels
    samples = samples[:total]

    out_len = int(total * (dst_rate / src_rate))
    step = src_rate / dst_rate
    out = array.array("h")
    for i in range(out_len):
        pos = i * step
        idx = int(pos)
        if idx >= total - 1:
            idx = total - 2
        frac = pos - idx
        for ch in range(channels):
            a = samples[idx * channels + ch]
            b = samples[(idx + 1) * channels + ch]
            out.append(int(a + (b - a) * frac))
    return out.tobytes()


def normalize_to_canonical(pcm: bytes, src_rate: int,
                           src_channels: int = 1) -> bytes:
    """Provider output -> canonical PCM (s16le, mono, 24 kHz).

    Handles the two things that actually vary between providers:
      * sample rate (resample linearly),
      * channel count (mix down to mono by averaging).
    """
    data = pcm
    if src_channels > CANONICAL_CHANNELS:
        n = len(data) // (BYTES_PER_SAMPLE * src_channels)
        frames = array.array("h")
        frames.frombytes(data[: n * BYTES_PER_SAMPLE * src_channels])
        mono = array.array("h")
        for i in range(n):
            acc = 0
            for ch in range(src_channels):
                acc += frames[i * src_channels + ch]
            mono.append(acc // src_channels)
        data = mono.tobytes()
    if src_rate != CANONICAL_SAMPLE_RATE:
        data = resample_linear(data, src_rate, CANONICAL_SAMPLE_RATE,
                               CANONICAL_CHANNELS)
    return data


def chunk_bytes(data: bytes, size: int = 32 * 1024) -> List[bytes]:
    """Split a complete payload into streaming chunks (bounded allocation)."""
    if size <= 0:
        size = 32 * 1024
    return [data[i:i + size] for i in range(0, len(data), size)]


def silence_bytes(duration_ms: int,
                  sample_rate: int = CANONICAL_SAMPLE_RATE) -> bytes:
    frames = (duration_ms * sample_rate) // 1000
    return b"\x00\x00" * frames
