package com.studyagent.client.ui.components.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendStatusSnapshot
import com.studyagent.client.core.anki.BackendResolutionResult

/**
 * GATE 21 — presentation models for backend selection and context indication
 * (IMPLEMENTATION-21-18/19, DECISION-21-36/37).
 *
 * Pure Kotlin on purpose: the wording and the "can this be picked?" rules are exactly the part a
 * reviewer must be able to assert on, and they must not depend on Compose to be tested.
 *
 * Two rules this file enforces:
 *
 * - **A mode is selectable even when nothing answers.** Picking an unavailable backend is how a user
 *   says "I want *this* one", and the honest result is a blocked operation, never a silent substitute
 *   (DECISION-21-26, INV-21-10).
 * - **AUTO never promises merged data.** Its description says it picks *one* backend per operation
 *   (DECISION-21-05, VER-21-27).
 */

/** One row of the backend selector. */
data class AnkiBackendOptionUi(
    val mode: AnkiBackendMode,
    val title: String,
    /** Short status of the backend(s) this mode can use, or why none can be used. */
    val statusLabel: String,
    /** Longer explanation, only when there is something honest to add. */
    val detail: String?
)

/** The whole selector, plus the effect statement shown when the user switches. */
data class AnkiBackendSelectionUi(
    val options: List<AnkiBackendOptionUi>,
    val selected: AnkiBackendMode,
    /** DECISION-21-37 — what a switch does and does not touch. */
    val switchEffectNotice: String
) {
    companion object {
        /**
         * One sentence, always shown: switching is *not* a live re-route. Library data reloads, but an
         * active session keeps its own locked backend and a bound editor keeps the backend it opened
         * against (DECISION-21-20/32, INV-21-14).
         */
        const val SWITCH_EFFECT_NOTICE =
            "Applies to new browsing and new study sessions. An active session, an open editor and any " +
                "unfinished save keep the backend they started on."
    }
}

/** Human label for a backend identity — display only, never an identity (IMPLEMENTATION-21-01). */
fun AnkiBackendId.displayLabel(): String = when (this) {
    is AnkiBackendId.AnkiDroidLocal -> "AnkiDroid"
    is AnkiBackendId.PcAgent -> if (profileId == "default") "PC Agent" else "PC Agent ($profileId)"
    is AnkiBackendId.Fake -> "Test backend"
}

/**
 * The Study/Library context indicator (IMPLEMENTATION-21-19).
 *
 * Study must show the **locked** backend, not the preference: they can legitimately differ, and
 * showing the preference there would tell the user a lie about where their ratings are going
 * (INV-21-08, DECISION-21-36).
 */
fun lockedBackendIndicator(backendId: AnkiBackendId?): String =
    backendId?.let { "Using ${it.displayLabel()}" } ?: "No Anki backend bound"

/** Mode titles. `AUTO` is spelled out as a choice, not as "default". */
fun AnkiBackendMode.displayTitle(): String = when (this) {
    AnkiBackendMode.AUTO -> "Automatic"
    AnkiBackendMode.ANKIDROID_LOCAL -> "AnkiDroid"
    AnkiBackendMode.PC_AGENT -> "PC Agent"
}

/**
 * Build the selector UI from live status.
 *
 * Every mode stays selectable; only the *status text* changes. [resolution] is the result of the last
 * pre-binding resolution and is used purely to explain what Automatic would pick right now — it is
 * never a promise for a session that is already locked.
 */
fun buildAnkiBackendSelectionUi(
    snapshot: AnkiBackendStatusSnapshot,
    resolution: BackendResolutionResult? = null
): AnkiBackendSelectionUi {
    val entries = snapshot.entries
    fun statusFor(kind: AnkiBackendKind): Pair<String, String?> {
        val matching = entries.filter { it.descriptor.kind == kind }
        if (matching.isEmpty()) return "Not available in this build" to null
        val ready = matching.filter { it.isReachable }
        return when {
            ready.size == 1 -> "Ready" to ready.single().id.displayLabel()
            ready.size > 1 -> "${ready.size} ready" to ready.joinToString(", ") { it.id.displayLabel() }
            else -> readyOrReason(matching.first().descriptor.availability) to
                matching.first().descriptor.availability.statusText()
        }
    }

    val (localStatus, localDetail) = statusFor(AnkiBackendKind.ANKIDROID_LOCAL)
    val (pcStatus, pcDetail) = statusFor(AnkiBackendKind.PC_AGENT)

    val autoDetail = when (resolution) {
        is BackendResolutionResult.Resolved -> "Would use ${resolution.backendId.displayLabel()}"
        is BackendResolutionResult.Ambiguous ->
            "Choose a PC profile: ${resolution.candidates.joinToString(", ") { it.displayLabel() }}"
        is BackendResolutionResult.RequiredCapabilityUnavailable -> "No backend offers what this needs"
        is BackendResolutionResult.NoBackendAvailable -> "No Anki backend is registered"
        is BackendResolutionResult.PreferredBackendUnavailable -> "No Anki backend is ready"
        is BackendResolutionResult.ConfigurationInvalid -> "Backend preference could not be read"
        // Any future failure variant still gets an honest sentence rather than a silent blank.
        is BackendResolutionResult.Failure -> "No Anki backend could be selected"
        null -> null
    }

    return AnkiBackendSelectionUi(
        options = listOf(
            AnkiBackendOptionUi(
                mode = AnkiBackendMode.AUTO,
                title = AnkiBackendMode.AUTO.displayTitle(),
                // AUTO picks ONE backend per operation; it never merges two collections.
                statusLabel = autoDetail ?: "Picks one ready backend per task",
                detail = "Never merges AnkiDroid and PC data into one list."
            ),
            AnkiBackendOptionUi(
                mode = AnkiBackendMode.ANKIDROID_LOCAL,
                title = AnkiBackendMode.ANKIDROID_LOCAL.displayTitle(),
                statusLabel = localStatus,
                detail = localDetail
            ),
            AnkiBackendOptionUi(
                mode = AnkiBackendMode.PC_AGENT,
                title = AnkiBackendMode.PC_AGENT.displayTitle(),
                statusLabel = pcStatus,
                detail = pcDetail
            )
        ),
        selected = snapshot.preferred,
        switchEffectNotice = AnkiBackendSelectionUi.SWITCH_EFFECT_NOTICE
    )
}

/** User-facing availability wording. Tokens stay in the domain; copy lives here. */
fun AnkiAvailability.statusText(): String? = when (this) {
    is AnkiAvailability.Ready -> null
    AnkiAvailability.Checking -> "Checking…"
    AnkiAvailability.NotInstalled -> "AnkiDroid is not installed on this device."
    is AnkiAvailability.ProviderUnavailable -> "AnkiDroid's integration provider is not reachable."
    is AnkiAvailability.PermissionRequired -> "Study-Agent has not been granted access to AnkiDroid."
    AnkiAvailability.CollectionNotInitialized -> "No Anki collection is open yet."
    is AnkiAvailability.TemporarilyUnavailable -> "Temporarily busy; it should recover on its own."
    AnkiAvailability.AgentDisconnected -> "The PC agent is not reachable."
    AnkiAvailability.AgentAnkiUnavailable -> "The PC agent is up, but Anki on that PC is not ready."
    is AnkiAvailability.Unsupported -> "This Anki version is not supported."
    is AnkiAvailability.Fault -> "The availability check failed."
}

private fun readyOrReason(availability: AnkiAvailability): String =
    availability.statusText() ?: "Ready"
