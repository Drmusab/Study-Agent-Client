package com.studyagent.client.ui

import com.studyagent.client.anki.FixedPreferenceStore
import com.studyagent.client.anki.ProbeOnlyBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendStatusCenter
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiStudyBackendLockTracker
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendPreferenceInvalidIssue
import com.studyagent.client.core.anki.kind
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.ui.components.anki.ActiveStudySessionUi
import com.studyagent.client.ui.components.anki.AnkiBackendAutoRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendInstanceRowUi
import com.studyagent.client.ui.components.anki.AnkiBackendRefreshUi
import com.studyagent.client.ui.components.anki.AnkiBackendSaveFeedbackUi
import com.studyagent.client.ui.components.anki.AnkiBackendUnimplementedKindRowUi
import com.studyagent.client.ui.components.anki.AnkiCustomStudyStatusUi
import com.studyagent.client.ui.components.anki.USER_SELECTABLE_BACKEND_KINDS
import com.studyagent.client.ui.components.anki.buildAnkiBackendSectionUi
import com.studyagent.client.ui.components.anki.preferenceToken
import com.studyagent.client.ui.screens.settings.AnkiBackendSelectionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * GATE 21M Revision 2 — **VER-21M-R2-49**: one deterministic chaos/stress pass over the selection
 * model and its controller, with every safety counter required to end at zero.
 *
 * Why a stress pass and not only example cases: the failure modes this gate guards against are
 * combinatorial. An inventory of 0–3 instances × 12 availability states × 4 preference shapes ×
 * 0–2 session locks × 5 write-feedback states × 5 refresh states is far larger than any hand-written
 * example set, and the dangerous outcomes are the ones nobody thought to write a case for: two rows
 * claiming to be selected, a row for an instance that was never registered, a capability reported as
 * usable on a backend that is not ready, a preference silently turned into AUTO, a "saved" that the
 * store never confirmed, or a session owner that moved because a preference changed.
 *
 * The pass is seeded (`Random(21_000_049)`), so it is reproducible: the same iteration count produces
 * the same sequences on every run, and a failure names the iteration and the whole input. Counters are
 * accumulated rather than asserted per-iteration so one run reports *every* violated invariant.
 */
class Gate21mUiCapabilityStressTest {

    private val capabilities = AnkiCapabilities(
        review = true,
        scheduledReview = true,
        deckListing = true,
        editNoteFields = true,
        media = true
    )

    private val identityPool = listOf(
        AnkiBackendId.AnkiDroidLocal,
        AnkiBackendId.PcAgent("home"),
        AnkiBackendId.PcAgent("work"),
        AnkiBackendId.Fake("chaos")
    )

    private val availabilityPool = listOf(
        AnkiAvailability.Checking,
        AnkiAvailability.NotInstalled,
        AnkiAvailability.ProviderUnavailable("content://chaos"),
        AnkiAvailability.PermissionRequired("SecurityException: chaos"),
        AnkiAvailability.CollectionNotInitialized,
        AnkiAvailability.Ready(capabilities),
        AnkiAvailability.Ready(AnkiCapabilities()),
        AnkiAvailability.TemporarilyUnavailable("chaus transport"),
        AnkiAvailability.AgentDisconnected,
        AnkiAvailability.AgentAuthenticationRequired,
        AnkiAvailability.AgentAnkiUnavailable,
        AnkiAvailability.Unsupported("chaos_unsupported"),
        AnkiAvailability.Fault(AnkiError.BackendUnavailable("chaos"))
    )

    private fun state(id: AnkiBackendId, availability: AnkiAvailability) = AnkiBackendState(
        descriptor = AnkiBackendDescriptor(
            id,
            availability,
            if (availability is AnkiAvailability.Ready) availability.capabilities else capabilities
        ),
        implementedClaim = capabilities
    )

