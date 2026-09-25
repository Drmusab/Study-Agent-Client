package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend.CommitStep
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * GATE 11 — seeded commit chaos (§430–§440).
 *
 * Real reducer + real executor + real ledger + fake backend. Each seed randomizes backend outcomes
 * (committed / retryable / ambiguous-applied / ambiguous-not-applied), duplicate effect delivery,
 * injected process death at every [CommitFaultPoint] followed by a ledger restart and redelivery of
 * the original effect, reconciliation and explicit retry. Two backend identities are exercised: the
 * AnkiDroid identity (no authoritative reconciliation) and a reconcilable one. The fake backend has
 * no memory: a repeated id that already had an effect applies again, as a real provider would.
 *
 * Invariants after every step:
 *  I1  no logical commit id has more than one physical scheduler effect;
 *  I2  the session advanced exactly once per durable COMMITTED record, never on AMBIGUOUS/FAILED;
 *  I3  a delivery never makes a physical call for an id that is AMBIGUOUS or COMMITTED at that
 *      moment (AMBIGUOUS may only leave via reconciliation; a later explicit retry after an
 *      authoritative NOT_APPLIED is legal and still bounded by I1).
 *
 * A failure prints the seed and the action scroll; rerun one seed with -Dgate11.chaos.seed=N.
 */
class ReviewCommitChaosTest {

    private companion object {
        /**
         * The fake re-applies any repeated id that already had an effect, so only the client ledger
         * can prevent a double rating. A backend-side dedup table would otherwise mask client bugs.
         */
        val NO_MEMORY = CommitGuaranteeLevel.LOCAL_DEDUP_ONLY
    }

    private class RandomCrash(private val random: Random, private val rate: Double) : CommitFaultInjector {
        var armed: CommitFaultPoint? = null
        var crashes = 0
        fun arm() {
            armed = if (random.nextDouble() < rate) CommitFaultPoint.entries.random(random) else null
        }
        override suspend fun on(point: CommitFaultPoint) {
            if (point == armed) {
                armed = null
                crashes += 1
                throw CommitFaultException(point)
            }
        }
    }

