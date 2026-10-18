package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCustomStudyCapabilities
import com.studyagent.client.data.preferences.toEncodedBackendPreference
import com.studyagent.client.ui.components.anki.AnkiBackendInstanceRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendUnimplementedKindRowUi
import com.studyagent.client.ui.components.anki.USER_SELECTABLE_BACKEND_KINDS
import com.studyagent.client.ui.components.anki.buildAnkiBackendSectionUi
import com.studyagent.client.ui.components.anki.buildCustomStudyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 21M Revision 2 — the **R-20-2 boundary** as the selection UI must honour it.
 *
 * R-20-2 is the finding that no production PC Anki backend exists: `PcAnkiAvailabilitySource` is
 * structure only, the composition root registers exactly one configured backend (AnkiDroid), and GATE
 * 21L is BLOCKED, so no Custom Study lifecycle exists either. GATE 21M is a presentation gate, and its
 * job at that boundary is to *show the truth* without making it selectable:
 *
 * - no operational PC entry may appear while no PC instance is registered;
 * - a PC row may not become selectable because a PC **agent** (AI / STT / TTS) is reachable;
 * - no Custom Study operation may be claimed for any backend this build wires;
 * - a Fake PC backend is permitted **only** as an architectural / UI-identity fixture, and even then it
 *   is rendered as a test double that cannot be selected, because the preference repository refuses to
 *   persist a Fake identity in the first place.
 *
 * The exclusion is derived from the registry, never hard-coded per kind, so wiring a real audited PC
 * adapter later activates the row without editing a domain model or a UI file.
 */
class Gate21mR20_2BoundaryTest {

    private val capabilities = AnkiCapabilities(review = true, scheduledReview = true, deckListing = true)
    private val ankiDroid = AnkiBackendId.AnkiDroidLocal

    private fun state(
        id: AnkiBackendId,
        availability: AnkiAvailability = AnkiAvailability.Ready(capabilities)
    ) = AnkiBackendState(AnkiBackendDescriptor(id, availability, capabilities), capabilities)

    private fun section(
        states: List<AnkiBackendState>,
        preference: BackendPreference = BackendPreference.Auto,
        claims: Map<AnkiBackendId, AnkiCustomStudyCapabilities> = emptyMap()
    ) = buildAnkiBackendSectionUi(
        states = states,
        preference = preference,
        customStudyClaims = claims
    )

    // ------------------------------------------------------------------ the production inventory

    @Test
    fun `the production inventory yields no operational PC entry and one informational PC row`() {
        // This is what the composition root registers today: one configured AnkiDroid backend.
        val model = section(listOf(state(ankiDroid)))

        val pcRows = model.configuredInstanceRows.filter { it.kindToken == AnkiBackendKind.PC_AGENT.name }
        assertTrue("no PC instance is registered, so none may be rendered", pcRows.isEmpty())

        val informational = model.rows.filterIsInstance<AnkiBackendUnimplementedKindRowUi>().single()
        assertEquals(AnkiBackendKind.PC_AGENT, informational.kind)
        assertFalse(informational.selectable)
        assertFalse(informational.selected)
        assertEquals("BACKEND_KIND_NOT_IMPLEMENTED", informational.statusToken)
        // A connected PC agent is not a PC Anki backend, and the copy says so explicitly.
        assertTrue(informational.explanation.contains("is not an Anki backend"))
        assertTrue(informational.explanation.contains("never makes this row ready"))
        assertFalse(
            "the informational row must not claim readiness",
            informational.statusLabel.contains("Ready", ignoreCase = true)
        )
    }

    @Test
    fun `the composition root still registers exactly one configured backend`() {
        val container = source("di/AppContainer.kt")

        assertTrue(container.contains("AnkiBackendRegistry(listOf(ankiDroidBackend))"))
        assertEquals(1, Regex("AnkiBackendRegistry\\(").findAll(container).count())
        assertEquals(1, Regex("AnkiDroidBackend\\(").findAll(container).count())
        assertFalse("no production PC backend is constructed", container.contains("PcAnkiBackend("))
        assertFalse("no production PC identity is allocated", container.contains("AnkiBackendId.PcAgent("))
        // The PC readiness structure stays structure-only: nothing in production calls it.
        val offenders = mainSources().filter { file ->
            val code = code(file)
            code.contains("PcAnkiAvailabilitySource") || code.contains("PcAnkiAvailabilityMapper")
        }.map { it.name }
        assertEquals(listOf("PcAnkiAvailabilitySource.kt"), offenders)
    }

