package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendUnavailableReason
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendResolutionFailure
import com.studyagent.client.core.anki.BackendResolutionReason
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.ui.components.anki.AnkiBackendAutoRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendInstanceRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendSectionUiState
import com.studyagent.client.ui.components.anki.AnkiCustomStudyStatusUi
import com.studyagent.client.ui.components.anki.buildAnkiBackendSectionUi
import com.studyagent.client.ui.components.anki.buildCustomStudyStatus
import com.studyagent.client.ui.components.anki.capabilityGroupOrder
import com.studyagent.client.ui.components.anki.displayTitle
import com.studyagent.client.ui.components.anki.groupCapabilities
import com.studyagent.client.ui.components.anki.uiGroupTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21M Revision 2 — capability-aware availability presentation.
 *
 * The invariant under test is BACKEND_SELECTION_POLICY §2.1: **implemented** and **available now** are
 * two dimensions, and collapsing them produces one of two lies — "supported" read as "usable now", or
 * "disconnected" read as "not implemented". Both directions are asserted here, plus the totality of the
 * capability vocabulary (a new `AnkiRequiredCapability` cannot silently disappear from the section) and
 * the preservation of the locked availability/reason vocabulary as tokens.
 */
class Gate21mCapabilityAvailabilityUiTest {

    private val fullCapabilities = AnkiCapabilities(
        review = true,
        scheduledReview = true,
        deckListing = true,
        deckCounts = true,
        renderedCards = true,
        cardDetails = true,
        editNoteFields = true,
        editNoteTags = true,
        flags = true,
        bury = true,
        suspendCards = true,
        media = true,
        createNotes = true,
        noteModelListing = true
    )

    private val ankiDroid = AnkiBackendId.AnkiDroidLocal

    private fun state(
        id: AnkiBackendId = ankiDroid,
        availability: AnkiAvailability = AnkiAvailability.Ready(fullCapabilities),
        runtime: AnkiCapabilities = fullCapabilities,
        implemented: AnkiCapabilities? = fullCapabilities
    ): AnkiBackendState = AnkiBackendState(
        descriptor = AnkiBackendDescriptor(id, availability, runtime),
        implementedClaim = implemented
    )

    private fun row(
        state: AnkiBackendState,
        preference: BackendPreference = BackendPreference.Auto,
        preview: BackendResolutionResult2? = null,
        required: Set<AnkiRequiredCapability> = STUDY_SESSION_REQUIREMENTS,
        customStudy: AnkiCustomStudyCapabilities? = null
    ): AnkiBackendInstanceRowUi = buildAnkiBackendSectionUi(
        states = listOf(state),
        preference = preference,
        autoPreview = preview,
        customStudyClaims = customStudy?.let { mapOf(state.backendId to it) } ?: emptyMap(),
        requiredCapabilities = required
    ).configuredInstanceRows.single()

    private fun capability(
        row: AnkiBackendInstanceRowUi,
        required: AnkiRequiredCapability
    ) = row.capabilities.single { it.capability == required }

    // ------------------------------------------------------- SUPPORTED is not READY (both ways)

    @Test
    fun `an implemented capability on a disconnected backend is supported and not available now`() {
        val row = row(state(availability = AnkiAvailability.AgentDisconnected))
        val editFields = capability(row, AnkiRequiredCapability.EDIT_NOTE_FIELDS)

        assertEquals("SUPPORTED", editFields.implemented.name)
        assertEquals("NOT_READY", editFields.operational.name)
        assertEquals("Supported", editFields.implementedLabel)
        assertEquals("Not available now", editFields.currentStateLabel)
        assertFalse("usableNow is positive knowledge on BOTH dimensions", editFields.usableNow)
        assertFalse("the backend itself is not ready", row.readyNow)
        assertEquals("AGENT_DISCONNECTED", row.statusToken)
        assertEquals("TRANSPORT_UNAVAILABLE", row.unavailableReasonToken)
        assertEquals(
            AnkiBackendUnavailableReason.TRANSPORT_UNAVAILABLE.retryability.name,
            row.retryabilityToken
        )
    }

