# GATE 17 — Backend Contract Resolution (PART 0)

**Result: BLOCKED.** **IMPLEMENTATION AUTHORIZED: NO.**

This is the PART 0 investigation report. It changes no production code and adds no mutation,
UI or retry behaviour. Every assertion cites a source that was actually read in this pass.

## 0. Evidence sources and what was read

| ID | Source | Pin | Used for |
|---|---|---|---|
| E1 | `ankidroid/Anki-Android` `AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt` | tag `v2.24.1` = commit `9f579c10bb151146728220729c510acbbd8faba7` | provider `update()` branches, `require`s, return counts |
| E2 | same repo `api/src/main/java/com/ichi2/anki/api/Utils.kt` | same | `splitFields` trailing-empty behaviour |
| E3 | same repo `api/src/main/java/com/ichi2/anki/FlashCardsContract.kt` | same | column contracts (`flds`, `tags`, `deck_id`) |
| E4 | `ankitects/anki` `rslib/src/notes/mod.rs` | tag `25.09.2` (version cited by v2.24.1) | `update_note` transaction scope, no-op on identical note, card generation |
| E5 | `ankitects/anki` `rslib/src/scheduler/bury_and_suspend.rs` | `25.09.2` | bury/suspend idempotency |
| E6 | repository `server/mock_pc_agent.py`, `docs/PROTOCOL.md`, `app/.../core/network`, `app/.../data/anki` | this branch | PC write surface |
| E7 | repository `docs/GATE_17_NOTE_EDITING.md` and the GATE 17 code | this branch | claims under audit |

Not available in this sandbox: `libanki` (the Python layer between the provider and rslib),
AnkiDroid's `CollectionManager`, and the device. Claims that depend on them are marked UNKNOWN.

## 1. Existing write surfaces (CONTRACT-00)

| Mutation | Existing Study-Agent path | AnkiDroid path | PC path | Production use? |
|---|---|---|---|---|
| Update fields | `AnkiDroidNoteMutationMapper` → `AnkiDroidNoteMutationGateway` → `safeUpdate("notes/<id>", flds)` | `notes/<id>` `flds` (E1 L495–535) | none | Only via `NoteMutationCoordinator` (guarded by `CoordinatorTest`/audit) |
| Replace tags | same `notes/<id>` update, `tags` key | `notes/<id>` `tags` (E1 L495–535) | none | same |
| Add / remove tags (delta) | none (set-replace only) | not offered by provider | none | No |
| Move card deck | `notes/<id>/cards/<ord>` `deck_id` | `NOTES_ID_CARDS_ORD` (E1 L540–562) | none | Same coordinator |
| Bury / suspend | `performReviewerAction` → `SCHEDULE` update | E1 L666–710 | none | Out of GATE 17 |
| Answer card | rating path | E1 SCHEDULE branch | none | Out of GATE 17 |
| Generic `executeAction` / `mutation` / `updateCard` / `updateNote` | none | none | none | — |
| `ContentResolver.update` direct calls | only the four sanctioned gateways (`AnkiDroidProviderClient.safeUpdate`) | — | — | Architecture audit enforces this |
| AnkiConnect `updateNoteFields` / `updateNote` / `changeDeck` / `replaceTags` | no code path | not applicable | not applicable | No |

## 2. AnkiDroid field mutation (CONTRACT-01)

- **API symbol:** `CardContentProvider.update(uri, values, null, null)`, `NOTES_ID` branch
  (E1 L495–535), URI `content://<authority>/notes/<noteId>`.
- **Input identity:** the note ID from the path (`uri.pathSegments[1].toLong()`). The card is not
  addressed. Fields are not addressed by name.
- **Payload:** `flds` = one string, fields joined by U+001F. The provider splits it with
  `Utils.splitFields` (E2 L38–42).
- **Return:** `updated` = one per recognised key (E1 L518, L526; `return updated` at L734).
- **Transaction scope:** the provider mutates the in-memory `Note` for every key, then calls
  `col.updateNote(currentNote)` **once** (E1 L535). That call is `transact(Op::UpdateNote, …)`
  in rslib (E4 L393–394). Note and tag writes therefore share one rslib transaction. The
  in-memory edits before that call are not persisted until it runs.

Answers:

1. **Identity:** the note ID (`notes/<id>`). Not a card or a URI handle.
2. **Replace all fields or only supplied ones?** The `flds` key replaces **all** fields
   positionally. Omitting a field is not possible inside a `flds` value.
