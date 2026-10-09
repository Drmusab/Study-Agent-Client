# GATE 17 — Safe editing of existing note content (fields, tags, card deck)

**Status: LOCKED — contract locked, implementation complete, verified on the JVM harness.**
One environmental residual stays open and is recorded rather than hidden: the Android build
(`./gradlew testDebugUnitTest lint assembleDebug`) and any device verification could not run in this
sandbox (R6 in the contract report).

- Contract: `docs/GATE_17_BACKEND_CONTRACT.md` → `RESULT: BACKEND CONTRACT LOCKED`,
  `IMPLEMENTATION AUTHORIZED: YES` (§27–§28). No production mutation code was written before that lock.
- Branch: `arena/856e2f20-study-agent-client`. Base: `dd6dc18`.
- Verification headline: harness build **main 0 / compose 0 / test 0 errors**; GATE 17 suites
  **241 passed, 0 failed** (10 classes); whole repository **2013 passed, 0 failed** (170 classes).

## PART 0 result (summary)

PART 0 resolved the write surfaces of both backends independently, from sources read at pinned
revisions (E1–E13 in the contract document). The decisive findings:

| Question | Answer | Evidence |
|---|---|---|
| Field write | `content://<auth>/notes/<id>` with one `flds` string, positional, count-checked | E1 L495–536 |
| Tag write | same call, `tags` key, **replace-only**, canonified by the backend | E1 L520–527, E8, E10 |
| Fields + tags | **one** provider call → **one** `transact(Op::UpdateNote)` → atomic | E1 L535, E8, E11 |
| Deck write | `notes/<id>/cards/<ord>` with `deck_id` → separate `transact(Op::UpdateCard)`, **card-scoped** | E1 L538–563, E11 |
| Success evidence | row count = number of `ContentValues` keys — proves the provider path completed, **not** the stored value | E1 |
| Proven non-application | `NotDispatched`, `SecurityException`, `IllegalArgumentException`, `NumberFormatException` — all pre-write | E1, E8, E9, E13 |
| Everything else | `OutcomeUnknown` (binder-wrapped `RuntimeException`/`BackendException`, `-1`, timeouts) | E9, E13 |
| Idempotency | content writes are *effectively* no-ops when unchanged; deck writes always write; no backend key → `idempotentReplay = false` | E4, E11 |
| Conflict detection | none in the backend → best-effort pre-save re-read only | E1 |
| Reconciliation | none (`mod`/`usn` are not tokens) → a human attestation is the only escape from AMBIGUOUS | E1, E8 |
| PC backend | no note-mutation message exists → **UNSUPPORTED**, capabilities off, no code | E6 |

First-pass findings R1–R5 were resolved in the second pass; R6–R10 are accepted residuals
(contract §24). No critical `UNKNOWN` affecting mutation safety remains, which is what authorized the
lock.

## Scope delivered

| Area | Delivered |
|---|---|
| Domain model (`core/anki/edit/`) | 11 files. `NoteMutationId`, `NoteMutationStatus` (exactly PREPARED, SUBMITTING, APPLIED, RETRY_ALLOWED, AMBIGUOUS, CONFLICT), `NoteMutationBackendResult`, `NoteMutationRecord`, `NoteMutationPatch`, `NoteMutationPlan`, `NoteEditBase`, `NoteEditDraft`, `NoteMutationVerification` + `NoteContentCanonicalization`, `NoteMutationAttestation`. No reuse of the ReviewCommit or ReviewerAction ledgers, statuses or records. |
| Transition table | Closed table in `NoteMutationTransition.kt`, extended with the three edges the contract requires: `PostWriteVerificationFailed` → AMBIGUOUS, `UserAttestedApplied` → APPLIED, `UserAttestedNotApplied` → CONFLICT. APPLIED and CONFLICT are terminal; AMBIGUOUS has no automatic replay edge. |
| Planning | `NoteEditPlanner`: pre-transaction validation, tag normalization, minimal patch, stable deck identity by `deckId`, content-before-deck plan order, empty patch → no plan and no record. |
| Verification | `NoteMutationVerifier.verify` (post-write gate) and `verifyIntent` (read-only evidence for a human), both canonicalization-tolerant and both pure. |
| Coordinator | `DefaultNoteMutationCoordinator` is the only caller of `AnkiBackend.applyNoteMutation`. Order: validate → refuse unsupported/trailing-empty → verify deck target → refuse if another mutation owns the note → study-safety → PREPARED durable → re-read base (drift ⇒ CONFLICT, no write) → SUBMITTING durable → per-operation index durable → one backend call per step → classify → last step: up to 2 authoritative reads → verify → APPLIED or AMBIGUOUS. Plus `retry`, `resumePrepared`, `startAfterConflict`, `recover`, `resolveAmbiguous`, `activeMutationFor`. |
| Persistence | `NoteMutationLedger` (Mutex, compare-and-set, retention, fail-closed), versioned JSON codec, metadata only. `DataStoreNoteMutationStore` in its own file `anki_note_mutation_ledger`. |
| Study safety | `StudyActivityNoteEditSafetyPolicy` over the unresolved ReviewCommit and ReviewerAction ledgers and the presented-but-unresolved Study turn; consulted before PREPARED **and** again before the first write. Enforced in the domain, not in the UI. |
| AnkiDroid adapter | `AnkiDroidNoteMutationMapper` (pure translation + boundary classification) and `AnkiDroidNoteMutationGateway` (one provider `update` per submit, shared write permit, no retries, 15 s write / 5 s permit timeouts). |
| Backend wiring | `AnkiDroidBackend.applyNoteMutation` + `noteMutationSemantics = ANKIDROID_V2_24_1`; capabilities advertised only when both gateways are wired; coarse `editNotes = false`; `authoritativeMutationReconciliation = false`. |
| **Edit UI (new this pass)** | `ui/screens/editnote/` — `EditNoteModels.kt`, `EditNoteMapper.kt`, `EditNoteViewModel.kt`, `EditNoteScreen.kt`; `ui/components/anki/` — `NoteFieldEditor.kt`, `TagsEditor.kt`, `DeckSelector.kt`. |
| **Entry point (new this pass)** | Card Details gains a capability-gated `onOpenNoteEditor` affordance (absent, not disabled, when no editing dimension is offered). The screen itself stays read-only. |
| **Navigation (new this pass)** | `Screen.EditNote` (`edit-note/{cardRef}`, Base64url token) and its destination in `AppNavHost`, which binds the editor to the backend that owns the card and re-reads Card Details after a save. |
| Composition | `AppContainer.noteMutationCoordinator` (already wired) is the only write path the editor is given. |

## Pinned AnkiDroid v2.24.1 facts this gate relies on

