package com.studyagent.client.data.anki.ankidroid

/**
 * GATE 02 — the pinned AnkiDroid *public* integration contract.
 *
 * Study-Agent talks to AnkiDroid the way AnkiDroid documents it: through the exported
 * `CardContentProvider`, discovered by authority, guarded by the permission the app
 * declares, and versioned by the provider spec the app publishes as provider metadata.
 * Nothing else about AnkiDroid is depended on, read or copied — no AnkiDroid classes are
 * linked into this app, no AnkiDroid files are opened, no SQLite is touched, and no
 * debug authority leaks into release behavior (GATE 02 §3/§4/§7/§10).
 *
 * ## Provenance of every constant below (verified against sources, not memory)
 *
 * AnkiDroid release pinned for this gate: **v2.24.1** (`api` module version `2.0.0`).
 *
 * | Fact | Value | Verified from |
 * |---|---|---|
 * | Release authority | `com.ichi2.anki.flashcards` | `api/build.gradle.kts` `buildConfigField("String", "AUTHORITY", "\"com.ichi2.anki.flashcards\"")` |
 * | Release permission | `com.ichi2.anki.permission.READ_WRITE_DATABASE` | same file, `READ_WRITE_PERMISSION` buildConfigField |
 * | Debug authority | `com.ichi2.anki.debug.flashcards` | same file, `buildTypes { debug { … } }` |
 * | Debug permission | `com.ichi2.anki.debug.permission.READ_WRITE_DATABASE` | same file, `buildTypes { debug { … } }` |
 * | Provider spec metadata key | `com.ichi2.anki.provider.spec` | `AnkiDroid/src/main/AndroidManifest.xml` `<provider>` `<meta-data>`; read by `AddContentApi.PROVIDER_SPEC_META_DATA_KEY` |
 * | Spec metadata value for this release | `2` | `AnkiDroid/src/main/AndroidManifest.xml` `<meta-data android:value="2" />` |
 * | Authority template | `${applicationId}.flashcards` | `AnkiDroid/src/main/AndroidManifest.xml` `<provider android:authorities="${applicationId}.flashcards">` |
 * | Spec fallback when metadata is absent | `1` | `AddContentApi.DEFAULT_PROVIDER_SPEC_VALUE = 1 // for when meta-data key does not exist` |
 * | Permission is enforced *dynamically* by the provider | `SecurityException("Permission not granted for: …")` | `CardContentProvider.throwSecurityException`, reached from `query()` when `!hasReadWritePermission()` |
 * | Permission protection level | `dangerous` | `AnkiDroid/src/main/AndroidManifest.xml` `<permission android:protectionLevel="dangerous">` |
 * | Consumer manifest merge | the api module ships `<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE"/>` | `api/src/main/AndroidManifest.xml` |
 * | Cheapest bounded read | `content://<authority>/selected_deck` → **one** row | `CardContentProvider.query` `DECK_SELECTED -> { … MatrixCursor(columns, 1) }` |
 * | `selected_deck` projection column used for the probe | `deck_id` | `FlashCardsContract.Deck.DECK_ID = "deck_id"` |
 *
 * ## Why the provider, not the package (GATE 02 §9/§32/§62)
 *
 * The package name is a *consequence* of detection, never its input:
 * `PackageManager.resolveContentProvider(authority, GET_META_DATA)` returns the actual
 * `ProviderInfo` — its `packageName`, its `enabled` flag and its metadata. That is the same
 * mechanism AnkiDroid's own API uses (`AddContentApi.getAnkiDroidPackageName()` returns
 * `resolveContentProvider(AUTHORITY, 0)?.packageName`), and it keeps working for builds whose
 * application id is not the one this app expects, because the authority is what is looked up.
 *
 * ## Why there is no compile-time AnkiDroid artifact (documented decision, GATE 02 §4/§5)
 *
 * The official artifact was evaluated and is *not* linked in this gate; the full evaluation
 * (coordinates, JitPack build status, license, packaging risks, the snippet to adopt it later)
 * lives in `docs/ANKIDROID_INTEGRATION.md` §2. The short version: this integration is
 * dynamic app-to-app IPC, every value it needs is a public contract string that is verified
 * above against the pinned release, and no AnkiDroid code is copied or linked in either case.
 */
object AnkiDroidApiContract {

