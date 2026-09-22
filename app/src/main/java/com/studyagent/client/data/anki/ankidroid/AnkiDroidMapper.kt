package com.studyagent.client.data.anki.ankidroid

import android.database.Cursor

/**
 * GATE 04 — mapper responsibility (§56).
 *
 * Converts provider-specific values into domain models.
 * No UI formatting, no voice normalization, no AI transformation (§57/§58/§59).
 *
 * Currently placeholder for future deck/card mapping (GATE 05+).
 * Establishes the pattern: strict required / lenient optional (§55).
 */
internal object AnkiDroidMapper {

    /**
     * Validates required column exists before reading.
     * Returns typed failure info for malformed critical results (§54).
     */
    fun requireColumnIndex(cursor: Cursor, columnName: String): Int {
        val index = cursor.getColumnIndex(columnName)
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
     * Optional column — returns -1 if missing, which caller should treat as
     * capability unavailable, not backend broken (§53).
     */
    fun optionalColumnIndex(cursor: Cursor, columnName: String): Int {
        return cursor.getColumnIndex(columnName)
    }

    /**
     * Safe Long extraction with validation — never silently uses 0 as fallback (§75).
     */
    fun getRequiredLong(cursor: Cursor, columnIndex: Int, fieldName: String): Long {
        if (columnIndex < 0) throw AnkiDroidMappingException("Missing required field: $fieldName", fieldName, true)
        return try {
            cursor.getLong(columnIndex)
        } catch (e: Exception) {
            throw AnkiDroidMappingException(
                message = "Invalid type for $fieldName at index $columnIndex",
                columnName = fieldName,
                isRequired = true,
                cause = e
            )
        }
    }

    fun getOptionalString(cursor: Cursor, columnIndex: Int): String? {
        if (columnIndex < 0) return null
        return try {
            if (cursor.isNull(columnIndex)) null else cursor.getString(columnIndex)
        } catch (_: Exception) {
            null
        }
    }

    fun getRequiredString(cursor: Cursor, columnIndex: Int, fieldName: String): String {
        if (columnIndex < 0) throw AnkiDroidMappingException("Missing required field: $fieldName", fieldName, true)
        return try {
            val value = cursor.getString(columnIndex)
            if (value.isNullOrBlank()) {
                throw AnkiDroidMappingException("Blank required field: $fieldName", fieldName, true)
            }
            value
        } catch (e: AnkiDroidMappingException) {
            throw e
        } catch (e: Exception) {
            throw AnkiDroidMappingException("Invalid type for $fieldName", fieldName, true, e)
        }
    }
}

internal class AnkiDroidMappingException(
    message: String,
    val columnName: String,
    val isRequired: Boolean,
    cause: Throwable? = null
) : Exception(message, cause)
