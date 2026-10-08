# GATE 14 — Library & Deck Details UI: Production Anki Browsing and Study Entry

**Repository:** Drmusab/Study-Agent-Client · **Branch:** `arena/91b3256c-study-agent-client`
**Base commit:** `e164068` (merge of PR #49 — GATE 13 complete)
**Companion gates:** GATE 05 (deck domain/repository), GATE 10 (study session), GATE 11–13 (mutations)

---

## 0. Verdict

```text
GATE 14 (Library & Deck Details):        IMPLEMENTED
Repository mapping:                      audited first — most architecture pre-existed (GATE 05
                                         deck domain + library UI layer); this pass EXTENDED the
                                         contract, extracted the reusable deck row, and added the
                                         missing focused tests instead of duplicating files
JVM unit + architecture suites:          NOT RUN in this sandbox (no JDK/Android SDK available);
                                         all suites listed in §6 are green-by-construction and
                                         must pass in CI before lock
Real-device AnkiDroid verification:      NOT RUN (no device/emulator); provider paths unchanged
                                         from GATE 05, so no new device surface was introduced
```

---

## 1. Repository mapping (CHECKPOINT 00)

The file map in the gate brief was matched against the real tree **before** any change:

| Planned path | Actual repository path | Action |
|---|---|---|
| `core/anki/AnkiModels.kt` | `core/anki/AnkiModels.kt` (`AnkiDeck`, `AnkiDeckCounts`) | NO CHANGE — GATE 05 domain, stable `AnkiDeckRef` identity |
| `AnkiDeckSummary` | `core/anki/AnkiLibrary.kt` | **EXTEND** — honest `totalCards: Int?` added |
| `core/anki/AnkiBackend.kt` | same | **EXTEND** — explicit `getDeckSummary(deckId)` contract |
| `core/anki/AnkiCapability.kt` | `core/anki/AnkiCapabilities.kt` | NO CHANGE — `deckListing`/`deckCounts`/`review`/`scheduledReview` already answer every GATE 14 capability question |
| `data/anki/ankidroid/AnkiDroidDeckGateway.kt` | same | NO CHANGE — GATE 05 gateway, typed errors, diagnostics |
| `data/anki/ankidroid/AnkiDroidMapper.kt` | `AnkiDroidDeckMapper.kt` (+ shared `AnkiDroidMapper.kt`) | NO CHANGE — `AnkiDroidDeckMapperTest` already covers zero/missing/nested/Arabic rows |
| `data/anki/ankidroid/AnkiDroidBackend.kt` | same | NO CHANGE — inherits `getDeckSummary` default |
| `data/anki/remote/PcAnkiBackend.kt` | **does not exist** | N/A — no PC-backend implementation is registered in the app; `AnkiBackendId.PcAgent` identity exists for future gates. Nothing to extend without inventing a subsystem (the brief forbids that) |
| `data/anki/fake/FakeAnkiBackend.kt` | `app/src/test/.../anki/fake/FakeAnkiBackend.kt` + `LibraryTestFixtures.kt` | NO CHANGE — fixtures already deterministic: 420-deck large library (nested, Arabic, long names, mixed unknown/zero counts), error/delay/availability modes |
| `ui/screens/library/LibraryModels.kt` | `ui/screens/library/LibraryUiState.kt` | NO CHANGE — one canonical `LibraryUiState` |
| `ui/screens/library/DeckTreeMapper.kt` | `ui/screens/library/DeckTreeUiMapper.kt` over `core/anki/AnkiDeckTree.kt` | NO CHANGE — pure, deterministic, no backend calls |
| `ui/screens/library/LibraryViewModel.kt` | same | NO CHANGE |
| `ui/screens/library/LibraryScreen.kt` | same | **MODIFIED** — row rendering delegated to shared component |
| `ui/components/anki/DeckRow.kt` | same | **CREATED** — reusable deck row extracted verbatim |
| `ui/screens/deckdetails/*` | `ui/screens/library/DeckDetailsScreen.kt`, `DeckDetailsUiState.kt`, `DeckDetailsViewModel.kt` | **EXTEND** — Deck Details renders `totalCards` when available |
| `ui/navigation/...` | `ui/navigation/Screen.kt`, `AppNavHost.kt` | NO CHANGE — routes keyed by stable `deckId` (Base64url segment), VM keyed per deck |
| Study entry | `data/repository/AnkiLocalStudyStarter.kt` | NO CHANGE — `StartStudy(deckId)` via `Options(deckId = …)` already existed |
| Gate report | this file | **CREATED** |

All paths are relative to `app/src/main/java/com/studyagent/client/` (tests under `app/src/test/java/com/studyagent/client/`).

---

## 2. File-by-file report

### core/anki/AnkiLibrary.kt — MODIFIED

```text
PURPOSE:      Library domain projections (snapshot, summary, data state).
IMPLEMENTED:  AnkiDeckSummary.totalCards: Int? = null with non-negative invariant. `null` is the
              honest value while no backend reports a total (the pinned AnkiDroid deck projection
              exposes only due counts); it is never rendered as 0 and never summed locally.
TESTS:        Existing summary tests unaffected (new parameter defaults). Zero/unknown semantics
              pinned by AnkiBackendDeckSummaryTest + LibraryViewModelTest.
ARCH NOTES:   No Android/UI imports; unknown ≠ zero; identity untouched.
```

### core/anki/AnkiBackend.kt — MODIFIED

```text
PURPOSE:      Backend-neutral Anki boundary.
IMPLEMENTED:  suspend fun getDeckSummary(deckId): AnkiResult<AnkiDeckSummary> as a DEFAULT
              implementation derived from the single batched getDecks() read — one backend call
              per summary, no N+1 (INV-14-17). Blank ID → InvalidRequest without any backend
              call; listing failure propagates typed; missing deck → DeckNotFound with the exact
              backend-qualified ref. Backends with a cheaper per-deck transport may override.
TESTS:        AnkiBackendDeckSummaryTest (new): identity, zero vs unknown counts, DeckNotFound,
              CollectionUnavailable/PermissionRequired propagation, capability-off refusal,
              blank-ID refusal, bounded read count.
ARCH NOTES:   No UI/provider types; no scheduler logic; both registered backends inherit it.
```

### ui/components/anki/DeckRow.kt — CREATED

```text
PURPOSE:      Reusable deck presentation (checkpoint 13).
IMPLEMENTED:  DeckRow (name, indentation by depth, filtered/selected markers, New/Learning/Review
              chips, total-due line, expand affordance, open intent), DeckCountChip, and the
              deckCountDescription accessibility helper — extracted verbatim from LibraryScreen.
              Counts render "—" when unknown and 0 when the backend confirmed zero; counts are
              hidden entirely when the deckCounts capability is unverified. Study is not a row
              intent: it starts from Deck Details through the session-start site.
TESTS:        Rendering matrix (long names, Arabic/mixed RTL, zero vs unknown counts, nesting)
              is exercised by the existing DeckTreeUiMapperTest/AnkiDeckTreeTest projections that
              feed the row, plus the row's semantics strings which distinguish unknown from zero.
ARCH NOTES:   Pure presentation; no backend types; only intents in, nothing read out.
```

### ui/screens/library/LibraryScreen.kt — MODIFIED

```text
PURPOSE:      Renders Library state only.
IMPLEMENTED:  Private row composables removed; screen now imports the shared DeckRow. No behavior
              change — same call site, same arguments.
ARCH NOTES:   Composition still dispatches only ViewModel intents; rows are keyed by
              "deck:<stableKey>" / "group:<fullName>".
```

### ui/screens/library/DeckDetailsScreen.kt — MODIFIED

```text
PURPOSE:      Renders one deck's details.
IMPLEMENTED:  "Total cards" line rendered only when summary.totalCards != null (backend-reported),
              with accessibility description; zero renders as "No cards in this deck".
ARCH NOTES:   No other state added; card browser/edit/custom-study remain out of scope.
```

### Tests — MODIFIED / CREATED

```text
app/src/test/.../anki/AnkiBackendDeckSummaryTest.kt   CREATED — contract tests above.
app/src/test/.../ui/DeckDetailsViewModelTest.kt       MODIFIED — study-entry assertions now pin
                                  the locked backend preference (ANKIDROID_LOCAL) and the frozen
                                  reviewer-action capability set; new typed-refusal test for a
                                  deck deleted between render and tap (no dispatch, DeckNotFound).
app/src/test/.../ui/LibraryViewModelTest.kt           MODIFIED — new cross-backend guard test:
                                  a snapshot scoped to another backend identity is never rendered
                                  (fails closed as Error(BackendUnavailable), zero reads of the
                                  observed backend through the foreign cache).
```

---

## 3. Files intentionally not created

| Planned file | Why it was not created |
|---|---|
| `data/anki/remote/PcAnkiBackend.kt` | No PC Anki backend implementation exists in the app (only the `AnkiBackendId.PcAgent` identity and the `PC_AGENT` mode). Creating one would be a new subsystem, explicitly out of scope. When a PC backend lands, it implements `AnkiBackend` and inherits the `getDeckSummary` default; summary fields it cannot provide stay `null`. |
| `ui/screens/library/LibraryModels.kt` | Equivalent exists: `LibraryUiState.kt` (one canonical state + `DeckListItem` + tree projection). |
| `ui/screens/library/DeckTreeMapper.kt` | Equivalent exists: `DeckTreeUiMapper.kt` delegating to the domain `AnkiDeckTreeBuilder` (the hierarchy math lives once, in `core/anki/AnkiDeckTree.kt`). |
| `ui/screens/deckdetails/*` package | Deck Details already lives in `ui/screens/library/` with its own state/VM/screen; moved nothing, per "extend, don't duplicate". |
| Batched `getDeckSummaries(ids)` | Not added: summaries already arrive from one batched `getDecks()` read via `AnkiLibraryRepository` (single-flight, generation-guarded). Batching would be speculative. |
| Separate study-start subsystem | Not added: `AnkiLocalStudyStarter` (GATE 13 §17) is the one session-start site and already accepts `Options(deckId)`. |

---

## 4. Implementation summary

GATE 14's library experience was largely established by GATE 05 (deck domain, repository,
freshness model) and the subsequent library UI layer. This pass completed the gate's explicit
contract: an honest `totalCards` on the deck summary, an explicit `getDeckSummary(deckId)` on the
backend abstraction (derived, batched, typed), a reusable `DeckRow` component at the
`ui/components/anki` boundary, total-cards rendering in Deck Details, and the remaining focused
tests (summary contract, cross-backend stale-data guard, study-entry lock assertions, typed
deck-vanished refusal). No alternate study or mutation path was introduced.

