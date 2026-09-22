package com.studyagent.client.core.anki

/**
 * GATE 01 contract — resolves the *effective* Anki backend for one session
 * start (§39-§41).
 *
 * Rules:
 *  - Runs once, at session start. Its output is locked into
 *    [AnkiSessionContext] for the session's lifetime (INV-ANKI-01/07).
 *  - Never rewrites the user's [AnkiBackendMode] preference (§40).
 *  - `AUTO` order is an explicit architecture decision: AnkiDroid-local first
 *    when review-ready (works offline, fewest moving parts, single ownership
 *    boundary), the PC agent path second; a backend that is not *implemented*
 *    in this build is never resolved, so today AUTO still lands on the PC
 *    path (§91 backward compatibility; rationale in
 *    `docs/ANKI_INTEGRATION_ARCHITECTURE.md` §7).
 *  - Resolution is *between* sessions only. It is never consulted mid-session
 *    (§6-§7: no silent failover during an active review transaction).
 */
object AnkiBackendSelector {

    /** AUTO preference order — AnkiDroid-local first, PC agent second. */
    private val AUTO_ORDER: List<AnkiBackendId> = listOf(
        AnkiBackendId.ANKIDROID_LOCAL,
        AnkiBackendId.PC_AGENT
    )

    enum class UnavailableReason {
        /** The pinned backend has no implementation in this build. */
        EXPLICIT_BACKEND_NOT_IMPLEMENTED,

        /** The pinned backend exists but is not review-ready right now. */
        EXPLICIT_BACKEND_NOT_READY,

        /** AUTO found no implemented, review-ready backend at all. */
        NO_READY_BACKEND
    }

    sealed interface Resolution {
        /** Exactly one effective backend — the session's write-lock (INV-ANKI-01). */
        data class Resolved(val backendId: AnkiBackendId) : Resolution

        /** No session may start on Anki data; Study-Agent degrades per policy (§45). */
        data class Unavailable(
            val reason: UnavailableReason,
            val detail: String? = null
        ) : Resolution
    }

    /**
     * @param preference the user's stored preference; never mutated here.
     * @param implemented backends with a registered implementation in this build.
     * @param availabilityOf current availability per backend id.
     */
    fun resolve(
        preference: AnkiBackendMode,
        implemented: Set<AnkiBackendId>,
        availabilityOf: (AnkiBackendId) -> AnkiAvailability
    ): Resolution {
        val pinned = preference.pinnedBackendId()
        if (pinned != null) {
            if (pinned !in implemented) {
                return Resolution.Unavailable(UnavailableReason.EXPLICIT_BACKEND_NOT_IMPLEMENTED)
            }
            return if (availabilityOf(pinned).isReadyForReview) {
                Resolution.Resolved(pinned)
            } else {
                Resolution.Unavailable(UnavailableReason.EXPLICIT_BACKEND_NOT_READY)
            }
        }

        for (candidate in AUTO_ORDER) {
            if (candidate in implemented && availabilityOf(candidate).isReadyForReview) {
                return Resolution.Resolved(candidate)
            }
        }
        return Resolution.Unavailable(UnavailableReason.NO_READY_BACKEND)
    }
}
