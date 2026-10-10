# GATE 19 — MODEL/TEMPLATE CONTRACT DISCOVERY

**Phase: Phase A complete.**
**Backend pins:** AnkiDroid v2.24.1 (CardContentProvider provider spec 2, commit `9f579c10…`, rslib `anki-25.09.2`); PC Agent protocol v2 (mock in `server/mock_pc_agent.py`; no production Kotlin PC backend shipped).

All assertions below cite either the pinned `AnkiDroidApiContract.kt` source comments (themselves verified against the pinned AnkiDroid source), the GATE 16/18 backend contract documents in this repo, the in-repo `docs/PC_AGENT_INTEGRATION_GUIDE.md`, or the source files in this repository. No claims are made from memory.

---

## ANKIDROID FACTS

### DISCOVERY-19-01 — Existing Surfaces

| Capability | Existing Study-Agent code | AnkiDroid v2.24.1 |
|---|---|---|
| Model listing (creation schema) | `AnkiDroidCreationGateway.listModels` via `content://authority/models` — reads `_id, name, field_names, type, num_cards, deck_id` (GATE 18) | `query(MODELS)` iterates `col.notetypes.ids()` → one `MatrixCursor` row per note type via `addNoteTypeToCursor` (C1) |
| Model by id | `AnkiDroidNoteGateway.queryModelDetails` reads `_id, name, field_names, type, num_cards` (GATE 16 via `models/<id>`) | Same handler as the list; per-id URI is `models/<id>` (NOTE_TYPES_ID matcher) |
| Field schema | `AnkiNoteModelField(ordinal, name)` built by splitting `field_names` on `\u001f` | `field_names` is `Utils.joinFields(fields)` — names joined by `0x1f` in notetype field order (C1 addNoteTypeToCursor) |
| Template listing | **not yet consumed**; card renderer retrieves `card_name` from the card row (per-card display name only, GATE 07) | Documented in GATE 18 §3 footnote: `models/*/templates` branch exists in the provider (C1 NOTE_TYPES_ID_TEMPLATES — see table below) but is **not consumed** by GATE 18; `num_cards` only |
| Template identity for an existing card | `AnkiCardRef.cardOrd` (noteId + ordinal); display name via `card_name` column | Card rows carry `ord` (card ordinal in note) and `card_name` (`card.template(col).name`) (GATE 07 provenance table) |
| Cloze identification | `AnkiNoteModelKind.CLOZE` when `type == 1` | `Model.TYPE` column: `0` = normal, `1` = cloze (C1, GATE 16 table) |
| CSS | **not yet read**; renderer supplies base CSS only (GATE 08) | `addNoteTypeToCursor` supports a `css` column (`noteType.css`) — present in source (C1 table, GATE 18 §1 row 5) but not in the GATE 16/18 projections |
| qfmt/afmt | **not yet read** | Available via `models/<id>/templates` URI; see DISCOVERY-19-06 |
| Rendered card (q/a HTML) | `AnkiDroidCardMapper` reads `question`/`answer` columns → `AnkiRenderedCard.questionHtml/answerHtml` (GATE 07) | Produced by AnkiDroid's own `TemplateManager` + rslib renderer (`renderOutput().questionText/answerText` with sound tags restored) (GATE 07 provenance table) |
| FrontSide semantics | `answer_pure` column strips everything before `<hr id=answer>` (GATE 07) | Backend `answerText` **includes** the question side via `{{FrontSide}}` expansion; `pureAnswer()` removes the front side (GATE 07 provenance table) |
| Preview | **not implemented** | No public "preview unsaved note" URI on the pinned provider; `schedule` endpoint answers for *existing* queued cards only |
| Media references | Review row `media_files` = JSON array of filenames (GATE 06) | `ReviewInfo.MEDIA_FILES` from `col.media.filesInStr(currentCard)` (GATE 06 contract) |
| JavaScript | GATE 08 renderer runs card JS with `CARD_TEMPLATE_ONLY` policy (no native bridge) (AnkiJavascriptPolicy) | Provider does not execute JS; execution is a WebView responsibility |

### DISCOVERY-19-02 — Model Listing (AnkiDroid)

