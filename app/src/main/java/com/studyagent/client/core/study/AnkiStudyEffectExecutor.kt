package com.studyagent.client.core.study

import com.studyagent.client.core.anki.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Performs Anki effects in the caller's owned coroutine and reports them as events. It never writes
 * machine state; the reducer decides what every event means.
 *
 * GATE 11 adds the one write path, [CommitRating], with this fixed order (STEP 18):
 *
 * 1. `ledger.prepare` — NOT_STARTED durable (or the existing record for this commit id);
 * 2. `backend.prepareCommit` — read-only baseline evidence (first attempt only);
 * 3. `ledger.claim` → SUBMITTING/PREPARED (durable, CAS);
 * 4. backend preflight → `ledger.markMutationEntered` callback (durable) immediately before
 *    the real scheduler mutation; adapters without preflight mark before `backend.commitRating`;
 * 5. persist the classified response, then terminal state/LOCAL_RESULT_PERSISTED;
 * 6. emit a success only after that final write is durable. A write failure blocks progression.
 *
 * Without a [ledger] there is no exactly-once guarantee across process death, so commits are
 * refused before dispatch (fail closed). A COMMITTED or AMBIGUOUS ledger record is answered from
 * the ledger — the backend is never called again for it (no replay, no blind resend).
 */
