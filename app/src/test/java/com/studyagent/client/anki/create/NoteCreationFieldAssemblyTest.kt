package com.studyagent.client.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelField
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.create.AddNoteDraft
import com.studyagent.client.core.anki.create.CreationMediaKind
import com.studyagent.client.core.anki.create.NoteCreationFieldAssembly
import com.studyagent.client.core.anki.create.PendingMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GATE 18 — field assembly (CONTRACT-18-14/26). Fields reference ONLY backend-confirmed media
 * names, using exactly Anki's public reference syntax. Nothing is invented, and an incomplete set
 * of stored names refuses assembly instead of guessing.
 */
class NoteCreationFieldAssemblyTest {

    private val backend = AnkiBackendId.AnkiDroidLocal

    private fun model(fieldCount: Int = 2) = AnkiNoteModel(
        ref = AnkiNoteModelRef(backend, "model-basic"),
        name = "Basic",
        kind = AnkiNoteModelKind.NORMAL,
        fields = List(fieldCount) { i -> AnkiNoteModelField(ordinal = i, name = "Field $i") },
        templateCount = 1,
        defaultDeckId = "1"
    )

    private fun media(kind: CreationMediaKind, target: Int = 0) = PendingMedia(
        contentUri = "content://picker/${target}",
        sourceName = "clip",
        mimeType = if (kind == CreationMediaKind.IMAGE) "image/png" else "audio/mpeg",
        extension = if (kind == CreationMediaKind.IMAGE) "png" else "mp3",
        sizeBytes = 10,
        kind = kind,
        targetFieldOrdinal = target
    )

    private fun draft(media: List<PendingMedia>, values: Map<Int, String>) = AddNoteDraft(
        backendId = backend,
        collectionKey = null,
        model = model(),
        fieldValues = values,
        tags = emptyList(),
        media = media
    )

    @Test
    fun `no media returns the fields exactly as typed`() {
        val d = draft(emptyList(), mapOf(0 to "front", 1 to "back"))
        assertEquals(listOf("front", "back"), NoteCreationFieldAssembly.assembleWithoutMedia(d))
    }

    @Test
    fun `image references use img syntax and append to the target field`() {
        val d = draft(listOf(media(CreationMediaKind.IMAGE, target = 1)), mapOf(0 to "q", 1 to "answer"))
        val assembled = NoteCreationFieldAssembly.assemble(d, listOf("pic_1.png"))
        assertEquals(listOf("q", "answer<br><img src=\"pic_1.png\">"), assembled)
    }

    @Test
    fun `audio references use sound syntax`() {
        val d = draft(listOf(media(CreationMediaKind.AUDIO, target = 0)), mapOf(0 to "listen", 1 to "b"))
        val assembled = NoteCreationFieldAssembly.assemble(d, listOf("clip_2.mp3"))
        assertEquals(listOf("listen<br>[sound:clip_2.mp3]", "b"), assembled)
    }

    @Test
    fun `a blank target field becomes the reference alone`() {
        val d = draft(listOf(media(CreationMediaKind.IMAGE, target = 1)), mapOf(0 to "q", 1 to ""))
        val assembled = NoteCreationFieldAssembly.assemble(d, listOf("pic.png"))
        assertEquals(listOf("q", "<img src=\"pic.png\">"), assembled)
    }

    @Test
    fun `multiple media into the same field keep their order`() {
        val d = draft(
            listOf(media(CreationMediaKind.IMAGE, 0), media(CreationMediaKind.AUDIO, 0)),
            mapOf(0 to "x", 1 to "y")
        )
        val assembled = NoteCreationFieldAssembly.assemble(d, listOf("a.png", "b.mp3"))
        assertEquals(listOf("x<br><img src=\"a.png\"><br>[sound:b.mp3]", "y"), assembled)
    }

    @Test
    fun `incomplete stored names refuse assembly`() {
        val d = draft(listOf(media(CreationMediaKind.IMAGE), media(CreationMediaKind.AUDIO)),
            mapOf(0 to "x", 1 to "y"))
        assertNull(NoteCreationFieldAssembly.assemble(d, listOf("a.png")))
        assertNull(NoteCreationFieldAssembly.assemble(d, listOf("a.png", "")))
        assertNull(NoteCreationFieldAssembly.assemble(d, listOf("a.png", "  ")))
        assertNull(NoteCreationFieldAssembly.assemble(d, emptyList()))
    }
}