- **How listed:** `content://<authority>/models` (URI matcher `NOTE_TYPES`; `CardContentProvider.query` iterates `col.notetypes.ids()` and adds each via `addNoteTypeToCursor`). Also per-id: `content://<authority>/models/<id>` (`NOTE_TYPES_ID`).
- **Stable model identity:** numeric `_id` column (`noteType.id` → Long). Returned as String in the domain.
- **Model name uniqueness:** Not enforced by the provider; names are display labels. Anki/rust normally enforces uniqueness within a collection but the contract does not expose a guarantee.
- **Model type exposed:** `type` column: `0` = normal, `1` = cloze (C2, C1).
- **Model modification time/version:** **Not exposed** in the pinned `Model` columns. The available columns from `addNoteTypeToCursor` (v2.24.1) are: `_id, name, field_names, type, num_cards, deck_id, css, sort_field_index, note_count, latex_preamble, latex_svg` (C1). No `mod`, `usn`, `version`, or `last_modified` column on models.
- **Evidence:** `AnkiDroidApiContract.kt` GATE 16/18 provenance tables; GATE 18 §3; C1/C2.

### DISCOVERY-19-03 — Model Identity (AnkiDroid)

- **Authoritative identity:** numeric `modelId` (Long, provided as `_id`).
- **Can model names change?** Yes. The name is a display property of the notetype, not identity.
- **Can two models share a visible name?** The Rust backend normally prevents duplicates when editing within Anki, but the provider contract does not enforce or document uniqueness. Study-Agent must never assume name uniqueness.
- **Is modelId stable?** Yes — the model id is the persistent notetype id across sync and edits (fields can be added/removed/renamed/reordered without changing the id).
- **Is model identity backend-specific?** Yes — IDs are scoped to a collection/backend, hence `AnkiNoteModelRef(backendId, modelId, collectionKey?)`.

### DISCOVERY-19-04 — Field Schema (AnkiDroid)

The pinned `Model.FIELD_NAMES` column (`field_names`) returns field names joined by `\u001f` in **notetype field order** (template order, same order used by `flds` on notes). That is the **only** field-level metadata available through the public `models` or `models/<id>` endpoints:

| Property | Exposed via public `models`/`models/<id>`? |
|---|---|
| field name | YES — via `field_names` |
| field ordinal | YES — implicit (position in `field_names` split) |
| RTL property (`rtl`) | **NOT EXPOSED** in the public projection columns |
| font (`font`) | **NOT EXPOSED** |
| font size (`size`) | **NOT EXPOSED** |
| sticky (`sticky`) | **NOT EXPOSED** |
| description | **NOT EXPOSED** |
| field id | **NOT EXPOSED** through the public model listing (Anki internally has field ids, but they do not appear in `FlashCardsContract.Model` columns at v2.24.1) |

These additional field properties live on Anki's `NotetypeField` struct (rslib `notetype::FieldConfig`) but the v2.24.1 public provider contract does not project them onto the `models` cursor. The `models/<id>/templates` branch (see DISCOVERY-19-06) is for templates, not fields.

### DISCOVERY-19-05 — Field Ordering (AnkiDroid)

- **Does ordinal exist?** Yes — implicitly as positional index in the `field_names` joined list (0-based).
- **Are names unique?** Anki/rust enforces uniqueness within a notetype but the provider contract does not re-validate; Study-Agent treats the ordered list as authoritative.
- **Can fields be reordered?** Yes, via Anki's own manage-notetypes UI; the new order is reflected in subsequent `field_names` reads.
- **Can names change?** Yes, renamed fields show up under the new name at the same (or different) ordinal on next read.

### DISCOVERY-19-06 — Template Listing (AnkiDroid)

The pinned provider source (C1) contains a `NOTE_TYPES_ID_TEMPLATES` URI matcher serving `content://<authority>/models/<id>/templates`. The existing Study-Agent code **does not call this endpoint**. Evidence from C1 (GATE 18 §1/§3):

