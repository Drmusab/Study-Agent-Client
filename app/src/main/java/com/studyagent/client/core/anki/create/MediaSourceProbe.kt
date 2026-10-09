package com.studyagent.client.core.anki.create

/**
 * GATE 18 — local preparation of one media attachment (CONTRACT-18-12). This is REVERSIBLE local
 * inspection of a user-picked content URI: nothing here means backend media exists, and no field
 * may reference a name before a `Stored` result (docs/GATE_18 §10/§14).
 */

/**
 * MIME types GATE 18 accepts for creation media, with the extension requested from the backend.
 * Deliberately small and explicit: the pinned provider derives its temp-file extension from the
 * MIME type, and an unmapped type would produce a `.null` name (docs/GATE_18 R4) — so anything not
 * listed here is refused pre-boundary instead.
 */
object MediaMimeTypes {
    private val IMAGE: Map<String, String> = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",
        "image/bmp" to "bmp",
        "image/heic" to "heic",
        "image/heif" to "heif"
    )
    private val AUDIO: Map<String, String> = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/flac" to "flac",
        "audio/webm" to "webm",
        "audio/3gpp" to "3gp",
        "audio/amr" to "amr",
        "audio/midi" to "mid"
    )

    fun extensionFor(mimeType: String): String? =
        IMAGE[mimeType.lowercase()] ?: AUDIO[mimeType.lowercase()]

    fun kindFor(mimeType: String): CreationMediaKind? = when {
        IMAGE.containsKey(mimeType.lowercase()) -> CreationMediaKind.IMAGE
        AUDIO.containsKey(mimeType.lowercase()) -> CreationMediaKind.AUDIO
        else -> null
    }
}

/** The answer of one local probe. Refusal tokens are stable and content-free. */
sealed interface MediaSourceProbeResult {
    data class Ready(
        val mimeType: String,
        val extension: String,
        val sizeBytes: Long,
        val displayName: String
    ) : MediaSourceProbeResult

    data class Refused(val token: String) : MediaSourceProbeResult
}

/**
 * Probes a user-picked content URI in the local process. Implementations live at the platform
 * edge; the creation domain only sees typed results. A probe never opens the AnkiDroid provider
 * and never reads Anki's media storage (INV-18-12).
 */
interface MediaSourceProbe {
    suspend fun probe(contentUri: String): MediaSourceProbeResult
}
