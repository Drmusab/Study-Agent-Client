package com.studyagent.client.ui.components.anki

import com.studyagent.client.core.anki.AnkiBackendDiagnostics
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRoutingDiagnostics
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendStateLookup
import com.studyagent.client.core.anki.AnkiIdentityAssurance
import com.studyagent.client.core.anki.AnkiIdentityAssurancePolicy
import com.studyagent.client.core.anki.AnkiIdentityCheck
import com.studyagent.client.core.anki.AnkiIdentityObservation
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiTransactionFamily
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionRead
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities

/**
 * GATE 21M — the **read-only** Anki backend diagnostics projection
 * (CHECKPOINT 21M-16 … 21M-19, 21M-22; AUDIT-21M-R2-13/14/15/18).
 *
 * It renders what authoritative services already know and adds no authority of its own:
 *
 * ```text
 * per-backend capability/availability truth  ← AnkiBackendState.diagnostics()   (GATE 21D, tokens only)
 * the four selection roles                   ← AnkiBackendRoutingDiagnostics    (GATE 21, DECISION-21-35)
 * active StudySession owner                  ← AnkiStudyBackendLockTracker      (GATE 21G)
 * collection identity assurance              ← AnkiIdentityAssurancePolicy      (GATE 21C §3.5)
 * unresolved durable transactions            ← each family's own read-only `unresolved()` projection
 * custom-study / filtered-session truth      ← GATE 20 AnkiCustomStudyCapabilities
 * ```
 *
 * ## What this file must never do
 *
 * - **No mutation authority.** There is no write, no status reset, no forced recovery, no retry-all,
 *   no force-commit, no deletion and no cross-backend reassignment anywhere in it. The only actions a
 *   diagnostics surface may offer are the closed set in [AnkiBackendUiAction].
 * - **No free text from a backend.** Only enums and stable tokens are rendered. `AnkiAvailability`
 *   payloads (`detail`, `reason`), provider text, endpoints, credentials, collection keys, card
 *   content and note fields never reach a row — the values below are either enum `name`s, backend
 *   `stableId`s or counts (DECISION-21B-27, INV-21D-19).
 * - **No fabricated continuity.** A collection assurance category is rendered verbatim
 *   (`AUTHORITATIVE_MATCH`, `SESSION_CONTINUITY_VERIFIED`, `IDENTITY_UNVERIFIABLE(nominal|degraded)`,
 *   `AUTHORITATIVE_MISMATCH`, `ForeignBackend`) and is never reworded as "same collection"
 *   (`docs/GATE_21C_IDENTITY_IMPLEMENTATION.md` §21M handoff row).
 * - **No invented section for a projection that does not exist.** A family without a read-only
 *   projection is reported as `NOT_PROJECTED`, and the filtered-session family — GATE 20's lifecycle
 *   is unimplemented and GATE 21L is BLOCKED — is reported as unavailable rather than filled in.
 */

/** The durable transaction families a diagnostics reader may ask about. */
enum class AnkiTransactionFamilyUi(
    /** The durable family this row reports, or `null` when no such family exists in this build. */
    val family: AnkiTransactionFamily?,
    val title: String
) {
    REVIEW_COMMIT(AnkiTransactionFamily.REVIEW_COMMIT, "ReviewCommit"),
    REVIEWER_ACTION(AnkiTransactionFamily.REVIEWER_ACTION, "ReviewerAction"),
    NOTE_MUTATION(AnkiTransactionFamily.NOTE_MUTATION, "NoteMutation"),
    NOTE_CREATION(AnkiTransactionFamily.NOTE_CREATION, "NoteCreation"),

    /**
     * GATE 20/21L: no filtered-session lifecycle exists, so no transaction family exists for one.
     * Present in the vocabulary so the omission is *stated* instead of silently missing — a filtered
     * deck is refused by `CustomStudyPolicy`, never silently substituted (R-20-2).
     */
    FILTERED_SESSION(null, "FilteredSession")
}

/**
 * One family's unresolved records, already reduced to counts by their own status vocabulary.
 *
 * Counts and status tokens only: no record id, no card, no note field, no deck name. Each family
 * keeps **its own** status enum name (`ReviewCommitStatus`, `ReviewerActionStatus`,
 * `NoteMutationStatus`, `NoteCreationStatus`), so `CONFLICT` and `AMBIGUOUS` stay distinct instead of
 * collapsing into one generic "pending" bucket (CHECKPOINT 21M-17, VER-21M-R2-30).
 */