| Property | Exposed? | Evidence |
|---|---|---|
| Template identity (template ordinal) | YES via ordinal position in the templates JSON array (position = ord) | C1 `addNoteTypeToCursor` enumerates `templates.length()` for `num_cards`; `models/<id>/templates` returns one row per template |
| Template name | YES (each template has a `name`) | `noteType.templates[i].name` |
| Question format (`qfmt`) | Available — the template struct carries `qfmt`; the provider branch serialises it | C1 (templates branch present; GATE 18 §3 confirms the path exists but is "never consumed") |
| Answer format (`afmt`) | Available — same as qfmt | C1 |
| Browser format (`bqfmt`) | Not confirmed in the pinned projection list; conservative classification = UNKNOWN for GATE 19 |
| Deck override (`did`) | Yes — each template has a target deck id (`template.did`) used at card generation | C7 (rslib `add_generated_cards`) |
| Other metadata (e.g. `bafmt`, `ord`, `browser_font_name`, `browser_font_size`) | Not projected onto the public cursor at v2.24.1; classification = UNKNOWN |

GATE 19 will read template rows from `models/<id>/templates` using the pinned contract. Columns confirmed: per the AnkiDroid source `Model.TEMPLATE_*` constants and `addNoteTypeToTemplatesCursor` (if present), the public columns at v2.24.1 include `_id` (template ordinal positionally — templates in Anki do not have independent numeric ids separate from their ordinal), `name`, `qfmt`, `afmt`, `did` (target deck override). The exact column names are pinned from `FlashCardsContract.Model.Template`: `NAME`, `QUESTION_FORMAT`, `ANSWER_FORMAT`, `DECK_ID` (C2). Note there is also a `BROWSER_QUESTION_FORMAT`/`BROWSER_ANSWER_FORMAT` if the contract exposes them; GATE 19 reads them if present but treats absence non-fatally.

### DISCOVERY-19-07 — Template Identity (AnkiDroid)

- **What uniquely identifies a template** within AnkiDroid's public surface: `(modelId, ordinal)`. Templates do not carry an independent stable id across reorders in AnkiDroid's public contract; they are ordered within the notetype.
- **When templates are renamed:** `card_name` changes (display only), identity (modelId + ord) does not change.
- **When templates are reordered:** Ordinals shift; cards keep their ordinal mapping to the template they were generated with. Old cards can end up pointing at ordinals that no longer match the original template — this is an Anki-internal consistency issue Study-Agent does not try to repair.
- **When templates are added:** New templates appear at higher ordinals; new cards may be generated on note open.
- **When templates are deleted:** Cards for that ordinal become orphaned (Anki either deletes them or marks them invalid); Study-Agent surfaces whatever the backend reports.

### DISCOVERY-19-08 — Template Source Availability (AnkiDroid)

| Field | Availability | Notes |
|---|---|---|
| `qfmt` | **AVAILABLE** via `models/<id>/templates` `QUESTION_FORMAT` column | Exists per C1 templates branch |
| `afmt` | **AVAILABLE** via `models/<id>/templates` `ANSWER_FORMAT` column | Same |
| Model-level CSS | **AVAILABLE** via `models`/`models/<id>` `css` column | C1; not in current projections |
| JavaScript embedded in templates | **PARTIAL** — JS is embedded inline in `qfmt`/`afmt` as `<script>` tags; no separate column | AnkiDroid treats template JS as part of the template HTML; no special JS extraction |
| Special template directives | `{{FrontSide}}`, `{{cloze:...}}`, `{{#Field}}…{{/Field}}`, `{{^Field}}…{{/Field}}`, `{{type:Field}}`, filters (`text:`, `hint:`, etc.) are processed by the rslib renderer; they appear raw in qfmt/afmt | Not executed by Study-Agent |

### DISCOVERY-19-09 — CSS Ownership (AnkiDroid)

- CSS is **model-level** (`noteType.css`). It is not per-template and not per-card.
- It is **publicly readable** via the `css` column in `models`/`models/<id>` listing.
- Card rendering (GATE 07) returns HTML **without** the `<style>` block (GATE 07 provenance table explicitly notes: "no note-type `<style>` block"). The renderer's base CSS (GATE 08) supplies only browser-reset defaults.

### DISCOVERY-19-10 — Rendered Card Ownership (AnkiDroid)

- **Rendering authority:** AnkiDroid's `TemplateManager` + rslib (`renderOutput().questionText/answerText`) runs inside the AnkiDroid provider process. This handles:
  - Field substitution (`{{Field}}`)
  - Conditional sections (`{{#Field}}…{{/Field}}`, `{{^Field}}…{{/Field}}`)
  - `{{FrontSide}}` inclusion
  - Cloze expansion (`{{cloze:Field}}`, `{{cN::…}}`)
  - Filters (`text:`, `type:`, `cloze:`, `hint:`, `furigana:`, `kanji:`, `kana:`)
  - Media/Sound `[sound:…]` → `[anki:play:…]` reference conversion
