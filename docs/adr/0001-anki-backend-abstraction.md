# ADR 0001 — Anki backend abstraction with a strict gateway boundary

**Status:** Accepted (GATE 01, 2026-09-22)
**Invariants:** INV-ANKI-06

## Context

Study-Agent must talk to two very different Anki providers: AnkiDroid on the
same phone (ContentProvider / integration API, `Cursor`, permissions) and
Desktop Anki through the PC Study Agent (WebSocket protocol messages,
an already-existing integration). Today's code speaks *only* protocol messages;
`ServerMessage.Question` is treated as "the card" everywhere.

If either provider's native types leak into study code, every feature becomes
backend-specific, backends cannot be added safely, and cards/ratings from
different Anki installations become ambiguous.

## Decision

Introduce one gateway interface — `AnkiBackend` (`core/anki/AnkiBackend.kt`) —
with backend-neutral domain types (`AnkiCardRef`, `AnkiRenderedCard`,
`AnkiDeckRef`, `AnkiSessionContext`, `AnkiAvailability`, `AnkiCapabilities`,
`AnkiError`, commit outcome types).

- AnkiDroid types (`FlashCardsContract`, `Cursor`, `ContentResolver`,
  `AddContentApi`) are confined to `data/anki/ankidroid/`.
- PC protocol types (`ProtocolMessage`, `ServerMessage`, sockets) are confined
  to `data/anki/remote/`; `PcAnkiBackend` translates them into domain models.
- Every reference is backend-qualified (`backendId` is part of the value), so
  cross-backend identity confusion is structurally impossible.
- The interface is deliberately minimal (review, decks, card actions). Growth
  paths are documented (decomposition into `AnkiReviewBackend` /
  `AnkiDeckSource` / `AnkiNoteEditor` / `AnkiSearchSource`), not built
  speculatively; no god interface, no repository explosion on top of the
  gateway, no universal learning-content plugin SDK.

## Consequences

- `StudySessionMachine` and UI compile against `core/anki` only; adding a third
  Anki backend does not touch the machine.
- Two translations must be maintained (AnkiDroid, PC). Accepted: translation
  code is mechanical and lives at the boundary it belongs to.
- Contract tests run the same behavioral suite against every backend, keeping
  the abstraction honest.

## Alternatives considered

- *Speak protocol everywhere*: rejected — binds the app permanently to the PC
  agent and makes AnkiDroid integration a parallel, competing system.
- *Per-feature direct integrations* (UI talks to ContentResolver for decks,
  protocol for study): rejected — violates the dependency direction and makes
  ownership untraceable.
