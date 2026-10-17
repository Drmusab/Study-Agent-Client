# GATE 21K — Note Creation Backend Binding (Implementation Record, Audit and Verification)

Sub-gate of **GATE 21 — Multi-Backend Anki Selection / Hybrid Mode**.
Parent contract: `docs/GATE_21B_CONTRACT_LOCK.md` (DECISION-21B-21 creation-context binding,
DECISION-21B-04 backend-qualified resource identity, DECISION-21K-02 creation-family non-creation
proof). Transaction authority: `docs/GATE_18_BACKEND_CREATION_CONTRACT.md` and the creation status
machine (`core/anki/create/NoteCreationStatus.kt`, `NoteCreationTransition.kt`), **unchanged**.

**Status of this document.** Implementation, audit and verification report, **harness-verified only**.
No Gradle, Android SDK, device, real AnkiDroid or real PC backend was available in this environment
(§7). Nothing below is CI proof and no device claim is made.

---

## §0 Why this revision exists

`docs/GATE_21J_NOTE_EDIT_BINDING.md` §1 records, in its own words:

```text
GATE 21K:                     NOT STARTED
... R9's note half is now implemented (creation half → 21K).
```

The creation-family **implementation** subsequently landed at HEAD
(`core/anki/create/NoteCreationRoutingPolicy.kt`, the `GATE 21K` markers throughout
`NoteCreationCoordinator.kt`, `AddNoteDraft.kt`, `AnkiNoteCreationContext.kt`,
`NoteCreationRecord.kt`, `NoteCreationBackendResults.kt`). What never landed was the Sub-Gate's
**evidence**:

| Item | State found at HEAD `b4ea0be3c5ee8e988a3eb9d3322c3956b4d49149` |
|---|---|
| `docs/GATE_21K_*.md` | **absent** — `ls docs/GATE_21K*` returns nothing |
| `Gate21k*Test.kt` | **absent** — the only 21K test file was `Gate21kFixtures.kt` |
| `Gate21kFixtures.kt` | **orphaned** — `grep -rln "Gate21k" app/src/test app/src/androidTest` returned only the fixture file itself, i.e. zero referencing tests |
| `docs/GATE_21_FINAL_LOCK.md` §16 | lists `core/anki/create/NoteCreationRoutingPolicy.kt — GATE 21K` as produced, but lists **no** `GATE_21K_*.md` and **no** `Gate21k*Test.kt` |

So the Sub-Gate's VER-21K-01 … VER-21K-06 had **no executable evidence** and the fixture built to
drive them (CHECKPOINT 21K-33: two backends with deliberately colliding backend-local identities) was
dead code. This revision supplies that evidence. **No production source was changed** (§1).

---

## §1 Gate result

```text
GATE:                         21K — Note Creation Backend Binding
REPOSITORY:                   Drmusab/Study-Agent-Client
BRANCH:                       arena/584dfb88-study-agent-client
HEAD BEFORE THIS REVISION:    b4ea0be3c5ee8e988a3eb9d3322c3956b4d49149  (merge of PR #91)
WORKTREE BEFORE THIS REVISION: clean (git status --porcelain empty)
STATUS:                       PASS
SCOPE:                        GATE 21K only; GATE 21L NOT started, GATE 21M NOT started
PRODUCTION SOURCE CHANGED:    none — the 21K implementation was found present and was audited as found
```

### PRECONDITIONS

- GATE 21C identity types present and compiled: `core/anki/AnkiBackendId.kt`,
  `AnkiIdentityAssurance.kt` (`AnkiIdentityAssurancePolicy.check`), `AnkiIdentityContinuation.kt`
  (locked §3.3 matrix, `AnkiOperationClass.OP_M1/OP_M2/OP_R2`).
- GATE 21D registry present: `core/anki/AnkiBackendSelector.kt` → `AnkiBackendRegistry`
  (`find`, `register`, `remove`).
- GATE 18 creation family present and unchanged: `NoteCreationLedger`, `NoteCreationStatus`,
  `NoteCreationTransitions`, `NoteCreationValidator`, `DefaultNoteCreationCoordinator`.