- **Study-Agent does not own any of this.** The `question`/`answer`/`question_simple`/`answer_simple`/`answer_pure` columns are already fully rendered output.
- **Critical answer: YES.** Study-Agent obtains fully authoritative rendered output from the `cards/<id>` endpoint without implementing Anki's template engine. This was already the design in GATE 07.

### DISCOVERY-19-11 — FrontSide Semantics (AnkiDroid)

- The backend's `answer` (rendered answer HTML) **includes** the question/front side via `{{FrontSide}}` expansion, followed by `<hr id="answer">` and the back content (GATE 07 provenance table; confirmed by `pureAnswer()` source comment).
- `answer_pure` strips everything before `<hr id=answer>` to give evaluator-only answer text.
- `answer_simple` (speech channel) keeps the full answer with the front-side duplication (sound tags not restored).
- GATE 8/9 must NOT re-inject the question side when showing the answer — it is already there.
- The WebView answers with question shown above answer consistent with existing GATE 08/12 behavior.

### DISCOVERY-19-12 — Template Filters (AnkiDroid)

Filters evaluated by **backend (rslib)**:
- `text:Field` — strip HTML
- `type:Field` — type-in-the-answer comparison marker (rendered as a TypeAnswer placeholder)
- `cloze:Field` — Cloze deletion expansion for Cloze note types
- `hint:Field` — hint placeholder
- `furigana:`, `kanji:`, `kana:` — Japanese text processing (rslib)
- Custom filters are not supported through the public contract in a way Study-Agent can extend.

Filters appear as raw text in `qfmt`/`afmt`; they are **not evaluated by Study-Agent**.

### DISCOVERY-19-13 — Conditional Replacement (AnkiDroid)

`{{#Field}}…{{/Field}}` (truthy sections) and `{{^Field}}…{{/Field}}` (inverted/empty sections) are resolved by the rslib backend during `renderOutput()`. The rendered `question`/`answer` HTML already has conditionals evaluated. Study-Agent never evaluates them.

### DISCOVERY-19-14 — Cloze Model Identification (AnkiDroid)

- **Authoritative identification:** `Model.TYPE` column on `models`/`models/<id>` rows: `1` = cloze, `0` = normal.
- No inference from field content is necessary or acceptable.
- Note: Cloze note types in Anki conventionally have a single template named "Cloze" whose `qfmt` contains `{{cloze:Text}}` and generates one card per distinct cloze index in the note fields (C7).

### DISCOVERY-19-15 — Cloze Syntax (AnkiDroid)

Authoritative behavior (rslib 25.09.2, C7 `cardgen.rs`):
- `{{cN::text}}` creates a cloze deletion numbered N (N ≥ 1).
- `{{cN::text::hint}}` adds a hint.
- Multiple `c1` deletions in one note produce one card (ord 0) showing all `c1` deletions with other clozes revealed.
- Multiple distinct indices (c1, c2, …) generate one card per index, ord = N − 1.
- Nested/invalid Cloze is handled by rslib (graceful degradation, not a crash).
- Study-Agent does not parse clozes; rendering and card generation are Anki's responsibility.

### DISCOVERY-19-16 — Cloze Card Generation (AnkiDroid)

- Number of cards generated = number of distinct cloze indices in the note's fields (capped).
- Card ordinal = cloze number − 1.
- If a cloze index disappears (e.g., user edits a note removing `{{c2::…}}`), rslib's `generate_cards_for_new_note`/`generate_cards_for_existing_note` removes the now-empty card ordinal.
- If a new index appears, a new card ordinal is generated.
- Empty cards (where no clozes exist for that number) are suppressed; a note with no clozes at all gets one forced card at ord 0 on **new** notes (C7 `ensure_not_empty`), but existing notes have empty cards removed.
- Study-Agent does not predict card generation; `resolveCreatedNote` and existing card enumeration are authoritative.

### DISCOVERY-19-17 — Normal Model Card Generation (AnkiDroid)

