package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * GATE 13 PART II — Mandatory Self-Audit (AUDIT 1-8) and the non-negotiable invariants
 * (INV-13-01 … INV-13-20), as executable checks.
 *
 * Source scans are supplements to the behavioural tests, not a substitute for them: they catch the
 * *shape* regressions a future change would otherwise slip in (a second status type, a UI bypass, an
 * automatic retry path, a ledger swap).
 */
class Gate13ArchitectureAuditTest {

    private val mainJava: File by lazy {
        listOf(
            File("app/src/main/java/com/studyagent/client"),
            File("../app/src/main/java/com/studyagent/client")
        ).firstOrNull { it.isDirectory }
            ?: error("Cannot locate app/src/main/java/com/studyagent/client from ${File(".").absolutePath}")
    }

    private val mainSources: List<File> by lazy {
        File(mainJava, "core").walkTopDown().filter { it.extension == "kt" }.toList() +
            File(mainJava, "data").walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)^\\s*//.*$"), " ")

    private fun file(name: String): File = mainSources.single { it.name == name }

    private fun source(name: String): String = code(file(name))

    /** Reads a production source by path relative to the package root (e.g. a UI model file). */
    private fun sourceAt(relativePath: String): String {
        val file = File(mainJava, relativePath)
        check(file.isFile) { "Missing production source: $relativePath" }
        return code(file)
    }

    private fun offenders(pattern: String): List<String> =
        mainSources.filter { code(it).contains(pattern) }.map { it.name }.sorted()

    // ------------------------------------------------------------------- AUDIT 1: one action model

    @Test
    fun `AUDIT 1 - ReviewerActionStatus is the only durable reviewer-action transaction state`() {
        assertEquals(
            listOf(
                ReviewerActionStatus.PREPARED,
                ReviewerActionStatus.SUBMITTING,
                ReviewerActionStatus.APPLIED,
                ReviewerActionStatus.RETRY_ALLOWED,
                ReviewerActionStatus.AMBIGUOUS
            ),
            ReviewerActionStatus.entries
        )
        // The retired vocabulary must not survive anywhere in production code.
        val retired = listOf("ReviewActionState", "ReviewerActionState", "ApplyingSucceeded", "PendingActionState")
        retired.forEach { token ->
            assertTrue("retired action-state name '$token' is back: ${offenders(token)}", offenders(token).isEmpty())
        }
        // The presentation model is a projection, and it says so: it is derived from the durable
        // status in exactly one place and never stores a competing lifecycle of its own.
        val ui = source("ReviewerActionUiState.kt")
        assertTrue(ui.contains("fun from("))
        assertTrue(ui.contains("ReviewerActionStatus"))
        listOf("Idle", "Saving", "RetryAvailable", "VerificationRequired").forEach {
            assertTrue("UI projection must expose $it", ui.contains(it))
        }
        // Blocked / Failed are *not* durable states; a refusal is presentation only.
        assertTrue(ui.contains("ReviewerActionRefusal"))
        assertFalse("no failed business state may exist", ui.contains("data class Failed("))
    }

    @Test
    fun `AUDIT 1 - a refusal is never a transaction and a projection is never truth`() {
        val reducer = source("StudyReducer.kt")
        // A blocked request must not create an action record.
        assertTrue(reducer.contains("reviewerActionRefusal = ReviewerActionRefusal("))
        val policy = source("ReviewerActionPolicy.kt")
        // The policy reads durable statuses only.
        assertTrue(policy.contains("commitStatus: ReviewCommitStatus?"))
        assertTrue(policy.contains("activeActionStatus: ReviewerActionStatus?"))
        assertFalse("the policy must not read a UI projection", policy.contains("ReviewerActionUiState"))
    }

    // ----------------------------------------------------- AUDIT 2: rating and action separation

    @Test
    fun `AUDIT 2 - the two transaction families keep separate statuses, identities and ledgers`() {
        val commitLedger = source("ReviewCommitLedger.kt")
        val actionLedger = source("ReviewerActionLedger.kt")
        assertFalse("the rating ledger must never name the action status", commitLedger.contains("ReviewerActionStatus"))
        assertFalse("the action ledger must never name the rating status", actionLedger.contains("ReviewCommitStatus"))
        assertFalse(
            "no record may be stored in the wrong ledger",
            actionLedger.contains("ReviewCommitRecord")
        )
        // Separate durable cells, separate files.
        assertTrue(actionLedger.contains("interface ReviewerActionStore"))
        assertTrue(source("DataStoreReviewerActionStore.kt").contains("anki_reviewer_action_ledger"))
        assertTrue(source("DataStoreReviewCommitStore.kt").contains("anki_review_commit_ledger"))
        // Distinct identity types.
        assertNotEquals(ReviewerActionId.of(
            AnkiBackendId.AnkiDroidLocal, "s", ReviewTurnId("t"), ReviewerAction.BuryCard
        ).value, ReviewCommitId(AnkiBackendId.AnkiDroidLocal, "s", ReviewTurnId("t")).stableKey)
    }