    /** Release AnkiDroid application id (the value the release authority is derived from). */
    const val RELEASE_PACKAGE: String = "com.ichi2.anki"

    /** Release provider authority (`${applicationId}.flashcards`). */
    const val RELEASE_AUTHORITY: String = "com.ichi2.anki.flashcards"

    /** Release permission AnkiDroid declares and its provider enforces. */
    const val RELEASE_PERMISSION: String = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    /**
     * Debug-build AnkiDroid application id. Only ever used by debug environments
     * (GATE 02 §10); release logic resolves [AnkiDroidEndpoints.RELEASE] alone.
     */
    const val DEBUG_PACKAGE: String = "com.ichi2.anki.debug"

    /** Debug-build authority (`${applicationId}.flashcards` of the debug variant). */
    const val DEBUG_AUTHORITY: String = "com.ichi2.anki.debug.flashcards"

    /** Debug-build permission. Never part of a release endpoint set. */
    const val DEBUG_PERMISSION: String = "com.ichi2.anki.debug.permission.READ_WRITE_DATABASE"

    /** Provider metadata key that carries the API/provider spec version. */
    const val PROVIDER_SPEC_METADATA_KEY: String = "com.ichi2.anki.provider.spec"

    /** URI path segment of the cheapest bounded read (exactly one row). */
    const val SELECTED_DECK_PATH: String = "selected_deck"

    /** Projection column of that read; a recognized column, so the row shape is well defined. */
    const val SELECTED_DECK_COLUMN: String = "deck_id"

    // ------------------------------------------------------------------------------------------
    // GATE 05 — deck listing contract (`FlashCardsContract.Deck`, verified at v2.24.1)
    //
    // | Fact | Value | Verified from |
    // |---|---|---|
    // | Deck list URI | `content://<authority>/decks` (one row per deck, nested decks included) | `FlashCardsContract.Deck.CONTENT_ALL_URI`; `CardContentProvider.query` `DECKS -> col.sched.deckDueTree().forEach { addDeckToCursor(it.did, it.fullDeckName, …) }` |
    // | Selected deck URI | `content://<authority>/selected_deck` (exactly one row) | `Deck.CONTENT_SELECTED_URI`; `DECK_SELECTED -> col.decks.selected()` |
    // | `deck_id` | `long`, read-only, "unique ID of the Deck" | `Deck.DECK_ID` + KDoc table |
    // | `deck_name` | `String`, full name, `::`-separated for nested decks | `Deck.DECK_NAME`; provider passes `DeckNode.fullDeckName` |
    // | `deck_count` | `JSONArray` **`[learn, review, new]`**, read-only | `Deck.DECK_COUNTS` KDoc table; `getDeckCountsFromDueTreeNode` puts `lrnCount, revCount, newCount` in that order |
    // | `deck_dyn` | `Boolean` ("whether or not the deck is a filtered deck") | `Deck.DECK_DYN`; provider adds `col.decks.isFiltered(id)` |
    // | `deck_desc` | `String` | `Deck.DECK_DESC` — **not consumed**: the provider adds `col.decks.current().description`, i.e. the *selected* deck's description on every row |
    // | `options` | `JSONObject` deck config | `Deck.OPTIONS` — **not consumed** (raw options JSON never travels upward, §36) |
    // | Unknown projection column | silently skipped by `addDeckToCursor` (`when` without `else`) → later cells shift left | `CardContentProvider.addDeckToCursor` — the reason only recognized columns are ever requested |
    // | Transport of non-numeric cells | `Boolean`/`JSONArray` cross the process boundary as their `toString()` | Android `CursorWindow`/`DatabaseUtils.cursorFillWindow` (`getTypeOfObject` → STRING) |
    // | `selected_deck` counts | `JSONArray(listOf(col.sched.counts()))` — a *nested* array, not `[learn, review, new]` | `DECK_SELECTED` branch — the reason counts are never read from that row |
    // | No parent ID, no collection ID, no per-deck "selected" flag in the contract | — | `FlashCardsContract.Deck` columns above are the complete set |
    // ------------------------------------------------------------------------------------------

    /** URI path of the deck list (`Deck.CONTENT_ALL_URI`). */
    const val DECKS_PATH: String = "decks"

    /** `Deck.DECK_ID` — the backend-issued deck identity (never the name, INV-ANKI-DECK-01). */
    const val DECK_ID_COLUMN: String = "deck_id"

