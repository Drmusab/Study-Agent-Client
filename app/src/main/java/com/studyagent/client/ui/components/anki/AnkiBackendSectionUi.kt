package com.studyagent.client.ui.components.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiCapabilityReadiness
import com.studyagent.client.core.anki.AnkiImplementedSupport
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendPreferenceInvalidIssue
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.core.anki.kind
import com.studyagent.client.core.anki.statusCode

/**
 * GATE 21M — the Settings **Anki backend** section: instance-exact selection, capability-aware
 * presentation and honest availability (CHECKPOINT 21M-02 … 21M-15, 21M-25).
 *
 * Pure Kotlin on purpose, exactly like [AnkiBackendUiModels] and GATE 20's `CustomStudyModels`: the
 * rules a reviewer must be able to assert on — *what may be picked, what a status may claim, what an
 * absent adapter may say* — must not need Compose to be tested.
 *
 * ## What this model renders, and from where (no second source of truth)
 *
 * ```text
 * configured instances + their availability/capability truth  ← AnkiBackendStatusCenter.states
 *                                                                (which reads AnkiBackendRegistry)
 * implemented / operational per capability                     ← AnkiBackendState.capability(...)
 * the user's preference for NEW unbound work                   ← BackendPreferenceRepository
 * what AUTO would pick right now (pre-binding preview only)     ← AnkiBackendResolver2 (pure)
 * the immutable owner of an active StudySession                ← AnkiStudyBackendLockTracker
 * custom-study / filtered-session truth                        ← GATE 20 AnkiCustomStudyCapabilities
 * ```
 *
 * Nothing here probes, refreshes, resolves for a bound context, or writes. It is a projection.
 *
 * ## The three boundaries this file exists to keep visible
 *
 * 1. **R-20-2 (PC Anki is not implemented).** An operational, selectable row exists *only* for an
 *    instance the registry actually holds. A user-selectable kind with no registered instance is
 *    rendered as an informational, non-selectable row that says the support is not implemented in
 *    this build — never as a pickable backend, and never with an invented instance identity. The
 *    exclusion is derived from the registry (through [configuredKinds]), not hard-coded per kind, so
 *    wiring a real, audited PC adapter later activates the row without editing a domain model.
 * 2. **Capability ≠ readiness** (BACKEND_SELECTION_POLICY §2.1). `SUPPORTED` + `NOT_READY` and
 *    `UNSUPPORTED` are different rows with different copy: a backend can implement note editing and
 *    be disconnected, and a backend with no wired adapter cannot claim the operation at all.
 * 3. **Preference ≠ ownership.** The selected row is the *preference*; the active-session row is the
 *    *lock*. `Preferred: Automatic` next to `Active session: AnkiDroid` is a valid, truthful state
 *    (INV-21-08, DECISION-21-32/36).
 */

/**
 * One display heading and the locked capability rows rendered under it.
 *
 * Deliberately **not** an enum: AUDIT-21D-07 forbids any parallel capability vocabulary, and a group
 * enum whose members stood for capabilities would be exactly that. `AnkiRequiredCapability` stays the
 * one capability vocabulary; a group is a heading plus rows, used only for reading order. It is never
 * a policy input — resolution reads requirements, not headings.
 */
data class AnkiBackendCapabilityGroupUi(
    val title: String,
    val capabilities: List<AnkiBackendCapabilityRowUi>
)

/** Reading order of the display headings. Stable; carries no priority for resolution. */
val capabilityGroupOrder: List<String> = listOf(
    "Review",
    "Library",
    "Browser",
    "Details",
    "Reviewer actions",
    "Note editing",
    "Note creation",
    "Media"
)

/** Display heading of one requirement. Total, so a new capability cannot silently disappear. */
val AnkiRequiredCapability.uiGroupTitle: String
    get() = when (this) {
        AnkiRequiredCapability.REVIEW,
        AnkiRequiredCapability.SCHEDULED_REVIEW -> "Review"

        AnkiRequiredCapability.DECKS,
        AnkiRequiredCapability.DECK_COUNTS -> "Library"

        AnkiRequiredCapability.CARD_BROWSER -> "Browser"

        AnkiRequiredCapability.CARD_DETAILS,
        AnkiRequiredCapability.RENDERED_CARDS -> "Details"

        AnkiRequiredCapability.FLAGS,
        AnkiRequiredCapability.BURY,
        AnkiRequiredCapability.SUSPEND_CARDS,
        AnkiRequiredCapability.CHANGE_CARD_DECK -> "Reviewer actions"

        AnkiRequiredCapability.EDIT_NOTE_FIELDS,
        AnkiRequiredCapability.EDIT_NOTE_TAGS -> "Note editing"

        AnkiRequiredCapability.NOTE_MODEL_LISTING,
        AnkiRequiredCapability.NOTE_MODEL_SCHEMA,
        AnkiRequiredCapability.NOTE_TEMPLATE_LISTING,
        AnkiRequiredCapability.CREATE_NOTE -> "Note creation"

        AnkiRequiredCapability.MEDIA_READ,
        AnkiRequiredCapability.STORE_MEDIA -> "Media"
    }

