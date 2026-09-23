# GATE 07 — Card Extraction & Normalized Card Model

Pinned integration: **AnkiDroid v2.24.1**, provider spec **2** (`docs/ANKIDROID_INTEGRATION.md`
§2/§26). Full contract source-of-truth: the GATE 07 provenance table in
`AnkiDroidApiContract.kt`'s KDoc and §2 below. Everything in §3/§4 is read from the pinned
AnkiDroid sources (`FlashCardsContract.kt`, `CardContentProvider.kt`, `libanki/Card.kt`,
`libanki/Note.kt`, `libanki/TemplateManager.kt`, `Flag.kt` at tag `v2.24.1`); nothing is assumed.

---

## 1. Mission and scope

Turn the GATE 06 scheduled card identity (`AnkiCardRef` / `AnkiScheduledCard`) into a complete,
**backend-neutral** `AnkiRenderedCard` with three separated content channels:

| Channel | Fields | Consumer (future) | Rule |
|---|---|---|---|
| **visual** | `questionHtml`, `answerHtml` | WebView (GATE 08) | backend's rendered HTML, preserved verbatim |
| **speech** | `questionText`, `answerText` | TTS | backend's simplified text; HTML never speaks (INV-ANKI-CARD-06) |
| **evaluation** | `pureAnswerText` → fallback `answerText` | AI evaluator | never raw HTML (INV-ANKI-CARD-07) |

**In scope:** read-only hydration of one known card identity; scheduled↔hydrated identity
verification; explicit projection; strict-identity / lenient-metadata mapping; empty-vs-missing
semantics; typed failures; turn-scoped single-flight hydration; metadata-only diagnostics; the
capability matrix move; fake-backend fixtures and the full JVM test matrix.

**Out of scope (by decree):** WebView rendering, media resolution/preview, TTS, STT, AI
evaluation, rating commit, answer reveal, `StudySessionMachine` integration. Rating commit stays
GATE 11; nothing here pretends otherwise.

---

## 2. Pinned card contract (verified against v2.24.1)

### 2.1 Endpoints

```
content://<authority>/cards/<cardId>              (stable card id)
content://<authority>/notes/<noteId>/cards/<ord>  (GATE 06's identity shape: note + ordinal)
```

The card-id URI is preferred when the id is known; the note+ord URI is the public API's supported
alternative and is exactly what a scheduled ref (`cardId = null`) can address. Never a
question-text search, never a deck-name search (INV-ANKI-CARD-12).

### 2.2 Columns — consumed / deliberately unused

Consumed via `CARD_PROJECTION` (20 explicit columns, pinned in `AnkiDroidContractTest` — never
`SELECT *`, INV-ANKI-CARD-17):

| Column | Type (as transported) | Use in `AnkiRenderedCard` |
|---|---|---|
| `_id` | `long` | `ref.cardId` (canonical decimal; null when unreadable + token) |
| `note_id` | `long` | `ref.noteId` / `noteRef` |
| `ord` | `int` | `ref.cardOrd` (meaningful only with its note) |
| `card_name` | `String` | `metadata.templateName` — **display name, never identity** |
| `deck_id` | `long` | `deckRef` (current deck) |
| `original_deck_id` | `long` | `metadata.originalDeckRef` (filtered-deck home; `0` = none → `null`) |
| `question` | `String` | `questionHtml` (visual) |
| `answer` | `String` | `answerHtml` (visual; includes the question via `{{FrontSide}}` + `<hr id=answer>`) |
| `question_simple` | `String` | `questionText` (speech) |
| `answer_simple` | `String` | `answerText` (speech) |
| `answer_pure` | `String` | `pureAnswerText` (evaluation) |
| `reps`, `lapses`, `interval` | `int` | `scheduling.reps/lapses/intervalDays` (stored facts) |
| `type` | `int` | queue-state fallback (0 new / 1 learning / 2 review / 3 relearning) |
| `queue` | `int` | queue-state primary (−3/−2 buried, −1 suspended, 0–3 active) |
| `fsrs_stability`, `fsrs_difficulty`, `fsrs_desired_retention` | `double` | `scheduling.fsrs` (informational) |
| `last_review_time_secs` | `long` | `scheduling.lastReviewEpochSeconds` |