3. **Field identity:** **ordinal (positional)** only. There is no name key. The provider does not
   check names.
4. **Model changed between read and write?** The provider does not compare. It uses the note's
   *current* field count (`require(flds.size == currentNote.fields.size)`, E1 L511). If the model
   gained or lost a field, the count check refuses. If the count matches but the meaning moved,
   the write is accepted. **Not detected.**
5. **Can unchanged fields be omitted?** No. The coordinator must send every field. The existing
   `materialize()` already does this from the in-memory base. Stale values in that base cause
   overwrites, which is why the pre-write re-read exists.
6. **Does an invalid field count reject the whole operation?** Yes. `require` runs before
   `updateNote`, so nothing is written.
7. **Can it partially modify a note?** No. Failures before `updateNote` leave the note unchanged,
   and `updateNote` is one rslib transaction (E4).
8. **Confirmed success:** the call returns `expectedRows` = number of keys sent, **and** the
   provider returns normally. The row count proves the code path completed, not that the stored
   value equals the payload. (Gap: the existing design accepts the count as applied. See §11, R2.)
9. **Confirmed non-application:** only refusals raised *before* `updateNote`. See §7 for which
   exception classes qualify, and why `IllegalArgumentException` does not qualify as cleanly as
   the GATE 17 docs claim.
10. **Timeout or exception after application?** Yes. `updateNote` can complete and the binder
    reply can be lost. `ContentResolver.update` returns **−1** when the provider process dies
    mid-call (E1 comment; `UPDATE_REMOTE_FAILURE_ROWS` in `AnkiDroidApiContract`). Exceptions
    thrown by `col.updateNote` itself are not excluded (§7).

**Side effect found (not in GATE 17 docs):** `update_note_inner_generating_cards` calls
`generate_cards_for_existing_note` (E4 L446–465). Editing fields can create **new sibling cards**
when a template's front becomes non-empty. The `NOTES_ID` response does not report them. Deck
placement of generated cards was not read (UNKNOWN). See §10, CONTRACT-15.

## 3. AnkiDroid tag mutation (CONTRACT-02)

- Same `notes/<id>` update, key `tags`. The value is one space-separated string
  (`setTagsFromStr(col, tags)`, E1 ~L521). **Replace-only:** the provider has no add or remove
  operation (E1 has no tag delta).
- Identification: tag text as sent. Ordering: the string is canonified by rslib
  `canonify_tags` (E4 L367–371). Whether that makes order meaningless, and how case and
  hierarchy are matched, was **not read** (UNKNOWN).
- Duplicate input and case matching: UNKNOWN (`canonify_tags` not read). The GATE 17 planner
  deduplicates case-insensitively **before** sending, which is a Study-Agent rule, not a backend
  guarantee.
- Atomicity: tags go in the same `updateNote` call as fields, so a combined `flds`+`tags` update
  is one rslib transaction (E4 L393). Tag-only and combined updates have the same scope.
- Success evidence: same as fields (row count, §2 item 8).
- Ambiguity: same as fields.
- `setTagsFromStr` may register tags in the collection before `updateNote`. Whether that happens
  is UNKNOWN (libanki not available). It would be a tag-registry side effect, not a note change.

## 4. AnkiDroid deck mutation (CONTRACT-03)

- **API:** `update(content://<authority>/notes/<noteId>/cards/<ord>, {deck_id})`, branch
  `NOTES_ID_CARDS_ORD` (E1 L540–562).
- **Accepted keys:** only `deck_id` (`isDeckUpdate = key == DECK_ID`). Any other key ends in
  `throw IllegalArgumentException("Currently only updates of decks are supported")` (E1 ~L561).
- **Guards:** `require(!col.decks.isFiltered(did))` (E1). A negative ID falls through to the
  "only decks" throw. The target deck's **existence is not checked** by the provider. The
  coordinator checks it through `getDecks()` (existing `checkDeckTarget`).
- **Object moved:** `currentCard.did = did; col.updateCard(currentCard)`. **One card**, selected by
  `(noteId, ord)`. Its note, and its siblings, are not touched.

Answers for the "Note N with cards A and B" question:

