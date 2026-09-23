package com.studyagent.client.core.anki

/**
 * GATE 07 — the scheduled-identity → normalized-content rules (STEP 42/§53/§54).
 *
 * This is deliberately a pure, stateless object instead of another orchestrating abstraction:
 * the AnkiDroid gateway already owns "provider row → [AnkiRenderedCard]", and the backend owns
 * "is this session allowed to read". What is left is the *composition contract* every backend
 * must obey when scheduled identity and hydrated content meet — and that contract must also be
 * executable against the fake backend with no Android at all (STEP 92).
 *
 * The rules, in one place (INV-ANKI-CARD-02/23/28/30):
 *
 * 1. **Identity is confirmed, not compared for equality** ([identityMatches]). GATE 06 schedules
 *    cards by `noteId + ordinal` and GATE 07 answers with the *current* full identity (card id
 *    included). Enriching a ref is not proof of same-card (see [AnkiCardRef]), so every identity
 *    component the scheduled ref *knows* must be confirmed by the hydrated one. A scheduled
 *    component the answer cannot confirm is a mismatch — fail safely, never attach the wrong
 *    card (STEP 54).
 * 2. **Media lists merge without duplicates** ([mergeMedia]) — scheduled references first,
 *    hydrated additions after, first-seen order preserved (STEP 36/§37), stable per-entry
 *    identity used for deduplication. No resolution of any kind happens here (INV-ANKI-CARD-20).
 * 3. **Attach preserves the turn** ([attach]) — same [ReviewTurnId], same scheduled card. The
 *    scheduler metadata GATE 06 obtained (rating options, buttons, next-review labels) survives
 *    untouched (INV-ANKI-CARD-23). A late result therefore *cannot* install itself onto a
 *    different presentation: it either verifies against the turn it is attached to or is
 *    rejected, and installers compare turn ids (INV-ANKI-CARD-30).
 */
object AnkiCardHydration {

    /**
     * True when [hydrated] confirms every identity component [scheduled] knows (STEP 54).
     *
     * Directional on purpose: a scheduled ref addressed by `noteId + ord` must be confirmed on
     * those fields, while a hydrated ref that *adds* a card id is fine — enrichment is allowed,
     * unconfirmed claims are not. Collection identity is strict equality: a known collection key
     * on either side that the other does not share is a different collection
     * (INV-ANKI-CARD-03/§13/§47), and unknown (`null`) is never a wildcard.
     */
    fun identityMatches(scheduled: AnkiCardRef, hydrated: AnkiCardRef): Boolean {
        if (scheduled.backendId != hydrated.backendId) return false
        if (scheduled.collectionKey != hydrated.collectionKey) return false
        if (scheduled.cardId != null && scheduled.cardId != hydrated.cardId) return false
        if (scheduled.noteId != null && scheduled.noteId != hydrated.noteId) return false
        if (scheduled.cardOrd != null && scheduled.cardOrd != hydrated.cardOrd) return false
        return true
    }

    /**
     * Union of the scheduled and hydrated media references (STEP 36/§37).
     *
     * Deduplicated by a stable per-entry key (name/uri/stream id), preserving first-seen order —
     * scheduled references first. Order is kept because it can affect playback/rendering
     * sequencing later; alphabetical sorting would be an unasked-for reordering (§37). Entries
     * stay *references* (INV-ANKI-CARD-20): nothing is opened, probed or resolved.
     */
    fun mergeMedia(
        scheduled: List<AnkiMediaRef>,
        hydrated: List<AnkiMediaRef>
    ): List<AnkiMediaRef> {
        val merged = ArrayList<AnkiMediaRef>(scheduled.size + hydrated.size)
        val seen = HashSet<String>(scheduled.size + hydrated.size)
        for (reference in scheduled) {
            if (seen.add(mediaKey(reference))) merged.add(reference)
        }
        for (reference in hydrated) {
            if (seen.add(mediaKey(reference))) merged.add(reference)
        }
        return merged
    }