/** Group rendered rows under their headings, in [capabilityGroupOrder]. Empty headings are dropped. */
fun groupCapabilities(
    rows: List<AnkiBackendCapabilityRowUi>
): List<AnkiBackendCapabilityGroupUi> = capabilityGroupOrder.mapNotNull { title ->
    val inGroup = rows.filter { it.capability.uiGroupTitle == title }
    if (inGroup.isEmpty()) null else AnkiBackendCapabilityGroupUi(title, inGroup)
}

/** Short human title of one requirement. The requirement token itself stays in [AnkiBackendCapabilityRowUi.capability]. */
val AnkiRequiredCapability.displayTitle: String
    get() = when (this) {
        AnkiRequiredCapability.DECKS -> "Deck listing"
        AnkiRequiredCapability.DECK_COUNTS -> "Verified due counts"
        AnkiRequiredCapability.SCHEDULED_REVIEW -> "Scheduled review (read-only)"
        AnkiRequiredCapability.REVIEW -> "Review loop (answer + commit)"
        AnkiRequiredCapability.RENDERED_CARDS -> "Rendered card content"
        AnkiRequiredCapability.CARD_BROWSER -> "Card browser"
        AnkiRequiredCapability.CARD_DETAILS -> "Card / note details"
        AnkiRequiredCapability.FLAGS -> "Flag"
        AnkiRequiredCapability.BURY -> "Bury"
        AnkiRequiredCapability.SUSPEND_CARDS -> "Suspend"
        AnkiRequiredCapability.EDIT_NOTE_FIELDS -> "Edit note fields"
        AnkiRequiredCapability.EDIT_NOTE_TAGS -> "Edit note tags"
        AnkiRequiredCapability.CHANGE_CARD_DECK -> "Move card to another deck"
        AnkiRequiredCapability.NOTE_MODEL_LISTING -> "Note type listing"
        AnkiRequiredCapability.NOTE_MODEL_SCHEMA -> "Note type schema"
        AnkiRequiredCapability.NOTE_TEMPLATE_LISTING -> "Card template listing"
        AnkiRequiredCapability.MEDIA_READ -> "Media read"
        AnkiRequiredCapability.STORE_MEDIA -> "Media store"
        AnkiRequiredCapability.CREATE_NOTE -> "Create note"
    }

/**
 * One capability of one backend, on the two dimensions the registry can honestly report
 * (GATE 21D `AnkiCapabilityStatus`). The enum values are carried through verbatim so a test — or a
 * support report — can assert on the contract vocabulary instead of on copy.
 */
data class AnkiBackendCapabilityRowUi(
    val capability: AnkiRequiredCapability,
    val title: String,
    val implemented: AnkiImplementedSupport,
    val operational: AnkiCapabilityReadiness,
    /** "Supported" / "Not implemented" / "Unknown" — the *implementation* dimension. */
    val implementedLabel: String,
    /** "Available now" / "Not available now" / "Not checked yet" — the *readiness* dimension. */
    val currentStateLabel: String
) {
    /** True only for positive knowledge on both dimensions ([AnkiCapabilityReadiness.READY]). */
    val usableNow: Boolean get() = operational == AnkiCapabilityReadiness.READY

    /** Stable diagnostics token, e.g. `EDIT_NOTE_FIELDS=SUPPORTED/NOT_READY`. */
    val statusToken: String get() = "${capability.name}=${implemented.name}/${operational.name}"
}

fun AnkiImplementedSupport.displayLabel(): String = when (this) {
    AnkiImplementedSupport.SUPPORTED -> "Supported"
    AnkiImplementedSupport.UNSUPPORTED -> "Not implemented"
    AnkiImplementedSupport.UNKNOWN -> "Unknown"
}

fun AnkiCapabilityReadiness.displayLabel(): String = when (this) {
    AnkiCapabilityReadiness.READY -> "Available now"
    AnkiCapabilityReadiness.NOT_READY -> "Not available now"
    AnkiCapabilityReadiness.UNKNOWN -> "Not checked yet"
}

/** GATE 20 truth about custom study / filtered sessions, as one non-interactive disclosure. */
data class AnkiCustomStudyStatusUi(
    /** Stable token for diagnostics; never parsed by the UI. */
    val token: String,
    val headline: String,
    val explanation: String,
    val supportsAnyCustomStudy: Boolean,
    val canCreateFilteredSession: Boolean,
    val canReviewFilteredDeck: Boolean
) {
    companion object {
        const val NOT_IMPLEMENTED = "CUSTOM_STUDY_NOT_IMPLEMENTED"
        const val LIFECYCLE_NOT_IMPLEMENTED = "CUSTOM_STUDY_LIFECYCLE_NOT_IMPLEMENTED"
    }
}

/**
 * Build the custom-study disclosure from GATE 20's own capability claim.
 *
 * GATE 21L is BLOCKED and no wired backend can create, rebuild or end a filtered session, so this
 * never produces a control: it produces one truthful line (CHECKPOINT 21M-06). A backend that does
 * claim part of the vocabulary still gets "the filtered-session lifecycle is not implemented in this
 * build", because a claim by an adapter is not an implemented Study-Agent lifecycle (21L).
 */
