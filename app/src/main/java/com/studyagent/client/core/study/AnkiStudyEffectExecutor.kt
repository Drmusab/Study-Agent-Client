package com.studyagent.client.core.study

import com.studyagent.client.core.anki.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Performs Anki effects in the caller's owned coroutine and reports them as events. It never writes
 * machine state; the reducer decides what every event means.
 *
 * It is also the app's [ReviewCommitCoordinator] (GATE 11B §36): the study interaction owns *that*
 * a rating was selected, this class owns *whether and how* it may reach the scheduler exactly once.
 *
 * GATE 11B §29 — the fixed commit order:
 *
 * 1. `ledger.prepare` — durable `PREPARED / INTENT_PERSISTED` (or the existing record for this id);
 * 2. `backend.prepareCommit` — read-only baseline evidence (first attempt only);
 * 3. `ledger.claim` — durable attempt claim, still `PREPARED` (the boundary is NOT entered yet);
 * 4. `ledger.markMutationEntered` — durable `SUBMITTING / MUTATION_BOUNDARY_ENTERED`, written
 *    inside the boundary callback immediately before the real scheduler mutation, never before the
 *    backend is merely invoked;
 * 5. persist the classified backend answer, then the terminal status;
 * 6. emit a success only after that final write is durable. A write failure blocks progression.
 *
 * Without a [ledger] there is no at-most-once guarantee across process death, so commits are
 * refused before dispatch (fail closed). A COMMITTED or AMBIGUOUS ledger record is answered from
 * the ledger — the backend is never called again for it (no replay, no blind resend).
 */