- `notes/<id>` accepts `flds` (full positional overwrite, count must match) and `tags` (full
  replacement) in one update; the returned count is one per key sent; `col.updateNote` is the only
  write and runs after every `require`.
- `Utils.splitFields` drops trailing empty entries, so a `flds` value whose **last** field is empty
  fails the count check. Refused before PREPARED as `TrailingEmptyFieldNotRepresentable`; a backend
  must prove otherwise through `NoteMutationSemantics.trailingEmptyFieldRepresentable`, and AnkiDroid
  does not.
- `notes/<id>/cards/<ord>` accepts only `deck_id`, rejects filtered decks and negative ids, and does
  not verify that the target exists — so existence and `isFiltered` are checked against a live deck
  listing before the write, and an unverifiable deck is refused.
- `SecurityException`, `IllegalArgumentException` and `NumberFormatException` are raised before the
  single write, and a backend (Rust) error can never arrive as one of them, so they are proven
  non-application. Every other throwable is `OutcomeUnknown`.
- Tags are canonified by rslib: NFC per `::` component, control/separator stripping, registered-case
  adoption, case-insensitive dedupe, blank component → `blank`, sorted storage. Order and case are
  backend-owned and carry no evidence.
- Fields are normalized by rslib: ASCII control characters other than `\n`/`\t` are removed, and NFC
  is applied when the collection prefers it (that preference is not readable → both sides are
  compared in NFC).
