# GATE 18 — Backend Creation Contract Resolution (PART 0)

**Result: BACKEND CREATION CONTRACT LOCKED.** **IMPLEMENTATION AUTHORIZED: YES.**

This is the PART 0 investigation report and the CONTRACT-LOCK REPORT (§24). Every assertion cites a
source that was actually read at the pin named in §0. PART 0 discipline held while the contract was
open: no coordinator, ledger, gateway, media pipeline or Add Note UI was written before §24
authorized it; the only pre-lock activity was read-only inspection and disposable source probes.

## 0. Evidence sources and what was read

| ID | Source | Pin | Used for |
|---|---|---|---|
| C1 | `ankidroid/Anki-Android` `AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt` | tag `v2.24.1` (commit `9f579c10…`) | `insert()` branches: NOTES, NOTE_TYPES, MEDIA, DECKS; `insertMediaFile`; security guards; delete surface |
| C2 | same repo `api/src/main/java/com/ichi2/anki/FlashCardsContract.kt` | same | `Note` columns (`MID`,`FLDS`,`TAGS`,`ALLOW_EMPTY`), `Model` columns incl. `deck_id`, `AnkiMedia` (`FILE_URI`,`PREFERRED_NAME`), URIs |
| C3 | same repo `api/src/main/java/com/ichi2/anki/api/Utils.kt` | same | `splitFields` trailing-empty drop; `joinFields` |
| C4 | same repo `libanki/src/main/java/com/ichi2/anki/libanki/Collection.kt`, `Note.kt`, `Media.kt` | same | `addNote(note, deckId)` → `backend.addNote` returns `noteId`; `Note.fromNotetypeId` → `backend.newNote`; `setField`/`setTagsFromStr`; `Media.addFile`/`writeData` → `backend.addMediaFile` |
| C5 | same repo `libanki/.../exception/EmptyMediaException.kt` | same | empty-media refusal class (plain `Exception`, caught by provider) |
| C6 | `ankitects/anki` `rslib/src/notes/mod.rs` | tag `25.09.2` | `add_note` = `transact(Op::AddNote)`; `add_note_inner` steps; **no duplicate enforcement**; `note_fields_check` advisory; cloze check |
| C7 | `ankitects/anki` `rslib/src/notetype/cardgen.rs` | `25.09.2` | `generate_cards_for_new_note`; ≥1 card forced (`ensure_not_empty`); template target deck else `did`; missing/filtered deck → silent fallback to Default(1) |
| C8 | `ankitects/anki` `rslib/src/media/files.rs`, `media/mod.rs`, `media/service.rs` | `25.09.2` | `add_file` transact; `add_data_to_folder_uniquely` (NFC normalize, sha1 dedupe, hash-suffix rename); `add_media_file` service returns the used name; filename normalization/truncation |
| C9 | `ankitects/anki` `proto/anki/notes.proto`, `proto/anki/media.proto` | `25.09.2` | `AddNoteResponse{note_id}`; `AddNotesRequest` batch; `CardsOfNote`; `AddMediaFileRequest{desired_name,data} → String` |
| C10 | `ankitects/anki` `rslib/src/adding.rs` | `25.09.2` | deck/notetype defaults for adding (informational: provider never calls it) |
| C11 | `ankidroid/Anki-Android-Backend` `rsdroid/.../Backend.kt`, `BackendException.kt` | commit `f9b78ba1` ("release: bump version to 0.1.64-anki25.09.2"; no tag published) | `unpackResult` → `BackendException.fromError`; `Kind.NOT_FOUND_ERROR → BackendNotFoundException`; `BackendException : RuntimeException` |
| C12 | this repository `docs/PROTOCOL.md`, `server/mock_pc_agent.py` | this branch | PC write surface: **no** note/model/media message exists |
| C13 | this repository `docs/GATE_17_BACKEND_CONTRACT.md` (E8–E13) and the GATE 17 code | this branch | binder exception preservation (`Parcel.writeException`), rslib transaction rollback on any Rust error, tag canonification, `-1`/null swallow, splitFields behaviour |
| C14 | this repository `app/.../AnkiDroidApiContract.kt` | this branch | already-pinned read contracts reused for hydration: `notes/<id>`, `models/<id>` (+ `deck_id` support verified against C1 `addNoteTypeToCursor`), card columns `_id`/`ord`/`card_name`/`deck_id` |

Still not available in this sandbox: a device or emulator, Gradle/Android SDK resolution, and
AnkiDroid's `CollectionManager` at runtime. Consequences are recorded as R1 (no on-device
verification) and R2 (rsdroid pinned by commit, not a published tag).

---

## 1. Existing creation surfaces (CONTRACT-18-00)

