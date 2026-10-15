package com.studyagent.client.data.preferences

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendPreferenceInvalidIssue
import com.studyagent.client.core.anki.toPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A validated DataStore write; the mode and pin are committed atomically by [PreferencesDataStore]. */
internal data class EncodedBackendPreference(
    val mode: AnkiBackendMode,
    val instancePinStableId: String?
)

/**
 * Decode one atomic persisted snapshot into the domain preference.
 *
 * Missing mode means the locked fresh-install default, AUTO. Unknown mode tokens, malformed pins and
 * pins whose identity kind contradicts the stored PC mode remain typed invalid values; none is
 * guessed into AUTO, an unpinned PC kind, or another backend. Raw persisted text is intentionally not
 * retained by the domain value or included in an error/result.
 */
internal fun decodeStoredBackendPreference(
    modeStableId: String?,
    instancePinStableId: String?
): BackendPreference {
    if (modeStableId == null) return BackendPreference.Auto

    val mode = AnkiBackendMode.fromStableId(modeStableId)
        ?: return BackendPreference.InvalidStored(BackendPreferenceInvalidIssue.UNKNOWN_MODE)

    return when (mode) {
        AnkiBackendMode.AUTO -> BackendPreference.Auto
        AnkiBackendMode.ANKIDROID_LOCAL ->
            // AnkiDroid is a singleton; the mode is already an exact, unambiguous selection.
            BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal)
        AnkiBackendMode.PC_AGENT -> {
            if (instancePinStableId == null) {
                BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
            } else {
                when (val id = AnkiBackendId.fromStableId(instancePinStableId)) {
                    is AnkiBackendId.PcAgent -> BackendPreference.Explicit(id)
                    null -> BackendPreference.InvalidStored(
                        issue = BackendPreferenceInvalidIssue.INVALID_INSTANCE_PIN,
                        legacyMode = mode
                    )
                    else -> BackendPreference.InvalidStored(
                        issue = BackendPreferenceInvalidIssue.INSTANCE_PIN_KIND_MISMATCH,
                        legacyMode = mode
                    )
                }
            }
        }
    }
}

/**
 * Encode only valid, user-selectable preferences. Invalid persisted values and Fake identities cannot
 * be written, preventing a bad setting from being silently replaced with AUTO.
 */
internal fun BackendPreference.toEncodedBackendPreference(): EncodedBackendPreference? = when (this) {
    BackendPreference.Auto -> EncodedBackendPreference(AnkiBackendMode.AUTO, null)
    is BackendPreference.Explicit -> when (val id = backendId) {
        is AnkiBackendId.AnkiDroidLocal -> EncodedBackendPreference(AnkiBackendMode.ANKIDROID_LOCAL, null)
        is AnkiBackendId.PcAgent -> EncodedBackendPreference(AnkiBackendMode.PC_AGENT, id.stableId)
        is AnkiBackendId.Fake -> null
    }
    is BackendPreference.ExplicitKind -> when (kind) {
        AnkiBackendKind.ANKIDROID_LOCAL -> EncodedBackendPreference(AnkiBackendMode.ANKIDROID_LOCAL, null)
        AnkiBackendKind.PC_AGENT -> EncodedBackendPreference(AnkiBackendMode.PC_AGENT, null)
        AnkiBackendKind.FAKE -> null
    }
    is BackendPreference.InvalidStored -> null
}

