# GATE 16 — Read-only Card / Note Details

**Result: BLOCKED — implementation and source/unit audits pass; Android Gradle, lint, APK and real-device verification are not available in this sandbox. Do not mark GATE 16 locked yet.**

This Gate adds a read-only detail view for one exact card. It adds no rating, note, deck, tag, flag, bury, suspend, delete, study-session or scheduler mutation.

## CHECKPOINT 00 — Repository map

| Planned responsibility | Actual file(s) | Action / decision |
|---|---|---|
| Deep domain card/note details | `core/anki/AnkiCardDetails.kt` | CREATE |
| Card identity | `core/anki/AnkiRefs.kt` | NO CHANGE — `AnkiCardRef` already includes backend identity and optional collection identity; equality includes both |
| Backend details contract | `core/anki/AnkiBackend.kt` | EXTEND with one read-only `getCardDetails(cardRef)` call and a safe unsupported default |
| Capability truth | `core/anki/AnkiCapabilities.kt` | EXTEND with `cardDetails`, default false |
| Stored scheduling facts | `core/anki/AnkiModels.kt` | EXTEND existing `AnkiSchedulingInfo`; no duplicate scheduler model |
| Exact AnkiDroid card lookup | `data/anki/ankidroid/AnkiDroidCardGateway.kt` | NO CHANGE — existing public item URI lookup reused |
| Exact AnkiDroid note / note-type lookup | `data/anki/ankidroid/AnkiDroidNoteGateway.kt` | CREATE |
| Public provider contract | `data/anki/ankidroid/AnkiDroidApiContract.kt` | EXTEND with exact `notes/<id>` / `models/<id>` projections and GATE 16 card scheduling columns |
| Provider row → source fields | `data/anki/ankidroid/AnkiDroidNoteMapper.kt` | CREATE |
| Existing card mapping | `data/anki/ankidroid/AnkiDroidCardMapper.kt` | EXTEND card-type separation and raw due/ease mapping |
| Domain details assembly | `data/anki/ankidroid/AnkiDroidCardDetailsMapper.kt` | CREATE |
| AnkiDroid backend coordination | `data/anki/ankidroid/AnkiDroidBackend.kt` | EXTEND exact read; no browse scan |
| Capability report | `data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt` | EXTEND; backend drops the claim unless the note gateway is wired |
| PC implementation | `data/anki/remote/PcAnkiBackend.kt` | NO CHANGE / NOT PRESENT — no PC `AnkiBackend` implementation exists in this checkout; default is typed unsupported, no values fabricated |
| Fake details | `app/src/test/.../anki/fake/FakeAnkiBackend.kt`, `CardDetailsTestFixtures.kt` | EXTEND / CREATE |
| UI state and projection | `ui/screens/carddetails/CardDetailsModels.kt`, `CardDetailsMapper.kt`, `CardDetailsViewModel.kt`, `CardDetailsScreen.kt` | CREATE |
| Reusable detail components | `ui/components/anki/CardMetadataSection.kt`, `NoteFieldsSection.kt`, `CardSchedulingSection.kt` | CREATE |
| Original renderer | `ui/components/anki/AnkiCardRenderer.kt`, `AnkiCardWebView.kt` | NO CHANGE — existing renderer and WebView security boundary reused |
| Navigation | `ui/navigation/Screen.kt` (existing typed route), `ui/navigation/AppNavHost.kt` | EXTEND host wiring; route still contains only encoded `AnkiCardRef` |
| Browser row selection | `ui/screens/cardbrowser/*` | NO CHANGE — GATE 15 already emits `OpenCardDetails(cardRef)` |

## Implementation and data boundaries

### Card identity

`AnkiCardDetails.cardRef` is the card identity. It remains distinct from `noteRef`; `noteId` is exposed as a note identity only. `cardOrd` is kept as an optional zero-based ordinal. Existing `AnkiCardRef` carries `AnkiBackendId` and `collectionKey?`; no collection ID is invented for AnkiDroid, whose pinned public provider does not expose one.

Navigation decodes the backend-qualified ref, resolves that exact backend through `AnkiBackendRegistry`, and refuses an unregistered or mismatched backend. It never routes by content, deck name, list index or a serialized card object.

### Content channels

