# Architecture Decision Records

ADRs for the Anki fusion architecture. Each ADR is immutable once Accepted;
superseding decisions are recorded as new ADRs that reference the old one.

The normative contract these ADRs support is
[`docs/ANKI_INTEGRATION_ARCHITECTURE.md`](../ANKI_INTEGRATION_ARCHITECTURE.md)
(GATE 01). Invariant IDs (INV-ANKI-nn) are defined there.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-anki-backend-abstraction.md) | Single Anki gateway abstraction; backend-specific types confined behind it | Accepted |
| [0002](0002-single-writable-backend-per-session.md) | One writable Anki backend per review session; resolved once; no silent mid-session switch | Accepted |
| [0003](0003-anki-owns-scheduling.md) | Anki backends own scheduling, FSRS, due state and review history | Accepted |
| [0004](0004-study-agent-owns-review-interaction.md) | Study-Agent owns the review interaction layer; the study machine stays UI-flow authority | Accepted |
| [0005](0005-no-ankidroid-fork.md) | Integrate via the public AnkiDroid integration API; no source fork | Accepted |
| [0006](0006-backend-provider-independence.md) | Anki backend selection is independent of AI/TTS/STT provider selection | Accepted |
| [0007](0007-rating-authority-and-exactly-once.md) | Suggested ≠ selected ≠ committed rating; exactly-once scheduling mutation per review turn | Accepted |