fun buildCustomStudyStatus(
    capabilities: AnkiCustomStudyCapabilities,
    backendTitle: String
): AnkiCustomStudyStatusUi =
    if (!capabilities.supportsAnyCustomStudy) {
        AnkiCustomStudyStatusUi(
            token = AnkiCustomStudyStatusUi.NOT_IMPLEMENTED,
            headline = "Custom Study: not implemented",
            explanation = "$backendTitle offers no custom-study or filtered-session operation through " +
                "its audited public contract. Ordinary deck browsing is not Custom Study.",
            supportsAnyCustomStudy = false,
            canCreateFilteredSession = capabilities.canCreateFilteredSession,
            canReviewFilteredDeck = capabilities.canReviewFilteredDeck
        )
    } else {
        AnkiCustomStudyStatusUi(
            token = AnkiCustomStudyStatusUi.LIFECYCLE_NOT_IMPLEMENTED,
            headline = "Custom Study: lifecycle not implemented",
            explanation = "$backendTitle claims part of the custom-study vocabulary, but the " +
                "filtered-session lifecycle (create, rebuild, end) is not implemented in this build, " +
                "so no Custom Study session can be started, rebuilt or ended here.",
            supportsAnyCustomStudy = true,
            canCreateFilteredSession = capabilities.canCreateFilteredSession,
            canReviewFilteredDeck = capabilities.canReviewFilteredDeck
        )
    }

/** What one row of the selector is. Every row carries a stable Compose/test key. */
sealed interface AnkiBackendRowUi {
    /** Stable identity of the row: `AUTO`, one `AnkiBackendId.stableId`, or a kind token. */
    val key: String
    val title: String
    /** True only for a row the user may actually choose. Informational rows are always false. */
    val selectable: Boolean
    val selected: Boolean
    /** Short status; never the only indicator (colour and icon are added by the Compose layer). */
    val statusLabel: String
    /** Stable status token for diagnostics/tests. */
    val statusToken: String
    /** Longer explanation, when there is something honest to add. */
    val explanation: String?
}

/** The AUTO policy row. AUTO is a policy, not a backend, and never merges two collections. */
data class AnkiBackendAutoRowUi(
    override val key: String = KEY,
    override val title: String = "Automatic (AUTO)",
    override val selectable: Boolean = true,
    override val selected: Boolean,
    override val statusLabel: String,
    override val statusToken: String,
    override val explanation: String? = AUTO_EXPLANATION
) : AnkiBackendRowUi {
    companion object {
        const val KEY = "preference:auto"
        const val AUTO_EXPLANATION =
            "Selects one eligible implemented Anki backend when a new operation begins. It never " +
                "merges two collections and never moves an active session."
    }
}

/** One *configured instance*, keyed by its exact [AnkiBackendId] — never by name or kind. */
data class AnkiBackendInstanceRowUi(
    val backendId: AnkiBackendId,
    override val key: String = backendId.stableId,
    override val title: String,
    override val selectable: Boolean,
    override val selected: Boolean,
    override val statusLabel: String,
    override val statusToken: String,
    override val explanation: String?,
    /** Verbatim identity token. Never translated, never used as a label substitute. */
    val identityToken: String = backendId.stableId,
    val kindToken: String,
    val localityToken: String,
    /** `AnkiAvailability.statusCode` vocabulary is kept; this is the same value as [statusToken]. */
    val readyNow: Boolean,
    /** A test double is rendered but is never an operational choice (INV-21D-01, R-20-2 fixtures). */
    val testDouble: Boolean,
    val capabilityEvidenceToken: String,
    val unavailableReasonToken: String?,
    val retryabilityToken: String?,
    val capabilities: List<AnkiBackendCapabilityRowUi>,
    val customStudy: AnkiCustomStudyStatusUi?
) : AnkiBackendRowUi {
    /** The same rows under their display headings, in a stable reading order. */
    val capabilityGroups: List<AnkiBackendCapabilityGroupUi> get() = groupCapabilities(capabilities)
}

/**
 * A user-selectable kind this build wires no adapter for: informational and disabled (R-20-2).
 *
 * This is the row that must never become selectable, and must never claim readiness because a PC
 * *agent* (AI / STT / TTS) is connected: a PC agent is not a PC Anki backend.
 */
data class AnkiBackendUnimplementedKindRowUi(
    val kind: AnkiBackendKind,
    override val key: String = "kind:${kind.name}",
    override val title: String,
    override val selectable: Boolean = false,
    override val selected: Boolean = false,
    override val statusLabel: String = "Not implemented in this build",
    override val statusToken: String = TOKEN,
    override val explanation: String
) : AnkiBackendRowUi {
    companion object {
        const val TOKEN = "BACKEND_KIND_NOT_IMPLEMENTED"
    }
}

/**
 * The persisted preference names an exact instance the registry no longer holds (CHECKPOINT 21M-12).
 *
 * The identity is preserved verbatim: it is never rewritten to AUTO, never guessed by kind, and never
 * used to reassign a historical transaction owner. The user corrects it explicitly.
 */
data class AnkiBackendUnresolvedRowUi(
    val backendId: AnkiBackendId?,
    override val key: String = backendId?.let { "unresolved:${it.stableId}" } ?: "unresolved:preference",
    override val title: String,
    override val selectable: Boolean = false,
    override val selected: Boolean = true,
    override val statusLabel: String,
    override val statusToken: String,
    override val explanation: String,
    /** The exact unresolved preference, so the UI can show what is stored without guessing. */
    val storedPreferenceToken: String
) : AnkiBackendRowUi

