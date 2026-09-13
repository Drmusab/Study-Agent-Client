package com.studyagent.client.core.audio

/**
 * Study audio architecture — pure models (no Android APIs).
 *
 * The whole point of this file: headphones are an **enhancement**, not a dependency.
 * A user with only a phone must be able to press Start, hear the question through the
 * phone speaker, answer through the built-in microphone and continue hands-free.
 *
 * Layer responsibilities (§112):
 *  - [AudioRouteManager] (Android side) reports **facts** about the devices present.
 *  - [AudioRouteSnapshot] is those facts expressed as pure data.
 *  - [StudyAudioModeResolver] **chooses** the effective study route from a user preference
 *    plus a snapshot. Pure logic, no Android, trivially testable.
 *  - [StudyAudioRouteCoordinator] applies that choice over time (loss detection, pending
 *    route changes at safe boundaries, user overrides).
 *  - The study session only ever asks to "speak" or "listen"; it never branches on
 *    "is a headset connected".
 */

// ---------------------------------------------------------------------------- preference

/**
 * User-facing audio preference (§3/§7). Persisted by name; unknown values fall back to
 * [AUTO] so an older/newer build never crashes on a settings file it does not understand.
 */
enum class StudyAudioMode {
    /** Use headphones when they are available, otherwise the phone. The default. */
    AUTO,

    /** Like [AUTO], but the user explicitly prefers the headset path when both exist. */
    HEADSET_PREFERRED,

    /** Explicitly phone speaker + built-in microphone, even with headphones connected. */
    PHONE,

    /** Advanced privacy mode: refuse to start voice study without headphones. Never default. */
    HEADSET_REQUIRED;

    val displayName: String
        get() = when (this) {
            AUTO -> "Automatic"
            HEADSET_PREFERRED -> "Prefer Headphones"
            PHONE -> "Phone"
            HEADSET_REQUIRED -> "Headphones Required"
        }

    val description: String
        get() = when (this) {
            AUTO -> "Use headphones when available, otherwise use the phone."
            HEADSET_PREFERRED -> "Fall back to the phone if headphones are unavailable."
            PHONE -> "Use the phone speaker and microphone."
            HEADSET_REQUIRED -> "Do not start voice study without headphones."
        }

    companion object {
        val DEFAULT: StudyAudioMode = AUTO

        /** Tolerant parse for DataStore reads (§78): never throws, always yields a mode. */
        fun fromStorage(raw: String?): StudyAudioMode {
            if (raw.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: DEFAULT
        }
    }
}

// ---------------------------------------------------------------------------- route words

/** Which physical path the study audio *output* should take. */
enum class StudyOutputRoute {
    BLUETOOTH,
    WIRED,
    USB,
    PHONE_SPEAKER,
    PHONE_EARPIECE,
    /** We could not pin the device down — Android picks. Not an error. */
    SYSTEM_SELECTED,
    UNKNOWN;

    val isExternalHeadset: Boolean
        get() = this == BLUETOOTH || this == WIRED || this == USB

    val isPhoneDevice: Boolean
        get() = this == PHONE_SPEAKER || this == PHONE_EARPIECE
}

/** Which physical path the study audio *input* should take. */
enum class StudyInputRoute {
    BLUETOOTH_MIC,
    WIRED_MIC,
    USB_MIC,
    BUILTIN_MIC,
    /** A microphone exists but the platform will decide which one. */
    SYSTEM_SELECTED,
    UNKNOWN;

    val isExternalHeadsetMic: Boolean
        get() = this == BLUETOOTH_MIC || this == WIRED_MIC || this == USB_MIC

    val isUsable: Boolean
        get() = this != UNKNOWN
}

/**
 * How sure we are about a route. Android does not publish which input a `SpeechRecognizer`
 * actually used, so a route inferred from the device list is [LIKELY], not [CONFIRMED].
 */
enum class RouteCertainty { CONFIRMED, LIKELY, UNKNOWN }

/**
 * Effective study mode — **derived**, never persisted (§69).
 *
 * [HYBRID] is the common real-world case that a naive `headset ? HEADSET : PHONE` model gets
 * wrong: headphones for output (A2DP, no microphone profile) plus the phone's built-in
 * microphone for input (§73/§74/§75).
 */
enum class EffectiveStudyAudioMode {
    HEADSET,
    HYBRID,
    PHONE,
    /** Preference cannot be satisfied (e.g. `HEADSET_REQUIRED` with no headset). */
    BLOCKED,
    UNKNOWN;

