package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendPreferenceInvalidIssue
import com.studyagent.client.ui.components.anki.ActiveStudySessionUi
import com.studyagent.client.ui.components.anki.AnkiBackendAutoRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendInstanceRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendRefreshUi
import com.studyagent.client.ui.components.anki.AnkiBackendSaveFeedbackUi
import com.studyagent.client.ui.components.anki.AnkiBackendSectionUiState
import com.studyagent.client.ui.components.anki.AnkiBackendUiAction
import com.studyagent.client.ui.components.anki.AnkiBackendUnimplementedKindRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendUnresolvedRowUi
import com.studyagent.client.ui.components.anki.buildAnkiBackendSectionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21M Revision 2 — backend **selection** presentation model.
 *
 * These tests pin the two things a selection surface most easily gets wrong:
 *
 * 1. *identity*: a row is one configured instance addressed by its exact `BackendId`, so an
 *    unavailable instance stays itself and a missing one is never silently replaced (CHECKPOINT
 *    21M-02/03/04/12, INV-21-10, DECISION-21-15);
 * 2. *authority*: what is shown as selected is the persisted preference, what is shown as active is
 *    the session's own lock, and neither is derived from the other (CHECKPOINT 21M-13, INV-21-08).
 *
 * R-20-2 is asserted from both sides: a kind with no wired adapter produces an informational row and
 * no operational entry, and a *registered* instance of that same kind removes the informational row —
 * so the exclusion is derived from the registry, never hard-coded per kind.
 */
class Gate21mBackendSelectionUiTest {

    private val capabilities = AnkiCapabilities(
        review = true,
        scheduledReview = true,
        deckListing = true,
        editNoteFields = true
    )

    private val ankiDroid = AnkiBackendId.AnkiDroidLocal
    private val pcHome = AnkiBackendId.PcAgent("home")
    private val pcWork = AnkiBackendId.PcAgent("work")

    private fun state(
        id: AnkiBackendId,
        availability: AnkiAvailability = AnkiAvailability.Ready(capabilities),
        implemented: AnkiCapabilities? = capabilities
    ): AnkiBackendState = AnkiBackendState(
        descriptor = AnkiBackendDescriptor(id, availability, capabilities),
        implementedClaim = implemented
    )

    private fun section(
        states: List<AnkiBackendState>,
        preference: BackendPreference = BackendPreference.Auto,
        locks: Map<String, AnkiSessionContext> = emptyMap(),
        saveFeedback: AnkiBackendSaveFeedbackUi = AnkiBackendSaveFeedbackUi.Idle,
        refresh: AnkiBackendRefreshUi = AnkiBackendRefreshUi.Idle
    ): AnkiBackendSectionUiState = buildAnkiBackendSectionUi(
        states = states,
        preference = preference,
        autoPreview = null,
        sessionLocks = locks,
        saveFeedback = saveFeedback,
        refresh = refresh
    )

    private fun lock(id: AnkiBackendId, session: String, collectionKey: String? = "col-1") = session to
        AnkiSessionContext(
            backendId = id,
            collection = collectionKey?.let { AnkiCollectionIdentity(id, it) },
            deckRef = null,
            startedAtEpochMs = 1_000L,
            capabilities = capabilities,
            studySessionId = session
        )

    // ------------------------------------------------------------------ CHECKPOINT 21M-02 / 04

    @Test
    fun `one row per configured instance, keyed by the exact backend identity`() {
        val model = section(listOf(state(pcWork), state(ankiDroid), state(pcHome)))

        val instanceKeys = model.configuredInstanceRows.map { it.key }
        assertEquals(
            "identity order is display order only, and it is deterministic for a shuffled inventory",
            listOf("ankidroid_local", "pc_agent:home", "pc_agent:work"),
            instanceKeys
        )
        assertFalse("Compose item keys must be unique", model.hasDuplicateKeys)
        assertEquals("AUTO is a policy row, not a backend", AnkiBackendAutoRowUi.KEY, model.rows.first().key)
        // Two instances of one kind are two rows: a kind can never stand for an instance.
        assertEquals(2, model.configuredInstanceRows.count { it.kindToken == AnkiBackendKind.PC_AGENT.name })
    }