- A field edit can make Anki generate sibling cards, placed by Anki (template target deck, else the
  notetype's last-added deck). The row count does not report them and the UI says so before the save.
- There is no receipt, no lookup-by-id and no version token; `mod`/`usn` are not authoritative. No
  revision token is fabricated anywhere in this gate.
- Not writable in this gate: `NOTES`, `NOTES_V2`, `NOTES_ID_CARDS`, `DECKS`, `DECKS_ID`, field
  renames, template or note-type writes, and any add/delete of notes, cards or media (GATE 18).

## PART I-A — checkpoint reports

Format: FILE / STATUS / CONTRACT REQUIREMENT IMPLEMENTED / RESPONSIBILITY / CHANGES / LOCAL CHECKS /
ARCHITECTURE CHECK / READY FOR NEXT FILE. Files delivered before the lock keep their earlier
checkpoints in this repository's history; the entries below are the files this pass added or changed.

**`core/anki/edit/NoteMutationVerification.kt` (new)**
- STATUS: complete, compiles and tested on the JVM.
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-06 (success evidence), CONTRACT-15 (post-mutation read
  and backend canonicalization), CONTRACT-12 (no authoritative reconciliation → evidence for a human).
- RESPONSIBILITY: the only place that decides whether a stored state matches an intended one, and the
  read-only comparison offered to a human for an AMBIGUOUS record.
- CHANGES: `NoteContentCanonicalization` (field control-stripping with U+001F deliberately preserved,
  NFC, positional field equivalence, case-insensitive unordered tag equivalence, blank-hierarchy
  detection), `NoteMutationVerification` (MatchesIntent / DiffersFromIntent / Unverifiable),
  `NoteMutationVerifier.verify` + `verifyIntent`, `NoteMutationIntent`.
- LOCAL CHECKS: `NoteMutationVerificationTest` — 31 passed.
- ARCHITECTURE CHECK: pure (no Android, no Compose, no provider type); never names a status, so it
  cannot change one; `Gate17ArchitectureAuditTest` passes.
- READY FOR NEXT FILE: yes.

**`core/anki/edit/NoteMutationCoordinator.kt` (changed)**
- STATUS: complete, compiles and tested.
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-06/-15 (APPLIED only after a verifying read),
  CONTRACT-08 (ambiguity), CONTRACT-12/-26 (attestation and blocking), CONTRACT-24 (multi-step save).
- RESPONSIBILITY: the only writer; owns ordering, classification, partial application and recovery.
- CHANGES: verification-gated `finishApplied` + `readForVerification` (2 attempts), `stateEvidence`,
  `resolveAmbiguous(mutationId, attestation)`, `activeMutationFor(backendId, noteId)`, evidence in the
  AMBIGUOUS paths, class documentation of steps 7–9.
- LOCAL CHECKS: `NoteMutationCoordinatorTest` — 47 passed, including
  `appliedWriteWithoutAnAuthoritativeReadIsNeverReportedAsApplied`,
  `appliedWriteThatReadsBackDifferentValuesIsRecordedAsAmbiguousNotApplied`,
  `attestationIsNeverReportedUnlessItIsDurable`.
- ARCHITECTURE CHECK: `onlyTheCoordinatorInvokesABackendNoteWrite`,
  `noForceOverwriteOrSilentRetryOfAnAmbiguousWriteExists`,
  `theDurableOrderIsPreparedThenSubmittingThenBackendThenTerminal`,
  `theCoordinatorChecksStudyOverlapBeforeTheTransactionAndAgainBeforeTheWrite` — all pass.
- READY FOR NEXT FILE: yes.

**`core/anki/edit/NoteMutationTransition.kt`, `NoteMutationBackendResult.kt`, `NoteEditModels.kt`, `NoteEditPlanner.kt` (changed)**
- STATUS: complete, compiles and tested.
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-19…-23 (normalized operation/result/semantics models),
  CONTRACT-25/-26 (retry and recovery policy), CONTRACT-02 (tag model), CONTRACT-14 (field identity).
- CHANGES: three new events (`PostWriteVerificationFailed`, `UserAttestedApplied`,
  `UserAttestedNotApplied`), `NoteMutationAttestation`, three new `NoteMutationReason` values
  (`POST_WRITE_VERIFICATION_MISMATCH`, `POST_WRITE_UNVERIFIED`, `USER_ATTESTED_*`), semantics fields
  for canonicalization and no-op behaviour, planner normalization shared with the verifier.
- LOCAL CHECKS: `NoteMutationTransitionTest` 13, `NoteEditPlannerTest` 17 — all passed; the closed
  table is checked exhaustively over every (status × event) pair.
- ARCHITECTURE CHECK: `theNoteMutationStatusEnumIsExactlyTheSpecifiedSet`,
  `noteMutationTypesDoNotReuseTheReviewCommitOrReviewerActionLedgersOrStatuses`,
  `noFabricatedRevisionTokenIsIntroduced` — pass.
- READY FOR NEXT FILE: yes.

**`data/anki/ankidroid/AnkiDroidNoteMutationMapper.kt` (changed)**
- STATUS: complete, compiles and tested on the JVM (pure Kotlin, no Android type).
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-05/-07 (mutation boundary and proven non-application) —
  the R1 resolution.
- CHANGES: `NumberFormatException` joins `SecurityException` and `IllegalArgumentException` as proven
  pre-write non-application; the evidence chain (E1 → E8 → E9 → E13) is documented at the
  classification site; everything else stays `OutcomeUnknown`.
- LOCAL CHECKS: `AnkiDroidNoteMutationMapperTest` — 19 passed, including
  `aNumericParseFailureIsProvenNonApplicationBecauseItPrecedesTheWrite`,
  `aBinderWrappedBackendErrorIsUnknownNeverProvenNonApplication`,
  `onlyTheThreeProvenPreWriteFailuresAreEverConfirmedNotApplied`.
- ARCHITECTURE CHECK: `aNoteWriteValueIsConstructedOnlyByTheMapper`,
  `deckIdentityIsTheStableIdNeverTheDeckName` — pass.
- READY FOR NEXT FILE: yes.

**`ui/screens/editnote/EditNoteModels.kt` (new)**
- STATUS: complete, compiles (now covered by the harness `pureui` layer) and tested indirectly.
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-16 (capability granularity), INV-17-17 (the UI decides
  nothing): every "may I edit this" answer is a derived property, not a screen computation.
- RESPONSIBILITY: the presentation model — capabilities, field/tag/deck state, notices, save states,
  blocked-mutation affordances.
- CHANGES: new file. `EditNoteUiState` (Loading / Unavailable / Error / Refused / Ready),
  `EditNoteCapabilities`, `EditNoteFieldState`, `EditNoteTagsState`, `EditNoteDeckState`,
  `EditNoteDeckOption`, `EditNoteIssue`, `EditNoteNotice`, `EditNoteSaveState`, `EditNoteEditorState`
  (`dirty`, `canSave`), `EditNoteBlockedMutation`.
- LOCAL CHECKS: `EditNoteMapperTest` — 50 passed.
- ARCHITECTURE CHECK: `theEditProjectionAndItsModelArePureKotlin`,
  `theEditPresentationVocabularyNeverRenamesADurableStatus`,
  `theSaveControlIsEnabledOnlyByTheMappedFlag` — pass. The GATE 11B vocabulary lock
  (`ReviewCommitVocabularyLockTest`) forced the retired spelling `Retryable` out of this layer; the
  presentation case is `RetryAvailable`, matching `RatingCommitUiState`.
- READY FOR NEXT FILE: yes.

**`ui/screens/editnote/EditNoteMapper.kt` (new)**
- STATUS: complete, compiles and tested.
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-02/-03/-11/-12/-16, and the wording rules the gate
  states explicitly (card-scoped deck, best-effort conflict detection, unprovable ambiguity,
  attestation is the user's).
- RESPONSIBILITY: the single projection between domain and screen: `baseOrRefusal`, `capabilitiesOf`,
  `project`, `notices`, `saveStateOf`, `blockedMutation`, `statusLabel`, `reasonMessage`,
  `evidenceMessage`, `attestationLabel`, `tagInputResult`, `validationMessages`, `refusalMessage`,
  `message`.
- CHANGES: new file (507 lines), pure Kotlin.
- LOCAL CHECKS: `EditNoteMapperTest` — 50 passed.
- ARCHITECTURE CHECK: `theComposeEditLayerMakesNoCapabilityOrContractDecision`,
  `deckWordingIsCardScopedThroughoutTheUiLayer`, `tagWordingIsReplaceOnlyThroughoutTheUiLayer` — pass.
- READY FOR NEXT FILE: yes.

**`ui/screens/editnote/EditNoteViewModel.kt` (new)**
- STATUS: complete, compiles and tested end to end against the real coordinator and ledger.
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-24/-25/-26 (multi-step save, retry, crash recovery),
  CONTRACT-28 (active-Study interaction), INV-17-14 (post-write truth overrides draft truth),
  VER-17-15 (backend-context correlation).
- RESPONSIBILITY: holds one authoritative base, the draft, the deck listing and the last outcome;
  routes every write through the coordinator; correlates each response with the backend instance and
  card reference it was started for.
- CHANGES: new file (712 lines). `blockOn` assigns the editor state itself, so an outcome branch
  cannot silently drop a blocking record (a real defect this pass's tests found and fixed).
- LOCAL CHECKS: `EditNoteViewModelTest` — 23 passed, including
  `aDimensionTheBackendDoesNotOfferIsRefusedEvenIfTheUiAsksForIt`,
  `anUnresolvedMutationBlocksTheEditorAndIsNeverWrittenOver`,
  `aSavedEditWritesOnceAndAdoptsTheBackendReadAsItsTruth`.
- ARCHITECTURE CHECK: `theEditViewModelWritesOnlyThroughTheCoordinator`,
  `theUiLayerHasNoDirectProviderOrAnkiConnectAccess` — pass.
- READY FOR NEXT FILE: yes.

**`ui/screens/editnote/EditNoteScreen.kt` (new)**
- STATUS: complete, compiles against the harness Compose stubs.
- CONTRACT REQUIREMENT IMPLEMENTED: INV-17-17 (no decision in Compose), AUDIT-17-12 (an unsupported
  affordance is absent, not disabled-looking).
- RESPONSIBILITY: renders `EditNoteUiState`; gates Save on `editor.canSave` and each recovery control
  on the mapped `canRetry` / `canResume` / `canRecover` / `canAttest` / `canRestartAfterConflict`.
- CHANGES: new file (410 lines); test tags `edit_note`, `edit_note_{blocked,retry,recover,attest_applied,attest_absent,restart,save}`.
- LOCAL CHECKS: compiles in the Compose scope; behaviour is asserted through the mapper and ViewModel
  suites (no instrumentation framework is available in this sandbox).
- ARCHITECTURE CHECK: `theComposeEditLayerMakesNoCapabilityOrContractDecision`,
  `theSaveControlIsEnabledOnlyByTheMappedFlag`, `theRecoveryControlsFollowTheMappedAffordances`,
  `theEditorExposesStableTestTagsForEveryAffordance` — pass.
- READY FOR NEXT FILE: yes.

**`ui/components/anki/NoteFieldEditor.kt`, `TagsEditor.kt`, `DeckSelector.kt` (new)**
- STATUS: complete, compile against the Compose stubs.
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-01/-14 (field identity is the ordinal, the name is a
  label), CONTRACT-02 (replace-only tags), CONTRACT-03 (card-scoped deck move, stable deck id).
- RESPONSIBILITY: three dumb controls. They receive `editable`/`selectable` flags and emit intents;
  they contain no capability or contract logic.
- CHANGES: new files (110 / 179 / 163 lines). `TagsEditor` uses `@OptIn(ExperimentalLayoutApi::class)`
  with `FlowRow` (the module opts in only to `ExperimentalMaterial3Api`). `DeckSelector` disables
  filtered and unverifiable decks with a reason and offers "Keep the current deck".
- LOCAL CHECKS: compile clean; wording pinned by `deckWordingIsCardScopedThroughoutTheUiLayer` and
  `tagWordingIsReplaceOnlyThroughoutTheUiLayer`.
- ARCHITECTURE CHECK: `theComposeEditLayerMakesNoCapabilityOrContractDecision` — pass.
- READY FOR NEXT FILE: yes.

**`ui/screens/carddetails/{CardDetailsModels,CardDetailsMapper,CardDetailsViewModel,CardDetailsScreen}.kt` (changed)**
- STATUS: complete, compiles (screen in the Compose scope), existing suites still pass.
- CONTRACT REQUIREMENT IMPLEMENTED: CONTRACT-16 (per-dimension capabilities decide whether editing is
  offered at all), AUDIT-17-12 (absent, never silently degraded).
- RESPONSIBILITY: the entry point. Card Details stays read-only; it only reports whether the connected
  backend offers any note-editing dimension and, if so, offers to open the editor for that card.
- CHANGES: `canOpenNoteEditor` in the model and mapper; `noteEditingOffered` in the ViewModel's
  backend snapshot and request token (`editNoteFields || editNoteTags || changeCardDeck`); an
  `Icons.Default.Edit` action gated on `ready?.canOpenNoteEditor == true` and a non-null
  `onOpenNoteEditor`, with test tag `card_details_open_editor`.
- LOCAL CHECKS: `CardDetailsMapperTest`, `CardDetailsViewModelTest` and the GATE 16 audits still pass
  (whole-suite run: 2013/2013).
- ARCHITECTURE CHECK: `theCardDetailsEntryIsCapabilityGatedAndTheScreenStaysReadOnly` passes; the
  GATE 16 rule "details UI and ViewModel have no mutation calls or provider types" still passes —
  hence the names `onOpenNoteEditor` / `canOpenNoteEditor` / `noteEditingOffered`.
- READY FOR NEXT FILE: yes.

**`ui/navigation/Screen.kt`, `ui/navigation/AppNavHost.kt` (changed)**
- STATUS: complete, compiles (AppNavHost is outside the harness Compose scope; Screen.kt is pure and
  compiled in the main layer).
- CONTRACT REQUIREMENT IMPLEMENTED: INV-17-14 (post-save authoritative re-read), CONTRACT-13 (identity
  requirements: a stable, backend-qualified card reference).
- RESPONSIBILITY: route + destination. The route carries an encoded `AnkiCardRef` and nothing else; the
  destination refuses a null or foreign-backend reference instead of opening the editor against
  another backend, and constructs the ViewModel with the owning backend and the shared coordinator.
- CHANGES: `Screen.EditNote` (`edit-note/{cardRef}`) with `createRoute`/`decodeCardRef`; the card-ref
  token logic extracted into shared private `cardRefToken`/`cardRefFromToken`; the `Screen.EditNote`
  composable; a `NOTE_EDIT_SAVED_KEY` saved-state signal that makes Card Details call `refresh()`
  after a save.
- LOCAL CHECKS: `theEditorIsReachedByReferenceBoundToTheOwningBackend` (source audit) plus the
  GATE 16 navigation audits, which still pass.
- ARCHITECTURE CHECK: the audit asserts the registry lookup, the coordinator injection and that no
  note content appears in navigation.
- READY FOR NEXT FILE: yes.

**`app/src/test/.../anki/edit/NoteMutationVerificationTest.kt`, `ui/EditNoteMapperTest.kt`, `ui/EditNoteViewModelTest.kt` (new); `NoteMutationCoordinatorTest.kt`, `NoteMutationTransitionTest.kt`, `AnkiDroidNoteMutationMapperTest.kt`, `Gate17ArchitectureAuditTest.kt` (changed)**
- STATUS: complete; 130 new test cases across these files (104 in the three new suites, 26 added to the four existing ones).
- CONTRACT REQUIREMENT IMPLEMENTED: PART II and PART III below.
- CHANGES: three new suites; the coordinator suite gained the verification, attestation and
  blocking cases; the transition suite gained the three new edges in its closed table; the mapper
  suite gained the R1 classification cases; the audit gained 12 UI/navigation rules.
- LOCAL CHECKS: 241 GATE 17 tests pass; 2013 repository tests pass.
- ARCHITECTURE CHECK: n/a (these are the checks).
- READY FOR NEXT FILE: yes.

**`tools/jvm-harness/bin/build.sh`, `tools/jvm-harness/shims/compose/{AnimationTransitions,FoundationLayout,Material3}.kt` (changed)**
- STATUS: complete; harness reports main 0 / compose 0 / test 0 errors.
- CONTRACT REQUIREMENT IMPLEMENTED: none directly — this is verification infrastructure.
- RESPONSIBILITY: make the new UI compile and run on the JVM in the absence of Gradle/Android SDK.
- CHANGES: a `pureui()` layer so pure Kotlin files under `ui/` are actually compiled (they were
  silently skipped before, which is why the first `EditNoteMapper` bugs went unseen); the Compose scope
  extended with `AppControls.kt`, the three new controls and `EditNoteScreen.kt`; three shim gaps fixed
  to match the real APIs (`EnterTransition + EnterTransition`, a single `FlowRow` overload with a
  `FlowRowScope` receiver, `AssistChip` with `trailingIcon`/`enabled`/`shape`/`colors`/`border` plus
  `AssistChipDefaults`/`ChipColors`).
- LOCAL CHECKS: the whole suite runs green through the harness.
- ARCHITECTURE CHECK: shims are stubs only; no production type is weakened by them.
- READY FOR NEXT FILE: yes.

## PART II — architecture audit (AUDIT-17-01 … AUDIT-17-14)

Enforced by `Gate17ArchitectureAuditTest` (27 tests, all passing). The spec text for these IDs is not
in the repository, so each ID is assigned here to the rule it names, and the enforcing test is cited.

| ID | Rule | Enforcing test |
|---|---|---|
| AUDIT-17-01 | The status vocabulary is exactly the six specified values | `theNoteMutationStatusEnumIsExactlyTheSpecifiedSet` |
| AUDIT-17-02 | Note mutations never reuse the rating/reviewer-action transaction families | `noteMutationTypesDoNotReuseTheReviewCommitOrReviewerActionLedgersOrStatuses` |
| AUDIT-17-03 | Exactly one component invokes a backend note write | `onlyTheCoordinatorInvokesABackendNoteWrite` |
| AUDIT-17-04 | The only provider note write runs through the shared permit and the mapper, and never retries | `theOnlyProviderNoteWriteRunsThroughTheSharedPermitAndTheMapper` |
| AUDIT-17-05 | A typed write value is constructed only by the mapper | `aNoteWriteValueIsConstructedOnlyByTheMapper` |
| AUDIT-17-06 | No add/delete of notes, cards, note types or media exists in GATE 17 code | `noAddOrDeleteNoteOrNoteTypeWriteExistsInGateSeventeenCode` |
| AUDIT-17-07 | No force-overwrite path and no silent retry of an ambiguous write | `noForceOverwriteOrSilentRetryOfAnAmbiguousWriteExists` |
| AUDIT-17-08 | No fabricated revision token; deck identity is the stable id, never a name; nothing persisted carries note content | `noFabricatedRevisionTokenIsIntroduced`, `deckIdentityIsTheStableIdNeverTheDeckName`, `persistedCodecCarriesMetadataOnlyNeverFieldValues`, `theAnkiDroidNoteWriteStatusIsNeverBuiltFromRenderedContent` |
| AUDIT-17-09 | The Compose layer makes no capability or contract decision | `theComposeEditLayerMakesNoCapabilityOrContractDecision` |
| AUDIT-17-10 | The projection and its model are pure Kotlin (JVM-testable) and the editor is exactly four files | `theEditProjectionAndItsModelArePureKotlin` |
| AUDIT-17-11 | One derived flag decides whether a write may be offered; the screen only reads it | `theSaveControlIsEnabledOnlyByTheMappedFlag` |
| AUDIT-17-12 | Recovery affordances follow the mapped status; an unsupported one is absent, not disabled | `theRecoveryControlsFollowTheMappedAffordances`, `theCardDetailsEntryIsCapabilityGatedAndTheScreenStaysReadOnly` |
| AUDIT-17-13 | The editor is reached by reference, bound to the owning backend; a save forces an authoritative re-read; navigation carries no note content | `theEditorIsReachedByReferenceBoundToTheOwningBackend` |
| AUDIT-17-14 | Contract wording is card-scoped for decks and replace-only for tags, everywhere in the UI; the backend claims only what it wires and never claims reconciliation | `deckWordingIsCardScopedThroughoutTheUiLayer`, `tagWordingIsReplaceOnlyThroughoutTheUiLayer`, `theBackendClaimsOnlyWhatItWiresAndNeverClaimsReconciliation` |

Also enforced, outside this class: `theUiLayerHasNoDirectProviderOrAnkiConnectAccess`,
`theEditViewModelWritesOnlyThroughTheCoordinator`, `theEditorExposesStableTestTagsForEveryAffordance`,
`theEditPresentationVocabularyNeverRenamesADurableStatus`,
`theCoordinatorChecksStudyOverlapBeforeTheTransactionAndAgainBeforeTheWrite`,
`theDurableOrderIsPreparedThenSubmittingThenBackendThenTerminal`,
`theSharedDataStoreFileIsSeparateFromEveryOtherLedgerFile`, and the pre-existing GATE 11B/16 audits.

## PART III — verification (VER-17-01 … VER-17-22 + backend-specific)

One table, as PART VI requires. Suite totals: edit mapper 50, coordinator 43, verification 31, architecture audit 27, edit ViewModel 23,
AnkiDroid mapper 19, planner 17, transition 14, ledger 12, AnkiDroid gateway 5 —
**241 GATE 17 tests, 0 failures**; whole repository **2013, 0 failures**.

| ID | Guarantee verified | Test |
|---|---|---|
| VER-17-01 | An empty patch performs zero writes and records nothing | `emptyDraftIsNoChangesAndWritesNothing`, `emptyPatchNeverBecomesAPlan`, `savingWithoutAChangeWritesNothingAndRecordsNothing` |
| VER-17-02 | Unchanged values are not a change (a case-only tag change is) | `unchangedValuesDoNotCreateAPatch`, `tagCaseChangeIsARealChange` |
| VER-17-03 | Validation is pre-transaction and never yields RETRY_ALLOWED | `validationErrorIsPreTransactionAndNeverRetryAllowed`, `unknownFieldOrdinalIsRejectedBeforeAnyTransaction`, `fieldSeparatorInValueIsRejected` |
| VER-17-04 | A trailing empty field is refused before any transaction | `clearingTheLastFieldIsRefusedBeforeAnyTransactionBecauseTheProviderDropsTrailingEmpties`, `editingAnEarlierFieldWhileTheLastFieldIsNonEmptyIsNotBlockedByThatRule`, `anEmptyLastFieldIsRefusedWhenTheBackendCannotRepresentIt` |
| VER-17-05 | Unsupported dimensions are refused without recording anything | `unsupportedDimensionIsRefusedWithoutRecordingAnything`, `backendWithoutRereadCannotEditAtAll`, `aDimensionTheBackendDoesNotOfferIsRefusedEvenIfTheUiAsksForIt` |
| VER-17-06 | Durable order: PREPARED → SUBMITTING → write → verification → terminal | `happyPathPersistsPreparedThenSubmittingThenWritesThenVerifiesThenApplied`, `noBackendWriteWithoutDurableSubmitting`, `preparedNotPersistedMeansNothingIsReadOrSent`, `backendOperationStepsAreNeverIssuedWithoutAPersistedIndex` |
| VER-17-07 | A claimed write is APPLIED only after an authoritative read holds the intent | `appliedWriteWithoutAnAuthoritativeReadIsNeverReportedAsApplied`, `appliedWriteThatReadsBackDifferentValuesIsRecordedAsAmbiguousNotApplied`, `aReadHoldingTheWrittenContentMatchesIntent` |
| VER-17-08 | A matching state is never reported as transaction-correlated proof | `evidenceWordingNeverClaimsProof`, `evidenceForARecordWithoutChangesIsNotAClaimAboutContent`, `anUnresolvedMutationBlocksTheEditorAndIsNeverWrittenOver` |
| VER-17-09 | Verification tolerates only backend-owned canonicalization | `canonicalizedStorageStillMatchesIntent`, `backendOwnedTagOrderAndCaseStillMatchIntent`, `nfcEquivalentFieldsAreTheSameContent`, `theFieldSeparatorIsNeverSilentlyStripped` |
| VER-17-10 | Proven non-application is the only route to a retry, under the same id | `confirmedNotApplicedAtFirstStepIsRetryableWithTheSameIdentity`, `aRetryIsOfferedOnlyForAProvenNonApplication`, `retryReturnsToPreparedWithSameIdAndNeverSkipsToSubmitting` |
| VER-17-11 | Unknown outcomes are AMBIGUOUS and never retried | `outcomeUnknownIsAmbiguousAndCannotBeRetried`, `unexpectedBackendFailureIsTreatedAsUnknownNotAsNotApplied`, `ambiguousCannotBeRetriedDirectly`, `aBinderWrappedBackendErrorIsUnknownNeverProvenNonApplication` |
| VER-17-12 | Partial application is labelled partial and is not retryable as a whole | `laterStepNotAppliedAfterEarlierSuccessIsAmbiguousAndNotRetryable`, `outcomeUnknownAfterEarlierOperationIsRecordedAsPartial`, `aTwoStepPlanIsVerifiedAgainstBothWritesAndAPartialOneDiffers` |
| VER-17-13 | Conflict handling is honest: drift before the write writes nothing | `noteChangedAfterTheBaseWasReadIsAConflictBeforeAnyWrite`, `backendConflictIsTerminalAndNotRetryable`, `aPreWriteConflictIsWordedAsNothingWrittenAndOffersAFreshStart` |
| VER-17-14 | Conflict resolution creates a new identity that links back | `conflictResolutionCreatesANewIdentityThatLinksBack`, `startAfterConflictRefusesWhenTheSourceIsNotAConflict` |
| VER-17-15 | Backend-context correlation: a stale or foreign context is dropped | `backendMismatchIsRefused`, `aCardReferenceFromAnotherBackendIsAnErrorNotAGuess`, `aReadFromAnotherBackendProvesNothing`, `evidenceFromAnotherBackendProvesNothing` |
| VER-17-16 | One active mutation per note; a concurrent save is refused, not queued | `secondEditOfTheSameNoteWhileOneIsActiveIsRefused`, `aConcurrentSaveWhileOneIsInFlightIsRefusedNotQueued`, `activeMutationForReportsTheOwningRecordAndNothingOnceTerminal` |
| VER-17-17 | Active-Study safety is enforced in the domain, twice | `studyOverlapBlocksBeforeAnyRecord`, `studyWorkStartedBetweenRecordingAndWritingStopsTheWrite`, `unfinishedStudyWorkRefusesTheSaveWithoutAnyWrite` |
| VER-17-18 | Crash/restart recovery: SUBMITTING → AMBIGUOUS, PREPARED/RETRY_ALLOWED abandoned | `restartTurnsSubmittingIntoAmbiguousAndAbandonsPreparedAndRetryAllowed`, `coordinatorRefusesWhenAFreshRestartFindsOnlyUnknownRecords`, `preWriteReadFailureLeavesPreparedAndSendsNothingThenResumes` |
| VER-17-19 | Cancellation during a write is persisted as AMBIGUOUS before propagating | `cancellationDuringTheWriteIsPersistedAsAmbiguousBeforePropagating` |
| VER-17-20 | The ledger fails closed and never loses or invents a record | `unreadableStoreFailsClosed`, `corruptSnapshotFailsClosed`, `failedWriteLeavesDurableStateUnchangedAndNextReadReloadsIt`, `appliedThatCannotBeRecordedIsNeverReplayed`, `attestationIsNeverReportedUnlessItIsDurable`, `ledgerRejectsAnAppliedRecordReplayRequestEvenIfCalledDirectly` |
| VER-17-21 | Persisted metadata never contains note content | `persistedMetadataNeverContainsNoteContent`, `persistedSnapshotNeverContainsFieldValues`, `codecRoundTripsEveryField`, `codecRejectsAnUnknownVersion` |
| VER-17-22 | Post-save authoritative refresh: the backend read, not the draft, becomes screen truth | `aSavedEditWritesOnceAndAdoptsTheBackendReadAsItsTruth`, `theEditorIsReachedByReferenceBoundToTheOwningBackend` |
| VER-17-A1 (backend-specific) | AnkiDroid: fields+tags are one write, the deck move is a second, card-scoped write | `fieldsAndTagsGoInOneNoteUpdateWithAFullPositionalArrayAndTwoExpectedRows`, `deckMoveIsCardScopedWithOneDeckIdColumnAndOneExpectedRow`, `fieldsAndTagsAreWrittenTogetherAndADeckMoveIsASecondWrite`, `planOrdersContentBeforeDeckAndMergesFieldsAndTagsIntoOneContentOperation` |
| VER-17-A2 (backend-specific) | AnkiDroid: one provider update per submit, permit shared, no retry, timeouts are unknown | `oneSubmitIssuesExactlyOneProviderUpdateWithTheMappedPath`, `aBusyPermitRefusesBeforeAnyProviderCall`, `aThrownProviderCallIsReportedWithItsClassOnlyAndReleasesThePermit`, `aTimedOutWriteIsUnknownAndKeepsThePermitUntilTheLiveCallReturns` |
| VER-17-A3 (backend-specific) | AnkiDroid: deck targets are verified against a live listing; filtered, foreign and unknown decks are refused | `deckTargetsAreVerifiedAgainstTheLiveListing`, `deckMoveFromAnUnknownSourceDeckIsRefused`, `aFilteredDeckTargetIsRefusedBeforeAnyWrite`, `deckOptionsMarkFilteredAndUnverifiableDecksAsUnselectable` |
| VER-17-A4 (backend-specific) | AnkiDroid: boundary classification matches the pinned provider sources | `aMatchingRowCountIsConfirmedApplied`, `aRowCountThatDoesNotMatchIsNeverTakenAsSuccess`, `refusalBeforeDispatchIsConfirmedNotApplied`, `securityAndArgumentFailuresAreConfirmedNotAppliedButOtherThrowsAreUnknown`, `aNumericParseFailureIsProvenNonApplicationBecauseItPrecedesTheWrite`, `onlyTheThreeProvenPreWriteFailuresAreEverConfirmedNotApplied`, `theProviderPathsUseOnlyPinnedConstants` |
| VER-17-A5 (backend-specific) | PC backend: unsupported, so the editor is refused and no PC mutation code exists | `editingIsRefusedWhenNoEditingDimensionIsOffered`, `aBackendWithoutAnyEditingDimensionIsRefusedNotDegraded`, plus the source audits (no PC writer exists) |
| VER-17-A6 (backend-specific) | Attestation is the only escape from AMBIGUOUS and is recorded as an attestation | `attestationAppliedClosesTheRecordWithoutTouchingTheBackend`, `attestationAbsentClosesTheRecordAndFreesTheNoteForAFreshEdit`, `attestationCannotChangeARecordThatIsNotAmbiguous`, `attestationForAnUnknownMutationIsNotFoundAndNeverInventsARecord`, `attestationsAreRecordedAsAttestationsNeverAsBackendEvidence`, `anAttestationClosesTheAmbiguityReloadsTheNoteAndIsNotAWrite` |

### Not executed here (environment)

- `./gradlew testDebugUnitTest`, `lint`, `assembleDebug`, clean and release builds: the Gradle
  distribution, the Android SDK and Maven repositories are unreachable from this sandbox.
- Android-only files are reviewed and source-audited but not executed: `AnkiDroidBackend`,
  `AnkiDroidCompatibilityPolicy`, `AnkiDroidProviderClient`, `DataStoreNoteMutationStore`,
  `AppContainer`, and `AppNavHost`/`EditNoteScreen` at runtime (they do compile against the harness
  Compose stubs).
- Device or emulator verification of provider behaviour, and instrumented UI tests.
- Pre-existing, unrelated to this gate: `AnkiDroidApiContract.kt` uses `const val`s before their
  declaration, which K1 rejects and K2 accepts. It is untouched here; the harness compiles it.

## PART IV — invariants (INV-17-01 … INV-17-20)

| ID | Invariant | Where enforced |
|---|---|---|
| INV-17-01 | No backend write before PREPARED and SUBMITTING are durable | `DefaultNoteMutationCoordinator.saveLocked`; `noBackendWriteWithoutDurableSubmitting` |
| INV-17-02 | Exactly one component calls `applyNoteMutation` | audit `onlyTheCoordinatorInvokesABackendNoteWrite` |
| INV-17-03 | One active mutation per note | `DefaultNoteMutationLedger.create`; `secondEditOfTheSameNoteWhileOneIsActiveIsRefused` |
| INV-17-04 | The status vocabulary is closed to six values; APPLIED and CONFLICT are terminal | `NoteMutationStatus`, `NoteMutationTransitions`; `statusEnumIsExactlyTheSpecifiedSixValues` |
| INV-17-05 | An empty patch produces zero writes and zero records | `NoteEditPlanner`; `emptyDraftIsNoChangesAndWritesNothing` |
| INV-17-06 | Conflict detection is best-effort and is never worded as protection | `EditNoteMapper.notices`; `theConflictNoticeSaysBestEffortAndNeverSaysProtected` |
| INV-17-07 | A matching post-write state is not proof that *this* transaction applied | `NoteMutationVerifier` documentation and `verifyIntent`; `evidenceWordingNeverClaimsProof` |
| INV-17-08 | No state-equality heuristic is reported as transaction-correlated reconciliation | `authoritativeReconciliation = false`; `theLockedBackendNeverClaimsAuthoritativeReconciliation` |
| INV-17-09 | Deck identity is the stable id; labels are display only; the move is card-scoped | `DeckChange`, mapper, `DeckSelector`; `deckIdentityIsTheStableIdNeverTheDeckName`, `deckWordingIsCardScopedThroughoutTheUiLayer` |
| INV-17-10 | No revision token is fabricated from a hash, timestamp or local snapshot | audit `noFabricatedRevisionTokenIsIntroduced` |
| INV-17-11 | The UI has no write path other than the coordinator | `theEditViewModelWritesOnlyThroughTheCoordinator`, `theUiLayerHasNoDirectProviderOrAnkiConnectAccess` |
| INV-17-12 | AMBIGUOUS is never replayed automatically | `NoteMutationTransitions` (no edge), `noForceOverwriteOrSilentRetryOfAnAmbiguousWriteExists` |
| INV-17-13 | Partial application is never labelled a full safe failure | `PARTIAL_OPERATION_UNKNOWN`; `laterStepNotAppliedAfterEarlierSuccessIsAmbiguousAndNotRetryable` |
| INV-17-14 | Post-write backend truth overrides local draft truth | `finishApplied`, `EditNoteViewModel.handleOutcome(Applied)`, `AppNavHost` refresh signal; `aSavedEditWritesOnceAndAdoptsTheBackendReadAsItsTruth` |
| INV-17-15 | Persisted state carries metadata only, never note content | `NoteMutationCodec`; `persistedMetadataNeverContainsNoteContent` |
| INV-17-16 | Private AnkiDroid storage and internals are never used | only the public provider URIs in `AnkiDroidApiContract`; `theProviderPathsUseOnlyPinnedConstants` |
| INV-17-17 | The UI cannot bypass backend-contract enforcement | `theComposeEditLayerMakesNoCapabilityOrContractDecision`, `theSaveControlIsEnabledOnlyByTheMappedFlag`, `aDimensionTheBackendDoesNotOfferIsRefusedEvenIfTheUiAsksForIt` |
| INV-17-18 | Note mutations stay separate from the rating and reviewer-action transaction families | `noteMutationTypesDoNotReuseTheReviewCommitOrReviewerActionLedgersOrStatuses`; separate DataStore file |
| INV-17-19 | Active-Study safety is enforced in the domain, not only in the UI | `NoteEditSafetyPolicy` consulted twice in the coordinator; `studyWorkStartedBetweenRecordingAndWritingStopsTheWrite` |
| INV-17-20 | GATE 17 does not weaken GATE 11–16 | whole-suite run: 2013 passed, 0 failed, including the GATE 11B vocabulary lock and the GATE 16 card-details audits |

## PART V — definition of done

| Requirement | State |
|---|---|
| PART 0 contract resolved and locked before production mutation code | Done — `docs/GATE_17_BACKEND_CONTRACT.md` §27–§28, `IMPLEMENTATION AUTHORIZED: YES` |
| Contract matrix complete, no blank cells | Done — §17 of the contract document |
| Normalized operation / result / semantics models | Done — `NoteMutationStep`, `NoteMutationBackendResult`, `NoteMutationSemantics` |
| Durable ledger, closed transition table, coordinator ordering | Done — 12 domain files, 47 + 13 + 15 tests |
| Empty patch = zero writes; validation before any transaction | Done |
| Honest conflict handling; partial application labelled partial | Done |
| Post-save authoritative refresh | Done — verification read is the presentation truth, and Card Details re-reads after a save |
| Active-Study safety in the domain/coordinator | Done — checked twice |
| Capability that cannot be made safe is disabled, not weakened | Done — PC backend unsupported; trailing-empty-field edits refused; filtered/unverifiable decks refused; no automatic replay |
| Edit UI (screen, field/tag/deck controls, Card Details entry, navigation) | Done — 7 new UI files, 4 changed |
| PART I-A checkpoint reports | Done — above |
| PART II audit (AUDIT-17-01…14) | Done — 27 audit tests |
| PART III verification (VER-17-01…22 + backend-specific) | Done — one table above, 241 tests |
| PART IV invariants (INV-17-01…20) | Done — table above |
| Gradle build, lint, instrumented/device verification | **Not possible in this sandbox** — recorded as R6, with the JVM harness as equivalence evidence |
| GATE 18 not started | Correct — nothing for add-note/card/media exists |

## PART VI — final report

**Contract result.** `BACKEND CONTRACT LOCKED`, `IMPLEMENTATION AUTHORIZED: YES`
(`docs/GATE_17_BACKEND_CONTRACT.md` §27–§28). AnkiDroid v2.24.1 and the PC agent were resolved
independently; the first pass ended BLOCKED on one finding and four residuals, all five of which the
second pass resolved from sources read at pinned revisions.

**Contract matrix.** Contract §17. Every cell is `SUPPORTED — evidence`, `UNSUPPORTED — evidence` or
`UNKNOWN — reason`; there are no blanks. The remaining UNKNOWN cells are all on the PC side, where no
note-mutation contract exists in this repository, and that is precisely why the capability is off.

**Normalized contract.** Operations: `UpdateNoteContent(fields?, tags?)` and
`ChangeCardDeck(card → toDeck)`. Results: `ConfirmedApplied`, `ConfirmedNotApplied`, `Conflict(latest)`,
`OutcomeUnknown`. Capabilities: `editNoteFields`, `editNoteTags`, `changeCardDeck` (coarse
`editNotes = false`, `authoritativeMutationReconciliation = false`). Semantics: `ANKIDROID_V2_24_1`
(content write atomic; deck scope card-only; conflict guarantee best-effort pre-save re-read; no
idempotent replay; fields ordinal-identified and count-checked; tags replace-only and canonified;
trailing empty field not representable). Save order: content, then deck; a failure of the second step
is partial application.

**Unsupported semantics (explicit).** Tag add/remove deltas; note-wide deck moves; atomic
content+deck saves; backend idempotency keys; backend conflict detection or locking; authoritative
reconciliation of an ambiguous write; trailing-empty-field writes through the public provider; any
PC-backend note mutation; any note/card/media creation (GATE 18). Each is refused in code, not
silently degraded, and each is worded to the user.

**Guarantee statement.** Study Agent guarantees: no write is dispatched before PREPARED and SUBMITTING
are durable; a save is reported as saved only after an authoritative post-write read holds the intended
state; a retry is possible only after proven non-application, is always a user action, reuses the same
mutation id and re-reads the base first; any unknown outcome ends in AMBIGUOUS, which blocks that note
until a human attests; partial application is labelled partial and never retried as a whole; field,
tag and deck availability come from backend capabilities and declared semantics, never from a
backend's name; and the UI has no write path other than the coordinator. Study Agent does **not**
claim: atomicity of a content+deck save, backend-enforced idempotency, conflict prevention,
authoritative reconciliation, that a matching state proves which transaction produced it, or any
note-wide deck move.

**Implementation report.** 11 domain files, 2 AnkiDroid adapter files, 1 persistence file, backend and
container wiring, 7 new UI files, 6 changed UI/navigation files, 3 new test suites and 4 extended ones,
plus harness work to compile and run the new UI on a JVM. All of it is on this branch; PART I-A above
reports it file by file.

**Audit.** PART II: AUDIT-17-01…14 all enforced by `Gate17ArchitectureAuditTest` (27 tests) plus the
pre-existing GATE 11B and GATE 16 audits, which still pass unchanged.

**Verification.** PART III is the single verification table: VER-17-01…22 plus six backend-specific
groups, 241 GATE 17 tests and 2013 repository tests, 0 failures, on the JVM harness.

**Residual risks.**
1. **R6 — no Gradle, no device.** Provider behaviour is inferred from source at a pinned tag and
   verified by JVM equivalence tests; the Android-only files compile-review clean but were never
   executed. Mitigation: the harness compiles the domain, the pure UI and the Compose layer, and the
   audits pin the structural rules; a real `./gradlew testDebugUnitTest lint assembleDebug` run is the
   first thing to do outside this sandbox.
2. **R7 — PC backend unsupported.** No PC mutation code exists; if a PC contract is ever added it must
   repeat this contract-first discipline before any implementation.
3. **R8 — NFC preference unreadable.** Both sides of the comparison are NFC-normalized, so a
   collection with `NormalizeNoteText` on or off verifies the same way; a backend that normalized
   *differently* than rslib would show up as `DiffersFromIntent` (AMBIGUOUS), never as a false APPLIED.
4. **R9 — a deck write always writes.** The planner emits no deck operation when the target equals the
   current deck, so no unnecessary write is dispatched.
5. **R10 — irreducible ambiguity windows.** A lost binder reply, a `-1` row count or process death
   after a durable SUBMITTING ends in AMBIGUOUS. The note stays blocked until the user checks Anki and
   attests; the attestation is recorded as an attestation and never as backend evidence.
6. **Sibling cards.** A field edit can make Anki generate further cards for the note, placed by Anki.
   The user is warned before the save; those cards are neither verified nor reported by this gate.
7. **No instrumented UI tests.** Compose behaviour is verified by compilation plus mapper/ViewModel
   suites; the Compose tree itself is unexercised until an Android test run is possible.

## PART VII — gate lock

GATE 17 is locked on the evidence above. GATE 18 (Add Note / Add Card / Media) may not start from this
gate's code: it must repeat the contract-first discipline (its own PART 0, its own matrix, its own
lock report) before any creation path is written. Nothing in this gate weakens GATE 11–16; the whole
repository suite passes.

Suggested commit: `gate17: add contract-driven safe Anki note editing`.

## Known issues and decisions

1. **A trailing empty field cannot be written through AnkiDroid's provider.** A note whose last field
   is empty cannot have any field edited until that field has a value. Refused before any transaction
   (`TrailingEmptyFieldNotRepresentable`), surfaced as a blocking field issue with a concrete
   suggestion, and never sent to the backend.
2. **AMBIGUOUS blocks the note, and the only escape is a human attestation.** This was an open product
   decision in the first pass; the contract resolved it (CONTRACT-26): `resolveAmbiguous` records
   `USER_ATTESTED_APPLIED` → APPLIED or `USER_ATTESTED_NOT_APPLIED` → CONFLICT (terminal, so the note
   is free again and a fresh edit starts with a new id). The wording always says "you confirmed", never
   "the backend confirmed".
3. **Reconciliation is not authoritative at this pin.** `reconcileNoteMutation` is not overridden by
   the AnkiDroid backend; `recover` performs a read-only comparison and offers it as evidence.
4. **Force overwrite and rollback are not implemented**, as the gate requires. There is no force path
   anywhere in the edit sources (audited).
5. **`PcAnkiBackend` and a main-source `FakeAnkiBackend` do not exist in this repository.** Nothing was
   written for them; the test fake lives in test sources and inherits the default refusals.
6. **Presentation vocabulary.** The GATE 11B lock forbids the retired spelling `Retryable` anywhere in
   production code; the editor's presentation case is `RetryAvailable`, matching `RatingCommitUiState`.
   The lock caught this during the whole-suite run and it was fixed rather than allowlisted.
7. **A real defect found by this pass's tests.** `EditNoteViewModel.handleOutcome` built the blocking
   state for five outcome branches (`ActiveMutationExists`, `ReadFailed`, `Conflict`, `RetryAvailable`,
   `VerificationRequired`) but discarded it, which left the screen showing "Saving" while a record was
   unresolved. The helper (`blockOn`) now assigns the state itself, so the mistake is no longer
   expressible, and `EditNoteViewModelTest` pins every branch.
8. **Harness scope.** Pure Kotlin files under `ui/` were previously compiled by no layer at all; the
   harness now has a `pureui` layer, which is why the edit projection and its model are compiled and
   tested. Three Compose stubs were corrected to match the real APIs (see PART I-A).
