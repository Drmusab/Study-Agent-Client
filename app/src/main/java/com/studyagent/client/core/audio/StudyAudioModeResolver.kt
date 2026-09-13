package com.studyagent.client.core.audio

/**
 * Chooses the effective study audio route from a user preference plus the available devices
 * (§70). **Pure logic — no Android APIs, no coroutines, no clock.** Everything the resolver
 * needs is in [AudioRouteSnapshot], which is why the full policy matrix (§29) is testable
 * without a device.
 */
interface StudyAudioModeResolver {

    fun resolve(
        preference: StudyAudioMode,
        routeSnapshot: AudioRouteSnapshot,
        generation: Long = 0L
    ): EffectiveStudyAudioRoute
}

/**
 * Deterministic implementation of the policy matrix (§29).
 *
 * | Preference         | Headset present | Output                  | Input                    |
 * |--------------------|-----------------|-------------------------|--------------------------|
 * | AUTO               | yes             | headset                 | headset mic / phone mic  |
 * | AUTO               | no              | phone speaker           | built-in / system        |
 * | HEADSET_PREFERRED  | yes             | headset                 | headset mic / phone mic  |
 * | HEADSET_PREFERRED  | no              | phone speaker           | built-in / system        |
 * | PHONE              | any             | phone speaker/earpiece  | built-in / system        |
 * | HEADSET_REQUIRED   | yes             | headset                 | headset mic / phone mic  |
 * | HEADSET_REQUIRED   | no              | blocked                 | –                        |
 *
 * Two rules that used to be broken elsewhere in the app and are enforced here:
 *  1. The built-in microphone is a first-class input (§2/§10/§46), never "degraded".
 *  2. Input and output may come from different physical devices (§73/§76) — the resolver
 *     resolves them independently and reports the combination honestly as [HYBRID].
 */
class DefaultStudyAudioModeResolver : StudyAudioModeResolver {

    override fun resolve(
        preference: StudyAudioMode,
        routeSnapshot: AudioRouteSnapshot,
        generation: Long
    ): EffectiveStudyAudioRoute {
        return when (preference) {
            StudyAudioMode.PHONE -> phoneRoute(preference, routeSnapshot, generation)

            StudyAudioMode.HEADSET_REQUIRED -> {
                if (routeSnapshot.headsetOutputAvailable) {
                    headsetRoute(preference, routeSnapshot, generation)
                } else {
                    blockedRoute(routeSnapshot, generation)
                }
            }

            StudyAudioMode.AUTO,
            StudyAudioMode.HEADSET_PREFERRED -> {
                // Both preferences behave identically when a headset exists; the difference is
                // only in how eagerly the app keeps it, which is a switching concern handled by
                // the coordinator, not a resolution concern.
                if (routeSnapshot.headsetOutputAvailable) {
                    headsetRoute(preference, routeSnapshot, generation)
                } else {
                    phoneRoute(preference, routeSnapshot, generation)
                }
            }
        }
    }

    // ------------------------------------------------------------------ resolutions

    private fun headsetRoute(
        preference: StudyAudioMode,
        snapshot: AudioRouteSnapshot,
        generation: Long
    ): EffectiveStudyAudioRoute {
        val output = snapshot.headsetOutput.toStudyOutputRoute()
        val headsetMicRoute = snapshot.headsetMic.toStudyInputRoute()
        val input = headsetMicRoute ?: if (snapshot.builtInMicAvailable) {
            StudyInputRoute.BUILTIN_MIC
        } else {
            StudyInputRoute.UNKNOWN
        }

        // Headphones for output, phone microphone for input: common with A2DP-only headsets
        // and entirely valid (§73/§74/§75).
        val hybrid = input == StudyInputRoute.BUILTIN_MIC
        val effective = if (hybrid) {
            EffectiveStudyAudioMode.HYBRID
        } else {
            EffectiveStudyAudioMode.HEADSET
        }

        val readiness = if (input.isUsable) {
            StudyAudioReadiness.READY
        } else {
            StudyAudioReadiness.OUTPUT_ONLY
        }

        val inputLabel = when (input) {
            StudyInputRoute.BUILTIN_MIC -> "Built-in microphone"
            StudyInputRoute.SYSTEM_SELECTED -> "System-selected microphone"
            StudyInputRoute.BLUETOOTH_MIC -> snapshot.headsetMicLabel ?: "Bluetooth headset microphone"
            StudyInputRoute.WIRED_MIC -> snapshot.headsetMicLabel ?: "Wired headset microphone"
            StudyInputRoute.USB_MIC -> snapshot.headsetMicLabel ?: "USB headset microphone"
            StudyInputRoute.UNKNOWN -> "No usable microphone"
        }

        return EffectiveStudyAudioRoute(
            preference = preference,
            effective = effective,
            output = output,
            input = input,
            certainty = weakest(snapshot.outputCertainty, snapshot.inputCertainty),
            outputLabel = snapshot.headsetOutputLabel ?: defaultOutputLabel(output),
            inputLabel = inputLabel,
            // Acoustic isolation follows the *output*: headphones keep the app's own speech
            // out of the microphone even when the input is the phone's built-in one.
            acousticProfile = AcousticProfile.HEADSET,
            readiness = readiness,
            generation = generation,
            reason = if (hybrid) {
                "Headphones for audio, phone microphone for answers."
            } else {
                null
            }
        )
    }