    @Test
    fun `the exclusion follows the registry, so a wired adapter would activate the row`() {
        val pc = AnkiBackendId.PcAgent("home")

        val before = section(listOf(state(ankiDroid)))
        assertTrue(before.rows.any { it is AnkiBackendUnimplementedKindRowUi && it.kind == AnkiBackendKind.PC_AGENT })

        val after = section(listOf(state(ankiDroid), state(pc)))
        assertFalse(after.rows.any { it is AnkiBackendUnimplementedKindRowUi && it.kind == AnkiBackendKind.PC_AGENT })
        val row = after.configuredInstanceRows.single { it.backendId == pc }
        assertTrue("a registered instance is selectable", row.selectable)
        assertEquals("pc_agent:home", row.key)
        // Nothing in the UI layer decided this: the same builder produced both answers.
        assertFalse(AnkiBackendKind.FAKE in USER_SELECTABLE_BACKEND_KINDS)
    }

    // ------------------------------------------------------------------ Custom Study (GATE 20 / 21L)

    @Test
    fun `the only production backend claims no custom study, so none may be offered`() {
        assertEquals(AnkiCustomStudyCapabilities.NONE, AnkiDroidCustomStudyCapabilities.AUDITED)
        assertFalse(AnkiDroidCustomStudyCapabilities.AUDITED.supportsAnyCustomStudy)
        assertFalse(AnkiDroidCustomStudyCapabilities.AUDITED.canCreateFilteredSession)
        assertFalse(AnkiDroidCustomStudyCapabilities.AUDITED.canReviewFilteredDeck)

        val status = buildCustomStudyStatus(AnkiDroidCustomStudyCapabilities.AUDITED, "AnkiDroid")
        assertEquals("CUSTOM_STUDY_NOT_IMPLEMENTED", status.token)
        assertEquals("Custom Study: not implemented", status.headline)
        // No lifecycle wording that could read as an available control.
        listOf("Rebuild", "End session", "Start", "Create").forEach { word ->
            assertFalse("no `$word` control wording", status.explanation.contains(word))
        }
    }

    @Test
    fun `the section discloses the production claim and never a filtered-session operation`() {
        val model = section(
            listOf(state(ankiDroid)),
            claims = mapOf(ankiDroid to AnkiDroidCustomStudyCapabilities.AUDITED)
        )
        val row = model.configuredInstanceRows.single()

        assertNotNull(row.customStudy)
        assertEquals("CUSTOM_STUDY_NOT_IMPLEMENTED", row.customStudy!!.token)
        assertFalse(row.customStudy!!.canCreateFilteredSession)
        // The action vocabulary holds no custom-study entry at all.
        val all = code(File(mainRoot, "ui/components/anki/AnkiBackendSectionUi.kt"))
        assertFalse(all.contains("START_CUSTOM_STUDY"))
        assertFalse(all.contains("REBUILD"))
        assertFalse(all.contains("END_SESSION"))
    }

    @Test
    fun `a fixture that claims filtered sessions still gets no lifecycle in this build`() {
        val claiming = AnkiCustomStudyCapabilities(
            canReviewFilteredDeck = true,
            canProveFilteredRatingCommit = true,
            canExposeReschedulingSemantics = true,
            canFilterByDeck = true
        )
        assertTrue(claiming.supportsAnyCustomStudy)

        val status = buildCustomStudyStatus(claiming, "Test backend")
        assertEquals("CUSTOM_STUDY_LIFECYCLE_NOT_IMPLEMENTED", status.token)
        assertTrue(status.explanation.contains("not implemented in this build"))
        listOf("Rebuild", "End session").forEach { word ->
            assertFalse("no `$word` control wording", status.explanation.contains(word))
        }
    }

    // ------------------------------------------------------------------ Fake fixtures

    @Test
    fun `a registered test double is rendered as one and cannot be selected`() {
        val fake = AnkiBackendId.Fake("gate21m-identity")
        val model = section(listOf(state(ankiDroid), state(fake)))

        val row = model.configuredInstanceRows.single { it.backendId == fake }
        assertTrue(row.testDouble)
        assertFalse("a fixture is never an operational choice", row.selectable)
        assertEquals("fake:gate21m-identity", row.key)
        assertEquals("fake:gate21m-identity", row.identityToken)
        assertEquals(AnkiBackendKind.FAKE.name, row.kindToken)
        assertTrue(row.explanation!!.contains("never be selected as an operational Anki backend"))
        // A test double is not a PC instance: the informational PC row is still there, and still true.
        assertTrue(
            model.rows.any {
                it is AnkiBackendUnimplementedKindRowUi && it.kind == AnkiBackendKind.PC_AGENT
            }
        )
    }

