package com.studyagent.client.core.anki

/**
 * GATE 19 — read-only card-template domain types (templates, compatibility, metadata).
 *
 * These types model what a backend can honestly expose about a note type's card templates and
 * model-level CSS after Contract Lock (docs/GATE_19_CONTRACT_LOCK.md). They are strictly
 * read-only and serve inspection/UI/compatibility-diagnosis purposes only:
 *
 * - Template source (qfmt/afmt) is inspection metadata, never a mandate to render locally
 *   (INV-19-04/12). Card rendering remains the backend's exclusive job (GATE 07/08/09).
 * - Cloze parsing, card generation and filter evaluation remain Anki's exclusive ownership
 *   (INV-19-05/06).
 * - No mutation, no template/model editing, no CSS editing, no local template engine.
 */

/** Stable backend-qualified identity of one card template within a note type. */
data class AnkiCardTemplateRef(
    val modelRef: AnkiNoteModelRef,
    /** 0-based positional ordinal within the model's template list. */
    val ordinal: Int,
    val collectionKey: String? = null
) {
    init {
        require(ordinal >= 0) { "Template ordinal starts at zero" }
        require(collectionKey == null || modelRef.collectionKey == null ||
            collectionKey == modelRef.collectionKey) {
            "Template collection must match model collection"
        }
    }

    val backendId: AnkiBackendId get() = modelRef.backendId
    val stableKey: String get() =
        "anki:${backendId.stableId}:${collectionKey ?: modelRef.collectionKey ?: "?"}:model:${modelRef.modelId}:template:$ordinal"
}

/**
 * Read-only, backend-authoritative metadata for one card template.
 *
 * - [name] is display-only; never identity.
 * - [qfmt]/[afmt] are raw source strings when the backend exposes them, null otherwise. They are
 *   NEVER rendered by Study-Agent (INV-19-12).
 * - [targetDeckId] is the template's override deck id (AnkiDroid `template.did`); `null` when the
 *   backend does not expose one or when the template uses the model default. Display only — cards
 *   are placed by Anki's generation logic.
 * - [browserQfmt]/[browserAfmt] are the browser-specific format strings when exposed; null when
 *   unavailable (GATE 19 discovery classified these UNKNOWN at the pin — they are optionally
 *   read without failing the load).
 */
data class AnkiCardTemplateMetadata(
    val ref: AnkiCardTemplateRef,
    val name: String,
    val qfmt: String? = null,
    val afmt: String? = null,
    val targetDeckId: String? = null,
    val browserQfmt: String? = null,
    val browserAfmt: String? = null
) {
    init {
        require(name.isNotBlank()) { "A template needs a name" }
    }

    /** True when this template's raw source is available for inspection/compatibility analysis. */
    val hasSource: Boolean get() = qfmt != null || afmt != null
}

/**
 * Compatibility-level classification for a model/template/card (CONTRACT LOCK: Compatibility).
 *
 * These are UI/diagnostic labels derived from static inspection of raw template source; they
 * never drive rendering changes and never authorize weakening the GATE 8/9 security policy.
 */
enum class AnkiCompatibilityLevel {
    /** Renders with standard HTML/CSS only; no JS, no external deps, no unusual constructs. */
    FULL,

    /** Renders through GATE 8/9 within existing policy (JS under CARD_TEMPLATE_ONLY, media resolved by GATE 9). */
    SUPPORTED_WITH_LIMITATIONS,

    /** Card HTML displays but some interactive/dynamic features cannot work (e.g., AnkiDroid JS API, custom bridges). */
    DISPLAY_ONLY,

    /** Cannot be displayed safely or correctly under current policy (e.g., requires private storage access). */
    UNSUPPORTED,

    /** Inspection could not classify the template/card. Treated as SUPPORTED_WITH_LIMITATIONS but labelled unknown. */
    UNKNOWN
}

/** Read-only compatibility signals discovered by static inspection of template source/CSS. */
data class AnkiTemplateCompatibility(
    val level: AnkiCompatibilityLevel,
    val hasJavaScript: Boolean = false,
    val hasAnkiBridge: Boolean = false,
    val hasClozeDirective: Boolean = false,
    val hasFrontSide: Boolean = false,
    val hasConditionals: Boolean = false,
    val hasTypeAnswer: Boolean = false,
    val hasHintField: Boolean = false,
    val hasMediaReferences: Boolean = false,
    val hasLatex: Boolean = false,
    val hasMathJax: Boolean = false,
    val hasCustomFonts: Boolean = false,
    val hasExternalUrls: Boolean = false,
    val hasRtlSignals: Boolean = false,
    /** Content-free diagnostic tokens (stable, never source text). */
    val notes: List<String> = emptyList()
) {
    init {
        require(notes.none(String::isBlank))
    }

    companion object {
        /** No source available: cannot classify. */
        val NO_SOURCE = AnkiTemplateCompatibility(
            level = AnkiCompatibilityLevel.UNKNOWN,
            notes = listOf("template_source_unavailable")
        )
    }
}

/**
 * GATE 19 — the enriched read-only model schema, extending the GATE 18 creation schema with
 * template metadata and model-level CSS where the backend exposes them.
 *
 * This object is returned by [AnkiBackend.getNoteModel] (single-model deep read). The existing
 * [AnkiNoteModel] continues to be returned by [AnkiBackend.getNoteModels] (lightweight listing);
 * callers that need templates/CSS request the enriched variant explicitly.
 *
 * - [templates] is in backend-authoritative order; ordinals are dense 0..N-1.
 * - [css] is the raw model-level stylesheet when readable, null otherwise. It is inspection
 *   metadata — GATE 08's renderer does NOT inject it; existing cards render whatever CSS the
 *   backend embedded in the rendered HTML.
 * - [sortFieldIndex] is the 0-based index of the sort field when exposed, null otherwise.
 * - [noteCount] is informational when exposed; null when unavailable. Never treated as authoritative.
 * - [latexPreamble]/[latexSvg] are inspection metadata when exposed.
 */
data class AnkiNoteModelEnriched(
    val model: AnkiNoteModel,
    val templates: List<AnkiCardTemplateMetadata>,
    val css: String? = null,
    val sortFieldIndex: Int? = null,
    val noteCount: Int? = null,
    val latexPreamble: String? = null,
    val latexSvg: Boolean? = null
) {
    init {
        // Template ordinals must be dense starting at 0 and match their list position.
        require(templates.map { it.ref.ordinal } == templates.indices.toList()) {
            "Template ordinals must be dense and start at zero"
        }
        require(templates.all { it.ref.modelRef == model.ref }) {
            "Every template must belong to the enclosing model"
        }
        require(templates.distinctBy { it.ref.ordinal }.size == templates.size) {
            "Template ordinals must be unique"
        }
        require(sortFieldIndex == null || sortFieldIndex in model.fields.indices) {
            "Sort field index must reference a real field"
        }
        require(noteCount == null || noteCount >= 0)
    }

    val ref: AnkiNoteModelRef get() = model.ref
    val name: String get() = model.name
    val kind: AnkiNoteModelKind get() = model.kind
    val fields: List<AnkiNoteModelField> get() = model.fields
    val templateCount: Int get() = templates.size
}
