# GATE 21L — Custom Study & Filtered Session Backend Ownership (Authoritative Gate Report)

**Verdict:** `BLOCKED` — `IMPLEMENTATION AUTHORIZED: NO`
**Supersedes:** `docs/GATE_21L_BLOCKED_REPORT.md` (kept unchanged as the earlier record of the same verdict)
**Repository HEAD:** `42336ad05caaf1ce709e8d5030fc7968ba1938d2` (branch `arena/5b5399ab-study-agent-client`)
**Type of this gate run:** prerequisite validation → source inventory → independent upstream capability re-verification → executed regression baseline → blocker register → gate lock decision. **No production code was written**, because the gate's own prerequisite rule forbids it (§14, and the prompt's §4/§5: *"If GATE 20 is not implemented, do not recreate its complete functionality under 21L"*).
**Next authorized sub-gate:** `NONE` — return to **GATE 20**. GATE 21M must not start from this report (§13).

---

## 1. Initial report (required header)

```text
REPOSITORY HEAD:    42336ad05caaf1ce709e8d5030fc7968ba1938d2
BRANCH:             arena/5b5399ab-study-agent-client (from master @ 42336ad)
WORKTREE STATUS:    clean at the start of the gate; this gate adds docs + one tool (see §7)

GATE 20:            DESIGN_ONLY / BLOCKED   (docs/GATE_20_CUSTOM_STUDY_CONTRACT.md:3, :531)
GATE 21A:           LOCKED                  (docs/GATE_21A_DISCOVERY.md)
GATE 21B:           LOCKED                  (docs/GATE_21B_CONTRACT_LOCK.md, 21L entry :1605-1613)
GATE 21C:           LOCKED                  (docs/GATE_21C_IDENTITY_IMPLEMENTATION.md)
GATE 21D:           LOCKED                  (docs/GATE_21D_REGISTRY_IMPLEMENTATION.md)
GATE 21E:           LOCKED                  (docs/GATE_21E_RESOLVER_IMPLEMENTATION.md)
GATE 21F:           LOCKED                  (docs/GATE_21F_READ_CONTEXT_ISOLATION.md)
GATE 21G:           LOCKED                  (docs/GATE_21G_VERIFICATION_REPORT.md)
GATE 21H:           LOCKED                  (docs/GATE_21H_VERIFICATION_REPORT.md)
GATE 21I:           LOCKED                  (docs/GATE_21I_VERIFICATION_REPORT.md)
GATE 21J:           NOT LOCKED              (implemented + harness-verified; "pending audit approval of
                                            DECISION-21J-03", docs/GATE_21J_NOTE_EDIT_BINDING.md:21)
GATE 21K:           NOT LOCKED              (no gate report; creation route still seeded from the
                                            AnkiDroid instance — AppNavHost.kt:121, :381)

CUSTOM STUDY COORDINATOR:      MISSING
FILTERED SESSION BACKEND API:  MISSING   (AnkiBackend has no filtered-session / custom-study operation)
CUSTOM STUDY PERSISTENCE:      MISSING
FILTERED SESSION IDENTITY:     UNKNOWN   (no verified backend identifier exists in this repo)
LIFECYCLE MUTATION RECOVERY:   UNKNOWN   (no durable creation/rebuild/cleanup ledger exists or is designed)
```

**Gate condition met:** GATE 20 is not implemented and GATE 21B's own 21L entry calls GATE 20 a *hard blocker*
(`docs/GATE_21B_CONTRACT_LOCK.md:1613`), and `docs/BACKEND_OWNERSHIP_INVARIANTS.md:382` (LOCKED) states:
*"21L **must not** implement custom-study backend binding before GATE 20 lands. 21B authorizes no work here
beyond preserving the prohibition."* GATE 21A:965-969 says the same (*"B12 custom study must be marked BLOCKED
until GATE 20"*).

```text
GATE 21L: BLOCKED

IMPLEMENTATION AUTHORIZED: NO
```

---

## 2. Method and evidence sources

| Step | What was done | Where the evidence lives |
|---|---|---|
| 1 | Read every GATE 20 / 21 artifact in `docs/` and the locked invariants. | `docs/GATE_20_CUSTOM_STUDY_CONTRACT.md`, `docs/GATE_21A_DISCOVERY.md`, `docs/GATE_21B_CONTRACT_LOCK.md`, `docs/BACKEND_OWNERSHIP_INVARIANTS.md` |
| 2 | Searched the source tree for every symbol GATE 20 would have introduced, and for the lifecycle operations the prompt names (§3). | `app/src/main`, `app/src/test`, `app/src/androidTest` — grep results in §4 |
| 3 | Re-derived the backend capability rows from the **pinned upstream sources** instead of from a document. | `ankitects/anki` @ tag `25.09.2` (`3890e12`), `ankidroid/Anki-Android` @ tag `v2.24.1` (`9f579c10bb151146728220729c510acbbd8faba7`), `FooSoft/anki-connect` @ default branch (`4064fa14…`, **unpinned**) |
| 4 | Executed the repository's Gradle-free JVM harness as a regression baseline, because Gradle itself is environmentally blocked here. | §10 (commands, exit codes, results) |
| 5 | Classified every checkpoint, audit and verification row against the result of steps 2–4. | §6, §8, §9 |

Step 3 is reproducible: **`tools/check-capability-pins.sh`** clones the pinned sources into a temp directory and
re-checks all 13 capability markers (exit 0 = the matrix in §5 still holds). Source evidence is not behavioural
evidence; it says what the upstream code *says*, not that a filtered deck was ever created.

---

## 3. Custom Study architecture as it exists today

### 3.1 Actual components (CHECKPOINT 21L-00)

| Required component (prompt naming) | Actual state at HEAD | Evidence |
|---|---|---|
| `CustomStudyRequest` / `CustomStudyConfig` | **absent** | no match for `CustomStudy*` in `app/src/main` |
| `CustomStudyCoordinator` | **absent** | no match |
| `FilteredSessionRef` / `FilteredDeckRef` / `AnkiFilteredSessionRef` | **absent** | no match |
| Custom Study persistence | **absent** | no store, no record, no schema |
| Custom Study tests | **absent** | `tools/jvm-harness/bin/run.sh 'CustomStudy\|Filtered\|FilteredDeck'` → `classes=0 tests=0` (the only sources using the words test filtered-deck *awareness*: `AnkiDroidCommitClassificationTest.kt:66,84,97,110`, `Gate21jNoteEditOwnershipTest.kt:321`, `EditNoteViewModelTest.kt:359`) |
| `StudyMode.CUSTOM_SESSION` | present, **wire enum only** | `core/models/StudyControlModels.kt:37`; consumed by control-centre/dashboard plumbing (`ui/screens/control/ControlCenterScreen.kt:207`, `ui/screens/home/HomeViewModel.kt:279,284,299`) and by no scheduler code |
| `AnkiBackend` filtered-session operation | **absent** | `core/anki/AnkiBackend.kt` — decks, selected deck, deck summary, browse, begin/next review, hydrate, commit rating, reviewer actions, note mutation, media, note creation. **No** create/rebuild/empty/delete/queue operation |
| Filtered-deck *awareness* (not ownership) | present | `core/anki/AnkiLibrary.kt:52` (`filteredDeckCount`), `core/anki/AnkiCardHydration.kt:103-104`, `data/anki/ankidroid/AnkiDroidCommitEvidence.kt:55,172,174`, `AnkiDroidCommitResultClassifier.kt:61,68`, `AnkiDroidRatingCommitter.kt:205` |
| Lifecycle ops (`RebuildFilteredDeck`, `EmptyFilteredDeck`, `AddOrUpdateFilteredDeck`, `GetQueuedCards`) | **absent** | zero matches in `app/src` (main, test, androidTest) |
| Capability flags for Custom Study | **absent** | `core/anki/AnkiCapabilities.kt:92-222` — 25 Boolean capability flags plus 2 non-Boolean members (`cardBrowser`, `noteEditConflictGuarantee`); none for custom study / filtered decks |

