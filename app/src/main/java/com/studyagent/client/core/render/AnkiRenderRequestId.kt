package com.studyagent.client.core.render

import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.ReviewTurnId

/**
 * GATE 08 — the identity of one render request (STEP 18/§19).
 *
 * Every document the renderer hands to a WebView is stamped with this, and every callback the
 * WebView produces carries the stamp it was loaded with. That is the whole mechanism by which a
 * late callback is recognised as late: a page-finished from Turn A cannot mark Turn B ready, because
 * Turn B's stamp is not Turn A's stamp (STEP 19, INV-ANKI-RENDER-06).
 *
 * The four components answer four different questions:
 *
 * - [turnId] — *which presentation* is this? Question and answer of one turn share it
 *   (INV-ANKI-RENDER-05), and the same card reviewed twice does not (GATE 06 turn semantics).
 * - [cardRef] — *which card*? Kept even though the turn implies it, because a stale callback whose
 *   turn happens to be reused must still be distinguishable, and because diagnostics need the card
 *   identity without dereferencing a turn.
 * - [side] — *which visual state*? Distinct from [turnId] on purpose: a side change is a new
 *   document, not a new turn (STEP 31).
 * - [generation] — *which attempt*? Monotonic within a controller, incremented once per document
 *   load, including the re-presentation after a renderer-process failure (STEP 72/§73) and the
 *   re-presentation onto a freshly attached surface. Two loads of the same turn/card/side therefore
 *   never share a generation, so a callback from the dead attempt cannot be mistaken for the live
 *   one — and [INV-ANKI-RENDER-24][AnkiRenderRequestId] holds even when the *same* card comes back.
 *
 * This is a value, not a handle: it owns no WebView, no card content and no session state.
 */
data class AnkiRenderRequestId(
    val turnId: ReviewTurnId,
    val cardRef: AnkiCardRef,
    val side: AnkiCardSide,
    val generation: Long
) {
    init {
        require(generation > 0L) { "render generations start at 1; 0 means 'no request yet'" }
    }

    /** Content-free, log-safe identity string (`turn|card|SIDE|gen`). */
    val stableKey: String
        get() = "${turnId.value}|${cardRef.stableKey}|${side.name}|$generation"

    /** Same presentation, whichever side of it is showing (STEP 31). */
    fun isSameTurn(other: AnkiRenderRequestId): Boolean = turnId == other.turnId

    /** Same presentation *and* same side; generations may differ (a retry of the same target). */
    fun isSameTarget(other: AnkiRenderRequestId): Boolean =
        turnId == other.turnId && cardRef == other.cardRef && side == other.side

    /**
     * True when this callback's identity is not [active] — i.e. it belongs to a superseded document
     * and must be dropped (STEP 19). A `null` active request means the renderer has nothing in
     * flight, so *every* callback is stale.
     */
    fun isStaleFor(active: AnkiRenderRequestId?): Boolean = this != active

    /** This identity with the next generation: the same target, a new attempt. */
    fun nextGeneration(): AnkiRenderRequestId = copy(generation = generation + 1L)

    override fun toString(): String = stableKey
}