- JVM harness bootstrapped in this sandbox (§7). Baseline before this revision, measured here:
  `RESULT classes=227 tests=2756 passed=2756 failed=0 ignored=0` — reproducing the number claimed by
  `docs/GATE_21_FINAL_LOCK.md` §3 independently.

### FILES CREATED

```text
app/src/test/java/com/studyagent/client/anki/create/Gate21kNoteCreationOwnershipTest.kt   (27 tests)
app/src/test/java/com/studyagent/client/anki/Gate21kArchitectureAuditTest.kt              (16 tests)
docs/GATE_21K_NOTE_CREATION_BINDING.md                                                    (this file)
```

### FILES MODIFIED

```text
docs/GATE_21_FINAL_LOCK.md   — addendum §18 only (bookkeeping correction, no lock semantics changed)
```

### FILES INTENTIONALLY REUSED (read, not modified)

```text
app/src/test/java/com/studyagent/client/anki/create/Gate21kFixtures.kt
    — the orphaned CHECKPOINT 21K-33 world. Now referenced by Gate21kNoteCreationOwnershipTest,
      so the colliding-identity fixture (model "100" / deck "10" / first note "1000" on BOTH
      backends, different schemas, different deck names) is live evidence instead of dead code.
app/src/test/java/com/studyagent/client/anki/create/FakeNoteCreationBackend.kt   (GATE 18 fake)
app/src/test/java/com/studyagent/client/anki/create/NoteCreationLedgerTest.kt    (InMemoryNoteCreationStore)
app/src/main/java/com/studyagent/client/core/anki/create/*.kt                    (all — unchanged)
app/src/main/java/com/studyagent/client/core/anki/AnkiIdentityContinuation.kt    (unchanged)
app/src/main/java/com/studyagent/client/core/anki/AnkiIdentityAssurance.kt       (unchanged)
app/src/main/java/com/studyagent/client/core/anki/AnkiBackendSelector.kt         (unchanged)
```

---

## §2 PART I — Implementation (audited as found)

Checkpoints from the GATE 21K plan, with the source that discharges each. All were found present;
none was written by this revision.

| CP | Requirement | Source evidence |
|---|---|---|
| **K01** | Creation draft context captures backend, collection, model, deck | `core/anki/create/AnkiNoteCreationContext.kt` — `data class AnkiNoteCreationContext(backendContext, generation)`; `backendId`/`collectionKey` are derived getters over an immutable `AnkiBackendContext`; `AddNoteDraft.context` is the only binding (`AddNoteDraft.backendId get() = context.backendId`) |
| **K02** | Media destination bound to the original backend | `NoteCreationRecord.storedMediaRefs` re-attaches `AnkiStoredMediaRef(backendId, it, collectionKey)`; `NoteCreationCoordinator.kt:427` refuses a `Stored` result whose `media.backendId != backend.id`; `NoteCreationFieldAssembly.assemble` rejects any ref with `it.backendId != draft.backendId` |
| **K03** | Create Note uses the locked target backend | `NoteCreationCoordinator.kt:202` `routing.admit(draft.context)`; `:347` re-checks `draft.backendId != backend.id`; `:498` the single `backend.createAnkiNote(` dispatch |
| **K04** | Model/deck ids never reinterpreted against a new backend | `AddNoteDraft.init` `require(model.ref.backendId == backendId)`; `CreateNoteBackendRequest.init` `require(model.backendId == backendId)`; `preflight()` and the pre-boundary re-read both match `draft.model.ref.modelId` **within the routed backend's own** `getNoteModels()` |
| **K05** | Recovery routes unresolved creation to the original backend | `NoteCreationRoutingPolicy.route(record, …)` → `registry.find(record.backendId)`; `:133`. No AUTO, no preference, no sibling |
| **K06** | Post-create hydration uses the creating backend | `NoteCreationCoordinator.kt:299` `routing.route(record, AnkiOperationClass.OP_R2, boundIsLiveInThisProcess = false)`; `:562` the single `backend.resolveCreatedNote(` site |

---

## §3 PART II — Architecture Audit

Audit IDs are this revision's own numbering; the right-hand column maps each to the GATE 21K PART II
requirement it discharges. Executed in `Gate21kArchitectureAuditTest` (16 tests, all PASS).
Convention follows GATE 12–21J: rules are checked on source text with comment lines stripped.

