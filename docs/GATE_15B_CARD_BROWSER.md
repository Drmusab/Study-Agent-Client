# GATE 15B — Card Browser / Search / Filters / Sorting (locked contract)

**Repository:** Drmusab/Study-Agent-Client<br>
**Working branch:** `arena/11856eb2-study-agent-client`<br>
**Supersedes:** `docs/GATE_15_CARD_BROWSER.md` (the earlier, pre-lock 18-invariant draft — kept for history, still marked OPEN)<br>
**Scope:** the single read-only browse entry point, its query model and its verification. GATE 16 owns one-card hydration / Card Details.

---

## 0. Verdict

```text
Normative contract implementation:       IMPLEMENTED — one entry point, explicit scope, literal
                                        text, explicit filter/sort categories, bounded opaque
                                        paging, typed refusals, read-only boundary
Domain/backend contract semantics:       LOCKED (structure enforced by types + contract suite)
Live AnkiDroid browser support:          truthfully UNSUPPORTED — the pinned public provider has
                                        no bounded collection-card list; the adapter refuses with
                                        typed UnsupportedQueryFeature tokens and issues no query
PC Agent browser support:                N/A — no PC Anki backend is registered in this client
Targeted JVM harness evidence:           118 tests PASS, 0 FAIL (non-Gradle harness, §5)
Full Android Gradle build / lint:        NOT RUN — Gradle, Google Maven and Maven Central are not
                                        reachable from this sandbox (no build possible at all)
Compose/UI compilation:                  NOT VERIFIED by the harness (no Compose artifacts offline);
                                        the Android-free UI logic (models, mapper, ViewModel) *is*
                                        compiled and tested
Real-device verification:                NOT RUN
Lock decision:                           Contract semantics: LOCKED against the harness evidence.
                                        Production lock still requires the Gradle CI build, lint and
                                        the real-device matrix, which this sandbox cannot run.
```

The production AnkiDroid adapter is fail-closed: it answers every browse request with a typed
`AnkiError.UnsupportedQueryFeature` (`card_browser` from the backend's capability preflight, and
component tokens from the refuse-only mapper) and issues zero provider requests. No partial
substitute (due queue, note search, known-card item lookup, locally filtered page) is ever used.

---

## 1. What changed in this pass

| Layer | Path (relative to `app/src/main/java/com/studyagent/client/`) | Change |
|---|---|---|
| Query model | `core/anki/AnkiCardQuery.kt` | Rewritten: `AnkiCardQuery(scope, text, filters, sort, page)`, `AnkiCardScope`, `AnkiCardFilters`, `SuspensionFilter`/`BurialFilter`, `SortDirection`, `AnkiCardSort`, normalization, `structuralError()`, `unsupportedFeature()`, `preflightError()`, `consistencyKey()` |
| Paging | `core/anki/AnkiCardPaging.kt` (new) | `AnkiPageCursor`, `AnkiQuerySnapshotToken`, `AnkiPageRequest` (MIN 1 / DEFAULT 50 / MAX 100), `AnkiCardPage` (+`of()`), `AnkiCardListItem.pageIdentityError()`, `AnkiOffsetCursorAdapter` |
| Capabilities | `core/anki/AnkiCapabilities.kt` | `AnkiCardBrowserCapabilities` (per-feature) + `AnkiCardFilterCapability` / `AnkiCardSortCapability`; legacy coarse fields (`browse`, `deckScope`, `textSearch`, `*Filter`, `sorts: Set<AnkiCardSort>`, `totalCount`, `answerPreview`) removed |
| Errors | `core/anki/AnkiErrors.kt` | `UnsupportedQueryFeature`, `InvalidQuery`, `InvalidCursor`, `DataIntegrityFailure`, `TransientFailure` + the §49 category→type table |
| Backend port | `core/anki/AnkiBackend.kt` | `browseCards(AnkiCardQuery): AnkiResult<AnkiCardPage>` is the only browse verb; default refuses with `UnsupportedQueryFeature("card_browser")` |
| Items | `core/anki/AnkiModels.kt` | `AnkiCardListItem` identity + optional backend-reported metadata; `dueEpochSeconds`, note created/modified epochs added for the `Due`/`Created`/`Modified` sorts |
| Diagnostics | `data/repository/DiagnosticsRepository.kt` | Rows read the structured capability fields |
| AnkiDroid | `data/anki/ankidroid/{AnkiDroidBackend,AnkiDroidCardBrowserGateway,AnkiDroidCardQueryMapper,AnkiDroidCompatibilityPolicy}.kt` | Ordered preflight (`usabilityError → structuralError → preflightError → gateway`), refuse-only mapper with component tokens, capability rows keyed on `canBrowse`/`canSearchText` |
| UI models | `ui/screens/cardbrowser/CardBrowserModels.kt` | `CardBrowserQueryUi`, `CardBrowserRow` (text-only previews), filter/sort chip models + pure builders, sealed `CardBrowserUiState` |
| UI mapper | `ui/screens/cardbrowser/CardBrowserQueryMapper.kt` | Selection → query, trim-only text, cursor passed through verbatim |
| ViewModel | `ui/screens/cardbrowser/CardBrowserViewModel.kt` | Query identity/generation guards, cursor opacity, debounce, dedupe by `AnkiCardRef`, typed states, backend switch invalidation, cancellation passthrough |
| UI | `ui/screens/cardbrowser/CardBrowserScreen.kt`, `ui/components/anki/CardBrowser{Filters,SortMenu,Row}.kt`, `ui/screens/library/DeckDetailsScreen.kt` | Capability-driven affordances, tri-state filter chips with explicit enums, direction-explicit sort menu, deck-scope entry gated on `canBrowseDeck` |