| Move Deck X → Y for card A | Result |
|---|---|
| Card A | **changes** (`updateCard`) |
| Card B (sibling) | does not change (only A is updated; `updateCard` is per card) |
| Note-level default deck / metadata | does not change (the provider never writes the note row here) |
| Subset of backend-defined cards | none |

This is card-only, so the UI label must be "Move this card" and not "Move note". The GATE 17
`DeckChangeScope.CARD_ONLY` claim is confirmed.

## 5. PC backend (CONTRACT-17)

- No PC note-mutation message exists in `docs/PROTOCOL.md` or `server/mock_pc_agent.py`. The
  mock's full message set (E6): hello/auth/ping, start/submit/rate, session snapshot/status,
  dashboard, decks, study config, component health, TTS. **Nothing edits notes, fields, tags or
  decks.**
- No `PcAnkiBackend` or equivalent class exists under `app/src/main`. `AnkiConnect` appears only in
  `AnkiAvailability.kt` and `server/test_contract.py` as text, with no mutation code.
- The real PC agent is not in this repository. Its behaviour cannot be established from this
  repo, so no PC semantic may be claimed.

**PC result: UNSUPPORTED on this branch.** Every PC capability must stay disabled. This is the
path the gate allows ("disable that capability rather than weaken the transaction model").

## 6. Atomicity (CONTRACT-04)

| Backend | Classification | Evidence |
|---|---|---|
| AnkiDroid — fields + tags | **ATOMIC_SINGLE_OPERATION** (one `updateNote` transaction at rslib). Caveat: the provider-side read-modify-write is not guarded. | E1 L495–535, E4 L393 |
| AnkiDroid — fields + tags + deck | **NON_ATOMIC_MULTI_OPERATION.** Deck is a separate `updateCard` call on another URI, with its own transaction and no shared transaction with `updateNote`. | E1 L540–562 |
| PC | **UNKNOWN / unsupported** (no contract) | §5 |

## 7. Mutation boundary and confirmed non-application (CONTRACT-05, -07)

Boundary (AnkiDroid):

```
validation (Study-Agent)
↓
re-read card details (getCardDetails)          ← still PREPARED, no effect
↓
PREPARED durable → SUBMITTING durable
======== MUTATION BOUNDARY ========  ContentResolver.update(...)  [after this point the call may apply]
↓
provider: SecurityException guard → require/IAE checks → col.updateNote (one rslib tx) → return count
```

Confirmed non-application, by exception class at the provider:

| Source | Class | Proven non-application? |
|---|---|---|
| `hasReadWritePermission()` false → `throwSecurityException` (E1 L484–485) | `SecurityException` | **Yes.** Thrown before any collection access. |
| Unknown key / field-count mismatch / unsupported column / `getCard` missing ord / filtered deck (E1 `require`s and `throw`s before the write) | `IllegalArgumentException` | **Only when thrown by the provider's own checks.** The same class can come from `col.updateNote`, which the source does not exclude (E1 has no try/catch around it). |
| Provider process died | `ContentResolver.update` returns `-1` | **No.** Unknown. |
| Any other throwable | — | **No.** |

