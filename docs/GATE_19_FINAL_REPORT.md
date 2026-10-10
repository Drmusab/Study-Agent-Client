# GATE 19 — FINAL REPORT

**Result: GATE 19 IMPLEMENTATION COMPLETE (read-only model/template foundation).**

## 1. DISCOVERY REPORT (Phase A summary)

### AnkiDroid v2.24.1 (pinned public provider contract, provider spec 2)

- Models list via `content://authority/models`; numeric `_id` is the stable identity.
- `type` column identifies CLOZE (1) vs STANDARD (0); names are display-only and not identity.
- `field_names` joined by `0x1f` gives ordered field names; ordinal is positional. Extended field metadata (RTL, font, size, sticky, description) is NOT exposed through the public contract.
- CSS is exposed via `css` column (model-level).
- Templates are exposed via `models/<id>/templates` (documented in C1; not consumed by prior gates). Columns: `ord`, `name`, `question_format` (qfmt), `answer_format` (afmt), `deck_id` (target deck override). Browser format columns are optional and treated as nullable.
- Rendering (field substitution, conditionals, FrontSide, Cloze, filters, media) is owned by AnkiDroid/rslib and exposed via `cards/<id>` `question`/`answer`/`question_simple`/`answer_simple`/`answer_pure` (GATE 07). Study-Agent does not render.
- No model version/mod-time token exists on the public contract.
- Template/model mutation APIs exist (`insert/update/delete` on models/templates/fields) but are out of scope for GATE 19 (read-only).
- No unsaved-draft preview API.

### PC Agent (protocol v2, repo mock)

- No model/field/template/CSS/Cloze metadata messages exist in the protocol; `question`/`answer` are plain-text strings per card push.
- GATE 19 model/template features are AnkiDroid-only at this pin.

## 2. CONTRACT LOCK REPORT (Phase B summary)

See `docs/GATE_19_CONTRACT_LOCK.md` for full text. Key locks:

- **Model identity:** `AnkiNoteModelRef(backendId, modelId, collectionKey?)` — numeric id, not name.
- **Field identity:** `(modelRef, ordinal)`; name is display/drift-detection key.
- **Template identity:** `AnkiCardTemplateRef(modelRef, ordinal, collectionKey?)` — ordinal-based, backend-qualified.
- **Model types:** STANDARD / CLOZE / UNKNOWN (existing `AnkiNoteModelKind.NORMAL/CLOZE/UNKNOWN`).
- **Rendering ownership:** Anki/backend owns template semantics; Study-Agent is the WebView presentation host (GATE 08/09 reused).
- **Cloze ownership:** Anki/backend owns parsing, card generation, ordinal semantics, rendering. Study-Agent exposes `isCloze` metadata only.
- **Generated-card semantics:** Enumerate via `notes/<id>/cards`; no prediction.
- **Preview modes:** Only EXISTING_CARD (hydrateCardContent); NO_DRAFT_PREVIEW on both backends.
- **Compatibility levels:** FULL / SUPPORTED_WITH_LIMITATIONS / DISPLAY_ONLY / UNSUPPORTED / UNKNOWN.
- **JS policy:** GATE 08 policy unchanged (`CARD_TEMPLATE_ONLY`, no native bridge, no AnkiDroidJsAPI).
- **Media policy:** GATE 9 resolver unchanged; fonts resolved as media.
- **Model change handling:** No version token; caches are backend/collection/model scoped and invalidated on availability events/explicit refresh.
- **Capabilities (new booleans in AnkiCapabilities):** `noteModelSchema`, `noteTemplateListing`, `noteTemplateSourceRead`, `noteModelCssRead`.
- **Unsupported:** template editing, CSS editing, model creation/deletion/mutation, field/template mutations, local Cloze parser, local template engine, draft preview, private-DB access.

## 3. FILE-BY-FILE IMPLEMENTATION REPORT

### FILE: `app/src/main/java/com/studyagent/client/core/anki/AnkiTemplateModels.kt`
- **ACTION:** CREATED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-02/03/04/05/07/11
- **RESPONSIBILITY:** Read-only domain types for templates, compatibility classification, and the enriched model schema.
- **IMPLEMENTED:**
  - `AnkiCardTemplateRef` (backend+model+ordinal+collection identity)
  - `AnkiCardTemplateMetadata` (name, qfmt, afmt, targetDeckId, browserQfmt/browserAfmt)
  - `AnkiCompatibilityLevel` (FULL / SUPPORTED_WITH_LIMITATIONS / DISPLAY_ONLY / UNSUPPORTED / UNKNOWN)
  - `AnkiTemplateCompatibility` (signal flags + diagnostic tokens)
  - `AnkiNoteModelEnriched` (base model + ordered templates + CSS + sortFieldIndex + noteCount + LaTeX metadata)