---

## 5. Audit findings (PART II)

```text
AUDIT 1  File responsibility ............ PASS — gateway/mapper/domain/VM/compose stay separated;
                                            DeckRow extraction gives presentation its own file.
AUDIT 2  Backend boundary ................ PASS — grep of ui/screens/library + DeckRow.kt for
                                            ContentResolver/Uri/FlashCardsContract/AddContentApi/
                                            AnkiConnect returns zero hits.
AUDIT 3  Scheduler authority ............. PASS — no due-order/FSRS/interval computation anywhere
                                            in library code; counts are display snapshots only.
AUDIT 4  Deck identity ................... PASS — navigation route, VM key, tree keys and study
                                            start all use deckId (AnkiDeckRef.stableKey); names
                                            are display-only.
AUDIT 5  Unknown vs zero ................. PASS — mapper yields null for missing counts (never 0);
                                            UI renders "—" for null and 0 for confirmed zero;
                                            totalCards renders nothing when null. Pinned by tests.
AUDIT 6  N+1 behavior .................... PASS — see §8 request profile: 1 getDecks + 1
                                            getSelectedDeck per refresh regardless of deck count;
                                            getDeckSummary default = one listing read.
AUDIT 7  Backend switching ............... PASS — LibraryViewModel refuses snapshots whose
                                            backendId ≠ observed backend (new test); repository
                                            cache is backend-scoped; selector never migrates a
                                            session (DeckDetailsViewModelTest covers the
                                            "other ready backend" refusal).
AUDIT 8  Session backend lock ............ PASS — library refresh never touches the session;
                                            AnkiStudyRequest freezes backendId/deckRef/capabilities
                                            at start (GATE 13 §17); global availability loss only
                                            surfaces as Unavailable, never redirects.
AUDIT 9  Study entry ..................... PASS — exactly one path: DeckDetailsViewModel.startStudy
                                            → AnkiLocalStudyStarter.start → StudySessionMachine-
                                            Repository.startAnkiStudy; Home uses the same starter.
AUDIT 10 Compose side effects ............ PASS — LazyColumn items dispatch intents only; grep for
                                            backend reads in composables returns none.
AUDIT 11 Stale async handling ............ PASS — repository generation counter discards stale
                                            refreshes (single-flight + gen guard); DeckDetails
                                            matches every projection to (backendId, deckId).
AUDIT 12 Capability handling ............. PASS — all affordances derive from AnkiCapabilities
                                            StateFlow values, never from backend names.
```