    /** `Deck.DECK_NAME` — full name; nested decks use [DECK_NAME_SEPARATOR]. */
    const val DECK_NAME_COLUMN: String = "deck_name"

    /** `Deck.DECK_COUNTS` — JSON array `[learn, review, new]`, transported as text. */
    const val DECK_COUNTS_COLUMN: String = "deck_count"

    /** `Deck.DECK_DYN` — filtered-deck flag, transported as `"true"`/`"false"` text. */
    const val DECK_DYN_COLUMN: String = "deck_dyn"

    /** Anki's hierarchy separator inside a full deck name (`Parent::Child::Grandchild`). */
    const val DECK_NAME_SEPARATOR: String = "::"

    /**
     * The only columns GATE 05 asks the provider for. Deliberately excludes `options` (heavy,
     * never consumed) and `deck_desc` (wrong value per row, see table). Never add a column that
     * the pinned contract does not define: the provider skips unknown names and shifts the row.
     */
    val DECK_LIST_PROJECTION: Array<String> = arrayOf(
        DECK_ID_COLUMN,
        DECK_NAME_COLUMN,
        DECK_COUNTS_COLUMN,
        DECK_DYN_COLUMN
    )

    /** Projection of the selected-deck read: identity only (its counts cell is not `[l, r, n]`). */
    val SELECTED_DECK_PROJECTION: Array<String> = arrayOf(
        DECK_ID_COLUMN,
        DECK_NAME_COLUMN
    )

    // ------------------------------------------------------------------------------------------
    // GATE 06 — scheduled-review contract (review-info endpoint, verified at v2.24.1)
    //
    // | Fact | Value | Verified from |
    // |---|---|---|
    // | Endpoint URI | `content://<authority>/schedule` | `FlashCardsContract.ReviewInfo.CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "schedule")`; `CardContentProvider` `addUri("schedule/", SCHEDULE = 3000)` |
    // | Query arguments | `selection` string `"limit=?, deckID=?"`; `?` consumes `selectionArgs` **in order** | `CardContentProvider.query` `SCHEDULE` branch: `selection.split(",")` → `arg.split("=")` → placeholder pulled from `selectionArgs[selectionArgIndex++]` |
    // | Unrecognised argument key | silently ignored (no error) | same branch: `if ("limit" == …) … else if ("deckID" == …)` — no `else` |
    // | Unparseable argument value | `NumberFormatException` caught + logged, previous default kept | same branch: `catch (nfe: NumberFormatException) { Timber.w(nfe) }` |
    // | `limit` default | `1` | same branch: `var limit = 1` |
    // | `limit` meaning | maximum number of **rows** returned | `FlashCardsContract.ReviewInfo` KDoc: "The maximum number of cards (rows) that will be returned"; implemented as `getQueuedCards(fetchLimit = limit)` then `while (k < limit)` |
    // | `deckID` semantics | the deck the queue is drawn from; **absent** → AnkiDroid's currently selected deck | KDoc default column: "The deck, that was last selected for reviewing by the user in the Deck chooser dialog" |
    // | Unknown `deckID` | provider returns an **empty cursor** (indistinguishable from "nothing due") | `if (!selectDeckWithCheck(col, deckId)) return rv` |
    // | Query side effect | temporarily selects `deckID`, then restores the previous selection | `col.decks.select(deckIdOfTemporarilySelectedDeck)` … `col.decks.select(selectedDeckBeforeQuery)`; `Collection.set_current_deck` is a transacted `Op::SetCurrentDeck` config write |
    // | Restore is skipped | if anything throws between the two `select` calls | the restore is the last statement of the branch, not a `finally` |
    // | `note_id` | `long`, read-only; the note the row belongs to | `ReviewInfo.NOTE_ID = "note_id"`; matched in `addReviewInfoToCursor` via `Card.NOTE_ID` (same string) |
    // | `ord` | `int`, read-only; the card ordinal within the note | `ReviewInfo.CARD_ORD = "ord"` |
    // | `button_count` | `int`, read-only, documented range 2..4 | `ReviewInfo.BUTTON_COUNT`; **the v2.24.1 provider hard-codes `val buttonCount = 4`** |
    // | `next_review_times` | `JSONArray` of interval labels, transported as text; "must equal the number of buttons" | `ReviewInfo.NEXT_REVIEW_TIMES`; `rb.add(nextReviewTimesJson.toString())` |
    // | Interval label order | index `i` ← `nextIvlStr(card, CardAnswer.Rating.forNumber(i))` for `i` in `0 until buttonCount` | `CardContentProvider.query` `SCHEDULE` branch; `CardAnswer.Rating` is `AGAIN = 0, HARD = 1, GOOD = 2, EASY = 3` (`proto/anki/scheduler.proto`) ⇒ index 0..3 = again/hard/good/easy |
    // | `media_files` | `JSONArray` of filenames, transported as text | `ReviewInfo.MEDIA_FILES`; `rb.add(JSONArray(col.media.filesInStr(currentCard)))` |
    // | Unknown projection column | **throws** `UnsupportedOperationException("Queue \"<col>\" is unknown")` | `addReviewInfoToCursor` — unlike `addDeckToCursor`, there is no silent skip |
    // | Provider spec gate | none | `CardContentProvider` v2.24.1 contains no `requireApiLevel` call at all |
    // | Permission | same `READ_WRITE_PERMISSION`, enforced on `query` | `if (!hasReadWritePermission() && shouldEnforceQueryOrInsertSecurity()) throwSecurityException("query", uri)` |
    // | Rating mutation | lives on `update`, **not** `query` | `update` `SCHEDULE` branch reads `answer_ease`/`time_taken`/`buried`/`suspended` and calls `col.sched.answerCard` — GATE 06 never issues an `update` |
    // | No wrapper in the api artifact | `AddContentApi` exposes no schedule/`getSched` member at v2.24.1 | read of `api/src/main/java/com/ichi2/anki/api/AddContentApi.kt` in full; the api module contains only `AddContentApi`, `BasicModel`, `Basic2Model`, `Ease`, `NoteInfo`, `Utils` |
    // ------------------------------------------------------------------------------------------