    @Test
    fun `AUDIT 2 and INV-13-04 - reviewer actions never create a rating transaction`() {
        // No file of the action family may *create* rating truth.
        mainSources.filter { it.name.startsWith("ReviewerAction") }.forEach { file ->
            val text = code(file)
            listOf("commitRating(", "ReviewCommitLedger(", "BackendCommitResult", "ReviewCommitRecord(")
                .forEach { token ->
                    assertFalse(
                        "${file.name} must not create rating transactions: $token",
                        text.contains(token)
                    )
                }
        }
        // The durable action vocabulary itself never mentions the rating status; the *policy* reads
        // it deliberately, because §23's table is expressed in terms of the rating transaction.
        listOf("ReviewerActionLedger.kt", "ReviewerActionStatus.kt", "ReviewerActionRecord.kt",
            "ReviewerActionBackendResult.kt").forEach { name ->
            assertFalse(
                "$name must not name the rating status",
                source(name).contains("ReviewCommitStatus")
            )
        }
        assertTrue("the policy reads the rating status on purpose",
            source("ReviewerActionPolicy.kt").contains("ReviewCommitStatus"))
    }

    // ------------------------------------------------------------ AUDIT 3: one mutation entry path

    @Test
    fun `AUDIT 3 - every action mutation flows through the coordinator`() {
        // Only the coordinator may call the backend mutation, and only the executor may call the
        // coordinator.
        val callersOfBackendMutation = mainSources
            .filter { code(it).contains(".performReviewerAction(") || code(it).contains("performReviewerAction(") }
            .map { it.name }
            .sorted()
        assertEquals(
            "only the coordinator may invoke the backend action mutation (plus its own declaration " +
                "and the AnkiDroid implementation)",
            listOf("AnkiBackend.kt", "AnkiDroidBackend.kt", "ReviewerActionCoordinator.kt"),
            callersOfBackendMutation
        )
        val executor = source("AnkiStudyEffectExecutor.kt")
        assertTrue("the executor must own the reviewer-action effects",
            executor.contains("is AnkiStudyEffect.PerformReviewerAction ->"))
        // A UI file may never reach the coordinator or the backend directly.
        listOf("AnkiDroidReviewerActionGateway.kt", "AnkiDroidBackend.kt", "ReviewerActionCoordinator.kt")
            .forEach { owner ->
                val uiUsers = File(mainJava, "ui").walkTopDown().filter { it.extension == "kt" }
                    .filter { code(it).contains(owner.removeSuffix(".kt")) }
                    .map { it.name }
                    .toList()
                assertTrue("the UI must not reference $owner: $uiUsers", uiUsers.isEmpty())
            }
    }

    @Test
    fun `AUDIT 3 - the study machine has exactly one action effect path`() {
        val machine = source("StudySessionMachine.kt")
        assertTrue(machine.contains("is AnkiStudyEffect.PerformReviewerAction,"))
        // Mutations live on the write lane; nothing else may execute them.
        val effective = mainSources.filter {
            code(it).contains("AnkiStudyEffect.PerformReviewerAction")
        }.map { it.name }.sorted()
        assertEquals(
            "the action effect may only be produced by the reducer and consumed by the machine/executor",
            listOf("AnkiStudyEffectExecutor.kt", "StudyReducer.kt", "StudySessionMachine.kt"),
            effective
        )
    }

    // --------------------------------------------------------------------- AUDIT 4: durable ordering

    @Test
    fun `AUDIT 4 - the durable order is prepared, submitting, backend, final`() {
        val coordinator = source("ReviewerActionCoordinator.kt")
        val create = coordinator.indexOf("ledger.create(")
        val boundary = coordinator.indexOf("ReviewerActionTransition.EnterMutationBoundary")
        val backendCall = coordinator.indexOf("backend.performReviewerAction(")
        val persistResult = coordinator.indexOf("result.toTransition()")
        // The durable PREPARED intent precedes the backend call, the SUBMITTING marker is written
        // *inside* the mutation boundary callback the backend receives, and the final status is
        // persisted only after the backend answered. (The behavioural proof that the marker is on
        // disk before the mutation lives in ReviewerActionCoordinatorTest.)
        assertTrue("PREPARED must precede the backend call: create=$create backend=$backendCall",
            create in 1 until backendCall)
        assertTrue(
            "SUBMITTING must be written inside the boundary callback: " +
                "backend=$backendCall boundary=$boundary final=$persistResult",
            boundary in (backendCall + 1) until persistResult
        )
        // The final status is written through the transition engine, never assigned directly.
        val engine = source("ReviewerActionStatus.kt")
        assertTrue(engine.contains("fun transition("))
        val ledger = source("ReviewerActionLedger.kt")
        assertTrue("the ledger must apply commands through the engine", ledger.contains("ReviewerActionTransitions.transition("))
        assertFalse("no path may assign a status directly",
            ledger.contains(".copy(status =") && !ledger.contains("result.record"))
    }

