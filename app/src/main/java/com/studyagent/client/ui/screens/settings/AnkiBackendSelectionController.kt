package com.studyagent.client.ui.screens.settings

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRefresh
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendStatusCenter
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiStudyBackendLockTracker
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.ui.components.anki.AnkiBackendRefreshUi
import com.studyagent.client.ui.components.anki.AnkiBackendSaveFeedbackUi
import com.studyagent.client.ui.components.anki.AnkiBackendSectionUiState
import com.studyagent.client.ui.components.anki.buildAnkiBackendSectionUi
import com.studyagent.client.ui.components.anki.preferenceToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * GATE 21M Revision 2 — the Settings **Anki backend** section's presentation controller
 * (CHECKPOINT 21M-02 … 21M-15, 21M-21, 21M-23, 21M-24).
 *
 * ## Why this class exists separately from the ViewModel
 *
 * Everything GATE 21M adds is asynchronous, race-sensitive presentation logic: a preference write that
 * must be confirmed by the authoritative store before it may be called "saved", a stale write result
 * that must not overwrite a newer one, a read-only availability re-check whose late completion must not
 * resurrect a removed backend. None of that needs Android, and none of it should be locked behind a
 * ViewModel that can only be built with a `Context`. So the logic lives here, on a supplied
 * [CoroutineScope], and `SettingsViewModel` delegates to it with `viewModelScope`.
 *
 * It imports no `android.*` and no `androidx.*` type: it is ordinary Kotlin over flows, which keeps it
 * deterministically unit-testable and keeps the ViewModel a thin Android adapter.
 *
 * ## What it owns, and what it does not
 *
 * Owned: two pieces of *write/refresh feedback* (`Saving`/`Saved`/`Failed`/`Unconfirmed`, and
 * `Running`/`Completed`/`Failed`) and the monotonic generations that keep late results from being
 * published over newer ones.
 *
 * Not owned: backend existence and state (GATE 21D's registry and status center), the persisted
 * preference (GATE 21E's repository), active-session ownership (GATE 21G's immutable locks), the
 * selection policy (GATE 21E's one pure resolver, reached only through the injected preview function)
 * and durable transaction truth (each family's own ledger). This class reads those and writes exactly
 * one thing: the user's *preference*, through the injected writer.
 *
 * A preference change never rewrites a bound operation's identity: an active session keeps its locked
 * backend, a bound editor or creation draft keeps the backend it opened against, and every unresolved
 * durable transaction keeps the backend it was written to (INV-21-08/14/15, DECISION-21-32/37).
 */
class AnkiBackendSelectionController(
    private val scope: CoroutineScope,
    /** GATE 21D's observation point: one live state per registered backend, registry-owned. */
    private val statusCenter: AnkiBackendStatusCenter,
    /** GATE 21E's authoritative preference. The rendered selection always comes from here. */
    private val preference: StateFlow<BackendPreference>,
    /**
     * Used for exactly two read-only things: the sanctioned per-instance availability re-check
     * ([AnkiBackendRegistry.refresh]) and each wired adapter's *static* GATE 20 custom-study claim.
     */
    private val registry: AnkiBackendRegistry? = null,
    /** Content-free error token of the most recent refused write, published by the repository. */
    private val writeError: StateFlow<String?>? = null,
    /** The repository's own `setPreference`. Absent means the section is read-only. */
    private val writer: (suspend (BackendPreference) -> Boolean)? = null,
    /** GATE 21G's immutable active-session locks — projected, never derived from the preference. */
    private val sessionLocks: AnkiStudyBackendLockTracker? = null,
    /**
     * Pure pre-binding AUTO preview, supplied by the composition root so that this class is not a
     * second resolution site (AUDIT-21-07's closed list stays closed).
     */
    private val autoPreview:
        ((List<AnkiBackendState>, Set<AnkiRequiredCapability>) -> BackendResolutionResult2)? = null,
    /** The requirement set the AUTO preview is resolved against, and the copy says so. */
    private val requiredCapabilities: Set<AnkiRequiredCapability> = STUDY_SESSION_REQUIREMENTS,
    /** How long a confirmed write is awaited before it is reported as unconfirmed rather than saved. */
    private val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
    /** Failure reporting seam. Only exception *class* names are passed — never a message or a stack. */
    private val logFailure: (String) -> Unit = {}
) {

    private val _saveFeedback =
        MutableStateFlow<AnkiBackendSaveFeedbackUi>(AnkiBackendSaveFeedbackUi.Idle)

    private val _refresh = MutableStateFlow<AnkiBackendRefreshUi>(AnkiBackendRefreshUi.Idle)

    /**
     * Monotonic guards (CHECKPOINT 21M-11/21/24). Cancellation alone is not enough: a late write
     * result or a late refresh completion must not overwrite a newer state, and a backend removed
     * while its probe ran must not reappear from the probe's own result.
     */
    private val writeGeneration = AtomicInteger(0)
    private val refreshGeneration = AtomicInteger(0)

    /**
     * One projection of five authoritative inputs, with no backend truth of its own.
     *
     * Lifecycle-aware by construction (`stateIn` on the caller's scope), so nothing is collected during
     * composition and no probe, resolution or write happens on recomposition (CHECKPOINT 21M-23).
     */
    val section: StateFlow<AnkiBackendSectionUiState?> = combine(
        statusCenter.states,
        preference,
        _saveFeedback,
        _refresh,
        sessionLocks?.locks ?: MutableStateFlow<Map<String, AnkiSessionContext>>(emptyMap())
    ) { states, currentPreference, saveFeedback, refresh, locks ->
        buildAnkiBackendSectionUi(
            states = states,
            preference = currentPreference,
            autoPreview = autoPreview?.invoke(states, requiredCapabilities),
            sessionLocks = locks,
            customStudyClaims = customStudyClaims(),
            saveFeedback = saveFeedback,
            refresh = refresh,
            requiredCapabilities = requiredCapabilities
        )
    }.stateIn(scope = scope, started = SharingStarted.WhileSubscribed(5000), initialValue = null)

    /** Current write feedback, for callers that render it separately from the section. */
    val saveFeedback: StateFlow<AnkiBackendSaveFeedbackUi> = _saveFeedback

    /** Current availability re-check state. */
    val refreshState: StateFlow<AnkiBackendRefreshUi> = _refresh

    /**
     * Each wired adapter's *static* GATE 20 custom-study claim, keyed by exact identity. A claim, not
     * an observation: it is read from the registry the way GATE 21D reads `implementedCapabilities`,
     * and it can never make a filtered-session lifecycle appear operational (CHECKPOINT 21M-06).
     */
    private fun customStudyClaims(): Map<AnkiBackendId, AnkiCustomStudyCapabilities> =
        registry?.registered?.associate { it.id to it.customStudyCapabilities } ?: emptyMap()

    /**
     * Persist a new preferred backend (CHECKPOINT 21M-11, DECISION-21-32).
     *
     * The sequence is deliberately pessimistic:
     *
     * 1. `Saving` immediately, so a double tap cannot present two saved states;
     * 2. the authoritative writer decides acceptance — an exception is a refusal, never a success;
     * 3. a *newer* write invalidates this one, so its result is dropped rather than published;
     * 4. on acceptance, the authoritative preference flow must actually reach the target within
     *    [confirmationTimeoutMs]; if it does not, the honest answer is `Unconfirmed`, not `Saved`.
     *
     * The rendered selection never comes from this call's intent, only from the preference flow, so a
     * refused or unconfirmed write cannot look like a saved one (VER-21M-R2-14).
     */
    fun select(target: BackendPreference) {
        val write = writer ?: return
        val generation = writeGeneration.incrementAndGet()
        val targetToken = target.preferenceToken()
        _saveFeedback.value = AnkiBackendSaveFeedbackUi.Saving(targetToken)
        scope.launch {
            val accepted = try {
                write(target)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // Class name only: an exception message can carry a path, a provider or a key.
                logFailure("backend preference write failed (${failure.javaClass.simpleName})")
                false
            }
            if (writeGeneration.get() != generation) return@launch
            if (!accepted) {
                _saveFeedback.value = AnkiBackendSaveFeedbackUi.Failed(
                    targetToken = targetToken,
                    errorToken = writeError?.value ?: DEFAULT_WRITE_ERROR_TOKEN,
                    message = "The preference was not saved. The previous selection still applies."
                )
                return@launch
            }
            val confirmed = withTimeoutOrNull(confirmationTimeoutMs) {
                preference.first { it == target }
            }
            if (writeGeneration.get() != generation) return@launch
            _saveFeedback.value = if (confirmed != null) {
                AnkiBackendSaveFeedbackUi.Saved(targetToken)
            } else {
                AnkiBackendSaveFeedbackUi.Unconfirmed(targetToken)
            }
        }
    }

    /**
     * Read-only availability re-check of every configured instance through GATE 21D's sanctioned entry
     * point ([AnkiBackendRegistry.refresh] → `AnkiBackend.refreshAvailability`), which is contractually
     * read-only, ordered and cancellable (CHECKPOINT 21M-21, INV-21D-16).
     *
     * User-initiated only: nothing polls, nothing is triggered by composition, and a second request
     * while one is running starts a new generation rather than a second probe loop. Instances are
     * probed in identity order so two runs are comparable.
     */
    fun refreshStatus() {
        val inventory = registry
        if (inventory == null) {
            _refresh.value = AnkiBackendRefreshUi.Failed(AnkiBackendRefreshUi.UNAVAILABLE, null)
            return
        }
        val generation = refreshGeneration.incrementAndGet()
        _refresh.value = AnkiBackendRefreshUi.Running(null)
        scope.launch {
            val targets = inventory.registered.map { it.id }.sortedBy { it.stableId }
            var notRegistered = false
            try {
                targets.forEach { id ->
                    when (inventory.refresh(id)) {
                        is AnkiBackendRefresh.Requested -> Unit
                        // Removed while the probe ran: absence is reported, never a sibling's state.
                        is AnkiBackendRefresh.NotRegistered -> notRegistered = true
                    }
                }
            } catch (cancellation: CancellationException) {
                if (refreshGeneration.get() == generation) {
                    _refresh.value = AnkiBackendRefreshUi.Completed(AnkiBackendRefreshUi.CANCELLED, null)
                }
                throw cancellation
            } catch (failure: Throwable) {
                logFailure("backend availability refresh failed (${failure.javaClass.simpleName})")
                if (refreshGeneration.get() == generation) {
                    _refresh.value = AnkiBackendRefreshUi.Failed(AnkiBackendRefreshUi.FAILED, null)
                }
                return@launch
            }
            // Stale-response rejection: an older refresh never overwrites a newer one.
            if (refreshGeneration.get() != generation) return@launch
            _refresh.value = AnkiBackendRefreshUi.Completed(
                if (notRegistered) AnkiBackendRefreshUi.NOT_REGISTERED else AnkiBackendRefreshUi.REQUESTED,
                null
            )
        }
    }

    companion object {
        /** A write the repository accepted but the store has not echoed back yet is *unconfirmed*. */
        const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 2_000L

        /** Fallback when the repository publishes no token for a refused write. */
        const val DEFAULT_WRITE_ERROR_TOKEN = "backend_preference_not_saved"
    }
}
