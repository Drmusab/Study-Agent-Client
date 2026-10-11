# GATE 21B — Backend Contract Resolution & Architecture Lock

**Sub-Gate:** 21B of 21A–21N (parent: GATE 21 — Multi-Backend Anki Selection / Hybrid Mode)
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/e0f0b8dd-study-agent-client`
**HEAD:** `236cff8f2733203ccacbc479a1463b283fcf2ecf`
**Date:** 2026-10-11
**Execution type:** Architecture decision, contract specification, formal safety analysis

```text
GATE 21B — BACKEND CONTRACT RESOLUTION

RESULT:                     LOCKED
REPOSITORY HEAD:            236cff8f2733203ccacbc479a1463b283fcf2ecf
GATE 21A PREREQUISITE:      PASS
PRODUCTION CODE MODIFIED:   NO
GRADLE CHANGES:             NONE
PERSISTENCE MIGRATIONS:     NONE
RUNTIME BEHAVIOR CHANGES:  NONE
IMPLEMENTATION AUTHORIZED:  NO
NEXT AUTHORIZED SUB-GATE:   21C — Backend Identity & Resource Qualification
```

**Deliverables of this gate**

| Document | Owns |
|---|---|
| `docs/GATE_21B_CONTRACT_LOCK.md` (this file) | Decisions 01–30, prerequisite validation, contract consistency review, architecture audit, verification, handoff, residual risk, gate lock |
| `docs/BACKEND_SELECTION_POLICY.md` | AUTO algorithm and tie-breakers, manual policy, readiness/capability ladder, typed resolution semantics, fallback scope |
| `docs/BACKEND_IDENTITY_CONTRACT.md` | Backend identity, collection identity, assurance categories, resource qualification, collisions, persistence and migration |
| `docs/BACKEND_OWNERSHIP_INVARIANTS.md` | Session ownership, transaction ownership, recovery targets, editor/creation binding, custom study, source-of-truth and operation-ownership matrices |
| `docs/BACKEND_FAILURE_MATRIX.md` | Unavailable, capability loss, mismatch, reconnect, process death, ambiguity, historical identity, read switch, diagnostics/privacy, security preconditions |
| `docs/GATE_21B_DECISION_EVIDENCE.md` | Traceability matrix: decision → 21A evidence → policy choice → risk → downstream sub-gate |

To avoid duplicating the same explanation six times: each decision below states its **question,
evidence, rejected alternatives and rationale** here, and delegates the **full statement of the locked
policy** to the owning document, referenced explicitly.

---

# PART 0 — PREREQUISITE VALIDATION

## 0.1 Documented repository HEAD vs current HEAD

| Item | Value |
|---|---|
| HEAD documented by GATE 21A | `2d2a0a095ac9b50f10bdf101528491dc6b7e68a4` (`2d2a0a0 docs(gate21a): map backend identity routing and capability contracts`) |
| Current HEAD | `236cff8f2733203ccacbc479a1463b283fcf2ecf` (`236cff8 Merge pull request #64 …`) |
| Relationship (verified) | `2d2a0a0` is the **first parent** of the current HEAD: `gh api .../commits/236cff8 --jq '.parents[].sha'` → `["2d2a0a095ac9b50f10bdf101528491dc6b7e68a4", "5d2abce…"]` |
| Method note | The local clone is shallow (depth 1), so the documented hash is not reachable locally. Provenance was therefore established through the GitHub API against the upstream repository, not assumed. |

## 0.2 Source changes since GATE 21A

```text
gh api repos/Drmusab/Study-Agent-Client/compare/2d2a0a0...236cff8
  status:        ahead
  total_commits: 2
  files:         docs/BACKEND_CAPABILITY_MATRIX.md      (modified)
                 docs/BACKEND_IDENTITY_INVENTORY.md     (modified)
                 docs/BACKEND_ROUTING_INVENTORY.md      (modified)
                 docs/GATE_21A_DISCOVERY.md             (modified)
                 docs/GATE_21A_EVIDENCE_LEDGER.md       (added)
```

**Result: zero production source changes since GATE 21A.** Every changed path is under `docs/`. The
delta is exactly the GATE 21A documentation set delivered by PR #64 (merged `2026-10-11T16:06:49Z`).

Consequences:

- 21A's `app/src` findings are **not stale**. No revalidation of line numbers or symbols against a moved
  HEAD is required. (They were nevertheless re-verified independently — §0.4.)
- 21A's statement that no production code was modified in its sub-gate is consistent with the merge
  evidence.

## 0.3 GATE 21A result

`docs/GATE_21A_DISCOVERY.md`, PART VI §1: **STATUS: PASS**; PART VII: **GATE 21A — DISCOVERY LOCKED**;
audit 10/10 PASS (AUDIT-21A-01…10); verification 10/10 PASS (VER-21A-01…10); safety-critical unknowns
enumerated with explicit `BLOCKS GATE 21B` markers; `IMPLEMENTATION AUTHORIZED: NO`.

**Prerequisite verdict: PASS.** GATE 21A is complete, locked, and describes the current source.

## 0.4 Independent re-verification of safety-critical 21A claims

GATE 21B did not accept 21A on trust. Each claim below was re-derived from source at this HEAD. All
re-verifications confirmed the 21A claim.

| # | 21A claim | GATE 21B re-verification | Verdict |
|---|---|---|---|
| 1 | Only one real backend; no `PcAnkiBackend` | `grep -rn "PcAnkiBackend" app/src` → 0 production matches; `grep -rn "PcAgent(" app/src/main` → only the declaration at `AnkiBackendId.kt:19` | CONFIRMED |
| 2 | Registry holds one entry, refuses duplicates | `AppContainer.kt:492-495` `AnkiBackendRegistry(listOf(ankiDroidBackend))`; `AnkiBackendSelector.kt:12` `require(byId.size == backends.size)` | CONFIRMED |
| 3 | `AnkiBackendId` is logical, serializable, round-trips, blank-refusing | `AnkiBackendId.kt:8-42` read in full | CONFIRMED |
| 4 | Collection identity always null on AnkiDroid | `AnkiDroidDeckMapper.kt:93,114` `collectionKey = null` | CONFIRMED |
| 5 | Collection continuity check fails open when the bound key is unknown | `AnkiDroidBackend.kt:1336-1341` `val boundKey = … ?: return true` | CONFIRMED |
| 6 | Recovery reports `COLLECTION_MISMATCH` only when both keys are known | `AnkiTransactionRecoveryRouting.kt:71` | CONFIRMED |
| 7 | Resource refs are backend-qualified at construction and in `stableKey` | `AnkiRefs.kt:13,27,49`; `AnkiNoteModels.kt:25`; `AnkiTemplateModels.kt:18` | CONFIRMED |
| 8 | Media ref and filtered-session ref do not exist; media result is unqualified | `NoteCreationBackendResults.kt:68-74,93`; no `AnkiMediaRef`/`AnkiFilteredSessionRef` type in `app/src` | CONFIRMED |
| 9 | Study runtime resolves once, inside `begin()`, and routes post-binding by owned identity | `AnkiStudyEffectExecutor.kt:544-549` (only `AnkiBackendSelector(registry).resolve`), `:508, :513, :628, :664-666, :804` all `registry.find(<owned id>)`; `grep` for `preferenceStore` inside the executor → none | CONFIRMED |
| 10 | Lock published only after `beginReview` succeeds; released only on end | `AnkiStudyEffectExecutor.kt:582-585`, `:802` | CONFIRMED |
| 11 | Lock is process memory only; `AnkiSessionContext` not serializable | `AnkiBackendStatus.kt:128` `MutableStateFlow<Map<String, AnkiSessionContext>>`; `AnkiSessionContext.kt:10` plain `data class` | CONFIRMED |
| 12 | All four durable families persist `backendId`; envelopes are versioned | `ReviewCommit.kt:272`; `ReviewerActionRecord.kt:110`; `edit/NoteMutationRecord.kt:21`; `create/NoteCreationRecord.kt:47`; ledger versions 4/1/1/1 | CONFIRMED |
| 13 | Recovery never consults the resolver or the preference | `AnkiTransactionRecoveryRouting.kt` read in full — no reference to resolver/preference/mode; test-enforced | CONFIRMED |
| 14 | Note coordinators are pinned to one instance and refuse foreign records | `NoteMutationCoordinator.kt:151,344`; `NoteCreationCoordinator.kt:162,277`; wired at `AppContainer.kt:439,470` | CONFIRMED |
| 15 | Read contexts Library / Deck Details / Card Browser / Add-Note seed are pinned; `switchBackend` has no production caller | `AppNavHost.kt:81,94,110,142,144`; `grep -rn switchBackend app/src` → 3 declarations + 2 test files only | CONFIRMED |
| 16 | Deck-level routes carry a raw deck id with no backend discriminator | `Screen.kt:17,64` (base64 deck id only) | CONFIRMED |
| 17 | Async correlation exists for Browser and Library | `CardBrowserViewModel.kt:62,142,387`; `AnkiLibraryRepository.kt:193,208-210` | CONFIRMED |
| 18 | Diagnostics keep the four roles separate | `AnkiBackendPreference.kt:41-57`; `DiagnosticsRepository.kt:594-605` | CONFIRMED |
| 19 | Capability claims are dropped when a writer is not wired; browser/media/flags/counts are refused | `AnkiDroidBackend.kt:211`; `AnkiDroidCompatibilityPolicy.kt:63,106`; `AnkiCompatibility` browser `NONE`; `AppContainer.kt:326` | CONFIRMED |
| 20 | GATE 21 test inventory: 6 classes, 56 tests | `grep -c '@Test'`: 15 + 13 + 9 + 4 + 7 + 8 = **56** | CONFIRMED |
| 21 | Durable formats have always carried a full backend identity (so "owner unknown" is not a historical case for records) | `ReviewCommitLedger` decode requires `commitId` (which embeds `backendId`) for every schema 1..4; the one nullable-owner type is `CommitIdentityTombstone`, repaired from evidence inside `commitKey` (`ReviewCommitLedger.kt:138-145, 210-216`) | CONFIRMED (and sharpened — see DECISION-21B-17) |

**No 21A finding required correction.** Two findings were *sharpened* rather than corrected: the
tombstone identity-repair path (item 21), and the fact that the resolver's `TEST_DOUBLE` fallback branch
is unreachable from production modes (see DECISION-21B-06).