Tests added/rewritten: `anki/AnkiCardQueryTest.kt`, `anki/AnkiCardBrowserBackendTest.kt`,
`anki/Gate15ArchitectureAuditTest.kt`, `anki/contract/{AnkiCardBrowserContractSuite,CardBrowserContractFixture,FakeContractFixtures,FakeAnkiBackendContractTest,LimitedFakeAnkiBackendContractTest}.kt`,
`anki/fake/{FakeAnkiBackend,CardBrowserTestFixtures}.kt`, `anki/ankidroid/AnkiDroidCardQueryMapperTest.kt`,
`ui/CardBrowser{QueryMapper,Row,ViewModel}Test.kt`.

---

## 2. The model as implemented

**Entry point (§1).** `AnkiBackend.browseCards(query): AnkiResult<AnkiCardPage>` — the only browse
verb in the production tree (`Gate15ArchitectureAuditTest` scans the whole main tree for
`searchCards`/`filterCards`/`sortCards`/`browseDeckCards`/`getCardsPage`).

**Scope (§3–§6).** `AnkiCardScope.AllCards | AnkiCardScope.Deck(deckId: String, includeChildren:
Boolean = true)`. There is no nullable deck id anywhere in the contract, so "global" and "selected
deck" cannot be confused. Child decks resolve through the authoritative deck hierarchy
(`parentRef`), never by name prefix. A deck identity that does not exist is
`AnkiError.DeckNotFound` — never an empty page.

**Text (§7–§11).** `text: String?` is literal user search text over backend-supported searchable
fields. Normalization is exactly `trim` (plus `null`/empty → `null`); no case folding, accent
stripping, diacritic removal or manual tokenization. The UI layer never sends backend query syntax:
the audit scans the browser UI for `deck:`/`tag:`/`is:`/`prop:` literals and fails if one appears.

**Filters (§12–§19).** `AnkiCardFilters(flags: Set<AnkiFlag>, tags: Set<String>, cardTypes:
Set<AnkiCardType>, suspension: SuspensionFilter, burial: BurialFilter)` with
`SuspensionFilter{Any,SuspendedOnly,NotSuspended}` and `BurialFilter{Any,BuriedOnly,NotBuried}`.
Flags and card types are OR sets, tags are an AND set, categories combine with AND, and an empty set
means "no restriction" — never "unflagged only". No nullable booleans and no queue-number inference
exist (the audit forbids `Boolean?` in the filter type and `queueState` in the filter UI).

**Sort (§22–§26).** `AnkiCardSort{Default; Due(direction); Created(direction); Modified(direction);
Reps(direction); Lapses(direction)}` with `SortDirection{ASCENDING,DESCENDING}`. `Default` is the
backend's own deterministic browsing order. Every explicit sort must have a deterministic
tie-breaker; the reference backend sorts on backend-reported keys with `null` values last and breaks
ties by `AnkiCardRef.stableKey`, and the UI never re-sorts a loaded page.

**Paging (§27–§33).** `AnkiPageRequest(limit = 50, cursor = null)` with MIN 1 / MAX 100; an
out-of-range limit is *representable and rejected* (`InvalidQuery` with
`card_page_limit_below_minimum|_above_maximum`), never clamped. `AnkiPageCursor` is an opaque value
class: the ViewModel may store and return `nextCursor`, and the audit forbids cursor construction,
parsing, `offsetFor` or offset/generation encoding anywhere in the UI. The offset-encoding adapter
binds the offset to the query consistency key, so a cursor from another query/backend/collection is
a typed `InvalidCursor` (`malformed_cursor`, `cursor_query_mismatch`,
`cursor_offset_out_of_range`) — never a silent restart.

