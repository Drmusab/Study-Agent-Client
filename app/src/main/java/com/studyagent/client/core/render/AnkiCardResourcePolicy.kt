package com.studyagent.client.core.render

import java.net.URI

/** Explicit, pure resource policy for the restricted card browser. */
enum class AnkiResourceDecision { ALLOW_CONTROLLED, ALLOW_IN_PAGE, BLOCK }

data class AnkiResourceClassification(
    val decision: AnkiResourceDecision,
    val scheme: String?,
    val reason: String
)

object AnkiCardResourcePolicy {
    private const val CONTROLLED_HOST = "card.studyagent.invalid"

    /** Remote card resources are blocked by default; local card content remains offline-capable. */
    fun classify(url: String?): AnkiResourceClassification {
        val value = url?.trim().orEmpty()
        if (value.startsWith("#")) return AnkiResourceClassification(AnkiResourceDecision.ALLOW_IN_PAGE, null, "fragment")
        val scheme = runCatching { URI(value).scheme?.lowercase() }.getOrNull()
        if (scheme == null) return AnkiResourceClassification(AnkiResourceDecision.BLOCK, null, "no_scheme")
        if (scheme == "https" && runCatching { URI(value).host?.lowercase() }.getOrNull() == CONTROLLED_HOST) {
            return AnkiResourceClassification(AnkiResourceDecision.ALLOW_CONTROLLED, scheme, "controlled_origin")
        }
        return AnkiResourceClassification(AnkiResourceDecision.BLOCK, scheme.take(16), "resource_policy")
    }
}