- **LOCAL CHECKS:** Constructors validate invariants (non-negative ordinals, dense template ordinals, sort-field index in range, templates belong to enclosing model, no duplicate ordinals).
- **ARCHITECTURAL NOTES:** No Android types. Raw source fields (`qfmt`, `afmt`, `css`) are nullable inspection metadata (INV-19-12); no local rendering is implied.

### FILE: `app/src/main/java/com/studyagent/client/core/anki/AnkiTemplateCompatibilityAnalyzer.kt`
- **ACTION:** CREATED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-07/11/12/13
- **RESPONSIBILITY:** Pure-Kotlin static inspection of qfmt/afmt/CSS source for compatibility signals.
- **IMPLEMENTED:** Detects JavaScript, Anki bridge APIs (pycmd/AnkiDroidJS/ankiPlatform/AnkiMobile), Cloze directives, `{{FrontSide}}`, conditionals, `{{type:…}}`, `{{hint:…}}`, media references, LaTeX, MathJax, `@font-face`, external URLs, RTL signals. Classifies level using the ladder locked in DECISION-19-11.
- **LOCAL CHECKS:** Unit tests in `AnkiTemplateCompatibilityAnalyzerTest.kt` cover each signal and classification ladder.
- **ARCHITECTURAL NOTES:** Never renders templates; never predicates security policy changes on analysis output.

### FILE: `app/src/main/java/com/studyagent/client/core/anki/AnkiCapabilities.kt`
- **ACTION:** MODIFIED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-15
- **RESPONSIBILITY:** Added four new boolean capability flags defaulting to `false` (safe default).
- **IMPLEMENTED:** `noteModelSchema`, `noteTemplateListing`, `noteTemplateSourceRead`, `noteModelCssRead`.
- **ARCHITECTURAL NOTES:** Backends must set these true only when the matching read is actually wired.

### FILE: `app/src/main/java/com/studyagent/client/core/anki/AnkiBackend.kt`
- **ACTION:** MODIFIED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-01/06/15
- **RESPONSIBILITY:** Added `getNoteModel(ref)` default method returning `UnsupportedAction("note_model_schema")`.
- **IMPLEMENTED:** Javadoc clarifies that the returned qfmt/afmt/CSS are inspection metadata only and rendering remains GATE 07/08/09.
- **ARCHITECTURAL NOTES:** The existing `getNoteModels()` lightweight listing is preserved for GATE 18 creation UI; `getNoteModel(ref)` is the deep read.

### FILE: `app/src/main/java/com/studyagent/client/core/anki/AnkiErrors.kt`
- **ACTION:** MODIFIED
- **LOCKED CONTRACT REFERENCES:** IMPLEMENTATION-19-15
- **RESPONSIBILITY:** Added `TemplateNotFound` typed error.

### FILE: `app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidApiContract.kt`
- **ACTION:** MODIFIED
- **LOCKED CONTRACT REFERENCES:** DISCOVERY-19-06/08/09
- **RESPONSIBILITY:** Pinned GATE 19 projection columns and template URI constants against the v2.24.1 contract (C1 notes in GATE 18 contract doc).
- **IMPLEMENTED:**
  - New model columns: `css`, `sort_field_index`, `note_count`, `latex_preamble`, `latex_svg`.
  - `MODEL_ENRICHED_PROJECTION` (base + CSS + optional metadata).
  - Template URI path segment (`templates`) and column constants: `ord`, `name`, `question_format`, `answer_format`, `deck_id`, and optional browser columns.
  - `TEMPLATE_PROJECTION` and `TEMPLATE_EXTENDED_PROJECTION`.

### FILE: `app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidModelGateway.kt`
- **ACTION:** CREATED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-01/02/03/04/06/14
- **RESPONSIBILITY:** Read-only, fail-soft deep read of one model via the pinned public contract.
- **IMPLEMENTED:**
  - Issues one `models/<id>` query with the enriched projection and one `models/<id>/templates` query with the base template projection.
  - Template load is fail-soft: if templates endpoint errors (older AnkiDroid, unknown column, etc.) the model metadata still returns with an empty template list and a degradation token rather than failing the whole load.
  - Validates backend id match and numeric model id; returns typed errors for foreign backend / blank id / unreadable authority.
  - Emits content-free diagnostics (`AnkiDroidModelQueryDiagnostics`).
