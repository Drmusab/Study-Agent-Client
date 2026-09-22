"""
Short-lived, single-use audio stream registry + bounded synthesis cache.

Stream lifecycle (master prompt §42/§51):
    synthesize request  →  [thread] provider synthesis  →  stream record (bytes ready)
    control reply       →  {stream_id, stream_path, format, sample_rate, expires_in}
    Android GET         →  200 PCM  (first GET consumes the stream)
    second GET / expiry →  410      (streams are NOT indefinitely reusable)
    tts_cancel          →  upstream provider cancelled (best effort)

Every record carries its speech_request identity so a late callback for one
request can never be served to another.
"""

from __future__ import annotations

import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Callable, Dict, Optional

from .audio import pcm_duration_ms
from .cache import BoundedPcmCache, synthesis_cache_key
from .models import (
    CANONICAL_CHANNELS,
    CANONICAL_FORMAT,
    CANONICAL_SAMPLE_RATE,
    TtsErrorCode,
    TtsSynthesisError,
)
from .provider import TtsProvider


@dataclass
class StreamRecord:
    stream_id: str
    speech_request_id: str
    provider: str
    format: str = CANONICAL_FORMAT
    sample_rate: int = CANONICAL_SAMPLE_RATE
    channels: int = CANONICAL_CHANNELS
    pcm: bytes = b""
    cache_hit: bool = False
    estimated_duration_ms: int = 0
    created_at: float = field(default_factory=time.time)
    expires_at: float = 0.0
    consumed: bool = False
    cancelled: threading.Event = field(default_factory=threading.Event)
    synthesis_error: Optional[str] = None  # typed code, set on eager failure
    synthesis_retryable: bool = False
    synthesis_message: Optional[str] = None

    @property
    def expired(self) -> bool:
        return time.time() > self.expires_at