**Ownership seam inventory (21L-00 exit condition).** Operational lifecycle rows are the read-only/awareness
ones above; every *mutating* Custom Study lifecycle operation is unimplemented, so there is no operation to
classify as read-only vs. potentially mutating yet.

### 3.2 Required call graph vs. reality

```text
Open Custom Study            -> ABSENT (no entry point; StudyMode.CUSTOM_SESSION is a control-centre label)
configure study criteria     -> ABSENT (no request type)
select source deck(s)        -> PARTIAL: AnkiDeckRef exists and is backend-qualified, but no Custom Study consumer
resolve backend              -> EXITS: GATE 21E resolver exists (AnkiBackendResolver2, BackendPreference)
validate capability          -> ABSENT (no custom-study capability is declared on any backend)
create filtered session/deck -> ABSENT (no backend operation; no lifecycle owner)
bind StudySession            -> EXITS: GATE 21G lock exists (StudySessionBackendLock) but nothing binds it for a filtered session
request next card            -> EXISTS ONLY for normal sessions (AnkiBackend review path); no filtered context
rate / reviewer actions      -> EXISTS for normal sessions (GATE 11 / GATE 13), original-backend-bound
rebuild where supported      -> ABSENT
complete / end               -> ABSENT for filtered resources; normal session end exists (GATE 10)
restore after restart        -> ABSENT for filtered resources; session-lock restore exists (GATE 21G)
```

Conceptually the prompt's chain therefore collapses at the third box. Nothing after *"select exact BackendId"*
can be implemented without inventing GATE 20's contract, its identity path, and its lifecycle ledger.

### 3.3 Identity contract (what exists / what is missing)

| Field the prompt requires | State today | Evidence |
|---|---|---|
| `BackendId` | **EXISTS, locked (21C)** — `AnkiBackendId` sealed, `@Serializable`, `stableId` includes the PC profile discriminator | `core/anki/AnkiBackendId.kt:8-40` |
| Collection context | **PARTIAL** — `AnkiCollectionIdentity(backendId, collectionKey?)`; AnkiDroid always `null` (fails open at the session check) | `data/anki/ankidroid/AnkiDroidDeckMapper.kt:93,114`; `AnkiDroidBackend.kt:1385-1389` (fail-open at :1389; call sites :1274, :1339); GATE 21A:949-951 |
| Qualified source `DeckRef` | **EXISTS** — `AnkiDeckRef(backendId, deckId, collectionKey?)` | `core/anki/AnkiRefs.kt`, used by GATE 14/15/21J |
| `StudySessionId` | **EXISTS** — `StudySessionBackendLock.studySessionId` | `core/anki/StudySessionBackendLock.kt:33-45` |
| Backend filtered-resource reference | **MISSING** — no type, no persistence, no verification path | grep evidence §3.1 |
| Context generation / session generation | **EXISTS inside families** (ReviewCommit/ReviewerAction/NoteMutation records carry backend id + turn identity) but **nothing for filtered resources** | `ReviewCommit.kt:272`, `ReviewerActionRecord.kt:110` |
| Identity assurance | **EXISTS (21C)** — `AnkiIdentityAssurance` / `AnkiUnverifiabilityQualifier` | `core/anki/AnkiIdentityAssurance.kt`, `AnkiIdentityContinuation.kt:210` |

`StudySessionId ≠ BackendFilteredSessionId` (prompt §2.5, INV-21L-08) is honoured **vacuously**: the second half of
the pair does not exist yet. That is not a pass; it is an unimplemented distinction (VER-21L-09).

### 3.4 Lifecycle state machine (existing states only — nothing invented)

| Conceptual state (prompt §PART VII-5) | Actual state at HEAD |
|---|---|
| Configuration | none — no Custom Study configuration object exists |
| Binding | none for Custom Study. GATE 21G binding exists for normal sessions (`StudySessionBackendLock`, `StudySessionLockStore`, admission/continuation/restore sealed results) |
| Creating | none |
| Ready | none for a filtered resource |
| Active study | normal-session states only (GATE 10 session machine) |
| Rebuild pending | none |
| Completed | none for a filtered resource |
| Blocked / unavailable | backend-level only (`AnkiBackendUnavailableReason`, `AnkiBackendStatusSnapshot`) — not session-level |
| Ending / Terminated | normal session end (`endReview`, lock release per `BACKEND_OWNERSHIP_INVARIANTS.md:110`) |

GATE 20 records the only backend-real filtered states as *deck exists with membership / emptied / removed*, and
explicitly refuses to persist ACTIVE/COMPLETED/ENDED/INVALIDATED (`GATE_20_CUSTOM_STUDY_CONTRACT.md:563-566`).

### 3.5 Scheduler ownership matrix (contract that is locked vs. code that exists)

| Operation | Backend source of truth (locked contract) | AUTO permitted? | Implemented today? |
|---|---|---|---|
| New Custom Study | GATE 21E resolver | before binding only | **no** — no Custom Study entry point |
| Create filtered resource | bound GATE 20 context | **no** | **no** — no backend operation |
| Get next card | locked StudySession | **no** | **no** for filtered; yes for normal sessions |
| Rating | GATE 11 `ReviewCommitCoordinator` | **no** | **no** for filtered context; yes for normal sessions |
| Reviewer action | GATE 13 `ReviewerActionCoordinator` | **no** | **no** for filtered context; yes for normal sessions |
| Rebuild | original filtered-resource owner | **no** | **no** |
| Cleanup | original filtered-resource owner | **no** | **no** (and the empty-vs-remove choice is undecided, B5) |
| Restore | persisted original context | **no** | **no** for filtered resources; yes for session locks |

### 3.6 Lifecycle mutation safety

| Property the prompt requires | State today |
|---|---|
| Creation durability | **none** — no ledger, no record type; GATE 20 DECISION-20-11 (durable creation ledger) is *indicated but not designed* |
| Rebuild durability | **none** |
| Cleanup durability | **none** |
| Partial results | not modelled (no operation exists) |
| Lost-response behaviour | not modelled |
| Retry eligibility | GATE 20 records intent only: preset creation `RETRY_ONLY_AFTER_PROVEN_NON_CREATION`; raw `AddOrUpdateFilteredDeck(id=0)` `NO_BLIND_RETRY` (`GATE_20_CUSTOM_STUDY_CONTRACT.md:567-573`) |
| Reconciliation support | **none** for filtered resources. GATE 11/GATE 13/GATE 17 families have their own reconciliation; the prompt forbids reusing them for lifecycle mutations (§2.7) |
| Concurrent operation barriers | **none** for filtered resources. Existing barriers for rating/actions are unchanged and were re-verified green (§10) |

