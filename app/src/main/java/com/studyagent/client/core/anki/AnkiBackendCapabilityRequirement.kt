package com.studyagent.client.core.anki

/**
 * GATE 21 — one thing an operation needs from a backend (IMPLEMENTATION-21-03/07).
 *
 * The point of this enum is that an operation names *what it needs*, and backend selection answers
 * *who can do it* — so no screen ever asks "is this AnkiDroid?" (AUDIT-21-13). Every entry maps to
 * exactly one field of [AnkiCapabilities]; a capability that does not exist yet is not invented
 * here, it is added to [AnkiCapabilities] first (defaulting to `false`, which is the safe answer).
 *
 * Note the deliberate separation the capabilities themselves already make: [REVIEW] is the complete
 * rating loop, [SCHEDULED_REVIEW] is asking the scheduler without writing, [EDIT_NOTE_FIELDS] is not
 * the coarse `editNotes` flag, and [STORE_MEDIA] is a public media write rather than a filesystem
 * shortcut (GATE 18 INV-18-11/12).
 */
enum class AnkiRequiredCapability {
    /** Read-only deck listing (GATE 05) — the minimum for a Library/browse context. */
    DECKS,

    /** Verified due counts; without it counts stay advisory/nullable (GATE 05 §13/§45). */
    DECK_COUNTS,

    /** Ask the backend's scheduler for the next card, without committing ratings (GATE 06). */
    SCHEDULED_REVIEW,

    /** The complete review loop: due cards **and** rating commits (GATE 01 §16). */
    REVIEW,

    /** Backend-normalized card content for presentation (GATE 07). */
    RENDERED_CARDS,

    /** Read-only card-browser query surface (GATE 15). */
    CARD_BROWSER,

    /** Deep read-only details of one card (GATE 16). */
    CARD_DETAILS,

    /** Reviewer actions: flag mutation (GATE 13). */
    FLAGS,

    /** Reviewer actions: bury (GATE 13). */
    BURY,

    /** Reviewer actions: suspend (GATE 13). */
    SUSPEND_CARDS,

    /** Replace one note's field values through the backend's public write path (GATE 17). */
    EDIT_NOTE_FIELDS,

    /** Replace one note's full tag set (GATE 17). */
    EDIT_NOTE_TAGS,

    /** Move one card to another existing, non-filtered deck (GATE 17). */
    CHANGE_CARD_DECK,

    /** Note-type listing with the authoritative creation schema (GATE 18). */
    NOTE_MODEL_LISTING,

    /** Enriched note-type schema: templates, CSS, LaTeX preamble (GATE 19). */
    NOTE_MODEL_SCHEMA,

    /** Per-template metadata (name, qfmt/afmt, deck override) (GATE 19). */
    NOTE_TEMPLATE_LISTING,

    /** Read media through the backend's public resolver. */
    MEDIA_READ,

    /** Store one media file through the backend's PUBLIC media API (GATE 18 CONTRACT-18-09). */
    STORE_MEDIA,

    /** Create one note (GATE 18). */
    CREATE_NOTE
}

/** One mapping table, in one place, so a capability and its requirement cannot drift apart. */
fun AnkiCapabilities.supports(required: AnkiRequiredCapability): Boolean = when (required) {
    AnkiRequiredCapability.DECKS -> deckListing
    AnkiRequiredCapability.DECK_COUNTS -> deckCounts
    AnkiRequiredCapability.SCHEDULED_REVIEW -> scheduledReview
    AnkiRequiredCapability.REVIEW -> review
    AnkiRequiredCapability.RENDERED_CARDS -> renderedCards
    // "Can browse" = at least one scope the backend can serve authoritatively; `NONE` (the safe
    // default) advertises neither, so a backend that never claimed a browser is never selected for one.
    AnkiRequiredCapability.CARD_BROWSER ->
        cardBrowser.canBrowseAllCards || cardBrowser.canBrowseDeck
    AnkiRequiredCapability.CARD_DETAILS -> cardDetails
    AnkiRequiredCapability.FLAGS -> flags
    AnkiRequiredCapability.BURY -> bury
    AnkiRequiredCapability.SUSPEND_CARDS -> suspendCards
    AnkiRequiredCapability.EDIT_NOTE_FIELDS -> editNoteFields
    AnkiRequiredCapability.EDIT_NOTE_TAGS -> editNoteTags
    AnkiRequiredCapability.CHANGE_CARD_DECK -> changeCardDeck
    AnkiRequiredCapability.NOTE_MODEL_LISTING -> noteModelListing
    AnkiRequiredCapability.NOTE_MODEL_SCHEMA -> noteModelSchema
    AnkiRequiredCapability.NOTE_TEMPLATE_LISTING -> noteTemplateListing
    AnkiRequiredCapability.MEDIA_READ -> media
    AnkiRequiredCapability.STORE_MEDIA -> storeMedia
    AnkiRequiredCapability.CREATE_NOTE -> createNotes
}

/**
 * The capability set a *rating-writing* study session must have before it may be locked.
 *
 * Single source of truth for [AnkiBackendSelector.REVIEW_REQUIREMENT]: the selector reads this
 * constant rather than restating the literal, so the documented policy and the enforced policy
 * cannot drift apart (AUDIT-21-13).
 *
 * DECKS is deliberately NOT here. A locked study session reads its next card by [AnkiCardRef] from
 * the backend it is already locked to, so the rating loop never needs deck listing. Requiring DECKS
 * would narrow which backends may hold a study session, and GATE 21 does not authorize changing
 * that — the selector's GATE 01 selection behaviour is preserved exactly (DECISION-21-11).
 */
val STUDY_SESSION_REQUIREMENTS: Set<AnkiRequiredCapability> =
    setOf(AnkiRequiredCapability.REVIEW)

/**
 * GATE 21K — the capability set a backend must have before a *new* creation context may bind to it
 * (DECISION-21B-21, the `STORE_MEDIA`/`CREATE_NOTE` row of the ownership matrix).
 *
 * Used exactly twice, both before binding: to seed the Add Note route from GATE 21E resolution,
 * and as the creation screen's exact-context requirement. It is never consulted after a creation
 * context binds, and it never redirects an admitted creation.
 *
 * `STORE_MEDIA` is deliberately NOT here. A field-only creation must stay possible on a backend
 * that cannot store media; media support is required per-draft at admission, where the coordinator
 * refuses a media-bearing draft without it instead of silently dropping the attachment.
 * `DECKS` is present because the creation screen reads deck names to display the model's own
 * backend-determined destination (creation takes no caller deck at the pinned contract).
 */
val CREATION_CONTEXT_REQUIREMENTS: Set<AnkiRequiredCapability> = setOf(
    AnkiRequiredCapability.NOTE_MODEL_LISTING,
    AnkiRequiredCapability.CREATE_NOTE,
    AnkiRequiredCapability.DECKS
)
