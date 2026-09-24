package com.studyagent.client.core.study

import com.studyagent.client.core.anki.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Performs Anki effects in the caller's owned coroutine and reports them as events. It never writes
 * machine state; the reducer decides what every event means.
 *
 * GATE 11 adds the one write path, [CommitRating], with this fixed order (STEP 18):
 *
 * 1. `ledger.prepare` — NOT_STARTED durable (or the existing record for this commit id);
 * 2. `backend.prepareCommit` — read-only baseline evidence (first attempt only);
 * 3. `ledger.claim` — compare-and-set to SUBMITTING, durable *before* the backend is called;
 * 4. `backend.commitRating` — the only mutation;
 * 5. `ledger.complete` — the classified outcome, durable;
 * 6. emit [AnkiStudyEvent.RatingCommitResolved] correlated by epoch + commit id.
 *
 * Without a [ledger] there is no exactly-once guarantee across process death, so commits are
 * refused before dispatch (fail closed). A COMMITTED or AMBIGUOUS ledger record is answered from
 * the ledger — the backend is never called again for it (no replay, no blind resend).
 */
class AnkiStudyEffectExecutor(
    private val registry: AnkiBackendRegistry,
    private val ledger: ReviewCommitLedger? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
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
                is AnkiStudyEffect.Begin -> {
                    val result = begin(effect)
                    val prior = if (result is AnkiResult.Success) priorUnresolved(result.value.context.backendId) else 0
                    AnkiStudyEvent.Begun(effect.epoch, result, prior)
                }
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

    private suspend fun begin(effect: AnkiStudyEffect.Begin): AnkiResult<AnkiReviewSession> {
        val request = effect.request
        // Only startup probes candidates. All subsequent reads use the locked logical identity.
        registry.ids.filter(request.preference::accepts).forEach { registry.find(it)?.refreshAvailability() }
        val resolution = AnkiBackendSelector(registry).resolve(request.preference)
        val id = when (resolution) {
            is AnkiBackendSelector.Resolution.Resolved -> resolution.backendId
            is AnkiBackendSelector.Resolution.Unavailable -> return AnkiResult.Failure(resolution.error)
            is AnkiBackendSelector.Resolution.Ambiguous -> return AnkiResult.Failure(AnkiError.InvalidRequest("ambiguous_backend"))
        }
        if (request.deck.backendId != id) return AnkiResult.Failure(AnkiError.InvalidRequest("foreign_deck"))
        val backend = registry.find(id) ?: return AnkiResult.Failure(AnkiError.BackendUnavailable())
        // Validate before binding, even for implementations whose beginReview is less strict.
        when (val decks = backend.getDecks()) {
            is AnkiResult.Failure -> return decks
            is AnkiResult.Success -> if (decks.value.none { it.ref == request.deck }) {
                return AnkiResult.Failure(AnkiError.DeckNotFound(request.deck))
            }
        }
        val context = AnkiSessionContext(
            id, request.deck.collectionKey?.let { AnkiCollectionIdentity(id, it) }, request.deck,
            effect.startedAtMs, backend.capabilities.value, request.studySessionId
        )
        return backend.beginReview(BeginReviewRequest(context))
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

        val ledger = this.ledger ?: return refused("ledger_unavailable", safe = false)

        // 1. CommitPrepared — NOT_STARTED durable, or the existing record for this identity.
        val record = when (val prepared = ledger.prepare(request)) {
            is ReviewCommitLedger.PrepareResult.Prepared -> prepared.record
            is ReviewCommitLedger.PrepareResult.Existing -> prepared.record
            // The recorded rating is immutable: a different payload for this id is never sent.
            is ReviewCommitLedger.PrepareResult.Conflict -> return refused("commit_payload_conflict", safe = false)
            ReviewCommitLedger.PrepareResult.Full -> return refused("ledger_full", safe = false)
            is ReviewCommitLedger.PrepareResult.StoreFailed -> return refused("ledger_write_failed", safe = true)
            is ReviewCommitLedger.PrepareResult.Unavailable -> return refused("ledger_unavailable", safe = false)
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
            ledger.markRefused(commitId, "backend_missing", safeToRetry = true)
            return refused("backend_missing", safe = true)
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
                    ledger.markRefused(commitId, category, preparation.retryable)
                    return refused(category, preparation.retryable)
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
            ReviewCommitLedger.ClaimResult.Missing -> return refused("ledger_record_missing", safe = false)
            // SUBMITTING never became durable, so the backend was not called: safe to retry.
            is ReviewCommitLedger.ClaimResult.StoreFailed -> return refused("ledger_write_failed", safe = true)
            is ReviewCommitLedger.ClaimResult.Unavailable -> return refused("ledger_unavailable", safe = false)
        }
        emit(AnkiStudyEvent.RatingCommitStarted(effect.epoch, commitId, claimed.attemptCount))

        // 4. The mutation. From here on, anything that is not a classified result is AMBIGUOUS.
        val result = try {
            backend.commitRating(claimed.toRequest())
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { ledger.markAmbiguous(commitId, "cancelled_after_dispatch") }
            throw cancelled
        } catch (_: Exception) {
            CommitRatingResult.Ambiguous(AnkiError.Unknown("commit_threw"))
        }

        // 5. Persist the outcome before anyone can act on it.
        val final = withContext(NonCancellable) { ledger.complete(commitId, result) }
            ?: return resolved(AnkiCommitOutcome.Ambiguous("ledger_unavailable_after_dispatch"))
        return resolved(final.toOutcome(committedSource = "backend_confirmed"))
    }

    private suspend fun reconcile(effect: AnkiStudyEffect.ReconcileCommit): AnkiStudyEvent {
        val commitId = effect.commitId
        fun done(outcome: AnkiCommitOutcome) = AnkiStudyEvent.RatingCommitReconciled(effect.epoch, commitId, outcome)
        val ledger = this.ledger ?: return done(AnkiCommitOutcome.Ambiguous("ledger_unavailable"))
        val record = ledger.get(commitId) ?: return done(AnkiCommitOutcome.Ambiguous("ledger_record_missing"))
        // Already decided (for example by a concurrent attempt): report the durable truth.
        if (record.state != ReviewCommitState.AMBIGUOUS) return done(record.toOutcome(committedSource = "reconciled"))
        val backend = registry.find(commitId.backendId)
            ?: return done(AnkiCommitOutcome.Ambiguous("backend_missing"))
        val submittedAt = record.submittedAtEpochMs ?: record.createdAtEpochMs
        val windowEnd = maxOf(submittedAt, record.resolvedAtEpochMs ?: clock())
        val result = try {
            backend.reconcileCommit(ReconcileCommitRequest(
                commitId, record.card, record.rating, record.evidence, submittedAt, windowEnd
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled // read-only: nothing to undo, the record stays AMBIGUOUS
        } catch (_: Exception) {
            ReconcileCommitResult.StillAmbiguous("reconcile_threw")
        }
        val updated = withContext(NonCancellable) { ledger.reconcile(commitId, result) } ?: record
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
}