    val isPhoneExperience: Boolean get() = this == PHONE
    val usesExternalAudio: Boolean get() = this == HEADSET || this == HYBRID
}

/**
 * Acoustic behaviour class of the current output. The phone speaker leaks the app's own
 * speech into the microphone in a way that headphones do not, so the TTS→STT handoff needs a
 * longer, route-specific guard (§15/§16).
 */
enum class AcousticProfile { HEADSET, PHONE_SPEAKER }

/** External output device families we recognise. */
enum class HeadsetOutputKind { BLUETOOTH, WIRED, USB }

/** External microphone device families we recognise. */
enum class HeadsetMicKind { BLUETOOTH_MIC, WIRED_MIC, USB_MIC }

/**
 * Whether the resolved route can actually run a spoken study turn.
 *
 * [OUTPUT_ONLY] is deliberately *not* an error (§87/§88): the question can still be spoken
 * and answered with the on-screen controls. Only [BLOCKED] means "do not start voice study",
 * and that only happens when the user asked for `HEADSET_REQUIRED` (§7) or the device has no
 * usable output at all.
 */
enum class StudyAudioReadiness {
    READY,
    OUTPUT_ONLY,
    BLOCKED;

    val isBlocked: Boolean get() = this == BLOCKED
}

// ---------------------------------------------------------------------------- preferences

/**
 * What to do when headphones disappear **mid-session** (§35/§37/§38).
 *
 * This is deliberately *not* the same question as "there is no headset" — a session that
 * starts on the phone never triggers this policy at all (§36/§43/§93).
 */
enum class StudyAudioDisconnectPolicy {
    /** Default: stop talking immediately and ask how to continue. Privacy-preserving. */
    PAUSE_VOICE,

    /** Cancel the interrupted turn, continue on the phone, repeat the current question. */
    CONTINUE_ON_PHONE;