    private fun steps(random: Random): List<CommitStep> = List(24) {
        when (random.nextInt(10)) {
            0, 1 -> CommitStep(CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("chaos_busy")))
            2 -> CommitStep(CommitRatingResult.Ambiguous(AnkiError.Unknown("chaos_ack_lost")), appliedWhenAmbiguous = true)
            3 -> CommitStep(CommitRatingResult.Ambiguous(AnkiError.Unknown("chaos_timeout")), appliedWhenAmbiguous = false)
            else -> CommitStep(CommitRatingResult.Committed())
        }
    }

    @Test fun `seeded commit chaos never applies a rating twice nor advances without COMMITTED`() = runTest {
        val only = System.getProperty("gate11.chaos.seed")?.toLongOrNull()
        val seeds = only?.let { listOf(it) } ?: (1L..150L).toList()
        var totalCrashes = 0
        var totalAmbiguous = 0
        for (seed in seeds) {
            for (reconcilable in listOf(false, true)) {
                val (crashes, ambiguous) = runSeed(seed, reconcilable)
                totalCrashes += crashes
                totalAmbiguous += ambiguous
            }
        }
        // The run is only evidence if the dangerous windows were actually visited.
        if (only == null) {
            check(totalCrashes > 100) { "chaos visited too few crash windows: $totalCrashes" }
            check(totalAmbiguous > 50) { "chaos visited too few ambiguous outcomes: $totalAmbiguous" }
        }
    }

    private suspend fun runSeed(seed: Long, reconcilable: Boolean): Pair<Int, Int> {
        val random = Random(seed * 31 + if (reconcilable) 1 else 0)
        val crash = RandomCrash(random, rate = 0.3)
        val cards = List(8) { AnkiCommitHarness.card("C$it",
            if (reconcilable) AnkiCommitHarness.RECONCILABLE_BACKEND else AnkiCommitHarness.BACKEND) }
        val h = if (reconcilable) {
            AnkiCommitHarness(backendId = AnkiCommitHarness.RECONCILABLE_BACKEND, mode = AnkiBackendMode.PC_AGENT,
                cards = cards, commitSteps = steps(random), faults = crash, guarantee = NO_MEMORY)
        } else {
            AnkiCommitHarness(cards = cards, commitSteps = steps(random), faults = crash, guarantee = NO_MEMORY)
        }
        val scroll = mutableListOf<String>()
        var ambiguousSeen = 0

        suspend fun check(where: String) {
            val problems = mutableListOf<String>()
            val records = h.ledger.snapshot()
            h.fake.recordedCommits().forEach { rc ->
                if (rc.mutationCount > 1) problems += "I1 ${rc.request.commitId} applied ${rc.mutationCount}x"
            }
            val committed = records.count { it.state == ReviewCommitState.COMMITTED }
            val reviewed = h.state.session?.totalReviewedInSession ?: 0
            if (reviewed != committed) problems += "I2 reviewed=$reviewed committed=$committed"
            if (problems.isNotEmpty()) {
                fail("seed=$seed reconcilable=$reconcilable at $where\n" + problems.joinToString("\n") +
                    "\n--- scroll ---\n" + scroll.takeLast(40).joinToString("\n"))
            }
        }

        /** Runs one effect; an injected crash restarts the ledger and redelivers the same effect. */
        suspend fun execute(effect: AnkiStudyEffect) {
            if (effect is AnkiStudyEffect.CommitRating) {
                val id = effect.request.commitId.toString()
                val physicalBefore = h.fake.physicalCommitCalls
                val stateNow = h.ledger.get(effect.request.commitId)?.state
                val wasTerminal = stateNow == ReviewCommitState.AMBIGUOUS || stateNow == ReviewCommitState.COMMITTED
                crash.arm()
                try {
                    h.run(effect)
                } catch (fault: CommitFaultException) {
                    scroll += "  crash@${fault.point} → restart + redeliver"
                    h.restartLedger()
                    h.run(effect) // redelivery after restart must be answered from the ledger
                }
                if (wasTerminal && h.fake.physicalCommitCalls != physicalBefore) {
                    fail("seed=$seed I3 physical call for $stateNow $id\n" + scroll.joinToString("\n"))
                }
            } else {
                h.run(effect)
            }
        }

        h.loadToRating()
        var stuck = 0
        for (step in 0 until 120) {
            if (h.state.phase == SessionPhase.Finished || h.state.anki == null) break
            val phase = h.state.phase
            val commit = h.commit
            val action = when {
                h.pending.isNotEmpty() -> {
                    val effect = h.pending.removeFirst()
                    if (effect is AnkiStudyEffect.CommitRating && random.nextDouble() < 0.3) {
                        h.pending.addFirst(effect) // duplicate delivery of the same effect
                        "dup ${effect::class.simpleName}"
                    } else {
                        execute(effect)
                        "run ${effect::class.simpleName}"
                    }
                }
                phase == SessionPhase.WaitingForRating ->
                    "rate ${Rating.entries.random(random)}".also { h.rate(Rating.valueOf(it.removePrefix("rate "))) }
                phase == SessionPhase.ReconciliationRequired && commit != null -> {
                    ambiguousSeen += 1
                    if (!reconcilable && stuck++ > 1) break // AnkiDroid: unknown stays unknown; user ends
                    h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commit.commitId)); "reconcile"
                }
                phase == SessionPhase.RatingCommitFailed && commit != null && commit.safeToRetry -> {
                    h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commit.commitId)); "retry"
                }
                phase == SessionPhase.RatingCommitFailed -> break // not safe to retry: user must act
                else -> { if (stuck++ > 3) break; "idle $phase" }
            }
            scroll += "step=$step phase=$phase $action"
            check("step=$step $action")
        }
        check("end")
        return crash.crashes to ambiguousSeen
    }
}
