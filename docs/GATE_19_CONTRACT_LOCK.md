# GATE 19 — MODEL/TEMPLATE CONTRACT LOCK

**RESULT: LOCKED**
**IMPLEMENTATION AUTHORIZED: YES**

---

## SUPPORTED PRODUCT SCOPE

GATE 19 delivers the following read-only capabilities:

1. **Read model metadata** — list all note types, fetch one note type by stable ref. Includes: backend-qualified model identity, display name, model kind (STANDARD/CLOZE/UNKNOWN), ordered field schema, template count, default deck id (display only), model-level CSS (where available).
2. **Read field schema** — ordered list of (ordinal, name). Ordinal is the field's positional identity; name is display label and drift-detection key.
3. **Read template metadata** — for each template of a model: ordinal, name, qfmt (raw question-format source), afmt (raw answer-format source), target deck override (if available).
4. **Identify Cloze models** — by authoritative backend `type` flag only.
5. **Map card ord/template relationships** — for existing cards: cardOrd maps to template ordinal (standard models) or cloze index−1 (cloze models). This mapping is backend-authoritative, never predicted.
6. **Display compatibility information** — read-only: qfmt/afmt/CSS availability, JS classification, media references, math markers, RTL signals, conditional usage.
7. **Reuse authoritative rendered cards** — continue to use GATE 7 `hydrateCardContent` for both review and details; no second rendering stack.
8. **Model details screen (read-only)** — shows model identity, kind, fields list, templates list, CSS availability, capability/compatibility status.
9. **Template details screen (read-only)** — shows template ordinal, name, qfmt/afmt source (display only, not editable), preview via existing card rendering where an example card exists, compatibility status.
10. **Add Note model-selector integration** — GATE 18 already uses `getNoteModels()`; GATE 19 extends the schema with richer field metadata (preserving backward compatibility).
11. **Card Details navigation** — from card details → model details → template details, passing stable refs only.

## MODEL IDENTITY

- **Canonical reference:** `AnkiNoteModelRef(backendId, modelId, collectionKey?)`
- `modelId` is the backend-issued stable id (AnkiDroid: numeric Long as String; PC: opaque string when added later).
- Name is **never** used as identity. Name is display-only.
- Model refs are backend+collection-qualified; a ref from backend A never resolves against backend B.
- Equality uses all components (backendId, modelId, collectionKey).
- This extends — not replaces — the existing GATE 18 `AnkiNoteModelRef`; the existing type already has this shape.

## FIELD IDENTITY

- **Canonical identity:** `(modelRef, ordinal)` — ordinal is a 0-based integer index.
- `name` is a display label and drift-detection key (used in `schemaMatches`), not a write key.
- Fields are always represented in **backend-authoritative order** (ordinals dense from 0, no gaps).
- Field names are required unique within a model (enforced by the backend; asserted in model constructor).
- Extended field metadata (RTL, font, size, sticky, description) is **not modeled** because AnkiDroid's public contract does not expose them; if a backend later exposes them, add as nullable fields with default `null`.
- The existing GATE 18 `AnkiNoteModelField(ordinal, name)` is retained.

## TEMPLATE IDENTITY

- **Canonical reference:** `AnkiCardTemplateRef(backendId, modelRef, ordinal, collectionKey?)`
- Ordinal is the 0-based positional index within the model's template list (AnkiDroid templates have no independent id).
- Template name is display-only; never used as identity.
- Template refs are backend+collection+model-qualified.

## MODEL TYPES

Normalized set:
- `STANDARD` — AnkiDroid `type = 0`
- `CLOZE` — AnkiDroid `type = 1`
- `UNKNOWN` — any other code; UI displays "Unknown" and does not infer behavior.

This extends the existing GATE 18 `AnkiNoteModelKind` enum (`NORMAL` → `STANDARD` rename-or-alias; kept as `NORMAL` to minimize diff; both map via kind). **Decision: keep the existing `AnkiNoteModelKind.NORMAL/CLOZE/UNKNOWN` names to avoid a sweeping rename.** The semantic lock is identical.

## RENDERING OWNERSHIP

Normative rule:
```
Anki / backend       → template semantics, Cloze, filters, FrontSide, conditionals, card generation
Study-Agent (GATE 8/9) → WebView presentation host, media resolution, security
```