| Audit ID | Result | Requirement mapped | Evidence |
|---|---|---|---|
| AUDIT-21K-01 | **PASS** | coordinator holds no pinned backend | `NoteCreationCoordinator.kt:184` `private val backends: AnkiBackendRegistry`; no `private val backend: AnkiBackend`; no `AnkiBackendId.AnkiDroidLocal` / `AnkiBackendId.PcAgent(` literal anywhere in the file (`grep -n "AnkiBackendId\." → (none)`) |
| AUDIT-21K-02 | **PASS** | no write routing through global preference | `grep -rn "BackendPreference\|AnkiBackendPreferenceStore\|AnkiBackendMode\|AnkiBackendResolver\|BackendResolution" core/anki/create/` → **(none)** across all 12 files |
| AUDIT-21K-03 | **PASS** | exactly one backend-selection point | `grep -rn "\.find(" core/anki/create/` returns exactly 2 hits, both in `NoteCreationRoutingPolicy.kt` (`:110 context.backendId`, `:133 record.backendId`); the coordinator reaches a backend only via `is NoteCreationRoute.Routed -> route.backend` (3 sites: create `:203`, retry `:236`, hydrate `:299`) |
| AUDIT-21K-04 | **PASS** | no family-local matrix re-decision (the DECISION-21J-03 lesson) | the only `AnkiContinuationBlockReason.` occurrence in the routing policy is the unreachable `FOREIGN_BACKEND` guard (`:160`); every verdict comes from `AnkiIdentityContinuation.continuationVerdict(operation, check, facts)` (`:175`) |
| AUDIT-21K-05 | **PASS** | no media created on A and note created on B | foreign-identity fail-closed at `:427` (`stored_media_foreign_backend`) and `:508` (`created_note_foreign_backend`); preflight re-check at `:347` |
| AUDIT-21K-06 | **PASS** | no fabricated model/deck equivalence at the field level | `NoteCreationFieldAssembly.assemble` returns `null` on `it.backendId != draft.backendId` and on a contradicting `collectionKey` |
| AUDIT-21K-07 | **PASS** | resource identity stays backend/collection qualified | `NoteCreationRecord.backendId`/`collectionKey`; `storedMediaRefs`; `ConfirmedCreated(val noteRef: AnkiNoteRef)`; `Stored(val media: AnkiStoredMediaRef)` — no bare note id or media name crosses the boundary |
| AUDIT-21K-08 | **PASS** | no duplicate creation from backend fallback; no cross-backend retry | exactly **1** `backend.createAnkiNote(` site, **1** `backend.storeAnkiMedia(` site, **1** `backend.resolveCreatedNote(` site in the coordinator; hydration is `OP_R2` by `record.backendId` |
| AUDIT-21K-09 | **PASS** | existing orphan-media policy unchanged | `grep -rn "deleteMedia\|removeMedia\|deleteAnkiMedia\|cleanupMedia\|purgeMedia" core/anki/create/` → **(none)**; a `MediaOutcomeUnknown` still lands on `ABANDONED` with the orphan risk disclosed (`NoteCreationTransitions`) |
| AUDIT-21K-10 | **PASS** | family keeps its own id/ledger/status | no `NoteMutationStatus/Id`, `ReviewCommitStatus/Id`, `ReviewerActionStatus/Id` or `core.anki.edit.` import in any `core/anki/create/` source (the only textual hits are in a stripped comment in `NoteCreationId.kt:7`) |
| AUDIT-21K-11 | **PASS** | one backend per draft and per request | `AddNoteDraft.init` and `CreateNoteBackendRequest.init` requires (§2 K04) |
| AUDIT-21K-12 | **PASS** | UI does not own routing policy | no `BackendPreference`/`AnkiBackendResolver`/`AnkiBackendRegistry`/`AnkiBackendMode`/`BackendResolution` in `ui/screens/addnote/`; `AddNoteViewModel` receives an `AnkiReadContextBinding`, writes only via `coordinator.create/retry/resolveAmbiguous/hydrate`, filters recovery by `record.backendId == backend.id`, and surfaces `NoteCreationRefusal.BackendRouteBlocked` as its own refusal |