    /** URI path of the scheduled-review endpoint (`ReviewInfo.CONTENT_URI`). */
    const val SCHEDULE_PATH: String = "schedule"

    /** Review row column: the note the card belongs to. */
    const val REVIEW_NOTE_ID_COLUMN: String = "note_id"

    /** Review row column: the card ordinal inside that note. */
    const val REVIEW_CARD_ORD_COLUMN: String = "ord"

    /** Review row column: how many rating buttons the scheduler offers. */
    const val REVIEW_BUTTON_COUNT_COLUMN: String = "button_count"

    /** Review row column: backend-rendered interval labels, one per button. */
    const val REVIEW_NEXT_REVIEW_TIMES_COLUMN: String = "next_review_times"

    /** Review row column: filenames of the media referenced by the card. */
    const val REVIEW_MEDIA_FILES_COLUMN: String = "media_files"

    /** Selection key that scopes the queue to one deck. */
    const val REVIEW_DECK_ID_SELECTION_KEY: String = "deckID"

    /** Selection key that bounds how many rows come back. */
    const val REVIEW_LIMIT_SELECTION_KEY: String = "limit"

    /** Separator between selection entries (`"limit=?, deckID=?"`). */
    const val REVIEW_SELECTION_ENTRY_SEPARATOR: String = ","

    /** Separator between a selection key and its value. */
    const val REVIEW_SELECTION_KEY_VALUE_SEPARATOR: String = "="

    /** The placeholder whose value is taken from `selectionArgs`, in order. */
    const val REVIEW_SELECTION_PLACEHOLDER: String = "?"

    /** The provider's own default when `limit` is not supplied. */
    const val REVIEW_DEFAULT_LIMIT: Int = 1

    /**
     * Upper bound this build will ever ask for. The endpoint is bounded on purpose: Study-Agent
     * retrieves one scheduled card at a time so that no stale queue is ever held locally
     * (§13/§14/§39/§40/§79). `limit = 0` is additionally rejected rather than forwarded — Anki's
     * queue builder applies it as `take(0)`, i.e. it answers "nothing is due", which can never be
     * the meaning of a zero limit (§41/§42/§78).
     */
    const val REVIEW_MAX_LIMIT: Int = 32

    /** The provider's documented button-count range. Anything outside it is not a real answer. */
    const val REVIEW_MIN_BUTTON_COUNT: Int = 2
    const val REVIEW_MAX_BUTTON_COUNT: Int = 4

