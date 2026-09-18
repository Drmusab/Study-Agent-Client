package com.studyagent.client.core.audio

import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies [StudyAudioModeResolver] decisions over time (§41/§43/§44/§70).
 *
 * Responsibilities:
 *  - Keep the **effective route** current while the device set changes.
 *  - Distinguish an *unexpected headset loss* from *"there was never a headset"* — the
 *    second one is the normal Phone Mode startup and must never look like an error
 *    (§36/§43/§44/§93/§119).
 *  - Defer non-loss route changes (a headset appearing, A2DP upgrading to SCO, ...) to a safe
 *    turn boundary so the app never switches device mid-question or mid-answer
 *    (§39/§40/§41/§94/§95).
 *  - Apply an explicit user change immediately (§42/§84) and surface the recovery prompt
 *    after a loss (§96/§118).
 *
 * Android-free: the device facts arrive as a [Flow] of [AudioRouteSnapshot]; settings arrive
 * as a [Flow] of [StudyAudioPreferences]. Everything below is testable with plain flows.
 */
class StudyAudioRouteCoordinator(
    private val snapshots: Flow<AudioRouteSnapshot>,
    private val preferences: Flow<StudyAudioPreferences>,
    private val scope: CoroutineScope,
    private val resolver: StudyAudioModeResolver = DefaultStudyAudioModeResolver(),
    val metrics: PhoneModeDiagnostics = PhoneModeDiagnostics(),
    initialSnapshot: AudioRouteSnapshot = AudioRouteSnapshot.PHONE_ONLY,
    initialPreferences: StudyAudioPreferences = StudyAudioPreferences()
) {
    private val tag = "StudyAudioRoute"

    /** Why a re-resolution happened. Only a user action is allowed to cut a live turn short. */
    private enum class Trigger { INITIAL, DEVICES, PREFERENCE, USER_OVERRIDE }

    /**
     * Guards [snapshot], [prefs], [phoneOverride], [generation] and [initialised].
     *
     * Resolutions run on the collector coroutine while user actions
     * ([continueOnPhone], [useHeadsetNow], [onSafeTurnBoundary],
     * [resetSessionOverrides]) arrive on arbitrary threads — every state
     * transition below holds this lock, and [recompute] requires it.
     */
    private val stateLock = Any()

    private var snapshot: AudioRouteSnapshot = initialSnapshot
    private var prefs: StudyAudioPreferences = initialPreferences

    /** Session-scoped "stay on the phone" override set by *Continue on phone* (§38/§96). */
    private var phoneOverride: Boolean = false

    private var generation: Long = 0L
    private var initialised: Boolean = false

    private val _effectiveRoute = MutableStateFlow(
        resolver.resolve(initialPreferences.mode, initialSnapshot, generation = 0L)
    )

    /** The route the voice loop is using right now. */
    val effectiveRoute: StateFlow<EffectiveStudyAudioRoute> = _effectiveRoute.asStateFlow()

    private val _pendingRoute = MutableStateFlow<EffectiveStudyAudioRoute?>(null)

    /**
     * The route that will be applied at the next safe turn boundary (§41). Non-null means
     * "something better is available but a turn is in flight".
     */
    val pendingRoute: StateFlow<EffectiveStudyAudioRoute?> = _pendingRoute.asStateFlow()

    private val _attention = MutableStateFlow<StudyAudioAttention?>(null)

    /** User-facing recovery prompt after an unexpected headset loss (§96/§118). */
    val attention: StateFlow<StudyAudioAttention?> = _attention.asStateFlow()

    private val _events = MutableSharedFlow<StudyAudioRouteEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<StudyAudioRouteEvent> = _events.asSharedFlow()

    private val _headsetRouteActive = MutableStateFlow(_effectiveRoute.value.usesExternalHeadsetOutput)

    /**
     * True when the *effective output* is an external headset. This — not raw
     * `isHeadsetConnected` — is what the TTS pipeline consults before treating a disconnect
     * as a route loss (§44/§115/§119).
     */
    val headsetRouteActive: StateFlow<Boolean> = _headsetRouteActive.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        // A single collector: the two input flows used to be collected on two coroutines
        // that raced on the plain state above (torn snapshot/prefs pairs, double first
        // resolution). combine() keeps the trigger semantics — a device change resolves
        // as DEVICES, an explicit mode change as PREFERENCE — while making every
        // resolution atomic and emission-ordered.
        scope.launch {
            combine(snapshots, preferences) { snap, pref -> snap to pref }.collect { (incomingSnapshot, incomingPrefs) ->
                synchronized(stateLock) {
                    val snapshotChanged = !initialised || incomingSnapshot != snapshot
                    snapshot = incomingSnapshot
                    val modeChanged = incomingPrefs.mode != prefs.mode
                    prefs = incomingPrefs
                    when {
                        snapshotChanged -> recompute(Trigger.DEVICES)
                        modeChanged -> {
                            // An explicit settings change is an explicit user intent: it wins over a
                            // previous "continue on phone" fallback.
                            phoneOverride = false
                            recompute(Trigger.PREFERENCE)
                        }
                        // A non-mode preference edit changes nothing about the route.
                        else -> Unit
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Re-resolves the effective route from [snapshot] + [prefs].
     *
     * Callers must hold [stateLock]: the collector takes it around each emission and the
     * user-action entry points take it around the whole action, so a resolution always sees
     * one consistent input pair.
     */
    private fun recompute(trigger: Trigger) {
        // "Continue on phone" is a hold, not a permanent mode change: it is released as soon as
        // headphones are available again. The switch itself still waits for a safe boundary
        // (§39/§41), so the user never hears the route change mid-question.
        if (phoneOverride && snapshot.headsetOutputAvailable) {
            AppLogger.i(tag, "Headphones available again; releasing continue-on-phone hold")
            phoneOverride = false
        }
        val desiredPreference = if (phoneOverride) StudyAudioMode.PHONE else prefs.mode
        val candidate = resolver.resolve(desiredPreference, snapshot, generation = generation)
        val current = _effectiveRoute.value

        if (!initialised) {
            // First resolution: this is the environment the app starts in. Starting with no
            // headset is Phone Mode, not a loss (§44/§93).
            initialised = true
            generation += 1
            apply(candidate.copy(generation = generation))
            if (candidate.readiness.isBlocked) {
                metrics.recordBlockedStart()
                _events.tryEmit(StudyAudioRouteEvent.RouteBlocked(candidate))
            }
            return
        }

        if (sameRoute(current, candidate)) {
            // Labels, certainty and the "phone with headset attached" hint may still have
            // changed — keep the UI honest without pretending the route moved.
            if (current != candidate) apply(candidate.copy(generation = generation))
            _pendingRoute.value = null
            return
        }

        if (candidate.readiness.isBlocked) {
            generation += 1
            val applied = candidate.copy(generation = generation)
            apply(applied)
            metrics.recordBlockedStart()
            _events.tryEmit(StudyAudioRouteEvent.RouteBlocked(applied))
            return
        }

        // Loss = the external device that was in use is *gone from the device list*, not
        // merely "the effective route no longer uses a headset" (§44). Switching the preference
        // to Phone while headphones are still connected therefore never raises a disconnect
        // prompt or a loss metric.
        val headsetDeviceGone = current.usesExternalHeadsetOutput && !snapshot.headsetOutputAvailable
        if (headsetDeviceGone) {
            // A loss is the one case that *must* switch immediately: the device is gone.
            generation += 1
            val applied = candidate.copy(generation = generation)
            apply(applied)
            metrics.recordHeadsetLoss()
            metrics.recordRouteChange()
            AppLogger.i(tag, "External headset lost; route now ${applied.statusLabel}")
            if (prefs.disconnectPolicy == StudyAudioDisconnectPolicy.PAUSE_VOICE) {
                _attention.value = StudyAudioAttention(
                    message = "Headphones disconnected. Voice study is paused.",
                    canContinueOnPhone = true,
                    canWaitForHeadset = true,
                    canUseHeadsetNow = false
                )
            } else {
                _attention.value = null
            }
            _events.tryEmit(
                StudyAudioRouteEvent.ExternalHeadsetLost(
                    previous = current,
                    newRoute = applied,
                    atSafeBoundary = false,
                    policy = prefs.disconnectPolicy
                )
            )
            return
        }

        val gainedHeadsetOutput = candidate.usesExternalHeadsetOutput && !current.usesExternalHeadsetOutput
        val explicitUserChange = trigger == Trigger.PREFERENCE || trigger == Trigger.USER_OVERRIDE

        when {
            gainedHeadsetOutput -> {
                if (explicitUserChange) {
                    generation += 1
                    val applied = candidate.copy(generation = generation)
                    apply(applied)
                    metrics.recordRouteChange()
                    _events.tryEmit(StudyAudioRouteEvent.RouteChanged(current, applied, atSafeBoundary = false))
                } else {
                    // Never change the output device in the middle of a question/answer
                    // (§39/§40/§41/§94/§95): remember it and wait for the turn boundary.
                    _pendingRoute.value = candidate
                    _events.tryEmit(StudyAudioRouteEvent.ExternalHeadsetConnected(candidate, applied = false))
                }
            }

            explicitUserChange -> {
                generation += 1
                val applied = candidate.copy(generation = generation)
                apply(applied)
                metrics.recordRouteChange()
                _events.tryEmit(StudyAudioRouteEvent.RouteChanged(current, applied, atSafeBoundary = false))
            }

            else -> {
                // e.g. speaker → earpiece, or a different headset family while a turn is live.
                _pendingRoute.value = candidate
                metrics.recordRouteChange()
                _events.tryEmit(StudyAudioRouteEvent.RouteChanged(current, candidate, atSafeBoundary = false))
            }
        }
    }

    private fun apply(route: EffectiveStudyAudioRoute) {
        _effectiveRoute.value = route
        _headsetRouteActive.value = route.usesExternalHeadsetOutput
    }

    /** Two routes are "the same" when they resolve to the same device pair and readiness. */
    private fun sameRoute(a: EffectiveStudyAudioRoute, b: EffectiveStudyAudioRoute): Boolean =
        a.output == b.output &&
            a.input == b.input &&
            a.effective == b.effective &&
            a.readiness == b.readiness

    // ------------------------------------------------------------------ public controls

    /**
     * Promote a pending preferred route. Called by the study machine at turn boundaries (§40):
     * before the next question, after a rating is saved, and while paused — never mid-turn.
     */
    fun onSafeTurnBoundary() {
        synchronized(stateLock) {
            val pending = _pendingRoute.value ?: return
            _pendingRoute.value = null
            val current = _effectiveRoute.value
            if (sameRoute(current, pending)) return
            generation += 1
            val applied = pending.copy(generation = generation)
            apply(applied)
            metrics.recordRouteChange()
            AppLogger.i(tag, "Applying preferred route at turn boundary: ${applied.statusLabel}")
            _events.tryEmit(StudyAudioRouteEvent.RouteChanged(current, applied, atSafeBoundary = true))
        }
    }

    /**
     * The user chose *Continue on phone* (§38/§96/§118). Cancels the "waiting for headphones"
     * state, pins the phone route for the rest of the session and reports the switch so the
     * study layer can repeat the current question exactly once.
     */
    fun continueOnPhone() {
        synchronized(stateLock) {
            if (phoneOverride && !snapshot.externalAudioPresent) return
            phoneOverride = true
            _attention.value = null
            recompute(Trigger.USER_OVERRIDE)
        }
    }

    /** Manual *Use headphones now* (§42): immediate switch, the caller repeats the question. */
    fun useHeadsetNow(): Boolean {
        synchronized(stateLock) {
            if (!snapshot.headsetOutputAvailable) return false
            phoneOverride = false
            _attention.value = null
            recompute(Trigger.USER_OVERRIDE)
            return true
        }
    }

    /** *Wait for headphones* — hides the prompt; the session stays paused until the user acts. */
    fun dismissAttention() {
        _attention.value = null
    }

    /** Drop session-scoped overrides (called when a session ends). */
    fun resetSessionOverrides() {
        synchronized(stateLock) {
            if (!phoneOverride && _attention.value == null) return
            phoneOverride = false
            _attention.value = null
            recompute(Trigger.DEVICES)
        }
    }

    fun currentSnapshot(): AudioRouteSnapshot = synchronized(stateLock) { snapshot }

    /** Compact row for Diagnostics (§67). */
    fun diagnosticsRows(): List<Pair<String, String>> {
        val route = _effectiveRoute.value
        val m = metrics.metrics.value
        // Snapshot the plain inputs under the lock so a torn pair is never reported.
        val (mode, externalPresent, policy) = synchronized(stateLock) {
            Triple(prefs.mode, snapshot.externalAudioPresent, prefs.disconnectPolicy)
        }
        return listOf(
            "Study audio mode" to mode.displayName,
            "Effective mode" to when (route.effective) {
                EffectiveStudyAudioMode.HEADSET -> "Headset"
                EffectiveStudyAudioMode.HYBRID -> "Headphones + phone mic"
                EffectiveStudyAudioMode.PHONE -> "Phone"
                EffectiveStudyAudioMode.BLOCKED -> "Blocked (headphones required)"
                EffectiveStudyAudioMode.UNKNOWN -> "Unknown"
            },
            "Output route" to "${route.outputLabel} (${route.output.name})",
            "Input route" to "${route.inputLabel} (${route.input.name})",
            "External headset" to if (externalPresent) "Connected" else "Not connected",
            "Route certainty" to route.certainty.name.lowercase().replaceFirstChar { it.uppercase() },
            "Acoustic profile" to route.acousticProfileLabel,
            "Pending route" to (_pendingRoute.value?.statusLabel ?: "None"),
            "Disconnect policy" to policy.displayName,
            "Route generation" to route.generation.toString(),
            "Route changes" to m.routeChanges.toString(),
            "Headset loss events" to m.headsetLossEvents.toString(),
            "Phone-mode turns" to m.phoneTurns.toString(),
            "Headset-mode turns" to m.headsetTurns.toString(),
            "Avg handoff latency" to if (m.avgHandoffLatencyMs < 0) "-" else "${m.avgHandoffLatencyMs}ms",
            "Suspected self-echo" to m.suggestedSelfEcho.toString(),
            "No speech / no match" to "${m.sttNoSpeech} / ${m.sttNoMatch}",
            "STT timeouts" to m.sttTimeouts.toString(),
            "Mic-unavailable skips" to m.micUnavailableSkips.toString()
        )
    }
}