**Page (§34–§41).** `AnkiCardPage(items, nextCursor, totalCount, snapshotToken)`: `nextCursor == null`
never means "collection empty"; `totalCount == null` means "unavailable" (never an estimated exact
count, never zero); a valid empty first page is not an error; an empty page can never carry a cursor
(enforced in the constructor and normalized by `of()`); the fake cannot prove snapshot isolation and
therefore reports `snapshotToken = null`; the ViewModel still dedupes defensively by `AnkiCardRef`.

**Items (§42–§48).** Browser rows always carry `cardRef` (and thereby note/deck identity) plus
optional backend-reported `deckName`/`questionText`/`answerText`/`tags`/`flag`/`type`/scheduling/
`suspended`/`buried`. No `questionHtml`/`answerHtml`/media/template data crosses the list boundary
(the audit asserts the row model has no such field). A row that cannot supply identity fails the
page with `DataIntegrityFailure` (`foreign_card_ref`, `card_row_missing_note_identity`,
`card_row_missing_deck_identity`, `card_row_out_of_scope`) instead of publishing an invented ref.

**Errors (§49–§54).** Only normalized domain errors cross the boundary: `BackendUnavailable`,
`ProviderUnavailable`, `PermissionRequired`, `CollectionUnavailable`, `DeckNotFound`/`CardNotFound`,
`UnsupportedQueryFeature(feature)`, `InvalidQuery(detail)`, `InvalidCursor(detail)`,
`TransientFailure`, `DataIntegrityFailure`, `Unknown`. `commitCategory()` maps each to a stable
diagnostic token (`unsupported_query_feature:…`, `invalid_query:…`, `invalid_cursor:…`,
`data_integrity:…`, `transient:…`).

**Capabilities (§20–§21, §62).** `AnkiCardBrowserCapabilities(canBrowseAllCards, canBrowseDeck,
canIncludeChildDecks, canSearchText, supportedFilters, supportedSorts, supportsTotalCount,
maxPageSize)`. There is no coarse `cardFilters`-style flag; UI preflight is convenience only — the
backend re-validates every request and returns the typed refusal.

**Hard prohibitions.** Never silently ignore/approximate/downgrade a query component; never fake a
global filter or sort by post-processing a loaded page; never route backend query syntax through the
UI; never mutate anything (rating, bury, suspend, flag, edit, change deck, sync, mark viewed).

---

## 3. Validation order (§63)

| Step | Where |
|---|---|
| 1. Backend available / permission / provider | `AnkiDroidBackend.usabilityError()`; fake `usabilityError()` |
| 2. Collection available | same availability family (`CollectionNotInitialized` → `CollectionUnavailable`) |
| 3. Query structurally valid | `AnkiCardQuery.structuralError()` (limit bounds, blank deck id, blank tag, blank cursor) |
| 4. Scope identity valid | backend deck lookup → `DeckNotFound` (never an empty page) |
| 5. Features supported | `AnkiCardQuery.unsupportedFeature(capabilities)` → `UnsupportedQueryFeature(feature)` |
| 6. Cursor valid for this exact query | `AnkiOffsetCursorAdapter.offsetFor` → `InvalidCursor` |
| 7. Execute one collection read | fake: filter → sort → offset slice; AnkiDroid: refused before any provider call |
| 8. Map data (identity-checked) | `AnkiCardListItem.pageIdentityError` → `DataIntegrityFailure` |
| 9. Return one bounded page | `AnkiCardPage.of(...)`; empty ⇒ no cursor; no cursor ⇒ no more rows |

Ordering is asserted, not assumed: `Gate15ArchitectureAuditTest.INV-15-14` reads the reference
backend's source and fails if structural validation, capability preflight or cursor resolution move
relative to each other or relative to the page slice.

---

## 4. AnkiDroid capability truth

`AnkiDroidCompatibilityPolicy.implementedCapabilitiesFor(spec, isReady).cardBrowser` advertises
**nothing** (`canBrowseAllCards = false`, …). `AnkiDroidBackend.browseCards` therefore refuses with
`UnsupportedQueryFeature("card_browser")` before the provider seam. Should a future adapter pass that
preflight, the refuse-only mapper names the exact component it still cannot honour:
`card_search`, `card_filter_flags`, `card_filter_tags`, `card_filter_types`,
`card_filter_suspended`, `card_filter_buried`, `card_sort_<due|created|modified|reps|lapses>`,
`card_browser_deck_scope`, `card_browser_scope_all`, `card_browser_public_api`.
Diagnostics report the same structured fields (`Card Browser = canBrowse`, `Card Search =
canSearchText`); the coarse 13-flag `AnkiCapabilities` pairs (`cardBrowser`, `cardSearch`) remain for
provenance only and are not a second source of query truth.