data class AnkiPendingTransactionGroupUi(
    val family: AnkiTransactionFamilyUi,
    /** The backend the records were written against — preserved, never re-derived or reassigned. */
    val backendId: AnkiBackendId?,
    /** Family status token → count, in a stable order. */
    val statusCounts: List<Pair<String, Int>>,
    /** True when the family exposes no read-only projection in this build. */
    val notProjected: Boolean = false,
    /** Why nothing is shown: no projection, or genuinely nothing unresolved. */
    val noteToken: String? = null
) {
    val total: Int get() = statusCounts.sumOf { it.second }
}

/** One rendered diagnostics line. Both sides are tokens, counts or enum names. */
data class AnkiBackendDiagnosticsLine(val label: String, val value: String) {
    fun toPair(): Pair<String, String> = label to value
}

/** Everything the read-only projection needs, gathered by the caller from existing services. */
data class AnkiBackendDiagnosticsInput(
    val states: List<AnkiBackendState>,
    /** `null` when no preference projection was supplied: reported, never defaulted to AUTO. */
    val preference: BackendPreference?,
    /** The existing four-role routing projection, when the composition root supplies one. */
    val routing: AnkiBackendRoutingDiagnostics? = null,
    val sessionLocks: Map<String, AnkiSessionContext> = emptyMap(),
    /** Live collection discriminator per backend, or absent when the backend presents none. */
    val liveCollectionKeys: Map<AnkiBackendId, String?> = emptyMap(),
    val customStudyClaims: Map<AnkiBackendId, AnkiCustomStudyCapabilities> = emptyMap(),
    val pendingTransactions: List<AnkiPendingTransactionGroupUi> = emptyList(),
    /**
     * Whether the unresolved-transaction read actually ran. `false` means "not read yet", which is a
     * different statement from "no projection exists" and must not be rendered as a zero count.
     */
    val pendingTransactionsLoaded: Boolean = false,
    /**
     * Read-context ownership, when an existing projection supplies it (GATE 21F). Empty means "no
     * projection": the section then says so instead of deriving a label from the preference, which
     * would be exactly the mutable-source contamination CHECKPOINT 21M-14 forbids.
     */
    val readContextOwners: List<AnkiReadContextOwnerUi> = emptyList(),
    /** App/build metadata already owned by the diagnostics header; never re-derived here. */
    val reportRevisionToken: String? = null
)

/**
 * One bound read context's owner (GATE 21F). Backend-qualified by identity, never by preference and
 * never by display label alone.
 */
data class AnkiReadContextOwnerUi(
    /** Content-free purpose token supplied by the binding owner (e.g. `library`, `card_browser`). */
    val purposeToken: String,
    val backendId: AnkiBackendId,
    val collectionKeyPresent: Boolean,
    val liveInThisProcess: Boolean
)

/** Build the read-only diagnostics lines. Pure: no I/O, no probing, no writes. */
fun buildAnkiBackendDiagnosticsLines(
    input: AnkiBackendDiagnosticsInput
): List<AnkiBackendDiagnosticsLine> = buildList {
    addAll(preferenceLines(input))
    addAll(routingRoleLines(input.routing))
    addAll(backendLines(input))
    addAll(sessionOwnershipLines(input))
    addAll(readContextLines(input.readContextOwners))
    addAll(pendingTransactionLines(input.pendingTransactions, input.pendingTransactionsLoaded))
    input.reportRevisionToken?.let { add(AnkiBackendDiagnosticsLine("Report revision", it)) }
}

// --------------------------------------------------------------------------- preference + roles

private fun preferenceLines(input: AnkiBackendDiagnosticsInput): List<AnkiBackendDiagnosticsLine> {
    val lines = mutableListOf(
        AnkiBackendDiagnosticsLine(
            "Preferred backend (token)",
            input.preference?.preferenceToken() ?: NOT_PROJECTED
        ),
        AnkiBackendDiagnosticsLine(
            "Configured backends",
            if (input.states.isEmpty()) "none" else input.states.size.toString()
        )
    )
    // A user-selectable kind with no registered instance is stated, so a reader can tell "not
    // configured" from "configured but down" without guessing (R-20-2).
    USER_SELECTABLE_BACKEND_KINDS.forEach { kind ->
        val configured = input.states.count { it.kind == kind }
        lines.add(
            AnkiBackendDiagnosticsLine(
                "Configured ${kind.name}",
                if (configured == 0) "0 (no adapter wired in this build)" else configured.toString()
            )
        )
    }
    return lines
}

