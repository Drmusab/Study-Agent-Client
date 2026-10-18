package com.studyagent.client.core.anki

/**
 * GATE 21M — a **read-only** view of the durable transaction families that already exist
 * (CHECKPOINT 21M-17; AUDIT-21M-R2-14).
 *
 * Why this vocabulary exists at all: the four ledgers are authoritative, each with its own record
 * type, its own status enum and its own store. A troubleshooting surface needs their *unresolved*
 * counts without being handed any of them. So the composition root exposes this projection and
 * nothing else — an interface with one read method and no write path (AUDIT-21M-R2-13).
 *
 * What is deliberately preserved rather than interpreted:
 *
 * - the **owner identity of each record** ([AnkiUnresolvedTransactionFact.backendId]): an unresolved
 *   transaction belongs to the backend it was bound to, never to the current preference, never to
 *   whichever backend happens to be ready, and never to a kind (DECISION-21-15, INV-21-03/04);
 * - the **family's own status token**, verbatim: `CONFLICT` and `AMBIGUOUS` stay separate because they
 *   are separate enum values in the family that defined them, and a timeout is never presented as
 *   proof that a mutation failed or succeeded.
 *
 * What must never appear here: card or note content, field values, tag text, deck names, media paths,
 * record ids or a collection key value. This projection carries a family, an owner identity and a
 * status token — enough to count, not enough to leak.
 */
enum class AnkiTransactionFamily {
    REVIEW_COMMIT,
    REVIEWER_ACTION,
    NOTE_MUTATION,
    NOTE_CREATION
}

/**
 * One unresolved record, reduced to what a read-only surface may show: its family, the backend it is
 * bound to (`null` only when the record itself carries no owner, which is reported, not assumed), and
 * its own status token.
 */
data class AnkiUnresolvedTransactionFact(
    val family: AnkiTransactionFamily,
    val backendId: AnkiBackendId?,
    val statusToken: String
)

/**
 * The result of one read.
 *
 * `loaded` distinguishes "we asked" from "we never asked". [unavailableFamilies] are families whose
 * read was refused or whose store could not be read; [notProjectedFamilies] are families this surface
 * was never given a projection for. Neither may be rendered as a zero count, because "could not read
 * it" and "nothing is unresolved" are different statements about durable data.
 */
data class AnkiUnresolvedTransactionRead(
    val facts: List<AnkiUnresolvedTransactionFact>,
    val loaded: Boolean,
    val unavailableFamilies: Set<AnkiTransactionFamily> = emptySet(),
    val notProjectedFamilies: Set<AnkiTransactionFamily> = emptySet()
) {
    companion object {
        /** Nothing was read: every family must be reported as not projected. */
        val NOT_READ = AnkiUnresolvedTransactionRead(
            facts = emptyList(),
            loaded = false,
            notProjectedFamilies = AnkiTransactionFamily.entries.toSet()
        )
    }
}

/**
 * The only transaction authority a diagnostics surface may hold: read the unresolved projections once.
 *
 * No retry-all, no force-commit, no clear, no delete, no reassignment and no backend call is
 * expressible through it. Recovery stays with each family's own coordinator, on the backend the record
 * is bound to.
 */
interface AnkiUnresolvedTransactionReader {
    suspend fun read(): AnkiUnresolvedTransactionRead
}
