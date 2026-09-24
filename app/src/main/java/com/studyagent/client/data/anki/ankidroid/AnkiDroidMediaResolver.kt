package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiMediaResolveResult
import com.studyagent.client.core.anki.AnkiMediaResolver
import com.studyagent.client.core.anki.AnkiMediaFailure
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * GATE 09 adapter for the verified AnkiDroid v2.24.1 public contract.
 *
 * ReviewInfo.MEDIA_FILES exposes filenames only. The public CardContentProvider contract pinned in
 * [AnkiDroidApiContract] has no media-file read/content-URI endpoint, and this app must not derive
 * `/data/data/com.ichi2.anki/...` (or any other private path) from a filename. Until AnkiDroid
 * publishes a supported read stream contract, media is an observable, safe degradation.
 */
class AnkiDroidMediaResolver : AnkiMediaResolver {
    override suspend fun resolve(ref: AnkiMediaRef): AnkiMediaResolveResult {
        coroutineContext.ensureActive()
        return when (ref) {
            is AnkiMediaRef.BackendStream -> {
                if (com.studyagent.client.core.anki.AnkiMediaReferencePolicy
                        .validateLogicalName(ref.streamId)) {
                    AnkiMediaResolveResult.Unavailable(
                        AnkiMediaFailure.PROVIDER_UNAVAILABLE,
                        "ankidroid_public_contract_exposes_filename_only"
                    )
                } else {
                    AnkiMediaResolveResult.Unavailable(
                        AnkiMediaFailure.INVALID_REFERENCE,
                        "invalid_media_reference"
                    )
                }
            }
            // These references are not produced by the AnkiDroid mapper. Do not trust a URI merely
            // because it is represented as a domain value; adapters own their URI allowlists.
            is AnkiMediaRef.ContentUri -> AnkiMediaResolveResult.Unavailable(
                AnkiMediaFailure.BLOCKED, "content_uri_not_supported_by_ankidroid_adapter"
            )
            is AnkiMediaRef.RemoteUrl -> AnkiMediaResolveResult.Unavailable(
                AnkiMediaFailure.BLOCKED, "remote_media_not_enabled"
            )
            is AnkiMediaRef.Unavailable -> AnkiMediaResolveResult.Unavailable(
                AnkiMediaFailure.MISSING, ref.reason ?: "media_unavailable"
            )
        }
    }
}
