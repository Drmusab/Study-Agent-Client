#!/usr/bin/env python3
"""
GATE 11 — PC-side review-commit idempotency contract (reference implementation).

This module is what a PC Study Agent needs so that a repeated ``rate_card`` carrying the same
``review_commit_id`` never produces a second scheduler effect in Desktop Anki.

It deliberately separates *delivery* deduplication (the WebSocket ``message_id`` cache, process
memory only) from *effect* deduplication (this table, keyed by the logical ``review_commit_id``,
durable on disk). They are different guarantees: a reconnect sends a new ``message_id`` for the same
logical rating, so only the second one can stop a duplicate scheduler mutation.

Wire contract (§396/§397)
-------------------------
* same ``review_commit_id`` + same payload, already applied   → the stored result, no new effect
* same ``review_commit_id`` + different payload               → ``COMMIT_CONFLICT``, no effect
* same ``review_commit_id`` whose intent survived a crash
  without a recorded result                                   → ``COMMIT_OUTCOME_UNKNOWN``, no effect
* same ``review_commit_id`` previously *proven* not applied   → may be applied now (safe retry)

Persistence order (§398) and crash windows
------------------------------------------
::

    1. validate request                              (nothing durable, nothing applied)
    2. persist INTENT {id, payload}                  ← durable before Anki is touched
    3. apply_fn(request)  → Desktop Anki mutation    ← the irreversible boundary
    4. persist APPLIED {result}                      ← durable before the reply is sent
    5. reply rating_saved

    crash before 2      → no record, nothing applied        → replay applies once (safe)
    crash between 2..3  → INTENT, Anki NOT applied           → replay: OUTCOME_UNKNOWN (conservative)
    crash inside 3 / between 3..4
                        → INTENT, Anki MAY be applied        → replay: OUTCOME_UNKNOWN (never re-apply)
    crash after 4       → APPLIED                            → replay: stored result, no effect

The store cannot distinguish "between 2 and 3" from "inside 3": both leave INTENT. It therefore
never re-applies an INTENT record. That is the dual-write limit between this table and Anki's own
collection (§399): without a transaction spanning both, the server is *idempotent for replay* but
cannot promise *eventual* application across every crash window. It is not end-to-end exactly-once.

Store loss (§436): if the table file is deleted or rolled back, the server no longer knows the id
and a replay WILL apply again. Durable idempotency is exactly as strong as this file's durability.

Security (§464-§466): the table stores identifiers, the rating and a result token only — never
auth tokens, transcripts, card HTML or answer text. A ``review_commit_id`` is correlation, not
authorization: callers must authenticate the connection before calling :meth:`commit`.
"""

from __future__ import annotations

import json
import os
import tempfile
import threading
from dataclasses import dataclass, field
from typing import Callable, Dict, Optional

SCHEMA_VERSION = 1

STATE_INTENT = "INTENT"            # persisted before the Anki mutation; outcome unknown if left behind
STATE_APPLIED = "APPLIED"          # Anki confirmed the mutation; result recorded
STATE_NOT_APPLIED = "NOT_APPLIED"  # proven not applied (rejected before the mutation boundary)

OUTCOME_APPLIED = "APPLIED"
OUTCOME_DUPLICATE = "DUPLICATE"            # stored result returned, no new effect
OUTCOME_CONFLICT = "COMMIT_CONFLICT"
OUTCOME_UNKNOWN = "COMMIT_OUTCOME_UNKNOWN"  # never auto-replayed
OUTCOME_REJECTED = "REJECTED"              # proven not applied; safe to retry the same id
OUTCOME_STORE_FAILED = "COMMIT_STORE_FAILED"  # intent not durable → Anki untouched

PAYLOAD_FIELDS = ("session_id", "review_turn_id", "card_id", "rating")
VALID_RATINGS = ("again", "hard", "good", "easy")


class NotAppliedError(Exception):
    """Raised by ``apply_fn`` only when it can PROVE Anki was not mutated (pre-boundary refusal)."""

    def __init__(self, category: str):
        super().__init__(category)
        self.category = category


@dataclass
class CommitResult:
    outcome: str
    review_commit_id: str
    result: Optional[dict] = None
    category: Optional[str] = None
    effect_applied_now: bool = False

    @property
    def is_success(self) -> bool:
        return self.outcome in (OUTCOME_APPLIED, OUTCOME_DUPLICATE)


class StoreWriteError(Exception):
    pass


class ReviewCommitTable:
    """One JSON file, replaced atomically (write temp → fsync → rename → fsync dir)."""

    def __init__(self, path: Optional[str]):
        self.path = path
        self._records: Dict[str, dict] = {}
        self._lock = threading.Lock()
        self.fail_next_writes = 0  # test seam: simulate a storage failure
        if path and os.path.exists(path):
            with open(path, "r", encoding="utf-8") as handle:
                envelope = json.load(handle)
            if envelope.get("schema_version") != SCHEMA_VERSION:
                raise ValueError(f"unsupported review commit table schema {envelope.get('schema_version')}")
            self._records = dict(envelope.get("records", {}))

    @property
    def durable(self) -> bool:
        return self.path is not None

    def get(self, commit_id: str) -> Optional[dict]:
        record = self._records.get(commit_id)
        return dict(record) if record is not None else None

    def put(self, commit_id: str, record: dict) -> None:
        """Durable replace. On failure memory is left at the last durable snapshot."""
        if self.fail_next_writes > 0:
            self.fail_next_writes -= 1
            raise StoreWriteError("injected write failure")
        candidate = dict(self._records)
        candidate[commit_id] = record
        if self.path:
            directory = os.path.dirname(os.path.abspath(self.path)) or "."
            fd, tmp = tempfile.mkstemp(prefix=".review_commits.", dir=directory)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    json.dump({"schema_version": SCHEMA_VERSION, "records": candidate}, handle, sort_keys=True)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.replace(tmp, self.path)
                try:
                    dir_fd = os.open(directory, os.O_RDONLY)
                    try:
                        os.fsync(dir_fd)
                    finally:
                        os.close(dir_fd)
                except OSError:
                    pass  # directory fsync is not available on every platform
            except BaseException:
                if os.path.exists(tmp):
                    os.unlink(tmp)
                raise
        self._records = candidate

    def snapshot(self) -> Dict[str, dict]:
        return {k: dict(v) for k, v in self._records.items()}