    private fun lock(id: AnkiBackendId, session: String) = session to AnkiSessionContext(
        backendId = id,
        collection = AnkiCollectionIdentity(id, "chaos-key-$session"),
        deckRef = null,
        startedAtEpochMs = 1L,
        capabilities = capabilities,
        studySessionId = session
    )

    /** Deterministic settling: this harness's test scheduler needs an explicit dispatch. */
    private fun TestScope.settle(pastConfirmationMs: Long = 3_000) {
        runCurrent()
        if (pastConfirmationMs > 0) testScheduler.advanceTimeBy(pastConfirmationMs)
        runCurrent()
    }

    // ------------------------------------------------------------------ phase 1: the model

    @Test
    fun `600 random inventories, preferences, locks and feedback states violate no safety invariant`() {
        val random = Random(21_000_049)
        var duplicateKeys = 0
        var selectableInformational = 0
        var selectedRowCountViolations = 0
        var unknownSelectionKey = 0
        var inventedInstances = 0
        var silentAutoConversion = 0
        var capabilityOverPromise = 0
        var fakeSelectable = 0
        var freshnessClaims = 0
        var customStudyControls = 0
        var blankCopy = 0
        var missingKindDisclosure = 0
        var unavailableNotSelectable = 0
        var firstFailure: String? = null

        repeat(600) { iteration ->
            val states = (0..random.nextInt(3)).map {
                state(identityPool[random.nextInt(identityPool.size)], availabilityPool[random.nextInt(availabilityPool.size)])
            }.distinctBy { it.backendId }

            val preference = when (random.nextInt(6)) {
                0 -> BackendPreference.Auto
                1 -> BackendPreference.Explicit(identityPool[random.nextInt(identityPool.size)])
                2 -> BackendPreference.Explicit(AnkiBackendId.PcAgent("never-registered-${random.nextInt(5)}"))
                3 -> BackendPreference.ExplicitKind(
                    if (random.nextBoolean()) AnkiBackendKind.PC_AGENT else AnkiBackendKind.ANKIDROID_LOCAL
                )

                4 -> BackendPreference.InvalidStored(
                    BackendPreferenceInvalidIssue.entries[random.nextInt(BackendPreferenceInvalidIssue.entries.size)]
                )

                else -> BackendPreference.Explicit(AnkiBackendId.Fake("chaos"))
            }

            val locks = (0..random.nextInt(2)).map { index ->
                lock(identityPool[random.nextInt(identityPool.size)], "chaos-$index")
            }.toMap()

            val saveFeedback = when (random.nextInt(5)) {
                0 -> AnkiBackendSaveFeedbackUi.Idle
                1 -> AnkiBackendSaveFeedbackUi.Saving("chaos")
                2 -> AnkiBackendSaveFeedbackUi.Saved("chaos")
                3 -> AnkiBackendSaveFeedbackUi.Failed("chaos", "backend_preference_not_saved", "not saved")
                else -> AnkiBackendSaveFeedbackUi.Unconfirmed("chaos")
            }

            val refresh = when (random.nextInt(5)) {
                0 -> AnkiBackendRefreshUi.Idle
                1 -> AnkiBackendRefreshUi.Running(null)
                2 -> AnkiBackendRefreshUi.Completed(AnkiBackendRefreshUi.REQUESTED, null)
                3 -> AnkiBackendRefreshUi.Completed(AnkiBackendRefreshUi.NOT_REGISTERED, null)
                else -> AnkiBackendRefreshUi.Failed(AnkiBackendRefreshUi.FAILED, null)
            }

            val model = buildAnkiBackendSectionUi(
                states = states,
                preference = preference,
                autoPreview = null,
                sessionLocks = locks,
                saveFeedback = saveFeedback,
                refresh = refresh,
                requiredCapabilities = STUDY_SESSION_REQUIREMENTS
            )

            fun report(counter: () -> Unit, detail: String) {
                counter()
                if (firstFailure == null) {
                    firstFailure = "iteration $iteration: $detail\n  states=${states.map { candidate -> candidate.backendId.stableId to candidate.availability.statusCode }}\n" +
                        "  preference=${preference.preferenceToken()} locks=${locks.keys} feedback=$saveFeedback refresh=$refresh"
                }
            }

            if (model.hasDuplicateKeys) report({ duplicateKeys++ }, "duplicate row keys ${model.rows.map { it.key }}")
            model.rows.filterIsInstance<AnkiBackendUnimplementedKindRowUi>()
                .filter { it.selectable || it.selected }
                .forEach { report({ selectableInformational++ }, "informational row selectable: ${it.key}") }

            val selectedRows = model.rows.count { it.selected }
            if (selectedRows != 1) {
                report({ selectedRowCountViolations++ }, "$selectedRows rows claim to be selected")
            }
            if (model.selectedKey != null && model.rows.none { it.key == model.selectedKey }) {
                report({ unknownSelectionKey++ }, "selectedKey ${model.selectedKey} is not a row")
            }
            val registered = states.map { it.backendId }.toSet()
            model.configuredInstanceRows.filter { it.backendId !in registered }.forEach {
                report({ inventedInstances++ }, "row for an unregistered instance ${it.backendId.stableId}")
            }
            val autoSelected = model.rows.filterIsInstance<AnkiBackendAutoRowUi>().single().selected
            if (preference != BackendPreference.Auto && autoSelected) {
                report({ silentAutoConversion++ }, "preference ${preference.preferenceToken()} rendered as AUTO")
            }
            model.configuredInstanceRows.forEach { row ->
                if (row.testDouble && row.selectable) {
                    report({ fakeSelectable++ }, "test double selectable: ${row.key}")
                }
                if (!row.testDouble && !row.selectable) {
                    report({ unavailableNotSelectable++ }, "a configured instance must stay selectable: ${row.key}")
                }
                row.capabilities.filter { it.usableNow && !row.readyNow }.forEach {
                    report({ capabilityOverPromise++ }, "${it.capability} usable on a backend that is not ready")
                }
                row.customStudy?.let { custom ->
                    if (custom.token !in listOf(
                            AnkiCustomStudyStatusUi.NOT_IMPLEMENTED,
                            AnkiCustomStudyStatusUi.LIFECYCLE_NOT_IMPLEMENTED
                        )
                    ) {
                        report({ customStudyControls++ }, "unknown custom-study token ${custom.token}")
                    }
                    if (listOf("Rebuild", "End session", "Start Custom Study").any { custom.explanation.contains(it) }) {
                        report({ customStudyControls++ }, "custom-study lifecycle wording offered")
                    }
                }
            }
            USER_SELECTABLE_BACKEND_KINDS
                .filter { kind -> states.none { it.kind == kind } }
                .forEach { kind ->
                    if (model.rows.none { it is AnkiBackendUnimplementedKindRowUi && it.kind == kind }) {
                        report({ missingKindDisclosure++ }, "no informational row for unconfigured $kind")
                    }
                }
            model.rows.forEach { row ->
                if (row.title.isBlank() || row.statusLabel.isBlank()) {
                    report({ blankCopy++ }, "blank title or status on ${row.key}")
                }
                val copy = listOfNotNull(row.statusLabel, row.explanation, row.title).joinToString(" ")
                if (listOf(" ago", "last checked", "still connected", "up to date").any { copy.contains(it, ignoreCase = true) }) {
                    report({ freshnessClaims++ }, "freshness claim on ${row.key}: $copy")
                }
            }
            val active = model.activeSession
            when {
                locks.isEmpty() && active != ActiveStudySessionUi.None ->
                    report({ selectedRowCountViolations++ }, "an active session was invented")

                locks.size == 1 && active !is ActiveStudySessionUi.One ->
                    report({ selectedRowCountViolations++ }, "one lock did not produce one owner")

                locks.size > 1 && active != ActiveStudySessionUi.Several(locks.size) ->
                    report({ selectedRowCountViolations++ }, "several locks did not produce a count")
            }
            if (locks.size == 1) {
                val owner = (model.activeSession as? ActiveStudySessionUi.One)?.backendId
                if (owner != locks.values.single().backendId) {
                    report({ silentAutoConversion++ }, "the session owner was re-derived ($owner)")
                }
            }
            if (model.switchEffectNotice != null &&
                !(saveFeedback is AnkiBackendSaveFeedbackUi.Saved && active is ActiveStudySessionUi.One)
            ) {
                report({ customStudyControls++ }, "a switch-effect notice without a confirmed write and a session")
            }
            if (states.isEmpty() && model.emptyInventory == null) {
                report({ missingKindDisclosure++ }, "an empty inventory was not disclosed")
            }
        }

        val totals = linkedMapOf(
            "duplicateKeys" to duplicateKeys,
            "selectableInformationalRow" to selectableInformational,
            "selectedRowCountViolations" to selectedRowCountViolations,
            "unknownSelectionKey" to unknownSelectionKey,
            "inventedInstances" to inventedInstances,
            "silentAutoConversion" to silentAutoConversion,
            "capabilityOverPromise" to capabilityOverPromise,
            "fakeSelectable" to fakeSelectable,
            "freshnessClaims" to freshnessClaims,
            "customStudyControls" to customStudyControls,
            "blankCopy" to blankCopy,
            "missingKindDisclosure" to missingKindDisclosure,
            "unavailableNotSelectable" to unavailableNotSelectable
        )
        assertEquals(
            "every safety counter must be zero. counters=$totals\nfirst failure: $firstFailure",
            0,
            totals.values.sum()
        )
    }