---

## 5. Verification evidence (non-Gradle harness)

The sandbox cannot run Gradle (no network path to Google Maven / Maven Central / services.gradle.org,
no Android SDK). Verification therefore uses a standalone Kotlin compiler against the Android-free
slice of the app:

```text
JAVA_HOME=/tmp/g15/jdk/jdk4py/java-runtime        # runtime-only JDK
kotlinc 2.4.21 at /tmp/g15/kotlinc                 # + kotlinx-serialization compiler plugin
classpath: kotlinx-coroutines-core-jvm 1.8.1, kotlinx-serialization-{core,json}-jvm 1.6.3,
           junit 4.13.2, hamcrest 1.3
main set:  core/anki/*.kt, core/models/{Rating,AgentCapabilities}.kt,
           core/common/StoreFailureBoundary.kt, data/anki/ankidroid/AnkiDroid{CardQueryMapper,
           CardBrowserGateway,CompatibilityPolicy,ApiContract}.kt        → 0 errors
ui set:    ui/screens/cardbrowser/{CardBrowserModels,CardBrowserQueryMapper,CardBrowserViewModel}.kt
           compiled against an androidx.lifecycle shim                      → 0 errors
tests:     118 tests, 0 failures
```

| Test class | Tests | What it establishes |
|---|---|---|
| `anki.contract.FakeAnkiBackendContractTest` | 23 | §75 semantics against the fully capable reference backend |
| `anki.contract.LimitedFakeAnkiBackendContractTest` | 23 | the same suite with a deliberately limited capability set: every unsupported component is refused with its token (§76) |
| `anki.AnkiCardQueryTest` | 11 | query model: normalization, consistency key, structural tokens, capability tokens, page/cursor invariants |
| `anki.AnkiCardBrowserBackendTest` | 6 | collection scale (1 200 cards), bounded pages, read-only counters, foreign-cursor refusal |
| `anki.AnkiArchitectureContractTest` | 14 | GATE 01 identity/architecture contract still holds |
| `anki.Gate15ArchitectureAuditTest` | 19 | source-level invariants INV-15-01…INV-15-19 (§6) |
| `anki.ankidroid.AnkiDroidCardQueryMapperTest` | 4 | AnkiDroid refusals name the exact component; the capability matrix advertises nothing |
| `ui.CardBrowserQueryMapperTest` | 3 | selection → query mapping, trim-only text, cursor passthrough |
| `ui.CardBrowserRowTest` | 1 | row projection: markup stripping, preview clamp, identity retention |
| `ui.CardBrowserViewModelTest` | 14 | query change/cursor invalidation, debounce, stale-response rejection, backend switch, capability refusal, missing deck, invalid cursor, dedupe, read-only counters, navigation payload |
| **total** | **118** | **all PASS** |

---

## 6. Per-invariant results

| Invariant | Result | Evidence |
|---|---|---|
| INV-15-01 browser UI is backend-neutral, no provider/storage access | PASS | `Gate15ArchitectureAuditTest` |
| INV-15-02 one browse entry point; no parallel query verb; query carries 5 components | PASS | audit (whole-main-tree scan) |
| INV-15-03 explicit scope; deck identity never nullable | PASS | audit + `AnkiCardQueryTest` |
| INV-15-04 literal text, trim-only normalization, no backend syntax in UI | PASS | audit + `AnkiCardQueryTest` + `CardBrowserQueryMapperTest` |
| INV-15-05 filters are explicit categories (no nullable booleans, no queue inference) | PASS | audit + suite (multi-flag OR, multi-tag AND, category AND, tri-state) |
| INV-15-06 unsupported components refused by token, structural check first | PASS | audit + both contract subclasses |
| INV-15-07 directional sorts with deterministic backend tie-breaker | PASS | audit + suite (both directions, stable tie-break) + `AnkiCardBrowserBackendTest` |
| INV-15-08 page bounds explicit; invalid limits rejected; cursor opaque to UI | PASS | audit + `AnkiCardQueryTest` + suite (limit bounds) |
| INV-15-09 unknown totals and exhausted pages stay distinguishable | PASS | audit + suite (unknown count ≠ zero, empty page ⇒ no cursor) |
| INV-15-10 rows lightweight/text-only, never hydrated in the list | PASS | audit + `CardBrowserRowTest` |
| INV-15-11 browsing read-only; cancellation never an error | PASS | audit + suite (cancellation case, mutation counters) + `CardBrowserViewModelTest` |
| INV-15-12 stale pages/query changes invalidate paging without silent restart | PASS | audit + `CardBrowserViewModelTest` (stale page, backend switch, invalid cursor) |
| INV-15-13 missing row identity is a typed data-integrity failure | PASS | audit + `AnkiCardQueryTest` + reference backend mapping |
| INV-15-14 backend validates the whole query before slicing one bounded page | PASS | audit (ordering scan) + suite |
| INV-15-15 capabilities are structured per feature and drive every affordance | PASS | audit + `AnkiDroidCardQueryMapperTest` |
| INV-15-16 states canonical, accessible, RTL-safe | PASS | audit + `CardBrowserViewModelTest` (Loading/Ready/Empty/Unavailable/Error) |
| INV-15-17 details navigation carries identity only; GATE 16 owns hydration | PASS | audit |
| INV-15-18 every query selection restarts from a fresh first page | PASS | audit + `CardBrowserViewModelTest` |
| INV-15-19 browser capability truth comes from one structured source | PASS | audit |