/** The immutable owner of the active StudySession — never derived from the preference. */
sealed interface ActiveStudySessionUi {
    val headline: String
    val token: String

    data object None : ActiveStudySessionUi {
        override val headline: String get() = "No active study session"
        override val token: String get() = "NO_ACTIVE_SESSION"
    }

    /** Exactly one session holds a lock, so exactly one owner may be named. */
    data class One(
        val backendId: AnkiBackendId,
        val studySessionId: String,
        val backendTitle: String,
        val collectionKeyPresent: Boolean
    ) : ActiveStudySessionUi {
        override val headline: String get() = "Active study session: $backendTitle"
        override val token: String get() = "ONE_ACTIVE_SESSION"
    }

    /**
     * Several sessions hold locks. Naming one would present a single session's authority as "the"
     * app authority (INV-21-05), so the count is reported and no backend is named.
     */
    data class Several(val count: Int) : ActiveStudySessionUi {
        override val headline: String get() = "$count active study sessions hold a backend lock"
        override val token: String get() = "SEVERAL_ACTIVE_SESSIONS"
    }
}

/** Preference write feedback. `Saved` requires the authoritative repository to agree. */
sealed interface AnkiBackendSaveFeedbackUi {
    val token: String

    data object Idle : AnkiBackendSaveFeedbackUi {
        override val token: String get() = "IDLE"
    }

    data class Saving(val targetToken: String) : AnkiBackendSaveFeedbackUi {
        override val token: String get() = "SAVING"
    }

    data class Saved(val targetToken: String) : AnkiBackendSaveFeedbackUi {
        override val token: String get() = "SAVED"
        val message: String get() = "Backend preference saved."
    }

    /** Storage refused, or the authoritative value did not confirm the write. Never shown as saved. */
    data class Failed(val targetToken: String, val errorToken: String, val message: String) :
        AnkiBackendSaveFeedbackUi {
        override val token: String get() = "FAILED"
    }

    /** The write was accepted but could not be re-read yet: honest, and not a success claim. */
    data class Unconfirmed(val targetToken: String) : AnkiBackendSaveFeedbackUi {
        override val token: String get() = "UNCONFIRMED"
        val message: String get() =
            "The preference was written but could not be re-read yet. Reopen Settings to confirm."
    }
}

/** Health-refresh state. Refresh is read-only (GATE 21D `AnkiBackendRegistry.refresh`). */
sealed interface AnkiBackendRefreshUi {
    val token: String

    data object Idle : AnkiBackendRefreshUi {
        override val token: String get() = "IDLE"
    }

    data class Running(val targetToken: String?) : AnkiBackendRefreshUi {
        override val token: String get() = "RUNNING"
    }

    data class Completed(val resultToken: String, val targetToken: String?) : AnkiBackendRefreshUi {
        override val token: String get() = "COMPLETED"
    }

    data class Failed(val errorToken: String, val targetToken: String?) : AnkiBackendRefreshUi {
        override val token: String get() = "FAILED"
    }

    companion object {
        const val REQUESTED = "REFRESH_REQUESTED"
        const val NOT_REGISTERED = "REFRESH_BACKEND_NOT_REGISTERED"
        const val UNAVAILABLE = "REFRESH_MECHANISM_NOT_AVAILABLE"
        const val CANCELLED = "REFRESH_CANCELLED"
        const val FAILED = "REFRESH_FAILED"
    }
}

/**
 * The safe actions this section may offer (CHECKPOINT 21M-20).
 *
 * A closed set on purpose: every entry is backed by an existing application service, so there is no
 * inert control and no unauthorized backend mutation. There is deliberately **no** "grant AnkiDroid
 * permission" action: that permission is third-party-declared and resolved at install time, so no
 * interactive grant exists (`AnkiDroidPermissionManager` KDoc, GATE 02 §99) — the honest remedies are
 * Refresh and Open AnkiDroid, which the Settings Anki-integration card already provides.
 *
 * There is also no retry-all, force-commit, clear or reassign action anywhere in GATE 21M: a durable
 * transaction keeps its own backend and its own status vocabulary (AUDIT-21M-R2-14).
 */
enum class AnkiBackendUiAction(val token: String) {
    /** Read-only availability re-check of one exact instance (`AnkiBackendRegistry.refresh`). */
    REFRESH_STATUS("refresh_status"),

    /** Expand/collapse the capability detail of one row (a real UI action, no backend call). */
    VIEW_CAPABILITY_DETAILS("view_capability_details"),

    /** Open the existing Connection screen, where backend/agent configuration lives. */
    OPEN_BACKEND_CONFIGURATION("open_backend_configuration"),

    /** Open the existing read-only Diagnostics screen. */
    OPEN_DIAGNOSTICS("open_diagnostics"),

    /** Launch the installed AnkiDroid app (existing `AnkiDroidLauncher`). */
    OPEN_ANKIDROID("open_ankidroid")
}

/** Truthful empty inventory: nothing is configured, and nothing is fabricated to fill the space. */
data class AnkiBackendEmptyInventoryUi(
    val headline: String = "No Anki backend is configured",
    val explanation: String = "This build has no configured Anki backend instance, so no backend can " +
        "be selected and no Anki operation can start. Automatic (AUTO) will report the same refusal."
)