class AnkiStudyEffectExecutor(
    private val registry: AnkiBackendRegistry,
    private val ledger: ReviewCommitLedger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val faults: CommitFaultInjector = NoCommitFaults,
    private val phases: CommitPhaseSink = CommitPhaseSink { _, _ -> },
    /** Upper bound for one read-only reconciliation query; expiry leaves the commit AMBIGUOUS. */
    private val reconcileTimeoutMs: Long = DEFAULT_RECONCILE_TIMEOUT_MS
) {
    init { require(reconcileTimeoutMs > 0) }

    /** Known only while this process lives. A lost durable response never authorizes replay. */
    private val unpersistedResponses = ConcurrentHashMap<ReviewCommitId, CommitRatingResult>()

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
            is AnkiStudyEffect.CommitRating -> commit(effect, emit)
            is AnkiStudyEffect.ReconcileCommit -> reconcile(effect)
            is AnkiStudyEffect.EndReview -> {
                endReview(effect)
                null
            }
            is AnkiStudyEffect.Begin, is AnkiStudyEffect.Next, is AnkiStudyEffect.Hydrate -> read(effect)
        }
    }

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

    // ---------------------------------------------------------------- GATE 11 commit

    private suspend fun commit(
        effect: AnkiStudyEffect.CommitRating,
        emit: suspend (AnkiStudyEvent) -> Unit
    ): AnkiStudyEvent? {
        val request = effect.request
        val commitId = request.commitId
        fun resolved(outcome: AnkiCommitOutcome) = AnkiStudyEvent.RatingCommitResolved(effect.epoch, commitId, outcome)
        fun refused(category: String, safe: Boolean) = resolved(AnkiCommitOutcome.Failed(category, safe, dispatched = false))

        val ledger = this.ledger ?: return resolved(AnkiCommitOutcome.PersistenceFailure("ledger_unavailable"))
        fun storageFault(category: String) = resolved(AnkiCommitOutcome.PersistenceFailure(category))

        faults.on(CommitFaultPoint.BEFORE_LEDGER_CREATE)
        // 1. CommitPrepared — NOT_STARTED durable, or the existing record for this identity.
        // Semantics are frozen here and are not rewritten if the record already exists.
        val backendForFreeze = registry.find(commitId.backendId)
        val record = when (val prepared = ledger.prepare(request, backendForFreeze?.commitSemantics?.enforced(backendForFreeze.id))) {
            is ReviewCommitLedger.PrepareResult.Prepared -> prepared.record
            is ReviewCommitLedger.PrepareResult.Existing -> prepared.record
            is ReviewCommitLedger.PrepareResult.Tombstoned ->
                return resolved(AnkiCommitOutcome.Committed("ledger_tombstone"))
            // The recorded rating is immutable: a different payload for this id is never sent.
            is ReviewCommitLedger.PrepareResult.Conflict -> return refused("commit_payload_conflict", safe = false)
            ReviewCommitLedger.PrepareResult.Full -> return storageFault("ledger_full")
            is ReviewCommitLedger.PrepareResult.StoreFailed -> return storageFault("commit_persistence_failure")
            is ReviewCommitLedger.PrepareResult.Unavailable -> return storageFault("ledger_unavailable")
        }
        // Answer from the ledger whenever the record is not claimable by this effect.
        when (record.state) {
            ReviewCommitState.COMMITTED -> return resolved(AnkiCommitOutcome.Committed("ledger_replay"))
            ReviewCommitState.AMBIGUOUS -> return resolved(AnkiCommitOutcome.Ambiguous(record.failure?.category ?: "ambiguous"))
            ReviewCommitState.SUBMITTING -> return null // in flight in this process; that attempt reports
            ReviewCommitState.FAILED -> if (!effect.retry || !record.safeToRetry) return resolved(record.toOutcome())
            ReviewCommitState.NOT_STARTED -> Unit
        }

        val backend = registry.find(commitId.backendId)
        if (backend == null) {
            val saved = ledger.markRefused(commitId, "backend_missing", safeToRetry = true)
            return if (saved != null) resolved(saved.toOutcome()) else storageFault("commit_persistence_failure")
        }

        // 2. Read-only baseline evidence, captured once and then immutable in the ledger.
        var evidence = record.evidence
        if (evidence == null) {
            val preparation = try {
                backend.prepareCommit(record.toRequest())
            } catch (cancelled: CancellationException) {
                throw cancelled // nothing dispatched; the record stays NOT_STARTED / FAILED-safe
            } catch (_: Exception) {
                CommitPreparation.Refused(AnkiError.Unknown("prepare_threw"), retryable = true)
            }
            when (preparation) {
                is CommitPreparation.Refused -> {
                    val category = preparation.error.commitCategory()
                    val saved = ledger.markRefused(commitId, category, preparation.retryable)
                    return if (saved != null) resolved(saved.toOutcome()) else storageFault("commit_persistence_failure")
                }
                is CommitPreparation.Ready -> evidence = preparation.evidence
            }
        }

        // 3. SUBMITTING — durable before the backend call; only one caller can win this CAS.
        val claimed = when (val claim = ledger.claim(commitId, evidence, allowRetry = effect.retry)) {
            is ReviewCommitLedger.ClaimResult.Claimed -> claim.record
            is ReviewCommitLedger.ClaimResult.InFlight -> return null
            is ReviewCommitLedger.ClaimResult.AlreadyCommitted -> return resolved(AnkiCommitOutcome.Committed("ledger_replay"))
            is ReviewCommitLedger.ClaimResult.NotClaimable -> return resolved(claim.record.toOutcome())
            ReviewCommitLedger.ClaimResult.Missing -> return storageFault("ledger_record_missing")
            is ReviewCommitLedger.ClaimResult.StoreFailed -> return storageFault("commit_persistence_failure")
            is ReviewCommitLedger.ClaimResult.Unavailable -> return storageFault("ledger_unavailable")
        }
        phases.onPhase("PREPARED", claimed.attemptCount)
        emit(AnkiStudyEvent.RatingCommitStarted(effect.epoch, commitId, claimed.attemptCount))
        faults.on(CommitFaultPoint.AFTER_PREPARED)

        // The backend may perform preflight while PREPARED. Its boundary callback persists
        // CALL_ENTERED immediately before the real scheduler API. A failed write prevents it.
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
                        phases.onPhase("CALL_ENTERED", claimed.attemptCount)
                        faults.on(CommitFaultPoint.AFTER_CALL_ENTERED)
                        faults.on(CommitFaultPoint.BEFORE_PROVIDER_CALL)
                    }
                    mutationEntered
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (mutationEntered) ledger.markAmbiguous(commitId, "cancelled_after_dispatch")
                else ledger.markPreparedFailure(commitId)
            }
            throw cancelled
        } catch (fault: CommitFaultException) {
            throw fault
        } catch (_: Exception) {
            if (mutationEntered) CommitRatingResult.Ambiguous(AnkiError.Unknown("commit_threw"))
            else CommitRatingResult.RetryableFailure(AnkiError.Unknown("preflight_threw"))
        }
        if (boundaryFault && !mutationEntered) return storageFault("commit_persistence_failure")
        val result = if (boundaryFault) CommitRatingResult.Ambiguous(AnkiError.Unknown("boundary_called_twice"))
            else backendResult
        if (!mutationEntered) {
            // A result before the callback is a PREPARED-side refusal. A claimed success or
            // unknown result without entering is a backend contract violation: NEVER advance.
            val final = withContext(NonCancellable) {
                when (result) {
                    is CommitRatingResult.RetryableFailure -> ledger.markPreparedFailure(commitId,
                        result.error.commitCategory(), safeToRetry = true)
                    is CommitRatingResult.Rejected -> ledger.markPreparedFailure(commitId,
                        result.error.commitCategory(), safeToRetry = false)
                    is CommitRatingResult.Committed, is CommitRatingResult.Ambiguous ->
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
        phases.onPhase("LOCAL_COMMIT_PERSISTED", final.attemptCount)
        unpersistedResponses.remove(commitId)
        return resolved(final.toOutcome(committedSource = "backend_confirmed"))
    }

    private suspend fun reconcile(effect: AnkiStudyEffect.ReconcileCommit): AnkiStudyEvent {
        val commitId = effect.commitId
        fun done(outcome: AnkiCommitOutcome) = AnkiStudyEvent.RatingCommitReconciled(effect.epoch, commitId, outcome)
        fun storageFault() = done(AnkiCommitOutcome.PersistenceFailure("commit_persistence_failure"))
        val ledger = this.ledger ?: return storageFault()
        var record = ledger.get(commitId) ?: return storageFault()

        // First recover a *known* in-process response without calling the backend. If only a
        // durable RESPONSE_RECEIVED marker survived, finalize that instead. A failed write blocks.
        if (record.state == ReviewCommitState.SUBMITTING) {
            val result = unpersistedResponses[commitId]
            if (result != null && record.phase == CommitAttemptPhase.MUTATION_CALL_ENTERED) {
                record = withContext(NonCancellable) { ledger.markResponseReceived(commitId, result) }
                    ?: return storageFault()
            }
            if (record.phase == CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED) {
                record = withContext(NonCancellable) {
                    if (result != null) ledger.complete(commitId, result)
                    else ledger.finalizeRecordedResponse(commitId)
                } ?: return storageFault()
                unpersistedResponses.remove(commitId)
            } else if (record.phase == CommitAttemptPhase.PREPARED) {
                record = withContext(NonCancellable) { ledger.markPreparedFailure(commitId) }
                    ?: return storageFault()
            } else if (record.phase == CommitAttemptPhase.MUTATION_CALL_ENTERED) {
                record = withContext(NonCancellable) { ledger.markAmbiguous(commitId, "outcome_not_durable") }
                    ?: return storageFault()
            }
        }
        if (record.state != ReviewCommitState.AMBIGUOUS) return done(record.toOutcome(committedSource = "reconciled"))
        val backend = registry.find(commitId.backendId)
            ?: return done(AnkiCommitOutcome.Ambiguous("backend_missing"))
        if (record.card.backendId != backend.id || record.deckRef?.backendId?.let { it != backend.id } == true) {
            return done(AnkiCommitOutcome.Ambiguous("backend_identity_mismatch"))
        }
        // A backend that only observes counters/time cannot turn them into transaction truth.
        val authoritative = if (record.frozenGuarantee != null) record.frozenAuthoritativeReconciliation
            else backend.commitSemantics.supportsAuthoritativeReconciliation
        if (!authoritative) {
            return done(AnkiCommitOutcome.Ambiguous("authoritative_reconciliation_unavailable"))
        }
        val submittedAt = record.submittedAtEpochMs ?: record.createdAtEpochMs
        val windowEnd = maxOf(submittedAt, record.resolvedAtEpochMs ?: clock())
        val result = try {
            // Read-only and bounded: a hung provider query must not hold the session in CHECKING.
            // Expiry is "still unknown", never "not applied" — the user can check again later.
            withTimeoutOrNull(reconcileTimeoutMs) {
                backend.reconcileCommit(ReconcileCommitRequest(
                    commitId, record.card, record.rating, record.evidence, submittedAt, windowEnd
                ))
            } ?: ReconcileCommitResult.StillAmbiguous(RECONCILE_TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled // read-only: nothing to undo, the record stays AMBIGUOUS
        } catch (_: Exception) {
            ReconcileCommitResult.StillAmbiguous("reconcile_threw")
        }
        val updated = withContext(NonCancellable) { ledger.reconcile(commitId, result) }
            ?: return storageFault()
        return done(updated.toOutcome(committedSource = "reconciled"))
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

    private fun ReviewCommitRecord.toOutcome(committedSource: String = "ledger_replay"): AnkiCommitOutcome = when (state) {
        ReviewCommitState.COMMITTED -> AnkiCommitOutcome.Committed(committedSource)
        ReviewCommitState.FAILED -> AnkiCommitOutcome.Failed(
            failure?.category ?: "failed", safeToRetry, dispatched = attemptCount > 0
        )
        // A record still NOT_STARTED/SUBMITTING here was never resolved: never claim success.
        ReviewCommitState.AMBIGUOUS, ReviewCommitState.SUBMITTING, ReviewCommitState.NOT_STARTED ->
            AnkiCommitOutcome.Ambiguous(failure?.category ?: "ambiguous")
    }

    companion object {
        const val DEFAULT_RECONCILE_TIMEOUT_MS: Long = 10_000L
        const val RECONCILE_TIMEOUT: String = "reconcile_timeout"
    }

}