- Study-Agent consumes authoritative rendered question/answer from the backend for all existing cards.
- qfmt/afmt/CSS are **inspection metadata only** — they are displayed in a read-only template details screen for diagnostic/compatibility purposes; they are **never** fed to a local template renderer.
- No local Anki template engine.

## RAW TEMPLATE SOURCE ROLE

- `qfmt`, `afmt`, and model CSS are read-only inspection data.
- Used for:
  - Compatibility diagnostics (detecting `<script>`, `{{type:`, `{{cloze:`, `[latex]`, media refs, `@font-face`, conditional blocks, `{{FrontSide}}`).
  - Display in template details screens (read-only source viewer).
  - Future editing input (not in GATE 19).
- Never used to:
  - Render cards locally.
  - Predict generated cards.
  - Re-implement Anki scheduler/card generation logic.

## CLOZE OWNERSHIP

- Anki/backend owns: cloze parsing, cloze card generation, cloze ordinal semantics, cloze rendering.
- Study-Agent exposes `isCloze` boolean at the model level (from backend type flag) as metadata.
- Card ordinal for Cloze cards is backend-authoritative; Study-Agent does not compute it from `{{cN::…}}` text.
- No local Cloze parser. No card-count prediction.

## GENERATED-CARD SEMANTICS

- Study-Agent can enumerate cards for an existing note via backend `notes/<id>/cards` (already used by GATE 18).
- Study-Agent does **not** predict how many cards a draft will generate for either STANDARD or CLOZE models.
- Template-to-cardOrd mapping is authoritative per card:
  - STANDARD models: card ord = template ordinal (one card per non-empty template; ord mapping preserved from generated cards enumeration).
  - CLOZE models: card ord = cloze number − 1 (single template generates N cards; N comes from the card enumeration, not from parsing fields).
- Empty-card suppression is Anki's responsibility; Study-Agent does not duplicate this logic.

## PREVIEW MODES

Explicit supported modes:
- `EXISTING_CARD` — SUPPORTED: use GATE 7 `hydrateCardContent` for an existing card ref. This is the only "preview" mode guaranteed authoritative.
- `NO_DRAFT_PREVIEW` — Draft preview for an unsaved note is classified **UNSUPPORTED** on both backends. No client-side emulation.
- UI must label preview accordingly: only existing cards are previewable; draft note creation shows the field editor without a rendered preview, or uses the existing card if a sibling card exists for a prior note of the same model.

## COMPATIBILITY LEVELS

Compatibility classification per card/model/template (read-only diagnostic):

- **FULL** — renders through GATE 8/9 without limitations; uses only HTML/CSS, no JS/custom filters/math that GATE 9 cannot resolve.
- **SUPPORTED_WITH_LIMITATIONS** — renders through GATE 8/9; uses JS (allowed under `CARD_TEMPLATE_ONLY` policy), media (resolved by GATE 9), custom fonts, MathJax/LaTeX (displayed as backend provides), RTL (content-resolved via `dir="auto"`).
- **DISPLAY_ONLY** — can display model/template metadata and the existing rendered card via backend but a known compatibility caveat applies (e.g., uses AnkiDroidJsAPI which is not provided; the card still renders with script errors contained).
- **UNSUPPORTED** — cannot be handled (e.g., requires private storage, DRM media, network access not permitted by GATE 9 security policy); UI explicitly labels it unsupported.
- **UNKNOWN** — inspection could not determine; treated as SUPPORTED_WITH_LIMITATIONS but surfaced as "not classified" in diagnostics.

Classification criteria:
- Presence of `<script>` → not automatically limited; JS is allowed by `CARD_TEMPLATE_ONLY`.
- Presence of `AnkiDroidJS` / `pycmd` / `ankiPlatform` bridges → DISPLAY_ONLY (scripts may not behave as expected but HTML still renders).
- Presence of `http://`/`https://` resource references → depends on GATE 9 policy (mixed content blocked); marked DISPLAY_ONLY for external-network dependencies.
- Presence of `[sound:]`/`<img>`/`<video>` → resolvable by GATE 9 media resolver; FULL/SUPPORTED_WITH_LIMITATIONS depending on media type availability.
- Presence of `[latex]`/`[$]`/`MathJax` → SUPPORTED_WITH_LIMITATIONS (backend-rendered; no MathJax injection).
- Presence of `@font-face` → SUPPORTED_WITH_LIMITATIONS (fonts resolved as media).
- RTL markers in content / CSS `direction: rtl` → FULL (handled by `dir="auto"` and authored CSS).