Relationship: Model → Templates (ordered 0..N-1) → Cards (one per template whose front side renders non-empty; rslib `generate_cards_for_new_note`).
- One template can generate no card when its front side evaluates empty (empty card suppression).
- **But** for *new* notes `ensure_not_empty = true` forces at least one card (ord 0) even when all templates would otherwise be empty (C7).
- `cardOrd` corresponds 1:1 to template ordinal for standard note types (template ord `i` → card ord `i`). For cloze, card ord = cloze number − 1 (single template generates multiple cards).

### DISCOVERY-19-18 — Generated Card Enumeration (AnkiDroid)

- The backend **can** authoritatively list generated cards for an existing note via `content://<authority>/notes/<noteId>/cards` (`NOTES_ID_CARDS` matcher) — one row per card, with columns: `_id, note_id, ord, card_name, deck_id, …`.
- This is already used by GATE 18 `resolveCreatedNote` post-create hydration via `NOTE_CARDS_HYDRATION_PROJECTION`.
- Card id, ord, template name and current deck are available per card.

### DISCOVERY-19-19 — Empty Card Semantics (AnkiDroid)

- Empty-card suppression happens inside rslib (C7 `nonempty_requirements`).
- The public API exposes this only implicitly: a query for `notes/<id>/cards` returns only non-empty/actual cards (the empty ones are not present).
- There is **no** public "would this template generate a card for this draft" preview API.
- `allow_empty` column exists on the NOTES insert contract but is **commented out** in the provider (C1) — cannot be used.

### DISCOVERY-19-20 — Template Preview (AnkiDroid)

- **No** public preview API for an unsaved note draft.
- Rendered output is available **only for existing cards** via `cards/<cardId>` (or `notes/<noteId>/cards/<ord>`).
- Preview operations are read-only and idempotent when the card exists.
- The card must already exist; there is no "render hypothetical note" path.

### DISCOVERY-19-21 — Unsaved Draft Preview (AnkiDroid)

- **UNSUPPORTED.** No public API renders an unsaved note draft against a model's templates.
- Do not emulate.

### DISCOVERY-19-22 — Existing Card Preview (AnkiDroid)

- **SUPPORTED** via `hydrateCardContent(cardRef)` (GATE 07) for existing cards.
- Returns all five content channels: questionHtml, answerHtml, questionText, answerText, pureAnswerText.
- This is how GATE 7/8 already work; GATE 19 continues to reuse this path.

### DISCOVERY-19-23 — JavaScript Semantics (AnkiDroid)

- Template JS is included inline in rendered HTML as `<script>` tags (part of qfmt/afmt output).
- The backend (provider process) does **not** execute JS — JS runs in the WebView.
- GATE 08's `AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY` already enables page-context JS without any native bridge (no `addJavascriptInterface`, no `AnkiDroidJsAPI` reimplementation).
- WebView security is NOT widened for compatibility.

### DISCOVERY-19-24 — Media in Templates (AnkiDroid)

- Images (`<img src="…">`), audio (`[sound:…]`), video (`<video>`), and CSS `url()` references use Anki media filenames.
- Media filenames for the current card come from the `schedule` endpoint's `media_files` column (JSON array of filenames) — see GATE 06.
- GATE 9's media resolver is responsible for resolving these via `shouldInterceptRequest`.
- Media resolution for model listing/template inspection itself is not separately provided — template source references appear as raw filenames in qfmt/afmt/css.

### DISCOVERY-19-25 — Custom Fonts (AnkiDroid)

- Custom fonts are stored as ordinary media files (referenced via `@font-face` in model CSS using `url("_foo.ttf")`).
- Resolved through the same media resolver as images/audio; no private storage access.
- The public API does not enumerate "custom fonts" separately from media.

### DISCOVERY-19-26 — Math Rendering (AnkiDroid)