| Representation | Source | Purpose |
|---|---|---|
| `questionHtml`, `answerHtml` | Existing GATE 07 exact card query | Original Anki-rendered sides, forwarded unchanged to GATE 08 `AnkiCardRenderer` |
| `questionText`, `answerText` | Existing backend-normalized text | Readable text projection; no HTML-derived fallback |
| `pureAnswerText` | Existing evaluator-oriented answer channel | Kept separate; never substituted with HTML |
| `fields` | Exact `notes/<noteId>` `flds` paired with exact `models/<mid>` `field_names` | Ordered source note values; empty values and order preserved; never reconstructed from rendered HTML |
| `tags` | Exact note row `tags` | Backend order preserved; null = unavailable, empty = authoritatively no tags |
| `mediaFiles` | Conservative logical-reference extraction from source/rendered strings | Logical filenames only; traversal/URI-like names rejected; no private AnkiDroid path access |

Note fields are projected to selectable, safe plain text in Compose. Field HTML is not executed. The original card remains inside the existing renderer and WebView security policy; no second WebView stack was added.

AnkiDroid's pinned public API exposes media filenames but not a public media stream. Its existing `AnkiDroidMediaResolver` remains fail-closed; filenames can be inspected in details, but actual private-file reads are not attempted. Real-device image/audio resolution is therefore not claimed.

### Scheduling authority and capability matrix

All scheduler metadata comes from the backend's exact card row and remains informational. GATE 16 adds the public `due`, `original_due`, and `sm2_factor` columns to the existing explicit card projection. It does not calculate scheduler output.

| Value | AnkiDroid v2.24.1 public API | Presentation behavior |
|---|---|---|
| Due | **SUPPORTED as raw stored value** (`due`); queue-dependent units | Displayed explicitly as raw/backend-defined. Not converted to a date or review prediction |
| Normalized due timestamp | **UNAVAILABLE** for this provider: review due is a collection day number and learning due is timestamp-shaped; collection cutoff/day metadata is not exposed |
| Interval | **SUPPORTED** (`interval`, days); negative learning-step encodings are left unavailable rather than mislabeled as days | Displays confirmed `0` as `0 days`; null is omitted |
| Reps / lapses | **SUPPORTED** | `0` stays visible; null is unavailable |
| Card type | **SUPPORTED** from documented `type` codes | Separate from queue state; unknown code becomes `UNKNOWN` |
| Queue type / suspended / buried | **SUPPORTED** from documented `queue` codes | Separate from card type; unknown code becomes `UNKNOWN` |
| Original deck identity | **SUPPORTED** (`original_deck_id`; `0` means no filtered-deck home ref) | Current deck and original deck remain separate |
| Original due | **SUPPORTED as raw stored value** (`original_due`) | Displayed raw; never converted |
| SM-2 ease factor | **SUPPORTED** (`sm2_factor`, stored ×10) | Displayed with its source scale; no ease transition derived |
| FSRS stability, difficulty, desired retention | **SUPPORTED when returned by the pinned card row** | Display only; no locally reconstructed FSRS data or prediction |
| Card flag | **UNAVAILABLE** on pinned public Card provider contract | Null is shown as unavailable; note flags are never misused as card flags |
| PC backend | **NOT VERIFIED / NOT IMPLEMENTED** in this checkout | Contract default returns `UnsupportedAction`; all optional values remain unavailable |

### Errors, loading, refresh and stale responses

- Missing exact card → `AnkiError.CardNotFound`; no sibling card is selected.
- Card exists but note / note-type relationship is missing or inconsistent → `DataIntegrityFailure`; fields are never fabricated.
- Provider permission, collection, transient and other failures stay mapped to the existing typed domain error family.
- Unsupported details capability → typed `UnsupportedAction`; it is not inferred from backend name.
- Refresh starts a new exact read and clears the previous `Ready` state while loading.
- Each request is bound to backend object/id, collection key when known, card ref and generation. Late results for another card/backend are discarded.
- An unknown collection key is not a wildcard when switching backend instances; details are refused rather than reinterpreted.

## File-by-file report

### Created