    private fun phoneRoute(
        preference: StudyAudioMode,
        snapshot: AudioRouteSnapshot,
        generation: Long
    ): EffectiveStudyAudioRoute {
        val output = when {
            snapshot.builtInSpeakerAvailable -> StudyOutputRoute.PHONE_SPEAKER
            snapshot.builtInEarpieceAvailable -> StudyOutputRoute.PHONE_EARPIECE
            else -> StudyOutputRoute.SYSTEM_SELECTED
        }

        val input = if (snapshot.builtInMicAvailable) {
            StudyInputRoute.BUILTIN_MIC
        } else {
            // No built-in microphone enumerated: let Android decide rather than claiming
            // there is none (§21). The external mic, if any, is a valid fallback.
            if (snapshot.headsetMicAvailable) {
                snapshot.headsetMic.toStudyInputRoute() ?: StudyInputRoute.SYSTEM_SELECTED
            } else {
                StudyInputRoute.SYSTEM_SELECTED
            }
        }

        val readiness = when {
            !snapshot.hasUsableOutput -> StudyAudioReadiness.BLOCKED
            !snapshot.hasUsableMicrophone -> StudyAudioReadiness.OUTPUT_ONLY
            else -> StudyAudioReadiness.READY
        }

        // Honest certainty: with an external headset attached, Android is free to keep media
        // on it, and it may also route the recognizer to the headset microphone. The app does
        // not force routing for Phone mode (§22), so the resulting route is *likely*.
        val certainty = if (snapshot.externalAudioPresent) {
            RouteCertainty.LIKELY
        } else {
            weakest(snapshot.outputCertainty, snapshot.inputCertainty)
        }

        return EffectiveStudyAudioRoute(
            preference = preference,
            effective = EffectiveStudyAudioMode.PHONE,
            output = output,
            input = input,
            certainty = certainty,
            outputLabel = when (output) {
                StudyOutputRoute.PHONE_SPEAKER -> "Phone speaker"
                StudyOutputRoute.PHONE_EARPIECE -> "Phone earpiece"
                else -> "System-selected output"
            },
            inputLabel = when (input) {
                StudyInputRoute.BUILTIN_MIC -> "Built-in microphone"
                StudyInputRoute.BLUETOOTH_MIC -> snapshot.headsetMicLabel ?: "Bluetooth headset microphone"
                StudyInputRoute.WIRED_MIC -> snapshot.headsetMicLabel ?: "Wired headset microphone"
                StudyInputRoute.USB_MIC -> snapshot.headsetMicLabel ?: "USB headset microphone"
                StudyInputRoute.SYSTEM_SELECTED -> "System-selected microphone"
                StudyInputRoute.UNKNOWN -> "No usable microphone"
            },
            acousticProfile = if (output == StudyOutputRoute.PHONE_SPEAKER) {
                AcousticProfile.PHONE_SPEAKER
            } else {
                AcousticProfile.HEADSET
            },
            readiness = readiness,
            generation = generation,
            reason = if (snapshot.externalAudioPresent) {
                "Phone audio selected while a headset is connected."
            } else {
                null
            },
            phonePreferredWithHeadset = snapshot.externalAudioPresent
        )
    }

    private fun blockedRoute(
        snapshot: AudioRouteSnapshot,
        generation: Long
    ): EffectiveStudyAudioRoute = EffectiveStudyAudioRoute(
        preference = StudyAudioMode.HEADSET_REQUIRED,
        effective = EffectiveStudyAudioMode.BLOCKED,
        output = StudyOutputRoute.UNKNOWN,
        input = StudyInputRoute.UNKNOWN,
        certainty = RouteCertainty.UNKNOWN,
        outputLabel = "Not routed",
        inputLabel = "Not routed",
        acousticProfile = AcousticProfile.HEADSET,
        readiness = StudyAudioReadiness.BLOCKED,
        generation = generation,
        reason = "Headphones required: connect headphones, or switch Audio Mode to " +
            "Automatic or Phone in Settings.",
        phonePreferredWithHeadset = snapshot.externalAudioPresent
    )

    // ------------------------------------------------------------------ helpers

    /** The lower of two certainties — the honest answer for a route built from two devices. */
    private fun weakest(a: RouteCertainty, b: RouteCertainty): RouteCertainty {
        fun rank(c: RouteCertainty): Int = when (c) {
            RouteCertainty.CONFIRMED -> 2
            RouteCertainty.LIKELY -> 1
            RouteCertainty.UNKNOWN -> 0
        }
        return if (rank(a) <= rank(b)) a else b
    }

    private fun defaultOutputLabel(route: StudyOutputRoute): String = when (route) {
        StudyOutputRoute.BLUETOOTH -> "Bluetooth headset"
        StudyOutputRoute.WIRED -> "Wired headset"
        StudyOutputRoute.USB -> "USB headset"
        StudyOutputRoute.PHONE_SPEAKER -> "Phone speaker"
        StudyOutputRoute.PHONE_EARPIECE -> "Phone earpiece"
        StudyOutputRoute.SYSTEM_SELECTED -> "System-selected output"
        StudyOutputRoute.UNKNOWN -> "Unknown output"
    }
}

// ---------------------------------------------------------------------- mapping helpers

internal fun HeadsetOutputKind?.toStudyOutputRoute(): StudyOutputRoute = when (this) {
    HeadsetOutputKind.BLUETOOTH -> StudyOutputRoute.BLUETOOTH
    HeadsetOutputKind.WIRED -> StudyOutputRoute.WIRED
    HeadsetOutputKind.USB -> StudyOutputRoute.USB
    null -> StudyOutputRoute.PHONE_SPEAKER
}

internal fun HeadsetMicKind?.toStudyInputRoute(): StudyInputRoute? = when (this) {
    HeadsetMicKind.BLUETOOTH_MIC -> StudyInputRoute.BLUETOOTH_MIC
    HeadsetMicKind.WIRED_MIC -> StudyInputRoute.WIRED_MIC
    HeadsetMicKind.USB_MIC -> StudyInputRoute.USB_MIC
    null -> null
}