    /**
     * Projection of the scheduled-review read. Only columns the endpoint recognises may appear
     * here: an unknown name makes the provider throw instead of shifting the row (see the table).
     */
    val REVIEW_PROJECTION: Array<String> = arrayOf(
        REVIEW_NOTE_ID_COLUMN,
        REVIEW_CARD_ORD_COLUMN,
        REVIEW_BUTTON_COUNT_COLUMN,
        REVIEW_NEXT_REVIEW_TIMES_COLUMN,
        REVIEW_MEDIA_FILES_COLUMN
    )

    // ------------------------------------------------------------------------------------------
    // GATE 07 — card-content contract (`FlashCardsContract.Card`, verified at v2.24.1)
    //
    // | Fact | Value | Verified from |
    // |---|---|---|
    // | Card-by-id URI | `content://<authority>/cards/<cardId>` | `addUri("cards/#", CARD_ID)`; the query branch reads `uri.pathSegments[1].toLong()` as the **card id** and calls `col.getCard(cardId)` |
    // | Card-by-note+ord URI | `content://<authority>/notes/<noteId>/cards/<ord>` | `addUri("notes/#/cards/#", NOTES_ID_CARDS_ORD)`; `getCardFromUri` reads `pathSegments[1]` as note id and `pathSegments[3]` as **ordinal** |
    // | Missing card (by id) | `col.getCard` raises `BackendNotFoundException` | `libanki Collection.getCard`: "@throws BackendNotFoundException if the card does not exist" |
    // | Missing card (by note+ord) | `IllegalArgumentException("Card with ord $ord does not exist for note $noteId")` | `CardContentProvider.getCard(noteId, ord, col)`; a missing *note* raises the same `BackendNotFoundException` family via `col.getNote` |
    // | Projection honoured | `MatrixCursor(columns, 1)` is built from the caller's projection; every requested column is filled or the endpoint **throws** `UnsupportedOperationException("Queue \"<col>\" is unknown")` | `addCardToCursor` — same strictness as the review endpoint |
    // | `_id` (`Card._ID`) | `long`, the card id (`currentCard.id`) | `addCardToCursor` |
    // | `note_id` (`Card.NOTE_ID`) | `long`, `currentCard.nid` | same |
    // | `ord` (`Card.CARD_ORD`) | `int`, `currentCard.ord` | same |
    // | `card_name` (`Card.CARD_NAME`) | `String`, the card *template display name* (`card.template(col).name`) — **never identity** (STEP 27); note it is computed even when not projected, and an invalid template makes the whole row throw `IllegalArgumentException("Card is using an invalid template")` | same |
    // | `deck_id` (`Card.DECK_ID`) | the card's **current** deck id (`currentCard.did`) | same |
    // | `original_deck_id` (`Card.ORIGINAL_DECK_ID`) | `oDid` — the *home* deck while in a filtered deck, `0` otherwise | same + contract KDoc |
    // | `question` (`Card.QUESTION`) | **rendered question** = `renderOutput().questionText` with `[anki:play:q:n]` AV refs restored to `[sound:…]` (`replaceWithSoundTags`) — HTML fragment, no note-type `<style>` block (that is `Card.question()`, which the provider does not use) | `addCardToCursor` + `Sound.replaceWithSoundTags` |
    // | `answer` (`Card.ANSWER`) | **rendered answer** = `renderOutput().answerText` with sound tags restored. By template convention this *includes* the question side (`{{FrontSide}}`) followed by `<hr id=answer>` and the answer — the answer DOES contain question content | `addCardToCursor` + `TemplateManager` (`answerText`) |
    // | `question_simple` (`Card.QUESTION_SIMPLE`) | the raw `TemplateRenderOutput.questionText` — the backend's simplified question representation (contract KDoc: "without card styling information (CSS)"). Verified nuance at v2.24.1: it is `QUESTION` *minus* the `[anki:play:…] → [sound:…]` restoration; it is the closest backend-provided speech-side text but is **not guaranteed to be plain text** | `addCardToCursor` (`renderOutput(col).questionText`) |
    // | `answer_simple` (`Card.ANSWER_SIMPLE`) | the raw `TemplateRenderOutput.answerText` — full answer side (question duplication included when templates use `{{FrontSide}}`), no sound-tag restoration | `addCardToCursor` (`renderOutput(col, false).answerText`; `false` = no reload) |
    // | `answer_pure` (`Card.ANSWER_PURE`) | `pureAnswer()`: `answerText` with everything **before** `<hr id=answer>` (or `<hr id="answer">`) removed and the remainder trimmed — i.e. question-side content stripped. If the template emitted no `<hr id=answer>` marker, the WHOLE `answerText` is returned unchanged. NOT identical to `ANSWER_SIMPLE` (which keeps the question duplication) | `CardContentProvider.pureAnswer` |
    // | `reps` / `lapses` | `int`, stored counters (`currentCard.reps` / `.lapses`) | `addCardToCursor` |
    // | `interval` | `int`, stored interval `ivl` in **days** (0 for learning cards) | `addCardToCursor` + contract KDoc |
    // | `type` | `int` card-type code: `0` new, `1` learning, `2` review, `3` relearning — "other values should be treated as unknown" | `Card.TYPE` KDoc |
    // | `queue` (`Card.RAW_QUEUE`) | `int` queue code: `-3` manually buried, `-2` sibling buried, `-1` suspended, `0` new, `1` learning, `2` review, `3` day-learning/relearning, `4` preview — "other values should be treated as unknown" | `Card.RAW_QUEUE` KDoc |
    // | `fsrs_stability` / `fsrs_difficulty` / `fsrs_desired_retention` | `Float?` memory-state values; `null` when the card has no stored FSRS state. Informational only (INV-ANKI-CARD-19) | `addCardToCursor` (`memoryStateStability` / `memoryStateDifficulty` / `desiredRetention`) + KDoc |
    // | `last_review_time_secs` | `Long?`, Unix epoch **seconds** (`null` when never reviewed) | `Card.LAST_REVIEW_TIME_SECONDS` KDoc |
    // | **No flags column** | `FlashCardsContract.Card` at v2.24.1 exposes NO flag field (`addCardToCursor` fills no flags cell; the contract object's constants end at `last_review_time_secs`) — GATE 07 maps no flag and `AnkiRenderedCard.flag` stays null on this backend | full read of `FlashCardsContract.kt` + `addCardToCursor` |
    // | No media column, no tags column, no deck-name column | media names live on the review-info endpoint (`media_files`, GATE 06); tags live on the note surface (a separate query — deliberately not made, STEP 39/§40); deck names live on the deck surface (GATE 05) | contract column tables |
    // | Rendering authority | `question`/`answer`/`question_simple`/`answer_simple`/`answer_pure` are all produced by AnkiDroid's own template renderer (`TemplateManager` + rust backend), including cloze and `{{FrontSide}}` expansion. Study-Agent never expands `{{…}}` itself (INV-ANKI-CARD-08/09) | `addCardToCursor` calls `renderOutput`/`pureAnswer` |
    // | Not consumed (present but unused) | `due`, `original_due`, `sm2_factor`, `left`, `original_position`, `custom_data`, `fsrs_decay` — raw scheduler internals GATE 07 does not need (STEP 19); deliberately left out of the projection | STEP 14 |
    // | Read-only guarantee | the card query branches contain no writes; `update` on the card URIs only moves decks — GATE 07 never calls it, and a source scan enforces it | `CardContentProvider.update` + `AnkiDroidIntegrationIsolationTest` |
    // ------------------------------------------------------------------------------------------

