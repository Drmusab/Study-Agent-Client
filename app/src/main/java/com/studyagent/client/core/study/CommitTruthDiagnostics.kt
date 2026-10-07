package com.studyagent.client.core.study

import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitRecord
import com.studyagent.client.core.anki.ReviewCommitRecoveryPolicy
import com.studyagent.client.core.anki.ReviewCommitStatus

/**
 * GATE 11B PART V — **source-of-truth diagnostics**.
 *
 * Interaction truth (`StudySessionMachine`), transaction truth (`ReviewCommitLedger`) and scheduler
 * truth (`AnkiBackend`) are separate domains with separate owners. They are synchronised through
 * events and reconciliation, never collapsed into one mutable state object, so they *can* be
 * observed disagreeing for a moment. Divergence is data, not an exception: this file makes it
 * visible instead of silent.
 *
 * Six fields, one per question a support engineer actually asks:
 *
 * | Field | Answers | Owner |
 * |---|---|---|
 * | [CommitTruthSnapshot.studyState] | what is the user doing? | `StudySessionMachine` |
 * | [CommitTruthSnapshot.studyCommitProjection] | what would the screen show? | projection |
 * | [CommitTruthSnapshot.ledgerCommitState] | did Study-Agent durably commit? | `ReviewCommitLedger` |
 * | [CommitTruthSnapshot.attemptPhase] | how far did the attempt get? | ledger (diagnostics only) |
 * | [CommitTruthSnapshot.backendSchedulerAvailability] | can Anki answer scheduler questions? | `AnkiBackend` |
 * | [CommitTruthSnapshot.recoveryAction] | what may happen next? | `ReviewCommitRecoveryPolicy` |
 *
 * Nothing here derives one domain's answer from another domain's state
 * (brief PART II: one question, one owner): [ledgerCommitState] is read from the durable record, never from the study
 * projection, and [backendSchedulerAvailability] is observed from the backend, never inferred from
 * a commit status.
 */
data class CommitTruthSnapshot(
    /** Interaction truth: the phase the machine is in (`SessionPhase` server name). */
    val studyState: String,
    /** Presentation projection of the transaction (`RatingCommitUiState`). Never truth. */
    val studyCommitProjection: String,
    /** Durable transaction truth: the `ReviewCommitStatus` of the record, or `no_record`. */
    val ledgerCommitState: String,
    /** Diagnostics only: how far the latest attempt got, or `no_attempt`. */
    val attemptPhase: String,
    /** Scheduler domain: observed backend availability, never inferred. */
    val backendSchedulerAvailability: String,
    /** What the pure recovery policy decided for the durable record. */
    val recoveryAction: String,
    /** Content-free identity, so a support report can be correlated with the ledger. */
    val commitId: String? = null,
    val turnId: String? = null,
    /** True when the study projection and the durable record disagree right now. */
    val divergent: Boolean = false
) {
    /** Rendering order is the order an engineer reads them: interaction → transaction → scheduler. */
    fun rows(): List<Pair<String, String>> = listOf(
        "Study state (interaction truth)" to studyState,
        "Commit projection (UI)" to studyCommitProjection,
        "Ledger commit state (truth)" to ledgerCommitState,
        "Attempt phase (diagnostics)" to attemptPhase,
        "Backend scheduler availability" to backendSchedulerAvailability,
        "Recovery action" to recoveryAction,
        "Sources agree" to if (divergent) "No — see COMMIT_STATE_PROJECTION_MISMATCH" else "Yes"
    )

    companion object {
        /** Rendered instead of a plausible-looking default when nothing was observed. */
        const val UNKNOWN = "unknown"
        const val NO_RECORD = "no_record"
        const val NO_ATTEMPT = "no_attempt"
        const val NO_RECOVERY = "not_applicable"
    }
}

/**
 * A recorded disagreement between the study session's projection and the durable ledger.
 *
 * The projection is always the loser (brief PART IV: the ledger decides, the machine re-projects): on recovery the ledger decides, the machine
 * re-projects it, and the UI renders the re-projected state.
 */
data class CommitProjectionMismatch(
    val commitId: String,
    val turnId: String?,
    /** What the study session believed (null when it had no projection at all). */
    val studyProjection: String?,
    /** What the durable record says. This is the truth. */
    val ledgerState: String,
    /** Where the durable truth came from: a restore scan, a ledger replay, a reconciliation. */
    val source: String
) {
    companion object {
        const val SOURCE_RESTORE = "restore_scan"
        const val SOURCE_REPLAY = "ledger_replay"
        const val SOURCE_RECONCILE = "reconciliation"
        /** There is only one legal answer to "who wins?", so it is a constant, not a branch. */
        const val LEDGER_WINS = "ledger_overrides_study_projection"
    }
}

/**
 * Pure source-of-truth diagnostics. No I/O, no clock, no scheduler reads — it only compares and
 * names things, so it can be unit-tested and so the reducer that calls it stays pure (brief PART II: reducer purity).
 */
object CommitTruthDiagnostics {

