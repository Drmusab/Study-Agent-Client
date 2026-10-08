# GATE 17 — Safe editing of existing note content (fields, tags, card deck)

**Status: NOT LOCKED.** The domain, AnkiDroid adapter, composition and source audits are implemented
and tested on the JVM. The UI (Card Details entry and the edit screen) is **not built**, and the
Android build (`./gradlew testDebugUnitTest lint assembleDebug`) could not be run in this sandbox.
Gate 17 can be locked only after the UI and the Gradle/device verification are complete.

Branch: `arena/0320c17c-study-agent-client`. Base: `b4efe48`.

## Scope actually delivered

| Area | Delivered |
|---|---|
| Domain model (`core/anki/edit/`) | Separate note-mutation model: `NoteMutationId`, `NoteMutationStatus` (exactly PREPARED, SUBMITTING, APPLIED, RETRY_ALLOWED, AMBIGUOUS, CONFLICT), `NoteMutationBackendResult` (ConfirmedApplied / ConfirmedNotApplied / Conflict / OutcomeUnknown), `NoteMutationRecord`, `NoteMutationPatch`, `NoteMutationPlan`, `NoteEditBase`, `NoteEditDraft`, `NoteMutationReconciliationResult`. None of these reuse the ReviewCommit or ReviewerAction ledgers or statuses. |
| Transition table | Closed table in `NoteMutationTransition.kt`. APPLIED and CONFLICT are terminal. AMBIGUOUS cannot retry directly. Retry only from RETRY_ALLOWED with the same `NoteMutationId`. Conflict resolution creates a new mutation ID. |
| Planning | `NoteEditPlanner`: pre-transaction validation, tag normalisation (trim, reject whitespace/control, case-insensitive dedupe), minimal patch, stable deck identity by `deckId`. An empty patch yields `NoChanges` and no record. |
| Coordinator | `DefaultNoteMutationCoordinator` is the only caller of `AnkiBackend.applyNoteMutation`. Order: validate → refuse → check overlap → persist PREPARED → re-read base → persist SUBMITTING → backend write → persist classified status → authoritative refresh. No write before a durable SUBMITTING. One active mutation per note. |
| Persistence | `NoteMutationLedger` (Mutex, compare-and-set, retention of 100 terminal plus active, fail-closed on corrupt or unreadable store). `NoteMutationCodec` is versioned JSON. Only transaction metadata is persisted: IDs, plan shape, status, changed-field ordinals and names, deck refs, reason, timestamps. Field values and the base snapshot stay in memory. `DataStoreNoteMutationStore` uses its own DataStore file, `anki_note_mutation_ledger`. |
| Study safety | `StudyActivityNoteEditSafetyPolicy` checks the unresolved ReviewCommit and ReviewerAction ledgers and the presented-but-unresolved Study turn (`AnkiDroidBackend.unresolvedStudyTurnCards()`). An unknown note identity blocks. It is checked before PREPARED and again before the boundary. |
| AnkiDroid adapter | `AnkiDroidNoteMutationMapper` (pure translation and classification). `AnkiDroidNoteMutationGateway` (one provider `update` per submit, shared `AnkiDroidWritePermit`, no retry, timeout keeps the permit until the live call returns). `ProviderValue.StringValue` added. |
| Backend wiring | `AnkiDroidBackend.applyNoteMutation` and `noteMutationSemantics`. Capabilities are advertised only when the note gateway and the note-mutation writer are both wired. `editNotes` (coarse flag) stays false. `authoritativeMutationReconciliation` stays false. |
| Composition | `AppContainer` wires the gateway into the backend, and the ledger, coordinator and safety policy as `noteMutationLedger` and `noteMutationCoordinator`. |

## Pinned AnkiDroid v2.24.1 facts this gate relies on

Source: `AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt` and `api/.../Utils.kt`.