/**
 * GATE 21E — the single full preference repository for future, unbound Anki work.
 *
 * The two stored fields are decoded from one DataStore snapshot so a mode and an instance pin can
 * never be combined across different preference writes. The repository does not own active-session
 * backend (the StudySession lock does), transaction backend (each durable ledger does), or selection
 * policy (the pure resolver does).
 *
 * Storage remains backward compatible with the existing keys:
 *  - `anki_backend_mode` stores `auto`, `ankidroid_local`, or `pc_agent`.
 *  - `anki_backend_instance_pin` stores one persistent `AnkiBackendId.stableId`, when selected.
 *  - missing mode uses the locked default, AUTO; `pc_agent` without a pin remains the legacy
 *    kind-only preference and resolution applies GATE 21B's zero/one/many-instance rule.
 *  - a well-formed pin for a removed PC profile remains `Explicit(id)` and resolves to a typed missing
 *    backend result; it is never rewritten to AUTO or to a sibling profile.
 *  - malformed or kind-mismatched storage remains [BackendPreference.InvalidStored] until the user
 *    chooses a valid preference. Raw values are not exposed in diagnostics.
 *
 * [mode] is a compatibility projection for pre-instance-pin consumers only. New routing must consume
 * [preference], since a legacy mode cannot represent an invalid stored value or an exact PC pin.
 */
class BackendPreferenceRepository(
    private val preferencesDataStore: PreferencesDataStore,
    scope: CoroutineScope
) {
    /** Restored preference, including an explicit invalid-stored state rather than a silent fallback. */
    val preference: StateFlow<BackendPreference> = preferencesDataStore.backendPreferenceSnapshotFlow
        .map { stored ->
            decodeStoredBackendPreference(stored.modeStableId, stored.instancePinStableId)
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = BackendPreference.Auto
        )

    /**
     * Legacy kind-only projection retained for existing UI/status call sites. It is not sufficient
     * for exact-instance routing; use [preference] for every new pre-binding resolution.
     */
    val mode: StateFlow<AnkiBackendMode> = preference
        .map { it.toLegacyMode() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AnkiBackendMode.AUTO
        )

    /** Content-free error token for the most recent failed write, or null. */
    private val _lastWriteError = MutableStateFlow<String?>(null)
    val lastWriteError: StateFlow<String?> = _lastWriteError.asStateFlow()

    /**
     * Persist a valid full preference. Invalid saved values and Fake pins are refused without writing
     * anything. A successful update changes future unbound work only; existing bindings are untouched.
     */
    suspend fun setPreference(preference: BackendPreference): Boolean {
        val encoded = preference.toEncodedBackendPreference()
        if (encoded == null) {
            _lastWriteError.value = "backend_preference_invalid"
            return false
        }

        val ok = preferencesDataStore.setAnkiBackendPreference(
            mode = encoded.mode,
            pin = encoded.instancePinStableId
        )
        if (ok) {
            _lastWriteError.value = null
        } else {
            _lastWriteError.value = "backend_preference_not_saved"
        }
        return ok
    }

    /**
     * Backward-compatible setter for the existing kind-level Settings control.
     * AUTO and AnkiDroid clear any prior pin; PC_AGENT without a pin is subject to the N-rule and is
     * ambiguous whenever multiple PC instances are configured.
     */
    suspend fun setMode(mode: AnkiBackendMode): Boolean = setPreference(mode.toPreference())

    private fun BackendPreference.toLegacyMode(): AnkiBackendMode = when (this) {
        BackendPreference.Auto -> AnkiBackendMode.AUTO
        is BackendPreference.Explicit -> when (backendId) {
            is AnkiBackendId.AnkiDroidLocal -> AnkiBackendMode.ANKIDROID_LOCAL
            is AnkiBackendId.PcAgent -> AnkiBackendMode.PC_AGENT
            is AnkiBackendId.Fake -> AnkiBackendMode.AUTO
        }
        is BackendPreference.ExplicitKind -> when (kind) {
            AnkiBackendKind.ANKIDROID_LOCAL -> AnkiBackendMode.ANKIDROID_LOCAL
            AnkiBackendKind.PC_AGENT -> AnkiBackendMode.PC_AGENT
            AnkiBackendKind.FAKE -> AnkiBackendMode.AUTO
        }
        is BackendPreference.InvalidStored -> legacyMode ?: AnkiBackendMode.AUTO
    }
}
