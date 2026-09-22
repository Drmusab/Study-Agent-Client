# ADR 0005 — Integrate with AnkiDroid via its public API; no source fork

**Status:** Accepted (GATE 01, 2026-09-22)
**Invariants:** INV-ANKI-06 (boundary), supports INV-ANKI-04

## Context

`https://github.com/ankidroid/Anki-Android` is a large, actively maintained,
GPL-licensed application with its own database schema, scheduler binding and
internal APIs. Study-Agent needs its collections, scheduling and rendering —
the question is *how*.

Options: (a) depend on the public AnkiDroid integration API (the
ContentProvider/Intent contract other apps use), (b) copy or fork AnkiDroid
sources into this repository, (c) bypass the app and open its private SQLite
database directly.

## Decision

1. Study-Agent integrates with AnkiDroid as a **separate app through the
   supported public integration API** — permissions, installation detection,
   provider queries, API intents.
2. No AnkiDroid source is copied or forked into this repository.
3. Forbidden shortcuts: reading AnkiDroid's SQLite directly, reading
   `/data/data/com.ichi2.anki`, copying `collection.anki2`, or touching its
   private media directories outside the supported contract.
4. All AnkiDroid-facing code is confined to `data/anki/ankidroid/` (ADR 0001).
5. This ADR makes no legal conclusions. A licensing review (AnkiDroid's GPL
   terms vs Study-Agent's distribution model) is recorded as a **release
   requirement** in the architecture contract §14 before the integration ships.

## Consequences

- Scheduler/history ownership stays where it belongs (ADR 0003); AnkiDroid
  updates arrive through the user's installed app, not through this repo.
- We inherit the public API's capability surface — anything it cannot do
  (e.g. some rendering/edge features) becomes a capability flag with a
  documented fallback or delegation, not a reason to fork.
- The integration permission flow and installation detection become real UX
  states (`AnkiAvailability`); GATE 02 owns them.

## Alternatives considered

- *Fork AnkiDroid*: rejected — permanent maintenance burden, license
  contamination risk, and it duplicates an Anki scheduler (violates ADR 0003).
- *Direct SQLite access*: rejected — unsupported, breaks on schema changes,
  violates user trust and platform security rules.
