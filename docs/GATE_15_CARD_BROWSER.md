# GATE 15 — Backend-neutral Card Browser

> **Superseded (pre-lock draft).** This document describes the earlier 18-invariant draft and its
> "OPEN — NOT LOCKED" verdict. The locked contract, the current implementation map and the current
> verification evidence live in [`GATE_15B_CARD_BROWSER.md`](GATE_15B_CARD_BROWSER.md). This file is
> kept only as history and must not be used as the GATE 15 status source.

**Repository:** Drmusab/Study-Agent-Client<br>
**Working branch:** `arena/e52f7ebb-study-agent-client`<br>
**Scope:** read-only, deck-scoped card browsing; GATE 16 owns full Card Details hydration.

---

## 0. Verdict

```text
GATE 15 status:                         OPEN — NOT LOCKED
Feature code:                           added across the domain, backend, ViewModel, UI,
                                        navigation, Deck Details and test layers
Live AnkiDroid browser support:         truthfully UNSUPPORTED; no audited bounded public
                                        collection-card query was available
PC Agent browser support:               N/A — no PC Anki backend implementation is registered
Backend capability parity:              NOT CLAIMED
Targeted JVM harness evidence:           65 tests PASS, 0 FAIL (non-Gradle harness; see §7)
Full Android Gradle build / lint:         NOT RUN — Gradle distribution and Android dependency
                                        repositories are not available in this sandbox
Compose/navigation compilation:          NOT VERIFIED by the fallback harness
Real-device verification:                NOT RUN
Lock decision:                           DO NOT LOCK until the full Android build, lint and CI
                                        verification pass
```

The production AnkiDroid adapter is intentionally fail-closed. It does not convert the known-card
item lookup or generic note search into a collection browser, and it sends no provider query for
this operation. The browser affordance in Deck Details is therefore hidden for the current live
backend. The test fake exercises the complete browser contract but is not a production backend and
does not establish parity.

---

## 1. Repository mapping and checkpoint order

Paths below are relative to `app/src/main/java/com/studyagent/client/`, unless noted.

| Checkpoint | Planned responsibility | Actual path | Outcome |
|---|---|---|---|
| 00 — map existing seams | backend / capabilities / UI / navigation | `core/anki/AnkiBackend.kt`, `AnkiCapabilities.kt`, `AnkiModels.kt`; `ui/screens/library/DeckDetailsScreen.kt`; `ui/navigation/*` | Extended existing seams; no duplicate backend or library subsystem |
| 01 — neutral query contract | query, filters, sorts, paging, lightweight projection | `core/anki/AnkiCardQuery.kt`, `AnkiModels.kt`, `AnkiCapabilities.kt` | Added bounded request and capability-gated query semantics |
| 02 — backend honesty | provider edge, mapping, AnkiDroid capability report | `data/anki/ankidroid/AnkiDroidCardBrowserGateway.kt`, `AnkiDroidCardQueryMapper.kt`, `AnkiDroidBackend.kt`, `AnkiDroidCompatibilityPolicy.kt` | Added explicit typed refusal; no unaudited provider calls |
| 03 — deterministic backend fixture | large collection, search, filtering, sorting, pages | `app/src/test/.../anki/fake/FakeAnkiBackend.kt`, `CardBrowserTestFixtures.kt` | Added 1,200-card multilingual fixture and request counters |
| 04 — query projection and state | normalization, cancellation, stale protection, pagination | `ui/screens/cardbrowser/CardBrowserQueryMapper.kt`, `CardBrowserModels.kt`, `CardBrowserViewModel.kt` | Added one backend-bound ViewModel and canonical UI state |
| 05 — read-only UI | search, supported filters/sorts, accessible rows, empty/error/loading | `ui/screens/cardbrowser/CardBrowserScreen.kt`, `ui/components/anki/CardBrowserFilters.kt`, `CardBrowserSortMenu.kt`, `CardBrowserRow.kt` | Added display-only Compose surfaces; no row-level backend calls |
| 06 — navigation and entry point | stable deck/card identity | `ui/navigation/Screen.kt`, `AppNavHost.kt`, `CardDetailsPlaceholderScreen.kt`, `DeckDetailsScreen.kt` | Added optional deck route, reference-only details route, gated Deck Details entry |
| 07 — audit and verification | contract, ViewModel, capability, navigation and source audits | `app/src/test/.../Gate15ArchitectureAuditTest.kt` and focused tests below | Targeted fallback evidence passes; full build/audit verification remains outstanding |

