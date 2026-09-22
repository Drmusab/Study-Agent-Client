package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCapabilities

/**
 * GATE 04 — compatibility policy: minimum usable provider spec, capability mapping,
 * and forward-compatibility handling.
 *
 * Responsibilities (per spec §50):
 * - minimum usable provider spec
 * - capability mapping (spec → API support)
 * - unsupported versions
 *
 * No version-condition trees scattered across files (§49).
 */
object AnkiDroidCompatibilityPolicy {

    const val MIN_SUPPORTED_SPEC: Int = AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC
    const val MAX_VALIDATED_SPEC: Int = AnkiDroidProviderSpec.MAX_VALIDATED_SPEC

    /**
     * True when [spec] is usable by this build.
     *
     * Forward compatibility (§51): unknown newer specs are NOT automatically failed
     * if contract remains compatible. We accept any spec >= MIN_SUPPORTED_SPEC.
     */
    fun isSpecSupported(spec: Int): Boolean = spec >= MIN_SUPPORTED_SPEC

    /**
     * Returns API-level capability support derived from [spec].
     *
     * This is what AnkiDroid *can* do at this spec, not what Study-Agent has implemented.
     * Provenance: provider spec + contract availability.
     */
    fun apiCapabilitiesForSpec(spec: Int?): AnkiDroidApiCapabilityReport {
        if (spec == null) return AnkiDroidApiCapabilityReport.UNKNOWN

        if (!isSpecSupported(spec)) {
            return AnkiDroidApiCapabilityReport(
                deckListing = CapabilitySupport.UNSUPPORTED,
                deckCounts = CapabilitySupport.UNSUPPORTED,
                scheduledReview = CapabilitySupport.UNSUPPORTED,
                renderedCards = CapabilitySupport.UNSUPPORTED,
                simpleCardText = CapabilitySupport.UNSUPPORTED,
                nextReviewIntervals = CapabilitySupport.UNSUPPORTED,
                ratingCommit = CapabilitySupport.UNSUPPORTED,
                flags = CapabilitySupport.UNSUPPORTED,
                bury = CapabilitySupport.UNSUPPORTED,
                suspend = CapabilitySupport.UNSUPPORTED,
                noteRead = CapabilitySupport.UNSUPPORTED,
                noteEdit = CapabilitySupport.UNSUPPORTED,
                noteCreate = CapabilitySupport.UNSUPPORTED,
                mediaRead = CapabilitySupport.UNSUPPORTED,
                mediaWrite = CapabilitySupport.UNSUPPORTED,
                search = CapabilitySupport.UNSUPPORTED,
                noteTypes = CapabilitySupport.UNSUPPORTED,
                cardTemplates = CapabilitySupport.UNSUPPORTED,
                specVersion = spec,
                reason = "spec $spec below minimum $MIN_SUPPORTED_SPEC"
            )
        }

        // Spec 1+ is the documented baseline. AnkiDroid's public provider contract
        // (FlashCardsContract) at v2.24.1 includes decks, notes, cards, review info,
        // and media. We conservatively mark all known capabilities as SUPPORTED
        // when spec >= 1, because the contract contains them. Study-Agent implementation
        // status is tracked separately (ImplementationStatus).
        return AnkiDroidApiCapabilityReport(
            deckListing = CapabilitySupport.SUPPORTED,
            deckCounts = CapabilitySupport.SUPPORTED,
            scheduledReview = CapabilitySupport.SUPPORTED,
            renderedCards = CapabilitySupport.SUPPORTED,
            simpleCardText = CapabilitySupport.SUPPORTED,
            nextReviewIntervals = CapabilitySupport.SUPPORTED,
            ratingCommit = CapabilitySupport.SUPPORTED,
            flags = CapabilitySupport.SUPPORTED,
            bury = CapabilitySupport.SUPPORTED,
            suspend = CapabilitySupport.SUPPORTED,
            noteRead = CapabilitySupport.SUPPORTED,
            noteEdit = CapabilitySupport.SUPPORTED,
            noteCreate = CapabilitySupport.SUPPORTED,
            mediaRead = CapabilitySupport.SUPPORTED,
            mediaWrite = CapabilitySupport.SUPPORTED,
            search = CapabilitySupport.SUPPORTED,
            noteTypes = CapabilitySupport.SUPPORTED,
            cardTemplates = CapabilitySupport.SUPPORTED,
            specVersion = spec,
            reason = "provider contract supported at spec $spec"
        )
    }

    /**
     * Core capability set required for future local review (§20).
     * GATE 04: provider access + collection access + deck/card lookup foundation.
     * Does NOT require editing/media.
     */
    fun isCoreCapabilitySetPresent(apiReport: AnkiDroidApiCapabilityReport): Boolean {
        return apiReport.deckListing == CapabilitySupport.SUPPORTED &&
            apiReport.simpleCardText == CapabilitySupport.SUPPORTED
    }

    /**
     * Maps current spec + runtime readiness to implemented AnkiCapabilities.
     *
     * GATE 04: no review, deck listing, etc. implemented yet — returns NONE.
     * Future gates will return true for features they have verified.
     */
    fun implementedCapabilitiesFor(
        spec: Int?,
        isReady: Boolean
    ): AnkiCapabilities {
        if (!isReady || spec == null || !isSpecSupported(spec)) return AnkiCapabilities.NONE

        // GATE 04 intentionally returns NONE: API supports features but Study-Agent
        // has not yet implemented them. This prevents isReadyForReview from becoming true
        // before GATE 06 (review gate) lands, preserving PC path isolation.
        // Documented in GATE 02 §96 and GATE 04 §19/§22.
        return AnkiCapabilities.NONE
    }
}

