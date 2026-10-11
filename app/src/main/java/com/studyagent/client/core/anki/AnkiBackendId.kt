package com.studyagent.client.core.anki

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Logical identity, not object identity. PC profiles must never share a write ledger. */
@Serializable
sealed interface AnkiBackendId {
    val stableId: String

    @Serializable
    @SerialName("ankidroid_local")
    data object AnkiDroidLocal : AnkiBackendId {
        override val stableId: String get() = "ankidroid_local"
    }

    @Serializable
    @SerialName("pc_agent")
    data class PcAgent(val profileId: String) : AnkiBackendId {
        init { require(profileId.isNotBlank()) }
        override val stableId: String get() = "pc_agent:$profileId"
    }

    @Serializable
    @SerialName("fake")
    data class Fake(val id: String = "fake") : AnkiBackendId {
        init { require(id.isNotBlank()) }
        override val stableId: String get() = "fake:$id"
    }

    companion object {
        fun fromStableId(value: String?): AnkiBackendId? = when {
            value == "ankidroid_local" -> AnkiDroidLocal
            value?.startsWith("pc_agent:") == true -> value.removePrefix("pc_agent:")
                .takeIf { it.isNotBlank() }?.let(::PcAgent)
            value?.startsWith("fake:") == true -> value.removePrefix("fake:")
                .takeIf { it.isNotBlank() }?.let(::Fake)
            else -> null
        }
    }
}

/** User preference; resolution must never overwrite AUTO with an effective identity. */
@Serializable
enum class AnkiBackendMode(val stableId: String) {
    AUTO("auto"), ANKIDROID_LOCAL("ankidroid_local"), PC_AGENT("pc_agent");

    fun accepts(id: AnkiBackendId): Boolean = when (this) {
        AUTO -> id is AnkiBackendId.AnkiDroidLocal || id is AnkiBackendId.PcAgent
        ANKIDROID_LOCAL -> id is AnkiBackendId.AnkiDroidLocal
        PC_AGENT -> id is AnkiBackendId.PcAgent
    }

    companion object {
        fun fromStableId(stableId: String?): AnkiBackendMode? =
            entries.firstOrNull { it.stableId == stableId }
    }
}

/** Length prefixes preserve null, delimiters and all identity fields without collisions. */
internal fun identityKey(vararg parts: String?): String =
    parts.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }

internal fun requireOptionalId(value: String?) {
    require(value == null || value.isNotBlank()) { "An optional identifier must be null or non-blank" }
}

/**
 * GATE 21C — allocation rules A3/A5 (BACKEND_IDENTITY_CONTRACT §1.3), checkable by the allocation
 * authority (owner: 21D) and by any writer that must decide whether an identity is safe to persist.
 *
 * True when this identity survives its own [AnkiBackendId.stableId] round trip: decoding the token
 * yields exactly this instance. An identity that fails this is not persistable — a reader would decode
 * the token to `null` (unknown owner) or to a *different* configured instance, which is how a durable
 * record silently changes hands.
 */
val AnkiBackendId.roundTripsStableId: Boolean
    get() = AnkiBackendId.fromStableId(stableId) == this

/**
 * GATE 21C — allocation rule A5: the discriminator is normalized **once**, at allocation, and never
 * re-normalized at read time.
 *
 * A configuration whose discriminator carries surrounding whitespace would produce a second
 * configured instance that looks identical in the UI, so the allocation authority must refuse or
 * normalize it (21D). The identity types themselves must never rewrite a stored value: re-normalizing
 * on read would change the owner of every record already written under the un-normalized token.
 */
val AnkiBackendId.discriminatorIsNormalized: Boolean
    get() = stableId == stableId.trim()