def payload_of(request: dict) -> dict:
    return {name: request.get(name) for name in PAYLOAD_FIELDS}


def validate(request: dict) -> Optional[str]:
    commit_id = request.get("review_commit_id")
    if not isinstance(commit_id, str) or not commit_id.strip():
        return "missing_review_commit_id"
    for name in ("session_id", "review_turn_id", "card_id"):
        if not isinstance(request.get(name), str) or not request.get(name):
            return f"missing_{name}"
    if request.get("rating") not in VALID_RATINGS:
        return "invalid_rating"
    return None


@dataclass
class ReviewCommitProcessor:
    """Serializes commits (one in flight at a time) and applies the persistence order above."""

    table: ReviewCommitTable
    apply_fn: Callable[[dict], dict]
    trace: Callable[[str], None] = field(default=lambda _line: None)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def commit(self, request: dict) -> CommitResult:
        with self._lock:
            return self._commit_locked(request)

    def _commit_locked(self, request: dict) -> CommitResult:
        problem = validate(request)
        commit_id = str(request.get("review_commit_id") or "")
        if problem:
            return CommitResult(OUTCOME_REJECTED, commit_id, category=problem)
        payload = payload_of(request)
        existing = self.table.get(commit_id)
        if existing is not None:
            if existing["payload"] != payload:
                self.trace(f"commit={_short(commit_id)} conflict")
                return CommitResult(OUTCOME_CONFLICT, commit_id, category="payload_mismatch")
            state = existing["state"]
            if state == STATE_APPLIED:
                self.trace(f"commit={_short(commit_id)} duplicate → stored result")
                return CommitResult(OUTCOME_DUPLICATE, commit_id, result=existing.get("result"))
            if state == STATE_INTENT:
                # Crash window 2..4: Anki may or may not have applied it. Never re-apply.
                self.trace(f"commit={_short(commit_id)} intent without result → unknown")
                return CommitResult(OUTCOME_UNKNOWN, commit_id, category="intent_without_result")
            # STATE_NOT_APPLIED: proven no effect → fall through to a fresh attempt with the same id.

        attempt = (existing or {}).get("attempts", 0) + 1
        try:
            self.table.put(commit_id, {"payload": payload, "state": STATE_INTENT, "attempts": attempt})
        except Exception:
            # Intent not durable → Anki is not touched. Retrying the same id is safe.
            self.trace(f"commit={_short(commit_id)} intent persist failed → not applied")
            return CommitResult(OUTCOME_STORE_FAILED, commit_id, category="intent_not_durable")
        self.trace(f"commit={_short(commit_id)} INTENT persisted attempt={attempt}")

        try:
            result = self.apply_fn(request)  # ← irreversible Desktop Anki mutation boundary
        except NotAppliedError as refused:
            self._record_not_applied(commit_id, payload, attempt, refused.category)
            return CommitResult(OUTCOME_REJECTED, commit_id, category=refused.category)
        except Exception:
            # Unknown after entering the mutation: leave INTENT so a replay answers UNKNOWN.
            self.trace(f"commit={_short(commit_id)} apply raised → unknown")
            return CommitResult(OUTCOME_UNKNOWN, commit_id, category="apply_outcome_unknown")

        try:
            self.table.put(commit_id, {"payload": payload, "state": STATE_APPLIED, "attempts": attempt,
                                       "result": result})
        except Exception:
            # Anki applied it but the result is not durable. The in-memory reply is still true,
            # and INTENT on disk makes any replay answer UNKNOWN rather than apply twice.
            self.trace(f"commit={_short(commit_id)} APPLIED persist failed (effect happened)")
            return CommitResult(OUTCOME_APPLIED, commit_id, result=result, effect_applied_now=True,
                                category="result_not_durable")
        self.trace(f"commit={_short(commit_id)} APPLIED persisted")
        return CommitResult(OUTCOME_APPLIED, commit_id, result=result, effect_applied_now=True)

    def _record_not_applied(self, commit_id: str, payload: dict, attempt: int, category: str) -> None:
        try:
            self.table.put(commit_id, {"payload": payload, "state": STATE_NOT_APPLIED, "attempts": attempt,
                                       "category": category})
        except Exception:
            pass  # INTENT stays on disk → a replay is answered UNKNOWN (conservative)

    def status(self, commit_id: str) -> str:
        """Read-only reconciliation answer: APPLIED | NOT_APPLIED | UNKNOWN | ABSENT."""
        record = self.table.get(commit_id)
        if record is None:
            return "ABSENT"
        return {STATE_APPLIED: "APPLIED", STATE_NOT_APPLIED: "NOT_APPLIED"}.get(record["state"], "UNKNOWN")


def _short(commit_id: str) -> str:
    """Log correlation without dumping the full identifier (§456)."""
    import hashlib
    return hashlib.sha256(commit_id.encode("utf-8")).hexdigest()[:10]