/** Everything the Settings **Anki backend** section renders, immutable and content-free. */
data class AnkiBackendSectionUiState(
    val rows: List<AnkiBackendRowUi>,
    /** Key of the row that reflects the *authoritative* persisted preference (null only if unknown). */
    val selectedKey: String?,
    val preferenceSummary: String,
    /** Stable token of the persisted preference, e.g. `auto`, `explicit:ankidroid_local`. */
    val preferenceToken: String,
    val saveFeedback: AnkiBackendSaveFeedbackUi,
    val refresh: AnkiBackendRefreshUi,
    val activeSession: ActiveStudySessionUi,
    /** Always shown: what a preference change does and does not touch (DECISION-21-37). */
    val scopeNotice: String,
    /**
     * Shown only when a preference write was confirmed **and** an active session exists
     * (CHECKPOINT 21M-15). Never a blocking dialog, never a session migration.
     */
    val switchEffectNotice: String?,
    val emptyInventory: AnkiBackendEmptyInventoryUi?,
    /** Freshness honesty: the contract has no timestamp, so no age or TTL may be implied. */
    val observationNotice: String,
    val actions: List<AnkiBackendUiAction>
) {
    /** Keys are unique, so Compose item keys and test tags are stable (CHECKPOINT 21M-04). */
    val hasDuplicateKeys: Boolean get() = rows.map { it.key }.distinct().size != rows.size

    val configuredInstanceRows: List<AnkiBackendInstanceRowUi>
        get() = rows.filterIsInstance<AnkiBackendInstanceRowUi>()

    companion object {
        const val SCOPE_NOTICE =
            "Applies to new browsing and new study sessions only. An active session, an open editor, " +
                "an open creation draft and every unfinished transaction keep the backend they " +
                "started on."
        const val SWITCH_EFFECT_NOTICE =
            "Backend preference updated. Your active study session remains connected to its original " +
                "Anki backend."
        const val OBSERVATION_NOTICE =
            "Status is the last observation each backend published; it is not a live connection and " +
            "carries no timestamp. Use Refresh to re-check."
    }
}

/** Kinds a user may express a preference for. `FAKE` is never user-selectable. */
val USER_SELECTABLE_BACKEND_KINDS: List<AnkiBackendKind> =
    listOf(AnkiBackendKind.ANKIDROID_LOCAL, AnkiBackendKind.PC_AGENT)

/** Kind label used by the informational rows. Display only — never an identity, never a policy input. */
fun AnkiBackendKind.selectionTitle(): String = when (this) {
    AnkiBackendKind.ANKIDROID_LOCAL -> "AnkiDroid"
    AnkiBackendKind.PC_AGENT -> "PC Anki"
    AnkiBackendKind.FAKE -> "Test backend"
}

/**
 * Why a kind with no wired adapter cannot be selected.
 *
 * The PC wording carries the distinction GATE 21M is required to make explicit: a PC-hosted AI /
 * speech service being reachable does not make a PC Anki backend ready, because they are different
 * things (R-20-2, BACKEND_SELECTION_POLICY §2 "PC transport ready ≠ desktop Anki review ready").
 */
fun AnkiBackendKind.notImplementedExplanation(): String = when (this) {
    AnkiBackendKind.ANKIDROID_LOCAL ->
        "No AnkiDroid backend instance is wired in this build, so it cannot be selected."
    AnkiBackendKind.PC_AGENT ->
        "PC Anki support is not implemented in this build: no production PC Anki adapter is wired, so " +
            "there is no PC backend to select. A connected PC agent for AI, speech-to-text or " +
            "text-to-speech is not an Anki backend and never makes this row ready."
    AnkiBackendKind.FAKE ->
        "Test backends are never selectable in the production UI."
}

/** Stable, content-free token for a persisted preference (never the raw stored text). */
fun BackendPreference.preferenceToken(): String = when (this) {
    BackendPreference.Auto -> "auto"
    is BackendPreference.Explicit -> "explicit:${backendId.stableId}"
    is BackendPreference.ExplicitKind -> "explicit_kind:${kind.name}"
    is BackendPreference.InvalidStored -> "invalid_stored:${issue.name}"
}

/**
 * Build the whole section.
 *
 * @param states live per-backend states from `AnkiBackendStatusCenter.states` (registry-owned).
 * @param preference the authoritative persisted preference (`BackendPreferenceRepository.preference`).
 * @param autoPreview pure pre-binding preview of what AUTO would choose *now*; never a promise for a
 *   bound context, and never a session failover.
 * @param sessionLocks the lock tracker's map, read as a snapshot.
 * @param customStudyClaims GATE 20 static claims, keyed by the exact backend identity.
 * @param requiredCapabilities the capability set the AUTO preview was resolved against.
 */
