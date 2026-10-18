package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 21M Revision 2 PART II — independent source audit (AUDIT-21M-R2-01 … AUDIT-21M-R2-26).
 *
 * The structural rules a behavioural test cannot see from outside: which files GATE 21M was allowed to
 * add, which vocabulary they may speak, and which authority they must not hold. Checks run on source
 * text with comments removed, so a document may explain a forbidden spelling while live code may not
 * use it (the GATE 12–21D convention). Every check names the audit row it enforces.
 *
 * The locked contracts behind these rows: `docs/BACKEND_SELECTION_POLICY.md`,
 * `docs/BACKEND_OWNERSHIP_INVARIANTS.md`, `docs/GATE_21B_CONTRACT_LOCK.md`,
 * `docs/GATE_21D_OBSERVATION_CONTRACT.md`, `docs/GATE_20_FINAL_REPORT.md` (R-20-2) and
 * `docs/GATE_21L_BLOCKED_REPORT.md`.
 *
 * Scope note: GATE 21M is a **presentation** gate. It may add presentation models, one presentation
 * controller and one read-only projection; it may not add a registry, a resolver, a session machine, a
 * ledger, a scheduler or a backend.
 */
class Gate21mArchitectureAuditTest {

    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private val mainRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")

    /** Block comments and whole-line `//` comments removed (a `://` inside a string is untouched). */
    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)^\\s*//.*$"), " ")

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 21M source $relative" }
        return file
    }

    private fun mainSources(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun assertNoTokens(text: String, tokens: List<String>, scope: String) {
        tokens.forEach { token -> assertFalse("$scope must not contain `$token`", text.contains(token)) }
    }

    private fun assertNoPatterns(text: String, patterns: List<String>, scope: String) {
        patterns.forEach { pattern ->
            assertFalse("$scope must not match /$pattern/", Regex(pattern).containsMatchIn(text))
        }
    }

    /** The two presentation models GATE 21M adds. */
    private val sectionUi get() = main("ui/components/anki/AnkiBackendSectionUi.kt").code()
    private val diagnosticsUi get() = main("ui/components/anki/AnkiBackendDiagnosticsUi.kt").code()

    /** The one controller GATE 21M adds, plus the ViewModel that delegates to it. */
    private val controller get() = main("ui/screens/settings/AnkiBackendSelectionController.kt").code()
    private val settingsViewModel get() = main("ui/screens/settings/SettingsViewModel.kt").code()
    private val settingsScreen get() = main("ui/screens/settings/SettingsScreen.kt").code()
    private val diagnosticsViewModel get() = main("ui/screens/diagnostics/DiagnosticsViewModel.kt").code()
    private val diagnosticsScreen get() = main("ui/screens/diagnostics/DiagnosticsScreen.kt").code()

    /** The read-only transaction projection: one core vocabulary, one data-layer reader. */
    private val transactionVocabulary get() = main("core/anki/AnkiUnresolvedTransactions.kt").code()
    private val transactionReader get() =
        main("data/repository/LedgerUnresolvedTransactionReader.kt").code()

    private val container get() = main("di/AppContainer.kt").code()
    private val navHost get() = main("ui/navigation/AppNavHost.kt").code()

    /** The files GATE 21M adds. */
    private val addedFiles: List<File> get() = listOf(
        main("ui/components/anki/AnkiBackendSectionUi.kt"),
        main("ui/components/anki/AnkiBackendDiagnosticsUi.kt"),
        main("ui/screens/settings/AnkiBackendSelectionController.kt"),
        main("core/anki/AnkiUnresolvedTransactions.kt"),
        main("data/repository/LedgerUnresolvedTransactionReader.kt")
    )

    /** Every file GATE 21M added or changed. */
    private val gate21mFiles: List<File> get() = listOf(
        main("ui/components/anki/AnkiBackendSectionUi.kt"),
        main("ui/components/anki/AnkiBackendDiagnosticsUi.kt"),
        main("ui/screens/settings/AnkiBackendSelectionController.kt"),
        main("ui/screens/settings/SettingsViewModel.kt"),
        main("ui/screens/settings/SettingsScreen.kt"),
        main("ui/screens/diagnostics/DiagnosticsViewModel.kt"),
        main("ui/screens/diagnostics/DiagnosticsScreen.kt"),
        main("core/anki/AnkiUnresolvedTransactions.kt"),
        main("data/repository/LedgerUnresolvedTransactionReader.kt"),
        main("di/AppContainer.kt"),
        main("ui/navigation/AppNavHost.kt")
    )

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-01

    @Test
    fun `AUDIT-21M-R2-01 the gate adds presentation models and one controller, and no new authority`() {
        // No new registry, resolver, session machine, ledger, scheduler, store or coordinator.
        val declared = gate21mFiles.flatMap { file ->
            Regex("(?m)^(?:internal |abstract |open |data |sealed )*(class|interface|object) (\\w+)")
                .findAll(file.code()).map { it.groupValues[2] to file.name }.toList()
        }
        val forbiddenKinds = listOf(
            "Registry", "Resolver", "StudySessionMachine", "Ledger", "Scheduler", "Coordinator",
            "Store", "Policy", "Planner", "Recovery"
        )
        declared.forEach { (name, file) ->
            if (name == "LedgerUnresolvedTransactionReader" || name == "AnkiUnresolvedTransactionReader") {
                // The sanctioned read-only projection: it *reads* ledgers, it is not one.
                return@forEach
            }
            forbiddenKinds.forEach { kind ->
                assertFalse(
                    "$file declares `$name`, which reads as a new $kind (GATE 21M may not add one)",
                    name.contains(kind)
                )
            }
        }
        // The two model files declare no class with behaviour of its own beyond data + pure functions.
        assertNoPatterns(
            sectionUi + diagnosticsUi,
            listOf("(?m)^class \\w+", "(?m)^internal class \\w+"),
            "the presentation models (data classes and pure functions only)"
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-02

    @Test
    fun `AUDIT-21M-R2-02 the presentation models are Android-free and therefore deterministically testable`() {
        listOf(
            main("ui/components/anki/AnkiBackendSectionUi.kt"),
            main("ui/components/anki/AnkiBackendDiagnosticsUi.kt"),
            main("ui/screens/settings/AnkiBackendSelectionController.kt"),
            main("core/anki/AnkiUnresolvedTransactions.kt")
        ).forEach { file ->
            val imports = file.readLines().filter { it.startsWith("import ") }
            assertFalse(
                "${file.name} must import no android/androidx type",
                imports.any { line -> line.contains("android.") || line.contains("androidx.") }
            )
        }
        // No wall clock anywhere in the gate: freshness is not measurable, so it is not claimed.
        assertNoTokens(
            gate21mFiles.joinToString("\n") { it.code() },
            listOf("System.currentTimeMillis", "elapsedRealtime", "Instant.now", "LocalDateTime.now"),
            "GATE 21M"
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-03

    @Test
    fun `AUDIT-21M-R2-03 the UI layer keeps the locked identity rule - no per-backend type checks`() {
        // AUDIT-21-13's single exception stays `AnkiBackendUiModels.kt`; GATE 21M adds none.
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.replace('\\', '/').contains("/ui/") }
            .filter { it.name != "AnkiBackendUiModels.kt" }
            .filter { file ->
                val code = file.code()
                code.contains("is AnkiBackendId.AnkiDroidLocal") || code.contains("is AnkiBackendId.PcAgent")
            }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-04

    @Test
    fun `AUDIT-21M-R2-04 the presentation layer allocates no backend identity`() {
        // A UI layer that could mint an identity could invent a PC instance (R-20-2).
        val uiFiles = gate21mFiles.filter { it.path.replace('\\', '/').contains("/ui/") }
        uiFiles.forEach { file ->
            assertNoPatterns(
                file.code(),
                listOf("AnkiBackendId\\.PcAgent\\(", "AnkiBackendId\\.Fake\\(", "AnkiBackendId\\.parse"),
                file.name
            )
        }
        // The screen and the nav host pass identities through; they never build one.
        assertNoPatterns(navHost, listOf("AnkiBackendId\\.PcAgent\\(", "AnkiBackendId\\.Fake\\("), "AppNavHost")
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-05

    @Test
    fun `AUDIT-21M-R2-05 no GATE 21M file becomes a second resolution site`() {
        // AUDIT-21-07 keeps the list of files that may build a resolution request closed. GATE 21M adds
        // none: the AUTO preview is injected from the composition root, which was already on the list.
        val resolutionSites = mainSources()
            .filter { it.code().let { c -> c.contains(".resolve(") && c.contains("BackendResolutionRequest") } }
            .map { it.name }
            .sorted()
        assertEquals(
            listOf(
                "AnkiBackendResolver.kt",
                "AnkiBackendSelector.kt",
                "AnkiBackendStatus.kt",
                "AnkiLocalStudyStarter.kt",
                "AnkiReadContextController.kt",
                "AnkiStudyEffectExecutor.kt",
                "AppContainer.kt"
            ),
            resolutionSites
        )
        assertNoTokens(
            controller + settingsViewModel + settingsScreen + diagnosticsViewModel + sectionUi + diagnosticsUi,
            listOf("AnkiBackendResolver2", "BackendResolutionRequest2("),
            "the GATE 21M presentation layer"
        )
        // The preview seam is a function type: the policy stays where GATE 21E put it.
        assertTrue(
            controller.contains("((List<AnkiBackendState>, Set<AnkiRequiredCapability>) -> BackendResolutionResult2)?")
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-06

    @Test
    fun `AUDIT-21M-R2-06 the controller writes exactly one thing - the preference`() {
        assertNoTokens(
            controller,
            listOf(
                ".register(", ".remove(", "setMode(", ".apply(", ".create(", ".commit(", ".beginReview(",
                ".nextCard(", ".refreshAll(", "locked(", "released(", ".transition(", ".retry("
            ),
            "the selection controller"
        )
        // The one sanctioned probe entry point, and nothing else that reaches a backend.
        assertTrue(controller.contains("inventory.refresh(id)"))
        // Everything the controller touches on an authoritative component, properties included.
        val touched = Regex("\\b(?:registry|inventory|statusCenter)\\??\\.(\\w+)")
            .findAll(controller).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("refresh", "registered", "states"), touched)
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-07

    @Test
    fun `AUDIT-21M-R2-07 the four selection roles are read from the existing projection, never re-derived`() {
        assertTrue(diagnosticsUi.contains("AnkiBackendRoutingDiagnostics"))
        listOf("routing.resolved", "routing.resolvedReason", "routing.lockedSessionBackend",
            "routing.lockedSessionCount", "routing.transactionBackend").forEach {
            assertTrue("the roles must be rendered from the projection: `$it`", diagnosticsUi.contains(it))
        }
        // The composition root exposes the snapshot it already computed; it does not compute a new one.
        assertTrue(container.contains("::ankiBackendRoutingDiagnostics"))
        // Re-deriving a role would need a resolution or a lock write; neither exists in the read model.
        assertNoTokens(diagnosticsUi, listOf(".resolve(", "locked(", "released("), "the diagnostics read model")
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-08

    @Test
    fun `AUDIT-21M-R2-08 diagnostics render tokens, identities and counts - never backend free text`() {
        assertNoPatterns(
            diagnosticsUi,
            listOf("\\.detail\\b", "\\.message\\b", "\\.cause\\b", "\\.serverName\\b", "\\.endpoint"),
            "the diagnostics read model"
        )
        // A collection key may only ever be reasoned about as *presence*: every line that mentions one
        // either declares a presence flag, tests it against null, or hands it to the identity policy
        // (which compares discriminators and never returns one). No line renders a value.
        diagnosticsUi.lines().filter { it.contains("collectionKey") }.forEach { line ->
            assertTrue(
                "only presence reasoning may mention a collection key: $line",
                line.contains("collectionKeyPresent") || line.contains("collectionKey != null") ||
                    line.contains("collection?.collectionKey)")
            )
        }
        assertNoPatterns(
            diagnosticsUi,
            listOf("collectionKey\\}"),
            "the diagnostics read model (collection key values)"
        )
        assertTrue(diagnosticsUi.contains("published (value withheld)"))
        assertTrue(diagnosticsUi.contains("none published (identity unverifiable)"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-09

    @Test
    fun `AUDIT-21M-R2-09 the section model claims no freshness the contract cannot supply`() {
        assertNoTokens(
            sectionUi,
            listOf("seconds ago", "minutes ago", "last checked", "still connected", "up to date", "TTL"),
            "the backend section model"
        )
        assertTrue(sectionUi.contains("carries no timestamp"))
        // The registry state has no timestamp field to read, so no age reading may be invented.
        assertNoPatterns(
            sectionUi,
            listOf("observedAt", "updatedAt", "timestampEpochMs", "ageMs", "elapsedSince"),
            "the section model"
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-10

    @Test
    fun `AUDIT-21M-R2-10 rows are keyed by identity and the model can detect a duplicate key`() {
        assertTrue(sectionUi.contains("override val key: String = backendId.stableId"))
        assertTrue(sectionUi.contains("const val KEY = \"preference:auto\""))
        assertTrue(sectionUi.contains("val hasDuplicateKeys: Boolean"))
        assertTrue(sectionUi.contains("override val key: String = \"kind:\${kind.name}\""))
        // The screen addresses rows by that key, never by position or label.
        assertTrue(settingsScreen.contains("\"\$ANKI_BACKEND_OPTION_TEST_TAG\${row.key}\""))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-11

    @Test
    fun `AUDIT-21M-R2-11 the unimplemented-kind row is derived from the registry, not hard-coded`() {
        assertTrue(sectionUi.contains("USER_SELECTABLE_BACKEND_KINDS"))
        assertTrue(sectionUi.contains(".filterNot { it in configuredKinds }"))
        assertTrue(sectionUi.contains("val configuredKinds = states.map { it.kind }.toSet()"))
        // No kind is excluded by name from the operational rows.
        assertNoPatterns(
            sectionUi,
            listOf("kind == AnkiBackendKind\\.PC_AGENT", "kind != AnkiBackendKind\\.PC_AGENT"),
            "the section model (no per-kind exclusion)"
        )
        // The informational row is never selectable and never selected.
        assertTrue(sectionUi.contains("override val selectable: Boolean = false"))
        assertTrue(sectionUi.contains("const val TOKEN = \"BACKEND_KIND_NOT_IMPLEMENTED\""))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-12

    @Test
    fun `AUDIT-21M-R2-12 no Custom Study lifecycle control exists anywhere in the gate`() {
        val all = gate21mFiles.joinToString("\n") { it.code() }
        assertNoTokens(
            all,
            listOf(
                "Rebuild", "End session", "Start Custom Study", "Create filtered", "createFilteredSession(",
                "CustomStudyPolicy", "AddOrUpdateFilteredDeck", "FilteredDeck(", "rebuildFiltered"
            ),
            "GATE 21M (GATE 21L is BLOCKED)"
        )
        // The disclosure is a statement about GATE 20 capability truth, with stable tokens.
        assertTrue(sectionUi.contains("CUSTOM_STUDY_NOT_IMPLEMENTED"))
        assertTrue(sectionUi.contains("CUSTOM_STUDY_LIFECYCLE_NOT_IMPLEMENTED"))
        assertTrue(sectionUi.contains("capabilities.supportsAnyCustomStudy"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-13

    @Test
    fun `AUDIT-21M-R2-13 the read-only surfaces hold no mutation authority`() {
        // The projection interface has exactly one member, and it reads.
        val readerInterface = transactionVocabulary
            .substringAfter("interface AnkiUnresolvedTransactionReader")
        assertEquals(1, Regex("suspend fun \\w+\\(").findAll(readerInterface).count())
        assertTrue(readerInterface.contains("suspend fun read()"))
        assertNoTokens(readerInterface, listOf("fun retry", "fun clear", "fun force", "fun reassign"),
            "the read-only projection")

        // The data-layer reader is built from read lambdas only: no ledger type is reachable.
        assertNoTokens(
            transactionReader,
            listOf(
                "ReviewCommitLedger", "ReviewerActionLedger", "NoteMutationLedger", "NoteCreationLedger",
                ".create(", ".apply(", ".transition(", ".retry(", ".acknowledge(", ".prune("
            ),
            "the ledger reader"
        )

        // The diagnostics ViewModel holds the reader, never a ledger or a coordinator.
        assertNoTokens(
            diagnosticsViewModel,
            listOf("Ledger", "Coordinator", "AnkiBackendRegistry.register", ".remove("),
            "the diagnostics ViewModel"
        )
        assertTrue(diagnosticsViewModel.contains("AnkiUnresolvedTransactionReader"))

        // The composition root exposes the reader and keeps the ledgers behind it.
        val containerInterface = container
            .substringAfter("interface AppContainer {")
            .substringBefore("class DefaultAppContainer")
        assertNoTokens(
            containerInterface,
            listOf("ReviewCommitLedger", "ReviewerActionLedger", "NoteMutationLedger", "NoteCreationLedger"),
            "the container interface (ledgers stay private to the composition root)"
        )
        assertTrue(containerInterface.contains("val ankiUnresolvedTransactions: AnkiUnresolvedTransactionReader"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-14

    @Test
    fun `AUDIT-21M-R2-14 transaction truth keeps owner and status vocabulary, and distinguishes absence`() {
        assertTrue(transactionVocabulary.contains("val backendId: AnkiBackendId?"))
        assertTrue(transactionVocabulary.contains("val statusToken: String"))
        assertTrue(transactionVocabulary.contains("val unavailableFamilies: Set<AnkiTransactionFamily>"))
        assertTrue(transactionVocabulary.contains("val notProjectedFamilies: Set<AnkiTransactionFamily>"))
        // The reader reduces a record to family + owner + the family's own token.
        assertTrue(transactionReader.contains("it.status.name"))
        assertTrue(transactionReader.contains("record.backendId"))
        // CONFLICT and AMBIGUOUS are never merged, and a filtered-session family is never invented.
        assertNoTokens(diagnosticsUi, listOf("CONFLICT_OR_AMBIGUOUS", "\"AMBIGUOUS/CONFLICT\""),
            "the diagnostics read model")
        assertTrue(diagnosticsUi.contains("FILTERED_SESSION"))
        assertEquals(4, Regex("AnkiTransactionFamily\\.\\w+, \"").findAll(diagnosticsUi).count())
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-15

    @Test
    fun `AUDIT-21M-R2-15 the assurance category is rendered verbatim and never reworded`() {
        listOf(
            "\"AUTHORITATIVE_MATCH\"", "\"SESSION_CONTINUITY_VERIFIED\"", "\"AUTHORITATIVE_MISMATCH\"",
            "\"ForeignBackend\"", "IDENTITY_UNVERIFIABLE("
        ).forEach { assertTrue("missing assurance token $it", diagnosticsUi.contains(it)) }
        assertNoTokens(diagnosticsUi, listOf("same collection", "collection verified", "identity confirmed"),
            "the diagnostics read model")
        assertTrue(diagnosticsUi.contains("AnkiIdentityAssurancePolicy.check("))
        // Presence of a key is reported; the value is not.
        assertTrue(diagnosticsUi.contains("if (context.collection?.collectionKey != null) \"present\" else \"absent\""))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-16

    @Test
    fun `AUDIT-21M-R2-16 the Settings section reuses the existing Compose primitives`() {
        listOf("ChoiceRow(", "InfoBanner(", "EmptyState(", "ExpandableSection(", "KeyValueRow(",
            "SecondaryButton(", "SectionHeader(").forEach {
            assertTrue("the backend section should reuse `$it`", settingsScreen.contains(it))
        }
        // No bespoke selector widget was introduced next to the shared one.
        assertNoPatterns(
            settingsScreen,
            listOf("private fun \\w*RadioButton", "private fun \\w*Selector\\b"),
            "the Settings screen"
        )
        // The diagnostics section reuses the existing rows card and its key-based list items.
        assertTrue(diagnosticsScreen.contains("DiagnosticsRowsCard(\"Anki backends\", rows)"))
        assertTrue(diagnosticsScreen.contains("item(key = \"anki-backends\")"))
        // The asynchronous read must be observed, or the card freezes on NOT_PROJECTED.
        assertTrue(diagnosticsScreen.contains("viewModel.ankiPendingTransactions.collectAsStateWithLifecycle()"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-17

    @Test
    fun `AUDIT-21M-R2-17 no permission-grant control is offered, because none exists`() {
        val all = gate21mFiles.joinToString("\n") { it.code() }
        assertNoTokens(
            all,
            listOf("requestPermission", "grantPermission", "GRANT_PERMISSION", "ActivityCompat", "permissionResult"),
            "GATE 21M (the AnkiDroid permission is install-time and third-party-declared)"
        )
        // The closed action set names the honest remedies only.
        AnkiBackendActionTokens.forEach { assertTrue(sectionUi.contains(it)) }
        assertFalse(sectionUi.contains("GRANT"))
    }

    private val AnkiBackendActionTokens = listOf(
        "\"refresh_status\"", "\"view_capability_details\"", "\"open_backend_configuration\"",
        "\"open_diagnostics\"", "\"open_ankidroid\""
    )

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-18

    @Test
    fun `AUDIT-21M-R2-18 nothing polls and nothing runs because of composition`() {
        assertNoTokens(
            controller + settingsViewModel + diagnosticsViewModel,
            listOf("delay(", "while (true)", "repeatOnLifecycle", "Timer("),
            "the GATE 21M controllers"
        )
        // The projection is lifecycle-aware, so recomposition cannot trigger work.
        assertTrue(controller.contains("SharingStarted.WhileSubscribed(5000)"))
        assertTrue(controller.contains("stateIn(scope = scope"))
        // Refresh is user-initiated only, and a second press is a new generation, not a second loop.
        assertTrue(controller.contains("refreshGeneration.incrementAndGet()"))
        assertTrue(settingsScreen.contains("onClick = onRefresh"))
        assertFalse(settingsScreen.contains("LaunchedEffect(Unit) { viewModel.refreshAnkiBackendStatus() }"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-19

    @Test
    fun `AUDIT-21M-R2-19 stale asynchronous results are rejected by a monotonic generation guard`() {
        assertTrue(controller.contains("writeGeneration.incrementAndGet()"))
        assertTrue(controller.contains("refreshGeneration.incrementAndGet()"))
        // Two re-checks in the write path (after the write, after the confirmation window) and three in
        // the refresh path (cancellation, failure, completion): five published-state decisions, each
        // guarded by its generation.
        assertEquals(
            "both guards must re-check before publishing",
            5,
            Regex("Generation\\.get\\(\\) != generation").findAll(controller).count() +
                Regex("Generation\\.get\\(\\) == generation").findAll(controller).count()
        )
        assertEquals(2, Regex("writeGeneration\\.get\\(\\) != generation").findAll(controller).count())
        assertEquals(1, Regex("refreshGeneration\\.get\\(\\) != generation").findAll(controller).count())
        assertTrue(controller.contains("AtomicInteger(0)"))
        // Cancellation is rethrown, never converted into a result.
        assertEquals(2, Regex("throw cancellation").findAll(controller).count())
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-20

    @Test
    fun `AUDIT-21M-R2-20 a write is called saved only when the authoritative store agrees`() {
        val select = controller.substringAfter("fun select(target: BackendPreference)")
            .substringBefore("fun refreshStatus()")
        assertTrue(select.contains("withTimeoutOrNull(confirmationTimeoutMs)"))
        assertTrue(select.contains("preference.first { it == target }"))
        assertTrue(select.contains("AnkiBackendSaveFeedbackUi.Unconfirmed(targetToken)"))
        assertTrue(select.contains("AnkiBackendSaveFeedbackUi.Saved(targetToken)"))
        // Saved is published only on the confirmed branch.
        val savedBranch = select.substringAfter("if (confirmed != null)")
        assertTrue(savedBranch.contains("Saved(targetToken)"))
        // An exception is a refusal.
        assertTrue(select.contains("logFailure("))
        assertTrue(select.contains("false"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-21

    @Test
    fun `AUDIT-21M-R2-21 only the exception class name is ever reported or logged`() {
        val all = gate21mFiles.joinToString("\n") { it.code() }
        assertNoPatterns(
            all,
            listOf("failure\\.message", "failure\\.stackTrace", "it\\.printStackTrace", "\\$\\{?failure\\}?\""),
            "GATE 21M failure reporting"
        )
        assertTrue(controller.contains("failure.javaClass.simpleName"))
        assertTrue(transactionReader.contains("catch (failure: Throwable)"))
        assertTrue(transactionReader.contains("throw cancellation"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-22

    @Test
    fun `AUDIT-21M-R2-22 the section states what a preference change does not touch`() {
        assertTrue(sectionUi.contains("Applies to new browsing and new study sessions only"))
        assertTrue(sectionUi.contains("keep the backend they"))
        assertTrue(sectionUi.contains("remains connected to its original"))
        // The switch-effect notice is conditional on a confirmed write AND a real active session.
        assertTrue(sectionUi.contains("saveConfirmed && activeSession is ActiveStudySessionUi.One"))
        // The screen renders it as an informational banner, never a blocking dialog.
        assertTrue(settingsScreen.contains("switchEffectNotice"))
        assertFalse(settingsScreen.contains("AlertDialog(\n                    onDismissRequest = { showBackendSwitch"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-23

    @Test
    fun `AUDIT-21M-R2-23 disabled and informational rows carry a reason, and no row is icon-only`() {
        // Type level: the informational rows cannot be built without an explanation.
        assertTrue(sectionUi.contains("val explanation: String\n) : AnkiBackendRowUi"))
        assertTrue(sectionUi.contains("override val explanation: String"))
        // Every row has a text label and a status word, so colour is never the only indicator.
        assertTrue(sectionUi.contains("override val statusLabel: String"))
        assertTrue(settingsScreen.contains("description = instanceDescription(row)"))
        assertTrue(settingsScreen.contains("enabled = row.selectable"))
        assertTrue(settingsScreen.contains("enabled = false"))
        // No icon-only control was added by the gate.
        assertNoPatterns(settingsScreen, listOf("IconButton\\(\\s*onClick[^)]*\\)\\s*\\{\\s*Icon\\("),
            "the Settings backend section")
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-24

    @Test
    fun `AUDIT-21M-R2-24 layout stays direction-agnostic for RTL and adds no string resources`() {
        val screens = settingsScreen + diagnosticsScreen
        assertNoTokens(screens, listOf("LayoutDirection.Ltr", "LayoutDirection.Rtl", "absolutePadding"),
            "the GATE 21M screens")
        assertNoPatterns(screens, listOf("padding\\(left =", "padding\\(right ="), "the GATE 21M screens")
        // Copy lives in the presentation models, per the existing convention; no resource ids were added.
        assertNoTokens(sectionUi + diagnosticsUi + controller, listOf("R.string", "stringResource("),
            "the GATE 21M presentation models")
        // Technical identifiers are shown verbatim and are never translated.
        assertTrue(sectionUi.contains("val identityToken: String = backendId.stableId"))
        assertTrue(settingsScreen.contains("append(row.identityToken)"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-25

    @Test
    fun `AUDIT-21M-R2-25 the export path is unchanged and the new rows are not silently exported`() {
        // The repository that owns sanitizing and export was not touched by this gate.
        val repository = main("data/repository/DiagnosticsRepository.kt").code()
        assertNoTokens(repository, listOf("AnkiBackendDiagnosticsInput", "buildAnkiBackendDiagnosticsLines",
            "AnkiUnresolvedTransactionReader"), "the locked diagnostics repository")
        // The screen renders the new rows in exactly one place, outside the copy/share helpers.
        assertEquals(1, Regex("ankiBackendRows\\(\\)").findAll(diagnosticsScreen).count())
        assertTrue(diagnosticsScreen.contains("viewModel.getExportText()"))
        // Export stays exactly the repository's job: the new rows are not appended to it, so widening
        // the export surface remains a privacy decision for another gate (with its own test).
        assertTrue(
            diagnosticsViewModel.contains("fun getExportText(): String = diagnosticsRepository.getExportText()")
        )
        assertTrue(
            diagnosticsViewModel.contains("fun getSummaryText(): String = diagnosticsRepository.getSummaryText()")
        )
        assertNoPatterns(
            diagnosticsViewModel,
            listOf("getExportText\\(\\)\\s*\\+", "ankiBackendRows\\(\\)[^\\n]*getExportText"),
            "the diagnostics ViewModel (export path)"
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21M-R2-26

    @Test
    fun `AUDIT-21M-R2-26 GATE 20, 21L and 21N behaviour is not implemented by this gate`() {
        // Scoped to the files this gate ADDS: `AppContainer.kt` legitimately wires the pre-existing
        // GATE 21G session machine and the GATE 17/18 coordinators, and GATE 21M neither adds nor
        // changes any of it (asserted separately below, on the wiring lines themselves).
        val all = addedFiles.joinToString("\n") { it.code() }
        assertNoTokens(
            all,
            listOf(
                "StudySessionMachine", "NoteCreationCoordinator(", "NoteMutationCoordinator(",
                "ReviewCommitLedger(", "DurableReviewerActionLedger(", "AnkiDroidBackend(",
                "PcAnkiBackend(", "WebSocketAgentConnection(", "FilteredDeckReview", "Gate21N"
            ),
            "GATE 21M (no GATE 20/21L/21N implementation, no new backend)"
        )
        // Production wiring is unchanged in substance: one registry, built from the configured backend.
        assertTrue(container.contains("AnkiBackendRegistry(listOf(ankiDroidBackend))"))
        assertEquals(1, Regex("AnkiBackendRegistry\\(").findAll(container).count())
        // The nav host passes existing authoritative components and nothing else.
        listOf(
            "ankiBackendRegistry = container.ankiBackendRegistry",
            "ankiBackendStatusCenter = container.ankiBackendStatusCenter",
            "ankiBackendPreference = container.backendPreferenceRepository.preference",
            "ankiBackendPreferenceWriter",
            "ankiSessionLocks = container.ankiStudyBackendLocks",
            "ankiBackendAutoPreview = container.ankiBackendAutoPreview",
            "ankiUnresolvedTransactions = container.ankiUnresolvedTransactions"
        ).forEach { assertTrue("AppNavHost must wire `$it`", navHost.contains(it)) }
        assertFalse(navHost.contains("FakeAnkiBackend"))
    }
}