    @Test
    fun `selectability follows registration, which is why a fixture PC instance must never be wired`() {
        // A UI-identity fixture may register a PC instance to prove the row renders by identity. The
        // model then treats it as a configured instance, because that is what registration *means*:
        // there is no per-kind exception to fall back on. Production therefore must never register one,
        // and the composition-root audit above is what enforces that.
        val fixture = AnkiBackendId.PcAgent("fixture-only")
        val model = section(listOf(state(ankiDroid), state(fixture)))

        val row = model.configuredInstanceRows.single { it.backendId == fixture }
        assertFalse(row.testDouble)
        assertTrue(row.selectable)
        assertEquals("pc_agent:fixture-only", row.identityToken)
        assertFalse(
            model.rows.any {
                it is AnkiBackendUnimplementedKindRowUi && it.kind == AnkiBackendKind.PC_AGENT
            }
        )
    }

    @Test
    fun `a fake identity cannot be persisted, so production can never pin one`() {
        assertNull(BackendPreference.Explicit(AnkiBackendId.Fake("x")).toEncodedBackendPreference())
        // A Fake kind preference is refused even earlier: at construction.
        val refusal = try {
            BackendPreference.ExplicitKind(AnkiBackendKind.FAKE)
            "no refusal"
        } catch (failure: IllegalArgumentException) {
            failure.message
        }
        assertEquals("FAKE is not a user-selectable kind", refusal)
        assertNull(BackendPreference.InvalidStored(
            com.studyagent.client.core.anki.BackendPreferenceInvalidIssue.UNKNOWN_MODE
        ).toEncodedBackendPreference())
        // The identities a user may express are encodable.
        assertNotNull(BackendPreference.Auto.toEncodedBackendPreference())
        assertNotNull(BackendPreference.Explicit(ankiDroid).toEncodedBackendPreference())
        assertNotNull(BackendPreference.Explicit(AnkiBackendId.PcAgent("home")).toEncodedBackendPreference())
    }

    @Test
    fun `a pinned fake is shown as stored and unselectable, never converted to AUTO`() {
        val fake = AnkiBackendId.Fake("x")
        val model = section(listOf(state(ankiDroid), state(fake)), BackendPreference.Explicit(fake))

        val row = model.configuredInstanceRows.single { it.backendId == fake }
        assertEquals("fake:x", model.selectedKey)
        assertTrue("the stored preference is reported as it is", row.selected)
        assertFalse("and it is still not an operational choice", row.selectable)
        assertFalse("nothing was rewritten to AUTO", model.rows.first().selected)
        assertEquals("explicit:fake:x", model.preferenceToken)
    }

    // ------------------------------------------------------------------ no per-backend UI branching

    @Test
    fun `the settings surface has no PC-specific branch and no hard-coded exclusion`() {
        val screen = code(File(mainRoot, "ui/screens/settings/SettingsScreen.kt"))
        val sectionModel = code(File(mainRoot, "ui/components/anki/AnkiBackendSectionUi.kt"))

        assertFalse(screen.contains("PcAgent"))
        assertFalse(screen.contains("AnkiDroidLocal"))
        // Copy may name a kind; identity decisions may not.
        assertTrue(sectionModel.contains("AnkiBackendKind.PC_AGENT -> \"PC Anki\""))
        assertFalse(sectionModel.contains("is AnkiBackendId.PcAgent"))
        assertFalse(sectionModel.contains("is AnkiBackendId.AnkiDroidLocal"))
    }

    // ------------------------------------------------------------------ helpers

    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private val mainRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")

    private fun mainSources(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun source(relative: String): String = code(File(mainRoot, relative))

    /** Comments removed, so a document may explain a forbidden spelling while live code may not use it. */
    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)^\\s*//.*$"), " ")

    @Test
    fun `an unavailable production backend stays selectable so choosing it yields a typed refusal`() {
        val model = section(
            listOf(
                state(ankiDroid, AnkiAvailability.PermissionRequired("provider not granted"))
            )
        )
        val row = model.configuredInstanceRows.single()

        assertTrue("unavailable is not unselectable (INV-21-10)", row.selectable)
        assertEquals("PERMISSION_REQUIRED", row.statusToken)
        assertFalse(row.readyNow)
        // The free text a backend publishes is not rendered; the token and the reason are.
        assertFalse(row.explanation?.contains("provider not granted") ?: false)
        assertEquals("PERMISSION_REQUIRED", row.unavailableReasonToken)
        assertEquals(
            com.studyagent.client.core.anki.AnkiBackendUnavailableReason.PERMISSION_REQUIRED.retryability.name,
            row.retryabilityToken
        )
        assertTrue(row is AnkiBackendInstanceRowUi)
    }
}
