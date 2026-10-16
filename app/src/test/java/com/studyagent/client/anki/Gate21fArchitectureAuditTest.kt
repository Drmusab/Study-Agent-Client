package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 21F source audit. Behavioral isolation tests live in the controller, Library, Browser and
 * Card Details test classes; this audit pins the wiring and rejects parallel/fallback read paths.
 */
class Gate21fArchitectureAuditTest {
    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }
    private val clientRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")
    private val docsRoot: File get() = File(appRoot.parentFile, "docs")

    /** Strip comments before enforcing rules; string literals and route-token text remain intact. */
    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)^\\s*//.*$"), " ")

    private fun source(relative: String): String {
        val file = File(clientRoot, relative)
        check(file.isFile) { "Missing GATE 21F source: $relative" }
        return file.code()
    }

    @Test
    fun `AUDIT-21F-01 locked 21A to 21E contracts authorize this handoff`() {
        val gateA = File(docsRoot, "GATE_21A_DISCOVERY.md").readText()
        val gateB = File(docsRoot, "GATE_21B_CONTRACT_LOCK.md").readText()
        val gateC = File(docsRoot, "GATE_21C_IDENTITY_IMPLEMENTATION.md").readText()
        val gateD = File(docsRoot, "GATE_21D_REGISTRY_IMPLEMENTATION.md").readText()
        val gateE = File(docsRoot, "GATE_21E_RESOLVER_IMPLEMENTATION.md").readText()
        assertTrue(gateA.contains("GATE 21A — DISCOVERY LOCKED"))
        assertTrue(gateB.contains("GATE 21B — CONTRACT LOCKED"))
        assertTrue(gateC.contains("GATE 21C: LOCKED"))
        assertTrue(gateD.contains("GATE 21D:                 LOCKED"))
        assertTrue(gateE.contains("GATE 21E:                 PASS (harness-verified)"))
        assertTrue(gateE.contains("NEXT AUTHORIZED SUB-GATE: 21F"))
        // The checked-in report's wording is intentionally left untouched; the session owner also
        // confirmed 21E locked, and the final gate report will preserve that documentary distinction.
    }

    @Test
    fun `AUDIT-21F-02 one core identity keeps backend collection and generation separate`() {
        val context = source("core/anki/AnkiReadContext.kt")
        assertTrue(context.contains("data class AnkiReadContext("))
        assertTrue(context.contains("val backendContext: AnkiBackendContext"))
        assertTrue(context.contains("val generation: AnkiReadContextGeneration"))
        assertTrue(context.contains("val backendId: AnkiBackendId get() = backendContext.backendId"))
        assertTrue(context.contains("val collectionKey: String? get() = backendContext.collectionKey"))
        assertTrue(context.contains("data class AnkiReadRequestIdentity("))
        assertTrue(context.contains("val context: AnkiReadContext"))
        assertTrue(context.contains("val queryGeneration: Long"))
        assertTrue(context.contains("val requestGeneration: Long"))
        assertTrue(context.contains("val cursor: AnkiPageCursor? = null"))
    }

    @Test
    fun `AUDIT-21F-03 only new contexts resolve and existing routes bind exact identity`() {
        val controller = source("data/anki/context/AnkiReadContextController.kt")
        val newContext = controller.substringAfter("suspend fun bindNewContext(")
            .substringBefore("suspend fun bindReferenceContext(")
        val exactContext = controller.substringAfter("private suspend fun bindExactContext(")
            .substringBefore("private fun resolutionFailure(")
        val continued = controller.substringAfter("suspend fun continueContext(")
            .substringBefore("fun invalidate(binding:")
        assertTrue(newContext.contains("AnkiBackendResolver2.resolve"))
        assertTrue(newContext.contains("preference.value != capturedPreference"))
        assertTrue(exactContext.contains("registry.find(backendId)"))
        assertFalse(exactContext.contains("AnkiBackendResolver2.resolve"))
        assertTrue(continued.contains("sourceContext.backendId != resourceBackendId"))
        assertTrue(continued.contains("sourceContext.collectionKey"))
        assertFalse(continued.contains("AnkiBackendResolver2.resolve"))
        assertTrue(continued.contains("requiredCapabilities"))
    }

    @Test
    fun `AUDIT-21F-04 lease membership and read access reject before and after every operation`() {
        val binding = source("data/anki/context/AnkiReadContextBinding.kt")
        val controller = source("data/anki/context/AnkiReadContextController.kt")
        val access = controller.substringAfter("class AnkiReadContextAccess")
        assertTrue(binding.contains("isStillRegistered: () -> Boolean"))
        assertTrue(binding.contains("val isValid: Boolean get() = locallyValid.get() && lease.isValid"))
        assertTrue(controller.contains("isStillRegistered = { registry.find(backend.id) === backend }"))
        val preflight = access.indexOf("validateBinding(binding, requiredCapabilities)")
        val backendCall = access.indexOf("operation(binding.backend)")
        val postflight = access.lastIndexOf("validateBinding(binding, requiredCapabilities)")
        assertTrue(preflight >= 0 && preflight < backendCall)
        assertTrue(postflight > backendCall)
        assertTrue(access.contains("validateResult(result.value)"))
        assertTrue(access.contains("binding.revokeContext()"))
        assertTrue(access.contains("requiredCapabilities"))
    }

    @Test
    fun `AUDIT-21F-05 resource routes serialize exact backend collection and context generation`() {
        val routes = source("ui/navigation/Screen.kt")
        assertTrue(routes.contains("context.backendId.stableId"))
        assertTrue(routes.contains("context.collectionKey"))
        assertTrue(routes.contains("context.generation.value"))
        assertTrue(routes.contains("deckRef.backendId.stableId"))
        assertTrue(routes.contains("deckRef.collectionKey"))
        assertTrue(routes.contains("cardRef.backendId.stableId"))
        assertTrue(routes.contains("cardRef.collectionKey"))
        assertTrue(routes.contains("fun decodeReadContext(token: String?): AnkiReadContext?"))
        assertFalse(routes.contains("fun decodeDeckId"))
        assertTrue(routes.contains("data object AddNote : Screen(\"add-note/{backendId}\")"))
    }

    @Test
    fun `AUDIT-21F-06 navigation binds references and preserves existing Study and Add Note owners`() {
        val nav = source("ui/navigation/AppNavHost.kt")
        assertTrue(nav.contains("AnkiReadContextRequest.New("))
        assertTrue(nav.contains("AnkiReadContextRequest.Reference("))
        assertTrue(nav.contains("AnkiReadContextRequest.Continue("))
        assertTrue(nav.contains("AnkiRequiredCapability.DECKS"))
        assertTrue(nav.contains("AnkiRequiredCapability.CARD_BROWSER"))
        assertTrue(nav.contains("AnkiRequiredCapability.CARD_DETAILS"))
        // GATE 21K amendment — the 21F interim Add Note pin ("creation destination stays explicitly
        // AnkiDroid"; `container.ankiDroidBackend.id` seed and the `!== container.ankiDroidBackend`
        // route guard) is lifted by its owning gate per DECISION-21B-21 and identity contract
        // §4.4: the route is now seeded from GATE 21E resolution for the new creation context and
        // binds that exact backend through the registry, with the creation capability set.
        assertTrue(nav.contains("container.resolveAddNoteSeedBackendId()"))
        assertTrue(nav.contains("addNoteBackendId?.let(container.ankiBackendRegistry::find)"))
        assertTrue(nav.contains("CREATION_CONTEXT_REQUIREMENTS"))
        assertFalse(nav.contains("container.ankiDroidBackend"))
        assertTrue(nav.contains("coordinator = container.noteCreationCoordinator"))
        assertTrue(nav.contains("studyStarter = container.ankiLocalStudyStarter"))
    }

    @Test
    fun `AUDIT-21F-07 every implemented screen read declares its operation capability`() {
        val library = source("data/anki/AnkiLibraryRepository.kt")
        val addNote = source("ui/screens/addnote/AddNoteViewModel.kt")
        val browser = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        val details = source("ui/screens/carddetails/CardDetailsViewModel.kt")
        assertEquals(2, Regex("requiredCapabilities = setOf\\(AnkiRequiredCapability.DECKS\\)")
            .findAll(library).count())
        assertTrue(library.contains("operation = { it.getDecks() }"))
        assertTrue(library.contains("operation = { it.getSelectedDeck() }"))
        assertTrue(addNote.contains("requiredCapabilities = setOf(AnkiRequiredCapability.NOTE_MODEL_LISTING)"))
        assertTrue(addNote.contains("requiredCapabilities = setOf(AnkiRequiredCapability.DECKS)"))
        assertTrue(addNote.contains("operation = { it.getNoteModels() }"))
        assertTrue(addNote.contains("operation = { it.getDecks() }"))
        assertTrue(browser.contains("requiredCapabilities = setOf(AnkiRequiredCapability.CARD_BROWSER)"))
        assertTrue(browser.contains("operation = { it.browseCards(request) }"))
        assertTrue(details.contains("requiredCapabilities = setOf(AnkiRequiredCapability.CARD_DETAILS)"))
        assertTrue(details.contains("operation = { it.getCardDetails(token.cardRef) }"))
    }

    @Test
    fun `AUDIT-21F-08 library caches publish only atomically under the exact context and request`() {
        val repository = source("data/anki/AnkiLibraryRepository.kt")
        assertTrue(repository.contains("val binding: AnkiReadContextBinding"))
        assertTrue(repository.contains("context = binding.context"))
        assertTrue(repository.contains("snapshot.readContext != requestIdentity.context"))
        assertTrue(repository.contains("requestIdentity.requestGeneration != generation"))
        assertTrue(repository.contains("_state.value = ready"))
        assertTrue(repository.contains("cached = null"))
        assertTrue(repository.contains("it.backendId == backend.id && it.readContext == binding.context"))
        assertTrue(repository.contains("AnkiReadContextAccess.isContextSafetyFailure(error)"))
        assertFalse(repository.contains("var activeBackend"))
    }

    @Test
    fun `AUDIT-21F-09 Browser pagination and Card Details requests carry immutable context identity`() {
        val browser = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        val details = source("ui/screens/carddetails/CardDetailsViewModel.kt")
        assertTrue(browser.contains("val identity: AnkiReadRequestIdentity"))
        assertTrue(browser.contains("cursor = cursor"))
        assertTrue(browser.contains("token.identity.context == binding.context"))
        assertTrue(browser.contains("token.identity.requestGeneration == latestRequestGeneration"))
        assertTrue(browser.contains("token.cursor == null || nextCursor == token.cursor"))
        assertTrue(browser.contains("item.noteRef?.let"))
        assertTrue(browser.contains("item.deckRef?.let"))
        assertFalse(browser.contains("fun switchBackend"))
        assertFalse(browser.contains("fun switchCollection"))
        assertTrue(details.contains("val identity: AnkiReadRequestIdentity"))
        assertTrue(details.contains("token.identity.context == binding.context"))
        assertTrue(details.contains("details.noteRef?.let"))
        assertTrue(details.contains("details.deckRef?.let"))
        assertTrue(details.contains("details.originalDeckRef?.let"))
        assertFalse(details.contains("fun switchBackend"))
    }

    @Test
    fun `AUDIT-21F-10 UI exposes no unimplemented deep Model or Template read surface`() {
        val uiSources = File(clientRoot, "ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.code() }
            .toList()
        assertTrue(uiSources.any { it.contains("getNoteModels()") })
        assertFalse(uiSources.any { it.contains("getNoteModel(") })
        assertFalse(uiSources.any { it.contains("getNoteTemplates(") })
        assertFalse(uiSources.any { it.contains("getNoteModelSchema(") })
    }

    @Test
    fun `AUDIT-21F-11 context wiring adds no second resolver or mutation owner`() {
        val container = source("di/AppContainer.kt")
        val contextFiles = File(clientRoot, "data/anki/context").listFiles()
            ?.filter { it.isFile && it.extension == "kt" }.orEmpty()
        assertEquals(1, contextFiles.count { it.name == "AnkiReadContextController.kt" })
        assertTrue(container.contains("AnkiReadContextController("))
        assertTrue(container.contains("preference = backendPreferenceRepository.preference"))
        assertFalse(contextFiles.any { it.name.contains("Resolver", ignoreCase = true) })
        val controller = source("data/anki/context/AnkiReadContextController.kt")
        assertFalse(controller.contains("NoteCreationCoordinator"))
        assertFalse(controller.contains("NoteMutationCoordinator"))
        assertFalse(controller.contains("StudySessionRepository"))
        assertFalse(controller.contains("nextCard("))
    }
}