class AnkiStudyEffectExecutor(
    private val registry: AnkiBackendRegistry,
    private val ledger: ReviewCommitLedger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val faults: CommitFaultInjector = NoCommitFaults,
    private val phases: CommitPhaseSink = CommitPhaseSink { _, _, _ -> },
    /** Upper bound for one read-only reconciliation query; expiry leaves the commit AMBIGUOUS. */
    private val reconcileTimeoutMs: Long = DEFAULT_RECONCILE_TIMEOUT_MS,
    private val recoveryPolicy: ReviewCommitRecoveryPolicy = ReviewCommitRecoveryPolicy(),
    /**
     * GATE 11D §9 — the read-only transaction reconciler. Defaults to
     * [AnkiReviewCommitReconciler], which dispatches by the transaction's own backend identity
     * and enforces the backend's reconciliation capability; tests may substitute a scripted one.
     */
    private val reconciler: ReviewCommitReconciler =
        AnkiReviewCommitReconciler(registry, clock, reconcileTimeoutMs),
    /**
     * GATE 12 — optional pluggable evaluator for Anki study turns. When absent or when the
     * evaluator fails, the turn degrades cleanly to manual review on the same [ReviewTurnId].
     */
    private val answerEvaluator: AnkiAnswerEvaluator? = null
) : ReviewCommitCoordinator {
    init { require(reconcileTimeoutMs > 0) }

    /** Known only while this process lives. A lost durable response never authorizes replay. */
    private val unpersistedResponses = ConcurrentHashMap<ReviewCommitId, BackendCommitResult>()

    /**
     * GATE 11D §35 — content-free reconciliation observability (result token + latency, per
     * commit). Diagnostics only: nothing decides a transaction state from this.
     */
    data class ReconciliationDiagnostics(
        val resultToken: String,
        val latencyMs: Long,
        val atEpochMs: Long
    )

    private val reconciliationDiagnostics = ConcurrentHashMap<ReviewCommitId, ReconciliationDiagnostics>()

    fun reconciliationDiagnostics(commitId: ReviewCommitId): ReconciliationDiagnostics? =
        reconciliationDiagnostics[commitId]

    /**
     * Executes [effect]. [emit] receives intermediate events (commit started); the return value is
     * the final event, or `null` when there is nothing to report (for example a duplicate commit
     * effect while the same commit is already in flight — the in-flight attempt reports).
     */
    suspend fun execute(
        effect: AnkiStudyEffect,
        emit: suspend (AnkiStudyEvent) -> Unit = {}
    ): AnkiStudyEvent? {
        return when (effect) {
            AnkiStudyEffect.CancelReads -> null
            is AnkiStudyEffect.CommitRating -> {
                val outcome = commitTransaction(effect, emit) ?: return null
                AnkiStudyEvent.RatingCommitResolved(effect.epoch, effect.request.commitId, outcome)
            }
            is AnkiStudyEffect.ReconcileCommit -> reconcile(effect)
            is AnkiStudyEffect.EndReview -> {
                endReview(effect)
                null
            }
            is AnkiStudyEffect.EvaluateAnswer -> evaluateAnswer(effect)
            is AnkiStudyEffect.Begin, is AnkiStudyEffect.Next, is AnkiStudyEffect.Hydrate -> read(effect)
        }
    }

    private suspend fun evaluateAnswer(effect: AnkiStudyEffect.EvaluateAnswer): AnkiStudyEvent {
        val req = effect.request
        val evaluator = answerEvaluator ?: return AnkiStudyEvent.AnswerEvaluationFailed(
            epoch = effect.epoch,
            sessionId = req.sessionId,
            turnId = req.turnId,
            cardRef = req.cardRef,
            requestId = req.requestId,
            reason = "evaluator_unavailable"
        )
        return try {
            when (val result = evaluator.evaluate(req)) {
                is AnkiAnswerEvaluationResult.Success -> AnkiStudyEvent.AnswerEvaluationCompleted(
                    epoch = effect.epoch,
                    sessionId = req.sessionId,
                    turnId = req.turnId,
                    cardRef = req.cardRef,
                    requestId = req.requestId,
                    evaluation = result.evaluation,
                    speakFeedback = effect.speakFeedback
                )
                is AnkiAnswerEvaluationResult.Failure -> AnkiStudyEvent.AnswerEvaluationFailed(
                    epoch = effect.epoch,
                    sessionId = req.sessionId,
                    turnId = req.turnId,
                    cardRef = req.cardRef,
                    requestId = req.requestId,
                    reason = result.reason
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AnkiStudyEvent.AnswerEvaluationFailed(
                epoch = effect.epoch,
                sessionId = req.sessionId,
                turnId = req.turnId,
                cardRef = req.cardRef,
                requestId = req.requestId,
                reason = error.message?.takeIf { it.isNotBlank() } ?: "evaluator_exception"
            )
        }
    }

    // ------------------------------------------------- GATE 11B PART V diagnostics (read-only)

    /**
     * The durable record for [commitId], for diagnostics correlation only.
     *
     * Deliberately narrow: diagnostics may *read* durable transaction truth, but nothing may decide
     * anything from this call, and there is no write counterpart here — every state change goes
     * through the coordinator's three entry points (brief PART II: one transaction path).
     */
    suspend fun durableRecord(commitId: ReviewCommitId): ReviewCommitRecord? = ledger?.get(commitId)

    /**
     * What the backend currently says about its own reachability, or [CommitTruthSnapshot.UNKNOWN].
     * Scheduler truth is observed, never inferred from a commit status (brief PART II: ledger answers transactions, backend answers scheduling).
     */
    fun schedulerAvailabilityOf(backendId: AnkiBackendId): String {
        val backend = registry.find(backendId) ?: return CommitTruthSnapshot.UNKNOWN
        return when (val value = backend.availability.value) {
            is AnkiAvailability.Ready -> "ready"
            is AnkiAvailability.Checking -> CommitTruthSnapshot.UNKNOWN
            else -> value::class.simpleName?.lowercase() ?: CommitTruthSnapshot.UNKNOWN
        }
    }

    // ---------------------------------------------------------------- GATE 11B coordinator API

    override suspend fun commit(request: CommitRatingRequest): ReviewCommitOutcome {
        val outcome = commitTransaction(AnkiStudyEffect.CommitRating(0L, request, retry = false)) { }
        val commitId = request.commitId
        return durableOutcome(commitId, conflictOrStorage(outcome))
    }

    override suspend fun retry(commitId: ReviewCommitId): ReviewCommitOutcome {
        val ledger = this.ledger
            ?: return ReviewCommitOutcome.Conflict(AnkiError.CommitLedgerUnavailable())
        val record = ledger.get(commitId) ?: return ReviewCommitOutcome.Conflict(AnkiError.CommitConflict())
        // INV-11B-06: a mutation retry begins only from RETRY_ALLOWED.
        if (record.status != ReviewCommitStatus.RETRY_ALLOWED) {
            return when (record.status) {
                ReviewCommitStatus.COMMITTED -> ReviewCommitOutcome.Committed(record)
                ReviewCommitStatus.AMBIGUOUS -> ReviewCommitOutcome.Ambiguous(record)
                else -> ReviewCommitOutcome.Conflict(AnkiError.CommitConflict())
            }
        }
        val outcome = commitTransaction(AnkiStudyEffect.CommitRating(0L, record.toRequest(), retry = true)) { }
        return durableOutcome(commitId, conflictOrStorage(outcome))
    }

    override suspend fun recover(commitId: ReviewCommitId): ReviewCommitRecoveryResult {
        val ledger = this.ledger
            ?: return ReviewCommitRecoveryResult.Indeterminate(commitId, "ledger_unavailable")
        var record = ledger.get(commitId)
        if (record == null) {
            // GATE 11B PART IV, last row: "unknown is not absent". A ledger that could not be read
            // must never be reported as "no transaction", because that would license a fresh
            // mutation for a turn whose durable truth is simply unknown.
            // GATE 11D §30: a structurally invalid ledger (two unresolved commits for one
            // session) is named as an integrity failure, not swallowed as plain unavailability —
            // recovery must not guess which record is "the" transaction.
            val health = ledger.health()
            if (health !is ReviewCommitLedger.Health.Ready) {
                return when (health) {
                    is ReviewCommitLedger.Health.Unavailable ->
                        if (health.reason == ReviewCommitLedgerCodec.MULTIPLE_UNRESOLVED_PER_SESSION) {
                            ReviewCommitRecoveryResult.IntegrityFailure(health.reason)
                        } else ReviewCommitRecoveryResult.Indeterminate(commitId, "commit_ledger_unavailable")
                    ReviewCommitLedger.Health.Ready ->
                        ReviewCommitRecoveryResult.Indeterminate(commitId, "commit_ledger_unavailable")
                }
            }
            return ReviewCommitRecoveryResult.NoTransaction(commitId)
        }

        // 1. Durable backend evidence is applied first: a recorded answer is proof, not inference.
        //    Recovery never re-enters the mutation boundary — it only finishes *writing* an answer
        //    this process already holds, or finalizes one that is already durable.
        if (record.status == ReviewCommitStatus.SUBMITTING) {
            val inProcess = unpersistedResponses[commitId]
            val recovered: ReviewCommitRecord? = when {
                inProcess != null -> {
                    // The response write failed while the scheduler answer was still only in
                    // memory. Persisting it is a ledger write, never a second mutation.
                    unpersistedResponses.remove(commitId)
                    ledger.complete(commitId, inProcess)
                }
                record.phase == ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED ->
                    ledger.finalizeRecordedResponse(commitId)
                else -> record
            }
            record = recovered
                ?: return ReviewCommitRecoveryResult.Indeterminate(commitId, "commit_persistence_failure")
        }

        // 2. The durable status alone decides what recovery may do (GATE 11B §27).
        return when (val action = recoveryPolicy.classify(record)) {
            ReviewCommitRecoveryAction.ResumeCommitted ->
                ReviewCommitRecoveryResult.Recovered(action, record, ReviewCommitOutcome.Committed(record))
            ReviewCommitRecoveryAction.OfferRetry ->
                ReviewCommitRecoveryResult.Recovered(action, record, ReviewCommitOutcome.RetryAllowed(record))
            is ReviewCommitRecoveryAction.IntegrityFailure ->
                ReviewCommitRecoveryResult.Recovered(action, record, null)
            ReviewCommitRecoveryAction.RemainBlocked ->
                ReviewCommitRecoveryResult.Recovered(action, record, ReviewCommitOutcome.Ambiguous(record))
            ReviewCommitRecoveryAction.Reconcile -> reconcileRecord(ledger, record)
        }
    }

    /**
     * Read-only reconciliation of an unknown outcome. Never a mutation and never a replay: the
     * only backend call is the read-only evidence query inside [ReviewCommitReconciler]
     * (INV-11D-09). Backend/collection/card identity, the capability gate, availability and the
     * timeout all live in the reconciler (GATE 11D §9-§20); the ledger then applies the evidence
     * through its single transition engine, so the durable record moves only
     * AMBIGUOUS → {COMMITTED, RETRY_ALLOWED, AMBIGUOUS}.
     */
    private suspend fun reconcileRecord(
        ledger: ReviewCommitLedger,
        record: ReviewCommitRecord
    ): ReviewCommitRecoveryResult {
        val commitId = record.commitId
        val startedAt = clock()
        // Contract guard: a backend that throws from a read-only probe violated the
        // AnkiBackend contract; the outcome stays unknown (read-only — nothing to undo).
        val reconciliation = try {
            reconciler.reconcile(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReviewCommitReconciliationResult.Unresolved(AnkiError.Unknown("reconcile_threw"))
        }
        val token = when (reconciliation) {
            is ReviewCommitReconciliationResult.ConfirmedCommitted -> "confirmed_committed"
            is ReviewCommitReconciliationResult.ConfirmedNotCommitted -> "confirmed_not_committed"
            is ReviewCommitReconciliationResult.Unresolved -> "unresolved"
        }
        reconciliationDiagnostics[commitId] = ReconciliationDiagnostics(
            token, (clock() - startedAt).coerceAtLeast(0L), startedAt)
        val evidence = when (reconciliation) {
            is ReviewCommitReconciliationResult.ConfirmedCommitted ->
                ReconcileCommitResult.Applied("reconciled", reconciliation.receipt)
            is ReviewCommitReconciliationResult.ConfirmedNotCommitted ->
                ReconcileCommitResult.NotApplied("reconciled")
            is ReviewCommitReconciliationResult.Unresolved ->
                ReconcileCommitResult.StillAmbiguous(
                    reconciliation.reason?.commitCategory() ?: "reconciliation_unresolved")
        }
        val updated = withContext(NonCancellable) { ledger.reconcile(commitId, evidence) }
            ?: return ReviewCommitRecoveryResult.Indeterminate(commitId, "commit_persistence_failure")
        return when (updated.status) {
            ReviewCommitStatus.COMMITTED -> ReviewCommitRecoveryResult.Recovered(
                ReviewCommitRecoveryAction.ResumeCommitted, updated, ReviewCommitOutcome.Committed(updated))
            ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitRecoveryResult.Recovered(
                ReviewCommitRecoveryAction.OfferRetry, updated, ReviewCommitOutcome.RetryAllowed(updated))
            ReviewCommitStatus.AMBIGUOUS -> ReviewCommitRecoveryResult.Recovered(
                ReviewCommitRecoveryAction.RemainBlocked, updated, ReviewCommitOutcome.Ambiguous(updated))
            else -> ReviewCommitRecoveryResult.Recovered(
                ReviewCommitRecoveryAction.IntegrityFailure("recovery_${updated.status.name.lowercase()}"),
                updated, null)
        }
    }

    /** The coordinator only ever reports durable truth; an unresolved row is a conflict. */
    private suspend fun durableOutcome(commitId: ReviewCommitId, unresolved: AnkiError): ReviewCommitOutcome {
        val record = ledger?.get(commitId) ?: return ReviewCommitOutcome.Conflict(unresolved)
        return when (record.status) {
            ReviewCommitStatus.COMMITTED -> ReviewCommitOutcome.Committed(record)
            ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitOutcome.RetryAllowed(record)
            ReviewCommitStatus.AMBIGUOUS -> ReviewCommitOutcome.Ambiguous(record)
            ReviewCommitStatus.PREPARED, ReviewCommitStatus.SUBMITTING -> ReviewCommitOutcome.Conflict(unresolved)
        }
    }

    private fun conflictOrStorage(outcome: AnkiCommitOutcome?): AnkiError =
        if (outcome is AnkiCommitOutcome.PersistenceFailure) AnkiError.CommitLedgerUnavailable()
        else AnkiError.CommitConflict()

    // ---------------------------------------------------------------- reads (GATE 10, unchanged)

    private suspend fun read(effect: AnkiStudyEffect): AnkiStudyEvent? {
        return try {
            when (effect) {
                is AnkiStudyEffect.Begin -> begin(effect)
                is AnkiStudyEffect.Next -> AnkiStudyEvent.Scheduled(
                    effect.epoch,
                    registry.find(effect.session.context.backendId)?.nextCard(effect.session)
                        ?: NextCardResult.BackendUnavailable(AnkiError.BackendUnavailable())
                )
                is AnkiStudyEffect.Hydrate -> AnkiStudyEvent.Hydrated(
                    effect.epoch, effect.turn.turnId,
                    registry.find(effect.turn.backendId)?.hydrateCardContent(effect.turn.cardRef)
                        ?: AnkiResult.Failure(AnkiError.BackendUnavailable())
                )
                else -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No exception text (provider content may be sensitive), and never retry here.
            val failure = AnkiError.QueryFailure("unexpected")
            when (effect) {
                is AnkiStudyEffect.Begin -> AnkiStudyEvent.Begun(effect.epoch, AnkiResult.Failure(failure))
                is AnkiStudyEffect.Next -> AnkiStudyEvent.Scheduled(effect.epoch, NextCardResult.Failure(failure))
                is AnkiStudyEffect.Hydrate -> AnkiStudyEvent.Hydrated(effect.epoch, effect.turn.turnId, AnkiResult.Failure(failure))
                else -> null
            }
        }
    }

    private suspend fun priorUnresolved(backendId: AnkiBackendId): Int = try {
        ledger?.pendingRecovery(backendId)?.size ?: 0
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0
    }

    private suspend fun begin(effect: AnkiStudyEffect.Begin): AnkiStudyEvent {
        val request = effect.request
        fun failed(error: AnkiError) = AnkiStudyEvent.Begun(effect.epoch, AnkiResult.Failure(error))
        // Only startup probes candidates. No scheduler query is issued before the recovery scan.
        registry.ids.filter(request.preference::accepts).forEach { registry.find(it)?.refreshAvailability() }
        val resolution = AnkiBackendSelector(registry).resolve(request.preference)
        val id = when (resolution) {
            is AnkiBackendSelector.Resolution.Resolved -> resolution.backendId
            is AnkiBackendSelector.Resolution.Unavailable -> return failed(resolution.error)
            is AnkiBackendSelector.Resolution.Ambiguous -> return failed(AnkiError.InvalidRequest("ambiguous_backend"))
        }
        if (request.deck.backendId != id) return failed(AnkiError.InvalidRequest("foreign_deck"))
        val backend = registry.find(id) ?: return failed(AnkiError.BackendUnavailable())
        if (ledger != null) {
            if (ledger.health() !is ReviewCommitLedger.Health.Ready) {
                return failed(AnkiError.CommitLedgerUnavailable())
            }
            ledger.recoveryBlocker(id, request.deck.collectionKey, request.studySessionId)?.let {
                return AnkiStudyEvent.RecoveryBlocked(effect.epoch, it)
            }
        }
        when (val decks = backend.getDecks()) {
            is AnkiResult.Failure -> return failed(decks.error)
            is AnkiResult.Success -> if (decks.value.none { it.ref == request.deck }) {
                return failed(AnkiError.DeckNotFound(request.deck))
            }
        }
        val context = AnkiSessionContext(
            id, request.deck.collectionKey?.let { AnkiCollectionIdentity(id, it) }, request.deck,
            effect.startedAtMs, backend.capabilities.value, request.studySessionId
        )
        val result = backend.beginReview(BeginReviewRequest(context))
        val prior = if (result is AnkiResult.Success) priorUnresolved(id) else 0
        return AnkiStudyEvent.Begun(effect.epoch, result, prior)
    }

    // ---------------------------------------------------------------- GATE 11B commit

    /**
     * Runs one commit attempt. Returns `null` only when another attempt is already in flight, in
     * which case *that* attempt reports the outcome.
     */
    private suspend fun commitTransaction(
        effect: AnkiStudyEffect.CommitRating,
        emit: suspend (AnkiStudyEvent) -> Unit = {}
    ): AnkiCommitOutcome? {
        val request = effect.request
        val commitId = request.commitId
        fun resolved(outcome: AnkiCommitOutcome) = outcome
        fun refused(category: String) = AnkiCommitOutcome.Failed(category, dispatched = false)

        val ledger = this.ledger ?: return AnkiCommitOutcome.PersistenceFailure("ledger_unavailable")
        fun storageFault(category: String) = AnkiCommitOutcome.PersistenceFailure(category)


        faults.on(CommitFaultPoint.BEFORE_LEDGER_CREATE)
        // 1. Durable intent: PREPARED / INTENT_PERSISTED, or the existing record for this identity.
        // Semantics are frozen here and are not rewritten if the record already exists.
        val backendForFreeze = registry.find(commitId.backendId)
        val record = when (val prepared = ledger.prepare(request, backendForFreeze?.commitSemantics?.enforced(backendForFreeze.id))) {
            is ReviewCommitLedger.PrepareResult.Prepared -> {
                // INTENT is durable: this is the CREATED marker of the transaction.
                phases.onPhase(DURABLE_CREATED, 0, prepared.record.commitId)
                prepared.record
            }
            is ReviewCommitLedger.PrepareResult.Existing -> prepared.record
            is ReviewCommitLedger.PrepareResult.Tombstoned -> return AnkiCommitOutcome.Committed(
                AnkiCommitOutcome.SOURCE_LEDGER_TOMBSTONE)
            // The recorded rating is immutable: a different payload for this id is never sent.
            is ReviewCommitLedger.PrepareResult.Conflict -> return refused("commit_payload_conflict")
            ReviewCommitLedger.PrepareResult.Full -> return storageFault("ledger_full")
            is ReviewCommitLedger.PrepareResult.StoreFailed -> return storageFault("commit_persistence_failure")
            is ReviewCommitLedger.PrepareResult.Rejected -> return storageFault("invalid_commit_record")
            is ReviewCommitLedger.PrepareResult.Unavailable -> return storageFault("ledger_unavailable")
        }
        // The durable row exists (or already existed): publish the guarantee the ledger froze with
        // it, so every later outcome — including a preflight refusal that never claims the write —
        // correlates with the same guarantee level. Correlation only: no status, no phase change.
        emit(AnkiStudyEvent.RatingCommitPrepared(effect.epoch, commitId, record.frozenGuarantee))

        // Answer from the ledger whenever the record is not claimable by this effect.
        when (record.status) {
            ReviewCommitStatus.COMMITTED -> return AnkiCommitOutcome.Committed(AnkiCommitOutcome.SOURCE_LEDGER_REPLAY)
            ReviewCommitStatus.AMBIGUOUS ->
                return AnkiCommitOutcome.Ambiguous(record.failure?.category ?: "ambiguous")
            ReviewCommitStatus.SUBMITTING -> return null // in flight in this process; that attempt reports
            ReviewCommitStatus.RETRY_ALLOWED -> if (!effect.retry) return resolved(record.toOutcome())
            // PREPARED: the boundary was never entered, so this attempt may still be submitted.
            ReviewCommitStatus.PREPARED -> Unit
        }

        val backend = registry.find(commitId.backendId)
        if (backend == null) {
            val saved = ledger.markRefused(commitId, "backend_missing")
            return if (saved != null) saved.toOutcome() else storageFault("commit_persistence_failure")
        }

        // 2. Read-only baseline evidence, captured once and then immutable in the ledger.
        var evidence = record.evidence
        if (evidence == null) {
            val preparation = try {
                backend.prepareCommit(record.toRequest())
            } catch (cancelled: CancellationException) {
                throw cancelled // read-only preparation; PREPARED / RETRY_ALLOWED remains safe
            } catch (_: Exception) {
                CommitPreparation.Refused(AnkiError.Unknown("prepare_threw"), retryable = true)
            }
            when (preparation) {
                is CommitPreparation.Refused -> {
                    val category = preparation.error.commitCategory()
                    val saved = ledger.markRefused(commitId, category)
                    return if (saved != null) saved.toOutcome() else storageFault("commit_persistence_failure")
                }
                is CommitPreparation.Ready -> evidence = preparation.evidence
            }
        }

        // 3. Durable attempt claim. Still PREPARED: the mutation boundary has NOT been entered.
        val claimed = when (val claim = ledger.claim(commitId, evidence, allowRetry = effect.retry)) {
            is ReviewCommitLedger.ClaimResult.Claimed -> claim.record
            is ReviewCommitLedger.ClaimResult.InFlight -> return null
            is ReviewCommitLedger.ClaimResult.AlreadyCommitted -> return AnkiCommitOutcome.Committed(
                AnkiCommitOutcome.SOURCE_LEDGER_REPLAY)
            is ReviewCommitLedger.ClaimResult.NotClaimable -> return resolved(claim.record.toOutcome())
            ReviewCommitLedger.ClaimResult.Missing -> return storageFault("ledger_record_missing")
            is ReviewCommitLedger.ClaimResult.StoreFailed -> return storageFault("commit_persistence_failure")
            is ReviewCommitLedger.ClaimResult.Rejected -> return storageFault("invalid_commit_transition")
            is ReviewCommitLedger.ClaimResult.Unavailable -> return storageFault("ledger_unavailable")
        }
        phases.onPhase(DURABLE_INTENT_PERSISTED, claimed.attemptCount, commitId)
        emit(AnkiStudyEvent.RatingCommitStarted(
            effect.epoch, commitId, claimed.attemptCount, claimed.frozenGuarantee))
        faults.on(CommitFaultPoint.AFTER_PREPARED)

        // The backend may perform read-only preflight while the record is still PREPARED. Its
        // boundary callback persists SUBMITTING immediately before the real scheduler API, so a
        // crash during preflight still leaves a provably un-entered record behind.
        var mutationEntered = false
        var boundaryFault = false
        val backendResult = try {
            backend.commitRating(claimed.toRequest()) {
                if (mutationEntered) {
                    boundaryFault = true // a second callback is not another authorized dispatch
                    false
                } else {
                    val saved = withContext(NonCancellable) { ledger.markMutationEntered(commitId) }
                    mutationEntered = saved != null
                    if (!mutationEntered) boundaryFault = true
                    if (mutationEntered) {
                        phases.onPhase(DURABLE_MUTATION_BOUNDARY_ENTERED, claimed.attemptCount, commitId)
                        faults.on(CommitFaultPoint.AFTER_CALL_ENTERED)
                        faults.on(CommitFaultPoint.BEFORE_PROVIDER_CALL)
                    }
                    mutationEntered
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (mutationEntered) ledger.markAmbiguous(commitId, "cancelled_after_dispatch")
                else ledger.releaseClaim(commitId) // nothing was dispatched; the attempt stays claimable
            }
            throw cancelled
        } catch (fault: CommitFaultException) {
            throw fault
        } catch (_: Exception) {
            if (mutationEntered) BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("commit_threw"))
            else BackendCommitResult.ConfirmedNotCommitted(AnkiError.Unknown("preflight_threw"))
        }
        if (boundaryFault && !mutationEntered) return storageFault("commit_persistence_failure")
        val result = if (boundaryFault) BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("boundary_called_twice"))
            else backendResult
        if (!mutationEntered) {
            // A result before the callback is a PREPARED-side refusal — the backend certifies that
            // no scheduler mutation was dispatched. A claimed success, or an unknown outcome
            // without entering, is a backend contract violation: NEVER advance.
            val final = withContext(NonCancellable) {
                when (result) {
                    is BackendCommitResult.ConfirmedNotCommitted -> ledger.markNotCommitted(commitId,
                        result.reason?.commitCategory() ?: "not_applied")
                    is BackendCommitResult.ConfirmedCommitted, is BackendCommitResult.OutcomeUnknown ->
                        ledger.markBoundaryViolation(commitId)
                }
            } ?: return storageFault("commit_persistence_failure")
            return resolved(final.toOutcome())
        }

        // A backend result in memory alone is not authority to move to the next card.
        unpersistedResponses[commitId] = result
        faults.on(CommitFaultPoint.AFTER_PROVIDER_MUTATION)
        faults.on(CommitFaultPoint.BEFORE_RESPONSE_PERSIST)
        withContext(NonCancellable) { ledger.markResponseReceived(commitId, result) }
            ?: return storageFault("commit_persistence_failure")
        faults.on(CommitFaultPoint.AFTER_RESPONSE_PERSIST)
        faults.on(CommitFaultPoint.BEFORE_COMMITTED_PERSIST)
        val final = withContext(NonCancellable) { ledger.complete(commitId, result) }
            ?: return storageFault("commit_persistence_failure")
        faults.on(CommitFaultPoint.AFTER_COMMITTED_PERSIST)
        phases.onPhase(DURABLE_FINAL_STATUS_PERSISTED, final.attemptCount, commitId)
        unpersistedResponses.remove(commitId)
        return resolved(final.toOutcome(committedSource = AnkiCommitOutcome.SOURCE_BACKEND_CONFIRMED))
    }

    /** Study-facing reconcile effect (an explicit "check again" from the UI). */
    private suspend fun reconcile(effect: AnkiStudyEffect.ReconcileCommit): AnkiStudyEvent {
        val commitId = effect.commitId
        fun done(outcome: AnkiCommitOutcome) = AnkiStudyEvent.RatingCommitReconciled(effect.epoch, commitId, outcome)
        val record = ledger?.get(commitId)
            ?: return done(AnkiCommitOutcome.PersistenceFailure("commit_persistence_failure"))
        return when (val result = recover(commitId)) {
            is ReviewCommitRecoveryResult.NoTransaction ->
                done(AnkiCommitOutcome.PersistenceFailure("ledger_record_missing"))
            is ReviewCommitRecoveryResult.Indeterminate ->
                done(AnkiCommitOutcome.PersistenceFailure(result.reason))
            // GATE 11D §30 — a structurally invalid ledger is fail-closed: the outcome is kept
            // uncertain, nothing is retried and no next card may load.
            is ReviewCommitRecoveryResult.IntegrityFailure ->
                done(AnkiCommitOutcome.Ambiguous(result.reason))
            is ReviewCommitRecoveryResult.Recovered -> done((result.outcome ?: ReviewCommitOutcome.Ambiguous(
                result.record)).toStudyOutcome(record.resolution))
        }
    }

    private suspend fun endReview(effect: AnkiStudyEffect.EndReview) {
        try {
            registry.find(effect.session.context.backendId)?.endReview(effect.session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Releasing a handle is best effort and never a mutation towards Anki.
        }
    }

    /**
     * Durable truth → the study event payload. PREPARED means "provably un-entered", so it is a
     * not-applied outcome and never an uncertain one.
     */
    private fun ReviewCommitRecord.toOutcome(
        committedSource: String = AnkiCommitOutcome.SOURCE_LEDGER_REPLAY
    ): AnkiCommitOutcome = when (status) {
        ReviewCommitStatus.COMMITTED -> AnkiCommitOutcome.Committed(committedSource)
        ReviewCommitStatus.RETRY_ALLOWED, ReviewCommitStatus.PREPARED -> AnkiCommitOutcome.Failed(
            failure?.category ?: "not_applied", dispatched = attemptCount > 0
        )
        ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.AMBIGUOUS ->
            AnkiCommitOutcome.Ambiguous(failure?.category ?: "ambiguous")
    }

    /** Coordinator outcome → study event payload. No truth is re-derived here. */
    private fun ReviewCommitOutcome.toStudyOutcome(resolution: String? = null): AnkiCommitOutcome = when (this) {
        is ReviewCommitOutcome.Committed -> AnkiCommitOutcome.Committed(AnkiCommitOutcome.SOURCE_LEDGER_REPLAY)
        is ReviewCommitOutcome.RetryAllowed -> AnkiCommitOutcome.Failed(
            record.failure?.category ?: "not_applied", dispatched = record.attemptCount > 0)
        is ReviewCommitOutcome.Ambiguous -> AnkiCommitOutcome.Ambiguous(
            record.failure?.category ?: resolution ?: "ambiguous")
        is ReviewCommitOutcome.Conflict -> if (error is AnkiError.CommitLedgerUnavailable)
            AnkiCommitOutcome.PersistenceFailure(error.commitCategory())
        else AnkiCommitOutcome.Ambiguous(error.commitCategory())
    }

    companion object {
        const val DEFAULT_RECONCILE_TIMEOUT_MS: Long = 10_000L
        const val RECONCILE_TIMEOUT: String = "reconcile_timeout"

        /** Durable phase markers, named after the canonical [ReviewCommitPhase] they persist. */
        const val DURABLE_CREATED: String = "CREATED"
        const val DURABLE_INTENT_PERSISTED: String = "INTENT_PERSISTED"
        const val DURABLE_MUTATION_BOUNDARY_ENTERED: String = "MUTATION_BOUNDARY_ENTERED"
        const val DURABLE_FINAL_STATUS_PERSISTED: String = "FINAL_STATUS_PERSISTED"
    }
}
