"""
Bounded synthesis cache.

Repeated questions ("Repeat"), reconnect replays and voice previews re-synthesize
the same text over and over; caching makes those nearly free.

Privacy (master prompt §85/§86/§87):
  * Keys are SHA-256 over provider|model|voice|rate|options|normalized text —
    the plaintext never appears in keys, logs, or filenames.
  * The cache is bounded in entries, bytes and age. It is in-memory only
    (session scope) by default — persistent disk caching is a deliberate
    future opt-in, not the default.
  * The cache NEVER changes behavior: a miss is transparently re-synthesized.
"""

from __future__ import annotations

import hashlib
import json
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Dict, Optional, Tuple


def _normalize_text(text: str) -> str:
    # Collapse whitespace so cosmetic differences don't bust the cache.
    return " ".join(text.split())


def synthesis_cache_key(provider: str, model: Optional[str], voice_id: Optional[str],
                        rate: float, options: Optional[Dict], text: str) -> str:
    """Deterministic, content-hashed cache key. No plaintext content in the key."""
    options_json = ""
    if options:
        options_json = json.dumps(options, sort_keys=True, ensure_ascii=True)
    parts = "|".join([
        provider,
        str(model or ""),
        str(voice_id or ""),
        f"{float(rate):.3f}",
        options_json,
        _normalize_text(text),
    ])
    return hashlib.sha256(parts.encode("utf-8")).hexdigest()


@dataclass
class _Entry:
    pcm: bytes
    created_at: float
    hits: int = 0


class BoundedPcmCache:
    """LRU cache of synthesized PCM payloads with entry/byte/age bounds."""

    def __init__(self, max_entries: int = 16, max_bytes: int = 128 * 1024 * 1024,
                 max_age_seconds: float = 30 * 60):
        self.max_entries = max(1, max_entries)
        self.max_bytes = max(1024, max_bytes)
        self.max_age = max(0.05, max_age_seconds)
        self._lock = threading.Lock()
        self._entries: "OrderedDict[str, _Entry]" = OrderedDict()
        self._size = 0
        self.hits = 0
        self.misses = 0
        self.evictions = 0

    def get(self, key: str) -> Optional[bytes]:
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                self.misses += 1
                return None
            if time.time() - entry.created_at > self.max_age:
                self._remove(key)
                self.misses += 1
                return None
            entry.hits += 1
            self._entries.move_to_end(key)
            self.hits += 1
            return entry.pcm

    def put(self, key: str, pcm: bytes) -> None:
        if not pcm:
            return
        with self._lock:
            existing = self._entries.get(key)
            if existing is not None:
                self._size -= len(existing.pcm)
                del self._entries[key]
            self._entries[key] = _Entry(pcm=pcm, created_at=time.time())
            self._size += len(pcm)
            # Enforce bounds: drop oldest (least recently used) first.
            while len(self._entries) > self.max_entries or self._size > self.max_bytes:
                oldest_key, oldest = next(iter(self._entries.items()))
                self._remove(oldest_key)
            # Drop expired entries opportunistically.
            now = time.time()
            expired = [k for k, e in self._entries.items() if now - e.created_at > self.max_age]
            for k in expired:
                self._remove(k)

    def _remove(self, key: str) -> None:
        entry = self._entries.pop(key, None)
        if entry is not None:
            self._size -= len(entry.pcm)
            self.evictions += 1

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()
            self._size = 0

    def stats(self) -> Dict:
        with self._lock:
            return {
                "entries": len(self._entries),
                "bytes": self._size,
                "hits": self.hits,
                "misses": self.misses,
                "evictions": self.evictions,
            }