**Finding R1 (blocking):** `AnkiDroidNoteMutationMapper.classify` maps every
`IllegalArgumentException` to `ConfirmedNotApplied`, which enables `RETRY_ALLOWED`. The
provider's own IAEs are pre-write, but the same class can escape from `col.updateNote` after the
rslib transaction started. Nothing in the source distinguishes the two. Under CONTRACT-07 ("do not
classify generic exceptions as safe-to-retry"), this classification is **not proven**. The
conservative correction is to map IAE to `OutcomeUnknown` (no retry). This is a change to
production code, so it is deferred until the contract is accepted.

## 8. Ambiguous outcome windows (CONTRACT-08)

| Window | AnkiDroid |
|---|---|
| Between `ContentResolver.update` dispatch and the reply | Yes. `updateNote` may have committed; the reply is lost. The provider process may die mid-call (`-1`). |
| Provider throws from `updateNote` after commit | Possible in principle. Not excluded by source (R1). |
| Coroutine cancelled while the binder call is in flight | Yes. The gateway runs the call in a `NonCancellable` scope with a timeout that does not cancel it. The timeout returns "unknown" (correct). |
| Process death after SUBMITTING durable | Yes. Recovery turns SUBMITTING into AMBIGUOUS (`DefaultNoteMutationLedger` restart rule). |

## 9. Idempotency (CONTRACT-09)

| Operation | Classification | Evidence |
|---|---|---|
| UpdateFields with identical payload | **EFFECTIVELY_IDEMPOTENT_BUT_NOT_CONTRACTUAL** | E4 L432–434: `update_note_inner` returns early when the note does not differ. The second call is a no-op, but the provider still returns the same count, so the caller cannot tell "applied now" from "already applied". |
| ReplaceTags with identical set | same as above | same |
| AddTags / RemoveTags | UNSUPPORTED (no delta op) | E1 |
| ChangeDeck to the same deck | **EFFECTIVELY_IDEMPOTENT_BUT_NOT_CONTRACTUAL** (the code sets `did` and calls `updateCard` again; a no-op write is not proven) | E1 L540–562 |
| Bury / suspend twice | **PROVEN_IDEMPOTENT** at the rslib level: `if card.queue != desired_queue` (E5 L104) | E5 |

Only `PROVEN_IDEMPOTENT` permits replay. For note fields, tags and deck the answer is **no replay**
under the current evidence. The existing `idempotentReplay = false` is correct.

## 10. Idempotency keys, conflict detection, reconciliation (CONTRACT-10, -11, -12)

- **Backend idempotency key:** none. `NoteMutationId` is local only. The provider has no
  `mutationId` column (E3 has none for `Note`). This is stated explicitly, as required.
- **Conflict detection:** **NO_CONFLICT_DETECTION** on the provider. No revision, `mod`, `usn`
  or expected-value check is in the `update` branch. The only protection is the Study-Agent
  pre-write re-read, which is **BEST_EFFORT_PRE_SAVE_COMPARE**. A change between the re-read and
  the write is not excluded. `csum`, `mod` and `usn` must not be treated as tokens.
- **Reconciliation:** **NO_RECONCILIATION.** No lookup-by-transaction, no receipt. `mod` cannot
  attribute a change to a transaction. Current state == desired state is not proof, since another
  editor could have produced it (CONTRACT-12).

## 11. Identity requirements (CONTRACT-13)

| Operation | Required identity |
|---|---|
| Update fields | `backendId` + `collectionKey` (if known) + `noteId`. Positional field ordinals verified against the current model's field count. |
| Update tags | `backendId` + `noteId` |
| Change deck | `backendId` + `noteId` + `cardOrd` (the provider addresses the card by `(noteId, ord)`, never by card ID) + target `deckId` (numeric, non-filtered) |

## 12. Model and field schema (CONTRACT-14)

The provider cannot detect deleted, renamed or reordered fields, or a changed model. It checks only
the **count** (E1 L511). `NoteFieldRef` should therefore carry **ordinal + noteTypeId**, verified
against a fresh read before every write. Names are informational only, and must not be used as
write keys.

## 13. Post-write read (CONTRACT-15)

- `updateNote` returns no note payload. The post-write read is required.
- Correct read: `getCardDetails(cardRef)` (existing `notes/<id>` + `models/<id>` + `cards/<id>`
  reads, GATE 16). It returns fields, tags, and card deck.
- **Affected sibling cards:** the field write can create sibling cards (§2 side effect). The
  refresh must list the note's cards (`notes/<id>/cards`, which the provider does not implement
  for update, but the query path must be confirmed; UNKNOWN for query support on that URI in this
  pass). Until confirmed, the UI must say "other cards of this note may have been created".

## 14. Active Study interaction (CONTRACT-28)

Identity matching is possible. `AnkiCardRef.noteId` is available for review turns from the
review-info `note_id` column (AnkiDroidApiContract `REVIEW_NOTE_ID_COLUMN`), so
`activeTurn.card → noteId → requested noteId` is decidable. The existing
`StudyActivityNoteEditSafetyPolicy` implements this and fails closed on unknown identity. The
Study rule is therefore **locked**: an unresolved Study turn, rating commit, or reviewer action
for the same note blocks the edit before PREPARED and again before the boundary.

## 15. Error taxonomy (CONTRACT-29)

Existing mapping, reviewed:

| Failure | Category | Note |
|---|---|---|
| `SecurityException` | `PermissionRequired` | proven non-application |
| provider `require` / IAE | `ValidationFailure` / `InvalidRequest` | proven only when pre-write (R1) |
| `getCard` missing ord | `TargetNotFound` | IAE — same R1 caveat |
| missing note | `TargetNotFound` (`col.getNote` throws `BackendNotFoundException`) | not a GATE 17 path; classifies as Unknown → OutcomeUnknown |
| filtered deck target | `ValidationFailure` | pre-write |
| `-1` return | `OutcomeUnknown` | correct |
| row count mismatch | `OutcomeUnknown` | correct |
| other throwable | `OutcomeUnknown` | correct |
| `Conflict` | not produced by AnkiDroid | pre-write re-read only |

## 16. Capability differences

| Capability | AnkiDroid v2.24.1 | PC |
|---|---|---|
| `editNoteFields` | supported (one `notes/<id>` update) | unsupported |
| `editNoteTags` | supported, replace-only | unsupported |
| `changeCardDeck` | supported, card-only | unsupported |
| `authoritativeMutationReconciliation` | unsupported | unsupported |
| conflict guarantee | best-effort pre-save re-read | unsupported |

## 17. Contract matrix (CONTRACT-18)

| Property | AnkiDroid | PC Agent |
|---|---|---|
| Update fields | SUPPORTED — `notes/<id>` `flds`, full positional overwrite (E1 L495–535) | UNSUPPORTED — no message type (E6) |
| Field identity | SUPPORTED — ordinal only; count-checked; names not checked (E1 L511) | UNSUPPORTED |
| Replace tags | SUPPORTED — `notes/<id>` `tags`, replace (E1) | UNSUPPORTED |
| Add/remove tags | UNSUPPORTED — no delta op (E1) | UNSUPPORTED |
| Deck mutation scope | SUPPORTED — card only (E1 L540–562) | UNSUPPORTED |
| Atomic fields+tags | SUPPORTED — one `updateNote` tx (E4 L393) | UNSUPPORTED |
| Atomic fields+tags+deck | UNSUPPORTED — deck is a separate `updateCard` call (E1) | UNSUPPORTED |
| Mutation boundary | SUPPORTED — after `ContentResolver.update` dispatch (§7) | UNKNOWN — no contract |
| Confirmed success evidence | SUPPORTED-WEAK — row count = keys, proves completion of the provider path, not stored value (E1) | UNKNOWN |
| Confirmed non-application | SUPPORTED-PARTIAL — `SecurityException` only; IAE unproven (R1) | UNKNOWN |
| Ambiguous outcome possible | SUPPORTED — yes (binder loss, `-1`, process death) | UNKNOWN |
| Idempotency | UNSUPPORTED as contract — effectively idempotent, not contractual (§9) | UNKNOWN |
| Backend idempotency key | UNSUPPORTED — none in E3 | UNKNOWN |
| Conflict detection | UNSUPPORTED — none; best-effort pre-save re-read only | UNKNOWN |
| Reconciliation | UNSUPPORTED — no transaction correlation | UNKNOWN |
| Authoritative post-write read | SUPPORTED — `getCardDetails` (GATE 16 reads); sibling-card query UNKNOWN | UNKNOWN |

Every cell is classified. "UNKNOWN" marks a cell that cannot be resolved from this repository.
Those cells are on the PC side, where the answer is simply that no contract exists here.

## 18. Normalized contract (CONTRACT-19 to -23)

**CONTRACT-20 — single method decision.** A single `applyNoteMutation(request)` is valid only if
every operation shares one classification. It does not: the deck move is a separate, non-atomic
call, and it has its own refusal set. **Decision: keep separate operations** (as the existing
`NoteMutationStep.UpdateNoteContent` and `ChangeDeck` do). The current single-entry
`AnkiBackend.applyNoteMutation(request)` is acceptable only as a dispatcher over these two steps.

**CONTRACT-21 — operation set:** `UpdateNoteContent(fields?, tags?)` (one atomic step) and
`ChangeCardDeck(card, toDeck)` (one non-atomic step). No `AddTags` or `RemoveTags`, since the
provider has no delta semantics and the mapping would be an approximation.

**CONTRACT-22 — result set:** `ConfirmedApplied`, `ConfirmedNotApplied(reason)` (only
`SecurityException`, and IAE once R1 is resolved), `Conflict` (AnkiDroid cannot produce it at
the provider; kept for the Study-Agent pre-write re-read), `OutcomeUnknown(reason)`. Nothing else.