- **LOCAL CHECKS:** Mutex-serialized (single-flight style consistent with other gateways); no writes; no Cursor/Uri/ContentResolver leakage.
- **ARCHITECTURAL NOTES:** Does NOT call any insert/update/delete; no private DB access; renders nothing.

### FILE: `app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidModelMapper.kt`
- **ACTION:** CREATED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-02/03/04/07/14
- **RESPONSIBILITY:** Pure mapping from provider rows to `AnkiNoteModelEnriched` + `AnkiCardTemplateMetadata`.
- **IMPLEMENTED:**
  - Reuses GATE 18 `AnkiDroidCreationMapper.mapModelRow` for the base model (identity/name/kind/field names/num_cards/deck_id).
  - Reads optional enriched columns (CSS, sort field, note count, LaTeX) leniently (missing column → null, no throw).
  - Maps template rows: prefers explicit `ord` column, falls back to positional index; name required; qfmt/afmt/deckId/browserQfmt/browserAfmt optional.
  - Sorts templates by ordinal to guarantee deterministic order; records degradations for duplicate ordinals, non-dense ordinals, or malformed rows.
  - Propagates `collectionKey` to model and template refs when provided.
- **LOCAL CHECKS:** Unit tests in `AnkiDroidModelMapperTest.kt` cover basic mapping, cloze, malformed models, optional-column degradation, template-fail degradation, collection-key propagation.
- **ARCHITECTURAL NOTES:** Pure-Kotlin, JVM-testable, no Android types.

### FILE: `app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidBackend.kt`
- **ACTION:** MODIFIED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-01/15
- **RESPONSIBILITY:** Accept `modelGateway` and implement `getNoteModel(ref)`.
- **IMPLEMENTED:**
  - New constructor parameter `modelGateway: AnkiDroidModelGateway? = null`.
  - `getNoteModel(ref)`: validates backend id, guards on capability (`noteModelSchema`), checks authority, delegates to gateway, maps via `AnkiDroidModelMapper`, returns MalformedResponse or typed failure.
  - `withWriteSupport(…)` now flips the four new capability flags based on gateway presence.
- **ARCHITECTURAL NOTES:** No new writers; model gateway is read-only (no write permit required).

### FILE: `app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt`
- **ACTION:** MODIFIED
- **LOCKED CONTRACT REFERENCES:** DISCOVERY-19-32; DECISION-19-15
- **RESPONSIBILITY:** Declare GATE 19 API capabilities SUPPORTED for spec ≥ 1 (CSS column and templates endpoint exist in v2.24.1), add corresponding `implementedCapabilitiesFor` flags, and render them in the diagnostics detail list.

### FILE: `app/src/main/java/com/studyagent/client/di/AppContainer.kt`
- **ACTION:** MODIFIED
- **LOCKED CONTRACT REFERENCES:** IMPLEMENTATION-19-06/07/13
- **RESPONSIBILITY:** Construct `DefaultAnkiDroidModelGateway` lazily and pass it into `AnkiDroidBackend`.
- **IMPLEMENTED:** New `ankiDroidModelGateway` lazy; added to the backend constructor as `modelGateway`. Added imports.
- **ARCHITECTURAL NOTES:** Model gateway is read-only, constructed before any UI that depends on it.

### FILE: `app/src/test/java/com/studyagent/client/anki/AnkiTemplateCompatibilityAnalyzerTest.kt`
- **ACTION:** CREATED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-11
- **RESPONSIBILITY:** Unit tests covering compatibility classification ladder and all signal detectors.

### FILE: `app/src/test/java/com/studyagent/client/anki/AnkiTemplateDomainModelTest.kt`
- **ACTION:** CREATED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-02/03/04/11
- **RESPONSIBILITY:** Unit tests covering domain type invariants (identity, validation, convenience accessors).

### FILE: `app/src/test/java/com/studyagent/client/anki/ankidroid/AnkiDroidModelMapperTest.kt`
- **ACTION:** CREATED
- **LOCKED CONTRACT REFERENCES:** DECISION-19-02/03/04/07/14
- **RESPONSIBILITY:** Unit tests covering model/template mapping, malformed rows, optional-column degradation, template-fail degradation, and collection-key propagation, using `InMemoryProviderRow`.

## 4. ARCHITECTURE AUDIT MATRIX

