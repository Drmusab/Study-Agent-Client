package com.studyagent.client.core.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiNoteModel

/** Product limit: one media file. The provider reads whole files into memory (docs/GATE_18 §9). */
const val MAX_MEDIA_SIZE_BYTES: Long = 25L * 1024L * 1024L

/** Product limit: media steps per creation. Keeps the plan bounded and the ledger small. */
const val MAX_MEDIA_PER_CREATION: Int = 8

/**
 * GATE 18 — one media attachment the user picked, before any backend effect exists.
 *
 * This is local preparation only (CONTRACT-18-12): nothing here proves backend media exists, and
 * the stored name is only known after a `Stored` result. [extension] is the locally determined
 * file extension (from the MIME type); the provider's temp-name mechanics make an unknown extension
 * unusable, so the validator rejects it pre-boundary (docs/GATE_18 R4).
 */
data class PendingMedia(
    val contentUri: String,
    val sourceName: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long,
    val kind: CreationMediaKind,
    /** The field ordinal the reference is appended to once the name is known. */
    val targetFieldOrdinal: Int
) {
    init {
        require(contentUri.isNotBlank()) { "Media needs a source URI" }
        require(sourceName.isNotBlank()) { "Media needs a source name" }
        require(extension.isNotBlank()) { "Media needs a known extension" }
        require(sizeBytes > 0) { "Media size must be known and positive" }
        require(targetFieldOrdinal >= 0) { "Media targets a field ordinal" }
    }

    /** The sanitized preferred name requested from the backend (source stem + extension). */
    val requestedName: String
        get() {
            val stem = sourceName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.')
                .trim()
                .take(40)
                .ifBlank { "media" }
            return "$stem.$extension"
        }
}

/**
 * GATE 18 — the frozen creation draft (PART I §4/§5).
 *
 * Once the user presses Save and validation passes, this object is the immutable transaction input:
 * no mutable UI state may alter an in-flight creation. Field values are keyed by schema ordinal —
 * the only field identity the pinned backend honours (CONTRACT-18-03). The model schema snapshot is
 * carried along, so the coordinator can detect drift with a fresh read before the boundary.
 */
data class AddNoteDraft(
    val backendId: AnkiBackendId,
    val collectionKey: String?,
    val model: AnkiNoteModel,
    val fieldValues: Map<Int, String>,
    val tags: List<String>,
    val media: List<PendingMedia>
) {
    init {
        require(model.ref.backendId == backendId) { "A draft belongs to one backend" }
        require(fieldValues.isNotEmpty()) { "A draft needs field values" }
        require(media.size <= MAX_MEDIA_PER_CREATION) { "Too many media attachments" }
        require(media.map { it.targetFieldOrdinal }.all { it < model.fieldCount }) {
            "Media must target an existing field"
        }
    }

    /** Ordered field values, one per schema ordinal. Refuses gaps instead of guessing. */
    fun orderedFields(): List<String> =
        model.fields.map { field ->
            requireNotNull(fieldValues[field.ordinal]) { "Field ordinal ${field.ordinal} is missing" }
        }
}

/**
 * Pure assembly of the final field payload from the draft plus the backend-returned media names
 * (CONTRACT-18-14: field text references only names the backend already confirmed).
 *
 * References use exactly Anki's public syntax (CONTRACT-18-26): `<img src="NAME">` for images and
 * `[sound:NAME]` for sounds. They are appended to the target field; nothing else in the draft is
 * touched. No path is invented: [storedNames] is indexed by media step and must be complete.
 */
object NoteCreationFieldAssembly {

    fun assemble(draft: AddNoteDraft, storedNames: List<String>): List<String>? {
        if (storedNames.size != draft.media.size) return null
        if (storedNames.any { it.isBlank() }) return null
        if (draft.model.fields.any { !draft.fieldValues.containsKey(it.ordinal) }) return null
        val base = draft.orderedFields().toMutableList()
        draft.media.forEachIndexed { index, pending ->
            val reference = when (pending.kind) {
                CreationMediaKind.IMAGE -> "<img src=\"${storedNames[index]}\">"
                CreationMediaKind.AUDIO -> "[sound:${storedNames[index]}]"
            }
            val current = base[pending.targetFieldOrdinal]
            base[pending.targetFieldOrdinal] = if (current.isBlank()) reference else "$current<br>$reference"
        }
        return base
    }

    /** The assembled payload for a creation with no media — the fields exactly as typed. */
    fun assembleWithoutMedia(draft: AddNoteDraft): List<String> = draft.orderedFields()
}