**No exactly-once claim is made or implied anywhere in this report.** Upstream source confirms the mutations are
RPC calls with no idempotency key (`scheduler.proto:32-33`, `decks.proto:35`), so INV-21L-21 ("do not invent
backend-native idempotency") holds by construction: there was nothing to invent because nothing was implemented.

### 3.7 Restoration report

| Restoration scenario (prompt §PART VII-8) | State today |
|---|---|
| Original backend available | **not applicable** — no persisted Custom Study record to restore |
| Original backend unavailable | **not applicable** — same |
| Collection mismatch | **cross-gate gap remains open (21C/21B B10):** AnkiDroid produces no collection key and the session check fails open (`AnkiDroidBackend.kt:1389`), so "verified continuity" is not obtainable on AnkiDroid today |
| Unknown collection identity | same as above — *UNKNOWN is not verified continuity* (INV-21L-25) but the current code path fails open; 21L must not build on that |
| Filtered resource removed | **not detectable** — no filtered-resource reference exists |
| Pending rating | handled by GATE 11 for normal sessions (records survive termination per INV-21L-27); no filtered variant |
| Pending lifecycle mutation | **not modelled** |
| Legacy missing identity | **not applicable** — no legacy Custom Study records exist to protect (`grep` over stores/schemas shows none) |

---

## 4. Source inventory evidence (CHECKPOINT 21L-00)

```text
$ grep -rn "CustomStudy|FilteredDeck|FilteredSession|filteredDeck|filtered_session" app/src/main --include=*.kt
  -> 8 matches, all filtered-deck *awareness* in commit/rating/library code (listed in §3.1). 0 lifecycle types.

$ grep -rn "RebuildFilteredDeck|EmptyFilteredDeck|AddOrUpdateFilteredDeck|GetQueuedCards" app/src --include=*.kt
  -> 0 matches

$ grep -rn "CUSTOM_SESSION|StudyMode" app/src/main --include=*.kt
  -> enum + control-centre/dashboard plumbing only; no scheduler consumer

$ tools/jvm-harness/bin/run.sh 'CustomStudy|Filtered|FilteredDeck'
  -> RESULT classes=0 tests=0 passed=0 failed=0
```

Test sources that mention the words (for completeness): `AnkiDroidCommitClassificationTest.kt:66,84,97,110`
(`inFilteredDeck` commit-classification flag), `Gate21jNoteEditOwnershipTest.kt:321` (foreign filtered deck with a
colliding id must not block a move — GATE 21J), `EditNoteViewModelTest.kt:359` (filtered deck target refused).
None of these is a Custom Study workflow test.

---

## 5. Backend capability matrix (CHECKPOINT 21L-01 / 21L-02)

Two independent columns, because *public API support* ≠ *application implementation* (prompt 21L-01).

### 5.1 Upstream API support (re-derived from pinned source, `tools/check-capability-pins.sh`)

| Operation | AnkiDroid v2.24.1 public provider | PC (Anki desktop backend 25.09.2) | AnkiConnect (documented PC route) |
|---|---|---|---|
| Create filtered deck / custom study | **UNSUPPORTED** — the contract exposes notes, notes_v2, models, cards, schedule, media and read-only deck listing; no create/rebuild/empty endpoint (`FlashCardsContract.kt:221,228,366,898,1032,1264`) | **SUPPORTED** — `scheduler.proto:46 CustomStudy(CustomStudyRequest)`, `decks.proto:34-36 GetOrCreateFilteredDeck` / `AddOrUpdateFilteredDeck` | **UNSUPPORTED** — 110 `@util.api` actions, none for custom study / filtered deck / queue |
| Filter criteria (deck scope, tags, states, limits) | **UNSUPPORTED to configure**; only readable | **SUPPORTED** — `CustomStudyRequest` oneof (new/review limit delta, forgot/ahead/preview days, `Cram{kind,card_limit,tags_to_include,tags_to_exclude}`), `scheduler.proto:288-317` | **UNSUPPORTED** |
| Ordering | **UNSUPPORTED to configure** | **PARTIAL / backend preset-defined** — order comes from the preset function (`custom_study.rs:218-290`: Due / Added / Random), not a client parameter | **UNSUPPORTED** |
| Limits | **UNSUPPORTED to configure** | **SUPPORTED** — `card_limit` on cram; per-term `limit` on the raw path | **UNSUPPORTED** |
| Next card of a filtered deck | **PARTIAL** — a filtered deck is visible (`Deck.DECK_DYN`, `FlashCardsContract.kt:1219`) and can be *selected* (`Deck.CONTENT_SELECTED_URI`, `:1194`); queue semantics through the provider are the normal schedule read | **SUPPORTED** — `GetQueuedCards` (`scheduler.proto:18`) serves filtered queues | **UNSUPPORTED** for a filtered queue (no queue action) |
| Rating inside a filtered deck | **PARTIAL / device-unverified** — the normal answer path applies; filtered-preview answers are classified Ambiguous in GATE 11 (`ANKIDROID_INTEGRATION.md:1060-1071`) | **SUPPORTED** — normal answering; reschedule flag decides history effects | **PARTIAL** — `answerCards` exists (`@util.api`), no filtered-specific semantics |
| Rebuild | **UNSUPPORTED** | **SUPPORTED** — `RebuildFilteredDeck(DeckId) -> OpChangesWithCount` (`scheduler.proto:33`; `filtered/mod.rs:73`) | **UNSUPPORTED** |
| Empty / end | **UNSUPPORTED** | **SUPPORTED** — `EmptyFilteredDeck(DeckId) -> OpChanges` (`scheduler.proto:32`; `filtered/mod.rs:65`) | **UNSUPPORTED** |
| Delete / remove the deck | **UNSUPPORTED** | **SUPPORTED via the deck service** (`decks.proto:30 RemoveDecks`), but *which* of empty vs. remove the product wants is **undecided (B5)** | **PARTIAL** — `deleteDecks` exists (plain deck deletion; no filtered-deck or queue semantics) |
| Restore an existing filtered session | **PARTIAL** — observable + selectable, no ownership proof | **SUPPORTED at the API level**, subject to this repo having an identity + ledger (B3/B4) | **UNSUPPORTED** |

```text
Classification (implemented capabilities in THIS repository).

AnkiDroid:  create UNSUPPORTED · rebuild UNSUPPORTED · empty UNSUPPORTED · delete UNSUPPORTED
            observe/select an existing filtered deck: SUPPORTED (read-only)
            next card / rating inside it: PARTIAL, device-unverified
PC:         UNKNOWN, END-TO-END. The Anki backend supports everything above; the PC *agent* that
            would carry it is not in this repository. server/mock_pc_agent.py only *simulates* review
            effects in memory (`ANKI_REVIEW_EFFECTS` :159, `apply_simulated_anki_review` :162-165) and
            advertises an "anki" capability entry (:187) - it exposes no filtered-deck or custom-study
            surface - and no PC Anki transport exists.
            Verified at HEAD: the only `PcAnkiBackend` matches in `app/src` are *negative* architecture
            assertions (`AnkiDomainIsolationTest.kt:57`, `Gate21cArchitectureAuditTest.kt:367`,
            `Gate21dArchitectureAuditTest.kt:404,422,426`).
```

### 5.2 Mode contract (21L-02)

GATE 20's proposed modes are **PROPOSED ONLY, not locked** (`GATE_20_CUSTOM_STUDY_CONTRACT.md:533-537`):
`BACKEND_FILTERED_REVIEW` (reschedule=true), `BACKEND_PREVIEW` (reschedule=false), plus the *existing*
`DECK_SCOPED_NORMAL_REVIEW`. The label "Custom Study" therefore proves nothing yet: the mode vocabulary, the
reschedule semantics per backend, and the "no rescheduling" mapping for AnkiDroid (no reschedule flag is exposed)
are all unresolved. Nothing in this repo may advertise a mode (`AnkiCapabilities` declares none, correctly).

---

## 6. Blocker register (what 21L is waiting on, and for whom)

| # | Blocker | Owner | State after this gate run |
|---|---|---|---|
| B1 | AnkiDroid cannot create/rebuild/empty a filtered deck through any public API | GATE 20 (contract) | **re-confirmed independently** from pinned `v2.24.1` source; the only filtered-deck affordances are read-only observation and deck selection |
| B2 | The PC agent is not in this repository; its route to Anki's custom-study RPCs is unknown | GATE 20 + PC agent repo | **unchanged**; new: AnkiConnect (the documented route) exposes **zero** related actions, so the route must be an in-process/plugin decision made outside this repo |
| B3 | Filtered-session identity is undefined for presets | GATE 20 (DECISION-20-08) | **narrowed by evidence:** the raw path *does* return an id (`AddOrUpdateFilteredDeck -> OpChangesWithId`); the preset path *cannot* (`CustomStudy -> OpChanges`, reserved name built from a translation, `custom_study.rs:164`). The decision is therefore "raw path vs. preset", not "is an id obtainable at all" |
| B4 | Creation mutates the collection persistently; the durable creation ledger is indicated but not designed | GATE 20 (DECISION-20-11/12/13) | **unchanged** |
| B5 | End/cleanup policy (empty vs. remove deck) is a product decision | Product owner via GATE 20 (DECISION-20-14) | **unchanged**; new evidence: `EmptyFilteredDeck` and deck removal are *different* RPCs with different results, so they cannot be substituted for each other |
| B6 | No device/disposable-collection evidence for AnkiDroid preview, reschedule flag, restart, sync, external mutation | GATE 20 | **unchanged** — no device exists in this sandbox |
| B7 | Custom Study capability vocabulary does not exist on any backend adapter | GATE 20 (contract) → 21D/21L | **unchanged** (`AnkiCapabilities` has no such flag; adding one without a locked contract would be *advertising an unsupported capability*) |
| B8 | AnkiDroid collection identity is always UNKNOWN and the session check fails open | GATE 21C/21B (B10) | **unchanged** — 21L-22 (collection/profile mismatch) cannot be satisfied on AnkiDroid today and must not be faked |
| B9 | GATE 21J not locked (DECISION-21J-03 pending audit); GATE 21K not locked (creation still seeded from the AnkiDroid instance) | 21J audit / 21K | **unchanged** — recorded because the prompt lists them as prerequisites |

Each blocker maps to a gate, not to 21L. **21L cannot resolve any of them without writing GATE 20's contract**,
which its own scope section forbids (§5 OUT OF SCOPE: *"Entire GATE 20 workflow from scratch"*).

---

## 7. File-by-file report

Every checkpoint is classified below. The gate's own file changes are documentation and evidence tooling only.

### 7.1 Changes made by this gate

| FILE | ACTION | LOCKED CONTRACT REFERENCES | RESPONSIBILITY | IMPLEMENTED | LOCAL CHECKS |
|---|---|---|---|---|---|
| `docs/GATE_21L_REPORT.md` | **CREATE** | prompt §4 prerequisite rule; GATE 21B 21L entry; INV-21L-32/33 | The authoritative GATE 21L record: prerequisite validation, inventory, capability matrix, blocker register, audit/verification/invariant tables, lock decision | full report | `tools/check-capability-pins.sh` exit 0; harness suite green (§10) |
| `tools/check-capability-pins.sh` | **CREATE** | 21L-01 (capability accuracy) | Reproducible source evidence for §5: clones the three pinned upstreams into a temp dir and re-checks 13 markers; read-only, never touches a collection | 13 checks, exit 0 | executed here: `13 hold / 0 disagree / 0 unverified` |
| `docs/GATE_21L_BLOCKED_REPORT.md` | **MODIFY** (header note only) | — | Pointer so the two 21L records are not read as contradictory | 3-line note | n/a |

No production code, test, build file, or capability flag was added, per the gate's own prerequisite rule.

### 7.2 Checkpoint classification (21L-00 … 21L-33)

| Checkpoint | Action | Reason |
|---|---|---|
| 21L-00 architecture inventory | **NO_CHANGE** (completed read-only, §3–§4) | No CustomStudy/FilteredSession lifecycle exists |
| 21L-01 capability discovery | **NO_CHANGE** (completed read-only, §5) | Re-derived from pinned source; no capability is advertised in code, so nothing to change |
| 21L-02 mode contract | **BLOCKED** | GATE 20 DECISION-20-01 is "PROPOSED ONLY" |
| 21L-03 immutable Custom Study context | **BLOCKED** | Needs the locked mode + identity (B3) |
| 21L-04 source deck qualification | **BLOCKED** | Depends on 21L-03; `AnkiDeckRef` exists but has no Custom Study consumer |
| 21L-05 request validation | **BLOCKED** | GATE 20 DECISION-20-03/04/05 proposed only |
| 21L-06 initial backend resolution | **BLOCKED** | The seam exists (GATE 21E `AnkiBackendResolver2`, locked); the request type it would resolve for does not |
| 21L-07 atomic ownership admission | **BLOCKED** | Needs the creation boundary + durable record (B4) |
| 21L-08 filtered resource identity | **BLOCKED** | B3; verified: no identity type, and the preset path provably returns none |
| 21L-09 filtered session creation | **BLOCKED** | B1/B2/B4; `AnkiBackend` has no such operation |
| 21L-10 creation result / partial failure | **BLOCKED** | No ledger (B4) |
| 21L-11 StudySession ownership integration | **BLOCKED** | GATE 21G lock exists and is reusable later, but there is no Custom Study owner to agree with |
| 21L-12 backend-authoritative next card | **BLOCKED** | No filtered-session context; nothing may be substituted locally |
| 21L-13 review information & rating | **BLOCKED** | GATE 11 is locked and untouched; there is no filtered review turn to route |
| 21L-14 reviewer actions | **BLOCKED** | GATE 13 locked and untouched; filtered-deck bury/suspend semantics unverified (GATE 20 DISCOVERY-20-26) |
| 21L-15 rebuild semantics | **BLOCKED** | B2; `RebuildFilteredDeck` is not reachable from this repo |
| 21L-16 rebuild ambiguity | **BLOCKED** | B4 |
| 21L-17 empty / exhausted session | **BLOCKED** | Requires the lifecycle contract; new evidence: "no matching cards" is a backend **error**, not an empty deck (`custom_study.rs:189`) |
| 21L-18 end session semantics | **BLOCKED** | B5 (product decision) |
| 21L-19 cleanup mutation safety | **BLOCKED** | B5/B4 |
| 21L-20 backend loss during Custom Study | **BLOCKED** | No binding to protect; nothing to pause |
| 21L-21 same-backend reconnect | **BLOCKED** | No binding; and B8 means "verified continuity" is unobtainable on AnkiDroid |
| 21L-22 collection/profile mismatch | **BLOCKED** | B8 — cross-gate gap, not 21L's to fix |
| 21L-23 external filtered-resource changes | **BLOCKED** | No filtered-resource reference exists to invalidate |
| 21L-24 process-death restoration | **BLOCKED** | No persisted Custom Study record; session-lock restore exists for normal sessions only |
| 21L-25 lifecycle mutation ownership | **BLOCKED** | B4; the prompt forbids inventing a ledger here |
| 21L-26 legacy compatibility | **NO_CHANGE** (completed read-only) | No legacy Custom Study records exist in any store/schema (`data/anki/DataStore*Store.kt` contain none; the only data-layer hits are commit-evidence awareness fields); nothing to migrate or protect |
| 21L-27 concurrent operation admission | **BLOCKED** | Depends on the above; existing GATE 11/13 barriers verified green (§10) |
| 21L-28 stale callback isolation | **BLOCKED** | No Custom Study callbacks exist |
| 21L-29 read-context independence | **BLOCKED** | Nothing to isolate yet; GATE 21F is locked and unchanged (`data/anki/context/*`) |
| 21L-30 UI/diagnostics projection | **BLOCKED** | Pre-empts GATE 21M; only the pre-existing backend-status surfaces exist |
| 21L-31 deterministic fake backends | **BLOCKED** | Writing a filtered-session fake API would *invent* the unlocked protocol |
| 21L-32 DI integration | **NO_CHANGE** | No coordinator to wire; registry/resolver/DI unchanged |
| 21L-33 regression review | **NO_CHANGE (executed)** | Full harness suite re-run green (§10) |

---

## 8. Architecture audit (AUDIT-21L-01 … 21L-30)

Rule applied: an audit that needs an implementation is `BLOCKED`; an audit that inspects *absence* of a hazard is
reported as "holds for scope" only when this gate can actually verify it, and never as a lock-enabling PASS.

| Audit | Result | Evidence |
|---|---|---|
| 21L-01 Contract conformance | **BLOCKED** | No GATE 20 contract to conform to (Phase B BLOCKED) |
| 21L-02 Single backend ownership | **BLOCKED** | No Custom Study workflow exists |
| 21L-03 Collection identity | **BLOCKED** | No filtered resource; collection key UNKNOWN on AnkiDroid (B8) |
| 21L-04 Exact registry lookup | **BLOCKED** | No lifecycle/scheduler operation to route |
| 21L-05 AUTO isolation | **BLOCKED** | Nothing to reroute; the resolver seam is locked and unchanged |
| 21L-06 Capability accuracy | **HOLDS FOR SCOPE** | No custom-study capability is declared anywhere (`AnkiCapabilities.kt:92-222`), so nothing unsupported is advertised |
| 21L-07 Source deck identity | **BLOCKED** | `AnkiDeckRef` is backend-qualified, but no Custom Study consumer exists |
| 21L-08 Filtered resource identity | **BLOCKED** | `StudySessionId ≠ BackendFilteredSessionId` is vacuously true; the second term is missing |
| 21L-09 StudySession compatibility | **BLOCKED** | No Custom Study owner to compare with the 21G lock |
| 21L-10 Backend scheduler authority | **HOLDS FOR SCOPE** | No local queue/FSRS/ordering was introduced. Verified at HEAD: zero `nextLocalCard`/`dueSort`/local-queue matches; every `fsrs` match is an informational model field or comment with no computation (`AnkiModels.kt:120,134,152,189`, `AnkiCardDetails.kt:34`, `AnkiModels.kt` — "FSRS facts are informational only"); Custom Study/Filtered test classes = 0 |
| 21L-11 Rating integration | **HOLDS FOR SCOPE** | GATE 11 untouched; `ReviewCommit` suite green (112/112) |
| 21L-12 Reviewer action integration | **HOLDS FOR SCOPE** | GATE 13 untouched; `ReviewerAction` suite green (130/130) |
| 21L-13 Creation ambiguity | **HOLDS FOR SCOPE** | No creation path exists, so no blind replay is possible |
| 21L-14 Rebuild safety | **BLOCKED** | No rebuild path |
| 21L-15 End/cleanup semantics | **BLOCKED** | No end/cleanup path; B5 undecided |
| 21L-16 No destructive cleanup guessing | **HOLDS FOR SCOPE** | No delete/empty behavior was added |
| 21L-17 Partial outcome accuracy | **HOLDS FOR SCOPE** | No mutation is modelled, so no false atomicity is claimed |
| 21L-18 Recovery ownership | **BLOCKED** | No Custom Study recovery exists |
| 21L-19 Reconnect safety | **BLOCKED** | No binding; transport readiness is unrelated code |
| 21L-20 Collection mismatch | **BLOCKED** | B8 — AnkiDroid continuity is unobtainable and fails open |
| 21L-21 External state change | **BLOCKED** | No filtered resource is tracked |
| 21L-22 Concurrent operation safety | **BLOCKED** | Existing barriers verified green, but no filtered-session admission exists |
| 21L-23 Stale callback safety | **BLOCKED** | No callbacks exist |
| 21L-24 Multiple backend isolation | **BLOCKED** | Nothing to isolate for Custom Study |
| 21L-25 Legacy record safety | **HOLDS FOR SCOPE** | No legacy records exist; nothing was guessed |
| 21L-26 Read context independence | **BLOCKED** | GATE 21F is intact, but there is no Custom Study context to keep independent |
| 21L-27 Security & privacy | **HOLDS FOR SCOPE** | No private AnkiDroid database/scheduler access and no secrets were introduced; `tools/check-capability-pins.sh` only clones public repos into a temp dir and greps them |
| 21L-28 Source-of-truth separation | **HOLDS FOR SCOPE** | Registry (21D), resolver (21E), session lock (21G), families (11/13/17/18) unchanged; no new authority created |
| 21L-29 Prior gate preservation | **PASS (executed)** | Harness: 2625/2625 tests pass at HEAD (§10) with no source change |
| 21L-30 Scope discipline | **PASS (executed)** | No scheduler, no offline replay queue, no cross-backend migration, no duplicate GATE 20 infrastructure added; the only new files are a report and an evidence script |

Two audits (21L-29, 21L-30) are PASS because they are verifiable without the missing contract, and ten hold *for
scope* because the hazard is absent by construction (21L-06, -10, -11, -12, -13, -16, -17, -25, -27, -28).
**None of that constitutes the lock**: eighteen rows are BLOCKED — 21L-01…21L-05, 21L-07…21L-09, 21L-14,
21L-15, 21L-18…21L-24, 21L-26.

---

## 9. Authoritative verification plan (VER-21L-01 … 21L-47)

Every row is `BLOCKED`: each requires a Custom Study binding, a filtered-resource identity, or a lifecycle
operation, and none of the three exists. Rows are not marked NOT_APPLICABLE, because the prompt permits
NOT_APPLICABLE only when a **locked** contract explicitly excludes the capability — and there is no locked
contract here.

| Verification | Result | Why |
|---|---|---|
| VER-21L-01 Initial AnkiDroid binding | BLOCKED | no binding; AnkiDroid cannot create the resource anyway (B1) |
| VER-21L-02 Initial PC binding | BLOCKED | no PC Anki backend implementation exists in this repo (B2) |
| VER-21L-03 Explicit missing backend | BLOCKED | GATE 21E refusal paths exist and are locked, but no Custom Study request uses them |
| VER-21L-04 AUTO selection | BLOCKED | same — resolver exists, request does not |
| VER-21L-05 Preference change after binding | BLOCKED | no binding |
| VER-21L-06 Backend availability change | BLOCKED | no binding |
| VER-21L-07 Unsupported Custom Study mode | BLOCKED | mode vocabulary unlocked |
| VER-21L-08 Source deck id collision | BLOCKED | no Custom Study source-deck path (the 21J colliding-id test is a different family) |
| VER-21L-09 Filtered resource id collision | BLOCKED | no filtered-resource identity exists |
| VER-21L-10 Valid filtered resource creation | BLOCKED | B1/B2/B4 |
| VER-21L-11 Duplicate start/create | BLOCKED | no start path |
| VER-21L-12 Create response lost | BLOCKED | no creation ledger |
| VER-21L-13 Create result persistence failure | BLOCKED | no persistence |
| VER-21L-14 StudySession ownership consistency | BLOCKED | no Custom Study owner |
| VER-21L-15 Backend-authoritative next card | BLOCKED | no filtered queue path |
| VER-21L-16 No local queue | **PASS (executed, scoped)** | no local next-card selection exists: no new scheduler code, `CustomStudy|Filtered` tests = 0 |
| VER-21L-17 Review information | BLOCKED | no filtered review turn |
| VER-21L-18 Rating ownership | BLOCKED | GATE 11 intact for normal sessions only |
| VER-21L-19 AMBIGUOUS rating barrier | BLOCKED | the barrier exists and is green for normal sessions; no filtered variant |
| VER-21L-20 Reviewer action ownership | BLOCKED | same |
| VER-21L-21 Valid rebuild | BLOCKED | no rebuild path |
| VER-21L-22 Rebuild during pending rating | BLOCKED | no admission rules exist for filtered resources |
| VER-21L-23 Rebuild response lost | BLOCKED | no rebuild ledger |
| VER-21L-24 Post-rebuild state | BLOCKED | no rebuild path |
| VER-21L-25 Empty filtered session | BLOCKED | no session state |
| VER-21L-26 Backend failure ≠ completion | BLOCKED | no filtered session; the equivalent normal-session rule is unchanged |
| VER-21L-27 Local end vs backend cleanup | BLOCKED | no end path; B5 undecided |
| VER-21L-28 Supported backend cleanup | BLOCKED | no cleanup path |
| VER-21L-29 Cleanup response lost | BLOCKED | no cleanup ledger |
| VER-21L-30 End session with pending rating | BLOCKED | GATE 11 durability is intact, but no Custom Study termination exists to test |
| VER-21L-31 Original backend unavailable | BLOCKED | no workflow to starve |
| VER-21L-32 Same-collection reconnect | BLOCKED | B8 — continuity is unobtainable on AnkiDroid |
| VER-21L-33 Different collection | BLOCKED | same |
| VER-21L-34 Unknown collection assurance | BLOCKED | the fail-open path is a **known cross-gate gap** (21C/21B B10), not a pass |
| VER-21L-35 External resource deletion | BLOCKED | no tracked resource |
| VER-21L-36 Process-death restoration | BLOCKED | no record |
| VER-21L-37 Original backend missing after restart | BLOCKED | no record; nothing invokes AUTO for a Custom Study because none exists |
| VER-21L-38 Outstanding lifecycle mutation after restart | BLOCKED | no ledger |
| VER-21L-39 Legacy missing backend identity | BLOCKED | no legacy records exist (nothing to block, nothing to guess) |
| VER-21L-40 Multiple backend workflows | BLOCKED | no workflows |
| VER-21L-41 Library backend switch | BLOCKED | GATE 21F isolation is intact, but no Custom Study session exists to keep A-bound |
| VER-21L-42 Stale lifecycle callback | BLOCKED | no callbacks |
| VER-21L-43 A → B → A generations | BLOCKED | no generations |
| VER-21L-44 Capability asymmetry | BLOCKED | no capability is advertised; nothing to emulate or reroute |
| VER-21L-45 1,000-event fault injection | BLOCKED | the fake filtered-session API would have to be invented first (21L-31) |
| VER-21L-46 Real backend validation | BLOCKED | no device, no disposable collection, no PC backend in this sandbox. Unexecuted required real-backend verification is BLOCKED, not PASS |
| VER-21L-47 Previous gate regression | **PASS (executed)** | 2625/2625 harness tests pass at HEAD; per-area subsets green (§10) |

---

## 10. Build and regression results

Environment facts (measured, not assumed): no `java`/`javac`, no `ANDROID_HOME`/`ANDROID_SDK_ROOT`, no
`/usr/lib/jvm`; Python 3.11.2; `git` over HTTPS to GitHub works; PyPI reachable via `pip`.

```text
COMMAND:      ./gradlew --version
EXECUTED:     yes
EXIT CODE:    1
RESULT:       "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
ENVIRONMENT BLOCKER: no JDK in the sandbox image.

COMMAND:      JAVA_HOME=<jdk4py-java-25> ./gradlew --version
EXECUTED:     yes
EXIT CODE:    1
RESULT:       wrapper could not download gradle-8.7-bin.zip:
              java.io.EOFException: SSL peer shut down incorrectly (services.gradle.org unreachable)
ENVIRONMENT BLOCKER: only github.com / codeload.github.com / api.github.com / registry.npmjs.org /
              pypi.org / files.pythonhosted.org are reachable; Maven Central, dl.google.com and
              services.gradle.org are not. ./gradlew testDebugUnitTest, lint, assembleDebug,
              assembleRelease and connectedDebugAndroidTest are therefore NOT EXECUTED here.

COMMAND:      HARNESS_WORK=/tmp/h tools/jvm-harness/bin/bootstrap-sandbox.sh
EXECUTED:     yes
EXIT CODE:    0
RESULT:       main errors: 0 (files: 328); compose scope errors: 0 (24 files); test errors: 0 (250 files)
ENVIRONMENT BLOCKER: none (documented fallback: jdk4py + kotlin-jupyter-kernel from PyPI,
              Sable/android-platforms android-34 jar and kotlinx.coroutines 1.10.2 from GitHub)

COMMAND:      tools/jvm-harness/bin/run.sh
EXECUTED:     yes
EXIT CODE:    0
RESULT:       RESULT classes=218 tests=2625 passed=2625 failed=0 ignored=0 time=44.3s

COMMAND:      run.sh 'StudySession|SessionLock|BackendLock|SessionAdmission'   -> 47/47 passed
              run.sh 'ReviewCommit'                                            -> 112/112 passed
              run.sh 'ReviewerAction'                                          -> 130/130 passed
              run.sh 'Resolver|Selector|Registry'                              -> 89/89 passed
              run.sh 'CustomStudy|Filtered|FilteredDeck'                       -> 0 classes, 0 tests

COMMAND:      python3 server/test_contract.py   (the command CI's contract-tests job runs)
EXECUTED:     yes
EXIT CODE:    0
RESULT:       "All unit contract tests passed!"

COMMAND:      python3 -m py_compile server/mock_pc_agent.py
EXECUTED:     yes
EXIT CODE:    0
RESULT:       OK

COMMAND:      tools/check-capability-pins.sh   (new; source evidence for §5)
EXECUTED:     yes
EXIT CODE:    0
RESULT:       13 checks hold (8 Anki backend, 4 AnkiDroid contract, 1 AnkiConnect), 0 disagree,
              0 unverified
```

Harness caveats are the repository's own (`tools/jvm-harness/README.md`): it compiles with Kotlin 2.3 instead of
1.9.24 and substitutes API-shaped shims, so its results are **equivalence evidence, not Gradle proof**. Nothing
above is device-verified, and no real AnkiDroid or PC backend was contacted. The full suite result is a
**regression baseline for unchanged sources**, not evidence for any GATE 21L behavior.

---

## 11. Invariant report (INV-21L-01 … INV-21L-36)

| Invariant | State | Note |
|---|---|---|
| INV-21L-01 one immutable configured BackendId per workflow | **BLOCKED** | no workflow exists |
| INV-21L-02 AUTO only before binding | **BLOCKED** | vacuously true; nothing binds |
| INV-21L-03 manual selection never silently falls back | **BLOCKED** | GATE 21E refusal types exist (`BackendResolutionResult2.ExplicitBackendMissing/Unavailable`); unused by Custom Study |
| INV-21L-04 active ownership cannot change backend | **BLOCKED** | no active Custom Study ownership |
| INV-21L-05 collection identity preserved, not fabricated | **HOLDS FOR SCOPE** | nothing was fabricated; AnkiDroid remains `null` (B8) |
| INV-21L-06 source DeckRefs backend-qualified | **HOLDS FOR SCOPE** | `AnkiDeckRef` carries `backendId`; no Custom Study consumer to misuse it |
| INV-21L-07 filtered refs backend-qualified | **BLOCKED** | no filtered ref type exists |
| INV-21L-08 StudySessionId ≠ backend filtered identity | **HOLDS FOR SCOPE** | vacuous: only the first half exists |
| INV-21L-09 Custom Study and StudySession owners agree | **BLOCKED** | no pair to compare |
| INV-21L-10 matching ids ≠ equivalence | **HOLDS FOR SCOPE** | no code compares cross-backend filtered ids; the 21J test at `Gate21jNoteEditOwnershipTest.kt:321` already enforces the same rule for decks |
| INV-21L-11 capability semantics govern modes | **HOLDS FOR SCOPE** | no mode is advertised or implemented |
| INV-21L-12 backend-authoritative scheduler | **HOLDS FOR SCOPE** | normal-session scheduling unchanged; no filtered path |
| INV-21L-13 no local FSRS/due queue | **HOLDS FOR SCOPE** | verified by grep + test count (VER-21L-16) |
| INV-21L-14 no silent substitution of normal review | **HOLDS FOR SCOPE** | no Custom Study path exists to substitute for |
| INV-21L-15 ratings keep GATE 11 authority | **HOLDS** | GATE 11 code untouched; 112/112 green |
| INV-21L-16 actions keep GATE 13 authority | **HOLDS** | GATE 13 code untouched; 130/130 green |
| INV-21L-17 rebuild/cleanup original-backend-bound | **BLOCKED** | no rebuild/cleanup exists |
| INV-21L-18 local termination ≠ backend cleanup | **BLOCKED** | no filtered termination exists |
| INV-21L-19 no assumed atomicity | **HOLDS FOR SCOPE** | no lifecycle mutation modelled |
| INV-21L-20 no blind retry of uncertain mutations | **HOLDS FOR SCOPE** | no retry path exists |
| INV-21L-21 no invented idempotency | **HOLDS FOR SCOPE** | verified upstream: no idempotency key exists in the protocol; none was invented |
| INV-21L-22 restoration preserves backend identity | **BLOCKED** | no Custom Study record |
| INV-21L-23 recovery never uses AUTO to replace an owner | **BLOCKED** | nothing to recover |
| INV-21L-24 verified mismatch blocks incompatible ops | **BLOCKED** | B8 — verification unobtainable on AnkiDroid |
| INV-21L-25 UNKNOWN ≠ verified continuity | **HOLDS FOR SCOPE (cross-gate gap open)** | the existing session check *fails open* when the key is null; that gap belongs to 21C/21B and is recorded, not papered over |
| INV-21L-26 external resource changes not ignored | **BLOCKED** | no tracked resource |
| INV-21L-27 unresolved rating/action records survive termination | **HOLDS** | GATE 11/13 durability untouched and green |
| INV-21L-28 conflicting lifecycle ops respect barriers | **BLOCKED** | no filtered-resource admission exists |
| INV-21L-29 stale callbacks cannot modify newer sessions | **BLOCKED** | no callbacks exist |
| INV-21L-30 library context cannot redirect Custom Study | **BLOCKED** | nothing to redirect; 21F isolation unchanged |
| INV-21L-31 historical missing ownership never guessed | **HOLDS FOR SCOPE** | no legacy records; nothing guessed |
| INV-21L-32 GATE 20 remains lifecycle authority | **HOLDS** | no duplicate lifecycle owner was created |
| INV-21L-33 no duplicate scheduler/session machine/registry/ledger | **HOLDS** | verified: no new type was added by this gate |
| INV-21L-34 no cross-backend migration/replay | **HOLDS** | nothing added |
| INV-21L-35 no private AnkiDroid DB/scheduler access | **HOLDS** | nothing added; the new tool clones public repos only |
| INV-21L-36 21L cannot weaken GATE 10–21K | **HOLDS (executed)** | full suite green on unchanged sources |

---

## 12. Residual risks

1. **Product gap.** Custom Study remains unusable end-to-end on every backend, because AnkiDroid cannot create a
   filtered deck through its public API and the PC agent is not in this repository.
2. **Identity.** The only identity path proven by source is the raw `AddOrUpdateFilteredDeck` (returns
   `OpChangesWithId`). The preset `CustomStudy` path returns no id and names its deck from a *translation*, so any
   name-based identity is fragile and localized.
3. **Replace semantics.** `create_custom_study_deck` reuses an existing deck with the reserved (localized) name and
   updates it in place (`custom_study.rs:162-190`); a second Custom Study can therefore silently rewrite a session
   the user still considers theirs. Unresolved.
4. **Empty vs. remove.** `EmptyFilteredDeck` (returns `OpChanges`) and deck removal (different RPC, different
   result) are not interchangeable; the product decision (B5) is missing.
5. **AnkiDroid continuity.** Collection identity is always UNKNOWN and the session check fails open
   (`AnkiDroidBackend.kt:1385,1389`). Any future 21L that treats "reconnected" as "same collection" would be
   building on INV-21L-24's violation. Owned by 21C/21B.
6. **Device evidence.** Nothing in this report is device- or disposable-collection-verified (B6), including the
   reschedule flag behavior and preview-answer classification.
7. **AnkiConnect is unpinned.** No AnkiConnect version is fixed anywhere in this repository, yet it is the
   documented PC route; its 110-action surface was checked at a floating commit (`4064fa14…`).
8. **Ledger design debt.** Creation/rebuild/cleanup durability (B4) is the largest piece of future work, and the
   prompt forbids inventing a generic ledger in 21L — it must be designed inside GATE 20's operation model.
9. **Environment.** Gradle, lint, APK assembly and instrumented tests cannot run in this sandbox; the harness is
   equivalence evidence only.
10. **Prerequisite drift.** GATE 21J (DECISION-21J-03) and GATE 21K are not locked; the 21I-text vs. 21C-code
    inconsistency over NOMINAL-`OP-M3` is still awaiting an audit decision.

---

## 13. Handoff (for GATE 20 first; GATE 21M second)

### 13.1 What GATE 21M may present today (these contracts exist and are locked)

| Contract | Symbol / path | Availability |
|---|---|---|
| Backend preference | `BackendPreference` (Auto / Explicit / ExplicitKind), `core/anki/BackendPreference.kt:22-77` | available |
| Configured backend identity | `AnkiBackendId` (`AnkiDroidLocal` / `PcAgent(profileId)` / `Fake(id)`), `core/anki/AnkiBackendId.kt` | available |
| Registry state | `AnkiBackendState`, `AnkiBackendStatusSnapshot` / `AnkiBackendStatusEntry`, `AnkiBackendStateLookup` | available |
| Capability/readiness snapshot | `AnkiCapabilities` (25 Boolean flags + 2 non-Boolean members), `AnkiCapabilityStatus` + `AnkiCapabilityReadiness` + `AnkiCapabilityEvidence`, `AnkiImplementedSupport` | available |
| Resolution result + typed failure | `BackendResolutionResult2` (`Resolved`, `NoBackendConfigured`, `NoEligibleBackend`, `ExplicitBackendMissing`, `ExplicitBackendUnavailable`, `CapabilityUnsupported`, `Ambiguous`, `ConfigurationInvalid`) | available |
| Locked StudySession backend | `StudySessionBackendLock` (id + backend + collection + deckRef + startedAt) | available |
| Backend-specific unavailable reasons | `AnkiBackendUnavailableReason` + `AnkiBackendRetryability` | available |
| Collection assurance | `AnkiCollectionIdentity`, `AnkiIdentityAssurance`, `AnkiIdentityContinuation` | available, but **UNKNOWN on AnkiDroid** |
| Blocked-recovery reasons | `AnkiTransactionRecoveryRouting`, ledger `pendingRecovery` / `recoveryBlocker` | available for the note/rating/action families |

### 13.2 What 21M must **not** present (no contract exists)

```text
Custom Study backend identity            (no binding object)
Filtered resource state                  (no ref, no status, no membership read)
Filtered-session lifecycle state         (no Creating/Ready/RebuildPending/Completed)
Capability rows for custom study         (no flag exists — presenting a row would be a false claim)
Collection-mismatch reasons for a filtered session (B8 gap)
Blocked reasons for Custom Study recovery (nothing to recover)
```

21M must not recreate the registry, resolver, StudySession lock, or any mutation authority inside the UI; the
prompt's own rule applies, and the surfaces above are the complete set of inputs it may consume.

### 13.3 Unblock dossier for GATE 20 (input, not a decision)

New, independently re-derived facts that narrow the blockers:

1. **Identity (B3):** the raw path returns an id — `AddOrUpdateFilteredDeck -> OpChangesWithId`
   (`decks.proto:35`, `rslib/src/decks/service.rs:203`). The preset path cannot (`CustomStudy -> OpChanges`,
   `scheduler.proto:46`, `custom_study.rs:28-31`; name from `tr.custom_study_custom_study_session()`).
   DECISION-20-08 therefore reduces to "raw path vs. preset", with the raw path as the only identity-bearing
   option.
2. **Empty vs. rebuild (B5 input):** different RPCs with different results —
   `EmptyFilteredDeck -> OpChanges` and `RebuildFilteredDeck -> OpChangesWithCount` (`scheduler.proto:32-33`).
   They cannot be substituted for one another, and neither is a deck *deletion*.
3. **"No cards" is an error (21L-17 input):** creation with a search that matches nothing fails with
   `CustomStudyError::NoMatchingCards` (`custom_study.rs:177-190`); `allow_empty: false`. An empty filtered deck
   and an exhausted session must be modelled as different things.
4. **PC route (B2):** AnkiConnect exposes 110 actions and **none** of them creates or drives a filtered deck, so
   if the PC backend is to support Custom Study at all, the route must be an in-process/plugin decision made and
   verified in the PC agent's repository.
5. **AnkiDroid (B1):** the pinned public contract can only *observe* a filtered deck (`Deck.DECK_DYN`,
   read-only) and *select* a deck (`Deck.CONTENT_SELECTED_URI`); everything else about filtered decks is
   app-internal UI. If AnkiDroid is in scope for "review of an existing filtered deck", that is a decision about a
   *narrower* capability than Custom Study creation, and it still needs the `deckID` side-effect evidence (B6).
6. **Reproducibility:** every statement above is re-checkable with `tools/check-capability-pins.sh`.

---

## 14. Gate lock decision

```text
GATE 21L:                            BLOCKED
CUSTOM STUDY BINDING:                NOT_IMPLEMENTED (BLOCKED — GATE 20)
BACKEND IMMUTABILITY:                NOT_APPLICABLE (no binding exists)
FILTERED RESOURCE IDENTITY:          BLOCKED (B3: preset path returns no id)
CAPABILITY HONESTY:                  PASS (no unsupported capability is advertised anywhere)
SCHEDULER OWNERSHIP:                 BLOCKED (no filtered-session scheduler path)
RATING / REVIEWER ACTION INTEGRATION: NO_CHANGE (GATE 11 / GATE 13 intact; suites green)
CREATE / REBUILD / END:              BLOCKED (B1, B2, B4, B5)
LIFECYCLE AMBIGUITY:                 BLOCKED (B4: no ledger designed)
RESTORATION:                         BLOCKED (no persisted Custom Study record)
COLLECTION SAFETY:                   UNCHANGED — AnkiDroid fails open (cross-gate gap, 21C/21B B10)
STALE CALLBACK ISOLATION:            NOT_APPLICABLE (no callbacks exist)
ARCHITECTURE AUDIT:                  BLOCKED (2 PASS, 10 "holds for scope", 18 BLOCKED — see §8)
VERIFICATION:                        BLOCKED (1 scoped PASS, 1 regression PASS, 45 BLOCKED — see §9)
BUILD:                               BLOCKED FOR GRADLE (no JDK/SDK/Maven); harness + contract tests
                                     EXECUTED and green (§10)
NEXT AUTHORIZED SUB-GATE:            NONE — return to GATE 20
```

`GATE 21L: BLOCKED` is not a PASS. No part of this report claims runtime-tested Custom Study behaviour, because
none exists to test.

**Files changed:** `docs/GATE_21L_REPORT.md` (new), `tools/check-capability-pins.sh` (new),
`docs/GATE_21L_BLOCKED_REPORT.md` (pointer note only). No production code, test, capability flag, or build file
was modified.