fun buildAnkiBackendSectionUi(
    states: List<AnkiBackendState>,
    preference: BackendPreference,
    autoPreview: BackendResolutionResult2? = null,
    sessionLocks: Map<String, AnkiSessionContext> = emptyMap(),
    customStudyClaims: Map<AnkiBackendId, AnkiCustomStudyCapabilities> = emptyMap(),
    saveFeedback: AnkiBackendSaveFeedbackUi = AnkiBackendSaveFeedbackUi.Idle,
    refresh: AnkiBackendRefreshUi = AnkiBackendRefreshUi.Idle,
    requiredCapabilities: Set<AnkiRequiredCapability> = STUDY_SESSION_REQUIREMENTS
): AnkiBackendSectionUiState {
    // One row per registered instance, in identity order: display order only, never a priority.
    val instanceRows = states
        .sortedBy { it.backendId.stableId }
        .map { state -> instanceRow(state, preference, states, customStudyClaims[state.backendId]) }

    val configuredKinds = states.map { it.kind }.toSet()

    // R-20-2: a user-selectable kind with nothing registered is informational, never operational.
    val unimplementedKinds = USER_SELECTABLE_BACKEND_KINDS
        .filterNot { it in configuredKinds }
        .map { kind ->
            AnkiBackendUnimplementedKindRowUi(
                kind = kind,
                title = kind.selectionTitle(),
                explanation = kind.notImplementedExplanation()
            )
        }

    val unresolved = unresolvedRow(preference, states)

    val autoRow = AnkiBackendAutoRowUi(
        selected = preference == BackendPreference.Auto,
        statusLabel = autoStatusLabel(preference, autoPreview, states, instanceRows, requiredCapabilities),
        statusToken = autoStatusToken(autoPreview, states),
        explanation = AnkiBackendAutoRowUi.AUTO_EXPLANATION
    )

    val rows: List<AnkiBackendRowUi> =
        listOf(autoRow) + instanceRows + listOfNotNull(unresolved) + unimplementedKinds

    val activeSession = activeSessionUi(sessionLocks)
    val saveConfirmed = saveFeedback is AnkiBackendSaveFeedbackUi.Saved

    return AnkiBackendSectionUiState(
        rows = rows,
        selectedKey = selectedKeyFor(preference, states, unresolved),
        preferenceSummary = preferenceSummary(preference, states, unresolved),
        preferenceToken = preference.preferenceToken(),
        saveFeedback = saveFeedback,
        refresh = refresh,
        activeSession = activeSession,
        scopeNotice = AnkiBackendSectionUiState.SCOPE_NOTICE,
        // CHECKPOINT 21M-15: only after a confirmed write, and only when a session is really active.
        switchEffectNotice = if (saveConfirmed && activeSession is ActiveStudySessionUi.One) {
            AnkiBackendSectionUiState.SWITCH_EFFECT_NOTICE
        } else {
            null
        },
        emptyInventory = if (states.isEmpty()) AnkiBackendEmptyInventoryUi() else null,
        observationNotice = AnkiBackendSectionUiState.OBSERVATION_NOTICE,
        actions = sectionActions(states)
    )
}

// --------------------------------------------------------------------------- rows

private fun instanceRow(
    state: AnkiBackendState,
    preference: BackendPreference,
    states: List<AnkiBackendState>,
    customStudyClaim: AnkiCustomStudyCapabilities?
): AnkiBackendInstanceRowUi {
    val id = state.backendId
    val testDouble = state.kind == AnkiBackendKind.FAKE
    val title = id.displayLabel()
    val reason = state.unavailableReason
    val availabilityText = state.availability.statusText()
    val statusLabel = availabilityText ?: if (state.descriptor.isReachable) "Ready" else "Unknown"

    return AnkiBackendInstanceRowUi(
        backendId = id,
        title = title,
        // A test double is rendered for honesty but is never an operational choice; every other
        // registered instance stays selectable even while unavailable, because picking an unavailable
        // backend must produce a typed refusal and never a silent substitute (INV-21-10).
        selectable = !testDouble,
        selected = isSelected(preference, id, states),
        statusLabel = statusLabel,
        statusToken = state.availability.statusCode,
        explanation = explanationFor(state, testDouble),
        kindToken = state.kind.name,
        localityToken = state.locality.name,
        readyNow = state.descriptor.isReachable,
        testDouble = testDouble,
        capabilityEvidenceToken = state.capabilityEvidence.name,
        unavailableReasonToken = reason?.token,
        retryabilityToken = reason?.retryability?.name,
        capabilities = AnkiRequiredCapability.entries.map { required ->
            val status = state.capability(required)
            AnkiBackendCapabilityRowUi(
                capability = required,
                title = required.displayTitle,
                implemented = status.implemented,
                operational = status.operational,
                implementedLabel = status.implemented.displayLabel(),
                currentStateLabel = status.operational.displayLabel()
            )
        },
        customStudy = customStudyClaim?.let { buildCustomStudyStatus(it, title) }
    )
}

private fun explanationFor(state: AnkiBackendState, testDouble: Boolean): String? = when {
    testDouble -> "A test backend is registered in this process. It is shown for honesty and can " +
        "never be selected as an operational Anki backend."
    else -> state.availability.statusText()
}

private fun isSelected(
    preference: BackendPreference,
    id: AnkiBackendId,
    states: List<AnkiBackendState>
): Boolean = when (preference) {
    is BackendPreference.Explicit -> preference.backendId == id

    // A legacy kind preference names an instance only when exactly one instance of that kind is
    // configured. With two or more it is ambiguous, so *no* row may claim to be the selected one —
    // marking both would present two checked choices for one stored preference, and marking either
    // would be the selection choosing an instance for the user (DECISION-21-15, INV-21-10). The
    // ambiguity is carried by the unresolved row instead.
    is BackendPreference.ExplicitKind ->
        preference.kind == id.kind && states.count { it.kind == preference.kind } == 1

    else -> false
}