| File | Responsibility / implemented | Tests |
|---|---|---|
| `core/anki/AnkiCardDetails.kt` | Pure domain card details and ordered note field; nullable optional metadata; explicit card/note/ordinal identity | Domain isolation and UI mapper tests |
| `data/anki/ankidroid/AnkiDroidNoteGateway.kt` | Exact note and exact model public reads; typed errors; content-free query diagnostics | `AnkiDroidNoteGatewayTest` |
| `data/anki/ankidroid/AnkiDroidNoteMapper.kt` | Ordered `flds`/`field_names` pairing, tags, model identity verification, note mod time | `AnkiDroidNoteGatewayTest` |
| `data/anki/ankidroid/AnkiDroidCardDetailsMapper.kt` | Assembles existing card content with independent source note facts; safe logical media refs | Backend and architecture tests |
| `ui/screens/carddetails/CardDetailsModels.kt` | Canonical UI state and Compose-free presentation types | Compiled by fallback main harness |
| `ui/screens/carddetails/CardDetailsMapper.kt` | UI-only labels, scheduling formatting, safe field text and existing `AnkiRenderedCard` projection | `CardDetailsMapperTest` |
| `ui/screens/carddetails/CardDetailsViewModel.kt` | Exact read, refresh, availability/capability observation, stale and backend-switch protection | `CardDetailsViewModelTest` |
| `ui/screens/carddetails/CardDetailsScreen.kt` | Read-only overview, original question/answer tabs, readable text, fields, tags, scheduling and technical sections | Static source audit; full Compose compilation not available |
| `ui/components/anki/CardMetadataSection.kt` | Accessible reusable metadata rows | Static source audit |
| `ui/components/anki/NoteFieldsSection.kt` | Ordered safe selectable field display with expand/collapse | Mapper and source audit |
| `ui/components/anki/CardSchedulingSection.kt` | Authoritative scheduling rows only | Mapper tests |
| `app/src/test/.../CardDetailsTestFixtures.kt` | Basic/multi-field/sibling/HTML/media/Cloze/Arabic/mixed/new/learning/review/suspended/flagged/unknown fixtures | Fake backend contract test |
| `app/src/test/.../CardDetailsFakeBackendTest.kt` | Multiple cards per note, missing card and zero mutation assertions | Passed |
| `app/src/test/.../CardDetailsMapperTest.kt` | Field order/safe text, content separation, zero-vs-unknown, raw due, media filtering, flags/status | Passed |
| `app/src/test/.../CardDetailsViewModelTest.kt` | Loading/read errors, refresh, zero metadata, same-note cards, backend switch, stale A/B result | Passed |
| `app/src/test/.../AnkiDroidNoteGatewayTest.kt` | Exact note/model projections, field order/trailing empties, tags, missing/corrupt relationships | Passed |
| `app/src/test/.../AnkiDroidCardDetailsBackendTest.kt` | Card→note→model/deck coordination, typed missing note/card, no writes/reviewer/scheduler query | Passed |
| `app/src/test/.../Gate16ArchitectureAuditTest.kt` | Source audit for identity, read-only boundary, exact lookup, renderer reuse and HTML safety | 8/8 passed |

### Modified

| File | Responsibility / change | Tests |
|---|---|---|
| `core/anki/AnkiBackend.kt` | One `getCardDetails(cardRef)` deep-read contract; unsupported safe default | Full fallback suite |
| `core/anki/AnkiCapabilities.kt` | `cardDetails` capability, default false | Capability tests |
| `core/anki/AnkiModels.kt` | Extend existing scheduling model with raw due, original due and ease factor; no duplicate scheduler type | Card mapping + full suite |
| `data/anki/ankidroid/AnkiDroidApiContract.kt` | Pin note/model item projections and GATE 16 scheduling columns | Contract test |
| `data/anki/ankidroid/AnkiDroidCardMapper.kt` | Distinct documented card type mapping; parse raw due/ease without replacing zero | Mapper tests |
| `data/anki/ankidroid/AnkiDroidBackend.kt` | Coordinate one exact card, note/model and optional deck-name read; no browse scan | Backend details tests |
| `data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt` | Advertise card details only when ready; backend masks claim without note gateway | Capability tests |
| `di/AppContainer.kt` | Wire the application-scoped read-only note gateway | Static inspection only; Android compilation blocked |
| `ui/navigation/AppNavHost.kt` | Resolve backend by ref identity and open the real details VM/screen | Static architecture audit |
| `app/src/test/.../AnkiDroidCardMapperTest.kt` | Assert card type vs queue and raw due zero/ease facts | Passed |
| `app/src/test/.../AnkiDroidContractTest.kt` | Pin extended explicit card projection | Passed |
| `app/src/test/.../AnkiDroidCapabilityProbeTest.kt` | Assert GATE 16 capability truth | Passed |
| `app/src/test/.../fake/FakeAnkiBackend.kt` | Exact details fixture read; default unsupported capability; zero-write counters | Passed |
| `app/src/test/.../ui/DeckTreeUiMapperTest.kt` | Fix pre-existing test call to adapt `AnkiDeck` fixtures into the mapper's `DeckListItem` input | Full suite now compiles |
| `tools/jvm-harness/bin/build.sh` | Optional external extra-source hooks for lifecycle shims in this Maven-blocked sandbox; no normal build behavior change | Fallback harness |