No `data/anki/remote/PcAnkiBackend.kt` was added: the PC Agent identity/mode exists, but no PC
backend implementation is registered in this app. No public AnkiDroid card-list API was inferred.

---

## 2. File-by-file checkpoint report

### Domain contract — CREATED / EXTENDED

```text
FILES:        core/anki/AnkiCardQuery.kt; AnkiModels.kt; AnkiCapabilities.kt; AnkiBackend.kt
IMPLEMENTED:  AnkiCardQuery carries optional stable deck ID, plain text, neutral filters/sort and an
              opaque cursor with a hard 1..100 page limit. Search normalization trims and collapses
              Unicode whitespace, maps empty input to null, and does not lowercase or otherwise
              rewrite text. Multi-value flags/card types use OR semantics; selected tags use AND;
              suspended/buried are tri-state exact predicates. Unsupported requested components
              have explicit feature tokens. AnkiCardListItem carries stable refs and text/metadata
              projections only — no HTML, media or full-card field.
CAPABILITY:   AnkiCardBrowserCapabilities independently advertises browse, deck scope, search,
              each filter family, exact totals, answer preview, and supported non-default sorts.
              Default AnkiBackend.browseCards remains unsupported.
TESTS:        AnkiCardQueryTest; CardBrowserQueryMapperTest.
```

### AnkiDroid edge — CREATED / EXTENDED

```text
FILES:        data/anki/ankidroid/AnkiDroidCardBrowserGateway.kt; AnkiDroidCardQueryMapper.kt;
              AnkiDroidBackend.kt; AnkiDroidCompatibilityPolicy.kt; DiagnosticsRepository.kt;
              di/AppContainer.kt
IMPLEMENTED:  The mapper returns an explicit Unsupported mapping for search, filters, sorts, deck
              scope and the absent public collection-list query. The gateway issues no provider
              traffic. AnkiDroidBackend first checks live availability/capabilities and rejects the
              request before crossing the gateway when the capability is false. API diagnostics
              distinguish generic search from card search and card browsing.
CAPABILITY:   Pinned AnkiDroid report: card browse UNSUPPORTED; card search UNSUPPORTED; no deck
              scope, filter, sort, total-count or answer-preview claim.
TESTS:        AnkiDroidCardQueryMapperTest; AnkiDroidBackendTest `browse cards is capability gated
              before the public provider seam`.
```

### Fake backend — EXTENDED

```text
FILES:        app/src/test/.../anki/fake/FakeAnkiBackend.kt;
              app/src/test/.../anki/fake/CardBrowserTestFixtures.kt
IMPLEMENTED:  Collection-wide text matching, deck scope, flag/tag/type/suspended/buried predicates,
              Reps/Lapses sorts, exact total, opaque test cursor and bounded text-only projection.
              Added gate/call/query/limit counters to exercise ignored-cancellation races. The
              fixture contains 1,200 multilingual cards, Arabic tags, unknown queue states and
              duplicate-looking questions with distinct refs. Browse reads do not hydrate or mutate.
CAPABILITY:   All listed browser features are advertised only by the test fake. These capabilities
              are not production evidence.
TESTS:        AnkiCardBrowserBackendTest; large-collection cases in CardBrowserViewModelTest.
```

### Browser state and Compose UI — CREATED