**CONTRACT-23 — semantics metadata:** the existing `NoteMutationSemantics` is correct in
direction. The `contentWriteAtomic` flag can become `true` for AnkiDroid on the rslib evidence in
E4, but the provider-side read-modify-write is not guarded, so the claim must read "single
transaction for the write, no concurrency guarantee". It stays `false` until device verification.

## 19. Multi-step save policy (CONTRACT-24)

**Case B.** Save = content step, then (if present) deck step.

- Order: content → deck (the existing order). A deck failure cannot hide an applied content
  write, because content is recorded first.
- Partial success: content applied, deck not applied → the whole save is **not** retryable.
  The record must become AMBIGUOUS or a terminal partial state, and the UI must show what applied.
  The existing `PARTIAL_OPERATION_UNKNOWN` logic is the right direction.
- Failure of deck when content is `ConfirmedApplied`: the outcome is partial, not "failed".

## 20. Retry policy (CONTRACT-25)

| Operation | Class |
|---|---|
| Content step, `SecurityException` before dispatch | SAFE_RETRY_AFTER_PROVEN_NON_APPLICATION |
| Content step, IAE | UNKNOWN until R1 resolved → NO_AUTOMATIC_RETRY |
| Content step, `-1` / exception / count mismatch | NO_AUTOMATIC_RETRY |
| Deck step, `SecurityException` | SAFE_RETRY_AFTER_PROVEN_NON_APPLICATION (only if the content step was not entered) |
| Deck step, other | NO_AUTOMATIC_RETRY |
| Any step, idempotent replay | not available (§9) |

## 21. Crash-recovery policy (CONTRACT-26)

| Window | Durable state | Could backend effect have occurred? | Reconcilable? | Replayable? | Block user? |
|---|---|---|---|---|---|
| before durable intent | none | no | — | — | no |
| after PREPARED, before boundary | PREPARED | no | — | via re-read | no |
| after SUBMITTING, before dispatch | SUBMITTING | no, but unknown | no | **no** | yes (AMBIGUOUS) |
| after dispatch, before result | SUBMITTING | yes | **no** | **no** | yes |
| after success, before APPLIED persisted | SUBMITTING | yes | **no** (no receipt) | **no** | yes |
| after durable APPLIED, before refresh | APPLIED | yes (applied) | — | — | no; refresh only |

The existing restart rule (SUBMITTING → AMBIGUOUS; PREPARED/RETRY_ALLOWED → CONFLICT) matches
this table. Because reconciliation is impossible, AMBIGUOUS records **must** block further edits to
that note until a human verifies it. There is no safe automatic resolution.

## 22. External modification (CONTRACT-27)

| Source | AnkiDroid | PC |
|---|---|---|
| Anki desktop UI, sync | best-effort detection via the pre-write re-read only | UNKNOWN |
| Another Study-Agent instance | ledger is per process; detection via re-read | UNKNOWN |
| Plugin / other provider client | best-effort, same as above | UNKNOWN |

**Locked:** `best-effort detection` for AnkiDroid; `no detection` for anything else. No strong
prevention is claimed.

## 23. Unsupported semantics (explicit)

- Add/remove tag deltas.
- Field edit by name; field rename; note-type or template changes.
- Note-wide deck move (provider is card-only).
- Backend idempotency keys and replay of any note write.
- Atomic compare-and-set on note content.
- Transaction reconciliation of any note write.
- Any PC note mutation.
- Creating notes, cards or note types (GATE 18).

## 24. Residual uncertainties

- **R1 (blocking):** IAE classification as `ConfirmedNotApplied` (§7). Fix = map IAE to
  `OutcomeUnknown`. This removes retry for IAE, which is the only safe result.
- **R2:** the row count is accepted as "applied". It proves the provider path completed, not that
  the stored value equals the payload. Mitigation: a post-write read compared to the payload
  before reporting APPLIED.
- **R3:** `setTagsFromStr` / `canonify_tags` side effects and case/hierarchy handling (libanki not
  available).
- **R4:** new sibling cards created by a field edit: their deck and whether the post-write read
  lists them (§13).
- **R5:** `col.updateNote` can throw an rslib error class whose hierarchy is not verified here.
- **R6:** no device or Gradle verification of any provider behaviour in this pass. Evidence is
  source reading plus the JVM harness.
- **R7:** the PC agent is outside this repository. Its contract is unknown and is treated as
  unsupported.

## 25. Verification performed in this pass