/**
 * The four roles, as separate lines (DECISION-21-35, AUDIT-21-15). Rendered from the existing
 * projection verbatim; when the composition root supplies none, the lines say `NOT_PROJECTED`
 * rather than inventing a role value.
 */
private fun routingRoleLines(routing: AnkiBackendRoutingDiagnostics?): List<AnkiBackendDiagnosticsLine> =
    if (routing == null) {
        listOf(
            AnkiBackendDiagnosticsLine("Resolved backend", NOT_PROJECTED),
            AnkiBackendDiagnosticsLine("Locked session backend", NOT_PROJECTED),
            AnkiBackendDiagnosticsLine("Transaction backend", NOT_PROJECTED)
        )
    } else {
        listOf(
            AnkiBackendDiagnosticsLine("Resolved backend", routing.resolved?.stableId ?: NOT_MEASURED),
            AnkiBackendDiagnosticsLine(
                "Resolution reason",
                routing.resolvedReason?.name ?: NOT_MEASURED
            ),
            AnkiBackendDiagnosticsLine(
                "Locked session backend",
                routing.lockedSessionBackend?.stableId ?: NOT_MEASURED
            ),
            AnkiBackendDiagnosticsLine("Locked session count", routing.lockedSessionCount.toString()),
            AnkiBackendDiagnosticsLine(
                "Transaction backend",
                routing.transactionBackend?.stableId ?: NOT_MEASURED
            )
        )
    }

// --------------------------------------------------------------------------- per backend

private fun backendLines(input: AnkiBackendDiagnosticsInput): List<AnkiBackendDiagnosticsLine> {
    if (input.states.isEmpty()) {
        return listOf(
            AnkiBackendDiagnosticsLine(
                "Backend inventory",
                "no configured backend instance"
            )
        )
    }
    val lines = mutableListOf<AnkiBackendDiagnosticsLine>()
    input.states.sortedBy { it.backendId.stableId }.forEach { state ->
        val diagnostics: AnkiBackendDiagnostics = state.diagnostics()
        val id = diagnostics.backendId
        lines += AnkiBackendDiagnosticsLine("Backend", id.stableId)
        lines += AnkiBackendDiagnosticsLine("  kind / locality", "${diagnostics.kind.name} / ${diagnostics.locality.name}")
        lines += AnkiBackendDiagnosticsLine("  availability", diagnostics.statusToken)
        lines += AnkiBackendDiagnosticsLine(
            "  unavailable reason",
            diagnostics.unavailableReason?.token ?: NOT_APPLICABLE
        )
        lines += AnkiBackendDiagnosticsLine(
            "  retryability",
            diagnostics.unavailableReason?.retryability?.name ?: NOT_APPLICABLE
        )
        lines += AnkiBackendDiagnosticsLine("  capability evidence", diagnostics.capabilityEvidence.name)
        lines += AnkiBackendDiagnosticsLine(
            "  collection identity",
            collectionIdentityValue(id, input.liveCollectionKeys)
        )
        // Capability truth on both dimensions, in declaration order: a gap cannot hide as "absent".
        AnkiRequiredCapability.entries.forEach { required ->
            val status = diagnostics.capabilities[required] ?: state.capability(required)
            lines += AnkiBackendDiagnosticsLine(
                "  ${required.name}",
                "${status.implemented.name}/${status.operational.name}"
            )
        }
        val claim = input.customStudyClaims[id]
        lines += AnkiBackendDiagnosticsLine(
            "  CUSTOM_STUDY",
            if (claim == null) NOT_PROJECTED
            else buildCustomStudyStatus(claim, id.displayLabel()).token
        )
    }
    return lines
}

/**
 * Collection identity assurance is reported as *presence* plus the assurance category, never as the
 * key itself: a discriminator is identity evidence, and printing it would put a collection reference
 * into a copyable report (CHECKPOINT 21M-18/22).
 */
private fun collectionIdentityValue(
    backendId: AnkiBackendId,
    liveCollectionKeys: Map<AnkiBackendId, String?>
): String = when {
    backendId !in liveCollectionKeys -> NOT_MEASURED
    liveCollectionKeys[backendId] == null -> "none published (identity unverifiable)"
    else -> "published (value withheld)"
}

// --------------------------------------------------------------------------- session ownership

