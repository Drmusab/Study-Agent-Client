package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 21I PART II — repository source audit (AUDIT-21I-01 … AUDIT-21I-27).
 *
 * The structural half of the audit: the rules a behavioural test cannot see from outside, because
 * they are about *where a decision may be made at all*. The behavioural half (does the action really
 * stay on its backend under churn, crash and preference change) lives in
 * `study/Gate21iReviewerActionRoutingTest` and `study/Gate21iActionEnduranceTest`; together they are
 * the evidence for the audit matrix in `docs/GATE_21I_VERIFICATION_REPORT.md`.
 *
 * Convention follows the GATE 12–21G audits: rules are checked on source text **with comments
 * stripped**, so a document may explain a forbidden spelling while live code may not use it.
 */
class Gate21iArchitectureAuditTest {

    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private val mainRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")

    /** Source with comment lines removed, so documentation cannot satisfy (or break) a rule. */
    private fun File.code(): String = readLines()
        .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") }
        .joinToString("\n")

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 21I source $relative" }
        return file
    }

    private fun mainSources(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Every file of the reviewer-action transaction family, plus its two seams. */
    private fun actionFamilySources(): List<File> =
        File(mainRoot, "core/anki").listFiles()!!
            .filter { it.isFile && it.name.startsWith("ReviewerAction") } +
            listOf(
                main("core/study/AnkiStudyEffectExecutor.kt"),
                main("data/anki/ankidroid/AnkiDroidReviewerActionGateway.kt"),
                main("data/anki/ankidroid/AnkiDroidReviewerActionCommitter.kt")
            )

    /**
     * The class-member bodies of one file, keyed by function name (four-space indentation is a class
     * member; nested local functions are indented deeper and stay inside their owner's body). Used to
     * assert *where* a token may appear, not merely whether it appears.
     */
    private fun memberBodies(code: String): Map<String, String> {
        val pattern = Regex("(?m)^    (?:private |internal |public )?(?:suspend )?fun ([A-Za-z0-9_]+)\\(")
        val matches = pattern.findAll(code).toList()
        return matches.mapIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: code.length
            match.groupValues[1] to code.substring(match.range.first, end)
        }.toMap()
    }

    private fun executorMembers(): Map<String, String> =
        memberBodies(main("core/study/AnkiStudyEffectExecutor.kt").code())

    // ------------------------------------------- AUDIT-21I-01 / 02 / 30 / 31: one action authority

    @Test
    fun `gate 21i keeps the gate 13 status vocabulary and adds no second action authority`() {
        // The GATE 13 vocabulary, unchanged: same five names, same order, one declaration.
        val statusSources = mainSources()
            .filter { it.code().contains("enum class ReviewerActionStatus {") }
        assertEquals(
            "exactly one ReviewerActionStatus enum exists in production",
            listOf("core/anki/ReviewerActionStatus.kt"),
            statusSources.map { it.relativeTo(mainRoot).path.replace(File.separatorChar, '/') }
        )
        val body = main("core/anki/ReviewerActionStatus.kt").code()
            .substringAfter("enum class ReviewerActionStatus {")
            .substringBefore("\n}")
        assertEquals(
            listOf("PREPARED", "SUBMITTING", "APPLIED", "RETRY_ALLOWED", "AMBIGUOUS"),
            Regex("(?m)^    ([A-Z_]+),?$").findAll(body).map { it.groupValues[1] }.toList()
        )

        // One ledger, one coordinator, one store, one family routing policy — 21I added no sibling.
        val all = mainSources().map { it.code() }
        fun count(token: String) = all.count { it.contains(token) }
        assertEquals(1, count("class DurableReviewerActionLedger"))
        assertEquals(1, count("interface ReviewerActionLedger {"))
        assertEquals(1, count("class DefaultReviewerActionCoordinator"))
        assertEquals(1, count("interface ReviewerActionCoordinator {"))
        assertEquals(1, count("interface ReviewerActionStore {"))
        assertEquals(1, count("class ReviewerActionRoutingPolicy"))

        // INV-21I-31 — the rating family keeps its own, separate transaction authority.
        assertEquals(1, count("enum class ReviewCommitStatus {"))
        assertEquals(1, count("class ReviewCommitRoutingPolicy"))
        assertEquals(1, count("class ReviewCommitLedger("))

        // The 21I file itself declares no ledger, coordinator, registry, resolver or store.
        val policy = main("core/anki/ReviewerActionRoutingPolicy.kt").code()
        listOf(
            "class DurableReviewerActionLedger", "interface ReviewerActionLedger",
            "class DefaultReviewerActionCoordinator", "class AnkiBackendRegistry",
            "class AnkiBackendResolver", "object AnkiBackendResolver", "class ReviewCommitLedger",
            "OfflineQueue", "WorkManager"
        ).forEach { token ->
            assertFalse("the routing policy must not declare $token", policy.contains(token))
        }
    }

    // ------------------------------------- AUDIT-21I-03 / 04: immutable, self-consistent identity

    @Test
    fun `the durable action record carries one immutable backend, target, kind and scope`() {
        val record = main("core/anki/ReviewerActionRecord.kt").code()
        // From the record's own declaration onwards: `ReviewerActionId` above it has an `init` too.
        val declaration = record.substringAfter("data class ReviewerActionRecord(")
        val body = declaration.substringBefore("\n) {")
        listOf(
            "val actionId", "val sessionId", "val turnId", "val backendId", "val collectionRef",
            "val cardRef", "val action", "val status", "val frozenIdempotentReplay",
            "val frozenAuthoritativeReconciliation"
        ).forEach { field -> assertTrue("the record must carry $field", body.contains(field)) }
        assertFalse("no mutable field in the durable record body", body.contains("var "))

        // Ownership coherence is enforced at construction, so a mismatched target cannot exist.
        val init = declaration.substringAfter("init {").substringBefore("\n    }")
        assertTrue(init.contains("require(cardRef.backendId == backendId)"))
        assertTrue(init.contains("require(collectionRef == null || collectionRef.backendId == backendId)"))
        assertTrue(
            "the binding key and the resource key may never disagree",
            init.contains("collectionRef.collectionKey == cardRef.collectionKey")
        )

        // Nothing in the family rewrites an admitted destination, target, kind or scope.
        actionFamilySources().forEach { file ->
            val code = file.code()
            listOf(
                "copy(backendId =", "copy(cardRef =", "copy(collectionRef =", "copy(action =",
                "copy(turnId =", "copy(sessionId =", "copy(actionId ="
            ).forEach { token ->
                assertFalse("${file.name} must never rewrite action identity ($token)", code.contains(token))
            }
        }
    }

    // ------------------------ AUDIT-21I-05 / 06 / 07 / 23: exact lookup, no AUTO, no read-context

    @Test
    fun `action routing reads the record's own identity and nothing else`() {
        val policy = main("core/anki/ReviewerActionRoutingPolicy.kt").code()
        // The only backend lookups are by the identity the transaction carries.
        assertTrue(policy.contains("registry.find(ownerBackendId)"))
        assertTrue(policy.contains("registry.find(record.backendId)"))
        assertEquals(
            "the family policy reads the registry exactly three times, always by identity",
            3, Regex("registry\\.(find|ids|backends|all|first)").findAll(policy).count()
        )
        // No preference, no resolver, no AUTO, no availability sweep, no read context, no fallback.
        listOf(
            "AnkiBackendResolver", ".resolve(", "AUTO", "BackendPreference", "preference",
            "Preference", "AnkiBackendMode", "AnkiBackendSelector", "firstOrNull", "fallback",
            "alternate", "registry.ids", "AnkiReadContext", "readContext", "libraryBackend",
            "AnkiBackendSelector"
        ).forEach { token ->
            assertFalse("the family policy must not consult $token", policy.contains(token))
        }
        // Binding evidence comes from the transaction's own collection ref — never from a resource
        // ref, a deck name or an endpoint (AUDIT-21I-04/10).
        val binding = policy.substringAfter("fun bindingCollectionKey(").substringBefore("\n")
        assertTrue(binding.contains("record.collectionRef?.collectionKey"))
        assertFalse(binding.contains("cardRef"))
        assertFalse(binding.contains("deckRef"))
    }

    @Test
    fun `the executor consults a resolver only before the session is bound`() {
        val members = executorMembers()
        val begin = members["begin"] ?: error("begin() not found")
        assertTrue(
            "session start resolves through the 21E resolver (pre-binding only)",
            begin.contains("AnkiBackendResolver2.resolve(")
        )
        // Every transaction member routes by durable identity instead.
        listOf(
            "reviewerAction", "retryReviewerAction", "recoverReviewerAction", "reviewerActionEvent",
            "actionRecoveryPlan", "restoreSession", "commitTransaction", "reconcileRecord",
            "recoveryPlan", "read", "endReview"
        ).forEach { name ->
            val body = members[name] ?: error("$name() not found")
            listOf(
                "AnkiBackendResolver", ".resolve(", "BackendPreference", "preference", "AUTO",
                "AnkiBackendSelector", "AnkiReadContext", "readContext"
            ).forEach { token ->
                assertFalse("$name() must not route by $token", body.contains(token))
            }
        }
        // The action admission path is gated by the family policy and by the 21G session lock.
        val action = members["reviewerAction"] ?: error("reviewerAction() not found")
        val lockCheck = action.indexOf("sessionLockConsistent(")
        val admission = action.indexOf("actionRouting.route(")
        val dispatch = action.indexOf("coordinator.perform(")
        assertTrue("the 21G lock is checked first", lockCheck >= 0)
        assertTrue("the 21I OP-M1 admission gate runs before dispatch", admission in 0 until dispatch)
        assertTrue(lockCheck < admission)
        // A retry re-runs the identity contract against the durable record before dispatching.
        val retry = members["retryReviewerAction"] ?: error("retryReviewerAction() not found")
        assertTrue(
            "a retry is re-validated against the record's own backend",
            retry.indexOf("actionRouting.route(") in 0 until retry.indexOf("coordinator.retry(")
        )
        // Recovery groups by the identity each record carries (INV-21I-21).
        val plan = main("core/anki/ReviewerActionRoutingPolicy.kt").code()
            .substringAfter("fun recoveryPlan(")
        assertTrue(plan.contains("groupByOwnerBackend(records) { it.backendId }"))
    }

    // ------------------------------- AUDIT-21I-08 / 09 / 25: capability truth, scope, no scheduler

    @Test
    fun `capability and scope claims come from the backend contract, never from a guess`() {
        val backend = main("core/anki/AnkiBackend.kt").code()
        // The default claims nothing: an unaudited backend cannot inherit an action promise.
        assertTrue(
            "the default action entry point refuses with a typed unsupported error",
            backend.contains("ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.UnsupportedAction(action.key))")
        )
        assertTrue(
            "the default semantics are UNVERIFIED",
            backend.contains("ReviewerActionSemantics.UNVERIFIED")
        )
        assertTrue(
            "the default reconciliation is Unresolved, never ConfirmedApplied",
            backend.contains("ReviewerActionReconciliationResult.Unresolved()")
        )

        // The pinned AnkiDroid contract: no flag write path at all, bury/suspend verified.
        val ankiDroid = main("data/anki/ankidroid/AnkiDroidBackend.kt").code()
        assertTrue("AnkiDroid never claims a flag capability", ankiDroid.contains("flags = false"))
        val semantics = ankiDroid.substringAfter("override fun reviewerActionSemantics(")
            .substringBefore("override suspend fun reconcileReviewerAction(")
        assertTrue(semantics.contains("ReviewerActionKind.FLAG -> ReviewerActionSemantics.UNVERIFIED"))
        assertTrue(semantics.contains("supportsAuthoritativeReconciliation = true"))

        // The modelled action family is card-scoped only: nothing note- or sibling-scoped exists, so
        // no adapter can silently widen a card action into a note mutation (INV-21I-08).
        val action = main("core/anki/ReviewerAction.kt").code()
        assertTrue(action.contains("data class SetFlag("))
        assertTrue(action.contains("data object BuryCard"))
        assertTrue(action.contains("data object SuspendCard"))
        listOf("BuryNote", "SuspendNote", "Sibling", "NoteId", "noteId", "bulk", "Bulk").forEach { token ->
            assertFalse("no note/sibling-scoped action may exist ($token)", action.contains(token))
        }
        assertEquals(
            "exactly three actions are modelled",
            3, Regex("ReviewerActionKind\\.(FLAG|BURY|SUSPEND)").findAll(action)
                .map { it.groupValues[1] }.distinct().count()
        )

        // No client-side scheduler: the action family computes no interval, queue or FSRS state.
        actionFamilySources().forEach { file ->
            val code = file.code()
            listOf(
                "fsrs", "FSRS", "easeFactor", "computeInterval", "dueQueue", "stability =",
                "difficulty =", "scheduleCard", "answerCard"
            ).forEach { token ->
                assertFalse("${file.name} must not implement scheduling ($token)", code.contains(token))
            }
        }
    }

    // ------------------- AUDIT-21I-10 / 14 / 15 / 19: assurance, retry evidence, honest reconcile

    @Test
    fun `collection assurance follows the locked matrix and reconciliation stays honest`() {
        val policy = main("core/anki/ReviewerActionRoutingPolicy.kt").code()
        val reconcile = policy.substringAfter("fun reconcileRoute(")
        // A verified mismatch and a degraded key are mismatch-grade in every family: both block.
        assertTrue(reconcile.contains("AnkiIdentityAssurance.AuthoritativeMismatch -> blocked("))
        assertTrue(reconcile.contains("AnkiUnverifiabilityQualifier.DEGRADED"))
        assertTrue(reconcile.contains("blockReasonToken(AnkiContinuationBlockReason.COLLECTION_MISMATCH)"))
        // An authoritative match additionally requires the family's own reconciliation evidence.
        assertTrue(reconcile.contains("familyReconciliationAvailable(record, backend)"))
        assertTrue(
            "RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH is a real refusal, not a comment",
            reconcile.contains("AnkiContinuationBlockReason.RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH")
        )
        // Retry/reconciliation capability is the frozen promise ∩ the live claim: it can only shrink.
        val capability = policy.substringAfter("private fun familyReconciliationAvailable(")
        assertTrue(capability.contains("record.frozenAuthoritativeReconciliation"))
        assertTrue(capability.contains("supportsAuthoritativeReconciliation"))

        // The reconciler gates identity *before* the provider and only ever reads.
        val recovery = main("core/anki/ReviewerActionRecovery.kt").code()
        val reconciler = recovery.substringAfter("override suspend fun reconcile(record: ReviewerActionRecord)")
        val gate = reconciler.indexOf("routing.reconcileRoute(record")
        val probe = reconciler.indexOf("backend.reconcileReviewerAction(")
        assertTrue("the OP-M3 identity gate exists", gate >= 0)
        assertTrue("the provider read exists", probe >= 0)
        assertTrue("identity is decided before any provider call", gate < probe)
        assertTrue(
            "a reconciler holds no session context, so it may not claim in-process continuity",
            reconciler.contains("routing.reconcileRoute(record, boundIsLiveInThisProcess = false)")
        )
        assertTrue(
            "a blocked cell returns Unresolved without reading",
            reconciler.contains("is ReviewerActionRoute.Blocked ->")
        )
        listOf(
            "performReviewerAction", "commitRating", "nextCard", "updateNote", "createNote",
            "buryCards", "suspendCards", "AnkiBackendResolver", ".resolve(", "preference"
        ).forEach { token ->
            assertFalse("reconciliation must never mutate or re-route ($token)", reconciler.contains(token))
        }
        // Attribution stays with the record's own backend and card.
        assertTrue(reconciler.contains("record.cardRef.backendId != backend.id"))
    }

    // --------------------- AUDIT-21I-11 / 12 / 16 / 18: durable barriers in the required order

    @Test
    fun `durable preparation and submission precede the irreversible backend call`() {
        val coordinator = main("core/anki/ReviewerActionCoordinator.kt").code()
        val create = coordinator.indexOf("ledger.create(request.toRecord(")
        val dispatch = coordinator.indexOf("backend.performReviewerAction(record.cardRef, record.action)")
        val boundary = coordinator.indexOf("ReviewerActionTransition.EnterMutationBoundary)")
        assertTrue(create >= 0 && dispatch >= 0 && boundary >= 0)
        assertTrue("PREPARED is durable before any backend call", create < dispatch)
        assertTrue(
            "SUBMITTING is written at the mutation boundary the backend itself opens",
            boundary in dispatch..(dispatch + 900)
        )
        // A failed boundary write is never a retry grant invented by the caller.
        assertTrue(coordinator.contains("boundaryWriteFailed"))
        assertTrue(coordinator.contains("persistBoundaryViolation(record, result)"))
        // The backend is looked up by the record's own identity, and a missing owner is not a
        // licence to pick another one.
        val submit = coordinator.substringAfter("private suspend fun submit(record: ReviewerActionRecord)")
        assertTrue(submit.contains("registry.find(record.backendId)"))
        assertTrue(submit.contains("\"backend_missing\""))
        listOf("AnkiBackendResolver", ".resolve(", "preference", "firstOrNull", "registry.ids")
            .forEach { token ->
                assertFalse("submit() must not route by $token", submit.contains(token))
            }

        // Session-start recovery scans both ledgers before any scheduler traffic (INV-21I-28).
        val begin = executorMembers()["begin"] ?: error("begin() not found")
        val actionBlocker = begin.indexOf("actionLedger.recoveryBlocker(")
        val decks = begin.indexOf("backend.getDecks()")
        val beginReview = begin.indexOf("backend.beginReview(")
        assertTrue("the action ledger is scanned at start", actionBlocker >= 0)
        assertTrue(actionBlocker < decks && decks < beginReview)
        assertTrue(begin.contains("AnkiStudyEvent.ReviewerActionRecoveryBlocked("))

        // The restore barrier counts an unresolved action exactly like an unresolved commit.
        val restore = executorMembers()["restoreSession"] ?: error("restoreSession() not found")
        assertTrue(restore.contains("actionLedger?.unresolved()"))
        assertTrue(restore.contains("record.status != ReviewerActionStatus.APPLIED"))
        assertTrue(restore.contains("record.backendId == lock.backendId"))
    }

    @Test
    fun `post-action progression uses the one shared backend-authoritative barrier`() {
        val barrier = main("core/anki/ReviewerActionStatus.kt").code()
            .substringAfter("fun nextCardAllowed(action: ReviewerAction, status: ReviewerActionStatus)")
        assertTrue(
            "the rule is invalidatesCurrentTurn AND APPLIED — nothing else",
            barrier.contains("action.invalidatesCurrentTurn && status == ReviewerActionStatus.APPLIED")
        )
        val reducer = main("core/study/StudyReducer.kt").code()
        val applied = reducer.indexOf("is AnkiReviewerActionOutcome.Applied ->")
        val gate = reducer.indexOf("if (!nextCardAllowed(action.action, ReviewerActionStatus.APPLIED))")
        val next = reducer.indexOf("listOf(AnkiStudyEffect.Next(state.epoch, session))", gate)
        assertTrue(applied >= 0 && gate >= 0 && next >= 0)
        assertTrue("the barrier is evaluated inside the applied branch", gate in applied..(applied + 2500))
        assertTrue("a fresh scheduler query follows the barrier, never a local card", gate < next)
        // The next card is always requested from the locked session's own backend.
        val read = executorMembers()["read"] ?: error("read() not found")
        assertTrue(read.contains("registry.find(effect.session.context.backendId)?.nextCard(effect.session)"))
        assertFalse(
            "the reducer never fabricates a review turn",
            reducer.substring(applied, next).contains("AnkiReviewTurn(")
        )
    }

    // --------------- AUDIT-21I-13 / 17 / 20 / 21 / 22: ambiguity, duplicates, staleness, legacy

    @Test
    fun `ambiguity is preserved, duplicates collapse, and stale input is refused`() {
        // The closed recovery table never lets a button press create evidence.
        val recovery = main("core/anki/ReviewerActionRecovery.kt").code()
        val retryRow = recovery.substringAfter("ReviewerActionRecoveryEvent.RETRY_REQUESTED -> when (current)")
            .substringBefore("ReviewerActionRecoveryEvent.RECONCILIATION_STARTED")
        assertTrue(retryRow.contains("ReviewerActionStatus.PREPARED, ReviewerActionStatus.RETRY_ALLOWED"))
        assertTrue("everything else is rejected", retryRow.contains("else -> null"))
        assertFalse("AMBIGUOUS is never retryable by request", retryRow.contains("AMBIGUOUS ->"))
        // An unresolved reconciliation normalizes to explicit uncertainty, never to a retry grant.
        val unresolved = recovery.substringAfter("ReviewerActionRecoveryEvent.RECONCILIATION_UNRESOLVED -> when (current)")
            .substringBefore("ReviewerActionRecoveryEvent.INTEGRITY_VIOLATION_DETECTED")
        assertTrue(unresolved.contains("ReviewerActionStatus.AMBIGUOUS"))
        assertFalse(unresolved.contains("RETRY_ALLOWED"))

        // Identity is derived, never random: duplicate input collapses onto one logical action.
        val record = main("core/anki/ReviewerActionRecord.kt").code()
        val idOf = record.substringAfter("fun of(").substringBefore("\n    }")
        assertTrue(idOf.contains("identityKey(backendId.stableId, studySessionId, turnId.value, action.key)"))
        actionFamilySources().forEach { file ->
            val code = file.code()
            listOf("UUID", "randomUUID", "Random(", "random(").forEach { token ->
                assertFalse("${file.name} must not mint random identity ($token)", code.contains(token))
            }
        }
        // The ledger, not the UI, owns duplicate suppression and per-turn exclusion.
        val ledger = main("core/anki/ReviewerActionLedger.kt").code()
        assertTrue(ledger.contains("sameAction(record)"))
        assertTrue(ledger.contains("it.turnId == record.turnId && it.isUnresolved"))
        assertTrue(ledger.contains("it.sessionId == record.sessionId && it.isUnresolved"))

        // Stale callbacks: the reducer validates epoch + action identity, and the executor validates
        // the 21G session lock before anything is dispatched.
        val reducer = main("core/study/StudyReducer.kt").code()
        assertTrue(reducer.contains("\"stale-anki-action-result\""))
        assertTrue(reducer.contains("\"stale-anki-action-retry\""))
        assertTrue(reducer.contains("\"anki-action-retry-not-safe\""))
        val action = executorMembers()["reviewerAction"] ?: error("reviewerAction() not found")
        assertTrue(action.contains("AnkiStudyEffectExecutor.SESSION_BACKEND_MISMATCH") ||
            action.contains("SESSION_BACKEND_MISMATCH"))

        // Legacy/unreadable history fails closed: no owner is ever guessed.
        assertTrue(ledger.contains("\"unsupported_schema_\$schemaVersion\""))
        assertTrue(ledger.contains("if (id.value != actionId) return null"))
        assertTrue(ledger.contains("Decoded.Unreadable(\"unreadable_action_record\")"))
        mainSources().forEach { file ->
            assertFalse(
                "${file.name} must not default an unknown owner",
                file.code().contains("?: AnkiBackendId.")
            )
        }
        val unreadable = executorMembers()["actionRecoveryPlan"] ?: error("actionRecoveryPlan() not found")
        assertTrue(
            "an unreadable ledger routes nothing at all",
            unreadable.contains("actionLedger?.unresolved().orEmpty()")
        )
    }

    // -------------------- AUDIT-21I-18 / 24 / 26 / 27: exclusion, privacy, scope, prior gates

    @Test
    fun `rating and action stay separate authorities and prior gates stay intact`() {
        // §23 mutual exclusion is enforced below the UI, in both directions, on the *other* family's
        // ledger — never by merging the two status machines.
        val coordinator = main("core/anki/ReviewerActionCoordinator.kt").code()
        assertTrue(coordinator.contains("private val commitLedger: ReviewCommitLedger? = null"))
        assertTrue(coordinator.contains("commitBlocker(request.turnId, request.sessionId)"))
        val policy = main("core/anki/ReviewerActionPolicy.kt").code()
        assertTrue(policy.contains("fun ratingBlockReason(activeActionStatus: ReviewerActionStatus?)"))
        assertTrue(policy.contains("ReviewCommitStatus.SUBMITTING -> ReviewerActionBlockReason.COMMIT_MUTATION_IN_FLIGHT"))
        assertFalse(
            "no action status is expressed in commit vocabulary",
            policy.contains("ReviewCommitStatus.APPLIED")
        )

        // 21H preserved: the rating family keeps its own policy and its own recovery pass.
        val executor = main("core/study/AnkiStudyEffectExecutor.kt").code()
        assertTrue(executor.contains("private val commitRouting: ReviewCommitRoutingPolicy"))
        assertTrue(executor.contains("private val actionRouting: ReviewerActionRoutingPolicy"))
        assertTrue(executor.contains("commitRouting.recoveryPlan(ledger?.unresolved().orEmpty())"))
        assertTrue(executor.contains("actionRouting.recoveryPlan(actionLedger?.unresolved().orEmpty())"))
        // 21G preserved: the lock tracker is still the session-ownership check for actions.
        assertTrue(executor.contains("private fun sessionLockConsistent(studySessionId: String, backendId: AnkiBackendId)"))
        assertTrue(executor.contains("private fun sessionBoundHere(studySessionId: String)"))

        // 21F preserved: no read-context type reaches the mutation path.
        listOf(
            "core/study/AnkiStudyEffectExecutor.kt",
            "core/anki/ReviewerActionRoutingPolicy.kt",
            "core/anki/ReviewerActionCoordinator.kt",
            "core/anki/ReviewerActionRecovery.kt"
        ).forEach { relative ->
            val code = main(relative).code()
            listOf("AnkiReadContextController", "AnkiReadContextBinding", "libraryBackendId", "cardBrowserBackend")
                .forEach { token ->
                    assertFalse("$relative must not read a Library context ($token)", code.contains(token))
                }
        }

        // Scope discipline: no note edit/create recovery and no backend-selection UI in this gate.
        listOf(
            "core/anki/edit/NoteMutationCoordinator.kt",
            "core/anki/create/NoteCreationCoordinator.kt",
            "core/anki/edit/NoteMutationRecord.kt",
            "core/anki/create/NoteCreationRecord.kt"
        ).forEach { relative ->
            val code = main(relative).code()
            assertFalse(
                "$relative must not adopt the reviewer-action state machine",
                code.contains("ReviewerActionStatus")
            )
        }
        File(mainRoot, "ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = file.code()
            listOf("ReviewerActionRoutingPolicy", "ReviewerActionRoute", "actionRecoveryPlan")
                .forEach { token ->
                    assertFalse("${file.name} must not become a second transaction authority ($token)",
                        code.contains(token))
                }
        }

        // Privacy: the action family touches no credential and no private AnkiDroid storage.
        actionFamilySources().forEach { file ->
            val code = file.code()
            listOf(
                "password", "Password", "Bearer", "apiKey", "api_key", "authorization",
                "SQLiteDatabase", "rawQuery", "content://", "openFileOutput"
            ).forEach { token ->
                assertFalse("${file.name} must not carry credentials or private DB access ($token)",
                    code.contains(token))
            }
        }
        // The AnkiDroid action path goes through the pinned public provider contract only.
        val gateway = main("data/anki/ankidroid/AnkiDroidReviewerActionGateway.kt").code()
        assertTrue(gateway.contains("AnkiDroidApiContract.SCHEDULE_PATH"))
        assertTrue(gateway.contains("providerClient.safeUpdate("))
    }
}
