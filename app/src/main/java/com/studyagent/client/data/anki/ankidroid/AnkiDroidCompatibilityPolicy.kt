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
        //
        // GATE 07 correction — `flags` is UNSUPPORTED at v2.24.1, verified against both
        // `FlashCardsContract.Card` and `CardContentProvider.addCardToCursor`: the public card
        // contract exposes no flag column at all (the app-private `Card.flags` never crosses the
        // provider). Claiming API support here would be exactly the capability lie GATE 01 §16
        // forbids; it flips to SUPPORTED only when a verified contract version exposes flags.
        return AnkiDroidApiCapabilityReport(
            deckListing = CapabilitySupport.SUPPORTED,
            deckCounts = CapabilitySupport.SUPPORTED,
            scheduledReview = CapabilitySupport.SUPPORTED,
            renderedCards = CapabilitySupport.SUPPORTED,
            simpleCardText = CapabilitySupport.SUPPORTED,
            nextReviewIntervals = CapabilitySupport.SUPPORTED,
            ratingCommit = CapabilitySupport.SUPPORTED,
            flags = CapabilitySupport.UNSUPPORTED,
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
     * GATE 05: [AnkiCapabilities.deckListing] is true when the backend is Ready at a supported
     * spec. [AnkiCapabilities.deckCounts] stays false — counts are mapped best-effort from the
     * provider but their recursive-vs-self semantics have not been verified on a real device,
     * so UI must treat them as nullable/advisory.
     *
     * GATE 06: [AnkiCapabilities.scheduledReview] becomes true — the backend can ask AnkiDroid's
     * scheduler for the next card and map the answer, and [AnkiCapabilities.reviewIntervals] with
     * it, because the interval labels are mapped as display metadata.
     *
     * GATE 07: [AnkiCapabilities.renderedCards] becomes true — the backend can hydrate a known
     * card identity into the normalized visual/speech/evaluation channels and map the optional
     * card metadata (verified mapping against the pinned card contract, JVM-tested). Everything
     * the card-content read does *not* have stays false:
     *
     * - [AnkiCapabilities.review] — the full loop still needs rating commit (GATE 11), so
     *   `isReadyForReview` stays false and no production flow may start a rated session;
     * - [AnkiCapabilities.media] — media names are kept as references, nothing is resolved or
     *   read (GATE 09);
     * - [AnkiCapabilities.flags] — the pinned card contract exposes no flags column at all
     *   (v2.24.1, verified), so nothing here can read or write a flag (GATE 11, if ever).
     *
     * Marking any of those true to make a screen look complete would be exactly the capability
     * lie GATE 01 §16 forbids.
     */
    fun implementedCapabilitiesFor(
        spec: Int?,
        isReady: Boolean
    ): AnkiCapabilities {
        if (!isReady || spec == null || !isSpecSupported(spec)) return AnkiCapabilities.NONE
        return AnkiCapabilities(
            deckListing = true,
            scheduledReview = true,
            reviewIntervals = true,
            renderedCards = true
        )
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

    /**
     * The capability matrix as rows.
     *
     * [implemented] is the same [AnkiCapabilities] the probe publishes, so a row can never claim
     * more than the backend actually offers. Before GATE 06 every row took the default of "API
     * support only"; the review rows now reflect what is genuinely wired, and `ratingCommit`
     * still does not (§75/§147).
     */
    fun toCapabilityDetailList(
        implemented: AnkiCapabilities = AnkiCapabilities.NONE
    ): List<AnkiDroidCapabilityDetail> = listOf(
        AnkiDroidCapabilityDetail("deckListing", deckListing, maturityFor(deckListing, implemented.deckListing), "provider contract"),
        AnkiDroidCapabilityDetail("deckCounts", deckCounts, maturityFor(deckCounts, implemented.deckCounts), "provider contract"),
        AnkiDroidCapabilityDetail("scheduledReview", scheduledReview, maturityFor(scheduledReview, implemented.scheduledReview), "GATE 06 — schedule endpoint"),
        AnkiDroidCapabilityDetail("renderedCards", renderedCards, maturityFor(renderedCards, implemented.renderedCards), "GATE 07 — card content provider"),
        AnkiDroidCapabilityDetail("simpleCardText", simpleCardText, maturityFor(simpleCardText, implemented.renderedCards), "GATE 07 — question_simple/answer_simple/answer_pure"),
        AnkiDroidCapabilityDetail("nextReviewIntervals", nextReviewIntervals, maturityFor(nextReviewIntervals, implemented.reviewIntervals), "GATE 06 — mapped as display labels"),
        AnkiDroidCapabilityDetail("ratingCommit", ratingCommit, maturityFor(ratingCommit, implemented.review), "GATE 11"),
        AnkiDroidCapabilityDetail("flags", flags, maturityFor(flags, implemented.flags), "no flags column in the pinned card contract (v2.24.1)"),
        AnkiDroidCapabilityDetail("bury", bury, maturityFor(bury, implemented.bury), "provider contract"),
        AnkiDroidCapabilityDetail("suspend", suspend, maturityFor(suspend, implemented.suspendCards), "provider contract"),
        AnkiDroidCapabilityDetail("noteRead", noteRead, maturityFor(noteRead, implemented.renderedCards), "provider contract"),
        AnkiDroidCapabilityDetail("noteEdit", noteEdit, maturityFor(noteEdit, implemented.editNotes), "provider contract"),
        AnkiDroidCapabilityDetail("noteCreate", noteCreate, maturityFor(noteCreate, implemented.createNotes), "provider contract"),
        AnkiDroidCapabilityDetail("mediaRead", mediaRead, maturityFor(mediaRead, implemented.media), "not yet validated"),
        AnkiDroidCapabilityDetail("mediaWrite", mediaWrite, maturityFor(mediaWrite, implemented.media && implemented.createNotes), "not yet validated"),
        AnkiDroidCapabilityDetail("search", search, maturityFor(search, implemented.search), "provider contract"),
        AnkiDroidCapabilityDetail("noteTypes", noteTypes, maturityFor(noteTypes, implemented.renderedCards), "provider contract"),
        AnkiDroidCapabilityDetail("cardTemplates", cardTemplates, maturityFor(cardTemplates, implemented.renderedCards), "provider contract")
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