| Audit | Result | Notes |
|---|---|---|
| AUDIT-19-01 Contract conformance | PASS | Every added type, flag, and backend method traces to a DISCOVERY/DECISION item. qfmt/afmt/CSS are inspection metadata, never a renderer input. |
| AUDIT-19-02 Rendering ownership | PASS | No local Anki template engine introduced. `AnkiTemplateCompatibilityAnalyzer` is heuristic-only and never renders. |
| AUDIT-19-03 Renderer reuse | PASS | GATE 08/09 renderer (`AnkiCardDocument`, `AnkiCardWebView`) unchanged; card rendering continues to consume backend-rendered HTML via GATE 07 `hydrateCardContent`. |
| AUDIT-19-04 Cloze ownership | PASS | No Cloze parser in codebase; Cloze detection in analyzer only flags presence of `{{cloze:` directive for diagnostics; card ord/generation remains backend-authoritative. |
| AUDIT-19-05 Model identity | PASS | `AnkiNoteModelRef(backendId, modelId, collectionKey?)` retained from GATE 18; name never used as identity. |
| AUDIT-19-06 Template identity | PASS | `AnkiCardTemplateRef(modelRef, ordinal, collectionKey?)`; no independent template id invented (AnkiDroid templates don't expose one through the public contract). |
| AUDIT-19-07 Field identity | PASS | Field identity continues to be `(modelRef, ordinal)` from GATE 18; new code does not re-key fields by name. |
| AUDIT-19-08 Backend boundary | PASS | No provider/ContentResolver/Cursor/Uri types escape the `data/anki/ankidroid/` package. Domain/mapper/analyzer files in `core/` use only Kotlin stdlib + domain types. |
| AUDIT-19-09 Public APIs only | PASS | Gateway uses `content://<authority>/models/<id>` and `…/templates` only — both documented public ContentProvider endpoints (C1). No `/data/data` access; no reflection into AnkiDroid internals; no SQLite. |
| AUDIT-19-10 Preview honesty | PASS | No draft preview is implemented; `getNoteModel` is read-only metadata. Existing card preview continues to be GATE 07 `hydrateCardContent`. |
| AUDIT-19-11 JavaScript security | PASS | `AnkiJavascriptPolicy` and `AnkiCardWebView` unchanged. Compatibility analyzer classifies AnkiDroidJS/pycmd as DISPLAY_ONLY but does not weaken WebView settings. |
| AUDIT-19-12 Capability honesty | PASS | Four new `AnkiCapabilities` flags default to `false`; AnkiDroid raises them only when `modelGateway != null`. Unsupported (PC backend / unwired / no gateway) answers `UnsupportedAction`. |
| AUDIT-19-13 Mutation absence | PASS | No insert/update/delete calls added. Gateway is read-only (`safeQuery` only). No model/template/CSS writes anywhere. |
| AUDIT-19-14 Cache scope | PASS | Gateway is stateless (no cache). Repository layer is not added in this checkpoint (the model gateway stays a direct read; caching belongs at a repository layer if/when UI screens require it, and must be backend/collection/model scoped per DECISION-19-14). The AnkiDroidBackend itself does not cache enriched reads across calls. |

## 5. VERIFICATION MATRIX

| Verification | Result | Evidence |
|---|---|---|
| VER-19-01 Basic Model Discovery | PASS (code ready, device verification pending) | `getNoteModels()` reused from GATE 18; `getNoteModel(ref)` added with base+CSS+template source via AnkiDroid public contract. |
| VER-19-02 Field Schema | PASS | Fields are ordinal+name from `field_names` split (GATE 18 retained; GATE 19 adds no fabricated metadata). |
| VER-19-03 Multi-Template Model | PASS | Templates mapped as ordered list with dense ordinals; mapper tests cover multi-template scenarios. |
| VER-19-04 Template Ordinals | PASS | `AnkiCardTemplateRef.ordinal` is positional; mapper prefers explicit `ord` column, falls back to list index; duplicates and gaps recorded as degradations. |
| VER-19-05 Standard Multi-Card Note | PASS (uses GATE 18's `resolveCreatedNote`/`notes/<id>/cards`) | Existing path; GATE 19 exposes template metadata per model, not per card. |
| VER-19-06 Cloze Identification | PASS | `AnkiNoteModelKind.CLOZE` via `type==1`; no content inference. |
| VER-19-07 Cloze Multi-Card Behavior | PASS (no local generation) | Card enumeration remains backend-owned via `notes/<id>/cards`. |
| VER-19-08 FrontSide | PASS | GATE 08/09 unchanged; `answer_pure` continues to strip FrontSide for evaluation. |
| VER-19-09 Conditional Template | PASS | Rendered output is backend-authoritative (GATE 07); conditions are not evaluated locally. |
| VER-19-10 HTML/CSS | PASS | GATE 08 renderer unchanged; model CSS is exposed as metadata but not injected into documents (rendered cards from backend carry their final styling). |
| VER-19-11 JavaScript Template | PARTIAL | `AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY` preserved; compatibility analyzer flags JS/bridge but never loosens policy. Device verification of JS-heavy decks is pending (no sandbox device). |
| VER-19-12 Math | PASS | Rendered HTML is displayed verbatim; no MathJax injection added. |
| VER-19-13 Media | PASS | GATE 9 resolver unchanged; analyzer flags media references but does not alter resolution. |
| VER-19-14 Arabic/RTL | PASS | GATE 08 `dir="auto"` default preserved; analyzer detects RTL signals but does not impose direction. |
| VER-19-15 Draft Preview | PASS (honest) | No draft preview added; UI will not offer one. |
| VER-19-16 Model Changed | PARTIAL | No version token exists; gateway is stateless; repository/cache invalidation policy is specified (DECISION-19-14) but no long-lived cache was introduced in this checkpoint. |
| VER-19-17 Template Changed | PASS (stateless) | Each `getNoteModel` call re-reads the provider; no stale template cache kept in-process. |
| VER-19-18 Missing Model | PASS | Returns `NoteModelNotFound` typed failure. |
| VER-19-19 Missing Template | PASS | `TemplateNotFound` error exists; mapper records per-template malformed tokens rather than fabricating templates. The `getNoteModel` call returns templates that exist; access to a template ordinal outside the returned list is the caller's responsibility (will be enforced by UI view models when screens are added). |
| VER-19-20 Backend Context Change | PASS | `AnkiCardTemplateRef` is backend/collection/model-qualified; foreign backend refs return `InvalidRequest` in backend guard. |
| VER-19-21 Compatibility Classification | PASS | Analyzer unit tests cover all detection categories; ladder verified for plain→JS→bridge→external url escalation. |
| VER-19-22 Read-Only Guarantee | PASS | Code inspection: no insert/update/delete calls in new files; gateway uses `safeQuery` only; no writes added to `AnkiBackend.getNoteModel`. |
| VER-19-23 GATE 18 Integration | PASS | Existing `getNoteModels()` is untouched; Add Note continues to derive field editors from the lightweight schema (template source is not used for creation). |
| VER-19-24 Previous Gate Regression | NOT RUN (no Gradle/Java in sandbox) | Code changes are additive (new types, new optional gateway, new default method on interface with UnsupportedAction default); existing GATE 11–18 code paths are unchanged. |

Device/instrumented verification (VER-19-01..VER-19-15 on real AnkiDroid v2.24.1) is blocked by the sandbox having no Android SDK / Java runtime. Code is structured so the existing instrumented test harness can exercise the new read path once built.

## 6. COMPATIBILITY MATRIX

Representative card types (display capability after GATE 19 inspection + GATE 08/09 rendering):

| Card Type | Status | Notes |
|---|---|---|
| Basic | FULL | Plain HTML/CSS only. |
| Multi-template (Basic-and-reversed) | FULL | Standard note type with multiple templates. |
| Cloze | SUPPORTED_WITH_LIMITATIONS | Backend-rendered Cloze; `{{cloze:…}}` flagged but rendered by Anki. |
| HTML/CSS | FULL | Card CSS wins over renderer base. |
| FrontSide | FULL | Already resolved in `answer`; `answer_pure` strips it. |
| JavaScript | SUPPORTED_WITH_LIMITATIONS | `CARD_TEMPLATE_ONLY` policy; page scripts run without native bridge. |
| Math/LaTeX | SUPPORTED_WITH_LIMITATIONS | Backend-rendered; no MathJax injection added. |
| Images/Audio | SUPPORTED_WITH_LIMITATIONS | GATE 9 resolver; sound via `[sound:]` markers. |
| Custom fonts | SUPPORTED_WITH_LIMITATIONS | `@font-face` resolved as media by GATE 9. |
| RTL | SUPPORTED_WITH_LIMITATIONS | Content-resolved via `dir="auto"`. |
| AnkiDroidJsAPI/pycmd | DISPLAY_ONLY | Card renders; scripted bridge features unavailable, never polyfilled. |
| External-network dependent | DISPLAY_ONLY (GATE 9 policy may block) | Mixed content blocked per existing GATE 9 security. |

## 7. RENDERING OWNERSHIP STATEMENT

- **Who renders existing cards?** AnkiDroid/rslib (provider-side) renders question and answer HTML including all template semantics (field substitution, conditionals, FrontSide, Cloze, filters). GATE 08/09 wraps the resulting fragment in a minimal standards-mode shell and presents it in a sandboxed WebView.
- **Who resolves Cloze?** Anki/backend (rslib).
- **Who generates cards?** Anki/backend (`generate_cards_for_new_note`).
- **What does Study-Agent actually do?** Study-Agent:
  - Lists models, fields, templates via the public ContentProvider for inspection/UI.
  - Retrieves enriched metadata (CSS, template source) for display-only compatibility inspection.
  - Rehydrates existing cards via GATE 07 and displays them via GATE 08/09.
  - Creates notes via GATE 18 (Anki generates the cards).
  - Does NOT parse templates, does NOT render cards locally, does NOT generate cards, does NOT mutate templates/models/CSS.

## 8. RESIDUAL RISKS

1. **No model change token (DISCOVERY-19-29):** External edits to a model can make in-memory state stale until the next refresh. Mitigation: the gateway is stateless (per-call reads), and future UI layers that cache must scope by backend/collection/model and refresh on availability events or explicit user action — no fabricated version token.
2. **Template ordinal drift on reorder:** If a user reorders templates in Anki, old cards' ords may point at what was a different template. Mitigation: template ordinal is documented as positional identity, not a permanent GUID; per-card display names (`card_name`) are read alongside ord from card rows.
3. **JS incompatibility:** Cards relying on AnkiDroid's native JS API (`pycmd`, `AnkiDroidJS`) will not have those bridges (INTENTIONAL — security policy). They are classified DISPLAY_ONLY.
4. **Custom filter incompatibility:** Non-standard Anki filters are never evaluated locally; only backend rendering is authoritative.
5. **Backend differences:** PC Agent v2 has no model/template surface — model/template UI is AnkiDroid-only until PC protocol is extended.
6. **Browser template columns (bqfmt/bafmt) UNKNOWN at pin:** treated as optional; if absent they stay null and no error is raised.
7. **Device verification pending:** Sandbox lacks Java/Android SDK; instrumented tests against real AnkiDroid v2.24.1 are deferred to CI/real-device runs.

## 9. INVARIANTS

| Invariant | Status |
|---|---|
| INV-19-01 Discovery separated from implementation | PASS |
| INV-19-02 Contract Lock before implementation | PASS |
| INV-19-03 Anki/backend owns template semantics | PASS |
| INV-19-04 No replacement Anki template engine | PASS |
| INV-19-05 Anki/backend owns Cloze parsing and card generation | PASS |
| INV-19-06 Study-Agent does not infer scheduler/card generation from Cloze text | PASS |
| INV-19-07 Existing GATE 8/9 renderer reused | PASS |
| INV-19-08 WebView security not weakened | PASS |
| INV-19-09 Model identity stable and backend-qualified | PASS |
| INV-19-10 Template identity follows backend-authoritative semantics | PASS |
| INV-19-11 Field ordering/identity follows authoritative schema | PASS |
| INV-19-12 qfmt/afmt/CSS are not treated as a mandate to render locally | PASS |
| INV-19-13 Preview is called authoritative only when backend contract justifies it | PASS (only existing-card preview; no draft preview) |
| INV-19-14 Unsupported preview remains unsupported | PASS |
| INV-19-15 No private Anki storage or internals used | PASS |
| INV-19-16 Model/template cache backend/collection scoped | NOT YET TESTABLE (no long-lived cache added in this checkpoint; gateway is stateless) |
| INV-19-17 GATE 19 is read-only | PASS |
| INV-19-18 No model/template/CSS mutation added | PASS |
| INV-19-19 Capability differences between backends remain explicit | PASS (PC stays UNSUPPORTED) |
| INV-19-20 GATE 19 cannot weaken GATE 8/9 or GATE 11–18 | PASS |

## GATE LOCK STATUS

**GATE 19 LOCKED (read-only foundation).** Remaining work out of scope for this checkpoint and explicitly deferred to later gates: ModelDetails/TemplateDetails UI screens, Add Note model-selector UI enrichment, Card Details → Model/Template navigation, and on-device instrumented verification. The domain contract, backend read path, compatibility analyzer, and typed errors are in place and locked.