private fun unresolvedRow(
    preference: BackendPreference,
    states: List<AnkiBackendState>
): AnkiBackendUnresolvedRowUi? {
    val registered = states.map { it.backendId }.toSet()
    return when (preference) {
        is BackendPreference.Explicit ->
            if (preference.backendId in registered) {
                null
            } else {
                AnkiBackendUnresolvedRowUi(
                    backendId = preference.backendId,
                    title = preference.backendId.displayLabel(),
                    statusLabel = "Saved, but not configured",
                    statusToken = "PREFERRED_BACKEND_NOT_CONFIGURED",
                    explanation = "Your saved preference names the backend " +
                        "'${preference.backendId.stableId}', which is not configured in this build. " +
                        "Nothing was rewritten: choose Automatic or another backend to correct it. " +
                        "Any transaction already written to that backend keeps its original owner.",
                    storedPreferenceToken = preference.preferenceToken()
                )
            }

        is BackendPreference.ExplicitKind -> {
            val matching = states.filter { it.kind == preference.kind }
            when {
                matching.size == 1 -> null
                matching.isEmpty() -> AnkiBackendUnresolvedRowUi(
                    backendId = null,
                    title = preference.kind.selectionTitle(),
                    statusLabel = "Saved, but not configured",
                    statusToken = "PREFERRED_BACKEND_KIND_NOT_CONFIGURED",
                    explanation = "Your saved preference names a ${preference.kind.selectionTitle()} " +
                        "backend, and this build has no configured instance of it. Choose Automatic or " +
                        "a configured backend to correct it.",
                    storedPreferenceToken = preference.preferenceToken()
                )

                else -> AnkiBackendUnresolvedRowUi(
                    backendId = null,
                    title = preference.kind.selectionTitle(),
                    statusLabel = "Choose one configured instance",
                    statusToken = "PREFERRED_BACKEND_KIND_AMBIGUOUS",
                    explanation = "Your saved preference names a ${preference.kind.selectionTitle()} " +
                        "backend without naming which instance, and ${matching.size} are configured. " +
                        "Selection must not choose for you: pick one instance below.",
                    storedPreferenceToken = preference.preferenceToken()
                )
            }
        }

        is BackendPreference.InvalidStored -> AnkiBackendUnresolvedRowUi(
            backendId = null,
            title = "Saved preference could not be read",
            statusLabel = invalidStoredLabel(preference.issue),
            statusToken = "PREFERENCE_INVALID_${preference.issue.name}",
            explanation = "The stored backend preference could not be interpreted safely " +
                "(${preference.issue.name}). It was not converted to Automatic and nothing was " +
                "guessed: choose a backend to replace it.",
            storedPreferenceToken = preference.preferenceToken()
        )

        BackendPreference.Auto -> null
    }
}

private fun invalidStoredLabel(issue: BackendPreferenceInvalidIssue): String = when (issue) {
    BackendPreferenceInvalidIssue.UNKNOWN_MODE -> "Unknown saved selection"
    BackendPreferenceInvalidIssue.INVALID_INSTANCE_PIN -> "Saved backend reference is invalid"
    BackendPreferenceInvalidIssue.INSTANCE_PIN_KIND_MISMATCH -> "Saved backend reference does not match"
}

// --------------------------------------------------------------------------- AUTO row

private fun autoStatusLabel(
    preference: BackendPreference,
    preview: BackendResolutionResult2?,
    states: List<AnkiBackendState>,
    instanceRows: List<AnkiBackendInstanceRowUi>,
    requiredCapabilities: Set<AnkiRequiredCapability>
): String {
    if (states.isEmpty()) return "No Anki backend is configured"
    return when (preview) {
        is BackendResolutionResult2.Resolved ->
            "Would use ${preview.backendId.displayLabel()} for ${describeRequirement(requiredCapabilities)}"
        is BackendResolutionResult2.NoBackendConfigured -> "No Anki backend is configured"
        is BackendResolutionResult2.ExplicitBackendKindUnavailable ->
            "No configured backend of the requested kind"
        is BackendResolutionResult2.ExplicitBackendMissing -> "The saved backend is not configured"
        is BackendResolutionResult2.ExplicitBackendUnavailable ->
            "The saved backend is not ready: ${preview.reason?.name ?: "reason unknown"}"
        is BackendResolutionResult2.NoEligibleBackend ->
            "No backend is eligible right now" + readySummary(instanceRows)
        is BackendResolutionResult2.CapabilityUnsupported ->
            "No backend offers what a review session needs" + missingSummary(preview, instanceRows)
        is BackendResolutionResult2.Ambiguous ->
            "Choose one instance: ${preview.candidates.sortedBy { it.stableId }.joinToString(", ") { it.displayLabel() }}"
        is BackendResolutionResult2.ConfigurationInvalid ->
            "The saved backend preference could not be read"
        // A future failure variant still gets an honest sentence, never a silent blank.
        null -> if (preference == BackendPreference.Auto) "Not resolved yet" else "Not applicable"
    }
}

/**
 * Names what the AUTO preview was resolved against, so the copy cannot promise more than the request
 * asked for: a preview resolved for a review session says so, and one resolved for a narrower or
 * wider requirement does not borrow that wording.
 */