Deliberately **not** projected (documented in the contract KDoc): `due`, `original_due`, `left`,
`sm2_factor`, `custom_data`, `original_position`, `fsrs_decay` — scheduler internals or
undecoded payloads this build does not consume.

**There is no flags column** in the card contract at v2.24.1. `Card.flags` exists only inside
AnkiDroid (`libanki`), app-private. `AnkiRenderedCard.flag` is therefore always `null` on this
backend — never fabricated (INV-ANKI-CARD-21).

### 2.3 Rendering semantics (verified)

- `QUESTION` = `card.render_output()` question side, sound tags restored, no `<style>`.
- `ANSWER` = rendered answer, question included via `{{FrontSide}}`, separator `<hr id=answer>`.
- `QUESTION_SIMPLE` / `ANSWER_SIMPLE` = `TemplateManager` plain-text view with **raw template
  text** (field references are literal) and sound-tag restoration skipped. They are **not**
  ordinary plain text and must never be treated as "safe to auto-speak" without the GATE 10 flow.
- `ANSWER_PURE` = `answerText` after the first `<hr id=answer>`; without the marker the whole
  text is returned unchanged. The evaluation channel therefore depends on the marker only inside
  AnkiDroid — Study-Agent never splits on it.

### 2.4 Failure semantics (verified)

- Missing card: `BackendNotFoundException` (card id path) or
  `IllegalArgumentException("Card with ord $ord does not exist for note $noteId")` (note path);
  an empty cursor also means "gone". All map to **`AnkiError.CardNotFound`** — matched by the
  documented `ENTITY_NOT_FOUND` signatures **before** the generic contract classifier
  (`AnkiDroidErrors.ENTITY_NOT_FOUND`, tested in `AnkiDroidCardGatewayTest`).
- Unknown projected column: `UnsupportedOperationException` (contract mismatch family).
- Invalid template: `IllegalArgumentException("Card is using an invalid template")` **even when
  `card_name` is unprojected** — the name is computed unconditionally. That is provider-content
  corruption → `MalformedResponse` family, never a crash and never a blank card.

---

## 3. The normalized model (`core/anki`)

`AnkiRenderedCard` (GATE 03 shape, GATE 07 extension): five content fields with **nullability as
meaning** — `null` = the backend could not supply it, `""` = a legitimately empty rendering
(INV-ANKI-CARD-13). A question representation (HTML or text) is required by construction; the
mapper reports `MalformedResponse(card_question_content_missing)` instead of ever building a
blank card. A question that exists only as HTML is a **visual-only** card +
`card_speech_text_unavailable` — there is no HTML-stripping fallback (INV-ANKI-CARD-16).

Extensions this gate added — each optional, each with exactly one home:

| Field | Home | Rule |
|---|---|---|
| `metadata.templateName` | card surface | display name, never identity |
| `metadata.queueState` | card surface | `NEW/LEARNING/REVIEW/RELEARNING/SUSPENDED/BURIED/UNKNOWN`; raw queue/type ints never leave the layer; unknown codes → `UNKNOWN` + token, never guessed |
| `metadata.originalDeckRef` | card surface | filtered-deck home deck (`0` → `null`); current deck stays `deckRef` |
| `flag: AnkiFlag?` | card surface | `null` = backend said nothing; `UNKNOWN` ≠ `NONE` |
| `scheduling.reps/lapses/intervalDays/lastReviewEpochSeconds/fsrs` | card surface | stored facts (INV-ANKI-CARD-24) |
| `degradations` | card surface | content-free tokens (INV-ANKI-CARD-32) |
| `evaluationAnswerText` | derived | the documented `pureAnswerText ?: answerText` fallback |

GATE 06's `AnkiScheduledCard.scheduling` keeps its own disjoint fields (labels,
`nextReviewTimes`) and **survives hydration** on `AnkiReviewTurnContent.Rendered(card,
scheduledCard)` — rating buttons and interval labels are never erased by a content read
(INV-ANKI-CARD-23), and the two `AnkiSchedulingInfo` instances never merge into one ambiguous
blob (INV-ANKI-CARD-24).

## 4. Hydration identity and association (`AnkiCardHydration`)

