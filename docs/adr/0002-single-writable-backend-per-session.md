# ADR 0002 — Single writable Anki backend per review session

**Status:** Accepted (GATE 01, 2026-09-22)
**Invariants:** INV-ANKI-01, INV-ANKI-07

## Context

With two Anki backends, the question of *which one receives rating commits* is
load-bearing. If a session could silently migrate — card loaded from AnkiDroid,
then AnkiDroid disappears, PC agent appears, rating lands in Desktop Anki — the
result is split-brain review history: card identities may differ, collection
and scheduler state may differ, and the same answer is recorded in two
collections or against the wrong scheduler state.

## Decision

1. One active review session has exactly one writable Anki backend.
2. The effective backend is resolved **once at session start** by
   `AnkiBackendSelector` (pure, tested; AUTO = AnkiDroid-local first when
   review-ready, else PC agent path; explicit modes fail closed) and locked
   into `AnkiSessionContext` for the session's lifetime.
3. Mid-session backend loss triggers a controlled
   `AnkiUnavailable / Recovering / Paused` transition against the *same*
   context — never substitution.
4. Re-selection happens only between sessions (finish or explicit restart).
5. The user's preference (`AnkiBackendMode`) is never overwritten by runtime
   resolution; preference and effective backend are separate facts.
6. An *explicit* preference failing (e.g. AnkiDroid chosen but not ready) fails
   closed with a typed reason instead of silently serving the other backend.

## Consequences

- A user whose AnkiDroid dies mid-review keeps their rating intent safely
  parked instead of duplicated elsewhere; review history stays consistent.
- Automatic "seamless failover" UX is explicitly forfeited inside sessions;
  between sessions, fallback is free and safe.
- Every rating commit is attributable to exactly one backend/collection,
  enabling reliable reconciliation and diagnostics.

## Alternatives considered

- *Hot failover on availability*: rejected — see the canonical unsafe scenario
  in the architecture contract §6.4.
- *Route each commit to whichever backend is healthy*: rejected — review
  history splits across collections silently.
