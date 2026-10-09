package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardDetails
import java.text.Normalizer

/**
 * GATE 17 CONTRACT-15 / CONTRACT-06 — what the pinned Anki backend does to a value on the way into
 * the collection, and therefore how a post-write read may legitimately differ from the payload.
 *
 * This is *evidence-driven tolerance*, not a heuristic. Every rule below is a transformation the
 * backend provably performs itself, so a difference produced by it cannot mean "the write did not
 * apply":
 *
 * - **Fields.** `anki` rslib 25.09.2 `notes/mod.rs` `normalize_field` (called from
 *   `prepare_for_update` inside `update_note_inner`) removes every ASCII control character except
 *   `\n` and `\t`, and applies NFC when the collection's `NormalizeNoteText` preference is on. That
 *   preference is not readable through the AnkiDroid public provider, so NFC is treated as
 *   optional: both sides are compared in NFC.
 * - **Tags.** rslib `tags/register.rs` `canonify_tags` splits on space/U+3000, drops empties,
 *   NFC-normalizes each component, removes ASCII control characters, replaces a blank component with
 *   `blank`, adopts the case of an already-registered tag or parent, de-duplicates
 *   case-insensitively (first instance wins) and stores the result **sorted**. Its own unit test
 *   pins `["foo","FOO"] -> ["foo"]`, `["FOO"] -> ["foo"]` when `foo` is registered, and
 *   `"\u{fa47}" -> "\u{6f22}"`. Tag order and tag case are therefore backend-owned and carry no
 *   information about whether the write applied.
 *
 * What this file deliberately does NOT do: it never treats "the note now looks like the draft" as
 * proof that *this* transaction applied (INV-17-07/08). It is a state comparison used to upgrade the
 * provider's weak row-count answer into "the stored state matches the intent". Transaction
 * correlation does not exist at this pin, and nothing here claims it.
 */
object NoteContentCanonicalization {

    /**
     * ASCII control characters the backend strips from a field value (`invalid_char_for_field`).
     *
     * U+001F is deliberately NOT stripped here even though rslib would strip it: it is the provider's
     * field separator, so a value containing it is refused upstream ([NoteEditPlanner.validateDraft]
     * and the AnkiDroid mapper) and, if it ever reached a comparison, keeping it makes the comparison
     * fail loudly instead of hiding a broken field structure.
     */
    private const val FIELD_SEPARATOR_CHAR: Char = '\u001F'

    private fun isStrippedFieldChar(c: Char): Boolean =
        c.code < 0x20 && c != '\n' && c != '\t' && c != FIELD_SEPARATOR_CHAR

    /**
     * The field value the backend will store, minus the transformations it always applies. Used both
     * to build the payload (so what is sent is what can be stored) and to compare after the write.
     */
    fun fieldValue(raw: String): String = buildString(raw.length) {
        for (c in raw) if (!isStrippedFieldChar(c)) append(c)
    }

    /** NFC form. Android and the JVM both provide `java.text.Normalizer`. */
    fun nfc(raw: String): String =
        if (Normalizer.isNormalized(raw, Normalizer.Form.NFC)) raw else Normalizer.normalize(raw, Normalizer.Form.NFC)

    /** True when two stored field lists are the same content as far as the backend is concerned. */
    fun fieldsEquivalent(intended: List<String>, stored: List<String>): Boolean {
        if (intended.size != stored.size) return false
        return intended.indices.all { index ->
            nfc(fieldValue(intended[index])) == nfc(fieldValue(stored[index]))
        }
    }

    /**
     * True when two tag lists denote the same tag set. Order is ignored (the backend sorts) and case
     * is ignored (the backend adopts the registered case). Each side is NFC-normalized first.
     */
    fun tagsEquivalent(intended: List<String>, stored: List<String>): Boolean =
        tagKeys(intended) == tagKeys(stored)

    private fun tagKeys(tags: List<String>): Set<String> =
        tags.map { nfc(it.trim()).lowercase() }.filter { it.isNotEmpty() }.toSet()

    /**
     * A tag whose `::` components are not all non-blank is *rewritten* by the backend (a blank
     * component becomes the literal `blank`), so the stored tag would not be the requested tag. Such
     * input is refused before any transaction instead of being written and then failing verification.
     */
    fun tagHasBlankHierarchyComponent(tag: String): Boolean =
        tag.split("::").any { component -> component.isBlank() }
}

/** Outcome of comparing one authoritative post-write read with the payload that was sent. */
sealed interface NoteMutationVerification {
    /** The stored state is the intended state, allowing only backend-owned canonicalization. */
    data object MatchesIntent : NoteMutationVerification

    /** The stored state is not the intended state. The write cannot be reported as APPLIED. */
    data class DiffersFromIntent(val detail: String) : NoteMutationVerification

    /** The read needed for the comparison is missing or unusable. Nothing is claimed either way. */
    data class Unverifiable(val detail: String) : NoteMutationVerification
}

/**
 * GATE 17 — the post-write verification step (CONTRACT-15, and the resolution of the PART 0 finding
 * that a provider row count proves only that the provider code path completed).
 *
 * Pure: it compares [sent] with one [AnkiCardDetails] read and never touches a backend, a ledger or
 * a clock, so every rule is unit-testable on the JVM.
 */