### Reused without modification

`core/anki/AnkiRefs.kt`, `data/anki/ankidroid/AnkiDroidCardGateway.kt`, `AnkiDroidReviewGateway.kt`, `ui/components/anki/AnkiCardRenderer.kt`, `AnkiCardWebView.kt`, and the existing GATE 15 browser event/row contract. `CardDetailsPlaceholderScreen.kt` remains only as an unused GATE 15 compatibility placeholder; the NavHost now routes to the production details screen.

## Read-only and identity audit

- Rating mutation calls in details browsing: **0** (fake counters; AnkiDroid backend integration test)
- Reviewer-action mutation calls: **0** (fake counters; AnkiDroid backend integration test)
- Note-write calls: **0** (`AnkiDroidProviderClient.updateLog` remains empty; note gateway has no write method)
- Scheduler advancement / queue query calls: **0** (review-gateway query count remains zero; ViewModel has no `nextCard` dependency)
- Stable identity: `cardRef` remains route/request identity; `noteRef` and `cardOrd` are separate; two cards sharing a note remain distinct.
- Renderer: existing `AnkiCardRenderer` is invoked for both sides with `BROWSING`; no WebView implementation added.
- HTML: note fields become text-only display; original rendered question/answer continue through existing renderer security policy.

## Invariant ledger

| Invariant | Result | Evidence / qualification |
|---|---|---|
| INV-16-01 read-only | PASS | Details VM has no mutation dependencies/calls; fake and provider integration counters remain zero |
| INV-16-02 `AnkiCardRef` is card identity | PASS | Domain stores `cardRef`; browser route carries only that ref |
| INV-16-03 note identity is distinct | PASS | `noteRef`/`noteId` stay separate; domain/backend tests assert both |
| INV-16-04 same-note cards remain distinct | PASS | Fake fixtures and ViewModel tests use same note with ordinals 0/1 and distinct card refs |
| INV-16-05 source/rendered separation | PASS | Note mapper reads `flds`; details mapper copies rendered channels independently |
| INV-16-06 existing renderer reused | PASS | Screen calls `AnkiCardRenderer` for both sides |
| INV-16-07 no WebView duplication | PASS | No WebView implementation/import added in GATE 16 UI |
| INV-16-08 scheduling backend authority | PASS | Scheduling facts map from pinned public card columns only |
| INV-16-09 no scheduler calculations | PASS | Raw queue-dependent due values stay raw; no interval/FSRS prediction |
| INV-16-10 unknown distinct from zero | PASS | Mapper/presentation tests cover null vs reps/lapses/interval/raw due = 0 |
| INV-16-11 exact card lookup | PASS | Existing `cards/<id>` or `notes/<id>/cards/<ord>` lookup reused |
| INV-16-12 no collection card scan | PASS | Backend contract/source audit forbids `browseCards` in details path |
| INV-16-13 provider types stay below data layer | PASS | Pure domain and UI source audit; provider rows do not cross gateway |
| INV-16-14 stale results cannot cross cards | PASS | Controlled non-cancellable A→B response test passes |
| INV-16-15 backend changes cannot reinterpret refs | PASS | Foreign backend and unknown-collection instance-switch tests fail closed |
| INV-16-16 note-field HTML is not executed | PASS | Field values are projected to plain text; no field WebView/HTML component |
| INV-16-17 tags/flags/status read-only | PASS | UI uses display-only tags/metadata; no write calls or edit affordance |
| INV-16-18 no StudySession advancement | PASS | Details VM has no review/session API; review gateway count remains zero |
| INV-16-19 no scheduler mutation | PASS | Rating/action/update counters remain zero; no `nextCard` call |
| INV-16-20 previous Gates unchanged | PASS in fallback suite | Full GATE 11–15 unit/regression suite is included and green; Gradle/device verification remains blocked |

## Verification result

```text
Fallback main compile (non-Compose + pure UI VM/mapper): 0 errors
Fallback test compile:                                 0 errors
Targeted GATE 16 suite:                                 61 passed / 0 failed
Full JVM fallback suite:                                1772 passed / 0 failed
GATE16 architecture source audit:                        8 passed / 0 failed
GATE 11–15 regression tests (in full suite):             pass
`git diff --check`:                                      pass
`./gradlew testDebugUnitTest`:                           BLOCKED — Gradle 8.7 wrapper distribution SSL handshake failed
`./gradlew lint` / `assembleDebug` / `assembleRelease`:  NOT RUN — wrapper distribution unavailable
`connectedDebugAndroidTest` / real AnkiDroid device:     NOT RUN
Compose/navigation compilation:                         NOT VERIFIED by fallback harness
```

