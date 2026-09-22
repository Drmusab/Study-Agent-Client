package com.studyagent.client.data.anki.ankidroid

/**
 * GATE 05 — platform-neutral view over one provider result row (INV-ANKI-DECK-02).
 *
 * The Android provider client adapts `android.database.Cursor` to this interface *inside*
 * `AnkiDroidProviderClient.kt`; every mapper above it (deck rows in GATE 05, card rows in later
 * gates) reads columns through this contract only, which is what keeps the deck gateway and its
 * mappers compilable and testable on the plain JVM while `Cursor` stays confined to the two
 * platform files (GATE 04 §4/§7, INV-ANKI-GW-01/02).
 *
 * A row is a *view*, valid only for the duration of the mapper callback — mappers copy the values
 * they need into domain objects and never retain the row.
 *
 * Type discipline (why there is no `getBoolean`/`getJson`): AnkiDroid's provider builds a
 * `MatrixCursor` and Android transports it through a `CursorWindow`, which stores anything that
 * is not a number or a byte array as its `toString()` — a `Boolean` arrives as `"true"`/`"false"`
 * and a `JSONArray` as `"[1,2,3]"`. Mappers therefore read such columns as strings and parse
 * them explicitly (see `AnkiDroidDeckMapper`), instead of trusting typed getters that would
 * silently return `0` or throw on the transported representation.
 */
interface AnkiDroidProviderRow {

    /** Column index of [name], or `-1` when the provider did not return that column. */
    fun columnIndex(name: String): Int

    fun isNull(index: Int): Boolean

    fun getLong(index: Int): Long

    fun getInt(index: Int): Int

    fun getString(index: Int): String?
}

/**
 * In-memory row used by JVM tests and fakes: column order is the map's insertion order, values
 * are exposed the way a transported cursor would expose them (`toString()` for non-numbers).
 * Lives in production sources only so that `FakeAnkiDroidProviderClient` can serve rows; it holds
 * no Android types and no behavior beyond the interface.
 */
class InMemoryProviderRow(values: Map<String, Any?>) : AnkiDroidProviderRow {

    private val columns: List<String> = values.keys.toList()
    private val cells: List<Any?> = values.values.toList()

    override fun columnIndex(name: String): Int = columns.indexOf(name)

    override fun isNull(index: Int): Boolean = cells.getOrNull(index) == null

    override fun getLong(index: Int): Long = when (val value = cells.getOrNull(index)) {
        null -> 0L
        is Number -> value.toLong()
        else -> value.toString().toLong()
    }

    override fun getInt(index: Int): Int = when (val value = cells.getOrNull(index)) {
        null -> 0
        is Number -> value.toInt()
        else -> value.toString().toInt()
    }

    override fun getString(index: Int): String? = cells.getOrNull(index)?.toString()
}