```text
FILES:        ui/screens/cardbrowser/CardBrowserQueryMapper.kt, CardBrowserModels.kt,
              CardBrowserViewModel.kt, CardBrowserScreen.kt;
              ui/components/anki/CardBrowserFilters.kt, CardBrowserSortMenu.kt, CardBrowserRow.kt
IMPLEMENTED:  One boundary: CardBrowserScreen → CardBrowserViewModel → AnkiBackend. Text edits are
              debounced/cancellable. Filter/sort/search/refresh restart at page one. Requests are
              serialized and tagged with backend identity, query, cursor and generation; responses
              must still match before state changes. Pages are bounded and appended by stable
              AnkiCardRef identity; duplicate-looking distinct refs remain. Unsupported fields are
              rejected. Rows use a capped text-only projection; composition performs no backend
              call, hydration, media load, WebView rendering, or mutation.
UI:           Loading, Ready, Empty (no cards vs no matches), Unavailable and Error states; manual
              load-more/retry; only advertised filter/sort controls; semantic descriptions and
              content-derived text direction for Arabic/RTL.
TESTS:        CardBrowserViewModelTest; CardBrowserQueryMapperTest; CardBrowserRowTest.
```

### Navigation and Deck Details — EXTENDED

```text
FILES:        ui/navigation/Screen.kt, AppNavHost.kt;
              ui/screens/cardbrowser/CardDetailsPlaceholderScreen.kt;
              ui/screens/library/DeckDetailsScreen.kt
IMPLEMENTED:  Optional deck route encodes only stable deck ID. Card Details encodes only the
              backend-qualified AnkiCardRef fields, validates malformed ordinals, and never sends
              card content. GATE 15 destination deliberately does not hydrate; GATE 16 owns the
              complete details UI. Deck Details shows “Browse cards” only when both browse and deck
              scope are advertised.
TESTS:        DeckDetailsNavigationTest (including malformed route); source audits for identity and
              capability gating.
```

---

## 3. Backend capability matrix

| Backend | Browse | Deck scope | Backend text search | Filters | Non-default sorts | Exact total | Answer preview | Gate result |
|---|---:|---:|---:|---|---|---:|---:|---|
| `FakeAnkiBackend` (test-only) | Yes | Yes | Yes, across fixture collection | Flags, tags, type, suspended, buried | Reps, Lapses | Yes | Yes | Exercises contract only; not production parity |
| AnkiDroid (pinned public provider) | **No** | No | **No** | None | None | No | No | Typed `UnsupportedAction`; no provider query |
| PC Agent | N/A | N/A | N/A | N/A | N/A | N/A | N/A | No registered backend implementation; default remains unsupported |
| `AnkiBackend` default | No | No | No | None | None | No | No | Typed unsupported default |

The API’s generic `search` capability is not reused as Card Browser search. Search semantics remain
backend-owned; no UI page-local search or capability-parity claim is made.

---

## 4. Mandatory invariants — source-audited (`INV-15-01` … `INV-15-18`)

| Invariant | Result | Evidence |
|---|---|---|
| `INV-15-01` UI/ViewModel do not touch provider APIs or private storage | PASS | `Gate15ArchitectureAuditTest` source scan |
| `INV-15-02` query and row projection are backend-neutral and lightweight | PASS | neutral query/projection scan; `AnkiCardQueryTest` |
| `INV-15-03` normalization trims/collapses, empty→null, no lowercasing | PASS | normalization unit + source audit |
| `INV-15-04` search occurs across backend collection before paging | PASS for fake contract; AnkiDroid rejects | fake backend test + audit; AnkiDroid mapper/capability tests |
| `INV-15-05` capability matrix is explicit; no inferred backend parity | PASS | pinned capability matrix tests + audit |
| `INV-15-06` unsupported filters/sorts are never silently dropped | PASS | query capability tests + AnkiDroid mapper tests |
| `INV-15-07` bounded paging and opaque cursor | PASS | request validation + mapper/ViewModel tests |
| `INV-15-08` stable `AnkiCardRef` identity for row key/navigation | PASS | ViewModel, route and source audit tests |
| `INV-15-09` stale responses are rejected by backend/query/generation/cursor | PASS | ignored-cancellation ViewModel test + source audit |
| `INV-15-10` list rows are capped plain text; no HTML/media hydration | PASS | `CardBrowserRowTest` + source audit |
| `INV-15-11` browser remains read-only; no row-level backend call | PASS | fake counters, ViewModel assertions + source audit |
| `INV-15-12` deck scope is sent and response scope is checked | PASS | deck query and mismatch guard tests/source audit |
| `INV-15-13` Arabic/RTL and accessibility semantics | PASS by source audit; device rendering not verified | text-direction and semantics audit; no real-device run |
| `INV-15-14` Card Details carries stable ref only; hydration deferred to GATE 16 | PASS | route test + placeholder source audit |
| `INV-15-15` Deck Details entry requires browse + deck-scope capabilities | PASS | Deck Details source audit |
| `INV-15-16` no study-session/backend write-lock changes | PASS | source audit; no study-session mutation code added |
| `INV-15-17` empty/loading/unavailable/error states are distinct | PASS | UI-state tests/source audit |
| `INV-15-18` search/filter/sort/refresh restart at first page | PASS | ViewModel tests/source audit |