    @Test
    fun `a ready backend still reports the capabilities it does not implement`() {
        // `fullCapabilities` deliberately leaves the card browser at NONE: readiness of the backend
        // says nothing about an operation it cannot perform (BACKEND_SELECTION_POLICY §2.1).
        val row = row(state())
        val browser = capability(row, AnkiRequiredCapability.CARD_BROWSER)
        val editing = capability(row, AnkiRequiredCapability.EDIT_NOTE_FIELDS)

        assertTrue("the backend is ready", row.readyNow)
        assertEquals("READY", row.statusToken)
        assertEquals("UNSUPPORTED", browser.implemented.name)
        assertEquals("Not implemented", browser.implementedLabel)
        assertEquals("NOT_READY", browser.operational.name)
        assertFalse(browser.usableNow)
        // The same row, the same backend, the same moment: another capability IS usable now.
        assertEquals("SUPPORTED", editing.implemented.name)
        assertEquals("READY", editing.operational.name)
        assertTrue(editing.usableNow)
        assertNull("a ready backend has no unavailable reason", row.unavailableReasonToken)
        assertNull(row.retryabilityToken)
    }

    @Test
    fun `capability evidence distinguishes a static claim from a runtime negotiation and from nothing`() {
        assertEquals(
            "STATIC_CLAIM",
            row(state(implemented = fullCapabilities)).capabilityEvidenceToken
        )
        assertEquals(
            "RUNTIME_NEGOTIATED",
            row(state(implemented = null)).capabilityEvidenceToken
        )
        assertEquals(
            "NONE",
            row(state(availability = AnkiAvailability.Checking, implemented = null)).capabilityEvidenceToken
        )
    }

    @Test
    fun `an unchecked backend claims neither support nor refusal`() {
        val row = row(state(availability = AnkiAvailability.Checking, implemented = null))
        val review = capability(row, AnkiRequiredCapability.REVIEW)

        assertEquals("UNKNOWN", review.implemented.name)
        assertEquals("Unknown", review.implementedLabel)
        assertEquals("Not checked yet", review.currentStateLabel)
        assertFalse(review.usableNow)
        assertFalse("checking is not readiness", row.readyNow)
    }

    // ------------------------------------------------------- vocabulary preservation + totality

    @Test
    fun `every availability state keeps its own status token and a non-blank text label`() {
        val availabilities = listOf(
            AnkiAvailability.Checking,
            AnkiAvailability.NotInstalled,
            AnkiAvailability.ProviderUnavailable(),
            AnkiAvailability.PermissionRequired(),
            AnkiAvailability.CollectionNotInitialized,
            AnkiAvailability.Ready(fullCapabilities),
            AnkiAvailability.TemporarilyUnavailable(),
            AnkiAvailability.AgentDisconnected,
            AnkiAvailability.AgentAuthenticationRequired,
            AnkiAvailability.AgentAnkiUnavailable,
            AnkiAvailability.Unsupported("unsupported_operation"),
            AnkiAvailability.Fault(AnkiError.BackendUnavailable("fault"))
        )
        assertEquals("the locked availability vocabulary is total", 12, availabilities.size)

        availabilities.forEach { availability ->
            val row = row(state(availability = availability))
            assertEquals(
                "the token is AnkiAvailability.statusCode, never localized copy",
                availability.statusCode,
                row.statusToken
            )
            assertTrue(
                "a text label always exists, so colour is never the only indicator ($availability)",
                row.statusLabel.isNotBlank()
            )
        }
    }

    @Test
    fun `every required capability has a title and a display group, and no row is lost by grouping`() {
        val row = row(state())

        assertEquals(AnkiRequiredCapability.entries.size, row.capabilities.size)
        AnkiRequiredCapability.entries.forEach { required ->
            assertTrue("blank title for $required", required.displayTitle.isNotBlank())
            assertTrue(
                "group heading for $required must be in the reading order",
                required.uiGroupTitle in capabilityGroupOrder
            )
            assertEquals(
                "${required.name}=${capability(row, required).implemented.name}/" +
                    capability(row, required).operational.name,
                capability(row, required).statusToken
            )
        }

        val groups = groupCapabilities(row.capabilities)
        assertEquals(
            "grouping is a partition: nothing is dropped or duplicated",
            row.capabilities.map { it.capability }.toSet(),
            groups.flatMap { it.capabilities }.map { it.capability }.toSet()
        )
        assertEquals(row.capabilities.size, groups.sumOf { it.capabilities.size })
        assertEquals(
            "groups appear in the declared reading order",
            groups.map { it.title },
            capabilityGroupOrder.filter { title -> groups.any { it.title == title } }
        )
        assertEquals(groups, row.capabilityGroups)
    }