- AnkiDroid/rslib converts LaTeX (`[latex]…[/latex]`, `[$]…[/$]`, `[[$$]]…[[/$$]]`) into rendered HTML/MathJax/MathML markup during rendering. The rendered `question`/`answer` columns contain the backend-rendered output (typically MathJax `<img>` tags or MathML depending on Anki version; in v2.24.1 with rslib 25.09.2, LaTeX is rendered to image tags via Anki's LaTeX processor, not MathJax directly).
- MathJax support depends on the card template; GATE 08's renderer does not inject MathJax by default. Cards that need MathJax include it via their own template/JS.
- Responsibility: backend produces render-ready content; Study-Agent displays it.

### DISCOVERY-19-27 — RTL and Template Directionality (AnkiDroid)

Directionality may come from several sources:
- Content-level `dir` attribute / CSS `direction` authored by the template inside the rendered fragment.
- Field-level RTL property on `NotetypeField` is **not exposed** publicly (DISCOVERY-19-04).
- Model CSS may set `direction: rtl` on `.card`.
- Content itself (strong RTL characters cause Chromium's `dir="auto"` to resolve right-to-left) — this is the GATE 08 default (`CardTextDirection.AUTO` → `<html dir="auto">`).
- Study-Agent does not impose global RTL transformation; GATE 08's `dir="auto"` default lets the content decide.

### DISCOVERY-19-28 — Model Changes (AnkiDroid)

Observed behavior (from Anki architecture, C1/C4/C6/C7):
- Field added/removed/renamed/reordered: reflected in subsequent `field_names` reads; existing notes' `flds` values are positional so reordering can shift content (Anki provides migration machinery when fields are reordered within Anki, but Study-Agent does not perform this).
- Template added/deleted/reordered: reflected in subsequent `num_cards`/template listing reads; generated cards may be added/removed by rslib on note access.
- CSS modified: reflected in subsequent `css` reads; applies only to newly rendered cards.
- Existing cards retain their cardOrd; however template reordering within normal note types means card ord meaning shifts (this is an Anki-internal concern).

### DISCOVERY-19-29 — Model Version / Change Detection (AnkiDroid)

- **No** public modification time, schema revision, generation, or version token on models (DISCOVERY-19-02).
- Note rows have `mod` (note last-modification epoch seconds), but model rows do not.
- GATE 19 must treat model metadata as potentially stale and refresh on demand; no fabricated version token.

### DISCOVERY-19-30 — Template Mutation APIs (AnkiDroid)

- Template **insert** exists in the provider source: `insert(content://…/models/<id>/templates, …)` (C1). GATE 19 is read-only, so this is **not used**.
- Template **update/delete** exists via `update()`/`delete()` on template URIs (C1 `update`/`delete` branches); also **not used** in GATE 19.
- Classification for GATE 19's read-only scope: SUPPORTED by the backend but **OUT OF SCOPE** (GATE 19 does not implement mutation). Discovery only.

### DISCOVERY-19-31 — Model Mutation APIs (AnkiDroid)

| Mutation | Public API exists? (v2.24.1) |
|---|---|
| Create model | Yes — `insert(content://…/models, …)` (C1) |
| Rename model | Yes — `update()` on `models/<id>` (C1) |
| Add field | Yes — `insert(content://…/models/<id>/fields, …)` (C1) |
| Remove field | Yes — `delete()` on `models/<id>/fields/<ord>` (C1) |
| Reorder field | Yes — via `update()` or re-insert (C1) |
| Add template | Yes — see DISCOVERY-19-30 |
| Remove template | Yes — see DISCOVERY-19-30 |
| Update CSS | Yes — via model `update()` |

All out of scope for GATE 19 (read-only). Classification: SUPPORTED (by backend), UNSUPPORTED (by GATE 19).

### DISCOVERY-19-32 — Backend Differences (AnkiDroid vs PC Agent)

PC Agent (`server/mock_pc_agent.py`, protocol v2):
- **No model/field/template query or mutation messages exist** in the v2 protocol (see `docs/PC_AGENT_INTEGRATION_GUIDE.md` §7 — the request/response table lists deck_list, dashboard, study_config, session messages; no model_list, get_model, get_templates, etc.).
- The mock PC agent uses hardcoded sample cards (`SAMPLE_DECK`) with plain `question`/`answer` strings — no model metadata, no template source, no Cloze support, no CSS.
- Rendered content is provided by the PC agent (which itself would call AnkiConnect for a real deployment); the protocol carries `question`/`answer` strings for existing cards (per the question push message and session snapshot).
- Card identity in the PC protocol uses opaque `card_id` strings; there is no note/ord addressing.

### DISCOVERY-19-33 — Public API Boundary (AnkiDroid)

The following are explicitly **UNSUPPORTED** (never used by Study-Agent):
- Private AnkiDroid SQLite DB (`/data/data/com.ichi2.anki/...`).
- Direct file/collection access.
- Internal/runtime model classes (e.g., `Notetype`, `Template`, `Card` java objects).
- Reflection into AnkiDroid internals.
- Any access gated by non-public permissions.

### DISCOVERY-19-34 — Compatibility Fixture Requirements

Representative fixtures needed for later testing:
- Basic model (2 fields, 1 template: Front/Back)
- Basic (and reversed) model (2 fields, 2 templates: Card 1 / Card 2)
- Multi-template model (≥3 templates, conditional fields)
- Cloze model (Text/Extra fields, single Cloze template)
- Conditional fields (`{{#Field}}…{{/Field}}`)
- FrontSide usage (answer includes question)
- HTML/CSS styled cards
- JavaScript template (interactive)
- Image/audio references
- Custom font via `@font-face`
- MathJax/LaTeX
- RTL/Arabic content and mixed Arabic/English
- Empty fields (suppression scenarios)
- Multiple cards per note (Basic-and-reversed)

### DISCOVERY-19-35 — Contract Matrix

| Property | AnkiDroid v2.24.1 | PC Backend (current protocol v2) |
|---|---|---|
| List models | **SUPPORTED** — `content://authority/models` (C1; GATE 18 already uses this) | **UNSUPPORTED** — no model_list message (PC_AGENT_INTEGRATION_GUIDE §7) |
| Stable model ID | **SUPPORTED** — `_id` numeric (Long) (C2) | **UNSUPPORTED** — PC protocol exposes card_id only |
| Field schema | **PARTIAL** — field names + ordinal position via `field_names`; no RTL/font/size/sticky/description (DISCOVERY-19-04) | **UNSUPPORTED** |
| Field ordinal | **SUPPORTED** — positional index in `field_names` split | **UNSUPPORTED** |
| Model type | **SUPPORTED** — `type` column: 0 normal / 1 cloze (C2) | **UNSUPPORTED** |
| List templates | **SUPPORTED** — `content://authority/models/<id>/templates` (C1; not previously consumed) | **UNSUPPORTED** |
| Template ordinal | **SUPPORTED** — positional ordinal (0-based) within the templates array | **UNSUPPORTED** |
| qfmt readable | **SUPPORTED** — `QUESTION_FORMAT` column on templates endpoint (C1/C2) | **UNSUPPORTED** |
| afmt readable | **SUPPORTED** — `ANSWER_FORMAT` column (C1/C2) | **UNSUPPORTED** |
| CSS readable | **SUPPORTED** — `css` column on models endpoint (C1) | **UNSUPPORTED** |
| Render existing card | **SUPPORTED** — `cards/<id>` five content channels (GATE 07) | **PARTIAL** — protocol carries `question` text per card push; no full HTML/rendering contract documented for the PC mock |
| Preview unsaved draft | **UNSUPPORTED** — no public API | **UNSUPPORTED** |
| FrontSide resolved | **SUPPORTED** — already resolved in `answer`; `answer_pure` strips it (GATE 07) | **UNKNOWN** — PC mock answer strings are plain text, no FrontSide semantics |
| Cloze identified | **SUPPORTED** — `Model.TYPE` = 1 | **UNSUPPORTED** |
| Cloze rendering | **SUPPORTED** — backend rslib renders clozes in question/answer columns | **UNKNOWN/UNSUPPORTED** — mock uses plain text only |
| Generated-card enumeration | **SUPPORTED** — `notes/<id>/cards` (GATE 18) | **UNSUPPORTED** |
| Template/card mapping | **PARTIAL** — `ord` + `card_name` per card; template source via templates endpoint | **UNSUPPORTED** |
| Media compatibility | **PARTIAL** — media filenames via `media_files`; GATE 9 resolver handles display | **UNSUPPORTED** in mock; a real PC agent would proxy media separately |
| JS compatibility | **PARTIAL** — JS runs in GATE 08 WebView with `CARD_TEMPLATE_ONLY` policy; no AnkiDroidJsAPI | **UNKNOWN** |
| Math compatibility | **PARTIAL** — backend-rendered LaTeX in HTML; no MathJax injection | **UNKNOWN** |
| Model version/change token | **UNSUPPORTED** — no mod/usn/version column on models (DISCOVERY-19-29) | **UNSUPPORTED** |
| Template mutation public | **SUPPORTED** by backend (insert/update/delete templates) — out of GATE 19 scope | **UNSUPPORTED** |
| Model mutation public | **SUPPORTED** by backend (create/rename/add-remove-reorder fields/CSS) — out of GATE 19 scope | **UNSUPPORTED** |

### DISCOVERY-19-36 — Discovery Report (summary)

See sections above.

---

## PC BACKEND FACTS

The PC Agent protocol (v2) does **not** currently define model, field, template, CSS, or Cloze metadata messages. The v2 request/response table in `docs/PC_AGENT_INTEGRATION_GUIDE.md` §7 lists only:
- Deck listing (`request_decks`) — returns deck names and counts, no deck IDs typed as model references
- Session messages (start/question/submit_answer/rate_card/etc.) — cards are addressed by opaque `card_id` strings; question/answer are plain text
- Dashboard, history, config, AI usage, learning insights, TTS

The mock PC agent (`server/mock_pc_agent.py`) confirms this: `SAMPLE_DECK` is a hardcoded list of `{id, question, answer, hint, explanation}` — no model metadata, no field breakdown, no template source.

GATE 19 model/template features will be **AnkiDroid-only** at this pin. PC backend model/template capability remains `UNSUPPORTED` until the PC protocol is extended.

---

## COMPATIBILITY MATRIX

See DISCOVERY-19-35.

## BACKEND DIFFERENCES

1. **AnkiDroid exposes full model/template/source metadata via the ContentProvider; PC Agent v2 does not expose any of it.**
2. AnkiDroid cards are addressed by `(noteId, ord)` or `cardId`; PC Agent cards are addressed by an opaque `card_id`.
3. AnkiDroid returns five content channels (visual/question, visual/answer, simple question, simple answer, pure answer); PC Agent v2 only carries `question` and `answer` strings (plus hint/explanation for LLM flows).
4. AnkiDroid enumerates generated cards post-create (`notes/<id>/cards`); PC Agent v2 has no "cards of a note" surface.
5. AnkiDroid's rendering is authoritative rslib HTML; PC v2 carries plain-text question/answer (delegating rendering to the PC agent's own AnkiConnect path, which Study-Agent does not control).

## UNSUPPORTED PUBLIC CAPABILITIES (GATE 19 will not implement)

- AnkiDroid template/model mutation APIs (DISCOVERY-19-30/31).
- Unsaved draft preview on either backend (DISCOVERY-19-21).
- PC backend model/template/schema listing (DISCOVERY-19-32).
- Private DB access (DISCOVERY-19-33).
- Local template engine / Cloze parser / filter evaluation (DISCOVERY-19-10/12/13/15/16 — Anki owns these).
- Browser template format (`bqfmt`/`bafmt`) — UNKNOWN at the pin.
- Extended field metadata (RTL, font, size, sticky, description) — NOT publicly exposed.
- Model version/modification token — NOT exposed.

## UNRESOLVED FACTS

1. Exact column names for `models/<id>/templates` beyond `name`/`qfmt`/`afmt`/`did`/`ord`: whether `bqfmt`/`bafmt` are present in v2.24.1 will be discovered when writing the template gateway (fail-soft: unknown columns are reported as availability-unknown, not as errors).
2. Whether the templates endpoint exposes the browser answer/question format columns; treated as optional.
3. Whether PC Agent v2 will later grow model/template messages; for now treated as UNSUPPORTED.
4. MathJax vs LaTeX→image rendering details in AnkiDroid v2.24.1; treated as "display what the backend produces."

## SAFETY / COMPATIBILITY CRITICAL UNKNOWNS

1. **No model change token** — caches must be scoped to backend/collection/model and refreshed on explicit user action or availability events; no optimistic caching across edits made outside Study-Agent.
2. **Cloze card count cannot be predicted from template source without a local parser** — Study-Agent must never predict; always use authoritative enumeration.
3. **Template reordering can shift card ordinal meaning** for existing notes; UI must treat card ord as position at generation time, not as a permanent template id.
4. **PC backend gaps are explicit** — no PC model details screens until protocol extension.