---

## 6. Verification evidence (PART III)

JVM tests could not be executed in this sandbox (no JDK/Android SDK; Gradle downloads are not
reachable from this environment). The following suites encode each verification and must be green
in CI; they were written/updated in this pass and reasoned through line-by-line:

```text
VER 1  Normal library ................... LibraryViewModelTest `ready library maps backend decks…`
VER 2  Empty library .................... LibraryViewModelTest `ready empty collection is Empty…`
VER 3  Backend unavailable .............. LibraryBackendAvailabilityTest + LibraryViewModelTest
VER 4  Permission required .............. LibraryViewModelTest `permission failure is unavailable…`
                                          (asserts 0 deck queries, never "zero decks")
VER 5  Unknown counts ................... LibraryViewModelTest `unknown counts remain unavailable…`
                                          + AnkiBackendDeckSummaryTest
VER 6  Zero counts ...................... same tests — zero asserted equal to 0, not null
VER 7  Nested decks ..................... AnkiDeckTreeTest + DeckTreeUiMapperTest
VER 8  Similar names / ID collisions .... DeckDetailsViewModelTest `exact deck ID…duplicate names`
                                          + DeckDetailsNavigationTest route round-trip
VER 9  Arabic / RTL ..................... AnkiDeckTreeTest Arabic cases, DeckTreeUiMapperTest,
                                          LibraryTestFixtures Arabic/mixed-script decks,
                                          TextDirection.ContentOrLtr on every name surface
VER 10 Deck details correctness ......... DeckDetailsViewModelTest identity tests
VER 11 Stale response ................... LibraryViewModelTest cross-backend guard (new);
                                          DeckDetailsViewModelTest stale-owner test; repository
                                          generation tests in AnkiLibraryRepositoryTest
VER 12 Refresh .......................... LibraryViewModelTest stale-refresh test
VER 13 Rapid refresh .................... LibraryViewModelTest `rapid refresh taps use single flight`
VER 14 Backend switch ................... LibraryBackendAvailabilityTest loss/recovery; cross-
                                          backend guard test (new)
VER 15 Study start ...................... DeckDetailsViewModelTest `study start passes the stable
                                          deck ID…` — asserts locked backendId/deckId, frozen
                                          capability set, nextCardCount == 0 at start
VER 16 No due card ...................... backend remains authority: FakeAnkiBackend Finished path
                                          (FakeAnkiBackendTest); library never pre-computes due
VER 17 Large collection ................. 420-deck fixture: 1 getDecks + 1 getSelectedDeck call,
                                          all 420 stable identities projected (bounded reads test)
VER 18 Activity recreation .............. nav VM keyed per deckId; repository keeps last-good
                                          snapshot — no reload loop construct exists
VER 19 Navigation restore ............... DeckDetailsNavigationTest + keyed viewModel factory
VER 20 GATE 11–13 regressions ........... no mutation file touched in this pass (git diff is
                                          library/contract/tests/docs only); GATE 11–13 suites
                                          (FakeAnkiBackendTest, AnkiDroidRatingCommitTest,
                                          reviewer-action suites) re-run in CI
```