    // ------------------------------------------------------- CHECKPOINT 21M-06 (GATE 20 / 21L)

    @Test
    fun `a backend with no custom-study claim says so and offers no lifecycle control`() {
        val status = buildCustomStudyStatus(AnkiCustomStudyCapabilities.NONE, "AnkiDroid")

        assertEquals(AnkiCustomStudyStatusUi.NOT_IMPLEMENTED, status.token)
        assertEquals("CUSTOM_STUDY_NOT_IMPLEMENTED", status.token)
        assertFalse(status.supportsAnyCustomStudy)
        assertFalse(status.canCreateFilteredSession)
        assertFalse(status.canReviewFilteredDeck)
        // GATE 21L is BLOCKED: no create / rebuild / end wording may be offered as an action.
        listOf("Rebuild", "End session", "Start Custom Study", "Create filtered").forEach {
            assertFalse("no lifecycle control wording: `$it`", status.explanation.contains(it))
            assertFalse(status.headline.contains(it))
        }
        assertTrue(status.explanation.contains("Ordinary deck browsing is not Custom Study"))
    }

    @Test
    fun `a backend that claims part of the vocabulary still reports the missing lifecycle`() {
        // GATE 20's claim type is coherent by construction: preview-only study *is* the
        // no-reschedule mode, so it cannot be claimed without it.
        val claiming = AnkiCustomStudyCapabilities(canPreviewOnly = true, canSelectNoReschedule = true)
        assertTrue("the fixture really claims something", claiming.supportsAnyCustomStudy)
        assertFalse("the fixture claims no filtered-session lifecycle", claiming.canCreateFilteredSession)

        val status = buildCustomStudyStatus(claiming, "AnkiDroid")
        assertEquals(AnkiCustomStudyStatusUi.LIFECYCLE_NOT_IMPLEMENTED, status.token)
        assertEquals("CUSTOM_STUDY_LIFECYCLE_NOT_IMPLEMENTED", status.token)
        assertTrue(status.supportsAnyCustomStudy)
        assertTrue(status.explanation.contains("not implemented in this build"))
    }

    @Test
    fun `the section carries a custom-study disclosure only for backends that make a claim`() {
        val claimed = row(state(), customStudy = AnkiCustomStudyCapabilities.NONE)
        val unclaimed = row(state())

        assertNotNull(claimed.customStudy)
        assertNull("no claim, no disclosure invented", unclaimed.customStudy)
    }

    // ------------------------------------------------------- CHECKPOINT 21M-08 (AUTO preview)

    @Test
    fun `the AUTO preview names what it resolved and what it resolved against`() {
        val resolved = BackendResolutionResult2.Resolved(
            backendId = ankiDroid,
            reason = BackendResolutionReason.AUTO_LOCAL_PREFERRED
        )

        val label = autoLabel(resolved)
        assertTrue(label, label.startsWith("Would use AnkiDroid for "))
        assertTrue(label, label.contains("a new review session"))
        assertEquals("AUTO_PREVIEW_RESOLVED", autoRow(resolved).statusToken)

        // The same preview resolved for a narrower requirement may not borrow the review wording.
        val narrow = autoLabel(resolved, required = setOf(AnkiRequiredCapability.MEDIA_READ))
        assertTrue(narrow, narrow.contains("a new operation needing Media read"))
        assertFalse(narrow, narrow.contains("review session"))
    }

