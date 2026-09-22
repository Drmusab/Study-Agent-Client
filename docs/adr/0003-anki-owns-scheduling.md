# ADR 0003 — Anki owns scheduling, FSRS, due state and review history

**Status:** Accepted (GATE 01, 2026-09-22)
**Invariants:** INV-ANKI-04, INV-ANKI-09, INV-ANKI-12, INV-ANKI-14

## Context

Study-Agent already fakes "Anki sessions" through the mock PC agent, and it
would be locally convenient to track which cards are due, compute next
intervals, keep a local review log for offline use, or interpret cloze syntax
when rendering cards. Each of these quietly creates a second Anki.

A second scheduler disagrees with the real one; a shadow history diverges on
its first conflict; a local due-date database goes stale the moment AnkiDroid
or Desktop Anki changes anything; a hand-rolled cloze parser renders cards
differently than Anki does.

## Decision

1. Scheduling, FSRS, due calculation, next-card selection, review history and
   the collection belong to the selected Anki backend. Study-Agent never
   recomputes them.
2. Study-Agent asks the scheduler for the next card; it does not select it.
3. Caching is allowed for deck lists (short-lived), the active turn's rendered
   card (turn-scoped), counts (refreshable) and media (bounded). Caches are
   never scheduling authority; there is no local due-date database and no
   shadow review history unless a future offline architecture introduces one
   deliberately and documents it here first.
4. Card rendering, cloze rules and template semantics stay Anki-owned;
   Study-Agent consumes normalized output (visual HTML / speech text /
   evaluation text), and missing identifiers are never fabricated client-side.
5. Anki sync, import/export and template editing are delegated to
   AnkiDroid/Desktop Anki.

## Consequences

- Study-Agent stays correct when users also review directly in AnkiDroid or on
  Desktop — there is only ever one scheduler and one history.
- Offline-first is still achievable (AnkiDroid local backend IS Anki — the
  scheduler is on-device); no second engine required.
- Some desired "smart" features (next-interval prediction in UI, offline due
  lists) must display backend-provided data rather than compute their own.

## Alternatives considered

- *Local scheduler replica with Anki as storage*: rejected — that is a second
  Anki, with FSRS drift and duplicate-history bugs as certainties.
- *Cache due lists and serve them offline*: rejected as authority; permitted
  strictly as labeled CACHE with freshness rules.