| Capability | Existing Study-Agent path | AnkiDroid API (v2.24.1) | PC API (repo protocol) | Already production-used? |
|---|---|---|---|---|
| add / create note | none | `insert(content://…/notes, {mid, flds, tags})` (C1) | none (C12) | No |
| create deck | none | `insert(content://…/decks, {deck_name})` (C1) — **out of GATE 18 scope** | none | No |
| create note type / template / field | none | `insert(content://…/models…)`, `…/templates`, `…/fields` (C1) — **out of scope** | none | No |
| media insertion | none | `insert(content://…/media, {file_uri, preferred_name})` (C1/C2) | none | No |
| store media (other) | none | none (no media update/delete branch; `delete()` = NOTES_ID / empty-cards only, C1) | none | No |
| model listing / field schema | read-only: GATE 16 `models/<id>` (5 columns) | `query(content://…/models[/<id>])` — `addNoteTypeToCursor` additionally supports `deck_id`, `css`, `sort_field_index`, `note_count`, `latex_*` (C1) | none | Read-only yes (GATE 16) |
| duplicate check | none | rslib `note_fields_check` exists (C6) but **no provider surface exposes it** | none | No |
| card generation | never (Anki-owned) | implicit inside `col.addNote` (C4/C6/C7) | none | n/a |
| note deletion | none | `delete(notes/<id>)` exists (C1) — **out of scope** | none | No |

Conclusion: no production creation path exists anywhere; GATE 18 builds the first one, and only on
AnkiDroid.

## 2. Creation identity (CONTRACT-18-01)

What is supplied to the AnkiDroid provider `NOTES` insert (C1, `insert()` NOTES branch):

- `MID` — **modelId** (numeric note-type id). Required; looked up via `Note.fromNotetypeId` before
  anything else is done.
- `FLDS` — one string, **ordered fields** joined by `0x1f`. No field-name map, no ordinals, no
  per-field keys.
- `TAGS` — optional space-separated tag string.
- No deck id, no options. The contract's `ALLOW_EMPTY` column exists (C2) but the provider branch
  has its use **commented out** — it is ignored.
- Caller identity beyond the URI: none. No idempotency key, no caller transaction id.

What is returned:

- On success: `Uri.withAppendedPath(Note.CONTENT_URI, newNote.id.toString())` — the **noteId is
  returned synchronously** and authoritatively (it comes from rslib's `AddNoteResponse.note_id`,
  C4/C9).
- **Card ids are NOT returned.** Only the note id. Generated cards are discovered by a later read
  (§8).
- No success flag beyond the non-null URI; no created object payload.

## 3. Note type / model contract (CONTRACT-18-02)

- **Listing:** `query(content://<authority>/models)` iterates `col.notetypes.ids()` and builds one
  row per note type (C1, NOTE_TYPES branch). Per-id: `models/<id>` (GATE 16 already pins this URI).
- **Identity:** numeric `_id` (note-type id). `models/current` exists for queries but is never used
  by GATE 18 (identity must be stable and explicit).
- **Field order:** `field_names` joined by `0x1f` in note-type field order (`addNoteTypeToCursor`,
  C1) — the same order `flds` uses. Ordinal position in that list is the field identity for
  creation (the insert counts positionally, §4).
- **Field name uniqueness:** enforced by Anki's notetype machinery (rslib notetype validation), not
  by the provider; the provider passes names through. Study-Agent treats the ordered list as
  authoritative and never re-keys by name.
