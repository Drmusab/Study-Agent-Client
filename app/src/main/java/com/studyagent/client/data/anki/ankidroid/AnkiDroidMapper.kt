package com.studyagent.client.data.anki.ankidroid

/**
 * GATE 04 — mapper responsibility (§56), made platform-neutral in GATE 05.
 *
 * Converts provider row values into domain-ready values. No UI formatting, no voice
 * normalization, no AI transformation (§57/§58/§59). Reads go through [AnkiDroidProviderRow], so
 * these helpers — and every mapper built on them — compile and run on the plain JVM
 * (INV-ANKI-DECK-02); `android.database.Cursor` never appears here.
 *
 * Pattern (§55): strict for required identity fields, lenient for optional display fields.
 * The helpers that *throw* ([requireColumnIndex], [getRequiredLong], [getRequiredString]) are for
 * structural problems where the whole query is unusable; per-row policies (skip a malformed row,
 * keep the rest) are expressed by mappers returning outcome values — see `AnkiDroidDeckMapper`.
 */
internal object AnkiDroidMapper {

    /**
     * Validates that a required column exists before reading.
     * Throws a typed mapping error for a malformed critical result (§54).
     */
    fun requireColumnIndex(row: AnkiDroidProviderRow, columnName: String): Int {
        val index = row.columnIndex(columnName)
        if (index < 0) {
            throw AnkiDroidMappingException(
                message = "Missing required column: $columnName",
                columnName = columnName,
                isRequired = true
            )
        }
        return index
    }

    /**
     * Optional column — returns -1 if missing, which callers treat as "value unknown"
     * (a capability the provider did not expose), never as "backend broken" (§53).
     */
    fun optionalColumnIndex(row: AnkiDroidProviderRow, columnName: String): Int =
        row.columnIndex(columnName)

    /** Safe Long extraction — never silently substitutes 0 for a missing field (§75). */
    fun getRequiredLong(row: AnkiDroidProviderRow, columnIndex: Int, fieldName: String): Long {
        if (columnIndex < 0) throw AnkiDroidMappingException("Missing required field: $fieldName", fieldName, true)
        return try {
            row.getLong(columnIndex)
        } catch (e: RuntimeException) {
            throw AnkiDroidMappingException(
                message = "Invalid type for $fieldName at index $columnIndex",
                columnName = fieldName,
                isRequired = true,
                cause = e
            )
        }
    }

    /** Lenient optional read: absent column, SQL NULL or a read error all yield `null`. */
    fun getOptionalString(row: AnkiDroidProviderRow, columnIndex: Int): String? {
        if (columnIndex < 0) return null
        return try {
            if (row.isNull(columnIndex)) null else row.getString(columnIndex)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun getRequiredString(row: AnkiDroidProviderRow, columnIndex: Int, fieldName: String): String {
        if (columnIndex < 0) throw AnkiDroidMappingException("Missing required field: $fieldName", fieldName, true)
        return try {
            val value = row.getString(columnIndex)
            if (value.isNullOrBlank()) {
                throw AnkiDroidMappingException("Blank required field: $fieldName", fieldName, true)
            }
            value
        } catch (e: AnkiDroidMappingException) {
            throw e
        } catch (e: RuntimeException) {
            throw AnkiDroidMappingException("Invalid type for $fieldName", fieldName, true, e)
        }
    }
}

internal class AnkiDroidMappingException(
    message: String,
    val columnName: String,
    val isRequired: Boolean,
    cause: Throwable? = null
) : RuntimeException(message, cause)
