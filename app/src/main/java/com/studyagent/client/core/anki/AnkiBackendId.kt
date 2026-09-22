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