**One-way confirmation** (STEP 53/§54, INV-ANKI-CARD-02/03): every identity component the
scheduled ref *knows* (backend, collection key — strict equality; card id, note id, ordinal —
confirmed when present) must be confirmed by the hydrated ref. Enrichment (a learned card id) is
fine; an unconfirmed or contradicted claim is `StaleCardReference(card_identity_mismatch)` and
the content is discarded — never attached.

`attach(turn, card)` preserves `turnId` and `scheduledCard` (INV-ANKI-CARD-22/23); it composes:

- media = scheduled references ∪ hydrated references, first-seen order, deduplicated by
  kind+name; entries stay unresolved references (INV-ANKI-CARD-20);
- deck difference current≠scheduled → `card_deck_moved` token — unless
  `metadata.originalDeckRef == scheduledDeck` (filtered-deck home explains the move,
  INV-ANKI-CARD-25). Neither deck fact is ever rewritten.

A late result from a previous turn cannot land on a different presentation: `attach` verifies
against the turn it is attached to (INV-ANKI-CARD-30).

## 5. Lookup, concurrency and caching

- `AnkiDroidBackend.hydrateCardContent(ref)` is **turn-agnostic** (the same card is the same
  content regardless of which turn asks) and **read-only** (INV-ANKI-CARD-10).
- **Single-flight:** a hydration mutex with memo re-check collapses duplicate and concurrent
  consumers of one card into one provider read (STEP 79/§80).
- **Turn-scoped memo:** one slot keyed by `stableKey`. It is a presentation cache, never
  collection truth (INV-ANKI-CARD-26): it is dropped when a new turn is presented and when a
  session ends (STEP 83/§84), so a card edited in AnkiDroid hydrates to its **latest** content
  (INV-ANKI-CARD-27) and nothing is persisted across process death (STEP 85 — rehydrate from a
  logical ref).
- Cancellation propagates as `CancellationException`, never as a domain failure
  (INV-ANKI-CARD-29), tested at gateway, fake backend and real backend levels.

## 6. Error mapping

| Situation | `AnkiError` |
|---|---|
| provider signatures `BackendNotFoundException` / `does not exist for note` / empty cursor | `CardNotFound(card)` |
| row does not confirm the requested identity | `StaleCardReference(card, "card_identity_mismatch")` |
| identity columns missing/unreadable, no question representation, unusable rows | `MalformedResponse(detail=<content-free token>)` |
| foreign backend ref | `InvalidRequest("card_ref_foreign_backend")` |
| permission denied / collection loss / timeout / contract mismatch | pre-existing GATE 02/04 families (`PermissionRequired`, `CollectionUnavailable`, `QueryFailure`, `UnsupportedAction`, `UnsupportedApi`, `ProviderUnavailable`) |
| capability absent (`renderedCards = false`) | `UnsupportedAction("renderedCards")` |
| mapped garbage in optional fields | **not an error** — `null`/`UNKNOWN` + degradation token (INV-ANKI-CARD-32) |

Degradation tokens (content-free): `card_id_unreadable`, `card_note_identity_unreadable`,
`card_speech_text_unavailable`, `card_deck_id_unreadable`, `card_queue_state_unmapped`,
`card_deck_moved`.

## 7. Diagnostics (metadata only)

Events: `ANKI_CARD_HYDRATION_STARTED` / `SUCCEEDED` / `FAILED`, `ANKI_CARD_CONTENT_DEGRADED`.
Payloads are identifiers, counts and **lengths** only — never a question, answer, HTML, note
field, template name or filename (INV-ANKI-CARD-31, STEP 88/89). `AnkiDroidCardQueryDiagnostics`
(last status, ids, ord, channel lengths, degradation tokens, durations, `providerQueryCount`)
feeds the diagnostics surface the same way `AnkiDroidReviewQueryDiagnostics` did in GATE 06.

## 8. Capability matrix (STEP 100) — and one truth fix

After GATE 07, at Ready + supported spec: `renderedCards = true` (identity-verified, JVM-tested
mapping). Unchanged: `review = false` (rating commit is GATE 11), `media = false` (GATE 09),
`deckCounts = false` (unverified), `flags = false`.

