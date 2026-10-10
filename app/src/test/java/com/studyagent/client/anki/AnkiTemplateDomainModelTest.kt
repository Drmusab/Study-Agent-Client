package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardTemplateMetadata
import com.studyagent.client.core.anki.AnkiCardTemplateRef
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelEnriched
import com.studyagent.client.core.anki.AnkiNoteModelField
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.AnkiNoteModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiTemplateDomainModelTest {

    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val modelRef = AnkiNoteModelRef(backendId, "12345")

    @Test
    fun `template ref requires non-negative ordinal`() {
        try {
            AnkiCardTemplateRef(modelRef, ordinal = -1)
            throw AssertionError("expected constructor to reject negative ordinal")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `template ref stable key contains all identity parts`() {
        val ref = AnkiCardTemplateRef(modelRef, ordinal = 2)
        val key = ref.stableKey
        assertTrue(key.contains(backendId.stableId))
        assertTrue(key.contains("12345"))
        assertTrue(key.contains("template"))
        assertTrue(key.contains("2"))
    }

    @Test
    fun `template ref foreign backend equality differs`() {
        val otherBackend = AnkiBackendId.Fake("test")
        val a = AnkiCardTemplateRef(modelRef, ordinal = 0)
        val b = AnkiCardTemplateRef(AnkiNoteModelRef(otherBackend, "12345"), ordinal = 0)
        assertNotEquals(a, b)
    }

    @Test
    fun `template metadata requires non-blank name`() {
        val ref = AnkiCardTemplateRef(modelRef, ordinal = 0)
        try {
            AnkiCardTemplateMetadata(ref = ref, name = "   ")
            throw AssertionError("expected constructor to reject blank name")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `template metadata hasSource true when qfmt present`() {
        val ref = AnkiCardTemplateRef(modelRef, ordinal = 0)
        val meta = AnkiCardTemplateMetadata(ref = ref, name = "Card 1", qfmt = "{{Front}}")
        assertTrue(meta.hasSource)
        assertNull(meta.afmt)
    }

    @Test
    fun `template metadata hasSource false when neither qfmt nor afmt`() {
        val ref = AnkiCardTemplateRef(modelRef, ordinal = 0)
        val meta = AnkiCardTemplateMetadata(ref = ref, name = "Card 1")
        assertFalse(meta.hasSource)
    }

    @Test
    fun `enriched model requires dense template ordinals`() {
        val model = AnkiNoteModel(
            ref = modelRef,
            name = "Basic",
            kind = AnkiNoteModelKind.NORMAL,
            fields = listOf(
                AnkiNoteModelField(0, "Front"),
                AnkiNoteModelField(1, "Back")
            ),
            templateCount = 2
        )
        // Non-dense ordinals should fail
        val t0 = AnkiCardTemplateMetadata(
            ref = AnkiCardTemplateRef(modelRef, ordinal = 0),
            name = "Card 1",
            qfmt = "{{Front}}"
        )
        val t2 = AnkiCardTemplateMetadata(
            ref = AnkiCardTemplateRef(modelRef, ordinal = 2), // Gap!
            name = "Card 3",
            qfmt = "{{Back}}"
        )
        try {
            AnkiNoteModelEnriched(model = model, templates = listOf(t0, t2))
            throw AssertionError("expected dense-ordinal invariant to reject gapped list")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `enriched model validates templates belong to the model`() {
        val otherRef = AnkiNoteModelRef(backendId, "99999")
        val model = AnkiNoteModel(
            ref = modelRef,
            name = "Basic",
            kind = AnkiNoteModelKind.NORMAL,
            fields = listOf(AnkiNoteModelField(0, "Front")),
            templateCount = 1
        )
        val t = AnkiCardTemplateMetadata(
            ref = AnkiCardTemplateRef(otherRef, ordinal = 0),
            name = "Card 1",
            qfmt = "{{Front}}"
        )
        try {
            AnkiNoteModelEnriched(model = model, templates = listOf(t))
            throw AssertionError("expected foreign template to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `enriched model validates sort field is in range`() {
        val model = AnkiNoteModel(
            ref = modelRef,
            name = "Basic",
            kind = AnkiNoteModelKind.NORMAL,
            fields = listOf(AnkiNoteModelField(0, "Front"), AnkiNoteModelField(1, "Back")),
            templateCount = 1
        )
        val t = AnkiCardTemplateMetadata(
            ref = AnkiCardTemplateRef(modelRef, ordinal = 0),
            name = "Card 1",
            qfmt = "{{Front}}"
        )
        // sortFieldIndex = 5 is out of range (only 2 fields)
        try {
            AnkiNoteModelEnriched(model = model, templates = listOf(t), sortFieldIndex = 5)
            throw AssertionError("expected out-of-range sort field to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        // 0 should work
        val ok = AnkiNoteModelEnriched(model = model, templates = listOf(t), sortFieldIndex = 0)
        assertEquals(0, ok.sortFieldIndex)
    }

    @Test
    fun `enriched model exposes convenience accessors`() {
        val model = AnkiNoteModel(
            ref = modelRef,
            name = "Cloze",
            kind = AnkiNoteModelKind.CLOZE,
            fields = listOf(AnkiNoteModelField(0, "Text"), AnkiNoteModelField(1, "Extra")),
            templateCount = 1
        )
        val t = AnkiCardTemplateMetadata(
            ref = AnkiCardTemplateRef(modelRef, ordinal = 0),
            name = "Cloze",
            qfmt = "{{cloze:Text}}",
            afmt = "{{cloze:Text}}<br>{{Extra}}"
        )
        val enriched = AnkiNoteModelEnriched(
            model = model,
            templates = listOf(t),
            css = ".card { color: black; }"
        )
        assertEquals(modelRef, enriched.ref)
        assertEquals("Cloze", enriched.name)
        assertEquals(AnkiNoteModelKind.CLOZE, enriched.kind)
        assertEquals(2, enriched.fields.size)
        assertEquals(1, enriched.templateCount)
        assertEquals(".card { color: black; }", enriched.css)
    }
}