private fun sessionOwnershipLines(input: AnkiBackendDiagnosticsInput): List<AnkiBackendDiagnosticsLine> {
    val locks = input.sessionLocks
    if (locks.isEmpty()) {
        return listOf(AnkiBackendDiagnosticsLine("Active study session", "none"))
    }
    if (locks.size > 1) {
        // Naming one of several sessions would present a single session's authority as the app's.
        return listOf(
            AnkiBackendDiagnosticsLine("Active study sessions", "${locks.size} (no single owner named)")
        )
    }
    val context = locks.values.single()
    val liveKey = input.liveCollectionKeys[context.backendId]
    val assurance = assuranceToken(
        bound = AnkiIdentityObservation(context.backendId, context.collection?.collectionKey),
        live = AnkiIdentityObservation(context.backendId, liveKey),
        // The lock tracker is process memory: the lock it holds is this process's own live binding.
        boundIsLiveInThisProcess = true
    )
    return listOf(
        AnkiBackendDiagnosticsLine("Active study session", context.backendId.stableId),
        AnkiBackendDiagnosticsLine("  study mode", context.studyMode::class.simpleName ?: "unknown"),
        AnkiBackendDiagnosticsLine("  bound collection key", if (context.collection?.collectionKey != null) "present" else "absent"),
        AnkiBackendDiagnosticsLine("  live collection key", if (liveKey == null) "absent" else "present"),
        AnkiBackendDiagnosticsLine("  collection assurance", assurance),
        AnkiBackendDiagnosticsLine(
            "  frozen review capability",
            if (context.capabilities.review) "SUPPORTED" else "UNSUPPORTED"
        )
    )
}

/**
 * The GATE 21C category tokens, verbatim. `ForeignBackend` is a typed outcome, not a fifth category,
 * and a `null` live observation for a backend that is not registered is reported as such instead of
 * being compared (absent is not unavailable).
 */
fun assuranceToken(
    bound: AnkiIdentityObservation,
    live: AnkiIdentityObservation,
    boundIsLiveInThisProcess: Boolean
): String {
    val check: AnkiIdentityCheck =
        AnkiIdentityAssurancePolicy.check(bound, live, boundIsLiveInThisProcess)
    return when (check) {
        is AnkiIdentityCheck.ForeignBackend -> "ForeignBackend"
        is AnkiIdentityCheck.SameBackend -> when (val assurance = check.assurance) {
            is AnkiIdentityAssurance.AuthoritativeMatch -> "AUTHORITATIVE_MATCH"
            AnkiIdentityAssurance.SessionContinuityVerified -> "SESSION_CONTINUITY_VERIFIED"
            is AnkiIdentityAssurance.IdentityUnverifiable ->
                "IDENTITY_UNVERIFIABLE(${assurance.qualifier.name.lowercase()})"
            is AnkiIdentityAssurance.AuthoritativeMismatch -> "AUTHORITATIVE_MISMATCH"
        }
    }
}

// --------------------------------------------------------------------------- read contexts

private fun readContextLines(owners: List<AnkiReadContextOwnerUi>): List<AnkiBackendDiagnosticsLine> =
    if (owners.isEmpty()) {
        listOf(
            AnkiBackendDiagnosticsLine(
                "Read-context owners",
                "$NOT_PROJECTED (no read-context projection is wired into this surface)"
            )
        )
    } else {
        owners.sortedBy { it.purposeToken }.map { owner ->
            AnkiBackendDiagnosticsLine(
                "Read context ${owner.purposeToken}",
                "${owner.backendId.stableId} collection=${if (owner.collectionKeyPresent) "present" else "absent"}" +
                    " live=${owner.liveInThisProcess}"
            )
        }
    }

// --------------------------------------------------------------------------- transactions

private fun pendingTransactionLines(
    groups: List<AnkiPendingTransactionGroupUi>,
    loaded: Boolean
): List<AnkiBackendDiagnosticsLine> {
    val lines = mutableListOf<AnkiBackendDiagnosticsLine>()
    // Every family is reported, including the ones with nothing to show, so an empty section cannot
    // be mistaken for a section that was never read.
    AnkiTransactionFamilyUi.entries.forEach { family ->
        val familyGroups = groups.filter { it.family == family }
        when {
            family == AnkiTransactionFamilyUi.FILTERED_SESSION -> lines += AnkiBackendDiagnosticsLine(
                "Unresolved ${family.title}",
                "$NOT_APPLICABLE (no filtered-session lifecycle exists in this build; GATE 21L BLOCKED)"
            )

            familyGroups.isEmpty() -> lines += AnkiBackendDiagnosticsLine(
                "Unresolved ${family.title}",
                if (loaded) "0"
                else "$NOT_PROJECTED (no read-only projection supplied to this surface)"
            )

            else -> familyGroups
                .sortedBy { it.backendId?.stableId ?: "" }
                .forEach { group ->
                    lines += when {
                        // No owner is claimed for a family this surface could not read: inventing one
                        // would be worse than saying the projection is absent.
                        group.notProjected -> AnkiBackendDiagnosticsLine(
                            "Unresolved ${family.title}",
                            "$NOT_PROJECTED (${group.noteToken ?: "no_read_only_projection"})"
                        )

                        group.total == 0 -> AnkiBackendDiagnosticsLine(
                            "Unresolved ${family.title} @ ${group.backendId?.stableId ?: "unknown_owner"}",
                            "0"
                        )

                        else -> AnkiBackendDiagnosticsLine(
                            "Unresolved ${family.title} @ ${group.backendId?.stableId ?: "unknown_owner"}",
                            group.statusCounts.joinToString(", ") { (status, count) -> "$status=$count" }
                        )
                    }
                }
        }
    }
    // No control is offered for any of the above: an unresolved record keeps its own backend and its
    // own status, and only its family's coordinator may move it (AUDIT-21M-R2-14).
    return lines
}