    /** URI path of the card collection (`Card.CONTENT_URI`). */
    const val CARDS_PATH: String = "cards"

    /** URI path of the note collection — parent of `notes/<id>/cards[/<ord>]`. */
    const val NOTES_PATH: String = "notes"

    /** URI path segment of a note's card collection (`notes/<id>/cards`). */
    const val NOTE_CARDS_PATH: String = "cards"

    /** `Card._ID` — the backend-issued card id. */
    const val CARD_ID_COLUMN: String = "_id"

    /** `Card.NOTE_ID` — the note this card belongs to. */
    const val CARD_NOTE_ID_COLUMN: String = "note_id"

    /** `Card.CARD_ORD` — the card ordinal inside its note. */
    const val CARD_ORD_COLUMN: String = "ord"

    /** `Card.CARD_NAME` — template display name. Not identity (STEP 27). */
    const val CARD_NAME_COLUMN: String = "card_name"

    /** `Card.DECK_ID` — the card's current deck id. */
    const val CARD_DECK_ID_COLUMN: String = "deck_id"

    /** `Card.ORIGINAL_DECK_ID` — home deck while in a filtered deck, `0` otherwise. */
    const val CARD_ORIGINAL_DECK_ID_COLUMN: String = "original_deck_id"

    /** `Card.QUESTION` — rendered question with sound tags restored (visual channel). */
    const val CARD_QUESTION_COLUMN: String = "question"