    // ------------------------------------------------------------------ phase 2: the controller

    @Test
    fun `120 random controller operations keep write, refresh and ownership honest`() = runTest {
        val random = Random(21_000_049)
        val backends = listOf(
            ProbeOnlyBackend(
                id = AnkiBackendId.AnkiDroidLocal,
                availability = AnkiAvailability.Ready(capabilities),
                capabilities = capabilities,
                claim = capabilities
            ),
            ProbeOnlyBackend(
                id = AnkiBackendId.PcAgent("home"),
                availability = AnkiAvailability.AgentDisconnected,
                capabilities = capabilities,
                claim = capabilities
            )
        )
        val registry = AnkiBackendRegistry(backends)
        val preference = MutableStateFlow<BackendPreference>(BackendPreference.Auto)
        val writeError = MutableStateFlow<String?>(null)
        val locks = AnkiStudyBackendLockTracker()
        val writes = mutableListOf<BackendPreference>()
        val logs = mutableListOf<String>()
        var refreshRequests = 0
        var savedWithoutConfirmation = 0
        var stalePublished = 0
        var sessionOwnerChanged = 0
        var probeAccountingViolations = 0
        var selectionMovedWithoutWrite = 0
        var exceptionLeak = 0
        var firstFailure: String? = null

        val center = AnkiBackendStatusCenter(
            registry = registry,
            preferenceStore = FixedPreferenceStore(AnkiBackendMode.AUTO),
            resolver = AnkiBackendResolver(registry),
            scope = backgroundScope
        )
        val controller = AnkiBackendSelectionController(
            scope = backgroundScope,
            statusCenter = center,
            preference = preference,
            registry = registry,
            writeError = writeError,
            writer = { target ->
                writes += target
                when (random.nextInt(4)) {
                    // Accepted and echoed back, like a DataStore write that publishes.
                    0 -> {
                        preference.value = target
                        writeError.value = null
                        true
                    }
                    // Accepted but never echoed: the honest outcome is Unconfirmed.
                    1 -> true
                    // Refused with a token.
                    2 -> {
                        writeError.value = "backend_preference_not_saved"
                        false
                    }
                    // A failure whose message must never be logged or rendered.
                    else -> throw IllegalStateException("content://chaos/secret-key")
                }
            },
            sessionLocks = locks,
            autoPreview = { _, _ ->
                com.studyagent.client.core.anki.BackendResolutionResult2.NoBackendConfigured(considered = emptySet())
            },
            confirmationTimeoutMs = 1_000L,
            logFailure = { logs += it }
        )

        val observed = mutableListOf<AnkiBackendSelectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.section.collect { section -> observed += AnkiBackendSelectionState(section, preference.value) }
        }