    val displayName: String
        get() = when (this) {
            PAUSE_VOICE -> "Pause voice study"
            CONTINUE_ON_PHONE -> "Continue on phone"
        }
}

/** Everything the resolver needs from user settings, as one immutable value. */
data class StudyAudioPreferences(
    val mode: StudyAudioMode = StudyAudioMode.DEFAULT,
    val disconnectPolicy: StudyAudioDisconnectPolicy = StudyAudioDisconnectPolicy.PAUSE_VOICE
)

// ---------------------------------------------------------------------------- snapshot

/**
 * Pure description of the audio devices currently available (§9/§70).
 *
 * Built by the Android layer ([AndroidAudioRouteManager]) and consumed by
 * [StudyAudioModeResolver]. Defaults describe a bare phone, because that is the environment
 * where getting this wrong used to block study entirely.
 */
data class AudioRouteSnapshot(
    /** Monotonic revision; bumped every time the device sets are re-enumerated. */
    val revision: Long = 0L,

    val headsetOutput: HeadsetOutputKind? = null,
    val headsetOutputLabel: String? = null,
    val headsetMic: HeadsetMicKind? = null,
    val headsetMicLabel: String? = null,

    val builtInSpeakerAvailable: Boolean = true,
    val builtInEarpieceAvailable: Boolean = false,
    val builtInMicAvailable: Boolean = true,

    val outputCertainty: RouteCertainty = RouteCertainty.CONFIRMED,
    val inputCertainty: RouteCertainty = RouteCertainty.LIKELY
) {
    val headsetOutputAvailable: Boolean get() = headsetOutput != null
    val headsetMicAvailable: Boolean get() = headsetMic != null

    /** Any external audio device present (output or microphone). */
    val externalAudioPresent: Boolean get() = headsetOutputAvailable || headsetMicAvailable

    val hasUsableOutput: Boolean
        get() = headsetOutputAvailable || builtInSpeakerAvailable || builtInEarpieceAvailable

    /**
     * A microphone the recognizer can use — **including the built-in one** (§11/§45).
     * This is the condition STT needs; `hasExternalMicrophone` is not, and never was.
     */
    val hasUsableMicrophone: Boolean get() = headsetMicAvailable || builtInMicAvailable

    companion object {
        /** A bare phone: speaker + built-in mic, nothing else (§71/§90). */
        val PHONE_ONLY = AudioRouteSnapshot()

        /** Bluetooth headset with both profiles available (§72). */
        val BLUETOOTH_HEADSET = AudioRouteSnapshot(
            headsetOutput = HeadsetOutputKind.BLUETOOTH,
            headsetOutputLabel = "Bluetooth headset",
            headsetMic = HeadsetMicKind.BLUETOOTH_MIC,
            headsetMicLabel = "Bluetooth headset microphone"
        )

        /** A2DP output only, no headset microphone: the hybrid case (§73/§100). */
        val OUTPUT_ONLY_HEADPHONES = AudioRouteSnapshot(
            headsetOutput = HeadsetOutputKind.BLUETOOTH,
            headsetOutputLabel = "Bluetooth headphones",
            headsetMic = null
        )

        val WIRED_HEADSET = AudioRouteSnapshot(
            headsetOutput = HeadsetOutputKind.WIRED,
            headsetOutputLabel = "Wired headset",
            headsetMic = HeadsetMicKind.WIRED_MIC,
            headsetMicLabel = "Wired headset microphone"
        )
    }
}

// ---------------------------------------------------------------------------- effective route

/**
 * The resolved answer to "how should voice study run right now?" (§9/§70).
 *
 * Studied logic depends on this value — never on `isHeadsetConnected` alone (§119).
 */
data class EffectiveStudyAudioRoute(
    /** What the user asked for. */
    val preference: StudyAudioMode,

    /** What we will actually do. */
    val effective: EffectiveStudyAudioMode,

    val output: StudyOutputRoute,
    val input: StudyInputRoute,
    val certainty: RouteCertainty,

    val outputLabel: String,
    val inputLabel: String,

    val acousticProfile: AcousticProfile,
    val readiness: StudyAudioReadiness,

    /** Bumped whenever the effective output/input pair changes. */
    val generation: Long = 0L,

    /** Human-readable, non-alarming explanation ("Using the phone speaker and microphone."). */
    val reason: String? = null,

    /** True when the user asked for Phone mode while a headset is attached. */
    val phonePreferredWithHeadset: Boolean = false
) {
    /** Headphone output paired with a different (usually phone) microphone. */
    val isHybrid: Boolean get() = effective == EffectiveStudyAudioMode.HYBRID

    /** The output path is an external headset — used for TTS route-loss policy. */
    val usesExternalHeadsetOutput: Boolean get() = output.isExternalHeadset

    /** Voice study may be started (session start gate, §7/§82). */
    val canStartVoiceStudy: Boolean get() = !readiness.isBlocked

    /**
     * A microphone the recognizer can actually use (§11).
     *
     * Requires a fully ready route — `SYSTEM_SELECTED` means "a microphone may exist", which
     * is not the same as "we know one does", and the turn gate must not open the microphone on
     * a guess.
     */
    val hasUsableMicrophone: Boolean get() = readiness == StudyAudioReadiness.READY

    /** Short status text for the dashboard (§32/§80). */
    val statusLabel: String
        get() = when (effective) {
            EffectiveStudyAudioMode.HEADSET -> "Headset"
            EffectiveStudyAudioMode.HYBRID -> "Headphones + phone mic"
            EffectiveStudyAudioMode.PHONE -> "Phone"
            EffectiveStudyAudioMode.BLOCKED -> "Headphones required"
            EffectiveStudyAudioMode.UNKNOWN -> "Unknown"
        }

    /** Emoji-prefixed compact status for one-line UI rows. */
    val statusLabelWithIcon: String
        get() = when (effective) {
            EffectiveStudyAudioMode.HEADSET -> "🎧 $statusLabel"
            EffectiveStudyAudioMode.HYBRID -> "🎧 $statusLabel"
            EffectiveStudyAudioMode.PHONE -> "📱 $statusLabel"
            EffectiveStudyAudioMode.BLOCKED -> "🔒 $statusLabel"
            EffectiveStudyAudioMode.UNKNOWN -> "❔ $statusLabel"
        }

    /** Study-screen one-liner: "Phone speaker • Built-in mic" (§81). */
    val compactSummary: String get() = "$outputLabel • $inputLabel"

    val acousticProfileLabel: String
        get() = when (acousticProfile) {
            AcousticProfile.HEADSET -> "Headset"
            AcousticProfile.PHONE_SPEAKER -> "Phone Speaker"
        }
}

// ---------------------------------------------------------------------------- route events

/**
 * Route facts that the voice layer and the study session care about.
 *
 * Note what is *not* here: "the app started without a headset". That is not an event — it is
 * simply the initial route (§44/§93/§119). A loss requires that an external route was
 * previously **in use**.
 */
sealed interface StudyAudioRouteEvent {
    /** Effective route changed (immediately, or at a safe boundary when it was deferred). */
    data class RouteChanged(
        val from: EffectiveStudyAudioRoute,
        val to: EffectiveStudyAudioRoute,
        val atSafeBoundary: Boolean
    ) : StudyAudioRouteEvent

    /**
     * An external output that was **in use** disappeared. This event cannot be produced by an
     * app that started without a headset (§44/§93).
     */
    data class ExternalHeadsetLost(
        val previous: EffectiveStudyAudioRoute,
        val newRoute: EffectiveStudyAudioRoute,
        val atSafeBoundary: Boolean,
        val policy: StudyAudioDisconnectPolicy
    ) : StudyAudioRouteEvent

    /** An external device appeared; the switch may be deferred to a safe boundary (§39/§41). */
    data class ExternalHeadsetConnected(
        val newRoute: EffectiveStudyAudioRoute,
        val applied: Boolean
    ) : StudyAudioRouteEvent

    /** The preference cannot be satisfied right now (only `HEADSET_REQUIRED`). */
    data class RouteBlocked(val route: EffectiveStudyAudioRoute) : StudyAudioRouteEvent
}

/**
 * A user-facing prompt raised after an *unexpected* headset loss while studying (§36/§96/§118).
 * Phone Mode itself never raises this — there is no headset to lose.
 */
data class StudyAudioAttention(
    val message: String,
    val canContinueOnPhone: Boolean = true,
    val canWaitForHeadset: Boolean = true,
    val canUseHeadsetNow: Boolean = false
)