**One benign line drift found and recorded.** 21A cites `ui/components/anki/AnkiBackendUiModels.kt:50`
for `AnkiBackendId.displayLabel()`. At this HEAD line 50 is inside
`AnkiBackendModeUiModel.SWITCH_EFFECT_NOTICE`; `displayLabel()` is at `:56`. The claim ("a display label
is UI-only and never an identity") holds; only the line moved. GATE 21B cites `:56` in the identity
contract and leaves the 21A document untouched.

**One additional supporting finding (not a contradiction).** `AnkiBackendUiModels.kt:49-51` already ships
the switch-effect notice *"Applies to new browsing and new study sessions. An active session, an open
editor and any unfinished save keep the backend they started on."* That sentence is the user-facing form of
DECISION-21B-05/12/20, **and it is not yet factually true for the pinned read surfaces** (Library, Deck
Details, Card Browser are hard-bound to one instance — 21A sites #23–#27). This is independent evidence
that 21F's work is required for *truthfulness* of existing UI copy, not merely for new capability, and it
reinforces DECISION-21B-18 rather than changing it.

## 0.5 Implemented Gates vs design-only specifications

A prerequisite of this gate is not to claim compatibility with code that does not exist
(PART VI, INV-21B-20). Status at this HEAD:

| Gate | Status | Consequence for 21B |
|---|---|---|
| GATE 01–10 (backend abstraction, domain, gateway, deck, review, card, rendering, session) | IMPLEMENTED | Contracts to preserve |
| GATE 11 (rating commit) | **IMPLEMENTED; gate result BLOCKED** — real-device mutation verification not run | Durable machinery is real; "exactly-once on a device" is **not** claimed |
| GATE 12 (answer reveal) | IMPLEMENTED | Not affected |
| GATE 13 (reviewer actions) | IMPLEMENTED AND WIRED | Owned routing to preserve |
| GATE 14 (library/deck details) | IMPLEMENTED | Pinned read contexts to fix in 21F |
| GATE 15 (card browser) | **OPEN — NOT LOCKED** | Browser surface exists; `CARD_BROWSER` capability is `NONE` |
| GATE 16 (card/note details) | IMPLEMENTED; **BLOCKED from LOCKED** pending Gradle/device verification | Route-bound backend ownership is real |
| GATE 17 (note editing) | **LOCKED**, implementation complete | Editor binding to preserve |
| GATE 18 (note creation) | **LOCKED**, implementation complete | Creation binding to preserve |
| GATE 19 (note models/templates) | **LOCKED**, implementation complete | Model/template reads are backend-scoped |
| GATE 20 (custom study / filtered decks) | **BLOCKED — not implemented** | Every custom-study statement in 21B is a FUTURE CONTRACT |
| GATE 21 (multi-backend contract) | **LOCKED**, implementation report present (6 classes / 56 tests, JVM harness) | Its decisions 21-01…21-39 are the parent contract; 21B refines, never duplicates |
| PC Anki backend | **ABSENT** | Every PC cell in 21B is a specification, not a measurement |
| Offline/degradation (GATE 22) | Not started | 21B makes nothing replayable; degradation is out of scope |

## 0.6 Rule compliance

| Prerequisite rule | Compliance |
|---|---|
| Verify documented HEAD against current HEAD | Done — §0.1 (via upstream API, because the clone is shallow) |
| Identify source changes since 21A | Done — §0.2: **none** |
| Confirm 21A has a documented PASS/LOCKED result | Done — §0.3 |
| Confirm discovery evidence exists for every safety-critical claim | Done — 21A evidence ledger `01-xx`…`14-xx` plus 21B's own re-verification §0.4 |
| Distinguish implemented Gates from design-only specifications | Done — §0.5, and every contract statement is labelled IMPLEMENTED / LOCKED / FUTURE CONTRACT |
| Revalidate stale findings if code changed | Not required (no code changed); revalidated anyway — §0.4 |
| Do not treat a previous assistant-generated roadmap as implementation evidence | Complied: only source, tests and the gate documents in this repository are cited; no roadmap is used as evidence |
| Do not invent missing findings | Complied: the PC backend, media ref, filtered-session ref and PC identity allocation remain explicitly absent |

**PREREQUISITE VALIDATION: PASS.**

---

# PART 1 — CONTRACT RESOLUTION (DECISION-21B-01 … DECISION-21B-30)

Format per decision: `DECISION ID / QUESTION / DISCOVERY EVIDENCE / ALTERNATIVES / SELECTED POLICY /
RATIONALE / TRADE-OFFS / FAILURE BEHAVIOR / AFFECTED COMPONENTS / INVARIANTS / OPEN UNCERTAINTY`.
Rejected alternatives carry their technical reason for rejection.

---

## DECISION-21B-01 — Canonical backend identity

**DECISION ID:** DECISION-21B-01
**QUESTION:** What uniquely identifies one configured Anki backend, and how are backend *kind*,
configured *instance*, *runtime object* and *display name* kept apart?
**DISCOVERY EVIDENCE:** Ledger `02-01`…`02-12`; `AnkiBackendId.kt:8-42` (`@Serializable` sealed interface,
`stableId`, `fromStableId` returning `null` for unknown); `AnkiBackendSelector.kt:12` (duplicate identity
refused); `AnkiBackendDescriptor.kt:34,41,67` (kind/locality derived, never identity); 21A Safety-Critical
Unknown #2 (no production `PcAgent` producer, so allocation is unauditable). Re-verified §0.4 items 3, 21.
**ALTERNATIVES:**
1. *Use the backend kind as identity* (`ANKIDROID_LOCAL` / `PC_AGENT`). **Rejected:** two PC configurations
   are two collections; a kind cannot distinguish them, and a single identity for two write targets would
   put two collections under one ledger namespace — the exact failure the type comment forbids.
2. *Use the connection endpoint (host:port) as identity.* **Rejected:** endpoints change (DHCP, roaming,
   agent restart) and would silently re-key durable records; endpoints are also not something we want in
   diagnostics (see DECISION-21B-27).
3. *Use a display/label string as identity.* **Rejected:** labels are user-editable and non-unique;
   identity would change when a user renames a backend, breaking ledger ownership and route decoding.
4. *Keep `PcAgent(profileId)` but leave the allocation policy undefined.* **Rejected:** 21A shows two
   desktop machines can both run a profile named e.g. `User 1`; without an allocation rule, two collections
   could share one identity and one write ledger.
5. *Mint a fresh random UUID per registration.* **Rejected:** object/registration-scoped identity is not
   durable; a reinstall or re-registration would orphan every record and route, and a "same configuration"
   would become a different owner.
**SELECTED POLICY:** Backend identity is `AnkiBackendId` (`AnkiBackendId.kt:8`), a kind-namespaced,
`@Serializable`, configuration-derived identity whose canonical form is `stableId`. It identifies a
**configured instance**, never a kind, a runtime object or a display name. Allocation is owned by a single
allocation authority at configuration time; the five allocation rules (A1–A6) and the stability/collision
rules are locked in `BACKEND_IDENTITY_CONTRACT.md` §1.3–§1.5. The existing variants are frozen; no new
encoding is introduced now.
**RATIONALE:** The existing representation already satisfies the required invariant for representation and
registration; what is missing is *allocation discipline*, not a new type. Adding a new identity scheme now
would invalidate persisted records and route tokens for no safety gain, and would be a change with
migration risk in an architecture-only gate. The contract therefore locks the allocation rules that must
exist before a second instance may be registered.
**TRADE-OFFS:** The `profileId` discriminator is coarse: it cannot distinguish two machines that reuse the
same profile name. This is accepted as a *configuration-time obligation* (A2) rather than encoded now,
because encoding a machine discriminator would change the persisted format. A future finer scheme must be
purely additive (`BACKEND_IDENTITY_CONTRACT.md` §1.6).
**FAILURE BEHAVIOR:** A collision (two configurations resolving to one `stableId`) is a configuration
error: `ConfigurationInvalid`, refused at allocation and again at registration
(`AnkiBackendSelector.kt:12`). Fail-closed at startup; never resolved by renumbering, "first wins", or
silent merging.
**AFFECTED COMPONENTS:** `core/anki/AnkiBackendId.kt`, `AnkiBackendRegistry`, all four durable ledgers,
navigation routes (`Screen.kt`), diagnostics; future 21D (allocation + registration).
**INVARIANTS:** INV-21B-01.
**OPEN UNCERTAINTY:** No producer exists, so allocation discipline is unverifiable until 21D is
implemented. Whether the 21D discriminator will be an app-generated install id, a user-supplied instance
name, or a composed `<agent-id>:<anki-profile>` is deliberately left open **within the constraints A2/A3**.

---

## DECISION-21B-02 — Canonical collection identity

**DECISION ID:** DECISION-21B-02
**QUESTION:** How is backend collection ownership represented, and what must happen on match, mismatch,
unavailability, and un-checkability after reconnect?
**DISCOVERY EVIDENCE:** Ledger `03-01`…`03-08`; `AnkiRefs.kt:7` (nullable `collectionKey`; "null means
unknown, never an invented default"); `AnkiDroidDeckMapper.kt:93,114` (always null);
`AnkiDroidBackend.kt:1336-1341` (continuity check fails open on an unknown bound key);
`AnkiTransactionRecoveryRouting.kt:71` (mismatch only when both keys exist); 21A discovery
DISCOVERY-21-03/04 (no cross-backend equivalence, no sync state). Re-verified §0.4 items 4–6.
**ALTERNATIVES:**
1. *Synthesize a collection identity from deck names, card counts or card ids.* **Rejected:** all four are
   either unstable or coincidentally equal across collections; a synthesized identity would manufacture a
   match and permit silent cross-collection continuation — the failure GATE 21 explicitly prohibits.
2. *Persist a client-written marker inside the Anki collection and read it back.* **Rejected:** it requires
   writing to a collection in order to prove identity, changes user data, is not available on a read-only
   path, and would create a mutation whose owner is the very thing being established. (Reconsiderable far
   in the future, but it must never be the sole basis.)
3. *Treat `null` as a wildcard/match (generalize the current fail-open helper).* **Rejected:** it converts
   "we cannot tell" into "we verified", which is the single most dangerous conflation in this design.
4. *Fail closed on every operation when no key exists.* **Rejected:** the only real backend never produces a
   key, so this would disable the application entirely; it also adds no safety because nothing was ever
   promised for nominal-unknown bindings.
5. *Block all mutation unless an authoritative match exists.* **Rejected** for the same reason as (4), and
   because the mutation families already carry their own non-application/replay safety rules that do not
   depend on collection proof.
**SELECTED POLICY:** `AnkiCollectionIdentity(backendId, collectionKey?)` remains the only collection type;
`null` means UNKNOWN and is never a wildcard, a sentinel or a back-fillable value. The operation-specific
assurance boundary is locked in `BACKEND_IDENTITY_CONTRACT.md` §2.4 and §3.3: match ⇒ continuation;
mismatch ⇒ fail closed; **nominal** unavailability ⇒ continue under the recorded category with explicit
disclosure; **degraded** unavailability ⇒ mismatch-grade block; un-checkable after reconnect ⇒ never
verified, and an ambiguous mutation stays ambiguous.
**RATIONALE:** Identity evidence must come from the backend, and where none exists the honest representation
is absence. The safety boundary is drawn by *operation class*, because an initial read-only operation and
the recovery of a possibly-applied mutation carry completely different consequences if the collection is
wrong.
**TRADE-OFFS:** Some operations proceed without collection proof (nominal case). They are exactly the
operations that were never verifiable on this backend, and the app must disclose the category rather than
imply verification. AnkiDroid profile switches remain undetectable — accepted and recorded as residual
risk, never compensated by a heuristic.
**FAILURE BEHAVIOR:** `AUTHORITATIVE_MISMATCH` ⇒ refuse new bindings, block bound work, `Blocked(COLLECTION_MISMATCH)`
for affected transaction groups. Degradation (a known key becoming unknown) ⇒ same blocking treatment. No
path converts unknown into matched.
**AFFECTED COMPONENTS:** `core/anki/AnkiRefs.kt`, `AnkiSessionContext`, all four ledgers and their recovery
paths, `AnkiTransactionRecoveryRouting`, `AnkiDroidBackend.collectionMatchesBoundSession`, diagnostics.
**INVARIANTS:** INV-21B-02, INV-21B-13.
**OPEN UNCERTAINTY:** Whether AnkiDroid exposes *any* authoritative collection discriminator cannot be
settled from source (a device probe would be required); until then the policy treats it as UNAVAILABLE by
design. Whether the PC agent can report one is unknown because no implementation exists.

---

## DECISION-21B-03 — Identity assurance model

**DECISION ID:** DECISION-21B-03
**QUESTION:** How much identity evidence is required before an operation is permitted, and what is permitted
at each level of evidence?
**DISCOVERY EVIDENCE:** As DECISION-21B-02; additionally 21A's collection-identity classification table
(`AUTHORITATIVE_STABLE: none`, `UNAVAILABLE: AnkiDroid production`, `UNKNOWN: PC`) and the codes that
already distinguish "cannot tell" from "told differently" (`AnkiTransactionRecoveryRouting.kt:57-71`).
**ALTERNATIVES:**
1. *A binary verified/unverified flag.* **Rejected:** it cannot express "no key exists by construction"
   versus "a key existed and is gone", and those two must behave differently.
2. *Trust the binding whenever no key exists, forever.* **Rejected:** it would let a backend that acquires a
   key (or loses one) change semantics without any check — the "silent upgrade" failure.
3. *Require an authoritative match for everything.* **Rejected:** unusable with the only real backend (see
   DECISION-21B-02 alternative 4).
4. *Grant mutation recovery permission when the network reconnects.* **Rejected:** reachability is not
   evidence about what the backend already did. This is the explicit failure this gate must prevent.
**SELECTED POLICY:** Four conceptual categories locked in `BACKEND_IDENTITY_CONTRACT.md` §3 —
`AUTHORITATIVE_MATCH`, `SESSION_CONTINUITY_VERIFIED`, `IDENTITY_UNVERIFIABLE` (with `nominal` and `degraded`
sub-qualifiers), `AUTHORITATIVE_MISMATCH` — plus the operation-by-assurance decision matrix (§3.3), the
no-spontaneous-downgrade rule (§3.2) and the assurance computation algorithm (§3.5). Mutation recovery is
never granted on connectivity: `OP-M2` requires the family's own non-application proof, and `OP-M3` requires
an authoritative reconciliation capability **and** a verified match.
**RATIONALE:** The distinction that matters is not "do we have a key" but "did we ever promise one".
Nominal absence is the honest state of a backend that never had identity to give, and blocking it would
destroy usability without adding safety; degraded absence and mismatch both mean the app can no longer
stand behind its own earlier statement, and those must block.
**TRADE-OFFS:** `SESSION_CONTINUITY_VERIFIED` is convenient but weak: it proves only that this process
still holds its own binding. It is therefore explicitly *not* collection verification, cannot authorize
`OP-M3`, and must be labelled as such everywhere it is surfaced.
**FAILURE BEHAVIOR:** Every blocked cell in the matrix produces a typed refusal or a blocked state that
preserves state; none produces substitution, retry or migration.
**AFFECTED COMPONENTS:** Session binding and continuation, all four transaction families and their recovery,
read-context binding, diagnostics vocabulary, UI indicators (21M).
**INVARIANTS:** INV-21B-02, INV-21B-13, INV-21B-12.
**OPEN UNCERTAINTY:** The `SESSION_CONTINUITY_VERIFIED` category is currently unreachable as a *sole*
category for AnkiDroid in a strict reading (the session also has nominal-unverifiable collection identity);
21C must decide whether the pair is represented as two orthogonal facts (recommended, and what the matrix
assumes) or as one composed category, without losing the distinction.

---

## DECISION-21B-04 — Backend-qualified resource identity

**DECISION ID:** DECISION-21B-04
**QUESTION:** What is the logical identity of an Anki resource, and how are the six reference types locked
against cross-backend confusion?
**DISCOVERY EVIDENCE:** Ledger `04-01`…`04-12`; `AnkiRefs.kt:13,27,49` and `AnkiNoteModels.kt:25`,
`AnkiTemplateModels.kt:18` (backend-qualified, length-prefixed `identityKey`); missing `AnkiMediaRef`
(request qualified at `NoteCreationBackendResults.kt:68-74`, result a bare `Stored(mediaName)` at `:93`);
missing `AnkiFilteredSessionRef` (GATE 20 BLOCKED); `Screen.kt:17,64` deck routes carry a raw deck id;
`Screen.kt:95-122` card routes carry the full qualified ref. Re-verified §0.4 items 7, 8, 16.
**ALTERNATIVES:**
1. *Treat numeric ids as globally unique (a card id is a card id).* **Rejected:** numbers collide across
   collections; this is the interpretation the parent gate forbids and the exact hazard 21A recorded for
   deck routes.
2. *Introduce a client-side translation/correlation table between backends.* **Rejected:** a translation
   table is a merging mechanism; it would create a second authority for "the same resource" and would
   invite cross-backend replay.
3. *Identity a deck by its name/path.* **Rejected:** names repeat and change; renames would silently
   re-point identity.
4. *Enrich existing records in place with a collection key when one becomes available.* **Rejected:**
   enrichment is not proof of the same persisted resource (already documented on `AnkiCardRef`), and it
   would rewrite historical evidence.
5. *Let media/filtered-session references keep travelling unqualified until those features are built.*
   **Rejected:** the reference types are consumed by navigation, drafts and records; an unqualified media
   name or filtered-session id is precisely what would later cross a backend boundary unnoticed.
**SELECTED POLICY:** `logical id = configured backend identity + collection context (or explicit unknown) +
backend-local discriminator (+ type tag)`. The six reference types and their required qualification are
locked in `BACKEND_IDENTITY_CONTRACT.md` §4.2; the prohibited practices in §4.3 (no implicit cross-backend
equality, no global card/note ids, no deck-name identity, no card↔note confusion, no reinterpretation during
navigation, no silent migration); navigation rules in §4.4 (including the interim rule that a legacy
unqualified deck token resolves only against the backend that could have produced it); serialized-reference
compatibility in §4.5.
**RATIONALE:** Backend qualification is already structural for five of six types, and the two missing types
are missing because their features are missing. Locking the rule now — including the exact navigation
interim rule — prevents the first multi-backend build from acquiring a reinterpretation bug.
**TRADE-OFFS:** Deck-level routes stay unqualified until 21F, which leaves a documented, bounded gap; the
interim rule makes today's behavior deterministic without inventing a migration. Adding `AnkiMediaRef`
later means the media name contract changes; doing it now would create a type with no consumer.
**FAILURE BEHAVIOR:** A foreign reference is refused by the backend with a typed error; a malformed route
token decodes to `null` and shows an error screen (never a guess); an unqualified deck token is never
resolved against the preference.
**AFFECTED COMPONENTS:** `core/anki/AnkiRefs.kt`, `AnkiNoteModels.kt`, `AnkiTemplateModels.kt`,
`core/anki/create/**` (media results), navigation (`Screen.kt`, `AppNavHost.kt`), read contexts (21F),
creation (21K), custom study (21L).
**INVARIANTS:** INV-21B-03, INV-21B-14.
**OPEN UNCERTAINTY:** The exact shape of `AnkiMediaRef` and `AnkiFilteredSessionRef` (and whether a media
reference needs a collection key at all) is deferred to the sub-gates that introduce them, under the
qualification rule locked here.

---

## DECISION-21B-05 — Backend selection vocabulary

**DECISION ID:** DECISION-21B-05
**QUESTION:** How many selection concepts exist, who owns each, and what does explicit user selection mean
when a kind has several configured instances?
**DISCOVERY EVIDENCE:** Ledger `02-12` (`AnkiBackendPreferenceStore.mode`; "PreferredBackend is NOT the
active backend"), `08-xx` (session lock), `09-xx` (four durable families), `10-xx` (read contexts);
`AnkiBackendId.kt:44` ("resolution must never overwrite AUTO with an effective identity");
`AnkiBackendRoutingDiagnostics` four fields (`AnkiBackendPreference.kt:41-57`); 21A gap: `PC_AGENT` with two
profiles resolves to ambiguity with no instance-pin mechanism.
**ALTERNATIVES:**
1. *One "current backend" field shared by settings, session and transactions.* **Rejected:** it makes a
   preference change able to move an active session or a pending mutation — the prohibited conflation.
2. *Write the resolved identity back into the preference after AUTO resolves.* **Rejected:** it converts a
   default into a binding, makes the preference depend on transient availability, and is explicitly
   forbidden by the existing type contract (`AnkiBackendId.kt:44`).
3. *Let `PC_AGENT` mean "any PC instance" indefinitely.* **Rejected:** with two instances it is genuinely
   ambiguous, and picking one silently decides which user collection is mutated.
**SELECTED POLICY:** Exactly four concepts — `PreferredBackend` (settings, mode + optional instance pin),
`ResolvedBackend` (one instance for one unbound context), `LockedSessionBackend` (immutable session owner),
`TransactionBackend` (durable record owner). None may substitute for another
(`BACKEND_SELECTION_POLICY.md` §1). Explicit selection must resolve to an **instance**: N = 0 ⇒
`ExplicitBackendUnavailable`, N = 1 ⇒ that instance, N ≥ 2 ⇒ `Ambiguous` unless an instance pin is set
(§1.1).
**RATIONALE:** The four concepts already exist as separate fields in diagnostics and separate components in
the code; the missing piece is the rule for instance-level explicit selection, which becomes necessary the
moment a second instance exists.
**TRADE-OFFS:** An instance pin is an addition to the persisted preference (21E), which must be backward
compatible: a stored mode with no pin keeps today's meaning exactly.
**FAILURE BEHAVIOR:** Ambiguity refuses rather than choosing; an unavailable pinned instance refuses rather
than substituting a sibling; a preference write never touches an active binding.
**AFFECTED COMPONENTS:** `AnkiBackendPreferenceStore`/DataStore codec, `AnkiBackendMode`,
`BackendResolutionRequest`, Settings UI, diagnostics.
**INVARIANTS:** INV-21B-19, INV-21B-05, INV-21B-08.
**OPEN UNCERTAINTY:** Whether the pin is stored as a separate key or composed into the mode string is
21E's decision; the semantic requirement (an instance, not a kind) is fixed here.

---

## DECISION-21B-06 — Deterministic AUTO resolution

**DECISION ID:** DECISION-21B-06
**QUESTION:** What exactly does AUTO do, in what order, with what tie-breaking, and what may it never do?
**DISCOVERY EVIDENCE:** Ledger `07-04`, `07-05`, `13-02`; `AnkiBackendResolver.kt:104-215` (pure function,
mode filter → reachable → capable → local-first → single-remote-or-ambiguous, sorted by `orderingKey`);
`AnkiBackendDescriptor.kt:88` (`orderingKey = kind.ordinal:stableId`); "registry order is never a routing
input" (`AnkiBackendSelector.kt:13`); determinism pinned by `Gate21BackendResolutionTest` tests
`AUTO is deterministic for identical inputs` and `resolution is pure…`. Re-verified §0.4 items 9, 20.
**ALTERNATIVES:**
1. *First backend to respond / fastest to answer.* **Rejected:** timing-dependent and untestable; it makes
   the chosen collection depend on network jitter.
2. *Most recently used, or registry insertion order.* **Rejected:** accidental runtime ordering; the
   registry explicitly documents that order is not a routing input.
3. *Prefer latency (a measured "best" backend).* **Rejected:** no latency measurement exists in this
   repository (21A), and a performance preference must never decide *which collection* is mutated.
4. *Merge both collections when both are ready (a federated view).* **Rejected:** mixing datasets from two
   collections is prohibited outright.
5. *Auto-pick one of several PC profiles.* **Rejected:** two profiles are two collections; choosing is a
   user decision.
6. *Allow AUTO to re-resolve when the bound backend is lost.* **Rejected:** that is mid-session failover.
**SELECTED POLICY:** The exact pseudocode, precedence table and tie-breakers in
`BACKEND_SELECTION_POLICY.md` §3.3 and §3.4: eligibility → readiness (R2) → required capabilities (R3) →
locality class (`LOCAL_DEVICE` before `REMOTE_AGENT`, never `TEST_DOUBLE`) → ambiguity refusal → final
tie-break on `orderingKey`. AUTO runs **only before context binding** (INV-21B-04), returns exactly one
instance or a typed failure, and never merges collections.
**RATIONALE:** The current policy is already pure and deterministic for every input a production mode can
produce; the gate locks the exact precedence and the two clarifications (never select a test double; the
`TEST_DOUBLE` fallback branch is unreachable for real modes) so that later sub-gates cannot "improve" AUTO
into non-determinism.
**TRADE-OFFS:** `LOCAL_DEVICE` first is a *policy* preference, not a performance claim — recorded in DECISION-21B-08
of the parent gate and in 21A discovery DISCOVERY-21-32 (no latency measurement exists). A user who wants
the remote backend must select it explicitly.
**FAILURE BEHAVIOR:** No eligible instance ⇒ `NoEligibleBackend` with `considered` and a typed reason (the
single candidate's own reason when there is exactly one). Capability gap ⇒
`RequiredCapabilityUnavailable` with per-candidate missing sets. Several equal remotes ⇒ `Ambiguous`.
**AFFECTED COMPONENTS:** `AnkiBackendResolver`, `AnkiBackendSelector`, `AnkiBackendStatusCenter.resolveFor`,
`AnkiLocalStudyStarter`, future read contexts (21F).
**INVARIANTS:** INV-21B-04, INV-21B-06, INV-21B-07.
**OPEN UNCERTAINTY:** Availability cannot be observed atomically with capability (two StateFlows); the
current fail-closed convergence (`AnkiBackendDescriptor.supports` requires both projections) is the locked
answer, and 21E must not introduce a "best effort" read of either projection.

---

## DECISION-21B-07 — Manual selection contract

**DECISION ID:** DECISION-21B-07
**QUESTION:** What happens when the user explicitly selects a backend that is unavailable, unauthenticated,
unauthorized or incapable?
**DISCOVERY EVIDENCE:** Ledger `07-01`…`07-03`, `13-05`; `Gate21BackendResolutionTest` tests
`manual selection of an unavailable backend blocks instead of falling back` and
`manual PC selection with no PC registered is not implemented, not redirected`; parent contract
DECISION-21-26; `AnkiBackendSelector.UnavailableReason` (`EXPLICIT_BACKEND_NOT_IMPLEMENTED`,
`EXPLICIT_BACKEND_NOT_READY`, `NO_READY_BACKEND`).
**ALTERNATIVES:**
1. *Silently fall back to the other backend (the convenient behavior).* **Rejected:** it makes the user's
   explicit choice advisory, and it hides which collection is about to be studied.
2. *Ask "switch to the other backend?" mid-operation.* **Rejected:** a prompt inside a failing operation
   cannot be answered safely without knowing whether a mutation was admitted; and the answer would still
   change a binding after the fact.
3. *Auto-switch back to the manual selection when it becomes ready, abandoning the current context.*
   **Rejected:** it moves an active context, which is prohibited regardless of intent.
**SELECTED POLICY:** `BACKEND_SELECTION_POLICY.md` §4 — manual selection is honoured exactly or refused
exactly. Unavailable ⇒ `ExplicitBackendUnavailable` with the instance's typed reason; capability mismatch ⇒
`RequiredCapabilityUnavailable` (not retryable by waiting); authentication ⇒ user-actionable outcome;
permission ⇒ `PermissionRequired` with an explanation that the app cannot grant it itself. A refused start
publishes no lock. A manual selection applies to **future unbound work only**.
**RATIONALE:** The purpose of manual selection is to give the user control over *which collection* is used.
Any silent substitution defeats that purpose, and mid-operation switching would violate session and
transaction ownership simultaneously.
**TRADE-OFFS:** A user who selects an unavailable backend sees a refusal instead of a working app on
another backend. That is the intended trade: correctness of collection ownership over convenience.
**FAILURE BEHAVIOR:** No preference mutation, no lock publication, no partial binding, no retry loop. The
readiness display may refresh; the operation does not restart itself.
**AFFECTED COMPONENTS:** Settings UI + `SettingsViewModel.selectAnkiBackend`, `AnkiBackendSelector`,
`AnkiLocalStudyStarter`, every future pre-binding entry point.
**INVARIANTS:** INV-21B-05, INV-21B-19.
**OPEN UNCERTAINTY:** Whether the dashboard pre-flight (`AnkiLocalStudyStarter.resolveReadyBackendId`, which
uses a hard-coded `LOCAL_PREFERENCE` rather than the persisted preference — 21A site #8) should be aligned
to the preference or to a "single consistent answer" is a **21F/21E UI-consistency decision**; the locked
requirement is only that the button state and the actual start must not disagree about which backend is
meant.

---

## DECISION-21B-08 — Typed resolution result

**DECISION ID:** DECISION-21B-08
**QUESTION:** What are the semantic outcomes of backend resolution, and which are terminal, retryable,
user-actionable, binding-blocking or UI-visible?
**DISCOVERY EVIDENCE:** Ledger `07-25`…`07-30`, `13-04`; `BackendResolutionResult` (`AnkiBackendResolver.kt:13-72`)
with `Resolved`, `NoBackendAvailable`, `PreferredBackendUnavailable`, `RequiredCapabilityUnavailable`,
`Ambiguous`, `ConfigurationInvalid`, each carrying `considered` + a typed `error`; pinned by
`every failure explains itself with a typed error and the identities it considered`; parent contract
DECISION-21-25.
**ALTERNATIVES:**
1. *Return a nullable backend.* **Rejected:** it collapses "nothing is configured", "the one you asked for is
   down" and "it is up but cannot do this" into one indistinguishable state, which the existing contract
   already rejects.
2. *Return a boolean plus a message string.* **Rejected:** a string cannot drive a typed UI action, and it
   invites content (backend names, provider text) into a decision path.
3. *Signal failures by exception.* **Rejected:** resolution is a normal, expected outcome, and exceptions
   would bypass the `considered` evidence needed by diagnostics.
4. *Fold collection identity into the resolution result* (add `IdentityUnavailable` as a resolution
   failure). **Rejected:** resolution happens **before** a context exists, so it cannot observe that
   context's collection identity; adding it would invite callers to "re-resolve" a bound context in order
   to legitimize continuing — the exact re-binding hazard this gate prohibits.
**SELECTED POLICY:** Eight semantic outcomes with their dispositions locked in
`BACKEND_SELECTION_POLICY.md` §5.1: `RESOLVED`, `NO_ELIGIBLE_BACKEND`, `EXPLICIT_BACKEND_UNAVAILABLE`,
`REQUIRED_CAPABILITY_UNAVAILABLE`, `AUTHENTICATION_REQUIRED`, `CONFIGURATION_INVALID`, `AMBIGUOUS`, and —
**demoted out of resolution** — `IDENTITY_UNAVAILABLE`, which is a *binding/continuation verdict*
(`BindingVerdict`, §5.2) because identity becomes observable only once something is bound.
`CONFIGURATION_INVALID` is the only terminal-by-default outcome; nothing else is terminal in the sense of
never being retryable later. Every failure carries the identities it considered and a typed error.
**RATIONALE:** A caller must be able to explain *why* there is no backend and what the user can do about it.
Keeping identity out of resolution preserves the pre-binding/post-binding separation that the whole
ownership model rests on.
**TRADE-OFFS:** Two result vocabularies exist (resolution outcomes and binding verdicts) instead of one
merged type. This is deliberate: merging them would blur the boundary that makes "AUTO only before binding"
enforceable.
**FAILURE BEHAVIOR:** All failure outcomes block context binding for that attempt and are surfaced with
their typed reason; none may be rendered as a generic "backend unavailable".
**AFFECTED COMPONENTS:** `BackendResolutionResult`, `AnkiBackendSelector.Resolution`, UI mapping in
Settings/Study/Library/Diagnostics, 21E.
**INVARIANTS:** INV-21B-04, INV-21B-06, INV-21B-07.
**OPEN UNCERTAINTY:** Whether `AUTHENTICATION_REQUIRED` becomes its own variant or stays a typed reason
inside a failure is 21E's encoding decision; the requirement is that it remains distinguishable and
user-actionable.

---

## DECISION-21B-09 — Capability contract

**DECISION ID:** DECISION-21B-09
**QUESTION:** How do capabilities participate in routing, and how are *implements*, *ready for* and
*permitted to execute* kept apart?
**DISCOVERY EVIDENCE:** Ledger `05-xx`, `13-06`; `AnkiCapabilities` (26 flags, safe defaults),
`AnkiRequiredCapability` (19 requirements mapped by one function `AnkiCapabilities.supports`);
`AnkiDroidBackend.withWriteSupport` (`:211`) dropping claims when a writer is not wired;
`AnkiBackendDescriptor.supports` (`:95-103`) requiring both capability projections;
`AnkiCompatibility` browser `NONE`, `media = false`, `flags = false`, `deckCounts = false`;
`STUDY_SESSION_REQUIREMENTS = { REVIEW }` with `DECKS` deliberately excluded. Re-verified §0.4 item 19.
**ALTERNATIVES:**
1. *Advertise capabilities that the underlying API could theoretically support.* **Rejected:** it would
   claim behavior that no code implements — exactly what 21A recorded as the trap avoided by
   `cardBrowser = NONE`.
2. *Branch on the backend name/kind instead of a capability.* **Rejected:** it makes a second backend
   require editing UI logic; test-enforced prohibition (`capability resolution never inspects a backend name`).
3. *Silently degrade to a lesser operation when a capability is missing.* **Rejected:** a "browse" that is
   really a deck listing is a lie about data completeness.
4. *Keep the hard-coded DI pins so unsupported features keep appearing to work.* **Rejected:** the pins are
   the reason a refusal cannot currently be explained, and they would become the second routing system.
**SELECTED POLICY:** Three-way distinction locked in `BACKEND_SELECTION_POLICY.md` §2.1 —
*implements* (static, versioned claim about the wired object graph), *ready for* (runtime, both projections
must agree, fail-closed), *permitted* (policy: only inside the owning binding, only after pre-binding
checks pass; revalidated before irreversible operations). Requirement expressions go through
`AnkiRequiredCapability` and the single `supports` mapping. Capability loss never redirects and never
retroactively enables. Unsupported features are refused honestly, naming the missing capability.
**Product decision taken here:** `CARD_BROWSER`, `MEDIA_READ`, `FLAGS`, `DECK_COUNTS` remain **refused**
(not implemented in this gate series, not masked by pins). 21F must make the refusals explainable.
**RATIONALE:** Capabilities are the only mechanism that lets several backends with different feature sets
share one routing policy without branching on names. Making the claim honest is a precondition for making
routing capability-constrained.
**TRADE-OFFS:** The Card Browser entry point currently reachable through a pinned injection will be gated
and will refuse on AnkiDroid until a browser capability exists (GATE 15 is OPEN — NOT LOCKED). A product
decision to implement it is out of scope here and remains open.
**FAILURE BEHAVIOR:** `RequiredCapabilityUnavailable` before binding; typed refusal inside a binding;
never substitution.
**AFFECTED COMPONENTS:** `AnkiCapabilities`, `AnkiRequiredCapability`, `AnkiBackendDescriptor`,
`AnkiDroidBackend.withWriteSupport`, resolver, all read contexts, UI gating.
**INVARIANTS:** INV-21B-07, INV-21B-11.
**OPEN UNCERTAINTY:** Capability *versioning* discipline (how a claim is invalidated when the pinned
AnkiDroid contract changes) has no implementation today; 21D must define how a probe/version change
invalidates claims without ever widening them.

---

## DECISION-21B-10 — Backend registry ownership

**DECISION ID:** DECISION-21B-10
**QUESTION:** What is the single source of truth for configured backend instances, and what must it not own?
**DISCOVERY EVIDENCE:** Ledger `01-10`, `01-13`, `01-14`; `AnkiBackendRegistry` (`AnkiBackendSelector.kt:10`),
built once at `AppContainer.kt:492-495`, immutable, duplicate-refusing, exposing `registered`, `ids`,
`find`, `descriptors()`; parent contract DECISION-21-23; 21A AUDIT-21A-14 (registry = existence and state,
never routing).
**ALTERNATIVES:**
1. *Create a new `BackendRegistry` in 21D.* **Rejected:** a second registry is a second source of truth for
   "what exists"; the two could disagree and the disagreement would be silent.
2. *Let the registry also route (make `find` choose).* **Rejected:** it would mix existence with policy and
   make routing depend on registration, destroying the purity that makes AUTO deterministic and testable.
3. *Use a service-locator or a mutable "active backend" singleton.* **Rejected:** a global current backend is
   the classic source of implicit fallback and cannot express per-session ownership.
**SELECTED POLICY:** `AnkiBackendRegistry` in `AppContainer` remains the one application-level source of
configured instances: identities, lookup by identity, per-instance availability and capability snapshots.
It owns nothing else — explicitly excluded: StudySession state, review-commit transaction truth, scheduler
calculations, mutation retry policy, cross-backend synchronization. Boundary table in
`BACKEND_OWNERSHIP_INVARIANTS.md` §1.1.
**RATIONALE:** The component already exists, is immutable, and already refuses duplicate identity. Extending
it is strictly safer than introducing a parallel structure, and the ownership exclusion list is what
prevents the registry from slowly becoming a router.
**TRADE-OFFS:** Registration remains a composition-root concern, so adding a backend requires touching
`AppContainer`; that is accepted, because backend registration is a configuration event, not a runtime one.
**FAILURE BEHAVIOR:** Duplicate identity ⇒ construction failure. Unregistered identity ⇒ `find` returns
null and the caller surfaces a typed error (`BACKEND_NOT_REGISTERED` for recovery; an error screen for
routes).
**AFFECTED COMPONENTS:** `AnkiBackendRegistry`, `AppContainer`, all `registry.find` call sites, 21D, 21F.
**INVARIANTS:** INV-21B-19.
**OPEN UNCERTAINTY:** How a *removed* configuration interacts with a still-registered runtime object at
runtime (dynamic registration) is not exercised today; 21D must define it without introducing a mutable
"current" pointer.

---

## DECISION-21B-11 — Resolver ownership

**DECISION ID:** DECISION-21B-11
**QUESTION:** Does the architecture need a distinct resolver component, or is the boundary adequate inside
the existing service?
**DISCOVERY EVIDENCE:** Ledger `01-11`, `01-12`, `07-39`, `07-40`; `AnkiBackendResolver` is a pure policy
(`AnkiBackendResolver.kt:104`), `AnkiBackendSelector` is a thin session-start adapter
(`AnkiBackendSelector.kt:40`), and `AnkiBackendStatusCenter.resolveFor` exposes the same policy for
pre-binding preview only; 21A AUDIT-21A-03/14.
**ALTERNATIVES:**
1. *Add a second resolver for read contexts.* **Rejected:** two policies can disagree; the existing adapter
   already routes read-context resolution through the same policy.
2. *Move resolution into the registry.* **Rejected:** see DECISION-21B-10 alternative 2.
3. *Allow the resolver into the recovery path ("resolve where this record should go").* **Rejected:** the
   recovery planner is deliberately resolver-free; routing a historical record by current availability is
   cross-backend replay.
**SELECTED POLICY:** No new component. The boundary stays where it is: `AnkiBackendRegistry` = what exists;
`AnkiBackendResolver` (+ `AnkiBackendSelector` adapter, + `AnkiBackendStatusCenter` for preview) = which
instance a **new, unbound** context uses. The resolver must never change an active StudySession, perform
scheduler operations, commit ratings, replay reviewer actions, migrate note edits, recover unknown note
creations, probe, refresh, or write preferences (`BACKEND_OWNERSHIP_INVARIANTS.md` §1.2).
**RATIONALE:** The separation already exists and is test-enforced. Creating a new component would add
surface without adding safety, and it would tempt future sub-gates to give the new component powers the
existing purity forbids.
**TRADE-OFFS:** As the operation set grows, `BackendResolutionRequest` must grow (required capabilities,
readiness class, purpose). That is an extension of one type, not a new policy component.
**FAILURE BEHAVIOR:** Resolution failures are typed and pre-binding; a resolver that cannot answer refuses
rather than returning a default.
**AFFECTED COMPONENTS:** `AnkiBackendResolver`, `AnkiBackendSelector`, `AnkiBackendStatusCenter`, 21E.
**INVARIANTS:** INV-21B-19, INV-21B-04.
**OPEN UNCERTAINTY:** Whether `AnkiBackendStatusCenter` should keep exposing `resolveFor` or delegate to a
single façade is 21E's structural choice; the requirement is that exactly one policy implementation exists.

---

## DECISION-21B-12 — Immutable StudySession ownership

**DECISION ID:** DECISION-21B-12
**QUESTION:** When does a session's backend become immutable, and which component supplies the backend for
every session-bound operation?
**DISCOVERY EVIDENCE:** Ledger `08-01`…`08-10`, `01-15`; `AnkiStudyEffectExecutor.begin()` (`:544-549`
resolution, `:571-576` deck validation, `:578` context creation, `:582` `beginReview`, `:585` lock
publication), `:508` next card, `:513` hydration, `:628,664-666` commit, `:802-804` end and release;
`AnkiStudyBackendLockTracker` (`AnkiBackendStatus.kt:128`); `StudyViewModel.lockedAnkiBackendLabel` reads
the lock, never the preference. Re-verified §0.4 items 9–11.
**ALTERNATIVES:**
1. *Re-resolve the backend for each operation (or on each availability change).* **Rejected:** it makes the
   scheduler owner a function of availability, which is exactly mid-session failover.
2. *Let a settings change rebind the active session.* **Rejected:** it would move an in-flight rating loop
   between collections.
3. *Publish the lock as soon as a backend is chosen (before the backend accepts the session).* **Rejected:**
   a refused start would advertise a backend that owns nothing; the current code publishes only on success
   and that behavior is locked.
4. *Treat PC AI availability as a reason to consider the PC an Anki backend.* **Rejected:** transport and
   service readiness are not Anki readiness.
**SELECTED POLICY:** The session binds its backend **once**, after capability validation and deck
validation against the live collection, at `beginReview` success. `StudySession.backendId` (today
`AnkiSessionContext.backendId`) is immutable for the session's lifetime. Ownership source per operation is
locked in `BACKEND_OWNERSHIP_INVARIANTS.md` §2.3. The PC agent may continue to provide AI/TTS/STT while
another backend owns the Anki scheduler; the boundary is stated in §7 of that document (PC services are
keyed by the agent connection, never by an `AnkiBackendId`, and are not an input to resolution).
**RATIONALE:** Scheduler state (due queue, learning steps, revlog, filtered state) belongs to one collection;
a session that can change owners cannot be reasoned about at all, and the collection-change detection needed
to do it safely does not exist.
**TRADE-OFFS:** A user whose locked backend fails mid-session must wait or end the session; the app cannot
"helpfully" continue elsewhere. This is the deliberate trade for ownership integrity.
**FAILURE BEHAVIOR:** Backend loss ⇒ typed failure on the same identity (`BackendUnavailable`); the session
pauses/blocks; no lock release, no re-resolution, no lock replacement.
**AFFECTED COMPONENTS:** `AnkiStudyEffectExecutor`, `AnkiLocalStudyStarter`, `AnkiStudyBackendLockTracker`,
`AnkiSessionContext`, Study UI indicators, 21G.
**INVARIANTS:** INV-21B-08, INV-21B-18.
**OPEN UNCERTAINTY:** The naming of the lock field (`StudySession.backendId` vs `AnkiSessionContext.backendId`)
and whether 21G introduces a durable session record — see DECISION-21B-14.

---

## DECISION-21B-13 — No mid-session backend failover

**DECISION ID:** DECISION-21B-13
**QUESTION:** What happens when the session's locked backend becomes unavailable while another backend is
available?
**DISCOVERY EVIDENCE:** Ledger `11-xx`, `07-25`…`07-30`, `08-04`; parent contract DECISION-21-12/13;
`Gate21SessionBackendLockTest.losing the locked backend blocks the session instead of failing over` and the
1000-turn endurance test; `Gate21ArchitectureAuditTest.no cross-backend failover exists anywhere in study
runtime`; no `alternateBackend`/`tryOtherBackend`/`fallbackBackend` symbol anywhere in `app/src`.
**ALTERNATIVES:**
1. *Continue the session through the other backend.* **Rejected:** different scheduler state, revlog, due
   queue and possibly a different collection; the session's card refs, session id and rating history would
   describe a collection the other backend has never seen.
2. *Fail over only for read-only session operations (hydrate a card's content) while keeping mutations on
   the original.* **Rejected:** the hydrated content feeds the same review turn and the same rating; mixing
   two collections inside one turn invalidates the turn's meaning even without a write. (Read contexts
   *outside* an active session are a separate matter, governed by read-context rules.)
3. *Fail over if sync status or matching ids suggest the collections are the same.* **Rejected:** no sync
   state exists, and matching ids are not identity.
4. *Auto-terminate the session when the backend is lost.* **Rejected:** it discards user progress, and it
   could orphan an ambiguous commit by ending the context that must resolve it.
**SELECTED POLICY:** Pause/block, preserve session identity, preserve transaction truth, wait for the
original backend or an explicit safe termination by the user (`BACKEND_FAILURE_MATRIX.md` §2.1). No
inference of collection equivalence from sync status, ids, counts or names — none of which is even
available as an equivalence input.
**RATIONALE:** This is the semantic core of "hybrid ≠ multi-scheduler": several backends may serve different
sessions or different operations, never one scheduler across two collections.
**TRADE-OFFS:** Sessions can stall during an outage. GATE 22 may define degradation, but it must do so
without making records replayable or permitting substitution.
**FAILURE BEHAVIOR:** Typed failure on the same identity; lock preserved; session resumable only against
the original backend; a session whose backend never returns can be ended explicitly by the user, which
releases the lock without touching any unresolved record.
**AFFECTED COMPONENTS:** Study runtime, session lock tracker, Study UI, recovery blockers, 21G.
**INVARIANTS:** INV-21B-09, INV-21B-08.
**OPEN UNCERTAINTY:** How long a paused session may be held (and whether an "unsafe to continue" state should
ever be shown as terminal) is a product decision owned by 21G/21M, not by this contract.

---

## DECISION-21B-14 — StudySession persistence and restoration

**DECISION ID:** DECISION-21B-14
**QUESTION:** What is the minimum durable session context, in what order is a session restored, and what
happens to a session whose backend or collection cannot be re-established?
**DISCOVERY EVIDENCE:** Ledger `02-11`, `08-08`, `11-xx`; `AnkiSessionContext.kt:10` (plain data class, not
`@Serializable`), `AnkiBackendStatus.kt:128` (in-memory `MutableStateFlow` map), `AppContainer.kt:486-488`
(only ledgers are loaded at startup; study state is not mutated); 21A Safety-Critical Unknown #6
("process restart preserves backend lock" cannot be met for the session today).
**ALTERNATIVES:**
1. *Make the whole session context serializable and restore it as-is.* **Rejected:** restoring a scheduler
   session without revalidating backend identity, capability and collection continuity would be restoring
   *into* a potentially different collection — and it would reintroduce the fail-open identity problem at
   the moment of highest risk.
2. *Persist nothing (the status quo) and declare session durability out of scope.* **Partially accepted:**
   this is the current architecture, and it is recorded as a *dependency/limitation* rather than a
   guarantee; but the contract must still state what durable session context would have to contain so that
   21G cannot invent an unsafe one.
3. *Restore onto whichever backend is currently resolved.* **Rejected:** explicitly prohibited; it is
   restore-time failover.
4. *Keep the session lock in a global singleton so a process restart "finds" it again.* **Rejected:** a
   process cannot retain in-memory state across process death; claiming otherwise would be a false
   guarantee.
**SELECTED POLICY:** Today: a session is **not** restored; only transaction ownership survives process
death, and recovery follows the transaction rules. If 21G adds durable session context, the minimum field
set, restoration ordering and invalidation rules of `BACKEND_OWNERSHIP_INVARIANTS.md` §2.5 apply —
including that `BackendId` is required and non-defaulted, that an undecodable identity invalidates the
session, that a mid-turn session must not silently resume, and that collection mismatch blocks restoration.
**RATIONALE:** Durability must not become a back door around binding revalidation. Stating the future field
set now prevents 21G from persisting "whatever the session object happens to hold", which would freeze
transient facts (capability snapshots, in-flight turn ids) into storage.
**TRADE-OFFS:** Users cannot resume a study session after the process dies; they start a new session while
unresolved transactions recover in the background. Accepted for safety; GATE 22 may revisit degradation.
**FAILURE BEHAVIOR:** Undecodable backend id, unregistered instance, collection mismatch, degraded
assurance, unmet capability or out-of-range schema ⇒ the session record is **invalidated**, never migrated
and never repaired by guessing.
**AFFECTED COMPONENTS:** `AnkiSessionContext`, `AnkiStudyBackendLockTracker`, `AppContainer` startup path,
`StudySessionMachine`/repository, 21G.
**INVARIANTS:** INV-21B-08, INV-21B-02.
**OPEN UNCERTAINTY:** Whether session durability is required at all is a product decision; this gate fixes
only the safety envelope it must satisfy if introduced.

---

## DECISION-21B-15 — Transaction backend ownership

**DECISION ID:** DECISION-21B-15
**QUESTION:** For every transaction family, what is the source of backend truth, and what is explicitly
*not*?
**DISCOVERY EVIDENCE:** Ledger `09-01`…`09-11`; `ReviewCommit.kt:272`, `ReviewerActionRecord.kt:110,147,148`,
`edit/NoteMutationRecord.kt:21`, `create/NoteCreationRecord.kt:47`; structural guards `ReviewCommit.kt:230`,
`AnkiBackend.kt:551`, `AnkiSessionContext.kt:26`; coordinator pinning `NoteMutationCoordinator.kt:151,344`
and `NoteCreationCoordinator.kt:162,277` with `AppContainer.kt:439,470`; GATE 20 absent. Re-verified §0.4
item 12, 14.
**ALTERNATIVES:**
1. *Re-derive the target from the current preference at recovery time.* **Rejected:** it would send a
   mutation written against A into B's collection.
2. *Migrate records when their owner is gone (re-attribution).* **Rejected:** it rewrites ownership without
   evidence and destroys the only record of who owes the mutation.
3. *Keep the note coordinators pinned to one instance.* **Rejected as a target state:** fail-closed today,
   but a record owned by a second backend could not be recovered at all (21A latent defect). 21J/21K must
   resolve by record identity.
4. *Store the backend only in the session context and infer it for records.* **Rejected:** the session lock
   is not durable; record-level ownership is what survives process death.
**SELECTED POLICY:** `TransactionBackend ≡ the backend identity recorded when the operation was admitted`,
never the global preference, an AUTO result after restart, the currently connected backend, or the first
available backend. The five-family matrix with source of truth, recovery destination, cross-backend-retry
prohibition and implementation status is locked in `BACKEND_OWNERSHIP_INVARIANTS.md` §3.2 — including the
explicit statement that the `CustomStudy` row is a **required future contract**, not an existing guarantee,
and that the two note families carry a documented latent gap to be closed by 21J/21K.
**RATIONALE:** Backend ownership must be durable at the granularity of the mutation, because that is the
granularity at which the backend applies it and at which recovery must reason.
**TRADE-OFFS:** Records stay blocked while their owner is unavailable, by design (no expiry, no escalation,
no queue) — carried forward from the parent gate and 21A.
**FAILURE BEHAVIOR:** Unavailable owner ⇒ `Blocked(BACKEND_UNREACHABLE)`; unregistered owner ⇒
`Blocked(BACKEND_NOT_REGISTERED)`; foreign record presented to a pinned coordinator ⇒ refusal
(`BackendMismatch`), never a redirect.
**AFFECTED COMPONENTS:** Four ledgers and their coordinators/reconcilers, `AnkiTransactionRecoveryPlanner`,
21H–21K.
**INVARIANTS:** INV-21B-10, INV-21B-11, INV-21B-12.
**OPEN UNCERTAINTY:** Whether the durable record needs a *capability/version* snapshot for recovery
decisions is deferred to 21H–21K per family.

---

## DECISION-21B-16 — Transaction recovery routing

**DECISION ID:** DECISION-21B-16
**QUESTION:** What is the exact restart/recovery sequence, and what must recovery never do?
**DISCOVERY EVIDENCE:** Ledger `09-08`…`09-11`, `13-03`; `AnkiTransactionRecoveryRouting.kt:44-105`
(`groupByOwnerBackend`, `plan`, per-group `Routed`/`Blocked`, no resolver/preference/mode reference);
`AnkiStudyEffectExecutor.kt:556-567` (blockers before scheduler traffic);
`ReviewCommitReconciliation.kt:82`; `ReviewerActionRecovery.kt:286-320`;
`Gate21TransactionRecoveryRoutingTest` (9 tests, including per-group isolation and
`absent collection identity is recorded honestly and blocks nothing`). Re-verified §0.4 items 13, 20.
**ALTERNATIVES:**
1. *Call AUTO during recovery ("route the record to a usable backend").* **Rejected:** it makes a mutation's
   destination depend on current availability — cross-backend replay by construction.
2. *If the owner is missing, try the ready backend.* **Rejected:** the ready backend never saw the attempt.
3. *Rewrite stored identities during recovery to the current scheme.* **Rejected:** rewriting ownership
   evidence during the act of trusting it is circular and destroys auditability.
4. *Fail the whole recovery pass when one backend is unavailable.* **Rejected:** blocking must be per group;
   a PC outage must not stop AnkiDroid recovery.
**SELECTED POLICY:** The normative sequence and its eight rules in `BACKEND_OWNERSHIP_INVARIANTS.md` §3.3:
load → read persisted identity per record → group by that identity → find that exact registered instance →
validate only the continuity that is checkable → recover through the original family. No AUTO, no identity
rewriting, no replay through another backend, no "reconnect proves non-application", per-group isolation,
ambiguity semantics untouched.
**RATIONALE:** Recovery is the highest-consequence routing decision in the system: it is the only place
where the client acts on state it cannot re-observe. Everything about it must therefore be derived from
persisted evidence, never from live opportunity.
**TRADE-OFFS:** Recovery blocks are potentially long-lived and user-visible. Accepted; the alternative is a
mutation in a collection that never saw it.
**FAILURE BEHAVIOR:** Per-owner `Blocked(reason)`; other groups unaffected; the UI/diagnostics show the
owner and the reason; an ambiguous record keeps its original blocking semantics.
**AFFECTED COMPONENTS:** `AnkiTransactionRecoveryPlanner`, four families' reconcilers/coordinators, startup
recovery path, diagnostics (21M), 21H–21K.
**INVARIANTS:** INV-21B-11, INV-21B-12, INV-21B-10.
**OPEN UNCERTAINTY:** Whether a *registered but never-reachable* owner should eventually be reported
differently from an owner that has never been registered is a diagnostics concern for 21M; both keep the
record blocked.

---

## DECISION-21B-17 — Historical records and migration

**DECISION ID:** DECISION-21B-17
**QUESTION:** What happens to old persisted records that do not carry a sufficiently strong backend or
collection identity?
**DISCOVERY EVIDENCE:** Ledger `09-06`, `02-03`, `04-xx`; `ReviewCommitLedger` decode path
(`:100-155`) requiring `commitId` (which embeds `backendId`) for every supported schema 1..4;
`normalizeLegacyVocabulary` (`:157`) and `migrateLegacy` (`:239`) rewriting *vocabulary*, never identity;
`CommitIdentityTombstone` (`:37-49`) as the only nullable-owner persisted type, repaired by decoding the
identity embedded in `commitKey` (`:138-145, 210-216`); `MULTIPLE_UNRESOLVED_PER_SESSION` and
`duplicate_commit_ids` structural refusals. Re-verified §0.4 item 21.
**ALTERNATIVES:**
1. *Back-fill a collection key for old records from the currently active collection.* **Rejected:** it
   asserts continuity that was never observed, and it erases the record's own honesty about not knowing.
2. *Assign records with an unusable owner to the currently preferred backend.* **Rejected:** that is
   historical re-attribution and would replay a mutation into a collection that never saw it.
3. *Relax decoding so legacy records always load (e.g. default the backend).* **Rejected:** a default turns
   an unreadable record into a wrongly-owned one, which is strictly worse than refusing it.
4. *Re-derive tombstone ownership by heuristic (nearest, most recent, preferred).* **Rejected:** the
   existing derivation is a *decode* from evidence inside the record; a heuristic would be a guess.
5. *Block every legacy record that lacks a collection key.* **Rejected:** that is the normal AnkiDroid case;
   it would block all historical records for no safety benefit.
**SELECTED POLICY:** The case table in `BACKEND_IDENTITY_CONTRACT.md` §6.2 and the must/must-not lists in
§6.3. Keys: a full backend identity is always present in current records; `collectionKey` absence is
honoured as unknown and never back-filled; an absent or undecodable owner makes a record
unrecoverable/`Unreadable` rather than re-attributed; a *degraded* key (known in the record, unknown live)
blocks; ownership derivation is allowed only from evidence the record itself carries; uncertainty evidence
is never erased.
**RATIONALE:** Migration must preserve the original uncertainty, because uncertainty is the only thing that
keeps a mutation from being replayed into the wrong collection. Making old data loadable is not worth
manufacturing ownership for it.
**TRADE-OFFS:** Some historical records may never be recoverable. Accepted explicitly: an unrecoverable
record is surfaced, not guessed at. Note also that the *anticipated* case "legacy record with no backend id"
**does not exist** in these formats — the format has always carried the identity — so this decision closes a
theoretical door rather than migrating real data.
**FAILURE BEHAVIOR:** `Unreadable(<reason>)` at decode time, which blocks only that record/ledger group and
is reported; never an empty ledger and never a default owner.
**AFFECTED COMPONENTS:** All four ledgers and their codecs, tombstones, route tokens, 21C (format
compatibility), 21H–21K.
**INVARIANTS:** INV-21B-10, INV-21B-12, INV-21B-02.
**OPEN UNCERTAINTY:** If a future identity scheme is introduced, whether old records should be *decodable
forever* (locked here: yes) or eventually migrated is a separate schema decision; this contract permits only
additive variants and forbids in-place rewriting.

---

## DECISION-21B-18 — Read context isolation

**DECISION ID:** DECISION-21B-18
**QUESTION:** Who owns Library, Deck Details, Card Browser, Card Details, Model Details and Template
Details, and what happens when the backend changes?
**DISCOVERY EVIDENCE:** Ledger `10-01`…`10-12`, `07-23`…`07-33`; `AppNavHost.kt:81,94,110,142,144`
(pinned Library, Deck Details, Browser, Add-Note seed); `:167-176, :204-222, :248-253` (route-owned Card
Details / Add Note / Edit Note); `AnkiLibraryRepository.kt:193,208-210` (`invalidateIfBackendChanged`,
per-backend cache reuse); `CardBrowserViewModel.kt:62,142,387` (`RequestToken`, `isCurrent`);
`Screen.kt:17,64` (unqualified deck routes). Re-verified §0.4 items 15–17.
**ALTERNATIVES:**
1. *Federate several backends into one library/browser (merged dataset).* **Rejected:** merges two
   collections into one authoritative-looking list and makes every row's backend ambiguous.
2. *Reuse cached data across a switch to avoid a reload flicker.* **Rejected:** it shows another backend's
   rows as if they were the current context's.
3. *Keep the pinned DI injections as the "resolution".* **Rejected:** a pin is a hard-coded backend choice;
   it cannot express a user selection and cannot explain a refusal.
4. *Treat "switching backend" as a property change on the existing context.* **Rejected:** mutation of a
   bound read context is how stale data and reinterpreted refs appear.
**SELECTED POLICY:** One backend per read context; switching **creates a new logical context**; the
invalidation list (results, refs, cursor, counts, model/template cache, in-flight requests, selected deck)
is mandatory; no merging, no cross-backend cache reuse; deck-level route tokens must become
backend-qualified (21F) with the interim rule of DECISION-21B-04. Contract in
`BACKEND_OWNERSHIP_INVARIANTS.md` §4.
**RATIONALE:** Read surfaces are where a user forms their belief about the collection. If a surface can mix
two collections or keep showing one after switching, every downstream decision (study a deck, edit a note)
is made on a false premise.
**TRADE-OFFS:** Switching backends costs a reload. Accepted; a cached foreign row is worse than a spinner.
**FAILURE BEHAVIOR:** A switch invalidates; a failure to load shows a failure state for the *new* context,
not stale rows from the old one.
**AFFECTED COMPONENTS:** `LibraryViewModel` + repository, `DeckDetailsViewModel`, `CardBrowserViewModel`,
`CardDetailsViewModel`, model/template surfaces, navigation, 21F.
**INVARIANTS:** INV-21B-14, INV-21B-03.
**OPEN UNCERTAINTY:** Whether a read context may remember a *preferred* backend per surface (a UX nicety) is
21F's decision, provided the backend is captured at bind time and never re-derived during the context's life.

---

## DECISION-21B-19 — Asynchronous response correlation

**DECISION ID:** DECISION-21B-19
**QUESTION:** How are stale responses rejected when a context's backend changes while requests are in
flight?
**DISCOVERY EVIDENCE:** Ledger `10-10`…`10-12`; `CardBrowserViewModel.kt:62` (`RequestToken(backend.id,
generation, query, cursor)`), `:387` (`isCurrent(token, targetBackend)`);
`AnkiLibraryRepository.kt:193,208-210` (expected-backend guard); `Gate21ArchitectureAuditTest.browser
paging state cannot cross a backend boundary`; `AnkiCardQuery.consistencyKey` (parent contract
DECISION-21-19).
**ALTERNATIVES:**
1. *Rely on request cancellation.* **Rejected:** cancellation is best-effort; a cancelled call can still
   deliver a result, and correctness must not depend on a race being won.
2. *Rely on Compose recomposition.* **Rejected:** recomposition is a rendering concern; a stale value can
   be published before recomposition and can be observed by other consumers.
3. *Order responses by wall-clock arrival.* **Rejected:** a late response from the old backend may arrive
   after a fast response from the new one; ordering by arrival is exactly the bug.
4. *Order by client timestamp of the request.* **Rejected:** clocks are not the issue; identity is. A
   monotonic generation per context is deterministic and testable.
**SELECTED POLICY:** Publish a result only if it is correlated with `(BackendId, collection context, query
identity, query generation, request generation)` — **latest-request-wins with explicit rejection** of
everything else (`BACKEND_OWNERSHIP_INVARIANTS.md` §4.3). Switching backend increments the generation and
invalidates outstanding requests. Compose recomposition is explicitly **not** a correctness mechanism.
**RATIONALE:** Correctness must be a property of the data path, not of rendering timing. The existing
Browser token already implements the required shape and is the model for every other surface.
**TRADE-OFFS:** Some surfaces must add a generation counter and a token check where they currently only
check the backend id. That is a small cost for eliminating an entire class of cross-backend data leakage.
**FAILURE BEHAVIOR:** A stale response is discarded silently for the user and counted for diagnostics; it
never overwrites, never appends, and never partially updates.
**AFFECTED COMPONENTS:** Every read context (21F), repository cache guards, paging adapters, diagnostics.
**INVARIANTS:** INV-21B-14.
**OPEN UNCERTAINTY:** Whether the correlation tuple needs the collection context explicitly (in addition to
the backend id) is decided by 21F; on AnkiDroid that element is currently always "unknown", which is itself
a fact the tuple should record rather than omit.

---

## DECISION-21B-20 — Editor binding

**DECISION ID:** DECISION-21B-20
**QUESTION:** Once an editor has loaded note X from backend A, what happens if the global preference becomes
B? Does the editor continue, block, or require reopening?
**DISCOVERY EVIDENCE:** Ledger `07-30`, `10-xx`, GATE 17 lock; `Screen.kt:42,95-122` (edit route carries the
full qualified card ref); `AppNavHost.kt:248-253` (`find(editCardRef.backendId)`, unregistered ⇒ error
screen); `NoteMutationCoordinator.kt:344` (`BackendMismatch` refusal); `EditNoteMapper.kt:372`; parent
contract DECISION-21-20; `Gate21ArchitectureAuditTest.bound editors and drafts resolve their backend from
the route, not the preference`.
**ALTERNATIVES:**
1. *Continuation against B (redirect Save).* **Rejected:** the base note belongs to A; the same numeric id on
   B is a different note, and the write would land in a collection that never had the base.
2. *Migrate the draft to B by re-resolving the note id on B.* **Rejected:** that is resource-id
   reinterpretation plus draft migration — two prohibited operations in one step.
3. *Block editing whenever the preference differs from the binding.* **Rejected:** it would make a benign
   preference change break an open editor, contradicting the "preference affects future unbound work only"
   rule.
4. *Silently keep the editor open but mark it "read-only" so the user cannot lose the draft.* **Rejected as
   the default:** read-only without explanation hides a failure. Explicit blocking with a reason and a
   preserved draft is the contract.
**SELECTED POLICY:** The editor's backend is the base note's backend for the whole edit-context lifetime.
A preference change has **no effect**. If A becomes unavailable, the editor **blocks** with a typed error
and preserves the draft locally; to edit through B the user must explicitly cancel and open a new edit
context from B's data. Drafts are never migrated; note identities are never re-pointed
(`BACKEND_OWNERSHIP_INVARIANTS.md` §5.1).
**RATIONALE:** A write must land in the collection that supplied its base. Route-carried qualified identity
already makes this enforceable; the contract locks the *user-visible* behavior so 21J cannot "helpfully"
fall back.
**TRADE-OFFS:** A user who changes their mind about where to edit must reopen the note; the alternative is
a silent write into another collection.
**FAILURE BEHAVIOR:** `BackendMismatch`/typed refusal; Save disabled with an explanation; "retry" allowed
against A; nothing written elsewhere; no lock or transaction created for B.
**AFFECTED COMPONENTS:** `EditNoteViewModel`, `NoteMutationCoordinator`, edit route, mapper/UI, 21J.
**INVARIANTS:** INV-21B-15, INV-21B-10.
**OPEN UNCERTAINTY:** Whether the app should offer a one-click "open the same note on B" (a *new* context
with a *new* identity resolution) is a UX decision for 21J; it must be an explicit new binding, never a
migration.

---

## DECISION-21B-21 — Creation binding

**DECISION ID:** DECISION-21B-21
**QUESTION:** What does a creation context bind, what combinations are forbidden, and what happens to an
unsaved draft when the preference changes?
**DISCOVERY EVIDENCE:** Ledger `04-xx`, GATE 18 lock; `Screen.kt:51-57` (`add-note/{backendId}`);
`AppNavHost.kt:94` (seed hard-coded to AnkiDroid), `:204-222` (`find(decodeBackendId)`);
`NoteCreationCoordinator.kt:162,277` (`BackendMismatch`); `NoteCreationBackendResults.kt:68-74,93` (media
request qualified, result bare name); `Gate21ArchitectureAuditTest.bound editors and drafts resolve their
backend from the route, not the preference`. Re-verified §0.4 item 8.
**ALTERNATIVES:**
1. *Read the model schema from A and create on B (schemas "match").* **Rejected:** model ids and field
   layouts are backend-local; a matching name does not make the schema the same, and a failed mapping could
   create a malformed note.
2. *Store media where convenient and create the note elsewhere.* **Rejected:** the note would reference
   media the target collection does not have; media names are not qualified today (no `AnkiMediaRef`).
3. *Auto-recreate an interrupted creation on the other backend.* **Rejected:** a create may have succeeded
   already; recreating elsewhere duplicates the note in a different collection.
4. *Let the preference change retarget the draft.* **Rejected:** the draft's model, deck and media
   destination all belong to A.
**SELECTED POLICY:** A creation context binds a coherent set — backend + collection context + note model +
deck + media destination — and all five must belong to the same backend
(`BACKEND_OWNERSHIP_INVARIANTS.md` §5.2). The four forbidden combinations are locked. A preference change
does not touch the draft; creating on another backend requires a **new** Add Note context (the seed must
come from resolution for that new context — 21K). Unresolved creation transactions stay bound to the
original backend.
**RATIONALE:** A creation is the one operation where the client *creates* a resource; if any element of the
set comes from a different backend, the created note is either malformed or references something that does
not exist in the target collection.
**TRADE-OFFS:** Switching where a new note will be created costs reopening the screen. Accepted.
**FAILURE BEHAVIOR:** Typed refusal (`BackendMismatch` or a capability/readiness refusal); draft preserved;
nothing created on the other backend; media never stored on a backend that will not host the note.
**AFFECTED COMPONENTS:** `AddNoteViewModel`, `NoteCreationCoordinator`, creation route/seed, media store
path, 21K.
**INVARIANTS:** INV-21B-16, INV-21B-10.
**OPEN UNCERTAINTY:** Whether media must be stored *before* the note is created (and therefore needs its own
durable ownership) is a GATE 18 design detail that 21K must confirm; if so, the media operation must carry
the same backend identity and be recoverable under it.

---

## DECISION-21B-22 — Custom Study ownership

**DECISION ID:** DECISION-21B-22
**QUESTION:** How is a filtered/custom-study context bound to a backend, and how do
`StudySessionId`, the backend's filtered-session identity and the scheduler owner relate?
**DISCOVERY EVIDENCE:** Ledger `04-09`, `09-05`; `StudyMode.CUSTOM_SESSION` exists only as a wire enum
(`StudyControlModels.kt:37`) with no client logic; no `AnkiFilteredSessionRef` type exists;
`GATE_20_CUSTOM_STUDY_CONTRACT.md` result **BLOCKED** with the identity question explicitly unresolved
(filtered deck id unavailable from preset CustomStudy; identity by reserved localized name is ambiguous);
parent contract DECISION-21-22.
**ALTERNATIVES:**
1. *Identity the filtered session by its deck name.* **Rejected:** names are localized, user-editable and
   ambiguous — GATE 20 reached the same conclusion.
2. *Treat a filtered deck as an ordinary deck.* **Rejected:** filtered decks have rebuild semantics, a
   preset and a lifespan; treating them as normal decks loses the ownership relationship with the study
   session.
3. *Fall back to normal review when the filtered context is unavailable.* **Rejected:** it silently changes
   what the user is studying and which queue the scheduler is consuming.
4. *Bind at first use rather than at creation.* **Rejected:** the first use is already a bound operation;
   binding must happen before the first scheduler call, as for normal sessions.
**SELECTED POLICY:** `StudySessionId ≠ BackendFilteredSessionId`; the filtered-session reference is a
backend-qualified identity (FUTURE `AnkiFilteredSessionRef`, `BACKEND_IDENTITY_CONTRACT.md` §4.2); per-
operation binding for create / next card / rate / rebuild / end / restore is locked in
`BACKEND_OWNERSHIP_INVARIANTS.md` §6.2; no cross-backend migration; no silent fallback to normal review.
**Status:** **every statement here is a required future contract, not a guarantee.** GATE 20 is unimplemented
and 21L must not implement before it lands.
**RATIONALE:** Custom study changes which cards the scheduler serves. Its identity must therefore be at least
as strong as a normal session's, and its owner must be the same locked backend.
**TRADE-OFFS:** 21L is blocked on 21-20; the alternative (implementing custom study here) is explicitly out
of scope and would require the missing identity mechanism.
**FAILURE BEHAVIOR:** (When implemented) block/pause with a typed reason; never substitute a normal review
queue; never migrate the filtered session.
**AFFECTED COMPONENTS:** Study session machine, study mode handling, a future filtered-session type, 21L.
**INVARIANTS:** INV-21B-17.
**OPEN UNCERTAINTY:** The whole mechanism — including whether a filtered-session identity is obtainable at
all — is open and owned by GATE 20, not by 21B.

---

## DECISION-21B-23 — Reconnect and identity revalidation

**DECISION ID:** DECISION-21B-23
**QUESTION:** What exactly must be true before a reconnected backend may continue the work that was bound to
it?
**DISCOVERY EVIDENCE:** Ledger `11-01`…`11-09`; `AnkiDroidHealthRepository` (`:48` publish-if-current, `:142` `onAppForeground`, `:158` `requestRefresh`) probing and
publishing; `collectionMatchesBoundSession` failing open (`:1336-1341`); `AnkiTransactionRecoveryPlanner`
requiring two known keys to detect a mismatch (`:71`); no Anki-backend reconnect path exists (all
`reconnect` symbols belong to the PC **agent** transport); 21A's explicit distinction
RECONNECT vs SAME BACKEND/COLLECTION VERIFIED; 21A Safety-Critical Unknown #1.
**ALTERNATIVES:**
1. *Treat a successful reconnect as resumption authority.* **Rejected:** it proves reachability, not
   identity, and it would let a collection swap (or a PC agent restarted with a different Anki profile)
   silently continue a session.
2. *Treat a matching `PcAgent(profileId)` as collection equality.* **Rejected:** the profile id is
   configuration-derived; the collection behind it can change (21A case: profile switched on the desktop).
3. *Automatically resume sessions after reconnect.* **Rejected:** resumption is a user decision and must pass
   the assurance matrix.
4. *Expose a single "connected" boolean.* **Rejected:** it collapses R0…R4 into one state and makes the
   distinction invisible in the UI — the failure mode 21A identified.
**SELECTED POLICY:** The five-level ladder R0…R4 and the per-operation-class mandatory conditions in
`BACKEND_FAILURE_MATRIX.md` §3.1–§3.2, plus the six required cases in §3.3 with their conservative
behavior. In particular: reconnect never upgrades assurance; a different verified collection blocks; a
degraded/unknown key follows the assurance matrix; a different configured endpoint is a different instance
unless it is the same configuration record; a removed-and-recreated instance requires continuity to be
re-established; a PC agent restarted with a different Anki profile must be treated as a possible collection
change and never presented as a verified resumption.
**RATIONALE:** Continuity is a claim about a collection, and the only evidence that can support it does not
exist on the only real backend. The contract therefore separates what is provable (transport, authentication,
backend readiness) from what is not (collection continuity) and forbids conflating them.
**TRADE-OFFS:** Users may be asked to restart a session when continuity cannot be established. Accepted: the
alternative is silently studying a different collection.
**FAILURE BEHAVIOR:** Continuation is denied with a typed reason; the session pauses; the binding is not
rewritten; the user may end the session explicitly.
**AFFECTED COMPONENTS:** Health/availability plumbing, session resume path, recovery planner, diagnostics,
UI indicators (21M).
**INVARIANTS:** INV-21B-13, INV-21B-02.
**OPEN UNCERTAINTY:** Whether AnkiDroid exposes any discriminator (device probe) and what the PC agent can
report (no implementation) — both remain open; the policy is written to be correct in either case.

---

## DECISION-21B-24 — Source-of-truth matrix

**DECISION ID:** DECISION-21B-24
**QUESTION:** Which component is authoritative for each concern, and where must there never be two?
**DISCOVERY EVIDENCE:** Ledger `01-xx`…`13-xx` as cited per row; parent contract DECISION-21-23/24/35;
`Gate21ArchitectureAuditTest.the four sources of truth are separate components`.
**ALTERNATIVES:**
1. *Let the status center be authoritative for selection because it already combines everything.*
   **Rejected:** it combines *observations*; making it decide would create a second policy alongside the
   resolver.
2. *Let settings store the list of configured backends.* **Rejected:** settings store a preference; the
   registry stores configuration truth.
3. *Let diagnostics be a data source for decisions.* **Rejected:** diagnostics are a projection; a decision
   that reads a projection can be affected by when the projection was last refreshed.
4. *Let each transaction family keep its own notion of "the current backend".* **Rejected:** that is how a
   second source of truth for ownership appears.
**SELECTED POLICY:** The matrix in `BACKEND_OWNERSHIP_INVARIANTS.md` §8: one authoritative owner per concern
(registry/configuration, settings, resolver, session lock, four record types, backend collection, collection
identity evidence, editor/creation draft state, UI projections, availability, capability claims, recovery
routing).
**RATIONALE:** Ownership is only meaningful if it is unique. The matrix is the checklist that the audit and
the downstream sub-gates test against, and it is what prevents "two independent components authoritative for
the same concern".
**TRADE-OFFS:** New concerns must be assigned explicitly rather than emerging implicitly, which adds a small
documentation cost to every future feature.
**FAILURE BEHAVIOR:** Where a concern is read from a non-authoritative source, the result must be treated as
a projection only (display, preview) and never as a decision input.
**AFFECTED COMPONENTS:** All of the above; 21C–21N as consumers.
**INVARIANTS:** INV-21B-19.
**OPEN UNCERTAINTY:** Where a *future* concern (for example, a durable session record, or media ownership)
belongs is decided by the sub-gate that introduces it, subject to the uniqueness rule.

---

## DECISION-21B-25 — Operation routing matrix

**DECISION ID:** DECISION-21B-25
**QUESTION:** For every operation, what is the required capability, the backend-selection source, the binding
moment, the identity requirement, and whether the backend may change before or after invocation?
**DISCOVERY EVIDENCE:** Ledger `07-xx`, `08-xx`, `09-xx`, `10-xx`; the per-operation call sites cited in
`BACKEND_OWNERSHIP_INVARIANTS.md` §9.
**ALTERNATIVES:**
1. *Decide the backend at the UI layer per screen.* **Rejected:** the UI would become a second resolver, and
   screen code cannot see session or transaction ownership.
2. *Decide at call time from "the current backend".* **Rejected:** there is no such thing; there is a
   preference, a resolution, a session lock and a record owner.
3. *Let operations without a binding choose freely (including mutations).* **Rejected:** every mutation must
   have an admitted owner before it can be recovered.
**SELECTED POLICY:** The full matrix in `BACKEND_OWNERSHIP_INVARIANTS.md` §9 (16 operations × 8 attributes:
required capability, selection source, binding moment, identity requirements, switch-before, switch-after,
failure behavior), with the rule that **every irreversible operation preserves the backend identity under
which it was admitted**.
**RATIONALE:** A single table makes it possible to answer "which backend owns this?" mechanically for every
operation, which is the gate's contract question. It also surfaces operations whose capability is currently
unsatisfiable (`CARD_BROWSER`, `FLAGS`, `MEDIA_READ`) so their future enablement cannot silently introduce a
new selection path.
**TRADE-OFFS:** Operations that currently have no multi-backend path (pinned read contexts) appear as gaps
with named owners rather than as "done"; that is intentional and traceable.
**FAILURE BEHAVIOR:** Per row; uniformly: typed failure on the owned identity, never a substitution.
**AFFECTED COMPONENTS:** All surfaces and features; 21F–21L implement the rows.
**INVARIANTS:** INV-21B-08, INV-21B-10, INV-21B-14, INV-21B-15, INV-21B-16.
**OPEN UNCERTAINTY:** `Set flag` and `Browse cards` are specified before their capability exists; if a future
gate decides not to implement them, the rows remain correct and simply stay unreachable.

---

## DECISION-21B-26 — Failure and failover matrix

**DECISION ID:** DECISION-21B-26
**QUESTION:** For each context, what exactly happens when the original backend is unavailable while another
backend is available?
**DISCOVERY EVIDENCE:** Ledger `07-25`…`07-30`, `11-xx`; parent contract DECISION-21-27/32/38; 21A's
explicit "IMPLICIT_FALLBACK: NONE FOUND" result; `Gate21SessionBackendLockTest.losing the locked backend
blocks the session instead of failing over`.
**ALTERNATIVES:**
1. *Leave the policy as "fallback if possible" wording.* **Rejected:** ambiguous prose is how an implicit
   fallback gets implemented by a later contributor who believes it is intended.
2. *Allow fallback for reads only, silently.* **Rejected:** silent read substitution shows another
   collection's data as if it were the current one.
3. *Allow retry of pending mutations when a different backend is healthy.* **Rejected:** cross-backend
   replay.
4. *Auto-restore the original binding when the original backend returns.* **Rejected:** resumption must pass
   the assurance rules; an automatic restore would bypass them.
**SELECTED POLICY:** The full matrix in `BACKEND_FAILURE_MATRIX.md` §8 with exact result/error/UI semantics
per context, and the single overarching rule: the only permitted fallback is AUTO choosing an instance for a
**new, not-yet-bound** context.
**RATIONALE:** Every fallback decision in this architecture is a decision about *which collection receives a
write or supplies a read*. Ambiguity there is a correctness defect, not a UX detail.
**TRADE-OFFS:** More refusal states surface to users than a permissive design would produce. Each refusal
carries a typed reason so it can be explained rather than merely felt.
**FAILURE BEHAVIOR:** See the matrix; uniformly: preserve state, block, never substitute, never drop.
**AFFECTED COMPONENTS:** Every surface; 21F–21M implement the semantics.
**INVARIANTS:** INV-21B-09, INV-21B-11, INV-21B-12, INV-21B-14.
**OPEN UNCERTAINTY:** None on policy; the UX copy for each refusal is owned by 21M.

---

## DECISION-21B-27 — Diagnostics and privacy contract

**DECISION ID:** DECISION-21B-27
**QUESTION:** What metadata may diagnostics carry about backend routing, and what must never appear?
**DISCOVERY EVIDENCE:** Ledger `13-06`; `AnkiBackendPreference.kt:41-57` (four roles + availability map),
`DiagnosticsRepository.kt:594-605` (one row per role, stable tokens), `DiagnosticsExportPrivacyTest`
(existing no-secret-in-export test), `AnkiBackendDescriptor.kt` (descriptor carries no host/port/token);
`AnkiAvailability.statusCode` (stable tokens).
**ALTERNATIVES:**
1. *Include endpoints/hosts for supportability.* **Rejected:** endpoints are not needed to answer "which
   backend owned this", and they leak infrastructure detail (and potentially credentials embedded in URLs).
2. *Include card/note content in routing diagnostics.* **Rejected:** routing diagnostics must be
   content-free; content belongs to the domain, not to routing.
3. *Create a new diagnostics format for backend routing.* **Rejected:** a second format would drift from the
   existing one and duplicate the four roles.
4. *Log an unverified collection as "matched" to reduce noise.* **Rejected:** falsifying assurance in logs is
   the most dangerous possible convenience.
**SELECTED POLICY:** The allowed metadata list and the prohibited list in `BACKEND_FAILURE_MATRIX.md` §9,
plus: identity is always the `stableId`; assurance categories are logged verbatim including
`IDENTITY_UNVERIFIABLE`; error classification uses the existing typed vocabulary and stable status codes;
retention/export follow existing conventions and extend the existing privacy test rather than adding a
parallel one.
**RATIONALE:** Diagnostics are the only way a user or supporter can answer "which backend did this?", and
that answer must be reconstructible without content or secrets. The existing four-role layout is already the
right shape.
**TRADE-OFFS:** Support cases may need the user to describe their configuration manually, because raw
endpoints are not logged. Accepted.
**FAILURE BEHAVIOR:** A diagnostics write that would include a prohibited item must be refused at the
formatting boundary (the same way content-free tokens are produced today), not filtered later.
**AFFECTED COMPONENTS:** `AnkiBackendRoutingDiagnostics`, `DiagnosticsRepository`, export path, 21M.
**INVARIANTS:** INV-21B-19; and the parent gate's privacy rules.
**OPEN UNCERTAINTY:** Whether an assurance category should also be exported in a machine-readable form for
field support is a 21M packaging decision.

---

## DECISION-21B-28 — Security preconditions

**DECISION ID:** DECISION-21B-28
**QUESTION:** How does backend resolution preserve backend-specific trust requirements?
**DISCOVERY EVIDENCE:** Ledger `12-01`…`12-xx`; `AnkiDroidPermissionManager` + `AnkiDroidDetector`
(permission + package detection), `AnkiDroidEndpoints.forBuild(includeDebugEndpoints = BuildConfig.DEBUG)`
(`AnkiDroidApiContract.kt:790,814`), `AnkiDroidWritePermit` (single physical-write permit);
`AnkiAvailability.PermissionRequired`; PC trust model **absent** (`grep -rin "anki" core/models/ProtocolMessages.kt`
→ empty).
**ALTERNATIVES:**
1. *Select on transport reachability (the socket answers ⇒ usable).* **Rejected:** reachability is not
   authentication, authorization or readiness; and an unauthenticated remote backend is exactly the case
   where a privileged operation must not be attempted.
2. *Treat "same local network" as authorization for the PC backend.* **Rejected:** network location is not a
   credential, and the agent protocol must authenticate explicitly.
3. *Skip protocol/version negotiation and rely on capability flags alone.* **Rejected:** a capability claim
   from an unnegotiated protocol version is unverifiable.
4. *Keep the AnkiDroid write permit only for AnkiDroid and let a PC backend write freely.* **Rejected:** the
   PC path must define its own serialization/authorization; "another backend, so no rules" is not a policy.
**SELECTED POLICY:** The precondition table in `BACKEND_FAILURE_MATRIX.md` §10, enforced through the
readiness ladder: R2 for AnkiDroid already requires provider + collection readiness (which itself requires
the integration permission), and for a PC backend R2 must additionally require authentication, authorization
and readiness of the Anki backend beneath the agent. A reachable-but-unauthenticated backend is at R0/R1 and
is therefore ineligible for every operation. No device-local assumption may substitute for authorization.
**RATIONALE:** Selection is a security decision: it decides which collection a privileged operation touches.
The readiness ladder is the enforcement point that already exists in the code, so extending it is safer than
adding a parallel permission check that could be bypassed by a different call path.
**TRADE-OFFS:** A PC backend cannot be selected at all until authentication exists — which is correct, since
no PC backend exists.
**FAILURE BEHAVIOR:** `AuthenticationRequired` / `PermissionRequired` outcomes; no privileged operation is
attempted; no credential is logged (DECISION-21B-27).
**AFFECTED COMPONENTS:** Availability model, health/probe path, endpoint allowlist, write permit, future PC
transport and auth, 21D/21H/21M.
**INVARIANTS:** INV-21B-07; parent-gate security invariants.
**OPEN UNCERTAINTY:** The PC authentication/authorization/transport-security design does not exist and is not
authored here; it is a precondition of any PC backend becoming selectable.

---

## DECISION-21B-29 — Architecture compatibility

**DECISION ID:** DECISION-21B-29
**QUESTION:** Do the locked contracts contradict the implemented architecture or the locked requirements of
GATE 11–20, and where are those requirements design-only?
**DISCOVERY EVIDENCE:** §0.5 (Gate status table) plus the per-decision evidence; `docs/GATE_11_RATING_COMMIT.md`
("BLOCKED — real mutation verification not run"), `GATE_13` (IMPLEMENTED AND WIRED),
`GATE_15` ("OPEN — NOT LOCKED"), `GATE_16` ("BLOCKED — do not mark locked yet"),
`GATE_17`/`GATE_18`/`GATE_19` (LOCKED), `GATE_20` ("RESULT: BLOCKED; IMPLEMENTATION AUTHORIZED: NO"),
`GATE_21` (LOCKED contract + implementation report).
**ALTERNATIVES:**
1. *Assert full backward compatibility with every earlier gate.* **Rejected:** it would claim compatibility
   with code that does not exist (PC backend, custom study) and with verification that was never performed
   (GATE 11 device verification, GATE 16 lock).
2. *Ignore earlier gate status and treat features as implemented.* **Rejected:** the entire point of a
   contract lock is to state what it can rely on.
3. *Re-open earlier gates to fix their status here.* **Rejected:** out of scope; 21B is architecture-only and
   must not modify other gates' verdicts.
**SELECTED POLICY:** Compatibility is asserted only against what exists, and every reliance on a design-only
or blocked dependency is recorded as such (compatibility table below).
**RATIONALE:** A contract that assumes nonexistent behavior fails silently at exactly the moment it is
relied upon. Naming the dependencies makes the failure loud and locatable.

| Checked item | Status at this HEAD | 21B position |
|---|---|---|
| `StudySessionMachine` / session ownership | IMPLEMENTED (machine + reducer + executor); the session lock itself is process-memory | Compatible; DECISION-21B-12/14 lock the ownership rule and record the durability limitation |
| ReviewCommit status/recovery | IMPLEMENTED; gate result BLOCKED (device verification) | Compatible; no exactly-once *device* claim is made by 21B |
| ReviewerAction status/recovery | IMPLEMENTED AND WIRED | Compatible; per-group recovery preserved |
| NoteMutation semantics | IMPLEMENTED (GATE 17 LOCKED) | Compatible; coordinator pinning recorded as a latent gap for 21J |
| NoteCreation semantics | IMPLEMENTED (GATE 18 LOCKED) | Compatible; same latent gap for 21K |
| Custom Study scheduler authority | **NOT IMPLEMENTED (GATE 20 BLOCKED)** | 21B states a FUTURE CONTRACT only; 21L may not implement before GATE 20 |
| Library / browser query isolation | IMPLEMENTED for a single backend (cache guard + request token); read contexts are **pinned** | Compatible as a baseline; widening is 21F's job under DECISION-21B-18/19 |
| `AppContainer` / DI ownership | IMPLEMENTED (composition root; one-entry registry) | Compatible; DECISION-21B-10 extends it and forbids a second registry |
| Existing settings/connection infrastructure | IMPLEMENTED for the agent transport (AI/TTS/STT) and for the backend **mode** preference | Compatible; DECISION-21B-28 records that this is *not* an Anki-backend trust model |

**TRADE-OFFS:** 21B declines to promise session durability or PC-backend compatibility, which may read as
incomplete; the alternative would be a guarantee that cannot be honoured.
**FAILURE BEHAVIOR:** If a later sub-gate finds that a dependency listed here as design-only has changed,
the affected decision must be revalidated before it is relied upon.
**AFFECTED COMPONENTS:** All downstream sub-gates.
**INVARIANTS:** INV-21B-20.
**OPEN UNCERTAINTY:** Whether GATE 20 and the PC backend land before 21J–21L is a scheduling question outside
this gate.

---

## DECISION-21B-30 — Contract consistency review

**DECISION ID:** DECISION-21B-30
**QUESTION:** Do all thirty decisions, the failure matrices and the ownership rules produce compatible
answers for the same scenario?
**DISCOVERY EVIDENCE:** The fifteen decisions above plus the six companion matrices; the audit and
verification sections of this document.
**ALTERNATIVES:**
1. *Lock without a cross-consistency pass.* **Rejected:** contradictions between individually coherent
   decisions are exactly what a contract lock exists to catch.
2. *Resolve contradictions by loosening a safety rule.* **Rejected:** every answer below is resolved toward
   the stricter rule; loosening would weaken a parent-gate invariant (INV-21B-20).
**SELECTED POLICY:** The twelve mandatory questions, answered unambiguously (table below), plus the rule
that where two decisions disagree the stricter one wins and this review must be re-run before the gate is
relied upon.
**RATIONALE:** Individually coherent decisions can still contradict each other at their boundaries — in
this contract the boundaries are precisely where safety lives: pre-binding versus post-binding, session
versus transaction, resolution versus continuation, nominal versus degraded. A cross-consistency pass is
the only mechanism that tests those seams, and it is also what turned four underspecified readings into
explicit stricter rules rather than leaving them to a future implementer's judgement.

| # | Mandatory question | Answer | Authority |
|---|---|---|---|
| 1 | Can AUTO select a backend **after** a StudySession lock? | **No.** AUTO runs only pre-binding; the session lock is immutable and post-binding operations resolve from owned identity. | DECISION-21B-06, 21B-12; INV-21B-04 |
| 2 | Can manual mode **silently** fall back? | **No.** Manual selection is honoured exactly or refused with a typed reason. | DECISION-21B-07; INV-21B-05 |
| 3 | Can transaction recovery use the **current preference**? | **No.** Recovery reads the identity persisted in the record; no resolver, no preference, no mode. | DECISION-21B-16; INV-21B-11 |
| 4 | Can an **unknown** collection be described as **verified**? | **No.** Unknown is `IDENTITY_UNVERIFIABLE` and is surfaced verbatim; `SESSION_CONTINUITY_VERIFIED` is explicitly not collection verification. | DECISION-21B-02/03; INV-21B-02 |
| 5 | Can equal numeric card ids establish **equal resources**? | **No.** Identity includes backend (+ collection context); numbers are backend-local. | DECISION-21B-04; INV-21B-03 |
| 6 | Can a session use **both** AnkiDroid and PC as scheduler owners? | **No.** One immutable Anki scheduler owner per session. The PC agent may serve AI/TTS/STT concurrently, which is not scheduler authority. | DECISION-21B-12/13; INV-21B-08, INV-21B-18 |
| 7 | Can an editor Save against a **different** backend than its base? | **No.** The editor is bound to the base note's backend; a preference change has no effect; changing the target requires a new context. | DECISION-21B-20; INV-21B-15 |
| 8 | Can media upload and note creation target **different** backends? | **No.** Both belong to the creation context's single backend binding. | DECISION-21B-21; INV-21B-16 |
| 9 | Can PC AI evaluation be used while AnkiDroid owns scheduling? | **Yes** — and it is not hybrid scheduler authority: PC services are keyed by the agent connection, are never an input to Anki resolution, and neither service's availability changes the other. | DECISION-21B-12; INV-21B-18 |
| 10 | Can backend loss convert an **ambiguous mutation** into a **safe retry**? | **No.** Ambiguity is preserved; connectivity is not proof; only the original family's mechanism, against the original backend, may resolve it. | DECISION-21B-03/16, failure matrix §5; INV-21B-12 |
| 11 | Can a **model/template cache** from A be reused against B? | **No.** Model/template identity is backend-qualified, caches are per-backend, and switching invalidates them. | DECISION-21B-04/18; INV-21B-14 |
| 12 | Can a legacy transaction be migrated **without evidence** of its owner? | **No.** Ownership comes only from evidence inside the record; an unknown owner stays unknown/unrecoverable and is never assigned to the current preference. | DECISION-21B-17; INV-21B-10, INV-21B-12 |

**Result: no contradiction found.** Where two decisions touched the same scenario they produced the same
(stricter) answer, and in four places the stricter answer was adopted during this review:

1. The `TEST_DOUBLE` fallback branch in `AnkiBackendResolver` is stated as *never selectable by AUTO*
   (DECISION-21B-06 clarification 1) instead of being left as an unreachable implementation detail.
2. A key that *appears* for a binding that had none is **degraded**, not nominal
   (`BACKEND_IDENTITY_CONTRACT.md` §3.5 third branch).
3. `IDENTITY_UNAVAILABLE` was moved out of the resolution-result vocabulary into the binding verdict
   (DECISION-21B-08 alternative 4).
4. Capability **gain** must not retroactively enable a path refused at binding time (DECISION-21B-09 /
   failure matrix §2.2).

**TRADE-OFFS:** The stricter readings make a few states less convenient (a test-double-only build, a
backend that starts reporting a key). Both are acceptable: neither occurs in production today, and both are
safer to have fail closed.
**FAILURE BEHAVIOR:** If a future sub-gate finds a scenario where two decisions disagree, the stricter
decision wins and this review must be re-run before the gate is relied upon.
**AFFECTED COMPONENTS:** All.
**INVARIANTS:** All twenty.
**OPEN UNCERTAINTY:** None affecting the lock.

# PART 2 — ARCHITECTURE AUDIT

The audit judges the contract, not the code. No production file was read in order to change it, and none
was changed.

| Audit | Result | Evidence and findings |
|---|---|---|
| **AUDIT-21B-01 — Discovery Traceability** | **PASS** | Every normative decision cites either GATE 21A evidence (ledger IDs `02-01`…`13-06`, file:line, re-verified in §0.4) or an explicitly declared product-level policy choice. The declared policy choices are: the `LOCAL_DEVICE`-first AUTO preference (inherited from the parent gate, DISCOVERY-21-32 records that no latency measurement exists), the honest-refusal decision for `CARD_BROWSER`/`MEDIA_READ`/`FLAGS`/`DECK_COUNTS` (DECISION-21B-09), the "no durable session today; envelope only if 21G adds one" position (DECISION-21B-14), and the privacy vocabulary (DECISION-21B-27). No decision invents a finding; the four absent artefacts (PC backend, media ref, filtered-session ref, PC identity allocation) remain absent. |
| **AUDIT-21B-02 — Identity Consistency** | **PASS** | Three identity layers are kept apart and never conflated: backend (`AnkiBackendId`, `BACKEND_IDENTITY_CONTRACT.md` §1), collection (`AnkiCollectionIdentity` + assurance categories, §2–§3), resource (backend-qualified refs, §4). Kind, runtime object and display name are explicitly non-identities (INV-21B-01). Collection identity is never derived from backend identity, and resource identity never from an unqualified numeric id. Failure states have distinct names (`BACKEND_NOT_REGISTERED` vs `BACKEND_UNREACHABLE` vs `COLLECTION_MISMATCH`; `AUTHORITATIVE_MISMATCH` vs degraded vs nominal `IDENTITY_UNVERIFIABLE`). |
| **AUDIT-21B-03 — AUTO Determinism** | **PASS** | `BACKEND_SELECTION_POLICY.md` §3.3 gives total pseudocode; §3.4 gives the six-level precedence with a total final tie-break (`orderingKey`). Explicit negatives: no timing, no latency, no registry order, no "first to answer", no iteration. Every branch returns exactly one instance or a typed failure. The parent gate's determinism tests are identified by name (their *re-execution* is not claimed — see §4 note). |
| **AUDIT-21B-04 — Manual Selection** | **PASS** | No silent fallback in any branch (§4.2 table): not for unavailability, capability mismatch, authentication or permission. Explicitly excluded: writing the preference on failure, publishing a lock on a refused start, retrying through another instance, "auto-switch back". Ambiguity under manual selection refuses rather than choosing. |
| **AUDIT-21B-05 — Session Immutability** | **PASS** | One owner per session-bound operation, enumerated operation by operation (`BACKEND_OWNERSHIP_INVARIANTS.md` §2.3): next card, hydration, commit, bury/suspend, flag (capability-gated), end review, custom study (future). Lock publication only on success; release only on end. The durability limitation is stated rather than hidden (§2.4). |
| **AUDIT-21B-06 — Transaction Ownership** | **PASS** | All five families have a declared source of truth, recovery destination and cross-backend-retry prohibition; the two note families' latent pinning gap and the custom-study absence are named as gaps with owning sub-gates rather than as guarantees (§3.2). Recovery routing rules include the four hard prohibitions (no AUTO, no identity rewrite, no collection rewrite, no cross-backend replay). |
| **AUDIT-21B-07 — Reconnect Safety** | **PASS** | The R0–R4 ladder separates transport, authentication, backend readiness, continuity and restoration permission, and states that each implies only itself. All six required reconnect cases are answered conservatively (§3.3), including the two that cannot be detected today (AnkiDroid profile change, PC profile change), which are answered with explicit non-claims and carried as residual risk. Reconnect never upgrades assurance and never converts ambiguity into retry. |
| **AUDIT-21B-08 — Resource Qualification** | **PASS** | Six reference types have locked qualification; two of them are explicitly FUTURE with the qualification requirement stated before they exist. Seven prohibited practices are enumerated, including card↔note confusion and reinterpretation during navigation. The unqualified deck-route gap has an interim rule that is deterministic and cannot consult the preference. |
| **AUDIT-21B-09 — Read Context Isolation** | **PASS** | One backend per read context; switching creates a new context; an eight-item invalidation list; explicit rejection of stale responses by a five-element correlation tuple with latest-request-wins; Compose recomposition and best-effort cancellation are explicitly excluded as correctness mechanisms. Current pinned surfaces are named as gaps with owner 21F instead of being presented as resolved. |
| **AUDIT-21B-10 — Edit/Creation Binding** | **PASS** | Editors: bound to the base note's backend; continue/block/reopen options are decided (continue with A, block if A is unavailable, reopen to use B), and draft/identity migration is prohibited. Creation: five-element coherent binding set, four forbidden cross-backend combinations, unresolved creation records stay with the original backend, media must match the note's backend. |
| **AUDIT-21B-11 — Custom Study Ownership** | **PASS** | `StudySessionId ≠ BackendFilteredSessionId` is locked as a distinction; binding sources for create/next/rate/rebuild/end/restore are stated; cross-backend migration and silent fallback to normal review are prohibited. The whole section is marked as a required future contract with GATE 20 BLOCKED and 21L prohibited from starting. |
| **AUDIT-21B-12 — Source-of-Truth Separation** | **PASS** | The matrix assigns exactly one authority per concern and simultaneously states what each authority must *not* own (registry, resolver, settings, session lock, four record types, availability, capability claims, recovery routing, UI projections). The status center is classified as an observation point, not a decision source. |
| **AUDIT-21B-13 — Security** | **PASS** | The security preconditions table preserves the existing AnkiDroid trust path (integration permission, endpoint allowlist, single write permit) and requires authentication/authorization/negotiation/readiness for any PC backend before it may be selected. "Transport reachable" is explicitly insufficient for a privileged operation, and no device-local assumption may substitute for authorization. Diagnostics are constrained so that a status refresh cannot leak or require credentials. |
| **AUDIT-21B-14 — Compatibility** | **PASS** | §0.5 and DECISION-21B-29 state, gate by gate, what is implemented (01–10, 12, 13, 14, 17, 18, 19, 21), what is implemented but not verified (11, 16), what is open (15), and what does not exist (20 custom study, PC backend, media ref, filtered-session ref). No backward-compatibility claim is made about nonexistent code, and no earlier gate's verdict is altered. |
| **AUDIT-21B-15 — No Implementation Leakage** | **PASS** | Only `docs/` files were created or modified. No Kotlin file, no Gradle file, no persistence schema, no migration and no runtime behavior was touched. No interface, class, enum or type is defined in Kotlin; the four assurance categories and the eight semantic outcomes are explicitly conceptual and are left for 21C/21E to encode. No file path is asserted as a confirmed future repository path (see PART 4). The pre-existing working tree was clean at the start of this gate and remains confined to `docs/`. |

---

# PART 3 — AUTHORITATIVE CONTRACT VERIFICATION

These are **architectural contract checks** (deterministic decision tables and scenario walkthroughs), not
runtime verification. GATE 21B ran no build, no device and no backend. Where a scenario is already covered
by an existing test, the test is named as *pre-existing evidence for the behavior the contract relies on*;
the test was **not** re-executed in this gate (no JDK/Android SDK is available in the sandbox — the same
limitation GATE 21A recorded).

| Verification | Scenario | Expected (contract) | Contract answer / evidence |
|---|---|---|---|
| **VER-21B-01 — Manual Selection** | Explicit A selected; A unavailable; B available | No fallback | `ExplicitBackendUnavailable` with A's typed reason; preference untouched; no lock published; no session started (DECISION-21B-07; parent test `manual selection of an unavailable backend blocks instead of falling back`). |
| **VER-21B-02 — AUTO Selection** | Identical availability, capability and preference inputs, repeated | Deterministic | Total ordering `(locality class, kind ordinal, stableId)`; identical inputs ⇒ identical result in any registry order (DECISION-21B-06; `orderingKey`). |
| **VER-21B-03 — Capability-Constrained Selection** | A lacks the required capability; B has it | A ineligible | A is filtered at step 2; if no capable candidate remains: `RequiredCapabilityUnavailable` with per-candidate missing sets (DECISION-21B-06/09). |
| **VER-21B-04 — Session Lock** | Session created with A | Later scheduler operations cannot route to B | Every session-bound operation resolves from the session's own identity; a fresh resolution inside the runtime is prohibited (DECISION-21B-12; `AnkiStudyEffectExecutor` sites `:508, :513, :628, :664-666, :804`). |
| **VER-21B-05 — Preference Change** | Preference A→B during a live session | Active session owner unchanged | The preference is read only pre-binding; the lock is not rewritten; the Study indicator reads the lock (DECISION-21B-12; `StudyViewModel.lockedAnkiBackendLabel`; `Gate21SessionBackendLockTest`). |
| **VER-21B-06 — Backend Loss** | A goes offline; B ready | Session blocked/paused, not migrated | Typed failure on A's identity; lock preserved; resumption only against A (DECISION-21B-13; failure matrix §2.1). |
| **VER-21B-07 — Rating Recovery** | Ambiguous rating on A across restart | Still owned by A | Grouped by `commitId.backendId`; `Blocked(BACKEND_UNREACHABLE)` while A is down; `AMBIGUOUS` preserved (DECISION-21B-16; failure matrix §5). |
| **VER-21B-08 — Reviewer Action Recovery** | Unresolved bury/suspend on A | Cannot replay through B | `ReviewerActionRecord.backendId` ownership; per-group blocking; a foreign card/collection is refused (DECISION-21B-15/16). |
| **VER-21B-09 — Note Edit Binding** | Draft created from A; preference now B | Cannot submit to B | Editor bound to the base note's backend; `BackendMismatch` refusal if the target differs; draft preserved (DECISION-21B-20). |
| **VER-21B-10 — Note Creation Binding** | Model, deck, media, note creation | One backend context | Five-element coherent binding; four forbidden combinations prohibited (DECISION-21B-21). |
| **VER-21B-11 — Custom Study** | Filtered session identity and scheduler authority | Backend-locked | `StudySessionId ≠ BackendFilteredSessionId`; one scheduler owner; no migration; no fallback to normal review (DECISION-21B-22 — future contract, GATE 20 blocked). |
| **VER-21B-12 — Numeric ID Collision** | A.cardId = 123 and B.cardId = 123 | Different logical resources | Qualification by backend (+ collection context) makes them distinct in `stableKey`; equivalent reasoning applies to note/deck/model ids (DECISION-21B-04; `AnkiArchitectureContractTest`). |
| **VER-21B-13 — Verified Collection Mismatch** | Reconnect to a provably different collection | Bound work blocked | `AUTHORITATIVE_MISMATCH` ⇒ block continuation, invalidate read contexts, `Blocked(COLLECTION_MISMATCH)` for affected groups (DECISION-21B-02; parent test `a reconnected backend on a different collection stays blocked`). |
| **VER-21B-14 — Unverifiable Collection** | No key on either side | No invented match | Nominal `IDENTITY_UNVERIFIABLE`: permitted operations follow the §3.3 matrix; nothing is described as verified; degraded variant blocks (DECISION-21B-02/03). |
| **VER-21B-15 — Cross-Backend Read Switch** | A's pending response returns after switching to B | A's result cannot become authoritative | Correlation tuple mismatch ⇒ discard (latest-request-wins). Cancellation and recomposition are explicitly not relied upon (DECISION-21B-19). |
| **VER-21B-16 — Multiple Recovery Backends** | Unresolved records for A and B | Independently routed | `plan()` returns one route per owner identity; `Blocked` is per group (DECISION-21B-16; parent tests `a missing backend blocks only its own group…`, `an unreachable backend keeps its transactions blocked while another backend is ready`). |
| **VER-21B-17 — Historical Identity** | Legacy record with insufficient ownership evidence | Not assignable to the current preference | Ownership derives only from evidence inside the record; otherwise `Unreadable`/unrecoverable (DECISION-21B-17). |
| **VER-21B-18 — PC Service Separation** | AnkiDroid owns the scheduler; PC provides AI/TTS | Permitted, no lock violation | PC services are keyed by the agent connection, never an `AnkiBackendId`; not an input to resolution; neither service's availability changes the other (DECISION-21B-12; ownership invariants §7). |
| **VER-21B-19 — Security Preconditions** | Backend reachable but unauthorized | Ineligible for operations requiring authorization | R0/R1 ≠ R2; a privileged operation requires authenticated + authorized + ready; `AuthenticationRequired`/`PermissionRequired` (DECISION-21B-28). |
| **VER-21B-20 — Full Contract Consistency** | All decisions, matrices and ownership rules applied to one scenario set | Compatible results | The twelve mandatory questions in DECISION-21B-30 answer consistently, and the four places where a stricter reading was needed were resolved toward the stricter reading. |

**Result: VER-21B-01 … VER-21B-20 all PASS as architectural contract checks.**

**Explicit non-claim.** These checks do not prove any future Kotlin implementation, and they are not a
re-execution of the existing test suite. The pre-existing JVM-harness evidence for the parent gate
(6 classes / 56 tests) and the 21A source audit remain the only executed evidence in this repository; the
present gate adds *contract* verification only.

---

# PART 4 — IMPLEMENTATION HANDOFF SPECIFICATION

This section describes future responsibilities. **It creates no file and no type.** No path below is
asserted to exist or to be the confirmed location of future work; suggested locations are named only where
the component already exists today, and everything else is described by responsibility.

**Fixed ownership map**

| Sub-Gate | Contract responsibility |
|---|---|
| 21C | Identity and resource qualification |
| 21D | Registry, availability, capabilities |
| 21E | Preference and deterministic resolver |
| 21F | Read-context isolation |
| 21G | StudySession immutable backend |
| 21H | Rating routing/recovery |
| 21I | Reviewer action routing/recovery |
| 21J | Note editing binding |
| 21K | Note creation binding |
| 21L | Custom Study binding |
| 21M | UI indicators and diagnostics |
| 21N | Final audit and verification |

---

### 21C — Identity and resource qualification

- **REQUIRED DECISIONS:** how the four assurance categories and the two sub-qualifiers are encoded; whether `SESSION_CONTINUITY_VERIFIED` is composed with nominal unverifiability or represented as two orthogonal facts; whether `BindingVerdict` is a distinct type from `BackendResolutionResult`; how `AnkiMediaRef` and `AnkiFilteredSessionRef` are shaped (without implementing their features); the unit and shape of the collection-key contract.
- **INPUT CONTRACT:** `AnkiBackendId`, `AnkiCollectionIdentity`, the existing ref types, the assurance computation algorithm (`BACKEND_IDENTITY_CONTRACT.md` §3.5) and the operation-by-assurance matrix (§3.3).
- **OUTPUT CONTRACT:** encoded identity/assurance types + the qualification rules applied to every reference type and every persisted field; no behavior change to routing, sessions or mutations.
- **EXISTING COMPONENTS TO REUSE:** `core/anki/AnkiRefs.kt`, `AnkiNoteModels.kt`, `AnkiTemplateModels.kt`, `AnkiBackendId.kt` (extend, never replace), `AnkiSessionContext`.
- **EXPECTED INTEGRATION BOUNDARIES:** identity types are consumed by ledgers, routes, the resolver and diagnostics; no identity type may reach into scheduler or transaction logic.
- **DEPENDENCIES:** DECISION-21B-01…04, 21B-17; parent gate DECISION-21-01…03.
- **BLOCKERS:** none. PC identity allocation rules (A1–A6) must be *stated* here but can only be *exercised* in 21D.

### 21D — Registry, availability, capabilities

- **REQUIRED DECISIONS:** the concrete PC instance allocation and registration mechanism satisfying A1–A6; how availability snapshots are produced for a PC backend (including authentication and backend readiness as distinct states); how capability claims are versioned and invalidated; how dynamic registration/removal is handled without a mutable "current backend" pointer; whether `descriptors()` remains the single snapshot source.
- **INPUT CONTRACT:** the registry ownership boundary (`BACKEND_OWNERSHIP_INVARIANTS.md` §1.1), the readiness ladder R0–R2 (`BACKEND_SELECTION_POLICY.md` §2), the capability three-way distinction (§2.1).
- **OUTPUT CONTRACT:** registry extended to N instances with capability/readiness snapshots; the security preconditions of DECISION-21B-28 enforced at snapshot production; no routing policy inside the registry.
- **EXISTING COMPONENTS TO REUSE:** `AnkiBackendRegistry`, `AnkiBackendStatusCenter`, `AnkiAvailability` (11 states, 3 already reserved for PC), `AnkiDroidHealthRepository` as the shape for a PC health source, `AnkiBackendDescriptor`.
- **EXPECTED INTEGRATION BOUNDARIES:** composition root registers; everything else looks up by identity. Registration must not become a runtime routing event.
- **DEPENDENCIES:** DECISION-21B-01, 21B-09, 21B-10, 21B-28; parent DECISION-21-23.
- **BLOCKERS:** the PC Anki backend does not exist. Until it does, 21D can only prepare registry/availability structure for a second instance; it must not fabricate a PC `Ready` state.

### 21E — Preference and deterministic resolver

- **REQUIRED DECISIONS:** whether an instance pin is added to the persisted preference and in what format; whether `AUTHENTICATION_REQUIRED` becomes its own resolution variant; whether the `TEST_DOUBLE` fallback branch is removed or made an explicit refusal; how `BackendResolutionRequest` carries readiness class and purpose; whether `resolveFor` remains or is folded into one façade.
- **INPUT CONTRACT:** the exact AUTO algorithm and precedence (`BACKEND_SELECTION_POLICY.md` §3), the eight semantic outcomes (§5.1), the manual-selection behavior table (§4.2).
- **OUTPUT CONTRACT:** one pure resolution policy, deterministic and total, returning one instance or a typed failure; preference storage backward compatible; no resolution reachable from a bound context.
- **EXISTING COMPONENTS TO REUSE:** `AnkiBackendResolver`, `AnkiBackendSelector`, `BackendResolutionResult`, `BackendResolutionRequest`, `AnkiBackendPreferenceStore` + the settings codec.
- **EXPECTED INTEGRATION BOUNDARIES:** session start and every future pre-binding entry point (read contexts, editor/creation seeds) call the same policy; recovery never calls it.
- **DEPENDENCIES:** DECISION-21B-05…08, 21B-11; parent DECISION-21-24…27.
- **BLOCKERS:** instance-pin semantics are only exercisable with a second instance (21D).

### 21F — Read-context isolation

- **REQUIRED DECISIONS:** how a read context acquires its backend (resolution vs route vs pin removal); how deck-level routes become backend-qualified (token format and migration from the current raw `deckId`); the cache/query-generation key shape per surface; how capability refusals are presented for `CARD_BROWSER`; whether per-surface backend memory is allowed.
- **INPUT CONTRACT:** DECISION-21B-18/19, the invalidation list and correlation tuple, the interim deck-token rule, the honest-refusal policy for unsatisfiable capabilities.
- **OUTPUT CONTRACT:** every read surface binds exactly one backend, invalidates on switch, rejects stale responses, and never merges datasets; a capability refusal is explainable.
- **EXISTING COMPONENTS TO REUSE:** `LibraryViewModel`/`AnkiLibraryRepository` (cache guard), `DeckDetailsViewModel`, `CardBrowserViewModel` (`RequestToken`, `isCurrent`), `CardDetailsViewModel` and model/template surfaces (already route-bound), `Screen.kt` route encoders.
- **EXPECTED INTEGRATION BOUNDARIES:** navigation passes identity; view models never resolve; repositories never cross backends.
- **DEPENDENCIES:** DECISION-21B-04, 21B-18, 21B-19; parent DECISION-21-17…19.
- **BLOCKERS:** `CARD_BROWSER` is unsatisfiable (GATE 15 OPEN); the browser surface will refuse until a capability (and a product decision) exists.

### 21G — StudySession immutable backend

- **REQUIRED DECISIONS:** whether the lock field is renamed/aliased (`StudySession.backendId` vs `AnkiSessionContext.backendId`); whether session durability is introduced and, if so, the persistence of the §2.5 field set with its restoration ordering; how the "mid-turn, ambiguous" state blocks resumption; how the lock is exposed to UI without becoming a decision input.
- **INPUT CONTRACT:** the lock contract and publication/release rules (`BACKEND_OWNERSHIP_INVARIANTS.md` §2.1, §2.3), the durability rules (§2.4–§2.5), the assurance matrix for `OP-S1`/`OP-S2`, the no-failover rule.
- **OUTPUT CONTRACT:** one immutable session owner, one resolution, no post-binding re-resolution; a durable session (if any) that cannot be restored into a different collection or an invalidated state.
- **EXISTING COMPONENTS TO REUSE:** `AnkiStudyEffectExecutor`, `AnkiLocalStudyStarter`, `AnkiStudyBackendLockTracker`, `AnkiSessionContext`, `StudySessionMachine`/repository, `StudyViewModel` label.
- **EXPECTED INTEGRATION BOUNDARIES:** the executor owns the lock's creation/release; UI reads it read-only; recovery never writes it.
- **DEPENDENCIES:** DECISION-21B-12…14; parent DECISION-21-11/12.
- **BLOCKERS:** none for the immutability contract; durable session work depends on a product decision (DECISION-21B-14 open uncertainty).

### 21H — Rating routing/recovery

- **REQUIRED DECISIONS:** whether per-family capability/version snapshots are persisted with the record; how `OP-M2` non-application proof is expressed for this family; whether an absolute "cannot be reconciled on this backend" terminal state is surfaced to the user; how the recovery blocker interacts with session resumption.
- **INPUT CONTRACT:** the transaction ownership matrix row for `ReviewCommit`, the ambiguity rules (failure matrix §5), the assurance matrix (`OP-M2`/`OP-M3`), the recovery routing rules.
- **OUTPUT CONTRACT:** every rating routes by `commitId.backendId`; ambiguity never becomes retry; blocked groups are per owner; no device-level exactly-once claim beyond what the parent gate verified.
- **EXISTING COMPONENTS TO REUSE:** `ReviewCommitLedger` (+ codec/migration), `ReviewCommitCoordinator`, `ReviewCommitReconciliation`, `AnkiReviewCommitReconciler`, `AnkiStudyEffectExecutor` commit paths, `CommitFailureClassification`.
- **EXPECTED INTEGRATION BOUNDARIES:** ledger owns truth; the executor dispatches; the reconciler is the only recovery entry point; the planner only routes.
- **DEPENDENCIES:** DECISION-21B-15/16; parent DECISION-21-14…16, 21-30/31; GATE 11 machinery.
- **BLOCKERS:** GATE 11's real-device verification is outstanding (recorded, not re-opened).

### 21I — Reviewer action routing/recovery

- **REQUIRED DECISIONS:** how `Set flag` remains refused while `BURY`/`SUSPEND_CARDS` are routed by record identity; whether the family needs its own capability snapshot; how `collectionRef` participates in the assurance matrix when it is null.
- **INPUT CONTRACT:** the `ReviewerAction` row of the ownership matrix, the assurance matrix, the per-group blocking rule.
- **OUTPUT CONTRACT:** every bury/suspend/stored action routes by `record.backendId`; a flag request is refused with `RequiredCapabilityUnavailable`; refusal is never a redirect.
- **EXISTING COMPONENTS TO REUSE:** `ReviewerActionLedger`, `ReviewerActionCoordinator`, `ReviewerActionRecovery`, `AnkiDroidReviewerActionGateway` + committer, `ReviewerActionPolicy`.
- **EXPECTED INTEGRATION BOUNDARIES:** as for 21H; the reviewer-action ledger owns truth.
- **DEPENDENCIES:** DECISION-21B-15/16, 21B-09.
- **BLOCKERS:** `FLAGS` capability is `false` on AnkiDroid; only the refusal path is implementable today.

### 21J — Note editing binding

- **REQUIRED DECISIONS:** whether `NoteMutationCoordinator` becomes registry-routed by record/draft identity (closing the latent gap) or keeps a pinned instance plus an explicit refusal — with the contract requiring that a record owned by another backend is recoverable; how the editor surfaces a blocked Save; whether "open this note on another backend" is offered as a new context.
- **INPUT CONTRACT:** DECISION-21B-20, the `NoteMutation` row of the ownership matrix, the `OP-M1`/`OP-M2` assurance cells, the `BackendMismatch` refusal semantics.
- **OUTPUT CONTRACT:** Save targets the base note's backend always; drafts are never migrated; foreign records are recoverable through their own owner.
- **EXISTING COMPONENTS TO REUSE:** `NoteMutationLedger`, `NoteMutationCoordinator`, `EditNoteViewModel`, `EditNoteMapper`, the edit route token.
- **EXPECTED INTEGRATION BOUNDARIES:** route carries identity; coordinator resolves by identity; ledger records it; UI never resolves.
- **DEPENDENCIES:** DECISION-21B-15/20; GATE 17 locked contract.
- **BLOCKERS:** none technically; the coordinator change is the only structural work.

### 21K — Note creation binding

- **REQUIRED DECISIONS:** the creation context's backend acquisition and the seeding of the Add-Note route from resolution; whether media must be stored before the note and therefore needs durable ownership; how a refused creation preserves the draft; whether a media reference type is introduced here or deferred.
- **INPUT CONTRACT:** DECISION-21B-21, the `NoteCreation`/media rows of the ownership matrix, the `STORE_MEDIA`/`CREATE_NOTE` capability requirements, the coherence rule.
- **OUTPUT CONTRACT:** one coherent creation context; no cross-backend model/deck/media/note combination; unresolved creations recover only through their owner.
- **EXISTING COMPONENTS TO REUSE:** `NoteCreationLedger`, `NoteCreationCoordinator`, `AddNoteViewModel`, `AddNoteMapper`, creation route + seed, `AnkiDroidCreationGateway` media path.
- **EXPECTED INTEGRATION BOUNDARIES:** the route carries the target backend for a **new** context; the coordinator owns the draft's binding; the ledger owns recovery.
- **DEPENDENCIES:** DECISION-21B-15/21, 21B-04 (media qualification).
- **BLOCKERS:** `AnkiMediaRef` does not exist; the media-result qualification (DECISION-21B-04) must land with this sub-gate.

### 21L — Custom Study binding

- **REQUIRED DECISIONS:** all of them, starting with whether a backend-side filtered-session identity is obtainable at all.
- **INPUT CONTRACT:** DECISION-21B-22 and the custom-study row of the ownership matrix.
- **OUTPUT CONTRACT:** a filtered session with one scheduler owner, a distinct filtered-session identity, and no migration or fallback.
- **EXISTING COMPONENTS TO REUSE:** none yet — GATE 20 must land first and its own contract defines the mechanism.
- **EXPECTED INTEGRATION BOUNDARIES:** to be defined by GATE 20 + 21L together, within the locked rules.
- **DEPENDENCIES:** GATE 20 (BLOCKED) — mandatory.
- **BLOCKERS:** **hard blocker.** 21L is not authorized to start before GATE 20 is unlocked and implemented.

### 21M — UI indicators and diagnostics

- **REQUIRED DECISIONS:** how the four roles, the assurance categories and the readiness level are surfaced without implying verification; per-refusal copy; how a blocked recovery is presented per backend; whether an assurance token is exported machine-readably.
- **INPUT CONTRACT:** DECISION-21B-27, the diagnostics vocabulary (failure matrix §9), the operation ownership matrix (what each surface should indicate), the readiness ladder.
- **OUTPUT CONTRACT:** indicators that state identity, category and reason truthfully; no "connected" boolean that collapses R0–R4; content-free, secret-free exports.
- **EXISTING COMPONENTS TO REUSE:** `AnkiBackendRoutingDiagnostics`, `DiagnosticsRepository` routing rows, `DiagnosticsFormatting.NOT_MEASURED`, `DiagnosticsExportPrivacyTest`, Settings backend section, Study locked-backend indicator.
- **EXPECTED INTEGRATION BOUNDARIES:** UI and diagnostics are projections; they must never feed a decision.
- **DEPENDENCIES:** DECISION-21B-24/27, 21B-28 (no credential exposure).
- **BLOCKERS:** none. Library-level indicators are the missing piece today (21A 21M gap).

### 21N — Final audit and verification

- **REQUIRED DECISIONS:** what constitutes executed verification for this series (Gradle/device availability), how each locked invariant is checked, and how contract drift is detected when earlier gates change.
- **INPUT CONTRACT:** all six 21B documents, the parent gate's implementation report, every sub-gate's output.
- **OUTPUT CONTRACT:** an independent audit report stating, per invariant, where it is enforced and how it was verified — distinguishing source audit, unit test, harness run and device verification.
- **EXISTING COMPONENTS TO REUSE:** `Gate21*` test classes as the shape of enforcement tests; the JVM harness as the fallback runner; the audit/verification table formats of this gate.
- **EXPECTED INTEGRATION BOUNDARIES:** 21N audits; it does not implement features.
- **DEPENDENCIES:** every preceding sub-gate.
- **BLOCKERS:** a real Gradle/device run is still required before any release claim (GATE 11, 16, 21 and 21B all record this).

# PART 5 — RESIDUAL RISKS

Carried forward honestly. None of these is resolved by this gate; each has an owner and a stated
consequence if it materialises.

| # | Risk | Why it exists (evidence) | Consequence under this contract | Owner |
|---|---|---|---|---|
| R1 | **No authoritative collection identity on the only real backend** | AnkiDroid deck mapping always sets `collectionKey = null` (`AnkiDroidDeckMapper.kt:93,114`); the continuity check fails open on an unknown bound key (`AnkiDroidBackend.kt:1336-1341`) | Collection equals/mismatch can never be *proven* on AnkiDroid; all operations run under nominal `IDENTITY_UNVERIFIABLE`; no claim of verification may be made | 21C (represent), 21M (disclose); resolution requires a product decision or a device probe |
| R2 | **AnkiDroid profile/collection change is undetectable while the app is alive** | same evidence; no profile id, hkey, server id or sync state is exposed anywhere | A user switching profiles mid-session will not be warned; the session continues against whatever collection the provider now serves | Product decision (out of 21B scope). The contract forbids pretending otherwise |
| R3 | **PC identity allocation is unauditable** | No production producer of `PcAgent(...)` exists (`grep` → declaration only) | Two PC configurations could collide until A1–A6 are implemented; until then `PC_AGENT` is legitimately unselectable | 21D (hard precondition for registering a PC backend) |
| R4 | **Backend capability asymmetry cannot be measured** | Only one backend is implemented; every PC cell in the capability matrix is "no code", not a measurement | Capability-constrained routing will refuse operations that a future PC backend might support; AUTO may therefore refuse where a second backend would have been eligible | 21D/21E once a PC backend exists |
| R5 | **Reconnect assurance is bounded by the same missing identity** | `AnkiTransactionRecoveryPlanner.route` needs two known keys (`:71`) | Recovery cannot detect a wrong-collection reconnect on AnkiDroid; it can only block when evidence exists | 21H–21K (preserve current semantics), 22 (degradation policy must not weaken it) |
| R6 | **Sync divergence is unprovable** | No sync state is exposed by any backend; no comparison code exists | The app must never suggest that two collections are "the same" or "in sync"; cross-backend continuation remains impossible | Permanent design constraint |
| R7 | **Unimplemented earlier-Gate dependencies** | GATE 20 BLOCKED; PC Anki backend absent; `CARD_BROWSER`/`MEDIA_READ`/`FLAGS`/`DECK_COUNTS` unsatisfiable; GATE 11 device verification outstanding; GATE 15 OPEN; GATE 16 BLOCKED from locked | 21L cannot start; browser/media/flag features must refuse honestly; no exactly-once *device* claim; no PC column may be filled | 21L (blocked), 21F (refusals), parent gates |
| R8 | **Session ownership is not durable** | `AnkiSessionContext` is not `@Serializable`; the lock tracker is process memory | A process death ends the session; only transaction ownership survives | 21G (if durability is required) — must satisfy §2.5 |
| R9 | **The two note coordinators are pinned to one instance** | `NoteMutationCoordinator.kt:151`, `NoteCreationCoordinator.kt:162` with `AppContainer.kt:439,470` | Fail-closed today, but a record owned by a second backend could not be recovered | 21J / 21K (required change) |
| R10 | **Read contexts are pinned and deck routes are unqualified** | `AppNavHost.kt:81,110,142,144`; `Screen.kt:17,64` | Until 21F lands, a second registered backend must not be reachable from those surfaces; the interim rule keeps today's behavior deterministic | 21F |
| R11 | **Capability/version invalidations are undefined** | No versioning mechanism exists; `withWriteSupport` only drops claims for unwired writers | A future AnkiDroid contract change could leave a stale capability claim | 21D |
| R12 | **No executed verification exists in this sandbox** | No JDK/Android SDK; Gradle cannot run; the JVM harness was not re-run in this gate | This gate's verification is contractual only. Executed verification remains owed for release | 21N + CI |
| R13 | **A conflicting offline/degradation design could weaken ownership** | GATE 22 is not started | The contract states that nothing here makes a mutation replayable; 22 must not introduce replay or substitution | GATE 22 |

**Explicit non-risk claim.** GATE 21B does **not** claim: that collection continuity is verified anywhere,
that PC Anki works, that custom study works, that any device has ever executed a mutation successfully, or
that the AUTO policy has been re-executed against a second backend.

---

# PART 6 — NON-NEGOTIABLE INVARIANTS

```text
INV-21B-01  The configured backend instance is distinguished from the backend kind (and from the
            runtime object and the display name).
INV-21B-02  Collection identity uncertainty is represented explicitly and is never converted to a match.
INV-21B-03  Resource IDs never imply cross-backend equivalence.
INV-21B-04  AUTO resolves only before context binding.
INV-21B-05  Manual backend selection has no silent fallback.
INV-21B-06  AUTO is deterministic for equivalent inputs.
INV-21B-07  Capability and readiness checks occur before binding.
INV-21B-08  Every StudySession has one immutable Anki scheduler backend.
INV-21B-09  Mid-session cross-backend failover is prohibited.
INV-21B-10  Every mutation retains its original backend identity.
INV-21B-11  Recovery never invokes AUTO for a persisted transaction.
INV-21B-12  Unknown mutation outcomes cannot trigger cross-backend replay.
INV-21B-13  Backend reconnect does not automatically prove collection continuity.
INV-21B-14  Bound read contexts cannot merge data silently; stale responses are rejected explicitly.
INV-21B-15  Editors cannot redirect writes after preference changes.
INV-21B-16  Note creation and media storage preserve backend binding.
INV-21B-17  Custom Study cannot migrate scheduler authority.
INV-21B-18  PC AI/TTS services are distinct from PC Anki scheduling ownership.
INV-21B-19  Settings, Registry, Resolver, Session and Transaction Ledger maintain distinct authority.
INV-21B-20  GATE 21B cannot weaken GATE 11–20 invariants.
```

**Where each invariant is anchored** (contract → enforcement point → how it was checked by this gate):

| Invariant | Contract location | Enforcement point | Checked by |
|---|---|---|---|
| 01 | Identity contract §1 | `AnkiBackendId`; registry duplicate refusal; allocation rules A1–A6 | Audit 02; VER-21B-12 |
| 02 | Identity contract §2–§3 | `collectionKey` null semantics; assurance matrix; planner's two-key rule | Audit 02/07; VER-21B-13/14 |
| 03 | Identity contract §4 | backend-qualified refs + `stableKey` | Audit 08; VER-21B-12 |
| 04 | Selection policy §3.1 | one resolution inside `begin()`; resolver purity | Audit 03; VER-21B-04/05 |
| 05 | Selection policy §4 | resolution outcomes; no substitution branches | Audit 04; VER-21B-01 |
| 06 | Selection policy §3.3–§3.4 | total ordering + tie-break | Audit 03; VER-21B-02 |
| 07 | Selection policy §2 | readiness + capability gates | Audit 03/13; VER-21B-03/19 |
| 08 | Ownership invariants §2 | session lock immutability | Audit 05; VER-21B-04/05/06 |
| 09 | Ownership invariants §2.1; failure matrix §2.1 | no alternate/failover symbols in study runtime | Audit 05/06; VER-21B-06 |
| 10 | Ownership invariants §3 | record-level `backendId` | Audit 06; VER-21B-07/08 |
| 11 | Ownership invariants §3.3 | resolver-free recovery planner | Audit 06; VER-21B-07/16 |
| 12 | Failure matrix §5 | ambiguity status semantics | Audit 06/07; VER-21B-07 |
| 13 | Failure matrix §3 | R0–R4 ladder; six reconnect cases | Audit 07; VER-21B-13/14 |
| 14 | Ownership invariants §4 | invalidation list + correlation tuple | Audit 09; VER-21B-15 |
| 15 | Ownership invariants §5.1 | route-bound editor identity + `BackendMismatch` | Audit 10; VER-21B-09 |
| 16 | Ownership invariants §5.2 | creation coherence rule | Audit 10; VER-21B-10 |
| 17 | Ownership invariants §6 | filtered-session identity distinction | Audit 11; VER-21B-11 |
| 18 | Ownership invariants §7 | agent-connection vs `AnkiBackendId` separation | Audit 12; VER-21B-18 |
| 19 | Ownership invariants §1, §8 | one authority per concern | Audit 12; VER-21B-20 |
| 20 | This document §0.5, DECISION-21B-29 | no previous gate's verdict altered; no compatibility claimed for absent code | Audit 14 |

---

# PART 7 — DEFINITION OF DONE

| Criterion | Result |
|---|---|
| GATE 21A locked | **MET** — PASS/LOCKED, 10/10 audits, 10/10 verifications |
| Discovery references validated | **MET** — every decision cites ledger IDs and file:line; §0.4 re-verified 21 claims from source |
| Stale findings rechecked | **MET** — no source change since 21A (proven via upstream compare); findings re-verified anyway |
| Missing/unknown implementations classified accurately | **MET** — PC backend, media ref, filtered-session ref, PC allocation all recorded as absent/unknown |
| All DECISION-21B-01…30 resolved | **MET** — thirty decisions, each with the ten required fields, alternatives and rejection reasons |
| AUTO algorithm explicit | **MET** — total pseudocode + precedence + tie-breaks (`BACKEND_SELECTION_POLICY.md` §3) |
| Manual selection explicit | **MET** — behavior table, no-fallback rule (§4) |
| Backend identity defined | **MET** — identity contract §1 (creation, persistence, stability, re-registration, removal, collisions, legacy) |
| Collection assurance defined | **MET** — identity contract §2–§3 (four categories, downgrade rule, operation matrix, computation algorithm) |
| Resource qualification defined | **MET** — identity contract §4 |
| StudySession ownership defined | **MET** — ownership invariants §2 |
| Transaction recovery routing defined | **MET** — ownership invariants §3 |
| Reconnect policy defined | **MET** — failure matrix §3 |
| Failure matrix complete | **MET** — failure matrix; every cell has result/error/UI semantics, no "fallback if possible" |
| Source-of-truth matrix complete | **MET** — ownership invariants §8 |
| Downstream handoff defined | **MET** — PART 4 (21C–21N with the seven required fields each) |
| All AUDIT-21B-01…15 pass | **MET** — 15/15 PASS |
| All VER-21B-01…20 pass | **MET** — 20/20 PASS as contract checks |
| Documentation complete and internally consistent | **MET** — six documents; consistency confirmed by DECISION-21B-30 and the audits |
| Production Kotlin changes | **0** |
| Gradle changes | **0** |
| Persistence migrations | **0** |
| Runtime behavior changes | **0** |
| Pre-existing working-tree changes | **None** at the start of this gate (clean tree); GATE 21B's own changes are confined to `docs/` |

---

# PART 8 — CONTRACT LOCK DECISION

## 8.1 Gate lock conditions

| Condition | Status |
|---|---|
| GATE 21A discovery valid | **MET** |
| All normative decisions resolved | **MET** (30/30) |
| Identity contract consistent | **MET** (audit 02, 08; VER-21B-12) |
| AUTO policy deterministic | **MET** (audit 03; VER-21B-02) |
| Session ownership immutable | **MET** (audit 05; VER-21B-04/05/06) |
| Transaction recovery original-backend-only | **MET** (audit 06; VER-21B-07/08/16) |
| Failure policy explicit | **MET** (failure matrix; VER-21B-06/13/14/15) |
| Architecture audit complete | **MET** (15/15) |
| Contract verification complete | **MET** (20/20) |
| Documentation complete | **MET** (6 documents) |

## 8.2 Decision

```text
GATE 21B:                    LOCKED

BACKEND IDENTITY:            LOCKED
COLLECTION ASSURANCE:        LOCKED
AUTO POLICY:                 LOCKED
SESSION OWNERSHIP:           LOCKED
TRANSACTION OWNERSHIP:       LOCKED
RECOVERY ROUTING:            LOCKED

IMPLEMENTATION AUTHORIZED:   NO
NEXT AUTHORIZED SUB-GATE:    21C — Backend Identity & Resource Qualification
```

```text
GATE 21B — CONTRACT LOCKED

PRODUCTION IMPLEMENTATION:
NOT STARTED

NEXT AUTHORIZED:
GATE 21C — Backend Identity & Resource Qualification
```

**Why implementation is NOT authorized by this gate.** GATE 21B is an architecture sub-gate: it resolves
contracts so that 21C–21N can implement them. Authorizing implementation from here would be authorizing work
against contracts that its own first consumer has not yet encoded. The parent gate's implementation
authorization (GATE 21, `IMPLEMENTATION AUTHORIZED: YES`) remains valid for the components it already
specified; nothing in 21B re-authorizes or extends it.

**What 21C may now do.** Encode the identity and assurance contracts of
`BACKEND_IDENTITY_CONTRACT.md` (categories, resource qualification, format compatibility) without changing
routing, session or mutation behavior. Nothing else.

**Scope prohibitions that remain in force after this lock**

```text
No production implementation in this sub-gate (none was performed).
No Kotlin interface, class or enum was created.
No BackendRegistry or BackendResolver was implemented or modified.
No persistence migration was written.
No earlier gate's verdict was changed.
No claim of runtime, device or PC-backend verification is made anywhere in this gate's documents.
```

---

# PART 9 — NORMATIVE CONTRACT SUMMARY

```text
BACKEND IDENTITY      AnkiBackendId (kind-namespaced stableId) identifies a CONFIGURED INSTANCE.
                      Kind, runtime object and display name are never identities. Identity is minted once
                      at configuration time by one allocation authority, is persistable and stable across
                      restart, and two instances may never share an identity. Collisions are a
                      configuration error (fail closed), never renumbered or merged.

COLLECTION IDENTITY   AnkiCollectionIdentity(backendId, collectionKey?) is the only representation.
                      null = UNKNOWN, never a wildcard and never back-filled. Assurance categories:
                      AUTHORITATIVE_MATCH | SESSION_CONTINUITY_VERIFIED |
                      IDENTITY_UNVERIFIABLE(nominal|degraded) | AUTHORITATIVE_MISMATCH. Match ⇒ continue;
                      mismatch ⇒ fail closed; nominal unverifiable ⇒ continue under the recorded category
                      with disclosure; degraded ⇒ mismatch-grade block. Assurance never upgrades on
                      reconnect and never downgrades spontaneously.

RESOURCE IDENTITY     logical id = backend + collection context (or explicit unknown) + backend-local
                      discriminator (+ type tag). No cross-backend equality, no global numeric ids, no
                      deck-name identity, no card↔note confusion, no reinterpretation during navigation,
                      no silent migration. Media and filtered-session references must be qualified when
                      they are created (neither exists today).

SELECTION             Four roles never substituted: PreferredBackend (settings) / ResolvedBackend
                      (pre-binding) / LockedSessionBackend (immutable) / TransactionBackend (durable
                      record). AUTO is a pure, deterministic function: eligibility → readiness →
                      capability → local-first → single-remote-or-ambiguous, tie-broken on orderingKey,
                      and only ever before binding. Explicit selection resolves to an instance or refuses;
                      it never falls back. The only fallback in the architecture is AUTO choosing an
                      instance for a new, unbound context.

SESSION LOCK          One StudySession binds exactly one Anki scheduler backend at session start, after
                      capability and deck validation, and publishes the lock only on success. The owner is
                      immutable; the lock is released only on end-review. The PC agent may concurrently
                      provide AI/TTS/STT, which is not scheduler authority. Mid-session failover is
                      prohibited; loss means pause/block/wait for the original backend. The lock is process
                      memory today; a session is not restored across process death.

TRANSACTION OWNERSHIP Every durable mutation records the backend it was admitted against, and recovery
                      routes by that record identity only — never AUTO, never the preference, never the
                      first available backend. Mismatch blocks the group; an unregistered owner blocks the
                      group; another backend's outage must not affect it. Ambiguity is never resolved by
                      connectivity. Historical records keep their uncertainty; nothing is re-attributed.

FAILURE POLICY        Nothing converts a bound context into an unbound one, and nothing converts an
                      ambiguous mutation into a safe retry. Reads invalidate instead of merging; writes
                      refuse instead of redirecting; recovery waits instead of substituting.
```

---

# PART 10 — FINAL REPORT REFERENCE

The PART VIII final-report tables (gate result, decision matrix, AUTO algorithm, assurance matrix,
operation ownership matrix, failure/recovery matrix, source-of-truth matrix, audit report, verification
report, files created, downstream handoff, residual risks, contract lock decision) are reproduced in
condensed form in this document (§0, PART 1, PART 3–8) and in full for the traceability view in
`docs/GATE_21B_DECISION_EVIDENCE.md`.

The documentation commit suggested by this gate (not created — commit creation is not authorized here):

```text
docs(gate21b): lock multi-backend identity selection and ownership contracts
```