    // ------------------------------------------------------------------- AUDIT 5: turn progression

    @Test
    fun `AUDIT 5 - only a confirmed turn-invalidating action closes the turn`() {
        val domain = source("ReviewerAction.kt")
        assertTrue(domain.contains("override val invalidatesCurrentTurn: Boolean get() = false"))
        assertEquals("two turn-invalidating actions", 2,
            Regex("invalidatesCurrentTurn: Boolean get\\(\\) = true").findAll(domain).count())
        val reducer = source("StudyReducer.kt")
        assertTrue("the reducer must use the shared barrier", reducer.contains("nextCardAllowed(action.action,"))
        // The Next effect may only appear in the confirmed-applied branch of the action outcome.
        val actionSection = reducer.substringAfter("private fun applyReviewerActionOutcome")
            .substringBefore("private fun movedActionRecovery")
        val nextIndices = Regex("AnkiStudyEffect\\.Next\\(").findAll(actionSection)
            .map { it.range.first }.toList()
        assertEquals("exactly one next-card query in the outcome handler", 1, nextIndices.size)
        val beforeNext = actionSection.substring(0, nextIndices.single())
        assertTrue(
            "the query must be gated by the shared §22 rule",
            beforeNext.contains("nextCardAllowed(action.action,")
        )
        assertTrue(
            "the query may only live in the confirmed-APPLIED branch",
            beforeNext.substringAfterLast("is AnkiReviewerActionOutcome.").startsWith("Applied ->")
        )
    }

    // ---------------------------------------------------- AUDIT 6: rating and action exclusion

    @Test
    fun `AUDIT 6 - mutual exclusion is enforced below the UI, in both directions`() {
        val coordinator = source("ReviewerActionCoordinator.kt")
        assertTrue("the action coordinator must consult the rating ledger",
            coordinator.contains("getByTurn("))
        val executor = source("AnkiStudyEffectExecutor.kt")
        assertTrue("the rating pipeline must consult the action ledger",
            executor.contains("actionLedger.findActiveForTurn("))
        val policy = source("ReviewerActionPolicy.kt")
        assertTrue(policy.contains("fun ratingBlockReason("))
    }

    // ------------------------------------------------------------------------ AUDIT 7: no auto retry

    @Test
    fun `AUDIT 7 - nothing replays an unresolved reviewer action automatically`() {
        // The RETRY_ALLOWED → PREPARED command exists in exactly one place (the engine table), and
        // the only production caller is the coordinator's explicit retry path.
        val retryCallers = mainSources.filter {
            code(it).contains("ReviewerActionTransition.RetryRequested")
        }.map { it.name }.sorted()
        assertEquals(
            "the retry command is named by the state machine, the recovery table, the coordinator " +
                "and the ledger's counter only",
            listOf(
                "ReviewerActionCoordinator.kt",
                "ReviewerActionLedger.kt",
                "ReviewerActionRecovery.kt",
                "ReviewerActionStatus.kt"
            ),
            retryCallers
        )

        // Retry is only ever reached from an explicit user event.
        val reducer = source("StudyReducer.kt")
        assertTrue(reducer.contains("is AnkiStudyEvent.RetryReviewerAction -> retryReviewerAction("))
        assertTrue(reducer.contains("AnkiStudyEvent.RecoverReviewerAction -> recoverReviewerAction("))
        // No loop / backoff / replay machinery in the action family.
        mainSources.filter { it.name.startsWith("ReviewerAction") }.forEach { file ->
            listOf("backoff", "repeat(", "while (true)", "retryLoop", "autoRetry").forEach { token ->
                assertFalse("${file.name} must not contain automatic retry machinery: $token",
                    code(file).contains(token))
            }
        }
        // The domain exposes no retry shortcut that bypasses PREPARED.
        val engine = source("ReviewerActionStatus.kt")
        assertTrue(
            "retry must return to PREPARED, never straight to SUBMITTING",
            Regex("ReviewerActionStatus\\.RETRY_ALLOWED -> after.status in setOf\\([^)]*PREPARED[^)]*\\)")
                .containsMatchIn(engine) && !Regex(
                "ReviewerActionStatus\\.RETRY_ALLOWED -> after.status in setOf\\([^)]*SUBMITTING"
            ).containsMatchIn(engine)
        )
    }