    /**
     * Identity-verified composition of one scheduled card and its hydrated content (§54/§36).
     *
     * On success the returned card carries the *merged* media list and — when the provider
     * reports a current deck that differs from the scheduled one — a `card_deck_moved` token in
     * [AnkiRenderedCard.degradations]. A deck difference is deliberately **not** an error and
     * deliberately **not** an overwrite (STEP 28/§29/§30): filtered decks legitimately move
     * cards temporarily, the scheduled turn keeps its own deck context, the rendered card keeps
     * the provider's current deck as its own fact, and `AnkiCardMetadata.originalDeckRef`
     * preserves home-deck ownership so later Card Details can tell the two apart.
     */
    fun compose(
        scheduled: AnkiScheduledCard,
        hydrated: AnkiRenderedCard
    ): AnkiResult<AnkiRenderedCard> {
        if (!identityMatches(scheduled.ref, hydrated.ref)) {
            return AnkiResult.Failure(
                AnkiError.StaleCardReference(card = scheduled.ref, detail = "card_identity_mismatch")
            )
        }
        val degradations = ArrayList<String>(hydrated.degradations)
        val scheduledDeck = scheduled.deckRef
        val currentDeck = hydrated.deckRef
        // Filtered-deck semantics *explain* a deck difference when the card's home deck
        // (`originalDeckRef`) is the scheduled deck and the card is temporarily sitting in a
        // filtered deck. Anything else is an unexplained move: keep both facts and surface the
        // observation as a token. Never rewrite either side (STEP 29/§30).
        val explainedByFilteredDeck = hydrated.metadata.originalDeckRef == scheduledDeck
        if (currentDeck != null && currentDeck != scheduledDeck && !explainedByFilteredDeck) {
            if (DECK_MOVED !in degradations) degradations.add(DECK_MOVED)
        }
        return AnkiResult.Success(
            hydrated.copy(
                media = mergeMedia(scheduled.media, hydrated.media),
                degradations = degradations
            )
        )
    }

    /**
     * Identity-verified attach of hydrated content to its turn (STEP 53/§54/INV-ANKI-CARD-22).
     *
     * The turn keeps its [ReviewTurnId] and its scheduled card; only the content phase moves
     * from [AnkiReviewTurnContent.Scheduled] to [AnkiReviewTurnContent.Rendered]. Re-attaching
     * current content to an already-hydrated turn is allowed and idempotent (STEP 45/§50): the
     * latest authoritative content wins, still under the same turn identity.
     *
     * Cancellation is never involved here (pure function — INV-ANKI-CARD-29 is the backends'
     * rule), and a result for a *different* card is [AnkiError.StaleCardReference], which is
     * exactly how a late hydration from an old turn is refused when it tries to land on a
     * different presentation (INV-ANKI-CARD-30, test T).
     */
    fun attach(
        turn: AnkiReviewTurn,
        hydrated: AnkiRenderedCard
    ): AnkiResult<AnkiReviewTurn> =
        when (val composed = compose(turn.scheduledCard, hydrated)) {
            is AnkiResult.Failure -> composed
            is AnkiResult.Success -> AnkiResult.Success(
                turn.copy(content = AnkiReviewTurnContent.Rendered(composed.value, turn.scheduledCard))
            )
        }

    private fun mediaKey(reference: AnkiMediaRef): String = when (reference) {
        is AnkiMediaRef.BackendStream -> "stream:${reference.streamId}"
        is AnkiMediaRef.ContentUri -> "uri:${reference.uri}"
        is AnkiMediaRef.RemoteUrl -> "url:${reference.url}"
        is AnkiMediaRef.Unavailable -> "unavailable:${reference.reason}"
    }

    /** Content-free degradation token: the provider's current deck differs from the scheduled. */
    const val DECK_MOVED: String = "card_deck_moved"
}