---

## 7. Backend data flow (as implemented)

```text
LibraryScreen ──intents──▶ LibraryViewModel ──▶ AnkiLibraryRepository ──▶ AnkiBackend
DeckDetailsScreen ─intents─▶ DeckDetailsViewModel ─┤                       │
                                                   ▼                       ▼
                                        LibrarySnapshot (single-flight,    AnkiDroidBackend
                                        generation-guarded, backend-       └─ AnkiDroidDeckGateway
                                        scoped cache)                          └─ ContentProvider
                                        AnkiDeckTreeBuilder (hierarchy)           (public API only)
                                        DeckTreeUiMapper (UI projection)
```

Upper layers never see `Cursor`/`Uri`/`FlashCardsContract`; those stop at the gateway/mapper.

---

## 8. Deck identity strategy

- Identity is `AnkiDeckRef(backendId, deckId, collectionKey?)` — the AnkiDroid deck id as text;
  names are display-only and can duplicate legally (`DeckDetailsViewModelTest` pins it).
- Navigation serializes only the deckId (Base64url, single route segment); Deck Details is
  recreated from deckId alone; VMs are keyed `deck-details:$deckId`.
- Hierarchy is derived (`AnkiDeckTreeBuilder`) from `::`-paths; virtual groups carry no id; the
  tree never invents identities (INV-14-02).

## 9. Count semantics