    // ------------------------------------------------------------------------ AUDIT 8: recovery

    @Test
    fun `AUDIT 8 - process restoration cannot lose unresolved action truth`() {
        val ledger = source("ReviewerActionLedger.kt")
        assertTrue("APPLIED may never be evicted while unresolved truth exists",
            ledger.contains("it.status == ReviewerActionStatus.APPLIED && it.turnId != record.turnId"))
        assertTrue("an unreadable ledger must never look empty", ledger.contains("ReviewerActionStoreRead.Unreadable"))
        val executor = source("AnkiStudyEffectExecutor.kt")
        assertTrue("startup must scan actions before any scheduler query",
            executor.contains("actionLedger.recoveryBlocker("))
        assertTrue("and it must fail closed when the action ledger is unreadable",
            executor.contains("AnkiError.ActionLedgerUnavailable(\"action_ledger_unavailable\")"))
        val machine = source("StudySessionMachine.kt")
        assertTrue("a blocked startup is a session phase", machine.contains("SessionPhase.ReviewerActionRecoveryRequired"))
        // The recovery table is declared once and owned by the action domain.
        val tables = offenders("fun reviewerActionRecoveryTransition(")
        assertEquals(listOf("ReviewerActionRecovery.kt"), tables)
    }

    // --------------------------------------------------------------- invariant locks (the pack)

    @Test
    fun `INV-13-01 to INV-13-20 - the invariant pack holds as source rules`() {
        // INV-13-08: the backend mutation is never entered without a durable SUBMITTING write.
        val backend = source("AnkiBackend.kt")
        assertTrue("the boundary-carrying entry point must exist",
            backend.contains("mutationEntry: suspend () -> Boolean"))
        // INV-13-09/10: APPLIED is terminal and AMBIGUOUS cannot retry directly.
        val engine = source("ReviewerActionStatus.kt")
        assertTrue(engine.contains("ReviewerActionTransitionRejection.APPLIED_TERMINAL"))
        assertTrue(engine.contains("ReviewerActionStatus.AMBIGUOUS -> after.status in setOf("))
        // INV-13-12: an unknown outcome is never mapped to a retry grant.
        val coordinator = source("ReviewerActionCoordinator.kt")
        assertTrue(coordinator.contains("ReviewerActionOutcome.Ambiguous(prepared)"))
        assertFalse("an ambiguous record must never be submitted",
            coordinator.contains("ReviewerActionStatus.AMBIGUOUS -> submit("))
        // INV-13-16: Anki remains the scheduler; the action family never orders cards itself.
        mainSources.filter { it.name.startsWith("ReviewerAction") }.forEach { file ->
            listOf("nextCard(", "getNextCard", "queue.removeAt").forEach { token ->
                assertFalse("${file.name} must not schedule: $token", code(file).contains(token))
            }
        }
        // INV-13-18: UI is projection only.
        val uiModels = sourceAt("ui/screens/study/ReviewerActionUiModels.kt")
        assertFalse(uiModels.contains(".dispatch("))
        assertFalse(uiModels.contains("machine."))
        // INV-13-20: public AnkiDroid API only — no private database access anywhere in the action
        // path (checked app-wide by the integration isolation test as well).
        assertTrue(offenders("getDatabasePath").isEmpty())
        assertTrue(offenders("/data/data").isEmpty())
    }

    @Test
    fun `the capability chain derives from the one capability source, never a second store`() {
        val actionFile = source("ReviewerAction.kt")
        assertTrue(actionFile.contains("fun AnkiCapabilities.reviewerActions("))
        val backend = source("AnkiBackend.kt")
        assertTrue("the backend must declare audited semantics", backend.contains("fun reviewerActionSemantics("))
        // There is exactly one capability source: the backend's own AnkiCapabilities flow.
        assertEquals(
            listOf("ReviewerAction.kt"),
            offenders("fun AnkiCapabilities.reviewerActions(")
        )
    }

    @Test
    fun `the reviewer-action ledger exposes no unrestricted write API`() {
        val iface = source("ReviewerActionLedger.kt")
            .substringAfter("interface ReviewerActionLedger {")
            .substringBefore("/** The typed result of a ledger write")
        assertTrue(iface.contains("suspend fun create("))
        assertTrue(iface.contains("suspend fun transition("))
        assertTrue(iface.contains("suspend fun get("))
        assertTrue(iface.contains("suspend fun findActiveForTurn("))
        assertTrue(iface.contains("suspend fun unresolved()"))
        assertFalse("no unrestricted save()", iface.contains("fun save("))
        assertFalse("no unrestricted status write", iface.contains("updateStatus"))
        assertFalse("no unrestricted record write", iface.contains("fun put("))
    }
}