    /**
     * The one divergence event. It exists because a projection that quietly disagrees with the
     * durable record is exactly how a double-submit or a lost rating gets shipped.
     */
    const val EVENT_COMMIT_STATE_PROJECTION_MISMATCH = "COMMIT_STATE_PROJECTION_MISMATCH"

    /** The policy is stateless; one shared instance keeps diagnostics allocation-free. */
    private val recoveryPolicy = ReviewCommitRecoveryPolicy()

    /**
     * The durable record is the only argument that can answer "did Study-Agent commit?".
     * `null` record ⇒ `no_record`, never `COMMITTED`-because-the-UI-looks-saved (brief PART II: UI owns no business truth).
     */
    fun snapshot(
        machine: SessionMachineState,
        record: ReviewCommitRecord? = null,
        backendSchedulerAvailability: String = CommitTruthSnapshot.UNKNOWN
    ): CommitTruthSnapshot {
        val local = machine.anki
        val commit = local?.commit
        return CommitTruthSnapshot(
            studyState = SessionPhase.serverPhaseName(machine.phase),
            studyCommitProjection = projectionName(machine),
            ledgerCommitState = record?.status?.name ?: CommitTruthSnapshot.NO_RECORD,
            attemptPhase = record?.phase?.name ?: CommitTruthSnapshot.NO_ATTEMPT,
            backendSchedulerAvailability = backendSchedulerAvailability,
            recoveryAction = record?.let { recoveryPolicy.classifyStatus(it.status).label }
                ?: CommitTruthSnapshot.NO_RECOVERY,
            commitId = commit?.commitId?.stableKey ?: record?.commitId?.stableKey,
            turnId = commit?.commitId?.turnId?.value ?: record?.turnId?.value,
            divergent = divergence(machine, record) != null
        )
    }

    /** The presentation projection of the current turn's transaction. */
    fun projectionName(machine: SessionMachineState): String =
        machine.anki?.commit?.commitUiState?.let { it::class.simpleName ?: CommitTruthSnapshot.UNKNOWN }
            ?: RatingCommitUiState.AwaitingRating::class.simpleName!!

    /**
     * A live comparison of the study projection against the durable record: non-null while the two
     * domains disagree *right now*.
     *
     * The study projection mirrors the durable status by design, so a difference means durable
     * truth moved without the machine hearing about it (process death, a lost event, a repair) —
     * or the machine was handed a restored record. Either way the ledger wins.
     *
     * This deliberately ignores the reducer's recorded [CommitProjectionMismatch]: that record is
     * history ("the ledger overrode the projection at adoption time"), while this function answers
     * "do they disagree now". Conflating the two would report a divergence forever after a single
     * restore, and would also fire on the harmless instant between a write-lane completion and the
     * reducer processing its outcome event.
     */
    fun divergence(machine: SessionMachineState, record: ReviewCommitRecord?): CommitProjectionMismatch? {
        val commit = machine.anki?.commit ?: return null
        val durable = record?.status ?: return null
        return detectMismatch(commit.status, durable, commit.commitId, CommitProjectionMismatch.SOURCE_REPLAY)
    }

    /**
     * The divergence the reducer *recorded* on this transition, if it recorded one: present in
     * [after] but absent in [before].
     *
     * The machine emits [EVENT_COMMIT_STATE_PROJECTION_MISMATCH] exactly on this edge. Edge
     * detection — not a live comparison — is what keeps a normal commit quiet: at the moment a
     * write-lane effect completes, the reducer has not processed its outcome event yet, and a live
     * comparison would mistake that ordering for a divergence.
     */
    fun newlyRecordedMismatch(
        before: SessionMachineState,
        after: SessionMachineState
    ): CommitProjectionMismatch? {
        if (before.anki?.projectionMismatch != null) return null
        return after.anki?.projectionMismatch
    }

    /**
     * Mismatch for one durable override, used by the reducer when it adopts durable truth
     * ([source] is where that truth came from). Pure, so the reducer stays pure.
     */
    fun detectMismatch(
        projected: ReviewCommitStatus?,
        durable: ReviewCommitStatus?,
        commitId: ReviewCommitId,
        source: String
    ): CommitProjectionMismatch? {
        if (durable == null) return null
        if (projected == null) return null // nothing was projected: there is nothing to contradict
        if (projected == durable) return null
        return CommitProjectionMismatch(
            commitId = commitId.stableKey,
            turnId = commitId.turnId.value,
            studyProjection = projected.name,
            ledgerState = durable.name,
            source = source
        )
    }

    /** Metadata for the diagnostic event. Identifiers and names only, never card content. */
    fun metadata(mismatch: CommitProjectionMismatch): Map<String, String> = mapOf(
        "commit" to mismatch.commitId,
        "turn" to (mismatch.turnId ?: "-"),
        "studyProjection" to (mismatch.studyProjection ?: CommitTruthSnapshot.NO_RECORD),
        "ledgerState" to mismatch.ledgerState,
        "source" to mismatch.source,
        "resolution" to CommitProjectionMismatch.LEDGER_WINS
    )
}
