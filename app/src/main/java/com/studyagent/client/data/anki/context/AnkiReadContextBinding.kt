package com.studyagent.client.data.anki.context

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiReadContext
import java.util.concurrent.atomic.AtomicBoolean

/** Shared revocation/counting anchor for one context generation across adjacent read screens. */
internal class AnkiReadContextLease(
    val context: AnkiReadContext,
    val backend: AnkiBackend,
    val isLiveBindingInThisProcess: Boolean,
    private val isStillRegistered: () -> Boolean = { true }
) {
    private var owners: Int = 0
    private var valid: Boolean = true

    @Synchronized
    fun retain(): Boolean {
        if (!valid) return false
        owners += 1
        return true
    }

    /** Returns true when the last read owner released the context. */
    @Synchronized
    fun release(): Boolean {
        if (owners > 0) owners -= 1
        if (owners == 0) valid = false
        return owners == 0
    }

    @Synchronized
    fun revoke() {
        valid = false
    }

    @get:Synchronized
    val isValid: Boolean get() = valid && isStillRegistered()
}

/**
 * Runtime handle for one immutable [AnkiReadContext]. The backend object and revocable lease are
 * deliberately outside the value identity. Adjacent navigation owners may retain the same context
 * generation; each has a local lease, while a proven collection-identity failure revokes the shared
 * anchor for all of them.
 */
class AnkiReadContextBinding internal constructor(
    val context: AnkiReadContext,
    val backend: AnkiBackend,
    /** True only for a context created in this process, not a restored route. */
    val isLiveBindingInThisProcess: Boolean,
    internal val lease: AnkiReadContextLease,
    private val onLastOwnerReleased: (AnkiReadContextLease) -> Unit = {}
) {
    private val locallyValid = AtomicBoolean(true)

    init {
        require(context.backendId == backend.id) { "A read binding must own its exact backend" }
        require(lease.context == context && lease.backend === backend)
        require(lease.isLiveBindingInThisProcess == isLiveBindingInThisProcess)
        check(lease.retain()) { "Cannot retain an invalidated read context" }
    }

    val isValid: Boolean get() = locallyValid.get() && lease.isValid

    /** Release only this screen owner's lease; sibling navigation owners remain bound. */
    internal fun invalidateLease() {
        if (locallyValid.compareAndSet(true, false) && lease.release()) {
            onLastOwnerReleased(lease)
        }
    }

    /** Revoke every screen sharing this context after a confirmed identity-integrity failure. */
    internal fun revokeContext() {
        lease.revoke()
        if (locallyValid.compareAndSet(true, false)) lease.release()
        onLastOwnerReleased(lease)
    }

    companion object {
        /** Compatibility seam for isolated tests/callers that already chose one exact backend. */
        internal fun forLegacyBackend(backend: AnkiBackend): AnkiReadContextBinding =
            create(
                backend = backend,
                collectionKey = backend.currentCollectionIdentity
                    ?.takeIf { it.backendId == backend.id }
                    ?.collectionKey,
                live = true
            )

        /** Compatibility seam for an already-qualified route reference; never consults preference. */
        internal fun forRouteReference(
            backend: AnkiBackend,
            collectionKey: String?
        ): AnkiReadContextBinding = create(backend, collectionKey, live = false)

        private fun create(
            backend: AnkiBackend,
            collectionKey: String?,
            live: Boolean
        ): AnkiReadContextBinding {
            val collection = collectionKey?.let { AnkiCollectionIdentity(backend.id, it) }
            val context = AnkiReadContext.create(AnkiBackendContext(backend.id, collection))
            val lease = AnkiReadContextLease(context, backend, live)
            return AnkiReadContextBinding(context, backend, live, lease)
        }
    }
}