## JAVASCRIPT POLICY

- GATE 8/9 policy is preserved unchanged (`AnkiJavascriptPolicy.CARD_TEMPLATE_ONLY` as default, `DISABLED` available).
- No native bridge (`addJavascriptInterface`) is added.
- AnkiDroid JS API (`AnkiDroidJS`, `pycmd`, `ankiPlatform`) is NOT reimplemented. Cards that depend on it degrade to DISPLAY_ONLY compatibility.
- No file access, no network access beyond existing GATE 9 policy, no intent handling, no mixed-content loosening.
- GATE 19 does not weaken WebView security.

## MEDIA POLICY

- GATE 9 media resolver remains authoritative for images, audio, video.
- Custom fonts referenced via `@font-face url(...)` are resolved as ordinary media (filename-only) when referenced by name; no private storage access.
- CSS `url()` references that resolve to filenames in Anki's media folder are served by GATE 9's shouldInterceptRequest.
- External http(s) URLs remain subject to GATE 9 security policy.
- Template source qfmt/afmt may reference media filenames; GATE 19 surfaces them as compatibility metadata (detected via regex) but does not pre-fetch or verify them.

## MODEL CHANGE HANDLING

- AnkiDroid does **not** expose a model modification time or version token.
- Model metadata may be cached in memory scoped by `(backendId, collectionKey, modelId)`.
- Cache is invalidated on:
  - Explicit user-initiated refresh.
  - Backend availability transitions (backend reconnected → refresh).
  - Note creation success (the post-create flow re-resolves the model name already).
  - Add Note screen re-entry.
- If a model schema read and a later use disagree (e.g., field count mismatch at note creation), the existing GATE 18 drift refusal already returns a typed error.
- No fabricated revision token.

## CAPABILITIES

Granular capability set added to `AnkiCapabilities`:

```
noteModelListing         → true on AnkiDroid (existing); false on PC
noteModelSchema          → true when getNoteModel(single) returns full schema incl. templates
noteTemplateListing      → true when templates endpoint readable
noteTemplateSourceRead   → true when qfmt/afmt readable
noteModelCssRead         → true when css column readable
noteModelCompatibility   → true when compatibility analyzer is wired
```

(Concrete boolean fields added to `AnkiCapabilities` with safe defaults of `false`.)

`getNoteModel(ref)` is added to the `AnkiBackend` interface for fetching a single enriched model (with template metadata). The existing `getNoteModels()` continues to return summary-level models (without the heavyweight template source); `getNoteModel(ref)` is the deep read.

## UNSUPPORTED FEATURES (explicit)

- Template editing (qfmt/afmt mutation)
- CSS editing
- Model creation
- Model deletion
- Model rename
- Field add/remove/rename/reorder mutation
- Template add/remove/reorder mutation
- Manual Cloze parser / local Cloze card generation
- Local Anki template engine
- Unsaved draft preview
- Browser template format (`bqfmt`/`bafmt`) editing (read if available, display only)
- PC backend model/template metadata (until protocol extended)
- Field RTL/font/size/sticky/description editing (not exposed publicly by AnkiDroid)
- Empty-card prediction logic

## RESIDUAL RISKS

1. **No model change token** — external edits to a model can make cached metadata stale until the next refresh. Mitigated by cache scoping + explicit refresh triggers.
2. **Template ordinal instability** — reordering templates can shift what card ord "means" for old notes. Mitigated by always reading template name alongside ordinal per card, and by not assuming ord = permanent identity across schema changes.
3. **JavaScript compatibility** — cards using AnkiDroidJsAPI/pycmd will not have their scripted features; they render as static HTML. Surfaced via compatibility classification.
4. **Custom filter incompatibility** — non-standard template filters will appear as literal text in qfmt/afmt source but the backend still renders them authoritatively on existing cards; only the source view shows unknown filter syntax.
5. **Backend differences** — PC backend provides no model/template surface; model/template UI is AnkiDroid-only.
6. **Draft preview unavailable** — Add Note cannot show a live card preview before save; users see field editors only until they save, then view the real rendered card.

## IMPLEMENTATION AUTHORIZED: YES

All locked decisions derive from observed backend facts in Phase A. Any behavior not justified by Phase A evidence is classified UNSUPPORTED or UNKNOWN rather than guessed.