    @Test
    fun `a row carries the verbatim identity token and never uses a label as identity`() {
        val row = section(listOf(state(pcHome)), BackendPreference.Explicit(pcHome))
            .configuredInstanceRows.single()

        assertEquals("pc_agent:home", row.identityToken)
        assertEquals("pc_agent:home", row.key)
        assertEquals(AnkiBackendKind.PC_AGENT.name, row.kindToken)
        assertFalse("the key is an identity, not copy", row.key == row.title)
        assertEquals(
            "explicit:pc_agent:home",
            section(listOf(state(pcHome)), BackendPreference.Explicit(pcHome)).preferenceToken
        )
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-03

    @Test
    fun `an explicit instance pin selects exactly that row`() {
        val model = section(
            listOf(state(ankiDroid), state(pcHome), state(pcWork)),
            BackendPreference.Explicit(pcWork)
        )

        assertEquals("pc_agent:work", model.selectedKey)
        model.configuredInstanceRows.forEach { row ->
            assertEquals(row.backendId == pcWork, row.selected)
        }
        assertFalse("AUTO is not selected when an instance is pinned", model.rows.first().selected)
    }

    @Test
    fun `a legacy kind preference selects the single configured instance of that kind`() {
        val model = section(
            listOf(state(ankiDroid), state(pcHome)),
            BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
        )

        assertEquals("pc_agent:home", model.selectedKey)
        assertTrue(model.configuredInstanceRows.single { it.backendId == pcHome }.selected)
    }

    @Test
    fun `a legacy kind preference with two configured instances selects nothing and says why`() {
        val model = section(
            listOf(state(pcHome), state(pcWork)),
            BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
        )

        val unresolved = model.rows.filterIsInstance<AnkiBackendUnresolvedRowUi>().single()
        assertEquals("PREFERRED_BACKEND_KIND_AMBIGUOUS", unresolved.statusToken)
        assertEquals("unresolved:preference", model.selectedKey)
        assertFalse("selection must not choose an instance for the user", model.configuredInstanceRows.any { it.selected })
        assertTrue(unresolved.explanation.contains("2 are configured"))
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-12

    @Test
    fun `a preference naming an unconfigured backend is reported and never rewritten to AUTO`() {
        val model = section(
            listOf(state(ankiDroid)),
            BackendPreference.Explicit(AnkiBackendId.PcAgent("retired"))
        )

        val unresolved = model.rows.filterIsInstance<AnkiBackendUnresolvedRowUi>().single()
        assertEquals("PREFERRED_BACKEND_NOT_CONFIGURED", unresolved.statusToken)
        assertEquals(AnkiBackendId.PcAgent("retired"), unresolved.backendId)
        assertEquals("unresolved:pc_agent:retired", unresolved.key)
        assertEquals("explicit:pc_agent:retired", unresolved.storedPreferenceToken)
        assertTrue(
            "the stored identity is shown verbatim",
            unresolved.explanation.contains("pc_agent:retired")
        )
        assertFalse("nothing was converted to AUTO", model.rows.first().selected)
        assertEquals("unresolved:pc_agent:retired", model.selectedKey)
        assertFalse(
            "no operational row was invented for the missing instance",
            model.configuredInstanceRows.any { it.backendId == AnkiBackendId.PcAgent("retired") }
        )
    }

    @Test
    fun `an unreadable stored preference keeps its own issue token`() {
        BackendPreferenceInvalidIssue.entries.forEach { issue ->
            val model = section(
                listOf(state(ankiDroid)),
                BackendPreference.InvalidStored(issue)
            )
            val unresolved = model.rows.filterIsInstance<AnkiBackendUnresolvedRowUi>().single()
            assertEquals("PREFERENCE_INVALID_${issue.name}", unresolved.statusToken)
            assertFalse("AUTO is never claimed for an unreadable preference", model.rows.first().selected)
            assertNull(model.configuredInstanceRows.singleOrNull { it.selected })
        }
    }

    @Test
    fun `a kind preference with no configured instance of that kind is reported, not defaulted`() {
        val model = section(
            listOf(state(ankiDroid)),
            BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
        )

        val unresolved = model.rows.filterIsInstance<AnkiBackendUnresolvedRowUi>()
            .single { it.statusToken == "PREFERRED_BACKEND_KIND_NOT_CONFIGURED" }
        assertNull("no instance identity is invented for a kind-level preference", unresolved.backendId)
        assertTrue(model.preferenceSummary.contains("not configured"))
    }

    // ------------------------------------------------------------------ R-20-2

    @Test
    fun `a user-selectable kind with no wired adapter is informational and never operational`() {
        val model = section(listOf(state(ankiDroid)))

        val pcRow = model.rows.filterIsInstance<AnkiBackendUnimplementedKindRowUi>().single()
        assertEquals(AnkiBackendKind.PC_AGENT, pcRow.kind)
        assertEquals("kind:PC_AGENT", pcRow.key)
        assertEquals("BACKEND_KIND_NOT_IMPLEMENTED", pcRow.statusToken)
        assertFalse("not selectable", pcRow.selectable)
        assertFalse("not selected", pcRow.selected)
        assertEquals("Not implemented in this build", pcRow.statusLabel)
        // No operational PC entry exists anywhere in the model.
        assertTrue(model.configuredInstanceRows.none { it.kindToken == AnkiBackendKind.PC_AGENT.name })
        // The row must make the distinction explicit: a reachable PC agent is not a PC Anki backend.
        assertTrue(pcRow.explanation.contains("not implemented in this build"))
        assertTrue(pcRow.explanation.contains("is not an Anki backend"))
        assertTrue(pcRow.explanation.contains("never makes this row ready"))
    }

    @Test
    fun `registering a real instance of that kind removes the informational row`() {
        val withPc = section(listOf(state(ankiDroid), state(pcHome)))

        assertTrue(
            "the exclusion is derived from the registry, not hard-coded per kind",
            withPc.rows.none { it is AnkiBackendUnimplementedKindRowUi && it.kind == AnkiBackendKind.PC_AGENT }
        )
        assertTrue(withPc.configuredInstanceRows.any { it.backendId == pcHome })
    }

    @Test
    fun `a registered test double is rendered for honesty but is never an operational choice`() {
        val fake = AnkiBackendId.Fake("gate21m")
        val model = section(listOf(state(ankiDroid), state(fake)), BackendPreference.Explicit(fake))

        val row = model.configuredInstanceRows.single { it.backendId == fake }
        assertTrue(row.testDouble)
        assertFalse("a test double can never be selected as an operational backend", row.selectable)
        assertNotNull(row.explanation)
        assertTrue(row.explanation!!.contains("test backend"))
        assertFalse("FAKE is not a user-selectable kind", AnkiBackendKind.FAKE in
            com.studyagent.client.ui.components.anki.USER_SELECTABLE_BACKEND_KINDS)
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-07 / 10

    @Test
    fun `an empty inventory says so instead of showing a backend list`() {
        val model = section(emptyList())

        assertNotNull(model.emptyInventory)
        assertTrue(model.configuredInstanceRows.isEmpty())
        assertEquals("No Anki backend is configured", model.rows.first().statusLabel)
        assertEquals("AUTO_PREVIEW_NO_BACKEND_CONFIGURED", model.rows.first().statusToken)
        assertTrue(
            "no refresh action is offered for an inventory that cannot be refreshed",
            AnkiBackendUiAction.REFRESH_STATUS !in model.actions
        )
    }

    @Test
    fun `the observation notice claims no freshness the contract cannot supply`() {
        val notice = AnkiBackendSectionUiState.OBSERVATION_NOTICE

        listOf("seconds ago", "minutes ago", "still connected", "last checked", "up to date").forEach {
            assertFalse("no implied age or TTL: `$it`", notice.contains(it, ignoreCase = true))
        }
        assertTrue(notice.contains("no timestamp"))
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-13 / 15

    @Test
    fun `the active session owner is its own fact and is never the preference`() {
        val model = section(
            listOf(state(ankiDroid), state(pcHome)),
            preference = BackendPreference.Explicit(pcHome),
            locks = mapOf(lock(ankiDroid, "study-1"))
        )

        val active = model.activeSession as ActiveStudySessionUi.One
        assertEquals("the session keeps the backend it started on", ankiDroid, active.backendId)
        assertEquals("study-1", active.studySessionId)
        assertTrue(active.collectionKeyPresent)
        // The preference row is still the PC instance: two separate truths, both shown.
        assertEquals("pc_agent:home", model.selectedKey)
        assertEquals("Active study session: ${active.backendTitle}", active.headline)
        assertEquals("ONE_ACTIVE_SESSION", active.token)
    }

    @Test
    fun `several active sessions are counted, not merged into one owner`() {
        val model = section(
            listOf(state(ankiDroid), state(pcHome)),
            locks = mapOf(lock(ankiDroid, "study-1"), lock(pcHome, "study-2"))
        )

        val several = model.activeSession as ActiveStudySessionUi.Several
        assertEquals(2, several.count)
        assertEquals("SEVERAL_ACTIVE_SESSIONS", several.token)
        assertTrue(
            "naming one session's backend would present it as the app's authority",
            several.headline.contains("2 active study sessions")
        )
    }

    @Test
    fun `no active session is reported as none`() {
        val none = section(listOf(state(ankiDroid))).activeSession
        assertEquals(ActiveStudySessionUi.None, none)
        assertEquals("NO_ACTIVE_SESSION", none.token)
    }

    @Test
    fun `the switch-effect notice appears only after a confirmed write with an active session`() {
        val locks = mapOf(lock(ankiDroid, "study-1"))
        val saved = AnkiBackendSaveFeedbackUi.Saved("explicit:pc_agent:home")

        assertNotNull(section(listOf(state(ankiDroid), state(pcHome)), locks = locks, saveFeedback = saved)
            .switchEffectNotice)
        assertNull("no session, no migration to warn about",
            section(listOf(state(ankiDroid), state(pcHome)), saveFeedback = saved).switchEffectNotice)
        assertNull("an unconfirmed write must not claim an effect",
            section(listOf(state(ankiDroid)), locks = locks,
                saveFeedback = AnkiBackendSaveFeedbackUi.Unconfirmed("explicit:ankidroid_local"))
                .switchEffectNotice)
        assertNull("a refused write must not claim an effect",
            section(listOf(state(ankiDroid)), locks = locks,
                saveFeedback = AnkiBackendSaveFeedbackUi.Failed("explicit:ankidroid_local", "backend_preference_not_saved", "x"))
                .switchEffectNotice)
        assertNull("nothing was written",
            section(listOf(state(ankiDroid)), locks = locks).switchEffectNotice)

        val notice = AnkiBackendSectionUiState.SWITCH_EFFECT_NOTICE
        assertTrue(notice.contains("remains connected to its original"))
        assertFalse("never a blocking dialog or a session migration", notice.contains("will be moved"))
    }

    @Test
    fun `the scope notice states what a preference change does not touch`() {
        val notice = AnkiBackendSectionUiState.SCOPE_NOTICE

        listOf("active session", "open editor", "creation draft", "unfinished transaction").forEach {
            assertTrue("the notice must name `$it`", notice.contains(it))
        }
        assertTrue(notice.startsWith("Applies to new"))
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-20

    @Test
    fun `the action set is closed and holds no permission, retry or mutation action`() {
        val tokens = AnkiBackendUiAction.entries.map { it.token }

        assertEquals(
            listOf(
                "refresh_status",
                "view_capability_details",
                "open_backend_configuration",
                "open_diagnostics",
                "open_ankidroid"
            ),
            tokens
        )
        listOf("permission", "grant", "retry_all", "force", "commit", "clear", "reassign", "delete").forEach {
            assertTrue("no `$it` action may exist", tokens.none { token -> token.contains(it) })
        }
    }

    @Test
    fun `each offered action is backed by something this build really has`() {
        val ankiDroidOnly = section(listOf(state(ankiDroid)))
        assertTrue(AnkiBackendUiAction.OPEN_ANKIDROID in ankiDroidOnly.actions)
        assertTrue(AnkiBackendUiAction.REFRESH_STATUS in ankiDroidOnly.actions)

        val pcOnly = section(listOf(state(pcHome)))
        assertFalse(
            "no AnkiDroid launcher action when no AnkiDroid backend is wired",
            AnkiBackendUiAction.OPEN_ANKIDROID in pcOnly.actions
        )
    }

    // ------------------------------------------------------------------ write feedback honesty

    @Test
    fun `write feedback is passed through unchanged, so a refusal cannot read as a save`() {
        val failed = AnkiBackendSaveFeedbackUi.Failed(
            targetToken = "explicit:ankidroid_local",
            errorToken = "backend_preference_not_saved",
            message = "The preference could not be written."
        )

        val model = section(listOf(state(ankiDroid)), saveFeedback = failed)
        assertEquals(failed, model.saveFeedback)
        assertEquals("backend_preference_not_saved", (model.saveFeedback as AnkiBackendSaveFeedbackUi.Failed).errorToken)
        assertEquals(AnkiBackendSaveFeedbackUi.Idle, section(listOf(state(ankiDroid))).saveFeedback)
        assertEquals(
            AnkiBackendRefreshUi.REQUESTED,
            section(listOf(state(ankiDroid)), refresh = AnkiBackendRefreshUi.Completed(AnkiBackendRefreshUi.REQUESTED, "ankidroid_local"))
                .let { (it.refresh as AnkiBackendRefreshUi.Completed).resultToken }
        )
    }
}