    /** `Card.ANSWER` — rendered answer, question side included (visual channel). */
    const val CARD_ANSWER_COLUMN: String = "answer"

    /** `Card.QUESTION_SIMPLE` — backend's simplified question (speech channel). */
    const val CARD_QUESTION_SIMPLE_COLUMN: String = "question_simple"

    /** `Card.ANSWER_SIMPLE` — backend's simplified full answer (speech/clean channel). */
    const val CARD_ANSWER_SIMPLE_COLUMN: String = "answer_simple"

    /** `Card.ANSWER_PURE` — answer with question-side content removed (evaluation channel). */
    const val CARD_ANSWER_PURE_COLUMN: String = "answer_pure"

    /** `Card.REPS` — stored repetition counter. */
    const val CARD_REPS_COLUMN: String = "reps"

    /** `Card.LAPSES` — stored lapse counter. */
    const val CARD_LAPSES_COLUMN: String = "lapses"

    /** `Card.INTERVAL` — stored interval in days. */
    const val CARD_INTERVAL_COLUMN: String = "interval"

    /** `Card.TYPE` — stored card-type code (0..3; other values are unknown). */
    const val CARD_TYPE_COLUMN: String = "type"

    /** `Card.RAW_QUEUE` — stored queue code (−3..4; other values are unknown). */
    const val CARD_QUEUE_COLUMN: String = "queue"

    /** `Card.FSRS_STABILITY` — stored FSRS stability, `null` when absent. */
    const val CARD_FSRS_STABILITY_COLUMN: String = "fsrs_stability"

    /** `Card.FSRS_DIFFICULTY` — stored FSRS difficulty, `null` when absent. */
    const val CARD_FSRS_DIFFICULTY_COLUMN: String = "fsrs_difficulty"

    /** `Card.FSRS_DESIRED_RETENTION` — stored desired retention (fraction), `null` when absent. */
    const val CARD_FSRS_DESIRED_RETENTION_COLUMN: String = "fsrs_desired_retention"

    /** `Card.LAST_REVIEW_TIME_SECONDS` — last review as Unix epoch seconds, `null` when never. */
    const val CARD_LAST_REVIEW_TIME_COLUMN: String = "last_review_time_secs"

    /**
     * The only columns GATE 07 asks the card endpoint for (STEP 14): identity, the five content
     * representations, current/home deck, and the optional metadata this gate maps. Raw
     * scheduler internals (`due`, `sm2_factor`, `left`, …) and `custom_data` are deliberately
     * excluded (STEP 19). An unknown name would make the endpoint throw, so this list is also
     * the contract pin.
     */
    val CARD_PROJECTION: Array<String> = arrayOf(
        CARD_ID_COLUMN,
        CARD_NOTE_ID_COLUMN,
        CARD_ORD_COLUMN,
        CARD_NAME_COLUMN,
        CARD_DECK_ID_COLUMN,
        CARD_ORIGINAL_DECK_ID_COLUMN,
        CARD_QUESTION_COLUMN,
        CARD_ANSWER_COLUMN,
        CARD_QUESTION_SIMPLE_COLUMN,
        CARD_ANSWER_SIMPLE_COLUMN,
        CARD_ANSWER_PURE_COLUMN,
        CARD_REPS_COLUMN,
        CARD_LAPSES_COLUMN,
        CARD_INTERVAL_COLUMN,
        CARD_TYPE_COLUMN,
        CARD_QUEUE_COLUMN,
        CARD_FSRS_STABILITY_COLUMN,
        CARD_FSRS_DIFFICULTY_COLUMN,
        CARD_FSRS_DESIRED_RETENTION_COLUMN,
        CARD_LAST_REVIEW_TIME_COLUMN
    )

    /** Documented `type` codes (`Card.TYPE` KDoc). Anything else maps to `UNKNOWN`. */
    const val CARD_TYPE_NEW: Int = 0
    const val CARD_TYPE_LEARNING: Int = 1
    const val CARD_TYPE_REVIEW: Int = 2
    const val CARD_TYPE_RELEARNING: Int = 3