        var latestTarget: BackendPreference = BackendPreference.Auto
        repeat(120) { iteration ->
            when (random.nextInt(4)) {
                0, 1 -> {
                    latestTarget = when (random.nextInt(3)) {
                        0 -> BackendPreference.Auto
                        1 -> BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal)
                        else -> BackendPreference.Explicit(AnkiBackendId.PcAgent("home"))
                    }
                    controller.select(latestTarget)
                }

                2 -> {
                    refreshRequests++
                    controller.refreshStatus()
                }

                else -> locks.locked(
                    lock(
                        if (random.nextBoolean()) AnkiBackendId.AnkiDroidLocal else AnkiBackendId.PcAgent("home"),
                        "chaos-${random.nextInt(3)}"
                    ).second
                )
            }
            settle()

            val feedback = controller.saveFeedback.value
            val section = observed.lastOrNull()?.section
            fun report(counter: () -> Unit, detail: String) {
                counter()
                if (firstFailure == null) {
                    firstFailure = "iteration $iteration: $detail (feedback=$feedback, preference=" +
                        "${preference.value.preferenceToken()}, writes=${writes.size}, refreshes=$refreshRequests)"
                }
            }

            // A "saved" is only ever published for a preference the authoritative flow really holds.
            if (feedback is AnkiBackendSaveFeedbackUi.Saved &&
                preference.value.preferenceToken() != feedback.targetToken
            ) {
                report({ savedWithoutConfirmation++ }, "Saved without the store agreeing")
            }
            // Once a newer write has been issued, an older target may not be the published one.
            if (feedback is AnkiBackendSaveFeedbackUi.Saved &&
                feedback.targetToken != latestTarget.preferenceToken()
            ) {
                report({ stalePublished++ }, "a superseded write published its result")
            }
            if (feedback is AnkiBackendSaveFeedbackUi.Failed &&
                feedback.targetToken != latestTarget.preferenceToken()
            ) {
                report({ stalePublished++ }, "a superseded refusal published its result")
            }
            // The rendered selection is the authoritative preference, never the intent.
            if (section != null && writes.isEmpty() &&
                section.selectedKey != expectedKey(preference.value, section)
            ) {
                report({ selectionMovedWithoutWrite++ }, "selection moved with nothing written")
            }
            // Session ownership follows the lock tracker alone.
            val owners = locks.locks.value
            when {
                owners.isEmpty() && section?.activeSession != ActiveStudySessionUi.None ->
                    report({ sessionOwnerChanged++ }, "an active session was invented")

                owners.size == 1 -> {
                    val active = section?.activeSession
                    if (active !is ActiveStudySessionUi.One ||
                        active.backendId != owners.values.single().backendId
                    ) {
                        report({ sessionOwnerChanged++ }, "the session owner was re-derived")
                    }
                }

                owners.size > 1 && section?.activeSession != ActiveStudySessionUi.Several(owners.size) ->
                    report({ sessionOwnerChanged++ }, "several locks did not produce a count")
            }
            // Every probe happened because a refresh was requested: nothing polls.
            val probes = backends.sumOf { it.calls.count { call -> call == "refreshAvailability" } }
            if (probes != refreshRequests * backends.size) {
                report({ probeAccountingViolations++ }, "probes=$probes expected=${refreshRequests * backends.size}")
            }
            // Only exception class names are ever reported.
            logs.filter { it.contains("content://") || it.contains("secret-key") }.forEach {
                report({ exceptionLeak++ }, "exception detail reached a log: $it")
            }
        }

        // Every non-probe call a backend saw must be nothing at all: the gate never touches a backend
        // beyond the sanctioned read-only availability re-check.
        val nonProbeCalls = backends.flatMap { it.calls }.filter { it != "refreshAvailability" }

        val totals = linkedMapOf(
            "savedWithoutConfirmation" to savedWithoutConfirmation,
            "stalePublished" to stalePublished,
            "sessionOwnerChanged" to sessionOwnerChanged,
            "probeAccountingViolations" to probeAccountingViolations,
            "selectionMovedWithoutWrite" to selectionMovedWithoutWrite,
            "exceptionLeak" to exceptionLeak,
            "unexpectedBackendCalls" to nonProbeCalls.size
        )
        assertEquals(
            "every safety counter must be zero. counters=$totals\nfirst failure: $firstFailure",
            0,
            totals.values.sum()
        )
        assertTrue("the pass really exercised writes", writes.isNotEmpty())
        assertTrue("the pass really exercised refreshes", refreshRequests > 0)
        assertTrue(
            "the pass really observed rendered sections: ${observed.size} emissions",
            observed.count { it.section != null } > 0
        )
        assertTrue("the pass really held session locks", locks.locks.value.isNotEmpty())
        assertEquals(emptyList<String>(), nonProbeCalls)
    }

    /** One observed section together with the preference that was authoritative when it was read. */
    private data class AnkiBackendSelectionState(
        val section: com.studyagent.client.ui.components.anki.AnkiBackendSectionUiState?,
        val preference: BackendPreference
    )

    /** The key the authoritative preference alone implies, given the rows that exist. */
    private fun expectedKey(
        preference: BackendPreference,
        section: com.studyagent.client.ui.components.anki.AnkiBackendSectionUiState
    ): String? = when (preference) {
        BackendPreference.Auto -> AnkiBackendAutoRowUi.KEY
        is BackendPreference.Explicit ->
            if (section.configuredInstanceRows.any { it.backendId == preference.backendId }) {
                preference.backendId.stableId
            } else {
                section.rows.filterIsInstance<com.studyagent.client.ui.components.anki.AnkiBackendUnresolvedRowUi>()
                    .firstOrNull()?.key
            }

        is BackendPreference.ExplicitKind -> {
            val matching = section.configuredInstanceRows.filter { it.kindToken == preference.kind.name }
            when (matching.size) {
                1 -> matching.single().key
                else -> section.rows
                    .filterIsInstance<com.studyagent.client.ui.components.anki.AnkiBackendUnresolvedRowUi>()
                    .firstOrNull()?.key
            }
        }

        is BackendPreference.InvalidStored -> section.rows
            .filterIsInstance<com.studyagent.client.ui.components.anki.AnkiBackendUnresolvedRowUi>()
            .firstOrNull()?.key
    }

    @Test
    fun `the stress fixtures are honest about what they model`() {
        // The pool really contains an unavailable PC instance, a test double and a ready local one, so
        // the pass cannot trivially satisfy the invariants by only ever building ready backends.
        assertEquals(4, identityPool.size)
        assertEquals(13, availabilityPool.size)
        assertTrue(availabilityPool.any { it is AnkiAvailability.Ready })
        assertTrue(availabilityPool.any { it !is AnkiAvailability.Ready })
        assertTrue(identityPool.any { it.kind == AnkiBackendKind.FAKE })
        assertTrue(identityPool.count { it.kind == AnkiBackendKind.PC_AGENT } == 2)
        assertTrue(
            "an instance row must survive a not-ready backend",
            buildAnkiBackendSectionUi(
                states = listOf(state(AnkiBackendId.PcAgent("home"), AnkiAvailability.AgentDisconnected)),
                preference = BackendPreference.Explicit(AnkiBackendId.PcAgent("home"))
            ).configuredInstanceRows.single() is AnkiBackendInstanceRowUi
        )
    }
}
