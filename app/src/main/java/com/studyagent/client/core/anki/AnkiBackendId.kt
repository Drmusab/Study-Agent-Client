package com.studyagent.client.core.anki

/**
 * GATE 01 contract — identity of an Anki backend.
 *
 * A backend id qualifies every reference that crosses the Anki domain boundary
 * (`AnkiCardRef`, `AnkiDeckRef`, `AnkiNoteRef`, `AnkiCollectionIdentity`,
 * `ReviewCommitId`) so that an AnkiDroid-local card can never be confused with
 * a Desktop-Anki card that happens to share a numeric id (INV-ANKI-06,
 * collection-identity rule §24 of the architecture contract).
 *
 * These ids are domain concepts only. No AnkiDroid API type and no PC-protocol
 * type appears in this package; backends translate at their gateway
 * (see `docs/ANKI_INTEGRATION_ARCHITECTURE.md`).
 */
enum class AnkiBackendId(val stableId: String) {
    /** AnkiDroid on this phone, reached through its public integration API. */
    ANKIDROID_LOCAL("ankidroid_local"),

    /** Desktop Anki reached through the PC Study Agent (AnkiConnect beneath it). */
    PC_AGENT("pc_agent");

    companion object {
        fun fromStableId(stableId: String?): AnkiBackendId? =
            entries.firstOrNull { it.stableId == stableId }
    }
}

/**
 * The user's *preference* for where Anki work happens — never rewritten by
 * runtime resolution. A session resolves this preference into exactly one
 * effective [AnkiBackendId] at session start (INV-ANKI-01, INV-ANKI-07);
 * the preference itself stays untouched so the next session resolves freshly.
 *
 * Persistence of this preference is deferred to the gate that adds settings
 * integration; GATE 01 defines only the domain type and the resolution policy
 * in [AnkiBackendSelector].
 */
enum class AnkiBackendMode(val stableId: String) {
    /** Resolve at session start: AnkiDroid-local first when ready, else the PC path. */
    AUTO("auto"),

    /** Require AnkiDroid on this phone; fail closed when it is not review-ready. */
    ANKIDROID_LOCAL("ankidroid_local"),

    /** Require Desktop Anki through the PC Study Agent; fail closed otherwise. */
    PC_AGENT("pc_agent");

    /** The concrete backend this mode pins, or null when [AUTO] leaves it open. */
    fun pinnedBackendId(): AnkiBackendId? = when (this) {
        AUTO -> null
        ANKIDROID_LOCAL -> AnkiBackendId.ANKIDROID_LOCAL
        PC_AGENT -> AnkiBackendId.PC_AGENT
    }

    companion object {
        fun fromStableId(stableId: String?): AnkiBackendMode? =
            entries.firstOrNull { it.stableId == stableId }
    }
}