/**
 * Three-state capability support (§12): SUPPORTED / UNSUPPORTED / UNKNOWN.
 * UNKNOWN is used when spec is not known or validation is pending.
 */
enum class CapabilitySupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
}

/**
 * Internal feature maturity (§103) to distinguish API support from implementation.
 */
enum class CapabilityMaturity {
    API_UNSUPPORTED,
    API_SUPPORTED_NOT_IMPLEMENTED,
    IMPLEMENTED,
    VERIFIED
}

/**
 * API-level capability report: what AnkiDroid provider supports at a given spec.
 * Used for diagnostics (§121) to show "Provider supports X but Study-Agent pending Y".
 */
data class AnkiDroidApiCapabilityReport(
    val deckListing: CapabilitySupport,
    val deckCounts: CapabilitySupport,
    val scheduledReview: CapabilitySupport,
    val renderedCards: CapabilitySupport,
    val simpleCardText: CapabilitySupport,
    val nextReviewIntervals: CapabilitySupport,
    val ratingCommit: CapabilitySupport,
    val flags: CapabilitySupport,
    val bury: CapabilitySupport,
    val suspend: CapabilitySupport,
    val noteRead: CapabilitySupport,
    val noteEdit: CapabilitySupport,
    val noteCreate: CapabilitySupport,
    val mediaRead: CapabilitySupport,
    val mediaWrite: CapabilitySupport,
    val search: CapabilitySupport,
    val noteTypes: CapabilitySupport,
    val cardTemplates: CapabilitySupport,
    val specVersion: Int?,
    val reason: String
) {
    companion object {
        val UNKNOWN = AnkiDroidApiCapabilityReport(
            deckListing = CapabilitySupport.UNKNOWN,
            deckCounts = CapabilitySupport.UNKNOWN,
            scheduledReview = CapabilitySupport.UNKNOWN,
            renderedCards = CapabilitySupport.UNKNOWN,
            simpleCardText = CapabilitySupport.UNKNOWN,
            nextReviewIntervals = CapabilitySupport.UNKNOWN,
            ratingCommit = CapabilitySupport.UNKNOWN,
            flags = CapabilitySupport.UNKNOWN,
            bury = CapabilitySupport.UNKNOWN,
            suspend = CapabilitySupport.UNKNOWN,
            noteRead = CapabilitySupport.UNKNOWN,
            noteEdit = CapabilitySupport.UNKNOWN,
            noteCreate = CapabilitySupport.UNKNOWN,
            mediaRead = CapabilitySupport.UNKNOWN,
            mediaWrite = CapabilitySupport.UNKNOWN,
            search = CapabilitySupport.UNKNOWN,
            noteTypes = CapabilitySupport.UNKNOWN,
            cardTemplates = CapabilitySupport.UNKNOWN,
            specVersion = null,
            reason = "spec not known"
        )
    }

    fun toCapabilityDetailList(): List<AnkiDroidCapabilityDetail> = listOf(
        AnkiDroidCapabilityDetail("deckListing", deckListing, maturityFor(deckListing, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("deckCounts", deckCounts, maturityFor(deckCounts, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("scheduledReview", scheduledReview, maturityFor(scheduledReview, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("renderedCards", renderedCards, maturityFor(renderedCards, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("simpleCardText", simpleCardText, maturityFor(simpleCardText, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("nextReviewIntervals", nextReviewIntervals, maturityFor(nextReviewIntervals, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("ratingCommit", ratingCommit, maturityFor(ratingCommit, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("flags", flags, maturityFor(flags, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("bury", bury, maturityFor(bury, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("suspend", suspend, maturityFor(suspend, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("noteRead", noteRead, maturityFor(noteRead, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("noteEdit", noteEdit, maturityFor(noteEdit, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("noteCreate", noteCreate, maturityFor(noteCreate, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("mediaRead", mediaRead, maturityFor(mediaRead, implemented = false), "not yet validated"),
        AnkiDroidCapabilityDetail("mediaWrite", mediaWrite, maturityFor(mediaWrite, implemented = false), "not yet validated"),
        AnkiDroidCapabilityDetail("search", search, maturityFor(search, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("noteTypes", noteTypes, maturityFor(noteTypes, implemented = false), "provider contract"),
        AnkiDroidCapabilityDetail("cardTemplates", cardTemplates, maturityFor(cardTemplates, implemented = false), "provider contract")
    )

    private fun maturityFor(support: CapabilitySupport, implemented: Boolean): CapabilityMaturity = when {
        support == CapabilitySupport.UNSUPPORTED -> CapabilityMaturity.API_UNSUPPORTED
        support == CapabilitySupport.SUPPORTED && !implemented -> CapabilityMaturity.API_SUPPORTED_NOT_IMPLEMENTED
        support == CapabilitySupport.SUPPORTED && implemented -> CapabilityMaturity.IMPLEMENTED
        else -> CapabilityMaturity.API_SUPPORTED_NOT_IMPLEMENTED
    }
}

data class AnkiDroidCapabilityDetail(
    val name: String,
    val apiSupport: CapabilitySupport,
    val maturity: CapabilityMaturity,
    val reason: String
)