---

## 5. Read-only and identity boundaries

```text
CardBrowserScreen
    → CardBrowserViewModel
        → AnkiBackend.browseCards(AnkiCardQuery)
            → implementation-specific read gateway

Card row:        AnkiCardListItem → capped CardBrowserRow text projection
Card navigation: AnkiCardRef only → GATE 15 placeholder
Deck entry:      AnkiDeckRef.deckId only, gated by browse + deckScope
```

No rating/reviewer-action operation, note/card mutation, scheduler mutation, bulk action, or
study-session backend-lock change was introduced. There is no row hydration, full HTML/media list
projection, local page-only search, or per-row backend request.

---

## 6. Checkpoint report

```text
CHECKPOINT 00 — Repository map .......... PASS; no PC backend / no public bounded AnkiDroid list API
CHECKPOINT 01 — Domain query/capability . PASS; bounded neutral query + explicit capabilities
CHECKPOINT 02 — Backend seam ............ PASS; AnkiDroid false claims rejected before provider
CHECKPOINT 03 — Test backend ............ PASS; 1,200-card multilingual collection and query hooks
CHECKPOINT 04 — ViewModel ............... PASS in focused harness; search/paging/stale guards tested
CHECKPOINT 05 — Compose screen .......... IMPLEMENTED; Android Compose compiler not run
CHECKPOINT 06 — Navigation/entry ........ PASS in route tests; Deck Details capability-gated
CHECKPOINT 07 — Audit/verification ...... PARTIAL; all 18 source invariants and 65 focused tests
                                           pass in fallback harness; full Android build/lint remains
                                           a required lock blocker
```

---

## 7. Verification evidence and remaining gates

```text
`git diff --check`:                       PASS
Fallback main compile:                    0 errors across 225 non-Compose source inputs
Focused test compile/run:                 65 PASS / 0 FAIL
Source audit:                              18/18 INV-15-* PASS
Full `./gradlew testDebugUnitTest`:        NOT RUN (Gradle 8.7 distribution unavailable)
Android Compose/navigation build:         NOT VERIFIED
Android lint:                              NOT RUN
Full repository test suite:                NOT RUN
Real AnkiDroid device verification:         NOT RUN
```

The fallback harness uses Kotlin 2.3/K2 and coroutines-test 1.10.2 rather than the project’s Gradle
versions. Because it excludes AndroidX lifecycle/Compose sources, the focused run compiled
`CardBrowserViewModel` and its tests with a temporary, external lifecycle shim; that shim and all
harness artifacts are outside the repository. Thus the 65 passing tests are useful focused evidence,
not a substitute for the Android Gradle build, Compose compiler, lint or CI.

**Do not mark GATE 15 locked.** The code, capability audit and focused contract tests are in place;
full project verification is still required. Current production AnkiDroid browsing remains
unavailable by design until a safe public collection-list query is audited or another registered
backend implements the contract.