- `notes/<id>` accepts `flds` (full positional overwrite, count must match) and `tags` (full replacement) in one update. The returned count is one per key sent. `updateNote` is the only write and runs after every `require`.
- `Utils.splitFields` drops trailing empty entries (`dropLastWhile { it.isEmpty() }`). So a `flds` value whose **last** field is empty fails the count check. The coordinator refuses this before PREPARED (`TrailingEmptyFieldNotRepresentable`). A backend must prove the rule away through `NoteMutationSemantics.trailingEmptyFieldRepresentable`, and AnkiDroid does not.
- `notes/<id>/cards/<ord>` accepts only `deck_id`. It rejects filtered decks and negative ids, and it does not verify that the target deck exists. Deck existence and `isFiltered` are checked before the write.
- `SecurityException` and `IllegalArgumentException` are raised before the single write, so they are classified as ConfirmedNotApplied. Other throwables are OutcomeUnknown.
- There is no receipt, no lookup-by-id and no version token. `mod` and `usn` are not authoritative. Nothing is claimed as authoritative, and no revision token is fabricated.
- Not writable in this gate: `NOTES`, `NOTES_V2`, `NOTES_ID_CARDS`, `DECKS`, `DECKS_ID`, field renames, template or note-type writes.

## CHECKPOINT 00 — planned vs actual file mapping

| Planned | Actual | Note |
|---|---|---|
| `core/anki/edit/*` | 10 files in `core/anki/edit/` | Implemented. |
| `data/anki/ankidroid/AnkiDroidNoteMutationGateway.kt` | Implemented | |
| `data/anki/ankidroid/AnkiDroidNoteMutationMapper.kt` | Implemented | |
| `data/anki/remote/PcAnkiBackend.kt` | **Not present in this repository.** No PC backend exists on this branch. | Nothing to change. Not created. |
| `data/anki/fake/FakeAnkiBackend.kt` | **No such main-source file.** The fake lives in test sources. | It inherits the default refusals, so it never claims note editing. |
| `ui/screens/editnote/*` | **Not built** | Card Details entry and edit screen are outstanding. |
| `ui/components/anki/NoteFieldEditor.kt`, `TagsEditor.kt`, `DeckSelector.kt` | **Not built** | Outstanding. |
| (not planned) `data/anki/DataStoreNoteMutationStore.kt` | Added | Persistence for the ledger, mirroring the reviewer-action store. |
| (not planned) `AnkiDroidCompatibilityPolicy`, `AnkiDroidProviderClient`, `AnkiBackend`, `AnkiCapabilities`, `AppContainer` | Edited | Capability claims, `StringValue`, default interface members, composition. |

## Checkpoint reports

Format: FILE / STATUS / RESPONSIBILITY / CHANGES / LOCAL CHECKS / ARCHITECTURE CHECK / READY FOR NEXT FILE.

**core/anki/edit/ (10 files)**
- STATUS: complete (domain). Compiled with Kotlin 1.9.24 (`/tmp/build_core.sh`), zero errors.
- CHANGES: new package. Coordinator, planner, ledger, codec, transitions, models, safety policy, backend-result types.
- LOCAL CHECKS: 76 domain tests pass (transition table, planner, ledger, coordinator). See Verification.
- ARCHITECTURE CHECK: audit tests pass. No reuse of ReviewCommit or ReviewerAction types, only the coordinator writes, no add, delete, force or retry-of-AMBIGUOUS path, no rendered content persisted.
- READY FOR NEXT FILE: yes.

**data/anki/ankidroid/AnkiDroidNoteMutationMapper.kt + AnkiDroidNoteMutationGateway.kt**
- STATUS: complete (JVM-verified), Android compile not run.
- CHANGES: pure mapper and classifier, gateway with shared permit. `ProviderValue.StringValue` in `AnkiDroidProviderClient.kt`.
- LOCAL CHECKS: 21 tests pass (mapper: 16, gateway: 5). Run against verbatim copies of `ProviderValue`/`ProviderUpdateResult` and a scratch copy of `AnkiDroidApiContract` with its constants hoisted (see Known issues).
- ARCHITECTURE CHECK: `AnkiDroidIntegrationIsolationTest` allowlist updated to name the new writer. This is a deliberate third sanctioned writer, not a relaxation.
- READY FOR NEXT FILE: yes, pending Gradle compile.

