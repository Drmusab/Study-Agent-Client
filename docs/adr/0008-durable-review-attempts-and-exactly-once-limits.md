# ADR 0008 — Durable review attempts and exactly-once limits

**Status:** Accepted (GATE 11 correction, 2026-09-24)
**Supersedes:** ADR 0007's unqualified exactly-once scheduling and card-state reconciliation claims. Its rating-authority and stable-turn-identity decisions still apply.

## Decision

`ReviewCommitId = (backendId, studySessionId, ReviewTurnId)` is the logical transaction key; a WebSocket `message_id` is a transport delivery key. A card ID, a new card, an incremented review counter, an interval, or a timestamp is **not** evidence that a particular logical commit applied. Neither a reducer guard nor a client-side mutex supplies server-side idempotency.

The Anki-local path persists intent and attempt phase separately. An attempt is `PREPARED` until a durable `MUTATION_CALL_ENTERED` marker is written **before the real scheduler API call** (after backend preflight where supported). This marker means *may have happened*, not *did happen*. Only a definitively received response can be durably recorded as `MUTATION_RESPONSE_RECEIVED`; final `COMMITTED`/`FAILED`/`AMBIGUOUS` is `LOCAL_RESULT_PERSISTED`. `COMMITTED` must be durable before requesting another card. If a write fails, the session enters a separate persistence-fault state, stops, and never fabricates success or retries a potentially dispatched rating.

On restart: `PREPARED` can become a safe pre-call failure; `MUTATION_CALL_ENTERED` with no response becomes `AMBIGUOUS`; a durably received response is finalized without redispatch. The only way out of an ambiguous transaction is an *authoritative, commit-correlated* backend result. Reconciliation reads only. An unsupported/unavailable/inconclusive result retains the ambiguity. The affected collection is blocked at startup before scheduler reads, including after ending the UI session. A separate backend/known unrelated collection can remain available.

## Backend guarantees and limitations

| Backend | Local durable intent / serialization | Backend-owned durable logical-ID dedup | Authoritative lost-response reconciliation | Guarantee claimed here |
|---|---|---|---|---|
| AnkiDroid public provider | Yes (Study-Agent DataStore + serialized rating gateway) | No public `ReviewCommitId` API | No public commit-correlated receipt/query | `AT_MOST_ONCE_FAIL_CLOSED`; an ambiguous result can remain stuck |
| PC Agent mock / existing WebSocket path | Client turn guard and server's *bounded in-memory* `message_id` cache; rating timeout now blocks replay and next-card progression | **Not shown**: no durable logical commit table keyed by `ReviewCommitId` | **Not shown**: session snapshots/revisions are not commit receipts | No end-to-end exactly-once claim; `message_id` replay alone is insufficient |
| Configurable JVM fake | Models both unsafe and idempotent backends; a shareable fake effect store survives recreated fake adapters | In the idempotent fake mode only | In the authoritative fake mode only | A *simulation*, not evidence that either real backend supports those features |

AnkiDroid's synchronous `update(schedule)` returning `1` plus a matching immediate card observation is a **limited direct-response classification**, not a durable backend transaction receipt. The pinned provider can swallow a scheduler exception and still report a row; `-1`, exceptions, timeouts and inconclusive immediate observations are ambiguous. The direct-response path and its concurrency assumptions require **real AnkiDroid on a disposable collection** before calling it empirically verified. No AnkiDroid lost-response case is proved applied/not applied by reading card state.

The PC integration guide describes what a *future* PC server must implement to claim idempotent replay: persist `(backend/collection, ReviewCommitId, immutable card+rating payload, outcome and receipt)` atomically with the scheduler effect, reject conflicting payloads, retain the table across restarts/evictions for the documented window, and serve read-only status by that same logical ID. This repository's mock and available client protocol do not establish that contract.

## Explicit non-claims

No cross-process backend-side exactly-once for AnkiDroid; no progress guarantee after ambiguity; no durable PC logical-commit dedup, PC receipt or snapshot-based proof; no assumption that provider row counts, card-state deltas, time windows, next-card changes, local debounce, or abandonment prove a scheduler mutation. Retrying after a confirmed pre-call failure is a new *attempt* under the same logical ID, not a second logical commit.
