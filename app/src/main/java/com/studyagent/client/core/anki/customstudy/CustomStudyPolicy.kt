package com.studyagent.client.core.anki.customstudy

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiError

/**
 * GATE 20 — the one decision that turns discovered backend facts into Study-Agent product
 * semantics (DECISION-20-01/20-02/20-08/20-15, IMPLEMENTATION-20-08).
 *
 * ```
 * deck snapshot (live listing) + backend capabilities + backend rescheduling disclosure
 *   ↓ CustomStudyPolicy.evaluate
 * Proceed(mode)            → one StudySession, one backend, one scheduler authority
 * Refused(reason)          → nothing is started, nothing is dispatched
 * ```
 *
 * What this policy deliberately does **not** do:
 *
 * - it never builds, filters, sorts or counts cards (INV-20-03/INV-20-07);
 * - it never falls back: a filtered deck on a backend that cannot review one is refused, never
 *   studied as if it were an ordinary deck (INV-20-18);
 * - it never treats "unknown" as a mode: an unknown filtered state proceeds as [AnkiStudyMode.Normal]
 *   (the only constructible mode) while a *known* filtered deck is refused, and an undisclosed
 *   rescheduling behaviour is refused rather than presented as normal review (INV-20-16).
 *
 * It is a pure function so that the same rule is enforced at the two places that need it and can
 * be tested without a backend: the pre-binding start site
 * ([com.studyagent.client.data.repository.AnkiLocalStudyStarter]) and the binding moment inside the
 * study effect executor, where the deck listing has just been re-read.
 */
object CustomStudyPolicy {

    sealed interface Decision {
        /** Start this session in [mode]; the backend stays the only scheduler authority. */
        data class Proceed(val mode: AnkiStudyMode) : Decision

        /** Start nothing. [refusal] is a stable, content-free token; nothing else is needed to report it. */
        data class Refused(val refusal: Refusal) : Decision
    }

    /** Content-free refusal tokens. The UI maps them; nothing parses them. */
    enum class Refusal(val token: String) {
        /** The deck belongs to another backend than the one that would run the session. */
        FOREIGN_DECK("foreign_deck"),

        /**
         * The deck is a backend-native filtered/dynamic deck and this backend cannot review one
         * through its public contract.
         */
        FILTERED_DECK_REVIEW_UNSUPPORTED("filtered_deck_review_unsupported"),

        /**
         * The backend can review filtered decks but could not disclose, before the start, whether
         * answers reschedule cards. Refused rather than labelled normal review (INV-20-16).
         */
        FILTERED_RESCHEDULING_UNDISCLOSED("filtered_rescheduling_undisclosed")
    }

    /**
     * Evaluate one start attempt.
     *
     * @param deck the deck as the **live** listing reported it. [AnkiDeck.isFiltered] is nullable:
     *   `true` = the backend says filtered, `false` = the backend says not filtered, `null` = the
     *   backend did not say. Only `true` can trigger a filtered-mode decision; `null` is never
     *   upgraded to a claim in either direction.
     * @param backendId the backend that would own the session. A deck from another backend is
     *   refused instead of reinterpreted.
     * @param capabilities the backend's audited custom-study claims.
     * @param rescheduling what the backend disclosed about answers in this deck, **only** when the
     *   deck is known filtered. `null` means "not disclosed".
     */
    fun evaluate(
        deck: AnkiDeck,
        backendId: AnkiBackendId,
        capabilities: AnkiCustomStudyCapabilities,
        rescheduling: AnkiReschedulingDisclosure? = null
    ): Decision {
        if (deck.ref.backendId != backendId) return Decision.Refused(Refusal.FOREIGN_DECK)

        if (deck.isFiltered == true) {
            if (!capabilities.canReviewFilteredDeck) {
                return Decision.Refused(Refusal.FILTERED_DECK_REVIEW_UNSUPPORTED)
            }
            // The capability constructor already guarantees a provable commit and an exposed
            // rescheduling disclosure *exist* on this backend; this checks that they were actually
            // obtained for this deck, right now.
            val disclosed = rescheduling
                ?: return Decision.Refused(Refusal.FILTERED_RESCHEDULING_UNDISCLOSED)
            return Decision.Proceed(AnkiStudyMode.Filtered(deck.ref, disclosed))
        }

        // Ordinary deck review: the backend's scheduler draws from this deck and its descendants.
        // `isFiltered == null` stays here too: unknown is not a filtered mode, and the backend
        // never claimed one.
        return Decision.Proceed(AnkiStudyMode.Normal)
    }

    /** The typed error a refusal surfaces as. Never `null`, never a generic "something failed". */
    fun errorFor(refusal: Refusal): AnkiError = when (refusal) {
        Refusal.FOREIGN_DECK -> AnkiError.InvalidRequest(detail = "custom_study_foreign_deck")
        Refusal.FILTERED_DECK_REVIEW_UNSUPPORTED ->
            AnkiError.UnsupportedAction(action = "filtered_deck_review")
        Refusal.FILTERED_RESCHEDULING_UNDISCLOSED ->
            AnkiError.UnsupportedAction(action = "filtered_deck_rescheduling")
    }

    /** The refusal token as a stable string, for logs and diagnostics. */
    fun tokenFor(refusal: Refusal): String = refusal.token
}
