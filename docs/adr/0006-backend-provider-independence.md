# ADR 0006 — Anki backend selection is independent of AI/TTS/STT providers

**Status:** Accepted (GATE 01, 2026-09-22)
**Invariants:** INV-ANKI-10

## Context

Before this architecture, everything flowed through the PC connection: cards,
evaluation, and (in-progress) cloud TTS. If "mode" stays connection-shaped —
connected = PC does everything; disconnected = nothing works — then two states
that users legitimately want are impossible:

- **Offline review:** AnkiDroid local cards + Android TTS/STT + manual rating,
  no PC at all.
- **Hybrid review:** AnkiDroid owns cards and scheduling while the PC agent
  supplies LLM evaluation and cloud voices.

Also, letting a TTS or network failure touch Anki state (pause a commit, change
which card is due) would violate ownership: voice providers and network
connectivity do not own study or Anki state.

## Decision

1. There is deliberately NO single "mode" enum bundling providers. Four
   independent selections exist: Anki backend, AI provider, TTS provider, STT
   provider.
2. `ConnectionState.Ready` is not a prerequisite for all study activity; it is
   a prerequisite only for capabilities the PC actually supplies for the
   current configuration. Today's start-gate coupling
   (`HomeViewModel.startStudy`, `StudySessionMachine.observeConnection`
   treating any connection loss as session loss) is an acknowledged hotspot
   (H1/H4) to be scoped per backend in GATE 06.
3. AI evaluation is optional in local review: policy `MANUAL` /
   `AI_ASSISTED` (default) / `AI_REQUIRED`. AI loss ⇒ continue manual or pause,
   per setting.
4. TTS/STT failures degrade to visual/manual study; they never alter due state
   or commit outcomes.
5. Provider health is reported per provider, in unified availability terms.

## Consequences

- The offline story is available the day the AnkiDroid backend lands — no
  redesign needed.
- Configuration UI must expose four independent choices with clear effective
  resolution (work for a later gate; no bundling shortcut allowed).
- Diagnostics must attribute failures to their provider, which the unified
  availability/error model already supports.

## Alternatives considered

- *One compound "Study Mode" enum (PC / Local / Hybrid)*: rejected — every new
  provider doubles the enum, hides which capability failed, and re-couples Anki
  to the network.
