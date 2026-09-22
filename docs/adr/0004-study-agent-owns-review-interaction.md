# ADR 0004 — Study-Agent owns the review interaction layer

**Status:** Accepted (GATE 01, 2026-09-22)
**Invariants:** INV-ANKI-03 (with ADR 0007)

## Context

Between "Anki serves a due card" and "Anki receives a rating", a lot happens in
Study-Agent: spoken question, listening, AI evaluation, hints, explanations,
feedback, pauses, route changes, retries. The existing `StudySessionMachine`
(serialized event loop, reducer + effects) already owns this interaction flow
for the PC protocol path.

Two temptations exist: push interaction flow into the backend integration
(let the gateway drive the UI), or embed AnkiDroid's reviewer activity as the
study screen. Both would fork the interaction logic Study-Agent exists to
provide, and both make the app's UX dependent on which backend is active.

## Decision

1. Study-Agent owns the review-turn lifecycle: speak question → listen →
   evaluate → explain → ask rating → commit. Anki owns which card is due and
   what a rating means; nothing in between.
2. `StudySessionMachine` remains the sole authority of user-flow state.
   Backends are effect dependencies invoked by the machine; backend callbacks
   never mutate session state directly — they complete effects by dispatching
   events, the same discipline every other effect already follows.
3. Anki concerns do not inflame `SessionPhase` into a mega state machine:
   presentation is derived from orthogonal states (session phase × Anki
   availability × session binding × connection × voice).
4. Card identity ≠ review-turn identity. One card's repeated appearances are
   distinct turns; Study-Agent owns their lifecycle.

## Consequences

- Voice-first UX (the product's differentiator) works identically on both
  backends.
- Embedding AnkiDroid's reviewer as the primary study UI is explicitly out of
  scope; "Open in AnkiDroid" remains a delegation escape hatch.
- The machine's existing invariants (ledger, turn identity, stale-callback
  discipline) extend to Anki effects instead of being duplicated.

## Alternatives considered

- *AnkiDroid reviewer embedded per card*: rejected — the app becomes a launcher,
  voice interaction is lost, UX differs per backend.
- *A new combined "study-plus-anki" state machine*: rejected — combinatorial
  state explosion, untestable; bounded machines + events instead.