**Truth fix:** the API capability report previously claimed `flags = SUPPORTED`. The pinned
card contract exposes **no flags column at all** (verified, §2.2), so the claim was a capability
lie of exactly the kind GATE 01 §16 forbids. `apiCapabilitiesForSpec` now reports
`flags = UNSUPPORTED` and the matrix row reads `API_UNSUPPORTED`; it flips to `SUPPORTED` only
when a future contract version verifiably exposes flags.

## 9. Tests

| Suite | Covers |
|---|---|
| `AnkiDroidCardMapperTest` | strict identity (missing/unreadable/orphan-ord/never-invented), verbatim channels (HTML, RTL Arabic), empty ≠ missing, visual-only degradations, queue/type mapping incl. unknown→`UNKNOWN`, lenient metadata, transported-number parsing ("3", "3.0", garbage), flag absence |
| `AnkiDroidCardGatewayTest` | endpoint + **pinned projection** (`projectionLog`), note+ord path, foreign ref refused pre-query, empty→`CardNotFound`, `ENTITY_NOT_FOUND` signatures→`CardNotFound`, identity mismatch→`StaleCardReference`, enrichment accepted, malformed rows→`MalformedResponse`, permission/collection typed errors, cancellation propagation, serialization under concurrency, metadata-only diagnostics, read-only conversation |
| `AnkiCardHydrationTest` | identity confirmation/enrichment/contradiction, collection strictness, media merge order + dedup, deck-move token + filtered-deck explanation, attach idempotence + turn preservation, question-required invariant, empty/missing, evaluation fallback, flag code mapping (unknown ≠ NONE) |
| `AnkiCardFlowTest` | fixture matrix (HTML, plain, cloze, Arabic, mixed, large, metadata-poor) byte-for-byte; STEP 44 deleted→typed `CardNotFound`; STEP 45 edited→latest content; STEP 49 late hydration never crosses turns; STEP 80/82 single-flight + memo; STEP 83 memo dies with the turn; idempotence; foreign ref; capability truth; typed passthrough; cancellation; and the same single-flight/memo/lifecycle/failure rules on the **real** `AnkiDroidBackend` over `FakeAnkiDroidCardGateway` |
| `AnkiDroidContractTest` (extended) | `CARD_PROJECTION` pin (20 columns, no wildcard), addressing paths, queue/type code values |
| `FakeAnkiBackendTest` (updated) | scheduled-first flow, fixture-detached facts via hydration, pre-existing noteId-assertion bug fixed |
| `AnkiDomainModelTest` (updated) | `Rendered(card, scheduledCard)` composition, `scheduledCard`/`ratingOptions` accessors |
| `AnkiDroidIntegrationIsolationTest` (unchanged, passing by scan) | no writes, no AnkiDroid internals, no scheduler reimplementation, layer-import allowlist, no calendar arithmetic — including the new card files |
| `AnkiDroidReviewSessionTest`, `AnkiDroidBackendTest`, `AnkiScheduledReviewContractTest`, `AnkiDroidCapabilityProbeTest`, `AnkiDroidIntegrationStateTest`, instrumented `AnkiDroidReviewInstrumentedTest` | updated to the `scheduledCard` accessor + `cardGateway` dependency + capability truth |

