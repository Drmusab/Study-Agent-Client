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