**Audit result: 16/16 PASS, 0 FAIL.**

---

## §4 PART III — Verification

Executed in `Gate21kNoteCreationOwnershipTest` (27 tests, all PASS). Backend A =
`AnkiBackendId.AnkiDroidLocal`; backend B = `AnkiBackendId.PcAgent("pc-profile-1")`. Both fakes are
built by `Gate21kWorld` with model id `"100"` and deck id `"10"` on **both** backends, and first note
id `"1000"` on both, so a single mis-route is observable as a call log on the wrong backend.

| Verification ID | Result | Test evidence |
|---|---|---|
| **VER-21K-01** Draft opened on A creates on A | **PASS** (2 tests) | `a draft bound to A creates only on A` — `Created`, `record.backendId == aId`, `a.createNoteCalls == 1`, **`b.totalCalls() == 0`**, `deckName == "Deck@A"`. Symmetric: `a draft bound to B creates only on B` — `a.totalCalls() == 0` |
| **VER-21K-02** Preference B does not redirect Create | **PASS** (2 tests) | `flipping the global preference to B does not redirect a draft bound to A` — a `MutableStateFlow<BackendPreference>` is set to `Explicit(bId)` then `Auto`; both creations still land on A (`a.createNoteCalls == 2`, `b.totalCalls() == 0`). `the only way to reach B is a draft bound to B` — two distinct `creationId`s, one dispatch each |
| **VER-21K-03** Media and note share correct locked destination | **PASS** (3 tests) | `media store and note creation of one creation share the locked backend` — `a.mediaStoreCalls == 1`, `a.createNoteCalls == 1`, `b.totalCalls() == 0`, all `storedMediaRefs.backendId == aId`, note contains `<img src="image.png">`. `a media store answering under a foreign identity fails closed` — scripted `Stored(AnkiStoredMediaRef(bId, …))` ⇒ `MediaOutcomeUnclear`, status `ABANDONED`, **note boundary never entered**, `b.totalCalls() == 0`, detail `stored_media_foreign_backend`. `field assembly refuses a stored name qualified to another backend` — foreign ⇒ `null`, own ⇒ assembled |
| **VER-21K-04** Unknown creation result never retries through B | **PASS** (4 tests) | `an ambiguous creation is never retried on the other backend` — `AMBIGUOUS`, `retry` ⇒ `Refused(RetryNotAllowed(AMBIGUOUS))`, still 1 dispatch on A, `b.totalCalls() == 0`; human attestation touches **no** backend. `an ambiguous creation survives a restart still owned by A` — reloaded record `backendId == aId`, retry refused, B untouched. `a proven non-creation is abandoned at restart and never redirected to B` — see §5 OPEN-21K-01. `a retry is blocked, not redirected, when the owning backend leaves the registry` — `registry.remove(aId)` ⇒ `Refused(BackendRouteBlocked("backend_not_registered"))`, record preserved as `RETRY_ALLOWED` under `activeCreationsFor(aId)`, `b.totalCalls() == 0`; re-registering A then completes the retry on A (`a.createNoteCalls == 2`) |
| **VER-21K-05** Post-create hydration uses original backend | **PASS** (3 tests) | `post-create hydration reads from the backend that created the note` — `a.hydrationCalls == 1` with `backendId == aId`, `b.totalCalls() == 0`. `a hydration resume after restart reads the original backend only` — after a scripted hydration failure the record stays `CREATED`; resume ⇒ `Hydrated` with `noteRef.backendId == aId`, `deckName == "Deck@A"`, B untouched. `hydration is blocked when the owning backend is not registered` — registry holds only B ⇒ `HydrationFailed(InvalidRequest("backend_not_registered"))`, **`b.totalCalls() == 0`**, record still `CREATED`/`aId` |
| **VER-21K-06** Identical numeric deck/model IDs across backends stay distinct | **PASS** (4 tests) | `identical numeric model and deck ids are distinct identities per backend` — `AnkiNoteModelRef(aId,"100") != AnkiNoteModelRef(bId,"100")` and `stableKey`s differ; same for `AnkiDeckRef(…,"10")`. `two creations using the same numeric ids land on their own backends and schemas` — both created notes have the **same** `noteRef.noteId` yet different `stableKey`; `Deck@A` vs `Deck@B`; each keeps its own model ref; each backend served its own 2 schema reads. `a draft cannot be assembled from another backend's model` and `a creation request cannot carry another backend's model reference` — both throw `IllegalArgumentException` |
| **K01 / K04** collection evidence gates the destination | **PASS** (4 tests) | contradicting key ⇒ `Refused(BackendRouteBlocked("collection_mismatch"))` with `store.writes == 0` and `a.totalCalls() == 0` (nothing recorded, nothing sent); key present in the draft but not offered live ⇒ `"identity_unverifiable_degraded"` (degraded, not "fine"); matching key ⇒ `Created` with `record.collectionKey == "col-bound"`; a **verified mismatch after binding blocks a retry** rather than redirecting it |
| **K05 / K06** recovery surface and durability | **PASS** (5 tests) | routing policy yields `Routed(aId, SessionContinuityVerified)` for a live binding and `Blocked("backend_not_registered", OP_M1)` for an unconfigured owner — a typed result, never a substitute; `unresolvedCreations()` partitions correctly (`activeCreationsFor(aId)` / `(bId)` are disjoint, an unconfigured instance owns nothing); a durable record round-trips its `backendId` through the store; the ledger stays readable post-creation |