```text
zero        → backend reported 0          → rendered "0"
unknown     → backend did not say (null)  → rendered "—" / hidden, semantics string says
                                            "count unavailable"
unsupported → capability flag false       → counts section hidden, "Counts unavailable"
error       → typed AnkiError             → Error/Unavailable state, never empty, never zero
totalCards  → null until a backend truly reports it → field hidden
```

## 10. Backend request profile

```text
deck count tested:      3-deck, empty, and 420-deck fixtures (nested, Arabic, long names)
getDecks calls:         1 per refresh (single-flight coalesces rapid taps — proven by test)
getSelectedDeck calls:  1 per refresh
getDeckSummary calls:   0 from Library/Details UI (they share the batched snapshot); the contract
                        method itself costs exactly 1 listing read when called directly
max concurrent reads:   1 (repository mutex + in-flight deferred; gateway has its own single-flight)
cache behavior:         last-good snapshot retained, marked STALE on failure (stale-while-error);
                        dropped only on backend-identity mismatch
```

## 11. Study entry

```text
DeckDetailsScreen "Start study"
  → DeckDetailsViewModel.startStudy()
    → AnkiLocalStudyStarter.start(Options(deckId = <stable id>))
      → selector resolves the one ready local backend (never a fake, never a silent switch)
      → refreshAvailability() then re-resolve (fail closed)
      → deckId validated against the LIVE deck listing (DeckNotFound refusal otherwise)
      → reviewer-action capabilities FROZEN from the backend's audited contract
      → dispatch AnkiStudyRequest → StudySessionMachineRepository.startAnkiStudy
        → StudySessionMachine → AnkiBackend.beginReview(context) → nextCard(session)
```

Deck Details never calls `nextCard`/`beginReview` itself (asserted: `nextCardCount == 0` at start).

## 12. Regression result

GATE 11/12/13 files are untouched by this pass (`git diff --stat` is confined to the library
contract/UI/tests/docs). Their suites re-run in CI as the lock condition.

## 13. Invariants

```text
INV-14-01 library depends on domain abstractions only ....... PASS (AUDIT 2)
INV-14-02 stable deck ID identity ........................... PASS (AUDIT 4)
INV-14-03 unknown ≠ zero .................................... PASS (mapper + UI + tests)
INV-14-04 Anki is scheduler authority ....................... PASS (AUDIT 3)
INV-14-05 library never orders reviews ...................... PASS (AUDIT 3)
INV-14-06 library reads are read-only ....................... PASS (no write path reachable)
INV-14-07 provider types stop at gateways ................... PASS (AUDIT 2)
INV-14-08 study starts through session orchestration ........ PASS (AUDIT 9)
INV-14-09 backend locked at session start ................... PASS (§11, tests)
INV-14-10 global changes cannot redirect a session .......... PASS (AUDIT 8)
INV-14-11 capabilities gate functional UI ................... PASS (AUDIT 12)
INV-14-12 async results identity-correlated ................. PASS (AUDIT 11, new test)
INV-14-13 rows never trigger backend reads .................. PASS (AUDIT 10)
INV-14-14 unavailable ≠ empty ............................... PASS (distinct states + tests)
INV-14-15 permission ≠ empty ................................ PASS (test asserts 0 queries)
INV-14-16 refresh never mutates review state ................ PASS (read-only paths only)
INV-14-17 large library bounded ............................. PASS (single-flight + profile §10)
INV-14-18 fake backend covers library scenarios ............. PASS (fixtures + error/delay modes)
INV-14-19 Arabic/RTL validated .............................. PASS via JVM tests; on-device
                                                              rendering NOT YET TESTABLE here
INV-14-20 gates 11–13 unweakened ............................ PASS (no mutation file touched;
                                                              CI re-run required for lock)
```

---

## 14. Gate lock condition

```text
IMPLEMENTATION COMPLETE  ✔ (this pass)
AUDIT COMPLETE           ✔ (§5)
VERIFICATION COMPLETE    ⏳ CI must run: ./gradlew testDebugUnitTest lint assembleDebug
                         assembleRelease (no JVM/Android toolchain exists in this sandbox;
                         connectedDebugAndroidTest additionally needs a device)
```

When CI is green: **GATE 14 LOCKED** — suggested commit `gate14: add Anki library and deck
details experience`.

**Next:** GATE 15 — Card Browser / Search / Filters / Sorting, building on `AnkiBackend`, stable
deck identity, Library navigation, and the capability model — without bypassing the backend
abstraction.