class StreamManager:
    """Bounded registry of live streams + the session synthesis cache."""

    MAX_LIVE_STREAMS = 32
    DEFAULT_TTL_SECONDS = 60.0

    def __init__(self, providers: Dict[str, TtsProvider],
                 cache: Optional[BoundedPcmCache] = None):
        self.providers = providers
        self.cache = cache or BoundedPcmCache()
        self._lock = threading.Lock()
        self._streams: Dict[str, StreamRecord] = {}
        self.synthesis_in_flight = 0

    # ------------------------------------------------------------ creation
    def create_stream(self, request, ttl_seconds: float = DEFAULT_TTL_SECONDS,
                      on_failure: Optional[Callable[[TtsSynthesisError], None]] = None) -> StreamRecord:
        """Eagerly synthesize (bounded worker) and register the stream.

        Raises TtsSynthesisError with a typed code when synthesis fails
        *before* audio exists — the control plane then replies with a typed
        error and Android never opens a doomed HTTP stream.
        """
        provider = self.providers.get(request.provider)
        if provider is None:
            raise TtsSynthesisError(
                TtsErrorCode.PROVIDER_UNAVAILABLE,
                f"Unknown TTS provider '{request.provider}'.", retryable=False)

        record = StreamRecord(
            stream_id="st_" + uuid.uuid4().hex[:12],
            speech_request_id=request.request_id,
            provider=request.provider,
            expires_at=time.time() + ttl_seconds,
        )

        with self._lock:
            self._evict_if_needed()
            self._streams[record.stream_id] = record
            self.synthesis_in_flight += 1

        cache_key = synthesis_cache_key(
            request.provider, request.model, request.voice_id,
            request.rate, request.provider_options, request.text)

        def _run() -> None:
            try:
                cached = self.cache.get(cache_key)
                if cached is not None and not record.cancelled.is_set():
                    record.pcm = cached
                    record.cache_hit = True
                    record.estimated_duration_ms = pcm_duration_ms(cached)
                    return
                chunks = []
                total = 0
                for chunk in provider.synthesize(request):
                    if record.cancelled.is_set():
                        record.synthesis_error = TtsErrorCode.STREAM_FAILED
                        return
                    chunks.append(chunk)
                    total += len(chunk)
                    # Bound memory: stop accumulating well past sane limits.
                    if total > 32 * 1024 * 1024:
                        record.synthesis_error = TtsErrorCode.STREAM_FAILED
                        return
                if record.cancelled.is_set():
                    record.synthesis_error = TtsErrorCode.STREAM_FAILED
                    return
                pcm = b"".join(chunks)
                record.pcm = pcm
                record.estimated_duration_ms = pcm_duration_ms(pcm)
                self.cache.put(cache_key, pcm)
            except TtsSynthesisError as e:
                record.synthesis_error = e.code
                record.synthesis_retryable = e.retryable
                record.synthesis_message = e.message
                if on_failure is not None:
                    try:
                        on_failure(e)
                    except Exception:
                        pass
            finally:
                with self._lock:
                    self.synthesis_in_flight -= 1

        worker = threading.Thread(target=_run, daemon=True,
                                  name=f"tts-synth-{record.stream_id}")
        worker.start()
        return record

    def wait_ready(self, record: StreamRecord, timeout: float) -> StreamRecord:
        """Block until eager synthesis finished (success, error or cancel).

        Used by the control-plane handler (off the event loop) before replying,
        so failures surface as typed WS errors rather than doomed HTTP streams.
        The worker thread always settles the record into exactly one of:
        pcm set / synthesis_error set / cancelled, so this is guaranteed to
        terminate well before [timeout].
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            if record.pcm or record.synthesis_error or record.cancelled.is_set():
                return record
            time.sleep(0.01)
        return record

    # ------------------------------------------------------------ serving
    def get(self, stream_id: str) -> StreamRecord:
        with self._lock:
            return self._streams.get(stream_id)

    def take(self, stream_id: str) -> StreamRecord:
        """Single-use consumption. Raises TtsSynthesisError with the stable code."""
        with self._lock:
            record = self._streams.get(stream_id)
            if record is None:
                raise TtsSynthesisError(TtsErrorCode.STREAM_NOT_FOUND,
                                        f"Stream '{stream_id}' unknown.")
            if record.cancelled.is_set():
                raise TtsSynthesisError(TtsErrorCode.STREAM_FAILED,
                                        "Stream was cancelled.")
            if record.expired:
                self._streams.pop(stream_id, None)
                raise TtsSynthesisError(TtsErrorCode.STREAM_EXPIRED,
                                        "Stream expired; request a new synthesis.")
            if record.synthesis_error:
                self._streams.pop(stream_id, None)
                raise TtsSynthesisError(
                    getattr(TtsErrorCode, record.synthesis_error,
                            TtsErrorCode.SYNTHESIS_FAILED),
                    "Stream failed during synthesis.")
            if record.consumed:
                raise TtsSynthesisError(TtsErrorCode.STREAM_EXPIRED,
                                        "Stream already consumed (single-use).")
            record.consumed = True
            return record

    def cancel(self, stream_id: str) -> bool:
        with self._lock:
            record = self._streams.get(stream_id)
            provider = self.providers.get(record.provider) if record else None
        if record is None:
            return False
        record.cancelled.set()
        if provider is not None:
            try:
                provider.cancel(stream_id)
            except Exception:
                pass  # best effort only — never block Android responsiveness
        return True

    def cancel_for_request(self, speech_request_id: str) -> int:
        count = 0
        with self._lock:
            targets = [s.stream_id for s in self._streams.values()
                       if s.speech_request_id == speech_request_id]
        for sid in targets:
            if self.cancel(sid):
                count += 1
        return count

    # ------------------------------------------------------------ housekeeping
    def _evict_if_needed(self) -> None:
        # Caller holds the lock.
        live = [s for s in self._streams.values() if not s.expired and not s.cancelled]
        if len(self._streams) < self.MAX_LIVE_STREAMS:
            return
        # Drop expired first, then oldest.
        ordered = sorted(self._streams.values(),
                         key=lambda s: (not s.expired, s.created_at))
        for rec in ordered:
            if len(self._streams) <= self.MAX_LIVE_STREAMS - 1:
                break
            self._streams.pop(rec.stream_id, None)

    def cleanup(self) -> int:
        now = time.time()
        with self._lock:
            dead = [s.stream_id for s in self._streams.values()
                    if s.expired or (s.cancelled.is_set() and s.consumed)]
            for sid in dead:
                self._streams.pop(sid, None)
        return len(dead)

    def stats(self) -> Dict:
        with self._lock:
            live = sum(1 for s in self._streams.values()
                       if not s.expired and not s.cancelled.is_set())
        return {
            "live_streams": live,
            "in_flight": self.synthesis_in_flight,
            **self.cache.stats(),
        }