private fun describeRequirement(required: Set<AnkiRequiredCapability>): String = when {
    required == STUDY_SESSION_REQUIREMENTS -> "a new review session"
    required.contains(AnkiRequiredCapability.REVIEW) -> "a new review operation"
    required.isEmpty() -> "a new operation"
    else -> "a new operation needing " + required.sortedBy { it.name }.joinToString(", ") { it.displayTitle }
}

private fun autoStatusToken(preview: BackendResolutionResult2?, states: List<AnkiBackendState>): String =
    when (preview) {
        is BackendResolutionResult2.Resolved -> "AUTO_PREVIEW_RESOLVED"
        is BackendResolutionResult2.Failed -> "AUTO_PREVIEW_${preview.failure.name}"
        null -> if (states.isEmpty()) "AUTO_PREVIEW_NO_BACKEND_CONFIGURED" else "AUTO_PREVIEW_NOT_RESOLVED"
    }

private fun readySummary(rows: List<AnkiBackendInstanceRowUi>): String {
    val ready = rows.count { it.readyNow }
    return if (ready == 0) " (no configured backend is ready)" else " ($ready of ${rows.size} ready)"
}

private fun missingSummary(
    preview: BackendResolutionResult2.CapabilityUnsupported,
    rows: List<AnkiBackendInstanceRowUi>
): String {
    val missing = preview.missing.values.flatten().distinct().sortedBy { it.name }
    val names = missing.joinToString(", ") { it.displayTitle }
    val ready = rows.count { it.readyNow }
    return if (names.isBlank()) " ($ready ready)" else " — missing: $names"
}

// --------------------------------------------------------------------------- session + summary

private fun activeSessionUi(locks: Map<String, AnkiSessionContext>): ActiveStudySessionUi = when {
    locks.isEmpty() -> ActiveStudySessionUi.None
    locks.size == 1 -> {
        val context = locks.values.single()
        ActiveStudySessionUi.One(
            backendId = context.backendId,
            studySessionId = context.studySessionId,
            backendTitle = context.backendId.displayLabel(),
            collectionKeyPresent = context.collection?.collectionKey != null
        )
    }

    else -> ActiveStudySessionUi.Several(locks.size)
}

private fun selectedKeyFor(
    preference: BackendPreference,
    states: List<AnkiBackendState>,
    unresolved: AnkiBackendUnresolvedRowUi?
): String? {
    val registered = states.map { it.backendId }
    return when (preference) {
        BackendPreference.Auto -> AnkiBackendAutoRowUi.KEY
        is BackendPreference.Explicit ->
            if (preference.backendId in registered) preference.backendId.stableId else unresolved?.key
        is BackendPreference.ExplicitKind -> {
            val matching = states.filter { it.kind == preference.kind }
            if (matching.size == 1) matching.single().backendId.stableId else unresolved?.key
        }

        is BackendPreference.InvalidStored -> unresolved?.key
    }
}

private fun preferenceSummary(
    preference: BackendPreference,
    states: List<AnkiBackendState>,
    unresolved: AnkiBackendUnresolvedRowUi?
): String = when (preference) {
    BackendPreference.Auto -> "Preferred backend: Automatic (AUTO)"
    is BackendPreference.Explicit ->
        if (states.any { it.backendId == preference.backendId }) {
            "Preferred backend: ${preference.backendId.displayLabel()}"
        } else {
            "Preferred backend: ${preference.backendId.displayLabel()} (not configured)"
        }

    is BackendPreference.ExplicitKind -> {
        val matching = states.filter { it.kind == preference.kind }
        when (matching.size) {
            1 -> "Preferred backend: ${matching.single().backendId.displayLabel()}"
            0 -> "Preferred backend: ${preference.kind.selectionTitle()} (not configured)"
            else -> "Preferred backend: ${preference.kind.selectionTitle()} (instance not chosen)"
        }
    }

    is BackendPreference.InvalidStored ->
        "Preferred backend: ${unresolved?.statusLabel ?: "saved preference could not be read"}"
}

/**
 * The closed set of actions offered for this state.
 *
 * Refresh is offered only when the registry can actually re-check something, and Open AnkiDroid only
 * when an AnkiDroid-kind instance is configured — an action with nothing behind it would be an inert
 * control (CHECKPOINT 21M-20). No entry here mutates a collection, retries a transaction, resets a
 * status or reassigns an owner.
 */
private fun sectionActions(states: List<AnkiBackendState>): List<AnkiBackendUiAction> = buildList {
    if (states.isNotEmpty()) add(AnkiBackendUiAction.REFRESH_STATUS)
    add(AnkiBackendUiAction.VIEW_CAPABILITY_DETAILS)
    add(AnkiBackendUiAction.OPEN_BACKEND_CONFIGURATION)
    add(AnkiBackendUiAction.OPEN_DIAGNOSTICS)
    // The launcher opens an installed app. It is offered only when this build wires that backend
    // kind *and* the app is not known to be absent; a button that can only log "not installed" is
    // not a working action. Checking/permission states still allow the launch.
    if (states.any {
            it.kind == AnkiBackendKind.ANKIDROID_LOCAL && it.availability !is AnkiAvailability.NotInstalled
        }
    ) {
        add(AnkiBackendUiAction.OPEN_ANKIDROID)
    }
}