**Verification result: 27/27 PASS, 0 FAIL.**

### Counters required by the plan's endurance contract

Measured across every test above (the fakes log every touch, reads included):

```text
Wrong-backend note creations:        0   (b.createNoteCalls == 0 in every A-bound test; symmetric for B)
Wrong-backend media stores:          0
Wrong-backend post-create reads:     0
Cross-backend transaction replays:   0   (retry after owner removal blocked; re-registration resumes on A only)
Resource-ID reinterpretations:       0   (model "100" / deck "10" / note "1000" collisions never conflated)
```

---

## §5 Open defects and honest deviations

**OPEN-21K-01 — a `RETRY_ALLOWED` creation is abandoned at restart, so "retry on the original backend
after process death" is not reachable for this family.** Found by a failing test, not by inspection.
`DefaultNoteCreationLedger.normalizeAfterRestart` (`core/anki/create/NoteCreationLedger.kt:230-239`)
maps `PREPARED`, `STORING_MEDIA` and `RETRY_ALLOWED` to `ABANDONED` on a fresh ledger load, because the
in-process draft payload died with the old process. This is GATE 18's documented rule
(`NoteCreationTransitions`: `RETRY_ALLOWED | RestartAbandoned → ABANDONED`; `NoteCreationReason.
ABANDONED_ON_RESTART`) and it is **correct** — there is no payload to retry with. The consequence for
GATE 21K is that the post-restart *retry* path can never demonstrate original-backend routing for this
family; the durable-ownership guarantee after restart is instead evidenced on the `AMBIGUOUS` path
(which survives restart untouched) and on `activeCreationsFor` / `unresolvedCreations`. The
same-process "chosen backend disappears" case is evidenced directly by removing the owner from the
live registry. **Not a defect in 21K; recorded so a future reader does not mistake the gap for an
untested path.**

**OPEN-21K-02 — no production PC `AnkiBackend` exists.** Carried forward verbatim from
`docs/GATE_21_FINAL_LOCK.md` §14 item 6: the PC transport exists but no Anki scheduler adapter does, so
every PC-side assertion here is against `FakeNoteCreationBackend`. Capability-scoped exclusion, not a
PASS on real hardware.

**OPEN-21K-03 — no authoritative creation reconciliation.** `NoteCreationSemantics.
authoritativeCreationReconciliation = false` at both pinned semantics (`ANKIDROID_V2_24_1`,
`UNVERIFIED`), so the creation family exposes **no** `OP-M3` route at all; the only escape from
`AMBIGUOUS` is a human attestation that touches no backend (CONTRACT-18-20). VER-21K-04 therefore
asserts the *absence* of a reconciliation path rather than its correctness.

---

## §6 Invariants