/**
 * GATE 21M — map the read-only transaction projection onto presentation groups (CHECKPOINT 21M-17).
 *
 * Pure and total: every family in the vocabulary is accounted for. A family that was read and holds
 * nothing becomes a zero group; a family whose read failed or that has no projection becomes a
 * `notProjected` group with the reason token, because a troubleshooting surface must never turn
 * "could not read it" into "nothing is unresolved".
 */
fun pendingTransactionGroups(
    read: AnkiUnresolvedTransactionRead
): List<AnkiPendingTransactionGroupUi> {
    if (!read.loaded) return emptyList()
    val groups = mutableListOf<AnkiPendingTransactionGroupUi>()
    AnkiTransactionFamilyUi.entries.forEach { familyUi ->
        val family = familyUi.family ?: return@forEach
        when {
            family in read.unavailableFamilies ->
                groups += notProjectedTransactionGroup(familyUi, noteToken = "read_unavailable")

            family in read.notProjectedFamilies -> groups += notProjectedTransactionGroup(familyUi)

            else -> {
                // A family that was read and holds nothing produces no group at all: the renderer
                // states the zero for the family, and no owner is invented for it.
                groups += groupPendingTransactions(
                    family = familyUi,
                    records = read.facts.filter { it.family == family },
                    backendOf = { it.backendId },
                    statusTokenOf = { it.statusToken }
                )
            }
        }
    }
    return groups
}

/**
 * Group one family's unresolved records by the backend identity **persisted in each record**.
 *
 * A helper rather than a policy: grouping by anything else (the current preference, a resolved
 * backend, whichever backend is ready) is the cross-backend replay GATE 21 prohibits
 * (DECISION-21-15, INV-21-03/04). The caller supplies the record's owner and status tokens; this
 * function only counts them and never inspects a card, a note or a collection key value.
 */
fun <T> groupPendingTransactions(
    family: AnkiTransactionFamilyUi,
    records: Collection<T>,
    backendOf: (T) -> AnkiBackendId?,
    statusTokenOf: (T) -> String
): List<AnkiPendingTransactionGroupUi> =
    records.groupBy { backendOf(it) }
        .entries
        .sortedBy { entry -> entry.key?.stableId ?: "" }
        .map { (backendId, familyRecords) ->
            AnkiPendingTransactionGroupUi(
                family = family,
                backendId = backendId,
                statusCounts = familyRecords.groupingBy { statusTokenOf(it) }
                    .eachCount()
                    .entries
                    .sortedBy { it.key }
                    .map { it.key to it.value }
            )
        }

/**
 * A family whose read-only projection could not be supplied. Reported, never silently dropped, and
 * never filled with a guessed count.
 */
fun notProjectedTransactionGroup(
    family: AnkiTransactionFamilyUi,
    noteToken: String = "no_read_only_projection"
): AnkiPendingTransactionGroupUi = AnkiPendingTransactionGroupUi(
    family = family,
    backendId = null,
    statusCounts = emptyList(),
    notProjected = true,
    noteToken = noteToken
)

/** Lookup helper for callers that hold a registry state lookup instead of a state list. */
fun AnkiBackendStateLookup.diagnosticsOrNull(): AnkiBackendDiagnostics? = when (this) {
    is AnkiBackendStateLookup.Registered -> state.diagnostics()
    is AnkiBackendStateLookup.NotRegistered -> null
}

private const val NOT_MEASURED = "NOT_MEASURED"
private const val NOT_PROJECTED = "NOT_PROJECTED"
private const val NOT_APPLICABLE = "NOT_APPLICABLE"