object NoteMutationVerifier {

    /**
     * @param sent the materialized steps that were actually dispatched, in plan order.
     * @param refreshed the authoritative read taken after the last step confirmed.
     */
    fun verify(
        sent: List<NoteMutationStep>,
        refreshed: AnkiCardDetails?,
        backendId: AnkiBackendId
    ): NoteMutationVerification {
        if (sent.isEmpty()) return NoteMutationVerification.Unverifiable("nothing_sent")
        if (refreshed == null) return NoteMutationVerification.Unverifiable("post_write_read_unavailable")
        if (refreshed.cardRef.backendId != backendId) {
            return NoteMutationVerification.Unverifiable("post_write_read_foreign_backend")
        }

        val content = sent.filterIsInstance<NoteMutationStep.UpdateNoteContent>()
        val fieldPayload = content.lastOrNull { it.fieldValues != null }?.fieldValues
        if (fieldPayload != null) {
            val stored = refreshed.fields
                ?: return NoteMutationVerification.Unverifiable("post_write_fields_unavailable")
            if (stored.size != fieldPayload.size) {
                return NoteMutationVerification.DiffersFromIntent("field_count")
            }
            val storedValues = stored.sortedBy { it.ordinal }.map { it.value }
            if (!NoteContentCanonicalization.fieldsEquivalent(fieldPayload, storedValues)) {
                return NoteMutationVerification.DiffersFromIntent("field_values")
            }
        }

        val tagPayload = content.lastOrNull { it.tags != null }?.tags
        if (tagPayload != null) {
            val stored = refreshed.tags
                ?: return NoteMutationVerification.Unverifiable("post_write_tags_unavailable")
            if (!NoteContentCanonicalization.tagsEquivalent(tagPayload, stored)) {
                return NoteMutationVerification.DiffersFromIntent("tags")
            }
        }

        val deckStep = sent.filterIsInstance<NoteMutationStep.ChangeDeck>().lastOrNull()
        if (deckStep != null) {
            val storedDeck = refreshed.deckRef
                ?: return NoteMutationVerification.Unverifiable("post_write_deck_unavailable")
            if (storedDeck.backendId != backendId || storedDeck.deckId != deckStep.toDeck.deckId) {
                return NoteMutationVerification.DiffersFromIntent("card_deck")
            }
        }

        return NoteMutationVerification.MatchesIntent
    }

    /**
     * Read-only evidence for a human who has to decide an AMBIGUOUS mutation: how the collection's
     * current state relates to what this mutation intended.
     *
     * This is STATE COMPARISON ONLY. It is never transaction-correlated evidence and it never changes
     * a status by itself (INV-17-07/08/09): another editor could have produced the same state, and a
     * matching state does not prove that *this* write applied. It exists so the person deciding can
     * see the same facts the app can see.
     */
    fun verifyIntent(
        record: NoteMutationRecord,
        intent: NoteMutationIntent,
        refreshed: AnkiCardDetails?
    ): NoteMutationVerification {
        if (refreshed == null) return NoteMutationVerification.Unverifiable("post_write_read_unavailable")
        if (refreshed.cardRef.backendId != record.backendId) {
            return NoteMutationVerification.Unverifiable("post_write_read_foreign_backend")
        }
        val fields = refreshed.fields?.sortedBy { it.ordinal }?.map { it.value }
        if (record.changedFields.isNotEmpty()) {
            if (fields == null) return NoteMutationVerification.Unverifiable("fields_unavailable")
            for (mark in record.changedFields) {
                val intended = intent.fieldValues[mark.ordinal]
                    ?: return NoteMutationVerification.Unverifiable("payload_not_retained")
                val stored = fields.getOrNull(mark.ordinal)
                    ?: return NoteMutationVerification.DiffersFromIntent("field_count")
                if (nfc(NoteContentCanonicalization.fieldValue(intended)) != nfc(stored)) {
                    return NoteMutationVerification.DiffersFromIntent("field_values")
                }
            }
        }
        if (record.tagsChanged) {
            val intendedTags = intent.tags ?: return NoteMutationVerification.Unverifiable("payload_not_retained")
            val storedTags = refreshed.tags ?: return NoteMutationVerification.Unverifiable("tags_unavailable")
            if (!NoteContentCanonicalization.tagsEquivalent(intendedTags, storedTags)) {
                return NoteMutationVerification.DiffersFromIntent("tags")
            }
        }
        val deckChange = record.deckChange
        if (deckChange != null && record.lastEnteredOperation >= 1) {
            val storedDeck = refreshed.deckRef
                ?: return NoteMutationVerification.Unverifiable("deck_unavailable")
            if (storedDeck.deckId != deckChange.toDeck.deckId) {
                return NoteMutationVerification.DiffersFromIntent("card_deck")
            }
        }
        return NoteMutationVerification.MatchesIntent
    }

    private fun nfc(text: String): String = NoteContentCanonicalization.nfc(text)
}

/**
 * The content one mutation intended to write. Held in memory by the coordinator for the lifetime of
 * the process and never persisted: the durable record carries ordinals, names and refs only.
 */
data class NoteMutationIntent(
    val fieldValues: Map<Int, String>,
    val tags: List<String>?,
    val targetDeckId: String?
)