The fallback harness compiles Kotlin 2.3/K2 with coroutines-test 1.10.2, not the repository's pinned Gradle toolchain. It now covers the Compose/activity layer through API-shaped stubs (`compose` step) and a committed `androidx.lifecycle` shim, both under `tools/jvm-harness/shims/`. Its 1,772 passing tests are meaningful regression evidence, not a substitute for Android Gradle/Compose/lint/APK verification. The real provider contract was source-pinned to AnkiDroid v2.24.1; the end-to-end provider behavior still needs a device run.

## Compose and activity-layer verification

The gate's earlier position was that the Compose layer could not be verified without Gradle. That was
too pessimistic in one direction and too generous in the other, so it is replaced by an explicit method
and its limits.

**Method.** `tools/jvm-harness/bin/build.sh compose` runs two steps:

1. the API-shaped stubs (`tools/jvm-harness/shims/{compose,activity,navigation,lifecycle,buildconfig}`
   plus a `R` class generated from `app/src/main/res` by `gen-r.py`) are compiled. A stub that does not
   compile aborts the step as a **harness** defect, so stub breakage can never be mistaken for a pass.
2. the GATE 16 UI closure (`ui/screens/carddetails/CardDetailsScreen.kt`, the eight
   `ui/components/anki/*` files it reuses, `ui/components/{StudyAgentTopBar,AppPrimitives}.kt` and
   `ui/theme/*`) is type-checked against those stubs and `main-out`, with `-Xfriend-paths` so that
   `internal` visibility behaves as it does under Gradle, where all of `app/src/main/java` is one
   module. `GATE16_COMPOSE_ALL=1` widens step 2 to every main file that imports a framework symbol.

`tools/check-androidx-imports.py` resolves every `import androidx.*` in the app against
**version-pinned** androidx api dumps fetched from `api.github.com`
(`compose/*` → `1.6.0-beta01`, `material3` → `1.2.0-beta02`, `lifecycle` → `2.8.0-beta01`,
`navigation` → `2.8.0-beta07`); `--selftest` proves the parser rejects fabricated symbols, so a
"0 unresolved" result means something. `androidx.compose.material.icons.*` is reported UNVERIFIED on
purpose: there is no usable icon dump to check names against.

**Limits.** There is no Compose compiler plugin in this harness, so `@Composable` calling rules,
recomposition behaviour, snapshot/`remember` semantics and stability inference are **not** checked —
only declaration resolution, parameter names/arity and value types. The stubs are hand-written
approximations of the pinned API: where a stub and the app disagree, the pinned published API decides
and the *stub* is corrected. Compose is therefore **import-verified against the pinned published API
and type-checked against API-shaped stubs — not Gradle-verified.** `./gradlew testDebugUnitTest`,
`lint`, `assembleDebug`, `assembleRelease` and `connectedDebugAndroidTest` remain the arbiter.

### Defects build validation surfaced (all outside GATE 16 domain logic)

Every row was confirmed against an authority outside this repository — AOSP sources, the pinned api
dump, or the platform jar's own symbol table — before any app file was edited.