| ID | Invariant | Status |
|---|---|---|
| INV-21K-a | A creation draft is bound to one immutable backend/collection context | **HELD** — `AnkiNoteCreationContext` is a `data class` over an immutable `AnkiBackendContext`; `AddNoteDraft.init` refuses a foreign model |
| INV-21K-b | Every creation backend touch derives its target from the creation's own identity | **HELD** — AUDIT-21K-03 (2 lookups, both in the routing policy) |
| INV-21K-c | No cross-backend create retry or media/note split | **HELD** — AUDIT-21K-05/08, VER-21K-03/04 |
| INV-21K-d | A foreign answer is never claimed as our own | **HELD** — `stored_media_foreign_backend`, `created_note_foreign_backend` |
| INV-21K-e | Resource identity stays backend/collection qualified | **HELD** — AUDIT-21K-07, VER-21K-06 |
| INV-21K-f | Existing orphan-media policy unchanged | **HELD** — AUDIT-21K-09 |
| INV-18-08 / 18-09 | `AMBIGUOUS` never retried; a confirmed creation never re-created | **HELD** — VER-21K-04, VER-21K-05 (blocked hydration leaves status `CREATED`) |
| INV-21-09 / 21-10 | AUTO only pre-binding; manual selection never silently falls back | **HELD** — AUDIT-21K-02/03, VER-21K-02 |

**Violated: none.**

---

## §7 Build checks (actually run)

Environment: no `java` on `PATH`, no `/usr/lib/jvm`, no Android SDK, no device. The repository's own
Gradle-free JVM harness was bootstrapped from the sources `tools/jvm-harness/bin/bootstrap-sandbox.sh`
documents (PyPI `jdk4py` + `kotlin-jupyter-kernel`; GitHub `Sable/android-platforms` android-34 and
`Kotlin/kotlinx.coroutines` 1.10.2). Per `tools/jvm-harness/README.md` this is **equivalence evidence,
not CI evidence**.

| # | Command | Result |
|---|---|---|
| 1 | `tools/jvm-harness/bin/bootstrap-sandbox.sh` | `main errors: 0 (files: 336)` · `compose errors: 0` · `test errors: 0 (files: 261)` |
| 2 | `tools/jvm-harness/bin/run.sh` — **baseline before this revision** | `RESULT classes=227 tests=2756 passed=2756 failed=0 ignored=0 time=36.2s` |
| 3 | `tools/jvm-harness/bin/build.sh test` (after adding the two suites) | `test errors: 0 (files: 263)` |
| 4 | `run.sh "Gate21k"` | `RESULT classes=2 tests=43 passed=43 failed=0 ignored=0` |
| 5 | `tools/jvm-harness/bin/run.sh` — **full suite after this revision** | `RESULT classes=229 tests=2799 passed=2799 failed=0 ignored=0 time=37.4s` · `grep -c "^FAIL"` → `0` |
| 6 | `tools/check-capability-pins.sh` | `checks that still hold : 13` · `disagree : 0` · `not verifiable : 0` |

Delta 2 → 5: **+2 classes, +43 tests, +0 failures** — exactly the two suites added here (27 + 16).
No previous-gate test changed result, so the GATE 11–20 regression surface is intact.

**BLOCKED (environment), not claimed:** `./gradlew clean`, `testDebugUnitTest`, `lint`,
`assembleDebug`, `assembleRelease`, `connectedDebugAndroidTest`. The wrapper cannot download a Gradle
distribution here and there is no Android SDK or device.

Three tests failed on the first run of the new suite. All three were **wrong expectations in the new
tests**, not product defects, and each was resolved against the source rather than by weakening an
assertion: `AnkiCreatedNote.model` is an `AnkiNoteModelRef?`, not an `AnkiNoteModel`; and two tests
assumed a `RETRY_ALLOWED` record survives a restart, which `normalizeAfterRestart` correctly does not
allow (OPEN-21K-01). The final assertions state the real contract.

---

## §8 Risks

1. **Harness ≠ Gradle.** The two new suites are JVM-harness green. `lint` and the Android build have
   never run on them; a Compose/Android-only compile error would not be caught here. (The new files are
   pure JVM + JUnit 4 + coroutines, so the risk is low but not zero.)