**Not run in this gate: the real-AnkiDroid instrumented suite and all Gradle/JVM execution.** The
sandbox has no JDK/Android SDK and no route to the toolchain (GATE 00/04/05/06
`BLOCKED_BY_ENVIRONMENT`), and GitHub Actions is **billing-locked** ("account is locked due to a
billing issue"), so CI cannot execute either. See §10.

## 10. Validation results (honest)

| Check | Result |
|---|---|
| `python3 server/test_contract.py` | **PASS** — "All unit contract tests passed!" |
| `python3 -m pytest server/test_tts_mock_provider.py server/test_tts_contract.py` | **PASS** — 50 passed (with `websockets==12.0`: the unversioned install pulls websockets ≥14, whose asyncio client dropped `extra_headers` and fails these pre-existing tests at *runtime API drift*, not in any GATE 07 code — `server/` is untouched this gate) |
| Static isolation scans (mirror of `AnkiDroidIntegrationIsolationTest` / `AnkiDomainIsolationTest` rules over all `core/anki` + `data/anki` sources, comments stripped) | **PASS** — clean: no write tokens, no AnkiDroid internals, no scheduler reimplementation, imports allowlisted, no calendar arithmetic, no `Throwable`/`Exception`/`Result<` in the domain, no endpoint vocabulary outside the layer |
| `./gradlew --stop && clean && testDebugUnitTest && lint && assembleDebug && assembleRelease` | **BLOCKED_BY_ENVIRONMENT** — no JDK/SDK in sandbox ("location of your Java installation"); dependency mirrors unreachable (same as GATE 00/04/05/06) |
| CI (`android-ci.yml`) | **BLOCKED** — GitHub account billing-locked ("account is locked due to a billing issue"); jobs abort before execution |
| Real-device / instrumented suite | **NOT RUN** — no device in this environment |

No unavailable test is reported as PASS.

## 11. Invariants — INV-ANKI-CARD-01 … 32 (PART IX verdicts)

| ID | Contract | Verdict |
|---|---|---|
| **INV-ANKI-CARD-01** | A scheduled card becomes complete content only via identity-verified hydration — never fabricated. | **HOLD** (model + `AnkiCardHydration` + flow tests) |
| **INV-ANKI-CARD-02** | A hydrated card is attached only after every scheduled identity claim is confirmed (one-way). | **HOLD** (`identityMatches`, gateway verify, `AnkiCardHydrationTest`) |
| **INV-ANKI-CARD-03** | Collection identity is strict equality; unknown is never a wildcard. | **HOLD** (`identityMatches`, tests) |
| **INV-ANKI-CARD-04** | Ids are parsed from what the provider reported; a missing id is never invented. | **HOLD** (`parsePositiveLong`, mapper tests) |
| **INV-ANKI-CARD-05** | Visual / speech / evaluation stay three separate channels. | **HOLD** (model, fixture matrix tests) |
| **INV-ANKI-CARD-06** | Speech consumes only `questionText`/`answerText`; HTML never reaches TTS. | **HOLD** (model KDoc + channel tests; TTS wiring is out of scope) |
| **INV-ANKI-CARD-07** | Evaluation consumes `pureAnswerText` → `answerText`; never raw HTML. | **HOLD** (`evaluationAnswerText`, tests) |
| **INV-ANKI-CARD-08** | Rendered content is preserved verbatim — no template expansion, no sanitizing, no rewriting. | **HOLD** (mapper copy semantics + byte-for-byte tests) |
| **INV-ANKI-CARD-09** | Template/cloze semantics remain the backend's; Study-Agent never interprets them. | **HOLD** (no template engine exists; cloze fixture asserts literal `{{c1::…}}`) |
| **INV-ANKI-CARD-10** | Card-content hydration is strictly read-only. | **HOLD** (isolation scan: no write tokens; gateway test asserts query-only conversation) |
| **INV-ANKI-CARD-11** | No Android/provider types escape the gateway. | **HOLD** (layer-import allowlist scan; JVM-compilable mapper/gateway) |
| **INV-ANKI-CARD-12** | Hydration is addressed only by identity (card id or note + ordinal). | **HOLD** (`pathFor` — exactly two paths, tested) |
| **INV-ANKI-CARD-13** | Empty and missing content are distinct facts. | **HOLD** (nullability semantics + tests) |
| **INV-ANKI-CARD-14** | Optional metadata is genuinely optional. | **HOLD** (lenient mapping + `metadataPoorCard` fixture) |
| **INV-ANKI-CARD-15** | Identity maps strictly; content and metadata map leniently. | **HOLD** (mapper split strict/lenient + tests) |
| **INV-ANKI-CARD-16** | Missing simple text is never replaced by HTML-derived pseudo-text. | **HOLD** (visual-only + token, no stripping fallback, tested) |
| **INV-ANKI-CARD-17** | Card reads use an explicit pinned projection — never `SELECT *`. | **HOLD** (`CARD_PROJECTION` + contract test + gateway `projectionLog` test) |
| **INV-ANKI-CARD-18** | Scheduling metadata is informational display data only. | **HOLD** (model KDoc; nothing consumes it for logic) |
| **INV-ANKI-CARD-19** | FSRS values are informational; Study-Agent never computes intervals from them. | **HOLD** (mapped as `AnkiFsrsInfo` display facts; no interval code exists) |
| **INV-ANKI-CARD-20** | Media entries stay unresolved references. | **HOLD** (`mergeMedia` never resolves; no I/O in card path; GATE 09 will resolve) |
| **INV-ANKI-CARD-21** | A flag is reported only as the backend reported it; unknown codes → `UNKNOWN`, never `NONE`. | **HOLD** (`AnkiFlag.fromBackendCode`; flag `null` on AnkiDroid (no column); tests) |
| **INV-ANKI-CARD-22** | Hydration never changes the turn identity. | **HOLD** (`attach` preserves `turnId`, tested) |
| **INV-ANKI-CARD-23** | Hydration never erases scheduler rating options / next-review labels. | **HOLD** (`Rendered(card, scheduledCard)` + accessors, tested) |
| **INV-ANKI-CARD-24** | Each scheduling field has exactly one authoritative surface (labels vs stored facts). | **HOLD** (split model KDoc + mapper stores facts only) |
| **INV-ANKI-CARD-25** | A deck move is reported (`card_deck_moved`), never overwritten; a filtered-deck home explains it. | **HOLD** (`compose` + tests) |
| **INV-ANKI-CARD-26** | The rendered-card memo is a presentation cache, never collection truth. | **HOLD** (1-slot memo, invalidated at present/endReview, tested) |
| **INV-ANKI-CARD-27** | A card edited in AnkiDroid hydrates to its latest content. | **HOLD** (memo invalidation + `replaceCard` flow test) |
| **INV-ANKI-CARD-28** | Content is copied without Unicode, whitespace or entity normalization. | **HOLD** (Arabic/RTL + mixed fixtures byte-for-byte) |
| **INV-ANKI-CARD-29** | Cancellation is never converted into a domain failure. | **HOLD** (gateway, fake, real backend tests) |
| **INV-ANKI-CARD-30** | A late hydration result can never cross turns. | **HOLD** (`attach` verification + flow test) |
| **INV-ANKI-CARD-31** | Hydration diagnostics are metadata-only. | **HOLD** (events + `AnkiDroidCardQueryDiagnostics`; content-free assertion in tests) |
| **INV-ANKI-CARD-32** | Degrading optional mapping records a content-free token instead of failing or inventing. | **HOLD** (degradation token set + tests) |

All 32 invariants are **implemented and JVM-tested** except where noted; none is claimed
device-verified (§10).

## 12. Deliberate deferrals

- **GATE 08 (rendering):** `questionHtml`/`answerHtml` are delivered raw; WebView sanitizing,
  CSP, `{{FrontSide}}` display quirks and dark-mode CSS are GATE 08's problem.
- **GATE 09 (media):** references are merged and preserved; nothing is opened, probed or
  resolved. `[sound:…]` handling and image loading are GATE 09.
- **GATE 10 (`StudySessionMachine`):** `attach` exists as the verified association primitive;
  installing content into UI/voice state is the machine's decision.
- **GATE 11 (writes):** rating commit, bury/suspend/flag edits. `commitRating` still refuses
  truthfully.
- **Flags:** modelled and mapped (`AnkiFlag`) but unobtainable from the pinned provider; reading
  flags needs a future contract with a flags column.
- **Device verification of rendering semantics** (§2.3) on a real AnkiDroid — the instrumented
  suite covers the query shape; rendered-output appearance is GATE 08's device matrix.

## 13. GATE 08 readiness

**YES — GATE 08 (card rendering) may proceed**, on the strength of: the normalized
`AnkiRenderedCard` with separated channels and honest nullability; identity-verified,
single-flight, turn-scoped hydration with typed failures; verbatim content guarantees
(INV-ANKI-CARD-08/28) with visual-only cards explicitly flagged; degradation tokens a renderer
can surface; and `renderedCards = true` with every unsupported affordance (`media`, `flags`)
still honestly `false`. Conditions carried forward: Gradle/CI/device verification remains
`BLOCKED_BY_ENVIRONMENT`/billing-locked and must be executed the moment the environment allows;
GATE 08 must not resolve media or interpret templates (GATE 09 / backend authority).