- **Templates:** only `num_cards` (template count) is exposed; template bodies are exposed under
  `models/*/templates` but are **never consumed** by GATE 18 — the UI must never infer field
  schemas or card outcomes from template HTML (also INV-18-02: card generation is Anki's).
- **Cloze identification:** `type` column: `0` normal, `1` cloze (C1/C2; already pinned in GATE 16
  as `MODEL_TYPE_CLOZE`).
- **Can the model change between load and create?** Yes — nothing locks it; another editor (or
  AnkiDroid itself) can add/remove/rename fields or delete the model between the schema read and the
  insert. Provider behaviour on a stale draft: field-count mismatch → `IllegalArgumentException`
  before `col.addNote` (proven non-creation, §9); deleted model → `Note.fromNotetypeId` throws a
  `BackendException` subclass before any write. GATE 18 re-reads the schema immediately before the
  boundary and refuses on drift (no silent remapping — PART I §19).
- **Effective default deck is readable:** `addNoteTypeToCursor` supports `Model.DECK_ID`
  (`noteType.did`) — used by GATE 18 only to *display* where Anki will put the cards (§6).

## 4. Field submission semantics (CONTRACT-18-03)

Creation expects an **ordered field list** (one `flds` string). Validation behaviour at the pin:

| Input | Behaviour | Evidence |
|---|---|---|
| missing required field | not expressable: every field ordinal must be present exactly once | count check (C1) |
| wrong field count | `IllegalArgumentException("Incorrect flds argument…")` **before** `col.addNote` → proven non-creation | C1 NOTES branch; C3 (`splitFields` drops trailing empties, so a payload whose last field is empty *always* fails the count) |
| extra unknown field | same count check | C1 |
| empty field | accepted by the provider; rslib creates the note (all-empty note gets one forced card, §7). Product rule rejects an empty *first* field pre-boundary (§13) | C6/C7 |
| invalid field order | not detectable by the backend: order is positional and authoritative; a reordered draft writes reordered content. Prevention = schema re-read + drift refusal (§3) | C1 |
| model mismatch (deleted) | `Note.fromNotetypeId` → backend `NOT_FOUND_ERROR` → `BackendNotFoundException` before any write | C4, C11 |
| `0x1f` inside a field value | would silently shift ordinals after `splitFields` — GATE 18 refuses it during validation, pre-boundary | C3 |

`AddNoteDraft` may now be defined: model ref + ordered field values keyed by ordinal + tags +
pending media (§17).

## 5. Card generation semantics (CONTRACT-18-04)

> Does the backend create the cards automatically from the note/model/template?

**Yes.** `col.addNote(newNote, did)` → rslib `add_note_inner` → `generate_cards_for_new_note`
(C4/C6/C7). Study-Agent submits the NOTE only and never generates a card itself. The architectural
preference `Study-Agent creates NOTE → Anki generates CARD(S)` is exactly the real contract.

- **One note creates ≥1 card, always.** For a *new* note `new_cards_required(..., ensure_not_empty
  = true)`: if no template generates a card, rslib forces card ord 0 (C7). Zero-card creation is
  impossible through `add_note`.
- **One note can create multiple cards:** a normal notetype generates one card per template whose
  front side renders non-empty against the note's non-empty fields; a cloze notetype generates one
  card per distinct cloze number found in the fields (card ord = cloze number − 1, capped at 499)
  (C7).
- **Card IDs are knowable only after creation:** the insert returns only the note id; cards are
  enumerated by reading `notes/<id>/cards` (§8).
- **Cloze content:** submitted as ordinary field text containing `{{cN::…}}`; rslib's cloze card
  generation is authoritative (C7). No local cloze card generation (CONTRACT-18-25 ✓).

## 6. Deck assignment semantics (CONTRACT-18-05)

- The provider's NOTES insert **accepts no deck parameter**: it calls
  `col.addNote(newNote, newNote.notetype.did)` (C1). `deckId` at creation therefore means: *the
  note type's stored last/default deck* (`notetype.did`), not a caller choice.
- Generated-card placement (C7 `add_generated_cards`): each generated card goes to the template's
  own target deck when the template sets one, else the `did` passed to `addNote` (i.e. the note
  type's default deck).
- **Missing or filtered deck → silent fallback to the Default deck (id 1)** (`deck_for_adding`
  falls back; C7). There is **no DeckNotFound refusal** at creation.
- Multi-card notes: all generated cards begin in decks chosen by the rules above — normally one
  deck, but per-template overrides can split them. Study-Agent must not claim a single deck.
- Consequence: **GATE 18 offers no deck selector.** The UI displays the note type's default deck
  (read via `models/<id>` `deck_id`, resolved against the deck listing) as information only, and
  warns that Anki places the cards. Deck selection is a later gate's capability and would require a
  backend surface that does not exist at this pin.
- CONTRACT-18-23 (deck creation): the provider's `DECKS` insert exists but is **not used**; GATE 18
  requires existing decks only — in fact it requires nothing about decks at all, since the caller
  cannot pass one.
- CONTRACT-18-24 (model creation): the provider's NOTE_TYPES/templates/fields inserts exist but are
  **forbidden** to GATE 18 code; existing models only. The architecture audit pins this.

## 7. Duplicate detection (CONTRACT-18-07)

- **Backend-enforced at creation: NO.** `add_note_inner` performs no duplicate check (C6). The note
  row is inserted regardless of identical first fields.
- rslib offers `note_fields_check` (first-field checksum compare within the same notetype,
  `is_duplicate`, C6), but it is advisory UI input in Anki itself and **no provider surface exposes
  it** (C1 has no such URI). Duplicate handling is therefore *model-dependent* and *first-field
  based* in Anki's own tooling, but **unsupported through the pinned public contract** for
  Study-Agent.
- Answers: a "duplicate" = same notetype + identical first field after HTML stripping (Anki's
  definition, C6); the backend never rejects one before creating; nothing races because nothing
  checks. There is no evidence path that proves "no note was created due to duplication" —
  duplication never blocks creation at this surface.
- GATE 18 does **not** invent a global duplicate algorithm: the result model has **no**
  `DuplicateRejected` branch, the UI makes no duplicate-protection claim, and duplicate *search* is
  not used as recovery evidence (§14).

## 8. Tags, atomicity, boundaries (CONTRACT-18-06 / 18-08 / 18-15)

- **Tags at creation:** supplied inside the create-note operation (`TAGS` value, C1). The provider
  sets them on the note before `col.addNote`; rslib `canonify_note_tags` runs inside the same
  `Op::AddNote` transaction (C6; canonification rules pinned in GATE 17 E10: NFC per `::`
  component, case adoption, dedupe, blank→`blank`, sorted). **Create Note + Tags is one atomic
  operation** at this pin.
- **Atomicity classification:**

  | Unit | Classification | Evidence |
  |---|---|---|
  | note + tags | **ATOMIC_SINGLE_OPERATION** (one rslib `Op::AddNote` transaction; any Rust error rolls it back) | C6, C13 (E11) |
  | note + deck | n/a — no caller deck input (§6) | C1 |
  | note + media | **MULTI_OPERATION** — media is stored by separate `media` inserts (§9) | C1 |

- **Mutation boundaries (exact points after which backend state may exist):**
  - media insertion boundary: dispatch of `ContentResolver.insert(content://<authority>/media, …)`;
  - note insertion boundary: dispatch of `ContentResolver.insert(content://<authority>/notes, …)`.
  Everything before these dispatches (validation, schema re-read, durable intent, local media
  pre-checks) is effect-free. The durable creation record must be persisted **before** each boundary
  is entered (§16/§18).

## 9. Media storage contract (CONTRACT-18-09 / 18-26 / 18-27 / 18-28)

- **API:** `insert(content://<authority>/media, {file_uri: <content-uri string>, preferred_name})`
  (C1 `insertMediaFile`, C2 `AnkiMedia`). Study-Agent never touches Anki's media directories; the
  provider copies the content itself (`internalizeUri` into a temp file in AnkiDroid's cache) and
  then calls libanki `Media.addFile` → `backend.addMediaFile(desiredName, data)` → rslib
  `add_media_file` (C4, C8).
- **Identifier supplied:** a content URI string the *AnkiDroid process* can read, plus a preferred
  name. The provider builds the actual desired name as `<preferred_name>_<random>.<ext>` from a
  temp file (`File.createTempFile`), where `<ext>` comes from `MimeTypeMap` on the URI's MIME type
  (unknown MIME → the literal extension `.null`). **The caller never controls the final name.**
- **Identifier returned:** `Uri.fromFile(File(fname))` — a `file://` URI whose last path segment is
  the **backend-chosen authoritative filename**. GATE 18 keeps the name (the part fields reference),
  never the `file://` path.
- **Filename collision (C8 `add_data_to_folder_uniquely`):**
  - no existing file → written under the NFC-normalized name;
  - existing file with **same sha1** → deduplicated: same name returned, nothing written (content-
    idempotent);
  - existing file with different content → renamed with a 40-hex sha1 suffix on the stem.
- **Overwrite/dedup:** the backend deduplicates by content hash; it never overwrites different
  content under the same name.
- **MIME/content constraints:** empty file → `EmptyMediaException` (caught, null result, C1/C5);
  OOM/IOException caught → null result; names are NFC-normalized, invalid characters sanitized and
  the name truncated to Anki's max media filename length (C8).
- **Reference in fields (CONTRACT-18-26):** standard Anki semantics only: images as
  `<img src="NAME">`, sounds as `[sound:NAME]`, where NAME is exactly the backend-returned filename.
  No paths are manufactured.
- **Filename policy (CONTRACT-18-27):** allowed names = whatever rslib normalization accepts;
  Unicode/Arabic names are preserved (NFC-normalized); case is preserved; the backend is the naming
  authority, so Study-Agent only *requests* a sanitized preferred name and records what came back.
- **Payload constraints (CONTRACT-18-28):** the provider reads the whole file into memory in the
  AnkiDroid process (`file.readBytes()`, C4) and the contract warns of OOM near 2 GB. Product limit,
  enforced pre-boundary by Study-Agent: **≤ 25 MB per file, ≤ 8 files per creation** (bounded plan);
  no documented backend limit on field length or tag count — Study-Agent adds none beyond
  structural rules (no `0x1f`, valid tags).
- **Media can be referenced immediately:** yes — once `add_media_file` returns the name, any note
  field may reference it (same collection).

## 10. Media ownership, atomicity, orphans (CONTRACT-18-10 / 18-11 / 18-12 / 18-13)

- **Ownership:** once stored, the Anki collection owns the media file. Study-Agent owns only the
  local pre-dispatch preparation (choosing the URI, checking size). Media insertion is permanent
  from Study-Agent's standpoint: **the provider exposes no media delete** (`delete()` supports only
  `notes/<id>` and empty-cards; media → `UnsupportedOperationException`, C1).
- **Note + media atomicity: NO.** They are separate provider operations with separate transactions
  (the media DB transaction inside `add_file` is unrelated to any note transaction, C8). Never
  claimed atomic.
- **Orphan media (media stored → note creation fails):** possible and **not cleanable by
  Study-Agent**: deletion is not public, ownership cannot be proven (another note or Anki itself may
  already reference the name; content-hash dedupe means the same bytes may be shared). Policy locked
  (§20): **never delete automatically; no manual delete offered; residual orphans are left to Anki's
  own Check Media tool.** Mitigation by ordering: media is stored *before* the note boundary, so the
  dangerous direction (note referencing missing media, CONTRACT-18-13) cannot happen — a note is
  only ever created with names the backend already returned.
- **Missing media risk:** eliminated by ordering (§15); a field never references a name that was not
  returned by a `Stored` result.

## 11. Safe operation ordering (CONTRACT-18-14)

Locked order:

```
validate (pure)
→ durable PREPARED intent
→ schema re-read + drift refusal
→ for each pending media: durable step mark → store media boundary → persist returned name
→ durable note-boundary mark → create-note boundary (fields assembled from stored names)
→ ConfirmedCreated → durable CREATED → hydrate (read-only)
```

Justification (irreversibility/orphan/referential-integrity/rollback criteria):

1. Media first, because a note referencing *missing* media is user-visible corruption, while orphan
   media is benign (Anki's Check Media handles it) and media storage is content-idempotent (sha1
   dedupe ⇒ replay never duplicates bytes).
2. Note creation last, because it is the only non-idempotent irreversible step: exactly one
   boundary crossing, never replayed automatically.
3. Referential integrity: field text is assembled only from backend-returned names, so a created
   note can never dangle.
4. Ambiguity windows shrink to the minimum: after the note boundary there is nothing left to do but
   record the answer.

## 12. Success / non-creation / ambiguity evidence (CONTRACT-18-16 / 18-17 / 18-18)

**Note creation:**

- **Confirmed creation evidence:** `insert(notes)` returns a URI whose last segment parses to a
  positive note id. That id comes from rslib's `AddNoteResponse.note_id` (C4/C9) — authoritative
  proof the note exists. Transport success ≠ creation success is honoured: a null or thrown answer
  is never upgraded.
- **Confirmed NON-creation evidence** (all proven pre-write at this pin, mirroring GATE 17's R1
  reasoning with the same three-part chain — every throw site precedes `col.addNote`; backend Rust
  errors cannot arrive as IAE; the binder preserves only the listed classes):
  - `SecurityException` — permission guard is the first statement of `insert` (C1);
  - `IllegalArgumentException` — field-count check, unsupported URI, `NOTES_ID`/cards inserts,
    client-side unknown-authority — all before any write;
  - `NumberFormatException` — none in the NOTES branch path itself (MID parse is `getAsLong`), kept
    for symmetry with the edit contract;
  - `NullPointerException` — `values!!` guard, before any collection access;
  - `NotDispatched` — Study-Agent refused before any IPC.
  - `BackendNotFoundException` (a `BackendException`, `RuntimeException`) can only originate from
    `Note.fromNotetypeId` at this pin (nothing on the `add_note_inner` path raises NOT_FOUND, C6/C7),
    which runs before `col.addNote`; nevertheless GATE 18 classifies **all `BackendException` /
    `RuntimeException` throws as `OutcomeUnknown`**, keeping the conservative GATE 17 precedent:
    the binder cannot prove where in the call the error occurred, and model existence is re-checked
    pre-boundary anyway (defence in depth).
- **Ambiguous creation (CONTRACT-18-18): YES, possible.** Windows: binder reply lost after commit;
  provider process death (platform returns **null** from `ContentResolver.insert`, analogous to the
  pinned `-1` for update — C13); wait timeout while the call may still run; `RuntimeException`
  throws (§above). **Blind retry may create a duplicate note** and is forbidden (§19).

**Media storage:**

- **Confirmed stored:** non-null `file://` URI → authoritative name (C1).
- **Confirmed NOT stored:** `SecurityException`, client-side `IllegalArgumentException`
  (unresolvable URI), `NullPointerException` (missing `FILE_URI`), `NotDispatched` (local refusal:
  oversize, unreadable, unknown MIME) — all before or without a store.
- **Unknown:** **null** return (covers pre-store failures *and* provider death after store —
  indistinguishable), wait timeout, any other throwable.

## 13. Pre-boundary validation (product rules, all refuse before any durable record)

1. capabilities permit creation (backend claims `createNotes`); backend context unchanged
   (backendId + collectionKey match the draft's binding);
2. model exists in a fresh `models` read; draft's schema version matches (field names + count);
3. exactly one value per field ordinal; no value contains `0x1f`;
4. first field non-empty after trimming (mirrors Anki desktop's blocking *Empty* state from
   `note_fields_check`, C6 — a product rule, not a backend rejection);
5. **last field non-empty** — trailing empties are unrepresentable through `splitFields` (C3); the
   backend would refuse with an IAE, GATE 18 refuses earlier;
6. tags: none blank, none containing whitespace/control characters, no `::` component blank (the
   backend would rewrite such tags — GATE 17 rule, re-locked);
7. media: each pending item has a readable local content URI, size ≤ 25 MB, a MIME type that maps
   to a known extension, and the plan has ≤ 8 items.

## 14. Idempotency / reconciliation (CONTRACT-18-19 / 18-20 / 18-21)

- **Caller idempotency keys: none.** The insert values carry no such column (C1/C2);
  `NoteCreationId` is Study-Agent's local transaction identity and is **not** backend idempotency
  (INV-18-07).
- **Media storage is effectively content-idempotent** (sha1 dedupe, C8) — replaying an identical
  store is safe (same bytes ⇒ same name). This is an *effect*, still not a contractual replay
  guarantee for arbitrary inputs; the ledger never relies on it for note creation.
- **Reconciliation: NO_RECONCILIATION.** There is no lookup-by-transaction, no receipt, and notes
  expose no creation-time column (GATE 16 pinned table), so "did request X create note Y?" is
  unanswerable authoritatively. Field-value search is STATE_SEARCH_ONLY at best; GATE 18 does not
  run it automatically and never converts a heuristic match into CREATED (CONTRACT-18-21 ✓). An
  ambiguous creation is closed only by human attestation, exactly like GATE 17's ambiguous edits.

## 15. Creation mutation boundaries and durable order (CONTRACT-18-15)

```
validation (effect-free)
→ PREPARED durable                       (no backend effect yet)
→ STORING_MEDIA durable (step i marked)  → media boundary i (effect possible from here)
→ ...persist each returned media name...
→ CREATING_NOTE durable                  → note boundary (effect possible from here)
→ CREATED durable                        (backend confirmed + finalized)
→ hydration (read-only; failure never re-opens creation)
```

No boundary is ever entered before its durable mark exists.

## 16. Note creation result semantics (CONTRACT-18-39, locked)

```kotlin
sealed interface CreateNoteBackendResult {
    data class ConfirmedCreated(val noteId: String) : CreateNoteBackendResult
    data class ConfirmedNotCreated(val error: AnkiError) : CreateNoteBackendResult
    data class OutcomeUnknown(val error: AnkiError) : CreateNoteBackendResult
}
```

No `DuplicateRejected` and no `Conflict` branch: the pinned backends have neither semantic (§7).
`cardRefs` are deliberately not part of the result: card identity is established by the hydration
read, not by the insert (§8/CONTRACT-18-31).

## 17. Media result semantics (CONTRACT-18-40, locked)

```kotlin
sealed interface MediaStoreBackendResult {
    data class Stored(val mediaName: String) : MediaStoreBackendResult
    data class ConfirmedNotStored(val error: AnkiError) : MediaStoreBackendResult
    data class OutcomeUnknown(val error: AnkiError) : MediaStoreBackendResult
}
```

`mediaName` is the backend-returned authoritative filename. No richer `AnkiMediaRef` is invented:
the backend returns a name, so the contract carries a name.

## 18. Durable creation state model (CONTRACT-18-41, crash windows)

Status enum (locked; deliberately separate from edit/review/reviewer-action statuses):

```
PREPARED → STORING_MEDIA → CREATING_NOTE → CREATED
                 ↘               ↘
             RETRY_ALLOWED      AMBIGUOUS
```

- `PREPARED` — intent durable; no effect. Restart: abandoned silently (nothing can have happened).
- `STORING_MEDIA` — at least one media boundary may have been entered; per-step stored names are
  persisted as they arrive. Restart: abandoned with an honest orphan-media note (§20); the note was
  never created, so nothing blocks; the user creates again (content-dedupe makes re-store harmless).
- `CREATING_NOTE` — note boundary entered; outcome not durably recorded. Restart → `AMBIGUOUS`.
- `CREATED` — terminal positive. Restart: kept; hydration resumes on next open (never re-creates).
- `RETRY_ALLOWED` — proven non-creation at the note boundary; the *user* may retry under the same
  id after a fresh validation; nothing retries automatically.
- `AMBIGUOUS` — unknown creation outcome; **never retried**; surfaced for human attestation
  (`exists in collection` / `absent`). It does not block future creations (the note id is unknown,
  so there is nothing addressable to block) — this is stated honestly rather than pretending to
  lock a note that cannot be identified.

Windows and answers:

| Crash window | Replay? | Reconcile? | Clean up? | Block? | User may retry? |
|---|---|---|---|---|---|
| before durable intent | n/a | n/a | n/a | no | n/a |
| after PREPARED, before any boundary | no need | n/a | nothing to clean | no | fresh creation |
| during/after media store, before note boundary | media re-store safe (dedupe) | n/a | **no deletion** (orphan policy) | no | fresh creation |
| after note boundary, before response persisted | **never** | no (none exists) | no | record AMBIGUOUS | only attestation |
| after CREATED, before hydration | no re-create | n/a | n/a | no | hydration only |

## 19. Retry policy (CONTRACT-18-43, locked)

```
unknown create outcome → NEVER retry (automatically or as a suggested default)
```

- Automatic retry: **none** for note creation, none for media (media re-store happens only inside a
  user-requested retry of a RETRY_ALLOWED creation, and is dedupe-safe).
- User retry: only from `RETRY_ALLOWED` (proven non-creation), under the same `NoteCreationId`,
  after re-running §13 validation including a fresh schema read.
- AMBIGUOUS: attestation only. No blind replay, ever (INV-18-08).

## 20. Orphan media policy (CONTRACT-18-42, locked)

```
orphan media cleanup: UNSUPPORTED
```

No automatic deletion, no manual deletion affordance. Deletion is not public at this pin (C1),
ownership cannot be proven, and content-hash dedupe makes repeated identical uploads harmless.
Residual risk (stored-but-unreferenced files) is disclosed to the user and left to Anki's own
Check Media tooling (CONTRACT-18-12 ✓).

## 21. Post-creation hydration (CONTRACT-18-30 / 18-31)

After `CREATED` is durable, GATE 18 reads backend truth:

- note: `notes/<noteId>` (`mid`, `flds`, `tags`, `mod` — GATE 16 pinned projection);
- generated cards: `notes/<noteId>/cards` — one row per generated card with the GATE 07/16 pinned
  card columns (`_id` card id, `ord`, `card_name`, `deck_id`, …) — this is the authoritative
  enumeration of generated cards (CONTRACT-18-31; provider branch NOTES_ID_CARDS, C1);
- model display name: `models/<mid>`; deck display names: the existing deck listing.

The created-card view is built **only** from this read. Hydration failure leaves the creation
CREATED and reports the read failure separately (INV-18-09); it never triggers re-creation.
One note ≠ one card is honoured everywhere (`List`, never a single assumed card).

## 22. Active Study interaction (CONTRACT-18-29)

Creation is independent of the current Study turn: the coordinator touches no review session, never
advances the scheduler, never injects the new note into an active turn, and never changes the
backend-selected deck. A newly created note becomes eligible strictly according to Anki's scheduler
truth. (INV-18-19; pinned by the architecture audit: creation files reference no session/rating
API.)

## 23. Creation contract matrix (CONTRACT-18-36)

| Property | AnkiDroid (v2.24.1) | PC Agent (repo protocol) |
|---|---|---|
| List models | SUPPORTED — `models` query, `notetypes.ids()` (C1) | UNSUPPORTED — no message type (C12) |
| Read model fields | SUPPORTED — `field_names` ordered, `type`, `num_cards`, `deck_id` (C1/C14) | UNSUPPORTED |
| Create note | SUPPORTED — `insert(notes, {mid, flds, tags})`, one rslib tx (C1/C6) | UNSUPPORTED |
| Note ID returned | SUPPORTED — URI carries `AddNoteResponse.note_id` (C1/C4/C9) | UNSUPPORTED |
| Generated card IDs returned | UNSUPPORTED — note id only; cards via `notes/<id>/cards` read (C1) | UNSUPPORTED |
| Multi-card generation | SUPPORTED — per non-empty template; ≥1 forced; cloze per number (C7) | UNSUPPORTED |
| Tags during create | SUPPORTED — same insert, canonified inside the same tx (C1/C6) | UNSUPPORTED |
| Deck during create | UNSUPPORTED — no deck input; notetype default + template overrides + silent Default fallback (C1/C7) | UNSUPPORTED |
| Duplicate detection | UNSUPPORTED — rslib check exists but is unexposed and advisory; add never rejects (C6/C1) | UNSUPPORTED |
| Media insertion | SUPPORTED — `insert(media, {file_uri, preferred_name})` → authoritative name (C1/C4/C8) | UNSUPPORTED |
| Media naming | SUPPORTED — NFC normalize, sha1 dedupe, hash-suffix rename, truncation (C8) | UNSUPPORTED |
| Note+media atomic | UNSUPPORTED — separate operations/transactions (C1/C8) | UNSUPPORTED |
| Confirmed creation evidence | SUPPORTED — returned note id (C1/C9) | UNSUPPORTED |
| Confirmed non-creation | SUPPORTED — SecurityException / IAE / NPE / NotDispatched, proven pre-write (§12) | UNSUPPORTED |
| Ambiguous creation possible | SUPPORTED — yes: lost reply, provider death (null), timeout, RuntimeException (§12) | UNSUPPORTED |
| Idempotency key | UNSUPPORTED — no caller key exists (C1/C2); media store is content-deduped (effect only, C8) | UNSUPPORTED |
| Reconciliation | UNSUPPORTED — no transaction correlation, no creation-time column (C14) | UNSUPPORTED |
| Post-create read | SUPPORTED — `notes/<id>`, `notes/<id>/cards`, `models/<id>`, decks (C1/C14) | UNSUPPORTED |

Every cell classified; UNKNOWN does not appear because the only unknown backend (PC) has no
contract at all in this repository, which is itself the classification.

## 24. CONTRACT-LOCK REPORT

```
GATE 18 — BACKEND CREATION CONTRACT RESOLUTION

RESULT:
LOCKED

ANKIDROID
- model discovery:   models query (all ids) + models/<id> (_id, name, field_names, type,
                     num_cards, deck_id) — C1/C14
- field schema:      ordered field_names (0x1f); ordinal identity; count-checked on insert;
                     model drift handled by pre-boundary re-read + refusal — C1/C3
- note creation:     insert(content://…/notes, {mid, flds, tags}); one rslib Op::AddNote tx;
                     deck = notetype default (no caller deck), silent Default fallback — C1/C6/C7
- generated cards:   Anki-generated; ≥1 forced; multi-card and cloze supported; ids only via
                     notes/<id>/cards read after creation — C7/C1
- tags:              same insert, canonified inside the same tx (NFC/case/dedupe/sorted) — C6/E10
- deck semantics:    notetype.did + template overrides; no selection; display-only reading — C1/C7
- duplicates:        NOT enforced; advisory rslib check unexposed; no rejection branch — C6/C1
- media:             insert(content://…/media, {file_uri, preferred_name}); authoritative returned
                     name; NFC/dedupe/hash-rename; ≤25MB & ≤8 items product limits; no delete — C1/C8
- atomicity:         note+tags ATOMIC_SINGLE_OPERATION; +media MULTI_OPERATION (media first) — C6/C8
- idempotency:       none contractual; note creation never idempotent; media content-deduped — C8
- mutation boundaries: after ContentResolver.insert dispatch (media, then note) — §8/§15
- success proof:     returned URI with rslib note id (note); returned file:// URI name (media) — C1
- non-creation proof: SecurityException, IllegalArgumentException, NullPointerException,
                     NotDispatched — all proven pre-write (§12, GATE-17-R1-style chain)
- reconciliation:    NONE (no transaction correlation, no creation-time column) — C14
- orphan policy:     never delete; unsupported; disclosed residual risk — C1/§20

PC BACKEND
- model discovery:   UNSUPPORTED (no message in docs/PROTOCOL.md or mock_pc_agent.py) — C12
- field schema:      UNSUPPORTED
- note creation:     UNSUPPORTED
- generated cards:   UNSUPPORTED
- tags:              UNSUPPORTED
- deck semantics:    UNSUPPORTED
- duplicates:        UNSUPPORTED
- media:             UNSUPPORTED
- atomicity:         UNSUPPORTED
- idempotency:       UNSUPPORTED
- mutation boundaries: none (no surface)
- success proof:     UNSUPPORTED
- non-creation proof: UNSUPPORTED
- reconciliation:    UNSUPPORTED
- orphan policy:     n/a (no media surface)

NORMALIZED CONTRACT
- operations:  getNoteModels() [read]; storeAnkiMedia(req) [irreversible];
               createAnkiNote(req) [irreversible, note+tags atomic]; resolveCreatedNote(ref) [read]
- identities:  numeric modelId + ordered fields + tags (create); backend-returned noteId (result);
               backend-returned media name (media result); NoteCreationId is LOCAL ONLY
- result models: CreateNoteBackendResult{ConfirmedCreated(noteId), ConfirmedNotCreated, OutcomeUnknown};
               MediaStoreBackendResult{Stored(mediaName), ConfirmedNotStored, OutcomeUnknown}
- capabilities: noteModelListing, createNotes, storeMedia (AnkiDroid only); coarse createNotes is
               gated by the write path being wired; authoritativeCreationReconciliation = false
- retry policy: never automatic; user retry only from RETRY_ALLOWED (proven non-creation),
               same NoteCreationId, fresh validation + schema re-read; AMBIGUOUS → attestation only
- recovery policy: restart — PREPARED/STORING_MEDIA abandoned (orphan note if media ran);
               CREATING_NOTE → AMBIGUOUS; CREATED kept, hydration resumes; hydration failure
               never re-creates

UNSUPPORTED:
- deck selection/creation at creation time (no provider input; CONTRACT-18-23)
- model/note-type/template/field creation (CONTRACT-18-24) — forbidden to GATE 18 code
- duplicate detection/rejection (CONTRACT-18-07)
- media deletion / orphan cleanup (CONTRACT-18-12/42)
- batch note creation (provider is single-insert; CONTRACT-18-22)
- authoritative create reconciliation (CONTRACT-18-20)
- any PC creation capability (C12)

UNCERTAINTIES (accepted, none affecting creation safety):
- R1: no device/emulator/Gradle run possible here; provider runtime behaviour inferred from pinned
  source; JVM harness (tools/jvm-harness) is equivalence evidence only
- R2: rsdroid pinned by release-bump commit f9b78ba1 (no 0.1.64 tag published); the unpack/error
  mechanics read there are the same ones GATE 17 already relied on at 0.1.63
- R3: NormalizeNoteText preference unreadable through the provider (GATE 17 R8 carries over) —
  hydration compares NFC-tolerantly and creation never treats normalization as failure
- R4: media temp-name uses the provider's random suffix and a MIME-derived extension (unknown MIME
  → ".null"); GATE 18 therefore refuses unknown-MIME attachments pre-boundary instead of
  generating ".null" names
- R5: irreducible ambiguity windows (lost binder reply, provider death, timeout) end in AMBIGUOUS
  with attestation as the only exit; identical to the GATE 17 treatment

IMPLEMENTATION AUTHORIZED:
YES
```

Authorization scope: PART I (creation domain, ledger, coordinator, gateways, mapper, Add Note UI),
PART I-A (checkpoint reports), PART III verification tests. It does not extend to the PC backend,
to deck/model creation, to media deletion, or to any weakening of GATE 11–17.