2. **Fake-only PC backend** (OPEN-21K-02). Every B-side assertion is against a controlled fake.
3. **Collection identity is null on the pinned AnkiDroid backend.** `FakeNoteCreationBackend.
   collectionIdentity` defaults to `null`, modelling AnkiDroid. The `AuthoritativeMatch` /
   `AuthoritativeMismatch` cells exercised here therefore rely on the fake's scripting; against real
   AnkiDroid the honest assurance is `NOMINAL`/`SessionContinuityVerified`, never a verified match.
4. **Source-text audits are structural, not semantic.** AUDIT-21K-* prove the forbidden spelling is
   absent; the behavioural suites carry the semantics.

---

## §9 Handoff

**To GATE 21L (Custom Study).** Still **NOT STARTED** and still **BLOCKED**: `core/anki/customstudy/`
holds only `AnkiCustomStudyCapabilities.kt`, `AnkiStudyMode.kt` and `CustomStudyPolicy.kt`; GATE 20 is
contract-only. `tools/check-capability-pins.sh` re-confirms `D3`/`C1` **ABSENT** (no filtered-deck
create/rebuild/empty endpoint in the AnkiDroid public contract, no AnkiConnect custom-study action).
Nothing in this revision unblocks it.

**To GATE 21M (Selection UI & Diagnostics).** Not started either: `ui/screens/diagnostics/` contains no
Anki-backend diagnostics (`grep -rn "backend" ui/screens/diagnostics/` matches only an STT comment),
although `AnkiBackendRoutingDiagnostics` exists in `core/anki/AnkiBackendPreference.kt`. The stable
refusal tokens this gate relies on and that a UI must surface verbatim are:
`backend_not_registered`, `collection_mismatch`, `identity_unverifiable_degraded`,
`stored_media_foreign_backend`, `created_note_foreign_backend`. Do **not** render `NOMINAL`
(disclosed-unverifiable) as "not connected".

**To GATE 21N.** `docs/GATE_21N_FINAL_INTEGRATION_REPORT.md` and `docs/GATE_21_FINAL_LOCK.md` both
predate this evidence. The final-lock matrix requires 21A–21M `LOCKED`; with this revision 21K now has
canonical executed evidence, while **21M remains NOT STARTED** and **21L remains BLOCKED**. The final
lock's overall "GATE 21 LOCKED" verdict is therefore still overstated on those two rows; an addendum
recording that has been appended to `docs/GATE_21_FINAL_LOCK.md` §17 rather than rewriting a locked
document.

---

## §10 Gate lock decision

```text
GATE 21K IMPLEMENTATION:       PRESENT AND COMPLETE AT HEAD — audited as found, no production change
GATE 21K ARCHITECTURE AUDIT:   PASS — AUDIT-21K-01 … AUDIT-21K-12 (16/16 executed tests)
GATE 21K VERIFICATION:         PASS — VER-21K-01 … VER-21K-06 + K01/K04/K05/K06 (27/27 executed tests)
GATE 21K REGRESSION:           PASS — 229 classes, 2,799 tests, 2,799 passed, 0 failed, 0 ignored
                               (measured baseline before this revision: 227 / 2,756 / 2,756 / 0)
CAPABILITY PINS:               PASS — 13 hold, 0 disagree, 0 not verifiable
GRADLE / ANDROID BUILD:        BLOCKED (ENVIRONMENT) — no Gradle distribution, no Android SDK, no device
REAL BACKEND EVIDENCE:         BLOCKED (ENVIRONMENT) — no AnkiDroid device, no PC Anki backend
GATE 21L:                      NOT STARTED / BLOCKED on GATE 20
GATE 21M:                      NOT STARTED
OVERALL GATE STATUS:           LOCKED — implemented, audited and harness-verified; Gradle, device and
                               real-backend verification remain environmentally BLOCKED and are
                               recorded as such, not as PASS
GATE LOCK:                     YES (harness-scoped)
NEXT AUTHORIZED GATE:          21M — Backend Selection UI & Diagnostics
                               (21L stays BLOCKED until GATE 20 exists)
```