- Existing GATE 17 JVM tests run through the repository's Gradle-free harness
  (`tools/jvm-harness`, filter `NoteMutation|NoteEdit|Gate17|AnkiDroidNoteMutation`):
  **113 passed, 0 failed.** This is equivalence evidence, not Gradle CI (see harness README).
- No new tests were added. None are justified until the contract is locked.

## 26. Contract lock checklist

| Item | Status |
|---|---|
| exact AnkiDroid field write API known | [x] `notes/<id>` `flds` (E1) |
| exact PC field write API known | [x] **none exists** — UNSUPPORTED |
| tag semantics known | [x] replace-only; case/canonify partly UNKNOWN (R3) |
| deck mutation scope known | [x] card-only |
| mutation boundaries known | [x] |
| atomicity known | [x] per §6 |
| backend success evidence known | [x] weak (R2) |
| safe non-application evidence known | [ ] **IAE unproven (R1)** |
| ambiguous outcome windows known | [x] |
| idempotency known | [x] |
| idempotency-key support known | [x] none |
| conflict capabilities known | [x] none; best-effort only |
| reconciliation capabilities known | [x] none |
| field identity semantics known | [x] ordinal + count only |
| post-write read path known | [~] `getCardDetails` yes; sibling listing UNKNOWN (R4) |
| capability differences known | [x] |
| normalized operation model justified | [x] |
| normalized result model justified | [~] pending R1 |
| safe retry policy justified | [ ] **depends on R1** |
| crash-recovery policy justified | [x] |

## 27. Gate result

```
GATE 17 — BACKEND CONTRACT RESOLUTION

RESULT: BLOCKED

ANKIDROID
- field mutation:   notes/<id> flds, positional, count-checked, one updateNote tx
- tag mutation:     notes/<id> tags, replace-only; canonify/case UNKNOWN
- deck mutation:    notes/<id>/cards/<ord> deck_id, card-only, separate tx
- atomicity:        fields+tags atomic (single tx); +deck NOT atomic
- idempotency:      effectively idempotent, not contractual → no replay
- mutation boundary: after ContentResolver.update dispatch
- success proof:    row count (weak: proves completion, not stored value)
- non-application:  SecurityException only; IAE NOT proven (R1)
- conflict support: none (best-effort pre-save re-read)
- reconciliation:   none

PC BACKEND
- field mutation:   UNSUPPORTED (no contract in repo)
- tag mutation:     UNSUPPORTED
- deck mutation:    UNSUPPORTED
- atomicity:        UNSUPPORTED
- idempotency:      UNKNOWN
- mutation boundary: UNKNOWN
- success proof:    UNKNOWN
- non-application:  UNKNOWN
- conflict support: UNSUPPORTED
- reconciliation:   UNSUPPORTED

NORMALIZED CONTRACT
- operations:  UpdateNoteContent(fields?, tags?) ; ChangeCardDeck(card, toDeck)
- identities:  noteId (+ ordinal for fields; noteId + cardOrd for deck)
- result types: ConfirmedApplied / ConfirmedNotApplied(SecurityException only) / Conflict(pre-write) / OutcomeUnknown
- capabilities: editNoteFields, editNoteTags, changeCardDeck (AnkiDroid only)
- retry policy: no automatic retry except SecurityException before dispatch
- recovery policy: AMBIGUOUS blocks the note until a human verifies

UNSUPPORTED SEMANTICS:
- see §23

UNCERTAINTIES:
- R1 (blocking), R2, R3, R4, R5, R6, R7

IMPLEMENTATION AUTHORIZED: NO
```

## 28. What must happen to unblock

1. **Decide R1.** Accept the conservative mapping (IAE → `OutcomeUnknown`, no retry). This is a
   one-line change in `AnkiDroidNoteMutationMapper.classify` plus a test that pins it. It is the
   only change the evidence requires before lock. It is deferred here because PART 0 forbids
   production changes.
2. **Record R2 as a design rule:** APPLIED requires a post-write read that matches the payload,
   not just a row count.
3. **Confirm R3, R4 and R5 on a device or with libanki source**, or accept them as residual risks
   with the UI wording in §19 and §13.
4. **PC:** either keep it disabled (current state), or design a PC protocol contract first. No PC
   code may be written from this report.

Until then the existing GATE 17 code on this branch stays under audit. Nothing further is to be
built on it, and GATE 17 is **not locked**.