    @Test
    fun `every AUTO preview failure produces an honest sentence and its own token`() {
        val cases = listOf(
            BackendResolutionResult2.NoBackendConfigured(considered = emptySet()) to
                "AUTO_PREVIEW_NO_BACKEND_CONFIGURED",
            BackendResolutionResult2.ExplicitBackendKindUnavailable(
                requestedKind = com.studyagent.client.core.anki.AnkiBackendKind.PC_AGENT
            ) to "AUTO_PREVIEW_EXPLICIT_BACKEND_UNAVAILABLE",
            BackendResolutionResult2.ExplicitBackendMissing(
                pinnedId = AnkiBackendId.PcAgent("gone"),
                considered = emptySet()
            ) to "AUTO_PREVIEW_EXPLICIT_BACKEND_MISSING",
            BackendResolutionResult2.NoEligibleBackend(
                considered = setOf(ankiDroid),
                consideredReasons = mapOf(ankiDroid to AnkiBackendUnavailableReason.PERMISSION_REQUIRED),
                error = AnkiError.BackendUnavailable("no_eligible_backend")
            ) to "AUTO_PREVIEW_NO_ELIGIBLE_BACKEND",
            BackendResolutionResult2.ExplicitBackendUnavailable(
                pinnedId = ankiDroid,
                reason = AnkiBackendUnavailableReason.PERMISSION_REQUIRED,
                considered = setOf(ankiDroid),
                error = AnkiError.BackendUnavailable("explicit_backend_unavailable")
            ) to "AUTO_PREVIEW_EXPLICIT_BACKEND_UNAVAILABLE",
            BackendResolutionResult2.CapabilityUnsupported(
                required = STUDY_SESSION_REQUIREMENTS,
                missing = mapOf(ankiDroid to setOf(AnkiRequiredCapability.CARD_BROWSER)),
                considered = setOf(ankiDroid)
            ) to "AUTO_PREVIEW_CAPABILITY_UNSUPPORTED",
            BackendResolutionResult2.Ambiguous(
                candidates = setOf(AnkiBackendId.PcAgent("home"), AnkiBackendId.PcAgent("work")),
                considered = setOf(AnkiBackendId.PcAgent("home"), AnkiBackendId.PcAgent("work"))
            ) to "AUTO_PREVIEW_AMBIGUOUS_CONFIGURATION",
            BackendResolutionResult2.ConfigurationInvalid(detail = "unknown_mode") to
                "AUTO_PREVIEW_CONFIGURATION_INVALID"
        )

        cases.forEach { (failure, expectedToken) ->
            val auto = autoRow(failure)
            assertEquals("$failure", expectedToken, auto.statusToken)
            assertTrue("$failure produced a blank label", auto.statusLabel.isNotBlank())
            assertEquals(
                "$failure must be classified as a typed failure",
                BackendResolutionFailure::class.java.isInstance(
                    (failure as BackendResolutionResult2.Failed).failure
                ),
                true
            )
        }

        assertTrue(autoLabel(cases[5].first).contains("missing: Card browser"))
        assertTrue(autoLabel(cases[6].first).contains("Choose one instance"))
        assertTrue(autoLabel(cases[3].first).contains("No backend is eligible right now"))
    }

    @Test
    fun `an absent preview claims nothing`() {
        assertEquals("Not resolved yet", autoLabel(null))
        assertEquals("AUTO_PREVIEW_NOT_RESOLVED", autoRow(null).statusToken)

        val empty = buildAnkiBackendSectionUi(states = emptyList(), preference = BackendPreference.Auto)
        assertEquals("AUTO_PREVIEW_NO_BACKEND_CONFIGURED", empty.rows.first().statusToken)
    }

    @Test
    fun `the AUTO row never promises a merged collection or a session failover`() {
        val explanation = AnkiBackendAutoRowUi.AUTO_EXPLANATION

        assertTrue(explanation.contains("never merges two collections"))
        assertTrue(explanation.contains("never moves an active session"))
        assertTrue(explanation.contains("when a new operation begins"))
    }

    // ----------------------------------------------------------------------- helpers

    private fun autoRow(
        preview: BackendResolutionResult2?,
        required: Set<AnkiRequiredCapability> = STUDY_SESSION_REQUIREMENTS
    ): AnkiBackendRowUi = buildAnkiBackendSectionUi(
        states = listOf(state()),
        preference = BackendPreference.Auto,
        autoPreview = preview,
        requiredCapabilities = required
    ).rows.first()

    private fun autoLabel(
        preview: BackendResolutionResult2?,
        required: Set<AnkiRequiredCapability> = STUDY_SESSION_REQUIREMENTS
    ): String = autoRow(preview, required).statusLabel

    @Test
    fun `the section state is immutable data with no derived authority of its own`() {
        val model: AnkiBackendSectionUiState = buildAnkiBackendSectionUi(
            states = listOf(state()),
            preference = BackendPreference.Explicit(ankiDroid)
        )

        // Nothing in the model can change a preference, a lock or a transaction: it is plain data.
        assertEquals(model, model.copy())
        assertFalse(model.hasDuplicateKeys)
        assertEquals("explicit:ankidroid_local", model.preferenceToken)
    }
}
