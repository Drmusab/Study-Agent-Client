package com.studyagent.client.data.preferences

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.toPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * GATE 21E — authoritative persistence for the user's preferred backend policy.
 *
 * Owns exactly one concern: which [BackendPreference] applies to future, unbound work. It does not
 * own active-session backend (that is `AnkiStudyBackendLockTracker`), transaction backend (that is
 * each durable ledger), or any routing decision (that is `AnkiBackendResolver2`).
 *
 * Storage schema (backward compatible with GATE-21-era installs):
 *  - `anki_backend_mode` (existing) stores the kind/mode token (`auto` | `ankidroid_local` | `pc_agent`).
 *  - `anki_backend_instance_pin` (new in 21E) stores an [AnkiBackendId.stableId] when the user has
 *    picked a specific configured instance. Absent/null = no pin.
 *
 * The combination is read through [preference]:
 *  - mode == `auto` → [BackendPreference.Auto], pin ignored.
 *  - mode == `ankidroid_local` → [BackendPreference.Explicit(AnkiDroidLocal)] (singleton, pin ignored).
 *  - mode == `pc_agent` with a parsable pin → [BackendPreference.Explicit(pin)].
 *  - mode == `pc_agent` without a pin (legacy install, or user has not picked an instance yet) →
 *    [BackendPreference.ExplicitKind(PC_AGENT)]; resolution applies the N-rule (0 → unavailable,
 *    1 → that instance, ≥2 → Ambiguous).
 *
 * An unreadable or unknown pin is *not* silently dropped into AUTO: it stays as a stored stableId
 * and surfaces as [ExplicitBackendMissing] until the user clears it. This is the contract rule for a
 * removed configured backend (INV-21E-02, BACKEND_SELECTION_POLICY §4.2).
 *
 * This repository exposes the legacy [mode] flow alongside [preference] so pre-21E consumers
 * (Settings UI picker that only offers kind-level choices today, status-center snapshot) keep
 * compiling and keep working unchanged.
 */
class BackendPreferenceRepository(
    private val preferencesDataStore: PreferencesDataStore,
    scope: CoroutineScope
) {

    /**
     * The resolved preference. Never null; unknown tokens fall back to [BackendPreference.Auto] per
     * the pre-existing policy (a stale preference must never make the app refuse to study).
     */
    val preference: StateFlow<BackendPreference>

    /**
     * The legacy kind-level mode, retained for backward compatibility with pre-21E call sites that
     * have not been migrated to instance-pin semantics. Derived from [preference]:
     *  - Auto → AUTO
     *  - Explicit(AnkiDroidLocal) → ANKIDROID_LOCAL
     *  - Explicit(PcAgent) → PC_AGENT
     *  - ExplicitKind(PC_AGENT) → PC_AGENT
     */
    val mode: StateFlow<AnkiBackendMode>

    /** Content-free error token for the most recent failed write, or null. */
    private val _lastWriteError = MutableStateFlow<String?>(null)
    val lastWriteError: StateFlow<String?> = _lastWriteError.asStateFlow()

    init {
        val prefFlow: Flow<BackendPreference> = combine(
            preferencesDataStore.ankiBackendModeFlow,
            preferencesDataStore.ankiBackendPinFlow
        ) { legacyMode, pin -> combine(legacyMode, pin) }

        // Seed with AUTO eagerly; the first real emission from DataStore replaces it.
        // This mirrors the pre-21E behaviour of DataStoreAnkiBackendPreferenceStore.
        preference = prefFlow.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = BackendPreference.Auto
        )

        mode = preference.map { p -> p.toMode() }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AnkiBackendMode.AUTO
        )
    }

    /**
     * Persist a full [BackendPreference]. Returns false if storage refused; the in-memory flow keeps
     * the previous committed value in that case so UI cannot claim a switch that did not happen.
     */
    suspend fun setPreference(preference: BackendPreference): Boolean {
        val (mode, pin) = preference.toStored()
        val ok = preferencesDataStore.setAnkiBackendPreference(mode, pin)
        if (ok) {
            _lastWriteError.value = null
        } else {
            _lastWriteError.value = "backend_preference_not_saved"
        }
        return ok
    }

    /**
     * Backward-compatible setter used by the existing Settings UI that only knows about kind-level
     * modes.
     *
     * - AUTO clears any pin.
     * - ANKIDROID_LOCAL pins the singleton AnkiDroidLocal.
     * - PC_AGENT sets the kind with no pin (so N ≥ 1 PC instances resolve according to the N-rule:
     *   unambiguous when exactly one is configured, Ambiguous otherwise). A future Settings screen
     *   that lets the user pick an instance will call [setPreference] with an explicit pin.
     */
    suspend fun setMode(mode: AnkiBackendMode): Boolean = setPreference(mode.toPreference())

    companion object {

        private fun combine(legacyMode: AnkiBackendMode, pinStableId: String?): BackendPreference {
            return when (legacyMode) {
                AnkiBackendMode.AUTO -> BackendPreference.Auto
                AnkiBackendMode.ANKIDROID_LOCAL ->
                    // AnkiDroidLocal is a singleton by construction; ignore any stray pin.
                    BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal)
                AnkiBackendMode.PC_AGENT -> {
                    val parsed = pinStableId?.let(AnkiBackendId::fromStableId)
                    when {
                        parsed is AnkiBackendId.PcAgent -> BackendPreference.Explicit(parsed)
                        pinStableId != null ->
                            // Stored pin exists but does not decode to any known PcAgent. Keep as
                            // Explicit with the decoded id when decodable, or leave as
                            // ExplicitKind so resolution reports "missing" rather than guessing.
                            if (parsed != null) BackendPreference.Explicit(parsed)
                            else BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
                        else -> BackendPreference.ExplicitKind(AnkiBackendKind.PC_AGENT)
                    }
                }
            }
        }

        private fun BackendPreference.toStored(): Pair<AnkiBackendMode, String?> = when (this) {
            BackendPreference.Auto -> AnkiBackendMode.AUTO to null
            is BackendPreference.Explicit -> when (val id = backendId) {
                is AnkiBackendId.AnkiDroidLocal -> AnkiBackendMode.ANKIDROID_LOCAL to null
                is AnkiBackendId.PcAgent -> AnkiBackendMode.PC_AGENT to id.stableId
                is AnkiBackendId.Fake -> AnkiBackendMode.AUTO to null // never persist a fake pin
            }
            is BackendPreference.ExplicitKind -> when (kind) {
                AnkiBackendKind.ANKIDROID_LOCAL -> AnkiBackendMode.ANKIDROID_LOCAL to null
                AnkiBackendKind.PC_AGENT -> AnkiBackendMode.PC_AGENT to null
                AnkiBackendKind.FAKE -> AnkiBackendMode.AUTO to null
            }
        }

        private fun BackendPreference.toMode(): AnkiBackendMode = when (this) {
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
        }
    }
}