**data/anki/ankidroid/AnkiDroidBackend.kt + AnkiDroidCompatibilityPolicy.kt**
- STATUS: complete, not compiled (Android).
- CHANGES: `noteMutationGateway` parameter (default null), `withWriteSupport` gating, `noteMutationSemantics`, `applyNoteMutation`, `unresolvedStudyTurnCards`. Compatibility claims for the three edit capabilities.
- LOCAL CHECKS: source reviewed. The existing `AnkiDroidBackendTest` expects `AnkiCapabilities.NONE` when unready, which still holds.
- ARCHITECTURE CHECK: `theBackendClaimsOnlyWhatItWiresAndNeverClaimsReconciliation` passes.
- READY FOR NEXT FILE: pending Gradle compile.

**data/anki/DataStoreNoteMutationStore.kt + di/AppContainer.kt**
- STATUS: complete, not compiled (Android).
- CHANGES: DataStore store in its own file. Composition of gateway, ledger, safety policy and coordinator.
- LOCAL CHECKS: source reviewed against the reviewer-action store and the `AppContainer` constructors.
- ARCHITECTURE CHECK: `theSharedDataStoreFileIsSeparateFromEveryOtherLedgerFile` passes.
- READY FOR NEXT FILE: pending Gradle compile.

**UI (`ui/screens/editnote/*`, `NoteFieldEditor.kt`, `TagsEditor.kt`, `DeckSelector.kt`, Card Details entry)**
- STATUS: **NOT STARTED.**
- READY FOR NEXT FILE: no. This is the outstanding gate work.

## Verification

Run in the sandbox with Kotlin 1.9.24 (the repo's pinned version) on JDK 17. Results on the final tree:

| Suite | Result |
|---|---|
| Domain: transition, planner, ledger, coordinator (`app/src/test/.../anki/edit/`) | 76 passed, 0 failed |
| Data layer: mapper and gateway (`AnkiDroidNoteMutation*Test`) | 21 passed, 0 failed (JVM slice) |
| Architecture audit (`Gate17ArchitectureAuditTest`) | 16 passed, 0 failed |

Verification IDs: the spec text for VER-17-01..29 and AUDIT-17-01..14 is not in the repository, and its wording was not preserved in this session. The tests are named by the rule they enforce, not by spec identifier. **Full VER/AUDIT mapping is therefore not claimed.**

### BLOCKED (not executed here)

- `./gradlew testDebugUnitTest`, `lint`, `assembleDebug`, clean and release builds. The Gradle distribution, the Android SDK and Maven repositories are unreachable from the sandbox (`services.gradle.org`, `dl.google.com`, `repo1.maven.org` return no route).
- Android-only code is not compiled: `AnkiDroidBackend`, `AnkiDroidCompatibilityPolicy`, `AnkiDroidProviderClient`, `DataStoreNoteMutationStore`, `AppContainer`.
- The existing JVM suites that depend on Android or on `kotlinx-coroutines-test` (`runTest`) were not re-run.
- Device verification of provider behaviour.

## Known issues and decisions

1. **Pre-existing compile error at `HEAD` under Kotlin 1.9.24 (K1).** `AnkiDroidApiContract.kt` uses `const val` constants (for example `CARD_DUE_COLUMN`, line 552) in `CARD_PROJECTION` (line 354), before their declaration. K1 rejects this with "variable must be initialized". K2 (2.4.21) accepts it. The build does not enable K2. This file is not modified by GATE 17. The sandbox slice uses a scratch copy with the constants hoisted. **Recommended fix:** move the `const val` declarations above their first use. That is behaviour-neutral. It was not applied because it is outside GATE 17 scope, and it must be confirmed by a real Gradle build.
2. **Trailing empty field cannot be edited through AnkiDroid's provider.** A note whose last field is empty cannot have any field written. The refusal is `TrailingEmptyFieldNotRepresentable`, raised before any transaction. The user must fill that field first, or the edit is blocked. This must be surfaced in the UI.
3. **AMBIGUOUS blocks further edits to the note, and no manual resolution transition exists.** This is an open product decision. Options: a user-confirmed "I checked, it did not apply" action (which needs a new transition), or leaving the block in place.
4. **Reconciliation is not authoritative at this pin.** `reconcileNoteMutation` is not overridden, so an AMBIGUOUS record stays AMBIGUOUS until a human resolves it (see 3).
5. **Force overwrite and rollback are not implemented**, as the gate requires.
6. **PcAnkiBackend and a main-source FakeAnkiBackend do not exist in this repo.** Nothing was written for them.
