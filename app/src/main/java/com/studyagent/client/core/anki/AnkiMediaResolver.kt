package com.studyagent.client.core.anki

/**
 * Resolves a backend-owned media reference without turning it into a filesystem path.
 *
 * This interface deliberately lives at the domain boundary: Android Uri, ContentResolver,
 * ParcelFileDescriptor and InputStream implementations belong below it. A resolver is read-only
 * and does not know how a card is rendered or played.
 */
interface AnkiMediaResolver {
    suspend fun resolve(ref: AnkiMediaRef): AnkiMediaResolveResult
}

enum class AnkiMediaType { IMAGE, AUDIO, VIDEO, FONT, UNKNOWN }

enum class AnkiMediaFailure {
    MISSING,
    UNSUPPORTED,
    BLOCKED,
    INVALID_REFERENCE,
    PROVIDER_UNAVAILABLE
}

/** Presentation-safe media metadata. [url] is an app-controlled logical URL, never a file path. */
data class ResolvedMedia(
    val stableReference: String,
    val type: AnkiMediaType,
    val url: String,
    val mimeType: String? = null
)

sealed interface AnkiMediaResolveResult {
    data class Resolved(val media: ResolvedMedia) : AnkiMediaResolveResult
    data class Unavailable(
        val failure: AnkiMediaFailure,
        val diagnostic: String
    ) : AnkiMediaResolveResult
}

/** Conservative validation shared by provider adapters and request interception tests. */
object AnkiMediaReferencePolicy {
    const val INVALID_REFERENCE = "invalid_media_reference"

    /** A backend media name is one logical filename, never a URI or path. */
    fun validateLogicalName(value: String): Boolean {
        val name = value.trim()
        if (name.isEmpty() || name.length > 1024) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains('\u0000')) return false
        // Decode repeatedly so double-encoded traversal cannot reach a provider. Every candidate
        // is checked, starting with the literal name itself. A malformed percent escape (e.g. the
        // "% r" in "100% ready.webp") makes URLDecoder throw; that is not a reason to reject the
        // name — no conforming decoder turns a broken escape into traversal, and a lenient one
        // passes the characters through literally — so validation continues on the name as-is.
        fun isTraversal(value: String): Boolean =
            value.contains("../") || value.contains("..\\") || value == ".."

        var candidate = name
        if (isTraversal(candidate)) return false
        repeat(3) {
            val decoded = runCatching {
                java.net.URLDecoder.decode(candidate, Charsets.UTF_8.name())
            }.getOrNull() ?: return@repeat
            candidate = decoded
            if (isTraversal(candidate)) return false
        }
        return !candidate.contains(':') && !candidate.contains("//")
    }

    fun typeForMimeOrName(mimeType: String?, name: String): AnkiMediaType {
        val mime = mimeType?.lowercase()?.substringBefore(';')
        return when {
            mime?.startsWith("image/") == true -> AnkiMediaType.IMAGE
            mime?.startsWith("audio/") == true -> AnkiMediaType.AUDIO
            mime?.startsWith("video/") == true -> AnkiMediaType.VIDEO
            mime == "font/ttf" || mime == "font/otf" || mime == "font/woff" || mime == "font/woff2" -> AnkiMediaType.FONT
            else -> when (name.substringAfterLast('.', "").lowercase()) {
                "png", "jpg", "jpeg", "gif", "webp", "svg" -> AnkiMediaType.IMAGE
                "mp3", "ogg", "oga", "wav", "m4a", "aac", "flac" -> AnkiMediaType.AUDIO
                "mp4", "webm", "ogv", "mov" -> AnkiMediaType.VIDEO
                "ttf", "otf", "woff", "woff2" -> AnkiMediaType.FONT
                else -> AnkiMediaType.UNKNOWN
            }
        }
    }
}
