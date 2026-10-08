package com.studyagent.client.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Repository source audit for GATE 16's read-only, exact-identity details boundary. */
class Gate16ArchitectureAuditTest {
    private val clientRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private fun source(relative: String): String {
        val file = File(clientRoot, "src/main/java/com/studyagent/client/$relative")
        check(file.isFile) { "Missing GATE 16 source $relative" }
        return file.readText()
    }

    @Test
    fun `deep card details model is pure and keeps card note and ordinal identities separate`() {
        val file = File(clientRoot, "src/main/java/com/studyagent/client/core/anki/AnkiCardDetails.kt")
        val text = file.readText()
        assertTrue(text.contains("val cardRef: AnkiCardRef"))
        assertTrue(text.contains("val noteRef: AnkiNoteRef?"))
        assertTrue(text.contains("val cardOrd: Int?"))
        assertTrue(text.contains("val fields: List<AnkiNoteField>?"))
        assertTrue(text.contains("val questionHtml: String?"))
        assertTrue(text.contains("val questionText: String?"))
        assertTrue(text.contains("val pureAnswerText: String?"))
        assertFalse(file.readLines().any { it.startsWith("import android.") || it.startsWith("import androidx.") })
        assertFalse(file.readLines().any { it.startsWith("import com.studyagent.client.data.") })
    }

    @Test
    fun `one backend method coordinates exact card and note reads, never card browser scans`() {
        val backend = source("data/anki/ankidroid/AnkiDroidBackend.kt")
            .substringAfter("override suspend fun getCardDetails")
            .substringBefore("fun noteGatewayDiagnostics")
        assertTrue(backend.contains("cardGateway.queryCard(authority, cardRef)"))
        assertTrue(backend.contains("detailsNoteGateway.queryNoteDetails"))
        assertTrue(backend.contains("DataIntegrityFailure"))
        assertFalse(backend.contains("browseCards("))
        assertFalse(backend.contains("nextCard("))
    }

    @Test
    fun `details UI and ViewModel have no mutation calls or provider types`() {
        val ui = listOf(
            source("ui/screens/carddetails/CardDetailsViewModel.kt"),
            source("ui/screens/carddetails/CardDetailsMapper.kt"),
            source("ui/screens/carddetails/CardDetailsScreen.kt"),
            source("ui/screens/carddetails/CardDetailsModels.kt"),
            source("ui/components/anki/NoteFieldsSection.kt"),
            source("ui/components/anki/CardMetadataSection.kt"),
            source("ui/components/anki/CardSchedulingSection.kt")
        ).joinToString("\n")
        val mutations = Regex("\\b(commitRating|answerCard|setFlag|buryCard|suspendCard|updateNote|editNote|deleteNote|nextCard)\\s*\\(")
        assertFalse(mutations.containsMatchIn(ui))
        assertFalse(Regex("ContentResolver|FlashCardsContract|android\\.database\\.Cursor|AnkiConnect|com\\.studyagent\\.client\\.data\\.")
            .containsMatchIn(ui))
        assertTrue(source("ui/screens/carddetails/CardDetailsViewModel.kt").contains("getCardDetails(token.cardRef)"))
    }

    @Test
    fun `source note fields are mapped from flds and never reconstructed from rendered HTML`() {
        val mapper = source("data/anki/ankidroid/AnkiDroidNoteMapper.kt")
        val detailsMapper = source("data/anki/ankidroid/AnkiDroidCardDetailsMapper.kt")
        assertTrue(mapper.contains("NOTE_FIELDS_COLUMN"))
        assertTrue(mapper.contains("MODEL_FIELD_NAMES_COLUMN"))
        assertTrue(mapper.contains("AnkiNoteField(name = name, value = values[index], ordinal = index)"))
        assertTrue(detailsMapper.contains("fields = note.fields"))
        assertFalse(detailsMapper.contains("parseFieldsFromHtml"))
    }

    @Test
    fun `original card reuses the established renderer and adds no WebView stack`() {
        val screen = source("ui/screens/carddetails/CardDetailsScreen.kt")
        assertTrue(screen.contains("AnkiCardRenderer("))
        assertTrue(screen.contains("AnkiRenderSurfaceKind.BROWSING"))
        assertFalse(Regex("WebView\\s*\\(|WebViewClient\\s*\\(|AndroidView\\s*\\(").containsMatchIn(screen))
        assertTrue(screen.contains("AnkiCardSide.QUESTION"))
        assertTrue(screen.contains("AnkiCardSide.ANSWER"))
    }

    @Test
    fun `unknown stored due stays raw and null scheduling is not zero`() {
        val cardMapper = source("data/anki/ankidroid/AnkiDroidCardMapper.kt")
        val screenMapper = source("ui/screens/carddetails/CardDetailsMapper.kt")
        assertTrue(cardMapper.contains("parseStoredLong"))
        assertTrue(cardMapper.contains("rawDue = rawDue"))
        assertTrue(cardMapper.contains("rawOriginalDue = rawOriginalDue"))
        assertTrue(screenMapper.contains("raw; backend-defined units"))
        assertFalse(screenMapper.contains("rawDue /"))
        assertFalse(screenMapper.contains("rawDue *"))
    }

    @Test
    fun `asynchronous requests bind backend collection card reference and generation`() {
        val vm = source("ui/screens/carddetails/CardDetailsViewModel.kt")
        assertTrue(vm.contains("val backendId: AnkiBackendId"))
        assertTrue(vm.contains("val collectionKey: String?"))
        assertTrue(vm.contains("val cardRef: AnkiCardRef"))
        assertTrue(vm.contains("val generation: Long"))
        assertTrue(vm.contains("backend === token.backend"))
        assertTrue(vm.contains("cardRef == token.cardRef"))
        assertTrue(vm.contains("StaleCardReference"))
    }

    @Test
    fun `HTML source values are text-only in Compose and card routes carry typed identity`() {
        val mapper = source("ui/screens/carddetails/CardDetailsMapper.kt")
        val fields = source("ui/components/anki/NoteFieldsSection.kt")
        val routes = source("ui/navigation/Screen.kt")
        val nav = source("ui/navigation/AppNavHost.kt")
        assertTrue(mapper.contains("safePlainText"))
        assertTrue(fields.contains("SelectionContainer"))
        assertFalse(fields.contains("WebView"))
        assertTrue(routes.contains("fun createRoute(cardRef: AnkiCardRef)"))
        assertTrue(nav.contains("ankiBackendRegistry.find(cardRef.backendId)"))
        assertTrue(nav.contains("CardDetailsViewModel(initialBackend = detailsBackend, initialCardRef = cardRef)"))
        assertFalse(nav.contains("AnkiRenderedCard"))
    }
}