§75 case list → `AnkiCardBrowserContractSuite` (23 cases per subclass): valid empty query, deck-only,
deck + children (authoritative hierarchy), text-only, text + deck, multi-flag OR, multi-tag AND,
multi-category AND, tri-state suspension/burial, card-type OR, sort direction + stable tie-break,
first/next page without duplicates or skips, empty result, duplicate-looking cards, missing deck,
real-but-empty deck, invalid cursor, cursor bound to its query, foreign cursor, limit bounds,
read-only counters, cancellation.

§76 negative list → asserted as typed refusals, not as smaller pages: unsupported tag/suspension/
burial/flag/type filters and unsupported sorts return their exact `UnsupportedQueryFeature` tokens;
a changed query refuses the old cursor; an invalid cursor never silently restarts; the reference
backend filters/sorts the whole matching set *before* the page slice (audit); a missing deck is
`DeckNotFound` while a real empty deck is an empty page; an unavailable total count is `null`, never
zero.

---

## 7. Limits of this evidence (explicitly not verified)

1. **No Gradle build, lint or unit-test task was run.** The sandbox cannot reach Google Maven, Maven
   Central or the Gradle distribution service, so `./gradlew assembleDebug` / `testDebugUnitTest` /
   `lint` are impossible here. Nothing in this document substitutes for them.
2. **Compose UI compilation is not covered by the harness.** `CardBrowserScreen.kt`, the
   `ui/components/anki/CardBrowser*.kt` components, `DeckDetailsScreen.kt` and `DiagnosticsRepository.kt`
   were migrated by inspection and are covered only by source-level audits (no provider symbols, no
   local re-sort, capability-driven affordances) plus the pure helpers they call, which *are*
   compiled and tested.
3. **The production AnkiDroid browse path is not compiled in the harness** (it needs the Android
   SDK). Its refusal logic is exercised through the compiled refuse-only mapper/gateway and policy,
   and through the test text updated in `AnkiDroidBackendTest`.
4. **One reference backend implementation only.** The contract suite exists precisely so a future
   production backend can be plugged in and validated against the same 23 cases; no parity claim is
   made for any production backend in this gate.
5. **No real-device run.** The AnkiDroid refusal behaviour (no provider traffic, typed error
   surfaced in the UI) should be re-checked on device when the Gradle build is available.
6. **`kotlinx-coroutines-test` and `turbine` are not available offline**, so the ViewModel tests use
   `runBlocking` with real (short) delays instead of a virtual clock. Semantics are asserted on state
   predicates; timing-sensitive assertions are bounded by a 10 s timeout.

## 8. Follow-ups (recommended, ordered)

1. Run the full Gradle build + unit tests + lint in CI on this branch and attach the report to this
   gate; fix anything the Compose/navigation compilation surfaces.
2. Re-run the 23-case contract suite from Gradle against the reference fake and, when a bounded
   provider query is audited in, against the production AnkiDroid adapter.
3. Real-device check: open the browser for a deck, confirm the "browsing unsupported" state (not an
   empty list) for the pinned AnkiDroid API, and confirm no provider request is issued.
4. Keep `docs/GATE_15_CARD_BROWSER.md` frozen as the pre-lock draft; delete it once this document is
   accepted so the repository has a single GATE 15 record.
