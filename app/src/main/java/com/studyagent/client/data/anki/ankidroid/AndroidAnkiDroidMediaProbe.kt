package com.studyagent.client.data.anki.ankidroid

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.studyagent.client.core.anki.create.MediaMimeTypes
import com.studyagent.client.core.anki.create.MediaSourceProbe
import com.studyagent.client.core.anki.create.MediaSourceProbeResult
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * GATE 18 — the ONLY local media preparation component (CONTRACT-18-12). Platform-facing by
 * necessity: it reads metadata of a user-picked content URI through the app's own resolver — never
 * the AnkiDroid provider, never Anki's media directories (INV-18-12). It reports facts
 * (MIME type, size, display name) and stable refusal tokens; every limit decision stays in the
 * creation domain ([com.studyagent.client.core.anki.create.NoteCreationValidator]).
 *
 * This probe is reversible local inspection: a successful probe does NOT mean backend media exists.
 */
class AndroidAnkiDroidMediaProbe(
    context: Context,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
) : MediaSourceProbe {

    private val appContext: Context = context.applicationContext

    override suspend fun probe(contentUri: String): MediaSourceProbeResult =
        withContext(dispatchers.io) {
            val uri = try {
                Uri.parse(contentUri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return@withContext MediaSourceProbeResult.Refused("uri_unparseable")
            }
            val mimeType = try {
                appContext.contentResolver.getType(uri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            if (mimeType == null) return@withContext MediaSourceProbeResult.Refused("mime_unknown")
            val extension = MediaMimeTypes.extensionFor(mimeType)
                ?: return@withContext MediaSourceProbeResult.Refused("mime_unsupported")

            var sizeBytes: Long? = null
            var displayName: String? = null
            var cursor: Cursor? = null
            try {
                cursor = appContext.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Fall through to the descriptor probe below.
            } finally {
                try {
                    cursor?.close()
                } catch (_: Throwable) {
                    // Closing must never fail a probe.
                }
            }
            if (sizeBytes == null) {
                try {
                    appContext.contentResolver.openAssetFileDescriptor(uri, "r").use { descriptor ->
                        sizeBytes = descriptor?.length?.takeIf { it >= 0L }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    sizeBytes = null
                }
            }
            val size = sizeBytes
            if (size == null || size <= 0L) {
                return@withContext MediaSourceProbeResult.Refused("size_unknown")
            }
            MediaSourceProbeResult.Ready(
                mimeType = mimeType,
                extension = extension,
                sizeBytes = size,
                displayName = displayName?.takeIf { it.isNotBlank() } ?: "media.$extension"
            )
        }
}
