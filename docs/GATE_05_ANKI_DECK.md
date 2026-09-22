# GATE 05 — Deck & Library Data Foundation

Date: 2026-09-22
Gate: GATE 05
Status: Implemented
Integration target: https://github.com/ankidroid/Anki-Android (public provider contract, v2.24.1)
Depends on: GATE 03 (domain), GATE 04 (gateway / provider client)

## 1. Mission

Study-Agent can now answer, through a backend-neutral pipeline:

```
which decks exist, what their stable IDs are, how they nest,
which deck AnkiDroid currently has selected (informational),
what new/learning/review counts are if the provider sent them,
and whether that snapshot is fresh, stale, or failed.
```

It still cannot start a review, fetch a card, or write anything to Anki.

## 2. Public API actually used

Read-only `ContentResolver.query` against AnkiDroid's exported provider.
Never SQLite, never `collection.anki2`, never libanki, never `FlashCardsContract` types
(column *names* are pinned as strings in `AnkiDroidApiContract`).

| URI | Path | Projection | Purpose |
|---|---|---|---|
| `content://<authority>/decks` | `decks` | `deck_id`, `deck_name`, `deck_count`, `deck_dyn` | Full deck list |
| `content://<authority>/selected_deck` | `selected_deck` | `deck_id`, `deck_name` | AnkiDroid's current deck |

Not consumed:

- `options` — raw JSON, never exposed upward (§36)
- `deck_desc` — the provider writes `col.decks.current().description` on **every** row, so it is not a per-deck fact

Transport fact (verified against Android `CursorWindow`): `Boolean` and `JSONArray` cells arrive as their `toString()`. Mappers parse text, they never call typed getters for those columns.

Unknown projection columns are skipped by `CardContentProvider.addDeckToCursor` (no `else`), which would shift later cells. GATE 05 therefore requests only recognized names.

## 3. Count semantics (required)

AnkiDroid `FlashCardsContract.Deck.DECK_COUNTS` KDoc: JSON array **`[learn, review, new]`**.

Provider implementation (`getDeckCountsFromDueTreeNode`):

```
put(deck.lrnCount); put(deck.revCount); put(deck.newCount)
```

`DeckNode` documents those fields as **today's due counts as shown in the deck picker, including subdecks** (recursive):

| Domain field | Provider index | Meaning |
|---|---|---|
| `learning` | `[0]` | Learning cards due today, **including descendants** |
| `review` | `[1]` | Review cards due today, **including descendants** |
| `new` | `[2]` | New cards due today, **including descendants** |
| `totalDue` | — | **Not reported. Left `null`. Never summed.** |

`selected_deck` counts are `JSONArray(listOf(col.sched.counts()))` — a nested array, **not** `[learn, review, new]`. GATE 05 never reads counts from that row.

**Do not aggregate parent + child.** Parent counts already include children. `AnkiCapabilities.deckCounts` stays `false` until a real-device comparison against AnkiDroid's deck picker has been recorded. Mapped counts are therefore advisory and nullable.

`0` means the provider sent zero. `null` means the column was absent, unparseable, or not verified. Missing is never coerced to zero (INV-ANKI-DECK-03).

## 4. Identity and hierarchy

- Identity = `AnkiDeckRef(backendId, deckId, collectionKey?)`. The deck name is display/path only.
- `deckId` is the provider's positive `Long`, stored as decimal text. `0`, negative, blank and non-numeric ids are rejected.
- Collection key is `null` for AnkiDroid: the provider does not expose a collection identity, and this gate does not invent one.
- Hierarchy is derived from `::` in the original full name. `parentRef` is always `null` (the contract has no parent id). If a real parent deck is present in the same list, the tree links it by matching the prefix path to that deck's full name — never by hashing.
- A `::` prefix with no matching deck becomes `AnkiDeckTreeNode.VirtualGroup` (no `AnkiDeckRef`). There is no synthetic ROOT deck.
- Empty segments (`Medicine::::Cardiology`) are preserved in `path`; they never crash and never become a fake Anki deck.
- Names are not trimmed, case-folded or Unicode-normalized. `A` and `a` are two decks.

Sort (`AnkiDeckOrder`): segment-wise, case-insensitive then exact, then numeric id. Deterministic across refreshes. Locale-independent.

## 5. Partial-row policy

| Condition | Outcome |
|---|---|
| Required column (`deck_id` / `deck_name`) missing from the cursor | Fail the query (`MalformedResponse`) |
| One row has an invalid id or blank name | Skip that row, count it in diagnostics, keep the rest |
| Every row skipped | Fail the query (`all_deck_rows_unusable`) |
| Zero rows from a successful query | `Success(emptyList())` — a valid empty collection |
| Provider / permission / collection failure | Typed `AnkiError`, never an empty list |

## 6. Pipeline

```
AnkiDroid public provider
        │  (IO, Cursor.use, no Cursor escape)
        ▼
AnkiDroidProviderClient.safeQuery → AnkiDroidProviderRow
        │
        ▼
AnkiDroidDeckMapper (strict id/name, lenient counts/filtered)
        │
        ▼
AnkiDroidDeckGateway (single-flight, sort, skip policy)
        │
        ▼
AnkiDroidBackend.getDecks() / getSelectedDeck()
        │  (capability + Ready guard)
        ▼
AnkiLibraryRepository  →  LibraryDataState (StateFlow)
        │  in-memory snapshot, stale-while-error, generation
        ▼
future LibraryViewModel  (not in this gate)
```

`AnkiDroid selected deck` ≠ Study-Agent session deck. This gate never writes `selected_deck`.

## 7. Cache

- In-memory only, last-good `LibrarySnapshot`
- Scoped by `AnkiBackendId` (collection key when known; currently null)
- Not scheduling authority, not persisted
- First load: Idle → Loading → Ready / Failed
- Refresh failure with cache: Ready(stale snapshot + lastRefreshError)
- Refresh failure without cache: Failed
- Cancellation: not converted to Failed
- Single-flight + generation so a late result cannot replace a newer snapshot
- No polling

## 8. Capabilities

| Capability | AnkiDroid API | Study-Agent | Verified on device |
|---|---|---|---|
| Deck listing | supported (spec ≥ 1) | implemented | no (sandbox has no AnkiDroid) |
| Deck counts | supported | mapped, `deckCounts = false` | no |
| Selected deck | supported | implemented (read-only) | no |
| Filtered flag | supported | mapped when parseable | no |

`isReadyForReview` remains false (review still unimplemented).

## 9. Invariants

INV-ANKI-DECK-01 … 12 as specified in the GATE 05 brief (identity, empty≠failure, null≠zero, no fabricated parent ids, virtual ≠ real, read-only, cache not scheduler, backend/collection scope, stale-result rejection, no nested double-count, selected-deck concepts distinct, no card/review ops).

## 10. Deferred

ReviewInfo, scheduled cards, next-card, session, rendering, ratings, media, Library UI, Card Browser, note editing, deck mutation, AnkiDroid selected-deck writes.