| File | Defect | Authority | Fix |
|---|---|---|---|
| `di/AppContainer.kt` | five stray lines after the class's closing brace (`rotocolVersion,` …) — a hard syntax error, present in HEAD | Kotlin parser | fragment removed |
| `di/AppContainer.kt` | `noteGateway = ankiDroidNoteGateway` referenced a container property that was never declared, so nothing constructed the GATE 16 note source gateway | the container interface itself | `ankiDroidNoteGateway` declared and wired to `DefaultAnkiDroidNoteGateway(providerClient = ankiDroidProviderClient)`, matching the sibling card/deck/review gateways |
| `ui/screens/carddetails/CardDetailsScreen.kt` | `import com.studyagent.client.core.anki.AnkiCardSide`; the enum is declared in `core.render` and every other consumer imports it from there | declaration search | import corrected (GATE 16 file) |
| `ui/components/anki/AnkiCardWebView.kt` | `blockNetworkImageLoads = true` — no such member; `WebSettings` declares `setBlockNetworkImage` and `setBlockNetworkLoads` | `aosp-mirror` `WebSettings.java` + jar symbol table | `blockNetworkImage = true`; the GATE 08 pin-list assertion in `AnkiRendererIsolationTest` tracks the real member name |
| `ui/theme/Motion.kt` | `AccessibilityManager.isReduceMotionEnabled` does not exist at any API level (0 hits in AOSP `android14-release`) | `aosp-mirror` `AccessibilityManager.java` | read `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, the signal androidx itself uses, still fail-closed to `false` |
| `ui/components/anki/DeckRow.kt` | `role = Role.Button` inside a `semantics { }` block with no `import androidx.compose.ui.semantics.role` | `ui` dump declares it as a top-level extension (`setRole(SemanticsPropertyReceiver, int)`) | import added |
| 7 files incl. `NoteFieldsSection.kt` | `import androidx.compose.ui.semantics.mergeDescendants` — `mergeDescendants` is a `Modifier.semantics` **parameter**, never an importable symbol | `ui` dump (single occurrence, a method parameter) | imports removed, `.semantics(mergeDescendants = true)` call sites kept |
| `ui/screens/home/HomeViewModel.kt` | `asStateFlow` / `asSharedFlow` used without their imports | coroutines carve of the pinned jar | imports added |
| 5 test files + `CardBrowserViewModelTest` | `kotlinx.coroutines.test.ExperimentalCoroutinesApi` (does not exist in the pinned 1.8.1), `UnconfinedTestDispatcher` used as a type, and a `Dispatchers.Main` that was never installed (14 failures) | kotlinx.coroutines 1.8.1 sources | imports/types corrected; `setMain`/`resetMain` added around that class |

### Not fixed: build blockers in other gates' files (owner decision)

These are real compile errors for `compileDebugKotlin`, found by the same sweep, but the correct
resolution is a design call in the owning gate's territory, so they are reported rather than patched:

- `ui/screens/study/AnkiAnswerReviewSection.kt:196,220` — passes `controller = renderController` to
  `AnkiCardRenderer`, which declares exactly one signature with no `controller` parameter.
- `ui/screens/study/AnkiAnswerReviewSection.kt:242` — passes `nightMode = true` to `CleanAnkiCardView`
  (`text, side, modifier, direction, degraded`); night mode is an `AnkiCardRenderConfig` field.
- `ui/screens/study/AnkiAnswerReviewSection.kt` (8 sites) and `ui/screens/study/ReviewerActionMenu.kt:97`
  — `AppColors.brandPrimary` (9 references in total); `AppColors` declares `actionPrimary`/`actionPrimaryStrong` and no
  `brandPrimary` exists anywhere in `app/src`. Whether the token should be added or the call sites
  renamed is a design-system decision.
- `ui/screens/study/StudyScreen.kt:257-260` — uses `RatingCommitUiState` in a `when` subject without
  importing it from `com.studyagent.client.core.study`.

### Verification result, re-measured after the harness change

```text
tools/jvm-harness/bin/bootstrap-sandbox.sh (clean sandbox, repo alone)
  main compile (non-Compose, 242 files)                 0 errors
  compose compile (GATE 16 closure, 18 + 46 stub files) 0 errors
  test compile (188 files)                              0 errors
tools/jvm-harness/bin/run.sh                            160 classes / 1772 tests / 1772 passed / 0 failed
tools/check-androidx-imports.py --fetch                 25 pinned dumps, 0 fetch failures
tools/check-androidx-imports.py --selftest              PASS (8 positives, 2 fabricated negatives)
tools/check-androidx-imports.py (app scan)              3197 imports (1163 androidx), 0 UNRESOLVED
GATE16_COMPOSE_ALL=1 build.sh compose                   143 residual errors in 67 files — diagnostic only
git diff --check                                        clean
```

The 143 residuals are dominated by APIs the stubs deliberately do not model yet (NavigationBar
colours, `FlowRow`/`ExperimentalLayoutApi`, `ExposedDropdownMenu`, `animateFloat`,
`togetherWith`, `TextFieldValue` details, icon names). They are **not** claimed to be app defects and
**not** claimed to be stub gaps case by case; the four cross-gate blockers above are the subset that
was checked against the app's own declarations and the pinned API. Widening the enforced scope is
later harness work, not GATE 16 acceptance evidence.

## Gate decision

**GATE 16 is implemented and source/unit audited, but BLOCKED from LOCKED status** until the standard Gradle verification and device checks pass. No Gate 17 mutation is included.