    /** Documented `queue` codes (`Card.RAW_QUEUE` KDoc). Anything else maps to `UNKNOWN`. */
    const val CARD_QUEUE_MANUALLY_BURIED: Int = -3
    const val CARD_QUEUE_SIBLING_BURIED: Int = -2
    const val CARD_QUEUE_SUSPENDED: Int = -1
    const val CARD_QUEUE_NEW: Int = 0
    const val CARD_QUEUE_LEARNING: Int = 1
    const val CARD_QUEUE_REVIEW: Int = 2
    const val CARD_QUEUE_DAY_LEARNING: Int = 3
    const val CARD_QUEUE_PREVIEW: Int = 4
}

/**
 * One addressable AnkiDroid integration endpoint.
 *
 * An endpoint is a *candidate*: it says "if this authority resolves, here is which package
 * should be serving it and which permission the caller needs". Detection resolves it at
 * runtime and treats a mismatch as evidence, not as success (GATE 02 §10/§32).
 */
data class AnkiDroidEndpoint(
    /** Content provider authority to resolve. */
    val authority: String,
    /** Application id that is expected to serve [authority] in a healthy installation. */
    val expectedPackage: String,
    /** Permission the caller must hold for the provider to answer. */
    val readWritePermission: String,
    /** Short, non-sensitive label used in diagnostics (for example `release`). */
    val label: String,
    /** True for endpoints that exist only in test/debug environments. */
    val debugOnly: Boolean = false
)

/**
 * The endpoints this build is allowed to look at.
 *
 * Release builds target the official AnkiDroid release endpoint only. Debug builds may also
 * look at the AnkiDroid *debug* endpoint (a separate application id, authority and permission),
 * which is how the integration is exercised against a locally installed debug AnkiDroid
 * without changing what a release build does (GATE 02 §10).
 */
object AnkiDroidEndpoints {

    val RELEASE: AnkiDroidEndpoint = AnkiDroidEndpoint(
        authority = AnkiDroidApiContract.RELEASE_AUTHORITY,
        expectedPackage = AnkiDroidApiContract.RELEASE_PACKAGE,
        readWritePermission = AnkiDroidApiContract.RELEASE_PERMISSION,
        label = "release"
    )

    val DEBUG: AnkiDroidEndpoint = AnkiDroidEndpoint(
        authority = AnkiDroidApiContract.DEBUG_AUTHORITY,
        expectedPackage = AnkiDroidApiContract.DEBUG_PACKAGE,
        readWritePermission = AnkiDroidApiContract.DEBUG_PERMISSION,
        label = "debug",
        debugOnly = true
    )

    /**
     * Endpoint search order for this build.
     *
     * The release endpoint is always first, so a device that has both an official AnkiDroid and
     * a debug build is reported against the official one. Debug endpoints are appended only when
     * the caller (the composition root, which knows the build type) asks for them.
     */
    fun forBuild(includeDebugEndpoints: Boolean): List<AnkiDroidEndpoint> =
        if (includeDebugEndpoints) listOf(RELEASE, DEBUG) else listOf(RELEASE)
}

/**
 * Provider spec policy (GATE 02 §15/§16): the *provider spec* is a capability contract,
 * deliberately distinct from the AnkiDroid marketing/app version.
 *
 * - [IMPLICIT_WHEN_METADATA_ABSENT] mirrors the official API's fallback
 *   (`AddContentApi.DEFAULT_PROVIDER_SPEC_VALUE = 1`). The fallback is only applied once the
 *   provider has actually resolved, so "metadata absent" is never mistaken for "AnkiDroid
 *   missing".
 * - [MIN_SUPPORTED_SPEC] is `1`: GATE 02 needs nothing more than "the provider exists, is
 *   permitted and can answer a bounded read". Rejecting older installs for a capability this
 *   gate does not use would take AnkiDroid away from users for no reason (§16).
 * - [MAX_VALIDATED_SPEC] is `2`: the highest spec whose contract this build has verified
 *   (`com.ichi2.anki.flashcards`, metadata value `2` on v2.24.1). A newer spec is reported as
 *   reachable but *not* validated — capabilities stay unclaimed rather than assumed (§95).
 */
object AnkiDroidProviderSpec {

    const val IMPLICIT_WHEN_METADATA_ABSENT: Int = 1

    const val MIN_SUPPORTED_SPEC: Int = 1

    const val MAX_VALIDATED_SPEC: Int = 2
}
